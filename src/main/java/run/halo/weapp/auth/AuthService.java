package run.halo.weapp.auth;

import java.net.URI;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.weapp.comment.RateLimitService;
import run.halo.weapp.config.SettingsService;
import run.halo.weapp.error.ApiException;
import run.halo.weapp.error.ErrorCode;
import run.halo.weapp.identity.ReaderIdentityService;
import run.halo.weapp.identity.WeAppUser;
import run.halo.weapp.security.SemverPolicy;
import run.halo.weapp.security.SessionService;
import run.halo.weapp.wechat.SecCheckResult;
import run.halo.weapp.wechat.WeChatClient;

/** 微信读者登录、恢复、资料、退出与注销编排。 */
@Service
public class AuthService {

    private final SettingsService settings;
    private final WeChatClient weChatClient;
    private final ReaderIdentityService identityService;
    private final ReactiveExtensionClient extensionClient;
    private final SessionService sessionService;
    private final RateLimitService rateLimitService;

    public AuthService(SettingsService settings, WeChatClient weChatClient,
                       ReaderIdentityService identityService,
                       ReactiveExtensionClient extensionClient,
                       SessionService sessionService, RateLimitService rateLimitService) {
        this.settings = settings;
        this.weChatClient = weChatClient;
        this.identityService = identityService;
        this.extensionClient = extensionClient;
        this.sessionService = sessionService;
        this.rateLimitService = rateLimitService;
    }

    public Mono<AuthResult> login(LoginCommand command, String clientVersion, String clientIp) {
        return Mono.defer(() -> {
            ReaderConfig config = requireReaderWrite(command == null
                ? null : command.privacyConsentVersion(), clientVersion);
            if (command == null || command.code() == null || command.code().isBlank()
                || command.code().length() > 128) {
                return Mono.error(validation("code 需为 1～128 个字符"));
            }
            if (command.displayName() != null && !command.displayName().isBlank()) {
                ApiException invalidName = validateDisplayName(command.displayName());
                if (invalidName != null) return Mono.error(invalidName);
            }
            // IP 兜底频控发生在 code2Session 外部调用之前。
            rateLimitService.checkIp(clientIp);
            return weChatClient.code2Session(command.code())
                .flatMap(result -> identityService.readerName(result.openId())
                    .flatMap(readerName -> restoreOrCreate(readerName, result.openId(),
                        command.displayName(), config)));
        });
    }

    public Mono<ReaderProfile> getProfile(String sessionToken) {
        return Mono.defer(() -> {
            SessionService.SessionPrincipal principal =
                sessionService.validateAccount(sessionToken);
            return fetchReader(principal.readerName());
        });
    }

    public Mono<ReaderProfile> updateProfile(String sessionToken, UpdateCommand command,
                                             String clientVersion, String clientIp) {
        return Mono.defer(() -> {
            ReaderConfig config = requireReaderWrite(command == null
                ? null : command.privacyConsentVersion(), clientVersion);
            if (command == null) {
                return Mono.error(validation("请求体不能为空"));
            }
            ApiException invalidName = validateDisplayName(command.displayName());
            if (invalidName != null) return Mono.error(invalidName);
            String displayName = command.displayName().trim();
            SessionService.SessionPrincipal principal =
                sessionService.validateAccount(sessionToken);
            SettingsService.CommentConfig limits = settings.comment();
            rateLimitService.checkUser("reader-profile:" + principal.readerName(), limits);
            rateLimitService.checkIp(clientIp);
            return extensionClient.fetch(WeAppUser.class, principal.readerName())
                .switchIfEmpty(readerMissing(principal.readerName()))
                .flatMap(user -> {
                    WeAppUser.WeAppUserSpec spec = validSpec(user);
                    if (displayName.equals(spec.getDisplayName())) {
                        spec.setPrivacyPolicyVersion(config.privacyVersion());
                        return persistUpdated(user, config.privacyVersion());
                    }
                    return checkDisplayName(principal.openId(), displayName)
                        .then(Mono.defer(() -> {
                            spec.setDisplayName(displayName);
                            spec.setPrivacyPolicyVersion(config.privacyVersion());
                            return persistUpdated(user, config.privacyVersion());
                        }));
                });
        });
    }

    /** 退出幂等：只要 header 非空，未知/已撤销 token 同样成功。 */
    public Mono<Void> logout(String sessionToken) {
        return Mono.defer(() -> {
            if (sessionToken == null || sessionToken.isBlank()) {
                return Mono.error(new ApiException(ErrorCode.SESSION_REQUIRED, "请先登录"));
            }
            sessionService.revoke(sessionToken);
            return Mono.empty();
        });
    }

