# ADR-0003：微信读者身份与最小化持久化

- 状态：已接受（M1）
- 日期：2026-08-02

## 背景

v0.3.0 的 `POST /session` 只为评论提交创建 90 分钟内存会话，没有可恢复资料、退出或注销。
v0.4.0 需要让用户主动建立可感知的“微信读者”身份，同时不能把普通读者升级为 Halo User、
不能把 OpenID 或长期凭据暴露给客户端，也不能用本地布尔值伪造登录状态。

## 决策

### 身份边界

- 身份来源仅为当前小程序 AppID 下微信 `code2Session` 返回的 OpenID；
- “微信读者”不是 Halo Console / UC 账号，不拥有 Halo 角色，不能访问私有文章、私有 Moment
  或任何管理 API；
- AppSecret、OpenID、session_key、identityKey、身份摘要和内部资源名均不返回客户端；
- 客户端只得到 90 分钟随机不透明 token 与 `{displayName, privacyPolicyVersion}` 公开资料。

### 确定性身份与密钥

- 首次需要读者身份时生成 32 字节 `SecureRandom` identityKey，以 Base64 编码存入独立的插件
  内部 ConfigMap `plugin-halo-weapp-identity` 的 `identityKey` 数据项。该 ConfigMap 与 Setting
  使用的 `plugin-halo-weapp-configmap` 隔离，避免 Halo 保存或重置插件设置时覆盖密钥；
- identityKey 不进入 Setting 表单、公开 DTO、日志、错误、jar 资源或测试快照；
- ConfigMap 缺失或没有 key 时，只有在系统中不存在任何 WeAppUser 才允许按“首次使用”初始化；
  若已有读者资源则返回 `HALO_UNAVAILABLE` 并等待恢复备份，禁止生成新 key 割裂旧账号；
- 身份摘要固定为 `HMAC-SHA256(identityKey, appId + ":" + openId)`；AppID 参与输入，切换
  AppID 不会复用旧身份；
- `WeAppUser.metadata.name` 为 `reader-` 加摘要的稳定 160-bit 十六进制前缀。名称只用于插件
  内部资源定位，不通过 Public API 返回；
- identityKey 必须随独立 ConfigMap 加密备份。丢失后无法从资源名反推 OpenID，轮换必须通过
  显式迁移完成，禁止静默重置后宣称旧账号仍可恢复。

### 持久资源

```yaml
apiVersion: weapp.halo.run/v1alpha1
kind: WeAppUser
metadata:
  name: reader-<160-bit digest prefix>
spec:
  displayName: <2～20 Unicode 字符>
  privacyPolicyVersion: <创建或最近更新资料时同意的版本>
```

- `WeAppUser` scheme 由插件生命周期注册，资源不聚合到 anonymous 角色；
- 首次并发登录使用确定性名称：先 fetch，缺失则 create；create 冲突后再次 fetch，确保同一
  OpenID 最终只产生一个资源；
- spec 不保存 OpenID、完整摘要、AppID、token、微信头像、手机号、邮箱或网站；
- 注销删除 `WeAppUser` 并撤销关联的全部内存会话。既有公开 Halo 评论不自动删除，客户端
  二次确认与隐私政策必须明确说明。

### 登录、恢复与会话

- `POST /auth/login` 仅在 readerAccount 实时开关开启、客户端版本满足且隐私版本与当前设置
  完全一致时执行 `code2Session`；
- 首次创建必须由用户主动点击、明确同意隐私政策并提供昵称；冷启动只可恢复已有资源，不能
  在后台静默创建新账号；
- 首次昵称和后续修改都执行 2～20 Unicode 字符校验、频控与 `msgSecCheck`，仅 `pass` 放行；
- 账号会话与评论临时会话使用同一随机 token/TTL 协议。账号会话额外携带 readerName；
  评论临时会话保持兼容且不创建 `WeAppUser`；
- `DELETE /auth/session` 只撤销当前 token；`DELETE /auth/account` 删除资源并按 readerName
  撤销全部 token；插件重启使全部短会话失效，但持久资料仍可通过新的 wx.login 恢复。

### 客户端持久化

- token、OpenID、身份摘要和内部名称绝不写 storage；
- storage 只允许保存“保持登录意愿”、公开资料与已同意隐私版本；缓存资料不代表已认证；
- 隐私版本变化时停止静默恢复，用户重新同意前不能登录、修改资料或把缓存资料当作认证态。

## 后果

- 账号可跨冷启动、跨设备恢复，同时原始 OpenID 不落盘；
- 插件停用、开关关闭、identityKey 损坏/丢失且已有读者或微信不可用时身份能力 fail-closed，
  公开阅读不受影响；
- identityKey 成为必须备份的高价值服务端资产，运维文档必须覆盖备份、恢复与轮换约束；
- 微信读者与 Halo User 保持严格隔离，未来若需要作者发布能力必须另立 UC 授权 ADR。
