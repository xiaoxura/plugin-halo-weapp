package run.halo.weapp.identity;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import run.halo.weapp.config.SettingsService;
import run.halo.weapp.config.WechatSettings;
import run.halo.weapp.error.ApiException;
import run.halo.weapp.error.ErrorCode;

/** 以 HMAC-SHA256(appId + ":" + openId) 派生确定性内部读者资源名。 */
@Component
public class ReaderIdentityService {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final int NAME_DIGEST_HEX_LENGTH = 40; // 160 bit

    private final IdentityKeyService identityKeyService;
    private final SettingsService settingsService;

    public ReaderIdentityService(IdentityKeyService identityKeyService,
                                 SettingsService settingsService) {
        this.identityKeyService = identityKeyService;
        this.settingsService = settingsService;
    }

    public Mono<String> readerName(String openId) {
        WechatSettings wechat = settingsService.wechat();
        if (!wechat.isConfigured() || openId == null || openId.isBlank()) {
            return Mono.error(new ApiException(ErrorCode.HALO_UNAVAILABLE,
                "身份服务暂时不可用，请稍后重试"));
        }
        return identityKeyService.getOrCreate()
            .map(key -> deriveReaderName(key, wechat.appId(), openId));
    }

    /** 测试向量入口；只返回 160-bit 前缀资源名，不暴露完整摘要。 */
    static String deriveReaderName(byte[] key, String appId, String openId) {
        if (key == null || key.length != IdentityKeyService.KEY_BYTES
            || appId == null || appId.isBlank() || openId == null || openId.isBlank()) {
            throw new IllegalArgumentException("identity input is invalid");
        }
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(key, HMAC_ALGORITHM));
            byte[] digest = mac.doFinal((appId + ":" + openId)
                .getBytes(StandardCharsets.UTF_8));
            return "reader-" + HexFormat.of().formatHex(digest)
                .substring(0, NAME_DIGEST_HEX_LENGTH);
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 unavailable");
        }
    }
}
