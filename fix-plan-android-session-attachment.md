# Android 端 Session 三栏自动展开 + 附件无法上传：根因与修复计划（待审阅）

> 对应上游 pin：`frontend/OPENCODE_APP_VERSION` = `@opencode-ai/app v1.18.31`（`anomalyco/opencode`）。
> 本文档先给根因（已读源码/补丁/壳代码 + vendored 产物交叉验证），再给完整修复计划。要求审阅者逐条质疑根因是否成立、修复是否最小且可验证。

## 1. 问题复述（用户期望）

1. **三栏自动展开**：Android 端任意打开一个 session，就自动呈现“三栏式布局”（聊天 + 更改/上下文 + 终端/文件树等多列）。期望：**不手动打开，就不自动打开其他内容**，新 session 默认单栏。
2. **附件无法上传**：无法添加任何附件；以图片为例，走了添加流程但最终并未上传（可能表现为选完无预览、无 toast，或有预览但发送后后端没收到）。

## 2. 三栏问题根因

### 2.1 官方布局本来就是“宽屏自动多列 + 全局持久化”（确认）

- `packages/app/src/pages/session.tsx:448`：`isDesktop = createMediaQuery("(min-width: 768px)")`。纯视口宽度判定，与平台无关。
- `session.tsx:450-468`：
  - `desktopReviewOpen = isDesktop() && view().reviewPanel.opened()`
  - `terminalOpen = view().terminal.opened()`，`desktopTerminalOpen = isDesktop() && terminalOpen()`
  - `desktopFileTreeOpen = isDesktop() && shouldShowFileTree({visible, opened})`
  - `desktopV2PanelLayout = sessionPanelLayout({review, terminal, files})`，`visible = review||terminal||files`（`session-panel-layout.ts:1-6`）。
- `context/layout.tsx:280-292` 默认值全是关：`terminal.opened=false`，`review.panelOpened=false (DEFAULT_REVIEW_PANEL_OPENED)`，`fileTree.opened=false`。但这些状态是**按 server scope 全局持久化**的（`layout.tsx:270 Persist.serverGlobal(..., "layout", ["layout.v6"])`），不是按 session 隔离。一旦某次手动打开过 review/terminal/fileTree，就会**对同后端所有 session 生效**，新开 session 也会带出来。这就是“任意开一个 session 都三栏”的第一来源。
- 平板横屏 / 大屏手机横屏 CSS 宽度经常 `>=768px`，`isDesktop()=true`，于是持久化的 `opened=true` 直接渲染为桌面多列。手机竖屏（`<768px`）走 `mobileTab` 单列，不会有三栏——与用户在不同设备上表现不一致的现象吻合。

### 2.2 壳补丁把“上下文”变成了持久化的第三路状态（确认，加重因素）

- 上游默认 `sessionViewState.mobileTab: "session"|"changes"`（`session.tsx:116`），补丁 `android-webview-z-ime-session-layout.patch` 扩展为 `"session"|"changes"|"context"`，并新增移动端第三个 Tab（`session-mobile-tab-context`），点击时执行：
  `setStore("mobileTab","context"); reviewPanel.open(...); tabs().open("context"); tabs().setActive("context")`。
- 上游 `helpers.ts:35,62-74 (createSessionTabs)`：
  - `contextOpen = active==="context" || all.includes("context")`
  - `activeTab` 在无文件 tab 时，若 `contextOpen()` 则回退为 `"context"`。即 **`all` 里残留 `"context"` 就会劫持 activeTab**。
- `tabs().all/active` 按 `sessionKey` 持久化在同一 `layout.v6`（`layout.tsx:299 sessionTabs`），`session-context-usage.tsx:80 contextVisible = reviewPanel.opened() && activeTab()==="context"`。于是：头部 context 圆环点过一次 → `reviewPanel.opened=true`（全局）+ `sessionTabs[sessionKey].all` 含 `context`（按 session 持久）→ 重进同 session 自动显示上下文；若再配合宽屏 `isDesktop=true`，就是稳定的多列。
- 补丁里的同步 effect（`tabs().active()/reviewPanel.opened()` → `setStore("mobileTab","context")`）只做单向同步，不会自动 `open()`，但它让“残留 context”在移动端也表现为自动切到上下文 Tab，用户观感同样是“没点就开了”。

