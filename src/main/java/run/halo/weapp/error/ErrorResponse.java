package run.halo.weapp.error;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 统一错误响应体，结构固定为 {code, message, requestId, retryAfter?}。
 * retryAfter 仅 RATE_LIMITED 时输出。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(String code, String message, String requestId, Integer retryAfter) {

    public static ErrorResponse of(ApiException e, String requestId) {
        return new ErrorResponse(e.code().name(), e.getMessage(), requestId, e.retryAfter());
    }
}
