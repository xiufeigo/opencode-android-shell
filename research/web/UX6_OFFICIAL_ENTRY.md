# UX6 — 官方入口直连（官方 UI 承担配置与认证）+ 箭头焦点 bug

状态：进行中（研究阶段）

## 1.1 官方「服务器」条目是否支持凭据 —— 结论：**支持**

证据（`_upstream`，branch v2 @ 702a73d）：

1. **官方「添加服务器」对话框有密码字段**
   - `packages/app/src/servers/connect/dialog.tsx:149-161`：密码输入框
     `<TextInput type="password" ... value={form.state.password()} placeholder={...passwordPlaceholder} .../>`
   - `dialog.tsx:110-124`：URL 字段；`:136-148`：名称（displayName）字段。
   - `dialog.tsx:261`：`const password = store.values.password || undefined`
   - `dialog.tsx:272-279`：连接对象 `{ type: "http", displayName: name, http: { url: normalized, password } }`
2. **密码如何发送：HTTP Basic，用户名固定 `opencode`**
   - `packages/app/src/runtime/server/api.ts:5-7`：`authTokenFromCredentials = btoa("opencode:" + password)`
   - `api.ts:26-32`：`headers: { Authorization: "Basic ..." }`（有密码才带）
   - `packages/app/src/runtime/server/health.ts:81-85`：健康检查同样带 Basic header
   - 测试佐证：`runtime/server/health.test.ts:16-29`（"authenticates using only the password"）
3. **未配置服务器时的官方首屏 = 官方自己的 ConnectServerScreen（含 URL+密码表单）**
   - `packages/app/src/shell/routes/routes.tsx:79`：`<Show when={servers.list.length > 0} fallback={<ConnectServerScreen />}>`
   - `packages/app/src/servers/connect/screen.tsx:90-128`：URL 字段 + 密码字段（`type="password"`, `autocomplete="current-password"`）
   - 即：**未配置时的真实表现是官方自己的连接页，不是我们造灰页**。
4. **官方前端由后端提供（SPA 回落）**
   - `packages/cli/src/services/web-ui.ts:12-24`：API 路由未命中 → `serveUI`
   - `web-ui.ts:28-33`：资源未命中 → 回落 `index.html`（SPA fallback）
   - `packages/cli/src/app-assets.ts:10-18`：嵌入的 app 资源（brotli+base64）或本地 `../../app/dist`

⇒ 决策点 2 成立：认证可全交给官方 UI，我们的口令弹框/凭据注入可彻底删除。

## 1.2 核实：直接加载真实服务器 ⇒ 官方 UI 正常（SPA 由后端提供）

- `packages/cli/src/services/web-ui.ts:12-24,27-52`：API 未命中 → `serveUI`；任意路径回落 `index.html`（:32-33）⇒ 深链/刷新可用。
- CSP：`web-ui.ts:58-60` `script-src 'self' …` —— 宿主注入的文档开始脚本**不受页面 CSP 约束**（既有结论，oc-host.js:600-608 注释有实测）。
- 明文：`network_security_config.xml` 已放行 `127.0.0.1` 明文 ⇒ `http://127.0.0.1:4095` 直连可用（adb reverse 照旧）。
- 未配置时官方真实表现 = `ConnectServerScreen`（routes.tsx:79 fallback；screen.tsx:90-128 有 URL+密码表单）⇒ 与我们的灰页彻底不同。
- 箭头 bug 根因（设备实测复现，基线包）：
  - 输入框（composer 是单行 `<input>`）聚焦、光标在行尾时，再按 →（KEYCODE_DPAD_RIGHT，注入）⇒ activeElement 从 INPUT 变为 BUTTON（logcat 逐键读数交替：奇数键 BUTTON / 偶数键 INPUT，光标停 10 不动）。
  - 机制 = Android WebView 的空间导航：光标无法右移时按键落给 focus search。上游键位派发器在 editable 目标里直接 return（command.tsx:410），无关。
  - 截图：ux6_before_arrow_focus_stolen.png（焦点高亮停在 Toggle review 按钮）。

