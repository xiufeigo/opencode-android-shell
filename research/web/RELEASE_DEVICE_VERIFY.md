# RELEASE_DEVICE_VERIFY — 签名 release 包在设备上真跑一遍

> **执行日期**：2026-10-05（硬期限 2026-10-07 之前 ✅）
> **仓库**：`C:\sensorsdata\main\program\opencode-android-shell`
> **被测物**：`research/deliver/opencode-shell-web-release-nor8.apk`（签名 release，**非 R8**）
> **设备**：`emulator-5860`（AVD `OC_Perf_Test`，**本轮自建**）
> **一句话**：**release 包能装、能起、官方 UI 真渲染（截图 + 55 个真实会话标题）**；
> 但派单的前提「两条新加的保护都在这个包里」**经字节级验证为假** ——
> **WebView 预检与诊断页根本不在这个 APK 里**，兜底页在真实故障下**也没有出现**。

---

## 0. 结论速查

| 项 | 结论 |
|---|---|
| 包身份 | ✅ md5/size/badging/apksigner 全部对上 |
| 能否安装 | ✅ `Success`（3 s） |
| 能否启动 | ✅ `am start -W` `LaunchState: COLD` / **`TotalTime: 551`** + 前台 focus 证据 |
| 官方 UI 是否渲染 | ✅ **是**（截图肉眼 + uiautomator a11y 55 个非空文本节点，含真实会话标题） |
| release 的 CDP 读数 | ❌ **测不到 + 原因**：release 无 devtools socket（`BuildConfig.DEBUG=false`） |
| debug 的 CDP 读数 | ✅ 拿到（`title=OpenCode` / `origin` / **910** DOM 节点 / 可见文本前 200 字） |
| 首屏（release） | `Z.firstDraw 412ms` / `DCL 1297ms` / **`FCP 1448ms`** |
| 首屏（debug） | `Z.firstDraw 1239ms` / `DCL 1285ms` / **`FCP 1409ms`** |
| 预检是否误触发 | ⚠️ **无法按预期验证 —— 该 APK 内没有预检代码**（§6） |
| 兜底页是否可用 | ❌ **未出现**。断开后端后显示裸 JSON 错误，**不是**兜底页/诊断页（§7） |
| 收尾 | ✅ `OC_Perf_Test`/`emulator-5860` 已关，**0 残留进程，端口 5860 无监听**（§9） |
| 碰过的 serial | **仅 `emulator-5860`**；`emulator-5870`(OC_Notify_Test) 与用户 5654/5656 全程未碰（§10） |

---

## 1. 包身份核对（原文抄录）

### 1.1 大小与 md5

```
======== opencode-shell-web-release-nor8.apk ========
SIZE=3948209  MD5=9ab8ee300027f9847c2b220bb26f2b98
======== opencode-shell-web.apk ========
SIZE=8636901  MD5=8dcffc572ad469f472f61594f12ea990
```

- release **3,948,209 B** / md5 `9ab8ee300027f9847c2b220bb26f2b98` ⇒ **与派单一致 ✅**
- debug **8,636,901 B** / md5 `8dcffc572ad469f472f61594f12ea990` ⇒ 与派单 `8dcffc57…` 一致 ✅

### 1.2 `aapt2 dump badging`（两个包逐字一致）

```
package: name='com.xiufeigo.opencodeshell.web.debug' versionCode='1' versionName='0.1.0-webhost' platformBuildVersionName='15' platformBuildVersionCode='35' compileSdkVersion='35' compileSdkVersionCodename='15'
minSdkVersion:'26'
targetSdkVersion:'35'
uses-permission: name='android.permission.POST_NOTIFICATIONS'
uses-permission: name='android.permission.INTERNET'
uses-permission: name='com.xiufeigo.opencodeshell.web.debug.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'
application-label:'OpenCode Web'
```

> ⚠️ **包名带 `.debug` 后缀**（`:webapp` 模块的 `applicationId` 未去 `debug`），
> 派单里的包名 `com.xiufeigo.opencodeshell.web.debug` 与实际**一致**，不是笔误。
> **后果**：release 与 debug **包名完全相同** ⇒ 无法共存，本轮 release vs debug 对比
> 必须「卸载 → 换装」串行做（§5 已这么做）。

### 1.3 `apksigner verify -v --print-certs`

