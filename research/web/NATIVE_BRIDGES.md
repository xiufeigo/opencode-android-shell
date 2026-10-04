# NATIVE_BRIDGES — Web 宿主的「原生桥」契约

> **P1 · 接口规范工程师** · 2026-10-04 · 仓库 `C:\sensorsdata\main\program\opencode-android-shell`
>
> **本轮纪律**：纯只读调研。**未跑 gradle、未装机、未改任何生产代码**（`app/src/main/**` 零字节改动）。
> 唯一写入 = 本文件（`research/web/NATIVE_BRIDGES.md`）。
> 查不到的一律写 **未核对**，不猜。

---

## 0. 怎么读这份文档

Web 宿主（`webapp/` 模块）要把原生壳**已经踩平过的一整套能力**在 WebView 里重做/对齐。
每项能力按四段写：

| 段 | 含义 |
|---|---|
| **(a) 原生壳现状** | `文件:行号` + 原文。原生壳**现在**是怎么做的。 |
| **(b) Web 宿主契约** | 应当怎么暴露给 JS：接口名、参数、返回、错误语义。**可执行**。 |
| **(c) Android API / 权限** | 对应的平台 API、manifest 声明、minSdk 门槛。 |
| **(d) 必须照抄的坑** | 本仓已用血换来的结论。**违反会复现已记账的 bug。** |

**路径缩写**（下文表格里用）：

| 缩写 | 绝对路径 |
|---|---|
| `A/` | `app/src/main/java/com/xiufeigo/opencodeshell/` |
| `MANIFEST` | `app/src/main/AndroidManifest.xml` |
| `GRADLE` | `app/build.gradle.kts` |
| `RES` | `app/src/main/res/` |

**基线 SDK**（`GRADLE:13-14`）：`minSdk = 26`、`targetSdk = 35`、`compileSdk = 35`；
`applicationId = "com.xiufeigo.opencodeshell"`（`GRADLE:12`）—— **无 build-type 后缀**，
这一点在 §3 FileProvider 里有硬依赖。

---

## 1. 既有 JS 桥面（Web 宿主可以直接复用的存量资产）

原生壳**已经有一层 JS↔Kotlin 桥**，但它属于**旧的 WebView 壳 `MainActivity`**（`MANIFEST:104-109`，
`exported="false"`，**不是 launcher**）。launcher 是 `OcActivity`（`MANIFEST:77-100`）。
**W1 建的 `webapp/` 就是要把这份能力以新壳形式重新交付** —— 下面四块是现成的规格与可抄的实现。

| # | 资产 | 文件:行号 | 作用 |
|---|---|---|---|
| 1 | `OpenCodeAndroidBridge` | `A/OpenCodeAndroidBridge.kt:5-57` | 注入名为 `window.OpenCodeAndroid` 的 `@JavascriptInterface` 对象。**8 个方法**（见 §2）。 |
| 2 | `AndroidBackJs` | `A/AndroidBackJs.kt:11-134` | 注入 `window.__ocAndroidHandleBack()`，返回 `"overlay"｜"nav"｜"root"`。 |
| 3 | `SystemBarsJs` | `A/SystemBarsJs.kt:8-59` | 注入 `window.__ocSystemBars`，观察页面配色并回报给原生。 |
| 4 | `NativeWebSocketJs` | `A/NativeWebSocketJs.kt:11-172` | **猴补 `window.WebSocket`**，把 loopback PTY socket 改走原生 OkHttp。 |
| 5 | `LocalGateway` | `A/LocalGateway.kt`（25617 B） | 本地代理，`publicOrigin()` / `publicPrefix()` / `originFor()`。 |

> **这 4 段 JS 注入文本是可以逐字复制的**（它们是 `const val INSTALL` 字符串常量，无 Kotlin 依赖），
> 但**注入时机与 `evaluateJavascript` 消费**必须重做 —— 因为 `MainActivity` 的消费侧是 `onBackPressed` 与
> tab 切换，与新壳的导航结构无关。逐条契约见下。

---

## 2. JS ↔ Kotlin 接口总表

**命名空间统一为 `window.OpenCodeAndroid`（新壳保留同名字段名，以便 §1 的存量 JS 零改动）。**
`@JavascriptInterface` 是 Android 硬要求：方法必须是 `public`、参数/返回必须是
**Java 侧可映射类型**（`String` / `int` / `boolean` / `void` / `long`），**不能用 `List`/`Map`/可空自定义类型**。

| # | JS 接口 | Kotlin 签名 | 参数 | 返回 | 错误语义 | 现状 |
|---|---|---|---|---|---|---|
| **网络 / WS** ||||||
| 1 | `proxyOrigin()` | `fun proxyOrigin(): String`（`OpenCodeAndroidBridge.kt:12`） | — | 代理 origin 字符串 | 不失败（抛则 JS 侧 `undefined`） | 已有 |
| 2 | `proxyPrefix()` | `fun proxyPrefix(): String`（`:25`） | — | 前缀串 | 同上 | 已有 |
| 3 | `proxyBaseUrl(realUrl)` | `fun proxyBaseUrl(realUrl: String): String`（`:29`） | `realUrl: string` | origin-only 串 | 同上 | 已有 |
| 4 | `syncServerConfig(json)` | `fun syncServerConfig(json: String?)`（`:20`） | `json: string｜null` | `void` | **静默**：`onServerConfig` 内部吞异常 | 已有 |
| 5 | `wsOpen(id, url)` | `fun wsOpen(id: String, url: String)`（`:32`） | `id, url: string` | `void` | 失败以 `__ocNativeWS.dispatch({type:'error'})` 回 | 已有 |
| 6 | `wsSendText(id, text)` | `fun wsSendText(id: String, text: String)`（`:37`） | 同上 | `void` | 抛则 `fire(this,'error')` | 已有 |
| 7 | `wsSendBinary(id, base64)` | `fun wsSendBinary(id: String, base64: String)`（`:42`） | `id, base64: string` | `void` | 同上 | 已有 |
| 8 | `wsClose(id, code, reason)` | `fun wsClose(id: String, code: Int, reason: String)`（`:47`） | `code: number` | `void` | `catch` 静默 | 已有 |
| **主题** ||||||
| 9 | `setColorScheme(scheme)` | `fun setColorScheme(scheme: String?)`（`:53`） | `"dark"｜"light"｜null/其他` | `void` | `darkFromWebScheme` 返 `null` 即**直接 return，不动原生**（`:54`） | 已有 |
| **返回键** ||||||
| 10 | `window.__ocAndroidHandleBack()` | `AndroidBackJs.INVOKE`（`AndroidBackJs.kt:103-112`） | — | `"overlay"｜"nav"｜"root"` | 异常兜底 `"root"`（`:109`）；非法值 `parse` 兜 `ROOT`（`:118-119`） | 已有 |
| **通知（新增）** ||||||
| 11 | `notifyPermissionState()` | **新增** `fun notifyPermissionState(): String` | — | `"granted"｜"denied"｜"unasked"｜"unsupported"` | 不失败 | 待建 |
| 12 | `notifyTurnDone(json)` | **新增** `fun notifyTurnDone(json: String)` | `{sessionId,kind,body}` | `"posted"｜"suppressed"｜"noauth"｜"busy"｜"inapp"` | 见 §3.1 | 待建 |
| 13 | `notifySeen(sessionId)` | **新增** `fun notifySeen(sessionId: String)` | `sessionId: string` | `void` | 静默 | 待建 |
| **文件（新增）** ||||||
| 14 | `saveTextFile(json)` | **新增** `fun saveTextFile(json: String)` | `{filename,mime,text}` | `void`（异步走 SAF） | 必须**每个终态都回调**（§3.3 坑 1） | 待建 |
| 15 | `saveTextFileResult(token, status)` | **新增** `fun saveTextFileResult(token: String, status: String)` | `status: saved｜cancelled｜failed` | `void` | — | 待建 |
| 16 | `openExternal(json)` | **新增** `fun openExternal(json: String)` | `{path,declaredMime}` | `"viewed"｜"copied"｜"failed"` | 见 §3.3 | 待建 |
| 17 | `shareText(json)` | **新增** `fun shareText(json: String)` | `{text,mime,path}` | `"sent"｜"failed"｜"unimplemented"` | 原生壳**未实现**（§3.3 坑 6） | 待建 |
| **凭据（新增）** ||||||
| 18 | `getServerConfig()` | **新增** `fun getServerConfig(): String` | — | `{url,username,hasPassword}` JSON —— **不含口令** | 不失败 | 待建 |
| 19 | `setServerConfig(json)` | **新增** `fun setServerConfig(json: String)` | `{url,password?}` | `"ok"｜"invalid"` | 明文只在**原生层**，见 §3.4 | 待建 |
| 20 | `probeServer(url, password)` | **新增** `fun probeServer(url: String, password: String)` | — | 异步，结果走 `probeServerResult` | 见 §3.8 | 待建 |
| 21 | `probeServerResult(token, status, detail)` | **新增** | `status: v2｜http｜unauthorized｜timeout｜unreachable｜notjson｜spa｜badversion` | `void` | — | 待建 |
| **窗口 / IME（新增）** ||||||
| 22 | `insetsChanged(json)` | **新增** `fun insetsChanged(json: String)` | `{ime,nav,status,left,right,room}` | `void` | 见 §5 | 待建 |
| 23 | `getInsets()` | **新增** `fun getInsets(): String` | — | 同 22 的 JSON | 不失败 | 待建 |
| **电源（新增）** ||||||
| 24 | `setKeepAwake(enabled)` | **新增** `fun setKeepAwake(enabled: Boolean)` | — | `void` | **原生壳未用**，见 §3.6 | 待建（可选） |
| 25 | `onBackground(reason)` / `onForeground()` | **新增** | `reason: string` | `void` | 见 §6.2 | 待建 |

**表内 25 条中：11 条原生壳已实现（#1–#10），14 条为 Web 宿主新增（#11–#25）。**

### 2.1 通用桥接纪律（照抄这几条，否则必踩）

1. **`@JavascriptInterface` 不能省**。Android 上没有该注解的方法在 API 17+ **不可见**。
2. **不要从 `@JavascriptInterface` 直接返回复杂类型**。`List`/`Map`/data class 会**抛异常**。
   规定：**复杂返回一律 JSON 字符串**，Kotlin 侧用 `org.json` 或 kotlinx-serialization 手工编码。
3. **桥方法跑在 WebView 的 JavaBridge 线程，不是主线程**（`OpenCodeAndroidBridge` 的所有实现都因此
   假定自己可以阻塞）。**任何碰 UI / Window / 通知的操作必须显式 `runOnUiThread`** ——
   存量代码正是这么做的：`MainActivity.kt:477-480`
   `onColorScheme = { dark -> webColorSchemeApplied = true; runOnUiThread { applySystemUi(dark) } }`。
4. **所有异常在原生侧吞掉**。JS 侧拿到的应该是**枚举字符串**，不是抛异常 ——
   `@JavascriptInterface` 抛出的异常在 JS 里表现为**方法不存在/静默 undefined**，极难排查。
   正面样板：`BasicAuth.kt:26-31` 的 `?: return null` 语义（空口令 → 不发头，而不是发坏头）。

---

## 3. 逐项契约

### 3.1 通知（Native → Web 方向的回调，不是 JS 主动调）

#### (a) 原生壳现状

**权限申请时机** —— `A/OcActivity.kt:138-139`：
```kotlin
// B-2: ask for `POST_NOTIFICATIONS` while the app is in the foreground.
NotificationPermissionRequest(settingsStore)
```
理由原文 `A/OcActivity.kt:232-238`：
> "Asked on the first composition — **not at the first notification** — because the interesting notification is the one raised while the app is **backgrounded**, and Android can only show a permission dialog from a foreground activity (upstream's lazy `Notification.requestPermission()` in `web.ts:23-27` has no Android equivalent). … the system itself shows the dialog at most twice, so a denial is not re-asked."

纯函数决策 `A/notify/SystemNotificationPolicy.kt:244-245`：
```kotlin
fun shouldRequestPermission(sdkInt, granted, enabled) = sdkInt >= PERMISSION_SDK && !granted && enabled
```
`PERMISSION_SDK = 33`（`A/notify/SystemNotificationPolicy.kt:30`）。
launcher `A/OcActivity.kt:244`，**结果回调是空的**（`:244` `rememberLauncherForActivityResult(...) { }`）——
即**拒绝不做事**。`LaunchedEffect(enabled)` 以三个开关为 key（`:250`），所以**后开开关会重新武装**这次询问。

**渠道** —— **只有一个**，`A/notify/SystemNotifier.kt:573-574`：
```kotlin
/** Stable channel id; a reinstall keeps the user's channel settings. */
const val CHANNEL_ID = "opencode.sessions"
```
构造 `A/notify/SystemNotifier.kt:469-479`：
```kotlin
val channel = NotificationChannel(
    CHANNEL_ID,
    appContext.getString(R.string.oc_setting_section_notifications),
    NotificationManager.IMPORTANCE_DEFAULT,
).apply { setSound(null, null); enableVibration(true); setShowBadge(true) }
manager.createNotificationChannel(channel)
```
**为什么必须 `setSound(null, null)`** —— `A/notify/SystemNotifier.kt:36-38`：
> "the sound is a *setting* (`soundAgent`/`soundPermissions`/`soundErrors`) the app applies itself — **a channel's sound is immutable once created**, so relying on it would make the three switches impossible to honour per kind"

**懒创建、且必须每次投放都调** —— `A/notify/SystemNotifier.kt:458-462`：
> "Re-creating an existing channel is a no-op (`createNotificationChannel` only inserts), so this is safe to call per delivery — **and it must be, because the process can be restarted between two deliveries**"

