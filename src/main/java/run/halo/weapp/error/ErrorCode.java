package run.halo.weapp.error;

/**
 * 稳定业务错误码，客户端只依赖 code 做逻辑判断（见 docs/openapi.yaml ErrorResponse）。
 */
public enum ErrorCode {

    VALIDATION_ERROR(400),
    SESSION_REQUIRED(401),
    SESSION_EXPIRED(401),
    READER_ACCOUNT_DISABLED(403),
    MOMENT_COMMENT_DISABLED(403),
    COMMENT_DISABLED(403),
    REPLY_DISABLED(403),
    POST_NOT_FOUND(404),
    MOMENT_NOT_FOUND(404),
    COMMENT_NOT_FOUND(404),
    READER_NOT_FOUND(404),
    COMMENT_NOT_ALLOWED(409),
    IDEMPOTENCY_CONFLICT(409),
    CONTENT_REVIEW(422),
    CONTENT_RISKY(422),
    PRIVACY_CONSENT_REQUIRED(428),
    CLIENT_UPDATE_REQUIRED(426),
    RATE_LIMITED(429),
    WECHAT_UNAVAILABLE(502),
    HALO_UNAVAILABLE(503);

    private final int httpStatus;

    ErrorCode(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    public int httpStatus() {
        return httpStatus;
    }
}