**两个包签名逐字相同**（同一把 debug key；release 用 debug key 签，属已知情况）：

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
```

**只有 v2**（无 v1/v3/v4）—— 意味着**不能装到 Android < 7.0**（v1 缺失）。
本包 `minSdk=26`（Android 8.0）⇒ 自洽，但**没在 Android 6/7 上验证过**。

### 1.4 安装后的运行时标志（决定 CDP 可用性的关键）

```
release:  flags=[ HAS_CODE ALLOW_CLEAR_USER_DATA ]                                    ← 无 DEBUGGABLE
debug:    flags=[ DEBUGGABLE HAS_CODE ALLOW_CLEAR_USER_DATA ]                          ← 有 DEBUGGABLE
```

---

## 2. 设备（自建，纪律合规）

| 项 | 值 |
|---|---|
| serial | **`emulator-5860`** |
| AVD | `OC_Perf_Test`（**我们自己的 `OC_*`** ✅） |
| 端口 | **5860**（≥5860 偶数；起之前实测 5860/5862/5864 **全 FREE**） |
| 镜像 | `system-images/android-35/google_apis_playstore_tablet/x86_64`（**已装，只用已装镜像** ✅） |
| 形态 | `hw.device.name = pixel_tablet` |
| 几何 | `Override size: 1080x2400` / `Override density: 420`（Physical 2560x1600@320） |
| boot | `sys.boot_completed=1`（轮询**第 1 次就为 true**，耗时 3 s —— 那时已在 §"启动" 步骤前并行跑了约 3 分钟，**实际开机远早于此**；本轮未单独计时 boot 全程） |
| WebView | **`com.google.android.webview 124.0.6367.219`** ✅（≥105，且是实测正常那个版本） |
| 后端 | `http://127.0.0.1:4095/` → **HTTP 200**（**Lead 起的，我全程未启停**） |

> `wm` 说明：首次 `wm size/density` 读数**就是** `Override 1080x2400 / 420`。
> **我没有下发过任何 `wm` 命令**（`adb logcat`/shell 历史里无 `wm size`/`wm density`），
> 因此收尾**无需复原 wm**（§9 已复核）。

---

## 3. 前置身份检查（确认跑的是官方 UI）

派单要求「先播种口令 + CDP 取 `document.title`/DOM 节点数/可见文本，**未通过则数据作废**」。
本轮 CDP 对 release **不可用**（§4.1），故改用可用的等价通道，并**如实标注方法差异**。

### 3.1 口令播种：派单前置条件其实**没有触发**

先跑了既有的 `research/perf/seed_password.ps1`，结果：

```
[00:44:29] === seed web ===
--- UI nodes (text/hint/class) ---
[00:44:38] !! no EditText found -> cannot seed
```

原因（已定位，不是脚本坏了）：**`OC_Perf_Test` 这个 AVD 上早就装过本包且 SecretStore 里已有口令**
（此前 perf 轮次留下的 app data），`adb install -r` **保留 app data** ⇒ 首启直接放行：

```
10-04 16:44:31.886  I OcWebHost: home=http://127.0.0.1:37347/ backend=http://127.0.0.1:4095 passwordPresent=true
```

⇒ **没有配置框，测的确实是官方 UI，不是对话框** ✅

**这个结论我做了反证**（见 §5.2）：干净卸载后重装 debug，首启**确实**弹了口令框，
日志 `no password in SecretStore, asking`，证明播种路径本身是通的。

### 3.2 身份检查证据（release，uiautomator a11y 通道）

```
non-empty text nodes = 55
  | OpenCode
  | Tabs
  | Projects
  | scrollable content
  | Recent sessions
  | Today
  | O Counting from 1 to 4000, one number per line opencode-android-shell
  | Yesterday
  | D Friendly greeting DSH 临时会话
  | Older
  | S android-e2e System32
  | C DRAFT__OK chenjieyan_test
  | S Count from 1 to 999, one number per line System32
  | D New session DSH 临时会话
  | D Android 启动时手动选择 server 连接 DSH-Remote
  | D Plugin activation failure waiting for conversationEvents DSH-Desktop
  | D 排查 dsh 崩溃与插件缺失问题 DSH-Remote
```

- 这些是**真实后端会话数据**（`opencode-android-shell` / `DSH-Remote` / `DSH-Desktop` 等），
  **不可能是静态兜底页或对话框** ⇒ **前置检查通过，数据有效**。
