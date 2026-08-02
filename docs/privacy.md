# HaloWeApp v0.4.0 隐私实施指南

> 本文描述代码的实际数据流，供站点运营者编写自己的隐私政策和微信小程序隐私保护指引。
> 它不是法律意见，也不能直接替代运营主体、联系方式、处理依据、保留期限和用户权利渠道等
> 必填内容。开启微信读者或评论前，运营者必须完成法务/合规核对并使用自己的 HTTPS 政策 URL。

## 1. 身份边界

“微信读者”只用于 HaloWeApp 的昵称资料和文章评论会话：

- 不是 Halo Console / UC 用户，不拥有 Halo 角色或管理权限；
- 不用于查看 PRIVATE Moment、发布/编辑内容或访问管理 API；
- v0.4.0 不请求或保存微信头像、手机号、邮箱、网站、位置、通讯录、相册或设备广告标识；
- 默认头像是昵称首字符生成的文字头像，不上传图片；
- 未登录用户仍可只读公开内容，并可在运营者开启评论时使用不创建账号的临时会话。

## 2. 数据清单

### 2.1 小程序本机

| storage key/内存 | 内容 | 目的 | 清理时机 |
| --- | --- | --- | --- |
| auth-session 内存 | 随机 session token、expiresAt | 当前进程认证；请求 auth/评论 API | 到期、二次 401、退出、注销或进程结束 |
| `readerKeepLogin` | 布尔登录意愿 | 冷启动时决定是否尝试恢复 | 退出、注销或用户清除小程序数据 |
| `readerProfile` | `displayName`、`privacyPolicyVersion` | 恢复期间展示缓存资料 | 退出、注销或不再保持登录 |
| `privacyConsentVersion` | 用户已同意版本 | 评论与账号流程共享的版本门禁 | 注销账号时清理；普通退出保留既有评论同意 |
| `commentNickname` | 用户可选保存的评论昵称 | 匿名评论表单预填 | 用户取消保存/清除小程序数据 |
| 搜索/点赞/公告状态 | 搜索词、公开内容本地点赞 key、已关闭公告版本 | 本机体验 | LRU 淘汰或用户清除小程序数据 |

客户端 storage **不得**包含 session token、wx.login code、OpenID、AppID、identityKey、身份摘要、
内部 readerName、AppSecret、access token、session_key、Halo PAT 或评论正文。

### 2.2 配套插件服务端

| 数据 | 位置/保留 | 目的 |
| --- | --- | --- |
| AppID / AppSecret | Halo Setting ConfigMap；AppSecret 为密码字段 | 服务端调用微信 code2Session / 内容安全 API |
| wx.login code | 单次请求内存，使用后丢弃 | 换取 OpenID/session_key |
| OpenID | 请求和 90 分钟内存会话 | 频控、临时评论会话或 HMAC 派生；不落盘 |
| session_key | code2Session 解析期间立即丢弃 | v0.4.0 不使用，不进入会话 |
| access token | 插件内存缓存 | 调用微信内容安全 API |
| identityKey | 独立内部 `plugin-halo-weapp-identity` ConfigMap | HMAC 派生确定性读者资源名；加密备份 |
| `WeAppUser.spec` | Halo 扩展资源 | 仅昵称与隐私版本，保存到用户注销或运营者依法清理 |
| 内部资源名 | `reader-` + HMAC 摘要 160-bit 前缀 | 幂等定位读者；不聚合 anonymous、不返回客户端 |
| 账号/临时 token | 插件进程内存，默认 5400 秒 | auth 和评论请求认证；插件重启失效 |
| 评论昵称/正文 | Halo Comment/Reply | 用户明确提交后公开展示或进入审核；按站点评论政策保留 |
| 安全/频控状态 | 插件短期内存 | 限流、幂等和内容安全，随 TTL/重启清理 |

原始 OpenID、session_key、token、identityKey 和完整摘要不得进入 WeAppUser、Halo Comment、公开
config、auth profile、错误响应、应用日志或测试夹具。

## 3. 处理时机与用户操作

### 3.1 公开阅读

文章和 Moment 使用匿名 Halo Public API。不开启微信读者/评论时不会调用 `wx.login`，也不会
创建 WeAppUser。Moment 仅显示匿名 API 返回的公开、已审核内容。

### 3.2 首次登录

仅当用户在 Profile 页面主动点击登录，并且：

1. 页面展示当前 HTTPS 隐私政策 URL 与版本；
2. 用户明确勾选同意；
3. 用户输入 2～20 个 Unicode 字符昵称；
4. `readerAccount` 本次冷启动实时配置为开启；

客户端才调用 `wx.login` 和 `/auth/login`。首次昵称须通过微信 `msgSecCheck`；review、risky、
超时和未知结果均不创建账号。

### 3.3 冷启动恢复

