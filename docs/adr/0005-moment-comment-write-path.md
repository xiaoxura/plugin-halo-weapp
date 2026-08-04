# ADR-0005: Moment 评论使用固定主体路由

- 状态：Accepted for v0.4.1
- 日期：2026-08-03
- 关联：`docs/openapi.yaml`、`CommentService`、`PluginMoments >= 1.15.0`

## Context

文章评论网关原本只允许 `content.halo.run/Post`。Moment 是可选插件，配套插件不能编译依赖
Moment 的 Java 类型，也不能让小程序上传任意 group/kind/version 来选择 Halo 资源主体。

## Decision

Moment 评论使用独立路由：

```text
POST /apis/api.weapp.halo.run/v1alpha1/moments/{momentName}/comments
POST /apis/api.weapp.halo.run/v1alpha1/comments/{commentName}/replies
```

服务端从路径生成唯一允许的 `moment.halo.run/Moment/v1alpha1` subjectRef，并通过本机
Moment Public API 读取 JSON，只有 `spec.visible=PUBLIC`、`spec.approved=true` 且未删除时才继续
执行频控、`msgSecCheck`、转义、幂等和 Halo 写入。回复先读取父评论 subjectRef，只允许
`content.halo.run/Post` 或 `moment.halo.run/Moment`，其他主体统一拒绝。

关闭 `features.moments.commentEnabled` 只影响 Moment 评论/回复，不关闭文章评论或公开 Moment
读取。Moment 不可用、私有、未审核和已删除均按稳定的 404 业务码处理；上游超时或 5xx 映射为
`HALO_UNAVAILABLE`。

## Consequences

- 公开写入仍走 Halo Public API，不需要 PAT 或 Halo UC 身份。
- Moment 插件缺失时文章链路继续可用，Moment 评论安全失败。
- 该能力属于 v0.4.1 P1；生产默认关闭，需独立完成真机、审核和目标环境演练后再开启。
