# N1/N2 · 上游一致性核查（N5 独立一轮）· 以 `origin/v2` 为准

- **轮次**：N5（上游一致性工程师，独立一轮：N-1 通知投递策略 / N-2 相机权限）
- **仓库**：`C:\sensorsdata\main\program\opencode-android-shell`
- **日期**：2026-10-05（**硬期限 2026-10-07**）
- **上游基准**：`_upstream` 的 **`origin/v2` = `ecfa884`** `feat(session-ui): show model variant in message meta (#53112)`
- **取证方式**：**只用 git 对象**（`git -C _upstream show origin/v2:<path>` / `git -C _upstream grep -n … origin/v2 -- packages/app/src packages/gui-extensions/src packages/cli/src`），**未切 `_upstream` 工作区**
- **设备**：🔴 **未碰设备、未起模拟器**　**后端**：🔴 **未起停**

> ⚠️ **对前置材料的更正（先说，因为它改变了结论）**
> `FIX_D1.md:382` 写「`git rev-parse origin/v2` 在本 clone **失败**……`origin/v2` 不是本仓的 ref」。
> **实测不成立**：`git -C _upstream log --oneline -1 origin/v2` 正常返回 `ecfa884`。
> ⇒ **`origin/v2` 是可用的对象来源**，本轮全部上游行号均取自该 ref，可复现。
> 另 `FIX_D1.md:385,573` 的「未复核 `_upstream` HEAD SHA」在本轮已闭环。

---

## 0. 结论速览

| 项 | 判定 | 一句话 |
|---|---|---|
| **N-1 通知投递策略** | **不一致 + 缺失**（含 3 条**自造**） | 上游只发 `turn-complete`/`error` 两种、**必须该会话有打开 tab**、按 eventID 跨重启去重、有焦点即放弃；**我们生产路径上一条都不发**，且宿主侧的「权限请求类/提问类通知」「后台补投 120s」是**上游根本没有的自造物** |
| **N-2 相机权限** | **需要但我们没实现（缺失）** | 上游**真取流**（`getUserMedia` + `QrScanner`）用于服务器配对扫码；我们**零权限、零回调** ⇒ 该功能在本宿主不可用 |

**本轮改了代码**（仅 `webapp/**`）⇒ **⚠️ 需重建交付物**（见 §5）。

---

## 1. N-1 · 上游通知投递策略（`origin/v2` 原文）

### 1.1 何时发：只有两种事件

`packages/app/src/shell/notifications/notification.tsx:281-290`：

```ts
const unsub = input.sdk.event.listen((event) => {
  if (event.type !== "session.execution.succeeded" && event.type !== "session.execution.failed") return

  const time = Date.now()
  if (event.type === "session.execution.failed") {
    handleSessionError(event.data.sessionID, event.data.error, event.id, time)
    return
  }
  handleSessionIdle(event.data.sessionID, event.id, time)
})
```

通知种类是**封闭 union**，`notification.tsx:26-29`：

```ts
export const Notification = Schema.Union([
  Persistence.struct({ ...NotificationBase, type: Schema.Literal("turn-complete") }),
  Persistence.struct({ ...NotificationBase, type: Schema.Literal("error"), error: SessionError.Error }),
])
```

**⇒ 上游既不发「权限请求」类通知，也不发「提问（question）」类通知。** 这两类在 `origin/v2` 里根本不存在。

子会话直接跳过，`notification.tsx:225`（success）/ `:253`（error）均为 `if (session.parentID) return`。

### 1.2 如何决定投递与否：三层门控

**① 有打开 tab 才通知**（这就是 M13，但措辞是反的）

`notification.tsx:227-230,240-246`：

```ts
const hasOpenTab = sessionIDHasOpenTab(tabs.store, input.key, sessionID)
if (hasOpenTab && settings.sounds.agentEnabled()) {
  void input.coordinator.sound(`${input.key}\0${eventID}`, () => playSoundById(settings.sounds.agent()))
}
…
if (hasOpenTab && settings.notifications.agent()) {
  void input.coordinator.system(`${input.key}\0${eventID}`, () =>
    platform.notify(language.t("notification.session.responseReady.title"), session.title ?? sessionID, …),
  )
}
```

