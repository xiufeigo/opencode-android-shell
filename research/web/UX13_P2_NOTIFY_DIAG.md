# UX13-P2：通知链路定位诊断（只读，不改代码）

- 日期：2026-10-06
- 轮次：UX13-P2（接 UX13-P1 步骤 3 的红项：**一条通知都没发**）
- 包：`research/deliver/opencode-shell-web.apk`（debug），包名 `com.xiufeigo.opencodeshell.web.debug`
- 后端：`127.0.0.1:4095`（opencode/opencode，v2.0.24），**不许起停**
- AVD：`Medium_Phone_API_37.0`，端口 6790
- SDK：`C:\Users\xiufeigo\AppData\Local\Android\Sdk`

## 本轮方法学（只读约束）

- 页面状态**只靠只读打点读取**（包装已有构造函数 / 读既有属性），**不改变任何运行时行为**：
  - `EventSource` 包装：只记录 URL 与实例引用，**转发构造、不改 readyState 语义**
  - `Notification` 包装：只记录调用参数，**照常 `new` 原构造**
  - `__ocAndroid.postMessage` 包装：只记录参数，**照常转发原实现**
- 不新增 EventSource / Notification 实例（避免制造假回合假通知）
- 不 `requestPermission()`、不 `pm grant`（除环境前置缺失外）、不改宿主文件
- 真实点击 / 真实按键完成所有 UI 路径

## 判定框架（四选一）

| 判定 | 特征 | 断点位置 |
|---|---|---|
| **A** | `notifCalls` 为空 | 上游闸门（web.ts 策略：`"Notification" in window` / 权限 / `hasFocus()` / `hasOpenTab`）或 SSE 未建立导致回合完成帧没到 coordinator |
| **B** | `notifCalls` 有、`pmCalls` 空 | shim 内部异常（`oc-host.js:482-551`） |
| **C** | `pmCalls` 有、`dumpsys` 无 | 宿主 `NotifierHost` 侧问题 |
| **D** | `dumpsys` 有记录但不可见 | 系统侧拦截（渠道 importance / POST_NOTIFICATIONS / 用户免打扰） |

---

## 步骤 0：起机装包 ✅

| 项 | 结果 |
|---|---|
| 6790 端口 | 启动前 **无占用**（`Get-NetTCPConnection -LocalPort 6790` 空） |
| emulator | `Start-Process` 脱离启动，pid **40324** |
| `sys.boot_completed` | `1`；`ro.build.version.sdk` = **37** |
| `adb devices` | `emulator-6790 device` |
| `adb reverse tcp:4095 tcp:4095` | 成功 |
| `install -r -t` | `Success` |
| `pm clear` | `Success`（清掉上轮残留，保证干净起点） |
| `POST_NOTIFICATIONS` | **`granted=true`**（P1 发现首装未授权，本轮按环境前置补授，否则 C/D 无法区分） |
| 通知渠道 | `NotificationChannel{mId='oc_turns', mImportance=3}` **已存在**；`AppSettings importance=DEFAULT userSet=false` |
| `am start` | `topResumedActivity=WebHostActivity t12` |

> **关键前置结论**：C（宿主不发）与 D（系统挡）这两类的**环境前提全部满足**
> —— 权限已授、渠道 importance=3、非 NONE。所以本轮若仍无通知，
> 断点必在 **A/B**，或宿主 postMessage→notify 环节（本轮归 C）。

## 步骤 1：连服务器 + 切上下文 + 进会话 ✅

全部真实点击，无注入：

1. Home 抽屉 (67,120) → `Settings` (179,2324)
2. `Preferences` 下拉 (868,216) → 选 `Server` (768,988)
3. `+ Add server` (906,482) → 对话框
4. `Server address` 真实键入 `http://127.0.0.1:4095`
5. `Password` 真实键入 `opencode`
6. 提交 `Add server` (924,1651)，等 16s
7. `Back to app` (192,216) → Home
8. 项目行 chevron (992,262) → 服务器列表 → 点 4095 行 (312,467)
9. 进会话 `v2 smoke test` (248,598)

| 验证点 | 证据 |
|---|---|
| 4095 已添加 | 服务器列表 `127.0.0.1:4095` 前为**绿点**（4173 为红点，绿/红对照同 P1） |
| 上下文已切 | Home 副标题 `All projects / 127.0.0.1:4095`，搜索框 `Search sessions in 127.0.0.1:4095` |
| 已进会话 | 会话页渲染历史多轮 `reply the single word OK` → `Used 1 Thought` → `OK`，composer 可见 |

截图：`_p2_04_adddlg.png` / `_p2_05_filled.png` / `_p2_09_chevron.png` / `_p2_11_session.png`

