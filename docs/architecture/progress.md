# 架构治理进度

## 运行框架

目标：在保留现有功能、数据兼容性和对外契约的前提下，把仓库收敛到唯一权威来源、标准实现、深模块、高内聚低耦合、局部变更和可执行架构约束。

本轮方式：高 rigor、渐进式。每个单元独立验证并独立提交。Android 发布身份、上游 UI 行为、服务器协议和用户本地数据是一旦破坏就很难恢复的回滚边界，因此这些边界不作为重构实验对象。

本轮的完成谓词：

- 每个接受的重构单元都有 before/after 证据。
- Android 单测、终端测试、服务端浏览器测试和相关浏览器验证保持绿色。
- 被替代实现已从源码与测试中删除，而不是与实现并存。
- 至少一项已治理规则被脚本或 CI 固化。
- 决策日志、任务池和进度文件同步更新。

## 基线验证

已在隔离 worktree `opencode-android-shell-arch-governance`、分支 `arch/governance-2026-11-28` 上建立基线。

| 验证 | 结果 |
| --- | --- |
| `node --test frontend/embedded-ui/*.test.mjs frontend/android/*.test.mjs` | 10/10 通过 |
| `cd frontend/terminal && npm ci && npm test` | 14/14 通过 |
| `cd frontend/terminal && npm run build` | 通过；`oc-terminal.js` 无 diff |
| `cd plugins/server-browser && npm ci && npm test` | 6/6 通过 |
| `cd plugins/server-browser && npm run typecheck && npm run build` | 通过 |
| `JAVA_HOME=<Android Studio JBR 21> ./gradlew :webapp:testDebugUnitTest --no-daemon` | 通过 |

完整官方 UI 构建基线已使用 pin 要求的 Bun 1.4.4 运行：`node frontend/android/build.mjs` 通过，生成 `webapp/src/main/assets/www/ui/` 后无 tracked diff、无未跟踪资产。

## 当前轮次

### Round 1：删除不可达的 Ghostty 终端兼容实现

状态：完成，已提交。

识别证据：删除前 `oc-host.js` 固定 `window.__ocTerminalEngine = "xterm"`；`installTerminalFontScale()` 与 `installTerminalTouchScroll()` 都在第一行因该值返回。生产触摸、字号、字符网格和滚动行为已经由 `frontend/terminal/terminal.js` 承担。

闭环内容：

- 删除 canvas 字体缩放补丁、触摸转 wheel 补丁和只为它们存在的 `TerminalFontScaleTest` / `TerminalTouchScrollTest`。
- 保留终端字号设置项及其存储键契约；列数估算改为直接使用浏览器原生 `measureText`。
- 完整 SPA 验证脚本的检查改为 `host-terminal-boundary`，明确宿主 facade 不得再长出第二套终端实现。
- 新增 `scripts/check-architecture.mjs`，并接入 CI，防止这些死实现复活。

验证：

- `node scripts/check-architecture.mjs` 通过。
- `node --check webapp/src/main/assets/www/oc-host.js` 通过。
- `node --test frontend/embedded-ui/*.test.mjs frontend/android/*.test.mjs` 通过。
- `cd frontend/terminal && npm test` 通过。
- `./gradlew :webapp:testDebugUnitTest --no-daemon --quiet` 通过。
- `VERIFY_TERMINAL_MODE=boot VERIFY_TERMINAL_FILTER=host-terminal-boundary node scripts/verify-android-terminal-release.mjs` 手机与平板均通过；报告中 `engine=xterm` 且 legacy host touch patch 为 `false`。

备注：一次 `VERIFY_TERMINAL_FILTER=drag` 的预改基线运行在终端打开等待处未进入 drag 检查，因此没有作为行为证据使用；本轮删除依赖静态不可达证明、xterm 单元测试和打包 SPA boot 边界验证。

### Round 2：断开 host 能力包对入口 Activity 的反向依赖

状态：完成，已提交。

闭环内容：

- 新增 `WebHostBridgeHost`，把桥所需的宿主能力声明为显式接口；`WebHostActivity` 实现该接口并在组合根注入。
- `WebHostBridge` 不再引用具体 `WebHostActivity`，只接收 `Context`、能力接口、资产域名和通知目标类型。
- `NotifierHost.postTurnFinished` 不再硬编码入口 Activity，通知目标由组合根传入。
- `scripts/check-architecture.mjs` 新增机器约束：`web/host/*.kt` 不得 import 入口 `WebHostActivity`。

验证：

- `node scripts/check-architecture.mjs` 通过。
- `git diff --check` 通过。
- `./gradlew :webapp:testDebugUnitTest --no-daemon --quiet` 通过。

### Round 3：版本事实单一权威来源

状态：完成，已提交。

闭环内容：根 README 不再复制上游版本/提交/Bun pin 或 APK versionCode/versionName，改为指向 `upstream.json` 与 `webapp/build.gradle.kts`；`frontend/android/README.md` 的当前基准说明同样改为引用权威文件。`scripts/check-architecture.mjs` 新增漂移检查，防止 README 再次硬编码这些可变事实。

验证：`node scripts/check-architecture.mjs` 与 `git diff --check` 通过。

### Round 4：删除无调用方的 Web SQL 兼容设置

状态：完成，已提交。

证据：仓库内唯一 `openDatabase` 是 IndexedDB 辅助函数命名，不是 Web SQL；打包 UI 中没有 Web SQL 调用。`databaseEnabled` 只控制已废弃的 Web SQL，不影响官方 UI 使用的 localStorage/IndexedDB，因此删除该 Deprecated 设置。`scripts/check-architecture.mjs` 禁止它无调用方回归。

