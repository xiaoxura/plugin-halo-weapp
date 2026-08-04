# ADR-0004：公开 Moment 集成与可选依赖降级

- 状态：已接受（M1）
- 日期：2026-08-02

## 背景

Halo Moment 是可选插件，v1.15.0 起提供匿名 Public API。其内容包含 HTML、标签以及
PHOTO / VIDEO / AUDIO / POST 多类媒体，且插件可能未安装、停用、超时或留下陈旧搜索索引。
原生小程序 tabBar 无法动态隐藏单个 tab，因此不能给所有部署制造固定死入口。

## 决策

### 固定依赖契约

- 客户端编译期固定插件名 `PluginMoments` 和 API 前缀
  `/apis/api.moment.halo.run/v1alpha1`，部署者不能配置任意插件名或探测路径；
- 每次冷启动最多调用一次 Halo plugin available API；并发调用单飞，结果只存内存，
  不持久化 `true`；超时、非 2xx、HTML、非法 JSON 和显式 false 一律视为不可用；
- 最低兼容 Moment v1.15.0，推荐 v1.16.1。匿名客户端只消费 Public API 返回的公开、已审核
  内容，不读取 PRIVATE Moment，不携带 Halo PAT、Console Cookie 或 UC 身份；
- `features.moments.enabled` 缺失时默认 false。未过期 config 缓存可恢复只读展示意图，
  但仍必须通过本次冷启动的 PluginMoments available 探测。

### 导航与数据边界

- v0.4.0 使用首页异步“最新瞬间”模块和二级列表/详情页，不新增固定 tab；
- 首页文章首屏不等待 Moment 探测或请求。依赖不可用时模块完全隐藏；分享深链则显示可返回、
  可重试的不可用/不存在状态；
- 页面只消费客户端 adapter 的 `MomentSummary` / `MomentDetail` 白名单模型，不直接访问插件
  原始字段；列表使用安全纯文本摘要，详情复用既有 HTML 清理和资源补全管线；
- 列表分页单飞、按 metadata.name 去重；标签切换重置分页；陈旧 Moment 搜索命中在功能关闭
  或插件不可用时过滤。

### 媒体安全与生命周期

- 所有相对头像和媒体 URL 通过站点 baseUrl 补全；生产只接受 HTTPS；
- PHOTO 最多展示接口提供的 9 项并仅预览当前 Moment 的有效 URL；
- VIDEO 不自动播放，切换页面或其他媒体时停止；AUDIO 统一使用一个 InnerAudioContext，
  进入后台和页面卸载时销毁；
- POST 能可靠映射本小程序文章时内部跳转，否则只提供复制 HTTPS 地址；
- 未知媒体保留不可执行的安全占位和可复制 HTTPS 链接，不猜测协议、不导致页面崩溃。

### 搜索、点赞与后续评论

- 搜索 adapter 只接受 `post.content.halo.run` 与 `moment.moment.halo.run`，输出明确 kind 并使用
  对应详情路由；
- tracker 点赞参数由受控 subject 描述生成：Post 使用 `content.halo.run/posts`，Moment 使用
  `moment.halo.run/moments`；本地状态使用 `post:<name>` / `moment:<name>` 命名空间；
- Moment 评论不作为 v0.4.0 的默认生产能力。v0.4.1 P1 已按 ADR-0005 实现固定主体独立路由，
  `features.moments.commentEnabled` 默认 false；客户端永远不能上传任意 subjectRef。

## 后果

- 未安装或停用 Moment 时文章主链路、搜索中的文章结果和读者身份均可独立工作；
- 首页没有固定死入口，但分享深链仍有可解释的恢复状态；
- 媒体 CDN 必须由部署者预先加入微信合法域名，代码无法绕过平台白名单；
- 私有 Moment 与作者发布能力明确后置，不会因“登录”功能误引入高权限凭据。