**投放** `A/notify/SystemNotifier.kt:340-357`：`NotificationManagerCompat.from(appContext)` → `areNotificationsEnabled()` 门
（`:341-347`）→ `ensureChannel()`（`:353`）→ `manager.notify(id, build(notification))`（`:354`）→ 记 `posted[id]`（`:355-357`）。
**标题按 kind** `A/notify/SystemNotifier.kt:558-563`：`"Response ready" / "Permission required" / "Question waiting" / "Something went wrong"`。

**点击回会话** —— extra key `A/notify/SystemNotificationPolicy.kt:36`：
```kotlin
const val EXTRA_SESSION_ID = "opencode.android.notification.sessionId"
```
PendingIntent `A/notify/SystemNotifier.kt:517-527`：
```kotlin
Intent(appContext, OcActivity::class.java)
  .setAction(Intent.ACTION_VIEW)
  .putExtra(SystemNotificationPolicy.EXTRA_SESSION_ID, sessionID)
  .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
// then
PendingIntent.getActivity(appContext, SystemNotificationPolicy.notificationId(notification), intent,
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
```
复用现有实例靠 `MANIFEST:81` `android:launchMode="singleTask"`，理由 `MANIFEST:85-88`：
> "The existing `singleTask` launch mode is also what lets a tapped system notification reuse the running instance and reach `onNewIntent` (B-2) instead of cold-starting a second one."

**requestCode = 通知 id = 每会话一个槽** `A/notify/SystemNotificationPolicy.kt:209-210`：
> "(sessionID ?: "app").hashCode() and 0x7fffffff" —— "The `NotificationManager` id **and** the `PendingIntent` request code: one slot per session"

消费侧：`A/OcActivity.kt:68`（onCreate，**首次组合之前**）与 `A/OcActivity.kt:175-179`（onNewIntent）。
落地 `A/ui/App.kt:886-904`：`if (NotificationNavigation.arrived(...)) { consume() }`（`:893-896`）。

**去重 / 静默**
- 5 秒去重窗：`A/notify/AppNotifier.kt:247` `DEDUPE_WINDOW_MS = 5_000L`，key `"${kind.name}:${sessionID}"`（`:242-243`）。
- 每会话一槽，最新覆盖旧：`A/notify/SystemNotificationPolicy.kt:200-203`
  > "one slot per session, so a session's newer notification replaces its older one (the Android convention for a conversation) instead of stacking — and the tap target keeps the extras of the newest raise."
- **"静默"= 逐 kind 声音开关**，不是渠道差异：`A/notify/SystemNotifier.kt:549-556` + `A/notify/SystemNotificationPolicy.kt:193-197`
  （任何无法识别的值都读作 `none`）。
- App 内横幅自动消失 8 秒：`A/notify/BannerPolicy.kt:41` `AUTO_DISMISS_MS = 8_000L`；点一下会**退掉整个会话**（`:91-102`）。

#### (b) Web 宿主契约

**方向是 Native → JS**（不是 JS 调原生）。原生壳把一次「回合结束」判定完后，调用页面注册的回调。

```js
// 页面侧注册（一次性；原生在 WebView 注入的脚本里挂钩）
window.__ocNotification = {
  onState(cb) { window.__ocNotification._cb = cb; },   // cb({ime, nav, room}) → CSS 变量
  onPermissionAsked(result) { /* 'granted' | 'denied' */ },
};
```

- **#11 `notifyPermissionState()`** —— 页面首帧调用一次，据此决定是否弹「去设置」引导。
  返回四态：`granted` / `denied` / `unasked`（33+ 且未问过）/ `unsupported`（<33 或 <26）。
- **#12 `notifyTurnDone(json)`** —— 原生在**判定回合结束**时调用，JSON `{sessionId, kind, body}`。
  `kind ∈ {agent, permissions, errors}`。返回：
  - `"posted"` —— 系统通知已投放
  - `"suppressed"` —— 被 5s 去重窗 / `viewed` / kind 开关拦下
  - `"noauth"` —— 包级通知被关（用户拒绝了 POST_NOTIFICATIONS）
  - `"busy"` —— 已有一次投放在进行（`claim` 拒绝重入）
  - `"inapp"` —— 只进了 App 内 feed，未走系统通知
  **错误语义**：本方法**不抛**。渠道创建失败 / ROM 拒绝投放 → 返回 `"suppressed"` 并 `Log.w`。
- **#13 `notifySeen(sessionId)`** —— 页面在**整个回合期间**都可见时调用。
  ⚠️ 必须是**客户端时钟**上的"全程可见"，不是"现在可见"（见 (d) 坑 5）。

#### (c) Android API / 权限

| 项 | 值 |
|---|---|
| 权限 | `android.permission.POST_NOTIFICATIONS`（`MANIFEST:22`）；33+ 运行时申请，**pre-33 声明为惰性**（`MANIFEST:20-22`） |
| 渠道 | `NotificationChannel`（**API 26+**，与 `minSdk=26` 齐平，**无需 SDK 分支**） |
| 投放 | `NotificationManagerCompat.from(ctx).notify(id, notification)` |
| 权限状态查询 | `NotificationManagerCompat.areNotificationsEnabled()` |
| 点击复用实例 | `launchMode="singleTask"`（`MANIFEST:81`）+ `onNewIntent` |
| PendingIntent 必需 | `FLAG_IMMUTABLE`（API 23+ 强制） |

**无前台服务**：`app/src/main/**` 内 `startForeground` / `<service>` / `FOREGROUND_SERVICE` **零命中**
（大小写不敏感 grep 覆盖 `*.kt|*.xml|*.kts`）。SSE 流在 `ChatViewModel` 里，随 Activity 组合存活。
**无 WorkManager**：`androidx.work` / `Worker` 零命中 —— 通知重试全靠进程内 `Handler` 定时器
（`A/notify/SystemNotifier.kt:154-155, 381-393`），**不跨进程死亡**。

#### (d) 必须照抄的坑

1. **权限必须在"第一次组合"就问，不能等到第一次通知**。要通知的典型场景是**用户在后台**，
   而 Android 只允许前台 Activity 弹权限框。晚问 = 永远问不到。（`A/OcActivity.kt:232-238`）
2. **渠道声音不可变** → 必须 `setSound(null, null)` + App 自己放声音，否则逐 kind 开关无法实现。
3. **"dumpsys 里没有渠道行" ≠ 渠道坏了**。渠道是懒创建的，只在真投放时才建
   （`A/notify/SystemNotifier.kt:458-462`）。反过来说：**一轮零投放对渠道什么也证明不了**。
4. **不能用 `onStop` 当"用户不看了"**。start/stop 计数器比 HOME 键**晚约 1.1 s**，
   Android 只延后 stop 那一半，导致 turn-complete 通知被**静默丢弃**。
   正面做法：`A/notify/ForegroundTracker.kt:107-109`
   `contentVisible = inForeground && (windowFocused || (now - focusLostAt < FOCUS_LOST_GRACE_MS))`，
   宽限 `FOCUS_LOST_GRACE_MS = 5_000L`（`A/notify/ForegroundTracker.kt:211`）。
5. **`viewed` 必须是"整个回合期间都被看着"（客户端时钟）**，不能是"现在被看着"。
   进程被缓存冻结、SSE 重连后会重放，回合结束帧通常在用户**回来之后**才被读到。
   服务端时钟与设备差了几小时。（`A/notify/TurnRunStatus.kt:32-42`）
6. **绝不能用会话列表判断"这个回合是不是我的"**。共享后端会漏别人的回合。
   归属必须来自"**我曾经打开过它**"（`A/notify/SessionOwnership.kt:16-23`）。
7. **一次 re-check 会把消息吃掉**。三段式载体：raise → re-check（2s）→ withheld，
   由 App 自己去后台那次行程投放（TTL 120s）。（`A/notify/SystemNotifier.kt:64-80`）
8. **消耗必须发生在"到达"而不是"读到"**（consume-on-arrival），且**每次 destination 变化都要重新 offer**，
   否则用户从通知回来会落到空草稿。（`A/ui/App.kt:876-883`）
9. **App 内横幅不能被 `viewed` 抑制**，且**一次 tap 要退掉整个会话**。
   （`A/notify/BannerPolicy.kt:20-28, 76-89`）
10. 🔴 **存量文档 bug**：`A/OcActivity.kt:171-172` 写 `SINGLE_TOP | CLEAR_TOP` / `launchMode="singleTop"`，
    与实际代码（`NEW_TASK|SINGLE_TOP`）和清单（`singleTask`）**都矛盾**。以
    `A/notify/SystemNotifier.kt:503-508` + 清单为准。

---

### 3.2 返回键（WebView 历史 vs 应用内返回 vs 退出）

#### (a) 原生壳现状

`MainActivity.kt:141-146` 注册**唯一一个**、**永久 enabled** 的回调：
```kotlin
val backCallback = object : OnBackPressedCallback(true) { override fun handleOnBackPressed() { handleAndroidBack(this) } }
onBackPressedDispatcher.addCallback(this, backCallback)
```

主流程 `MainActivity.kt:232-260`，**顺序是「JS 先跑，历史第三步才查」**：
```
:233  if (backInFlight) return                                  // 重入守卫
:237  if (currentTab == R.id.nav_chat) { dismissMentionIfShowing() … }
:244  backInFlight = true
:245  binding.webView.requestFocus()
:246  binding.webView.evaluateJavascript(AndroidBackJs.INVOKE) { raw ->
:247      backInFlight = false
:248      val result = AndroidBackJs.parse(raw)
:250-258  when {
            result == OVERLAY || result == NAV -> Unit          // ① JS 吃掉
            binding.webView.canGoBack() -> binding.webView.goBack()   // ② 历史
            else -> { callback.isEnabled = false; onBackPressedDispatcher.onBackPressed(); callback.isEnabled = true }  // ③ 退出
          }
```

JS 侧返回值语义 `A/AndroidBackJs.kt:12-14` + `:65-98`：
1. `overlayOpen()` 命中（选择器含 `dialog-overlay` / `dialog-v2` / `session-context-panel` /
   `role=dialog` / `popover-content` / `role=menu` / `role=listbox`，`:28-37`）→ `fireEscape()` → 返回 `"overlay"`；
2. 会话页 mobile-context tab → 点 `[data-action="session-mobile-tab-session"]` → `"nav"`（`:70-75`）；
3. 找 `aria-label`/text 为 `navigate back` / `go back` / `返回` / `上一页` 的按钮 → `"nav"`（`:82-85`）；
4. 找 `aria-pressed="false"` 且 label 以 `home`/`主页` 开头的按钮 → `"nav"`（`:90-96`）；
5. 否则 `"root"`（`:98`）。

**ESC 永不退出、壳永不吞 ESC** —— `A/AndroidBackJs.kt:130-133`：
```kotlin
fun shouldFinishOnEscape(): Boolean = false
/** Shell must not consume ESC app-wide (Vim / terminal need it). */
fun shellConsumesEscape(): Boolean = false
```
`MainActivity.kt:223-227` 复述：「不在 ESCAPE 上离开进程」。
**无"双击返回退出"、无二次确认 toast**（`MainActivity` 内不存在该逻辑）。

🔴 **`AndroidBackJs.shouldLeaveApp` 是死代码** —— `A/AndroidBackJs.kt:124-128` 定义了，
但全 `app/src/main` **无调用点**；`MainActivity.kt:250-258` 内联手写了同一判定。

**v31 BUG-1（Compose 路径，但 manifest 共享）** —— `MANIFEST:43-53`：
> "Measured on the emulator: every launch logged "OnBackInvokedCallback is not enabled for the application", i.e. the platform kept the app on the legacy back path while the whole shell routes BACK through Compose `BackHandler`s … the browser panel could not be closed with BACK and repeated presses exited the app. Opting in puts back on the activity's `OnBackInvokedDispatcher`, which is where `androidx.activity` registers the callback that drives `OnBackPressedDispatcher` (`ComponentActivity.addObserverForBackInvoker`, API 33+)"

🔴 **⚠️ 已修正（2026-10-04 复核）**：上一版此处写"现存 `BackHandler` 三处"。**方向对，但漏了关键区别** ——
**两种写法在本仓并存**，各有适用条件：

| 写法 | 使用处 | 说明 |
|---|---|---|
| `androidx.activity.compose.BackHandler` | `A/ui/overlay/DialogHost.kt:293`、`A/ui/home/HomeSessionsList.kt:301, 1281` | 常规用法。`DialogHost.kt:104` 注明"composed last so it **outranks the nav tree's handlers**"——靠 composition 顺序取胜 |
| **自建 `OverlayBackHandler`**（直接注册 dispatcher，**不带** lifecycleOwner） | `A/ui/session/SessionScreen.kt:3364-3393`（`@Composable` 定义在 `:3379`，调用点 `:2954` `OverlayBackHandler { browserUrl = null }`） | **在 `NavHost` destination 里 `BackHandler` 会静默失效**，见 (d) 坑 4 |

```kotlin
// A/ui/session/SessionScreen.kt:3379-3393
private fun OverlayBackHandler(onBack: () -> Unit) {
    val dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher ?: return
    val current = rememberUpdatedState(onBack)
    val callback = remember { object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() { current.value() } } }
    DisposableEffect(dispatcher, callback) {
        dispatcher.addCallback(callback)          // ← 直接注册，不带 lifecycleOwner
        onDispose { callback.remove() }
    }
}
```

⚠️ 行号时效说明：`SessionScreen.kt` 在本报告撰写期间（16:38）被**并行 lane** 修改过，
本节行号已于修改后复核。W1 若后续该文件再动，请以符号名（`OverlayBackHandler`、
`incompatibleServerVersion != null`）定位，不要依赖行号。

#### (b) Web 宿主契约

**三段式，逐字照抄原生壳的优先级：**

