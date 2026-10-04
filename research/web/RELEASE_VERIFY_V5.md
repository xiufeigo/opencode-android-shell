# RELEASE_VERIFY_V5 —— 刚重建的 release 的设备复验

> 复验工程师：V5 ｜ 日期：2026-10-05 ｜ 仓库：`C:\sensorsdata\main\program\opencode-android-shell`
> 被测物：`research/deliver/opencode-shell-web-release-nor8.apk`，于 `e360904` 重建，**本轮之前无人安装过**
> 设备：`emulator-6030`（AVD `OC_Deliver_R2`，`android-35;google_apis_playstore_tablet` + `pixel_tablet`，
> `wm size 1080x2400` + `wm density 420`）

## 0. 结论速览

| 项 | 结论 |
| --- | --- |
| 包身份（size/md5/签名/包名） | ✅ 与声称完全一致，且**设备上 `base.apk` 的 md5 与仓库同一份** |
| 四条修复是否都在包里 | ✅ **四条全部命中**（dex 原文见 §2） |
| assets 与仓库 md5 比对 | ✅ `oc-host.js` 与 **HEAD** 逐字节一致（工作区那份是别人的未提交改动，见 §3） |
| 官方 UI 渲染 + 首屏 | ✅ 官方 UI 真实渲染，会话列表来自后端真实数据 |
| 预检放行 | ✅ `tooOld=false` + `J.g1.preflightOk` |
| **回合常亮「正常结束 ⇒ 释放」** | ❌ **没拿到 —— 而且这一轮证明它不是"没测到"，是"不成立"**（见 §6） |

**一句话**：包本身没问题（四条修复都在、身份对得上）；**真正的新发现是回合常亮的释放那一跳在真机上不会发生**，
turn 正常结束后 flag 一直挂着，每 90 s 被兜底复查重新挂一次。上一路自报"已复核 release 内容未复核"这一项，
本轮补上了：内容对得上；但**"回合常亮修复在设备上真正闭环"这句话不成立**，理由见 §6，有设备原文。

---

## 1. 包身份（机械核对，原文）

### 1.1 size / md5

```
PATH: C:\sensorsdata\main\program\opencode-android-shell\research\deliver\opencode-shell-web-release-nor8.apk
SIZE_BYTES: 3965033
EXPECT_SIZE: 3965033  MATCH: True
MD5: b063148df53a1364bf5c42ce6bdca0d3
EXPECT_MD5: b063148df53a1364bf5c42ce6bdca0d3  MATCH: True
SHA256: 58bda7b26568ed1027aa7b209343274ae9a3fb53fa7b6390389acd22c123c84f
```

### 1.2 `aapt2 dump badging` —— `package:` 行原文

```
package: name='com.xiufeigo.opencodeshell.web.debug' versionCode='1' versionName='0.1.0-webhost' platformBuildVersionName='15' platformBuildVersionCode='35' compileSdkVersion='35' compileSdkVersionCodename='15'
minSdkVersion:'26'
targetSdkVersion:'35'
uses-permission: name='android.permission.POST_NOTIFICATIONS'
uses-permission: name='android.permission.INTERNET'
uses-permission: name='com.xiufeigo.opencodeshell.web.debug.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'
application-label:'OpenCode Web'
launchable-activity: name='com.xiufeigo.opencodeshell.web.WebHostActivity'  label='' icon=''
supports-screens: 'small' 'normal' 'large' 'xlarge'
```

> **要提请注意**：这份 **release** 的 `package:` 是 `...web.**debug**`，label `OpenCode Web`。
> 即"release 变体"沿用了 debug 的 applicationId（`nor8` = no resource shrink，所以体积能压到 3.9 MB，
> 但 applicationId 没改成 `.web`）。**不是本轮发现的问题**，但它意味着 release 与 debug **不能并存**、
> 且 `POST_NOTIFICATIONS` 运行时权限要按 debug 包名去授。是否有意为之请 Lead 确认。

