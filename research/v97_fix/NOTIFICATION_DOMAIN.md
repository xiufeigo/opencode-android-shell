# 通知域取证报告（诊断轮 · 只读 · 未改任何源码/测试）

- 诊断人：session-e8091491-5562b 对应的取证轮（通知域专项）
- 日期：2026-10-02
- 仓库：`C:\sensorsdata\main\program\opencode-android-shell`
- 触发：解决 `research/v97_verify/5562_sweepB/REPORT_v97b.md` N7「通知域整体未实现」与 `research/HANDOFF.md` v69 轮「通知全 PASS」的冲突
- 纪律：本轮**未修改任何源码/测试**，**未跑 gradle**，**未装机/碰 adb**。全部结论来自静态取证 + 报告原文对照。

---

## 结论

### **误报**（测试条件不满足）——且 N7 的三条「未实现」里，两条是误读、一条是没等触发条件

**一句话**：本壳的通知投递链路从权限、Channel、`notify()` 到点击回跳全部已实现并已接线；N7 的复现步骤在**一个回合完成之前**就取了读数，而本壳（与上游一致）**只在一轮对话 idle（回合跑完）时才产生任何通知**，所以 `dumpsys notification` 记录数为 0 是该步骤的必然结果；「无 NotificationChannel」是懒创建的副作用（Channel 只在真正要投时才建），「无前台服务」则是本壳与上游共有的**设计如此**（后台保活靠 SSE，不靠前台服务），不是缺陷。

**因此不存在需要修的「断点」**——本轮**不产出修法**，改派修复前请先按 §5 的命令级步骤做一次真机复验。

### 附带的两个真实发现（非 N7 所指，但同域）

| # | 发现 | 性质 |
|---|---|---|
| A | `SettingsStore.kt:190` `notifyErrors` 默认 **false**（对齐上游 `settings/model.tsx:261`），故「失败回合」默认**不通知**。若派单期望失败也提醒，需用户在设置里手动开。 | 既有设计，非缺陷 |
| B | N7 步骤**同时错过了第二条补投路径** `postWithheld()`：它在 `onActivityPaused` 时补投「前台完成、被 focus 门禁挡下」的 raise。但 N7 切后台时回合尚未完成，`withheld` 表是空的，补投无从发生。 | 复现步骤缺口 |

---

## 本壳投递链路与前置条件

### 0. 一句话链路

```
SSE 帧 → ChatViewModel.notifyFor (仅 4 类事件)
   → 门禁1 SessionOwnership.owns      (会话必须是本设备打开过的)
   → 门禁2 notificationEnabled        (三个设置开关, 默认开)
   → 门禁3 dedupe 5s                  (同 kind+session)
   → 门禁4 watchedThroughout + visibleSessionID → viewed
   → SystemNotifier.deliver
        → 门禁5 shouldDeliver(contentVisible)   ← 前台且正在看该会话 ⇒ 跳过
        ├─ 跳过 ⇒ withheld{} + 2s recheck
        ├─ 跳过 ⇒ onActivityPaused / focus-timeout ⇒ postWithheld (TTL 120s)
        └─ 不跳过 ⇒ post()
              → 门禁6 areNotificationsEnabled()  (系统级总开关)
              → ensureChannel()   ← ★ Channel 在这里才被创建
              → NotificationManagerCompat.notify(id, build(...))
```

### 1. 接线：Activity → ViewModel（`OcActivity.kt`）