错误侧同构，`notification.tsx:255-258,271-277`。

`packages/app/src/shell/tabs/tabs.tsx:66-68`：

```ts
export function sessionIDHasOpenTab(tabs: Tab[], server: ServerConnection.Key, sessionID: string) {
  return !!findSessionTab(tabs, server, sessionID)
}
```

> 🔴 **A4 的 M13 表述需要更正**：`PARITY_LEDGER_A4.md:118` 写「通知『会话有打开 tab 则静音』门控」。
> **代码是 `hasOpenTab &&` ⇒ 语义是「该会话必须有打开 tab 才通知」；没有打开 tab 的会话**不发**系统通知**（只进应用内 store）。
> 「有 tab 则静音」与上游**逻辑相反**。按错的方向实现会做出反向行为。

**② 焦点/可见性门控**（有焦点即放弃，**无重估分支**）

`packages/app/src/shell/notifications/coordinator.ts:60-69`：

```ts
system(eventID: string, run: () => Promise<unknown> | void) {
  return once("system", eventID, async () => {
    if (typeof document !== "undefined" && document.hasFocus()) return
    if (!locks) return run()
    await locks.request(FOCUS_LOCK, { mode: "exclusive", ifAvailable: true }, async (lock) => {
      if (!lock) return
      await run()
    })
  })
},
```

`coordinator.ts:11-31` 的 `updateFocus()` 会在**失焦后释放锁**，但 `system()` 里那句 `document.hasFocus()` 判断**每次调用重新求值**——即"此刻有焦点就不发"，**没有**"发出去之后再等一会儿补投"的分支。

平台层还有一道，`packages/app/src/runtime/platform/web.ts:21-40`：

```ts
async notify(title, description, onClick) {
  if (!("Notification" in window)) return

  const permission =
    Notification.permission === "default"
      ? await Notification.requestPermission().catch(() => "denied")
      : Notification.permission
  if (permission !== "granted") return
  if (document.visibilityState === "visible" && document.hasFocus()) return

  const notification = new Notification(title, {
    body: description ?? "",
    icon: "https://opencode.ai/favicon-96x96-v3.png",
  })
  notification.onclick = () => { window.focus(); onClick?.(); notification.close() }
},
```

**③ 用户设置开关**

`settings.notifications.agent()` / `settings.notifications.errors()`（系统通知）与 `settings.sounds.agentEnabled()` / `.agent()` / `.errorsEnabled()` / `.errors()`（音效），UI 在 `packages/app/src/settings/notifications/notifications.tsx:78-122`：三个 `Switch`（`settings-notifications-agent` / `-permissions` / `-errors`）+ 三个音效 `Select`（`settings-sounds-agent` / `-permissions` / `-errors`）。

> 注：`settings.notifications.permissions()` 这个开关**存在于 UI**，但**系统通知路径上没有被读**（`coordinator.system` 的全部调用点只有 `notification.tsx:241,272`，见 §1.5）。

### 1.3 去重：按 eventID，跨重启，且跨 tab

`coordinator.ts:73-93`：

```ts
function claim(kind: "sound" | "system", eventID: string, claimed: Set<string>) {
  if (claimed.has(eventID)) return false

  if (typeof localStorage !== "undefined") {
    try {
      const storageKey = `opencode:notification-${kind}`
      const value: unknown = JSON.parse(localStorage.getItem(storageKey) ?? "[]")
      const events = Array.isArray(value) ? value.filter((item): item is string => typeof item === "string") : []
      if (events.includes(eventID)) { claimed.add(eventID); return false }
      localStorage.setItem(storageKey, JSON.stringify([...events, eventID].slice(-MAX_CLAIMED)))
    } catch { /* 内存 claim 仍能防本渲染进程内重复 */ }
  }

  claimed.add(eventID)
  return true
}
```

`coordinator.ts:46-54` 再用 `navigator.locks` 做**跨 tab 互斥**：