    public Mono<Void> deleteAccount(String sessionToken) {
        return Mono.defer(() -> {
            SessionService.SessionPrincipal principal =
                sessionService.validateAccount(sessionToken);
            String readerName = principal.readerName();
            return extensionClient.fetch(WeAppUser.class, readerName)
                .switchIfEmpty(Mono.defer(() -> {
                    sessionService.revokeAllByReaderName(readerName);
                    return Mono.error(new ApiException(ErrorCode.READER_NOT_FOUND,
                        "微信读者账号不存在"));
                }))
                .flatMap(extensionClient::delete)
                .onErrorMap(t -> !(t instanceof ApiException), t -> storageUnavailable())
                .then(Mono.fromRunnable(() -> sessionService.revokeAllByReaderName(readerName)));
        });
    }

    private Mono<AuthResult> restoreOrCreate(String readerName, String openId,
                                             String requestedDisplayName,
                                             ReaderConfig config) {
        return extensionClient.fetch(WeAppUser.class, readerName)
            .flatMap(user -> refreshConsent(user, config.privacyVersion()))
            .switchIfEmpty(Mono.defer(() -> createReader(readerName, openId,
                requestedDisplayName, config.privacyVersion())))
            .map(user -> {
                ReaderProfile profile = profileOf(user);
                String token = sessionService.createAccount(openId, readerName);
                return new AuthResult(token, SessionService.SESSION_TTL_SECONDS, profile);
            })
            .onErrorMap(t -> !(t instanceof ApiException), t -> storageUnavailable());
    }

    private Mono<WeAppUser> createReader(String readerName, String openId,
                                         String requestedDisplayName, String privacyVersion) {
        ApiException invalidName = validateDisplayName(requestedDisplayName);
        if (invalidName != null) {
            return Mono.error(new ApiException(ErrorCode.VALIDATION_ERROR,
                "首次登录需要填写 2～20 个字符的昵称"));
        }
        String displayName = requestedDisplayName.trim();
        rateLimitService.checkUser("reader-login:" + readerName, settings.comment());
        return checkDisplayName(openId, displayName)
            .then(Mono.defer(() -> {
                WeAppUser user = new WeAppUser();
                Metadata metadata = new Metadata();
                metadata.setName(readerName);
                user.setMetadata(metadata);
                user.setSpec(new WeAppUser.WeAppUserSpec(displayName, privacyVersion));
                return extensionClient.create(user)
                    // 确定性名称保证并发幂等：create 冲突后读取实际胜者。
                    .onErrorResume(createError -> extensionClient
                        .fetch(WeAppUser.class, readerName)
                        .switchIfEmpty(Mono.error(storageUnavailable())));
            }));
    }

    private Mono<WeAppUser> refreshConsent(WeAppUser user, String privacyVersion) {
        WeAppUser.WeAppUserSpec spec = validSpec(user);
        if (privacyVersion.equals(spec.getPrivacyPolicyVersion())) {
            return Mono.just(user);
        }
        spec.setPrivacyPolicyVersion(privacyVersion);
        return persistUpdatedUser(user, spec.getDisplayName(), privacyVersion);
    }

    private Mono<ReaderProfile> persistUpdated(WeAppUser user, String privacyVersion) {
        String displayName = validSpec(user).getDisplayName();
        return persistUpdatedUser(user, displayName, privacyVersion)
            .map(AuthService::profileOf);
    }

    private Mono<WeAppUser> persistUpdatedUser(WeAppUser user, String displayName,
                                               String privacyVersion) {
        String readerName = user.getMetadata().getName();
        return extensionClient.update(user)
            .onErrorResume(updateError -> extensionClient.fetch(WeAppUser.class, readerName)
                .flatMap(latest -> {
                    WeAppUser.WeAppUserSpec latestSpec = validSpec(latest);
                    return displayName.equals(latestSpec.getDisplayName())
                        && privacyVersion.equals(latestSpec.getPrivacyPolicyVersion())
                        ? Mono.just(latest) : Mono.error(storageUnavailable());
                })
                .switchIfEmpty(Mono.error(storageUnavailable())));
    }

    private Mono<ReaderProfile> fetchReader(String readerName) {
        return extensionClient.fetch(WeAppUser.class, readerName)
            .switchIfEmpty(readerMissing(readerName))
            .map(AuthService::profileOf)
            .onErrorMap(t -> !(t instanceof ApiException), t -> storageUnavailable());
    }

