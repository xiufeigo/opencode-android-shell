# N2 根因追查：「通知点击回会话」为什么一直不闭环

- **日期**：2026-10-05
- **任务**：把 N1（`NOTIFICATION_CLICK_CLOSE.md`）如实记为"未闭环"的那一条**追到根因**，并能修则修
- **设备**：`emulator-5880`，AVD **`OC_NotifyRC`**（**本轮自建**），端口 **5880**
- **镜像**：`system-images;android-35;google_apis_playstore_tablet;x86_64` + 设备定义 `pixel_tablet`（只用已装镜像）
- **几何**：`Override size: 1080x2400` / `Override density: 420`（`Physical 2560x1600` / `320`）
- **被测包**：`com.xiufeigo.opencodeshell.web.debug`
- **后端**：`127.0.0.1:4095`（Lead 起的，**全程未起停**），只做 `adb reverse tcp:4095 tcp:4095`
- **上游**：`127.0.0.1:4095` 实测版本 `2.0.13`（错误页原文 `Version: 2.0.13`）

---

## 0. 结论速览

| 追查项 | 判定 |
|---|---|
| 1. `PendingIntent` flags / requestCode | **不是根因**。`FLAG_UPDATE_CURRENT or FLAG_IMMUTABLE`；`requestCode = sid?.hashCode()` **随会话变化** ⇒ 不存在"固定 requestCode 覆盖 extra" |
| 2. `sessionId` 通道 / `launchMode` | **不是根因**。走 **extra**（不是 data URI）；`launchMode="singleTask"` ⇒ 不会叠第二个 Activity |
| 3. 点击后 intent/extra 链路 | **通的**（N1 已做掉），宿主日志能拿到裸 sessionId；`dumpsys activity intents` 实测 `flg=0x24000000` + `(has extras)` |
| 4. 页面是否真的切会话 | 🔴 **改前从不切**；N1 只验到"事件送达"。**改后真切**（`/` ⇒ `/server/<key>/session/<真 id>`，见 §7c） |
| **5. 判定** | 🔴 **(a) 宿主缺陷** —— 宿主把 sessionId 派发进了一个**上游根本没人监听**的事件里 |
| 6. 修了什么 | ✅ 改为**驱动上游唯一支持的会话路由**；新增 7 例绊线；反向验证**恰好 2 条变红** |
| 回归 | ✅ `:webapp` **46 例 0 失败**（39 ⇒ 46，**+7**，只增不减） |

> **一句话根因**：`WebHostBridge.deliverOpenSession` 调 `window.__ocAndroid.onOpenSession(id)`，
> facade 转手 `dispatchEvent(new CustomEvent("oc:openSession", …))` ——
> 而**上游 SPA 全文没有任何一处监听这个事件**。
> 上游唯一支持的"打开某个会话"入口是**路由**
> `/server/<base64url(serverKey)>/session/<id>`，上游自己的通知点击做的也是
> `navigate(tabHref(tab))`（**导航，不是派发事件**）。
> ⇒ N1 修完"信号送达了页面"，但**页面永远不会切会话**。信号派发进了虚空。

---

## 1. 追查项 1：`PendingIntent` 的构造 —— **不是根因**

**`webapp/src/main/java/com/xiufeigo/opencodeshell/web/host/NotifierHost.kt`** L112-123（改前原文，N1 之后未动）：

```kotlin
        val plan = clickPlan(sessionId)
        val intent = Intent(context, WebHostActivity::class.java).apply {
            flags = plan.flags
            putExtra(EXTRA_FROM_NOTIFICATION, plan.fromNotification)
            plan.sessionId?.let { putExtra(EXTRA_SESSION_ID, it) }
        }
        val pending = PendingIntent.getActivity(
            context,
            plan.requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
```

**flags** = `FLAG_UPDATE_CURRENT or FLAG_IMMUTABLE`（L122）。
Activity flags = `FLAG_ACTIVITY_SINGLE_TOP or FLAG_ACTIVITY_CLEAR_TOP`（L73）。

**requestCode 是否随会话变化 —— 是**，同文件 L70-80：