```ts
const once = async (kind, eventID, run) => {
  const key = `${kind}:${eventID}`
  const execute = async () => { if (!claim(kind, key, claimed)) return; await run() }
  if (!locks) return execute()
  await locks.request(`opencode:notification:${key}`, execute)
}
```

⇒ **去重键 = `${kind}:${serverKey}\0${eventID}`**，localStorage 环形保留 `MAX_CLAIMED = 500`（`coordinator.ts:4`）。

**分组**：系统通知**没有分组**（每条独立 `new Notification`）。分组只发生在**应用内 store**：`notification.tsx:57-65`

```ts
const MAX_NOTIFICATIONS = 500
const NOTIFICATION_TTL_MS = 1000 * 60 * 60 * 24 * 30
```
按 `session` / `directory` 两个维度分桶（`notification.tsx:33-46,146-164`），30 天 TTL + 500 条上限，并统计 `unseenCount` / `unseenHasError`。

### 1.4 `viewed` 判定 = 当前路由

`notification.tsx:217-219`：

```ts
const viewedInCurrentSession = (sessionID: string) => {
  return typeof location !== "undefined" && location.pathname === sessionHref(input.key, sessionID)
}
```

### 1.5 全量调用点（判定"只有两种通知"的硬证据）

`coordinator.system` / `coordinator.sound` 在 `packages/app/src` 全域只有 **4 处**，全部在 `notification.tsx`：

```
origin/v2:packages/app/src/shell/notifications/notification.tsx:229: … coordinator.sound(…)   // turn-complete 音效
origin/v2:packages/app/src/shell/notifications/notification.tsx:241: … coordinator.system(…)   // turn-complete 系统通知
origin/v2:packages/app/src/shell/notifications/notification.tsx:257: … coordinator.sound(…)   // error 音效
origin/v2:packages/app/src/shell/notifications/notification.tsx:272: … coordinator.system(…)   // error 系统通知
```

---

## 2. N-1 · 我们的实现（`webapp/**`）

| 位置 | 内容 |
|---|---|
| `webapp/src/main/java/…/host/NotifierHost.kt:173-216` | `postTurnFinished`：建渠道 → 查 `POST_NOTIFICATIONS`（TIRAMISU+）→ `clickPlan(sessionId)` → `NotificationManagerCompat.notify(plan.notifyId, …)`。**全文无去重 / 无静音 / 无焦点判断** |
| `…/host/WebHostBridge.kt:83-88` | `@JavascriptInterface postMessage(title, body, sessionId)` → `NotifierHost.postTurnFinished(ctx, t, b, s)`。这是宿主通知的**唯一生产投递口** |
| `…/assets/www/oc-host.js:134-140` | 页面侧 facade `oc.postMessage` → `N.postMessage(...)` |
| `…/assets/www/index.html:284` | `oc.postMessage("自检通知", "W1 骨架 postMessage 通路验证", "sess_demo_1")` |

**🔴 生产路径断裂（代码级证据）**

`oc.postMessage` 的调用者全仓只有 `index.html:284` —— **自检页**，不是官方 SPA。
上游 SPA 里没有任何一处调 `__ocAndroid` / `oc.postMessage`（`FIX_D1.md:399` 已指出，本轮复核一致）。
同时上游 `web.ts:22` `if (!("Notification" in window)) return` 会在没有浏览器 `Notification` 的宿主里早退。

⇒ **A4 §9-2「实际由 `WebHostBridge.kt:83 postMessage` 投递」在生产路径上不成立**：宿主通知投递口无人调用。
（`BRIDGE_ACCEPTANCE.md:76-106` 记录的「通知真进系统」是**自检页直接调 facade**取得的，证明了投递通道可用，**没有**证明生产路径会投递。）

### 逐条判定