验证：`node scripts/check-architecture.mjs` 与 `:webapp:testDebugUnitTest` 通过后提交。

### Round 5：宿主运行时权威源码边界

状态：完成，待提交。

闭环内容：

- 新增 `frontend/host-runtime/` 作为 `index.html`、`oc-host.js`、`oc-perf.js`、`oc-preflight.js` 的唯一权威源码位置。
- 新增 `frontend/host-runtime/build.mjs`，逐字节复制生成 `webapp/src/main/assets/www/` 中的打包输入。
- `scripts/check-architecture.mjs` 对四份源码/产物做字节级同步检查；根 README 构建命令加入该生成步骤。
- 本轮只建立边界，不改变任何运行时字节内容。

验证：

- `node frontend/host-runtime/build.mjs` 后 `webapp/src/main/assets/www` 无 diff。
- `node --check` 通过四份运行时源码与构建脚本。
- `node --test frontend/embedded-ui/*.test.mjs frontend/android/*.test.mjs` 通过。
- `cd frontend/terminal && npm test` 通过。
- `./gradlew :webapp:testDebugUnitTest --no-daemon --quiet` 通过。
- 打包 SPA boot 边界验证手机/平板通过。

### Round 6：远程浏览器 RPC 契约与快速测试入口

状态：完成，已提交。

闭环内容：

- `plugins/server-browser/src/rpc.ts` 保持唯一 RPC 契约；新增 `frontend/remote-browser/sync-rpc.mjs` 生成客户端 `rpc.ts`。
- `scripts/check-architecture.mjs` 对契约与生成文件做字节级同步检查。
- `frontend/android/sources.mjs` 不再复制第二份 RPC，并改为显式复制十个浏览器面板输入文件；本地测试产生的 `node_modules`、锁文件和 harness 不会进入上游构建。
- 新增 `frontend/remote-browser/test.mjs`、锁定的依赖和 README；快速测试可在仓库内运行，完整类型检查仍由上游 workspace 构建执行。

验证：快速测试 10/10 通过；完整 `node frontend/android/build.mjs` 通过（包括上游 remote-browser 测试与 `packages/gui-plugins` typecheck），生成 UI 资产无 diff，构建检出的 remote-browser 只包含十个显式输入文件。

### Round 7：document-start facade 第一层职责拆分

状态：完成，已提交。

闭环内容：

- 将原 `frontend/host-runtime/oc-host.js` 按现有顶层职责拆为 `android-facade.js`、`overlay-focus.js`、`terminal-settings.js`。
- `frontend/host-runtime/build.mjs` 按固定顺序拼接三份源码生成 `webapp/src/main/assets/www/oc-host.js`；诊断页、preflight 和 perf 仍逐字节复制。
- `scripts/check-architecture.mjs` 禁止单体 `frontend/host-runtime/oc-host.js` 重新出现，并验证拼接结果与打包产物一致。

行为保持证据：拆分后生成 `oc-host.js` 为 64,436 字节，SHA-256 `a5c8e6e96f8eef6a3265e83e5a807e3658a27985f196d7a8134e52ddd567198d`，与拆分前完全一致。

验证：

- 六份 runtime 源文件、构建脚本和生成产物 `node --check` 通过。
- `node --test frontend/embedded-ui/*.test.mjs frontend/android/*.test.mjs` 通过。
- `cd frontend/terminal && npm test` 通过。
- `./gradlew :webapp:testDebugUnitTest --no-daemon --quiet` 通过。
- 打包 SPA boot 边界验证手机/平板通过。

### Round 8：相机权限状态机退出 Activity

状态：完成，已提交。

闭环内容：新增 `CameraPermissionHost`，集中封装可信来源检查、VIDEO_CAPTURE 白名单、运行期权限请求去重、结果回调和销毁时 pending 请求清理。`WebHostActivity` 只注册 Android result launcher、提供可信 origin 判断并转发 WebChromeClient 回调。既有源码绊线迁移到新的所有权位置；架构检查禁止 Activity 重新持有 pending/in-flight 相机状态。

验证：`:webapp:compileDebugKotlin` 与 `:webapp:testDebugUnitTest` 通过；`node scripts/check-architecture.mjs` 与 `git diff --check` 通过。

### Round 9：Android Back 仲裁退出 Activity

状态：完成，已提交。

闭环内容：新增 `BackNavigationHost`，集中封装页面优先消费 Back、pending 回调去重、页面失败提示和首页交还 Android 默认行为。`WebHostActivity` 只在组合根安装该能力。相关源码绊线迁移到能力模块；架构检查禁止 Activity 重新直接注册 Back callback。

验证：`:webapp:testDebugUnitTest` 通过；`node scripts/check-architecture.mjs` 与 `git diff --check` 通过。

### Round 10：origin 比较规则单一化

状态：完成，已提交。

闭环内容：新增纯 JVM 可测的 `WebOrigins.sameOrigin`，统一承载 scheme/host/effective-port 精确比较；Activity 的导航拦截、字体代理和相机可信来源共用该权威实现，并删除 Activity 内私有副本。新增直接行为测试覆盖路径忽略、默认端口、scheme/host/port 不匹配和 substring 绕过。架构检查禁止 Activity 重新私有持有该逻辑。

验证：`:webapp:testDebugUnitTest` 276 项通过；`node scripts/check-architecture.mjs` 与 `git diff --check` 通过。

