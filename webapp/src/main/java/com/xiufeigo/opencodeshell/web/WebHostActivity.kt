package com.xiufeigo.opencodeshell.web

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.xiufeigo.opencodeshell.web.host.CameraPermissionHost
import com.xiufeigo.opencodeshell.web.host.FileChooserHost
import com.xiufeigo.opencodeshell.web.host.AndroidPlatformHost
import com.xiufeigo.opencodeshell.web.host.BackNavigationHost
import com.xiufeigo.opencodeshell.web.host.DebugEvalHost
import com.xiufeigo.opencodeshell.web.host.BackgroundLifecycleHost
import com.xiufeigo.opencodeshell.web.host.LegacyBridgeChannel
import com.xiufeigo.opencodeshell.web.host.HostWebChromeClient
import com.xiufeigo.opencodeshell.web.host.HostWebViewClient
import com.xiufeigo.opencodeshell.web.host.ImeMode
import com.xiufeigo.opencodeshell.web.host.InsetsHost
import com.xiufeigo.opencodeshell.web.host.LocalUiOriginServer
import com.xiufeigo.opencodeshell.web.host.NotifierHost
import com.xiufeigo.opencodeshell.web.host.NotificationPermissionHost
import com.xiufeigo.opencodeshell.web.host.PerfMarks
import com.xiufeigo.opencodeshell.web.host.RenderProcessRecoveryHost
import com.xiufeigo.opencodeshell.web.host.RoundActivityTracker
import com.xiufeigo.opencodeshell.web.host.StartupRecoveryHost
import com.xiufeigo.opencodeshell.web.host.SurfaceColor
import com.xiufeigo.opencodeshell.web.host.SystemUiThemeHost
import com.xiufeigo.opencodeshell.web.host.SystemFontCatalog
import com.xiufeigo.opencodeshell.web.host.WebHostBridge
import com.xiufeigo.opencodeshell.web.host.WebHostBridgeHost
import com.xiufeigo.opencodeshell.web.host.WebViewBootstrapHost
import com.xiufeigo.opencodeshell.web.host.WebViewViewportFactory
import com.xiufeigo.opencodeshell.web.host.WebHostEscapePolicy
import com.xiufeigo.opencodeshell.web.host.WebOrigins
import com.xiufeigo.opencodeshell.web.host.WebViewPreflight
import org.json.JSONObject

/**
 * APK 内的官方 UI 由 [LocalUiOriginServer] 提供，API 和终端直连用户选择的 OpenCode。
 * [WebViewAssetLoader] 仅提供启动失败时的诊断页。
 * - 边到边 + insets 交给 [InsetsHost] → CSS 变量 / JS 事件。
 * - 返回：页面关闭浮层、退出子页面或回首页；首页交回 Android 的默认返回行为。
 * 服务器凭据由官方 UI 管理。
 */
class WebHostActivity : AppCompatActivity(), WebHostBridgeHost {

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

        /**
         * BR1 验收用的**调试**注入通道：`--es oc_debug_eval "<js>"` 让宿主把一段 JS
         * 丢进当前页面（`evaluateJavascript`），结果打进 logcat 的调试通道。
         * 官方 UI 自己不会主动调 `postMessage`/`getSecret` 这些桥，没有这个
         * 通道就只能从 UI 点，测不到它们。
         *
         * 只在 `BuildConfig.DEBUG` 生效（release 构建直接忽略），且不写文件、不回显口令。
         */
        const val EXTRA_DEBUG_EVAL = "oc_debug_eval"
        /** 页面还没加载完时注入要等一下，否则 evaluateJavascript 落在 about:blank 上。 */

