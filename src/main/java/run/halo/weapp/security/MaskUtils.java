package run.halo.weapp.security;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * 日志脱敏工具。
 *
 * <p>用户标识 = HMAC-SHA256(openId, salt) 前 16 个 hex 字符；salt 启动时由
 * SecureRandom 随机生成且不持久化，进程重启即变化，无法从日志反推 OpenID。</p>
 */
@Component
public class MaskUtils {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final int TAG_HEX_LENGTH = 16;

    private final byte[] salt;

    public MaskUtils() {
        this.salt = new byte[32];
        new SecureRandom().nextBytes(this.salt);
    }

    /** 测试可见：固定 salt 以断言 HMAC 稳定性。 */
    MaskUtils(byte[] salt) {
        this.salt = salt.clone();
    }

    /**
     * 生成日志用的用户标识（不含 OpenID 原文）。
     */
    public String userTag(String openId) {
        if (openId == null) {
            return "anonymous";
        }
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(salt, HMAC_ALGORITHM));
            byte[] digest = mac.doFinal(openId.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, TAG_HEX_LENGTH);
        } catch (Exception e) {
            // HmacSHA256 在任何 JDK 都可用；防御性兜底，不输出原文
            return "unavailable";
        }
    }

    /**
     * 日志输出前的最后防线：把消息中出现的敏感值（AppSecret、access token 等）
     * 替换为 "***"。敏感值由调用方显式给出，绝不记录其本身。
     */
    public static String redact(String message, String... secrets) {
        if (message == null) {
            return null;
        }
        String result = message;
        if (secrets != null) {
            for (String secret : secrets) {
                if (secret != null && !secret.isEmpty()) {
                    result = result.replace(secret, "***");
                }
            }
        }
        return result;
    }
}
