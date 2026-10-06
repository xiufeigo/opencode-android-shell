# UX13-P6 通知根因二分诊断（只读，未改代码）

- 执行时间：2026-10-06 18:03 ~ 18:45（CST）
- 仓库：`D:\XiaoZhuTouTou\opencode-android-shell`
- 后端：`127.0.0.1:4095`（opencode/opencode，v2.0.24），**全程未起停**
- AVD：`Medium_Phone_API_37.0`，端口 **6790**，serial `emulator-6790`
- 被测包：`research/deliver/opencode-shell-web.apk`（P4 最终批）
- 纪律：只读。真实点击 + 真实按键；注入仅「记录 + 透传」；未改任何代码、未碰 gradle、未 kill-server。

---

# 判定摘要

| # | 问题 | 结论 |
| --- | --- | --- |
| **A1** | 前台 `succeeded` 帧到不到？ | ✅ **到**。`round end session.execution.succeeded` @10:10:04.485，start→end **Δ=2.133s**。**前台链路健康** |
| **B1** | 4173 全局流是谁、carrying 什么？ | 上游 `[global-sdk]` 事件流，base URL 落到 `location.origin`(4173)，被 `LocalUiOriginServer` **404 + Connection: close** 打掉，固定 1000ms 重连 ⇒ ~1 次/秒空转。**但它不是断帧元凶** |
| **C1** | 后台断流的确切机制？ | **宿主 `onPause` 合成 `pagehide`，上游 `pageLifecycle` 监听器随即 `abort()` 掐断在途 SSE**。实测 pagehide 在 HOME 后 **+109ms** 派发，后端在 abort 后 **2.07s** 才完成回合 ⇒ 终帧必丢 |
| **D1** | `POST_NOTIFICATIONS` 谁触发？ | **上游 notify 闸门**（只挂在 `session.execution.succeeded` 上）。终帧不到 ⇒ 闸门从不评估 ⇒ shim 的 `requestPermission()` 从不触发 ⇒ Android 从不弹窗。**死锁** |

## 一句话根因

**前台完全正常；通知断链的唯一原因是宿主在 `onPause` 主动合成 `pagehide`、上游据此 abort 了事件流，把「回合结束帧」掐在了断点上；而首次 `POST_NOTIFICATIONS` 申请又只由这个结束帧触发，于是形成自锁。**

> ⚠️ **本轮修正 P5 的核心结论**：P5 判定「4095 流被 1s 重连风暴截断 / 前台也拿不到 succeeded」——**不成立**。本轮实测前台 `succeeded` 正常到达（2.13s），~1/s 的 4173 空转风暴**不是**丢帧原因。真正的单一断点是**合成 pagehide**。

---

## 步骤 0：起机 / 装包 / 首屏注入 ✅

| 项 | 结果 |
| --- | --- |
| 6790 端口 | 启动前**无占用**，无残留 qemu/emulator 进程 |
| emulator pid | 34848（emulator.exe）/ 41296（qemu-system-x86_64） |
| ⚠️ 早启动竞态 | `sys.boot_completed=1` @第 2 次轮询（~6s），但此刻 **`package` / `activity` 服务尚未注册**：`install` 直接返回 `cmd: Can't find service: package`。**真正就绪判据 = `pm list packages` 能返回 `package:`** |
| ⚠️ 组件串 | `am start -n com.xiufeigo.opencodeshell.web/.WebHostActivity` → `Error type 3`。**类在 `...opencodeshell.web`，但 applicationId 是 `...opencodeshell.web.debug`**，必须写 `<applicationId>/<真实类全名>` |
| reverse | `host-15 tcp:4095 tcp:4095` |
| install / clear | `Success` / `Success` |
| `POST_NOTIFICATIONS` | **`granted=false`**（全新安装基线） |
| 启动 | `topResumedActivity=com.xiufeigo.opencodeshell.web.debug/...WebHostActivity t14` |

**被测包基线**