        /**
         * G1：诊断页 URL（就是 C1 留下的那套兜底页资产，只是多带 query 参数换文案）。
         * 阈值与超时的依据全在 [WebViewPreflight] 里。
         *
         * ⚠️ 这里**不能**读实例字段（`StartupRecoveryHost` 中的版本读数）—— companion object
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
    private lateinit var localUiServer: LocalUiOriginServer
    private val systemFonts = SystemFontCatalog()

    private lateinit var platformHost: AndroidPlatformHost
    private lateinit var legacyChannel: LegacyBridgeChannel
    private val cameraPermissionHost: CameraPermissionHost = CameraPermissionHost(
        context = this,
        isTrustedOrigin = { origin -> isTrustedWebOrigin(origin) },
        requestCameraPermission = { cameraPermission.launch(Manifest.permission.CAMERA) },
    )
    private val systemUiTheme = SystemUiThemeHost(
        insetsProvider = { if (::insets.isInitialized) insets else null },
        systemDark = {
            (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        },
    )

    private val backgroundLifecycle = BackgroundLifecycleHost(
        activity = this,
        webViewProvider = { if (::webView.isInitialized) webView else null },
        evaluateJs = { js -> evaluateJs(js) },
        refreshInsets = { if (::insets.isInitialized) insets.refresh() },
    )

    private val renderProcessRecovery = RenderProcessRecoveryHost(
        webViewProvider = { if (::webView.isInitialized) webView else null },
        disposeWebView = { disposeWebView() },
        rebuildWebView = { setupWebViewAndLoad() },
        showCrashDiagnostic = { detail ->
            setupWebViewAndLoad(WebViewPreflight.Reason.RENDER_CRASH to detail)
        },
    )

    private val webViewBootstrap = WebViewBootstrapHost(
        context = this,
        legacyChannel = { legacyChannel },
        assetHost = ASSET_DOMAIN,
    )

    /** 供 [WebHostBridge.setKeepScreenOn] 在 JS 桥线程上访问的跟踪器。 */
    override fun roundActivityTracker(): RoundActivityTracker = backgroundLifecycle.roundTracker

    /** 页面桥读取的前台真值，由后台生命周期宿主统一维护。 */
    override fun isForeground(): Boolean = backgroundLifecycle.isForeground()

    /**
     * D1：按当前"回合是否在进行"重新结算一次常亮 flag。
     * 桥线程与主线程都会调它，自身保证切到主线程。
     */
    override fun applyKeepScreenOnState() {
        backgroundLifecycle.applyKeepScreenOnState()
    }

    // ---- G1：WebView 版本前置检查 + 首屏看门狗 -----------------------------
    private val startupRecovery = StartupRecoveryHost(
        context = this,
        diagnosticBaseUrl = LEGACY_PLACEHOLDER_URL,
        webViewProvider = { if (::webView.isInitialized) webView else null },
    )

    /**
     * 诊断页「重试」按钮的落点：重走一遍启动链。
     *
     * 关键是**重新读一次 WebView 版本**再决定 —— 用户可能刚更新了
     * "Android System WebView"，这时重试就该真的进官方 UI 了。
     */
    override fun retryFromDiagnosticPage() {
        Log.i(TAG, "G1 retry: re-running preflight + launch")
        startupRecovery.readWebViewVersion()
        restoredUrlOrNull = null
        launch(bootUrl())
    }

    /** 桥从 `oc-preflight.js` 收到首屏内容信号 ⇒ 解除看门狗。 */
    override fun onFirstContent(kind: String?, atMs: Long?) {
        startupRecovery.onFirstContent(kind, atMs)
    }

    override val imeModeName: String
        get() = if (::insets.isInitialized) insets.imeMode.name.lowercase() else "vars"

