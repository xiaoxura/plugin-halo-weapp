# plugin-halo-weapp

[HaloWeApp](https://github.com/xiaoxura/HaloWeApp) 微信小程序的配套 Halo 插件，提供：

- **公开配置下发**（`GET /apis/api.weapp.halo.run/v1alpha1/config`）：
  站点展示、分页、字体、评论开关、公告、最低版本、隐私政策等，不发版即可调整；
- **微信登录短会话**（`POST .../session`）：`wx.login` code 换取 90 分钟不透明
  token，AppSecret / OpenID / session_key 不出服务端；
- **安全评论/回复写入**（`POST .../comments`、`POST .../comments/{name}/replies`）：
  登录态、频控、幂等、微信 `msgSecCheck`（仅 `pass`）全部通过后才代理
  Halo Public Comment API 写入。

接口契约的唯一事实来源：[docs/openapi.yaml](docs/openapi.yaml)。

> 当前可执行的 v0.3.0 配套与 v0.2.0 回滚基线是 **v0.1.1**。请勿使用
> v0.1.0：该版本的 Setting 资源不在 Halo 可发现目录中，并且生产 Spring
> 容器无法确定两个多构造器组件的注入构造器。

## 安全模型

- 评论读取仍直接使用 Halo Public API，不经过本插件；
- 写能力 fail-closed：插件停用、配置异常、微信服务不可用时自动降级为只读；
- AppSecret 使用密码字段存储，日志全链路脱敏，详见
  [docs/threat-model.md](docs/threat-model.md)。
- Halo 管理员 PAT 不进入小程序包，插件也不持有或伪造管理员身份；公开读取接口无需 PAT。

## 环境要求

- Halo `>= 2.23.0`（已在 2.23.x 与 2.25.4 验证）
- 构建：JDK 21 + Gradle Wrapper（`./gradlew build`）
- Halo 后台需启用评论组件并允许游客评论（插件仅代理公开评论 API，不持有任何凭据）

## 安装与配置

1. 在 [Releases](https://github.com/xiaoxura/plugin-halo-weapp/releases) 下载 jar，
   或本地 `./gradlew build` 后取 `build/libs/plugin-halo-weapp-<version>.jar`；
2. Halo 后台 → 插件 → 安装并启用；
3. 打开插件设置：
   - **微信小程序**：填入 AppID / AppSecret（仅服务端保存）；
   - **站点展示**：配置博客名称、简介、分页大小和可选字体 URL；
   - **评论**：三个开关默认全部关闭，提审期间保持关闭；
   - **公告 / 版本与隐私**：按需配置；
4. 小程序端只需在 `config/index.js` 配置版本和 Halo `baseUrl`；插件名称与 API 路径是
   双端固定协议，无需重复填写。

字体 URL 必须使用 HTTPS，并将其域名加入微信小程序的 `downloadFile` 合法域名；留空时
客户端使用系统字体。

## 本地开发

```bash
./gradlew build          # 构建 + 测试
./gradlew haloServer     # 启动本地 Halo 2.25.4 调试实例（可选）
```

## 文档

- [docs/openapi.yaml](docs/openapi.yaml) — API 契约（唯一事实来源）
- [docs/threat-model.md](docs/threat-model.md) — 威胁模型
- [docs/adr/](docs/adr/) — 架构决策记录
- [docs/deployment.md](docs/deployment.md) — 部署、升级与回滚
- [docs/release-validation-v0.1.1.md](docs/release-validation-v0.1.1.md) — 维护候选双 Halo 回滚证据

## License

[GPL-3.0](LICENSE)