### 1.3 `apksigner verify --print-certs --verbose` 原文

```
Verifies
Verified using v1 scheme (JAR signing): false
Verified using v2 scheme (APK Signature Scheme v2): true
Verified using v3 scheme (APK Signature Scheme v3): false
Verified using v3.1 scheme (APK Signature Scheme v3.1): false
Verified using v4 scheme (APK Signature Scheme v4): false
Verified for SourceStamp: false
Number of signers: 1
Signer #1 certificate DN: C=US, O=Android, CN=Android Debug
Signer #1 certificate SHA-256 digest: e86b70066546bd0bdc2e6cb4d970704bc48b1a244af59c664564f030cca0510a
Signer #1 certificate SHA-1 digest: 426b682d492e08b8b5cff4a57bac2bef4070f18d
Signer #1 certificate MD5 digest: a8a01dcb84f4932ae6a181cf2524afbd
Signer #1 key algorithm: RSA
Signer #1 key size (bits): 2048
Signer #1 public key SHA-256 digest: 64cc7c4dcbadbd2bda19d3b80edc085ac18f829aaa1b18fb9095e1de021e8af0
Signer #1 public key SHA-1 digest: daad64a253a93206c4a18cbd62a561612ab5200c
Signer #1 public key MD5 digest: 2362f8eeb5360c10c38ba099ac924781
```

> release 用的是 **Android Debug 证书**（`CN=Android Debug`）、只有 **v2** 签名。这是可安装/可复验的，
> 但不是发布签名。**这一项只能判"签名自洽且与声称一致"，不能判"可用于对外分发"。**

### 1.4 设备侧同一性（本轮新增的硬证据）

安装后直接从设备上算 `base.apk` 的 md5：

```
path=/data/app/~~Vh1Oa47n2t1Jg3SeQ9rqgg==/com.xiufeigo.opencodeshell.web.debug-SzwZKJxb4V6Y39RDDNiHIA==/base.apk
b063148df53a1364bf5c42ce6bdca0d3  /data/app/.../base.apk
```

⇒ **设备上跑的这一份，就是 §1.1 里核对过的那一份**。后面所有设备结论都锚在这上面。
安装结果：`Performing Streamed Install` / `Success`（exit 0）。

---

## 2. 四条修复的包内检索命中原文

解包目录：`research/audit/rel_v5_unpack_0405/`（`classes.dex` 8,776,836 B / `classes2.dex` 960,316 B；
**四条全部落在 `classes.dex`，`classes2.dex` 零命中**）。

检索命令（可复现）：
```powershell
# 类/符号级（快，dex 结构化输出）
& "$env:LOCALAPPDATA\Android\Sdk\build-tools\36.0.0\dexdump.exe" -d classes.dex |
  Select-String -Pattern "RoundActivityTracker|WebViewPreflight|PowerPolicy|oc_session_id"
# 原始字节级（字符串常量是否存在）
# 在 classes.dex 上按 ASCII 逐字节匹配，报告命中偏移
```

### 2.1 回合常亮 —— `RoundActivityTracker` ✅

字符串级：`RoundActivityTracker` 命中 **4** 处（classes.dex 偏移 5609185 / 6282908 / 6282978 / …）。
类级 `dexdump` 原文：

```
  Class descriptor  : 'Lcom/xiufeigo/opencodeshell/web/host/RoundActivityTracker;'
  Class descriptor  : 'Lcom/xiufeigo/opencodeshell/web/host/RoundActivityTracker$Companion;'
      name          : 'roundActivityTracker'
      type          : '()Lcom/xiufeigo/opencodeshell/web/host/RoundActivityTracker;'
```

`setKeepScreenOn`（窗口 flag 写入点）与 `KEEP_SCREEN_ON` 常量也都在：
`setKeepScreenOn` 命中 2 处（偏移 7325707 / 7325724），`KEEP_SCREEN_ON` 命中 1 处（偏移 5947757）。

