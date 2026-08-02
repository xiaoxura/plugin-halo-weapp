# 威胁模型（plugin-halo-weapp v0.2.0 / HaloWeApp v0.4.0）

> 状态：M1 已接受。实现、OpenAPI 与 ADR-0003/0004 必须保持一致。
> 范围：小程序 → 配套插件 / Moment 插件 → 微信 API / Halo API 的公开读取、评论写入、
> 微信读者身份与远程配置链路。

## 1. 资产

| 资产 | 位置 | 保护要求 |
| --- | --- | --- |
| 小程序 AppSecret | 插件 Setting（密码字段） | 不进入 Git、jar 资源、公开配置、日志、测试夹具 |
| 微信 access token | 插件内存缓存 | 不返回客户端，不写日志 |
| 微信 session_key | code2Session 响应解析期间 | 立即丢弃、不进入会话、不持久化、不返回客户端、不写日志 |
| 用户 OpenID | 插件内存会话（90 分钟） | 不写入 WeAppUser/Halo Comment、不输出日志；诊断仅使用不可逆短标签 |
| identityKey | 独立内部 ConfigMap `plugin-halo-weapp-identity` | 256 bit；与 Setting ConfigMap 隔离；不进入表单、公开 DTO、日志、jar 或测试快照；加密备份 |
| 微信读者昵称 | WeAppUser | 用户主动提供；2～20 字；创建/修改均通过 msgSecCheck；可注销 |
| 读者身份摘要前缀 | WeAppUser metadata.name | 不返回客户端、不聚合到 anonymous；随账号注销删除 |
| 账号 session token | 双端内存（90 分钟） | 不写客户端 storage/URL/日志；退出立即撤销，注销撤销全部 |
| 评论内容 | Halo Comment/Reply | 仅通过微信 `msgSecCheck`（pass）后写入 |
| 配置开关（评论/身份/瞬间） | Halo ConfigMap | 写入和登录 fail-closed；新开关默认 false；缓存只允许只读展示 |

## 2. 信任边界

1. **小程序 ↔ 插件**：小程序不可信。客户端传入的昵称、正文、幂等键、会话
   token、隐私版本都必须在服务端重新校验；客户端 HTML 一律按纯文本处理。
2. **插件 ↔ 微信 API**：AppSecret、access token 不出服务端；微信返回的
   OpenID/session_key 不出服务端内存会话。
3. **插件 ↔ Halo**：插件以同站点 Public Comment API 写入，不持有 PAT，
   不伪造管理员身份，不反射调用内部 Service。
4. **配置下发**：公开 config 是显式白名单 DTO，不直接序列化 Setting 对象。
5. **插件 ↔ WeAppUser 扩展存储**：资源 scheme 不聚合 anonymous；资源不保存原始 OpenID，
   identityKey 只通过内部 ConfigMap 服务访问。
6. **小程序 ↔ Moment Public API**：只读取匿名 API；固定 PluginMoments 名称和路径；不携带
   PAT/Console Cookie/UC 身份，不把 Public API 响应当作可信 HTML 直接执行。

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
| T-13 | 原始 OpenID/完整摘要落盘或进入响应 | 可关联用户身份、违反最小化原则 | HMAC 派生确定性内部名称；WeAppUser 只保存昵称与隐私版本；DTO/错误/日志白名单与敏感词测试 |
| T-14 | identityKey 泄露、丢失或被设置重置覆盖/静默轮换 | 可离线关联内部身份，或所有账号无法恢复 | 32 字节随机 key；独立内部 ConfigMap；加密备份和恢复演练；已有 WeAppUser 时 ConfigMap 缺失/空值直接失败关闭，不自动生成；禁止日志/表单/公开 DTO；轮换必须迁移 |
| T-15 | 并发首次登录创建重复账号 | 资料分叉、注销不完整 | HMAC 确定性资源名；create 冲突后 fetch；并发自动化测试 |
| T-16 | 缓存配置创建账号或恢复 token | 远程关闭后仍收集个人信息 | canLogin 必须实时配置成功、版本满足、隐私契约完整；缓存资料不等于认证态 |
| T-17 | 隐私版本变化后静默恢复/改资料 | 缺少最新同意 | 请求必须回传当前版本；客户端暂停恢复；服务端返回 PRIVACY_CONSENT_REQUIRED |
| T-18 | 退出/注销后 token 仍可使用 | 越权访问已撤销账号 | 当前 token 精确撤销；账号会话携带 readerName；注销按 readerName 撤销全部并删除资源 |
| T-19 | 可配置插件名/路径探测内网或任意 API | 客户端被导向非预期资源 | PluginMoments 名称与 available/Public API 路径编译期固定；页面不拼 endpoint |
| T-20 | Moment HTML/未知媒体执行脚本或危险 URL | XSS、协议滥用、页面崩溃 | adapter 白名单；既有 HTML sanitizer；只接受 HTTPS；未知媒体不可执行占位；媒体生命周期释放 |
| T-21 | 陈旧 Moment 索引制造死链 | 用户进入不存在/停用内容 | 当前冷启动 available 探测；功能关闭时过滤 Moment 命中；详情 404/不可用独立状态 |
| T-22 | 回滚到不可启动旧 jar，或旧 Setting 协调删除新 ConfigMap 组 | 紧急回滚扩大故障、配置丢失且无法重新灰度 | 禁用 v0.1.0；v0.1.1 修复资源布局/构造器/RBAC并携带不生效的前向 feature 组；双 Halo 往返验证；回滚前完整备份并逐阶段核对资源哈希 |

## 4. 明确的非目标

- 插件只保证**小程序写入链路**经过检测，不替代博客 Web 端自身的反滥用措施；
- 不防御 Halo 管理员账号被盗后的恶意配置（属 Halo 自身安全边界）；
- 微信读者不是 Halo User，不支持私有内容、Console/UC、管理员登录或作者发布；
- 不持久化 token、OpenID、微信头像、手机号、邮箱、网站或位置；
- 注销不自动删除已公开 Halo 评论；评论删除/举报与数据主体请求另行设计；
- v0.4.0 不实现 Moment 评论写入，预留开关不得被解释为已支持。

## 5. 发布前检查

- [ ] 依赖漏洞扫描、凭据扫描（AppSecret/access token/session_key/OpenID 模式）通过；
- [ ] 日志关键字扫描：敏感词命中数为 0；
- [ ] 单元测试覆盖 review/risky/超时/未知 suggest 全部失败关闭路径；
- [ ] 频控触发时无微信/Halo 外部调用（mock 断言）。
- [ ] 同一 OpenID 并发首次登录只创建一个 WeAppUser，资源/错误/日志无原始 OpenID；
- [ ] identity ConfigMap 损坏，或已有 WeAppUser 时 ConfigMap 缺失/为空，均失败关闭且不生成 key；
- [ ] 客户端 storage 扫描无 token、OpenID、identityKey、摘要或内部 readerName；
- [ ] 退出后当前 token、注销后全部 token 立即失效，资源删除后二次查询失败；
- [ ] config 公开响应不含 identityKey，所有新 feature 默认 false，缓存不能开启登录/写入；
- [ ] Moment HTML、PHOTO/VIDEO/AUDIO/POST/未知媒体与相对 URL 降级测试通过。
- [ ] v0.1.1 正式产物/tag 哈希一致；目标环境执行 v0.2.0 → v0.1.1 → v0.2.0 后
  ConfigMap、identityKey/WeAppUser、Setting、旧客户端与 Moment 均保持可用。
