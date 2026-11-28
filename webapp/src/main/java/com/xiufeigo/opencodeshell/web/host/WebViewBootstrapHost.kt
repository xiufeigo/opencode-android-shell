package com.xiufeigo.opencodeshell.web.host

import android.content.Context
import android.content.res.Configuration
import android.util.Log
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject

/**
 * WebView bootstrap policy: settings plus document-start script installation.
 *
 * The Activity owns instance assembly and lifecycle. This module owns the static
 * WebView configuration contract and the ordering-sensitive script bundle that
 * must be registered before page scripts execute.
 */
class WebViewBootstrapHost(
    private val context: Context,
    private val legacyChannel: () -> LegacyBridgeChannel,
    private val assetHost: String,
) {
    fun configureSettings(settings: WebSettings) = with(settings) {
        javaScriptEnabled = true
        domStorageEnabled = true
        useWideViewPort = false
        loadWithOverviewMode = false
        setSupportZoom(false)
        builtInZoomControls = false
        displayZoomControls = false
        mediaPlaybackRequiresUserGesture = false
        // 混合内容仅走兼容模式：页面与 API 通常同源于后端 http 地址，但保留
        // https 后端 + http 子资源的边界场景可用性。
        mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        // 资产只走 assetLoader，不开放 file:// / content:// 通用读权限。
        allowFileAccess = false
        allowContentAccess = false
        cacheMode = WebSettings.LOAD_DEFAULT
        userAgentString = "$userAgentString OpenCodeWebHost/0.1 (W1-skeleton)"
    }

    fun installDocumentStartScripts(webView: WebView) {
        installFacade(webView)
        installPerfProbe(webView)
        installPreflightProbe(webView)
    }

    /**
     * Register the Android facade before any page script can observe a missing bridge.
     *
     * `onPageFinished` injection would be too late: first-screen page code has already
     * run without `window.__ocAndroid`. The initial scheme is prepended because the
     * bundled UI reads `prefers-color-scheme` during preload, and WebView can still
     * hold a stale media-query value on cold start.
     */
    private fun installFacade(webView: WebView) {
        val origins = setOf(LocalUiOriginServer.origin())
        val night = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val initialScheme = "window.__ocHostSchemeInitial=" + JSONObject.quote(if (night) "dark" else "light") + ";"
        val script = initialScheme + legacyChannel().script(origins) + runCatching {
            context.assets.open("www/oc-host.js").bufferedReader().use { it.readText() }
        }.getOrElse {
            Log.e(TAG, "cannot read www/oc-host.js", it)
            return
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(webView, script, origins)
            val diagnosticOrigins = setOf("https://$assetHost")
            WebViewCompat.addDocumentStartJavaScript(webView, legacyChannel().script(diagnosticOrigins, diagnostic = true), diagnosticOrigins)
            Log.i(TAG, "facade registered as document-start script")
        }
    }

    /**
     * Performance probes are diagnostic-only: missing/broken probe assets must not
     * break the shell. Registration failure is therefore logged and swallowed.
     */
    private fun installPerfProbe(webView: WebView) {
        val script = runCatching {
            context.assets.open("www/oc-perf.js").bufferedReader().use { it.readText() }
        }.getOrElse {
            Log.w(TAG, "oc-perf.js missing, skipping probe")
            return
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            runCatching { WebViewCompat.addDocumentStartJavaScript(webView, script, setOf("*")) }
                .onSuccess { PerfMarks.mark("E2.perfProbeRegistered") }
                .onFailure { Log.w(TAG, "perf probe register failed", it) }
        } else {
            Log.w(TAG, "DOCUMENT_START_SCRIPT unsupported -> perf probe skipped")
        }
    }

    /**
     * First-content preflight probe used by StartupRecoveryHost. Old WebViews fall
     * back to the later page-start path, but the preflight gate normally stops them
     * before this installer matters.
     */
    private fun installPreflightProbe(webView: WebView) {
        val script = runCatching {
            context.assets.open("www/oc-preflight.js").bufferedReader().use { it.readText() }
        }.getOrElse {
            Log.w(TAG, "oc-preflight.js missing, first-content signal unavailable", it)
            return
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            runCatching { WebViewCompat.addDocumentStartJavaScript(webView, script, setOf("*")) }
                .onSuccess { PerfMarks.mark("E3.g1ProbeRegistered") }
                .onFailure { Log.w(TAG, "g1 probe register failed", it) }
        } else {
            Log.w(TAG, "DOCUMENT_START_SCRIPT unsupported -> g1 probe falls back to onPageStarted")
        }
    }

    private companion object {
        const val TAG = "OcWebHost"
    }
}