### Round 11：启动恢复状态机退出 Activity

状态：完成，已提交。

闭环内容：新增 `StartupRecoveryHost`，集中封装 WebView 版本读取、首屏内容看门狗、诊断页切换和主文档加载失败升级。`WebHostActivity` 保留启动链编排（何时 preflight、何时加载哪个 URL），不再持有 `watchdogArmed` / `firstContentSeen` / `loadIssuedAtMs` 等恢复状态。渲染重建与销毁路径通过同一 `cancel()` 边界清理 pending watchdog。架构检查禁止入口 Activity 重新持有这些状态或直接读取 WebView 版本包。

验证：`:webapp:testDebugUnitTest` 276 项通过；`node scripts/check-architecture.mjs` 与 `git diff --check` 通过。

### Round 12：后台生命周期与回合电源状态机退出 Activity

状态：完成，已提交。

闭环内容：新增 `BackgroundLifecycleHost`，集中封装前台真值、回合跟踪器所有权、常亮 flag 写入与兜底复查、合成 pagehide/pageshow/focus/blur、后台 SSE 保活/收口状态机以及 WebView 销毁前的回调清理。`WebHostActivity` 只转发 onPause/onResume/onDestroy 并向桥暴露窄委托；通知权限申请仍留在入口层，下一轮可独立治理。既有事故绊线迁移到新的所有权位置，并修正其函数边界提取，避免把 Activity 包装层误当实现。架构检查禁止 Activity 重新持有后台保活、常亮回调或生命周期事件派发实现。

验证：`:webapp:testDebugUnitTest` 276 项通过；`node scripts/check-architecture.mjs` 与 `git diff --check` 通过。

### Round 13：通知权限状态机退出 Activity

状态：完成，已提交。

闭环内容：新增 `NotificationPermissionHost`，集中封装通知权限真值、页面 `oc:notifyPerm` 同步、首次前台回合主动申请防抖和 API 33 分支。Activity 只保留 Android 结果 launcher 注册与结果转发；页面加载完成仍由组合根触发重推。事故绊线迁移到新的所有权位置，架构检查禁止 Activity 重新持有权限状态或事件构造。

验证：`:webapp:testDebugUnitTest` 276 项通过；`node scripts/check-architecture.mjs` 与 `git diff --check` 通过。

### Round 14：渲染进程崩溃恢复状态机退出 Activity

状态：完成，已提交。

闭环内容：新增 `RenderProcessRecoveryHost`，集中封装渲染进程死亡事件消费、60 秒事故窗口、首崩重建、二崩诊断页收口和恢复顺序；新增无 Android 依赖的 `RenderCrashThrottle` 承载窗口计数，并用真实 JVM 行为测试覆盖首次计数、窗口内递增、边界恰好到期和窗口外重置。`WebHostActivity` 只注入销毁、重建、诊断页三个平台效果，并把 WebViewClient 事件委托给该宿主。删除了原实现中不可达的二次未初始化分支；`disposeWebView()` 与完整 `setupWebViewAndLoad()` 顺序保持不变。架构检查禁止 Activity 重新持有崩溃计数、窗口常量或 `SystemClock` 读数。

验证：`:webapp:testDebugUnitTest` 280 项通过；`node scripts/check-architecture.mjs` 与 `git diff --check` 通过。

### Round 15：WebView 设置与 document-start 脚本策略退出 Activity

状态：完成，已提交。

闭环内容：新增 `WebViewBootstrapHost`，集中封装 WebView 静态设置、facade/性能探针/首屏探针三份 document-start 脚本的资产读取、origin 范围、初始深浅色注入与特性能力分支。`WebHostActivity` 仍负责实例装配与生命周期，只在既有顺序的两个相位调用 `configureSettings` 与 `installDocumentStartScripts`；`LegacyBridgeChannel` 通过惰性 provider 读取，渲染崩溃重建后仍使用当前通道。相关相机/首屏源码绊线迁移到新的所有权位置，架构检查禁止 Activity 重新持有这些实现。

验证：`:webapp:testDebugUnitTest` 280 项通过；`node scripts/check-architecture.mjs` 与 `git diff --check` 通过。

### Round 16：WebViewClient 框架适配退出 Activity

状态：完成，待提交。

闭环内容：新增 `host/HostWebViewClient`，集中承载主框架导航拦截、诊断资产 `WebViewAssetLoader`、系统字体代理、页面开始/加载错误性能标记、主文档错误转发和渲染崩溃事件转发。Activity 不再用 `inner` 类隐式捕获宿主状态，而是在装配点显式注入 `systemFonts`、启动恢复、渲染恢复、外链打开和页面就绪回调。页面就绪后的 insets 补发、通知权限重推和深色日志仍由组合根 `onWebViewPageReady` 统一编排，顺序保持不变。既有绊线迁移为“client 转发 + Activity  wiring”双重断言，架构检查禁止 Activity 重新持有 client 实现。

验证：`:webapp:testDebugUnitTest` 280 项通过；`node scripts/check-architecture.mjs` 与 `git diff --check` 通过。

### Round 17：WebChromeClient 框架适配退出 Activity

状态：完成，待提交。

闭环内容：新增 `host/HostWebChromeClient`，集中承载文件选择 intent 的 MIME 聚合、SAF 结果回灌标准 callback、WebView 权限事件向 `CameraPermissionHost` 的转发，以及页面 console 日志桥接。Activity 不再通过 `inner class` 隐式捕获 `fileChooser` 与相机权限宿主，而是在装配点显式注入依赖；接口面仍是一次性构造的框架适配器，业务语义继续留在专门宿主。相机权限源码绊线迁移到新的所有权位置，架构检查禁止 Activity 重新持有该适配实现。