| 位置 | 原文 / 作用 |
|---|---|
| `OcActivity.kt:81` | `val notificationPrefs = remember { NotificationPrefs(applicationContext) }` |
| `OcActivity.kt:82-92` | `SystemNotifier(applicationContext, settings = { settingsStore.state.value }, owner = this@OcActivity).also { systemNotifier = it }` |
| `OcActivity.kt:93-100` | `ChatViewModel(serverStore, settingsStore, notificationPersistence = notificationPrefs, notificationDelivery = notifications)` ← **投递半边真的注进去了** |
| `OcActivity.kt:101-107` | `DisposableEffect(chatViewModel, notifications) { onDispose { chatViewModel.destroy(); notifications.close(); … } }` |
| `OcActivity.kt:118` | `NotificationPermissionRequest(settingsStore)` ← 权限请求挂在这里 |
| `OcActivity.kt:154-158` | `override fun onNewIntent(intent: Intent) { … NotificationNavigation.request(intent.getStringExtra(SystemNotificationPolicy.EXTRA_SESSION_ID)) }` ← 点通知回跳 |
| `OcActivity.kt:167-170` | `override fun onWindowFocusChanged(hasFocus: Boolean) { super…; systemNotifier?.onWindowFocusChanged(hasFocus) }` ← freeform 被盖的信号源 |
| `OcActivity.kt:219-238` | `NotificationPermissionRequest`：`rememberLauncherForActivityResult(RequestPermission())` + `shouldRequestPermission(sdk, granted, enabled)` |
| `AndroidManifest.xml:22` | `<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />` |

> **manifest 中没有 `<service>` 声明**（全文已读，`AndroidManifest.xml:1-125` 只有 1 个 provider + 2 个 activity）。这不是遗漏，见 §3 上游对照。

### 2. 触发端：什么事件才通知（`ChatViewModel.kt`）

| 位置 | 原文 / 作用 |
|---|---|
| `ChatViewModel.kt:2044` | `notifyFor(event, routed)` ← 每帧都调（注释 7033-7037：feed 是 app 级的，**不限于当前会话**） |
| `ChatViewModel.kt:7056-7108` | `private fun notifyFor(event: ChatStore.Event, routed: String?)` — 全文只认 4 类：`PermissionAsked`(7058)、`FormCreated`(7066)、`RunStatusChanged`(7072) 的 `ERROR`(7077) 与 `IDLE`(7099) |
| `ChatViewModel.kt:7088` | `val finished = !foreign && turnRunStatus.ranTurn(routed)` ← **IDLE 还要再过一道**：必须是本会话、且这一轮真的跑出过 step（注释 7082-7087：Move/rename 的 busy→idle 对不算回合） |
| `ChatViewModel.kt:7099` | `raise(AppNotificationKind.AGENT, sessionID = routed, since = since)` ← **完成通知的唯一来源就是 IDLE 帧** |
| `ChatViewModel.kt:7039-7041` | 注释原文：「`session.idle` is the deprecated-but-still-emitted end of a run」 |

> **这里就是 N7 的正主**：流式过程中（`session.part` delta 等）**没有任何分支会 raise**。N7 步骤 2「确认在流式（`C[Stop]` 存在）」时，通知域处于设计上的静止态。

### 3. 投前门禁

| 位置 | 原文 / 作用 |
|---|---|
| `ChatViewModel.kt:7183` | `if (!sessionOwnership.owns(sessionID)) { … return }` — 门禁1 |
| `SessionOwnership.kt:57-61` | `fun owns(sessionID: String?): Boolean { val id = … ?: return true; return id in ids }` — 只认**本设备打开过**的会话（`learn` 来自 `observeSession`） |
| `ChatViewModel.kt:817-826` | `notificationEnabled(kind)` → `settings.notifyAgent / notifyPermissions / notifyErrors` — 门禁2 |
| `ChatViewModel.kt:7187-7188` | `val watched = since == null \|\| (notificationDelivery?.watchedThrough(since) ?: true)`<br>`val viewed = sessionID != null && sessionID == visibleSessionID && watched` — 门禁4 |
| `AppNotifier.kt:131-134` | `if (!enabled(kind)) { Log.d(TAG, "gate: kind=$kind disabled by the settings switch…"); return null }` |
| `AppNotifier.kt:136-142` | `if (previous != null && time - previous < dedupeWindowMs) { … de-bounced … return null }`（`DEDUPE_WINDOW_MS = 5_000L`，`AppNotifier.kt:247`）— 门禁3 |
| `AppNotifier.kt:154` | `delivery?.deliver(notification)` ← 交给 Android 半边 |

### 4. 投递端：焦点门禁、三条补投路径、`notify()`（`SystemNotifier.kt`）

