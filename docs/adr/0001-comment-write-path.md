# ADR-0001：评论写入路径 —— 同站点 Public Comment API 代理

- 状态：已接受（M0）
- 日期：2026-08-01

## 背景

小程序评论必须经过登录、频控、幂等与微信 `msgSecCheck` 后才能写入 Halo。
写入侧有三条可选路径：

1. **反射/直接调用 Halo 内部 CommentService** —— 内部 API 非插件稳定契约，
   Halo 小版本升级即可能破坏，计划明确禁止；
2. **插件自定义 Extension + Reconciler** —— 数据与网站评论体系分裂，
   违背「网站与小程序评论互通」的产品约束；
3. **代理同站点 Halo Public Comment API**（`/apis/api.halo.run/v1alpha1/comments`）——
   与网站前端使用同一公开契约，评论/回复进入同一套 Comment/Reply 资源，
   审核队列、删除、置顶等管理能力天然复用。

## 决策

采用方案 3。插件内部定义 `HaloCommentGateway` 接口隔离该依赖：

- 业务层只依赖网关接口，不出现 Halo 内部非公开实现类；
- 网关以 loopback HTTP（`WebClient` 指向插件所在 Halo 实例的 public API，
  由 `ExternalUrlSupplier` 取得站点地址）发起匿名游客评论请求；
- 不内置 PAT、不伪造管理员身份；Halo 侧需开启评论组件并允许游客评论，
  插件启动诊断识别「评论组件未启用/游客评论关闭」但不泄露后台配置；
- Halo 返回的 `approved` 状态由网关映射为契约的 `published`/`pending`，
  客户端不直接解读 Halo 响应；
- 结构化错误（404 文章不存在、409 禁止评论、5xx 不可用）映射为稳定业务码。

## 兼容约束

- 支持范围 `Halo >= 2.23.0`；2.23.x 与 2.25.4 的请求/响应固定为集成测试夹具；
- 若 PoC 证明 loopback HTTP 代理在某部署形态（反向代理、子路径）下不稳定，
  必须回到本 ADR 修订并选择受支持的 Halo API，禁止退化为反射调用。

## 后果

- 优点：契约稳定、评论数据与网站完全互通、无额外持久化；
- 代价：依赖 Halo 游客评论开关；多一次 loopback HTTP 跳转；
- 风险：Public API 的字段在小版本间可能增删 —— 以双版本集成测试夹具兜底，
  网关只做显式字段映射，未知字段不穿透。
