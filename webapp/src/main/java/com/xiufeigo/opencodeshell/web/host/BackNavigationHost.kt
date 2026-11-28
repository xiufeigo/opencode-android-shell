package com.xiufeigo.opencodeshell.web.host

import android.util.Log
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity

/**
 * Android Back arbitration for the Web shell.
 *
 * The page gets the first chance to close its current surface. When it declines,
 * the application root is returned to Android's normal Back behavior. The pending
 * callback state and failure reporting live here instead of in the entry Activity.
 */
class BackNavigationHost(
    private val activity: AppCompatActivity,
    private val webViewProvider: () -> WebView,
) {
    fun install() {
        activity.onBackPressedDispatcher.addCallback(activity, object : OnBackPressedCallback(true) {
            private var pending: WebView? = null

            private fun reportFailure(error: RuntimeException? = null) {
                Log.e(TAG, "back: page handler failed, see WebView console", error)
                Toast.makeText(activity, "返回处理失败，请查看 WebView 日志", Toast.LENGTH_SHORT).show()
            }

            override fun handleOnBackPressed() {
                val current = webViewProvider()
                if (pending === current) return
                pending = current
                try {
                    current.evaluateJavascript(
                        "(function(){" +
                            "return !!(window.__ocAndroid && window.__ocAndroid.onBack && window.__ocAndroid.onBack());" +
                            "})()"
                    ) { result ->
                        if (pending !== current) return@evaluateJavascript
                        pending = null
                        if (current !== webViewProvider() || activity.isFinishing || activity.isDestroyed) return@evaluateJavascript
                        val pageHandled = when (result?.trim()) {
                            "true" -> true
                            "false" -> false
                            else -> {
                                reportFailure()
                                return@evaluateJavascript
                            }
                        }
                        if (pageHandled) {
                            Log.i(TAG, "back: page handled current surface")
                            return@evaluateJavascript
                        }
                        Log.i(TAG, "back: application root -> Android default")
                        isEnabled = false
                        try {
                            activity.onBackPressedDispatcher.onBackPressed()
                        } finally {
                            isEnabled = true
                        }
                    }
                } catch (error: RuntimeException) {
                    if (pending === current) pending = null
                    reportFailure(error)
                }
            }
        })
    }

    private companion object {
        const val TAG = "OcWebHost"
    }
}