```js
// 原生侧实现（伪码，逐行对应 MainActivity.kt:232-260）
fun onBackPressed() {
  if (backInFlight) return                       // 重入守卫
  backInFlight = true
  webView.requestFocus()
  webView.evaluateJavascript("window.__ocAndroidHandleBack && window.__ocAndroidHandleBack()") { raw ->
    backInFlight = false
    when (parse(raw)) {                           // "overlay" | "nav" | "root"
      "overlay", "nav" -> Unit                    // ① JS 已消化
      else -> if (webView.canGoBack()) webView.goBack()   // ② WebView 历史
               else -> { callback.isEnabled = false
                          onBackPressedDispatcher.onBackPressed()
                          callback.isEnabled = true }   // ③ 交回系统 → finish
    }
  }
}
```

- 页面必须实现 **`window.__ocAndroidHandleBack()`**，返回**严格三值之一**。
  返回其它任何东西（含 `undefined`）→ 原生按 `root` 处理（`A/AndroidBackJs.kt:118-119`）。
- `parse()` 要剥掉 Android 回传的外层引号：`raw.trim().removeSurrounding("\"").removeSurrounding("'")`
  （`A/AndroidBackJs.kt:116`）。**忘了剥引号 = 每次都当 root = 弹层关不掉、直接退进程。**
- `backInFlight` 重入守卫**必须保留**：`evaluateJavascript` 是异步的，无守卫时连按会并发多次。
- **不要用 `shouldLeaveApp`**，它是死代码，自己写 `when`。

#### (c) Android API / 权限

| 项 | 值 |
|---|---|
| 回调 API | `OnBackPressedDispatcher` + `OnBackPressedCallback`（`androidx.activity:activity-ktx:1.9.3`，`GRADLE:118`） |
| **必需 manifest 标志** | `android:enableOnBackInvokedCallback="true"`（`MANIFEST:56`）—— **API 33+ 不写这条，下面全部失效** |
| 异步取值 | `WebView.evaluateJavascript(js) { value -> }`（API 19+） |
| 权限 | 无 |

#### (d) 必须照抄的坑

1. **JS 优先于 WebView 历史**。若把 `canGoBack()` 放在前面，用户在弹层里按返回会**先退掉整个历史栈**。
2. **必须剥引号**（`A/AndroidBackJs.kt:116`）。`evaluateJavascript` 回传的是 JSON 字符串字面量。
3. **v33+ 必须 `enableOnBackInvokedCallback="true"`**（`MANIFEST:56`）。不写 ⇒ 所有返回键回调静默失效、
   连按直接退出 app。这是已记账的 v31 BUG-1。
4. 🔴 **3b（`enableOnBackInvokedCallback` 还不够）——`androidx.activity.compose.BackHandler` 在 `NavHost` 里会静默失效。**
   `A/ui/session/SessionScreen.kt:3367-3376` 原文：
   > "Deliberately **not** `androidx.activity.compose.BackHandler`: that one adds its callback with
   > `OnBackPressedDispatcher.addCallback(lifecycleOwner, callback)`, which only registers once
   > `LocalLifecycleOwner` reaches `STARTED` (and drops it again on `ON_STOP`). Inside a `NavHost`
   > destination that owner is the `NavBackStackEntry`, so a back handler composed while the entry is
   > not started is silently inert — the press then falls through to `super.onBackPressed()` and
   > finishes the activity, which is exactly what the device showed for the browser overlay
   > (panel stays up, repeated BACK exits the app). Registering directly on the dispatcher is
   > unconditional; the callback is still added in composition order (last one wins) and removed when
   > the overlay leaves."

   ⇒ **W1 若用带路由的导航栈（无论 Compose 还是 Web 历史栈），必须直接
   `onBackPressedDispatcher.addCallback(callback)`（不带 lifecycleOwner），并在卸载时
   `callback.remove()`。** 注意其语义：**按 composition 顺序注册，后注册者胜**（`last one wins`）。
5. **ESC 绝不能映射成返回、绝不能退出进程** —— 终端里的 Vim / xterm 需要 ESC
   （`A/AndroidBackJs.kt:132`）。
6. **不要复用 `AndroidBackJs.shouldLeaveApp`** —— 死代码，抄了会以为有测试保护，其实没有。
7. 返回键**没有**双击退出/二次确认。原生壳没做，Web 宿主要加的话是**新行为**，需单独裁定。

---

### 3.3 文件：打开（SAF）/ 保存 / `FileProvider` / 分享

#### (a) 原生壳现状

**清单侧**（`MANIFEST:115-123`）：
```xml
<provider
    android:name="androidx.core.content.FileProvider"
    android:authorities="${applicationId}.fileprovider"
    android:exported="false"
    android:grantUriPermissions="true">
```
> "FileProvider: share staged artifact bytes as content:// URIs (grant flags on the intent) so 「Open in app」 covers every MIME — the MediaStore/ACTION_SEND fallback only handled images and text."（`MANIFEST:111-113`）

`<queries>` 块（`MANIFEST:27-41`）声明了 **VIEW / SEND / CREATE_DOCUMENT(OPENABLE) 三种**，
理由 `MANIFEST:24-26`：
> "Android 11+ package visibility: let the FileProvider hand-off resolve ACTION_VIEW / ACTION_SEND handlers ("Open in app", share), and let the export fallback probe ACTION_CREATE_DOCUMENT handlers (SAF save)."

**roots** `RES/xml/file_paths.xml:5-8`：`cache-path` / `files-path` / `external-cache-path` / `external-files-path`。

**Kotlin 侧 authority 是硬编码的** `A/ui/session/review/OpenInAppLaunch.kt:62-66`：
```kotlin
/** …the FileProvider authority registered in AndroidManifest.xml
 *  (${applicationId}.fileprovider; applicationId is com.xiufeigo.opencodeshell, no build-type suffix) */
private const val FILE_PROVIDER_AUTHORITY = "com.xiufeigo.opencodeshell.fileprovider"
```
URI 构造 `A/ui/session/review/OpenInAppLaunch.kt:84`：
`FileProvider.getUriForFile(context, FILE_PROVIDER_AUTHORITY, staged)`，
暂存目录 `OpenInAppLaunch.kt:69` `STAGING_DIR = "open-in-app"` 挂在 `context.cacheDir`（`:81`）。

**"打开"= `ACTION_VIEW` + 只给读权限** `A/ui/session/review/OpenInAppLaunch.kt:86-90`：
```kotlin
Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
```
`FLAG_GRANT_WRITE_URI_PERMISSION` 全仓 **零命中** —— **只读**。
**没有 `ACTION_OPEN_DOCUMENT`，没有 `takePersistableUriPermission`**。

**保存 = Compose `ActivityResultContracts.CreateDocument`** `A/ui/session/export/SessionExportSaver.kt:254-256`：
```kotlin
rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
    scope.launch { saver.deliver(uri) }
}
```
MIME 固定 `A/ui/session/export/SessionExportFallback.kt:59` `const val MIME_TYPE = "application/json"`。
`deliver` 的 `uri == null` 分支（`SessionExportSaver.kt:151-155`）原文：
> "this branch used to `return` without calling anything, which left a scope-overload caller (SessionScreen's `exportWorking`) stuck true forever — the tap after that showed no picker, no toast and no file."

**兜底** `A/ui/session/export/SessionExportFallback.kt:128`（29+）`resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)`；
`:137`（pre-Q）`MediaStore.Files.getContentUri("external")`；`RELATIVE_PATH = "Download/"`（`:80-84`）。
**29+ 无需任何存储权限** —— `SessionExportFallback.kt:53`：
> "writes straight to the public Download/ collection through MediaStore, which needs no picker UI and — on API 29+ — no permission."

**超时** `A/ui/session/export/SessionExportFallback.kt:70` `const val SAF_TIMEOUT_MS = 60_000L`，理由 `:66-68`：
> "Generous on purpose: a user browsing for a folder is not a hang, and a late picker answer after the reclaim is dropped, never double-written."

**文件名字段名不能小写化** `A/ui/session/export/SessionExport.kt:56-62`：
> "upstream's `.toLowerCase()` is **not** reproduced here. It turned the device-tagged title E2ECHK5556-base into a pre-filled `e2echk5556-base.json`, so the saved file no longer carried the title the tester could see on screen … on the shared backend those tags are how one device's sessions are told apart."

**文件名清洗是安全控制** `A/ui/session/review/OpenInAppLaunch.kt:98-102`：basename 压平，
`""` / `"."` / `".."` → `"artifact"`，因为 `"FileProvider.getUriForFile requires a file inside a configured root"`。

#### (b) Web 宿主契约

```js
// #14 存文件（JSON → SAF / Download/）
window.OpenCodeAndroid.saveTextFile(JSON.stringify({ token, filename, mime, text }));
// → 随后原生回调 #15：
window.__ocFile.saveTextFileResult(token, 'saved' | 'cancelled' | 'failed');

// #16 用外部应用打开
window.OpenCodeAndroid.openExternal(JSON.stringify({ path, declaredMime }));
// → 同步返回 'viewed' | 'copied' | 'failed'

// #17 分享
window.OpenCodeAndroid.shareText(JSON.stringify({ text, mime, path }));
// → 同步返回 'sent' | 'failed' | 'unimplemented'
```

**接口 15 的存在是强制的**：`saveTextFile` 是**异步**的（要走 SAF 选文件夹），
原生必须持有 `token` 并在**每一个终态**回调 `saveTextFileResult`。
**取消也必须回调** —— 这是原生壳踩过的 P0（见 (d) 坑 1）。

`openExternal` 的降级链（对齐 `A/ui/session/review/OpenInApp.kt:37-45`）：
- `Kind.IMAGE && hasBytes && sdkInt >= 29` → `VIEW`
- TEXT / MARKDOWN / TABLE / HTML / MERMAID → `SHARE_TEXT`（**原生壳未实现**，见坑 6）
- 其余 → `COPY_PATH`，理由 `OpenInApp.kt:35`：
  > "everything else is a path copy so the button never silently does nothing."

#### (c) Android API / 权限

| 动作 | API | 权限 |
|---|---|---|
| SAF 保存 | `Intent.ACTION_CREATE_DOCUMENT` / `ActivityResultContracts.CreateDocument` | **无** |
| SAF 存在性预检 | `PackageManager.queryIntentActivities`（TIRAMISU+ 需 `ResolveInfoFlags.of(0)`，`SessionExportFallback.kt:110-113`） | 需 `<queries>`（`MANIFEST:36-40`） |
| Download 兜底 | `MediaStore.Downloads`（29+） | **29+ 无权限**；pre-29 需 `WRITE_EXTERNAL_STORAGE` |
| 分享 content:// | `androidx.core.content.FileProvider` | **无**（靠 intent grant flag） |
| 打开外部 | `Intent.ACTION_VIEW` + `FLAG_GRANT_READ_URI_PERMISSION` | **无** |
| 依赖 | `androidx.webkit:webkit:1.12.1`（`GRADLE:120`）已在，但文件流不依赖它 | — |

#### (d) 必须照抄的坑

1. 🔴 **"点了没反应"是本仓文件流的头号报障形态**（无 picker / 无 toast / 无文件）。
   根因是某个终态没回调。`ExportFeedback.of` 故意**不带 `else`**（`SessionExportFallback.kt:13-15`），
   `HomeRowActions.kt` 对 `ExportFinish.Cancelled ->` **不写 `is`**（`SessionExportFallback.kt:17-20`）——
   **让"静默"编译不过**。Web 宿主照这个手法：枚举穷尽 `when`。
2. **`withTimeout` 框不住阻塞 IO** —— `A/ui/session/export/SessionExportDeadline.kt:39-44`：
   > "its cancellation reaches the block at the block's next suspension point, so a `withContext(Dispatchers.IO)` body stuck in a blocking socket read (the transport's own 30 s) keeps the caller waiting past the deadline."
   ⇒ 必须 `select` 在 `scope.async` 上（`SessionExportDeadline.kt:57-72`）。
3. **同时只允许一次导出**，迟到的回调丢弃，取消要释放 busy 标志
   （`SessionExportSaver.kt:39-48, 147`）。
4. **SAF 在 Android 11+ 需要 `<queries>` 条目**，否则预检撒谎。
   预检假阴性的代价只是少个 picker，文件仍会落到 `Download/`（`SessionExportFallback.kt:91-95`）。
5. **别对导出文件名做小写化**（v63 BUG-3，`SessionExport.kt:56-62`）。
6. 🔴 **`ACTION_SEND` 分享在原生壳里根本没实现。** 全仓 Kotlin 里
   `Intent(Intent.ACTION_SEND)` / `EXTRA_STREAM` / `EXTRA_TEXT` / `Intent.createChooser` **零命中**；
   `OpenInApp.Action.SHARE_TEXT`（`A/ui/session/review/OpenInApp.kt:22`）与 `actionFor()`
   （`OpenInApp.kt:37-45`）**在 `app/src/main` 无调用点**。
   ⇒ **这是待实现面，不是可照抄的契约。** 页面必须处理 `'unimplemented'`。
7. **暂存文件名清洗是安全控制，不是美化** —— 构造的 `path` 不得逃出 FileProvider root。
8. `Environment.DIRECTORY_DOWNLOADS` 在 **JVM 单测下返回 `null`**（`SessionExportFallback.kt:77-78`）
   → 硬编码 `"Download/"`。

---

### 3.4 凭据 / 令牌

#### (a) 原生壳现状

**好消息：原生壳已经用 `EncryptedSharedPreferences`，不是明文。**
依赖 `GRADLE:142`：
```kotlin
implementation("androidx.security:security-crypto:1.1.0-alpha06")
```

存储 `A/ServerConfigStore.kt:320-334`：
```kotlin
val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
EncryptedSharedPreferences.create(context, "oc_server_config", masterKey,
    PrefKeyEncryptionScheme.AES256_SIV, PrefValueEncryptionScheme.AES256_GCM)
```
🔴 **但有一条静默降级到明文文件** —— `A/ServerConfigStore.kt:331-334`：
```kotlin
catch (e: Exception) {
    Log.w(TAG, "encrypted prefs unavailable, falling back to plain prefs: ${e.message}")
    context.getSharedPreferences("oc_server_config_plain", Context.MODE_PRIVATE)
}
```
代码自己承认了这个风险 —— `A/ServerConfigStore.kt:241-242`：
> "Secrets (password / key passphrase) are never persisted — not even in this file's `EncryptedSharedPreferences`, which silently falls back to a plain file when the platform keystore is unavailable."