只有用户先前选择保持登录、隐私版本未变化、实时开关仍开启时才在文章首屏之外调用
`wx.login`。恢复请求不携带昵称，因此账号不存在时不能静默新建。缓存 profile 只用于提示，
不能代表已认证。

### 3.4 评论

用户点击提交后才申请/复用短会话。昵称和正文经过长度校验、频控、幂等及微信内容安全检测，
仅 `pass` 写入 Halo。评论可能公开展示或进入审核；运营者必须说明公开范围、审核、保留和
投诉/删除渠道。

## 4. 退出、注销与数据主体请求

### 退出登录

- 服务端撤销当前账号 token；
- 客户端即使网络失败也清理内存 token、缓存 profile 和保持登录意愿；
- WeAppUser 与已公开评论仍保留；共用的评论隐私同意版本不会因普通退出自动撤销。

### 注销微信读者账号

- 需要二次确认，确认文案必须明确“已有公开评论不会自动删除”；
- 删除 WeAppUser（昵称、隐私版本、可关联内部摘要）并撤销该 readerName 的全部内存会话；
- 客户端清理全部 auth storage；服务端返回账号已不存在时也按本地注销完成收口；
- Halo Comment/Reply 是已经提交到公开内容系统的独立记录，不随账号资源自动级联删除。

### 公开评论处置

运营者必须提供可联系的数据主体请求渠道，并在验证请求人身份后按适用法律和站点政策处理
更正、删除、投诉或导出请求。当前 v0.4.0 没有“我的评论”或客户端评论删除 API，不能在政策中
承诺即时自助删除；也不能因为实现未自动删除就拒绝依法应处理的请求。

## 5. 隐私版本变化

- 插件 Setting 的 `privacyPolicyVersion` 是账号和评论同意的冻结标识；URL 必须为 HTTPS；
- 版本变化后，客户端停止静默恢复，并在重新同意前禁止登录和资料修改；
- 运营者应在政策发生实质变化时更新版本，而不是只替换页面内容却沿用旧版本；
- 旧版客户端若不满足 `minVersion`，服务端应拒绝登录/写入但继续允许公开阅读。

## 6. 第三方与跨边界处理

- **微信**：code2Session 和 `security.msgSecCheck`；运营者需遵守微信平台规则并申报相应处理；
- **Halo/站点托管方**：存储 Setting、WeAppUser 和公开评论；备份、日志和管理员权限由运营者管理；
- **对象存储/CDN**：公开文章/Moment 的图片、视频、音频、附件和字体；运营者应在政策中说明
  实际供应商，并配置微信合法域名；
- 本项目代码不出售个人信息，不接广告 SDK，不向小程序分发 Halo 管理员 PAT。

若实际部署增加统计 SDK、反向代理日志、第三方 CDN、客服系统或其他处理，运营者必须把新增
数据流加入自己的政策；本指南不能覆盖仓库外扩展。

## 7. 安全与保留

- Setting、identityKey、WeAppUser 与评论备份应加密、最小授权、记录保留期和销毁；
- identityKey 与 WeAppUser 必须同一时点备份/恢复。key 丢失时关闭 readerAccount 并恢复备份，
  不得静默生成新 key；
- 日志不得记录请求 auth header/body、OpenID、AppSecret、access token、session_key、token、
  identityKey 或 readerName；诊断只使用允许字段和不可逆短标签；
- AppSecret 泄露时立即在微信公众平台重置；identityKey 泄露不能直接轮换，需关闭登录并执行
  显式迁移；
- 具体备份与事故步骤见 [deployment.md](deployment.md)。

## 8. 微信隐私保护指引核对表

微信后台的分类名称可能随平台更新，运营者应按实际界面和法务意见选择，不要机械复制本文：

- [ ] 运营主体、联系方式、政策 URL/版本与插件 Setting 一致；
- [ ] 说明用户主动填写的昵称及其账号/评论用途；
- [ ] 说明 `wx.login` 产生的标识仅在服务端用于短会话与不可逆身份映射；
- [ ] 说明评论昵称/正文会执行内容安全检测并可能公开或待审核；
- [ ] 说明保持登录所存本机字段及关闭/清理方式；
- [ ] 说明注销删除读者账号，但已有公开评论不自动删除，并提供评论处置渠道；
- [ ] 声明不采集头像、手机号、邮箱、位置等仓库实现未使用的字段；
- [ ] 列出实际 Halo 托管、微信接口及 CDN/对象存储供应商；
- [ ] 在 iOS/Android 真机验证首次登录前可见同意、拒绝后不创建账号、版本变化后重新同意；
- [ ] 保存政策版本、上线时间、测试账号和签字记录，但测试证据中不保留真实 OpenID/token。

所有项目与实际部署一致后方可开启 `readerAccountEnabled` 或评论提交。
