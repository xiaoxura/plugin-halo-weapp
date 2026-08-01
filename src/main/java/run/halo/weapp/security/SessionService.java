package run.halo.weapp.security;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import run.halo.weapp.error.ApiException;
import run.halo.weapp.error.ErrorCode;

/**
 * 内存短会话服务：POST /session 签发不透明 token，评论链路校验会话。
 *
 * <p>token = SecureRandom 32 字节 hex（64 字符，256 bit 熵）；有效期 90 分钟（5400 秒）；
 * 仅存内存（ConcurrentHashMap），插件重启即全部失效；会话只保存 OpenID 与过期时间，
 * 绝不保存 session_key（code2Session 拿到后立即丢弃）。</p>
 */
@Component
public class SessionService {

    public static final long SESSION_TTL_SECONDS = 5400L;

    private static final int TOKEN_BYTES = 32;

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final SecureRandom secureRandom = new SecureRandom();
    private final Clock clock;
    private final Duration ttl;

    public SessionService() {
        this(Clock.systemUTC(), Duration.ofSeconds(SESSION_TTL_SECONDS));
    }

    /** 测试可见：注入可控时钟与 TTL。 */
    SessionService(Clock clock, Duration ttl) {
        this.clock = clock;
        this.ttl = ttl;
    }

    /**
     * 为指定 OpenID 签发新会话，返回不透明 token。
     */
    public String create(String openId) {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        String token = HexFormat.of().formatHex(bytes);
        sessions.put(token, new Session(openId, clock.instant().plus(ttl)));
        return token;
    }

    /**
     * 校验会话并返回 OpenID。
     *
     * @throws ApiException SESSION_REQUIRED（无 token）/ SESSION_EXPIRED（过期或不存在）
     */
    public String validate(String token) {
        if (token == null || token.isBlank()) {
            throw new ApiException(ErrorCode.SESSION_REQUIRED, "请先登录");
        }
        Session session = sessions.get(token);
        if (session == null) {
            throw new ApiException(ErrorCode.SESSION_EXPIRED, "登录已过期，请重新登录");
        }
        if (!session.expiresAt().isAfter(clock.instant())) {
            sessions.remove(token);
            throw new ApiException(ErrorCode.SESSION_EXPIRED, "登录已过期，请重新登录");
        }
        return session.openId();
    }

    private record Session(String openId, Instant expiresAt) {
    }
}
