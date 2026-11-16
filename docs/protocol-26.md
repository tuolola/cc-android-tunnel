# 协议 26 适配记录

核对日期：2026-11-16。上游基准为 [cc-desk-tunnel v0.3.15](https://github.com/sun168567/cc-desk-tunnel/releases/tag/v0.3.15)，固定提交 `c908845fa4ae26ebf4a665b8101a4b4c4e4ee396`；差异范围为 [v0.3.14 → v0.3.15](https://github.com/sun168567/cc-desk-tunnel/compare/v0.3.14...v0.3.15)。本文记录已核对的上游契约及 Android 适配边界，实际测试结果在文末单独登记。

## 版本与发布状态

| Android 构建 | 协议 | 对应上游服务端 | 状态 |
| --- | --- | --- | --- |
| GitHub 公开下载的 `v1.0.4-native` | 25 | `v0.3.14` | 已发布的公开测试 APK；原下载链接与校验值保持不变 |
| `1.0.5-native` / versionCode 11 | 26 | `v0.3.15` | 本轮公开预发布目标；[Release 与附件](https://github.com/tuolola/cc-android-tunnel/releases/tag/v1.0.5-native)，构建与验证见文末 |

上游 v0.3.15 的桌面客户端、`ccdt` 和服务端需配套升级。Android 的 `1.0.x-native` 是独立版本号，不能仅凭名称判断协议兼容。服务端明确拒绝协议不匹配的连接并以 WebSocket `4002` 关闭；本次适配不意味着同时兼容协议 25，也不意味着现有服务器已升级。[发布说明](https://github.com/sun168567/cc-desk-tunnel/releases/tag/v0.3.15)、[服务端检查](https://github.com/sun168567/cc-desk-tunnel/blob/v0.3.15/apps/server/src/server.ts#L1834)

README 的部署教程现对应 `v1.0.5-native` 与上游 v0.3.15，已核对该 tag 的官方 Windows 安装包、服务端程序包与 `CC_DESK_TUNNEL_VERSION=0.3.15` 安装参数。旧公开 v1.0.4 的大小与 SHA-256 仅作为历史记录，不能用于协议 26 的新附件。[上游资产](https://github.com/sun168567/cc-desk-tunnel/releases/tag/v0.3.15)、[固定版本安装脚本](https://github.com/sun168567/cc-desk-tunnel/blob/v0.3.15/deploy/install.sh#L67)

## 历史分块格式

以下 UUID 为文档示例；真实帧使用当前请求及会话的 UUID。

```json
{
  "type": "session.history.part",
  "requestId": "11111111-1111-4111-8111-111111111111",
  "sessionId": "22222222-2222-4222-8222-222222222222",
  "data": "[{\"type\":\"session.snapshot\",",
  "last": false
}
```

`data` 是一个 `ServerMessage[]` JSON 文本的连续片段，长度为 **1 至 32768 个 UTF-16 code units**，不是 UTF-8 字节数。它可以在 JSON 转义、字段或 Unicode 代理对中间断开，必须将所有 `data` 按 WebSocket 到达顺序拼接，再一次性解析。没有分片序号、总长度或额外的快照元数据帧；`last=true` 表示最后一块。[帧 schema](https://github.com/sun168567/cc-desk-tunnel/blob/v0.3.15/packages/protocol/src/index.ts#L847)

组装后的消息数组为：

- `session.subscribe`：一个 `session.snapshot`，以及可选的、在快照边界捕获的 `session.fragment`。
- `session.history`：一个 `mode="prepend"` 的 `session.snapshot`。

快照保留原有字段 `requestId`、`session`、`events`、`mode`、`firstSequence`、`lastSequence`、`hasEarlier`；`mode` 为 `replace`、`append` 或 `prepend`。捕获的 fragment 包含 `sessionId`、`runId`、`payload`，不带 `requestId`。服务端会先捕获快照和未完成片段，再交付这一固定数组。[快照 schema](https://github.com/sun168567/cc-desk-tunnel/blob/v0.3.15/packages/protocol/src/index.ts#L525)、[服务端组包](https://github.com/sun168567/cc-desk-tunnel/blob/v0.3.15/apps/server/src/server.ts#L1704)

整个数组的 JSON 文本不超过 32768 个 UTF-16 code units 时，服务端直接发送其中原有的 snapshot / fragment 帧，不发送 `session.history.part`。客户端必须同时支持直接快照与分块快照；直接帧无需 `history.next` 确认。[交付实现](https://github.com/sun168567/cc-desk-tunnel/blob/v0.3.15/apps/server/src/history-delivery.ts#L26)

## 确认、完成、超时与取消

每收到属于当前待处理请求和会话的一块，发送一次：

```json
{"type":"session.history.next","requestId":"11111111-1111-4111-8111-111111111111"}
```

**最后一块也必须确认。** 上游客户端收到块后先确认，最后一块再触发完整拼接与解析。服务端最多保留 4 块在途；收到确认后发送后续块，只有全部文本发完且所有在途块均已确认，原请求才成功返回 `response`。不能等待成功 `response` 后才确认最后一块。[客户端顺序](https://github.com/sun168567/cc-desk-tunnel/blob/v0.3.15/apps/desktop/src/client.ts#L424)、[服务端流控](https://github.com/sun168567/cc-desk-tunnel/blob/v0.3.15/apps/server/src/history-delivery.ts#L47)

每个已认证连接同时交付一页历史。客户端主动结束本次读取时发送：

```json
{"type":"session.history.cancel","requestId":"11111111-1111-4111-8111-111111111111"}
```

这两个控制帧严格只含 `type` 和 `requestId`，不属于普通命令，不各自创建待处理请求或响应。取消只作用于该连接的当前读取；错误、已结束或其他连接的请求 ID 不影响当前页。开始下一页交付会取消上一页，断连也会释放当前读取。**取消历史交付不取消会话订阅，也不停止运行。**[控制帧处理](https://github.com/sun168567/cc-desk-tunnel/blob/v0.3.15/apps/server/src/server.ts#L2238)、[取消实现](https://github.com/sun168567/cc-desk-tunnel/blob/v0.3.15/apps/server/src/history-delivery.ts#L82)

错误由原始 `session.subscribe` / `session.history` 的 `requestId` 返回：

| 场景 | 原请求结果 |
| --- | --- |
| 全部块已确认 | `response`，`ok=true`，可含 `sessionId` |
| 连续 30 秒未收到接收确认 | `response`，`ok=false`，`code="history_stalled"`，带错误 `message` |
| 显式取消或被下一页替代 | `response`，`ok=false`，`code="history_cancelled"`，带错误 `message` |

连接已关闭时不保证仍能收到错误帧。客户端也应独立检测历史读取停滞，只由该次请求收到的新块续期；无关响应、心跳或其他会话消息不能延长等待。[原请求响应](https://github.com/sun168567/cc-desk-tunnel/blob/v0.3.15/apps/server/src/server.ts#L1996)、[客户端等待](https://github.com/sun168567/cc-desk-tunnel/blob/v0.3.15/apps/desktop/src/client.ts#L907)

上游客户端对 `session.history` 在完整快照验证并应用后结束本地等待，忽略迟到的成功响应，避免数据已收到却因确认往返延迟误报超时；`session.subscribe` 仍等待原始响应。[分页完成处理](https://github.com/sun168567/cc-desk-tunnel/blob/v0.3.15/apps/desktop/src/client.ts#L620)

## 顺序、恢复与容量边界

订阅快照分块组装期间，同一会话的 `session.event`、`session.updated`、`session.fragment` 仍可能到达。先缓存这些后续消息，待完整快照及捕获的未完成片段应用完毕，再按到达顺序处理缓存；否则后到的快照可能覆盖实时输出或回退会话状态。分页继续按 `prepend` 合并，不替换当前实时片段。[上游缓冲实现](https://github.com/sun168567/cc-desk-tunnel/blob/v0.3.15/apps/desktop/src/client.ts#L449)

切换会话应取消旧读取，并忽略旧请求迟到的分块及响应，不能让它们进入新请求或覆盖新页面。订阅失败或取消后必须保留“需要重新读取”的状态；即使后来收到较大 sequence 的实时事件，下次订阅也应从 `afterSequence=0` 重新取快照，避免游标跳过未接收的历史。普通业务写入仍遵守原有去重和断线规则，不能因适配分块而自动重发。[恢复实现](https://github.com/sun168567/cc-desk-tunnel/blob/v0.3.15/apps/desktop/src/client.ts#L821)、[取消与失效标记](https://github.com/sun168567/cc-desk-tunnel/blob/v0.3.15/apps/desktop/src/client.ts#L960)、[上游边界测试](https://github.com/sun168567/cc-desk-tunnel/blob/v0.3.15/apps/desktop/test-main/client.test.mjs#L93)

历史页的 **1000 原始事件 / 512 KiB 是软预算**，完整的过大单轮仍会返回。协议没有规定聚合页的硬上限，不能把单块大小、512 KiB 页预算或 256 KiB 入站控制帧上限当成历史正文总长上限。[协议容量说明](https://github.com/sun168567/cc-desk-tunnel/blob/v0.3.15/packages/protocol/README.md#L43)

本轮 Android 实现为控制内存，另设**单页组装 16 Mi 个 UTF-16 code units** 的本机接收上限；组装期间的后续实时消息缓冲也有独立的 **16 Mi 个 UTF-16 code units** 上限。超出时明确报错并取消读取，不截断后当作成功。这是 Android 本机限制，不是协议限制；合法但超过该边界的上游巨型单轮仍可能无法在本版完整加载。

服务端为历史快照与历史分块启用协商式 WebSocket `permessage-deflate`。压缩由传输层处理，应用层收到的仍是上述 JSON，不应另外解压 `data` 字符串。[服务端压缩配置](https://github.com/sun168567/cc-desk-tunnel/blob/v0.3.15/apps/server/src/server.ts#L374)、[发送策略](https://github.com/sun168567/cc-desk-tunnel/blob/v0.3.15/apps/server/src/server.ts#L421)

## 其余协议差异与 Android 范围

除协议号与历史交付外，v0.3.15 的协议差异为：

| 变化 | Android 适配边界 |
| --- | --- |
| `account.login.method` 可选 `browser` / `setup-token`，`account.state.login.method` 同步标识 | 已有账号状态读取可保留新字段；不因此新增账号登录操作 |
| 新命令 `account.token`；一次性 `account.token.created` 含 `token/saved/error?`，只发给发起连接 | 当前 Android 未提供令牌管理入口；不得记录、缓存或展示无关连接的令牌 |
| `service.update.install.force` 可选布尔值；新增 `runWorking` 辅助判断 | 当前 Android 不执行服务端升级；本轮不增加强制中断运行的入口 |
| `tunnel.credentials.username` 允许空格 | Android 使用 `tunnel=false`，不承担电脑执行通道 |

`ready`、`session`、持久事件和 `historyPage` 的字段没有额外变化。现有设备身份过滤、手机/电脑会话归属、认证、权限和业务命令范围应保留；本轮不因上游新增能力而虚构手机操作入口。[协议完整差异](https://github.com/sun168567/cc-desk-tunnel/compare/v0.3.14...v0.3.15)、[新增账号字段](https://github.com/sun168567/cc-desk-tunnel/blob/v0.3.15/packages/protocol/src/index.ts#L78)、[升级参数](https://github.com/sun168567/cc-desk-tunnel/blob/v0.3.15/packages/protocol/src/index.ts#L633)

## 本轮验证

本轮构建为 `1.0.5-native` / versionCode 11，Debug 签名。以下区分本地协议夹具与真实已配置服务，不将 README 中旧版测试数量计入本轮。

| 检查 | 本轮结果 |
| --- | --- |
| JVM 测试 | **92 项通过，0 失败** |
| Android Lint | **0 个错误，40 条警告** |
| 生产源码/APK 原生边界审计 | 通过 |
| APK 审计回归 | **8 项通过** |
| 真机协议 26 / TLS | **真机 7 项 TLS 测试全部通过：5 项 NativeTlsIntegrationTest 与 2 项协议 26 分块历史测试** |
| 最终界面与交互 | 25 项真机交互通过（侧栏 2、流式滚动 3、聊天 7、原生输入框 7、手势 6），覆盖侧栏 30/60/65 条本地追加与搜索、IME/大字输入、模型与审批、滚动、途中反向拖动和退出阻断。浅色与深色各 14 个状态截图测试通过；人工复看两主题聊天与侧栏，确认原胶囊输入框、圆形发送按钮和简单全宽选中行已恢复，未见裁切或旧样式残留 |
| 真实已配置服务只读检查 | 返回 **`protocolMismatch`**；VPS 须先同步到 v0.3.15 / 协议 26，当前尚未通过真实历史、分页或恢复联调 |

交互用例日志保存在本地忽略目录 `.tmp/protocol-26-review`。已核对手机安装版本为 `1.0.5-native` / versionCode 11，安装后的 `base.apk` SHA-256 与下方交付包完全一致。深色截图为测试内局部主题，系统保持浅色；正式系统主题切换及状态栏效果未单独验收。匿名附件下载校验与旧版 App 更新安装链路未验收，发布后单独记录。

真实服务检查使用显式 `runLive=true` 的 [ConfiguredProtocol26ReadOnlyTest](../app/src/androidTest/java/dev/ccdesk/nativeapp/core/ConfiguredProtocol26ReadOnlyTest.kt)：凭据仅写内存隔离上下文、沿用原设备 ID，并通过出站白名单禁止消息发送、会话创建/删除和权限设置。报告仅包含固定状态、版本/协议与计数；本轮没有升级现有 VPS。

本轮最终 APK 大小为 **14,522,782 字节**，SHA-256 为 `13646f48395fe0b7303ce087bdcbcce47a3b80567c5da21f40745e8cc96e2bf2`。签名证书 SHA-256 为 `eaac7685189a81114415665d17e1ce4e3e4fa1154ae1c03470ff430a192f025b`，与旧版相同。这些值对应本轮最终 APK，不修改旧公开 v1.0.4 附件。

尚未验收的范围包括现有 VPS 的协议 26 真实历史/分页、真实账号下的消息与工具执行，以及完整设备套件；本地 TLS 夹具不能代替这些流程。发布后匿名下载校验与旧版更新发现的结果在 [App 更新记录](app-updates.md#验收与复现) 单独登记。
