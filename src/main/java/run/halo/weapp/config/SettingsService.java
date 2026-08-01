package run.halo.weapp.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import run.halo.app.plugin.SettingFetcher;

/**
 * 设置读取门面：每次调用实时从 SettingFetcher 拉取并按分组返回不可变快照。
 * 所有字段 fail-closed：缺失、为 null 或反序列化异常时一律落回默认值
 * （开关默认关闭、maxLength 500、每分钟 3、每小时 20、minVersion 0.3.0、超时 5000ms）。
 */
@Component
public class SettingsService {

    private static final Logger log = LoggerFactory.getLogger(SettingsService.class);

    public static final int DEFAULT_MAX_LENGTH = 500;
    public static final int DEFAULT_RATE_PER_MINUTE = 3;
    public static final int DEFAULT_RATE_PER_HOUR = 20;
    public static final String DEFAULT_MIN_VERSION = "0.3.0";
    public static final long DEFAULT_UPSTREAM_TIMEOUT_MILLIS = 5000L;

    private final SettingFetcher settingFetcher;

    public SettingsService(SettingFetcher settingFetcher) {
        this.settingFetcher = settingFetcher;
    }

    /** 微信凭据；未配置时 isConfigured() == false。 */
    public WechatSettings wechat() {
        WechatSettings raw = fetch("wechat", WechatSettings.class);
        return raw != null ? raw : new WechatSettings(null, null);
    }

    /** 评论设置快照（开关 + 长度 + 频控阈值）。 */
    public CommentConfig comment() {
        CommentSettings raw = fetch("comment", CommentSettings.class);
        if (raw == null) {
            raw = new CommentSettings(null, null, null, null, null, null);
        }
        return new CommentConfig(
            Boolean.TRUE.equals(raw.commentEnabled()),
            Boolean.TRUE.equals(raw.submitEnabled()),
            Boolean.TRUE.equals(raw.replyEnabled()),
            positiveOrDefault(raw.maxLength(), DEFAULT_MAX_LENGTH),
            positiveOrDefault(raw.rateLimitPerMinute(), DEFAULT_RATE_PER_MINUTE),
            positiveOrDefault(raw.rateLimitPerHour(), DEFAULT_RATE_PER_HOUR));
    }

    /** 公告设置快照。 */
    public AnnouncementConfig announcement() {
        AnnouncementSettings raw = fetch("announcement", AnnouncementSettings.class);
        if (raw == null) {
            raw = new AnnouncementSettings(null, null, null);
        }
        return new AnnouncementConfig(
            Boolean.TRUE.equals(raw.enabled()),
            raw.version() == null ? "" : raw.version(),
            raw.content() == null ? "" : raw.content());
    }

    /** 客户端版本/隐私/上游超时设置快照。 */
    public ClientConfig client() {
        ClientSettings raw = fetch("client", ClientSettings.class);
        if (raw == null) {
            raw = new ClientSettings(null, null, null, null);
        }
        long timeout = raw.upstreamTimeoutMillis() != null && raw.upstreamTimeoutMillis() > 0
            ? raw.upstreamTimeoutMillis() : DEFAULT_UPSTREAM_TIMEOUT_MILLIS;
        return new ClientConfig(
            raw.minVersion() == null || raw.minVersion().isBlank()
                ? DEFAULT_MIN_VERSION : raw.minVersion(),
            raw.privacyPolicyUrl() == null ? "" : raw.privacyPolicyUrl(),
            raw.privacyPolicyVersion() == null ? "" : raw.privacyPolicyVersion(),
            timeout);
    }

    private <T> T fetch(String group, Class<T> type) {
        try {
            return settingFetcher.fetch(group, type).orElse(null);
        } catch (Exception e) {
            // fail-closed：任何异常（类型不符、反序列化失败）都按未配置处理
            log.warn("[weapp] settings group={} unreadable, falling back to defaults", group);
            return null;
        }
    }

    private static int positiveOrDefault(Integer value, int defaultValue) {
        return value != null && value > 0 ? value : defaultValue;
    }

    public record CommentConfig(boolean commentEnabled, boolean submitEnabled,
                                boolean replyEnabled, int maxLength,
                                int rateLimitPerMinute, int rateLimitPerHour) {
    }

    public record AnnouncementConfig(boolean enabled, String version, String content) {
    }

    public record ClientConfig(String minVersion, String privacyPolicyUrl,
                               String privacyPolicyVersion, long upstreamTimeoutMillis) {
    }
}
