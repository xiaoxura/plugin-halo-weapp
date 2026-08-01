# ADR-0002：会话模型与 Schema 版本策略

- 状态：已接受（M0）
- 日期：2026-08-01

## 背景

`msgSecCheck` 要求 OpenID 对应用户在近两小时内访问过小程序，因此微信登录态
必须以短会话形式维护；同时客户端与插件会独立发布，需要明确的兼容策略。

## 决策

### 会话

- `POST /session` 用一次性 `wx.login` code 换取**随机不透明 token**
  （≥256 bit 熵，`SecureRandom` 生成，hex 编码）；
- 会话仅保存于插件内存（Caffeine/ConcurrentHashMap + TTL 90 分钟），
  不持久化；插件重启即全部失效，客户端收到 `SESSION_EXPIRED` 后重新登录；
- 会话内只保存 OpenID 与签发/过期时间，**不保存 session_key**
  （v0.3.0 无加解密需求，持有可能性只会扩大泄露面）；
- token 通过 `X-WeApp-Session` 请求头传递，不进入 URL 与日志；
- 客户端 401 后重新 `wx.login` 并**最多自动重试一次**，禁止无限重试。

### Schema 版本

- 路径保持 `v1alpha1`，业务结构用 `schemaVersion`（整数，当前为 1）演进；
- `schemaVersion: 1` 内只允许**增加可选字段**；删除、改名、改类型时提升版本；
- 客户端遇到高于自身支持范围的 `schemaVersion` 时保持只读，不尝试评论写入；
- 所有错误使用稳定业务码（见 `docs/openapi.yaml` ErrorResponse.code 枚举），
  客户端只根据 `code` 分支，`message` 仅用于展示。

## 后果

- 90 分钟窗口与 `msgSecCheck` 两小时要求对齐，且天然限制 token 泄露后的滥用时长；
- 内存会话意味着插件重启后用户需重新登录 —— 可接受（提交动作才需要会话）；
- 双端发布节奏解耦：插件先上线（开关关闭），小程序后发布，互不阻塞。
