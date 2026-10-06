# UX13-P7 · 后台回合保活 SSE + 通知权限主动申请（宿主修复轮，含重建）

- 执行时间：2026-10-06 18:17 ~ 18:57（CST）
- 仓库：`D:\XiaoZhuTouTou\opencode-android-shell`
- 后端：`127.0.0.1:4095` — **全程未起停、未连、未 kill-server**
- 设备：**本轮未起模拟器**（纯宿主侧源码修复 + 构建 + 包内自查）
- 纪律：第 0 步**只读** bundle；未改 `ui/` 一字节；未 git commit；未改 `app/**`

---

## 判定摘要

| # | 事项 | 结论 |
| --- | --- | --- |
| **S1** | 上游 pageLifecycle 吃什么事件？ | **只认 `pagehide`(abort) / `pageshow`(重连) / `online`(resync) / `visibilitychange`(resync，且仅当 `visibilityState==='visible'`)**。**完全不监听 `focus`/`blur`** ⇒ 派 blur 对 SSE 状态机**彻底惰性** |
| **S2** | 「不派 pagehide、只派 blur」会不会翻转未聚焦语义？ | **不会**。上游连接层没有任何 hasFocus 闸门；`hasFocus()` 只出现在**通知闸门**里，且那里的用法是"可见且有焦点 ⇒ 不通知"（即"后台要通知"）——方向与保活**同向**，不冲突 |
| **S3** | 为什么**不**派 `visibilitychange`？ | 上游 handler 是 `document.visibilityState==='visible' && (静默<20s \|\| resync)`。合成事件**不改变** `document.visibilityState`（那由 WebView 掌握）⇒ 切后台时若派它，很可能仍命中 `visible` 分支并**主动 abort 重连**，恰恰掐死我们要保的流。**故只派 blur** |
| **F1** | 回合进行中切后台 | 不派 pagehide，改派 `blur`；排 2s 收口 runnable |
| **F2** | 空闲切后台 | **维持原 pagehide 不变**（UX8 已验收项） |
| **F3** | 回前台 | 按 onPause 走过的路径分支：保活路径派 `focus`，空闲路径派 `pageshow` |
| **F4** | 权限申请 | 首次观测到回合开始**且在前台**时，复用既有 launcher 主动申请 |
| **T1** | 用例 | **212 全绿 / 0 失败**（198 基线 + 14 新增） |
| **T2** | 反向验证 | 回退到 `bc87e72` ⇒ **真红 15 条**（新 13 + 旧 2）；恢复 ⇒ 全绿 |

---

# 第 0 步：读 bundle 定设计事实（只读）

> `ui/` 官方资源本轮**一个字节都没改**（§2 红线自检 `git status -- ui/` = 干净）。

## 0.1 上游 coordinator 的生命周期闸门 —— 原文

**文件**：`webapp/src/main/assets/www/ui/oa/runtime-DVG-cmAP.js`

命中点（字节偏移 ~23200-23600），**原文抄录**：

```js
function w(){h&&(h=!1,g+=1,f?.abort(),s({status:`connecting`,attempt:0,error:void 0}))}
function E(e){!h||a.status!==`connected`||(t.log?.info?.(`event stream resync`,{reason:e,idle:Date.now()-_}),v=!0,p?.abort(Error(e)))}
if(t.pageLifecycle){
  let e=()=>w(),
      t=()=>void C(),
      n=()=>{document.visibilityState===`visible`&&(Date.now()-_<2e4||E(`Page returned to the foreground after the event stream went quiet`))},
      r=()=>E(`Network connection restored`);
  window.addEventListener(`pagehide`,e),
  window.addEventListener(`pageshow`,t),
  window.addEventListener(`online`,r),
  document.addEventListener(`visibilitychange`,n),
  d(()=>{window.removeEventListener(`pagehide`,e),
         window.removeEventListener(`pageshow`,t),
         window.removeEventListener(`online`,r),
         document.removeEventListener(`visibilitychange`,n)})
}
```

四个监听器的语义（逐字对照）：

