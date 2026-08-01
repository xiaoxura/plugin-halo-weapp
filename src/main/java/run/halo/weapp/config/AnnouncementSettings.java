package run.halo.weapp.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * settings.yaml announcement 分组的反序列化载体。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AnnouncementSettings(Boolean enabled, String version, String content) {
}
