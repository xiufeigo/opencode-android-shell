package com.xiufeigo.opencodeshell

import android.webkit.JavascriptInterface

class OpenCodeAndroidBridge(
    private val gateway: LocalGateway,
    private val webSockets: NativeWebSocketHub,
    private val onColorScheme: (dark: Boolean) -> Unit = {},
    private val onServerConfig: (String?) -> Unit = {},
) {
    @JavascriptInterface
    fun proxyOrigin(): String = gateway.publicOrigin()

    /**
     * Receives the WebView's server config (localStorage `opencode.global.dat:server`
     * = `{url, username?, password?}`). Called after page load and whenever the
     * user edits server settings; the shell caches it for the native client.
     */
    @JavascriptInterface
    fun syncServerConfig(json: String?) {
        onServerConfig(json)
    }

    @JavascriptInterface
    fun proxyPrefix(): String = gateway.publicPrefix()

    /** Origin-only loopback URL for [realUrl] so v1 `new URL('/api/...', base)` keeps the proxy. */
    @JavascriptInterface
    fun proxyBaseUrl(realUrl: String): String = gateway.originFor(realUrl)

    @JavascriptInterface
    fun wsOpen(id: String, url: String) {
        webSockets.open(id, url)
    }

    @JavascriptInterface
    fun wsSendText(id: String, text: String) {
        webSockets.sendText(id, text)
    }

    @JavascriptInterface
    fun wsSendBinary(id: String, base64: String) {
        webSockets.sendBinary(id, base64)
    }

    @JavascriptInterface
    fun wsClose(id: String, code: Int, reason: String) {
        webSockets.close(id, code, reason)
    }

    /** Official UI color-scheme: `"dark"` or `"light"`. */
    @JavascriptInterface
    fun setColorScheme(scheme: String?) {
        val dark = SystemUiAppearance.darkFromWebScheme(scheme) ?: return
        onColorScheme(dark)
    }
}