| 事件 | handler | 对 SSE 的作用 |
| --- | --- | --- |
| `pagehide` | `e = () => w()` | 🔴 **`f?.abort()` —— 唯一会掐断在途 SSE 的路径**（`f` 是当前 attempt 的 `AbortController`） |
| `pageshow` | `t = () => void C()` | 🟢 重连；已连接时 `C()` 因 `if(h) return m` 幂等返回 |
| `online` | `r = () => E(...)` | 🟡 仅 `resync`（abort + 重连），不因切后台触发 |
| `visibilitychange` | `n = () => ...` | 🟡 仅 resync，**且必须 `document.visibilityState==='visible'` 且静默 ≥20s** |

**`focus` / `blur` 全模块零监听**：

```
grep addEventListener(`(focus|blur)`  →  runtime-DVG-cmAP.js 命中 0
```

全 `oa/` 目录里 `hasFocus` 只出现在**通知闸门**一处（见 0.3），连接层没有 hasFocus 闸门。

### ⇒ 关键设计问题的答案

> **问**：后台时若不派 pagehide、只派 `blur`/`visibilitychange`，上游的"未聚焦"语义会不会翻转？

**答（连接层）**：**不会翻转，因为连接层压根不看焦点。** pageLifecycle 状态机是**纯 pagehide/pageshow 驱动**的二元开关（连 or 断），`visibilitychange`/`online` 只是两条 resync 旁路。派 blur = 对它**完全惰性**。

**答（通知层）**：见 0.3 —— 那里 `hasFocus()` 的用法是"可见**且**有焦点 ⇒ 不通知"，即"**没焦点反而要通知**"。保活路径不给它任何聚焦事件 ⇒ 方向同向，不冲突。

### ⇒ 为什么只派 blur、不派 visibilitychange（一个反直觉的决定）

上游 `visibilitychange` handler 的触发条件里带了 `document.visibilityState===`visible``。**合成 `dispatchEvent` 并不会改变 `document.visibilityState`** —— 那个值由 WebView 自身掌握。于是切后台那一刻派一个合成 `visibilitychange`，若此刻 `visibilityState` 仍是 `visible`（WebView 未翻转），就会命中 `E("Page returned to the foreground after the event stream went quiet")` → **主动 abort + 重连**，恰恰掐死我们要保的那条流。

所以：**保活路径只派 `blur`**（语义准确 + 对连接层惰性），把 `visibilitychange` 整个排除在派发清单外。这是本轮最关键的一个设计取舍。

## 0.2 `server.connected`（P6 记录，本轮复核确认）

`runtime-DVG-cmAP.js` 偏移 ~21183 原文：

```js
if(o.done)return{error:Error(`Event stream disconnected`),connectedAt:r};
if(o.value.type!==`server.connected`)return{error:Error(`Event stream did not start with server.connected`),connectedAt:r};
```

确认：**SSE 首帧必须是 `server.connected`，否则直接判错**。这解释了 P6 §B.1 里 4173 的 404 空转为何永远拿不到 `connected`、必然走 `await sr(rr, signal)` 的 1s 固定重连。

## 0.3 上游 notify 闸门 —— 原文 + permission 各分支

**文件**：`webapp/src/main/assets/www/ui/oa/index-D2r3WEwY.js`，偏移 ~568409，`XI(e)` 的 `notify` 方法，**逐字原文**：

