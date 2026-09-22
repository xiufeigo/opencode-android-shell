package com.xiufeigo.opencodeshell

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.HttpAuthHandler
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.xiufeigo.opencodeshell.databinding.ActivityMainBinding
import java.io.ByteArrayInputStream
import kotlin.math.max

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var assetLoader: WebViewAssetLoader
    private lateinit var gateway: LocalGateway
    private lateinit var webSockets: NativeWebSocketHub
    private lateinit var serverConfig: ServerConfigStore
    private lateinit var chatViewModel: ChatViewModel
    private lateinit var chatView: ChatView
    // Which bottom-nav tab is visible. Chat is the default so the shell boots
    // into native chat and only pays WebView cost when the terminal opens.
    private var currentTab: Int = R.id.nav_chat
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var backInFlight = false
    private var webColorSchemeApplied = false
    private var lastConfigToastAt = 0L
    // Native server config snapshot taken before the WebView boots; the
    // document-start sync can overwrite the store with the WebView's stale
    // value before onPageFinished, so boot convergence compares against this.
    private var bootServerJson: String? = null
    private var serverConverged = false
    // Last root padding applied by applyImeAwarePadding. Compared on all four
    // sides (rotation changes left/right) so identical insets never re-layout.
    private var lastPaddingLeft = -1
    private var lastPaddingTop = -1
    private var lastPaddingRight = -1
    private var lastPaddingBottom = -1
    // Last observed IME visibility. notifyWebViewImeHidden fires only on the
    // visible -> hidden edge so onEnd + onWindowFocusChanged can't double-fire.
    private var imeWasVisible = false

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val uris = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
        filePathCallback?.onReceiveValue(uris)
        filePathCallback = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        gateway = LocalGateway()
        gateway.start()

        // Native chat stack. The WebView stays the source of truth for the
        // server config; it pushes values through the bridge (thread-safe
        // inside ServerConfigStore) so the direct client never needs a peek.
        serverConfig = ServerConfigStore(this) { json ->
            runOnUiThread {
                val escaped = JSONObject.quote(json)
                if (::binding.isInitialized) {
                    binding.webView.evaluateJavascript("window.__ocSetServerConfig && window.__ocSetServerConfig($escaped);", null)
                }
            }
        }
        serverConfig.restore()
        // Captured before the WebView boots: the document-start sync may push
        // the WebView's stale localStorage value over the native one before we
        // can compare, so convergence at first page-finish uses this snapshot.
        bootServerJson = serverConfig.rawJson()
        // The sync JS retries for ~6s after page load; suppress duplicate
        // "no config" toasts within that window (showChatTab + onStart both
        // kick the model on cold boot).
        chatViewModel = ChatViewModel(serverConfig) {
            val now = System.currentTimeMillis()
            if (now - lastConfigToastAt > CONFIG_TOAST_THROTTLE_MS) {
                lastConfigToastAt = now
                Toast.makeText(this, R.string.chat_need_config, Toast.LENGTH_SHORT).show()
            }
        }
        chatView = ChatView(this, chatViewModel)
        binding.chatContainer.addView(chatView)

        // Do not hide system bars. HyperOS immersive overlays have been observed
        // swallowing every subsequent touch while chrome still paints.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        padWebViewForCutout()
        configureWebView()

        binding.errorRetry.setOnClickListener { loadEmbeddedFrontend() }

        val backCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleAndroidBack(this)
            }
        }
        onBackPressedDispatcher.addCallback(this, backCallback)

        // bottom_nav is the only tab chrome: every switch funnels through
        // showChatTab/showTerminalTab so the indicator and the visible
        // container can never desync with currentTab.
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_chat -> {
                    showChatTab()
                    true
                }

                R.id.nav_terminal -> {
                    showTerminalTab()
                    true
                }

                else -> false
            }
        }
        // Reselecting the visible tab must not restart anything.
        binding.bottomNav.setOnItemReselectedListener { }
        // Boot state: chat visible, WebView parked. The layout marks
        // chat_container gone so the first showChatTab() makes it authoritative.
        showChatTab()

        loadEmbeddedFrontend()
    }

    override fun onDestroy() {
        if (::chatViewModel.isInitialized) chatViewModel.destroy()
        if (::webSockets.isInitialized) webSockets.closeAll()
        gateway.close()
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        // Symmetric with gateway.pause() in onStop; without this the proxy
        // would keep dropping sockets forever after the first backgrounding.
        gateway.resume()
        // Chat SSE reconnects here after onStop closed it. Safe even before
        // first window attach: androidx.activity pins the ViewTree owners to
        // the decor view inside setContentView.
        if (currentTab == R.id.nav_chat) chatView.start()
        // The WebView renderer only wakes on the terminal tab. On chat it must
        // stay parked (renderer + timers paused) or the battery trio in onStop
        // is undone the moment the user returns (review MUST-FIX #3).
        if (currentTab == R.id.nav_terminal) {
            binding.webView.onResume()
            binding.webView.resumeTimers()
        }
    }

    override fun onStop() {
        // Battery trio: native SSE down, WebView renderer idle, proxy asleep.
        // Existing gateway streams are not killed; EventStream closes itself.
        chatView.stop()
        binding.webView.onPause()
        binding.webView.pauseTimers()
        gateway.pause()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        // The WebView renderer only resumes on the terminal tab; chat keeps it
        // parked so the native screen costs nothing while visible.
        if (currentTab == R.id.nav_terminal) {
            binding.webView.onResume()
            binding.webView.resumeTimers()
            binding.webView.requestFocus()
        }
    }

    /**
     * Keyboard events, including ESC, default to the focused WebView so OpenCode
     * (and xterm/Vim) receive them. The shell does **not** register an app-wide ESC
     * shortcut, does not map ESC to Back, and never leaves the process on ESCAPE.
     * Closing Settings is Android Back ([handleAndroidBack]), not ESC.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        super.dispatchKeyEvent(event)

    private fun handleAndroidBack(callback: OnBackPressedCallback) {
        if (backInFlight) return
        // Native chat owns this tab and the WebView may even be paused here,
        // so back exits directly instead of routing through JS. A showing
        // mention popup just collapses first (consumed).
        if (currentTab == R.id.nav_chat) {
            if (chatView.dismissMentionIfShowing()) return
            callback.isEnabled = false
            onBackPressedDispatcher.onBackPressed()
            callback.isEnabled = true
            return
        }
        backInFlight = true
        binding.webView.requestFocus()
        binding.webView.evaluateJavascript(AndroidBackJs.INVOKE) { raw ->
            backInFlight = false
            val result = AndroidBackJs.parse(raw)
            Log.i(LOG_TAG, "back js=$result canGoBack=${binding.webView.canGoBack()}")
            when {
                result == AndroidBackJs.OVERLAY || result == AndroidBackJs.NAV -> Unit
                binding.webView.canGoBack() -> binding.webView.goBack()
                else -> {
                    callback.isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    callback.isEnabled = true
                }
            }
        }
    }

    private fun showChatTab() {
        currentTab = R.id.nav_chat
        binding.chatContainer.visibility = View.VISIBLE
        binding.webContainer.visibility = View.GONE
        // Park the renderer (state preserved) while native chat is on screen;
        // the terminal tab reverses this.
        binding.webView.onPause()
        binding.webView.pauseTimers()
        // chatView.start() is fully idempotent: the collector is keyed by an
        // active Job check and the model retries cleanly when the server config
        // arrived via the bridge in the meantime.
        chatView.start()
    }

    private fun showTerminalTab() {
        currentTab = R.id.nav_terminal
        binding.chatContainer.visibility = View.GONE
        binding.webContainer.visibility = View.VISIBLE
        // Symmetric with showChatTab: the native stream stops while the
        // WebView is the visible surface (battery), reconnects on return.
        chatView.stop()
        binding.webView.onResume()
        binding.webView.resumeTimers()
        binding.webView.requestFocus()
    }

    private fun loadEmbeddedFrontend() {
        hideError()
        // File URL must stay under /assets/opencode-app/ so relative Vite assets
        // resolve. Solid routing uses Desktop's MemoryRouter (not this pathname).
        binding.webView.loadUrl(EMBEDDED_INDEX_URL)
    }

    private fun padWebViewForCutout() {
        // Pad the root, not the WebView. WebView ignores view padding on many
        // OEM builds (including HyperOS), which previously drew titlebar under
        // the status bar.
        //
        // Edge-to-edge (setDecorFitsSystemWindows false) makes windowSoftInputMode
        // adjustResize a no-op. Include IME insets so the FrameLayout shrinks and
        // 「添加服务器」fields stay above the keyboard.
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            applyImeAwarePadding(insets, force = true)
            WindowInsetsCompat.CONSUMED
        }
        ViewCompat.setWindowInsetsAnimationCallback(
            binding.root,
            object : WindowInsetsAnimationCompat.Callback(
                WindowInsetsAnimationCompat.Callback.DISPATCH_MODE_CONTINUE_ON_SUBTREE,
            ) {
                override fun onProgress(
                    insets: WindowInsetsCompat,
                    runningAnimations: MutableList<WindowInsetsAnimationCompat>,
                ): WindowInsetsCompat {
                    applyImeAwarePadding(insets, force = false)
                    return insets
                }

                override fun onEnd(animation: WindowInsetsAnimationCompat) {
                    ViewCompat.getRootWindowInsets(binding.root)?.let {
                        applyImeAwarePadding(it, force = true)
                    } ?: ViewCompat.requestApplyInsets(binding.root)
                    if (animation.typeMask and WindowInsetsCompat.Type.ime() == 0) return
                    val visible = ViewCompat.getRootWindowInsets(binding.root)
                        ?.isVisible(WindowInsetsCompat.Type.ime()) == true
                    handleImeVisibility(visible)
                }
            },
        )
        ViewCompat.requestApplyInsets(binding.root)
        applySystemUi(systemNightMode())
    }

    private fun applyImeAwarePadding(insets: WindowInsetsCompat, force: Boolean = false) {
        val bars = insets.getInsets(
            WindowInsetsCompat.Type.statusBars() or
                WindowInsetsCompat.Type.navigationBars() or
                WindowInsetsCompat.Type.displayCutout(),
        )
        val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
        val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
        val bottom = imeAwareBottomPadding(bars.bottom, ime.bottom, imeVisible)
        if (bars.left == lastPaddingLeft && bars.top == lastPaddingTop &&
            bars.right == lastPaddingRight && bottom == lastPaddingBottom
        ) {
            return
        }
        // 2px jitter gate only for per-frame onProgress. onApply/onEnd use
        // force=true for exact calibration (tail frame, gesture keyboard,
        // hardware keyboard never hit onEnd).
        if (!force && bars.left == lastPaddingLeft && bars.top == lastPaddingTop &&
            bars.right == lastPaddingRight && kotlin.math.abs(bottom - lastPaddingBottom) <= 2
        ) {
            return
        }
        lastPaddingLeft = bars.left
        lastPaddingTop = bars.top
        lastPaddingRight = bars.right
        lastPaddingBottom = bottom
        binding.root.updatePadding(
            left = bars.left,
            top = bars.top,
            right = bars.right,
            bottom = bottom,
        )
    }

    private fun handleImeVisibility(visible: Boolean) {
        val was = imeWasVisible
        imeWasVisible = visible
        if (was && !visible) notifyWebViewImeHidden()
    }

    private fun notifyWebViewImeHidden() {
        if (!::binding.isInitialized) return
        binding.webView.evaluateJavascript(IME_RESTORE_JS, null)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            ViewCompat.requestApplyInsets(binding.root)
            val visible = ViewCompat.getRootWindowInsets(binding.root)
                ?.isVisible(WindowInsetsCompat.Type.ime()) == true
            handleImeVisibility(visible)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Re-sync edge state without notifying: rotation/fold can change IME
        // visibility out from under the cached flag.
        imeWasVisible = ViewCompat.getRootWindowInsets(binding.root)
            ?.isVisible(WindowInsetsCompat.Type.ime()) == true
        // Until the WebView reports its own color-scheme, follow system night.
        if (!webColorSchemeApplied) {
            applySystemUi(systemNightMode())
        }
    }

    private fun systemNightMode(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    @Suppress("DEPRECATION")
    private fun applySystemUi(dark: Boolean) {
        val bg = SystemUiAppearance.backgroundColor(dark)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 28) {
            window.navigationBarDividerColor = Color.TRANSPARENT
        }
        if (Build.VERSION.SDK_INT >= 29) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        window.setBackgroundDrawable(ColorDrawable(bg))
        binding.root.setBackgroundColor(bg)
        if (::binding.isInitialized) {
            binding.webView.setBackgroundColor(bg)
        }
        val controller = WindowInsetsControllerCompat(window, binding.root)
        val lightBars = SystemUiAppearance.appearanceLightBars(dark)
        controller.isAppearanceLightStatusBars = lightBars
        controller.isAppearanceLightNavigationBars = lightBars
    }

    /**
     * One-time boot convergence between the two server-config stores.
     *
     * The WebView's localStorage and the native prefs mirror both accept
     * writes, so after native Settings edits (which happen in [OcActivity],
     * outside this Activity) the WebView may still hold a stale list. At the
     * first page-finish we compare the WebView's value against the pre-boot
     * native snapshot; if they differ, the native value wins: it is written
     * into the WebView and the page reloads once so the upstream store
     * re-hydrates from localStorage. `serverConverged` also prevents the
     * reload from looping.
     */
    private fun convergeServerConfigOnce(view: WebView) {
        if (serverConverged) return
        val nativeJson = bootServerJson ?: return
        serverConverged = true
        view.evaluateJavascript(
            "(function(){try{return localStorage.getItem('opencode.global.dat:server')||''}catch(e){return ''}})()",
        ) { raw ->
            val webJson = try {
                raw?.let { org.json.JSONTokener(it).nextValue() as? String }
            } catch (_: Exception) {
                null
            }
            if (webJson != nativeJson) {
                val escaped = JSONObject.quote(nativeJson)
                view.evaluateJavascript(
                    "window.__ocSetServerConfig && window.__ocSetServerConfig($escaped);",
                    null,
                )
                view.reload()
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun configureWebView() {
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(binding.webView, true)

        webSockets = NativeWebSocketHub(binding.webView)
        binding.webView.addJavascriptInterface(
            OpenCodeAndroidBridge(
                gateway,
                webSockets,
                onColorScheme = { dark ->
                    webColorSchemeApplied = true
                    runOnUiThread { applySystemUi(dark) }
                },
                // Fires on a JS bridge thread; ServerConfigStore is volatile +
                // prefs-backed, so no UI hop is needed. notify=false: the value
                // came *from* the WebView, do not echo it back.
                onServerConfig = { json -> serverConfig.updateFromJson(json, notify = false) },
            ),
            "OpenCodeAndroid",
        )
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            val origins = setOf(ANDROID_WEBVIEW_ORIGIN, "http://$ASSET_HOST")
            WebViewCompat.addDocumentStartJavaScript(binding.webView, NativeWebSocketJs.INSTALL, origins)
            WebViewCompat.addDocumentStartJavaScript(binding.webView, SystemBarsJs.INSTALL, origins)
            WebViewCompat.addDocumentStartJavaScript(binding.webView, ServerConfigSyncJs.INSTALL, origins)
        }

        binding.webView.isFocusable = true
        binding.webView.isFocusableInTouchMode = true
        binding.webView.isClickable = true
        binding.webView.isLongClickable = true
        binding.webView.overScrollMode = View.OVER_SCROLL_NEVER
        // Do NOT consume long-press: Chromium text selection / ActionMode
        // (copy/paste/select) is entered from View.performLongClick(), which
        // only runs when no OnLongClickListener swallows the event.
        // isLongClickable must stay true, otherwise the framework never posts
        // the long-press check and selection dies with it.
        binding.webView.setOnTouchListener { view, event ->
            if (event.action == MotionEvent.ACTION_DOWN && !view.hasFocus()) {
                view.requestFocus()
            }
            false
        }

        binding.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            javaScriptCanOpenWindowsAutomatically = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = false
            displayZoomControls = false
            setSupportZoom(false)
            allowFileAccess = false
            allowContentAccess = true
            cacheMode = WebSettings.LOAD_DEFAULT
            setSupportMultipleWindows(true)
            setNeedInitialFocus(true)
        }

        binding.webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ): WebResourceResponse? {
                // Only the zero-server offline stub is synthesized. Remote HTTPS
                // (including the user's OpenCode backend) must hit the network so
                // cookies, redirects, and TLS use the system/user trust store.
                disconnectedStub(request.url)?.let { return it }
                if (isLocalGateway(request.url)) return null
                return assetLoader.shouldInterceptRequest(request.url)
            }

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean {
                val uri = request.url
                if (uri.host == ASSET_HOST) return false
                if (isLocalGateway(uri)) return false
                val scheme = uri.scheme?.lowercase()
                if (scheme == "blob" || scheme == "data" || scheme == "about") return false
                if (scheme != "http" && scheme != "https") return true
                if (request.isForMainFrame) {
                    openExternal(uri)
                    return true
                }
                return false
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                binding.progress.visibility = View.VISIBLE
                hideError()
                view?.evaluateJavascript(TOUCH_FIX_JS, null)
                view?.evaluateJavascript(NativeWebSocketJs.INSTALL, null)
                view?.evaluateJavascript(AndroidBackJs.INSTALL, null)
                view?.evaluateJavascript(SystemBarsJs.INSTALL, null)
                view?.evaluateJavascript(ServerConfigSyncJs.INSTALL, null)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                CookieManager.getInstance().flush()
                binding.webView.evaluateJavascript(TOUCH_FIX_JS, null)
                binding.webView.evaluateJavascript(NativeWebSocketJs.INSTALL, null)
                binding.webView.evaluateJavascript(AndroidBackJs.INSTALL, null)
                binding.webView.evaluateJavascript(IME_SCROLL_JS, null)
                binding.webView.evaluateJavascript(IME_RESTORE_JS, null)
                binding.webView.evaluateJavascript(SystemBarsJs.INSTALL, null)
                binding.webView.evaluateJavascript(ServerConfigSyncJs.INSTALL, null)
                binding.webView.requestFocus()
                convergeServerConfigOnce(binding.webView)
            }

            override fun onReceivedHttpAuthRequest(
                view: WebView?,
                handler: HttpAuthHandler?,
                host: String?,
                realm: String?,
            ) {
                Log.w(LOG_TAG, "HTTP Basic Auth challenge host=$host realm=$realm — enter credentials in Settings → Server")
                handler?.cancel()
                Toast.makeText(
                    this@MainActivity,
                    R.string.error_auth_failed,
                    Toast.LENGTH_LONG,
                ).show()
            }

            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                errorResponse: WebResourceResponse,
            ) {
                if (request.isForMainFrame) return
                val path = request.url.path.orEmpty()
                if (path.endsWith("/api/health") || path.endsWith("/global/health")) {
                    Log.w(
                        LOG_TAG,
                        "health ${request.method} ${request.url} -> ${errorResponse.statusCode} ${errorResponse.reasonPhrase}",
                    )
                }
            }

            override fun onReceivedSslError(
                view: WebView?,
                handler: SslErrorHandler?,
                error: SslError?,
            ) {
                Log.e(LOG_TAG, "TLS error url=${error?.url} primary=${error?.primaryError}")
                handler?.cancel()
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError,
            ) {
                if (!request.isForMainFrame) {
                    val path = request.url.path.orEmpty()
                    if (path.endsWith("/api/health") || path.endsWith("/global/health")) {
                        Log.w(
                            LOG_TAG,
                            "subresource error ${request.url} code=${error.errorCode} ${error.description}",
                        )
                    }
                    return
                }
                if (request.url.host == ASSET_HOST) {
                    showError(
                        getString(
                            R.string.error_load_failed,
                            error.description ?: error.errorCode.toString(),
                        ),
                    )
                }
            }
        }

        binding.webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                val msg = consoleMessage.message()
                if (msg.contains("[OpenCode Android]") ||
                    msg.contains("CORS") ||
                    msg.contains("Failed to fetch")
                ) {
                    Log.w(
                        LOG_TAG,
                        "console ${consoleMessage.messageLevel()} ${consoleMessage.sourceId()}:${consoleMessage.lineNumber()} $msg",
                    )
                }
                return super.onConsoleMessage(consoleMessage)
            }

            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                binding.progress.progress = newProgress
                binding.progress.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
            }

            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message?,
            ): Boolean {
                val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                val temp = WebView(this@MainActivity).apply {
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            v: WebView,
                            request: WebResourceRequest,
                        ): Boolean {
                            openExternal(request.url)
                            return true
                        }
                    }
                }
                transport.webView = temp
                resultMsg.sendToTarget()
                return true
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?,
            ): Boolean {
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback
                val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }
                return try {
                    fileChooserLauncher.launch(intent)
                    true
                } catch (_: Exception) {
                    this@MainActivity.filePathCallback = null
                    false
                }
            }
        }
    }

    private fun openExternal(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: Exception) {
            Toast.makeText(this, uri.toString(), Toast.LENGTH_SHORT).show()
        }
    }

    private fun showError(message: String) {
        binding.errorPanel.visibility = View.VISIBLE
        binding.errorMessage.text = message
    }

    private fun hideError() {
        binding.errorPanel.visibility = View.GONE
    }

    private fun isLocalGateway(uri: Uri): Boolean {
        val host = uri.host ?: return false
        if (host != "127.0.0.1" && host != "localhost") return false
        return gateway.isBoundPort(uri.port)
    }

    companion object {
        const val ASSET_HOST = "appassets.androidplatform.net"
        const val ANDROID_WEBVIEW_ORIGIN = "https://$ASSET_HOST"
        const val EMBEDDED_INDEX_URL = "https://$ASSET_HOST/assets/opencode-app/index.html"
        private const val LOG_TAG = "OpenCodeShell"
        private const val CONFIG_TOAST_THROTTLE_MS = 8_000L

        /** Offline SDK stub is only 127.0.0.1/localhost/::1 port 0 — never a real HTTPS backend. */
        internal fun isOfflineDisconnectStub(host: String?, port: Int): Boolean {
            if (host != "127.0.0.1" && host != "localhost" && host != "::1") return false
            return port == 0
        }

        private const val TOUCH_FIX_JS = """
            (function () {
              try {
                document.documentElement.style.pointerEvents = 'auto';
                if (document.body) {
                  document.body.style.pointerEvents = 'auto';
                  document.body.removeAttribute('inert');
                }
                var root = document.getElementById('root');
                if (root) {
                  root.style.pointerEvents = 'auto';
                  root.removeAttribute('inert');
                }
                window.__ocAndroidShell = true;
                window.__ocAndroidEscapePassThrough = true;
                if (!document.getElementById('oc-android-touch-css')) {
                  var style = document.createElement('style');
                  style.id = 'oc-android-touch-css';
                  style.textContent = 'html,body{-webkit-user-select:none;user-select:none;}'
                    + 'input,textarea,[contenteditable],[data-component="terminal"],[data-component="terminal"] *{-webkit-user-select:text;user-select:text;}'
                    + 'html[data-android-webview] [data-component="text-part"],'
                    + 'html[data-android-webview] [data-component="text-part"] *,'
                    + 'html[data-android-webview] [data-slot="text-part-body"],'
                    + 'html[data-android-webview] [data-slot="text-part-body"] *,'
                    + 'html[data-android-webview] [data-slot="user-message-body"],'
                    + 'html[data-android-webview] [data-slot="user-message-body"] *,'
                    + 'html[data-android-webview] [data-slot="user-message-text"],'
                    + 'html[data-android-webview] [data-slot="user-message-text"] *,'
                    + 'html[data-android-webview] [data-component="tool-output"],'
                    + 'html[data-android-webview] [data-component="tool-output"] *,'
                    + 'html[data-android-webview] [data-slot*="markdown"],'
                    + 'html[data-android-webview] [data-slot*="markdown"] *,'
                    + 'html[data-android-webview] [data-slot="message-part-todo-content"],'
                    + 'html[data-android-webview] [data-slot="message-part-todo-content"] *,'
                    + 'html[data-android-webview] [data-slot="session-review-diff-wrapper"],'
                    + 'html[data-android-webview] [data-slot="session-review-diff-wrapper"] *,'
                    + 'html[data-android-webview] pre,'
                    + 'html[data-android-webview] code'
                    + '{-webkit-user-select:text;user-select:text;}';
                  document.documentElement.appendChild(style);
                }
                try {
                  if (window.OpenCodeAndroid) {
                    window.__ocAndroidProxy = {
                      origin: window.OpenCodeAndroid.proxyOrigin(),
                      prefix: window.OpenCodeAndroid.proxyPrefix()
                    };
                  }
                } catch (e2) {}
              } catch (e) {}
            })();
        """

        private const val IME_SCROLL_JS = """
            (function () {
              if (window.__ocImeScroll) return;
              window.__ocImeScroll = true;
              // Trailing settled scroll: OEM IME runs ~150-400ms (slow、分段 up to 750ms).
              // focusin + visualViewport.resize share one trailing timer; single
              // scrollFocused() call after settle. No per-frame re-center.
              var SETTLE_MS = 250;
              var FALLBACK_MS = 350;
              var MAX_RETRY = 3;
              var settleTimer = 0;
              var fallbackTimer = 0;
              var pendingScroll = false;
              var retry = 0;
              var lastH = 0;
              var fillRoot = function () {
                var root = document.getElementById('root');
                if (!root) return;
                root.style.height = '100%';
                root.style.minHeight = '100%';
                root.style.maxHeight = '100%';
              };
              var layoutH = function () {
                try {
                  if (document.documentElement && document.documentElement.clientHeight) {
                    return document.documentElement.clientHeight;
                  }
                } catch (e) {}
                return window.innerHeight;
              };
              var scrollFocused = function () {
                var el = document.activeElement;
                if (!el || el === document.body || el === document.documentElement) return;
                var tag = (el.tagName || '').toLowerCase();
                var editable = tag === 'input' || tag === 'textarea' || el.isContentEditable === true;
                if (!editable) return;
                try {
                  var rect = el.getBoundingClientRect();
                  if (rect.bottom <= layoutH() - 8 && rect.top >= 0) return;
                } catch (e) {}
                try { el.scrollIntoView({ block: 'nearest', inline: 'nearest' }); }
                catch (e2) { try { el.scrollIntoView(false); } catch (e3) {} }
              };
              var settled = function () {
                if (!pendingScroll) return;
                if (settleTimer) { clearTimeout(settleTimer); settleTimer = 0; }
                if (fallbackTimer) { clearTimeout(fallbackTimer); fallbackTimer = 0; }
                var h = layoutH();
                if (h !== lastH && retry < MAX_RETRY) {
                  lastH = h;
                  retry++;
                  settleTimer = setTimeout(settled, SETTLE_MS);
                  return;
                }
                pendingScroll = false;
                retry = 0;
                lastH = h;
                scrollFocused();
              };
              var arm = function () {
                pendingScroll = true;
                retry = 0;
                if (settleTimer) clearTimeout(settleTimer);
                if (fallbackTimer) clearTimeout(fallbackTimer);
                settleTimer = setTimeout(settled, SETTLE_MS);
                fallbackTimer = setTimeout(settled, FALLBACK_MS);
              };
              document.addEventListener('focusin', arm, true);
              if (window.visualViewport) {
                visualViewport.addEventListener('resize', arm);
              }
              fillRoot();
            })();
        """

        private const val IME_RESTORE_JS = """
            (function () {
              var root = document.getElementById('root');
              if (root) {
                root.style.height = '100%';
                root.style.minHeight = '100%';
                root.style.maxHeight = '100%';
              }
              try { window.dispatchEvent(new Event('resize')); } catch (e) {}
              try {
                if (window.visualViewport) visualViewport.dispatchEvent(new Event('resize'));
              } catch (e2) {}
            })();
        """

        internal fun imeAwareBottomPadding(
            systemBarsBottom: Int,
            imeBottom: Int,
            imeVisible: Boolean = imeBottom > systemBarsBottom,
        ): Int {
            if (!imeVisible) return systemBarsBottom
            return max(systemBarsBottom, imeBottom)
        }

        internal fun isLocalGateway(uri: Uri, gatewayPort: Int): Boolean {
            val host = uri.host ?: return false
            if (host != "127.0.0.1" && host != "localhost") return false
            return uri.port == gatewayPort
        }

        internal fun disconnectedStub(uri: Uri): WebResourceResponse? {
            if (!isOfflineDisconnectStub(uri.host, uri.port)) return null
            val body = """{"healthy":false,"disconnected":true}""".toByteArray()
            return WebResourceResponse(
                "application/json",
                "utf-8",
                503,
                "Disconnected",
                mapOf(
                    "Content-Type" to "application/json",
                    "Access-Control-Allow-Origin" to "*",
                    "Cache-Control" to "no-store",
                ),
                ByteArrayInputStream(body),
            )
        }
    }
}
