# plugin-halo-weapp v0.2.0：部署、升级、备份、恢复与回滚

> 适用目标：HaloWeApp v0.4.0 + plugin-halo-weapp v0.2.0。
> 当前文档描述 RC 流程，不表示生产演练已经完成。实际证据和未验证项必须写入
> [HaloWeApp v0.4.0 发布清单](https://github.com/xiaoxura/HaloWeApp/blob/develop/v0.4.0/docs/release-checklist-v0.4.0.md)。

## 1. 支持边界

| 组件 | 要求/说明 |
| --- | --- |
| Halo | `>= 2.23.0`；目标发布需实际验证 2.23.x 与 2.25.4 |
| Halo plugin API | CI 编译测试 2.23.0 与 2.25.0；2.25.4 runtime 发布的对应 API platform 是 2.25.0 |
| HaloWeApp | 目标 v0.4.0；v0.3.0 为回滚客户端 |
| PluginMoments | 可选，最低 v1.15.0，推荐 v1.16.1；缺失时只隐藏 Moment，不影响文章 |
| Java | JDK 21 |
| Halo 评论 | 开启评论组件并允许游客评论；配套插件只代理 Public Comment API |

### 版本组合

| 小程序 | 配套插件 | 行为 |
| --- | --- | --- |
| v0.3.0 | v0.1.0 | 已打 tag 的正式回滚基线 |
| v0.3.0 | v0.2.0 | `/config`、`/session` 和文章评论保持兼容；新可选字段被旧客户端忽略 |
| v0.4.0 | v0.1.0 | 新 `features`/auth API 缺失，Moment 与读者身份 fail-closed；旧文章能力可读 |
| v0.4.0 | v0.2.0 | 目标组合；新开关仍须按阶段显式开启 |

“协议上兼容”不替代运行时演练。发布前必须在目标 Halo 版本验证旧 v0.3.0 config/session/文章
评论，以及 v0.4.0 的 Moment 降级和读者账号闭环。

## 2. 受保护数据与备份范围

升级、卸载或回滚前使用 Halo Console 的**完整备份**作为权威恢复点，并确认备份成功、可下载、
加密保存且具备恢复权限。另需显式核对以下扩展数据均在恢复范围内：

| 资源 | 作用 | 敏感性/恢复约束 |
| --- | --- | --- |
| `plugin-halo-weapp-configmap` | Setting 数据；含 AppID、AppSecret 密文、站点/评论/版本/隐私开关 | 敏感；限制读取权限 |
| `plugin-halo-weapp-identity` | 仅含 Base64 编码的 32 字节 `identityKey` | 高敏感；必须加密备份且原样恢复 |
| `weapp.halo.run/v1alpha1` `WeAppUser` | 昵称、隐私版本及 HMAC 派生内部名称 | 与同一时点 identityKey 配套恢复 |
| Halo Comment/Reply | 已公开的文章评论与回复 | 注销读者账号不会自动删除 |

identityKey 与 WeAppUser 是一个不可拆分的恢复集合：

- key 丢失后不能从资源名或 OpenID 反推；
- 换 AppID 会改变 HMAC 输入，旧账号不能自动映射；
- 恢复旧 key 却漏掉 WeAppUser，会使冷启动恢复返回账号不存在；
- 恢复 WeAppUser 却漏掉 key 时，v0.2.0 会返回 `HALO_UNAVAILABLE` 并失败关闭，**不会生成新 key**；
- 任何 key 轮换都需要专门的数据迁移方案，本版本不支持在线轮换。

### 2.1 可选的定向核验导出

以下命令仅用于管理员核对完整 Halo 备份内容，不应替代经过演练的整站备份。PAT 只保存在本机
环境变量，不能进入小程序、Git、命令行参数、工单或聊天记录。

```bash
export HALO_URL='https://<你的 Halo 站点>'
read -rsp 'Halo administrator PAT: ' HALO_PAT; echo
umask 077
mkdir -p halo-weapp-backup

curl --fail-with-body --silent --show-error \
  -H "Authorization: Bearer ${HALO_PAT}" \
  "${HALO_URL}/apis/v1alpha1/configmaps/plugin-halo-weapp-configmap" \
  > halo-weapp-backup/plugin-halo-weapp-configmap.json

curl --fail-with-body --silent --show-error \
  -H "Authorization: Bearer ${HALO_PAT}" \
  "${HALO_URL}/apis/v1alpha1/configmaps/plugin-halo-weapp-identity" \
  > halo-weapp-backup/plugin-halo-weapp-identity.json

curl --fail-with-body --silent --show-error \
  -H "Authorization: Bearer ${HALO_PAT}" \
  "${HALO_URL}/apis/weapp.halo.run/v1alpha1/weappusers?page=1&size=1000" \
  > halo-weapp-backup/weappusers-page-1.json

unset HALO_PAT
```

读者超过一页时必须继续导出全部分页。定向 JSON 含敏感数据，不得提交 Git。用下列脚本只输出
不可逆 SHA-256 指纹并确认 key 长度，不打印 key 本身：

```bash
python3 - <<'PY'
import base64, hashlib, json
with open('halo-weapp-backup/plugin-halo-weapp-identity.json', encoding='utf-8') as f:
    encoded = json.load(f)['data']['identityKey']
key = base64.b64decode(encoded, validate=True)
if len(key) != 32:
    raise SystemExit(f'invalid identityKey length: {len(key)}')
print('identityKey sha256:', hashlib.sha256(key).hexdigest())
PY
```

将指纹、备份时间、Halo/插件版本、WeAppUser 数量和整站备份校验和写入受控运维记录；不要记录
identityKey 原文。

## 3. 构建与产物核验

正式 v0.2.0 jar 以最低 Halo API 2.23.0 编译：

```bash
export JAVA_HOME=/path/to/jdk-21
export PATH="$JAVA_HOME/bin:$PATH"

./gradlew clean test -PhaloApiVersion=2.23.0
./gradlew clean test -PhaloApiVersion=2.25.0
./gradlew clean build -PhaloApiVersion=2.23.0

sha256sum build/libs/plugin-halo-weapp-0.2.0.jar
unzip -p build/libs/plugin-halo-weapp-0.2.0.jar plugin.yaml | grep -E 'name:|requires:'
```

将 jar SHA-256、源提交和测试结果写入 release 记录。不要发布从 2.25.0 编译但未经最低版本验证的
替代 jar。

## 4. 从 v0.1.0 升级到 v0.2.0

1. 在 v0.1.0 Setting 中关闭评论提交/回复；记录公开 config 并完成旧客户端冒烟测试。
2. 执行第 2 节完整备份。v0.1.0 尚无 identity ConfigMap/WeAppUser 属正常情况。
3. 安装 v0.2.0 jar 并启用；不要删除或重建 Setting ConfigMap。
4. 打开设置并确认新增开关均保持默认关闭：
   - `momentsEnabled=false`；
   - `momentCommentEnabled=false`；
   - `readerAccountEnabled=false`；
   - 评论展示、提交、回复继续按暗部署要求关闭。
5. 匿名调用 `/config`：必须返回 JSON、`schemaVersion=1`，新 feature 全为 false，并且响应中无
   `appSecret`、OpenID、identityKey、readerName 或内部摘要。
6. 使用旧 HaloWeApp v0.3.0 回归配置、匿名 `/session` 和文章评论；此时不得开放新功能。

v0.2.0 的 identity ConfigMap 仅在首次实际使用读者身份时按需生成。第一次小范围测试账号登录
成功后，应立即重新执行完整备份并记录 identityKey 指纹；在该备份完成前不得扩大 readerAccount。

## 5. 分阶段上线

### 阶段 A：插件暗部署

1. 完成备份、jar 校验和 v0.3.0 回归；
2. 保持 Moment、读者账号及全部评论开关关闭；
3. 观察 `/config`、`/session` 状态码、上游耗时和脱敏日志；
4. 确认公开配置、错误响应和日志不含内部身份字段。

### 阶段 B：小程序 v0.4.0 只读审核

1. 发布 v0.4.0，所有新 feature 仍关闭；
2. 回归首页文章、搜索、文章详情、Profile 匿名态和原评论读取；
3. 核对微信隐私保护指引；提审期间不新增 UGC 入口；
4. 完成审核并观察客户端异常，不因缓存误开登录或写入。

### 阶段 C：公开 Moment

1. 安装并确认 `PluginMoments >= 1.15.0`，推荐 v1.16.1；
2. 从生产 Moment 响应提取站点、图片、视频、音频和附件的全部 HTTPS origin，加入微信
   `downloadFile` 合法域名；
3. 在开启合法域名校验的 iOS/Android 真机验证首页 3 条、列表、详情、深链和
   PHOTO/VIDEO/AUDIO/POST/未知媒体；
4. 开启 `momentsEnabled`，观察 API 延迟、媒体失败率和文章首屏；
5. `momentCommentEnabled` 保持关闭，v0.4.0 不支持 Moment 评论写入。

### 阶段 D：微信读者身份

1. 确认 HTTPS 隐私 URL/版本与微信隐私保护指引一致；
2. 开启 `readerAccountEnabled`，仅用少量真实账号验证首次登录、冷启动恢复、昵称修改、退出、
   注销、隐私版本变化和插件重启后的恢复；
3. 首次创建 identityKey 后立即备份并记录指纹；验证 WeAppUser 不含原始 OpenID；
4. 检查公开 DTO、错误和实际日志；任何原始 OpenID/AppSecret/token/identityKey 命中均停止发布；
5. 完成 iOS/Android 与弱网矩阵并稳定观察后才扩大范围。

### 阶段 E：文章评论灰度

1. 先开启评论区读取，再开启提交，最后开启回复；
2. 同时验证认证读者会话复用和匿名临时会话；
3. 验证 published/pending、重复幂等、429、内容拦截、401 单飞恢复和远程关闭；
4. 观察微信内容安全额度、Halo 审核队列与 5xx。

## 6. 恢复演练

恢复必须在隔离或维护窗口执行，readerAccount 和评论写入保持关闭：

1. 记录事故前 Halo、插件、小程序、Moment 版本和当前 AppID；
2. 使用 Halo 官方恢复流程恢复同一备份点的 Setting ConfigMap、identity ConfigMap、
   WeAppUser 和评论数据；禁止只恢复其中一部分；
3. 在启用登录前使用第 2.1 节脚本核对 identityKey 为 32 字节且 SHA-256 与备份记录一致；
4. 确认 AppID 与生成这些 WeAppUser 时一致；AppSecret 可轮换，但 AppID 变更需要独立迁移；
5. 启动 v0.2.0 插件，保持 feature false，验证 `/config` JSON 和旧 v0.3.0 路径；
6. 小范围开启 readerAccount，用恢复前已有账号执行**不携带昵称**的冷启动恢复；确认得到原昵称；
7. 验证退出后旧 token 401、注销后资源不存在且全部 token 失效；
8. 重新关闭 readerAccount，检查日志与资源后记录演练结果，再决定恢复灰度。

若 WeAppUser 存在但 identity ConfigMap 缺失/为空，预期行为是 `HALO_UNAVAILABLE`。此时：

- 立即保持 readerAccount 关闭；
- 不删除 WeAppUser，不保存/重置设置来“碰碰运气”，不创建新 key；
- 从可信备份原样恢复 identity ConfigMap并核对指纹；
- 无可信 key 备份时升级为数据恢复事故，不能宣称旧账号可恢复。

## 7. 回滚

优先使用远程开关止损，再回滚二进制：

| 级别 | 操作 | 效果 |
| --- | --- | --- |
| 一级 | `readerAccountEnabled=false` | 停止新登录、恢复和资料修改；文章/Moment 读取不受影响 |
| 二级 | 评论 submit/reply false | 立即关闭文章写入；已打开表单最终也由服务端拒绝 |
| 三级 | `momentsEnabled=false` | 首页模块隐藏，深链显示不可用；文章能力保留 |
| 四级 | 停用 `PluginMoments` | 文章、分类、标签和文章搜索继续工作 |
| 五级 | 插件回滚 v0.1.0 + 小程序回滚 v0.3.0 | 回到已打 tag 的正式组合 |

执行第五级回滚前：

1. 完成 v0.2.0 全量备份并保存 identityKey 指纹；
2. 关闭所有新 feature 和文章评论写入；
3. 安装 v0.1.0 jar，恢复 v0.1.0 Setting ConfigMap（如确有必要）；
4. **保留** `plugin-halo-weapp-identity` 和 WeAppUser，不在紧急回滚时清理读者资料；
5. 发布/回退 HaloWeApp v0.3.0；验证 config、session、文章读取和评论；
6. 恢复 v0.2.0 时先恢复配套 identity/WeAppUser，再按第 6 节演练。

v0.4.0 客户端在 v0.1.0 插件下会安全关闭账号和 Moment 新能力，但正式回滚应同时恢复
v0.3.0，以获得已验收且可定位的完整组合。

## 8. 安全运维与隐私

- AppSecret 泄露：立即在微信公众平台重置并更新插件设置，撤销受影响访问；
- identityKey 泄露：关闭 readerAccount、保护备份并启动显式迁移设计；不能直接换 key；
- 日志只允许 requestId、微信脱敏诊断字段和不可逆短标签，不得记录请求 header/body、OpenID、
  token、AppSecret、access token、session_key、identityKey 或 readerName；
- 备份文件权限至少 0600，加密传输/静态保存，限定保管人和保留期，销毁需可审计；
- 注销删除 WeAppUser 与账号会话，但不自动删除公开评论；数据主体请求按
  [隐私实施指南](privacy.md) 和站点政策处理；
- 微信内容安全有日额度，频控必须在微信/Halo 外部调用前生效；未知 `suggest` 一律拒绝。

## 9. 发布签字清单

- [ ] Halo 2.23.x 与 2.25.4 目标环境部署/回滚记录齐全；
- [ ] Moment 1.15.x / 1.16.1 与 iOS/Android 媒体矩阵完成；
- [ ] 两个 ConfigMap + 全部 WeAppUser + 评论的备份和恢复演练完成；
- [ ] identityKey 长度/指纹一致，已有账号无昵称恢复成功；
- [ ] v0.3.0 + v0.1.0 回滚路径实际执行成功；
- [ ] 新 feature 初始均 false，公开 config/日志/错误敏感值扫描通过；
- [ ] 真实登录、恢复、退出、注销、隐私升级和插件重启记录齐全；
- [ ] 微信隐私保护指引、站点隐私政策与实际字段/时机/注销行为一致；
- [ ] jar SHA-256、源提交、测试结果和最终 tag 一致。

任一项缺少直接证据时只能保持 RC，不得创建正式 v0.2.0 tag 或宣称发布门禁完成。
