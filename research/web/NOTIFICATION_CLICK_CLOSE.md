# N1 通知「点击回到会话」闭环报告

- **日期**：2026-10-05
- **任务**：把 BR1 八项桥里唯一那条「PASS 带保留」（`research/web/BRIDGE_ACCEPTANCE.md` §2b）做死
- **设备**：`emulator-5870`，AVD **`OC_Notify_Test`**（**本轮自建**），端口 **5870**
- **镜像**：`system-images;android-35;google_apis_playstore_tablet;x86_64` + 设备定义 `pixel_tablet`（**只用已装镜像**）
- **API**：35
- **几何**：`Override size: 1080x2400` / `Override density: 420`（**单测几何**；`Physical 2560x1600` / `320`）
- **被测包**：`com.xiufeigo.opencodeshell.web.debug`（`webapp` debug）
- **后端**：`127.0.0.1:4095`（Lead 起的，**全程未起停**），只做 `adb reverse tcp:4095 tcp:4095`

---

## 0. 结论速览

| 项 | 判定 |
|---|---|
| 通知真出现 | ✅ PASS |
| **真·手指点卡片**（`input tap`，非等价 intent） | ✅ **PASS —— BR1 的保留点本轮做掉了** |
| 点击后回到会话（前台 / extra / 页面 三项） | ✅ **PASS** |
| 查出的真 bug | 🔴 **1 个高危：会话标识被双重 JSON 编码，点了也回不到会话** |
| 已修 | ✅ 已修 + 真机复验 + 单测反向验证变红 |
| 绊线 | ✅ 新增 `NotificationClickPlanTest`（7 例） |
| 回归 | ✅ `:webapp` 39 例 0 失败；`:app` 见 §8 |

---

## 1. 现状（行号 + 原文）

### 1a 通知是怎么发的

**`webapp/src/main/java/com/xiufeigo/opencodeshell/web/host/WebHostBridge.kt`** —— 桥名 `__ocAndroidNative`（L32），
facade 把它挂成 `window.__ocAndroid`：

```kotlin
// L82-88
@JavascriptInterface
fun postMessage(title: String?, body: String?, sessionId: String?): Int {
    val t = title.orEmpty().take(200)
    val b = body.orEmpty().take(2000)
    val s = sessionId?.takeIf { it.isNotBlank() }?.take(200)
    return NotifierHost.postTurnFinished(ctx, t, b, s)
}
```

### 1b `PendingIntent` 怎么建的

**`webapp/src/main/java/com/xiufeigo/opencodeshell/web/host/NotifierHost.kt`**，`postTurnFinished`（L103）：

```kotlin
val plan = clickPlan(sessionId)                      // L112  ← 本轮抽出的纯函数
val intent = Intent(context, WebHostActivity::class.java).apply {
    flags = plan.flags                                // L114
    putExtra(EXTRA_FROM_NOTIFICATION, plan.fromNotification)
    plan.sessionId?.let { putExtra(EXTRA_SESSION_ID, it) }   // L116
}
val pending = PendingIntent.getActivity(
    context,
    plan.requestCode,                                 // L120
    intent,
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
)
...
NotificationManagerCompat.from(context).notify(plan.notifyId, notification)   // L137
```

### 1c `sessionId` 是怎么带的

**extra**（不是别的通道），键名硬编码在 `NotifierHost.kt` L31-32：

```kotlin
const val EXTRA_SESSION_ID = "oc_session_id"          // L31
const val EXTRA_FROM_NOTIFICATION = "oc_from_notification"  // L32
```

### 1d 点击后 `WebHostActivity` 怎么处理

**`webapp/src/main/java/com/xiufeigo/opencodeshell/web/WebHostActivity.kt`**：

```kotlin
// L666-671
override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    consumeNotificationIntent(intent)
    consumeDebugEval(intent)
}

// L673-680
private fun consumeNotificationIntent(intent: Intent?) {
    if (intent == null || !intent.getBooleanExtra(NotifierHost.EXTRA_FROM_NOTIFICATION, false)) return
    intent.removeExtra(NotifierHost.EXTRA_FROM_NOTIFICATION)
    val sessionId = intent.getStringExtra(NotifierHost.EXTRA_SESSION_ID)
    Log.i(TAG, "opened from notification, session=$sessionId")
    webView.postDelayed({ bridge.deliverOpenSession(sessionId) }, 400)
}
```

`onCreate` 路径同样调它（L298 `consumeNotificationIntent(intent)`）⇒ 冷启动（app 未运行）也走同一条消费逻辑。

