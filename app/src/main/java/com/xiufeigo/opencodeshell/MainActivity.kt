package com.xiufeigo.opencodeshell

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.webkit.CookieManager
import android.webkit.HttpAuthHandler
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
import com.xiufeigo.opencodeshell.databinding.ActivityMainBinding
import java.io.ByteArrayInputStream

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var assetLoader: WebViewAssetLoader
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

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

        // Do not hide system bars. HyperOS immersive overlays have been observed
        // swallowing every subsequent touch while chrome still paints.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        padWebViewForCutout()
        configureWebView()

        binding.errorRetry.setOnClickListener { loadEmbeddedFrontend() }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (binding.webView.canGoBack()) {
                        binding.webView.goBack()
                    } else {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            },
        )

        loadEmbeddedFrontend()
    }

    override fun onResume() {
        super.onResume()
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
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.statusBars() or
                    WindowInsetsCompat.Type.navigationBars() or
                    WindowInsetsCompat.Type.displayCutout(),
            )
            view.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        ViewCompat.requestApplyInsets(binding.root)
        val bars = WindowInsetsControllerCompat(window, binding.root)
        bars.isAppearanceLightStatusBars = true
        bars.isAppearanceLightNavigationBars = true
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun configureWebView() {
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(binding.webView, true)

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
                disconnectedStub(request.url)?.let { return it }
                return assetLoader.shouldInterceptRequest(request.url)
            }

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean {
                val uri = request.url
                if (uri.host == ASSET_HOST) return false
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
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                CookieManager.getInstance().flush()
                binding.webView.evaluateJavascript(TOUCH_FIX_JS, null)
                binding.webView.requestFocus()
            }

            override fun onReceivedHttpAuthRequest(
                view: WebView?,
                handler: HttpAuthHandler?,
                host: String?,
                realm: String?,
            ) {
                handler?.cancel()
                Toast.makeText(
                    this@MainActivity,
                    R.string.error_auth_failed,
                    Toast.LENGTH_LONG,
                ).show()
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError,
            ) {
                if (!request.isForMainFrame) return
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

    companion object {
        const val ASSET_HOST = "appassets.androidplatform.net"
        const val EMBEDDED_INDEX_URL = "https://$ASSET_HOST/assets/opencode-app/index.html"

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
              } catch (e) {}
            })();
        """

        internal fun disconnectedStub(uri: Uri): WebResourceResponse? {
            val host = uri.host ?: return null
            if (host != "127.0.0.1" && host != "localhost" && host != "::1") return null
            if (uri.port != 0) return null
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
