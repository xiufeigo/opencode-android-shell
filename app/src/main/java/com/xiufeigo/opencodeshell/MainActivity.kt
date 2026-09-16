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
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
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
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var backInFlight = false
    private var webColorSchemeApplied = false

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

        loadEmbeddedFrontend()
    }

    override fun onDestroy() {
        if (::webSockets.isInitialized) webSockets.closeAll()
        gateway.close()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        binding.webView.requestFocus()
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
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.statusBars() or
                    WindowInsetsCompat.Type.navigationBars() or
                    WindowInsetsCompat.Type.displayCutout(),
            )
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.updatePadding(
                left = bars.left,
                top = bars.top,
                right = bars.right,
                bottom = imeAwareBottomPadding(bars.bottom, ime.bottom),
            )
            WindowInsetsCompat.CONSUMED
        }
        ViewCompat.requestApplyInsets(binding.root)
        applySystemUi(systemNightMode())
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
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

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun configureWebView() {
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(binding.webView, true)

        webSockets = NativeWebSocketHub(binding.webView)
        binding.webView.addJavascriptInterface(
            OpenCodeAndroidBridge(gateway, webSockets) { dark ->
                webColorSchemeApplied = true
                runOnUiThread { applySystemUi(dark) }
            },
            "OpenCodeAndroid",
        )
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            val origins = setOf(ANDROID_WEBVIEW_ORIGIN, "http://$ASSET_HOST")
            WebViewCompat.addDocumentStartJavaScript(binding.webView, NativeWebSocketJs.INSTALL, origins)
            WebViewCompat.addDocumentStartJavaScript(binding.webView, SystemBarsJs.INSTALL, origins)
        }

        binding.webView.isFocusable = true
        binding.webView.isFocusableInTouchMode = true
        binding.webView.isClickable = true
        binding.webView.isLongClickable = true
        binding.webView.overScrollMode = View.OVER_SCROLL_NEVER
        binding.webView.setOnLongClickListener { false }
        binding.webView.setOnTouchListener { view, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
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
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                CookieManager.getInstance().flush()
                binding.webView.evaluateJavascript(TOUCH_FIX_JS, null)
                binding.webView.evaluateJavascript(NativeWebSocketJs.INSTALL, null)
                binding.webView.evaluateJavascript(AndroidBackJs.INSTALL, null)
                binding.webView.evaluateJavascript(IME_SCROLL_JS, null)
                binding.webView.evaluateJavascript(SystemBarsJs.INSTALL, null)
                binding.webView.requestFocus()
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
              var scrollFocused = function () {
                var el = document.activeElement;
                if (!el || el === document.body || el === document.documentElement) return;
                var tag = (el.tagName || '').toLowerCase();
                var editable = tag === 'input' || tag === 'textarea' || el.isContentEditable === true;
                if (!editable) return;
                try { el.scrollIntoView({ block: 'center', inline: 'nearest' }); }
                catch (e) { try { el.scrollIntoView(true); } catch (e2) {} }
              };
              document.addEventListener('focusin', function () { setTimeout(scrollFocused, 60); }, true);
              window.addEventListener('resize', scrollFocused);
              if (window.visualViewport) visualViewport.addEventListener('resize', scrollFocused);
            })();
        """

        internal fun imeAwareBottomPadding(systemBarsBottom: Int, imeBottom: Int): Int =
            max(systemBarsBottom, imeBottom)

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
