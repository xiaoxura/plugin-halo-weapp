# 变更日志

本项目遵循 [SemVer](https://semver.org/lang/zh-CN/)。

## [未发布]

目标版本：plugin-halo-weapp v0.2.0，配套 HaloWeApp v0.4.0。当前为 RC 开发分支；目标环境暗部署、
双真机、真实微信和恢复/回滚演练完成前不创建 v0.2.0 tag。v0.1.0 真实运行时回滚失败，必须先
完成 `hotfix/v0.1.1` 的正式维护发布，才能建立可执行的二进制回滚基线。

### 新增

- 公开配置 `features.moments` / `features.readerAccount` 节点与 Halo Setting 开关，默认全部关闭
- 微信读者 OpenAPI 与端点：登录/恢复、查询/修改资料、退出当前会话和注销账号；错误使用稳定
  `READER_NOT_FOUND`、`READER_ACCOUNT_DISABLED`、`PRIVACY_CONSENT_REQUIRED` 等业务码
- `WeAppUser` 内部扩展，只持久化昵称与隐私版本；scheme 不聚合到匿名角色
- `IdentityKeyService`：独立 Opaque Secret `plugin-halo-weapp-identity`、32 字节安全随机 key、
  HMAC-SHA256 确定性资源名、并发单飞初始化和损坏失败关闭
- 同一 OpenID 首次并发登录幂等创建；create 冲突后 fetch，避免重复身份资源
- 账号会话复用既有随机 token/TTL 协议并关联 readerName；退出撤销当前 token，注销删除资源并
  撤销该读者全部会话，匿名评论短会话保持兼容且不创建账号
- 首次昵称和资料修改的 Unicode 长度校验、频控、当前隐私版本校验与微信 `msgSecCheck`
- 微信读者身份 ADR、公开 Moment 集成 ADR、v0.2.0 OpenAPI 与扩展威胁模型
- Halo API 2.23.0 / 2.25.0 编译测试矩阵和 develop/v0.2.0 CI；默认正式产物仍以最低
  API 2.23.0 构建

### 变更

- 插件开发版本升级为 0.2.0；配置 schemaVersion 保持 1，仅增加向后兼容可选节点
- `SessionService` 支持账号会话精确撤销与按 readerName 全部撤销，原 v0.1.0 评论会话路径不变
- 部署文档扩展为 v0.2.0 暗部署、独立 identity Secret/WeAppUser 备份恢复、分级开关和
  v0.3.0 + 插件 v0.1.1 回滚路径；明确禁止使用不可执行的 v0.1.0 tag
- Setting 扩展定义移至 jar 的 `extensions/settings.yaml`，与 Halo 生产插件加载器的发现目录一致；
  新增资源布局回归测试，禁止只在 jar 根目录保留 `settings.yaml`
- 对存在测试构造器的 Spring 组件显式选择生产注入构造器，避免真实 Halo 启动时因多构造器而
  无法创建 Bean

### 安全

- AppSecret、access token、session_key、原始 OpenID、identityKey 和完整摘要不进入公开
  config、auth DTO、错误、插件业务日志或客户端；Halo core 可能在索引日志中显示
  `WeAppUser.metadata.name` 的 HMAC 截断名，但其中不含原始 OpenID
- HMAC 输入绑定 AppID；WeAppUser 资源名仅保留 160-bit 摘要前缀，spec 不保存 OpenID、AppID、
  token、头像、邮箱、手机号或网站
- readerAccount 实时开关、客户端 SemVer、HTTPS 隐私 URL和完全一致的隐私版本在登录/资料修改
  时重新校验；开关关闭、配置失败、未知微信结果均 fail-closed
- 已存在 WeAppUser 时若 identity Secret 缺失或 key 为空，身份服务返回
  `HALO_UNAVAILABLE`，禁止静默生成新 key 导致旧账号不可定位
- 实测 Halo 2.23.3 删除 ConfigMap 时会在 `GcReconciler` INFO 日志序列化完整 data；identityKey
  因此改用 `Secret.data`。早期 RC 同名 ConfigMap 在启动时原值迁移并清除 key，冲突/损坏时
  阻止插件启动；Secret 删除日志只显示 byte[] 对象引用
- 注销仅删除微信读者资源和账号会话；既有公开 Halo 评论不会自动删除，OpenAPI、隐私和客户端
  二次确认统一说明
- 匿名角色按 Halo `RequestInfoFactory` 的 resource/resourceName/nonResourceURL 语义最小授权；
  `/auth/login`、资料、退出、注销和评论回复不再被 Halo Security 重定向到 Console 登录页

### 验证

- 106 项 Java 自动化测试在 Halo plugin API platform 2.23.0 与 2.25.0 均通过
- `./gradlew clean build -PhaloApiVersion=2.23.0` 作为最终兼容产物门禁；OpenAPI 重复键、
  资源默认开关和敏感值扫描纳入 RC 清单
- Halo 2.23.3 与 2.25.4 隔离 H2 运行时均已验证 Setting/ConfigMap 初始化、插件冷启动、公开
  配置和全部匿名 auth/评论路由；两端还完成 Moment 1.15.0 → 1.16.1 启停、升级和数据保留冒烟
- `hotfix/v0.1.1` / `cfaa16f` 在相同双 Halo 运行时完成 v0.2.0 → v0.1.1 → v0.2.0：
  v0.1.1 `STARTED`，旧路由为 JSON，ConfigMap/Moment 保留，再升级后 Setting 与全部路由恢复
- Halo 2.23.3 合成身份演练完成 ConfigMap → Secret 原值迁移、完整目录备份/独立恢复、缺
  Secret 重启不轮换，以及 v0.2.0 → v0.1.1 → v0.2.0；identityKey 指纹和 WeAppUser spec
  全程一致，Secret 生命周期日志未出现 key。无真实微信上游，已有账号无昵称恢复仍待目标环境
- v0.1.1 GitHub Actions [run 30741013676](https://github.com/xiaoxura/plugin-halo-weapp/actions/runs/30741013676)
  的 Halo API 2.23.0/2.25.0 矩阵及最低版本 jar build 全部成功
- 编译/Mock 与本机 H2 合成数据测试不能替代目标环境暗部署、真实微信登录、真实账号恢复、
  双真机和 v0.1.1 正式维护发布/目标环境回滚演练；这些证据完成前保持未发布

## [0.1.0] - 2026-08-02

HaloWeApp v0.3.0 的首个配套插件正式版本。

### 新增

- 公开配置端点：白名单下发站点展示、分页、字体、评论、公告、最低版本与隐私配置
- 微信 `code2Session` 短会话：随机不透明 token 仅保存在服务端内存，默认有效期 90 分钟
- 文章评论与回复安全网关：频控、幂等、昵称/正文校验、HTML 转义与微信内容安全检测
- Halo Setting 配置表单及部署、OpenAPI、ADR、威胁模型和回滚文档

### 安全

- AppSecret、微信 access token、OpenID 与 session_key 不进入公开配置、客户端或日志
- 评论仅在 `msgSecCheck` 返回 `pass` 后写入 Halo；其他结果全部 fail-closed
- 客户端不能指定任意评论主体、审核状态、头像、邮箱、网站或管理凭据
- 公开读取使用 Halo Public API，不向小程序分发 Halo 管理员 PAT

### 验证

- Java 21 / Halo API 2.23.0 编译基线
- 67 项自动化测试全部通过
