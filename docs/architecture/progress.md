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

## 待确认事项

暂无需要产品决策的事项。若后续涉及上游补丁策略、存储键迁移或对外插件 RPC 变更，会先记录在这里，不擅自做破坏性迁移。