### 2.3 终端 dock 的 CSS 改动在窄屏也可能“多出一块”（次要，需验证）

- `android-webview-z-ime-session-layout.patch:325-333` 把聊天列从官方常驻 `shrink-0 h-full flex-1` 改为条件 `h-full shrink-0: isDesktop() || !terminalOpen()`。意图是窄屏终端打开时让出空间，但副作用是：窄屏 + `terminal.opened=true`（全局持久）时，聊天列与 `session-terminal-dock` 同列共存，用户没点终端也会看到终端占位，观感接近“自动多出一栏/一块”。

### 2.4 结论（给审阅者）

- **主因**：官方“768px 即桌面 + 面板 opened 全局持久”在 Android 平板/横屏上被直接继承；`opened=true` 一旦留下就对所有 session 生效。
- **次因（壳引入）**：context 三 Tab + `all.includes("context")` 劫持 `activeTab` + 全局 `reviewPanel.opened`，形成跨会话自动恢复。
- **用户期望与官方默认行为冲突**：官方桌面新 session 也会继承全局 opened；用户要的是 Android 默认单栏、手动才开。因此修复必须是 **Android-only 行为覆盖**，不能改上游默认。

## 3. 附件问题根因（链路拆解，区分确认/待验证）

官方附件链路（`v1.18.31` 源码，已读）：

```
附加按钮/Mod+U → pickAttachmentFiles({picker: platform.openAttachmentPickerDialog, fallback: fileInputRef.click()})
  → <input type=file multiple accept=...> onChange → addAttachments(files)
  → attachmentMime(file) → draftStore.putBlob(file) → ImageAttachmentPart{blob:{id,url}}
  → 发送时 blobDataUrl(blob, mime) = fetch(blob.url)→FileReader.readAsDataURL → data:image/...;base64
  → buildRequestParts images[].url=dataUrl → SDK POST JSON（含大 base64）经 LocalGateway 转发
```

逐段 Android 风险：

### 3.1 选择器段（高度怀疑，待真机验证）

- **A1（确认的代码缺陷）**：`MainActivity.kt:458-476 onShowFileChooser` 直接 `launch(fileChooserParams?.createIntent())`，**未处理多选**。`multiple` 属性对应 `FileChooserParams.getMode()==MODE_OPEN_MULTIPLE`，按 Android 文档需手动 `intent.putExtra(EXTRA_ALLOW_MULTIPLE, true)`。现状多选大概率只回单文件；与“无法添加任何附件”部分吻合（用户可能一次选多个，回调只给一个或行为异常）。
- **A2（高度怀疑）**：两个 `<input type=file>` 都是 `class="hidden"`（`prompt-input.tsx:1183,1569`，Tailwind `display:none`），靠 `fileInputRef.click()` 程序化触发。Android WebView 对 `display:none` input 的程序化点击存在不触发 `onShowFileChooser` / 无用户手势感知的已知坑；且第二个 input 位于 `pointer-events-none` 容器内。需真机 `adb logcat -s OpenCodeShell` + `onShowFileChooser` 打点确认是否根本没进回调。若没进，就是“选完无反应”的主因。
- **A3（确认的健壮性缺口）**：异常分支 `catch → callback=null; return false` 未通知 WebView，且取消/失败无 toast、无日志。用户侧就是静默失败。另 `parseResult` 为 null 时 `onReceiveValue(null)` 写法本身是对的（表示取消），但上层 JS 无任何提示。

### 3.2 本地 blob 段（高度怀疑，静默丢附件的经典位置）

- **B1**：`draft-store.ts:25 blobID` 直接 `crypto.subtle.digest`，无 fallback；`uuid.ts:6` 却有 `isSecureContext=false` 回退。`https://appassets.androidplatform.net` 经 `WebViewAssetLoader` 拦截，理论 `isSecureContext` 应为 true，但**未经真机验证**。若为 false / `crypto.subtle` 不可用，`putBlob` 直接抛异常。
- **B2**：`attachments.ts:46-78 add/addAttachments` 对 `putBlob` 抛异常**无 try/catch**；`onChange` 处 `void addAttachments(...)` 未处理拒绝。抛异常 → 无 toast（`toast=false` 的内层路径）→ 附件静默消失。用户看到的就是“添加了但最终没上传/没预览”。
- **B3**：`platform.draftStore = createBrowserDraftStore()`（`entry.tsx:121`）走 IndexedDB（`draft-store.ts:98`）。WebView 里 `domStorageEnabled=true` 已开（`MainActivity.kt:284`），但 IndexedDB 可用性/配额在部分 OEM WebView 上仍需验证。若 `indexedDB.open` 失败，同样静默丢。