验证：`:webapp:testDebugUnitTest` 280 项通过；`node scripts/check-architecture.mjs` 与 `git diff --check` 通过。

### Round 18：系统栏主题快照与深浅色兜底退出 Activity

状态：完成，待提交。

闭环内容：新增 `SystemUiThemeHost`，集中拥有页面已上报深浅色快照、相同上报去重、uiMode 变化后的快照失效、insets 重推和系统栏图标明暗兜底。顺带删除从未被读取的 `systemDark` 死状态：主题真值只剩“页面最新上报优先，否则当前 uiMode”这一条规则。Activity 只保留桥接口与配置事件的转发；UX4 绊线迁移为宿主所有权 + 组合根委托双重断言，架构检查禁止 Activity 重新持有该状态机。

验证：`:webapp:testDebugUnitTest` 280 项通过；`node scripts/check-architecture.mjs` 与 `git diff --check` 通过。

### Round 19：WebView 实例视口与 native focus 装配退出 Activity

状态：完成，待提交。

闭环内容：新增 `WebViewViewportFactory`，集中拥有每个 WebView 实例必须重建的视口容器、背景/滚动配置、native focus/IME 前置条件、ACTION_DOWN 焦点补救和首帧绘制标记。`WebViewViewport` 明确暴露“WebView 必须住在 padding 所属容器里”这一结构契约；Activity 只执行 `setContentView(viewport.root)` 并继续编排 bridge/client/insets/加载。 viewport 绊线迁移到工厂所有权，同时保留“不得直接 setContentView(webView)”负断言；架构检查禁止 Activity 重新持有该装配实现。

验证：`:webapp:testDebugUnitTest` 280 项通过；`node scripts/check-architecture.mjs` 与 `git diff --check` 通过。

### Round 20：本地 UI origin 概念正名

状态：完成，待提交。

闭环内容：纠正 UX10 之后遗留的概念漂移：`bootUrl()` 的权威文档、恢复 URL 注释、启动链说明、`launch` 参数与日志字段不再把页面来源误称为“后端地址”；统一为正名后的本地 UI origin。删除 `WebHostActivity` 对 `SecretStore` 的无执行语义 import，并同步修正两处测试文档中已废弃的直连页面来源叙述。恢复 URL 的同源绊线随参数正名迁移，行为不变。

验证：`:webapp:testDebugUnitTest` 280 项通过；`node scripts/check-architecture.mjs` 与 `git diff --check` 通过。

### Round 21：调试注入通道退出 Activity

状态：完成，待提交。

闭环内容：新增 `DebugEvalHost`，集中拥有 `oc_debug_eval` 的 debug/release 门卫、extra 消费、b64/表达式两种脚本形态、首屏 settle 延迟和 logcat 回写。Activity 只做组合根委托；`BuildConfig.DEBUG` 契约绊线迁移到宿主所有权 + 注入值双重断言。

验证：`:webapp:testDebugUnitTest` 280 项通过；`node scripts/check-architecture.mjs` 与 `git diff --check` 通过。

### Round 22：物理 ESC 改写事件构造收归策略模块

状态：完成，待提交。

闭环内容：`WebHostEscapePolicy` 新增 `remappedBackToEscape(event)`，把“判定是否应该改写”和“如何完整复制 KeyEvent 并替换 keyCode”合并为同一权威实现。Activity 不再手工逐字段构造 `KeyEvent`，只在策略返回非空时派发替换事件；导航栏/手势返回仍保持原路。源码绊线迁移为策略所有权 + Activity 委托，架构检查禁止 Activity 重新持有事件构造细节。

验证：`:webapp:testDebugUnitTest` 281 项通过；`node scripts/check-architecture.mjs` 与 `git diff --check` 通过。

### Round 23：通知点击消费退出 Activity

状态：完成，待提交。

闭环内容：新增 `NotificationLaunchHost`，集中拥有通知点击标记消费、session extra 读取、facade settle 400ms 延迟和 `deliverOpenSession` 投递。Activity 只保留 intent 边界转发；通知点击既有纯函数契约之外，新增消费侧所有权绊线，架构检查禁止 Activity 重新持有该流程。

验证：`:webapp:testDebugUnitTest` 281 项通过；`node scripts/check-architecture.mjs` 与 `git diff --check` 通过。

### Round 24：启动导航闸门与恢复 URL 裁决退出 Activity

状态：完成，待提交。

闭环内容：新增 `StartupNavigationHost`，集中拥有本地 UI 服务端口闸门、WebView feature 闸门、恢复 URL 的同 origin 裁决、G1 preflight、看门狗 arm 和最终 loadUrl 顺序。Activity 只保留组合根 `launch()` 委托与恢复快照存储；直连、终端重接和 G1 绊线迁移到导航宿主，架构检查禁止 Activity 重新持有这些策略。

验证：`:webapp:testDebugUnitTest` 281 项通过；`node scripts/check-architecture.mjs` 与 `git diff --check` 通过。

### Round 25：回合电源传感器退出 android-facade 主文件

状态：完成，待提交。

