package run.halo.weapp.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import run.halo.weapp.config.SettingsService;
import run.halo.weapp.config.WechatSettings;

class ReaderIdentityServiceTest {

    @Test
    void matchesFrozenHmacSha256VectorAndUses160BitPrefix() {
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) key[i] = (byte) i;
        String name = ReaderIdentityService.deriveReaderName(key, "wx-test", "openid-123");
        assertEquals("reader-cd054c5aa1cc2ab3f3be44c93544951570edb466", name);
        assertTrue(name.matches("reader-[0-9a-f]{40}"));
        assertTrue(!name.contains("openid-123"));
    }

    @Test
    void appIdAndOpenIdBothAffectIdentity() {
        byte[] key = new byte[32];
        String a = ReaderIdentityService.deriveReaderName(key, "app-a", "openid-1");
        String b = ReaderIdentityService.deriveReaderName(key, "app-b", "openid-1");
        String c = ReaderIdentityService.deriveReaderName(key, "app-a", "openid-2");
        assertNotEquals(a, b);
        assertNotEquals(a, c);
    }

    @Test
    void serviceReadsAppIdFromServerSettingsOnly() {
        byte[] key = new byte[32];
        IdentityKeyService keyService = mock(IdentityKeyService.class);
        SettingsService settings = mock(SettingsService.class);
        when(keyService.getOrCreate()).thenReturn(Mono.just(key));
        when(settings.wechat()).thenReturn(new WechatSettings("wx-server", "secret"));
        ReaderIdentityService service = new ReaderIdentityService(keyService, settings);
        assertEquals(ReaderIdentityService.deriveReaderName(key, "wx-server", "openid"),
            service.readerName("openid").block());
    }
}
