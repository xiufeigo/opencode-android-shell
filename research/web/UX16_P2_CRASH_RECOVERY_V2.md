# UX16-P2 批 #10 — 崩溃自愈改 WebView 重建式 + 诊断页字符集修复

- 日期：2026-10-07
- 仓库：`D:\XiaoZhuTouTou\opencode-android-shell`
- 前序：[UX16_P1_FINAL_GATE.md](UX16_P1_FINAL_GATE.md)（批 #9，真触发实证）
- 红线遵守：未改 `ui/`、未改 `app/**`、未改 `oc-host.js`、**未 commit**、未碰 `emulator-5554`

---

## 结论摘要

| 项 | 判定 |
|---|---|
| 崩溃自愈端到端（崩溃 → **恢复可用**） | ✅ **PASS**（CDP `Page.crash` 真崩复验，**1 次崩溃、0 次复发**、页面回 4173 可用） |
| 60s 节流 | ✅ PASS（连崩 2/3/4/5 全部落诊断页，无重建风暴） |
| 诊断页中文 | ✅ **PASS**（截图肉眼零 `?`） |
| 单元测试 | ✅ 242 全绿（239 + 新增 3） |
| 反向验证 | ✅ 3 处外科撤除分别真红，还原后全绿 |
| 产物 | ✅ 三份规范名已覆盖，ui=1613、oc-host.js md5 未变、凭据 0 命中 |

---

## 1. 改点 A：`WebHostActivity.kt` 崩溃自愈改**重建式**

### 1.1 抽出 `setupWebViewAndLoad()`（纯搬运）

把 `onCreate` 里的 WebView 初始化链**逐行原样**搬进新的 `private fun setupWebViewAndLoad()`：
`WebView(this)` → `setContentView` → `configureSettings` → `addJavascriptInterface(bridge, INJECT_NAME)`
→ `installFacade/installPerfProbe/installPreflightProbe` → `readWebViewVersion()`
→ `webViewClient` / `webChromeClient` / `setDownloadListener` → `viewTreeObserver` 首帧打点
→ `insets = InsetsHost(...)` → `launch(bootUrl())`。

`onCreate` 改为：先算 `pendingPageScrollY` 与 `restoredUrlOrNull`，再调 `setupWebViewAndLoad()`，
最后 `installBackHandler()` / `consumeNotificationIntent` / `consumeDebugEval`。
**初始化语义零改动**，只是换了个调用位置。

### 1.2 首崩分支：`reload()` → 销毁 + 重建

```kotlin
Log.i(TAG, "UX16 render crash; recreating WebView (window=${RENDER_CRASH_RELOAD_WINDOW_MS}ms)")
webView.removeJavascriptInterface(WebHostBridge.INJECT_NAME)
(webView.parent as? ViewGroup)?.removeView(webView)
webView.destroy()
setupWebViewAndLoad()
```

拆桥 → 出视图树 → 销毁的顺序与既有 `onDestroy`（`WebHostActivity.kt:1064-1068`）**完全一致**，不另造范式。

### 1.3 波及面（按「最小 diff」原则选型）

任务书给了两个方案，我选了**第三个**、也是波及面最小的一个，理由如下：

| 方案 | 波及面 | 结论 |
|---|---|---|
| A. `private lateinit var webView` 改 `private var webView: WebView? = null` | **波及全文**：每个 `webView.` 调用点都要加 `?.` 或 `!!`（`armWatchdog` / `showDiagnostic` / `onFirstContent` / `evaluateJs` / `onPageFinished` / `scheduleKeepScreenOnBackstopCheck` …）几十处，且每个 `?.` 都是在给"实例可能不存在"这个**并不存在**的状态开后门 | ❌ 否决 |
| B. 引入 `webViewSlot: ViewGroup` 容器，`removeAllViews()` + 新实例 add 回 | 需改 `setContentView(webView)` → `setContentView(slot)`，并调整 `InsetsHost`/`applyInsets` 等所有假定"contentView 就是 WebView"的代码 | ❌ 否决，改动面反而更大 |
| **C. 直接给 `lateinit var` 重新赋值** | **0 额外波及**：`lateinit` 只禁止"未初始化就读"，**不禁止重新赋值**。`onCreate` 本来就是 `webView = WebView(this)`，重建时同一行换成新实例即可 | ✅ **采用** |

