package run.halo.weapp.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.weapp.comment.RateLimitService;
import run.halo.weapp.config.SettingsService;
import run.halo.weapp.config.WechatSettings;
import run.halo.weapp.error.ApiException;
import run.halo.weapp.error.ErrorCode;
import run.halo.weapp.identity.ReaderIdentityService;
import run.halo.weapp.identity.WeAppUser;
import run.halo.weapp.security.SessionService;
import run.halo.weapp.wechat.Code2SessionResult;
import run.halo.weapp.wechat.SecCheckResult;
import run.halo.weapp.wechat.WeChatClient;

class AuthServiceTest {

    private static final String OPEN_ID = "openid-sensitive-value";
    private static final String READER =
        "reader-0123456789abcdef0123456789abcdef01234567";
    private static final String PRIVACY = "2026-08-01";

    private SettingsService settings;
    private FakeWeChatClient weChat;
    private ReaderIdentityService identityService;
    private ReactiveExtensionClient extensionClient;
    private SessionService sessions;
    private Map<String, WeAppUser> users;
    private AuthService service;
    private AtomicInteger createAttempts;
    private AtomicInteger deleteCalls;

    @BeforeEach
    void setUp() {
        settings = mock(SettingsService.class);
        when(settings.features()).thenReturn(new SettingsService.FeatureConfig(false, false, true));
        when(settings.wechat()).thenReturn(new WechatSettings("wx-app", "server-secret"));
        when(settings.client()).thenReturn(new SettingsService.ClientConfig(
            "0.4.0", "https://example.com/privacy", PRIVACY, 5000));
        when(settings.comment()).thenReturn(new SettingsService.CommentConfig(
            false, false, false, 500, 100, 1000));

        weChat = new FakeWeChatClient();
        identityService = mock(ReaderIdentityService.class);
        when(identityService.readerName(any())).thenReturn(Mono.just(READER));
        extensionClient = mock(ReactiveExtensionClient.class);
        users = new ConcurrentHashMap<>();
        createAttempts = new AtomicInteger();
        deleteCalls = new AtomicInteger();
        installInMemoryExtensionClient();
        sessions = new SessionService();
        service = new AuthService(settings, weChat, identityService, extensionClient,
            sessions, new RateLimitService());
    }

    @Test
    void featureAndPrivacyAndClientVersionFailClosedBeforeAccountCreation() {
        when(settings.features()).thenReturn(new SettingsService.FeatureConfig(false, false, false));
        expectCode(service.login(login(PRIVACY, "读者昵称"), "0.4.0", null),
            ErrorCode.READER_ACCOUNT_DISABLED);
        assertEquals(0, weChat.code2SessionCalls.get());

        when(settings.features()).thenReturn(new SettingsService.FeatureConfig(false, false, true));
        expectCode(service.login(login("old-version", "读者昵称"), "0.4.0", null),
            ErrorCode.PRIVACY_CONSENT_REQUIRED);
        expectCode(service.login(login(PRIVACY, "读者昵称"), "0.3.9", null),
            ErrorCode.CLIENT_UPDATE_REQUIRED);
        expectCode(service.login(login(PRIVACY, "读者昵称"), null, null),
            ErrorCode.CLIENT_UPDATE_REQUIRED);
        assertTrue(users.isEmpty());
        assertEquals(0, weChat.code2SessionCalls.get());
    }

    @Test
    void firstLoginRequiresExplicitNicknameAndPassSecurityCheck() {
        expectCode(service.login(login(PRIVACY, null), "0.4.0", null),
            ErrorCode.VALIDATION_ERROR);
        assertTrue(users.isEmpty());
        assertEquals(0, weChat.secCheckCalls.get());

        weChat.suggest = SecCheckResult.Suggest.REVIEW;
        expectCode(service.login(login(PRIVACY, "需要修改"), "0.4.0", null),
            ErrorCode.CONTENT_REVIEW);
        assertTrue(users.isEmpty());

        weChat.suggest = SecCheckResult.Suggest.RISKY;
        expectCode(service.login(login(PRIVACY, "风险昵称"), "0.4.0", null),
            ErrorCode.CONTENT_RISKY);
        assertTrue(users.isEmpty());
    }

