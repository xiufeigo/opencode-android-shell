package com.xiufeigo.opencodeshell.ui.session.browser

import java.net.URI

/**
 * Pure URL helpers for [SessionBrowserPane], the Android stand-in for upstream
 * `packages/app/src/session/browser/pane.tsx` + `model.ts` (v2 head).
 *
 * Upstream drives the desktop sidecar browser over RPC (`tabs.open`, `reload`,
 * `stop`, `navigate`, tab inventory mirrored into the session layout, dialog
 * overlap hiding the native cutout, `suspended` state). There is no sidecar on
 * Android, so the pane is a local `WebView` viewer over one agent-driven URL;
 * these helpers keep everything the JVM can test (normalization, display host,
 * http/https support gate, load state machine) out of the `@Composable`.
 *
 * Intentional deviations from `pane.tsx` (also noted on the pane):
 * - the address bar is read-only display: the fixed pane contract exposes no
 *   `onNavigate`, so in-page link taps navigate inside the `WebView` and update
 *   the display instead of the upstream editable-submit flow;
 * - an explicit `https`/`http` security indicator: upstream shows none because
 *   the desktop chrome owns it;
 * - `suspended` / dialog-overlap hiding have no Android equivalent and are
 *   omitted; only `http`/`https` load ([isSupported]).
 */
object SessionBrowserUrls {

    /** `pane.tsx:22` — `about:blank` renders as the empty state, never as text. */
    fun normalize(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.equals("about:blank", ignoreCase = true)) return ""
        if (trimmed.contains("://")) return trimmed
        return "https://$trimmed"
    }

    /**
     * Only `http`/`https` load. Manifest edits are out of scope for this pane,
     * so cleartext `http` follows the platform `WebView` default and may
     * surface as the error state on API 28+.
     */
    fun isSupported(normalized: String): Boolean {
        val scheme = normalized.substringBefore("://", "").lowercase()
        return scheme == "http" || scheme == "https"
    }

    /** `true` for `https`; drives the address-bar security indicator. */
    fun isSecure(normalized: String): Boolean =
        normalized.substringBefore("://", "").equals("https", ignoreCase = true)

    /**
     * Host for the address display (`pane.tsx:273-281` shows scheme + rest;
     * mobile width only fits the host, full URL stays on the content
     * description). Falls back to the raw string when it does not parse.
     */
    fun displayHost(normalized: String): String {
        if (normalized.isEmpty()) return ""
        return try {
            URI(normalized).host?.takeIf { it.isNotEmpty() } ?: normalized
        } catch (_: Exception) {
            normalized
        }
    }
}

/** Load phases mirroring `pane.tsx`'s `loading` / `loadError` / page states. */
enum class BrowserLoadState {
    IDLE,
    LOADING,
    READY,
    ERROR,
}

/**
 * JVM-testable load state machine for one pane mount. `WebViewClient`
 * callbacks map 1:1: `onPageStarted` -> [onStart], `onPageFinished` ->
 * [onFinished], `onReceivedError`/`onReceivedHttpError` -> [onError]; the
 * error-state retry button calls [onRetry] before `WebView.reload()`.
 */
class SessionBrowserState(initialUrl: String) {

    val normalizedUrl: String = SessionBrowserUrls.normalize(initialUrl)

    var loadState: BrowserLoadState = BrowserLoadState.IDLE
        private set

    val isEmpty: Boolean get() = normalizedUrl.isEmpty()

    /** `pane.tsx:22-23` — empty (or `about:blank`) never starts a load. */
    fun onStart() {
        if (isEmpty) return
        loadState = BrowserLoadState.LOADING
    }

    fun onFinished() {
        if (loadState == BrowserLoadState.LOADING) loadState = BrowserLoadState.READY
    }

    fun onError() {
        if (!isEmpty) loadState = BrowserLoadState.ERROR
    }

    /** Retry only leaves the error state; a fresh load still goes via [onStart]. */
    fun onRetry() {
        if (loadState == BrowserLoadState.ERROR) loadState = BrowserLoadState.LOADING
    }
}