⇒ **实际改动 = 1 处搬运 + 1 处分支替换，零新增字段、零新增空安全分支。**

### 1.4 重建路径上的既有状态（逐项确认）

| 状态 | 重建后是否一致 | 依据 |
|---|---|---|
| `bridge`（`WebHostBridge`） | ✅ **同一个实例** | 它是 Activity 级依赖，建在 `onCreate` 早段、**不在** `setupWebViewAndLoad()` 里 |
| 桥名 `WebHostBridge.INJECT_NAME` | ✅ 天然一致 | 新实例走同一段 `addJavascriptInterface(bridge, INJECT_NAME)` |
| 注入通道（`oc-host.js` + doc-start 种子） | ✅ 天然一致 | `installFacade/installPerfProbe/installPreflightProbe` 都在同一个 setup 方法里重跑一遍 |
| `RoundActivityTracker` | ✅ 保持 | `private val roundTracker` 是 Activity 级字段，**根本没搬进** setup 方法，不会被重建 |
| keepScreenOn flag | ✅ 不丢 | `applyKeepScreenOn` 记在 window 上，Activity 未重建 |
| `InsetsHost` | ✅ **必须重挂** | 它持 WebView 引用，已放进 `setupWebViewAndLoad()` 里重挂一个 |
| `installBackHandler()` | ✅ 只调一次 | 它注册的是 `onBackPressedDispatcher` 回调，注册两次会多挂一个；回调体读 `webView` **字段**，重建后自动指向新实例 |
| 60s 节流 | ✅ 保持 | 窗口内再崩仍走 `showDiagnostic(RENDER_CRASH)`（连重建都救不了的两连崩 = 真死，诊断页兜底正确） |

---

## 2. 改点 B：诊断页中文 `?` — **真因不是缺 charset**

### 2.1 证伪：`<meta charset>` 本来就在

任务书假设"HTML 里补 `<meta charset="utf-8">`"。实测**第 4 行本来就有**：

```html
    <meta charset="utf-8" />
```

所以"补 charset"这条修法**对缺陷完全无效**。必须继续往下挖。

### 2.2 真因：文件本身不是合法 UTF-8（GBK 往返产物）

字节级实测（`analyze.py`，状态机逐字节校验）：

```
bytes=17621   STRICT_UTF8=INVALID
Unable to translate bytes [E7][9A] at index 157
invalidSeq=156
```

- **156 处**三字节序列被塌成 `两字节 + 0x3F('?')`；
- 逐处实测：**每处的第 3 字节都是 `0x3F`**；
- 且每处**紧随其后还丢了 1 个字节**（分布：`/`36、`空格`19、`）`9、`,`9、`"`6 …，含 **CR/LF**）。

