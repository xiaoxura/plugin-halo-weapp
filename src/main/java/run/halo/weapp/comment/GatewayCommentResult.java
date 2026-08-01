package run.halo.weapp.comment;

/**
 * Halo 评论写入网关结果。
 *
 * @param haloName Halo 评论/回复的 metadata.name
 * @param approved Halo 返回的 approved 状态（true=published，false=pending）
 */
public record GatewayCommentResult(String haloName, boolean approved) {
}