### 3.3 发送段（可能性较低，但需排除大包转发）

- **C1**：图片最终是内联 `dataUrl` 的大 JSON POST（`submit.ts:114-119, build-request-parts.ts:197-205`），无独立上传接口。`LocalGateway.kt:364-371 readBody` 按 `Content-Length` 全量读入内存、`readChunked` 另行处理；`proxyHttp:207-274` 转发并剥掉 `Transfer-Encoding/Origin/Content-Length/Accept-Encoding` 等。单图几 MB 应能过，但**超大图/多图并发**下内存与 30s 写超时（`defaultClient:378-386`）需压测排除。文本聊天正常说明 preflight/CORS/SDK baseUrl 主链路没问题，附件大包是唯一增量。
- **C2**：`allowFileAccess=false + allowContentAccess=true`（`MainActivity.kt:292-294`）对 `content://` 选择结果理论放行，但需确认 WebView 读 `content://` 不需要额外 `takePersistableUriPermission`。同进程一般不需要，但值得在复现时打 `ContentResolver` 读取日志。

### 3.4 一句话结论

- 最可能的两处静默断点：**① 文件选择器根本没把 File 交到 JS（A2/A1）**；**② File 到了但 putBlob/blobDataUrl 抛异常被吞（B1-B3）**。网关大包（C1）排第三。必须按“选择→预览→发送→后端收到”四段分别打点，才能定案，而不是直接改网关。

## 4. 完整修复计划（要求最小、Android-only、可验证）

### 阶段 0：复现与打点（不改行为，只加可观测性，1 天）

- [ ] 0.1 文件选择打点：`MainActivity.onShowFileChooser` 入口/返回值/`getMode()`/`parseResult` 结果数量打 `Log.i(OpenCodeShell)`；JS 侧 `fileInput onChange`（两个 input）、`pickAttachmentFiles fallback`、`addAttachments` 入参 `size/type/name`、`attachmentMime` 结果、`putBlob` 成功/异常、`blobDataUrl` 成功/异常、`submit` 前 `images.length + dataUrl.length` 分别 `console.warn("[OpenCode Android] ...")`（壳已转发该前缀到 logcat，见 `MainActivity.kt:416-428`）。
- [ ] 0.2 布局打点（debug 版临时）：`isDesktop()`（`matchMedia("(min-width:768px)")`）、`params.id`、`reviewPanel.opened/source`、`terminal.opened`、`fileTree.opened/tab`、`tabs.all/active`、`mobileTab` 在 session 打开瞬间快照上报，确认三栏是哪几列（review/terminal/fileTree/context 各自的 opened 组合）。
- [ ] 0.3 用例矩阵：手机竖屏 / 手机横屏 / 平板竖屏 / 平板横屏 × 新 session / 旧 session × 冷启动后首个 session。记录每种的列数与上述快照。附件矩阵：单图 / 多图 / PDF / 文本 × WiFi/HTTPS 后端，记录四段（选到/预览/发送/后端落盘）断在哪。
- [ ] 验收：能用 logcat 明确指出三栏的 opened 组合，以及附件断点（A/B/C 哪段），再进入阶段 1/2。若打点显示与 §2/§3 不符，以实测为准重修本计划。

### 阶段 1：三栏——Android 默认单栏、手动才开（补丁 + 偏好，2-3 天）

原则：**零服务器时不动；有屏宽度的桌面语义在 Android 被显式覆盖；官方默认（DEFAULT_REVIEW_PANEL_OPENED=false 等）不改；只改 Android WebView 分支（`document.documentElement.hasAttribute("data-android-webview")` / `Sg()` 守卫）。**

