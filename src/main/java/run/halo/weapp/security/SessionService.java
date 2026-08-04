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
 * 仅存内存（ConcurrentHashMap），插件重启即全部失效。临时评论会话只携带 OpenID；
 * 账号会话额外携带内部 readerName；绝不保存 session_key。</p>
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
        return createSession(new SessionPrincipal(openId, null));
    }

    /** 为持久微信读者签发账号会话。 */
    public String createAccount(String openId, String readerName) {
        if (readerName == null || readerName.isBlank()) {
            throw new IllegalArgumentException("readerName is required");
        }
        return createSession(new SessionPrincipal(openId, readerName));
    }

    private String createSession(SessionPrincipal principal) {
        purgeExpiredSessions();
        String token;
        do {
            byte[] bytes = new byte[TOKEN_BYTES];
            secureRandom.nextBytes(bytes);
            token = HexFormat.of().formatHex(bytes);
        } while (sessions.putIfAbsent(token,
            new Session(principal, clock.instant().plus(ttl))) != null);
        return token;
    }

    /** 新登录会触发一次全量惰性清理，避免过期会话只在同一 token 被访问时才释放。 */
    private void purgeExpiredSessions() {
        Instant now = clock.instant();
        for (Map.Entry<String, Session> entry : sessions.entrySet()) {
            Session session = entry.getValue();
            if (!session.expiresAt().isAfter(now)) {
                sessions.remove(entry.getKey(), session);
            }
        }
    }

    /**
     * 校验会话并返回 OpenID。
     *
     * @throws ApiException SESSION_REQUIRED（无 token）/ SESSION_EXPIRED（过期或不存在）
     */
    public String validate(String token) {
        return validatePrincipal(token).openId();
    }

    /** 校验任意临时/账号会话。 */
    public SessionPrincipal validatePrincipal(String token) {
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
        return session.principal();
    }

    /** 校验账号会话；临时评论 token 不能访问资料 API。 */
    public SessionPrincipal validateAccount(String token) {
        SessionPrincipal principal = validatePrincipal(token);
        if (!principal.isAccount()) {
            throw new ApiException(ErrorCode.SESSION_REQUIRED, "请先登录微信读者");
        }
        return principal;
    }

    /** 精确撤销当前 token；未知 token 也可幂等处理。 */
    public boolean revoke(String token) {
        return token != null && !token.isBlank() && sessions.remove(token) != null;
    }

    /** 注销账号时撤销该 readerName 的全部设备会话。 */
    public int revokeAllByReaderName(String readerName) {
        if (readerName == null || readerName.isBlank()) {
            return 0;
        }
        int removed = 0;
        for (Map.Entry<String, Session> entry : sessions.entrySet()) {
            Session session = entry.getValue();
            if (readerName.equals(session.principal().readerName())
                && sessions.remove(entry.getKey(), session)) {
                removed++;
            }
        }
        return removed;
    }

    /** 插件停止时显式清空全部内存会话。 */
    public void clear() {
        sessions.clear();
    }

    private record Session(SessionPrincipal principal, Instant expiresAt) {
    }

    /**
     * 服务端内部主体。toString 刻意不输出 OpenID 或 readerName，避免调试日志泄露。
     */
    public static final class SessionPrincipal {

        private final String openId;
        private final String readerName;

        private SessionPrincipal(String openId, String readerName) {
            if (openId == null || openId.isBlank()) {
                throw new IllegalArgumentException("openId is required");
            }
            this.openId = openId;
            this.readerName = readerName;
        }

        public String openId() {
            return openId;
        }

        public String readerName() {
            return readerName;
        }

        public boolean isAccount() {
            return readerName != null && !readerName.isBlank();
        }

        @Override
        public String toString() {
            return "SessionPrincipal{account=" + isAccount() + '}';
        }
    }
}