**顺带发现（与通知策略直接相关）**：Preferences 下拉里存在 `Notifications` 分区，
即上游 `notification.tsx` 的用户开关（sound / notification）。
按 shim 注释 `notification.tsx:228,229,256,257 → sound/notification user switches`，
**这两个用户开关是上游策略的一部分，可能默认关闭**。
本轮先用只读打点定位，不去改它（改了就污染"上游闸门"的判定基线）。

## 步骤 2：后台前注入只读打点 ✅

打点全部在** pristine 页面**上安装（中途做过一次 `location.reload()`，原因见下方「打点自纠」）：

| # | 打点 | 安装结果 |
|---|---|---|
| 1 | `EventSource` 包装 | `"wrapped es=function"` |
| 2 | `Notification` 包装 | `captured:"ShimNotification"`, `permProp:"default"`, `hasReq:"function"` |
| 3 | `__ocAndroid.postMessage` 包装 | `{"wrapped":true,...}` |
| 4 | 基线读 | 见下 |

**基线（打点装好、还在前台时）**

```json
{"vis":"visible","focus":true,"notifPerm":1,"permProp":"default",
 "hasNotif":true,"shim":"ShimNotification",
 "path":"/server/aHR0cDovLzEyNy4wLjAuMTo0MDk1/session/ses_ef43fab08ffeoHiNxtinjW9V0c"}
```

- `notifPerm: 1` → 宿主侧 Android `POST_NOTIFICATIONS` **已授权**
- `permProp: "default"` → **shim 的 `permission` 是写死的 `"default"`**（`oc-host.js:537`），
  不会随 Android 授权状态变化
- `shim: "ShimNotification"` → shim 确实装上了（WebView 本来没有 Notification）
- `path` 里的 `aHR0cDovLzEyNy4wLjAuMTo0MDk1` = base64(`http://127.0.0.1:4095`) → 当前路由确实指向 4095

> ### 打点自纠（重要，影响结论可信度）
> 第一版 `Notification` 包装写成了 `window.Notification=function(t,o){...}`，
> **把 shim 上的静态属性 `permission` / `requestPermission` 弄丢了**（回读得到 `permProp:"undefined"`）。
> 这会污染上游权限闸门的判定，所以做了两件事修正：
> 1. 重新包装时**显式透传** `W.permission=N.permission` 与 `requestPermission` 委托
> 2. 为拿到**未被污染的原始 shim**，`location.reload()` 了一次，reload 后确认
>    `name="ShimNotification"`、`permission="default"` 才装的点
> reload 顺带解决了一个观测盲区（见步骤 4 的 `esLog` 说明）。

## 步骤 3：触发场景（真实操作）✅

1. 真实点 composer (540,2172) → 真实 `input text`（首版只进去 "reply"，被输入法联想栏吃掉；
   改为 `%s` 转义空格后为 `Reply the single word OK`）
2. 真实点发送 (972,1404)
3. **发送后 600ms 内** `KEYCODE_HOME` → 16:58:47，`topResumedActivity=NexusLauncher`
4. 后台轮询 t+3s…t+27s 共 9 次：每轮本包通知命中数恒为 **1**（就是 `AppSettings` 那行，不是通知）
5. t+42s 回前台

**回合确实完成**（截图 `_p2_14_reply.png`）：

```
Reply the single word OK   Build · Fledge Alpha Free · 8:58 AM
Used 1 Thought
OK                          Build · Fledge Alpha Free · 5s
```

## 步骤 4：回读打点 ✅

```json
{"esLog":[],"esStates":[],"notifCalls":[],"pmCalls":[]}
```

回前台后追加读（第二个回合之后）：

```json
{"notifCalls":[],"pmCalls":[],"notifStore":"{\"list\":[]}",
 "vis":"visible","focus":true,"perm":"default","hostPerm":1}
```

`dumpsys notification` 全量：`Notification List` 里**只有 systemui 的一条 DSK 通知**；
`com.xiufeigo` 全文件仅 1 次命中（`AppSettings` 行）、`oc_turns` 仅 1 次命中（渠道定义本身）。
**没有任何本包通知记录。**

### `esLog` 为空 ≠ SSE 没建立（观测盲区澄清）

`esLog` 空是因为：页面那个 EventSource 在打点装好**之前**就建好了。
logcat 里 `[oc-pow] event stream OPEN #70…#124` 持续每 ~1 秒递增，
这是**宿主代理层**在反复重开上游流，页面侧的 EventSource 并没有随之重建，
所以包装器没被再次调用。SSE 实际是通的，见下节证据。

## 步骤 5：断点判定 —— **A（上游没调 Notification）**，且比 web.ts 闸门更早一层

### 5.1 逐环排除

| 环节 | 观测值 | 结论 |
|---|---|---|
| 上游 `new Notification()` | `notifCalls: []` | ❌ **断在这里** |
| shim → `oc.postMessage` | `pmCalls: []` | 未到达（B 不成立） |
| 宿主 `NotifierHost` | `dumpsys` 无记录 | 未到达（C 不成立） |
| 系统可见性 | 无记录可判 | D 不成立 |