⇒ 这是一次 **GBK/CP936 往返**的典型破坏：`<3字节汉字><后随1字节>` → `<前2字节>` + `?`。
**丢失的换行会把两行并成一行**（`「」`。`+`<br />` 这类地方整段塌掉）。
git 溯源：该损坏由 HEAD 的 `05e48cd` 引入，**已提交**；其父提交 `cd088b3` 的同名 blob **字节级干净**。

### 2.3 修法：用干净的祖先 blob 做 oracle 精确还原

`HEAD~1`（`cd088b3`）blob 经 `git cat-file blob` 字节精确取出（`research/tmp/ux16p2/blob-cd088b3.html`，16000 字节，strict UTF-8 **VALID**）。
修复脚本 `research/tmp/ux16p2/repair2.py`：行级对齐后，在每个受损块内把每个损坏位做成通配 `(.{1,3}?)`，
**只在唯一匹配时才采信**（绝不静默接受猜测），命中即用干净原文还原。

```
stats: {'equal': 223, 'restored': 85, 'kept': 30, 'unresolved': 0}
WROTE index.html  bytes=17438  replacement_chars=0
STRICT_UTF8=VALID bytes=17438
```

- `restored=85` 行：受损行，从祖先还原；
- `kept=30` 行：HEAD 真实新增内容（`RENDER_CRASH` 诊断段等），**逐字保留未动**；
- `unresolved=0`：**没有任何一处靠猜**。

### 2.4 全局扫描其它自带 HTML 资产

```
index.html   bytes=17621   invalidSeq=156   ← 只有它坏
ui/index.html bytes=7250   invalidSeq=0     ← 干净，且本就声明了 charset
oc-host.js   bytes=74989   invalidSeq=0
```

⇒ 全仓只有 `assets/www/index.html` 一个资产受损；`ui/index.html` 的 charset 声明**原本就在**，无需补。
（`allBundledHtmlDeclareCharset` 仍覆盖两个文件，防的是**将来**新增资产漏声明。）

---

## 3. 绊线（`RenderProcessRecoveryTest`）

| # | 用例 | 状态 |
|---|---|---|
| 1 | `onRenderProcessGoneIsOverriddenAndConsumed` | 既有，保持 |
| 2 | `firstCrashRebuildsWithThrottle` | **改名并改写断言**（见下） |
| 3 | `crashLoopFallsBackToDiagnostic` | 既有，保持 |
| 4 | `defaultFinishIsPrevented` | 既有，保持 |
| 5 | `recoveryRecreatesWebViewInsteadOfReload` | 🆕 |
| 6 | `diagnosticPageDeclaresUtf8` | 🆕 |
| 7 | `allBundledHtmlDeclareCharset` | 🆕 |

### 3.1 ⚠️ 一处必须交代的指令冲突（已按"报告优先"处理）

任务书写「**既有 4 条保持**」，同时又要求新用例断言「**不得**再含首崩分支的 `reload()`」。
这两条**互斥**：既有第 2 条 `firstCrashReloadsWithThrottle` 的核心断言正是 `body.contains("reload()")`，
与新要求直接对撞，**不可能同时绿**。

我按"新要求是本轮修订的**产品契约**、旧断言是被它推翻的**旧契约**"处理：把第 2 条**改写**为
`firstCrashRebuildsWithThrottle`（钉 `destroy()` + `setupWebViewAndLoad()` + 节流窗口），并在用例里
用注释原样记录了它为什么必须变。若你要求保留旧名的旧断言，那等于要求自愈退回 `reload()`。

### 3.2 新用例 6 的加强（关键）

`diagnosticPageDeclaresUtf8` 不只断言 `charset` 声明，还用
`CodingErrorAction.REPORT` 断言**文件是严格合法 UTF-8**。
理由：按 §2.1，charset 声明**本来就有**，只断言声明的话这条绊线对真缺陷**完全瞎**。
（反向验证 (2) 即证明了这一点：删掉声明它会红，但那是"声明"这半句在起作用。）

### 3.3 反向验证（外科撤除 ⇒ 对应真红；原文抄录）

```
### (1) remove webView.destroy() from first-crash branch  gradleExit=1  failingTests=[firstCrashRebuildsWithThrottle, recoveryRecreatesWebViewInsteadOfReload]
    RED >> firstCrashRebuildsWithThrottle :: java.lang.AssertionError: 🔴 UX16-P2：崩溃恢复路径里必须 **destroy() 旧实例 + 重建**，而不是 reload —— 平台口径是渲染进程消失后该实例不应再被使用，且本镜像上 reload 后必然再崩。
    RED >> recoveryRecreatesWebViewInsteadOfReload :: java.lang.AssertionError: 🔴 UX16-P2：onRenderProcessGone 的首崩分支必须先 destroy() 旧 WebView 实例 —— 渲染进程已经没了，继续复用这个实例就是未定义行为。
