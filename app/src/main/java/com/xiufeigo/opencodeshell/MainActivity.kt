package com.xiufeigo.opencodeshell

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
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
import androidx.webkit.WebViewClientCompat
import com.xiufeigo.opencodeshell.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var settings: SettingsStore
    private lateinit var assetLoader: WebViewAssetLoader
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var httpAuthAttempts = 0

    private val settingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == RESULT_OK && settings.hasServerUrl) {
            loadEmbeddedFrontend(applyBootstrap = true)
        }
    }

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
        settings = settingsStore()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        hideSystemBars()
        padOverlayForCutout()
        configureWebView()

        binding.settingsButton.setOnClickListener { openSettings() }
        binding.errorRetry.setOnClickListener { loadEmbeddedFrontend(applyBootstrap = settings.hasServerUrl) }
        binding.errorSettings.setOnClickListener { openSettings() }

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

        if (!settings.hasServerUrl) {
            openSettings()
        }
        loadEmbeddedFrontend(applyBootstrap = false)
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        binding.webView.requestFocus()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemBars()
            binding.webView.requestFocus()
        }
    }

    private fun openSettings() {
        settingsLauncher.launch(Intent(this, SettingsActivity::class.java))
    }

    private fun loadEmbeddedFrontend(applyBootstrap: Boolean) {
        httpAuthAttempts = 0
        hideError()
        val url = if (applyBootstrap && settings.hasServerUrl) {
            val builder = Uri.parse(EMBEDDED_BOOTSTRAP_URL).buildUpon()
                .appendQueryParameter("server", settings.serverUrl)
            if (settings.username.isNotBlank()) {
                builder.appendQueryParameter("username", settings.username)
            }
            if (settings.password.isNotBlank()) {
                builder.appendQueryParameter("password", settings.password)
            }
            builder.build().toString()
        } else {
            EMBEDDED_INDEX_URL
        }
        binding.webView.loadUrl(url)
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, binding.root)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun padOverlayForCutout() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.overlayBar) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            view.updatePadding(top = bars.top, right = bars.right, left = bars.left)
            insets
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun configureWebView() {
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(binding.webView, true)

        binding.webView.isFocusable = true
        binding.webView.isFocusableInTouchMode = true
        binding.webView.isLongClickable = true
        binding.webView.setOnLongClickListener { false }

        binding.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            javaScriptCanOpenWindowsAutomatically = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            allowFileAccess = false
            allowContentAccess = true
            cacheMode = WebSettings.LOAD_DEFAULT
            setSupportMultipleWindows(true)
            setNeedInitialFocus(true)
        }

        binding.webView.webViewClient = object : WebViewClientCompat() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ): WebResourceResponse? {
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
                httpAuthAttempts = 0
                CookieManager.getInstance().flush()
                binding.webView.requestFocus()
            }

            override fun onReceivedHttpAuthRequest(
                view: WebView?,
                handler: HttpAuthHandler?,
                host: String?,
                realm: String?,
            ) {
                if (handler == null) return
                if (settings.hasCredentials && httpAuthAttempts < 2) {
                    httpAuthAttempts++
                    handler.proceed(settings.username, settings.password)
                } else {
                    handler.cancel()
                    Toast.makeText(
                        this@MainActivity,
                        R.string.error_auth_failed,
                        Toast.LENGTH_LONG,
                    ).show()
                }
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
                    webViewClient = object : android.webkit.WebViewClient() {
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
        const val EMBEDDED_ORIGIN = "https://$ASSET_HOST"
        const val EMBEDDED_INDEX_URL = "https://$ASSET_HOST/assets/opencode-app/index.html"
        const val EMBEDDED_BOOTSTRAP_URL = "https://$ASSET_HOST/assets/opencode-app/bootstrap.html"
    }
}