闭环内容：把 UX8 回合电源传感器（fetch 包装、SSE 透明 tee、execution 事件解析、busy 会话集合与 `oc.setKeepScreenOn` 驱动）从 `android-facade.js` 原位置切到 `round-power-sensor.js`；尾部表面色 observer 与外层 IIFE 收口进入 `surface-color-tail.js`。构建脚本按原顺序拼接，重建后的 `oc-host.js` 字节完全一致，不改变任何运行行为。`android-facade.js` 从 1090 行降至 842 行，架构检查同步识别新的权威源码拼件。

验证：`:webapp:testDebugUnitTest` 281 项通过；`node frontend/host-runtime/build.mjs` 后生成资产无 diff；`node scripts/check-architecture.mjs` 与 `git diff --check` 通过。

### Round 26：通知、后台读数与输入方向键 shim 退出 android-facade 主文件

状态：完成，待提交。

闭环内容：按原有执行顺序把三块独立页面侧 shim 从 `android-facade.js` 切出：`notification-shim.js` 拥有浏览器 Notification API 垫片与上游通知策略桥；`background-state-shim.js` 拥有后台 `visibilityState` / `hasFocus` 读数覆盖与 `__ocSetBackground`；`editable-arrow-guard.js` 拥有输入框边界方向键防空间导航。构建拼件顺序与原字节布局一致，重建后的 `oc-host.js` 无 diff；`android-facade.js` 从 842 行降至 598 行。

验证：`:webapp:testDebugUnitTest` 281 项通过；`node frontend/host-runtime/build.mjs` 后生成资产无 diff；`node scripts/check-architecture.mjs` 与 `git diff --check` 通过。

### Round 27：android-facade 核心与六块前置职责分层

状态：完成，待提交。

闭环内容：把剩余 `android-facade.js` 按原字节顺序切成职责明确的权威源码：`facade-bootstrap.js`（facade 启动、native 桥探测、缓存清理与共享状态）、`scheme-bridge.js`（宿主深浅色到 matchMedia）、`safe-area-style.js`（顶部安全区与视口高度 CSS）、`back-overlay.js`（返回键浮层关闭）、`ime-insets.js`（IME CSS 与 insets 应用）、`surface-color.js`（页面表面色解析）。剩余 `android-facade.js` 只保留 171 行 `window.__ocAndroid` facade API 与 shell 标记。生成 `oc-host.js` 字节完全一致；任务池中的 facade 内部职责拆分闭环完成。

验证：`:webapp:testDebugUnitTest` 281 项通过；`node frontend/host-runtime/build.mjs` 后生成资产无 diff；`node scripts/check-architecture.mjs` 与 `git diff --check` 通过。

### Round 28：架构地图与任务池状态校正

状态：完成，待提交。

闭环内容：重写 `docs/architecture/README.md` 的当前权威来源、依赖方向、host-runtime 拼件所有权和剩余偏离，删除“Activity 约 1500 行”“facade 未拆分”等已失效叙述；显式记录这些拼件共享同一 document-start 闭包、拼接顺序是行为契约，避免把分件误读成独立 ES module。任务池同步标记 Activity 深模块提取完成，并把 research 迁移项改为进行中。无生产代码变更。

验证：`node scripts/check-architecture.mjs` 与 `git diff --check` 通过。

### Round 29：跨 Kotlin/JS 桥契约机器可读化

状态：完成，待提交。

闭环内容：新增 `docs/architecture/host-bridge-contract.json`，集中登记 native bridge 注入名、页面 facade 名、`oc:insets`、`oc:notifyPerm`、`__ocSetBackground`、`__ocHostSchemeInitial`、终端字号存储键、通知 intent extras、debug eval extra 与 `__openSessionByRoute`。每项记录 owner、participant、验证 token 与语义说明；`check-architecture` 现在解析该清单并校验 owner/participant 文件仍包含正确契约 token。任务池中的跨 Kotlin/JS 契约集中项完成。没有选择生成常量方案，因为它会要求同时改 Gradle 与 JS 构建链，复杂度高于当前收益；契约清单先提供显式 SSOT 与漂移防线。

验证：`:webapp:testDebugUnitTest` 281 项通过；`node scripts/check-architecture.mjs` 与 `git diff --check` 通过。

### Round 30：上游 UI 生成产物边界机器检查

状态：完成，待提交。

闭环内容：`check-architecture` 新增生成 UI 边界验证：`opencode-build.json` 的 repository/commit/version/bun 必须与 `frontend/android/upstream.json` 完全一致且 platform 为 android；磁盘禁止出现 `_assets` 目录、必须存在 `oa` chunk 目录；`ui/index.html` 必须继续引用逻辑 `/_assets/` URL，且所有引用 chunk 都真实存在于 `ui/oa`。这把“禁止手改”升级为可执行的来源一致性与 asset-map 完整性约束，同时固化 `LocalUiOriginServer` 的逻辑路径/磁盘路径边界。

验证：`node scripts/check-architecture.mjs` 与 `git diff --check` 通过。

### Round 31：删除无调用方的页面侧死契约

状态：完成，待提交。

闭环内容：全仓扫描确认 `window.__ocAndroidShell`、`window.__ocAndroidEscapePassThrough` 与 `oc:ready` 只有生产侧定义/派发、没有任何仓库内消费者；前者还只是已删除 `:app` 时代的兼容标记，后者没有上游或宿主监听者。删除三处死表面与只保护死标记的测试方法，保留真实 ESC 行为判据、dispatch  wiring 与 Back 回调测试。架构检查禁止这些契约在无消费者情况下复活。

验证：`:webapp:testDebugUnitTest` 280 项通过；`node frontend/host-runtime/build.mjs` 重新生成；`node scripts/check-architecture.mjs` 与 `git diff --check` 通过。