### (2) remove <meta charset> from diagnostic page  gradleExit=1  failingTests=[diagnosticPageDeclaresUtf8, allBundledHtmlDeclareCharset]
    RED >> diagnosticPageDeclaresUtf8 :: java.lang.AssertionError: 🔴 UX16-P2：诊断页必须声明 <meta charset>（大小写不敏感）。
    RED >> allBundledHtmlDeclareCharset :: java.lang.AssertionError: 🔴 UX16-P2：自带 HTML 资产 src\main\assets\www\index.html 必须声明 <meta charset="utf-8">。
### (3) remove <meta charset> from assets/www/ui/index.html  gradleExit=1  failingTests=[allBundledHtmlDeclareCharset]
    RED >> allBundledHtmlDeclareCharset :: java.lang.AssertionError: 🔴 UX16-P2：自带 HTML 资产 src\main\assets\www\ui\index.html 必须声明 <meta charset="utf-8">。
### (4) all restored - expect ALL GREEN  gradleExit=0  failingTests=[]
```

- (1) 只红重建相关两条 ⇒ 定位精确；
- (2) 红字符集两条（诊断页本身也是"自带 HTML 资产"，被 ③ 同一条绊线顺带覆盖，符合预期）；
- (3) **只红** `allBundledHtmlDeclareCharset` ⇒ 隔离干净；
- (4) 还原后**全绿**，`git status` 确认三处改动仍在工作区。

> 过程中的一次真实翻车并已修正：首版反向脚本用 `git checkout --` 还原，**把 WebHostActivity.kt 的改动和
> index.html 的修复一起冲掉了**（回到 HEAD = 旧 `reload()` 契约 + 损坏文件）。已改为 `Copy-Item` 从
> `research/tmp/ux16p2/backup/` 还原，并重跑全绿。

---

## 4. 测试 + 构建

锁协议：`JAVA_HOME=C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot`（Microsoft JDK 21）、
内联 `ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk`、长编译 `run_in_background` 脱离 + 轮询。

```
:webapp:testDebugUnitTest      BUILD SUCCESSFUL
  tests=242 failures=0 errors=0 skipped=0      (30 个 xml；239 + 3)