> **注意语义**：`setKeepScreenOn` 在 dex 里命中的是 `WebView.setKeepScreenOn`（Android SDK 的方法引用），
> `KEEP_SCREEN_ON` 那 1 处是 PowerPolicy 的位常量。**真正被"接上电"的是 `RoundActivityTracker` + 代理回调**，
> 这部分确实在包里（见 §2.1 的类与 `roundActivityTracker()` getter）。**但"在包里"≠"在设备上闭环"，
> 实测闭环失败，见 §6。**

### 2.2 WebView 预检 —— `WebViewPreflight` + `MIN_MAJOR 105` ✅

字符串级：`WebViewPreflight` 命中 **27** 处；`MIN_MAJOR` 命中 1 处（偏移 6414022）。
类级原文：

```
      type          : '(Lcom/xiufeigo/opencodeshell/web/WebHostActivity;Lcom/xiufeigo/opencodeshell/web/host/WebViewPreflight$Reason;Ljava/lang/String;)V'
      type          : '(Lcom/xiufeigo/opencodeshell/web/host/WebViewPreflight$Reason;Ljava/lang/String;)V'
      name          : 'roundActivityTracker'
```

`blockedOldWebview` / `preflightOk` 两个埋点名也都在（各命中 1 处，偏移 5982208 / 5982232）。

### 2.3 通知点击 —— `PendingIntent` ✅

`PendingIntent` 命中 29 处。与"点击回到对应会话"直接相关的证据：

```
365b3a: 1a00 8eaa    |000f: const-string v0, "oc_session_id" // string@aa8e
1d6f56: 6e20 db37 2100 |00b3: invoke-virtual {v1, v2}, Landroidx/core/app/NotificationCompat$Builder;.setContentIntent:(Landroid/app/PendingIntent;)Landroidx/core/app/NotificationCompat$Builder; // method@37db
36c7fa: 5b03 ae39    |0007: iput-object v3, v0, Lcom/xiufeigo/opencodeshell/web/host/NotifierHost$ClickPlan;.sessionId:Ljava/lang/String; // field@39ae
36c58a: 5436 ae39    |0011: iget-object v6, v3, Lcom/xiufeigo/opencodeshell/web/host/NotifierHost$ClickPlan;.sessionId:Ljava/lang/String; // field@39ae
36c6fc: 5410 ae39    |0000: iget-object v0, v1, Lcom/xiufeigo/opencodeshell/web/host/NotifierHost$ClickPlan;.sessionId:Ljava/lang/String; // field@39ae
36c50c: 5441 ae39    |001a: iget-object v1, v4, Lcom/xiufeigo/opencodeshell/web/host/NotifierHost$ClickPlan;.sessionId:Ljava/lang/String; // field@39ae
36c510: 5453 ae39    |001c: iget-object v3, v5, Lcom/xiufeigo/opencodeshell/web/host/NotifierHost$ClickPlan;.sessionId:Ljava/lang/String; // field@39ae
```

对应源 `webapp/src/main/java/com/xiufeigo/opencodeshell/web/host/NotifierHost.kt`：
`:32 const val EXTRA_SESSION_ID = "oc_session_id"`、
`:188 val pending = PendingIntent.getActivity(`、`:192 PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,`。

> `FLAG_UPDATE_CURRENT` 在字节检索里是 **MISS**——这是**预期的**：它是 int 常量 `134217728`，
> dex 字符串表里不会出现这个符号名。它以立即数形式编码在 `getActivity` 的调用点，不构成"缺失"。

### 2.4 省电策略 —— `PowerPolicy` ✅

