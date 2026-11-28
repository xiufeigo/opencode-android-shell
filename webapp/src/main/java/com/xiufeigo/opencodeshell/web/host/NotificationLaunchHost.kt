package com.xiufeigo.opencodeshell.web.host

import android.content.Intent
import android.util.Log
import android.webkit.WebView

/**
 * Consumes notification-click intents and delivers their target session after
 * the page facade has had time to install itself.
 */
class NotificationLaunchHost(
    private val webViewProvider: () -> WebView?,
    private val bridgeProvider: () -> WebHostBridge,
) {
    fun consume(intent: Intent?) {
        if (intent == null || !intent.getBooleanExtra(NotifierHost.EXTRA_FROM_NOTIFICATION, false)) return
        intent.removeExtra(NotifierHost.EXTRA_FROM_NOTIFICATION)
        val sessionId = intent.getStringExtra(NotifierHost.EXTRA_SESSION_ID)
        Log.i(TAG, "opened from notification, session=$sessionId")
        val webView = webViewProvider() ?: return
        webView.postDelayed({ bridgeProvider().deliverOpenSession(sessionId) }, FACADE_SETTLE_MS)
    }

    private companion object {
        const val TAG = "OcWebHost"
        const val FACADE_SETTLE_MS = 400L
    }
}
