# 轮次：UX16-P2 —— 崩溃自愈改 WebView 重建式 + 诊断页字符集修复（批 #10）

你是本仓库本轮的执行 agent。仓库根：`D:\XiaoZhuTouTou\opencode-android-shell`。
**每步一条命令 + 立即读输出；结论当场写进 `research/web/UX16_P2_CRASH_RECOVERY_V2.md`。**

## 根因（UX16-P1 真触发实证，直接采信）

CDP `Page.crash` 触发 `onRenderProcessGone`：崩溃被消费 ✅、60s 节流落诊断页 ✅——**但 `reload()` 后渲染进程 ~6.5s 再崩**（两次复现一致），端到端"恢复可用"不成立。平台口径（`WebHostActivity.kt:1141-1145` 自陈）：渲染进程消失后**该 WebView 实例不应再被使用** ⇒ 必须**销毁旧实例、新建 WebView 并走完整初始化链**。
附带缺陷：诊断页中文全渲染成 `?`（字符集问题，截图证据在 UX16_P1 报告）。

## 修法（宿主侧，两处 + 绊线 + 重建 + CDP 复验）

### 1. `WebHostActivity.kt`：WebView 重建式自愈
- 把 onCreate 里的 WebView 初始化链（bridge 注入 / addDocumentStartJavaScript(oc-host.js+种子) / WebChromeClient / WebViewClient / insets / settings 配置 / loadUrl(bootUrl) 等）**原样抽成私有方法 `setupWebViewAndLoad()`**（不改任何初始化语义，纯搬运+注释）；onCreate 改调它。
- `onRenderProcessGone` 的"窗口内首崩"分支：不再 `reload()`，改为：
  1. `webView.removeJavascriptInterface(...)`、`(webView.parent as? ViewGroup)?.removeView(webView)`、`webView.destroy()`；
  2. `::webView` 不可重赋（lateinit）⇒ 改用可空持有者或直接重新赋值方案（读现有代码选最稳的，如把 `private lateinit var webView` 改 `private var webView: WebView? = null` 会波及全文——**替代**：用一个 `webViewSlot: ViewGroup`（启动时把 webView add 进去），重建时 slot.removeAllViews() + 新实例 add 回——**选哪个以"最小 diff"为准**，把波及面写进报告；
  3. 调 `setupWebViewAndLoad()` 重建并重新加载 `bootUrl()`；
  4. 保持 60s 节流：窗口内再崩仍落 `showDiagnostic(RENDER_CRASH)`（连重建都救不了的两连崩=真死，诊断页兜底正确）。
- ⚠️ 重建路径上的既有状态：keepScreenOn flag / RoundActivityTracker / 注入通道——确认重建后 bridge 名与注入与新实例一致（本来就走同一 setup 方法 ⇒ 天然一致，报告里点明）。

### 2. 诊断页中文 `?` 修复
找到诊断页资产/生成处（`showDiagnostic` 读取的 HTML；grep `WebViewPreflight` 与诊断页相关源），修复字符集：HTML 里补 `<meta charset="utf-8">`（或响应/加载侧指定 UTF-8）。**同时全局 grep 其它自带 HTML 资产**（`assets/www/*.html`）确认都有 charset 声明，缺的都补上。

### 3. 绊线
更新 `RenderProcessRecoveryTest`（既有 4 条保持）+ 新增：
1. `recoveryRecreatesWebViewInsteadOfReload`：onRenderProcessGone 分支含 `destroy()`+setup 方法调用，**不得**再含首崩分支的 `reload()`；
2. `diagnosticPageDeclaresUtf8`：诊断页 HTML 含 `charset`（utf-8 不区分大小写）；
3. `allBundledHtmlDeclareCharset`：`assets/www/*.html` 均含 charset。
反向验证：外科撤除①②③分别 ⇒ 对应真红 ⇒ 恢复全绿。抄原文。

### 4. 测试 + 重建 + 落盘
锁协议（JAVA_HOME=Microsoft JDK21；内联 ANDROID_HOME；长编译 Start-Process 脱离+轮询 test-results）。`:webapp:testDebugUnitTest` 全绿（预期 239+N）；assemble 三件套；覆盖 `research/deliver/`（**规范名三份**）。包内自查：ui=1613、新 oc-host.js md5 应与批 #9 一致 `659BED67B949F1A4466AD3EF3D2D1E06`（本轮不改 oc-host.js；变了=误改，停下报告）、凭据扫描 0 命中。

### 5. CDP 真崩复验（emulator-6790 你自建；后端 4095 夹具 pid 21136 在跑**不许起停**；reverse 4095）
1. install -r → **不 pm clear**（保持已连 4095 状态）→ am start → 确认页面可用；
2. CDP `Page.crash` → 观察：logcat 走**重建**分支；新 WebView 加载 4173 → **恢复可用**（官方 Home 渲染、若上下文/会话仍在则更好）⇒ 自愈端到端 PASS 判据；
3. 60s 内第二次 `Page.crash` ⇒ 落诊断页（节流 PASS）+ **诊断页中文正常显示**（charset 修复判据，截图）；
4. 从诊断页点重试能回到正常页（记录，不强求）。
5. 收尾：emu kill、reverse/forward remove、后端 pid 21136 未变+200、列 serial（只能 emulator-6790）。

## 🔴 红线
不改 `ui/`、不改 `app/**`、不改 oc-host.js；不 commit；不碰他人设备；失败停下写报告。

## 汇报（最后一行）
重建式改点+波及面 · 字符修复点 · 用例数/反向验证红条 · 三份产物 体积/md5 + oc-host.js md5 是否保持 659BED67… · CDP 复验（自愈端到端 PASS/FAIL / 节流 / 中文正常）· 锁已释放 · 报告路径。