**`launchMode`**：`webapp/src/main/AndroidManifest.xml` L35 `android:launchMode="singleTask"`
（L34 `android:exported="true"` —— BR1 §0 记的 debug 注入面仍在，本轮未动）。

### 1e 页面侧契约

**`webapp/src/main/assets/www/oc-host.js`** L282-291：

```javascript
onOpenSession: function (sessionId) {
    if (typeof sessionHandler === "function") { sessionHandler(sessionId); }
    window.dispatchEvent(
        new CustomEvent("oc:openSession", { detail: { sessionId: sessionId } })
    );
},
```

**入参被当裸会话 id 用**（`NotifierHost` L20-25 的类注释也逐字写着
「交给页面（`window.__ocAndroid.onOpenSession(sessionId)`）」）。**这一点是本轮 bug 的根因，见 §4。**

---

## 2. 触发方式 —— **注入，不是自然的**

> ⚠ **诚实标注**：本轮的 `postMessage` **不是用户从 UI 自然触发的**，是**宿主侧注入**的。

官方 opencode 网页版**自己不会主动调** `postMessage`（与 BR1 §0 同一前提）。
按任务授权用 BR1 已有的 **debug 注入通道**（`WebHostActivity.EXTRA_DEBUG_EVAL = "oc_debug_eval"`，
仅 `BuildConfig.DEBUG` 生效）注入一次调用：

```powershell
$js = 'JSON.stringify({ret:window.__ocAndroid.postMessage("N1-GEO","geometry 1080x2400 body","n1-geo-001"),perm:window.__ocAndroid.hasNotificationPermission()})'
adb -s emulator-5870 shell "am start -n $ACT --es oc_debug_eval '$js'"
```

回读（`logcat -s OcWebHost/Eval` 原文）：

```
eval=JSON.stringify({ret:window.__ocAndroid.postMessage("N1-GEO","geometry 1080x2400 body","n1-geo-001"),perm:window.__ocAndroid.hasNotificationPermission()})
  -> "{\"ret\":0,\"perm\":1}"        # 0=post 成功，1=已授权
```

**注入的是「发通知」这一个动作；「点通知」是真·手指点（见 §3、§4）。**
这两件事必须分开记账：本报告不把注入调用写成"用户点击触发的"。

---

## 3. 通知真出现的证据

`adb -s emulator-5870 shell dumpsys notification --noredact`（关键片段原文）：

```
NotificationRecord(0x0874d56f: pkg=com.xiufeigo.opencodeshell.web.debug user=UserHandle{0} id=-1771476117 tag=null importance=3
key=0|com.xiufeigo.opencodeshell.web.debug|-1771476117|null|10212:
Notification(channel=oc_turns shortcut=null contentView=null vibrate=null sound=null defaults=0
flags=AUTO_CANCEL color=0x00000000 category=status vis=PRIVATE))
        uid=10212 userId=0
        opPkg=com.xiufeigo.opencodeshell.web.debug
        icon=Icon(typ=RESOURCE pkg=com.xiufeigo.opencodeshell.web.debug id=0x01080077)
        flags=AUTO_CANCEL
              fullscreenIntent=null
              contentIntent=PendingIntent{dc3f22: PendingIntentRecord{3c16b22 android startActivity}}
              deleteIntent=null
              number=0
              extras={
                 android.title=String (N1-GEO)
                 android.text=String (geometry 1080x2400 body)
                 android.reduced.images=Boolean (true)
                 android.subText=null
```

修复前那一轮（`N1-CLICK`）的同类片段：

```
android.title=String (N1-CLICK)
android.text=String (N1 点击回会话 正文)
```

**判定 PASS**：通知真进了系统 —— `channel=oc_turns`、`vis=PRIVATE`、`category=status`、
`flags=AUTO_CANCEL`、`contentIntent` 非空、title/text 原文正确。

---

## 4. 真点 + 回到会话（三项证据）

### 4a 真实点击（不是等价 intent）

**坐标来源**：点之前**当场** `uiautomator dump` 重新解析（**本仓教训：bounds 会跳变**）。
实测本轮内 bounds 就跳过两次：换几何前 `[1416,244][2448,287]`，换到 1080×2400@420 后
`[179,684][996,741]` —— 同一张卡片，坐标完全不同。