```
365654: 6200 bf39   |0000: sget-object v0, Lcom/xiufeigo/opencodeshell/web/host/PowerPolicy;.INSTANCE:...
365660: 6e30 5bb3 1006 |0006: invoke-virtual {v0, v1, v6}, Lcom/xiufeigo/opencodeshell/web/host/PowerPolicy;.shouldKeepScreenOn:(ZZ)Z // method@b35b
369758: 6200 bf39   |0066: sget-object v0, Lcom/xiufeigo/opencodeshell/web/host/PowerPolicy;.INSTANCE:...
369770: 6e20 5cb3 5000 |0072: invoke-virtual {v0, v5}, Lcom/xiufeigo/opencodeshell/web/host/PowerPolicy;.streamingAction:(Z)Lcom/xiufeigo/opencodeshell/web/host/PowerPolicy$StreamingAction; // method@b35c
369778: 6205 bc39   |0076: sget-object v5, Lcom/xiufeigo/opencodeshell/web/host/PowerPolicy$StreamingAction;.REFUSE_WHILE_BACKGROUND:... // field@39bc
  Class descriptor  : 'Lcom/xiufeigo/opencodeshell/web/host/PowerPolicy$StreamingAction;'
```

⇒ **§2 结论：四条修复全部在包里，各有 dex 原文。**

---

## 3. assets 与仓库 md5 比对

APK 内 `assets/www/`：

```
72c62dc515f419c6765809b434147c2f  index.html        16000
bf54276929d5f044f92ab08670b2dbbe  oc-host.js        13804
11b30f25e7c8039c564f4ca67183ca13  oc-perf.js         3307
bd5a2dd84c065b14a016f9be6e4d1d2c  oc-preflight.js    2934
```

与**仓库 HEAD（`e360904`）** 比对：

```
HEAD oc-host.js md5=bf54276929d5f044f92ab08670b2dbbe size=13804
APK  oc-host.js md5=bf54276929d5f044f92ab08670b2dbbe size=13804
MATCH_HEAD: True
```

⇒ **`oc-host.js` 与仓库已提交版本逐字节一致**（`oc-perf.js` / `oc-preflight.js` 同样一致）。

> **一个必须说清楚的差异**：工作区里 `webapp/src/main/assets/www/oc-host.js` 现在是
> `2b6ee8db66451f659737f29dcfc96f45` / **16,939 B**，`git status` 显示它是 ` M`（**未提交的改动**）。
> 所以"APK vs 工作区"是不一致的，"APK vs HEAD"是一致的。
> **这不是打包缺陷**——是有人正在同一份工作区改这个文件（build intermediates 里那份仍是 13,804 B 的旧内容）。
> 本轮**没有碰** `webapp/src/**`（无写权限），原样留给它的主人；但请注意：**下一份包会与这一份不同**。

---

## 4. 官方 UI 渲染 + 首屏

- `wm size 1080x2400` / `wm density 420` 生效（`Override size: 1080x2400` / `Override density: 420`）。
- `adb reverse tcp:4095 tcp:4095` 已绑；后端 `http://127.0.0.1:4095` 探活 **HTTP 200**（**只做了 `adb reverse`，没起停后端**）。
- 播种口令：APK 保留了上一次安装的 SecretStore，日志确认
  `home=http://127.0.0.1:36289/ backend=http://127.0.0.1:4095 passwordPresent=true`，
  且 UI **真的带鉴权地拉到了真实会话列表**（见截图）⇒ 口令有效。

**首屏**（`_v5_firstscreen.png`）：官方 opencode UI 渲染完整——顶栏 `Home`、侧栏 `All projects`、
`Search sessions`、按 `Today / Yesterday / Older` 分组的真实会话（`Counting from 1 to 4000, one number per line` /
`opencode-android-shell`、`Friendly greeting` / `DSH 临时会话`、`android-e2e` / `System32` …）。
**这证明它不是"壳子"或错误页，而是上游官方 UI 拿着真实后端数据在跑。**

**UI 可用标记口径**（`OcPerf` 全序列，设备原文）：

