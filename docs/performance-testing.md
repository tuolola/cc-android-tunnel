# 性能与流畅度测试

“按钮能点、页面能打开”是功能正确；“触摸后及时反馈、动画不跳帧、滚动不抢位置”需要另外测量。现有 Compose 交互测试和流式滚动断言不能证明发布版本在实体设备上足够流畅。当前工程还没有 Macrobenchmark 模块、`StartupTimingMetric` / `FrameTimingMetric` 基准或完整绘制时间上报。

## 先做可重复的诊断采样

在仓库根目录的 PowerShell 中运行。先安装要测的 APK、连接并授权设备；只有一个已连接设备时可以省略 `-Serial`。脚本默认使用仓库内的 `.android-sdk\platform-tools\adb.exe`，其他位置可通过 `-AdbPath` 指定。

```powershell
.\.android-sdk\platform-tools\adb.exe devices -l
```

记录 APK/提交版本、设备、Android 版本、刷新率、动画倍率、数据量、网络状态和操作步骤。对比前后版本时保持这些条件一致，使用正常动画倍率，在设备温度稳定后开始；调试构建与模拟器结果只用于诊断。

### 启动：Startup 模式

先结束正在进行的工作；此模式每次都会强制停止目标应用，再启动主 Activity：

```powershell
.\scripts\measure-performance.ps1 -Mode Startup -Serial emulator-5554 -Iterations 10
```

将示例序列号替换为目标设备。脚本保留全部样本，包括第一次，并汇总 `am start -W -S` 的 `TotalTime` 最小值、中位数、P90 和最大值。

这里的“冷启动”是**应用进程被停止后的启动**，不会清空应用数据、系统缓存或编译状态，也不是首次安装启动。`TotalTime` 不是 TTFD（完全绘制时间）、网络就绪时间或第一次可用交互时间。应用的 `LaunchOverlay` 另有两个帧等待和 515 ms 动画，应另外观察遮罩消失与首个交互，不能把动画设计时长直接判定为卡顿。脚本不测暖/热启动；这些场景需要单独执行并按后文基准方法测量。

### 交互：Frames 模式

先打开目标页面，再运行命令；看到 `NOW repeat` 提示后，在前台应用里**手动重复指定动作**，采样期间保持同一个应用进程运行：

```powershell
.\scripts\measure-performance.ps1 -Mode Frames -Serial emulator-5554 -Scenario drawer -DurationSeconds 20
```

例如这一轮反复点击开合侧栏，下一轮改成连续拖动，分别使用 `-Scenario drawer-tap` 与 `-Scenario drawer-drag` 留档。每次只测一种明确场景，不要把所有页面混在一个窗口里。空闲状态和流式更新状态也应单独各采一轮。

脚本清零 `gfxinfo` 统计，等待采样窗口结束后读取帧统计，并检查进程是否更换、可用时检查统计起点是否被再次重置。它不会自动操作 UI，也不能验证命名的动作是否实际发生。窗口包含等待操作的空闲时间和后台数据更新产生的帧；没有持续产帧时不能用“帧数 ÷ 窗口秒数”推导交互帧率。不要同时运行另一个会重置 `gfxinfo` 的采集器。

默认输出在 `artifacts/performance/<时间戳>/`；可以用 `-OutputDirectory` 指定新的目录。每轮包含 `environment.json` 和 `summary.json`；Startup 另存每次启动原始文本，Frames 另存 reset、baseline 和 `gfxinfo-framestats.txt`。保留原始文件和实际操作记录，方便复核。

