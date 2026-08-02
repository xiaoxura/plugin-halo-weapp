package run.halo.weapp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import run.halo.app.extension.GroupVersionKind;
import run.halo.app.extension.Scheme;
import run.halo.app.extension.SchemeManager;
import run.halo.app.plugin.PluginContext;
import run.halo.weapp.identity.IdentityKeyService;
import run.halo.weapp.identity.WeAppUser;
import run.halo.weapp.security.SessionService;

class WeappPluginTest {

    @Test
    void registersAndUnregistersPrivateReaderSchemeAndClearsSessions() {
        PluginContext context = mock(PluginContext.class);
        SchemeManager schemeManager = mock(SchemeManager.class);
        SessionService sessions = mock(SessionService.class);
        IdentityKeyService identityKeys = mock(IdentityKeyService.class);
        Scheme scheme = Scheme.buildFromType(WeAppUser.class);
        when(identityKeys.migrateLegacyIfPresent()).thenReturn(Mono.empty());
        when(schemeManager.get(WeAppUser.class)).thenReturn(scheme);

        WeappPlugin plugin = new WeappPlugin(context, schemeManager, sessions, identityKeys);
        plugin.start();
        verify(identityKeys).migrateLegacyIfPresent();
        verify(schemeManager).register(WeAppUser.class);
        assertEquals(new GroupVersionKind("weapp.halo.run", "v1alpha1", "WeAppUser"),
            scheme.groupVersionKind());
        assertEquals("weappusers", scheme.plural());

        plugin.stop();
        verify(sessions).clear();
        verify(schemeManager).unregister(scheme);
    }

    @Test
    void legacyKeyMigrationFailurePreventsReaderSchemeStartup() {
        PluginContext context = mock(PluginContext.class);
        SchemeManager schemeManager = mock(SchemeManager.class);
        SessionService sessions = mock(SessionService.class);
        IdentityKeyService identityKeys = mock(IdentityKeyService.class);
        when(identityKeys.migrateLegacyIfPresent())
            .thenReturn(Mono.error(new IllegalStateException("migration failed")));

        WeappPlugin plugin = new WeappPlugin(context, schemeManager, sessions, identityKeys);
        assertThrows(IllegalStateException.class, plugin::start);
        verify(schemeManager, never()).register(WeAppUser.class);
    }
}
