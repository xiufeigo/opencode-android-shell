package com.xiufeigo.opencodeshell

import android.webkit.JavascriptInterface

class OpenCodeAndroidBridge(
    private val gateway: LocalGateway,
    private val webSockets: NativeWebSocketHub,
) {
    @JavascriptInterface
    fun proxyOrigin(): String = gateway.publicOrigin()

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
}
