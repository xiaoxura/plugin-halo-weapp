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

/** Public Moment visibility is validated without importing PluginMoments Java classes. */
class WebClientHaloMomentGatewayTest {

    private SettingsService settings;
    private ExternalUrlSupplier externalUrlSupplier;

    @BeforeEach
    void setUp() {
        settings = mock(SettingsService.class);
        when(settings.client()).thenReturn(
            new SettingsService.ClientConfig("0.4.0", "", "", 1000L));
        externalUrlSupplier = mock(ExternalUrlSupplier.class);
        when(externalUrlSupplier.get()).thenReturn(URI.create("http://halo.local/"));
    }

    private WebClientHaloMomentGateway gateway(
        java.util.function.Function<org.springframework.web.reactive.function.client
            .ClientRequest, Mono<ClientResponse>> handler) {
        WebClient webClient = WebClient.builder().exchangeFunction(handler::apply).build();
        return new WebClientHaloMomentGateway(webClient, externalUrlSupplier, settings);
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
    void publicApprovedMomentCompletesAndUsesFixedPublicApiPath() {
        AtomicReference<String> capturedUri = new AtomicReference<>();
        WebClientHaloMomentGateway gateway = gateway(request -> {
            capturedUri.set(request.url().toString());
            return json(HttpStatus.OK,
                "{\"metadata\":{},\"spec\":{\"visible\":\"PUBLIC\",\"approved\":true}}");
        });
        StepVerifier.create(gateway.validateCommentable("moment-1"))
            .verifyComplete();
        assertEquals("http://halo.local/apis/api.moment.halo.run/v1alpha1/moments/moment-1",
            capturedUri.get());
    }

    @Test
    void privateOrUnapprovedMomentIsNotFoundAndPathIsValidated() {
        WebClientHaloMomentGateway gateway = gateway(request -> json(HttpStatus.OK,
            "{\"spec\":{\"visible\":\"PRIVATE\",\"approved\":true}}"));
        StepVerifier.create(gateway.validateCommentable("moment-1"))
            .expectErrorSatisfies(t -> expectError(ErrorCode.MOMENT_NOT_FOUND, t))
            .verify();

        WebClientHaloMomentGateway unapproved = gateway(request -> json(HttpStatus.OK,
            "{\"metadata\":{},\"spec\":{\"visible\":\"PUBLIC\",\"approved\":false}}"));
        StepVerifier.create(unapproved.validateCommentable("moment-1"))
            .expectErrorSatisfies(t -> expectError(ErrorCode.MOMENT_NOT_FOUND, t))
            .verify();

        WebClientHaloMomentGateway deleted = gateway(request -> json(HttpStatus.OK,
            "{\"metadata\":{\"deletionTimestamp\":\"2026-08-03T00:00:00Z\"},"
                + "\"spec\":{\"visible\":\"PUBLIC\",\"approved\":true}}"));
        StepVerifier.create(deleted.validateCommentable("moment-1"))
            .expectErrorSatisfies(t -> expectError(ErrorCode.MOMENT_NOT_FOUND, t))
            .verify();

        WebClientHaloMomentGateway specDeleted = gateway(request -> json(HttpStatus.OK,
            "{\"metadata\":{},\"spec\":{\"visible\":\"PUBLIC\","
                + "\"approved\":true,\"deleted\":true}}"));
        StepVerifier.create(specDeleted.validateCommentable("moment-1"))
            .expectErrorSatisfies(t -> expectError(ErrorCode.MOMENT_NOT_FOUND, t))
            .verify();

        StepVerifier.create(gateway.validateCommentable("../admin"))
            .expectErrorSatisfies(t -> expectError(ErrorCode.VALIDATION_ERROR, t))
            .verify();
    }

    @Test
    void missingAndUpstreamErrorsAreSeparated() {
        WebClientHaloMomentGateway missing = gateway(
            request -> json(HttpStatus.NOT_FOUND, "{}"));
        StepVerifier.create(missing.validateCommentable("moment-1"))
            .expectErrorSatisfies(t -> expectError(ErrorCode.MOMENT_NOT_FOUND, t))
            .verify();

        WebClientHaloMomentGateway unavailable = gateway(
            request -> json(HttpStatus.INTERNAL_SERVER_ERROR, "{}"));
        StepVerifier.create(unavailable.validateCommentable("moment-1"))
            .expectErrorSatisfies(t -> expectError(ErrorCode.HALO_UNAVAILABLE, t))
            .verify();
    }
}
