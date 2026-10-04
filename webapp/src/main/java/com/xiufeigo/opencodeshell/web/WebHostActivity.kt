package com.xiufeigo.opencodeshell.web

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
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
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.xiufeigo.opencodeshell.web.host.FileChooserHost
import com.xiufeigo.opencodeshell.web.host.ImeMode
import com.xiufeigo.opencodeshell.web.host.InsetsHost
import com.xiufeigo.opencodeshell.web.host.LoopbackProxy
import com.xiufeigo.opencodeshell.web.host.NotifierHost
import com.xiufeigo.opencodeshell.web.host.PerfMarks
import com.xiufeigo.opencodeshell.web.host.SecretStore
import com.xiufeigo.opencodeshell.web.host.WebHostBridge
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
    }

    /** P2：本次运行是 `baseline` 还是 `fixed`。 */
    private var perfMode: String = "baseline"
    private val fixedMode: Boolean get() = perfMode == "fixed"

    private lateinit var webView: WebView
    private lateinit var bridge: WebHostBridge
    private lateinit var fileChooser: FileChooserHost
    private lateinit var insets: InsetsHost
    private var proxy: LoopbackProxy? = null

    private var pickedUri: Uri? = null
    private var pendingPageScrollY: Int = 0
    private var pageReportedDark: Boolean? = null

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
        perfMode = intent?.getStringExtra(EXTRA_PERF_MODE) ?: "baseline"
        PerfMarks.mark("A.onCreate.enter", "mode=$perfMode")
        super.onCreate(savedInstanceState)

        PerfMarks.mark("A.onCreate.superDone", "mode=$perfMode")

        bridge = WebHostBridge(this) { proxy?.publicOrigin() }
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
        PerfMarks.mark("E.facadeInstalled")
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
        launch(backend)
    }

    /** 起代理并把首页指到代理 origin。 */
    private fun launch(backend: String) {
        PerfMarks.mark("H.launch.enter", "backend=$backend mode=$perfMode")
        val p = runCatching {
            LoopbackProxy(
                upstreamBase = backend,
                // 每次转发现读 ⇒ 代理内存里不驻留明文，改密码立即生效。
                passwordProvider = { SecretStore.getSecret(this, SecretStore.KEY_SERVER_PASSWORD) },
                fixedMode = fixedMode,
            ).also { it.start() }
        }.getOrElse {
            Log.e(TAG, "cannot start loopback proxy, falling back to placeholder", it)
            webView.loadUrl(LEGACY_PLACEHOLDER_URL)
            return
        }
        proxy = p
        val origin = p.publicOrigin()
        val restored = restoredUrlOrNull
        val url = if (restored != null && restored.startsWith(origin)) restored else "$origin/"
        // 只报"有没有"，绝不回显口令本身。
        val hasPassword = !SecretStore.getSecret(this, SecretStore.KEY_SERVER_PASSWORD).isNullOrEmpty()
        Log.i(TAG, "home=$url backend=$backend passwordPresent=$hasPassword")
        PerfMarks.mark("I.proxyListening", "port=${p.port} mode=$perfMode")
        webView.loadUrl(url)
        PerfMarks.mark("J.loadUrlIssued", "url=$url")
    }

    private var restoredUrlOrNull: String? = null

    /**
     * 首次启动的配置对话框。用**原生**对话框而不是让页面自己弹：密码必须在进 JS
     * 之前落到 EncryptedSharedPreferences。
     */
    private fun showSetupDialog(defaultBackend: String) {
        val pad = (16 * resources.displayMetrics.density).toInt()
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
                launch(url)
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

    // ---- 返回：canGoBack → 问页面 → 什么都不做 -----------------------------
    private fun installBackHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                    return
                }
                webView.evaluateJavascript(
                    "(function(){try{" +
                        "return !!(window.__ocAndroid && window.__ocAndroid.onBack && window.__ocAndroid.onBack());" +
                        "}catch(e){return false}})()"
                ) { result ->
                    // 注意：**没有** else 分支去 finish()。
                    // 禁用直接 finish 是硬要求：Web 壳里 Activity 退出会丢会话页状态。
                    val handled = result?.trim() == "true"
                    Log.i(TAG, "back: canGoBack=false, pageHandled=$handled (no finish)")
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
        if (::webView.isInitialized) webView.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (::webView.isInitialized) {
            webView.onResume()
            insets.refresh()
        }
    }

    override fun onDestroy() {
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
    private open class HostWebViewClient : WebViewClient() {
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
        }

        override fun onPageFinished(view: WebView, url: String) {
            super.onPageFinished(view, url)
            PerfMarks.mark("L.onPageFinished", "url=$url")
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

        override fun onConsoleMessage(m: ConsoleMessage): Boolean {
            Log.i(TAG, "web[${m.messageLevel()}] ${m.message()} @${m.lineNumber()}")
            return true
        }
    }
}
