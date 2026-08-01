package run.halo.weapp.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * settings.yaml comment 分组的反序列化载体。字段可能为 null，由 SettingsService 落默认值。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CommentSettings(Boolean commentEnabled, Boolean submitEnabled, Boolean replyEnabled,
                              Integer maxLength, Integer rateLimitPerMinute,
                              Integer rateLimitPerHour) {
}