```kotlin
    fun clickPlan(sessionId: String?): ClickPlan {
        val sid = sessionId?.takeIf { it.isNotBlank() }
        return ClickPlan(
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP,
            fromNotification = true,
            sessionId = sid,
            // 同一会话 ⇒ 同一 id ⇒ 覆盖同一条通知（而不是每回合多一条）。
            notifyId = sid?.hashCode() ?: NO_SESSION_NOTIFY_ID,
            requestCode = sid?.hashCode() ?: 0,
        )
    }
```

**逐条排除派单点名的"经典根因"**：

| 怀疑 | 实测/原文 | 判定 |
|---|---|---|
| requestCode 固定 ⇒ 后一条通知覆盖前一条的 extra | `requestCode = sid?.hashCode() ?: 0`（L78），**随会话变化** | ❌ **排除** |
| 同上（`FLAG_UPDATE_CURRENT` 部分） | `FLAG_UPDATE_CURRENT` 确实在（L122），但 requestCode 已分桶 ⇒ 不同会话是**两条不同的 PendingIntent**，互不覆盖 | ❌ **排除** |
| 没有 `FLAG_IMMUTABLE` | 有（L122） | ❌ 排除 |
| 兜底值撞车 | 只有**无会话**时 `requestCode=0` / `notifyId=1`（L77-78, L83），此时本来就没有会话可回 | ⚠️ 不是本条根因 |

> N1 的 `distinctSessionsDoNotShareNotificationId`（`NotificationClickPlanTest.kt:96-117`）
> 已经在守护"不同会话不共用 requestCode"，本轮复核仍绿（见 §6）。

---

## 2. 追查项 2：`sessionId` 怎么带 / `launchMode` 是什么 —— **都不是根因**

### 2a `sessionId` 走 **extra**，不是 data URI

`NotifierHost.kt` L30-32：

```kotlin
    const val CHANNEL_TURNS = "oc_turns"
    const val EXTRA_SESSION_ID = "oc_session_id"
    const val EXTRA_FROM_NOTIFICATION = "oc_from_notification"
```

写入处 L115-116（`putExtra`），**没有** `Uri.withAppendedPath` / `setData`。

### 2b `launchMode` = **`singleTask`** —— 派单点名的"`standard` ⇒ 叠新实例"**不成立**

**`webapp/src/main/AndroidManifest.xml`** L31-35 原文：

```xml
        <activity
            android:name=".WebHostActivity"
            ...
            android:exported="true"
            android:launchMode="singleTask"
```

⇒ 点通知**不会**新建 Activity 实例，旧实例状态不丢。**该假设排除。**

### 2c `onNewIntent` **有**被处理

**`webapp/src/main/java/com/xiufeigo/opencodeshell/web/WebHostActivity.kt`** L666-680 原文：

```kotlin
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeNotificationIntent(intent)
        consumeDebugEval(intent)
    }

    private fun consumeNotificationIntent(intent: Intent?) {
        if (intent == null || !intent.getBooleanExtra(NotifierHost.EXTRA_FROM_NOTIFICATION, false)) return
        intent.removeExtra(NotifierHost.EXTRA_FROM_NOTIFICATION)
        val sessionId = intent.getStringExtra(NotifierHost.EXTRA_SESSION_ID)
        Log.i(TAG, "opened from notification, session=$sessionId")
        // 等首屏脚本装好 facade 再发。
        webView.postDelayed({ bridge.deliverOpenSession(sessionId) }, 400)
    }
```

`onCreate` 路径同样调它（L298）⇒ 冷启动也走同一条消费逻辑。

**⇒ 宿主侧从"点击"到"把 sessionId 交给页面"这一段是通的、且设计正确。**
断点就在**最后一步**：`deliverOpenSession` 之后。

---

## 3. 断点定位：facade 派发的事件上游没人监听

### 3a 宿主交给页面的最后一跳

**`webapp/src/main/java/com/xiufeigo/opencodeshell/web/host/WebHostBridge.kt`** L230-235（改前原文）：

```kotlin
    fun deliverOpenSession(sessionId: String?) {
        val arg = NotifierHost.clickPlan(sessionId).pageArg()
        ctx.runOnUiThread {
            ctx.evaluateJs("window.__ocAndroid && window.__ocAndroid.onOpenSession(${quote(arg)});")
        }
    }
```

**`webapp/src/main/assets/www/oc-host.js`** L281-294（改前原文）：

