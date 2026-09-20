# M0 进度记录（Android 原生重构地基）

> 里程碑定义见 `PLAN-android-native-refactor.md` §6。本文件记录 M0 落地状态，随执行持续更新。

## 已达成

### 1. 构建环境修复（前置阻塞）
- 环境仅有 JDK 25，与项目 `Gradle 8.11.1 + AGP 8.7.3` 不兼容（`06` 调研已预警）。
- 下载 Temurin JDK 21 到 `%TEMP%\jdk21\...`，构建时 `JAVA_HOME` 指向它。
- 创建 `local.properties` 指向本机 SDK `C:/Users/xiufe/AppData/Local/Android/Sdk`。

### 2. 数据层三修正（PLAN §3.3，缺陷定位来自 `research/04_data_api.md`）
| 缺陷 | 修法 | 文件 |
|---|---|---|
| `/event` 未传 directory（实例级流，多目录会静默丢事件） | `EventStream` 新增 `directory` 参数并加 `?directory=`；`ChatViewModel` 存储/传递当前会话 directory | `EventStream.kt`、`ChatViewModel.kt`、`ChatView.kt` |
| `ChatStore` 未处理 `message.part.delta`（丢流式文本） | 新增 `Event.PartDelta`，`reduce` 对已存在 part 做字符串追加（`part.text += delta`），孤儿 delta 丢弃 | `ChatStore.kt` |
| 运行态用 deprecated `session.idle` | `parseEvent` 新增 `session.status`（busy/retry→RUNNING，idle→IDLE），保留 `session.idle` 兜底 | `ChatStore.kt` |

### 3. Compose 主题地基（PLAN §3.3 → OC2 token）
- `build.gradle.kts`(root/app) 接入 Compose 编译器插件 + `androidx.compose` BOM 2024.12.01 + material3 + activity-compose + navigation-compose。
- `app/build.gradle.kts` 开启 `buildFeatures.compose = true`。
- 新建 `ui/theme/OcTheme.kt`：`OcColors`(dark/light 全部语义 token：bg 层/text/icon/border/overlay/state/agent/syntax) + `OcType`(11–20sp) + `OcShapes`(2/4/6/8/10) + `OcElevation` + `OcTheme(isDark){}` CompositionLocal provider。**不用 M3 配色**，组件直读 `OcTheme.colors`。

### 4. Compose 应用壳（M0 导航脚手架）
- `ui/App.kt`：`OcApp()` = `OcTheme` + NavHost，4 路由（home/session/settings/new-session），复刻 Web MemoryRouter 顶级目的地（真实持久化在 M3）。
- `ui/screen/HomeScreen.kt`：Home 空状态占位（“这里还没有内容 / 创建一个会话以开始。”）。
- `OcActivity.kt` + Manifest：非 launcher Activity，debug deep link `opencode-native://ui` 可达，不影响既有 WebView/原生聊天壳。

## 验证状态
- [x] `:app:testDebugUnitTest`（全量）BUILD SUCCESSFUL，无回归；含新增用例：
  - `ChatStoreTest`：`partDeltaAppendsTextAndDropsOrphans`、`parseEventHandlesPartDeltaAndSessionStatus`
  - `EventStreamTest`：`appendsDirectoryQueryParamWhenProvided`
- [x] `:app:assembleDebug` BUILD SUCCESSFUL → `app/build/outputs/apk/debug/app-debug.apk`（26.77 MB）
- [ ] 真机 dark/light 切换手测（`adb shell am start -d "opencode-native://ui"`）

## M0 退出条件对照（PLAN §6）
- [x] 引入 Compose；OcTheme(light/dark)+tokens；导航壳(Home)；数据三修正
- [ ] 启动进 Home（当前经 debug deep link，待 M3 接管真正 Home/launch）
- [x] dark/light 切换（OcTheme 双套 + Activity 按系统夜询问）
- [x] SSE 收事件到真实目录（`/event` 带 directory）

## 下一步（M1）
- 时间线(流式+diff) + 4-part 渲染(tool/text/reasoning/compaction) + composer(text/附件/@-mention) + 发送/停止。
- 会话 VM 接 Compose；paced-reveal 打字感（`07` 定案：无光标）。