`summary.json` 的掉帧分类和分位数来自 Android 的 `gfxinfo`，不是脚本从每个显示帧独立重算的结果；`framestats` 详细历史有容量上限。诊断数据的含义与系统版本有关，参见 [Android dumpsys 文档](https://developer.android.com/tools/dumpsys)。脚本不设置通过/失败阈值。

## 场景覆盖矩阵

每项至少覆盖正常数据和较大数据；同一动作重复多轮，记录偶发停顿、动画跳变、误触、失去响应或位置跳动。

| 场景 | 操作与观察点 |
| --- | --- |
| 启动 | 进程冷启动、保留进程的暖启动、后台返回的热启动；分别记录首帧、遮罩结束、数据就绪和可交互时间。 |
| 侧栏 | 按钮开合、边缘拖出、释放回弹、动画途中反向拖动、快速重复点击；搜索大量匹配项、置顶筛选、最近会话折叠。 |
| 页面 | 设置、连接工作台、账号与用量、访问管理、通知、帮助标签互切；全部会话、开源许可页打开/返回，覆盖长内容滚动。 |
| 按钮与弹层 | 模型/推理强度、权限模式、会话设置、工作台菜单、会话菜单和工具详情；测首次打开、再次打开、连续切换、拖动关闭、返回键关闭。 |
| 长列表 | 大量会话、通知、累计多页用量记录和长历史消息；快速滑动、搜索、筛选、列表内容改变。 |
| 流式回复 | 长 Markdown、代码块、表格持续增长；底部跟随、滚开阅读、回到最新消息，同时开侧栏和切设置页。 |
| 图片 | 展开含大图/多图的工具结果、滚动进出屏幕、图片加载完成；观察展开动画与解码是否相互阻塞。 |
| 键盘 | 输入框聚焦、键盘弹出/收起、多行输入框增高、输入时打开侧栏或弹层；检查布局、消息位置与按键反馈。 |
| 状态组合 | 空闲与后台流式更新各测一遍；加载中、离线/重连、有错误提示时重复关键动作，区分网络等待和 UI 反馈延迟。 |

## 正式验收与定位

正式验收应在目标实体设备上使用接近发布配置、不可调试且启用 `profileable` 的构建，固定数据和操作序列。新增独立 Macrobenchmark 模块，使用 UI Automator 驱动上述动作；构建与运行要求见 [Macrobenchmark 官方指南](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview)。这些基准目前尚未在本项目接入。

- `StartupTimingMetric`：分别测冷/暖/热启动。TTID 对应首个显示帧；要测 TTFD，需要先定义“内容完全可用”的条件并正确上报 `reportFullyDrawn()`，不能用 Activity 创建结束代替。
- `FrameTimingMetric`：对侧栏、切页、滚动和弹层分别取 P50/P90/P95/P99。Android 12 及以上优先查看 `frameOverrunMs` 与尾延迟；正值表示超过该帧截止时间，再结合 `frameDurationCpuMs` 分析 CPU 产帧耗时。指标定义见 [Macrobenchmark 指标文档](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-metrics)。
- 发现异常后查看对应 Perfetto trace，沿主线程、RenderThread、调度、布局/绘制、GC 和图片解码定位原因，再修改并在相同条件下复测。
- 按钮“触摸到首次视觉反馈”的延迟需要单独测：定义触摸事件与第一帧高亮/缩放的时间点，通过输入与显示 trace 或外部高帧率拍摄验证。平均帧率和动画总时长都不能替代这项测量。

刷新率对应的理论帧周期为 `1000 / Hz`：

| 刷新率 | 理论帧周期 |
| --- | ---: |
| 60 Hz | 16.67 ms |
| 90 Hz | 11.11 ms |
| 120 Hz | 8.33 ms |
| 165 Hz | 6.06 ms |

这是解释刷新节奏的参考，不是统一的耗时通过线。显示刷新率可能动态变化，应用目标帧率、渲染流水线与实际截止时间也要核对；不能直接把 `gfxinfo` 分位数或 CPU 耗时与上表比较后宣布掉帧/合格。应依据真实 deadline 的超时指标、尾延迟和可重复的交互体验制定项目验收目标。

## 当前代码中值得优先验证的风险

以下只来自静态代码检查，不代表已测出卡顿；路径均相对仓库根目录。

| 位置 | 已确认行为与待验证场景 |
| --- | --- |
| `ui/ChatMessages.kt` 的 `ToolOutput` | Base64 解码在组合阶段执行，图片解码在 `AndroidView.update` 同步执行；优先测大图/多图展开。 |
| `ui/MarkdownRenderer.kt` 的 `NativeMarkdown` | 文本改变会重新解析整篇 Markdown，并重建相关 spans/代码高亮；测长回复高频流入。 |
| `ui/WorkbenchApp.kt` 的 `DrawerContent` | 无搜索词时最多显示 18 项，有搜索词后使用 Column 全量显示匹配项；测大量搜索命中和折叠动画。 |
| `ui/WorkbenchApp.kt` 的 `WorkbenchApp` | 覆盖页下聊天仍保持组合；测持续回复时开侧栏与切设置标签。 |
| `ui/SecondaryScreens.kt` 的 `NativeScrollContent` | ScrollView 内为完整 Column，通知与用量行全量渲染；测大数据页面首次进入和滚动。 |
| `ui/WorkbenchApp.kt` 的 `ChatScreen` | 搜索在组合中遍历聊天行并构造搜索文本；测长历史搜索与流式更新叠加。 |

上述 Kotlin 路径的共同前缀是 `app/src/main/java/dev/ccdesk/nativeapp/`。性能报告应区分“已测场景”“未测场景”和“静态风险”，不要把模拟器调试采样写成全部页面的发布性能结论。
