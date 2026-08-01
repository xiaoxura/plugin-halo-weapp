package run.halo.weapp.comment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import run.halo.app.infra.ExternalUrlSupplier;
import run.halo.weapp.config.SettingsService;
import run.halo.weapp.error.ApiException;
import run.halo.weapp.error.ErrorCode;

/**
 * 基于 loopback HTTP 的 Halo Public Comment API 网关（ADR-0001）。
 * baseUrl 来自 {@link ExternalUrlSupplier}（插件所在 Halo 实例的站点地址），
 * 匿名游客身份写入，不持有 PAT、不伪造管理员身份。
 *
 * <p>请求体固定构造：{@code {raw, content, allowNotification:false,
 * owner:{displayName}, subjectRef:{group:"content.halo.run",kind:"Post",
 * version:"v1alpha1",name:postName}}}。raw 为服务端 HTML 转义后的纯文本，
 * content 为 {@code <p>转义文本</p>}（与 Halo 网站评论结构一致）；
 * 客户端不可指定 group/kind/version/approved/hidden/头像/邮箱/网站。</p>
 */
@Component
public class WebClientHaloCommentGateway implements HaloCommentGateway {

    private static final Logger log =
        LoggerFactory.getLogger(WebClientHaloCommentGateway.class);

    private static final String COMMENTS_PATH = "apis/api.halo.run/v1alpha1/comments";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final WebClient webClient;
    private final ExternalUrlSupplier externalUrlSupplier;
    private final SettingsService settings;

    public WebClientHaloCommentGateway(ExternalUrlSupplier externalUrlSupplier,
                                       SettingsService settings) {
        this(WebClient.builder().build(), externalUrlSupplier, settings);
    }

    /** 测试可见：注入指向假 Halo 的 WebClient。 */
    WebClientHaloCommentGateway(WebClient webClient, ExternalUrlSupplier externalUrlSupplier,
                                SettingsService settings) {
        this.webClient = webClient;
        this.externalUrlSupplier = externalUrlSupplier;
        this.settings = settings;
    }

    @Override
    public Mono<GatewayCommentResult> createComment(String postName, String displayName,
                                                    String content) {
        Map<String, Object> body = new HashMap<>();
        // raw/content 约定：raw = 转义后纯文本；content = <p>转义文本</p>
        body.put("raw", content);
        body.put("content", "<p>" + content + "</p>");
        body.put("allowNotification", false);
        body.put("owner", Map.of("displayName", displayName));
        body.put("subjectRef", Map.of(
            "group", "content.halo.run",
            "kind", "Post",
            "version", "v1alpha1",
            "name", postName));
        return post(COMMENTS_PATH, body, true);
    }

    @Override
    public Mono<GatewayCommentResult> createReply(String commentName, String displayName,
                                                  String content, String quoteReplyName) {
        Map<String, Object> body = new HashMap<>();
        body.put("raw", content);
        body.put("content", "<p>" + content + "</p>");
        body.put("allowNotification", false);
        body.put("owner", Map.of("displayName", displayName));
        if (quoteReplyName != null && !quoteReplyName.isBlank()) {
            body.put("quoteReply", quoteReplyName);
        }
        return post(COMMENTS_PATH + "/" + commentName + "/reply", body, false);
    }

    private Mono<GatewayCommentResult> post(String path, Map<String, Object> body,
                                            boolean commentTarget) {
        URI uri = externalUrlSupplier.get().resolve(path);
        return webClient.post()
            .uri(uri)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .exchangeToMono(response -> {
                int status = response.statusCode().value();
                if (status == 404) {
                    return Mono.error(commentTarget
                        ? new ApiException(ErrorCode.POST_NOT_FOUND, "内容不存在或已删除")
                        : new ApiException(ErrorCode.COMMENT_NOT_FOUND, "评论不存在或已删除"));
                }
                if (status >= 500) {
                    log.warn("[weapp] halo comment write unavailable status={}", status);
                    return Mono.error(haloUnavailable());
                }
                if (status >= 400) {
                    // 400/403/409 及其他 4xx：文章禁止评论 / 游客评论未开放等
                    return Mono.error(
                        new ApiException(ErrorCode.COMMENT_NOT_ALLOWED, "该内容暂不支持评论"));
                }
                return response.bodyToMono(String.class)
                    .flatMap(WebClientHaloCommentGateway::parseJson)
                    .flatMap(json -> {
                        String name = json.path("metadata").path("name").asText("");
                        boolean approved = json.path("spec").path("approved").asBoolean(false);
                        if (name.isBlank()) {
                            return Mono.error(haloUnavailable());
                        }
                        return Mono.just(new GatewayCommentResult(name, approved));
                    });
            })
            .timeout(Duration.ofMillis(settings.client().upstreamTimeoutMillis()))
            .onErrorMap(t -> !(t instanceof ApiException), t -> {
                // 超时 / 连接失败等
                log.warn("[weapp] halo comment write failed: {}",
                    t.getClass().getSimpleName());
                return haloUnavailable();
            });
    }

    private static ApiException haloUnavailable() {
        return new ApiException(ErrorCode.HALO_UNAVAILABLE, "服务暂时不可用，请稍后重试");
    }

    /** 解析 Halo 响应 JSON；解析失败按 HALO_UNAVAILABLE 处理（由 onErrorMap 转换）。 */
    private static Mono<JsonNode> parseJson(String body) {
        return Mono.fromCallable(() -> OBJECT_MAPPER.readTree(body));
    }
}