| A4 编号 | 原判定 | **N-5 判定** | 依据 |
|---|---|---|---|
| **A2** 权限请求类系统通知 | 自造 | **自造（确认）** | `notification.tsx:26-29` union 只有 `turn-complete｜error`；`coordinator.system` 调用点仅 `:241,272`（§1.5）。上游**从不发**权限请求类系统通知 |
| **A3** 提问（question）类系统通知 | 自造 | **自造（确认）** | 同上。上游提问走**应用内 dock**（`session/requests/session-question-dock.tsx:10` 走 `showToast`，非系统通知） |
| **A6** 后台补投 120s TTL + 延迟复查 | 自造 | **自造（确认）** | `coordinator.ts:62` 有焦点即 `return`，**无重估/补投分支**（§1.2②）。上游**不补投** |
| **M13** 「有打开 tab 则静音」 | 缺失 | **缺失，但原表述逻辑相反** | `notification.tsx:240` 是 `if (hasOpenTab && …)` ⇒ **有 tab 才通知**（§1.2①）。按原表述实现会做出反向行为 |
| — 生产通知通路 | 未列为行动项 | **缺失（本轮新发现，最高优先）** | 生产路径无人调用投递口（§2） |

> **A2/A3/A6 的处置建议**：这三项是**原生壳**（`app/**`，本轮禁改）的行为。上游既然**不发**，正确做法是**让它们消失**，而不是在对齐 `webapp/**` 时"补策略"。
> 本轮在 `webapp/**` 内的处置是：**不让它们有机会生效**——见 §3。

---

## 3. N-1 · 改动（仅 `webapp/**`）

### 做法：让**上游的策略自己跑**，宿主只负责投递

不把上游已实现的门控（hasOpenTab / 焦点 / eventID 去重 / 设置开关）**复制**到宿主——复制即产生第二个真相来源。
改为在页面侧补一个 `window.Notification` 垫片，让上游 `web.ts:21-40` 整条路径**原样执行**：

| 上游门控 | 执行位置（改后） |
|---|---|
| `web.ts:22` `"Notification" in window` | **宿主垫片满足**（原本早退） |
| `web.ts:24-28` 权限判定 | 垫片接宿主 `hasNotificationPermission()` |
| `web.ts:29` 可见+焦点 | 页面内原生执行 |
| `coordinator.ts:62` `hasFocus()` | 页面内原生执行 |
| `coordinator.ts:73-93` `claim()` 去重 | 页面内原生执行 |
| `notification.tsx:227,240` `hasOpenTab` | 页面内原生执行（读上游 tab store） |
| `notification.tsx:228,229,256,257` 音效开关 | 页面内原生执行 |
| 宿主 `NotifierHost.postTurnFinished` | **退化为纯投递**（无策略） |

⇒ **M13 与 A6 的"差异"不是被"实现"，而是被"消灭"**：策略只剩上游一份，宿主不再判断。

### diff

**`webapp/src/main/assets/www/oc-host.js`**（`window.__ocAndroid = oc;` 之后插入，约 +67 行）

```js
  function currentSessionId() {
    try {
      var m = /\/server\/[^/]+\/session\/([^/?#]+)/.exec(window.location.pathname || "");
      return m ? decodeURIComponent(m[1]) : "";
    } catch (e) { return ""; }
  }

  if (!("Notification" in window)) {
    var ShimNotification = function (title, options) {
      var opts = options || {};
      var sid = currentSessionId();
      var closed = false;
      try {
        oc.postMessage(String(title == null ? "" : title), String(opts.body == null ? "" : opts.body), sid);
      } catch (e) {}
      this.onclick = null;
      this.close = function () { closed = true; };
      this.__isClosed = function () { return closed; };
    };
    ShimNotification.permission = "default";
    ShimNotification.requestPermission = function () {
      var has = oc.hasNotificationPermission();
      if (has === 1) return Promise.resolve("granted");
      if (has < 0) return Promise.resolve("denied");
      oc.requestNotificationPermission();
      return Promise.resolve("default");   // 异步授权往返，页面侧不可观测 ⇒ 不猜
    };
    try { window.Notification = ShimNotification; } catch (e) {}
  }
```

设计取舍（逐条对应上游契约）：