**精确 key 串** `A/ServerConfigStore.kt:293-305`：
`DEFAULT_USERNAME = "opencode"`（`:293`）、`KEY_URL = "url"`、`KEY_USER = "username"`、
`KEY_PASS = "password"`、`KEY_LIST_JSON = "list_json"`、`KEY_SSH_JSON = "ssh_json"`。

**口令的实际写入路径** `A/ServerConfigStore.kt:129-162`，两次写：
- `:140-142` 完整 wire payload → `KEY_LIST_JSON`
- `:154-158` 标量镜像**每次接受 payload 都重写**：
```kotlin
prefs.edit().putString(KEY_URL, next?.url).putString(KEY_USER, next?.username)
           .putString(KEY_PASS, next?.password).apply()
```
理由 `A/ServerConfigStore.kt:147-153`：
> "a password-only edit whose decoded next already equals the in-memory server used to skip this write and leave a stale `KEY_PASS` behind, which restore then preferred over the fresh list JSON — every client built from `store.server` kept sending the old (or empty) secret."

**单例，不许每实例一个** `A/ServerConfigStore.kt:80-89`：
> "`EncryptedSharedPreferences.create` is not safe to call concurrently on the same file (first-run keyset generation races) and every call returns a distinct wrapper — per-instance creation let one `ServerConfigStore` write the password into the encrypted file while another instance, which had silently fallen back to the plain file, read null back and sent no Authorization at all."

**SSH 只存"做法"，不存秘密** `A/ServerConfigStore.kt:59-61, 251-252`：
> "No secret material: password and key passphrase are collected interactively on every fresh dial"

**请求时组头** `A/BasicAuth.kt:26-31`：
```kotlin
val secret = password?.takeIf { it.isNotEmpty() } ?: return null          // 空口令 ⇒ 完全不发头
val user = username?.takeIf { it.isNotEmpty() } ?: DEFAULT_BASIC_USERNAME
return "Basic " + Base64.getEncoder().encodeToString("$user:$secret".toByteArray(Charsets.UTF_8))
```
`DEFAULT_BASIC_USERNAME = "opencode"`（`A/BasicAuth.kt:34`）。
**踩过的坑** `A/BasicAuth.kt:16-20`：
> "both built `"$username:$password"`, which interpolated a literal null whenever one half was unset. A server entry with a typed username and an empty password … therefore sent Basic base64("user:null")."

`SettingsStore`（`oc_settings`，`A/SettingsStore.kt:35, 420`）**不含秘密** —— 只存主题等。
刻意不用加密的是 `A/ui/nav/LastRouteStore.kt:17-21`：
> "Deliberately NOT EncryptedSharedPreferences like ServerConfigStore: a route name and a session id are not secrets, and this one is read on the cold-start path where the encrypted store's key derivation would only add latency."

#### (b) Web 宿主契约

**红线：口令永不进 JS 可见面。**

| 接口 | 进 | 出 |
|---|---|---|
| **#18 `getServerConfig()`** | — | `{"url":string,"username":string,"hasPassword":boolean}` —— **无 `password` 字段** |
| **#19 `setServerConfig(json)`** | `{"url":string,"password"?:string}`；**省略 `password` = 不改** | `"ok"`｜`"invalid"` |

- 页面拿到的只有 `hasPassword`（用于渲染"已保存"），**永远拿不到明文**。
- 存储实现**照抄 `ServerConfigStore.kt:320-334`**，但：
  - **必须**把 `oc_server_config_plain` 降级**显式化**（见 (d) 坑 3），不要留静默 `Log.w`。
  - 必须**单例**持有 encrypted prefs（坑 4）。
  - 必须**每次接受 payload 都重写标量镜像**（坑 5）。
- 「读取用于发请求」必须在**探针时刻**现读，不许缓存字段（坑 6）。

#### (c) Android API / 权限

| 项 | 值 |
|---|---|
| 存储 | `EncryptedSharedPreferences`（`AES256_SIV` keys / `AES256_GCM` values）+ `MasterKey(AES256_GCM)` |
| 依赖 | `androidx.security:security-crypto:1.1.0-alpha06`（`GRADLE:142`）—— **已在依赖里，无需新增** |
| 备份 | `android:allowBackup="false"`（`MANIFEST:55`）—— 已关闭，两个 prefs 文件都不进备份 |
| 权限 | **无**（不需要任何运行时权限） |

#### (d) 必须照抄的坑

1. **依赖已在**（`GRADLE:142`）—— 不要再引入第二套加密库。
2. **`EncryptedSharedPreferences.create` 同文件并发不安全**（首次运行 keyset 生成会 race），
   且每次返回不同 wrapper。**必须进程级单例**，否则会出现"一个实例写进密文、另一个实例降级读明文读到 null"。
3. 🔴 **明文降级是当前实现的真实缺口**（`ServerConfigStore.kt:331-334` 只 `Log.w`）。
   Web 宿主**必须**改为：降级时**拒绝持久化凭据**并在 UI 上明说，或至少上报一个可查询状态。
   **明文落盘一律禁止** —— 这是本节的红线。
4. **永远不要用可空的两半拼 `"$username:$password"`** —— 会发出 `base64("user:null")`。
   空口令必须**不发头**（`BasicAuth.kt:27` `?: return null`）。
5. **改密码时标量镜像必须重写**，否则留下 stale `KEY_PASS` 且 `restore` 优先读它。
6. **探针时刻现读，别读缓存字段** —— `A/ServerConfigStore.kt:172-183`：
   "sends no Authorization at all … and the server answers 401. ServerHealthProbe … reports that very server healthy."
7. 凭据**从 URL 里搬出来**（`ServerConfigStore.kt:610-614`：
   > "the credential moves from the URL (which surfaces in logs and the server key) into fields"）。
   `stripStoredUserInfo`（`:655-661`）按 RFC 3986 取**最后一个 `@`**（`:504-521`）。

---

### 3.5 IME 与 insets —— 见 §5（独立大节，本项最重）

原生壳的完整坑位清单在 **§5**。此处只给接口。

```js
window.OpenCodeAndroid.getInsets();
// → {"ime":339,"nav":48,"status":24,"left":0,"right":0,"room":261,"windowH":600,"density":1.0}
// 全部为 dp；room = windowH − ime（**见 §5.4：判据只认 room**）
window.OpenCodeAndroid.insetsChanged(sameShape);   // 原生 → 页面推送
```

---

### 3.6 屏幕常亮 / 后台

#### (a) 原生壳现状

🔴 **关键否定结果：本仓完全没有常亮机制。**
`FLAG_KEEP_SCREEN_ON` / `keepScreenOn` / `setKeepScreenOn` / `android:keepScreenOn` /
`WakeLock` / `PARTIAL_WAKE_LOCK` / `onTrimMemory` / `ProcessLifecycleOwner` / `DefaultLifecycleObserver` / `isChangingConfigurations`
对 `app/src/main`（含 `res/`，大小写不敏感）grep 全部 **No matches found**。
**没有任何 WakeLock 被持有** —— 不存在超时/守护逻辑。

**"回合进行中保持常亮"是本项任务书的假设，原生壳没做。** 见 (d) 坑 1。

前后台切换的既有行为 —— `A/MainActivity.kt:201-209`：
```kotlin
// Battery trio: native SSE down, WebView renderer idle, proxy asleep.
// Existing gateway streams are not killed; EventStream closes itself.
networkWatch?.stop(); chatView.stop(); binding.webView.onPause(); binding.webView.pauseTimers(); gateway.pause()
```
回前台 `A/MainActivity.kt:182-198`：
> "The WebView renderer only wakes on the terminal tab. On chat it must stay parked (renderer + timers paused) or the battery trio in onStop is undone the moment the user returns (review MUST-FIX #3)."
> `if (currentTab == R.id.nav_terminal) { binding.webView.onResume(); binding.webView.resumeTimers() }`

`A/EventStream.kt:214` 另有：`// burns battery. 5xx and 3xx stay retryable.`

**没有"进后台保存工作"的代码** —— `onStop` 是**关停**，不是保存。

**`TurnIdleWatchdog`**（终局帧丢失时把 RUNNING 拉回 IDLE 的第二信使）：
`A/ChatViewModel.kt:7509` `IDLE_GRACE_MS = 10_000L`；`:7518` `IDLE_WATCHDOG_TICK_MS = 4_000L`，
理由 `:7513-7516`（v54 tB 实测 44 s 的 Stop 按钮不消失）。设计理由 `A/TurnIdleWatchdog.kt:29-31`：
> "a long foreground tool wait emits no part traffic at all … so silence alone proves nothing"

#### (b) Web 宿主契约

```js
// #24 回合进行中保持常亮（原生壳没有，Web 宿主新增 —— 但必须做成「回合进行中」而非「全程」）
window.OpenCodeAndroid.setKeepAwake(true | false);   // 内部 = window.addFlags/clearFlags(FLAG_KEEP_SCREEN_ON)

// #25 前后台切换
window.__ocPower.onBackground(reason)   // reason ∈ 'home' | 'recents' | 'split' | 'screenOff'
window.__ocPower.onForeground()
```

- **`setKeepAwake` 的调用纪律（按 (d) 坑 1 收紧）**：
  **仅在"有回合在跑"时 `true`**，回合结束 / 页面隐藏 / 超时（建议 5 min 无输入）立即 `false`。
  **禁止整个 Activity 生命周期常亮。**
- `onBackground(reason)` 页面侧应做的事：停止渲染性动画、暂停轮询、把非必要 WS 降频。
- `onForeground()` 恢复。

#### (c) Android API / 权限

| 项 | 值 |
|---|---|
| 常亮 | `Window.addFlags(LayoutParams.FLAG_KEEP_SCREEN_ON)` / `clearFlags` |
| 视图级替代 | `View.setKeepScreenOn(true)`（仅对该 view 生效，WebView 上更好控） |
| 可见性 | `onPause()` / `onResume()`（`WebView`） |
| **省电关键** | `WebView.pauseTimers()` / `resumeTimers()` —— 只在终端 tab 恢复（`MainActivity.kt:182-198`） |
| 权限 | **无**。`WAKE_LOCK` **不需要**（不要去申请） |

#### (d) 必须照抄的坑

1. 🔴 **原生壳没有 keep-awake，这是设计取舍不是遗漏。** Web 宿主若要加，
   **必须**写成"回合进行中"并在回合结束立即释放 —— 用户对"手机一直亮"的容忍度是负的。
   常亮 + 屏幕不灭 = 电量线性流失，是最容易被投诉的点。
2. **WebView 不可见必须 `onPause() + pauseTimers()`**（`MainActivity.kt:201-209`）。
   ⚠️ 只做 `onPause()` 不够 —— **timer 仍在跑**。`pauseTimers()` 是暂停整个 WebView 的定时器。
   这条被明确标为 review **MUST-FIX #3**。
3. **回前台不要无脑 `resumeTimers()`**：原生的规矩是**只有终端 tab 才恢复**
   （`MainActivity.kt:182-198`），否则上面的省电动作"在用户一回来的瞬间就被撤销"。
4. **后台不要用主 looper 做定时器** —— `A/ChatViewModel.kt:2138-2141`：
   > "The loop runs on `Dispatchers.Default`, not the view-model scope's `Dispatchers.Main.immediate`: a backgrounded process has its main looper suspended, and a delay chained to that dispatcher simply never fires there"
   ⇒ **所有轮询/看门狗必须跑在非主 dispatcher**。
5. **判定"用户还看着吗"不能用 `onStop`**（见 §3.1 坑 4：晚 1.1 s，丢通知）。
   **通知门要用 pause + 焦点 + 5s 宽限**。

---

### 3.7 主题：深浅色 / 跟随系统

#### (a) 原生壳现状

⚠️ **纠正一个易混点**：`A/ui/nav/AppearancePrefs.kt` **不是**主题，它是 tab 布局
（`:25-27` `const val KEY = "appearance.tabLayout"`，值 `VERTICAL`/`HORIZONTAL`）。

主题模式在 `A/SettingsStore.kt`：
- `:420` `PREFS_NAME = "oc_settings"`
- `:424` `KEY_COLOR_SCHEME = "opencode-color-scheme"`
- `:552` `val SCHEMES = setOf("system", "light", "dark")`
- `:526` `val DEFAULT_SCHEME = "system"`
- 写入 `:227` `"settings-color-scheme" -> if (value in SCHEMES) editor.putString(KEY_COLOR_SCHEME, value)`
- 解析 `:161-169`：`"dark"->true / "light"->false / else->systemDark`（`isSystemInDarkTheme()`）

**系统栏极性是反的，且是刻意的** —— `A/ui/theme/OcSystemBars.kt:14-16`：
> "dark content → light icons (false), light content → dark icons (true)"
`OcSystemBars.kt:10-13` 记 v40 `tD_20_dark` 缺陷：dark 图标压在 dark 对话框面上，时钟/信号/电池不可读。

**不重建 Activity** `A/OcActivity.kt:202-205`：
> "including a runtime switch from the settings dialog (the manifest declares `configChanges=uiMode`, so the activity is not recreated on a system dark⇄light flip either — **the value must be observed, never captured**)"

`MANIFEST:75-79`：
> "`uiMode` is here for the same reason (theme follow, `SystemBarIconAppearance`)."
`configChanges="orientation|screenSize|screenLayout|keyboardHidden|smallestScreenSize|uiMode|locale|layoutDirection"`

