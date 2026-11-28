package com.xiufeigo.opencodeshell.web.host

import android.content.Intent
import android.net.Uri
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView

/**
 * WebChromeClient adapter for file chooser, camera permission, and console forwarding.
 * Product behavior stays in the injected host modules; this class only adapts framework callbacks.
 */
class HostWebChromeClient(
    private val fileChooser: FileChooserHost,
    private val cameraPermissionHost: CameraPermissionHost,
) : WebChromeClient() {
    override fun onShowFileChooser(
        webView: WebView,
        callback: ValueCallback<Array<Uri>>,
        params: FileChooserParams,
    ): Boolean {
        val intent = params.createIntent().apply {
            if (params.acceptTypes.isNotEmpty()) {
                val types = params.acceptTypes.filter { it.isNotBlank() }
                if (types.isNotEmpty()) {
                    type = if (types.size == 1) types[0] else "*/*"
                    if (types.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, types.toTypedArray())
                }
            }
        }
        fileChooser.launchOpen(intent) { uris ->
            callback.onReceiveValue(if (uris.isEmpty()) null else uris.toTypedArray())
        }
        return true
    }

    override fun onPermissionRequest(request: PermissionRequest) {
        cameraPermissionHost.onPermissionRequest(request)
    }

    override fun onPermissionRequestCanceled(request: PermissionRequest) {
        cameraPermissionHost.onPermissionRequestCanceled(request)
    }

    override fun onConsoleMessage(message: ConsoleMessage): Boolean {
        Log.i(TAG, "web[${message.messageLevel()}] ${message.message()} @${message.lineNumber()}")
        return true
    }

    private companion object {
        const val TAG = "OcWebHost"
    }
}