| 位置 | 原文 / 作用 |
|---|---|
| `SystemNotifier.kt:297-326` | `override fun deliver(notification: AppNotification) { if (SystemNotificationPolicy.shouldDeliver(notification, contentVisible)) { post(notification, via = "raise"); return } … withheld[id] = notification; scheduleRecheck(notification) }` |
| `SystemNotificationPolicy.kt:116-120` | `fun shouldDeliver(notification, appInForeground): Boolean { if (!appInForeground) return true; val sessionVisible = notification.sessionID != null && notification.viewed; return !sessionVisible }` ← **「后台 ⇒ 立刻投」「前台且正看着该会话 ⇒ 不投」** |
| `SystemNotifier.kt:339-369` | `private fun post(…)`：`if (!manager.areNotificationsEnabled()) { … return }`（344）→ `ensureChannel()`（**353**）→ `manager.notify(id, build(notification))`（**354**）→ `posted[id] = …`（357）→ 声音（365-368） |
| `SystemNotifier.kt:404-422` | `private fun recheck(id: Int)` — 补投路径①，延迟 `RECHECK_DELAY_MS = 2_000L`（`SystemNotificationPolicy.kt:52`） |
| `SystemNotifier.kt:167-178` | `onActivityPaused` → `if (!foreground.inForeground) postWithheld()` — 补投路径② |
| `SystemNotifier.kt:268-277` | `focusTimeout` Runnable → `if (foreground.focusTimedOut()) postWithheld()` — freeform 被盖的补投路径③ |
| `SystemNotifier.kt:437-456` | `private fun postWithheld()`：`if (contentVisible) return`；逐个 `shouldPostOnBackground(notification, posted[id], now)` 再 `post(…, via = "background")` |
| `SystemNotificationPolicy.kt:99-105` | `shouldPostOnBackground` = `postedAt == null \|\| postedAt < notification.time` 且 `now - notification.time <= WITHHELD_TTL_MS`（**120_000L**，`SystemNotificationPolicy.kt:74`） |
| `ForegroundTracker.kt:138-142` | `fun resumed(key) { resumedActivities.add(key); if (!wasForeground && windowFocused) foregroundSince = now() }` |
| `ForegroundTracker.kt:149-153` | `fun paused(key): Boolean { …; if (!inForeground) foregroundSince = null; … }` |
| `ForegroundTracker.kt:195-202` | `fun focusTimedOut(): Boolean { if (windowFocused \|\| !inForeground) return false; …; foregroundSince = null; return true }` |

### 5. **Channel 在哪里创建**（直接回答 N7 的「无 NotificationChannel」）

**`SystemNotifier.kt:464-480`：**

```kotlin
private fun ensureChannel() {
    val manager = appContext.getSystemService(NotificationManager::class.java) ?: run {
        Log.w(TAG, "channel: no NotificationManager service"); return
    }
    val channel = NotificationChannel(
        CHANNEL_ID,
        appContext.getString(R.string.oc_setting_section_notifications),
        NotificationManager.IMPORTANCE_DEFAULT,
    ).apply { setSound(null, null); enableVibration(true); setShowBadge(true) }
    manager.createNotificationChannel(channel)
}
```

- `CHANNEL_ID = "opencode.sessions"`（`SystemNotifier.kt:574`）
- **唯一调用点是 `post()` 的第 353 行**（在 `manager.notify` 的前一行）。也就是说：**没有 post 就没有 channel**。
- 函数自己的注释（459-463）写明这是**故意的**：「Re-creating an existing channel is a no-op … so this is safe to call per delivery — and it must be, because the process can be restarted between two deliveries.」

> **结论**：N7 读到的「无 NotificationChannel」不是「没实现 Channel」，而是**「一次都没 post 过」的推论**。这两件事在懒创建下是同一件事的两种表述，不能互相印证为独立证据。

### 6. 横幅（banner）是什么

