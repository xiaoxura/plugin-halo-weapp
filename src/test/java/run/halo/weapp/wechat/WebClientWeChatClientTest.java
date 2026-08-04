package run.halo.weapp.wechat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import run.halo.weapp.config.SettingsService;
import run.halo.weapp.config.WechatSettings;
import run.halo.weapp.error.ApiException;
import run.halo.weapp.error.ErrorCode;
import run.halo.weapp.security.MaskUtils;

/**
 * WebClientWeChatClient：code2Session、access token 缓存/单飞/重试、msgSecCheck
 * 各失败路径全部失败关闭。全部使用假 ExchangeFunction，无真实网络。
 */
class WebClientWeChatClientTest {

    private SettingsService settings;
    private final List<String> requestedPaths = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        settings = mock(SettingsService.class);
        when(settings.wechat()).thenReturn(new WechatSettings("wx-app-id", "wx-secret"));
        when(settings.client()).thenReturn(
            new SettingsService.ClientConfig("0.3.0", "", "", 1000L));
    }

    private WebClientWeChatClient client(
        java.util.function.Function<ClientRequest, Mono<ClientResponse>> handler) {
        WebClient webClient = WebClient.builder()
            .exchangeFunction(request -> {
                requestedPaths.add(request.url().getPath());
                return handler.apply(request);
            })
            .build();
        return new WebClientWeChatClient(webClient, settings, new MaskUtils());
    }

    private static Mono<ClientResponse> json(String body) {
        return Mono.just(ClientResponse.create(HttpStatus.OK)
            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .body(body)
            .build());
    }

    private void expectWechatUnavailable(Throwable t) {
        assertTrue(t instanceof ApiException);
        assertEquals(ErrorCode.WECHAT_UNAVAILABLE, ((ApiException) t).code());
    }

    // ---------- code2Session ----------

    @Test
    void code2SessionReturnsOpenId() {
        WebClientWeChatClient client =
            client(req -> json("{\"openid\":\"openid-test-placeholder\","
                + "\"session_key\":\"session-key-test-placeholder\"}"));
        StepVerifier.create(client.code2Session("login-code"))
            .expectNextMatches(result -> "openid-test-placeholder".equals(result.openId()))
            .verifyComplete();
    }

    @Test
    void code2SessionErrcodeIsUnavailable() {
        WebClientWeChatClient client =
            client(req -> json("{\"errcode\":40029,\"errmsg\":\"invalid code\"}"));
        StepVerifier.create(client.code2Session("login-code"))
            .expectErrorSatisfies(this::expectWechatUnavailable)
            .verify();
    }

    @Test
    void code2SessionMissingOpenIdIsUnavailable() {
        WebClientWeChatClient client = client(req -> json("{\"errcode\":0}"));
        StepVerifier.create(client.code2Session("login-code"))
            .expectErrorSatisfies(this::expectWechatUnavailable)
            .verify();
    }

    // ---------- access token ----------

    @Test
    void accessTokenIsCached() {
        AtomicInteger upstreamCalls = new AtomicInteger();
        WebClientWeChatClient client = client(req -> {
            upstreamCalls.incrementAndGet();
            return json("{\"access_token\":\"token-1\",\"expires_in\":7200}");
        });
        StepVerifier.create(client.getAccessToken()).expectNext("token-1").verifyComplete();
        StepVerifier.create(client.getAccessToken()).expectNext("token-1").verifyComplete();
        assertEquals(1, upstreamCalls.get());
    }

    @Test
    void concurrentRefreshHasOnlyOneUpstreamCall() {
        AtomicInteger upstreamCalls = new AtomicInteger();
        WebClientWeChatClient client = client(req -> {
            upstreamCalls.incrementAndGet();
            return Mono.delay(Duration.ofMillis(50))
                .then(json("{\"access_token\":\"token-shared\",\"expires_in\":7200}"));
        });
        List<String> tokens = Flux.range(0, 10)
            .flatMap(i -> client.getAccessToken())
            .collectList()
            .block();
        assertEquals(10, tokens.size());
        assertTrue(tokens.stream().allMatch("token-shared"::equals));
        assertEquals(1, upstreamCalls.get());
    }

    @Test
    void invalidTokenErrorClearsCacheAndRetriesOnce() {
        AtomicInteger upstreamCalls = new AtomicInteger();
        WebClientWeChatClient client = client(req -> {
            upstreamCalls.incrementAndGet();
            return json("{\"errcode\":40001,\"errmsg\":\"invalid credential\"}");
        });
        StepVerifier.create(client.getAccessToken())
            .expectErrorSatisfies(this::expectWechatUnavailable)
            .verify();
        // 首次失败 + 重试一次 = 2 次上游请求
        assertEquals(2, upstreamCalls.get());
    }

    // ---------- msgSecCheck ----------

    private WebClientWeChatClient clientForSecCheck(String secCheckResponse) {
        return client(req -> {
            if (req.url().getPath().contains("/cgi-bin/token")) {
                return json("{\"access_token\":\"token-1\",\"expires_in\":7200}");
            }
            return json(secCheckResponse);
        });
    }

    @Test
    void secCheckPassIsTheOnlyPass() {
        WebClientWeChatClient client = clientForSecCheck(
            "{\"errcode\":0,\"result\":{\"suggest\":\"pass\",\"label\":100},"
                + "\"trace_id\":\"trace-1\"}");
        StepVerifier.create(client.msgSecCheck("openid-1", "你好"))
            .expectNextMatches(r -> r.suggest() == SecCheckResult.Suggest.PASS
                && "trace-1".equals(r.traceId()))
            .verifyComplete();
    }

    @Test
    void secCheckReviewAndRiskyAreMapped() {
        WebClientWeChatClient reviewClient = clientForSecCheck(
            "{\"errcode\":0,\"result\":{\"suggest\":\"review\",\"label\":20001}}");
        StepVerifier.create(reviewClient.msgSecCheck("openid-1", "x"))
            .expectNextMatches(r -> r.suggest() == SecCheckResult.Suggest.REVIEW)
            .verifyComplete();

        WebClientWeChatClient riskyClient = clientForSecCheck(
            "{\"errcode\":0,\"result\":{\"suggest\":\"risky\",\"label\":20002}}");
        StepVerifier.create(riskyClient.msgSecCheck("openid-1", "x"))
            .expectNextMatches(r -> r.suggest() == SecCheckResult.Suggest.RISKY)
            .verifyComplete();
    }

    @Test
    void secCheckUnknownSuggestFailsClosed() {
        WebClientWeChatClient client = clientForSecCheck(
            "{\"errcode\":0,\"result\":{\"suggest\":\"something-new\"}}");
        StepVerifier.create(client.msgSecCheck("openid-1", "x"))
            .expectErrorSatisfies(this::expectWechatUnavailable)
            .verify();
    }

    @Test
    void secCheckErrcode61010FailsClosed() {
        WebClientWeChatClient client = clientForSecCheck(
            "{\"errcode\":61010,\"errmsg\":\"access expired\"}");
        StepVerifier.create(client.msgSecCheck("openid-1", "x"))
            .expectErrorSatisfies(this::expectWechatUnavailable)
            .verify();
    }

    @Test
    void secCheckTimeoutFailsClosed() {
        WebClientWeChatClient client = client(req -> {
            if (req.url().getPath().contains("/cgi-bin/token")) {
                return json("{\"access_token\":\"token-1\",\"expires_in\":7200}");
            }
            // 超过 client.upstreamTimeoutMillis(1000ms) 的延迟
            return Mono.delay(Duration.ofSeconds(5))
                .then(json("{\"errcode\":0,\"result\":{\"suggest\":\"pass\"}}"));
        });
        StepVerifier.create(client.msgSecCheck("openid-1", "x"))
            .expectErrorSatisfies(this::expectWechatUnavailable)
            .verify(Duration.ofSeconds(10));
    }
}
