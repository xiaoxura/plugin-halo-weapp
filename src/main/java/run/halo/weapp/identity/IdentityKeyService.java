package run.halo.weapp.identity;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.Secret;
import run.halo.app.plugin.PluginContext;
import run.halo.weapp.error.ApiException;
import run.halo.weapp.error.ErrorCode;

/**
 * 插件身份 HMAC 密钥管理。
 *
 * <p>首次需要时生成 32 字节 SecureRandom，并写入插件内部 Opaque Secret 的
 * {@code identityKey} 数据项。使用 Secret.data 而非 ConfigMap，避免 Halo 在扩展删除日志中
 * 序列化明文。已存在但损坏的 key 一律失败关闭，绝不静默轮换。</p>
 *
 * <p>早期 v0.2.0 RC 曾把 Base64 key 写入同名 ConfigMap。发现 Halo 2.23.3 的删除日志会输出
 * ConfigMap.data 后，本服务会原值迁移到 Secret，并在校验两端一致后清除旧 ConfigMap 中的
 * identityKey；未知或冲突状态一律失败关闭。</p>
 */
@Component
public class IdentityKeyService {

    static final String DATA_KEY = "identityKey";
    static final int KEY_BYTES = 32;
    static final String PLUGIN_NAME_LABEL = "plugin.halo.run/plugin-name";
    private static final String RESOURCE_SUFFIX = "-identity";
    private static final int UPDATE_ATTEMPTS = 3;
    private static final Pattern DNS1123_SUBDOMAIN = Pattern.compile(
        "^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)*$");

    private final ReactiveExtensionClient extensionClient;
    private final String resourceName;
    private final String pluginName;
    private final SecureRandom secureRandom;
    private final AtomicReference<byte[]> cachedKey = new AtomicReference<>();
    private final AtomicReference<Mono<byte[]>> inFlight = new AtomicReference<>();

    @Autowired
    public IdentityKeyService(ReactiveExtensionClient extensionClient,
                              PluginContext pluginContext) {
        this(extensionClient, resourceNameFor(pluginContext == null ? null : pluginContext.getName()),
            pluginNameOf(pluginContext), new SecureRandom());
    }

    /** 测试可见：注入 Secret/旧 ConfigMap 名称、插件名和随机源。 */
    IdentityKeyService(ReactiveExtensionClient extensionClient, String resourceName,
                       String pluginName, SecureRandom secureRandom) {
        this.extensionClient = extensionClient;
        this.resourceName = requireResourceName(resourceName);
        this.pluginName = pluginName;
        this.secureRandom = secureRandom;
    }

    private static String resourceNameFor(String pluginName) {
        if (pluginName == null) {
            throw unavailable();
        }
        return requireResourceName(pluginName + RESOURCE_SUFFIX);
    }

    private static String pluginNameOf(PluginContext pluginContext) {
        if (pluginContext == null || pluginContext.getName() == null) {
            throw unavailable();
        }
        return pluginContext.getName();
    }

