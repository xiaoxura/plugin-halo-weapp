package run.halo.weapp.error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class ErrorResponseTest {

    @Test
    void retryAfterIsOnlyExposedForRateLimitedErrors() {
        ErrorResponse limited = ErrorResponse.of(
            new ApiException(ErrorCode.RATE_LIMITED, "too many", 17), "req-limited");
        assertEquals(17, limited.retryAfter());

        ErrorResponse validation = ErrorResponse.of(
            new ApiException(ErrorCode.VALIDATION_ERROR, "bad", 17), "req-validation");
        assertNull(validation.retryAfter());
    }
}