### Round 32：验证 harness 纯 helper 归一到共享模块

状态：完成，待提交。

闭环内容：新增 `scripts/verify-android-common.mjs`，把 `nativeBridgeSource`、`seedScript`、`base64url`、`ocHostLoaderSource` 四个无 runner 状态的纯 helper 从 4000 行 terminal release runner 移入唯一权威位置。9 个验证脚本改为直接从 common 模块导入；release runner 自己也从 common 导入，且不再 re-export，避免旧路径继续作为事实来源。架构检查禁止这些 helper 回到 runner 或从 runner 被外部导入。backend/UI server 仍依赖 runner 的 requestLog 与常量，记录为后续独立拆分项。

验证：所有改动脚本 `node --check` 通过；common helper smoke 通过；`node scripts/check-architecture.mjs` 与 `git diff --check` 通过。

### Round 33：打包 UI 静态服务器退出 terminal release runner

状态：完成，待提交。

闭环内容：新增 `scripts/verify-android-ui-server.mjs`，把 content-type、路径归一化、listen/closeServer 与完整 packaged UI origin server 从 terminal release runner 移入共享模块。`startUiServer` 通过显式参数接收 terminal module override 与 request recorder，避免共享库反向读取 runner 报告状态；release runner 注入自己的 `record`/`TERMINAL_MODULE_SERVED`，普通验证脚本使用默认打包资产。6 个消费脚本改为直接导入共享服务器，runner 不再 re-export。架构检查禁止 server/helper 所有权回流。

验证：`node --check` 覆盖所有改动脚本；独立 smoke 启动共享 UI server，确认 `/` SPA fallback 与 `/oc-terminal.js` 均 200 且 recorder 收到正确 via 标记；`node scripts/check-architecture.mjs` 与 `git diff --check` 通过。

### Round 34：刷新当前偏离清单并收口持续治理规则

状态：完成，待提交。

闭环内容：删除当前偏离清单中已经完成的桥契约清单与上游 UI 生成边界两项；把验证 harness 偏离改写为剩余真实问题（mock backend 与场景矩阵仍在 terminal release runner，shared helper/UI server 已归口）；把“每轮新规则必须机器化”从任务池的一次性待办移入治理规则，避免永远不能完成的流程要求伪装成任务。

验证：`node scripts/check-architecture.mjs` 与 `git diff --check` 通过。

### Round 35：mock backend 与 server lifecycle 退出 terminal release runner

状态：完成，待提交。

闭环内容：新增 `scripts/verify-android-backend.mjs` 与 `scripts/verify-android-server-lifecycle.mjs`。完整 mock OpenCode backend（HTTP、SSE、legacy/persistent PTY WebSocket、replay cursor、session/pty 状态）从 terminal release runner 移入 backend 模块；`startBackend({ record })` 只通过显式参数接收 runner 请求记录器。`listen/closeServer` 从 UI server 移到独立 lifecycle 模块，避免 backend 反向依赖 UI 静态服务器。release runner 与 10 个消费脚本全部改为直接从 canonical backend 模块导入；runner 不再 re-export 任何共享基础设施。架构检查禁止 backend/UI/helper/lifecycle 实现回流 runner。任务池中的验证 harness 基础设施收敛项完成；runner 剩余部分主要是终端场景矩阵与页面探针。

验证：所有改动脚本 `node --check` 通过；backend smoke 覆盖 `/api/health`、PTY WebSocket upgrade、socket 注册与 recorder 标记；`VERIFY_TERMINAL_MODE=boot VERIFY_TERMINAL_NO_SHOTS=1` 使用本机 plugin Playwright 路径运行手机/平板 boot，6/6 通过；`node scripts/check-architecture.mjs` 与 `git diff --check` 通过。首次 boot 因 `_upstream` Playwright 不存在而失败，属于环境路径问题；改用仓库现有 `plugins/server-browser` Playwright 后通过，不作为产品回归证据。

### Round 36：host-runtime 拼接顺序单一权威来源

状态：完成，待提交。

闭环内容：新增 `frontend/host-runtime/assembly.json`，把复制文件清单与 document-start facade 拼接顺序从 `build.mjs` / `check-architecture.mjs` 两份硬编码数组中收回。构建脚本和架构检查都读取同一清单；架构检查还校验数组非空、无重复、文件存在，并禁止 build 脚本再次内联片段顺序。`frontend/host-runtime/README.md` 改为指向该清单，不再复制过时片段列表。

验证：重建前后 `webapp/src/main/assets/www/oc-host.js` sha256 均为 `71e91fe509543e655cd700cf52a276868f1da314d79e8e0a217287c23e117bd6`；`node frontend/host-runtime/build.mjs`、`node scripts/check-architecture.mjs` 与 `git diff --check` 通过。

### Round 37：测试源码读取基础设施单一化

状态：完成，待提交。

闭环内容：新增 `webapp/src/test/java/com/xiufeigo/opencodeshell/web/testutil/TestSource.kt`，把 22 个测试类中重复的 `src/read/moduleFile/sourceOf/asset` 文件读取 helper 删除，统一迁移到 `TestSource.read`。该工具保留历史上“从 webapp 工作目录向上找 4 级”的兼容语义，避免测试运行目录差异重新变成隐式知识；注释 stripping、源码扫描等业务语义仍留在各测试类，不合并不同概念。架构检查禁止私有源码读取 helper 回流。

验证：`:webapp:testDebugUnitTest` 通过；`node scripts/check-architecture.mjs` 与 `git diff --check` 通过。