- `ui/App.kt:569` `NotificationBanner(chatViewModel = chatViewModel, …)` — app 顶部的 in-app 横幅带
- `ui/App.kt:3272-3300` — 数据源是 `chatViewModel.notifications`（即 `AppNotifier` 的同一个 feed），`LaunchedEffect(pending.id) { delay(BannerPolicy.AUTO_DISMISS_MS) … }`
- 即：**横幅与系统通知同源、同触发**。回合没完成 → 两者都不出现。

---

## 上游对照

### 触发面（`_upstream/packages/app/src/shell/notifications/notification.tsx`）

| 位置 | 原文 |
|---|---|
| `notification.tsx:225` | `const handleSessionIdle = (sessionID: string, eventID: string, time: number) => {` ← **完成通知的函数名就写着 Idle** |
| `notification.tsx:231-233` | `if (sessionIDHasOpenTab(tabs.store, input.key, sessionID) && settings.sounds.agentEnabled()) { void input.coordinator.sound(…, () => playSoundById(settings.sounds.agent())) }` |
| `notification.tsx:235-241` | `append({ …, viewed: viewedInCurrentSession(sessionID), type: "turn-complete", session: sessionID })` ← 落 in-app feed |
| `notification.tsx:243-249` | `if (settings.notifications.agent()) { void input.coordinator.system(…, () => platform.notify(language.t("notification.session.responseReady.title"), session.title ?? sessionID, () => openNotificationSession(…))) }` ← **「Response ready」= 回合完成** |
| `notification.tsx:253-278` | `handleSessionError` → `type: "error"`，同样 `if (settings.notifications.errors())` 才 system notify |

**上游没有「流式开始」「流式中」的通知。** N7 期待的「前台流式时的横幅」在上游同样不存在。

### 焦点门禁（上游）

| 位置 | 原文 |
|---|---|
| `packages/app/src/runtime/platform/web.ts:29` | `if (document.visibilityState === "visible" && document.hasFocus()) return` ← **页面可见且有焦点 ⇒ 直接 return，不发通知** |
| `packages/app/src/shell/notifications/coordinator.ts:62` | `if (typeof document !== "undefined" && document.hasFocus()) return` ← `coordinator.system()` 同样先看焦点 |
| `packages/desktop/src/renderer/platform/notifications.ts:6-7` | `const focused = await api.getWindowFocused().catch(() => document.hasFocus()); if (focused) return` |

**上游三端一致：正在看 → 不通知。** 本壳的 `shouldDeliver` 就是它的 Android 映射（`SystemNotificationPolicy.kt:9-19` 的 KDoc 明确写了 `coordinator.ts:60-69` 与 `web.ts:21-36` 两个锚点）。

### 前台服务

- 上游 web/desktop **均无任何前台服务/常驻服务**，完成提醒就是一条普通系统通知。
- 本壳 manifest 同样无 `<service>`。**「无前台服务」= 与上游一致的设计，不是缺口。**
- 后台保活在本壳由 SSE 完成，且 **P11 已实测 PASS**（`REPORT_v97b.md:178`：切桌面 4s 后 `C[Stop]` 与 `C[Working]` 仍在 → SSE 连接保持）。

---

## N7 条件复核（引原文 + 判定）

N7 原文（`REPORT_v97b.md:254-266`）最小复现 6 步，逐条判定：

