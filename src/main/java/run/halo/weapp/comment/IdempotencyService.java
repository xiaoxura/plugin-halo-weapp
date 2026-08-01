package run.halo.weapp.comment;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import run.halo.weapp.error.ApiException;
import run.halo.weapp.error.ErrorCode;

/**
 * 幂等键服务：key = HMAC(openId) + 路由 + X-Idempotency-Key，保留 10 分钟。
 *
 * <p>ConcurrentHashMap 存「请求体指纹(SHA-256) + 完成后的响应」；并发同 key 用
 * single-flight（computeIfAbsent 存共享的 cache 后 Mono）：同 key 同体返回首次结果，
 * 同 key 不同体抛 IDEMPOTENCY_CONFLICT。过期条目懒清理。</p>
 */
@Component
public class IdempotencyService {

    private static final Duration TTL = Duration.ofMinutes(10);

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final Clock clock;

    public IdempotencyService() {
        this(Clock.systemUTC());
    }

    /** 测试可见：注入可控时钟。 */
    IdempotencyService(Clock clock) {
        this.clock = clock;
    }

    /**
     * 以幂等键执行动作。同 key 同体共享首次执行结果（含错误），同 key 不同体拒绝。
     *
     * @param key 幂等键（用户标识 + 路由 + 客户端幂等键）
     * @param bodyFingerprint 请求体指纹（{@link #fingerprint(String...)}）
     * @param action 仅在首次执行时订阅的动作
     */
    @SuppressWarnings("unchecked")
    public <T> Mono<T> execute(String key, String bodyFingerprint, Mono<T> action) {
        purgeExpired();
        Entry created = new Entry(bodyFingerprint, action.cache(),
            clock.instant().plus(TTL));
        Entry entry = entries.computeIfAbsent(key, k -> created);
        if (entry != created && !entry.fingerprint.equals(bodyFingerprint)) {
            return Mono.error(
                new ApiException(ErrorCode.IDEMPOTENCY_CONFLICT, "请求冲突，请刷新后重试"));
        }
        // 失败结果不缓存：瞬时错误（如上游不可用）后用户手动重试应重新执行；
        // 成功结果在 TTL 内重放，保证重复请求最多生成一条评论。
        return (Mono<T>) entry.result
            .doOnError(t -> entries.remove(key, entry));
    }

    /** 计算请求体指纹（SHA-256 hex）。 */
    public static String fingerprint(String... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String part : parts) {
                digest.update(part == null ? new byte[] {0}
                    : part.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0x1f);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 在任何 JDK 都可用
            throw new IllegalStateException(e);
        }
    }

    private void purgeExpired() {
        Instant now = clock.instant();
        entries.entrySet().removeIf(e -> !e.getValue().expiresAt.isAfter(now));
    }

    private static final class Entry {
        private final String fingerprint;
        private final Mono<Object> result;
        private final Instant expiresAt;

        @SuppressWarnings("unchecked")
        Entry(String fingerprint, Mono<?> result, Instant expiresAt) {
            this.fingerprint = fingerprint;
            this.result = (Mono<Object>) result;
            this.expiresAt = expiresAt;
        }
    }
}