### Round 38：初始化编排退出后台状态 shim

状态：完成，待提交。

闭环内容：`background-state-shim.js` 曾同时持有后台 visibility/focus 读数 shim 与 document-start 初始化编排（安装 IME/top-inset 样式、委托 focusin、补放 lastInsets）。将后者按原字节顺序移入 `facade-init.js`，并把它登记到 `assembly.json` 的同一位置；后台 shim 现在只负责后台状态读数契约。生成 `oc-host.js` sha256 保持 `71e91fe509543e655cd700cf52a276868f1da314d79e8e0a217287c23e117bd6` 不变。

验证：`node frontend/host-runtime/build.mjs`、`node scripts/check-architecture.mjs` 与 `git diff --check` 通过。

### Round 39：测试几何 oracle 退出生产 APK

状态：完成，待提交。

闭环内容：生产源集中曾有两个只被 JVM 测试消费的几何对象：`ImeOcclusion`（且与真正生产使用的 `ImeResize` 混在同一文件）和 `PhoneFormDefect`（注释已承认宿主零引用）。现将生产 resize 策略独立为 `host/ImeResize.kt`，把测试 oracle 移入测试源集并正名为 `ImeOcclusionGeometry` / `PhoneFormGeometry`；测试类继续钉住同一组真机几何判据，但不再把测试专用代码打进生产 APK。`build.gradle.kts` 的显式测试输入同步改指 `ImeResize.kt`。

验证：`node scripts/check-architecture.mjs`、`git diff --check`、`:webapp:testDebugUnitTest` 通过。架构检查现在禁止这些测试 oracle 或旧文件名重新出现在生产 `host/` 源集。

### Round 40：系统栏主题边界退出 InsetsHost

状态：完成，待提交。

闭环内容：`SystemUiThemeHost` 名义上拥有页面主题快照和系统栏兜底策略，实际却把 `WindowInsetsControllerCompat`、状态栏/导航栏颜色与对比度遮罩操作留在 `InsetsHost.applyBarsTheme`，形成浅转发和错误所有权。现将 window 注入 `SystemUiThemeHost`，由它直接应用系统栏主题；`InsetsHost` 删除该框架边界代码，只保留 insets/resize 职责。语义保持不变：页面已上报时用页面主题，未上报或 uiMode 变化后回退系统深浅色。

验证：`node scripts/check-architecture.mjs`、`git diff --check`、`:webapp:testDebugUnitTest` 通过。架构检查现在禁止 `InsetsHost` 重新持有系统栏主题应用边界。

### Round 41：收尾测试源读取 canonical 化

状态：完成，待提交。

闭环内容：上一轮只消灭了私有 `src/read/moduleFile/sourceOf/asset` 包装函数，仍有一批测试直接用 `File("src/main/...").readText()` 读取单个源文件。现将这类单文件读取迁移到 `testutil.TestSource`，覆盖 preflight、ESC/back、后台闸顺序、终端字号、渲染恢复、兜底页、直连边界、IME、内嵌 service worker 与 LocalUiOriginServer 等测试；目录遍历、字节级检查、生成资产存在性/尺寸检查仍保留 `java.io.File`，因为它们不是“读取一个模块相对源文件”的同一语义。

验证：`node scripts/check-architecture.mjs`、`git diff --check`、`:webapp:testDebugUnitTest` 通过。架构检查现在拒绝测试中出现直接的 `File("src/main/...").readText()` 读取模式。

### Round 42：IME 默认模式单一事实

状态：完成，待提交。

闭环内容：`InsetsHost` 的默认模式是 RESIZE，而 `WebHostActivity.imeModeName` 在未初始化兜底时却返回 "vars"，同一业务事实出现两个答案。现把默认值显式建模为 `ImeMode.DEFAULT = RESIZE`，生产 owner 与桥接兜底共同消费它；测试同步从“字面值是 RESIZE”升级为“默认入口是 DEFAULT”。行为不变，但消除了桥提前查询时的错误答案和后续改默认时的双点修改。

验证：`node scripts/check-architecture.mjs`、`git diff --check`、`:webapp:testDebugUnitTest` 通过。

### Round 43：IME 视口 resize 状态机退出 InsetsHost

状态：完成，待提交。

闭环内容：`InsetsHost` 曾同时负责 insets 采集/归一/payload 发布、IME 模式契约、视口 resize 合并窗口、父层 padding、WebView 像素高度、露出层背景色和高度补发。现将共享模式契约移入 `ImeMode.kt`，把 resize 执行状态机移入 `ImeViewportResizeHost`：`committedPadPx/pendingPadPx/commitResizeRunnable`、80ms 合并、首次/收起即时提交、resize target、surface color 与高度变化回调全部归该 owner。`InsetsHost` 从 377 行降至 235 行，只保留 insets 采集、归一、payload 构造/去重和稳定门面；它把同一份归一值显式交给 resize owner，避免 payload 与视口压矮分叉。相关字符串绊线同步迁移到真实 owner，`build.gradle.kts` 显式测试输入加入新文件，防止资产契约假绿。

验证：`node scripts/check-architecture.mjs`、`git diff --check`、`:webapp:testDebugUnitTest` 通过。架构检查现在禁止 `InsetsHost` 重新持有 resize 状态机，并要求共享 `ImeMode` 契约保持独立。

### Round 44：本地 UI 路由策略纯化并行为化

状态：完成，待提交。

