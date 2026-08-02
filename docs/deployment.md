# 部署、升级与回滚

> 对应开发计划 §14「分阶段上线与回滚」。插件与小程序的版本组合须满足
> 兼容矩阵：`plugin-halo-weapp` v0.1.0 ↔ HaloWeApp v0.3.0，Halo `>= 2.23.0`。

## 前置条件

- Halo >= 2.23.0（已在 2.23.x 与 2.25.4 验证）；
- Halo 后台启用评论组件并**允许游客评论**（插件仅代理 Public Comment API）；
- 微信小程序 AppID / AppSecret（微信公众平台 → 开发管理）；
- 小程序后台将站点域名加入 request 合法域名（HTTPS）。

## 阶段 A：插件暗部署

1. 安装 `build/libs/plugin-halo-weapp-0.1.0.jar`（Halo 后台 → 插件 → 安装）并启用；
2. 打开插件设置：
   - **微信小程序**：填入 AppID / AppSecret（AppSecret 仅服务端保存）；
   - **站点展示**：填写博客名称、简介、每页文章数和可选字体 URL；
   - **评论**：三个开关保持**关闭**（默认）；
   - **版本与隐私**：填入隐私政策 HTTPS URL 与版本；
3. 验证公开配置（应只含白名单字段，无 AppSecret/OpenID）：

   ```bash
   curl https://<你的站点>/apis/api.weapp.halo.run/v1alpha1/config
   ```

4. 验证匿名可访问（不带任何凭据返回 200）；若返回 401/403，
   检查 `extensions/roles.yaml` 是否随插件加载（重新安装插件触发）。

## 阶段 B：小程序发布

1. `config/index.js` 中只配置小程序版本和 Halo 站点地址：

   ```js
   module.exports = {
     version: '0.3.0',
     baseUrl: 'https://<你的站点>'
   }
   ```

   插件名称与 API 路径已经固化在客户端协议中。不要在小程序配置中加入 Halo 管理员
   PAT、AppSecret 或其他长期凭据；它们会随小程序包公开。

2. 发布 v0.3.0（默认只读：插件侧开关全关，小程序无 UGC 入口）；
3. 完成微信隐私保护指引（声明：昵称、评论内容、服务端临时 OpenID 的处理目的）
   并通过审核；
4. 观察插件与小程序错误率，此阶段**不开放评论**。

## 阶段 C：灰度开放

1. 插件设置中先开启「评论区展示」（`commentEnabled`）；
2. 观察无异常后开启「评论提交」（`submitEnabled`）；
3. 用少量真实账号验证：正常发布、审核中（pending）、重复点击、限流（429）；
4. 观察微信内容安全额度、Halo 审核队列与 5xx；指标稳定后开启「回复提交」。

## 升级

- 插件：直接安装新版本 jar 并升级；内存会话随即全部失效，用户下次提交时
  小程序会自动重新 `wx.login`（表现为一次性重新登录，属预期行为）；
- 小程序：正常发版。旧版小程序（v0.2.x）不受插件影响，保持只读；
- 配置 Schema 演进规则见 `docs/openapi.yaml` 头部（`schemaVersion` 内只增可选字段）。

## 回滚

| 级别 | 操作 | 效果 |
| --- | --- | --- |
| 一级 | 设置 `submitEnabled=false` / `replyEnabled=false` | 立即关闭写入（已打开表单的最终提交仍被服务端拒绝） |
| 二级 | 设置 `commentEnabled=false` | 隐藏整个评论区 |
| 三级 | 停用插件 | 客户端探测失败，自动回落本地只读默认（评论隐藏） |
| 四级 | 小程序回滚至 v0.2.0 | 完全回到只读体验；插件保持关闭状态以便排查 |

## 安全运维

- AppSecret 泄露：立即在微信公众平台重置并更新插件设置；
- 插件日志不包含 AppSecret / access token / session_key / OpenID 明文，
  用户标识为 HMAC 不可逆哈希，可安全收集；
- 微信内容安全接口有日调用额度（未上架小程序额度较低），
  频控参数（每分钟/每小时）可按后台压力调整；
- 备份必须同时覆盖两个 Halo ConfigMap：Setting 配置位于
  `plugin-halo-weapp-configmap`（包含 AppSecret 密文），读者身份 HMAC 密钥单独位于
  `plugin-halo-weapp-identity`（首次使用身份能力时生成，仅含 `identityKey`）；两者都应加密保存，
  并限制备份读取权限；
- 保存或重置插件设置只会更新 Setting ConfigMap，不应删除或重建
  `plugin-halo-weapp-identity`。升级、回滚、停用或卸载插件时默认保留该内部 ConfigMap；重新安装
  前应先确认它仍存在；
- 恢复时必须原样恢复同一份 `identityKey`，并在开放登录前演练已有账号恢复。密钥丢失或损坏时
  服务会失败关闭，既有 `WeAppUser` 无法由 OpenID 再定位；禁止静默生成新密钥并宣称旧账号可恢复。
