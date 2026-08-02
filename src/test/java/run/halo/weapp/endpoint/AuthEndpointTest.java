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

        client.delete().uri("/auth/session")
            .header("X-WeApp-Session", "token")
            .exchange()
            .expectStatus().isNoContent();

        client.delete().uri("/auth/account")
            .header("X-WeApp-Session", "token")
            .exchange()
            .expectStatus().isNoContent();
    }
}