```js
async notify(e,t,n){
  if( !(`Notification` in window)
   || (Notification.permission==="default"
          ? await Notification.requestPermission().catch(()=>`denied`)
          : Notification.permission) !== `granted`
   || (document.visibilityState===`visible` && document.hasFocus()) ) return;
  let r=new Notification(e,{body:t??``,icon:`https://opencode.ai/favicon-96x96-v3.png`});
  r.onclick=()=>{window.focus(),n?.(),r.close()}
}
```

`permission` 各分支（逐条对照 shim 行为）：

| `Notification.permission` | 上游动作 | 落到宿主 |
| --- | --- | --- |
| `default` | **调 `requestPermission()`** → 拿 granted/denied，再判 `!== 'granted'` | `oc-host.js` shim 调 `oc.requestNotificationPermission()` → **宿主 launcher 弹窗** |
| `granted` | 跳过申请，直接判 `!== 'granted'` = false ⇒ 继续 | 无 |
| `denied` | 跳过申请，判 `!== 'granted'` = true ⇒ **return，不通知** | 无 |

而这个 `notify()` 的**唯一调用源**是终态帧监听（`runtime-DVG-cmAP.js`）：

```js
sdk.event.listen(e=>{ if(e.type!==`session.execution.succeeded` && e.type!==`session.execution.failed`) return; ... })
```

⇒ **P6 §D.3 的死锁原文成立**：终帧不到 ⇒ `notify()` 从不被调 ⇒ `requestPermission()` 从不触发 ⇒ launcher 从不弹窗。

## 0.4 事件映射表（本轮设计定案）

| 场景 | 宿主派发 | 上游连接层反应 | 通知闸门能否评估 | 依据 |
| --- | --- | --- | --- | --- |
| **回合进行中 → 后台** | `blur`（**不派 pagehide**） | **无反应（SSE 活着）** | ✅ 能（终帧会到） | S1：上游不监听 blur |
| **空闲 → 后台** | `pagehide`（**维持原样**） | `abort()` 断流 | ➖（无回合可评） | UX8 已验收项，不变坏 |
| **回合在后台结束** | 补派 `pagehide` | `abort()` 断流（省电收口） | ✅ 已评完 | §1a `backgroundRoundRelease` |
| **保活路径 → 回前台** | `focus`（不派 pageshow） | **无反应（从未断过）** | — | 没断过流，pageshow 属于撒谎 |
| **空闲路径 → 回前台** | `pageshow` | 重连 | — | UX8 原语义 |

---

# 第 1 步：宿主修复

## 1a `WebHostActivity.kt` —— onPause 断流改回合感知

**回合活跃状态来源：复用既有原生跟踪器 `RoundActivityTracker`（`roundTracker.isRoundActive(now)`）。**
理由（按任务要求写明）：`RoundActivityTracker` 在 UX8 起就是"回合进行中"的**宿主侧唯一真值**，由注入层传感器经 `WebHostBridge.setKeepScreenOn` 实时喂（`onRoundStart` / `onRoundEnd` / `onRoundStreamSignal`），**不是只有 90s 兜底** —— `isRoundActive()` 读的是 `roundStartAtMs`/`lastActivityAtMs` 的实时值，90s 只是它内部的兜底判定。因此直接查它即可，不必从 keepScreenOn flag 反推（flag 在后台已被 `clearKeepScreenOnOnBackground()` 无条件清掉，反推反而不可靠）。

`onPause` 新结构：

```kotlin
webView.removeCallbacks(backgroundRoundRelease)
val roundActive = roundTracker.isRoundActive(System.currentTimeMillis())
if (roundActive) {
    backgroundKeepAlive = true
    Log.i(TAG, "P7 onPause: round active -> keep SSE alive (blur only, no pagehide)")
    dispatchPageLifecycleEvent("blur")
    webView.postDelayed(backgroundRoundRelease, BACKGROUND_ROUND_RELEASE_POLL_MS)
} else {
    backgroundKeepAlive = false
    dispatchPageLifecycleEvent("pagehide")
}
```

`onResume` 对称分支：

```kotlin
if (backgroundKeepAlive) {
    backgroundKeepAlive = false
    dispatchPageLifecycleEvent("focus")
} else {
    dispatchPageLifecycleEvent("pageshow")
}
```

**省电收口 runnable**（回合在后台结束时补派 pagehide）：

```kotlin
private val backgroundRoundRelease: Runnable = Runnable {
    if (roundTracker.isRoundActive(System.currentTimeMillis())) {
        webView.removeCallbacks(backgroundRoundRelease)
        webView.postDelayed(backgroundRoundRelease, BACKGROUND_ROUND_RELEASE_POLL_MS)
        return@Runnable
    }
    if (!backgroundKeepAlive) return@Runnable
    backgroundKeepAlive = false
    Log.i(TAG, "P7 background round ended -> dispatch pagehide (release SSE)")
    dispatchPageLifecycleEvent("pagehide")
}
```

> ⚠ 编译细节：`Runnable {}` 内部要把自己 post 回去（自引用），交给 Kotlin 推断会报 `Type checking has run into a recursive problem` ⇒ **类型必须显式写** `private val backgroundRoundRelease: Runnable`。这是本轮唯一一处编译返工。

## 1b 通知权限主动申请（打破死锁）

选点：**首次观测到回合开始（传感器 → `setKeepScreenOn(true)` → `tracker.onRoundStart`）且在前台**时触发。

`WebHostBridge.setKeepScreenOn` 内：

```kotlin
if (on) {
    tracker.onRoundStart(System.currentTimeMillis())
    ctx.runOnUiThread { ctx.requestNotificationPermissionOnRoundStart() }
}
```

`WebHostActivity` 新增（**复用既有 launcher，未新造**）：

```kotlin
fun requestNotificationPermissionOnRoundStart() {
    if (!isForeground()) return
    if (notificationPermissionRequested) return
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) { notificationPermissionRequested = true; return }
    val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
        == PackageManager.PERMISSION_GRANTED
    if (granted) { notificationPermissionRequested = true; return }
    notificationPermissionRequested = true
    Log.i(TAG, "P7 proactive POST_NOTIFICATIONS request on first round start (foreground)")
    requestNotificationPermission()   // ← 既有链 :547 → notificationPermission.launch(...)
}
```

**为什么选这个时机**：弹窗落在"用户刚发起一次交互"（刚发消息）的上下文里，比"启动即弹"合理得多；且与 1a 合起来把死锁**双向拆解** —— 1a 保证"闸门会被评估"，1b 保证"闸门不评估时权限也已就位"。

**上游闸门里那条 `Notification.requestPermission()` 路径保持不变**（见 0.3 原文，本轮未改 ui/ 一字节）；shim 在已 granted 时短路。

## 1c `oc-host.js` 传感器日志增强（消灭 P5/P6 误读面）

动机（P6 §B.4 原文）：`EVENT_PATH_RE = /\/api\/event\/?$/` 是**纯 pathname 匹配、不查 status** ⇒ 页面 origin(4173) 被 `LocalUiOriginServer` 按红线 3 打成 404 + `Connection: close` 的空转流**也一样会打出 `event stream OPEN`**。P5 据此误判成"4095 真流被重连风暴截断"，P6 也差点跟着走偏。缺的就是 **origin 与 status**。

改动（`teeEventStream(resp, path, absUrl)`）：

```js
function originOf(url) {
  try { return new URL(url, window.location.href).origin; } catch (e) { return "?"; }
}

