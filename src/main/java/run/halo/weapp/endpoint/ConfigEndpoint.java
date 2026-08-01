package run.halo.weapp.endpoint;

import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RequestPredicates;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;
import run.halo.weapp.config.PublicConfig;
import run.halo.weapp.config.SettingsService;
import run.halo.weapp.error.ErrorHandler;

/**
 * GET /config：公开运行时配置。任何内部异常 → 500 同款结构，不泄露细节。
 */
@Component
public class ConfigEndpoint implements CustomEndpoint {

    private final SettingsService settingsService;

    public ConfigEndpoint(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return RouterFunctions.route()
            .GET("/config", RequestPredicates.accept(
                    org.springframework.http.MediaType.ALL),
                request -> {
                    String requestId = ErrorHandler.newRequestId();
                    return Mono.fromCallable(() -> PublicConfig.from(
                            settingsService.comment(),
                            settingsService.announcement(),
                            settingsService.client()))
                        .flatMap(config -> ServerResponse.ok().bodyValue(config))
                        .onErrorResume(t -> ErrorHandler.respond(t, requestId));
                })
            .build();
    }

    @Override
    public GroupVersion groupVersion() {
        return GroupVersion.parseAPIVersion("api.weapp.halo.run/v1alpha1");
    }
}