- [ ] 1.1 新增 Android-only 会话布局偏好（`localStorage`，默认关闭多列自动恢复）：
  - 键如 `opencode.android.session.autoPanels = "off"`（默认 off）。设置入口放在“设置 → 通用”现有 Android-only 偏好旁（参考 `「+」新建会话` 的实现位置），选项：`新会话始终单栏（默认）` / `跟随上次状态（官方行为）`。默认 off 即用户期望。
  - off 时 Android 分支：渲染 session 列的 `desktopReviewOpen/desktopTerminalOpen/desktopFileTreeOpen` 一律按 `false` 计算（即强制移动端单列 + `mobileTabs` 会话/更改/上下文），**但头部手动 toggle（terminal/review/fileTree/context 按钮）仍可单次打开**；手动打开只影响当前 session，不写回全局 `panelOpened/opened`（或写回但下个 session 不继承——二选一，默认选“不写回全局”，实现更小）。
  - on 时恢复现状官方行为（便于回退与对比）。
- [ ] 1.2 切断 context 残留自动劫持（Android 分支）：
  - `tabs.all` 含 `context` 时不再自动视为打开：Android 下 `contextOpen/activeTab/contextVisible/mobileContextOpen(xee)` 的 `all.includes("context")` 条件改为仅 `active==="context"`；或在 Android 下 session 切换/新 session 时若 `mobileTab` 无显式选择则强制 `mobileTab="session"` 且不 `reviewPanel.open()`。
  - 关闭上下文时同时 `tabs().close("context") + reviewPanel.close()`（若 source 是 `context-button` 且无其他 tab），现状关闭逻辑保留并补 `mobileTab="session"`（补丁已有后半段，需补前半段的自动恢复切断）。
- [ ] 1.3 终端 dock 窄屏占位修复：`h-full shrink-0` 条件恢复官方（或仅在用户本次手动打开终端时才让终端 dock 参与布局），避免 `terminal.opened` 全局残留导致窄屏也多出一块。以后续快照为准，若 0.2 证明窄屏无此问题可跳过。
- [ ] 1.4 迁移与回退：已持久化的 `layout.v6` 不做破坏性迁移；off 模式只是渲染层忽略 opened，不删用户数据。切回 on 即恢复官方行为。`sync-opencode-app.sh` 的补丁自检（`session-mobile-tab-context` 标记）同步更新。
- [ ] 验收：矩阵中所有“新开 session”默认单栏（仅聊天 + composer）；点头部终端/review/文件树/context 任一可单次打开；切走再进新 session 仍单栏；切偏好为“跟随上次”则恢复官方继承行为。E2E 断言 `mobileTab==="session"` 且 `desktopV2PanelLayout().visible===false`（Android off 分支）。

### 阶段 2：附件——四段逐个修硬（补丁 + 壳 + 前端容错，2-4 天）

- [ ] 2.1 壳选择器（`MainActivity.kt`）：
  - `onShowFileChooser`：若 `fileChooserParams.getMode()==MODE_OPEN_MULTIPLE` 则 `intent.putExtra(EXTRA_ALLOW_MULTIPLE, true)`；`intent.addFlags(FLAG_GRANT_READ_URI_PERMISSION)`；`createIntent()` 为空时回退 `ACTION_GET_CONTENT + CATEGORY_OPENABLE + type=*/*`（现状已有）并补 `EXTRA_ALLOW_MULTIPLE`。
  - 所有分支（成功/取消/异常/无 Activity）都 `Log` + 失败时 `Toast`（复用 `error_auth_failed` 风格的新增串）+ 保证 WebView 回调恰好一次（成功 `onReceiveValue(uris)`，取消 `onReceiveValue(null)`，异常 `return false` 前把旧 callback 置空并 `onReceiveValue(null)`，避免悬挂）。
  - 若 0.1 证实 `display:none` input 不触发回调，前端配合 2.2；壳侧不做 WebView 内部 DOM hack。
- [ ] 2.2 前端选择 input 可见性（补丁）：
  - 把两个 `fileAttachmentInput` 的 `class="hidden"` 改为 Android 下可见但离屏（如 `absolute size-px opacity-0 pointer-events-none`），保留 `multiple + accept` 与 `onChange` 后 `value=""` 清空逻辑。桌面分支不动。
  - `pickAttachmentFiles` 在 Android 下若 `platform.openAttachmentPickerDialog` 存在（不应存在，web 平台无此字段）也不走，原生 fallback 保持 `fileInputRef.click()`；点击必须在用户手势同步调用栈内（现状 `onSelect: pick` 已满足，不要加异步跳转）。