var origin = originOf(absUrl || path);
var status = resp.status;
log("event stream OPEN #" + id + " " + origin + path + " status=" + status);
if (typeof status === "number" && (status < 200 || status >= 300)) {
  log("event stream NON2XX #" + id + " " + origin + " status=" + status);
}
...
log("event stream CLOSE #" + id + " " + origin + " " + reason);
```

调用点传绝对 url（`path` 只是 pathname，推不出 origin）：`teeEventStream(resp, path, url)`。

**行为不变**：仍是纯透明 tee —— `resp` 原样 `return resp;`、仍走 `pipeThrough`、未读 `resp.text()/json()`。已由 `teeStaysByteTransparent` 钉住。

**新日志形态**：一条 grep 即可区分 `http://127.0.0.1:4173/api/event status=404`（空转）与 `http://127.0.0.1:4095/api/event status=200`（真实流）。

## 1d 绊线

**新增 `webapp/src/test/java/com/xiufeigo/opencodeshell/web/BackgroundRoundKeepaliveTest.kt`（14 例）**

| 用例 | 钉住什么 |
| --- | --- |
| `onPauseIsRoundAwareNotUnconditional` | onPause 必须查 `roundTracker.isRoundActive` 且有 `if (roundActive)` 分支 |
| `roundActiveBackgroundDoesNotDispatchPagehide` | **分支结构**：blur 在 if 内、pagehide 在 `} else {` 之后 |
| `idleBackgroundStillDispatchesPagehide` | UX8 不变量：空闲后台**仍**断流 |
| `keepaliveBackgroundTracksStateForResumeSymmetry` | `backgroundKeepAlive` 置位/复位 |
| `backgroundRoundEndReleasesTheStream` | 后台回合结束**补派 pagehide**（省电不许丢） |
| `onResumeIsSymmetricWithThePausePathTaken` | focus 必须在 pageshow 之前 |
| `blurAndFocusAreInertForUpstreamConnection` | 派发函数仍是纯 `window.dispatchEvent(new Event(...))` 且不碰凭据 |
| `proactivePermissionRequestExistsAndIsForegroundGated` | 前台闸 + 防抖 + 查真实授权 |
| `proactivePermissionRequestReusesExistingLauncherChain` | **复用** `requestNotificationPermission()` → 同一个 launcher |
| `bridgeTriggersPermissionOnRoundStart` | 桥在 `onRoundStart` **之后**真的调了它（生产接线点，非死代码） |
| `eventStreamLogCarriesOriginAndStatus` | OPEN/CLOSE 带 origin+status |
| `non2xxEventStreamsAreLoggedSeparately` | 独立 NON2XX 行 + 2xx 区间判定 |
| `teeStaysByteTransparent` | **行为红线**：仍 `return resp;`、仍 `pipeThrough`、不读 body |
| `fetchWrapperPassesAbsoluteUrlToTee` | origin 能从绝对 url 推导 |