    @Test
    void firstLoginPersistsOnlyMinimalProfileAndReturnsAccountSession() throws Exception {
        AuthService.AuthResult result = service
            .login(login(PRIVACY, "微信读者"), "0.4.0", "203.0.113.1")
            .block();
        assertNotNull(result);
        assertEquals("微信读者", result.profile().displayName());
        assertEquals(PRIVACY, result.profile().privacyPolicyVersion());
        assertEquals(SessionService.SESSION_TTL_SECONDS, result.expiresIn());
        assertEquals(READER, sessions.validateAccount(result.sessionToken()).readerName());
        assertEquals(1, users.size());
        assertEquals(1, weChat.secCheckCalls.get());
        assertEquals("微信读者", users.get(READER).getSpec().getDisplayName());

        String resourceJson = new ObjectMapper().writeValueAsString(users.get(READER));
        assertFalse(resourceJson.contains(OPEN_ID));
        assertFalse(resourceJson.contains("server-secret"));
        assertFalse(resourceJson.contains("identityKey"));
        assertFalse(result.toString().contains(result.sessionToken()));
        assertFalse(result.toString().contains(OPEN_ID));
    }

    @Test
    void existingReaderRestoresWithoutNicknameCheckAndRecordsCurrentConsent() {
        users.put(READER, user(READER, "原昵称", "old-version"));
        AuthService.AuthResult result = service
            .login(login(PRIVACY, null), "0.4.0", null)
            .block();
        assertEquals("原昵称", result.profile().displayName());
        assertEquals(PRIVACY, users.get(READER).getSpec().getPrivacyPolicyVersion());
        assertEquals(0, weChat.secCheckCalls.get());
        assertTrue(sessions.validateAccount(result.sessionToken()).isAccount());
    }

    @Test
    void deterministicNameRecoversConcurrentCreateConflictAndCreatesOneResource() {
        AtomicInteger initialFetches = new AtomicInteger();
        when(extensionClient.fetch(WeAppUser.class, READER)).thenAnswer(invocation ->
            Mono.defer(() -> {
                if (initialFetches.incrementAndGet() <= 2) return Mono.empty();
                return Mono.justOrEmpty(users.get(READER));
            }));

        Mono<AuthService.AuthResult> first =
            service.login(login(PRIVACY, "并发读者"), "0.4.0", null);
        Mono<AuthService.AuthResult> second =
            service.login(login(PRIVACY, "并发读者"), "0.4.0", null);
        var pair = Mono.zip(first, second).block();

        assertNotNull(pair);
        assertEquals(1, users.size());
        assertEquals(2, createAttempts.get(), "第二次 create 冲突后必须 fetch 胜者");
        assertNotEquals(pair.getT1().sessionToken(), pair.getT2().sessionToken());
        assertEquals(READER, sessions.validateAccount(pair.getT1().sessionToken()).readerName());
        assertEquals(READER, sessions.validateAccount(pair.getT2().sessionToken()).readerName());
    }

