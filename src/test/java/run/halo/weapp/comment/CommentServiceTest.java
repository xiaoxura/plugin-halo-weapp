package run.halo.weapp.comment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import run.halo.app.core.extension.content.Comment;
import run.halo.app.core.extension.content.Post;
import run.halo.app.core.extension.content.Reply;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.Ref;
import run.halo.weapp.config.SettingsService;
import run.halo.weapp.error.ApiException;
import run.halo.weapp.error.ErrorCode;
import run.halo.weapp.security.MaskUtils;
import run.halo.weapp.security.SessionService;
import run.halo.weapp.wechat.Code2SessionResult;
import run.halo.weapp.wechat.SecCheckResult;
import run.halo.weapp.wechat.WeChatClient;

/**
 * CommentService 编排测试：假 WeChatClient + 假 Gateway + mock ExtensionClient。
 * 覆盖开关/校验/文章/频控/检测/写入/幂等/版本全链路。
 */
class CommentServiceTest {

    private SettingsService settings;
    private ReactiveExtensionClient extensionClient;
    private FakeWeChatClient weChatClient;
    private FakeGateway gateway;
    private CommentService commentService;
    private String sessionToken;
    private SessionService sessionService;

    private static final String OPEN_ID = "test-openid-placeholder";
    private static final String IDEM_KEY = "test-idempotency-key";

    @BeforeEach
    void setUp() {
        settings = mock(SettingsService.class);
        extensionClient = mock(ReactiveExtensionClient.class);
        weChatClient = new FakeWeChatClient();
        gateway = new FakeGateway();
        sessionService = new SessionService();
        sessionToken = sessionService.create(OPEN_ID);
        commentService = new CommentService(settings, sessionService, new RateLimitService(),
            new IdempotencyService(), weChatClient, gateway, extensionClient,
            new MaskUtils());
        enableAll();
    }

    private void enableAll() {
        when(settings.comment()).thenReturn(
            new SettingsService.CommentConfig(true, true, true, 500, 100, 1000));
        when(settings.client()).thenReturn(
            new SettingsService.ClientConfig("0.3.0", "https://example.com/p", "v1", 5000L));
    }

    private CommentService.CommentCommand command() {
        return new CommentService.CommentCommand("post-1", "访客昵称", "写得不错", "v1");
    }

    private Mono<CommentService.WriteResult> submit() {
        return commentService.submitComment(sessionToken, IDEM_KEY, null, null, command());
    }

    private static Post post(Post.VisibleEnum visible, boolean publish, boolean deleted,
                             Boolean allowComment) {
        Post post = new Post();
        Post.PostSpec spec = new Post.PostSpec();
        spec.setVisible(visible);
        spec.setPublish(publish);
        spec.setDeleted(deleted);
        spec.setAllowComment(allowComment);
        post.setSpec(spec);
        return post;
    }

    private void givenCommentablePost() {
        when(extensionClient.fetch(Post.class, "post-1")).thenReturn(
            Mono.just(post(Post.VisibleEnum.PUBLIC, true, false, true)));
    }

    private void expectError(ErrorCode code, Throwable t) {
        assertTrue(t instanceof ApiException, "expected ApiException but got " + t);
        assertEquals(code, ((ApiException) t).code());
    }

    // ---------- 1) 开关 fail-closed ----------

    @Test
    void commentDisabledShortCircuitsBeforeAnyExternalCall() {
        when(settings.comment()).thenReturn(
            new SettingsService.CommentConfig(true, false, true, 500, 3, 20));
        StepVerifier.create(submit())
            .expectErrorSatisfies(t -> expectError(ErrorCode.COMMENT_DISABLED, t))
            .verify();
        verifyNoInteractions(extensionClient);
        assertEquals(0, weChatClient.secCheckCalls);
        assertEquals(0, gateway.calls);
    }

