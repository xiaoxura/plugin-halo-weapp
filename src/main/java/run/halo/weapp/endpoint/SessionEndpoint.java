package run.halo.weapp.endpoint;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RequestPredicates;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;
import run.halo.weapp.config.SettingsService;
import run.halo.weapp.error.ApiException;
import run.halo.weapp.error.ErrorCode;
import run.halo.weapp.error.ErrorHandler;
import run.halo.weapp.security.SessionService;
import run.halo.weapp.wechat.WeChatClient;

/**
 * POST /session：wx.login 一次性 code 换取不透明会话 token。
 * appId/appSecret 未配置 → 503 HALO_UNAVAILABLE；OpenID 与 session_key 不返回客户端。
 */
@Component
public class SessionEndpoint implements CustomEndpoint {

    private final SettingsService settingsService;
    private final WeChatClient weChatClient;
    private final SessionService sessionService;

    public SessionEndpoint(SettingsService settingsService, WeChatClient weChatClient,
                           SessionService sessionService) {
        this.settingsService = settingsService;
        this.weChatClient = weChatClient;
        this.sessionService = sessionService;
    }

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return RouterFunctions.route()
            .POST("/session", RequestPredicates.contentType(MediaType.APPLICATION_JSON),
                request -> {
                    String requestId = ErrorHandler.newRequestId();
                    return request.bodyToMono(SessionRequest.class)
                        .switchIfEmpty(Mono.error(
                            new ApiException(ErrorCode.VALIDATION_ERROR, "请求体不能为空")))
                        .flatMap(body -> {
                            String code = body.code();
                            if (code == null || code.isBlank() || code.length() > 128) {
                                return Mono.error(new ApiException(ErrorCode.VALIDATION_ERROR,
                                    "code 需为 1～128 个字符"));
                            }
                            if (!settingsService.wechat().isConfigured()) {
                                return Mono.error(new ApiException(ErrorCode.HALO_UNAVAILABLE,
                                    "服务暂时不可用，请稍后重试"));
                            }
                            // code 只使用一次，不写日志、不缓存原文
                            return weChatClient.code2Session(code)
                                .map(result -> {
                                    String token = sessionService.create(result.openId());
                                    return new SessionResponse(token,
                                        SessionService.SESSION_TTL_SECONDS);
                                });
                        })
                        .flatMap(response -> ServerResponse.ok().bodyValue(response))
                        .onErrorResume(t -> ErrorHandler.respond(t, requestId));
                })
            .build();
    }

    @Override
    public GroupVersion groupVersion() {
        return GroupVersion.parseAPIVersion("api.weapp.halo.run/v1alpha1");
    }

    public record SessionRequest(String code) {
    }

    public record SessionResponse(String sessionToken, long expiresIn) {
    }
}
