package run.halo.weapp.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

/**
 * MaskUtils：日志用户标识不含 OpenID 原文、HMAC 稳定、redact 生效。
 */
class MaskUtilsTest {

    @Test
    void userTagNeverContainsOpenIdAndIsStableForSameSalt() {
        MaskUtils mask = new MaskUtils("fixed-test-salt-0123456789abcdef".getBytes());
        String openId = "oTESTONLY_openid_placeholder_123";
        String tag1 = mask.userTag(openId);
        String tag2 = mask.userTag(openId);
        assertEquals(tag1, tag2);
        assertEquals(16, tag1.length());
        assertFalse(tag1.contains(openId));
        assertFalse(tag1.contains("oABCDEFG"));
        // 不同 openId → 不同 tag
        assertNotEquals(tag1, mask.userTag("another-openid"));
    }

    @Test
    void differentSaltYieldsDifferentTag() {
        byte[] salt1 = "salt-one-0123456789abcdef0123".getBytes();
        byte[] salt2 = "salt-two-0123456789abcdef0123".getBytes();
        String openId = "openid-x";
        assertNotEquals(new MaskUtils(salt1).userTag(openId),
            new MaskUtils(salt2).userTag(openId));
    }

    @Test
    void redactReplacesSecrets() {
        String message = "token=abc123secret failed, appid=wx123";
        String redacted = MaskUtils.redact(message, "abc123secret");
        assertFalse(redacted.contains("abc123secret"));
        assertEquals("token=*** failed, appid=wx123", redacted);
    }
}
