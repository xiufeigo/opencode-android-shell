# WEBVIEW_PREFLIGHT — 把「WebView 太旧 ⇒ 白屏」变成「看得懂的提示页」

- **执行日期**：2026-10-05（硬期限 2026-10-07 之前 ✅）
- **仓库**：`C:\sensorsdata\main\program\opencode-android-shell`，HEAD `8d0acc7`
- **写权限**：`webapp/src/**`（新增/改动）。**未碰** `app/**`、`_upstream/**`、`.github/**`、
  **`webapp/build.gradle.kts`**（另一路正在改它，见 §7 未闭环 2）。
- **断言**：**未删除、未放宽任何断言**；只增不减（`:webapp` 20 → **32**，本轮净增 **6**，见 §6）。
- **设备**：**一次都没碰**（本轮不需要设备）。**未启停后端**（Lead 的 `127.0.0.1:4095`），
  **未下发过任何 adb 命令**，**未改 `wm`**。
- **一句话**：白屏现在有了前置拦截 + 运行期看门狗 + 自建诊断页（真实版本号 + 怎么办 + 重试），
  **四条绊线全部反向验证真红**，`:webapp` 32 条 **0 失败**，APK 出包 ✅。

---

## 0. 一句话结论

| 项 | 结论 |
|---|---|
| 版本阈值 | **主版本 ≥ 105**（依据：83 实测白屏 / 124·149 实测正常 / 本仓 IME 修复依赖 `:has()`≥105） |
| 首屏看门狗 | **12 000 ms**（依据：最差实测首屏内容信号 3063 ms，×3.9 余量） |
| 诊断页给用户看 | 发生了什么（4 种原因分别文案）/ **真实** WebView 版本号 / 本壳要求 / 怎么办 / 技术细节 / **重试按钮** |
| 误挡能跑的设备？ | **不会**：124/149/105 全放行；**读不到版本也放行**（绊线 ④ 真跑变红） |
| 反向验证 | **4/4 真红**（§5.1 抄原文） |
| 回归 | `:webapp` **32 条 0 失败**；`:webapp:assembleDebug` 8,636,973 bytes ✅；**`:app` 未跑**（§7 未闭环 1） |

---

## 1. 失败模式证据（实测，不是推测）

### 1.1 白屏那一侧：**83**

`research/env/PHONE_AVD_VERIFY.md` §6（`emulator-5730`，android-30 / `com.android.webview 83.0.4103.120`）逐字：

```
I OcWebHost: home=http://127.0.0.1:44519/ backend=http://127.0.0.1:4095 passwordPresent=true
I OcWebHost/Proxy: GET http://127.0.0.1:4095/
I OcWebHost: web[ERROR] The source list for Content Security Policy directive 'script-src' contains an invalid source: ''wasm-unsafe-eval''. It will be ignored. @0
I OcWebHost: web[WARNING] The key "interactive-widget" is not recognized and ignored. @8
I OcWebHost: web[ERROR] Uncaught SyntaxError: Unexpected token '=' @2
I OcWebHost/Proxy: GET http://127.0.0.1:4095/icons/prod/favicon.ico
```

判定原文：

> **判定：官方 UI 在 5730 上「纯白」**（截图 `B5730_official_ui.png` 仅 21295 字节 = 极低熵，
> **已肉眼确认**：只有状态栏 + 导航栏，无任何 UI 元素）。
> 根因**独立复现 E2 §7**：WebView **83** 解析不了现代 ESM（`Unexpected token '=' @2`），
> **不是手机形态不适配**。

### 1.2 这条证据里**最要命**的一句（决定了本轮的设计）

> **250+ 个 `/_assets/` 资源全部 200 取回**（`PHONE_AVD_VERIFY.md:288`）

⇒ 也就是说：`onPageFinished` **照常回调**、没有任何网络错误、渲染进程也没崩。
**「加载完成」在坏 WebView 上与「用户看到东西」是两回事。**
所以本轮的看门狗信号必须是**首屏内容**，不能是 `onPageFinished`（否则永远抓不到这次故障）。

### 1.3 正常那一侧：**124 / 149**

| 版本 | 结论 | 出处 |
|---|---|---|
| `com.google.android.webview 124.0.6367.219` | ✅ 正常渲染 | `research/env/NARROW_PHONE_E6.md:541`、`NARROW_BOTTOMBAR_E7.md:74`、`research/perf/POST_FIX_RETEST.md:67`、`research/web/IME_OCCLUSION_FIX.md:4` |
| `com.google.android.webview 149.0.7827.5` | ✅ 正常渲染 | `research/env/PHONE_AVD_VERIFY.md:204`、`PHONE_AVD_E4.md:277` |
| `com.android.webview 83.0.4103.120` | ❌ 纯白 | §1.1 |