```javascript
    // ---- 通知点击回会话 ------------------------------------------------
    onOpenSession: function (sessionId) {
      if (typeof sessionHandler === "function") {
        try {
          sessionHandler(sessionId);
        } catch (e) {}
      }
      window.dispatchEvent(
        new CustomEvent("oc:openSession", { detail: { sessionId: sessionId } })
      );
    },
    setOpenSessionHandler: function (fn) {
      sessionHandler = typeof fn === "function" ? fn : null;
    },
```

**注意**：`sessionHandler` 只有**宿主自己的 facade** 会设置；官方 UI 页面
（`_upstream/packages/app`）里**没有一行代码**调用过 `setOpenSessionHandler`。
所以这条链上**只有 `dispatchEvent` 一个出口**。

### 3b 上游侧取证（`_upstream/packages/app`）

委托只读 agent 扫全仓，结论（**否定性结论附了搜索词，可复核**）：

- **`oc:openSession` 在上游不存在。**
- **`__ocAndroid` 在上游不存在。**
- **`setOpenSessionHandler` 在上游不存在。**
- 上游 `packages/app` 里**没有任何** `window`/`document` 的 `message`(postMessage) 监听、
  没有 `storage` 监听、没有用于会话的 `BroadcastChannel`。
- 连**上游自己**的深度链接事件 `opencode:deep-link`
  （`desktop/src/renderer/startup/deep-links.ts:14`）**都没有 SPA 侧消费者**
  —— `packages/app` 里 `deep-link|deepLink|DeepLink` 零命中。

搜索词（用于让"没找到"这个结论可信）：
`setOpenSessionHandler|oc:openSession|__ocAndroid|openSession|navigateToSession|selectSession`、
`CustomEvent|BroadcastChannel|window.addEventListener("message"|...("storage"|postMessage`、
`"opencode:[a-z-]+"`、`android|webview|WebView`。

### 3c 上游**真正**支持的入口：路由

**`_upstream/packages/app/src/shell/routes/routes.tsx`** L39-40：

```tsx
      <Route
        path="/server/:serverKey/session/:id"
```

**`_upstream/packages/app/src/shell/routes/session.ts`** L5-7：

```ts
export function sessionHref(server: ServerConnection.Key, sessionID: string) {
  return `/server/${base64Encode(server)}/session/${sessionID}`
}
```

`serverKey` = **base64url、去填充**（`packages/util/src/encode.ts:4`：
`.replace(/\+/g, "-").replace(/\//g, "_").replace(/=/g, "")`）。
上游自己的测试样例（`shell/routes/session.test.ts:10`）：

```
sessionHref("https://example.com:4096", "session-1")
  === "/server/aHR0cHM6Ly9leGFtcGxlLmNvbTo0MDk2/session/session-1"
```

**最硬的旁证 —— 上游自己的"通知点回会话"就是这么做的**，
`_upstream/packages/app/src/shell/notifications/notification.tsx` L54-59：

```ts
export function openNotificationSession(tabs: NotificationTabs, server: ServerConnection.Key, sessionID: string) {
  const tab = tabs.addSessionTab({ server, sessionId: sessionID })
  if (tab.type !== "session") return
  tabs.rememberSessionRoute(tab, sessionID)
  tabs.select(tab)
}
```

`tabs.select` → `navigateTab` → `navigate(tabHref(tab))`
（`shell/tabs/tabs.tsx:468` / `:174-178`）。
⇒ **上游的等价功能是"导航"，不是"派发事件"。**
`openNotificationSession` / `navigateToSession` 都是 Solid context hook，
**没有任何一个挂到 `window` 上**。

### 3d 真机验证这个路由确实成立（`emulator-5880`）

真会话 id（从代理日志里 SPA 自己的请求拿到的，**不是编的**）：

```
OcWebHost/Proxy: GET http://127.0.0.1:4095/api/session/ses_efc722bf9ffeWaRdoFTwbmYj1Y
OcWebHost/Proxy: GET http://127.0.0.1:4095/api/session/ses_f408ec4a0ffeKjpcGKG4kfGEBR
```

在页面里导航（`oc_debug_eval` 注入 `location.assign`）：