- **`if (!("Notification" in window))` 前置**：绝不覆盖真浏览器里的官方实现；`web.ts:22` 的语义也正是"有就用"。
- **`requestPermission` 在异步授权后返回 `"default"` 而非 `"granted"`**：Android 授权是异步往返，页面侧观测不到。返回 `"default"` 让上游**下一回合再问一次**（`web.ts:25-27` 会重新走 `requestPermission`），而不是谎报已授权。宁可多问一次，不可静默吞掉。
- **sessionId 从当前路由解析**：上游 `notify(title, description, onClick)` **不带** sessionId；而 `notification.tsx:240` 只在该会话**有打开 tab** 时才发 ⇒ 当前路由即目标会话。路由形状取自上游 `shell/routes/routes.tsx:39-40` / `shell/routes/session.ts:5-7`（与 `oc-host.js` 既有 `__openSessionByRoute` 同一口径）。
- **垫片里没有**任何去重/静音/焦点代码——这是刻意的，见 §3 反向验证。

**新增 `webapp/src/test/java/…/NotificationPolicyBridgeTest.kt`**（7 个用例，见 §3 反向验证）

### 反向验证（**已实跑，非声称**）

对 2 处关键突变做了真实的 mutation-verify（备份在 `research/env/n5_revert_backup/`，故意保留以便复核）：

| # | 突变 | 结果 | 变红的用例 |
|---|---|---|---|
| **A** | 把 `oc-host.js:413` 的 `window.Notification = ShimNotification;` 换成 `/*MUTATION: shim removed*/`（即撤掉垫片） | `BUILD FAILED` · `7 tests completed, 1 failed` | ✅ **`hostJsDefinesNotificationShim` RED**，其余 6 例仍绿 |
| **B** | 在 `NotifierHost.kt:174` 植入自造焦点门控 `val hasFocus = true // MUTATION: self-made focus gate that upstream already does in-page` | `BUILD FAILED` · `7 tests completed, 1 failed` | ✅ **`hostNotifierStaysPolicyFree` RED**，报错原文 `NotifierHost 出现了 \`hasFocus\`（coordinator.ts:62 的焦点门控已在页面侧实现，宿主不得重复）—— 策略出现第二个真相来源` |

两次突变后均已**从备份还原**并校验：`MUTATION_LEFTOVER=0`、`SHIM_PRESENT=1`。

未实跑（仅靠断言覆盖）的撤除点：

| 撤掉什么 | 预期变红的用例 |
|---|---|
| `if (!("Notification" in window)) {` 的前置判断（会让垫片覆盖真浏览器） | `shimOnlyInstallsWhenTheRealApiIsMissing` |
| `oc.postMessage(String(title == null …` 转发 | `shimForwardsToHostDelivery` |
| `oc.hasNotificationPermission()` | `shimMapsHostPermissionState` |
| 路由正则 `/\\/server\\/[^/]+\\/session\\/…/` | `shimDerivesSessionIdFromUpstreamRoute` |
| `WebHostBridge.kt:87` 的 `return NotifierHost.postTurnFinished(ctx, t, b, s)` | `hostStillDeliversThroughExistingBridge` |
| **在 `NotifierHost.kt` 里加回 `dedup` / `eventID` / `localStorage`** | `hostNotifierStaysPolicyFree` |

**突变 B 是本轮最关键的断言**：它把「宿主不得长出自造策略」钉成会失败的测试，
防止后续有人"顺手在 Kotlin 里补个静音判断"——那正是 A6 自造物的复活路径。

### 未做（有意）

- **A2/A3/A6 的删除**属 `app/**`（原生壳），本轮禁改 ⇒ 记入 §6 待办。
- **音效**：上游走 WebAudio 播 45 条内置音效（`shell/notifications/sound.ts`），本宿主不拦、不改。

---

## 4. N-2 · 相机权限

### 4.1 上游到底需不需要摄像头：**需要**，且**真取流**

`packages/app/src/servers/connect/camera.ts:4-22`：