```
A.onCreate.enter t=211ms mode=gzip
B.helpersReady t=241ms
C.webviewCreated+setContentView t=612ms
D.settingsConfigured t=613ms
E2.perfProbeRegistered t=618ms
E3.g1ProbeRegistered t=618ms
E.facadeInstalled t=618ms
F.insetsReady t=622ms
H.launch.enter t=623ms backend=http://127.0.0.1:4095 mode=gzip
I.proxyListening t=631ms port=36289 mode=gzip
J.g1.preflightOk t=631ms major=124 unknown=false
J.loadUrlIssued t=645ms url=http://127.0.0.1:36289/
G.onCreate.exit t=645ms
Z.firstDraw t=728ms
K.onPageStarted t=864ms url=http://127.0.0.1:36289/
P.fwd t=799ms path=/ code=200 upstreamMs=15 totalMs=18 ce=gzip
M.g1.firstContent t=1307ms kind=raf at=null
```

口径：首屏可用 = `Z.firstDraw 728ms`（**首帧**）+ `M.g1.firstContent 1307ms`（**首屏内容**），
无 `E.facadeInstalled` 之后的失败埋点。首屏资源经代理回源，`/api/event` 长连接在
`20:08:44.812 GET /api/event` 建立。

> **CDP 前置身份检查：测不到，原因写明（不编）**
> 任务要求用 CDP 取 `title`/DOM 节点数/可见文本。**本 release 包做不到**：
> ① `/proc/net/unix` 里**没有** `webview_devtools_remote` 抽象套接字（只有 `WebViewZygoteInit/...`）；
> ② 源码里**没有** `WebContentsDebuggingEnabled(true)`（`Select-String` 零命中）。
> ⇒ release 未开 WebView 调试，**没有 CDP 可用**。
> 我**没有**用别的东西冒充 CDP。替代证据是上面这段 `OcPerf` 埋点 + 两张截图 + 代理访问日志
> （官方 UI 资源名如 `index-Dadcbstg.js` / `eventListener-5JRELg8s.js` / `desktop-native-vJFY6KWL.js`）。

---

## 5. 预检放行证据

设备原文（`OcWebHost` tag）：

```
10-04 20:08:43.423  3104  3104 I WebViewFactory: Loading com.google.android.webview version 124.0.6367.219 (code 636771938)
10-04 20:08:43.795  3104  3104 I OcWebHost: G1 webview version=124.0.6367.219 major=124 min=105 tooOld=false
10-04 20:08:43.807  3104  3104 I OcPerf  : J.g1.preflightOk t=631ms major=124 unknown=false
10-04 20:08:43.787  3104  3104 I OcPerf  : E3.g1ProbeRegistered t=618ms
10-04 20:08:44.482  3104  3104 I OcPerf  : M.g1.firstContent t=1307ms kind=raf at=null
```

⇒ **`J.g1.preflightOk` 成立**、`tooOld=false`（124 ≥ `MIN_MAJOR 105`）、**无 `blockedOldWebview`**
（全量 logcat 里 `blocked` 零命中）、`unknown=false`（版本号是真读到的，不是猜的）。**放行成立。**

---

## 6. 回合常亮：「回合正常结束 ⇒ 释放」——❌ 没拿到，且已证明**不成立**

这是 D1 自报缺的那一跳。**本轮不仅没拿到，而且拿到了反证：这一跳在真机上不会发生。**

### 6.1 「置起」这一跳：✅ 拿到了

发一条真消息（`V5KEEPAWAKETEST`，走官方 UI 发送按钮，模型 `MiMo-V2.6-Pro`）：

```
10-04 20:11:01.357  3104  3104 I OcWebHost: D1 applyKeepScreenOn: roundActive=true foreground=true shouldKeep=true flagSet=true
```

`dumpsys window` 同步从 **0 命中 → 1 命中**（回合期间原文）：

```
      fl=KEEP_SCREEN_ON LAYOUT_IN_SCREEN LAYOUT_INSET_DECOR SPLIT_TOUCH HARDWARE_ACCELERATED DRAWS_SYSTEM_BAR_BACKGROUNDS
```

### 6.2 「回合正常结束」：✅ 真的正常结束了（截图 `_v5_turnstate.png`）