    @Test
    void replyDisabledShortCircuits() {
        when(settings.comment()).thenReturn(
            new SettingsService.CommentConfig(true, true, false, 500, 3, 20));
        StepVerifier.create(commentService.submitReply(sessionToken, IDEM_KEY, null, null,
                new CommentService.ReplyCommand("c-1", "访客昵称", "回复", "v1", null)))
            .expectErrorSatisfies(t -> expectError(ErrorCode.REPLY_DISABLED, t))
            .verify();
        assertEquals(0, weChatClient.secCheckCalls);
        assertEquals(0, gateway.calls);
    }

    // ---------- 会话 ----------

    @Test
    void invalidSessionIsRejected() {
        StepVerifier.create(commentService.submitComment("bad-token", IDEM_KEY, null, null,
                command()))
            .expectErrorSatisfies(t -> expectError(ErrorCode.SESSION_EXPIRED, t))
            .verify();
        assertEquals(0, gateway.calls);
    }

    @Test
    void accountSessionReusesSameOpenIdForExistingCommentPipeline() {
        givenCommentablePost();
        String accountToken = sessionService.createAccount(OPEN_ID, "reader-internal");
        StepVerifier.create(commentService.submitComment(accountToken, IDEM_KEY + "-account",
                null, null, command()))
            .assertNext(result -> assertEquals("published", result.status()))
            .verifyComplete();
        assertEquals(OPEN_ID, weChatClient.lastOpenId);
        assertEquals(1, gateway.calls);
    }

    // ---------- 请求体校验 ----------

    @Test
    void invalidNicknameIsRejected() {
        givenCommentablePost();
        for (String name : new String[] {"a", "这个昵称实在是太长太长太长太长太长太长太长啦"}) {
            StepVerifier.create(commentService.submitComment(sessionToken, IDEM_KEY + name,
                    null, null,
                    new CommentService.CommentCommand("post-1", name, "正文", "v1")))
                .expectErrorSatisfies(t -> expectError(ErrorCode.VALIDATION_ERROR, t))
                .verify();
        }
        assertEquals(0, weChatClient.secCheckCalls);
        assertEquals(0, gateway.calls);
    }

    @Test
    void blankContentIsRejected() {
        givenCommentablePost();
        StepVerifier.create(commentService.submitComment(sessionToken, IDEM_KEY, null, null,
                new CommentService.CommentCommand("post-1", "访客昵称", "   ", "v1")))
            .expectErrorSatisfies(t -> expectError(ErrorCode.VALIDATION_ERROR, t))
            .verify();
        assertEquals(0, gateway.calls);
    }

    @Test
    void overlongContentIsRejected() {
        givenCommentablePost();
        when(settings.comment()).thenReturn(
            new SettingsService.CommentConfig(true, true, true, 10, 100, 1000));
        StepVerifier.create(commentService.submitComment(sessionToken, IDEM_KEY, null, null,
                new CommentService.CommentCommand("post-1", "访客昵称",
                    "这段文字远远超过十个字符的限制", "v1")))
            .expectErrorSatisfies(t -> expectError(ErrorCode.VALIDATION_ERROR, t))
            .verify();
        assertEquals(0, gateway.calls);
    }

    @Test
    void mismatchedPrivacyConsentIsRejected() {
        givenCommentablePost();
        StepVerifier.create(commentService.submitComment(sessionToken, IDEM_KEY, null, null,
                new CommentService.CommentCommand("post-1", "访客昵称", "正文", "old-version")))
            .expectErrorSatisfies(t -> expectError(ErrorCode.VALIDATION_ERROR, t))
            .verify();
        assertEquals(0, gateway.calls);
    }

    // ---------- 2) 文章校验 ----------

    @Test
    void missingPostYieldsPostNotFound() {
        when(extensionClient.fetch(Post.class, "post-1")).thenReturn(Mono.empty());
        StepVerifier.create(submit())
            .expectErrorSatisfies(t -> expectError(ErrorCode.POST_NOT_FOUND, t))
            .verify();
        assertEquals(0, weChatClient.secCheckCalls);
        assertEquals(0, gateway.calls);
    }

    @Test
    void privatePostYieldsPostNotFound() {
        when(extensionClient.fetch(Post.class, "post-1")).thenReturn(
            Mono.just(post(Post.VisibleEnum.PRIVATE, true, false, true)));
        StepVerifier.create(submit())
            .expectErrorSatisfies(t -> expectError(ErrorCode.POST_NOT_FOUND, t))
            .verify();
    }

