package run.halo.weapp.config;

import java.time.Instant;

/**
 * GET /config 的响应 DTO（见 docs/openapi.yaml PublicConfig）。
 * 显式白名单组装：只包含客户端需要的公开字段，
 * 绝不序列化 Setting 原对象，绝不包含 appSecret 等敏感字段。
 */
public record PublicConfig(int schemaVersion, Instant generatedAt, Site site, Features features,
                           boolean commentEnabled, CommentOptions commentOptions,
                           Announcement announcement, String minVersion,
                           String privacyPolicyUrl, String privacyPolicyVersion) {

    public static final int SCHEMA_VERSION = 1;

    /**
     * 由设置快照组装公开配置。
     */
    public static PublicConfig from(SettingsService.SiteConfig site,
                                    SettingsService.FeatureConfig features,
                                    SettingsService.CommentConfig comment,
                                    SettingsService.AnnouncementConfig announcement,
                                    SettingsService.ClientConfig client) {
        return new PublicConfig(SCHEMA_VERSION, Instant.now(),
            new Site(site.blogName(), site.blogDesc(), site.pageSize(), site.fontUrl()),
            new Features(
                new MomentsFeature(features.momentsEnabled(), features.momentCommentEnabled()),
                new ReaderAccountFeature(features.readerAccountEnabled())),
            comment.commentEnabled(),
            new CommentOptions(comment.submitEnabled(), comment.replyEnabled(),
                comment.maxLength(), true),
            new Announcement(announcement.enabled(), announcement.version(),
                announcement.content()),
            client.minVersion(), client.privacyPolicyUrl(), client.privacyPolicyVersion());
    }

    public record CommentOptions(boolean submitEnabled, boolean replyEnabled, int maxLength,
                                 boolean nicknameRequired) {
    }

    public record Site(String blogName, String blogDesc, int pageSize, String fontUrl) {
    }

    public record Features(MomentsFeature moments, ReaderAccountFeature readerAccount) {
    }

    public record MomentsFeature(boolean enabled, boolean commentEnabled) {
    }

    public record ReaderAccountFeature(boolean enabled) {
    }

    public record Announcement(boolean enabled, String version, String content) {
    }
}
