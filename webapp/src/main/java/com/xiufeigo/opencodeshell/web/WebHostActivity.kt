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
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.xiufeigo.opencodeshell.web.host.FileChooserHost
import com.xiufeigo.opencodeshell.web.host.AndroidPlatformHost
import com.xiufeigo.opencodeshell.web.host.LegacyBridgeChannel
import com.xiufeigo.opencodeshell.web.host.ImeMode
import com.xiufeigo.opencodeshell.web.host.InsetsHost
import com.xiufeigo.opencodeshell.web.host.LocalUiOriginServer
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
 * APK 内的官方 UI 由 [LocalUiOriginServer] 提供，API 和终端直连用户选择的 OpenCode。
 * [WebViewAssetLoader] 仅提供启动失败时的诊断页。
 * - 边到边 + insets 交给 [InsetsHost] → CSS 变量 / JS 事件。
 * - 返回：页面关闭浮层、退出子页面或回首页；首页交回 Android 的默认返回行为。
 * 服务器凭据由官方 UI 管理。
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
         * UX14-P1：渲染进程崩溃后允许多少时间内的**第二次**崩溃还算"同一场事故"。
         *
         * 60 s 取的是"用户还来不及反应、也来不及手动重进"的长度：窗口内只放行
         * 第一次销毁并重建 WebView，再崩就用新实例呈现诊断页（防重启风暴，
         * 理由见 [renderCrashCount]）。出了窗口则计数清零，把新的崩溃当成独立事件
         * 再给一次自愈机会。
         */
        private const val RENDER_CRASH_RELOAD_WINDOW_MS = 60_000L

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
    private lateinit var localUiServer: LocalUiOriginServer

    private var pickedUri: Uri? = null
    private lateinit var platformHost: AndroidPlatformHost
    private lateinit var legacyChannel: LegacyBridgeChannel
    private var pendingPageScrollY: Int = 0
    private var pageReportedDark: Boolean? = null

    /**
     * UX14-P1：渲染进程崩溃的节流计数（配合 [RENDER_CRASH_RELOAD_WINDOW_MS]）。
     *
     * - [lastRenderCrashAtMs]：上一次崩溃时刻（`SystemClock.elapsedRealtime()`，0 = 还没崩过）
     * - [renderCrashCount]：该时刻之前的**连续**崩溃次数
     *
     * 为什么必须节流：崩溃的成因若是**页面本身**（超大 DOM、内存吃紧、某个
     * `postMessage` 触发的 OOM），那么"崩了就 reload"会变成
     * *reload → 起新渲染进程 → 同样输入 → 再崩* 的**无限重启风暴**，
     * 每次都真起一个 Chromium 渲染进程，很快把整机内存/电量吃干，用户看到的是
     * 疯狂闪烁而不是恢复。所以窗口内只放行**第一次** reload，第二次就认输、
     * 切诊断页（`RENDER_CRASH`），把可读信息交给用户而不是继续空转。
     *
     * 桌面端 Electron 崩溃会自动恢复；此处对齐那一半行为，但**不**无限对齐。
     */
    private var lastRenderCrashAtMs: Long = 0L
    private var renderCrashCount: Int = 0

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

    /**
     * P7 · 当前 `onPause` 是否走了「回合保活路径」。
     *
     * true = 切后台时有回合在进行 ⇒ **没派 pagehide**（SSE 仍活着）；
     * false = 走了空闲路径 ⇒ 已派 pagehide 断流，回前台需要 pageshow 重建。
     * 由 `onPause` 写、`onResume` 与 [backgroundRoundRelease] 读。
     */
    @Volatile
    private var backgroundKeepAlive: Boolean = false

    /**
     * P7 · 宿主是否已主动发起过一次 `POST_NOTIFICATIONS` 申请（进程内防抖）。
     *
     * 为什么要防抖：注入层传感器每个回合的 POST / 终态帧都会经
     * `WebHostBridge.setKeepScreenOn` 喂一次跟踪器；不防抖就是"每回合弹一次系统框"。
     * 用户拒绝后 Android 侧不再弹（系统自身记忆），这里的 flag 只是省掉重复 launch。
     */
    @Volatile
    private var notificationPermissionRequested: Boolean = false

    /**
     * P7 · 后台回合保活的收口轮询间隔（ms）。
     *
     * 取 2s：只在后台保活路径上排一个 runnable，作用仅是问一次
     * `isRoundActive`。回合结束的正常路径是**事件驱动**（传感器读到终态帧即
     * 毫秒级收口），这条轮询只是"传感器也失联了"的兜底。
     */
    private val BACKGROUND_ROUND_RELEASE_POLL_MS: Long = 2_000L

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
        // P9: push the grant result into the page-side Notification shim so its
        // static `permission` property stops reporting a stale "default".
        // The shim listens for `oc:notifyPerm` right next to
        // `window.Notification = ShimNotification` in assets/www/oc-host.js.
        evaluateJs(
            "window.dispatchEvent(new CustomEvent('oc:notifyPerm'," +
                "{detail:'${if (granted) "granted" else "denied"}'}));"
        )
        // 结果回给页面：onRequestResult 约定 {"notificationPermission":true|false}
        evaluateJs(
            "window.__ocAndroid && window.__ocAndroid.hostInfo &&" +
                "window.dispatchEvent(new CustomEvent('oc:permission'," +
                "{detail:{permission:'notifications',granted:$granted}}));"
        )
    }

    /**
     * P11 · 宿主侧通知权限真值（`"granted"` / `"denied"`）。
     *
     * 与 [WebHostBridge.hasNotificationPermission] 同源：API 33 之前没有运行时
     * 通知权限，一律视为已授权 —— 与 `requestNotificationPermission()` 里
     * "pre-13: no runtime permission needed" 那个分支保持一致。
     */
    private fun notificationPermissionState(): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return "granted"
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        return if (granted) "granted" else "denied"
    }

    /**
     * P11 · 把当前权限真值推给页面，复用 P9 已有的 `oc:notifyPerm` 通道。
     *
     * 事件名 / detail 取值与 P9 launcher 回调里那次派发**完全一致**，因此
     * `oc-host.js` 那个监听器一行都不用改。
     *
     * 非 granted 时推 `"denied"`：此时真值可能是"未授权"或"从未申请"，
     * 但对上游闸门而言两者等价（`Notification.permission !== "granted"`
     * ⇒ 直接 return），且真拿到授权后 launcher 回调会再推一次 `"granted"`，
     * 不会卡在错误状态。
     */
    private fun pushNotificationPermission() {
        val state = notificationPermissionState()
        Log.i(TAG, "P11 push oc:notifyPerm=$state")
        evaluateJs(
            "window.dispatchEvent(new CustomEvent('oc:notifyPerm'," +
                "{detail:'$state'}));"
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

        // UX11：官方前端由 App 自带（assets/www/ui），本机 http 静态来源只服务 UI 资源。
        // 🔴 这个端点不碰凭据、不代理 API —— 认证与 API 调用全部由页面直连用户配置的服务器。
        localUiServer = (application as WebHostApp).localUiServer.also { it.start() }
        PerfMarks.mark("B2.localUiOrigin", "bound=${localUiServer.boundPort}")

        pendingPageScrollY = savedInstanceState?.getInt(STATE_SCROLL_Y, 0) ?: 0
        // UX6：冷启动 = 直接加载真实服务器地址（官方前端由后端自托管）。
        // 恢复态的旧 URL 直接沿用（origin 现在是稳定的后端地址，不像代理端口每次必变）。
        val restored = savedInstanceState?.getString(STATE_URL)
        restoredUrlOrNull = restored

        setupWebViewAndLoad()

        installBackHandler()
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
     * - `installBackHandler()` 只在 [onCreate] 调一次：它注册的是
     *   `onBackPressedDispatcher` 回调（注册两次会多挂一个 callback），而回调体
     *   读的是 `webView` **字段**，重建后自动指向新实例。
     * - 焦点/IME 接线（`isFocusable` / `isFocusableInTouchMode` + ACTION_DOWN 监听）
     *   刻意写在 `apply {}` **实例块内**、而不是 `onCreate`：它与 WebView 实例强绑定，
     *   放在这里才能保证 [onRenderProcessGone] 崩溃重建后的新实例**同样**带这套焦点配置
     *   （放在方法外/字段上会在重建时漏挂，退化成"首启能用、崩一次就再也弹不出键盘"）。
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebViewAndLoad(diagnostic: Pair<WebViewPreflight.Reason, String>? = null) {
        webView = WebView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setBackgroundColor(0xFF080808.toInt())
            isVerticalScrollBarEnabled = false
            overScrollMode = WebView.OVER_SCROLL_NEVER

            // ---- 原生焦点 / IME 接线（最小修正）---------------------------------
            //
            // 现象（Android 15 / emulator-5636，真实 SPA + xterm，调试 APK）：
            // 原生 tap 后 DOM 侧 `textarea.inputFocused === true`，但 Gboard 不出；
            // `dumpsys input_method` 读到
            //   mServedView         = DecorView[WebHostActivity]  ← 服务视图是 DecorView，不是 WebView
            //   mServedInputConnection = null                      ← 根本没有 InputConnection
            // 且反复 `TYPE_SHOW STATUS_FAIL / SHOW_SOFT_INPUT / PHASE_CLIENT_VIEW_SERVED`。
            //
            // 判读：**DOM 焦点 ≠ native 焦点**。IME 由 ViewRootImpl 沿 `findFocus()`
            // 找服务视图；触屏模式下没有 descendant 持有焦点时它回落到 DecorView，
            // 而没有焦点就不会触发 `onCreateInputConnection` ⇒ `mServedInputConnection=null`
            // ⇒ 随后的 SHOW 请求必然 STATUS_FAIL（正是观测到的 PHASE_CLIENT_VIEW_SERVED）。
            // Chromium 侧 DOM 焦点是真的，但它下发给 WebView 的那次 native 焦点请求在
            // 触屏模式下不一定落到视图树上 ⇒ 于是出现"DOM 说聚焦了、原生说没有"的分裂。
            //
            // `isFocusable` / `isFocusableInTouchMode` 是 `View.requestFocus()` 的**平台前置条件**
            // （不满足则直接 return false、不做任何事），这里显式钉死，避免后续被任何
            // 主题/样式或重建路径改掉；真正把焦点抢下来的是下面那个 ACTION_DOWN 监听。
            isFocusable = true
            isFocusableInTouchMode = true

            // 触屏模式下框架不会因为 tap 自动转移焦点，所以必须由我们自己请求。
            // 为什么落在 ACTION_DOWN：原生 down 一定**先于** WebView 内部派发的 DOM focus，
            // 先在这里拿到 native focus，后面的 SHOW 才有一个正确的服务视图可挂 InputConnection。
            //
            // 返回 false（不消费）的理由：本监听**只**补一次焦点请求，不消费也不替换
            // WebView 既有的手势判定 —— 终端拖动选区、滚动、点击全部照旧走 WebView 自己的
            // onTouchEvent；也**不**因为"被点了"就自行弹键盘（键盘仍只由 DOM focus 决定）。
            // 因此它不是一个 click 处理器，不存在需要 `performClick()` 的可达性缺口。
            @SuppressLint("ClickableViewAccessibility")
            setOnTouchListener { v, event ->
                if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN && !v.isFocused) {
                    val got = v.requestFocus()
                    if (BuildConfig.DEBUG) {
                        Log.d(
                            TAG,
                            "focus: ACTION_DOWN requestFocus=$got focusable=${v.isFocusable} " +
                                "inTouchMode=${v.isFocusableInTouchMode}",
                        )
                    }
                }
                false
            }
        }
        setContentView(webView)
        PerfMarks.mark("C.webviewCreated+setContentView")

        configureSettings(webView.settings)
        if (::legacyChannel.isInitialized) legacyChannel.close()
        legacyChannel = LegacyBridgeChannel(bridge)
        webView.addJavascriptInterface(legacyChannel, WebHostBridge.INJECT_NAME)
        if (::platformHost.isInitialized) platformHost.close()
        platformHost = AndroidPlatformHost(this, fileChooser)
        if (localUiServer.boundPort != null && WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            platformHost.install(webView)
        }
        PerfMarks.mark("D.settingsConfigured")
        installFacade(webView)
        installPerfProbe(webView)
        installPreflightProbe(webView)
        PerfMarks.mark("E.facadeInstalled")
        // G1：版本读数必须在 [launch] 之前拿到 —— 前置检查要靠它决定加载官方 UI 还是诊断页。
        readWebViewVersion()
        webView.webViewClient = HostWebViewClient()
        webView.webChromeClient = HostWebChromeClient()
        webView.setDownloadListener { url, _, _, _, _ -> openExternalLink(url) }

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

        // insets 绑在实例上（InsetsHost 持 WebView 引用），所以重建时必须重挂一个。
        insets = InsetsHost(window, webView) { payload -> pushInsets(payload) }
        PerfMarks.mark("F.insetsReady")

        if (diagnostic == null) launch(bootUrl()) else showDiagnostic(diagnostic.first, diagnostic.second)
    }

    /**
     * UX6 · 本次启动要加载的后端地址。
     *
     * 唯一来源 = [SecretStore.KEY_BACKEND_URL]（**不是秘密**，只是"应用壳从哪个
     * 后端取官方 UI"的入口；默认 `http://127.0.0.1:4095`，靠 adb reverse 打通）。
     * 服务器与口令的**配置**不在宿主做 —— 那是官方 UI 「设置 → 服务器」的职责。
     */
    private fun bootUrl(): String =
        LocalUiOriginServer.origin()

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
        if (localUiServer.boundPort == null) {
            showDiagnostic(WebViewPreflight.Reason.LOAD_ERROR, "本地 UI 服务无法绑定端口 ${localUiServer.port}，请检查端口占用后重启应用")
            return
        }
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            showDiagnostic(WebViewPreflight.Reason.WEBVIEW_TOO_OLD, "当前 WebView 不支持平台消息和文档开始注入，请更新 Android System WebView")
            return
        }
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
        val origins = setOf(LocalUiOriginServer.origin())
        // 首帧深浅色：`ui/index.html` 的 preload 在页面脚本阶段就按
        // `prefers-color-scheme` 铺底色，而 WebView 的该媒体查询可能还停在旧值
        // ⇒ 深色冷启动会先闪一下浅色。`oc-host.js` 会用这个初值驱动它的深浅色桥
        // （见其中的 `applyHostScheme`），所以注入前先把当前 uiMode 真值递进去。
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val initialScheme = "window.__ocHostSchemeInitial=" + JSONObject.quote(if (night) "dark" else "light") + ";"
        val script = initialScheme + legacyChannel.script(origins) + runCatching { assets.open("www/oc-host.js").bufferedReader().use { it.readText() } }
            .getOrElse {
                Log.e(TAG, "cannot read www/oc-host.js", it)
                return
            }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(wv, script, origins)
            val diagnosticOrigins = setOf("https://$ASSET_DOMAIN")
            WebViewCompat.addDocumentStartJavaScript(wv, legacyChannel.script(diagnosticOrigins, diagnostic = true), diagnosticOrigins)
            Log.i(TAG, "facade registered as document-start script")
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

    // WebView history includes closed session tabs. Only the application decides
    // which surface Back leaves; at its root, let Android leave the Activity.
    private fun installBackHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            private var pending: WebView? = null

            private fun reportBackFailure(error: RuntimeException? = null) {
                Log.e(TAG, "back: page handler failed, see WebView console", error)
                Toast.makeText(this@WebHostActivity, "返回处理失败，请查看 WebView 日志", Toast.LENGTH_SHORT).show()
            }

            override fun handleOnBackPressed() {
                val current = webView
                if (pending === current) return
                pending = current
                try {
                    current.evaluateJavascript(
                        "(function(){" +
                            "return !!(window.__ocAndroid && window.__ocAndroid.onBack && window.__ocAndroid.onBack());" +
                            "})()"
                    ) { result ->
                        if (pending !== current) return@evaluateJavascript
                        pending = null
                        if (current !== webView || isFinishing || isDestroyed) return@evaluateJavascript
                        val pageHandled = when (result?.trim()) {
                            "true" -> true
                            "false" -> false
                            else -> {
                                reportBackFailure()
                                return@evaluateJavascript
                            }
                        }
                        if (pageHandled) {
                            Log.i(TAG, "back: page handled current surface")
                            return@evaluateJavascript
                        }
                        Log.i(TAG, "back: application root -> Android default")
                        isEnabled = false
                        try {
                            onBackPressedDispatcher.onBackPressed()
                        } finally {
                            isEnabled = true
                        }
                    }
                } catch (error: RuntimeException) {
                    if (pending === current) pending = null
                    reportBackFailure(error)
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
        //
        // 🔴 P7 · **回合感知**：这条断流不再是"无条件"的。
        //
        // P6 钉死的死锁链（`research/web/UX13_P6_NOTIFY_ROOTCAUSE.md`）：
        //   onPause → 合成 pagehide → 上游 pageLifecycle 的 pagehide 监听
        //         → w() → AbortController.abort() → 4095 的在途 SSE 被掐断
        //   ⇒ 后台期间后端发的 `session.execution.succeeded`（HOME 后 ~2s 才到）必丢
        //   ⇒ 上游 notify 闸门（只挂终态帧）从不评估 ⇒ `Notification.requestPermission()`
        //     从不触发 ⇒ 宿主 POST_NOTIFICATIONS launcher 从不弹窗 ⇒ 一条通知都没有。
        //
        // 读 bundle 得到的**关键设计事实**（本轮只读，未改 ui/ 一字节）：
        // `runtime-DVG-cmAP.js` 的 pageLifecycle 只注册四个监听器（原文见报告 §0）：
        //   pagehide        → w() = abort（**唯一**会掐断 SSE 的路径）
        //   pageshow        → C() = 重连（已连接时幂等返回）
        //   visibilitychange→ 仅在 `document.visibilityState==='visible'` 时可能触发 resync
        //   online          → resync
        // **它不监听 focus / blur。** ⇒ 后台派 blur 对上游连接状态机是**完全惰性**的：
        // 语义上"未聚焦"照常传给页面上层 UI，但 SSE 一滴不流。
        //
        // 所以回合进行中时：**只派 blur，不派 pagehide** ⇒ SSE 活着 ⇒ 终帧能到 ⇒
        // notify 闸门被评估 ⇒ 通知能投出；回合结束后由 [backgroundRoundRelease] 补派
        // pagehide 收口（省电语义不丢）。空闲后台维持原样（UX8 已验收项不变）。
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
        // P10 · 切后台 ⇒ 把页面的"可见/聚焦"**读数**改成 hidden / false。
        //
        // 🔴 P13 · **这一句必须排在 `webView.onPause()` 之前**。
        //
        // 修之前它在本方法**最后**一行，而 `webView.onPause()` 在它前面：
        // WebView 一旦被 `onPause()` 暂停，JS 线程被节流，随后这条 evaluateJavascript
        // 不会立刻执行。P13 设备实测（emulator-6790，冷启重进，ping→HOME）：
        //   12:09:49.454  P7 onPause（Android onPause 落地）
        //   12:09:52.222  页面 visibilityState 读数才第一次变成 "hidden"   ← 迟 2.768 s
        // 这 2.768 s 里页面仍向页面上层报告 `visibilityState === "visible"`，
        // 而上游的 focus 管理器（P13 在 bundle `build-CA4aPxfP.js` 实读到
        // `isFocused(){ ... : document?.visibilityState !== 'hidden' }`）
        // 正是拿 visibilityState 当**兜底真值** ⇒ 这 2.768 s 内它认为"仍在前台"，
        // notify 闸门若在此刻被评估就直接 return，通知不出。
        //
        // P13 同一次运行的实测时序（证明闸门就是在 vs 翻 hidden 之后才开的）：
        //   12:09:51.239  round end session.execution.succeeded（后台收到终帧）
        //   12:09:52.222  visibilityState → "hidden"（本条 evaluateJs 终于生效）
        //   12:09:52.232  Notification when=1791288592232（channel=oc_turns）  ← 仅迟 10 ms
        // ⇒ 终帧比"闸门可开"早了 0.98 s；本轮是**险胜**，这正是 P12 §5 偶发丢通知的宿主侧成因。
        //
        // 修法 = 只调换顺序，不改语义：读数仍只由 `__ocSetBackground` 改、仍不派任何事件
        // （派 visibilitychange 会触发上游 resync → abort，掐死保活路径的 SSE，P7 已证）。
        evaluateJs("window.__ocSetBackground && window.__ocSetBackground(true)")
        if (::webView.isInitialized) webView.onPause()
    }

    /**
     * P7 · **后台回合保活的省电收口**：回合在后台期间结束 ⇒ 补派 `pagehide` 断 SSE。
     *
     * 为什么需要它：保活路径下 onPause 没有派 pagehide，若没有这条兜底，
     * 「回合结束后继续挂一条 4095 长连接」会一直挂到用户回前台 —— 省电语义就丢了。
     *
     * 回合结束有两条到达路径，两条都收口：
     *  1. 注入层传感器在**后台**读到 `session.execution.succeeded/failed` → 经
     *     `WebHostBridge.setKeepScreenOn(false)` → `onRoundEnd()`；
     *  2. 这条 90s 空闲兜底轮询（回合崩溃 / 渲染进程被杀 / 传感器被撤时兜底）。
     *
     * 轮询间隔 [BACKGROUND_ROUND_RELEASE_POLL_MS] 取 2s：只为了在**后台**问一次
     * `isRoundActive`，开销可忽略；真正的精度来源是路径 1（事件驱动，毫秒级）。
     */
    // ⚠ 类型必须显式写：`Runnable {}` 内部要把自己重新 post 回去（自引用），
    //   交给 Kotlin 推断会撞上 "recursive problem" 编译错。
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
     * launcher（:238）→ 同一个结果回调（打日志 + 派 `oc:permission`）。**没有新造申请链。**
     * 上游闸门里那条 `requestPermission()` 路径保持不变（shim 会短路成 granted）。
     */
    fun requestNotificationPermissionOnRoundStart() {
        if (!isForeground()) return
        if (notificationPermissionRequested) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionRequested = true
            return
        }
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            notificationPermissionRequested = true
            return
        }
        notificationPermissionRequested = true
        Log.i(TAG, "P7 proactive POST_NOTIFICATIONS request on first round start (foreground)")
        requestNotificationPermission()
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
            //
            // P7 · **对称**：若上一次的 onPause 走的是"回合保活路径"（没断过流），
            // 这里补 `focus` 而不是 `pageshow` —— `pageshow` 的语义是"页面刚从
            // bfcache 恢复"，会触发一次 `start()`；虽然上游幂等不会双连，但在
            // 没有断过流的前提下派它属于撒谎。`focus` 才是保活路径的对偶事件，
            // 且上游 pageLifecycle **不监听 focus**（只认 pagehide/pageshow），
            // 所以它同样不会动 SSE 状态机 —— 纯粹是把"回到前台"这个事实还回页面。
            if (backgroundKeepAlive) {
                backgroundKeepAlive = false
                dispatchPageLifecycleEvent("focus")
            } else {
                dispatchPageLifecycleEvent("pageshow")
            }
            // P10 · 回前台 ⇒ 恢复 visible / true，让页面读数与真实前台状态一致。
            //
            // 与 onPause 的 `__ocSetBackground(true)` 严格对偶；同样只改读数、
            // 不派发事件。放在 `isInitialized` 判空之内（evaluateJs 自身也再兜一层）。
            evaluateJs("window.__ocSetBackground && window.__ocSetBackground(false)")
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

    private fun disposeWebView() {
        pendingCameraPermission?.deny()
        pendingCameraPermission = null
        if (::legacyChannel.isInitialized) legacyChannel.close()
        if (::platformHost.isInitialized) platformHost.close()
        if (!::webView.isInitialized) return
        webView.removeCallbacks(watchdog)
        webView.removeCallbacks(keepScreenOnBackstop)
        webView.removeJavascriptInterface(WebHostBridge.INJECT_NAME)
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
    }

    override fun onDestroy() {
        if (::fileChooser.isInitialized) fileChooser.close()
        // P6：销毁时也清一次（窗口虽马上就没了，但 flag 泄漏会在 `onSaveInstanceState`
        // 重建路径上被观察到；清掉是零成本的）。
        clearKeepScreenOnOnBackground()
        disposeWebView()
        super.onDestroy()
    }

    // ---- WebViewClient ----------------------------------------------------
    // G1：必须是 `inner` —— 运行期兜底要读 Activity 的 `watchdogArmed` 并调 `showDiagnostic`。
    private inner open class HostWebViewClient : WebViewClient() {
        private var assetLoader: WebViewAssetLoader? = null

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (!request.isForMainFrame) return false
            val url = request.url.toString()
            if (sameOrigin(url, LocalUiOriginServer.origin())) return false
            openExternalLink(url)
            return true
        }

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
         * UX14-P1：**接线**渲染进程崩溃自愈 —— 关闭 HANDOFF §5 第 11 条未闭环项。
         *
         * 此前这里只有一个 `onRenderProcessGoneNotWired()` 的空壳 + 一段注释，
         * 声称"本模块编译期解析不到 `WebViewRenderProcessDetail`"。那个结论是
         * **错的**（写错了类名）：平台类型叫 `android.webkit.RenderProcessGoneDetail`
         * —— 已在本机 `android-35/android.jar` 内确认存在该 class
         * （`add-type` 打开 zip 目录实查，非记忆）。`compileSdk = 35` / `minSdk = 26`
         * 都满足 API 26 门槛。历史备份里的报错原文见
         * `research/env/d1_revert_backup/WebHostActivity.kt.bak:971-984`。
         *
         * 为什么必须接：WebView 渲染进程崩溃是"白屏死"的**唯一常见成因**，而默认行为
         * 是 WebView 直接 **finish 掉整个 Activity** —— 用户唯一出路是杀进程重启，
         * 正在跑的回合（SSE）全丢。桌面端 Electron 渲染进程崩溃会自动恢复，
         * 这是功能一致性缺口。
         *
         * 行为（见 [RENDER_CRASH_RELOAD_WINDOW_MS] 与 [renderCrashCount]）：
         * 1. 窗口内第 1 次崩溃 ⇒ **销毁旧 WebView 实例并新建一个**（[setupWebViewAndLoad]），
         *    重新从 4173 本地源加载；会话与服务器状态在 `localStorage`，恢复后与桌面端一致。
         * 2. 窗口内再次崩溃 ⇒ 判定为**崩溃循环**（多半是页面自身把渲染进程打崩，或这台
         *    设备的 WebView 本身有问题），不再重建，切 [showDiagnostic] 并传
         *    [WebViewPreflight.Reason.RENDER_CRASH] —— 这正好把那个"有枚举、没人用"
         *    的分支接上。
         * 3. `return true` = **消费**该事件：宿主已接管，WebView 不再按默认行为
         *    finish Activity。**这里刻意不调 super**（super 的实现就是 return false，
         *    即"不处理"⇒ 走默认销毁路径）。
         *
         * ⚠️ 为什么第 1 次崩溃**不能**只 `reload()`（UX16-P1 CDP 真触发实证，批 #9 已闭环）：
         * 在本镜像上 `reload()` 之后渲染进程**必然再崩**，两次独立复现的间隔分别是
         * 6.55s / 6.56s，页面根本没起来。**两次崩溃总会被节流接住**，所以从日志表面看
         * "进了诊断页、一切正常"，实际用户拿到的是崩溃兜底页而非自动恢复的会话 ——
         * **节流的成功掩盖了自愈的失败**。这与平台口径一致：渲染进程消失后该实例
         * 不应再被使用。重建后若仍然崩溃，第 2 次崩溃会被节流挡下并落到诊断页，
         * **不会**无限重启。
         */
        override fun onRenderProcessGone(
            view: WebView,
            detail: RenderProcessGoneDetail,
        ): Boolean {
            if (!::webView.isInitialized || view !== webView) return true
            val now = SystemClock.elapsedRealtime()
            // 崩溃链的第一个观测点：先落日志再决策，日志要能独立判断"崩了还是被杀"。
            Log.w(
                TAG,
                "UX14 render process gone didCrash=${detail.didCrash()} " +
                    "priorityAtExit=${detail.rendererPriorityAtExit()}",
            )
            PerfMarks.mark(
                "N.onRenderProcessGone",
                "didCrash=${detail.didCrash()} count=$renderCrashCount",
            )

            val withinWindow = lastRenderCrashAtMs > 0L &&
                (now - lastRenderCrashAtMs) < RENDER_CRASH_RELOAD_WINDOW_MS
            // 出了窗口 = 上一场事故已结束，计数清零，重新给一次自愈机会。
            val crashesInWindow = if (withinWindow) renderCrashCount + 1 else 1
            lastRenderCrashAtMs = now
            renderCrashCount = crashesInWindow

            // webView 未初始化时（例如崩溃发生在 onCreate 完成前）没有可重载的对象。
            if (!::webView.isInitialized) {
                Log.w(TAG, "UX14 render crash before webView init; consuming event only")
                return true
            }

            if (crashesInWindow >= 2) {
                // 崩溃循环：认输，转诊断页，不再 reload（防重启风暴）。
                Log.w(TAG, "UX14 render crash loop count=$crashesInWindow within=${RENDER_CRASH_RELOAD_WINDOW_MS}ms")
                disposeWebView()
                setupWebViewAndLoad(WebViewPreflight.Reason.RENDER_CRASH to
                    "didCrash=${detail.didCrash()} crashes=$crashesInWindow windowMs=$RENDER_CRASH_RELOAD_WINDOW_MS")
            } else {
                // UX16-P2：窗口内首崩 ⇒ **销毁旧实例 + 新建**，不再 `reload()`。
                //
                // 为什么不再 reload（UX16-P1 真触发实证，结论已闭环）：本镜像上
                // `reload()` 之后渲染进程**必然再次崩溃**，稳定在 ~6.5s，
                // 端到端"恢复可用"从来不成立 —— 节流只是把第二次崩溃接住、
                // 落诊断页，把"自愈失败"伪装成了"一切正常"。
                // 平台口径也正是如此：渲染进程消失后该 WebView 实例**不应再被使用**。
                Log.i(TAG, "UX16 render crash; recreating WebView (window=${RENDER_CRASH_RELOAD_WINDOW_MS}ms)")
                // 拆桥 → 出视图树 → 销毁，顺序与 [onDestroy] 一致。
                // destroy() 之后这个实例的任何调用都是未定义行为，必须彻底摘干净。
                disposeWebView()
                // 新实例走**完整**初始化链（bridge 注入 / doc-start 脚本 / client /
                // insets / loadUrl(bootUrl)），否则页面会安静地半残。
                setupWebViewAndLoad()
            }
            // true = 消费：阻止 WebView 默认 finish 掉整个 Activity。不调 super。
            return true
        }

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
            // P11 · 权限真值重推：页面每次加载完成（首载 / 点通知把 Activity 拉起后
            // 的重载 / 手动刷新）都会重装 oc-host.js 垫片，静态
            // `Notification.permission` 随之回落成 "default"；而上游闸门是**先读
            // 属性**（读到 != "granted" 就 return，闸门自身不会去调 requestPermission）
            // ⇒ 不重推就会静默失效到下一次宿主主动推送。此刻垫片已装、监听器已在，
            // 按宿主真值补推一次即可关掉这个窗口。
            pushNotificationPermission()
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
            if (cameraPermissionInFlight) {
                Log.w(TAG, "camera permission request already pending")
                request.deny()
                return
            }
            pendingCameraPermission = request
            cameraPermissionInFlight = true
            cameraPermission.launch(Manifest.permission.CAMERA)
        }

        override fun onPermissionRequestCanceled(request: PermissionRequest) {
            if (pendingCameraPermission === request) pendingCameraPermission = null
        }

        override fun onConsoleMessage(m: ConsoleMessage): Boolean {
            Log.i(TAG, "web[${m.messageLevel()}] ${m.message()} @${m.lineNumber()}")
            return true
        }
    }

    // ---- F3 / N-2: camera runtime permission for the pairing scanner -------

    private var pendingCameraPermission: PermissionRequest? = null
    private var cameraPermissionInFlight = false

    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        cameraPermissionInFlight = false
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
        return localUiServer.boundPort != null && sameOrigin(LocalUiOriginServer.origin(), origin)
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