```ts
export function createCameraAvailability() {
  const platform = usePlatform()
  const supported = platform.platform === "web" && window.isSecureContext && !!navigator.mediaDevices?.getUserMedia
  const [available, actions] = createResource(
    async () => {
      if (!supported || !navigator.mediaDevices.enumerateDevices) return false
      const denied = await navigator.permissions?.query({ name: "camera" }).then(
        (permission) => permission.state === "denied", () => false,
      )
      if (denied) return false
      return navigator.mediaDevices.enumerateDevices().then(
        (devices) => devices.some((device) => device.kind === "videoinput"), () => false,
      )
    }, { initialValue: false },
  )
  return { supported, available, refetch: actions.refetch }
}
```

`packages/app/src/servers/connect/scanner.tsx:19-34,36,38`：

```ts
const scanner = new QrScanner(
  video,
  (result) => { … pairingLink(result.data) … redeemPairingLink(link) … },
  { preferredCamera: "environment", maxScansPerSecond: 10, returnDetailedScanResult: true },
)
scanner.setInversionMode("both")
…
void scanner.start().then(() => setState("ready", true), () => setState("error", language.t("server.connect.camera.error")))
```

⇒ **真取流**（`QrScanner` 内部走 `getUserMedia`），用途是**服务器配对二维码扫码**（`opencode pair`）。
入口：`servers/connect/screen.tsx:73` 与 `servers/connect/dialog.tsx:98`（`PairingScanner` lazy 加载，`screen.tsx:16` / `dialog.tsx:35`）。

在 `packages/app/src` + `packages/gui-extensions/src` 全域，`camera|getUserMedia|mediaDevices|qrscanner` 相关命中**仅此一处功能**（`camera.ts` ×2 消费者 + `scanner.tsx`），**无其它摄像头用途**。

### 4.2 上游自带优雅降级（这决定了"缺失"的严重度）

`packages/app/src/servers/connect/screen.tsx:136-155`：

```tsx
<Show when={platform.platform === "web"}>
  <Button … disabled={request.isPending || !camera.available.latest} … onClick={() => setState("scanning", true)}>
    {language.t("server.connect.scan")}
  </Button>
  <Show when={!camera.available.latest && !camera.available.loading}>
    <p id="server-connect-camera-unavailable">
      {language.t(window.isSecureContext ? "server.connect.camera.unavailable" : "server.connect.camera.insecure")}
    </p>
  </Show>
</Show>
```

⇒ 无摄像头时**按钮恒 disabled + 显示 `server.connect.camera.unavailable`**，`scanner.tsx:40` 起流失败再兜一层 `server.connect.camera.error`。
**⇒ 不加相机权限不会崩、不会白屏**，只是「扫码配对」这个入口在本宿主不可用。

### 4.3 我们侧：零命中

`git grep -i "onPermissionRequest\|RESOURCE_VIDEO_CAPTURE\|permission.CAMERA\|mediaDevices\|getUserMedia" -- webapp` ⇒ **EXIT=1，零命中**（A4 §M19 的「复验仍 0 命中」**本轮复现成立**）。

- `webapp/src/main/AndroidManifest.xml:5,12` 只声明 `POST_NOTIFICATIONS` 与 `INTERNET` ⇒ **webapp 模块无 `CAMERA`、无 `uses-feature camera`**。
- `webapp/src/main/java/…/WebHostActivity.kt:1001-1002`：`private inner class HostWebChromeClient : WebChromeClient()`，**只** override `onShowFileChooser` ⇒ 不转授任何 Web 权限。
- `webapp/src/main/java/…/host/FileChooserHost.kt:27` 已自记「`onShowFileChooser` 只能回 `Uri[]`」的限制，**同文件族无相机分支**。

> 注：`app/src/main/AndroidManifest.xml:16-17` **确有** `CAMERA` + `uses-feature camera required="false"`。
> 但那是**原生壳 `app` 模块**，不在 webapp 交付物内、也不作用于 WebView 的 Web 权限转授 ⇒ 与 N-2 无关。

### 4.4 判定

> ## **需要但我们没实现（缺失）**

- 上游**需要**摄像头（`camera.ts:6` + `scanner.tsx:19,33,38` 真取流）；
- 我们**完全没实现**（零权限声明、零 `onPermissionRequest`）；
- 后果**可控**：上游优雅降级 ⇒ 「扫码配对」入口不可用，其余功能不受影响；
- **本轮未加权限**（见 §4.5）。