```
命令: adb -s emulator-5870 shell cmd statusbar expand-notifications
命令: adb -s emulator-5870 shell uiautomator dump /sdcard/n1_g.xml
NODE 原文:
  <node index="0" text="N1-GEO" resource-id="android:id/title" class="android.widget.TextView"
        package="com.android.systemui" clickable="false" enabled="true"
        bounds="[179,684][996,741]" />
解析: x=(179+996)/2=588   y=(684+741)/2=712
命令: adb -s emulator-5870 shell input tap 588 712
```

**这是 `input tap` 真点系统通知卡片**（节点 `package="com.android.systemui"`），
**没有**用 `am start` 发等价 intent，**没有**用 BACK(4) 去关面板（面板是被点击本身消掉的）。
这正是 BR1 §2b 明确没做到的那一步。

### 4b 证据 A：前台 Activity

```
命令: adb -s emulator-5870 shell dumpsys window | grep mCurrentFocus
  mCurrentFocus=Window{c278394 u0 com.xiufeigo.opencodeshell.web.debug/
                        com.xiufeigo.opencodeshell.web.WebHostActivity}

命令: adb -s emulator-5870 shell dumpsys activity activities | grep topResumedActivity
  topResumedActivity=ActivityRecord{40374b0 u0 com.xiufeigo.opencodeshell.web.debug/
                                    com.xiufeigo.opencodeshell.web.WebHostActivity t11}
```

`singleTask` + `SINGLE_TOP|CLEAR_TOP` 生效：回到**同一个** `WebHostActivity`（pid 全程 `5590` 未变 ⇒
走的是 `onNewIntent`，**没有叠第二个 Activity**）。

### 4c 证据 B：收到的 intent extra 原文

宿主消费日志（`logcat -s OcWebHost`，点之前已 `logcat -c` 清空 ⇒ 这行只可能来自这次点击）：

```
I OcWebHost: opened from notification, session=n1-geo-001
```

> 说明：`dumpsys activity intents` 对本包**没有**输出含 `oc_session_id` 的记录
> （`dumpsys activity intents` 只列 IntentFilter 匹配，不列已消费 extra；实测该子命令
> 在本 build 上查不到本包的 extra）。**因此 extra 原文取自宿主自己 `Log.i` 打出来的值**，
> 该行由 `consumeNotificationIntent` 在 `getStringExtra(EXTRA_SESSION_ID)` 之后立即打印
> （`WebHostActivity.kt:677`），逐字对应 dumpsys 里那条 contentIntent 带进去的值。

### 4d 证据 C：页面是否真的收到

点之前先在页面装监听器：

```javascript
window.__n1=[];
window.addEventListener("oc:openSession", function(e){ window.__n1.push(e.detail.sessionId); });
```

点击后回读（`logcat -s OcWebHost/Eval` 原文）：

```
-> "{\"got\":[\"n1-geo-001\"],\"href\":\"http://127.0.0.1:41933/\"}"
```

**页面 `e.detail.sessionId` 拿到的是裸 id `n1-geo-001`。**

### 4e 🔴 查出的真 bug：**会话标识被双重 JSON 编码**

修复**前**那一轮，同样的三次真实点击，回读到的是：

```
-> "{\"got\":[\"{\\\"sessionId\\\":\\\"n1-session-777\\\"}\"],\"href\":\"http://127.0.0.1:46149/\"}"
```

即页面拿到的是 **`'{"sessionId":"n1-session-777"}'` 这个字符串**，而不是 `n1-session-777`。

**根因**（改前 `WebHostBridge.deliverOpenSession`）：

```kotlin
val payload = JSONObject().apply { put("sessionId", sessionId ?: "") }.toString()
ctx.runOnUiThread {
    ctx.evaluateJs("window.__ocAndroid && window.__ocAndroid.onOpenSession(${quote(payload)});")
}
```

`onOpenSession(sessionId)` 的入参按 `oc-host.js:282-291` 是**裸 id**，这里却又包了一层 JSON
⇒ 页面拿它当 session id 去查会话**必然查不到** ⇒
**「点了通知，但没回到那个会话」—— 名存实亡**。
（对比：`applyInsets` 的入参确实设计成 JSON 串，JS 侧 `JSON.parse`；`_emitFilePicked` 走的是另一条路。）

**影响面**：BR1 §2b 之所以判「PASS 带保留」，是因为它只断言到
`am start --es oc_session_id br1-session-001` → 宿主日志打对为止，
**没往页面侧读到 `onOpenSession` 的入参**，所以这个 bug 当时没暴露。

### 4f 判定

