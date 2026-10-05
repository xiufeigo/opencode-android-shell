# UX8 — 宿主侧重接：回合常亮（D1）+ 后台断 SSE（P6 刀 2）

状态：**已闭环**（两条行为补回、设备双档验证、三份产物重建、交接文档已同步）
开始：2026-10-05 23:23（Asia/Shanghai）· 收尾：2026-10-06 00:1x

## 任务
1. 回合进行中保持屏幕常亮；回合结束/切后台立刻释放（必须证明 flag 真被置起 + 回合结束后按时释放的时间读数）。
2. 切后台断开 SSE，回前台重建（不再依赖代理，宿主侧接线）。
红线：不许重新引入任何凭据处理；不许加回自定义页/灰页。

## 0. 步骤日志（关键节点）

- 读规格（BUG_QUEUE.md 步骤 2 全节）+ UX6 报告（两条能力回退的如实记录）+ 全部相关宿主源码。
- 上游调研（§1）→ 对真后端 `127.0.0.1:4095` 线上抓包（`research/web/ux8/wire_event.txt`）→ 发现关键事实：**真后端整轮没有 `session.status` 帧**，改用线上验证过的 `session.execution.*`。
- 实现（§2）→ 语法检查 → gradle 锁 → 单测（绿）→ **反向验证三处撤回真红** → 恢复全绿。
- 重建三份产物 → 起自建模拟器 2 台（6670 手机 / 6672 平板）→ 手机档主验证 → 平板档回归。
- 设备验证中发现并修正：注入层补 `round start/end` 取证日志 → 重建重装（手机）。
- 最终再全量重建三份产物 → 设备端 base.apk md5 与交付包**逐位一致**。

## 1. 上游调研（行号证据）

### 1.1 SSE 事件流与"回合状态"事件
- 事件流 = `GET /api/event`（SSE）：`_upstream/packages/client/src/promise/generated/client.ts:1637-1639` `{ method: "GET", path: `/api/event`, ... }`；`:354` 校验 `text/event-stream` 后用 reader 读流。
- **上游自己判定"会话在跑"用的是 `session.execution.*` 事件**（不是 session.status）：
  `_upstream/packages/client/src/solid/data.ts:1009-1011`
  `case "session.execution.started": setSessionActive(event.data.sessionID, "running")`；
  `:1025-1028` `session.execution.succeeded/failed/interrupted` → `setSessionActive(..., "idle")`。
- ⚠️ 对真后端实测抓包（`research/web/ux8/wire_event.txt`）：一次完整回合的事件序列为
  `session.inbox.enqueued → session.execution.started → session.step.* → session.execution.succeeded`，
  **全程没有 `session.status` / `session.idle` 帧**。
  ⇒ `RoundActivityTracker` 旧标记（`"session.status"` + `"type":"idle"`，依据
  `session-status-event.ts:8-32`）**在当前部署永远不会命中** —— 若直接复用旧标记，"回合结束释放"会再次落空（"释放只做一半"的回归）。本轮主判据改用 `session.execution.*`；旧标记保留为兜底（部分后端可能发）。
- 发消息请求形状：`_upstream/packages/client/src/promise/generated/client.ts:684-688`
  `POST /api/session/{id}/prompt`；乐观 busy：`_upstream/packages/app/src/composer/submit.ts:100-103`。

### 1.2 上游既有"页面生命周期"机制（后台断流复用的就是它）
- `_upstream/packages/client/src/solid/connection.ts:219-240`：
  `pagehide → stop()`（`:202-209`，`stream?.abort()`，**真正掐断 /api/event**）；
  `pageshow → start()`（重连）；`visibilitychange` 只在回前台且流静默 >20s 时 resync（`:224-228`）。
- App 侧开着：`packages/app/src/runtime/server/client.tsx:90-93` `createClientConnection(..., { pageLifecycle: true, ... })`。
- **但该机制在 Android WebView 里永远不会被触发**：切后台时 WebView 不发 `pagehide`
  （connection.ts:222-223 注释自己承认"切应用只 hide document、没有 pagehide"）。
  ⇒ **机制在位、扳机缺失**。本轮宿主补扳机：onPause 合成派发 pagehide、onResume 派发 pageshow。
- 合成事件波及面（全 `_upstream` 检索 pagehide/pageshow 监听者）：
  `packages/app/src/shell/state/layout.tsx:363`（pagehide → flush 布局状态落盘）与
  `packages/app/src/runtime/persistence/persist.ts:20`（pagehide → flushPersisted）——**都是落盘 flush，无害且有益**；其余命中在 desktop 包（web 不加载）与 connection.ts 自身。
  `stop()/start()` 均幂等（connection.ts:185-206），即便 WebView 将来原生补发也不会双连。

