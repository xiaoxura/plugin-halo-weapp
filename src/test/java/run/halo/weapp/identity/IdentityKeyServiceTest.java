package run.halo.weapp.identity;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
import run.halo.app.plugin.PluginContext;
import run.halo.weapp.error.ApiException;
import run.halo.weapp.error.ErrorCode;

class IdentityKeyServiceTest {

    private static final String PLUGIN_NAME = "plugin-halo-weapp";
    private static final String CONFIG_MAP_NAME = "plugin-halo-weapp-identity";

    private ReactiveExtensionClient client;

    @BeforeEach
    void setUp() {
        client = mock(ReactiveExtensionClient.class);
        when(client.list(eq(WeAppUser.class), any(), any())).thenReturn(Flux.empty());
    }

    @Test
    void readsExisting32ByteKeyWithoutUpdatingConfigMap() {
        byte[] expected = bytes(7);
        ConfigMap configMap = configMap(Map.of(
            IdentityKeyService.DATA_KEY, Base64.getEncoder().encodeToString(expected)));
        when(client.fetch(ConfigMap.class, CONFIG_MAP_NAME)).thenReturn(Mono.just(configMap));

        IdentityKeyService service = service();
        byte[] first = service.getOrCreate().block();
        byte[] second = service.getOrCreate().block();
        assertArrayEquals(expected, first);
        assertArrayEquals(expected, second);
        assertNotSame(first, second, "调用方必须获得防御性副本");
        verify(client, never()).update(any(ConfigMap.class));
    }

    @Test
    void concurrentInitializationIsSingleFlightAndPreservesOtherConfigData() {
        ConfigMap configMap = configMap(new HashMap<>(Map.of("wechat", "opaque-setting")));
        AtomicReference<ConfigMap> updated = new AtomicReference<>();
        when(client.fetch(ConfigMap.class, CONFIG_MAP_NAME)).thenReturn(Mono.just(configMap));
        when(client.update(any(ConfigMap.class))).thenAnswer(invocation -> {
            ConfigMap value = invocation.getArgument(0);
            updated.set(value);
            return Mono.just(value);
        });

        IdentityKeyService service = service();
        StepVerifier.create(Mono.zip(service.getOrCreate(), service.getOrCreate()))
            .assertNext(tuple -> {
                assertArrayEquals(tuple.getT1(), tuple.getT2());
                assertEquals(IdentityKeyService.KEY_BYTES, tuple.getT1().length);
            })
            .verifyComplete();

        verify(client).update(any(ConfigMap.class));
        assertEquals("opaque-setting", updated.get().getData().get("wechat"));
        byte[] persisted = Base64.getDecoder().decode(
            updated.get().getData().get(IdentityKeyService.DATA_KEY));
        assertEquals(IdentityKeyService.KEY_BYTES, persisted.length);
    }

    @Test
    void invalidPersistedKeyFailsClosedAndIsNeverRotated() {
        ConfigMap configMap = configMap(Map.of(IdentityKeyService.DATA_KEY,
            Base64.getEncoder().encodeToString(new byte[8])));
        when(client.fetch(ConfigMap.class, CONFIG_MAP_NAME)).thenReturn(Mono.just(configMap));
        IdentityKeyService service = service();

        StepVerifier.create(service.getOrCreate())
            .expectErrorSatisfies(error -> {
                assertTrue(error instanceof ApiException);
                assertEquals(ErrorCode.HALO_UNAVAILABLE, ((ApiException) error).code());
            })
            .verify();
        verify(client, never()).update(any(ConfigMap.class));
    }

    @Test
    void missingOrEmptyKeyFailsClosedWhenReadersAlreadyExist() {
        WeAppUser existingReader = new WeAppUser();
        Metadata readerMetadata = new Metadata();
        readerMetadata.setName("reader-existing");
        existingReader.setMetadata(readerMetadata);
        when(client.list(eq(WeAppUser.class), any(), any()))
            .thenReturn(Flux.just(existingReader));

        when(client.fetch(ConfigMap.class, CONFIG_MAP_NAME)).thenReturn(Mono.empty());
        StepVerifier.create(service().getOrCreate())
            .expectErrorSatisfies(error -> {
                assertTrue(error instanceof ApiException);
                assertEquals(ErrorCode.HALO_UNAVAILABLE, ((ApiException) error).code());
            })
            .verify();
        verify(client, never()).create(any(ConfigMap.class));

        ConfigMap emptyConfigMap = configMap(Map.of());
        when(client.fetch(ConfigMap.class, CONFIG_MAP_NAME)).thenReturn(Mono.just(emptyConfigMap));
        StepVerifier.create(service().getOrCreate())
            .expectErrorSatisfies(error -> {
                assertTrue(error instanceof ApiException);
                assertEquals(ErrorCode.HALO_UNAVAILABLE, ((ApiException) error).code());
            })
            .verify();
        verify(client, never()).update(any(ConfigMap.class));
    }

    @Test
    void productionConstructorCreatesDedicatedConfigMapWithOnlyInternalKey() {
        PluginContext pluginContext = mock(PluginContext.class);
        when(pluginContext.getName()).thenReturn(PLUGIN_NAME);
        when(client.fetch(ConfigMap.class, CONFIG_MAP_NAME)).thenReturn(Mono.empty());
        AtomicReference<ConfigMap> created = new AtomicReference<>();
        when(client.create(any(ConfigMap.class))).thenAnswer(invocation -> {
            ConfigMap value = invocation.getArgument(0);
            created.set(value);
            return Mono.just(value);
        });

        byte[] key = new IdentityKeyService(client, pluginContext).getOrCreate().block();
        assertEquals(CONFIG_MAP_NAME, created.get().getMetadata().getName());
        assertEquals(Map.of(IdentityKeyService.PLUGIN_NAME_LABEL, PLUGIN_NAME),
            created.get().getMetadata().getLabels());
        assertEquals(1, created.get().getData().size());
        assertArrayEquals(key, Base64.getDecoder().decode(
            created.get().getData().get(IdentityKeyService.DATA_KEY)));
    }

    private IdentityKeyService service() {
        return new IdentityKeyService(client, CONFIG_MAP_NAME, PLUGIN_NAME, new SecureRandom());
    }

    private static ConfigMap configMap(Map<String, String> data) {
        ConfigMap configMap = new ConfigMap();
        Metadata metadata = new Metadata();
        metadata.setName(CONFIG_MAP_NAME);
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
