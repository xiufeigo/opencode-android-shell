package com.xiufeigo.opencodeshell.web.host

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.webkit.WebView
import androidx.webkit.WebViewCompat

/**
 * Startup recovery state machine for the embedded Web shell.
 *
 * Owns WebView version reads, first-content watchdog scheduling, diagnostic-page
 * transitions, and main-document load-error escalation. The Activity coordinates
 * navigation; this module owns the recovery state that must survive implementation
 * details of the entry screen.
 */
class StartupRecoveryHost(
    private val context: Context,
    private val diagnosticBaseUrl: String,
    private val webViewProvider: () -> WebView?,
) {
    private val appContext: Context
        get() = context.applicationContext

    var versionName: String? = null
        private set
    var major: Int? = null
        private set

    private var watchdogArmed = false
    private var firstContentSeen = false
    private var loadIssuedAtMs = 0L

    private val watchdog = Runnable {
        val reason = WebViewPreflight.stallVerdict(
            elapsedMs = SystemClock.elapsedRealtime() - loadIssuedAtMs,
            contentSeen = firstContentSeen,
        )
        if (reason != null) {
            Log.w(TAG, "G1 watchdog fired: no first content in ${WebViewPreflight.LOAD_STALL_TIMEOUT_MS}ms")
            showDiagnostic(reason, "首屏 ${WebViewPreflight.LOAD_STALL_TIMEOUT_MS}ms 内没有任何内容")
        }
    }

    /** Read the real WebView package version. Unknown stays null and must not mean "too old". */
    fun readWebViewVersion() {
        val pkg = runCatching { WebViewCompat.getCurrentWebViewPackage(appContext) }.getOrNull()
        val name = runCatching { pkg?.versionName }.getOrNull()
        versionName = name
        major = WebViewPreflight.parseMajor(name)
        Log.i(
            TAG,
            "G1 webview version=$name major=$major " +
                "min=${WebViewPreflight.MIN_MAJOR} tooOld=${WebViewPreflight.isTooOld(major)}",
        )
    }

    /** Arm the first-content watchdog for one official-UI navigation. */
    fun arm() {
        val view = webViewProvider() ?: return
        watchdogArmed = true
        firstContentSeen = false
        loadIssuedAtMs = SystemClock.elapsedRealtime()
        view.removeCallbacks(watchdog)
        view.postDelayed(watchdog, WebViewPreflight.LOAD_STALL_TIMEOUT_MS)
    }

    /** Cancel any pending recovery action before replacing or destroying the WebView. */
    fun cancel() {
        watchdogArmed = false
        webViewProvider()?.removeCallbacks(watchdog)
    }

    /** Switch to the diagnostic page; diagnostic pages are not themselves diagnosed. */
    fun showDiagnostic(reason: WebViewPreflight.Reason, detail: String?) {
        watchdogArmed = false
        firstContentSeen = true
        val view = webViewProvider() ?: return
        val url = WebViewPreflight.diagnosticUrl(
            baseUrl = diagnosticBaseUrl,
            reason = reason,
            major = major,
            versionName = versionName,
            detail = detail,
        )
        Log.w(TAG, "G1 showing diagnostic page reason=${reason.wire} detail=$detail")
        PerfMarks.mark("M.g1.diagnostic", "reason=${reason.wire}")
        view.loadUrl(url)
    }

    /** The preflight probe saw real content, so the current navigation is no longer suspect. */
    fun onFirstContent(kind: String?, atMs: Long?) {
        if (firstContentSeen) return
        val view = webViewProvider() ?: return
        firstContentSeen = true
        PerfMarks.mark("M.g1.firstContent", "kind=$kind at=$atMs")
        view.post { view.removeCallbacks(watchdog) }
    }

    /** Escalate a main-document failure only while an official-UI navigation is being watched. */
    fun onLoadError(isForMainFrame: Boolean, detail: String): Boolean {
        val reason = WebViewPreflight.loadErrorVerdict(isForMainFrame) ?: return false
        if (!watchdogArmed) return false
        showDiagnostic(reason, detail)
        return true
    }

    private companion object {
        const val TAG = "OcWebHost"
    }
}
