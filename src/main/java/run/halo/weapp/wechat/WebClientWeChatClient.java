package run.halo.weapp.wechat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import run.halo.weapp.config.SettingsService;
import run.halo.weapp.config.WechatSettings;
import run.halo.weapp.error.ApiException;
import run.halo.weapp.error.ErrorCode;
import run.halo.weapp.security.MaskUtils;

/**
 * 基于 WebClient 的微信服务端 API 实现（https://api.weixin.qq.com）。
 *
 * <p>安全约束：AppSecret、access token、session_key、wx.login code、OpenID 明文
 * 绝不进日志；日志只记录 errcode、trace_id、suggest、label、耗时与 HMAC 用户标识。
 * 所有失败路径 fail-closed，统一映射 WECHAT_UNAVAILABLE，不泄露微信原始错误细节。</p>
 */
@Component
public class WebClientWeChatClient implements WeChatClient {

    private static final Logger log = LoggerFactory.getLogger(WebClientWeChatClient.class);

    private static final String BASE_URL = "https://api.weixin.qq.com";
    /** access token 缓存至过期前 5 分钟即刷新。 */
    private static final Duration REFRESH_MARGIN = Duration.ofMinutes(5);

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final WebClient webClient;
    private final SettingsService settings;
    private final MaskUtils maskUtils;

    private final AtomicReference<CachedToken> tokenCache = new AtomicReference<>();
    private final AtomicReference<Mono<String>> tokenInFlight = new AtomicReference<>();

    public WebClientWeChatClient(SettingsService settings, MaskUtils maskUtils) {
        this(WebClient.builder().baseUrl(BASE_URL).build(), settings, maskUtils);
    }

    /** 测试可见：注入指向假上游的 WebClient。 */
    WebClientWeChatClient(WebClient webClient, SettingsService settings, MaskUtils maskUtils) {
        this.webClient = webClient;
        this.settings = settings;
        this.maskUtils = maskUtils;
    }

    @Override
    public Mono<Code2SessionResult> code2Session(String code) {
        WechatSettings wechat = settings.wechat();
        return webClient.get()
            .uri(builder -> builder.path("/sns/jscode2session")
                .queryParam("appid", wechat.appId())
                .queryParam("secret", wechat.appSecret())
                .queryParam("js_code", code)
                .queryParam("grant_type", "authorization_code")
                .build())
            .retrieve()
            .bodyToMono(String.class)
            .timeout(timeout())
            .flatMap(WebClientWeChatClient::parseJson)
            .flatMap(json -> {
                int errcode = json.path("errcode").asInt(0);
                String openId = json.path("openid").asText(null);
                // session_key 字段读到即丢弃，不保存、不记录
                if (errcode != 0 || openId == null || openId.isBlank()) {
                    // 不记录 code 与微信原始 errmsg，只记数值型 errcode
                    log.warn("[weapp] code2Session failed errcode={}", errcode);
                    return Mono.error(loginUnavailable());
                }
                return Mono.just(new Code2SessionResult(openId));
            })
            .onErrorMap(t -> !(t instanceof ApiException), t -> {
                log.warn("[weapp] code2Session unavailable: {}", t.getClass().getSimpleName());
                return loginUnavailable();
            });
    }

    @Override
    public Mono<String> getAccessToken() {
        return Mono.defer(() -> {
            CachedToken cached = tokenCache.get();
            if (cached != null
                && cached.expiresAt().isAfter(Instant.now().plus(REFRESH_MARGIN))) {
                return Mono.just(cached.token());
            }
            return refreshTokenSingleFlight();
        });
    }

    /**
     * 单飞刷新：并发订阅共享同一个上游请求（cache 后的 Mono 放入
     * tokenInFlight，后到者直接复用）；失效错误（-1/40001/42001 等）
     * 清缓存后最多重试一次。
     */
    private Mono<String> refreshTokenSingleFlight() {
        return Mono.defer(() -> {
            Mono<String> existing = tokenInFlight.get();
            if (existing != null) {
                return existing;
            }
            AtomicReference<Mono<String>> self = new AtomicReference<>();
            // 失效错误（-1/40001/42001 等）清缓存后显式重试一次（最多 2 次上游请求）
            Mono<String> created = requestAccessToken()
                .onErrorResume(TokenFetchException.class, t -> {
                    tokenCache.set(null);
                    log.warn("[weapp] access token rejected, retry once");
                    return requestAccessToken();
                })
                .doOnNext(tokenCache::set)
                .map(CachedToken::token)
                .onErrorMap(TokenFetchException.class, t -> wechatUnavailable())
                .cache()
                .doFinally(signal -> tokenInFlight.compareAndSet(self.get(), null));
            self.set(created);
            if (tokenInFlight.compareAndSet(null, created)) {
                return created;
            }
            return tokenInFlight.get();
        });
    }