## 1.2 实施（已完成）

### 改动清单
删除（7 个生产文件）：
- `LoopbackProxy.kt`（901 行回环代理）、`StableOrigin.kt`（钉端口）、`BackendProbe.kt`（F5 探测）、
  `LaunchGuard.kt`（F21 闸门）、`NotConfiguredShell.kt`（未配置灰页）、`ConnectErrorPage.kt`（代理错误页）、
  `SetupDialogMetrics.kt`（配置对话框度量）。
- `webapp/build.gradle.kts`：移除 okhttp 依赖（无生产使用方）。
- `AndroidManifest.xml`：INTERNET 权限注释更新（不再为 ServerSocket）。

重写：
- `WebHostActivity.kt`：启动链 = `bootUrl()`（SecretStore[KEY_BACKEND_URL] ?: `http://127.0.0.1:4095`）
  → G1 前置检查 → `loadUrl(后端地址)`。删除：startProxyAndLoad/probeThenLaunch/showSetupDialog/
  showConnectErrorDialog/showNoPasswordBlockedPage/showNotConfiguredShell/openSetupFromBlockedPage/
  ocshell:// 私有 scheme、预热、P2/P3 perf extras。保留：G1 看门狗、insets、返回处理、文件桥、
  相机权限（可信 origin = 后端地址）、通知路由、D1 常亮（跟踪器改由 Activity 持有，驱动方只剩页面桥）。
- `WebHostBridge.kt`：删 proxyOriginProvider/openSetup；proxyOrigin/proxyPrefix 恒空串（打了补丁的上游
  前端要求两者都非空才改写 ⇒ 恒空 = 不改写 = 直连）；setKeepScreenOn 喂 Activity 持有的跟踪器。
- `SecretStore.kt`：删 KEY_SERVER_PASSWORD 与 HOST_ONLY_KEYS/isHostOnly；只剩 KEY_BACKEND_URL（非秘密）。
- `PowerPolicy.kt` / `RoundActivityTracker.kt`：仅注释更新（代理引用改为历史形态说明）。

### F21 在新形态下的表述（不许默默丢掉）
> 旧：**口令为空 ⇒ 绝不启动代理、绝不以无认证方式使用后端**（LaunchGuard 闸门）。
> 新：**宿主不持有、不注入、不转发任何口令；宿主不存在"替用户完成认证决策"的代码路径。**
> 保障者 = 结构（代理整个删除，注入点不存在）+ 官方 UI 与后端的协商
> （官方 ConnectServerScreen/「设置→服务器」密码字段，Basic `opencode:<password>` 直发后端）。
> 钉死它的绊线 = `DirectConnectAuthBoundaryTest`（10 条：无代理/无 Authorization/无 ServerSocket/
> 无口令键/启动直连/bootUrl 口径 + 反向样本验证）。

### 如实汇报的能力回退（产品决策的代价）
- **D1 回合常亮**：代理删除后宿主看不见流量，驱动方只剩页面桥（上游 UI 目前不调）⇒ 功能"页面可用、
  待上游接线"，实际处于不生效状态。
- **P6 省电刀 2（后台掐 SSE）**：随代理删除；后台行为回归为 WebView `onPause` 暂停 JS 的浏览器默认语义。
- 冷启动未配置时：官方 UI 的内置无凭据服务器会 401（实测页面 /api/info = 401，会话区 Loading）——
  这是**当前部署的打补丁前端**自动注册 origin 的行为；用户在官方「设置 → Server」里补密码后即绿。

## 2 箭头 bug（已修复）

### 复现（修复前，基线包，emulator-6610 / OC_Deliver_R2）
- composer 光标在行尾时按 →（KEYCODE_DPAD_RIGHT，`input keyevent 22` 注入）：
  logcat 逐键 eval 读数：activeElement 奇数键=BUTTON / 偶数键=INPUT（光标停在 10 不再移动）——
  焦点在输入框与相邻按钮之间被 WebView 空间导航来回切换；8 连按后稳定 BUTTON。