    // ---- 通知权限（必须在 Activity STARTED 之前注册 launcher） --------------
    private val notificationPermissionHost: NotificationPermissionHost = NotificationPermissionHost(
        context = this,
        isForeground = { isForeground() },
        requestRuntimePermission = { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) },
        evaluateJs = { js -> evaluateJs(js) },
    )

    private val notificationPermission: ActivityResultLauncher<String> = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        notificationPermissionHost.onPermissionResult(granted)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        PerfMarks.mark("A.onCreate.enter")
        super.onCreate(savedInstanceState)

        PerfMarks.mark("A.onCreate.superDone")

        bridge = WebHostBridge(
            context = this,
            host = this,
            assetHost = ASSET_DOMAIN,
            notificationTarget = WebHostActivity::class.java,
        )
        fileChooser = FileChooserHost(this)
        NotifierHost.ensureChannels(this)
        PerfMarks.mark("B.helpersReady")

        // UX11：官方前端由 App 自带（assets/www/ui），本机 http 静态来源只服务 UI 资源。
        // 🔴 这个端点不碰凭据、不代理 API —— 认证与 API 调用全部由页面直连用户配置的服务器。
        localUiServer = (application as WebHostApp).localUiServer.also { it.start() }
        PerfMarks.mark("B2.localUiOrigin", "bound=${localUiServer.boundPort}")

        // UX6：冷启动 = 直接加载真实服务器地址（官方前端由后端自托管）。
        // 恢复态的旧 URL 直接沿用（origin 现在由本机静态 UI 服务稳定提供）。
        val restored = savedInstanceState?.getString(STATE_URL)
        restoredUrlOrNull = restored

        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
        setupWebViewAndLoad()

        BackNavigationHost(this) { webView }.install()
        consumeNotificationIntent(intent)
        consumeDebugEval(intent)
        PerfMarks.mark("G.onCreate.exit")
    }

    /**
     * UX16-P2：**整个 WebView 初始化链**（建实例 → 配置 → 注入 → client → insets → 加载）。
     *
     * 纯搬运：与改前 `onCreate` 里的代码逐行相同，不改任何初始化语义。
     *
     * 为什么要抽出来：[onRenderProcessGone] 的崩溃自愈现在**不再** `reload()`，
     * 而是按平台口径**销毁旧实例 + 新建一个**（见该 override 的注释）。
     * 新实例必须走**完全相同**的初始化链 —— 否则 bridge 注入、doc-start 脚本、
     * WebViewClient、insets 至少会漏掉一环，页面会安静地半残（能显示但所有
     * 原生能力失效，比白屏更难查）。
     *
     * ⚠️ 这里**只**放「与 WebView 实例强绑定」的东西：
     * - `bridge` / `fileChooser` 是 Activity 级依赖，`localUiServer` 由进程统一持有。
     *   渲染重建时复用它们，所以它们不在本方法里。
     *   这也保证了重建后 `window.__ocAndroid` 的桥名与注入通道与首次启动完全一致。
     * - `BackNavigationHost.install()` 只在 [onCreate] 调一次：它注册的是
     *   `onBackPressedDispatcher` 回调（注册两次会多挂一个 callback），而回调体
     *   读的是 `webView` **字段**，重建后自动指向新实例。
     * - 焦点/IME 接线与视口容器由 `WebViewViewportFactory` 按实例重建，保证
     *   [onRenderProcessGone] 崩溃恢复后的新实例带同一套 native focus 配置。
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebViewAndLoad(diagnostic: Pair<WebViewPreflight.Reason, String>? = null) {
        val viewport = WebViewViewportFactory(this).create()
        webView = viewport.webView
        setContentView(viewport.root)
        PerfMarks.mark("C.webviewCreated+setContentView")

        webViewBootstrap.configureSettings(webView.settings)
        if (::legacyChannel.isInitialized) legacyChannel.close()
        legacyChannel = LegacyBridgeChannel(bridge)
        webView.addJavascriptInterface(legacyChannel, WebHostBridge.INJECT_NAME)
        if (::platformHost.isInitialized) platformHost.close()
        platformHost = AndroidPlatformHost(this, fileChooser, systemFonts)
        if (localUiServer.boundPort != null && WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            platformHost.install(webView)
        }
        PerfMarks.mark("D.settingsConfigured")
        webViewBootstrap.installDocumentStartScripts(webView)
        PerfMarks.mark("E.facadeInstalled")
        // G1：版本读数必须在 [launch] 之前拿到 —— 前置检查要靠它决定加载官方 UI 还是诊断页。
        startupRecovery.readWebViewVersion()
        webView.webViewClient = HostWebViewClient(
            assetDomain = ASSET_DOMAIN,
            assetPrefix = ASSET_PREFIX,
            systemFonts = systemFonts,
            startupRecovery = startupRecovery,
            renderProcessRecovery = renderProcessRecovery,
            openExternalLink = { url -> openExternalLink(url) },
            onPageReady = { view, url -> onWebViewPageReady(view, url) },
        )
        webView.webChromeClient = HostWebChromeClient(
            fileChooser = fileChooser,
            cameraPermissionHost = cameraPermissionHost,
        )
        webView.setDownloadListener { url, _, _, _, _ -> openExternalLink(url) }

        // insets 绑在实例上（InsetsHost 持 WebView 引用），所以重建时必须重挂一个。
        insets = InsetsHost(window, webView) { payload -> pushInsets(payload) }
        PerfMarks.mark("F.insetsReady")

        if (diagnostic == null) launch(bootUrl()) else startupRecovery.showDiagnostic(diagnostic.first, diagnostic.second)
    }

    /**
     * UX6 · 本次启动要加载的本地 UI origin。
     *
     * 页面来源固定为 `LocalUiOriginServer`；远端后端只由官方 UI 自己连接，
     * 宿主不再把后端地址当成 WebView 的页面 origin。
     */
    private fun bootUrl(): String =
        LocalUiOriginServer.origin()

    /**
     * UX6 启动链（代理已删，只剩一步直达）：
     *
     * ```
     *   bootUrl() ──▶ G1 前置检查 ──▶ StartupRecoveryHost.arm() ──▶ loadUrl(本地 UI origin)
     * ```
     *
     * 没有任何凭据检查：宿主不持有口令，"空口令 ⇒ 不许用"这条旧规则（F21）在新
     * 形态下由**结构**保障 —— 宿主侧根本不存在"以无认证方式使用后端"的代码路径；
     * 认证协商完全发生在官方 UI 与后端之间（官方连接页会自己提示口令错误）。
     */
    private fun launch(uiOrigin: String) {
        PerfMarks.mark("H.launch.enter", "uiOrigin=$uiOrigin")
        if (localUiServer.boundPort == null) {
            startupRecovery.showDiagnostic(WebViewPreflight.Reason.LOAD_ERROR, "本地 UI 服务无法绑定端口 ${localUiServer.port}，请检查端口占用后重启应用")
            return
        }
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            startupRecovery.showDiagnostic(WebViewPreflight.Reason.WEBVIEW_TOO_OLD, "当前 WebView 不支持平台消息和文档开始注入，请更新 Android System WebView")
            return
        }
        val restored = restoredUrlOrNull
        val url = if (restored != null && restored.startsWith(uiOrigin)) restored else "$uiOrigin/"

        // ---- G1 前置能力检查 ------------------------------------------------
        // 过旧就**不加载**官方 UI：实测 WebView 83 加载它只会得到一片纯白
        // （资源全 200、ESM 解析失败、`onPageFinished` 照常回调）。
        // 加载它再报错，等于让用户在白屏前多等一轮无意义的网络与解析。
        when (val verdict = WebViewPreflight.preflight(startupRecovery.versionName)) {
            is WebViewPreflight.Verdict.ShowDiagnostic -> {
                startupRecovery.showDiagnostic(verdict.reason, "WebView ${verdict.versionName} < ${WebViewPreflight.MIN_MAJOR}")
                PerfMarks.mark("J.g1.blockedOldWebview", "major=${verdict.major}")
                return
            }
            is WebViewPreflight.Verdict.LoadOfficialUi -> {
                // 读不到版本（unknownVersion=true）**照样放行**，只多挂一层运行期看门狗。
                PerfMarks.mark("J.g1.preflightOk", "major=${verdict.major} unknown=${verdict.unknownVersion}")
            }
        }

        Log.i(TAG, "home=$url uiOrigin=$uiOrigin")
        startupRecovery.arm()
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

    /**
     * 物理 **ESC 透传给 WebView**，绝不能变成 Android Back。
     *
     * v2.0.25 真机（Android 平板 + 物理键盘）：终端里按 ESC 退不出 Vim 的插入模式，
     * 反而整页跳回首页。原因是部分 OEM 键盘把 ESC 报成 `KEYCODE_BACK`，而
     * `ComponentActivity.dispatchKeyEvent` 会把 `KEYCODE_BACK` 交给
     * `onBackPressedDispatcher` ⇒ 命中 [BackNavigationHost] ⇒
     * `window.__ocAndroid.onBack()` ⇒ `frontend/android/back.ts` 的 `navigate("/")`。
     *
     * 三条路互不干扰：
     * 1. **真 ESC**（`KEYCODE_ESCAPE`）：原样 `super.dispatchKeyEvent` 交给
     *    WebView（xterm / Vim / OpenCode 自己的快捷键）。宿主**不消费**、不 finish。
     * 2. **被报成 BACK 的物理 ESC**：来源是键盘、且不是系统合成的虚拟硬键 ⇒
     *    还原成 `KEYCODE_ESCAPE` 再交给 WebView（见 [WebHostEscapePolicy]）。
     * 3. **导航栏 / 手势返回**：不是虚拟硬键就是压根不是 KeyEvent ⇒ 继续走
     *    [BackNavigationHost] → `__ocAndroid.onBack()` 的老路，行为不变。
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // 1) 真 ESC：交给 WebView，宿主不消费。
        if (WebHostEscapePolicy.isEscape(event.keyCode)) {
            return super.dispatchKeyEvent(event)
        }
        // 2) 物理键盘的 ESC 被报成了 BACK：还原成 ESC 再发给 WebView。
        val escaped = WebHostEscapePolicy.remappedBackToEscape(event)
        if (escaped != null) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "key: BACK from keyboard -> remapped to ESCAPE")
            }
            return super.dispatchKeyEvent(escaped)
        }
        return super.dispatchKeyEvent(event)
    }

    // ---- insets → 页面 ----------------------------------------------------
    private fun pushInsets(payload: JSONObject) {
        val json = payload.toString()
        evaluateJs("window.__ocAndroid && window.__ocAndroid.applyInsets($json);")
        systemUiTheme.applySystemBarTheme()
    }

    /** 系统栏图标明暗跟随页面 `data-color-scheme`（页面用 reportTheme 报上来）。 */
    override fun applyPageTheme(dark: Boolean) {
        systemUiTheme.applyPageTheme(dark)
    }

    /** 页面报上来的表面底色 → 视口压矮后露出的条带（见 InsetsHost.setSurfaceColor）。 */
    override fun applySurfaceColor(css: String?) {
        if (!::insets.isInitialized) return
        val color = SurfaceColor.parse(css) ?: return
        insets.setSurfaceColor(color)
    }

    override fun setImeMode(mode: ImeMode) {
        if (!::insets.isInitialized) return
        insets.setImeMode(mode)
    }

    // ---- 通知 ------------------------------------------------------------
    override fun requestNotificationPermission() {
        notificationPermissionHost.requestNotificationPermission()
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
        DebugEvalHost(
            debugEnabled = BuildConfig.DEBUG,
            extraName = EXTRA_DEBUG_EVAL,
            webViewProvider = { if (::webView.isInitialized) webView else null },
        ).consume(intent)
    }

    // ---- 杂项 ------------------------------------------------------------
    override fun dispatchToUiThread(action: () -> Unit) {
        runOnUiThread { action() }
    }

    override fun evaluateJs(js: String) {
        if (!::webView.isInitialized) return
        runCatching { webView.evaluateJavascript(js, null) }
            .onFailure { Log.w(TAG, "evaluateJavascript failed", it) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::webView.isInitialized) {
            outState.putString(STATE_URL, webView.url ?: bootUrl())
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
     *     必须让 SystemUiThemeHost 的兜底重新读当前 `uiMode`，
     *     等页面下一次 reportTheme 再精确跟随（页面若不再上报，兜底也正确）。
     *
     * 安全的重入性：`onResume` 本来就会 `insets.refresh()`，重复 refresh
     * 只会被 payload 去重挡掉，不会重复打扰页面。
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        systemUiTheme.onConfigurationChanged()
    }

    override fun onPause() {
        super.onPause()
        backgroundLifecycle.onPause()
    }

    /**
     * P7 · **首次观测到回合开始且在前台时，主动申请 `POST_NOTIFICATIONS`**。
     *
     * ## 为什么必须由宿主主动做（P6 判定的 D1 死锁）
     *
     * 上游唯一的申请点是 notify 闸门里的 `Notification.requestPermission()`
     * （`index-D2r3WEwY.js` 原文见报告 §0.3），而闸门**只挂终态帧**；终态帧要靠
     * 事件流活着才能到；事件流活着又需要权限先 granted 才能在后台投出通知 ——
     * 三者互相等待。P7 用 1a 打断了"终帧必丢"，再用这里打断"闸门从不评估"，
     * **死锁双向拆解**。
     *
     * 为什么选「用户刚发消息」这个时机：弹窗落在**用户刚发起一次交互**的上下文里，
     * 比"启动即弹"合理得多（后者既突兀又与用户心智无关）。
     *
     * 复用既有链：[requestNotificationPermission] → 同一个 `notificationPermission`
     * launcher（:238）→ 同一个结果回调（打日志 + 派 `oc:notifyPerm`）。**没有新造申请链。**
     * 上游闸门里那条 `requestPermission()` 路径保持不变（shim 会短路成 granted）。
     */
    override fun requestNotificationPermissionOnRoundStart() {
        notificationPermissionHost.requestNotificationPermissionOnRoundStart()
    }

    override fun onResume() {
        super.onResume()
        backgroundLifecycle.onResume()
    }

    private fun disposeWebView() {
        cameraPermissionHost.denyPending()
        if (::legacyChannel.isInitialized) legacyChannel.close()
        if (::platformHost.isInitialized) platformHost.close()
        if (!::webView.isInitialized) return
        startupRecovery.cancel()
        backgroundLifecycle.onWebViewDisposed()
        webView.removeJavascriptInterface(WebHostBridge.INJECT_NAME)
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
    }

    override fun onDestroy() {
        if (::fileChooser.isInitialized) fileChooser.close()
        // P6：销毁时也清一次（窗口虽马上就没了，但 flag 泄漏会在 `onSaveInstanceState`
        // 重建路径上被观察到；清掉是零成本的）。
        backgroundLifecycle.onDestroy()
        disposeWebView()
        super.onDestroy()
    }

    /** Page-ready effects owned by the composition root; the client boundary only forwards the event. */
    private fun onWebViewPageReady(view: WebView, url: String) {
        // 首屏 insets 补发：首次 publish 时页面还不存在，去重逻辑不会自动重发。
        if (::insets.isInitialized) {
            insets.replay()
            Log.i(TAG, "insets replayed after page finish: $url")
        }
        // 页面每次加载完成都会重装 Notification 垫片，必须按宿主真值重推权限。
        notificationPermissionHost.pushNotificationPermission()
        val dark = (view.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        if (dark) {
            Log.d(TAG, "page finished: $url")
        }
    }

    // ---- F3 / N-2: camera runtime permission for the pairing scanner -------

    private val cameraPermission: ActivityResultLauncher<String> = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        cameraPermissionHost.onRuntimePermissionResult(granted)
    }

    private fun openExternalLink(url: String) {
        try {
            platformHost.openExternal(url)
        } catch (error: Exception) {
            Log.e(TAG, "Cannot open external link", error)
            Toast.makeText(this, "无法打开链接：${error.message}", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * F3: 该 origin 是否是本宿主**自己**加载的页面。
     *
     * 仅信任成功绑定的 APK 静态服务器；后端网页与浏览器内的页面不能申请宿主相机。
     */
    private fun isTrustedWebOrigin(origin: String): Boolean {
        return localUiServer.boundPort != null && WebOrigins.sameOrigin(LocalUiOriginServer.origin(), origin)
    }

}
