package run.halo.weapp.comment;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
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
import run.halo.weapp.wechat.SecCheckResult;
import run.halo.weapp.wechat.WeChatClient;

/**
 * 评论/回复写入编排。处理顺序固定（契约，见 docs/openapi.yaml）：
 *
 * <ol>
 *   <li>实时总开关 → 会话 → 请求体（昵称/正文/隐私协议版本）→ 客户端版本；</li>
 *   <li>文章存在、公开、未删除、已发布、允许评论；回复则校验父评论属于的文章同样公开可评论；</li>
 *   <li>幂等检查 → 频控（频控在幂等之后、任何外部调用之前）；</li>
 *   <li>msgSecCheck（content = 昵称 + "\n" + 正文），仅 PASS 放行；</li>
 *   <li>服务端对昵称与正文做 HTML 转义；</li>
 *   <li>HaloCommentGateway 写入；approved=true → published，否则 pending；</li>
 *   <li>幂等结果缓存。</li>
 * </ol>
 */
@Service
public class CommentService {

    private static final Pattern SEMVER =
        Pattern.compile("^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)"
            + "(-[0-9A-Za-z.-]+)?(\\+[0-9A-Za-z.-]+)?$");

    private final SettingsService settings;
    private final SessionService sessionService;
    private final RateLimitService rateLimitService;
    private final IdempotencyService idempotencyService;
    private final WeChatClient weChatClient;
    private final HaloCommentGateway haloCommentGateway;
    private final ReactiveExtensionClient extensionClient;
    private final MaskUtils maskUtils;

    public CommentService(SettingsService settings, SessionService sessionService,
                          RateLimitService rateLimitService,
                          IdempotencyService idempotencyService,
                          WeChatClient weChatClient,
                          HaloCommentGateway haloCommentGateway,
                          ReactiveExtensionClient extensionClient,
                          MaskUtils maskUtils) {
        this.settings = settings;
        this.sessionService = sessionService;
        this.rateLimitService = rateLimitService;
        this.idempotencyService = idempotencyService;
        this.weChatClient = weChatClient;
        this.haloCommentGateway = haloCommentGateway;
        this.extensionClient = extensionClient;
        this.maskUtils = maskUtils;
    }

    /** 发表评论。 */
    public Mono<WriteResult> submitComment(String sessionToken, String idempotencyKey,
                                           String clientVersion, String clientIp,
                                           CommentCommand command) {
        // 1) 实时总开关（fail-closed：设置异常时 SettingsService 已回落为关闭）
        SettingsService.CommentConfig commentConfig = settings.comment();
        if (!commentConfig.submitEnabled()) {
            return Mono.error(
                new ApiException(ErrorCode.COMMENT_DISABLED, "评论功能已关闭"));
        }
        // 会话
        final String openId;
        try {
            openId = sessionService.validate(sessionToken);
        } catch (ApiException e) {
            return Mono.error(e);
        }
        // 请求体 + 隐私协议版本 + 客户端版本
        SettingsService.ClientConfig clientConfig = settings.client();
        ApiException validationError = validateBody(command.displayName(), command.content(),
            command.privacyConsentVersion(), commentConfig.maxLength(), clientConfig);
        if (validationError == null) {
            validationError = validatePostName(command.postName());
        }
        if (validationError == null) {
            validationError = checkClientVersion(clientVersion, clientConfig.minVersion());
        }
        if (validationError != null) {
            return Mono.error(validationError);
        }
        // 2) 文章校验
        return fetchCommentablePost(command.postName())
            .then(Mono.defer(() -> {
                // 3) 幂等 + 频控（频控在幂等之后、外部调用之前）
                String route = "POST /comments";
                String userTag = maskUtils.userTag(openId);
                String key = userTag + '|' + route + '|' + idempotencyKey;
                String fingerprint = IdempotencyService.fingerprint(route, command.postName(),
                    command.displayName(), command.content(), command.privacyConsentVersion());
                return idempotencyService.execute(key, fingerprint,
                    Mono.defer(() -> checkedWrite(openId, clientIp, commentConfig,
                        command.displayName().trim(), command.content(),
                        escaped -> haloCommentGateway.createComment(command.postName(),
                            escaped.displayName(), escaped.content()))));
            }));
    }

