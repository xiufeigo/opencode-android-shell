package com.xiufeigo.opencodeshell

import android.webkit.JavascriptInterface

class OpenCodeAndroidBridge(private val gateway: LocalGateway) {
    @JavascriptInterface
    fun proxyOrigin(): String = gateway.publicOrigin()

    @JavascriptInterface
    fun proxyPrefix(): String = gateway.publicPrefix()
}
