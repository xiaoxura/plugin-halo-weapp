package run.halo.weapp.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import run.halo.app.plugin.SettingFetcher;

/**
 * SettingsService 默认值 / 异常回落与 PublicConfig 白名单测试。
 */
class SettingsServiceTest {

    private final SettingFetcher fetcher = mock(SettingFetcher.class);
    private final SettingsService settings = new SettingsService(fetcher);

    @Test
    void fallsBackToDefaultsWhenGroupsMissing() {
        when(fetcher.fetch(org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any())).thenReturn(Optional.empty());

        SettingsService.CommentConfig comment = settings.comment();
        assertFalse(comment.commentEnabled());
        assertFalse(comment.submitEnabled());
        assertFalse(comment.replyEnabled());
        assertEquals(500, comment.maxLength());
        assertEquals(3, comment.rateLimitPerMinute());
        assertEquals(20, comment.rateLimitPerHour());

        SettingsService.ClientConfig client = settings.client();
        assertEquals("0.3.0", client.minVersion());
        assertEquals(5000L, client.upstreamTimeoutMillis());

        assertFalse(settings.announcement().enabled());
        assertFalse(settings.wechat().isConfigured());
    }

    @Test
    void fallsBackToDefaultsWhenFetcherThrows() {
        when(fetcher.fetch(org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any()))
            .thenThrow(new RuntimeException("bad setting type"));

        assertFalse(settings.comment().submitEnabled());
        assertEquals(500, settings.comment().maxLength());
        assertEquals(5000L, settings.client().upstreamTimeoutMillis());
        assertFalse(settings.wechat().isConfigured());
    }

    @Test
    void fallsBackPerFieldWhenFieldsAreNull() {
        when(fetcher.fetch("comment", CommentSettings.class)).thenReturn(Optional.of(
            new CommentSettings(true, true, null, null, -1, null)));
        SettingsService.CommentConfig comment = settings.comment();
        assertTrue(comment.commentEnabled());
        assertTrue(comment.submitEnabled());
        assertFalse(comment.replyEnabled());
        assertEquals(500, comment.maxLength());
        assertEquals(3, comment.rateLimitPerMinute());
    }

    @Test
    void readsConfiguredValues() {
        when(fetcher.fetch("wechat", WechatSettings.class))
            .thenReturn(Optional.of(new WechatSettings("wx-app-id", "wx-secret")));
        when(fetcher.fetch("client", ClientSettings.class)).thenReturn(Optional.of(
            new ClientSettings("0.4.0", "https://example.com/privacy", "2026-08-01", 8000L)));

        assertTrue(settings.wechat().isConfigured());
        SettingsService.ClientConfig client = settings.client();
        assertEquals("0.4.0", client.minVersion());
        assertEquals("2026-08-01", client.privacyPolicyVersion());
        assertEquals(8000L, client.upstreamTimeoutMillis());
    }

    @Test
    void publicConfigIsWhitelistOnlyAndNeverContainsSecrets() throws Exception {
        when(fetcher.fetch("wechat", WechatSettings.class))
            .thenReturn(Optional.of(new WechatSettings("wx-app-id", "super-secret-value")));
        when(fetcher.fetch("comment", CommentSettings.class)).thenReturn(Optional.of(
            new CommentSettings(true, true, false, 300, 5, 30)));
        when(fetcher.fetch("announcement", AnnouncementSettings.class)).thenReturn(
            Optional.of(new AnnouncementSettings(true, "v1", "hello")));
        when(fetcher.fetch("client", ClientSettings.class)).thenReturn(Optional.of(
            new ClientSettings("0.3.0", "https://example.com/privacy", "2026-08-01", 5000L)));

        PublicConfig config = PublicConfig.from(settings.comment(), settings.announcement(),
            settings.client());
        assertEquals(1, config.schemaVersion());

        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(config);
        assertFalse(json.contains("super-secret-value"));
        assertFalse(json.contains("appSecret"));
        assertFalse(json.contains("wx-app-id"));
        assertTrue(json.contains("\"maxLength\":300"));
        assertTrue(json.contains("\"nicknameRequired\":true"));
        assertTrue(json.contains("\"privacyPolicyVersion\":\"2026-08-01\""));
    }
}