- 通知真出现：**PASS**
- 真·手指点卡片：**PASS**（BR1 的保留点已做掉）
- 点击后回到会话 —— 前台 / extra / 页面 三项证据齐备：**PASS**（修掉 §4e 后）

---

## 5. 修了什么

### 5a diff —— `webapp/src/main/java/com/xiufeigo/opencodeshell/web/host/WebHostBridge.kt`

```diff
-    fun deliverOpenSession(sessionId: String?) {
-        val payload = JSONObject().apply { put("sessionId", sessionId ?: "") }.toString()
-        ctx.runOnUiThread {
-            ctx.evaluateJs("window.__ocAndroid && window.__ocAndroid.onOpenSession(${quote(payload)});")
-        }
-    }
+    /**
+     * 把通知带回来的 sessionId 交给页面。
+     *
+     * 🔴 N1 修掉的 bug：原先这里把 sessionId **再包一层 JSON** 才发给页面 ……
+     * 真机证据见 research/web/NOTIFICATION_CLICK_CLOSE.md §4。
+     * 现在直接发 sessionId 本身；pageArg() 是本仓唯一的取值口径。
+     */
+    fun deliverOpenSession(sessionId: String?) {
+        val arg = NotifierHost.clickPlan(sessionId).pageArg()      // L231
+        ctx.runOnUiThread {
+            ctx.evaluateJs("window.__ocAndroid && window.__ocAndroid.onOpenSession(${quote(arg)});")
+        }
+    }
```

### 5b diff —— `NotifierHost.kt`：抽出纯函数 `clickPlan`（L48-83）

```kotlin
data class ClickPlan(
    val flags: Int,
    val fromNotification: Boolean,
    val sessionId: String?,
    val notifyId: Int,
    val requestCode: Int,
) {
    fun pageArg(): String = sessionId.orEmpty()      // L66  ← 修复点：裸 id，不是 JSON
}

fun clickPlan(sessionId: String?): ClickPlan {       // L70
    val sid = sessionId?.takeIf { it.isNotBlank() }
    return ClickPlan(
        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP,
        fromNotification = true,
        sessionId = sid,
        notifyId = sid?.hashCode() ?: NO_SESSION_NOTIFY_ID,
        requestCode = sid?.hashCode() ?: 0,
    )
}
const val NO_SESSION_NOTIFY_ID = 1                   // L83
```

`postTurnFinished` 改为**只走这一个构造点**（L112/114/116/120/137），行为与改前逐字等价
（`sessionId?.hashCode() ?: 1` ⇒ `sid?.hashCode() ?: NO_SESSION_NOTIFY_ID`，`NO_SESSION_NOTIFY_ID = 1`）。

### 5c 反向验证（真机）

修复前后同一套「注入发通知 → 展开 → 解析 bounds → `input tap`」流程，各点一次：

| 轮次 | APK | 页面 `e.detail.sessionId` 实测 |
|---|---|---|
| 修前 | 基线 | `'{"sessionId":"n1-session-777"}'` ❌ |
| 修后（默认几何） | 修复版 | `"n1-fixed-001"` ✅ |
| 修后（1080×2400@420） | 修复版 | `"n1-geo-001"` ✅ |

---

## 6. 绊线与反向验证

**新增**：`webapp/src/test/java/com/xiufeigo/opencodeshell/web/host/NotificationClickPlanTest.kt`（**7 例**）

| 用例 | 守护的量 |
|---|---|
| `clickIntentCarriesTheSessionIdVerbatim` | 点击意图带的会话标识逐字正确 + 两个 extra 键名 |
| `blankOrMissingSessionIdMeansNoSession` | 空/空白/null ⇒ 不编假会话；`pageArg()==""`；兜底 notifyId |
| `flagsKeepSingleTopAndClearTop` | `SINGLE_TOP`/`CLEAR_TOP` 都在（不叠第二个 Activity） |
| `distinctSessionsDoNotShareNotificationId` | 不同会话不共用 notifyId / requestCode（不互相覆盖） |
| `pageArgIsTheBareSessionIdNotAJsonBlob` | **本轮真 bug**：交页面的必须是裸 id |
| `postTurnFinishedWiresThePlan` | **防"纯函数摆设"**：生产代码确实走 `clickPlan()` |
| `deliverOpenSessionUsesPageArg` | **防"纯函数摆设"** + 禁止 JSON 包装回归 |

