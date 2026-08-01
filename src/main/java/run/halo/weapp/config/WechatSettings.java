package run.halo.weapp.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * settings.yaml wechat 分组的反序列化载体。appSecret 绝不输出到日志/接口。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WechatSettings(String appId, String appSecret) {

    public boolean isConfigured() {
        return appId != null && !appId.isBlank()
            && appSecret != null && !appSecret.isBlank();
    }

    /** 防止 appSecret 随 toString 泄露。 */
    @Override
    public String toString() {
        return "WechatSettings[appId=" + (appId == null ? "null" : "***")
            + ", appSecret=***]";
    }
}
