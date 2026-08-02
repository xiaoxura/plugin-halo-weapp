package run.halo.weapp.config;

import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import run.halo.app.plugin.SettingFetcher;

/**
 * 设置读取门面：每次调用实时从 SettingFetcher 拉取并按分组返回不可变快照。
 * 所有字段 fail-closed：缺失、为 null 或反序列化异常时一律落回默认值
 * （开关与新增能力默认关闭、站点展示使用安全默认值、maxLength 500、每分钟 3、每小时 20、
 * minVersion 0.3.0、超时 5000ms）。
 */
@Component
public class SettingsService {

    private static final Logger log = LoggerFactory.getLogger(SettingsService.class);

    public static final int DEFAULT_MAX_LENGTH = 500;
    public static final int DEFAULT_RATE_PER_MINUTE = 3;
    public static final int DEFAULT_RATE_PER_HOUR = 20;
    public static final String DEFAULT_BLOG_NAME = "我的博客";
    public static final String DEFAULT_BLOG_DESC = "记录技术 · 记录生活";
    public static final int DEFAULT_PAGE_SIZE = 10;
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

    /** 小程序站点展示设置快照。 */
    public SiteConfig site() {
        SiteSettings raw = fetch("site", SiteSettings.class);
        if (raw == null) {
            raw = new SiteSettings(null, null, null, null);
        }
        return new SiteConfig(
            nonBlankOrDefault(raw.blogName(), DEFAULT_BLOG_NAME),
            raw.blogDesc() == null ? DEFAULT_BLOG_DESC : raw.blogDesc(),
            boundedOrDefault(raw.pageSize(), 1, 100, DEFAULT_PAGE_SIZE),
            httpsUrlOrEmpty(raw.fontUrl()));
    }

    /** v0.2.0 新增能力开关；缺失或异常一律全部关闭。 */
    public FeatureConfig features() {
        FeatureSettings raw = fetch("features", FeatureSettings.class);
        if (raw == null) {
            raw = new FeatureSettings(null, null, null);
        }
        return new FeatureConfig(
            Boolean.TRUE.equals(raw.momentsEnabled()),
            Boolean.TRUE.equals(raw.momentCommentEnabled()),
            Boolean.TRUE.equals(raw.readerAccountEnabled()));
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

    private static int boundedOrDefault(Integer value, int min, int max, int defaultValue) {
        return value != null && value >= min && value <= max ? value : defaultValue;
    }

    private static String nonBlankOrDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static String httpsUrlOrEmpty(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        try {
            URI uri = URI.create(value);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                ? value : "";
        } catch (IllegalArgumentException ignored) {
            return "";
        }
    }

    public record SiteConfig(String blogName, String blogDesc, int pageSize, String fontUrl) {
    }

    public record FeatureConfig(boolean momentsEnabled, boolean momentCommentEnabled,
                                boolean readerAccountEnabled) {
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