:webapp:assembleDebug          BUILD SUCCESSFUL
:webapp:assembleRelease        BUILD SUCCESSFUL
:webapp:assembleReleaseR8      BUILD SUCCESSFUL  (93 actionable tasks, 45s)
```

`RenderProcessRecoveryTest` 7 条全绿：

```
crashLoopFallsBackToDiagnostic  diagnosticPageDeclaresUtf8  allBundledHtmlDeclareCharset
firstCrashRebuildsWithThrottle  onRenderProcessGoneIsOverriddenAndConsumed
recoveryRecreatesWebViewInsteadOfReload  defaultFinishIsPrevented
```

---

## 5. 产物（`research/deliver/`，规范名三份）

| 文件 | 大小 | MD5 |
|---|---|---|
| `opencode-shell-web.apk` | 16,830,822 | `609D0F37902DD057C9EEC7E094F51762` |
| `opencode-shell-web-release-nor8.apk` | 14,313,026 | `B18EBCA3107F088A6A87271852C52199` |
| `opencode-shell-web-release-r8.apk` | 12,032,548 | `E17C5C025C7A361F15E52DB060BF0CCF` |

包内自查：

```
ui entries            = 1613   (expected 1613)
assets/www/*.html     = assets/www/index.html, assets/www/ui/index.html
oc-host.js in APK md5 = 659BED67B949F1A4466AD3EF3D2D1E06  (expected 659BED67…)  ✅ 与批 #9 一致，本轮未改
apk assets/www/index.html bytes=17438 utf8=VALID-UTF8 charsetDeclared=True
credential scan: 三份 APK 均 credentialHits=0
```

> debug APK 大小 16,830,822 与批 #9 完全相同 —— 字节数没变而 MD5 变了，是因为本轮只改了一个
> HTML 资产与 Kotlin 源码，体积变化互相抵消/对齐，不是误改的信号（MD5 变化本身即证明内容确实变了）。

---

## 6. CDP 真崩复验（自建 `emulator-6790`）

| 项 | 值 |
|---|---|
| 设备 | `emulator-6790`（AVD `Medium_Phone_API_37.0`, API 37, 1080×2400）— **我起我关** |
| 安装 | `install -r`（**未 `pm clear`**，reverse 4095 安装前后均在：`host-15 tcp:4095 tcp:4095`） |
| 组件 | `com.xiufeigo.opencodeshell.web.debug/com.xiufeigo.opencodeshell.web.WebHostActivity` |
| 后端 | `127.0.0.1:4095` **pid 21136，全程未启停** |
| 触发 | CDP `Page.crash`（production 镜像无 root，`kill -9` 渲染进程会被拒，见批 #9） |

### 6.1 崩溃前基线

`url=http://127.0.0.1:4173/` `title=OpenCode`，截图 `01-healthy.png`（官方 Home 正常渲染）。

### 6.2 第一次崩溃 ⇒ **重建 ⇒ 恢复可用** ✅ 端到端 PASS

```
crash issued @ 08:46:58.551
recv={"method":"Inspector.detached","params":{"reason":"Render process gone."}}
ws disposed @ 08:47:05.148          ← 立刻断开，排除"CDP 挂着才拖死渲染进程"这个替代解释

10-07 00:47:04.402  3733  3733 W OcWebHost: UX14 render process gone didCrash=true priorityAtExit=2
10-07 00:47:04.403  3733  3733 I OcWebHost: UX16 render crash; recreating WebView (window=60000ms)
10-07 00:47:04.427  3733  3733 I OcWebHost: home=http://127.0.0.1:4173/ backend=http://127.0.0.1:4173
10-07 00:47:04.657  3733  3733 I OcWebHost: web[LOG] OcPerfJS doc-start href=http://127.0.0.1:4173/ navStart=229 @19
```

静置 20s 后实测：

```
=== count of 'render process gone' in 20s window ===   1     ← 只有崩溃那一次
=== any diagnostic page? ===                          0     ← 没有兜底页
devtools 复查:  url=http://127.0.0.1:4173/ title=OpenCode
```

截图 `02-after-crash1.png` 与崩溃前基线**逐像素一致**（官方 Home 正常态）。

> **这是与批 #9 的决定性差异**：旧 `reload()` 实现在 6.55s / 6.56s 两次复现中**必然二次崩溃**；
> 本轮重建后 20s 内**零复发**，页面真的回到了可用态。
> 批 #9 判词"**节流的成功掩盖了自愈的失败**"至此闭环：**自愈这次是真的成功了**。

### 6.3 节流 ✅ + 诊断页中文 ✅

连发 2 次 `Page.crash`（同 60s 窗口）：

```
00:47:52.206 W UX14 render process gone didCrash=true priorityAtExit=2
00:47:52.206 W UX14 render crash loop count=2 within=60000ms
00:47:52.215 W G1 showing diagnostic page reason=rendercrash detail=didCrash=true crashes=2 windowMs=60000
00:47:58.789 W UX14 render crash loop count=3 ... crashes=3
00:48:12.355 W UX14 render crash loop count=4 ... crashes=4
00:48:18.904 W UX14 render crash loop count=5 ... crashes=5
00:48:19.050 I web[LOG] OcPerfJS doc-start href=https://appassets.androidplatform.net/assets//www/index.html?r=rendercrash&m=145&v=145.0.7632.218&min=105&t=12000&d=didCrash%3Dtrue%20crashes%3D5%20windowMs%3D60000
```

- ✅ **节流 PASS**：崩溃 2/3/4/5 全部落 `showDiagnostic(RENDER_CRASH)`，**无重建风暴、无无限重启**；
- ✅ **诊断页确实加载**且带对参数。

截图 `03-diagnostic-page.png`（判据：**中文零 `?`**，肉眼可验）：

| 行 | 渲染结果 |
|---|---|
| 标题 | `页面渲染进程崩溃了` |
| 发生了什么 | `WebView 的渲染进程在加载过程中崩溃或被系统杀掉，屏幕因此变空，且不会再有任何页面回调。` |
| 当前 WEBVIEW 版本号 | `145.0.7632.218（主版本 145）` |
| 本壳要求 | `主版本 ≥ 105；首屏看门狗 12000 ms` |
| 怎么办 | `① 先点「重新加载」；② 若反复崩溃，多半是内存不足…③ 仍然崩溃说明这台设备的 WebView 有问题，建议换设备。` |
| 技术细节 | `didCrash=true crashes=5 windowMs=60000` |
| 重试 | `会重新检查 WebView 版本并重连。` + 按钮 `重新加载` |
| ORIGIN / ASSETLOADER / FETCH | `https://appassets.androidplatform.net` / `NO — protocol=https…` / `YES — 同源 fetch ./oc-host.js = 59854 bytes` |

**与批 #9 截图 `08-crash1.png` 对照：当时整页全是 `?`（含 `?b>`、`?/DIV>` 残片），现在全部正常。**

### 6.4 诊断页「重试」

按要求**只记录、不强求**：按钮已渲染可见（`重新加载`），本轮未点击验证回跳。

### 6.5 收尾 ✅

```
am force-stop com.xiufeigo.opencodeshell.web.debug
forward --remove-all   → forward: (空)
reverse --remove-all   → reverse: (空)
adb -s emulator-6790 emu kill → OK: killing emulator, bye bye

$ adb devices -l
emulator-5554   device product:sdk_gtablet_x86_64 model=Pixel_Tablet transport_id:17   ← 只剩他人设备

$ Get-Process -Id 21136
ALIVE name=opencode start=10/07/2026 08:16:25      ← 启动时刻与开轮前完全一致
backend /    = 200
backend /app = 200
```

- ✅ 自建 `emulator-6790` 已关（谁起谁关）；✅ 未 kill adb server；
- ✅ 后端 **pid 21136 未受影响**；✅ `emulator-5554`（他人设备）**全程零写操作**。

---

## 7. 纪律自查

| 项 | 状态 |
|---|---|
| 未改 `ui/` / `app/**` / `oc-host.js` | ✅ `git status` 仅 3 个文件：`WebHostActivity.kt`、`index.html`、`RenderProcessRecoveryTest.kt`；oc-host.js md5 与批 #9 一致 |
| 未 commit | ✅ 仅工作区改动 |
| 后端不许启停 | ✅ pid 21136 未动，`/` `/app` 均 200 |
| 不碰他人设备 | ✅ `emulator-5554` 零写操作 |
| 不编造 | ✅ §2.1 明确证伪了任务书预设的修法；§3.1 主动交代指令冲突；本轮 3 处翻车（脚本回滚 bug）已如实记录 |
| 注入标注 | ✅ 本轮 CDP 只发 `Page.crash`，**未注入任何 JS、未点击任何按钮** |

---

## 8. 遗留与建议

1. **`ui/` 目录存在同类编码风险**（本轮只修了 `assets/www/index.html`）。
   `ui/index.html` 当前合法，但整个 `www/ui/**` 共 1613 个文件，本轮**只抽查了 `.html`**，
   未对全部 JS 做 UTF-8 合法性扫描。若历史上有过 PowerShell 管道写文件，值得单独排一次。
2. **诊断页文案与新契约已不同步**：`RENDER_CRASH` 的 `fix` 文案仍写「先点『重新加载』」，
   而宿主现在做的是**自动重建**。文案可考虑改成"已自动重建，若仍崩溃请…"。
3. **`RENDER_CRASH_RELOAD_WINDOW_MS` 名字已过时**：它现在管的是"重建窗口"而非"reload 窗口"，
   改名会牵动既有绊线的正则，暂留并在此记录。
4. **批 #9 的两条未闭环项本轮未动**（流式帧时取不到数、Add-server UI 不可达），根因与本轮无关。