| 项 | 值 |
| --- | --- |
| SHA256 | `ACBC520A0BE08779697ED92E42CF98BD796A494848DE639AF51AAA81CDC7AA67`（与 P5 记录**逐位一致**） |
| 大小 | 16,821,985 bytes / mtime 2026-10-06 17:36:14 |

**首屏注入**（`fetch` + `EventSource` 双包装，只读观测）：

```json
{"ok":1,"href":"http://127.0.0.1:4173/","path":"/"}
```

> **打点纪律**：包装只做「记录 + 透传」（`of.apply(this,arguments)`），不新增 EventSource / Notification 实例、不调 `requestPermission`、不 `pm grant`。
> ⚠️ 进会话后 SPA 重挂载会清掉打点，需在会话内**重新注入**（已如此）。

## 步骤 1：连服务器 + 切上下文 + 进会话 ✅

全部真实点击：抽屉 (67,120) → Settings (179,2324) → Preferences 下拉 (870,216) → Server (772,986) → `+ Add server` (906,482) → 键入 `http://127.0.0.1:4095` / `opencode` → 提交 (924,1651) → 等 16s → Back (192,216) → 项目 chevron (994,263) → 4095 行 (312,468) → 会话 `v2 smoke test` (250,620)。

| 验证点 | 证据 |
| --- | --- |
| 4095 已添加 | 服务器列表 `127.0.0.1:4095` **绿点** |
| 上下文已切 | Home 副标题 `All projects / 127.0.0.1:4095` |
| 已进会话 | 路由 `path=/server/aHR0cDovLzEyNy4wLjAuMTo0MDk1/session/ses_ef43fab08ffeoHiNxtinjW9V0c`（base64 解出 = `http://127.0.0.1:4095`） |

> **踩坑备忘**：首次发送时 `input tap 972 2286` 落在**软键盘**上（未发送，消息被输了两遍）⇒ `round start` 计数为 0，**差点误判成「前台也不来帧」**。发送按钮在**键盘弹起态**为 **(972,1405)**。教训：先截图确认发送态，再读 logcat。

---

## 问题 A：前台回合 —— `succeeded` 到底到不到？ ✅

**全程不切后台**，发送 `reply with the single word OK`，等 26s。

### A.1 logcat 原文（清空后仅本回合）

```
10-06 10:10:02.352  3537  3537 I OcWebHost: web[LOG] [oc-pow] round start session.execution.started @715
10-06 10:10:04.485  3537  3537 I OcWebHost: web[LOG] [oc-pow] round end   session.execution.succeeded @715
```

| 计数 | 值 |
| --- | --- |
| `round start` | **1** |
| `round end` | **1** |
| `succeeded` | **1** |
| `event stream OPEN` | 33（~1s 周期，见问题 B） |
| logcat 总行数 | 800 |

### A.2 后端侧对照（只读 REST）

```json
18:10:03.240  user        text='reply with the single word OK'
18:10:03.437  assistant   finish=stop  text='OK'
18:10:05.455  idle        outcome=succeeded
```

### A.3 判定 **A1**

✅ **`round end session.execution.succeeded` 出现了。前台链路健康，问题纯在后台断流。**

- `start→end` = **2.133s**；后端 `assistant` 完成于 18:10:03.437，client `round end` 于 10:10:04.485（device 与 backend 时钟差恒为 **+8h**，即同一时刻）⇒ 帧是**实时**送达的，没有排队/重放。
- ⚠️ 注意 device logcat 用的是 **UTC**（10:10:xx），backend 用**本地 +08**（18:10:xx），二者**差 8 小时**，比对时必须换算。

---

## 问题 B：4173 全局流是什么、carrying 什么 🔍

### B.1 logcat 原文（~1s 周期，三行一组）

```
10-06 10:10:00.646  I OcWebHost: web[LOG] [oc-pow] event stream OPEN #294 /api/event @715
10-06 10:10:00.646  I OcWebHost: web[LOG] [global-sdk] event stream disconnected [object Object] @2
10-06 10:10:00.647  I OcWebHost: web[LOG] OcPerfJS res #634 api start=295736 dur=29 enc=9 dec=9 xfer=309 http://127.0.0.1:4173/api/event @19
10-06 10:10:01.660  OcPerfJS res #635 ... dur=13 enc=0 dec=0 xfer=300 http://127.0.0.1:4173/api/event @19
```

