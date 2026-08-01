package run.halo.weapp.comment;

import reactor.core.publisher.Mono;

/**
 * Halo Public Comment API 写入网关（ADR-0001）：业务层只依赖本接口，
 * 不出现 Halo 内部非公开实现类，测试可注入假实现。
 */
public interface HaloCommentGateway {

    /** 发表文章评论（displayName/content 已由服务端转义）。 */
    Mono<GatewayCommentResult> createComment(String postName, String displayName,
                                             String content);

    /** 回复指定评论（quoteReplyName 可为 null）。 */
    Mono<GatewayCommentResult> createReply(String commentName, String displayName,
                                           String content, String quoteReplyName);
}