| N7 步骤 | 原文 | 判定 | 依据 |
|---|---|---|---|
| 1 | `pm grant com.xiufeigo.opencodeshell android.permission.POST_NOTIFICATIONS`（已 granted=true） | ✅ **条件成立** | 权限门禁6 `SystemNotifier.kt:344` 会被放行（另需 `areNotificationsEnabled()`，见 §7 未闭环） |
| 2 | 会话里发一条长 prompt（`Count slowly from 1 to 40…`），确认在流式（`C[Stop]` 存在） | ❌ **无通知可投** | 流式态下 `notifyFor` 的 4 个分支一个都不会命中（`ChatViewModel.kt:7056-7108`）。上游同样只在 idle 触发（`notification.tsx:225`）。**此时 dumpsys=0 是正确行为** |
| 3 | `input keyevent 3` 回桌面，等 4s | ❌ **核心条件缺口** | ① `Count slowly from 1 to 40` 是**慢速**计数，4s 内**回合尚未 idle** ⇒ 仍无 raise；② 切后台时 `withheld` 表为空（raise 都没发生过），补投路径②③ 无内容可补（`SystemNotifier.kt:437-456`）；③ 补投路径① 的 2s recheck 也从未被 arm（`scheduleRecheck` 只在 `deliver` 走到 skip 分支时调用） |
| 4 | `adb shell "dumpsys notification --noredact \| grep -i opencodeshell"` → 只有 `AppSettings: … importance=DEFAULT userSet=true`，**无 NotificationRecord** | ✅ **读数自洽** | 无 post ⇒ 无 NotificationRecord。`AppSettings` 是系统自身条目，不是本壳投递的 |
| 5 | `cmd statusbar expand-notifications` → 通知栏只有 `Android System` 系列 | ✅ **读数自洽** | 同上 |
| 6 | `dumpsys activity services com.xiufeigo.opencodeshell` → `(nothing)` | ✅ **符合设计** | manifest 无 `<service>`（`AndroidManifest.xml:1-125`）；上游亦无前台服务；P11 已证后台 SSE 不断（`REPORT_v97b.md:178`） |

### N7 三条「未实现」结论的逐条定性

| N7 断言（`REPORT_v97b.md:266`） | 判定 | 反证 |
|---|---|---|
| 「**通道**未实现」 | ❌ **误读** | `SystemNotifier.kt:464-480` `createNotificationChannel` 存在；「读不到」是因为它懒创建于 `post()`（353 行），无 post 即无 channel —— **同一现象的推论，不是独立证据** |
| 「**前台横幅**未实现」 | ❌ **误读** | 横幅在 `ui/App.kt:569`，由同一个 feed 喂；feed 只在 `IDLE` 时被写（`ChatViewModel.kt:7099`）。「流式时无横幅」= 上游同语义（`notification.tsx:225` 只有 idle） |
| 「**后台通知投递**未实现」 | ⚠️ **证据不足（不是「未实现」）** | 静态链路完整可投；但 5562 **从未在「回合完成后、且 app 在后台」这一条件下取过读数**，所以真机行为本轮未验到 —— 见 §5 复验步骤 |
| 「无前台服务」 | ✅ **设计如此** | 与上游一致，非缺陷 |

### 与 v69 旧证据的冲突如何消解

`HANDOFF.md:61`：「v69 轮 … 通知（freeform 被盖完成通知 + 前台横幅）… **全 PASS**」
`HANDOFF.md:60`：「v68 五修批 … 通知 freeform 被盖不投（contentVisible+5s grace）」

v68/v69 验的正是 **「回合完成 + freeform 被盖」** 与 **「回合完成 + 前台横幅」** 两个条件 —— 与本报告 §3 上游对照的语义完全一致。**N7 与 v69 并不矛盾：两者测的是同一条链路的两个不同触发点，N7 恰好停在触发点之前。**

---

## 给真机测试员的正确复验步骤（命令级）

> 目标：在**回合真正 idle 之后**、**app 在后台**的条件下取读数。三个条件缺一不可。

### 阶段 A：前置就位

```powershell
adb -s emulator-5562 shell pm grant com.xiufeigo.opencodeshell android.permission.POST_NOTIFICATIONS
adb -s emulator-5562 shell dumpsys package com.xiufeigo.opencodeshell | Select-String "POST_NOTIFICATIONS"
# 必须看到 granted=true

# 系统级总开关（门禁6 的另一半，N7 未查）
adb -s emulator-5562 shell cmd notification allowed_assistant 2>$null   # 若不支持可跳过
adb -s emulator-5562 shell settings get global notification_bubbles
```