**更新 `Ux8KeepAwakeSseTripwireTest`**：`onPauseDispatchesPagehide` / `onResumeDispatchesPageshow` 两个用例**保留断言**（回退到无 pagehide 形态时它们会红，是有效的历史绊线），但注释写明不变量已从「**无条件**派 pagehide」收窄为「**空闲**后台派 pagehide」，并指明回合分支的新不变量由 `BackgroundRoundKeepaliveTest` 钉住。**没有为了让本轮修复过关而删任何断言。**

### 反向验证（真红原文）

备份 → `git checkout bc87e72 -- WebHostActivity.kt WebHostBridge.kt oc-host.js` → 跑 `:webapp:testDebugUnitTest`：

```
BackgroundRoundKeepaliveTest > eventStreamLogCarriesOriginAndStatus FAILED
BackgroundRoundKeepaliveTest > onPauseIsRoundAwareNotUnconditional FAILED
BackgroundRoundKeepaliveTest > teeStaysByteTransparent FAILED
BackgroundRoundKeepaliveTest > fetchWrapperPassesAbsoluteUrlToTee FAILED
BackgroundRoundKeepaliveTest > proactivePermissionRequestReusesExistingLauncherChain FAILED
BackgroundRoundKeepaliveTest > proactivePermissionRequestExistsAndIsForegroundGated FAILED
BackgroundRoundKeepaliveTest > idleBackgroundStillDispatchesPagehide FAILED
BackgroundRoundKeepaliveTest > onResumeIsSymmetricWithThePausePathTaken FAILED
BackgroundRoundKeepaliveTest > non2xxEventStreamsAreLoggedSeparately FAILED
BackgroundRoundKeepaliveTest > roundActiveBackgroundDoesNotDispatchPagehide FAILED
BackgroundRoundKeepaliveTest > bridgeTriggersPermissionOnRoundStart FAILED
BackgroundRoundKeepaliveTest > backgroundRoundEndReleasesTheStream FAILED
BackgroundRoundKeepaliveTest > keepaliveBackgroundTracksStateForResumeSymmetry FAILED
```

**红条统计：`tests=212 failures=15`**（新 13 + 旧 2）。

| 项 | 说明 |
| --- | --- |
| 新增用例红 | **13 / 14** |
| 未红的 1 条 | `blurAndFocusAreInertForUpstreamConnection` —— 它断言的是**既有** dispatcher 的形状，而回退后的文件里那个 dispatcher 仍在（`bc87e72` 早于 P6，但 P6 的 dispatcher 是工作区未提交改动，不在 commit 里 ⇒ 回退后 `dispatchPageLifecycleEvent` 仍带 P6 版）。**如实记录：这条不构成本次回退的判别项。** |
| 额外红的 2 条 | `Ux8KeepAwakeSseTripwireTest.onPauseDispatchesPagehide` / `onResumeDispatchesPageshow` —— 因为 `bc87e72` **完全没有** P6 的合成派发。**这两条红恰好反证了 UX8 绊线本身是活的** |
| 恢复后 | 三个文件 md5 与回退前**逐位一致**，`tests=212 failures=0` |

