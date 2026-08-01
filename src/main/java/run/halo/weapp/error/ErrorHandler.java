package run.halo.weapp.error;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

/**
 * 统一错误处理工具：每个请求生成 requestId，业务异常按错误码映射 HTTP 状态；
 * 未知异常一律映射为 500/HALO_UNAVAILABLE 同款结构，不向外暴露内部细节。
 */
public final class ErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(ErrorHandler.class);

    private ErrorHandler() {
    }

    /** 生成排查用请求 ID（"req_" + UUID 去横线）。 */
    public static String newRequestId() {
        return "req_" + UUID.randomUUID().toString().replace("-", "");
    }

    /** 将任意异常转换为统一错误响应。 */
    public static Mono<ServerResponse> respond(Throwable t, String requestId) {
        if (t instanceof ApiException apiException) {
            if (apiException.code().httpStatus() >= 500) {
                log.warn("[weapp] requestId={} upstream error code={}", requestId,
                    apiException.code());
            }
            return ServerResponse.status(apiException.code().httpStatus())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(ErrorResponse.of(apiException, requestId));
        }
        // 未知异常：记录内部细节但对外只返回通用结构
        log.error("[weapp] requestId={} unexpected error", requestId, t);
        var body = new ErrorResponse(ErrorCode.HALO_UNAVAILABLE.name(),
            "服务暂时不可用，请稍后重试", requestId, null);
        return ServerResponse.status(500)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body);
    }
}