    @Test
    void profileUpdateChecksConsentSecurityAndFrequency() {
        users.put(READER, user(READER, "旧昵称", PRIVACY));
        String token = sessions.createAccount(OPEN_ID, READER);
        AuthService.ReaderProfile profile = service.updateProfile(token,
            new AuthService.UpdateCommand("新昵称", PRIVACY), "0.4.0", null).block();
        assertEquals("新昵称", profile.displayName());
        assertEquals("新昵称", users.get(READER).getSpec().getDisplayName());
        assertEquals(1, weChat.secCheckCalls.get());

        expectCode(service.updateProfile(token,
            new AuthService.UpdateCommand("再次修改", "old"), "0.4.0", null),
            ErrorCode.PRIVACY_CONSENT_REQUIRED);

        when(settings.comment()).thenReturn(new SettingsService.CommentConfig(
            false, false, false, 500, 1, 100));
        AuthService isolated = new AuthService(settings, weChat, identityService,
            extensionClient, sessions, new RateLimitService());
        isolated.updateProfile(token,
            new AuthService.UpdateCommand("频控一次", PRIVACY), "0.4.0", null).block();
        expectCode(isolated.updateProfile(token,
            new AuthService.UpdateCommand("频控二次", PRIVACY), "0.4.0", null),
            ErrorCode.RATE_LIMITED);
    }

    @Test
    void profileUpdateDoesNotMaskStorageFailureWhenOnlyConsentAlreadyMatches() {
        WeAppUser fetched = user(READER, "旧昵称", PRIVACY);
        WeAppUser latest = user(READER, "旧昵称", PRIVACY);
        AtomicInteger fetches = new AtomicInteger();
        when(extensionClient.fetch(WeAppUser.class, READER)).thenAnswer(invocation ->
            fetches.getAndIncrement() == 0 ? Mono.just(fetched) : Mono.just(latest));
        when(extensionClient.update(any(WeAppUser.class)))
            .thenReturn(Mono.error(new IllegalStateException("storage failed")));
        String token = sessions.createAccount(OPEN_ID, READER);

        expectCode(service.updateProfile(token,
            new AuthService.UpdateCommand("新昵称", PRIVACY), "0.4.0", null),
            ErrorCode.HALO_UNAVAILABLE);
    }

    @Test
    void invalidPrivacyConfigAndPersistedReaderSpecFailClosed() {
        when(settings.client()).thenReturn(new SettingsService.ClientConfig(
            "0.4.0", "http://example.com/privacy", PRIVACY, 5000));
        expectCode(service.login(login(PRIVACY, "读者昵称"), "0.4.0", null),
            ErrorCode.HALO_UNAVAILABLE);
        assertEquals(0, weChat.code2SessionCalls.get());

        when(settings.client()).thenReturn(new SettingsService.ClientConfig(
            "0.4.0", "https://example.com/privacy", PRIVACY, 5000));
        users.put(READER, user(READER, "读者昵称", " "));
        String token = sessions.createAccount(OPEN_ID, READER);
        expectCode(service.getProfile(token), ErrorCode.HALO_UNAVAILABLE);
    }

    @Test
    void remoteDisableBlocksLoginAndProfileMutationButNotReadLogoutOrDeletion() {
        users.put(READER, user(READER, "读者昵称", PRIVACY));
        String token = sessions.createAccount(OPEN_ID, READER);
        when(settings.features()).thenReturn(new SettingsService.FeatureConfig(false, false, false));

        assertEquals("读者昵称", service.getProfile(token).block().displayName());
        expectCode(service.updateProfile(token,
            new AuthService.UpdateCommand("新昵称", PRIVACY), "0.4.0", null),
            ErrorCode.READER_ACCOUNT_DISABLED);
        service.logout(token).block();
        expectCode(Mono.fromCallable(() -> sessions.validate(token)),
            ErrorCode.SESSION_EXPIRED);
    }

    @Test
    void logoutOnlyRevokesCurrentTokenAndIsIdempotent() {
        users.put(READER, user(READER, "读者昵称", PRIVACY));
        String first = sessions.createAccount(OPEN_ID, READER);
        String second = sessions.createAccount(OPEN_ID, READER);
        service.logout(first).block();
        service.logout(first).block();
        expectCode(Mono.fromCallable(() -> sessions.validate(first)),
            ErrorCode.SESSION_EXPIRED);
        assertEquals(OPEN_ID, sessions.validate(second));
    }