- 截图 `R5860_release_official_ui.png`（**236,593 B**，高熵）**已肉眼确认**：
  `Home` 顶栏、`All projects` 折叠、`Search sessions` 搜索框、`Today/Yesterday/Older` 分组、
  会话图标与标题、中文标题正常显示。**与"纯白屏"（历史 21,295 B 极低熵）截然不同。**

---

## 4. 能否安装启动

### 4.1 安装

```
=== adb reverse ===
4095
host-16 tcp:4095 tcp:4095
=== install release ===
Performing Streamed Install
Success
install took 3s  LASTEXIT=0
```

### 4.2 启动（`am start -W`，release）

```
Starting: Intent { cmp=com.xiufeigo.opencodeshell.web.debug/com.xiufeigo.opencodeshell.web.WebHostActivity }
Status: ok
LaunchState: COLD
Activity: com.xiufeigo.opencodeshell.web.debug/com.xiufeigo.opencodeshell.web.WebHostActivity
TotalTime: 551
WaitTime: 554
Complete
```

前台 focus 证据（两条独立读数）：

```
mCurrentFocus=Window{df5ee1f u0 com.xiufeigo.opencodeshell.web.debug/com.xiufeigo.opencodeshell.web.WebHostActivity}
mFocusedApp=ActivityRecord{bb7af8a0 u0 com.xiufeigo.opencodeshell.web.debug/com.xiufeigo.opencodeshell.web.WebHostActivity t90}
```

**⇒ 结论：release 包能装上、能起来，官方 UI 真渲染。✅**

### 4.3 release 的 CDP：**测不到 + 原因**

```
=== devtools sockets (CDP availability) ===
（空 —— 无任何 webview_devtools_remote_* 套接字）
```

**原因（源码定位 + 运行时标志双重佐证）**：
`WebHostActivity` 里 `setWebContentsDebuggingEnabled(BuildConfig.DEBUG)`；
release 的 `BuildConfig.DEBUG=false` ⇒ WebView 调试关闭 ⇒ **不开 devtools 套接字 ⇒ CDP 不可用**。
§1.4 的 `flags` 里 release **无 `DEBUGGABLE`**、debug **有**，与该判断吻合。

⇒ `document.title` / `location.origin` / DOM 节点数 / 可见文本前 200 字，
**对 release 无法用 CDP 取得**，这是**测试通道限制，不是应用故障**。
应用侧能拿到的等价读数：`OcPerfJS doc-start href=http://127.0.0.1:40511/`
（= 壳本地回环代理的 origin）与 §3.2 的 a11y 文本。

---

## 5. 首屏：release vs debug（同设备同轮次）

方法与 `research/perf/STARTUP_AND_FRAMETIME.md` **一致**：
`Z.firstDraw`（宿主首帧）/ `OcPerfJS DCL`（DOMContentLoaded）/
`OcPerfJS paint first-contentful-paint`（FCP）/ `doc-start navStart`（导航起点）。
**明确不把最后一个 `_assets` GET 当首屏**（见 §5.3）。

### 5.1 读数对照

| 信号 | release | debug | 差（debug−release） |
|---|---|---|---|
| `am start -W` TotalTime | **551 ms** | **1384 ms** | +833 ms |
| `Z.firstDraw`（宿主首帧） | **412 ms** | **1239 ms** | **+827 ms（debug 慢）** |
| `doc-start navStart` | 395 ms | 348 ms | −47 ms |
| `DCL`（DOMContentLoaded） | **1297 ms** | **1285 ms** | −12 ms |
| `FCP`（first-contentful-paint） | **1448 ms** | **1409 ms** | **−39 ms（debug 快）** |
| `res-total`（**资源**完成，非首屏） | n=61 xfer=1,455,934 dec=2,698,104 | n=61 xfer=1,455,934 dec=2,698,104 | **完全相同** |
| 官方 UI 渲染 | ✅ 55 文本节点 | ✅ 55 文本节点 | 一致 |
| WebView | 124.0.6367.219 | 124.0.6367.219 | 一致 |

**结论：两个包的首屏（FCP/DCL）实质相同，差在噪声内（39 ms / 12 ms）；都远优于
`WEBVIEW_PREFLIGHT.md` §1.4 记的最差实测 3063 ms。**

