package run.halo.weapp.endpoint;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import run.halo.weapp.auth.AuthService;
import run.halo.weapp.error.ApiException;
import run.halo.weapp.error.ErrorCode;

class AuthEndpointTest {

    private AuthService authService;
    private WebTestClient client;

    @BeforeEach
    void setUp() {
        authService = mock(AuthService.class);
        client = WebTestClient.bindToRouterFunction(new AuthEndpoint(authService).endpoint())
            .build();
    }

    @Test
    void loginReturnsOnlyPublicProfileAndSessionContract() {
        when(authService.login(any(), eq("0.4.0"), any())).thenReturn(Mono.just(
            new AuthService.AuthResult("opaque-token", 5400,
                new AuthService.ReaderProfile("读者昵称", "v1"))));

        client.post().uri("/auth/login")
            .header("X-WeApp-Client-Version", "0.4.0")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""
                {"code":"wx-code","privacyConsentVersion":"v1","displayName":"读者昵称"}
                """)
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.sessionToken").isEqualTo("opaque-token")
            .jsonPath("$.expiresIn").isEqualTo(5400)
            .jsonPath("$.profile.displayName").isEqualTo("读者昵称")
            .jsonPath("$.profile.privacyPolicyVersion").isEqualTo("v1")
            .jsonPath("$.openId").doesNotExist()
            .jsonPath("$.readerName").doesNotExist();

        when(authService.login(any(), eq("0.4.0"), any())).thenReturn(Mono.error(
            new ApiException(ErrorCode.PRIVACY_CONSENT_REQUIRED, "请先同意当前隐私政策")));
        client.post().uri("/auth/login")
            .header("X-WeApp-Client-Version", "0.4.0")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"code\":\"wx-code\",\"privacyConsentVersion\":\"old\"}")
            .exchange()
            .expectStatus().isEqualTo(428)
            .expectBody()
            .jsonPath("$.code").isEqualTo("PRIVACY_CONSENT_REQUIRED")
            .jsonPath("$.requestId").isNotEmpty()
            .jsonPath("$.retryAfter").doesNotExist();

        when(authService.login(any(), eq("0.4.0"), any())).thenReturn(Mono.error(
            new ApiException(ErrorCode.RATE_LIMITED, "操作过于频繁，请稍后再试", 23)));
        client.post().uri("/auth/login")
            .header("X-WeApp-Client-Version", "0.4.0")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"code\":\"wx-code\",\"privacyConsentVersion\":\"v1\"}")
            .exchange()
            .expectStatus().isEqualTo(429)
            .expectBody()
            .jsonPath("$.code").isEqualTo("RATE_LIMITED")
            .jsonPath("$.requestId").isNotEmpty()
            .jsonPath("$.retryAfter").isEqualTo(23);
    }

    @Test
    void profileRoutesUseSessionHeaderAndMissingHeaderHasStableError() {
        when(authService.getProfile("token")).thenReturn(Mono.just(
            new AuthService.ReaderProfile("读者昵称", "v1")));
        client.get().uri("/auth/profile")
            .header("X-WeApp-Session", "token")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.displayName").isEqualTo("读者昵称");

        client.get().uri("/auth/profile")
            .exchange()
            .expectStatus().isUnauthorized()
            .expectBody()
            .jsonPath("$.code").isEqualTo("SESSION_REQUIRED")
            .jsonPath("$.requestId").isNotEmpty();

        when(authService.getProfile("boom")).thenReturn(Mono.error(new IllegalStateException("secret")));
        client.get().uri("/auth/profile")
            .header("X-WeApp-Session", "boom")
            .exchange()
            .expectStatus().isEqualTo(503)
            .expectBody()
            .jsonPath("$.code").isEqualTo("HALO_UNAVAILABLE")
            .jsonPath("$.requestId").isNotEmpty();
    }

    @Test
    void patchLogoutAndDeleteRoutesMapToExpectedMethods() {
        when(authService.updateProfile(eq("token"), any(), eq("0.4.0"), any()))
            .thenReturn(Mono.just(new AuthService.ReaderProfile("新昵称", "v1")));
        when(authService.logout("token")).thenReturn(Mono.empty());
        when(authService.deleteAccount("token")).thenReturn(Mono.empty());

        client.patch().uri("/auth/profile")
            .header("X-WeApp-Session", "token")
            .header("X-WeApp-Client-Version", "0.4.0")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"displayName\":\"新昵称\",\"privacyConsentVersion\":\"v1\"}")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.displayName").isEqualTo("新昵称");

        when(authService.updateProfile(eq("token"), any(), eq("0.4.0"), any()))
            .thenReturn(Mono.error(new ApiException(
                ErrorCode.PRIVACY_CONSENT_REQUIRED, "请先同意当前隐私政策")));
        client.patch().uri("/auth/profile")
            .header("X-WeApp-Session", "token")
            .header("X-WeApp-Client-Version", "0.4.0")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"displayName\":\"新昵称\",\"privacyConsentVersion\":\"old\"}")
            .exchange()
            .expectStatus().isEqualTo(428)
            .expectBody()
            .jsonPath("$.code").isEqualTo("PRIVACY_CONSENT_REQUIRED")
            .jsonPath("$.requestId").isNotEmpty()
            .jsonPath("$.retryAfter").doesNotExist();

        when(authService.updateProfile(eq("token"), any(), eq("0.4.0"), any()))
            .thenReturn(Mono.error(new ApiException(
                ErrorCode.RATE_LIMITED, "操作过于频繁，请稍后再试", 19)));
        client.patch().uri("/auth/profile")
            .header("X-WeApp-Session", "token")
            .header("X-WeApp-Client-Version", "0.4.0")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"displayName\":\"新昵称\",\"privacyConsentVersion\":\"v1\"}")
            .exchange()
            .expectStatus().isEqualTo(429)
            .expectBody()
            .jsonPath("$.code").isEqualTo("RATE_LIMITED")
            .jsonPath("$.requestId").isNotEmpty()
            .jsonPath("$.retryAfter").isEqualTo(19);

        client.delete().uri("/auth/session")
            .header("X-WeApp-Session", "token")
            .exchange()
            .expectStatus().isNoContent();

        client.delete().uri("/auth/account")
            .header("X-WeApp-Session", "token")
            .exchange()
            .expectStatus().isNoContent();
    }

    @Test
    void emptyOrMalformedBodiesAndBlankSessionHeadersFailWithStableValidationErrors() {
        client.post().uri("/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .exchange()
            .expectStatus().isBadRequest()
            .expectBody()
            .jsonPath("$.code").isEqualTo("VALIDATION_ERROR")
            .jsonPath("$.requestId").isNotEmpty();

        client.post().uri("/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"code\":")
            .exchange()
            .expectStatus().isBadRequest()
            .expectBody()
            .jsonPath("$.code").isEqualTo("VALIDATION_ERROR")
            .jsonPath("$.requestId").isNotEmpty();

        client.patch().uri("/auth/profile")
            .header("X-WeApp-Session", "token")
            .header("X-WeApp-Client-Version", "0.4.0")
            .contentType(MediaType.APPLICATION_JSON)
            .exchange()
            .expectStatus().isBadRequest()
            .expectBody()
            .jsonPath("$.code").isEqualTo("VALIDATION_ERROR")
            .jsonPath("$.requestId").isNotEmpty();

        client.get().uri("/auth/profile")
            .header("X-WeApp-Session", "   ")
            .exchange()
            .expectStatus().isUnauthorized()
            .expectBody()
            .jsonPath("$.code").isEqualTo("SESSION_REQUIRED")
            .jsonPath("$.requestId").isNotEmpty();

        client.delete().uri("/auth/session")
            .exchange()
            .expectStatus().isUnauthorized()
            .expectBody()
            .jsonPath("$.code").isEqualTo("SESSION_REQUIRED")
            .jsonPath("$.requestId").isNotEmpty();

        client.delete().uri("/auth/account")
            .exchange()
            .expectStatus().isUnauthorized()
            .expectBody()
            .jsonPath("$.code").isEqualTo("SESSION_REQUIRED")
            .jsonPath("$.requestId").isNotEmpty();
    }

    @Test
    void profileErrorsPreserveHttpStatusWithoutRetryAfterForNonRateLimitedCodes() {
        when(authService.getProfile("forbidden")).thenReturn(Mono.error(new ApiException(
            ErrorCode.READER_ACCOUNT_DISABLED, "微信读者登录暂未开放")));
        client.get().uri("/auth/profile")
            .header("X-WeApp-Session", "forbidden")
            .exchange()
            .expectStatus().isForbidden()
            .expectBody()
            .jsonPath("$.code").isEqualTo("READER_ACCOUNT_DISABLED")
            .jsonPath("$.requestId").isNotEmpty()
            .jsonPath("$.retryAfter").doesNotExist();

        when(authService.getProfile("missing")).thenReturn(Mono.error(new ApiException(
            ErrorCode.READER_NOT_FOUND, "微信读者账号不存在")));
        client.get().uri("/auth/profile")
            .header("X-WeApp-Session", "missing")
            .exchange()
            .expectStatus().isNotFound()
            .expectBody()
            .jsonPath("$.code").isEqualTo("READER_NOT_FOUND")
            .jsonPath("$.requestId").isNotEmpty()
            .jsonPath("$.retryAfter").doesNotExist();
    }
}