### 1.3 上游有无"常亮/wakeLock"机制
- 全仓检索 `wakeLock|keepScreenOn|keepAwake`：`packages/app/src` **零命中**。
  ⇒ 回合常亮**没有**上游机制可复用 ⇒ 按规格在注入层自建传感器（§2）。

## 2. 实现路径与理由

**选型：注入层（oc-host.js）做"传感器" + 宿主生命周期做"扳机"，全部复用既有宿主策略层
（`RoundActivityTracker` / `PowerPolicy` / `applyKeepScreenOn`），没有重建代理、没有新 flag 写入口。**

- **为什么回合状态走注入层而不走宿主生命周期回调**：回合状态只有页面知道（上游不发 Android 生命周期事件）；宿主看不见流量（代理已删）。注入层以 `addDocumentStartJavaScript` 注册，**早于上游脚本**拿到 `window.fetch`，用透明 tee 观测 `/api/event` 字节流 —— 等价于旧代理的"协议解码"能力，但零网络中介、零凭据接触（fetch 原样透传，不读不写 header）。
- **为什么后台断流走 pagehide/pageshow 而不是自己包 EventSource/shouldInterceptRequest**：
  上游 client 已有完整的 stop()/start() 生命周期管理（断线重连、resync、事件重放边界都处理了），
  自己包一层只会与上游状态机打架。宿主唯一要做的是把缺失的扳机补上 —— **这是"复用上游既有机制"的最短路径**。

### 改动清单
- `webapp/src/main/assets/www/oc-host.js`：新增 `installRoundPowerSensor()`（+`installRoundPowerSensor();` 调用）：
  - 包装 `window.fetch`：POST `/api/session/{id}/prompt|message|message_async` ⇒ `setBusy("__post__", true)`（乐观置位，与上游 submit.ts 同思路）；
  - `/api/event` 响应体套透明 `TransformStream`（`Object.defineProperty(resp,"body")` 换成 pipeThrough 后的流 + 包 `getReader.cancel` 记 CLOSE），按行解析 `data: {json}` 帧（`leftover` 缓冲防读切半行 + `TextDecoder{stream:true}` 防多字节切断）：
    `session.execution.started` ⇒ busy+；`succeeded/failed/interrupted` ⇒ busy−；`session.status busy/idle`、`session.idle` 兜底；
  - busy 按 sessionID 计数（多会话：任一会话在跑 ⇒ 常亮）；变化时调既有桥 `oc.setKeepScreenOn(anyBusy)`；
  - 取证日志 `[oc-pow] round POST / round start / round end / keepScreenOn -> / event stream OPEN|CLOSE #n`（console.log → logcat）。
- `WebHostActivity.kt`：onPause 末尾 `dispatchPageLifecycleEvent("pagehide")`（在 webView.onPause() 之前）；onResume 里 webView.onResume() 后 `dispatchPageLifecycleEvent("pageshow")`；新增 `private fun dispatchPageLifecycleEvent(name)`（evaluateJs `window.dispatchEvent(new Event("..."))`，纯事件名、零凭据）。
- 注释更新（行为不变）：`WebHostBridge.kt` / `RoundActivityTracker.kt` / `WebHostActivity.kt`（applyKeepScreenOn）/`PowerPolicy.kt` / `PowerWiringTest.kt` 头部（后台断流"回退"表述改为"UX8 已恢复，经上游 pageLifecycle"，`suspendStreaming()` 反向断言保留）。

### 已知边界（如实记录）
- 后台期间回合结束的事件帧会丢失（SSE 已断）⇒ 靠宿主 `RoundActivityTracker` 既有 90s 空闲兜底 + 30min 硬上限收口（与旧代理时代同一取舍）。快速切后台（<90s）回前台时若回合仍在跑，flag 由 onResume 重算恢复。
- PTY WebSocket（`/api/pty/*`）不在本轮范围（规格只说 SSE；终端重连是上游 reattach 语义，另行评估）。

## 3. 绊线与反向验证

- 新增 `Ux8KeepAwakeSseTripwireTest`（13 用例）：钉死 ① 传感器必须被调用、② 必须调 `oc.setKeepScreenOn(`、③ 必须认线上实测的四个 `session.execution.*` 标记、④ 必须拦截 prompt POST、⑤ 必须 tee `/api/event`、⑥ 必须按 sessionID 计 busy、⑦ onPause 派 pagehide、⑧ onResume 派 pageshow、⑨ 派发真的是 `window.dispatchEvent(new Event(`、⑩ 生命周期派发不得含 Authorization/password、⑪ 反向形状存在性。
- **反向验证（撤回必须真红，已实跑）**：三处同时撤回——
  A. 撤 `installRoundPowerSensor();` 调用、B. 撤 onPause 的 pagehide 派发、C. 撤 `session.execution.failed` 标记——
  `BUILD FAILED`，**恰好 3 条真红**：`sensorIsInstalledFromDocumentStartScript` / `onPauseDispatchesPagehide` / `sensorKeysOnWireVerifiedRoundEndMarkers`，其余 10 条保持绿。随后恢复，全量 **6320 用例 0 失败**。
