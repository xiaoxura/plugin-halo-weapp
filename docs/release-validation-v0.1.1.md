# plugin-halo-weapp v0.1.1 维护候选验证记录

> 状态：2026-08-02 本机隔离 RC 验证通过，尚未创建 tag 或 GitHub Release。
> 本记录证明维护候选可在已验证的 Halo 版本上执行二进制回滚闭环；它不替代目标环境
> 备份恢复、真实微信或双真机验收。

## 1. 候选产物

| 项目 | 值 |
| --- | --- |
| 文件 | `build/libs/plugin-halo-weapp-0.1.1.jar` |
| 大小 | 117572 bytes |
| SHA-256 | `6c2cfc04ff883fe823eb64a69a0d47dc4ffbaac07e2744ce0cc548c79634c40c` |
| Java class | 50；全部位于 `run/halo/weapp/`，无打包第三方 class |
| Setting 布局 | `extensions/settings.yaml` 恰好 1 份；jar 根目录 `settings.yaml` 不存在 |
| manifest | `spec.version=0.1.1`、`requires=>=2.23.0`、Setting 引用一致 |

## 2. 构建与静态门禁

以下命令均从 `hotfix/v0.1.1` 干净构建执行：

```bash
./gradlew clean test -PhaloApiVersion=2.23.0
./gradlew clean test -PhaloApiVersion=2.25.0
./gradlew clean build -PhaloApiVersion=2.23.0
```

- 两个 Halo API 编译基线均为 71/71 测试通过；最低版本 build 通过；
- `plugin.yaml`、Setting、Role、CI workflow 与 OpenAPI YAML 均可解析；
- Redocly 确认 OpenAPI 有效，仅保留 `/config` 无伪造 4xx 的既有 1 个 style warning；
- Gitleaks 对 Git 历史和当前目录扫描均为 0 命中；
- `git diff --check` 通过。

## 3. 双 Halo 回滚矩阵

相同候选在两个现有隔离运行时执行：

```text
plugin-halo-weapp v0.2.0 STARTED
→ 停用并卸载（Setting 由 Halo 删除，ConfigMap 保留）
→ 安装并启用 v0.1.1
→ 验证旧 config/session/comment/reply 路由
→ 原位升级回 v0.2.0
→ 验证 Setting、ConfigMap、全部 v0.2.0 匿名路由与 Moment 数据
```

| Halo runtime | 镜像 digest | v0.1.1 | 回到 v0.2.0 | ConfigMap | Moment |
| --- | --- | --- | --- | --- | --- |
| 2.23.3 | `sha256:03f56d5b53f6d5a66cd8a368301430bbb20da355e314adeb9781165f7f86661d` | `STARTED` / Ready | `STARTED`，Setting schema 恢复 | 全程数据 SHA-256 相同 | PUBLIC 列表/详情保留，PRIVATE 详情 404 |
| 2.25.4 | `sha256:1299a0e7a849329a9f6a1a8498ec65a63dd6b70d9c74b829a2bbb5ba1ce547e6` | `STARTED` / Ready | `STARTED`，Setting schema 恢复 | 全程数据 SHA-256 相同 | PUBLIC 列表/详情保留，PRIVATE 详情 404 |

两个运行时的候选文件 SHA-256 均与第 1 节一致；恢复后的 v0.2.0 运行时文件 SHA-256 均为
`85f64f388e06e82f94e3056e888cbba8c2c4954e73d3eef0d92cef643f0652b3`。

### v0.1.1 匿名路由直接结果

| 路由 | 预期与结果 | 证明点 |
| --- | --- | --- |
| `GET /config` | 200 JSON | `get/list` 匿名 RBAC 生效，无 `/login` 302，无敏感字段 |
| `POST /session` | 503 `HALO_UNAVAILABLE` | 已进入未配置微信凭据的业务错误，而非 Halo 登录页 |
| `POST /comments` | 401 `SESSION_REQUIRED` | 已进入评论业务会话校验 |
| `POST /comments/{name}/replies` | 401 `SESSION_REQUIRED` | 子资源匿名授权与业务会话校验生效 |

### 配置与扩展资源不变量

- ConfigMap 数据 SHA-256（规范化 JSON）：
  `e562f8b79645e188dcf855c6b47780c89bb573d096b26af90bc0f28a2309d351`；
- v0.2.0 Setting spec SHA-256：
  `a4023633a1c241c9cd469085d50700ebddeb5933aabbae42fd8ec8706eb049f0`；
- v0.1.1 Setting 携带不生效的 `features` 前向配置组，因此 Halo 协调器不会在回滚时删除
  v0.2.0 的 ConfigMap feature 数据；
- 再升级 v0.2.0 后，Setting spec 与 ConfigMap 数据均恢复/保持上述哈希；Moment 插件始终
  `available=true`。

## 4. 结论与剩余门禁

v0.1.0 已由真实运行时证明不可执行，不得继续作为正式回滚基线；v0.1.1 候选解决了资源
发现、Spring 构造器选择、匿名 collection GET 授权以及前向 ConfigMap 保留问题。

在 v0.1.1 正式维护发布完成前，v0.2.0/v0.4.0 的五级回滚门禁仍保持未完成；不得据此创建
`plugin-halo-weapp v0.2.0` 或 `HaloWeApp v0.4.0` tag。
