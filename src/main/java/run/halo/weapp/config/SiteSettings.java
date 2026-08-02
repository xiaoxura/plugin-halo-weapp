package run.halo.weapp.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * settings.yaml site 分组的反序列化载体。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SiteSettings(String blogName, String blogDesc, Integer pageSize, String fontUrl) {
}
