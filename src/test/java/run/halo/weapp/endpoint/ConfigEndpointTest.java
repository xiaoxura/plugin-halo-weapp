package run.halo.weapp.endpoint;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import run.halo.weapp.config.SettingsService;

class ConfigEndpointTest {

    private SettingsService settingsService;
    private WebTestClient client;

    @BeforeEach
    void setUp() {
        settingsService = mock(SettingsService.class);
        client = WebTestClient.bindToRouterFunction(
            new ConfigEndpoint(settingsService).endpoint())
            .build();
    }

    @Test
    void returnsOnlyPublicRuntimeConfiguration() {
        when(settingsService.site()).thenReturn(
            new SettingsService.SiteConfig("技术博客", "Halo 驱动", 20,
                "https://cdn.example.com/font.woff2"));
        when(settingsService.features()).thenReturn(
            new SettingsService.FeatureConfig(true, false, true));
        when(settingsService.comment()).thenReturn(
            new SettingsService.CommentConfig(true, true, false, 300, 5, 30));
        when(settingsService.announcement()).thenReturn(
            new SettingsService.AnnouncementConfig(true, "v1", "维护公告"));
        when(settingsService.client()).thenReturn(
            new SettingsService.ClientConfig("0.4.0", "https://example.com/privacy",
                "2026-08-01", 5000L));

        client.get().uri("/config")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.schemaVersion").isEqualTo(1)
            .jsonPath("$.site.blogName").isEqualTo("技术博客")
            .jsonPath("$.features.moments.enabled").isEqualTo(true)
            .jsonPath("$.features.readerAccount.enabled").isEqualTo(true)
            .jsonPath("$.commentOptions.maxLength").isEqualTo(300)
            .jsonPath("$.privacyPolicyUrl").isEqualTo("https://example.com/privacy")
            .jsonPath("$.appSecret").doesNotExist()
            .jsonPath("$.identityKey").doesNotExist();
    }

    @Test
    void settingsFailureMapsToHaloUnavailableWithRequestId() {
        when(settingsService.site()).thenThrow(new IllegalStateException("bad setting"));

        client.get().uri("/config")
            .exchange()
            .expectStatus().isEqualTo(503)
            .expectBody()
            .jsonPath("$.code").isEqualTo("HALO_UNAVAILABLE")
            .jsonPath("$.requestId").isNotEmpty()
            .jsonPath("$.appSecret").doesNotExist();
    }
}