### 5.2 差异解释（release 宿主首帧快 827 ms 的原因）

宿主侧 `Z.firstDraw` release **412 ms** vs debug **1239 ms**，差 827 ms。原因**不是**资源压缩：

- `res/` 目录两包**逐项相同**（341 entries / 226,753 B）⇒ **没有做资源压缩/shrinking**。
- `assets/www/*` 三件套**字节数完全相同**（`index.html` 9854 / `oc-host.js` 12205 / `oc-perf.js` 3307）
  ⇒ **前端资产零差异**，`res-total` 的 `xfer/dec` 完全一致也印证了这点。
- **真正的差异是 dex**：release `classes.dex` 8,745,636 B + `classes2.dex` 960,316 B；
  debug `classes.dex` 10,745,744 B + `classes4/3/2.dex` 共 260,412 B。
- 且 **release 独带 `assets/dexopt/baseline.prof`（2,539 B）+ `baseline.profm`（243 B）**
  ⇒ **release 有 baseline profile 预编译（AOT）引导，dex 加载/校验更快**
  ⇒ 宿主 `onCreate → firstDraw` 少花约 0.8 s。FCP 层面被 WebView 自身的加载时间吸收掉了。

体积差 **8,636,901 − 3,948,209 = 4,688,692 B** 的构成：主要是 dex（raw 11.86 MB → 9.71 MB）
与 `resources.arsc`（262,024 → 248,924 B）。**不是 R8 效果**（文件名 `nor8` 与
dex 未混淆、无 `mapping.txt` 痕迹一致）。

### 5.3 关于「最后一个 `_assets` GET」

```
release: P.fwd t=12740ms path=/_assets/solarized-dark-UdsmMDCL.js
debug:   P.fwd t=13075ms path=/_assets/solarized-dark-UdsmMDCL.js
```

这两个 **12.7 s / 13.1 s** 的读数是**后台语言/主题包**的陆续下载，**不是首屏**
（首屏 FCP 早在 1.4 s 就发生了，相差约 9 倍）。**本报告未把它们当首屏**，与
`STARTUP_AND_FRAMETIME.md:150`「FCP 早它 29 s」的既有结论同向。

### 5.4 debug 的 CDP 原始读数（**这是 CDP 通道唯一能拿到的那个包**）

```
# target: id=01F812F5B79786AC016ACFBB6A9730AA title=OpenCode url=http://127.0.0.1:40471/
{
 "documentTitle": "OpenCode",
 "locationOrigin": "http://127.0.0.1:40471",
 "locationHref": "http://127.0.0.1:40471/",
 "domNodeCount": 910,
 "bodyChildCount": 5,
 "readyState": "complete",
 "userAgent": "Mozilla/5.0 (Linux; Android 15; Pixel Tablet Build/AE3A.240806.046.T1; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/124.0.6367.219 Mobile Safari/537.36 OpenCodeWebHost/0.1 (W1-skeleton)",
 "ocFacadePresent": "undefined",
 "visibleTextFirst200": "Home 0 All projects Today O Counting from 1 to 4000, one number per line opencode-android-shell Yesterday D Friendly greeting DSH 临时会话 Older S android-e2e System32 C DRAFT__OK chenjieyan_test S Count ",
 "paintEntries": [ "first-paint=1409", "first-contentful-paint=1409" ],
 "dcl_ms": 1285,
 "loadEvent_ms": 1385,
 "navType": "navigate"
}
```

> `ocFacadePresent: "undefined"` 是一处**新发现的可疑点**：`oc-host.js` 明明
> "facade registered as document-start script"（Kotlin 侧日志），
> 但页面里 `window.oc` 取不到 ⇒ **facade 可能没真正注入到页面全局**。
> 官方 UI 仍能渲染（说明它不依赖 `oc`），但依赖 `oc.*` 的能力（重试、onFirstContent 等）
> 在此构建上**存疑**。**本轮未深挖**，列为未闭环 §11-①。

---

## 6. WebView 预检（阈值 105）：**无法按派单验证 —— 代码不在这个包里**

派单前提：「WebView 预检（阈值 105）不误触发 … **都在这个包里**」。
**该前提经字节级验证为假。**

### 6.1 资产层：`oc-preflight.js` 不存在

```
--- opencode-shell-web-release-nor8.apk  assets/www ---
  assets/www/index.html  (9854)
  assets/www/oc-host.js  (12205)
  assets/www/oc-perf.js  (3307)
```