- 既有断言一条未删未放宽（`PowerWiringTest` 仅改注释；`KeepScreenOnWiringTest` 未动并全绿）。

## 4. 设备证据（真实点击 vs 程序注入均已标注）

设备：手机档 = **OC_UX6_Phone / emulator-6670**（1200×2670 覆盖，WebView 124.0.6367.219）；
平板档 = **OC_Deliver_R2 / emulator-6672**（2560×1600）。装的都是交付包 debug 版
（**设备端 `base.apk` md5 `e54752db94e2528d54f0774b8f7b9646` = 交付包 md5，MATCH=True**）。

### 4.1 手机档（主）

**Round A（数数字，6s）+ Round B（800 词故事，61.6s）——flag 置起与按时释放：**
- 真实点击：搜索框、会话行、发送按钮（input tap）；程序注入：`input text` 文本、
  `settings put system screen_off_timeout`、KEYCODE_WAKEUP/HOME、am start、logcat 读取。
- **flag 真被置起（三证）**：
  ① logcat 置起记录：15:49:45.494（A）/ 15:53:25.505（B）
     `D1 applyKeepScreenOn: roundActive=true foreground=true shouldKeep=true **flagSet=true**`
     （A 前置：15:49:45.235 `[oc-pow] keepScreenOn -> true busy=[__post__]` + `round POST /api/session/.../prompt`）；
  ② **物理常亮**：B 是 61.6s 长回合，screen_off_timeout=15s/20s/30s 三档均**越过多个超时点**，
     t+18s（>15s 超时）`mWakefulness=Awake` + 截图 `ux8_t2_hold_past_timeout.png`（故事流式输出中，屏幕实亮）；
     t+43s 仍 Awake。A 后 15:50:21 熄屏（`event stream CLOSE #5` = 断流证据，见 4.2）⇒ 无回合时屏幕正常睡。
- **回合结束后按时释放（时间读数，设备本地时戳）**：
  - A：round end `session.execution.succeeded` 15:49:51.407 → `keepScreenOn -> false` .420 → `flagSet=false` .423
    ⇒ **释放 Δ = 13–16 ms**。
  - B：round end 15:54:27.252 → `keepScreenOn -> false` .256 → `flagSet=false` .269
    ⇒ **释放 Δ = 4–17 ms**。
  - 释放后屏幕确实能睡：release+22s（超时 15s）`mWakefulness=Dozing`、截图为黑
    （`ux8_t3_after_release.png`）⇒ **不是"只做一半"，无 4 分钟钉亮**。
  - 截图：`ux8_r3_roundA_result.png`（回复 1..80）、`ux8_t2_hold_past_timeout.png`（长回合越超时常亮）、
    `ux8_w1_story_reply.png`（完整故事 + SSE-OK）。

**后台断 SSE / 回前台重建（三组配对读数）：**
- HOME（注入 keyevent 3）⇒ `P6 dispatch synthetic pagehide` ⇒ `[oc-pow] event stream CLOSE #n cancel`：
  - 15:56:05.524 → .552（**28 ms**，后台时焦点=NexusLauncher，`dumpsys window mCurrentFocus` 实证在后台）
  - 16:00:03.868 → .944（**76 ms**）
  - 16:03:11.567 → .673（**106 ms**）
- am start 回前台（注入）⇒ `P6 dispatch synthetic pageshow` ⇒ `event stream OPEN #n+1 /api/event`：
  - 15:56:49.143 → .388（**245 ms**）
  - 16:00:34.727 → .963（**236 ms**）
  - 16:03:33.345 → .437（**92 ms**）
- **回前台后会话仍正常**：SSE-OK 回复与 200 词海洋故事完整可见
  （`ux8_v1_reply_after_cycle.png` / `ux8_w1_story_reply.png`），时间线、Thought、token 信息齐全。

### 4.2 平板档（回归，emulator-6672）
- 常亮：16:07:01.460 `flagSet=true`（真实点击发送）→ 16:07:14.126 回合结束同毫秒释放
  （`keepScreenOn -> false busy=[]`）。后台：16:07:34.350 pagehide → .429 CLOSE #1（**79 ms**）；
  回前台：16:07:47.701 pageshow → .856 OPEN #2（**155 ms**）。