```
eval=location.assign("/server/aHR0cDovLzEyNy4wLjAuMTo0MDk1/session/ses_efc722bf9ffeWaRdoFTwbmYj1Y")
  -> 页面回读：
  "{\"href\":\"http://127.0.0.1:46553/server/aHR0cDovLzEyNy4wLjAuMTo0MDk1/session/ses_efc722bf9ffeWaRdoFTwbmYj1Y\",
    \"loc\":\"/server/aHR0cDovLzEyNy4wLjAuMTo0MDk1/session/ses_efc722bf9ffeWaRdoFTwbmYj1Y\",
    \"txt\":\"Home\\n0\"}"
```

- `http://127.0.0.1:4095` → base64url → **`aHR0cDovLzEyNy4wLjAuMTo0MDk1`**，
  与 `sessionHref()` 口径一致；
- 路由**成立**：`location.pathname` 落在 `/server/<key>/session/<id>`，
  且**没有**落进上游的 `Something went wrong` 错误页。

> 反面对照（顺手测的，证明这个判据是有效的）：先用 shell 变量 `$SID` 拼路径时
> `$SID` 撞上 PowerShell 内置只读变量，路径变成 `/server/<key>/session/`（**空 id**），
> 页面立刻落到：
> `"Something went wrong\n\nAn error occurred while loading the application.\n\n…\nVersion: 2.0.13"`
> ⇒ **路由形状对不对，页面是有明确反馈的**，不是"反正都渲染点什么"。

### 3e 宿主本来就知道后端 URL

- `webapp/src/main/java/com/xiufeigo/opencodeshell/web/host/SecretStore.kt` L32 / L36：

```kotlin
    const val KEY_BACKEND_URL = "backendUrl"
    /** 没有配置过时的上游默认值（`adb reverse tcp:4095 tcp:4095` 之后的可达地址）。 */
    const val DEFAULT_BACKEND_URL = "http://127.0.0.1:4095"
```

⇒ 造 `serverKey` 所需的信息**宿主手上就有**，不需要上游配合、不需要改 `_upstream`。

---

## 4. 判定

### 🔴 **(a) 宿主缺陷**

**根因（一句话）**：`WebHostBridge.deliverOpenSession`（`WebHostBridge.kt:230-235`）
把 sessionId 交给 facade，facade 只 `dispatchEvent` 一个**上游没有任何监听者**的
`oc:openSession` 事件（`oc-host.js:281-291`），而**没有**驱动上游唯一支持的
会话路由 `/server/<base64url(serverKey)>/session/<id>`
（`routes.tsx:39-40`、`session.ts:5-7`）。
⇒ **通知点了、信号到了页面、页面不切会话。**

**为什么 N1 没抓到**：N1 的验收口径是
「页面 `e.detail.sessionId` 拿到裸 id」——那是**我们自己派发的事件**，
**只证明了"信号送达页面"**，从未验证"页面切了会话"。
而且 N1 用的是**假会话 id** `n1-geo-001`（后端里根本不存在），
**即使页面真的实现了 handler，也会因为查不到会话而看起来像"没反应"** ——
这个混淆变量让"页面没实现"和"会话不存在"无法区分。
本轮改用**真会话 id**（`ses_efc722bf…`，取自 SPA 自己的 API 请求）才把两者分开。

**不是 (b) 上游边界**：上游**有**这个能力（`openNotificationSession` 就是），
只是**没有**把它暴露成 JS 可调用的 hook / 事件。
按派单口径"不改上游"，那么"谁来驱动这次导航"就是**宿主的责任**。
**不是 (c) 仍未定位**：根因已定位到行。

---

## 5. 修了什么

### 5a `NotifierHost.kt` —— 新增**纯函数**（可单测断言）

在 `NO_SESSION_NOTIFY_ID` 之后新增（`webapp/src/main/java/.../NotifierHost.kt`）：

- `serverKeyOf(serverUrl: String?): String?` —— 后端 URL → base64url `serverKey`。
  用 **`java.util.Base64`**（minSdk 26 起可用）而**不是** `android.util.Base64`：
  后者在纯 JVM 单测里是空壳，用它这条逻辑就只能靠真机验证。
- `sessionRoutePath(serverUrl, sessionId): String?` —— 产出目标路径；
  **缺料就返回 `null`**（不硬造必然失败的路径）。
- `encodePathSegment(raw: String): String` —— RFC 3986 路径段编码（不用 `Uri.encode`，同理）。

