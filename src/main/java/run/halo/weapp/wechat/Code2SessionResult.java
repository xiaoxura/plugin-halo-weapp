package run.halo.weapp.wechat;

/**
 * code2Session 结果。只保留 OpenID；session_key 在解析后立即丢弃，
 * 不进入会话、日志与任何返回值。
 */
public record Code2SessionResult(String openId) {

    /** 防止 OpenID 随 toString 进入日志。 */
    @Override
    public String toString() {
        return "Code2SessionResult[openId=***]";
    }
}