窗口内：`/api/event` 的 `OcPerfJS res` 共 **62** 次，全部指向 **`http://127.0.0.1:4173/api/event`**；`xfer=300 enc=0 dec=0`（空体）与 `xfer=309 enc=9 dec=9`（有 9 字节帧）交替。

### B.2 页面侧观测（performance entries —— 比 fetch 打点更全）

```json
{"resByHost":{"4095":1,"4173":220,"other":29},
 "perfEvt":[{"n":"http://127.0.0.1:4173/api/event","d":20}, …,{"n":"...","d":146}],
 "esNew":0, "notifCalls":0, "perm":"default"}
```

| 观测 | 值 | 解读 |
| --- | --- | --- |
| `perfEvt` | **30/30 条全是 4173**，`d`=7~30ms（末条 146ms） | 页面侧 `/api/event` 资源**全部打向页面 origin**，且**毫秒级即返回** ⇒ 空/错误体 |
| `resByHost` | 4173=**220**，4095=**1** | 页面事件流主体走 **4173**，4095 只有 1 次 |
| `esNew` | **0** | **复现 P2 结论：页面主事件流不走 `EventSource`**，走 `fetch` ⇒ fetch/performance 打点选型正确 |
| `__ocAndroid` 句柄 | 含 `hasNotificationPermission`、`requestNotificationPermission`、`postMessage` | 通知桥存在（见问题 D） |
| `notifCalls` / `perm` | **0** / **`default`** | 前台回合**未**调用 `new Notification`，权限未申请 |

### B.3 代码侧归属（只读）

- `[global-sdk]` tag 的唯一生产点是**上游 SDK 连接器**，`webapp/src/main/assets/www/ui/oa/runtime-DVG-cmAP.js`（压缩字节偏移 ~27873）：
  `connection: ar(r.api,{..., pageLifecycle:!0, onEvent(e){i.publish(e)}, log:{info(e,t){e===`event stream disconnected` && console.info(`[global-sdk] event stream disconnected`,{url:r.url,...})}}})`
- 发起方 = **上游页面 JS**（`sdk.event.listen`），**不是宿主**。
- base URL 落到页面 origin：`webapp/src/main/assets/www/ui/oa/index-D2r3WEwY.js`（~568851）`function ZI(){return location.origin}`，且 `QI()` 在 localStorage 无 `YI` 默认服务器时返回 `null` ⇒ **服务器上下文 = `http://127.0.0.1:4173`**。
- 4173 **明确拒绝 API**：`webapp/src/main/java/com/xiufeigo/opencodeshell/web/host/LocalUiOriginServer.kt:188-192`
  `// 🔴 红线 3：API 一律 404，宿主不做任何转发。` → `respond(out, 404, "text/plain; charset=utf-8", "not found".toByteArray(), isHead)`，并在 `:281` 追加 `Connection: close`。
- 重连节奏是**上游的固定定时器**，非宿主：同 `runtime-DVG-cmAP.js`（~19080）`rr=1e3`（固定 1000ms，**非指数退避**）；且要求首帧必须是 `server.connected`，404 体永远拿不到 ⇒ 必然走 `await sr(rr, signal)`。

### B.4 判定 **B1**

> **4173 全局流 = 上游 `[global-sdk]` 事件流，由页面 JS 发起、base URL 落到 `location.origin`(4173)；被宿主的 `LocalUiOriginServer` 以「红线 3」404 + `Connection: close` 打掉，上游按固定 1000ms 重连 ⇒ ~1 次/秒空转，carrying 实质为空（`enc=0 dec=0`）。它是「全局/session 无关」的那条流，而**真正承载执行帧的是同一条 `/api/event` 上按 pathname 匹配到的会话流**（`oc-pow` 在回合窗口打出 `enc=9 dec=9`，且 `round start/end` 均到达）。**