    /** 回复评论。 */
    public Mono<WriteResult> submitReply(String sessionToken, String idempotencyKey,
                                         String clientVersion, String clientIp,
                                         ReplyCommand command) {
        SettingsService.CommentConfig commentConfig = settings.comment();
        if (!commentConfig.replyEnabled()) {
            return Mono.error(new ApiException(ErrorCode.REPLY_DISABLED, "回复功能已关闭"));
        }
        final String openId;
        try {
            openId = sessionService.validate(sessionToken);
        } catch (ApiException e) {
            return Mono.error(e);
        }
        SettingsService.ClientConfig clientConfig = settings.client();
        ApiException validationError = validateBody(command.displayName(), command.content(),
            command.privacyConsentVersion(), commentConfig.maxLength(), clientConfig);
        if (validationError == null) {
            validationError = checkClientVersion(clientVersion, clientConfig.minVersion());
        }
        if (validationError != null) {
            return Mono.error(validationError);
        }
        // 2) 父评论存在且属于公开可评论文章
        return fetchParentComment(command.commentName())
            .then(validateQuoteReply(command.commentName(), command.quoteReplyName()))
            .then(Mono.defer(() -> {
                String route = "POST /comments/" + command.commentName() + "/replies";
                String userTag = maskUtils.userTag(openId);
                String key = userTag + '|' + route + '|' + idempotencyKey;
                String fingerprint = IdempotencyService.fingerprint(route,
                    command.commentName(), command.displayName(), command.content(),
                    command.privacyConsentVersion(),
                    command.quoteReplyName() == null ? "" : command.quoteReplyName());
                return idempotencyService.execute(key, fingerprint,
                    Mono.defer(() -> checkedWrite(openId, clientIp, commentConfig,
                        command.displayName().trim(), command.content(),
                        escaped -> haloCommentGateway.createReply(command.commentName(),
                            escaped.displayName(), escaped.content(),
                            command.quoteReplyName()))));
            }));
    }

    /**
     * 频控 → msgSecCheck → 转义 → 网关写入。只有 msgSecCheck 明确 PASS 才调用 Halo。
     */
    private Mono<WriteResult> checkedWrite(String openId, String clientIp,
                                           SettingsService.CommentConfig commentConfig,
                                           String displayName, String content,
                                           java.util.function.Function<EscapedText,
                                               Mono<GatewayCommentResult>> writer) {
        rateLimitService.checkIp(clientIp);
        rateLimitService.checkUser(maskUtils.userTag(openId), commentConfig);
        return weChatClient.msgSecCheck(openId, displayName + '\n' + content)
            .flatMap(secCheck -> {
                if (secCheck.suggest() == SecCheckResult.Suggest.REVIEW) {
                    return Mono.error(
                        new ApiException(ErrorCode.CONTENT_REVIEW, "内容需要修改后才能发布"));
                }
                if (secCheck.suggest() == SecCheckResult.Suggest.RISKY) {
                    return Mono.error(
                        new ApiException(ErrorCode.CONTENT_RISKY, "内容存在风险，无法发布"));
                }
                // PASS：服务端重新转义后写入（客户端传入的任何 HTML 一律不信任）
                EscapedText escaped =
                    new EscapedText(escapeHtml(displayName), escapeHtml(content));
                return writer.apply(escaped)
                    .map(result -> new WriteResult(result.haloName(), result.approved()));
            });
    }

    /** 校验文章存在、公开、未删除、已发布且允许评论。 */
    private Mono<Post> fetchCommentablePost(String postName) {
        return extensionClient.fetch(Post.class, postName)
            .onErrorResume(t -> Mono.empty())
            .switchIfEmpty(Mono.error(
                new ApiException(ErrorCode.POST_NOT_FOUND, "内容不存在或已删除")))
            .flatMap(post -> {
                Post.PostSpec spec = post.getSpec();
                if (spec == null || Boolean.TRUE.equals(spec.getDeleted())
                    || !Boolean.TRUE.equals(spec.getPublish()) || !Post.isPublic(spec)) {
                    return Mono.error(
                        new ApiException(ErrorCode.POST_NOT_FOUND, "内容不存在或已删除"));
                }
                if (Boolean.FALSE.equals(spec.getAllowComment())) {
                    return Mono.error(
                        new ApiException(ErrorCode.COMMENT_NOT_ALLOWED, "该内容暂不支持评论"));
                }
                return Mono.just(post);
            });
    }

    /** 校验父评论存在（未隐藏/删除），且其所属文章公开可评论。 */
    private Mono<Void> fetchParentComment(String commentName) {
        return extensionClient.fetch(Comment.class, commentName)
            .onErrorResume(t -> Mono.empty())
            .switchIfEmpty(Mono.error(
                new ApiException(ErrorCode.COMMENT_NOT_FOUND, "评论不存在或已删除")))
            .flatMap(comment -> {
                Comment.CommentSpec spec = comment.getSpec();
                if (spec == null || Boolean.TRUE.equals(spec.getHidden())) {
                    return Mono.error(
                        new ApiException(ErrorCode.COMMENT_NOT_FOUND, "评论不存在或已删除"));
                }
                Ref subjectRef = spec.getSubjectRef();
                if (subjectRef == null || !"Post".equals(subjectRef.getKind())
                    || subjectRef.getName() == null) {
                    return Mono.error(
                        new ApiException(ErrorCode.COMMENT_NOT_ALLOWED, "该内容暂不支持评论"));
                }
                return fetchCommentablePost(subjectRef.getName()).then();
            });
    }