（`WEBVIEW_PREFLIGHT.md` §4.3 记载应有 `assets/www/oc-preflight.js` ⇒ **缺**。debug 包同样缺。）

### 6.2 代码层：`classes.dex` 原始字节扫描

对 `classes.dex`（8,745,636 B）做 MUTF-8 字符串池原始字节匹配：

```
=== 阳性对照（这些是本 APK 实跑 logcat 里见过的原文，必须为 True） ===
  facade registered as document-start script   : True
  no password in SecretStore, asking           : True
  credentials stored backend=                   : True
  proxy prewarm failed                          : True
  Z.firstDraw                                   : True
=== 预检特征（应全为 False） ===
  G1 webview version                            : False
  G1 watchdog fired                             : False
  G1 showing diagnostic page                    : False
  G1 retry: re-running preflight                : False
  M.g1.diagnostic                               : False
  onFirstContent                                : False
  WebViewPreflight                              : False
  oc-preflight.js                               : False
```

**阳性对照全 True ⇒ 扫描方法有效**（不是"扫不出来"的假阴性）。
少数 `False` 的阳性项（如 `home=$url backend=...`、`OcPerfJS doc-start`）是**预期内**的：
前者是 Kotlin 字符串模板（编译期拆成拼接）、后者只存在于 JS 资产而非 dex。

### 6.3 运行时：`G1` 日志一条都没有

整个 release 冷启动的 124 行 `OcWebHost` 日志里，
`G1 | diagnostic | preflight | watchdog` **一条都匹配不到**。
而源码 `WebHostActivity.kt:179-183` 在 `launch()` 之前必打 `G1 webview version=…`。

### 6.4 结论

- **实测事实**：WebView **124.0.6367.219** 上**没有**任何预检代码在跑，
  官方 UI 正常加载 ⇒ **"预检不误触发"这句话在本包上无意义**（不是"通过了"，是"不存在"）。
- **不冒充**：本报告**不写**「预检阈值 105 验证通过」。
- **对发布风险的含义**：这个 release 包**在 WebView 83 那类"必白屏"设备上没有任何拦截**
  ⇒ 那个已实测复现的白屏故障，在这个包上**会原样白给用户**。

---

## 7. 兜底页验证：**未出现**（真实故障下显示的是裸 JSON）

### 7.1 做法（只在我这一侧断开，**没有弄坏后端**）

```
=== BEFORE: reverse list ===        host-16 tcp:4095 tcp:4095
=== remove device-side reverse ===
（reverse --list 变空）
=== host backend still alive? ===   HTTP 200 OK      ← 后端没被弄坏 ✅
```

断开后重装启动：

```
Status: ok
LaunchState: COLD
TotalTime: 455
10-04 16:48:11.804  I OcWebHost: home=http://127.0.0.1:35791/ backend=http://127.0.0.1:4095 passwordPresent=true
=== UI after backend cut ===
non-empty text nodes = 0
```

截图 `R5860_fallback_backend_cut.png`（**28,277 B**，极低熵）**肉眼内容**：

```
{"error":"Failed to connect to /127.0.0.1:4095"}
```

⇒ **黑底 + 一行裸 JSON**，**不是** C1 兜底页，**更不是** G1 诊断页
（a11y 文本节点 = 0，页面里没有"加载失败"/`d-what`/`retryBtn` 任何一项）。

### 7.2 为什么会这样

诊断页切换的两条通路（`onReceivedError` 主文档失败、`G1` 看门狗 12 s 超时）
**依赖 §6 已证明不存在的预检代码**。剩下唯一在跑的是壳的本地回环代理，
它把上游连接失败**原样当成响应体**吐给了 WebView ⇒ 用户看到裸 JSON。

### 7.3 恢复（收尾前已复原）

```
=== RESTORE device-side reverse ===
4095
host-16 tcp:4095 tcp:4095
host backend: HTTP 200 OK
```

---

## 8. 纪律核对