UI 上可见 `V5KEEPAWAKETEST` 已成为用户气泡，回了 `Build · MiMo-V2.6-Pro · 4s`，
输入框**回到占位符** `Ask anything, / for commands, @ for context...` ⇒ **回合 4 秒正常结束**，输入框回到可输入空闲态。

### 6.3 「释放」这一跳：❌ **没有发生**（回合结束后 4 分多钟仍挂着）

完整 `D1 applyKeepScreenOn` 时间线（设备原文，一条不漏）：

```
10-04 20:08:43.826  3104  3104 I OcWebHost: D1 applyKeepScreenOn: roundActive=false foreground=true shouldKeep=false flagSet=false   ← 启动，基线 0
10-04 20:11:01.357  3104  3104 I OcWebHost: D1 applyKeepScreenOn: roundActive=true  foreground=true shouldKeep=true  flagSet=true    ← 回合置起
10-04 20:12:31.358  3104  3104 I OcWebHost: D1 applyKeepScreenOn: roundActive=true  foreground=true shouldKeep=true  flagSet=true    ← +90s 兜底复查，**又挂一次**
10-04 20:14:01.360  3104  3104 I OcWebHost: D1 applyKeepScreenOn: roundActive=true  foreground=true shouldKeep=true  flagSet=true    ← +180s 同样又挂一次
10-04 20:15:31.361  3104  3104 I OcWebHost: D1 applyKeepScreenOn: roundActive=true  foreground=true shouldKeep=true  flagSet=true    ← +270s 同样又挂一次
```

`dumpsys window` 前后对比（**"前"= 回合前基线 0 命中；"后"= 回合结束 4 分钟之后**）：

| 时点 | `dumpsys window` 里 `fl=KEEP_SCREEN_ON` 命中数 | `dumpsys power` |
| --- | --- | --- |
| 回合前（基线） | **0** | `mWakefulness=Awake` / `mHoldingDisplaySuspendBlocker=true` |
| 回合中 | **1**（`fl=KEEP_SCREEN_ON ...`） | `mWakefulness=Awake` / `mHoldingDisplaySuspendBlocker=true` |
| **回合正常结束后 ~4 min** | **仍是 1**（`fl=KEEP_SCREEN_ON ...`） | `mWakefulness=Awake` / `mHoldingDisplaySuspendBlocker=true` |

**⇒「正常结束 ⇒ 释放」这一跳不存在。全程没有任何一条 `roundActive=false` 的释放行。**

### 6.4 卡在哪一步 —— 精确到代码条件

释放只有一条生产路径，`LoopbackProxy.kt:707-716`（回合结束的**唯一**触发点）：

```kotlin
if (roundActivity.isRoundActive(System.currentTimeMillis()) &&
    roundActivity.idleMs(System.currentTimeMillis()) >= SSE_IDLE_READ_TIMEOUT_MS   // = 35_000L (:104)
) {
    if (roundActivity.onRoundEnd()) publishRoundActive()
}
```

**这段代码只在某条流 `copyStreaming()` 退出那一刻才跑一次。** 而：

- 官方 UI **常驻**一条 `GET /api/event` 订阅（设备原文：`20:08:44.812 GET /api/event`，
  之后**再没有第二条** `/api/event` 请求 ⇒ 它一直没断）。
- 该订阅每 ~10 s 一次心跳（源码 `RoundActivityTracker.kt:68-79` 记的设备实测）⇒
  这些字节把 `lastActivityAtMs` 一直刷新 ⇒ **`idleMs` 永远 < 10 s**。
- 于是 `idleMs >= 35_000` 这个条件**在任何流退出那一刻都不可能成立** ⇒ `onRoundEnd()` 不跑 ⇒ 不释放。
- 兜底 90 s（`roundHoldTimeoutMs`）同理被心跳顶住：`isRoundActive` 返回 true ⇒
  `scheduleKeepScreenOnBackstopCheck` 每 90 s 又 `applyKeepScreenOn(true)` 一次
  ⇒ 正是 §6.3 里 +90/+180/+270 s 那三行。

