package com.xiufeigo.opencodeshell

import android.webkit.JavascriptInterface

class OpenCodeAndroidBridge(private val gateway: LocalGateway) {
    @JavascriptInterface
    fun proxyOrigin(): String = gateway.publicOrigin()

    @JavascriptInterface
    fun proxyPrefix(): String = gateway.publicPrefix()

    /** Origin-only loopback URL for [realUrl] so v1 `new URL('/api/...', base)` keeps the proxy. */
    @JavascriptInterface
    fun proxyBaseUrl(realUrl: String): String = gateway.originFor(realUrl)
}