    @Test
    void deletedPostYieldsPostNotFound() {
        when(extensionClient.fetch(Post.class, "post-1")).thenReturn(
            Mono.just(post(Post.VisibleEnum.PUBLIC, true, true, true)));
        StepVerifier.create(submit())
            .expectErrorSatisfies(t -> expectError(ErrorCode.POST_NOT_FOUND, t))
            .verify();
    }

    @Test
    void commentForbiddenPostYieldsCommentNotAllowed() {
        when(extensionClient.fetch(Post.class, "post-1")).thenReturn(
            Mono.just(post(Post.VisibleEnum.PUBLIC, true, false, false)));
        StepVerifier.create(submit())
            .expectErrorSatisfies(t -> expectError(ErrorCode.COMMENT_NOT_ALLOWED, t))
            .verify();
        assertEquals(0, gateway.calls);
    }

    // ---------- 3) 频控 ----------

    @Test
    void rateLimitedBeforeAnyExternalCall() {
        givenCommentablePost();
        when(settings.comment()).thenReturn(
            new SettingsService.CommentConfig(true, true, true, 500, 1, 100));
        // 第一次成功
        StepVerifier.create(commentService.submitComment(sessionToken, IDEM_KEY, null, null,
                command()))
            .expectNextCount(1).verifyComplete();
        // 第二次（不同幂等键避免命中缓存）触发分钟窗口
        StepVerifier.create(commentService.submitComment(sessionToken, "another-key-1",
                null, null, command()))
            .expectErrorSatisfies(t -> {
                expectError(ErrorCode.RATE_LIMITED, t);
                assertTrue(((ApiException) t).retryAfter() >= 1);
            })
            .verify();
        assertEquals(1, weChatClient.secCheckCalls);
        assertEquals(1, gateway.calls);
    }

    // ---------- 4) 内容安全 ----------

    @Test
    void reviewSuggestionIsRejectedWithoutGatewayCall() {
        givenCommentablePost();
        weChatClient.suggest = SecCheckResult.Suggest.REVIEW;
        StepVerifier.create(submit())
            .expectErrorSatisfies(t -> expectError(ErrorCode.CONTENT_REVIEW, t))
            .verify();
        assertEquals(1, weChatClient.secCheckCalls);
        assertEquals(0, gateway.calls);
    }

    @Test
    void riskySuggestionIsRejectedWithoutGatewayCall() {
        givenCommentablePost();
        weChatClient.suggest = SecCheckResult.Suggest.RISKY;
        StepVerifier.create(submit())
            .expectErrorSatisfies(t -> expectError(ErrorCode.CONTENT_RISKY, t))
            .verify();
        assertEquals(0, gateway.calls);
    }

    @Test
    void secCheckUnavailableFailsClosed() {
        givenCommentablePost();
        weChatClient.error =
            new ApiException(ErrorCode.WECHAT_UNAVAILABLE, "安全检测服务暂时不可用，请稍后重试");
        StepVerifier.create(submit())
            .expectErrorSatisfies(t -> expectError(ErrorCode.WECHAT_UNAVAILABLE, t))
            .verify();
        assertEquals(0, gateway.calls);
    }

    // ---------- 5/6) 写入与状态映射 ----------

    @Test
    void approvedTrueMapsToPublished() {
        givenCommentablePost();
        gateway.approved = true;
        StepVerifier.create(submit())
            .expectNextMatches(r -> r.approved() && "published".equals(r.status())
                && "halo-comment-1".equals(r.name()))
            .verifyComplete();
        // msgSecCheck content = 昵称 + "\n" + 正文
        assertEquals("访客昵称\n写得不错", weChatClient.lastContent);
        assertEquals(OPEN_ID, weChatClient.lastOpenId);
        // 服务端转义后写入网关
        assertEquals("post-1", gateway.lastPostName);
        assertEquals("访客昵称", gateway.lastDisplayName);
    }

