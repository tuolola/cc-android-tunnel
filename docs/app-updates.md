# App 更新与版本发布

App 更新从 **`1.0.2-native`（versionCode 8）**开始提供。旧版 `v1.0.1-native` APK 没有更新检查功能，需要先手动安装本版；仅更新 GitHub 仓库不会给旧 APK 增加功能。

本版为 Android 7.0 及以上的 **Debug 签名公开测试版**：[下载 CCtunnel.apk](https://github.com/tuolola/cc-android-tunnel/releases/download/v1.0.2-native/CCtunnel.apk) · [发布说明](https://github.com/tuolola/cc-android-tunnel/releases/tag/v1.0.2-native) · [SHA256SUMS.txt](https://github.com/tuolola/cc-android-tunnel/releases/download/v1.0.2-native/SHA256SUMS.txt)。APK 大小为 **13,563,373 字节**，SHA-256：

```text
fcaa59deb34e22370cbf350a7d3a9ff176afc55e8a667d066c0122c67e21e47e
```

## 在 App 中使用

打开 **设置 → 概览 → App 更新**，可查看当前安装版本、检查状态和上次成功检查时间。

- **检查更新 / 重试检查**：立即检查本项目的公开版本；失败会显示原因，可手动重试。
- **自动检查更新**：默认开启，App 进入前台时判断是否需要检查；成功后间隔 24 小时，失败后等待 1 小时再自动尝试，可以关闭。手动检查不受此间隔限制。它不是后台定时下载任务。
- **接收测试版更新**：初始值取决于安装版本是否带预发布后缀，例如 `1.0.2-native` 默认开启，`1.0.2` 默认关闭。开启后同时考虑正式版与公开测试版，关闭后只考虑正式版。
- **发现新版**：设置齿轮显示小圆点，更新卡片提供版本信息和可展开的发布说明，不弹窗打断对话。说明使用可选择、复制的原生文本显示。
- **下载 APK**：打开系统浏览器中的 APK 下载地址，由用户完成下载和安装。**App 不在内部下载或静默安装，也不声称已经校验下载文件。**

如果 GitHub APK 资产提供 SHA-256 `digest`，卡片会显示该值。它是发布资产的元数据，不代表 App 已读取并校验手机上的 APK。下载后可以自行将文件哈希与 Release 附件 `SHA256SUMS.txt` 核对，例如在 Windows 的 APK 所在目录运行：

```powershell
Get-FileHash .\CCtunnel.apk -Algorithm SHA256
```

覆盖安装需要相同包名、相同签名以及更高的 Android `versionCode`。旧版 `v1.0.1-native` 是 Debug 签名测试包；本版 `1.0.2-native`（versionCode 8）Debug APK 已核对使用相同签名，不需要先卸载旧版。后续 APK 也必须保持相同签名才能覆盖现有安装，单独更改版本名称不能解决签名不一致。

## 检查来源与版本规则

更新检查独立访问 **[tuolola/cc-android-tunnel Releases](https://github.com/tuolola/cc-android-tunnel/releases)**，按所选渠道筛选版本及名为 **`CCtunnel.apk`** 的附件。草稿和没有可用 APK 的发布不会触发更新提醒；符合条件且比当前安装版本新的版本才提示更新。

App 版本按 SemVer 比较，不按发布时间或字符串字典序判断：例如 `1.0.10` 高于 `1.0.9`，`1.0.2` 高于 `1.0.2-native`。带预发布后缀的版本可以继续使用序号，例如 `1.0.2-native.1`、`1.0.2-native.2`。

这项检查不依赖工作台连接，不使用工作台令牌或 Cookie，也不发送 `service.update.check`。后者是**上游服务端升级**协议，不能用来判断本 Android App 是否有新版。

## 维护者发布流程

1. 修改 [app/build.gradle.kts](../app/build.gradle.kts)：每次可覆盖安装的新构建都递增 `versionCode`；`versionName` 必须与准备发布的 `v` 前缀 tag 对应，例如 `versionName = "1.0.2-native"` 对应 `v1.0.2-native`。
2. 正式版使用纯 `x.y.z`，例如 `1.0.2`；测试版使用合法 SemVer 预发布名称，并在 GitHub Release 勾选 **Set as a pre-release**。不要只在标题写“测试版”而漏设发布属性。
3. 构建并验证目标 APK。用于覆盖更新的包保持既有签名，附件名称固定为 **`CCtunnel.apk`**。在发布说明中准确写明 Debug/Release 构建类型、变更、验证结果和待验证范围。
4. 对最终上传的 APK 计算 SHA-256，生成 **`SHA256SUMS.txt`**，内容格式为 `<64 位 SHA-256>  CCtunnel.apk`。哈希必须对应实际附件，不能使用另一轮构建的结果。
5. 从已验证提交创建对应 tag 和 Release 草稿，上传 `CCtunnel.apk`、`SHA256SUMS.txt` 并填写发布说明。确认版本、渠道和附件正确后再 **Publish release**；草稿阶段不应提醒用户更新。
6. 发布后检查匿名用户能打开 Release 并下载两个附件，核对下载文件哈希；再从较旧且具备更新检查功能的 App 手动检查，确认所选渠道、版本提示和下载链接正确。

构建与 GitHub 发布是两个独立步骤。不要为了显示“有更新”覆盖已有 tag 的附件或把旧版本 APK 替换成新版本；应发布新的版本记录。

## 验收与复现

截至 2026-11-14，`1.0.2-native` 已独立构建，65 项 JVM 测试通过（包含 17 项更新逻辑测试），8 项 APK 审计回归通过，原生边界与独立 APK 审计通过；Android Lint 为 0 个错误、40 条警告。完整 Android 设备套件共 72 项：**68 项通过、4 项跳过、0 项失败**，其中包含下方 7 项更新设备测试。

本轮显式启用了公开 GitHub 更新检查。跳过的是工作台真实服务测试 `LiveWorkbenchAcceptanceTest`、`LiveSessionRecoveryTest`、`RealToolAcceptanceTest`、`RealNetworkRecoveryTest`；它们不计作聊天、会话恢复、工具或断网恢复流程验收通过。

发布前已在 Android 14 模拟器的实际 `MainActivity` 中进入设置并检查真实 GitHub 发布源，验证浅色/深色主题、关闭两个更新选项后重启仍保持关闭，以及关闭测试版渠道后显示“当前渠道暂无可安装版本”。检查当时公开 Release 只有 `v1.0.1-native` 测试版，安装的 `1.0.2-native` 比它新；因此“当前版本无需更新”是当时的正确结果，不是发现了线上新版。

测试覆盖分为三层，不能混用证据：

| 测试 | 覆盖范围 |
| --- | --- |
| [17 项 JVM 更新测试](../app/src/test/java/dev/ccdesk/nativeapp/core/AppUpdatesTest.kt) | SemVer、渠道、附件及 HTTPS 链接过滤、分页、请求隔离、并发检查、缓存持久化、失败重试和冷却时间 |
| [4 项组件测试](../app/src/androidTest/java/dev/ccdesk/nativeapp/ui/AppUpdateScreenTest.kt)与 [2 项设置容器测试](../app/src/androidTest/java/dev/ccdesk/nativeapp/ui/AppUpdateIntegrationTest.kt) | 英文 fixture 的检查中/新版/失败状态、原生可选文本、链接回调、开关、设置页滚动和导航；不是线上已发布的新版本 |
| [1 项真实 GitHub 测试](../app/src/androidTest/java/dev/ccdesk/nativeapp/core/AppUpdateLiveTest.kt) | 读取公开 Release，确认有可解析版本及本仓库的 APK/发布页地址；不下载或安装 APK，不访问工作台 |

在仓库根目录可执行以下专项检查。设备测试需要已连接且授权的 Android 设备或模拟器；这里只列复现命令，不会启用工作台聊天、工具执行或断网测试。

```powershell
# 17 项更新逻辑测试
.\gradlew.bat :app:testDebugUnitTest --tests dev.ccdesk.nativeapp.core.AppUpdatesTest

# 6 项使用英文 fixture 的组件及设置容器测试
.\gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=dev.ccdesk.nativeapp.ui.AppUpdateScreenTest,dev.ccdesk.nativeapp.ui.AppUpdateIntegrationTest"

# 1 项真实公开 GitHub 发布检查，需设备可访问 GitHub
.\gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=dev.ccdesk.nativeapp.core.AppUpdateLiveTest" "-Pandroid.testInstrumentationRunnerArguments.runLiveUpdates=true"

# 复现包含真实 GitHub 更新检查的完整设备套件；工作台真实服务测试仍默认跳过
.\gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.runLiveUpdates=true"
```

真实 GitHub 检查是显式 opt-in；未设置 `runLiveUpdates=true` 时会跳过。没有网络或 GitHub 限流应视为检查失败，不能宣称当前版本已最新。测试中的下载按钮只核对链接回调；用户经系统浏览器下载、校验及覆盖安装的完整流程仍需单独验收，不能由 fixture 结果推断已通过。

## 与上游更新机制的关系

上游 `cc-desk-tunnel` 在服务端查询其 GitHub `releases/latest` 和 `release.json`：启动 60 秒后首次检查，随后每 6 小时检查；已连接的桌面窗口也可在上次检查超过一小时后请求服务端再检查。上游桌面自升级主要下载安装 Windows EXE，服务端也有自己的程序包升级流程。

本 Android App 保留检查状态、手动重试和更新说明的思路，但使用本项目的 APK 发布源与独立版本号。上游的纯三段版本比较、Windows 安装器和服务端升级流程不适用于 Android APK。

上游参考固定于提交 `9172e9317227440495f6bc5dadcc7504d3d7f01e`：

- [服务端检查时机与发布清单处理](https://github.com/sun168567/cc-desk-tunnel/blob/9172e9317227440495f6bc5dadcc7504d3d7f01e/apps/server/src/updates.ts#L88)
- [桌面端检查间隔](https://github.com/sun168567/cc-desk-tunnel/blob/9172e9317227440495f6bc5dadcc7504d3d7f01e/apps/desktop/src/update-check.ts#L3)
- [Windows 安装器启动流程](https://github.com/sun168567/cc-desk-tunnel/blob/9172e9317227440495f6bc5dadcc7504d3d7f01e/apps/desktop/electron/main.cjs#L738)
