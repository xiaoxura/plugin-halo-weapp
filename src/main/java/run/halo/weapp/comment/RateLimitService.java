package run.halo.weapp.comment;

import java.time.Clock;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;
import run.halo.weapp.config.SettingsService;
import run.halo.weapp.error.ApiException;
import run.halo.weapp.error.ErrorCode;

/**
 * 内存固定窗口频控。
 *
 * <p>按用户（OpenID 经 HMAC 后的标识做 key，不落 OpenID 原文）每分钟/每小时两个窗口；
 * 另有按来源 IP 的宽松窗口（每分钟 30 次，仅作兜底）。IP 取 ServerRequest 远端地址，
 * 不信任 X-Forwarded-For（v0.1.x 未配置受信代理）。窗口条目懒清理。</p>
 *
 * <p>必须在任何微信 / Halo 外部调用之前执行。</p>
 */
@Component
public class RateLimitService {

    /** IP 兜底窗口：每分钟 30 次。 */
    static final int IP_LIMIT_PER_MINUTE = 30;

    private static final long MINUTE_SECONDS = 60L;
    private static final long HOUR_SECONDS = 3600L;
    private static final int PURGE_THRESHOLD = 512;

    private final Map<String, Counter> counters = new ConcurrentHashMap<>();
    private final Clock clock;

    public RateLimitService() {
        this(Clock.systemUTC());
    }

    /** 测试可见：注入可控时钟。 */
    RateLimitService(Clock clock) {
        this.clock = clock;
    }

    /** 用户级频控：每分钟 / 每小时两个固定窗口。 */
    public void checkUser(String userTag, SettingsService.CommentConfig config) {
        check("u:m:" + userTag, MINUTE_SECONDS, config.rateLimitPerMinute());
        check("u:h:" + userTag, HOUR_SECONDS, config.rateLimitPerHour());
    }

    /** IP 兜底频控：每分钟 30 次；IP 不可知时跳过。 */
    public void checkIp(String ip) {
        if (ip == null || ip.isBlank()) {
            return;
        }
        check("i:m:" + ip, MINUTE_SECONDS, IP_LIMIT_PER_MINUTE);
    }

    private void check(String key, long windowSeconds, int limit) {
        purgeLazily();
        long now = clock.instant().getEpochSecond();
        long windowId = now / windowSeconds;
        Counter counter = counters.computeIfAbsent(key, k -> new Counter(windowSeconds));
        synchronized (counter) {
            if (counter.windowId != windowId) {
                counter.windowId = windowId;
                counter.count.set(0);
            }
            if (counter.count.get() >= limit) {
                long retryAfter = (windowId + 1) * windowSeconds - now;
                throw new ApiException(ErrorCode.RATE_LIMITED, "操作过于频繁，请稍后再试",
                    (int) Math.max(1, retryAfter));
            }
            counter.count.incrementAndGet();
        }
    }

    /** 懒清理：条目数超阈值时扫描移除过期窗口。 */
    private void purgeLazily() {
        if (counters.size() < PURGE_THRESHOLD) {
            return;
        }
        long now = clock.instant().getEpochSecond();
        Iterator<Map.Entry<String, Counter>> it = counters.entrySet().iterator();
        while (it.hasNext()) {
            Counter c = it.next().getValue();
            if (now / c.windowSeconds > c.windowId) {
                it.remove();
            }
        }
    }

    private static final class Counter {
        private final long windowSeconds;
        private final AtomicInteger count = new AtomicInteger();
        private long windowId = -1;

        Counter(long windowSeconds) {
            this.windowSeconds = windowSeconds;
        }
    }
}
