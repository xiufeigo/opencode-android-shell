package com.xiufeigo.opencodeshell.web

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.xiufeigo.opencodeshell.web.host.FileChooserHost
import com.xiufeigo.opencodeshell.web.host.ImeMode
import com.xiufeigo.opencodeshell.web.host.InsetsHost
import com.xiufeigo.opencodeshell.web.host.NotifierHost
import com.xiufeigo.opencodeshell.web.host.PerfMarks
import com.xiufeigo.opencodeshell.web.host.PowerPolicy
import com.xiufeigo.opencodeshell.web.host.RoundActivityTracker
import com.xiufeigo.opencodeshell.web.host.SecretStore
import com.xiufeigo.opencodeshell.web.host.WebHostBridge
import com.xiufeigo.opencodeshell.web.host.WebViewPreflight
import org.json.JSONObject
import java.util.Base64

/**
 * W1：WebView 宿主 Activity。
 *
 * - 静态资源用 [WebViewAssetLoader] 托管在 `https://appassets.androidplatform.net/assets/www/`
 *   —— **不用 `file://`**。`file://` 的 origin 是 `null`（opaque），fetch 带不上
 *   同源凭据、`localStorage` 在部分版本直接抛 SecurityError、SSE/EventSource 也废；
 *   而 asset origin 是真实 https origin，页面能正常 fetch 自己的资源、读同源存储。
 * - 边到边 + insets 交给 [InsetsHost] → CSS 变量 / JS 事件。
 * - 返回：`canGoBack()` 优先，否则问页面 `window.__ocAndroid.onBack()`，
 *   **两条都不成立时什么都不做**（不 finish）。
 *
 * UX6（当前架构）：**直连真实服务器，无回环代理**。
 * 启动链 = `bootUrl()`（SecretStore 里的后端地址，默认 `http://127.0.0.1:4095`，
 * 靠 `adb reverse tcp:4095 tcp:4095` 打通）→ G1 前置检查 → `loadUrl(后端地址)`。
 * 官方前端由后端自托管（`packages/cli/src/services/web-ui.ts` 的 SPA 回落），
 * 页面 origin == API origin，CORS 不参与；**未配置服务器时官方 UI 自己呈现
 * ConnectServerScreen**（`packages/app/src/shell/routes/routes.tsx:79`），
 * 服务器/凭据配置全部在官方「设置 → 服务器」里完成（`servers/connect/dialog.tsx`
 * 支持密码字段，Basic `opencode:<password>`，见 `runtime/server/api.ts:5-7`）。
 * 宿主不再持有、注入、转发任何口令 —— F21 在新形态下由「宿主侧无凭据」这一
 * 结构事实保障（见 `research/web/UX6_OFFICIAL_ENTRY.md` §1.2）。
 */
class WebHostActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "OcWebHost"
        const val ASSET_DOMAIN = "appassets.androidplatform.net"
        const val ASSET_PREFIX = "/assets/"

        /**
         * 诊断页 URL 基座（资产托管）。R1 起**不再**用它当首页，
         * 只在 G1 前置检查失败/主文档加载失败时呈现，用来区分
         * "页面全白"与"WebView 过旧"两类故障。
         */
        const val LEGACY_PLACEHOLDER_URL = "https://$ASSET_DOMAIN$ASSET_PREFIX/www/index.html"
        private const val STATE_URL = "oc_web_url"
        private const val STATE_SCROLL_Y = "oc_web_scroll_y"

        /**
         * BR1 验收用的**调试**注入通道：`--es oc_debug_eval "<js>"` 让宿主把一段 JS
         * 丢进当前页面（`evaluateJavascript`），结果打进 logcat 的 `OcWebHost/Eval`。
         * 官方 UI 自己不会主动调 `postMessage`/`pickFile`/`share` 这些桥，没有这个
         * 通道就只能从 UI 点，测不到它们。
         *
         * 只在 `BuildConfig.DEBUG` 生效（release 构建直接忽略），且不写文件、不回显口令。
         */
        const val EXTRA_DEBUG_EVAL = "oc_debug_eval"
        private const val EVAL_TAG = "OcWebHost/Eval"
        /** 页面还没加载完时注入要等一下，否则 evaluateJavascript 落在 about:blank 上。 */
        private const val EVAL_SETTLE_MS = 1200L

        /**
         * G1：诊断页 URL（就是 C1 留下的那套兜底页资产，只是多带 query 参数换文案）。
         * 阈值与超时的依据全在 [WebViewPreflight] 里。
         *
         * ⚠️ 这里**不能**读实例字段（`webViewMajor`/`webViewVersionName`）—— companion object
         * 没有实例上下文。真实读数由实例侧的 [showDiagnostic] 传进来。
         */
        fun diagnosticUrl(
            reason: WebViewPreflight.Reason,
            major: Int?,
            versionName: String?,
            detail: String? = null,
        ): String = WebViewPreflight.diagnosticUrl(
            baseUrl = LEGACY_PLACEHOLDER_URL,
            reason = reason,
            major = major,
            versionName = versionName,
            detail = detail,
        )
    }

    private lateinit var webView: WebView
    private lateinit var bridge: WebHostBridge
    private lateinit var fileChooser: FileChooserHost
    private lateinit var insets: InsetsHost

    private var pickedUri: Uri? = null
    private var pendingPageScrollY: Int = 0
    private var pageReportedDark: Boolean? = null

    /**
     * D1：回合活跃跟踪器。UX6 起**唯一的驱动方是页面桥**（`setKeepScreenOn`）——
     * 回环代理已删，宿主看不见流量，无法再按真实回合流驱动（这是本轮如实记录的
     * 能力回退，见报告 §1.2）。
     */
    private val roundTracker = RoundActivityTracker()

    /**
     * P6：宿主是否在前台。
     *
     * ⚠️ 为什么不用 `lifecycle.currentState.isAtLeast(STARTED)`：[WebHostBridge] 的
     * `@JavascriptInterface` 方法跑在** WebView 的 JS 桥线程**上，不是主线程。
     * 读 `lifecycleScope`/lifecycle registry 在跨线程读时是可见的，但 Activity
     * 已 `destroy` 时访问会抛；这里用一个 `@Volatile` 布尔，语义更窄也更安全。
     */
    @Volatile
    private var foreground: Boolean = false

    /** 供 [WebHostBridge.setKeepScreenOn] 在 JS 桥线程上访问的跟踪器。 */
    fun roundActivityTracker(): RoundActivityTracker = roundTracker

    /** 供 [WebHostBridge.setKeepScreenOn] 在 JS 桥线程上问"现在在前台吗"。 */
    fun isForeground(): Boolean = foreground

    /**
     * D1：按当前"回合是否在进行"重新结算一次常亮 flag。
     * 桥线程与主线程都会调它，自身保证切到主线程。
     */
    fun applyKeepScreenOnState() {
        val active = roundTracker.isRoundActive(System.currentTimeMillis())
        runOnUiThread { applyKeepScreenOn(active) }
    }

    // ---- G1：WebView 版本前置检查 + 首屏看门狗 -----------------------------
    /** 真实读数（不写死）：`WebView.getCurrentWebViewPackage()?.versionName`，读不到就是 `null`。 */
    private var webViewVersionName: String? = null
    private var webViewMajor: Int? = null

    /** 已加载官方 UI ⇒ 看门狗才有意义（诊断页自己不需要被诊断）。 */
    private var watchdogArmed = false
    private var firstContentSeen = false
    private var loadIssuedAtMs = 0L
    private val watchdog = Runnable {
        val reason = WebViewPreflight.stallVerdict(
            elapsedMs = SystemClock.elapsedRealtime() - loadIssuedAtMs,
            contentSeen = firstContentSeen,
        )
        if (reason != null) {
            // 到点还没内容 ⇒ 白屏/卡死。实测 WebView 83 就是这种：资源全 200、页面全白。
            Log.w(TAG, "G1 watchdog fired: no first content in ${WebViewPreflight.LOAD_STALL_TIMEOUT_MS}ms")
            showDiagnostic(reason, "首屏 ${WebViewPreflight.LOAD_STALL_TIMEOUT_MS}ms 内没有任何内容")
        }
    }

    /**
     * 诊断页「重试」按钮的落点：重走一遍启动链。
     *
     * 关键是**重新读一次 WebView 版本**再决定 —— 用户可能刚更新了
     * "Android System WebView"，这时重试就该真的进官方 UI 了。
     */
    fun retryFromDiagnosticPage() {
        Log.i(TAG, "G1 retry: re-running preflight + launch")
        readWebViewVersion()
        restoredUrlOrNull = null
        launch(bootUrl())
    }

    /** 读一次 WebView 版本。取不到就留 `null` —— **不得**因此判过旧（见 [WebViewPreflight]）。 */
    private fun readWebViewVersion() {
        val pkg = runCatching { WebViewCompat.getCurrentWebViewPackage(this) }.getOrNull()
        val name = runCatching { pkg?.versionName }.getOrNull()
        webViewVersionName = name
        webViewMajor = WebViewPreflight.parseMajor(name)
        Log.i(
            TAG,
            "G1 webview version=$name major=${webViewMajor} " +
                "min=${WebViewPreflight.MIN_MAJOR} tooOld=${WebViewPreflight.isTooOld(webViewMajor)}",
        )
    }

    /** 切到诊断页：先关掉看门狗（诊断页不需要被诊断），再加载带参数的自建页面。 */
    private fun showDiagnostic(reason: WebViewPreflight.Reason, detail: String?) {
        watchdogArmed = false
        firstContentSeen = true
        val url = diagnosticUrl(reason, webViewMajor, webViewVersionName, detail)
        Log.w(TAG, "G1 showing diagnostic page reason=${reason.wire} detail=$detail")
        PerfMarks.mark("M.g1.diagnostic", "reason=${reason.wire}")
        webView.loadUrl(url)
    }

    private fun armWatchdog() {
        watchdogArmed = true
        firstContentSeen = false
        loadIssuedAtMs = SystemClock.elapsedRealtime()
        webView.removeCallbacks(watchdog)
        webView.postDelayed(watchdog, WebViewPreflight.LOAD_STALL_TIMEOUT_MS)
    }

    /** 桥从 `oc-preflight.js` 收到首屏内容信号 ⇒ 解除看门狗。 */
    fun onFirstContent(kind: String?, atMs: Long?) {
        if (firstContentSeen) return
        firstContentSeen = true
        PerfMarks.mark("M.g1.firstContent", "kind=$kind at=$atMs")
        webView.post { webView.removeCallbacks(watchdog) }
    }

    val imeModeName: String
        get() = if (::insets.isInitialized) insets.imeMode.name.lowercase() else "vars"

    // ---- 通知权限（必须在 Activity STARTED 之前注册 launcher） --------------
    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        Log.i(TAG, "POST_NOTIFICATIONS granted=$granted")
        // 结果回给页面：onRequestResult 约定 {"notificationPermission":true|false}
        evaluateJs(
            "window.__ocAndroid && window.__ocAndroid.hostInfo &&" +
                "window.dispatchEvent(new CustomEvent('oc:permission'," +
                "{detail:{permission:'notifications',granted:$granted}}));"
        )
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        PerfMarks.mark("A.onCreate.enter")
        super.onCreate(savedInstanceState)

        PerfMarks.mark("A.onCreate.superDone")

        bridge = WebHostBridge(this)
        fileChooser = FileChooserHost(this)
        NotifierHost.ensureChannels(this)
        PerfMarks.mark("B.helpersReady")

        webView = WebView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setBackgroundColor(0xFF080808.toInt())
            isVerticalScrollBarEnabled = false
            overScrollMode = WebView.OVER_SCROLL_NEVER
        }
        setContentView(webView)
        PerfMarks.mark("C.webviewCreated+setContentView")

        configureSettings(webView.settings)
        webView.addJavascriptInterface(bridge, WebHostBridge.INJECT_NAME)
        PerfMarks.mark("D.settingsConfigured")
        installFacade(webView)
        installPerfProbe(webView)
        installPreflightProbe(webView)
        PerfMarks.mark("E.facadeInstalled")
        // G1：版本读数必须在 [launch] 之前拿到 —— 前置检查要靠它决定加载官方 UI 还是诊断页。
        readWebViewVersion()
        webView.webViewClient = HostWebViewClient()
        webView.webChromeClient = HostWebChromeClient()
        webView.setDownloadListener { url, ua, _, _, _ ->
            // 本轮不下载文件：外链交给系统，asset 域名直接忽略。
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        }

        // WebView 第一次真正把内容画进窗口的时刻（"首帧白屏"的终点）。
        webView.viewTreeObserver.addOnDrawListener(
            object : android.view.ViewTreeObserver.OnDrawListener {
                private var first = true
                override fun onDraw() {
                    if (first) {
                        first = false
                        PerfMarks.mark("Z.firstDraw")
                    }
                }
            },
        )

        insets = InsetsHost(window, webView) { payload -> pushInsets(payload) }
        installBackHandler()
        PerfMarks.mark("F.insetsReady")

        pendingPageScrollY = savedInstanceState?.getInt(STATE_SCROLL_Y, 0) ?: 0
        // UX6：冷启动 = 直接加载真实服务器地址（官方前端由后端自托管）。
        // 恢复态的旧 URL 直接沿用（origin 现在是稳定的后端地址，不像代理端口每次必变）。
        val restored = savedInstanceState?.getString(STATE_URL)
        restoredUrlOrNull = restored
        launch(bootUrl())
        consumeNotificationIntent(intent)
        consumeDebugEval(intent)
        PerfMarks.mark("G.onCreate.exit")
    }

    /**
     * UX6 · 本次启动要加载的后端地址。
     *
     * 唯一来源 = [SecretStore.KEY_BACKEND_URL]（**不是秘密**，只是"应用壳从哪个
     * 后端取官方 UI"的入口；默认 `http://127.0.0.1:4095`，靠 adb reverse 打通）。
     * 服务器与口令的**配置**不在宿主做 —— 那是官方 UI 「设置 → 服务器」的职责。
     */
    private fun bootUrl(): String =
        SecretStore.getSecret(this, SecretStore.KEY_BACKEND_URL) ?: SecretStore.DEFAULT_BACKEND_URL

    /**
     * UX6 启动链（代理已删，只剩一步直达）：
     *
     * ```
     *   bootUrl() ──▶ G1 前置检查 ──▶ armWatchdog ──▶ loadUrl(后端地址)
     * ```
     *
     * 没有任何凭据检查：宿主不持有口令，"空口令 ⇒ 不许用"这条旧规则（F21）在新
     * 形态下由**结构**保障 —— 宿主侧根本不存在"以无认证方式使用后端"的代码路径；
     * 认证协商完全发生在官方 UI 与后端之间（官方连接页会自己提示口令错误）。
     */
    private fun launch(backend: String) {
        PerfMarks.mark("H.launch.enter", "backend=$backend")
        val restored = restoredUrlOrNull
        val url = if (restored != null && restored.startsWith(backend)) restored else "$backend/"

        // ---- G1 前置能力检查 ------------------------------------------------
        // 过旧就**不加载**官方 UI：实测 WebView 83 加载它只会得到一片纯白
        // （资源全 200、ESM 解析失败、`onPageFinished` 照常回调）。
        // 加载它再报错，等于让用户在白屏前多等一轮无意义的网络与解析。
        when (val verdict = WebViewPreflight.preflight(webViewVersionName)) {
            is WebViewPreflight.Verdict.ShowDiagnostic -> {
                showDiagnostic(verdict.reason, "WebView ${verdict.versionName} < ${WebViewPreflight.MIN_MAJOR}")
                PerfMarks.mark("J.g1.blockedOldWebview", "major=${verdict.major}")
                return
            }
            is WebViewPreflight.Verdict.LoadOfficialUi -> {
                // 读不到版本（unknownVersion=true）**照样放行**，只多挂一层运行期看门狗。
                PerfMarks.mark("J.g1.preflightOk", "major=${verdict.major} unknown=${verdict.unknownVersion}")
            }
        }

        Log.i(TAG, "home=$url backend=$backend")
        armWatchdog()
        webView.loadUrl(url)
        PerfMarks.mark("J.loadUrlIssued", "url=$url")
    }

    private var restoredUrlOrNull: String? = null

    /**
     * 首次启动的配置对话框、接入探测、错误页、未配置灰态 —— **全部删除**（UX6）。
     *
     * 理由（产品决策，已确认）：启动直接进官方 UI；服务器/口令只在官方
     * 「设置 → 服务器」里配置；官方自己未配置时呈现 ConnectServerScreen，
     * 官方自己探测健康、自己报告口令错误。宿主再造一套 = 双份真相。
     */

    // ---- WebView 设置 ------------------------------------------------------
    private fun configureSettings(s: WebSettings) = with(s) {
        javaScriptEnabled = true
        domStorageEnabled = true
        databaseEnabled = true
        // 1 CSS px == 1 dp 的前提之一。
        useWideViewPort = false
        loadWithOverviewMode = false
        setSupportZoom(false)
        builtInZoomControls = false
        displayZoomControls = false
        mediaPlaybackRequiresUserGesture = false
        // 混合内容：仅兼容模式（页面与 API 现在同源于后端 http 地址，正常用不到；
        // 保留是为了 https 后端 + http 子资源的边界场景仍尽量可用）。
        mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        // 不给 file:// / content:// 通用读权限：资产只走 assetLoader。
        allowFileAccess = false
        allowContentAccess = false
        cacheMode = WebSettings.LOAD_DEFAULT
        userAgentString = "$userAgentString OpenCodeWebHost/0.1 (W1-skeleton)"
    }

    /**
     * 把 facade（`assets/www/oc-host.js`）注册成**文档开始**脚本。
     *
     * 用 `WebViewCompat.addDocumentStartJavaScript` 而不是 `onPageFinished` 里
     * `evaluateJavascript`：后者太晚，页面首屏脚本已经跑完、没拿到
     * `window.__ocAndroid`。老 WebView 不支持该特性时退回 `onPageStarted` 注入
     * （对真正的上游 SPA 可能错过首屏，但 G1 前置检查已把过旧的 WebView 拦住）。
     */
    private fun installFacade(wv: WebView) {
        val script = runCatching { assets.open("www/oc-host.js").bufferedReader().use { it.readText() } }
            .getOrElse {
                Log.e(TAG, "cannot read www/oc-host.js", it)
                return
            }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(wv, script, setOf("*"))
            Log.i(TAG, "facade registered as document-start script")
        } else {
            wv.webViewClient = object : HostWebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    view?.evaluateJavascript(script, null)
                }
            }
            Log.w(TAG, "DOCUMENT_START_SCRIPT unsupported -> falling back to onPageStarted")
        }
    }

    /**
     * P2 性能归因：注册 `assets/www/oc-perf.js` 为**文档开始**脚本，
     * 拿浏览器侧的 first-paint / DCL / 逐资源 waterfall。
     *
     * 读不到就跳过 —— 探针失败绝不能影响壳本身。
     */
    private fun installPerfProbe(wv: WebView) {
        val script = runCatching {
            assets.open("www/oc-perf.js").bufferedReader().use { it.readText() }
        }.getOrElse {
            Log.w(TAG, "oc-perf.js missing, skipping probe")
            return
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            runCatching { WebViewCompat.addDocumentStartJavaScript(wv, script, setOf("*")) }
                .onSuccess { PerfMarks.mark("E2.perfProbeRegistered") }
                .onFailure { Log.w(TAG, "perf probe register failed", it) }
        } else {
            Log.w(TAG, "DOCUMENT_START_SCRIPT unsupported -> perf probe skipped")
        }
    }

    /**
     * G1：把 `assets/www/oc-preflight.js` 注册成**文档开始**脚本，回报首屏内容信号。
     *
     * 拿不到特性（老 WebView）时退回 `onPageStarted` 注入 —— 那时也已经晚了，
     * 但 83 那种设备在**前置检查**就被拦住了，走不到这里。
     */
    private fun installPreflightProbe(wv: WebView) {
        val script = runCatching {
            assets.open("www/oc-preflight.js").bufferedReader().use { it.readText() }
        }.getOrElse {
            Log.w(TAG, "oc-preflight.js missing, first-content signal unavailable", it)
            return
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            runCatching { WebViewCompat.addDocumentStartJavaScript(wv, script, setOf("*")) }
                .onSuccess { PerfMarks.mark("E3.g1ProbeRegistered") }
                .onFailure { Log.w(TAG, "g1 probe register failed", it) }
        } else {
            Log.w(TAG, "DOCUMENT_START_SCRIPT unsupported -> g1 probe falls back to onPageStarted")
        }
    }

    // ---- 返回：**先问页面** → 再退历史 → 什么都不做 -------------------------
    //
    // F18（缺陷 B）：改前是 `if (webView.canGoBack()) { goBack(); return }` **优先**，
    // 页面自己的 `onBack()` 排在 else 分支里。而上游 SPA 用 History API 做站内导航，
    // 进会话时已经压了一条 history ⇒ 面板打开态按 BACK 时 `canGoBack()==true`
    // ⇒ 直接 `goBack()` 把 SPA 路由退回首屏，**面板没关、还退了两级**。
    //
    // 改成"先问页面"：页面知道有没有浮层开着，能消费就消费（关面板），
    // 消费不掉才落到历史回退 —— 这与桌面端"Esc 先关浮层、否则才后退"的心智一致。
    private fun installBackHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                webView.evaluateJavascript(
                    "(function(){try{" +
                        "return !!(window.__ocAndroid && window.__ocAndroid.onBack && window.__ocAndroid.onBack());" +
                        "}catch(e){return false}})()"
                ) { result ->
                    val pageHandled = result?.trim() == "true"
                    if (pageHandled) {
                        // 页面消费了（例如关掉 popover）⇒ 绝不走历史，绝不 finish。
                        Log.i(TAG, "back: pageHandled=true (overlay closed), no history pop")
                        return@evaluateJavascript
                    }
                    // 页面没处理，才退 WebView 历史。
                    if (webView.canGoBack()) {
                        webView.goBack()
                        Log.i(TAG, "back: pageHandled=false -> goBack()")
                    } else {
                        // 注意：**没有** finish 分支。
                        // 禁用直接 finish 是硬要求：Web 壳里 Activity 退出会丢会话页状态。
                        Log.i(TAG, "back: canGoBack=false, pageHandled=false (no finish)")
                    }
                }
            }
        })
    }

    // ---- insets → 页面 ----------------------------------------------------
    private fun pushInsets(payload: JSONObject) {
        val json = payload.toString()
        evaluateJs("window.__ocAndroid && window.__ocAndroid.applyInsets($json);")
        applySystemBarTheme()
    }

    /** 系统栏图标明暗跟随页面 `data-color-scheme`（页面用 reportTheme 报上来）。 */
    private var systemDark: Boolean = false

    fun applyPageTheme(dark: Boolean) {
        if (pageReportedDark == dark) return
        pageReportedDark = dark
        systemDark = dark
        applySystemBarTheme()
    }

    private fun applySystemBarTheme() {
        if (!::insets.isInitialized) return
        val dark = pageReportedDark ?: (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        insets.applyBarsTheme(dark)
    }

    fun setImeMode(mode: ImeMode) {
        if (!::insets.isInitialized) return
        insets.setImeMode(mode)
    }

    // ---- 通知 ------------------------------------------------------------
    fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Log.i(TAG, "pre-13: no runtime permission needed")
            return
        }
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

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

    // ---- BR1 验收用调试注入（仅 DEBUG 构建） ------------------------------
    private fun consumeDebugEval(intent: Intent?) {
        if (!BuildConfig.DEBUG) return
        val raw = intent?.getStringExtra(EXTRA_DEBUG_EVAL) ?: return
        intent.removeExtra(EXTRA_DEBUG_EVAL)
        // 等首屏 + facade 装好再注，否则 evaluateJavascript 落在中间态文档上。
        webView.postDelayed({
            // 官方 UI 的 CSP 没有 'unsafe-eval'，页面里的 eval/Function 一律被浏览器拒
            // （实测 ERR:EvalError）。所以复杂探针用 `b64:<base64(utf8 js)>` 传：宿主侧
            // 解码后把 **JS 原文**交给 evaluateJavascript 直接执行（它本身就是表达式
            // 执行器，不经页面 eval），返回值走 evaluateJavascript 的回调进 logcat。
            // 不带 b64: 前缀时保持旧行为（String() 包一层，表达式求值）。
            val js = if (raw.startsWith("b64:")) {
                String(Base64.getDecoder().decode(raw.removePrefix("b64:")), Charsets.UTF_8)
            } else {
                "(function(){try{return String($raw)}catch(e){return 'ERR:'+e}})()"
            }
            webView.evaluateJavascript(js) { r ->
                Log.i(EVAL_TAG, "eval=${raw.take(200)} -> ${r ?: "null"}")
            }
        }, EVAL_SETTLE_MS)
    }

    // ---- 文件 ------------------------------------------------------------
    fun launchOpenFile(accept: String?, multiple: Boolean) {
        pickedUri = null
        val mime = accept?.substringBefore(',')?.trim().orEmpty()
        val intent = Intent(if (multiple) Intent.ACTION_OPEN_DOCUMENT else Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = if (mime.isNotEmpty()) mime else "*/*"
            if (mime.isNotEmpty() && accept!!.contains(',')) {
                putExtra(Intent.EXTRA_MIME_TYPES, accept.split(',').map { it.trim() }.toTypedArray())
            }
        }
        fileChooser.launchOpen(intent) { uris ->
            val first = uris.firstOrNull()
            if (first == null) {
                bridge.emitFilePicked(JSONObject().put("cancelled", true))
                return@launchOpen
            }
            pickedUri = first
            bridge.emitFilePicked(fileChooser.describe(first).put("multiple", multiple).put("count", uris.size))
        }
    }

    /** 桥的 `readPickedFile()`：消费掉待读 Uri 并把内容 inline 成 base64 回给页面。 */
    fun readPickedInline(): String {
        val uri = consumePickedUri()
            ?: return JSONObject().put("error", "no pending file").toString()
        return fileChooser.readInline(uri).toString()
    }

    fun consumePickedUri(): Uri? {
        val u = pickedUri
        pickedUri = null
        return u
    }

    fun clearPickedUri() {
        pickedUri = null
    }

    fun launchSave(name: String?, mime: String?, base64: String?) {
        val b64 = base64.orEmpty()
        fileChooser.launchSave(name?.takeIf { it.isNotBlank() } ?: "download", mime?.takeIf { it.isNotBlank() } ?: "application/octet-stream", b64) { result ->
            bridge.emitFileSaved(result)
        }
    }

    // ---- 杂项 ------------------------------------------------------------
    fun evaluateJs(js: String) {
        if (!::webView.isInitialized) return
        runCatching { webView.evaluateJavascript(js, null) }
            .onFailure { Log.w(TAG, "evaluateJavascript failed", it) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::webView.isInitialized) {
            outState.putString(STATE_URL, webView.url ?: bootUrl())
            outState.putInt(STATE_SCROLL_Y, webView.scrollY)
        }
    }

    /**
     * UX4 · 深浅色配置变更（`uiMode` 在 manifest 的 configChanges 里 ⇒ Activity 不重建，
     * 配置直接投递到这里）。
     *
     * 改前**没有这个 override**：系统切深/浅色后宿主侧所有派生状态
     * （`--oc-scheme` / `colorScheme` CSS 变量、系统栏图标明暗兜底）都停留在
     * 旧值 —— [InsetsHost.publish] 只在 payload 变化时通知，而 insets 读数本身
     * 没变（状态栏高度不会因为深浅色变化），所以 scheme 翻转**不会**触发重发；
     * 页面主题本身靠 WebView 的 `prefers-color-scheme` 自动跟随（上游
     * `context.tsx` 监听其 change 事件），但宿主自己手里这份"系统深浅色"
     * 不复查就一直 stale。
     *
     * 这里做三件事：
     *  1. `super` 交给 AppCompat 走 DayNight 主题的标准处理；
     *  2. `insets.refresh()` 重推一次 payload —— `dark`/`scheme` 字段已翻转，
     *     去重比较会通过，页面侧 `applyInsets` 拿到新 `--oc-scheme`；
     *  3. 清掉 `pageReportedDark`：它是**页面某次**上报的快照，深浅色切换后
     *     必须让 [applySystemBarTheme] 的兜底重新读当前 `uiMode`，
     *     等页面下一次 reportTheme 再精确跟随（页面若不再上报，兜底也正确）。
     *
     * 安全的重入性：`onResume` 本来就会 `insets.refresh()`，重复 refresh
     * 只会被 payload 去重挡掉，不会重复打扰页面。
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!::insets.isInitialized) return
        pageReportedDark = null
        insets.refresh()
        applySystemBarTheme()
    }

    override fun onPause() {
        super.onPause()
        // P6：先落状态再清 flag。顺序有意为之 —— 让此刻之后任何 `setKeepScreenOn(true)`
        // 的桥调用都立刻被 [isForeground] 拒掉（哪怕 `clearFlags` 还没跑到主线程）。
        foreground = false
        // D1：撤掉挂着的兜底复查（回合已不在前台，没必要再问一次）。
        if (::webView.isInitialized) webView.removeCallbacks(keepScreenOnBackstop)
        // P6 · 省电 · 刀 1：切后台**无条件**清常亮。
        //
        // 为什么是"无条件"而不是"只在页面发过 false 时清"：这正是 T1 §7.3 点名的
        // **忘关路径** —— 回合中途页面崩溃 / WebView 渲染进程被杀 / 用户切走而没发
        // `setKeepScreenOn(false)`，flag 会一直留到 Activity 销毁。屏幕常亮是耗电大户，
        // 所以这里把"宿主离开前台"当作**硬边界**：只要不在前台，就不许常亮。
        // 决策在 [PowerPolicy.shouldKeepScreenOn]（纯 Kotlin、可单测）。
        clearKeepScreenOnOnBackground()
        // P6 · 刀 2（UX8 恢复）：切后台**主动断开 SSE**。
        //
        // 复用上游既有机制：client connection 的 pageLifecycle（_upstream
        // packages/client/src/solid/connection.ts:219-240）是
        //   pagehide → stop()（:202-209，`stream?.abort()` 真正掐断 /api/event）
        //   pageshow → start()（重连）
        // 上游 App 也开着它（packages/app/src/runtime/server/client.tsx:90-93
        // `pageLifecycle: true`）。但 WebView 切后台**不发 pagehide**
        // （connection.ts:222-223 注释自己承认），机制在位、扳机缺失 ——
        // 宿主合成派发补上。波及面全 _upstream 检索过：pagehide 的其它监听者
        // 只有 layout.tsx:363 / persist.ts:20 两个 flush（落盘，无害且有益）；
        // stop()/start() 均幂等（:185-206），即便 WebView 将来原生补发也不会双连。
        dispatchPageLifecycleEvent("pagehide")
        if (::webView.isInitialized) webView.onPause()
    }

    override fun onResume() {
        super.onResume()
        foreground = true
        // D1：回合若仍在进行，回前台要**把常亮补回来**（否则回合中途切走再回来，
        // 回合还在跑屏幕却不再常亮）。回合已结束则这里等价于一次幂等 clearFlags。
        val active = roundTracker.isRoundActive(System.currentTimeMillis())
        applyKeepScreenOn(active)
        if (::webView.isInitialized) {
            webView.onResume()
            insets.refresh()
            // P6 · 刀 2（UX8 恢复）：回前台重建 SSE —— 合成 pageshow 触发上游
            // connection.ts:221 的 start()。幂等（:185-188 已 started 则直接返回）。
            dispatchPageLifecycleEvent("pageshow")
        }
    }

    /**
     * P6 · 刀 2：把页面生命周期事件**合成派发**给页面。
     *
     * 上游 client 的 pageLifecycle 只认 window 的 pagehide/pageshow
     * （connection.ts:230-231），而 Android WebView 切后台/回前台不发这两个事件
     * （只 pause/resume JS 时钟），所以机制虽有、WebView 里永远不会被触发。
     * 宿主生命周期是唯一可靠的信号源 ⇒ 在这里把事件派给页面。
     *
     * 合成事件 `isTrusted=false`；上游 handler 是不校验信任位的普通闭包
     * （connection.ts:220-221），合成事件触发它 = 执行上游自己的 stop()/start()，
     * 不是绕过上游另起一套。不携带、不触碰任何凭据（纯事件名字符串）。
     */
    private fun dispatchPageLifecycleEvent(name: String) {
        if (!::webView.isInitialized) return
        Log.i(TAG, "P6 dispatch synthetic $name (upstream pageLifecycle)")
        evaluateJs("window.dispatchEvent(new Event(\"$name\"))")
    }

    /**
     * P6 · 兜底清常亮。
     *
     * 用 `clearFlags` 而不是 `addFlags(x and x.inv())`：直接 `clearFlags` 不依赖
     * "当前值是什么"，因此**幂等**、且天然清掉别人（或历史代码）设的同一 flag。
     */
    fun clearKeepScreenOnOnBackground() {
        runCatching { window.clearFlags(PowerPolicy.FLAG_KEEP_SCREEN_ON_BIT) }
    }

    /**
     * D1 · **按 [PowerPolicy] 的结论真正置位/清除常亮 flag**。
     *
     * 这是"回合进行中保持屏幕常亮"**唯一**的 flag 写入口。UX6 起驱动方只剩
     * 页面桥；UX8 起注入层回合传感器（`oc-host.js installRoundPowerSensor`，
     * 观测发消息 POST 与 /api/event 的 session.execution.* 帧）经
     * `WebHostBridge.setKeepScreenOn` 喂给 [roundTracker] ⇒ 功能恢复生效。
     *
     * @param roundActive 当前是否有回合在进行
     */
    fun applyKeepScreenOn(roundActive: Boolean) {
        val should = PowerPolicy.shouldKeepScreenOn(foreground = isForeground(), roundActive = roundActive)
        val next = if (should) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            true
        } else {
            window.clearFlags(PowerPolicy.FLAG_KEEP_SCREEN_ON_BIT)
            false
        }
        // D1：置位后**主动排一次兜底复查**。
        //
        // 为什么必须排：`RoundActivityTracker.isRoundActive` 是**被问才结算**的纯函数 ——
        // 90 s 兜底超时只是"判定规则"，**没有任何定时器会自己去问**。
        // 若不排这一下，回合中途崩溃/渲染进程被杀（既无结束事件、也没人再调桥）时
        // flag 就会**永久挂着** —— 正是 T1 §7.3 的忘关路径。
        // 实测教训：设备上只靠规则时，t=+91 s 仍然 `KEEP_SCREEN_ON hits=1`；
        // 加上这一行复查后，t=+95 s 归 0。
        if (next) scheduleKeepScreenOnBackstopCheck()
        Log.i(
            TAG,
            "D1 applyKeepScreenOn: roundActive=$roundActive foreground=${isForeground()} " +
                "shouldKeep=$should flagSet=$next",
        )
    }

    /** D1：兜底复查用的 runnable（同一时刻只挂一个，重复挂会互相覆盖成"最后一次"）。 */
    private val keepScreenOnBackstop = Runnable {
        val stillActive = roundTracker.isRoundActive(System.currentTimeMillis())
        if (stillActive) {
            // 回合还活着（心跳还在续命）⇒ 重新挂一次，别让它漏掉下一次兜底。
            applyKeepScreenOn(true)
        } else {
            applyKeepScreenOn(false)
        }
    }

    private fun scheduleKeepScreenOnBackstopCheck() {
        if (!::webView.isInitialized) return
        webView.removeCallbacks(keepScreenOnBackstop)
        val now = System.currentTimeMillis()
        val tracker = roundTracker
        // 两种兜底谁先到就按谁：
        //  ① 空闲超时（回合没动静了）—— 常规路径；
        //  ② 硬上限剩余量 —— 兜底中的兜底。常驻心跳会无限续命，
        //     只靠 ① 永远不会触发。
        val holdMs = tracker.roundHoldTimeoutMs
        val remainMs = (tracker.maxRoundKeepMs - tracker.roundAgeMs(now)).coerceAtLeast(1L)
        webView.postDelayed(keepScreenOnBackstop, minOf(holdMs, remainMs))
    }

    override fun onDestroy() {
        // P6：销毁时也清一次（窗口虽马上就没了，但 flag 泄漏会在 `onSaveInstanceState`
        // 重建路径上被观察到；清掉是零成本的）。
        clearKeepScreenOnOnBackground()
        if (::webView.isInitialized) {
            webView.removeJavascriptInterface(WebHostBridge.INJECT_NAME)
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        }
        super.onDestroy()
    }

    // ---- WebViewClient ----------------------------------------------------
    // G1：必须是 `inner` —— 运行期兜底要读 Activity 的 `watchdogArmed` 并调 `showDiagnostic`。
    private inner open class HostWebViewClient : WebViewClient() {
        private var assetLoader: WebViewAssetLoader? = null

        fun loader(ctx: android.content.Context): WebViewAssetLoader =
            assetLoader ?: WebViewAssetLoader.Builder()
                .setDomain(ASSET_DOMAIN)
                .addPathHandler(ASSET_PREFIX, WebViewAssetLoader.AssetsPathHandler(ctx))
                .build()
                .also { assetLoader = it }

        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest,
        ): WebResourceResponse? = loader(view.context).shouldInterceptRequest(request.url)

        // 参数用 `WebView?`：installFacade 的匿名子类里本来就有一个同签名的 override
        // （`view: WebView?`）。这里若声明成非空，那个匿名子类就会"overrides nothing"。
        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
            super.onPageStarted(view, url, favicon)
            PerfMarks.mark("K.onPageStarted", "url=$url")
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError,
        ) {
            super.onReceivedError(view, request, error)
            PerfMarks.mark(
                "K2.onReceivedError",
                "url=${request.url} code=${error.errorCode} desc=${error.description} main=${request.isForMainFrame}",
            )
            // G1：**只有主文档**挂掉才切诊断页。子资源 404 不切 —— 那会误伤能正常用的页面。
            val reason = WebViewPreflight.loadErrorVerdict(request.isForMainFrame) ?: return
            if (!watchdogArmed) return
            showDiagnostic(
                reason,
                "code=${error.errorCode} ${error.description} url=${request.url}",
            )
        }

        /**
         * G1：渲染进程崩溃兜底 —— **本轮未接线**，诚实记为未闭环。
         *
         * 想接的是 `onRenderProcessGone`（API 26），但本模块编译期解析不到
         * `android.webkit.WebViewRenderProcessDetail`（真跑报错：
         * `Unresolved reference 'WebViewRenderProcessDetail'` / `'onRenderProcessGone' overrides nothing`）。
         * 根因**未核对**（本机 SDK 的 android.jar 路径未能定位，来不及深挖）。
         *
         * 代价评估：该 hook 缺位**不影响**本轮修的故障 —— WebView 83 那次白屏里
         * 渲染进程**没有**崩（资源全 200、`onPageFinished` 照常回调），本来也抓不到它；
         * 真要崩了，12 s 看门狗仍会兜住（崩了就更不会有首屏内容信号）。
         * 诊断页的「渲染进程崩溃」文案已就位（`Reason.RENDER_CRASH`），
         * 宿主补上这个 override 就能直接用。
         */
        private fun onRenderProcessGoneNotWired() = Unit

        override fun onPageFinished(view: WebView, url: String) {
            super.onPageFinished(view, url)
            PerfMarks.mark("L.onPageFinished", "url=$url")
            // F18（缺陷 A）：首屏 insets 补发。
            // 第一次 publish 发生在 onCreate 的 requestApplyInsets()，那时页面还不存在，
            // evaluateJs 打在空文档上 ⇒ payload 丢失；顶部 inset 是静态的，之后再不变，
            // 去重逻辑又不会重发。此刻 documentElement 一定在了，显式补一次（不走去重）。
            if (::insets.isInitialized) {
                insets.replay()
                Log.i(TAG, "insets replayed after page finish: $url")
            }
            // 首屏 insets / 主题补一次（此刻 documentElement 一定在了）。
            if (contextCompatDark(view)) {
                Log.d(TAG, "page finished: $url")
            }
        }

        private fun contextCompatDark(view: WebView): Boolean =
            (view.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
    }

    // ---- WebChromeClient（文件选择） ---------------------------------------
    private inner class HostWebChromeClient : WebChromeClient() {
        override fun onShowFileChooser(
            webView: WebView,
            callback: ValueCallback<Array<Uri>>,
            params: FileChooserParams,
        ): Boolean {
            // 自己拿 Uri[]，因此不用 callback.onReceiveValue 的标准 parseResult 路径，
            // 而是让 SAF 结果走 bridge → JS 的 `oc:filePicked` 事件。
            val intent = params.createIntent().apply {
                if (params.acceptTypes.isNotEmpty()) {
                    val types = params.acceptTypes.filter { it.isNotBlank() }
                    if (types.isNotEmpty()) {
                        type = if (types.size == 1) types[0] else "*/*"
                        if (types.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, types.toTypedArray())
                    }
                }
            }
            fileChooser.launchOpen(intent) { uris ->
                // 仍然回灌标准 callback：老页面靠它拿 File。
                callback.onReceiveValue(if (uris.isEmpty()) null else uris.toTypedArray())
            }
            return true
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            // F3 / N-2: upstream's server pairing scanner really opens the camera
            // (origin/v2 servers/connect/scanner.tsx:19-34 QrScanner over <video>,
            //  :38 scanner.start() -> getUserMedia). WebView surfaces that as
            // onPermissionRequest with RESOURCE_VIDEO_CAPTURE, so we must grant it
            // or pairing scan is dead in this host (upstream then degrades
            // gracefully, screen.tsx:136-155 disables the button).
            //
            // SECURITY: a PermissionRequest carries the ORIGIN that asked
            // (PermissionRequest.getOrigin()). We never grant to an arbitrary
            // origin -- only to this host's own configured backend origin (the
            // page we ourselves load in loadUrl).
            val origin = request.origin?.toString()
            val trusted = origin != null && isTrustedWebOrigin(origin)
            val wanted = request.resources.filter { it == PermissionRequest.RESOURCE_VIDEO_CAPTURE }
            if (!trusted || wanted.isEmpty()) {
                Log.i(TAG, "camera permission DENIED origin=$origin resources=${request.resources.toList()}")
                request.deny()
                return
            }

            // Already granted at runtime (API 23+): hand it over now.
            if (ContextCompat.checkSelfPermission(
                    this@WebHostActivity,
                    Manifest.permission.CAMERA,
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                Log.i(TAG, "camera permission GRANTED (already held) origin=$origin")
                request.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE))
                return
            }

            // Not held yet. Do NOT grant now: the Android round-trip is async and
            // granting before the user answers would hand the page a stream that
            // then silently dies. Instead ask, remember the pending request, and
            // answer from the ActivityResult callback.
            //
            // Denying is safe: upstream camera.ts:10-14 only marks the permission
            // denied, and screen.tsx:140,148-154 then disables the scan button and
            // shows its own "camera unavailable" copy. We add no self-made UI text.
            Log.i(TAG, "camera permission REQUEST origin=$origin (runtime grant pending)")
            pendingCameraPermission = request
            cameraPermission.launch(Manifest.permission.CAMERA)
        }

        override fun onConsoleMessage(m: ConsoleMessage): Boolean {
            Log.i(TAG, "web[${m.messageLevel()}] ${m.message()} @${m.lineNumber()}")
            return true
        }
    }

    // ---- F3 / N-2: camera runtime permission for the pairing scanner -------

    private var pendingCameraPermission: PermissionRequest? = null

    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val request = pendingCameraPermission
        pendingCameraPermission = null
        if (request == null) {
            Log.i(TAG, "camera permission result=$granted but no pending request")
            return@registerForActivityResult
        }
        if (granted) {
            // Grant ONLY the whitelisted resource, never request.resources itself:
            // passing the whole array through would auto-allow any resource type
            // the page asks for later (e.g. RESOURCE_AUDIO_CAPTURE).
            Log.i(TAG, "camera permission GRANTED origin=${request.origin}")
            request.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE))
        } else {
            Log.i(TAG, "camera permission DENIED (user) origin=${request.origin}")
            request.deny()
        }
    }

    /**
     * F3: 该 origin 是否是本宿主**自己**加载的页面。
     *
     * 可信来源：配置的后端 origin（SecretStore.KEY_BACKEND_URL，默认
     * `http://127.0.0.1:4095`）—— 页面直连后端，origin 就是它。
     *
     * 刻意**不做**的：不按域名/正则泛化、不允许任意站点。
     * 一个 origin 拿到摄像头 = 用户不知道自己正被谁看着。
     */
    private fun isTrustedWebOrigin(origin: String): Boolean {
        val candidates = buildList {
            runCatching {
                SecretStore.getSecret(this@WebHostActivity, SecretStore.KEY_BACKEND_URL)
            }.getOrNull()?.let { add(it) }
            add(SecretStore.DEFAULT_BACKEND_URL)
        }
        return candidates.any { trusted ->
            trusted.isNotBlank() && sameOrigin(trusted, origin)
        }
    }

    /** 只比 scheme://host:port，路径与末尾斜杠无关。 */
    private fun sameOrigin(a: String, b: String): Boolean = try {
        val ua = Uri.parse(a.trim())
        val ub = Uri.parse(b.trim())
        val portOf = { u: Uri -> if (u.port != -1) u.port else if (u.scheme == "https") 443 else 80 }
        ua.scheme != null && ua.scheme == ub.scheme &&
            ua.host != null && ua.host.equals(ub.host, ignoreCase = true) &&
            portOf(ua) == portOf(ub)
    } catch (t: Throwable) {
        Log.w(TAG, "sameOrigin failed a=$a b=$b", t)
        false
    }
}
