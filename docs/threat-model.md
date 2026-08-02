# 威胁模型（plugin-halo-weapp v0.1.1 / HaloWeApp v0.3.0）

> 状态：v0.1.1 维护基线。v0.1.0 不满足 Halo 生产资源发现、构造器注入与匿名
> config 授权要求，不得作为安装或回滚版本。新增能力前先更新本文档。
> 范围：小程序 → 插件 → 微信 API / Halo Public API 的评论写入链路，以及远程配置下发链路。

## 1. 资产

| 资产 | 位置 | 保护要求 |
| --- | --- | --- |
| 小程序 AppSecret | 插件 Setting（密码字段） | 不进入 Git、jar 资源、公开配置、日志、测试夹具 |
| 微信 access token | 插件内存缓存 | 不返回客户端，不写日志 |
| 微信 session_key | 插件内存会话 | 不持久化、不返回客户端、不写日志 |
| 用户 OpenID | 插件内存会话（90 分钟） | 不写入 Halo Comment、不输出日志；日志使用带 salt 的 HMAC |
| 评论内容 | Halo Comment/Reply | 仅通过微信 `msgSecCheck`（pass）后写入 |
| 配置开关（评论/回复提交） | Halo ConfigMap | 写能力 fail-closed，实时校验，不依赖客户端缓存 |

## 2. 信任边界

1. **小程序 ↔ 插件**：小程序不可信。客户端传入的昵称、正文、幂等键、会话
   token、隐私版本都必须在服务端重新校验；客户端 HTML 一律按纯文本处理。
2. **插件 ↔ 微信 API**：AppSecret、access token 不出服务端；微信返回的
   OpenID/session_key 不出服务端内存会话。
3. **插件 ↔ Halo**：插件以同站点 Public Comment API 写入，不持有 PAT，
   不伪造管理员身份，不反射调用内部 Service。
4. **配置下发**：公开 config 是显式白名单 DTO，不直接序列化 Setting 对象。

## 3. 威胁与缓解

| # | 威胁 | 后果 | 缓解措施 |
| --- | --- | --- | --- |
| T-01 | AppSecret 泄露（日志/异常/HTTP trace/诊断接口） | 攻击者冒充小程序调用微信接口 | 密码字段存储；日志脱敏组件统一过滤；异常消息不携带凭据；发布前凭据扫描 |
| T-02 | 恶意刷评论接口 | 消耗微信内容安全日额度、Halo 垃圾评论 | OpenID 每分钟/每小时频控 + 宽松 IP 频控，频控发生在微信调用**之前**；请求体大小上限 |
| T-03 | 重复提交/重放 | 同一评论写入多条 | 客户端单飞锁 + 服务端幂等键（用户+路由+key，10 分钟）+ 结果缓存；同 key 不同体返回冲突 |
| T-04 | 违规内容写入 | 合规风险、小程序被处罚 | `msgSecCheck` scene=2 version=2；仅 pass 写入；review/risky/超时/未知值一律失败关闭 |
| T-05 | 客户端伪造 subjectRef/审核状态/HTML | 向任意资源写入、注入 XSS | 服务端固定构造 Post subjectRef；客户端字段白名单；服务端对昵称/正文 HTML 转义生成 raw/content |
| T-06 | 会话 token 被窃取重放 | 冒名发表（90 分钟窗口内） | token ≥256 bit 随机熵、不透明、仅内存、90 分钟过期；与 OpenID 绑定；不持久化 |
| T-07 | 过期缓存/旧客户端误开写入口 | 提审期间暴露 UGC、旧客户端调用不兼容接口 | 写能力必须本次冷启动实时探测+拉取成功；缓存强制覆盖 submit/reply=false；minVersion SemVer 过低时服务端返回 426 |
| T-08 | 会话固定/枚举 OpenID | 针对特定用户冒名 | code 一次性；code2Session 失败统一 WECHAT_UNAVAILABLE，不泄露微信原始错误细节 |
| T-09 | 日志泄露敏感信息 | OpenID/凭据外泄 | 日志只记 requestId、trace_id、suggest、label、耗时、HMAC(OpenID+salt)；大小上限 |
| T-10 | 插件配置被后台误改 | 写能力被意外开放 | 后台开关默认关闭；提交时服务端再次实时校验开关 |
| T-11 | 受信任代理头伪造来源 IP | 绕过 IP 频控 | 仅配置受信代理时解析 X-Forwarded-For，未配置不盲信客户端 header |
| T-12 | Halo 内部 API 变动 | 插件在新版本失效 | 只依赖 Public API；2.23.x/2.25.4 双版本集成测试夹具；网关隔离（ADR-0001） |

## 4. 明确的非目标

- 插件只保证**小程序写入链路**经过检测，不替代博客 Web 端自身的反滥用措施；
- 不防御 Halo 管理员账号被盗后的恶意配置（属 Halo 自身安全边界）；
- 不实现用户身份持久化、评论删除/举报（v0.4.0+ 另行建模）。

## 5. 发布前检查

- [ ] 依赖漏洞扫描、凭据扫描（AppSecret/access token/session_key/OpenID 模式）通过；
- [ ] 日志关键字扫描：敏感词命中数为 0；
- [ ] 单元测试覆盖 review/risky/超时/未知 suggest 全部失败关闭路径；
- [ ] 频控触发时无微信/Halo 外部调用（mock 断言）。