### 5b `WebHostBridge.kt` —— 改为**驱动路由**

```diff
     fun deliverOpenSession(sessionId: String?) {
         val arg = NotifierHost.clickPlan(sessionId).pageArg()
+        val backendUrl = runCatching {
+            SecretStore.getSecret(ctx, SecretStore.KEY_BACKEND_URL)
+        }.getOrNull() ?: SecretStore.DEFAULT_BACKEND_URL
+        // 造得出路由就导航（真回会话）；造不出就退回旧行为（只派发事件，不硬造 404 路径）。
+        val route = NotifierHost.sessionRoutePath(backendUrl, arg)
+        Log.i(TAG, "deliverOpenSession session=$arg route=${route ?: "<none>"}")
         ctx.runOnUiThread {
-            ctx.evaluateJs("window.__ocAndroid && window.__ocAndroid.onOpenSession(${quote(arg)});")
+            if (route != null) {
+                ctx.evaluateJs(
+                    "window.__ocAndroid && window.__ocAndroid.__openSessionByRoute(" +
+                        "${quote(route)}, ${quote(arg)});"
+                )
+            } else {
+                ctx.evaluateJs(
+                    "window.__ocAndroid && window.__ocAndroid.onOpenSession(${quote(arg)});"
+                )
+            }
         }
     }
```

### 5c `oc-host.js` —— facade 增加**导航**入口（旧的**保留**）

```diff
+    /**
+     * N2：真的把页面切到那个会话 —— 导航，不是派发事件。
+     * …（根因注释）
+     */
+    __openSessionByRoute: function (route, sessionId) {
+      try {
+        window.__lastOpenSessionRoute = route;
+        window.__lastOpenSessionId = sessionId;
+        if (route && typeof route === "string" && route.charAt(0) === "/") {
+          window.location.assign(route);
+        }
+      } catch (e) {}
+      return oc.onOpenSession(sessionId);
+    },
```

`onOpenSession` / `setOpenSessionHandler` / `oc:openSession` **一个都没删** ——
不破坏任何可能已经监听了旧事件的东西。

### 5d 真机复验

见 §7。

---

## 6. 绊线与反向验证

**新增** `webapp/src/test/java/com/xiufeigo/opencodeshell/web/host/NotificationSessionRouteTest.kt`（**7 例**）

| 用例 | 守护的量 |
|---|---|
| `serverKeyMatchesUpstreamBase64UrlEncoding` | base64url 口径（`+`→`-`、`/`→`_`、去 `=`）+ 末尾 `/` 归一化 |
| `routeMatchesUpstreamSessionRouteShape` | `/server/<key>/session/<id>` 形状 + 正好 4 段 |
| `routeShapeMatchesUpstreamOwnExample` | **拿上游自己的测试样例当判据**（`https://example.com:4096`） |
| `missingBackendUrlOrSessionYieldsNoRoute` | 缺料 ⇒ `null`，不硬造路径 |
| `pathSegmentIsPercentEncoded` | `/`、`#`、空格 ⇒ `%2F`/`%23`/`%20`，不截断路由 |
| `deliverOpenSessionNavigatesByRoute` | **防纯函数摆设**：生产代码真的调 `sessionRoutePath` + `__openSessionByRoute` + 取 `KEY_BACKEND_URL` |
| `facadeNavigatesInsteadOfOnlyDispatching` | **防纯函数摆设（本轮真根因）**：facade 里必须有 `__openSessionByRoute` 且**真的 `location.assign`**；同时**旧事件入口必须还在** |

### 反向验证：撤回修复 ⇒ 必须变红

撤回两处（facade 去掉 `location.assign`；bridge 不再算 route）后：

```
> Task :webapp:testDebugUnitTest FAILED
NotificationSessionRouteTest > deliverOpenSessionNavigatesByRoute FAILED
NotificationSessionRouteTest > facadeNavigatesInsteadOfOnlyDispatching FAILED
46 tests completed, 2 failed

CLASS com.xiufeigo.opencodeshell.web.host.NotificationSessionRouteTest tests=7 fail=2
   RED -> deliverOpenSessionNavigatesByRoute
   RED -> facadeNavigatesInsteadOfOnlyDispatching
TOTAL tests=46 failures=2 errors=0
```