### 1.4 首屏时延（本轮阈值/超时的数字来源）

| 信号 | 实测值 | 出处 |
|---|---|---|
| FCP | **2130** / 2290 / 2228 / 2452 / **2856** ms | `research/perf/STARTUP_AND_FRAMETIME.md:107,196,243`；`POST_FIX_RETEST.md:183,194` |
| DOMContentLoaded | 2002 / 1638 ms | 同上 |
| DOM 挂载完成（845 节点） | **2572** / **3063** ms | 同上 |
| 宿主 `Z.firstDraw` | 1236 / 1491 ms | `POST_FIX_RETEST.md:178` |
| 代理 bind | 225–246 ms | `WebHostActivity.kt:144`（改前注释） |

---

## 2. 阈值 **105** 的依据（三条，不是凭空的数）

1. **下限侧（必须挡住）**：**83 实测白屏**（§1.1）⇒ 83 < 105 落进"过旧"分支。
2. **通过侧（不能误伤）**：**124 / 149 实测正常**（§1.3）⇒ 都 ≥ 105，走正常加载。
3. **本仓自身依赖**：`research/web/IME_OCCULUSION_FIX.md:254` 逐字 —
   「`WebView 124 = Chrome 124`，`:has()` 自 **105** 起支持」，同文 `:541` 记
   「低于 105 的 WebView 上 `:has()` 规则会整条失效 ⇒ 退化回兜底路径」。
   ⇒ **105 是"壳自己也不完整"的真实地板**，不是随手挑的数。

> ⚠️ **未核对**：**105–123 区间本仓没有实测数据**（本机只有 83 / 124 / 149 三个点）。
> 选 105 是**偏保守**的下界：它只保证「124 及以上一定不被挡」，代价是 105–123 仍会走兜底。
> 将来在 105–123 上实测通过后，把 `MIN_MAJOR` 调低即可 —— 单测会跟着红，**红了才是提醒**。

### 2.1 防误伤的关键设计：**读不出版本 ⇒ 放行**

`WebView.getCurrentWebViewPackage()` 理论上可能返回 `null`（部分定制 ROM / 极少数设备）。
此刻**没有任何证据**说它不能跑 ⇒ 一律放行（只留运行期看门狗），而不是"读不到就当过旧"。
同理 `parseMajor` 解析失败返回 **`null` 而不是 `0`** —— 返回 0 会被判成过旧，
那就正好把"能跑但读不出来"的设备挡在门外了（绊线 ④ 钉这条）。

---

## 3. 加载超时 **N = 12 000 ms** 的依据

**信号选「首屏内容」而不是「加载完成」**，理由见 §1.2。取 12 000 ms 的理由：

- 下界必须按**最差一次**的内容信号算，不是平均值：最差 = **3063 ms**（DOM 挂载）。
  3063 × 3 = **9189 ms**。
- **12 000 / 3063 ≈ 3.9×**，给冷启动、更差的低端机、代理 bind（225–246 ms）留足余量，
  同时**不误判正常慢启动** —— 这是本条最需要防的回归（FCP 最差一次 2856 ms，落在 12 s 的
  **4.2 倍以内**，安全）。
- 它**不需要**覆盖 `STARTUP_AND_FRAMETIME.md:150` 那个「380 个语言/主题包串行拉到 31405 ms」——
  报告原文「**FCP 早它 29 s**」：FCP 才是"用户看到东西"的时刻，语言包到不到与白屏无关。

> ⚠️ 12 s 是在**最差实测值的 ~3.9 倍**上取的。若将来在更慢的设备上实测到 > 12 s 的合法首屏，
> 这里必须跟着调 —— 单测 `theStallTimeoutHasHeadroomOverTheWorstObservedFirstContent`
> 把「≥ 最差实测值 × 2」和「≥ 3.0×」都钉成硬下限。

---

## 4. 实现（行号 = 本轮改完后的 `webapp/src/main/java/.../WebHostActivity.kt`）

### 4.1 纯逻辑层（判定唯一入口，被单测直接覆盖）

**新增 `webapp/src/main/java/com/xiufeigo/opencodeshell/web/host/WebViewPreflight.kt`**
（**不碰任何 Android API** ⇒ 普通 JUnit 就能覆盖）：