```powershell
# ★ 决定性命令：开一条实时日志，覆盖整条链路的每一个门禁
adb -s emulator-5562 logcat -c
adb -s emulator-5562 logcat -s OcNotify:D
```
> 每一环都会打点（`SystemNotifier.TAG = "OcNotify"`，`SystemNotifier.kt:582`）：
> `permission: …ask=…` / `notifier ready: seeded=…` / `foreground: resumed|paused|stopped|focus …` /
> `gate settings: kind=AGENT enabled=true` / `trigger idle: …finished=true` / `raise: kind=AGENT …viewed=…` /
> `accepted: AGENT:ses_…` / `skip: …` / `withheld: …posting` / `recheck: …posting` / **`posted: id=… via=…`** /
> `skip: notifications disabled for the package`
>
> **只要看到 `posted: id=…`，系统侧就一定已经收到**，此时 `dumpsys` 必有条目。

### 阶段 B：触发（回合必须跑完）

1. 进任一会话（本设备打开过的，否则门禁1 拦下）。
2. 发一条**足够长、但有确定终点**的 prompt，例如：
   `Count slowly from 1 to 40. Output one number per line, one second apart.`
3. 确认进入流式（`C[Stop]` 存在）。
4. **`input keyevent 3` 立刻切桌面**（保持流式在后台跑；P11 已证 SSE 不断）。
5. **等待回合真正完成**：60–120s。期间每 10s 采样一次后端，确认 idle 帧已落库：

```powershell
adb -s emulator-5562 shell "dumpsys activity service com.xiufeigo.opencodeshell/.OcActivity"
# 或直接看后端：
# GET http://127.0.0.1:4095/api/session/<ses_id>/message?order=asc&limit=50
#   → 出现 type=idle 行 = 回合已结束（与 REPORT_v97b.md:113 的 api_msgs 读数同法）
```

6. **取读数（必须是回合完成之后）**：

```powershell
# 精确按包名过滤
adb -s emulator-5562 shell "dumpsys notification --noredact | grep -A5 com.xiufeigo.opencodeshell"

# 列出本包 Channel（懒创建，此刻应已存在 CHANNEL_ID=opencode.sessions）
adb -s emulator-5562 shell cmd notification list_channels com.xiufeigo.opencodeshell
# 期望：channel id=opencode.sessions

# 列出本包已投递的通知
adb -s emulator-5562 shell cmd notification list com.xiufeigo.opencodeshell

# 通知栏
adb -s emulator-5562 shell cmd statusbar expand-notifications
```

**期望读数**：`NotificationRecord` 出现、channel `opencode.sessions` 存在、标题为 `oc_notify_title_agent`（「回复已完成 / Response ready」类）、正文为会话标题。

### 阶段 C：两条补投路径（可选但建议，覆盖 v68/v69 语义）

```powershell
# 路径②：前台等回合完成 → 立刻切桌面
#   期望 logcat 出现 withheld: id=… posting … via=background
# 路径③（freeform）：把别的窗口盖住本窗（> FOCUS_LOST_GRACE_MS），让回合在遮挡中完成
```

### 阶段 D：回跳

```powershell
# 点击通知 → 应复用 singleTask 实例走 onNewIntent（OcActivity.kt:154-158）
# 期望：直接落到对应会话；logcat 有 foreground: resumed=OcActivity
```

### 复验判读表

| logcat 出现 | 含义 | 下一步 |
|---|---|---|
| `posted: id=…` | 投递成功 | N7 结论作废，收工 |
| `skip: notifications disabled for the package` | 门禁6 被系统总开关拦 | 查 App 通知设置页总开关 |
| `gate settings: kind=AGENT enabled=false` | 门禁2：用户关过开关 | 设置里打开 |
| `raise: skipped foreign session=…` | 门禁1：会话不是本设备打开的 | 先在本机打开该会话再测 |
| `trigger idle: … finished=false` | IDLE 帧到了但不算回合 | 门禁 `ranTurn`（`ChatViewModel.kt:7088`） |
| **完全没有 `trigger idle:`** | 回合未完成 / idle 帧没到 | 等待更久；查后端是否有 `type=idle` 行 |
| `skip: app is foreground …` 且之后无 `withheld:`/`recheck:` | 补投链路断裂 | 这才是真缺口，见 §6 |

---

## 若复验后仍为 0：断点定位与建议修法（**预案，非当前结论**）