### 5.2 上游策略代码（从 bundle 里读出来的真实闸门）

`ui/oa/index-D2r3WEwY.js`（平台 `web.ts` 等价物）：

```js
async notify(e,t,n){
  if( !(`Notification` in window)
     || (Notification.permission==="default"
           ? await Notification.requestPermission().catch(()=>`denied`)
           : Notification.permission) !== `granted`
     || (document.visibilityState===`visible` && document.hasFocus()) )
    return;
  let r = new Notification(e,{body:t??``, icon:...});   // ← 第一次真正 new
  ...
}
```

这三道闸门在本宿主里**本来都应该放行**：

1. `"Notification" in window` → shim 装上了，`true` ✅
2. 权限闸门 → shim 的 `permission="default"` 会走 `requestPermission()`，
   shim 内部 `oc.hasNotificationPermission()===1` → `Promise.resolve("granted")` → `===` `"granted"` ✅
3. 焦点闸门 → 后台时 `visibilityState!=="visible"` 且 `hasFocus()===false` → `&&` 为假 ✅

### 5.3 真正的断点：`platform.notify` 根本没人调

`.notify(` 在主 bundle 里 **0 处**。唯一真实调用点在
`ui/oa/runtime-DVG-cmAP.js`：

```js
x = (a,o,s) => { y(a).then(c=>{
  if (m.disposed || !c || c.parentID) return;
  let l = as(i.store,e.key,a);                       // ① hasOpenTab
  l && n.sounds.agentEnabled() && e.coordinator.sound(...);
  v({...,type:`turn-complete`,session:a});            // ② 无条件写通知 store
  l && n.notifications.agent() && e.coordinator.system(..., () =>
      t.notify(...))                                  // ③ 才轮到 platform.notify
})}

E = e.sdk.event.listen(e=>{
  if (e.type!==`session.execution.succeeded` && e.type!==`session.execution.failed`) return;
  ... x(e.data.sessionID, e.id, t)
})
```

**通知链路只挂在 `session.execution.succeeded` / `session.execution.failed` 这两个终止事件上。**

而 logcat 里实测（第一回合）：

```
08:58:45.458  [oc-pow] round POST /api/session/ses_ef43.../prompt
08:58:45.562  [oc-pow] round start session.execution.started
```

**`session.execution.succeeded` 一条都没有**（全文件 grep `succeeded` 只命中 2 条，
分别来自 Google `SP` 与 `Auth`，与本包无关；`OcWebHost.*succeeded` 命中数 = **0**）。

**第二个回合完全复现**：

```
09:02:42.131  [oc-pow] round POST /api/session/ses_ef43.../prompt
09:02:42.280  [oc-pow] round start session.execution.started
OcWebHost.*succeeded 命中数 = 0        （期间 event stream OPEN 27 次）
```

**旁证（决定性）**：通知 store 至今为空
`opencode.global.dat:notification = {"list":[]}`。
代码里 `v({type:'turn-complete',...})`（第 ② 步，**不受任何开关约束、无条件执行**）
一次都没往里写过东西 ⇒ **不是被开关拦的，是 `x()` 整个函数压根没跑**。

### 5.4 为什么界面能出回复、通知却没有

回合的**消息流事件**（`session.message.updated` 之类）走的是同一条 SSE，
它们**到达了**页面，所以 UI 正常渲染出 `OK`；
但**终止事件 `session.execution.succeeded` 没有到达**，
于是只有"渲染"没有"收尾通知"。这解释了 P1 观察到的
"助手回复 OK，但一条通知都没发"。

### 5.5 SSE 到底连到哪里

| 项 | 实测 |
|---|---|
| 页面 origin | `http://127.0.0.1:4173` |
| 事件流 URL | **`http://127.0.0.1:4173/api/event`**（logcat `OcPerfJS res ... http://127.0.0.1:4173/api/event`） |
| 业务 API | `http://127.0.0.1:4095/api/info`、`.../api/session/ses_.../model`（**直连 4095**，成功） |

**SSE 不是直连 4095，而是走 4173 页面 origin，由宿主 `oc-pow` 代理转发。**
这不是根因（流是通的，消息事件能收到），但**是终止事件丢失的最可疑环节**：

- `[oc-pow] event stream OPEN #N` **每 ~1 秒递增一次**（第一回合窗口 55 次，第二回合 27 次），
  即代理层在**持续把 SSE 连接拆了重开**
- 每回合的 `session.execution.started` 能过，**收尾的 `...succeeded` 过不来**，
  与"流在回合收尾瞬间被重连打断、终止事件被丢弃"的现象一致

## 步骤 6：收尾 ✅