**WebView 壳的颜色链路**：`RES/.../SystemBarsJs.kt:4-6` 观察
`data-color-scheme` / `meta[name=theme-color]` / `prefers-color-scheme`
→ `OpenCodeAndroid.setColorScheme`（`OpenCodeAndroidBridge.kt:53-56`）
→ **唯一消费者** `A/MainActivity.kt:477-480`：
```kotlin
onColorScheme = { dark -> webColorSchemeApplied = true; runOnUiThread { applySystemUi(dark) } },
```
配套 `A/MainActivity.kt:396-399`：
> "// Until the WebView reports its own color-scheme, follow system night."
背景色常量 `A/SystemUiAppearance.kt:13-14`：`LIGHT_BACKGROUND = 0xFFFAFAFA` / `DARK_BACKGROUND = 0xFF080808`。

**主题 XML**：`RES/values/themes.xml` 用 `Theme.Material3.DayNight.NoActionBar`，头部注释：
> "Material3 baseline defaults ship a lavender/purple palette … The official OpenCode UI is strictly monochrome"
🔴 **无 `android:enableEdgeToEdge`**（三个 theme XML 都没有，否定结果）—— edge-to-edge 全在代码里：
`A/MainActivity.kt:89` `enableEdgeToEdge()` + `:135` `WindowCompat.setDecorFitsSystemWindows(window, false)`。
`:133-134` 理由：
> "// Do not hide system bars. HyperOS immersive overlays have been observed swallowing every subsequent touch while chrome still paints."

#### (b) Web 宿主契约

- **#9 `setColorScheme(scheme)`** 已有，语义不变。**页面在 `html[data-color-scheme]` 上切换时必须调用**。
  页面侧注入可直接复用 `A/SystemBarsJs.kt:9-58`（纯 JS，无 Kotlin 依赖）。
- **页面主题状态要持久化到原生**，key 沿用 `opencode-color-scheme` / 值域 `{"system","light","dark"}`
  （与 `A/SettingsStore.kt:424, 552` 兼容，便于新壳与旧设置互读）。
- **`system` 模式的解析必须每帧观察**（`A/OcActivity.kt:204-205`：
  "the value must be observed, never captured"）—— 不许在启动时 capture 一次。
- 系统栏图标极性：**深色内容 → `isAppearanceLightStatusBars = false`**（照抄 `OcSystemBars.kt:14-16`）。

#### (c) Android API / 权限

| 项 | 值 |
|---|---|
| 深色判定 | `Configuration.uiMode & UI_MODE_NIGHT_MASK`；Compose 侧 `isSystemInDarkTheme()` |
| 边到边 | `enableEdgeToEdge()` + `WindowCompat.setDecorFitsSystemWindows(window, false)` |
| 图标极性 | `WindowInsetsControllerCompat.setAppearanceLightStatusBars/NavigationBars`（**语义是"浅色栏"**） |
| **禁止** | `WindowInsetsControllerCompat.hide(systemBars())` —— HyperOS 会吞触摸（`MainActivity.kt:133-134`） |
| 权限 | 无 |

#### (d) 必须照抄的坑

1. **`isAppearanceLightStatusBars = true` 意为"浅色栏 + 深色图标"**。
   深色内容必须置 **`false`** —— 否则 v40 `tD_20_dark` 状态栏不可读。
2. **主题切换不能重建 Activity**。靠 `configChanges=uiMode`（`MANIFEST:79`）+ 每帧 `SideEffect` 观察式赋值。
   若新壳允许重建，用户会丢失"正在编辑的设置弹层与选中的 tab"。
3. **不要隐藏系统栏**（`MainActivity.kt:133-134` 实测 HyperOS 吞触摸）。
4. **WebView 自报优先于系统夜模式**：在收到页面第一次 `setColorScheme` 之前跟随系统
   （`MainActivity.kt:396-399` 的 `webColorSchemeApplied` 标志）。
5. **默认跟随系统**（`DEFAULT_SCHEME = "system"`，`A/SettingsStore.kt:526`）。

---

### 3.8 配对 / 登录

#### (a) 原生壳现状 —— 契约逐条确认（两条假设**一处被推翻**）

**A. 服务端探测 —— ✅ 确认，且可补一条精细化**

`A/ui/servers/ServerHealth.kt:38`：
```kotlin
const val V2_INFO_PATH: String = "/api/info"
```
超时 `A/ui/servers/ServerHealth.kt:41` `const val HEALTH_TIMEOUT_MS: Long = 3_000L`，
在 `:273-276` 同时施加 `connectTimeout` / `readTimeout` / `callTimeout`。
理由 `A/ui/servers/ServerHealth.kt:25-29`：
> "one `GET /api/info` with a hard per-request budget … **no** retry loop: a dead host must not hold a settings open for 30 s"

**版本前缀判定（逐字）** `A/v2/V2Capability.kt:598-599`：
```kotlin
if (!version.startsWith("2."))
    … "version $version is not a 2.x server"
```
规则复述 `A/v2/V2Capability.kt:14`：
> "Probe rule: `GET /api/info` → JSON with `version` starting with `"2."`"

**六种内部失败原因**（`A/v2/V2Capability.kt:569-600`）：
401/403 → `authWall`；`statusCode != 200` → `"HTTP $statusCode, not a v2 info response"`；
`text/html` 或 body 以 `<` 开头 → `"text/html — the SPA shell, so /api/info is not served"`；
非 JSON → `"body is not JSON"`；无 `version` → `"JSON without the \`version\` marker"`；版本不符 → 上句。

🔴 **但引导页把这六种全压成一句话** —— `A/ui/servers/ServerHealth.kt:150-157`：
```kotlin
val info = V2Capability.decideInfo(200, contentType, body)
```
`healthBody` 只保留 V2/UNKNOWN 布尔，non-V2 一律 `unhealthy(HealthFailure.InvalidResponse)`
→ `strings.xml:826` **"Something answered, but it is not an OpenCode server."**
⇒ **用户看不到"你的服务器是 1.18"这个可行动事实。** 已知账：
`research/v97_fix/SERVER_PROBE_CONTRACT.md:466`「`incompatible` 已就位但**引导页当前未接**」。

**B. PATH 上必须是 v2 可执行文件 —— 🔴 作为 app 级门被推翻**

**app 里根本没有远程二进制探针。** `app/src/main/java` 内 grep
`command -v|sshpass|setCommand|exec|--version|Process(` 无命中；
`SshConnection.kt` / `SshConnectionModel.kt` 中 "version" **零出现**。
SSH 路径是 **JSch 隧道**（`A/ui/servers/SshConnection.kt:106` `val jsch = JSch()`），不是版本检查。
`SshErrorCode.VERSION`（`A/ui/servers/SshModel.kt:207` → `ssh_error_version`，`:221`）存在，
但**未找到构造点**（见 §6 未核对）。

**"v2 二进制"是宿主机纪律，不是壳的契约** —— `research/v97_fix/SERVER_PROBE_CONTRACT.md:255-256`：
> ★ **根因一句话**：这台机器上 **PATH 里的 `opencode` 是 npm 的 1.18.33**… 一个**根本不认识 `/api/info` 这个 v2 路由**的旧版。
（`:246` 记 `opencode --version` 解析到 npm shim 输出 `1.18.33`）

⇒ **正确的因果链**：PATH 上是 v1 ⇒ 该进程不提供 `/api/info` ⇒ **同一个 HTTP 探针**自然判它 `InvalidResponse`。
壳唯一认识的判据就是 `/api/info` 上的 `version` 前缀 `2.`。

**C. 连接认证 —— ✅ 用户名确认；口令来源需修正**

`A/BasicAuth.kt:34`：
```kotlin
internal const val DEFAULT_BASIC_USERNAME = "opencode"
```
`:33` 注释：`/** Upstream hardcodes this user for HTTP basic auth. */`
理由 `A/BasicAuth.kt:10-13`：
> "the username is the literal `opencode`, not a user-supplied field. With no password, upstream sends **no** `Authorization` header at all"

🔴 **它是一个"默认值"，不是硬锁**：非空的已存 username 会赢
（`A/ServerConfigStore.kt:32-33` "A stored username is read when present … a blank one falls back to `opencode`"）。
且**真机 a11y dump 证实引导页没有 Username 框**（`research/HANDOFF.md:255`：
「引导页只有两个框 …… **没有 Username 框** ⇒ `CONNECT_AUTH_CONTRACT.md` §1.3 成立」）。
连接页也确实发 `username = null`（`A/ui/servers/ConnectServerScreen.kt:122`）。

🔴 **口令来源要修正**：**app 从不读 `OPENCODE_SERVER_PASSWORD`，也不用 `sshpass`**，口令来自表单/扫码。
`OPENCODE_SERVER_PASSWORD` 是**服务端** env var，由 `opencode serve` 消费、`opencode pair` 反射出来
（`research/v97_fix/SERVER_PROBE_CONTRACT.md:250` `口令只走 env OPENCODE_SERVER_PASSWORD`；
`:253` `不能：显式设 OPENCODE_SERVER_PASSWORD='' 仍自动生成新口令`）。

**D. 凭据抵达 App 的完整步骤**

| # | 动作 | file:line |
|---|---|---|
| 1 | 用户输入地址+口令 | `A/ui/servers/ConnectServerScreen.kt:206-233` |
| 2 | 或扫码 | `:274` `scanLauncher.launch(QrScan.scanIntent())` |
| 3 | 扫码结果取串 | `A/ui/servers/QrScan.kt:58-59` `data?.getStringExtra(EXTRA_SCAN_RESULT)` |
| 4 | 解析配对码 | `ConnectServerScreen.kt:143` → `A/ui/settings/PairingModel.kt:68-69` `decodePairingScan` |
| 5 | 支持 4 种载荷形状 | `A/ui/settings/PairingModel.kt:118-125`：完整 `/connect` 链 / `?data=<json>` / `#<base64url>` / 裸 JSON |
| 6 | 校验 username 必须字面等于 `opencode` | `PairingModel.kt:77` `if (obj.optString("username") != PAIRING_USERNAME) return null`；`:33` `PAIRING_USERNAME = "opencode"` |
| 7 | 必须有 `password` | `PairingModel.kt:78` `if (!obj.has("password")) return null` |
| 8 | 自动填充 + 自动提交 | `ConnectServerScreen.kt:150-153` `url = pairing.urls.first()` / `password = pairing.password` / `submit()` |
| 9 | 探针前解析凭据（回退到已存口令） | `A/ui/servers/ServerHealth.kt:66-70` |
| 10 | 探测 `GET /api/info` | `ServerHealth.kt:38, 240` |
| 11 | 成功后落盘 | `ConnectServerScreen.kt:131-134` `store.replaceAll(...)` |
| 12 | 换壳 | `:81-83` "the store revision … re-evaluates the gate in `ui/App.kt` — the page replaces itself with the shell." |

**E. 版本门只在会话路由上全屏拦截**

`A/v2/V2Capability.kt:521-522`：
```kotlin
val incompatibleVersion: String? get() = version?.takeIf { generation != ServerGeneration.V2 }
```
理由 `:509-520`（B-7：answered `/api/info` with a real OpenCode version that is not 2.x）。
挂载点**只有一处** —— `A/ui/session/SessionScreen.kt:2820`（`if (incompatibleServerVersion != null) {`）
→ `:2825` `IncompatibleServerPanel(`，在 not-found 分支**之前**。
用户看到 `IncompatibleServerPanel`（告警图标 + 版本号 + 返回/重试，
`A/ui/session/IncompatibleServerPanel.kt:88-93, 132-141`）。
⚠️ `A/v2/V2Capability.kt:61-66`：约 100 行的 `COVERAGE` 覆盖表是**文档表，运行时不查**。

#### (b) Web 宿主契约 —— 怎么把它交给页面

**这是本节的核心设计要求：把"两条契约"变成页面的状态机，而不是散落的字符串。**

```js
// 页面侧：引导流程只需三个状态
window.__ocConnect = { onStep(cb) {} };   // cb({step, status, detail})
```
`#20 probeServer(url, password)` → 原生异步探测 → `#21 probeServerResult(token, status, detail)`，
**`status` 建议按原生壳的六种失败原因细分**（比原生壳当前的引导页更细，因为已知账要求这么改）：

| status | 触发 | detail | 建议文案方向 |
|---|---|---|---|
| `v2` | 200 + JSON + `version` 以 `2.` 开头 | `version` | 成功 |
| `http` | 非 200 | `statusCode` | 状态码 |
| `unauthorized` | 401/403 **且已发凭据** | — | "口令被拒；或服务端已重跑 `opencode pair`" |
| `noauth` | 401 且**未发凭据** | — | **必须与上条区分**（坑 1） |
| `timeout` | 3s 预算耗尽 | — | 超时 |
| `unreachable` | `IOException` | — | 连不上 |
| `notjson` / `spa` | body 非 JSON / 是 SPA HTML | — | "这个地址返回的是网页，不是 OpenCode 服务器" |
| `badversion` | 有 `version` 但非 `2.` | `version` | **"服务器是 X，请升级到 V2"**（当前壳缺这句） |

- **用户名**：页面**不渲染 Username 输入框**（对齐 `research/HANDOFF.md:255` 的真机结论）。
  固定用 `opencode`；`setServerConfig` 时可省略 `username`，由原生按 `BasicAuth.kt:34` 兜底。
- **口令**：用户在页面输入 → `#19 setServerConfig({url, password})` → **明文只存在于原生层**（§3.4）。
- **前端不实现版本判定**。`version` 前缀 `2.` 的判定**只在原生**做（单一真相源），
  避免页面与原生壳两套实现漂移 —— 这正是原生壳 `V2Capability.kt:14` 把规则写成一条常量的理由。

#### (c) Android API / 权限