- 截图 ux6_before_arrow_focus_stolen.png：焦点高亮落在「Toggle review」按钮（工具提示可见）。
- 根因：Android WebView 空间导航。光标无法右移时按键未被编辑器消费 ⇒ 落给 focus search。
  上游 command.tsx:410 对 editable 目标直接 return，与此无关（已排除）。

### 修复
- oc-host.js 新增 `onEditableArrowGuard`（文档捕获 keydown，全文件唯一逐键监听、P5 显式豁免）：
  - 单行 input/textarea：选区折叠且光标在首/尾 ⇒ preventDefault（不 stopPropagation）。
  - contenteditable：用 Selection 折叠位置 + 光标前字符数判定首/尾 ⇒ preventDefault。
  - 跳过 .xterm（终端按键汇）、IME 组合、一切带修饰键组合。
  - ⚠ 实施中发现当前服务的 composer 形态是 contenteditable（修复前首测时曾是 input——
    同一场景修复前复现为 BUTTON|x8，contenteditable 形态同样被抢焦点），守卫已覆盖两种形态。
- 绊线：`ComposerArrowGuardTripwireTest`（11 条）+ `InputPathHotspotTest` 豁免窄化（3 条新断言）。

### 修复后验证（新包，同机）
- 同场景 8×→：activeElement 稳定 `DIV|true`（contenteditable），焦点未跳出。
- ← 在框内仍正常移动光标（caret 10→5，5 次←）。
- 上游键位未坏：合成 ctrl+alt+t（注入）⇒ Terminal 1 面板打开（真实 PTY）。
- 反向验证（撤回注册行 ⇒ 5 条绊线真红，含 P5 主绊线）：已实跑确认。

### 证据标注
- 真实点击：进入设置、打开 Server 菜单、点 Add server 保存等（坐标换算后 input tap）。
- 程序注入：文本输入（input text）、方向键（input keyevent）、JS eval/click（oc_debug_eval）、
  合成键位事件。逐条已在上方标明。

## 设备证据清单（research/web/ux6/）

任务 1（平板 = OC_Deliver_R2 / emulator-6610）：
- `ux6_before_coldstart_gray.png` — 修复前冷启动 = 我们的灰页（历史对照，已删功能）
- `ux6_after_coldstart_official.png` / `ux6_after_coldstart_official2.png` — **冷启动未配置的真实表现**：
  官方 Home + 会话区 "Loading"（页面侧 /api/info = 401，已 eval 实测）——不是我们的灰页
- `ux6_after_official_settings.png` → `ux6_after_official_server_tab6.png` — 官方 设置 → Server 页（红点）
- `ux6_after_add_server_dialog.png` — **官方 Add server 对话框**（Server address / Server name / Password）
- `ux6_after_server_added.png` — 官方服务器行 **绿点**（健康）
- `ux6_after_relaunch_sessions.png` — 重启动后会话列表完整加载

任务 1（手机 = OC_UX6_Phone / emulator-6612，1080×2400）：
- `ux6_phone_coldstart.png` — 冷启动未配置（官方 Loading，无灰页/对话框）
- `ux6_phone_dropdown_open.png` → `ux6_phone_server_tab2.png` — 官方设置 → Server
- `ux6_phone_added3.png` — 官方 Add server 表单（已填地址+密码）→ `ux6_phone_submit2.png` 绿点
- `ux6_phone_sessions.png` — 重启动后会话列表

任务 2（箭头）：
- 修复前（基线包）：logcat 逐键读数 奇数键 BUTTON/偶数键 INPUT 交替（上文 §2）+
  `ux6_before_arrow_focus_stolen.png`（焦点高亮停在 "Toggle review" 按钮）
- 修复后：`ux6_after_arrow_fixed.png`（平板，8×→ 全程 activeElement=DIV|true）+
  `ux6_phone_arrow_fixed.png`（手机，6×→ 全程 DIV|true）
- 上游键位未坏：`ux6_after_terminal_keybind.png`（合成 ctrl+alt+t ⇒ Terminal 1 真实 PTY 打开）