**关键澄清（推翻 P5 的「风暴截断」推断）**：

`oc-host.js:745` `var EVENT_PATH_RE = /\/api\/event\/?$/;` 是**纯 pathname 匹配，且不检查 `resp.status`**：

```js
// oc-host.js:868-873
return NativeFetch.apply(window, arguments).then(function (resp) {
  try { if (resp && EVENT_PATH_RE.test(path)) teeEventStream(resp, path); } catch (e) {}
  return resp;
});
```

⇒ **404 也会打 `event stream OPEN`**（只要响应带 body）。所以 `event stream OPEN` 的计数把「4173 的 404 空转」和「4095 的真实流」**混在一起**，不能拿它推断真实流健康度。P5 正是被这个计数误导。

---

## 问题 C：后台断流的确切机制 🔴

### C.1 复现：发送 → 立刻 HOME → 26s → 回前台

| 时刻（device, UTC） | 事件 |
| --- | --- |
| 10:11:31.946 | 发送 |
| 10:11:32.227 | `[oc-pow] round start session.execution.started @715` ✅ |
| **10:11:33.172** | `KEYCODE_HOME`（`topResumedActivity=NexusLauncher`） |
| **10:11:33.281** | `I OcWebHost: P6 dispatch synthetic pagehide (upstream pageLifecycle)` ⚠️ **HOME 后 +109ms** |
| 10:11:33.777 | `VRI[WebHostActivity]: visibilityChanged true→false` |
| *后台 26s…* | **无任何 `round end` / `succeeded`**（`round end=0`） |
| **10:12:07.097** | `I OcWebHost: P6 dispatch synthetic pageshow (upstream pageLifecycle)` |

后台窗口计数：`round start=1`、**`round end=0`**、**`succeeded=0`**、`event stream OPEN=14`、`[global-sdk] disconnected=13`。

### C.2 后端侧对照（决定性）

```json
18:11:33.126  user        text='reply with the single word OK'
18:11:33.320  assistant   finish=stop  text='OK'
18:11:35.347  idle        outcome=succeeded      ← 后端在后台期间成功完成
```

换算到同一时钟：`pagehide` 派发于 **18:11:33.281**，后端 **`idle succeeded` 于 18:11:35.347** —— **在 abort 之后 2.066s**。

### C.3 代码侧机制（只读）

- 宿主合成点：`webapp/src/main/java/com/xiufeigo/opencodeshell/web/WebHostActivity.kt:740-744`
  ```kotlin
  private fun dispatchPageLifecycleEvent(name: String) {
      if (!::webView.isInitialized) return
      Log.i(TAG, "P6 dispatch synthetic $name (upstream pageLifecycle)")
      evaluateJs("window.dispatchEvent(new Event(\"$name\"))")
  }
  ```
  触发点仅 **`onPause`(:708) / `onResume`(:724)**，无 `onStop/onStart` 覆写、无定时器。注释自陈意图（:702-707）："WebView 切后台**不发 pagehide**（connection.ts:222-223 注释自己承认），机制在位、扳机缺失 —— 宿主合成派发补上。"
- 上游确实注册了监听并**据此 abort 在途 SSE**，`runtime-DVG-cmAP.js`（~23397）：
  `window.addEventListener('pagehide', e)`，其中 `e = () => w()`；`w()`（~23350）：
  `function w(){h&&(h=!1,g+=1,f?.abort(),s({status:'connecting',attempt:0,error:void 0}))}`
  `f` 是当前 attempt 的 `AbortController`（`let i=new AbortController; f=i;`）⇒ **abort 直接掐断 SSE 读取**。
- 只有 `pagehide`/`pageshow` 被合成；**`visibilitychange` 从未被合成**（全模块搜 `dispatchEvent|new Event\(` 确认）。

### C.4 判定 **C1**

