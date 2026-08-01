package run.halo.weapp.comment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import run.halo.weapp.MutableClock;
import run.halo.weapp.config.SettingsService;
import run.halo.weapp.error.ApiException;
import run.halo.weapp.error.ErrorCode;

/**
 * RateLimitService：分钟/小时窗口触发、retryAfter 合理、IP 兜底窗口。
 */
class RateLimitServiceTest {

    private final MutableClock clock =
        new MutableClock(Instant.ofEpochSecond(1_700_000_000));
    private final RateLimitService rateLimit = new RateLimitService(clock);

    private static SettingsService.CommentConfig config(int perMinute, int perHour) {
        return new SettingsService.CommentConfig(true, true, true, 500, perMinute, perHour);
    }

    @Test
    void minuteWindowTriggersWithRetryAfter() {
        SettingsService.CommentConfig config = config(2, 100);
        assertDoesNotThrow(() -> rateLimit.checkUser("user-1", config));
        assertDoesNotThrow(() -> rateLimit.checkUser("user-1", config));
        ApiException e = assertThrows(ApiException.class,
            () -> rateLimit.checkUser("user-1", config));
        assertEquals(ErrorCode.RATE_LIMITED, e.code());
        assertTrue(e.retryAfter() >= 1 && e.retryAfter() <= 60);
    }

    @Test
    void hourWindowTriggers() {
        SettingsService.CommentConfig config = config(100, 2);
        rateLimit.checkUser("user-1", config);
        rateLimit.checkUser("user-1", config);
        ApiException e = assertThrows(ApiException.class,
            () -> rateLimit.checkUser("user-1", config));
        assertEquals(ErrorCode.RATE_LIMITED, e.code());
        assertTrue(e.retryAfter() >= 1 && e.retryAfter() <= 3600);
    }

    @Test
    void windowResetsAfterMinutePasses() {
        SettingsService.CommentConfig config = config(1, 100);
        rateLimit.checkUser("user-1", config);
        assertThrows(ApiException.class, () -> rateLimit.checkUser("user-1", config));
        clock.advance(Duration.ofSeconds(61));
        assertDoesNotThrow(() -> rateLimit.checkUser("user-1", config));
    }

    @Test
    void usersAreIsolated() {
        SettingsService.CommentConfig config = config(1, 100);
        rateLimit.checkUser("user-1", config);
        assertDoesNotThrow(() -> rateLimit.checkUser("user-2", config));
    }

    @Test
    void ipFallbackWindowTriggers() {
        for (int i = 0; i < 30; i++) {
            rateLimit.checkIp("203.0.113.1");
        }
        ApiException e = assertThrows(ApiException.class,
            () -> rateLimit.checkIp("203.0.113.1"));
        assertEquals(ErrorCode.RATE_LIMITED, e.code());
        assertDoesNotThrow(() -> rateLimit.checkIp("203.0.113.2"));
        assertDoesNotThrow(() -> rateLimit.checkIp(null));
    }
}
