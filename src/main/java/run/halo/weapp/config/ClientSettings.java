package run.halo.weapp.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * settings.yaml client 分组的反序列化载体。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ClientSettings(String minVersion, String privacyPolicyUrl,
                             String privacyPolicyVersion, Long upstreamTimeoutMillis) {
}