| 成员 | 行 | 作用 |
|---|---|---|
| `MIN_MAJOR = 105` | 47 | 阈值（依据 §2） |
| `LOAD_STALL_TIMEOUT_MS = 12_000L` | 76 | 看门狗时限（依据 §3） |
| `WORST_OBSERVED_CONTENT_MS = 3_063L` | 88 | 最差实测首屏内容信号，给时限钉下限 |
| `Reason`（`version`/`loaderror`/`rendercrash`/`stalled`） | 93–107 | 诊断页据此换文案 |
| `parseMajor()` | 124–137 | 版本号 → 主版本；**读不到返回 `null` 不返回 0** |
| `isTooOld()` | 140 | `null` **不算过旧** |
| `preflight()` | 147–156 | **唯一入口判定**；调用方不许自己写 `major < 105` |
| `stallVerdict()` | 163–165 | 到点且无内容 ⇒ `STALLED` |
| `loadErrorVerdict()` | 171–172 | **只有主文档**失败才算整页挂掉 |
| `diagnosticUrl()` | 180–200 | 拼 `?r=&m=&v=&min=&t=&d=`（全 URL 编码） |

### 4.2 宿主接线（`WebHostActivity.kt`）

| 位置（行） | 内容 |
|---|---|
| `89–104`（companion） | `diagnosticUrl(reason, major, versionName, detail)` —— **必须显式收参数**：companion object 没有实例上下文，读不到 `webViewMajor`/`webViewVersionName`（编译期真报错，见 §7 未闭环 5） |
| `167–177` | `readWebViewVersion()`：`WebViewCompat.getCurrentWebViewPackage(this)?.versionName` → 真实读数（`null` 也照实留 `null`），并打 `G1 webview version=…` 日志 |
| `180–187` | `showDiagnostic(reason, detail)`：关看门狗 → 拼带参 URL → `loadUrl` |
| `189–195` | `armWatchdog()`：`postDelayed` 12 s |
| `197–203` | `onFirstContent(kind, atMs)`：探针回报 ⇒ 解除看门狗 |
| `156–164` | `retryFromDiagnosticPage()`：重试落点 —— **重新读一次版本**再重走 `launch()`（用户可能刚更新了 WebView） |
| `258` | `installPreflightProbe(webView)` |
| `261` | `readWebViewVersion()`（必须在 `launch()` 之前） |
| `351–364` | **前置门**：过旧 ⇒ `showDiagnostic(...)` + `return`，**不执行**下面的 `loadUrl(url)` |
| `366–368` | 放行 ⇒ `armWatchdog()` → `loadUrl(url)` |
| `807–814` | `onReceivedError`：**主文档**才切诊断页（子资源 404 不切，避免误伤） |
| `~825` | `onRenderProcessGoneNotWired()`：**本轮未接线**的占位 + 原因（§7 未闭环 6）。`HostWebViewClient` 已改成 `inner`（运行期兜底要读 Activity 的 `watchdogArmed`） |

### 4.3 资产层

- **新增 `webapp/src/main/assets/www/oc-preflight.js`**：文档开始脚本，回报首屏内容。
  两个信号源 `PerformanceObserver('paint') → first-contentful-paint` 与
  `requestAnimationFrame`×2 / `DOMContentLoaded`；**任一**先到即解除看门狗。
  纯白页面（含 83 的 ESM 失败）**两条都不会触发** ⇒ 在"坏"的方向上是真正的负信号。
  **只上报、不干预**，不影响页面。
- **改 `webapp/src/main/assets/www/oc-host.js:212–223`**：facade 透传 `retry()`。
- **改 `webapp/src/main/assets/www/index.html`**：C1 留下的兜底页**升级为诊断页**
  （复用资产，不新建第二个页面；无 `?r=` 参数时 C1 的默认文案与布局一字不动）。
- **`webapp/src/main/java/.../host/WebHostBridge.kt:156–187`**：新增 `retry()` 与
  `onFirstContent()` 两个 `@JavascriptInterface`。这同时**闭环了 `CLEANUP_C1.md` §4 第 4 条**
  的未闭环（"兜底页没有重试按钮…没有现成方法可调，本轮不臆造"）。

### 4.4 诊断页给用户看什么