> **是「宿主合成 pagehide 驱动上游主动关」，不是「WebView 网络被冻结」。**
> `onPause` → `dispatchPageLifecycleEvent("pagehide")` → 上游 `pageLifecycle` 的 `pagehide` 监听 → `w()` → `AbortController.abort()` → 在途 SSE 读取被主动取消。
> 实证判据：HOME 后 **+109ms** 就打出合成 pagehide（若为网络冻结，不会出现这个**应用自身的合成日志**）；且 `round start` 在 pagehide 之前 1.05s 到达、之后整整 26s 一个帧都没有。

**回前台后 UI 上的 `OK` 是哪来的**：回前台（10:12:07.097 合成 `pageshow`）后上游重连并**重新拉取会话消息**渲染，补上后台期间后端已写好的 `OK` —— **不是**事件流推来的（后台窗口 `succeeded=0` 已证明）。

### C.5 附带发现：`oc-pow` 自己承认这个边界

`webapp/src/main/assets/www/oc-host.js:699-700`
```
// 已知边界（与旧代理时代同取舍）：后台期间回合结束时 SSE 已被掐断，事件丢
// 失 ⇒ 靠宿主 RoundActivityTracker 的 90s 空闲兜底 + 30min 硬上限收口。
```

---

## 问题 D：权限申请链 🔴

### D.1 谁负责申请

| 环节 | 位置 | 状态 |
| --- | --- | --- |
| Android 侧 launcher | `WebHostActivity.kt:238-248` `registerForActivityResult(RequestPermission())`，结果打 `POST_NOTIFICATIONS granted=$granted` 并派发 `oc:permission` CustomEvent | **存在、可用** |
| 对外触发入口 | `WebHostActivity.kt:542-548` `fun requestNotificationPermission()` → `notificationPermission.launch(POST_NOTIFICATIONS)` | **存在** |
| JS 桥 | `webapp/.../host/WebHostBridge.kt:73-76` `@JavascriptInterface fun requestNotificationPermission()`；读回 `hasNotificationPermission()` @:65-71 返回 1/0/-1 | **存在** |
| shim | `oc-host.js:537-547`：`permission="default"`（**静态赋值，非 getter**）；`requestPermission()` 里 `has===1→"granted"`、`has<0→"denied"`、否则 `oc.requestNotificationPermission()` 并 resolve `"default"` | **存在** |
| 渠道 | `webapp/.../host/NotifierHost.kt:31` `CHANNEL_TURNS="oc_turns"`，`ensureChannels` @:156-170；`WebHostActivity.kt:259` onCreate 调用 | **存在**（设备实测 `mImportance=3` 已建） |

**唯一的上游触发点**（`index-D2r3WEwY.js` ~568200，等价 `web.ts`）：
```js
async notify(e,t,n){ if( !(`Notification` in window)
   || (Notification.permission==="default" ? await Notification.requestPermission().catch(()=>`denied`)
                                          : Notification.permission) !== `granted`
   || (document.visibilityState===`visible` && document.hasFocus()) ) return;
```

而这个 `notify()` 只由**终帧监听**触发 —— `runtime-DVG-cmAP.js`：
```js
E = e.sdk.event.listen(e=>{ if(e.type!==`session.execution.succeeded` && e.type!==`session.execution.failed`) return; ... })
```

### D.2 实测（本轮跑完 A+B+C 三个场景后）

| 判据 | 值 | 解读 |
| --- | --- | --- |
| `POST_NOTIFICATIONS` | **`granted=false`** | 全程未申请 |
| logcat `POST_NOTIFICATIONS granted=` | **0 命中** | **宿主 launcher 一次都没被触发** |
| logcat `oc:permission` | **0 命中** | 从未派发过权限结果事件 |
| 页面 `Notification.permission` | **`default`** | 未 granted 也未 denied |
| `window.__notifCalls` | **[]** | `new Notification()` 从未调用 |
| 渠道 `oc_turns` | 存在，`mImportance=3`，**`mLastNotificationUpdateTimeMs=0`** | **从未被写入过通知** |

### D.3 判定 **D1**