    @Test
    void deleteRemovesReaderAndRevokesAllDeviceSessions() {
        users.put(READER, user(READER, "读者昵称", PRIVACY));
        String first = sessions.createAccount(OPEN_ID, READER);
        String second = sessions.createAccount(OPEN_ID, READER);
        service.deleteAccount(first).block();
        assertTrue(users.isEmpty());
        expectCode(Mono.fromCallable(() -> sessions.validate(first)), ErrorCode.SESSION_EXPIRED);
        expectCode(Mono.fromCallable(() -> sessions.validate(second)), ErrorCode.SESSION_EXPIRED);
        // 注销只删除一个 WeAppUser；服务没有任何 Comment/Reply 删除依赖。
        assertEquals(1, deleteCalls.get());
    }

    @Test
    void missingReaderRevokesOrphanedSessionsAndReturnsStableCode() {
        String token = sessions.createAccount(OPEN_ID, READER);
        expectCode(service.getProfile(token), ErrorCode.READER_NOT_FOUND);
        expectCode(Mono.fromCallable(() -> sessions.validate(token)), ErrorCode.SESSION_EXPIRED);
    }

    private void installInMemoryExtensionClient() {
        when(extensionClient.fetch(WeAppUser.class, READER)).thenAnswer(invocation ->
            Mono.defer(() -> Mono.justOrEmpty(users.get(READER))));
        when(extensionClient.create(any(WeAppUser.class))).thenAnswer(invocation ->
            Mono.defer(() -> {
                createAttempts.incrementAndGet();
                WeAppUser candidate = invocation.getArgument(0);
                WeAppUser existing = users.putIfAbsent(candidate.getMetadata().getName(), candidate);
                return existing == null ? Mono.just(candidate)
                    : Mono.error(new IllegalStateException("conflict"));
            }));
        when(extensionClient.update(any(WeAppUser.class))).thenAnswer(invocation ->
            Mono.defer(() -> {
                WeAppUser value = invocation.getArgument(0);
                users.put(value.getMetadata().getName(), value);
                return Mono.just(value);
            }));
        when(extensionClient.delete(any(WeAppUser.class))).thenAnswer(invocation ->
            Mono.defer(() -> {
                deleteCalls.incrementAndGet();
                WeAppUser value = invocation.getArgument(0);
                users.remove(value.getMetadata().getName());
                return Mono.just(value);
            }));
    }

    private static AuthService.LoginCommand login(String privacy, String displayName) {
        return new AuthService.LoginCommand("wx-code", privacy, displayName);
    }

    private static WeAppUser user(String name, String displayName, String privacy) {
        WeAppUser user = new WeAppUser();
        run.halo.app.extension.Metadata metadata = new run.halo.app.extension.Metadata();
        metadata.setName(name);
        user.setMetadata(metadata);
        user.setSpec(new WeAppUser.WeAppUserSpec(displayName, privacy));
        return user;
    }

    private static void expectCode(Mono<?> mono, ErrorCode code) {
        StepVerifier.create(mono)
            .expectErrorSatisfies(error -> {
                assertTrue(error instanceof ApiException,
                    "expected ApiException but got " + error.getClass());
                assertEquals(code, ((ApiException) error).code());
            })
            .verify();
    }

    private static final class FakeWeChatClient implements WeChatClient {

        private final AtomicInteger code2SessionCalls = new AtomicInteger();
        private final AtomicInteger secCheckCalls = new AtomicInteger();
        private SecCheckResult.Suggest suggest = SecCheckResult.Suggest.PASS;

        @Override
        public Mono<Code2SessionResult> code2Session(String code) {
            code2SessionCalls.incrementAndGet();
            return Mono.just(new Code2SessionResult(OPEN_ID));
        }

        @Override
        public Mono<String> getAccessToken() {
            return Mono.just("not-used");
        }

        @Override
        public Mono<SecCheckResult> msgSecCheck(String openId, String content) {
            secCheckCalls.incrementAndGet();
            return Mono.just(new SecCheckResult(suggest, "trace"));
        }
    }
}