闭环内容：`LocalUiOriginServer` 曾把 socket/线程/HTTP 边界与纯路径策略混在同一类：API/SSE 拒答、路径归一化、`/_assets/` → `oa/` 磁盘映射都只是 server 的内部方法，测试主要靠字符串绊线确认实现存在。新增 `LocalUiRoutePolicy` 作为不碰 socket、assets、线程的纯 owner，server 只保留监听、请求读写、assets 命中与响应边界并显式委托；同时折叠 handle/readAsset 之间重复的一层 normalize，让 `readAsset` 成为唯一归一边界。路径穿越、`..` 根逃逸、`/_assets` 映射、`/api`/`/event` 拒答和 `/apix` 不误伤改为直接调用纯 owner 的行为契约测试，替代对应实现字符串断言。

验证：`node scripts/check-architecture.mjs`、`git diff --check`、`:webapp:testDebugUnitTest` 通过。架构检查现在禁止路径策略回流 socket server，并要求 server 委托 `LocalUiRoutePolicy`。

### Round 45：删除回环代理残留的流式拒绝策略

状态：完成，待提交。

闭环内容：`PowerPolicy` 仍保留 `allowStreaming` / `StreamingAction` / `BACKGROUND_STREAM_REFUSE_STATUS` 一整套后台 SSE 拦截策略，但 UX6 已删除回环代理、UX8 起后台断流改由合成 `pagehide/pageshow` 驱动上游 pageLifecycle，宿主不再介入页面流量。检索确认这些 API 没有任何生产消费者，只有测试在测试它们自身，属于被历史方案遗留下来的平行实现。删除死策略与对应测试，保留真实生效的常亮决策；同时更新 ProGuard 风险注释中的枚举清单。架构检查禁止这套回环代理策略重新混入 `PowerPolicy`。

验证：`node scripts/check-architecture.mjs`、`git diff --check`、`:webapp:testDebugUnitTest` 通过。

### Round 46：终端替换模块解析单一事实

状态：完成，待提交。

闭环内容：`VERIFY_TERMINAL_MODULE` 的读取、override 判空和 `path.resolve` 曾同时存在于 terminal release runner 与共享 packaged UI server；runner 还残留 `UI_ROOT` / `BUNDLE_DIR` / `TERMINAL_DIR_SERVED` 等不再消费的常量，注释仍把 xterm 时代的替换槽称为 Ghostty replacement。将 `resolveTerminalModule` 收口到 `verify-android-ui-server.mjs` 并导出，release runner 只消费同一份解析结果用于预检、服务参数与报告元数据；删除死常量并正名注释。架构检查禁止 runner 重新直接读取该环境变量，并扫描验证脚本中的 stale “Ghostty replacement” 术语。

验证：`node --check` 覆盖三个改动脚本；`resolveTerminalModule` 默认/override smoke 与 `/oc-terminal.js` 静态服务 smoke 通过；`node scripts/check-architecture.mjs`、`git diff --check`、`:webapp:testDebugUnitTest` 通过。

### Round 47：收缩 PowerPolicy 到生产真实消费的决策表面

状态：完成，待提交。

闭环内容：`PowerPolicy` 的 `hasKeepScreenOn` 与 `applyKeepScreenOn(flags, ...)` 只被测试消费；生产宿主并不读取或纯位运算重写窗口 flags，而是直接调用 Android `addFlags/clearFlags`。这意味着测试在验证一个生产不存在的位运算抽象，属于测试专用 API 混入生产。删除这两个 API，保留真实生产表面 `FLAG_KEEP_SCREEN_ON_BIT`、`shouldKeepScreenOn`、`mustClearKeepScreenOn`；`PowerPolicyTest` 改为直接断言四种 foreground/roundActive 决策矩阵，`RoundActivityTrackerTest` 改为验证 tracker 输出与该生产决策的一致往返。架构检查禁止这两个测试专用 API 回流。

验证：`node scripts/check-architecture.mjs`、`git diff --check`、`:webapp:testDebugUnitTest` 通过。

### Round 48：常亮 flag 效果机退出后台生命周期编排

状态：完成，待提交。

闭环内容：`BackgroundLifecycleHost` 同时持有前台真值、后台 SSE 保活/收口、合成页面事件，以及 Android window `KEEP_SCREEN_ON` 的具体写 flag、UI 线程 reconciliation、兜底 `postDelayed` 复查和 WebView callback 清理。新增 `KeepScreenOnHost` 作为常亮效果 owner，依赖收窄为 `Window`、`runOnUiThread`、WebView provider、foreground provider 与共享 `RoundActivityTracker`；生命周期宿主只在前台/后台/销毁边界显式委托。行为保持不变：桥仍经 `applyKeepScreenOnState` 进入同一状态机，离开前台仍无条件清 flag，回前台仍会补回 live round 的常亮，超时兜底仍由同一 WebView callback 排程。相关源码接线断言改为区分“生命周期委托边界”和“常亮效果实现”，并把两个被直接读取的源文件补进 Gradle 显式测试输入，避免假绿。

验证：`node scripts/check-architecture.mjs`、`git diff --check`、`:webapp:testDebugUnitTest` 通过。架构检查现在禁止常亮效果机回流 `BackgroundLifecycleHost`，并要求 `KeepScreenOnHost` 保持 Window/runOnUiThread 窄边界、PowerPolicy 门控、幂等 clear、flag 写入与 backstop 排程。

## 待确认事项

暂无需要产品决策的事项。若后续涉及上游补丁策略、存储键迁移或对外插件 RPC 变更，会先记录在这里，不擅自做破坏性迁移。
