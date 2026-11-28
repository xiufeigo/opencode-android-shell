package com.xiufeigo.opencodeshell.web.host

import android.os.SystemClock
import android.util.Log
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView

/**
 * Renderer-process crash recovery state machine.
 *
 * The Activity supplies only platform effects: dispose the dead WebView, rebuild a
 * fully wired one, or switch to the diagnostic page. This module owns the incident
 * window, crash-loop decision, event consumption, and recovery ordering.
 */
class RenderProcessRecoveryHost(
    private val webViewProvider: () -> WebView?,
    private val disposeWebView: () -> Unit,
    private val rebuildWebView: () -> Unit,
    private val showCrashDiagnostic: (String) -> Unit,
) {
    private val throttle = RenderCrashThrottle(RENDER_CRASH_RELOAD_WINDOW_MS)

    /**
     * Consume the WebView renderer-death event and choose the recovery path.
     *
     * First crash in a window: destroy the dead instance and rebuild it. A second
     * crash in the same window is treated as a crash loop and falls back to the
     * diagnostic page. Returning true is required; WebView's default response is
     * to finish the whole Activity.
     */
    fun onRenderProcessGone(
        view: WebView,
        detail: RenderProcessGoneDetail,
    ): Boolean {
        if (webViewProvider() !== view) return true
        val now = SystemClock.elapsedRealtime()
        Log.w(
            TAG,
            "UX14 render process gone didCrash=${detail.didCrash()} " +
                "priorityAtExit=${detail.rendererPriorityAtExit()}",
        )
        PerfMarks.mark(
            "N.onRenderProcessGone",
            "didCrash=${detail.didCrash()} count=${throttle.crashesInWindow}",
        )

        val crashesInWindow = throttle.record(now)
        if (crashesInWindow >= 2) {
            Log.w(TAG, "UX14 render crash loop count=$crashesInWindow within=${RENDER_CRASH_RELOAD_WINDOW_MS}ms")
            disposeWebView()
            showCrashDiagnostic(
                "didCrash=${detail.didCrash()} crashes=$crashesInWindow windowMs=$RENDER_CRASH_RELOAD_WINDOW_MS"
            )
        } else {
            Log.i(TAG, "UX16 render crash; recreating WebView (window=${RENDER_CRASH_RELOAD_WINDOW_MS}ms)")
            disposeWebView()
            rebuildWebView()
        }
        return true
    }

    companion object {
        /**
         * Crashes inside one minute are treated as the same incident. The first
         * gets one rebuild attempt; the next is a loop and must not restart forever.
         */
        private const val RENDER_CRASH_RELOAD_WINDOW_MS = 60_000L
        private const val TAG = "OcWebHost"
    }
}