真实 vs 注入标注：真实点击 = 灰页按钮、设置导航、Server 菜单、Add server 提交等 input tap；
程序注入 = input text（文本）、input keyevent（方向键/DPAD）、oc_debug_eval（JS eval/click/合成事件）。

## 交付物（research/deliver/，UX6 批）

| 产物 | 体积 | md5 | 签名 |
|---|---|---|---|
| opencode-shell-web.apk（debug） | 5,437,979 B | fba7db16c87e0dab3f4bab53d5433490 | CN=Android Debug（SHA-256 e86b7006…0510a） |
| opencode-shell-web-release-nor8.apk | 3,545,375 B | 525b68470b01438a04bc5e0d026c9534 | 同上 |
| opencode-shell-web-release-r8.apk | 1,265,237 B | a854824f3c4a0de3f1ea38ccea00d6dc | 同上 |

包内含源自查：三份 APK 内 assets/www/oc-host.js md5 = 784e5d27ac896251918a0ed3169ecd08，
与 webapp/src/main/assets/www/oc-host.js 源文件逐位一致（3/3 MATCH=True）。
**HANDOFF_WEB_SHELL.md 已同步**（§0.1 顶表 + UX6 复核块 + §2 架构段改写 + INTERNET 权限描述；旧 md5 显式作废）。

## 回归用例数（:webapp:testDebugUnitTest + :app:testDebugUnitTest ⇒ 0 失败）

- 基线：webapp 218 / app 6136。现况：**webapp 166 / app 6136，0 失败**。
- 删测试逐条理由（全是"被测功能被产品决策删除"，非放宽断言）：
  - EmptyPasswordLaunchGateTest 16 条 → F21 闸门（LaunchGuard）删除，由 DirectConnectAuthBoundaryTest（10 条，含反向验证）取代；
  - NotConfiguredShellTest 16 条 → 未配置灰页删除（产品决策：一切交给官方 UI）；
  - SetupDialogTouchTargetTest 8 条 → 启动配置对话框删除；
  - ConnectRobustnessTest 19 条 → BackendProbe/ConnectErrorPage 删除（官方 UI 自管健康检查与报错）；
  - TerminalReattachTest 11→3 → StableOrigin 类删除；origin 稳定性改由结构（bootUrl 无随机成分）+ 3 条结构断言守护；
  - KeepScreenOnWiringTest 删 4 条代理接线断言、增 2 条桥/跟踪器断言（代理删除所致，如实记录 D1 回退）；
  - PowerWiringTest 删 7 条 suspendStreaming/代理断言、增 1 条反向断言（后台断流能力随代理删除）。
- 新增：DirectConnectAuthBoundaryTest 10、ComposerArrowGuardTripwireTest 11、InputPathHotspotTest +2（豁免窄化）、
  TerminalReattach 结构断言、KeepScreenOn 新桥断言 —— 新逻辑（直连边界、箭头守卫）均被覆盖。

## 收尾复核

- 自建模拟器已关：emulator-6610（OC_Deliver_R2）、emulator-6612（OC_UX6_Phone）⇒ `emu kill` 均 "OK"，收尾 `adb devices`
  只剩 emulator-5630（非自建，未碰）。未碰过任何 DSH_*/T*/MCRemote 实例。
- 后端 127.0.0.1:4095 全程未起停；adb reverse tcp:4095 是 Android 侧访问宿主后端的既有前提（与代理无关）。
- 碰过的 serial：emulator-6610、emulator-6612（均自建，已关）。
- 未闭环：① D1 回合常亮/后台断流随代理删除而失效（页面可用、待上游接线）；② 冷启动未配置时官方内置无凭据
  服务器会 401 停在 Loading（当前部署前端的自动注册行为；用户在官方设置里补密码后即恢复）；③ ArrowUp/Down
  在 textarea 软折行边界未做吞咽（刻意收窄范围，防误伤上游搜索导航）；④ contenteditable 的 Up/Down 同理。