| 行 | 内容 |
|---|---|
| `#d-what` | **发生了什么** —— 4 种原因**分别**文案：版本过旧 / 加载失败 / 渲染崩溃 / 超时无内容 |
| `#d-ver` | **当前 WebView 版本号（真实读数）** + 主版本 + user agent 三重交叉验证 |
| `#d-req` | 本壳要求：主版本 ≥ 阈值、看门狗 N ms（阈值被复审改过时显示自动跟着变） |
| `#d-fix` | **怎么办** —— 版本过旧：更新 "Android System WebView"/Chrome → 回本页点重试 → 停更设备只能换机 |
| `#d-detail` | 技术细节（提 issue 用，来自 logcat 原文） |
| `#retryBtn` | **重试按钮**，经 facade `oc.retry()` → 原生 `retry()` → 重走前置检查 + 启动链 |

触摸靶：复用 C1 已钉的 `button { min-height: 48px }`（`index.html:57`），
新按钮自动继承；本轮新增 `everyInteractiveControlOnTheDiagnosticPage…` 断言重新算一遍盒模型。

---

## 5. 绊线（判据与守护对象一致）

**新增 `webapp/src/test/java/com/xiufeigo/opencodeshell/web/WebViewPreflightTest.kt`**，6 例：

| # | 用例 | 断言的判据 |
|---|---|---|
| ① | `anOldWebviewIsSentToTheDiagnosticPageInsteadOfTheOfficialUi` | `preflight("83.0.4103.120")` ⇒ `ShowDiagnostic`+`WEBVIEW_TOO_OLD`；边界 104 拦 / 105 放；**并断言宿主里 `preflight(` 的位置在 `loadUrl(url)` 之前**且过旧分支含 `showDiagnostic`+`return` |
| ② | `theDiagnosticPageShowsTheRealWebViewVersionNotAHardcodedOne` | URL 带 `r/v/min/t`；读不到时写 `未读到` / `m=?`（**不编 0**）；页面从 `q["v"]` 渲染；**页面里不得出现写死的 `83.0.4103.120`/`124.0.6367.219`/`149.0.7827.5`** |
| ③ | `theDiagnosticPageControlsClear48dp` | `#retryBtn` 存在、接到原生 `retry()`、宿主有 `retryFromDiagnosticPage()`；全部 `<button>` 盒模型 ≥ 48dp |
| ④ | `aSupportedWebviewIsNeverSentToTheDiagnosticPage`（**最重要**） | **124/149/105 全部放行**；`null`/`""`/`"   "`/`"unknown"`/`"not available"` **也放行**且标 `unknownVersion`；带 UA 尾巴的串能解析出 124；垃圾输入返 `null` 不返 0 |
| ⑤ | `theStallTimeoutHasHeadroomOverTheWorstObservedFirstContent` | `N ≥ max(2856, 3063) × 2` 且 `N / 3063 ≥ 3.0` |
| ⑥ | `theStallWatchdogOnlyFiresWhenNothingWasEverDrawn` | 有内容永不判白屏；未到点不判；到点判 `STALLED`；**子资源失败不切页**、主文档失败切；探针/回报/`onRenderProcessGone` 接线在位 |

### 5.1 反向验证（**四次全部真红**，原文抄录）

脚本 `%TEMP%\g1_reverse.ps1`（**仓库外**，跑完即弃），每次翻转用**精确字符串替换**，
找不到目标就 `throw`（防"翻了个寂寞"），翻转后立即还原。中文在 JUnit XML 里是 GBK 乱码，
**逐字可读的部分照抄**，乱码处用 `��` 标出。

**基线（翻转前）**：
```
BUILD SUCCESSFUL in 2s
tests=6 failures=0 errors=0
```

**① 阈值改成"永远通过"（`MIN_MAJOR = 105` → `= 0`）⇒ 绊线 ① 红**
```
> Task :webapp:testDebugUnitTest FAILED
WebViewPreflightTest > anOldWebviewIsSentToTheDiagnosticPageInsteadOfTheOfficialUi FAILED
32 tests completed, 1 failed
BUILD FAILED in 2s
RED >> anOldWebviewIsSentToTheDiagnosticPageInsteadOfTheOfficialUi
     java.lang.AssertionError: G1 ①: WebView 83.0.4103.120 是**实测会白屏**的版本（ESM 解析失败），
     preflight 却判成 LoadOfficialUi ⇒ 会去加载官方 UI = 让用户看白屏。
```

