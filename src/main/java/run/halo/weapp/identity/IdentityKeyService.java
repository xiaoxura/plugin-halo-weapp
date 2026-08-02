package run.halo.weapp.identity;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.plugin.PluginContext;
import run.halo.weapp.error.ApiException;
import run.halo.weapp.error.ErrorCode;

/**
 * 插件身份 HMAC 密钥管理。
 *
 * <p>首次需要时生成 32 字节 SecureRandom，并以 Base64 写入插件内部 ConfigMap 的
 * {@code identityKey} 数据项。已存在但损坏的 key 一律失败关闭，绝不静默轮换。</p>
 */
@Component
public class IdentityKeyService {

    static final String DATA_KEY = "identityKey";
    static final int KEY_BYTES = 32;
    static final String PLUGIN_NAME_LABEL = "plugin.halo.run/plugin-name";
    private static final String CONFIG_MAP_SUFFIX = "-identity";
    private static final int UPDATE_ATTEMPTS = 3;

    private final ReactiveExtensionClient extensionClient;
    private final String configMapName;
    private final String pluginName;
    private final SecureRandom secureRandom;
    private final AtomicReference<byte[]> cachedKey = new AtomicReference<>();
    private final AtomicReference<Mono<byte[]>> inFlight = new AtomicReference<>();

    public IdentityKeyService(ReactiveExtensionClient extensionClient,
                              PluginContext pluginContext) {
        this(extensionClient, pluginContext.getName() + CONFIG_MAP_SUFFIX,
            pluginContext.getName(), new SecureRandom());
    }

    /** 测试可见：注入 ConfigMap 名称、插件名和随机源。 */
    IdentityKeyService(ReactiveExtensionClient extensionClient, String configMapName,
                       String pluginName, SecureRandom secureRandom) {
        this.extensionClient = extensionClient;
        this.configMapName = configMapName;
        this.pluginName = pluginName;
        this.secureRandom = secureRandom;
    }

    /** 返回 key 的防御性副本；并发首次调用共享同一个初始化 Mono。 */
    public Mono<byte[]> getOrCreate() {
        return Mono.defer(() -> {
            byte[] cached = cachedKey.get();
            if (cached != null) {
                return Mono.just(cached.clone());
            }
            Mono<byte[]> running = inFlight.get();
            if (running != null) {
                return running.map(byte[]::clone);
            }

            AtomicReference<Mono<byte[]>> self = new AtomicReference<>();
            Mono<byte[]> created = loadOrCreate()
                .doOnNext(key -> cachedKey.compareAndSet(null, key.clone()))
                .onErrorMap(t -> !(t instanceof ApiException), t -> unavailable())
                .doFinally(signal -> inFlight.compareAndSet(self.get(), null))
                .cache();
            self.set(created);
            if (!inFlight.compareAndSet(null, created)) {
                Mono<byte[]> winner = inFlight.get();
                // 胜者可能在 CAS 失败与读取之间同步完成并清空 inFlight；此时重试缓存读取，
                // 避免极窄并发窗口中的空指针。
                return winner == null ? getOrCreate() : winner.map(byte[]::clone);
            }
            return created.map(byte[]::clone);
        });
    }

    private Mono<byte[]> loadOrCreate() {
        return extensionClient.fetch(ConfigMap.class, configMapName)
            .flatMap(configMap -> {
                Optional<byte[]> existing = readKey(configMap);
                if (existing.isPresent()) {
                    return Mono.just(existing.get());
                }
                return initializeExisting(configMap, randomKey(), 0);
            })
            .switchIfEmpty(Mono.defer(() -> createConfigMap(randomKey())));
    }

    private Mono<byte[]> createConfigMap(byte[] key) {
        ConfigMap configMap = new ConfigMap();
        Metadata metadata = new Metadata();
        metadata.setName(configMapName);
        metadata.setLabels(Map.of(PLUGIN_NAME_LABEL, pluginName));
        configMap.setMetadata(metadata);
        configMap.setData(Map.of(DATA_KEY, encode(key)));
        return extensionClient.create(configMap)
            .thenReturn(key)
            .onErrorResume(createError -> extensionClient.fetch(ConfigMap.class, configMapName)
                .flatMap(existing -> readKey(existing)
                    .map(Mono::just)
                    .orElseGet(() -> initializeExisting(existing, key, 0)))
                .switchIfEmpty(Mono.error(createError)));
    }

    private Mono<byte[]> initializeExisting(ConfigMap configMap, byte[] key, int attempt) {
        Map<String, String> data = configMap.getData() == null
            ? new HashMap<>() : new HashMap<>(configMap.getData());
        data.put(DATA_KEY, encode(key));
        configMap.setData(data);
        return extensionClient.update(configMap)
            .thenReturn(key)
            .onErrorResume(updateError -> extensionClient.fetch(ConfigMap.class, configMapName)
                .flatMap(latest -> {
                    Optional<byte[]> winner = readKey(latest);
                    if (winner.isPresent()) {
                        return Mono.just(winner.get());
                    }
                    if (attempt + 1 < UPDATE_ATTEMPTS) {
                        return initializeExisting(latest, key, attempt + 1);
                    }
                    return Mono.error(updateError);
                })
                .switchIfEmpty(Mono.error(updateError)));
    }

    /** 空值表示尚未初始化；非空但非法表示密钥损坏，禁止自动替换。 */
    private static Optional<byte[]> readKey(ConfigMap configMap) {
        if (configMap.getData() == null) {
            return Optional.empty();
        }
        String encoded = configMap.getData().get(DATA_KEY);
        if (encoded == null || encoded.isBlank()) {
            return Optional.empty();
        }
        try {
            byte[] key = Base64.getDecoder().decode(encoded);
            if (key.length != KEY_BYTES) {
                throw unavailable();
            }
            return Optional.of(key);
        } catch (IllegalArgumentException e) {
            throw unavailable();
        }
    }

    private byte[] randomKey() {
        byte[] key = new byte[KEY_BYTES];
        secureRandom.nextBytes(key);
        return key;
    }

    private static String encode(byte[] key) {
        return Base64.getEncoder().encodeToString(key);
    }

    private static ApiException unavailable() {
        return new ApiException(ErrorCode.HALO_UNAVAILABLE,
            "身份服务暂时不可用，请稍后重试");
    }
}