    private static String requireResourceName(String value) {
        if (value == null || value.length() > 253 || !DNS1123_SUBDOMAIN.matcher(value).matches()) {
            throw unavailable();
        }
        return value;
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

    /**
     * 插件启动时只迁移已经存在的早期 RC ConfigMap，不在尚未使用读者身份的站点预生成 key。
     * 迁移必须在插件进入 STARTED 前完成，避免旧 ConfigMap 在运维删除时被 Halo 明文写入日志。
     */
    public Mono<Void> migrateLegacyIfPresent() {
        return fetchLegacyKey()
            .flatMap(legacyKey -> extensionClient.fetch(Secret.class, resourceName)
                .flatMap(secret -> readSecretKey(secret)
                    .map(existing -> Mono.just(existing)
                        .flatMap(this::scrubMatchingLegacyKey))
                    .orElseGet(() -> initializeExistingSecret(secret, legacyKey, 0)
                        .flatMap(this::scrubMatchingLegacyKey)))
                .switchIfEmpty(Mono.defer(() -> createSecret(legacyKey)
                    .flatMap(this::scrubMatchingLegacyKey))))
            .then()
            .onErrorMap(t -> !(t instanceof ApiException), t -> unavailable());
    }

    private Mono<byte[]> loadOrCreate() {
        return extensionClient.fetch(Secret.class, resourceName)
            .flatMap(this::loadExistingSecret)
            .switchIfEmpty(Mono.defer(this::migrateLegacyOrCreate));
    }

    private Mono<byte[]> loadExistingSecret(Secret secret) {
        Optional<byte[]> existing = readSecretKey(secret);
        if (existing.isPresent()) {
            return scrubMatchingLegacyKey(existing.get());
        }
        // 允许从完整的旧 RC ConfigMap 恢复一个空 Secret；没有旧 key 时仍遵守“已有读者禁止
        // 生成新 key”的不变量。
        return fetchLegacyKey()
            .flatMap(key -> initializeExistingSecret(secret, key, 0)
                .flatMap(this::scrubMatchingLegacyKey))
            .switchIfEmpty(Mono.defer(() -> initializeWhenNoReaders(
                () -> initializeExistingSecret(secret, randomKey(), 0))));
    }

    private Mono<byte[]> migrateLegacyOrCreate() {
        return fetchLegacyKey()
            .flatMap(key -> createSecret(key).flatMap(this::scrubMatchingLegacyKey))
            .switchIfEmpty(Mono.defer(() -> initializeWhenNoReaders(
                () -> createSecret(randomKey()))));
    }

    private Mono<byte[]> fetchLegacyKey() {
        return extensionClient.fetch(ConfigMap.class, resourceName)
            .flatMap(configMap -> readLegacyKey(configMap)
                .map(Mono::just)
                .orElseGet(Mono::empty));
    }

    /**
     * Secret/旧 ConfigMap 缺失或没有 key 只可能在首个读者创建前自动初始化。
     * 已有读者时继续生成新 key 会让全部确定性资源永久不可定位，因此必须失败关闭并等待恢复备份。
     */
    private Mono<byte[]> initializeWhenNoReaders(Supplier<Mono<byte[]>> initializer) {
        return extensionClient.list(WeAppUser.class, ignored -> true, (left, right) -> 0)
            .hasElements()
            .flatMap(hasReaders -> hasReaders ? Mono.error(unavailable()) : initializer.get());
    }

    private Mono<byte[]> createSecret(byte[] key) {
        Secret secret = new Secret();
        Metadata metadata = new Metadata();
        metadata.setName(resourceName);
        metadata.setLabels(Map.of(PLUGIN_NAME_LABEL, pluginName));
        secret.setMetadata(metadata);
        secret.setType(Secret.SECRET_TYPE_OPAQUE);
        secret.setData(Map.of(DATA_KEY, key.clone()));
        return extensionClient.create(secret)
            .thenReturn(key)
            .onErrorResume(createError -> extensionClient.fetch(Secret.class, resourceName)
                .flatMap(existing -> readSecretKey(existing)
                    .map(Mono::just)
                    .orElseGet(() -> initializeExistingSecret(existing, key, 0)))
                .switchIfEmpty(Mono.error(createError)));
    }

    private Mono<byte[]> initializeExistingSecret(Secret secret, byte[] key, int attempt) {
        Map<String, byte[]> data = copySecretData(secret.getData());
        data.put(DATA_KEY, key.clone());
        secret.setData(data);
        secret.setType(Secret.SECRET_TYPE_OPAQUE);
        secret.setStringData(null);
        return extensionClient.update(secret)
            .thenReturn(key)
            .onErrorResume(updateError -> extensionClient.fetch(Secret.class, resourceName)
                .flatMap(latest -> {
                    Optional<byte[]> winner = readSecretKey(latest);
                    if (winner.isPresent()) {
                        return Mono.just(winner.get());
                    }
                    if (attempt + 1 < UPDATE_ATTEMPTS) {
                        return initializeExistingSecret(latest, key, attempt + 1);
                    }
                    return Mono.error(updateError);
                })
                .switchIfEmpty(Mono.error(updateError)));
    }

    /**
     * 迁移成功后清除旧 ConfigMap 的明文，保留其余未知数据。旧 key 与 Secret 不一致时不选择
     * 任一方，以免静默切换身份命名空间。
     */
    private Mono<byte[]> scrubMatchingLegacyKey(byte[] authoritativeKey) {
        return extensionClient.fetch(ConfigMap.class, resourceName)
            .flatMap(legacy -> scrubLegacyKey(legacy, authoritativeKey, 0))
            .thenReturn(authoritativeKey);
    }

    private Mono<Void> scrubLegacyKey(ConfigMap legacy, byte[] authoritativeKey, int attempt) {
        Optional<byte[]> legacyKey = readLegacyKey(legacy);
        if (legacyKey.isEmpty()) {
            return Mono.empty();
        }
        if (!MessageDigest.isEqual(legacyKey.get(), authoritativeKey)) {
            return Mono.error(unavailable());
        }
        Map<String, String> data = legacy.getData() == null
            ? new HashMap<>() : new HashMap<>(legacy.getData());
        data.remove(DATA_KEY);
        legacy.setData(data);
        return extensionClient.update(legacy)
            .then()
            .onErrorResume(updateError -> extensionClient.fetch(ConfigMap.class, resourceName)
                .flatMap(latest -> {
                    Optional<byte[]> latestKey = readLegacyKey(latest);
                    if (latestKey.isEmpty()) {
                        return Mono.empty();
                    }
                    if (!MessageDigest.isEqual(latestKey.get(), authoritativeKey)) {
                        return Mono.error(unavailable());
                    }
                    if (attempt + 1 < UPDATE_ATTEMPTS) {
                        return scrubLegacyKey(latest, authoritativeKey, attempt + 1);
                    }
                    return Mono.error(updateError);
                })
                .switchIfEmpty(Mono.empty()));
    }

    /** 空值表示尚未初始化；非空但非法表示密钥损坏，禁止自动替换。 */
    private static Optional<byte[]> readSecretKey(Secret secret) {
        if (secret.getData() == null) {
            return Optional.empty();
        }
        byte[] key = secret.getData().get(DATA_KEY);
        if (key == null || key.length == 0) {
            return Optional.empty();
        }
        return Optional.of(validateKey(key));
    }

    /** 仅用于一次性迁移早期 RC 的 Base64 ConfigMap。 */
    private static Optional<byte[]> readLegacyKey(ConfigMap configMap) {
        if (configMap.getData() == null) {
            return Optional.empty();
        }
        String encoded = configMap.getData().get(DATA_KEY);
        if (encoded == null || encoded.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(validateKey(Base64.getDecoder().decode(encoded)));
        } catch (IllegalArgumentException e) {
            throw unavailable();
        }
    }

    private static byte[] validateKey(byte[] key) {
        if (key.length != KEY_BYTES) {
            throw unavailable();
        }
        return key.clone();
    }

    private static Map<String, byte[]> copySecretData(Map<String, byte[]> source) {
        Map<String, byte[]> copy = new HashMap<>();
        if (source != null) {
            source.forEach((name, value) -> copy.put(name, value == null ? null : value.clone()));
        }
        return copy;
    }

    private byte[] randomKey() {
        byte[] key = new byte[KEY_BYTES];
        secureRandom.nextBytes(key);
        return key;
    }

    private static ApiException unavailable() {
        return new ApiException(ErrorCode.HALO_UNAVAILABLE,
            "身份服务暂时不可用，请稍后重试");
    }
}
