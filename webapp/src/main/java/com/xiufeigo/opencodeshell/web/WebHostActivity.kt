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
import com.xiufeigo.opencodeshell.web.host.LoopbackProxy
import com.xiufeigo.opencodeshell.web.host.NotifierHost
import com.xiufeigo.opencodeshell.web.host.PerfMarks
import com.xiufeigo.opencodeshell.web.host.BackendProbe
import com.xiufeigo.opencodeshell.web.host.ConnectErrorPage
import com.xiufeigo.opencodeshell.web.host.PowerPolicy
import com.xiufeigo.opencodeshell.web.host.RoundActivityTracker
import com.xiufeigo.opencodeshell.web.host.SecretStore
import com.xiufeigo.opencodeshell.web.host.SetupDialogMetrics
import com.xiufeigo.opencodeshell.web.host.WebHostBridge
import com.xiufeigo.opencodeshell.web.host.WebViewPreflight
import org.json.JSONObject

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
 */
class WebHostActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "OcWebHost"
        const val ASSET_DOMAIN = "appassets.androidplatform.net"
        const val ASSET_PREFIX = "/assets/"
        /**
         * W1 的占位页（资产托管）。R1 起**不再**用它当首页，只在代理起不来时兜底，
         * 好让"页面全白"和"代理没起来"两种故障在屏幕上可区分。
         */
        const val LEGACY_PLACEHOLDER_URL = "https://$ASSET_DOMAIN$ASSET_PREFIX/www/index.html"
        val HOME_URL: String get() = LEGACY_PLACEHOLDER_URL
        const val FACADE_ASSET = "$ASSET_PREFIX/www/oc-host.js"
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
         * P2 性能归因：同一个 APK 跑两种行为，**改前/改后二进制完全一致**，
         * 避免"重编译带来的差异"污染对比。
         *
         *  - `baseline`（默认）：不改任何行为，作为改前基准。
         *  - `fixed`：打开 gzip 透传 + 连接复用（见 [LoopbackProxy] 的 `fixedMode`）。
         *
         * 用 `--es oc_perf_mode fixed` 传。
         */
        const val EXTRA_PERF_MODE = "oc_perf_mode"
        /** P3：代理预热（并行化）开关。P3 实测在模拟器上更慢，默认 false。 */
        const val EXTRA_PREWARM = "oc_perf_prewarm"

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

    /** P2/P3：本次运行的 `oc_perf_mode`。见 [EXTRA_PERF_MODE]。 */
    private var perfMode: String = "gzip"
    private val fixedMode: Boolean get() = perfMode == "fixed"
    /** P3：gzip 透传**已合入**，除 `baseline`（改前基准）外都开。 */
    private val gzipMode: Boolean get() = perfMode != "baseline"
    /** P3 帧时归因：文档渲染档位 full / noanim / min / flat。 */
    private val renderMode: String
        get() = when (perfMode) {
            "min" -> "min"
            "noanim" -> "noanim"
            "flat" -> "flat"
            else -> "full"
        }

    private lateinit var webView: WebView
    private lateinit var bridge: WebHostBridge
    private lateinit var fileChooser: FileChooserHost
    private lateinit var insets: InsetsHost
    private var proxy: LoopbackProxy? = null

    private var pickedUri: Uri? = null
    private var pendingPageScrollY: Int = 0
    private var pageReportedDark: Boolean? = null

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

    /** 供 [WebHostBridge.setKeepScreenOn] 在 JS 桥线程上问"现在在前台吗"。 */
    fun isForeground(): Boolean = foreground

    /**
     * D1：回合活跃跟踪器（代理可能还没起来 ⇒ 可空）。
     * 供 [WebHostBridge.setKeepScreenOn] 把页面的显式请求喂进同一个状态机。
     */
    fun roundActivityTracker(): RoundActivityTracker? = runCatching { proxy?.roundActivity }.getOrNull()

    /**
     * D1：按当前"回合是否在进行"重新结算一次常亮 flag。
     * 桥线程与主线程都会调它，自身保证切到主线程。
     */
    fun applyKeepScreenOnState() {
        val active = runCatching {
            proxy?.roundActivity?.isRoundActive(System.currentTimeMillis())
        }.getOrNull() ?: false
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
        // `launch` 里会先做 preflight：通过 ⇒ 重新挂看门狗并加载官方 UI；
        // 仍过旧 ⇒ 又回到诊断页（且带上新的真实读数）。
        launch(SecretStore.getSecret(this, SecretStore.KEY_BACKEND_URL) ?: SecretStore.DEFAULT_BACKEND_URL)
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
        ActivityResultContracts.RequestPermission()
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
        perfMode = intent?.getStringExtra(EXTRA_PERF_MODE) ?: "gzip"
        PerfMarks.mark("A.onCreate.enter", "mode=$perfMode")
        super.onCreate(savedInstanceState)

        PerfMarks.mark("A.onCreate.superDone", "mode=$perfMode")

        bridge = WebHostBridge(this) { proxy?.publicOrigin() }
        fileChooser = FileChooserHost(this)
        NotifierHost.ensureChannels(this)
        PerfMarks.mark("B.helpersReady")
        // P3 实测结论：把代理预热挪到后台与 WebView 创建并行，**在模拟器上更慢**
        // （loadUrl 1102ms → 1171ms，TotalTime 无改善）。代理 bind 本身 ~225-246ms 且是
        // CPU 密集，与主线程的 WebView 类加载抢核 ⇒ 默认**不开**，仅留 opt-in 供复现。
        if (intent?.getBooleanExtra(EXTRA_PREWARM, false) == true) {
            prewarmProxyAsync()
            PerfMarks.mark("B2.prewarmKicked")
        }

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

        // P2：WebView 第一次真正把内容画进窗口的时刻（"首帧白屏"的终点）。
        webView.viewTreeObserver.addOnDrawListener(
            object : android.view.ViewTreeObserver.OnDrawListener {
                private var first = true
                override fun onDraw() {
                    if (first) {
                        first = false
                        PerfMarks.mark("Z.firstDraw")
                    }
                }
            }
        )

        insets = InsetsHost(window, webView) { payload -> pushInsets(payload) }
        installBackHandler()
        PerfMarks.mark("F.insetsReady")

        pendingPageScrollY = savedInstanceState?.getInt(STATE_SCROLL_Y, 0) ?: 0
        // R1：先确认凭据 → 起回环代理 → 从**代理 origin** 加载官方 UI。
        // 恢复态的旧 URL 端口多半已失效（ServerSocket(0) 每次都换端口），重算。
        val restored = savedInstanceState?.getString(STATE_URL)
        startProxyAndLoad(restored)
        consumeNotificationIntent(intent)
        consumeDebugEval(intent)
        PerfMarks.mark("G.onCreate.exit")
    }

    /**
     * R1 启动链。
     *
     * ```
     *  EncryptedSharedPreferences 里有密码？ ── 是 ──▶ 起代理 ──▶ loadUrl(代理 origin)
     *                                    └─ 否 ──▶ 原生对话框问一次 ──┘
     * ```
     *
     * 为什么必须原生问：官方 UI 的 `/api/…` 要 Basic 认证，而**不能**把密码交给页面
     * （`window.__ocAndroid.getSecret` 页面能读，等于把口令散给第三方脚本）。
     * 代理在转发时自己补 `Authorization`，页面全程不知道密码存在。
     */
    private fun startProxyAndLoad(restoredUrl: String?) {
        restoredUrlOrNull = restoredUrl
        val backend = SecretStore.getSecret(this, SecretStore.KEY_BACKEND_URL)
            ?: SecretStore.DEFAULT_BACKEND_URL
        val password = SecretStore.getSecret(this, SecretStore.KEY_SERVER_PASSWORD)
        if (password.isNullOrEmpty()) {
            Log.i(TAG, "no password in SecretStore, asking")
            showSetupDialog(backend)
            return
        }
        // F5 缺陷2：**先探一次再加载**。原来直接 `launch`，于是口令错时页面永远停在
        // "Loading" 骨架屏（V7 实测 `research/env/v7/v7_n5_wrongpw.png`），用户无从自救。
        // 探测在后台线程跑（不阻塞主线程），结论回来再决定加载还是报错。
        probeThenLaunch(backend, password)
    }

    /**
     * F5 缺陷2 · 接入探测 → 再加载。
     *
     * 判定与超时依据见 [BackendProbe]（探 `/api/info`，超时 [BackendProbe.PROBE_TIMEOUT_MS]
     * = 8 s，依据三条写在那个类的注释里）。
     *
     * 关键设计：**探不通不阻塞"仍然可以试"**。
     * - [BackendProbe.Result.OK] ⇒ 正常加载官方 UI。
     * - [BackendProbe.Result.AuthFailed] ⇒ 明确告知"用户名或口令不对"，
     *   并给**两条出路**：重试（回到输入口令的对话框）/ 继续看看（仍然加载，
     *   也许后端刚重启好了）。**这一条是本缺陷的核心**：V7 的形态是用户既看不到
     *   失败、也没有任何入口。
     * - [BackendProbe.Result.Unreachable] / [Result.Unknown] ⇒ 也给可重试的原生提示，
     *   但**默认不拦**（连不上时交给代理的友好错误页更合适，见 [ConnectErrorPage]），
     *   用户点"重试"即可重新探测。
     */
    private fun probeThenLaunch(backend: String, password: String?) {
        Log.i(TAG, "F5 probe before load path=${BackendProbe.PROBE_PATH}")
        probeRef?.cancel()
        val probe = BackendProbe()
        probeRef = probe
        probe.probeAsync(backend, password) { result ->
            when (result) {
                is BackendProbe.Result.OK -> {
                    Log.i(TAG, "F5 probe OK code=${result.code}, loading official UI")
                    launch(backend)
                }
                is BackendProbe.Result.AuthFailed -> {
                    // 明确判定：用户名或口令不对。**不回显口令**。
                    Log.w(TAG, "F5 probe AUTH FAILED code=${result.code}; showing retryable dialog")
                    showConnectErrorDialog(ConnectErrorPage.Kind.AUTH_FAILED, backend)
                }
                is BackendProbe.Result.Unreachable -> {
                    Log.w(TAG, "F5 probe unreachable reason=${result.reason}, loading anyway")
                    launch(backend)
                }
                is BackendProbe.Result.Unknown -> {
                    Log.w(TAG, "F5 probe unknown reason=${result.reason}, loading anyway")
                    launch(backend)
                }
            }
        }
    }

    /**
     * F5 · 接入失败的**原生可重试**对话框。
     *
     * 与 [showSetupDialog] 的关系：这是**报错**界面，不是首次配置界面。
     * 它必须让用户能（a）改口令重试、（b）返回、（c）继续加载。
     * 三个按钮文案与 [ConnectErrorPage.Kind] 的文案同源，避免"原生说一套、网页说另一套"。
     */
    private fun showConnectErrorDialog(kind: ConnectErrorPage.Kind, backend: String) {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val minTargetPx = SetupDialogMetrics.minTouchTargetPx(resources.displayMetrics.densityDpi)
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            setPadding(pad, pad / 2, pad, 0)
        }
        box.addView(android.widget.TextView(this).apply {
            text = kind.hint
            setTextColor(0xFFB9B9C0.toInt())
            textSize = 14f
        })

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(kind.heading)
            .setView(box)
            .setCancelable(false)
            .setNeutralButton("返回") { _, _ ->
                // 回到输入口令的对话框：用户在这里能改口令，也能改地址。
                showSetupDialog(backend)
            }
            .setNegativeButton("继续加载") { _, _ -> launch(backend) }
            .setPositiveButton("重试") { _, _ ->
                // 重试 = 重新探一次。口令仍从 SecretStore 读，用户没改过就再探一遍；
                // 若他刚刚在"返回"里改了口令，那次保存已经写回 SecretStore。
                probeThenLaunch(
                    backend,
                    SecretStore.getSecret(this, SecretStore.KEY_SERVER_PASSWORD),
                )
            }
            .show()
            .also { dialog ->
                dialog.window?.setSoftInputMode(
                    android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
                )
                // 触摸靶下限沿用 F2 的既有口径，保证按钮可达 48dp。
                runCatching {
                    dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                        .minHeight = minTargetPx
                    dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE)
                        .minHeight = minTargetPx
                    dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL)
                        .minHeight = minTargetPx
                }
            }
    }

    /** 起代理并把首页指到代理 origin。 */
    private fun launch(backend: String) {
        PerfMarks.mark("H.launch.enter", "backend=$backend mode=$perfMode")
        // P3：预热线程若还在跑就等它 —— 与 WebView 创建的并行已经赚到了重叠部分。
        runCatching { proxyPrewarm?.join(3000) }
        val p = runCatching { synchronized(proxyLock) { pendingProxy } }.getOrElse { null } ?: runCatching {
            LoopbackProxy(
                upstreamBase = backend,
                // 每次转发现读 ⇒ 代理内存里不驻留明文，改密码立即生效。
                passwordProvider = { SecretStore.getSecret(this, SecretStore.KEY_SERVER_PASSWORD) },
                fixedMode = fixedMode,
                gzipMode = gzipMode,
                renderMode = renderMode,
            ).also { it.start() }
        }.getOrElse {
            Log.e(TAG, "cannot start loopback proxy, falling back to placeholder", it)
            webView.loadUrl(LEGACY_PLACEHOLDER_URL)
            return
        }
        pendingProxy = null
        proxy = p
        // D1：接上"回合状态"回调 —— 代理一看到页面 POST 消息/回合流结束就通知，
        // 宿主据此置位/清除常亮 flag。这是让该功能**真正生效**的接线点
        // （此前 flag 只有页面主动调桥才会被置起，而上游 UI 从不调 ⇒ 死代码）。
        p.onRoundActiveChanged = { active -> runOnUiThread { applyKeepScreenOn(active) } }
        val origin = p.publicOrigin()
        val restored = restoredUrlOrNull
        val url = if (restored != null && restored.startsWith(origin)) restored else "$origin/"
        // 只报"有没有"，绝不回显口令本身。
        val hasPassword = !SecretStore.getSecret(this, SecretStore.KEY_SERVER_PASSWORD).isNullOrEmpty()
        Log.i(TAG, "home=$url backend=$backend passwordPresent=$hasPassword")
        PerfMarks.mark("I.proxyListening", "port=${p.port} mode=$perfMode")

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

        armWatchdog()
        webView.loadUrl(url)
        PerfMarks.mark("J.loadUrlIssued", "url=$url")
    }

    private val proxyLock = Object()

    /** P3：预热线程已经建好的代理（见 [prewarmProxyAsync]）。 */
    private var pendingProxy: LoopbackProxy? = null

    private var proxyPrewarm: Thread? = null

    /**
     * F5 缺陷2：当前这轮接入探测。点"重试"会再探一次，所以必须能**取消上一轮**，
     * 否则两次探测的结果可能乱序到达、把界面带去错误的分支。
     */
    private var probeRef: BackendProbe? = null

    /**
     * P3：把「起回环代理」从导航前的串行段里挪出去。
     *
     * 原来它排在 `onCreate` **末尾**，`WebView` 创建（实测 ~370 ms）必须先跑完，
     * 代理监听（实测 ~186 ms）才开始 —— 两者无依赖，纯串行浪费。
     * 现在在 `onCreate` 一进来就把代理起在**后台线程**上，与主线程的
     * WebView 创建 / settings 配置**并行**；到 `launch()` 时若还没起好就 join。
     *
     * 失败语义逐字不变：起不来仍然走 `LEGACY_PLACEHOLDER_URL` 兜底页。
     */
    private fun prewarmProxyAsync() {
        val backend = SecretStore.getSecret(this, SecretStore.KEY_BACKEND_URL)
            ?: SecretStore.DEFAULT_BACKEND_URL
        proxyPrewarm = Thread({
            val t0 = System.nanoTime()
            runCatching {
                LoopbackProxy(
                    upstreamBase = backend,
                    passwordProvider = { SecretStore.getSecret(this, SecretStore.KEY_SERVER_PASSWORD) },
                    fixedMode = fixedMode,
                    gzipMode = gzipMode,
                    renderMode = renderMode,
                ).also { it.start() }
            }.onSuccess { p ->
                synchronized(proxyLock) { pendingProxy = p }
                PerfMarks.mark("I2.proxyPrewarmed", "port=${p.port} ms=${(System.nanoTime() - t0) / 1_000_000}")
            }.onFailure {
                Log.w(TAG, "proxy prewarm failed: ${it.message}")
            }
        }, "oc-proxy-prewarm").apply { isDaemon = true; start() }
    }

    private var restoredUrlOrNull: String? = null

    /**
     * 首次启动的配置对话框。用**原生**对话框而不是让页面自己弹：密码必须在进 JS
     * 之前落到 EncryptedSharedPreferences。
     *
     * F2：两个输入框原先是 `WRAP_CONTENT` 且无 `minHeight`，实测只有 **45dp** 触摸靶
     * （`118px ÷ 2.625`，`research/env/PHONE_AVD_VERIFY.md` §6.2）⇒ 差 3dp 不达 48dp 硬要求。
     * 现在两者都显式 `minHeight = SetupDialogMetrics.minTouchTargetPx(density)`。
     * 取 `minHeight` 而不是 `TouchDelegate` 撑触摸区，理由见 [SetupDialogMetrics] 与
     * `research/web/TOUCH_TARGET_FIX.md` §4（`TouchDelegate` 不改无障碍节点 bounds，
     * 产出这份缺陷证据的 `uiautomator` 将**看不见**修复，判据与守护对象就对不上了）。
     */
    private fun showSetupDialog(defaultBackend: String) {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val densityDpi = resources.displayMetrics.densityDpi
        val minTargetPx = SetupDialogMetrics.minTouchTargetPx(densityDpi)
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            // 必须给 MATCH_PARENT：不设宽度的话 AlertDialog 按内容收缩成一个极窄的框，
            // 输入框和按钮会被挤到可视区之外（真机上表现为只剩标题+一段话）。
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            setPadding(pad, pad / 2, pad, 0)
        }
        val urlInput = android.widget.EditText(this).apply {
            setText(defaultBackend)
            setHint("后端地址")
            isSingleLine = true
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            setInputType(android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI)
            // F2：45dp → 48dp 触摸靶（WRAP_CONTENT 下的固有文本高度只有 45dp）。
            minHeight = minTargetPx
        }
        val passInput = android.widget.EditText(this).apply {
            hint = "服务器密码（用户名固定 ${LoopbackProxy.BASIC_USER}）"
            isSingleLine = true
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            // TYPE_CLASS_TEXT | TYPE_TEXT_VARIATION_PASSWORD：输入时打码。
            setInputType(
                android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD,
            )
            // F2：同上，密码框也抬到 48dp 触摸靶。两个框必须**都**写，漏一个测试就红。
            minHeight = minTargetPx
        }
        // 顺序很重要：**密码框放第一个**。AlertDialog 默认把焦点给第一个可编辑控件，
        // 放第一个就等于"打开即可直接输密码"，不用先点一下 URL 框。
        box.addView(passInput)
        box.addView(urlInput)

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("配置 opencode 后端")
            .setView(box)
            .setCancelable(false)
            .setNegativeButton("先不配置") { _, _ ->
                Log.w(TAG, "setup skipped -> API will 401")
                launch(defaultBackend)
            }
            .setPositiveButton("保存并连接") { _, _ ->
                val url = urlInput.text?.toString()?.trim().orEmpty()
                    .ifEmpty { defaultBackend }
                val pass = passInput.text?.toString().orEmpty()
                SecretStore.setSecret(this, SecretStore.KEY_BACKEND_URL, url)
                SecretStore.setSecret(this, SecretStore.KEY_SERVER_PASSWORD, pass)
                Log.i(TAG, "credentials stored backend=$url passwordPresent=${pass.isNotEmpty()}")
                // F5 缺陷2：**必须走探测**，不能直接 `launch`。
                // V7 的缺陷形态正是"在这里播了错口令"——首测时这条分支直接 launch，
                // 于是错口令 100% 落到"Loading 骨架屏永停"。探测后 401 会被判成
                // AUTH_FAILED 并弹出可重试对话框（可改口令、可返回、可继续加载）。
                probeThenLaunch(url, pass)
            }
            .show()
            .also { dialog ->
                dialog.window?.setSoftInputMode(
                    // ADJUST_RESIZE：键盘弹出时对话框整体上移，输入框与按钮留在可视区。
                    // 注意**不要**再 SOFT_INPUT_STATE_ALWAYS_VISIBLE —— 强行弹键盘会把
                    // 横屏下本就所剩无几的对话框高度压扁，输入框直接被裁掉。
                    android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
                )
            }
    }

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
        // 混合内容：仅兼容模式。
        //
        // 理由：宿主 origin 是 https://appassets.androidplatform.net，而 opencode 后端
        // 常跑在 PC 的 http://192.168.x.x:4096 —— 那必然是明文混合内容。
        // `COMPATIBILITY_MODE` 让 WebView 继续发请求（尽量走 CSP 判定），而
        // `ALWAYS_ALLOW` 会**完全关掉**浏览器的主动拦截（含 XHR/fetch），
        // `NEVER_ALLOW` 则整个开发流程不可用。明文范围另外由
        // `res/xml/network_security_config.xml` 逐条限定（只回环/自建地址）。
        // ⚠ 因此：任何"页面内的第三方 http 资源"都可能走到网络层，
        // 唯一的闸门是那份 network-security-config。
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
     * （对本轮占位页足够，但对真正的上游 SPA 可能错过首屏）。
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
        val js = intent?.getStringExtra(EXTRA_DEBUG_EVAL) ?: return
        intent.removeExtra(EXTRA_DEBUG_EVAL)
        // 等首屏 + facade 装好再注，否则 evaluateJavascript 落在中间态文档上。
        webView.postDelayed({
            webView.evaluateJavascript("(function(){try{return String($js)}catch(e){return 'ERR:'+e}})()") { r ->
                Log.i(EVAL_TAG, "eval=${js.take(200)} -> ${r ?: "null"}")
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
            outState.putString(STATE_URL, webView.url ?: HOME_URL)
            outState.putInt(STATE_SCROLL_Y, webView.scrollY)
        }
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
        // P6 · 省电 · 刀 2（本轮最该砍的一条）：切后台掐断 SSE 长连接。
        //
        // 依据 T1 §7.2：`LoopbackProxy` 自 `start()` 起常驻（`:137-155`），
        // 而 `close()` **只有 `onDestroy` 调**（旧 `:776`）⇒ 退到后台后上游 `/event`
        // 每 10 s 的心跳继续唤醒射频，**且与屏幕亮度无关**。
        //
        // 只掐**流式**连接，不碰 `ServerSocket`/`port`（不是 `proxy?.close()`，
        // 那会把 port 置空导致 `onResume` 恢复失败 —— T1 §7.2 明确警告过）。
        val pruned = runCatching { proxy?.suspendStreaming() ?: 0 }.getOrDefault(0)
        PerfMarks.markIfEnabled("P6.onPause", "prunedStreaming=$pruned")
        if (::webView.isInitialized) webView.onPause()
    }

    override fun onResume() {
        super.onResume()
        foreground = true
        // P6：解除后台拒绝，页面下一次 EventSource 重试即恢复（与代理 35 s 半开
        // 主动断开是同一条恢复路径，不引入新行为类别）。
        runCatching { proxy?.resumeStreaming() }
        // D1：回合若仍在进行，回前台要**把常亮补回来**（否则回合中途切走再回来，
        // 回合还在跑屏幕却不再常亮）。回合已结束则这里等价于一次幂等 clearFlags。
        val active = runCatching { proxy?.roundActivity?.isRoundActive(System.currentTimeMillis()) }
            .getOrNull() ?: false
        applyKeepScreenOn(active)
        if (::webView.isInitialized) {
            webView.onResume()
            insets.refresh()
        }
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
     * 这是"回合进行中保持屏幕常亮"**唯一**的 flag 写入口。之前
     * [WebHostBridge.setKeepScreenOn] 只在页面主动调时才会走到 `addFlags`，
     * 而上游官方 UI 从不调它 ⇒ flag 永远置不起来（死代码，详见
     * `research/web/FIX_D1.md` §1）。现在由 [LoopbackProxy.roundActivity]
     * 用真实流量驱动，页面那条路则改为喂同一个跟踪器。
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
        val stillActive = runCatching {
            proxy?.roundActivity?.isRoundActive(System.currentTimeMillis())
        }.getOrNull() ?: false
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
        val tracker = proxy?.roundActivity
        // 两种兜底谁先到就按谁：
        //  ① 空闲超时（回合没动静了）—— 常规路径；
        //  ② 硬上限剩余量 —— 兜底中的兜底。常驻 `GET /event` 心跳会无限续命
        //     （设备实测：t=+131s 仍 hits=1），只靠 ① 永远不会触发。
        val holdMs = tracker?.roundHoldTimeoutMs ?: 90_000L
        val remainMs = tracker?.let { (it.maxRoundKeepMs - it.roundAgeMs(now)).coerceAtLeast(1L) }
        webView.postDelayed(keepScreenOnBackstop, minOf(holdMs, remainMs ?: holdMs))
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
        // 释放回环端口：ServerSocket + 线程池都是进程内资源，不关会挂着 accept 阻塞。
        runCatching { proxy?.close() }
        proxy = null
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
            error: android.webkit.WebResourceError,
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
            // origin -- only to this host's own loopback proxy origin (the page we
            // ourselves load in loadUrl) or the configured backend origin.
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
        ActivityResultContracts.RequestPermission()
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
     * 两种可信来源：
     *  1. 回环代理的 public origin（`http://127.0.0.1:<port>`，LoopbackProxy.kt:169）
     *     —— 这正是 [launch] 里 `webView.loadUrl` 用的那个 origin（WebHostActivity.kt:412）；
     *  2. 配置的后端 origin（SecretStore.KEY_BACKEND_URL，默认 `http://127.0.0.1:4095`）——
     *     页面直接跑后端时（不经代理）也是我们配的。
     *
     * 刻意**不做**的：不按域名/正则泛化、不允许任意站点。
     * 一个 origin 拿到摄像头 = 用户不知道自己正被谁看着。
     */
    private fun isTrustedWebOrigin(origin: String): Boolean {
        val candidates = buildList {
            proxy?.publicOrigin()?.let { add(it) }
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
