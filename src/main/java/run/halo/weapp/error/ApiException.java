package run.halo.weapp.error;

/**
 * 业务异常：携带稳定错误码、面向用户的中文提示与可选 retryAfter。
 * message 仅用于展示，绝不携带凭据、OpenID、token 等敏感信息。
 */
public class ApiException extends RuntimeException {

    private final ErrorCode code;
    private final Integer retryAfter;

    public ApiException(ErrorCode code, String message) {
        this(code, message, null);
    }

    public ApiException(ErrorCode code, String message, Integer retryAfter) {
        super(message);
        this.code = code;
        this.retryAfter = retryAfter;
    }

    public ErrorCode code() {
        return code;
    }

    public Integer retryAfter() {
        return retryAfter;
    }
}
