package run.halo.weapp.identity;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.Secret;
import run.halo.app.plugin.PluginContext;
import run.halo.weapp.error.ApiException;
import run.halo.weapp.error.ErrorCode;

class IdentityKeyServiceTest {

    private static final String PLUGIN_NAME = "plugin-halo-weapp";
    private static final String RESOURCE_NAME = "plugin-halo-weapp-identity";

    private ReactiveExtensionClient client;

    @BeforeEach
    void setUp() {
        client = mock(ReactiveExtensionClient.class);
        when(client.fetch(Secret.class, RESOURCE_NAME)).thenReturn(Mono.empty());
        when(client.fetch(ConfigMap.class, RESOURCE_NAME)).thenReturn(Mono.empty());
        when(client.list(eq(WeAppUser.class), any(), any())).thenReturn(Flux.empty());
    }

    @Test
    void readsExisting32ByteKeyWithoutUpdatingSecret() {
        byte[] expected = bytes(7);
        when(client.fetch(Secret.class, RESOURCE_NAME)).thenReturn(Mono.just(secret(expected)));

        IdentityKeyService service = service();
        byte[] first = service.getOrCreate().block();
        byte[] second = service.getOrCreate().block();
        assertArrayEquals(expected, first);
        assertArrayEquals(expected, second);
        assertNotSame(first, second, "调用方必须获得防御性副本");
        verify(client, never()).update(any(Secret.class));
        verify(client, never()).create(any(Secret.class));
    }

    @Test
    void concurrentInitializationIsSingleFlightAndCreatesOpaqueSecret() {
        AtomicReference<Secret> created = new AtomicReference<>();
        when(client.create(any(Secret.class))).thenAnswer(invocation -> {
            Secret value = invocation.getArgument(0);
            created.set(value);
            return Mono.just(value);
        });

        IdentityKeyService service = service();
        StepVerifier.create(Mono.zip(service.getOrCreate(), service.getOrCreate()))
            .assertNext(tuple -> {
                assertArrayEquals(tuple.getT1(), tuple.getT2());
                assertEquals(IdentityKeyService.KEY_BYTES, tuple.getT1().length);
            })
            .verifyComplete();

        verify(client, times(1)).create(any(Secret.class));
        assertEquals(Secret.SECRET_TYPE_OPAQUE, created.get().getType());
        assertNull(created.get().getStringData());
        assertEquals(1, created.get().getData().size());
        assertEquals(IdentityKeyService.KEY_BYTES,
            created.get().getData().get(IdentityKeyService.DATA_KEY).length);
    }

    @Test
    void invalidPersistedSecretFailsClosedAndIsNeverRotated() {
        when(client.fetch(Secret.class, RESOURCE_NAME))
            .thenReturn(Mono.just(secret(new byte[8])));

        expectUnavailable(service().getOrCreate());
        verify(client, never()).update(any(Secret.class));
        verify(client, never()).create(any(Secret.class));
    }

    @Test
    void missingOrEmptySecretFailsClosedWhenReadersAlreadyExist() {
        WeAppUser existingReader = new WeAppUser();
        Metadata readerMetadata = new Metadata();
        readerMetadata.setName("reader-existing");
        existingReader.setMetadata(readerMetadata);
        when(client.list(eq(WeAppUser.class), any(), any()))
            .thenReturn(Flux.just(existingReader));

        expectUnavailable(service().getOrCreate());
        verify(client, never()).create(any(Secret.class));

        Secret empty = secret(null);
        when(client.fetch(Secret.class, RESOURCE_NAME)).thenReturn(Mono.just(empty));
        expectUnavailable(service().getOrCreate());
        verify(client, never()).update(any(Secret.class));
    }

    @Test
    void productionConstructorCreatesDedicatedSecretWithoutPrintableKey() {
        PluginContext pluginContext = mock(PluginContext.class);
        when(pluginContext.getName()).thenReturn(PLUGIN_NAME);
        AtomicReference<Secret> created = new AtomicReference<>();
        when(client.create(any(Secret.class))).thenAnswer(invocation -> {
            Secret value = invocation.getArgument(0);
            created.set(value);
            return Mono.just(value);
        });

        byte[] key = new IdentityKeyService(client, pluginContext).getOrCreate().block();
        Secret persisted = created.get();
        assertEquals(RESOURCE_NAME, persisted.getMetadata().getName());
        assertEquals(Map.of(IdentityKeyService.PLUGIN_NAME_LABEL, PLUGIN_NAME),
            persisted.getMetadata().getLabels());
        assertEquals(Secret.SECRET_TYPE_OPAQUE, persisted.getType());
        assertNull(persisted.getStringData());
        assertEquals(1, persisted.getData().size());
        assertArrayEquals(key, persisted.getData().get(IdentityKeyService.DATA_KEY));
        assertFalse(persisted.toString().contains(Base64.getEncoder().encodeToString(key)),
            "Halo 删除日志会调用 toString，Secret 不得打印 key");
    }

