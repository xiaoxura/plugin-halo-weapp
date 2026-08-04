package run.halo.weapp.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import run.halo.weapp.MutableClock;
import run.halo.weapp.error.ApiException;
import run.halo.weapp.error.ErrorCode;

/**
 * SessionService：token 随机性/长度、有效期、过期与缺失行为。
 */
class SessionServiceTest {

    @Test
    void tokenIs64HexCharsAndRandom() {
        SessionService service = new SessionService();
        Set<String> tokens = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            String token = service.create("openid-" + i);
            assertEquals(64, token.length());
            assertTrue(token.matches("[0-9a-f]{64}"));
            tokens.add(token);
        }
        assertEquals(100, tokens.size());
    }

    @Test
    void validateReturnsOpenIdWithinTtl() {
        MutableClock clock = new MutableClock(Instant.now());
        SessionService service = new SessionService(clock, Duration.ofSeconds(5400));
        String token = service.create("openid-1");
        assertEquals("openid-1", service.validate(token));
        clock.advance(Duration.ofSeconds(5399));
        assertEquals("openid-1", service.validate(token));
    }

    @Test
    void expiredSessionYieldsSessionExpired() {
        MutableClock clock = new MutableClock(Instant.now());
        SessionService service = new SessionService(clock, Duration.ofSeconds(5400));
        String token = service.create("openid-1");
        clock.advance(Duration.ofSeconds(5400));
        ApiException e = assertThrows(ApiException.class, () -> service.validate(token));
        assertEquals(ErrorCode.SESSION_EXPIRED, e.code());
    }

    @Test
    void creatingNewSessionPurgesExpiredSessionsBeforeAdding() {
        MutableClock clock = new MutableClock(Instant.now());
        SessionService service = new SessionService(clock, Duration.ofSeconds(1));
        service.createAccount("openid-old", "reader-old");
        clock.advance(Duration.ofSeconds(1));

        service.create("openid-new");

        assertEquals(0, service.revokeAllByReaderName("reader-old"));
    }

    @Test
    void unknownTokenYieldsSessionExpired() {
        SessionService service = new SessionService();
        ApiException e = assertThrows(ApiException.class,
            () -> service.validate("0".repeat(64)));
        assertEquals(ErrorCode.SESSION_EXPIRED, e.code());
    }

    @Test
    void missingTokenYieldsSessionRequired() {
        SessionService service = new SessionService();
        assertEquals(ErrorCode.SESSION_REQUIRED,
            assertThrows(ApiException.class, () -> service.validate(null)).code());
        assertEquals(ErrorCode.SESSION_REQUIRED,
            assertThrows(ApiException.class, () -> service.validate("  ")).code());
    }

    @Test
    void sessionsAreIndependent() {
        SessionService service = new SessionService();
        String t1 = service.create("openid-1");
        String t2 = service.create("openid-2");
        assertNotEquals(t1, t2);
        assertEquals("openid-1", service.validate(t1));
        assertEquals("openid-2", service.validate(t2));
    }

    @Test
    void accountSessionCarriesInternalReaderAndTemporarySessionCannotUseAuthApi() {
        SessionService service = new SessionService();
        String temporary = service.create("openid-1");
        assertEquals(ErrorCode.SESSION_REQUIRED,
            assertThrows(ApiException.class, () -> service.validateAccount(temporary)).code());

        String account = service.createAccount("openid-1", "reader-a");
        SessionService.SessionPrincipal principal = service.validateAccount(account);
        assertEquals("openid-1", principal.openId());
        assertEquals("reader-a", principal.readerName());
        assertTrue(principal.isAccount());
        assertFalse(principal.toString().contains("openid-1"));
        assertFalse(principal.toString().contains("reader-a"));
    }

    @Test
    void revokeCurrentAndRevokeAllReaderSessionsAreImmediate() {
        SessionService service = new SessionService();
        String readerA1 = service.createAccount("openid-a", "reader-a");
        String readerA2 = service.createAccount("openid-a", "reader-a");
        String readerB = service.createAccount("openid-b", "reader-b");
        assertTrue(service.revoke(readerA1));
        assertEquals(ErrorCode.SESSION_EXPIRED,
            assertThrows(ApiException.class, () -> service.validate(readerA1)).code());
        assertEquals(1, service.revokeAllByReaderName("reader-a"));
        assertEquals(ErrorCode.SESSION_EXPIRED,
            assertThrows(ApiException.class, () -> service.validate(readerA2)).code());
        assertEquals("openid-b", service.validate(readerB));
    }

    @Test
    void clearRevokesTemporaryAndAccountSessions() {
        SessionService service = new SessionService();
        String temporary = service.create("openid-a");
        String account = service.createAccount("openid-b", "reader-b");
        service.clear();
        assertThrows(ApiException.class, () -> service.validate(temporary));
        assertThrows(ApiException.class, () -> service.validate(account));
    }
}
