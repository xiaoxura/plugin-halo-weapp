package run.halo.weapp.error;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.core.codec.DecodingException;
import run.halo.weapp.error.ErrorCode;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.server.RequestPredicates;
import org.springframework.web.reactive.function.server.RouterFunctions;

class ErrorHandlerTest {

    @Test
    void unknownExceptionUsesStable503AndDoesNotExposeExceptionMessage() {
        var router = RouterFunctions.route(
            RequestPredicates.GET("/boom"),
            request -> ErrorHandler.respond(
                new IllegalStateException("secret-request-body"), "req_test"));

        WebTestClient.bindToRouterFunction(router)
            .build()
            .get()
            .uri("/boom")
            .exchange()
            .expectStatus().isEqualTo(503)
            .expectBody(String.class)
            .value(body -> {
                assertTrue(body.contains("HALO_UNAVAILABLE"));
                assertTrue(body.contains("req_test"));
                assertFalse(body.contains("secret-request-body"));
            });
    }

    @Test
    void malformedJsonUsesValidationErrorWithoutInternalDetails() {
        var router = RouterFunctions.route(
            RequestPredicates.GET("/decode"),
            request -> ErrorHandler.respond(new DecodingException("malformed secret"), "req_decode"));

        WebTestClient.bindToRouterFunction(router)
            .build()
            .get()
            .uri("/decode")
            .exchange()
            .expectStatus().isBadRequest()
            .expectBody(String.class)
            .value(body -> {
                assertTrue(body.contains(ErrorCode.VALIDATION_ERROR.name()));
                assertTrue(body.contains("req_decode"));
                assertFalse(body.contains("malformed secret"));
            });
    }

    @Test
    void apiExceptionPreservesStatusCodeAndRetryAfter() {
        var router = RouterFunctions.route(
            RequestPredicates.GET("/limited"),
            request -> ErrorHandler.respond(
                new ApiException(ErrorCode.RATE_LIMITED, "操作过于频繁", 9), "req_limited"));

        WebTestClient.bindToRouterFunction(router)
            .build()
            .get()
            .uri("/limited")
            .exchange()
            .expectStatus().isEqualTo(429)
            .expectBody(String.class)
            .value(body -> {
                assertTrue(body.contains(ErrorCode.RATE_LIMITED.name()));
                assertTrue(body.contains("\"retryAfter\":9"));
                assertTrue(body.contains("req_limited"));
            });
    }
}