**判据与守护对象一致**：这次不是设备侧断言，而是把"构造意图"这段**搬进纯函数**再断言，
所以能在单测里判；设备侧只负责证明"纯函数接的那一头"（真实点击 → 页面）也通（§4）。

### 反向验证：撤回修复 ⇒ 必须变红

把 `pageArg()` 改回双层 JSON（`sessionId?.let { "{\"sessionId\":\"$it\"}" } ?: ""`）后重跑：

```
> Task :webapp:testDebugUnitTest FAILED
NotificationClickPlanTest > pageArgIsTheBareSessionIdNotAJsonBlob FAILED
39 tests completed, 1 failed

org.junit.ComparisonFailure: N1: 页面拿到的不该是再包一层的 JSON 串……（真机证据：
research/web/NOTIFICATION_CLICK_CLOSE.md §4）expected:<[n1-session-777]> but was:<[{"sessionId":"n1-session-777"}]>
```

**恰好 1 条变红，且 diff 正是那个 bug**。改回后 `:webapp` 39 例全绿（§8）。

**设备侧复现脚本**：`research/web/n1-notify-click.ps1 -Serial emulator-5870`
（可复现：注入发通知 → `dumpsys notification --noredact` → `expand-notifications` →
`uiautomator dump` **当场解析 bounds** → `input tap` → 三项证据）。
> 注：脚本里 JS 用**单引号**包住整段再交给 `adb shell`（BR1 §0 同一条坑：
> 远端 shell 会吃掉括号/引号，PowerShell 侧也会因嵌套引号解析失败）。

---

## 7. 未闭环 / 测不到

1. **官方 UI 未接线"切到会话"** —— `oc-host.js` 提供了 `setOpenSessionHandler`（L292-294），
   但本轮实测官方 UI 页面可见文本是
   `Projects / Add project / Settings / Help / Loading`，
   **没有注册 handler**（探测 `!!__ocAndroid.setOpenSessionHandler` 只证明 facade 上有方法）。
   所以本轮"回到会话"的证据是**会话标识送达页面**（`e.detail.sessionId` 正确），
   **不是"页面已渲染出那个会话"**。后者需要官方 UI 自己接线，属上游范围，本轮未做。
2. **冷启动（app 未运行）时点通知**未单独跑一轮。`onCreate` 路径同样调
   `consumeNotificationIntent`（L298），但它 `postDelayed(400ms)` 就发 `onOpenSession`，
   冷启动时页面 400ms 内未必就绪 ⇒ **有可能丢**。本轮时间/设备预算内未复现验证，
   **不下"这是 bug"的结论**，如实记为未闭环。
3. **`dumpsys activity intents` 查不到本包 extra**（见 §4c），extra 原文取自宿主日志。
4. `WebHostActivity` 的 `exported="true"`（BR1 §0 记的 debug 注入面）**本轮未动**。

---

## 8. 回归

```
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :webapp:testDebugUnitTest :app:testDebugUnitTest --offline --rerun-tasks
```

| 模块 | 结果 |
|---|---|
| `:webapp:testDebugUnitTest` | **39 例 / 0 失败 / 0 错误**（改前 32 例 ⇒ **+7**，只增不减） |
| `:app:testDebugUnitTest` | **6136 例 / 0 失败 / 0 错误**（677 个测试类，全量，未筛选） |

`:webapp` 分项：

```
DebugEvalChannelTest          tests=2 fail=0 err=0
FallbackPageTouchTargetTest   tests=2 fail=0 err=0
ImeOcclusionTest              tests=8 fail=0 err=0
NotificationClickPlanTest     tests=7 fail=0 err=0   <-- 本轮新增
InputPathHotspotTest          tests=6 fail=0 err=0
SetupDialogTouchTargetTest    tests=8 fail=0 err=0
WebViewPreflightTest          tests=6 fail=0 err=0
---- TOTAL tests=39 failures+errors=0 skipped=0 ----
```

> **未删、未放宽任何断言**；改前改后 `:webapp` 全绿。
> 构建环境注记：Gradle 8.11 拒绝本机默认 JDK 25（报 `25.0.4.1`），
> 本轮统一用 Android Studio 自带 **JBR 21**（`JAVA_HOME`）跑构建与测试。

`:app` 里与通知相关的类（其余 671 个类同样全绿）：

```
AppNotifierTest                          tests=17 f=0 e=0
NotificationSoundsModelTest              tests=5  f=0 e=0
NotificationSystemTest                   tests=21 f=0 e=0
NotificationWithheldOnBackgroundV63Test  tests=5  f=0 e=0
NotifyBackgroundDeliveryTest             tests=12 f=0 e=0
NotifyCoveredWindowV68Test               tests=4  f=0 e=0
```