### 4.5 方案与代价（**本轮只写方案，不实施**）

> 理由：① 涉及 `AndroidManifest.xml` 权限声明 = **产品级决定**（多一个系统权限弹窗）；
> ② 需真机验证（**本轮禁设备**）；③ 严重度为"单入口不可用"，非阻断。

**若产品决定支持**，四步（全部落在 `webapp/**`）：

1. `webapp/src/main/AndroidManifest.xml` 加
   `<uses-permission android:name="android.permission.CAMERA" />` 与
   `<uses-feature android:name="android.hardware.camera" android:required="false" />`
   （`required="false"` 照抄 `app` 模块写法，**不**把无摄像头设备挡在安装之外）。
2. `WebHostActivity.kt:1001` 的 `HostWebChromeClient` override
   `onPermissionRequest(request: PermissionRequest, callback: PermissionCallback)`：
   - `request.resources` 过滤 `PermissionRequest.RESOURCE_VIDEO_CAPTURE`；
   - 用**运行期权限**（`ActivityCompat.requestPermissions` + `onRequestPermissionsResult`）而非直接 `grant()`；
   - 非 `VIDEO_CAPTURE` 资源一律 `deny()`（**白名单**，不整包放行）。
3. 权限被拒 ⇒ **不上报 granted**，让上游 `camera.ts:10-14` 的 `permissions.query({name:"camera"})` 判 `denied` ⇒ 复用**上游已有的降级文案**，不自造。
4. 单测：`webapp/src/test/…/CameraPermissionHostTest.kt`，钉住
   ① manifest 有 `CAMERA` 且 `uses-feature required="false"`；
   ② `onPermissionRequest` **只**对 `RESOURCE_VIDEO_CAPTURE` 放行；
   ③ 未获运行期权限时 `deny` 而非 `grant`。

**代价**：+1 个系统权限弹窗；`WebChromeClient` 回调需处理异步授权往返（与 §3 垫片同源问题：回调在 UI 线程，授权在之后）；
**风险**：整包 `grant(request.resources, ...)` 会把后续新增资源类型一并放行 ⇒ **必须白名单**。

**若产品决定不支持**：在 `webapp/src/main/assets/www/` 侧**不做任何遮蔽**——
上游 `camera.available` 恒 `false` ⇒ 按钮自己 disabled、文案自己显示。**这已是可接受的降级，宿主无需任何代码。**

> ⚠️ **明确结论：本轮没有新增、也没有删除任何 Android 权限。**

---

## 5. 交付物重建

> ## ⚠️ **需重建交付物**

本轮改动了 `webapp/src/main/assets/www/oc-host.js`（打进 APK 的 `assets/www/`）。
⇒ **任何内嵌该资产的 APK / 安装包 / 截图 / 演示录屏均已过期**，需重建后才反映本轮改动。
未改动的部分：`app/**`、`_upstream/**`（只读 git 对象）、`.github/**`、`webapp/build.gradle.kts`。

---

## 6. 回归

命令（前台、持 `.agent-gradle-lock`，跑完已释放）：

```
./gradlew :webapp:testDebugUnitTest :app:testDebugUnitTest --rerun-tasks
```

**结果：`BUILD SUCCESSFUL in 3m 2s`　`EXIT=0`**

| 套件 | 测试类 | 用例数 | 失败 | 错误 | 跳过 |
|---|---|---|---|---|---|
| `:webapp:testDebugUnitTest` | 13 | **104** | **0** | 0 | 0 |
| `:app:testDebugUnitTest` | 677 | **6136** | **0** | 0 | 0 |
| **合计** | 690 | **6240** | **0** | **0** | **0** |

其中本轮新增 `NotificationPolicyBridgeTest`：**7 例，0 失败**（全部计入上表 104）。

**用例数只增不减**：本轮 **+7**（`webapp` 13 → 104 之间的既有 97 例未动）。
**没有删除、没有放宽任何既有断言。** 新增用例全部是**新行为约束**（接线必须存在 / 宿主不得长出自造策略），
其中 2 例已通过实跑突变证明**会真变红**（§3 反向验证）。