源码作者其实**已经预见**了这件事，`RoundActivityTracker.kt:64-84` 写明
"官方 UI 常驻 `/event` ⇒ 90 s 兜底永远不会到期 ⇒ flag 可以**永久**挂着"，并为此加了 `maxRoundKeepMs = 30 min`
作为唯一天花板。**这与本轮设备原文完全吻合。**

### 6.5 已试过哪几种办法（都试了，都没拿到那一跳）

| # | 办法 | 结果 |
| --- | --- | --- |
| 1 | 走官方 UI 发送按钮发真消息（不用注入 JS） | ✅ 拿到置起 + 回合正常结束；❌ 释放不发生 |
| 2 | 等 SSE 静默 35 s 自然触发 `onRoundEnd()` | ❌ `/event` 心跳把 `idleMs` 顶在 10 s 内，条件永不成立 |
| 3 | 等 90 s 兜底 `roundHoldTimeoutMs` | ❌ 三次（+90/+180/+270 s）全部读到 `roundActive=true` 并**重新挂上** |
| 4 | 离开会话视图回首页，切断 `/event` EventSource，再等兜底 | ❌ +48 s 观察窗内 `windowKSO` 恒为 1，未释放 |
| 5 | 等 30 min 硬天花板（`maxRoundKeepMs`） | ⏳ **未等到**：回合起于 `20:11:01`，天花板在 `20:41:01`，超出本轮预算；且它属另一个 open item，不是"正常结束"这一跳 |

> **为什么这一轮没有用注入 JS 去凑**：D1 上一轮就是因为注入 JS 没执行、`window.__ocAndroid` 不可用才卡住。
> 本轮改成**在官方 UI 上点真实的发送按钮**，拿到的是真实回合流量（模型 4 s 回完），
> 比注入更接近用户路径，也**证明卡点不是"没驱动起来"，而是驱动起来了之后释放逻辑本身不触发**。
> 另外 §4 已说明 **CDP 在这份 release 里根本不可用**，注入式验证在本包上没有落脚点。

**⇒ 结论：`e360904` 的"设备侧显示 flag 被置起又被释放"这句话，在"正常回合结束"这个场景下不成立。**
置起成立；释放只在 **30 min 硬天花板**、**页面显式 `setKeepScreenOn(false)`**、或 **`onPause`** 这三条非"正常结束"路径上发生。

---

## 7. 收尾复核输出

`wm` 复原（我改过 `wm size` / `wm density`，已 reset）：

```
$ adb -s emulator-6030 shell wm size reset
$ adb -s $s shell wm density reset
Physical size: 2560x1600
Physical density: 320
```

⇒ 已回到 AVD 原生 `2560x1600 @ 320`，**无残留 override**。

关实例（只按 serial，且先确认是自己的 AVD/端口）：

```
$ adb -s emulator-6030 reverse --remove tcp:4095      # 只摘自己那条 reverse
$ adb devices                                          # 空
$ adb -s emulator-6030 emu kill
OK: killing emulator, bye bye
OK
```

kill 后复核：

```
=== after: emulator/qemu procs ===
total emulator/qemu process count = 0
=== after: OC_*/Medium_Desktop_B count ===
  0
=== after: port 6030 LISTEN ===
  LISTEN count = 0
=== after: adb devices ===
List of devices attached
=== gradle lock ===
  .agent-gradle-lock exists = False
```

**故意保留：0 项（我自己的）。** 我起的实例与进程已全部回收。

> **⚠️ 收尾时发现并发实例 —— 不是我的，故意保留**
> 收尾复核那一刻，`adb devices` 出现 `emulator-6050`，进程为：
> ```
> pid=34832 avd=OC_Deliver_R2 port=6050 start=10/05/2026 04:16:46 ... -avd OC_Deliver_R2 -port 6050 -read-only -no-boot-anim -no-snapshot -gpu swiftshader_indirect
> pid=4100  avd=OC_Deliver_R2 port=6050 start=10/05/2026 04:16:46 ... (qemu-system-x86_64.exe)
> ```
> **我没有起它**：我全程只用 **6030**，且我 kill 的就是 6030（`procs on port 6030 = 0` 已复核）。
> 它带 `-read-only`、端口 6050、启于 04:16:46（我 kill 6030 之后），**是另一路 agent 并发起的**。
> 按 `HOUSEKEEPING.md`「只关自己起的」，**我未动它、未动 `MCRemote_*` / `DSH_*` / `T7x_*`**。
> 若它成了孤儿，请由它的主人回收。

