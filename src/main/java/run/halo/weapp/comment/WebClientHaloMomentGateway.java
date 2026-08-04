package run.halo.weapp.comment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Duration;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import run.halo.app.infra.ExternalUrlSupplier;
import run.halo.weapp.config.SettingsService;
import run.halo.weapp.error.ApiException;
import run.halo.weapp.error.ErrorCode;

/**
 * Loopback validator for PluginMoments' anonymous Public API.
 *
 * <p>Only the JSON contract is used here.  The optional Moment plugin is not a
 * compile-time dependency of this plugin.</p>
 */
@Component
public class WebClientHaloMomentGateway implements HaloMomentGateway {

    private static final Logger log = LoggerFactory.getLogger(WebClientHaloMomentGateway.class);
    private static final String MOMENTS_PATH =
        "apis/api.moment.halo.run/v1alpha1/moments";
    private static final Pattern RESOURCE_NAME =
        Pattern.compile("^[A-Za-z0-9][A-Za-z0-9.-]{0,127}$");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final WebClient webClient;
    private final ExternalUrlSupplier externalUrlSupplier;
    private final SettingsService settings;

    @Autowired
    public WebClientHaloMomentGateway(ExternalUrlSupplier externalUrlSupplier,
                                      SettingsService settings) {
        this(WebClient.builder().build(), externalUrlSupplier, settings);
    }

    WebClientHaloMomentGateway(WebClient webClient, ExternalUrlSupplier externalUrlSupplier,
                               SettingsService settings) {
        this.webClient = webClient;
        this.externalUrlSupplier = externalUrlSupplier;
        this.settings = settings;
    }

    @Override
    public Mono<Void> validateCommentable(String momentName) {
        if (momentName == null || !RESOURCE_NAME.matcher(momentName).matches()) {
            return Mono.error(new ApiException(ErrorCode.VALIDATION_ERROR, "瞬间标识不合法"));
        }
        URI uri = externalUrlSupplier.get().resolve(MOMENTS_PATH + "/" + momentName);
        return webClient.get()
            .uri(uri)
            .accept(MediaType.APPLICATION_JSON)
            .exchangeToMono(response -> {
                int status = response.statusCode().value();
                if (status == 404) {
                    return Mono.error(new ApiException(ErrorCode.MOMENT_NOT_FOUND,
                        "瞬间不存在或已删除"));
                }
                if (status >= 500) {
                    log.warn("[weapp] moment validation unavailable status={}", status);
                    return Mono.error(unavailable());
                }
                if (status >= 400) {
                    return Mono.error(new ApiException(ErrorCode.MOMENT_NOT_FOUND,
                        "瞬间不存在或已删除"));
                }
                return response.bodyToMono(String.class)
                    .flatMap(WebClientHaloMomentGateway::parseJson)
                    .flatMap(json -> isCommentable(json)
                        ? Mono.empty()
                        : Mono.error(new ApiException(ErrorCode.MOMENT_NOT_FOUND,
                            "瞬间不存在或已删除")));
            })
            .then()
            .timeout(Duration.ofMillis(settings.client().upstreamTimeoutMillis()))
            .onErrorMap(t -> t instanceof ApiException ? t : unavailable());
    }

    private static boolean isCommentable(JsonNode json) {
        JsonNode metadata = json.path("metadata");
        JsonNode spec = json.path("spec");
        return !metadata.hasNonNull("deletionTimestamp")
            && !spec.path("deleted").asBoolean(false)
            && "PUBLIC".equals(spec.path("visible").asText())
            && spec.path("approved").asBoolean(false);
    }

    private static Mono<JsonNode> parseJson(String body) {
        return Mono.fromCallable(() -> OBJECT_MAPPER.readTree(body));
    }

    private static ApiException unavailable() {
        return new ApiException(ErrorCode.HALO_UNAVAILABLE, "服务暂时不可用，请稍后重试");
    }
}
