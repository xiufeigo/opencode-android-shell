package com.xiufeigo.opencodeshell.web.host

import android.util.Log
import androidx.webkit.WebViewFeature

/**
 * Startup navigation policy for the locally served official UI.
 *
 * This module owns the order of port/capability gates, WebView preflight,
 * restored-URL admission, watchdog arming, and the final loadUrl effect.
 */
class StartupNavigationHost(
    private val localUiServerProvider: () -> LocalUiOriginServer,
    private val startupRecovery: StartupRecoveryHost,
    private val restoredUrlProvider: () -> String?,
    private val loadUrl: (String) -> Unit,
) {
    fun launch(uiOrigin: String) {
        PerfMarks.mark("H.launch.enter", "uiOrigin=$uiOrigin")
        val localUiServer = localUiServerProvider()
        if (localUiServer.boundPort == null) {
            startupRecovery.showDiagnostic(
                WebViewPreflight.Reason.LOAD_ERROR,
                "本地 UI 服务无法绑定端口 ${localUiServer.port}，请检查端口占用后重启应用",
            )
            return
        }
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            startupRecovery.showDiagnostic(
                WebViewPreflight.Reason.WEBVIEW_TOO_OLD,
                "当前 WebView 不支持平台消息和文档开始注入，请更新 Android System WebView",
            )
            return
        }
        val restored = restoredUrlProvider()
        val url = if (restored != null && restored.startsWith(uiOrigin)) restored else "$uiOrigin/"

        // Never load the official UI into a WebView too old to parse it; that
        // failure mode is a white page with otherwise successful resource loads.
        when (val verdict = WebViewPreflight.preflight(startupRecovery.versionName)) {
            is WebViewPreflight.Verdict.ShowDiagnostic -> {
                startupRecovery.showDiagnostic(verdict.reason, "WebView ${verdict.versionName} < ${WebViewPreflight.MIN_MAJOR}")
                PerfMarks.mark("J.g1.blockedOldWebview", "major=${verdict.major}")
                return
            }
            is WebViewPreflight.Verdict.LoadOfficialUi -> {
                PerfMarks.mark("J.g1.preflightOk", "major=${verdict.major} unknown=${verdict.unknownVersion}")
            }
        }

        Log.i(TAG, "home=$url uiOrigin=$uiOrigin")
        startupRecovery.arm()
        loadUrl(url)
        PerfMarks.mark("J.loadUrlIssued", "url=$url")
    }

    private companion object {
        const val TAG = "OcWebHost"
    }
}
