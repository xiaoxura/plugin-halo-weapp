package run.halo.weapp.endpoint;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import run.halo.weapp.config.SettingsService;
import run.halo.weapp.config.WechatSettings;
import run.halo.weapp.security.SessionService;
import run.halo.weapp.wechat.Code2SessionResult;
import run.halo.weapp.wechat.WeChatClient;

class SessionEndpointTest {

    private SettingsService settingsService;
    private WeChatClient weChatClient;
    private SessionService sessionService;
    private WebTestClient client;

    @BeforeEach
    void setUp() {
        settingsService = mock(SettingsService.class);
        weChatClient = mock(WeChatClient.class);
        sessionService = new SessionService();
        client = WebTestClient.bindToRouterFunction(
            new SessionEndpoint(settingsService, weChatClient, sessionService).endpoint())
            .build();
    }

    @Test
    void missingWechatConfigurationReturnsStable503WithoutCallingWechat() {
        when(settingsService.wechat()).thenReturn(new WechatSettings(null, null));

        client.post().uri("/session")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"code\":\"wx-code\"}")
            .exchange()
            .expectStatus().isEqualTo(503)
            .expectBody()
            .jsonPath("$.code").isEqualTo("HALO_UNAVAILABLE")
            .jsonPath("$.requestId").isNotEmpty()
            .jsonPath("$.sessionToken").doesNotExist();

        client.post().uri("/session")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{}")
            .exchange()
            .expectStatus().isBadRequest()
            .expectBody()
            .jsonPath("$.code").isEqualTo("VALIDATION_ERROR")
            .jsonPath("$.requestId").isNotEmpty();

        client.post().uri("/session")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"code\":\"" + "x".repeat(129) + "\"}")
            .exchange()
            .expectStatus().isBadRequest()
            .expectBody()
            .jsonPath("$.code").isEqualTo("VALIDATION_ERROR")
            .jsonPath("$.requestId").isNotEmpty();

        org.mockito.Mockito.verifyNoInteractions(weChatClient);
    }

    @Test
    void malformedJsonIsValidationError() {
        client.post().uri("/session")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"code\":")
            .exchange()
            .expectStatus().isBadRequest()
            .expectBody()
            .jsonPath("$.code").isEqualTo("VALIDATION_ERROR")
            .jsonPath("$.requestId").isNotEmpty();
    }

    @Test
    void configuredLoginReturnsOpaqueSessionOnly() {
        org.mockito.Mockito.doReturn(new WechatSettings("wx-app", "secret"))
            .when(settingsService).wechat();
        when(weChatClient.code2Session("wx-code"))
            .thenReturn(Mono.just(new Code2SessionResult("openid-test-placeholder")));

        client.post().uri("/session")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"code\":\"wx-code\"}")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.sessionToken").isNotEmpty()
            .jsonPath("$.expiresIn").isEqualTo(SessionService.SESSION_TTL_SECONDS)
            .jsonPath("$.openId").doesNotExist()
            .jsonPath("$.session_key").doesNotExist();
    }

    @Test
    void codeAcceptsTheDocumentedOneTo128CharacterBoundary() {
        when(settingsService.wechat()).thenReturn(new WechatSettings("wx-app", "secret"));
        when(weChatClient.code2Session(anyString()))
            .thenReturn(Mono.just(new Code2SessionResult("openid-test-placeholder")));

        client.post().uri("/session")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"code\":\"x\"}")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.sessionToken").isNotEmpty();

        client.post().uri("/session")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"code\":\"" + "x".repeat(128) + "\"}")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.sessionToken").isNotEmpty();

        verify(weChatClient).code2Session("x");
        verify(weChatClient).code2Session("x".repeat(128));
    }

    @Test
    void blankCodeAndUnreadableSettingsFailClosedBeforeWechat() {
        when(settingsService.wechat()).thenThrow(new IllegalStateException("secret"));

        client.post().uri("/session")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"code\":\"wx-code\"}")
            .exchange()
            .expectStatus().isEqualTo(503)
            .expectBody()
            .jsonPath("$.code").isEqualTo("HALO_UNAVAILABLE")
            .jsonPath("$.requestId").isNotEmpty()
            .jsonPath("$.retryAfter").doesNotExist();

        org.mockito.Mockito.doReturn(new WechatSettings("wx-app", "secret"))
            .when(settingsService).wechat();
        client.post().uri("/session")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"code\":\"   \"}")
            .exchange()
            .expectStatus().isBadRequest()
            .expectBody()
            .jsonPath("$.code").isEqualTo("VALIDATION_ERROR")
            .jsonPath("$.requestId").isNotEmpty();

        org.mockito.Mockito.verifyNoInteractions(weChatClient);
    }

    @Test
    void wechatUpstreamFailureMapsTo502WithoutIssuingSession() {
        when(settingsService.wechat()).thenReturn(new WechatSettings("wx-app", "secret"));
        when(weChatClient.code2Session("wx-code")).thenReturn(Mono.error(
            new run.halo.weapp.error.ApiException(
                run.halo.weapp.error.ErrorCode.WECHAT_UNAVAILABLE,
                "登录服务暂时不可用，请稍后重试")));

        client.post().uri("/session")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"code\":\"wx-code\"}")
            .exchange()
            .expectStatus().isEqualTo(502)
            .expectBody()
            .jsonPath("$.code").isEqualTo("WECHAT_UNAVAILABLE")
            .jsonPath("$.requestId").isNotEmpty()
            .jsonPath("$.sessionToken").doesNotExist()
            .jsonPath("$.retryAfter").doesNotExist();
    }
}
