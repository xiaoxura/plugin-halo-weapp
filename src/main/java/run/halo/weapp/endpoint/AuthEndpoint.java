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
import run.halo.weapp.auth.AuthService;
import run.halo.weapp.error.ApiException;
import run.halo.weapp.error.ErrorCode;
import run.halo.weapp.error.ErrorHandler;

/** 微信读者 auth API：登录、资料、退出和注销。 */
@Component
public class AuthEndpoint implements CustomEndpoint {

    private static final String SESSION_HEADER = "X-WeApp-Session";
    private static final String CLIENT_VERSION_HEADER = "X-WeApp-Client-Version";

    private final AuthService authService;

    public AuthEndpoint(AuthService authService) {
        this.authService = authService;
    }

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return RouterFunctions.route()
            .POST("/auth/login", RequestPredicates.contentType(MediaType.APPLICATION_JSON),
                this::login)
            .GET("/auth/profile", this::profile)
            .PATCH("/auth/profile", RequestPredicates.contentType(MediaType.APPLICATION_JSON),
                this::updateProfile)
            .DELETE("/auth/session", this::logout)
            .DELETE("/auth/account", this::deleteAccount)
            .build();
    }

    private Mono<ServerResponse> login(ServerRequest request) {
        String requestId = ErrorHandler.newRequestId();
        return request.bodyToMono(ReaderLoginRequest.class)
            .switchIfEmpty(Mono.error(
                new ApiException(ErrorCode.VALIDATION_ERROR, "请求体不能为空")))
            .flatMap(body -> authService.login(
                new AuthService.LoginCommand(body.code(), body.privacyConsentVersion(),
                    body.displayName()),
                clientVersion(request), clientIp(request)))
            .flatMap(result -> ServerResponse.ok().bodyValue(result))
            .onErrorResume(t -> ErrorHandler.respond(t, requestId));
    }

    private Mono<ServerResponse> profile(ServerRequest request) {
        String requestId = ErrorHandler.newRequestId();
        return Mono.defer(() -> authService.getProfile(sessionToken(request)))
            .flatMap(profile -> ServerResponse.ok().bodyValue(profile))
            .onErrorResume(t -> ErrorHandler.respond(t, requestId));
    }

    private Mono<ServerResponse> updateProfile(ServerRequest request) {
        String requestId = ErrorHandler.newRequestId();
        return request.bodyToMono(ReaderProfileUpdateRequest.class)
            .switchIfEmpty(Mono.error(
                new ApiException(ErrorCode.VALIDATION_ERROR, "请求体不能为空")))
            .flatMap(body -> authService.updateProfile(sessionToken(request),
                new AuthService.UpdateCommand(body.displayName(),
                    body.privacyConsentVersion()),
                clientVersion(request), clientIp(request)))
            .flatMap(profile -> ServerResponse.ok().bodyValue(profile))
            .onErrorResume(t -> ErrorHandler.respond(t, requestId));
    }

    private Mono<ServerResponse> logout(ServerRequest request) {
        String requestId = ErrorHandler.newRequestId();
        return Mono.defer(() -> authService.logout(sessionToken(request)))
            .then(ServerResponse.noContent().build())
            .onErrorResume(t -> ErrorHandler.respond(t, requestId));
    }

    private Mono<ServerResponse> deleteAccount(ServerRequest request) {
        String requestId = ErrorHandler.newRequestId();
        return Mono.defer(() -> authService.deleteAccount(sessionToken(request)))
            .then(ServerResponse.noContent().build())
            .onErrorResume(t -> ErrorHandler.respond(t, requestId));
    }

    private static String sessionToken(ServerRequest request) {
        String token = request.headers().firstHeader(SESSION_HEADER);
        if (token == null || token.isBlank()) {
            throw new ApiException(ErrorCode.SESSION_REQUIRED, "请先登录");
        }
        return token;
    }

    private static String clientVersion(ServerRequest request) {
        return request.headers().firstHeader(CLIENT_VERSION_HEADER);
    }

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

    public record ReaderLoginRequest(String code, String privacyConsentVersion,
                                     String displayName) {
    }

    public record ReaderProfileUpdateRequest(String displayName,
                                             String privacyConsentVersion) {
    }
}