- [ ] 2.3 blob 容错与可观测（补丁，关键）：
  - `createPromptAttachmentsCore.add` 与 `draft-store.putBlob/blobDataUrl/blobID` 全链路加 try/catch：`crypto.subtle` 不可用 → 回退内存 `createBlobReference`（不经 IndexedDB）；IndexedDB 失败 → 回退内存并 `console.warn`；`attachmentMime` 返回空 → 弹 `pasteUnsupported` toast（现状仅部分路径有）。
  - `addAttachments` 拒绝不再静默：任何异常都转一次 error toast（复用 `common.requestFailed`），并 `console.warn("[OpenCode Android] attachment ...")`。
  - 发送前 `blobDataUrl` 失败：阻断发送并 toast，而不是发一个缺图的包。
- [ ] 2.4 网关大包压测与兜底（若 0.3 定位到 C 段才做全量，否则只做回归）：
  - 用 1MB/5MB/10MB 图片 × 单张/多张打 `POST session prompt`，看 `LocalGateway.readBody/proxyHttp` 日志与后端落盘；若 OOM/超时，改分块转发（避免全量 `ByteArray`）或调大写超时；`DEFAULT_ALLOW_HEADERS` 缺什么补什么（以 preflight 实际 `Access-Control-Request-Headers` 为准，现状已回显请求头，一般无需改）。
  - `allowFileAccess=false` 保持（安全），`allowContentAccess=true` 保持；若证实 `content://` 读取需持久权限，再补 `takePersistableUriPermission`。
- [ ] 验收：矩阵中单图/多图/PDF/文本均做到“选到→预览→发送→后端消息 parts 含对应 file part（含 dataUrl/文件名）”；取消选择无悬挂、无静默失败（失败必 toast+logcat）；多选在支持的系统上一次多文件全进 composer。现有 `android-proxy` 单测 + 新增 `files/attachment` 单测（mime 回退、putBlob 异常回退）全过。

### 阶段 3：回归与发布（0.5-1 天）

- [ ] `OPENCODE_REF=v1.18.31 ./scripts/sync-opencode-app.sh` 重打补丁 + `vite build`，确认补丁全部按序应用（含 `session-mobile-tab-context` 自检）。
- [ ] 现有 E2E（自述 67 PASS 档）全过；新增：三栏默认单栏断言、context 不自动恢复断言、附件单/多选与大包断言。
- [ ] 发 debug APK，备注 sha256 与 filebin 链接（沿用 README 既有发布格式），注明需覆盖安装/卸载重装条件。

## 5. 改动面预估（给审阅者核对是否最小）

- 壳：`app/src/main/java/com/xiufeigo/opencodeshell/MainActivity.kt`（仅 `onShowFileChooser` 分支 + 日志/toast）。
- 补丁（`frontend/patches/`，需同步更新 `sync-opencode-app.sh` 自检标记）：
  - 三栏：`android-webview-z-ime-session-layout.patch`（主）+ `android-webview-home.css` 相关段（次）。
  - 附件：`prompt-input.tsx` input 可见性、`files.ts/attachments.ts/draft-store.ts/submit.ts` 容错与日志（可拆分为 `android-webview-z-attachment.patch` 新补丁，避免污染现有补丁）。
- 不碰：上游默认 opened 值、桌面分支、网关主链路（除非压测证实 C 段）。

## 6. 请审阅者重点质疑

1. §2.1 “768px + 全局持久”是否足以解释“任意 session 自动三栏”？有没有遗漏的 `openReviewPanel()` 自动调用点（如评论/文件 tab 打开、command-palette、handoff）？
2. §2.2 把 `all.includes("context")` 视为劫持是否准确？上游 `activeTab` 这样写是有意的（桌面端保持 context 可见），Android 改为仅看 `active` 是否会破坏桌面/平板手动打开 context 的保持？
3. 阶段 1.1 “渲染层忽略 opened，不删数据”是否足够？还是必须把 `review.panelOpened/terminal.opened` 改为按 session 隔离？后者改动更大，是否有必要？
4. §3.1 A2（`display:none` input）在目标 WebView 版本上是否真的不触发？是否应先只加打点、不直接改可见性？
5. §3.2 回退到内存 blob 是否会丢草稿持久性？`draftStore.encode/decode` 对内存 URL 的处理是否需要同步改？
6. 网关全量读 body（`readFully`）在 10MB+ base64 下是否有 OOM 风险？是否值得现在就改流式转发，还是等压测数据？
