# 变更日志

本项目遵循 [SemVer](https://semver.org/lang/zh-CN/)。

## [未发布]

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