| 项 | 值 |
|---|---|
| 探测 | `okhttp3`（`GRADLE:121` `okhttp:4.12.0`），`Request.Builder().url(url).get()`（`ServerHealth.kt:240`） |
| 预算 | `connectTimeout` / `readTimeout` / `callTimeout` **三者都设**（`ServerHealth.kt:273-276`） |
| 基本认证 | `Basic base64("opencode:<password>")`（`A/BasicAuth.kt:26-31`） |
| 扫码 | `CAMERA`（`MANIFEST:16`，`required="false"` 特性 `MANIFEST:17`）→ `zxing:core` |
| 网络 | `INTERNET`（`MANIFEST:4`）、`ACCESS_NETWORK_STATE`（`MANIFEST:9`） |
| 清文流量 | `android:usesCleartextTraffic="true"`（`MANIFEST:65`）+ `networkSecurityConfig`（`MANIFEST:61`）—— 局域网 `http://` 必需 |
| 权限申请 | **无运行时权限**（网络 + 扫码除外） |

#### (d) 必须照抄的坑

1. 🔴 **`401` 必须二分**（`A/ui/servers/ServerHealth.kt:138-141`）：
   > "`401` is two different stories and must not share a sentence"
   血案：探针不带 `Authorization` → 401 → 被报成"**密码被拒**"（`ServerHealth.kt:50-55`，
   三设备 logcat 见 `A/ChatViewModel.kt:1560-1567`）。`#20/#21` 的 `noauth` / `unauthorized` 二分就是为此。
2. **空口令 ⇒ 完全不发 `Authorization` 头**（`BasicAuth.kt:27` `?: return null`）。
   空口令服务端是合法配置（`A/ui/servers/ServerConnectModel.kt:63-66`），**不能因此判死**。
3. **`callTimeout` 抛的是 `InterruptedIOException` 而不是 `SocketTimeoutException`**
   （`ServerHealth.kt:254-257`）—— **两个 catch 都要写**，否则超时会被误报成"不可达"。
4. **改密码时探针要用已存的旧口令**（`ServerHealth.kt:66-70`），
   否则编辑一条不改密钥的记录**必然 401**。
5. **不要在页面实现版本判定**，也不要把六种失败压成一句
   （`ServerHealth.kt:155` 当前的缺陷；账在 `SERVER_PROBE_CONTRACT.md:466`）。
6. **"PATH 上必须是 v2 可执行文件"是宿主机纪律，不是客户端契约。**
   客户端唯一认识的是 `/api/info` 上的 `version` 前缀 `2.`。文档/UI 里**不要**把它写成客户端要求，
   否则会去实现一个不存在的远程探针。
7. **`V2Capability.COVERAGE` 是文档表，运行时不查**（`V2Capability.kt:12, 61-66`）。按它改行为会走偏。
8. **不兼容的拦截是"进会话后全屏"，不是"启动即拒"**（`SessionScreen.kt:2821-2832`）。
   Web 宿主若改成启动即拒，是**行为变更**，需裁定。

---

### 3.9 省电与低延迟：已知取舍（后续优化的靶子清单）

#### (a) 原生壳现状（已量化的部分）

**冷启动 / 包体** —— `research/HANDOFF.md:233`：
> "**④ 冷启动性能**：量化闭环 —— 44MB 里 **7,426,218 B（16.4%）是 zipalign 页对齐填充**（104 个 STORED 条目各带 65,505 B extra field）；**bcprov 不在首帧路径、不是大头**；真凶是 **debuggable 无 AOT + 首帧前 8 个同步 `remember{}`**。已落两项低风险改动（Picnic 常数表排除、`NetworkRecoveryWatch` 移出首帧）。**要真压 44MB** 需动 STORED 条目集合（改安装语义）或走 **release 侧 R8 + baseline profile**（AGP 不把 profile 用于 debuggable）。"

**短窗预算实测** —— `A/ui/ShellChromeBudget.kt:12-17`（KDoc 表，1440x810@420dpi ≈ 308.6dp 窗口，
`research/v57_verify/tE`）：
`no IME: maxHeight 146.67 dp / chrome ceiling 30.67 dp / composer(stacked) 96.00 dp / timeline viewport 50.67 dp`
；`IME up: maxHeight 33.90 dp ← the composer is the entire pane`。

**已识别的自反馈震荡** —— `A/ui/ShellChromeBudget.kt:41-47`：
> "Read from the **configuration**, never from a measured layout … the v57 build measured the composer alternating between 56 and 116 dp several times a second until it was moved here."

#### (b)(c) WebView 上的劣势点与缓解手段（靶子清单）

| # | 劣势点 | 机理 | 可用缓解 | 证据/锚点 |
|---|---|---|---|---|
| 1 | **输入延迟** | WebView 输入经 `JavaBridge`/渲染进程往返；原生是直接 `onValueChange` | composer 走 **native 视图 overlay** 而非 Web 输入框；或 `inputmode` + 不做受控重渲染 | `OcKeyboard.kt:7-27` 记录了输入层的实测教训（见坑 3） |
| 2 | **受控重渲染杀延迟** | 每次 keystroke 触发 Web 布局 | composer 内容**不绑 React/Vue 状态**；用 uncontrolled + ref | `research/v75_ime_space/REPORT.md:58-66`：原生链是**逐字节直通**（源码级证明） |
| 3 | **IME 批量重写** | Gboard 拼音布局会重写批量注入的文本 | 与本项无关但同源：中文布局下文本可能被改写 | `research/v75_ime_space/REPORT.md:129` 记 `opencode`→`open code`、`http://127.0.0.1:4095`→`htt//1..7.001:495` |
| 4 | **IME 重启窗口** | App 侧**主动**写回（粘贴/插入/清空）触发 `restartInput()` | 打字期间**禁止**任何主动写回 | `research/v75_ime_space/REPORT.md:135` 明确列为残余风险 |
| 5 | **渲染进程常驻耗电** | WebView 渲染进程独立且常驻 | `onPause() + pauseTimers()`，且**只在需要时 resume** | `A/MainActivity.kt:182-209`（MUST-FIX #3） |
| 6 | **首帧慢** | 44MB 包 + 无 AOT | release 侧 **R8 + baseline profile**；首帧前不要同步 `remember{}` | `research/HANDOFF.md:233` |
| 7 | **布局自反馈震荡** | 用实测高度做预算 → 高度变了 → 预算变了 | **只从 `Configuration` 读** | `A/ui/ShellChromeBudget.kt:41-47`（56↔116 dp 实测抖动） |
| 8 | **后台定时器照跑** | JS timer 不随页面隐藏停 | `pauseTimers()` + 页面侧 `onBackground` 停轮询 | `A/MainActivity.kt:201-209` |
| 9 | **常亮** | 若为体验加常亮则线性耗电 | 回合级开关 + 5 min 无输入自动释放（§3.6 坑 1） | 本仓无 keep-awake（否定结果） |

**没有量化数据的一律标未核对**（见 §6）：WebView 输入延迟的具体毫秒数、
`pauseTimers()` 的实际省电百分比，本仓**无测量**，不要编。

---

## 4. JS ↔ Kotlin 注入点与时序（实施要点）

```
页面加载完成
  ├─ evaluateJavascript(AndroidBackJs.INSTALL)        // 装 __ocAndroidHandleBack
  ├─ evaluateJavascript(SystemBarsJs.INSTALL)          // 装 __ocSystemBars
  ├─ evaluateJavascript(NativeWebSocketJs.INSTALL)     // 猴补 window.WebSocket
  └─ 首帧：页面调 #11 notifyPermissionState() / #23 getInsets() / #18 getServerConfig()
```

⚠️ **三段 JS 都必须"幂等安装"**（它们自带守卫：`AndroidBackJs.kt:106`
`if (typeof window.__ocAndroidHandleBack !== 'function') { $INSTALL }`；
`SystemBarsJs.kt:11` `if (window.__ocSystemBars) return;`；
`NativeWebSocketJs.kt:14` `if (window.__ocNativeWS) return;`）——
**页面每次导航后都要重装**，因为守卫在新的 JS 上下文里会重置。

⚠️ **`NativeWebSocketJs` 只接管 loopback** —— `NativeWebSocketJs.kt:18-28` 的 `loopback(url)`
只匹配 `ws:`/`wss:` + `127.0.0.1`/`localhost`/`::1`；其余 `return new Native(url, protocols)`（`:84-86`）。
理由 `:7-9`：
> "`https://appassets.androidplatform.net` → `ws://127.0.0.1` is mixed content on older System WebView builds even with `MIXED_CONTENT_ALWAYS_ALLOW`. Native OkHttp is not subject to that check; LocalGateway still tunnels."

---

## 5. IME + insets 坑位清单（本仓最贵的一节）

### 5.1 `show_ime_with_hard_keyboard` —— 作用与用法

**它不是应用能设的开关，是一个 `Settings.Secure` 全局项，必须用 adb 显式设置。**

| 项 | 值 |
|---|---|
| 设置路径 | `settings put secure show_ime_with_hard_keyboard 1` |
| 读法 | `settings get secure show_ime_with_hard_keyboard` |
| 作用 | 允许**硬键盘存在时也弹出软 IME** |

**本仓的用法（`research/v97_verify/5554_md/REPORT_MONOTONE_FORM2.md:55`）**：
> "本轮按要求 `settings put secure show_ime_with_hard_keyboard 1`，并在**每一级**读取 `dumpsys window` 的 `type=ime frame=` 原始行。"

**关键事实**（`research/v97_verify/5554_md/REPORT_IME_STEP_CAUSE.md:310`）：
> "`show_ime_with_hard_keyboard` | **改动前读到 `1`；本轮全程未修改**（未用 `settings put`），现仍为 **`1`** ✅"

⇒ **不设它 = 测不到 IME**。本仓的 IME 曲线全部在 `=1` 下测得。
🔴 **Web 宿主注意**：这是**测试环境的必要条件**，不是产品需求；
但它同时也意味着 —— **本仓所有 IME 布局结论都只在「软键盘会弹」的前提下成立**。
设备若为纯硬键盘（该 secure 项为 0），§5.4 的判据**整个不适用**。

### 5.2 `dumpsys window insets` 的正确读法

🔴 **头号坑：这个命令不存在。** `research/v97_verify/5554_md/REPORT_FINAL_VERIFY.md:336-346`：
```
$ adb -s emulator-5554 shell dumpsys window insets
Bad window command, or no windows match: insets
Use -h for help.
```
⇒ **insets 原始行在 `dumpsys window` 的输出正文里**（`dumpsys window displays` 同源）。
`research/HANDOFF.md:264` 同样记录：「**不是合法子命令**（`Bad window command`）」。

**可用的原始行**（`research/v97_verify/5554_md/REPORT_MONOTONE_FORM2.md:57-58`）：
```
InsetsSource id=3 type=ime frame=[0,342][1366,768] visible=true
```

**各字段读法**：

| 字段 | 含义 | 读法 |
|---|---|---|
| `type=ime` | 键盘源 | `imeH = frame.bottom − frame.top`（**frame 永远贴显示底边**） |
| `frame=[l,t][r,b]` | 窗口坐标系矩形 | 见下方恒等式 |
| `visible=true` | 当前可见 | — |
| `mDisplayFrame` | 旧式显示区 | `contentTopInsets = mDisplayFrame.top`；`imeH = H − contentTopInsets` |
| `navigationBars` | 导航条 | 底 inset 读数（如 768 档为 `8`，见 §5.5） |
| `nonDecorFrame` | 去装饰区 | 区分"整窗"与"内容区"，判断窗口是否被 IME 裁短 |
| `Frames` / `frame=` | app 窗口矩形 | 判"系统裁没裁窗口"的决定性证据 |

**两个必查项**（`research/v97_fix/DIVERGENCE_LOCATE2.md:202-204`）：
```powershell
# ① 768 档 navigationBars 底 inset 的真值
adb shell dumpsys window insets | grep -iA3 navigationBars     # ← 该子命令无效，改用 dumpsys window
# ② ime.getBottom() 的真值 + 键盘实际顶边是否一致
adb shell dumpsys window | grep -iA3 ime                       # bottom
adb shell dumpsys input_method | grep -iA2 mCurFrame            # top
```
⚠️ 注意 `DIVERGENCE_LOCATE2.md:202-203` 里也写了 `dumpsys window insets` —— **那两行命令是错的**，
以 `REPORT_FINAL_VERIFY.md:336-346` 的纠正为准（该 −8px 归因因此至今**未闭环**）。

**恒等式（每级都验过）** —— `research/v97_verify/5554_md/REPORT_IME_STEP_CAUSE.md:73-77`：
```
imeH  =  Gboard content  +  48        （48 = 导航条，Gboard 自己画，mImeDrawsImeNavBar=true）
Gboard content = touchableRegion.bottom − contentTopInsets
imeH  =  H − contentTopInsets         （ime frame 永远贴显示底边）
```

### 5.3 IME 高度会随窗口变矮而塌缩（`426 → 339 → 261`）

**实测主曲线**（density 160，`research/v97_verify/5554_md/REPORT_IME_STEP_CAUSE.md:45-69` 原文抄录）：

| H | ime 原始行 | imeH | app 可见区 |
|---:|---|---:|---:|
| 768 | `frame=[0,342][1366,768]` | **426** | 342 |
| 660 | `frame=[0,234][1366,660]` | 426 | 234 |
| 601 | `frame=[0,175][1366,601]` | 426 | 175 |
| **600** | `frame=[0,261][1366,600]` | **339** | 261 |
| **599** | `frame=[0,338][1366,599]` | **261** | 338 |
| 598 | `frame=[0,337][1366,598]` | 261 | 337 |
| 560 | `frame=[0,299][1366,560]` | 261 | 299 |

**塌缩点是 `600 → 599`，单个 dp，掉 78 px**（`REPORT_MONOTONE_FORM2.md:92`）：
> "窗口 600 → 599 的**单个 dp**，ime 从 339 塌到 261（**−78px**），于是 `room` 从 261 **暴涨到 338（+77px）**。"