    @Test
    void migratesLegacyConfigMapToSecretAndScrubsOnlyKey() {
        byte[] expected = bytes(11);
        ConfigMap legacy = configMap(new HashMap<>(Map.of(
            IdentityKeyService.DATA_KEY, Base64.getEncoder().encodeToString(expected),
            "preserved", "opaque")));
        AtomicReference<Secret> created = new AtomicReference<>();
        AtomicReference<ConfigMap> scrubbed = new AtomicReference<>();
        when(client.fetch(ConfigMap.class, RESOURCE_NAME)).thenReturn(Mono.just(legacy));
        when(client.create(any(Secret.class))).thenAnswer(invocation -> {
            Secret value = invocation.getArgument(0);
            created.set(value);
            return Mono.just(value);
        });
        when(client.update(any(ConfigMap.class))).thenAnswer(invocation -> {
            ConfigMap value = invocation.getArgument(0);
            scrubbed.set(value);
            return Mono.just(value);
        });
        // 迁移保留原 key，即使已经存在读者也不生成新命名空间。
        when(client.list(eq(WeAppUser.class), any(), any()))
            .thenReturn(Flux.just(new WeAppUser()));

        service().migrateLegacyIfPresent().block();
        assertArrayEquals(expected, created.get().getData().get(IdentityKeyService.DATA_KEY));
        assertFalse(scrubbed.get().getData().containsKey(IdentityKeyService.DATA_KEY));
        assertEquals("opaque", scrubbed.get().getData().get("preserved"));
        verify(client).update(any(ConfigMap.class));
    }

    @Test
    void startupMigrationDoesNotPreGenerateSecretWithoutLegacyKey() {
        service().migrateLegacyIfPresent().block();

        verify(client, never()).create(any(Secret.class));
        verify(client, never()).list(eq(WeAppUser.class), any(), any());
    }

    @Test
    void emptySecretCanRecoverLegacyKeyThenScrubsConfigMap() {
        byte[] expected = bytes(19);
        Secret empty = secret(null);
        ConfigMap legacy = configMap(Map.of(IdentityKeyService.DATA_KEY,
            Base64.getEncoder().encodeToString(expected)));
        AtomicReference<Secret> updated = new AtomicReference<>();
        when(client.fetch(Secret.class, RESOURCE_NAME)).thenReturn(Mono.just(empty));
        when(client.fetch(ConfigMap.class, RESOURCE_NAME)).thenReturn(Mono.just(legacy));
        when(client.update(any(Secret.class))).thenAnswer(invocation -> {
            Secret value = invocation.getArgument(0);
            updated.set(value);
            return Mono.just(value);
        });
        when(client.update(any(ConfigMap.class))).thenReturn(Mono.just(legacy));

        assertArrayEquals(expected, service().getOrCreate().block());
        assertArrayEquals(expected, updated.get().getData().get(IdentityKeyService.DATA_KEY));
        assertFalse(legacy.getData().containsKey(IdentityKeyService.DATA_KEY));
    }

    @Test
    void conflictingSecretAndLegacyKeysFailClosedWithoutScrubbing() {
        when(client.fetch(Secret.class, RESOURCE_NAME))
            .thenReturn(Mono.just(secret(bytes(1))));
        ConfigMap legacy = configMap(Map.of(IdentityKeyService.DATA_KEY,
            Base64.getEncoder().encodeToString(bytes(2))));
        when(client.fetch(ConfigMap.class, RESOURCE_NAME)).thenReturn(Mono.just(legacy));

        expectUnavailable(service().migrateLegacyIfPresent());
        verify(client, never()).update(any(ConfigMap.class));
    }

    @Test
    void invalidLegacyKeyFailsClosedWithoutCreatingSecret() {
        ConfigMap legacy = configMap(Map.of(IdentityKeyService.DATA_KEY,
            Base64.getEncoder().encodeToString(new byte[8])));
        when(client.fetch(ConfigMap.class, RESOURCE_NAME)).thenReturn(Mono.just(legacy));

        expectUnavailable(service().migrateLegacyIfPresent());
        verify(client, never()).create(any(Secret.class));
        verify(client, never()).update(any(ConfigMap.class));
    }

    private void expectUnavailable(Mono<?> result) {
        StepVerifier.create(result)
            .expectErrorSatisfies(error -> {
                assertTrue(error instanceof ApiException);
                assertEquals(ErrorCode.HALO_UNAVAILABLE, ((ApiException) error).code());
            })
            .verify();
    }

    private IdentityKeyService service() {
        return new IdentityKeyService(client, RESOURCE_NAME, PLUGIN_NAME, new SecureRandom());
    }

    private static Secret secret(byte[] key) {
        Secret secret = new Secret();
        Metadata metadata = new Metadata();
        metadata.setName(RESOURCE_NAME);
        secret.setMetadata(metadata);
        secret.setType(Secret.SECRET_TYPE_OPAQUE);
        secret.setData(key == null ? Map.of() : Map.of(IdentityKeyService.DATA_KEY, key));
        return secret;
    }

    private static ConfigMap configMap(Map<String, String> data) {
        ConfigMap configMap = new ConfigMap();
        Metadata metadata = new Metadata();
        metadata.setName(RESOURCE_NAME);
        configMap.setMetadata(metadata);
        configMap.setData(data);
        return configMap;
    }

    private static byte[] bytes(int seed) {
        byte[] value = new byte[IdentityKeyService.KEY_BYTES];
        for (int i = 0; i < value.length; i++) value[i] = (byte) (seed + i);
        return value;
    }
}