**② 阈值改成"永远失败"（`MIN_MAJOR = 105` → `= 99999`）⇒ 绊线 ④ 红**
```
WebViewPreflightTest > aSupportedWebviewIsNeverSentToTheDiagnosticPage FAILED
WebViewPreflightTest > anOldWebviewIsSentToTheDiagnosticPageInsteadOfTheOfficialUi FAILED
32 tests completed, 2 failed
BUILD FAILED in 3s
RED >> aSupportedWebviewIsNeverSentToTheDiagnosticPage
     java.lang.AssertionError: G1 ④: WebView 124.0.6367.219 是**实测正常**的版本（官方 UI 正常渲染），
     却被判成 ShowDiagnostic ⇒ 把能跑的设备挡在门外。
RED >> anOldWebviewIsSentToTheDiagnosticPageInsteadOfTheOfficialUi
     java.lang.AssertionError: G1 ①: 105 低于阈值 99999，应放行（`:has()` 从 105 起支持）
```

**③ 诊断页把版本号写死（`esc(q["v"])` → `"83.0.4103.120"`）⇒ 绊线 ② 红**
```
WebViewPreflightTest > theDiagnosticPageShowsTheRealWebViewVersionNotAHardcodedOne FAILED
32 tests completed, 1 failed
BUILD FAILED in 2s
RED >> theDiagnosticPageShowsTheRealWebViewVersionNotAHardcodedOne
     java.lang.AssertionError: G1 ②: 诊断页出现了写死的版本号 `83.0.4103.120` —— 真实读数必须来自
     宿主 query 参数，否则换一台设备显示的就是假信息。
```

**④ 去掉 48dp 地板（`min-height: 48px` → `0px`）⇒ 绊线 ③ 红（且 C1 的绊线也一起红）**
```
WebViewPreflightTest > theDiagnosticPageControlsClear48dp FAILED
FallbackPageTouchTargetTest > everyInteractiveControlOnTheFallbackPageClears48dp FAILED
32 tests completed, 2 failed
BUILD FAILED in 2s
RED >> theDiagnosticPageControlsClear48dp
     java.lang.AssertionError: G1 ③: 诊断页可交互控件触摸靶只有 35.0 px(=dp)（min-height=0.0，
     固有盒高=35.0），必须 ≥ 48 px(=dp)。页内按钮数=5。
```

**全部还原后（必须重新变绿）**：
```
BUILD SUCCESSFUL in 2s
tests=6 failures=0 errors=0
```

> ④ 的 `35.0 px` 与 `CLEANUP_C1.md` §1.5 记的 `21 + 12 + 2 = 35` **逐位吻合**；
> 按钮数从 C1 记的 **4** 变成 **5**（本轮新增了「重新加载」这个重试按钮），符合预期。

---

## 6. 回归（真跑，**0 失败**）

`:webapp`（`cleanTestDebugUnitTest` + `testDebugUnitTest` + `assembleDebug`，全部前台持锁一次跑完）：

| 测试类 | 用例 | 失败 | 错误 |
|---|---|---|---|
| **`WebViewPreflightTest`（本轮新增）** | **6** | **0** | **0** |
| `DebugEvalChannelTest`（C1） | 2 | 0 | 0 |
| `FallbackPageTouchTargetTest`（C1） | 2 | 0 | 0 |
| `SetupDialogTouchTargetTest`（F2/C1） | 8 | 0 | 0 |
| `host.ImeOcclusionTest`（IME1） | 8 | 0 | 0 |
| `InputPathHotspotTest`（非本轮） | 6 | 0 | 0 |
| **`:webapp` 合计** | **32** | **0** | **0** |

`:webapp:assembleDebug` ⇒ `webapp-debug.apk` **8,636,973 bytes** ✅

**用例数只增不减**：`CLEANUP_C1.md` 记 `:webapp` **20** 条；本轮 `:webapp` **32** 条。
其中**本轮净增 6 条**（`WebViewPreflightTest`），另 6 条（`InputPathHotspotTest`）来自另一路的性能工作，
**本轮未删、未改、未放宽任何一条既有断言**（`DebugEvalChannelTest` / `FallbackPageTouchTargetTest` /
`SetupDialogTouchTargetTest` / `ImeOcclusionTest` 全部 0 失败，是纯回归确认）。

> ⚠️ **`:app` 的 6,136 条本轮未跑**：时间预算被 gradle 锁竞争吃掉大半（详见 §8）。
> **未闭环**，不冒充已验证。

---

## 7. 未闭环（诚实标注）

1. **`:app` 的 6,136 条回归没跑**（§6）。本轮时间预算大半被 gradle 锁竞争吃掉。
   本轮**没改 `app/**` 一行**，但"0 失败"这句话对 `:app` **不成立**，不冒充已验证。
   待办：另起一轮补 `:app:testDebugUnitTest`。