**三个值是 Gboard 选的三种键盘形态，不是系统裁出来的** —— `REPORT_IME_STEP_CAUSE.md:79-81`：
> "三级台阶对应的 **Gboard content 是三个 dp 常量**：`378dp / 291dp / 213dp`。`dumpsys window` 的 `mImeHeight` 同步为 `378 / 291 / 213`，`mInsetsHint` 同步为 `426 / 339 / 261`。**换句话说：`426/339/261` 不是系统裁出来的尺寸，而是 Gboard 选的三种键盘形态。**"

**定因（v160，`:15-31`）**：判定 **(a) Gboard 自己收行**。排除反馈环的决定性证据 `REPORT_IME_STEP_CAUSE.md:206-213`：
> | H | ime 顶边 | app 窗口 `frame` | `insetsChanged` |
> | 768 | 342 | `frame=[0,0][1366,768]` ← **整屏高** | false |
> | 600 | 261 | `frame=[0,0][1366,600]` ← **整屏高** | false |
> | 599 | 338 | `frame=[0,0][1366,599]` ← **整屏高** | false |
>
> "**app 窗口在 ime 顶边是 261 / 338 的情况下，frame 依然是整屏高，一像素都没被裁短。**"

方向检验 `:217-223`：
> | 量 | H=600 | H=599 | 变化 |
> | app 可见区（= ime 顶边） | 261 | 338 | **+77** |
> | ime 高度 | 339 | 261 | **−78** |
>
> "**窗口矮了 1px，app 的可用区反而大了 77px，ime 却塌了 78px。**"

**密度对照（推翻"只看像素"）** `REPORT_IME_STEP_CAUSE.md:236-250`：
| density | H=620 | H=600 | H=599 |
|---:|---:|---:|---:|
| 160 | 339 | 339 | **261** |
| **120** | **332** | **332** | **332**（content 恒 378.7 dp，**不塌缩**） |

> "**触发条件是 Gboard 自己那份「屏幕高 − 导航条」的 dp 预算**，与 app 无关。"
Gboard 原文 `REPORT_IME_STEP_CAUSE.md:112-114`：
> "**关键原文**：`update screenHeightWithoutNaviBar to: 551` —— Gboard 自己声明它的高度预算是
> **「屏幕高 − 导航条」= 599 − 48 = 551** … **这个数不是 app 窗口的高度**（H=599 时 app 可见区只有 338）… **它是屏幕尺寸算出来的。**"

🔴 **迟滞（路径依赖）** `REPORT_IME_STEP_CAUSE.md:159-171`：
| 路径 | 结果 |
|---|---|
| A：768 → 599（直落） | 261 |
| B：768 → 600 → 599 | 261 |
| C：599 → 600 → 599 | 261 |
| D：599 → 600（回升） | **339** |
| **H=620 自 768 下降** | **426** |
| **H=620 自 560/599 上升** | **339** |

> "**426/339 这一阶有迟滞**：同一 H=620，从上往下是 426，从下往上是 339。"

### 5.4 ⇒ 判据必须取「可用房 room」，不是窗口高

**这是本节最重要的一条。**

**现象** —— `research/v97_verify/5554_md/REPORT_MONOTONE_FORM2.md:15-16, 24`：
> "**ime 是否随窗口变矮而变化** | 🔴 **是，实测变化：426 → 339 → 261**"
> "**逐级形态单调性（真实条件）** | 🔴 **FAIL：600 → 599 出现回翻**（ONEROW → STACKED）"
> "**600 → 599 出现回翻：ONEROW → STACKED**（形态变大，而窗口变矮）。3 遍重复结果**完全一致**，非抖动"

**裁定（v158）** —— `research/v97_fix/MONOTONE_JUDGE_FIX.md:19`：
> "**判据的自变量 = `room`（`box − ime`，即 composer 真正被递给谓词的那个量），`H` 不得作判据。**"
`:22`：`v157 实测已钉进断言：ime 随窗口塌缩 **426 → 339 → 261**，塌缩点 **600 → 599**（单 dp，掉 78px）`
`:23`：**反向验证** —— 把 ime 退回 v156 的「常数 426」前提（＝「拿 H 当判据」的隐含假设）⇒ **2/5 真红**。

**固定 ime 时形态确实单调** `REPORT_MONOTONE_FORM2.md:150-153`：
```
room=194 ONEROW / 234 ONEROW / 261 ONEROW / 274 STACKED ← 阈值落在这里 / 299…342 STACKED
=> form vs room 单调不增: True
```
阈值 `room ∈ (261, 274]`，与源码 `composerCompact` 的 224 dp 阈值一致
（`A/ui/session/SessionPaneLayout.kt:786-787`）。

🔴 **伪断点教训** `REPORT_MONOTONE_FORM2.md:177-179`：
> "**不是。断点是 600/599 之间的 ime 塌缩边界。** 588 是 v156 在"ime 恒为 426"假定下用 `room = H − 426` 算出来的**伪断点**：真机上 588 处的 ime 早已塌到 261，`room` 是 **327** 而非预测的 162"
> "断点之所以"看起来"在 588，是 426 这个常数恰好落在 Gboard 某个梯级上造成的巧合。"

**⇒ 给 Web 宿主的硬性要求**：

```js
// ✅ 正确：判据只用 room
const room = windowHdp - imeDp;        // 判据自变量
// ❌ 错误：任何"随窗口高单调"的判据都是错的
if (windowHdp < 600) { /* ... */ }
```
并且**阈值也要跟着 density 换算** —— 因为 426/339/261 是 **dp 常量 × 密度**（§5.3 密度对照表）。

### 5.5 48dp 语义框会把 a11y / dump 侧尺寸撑大

**核心命题**：**生产布局值 ≠ dump 语义值。别拿 dump 当布局真相。**

**机制** —— `research/v97_fix/LEDGER_REWRITE_AND_USABILITY.md:64-68`：
> | 权威侧 | **生产求值**：`unionBottomPx = max(editorTop+editorH, toolRowTop+toolRowH) = max(14+20, 0+48) = 48`，**探针直读** |
> | dump 侧 | **按 allowance 准入**，而 allowance **从帧里推导**，不是字面量 |
> | allowance 定义 | `allowance = (innerLayoutBottom − productionUnionBottomAbs) + expansionDown` = `(249−262) + 14` = **1** |
> | 方向 | **只能向下**（语义 ⊇ 布局 ⇒ `dump ≥ production`） |
> | 上界 | `0 ≤ gap ≤ expansionDown`（不得超过它来源的扩张量） |

**撑大的具体量** —— `LEDGER_REWRITE_AND_USABILITY.md:108-113`：
> "实测 **1**，消息里写明了「准入是**临时**的：dump 的语义并集仍越过宿主盒底沿 **2px**（263 vs 261），
> **『生产无缺陷』是几何命题，不是可用性结论**」"
> "旧账红在「我以为跑的是别的代码」（假），新账红在「**语义框确实比布局框大**，而这 2px 越界还没在设备上解决」（真）。"

**派生语义框的扩张量** —— `LEDGER_REWRITE_AND_USABILITY.md:243`：
> "`Open terminal [1320,192][1366,240]` 底沿 240 与派生语义框底沿 `semBot = 229+10 = 239` 差 1px"

**后果：1px 分类边界 = 94% 遮挡** `LEDGER_REWRITE_AND_USABILITY.md:247-250`：
> "一旦这 1px 翻面（`Open terminal` 被纳入 `actions`），就会暴露 —— `Open terminal` 的语义框 46×48
> 有 **44×47 = 94%** 压在 `Send` 上（`v137` 帧是 16px 宽、45.6% 压在 `Stop` 上）。
> **1px 分类边界和 94% 遮挡是同一个几何事实的两面。**"

**⇒ 给 Web 宿主的硬性要求**：
- **48dp 语义框的达标线要在「派生语义框」上判，不在布局框上判**（`LEDGER_REWRITE_AND_USABILITY.md:183-189` 五项表：
  ① 语义框高 ≥48 ② 语义框宽 ≥48 ③ 与同 composer 其它动作互不重叠 ④ 落在可写区内 ⑤ 不被外来 48dp 靶遮挡）。
- **已知未达标项**（`LEDGER_REWRITE_AND_USABILITY.md:232`）：
  > "② 宽 ≥ 48dp 全线不达标：`Add` 三形态均 **42**；`Open terminal` **16 / 46 / 46 / 46**。**没有一个形态的 `Add` 达到 48 宽**"
  —— **这是原生壳自己也没解决的问题**，Web 宿主不要以为抄一下就达标。
- ⚠️ **WebView 上语义框来源不同**：DOM 元素的 a11y 框由 Chromium 的 `AccessibilityNode` 计算，
  **不一定等于 `getBoundingClientRect()`**。因此上面的 48dp 判据在 WebView 上
  **必须用 `getBoundingClientRect()` 判布局，用 a11y dump 判语义，两者分别记账** ——
  这正是本节命题的直接推论。

### 5.6 边到边 + `WindowInsets` 的正确读法

**边到边是代码里做的，不是 theme XML**（§3.7 (a)）：
`A/MainActivity.kt:89` `enableEdgeToEdge()` + `:135` `WindowCompat.setDecorFitsSystemWindows(window, false)`。

**窗口不裁的证据** —— `research/v97_verify/5554_md/REPORT_IME_STEP_CAUSE.md:194-202`：
```
mAttrs={... sim={adjust=resize} layoutInDisplayCutoutMode=always ...
  pfl=NO_MOVE_ANIMATION FORCE_DRAW_STATUS_BAR_BACKGROUND USE_BLAST APPEARANCE_CONTROLLED FIT_INSETS_CONTROLLED ...}
```
> "`mAttrs` 里**同时**有 `sim={adjust=resize}` **和** `pfl=... FIT_INSETS_CONTROLLED`。
> 后者意味着窗口走 edge-to-edge：**insets 派发给 app，由 app 自己排版，系统不裁窗口。**"

**`adjustResize` + `configChanges`** —— `MANIFEST:79` `configChanges` 含 `keyboardHidden`，
`MANIFEST:83` `android:windowSoftInputMode="adjustResize"`（`MainActivity` 同样，`MANIFEST:109`）。

**正确读法（对齐 `believedImeBottomPx` 的四个入参）** ——
`A/ui/session/SessionPaneLayout.kt:1622-1631` 注释：
> "All four arguments are px in the **window's** frame, and the return value is the inset to pad by
> (window-relative, so the caller may hand it straight to a bottom padding):
>  * **[visibleBoxPx]** — the pane's own box: the body column *without* the IME inset
>  * **[floorPx]** — the bottom inset the box already excludes (the navigation bar)
>  * **[imeBottomPx]** — `WindowInsets.ime`'s bottom, exactly as reported
>  * **[essentialPx]** — [PANE_ESSENTIAL_HEIGHT] at the current density"

**⭐ 核心防线：不要无条件相信平台给的键盘高度。** ——
`A/ui/session/SessionPaneLayout.kt:1679-1692`：
```kotlin
fun believedImeBottomPx(visibleBoxPx: Int, floorPx: Int, imeBottomPx: Int, essentialPx: Int): Int {
    if (imeBottomPx <= 0) return imeBottomPx
    if (visibleBoxPx <= 0 || essentialPx <= 0) return imeBottomPx
    if (visibleBoxPx < essentialPx * PANE_ESSENTIAL_SPARE_ROOM) return imeBottomPx
    val claimedPx = (imeBottomPx - floorPx.coerceAtLeast(0)).coerceAtLeast(0)
    val roomPx = visibleBoxPx - essentialPx
    if (claimedPx <= roomPx) return imeBottomPx
    return roomPx + floorPx.coerceAtLeast(0)
}
```
- `PANE_ESSENTIAL_HEIGHT`（`SessionPaneLayout.kt:1615-1616`）= `CHROME_FULL_HEIGHT + TIMELINE_MIN_HEIGHT + COMPOSER_MIN_HEIGHT + COMPOSER_REGION_PADDING` = **284 dp**（`research/v77_ime_inset_belief/REPORT.md:54-56`）。
- `PANE_ESSENTIAL_SPARE_ROOM = 2`（`SessionPaneLayout.kt:1700`）：
  > "Two, i.e. the keyboard would have to claim *more than half* of a box that is already worth twice what the pane needs."
- **只降不升**（`research/v77_ime_inset_belief/REPORT.md:61`）：
  > "夹取只**降**不升：结果 ≤ 上报值，永远不会把 composer 推到真实键盘下面更深处。"

**它防的是什么** —— `research/v77_ime_inset_belief/REPORT.md:25-26`：
> "链条上没有一处是 app 自己缓存的旧值：`ime` inset 是平台每次交付的读数，`maxHeight` 来自当帧约束。
> 崩的不是缓存，而是 **app 无条件相信平台给的键盘高度**。"

**CSS 安全区变量**（Web 宿主新增，⚠️ 本仓**无任何 CSS safe-area 存量**，属新设计）：
```css
:root {
  --oc-inset-top: env(safe-area-inset-top, 0px);
  --oc-inset-bottom: env(safe-area-inset-bottom, 0px);
  --oc-inset-left: env(safe-area-inset-left, 0px);
  --oc-inset-right: env(safe-area-inset-right, 0px);
  /* ⭐ 真正的判据自变量 —— 由原生 #23/#22 推送，不来自 CSS */
  --oc-room: 600px;      /* windowHdp - imeDp */
  --oc-ime: 339px;
}
```
🔴 **`env(safe-area-inset-*)` 不能替代 `WindowInsets.ime`**：
- `env(safe-area-inset-bottom)` 是 **`navigationBars`** 的值，**键盘弹起时它不变**。
  —— 这正是原生壳记的同一事实（`research/HANDOFF.md:71`：「`screenHeightDp` 是**显示**高度、
  键盘弹起时**不变**（`OcActivity` edge-to-edge …）」）。