    @Test
    void approvedFalseMapsToPending() {
        givenCommentablePost();
        gateway.approved = false;
        StepVerifier.create(submit())
            .expectNextMatches(r -> !r.approved() && "pending".equals(r.status()))
            .verifyComplete();
    }

    @Test
    void htmlIsEscapedServerSide() {
        givenCommentablePost();
        StepVerifier.create(commentService.submitComment(sessionToken, IDEM_KEY, null, null,
                new CommentService.CommentCommand("post-1", "<b>昵称</b>",
                    "<script>alert(1)</script>&'\"", "v1")))
            .expectNextCount(1).verifyComplete();
        assertEquals("&lt;b&gt;昵称&lt;/b&gt;", gateway.lastDisplayName);
        assertEquals(
            "&lt;script&gt;alert(1)&lt;/script&gt;&amp;&#39;&quot;",
            gateway.lastContent);
    }

    // ---------- 7) 幂等 ----------

    @Test
    void idempotentReplayReturnsSameNameAndGatewayRunsOnce() {
        givenCommentablePost();
        CommentService.WriteResult first = submit().block();
        CommentService.WriteResult second = submit().block();
        assertEquals(first.name(), second.name());
        assertEquals(1, gateway.calls);
    }

    @Test
    void sameIdempotencyKeyWithDifferentBodyConflicts() {
        givenCommentablePost();
        submit().block();
        StepVerifier.create(commentService.submitComment(sessionToken, IDEM_KEY, null, null,
                new CommentService.CommentCommand("post-1", "访客昵称", "另一段正文", "v1")))
            .expectErrorSatisfies(t -> expectError(ErrorCode.IDEMPOTENCY_CONFLICT, t))
            .verify();
        assertEquals(1, gateway.calls);
    }

    // ---------- 客户端版本 ----------

    @Test
    void outdatedClientVersionIsRejected() {
        givenCommentablePost();
        StepVerifier.create(commentService.submitComment(sessionToken, IDEM_KEY, "0.2.9",
                null, command()))
            .expectErrorSatisfies(t -> expectError(ErrorCode.CLIENT_UPDATE_REQUIRED, t))
            .verify();
        assertEquals(0, gateway.calls);
    }

    @Test
    void currentOrInvalidClientVersionIsAccepted() {
        givenCommentablePost();
        StepVerifier.create(commentService.submitComment(sessionToken, IDEM_KEY, "0.3.0",
                null, command()))
            .expectNextCount(1).verifyComplete();
        StepVerifier.create(commentService.submitComment(sessionToken, "k-2", "not-semver",
                null, command()))
            .expectNextCount(1).verifyComplete();
    }

    // ---------- 回复链路 ----------

    @Test
    void replyValidatesParentCommentAndItsPost() {
        Comment parent = comment("c-1", "post-1", false);
        when(extensionClient.fetch(Comment.class, "c-1")).thenReturn(Mono.just(parent));
        givenCommentablePost();
        StepVerifier.create(commentService.submitReply(sessionToken, IDEM_KEY, null, null,
                new CommentService.ReplyCommand("c-1", "访客昵称", "回复内容", "v1", null)))
            .expectNextMatches(r -> "published".equals(r.status()))
            .verifyComplete();
        assertEquals("c-1", gateway.lastCommentName);
    }

    @Test
    void replyToMissingCommentYieldsCommentNotFound() {
        when(extensionClient.fetch(Comment.class, "c-1")).thenReturn(Mono.empty());
        StepVerifier.create(commentService.submitReply(sessionToken, IDEM_KEY, null, null,
                new CommentService.ReplyCommand("c-1", "访客昵称", "回复内容", "v1", null)))
            .expectErrorSatisfies(t -> expectError(ErrorCode.COMMENT_NOT_FOUND, t))
            .verify();
        assertEquals(0, weChatClient.secCheckCalls);
        assertEquals(0, gateway.calls);
    }

