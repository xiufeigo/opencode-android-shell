package com.xiufeigo.opencodeshell.web.host

import android.content.Intent
import android.util.Log
import android.util.Base64
import android.webkit.WebView

/** Owns the debug-only oc_debug_eval intent channel. */
class DebugEvalHost(
    private val debugEnabled: Boolean,
    private val extraName: String,
    private val webViewProvider: () -> WebView?,
) {
    fun consume(intent: Intent?) {
        if (!debugEnabled) return
        val raw = intent?.getStringExtra(extraName) ?: return
        intent.removeExtra(extraName)
        val webView = webViewProvider() ?: return
        webView.postDelayed({
            val js = if (raw.startsWith("b64:")) {
                String(Base64.decode(raw.removePrefix("b64:"), Base64.DEFAULT), Charsets.UTF_8)
            } else {
                "(function(){try{return String($raw)}catch(e){return 'ERR:'+e}})()"
            }
            webView.evaluateJavascript(js) { result ->
                Log.i(TAG, "eval=${raw.take(200)} -> ${result ?: "null"}")
            }
        }, SETTLE_MS)
    }

    private companion object {
        const val TAG = "OcWebHost/Eval"
        const val SETTLE_MS = 1200L
    }
}