## 1e 测试 + 重建 + 落盘

```
$env:JAVA_HOME="C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot"
$env:ANDROID_HOME="$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat --console=plain :webapp:testDebugUnitTest
  → BUILD SUCCESSFUL ; tests=212 failures=0 skipped=0 (26 suites)
    BackgroundRoundKeepaliveTest: tests=14 failures=0
.\gradlew.bat --console=plain :webapp:assembleDebug :webapp:assembleRelease :webapp:assembleReleaseR8
  → BUILD SUCCESSFUL in 32s ; EXIT=0
```

> 🔧 **环境坑（留给下一轮）**：Android Studio 的 JBR 已升级到 **JDK 25.0.3**，Gradle 8.11.1 直接拒绝，报 `IllegalArgumentException: 25.0.3`（一个整句错误消息就是个版本号）。真正可用的是 **`C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot`**。另外本仓库无 `local.properties`，**必须显式设 `ANDROID_HOME`**。旧锁脚本 `research/web/_r2_build.ps1` 的 root 仍指向已废弃的 `C:\sensorsdata\main\program\...`，**别照抄它的 Set-Location**（只借它的锁协议）。

**三份新产物**（已复制到 `research/deliver/` 覆盖）：

| 文件 | 字节 | MD5 |
| --- | --- | --- |
| `opencode-shell-web.apk` | 16,840,057 | `debb8c57c5a8fdc1e1e7fd3c29fb5797` |
| `opencode-shell-web-release-nor8.apk` | 14,305,274 | `dbdc7f0a7440f2539ae55f2dc2b3cbba` |
| `opencode-shell-web-release-r8.apk` | 12,025,204 | `b10cc2c3d422b20abd4a2acffbb0de46` |

> 📌 交付目录沿用既有命名 `…-release-nor8.apk` / `…-release-r8.apk`。我最初按构建任务名另存了 `…-release.apk` / `…-releaseR8.apk`，**已删除**以免留下两套近义文件名误导下一轮。

**包内自查（三份逐个查，全部一致）**

| 检查项 | 期望 | debug | release-nor8 | release-r8 |
| --- | --- | --- | --- | --- |
| `assets/www/ui/**` 文件数 | 1613 | ✅ 1613 | ✅ 1613 | ✅ 1613 |
| `assets/www/ui/oa/**` 文件数 | 1590 | ✅ 1590 | ✅ 1590 | ✅ 1590 |
| 包内 `oc-host.js` md5 == 新源文件 md5 | — | ✅ | ✅ | ✅ |

🔴 **新 `oc-host.js` md5（本轮变了，登记新值）**：

```
新（本轮，源文件与三份包内一致）  3f0c9e4338697c428e52b25cc95f43bc
旧（bc87e72 的 blob）             572602f07d75c47738c5555711ef4237
```

**凭据扫描（改动文件）**：宽口径扫描命中 23 处，**逐条核对全部是既有 API 标识/注释**（`SecretStore`、`getSecret/setSecret` 桥方法名、`<input type="password">` 的正则、以及两处"不得出现 Authorization/password"的**反向断言字符串本身**），**无一为凭据**。对 `git diff` 的**新增行**单独扫描（127 行）：**0 命中**。

---

# 第 2 步：红线自检

| 红线 | 结果 |
| --- | --- |
| 不改 `ui/` 官方资源任何字节（第 0 步只读） | ✅ `git status --porcelain -- webapp/src/main/assets/www/ui/` **输出为空** |
| 不 git commit | ✅ 全部改动留在工作区，`git log -1` 仍是 `bc87e72` |
| 不起停 4095 后端 | ✅ 本轮**未连接**后端、未起模拟器；只跑 gradle |
| 不改 `app/**` | ✅ `git status` 无 `app/` 任何条目 |
| 不新造权限申请链 | ✅ 复用 `notificationPermission.launch`（`:238` 注册 / `:547` 使用） |
| tee 行为不变 | ✅ 仍 `return resp;` + `pipeThrough`，未读 body（`teeStaysByteTransparent` 钉住） |

**本轮改动文件清单（5 个）**

