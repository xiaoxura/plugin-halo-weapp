package run.halo.weapp.wechat;

/**
 * 文本安全检测结果。
 */
public record SecCheckResult(Suggest suggest, String traceId) {

    public enum Suggest {
        PASS, REVIEW, RISKY
    }
}