当前定性为**误报**，本节仅供复验真的读出 0 时按图索骥。**本轮未改任何代码。**

### 6.1 逐环断点定位（按 logcat 打点二分）

| 断点 | 缺的打点 | 含义 | 建议修法（方案，不实施） |
|---|---|---|---|
| ① 触发 | 无 `trigger idle:` | idle 帧没到 / 没被路由 | 查 `ChatStore.Event.RunStatusChanged` 的 `session.idle` 与 `session.status{idle}` 两条来源是否都被映射（`ChatViewModel.kt:7039-7041` 已声明两者） |
| ② 归属 | `raise: skipped foreign session=` | `SessionOwnership.learn` 未覆盖该会话 | 拓宽 `learn` 的入口（`ChatViewModel.observeSession`），或在 `owns` 为 false 但会话出现在本地 tab strip 时放行 |
| ③ 开关 | `gate settings: kind=AGENT enabled=false` | 偏好被关 | 非缺陷；复验前先查 `SettingsStore` 三个 key |
| ④ 回合判定 | `trigger idle: … finished=false` | `turnRunStatus.ranTurn` 未认这次 idle | 查 `RUNNING → IDLE` 配对是否在 reconcile 路径被 `settled` 提前清掉（`ChatViewModel.kt:7092`） |
| ⑤ 焦点门禁 | 有 `skip: app is foreground` 但**无** `withheld:`/`recheck:` | 补投链路断 | 重点查 `postWithheld` 的两个 caller：`onActivityPaused`（`SystemNotifier.kt:177`）与 `focusTimeout`（`SystemNotifier.kt:268-277`）；以及 `OcActivity.kt:167-170` 的 `systemNotifier` 是否为 null（`onDispose` 清空于 `OcActivity.kt:105`） |
| ⑥ 系统开关 | `skip: notifications disabled` | 通知总开关关 | 非缺陷；复验前用 `appops`/`settings` 确认 |
| ⑦ post 异常 | `deliver failed: …` + stacktrace | ROM 拒绝 / 权限撤销 | 看 `SystemNotifier.kt:314-325` 的 catch；Android 13+ `POST_NOTIFICATIONS` 被撤销时 `notify` 静默 |
| ⑧ 通道 | 有 `posted:` 但 `list_channels` 空 | 不可能（353 行先建后投） | 若真出现，说明 `ensureChannel` 的 `getSystemService` 返回 null（`SystemNotifier.kt:465-467` 会打 `channel: no NotificationManager service`） |

### 6.2 若最终仍判为真缺口，可能的修法方向

- **不要**加前台服务。P11 已证后台 SSE 不断，前台服务是过度设计且与上游背离。
- 优先补的是 **§6.1⑤ 的补投链路可观测性**：`postWithheld` 入口加一条 `Log.d(TAG, "withheld: map size=…")`，让「表里有东西但没人来认领」这种情况在 `logcat` 上一眼可见 —— 这正是 v63→v68→v69 反复打补丁的那一类问题（见 `SystemNotificationPolicy.kt:59-73` 的 v63 复盘原文）。
- 若确认是「回合完成但 App 被系统冻结导致 idle 帧延迟到回前台才被读」，则 `viewed` 判定（`ChatViewModel.kt:7187-7188` 的 `watchedThroughout`）是唯一防线，修复方向应落在 `TurnRunStatus.since` 的时间基准，而非放宽 `shouldDeliver`。

---

## 附：§5 顺带核对 —— `NotificationPrefs` 的默认值

**问题**：`NotificationPrefs` 的默认值是开还是关？是否需要用户手动打开？

**答**：

- `NotificationPrefs`（`NotificationPrefs.kt:20-35`）**不是一个开关**，它是 in-app feed 的 `SharedPreferences` 落盘家（`PREFS_NAME = "oc_notifications"`，`KEY_LIST = "opencode.android.notifications"`），只有 `load()`/`save()` 两个方法，**没有任何默认值语义**。把它当成「通知开关」是一个命名误解。
- 真正的三个开关在 `SettingsStore.kt:184-190`：