    private <T> Mono<T> readerMissing(String readerName) {
        return Mono.defer(() -> {
            sessionService.revokeAllByReaderName(readerName);
            return Mono.error(new ApiException(ErrorCode.READER_NOT_FOUND,
                "微信读者账号不存在"));
        });
    }

    private Mono<Void> checkDisplayName(String openId, String displayName) {
        return weChatClient.msgSecCheck(openId, displayName)
            .flatMap(result -> {
                if (result == null || result.suggest() == null) {
                    return Mono.error(new ApiException(ErrorCode.WECHAT_UNAVAILABLE,
                        "安全检测服务暂时不可用，请稍后重试"));
                }
                return switch (result.suggest()) {
                    case PASS -> Mono.empty();
                    case REVIEW -> Mono.error(new ApiException(ErrorCode.CONTENT_REVIEW,
                        "昵称需要修改后才能使用"));
                    case RISKY -> Mono.error(new ApiException(ErrorCode.CONTENT_RISKY,
                        "昵称存在风险，无法使用"));
                };
            });
    }

    private ReaderConfig requireReaderWrite(String privacyConsentVersion,
                                            String clientVersion) {
        if (!settings.features().readerAccountEnabled()) {
            throw new ApiException(ErrorCode.READER_ACCOUNT_DISABLED,
                "微信读者登录暂未开放");
        }
        if (!settings.wechat().isConfigured()) {
            throw storageUnavailable();
        }
        SettingsService.ClientConfig client = settings.client();
        if (!validPrivacyConfig(client)) {
            throw storageUnavailable();
        }
        if (privacyConsentVersion == null
            || !privacyConsentVersion.equals(client.privacyPolicyVersion())) {
            throw new ApiException(ErrorCode.PRIVACY_CONSENT_REQUIRED,
                "请阅读并同意最新隐私政策");
        }
        Integer versionCompare = SemverPolicy.compare(clientVersion, client.minVersion());
        if (versionCompare == null || versionCompare < 0) {
            throw new ApiException(ErrorCode.CLIENT_UPDATE_REQUIRED,
                "请更新小程序后再登录");
        }
        return new ReaderConfig(client.privacyPolicyVersion());
    }

    private static boolean validPrivacyConfig(SettingsService.ClientConfig client) {
        if (client.privacyPolicyVersion() == null || client.privacyPolicyVersion().isBlank()
            || client.privacyPolicyVersion().length() > 100) {
            return false;
        }
        try {
            URI uri = URI.create(client.privacyPolicyUrl());
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null;
        } catch (Exception e) {
            return false;
        }
    }

    static ApiException validateDisplayName(String value) {
        String displayName = value == null ? "" : value.trim();
        int length = displayName.codePointCount(0, displayName.length());
        if (length < 2 || length > 20 || displayName.codePoints()
            .anyMatch(codePoint -> Character.isISOControl(codePoint))) {
            return validation("昵称需为 2～20 个字符");
        }
        return null;
    }

    private static WeAppUser.WeAppUserSpec validSpec(WeAppUser user) {
        if (user == null || user.getMetadata() == null
            || user.getMetadata().getName() == null || user.getSpec() == null
            || validateDisplayName(user.getSpec().getDisplayName()) != null
            || user.getSpec().getPrivacyPolicyVersion() == null
            || user.getSpec().getPrivacyPolicyVersion().isBlank()
            || user.getSpec().getPrivacyPolicyVersion().length() > 100) {
            throw storageUnavailable();
        }
        return user.getSpec();
    }

    private static ReaderProfile profileOf(WeAppUser user) {
        WeAppUser.WeAppUserSpec spec = validSpec(user);
        return new ReaderProfile(spec.getDisplayName(), spec.getPrivacyPolicyVersion());
    }

    private static ApiException validation(String message) {
        return new ApiException(ErrorCode.VALIDATION_ERROR, message);
    }

    private static ApiException storageUnavailable() {
        return new ApiException(ErrorCode.HALO_UNAVAILABLE,
            "身份服务暂时不可用，请稍后重试");
    }

    private record ReaderConfig(String privacyVersion) {
    }

    public record LoginCommand(String code, String privacyConsentVersion,
                               String displayName) {
    }

    public record UpdateCommand(String displayName, String privacyConsentVersion) {
    }

    public record ReaderProfile(String displayName, String privacyPolicyVersion) {
    }

    public record AuthResult(String sessionToken, long expiresIn, ReaderProfile profile) {

        /** 防止调试日志通过 record 默认 toString 输出 token。 */
        @Override
        public String toString() {
            return "AuthResult{expiresIn=" + expiresIn + ", profile=" + profile + '}';
        }
    }
}