> 锁：`Test-Path .agent-gradle-lock` = 开轮前 `False` → 写锁 → 前台跑完 → `Remove-Item` 释放 → 收尾 `False`。**未绕过安全策略。**

## 6.1 收尾自检

| 项 | 状态 |
|---|---|
| 突变残留 | `MUTATION_LEFTOVER=0` ✅ |
| 垫片在位 | `SHIM_PRESENT=1` ✅ |
| 锁 | 已释放 ✅ |
| 越权改动 | 无：只动 `webapp/**` + 新增测试 + 本报告 ✅ |
| `app/**` / `_upstream/**` / `.github/**` / `webapp/build.gradle.kts` | **未触碰**（`_upstream` 只用 `git show`/`git grep` 读对象，**未切工作区**） |
| 设备 / 模拟器 | **未碰** 🔴 |
| 后端 4095 | **未起停** 🔴 |

> ⚠️ **收尾发现的一个非本轮产物**：未跟踪文件 `research/web/_head_oc-host.js`（13804 bytes，`LastWriteTime 2026/10/5 4:11:24`）出现在工作区。
> **它不是本轮创建的**（本轮只写 `research/web/N1N2_UPSTREAM_N5.md` 与 `research/env/n5_revert_backup/`），
> 内容是 `oc-host.js` 的一段开头拷贝，**疑为同仓并发轮次的临时草稿**。
> 本轮**未删除**（非本轮产物，不擅自处置）——**请维护方确认归属**。

---

## 7. 未核对清单

| # | 项 | 为什么未核对 |
|---|---|---|
| 1 | **Android WebView 是否真的不实现浏览器 `Notification` API** | 需真机 / `evaluateJavascript("'Notification' in window")` 验证，**本轮禁设备**。⇒ §2「生产路径断裂」的**结构性证据**（`oc.postMessage` 生产无调用者）**不依赖**此条即可成立；但"改后系统通知真的会在真机弹出"**未做真机验证** |
| 2 | `settings.notifications.permissions()` / `sounds.permissions` 是否有**其它**消费点 | `_upstream` 是 `[blob:none]` partial clone，该 grep 触发远端取 blob 时 `Failed to connect to github.com:443` ⇒ **未核对**。已确认的是：`coordinator.system/sound` 的全部调用点（`notification.tsx:229,241,257,272`）**都不读** `permissions` |
| 3 | 垫片在**真机 WebView** 上的实际投递效果 | 禁设备 ⇒ 未验（`BRIDGE_ACCEPTANCE.md:76-106` 只证明了**自检页**→宿主的投递通道可用） |
| 4 | N-2 四步方案的真机行为 | 禁设备；且本轮**未实施** |
| 5 | A2/A3/A6 自造物的**删除** | 在 `app/**`，本轮禁改 |

---

## 8. 一句话回报

**N-1 = 不一致 + 缺失**：上游只发 `turn-complete`/`error`（`notification.tsx:281-290`）、**必须该会话有打开 tab**（`notification.tsx:240`，A4 的 M13 措辞逻辑相反）、按 eventID 跨重启去重（`coordinator.ts:73-93`）、有焦点即放弃不补投（`coordinator.ts:62`）；而**我们生产路径一条都不发**（`oc.postMessage` 唯一调用者是自检页 `index.html:284`），且 A2/A3/A6 是**上游根本没有的自造物**。
**本轮改动**：加 `window.Notification` 垫片，让**上游策略自己跑**、宿主退化为纯投递；新增 7 例绊线（含"宿主不得长出自造策略"，已实跑 2 次突变证明会变红）。
**N-2 = 需要但我们没实现（缺失）**：上游 `camera.ts:6` + `scanner.tsx:19,33,38` 真取流做配对扫码；我们零权限零回调 ⇒ 该入口不可用，但**上游自带优雅降级**（`screen.tsx:140,148-154`）不崩。
**权限：没有加、也没有删。** ⚠️ **改了代码 ⇒ 需重建交付物。**