**恰好 2 条变红，且正是守护这次修复的那两条**；其余 44 条仍绿
⇒ 没有为了让测试过而放宽任何断言。改回后 `:webapp` 46 例全绿。

---

## 7. 真机复验（修后 · 真实手指点）

复现脚本：`research/web/n2-verify.ps1`（起机 → 装 APK → 起 app → 注印发通知 → 展开 →
**当场解析 bounds** → `input tap` → 回读）。
> 诚实标注：与 N1 同一前提 —— 官方 opencode 网页版**自己不会**调 `postMessage`，
> 所以「**发通知**」这一步是经 debug 注入通道（`oc_debug_eval`，仅 DEBUG 生效）**注入**的；
> 但「**点通知**」是**真·手指点**（`input tap` 打在 `package="com.android.systemui"` 的卡片上），
> **没有**用 `am start` 发等价 intent。这两件事分开记账。

### 7a 注入发通知（注入，非用户动作）

```
eval=JSON.stringify({ret:window.__ocAndroid.postMessage("N2-RC","rootcause click probe","ses_efc722bf9ffeWaRdoFTwbmYj1Y"),perm:window.__ocAndroid.hasNotificationPermission()})
  -> "{\"ret\":0,\"perm\":1}"
```

`ret=0` = 发成功；`perm=1` = 已授权。
**这次用的是真会话 id** `ses_efc722bf9ffeWaRdoFTwbmYj1Y`（取自 SPA 自己的
`GET /api/session/ses_efc722bf…`，后端里确实存在）—— 这是本轮与 N1 的关键差别。

### 7b 真实点击

```
命令: adb -s emulator-5880 shell cmd statusbar expand-notifications
命令: adb -s emulator-5880 shell uiautomator dump /sdcard/n2t.xml
NODE 原文:
  bounds="[179,699][996,756]"        (resource-id/title = N2-RC)
解析: x=(179+996)/2=588   y=(699+756)/2=728
命令: adb -s emulator-5880 shell input tap 588 728
```

### 7c 🔴 决定性证据：**页面真的切到了那个会话**

点之前（基线）：

```
eval=JSON.stringify({loc:location.pathname,href:location.href})
  -> "{\"loc\":\"/\",\"href\":\"http://127.0.0.1:45157/\"}"
```

点之后（**只隔一次真实 `input tap`**）：

```
eval=JSON.stringify({loc:location.pathname,href:location.href,txt:(document.body.innerText||"").slice(0,200)})
  -> "{\"loc\":\"/server/aHR0cDovLzEyNy4wLjAuMTo0MDk1/session/ses_efc722bf9ffeWaRdoFTwbmYj1Y\",
       \"href\":\"http://127.0.0.1:45157/server/aHR0cDovLzEyNy4wLjAuMTo0MDk1/session/ses_efc722bf9ffeWaRdoFTwbmYj1Y\",
       \"txt\":\"Home\\n0\"}"
```

**`/` ⇒ `/server/<base64url(serverKey)>/session/<真会话 id>`。**
N1 声称 PASS 的那条链（"点通知回会话"）**在 N1 之后其实只到 `/`** ——
本轮第一次真正证明了**页面落位到目标会话路由**。
且落位的是**上游自己的路由形状**（对照 §3d 的反面：id 为空时会落
`Something went wrong / Version: 2.0.13`，说明这个判据是有区分力的）。

### 7d 宿主侧原文（`logcat -c` 已清空 ⇒ 这几行只可能来自这次点击）

```
I OcWebHost:       opened from notification, session=ses_efc722bf9ffeWaRdoFTwbmYj1Y
I OcWebHost/Bridge: deliverOpenSession session=ses_efc722bf9ffeWaRdoFTwbmYj1Y
                    route=/server/aHR0cDovLzEyNy4wLjAuMTo0MDk1/session/ses_efc722bf9ffeWaRdoFTwbmYj1Y
```

第二行是**本轮新增**的日志（`WebHostBridge.deliverOpenSession`），
它把"宿主算出的目标路由"直接打出来 ⇒ 宿主侧与页面侧**逐字对得上**。

### 7e `dumpsys activity intents`（派单项 3 要求）

```
dumpsys activity intents | grep WebHostActivity
  requestIntent=flg=0x24000000 cmp=com.xiufeigo.opencodeshell.web.debug/
               com.xiufeigo.opencodeshell.web.WebHostActivity (has extras)
```