**红线自查**：只关 `emulator-6030` 一个 serial；只对 `OC_Deliver_R2`（`OC_*` 前缀，允许）动手；
**未** `adb kill-server`；**未** 用 `-a` / 通配符 / 按 pid 裸 kill；**未**起停后端（只 `adb reverse`，
且已摘除）；**未**写 `app/**`、`webapp/src/**`、`_upstream/**`、`.github/**`（本轮唯一写入是
`research/web/**` 与 `research/audit/**`）。

**后端状态**：`4095` 全程 **未起未停**（探活 HTTP 200 由 Lead 的进程提供，本轮只做了 `adb reverse`）。

---

## 8. 我碰过哪些 serial

| serial | AVD | 端口 | 镜像 / 设备 | 几何 | 处置 |
| --- | --- | --- | --- | --- | --- |
| `emulator-6030` | `OC_Deliver_R2` | **6030**（偶数、≥6030、避开 6010 段；启动前 6020–6060 全 FREE） | `android-35;google_apis_playstore_tablet` + `pixel_tablet` | `wm 1080x2400 @420`（已复原） | **本轮起、本轮关** ✅ |

**收尾时另见 `emulator-6050`（`OC_Deliver_R2`，`-read-only`，04:16:46 起）——不是本轮起的，本轮未碰、未 kill**
（详见 §7 的并发实例说明）。**本轮没有对它执行过任何命令。**

**只有 6030 这一个是我碰的。** 全程 `adb devices` 起始为空；结束时的 `emulator-6050` 属并发路，非我起。
未连接任何 `MCRemote_*` / `DSH_*` / `T7x_*` 实例（本机这些 AVD 均未被我启动）。

---

## 9. 未闭环 / 交给 Lead

1. **【新，最高优先】回合常亮"正常结束 ⇒ 释放"在真机上不成立**（§6，有设备原文 + 代码条件）。
   flag 在回合正常结束后仍挂着，并被 90 s 兜底反复重挂；唯一自动释放是 30 min 天花板。
   这与 `e360904` 提交信息里"设备侧显示 flag 被置起又被释放"的说法冲突，**建议先对齐这一条**。
   最小修法方向（**本轮未实施**，无 `app/**`/`webapp/src/**` 写权限）：让回合结束**不依赖 `/event` 心跳**——
   例如按 `/message` 流的显式完成事件收口，或把 `onRoundActivity` 的续命限定在**回合自身**的流上
   （`/event` 不参与续命），再复核 §6 全流程。
2. **30 min 硬天花板本身仍未在设备上验证**（§6.5 #5，超出本轮预算）。
3. **N-1 / N-2 仍需各自独立回合**（沿用上一轮结论，本轮未触碰）。
4. **release 变体沿用 debug applicationId**（`com.xiufeigo.opencodeshell.web.debug`，§1.2）
   且用 **Android Debug 证书 + 仅 v2 签名**（§1.3）——是否可对外分发，请 Lead 定调。
5. **CDP 在 release 包不可用**（§4），若后续要 DOM 级断言，需要在 release 里开
   `WebContentsDebuggingEnabled` 或改用 debug 包。
6. **工作区有一份未提交的 `oc-host.js` 改动**（§3，16,939 B）——不影响本份包（与 HEAD 一致），
   但**下一份包会与本份不同**。本轮未碰，交还其主人。

---

*本报告每条结论都附设备/工具原文。未测到的项一律写"测不到 + 原因"，无一条以推断充当通过。*
