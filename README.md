# plugin-halo-weapp

[HaloWeApp](https://github.com/xiaoxura/HaloWeApp) 微信小程序的配套 Halo 插件。

当前开发版本为 **v0.2.0 RC**，配套 HaloWeApp v0.4.0。接口契约的唯一事实来源是
[docs/openapi.yaml](docs/openapi.yaml)；发布前验证状态见客户端仓库的
[v0.4.0 RC 清单](https://github.com/xiaoxura/HaloWeApp/blob/develop/v0.4.0/docs/release-checklist-v0.4.0.md)。

## 能力

- **公开配置**（`GET /apis/api.weapp.halo.run/v1alpha1/config`）：站点展示、分页、字体、
  评论、公告、最低版本、隐私版本，以及默认关闭的 Moment / 微信读者开关；
- **匿名评论短会话**（`POST .../session`）：兼容 HaloWeApp v0.3.0，`wx.login` code 换取
  90 分钟不透明 token，不创建持久账号；
- **微信读者身份**（`/auth/login`、`/auth/profile`、`/auth/session`、`/auth/account`）：
  用户主动同意后创建/恢复最小化资料，支持修改昵称、退出和注销；
- **文章评论/回复安全网关**（`POST .../comments`、`POST .../comments/{name}/replies`）：
  会话、频控、幂等和微信 `msgSecCheck`（仅 `pass`）全部通过后才代理写入 Halo；
- **可选 Moment 能力配置**：只下发公开读取意图；Moment 数据由客户端固定访问
  `PluginMoments >= 1.15.0` 的匿名 Public API，本插件不代理私有内容。

v0.2.0 不实现 Moment 评论写入。`momentCommentEnabled` 仅为 v0.4.1 预留且默认关闭。

## 身份与安全边界

- 微信读者不是 Halo Console / UC 用户，不拥有 Halo 角色，不能读取私有文章或私有 Moment；
- AppSecret 使用 Halo Setting 密码字段；access token、session_key 和原始 OpenID 不持久化、
  不返回客户端、不写日志；
- `WeAppUser` 只保存 2～20 字昵称与隐私政策版本，内部名称来自
  `HMAC-SHA256(identityKey, appId + ":" + openId)` 的截断摘要；
- 32 字节 identityKey 存在独立内部 ConfigMap `plugin-halo-weapp-identity`，不进入 Setting、
  公开 DTO、日志或 jar；它必须与 WeAppUser 一起加密备份；
- 若已有 WeAppUser 而 identity ConfigMap 缺失或为空，插件会**失败关闭**，不会静默生成新密钥
  并割裂旧账号；
- 所有新开关默认关闭。插件/微信/Halo 上游不可用、隐私版本不一致或客户端版本过低时，登录和
  写入 fail-closed，公开阅读不受影响；
- 插件不持有 Halo 管理员 PAT，也不伪造管理员身份。

完整分析见 [威胁模型](docs/threat-model.md)、
[微信读者 ADR](docs/adr/0003-wechat-reader-identity.md) 和
[Moment ADR](docs/adr/0004-public-moment-integration.md)。

## 环境要求

| 项目 | 要求 |
| --- | --- |
| Halo | `>= 2.23.0` |
| Java / 构建 | JDK 21 + Gradle Wrapper |
| Halo 评论 | 启用评论组件并允许游客评论 |
| 微信 | 小程序 AppID / AppSecret；需配置并核对隐私保护指引 |
| Moment | 可选；`PluginMoments >= 1.15.0`，推荐 v1.16.1 |

CI 会分别用 Halo plugin API platform 2.23.0 和 2.25.0 编译并运行测试；RC 已在隔离的
Halo 2.23.3 与 2.25.4 H2 运行时完成插件冷启动、匿名 API 和 Moment 1.15/1.16 启停升级冒烟。
编译/本机运行测试都不等于真实微信、生产数据或双真机证据，发布前仍须按
[deployment.md](docs/deployment.md) 完成目标环境矩阵。

## 安装与初始配置

1. 从 [Releases](https://github.com/xiaoxura/plugin-halo-weapp/releases) 下载 jar，或本地构建：

   ```bash
   ./gradlew clean build -PhaloApiVersion=2.23.0
   ```

   产物为 `build/libs/plugin-halo-weapp-0.2.0.jar`。Halo 只从 jar 的 `extensions/` 发现扩展
   资源；构建产物必须包含 `extensions/settings.yaml` 与 `extensions/roles.yaml`。
2. Halo Console → 插件 → 安装/升级并启用。
3. 填写 AppID/AppSecret、站点展示、最低版本和 HTTPS 隐私政策。
4. **暗部署阶段保持以下开关关闭**：
   - 瞬间展示、瞬间评论展示、微信读者登录；
   - 评论区展示、评论提交、回复提交。
5. 先验证公开 config 无内部身份字段、旧 v0.3.0 客户端无回归，再依次灰度 Moment 读取、
   微信读者登录和文章评论。

首次开放读者登录前必须备份并核验两个 ConfigMap；升级/恢复顺序、identityKey 指纹校验和
v0.1.1 回滚方法见 [部署、升级、备份与回滚](docs/deployment.md)。已发布的 v0.1.0 tag
不满足 Halo 生产资源发现、Spring 注入和匿名 config 授权要求，禁止作为回滚版本；维护候选及
双 Halo 证据见 [`hotfix/v0.1.1` 的验证记录](https://github.com/xiaoxura/plugin-halo-weapp/blob/hotfix/v0.1.1/docs/release-validation-v0.1.1.md)。

字体、图片、视频和音频 URL 必须为 HTTPS，并将实际最终 origin 加入微信小程序
`downloadFile` 合法域名；站点 origin 还需加入 `request` 合法域名。域名配置必须在开启校验的
iOS 与 Android 真机验证。

## 本地开发与测试

```bash
# 默认以最低 API 2.23.0 构建正式兼容产物
./gradlew clean build -PhaloApiVersion=2.23.0

# 较新 API 编译/测试矩阵（Halo 2.25.4 runtime 对应已发布 API platform 2.25.0）
./gradlew clean test -PhaloApiVersion=2.25.0

# 可选：启动本地 Halo 2.25.4 调试实例
./gradlew haloServer -PhaloDevVersion=2.25.4
```

v0.2.0 RC 当前有 100 项 Java 自动化测试，覆盖配置门禁、插件资源布局、Spring 构造器选择、
匿名 RBAC、匿名/账号会话、内容安全、频控、幂等、读者身份 HMAC、并发首次创建、identityKey
初始化/损坏/丢失、资料修改、退出和注销。

## 文档

- [OpenAPI](docs/openapi.yaml) — API 契约唯一事实来源
- [部署、升级、备份与回滚](docs/deployment.md)
- [隐私实施指南](docs/privacy.md)
- [威胁模型](docs/threat-model.md)
- [架构决策记录](docs/adr/)
- [变更日志](CHANGELOG.md)

## License

[GPL-3.0](LICENSE)