- 回复 "TABLET-UX8-OK" 可见（`ux8_tb4_reply.png`）；右侧栏 / 终端 / Git changes 布局完好（未被本轮改坏）。
- 另：手机档 15:50:21 / 15:56:15 / 16:01:53 多次"熄屏 → CLOSE"自然配对也旁证了断流路径（屏幕灭 = Activity 暂停 = pagehide 触发）。

## 5. 红线自检

- **凭据**：新代码（传感器块 + dispatchPageLifecycleEvent）逐字扫描
  `Authorization|password|ServerSocket|authToken|proxy` **0 命中**；fetch 包装原样透传入参/响应，不构造任何 header；
  合成事件只带事件名字符串。`DirectConnectAuthBoundaryTest`（10 条）全绿，未动。
- **自定义页/灰页**：未新增任何 HTML/路由/灰页组件；改动仅 oc-host.js 传感器 + Activity 生命周期两行 + 注释。
- 未删/未放宽任何既有测试断言。

## 6. 交付物（research/deliver/，UX8 批，当前唯一有效）

| 产物 | 体积 | md5 | 签名 |
|---|---|---|---|
| `opencode-shell-web.apk`（debug） | 5,437,979 B | `e54752db94e2528d54f0774b8f7b9646` | CN=Android Debug，SHA-256 `e86b7006…0510a` |
| `opencode-shell-web-release-nor8.apk` | 3,549,507 B | `45ed3e896a9779debdc374ca5672fc94` | 同上 |
| `opencode-shell-web-release-r8.apk` | 1,268,829 B | `e146e547ce359259b456c1a336802519` | 同上 |

- **包内含源自查**：三份 APK 内 `assets/www/oc-host.js` md5 = **`b5057679b1a419af15d81ef72ff0fd9e`**（55,953 B）
  = 源文件 `webapp/src/main/assets/www/oc-host.js` 的 md5，**3/3 MATCH=True**（System.IO.Compression 解包实测）。
- **设备端一致性**：手机档设备上 `base.apk` md5 = `e54752db…` = 交付 debug 包 md5（MATCH=True）⇒ 设备证据取自交付字节体本身。

## 7. 回归用例数

`.\gradlew.bat --console=plain :webapp:testDebugUnitTest :app:testDebugUnitTest` ⇒ **BUILD SUCCESSFUL，
合计 6320 用例，0 失败**（webapp 184 / app 6136；较 UX7 批 +13 = 新增 `Ux8KeepAwakeSseTripwireTest`，只增不减）。
反向验证期间 3 条真红、恢复后全绿（§3）。

## 8. 我已同步交接文档

**HANDOFF_WEB_SHELL.md 已同步**：§0 顶部新增 UX8 状态条；§0.1 顶表三行换成 UX8 体积/md5 并标 UX7 旧值作废；
新增「UX8 亲手重跑 Get-FileHash」复核块（含包内含源自查 b5057679… 与 UX8 改动清单）并标 UX7 块为历史记录；
§5 未闭环表新增 ★ 行（"D1/P6 随删代理失效"正式改为"已补回"）。

## 9. 收尾复核（2026-10-06 00:14 实跑抄录）

- `adb -s emulator-6670 emu kill` / `adb -s emulator-6672 emu kill` ⇒ 均执行；收尾 `adb devices` **不含 6670/6672**
  （剩 emulator-5614/5620/5622/5704/5706/5710/5712/5742，全部**非本轮启动**、未碰）。
- 后端：`Invoke-WebRequest 127.0.0.1:4095/api/info` ⇒ **HTTP 200**（全程未起停）。
- gradle 锁：顶层 `rm -- .agent-gradle-lock` ⇒ `lock released: True`。
- 自建残留 0：`adb devices | Select-String '6670|6672'` 无输出；端口 6670/6672 Listen 无输出。
- 碰过的 serial：**emulator-6670、emulator-6672（均自建 OC_*，已关）**；
  **未碰过任何非自建实例**（DSH_*/T*/MCRemote 全程零接触；`adb kill-server`/通配符杀进程未使用）。

## 10. 未闭环

- 真机耗电 mAh 数字（全仓一直没有，需产品负责人跑 `battery_real_device.ps1`，HANDOFF §5①）。
- 后台期间回合结束的事件帧丢失 ⇒ 依赖宿主 90s/30min 兜底（§2 已知边界，属既有取舍）。
- PTY WebSocket 后台不断开（规格范围外，已说明）。
- 冷启动未配置时官方内置无凭据服务器 401 停在 Loading（UX6 遗留，官方设置里补密码即恢复）。
- `-Pwebapp.r8=true` 现为 no-op（F11 遗留，见 HANDOFF §5）。