> **触发首次 `POST_NOTIFICATIONS` 申请的唯一环节 = 上游 notify 闸门里的 `Notification.requestPermission()`，而该闸门只挂在 `session.execution.succeeded` / `.failed` 上。**
>
> 因为 `onPause` 合成 pagehide → SSE abort → **终帧永不到达** ⇒ 闸门**从不被评估** ⇒ `requestPermission()` 从不被调 ⇒ shim 的 `oc.requestNotificationPermission()` 从不被调 ⇒ 宿主 launcher 从不弹窗 ⇒ `granted` 永远 `false`。
>
> **即：只要回合在后台结束，权限申请就永远不会被触发 —— 形成死锁。**

补充：按 shim 逻辑，**若闸门真被评估**，`hasNotificationPermission()` 返回 `0`（非 granted、非 -1）会走 `oc.requestNotificationPermission()`，**宿主是会弹窗的**。所以「不弹窗」**不能**推出「宿主缺申请代码」—— 宿主代码是好的，缺的是终帧。

---

## 修复面建议

### 宿主可修（我方，优先级从高到低）

| # | 建议 | 依据 | 预期效果 |
| --- | --- | --- | --- |
| **F1** | **后台不要合成 `pagehide`**（或至少：先确保终帧可达再断）。最小改法 = `onPause` 只 `webView.onPause()`，把 `dispatchPageLifecycleEvent("pagehide")` 移除/延后；或改为**延迟到空闲后再断** | C.1/C.3 实测 pagehide 在 HOME 后 +109ms 掐断，2.07s 后后端才完成 | **单点修复后台通知**；同时修好 `oc-pow` 自述的「后台丢终帧」边界 |
| **F2** | 在 `oc-host.js:798` 的 OPEN/CLOSE 日志里补上 **origin + `resp.status`** | `EVENT_PATH_RE` 是纯 pathname 匹配、不查 status，404 也打 OPEN ⇒ OPEN 计数已误导过 P5 和本轮早期判断 | 一条 grep 即可区分 4173-404 与 4095-真实流 |
| **F3** | 补发 `visibilitychange`（与 pagehide/pageshow 配套） | 上游 notify 闸门读 `document.visibilityState`/`hasFocus()`；宿主只合成 pagehide/pageshow，**从不合成 visibilitychange** | 让「已后台」这一事实真正传到页面（当前靠 WebView 自身行为，不可控） |
| **F4** | 权限申请**从上游闸门上移**：`WebHostActivity.kt:259`（已有 `ensureChannels`）旁，在**首帧前台**主动 `notificationPermission.launch(...)` 一次（对齐原生壳 `OcActivity.kt:139` 的做法） | D.3 死锁：权限等终帧、终帧等不后台 | 打破自锁；F1 不落地时也能让通知有投出可能 |
| **F5**（可选）| 让 4173 不再空转：`LocalUiOriginServer` 遇 `/api/*` 时，或让页面在无默认服务器时把 SDK base URL 指向已选服务器 | B.3「红线 3」+ 上游固定 1000ms 重连 | 降 ~1 次/秒空转噪声与电量开销（**不影响功能正确性**） |

### 上游固有（不可由宿主单独消除）

| 项 | 说明 |
| --- | --- |
| 通知链路**只**挂在 `session.execution.succeeded`/`.failed` 两个终态事件上 | 回合在后台结束 ⇒ 必须依赖事件流存活；这是上游语义 |
| 终态事件**无重放/补偿**机制 | 后端 REST 能查到 `idle outcome=succeeded`，但事件流侧没有 replay；宿主只能靠回前台重新拉消息兜底 |
| SSE 重连是**固定 1000ms、非指数退避**，且首帧必须 `server.connected` | 上游 `connection.ts` 语义 |

---

## 步骤 5：收尾 ✅