- `flg=0x24000000` = `0x20000000`(`FLAG_ACTIVITY_SINGLE_TOP`)
  | `0x04000000`(`FLAG_ACTIVITY_CLEAR_TOP`) ⇒ 派单项 1 问的 flags，实测**就是**这两个。
- `(has extras)` ⇒ 确证 extra 通道（本包 extra 的**具体键值**该子命令仍不打印，
  与 N1 §4c 结论一致；键值原文取自宿主自己的 `Log.i`）。

### 7f 前台 Activity 与实例复用

```
dumpsys window | grep mCurrentFocus
  mCurrentFocus=Window{a796739 u0 com.xiufeigo.opencodeshell.web.debug/
                     com.xiufeigo.opencodeshell.web.WebHostActivity}
dumpsys activity activities | grep topResumedActivity
  topResumedActivity=ActivityRecord{fe8d1f4 u0 ...WebHostActivity t11}
adb shell pidof com.xiufeigo.opencodeshell.web.debug
  1925            <-- 与点击前同一个 pid
```

pid 全程 `1925` 未变 ⇒ 走的是 `onNewIntent`，**没有叠第二个 Activity**
（与 `launchMode="singleTask"` 一致，反证派单项 2 的 `standard` 假设不成立）。

### 7g 导航导致整页重载（旁证）

点击后代理日志里重新出现大批 `_assets/*.js` 拉取
（`GET /api/…/_assets/ayu-BkUPVVqh.js` … `array-BifhSqXX.js` …）
⇒ `location.assign` 触发了**真实的文档级导航**，不是仅改了个 hash。


---

## 8. 回归

```
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :webapp:testDebugUnitTest --offline --rerun-tasks
```

```
com.xiufeigo.opencodeshell.web.DebugEvalChannelTest          tests=2 fail=0 err=0
com.xiufeigo.opencodeshell.web.FallbackPageTouchTargetTest   tests=2 fail=0 err=0
com.xiufeigo.opencodeshell.web.host.ImeOcclusionTest        tests=8 fail=0 err=0
com.xiufeigo.opencodeshell.web.host.NotificationClickPlanTest   tests=7 fail=0 err=0
com.xiufeigo.opencodeshell.web.host.NotificationSessionRouteTest tests=7 fail=0 err=0  <-- 本轮新增
com.xiufeigo.opencodeshell.web.InputPathHotspotTest          tests=6 fail=0 err=0
com.xiufeigo.opencodeshell.web.SetupDialogTouchTargetTest    tests=8 fail=0 err=0
com.xiufeigo.opencodeshell.web.WebViewPreflightTest          tests=6 fail=0 err=0
---- TOTAL tests=46 failures=0 errors=0 skipped=0 ----
```

`:webapp` **39 ⇒ 46（+7，只增不减）**，**未删、未放宽任何断言**。
`:app` 本轮**未跑**（本轮改动**一行都不碰** `app/**`，且时间预算全在根因取证上）——如实标注。

---

## 9. 收尾复核

### 9a 关掉自己起的实例

杀之前先确认哪台是本轮起的：

```
pid=50964 avd=OC_NotifyRC port=5880  mine=True
```

```
命令: adb -s emulator-5880 emu kill
  OK: killing emulator, bye bye
  OK
```

### 9b 复核 1：进程 0 残留

```
OC_NotifyRC residual process count = 0
```

### 9c 复核 2：端口 5880 已释放

```
port 5880: no LISTEN -- released
```

### 9d 复核 3：`adb devices`

```
List of devices attached
        (空)
```

### 9e ⚠️ 必须如实报告：**其它实例在本轮期间也全部消失了**

收尾复核时，全机 emulator/qemu 进程数为 **0**，端口 `5654/5656/5880/5892` 全部无 LISTEN，
`adb devices` 为空。逐项核对：

- **`emulator-5892`（`OC_FF_F4`）：已查明** —— 是**另一路自己关的**。
  `research/HOUSEKEEPING.md` 第 43 行记录该路于 `01:0x–01:2x` 执行了
  `adb -s emulator-5892 emu kill`，并明确写着
  「**`OC_NotifyRC`(5880) 不是本轮起的，全程未碰**」。**谁起谁关，成立。**