> `:app` 的 `AppNotifier` 是**原生 app 自己的通知**（另一条链路），
> 本轮改动不碰 `app/**`；跑它是为了确认"通知"这件事在 app 侧没被牵连。

---

## 9. 收尾复核

### 9a 关掉自己起的实例

```
命令: adb -s emulator-5870 emu kill
  OK: killing emulator, bye bye
  OK
```

**杀之前**在列的进程（确认哪台是我起的）：

```
pid=52456 avd=DSH_T80_Tablet port=5654
pid=26272 avd=DSH_T80_Tablet port=5654
pid=10044 avd=DSH_T80_Phone  port=5656
pid=37252 avd=DSH_T80_Phone  port=5656
pid=52120 avd=OC_Notify_Test port=5870     <-- 本轮起的
pid=16712 avd=OC_Notify_Test port=5870     <-- 本轮起的
```

### 9b 复核 1：`adb devices`（`5870` 已不在列）

```
List of devices attached
emulator-5654          device product:sdk_gtablet_x86_64 model:Pixel_Tablet device=emu64xa transport_id=134
emulator-5656          device product:sdk_gtablet_x86_64 model:Pixel_Tablet device=emu64xa transport_id=136
```

### 9c 复核 2：进程 0 残留

```
OC_Notify_Test 残留进程数 = 0
  (他人实例, 未动) pid=52456 avd=DSH_T80_Tablet
  (他人实例, 未动) pid=26272 avd=DSH_T80_Tablet
  (他人实例, 未动) pid=10044 avd=DSH_T80_Phone
  (他人实例, 未动) pid=37252 avd=DSH_T80_Phone
```

**用户自己的 `DSH_T80_*` 两台仍在运行，未被本轮触碰。**

### 9d 复核 3：端口 5870 已释放

```
端口 5870 无 LISTEN —— 已释放
```

复核 4：`5855-5880` 段无任何 LISTEN ⇒ **5860 段（另一路）本轮未占用、未干扰**。

### 9e 其它收尾

| 项 | 状态 |
|---|---|
| `.agent-gradle-lock` | ✅ 本轮 00:44:22 取得，**收尾已释放** |
| `adb kill-server` | **未执行**（`adb devices` 收尾后仍正常列出 2 台用户设备） |
| `-a` / 通配符 kill | **未使用**（只按 serial `emulator-5870`） |
| 后端 `127.0.0.1:4095` | **未起停**，全程只 `adb reverse tcp:4095 tcp:4095` |
| `wm size/density` / `enable_freeform_support` 改动 | 随本轮 AVD 一起销毁（实例已关，无残留设备状态） |
| `research/HOUSEKEEPING.md` | ✅ 已补记本轮关闭记录，并闭环另一路留下的"故意保留"条目 |
| AVD 定义 `OC_Notify_Test` | ⚠️ **仍留在磁盘**（进程已关）。按 §四 的规矩只删 `OC_*`，留待产品负责人决定；需要时 `avdmanager delete avd -n OC_Notify_Test` |

### 🔒 设备纪律

**本轮碰过的 serial：只有 `emulator-5870`。**

- `emulator-5654`（`DSH_T80_Tablet`）、`emulator-5656`（`DSH_T80_Phone`）—— **用户自己的实例，全程未触碰**
- `emulator-5860` —— **另一路（perf 轮）占的 5860 段实例，全程未触碰**
  （它在收尾时已不在 `adb devices` 列里，是**那一路自己关的**，不是本轮；本轮只对 `emulator-5870` 执行过 `emu kill`）

### 本轮改动文件

- `webapp/src/main/java/com/xiufeigo/opencodeshell/web/host/NotifierHost.kt`（抽出 `ClickPlan`/`clickPlan`）
- `webapp/src/main/java/com/xiufeigo/opencodeshell/web/host/WebHostBridge.kt`（修双层 JSON 编码）
- `webapp/src/test/java/com/xiufeigo/opencodeshell/web/host/NotificationClickPlanTest.kt`（新增 7 例）
- `research/web/NOTIFICATION_CLICK_CLOSE.md`（本文件）、`research/web/n1-notify-click.ps1`（复现脚本）
- **未触碰**：`app/**`、`_upstream/**`、`.github/**`、`webapp/build.gradle.kts`