| 复核项 | 结果 |
| --- | --- |
| `adb emu kill` (6790) | `OK: killing emulator, bye bye` |
| `adb devices -l` | **空**（无任何 serial 残留） |
| `adb reverse --list`（kill 前） | `host-15 tcp:4095 tcp:4095` |
| `adb reverse --list`（kill 后） | `error: no devices/emulators found` ⇒ **规则随设备销毁，无残留** |
| 端口 6790 | 仅剩 `TimeWait`（`OwningProcess=0`），**NO LISTENER — 已释放** |
| emulator / qemu 进程 | **均已退出**（`none (all exited)`） |
| 后端 4095 `OwningProcess` | **15096 → 15096（未变，与 P5 记录一致）** |
| `GET /api/info` | **HTTP 200** `{"version":"2.0.24","pid":15096}` |
| 未 kill-server | ✅ 全程只用 `adb emu kill`，只关自建 `emulator-6790` |
| **未改代码** | ✅ 本轮**仅写入 2 个文件**：本报告 + `research/web/_p6_emu.ps1`（辅助脚本）。`git status` 中 `webapp/` 下的 5 处改动 **mtime 为 17:18~17:27，全部早于本轮（18:03 起）**，是 P3/P4 的既有产物，非本轮所致 |
| 未碰 gradle | ✅ |

**碰过的 serial（自始至终只有一个）**

```
List of devices attached
emulator-6790	device product:sdk_gphone64_x86_64 model:sdk_gphone64_x86_64 device:emu64xa transport_id:10
```

### 本轮推翻/修正的既有结论

| 既有结论 | 出处 | 本轮修正 |
| --- | --- | --- |
| 「SSE 被 ~1s 重连风暴截断，终止事件在重连瞬间被丢弃」 | P2 §5.5 / P5 §3.8 | ❌ **不成立**。前台 `succeeded` 正常到达（Δ=2.133s）。`event stream OPEN` 会为 **404 空响应**也打点（`EVENT_PATH_RE` 纯 pathname 匹配、不查 status），计数把 4173 空转与 4095 真实流**混为一谈**，据此推断风暴致损是错的 |
| 「通知闸门从未被评估，因为客户端 SSE 通道是死的」 | P5 §3.8 | ⚠️ **部分修正**：通道在**前台是活的**；死的是**后台窗口**——被宿主 `onPause` 合成 `pagehide` 主动 abort |
| 「宿主从未申请 POST_NOTIFICATIONS」= 宿主缺代码 | P5 §3.8 第 1 项 | ⚠️ **归因修正**：`WebHostActivity.kt:238/542` 的 launcher **存在且可用**，是**上游闸门从不评估**导致没被触发，不是宿主没写 |
| 「页面事件流直连 4095」 | P5 §3.5 | ⚠️ **修正**：`performance` 资源条目 **4173=220 / 4095=1**，页面 `/api/event` 主体打向 **4173 页面 origin**；4095 那 1 次是独立请求 |

## 产物清单

| 文件 | 内容 |
| --- | --- |
| 本报告 | `research/web/UX13_P6_NOTIFY_ROOTCAUSE.md` |
| `_p6_00_home` ~ `_p6_16_returned.png` | 全流程截图（首屏/加服务器/上下文/会话/发送/后台/回前台） |
| `_p6_logcat_fg.txt` | 前台回合窗口 logcat（含 `round start` + `round end`） |
| `_p6_logcat_bg.txt` | 后台窗口 logcat（含合成 pagehide，`round end=0`） |
| `_p6_emu.ps1` | 本轮 adb/注入辅助脚本 |

## 复现备忘

- adb 不在 PATH：`C:\Users\xiufeigo\AppData\Local\Android\Sdk\platform-tools\adb.exe`
- 启动判据**不能**只等 `sys.boot_completed`；要等 `pm list packages` 能返回 `package:`
- 组件串用 **`<applicationId>/<真实类全名>`**（debug applicationId ≠ 类所在包）
- eval 回显 tag 是 `OcWebHost/Eval`（含 `/`），`logcat -s` **取不到**，必须 `logcat -d | grep`
- **device logcat 是 UTC，backend 是 +08**，比对前先换算 8 小时
- 发送按钮坐标随软键盘变化：**键盘弹起 (972,1405)**；收起后 (972,2286) 落在键盘上
- `input text` 空格要写 `%s`；收键盘用 `keyevent 4`(BACK)，`keyevent 111`(ESC) 会关掉对话框