- ⇒ **键盘高度只能来自原生推送**（`WindowInsets.ime`），且**必须取 `room` 做判据**（§5.4）。
- `navigationBars` 底 inset 真实值需实测（`research/v97_fix/DIVERGENCE_LOCATE2.md:410`
  要求读 **768 档**；领先假设是 `8`，**但至今未闭环**，见 §6）。

### 5.7 IME/insets 坑位清单（浓缩，逐条可执行）

| # | 结论 | 锚点 |
|---|---|---|
| 1 | **`dumpsys window insets` 不是合法子命令** ⇒ 用 `dumpsys window`，找 `InsetsSource id=3 type=ime frame=` | `REPORT_FINAL_VERIFY.md:336-346` |
| 2 | **IME 高度会随窗口变矮而塌缩**：`426 → 339 → 261`，塌缩点 **`600 → 599`**（单 dp，−78 px） | `REPORT_IME_STEP_CAUSE.md:45-69` |
| 3 | **任何"随窗口高单调"的判据都是错的** ⇒ 判据自变量必须是 **`room = windowH − ime`** | `MONOTONE_JUDGE_FIX.md:19` |
| 4 | **`426/339/261` 是 Gboard 选的 dp 常量**（378/291/213），**density 120 时不塌缩** ⇒ 阈值要按 density 换算 | `REPORT_IME_STEP_CAUSE.md:79-81, 236-250` |
| 5 | **426/339 阶有迟滞**（自上而下 426、自下而上 339）⇒ 不要对这一阶写死阈值断言 | `REPORT_IME_STEP_CAUSE.md:159-171` |
| 6 | **不要无条件相信 `WindowInsets.ime`** ⇒ 用 `believedImeBottomPx` 式钳位（`essential=284dp`、`spareRoom=2`、只降不升） | `SessionPaneLayout.kt:1679-1692` |
| 7 | **48dp 语义框 ≠ 布局框**，dump 侧恒 ≥ 生产侧（方向只能是向下），上界 = `expansionDown` | `LEDGER_REWRITE_AND_USABILITY.md:64-68` |
| 8 | **1px 分类边界 = 94% 遮挡** ⇒ 48dp 达标要判**派生语义框**，且原生壳自己也未达标（`Add`=42） | `LEDGER_REWRITE_AND_USABILITY.md:232, 247-250` |
| 9 | **`env(safe-area-inset-bottom)` 是 nav bar，不是 IME** ⇒ 键盘高度只能原生推送 | `HANDOFF.md:71` |
| 10 | **edge-to-edge 下系统不裁窗口**（`FIT_INSETS_CONTROLLED`）⇒ 崩的是"无条件相信 ime"，不是缓存 | `REPORT_IME_STEP_CAUSE.md:194-213` |
| 11 | **尺寸输入要存 px、读处换算** ⇒ `with(density){ it.height.toDp() }` 会把上一套 Configuration 的 dp 写进状态 | `v77_ime_inset_belief/REPORT.md:63-71` |
| 12 | **布局预算只从 `Configuration` 读**，不读实测布局 ⇒ 否则 56↔116 dp 自反馈震荡 | `ShellChromeBudget.kt:41-47` |
| 13 | **`show_ime_with_hard_keyboard` 必须为 1 才测得到 IME**；且本仓所有 IME 结论只在该前提下成立 | `REPORT_IME_STEP_CAUSE.md:310` |
| 14 | **`Window.onBackInvokedCallback` 与 IME 无关，但 `adjustResize` + 边到边会同时出现在同一清单里** —— 改 manifest 时两处一起看 | `MANIFEST:56, 79, 83, 109` |

---

## 6. WebView 上做不到 / 会明显变差的事（诚实清单）

| # | 事 | 结论 | 依据 |
|---|---|---|---|
| 1 | **JS 读 `WindowInsets.ime` 本身** | **做不到**。`env(safe-area-inset-*)` 只给 nav bar/status bar，**键盘高度不暴露给 CSS** | §5.6；`HANDOFF.md:71` |
| 2 | **在 JS 里做 `room` 判据而不等原生推送** | **做不到**。`room` 必须来自原生 `WindowInsets.ime` | §5.4 |
| 3 | **`dumpsys` 式自省** | **做不到**。WebView 沙箱内无 shell。要诊断必须走 `adb`（宿主侧），不能在页面上做 | 平台事实 |
| 4 | **`AccessibilityNode` 尺寸 = `getBoundingClientRect()`** | **会变差**。Chromium 的 a11y 框是**派生**的，可能含 `expansion`。原生 Compose 也有这个问题但可控 | §5.5；`LEDGER_REWRITE_AND_USABILITY.md:64-68` |
| 5 | **IME 批量输入的逐字节直通** | **会变差**。原生链已证明是直通（`adopt`→`setText`→`ComposerDrafts.set` 全不改 `.text`）；WebView 侧要经 DOM value 赋值 + 受控组件回写，**易触发 `restartInput()` 窗口** | `v75_ime_space/REPORT.md:58-66, 135` |
| 6 | **输入延迟** | **会变差**。JS bridge + 渲染进程往返。缓解：composer 走 native overlay 或 uncontrolled 输入 | §3.9 靶子 1-2 |
| 7 | **`ACTION_SEND` 分享** | **原生壳都没实现**（全仓零命中），Web 宿主**从零实现**，无先例可抄 | §3.3 坑 6 |
| 8 | **冷启动优化（AOT / baseline profile）** | **部分做不到**。WebView 首帧还要叠加 JS bundle 解析 + Vite 产物体积（`app/src/main/assets/opencode-app/` 已有大量资源） | `HANDOFF.md:233` |
| 9 | **常亮的精细控制** | **部分**。`FLAG_KEEP_SCREEN_ON` 是**窗口级**的，做不到"仅某个 WebView 内容区常亮"（`View.setKeepScreenOn` 可近似，但会撑住整个 view） | §3.6 |
| 10 | **后台暂停 JS timer** | **部分**。`pauseTimers()` 是 WebView 级、不可分粒度；且**回前台必须手动 `resumeTimers()`** | `MainActivity.kt:182-209` |
| 11 | **配对页的版本细分提示** | **能做到且应更好**。原生壳把 6 种失败压成 1 句（`ServerHealth.kt:155`），Web 宿主可借 `#21` 的细分状态补上 | §3.8 坑 5 |
| 12 | **`/api/info` 版本判定的单一真相源** | **做不到（应该）**。若页面也实现一份，会与原生漂移。必须只在原生判 | §3.8 坑 7 |
| 13 | **ESC 的处理** | **要注意**。ESC **绝不能**映射成返回/退出。WebView 里 ESC 需原样透传给页面（终端/编辑器要它） | `AndroidBackJs.kt:130-133` |
| 14 | **在 `dumpsys` 里区分"我的窗口"** | **变差**。多 WebView 场景下需要 `WebView.getWebContentsDebuggingEnabled` + `chrome://inspect` 才能定位，粒度不如原生 | 平台事实 |
| 15 | **48dp 触控靶达标率** | **会更差**。原生壳自己都未达标（`Add`=42 宽，`Open terminal`=16/46） | `LEDGER_REWRITE_AND_USABILITY.md:232` |

---

## 7. 未核对清单（诚实记账）

| # | 项 | 状态 |
|---|---|---|
| 1 | **"不能后台线程建 channel / 通知"这一崩溃**是否真实发生过 | **未核对**。`notify/` 全包**无任何相关注释**；代码是**故意**在协程上投递并 `catch (RuntimeException)`（`SystemNotifier.kt:82-84, 314-325`）。任务书里的前提**在本仓找不到依据** |
| 2 | 点击一个**已删除的会话**的通知，最终是否渲染错误页 | **未核对**。只确认了"请求永不被消费 + 每次 destination 变化重试 + `Log.w`"（`ui/App.kt:876-903`） |
| 3 | `SshErrorCode.VERSION`（`ui/servers/SshModel.kt:207`）**是否有构造点** | **未核对**。只查了 `SshConnection*.kt`（均为 `CONNECTION`/`INPUT`），`SshDialog.kt` 未逐一核对 |
| 4 | **768 档 `navigationBars` 底 inset 真值** | **未闭环**。领先假设 `8`（`140 = 132 + 8` 逐位自洽），但判据命令 `dumpsys window insets` 本身是错的（§5.2），`research/v97_fix/DIVERGENCE_LOCATE2.md:405-410` 明确记「**只差一条 dumpsys 读数**」 |
| 5 | `SessionPaneLayout.kt:2385-2389` **自证循环** | **未闭环**（`HANDOFF.md:305`）。形态判定看的是钳位自己的输出；代入实测输入后 body 从 284 变 236，**循环更强了** |
| 6 | 队列条几何（`Steer h=43` / `× h=48` / 全 clickable） | **未测到**（`HANDOFF.md:260`）。扫 5 次 dump 全树无 `Queue`/`Steer` 节点 ⇒ **无法测量，不是"不达标"** |
| 7 | 运输失败卡片何时自我退休 | **未闭环**（`BannerPolicy.kt:155-183` 记为 transcript 行，需连接恢复时退休） |
| 8 | `ChatViewModel.start()/stop()` 是否把 `started` 置 false | **未核对**。影响 watchdog 在后台是否续跑 |
| 9 | `SystemNotifier.kt` 除 `:157/:226/:230-232` 外的生命周期回调完整实现 | **未核对**（前三处为 grep 摘录，未逐字通读） |
| 10 | 三个通知开关的字符串值 + 是否有"通知被屏蔽"的 App 内提示 | **未核对** |
| 11 | 加密相关的 TODO/FIXME | **未核对**。grep `app/src` 的 `*.kt` **无匹配**；`research/` 未检索 |
| 12 | `ServerConfigStore.replaceAll` 的落盘编码细节 | **未核对**（只有类注释 `ServerConfigStore.kt:30-34` 说明 username 往返） |
| 13 | **WebView 输入延迟的具体毫秒数** | **无测量**。本仓无数据，不要引用数字 |
| 14 | `pauseTimers()` 的**实际省电百分比** | **无测量**。本仓无数据 |
| 15 | `SessionExportSaver.save(...)` 各调用方（哪些屏用它） | **未核对**（超出指派文件范围） |
| 16 | `OpenInApp.Action.SHARE_TEXT` 是否有 JVM 测试钉住 | **未核对**。已确认 `app/src/main` 无调用点，`*.kt` grep（含测试）无命中 |
| 17 | Gboard 在 `291dp` / `213dp` 之间的**内部阈值比较式** | **未核对**。触发输入已定位为 `screenHeightWithoutNaviBar`（551 vs 552 dp），但阈值在闭源代码里（`REPORT_IME_STEP_CAUSE.md:326-329`） |
| 18 | 本节 IME 结论能否外推到**非 Gboard IME / 真机** | **未核对**。v160 判定明确绑定 `Gboard LatinIME + 模拟器`（`REPORT_IME_STEP_CAUSE.md:335-336`：「**不可外推到全部设备**」） |
| 19 | `research/web/` 下是否已有 W1 的 `webapp/` 产物需要对齐 | **已核对**：本轮开始时 `research/web/` **只有一个 `_build-app.log`**，无 W1 契约文件。本表以原生壳为唯一真相源 |

---

## 8. 一页速查（给实现者）

```
1. 桥方法全部 @JavascriptInterface + String 进 String 出（复杂返回用 JSON 串）+ 异常全吞
2. 桥方法在 JavaBridge 线程 → 碰 UI/Window/通知必须 runOnUiThread
3. 通知权限在第一次组合就问（不是第一次通知）
4. 通知渠道 setSound(null,null)，声音 App 自己放（渠道声音不可变）
5. 通知点击 = singleTask + onNewIntent + EXTRA_SESSION_ID="opencode.android.notification.sessionId"
6. 返回键 = JS 优先 → canGoBack → 系统；必须剥引号；必须 backInFlight 守卫
7. v33+ 必须 enableOnBackInvokedCallback="true"，否则返回键全废
7b. 带导航栈时禁用 androidx BackHandler（NavHost 下静默失效）→ 直接 addCallback(callback)，last-one-wins
8. ESC 永不退出、永不映射返回
9. 文件：FileProvider authority = com.xiufeigo.opencodeshell.fileprovider（无后缀）
10. 文件：只给 FLAG_GRANT_READ_URI_PERMISSION；SAF 取消也必须回调
11. 文件：导出文件名不小写化；暂存文件名清洗是安全控制
12. 凭据：EncryptedSharedPreferences 已在依赖里；口令永不过 JS；明文降级必须显式化
13. 凭据：单例持有 encrypted prefs；每次接受 payload 重写标量镜像
14. 认证：用户名固定 opencode；空口令 → 完全不发 Authorization 头
15. 探测：GET /api/info，200+JSON+version 以 "2." 开头，3s 预算，无重试
16. 探测：401 必须二分（noauth vs unauthorized）；callTimeout 抛 InterruptedIOException
17. IME：dumpsys window（不是 dumpsys window insets）
18. IME：判据自变量 = room = windowH - ime；H 作判据必错（426→339→261，塌缩点 600→599）
19. IME：不要无条件相信 WindowInsets.ime（believedImeBottomPx 钳位，只降不升）
20. IME：env(safe-area-inset-bottom) 是 nav bar 不是键盘；键盘高度只能原生推送
21. IME：48dp 判派生语义框，dump ≠ 布局（方向只能向下）
22. 常亮：原生壳没有；若加必须回合级 + 5min 无输入释放
23. 省电：WebView 不可见必须 onPause()+pauseTimers()，且只在需要时 resume
24. 主题：isAppearanceLightStatusBars=true 是"浅色栏"；深色内容置 false
25. 主题：不隐藏系统栏（HyperOS 吞触摸）
26. 通知门：用 pause+焦点+5s 宽限，不能用 onStop（晚 1.1s，丢通知）
```

---

*本文件由 P1 接口规范工程师产出，仅含 `research/web/**` 写入。`app/src/main/**` 零改动，未跑 gradle，未装机。*