| 项 | 本轮 |
|---|---|
| 设备 | **只自建**：`OC_Perf_Test` / `emulator-5860`（`OC_*` 属我们）✅ |
| 端口 | 5860（≥5860 偶数；起前查 5860/5862/5864 全 FREE）✅ |
| 镜像 | `android-35/google_apis_playstore_tablet/x86_64`（已装）✅ |
| 后端 | **未启停**（只用 `adb reverse`；仅在设备侧 `--remove` 与复原）✅ |
| `adb kill-server` | **未执行** ✅ |
| `-a` / 通配符 kill | **未使用**；收尾只按 serial `emulator-5860` 关 ✅ |
| 误伤他人设备 | **无**：`emulator-5654`(DSH_T80_Tablet) / `emulator-5656`(DSH_T80_Phone) 全程未碰、未关 ✅ |
| `wm` | **未下发过任何 `wm` 命令** ⇒ 无需复原（起止读数一致，见 §2）✅ |
| 写权限 | 只写 `research/web/**`（本文件 + `research/web/relverify/**`）✅ |
| 未碰 | `app/**`、`webapp/**`、`_upstream/**`、`.github/**` **一文件未改** ✅ |

---

## 9. 收尾复核输出（逐条抄录）

### 9.1 关之前

```
=== BEFORE kill: adb devices ===
List of devices attached
emulator-5654	device
emulator-5656	device
emulator-5860	device
emulator-5870	device          ← 注意：5870 不是我起的（见 §9.4）

=== BEFORE kill: my emulator processes ===
ProcessId Name                   AVD
--------- ----                   ---
    52456 emulator.exe           DSH_T80_Tablet
    26272 qemu-system-x86_64.exe DSH_T80_Tablet
    10044 emulator.exe           DSH_T80_Phone
    37252 qemu-system-x86_64.exe DSH_T80_Phone
    49804 emulator.exe           OC_Perf_Test      ← 我的
    48136 qemu-system-x86_64.exe OC_Perf_Test      ← 我的
    52120 emulator.exe           OC_Notify_Test    ← 不是我起的
    16712 qemu-system-x86_64.exe OC_Notify_Test    ← 不是我起的

=== BEFORE kill: port 5860 ===
LocalPort  State OwningProcess
---------  ----- -------------
     5860 Listen         48136
     5860 Listen         48136
```

### 9.2 关（**只按 serial，只关自己那台**）

```
=== KILL (by serial, ONLY my own) ===
OK: killing emulator, bye bye
OK
```

### 9.3 关之后复核

```
=== AFTER: adb devices ===
List of devices attached
emulator-5654	device
emulator-5656	device
emulator-5870	device
                              ← emulator-5860 已消失 ✅

--- residue check: any process for OC_PerfTest / 5860? ---
  0 RESIDUE

=== port 5860: Listen check (the thing that matters) ===
  EMPTY -> no listener on 5860

=== port 5860: all states ===
LocalPort RemotePort    State    OwningProcess
--------- ----------    ------    -------------
     5860      59938 TimeWait             0
                              ← 仅剩一个 TIME_WAIT 残根，OwningProcess=0（无任何进程持有）
                              ← 这是 TCP 正常关闭后的内核残根，会自行回收；**不是监听**。

=== FINAL residue: OC_Perf_Test process? ===
  0 RESIDUE (no OC_Perf_Test process)

=== adb devices (final) ===
List of devices attached
emulator-5654	device
emulator-5656	device
emulator-5870	device

=== host backend untouched: ===
  HTTP 200 OK (Lead's backend still up, I never touched it)
```

**收尾结论：进程 0 残留 ✅ / 端口 5860 无监听 ✅ / 用户设备未受影响 ✅ / 后端未动 ✅**
**未执行 `adb kill-server`**（收尾后 `adb devices` 仍能正常列出 3 台设备，即为佐证）。

### 9.4 故意保留：`OC_Notify_Test` / `emulator-5870`

本轮**收尾时** `adb devices` 里多出一台 `emulator-5870`（AVD `OC_Notify_Test`，进程 52120 / 16712）。
**它不是我起的**（我全程只启动过 `OC_Perf_Test`），按 `HOUSEKEEPING.md` 规矩第 2 条
「只关自己起的 … 任何来源不明的实例一律禁止 kill」⇒ **故意保留，未关闭**。
建议：起它的那一路自己按 serial `emulator-5870` 收尾。

**同理保留**：用户自己的 `emulator-5654`(`DSH_T80_Tablet`) / `emulator-5656`(`DSH_T80_Phone`)，
全程未碰、未关。

---

## 10. 碰过的 serial