| 复核项 | 结果 |
|---|---|
| `adb emu kill` | `OK: killing emulator, bye bye` |
| 25s 后 emulator/qemu 进程 | **no emulator processes** |
| 端口 6790 | **NO LISTENER — 已释放**（仅剩 `TimeWait`，OwningProcess=0） |
| `adb devices` | **空**（无任何 serial） |
| `adb reverse --remove tcp:4095` | 已执行；`reverse --list` 回显为空 |
| 后端 4095 OwningProcess | **15096 → 15096（未变）** |
| 后端 `/api/info` | **HTTP 200** `{"version":"2.0.24","pid":15096,...}` |
| 未 kill adb server | ✅ 全程只用 `adb emu kill` |
| **未改代码** | ✅ `git status --porcelain -- webapp app` **输出为空** |
| 未碰 gradle | ✅ |

**碰过的 serial（自始至终只有一个）**

```
List of devices attached
emulator-6790	device
```

---

# 总表

| 环节 | 观测 | 是否通过 |
|---|---|---|
| 上游策略调用 `new Notification()` | `notifCalls: []` | ❌ **断点** |
| shim → `oc.postMessage(title,body,sid)` | `pmCalls: []` | 未到达 |
| 宿主 `NotifierHost` → Android 通知 | `dumpsys notification` 无本包记录 | 未到达 |
| 系统可见性 | 无记录可判 | — |
| 环境前置（权限/渠道） | `granted=true`、`oc_turns` importance=3 | ✅ 已排除 |
| SSE 连通性 | `session.execution.started` 到达 | ✅ 通 |
| SSE 终止事件 | **`session.execution.succeeded` 从未到达**（2/2 回合复现） | ❌ **根因** |
| 通知 store | `opencode.global.dat:notification = {"list":[]}` | ❌ 从未被写入 |

# 结论

**断点 = A（上游没调 Notification）。**

但比任务书假设的"上游闸门（`hasFocus` / `hasOpenTab` / 权限）"还要更早一层：
**不是闸门挡的，是触发通知的那个函数压根没被执行到。**

上游把通知链路**只**挂在 `session.execution.succeeded` / `session.execution.failed`
两个终止事件上（`runtime-DVG-cmAP.js`）。实测这两个事件在本宿主里
**一次都没有到达页面**（`session.execution.started` 到了，`succeeded` 没有，2/2 回合复现），
于是 `x()` 没跑 → 无条件写通知 store 的那行没执行
（store 至今 `{"list":[]}`）→ `platform.notify` 没被调用
→ shim 没被触发 → `oc.postMessage` 没调 → 宿主没发通知。

**web.ts 那三道闸门（`"Notification" in window` / 权限 / 焦点）在本宿主其实都会放行**，
不是本轮的责任方；shim 本身工作正常（`ShimNotification` 已装载、
`hasNotificationPermission()===1` 会让 `requestPermission()` resolve `"granted"`）。

**SSE 连的是 `http://127.0.0.1:4173/api/event`（页面 origin，宿主 `oc-pow` 代理转发），
不是直连 4095**（业务 API `/api/info`、`/api/session/.../model` 才是直连 4095 且成功）。
流本身是通的，但 `[oc-pow] event stream OPEN #N` **每 ~1 秒递增一次**
（窗口内 55 次 / 27 次），即代理层在持续把 SSE 拆了重开。
**终止事件丢失最可疑就在这个重连风暴**——起始事件能挤进来，收尾事件被重连打断丢掉，
这正好解释了"界面能显示回复、却没有通知"。

# 下轮建议（不在本轮范围）

1. **查宿主 `oc-pow` SSE 代理**：为什么 `/api/event` 每秒重开一次？
   这是终止事件丢失的最可疑单点，优先级最高。
2. 在代理层**记录转发的事件类型名**（目前只记了 `round start`），
   以便直接证明 `succeeded` 是"上游没发"还是"代理没转发/丢了"。
3. 备用兜底：即使上游终止事件不可靠，`oc-host.js` 也不必死等
   `platform.notify`——但这属于改设计，需先确认 1/2 的结论再定。

# 产物清单

- 本报告：`research/web/UX13_P2_NOTIFY_DIAG.md`
- 截图：`_p2_00_home` / `_p2_04_adddlg` / `_p2_05_filled` / `_p2_09_chevron` /
  `_p2_10_ctx4095` / `_p2_11_session` / `_p2_12_typed` / `_p2_13_typed2` /
  `_p2_14_reply` / `_p2_15_typed3`（均在 `research/web/`）
- logcat：`_p2_logcat.txt`（回合 1，含 `round start` 无 `succeeded`）、
  `_p2_logcat2.txt`（回合 2，复现）
- 辅助脚本：`_p2_eval.ps1`（注入通道）、`_p2_emu.pid`（emulator pid）