    private Mono<CachedToken> requestAccessToken() {
        WechatSettings wechat = settings.wechat();
        return webClient.get()
            .uri(builder -> builder.path("/cgi-bin/token")
                .queryParam("grant_type", "client_credential")
                .queryParam("appid", wechat.appId())
                .queryParam("secret", wechat.appSecret())
                .build())
            .retrieve()
            .bodyToMono(String.class)
            .timeout(timeout())
            .flatMap(WebClientWeChatClient::parseJson)
            .flatMap(json -> {
                int errcode = json.path("errcode").asInt(0);
                String token = json.path("access_token").asText(null);
                long expiresIn = json.path("expires_in").asLong(0);
                if (errcode != 0 || token == null || token.isBlank() || expiresIn <= 0) {
                    // 不记录 errmsg 与凭据，只记数值型 errcode
                    log.warn("[weapp] fetch access token failed errcode={}", errcode);
                    return Mono.error(new TokenFetchException(errcode));
                }
                return Mono.just(new CachedToken(token,
                    Instant.now().plusSeconds(expiresIn)));
            })
            .onErrorMap(t -> !(t instanceof TokenFetchException),
                t -> new TokenFetchException(-1));
    }

    @Override
    public Mono<SecCheckResult> msgSecCheck(String openId, String content) {
        String userTag = maskUtils.userTag(openId);
        long startNanos = System.nanoTime();
        return getAccessToken()
            .flatMap(token -> webClient.post()
                .uri(builder -> builder.path("/wxa/msg_sec_check")
                    .queryParam("access_token", token)
                    .build())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                    "version", 2,
                    "scene", 2,
                    "openid", openId,
                    "content", content))
                .retrieve()
                .bodyToMono(String.class)
                .timeout(timeout())
                .flatMap(WebClientWeChatClient::parseJson))
            .flatMap(json -> {
                long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
                String traceId = json.path("trace_id").asText("");
                int errcode = json.path("errcode").asInt(0);
                String suggest = json.path("result").path("suggest").asText("");
                int label = json.path("result").path("label").asInt(-1);
                if (errcode != 0) {
                    // errcode 61010（token 失效）等一律按检测不可用处理，失败关闭
                    log.warn("[weapp] msgSecCheck failed user={} errcode={} traceId={} "
                            + "elapsedMs={}", userTag, errcode, traceId, elapsedMs);
                    return Mono.error(wechatUnavailable());
                }
                SecCheckResult.Suggest parsed = switch (suggest) {
                    case "pass" -> SecCheckResult.Suggest.PASS;
                    case "review" -> SecCheckResult.Suggest.REVIEW;
                    case "risky" -> SecCheckResult.Suggest.RISKY;
                    default -> null;
                };
                if (parsed == null) {
                    // 未知 suggest 失败关闭，绝不放行
                    log.warn("[weapp] msgSecCheck unknown suggest user={} label={} "
                        + "traceId={} elapsedMs={}", userTag, label, traceId, elapsedMs);
                    return Mono.error(wechatUnavailable());
                }
                log.info("[weapp] msgSecCheck user={} suggest={} label={} traceId={} "
                    + "elapsedMs={}", userTag, suggest, label, traceId, elapsedMs);
                return Mono.just(new SecCheckResult(parsed, traceId));
            })
            .onErrorMap(t -> !(t instanceof ApiException), t -> {
                log.warn("[weapp] msgSecCheck unavailable user={} cause={}", userTag,
                    t.getClass().getSimpleName());
                return wechatUnavailable();
            });
    }

    private Duration timeout() {
        return Duration.ofMillis(settings.client().upstreamTimeoutMillis());
    }

    /** 解析上游 JSON；解析失败按上游不可用处理（由调用方的 onErrorMap 统一转换）。 */
    private static Mono<JsonNode> parseJson(String body) {
        return Mono.fromCallable(() -> OBJECT_MAPPER.readTree(body));
    }

    private static ApiException loginUnavailable() {
        return new ApiException(ErrorCode.WECHAT_UNAVAILABLE, "登录服务暂时不可用，请稍后重试");
    }

    private static ApiException wechatUnavailable() {
        return new ApiException(ErrorCode.WECHAT_UNAVAILABLE, "安全检测服务暂时不可用，请稍后重试");
    }

    private record CachedToken(String token, Instant expiresAt) {
    }

    /** access token 获取失败的内部标记，绝不携带凭据或 errmsg。 */
    private static final class TokenFetchException extends RuntimeException {

        private final int errcode;

        TokenFetchException(int errcode) {
            super("wechat token fetch failed, errcode=" + errcode);
            this.errcode = errcode;
        }

        int errcode() {
            return errcode;
        }
    }
}