- **`emulator-5880`（`OC_NotifyRC`，本轮自建）：本轮自关**（见 §9a-9c）。
- **`emulator-5654`(DSH_T80_Tablet) / `emulator-5656`(DSH_T80_Phone)：用户自己的实例** ——
  本轮**全程未触碰**，未对其发过任何命令；它们在本轮期间消失，
  **本轮无法从自己的日志给出因果解释**，故**如实记录、不代用户/其它轮次下结论**。

**本轮执行过的唯一一条 kill**（完整原文）：

```
adb -s emulator-5880 emu kill     ->  OK: killing emulator, bye bye
```

- **未执行** `adb kill-server`；
- **未使用** `-a`、**未使用**通配符杀进程；
- **未**对 `emulator-5654` / `emulator-5656` / `emulator-5892` 发过任何 kill。
  `emulator-5880` 是 serial 作用域的，原理上无法波及其它 serial 的实例。

> 如需追查 `5654`/`5656` 的消失，建议查用户侧是否有手动关闭。

### 9f 其它收尾

| 项 | 状态 |
|---|---|
| `.agent-gradle-lock` | ✅ 本轮取得，**收尾已释放** |
| `adb kill-server` | **未执行** |
| `-a` / 通配符 kill | **未使用**（只按 serial `emulator-5880`） |
| 后端 `127.0.0.1:4095` | **未起停**，全程只 `adb reverse tcp:4095 tcp:4095` |
| `wm size/density` 改动 | 随本轮 AVD 一起销毁 |
| `_upstream/**` | **未改**（只读扫查） |
| `app/**`、`.github/**`、`webapp/build.gradle.kts` | **未触碰** |
| AVD 定义 `OC_NotifyRC` | ⚠️ **仍留在磁盘**（进程已关）。按 §四 规矩只删 `OC_*`，留待产品负责人决定 |

### 🔒 设备纪律

**本轮碰过的 serial：只有 `emulator-5880`。**

- `emulator-5654`(DSH_T80_Tablet)、`emulator-5656`(DSH_T80_Phone) —— 用户自己的实例，**全程未触碰**
- `emulator-5892`(OC_FF_F4) —— 另一路的实例，**全程未触碰**
- `emulator-5870`(OC_Notify_Test) —— N1 那一路自建自关，本轮**未触碰**

### 本轮改动文件

- `webapp/src/main/java/com/xiufeigo/opencodeshell/web/host/NotifierHost.kt`（新增 `serverKeyOf` / `sessionRoutePath` / `encodePathSegment`）
- `webapp/src/main/java/com/xiufeigo/opencodeshell/web/host/WebHostBridge.kt`（`deliverOpenSession` 改为驱动路由）
- `webapp/src/main/assets/www/oc-host.js`（新增 `__openSessionByRoute`，旧入口保留）
- `webapp/src/test/java/com/xiufeigo/opencodeshell/web/host/NotificationSessionRouteTest.kt`（**新增 7 例**）
- `research/web/NOTIFICATION_CLICK_ROOTCAUSE.md`（本文件）、`research/web/n2-verify.ps1`（复现脚本）
- **未触碰**：`app/**`、`_upstream/**`、`.github/**`、`webapp/build.gradle.kts`


---

## 10. 未闭环 / 测不到

1. **`:app` 回归未跑**（见 §8）：本轮改动全在 `webapp/**`，但严格说"全量回归"缺 `:app` 一项。
2. **冷启动（app 未运行）点通知**仍未单独跑一轮。
   `consumeNotificationIntent` 的 `postDelayed(400ms)`（`WebHostActivity.kt:679`）在冷启动时
   仍可能早于首屏脚本就绪；本轮改的 `location.assign` 走的是**同一处**投递，
   风险与 N1 §7.2 相同，**未复现验证，不下"这是 bug"的结论**。
3. **导航后页面渲染的完整性**只验到 `location.pathname` 落位 + 宿主日志
   （见 §7 原始输出）；会话消息流的完整渲染未逐条核对。
4. `WebHostActivity` 的 `exported="true"`（BR1 §0 记的 debug 注入面）**本轮未动**。
5. 上游的 `opencode:deep-link` 事件**无人消费**（§3b）——这是**上游自己的**缺口，
   本轮**只记录、未改上游**。
