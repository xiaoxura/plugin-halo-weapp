package run.halo.weapp.endpoint;

import java.net.InetSocketAddress;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RequestPredicates;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;
import run.halo.weapp.comment.CommentService;
import run.halo.weapp.error.ApiException;
import run.halo.weapp.error.ErrorCode;
import run.halo.weapp.error.ErrorHandler;

/**
 * POST /comments、POST /moments/{momentName}/comments、
 * POST /comments/{commentName}/replies：安全评论/回复写入。
 */
@Component
public class CommentEndpoint implements CustomEndpoint {

    private static final String SESSION_HEADER = "X-WeApp-Session";
    private static final String IDEMPOTENCY_HEADER = "X-Idempotency-Key";
    private static final String CLIENT_VERSION_HEADER = "X-WeApp-Client-Version";

    private final CommentService commentService;

    public CommentEndpoint(CommentService commentService) {
        this.commentService = commentService;
    }

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return RouterFunctions.route()
            .POST("/moments/{momentName}/comments",
                RequestPredicates.contentType(MediaType.APPLICATION_JSON),
                this::createMomentComment)
            .POST("/comments",
                RequestPredicates.contentType(MediaType.APPLICATION_JSON),
                this::createComment)
            .POST("/comments/{commentName}/replies",
                RequestPredicates.contentType(MediaType.APPLICATION_JSON),
                this::createReply)
            .build();
    }

    private Mono<ServerResponse> createMomentComment(ServerRequest request) {
        String requestId = ErrorHandler.newRequestId();
        String momentName = request.pathVariable("momentName");
        return request.bodyToMono(CommentCreateRequest.class)
            .switchIfEmpty(Mono.error(
                new ApiException(ErrorCode.VALIDATION_ERROR, "请求体不能为空")))
            .flatMap(body -> commentService.submitMomentComment(
                    sessionToken(request), idempotencyKey(request), clientVersion(request),
                    clientIp(request),
                    new CommentService.MomentCommentCommand(momentName, body.displayName(),
                        body.content(), body.privacyConsentVersion())))
            .map(result -> new CommentCreateResponse(requestId, result.status(), result.name()))
            .flatMap(response -> ServerResponse.ok().bodyValue(response))
            .onErrorResume(t -> ErrorHandler.respond(t, requestId));
    }

    private Mono<ServerResponse> createComment(ServerRequest request) {
        String requestId = ErrorHandler.newRequestId();
        return request.bodyToMono(CommentCreateRequest.class)
            .switchIfEmpty(Mono.error(
                new ApiException(ErrorCode.VALIDATION_ERROR, "请求体不能为空")))
            .flatMap(body -> {
                String sessionToken = sessionToken(request);
                String idempotencyKey = idempotencyKey(request);
                return commentService.submitComment(sessionToken, idempotencyKey,
                        clientVersion(request), clientIp(request),
                        new CommentService.CommentCommand(body.postName(), body.displayName(),
                            body.content(), body.privacyConsentVersion()))
                    .map(result -> new CommentCreateResponse(requestId, result.status(),
                        result.name()));
            })
            .flatMap(response -> ServerResponse.ok().bodyValue(response))
            .onErrorResume(t -> ErrorHandler.respond(t, requestId));
    }

    private Mono<ServerResponse> createReply(ServerRequest request) {
        String requestId = ErrorHandler.newRequestId();
        String commentName = request.pathVariable("commentName");
        return request.bodyToMono(ReplyCreateRequest.class)
            .switchIfEmpty(Mono.error(
                new ApiException(ErrorCode.VALIDATION_ERROR, "请求体不能为空")))
            .flatMap(body -> {
                String sessionToken = sessionToken(request);
                String idempotencyKey = idempotencyKey(request);
                return commentService.submitReply(sessionToken, idempotencyKey,
                        clientVersion(request), clientIp(request),
                        new CommentService.ReplyCommand(commentName, body.displayName(),
                            body.content(), body.privacyConsentVersion(),
                            body.quoteReplyName()))
                    .map(result -> new ReplyCreateResponse(requestId, result.status(),
                        result.name()));
            })
            .flatMap(response -> ServerResponse.ok().bodyValue(response))
            .onErrorResume(t -> ErrorHandler.respond(t, requestId));
    }

    private static String sessionToken(ServerRequest request) {
        String token = request.headers().firstHeader(SESSION_HEADER);
        if (token == null || token.isBlank()) {
            throw new ApiException(ErrorCode.SESSION_REQUIRED, "请先登录");
        }
        return token;
    }

    private static String idempotencyKey(ServerRequest request) {
        String key = request.headers().firstHeader(IDEMPOTENCY_HEADER);
        if (key == null || key.length() < 8 || key.length() > 128) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                "X-Idempotency-Key 需为 8～128 个字符");
        }
        return key;
    }

    private static String clientVersion(ServerRequest request) {
        return request.headers().firstHeader(CLIENT_VERSION_HEADER);
    }

    /** 来源 IP 只取远端地址，不信任 X-Forwarded-For（v0.1.x 未配置受信代理）。 */
    private static String clientIp(ServerRequest request) {
        return request.remoteAddress()
            .map(InetSocketAddress::getAddress)
            .map(address -> address.getHostAddress())
            .orElse(null);
    }

    @Override
    public GroupVersion groupVersion() {
        return GroupVersion.parseAPIVersion("api.weapp.halo.run/v1alpha1");
    }

    public record CommentCreateRequest(String postName, String displayName, String content,
                                       String privacyConsentVersion) {
    }

    public record ReplyCreateRequest(String displayName, String content,
                                     String privacyConsentVersion, String quoteReplyName) {
    }

    public record CommentCreateResponse(String requestId, String status, String commentName) {
    }

    public record ReplyCreateResponse(String requestId, String status, String replyName) {
    }
}
