package run.halo.weapp.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * settings.yaml features 分组的反序列化载体。
 *
 * <p>所有新增能力默认关闭；null、缺失或反序列化失败均由 SettingsService
 * 按 false 处理，保证旧 ConfigMap 与异常配置安全降级。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FeatureSettings(Boolean momentsEnabled, Boolean momentCommentEnabled,
                              Boolean readerAccountEnabled) {
}