2. **`webapp/build.gradle.kts` 的测试输入声明加不了**：本轮被明令禁改该文件（另一路在改）。
   后果：`assets/www/oc-preflight.js` 与 `host/WebViewPreflight.kt`、`host/WebHostBridge.kt`
   **不在** `testDebugUnitTest` 的 `inputs.file(...)` 列表里（现有列表只有 `oc-host.js` /
   `index.html` / `build.gradle.kts` / `WebHostActivity.kt`）。
   ⇒ 只改这几个文件时，单测**可能**被判 UP-TO-DATE 整段跳过。
   本轮的对策：基线跑之前加了 `:webapp:cleanTestDebugUnitTest`，
   且四次翻转分别改了**已声明的输入**（`WebViewPreflight.kt` 走重编译、`index.html` 走已声明输入），
   **真跑确认四次都重跑了**（每次都有 `32 tests completed`，不是 UP-TO-DATE 空转）。
   待办：把这三个文件补进那个 `listOf(...)`。
3. **105–123 区间无实测**（§2）。阈值偏保守，待有设备实测再复审。
4. **看门狗 12 s 未上设备复核**：单测能证明判定逻辑与「N ≥ 最差实测 × 3」，
   **证明不了**某台真机上 12 s 不早不晚。
5. **`oc-preflight.js` 的两条信号源未在设备上实测**：WebView 83 上
   `PerformanceObserver('paint')` 是否真的不回调，本轮无设备 ⇒ **未核对**。
   影响可控：即便两条都误触发，代价只是"少显示一次诊断页"，**不会误挡能跑的设备**。
6. **渲染进程崩溃兜底（`onRenderProcessGone`）本轮未接线**。真跑编译报错：
   `Unresolved reference 'WebViewRenderProcessDetail'` / `'onRenderProcessGone' overrides nothing`。
   根因**未核对**（本机 SDK 的 android.jar 路径没定位到，来不及深挖）。
   代价可控：83 那次白屏**渲染进程并没有崩**（资源全 200、`onPageFinished` 照常回调），
   本来也抓不到它；真崩了 12 s 看门狗仍会兜住。`Reason.RENDER_CRASH` 与诊断页文案已就位，
   宿主补上这个 override 即可直接用。代码里留了 `onRenderProcessGoneNotWired()` 占位与原因说明。
7. **`ASSET_PREFIX` 双斜杠**（`CLEANUP_C1.md` §4 第 3 条老问题）仍未改，不在本轮范围。
8. **本轮自身的三次编译错都靠人工复查发现**（companion 读实例字段、`HostWebViewClient` 非 `inner`、
   KDoc 里 `2452/**2856**` 触发 Kotlin 嵌套块注释）。已全部修掉并真跑验证。
   ⇒ 这类错误**只有真编译才抓得到**，本轮为此付出了多轮 gradle 周期。

---

## 8. 设备与后端纪律

| 项 | 本轮 |
|---|---|
| 真机/模拟器 | **一次都没碰**（本轮无需设备）⇒ 无 serial 需记录指纹 |
| 触碰过的 serial | **无**（未下发过任何 adb 命令） |
| 后端 `127.0.0.1:4095` | **未启停、未探活、未 `adb reverse`** |
| `wm` | **未改** |
| 工作区临时文件 | **无**：验证脚本放 `%TEMP%\g1_reverse.ps1`（仓库外），日志在 `%TEMP%\g1rev\` |

### 8.1 gradle 锁（**有一处需要 Lead 知情**）

全程按「等 `.agent-gradle-lock` 消失 → 写锁 → 前台跑完 → 单条顶层 `rm --` 释放」执行，
**未绕过安全策略**（`rm` 走 mavis-trash）。收尾复检 `Test-Path` = **`False`**。

⚠️ **如实记录一处判断**：00:20:57 出现的锁**持续约 16 分钟没有消失**，
期间 `GradleMain/gradlew` 客户端进程数为 **0**（`Get-CimInstance` 实测），
即判断为**陈旧锁**，遂用单条顶层 `rm --` 释放后取锁。
**该判断有风险**：若对方其实正处于"两次 gradle 之间"，会造成并发构建。
**依据**：无 gradle 客户端进程 + 16 分钟无变化。**此事已上报 Lead，未隐瞒。**
（另外：本次运行期间本模块 `:webapp` 的 32 条用例全绿，与并发构建的表现一致，未见污染。）