    @Test
    void quoteReplyMustBelongToSameCommentAndBeApproved() {
        Comment parent = comment("c-1", "post-1", false);
        when(extensionClient.fetch(Comment.class, "c-1")).thenReturn(Mono.just(parent));
        givenCommentablePost();

        // 引用其他评论下的回复 → 拒绝
        Reply foreign = reply("r-1", "c-other", true, false);
        when(extensionClient.fetch(Reply.class, "r-1")).thenReturn(Mono.just(foreign));
        StepVerifier.create(commentService.submitReply(sessionToken, IDEM_KEY, null, null,
                new CommentService.ReplyCommand("c-1", "访客昵称", "回复内容", "v1", "r-1")))
            .expectErrorSatisfies(t -> expectError(ErrorCode.COMMENT_NOT_FOUND, t))
            .verify();

        // 引用本评论下已公开回复 → 放行
        Reply own = reply("r-2", "c-1", true, false);
        when(extensionClient.fetch(Reply.class, "r-2")).thenReturn(Mono.just(own));
        StepVerifier.create(commentService.submitReply(sessionToken, "k-quote", null, null,
                new CommentService.ReplyCommand("c-1", "访客昵称", "回复内容", "v1", "r-2")))
            .expectNextCount(1).verifyComplete();
        assertEquals("r-2", gateway.lastQuoteReplyName);
    }

    private static Comment comment(String name, String postName, boolean hidden) {
        Ref ref = new Ref();
        ref.setGroup("content.halo.run");
        ref.setVersion("v1alpha1");
        ref.setKind("Post");
        ref.setName(postName);
        Comment comment = new Comment();
        run.halo.app.extension.Metadata metadata = new run.halo.app.extension.Metadata();
        metadata.setName(name);
        comment.setMetadata(metadata);
        Comment.CommentSpec spec = new Comment.CommentSpec();
        spec.setSubjectRef(ref);
        spec.setHidden(hidden);
        comment.setSpec(spec);
        return comment;
    }

    private static Reply reply(String name, String commentName, boolean approved,
                               boolean hidden) {
        Reply reply = new Reply();
        run.halo.app.extension.Metadata metadata = new run.halo.app.extension.Metadata();
        metadata.setName(name);
        reply.setMetadata(metadata);
        Reply.ReplySpec spec = new Reply.ReplySpec();
        spec.setCommentName(commentName);
        spec.setApproved(approved);
        spec.setHidden(hidden);
        reply.setSpec(spec);
        return reply;
    }

    // ---------- 假实现 ----------

    static final class FakeWeChatClient implements WeChatClient {
        SecCheckResult.Suggest suggest = SecCheckResult.Suggest.PASS;
        ApiException error;
        int secCheckCalls;
        String lastContent;
        String lastOpenId;

        @Override
        public Mono<Code2SessionResult> code2Session(String code) {
            return Mono.error(new UnsupportedOperationException());
        }

        @Override
        public Mono<String> getAccessToken() {
            return Mono.error(new UnsupportedOperationException());
        }

        @Override
        public Mono<SecCheckResult> msgSecCheck(String openId, String content) {
            secCheckCalls++;
            lastOpenId = openId;
            lastContent = content;
            if (error != null) {
                return Mono.error(error);
            }
            return Mono.just(new SecCheckResult(suggest, "trace-test"));
        }
    }

    static final class FakeGateway implements HaloCommentGateway {
        boolean approved = true;
        int calls;
        String lastPostName;
        String lastCommentName;
        String lastDisplayName;
        String lastContent;
        String lastQuoteReplyName;

        @Override
        public Mono<GatewayCommentResult> createComment(String postName, String displayName,
                                                        String content) {
            calls++;
            lastPostName = postName;
            lastDisplayName = displayName;
            lastContent = content;
            return Mono.just(new GatewayCommentResult("halo-comment-1", approved));
        }

        @Override
        public Mono<GatewayCommentResult> createReply(String commentName, String displayName,
                                                      String content, String quoteReplyName) {
            calls++;
            lastCommentName = commentName;
            lastDisplayName = displayName;
            lastContent = content;
            lastQuoteReplyName = quoteReplyName;
            return Mono.just(new GatewayCommentResult("halo-reply-1", approved));
        }
    }
}