    /**
     * 校验 quoteReplyName 只能引用当前评论下已公开的回复：回复必须存在、
     * 属于该评论（spec.commentName 相等）、approved=true 且未隐藏；
     * 否则一律按 COMMENT_NOT_FOUND 拒绝（不向客户端泄露回复是否存在）。
     */
    private Mono<Void> validateQuoteReply(String commentName, String quoteReplyName) {
        if (quoteReplyName == null || quoteReplyName.isBlank()) {
            return Mono.empty();
        }
        return extensionClient.fetch(Reply.class, quoteReplyName)
            .onErrorResume(t -> Mono.empty())
            .switchIfEmpty(Mono.error(
                new ApiException(ErrorCode.COMMENT_NOT_FOUND, "评论不存在或已删除")))
            .flatMap(reply -> {
                Reply.ReplySpec spec = reply.getSpec();
                boolean valid = spec != null
                    && commentName.equals(spec.getCommentName())
                    && Boolean.TRUE.equals(spec.getApproved())
                    && !Boolean.TRUE.equals(spec.getHidden());
                if (!valid) {
                    return Mono.error(
                        new ApiException(ErrorCode.COMMENT_NOT_FOUND, "评论不存在或已删除"));
                }
                return Mono.empty();
            });
    }

    private static ApiException validateBody(String displayName, String content,
                                             String privacyConsentVersion, int maxLength,
                                             SettingsService.ClientConfig clientConfig) {
        String name = displayName == null ? "" : displayName.trim();
        int nameLength = name.codePointCount(0, name.length());
        if (nameLength < 2 || nameLength > 20) {
            return new ApiException(ErrorCode.VALIDATION_ERROR, "昵称需为 2～20 个字符");
        }
        if (content == null || content.isBlank()) {
            return new ApiException(ErrorCode.VALIDATION_ERROR, "评论内容不能为空");
        }
        if (content.codePointCount(0, content.length()) > maxLength) {
            return new ApiException(ErrorCode.VALIDATION_ERROR,
                "评论内容不能超过 " + maxLength + " 个字符");
        }
        if (privacyConsentVersion == null
            || !privacyConsentVersion.equals(clientConfig.privacyPolicyVersion())) {
            return new ApiException(ErrorCode.VALIDATION_ERROR, "请先阅读并同意隐私政策");
        }
        return null;
    }

    private static ApiException validatePostName(String postName) {
        if (postName == null || postName.isBlank() || postName.length() > 128) {
            return new ApiException(ErrorCode.VALIDATION_ERROR, "文章标识不合法");
        }
        return null;
    }

    /** 客户端版本低于 minVersion 时拒绝写入；缺失或非法版本号忽略该检查。 */
    private static ApiException checkClientVersion(String clientVersion, String minVersion) {
        int[] client = parseSemver(clientVersion);
        int[] minimum = parseSemver(minVersion);
        if (client == null || minimum == null) {
            return null;
        }
        for (int i = 0; i < 3; i++) {
            if (client[i] != minimum[i]) {
                return client[i] < minimum[i]
                    ? new ApiException(ErrorCode.CLIENT_UPDATE_REQUIRED, "请更新小程序后再评论")
                    : null;
            }
        }
        return null;
    }

    private static int[] parseSemver(String version) {
        if (version == null) {
            return null;
        }
        Matcher matcher = SEMVER.matcher(version.trim());
        if (!matcher.matches()) {
            return null;
        }
        try {
            return new int[] {Integer.parseInt(matcher.group(1)),
                Integer.parseInt(matcher.group(2)), Integer.parseInt(matcher.group(3))};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** HTML 转义（& < > " ' 五个），客户端传入的 HTML 一律按纯文本处理。 */
    static String escapeHtml(String text) {
        return text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;");
    }

    private record EscapedText(String displayName, String content) {
    }

    /** 评论命令（对应 CommentCreateRequest）。 */
    public record CommentCommand(String postName, String displayName, String content,
                                 String privacyConsentVersion) {
    }

    /** 回复命令（对应 ReplyCreateRequest）。 */
    public record ReplyCommand(String commentName, String displayName, String content,
                               String privacyConsentVersion, String quoteReplyName) {
    }

    /** 写入结果：approved=true → published，否则 pending。 */
    public record WriteResult(String name, boolean approved) {

        public String status() {
            return approved ? "published" : "pending";
        }
    }
}
