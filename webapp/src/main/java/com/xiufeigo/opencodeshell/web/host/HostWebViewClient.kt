package com.xiufeigo.opencodeshell.web.host

import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader

/**
 * WebViewClient adapter for the Android shell.
 *
 * Framework callbacks stay in this boundary module. Product effects are injected:
 * external-link opening, startup recovery, renderer recovery, and page-ready
 * propagation remain owned by their respective host modules or the composition root.
 */
class HostWebViewClient(
    private val assetDomain: String,
    private val assetPrefix: String,
    private val systemFonts: SystemFontCatalog,
    private val startupRecovery: StartupRecoveryHost,
    private val renderProcessRecovery: RenderProcessRecoveryHost,
    private val openExternalLink: (String) -> Unit,
    private val onPageReady: (WebView, String) -> Unit,
) : WebViewClient() {
    private var assetLoader: WebViewAssetLoader? = null

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        if (!request.isForMainFrame) return false
        val url = request.url.toString()
        if (WebOrigins.sameOrigin(url, LocalUiOriginServer.origin())) return false
        openExternalLink(url)
        return true
    }

    private fun loader(context: android.content.Context): WebViewAssetLoader =
        assetLoader ?: WebViewAssetLoader.Builder()
            .setDomain(assetDomain)
            .addPathHandler(assetPrefix, WebViewAssetLoader.AssetsPathHandler(context))
            .build()
            .also { assetLoader = it }

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest,
    ): WebResourceResponse? {
        val url = request.url
        val path = url.path.orEmpty()
        if (WebOrigins.sameOrigin(url.toString(), LocalUiOriginServer.origin()) && path.startsWith(SystemFontCatalog.URL_PREFIX)) {
            val id = path.removePrefix(SystemFontCatalog.URL_PREFIX)
            val stream = if (request.method == "GET" && !request.isForMainFrame) systemFonts.open(id) else null
            return if (stream == null) {
                WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", emptyMap(), null)
            } else {
                WebResourceResponse("font/ttf", null, 200, "OK", mapOf("Cache-Control" to "no-store"), stream)
            }
        }
        return loader(view.context).shouldInterceptRequest(url)
    }

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
        startupRecovery.onLoadError(
            isForMainFrame = request.isForMainFrame,
            detail = "code=${error.errorCode} ${error.description} url=${request.url}",
        )
    }

    override fun onRenderProcessGone(
        view: WebView,
        detail: RenderProcessGoneDetail,
    ): Boolean = renderProcessRecovery.onRenderProcessGone(view, detail)

    override fun onPageFinished(view: WebView, url: String) {
        super.onPageFinished(view, url)
        PerfMarks.mark("L.onPageFinished", "url=$url")
        onPageReady(view, url)
    }
}
