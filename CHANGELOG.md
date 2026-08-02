# 变更日志

本项目遵循 [SemVer](https://semver.org/lang/zh-CN/)。

## [未发布]

## [0.1.1] - 未发布

HaloWeApp v0.3.0 与 plugin-halo-weapp v0.2.0 的维护回滚基线；不增加公开 API 或业务能力。

### 修复

- 将 Setting 定义从 jar 根目录移至 Halo 生产插件加载器可发现的
  `extensions/settings.yaml`，避免协调器因找不到 `plugin-halo-weapp-settings` 而启动失败
- 以不生效的前向兼容 Setting 组保留 v0.2.0 的 feature 配置，避免回滚协调时删除对应
  ConfigMap 数据；新开关在 v0.1.1 中始终不产生业务能力
- 为两个含测试辅助构造器的 Spring 组件显式选择生产 `@Autowired` 构造器
- 为匿名 `/config` 补充 Halo collection GET 所需的 `list` 权限，避免被重定向至登录页

### 验证

- 增加资源布局、Setting 引用、匿名 config RBAC 与 Spring 多构造器装配回归测试
- Java 21 下 71 项测试通过 Halo API 2.23.0 / 2.25.0 双编译基线
- Halo 2.23.3 与 2.25.4 隔离运行时均完成 v0.2.0 → v0.1.1 → v0.2.0 闭环；
  ConfigMap、Moment 数据与匿名路由通过，详见 `docs/release-validation-v0.1.1.md`

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