| 设置 | 默认值 | 原文 |
|---|---|---|
| `notifyAgent`（回合完成） | **true（开）** | `prefs.getBoolean(KEY_NOTIFY_AGENT, true)` |
| `notifyPermissions`（权限/表单询问） | **true（开）** | `prefs.getBoolean(KEY_NOTIFY_PERMISSIONS, true)` |
| `notifyErrors`（回合失败） | **false（关）** | `prefs.getBoolean(KEY_NOTIFY_ERRORS, false)` |

- 注释 186-189 给出了 `notifyErrors=false` 的理由：对齐上游 `settings/model.tsx:261`，「the native default used to be `true`, which the audit flagged as a mismatch」。

**因此对 N7 的直接回答：不是偏好开关的问题。** 回合完成通知**默认就是开的**，不需要用户在设置里手动打开；本轮也未在 Settings 里找到任何 `notify*` 被写为 false 的痕迹（未查设备 prefs 文件，列入未闭环）。

唯一需要提醒的：**失败回合默认不通知**（发现 A）。若后续派单要验「失败事件」，必须先在设置里手动打开 `notifyErrors`，否则会得到第二个假阴性。

---

## 未闭环 / 未验到

| 项 | 原因 |
|---|---|
| **回合完成 + 后台**的真机投递行为 | 本轮为静态取证，未碰 adb。§5 的复验步骤尚未执行 —— 这是把 N7 彻底定性为「误报」的最后一步（当前定性基于「链路完整 + 上游语义一致 + N7 条件不覆盖触发点」） |
| `areNotificationsEnabled()` 的系统级读数 | 门禁6（`SystemNotifier.kt:344`）依赖 App 通知设置页的总开关，N7 未查，§5 已给出命令 |
| 设备上 `oc_settings` 三个 `notify*` key 的实际值 | 未读 `/data/data/com.xiufeigo.opencodeshell/shared_prefs/oc_settings.xml`（需 run-as 或装机） |
| N7 步骤 1 之后 `AppSettings: … importance=DEFAULT userSet=true` 这一行 | 该条目属于系统自身（AppSettings），非本包投递；N7 用 `grep -i opencodeshell` 命中它，可能是 `dumpsys` 输出中与本包相邻的段落的上下文行。**未逐行复核该 dump 原文**（报告未落盘原始 dump 文本） |
| 通知点击回跳（`onNewIntent` → `NotificationNavigation`）的真机行为 | 未验（N7 因无通知而无法验） |
| 清除通知 / 通道管理 | 同 N7，无法验 |
| 上游 `notification.tsx:256-300`（error/ask 分支全文） | 只读到 278 行为止；`handleSessionError` 的 `error` 分支与 ask 分支未逐行读完（本轮结论不依赖它们） |
| `_upstream/packages/app/src/session/route.tsx` 等 40+ 个含 `notify` 的上游文件 | 只精读了 `notification.tsx` / `coordinator.ts` / `platform/web.ts` / `desktop/notifications.ts` 四个锚点；其余为 i18n 文案等，按需再挖 |

---

## 搜索过的关键词（便于复核）

- `AppNotificationKind\.` / `\.raise\(` / `notifier\.raise` / `watchedThrough` / `notifyFor\(` / `SystemNotifier\(` / `notificationDelivery` / `SessionOwnership\(` / `NotificationPrefs\(` / `AppNotifier\(` / `NotificationBanner` / `notifications\.value` / `AUTO_DISMISS_MS`
- `notifyAgent|notifyPermissions|notifyErrors|soundAgent`
- `Notification` / `onWindowFocusChanged` / `onNewIntent`（OcActivity）
- 上游：`notify|Notification`（限 `_upstream/packages/{app,desktop}/src/**`）
- 研究目录：`NotificationRecord|opencode.sessions|list_channels`、`v69.*(通知|notif)`、`通知（freeform|freeform 被盖|前台横幅`

**未改动任何源码或测试；仅新增本文件 `research/v97_fix/NOTIFICATION_DOMAIN.md`。**