| # | 文件 | 性质 |
| --- | --- | --- |
| 1 | `webapp/src/main/java/com/xiufeigo/opencodeshell/web/WebHostActivity.kt` | onPause/onResume 回合感知 + 收口 runnable + 主动申请权限 |
| 2 | `webapp/src/main/java/com/xiufeigo/opencodeshell/web/host/WebHostBridge.kt` | 回合开始触发主动申请（3 行） |
| 3 | `webapp/src/main/assets/www/oc-host.js` | 日志补 origin/status + NON2XX 行（行为不变） |
| 4 | `webapp/src/test/java/com/xiufeigo/opencodeshell/web/BackgroundRoundKeepaliveTest.kt` | **新增** 14 例 |
| 5 | `webapp/src/test/java/com/xiufeigo/opencodeshell/web/Ux8KeepAwakeSseTripwireTest.kt` | 仅注释更新（历史理由），**断言未删** |

> 附带产物：`research/web/_p7_ctx.ps1`（只读取证脚本）、`research/web/_p7_backup/`（回退备份，可删）、`research/web/_p7_build.log`。
> ⚠ `git status` 里的 `AndroidManifest.xml` / `network_security_config.xml` / `CameraPermissionHostTest.kt` / `CleartextPolicyTest.kt` 是 **P3/P4 既有产物**，非本轮所致。

---

# 第 3 步：遗留 / 下一轮建议

| # | 事项 | 说明 |
| --- | --- | --- |
| **R1** | **设备验证欠一轮** | 本轮是纯源码+构建轮，**没有起模拟器实跑**。必须在设备上确认三件事：①回合进行中 HOME → 后台不再出现 `pagehide`、logcat 出现 `[oc-pow] round end session.execution.succeeded`；②`POST_NOTIFICATIONS granted=` 出现真值；③空闲 HOME 仍照旧断流。**这是本轮唯一未闭环项。** |
| **R2** | `pagehide` 的另一批监听者 | `runtime` 里另有 `E(window,'pagehide',…)` + `visibilitychange&&hidden` 的 scroll flush（落盘，无害）。保活路径不派 pagehide ⇒ 后台期间这些 flush 不跑，属可接受代价（回前台补跑） |
| **R3** | 4173 的 ~1/s 空转仍在 | P6 §F5 未做。与本轮修复无因果，**未改**（改它要动 `LocalUiOriginServer` 的红线 3 语义） |
| **R4** | 通知**投出**本身仍未验 | 即便终帧到达、权限 granted，`new Notification()` 是否真的落到 `oc_turns` 渠道（`mLastNotificationUpdateTimeMs != 0`）要 R1 一并验 |

---

**汇报**：事件映射表一句话 = **回合进行中切后台只派 `blur`（上游不监听 ⇒ SSE 不断）、空闲切后台照旧派 `pagehide` 断流、回前台按原路径分别派 `focus`/`pageshow`；`visibilitychange` 被有意排除，因为合成事件不改变 `visibilityState`，派它反而会触发上游 resync 把要保的流 abort 掉。**

改动文件 5 个（`WebHostActivity.kt` / `WebHostBridge.kt` / `oc-host.js` / 新增 `BackgroundRoundKeepaliveTest.kt` / `Ux8KeepAwakeSseTripwireTest.kt` 注释）；用例 **212 全绿 / 0 失败**（198 + 14 新），反向验证**真红 15 条**（新 13 + 旧 2，如实记录 1 条未红原因）；三份产物 `opencode-shell-web.apk` 16,840,057 B / `debb8c57c5a8fdc1e1e7fd3c29fb5797`、`opencode-shell-web-release-nor8.apk` 14,305,274 B / `dbdc7f0a7440f2539ae55f2dc2b3cbba`、`opencode-shell-web-release-r8.apk` 12,025,204 B / `b10cc2c3d422b20abd4a2acffbb0de46`，包内 ui=1613 / oa=1590 三份全对，**新 `oc-host.js` md5 = `3f0c9e4338697c428e52b25cc95f43bc`**；**Gradle 锁已释放**（`.agent-gradle-lock` 经 mavis-trash 移除，已验证不存在）；报告路径 `research/web/UX13_P7_NOTIFY_FIX.md`。