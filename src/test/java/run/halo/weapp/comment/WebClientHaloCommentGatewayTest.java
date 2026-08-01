package run.halo.weapp.comment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import run.halo.app.infra.ExternalUrlSupplier;
import run.halo.weapp.config.SettingsService;
import run.halo.weapp.error.ApiException;
import run.halo.weapp.error.ErrorCode;

/**
 * WebClientHaloCommentGateway：请求体固定构造、approved 映射、错误码映射。
 * 全部使用假 ExchangeFunction，无真实网络。
 */
class WebClientHaloCommentGatewayTest {

    private SettingsService settings;
    private ExternalUrlSupplier externalUrlSupplier;

    @BeforeEach
    void setUp() {
        settings = mock(SettingsService.class);
        when(settings.client()).thenReturn(
            new SettingsService.ClientConfig("0.3.0", "", "", 1000L));
        externalUrlSupplier = mock(ExternalUrlSupplier.class);
        when(externalUrlSupplier.get()).thenReturn(URI.create("http://halo.local/"));
    }

    private WebClientHaloCommentGateway gateway(
        java.util.function.Function<org.springframework.web.reactive.function.client
            .ClientRequest, Mono<ClientResponse>> handler) {
        WebClient webClient = WebClient.builder().exchangeFunction(handler::apply).build();
        return new WebClientHaloCommentGateway(webClient, externalUrlSupplier, settings);
    }

    private static Mono<ClientResponse> json(HttpStatus status, String body) {
        return Mono.just(ClientResponse.create(status)
            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .body(body)
            .build());
    }

    private void expectError(ErrorCode code, Throwable t) {
        assertTrue(t instanceof ApiException, "expected ApiException but got " + t);
        assertEquals(code, ((ApiException) t).code());
    }

    @Test
    void commentBodyIsFixedAndApprovedIsParsed() {
        AtomicReference<String> capturedUri = new AtomicReference<>();
        WebClientHaloCommentGateway gateway = gateway(request -> {
            capturedUri.set(request.url().toString());
            return json(HttpStatus.OK,
                "{\"metadata\":{\"name\":\"c-abc\"},\"spec\":{\"approved\":true}}");
        });
        StepVerifier.create(gateway.createComment("post-1", "昵称", "内容"))
            .expectNextMatches(r -> "c-abc".equals(r.haloName()) && r.approved())
            .verifyComplete();
        assertEquals("http://halo.local/apis/api.halo.run/v1alpha1/comments",
            capturedUri.get());
    }

    @Test
    void missingApprovedMapsToPending() {
        WebClientHaloCommentGateway gateway = gateway(
            request -> json(HttpStatus.OK, "{\"metadata\":{\"name\":\"c-1\"},\"spec\":{}}"));
        StepVerifier.create(gateway.createComment("post-1", "昵称", "内容"))
            .expectNextMatches(r -> !r.approved())
            .verifyComplete();
    }

    @Test
    void replyUsesReplyPathAndMaps404ToCommentNotFound() {
        AtomicReference<String> capturedUri = new AtomicReference<>();
        WebClientHaloCommentGateway gateway = gateway(request -> {
            capturedUri.set(request.url().toString());
            return json(HttpStatus.NOT_FOUND, "{}");
        });
        StepVerifier.create(gateway.createReply("c-1", "昵称", "内容", "r-1"))
            .expectErrorSatisfies(t -> expectError(ErrorCode.COMMENT_NOT_FOUND, t))
            .verify();
        assertEquals("http://halo.local/apis/api.halo.run/v1alpha1/comments/c-1/reply",
            capturedUri.get());
    }

    @Test
    void comment404MapsToPostNotFound() {
        WebClientHaloCommentGateway gateway = gateway(
            request -> json(HttpStatus.NOT_FOUND, "{}"));
        StepVerifier.create(gateway.createComment("post-x", "昵称", "内容"))
            .expectErrorSatisfies(t -> expectError(ErrorCode.POST_NOT_FOUND, t))
            .verify();
    }

    @Test
    void clientErrorsMapToCommentNotAllowed() {
        for (HttpStatus status : new HttpStatus[] {HttpStatus.BAD_REQUEST,
            HttpStatus.FORBIDDEN, HttpStatus.CONFLICT}) {
            WebClientHaloCommentGateway gateway = gateway(
                request -> json(status, "{}"));
            StepVerifier.create(gateway.createComment("post-1", "昵称", "内容"))
                .expectErrorSatisfies(t -> expectError(ErrorCode.COMMENT_NOT_ALLOWED, t))
                .verify();
        }
    }

    @Test
    void serverErrorAndTimeoutMapToHaloUnavailable() {
        WebClientHaloCommentGateway gateway500 = gateway(
            request -> json(HttpStatus.INTERNAL_SERVER_ERROR, "{}"));
        StepVerifier.create(gateway500.createComment("post-1", "昵称", "内容"))
            .expectErrorSatisfies(t -> expectError(ErrorCode.HALO_UNAVAILABLE, t))
            .verify();

        WebClientHaloCommentGateway gatewayTimeout = gateway(
            request -> Mono.delay(java.time.Duration.ofSeconds(5))
                .then(json(HttpStatus.OK, "{}")));
        StepVerifier.create(gatewayTimeout.createComment("post-1", "昵称", "内容"))
            .expectErrorSatisfies(t -> expectError(ErrorCode.HALO_UNAVAILABLE, t))
            .verify(java.time.Duration.ofSeconds(10));
    }
}