**仅 `emulator-5860`（AVD `OC_Perf_Test`，端口 5860）** —— 本轮自建，**已关闭**（§9）。

| serial | 我对它做过什么 | 收尾 |
|---|---|---|
| `emulator-5860` | `adb reverse`、安装 release、launch、`am start -W`、`logcat`、`uiautomator dump`、`screencap`/`pull`、`pidof`、端口 forward（CDP，debug 轮）、`reverse --remove` + 复原、`emu kill` | **已关，0 残留，端口已释放** |

**未触碰**（只在 `adb devices` 输出里出现过，从未对其下发任何命令）：

| serial | AVD | 归属 |
|---|---|---|
| `emulator-5654` | `DSH_T80_Tablet` | 用户的 |
| `emulator-5656` | `DSH_T80_Phone` | 用户的 |
| `emulator-5870` | `OC_Notify_Test` | **不是我起的**（见 §9.4，故意保留） |

---

## 11. 未闭环（诚实标注）

1. **`oc` facade 在 debug 上取不到**：`window.oc === undefined`，尽管 Kotlin 日志说
   "facade registered as document-start script"。官方 UI 不受影响，但依赖 `oc.*` 的
   能力存疑。**本轮未深挖。**
2. **release 无 CDP 通道**：`document.title`/`location.origin`/DOM 节点数/可见文本
   对 release **测不到**。要拿到需改 `setWebContentsDebuggingEnabled` 的门控
   （或用可 debuggable 的变体）。**未改代码**（不在本轮写权限内）。
3. **预检/诊断页从未在设备上验证过**：因为它**不在这个 APK 里**。
   `WEBVIEW_PREFLIGHT.md` §7 第 4/5 条（看门狗 12 s、`oc-preflight.js` 两条信号源）
   **依旧未闭环**，且现在多了一条更前置的问题：**它到底有没有被打进包**。
4. **C1 兜底页在真实故障下不出现**（§7）。`CLEANUP_C1.md` 记的兜底页
   **在"后端不可达"这条最常见故障上等于没有**。
5. **`seed_password.ps1` 的正按钮定位不稳**：本轮两次都没点中
   （第一次无 EditText；第二次按钮 bounds 在 IME 弹出后从 `[729,1328][978,1470]`
   变成 `[729,918][978,1060]`，脚本仍按旧坐标 tap）。最后手工按 `(854,989)` 才成功。
   脚本本身在 `research/perf/**`（**非本轮写权限**），**未改**。
6. **单轮单次测量**：首屏各只测了 1 次，**未做多轮取中位数**（`research/perf` 的
   3 轮做法）。release 与 debug 的 39 ms 差**不足以判定优劣**，只能说"噪声内"。
7. **WebView 83 的白屏场景未复现**：没有 WebView 83 的设备可用（只有 124）。
   §6 的结论是**代码缺失**（静态 + 日志双证），不是"83 上会怎样"的动态复现。

---

## 12. 附：本轮产出物

| 文件 | 说明 |
|---|---|
| `research/web/relverify/R5860_release_official_ui.png` | release 官方 UI 截图（236,593 B） |
| `research/web/relverify/R5860_debug_official_ui.png` | debug 官方 UI 截图（236,910 B） |
| `research/web/relverify/R5860_fallback_backend_cut.png` | 断后端后的裸 JSON 错误（28,277 B） |
| `research/web/relverify/logcat_release.txt` / `logcat_debug.txt` | 两轮全量 logcat（threadtime） |
| `research/web/relverify/amstart_release.txt` / `amstart_debug.txt` | `am start -W` 原文 |
| `research/web/relverify/cdp_debug_identity.txt` | debug 的 CDP 原始读数 |
| `research/web/relverify/release_index.html` | 从 APK 解出的 `index.html`（兜底页证据） |
| `research/web/relverify/measure.ps1` | 冷启动测量脚本（release/debug 共用） |
| `research/web/relverify/debug_leg.ps1` | 干净卸载→装 debug→口令门验证脚本 |
| `research/web/relverify/cdp_eval_5860.ps1` / `identity.js` | CDP 通道（由 `research/env/cdp_eval.ps1` 适配 serial/端口） |
| `research/web/relverify/_ui_release2.xml` / `_ui_debug2.xml` / `_ui_fallback.xml` | a11y 树原文 |
| `research/web/relverify/_emu_5860.log` / `.err` | 模拟器启动日志 |
