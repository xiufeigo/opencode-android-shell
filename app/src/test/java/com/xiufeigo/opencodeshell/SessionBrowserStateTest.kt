package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.browser.BrowserLoadState
import com.xiufeigo.opencodeshell.ui.session.browser.SessionBrowserState
import com.xiufeigo.opencodeshell.ui.session.browser.SessionBrowserUrls
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the browser pane's JVM-testable logic
 * (`ui/session/browser/SessionBrowserState.kt`): URL normalization, display
 * host, http/https support gate, and the loading state machine the
 * `WebViewClient` callbacks drive.
 */
class SessionBrowserStateTest {

    @Test
    fun normalizeTrimsAndKeepsHttpSchemes() {
        assertEquals("https://example.com/a", SessionBrowserUrls.normalize("  https://example.com/a "))
        assertEquals("http://example.com", SessionBrowserUrls.normalize("http://example.com"))
    }

    @Test
    fun normalizeAddsHttpsWhenSchemeMissing() {
        assertEquals("https://example.com/path", SessionBrowserUrls.normalize("example.com/path"))
    }

    @Test
    fun normalizeTreatsBlankAndAboutBlankAsEmpty() {
        assertEquals("", SessionBrowserUrls.normalize(""))
        assertEquals("", SessionBrowserUrls.normalize("   "))
        assertEquals("", SessionBrowserUrls.normalize("about:blank"))
    }

    @Test
    fun isSupportedOnlyAdmitsHttpAndHttps() {
        assertTrue(SessionBrowserUrls.isSupported("https://example.com"))
        assertTrue(SessionBrowserUrls.isSupported("http://example.com"))
        assertFalse(SessionBrowserUrls.isSupported("ftp://example.com/x"))
        assertFalse(SessionBrowserUrls.isSupported(""))
    }

    @Test
    fun isSecureFlagsHttpsOnly() {
        assertTrue(SessionBrowserUrls.isSecure("https://example.com"))
        assertFalse(SessionBrowserUrls.isSecure("http://example.com"))
        assertFalse(SessionBrowserUrls.isSecure(""))
    }

    @Test
    fun displayHostExtractsHostAndFallsBackToRaw() {
        assertEquals("example.com", SessionBrowserUrls.displayHost("https://example.com/a?b=1"))
        assertEquals("", SessionBrowserUrls.displayHost(""))
        assertEquals("not a url at all", SessionBrowserUrls.displayHost("not a url at all"))
    }

    @Test
    fun loadStateWalksStartFinish() {
        val state = SessionBrowserState("https://example.com")
        assertEquals(BrowserLoadState.IDLE, state.loadState)
        state.onStart()
        assertEquals(BrowserLoadState.LOADING, state.loadState)
        state.onFinished()
        assertEquals(BrowserLoadState.READY, state.loadState)
    }

    @Test
    fun loadStateWalksStartErrorRetryFinished() {
        val state = SessionBrowserState("https://example.com")
        state.onStart()
        state.onError()
        assertEquals(BrowserLoadState.ERROR, state.loadState)
        state.onRetry()
        assertEquals(BrowserLoadState.LOADING, state.loadState)
        state.onFinished()
        assertEquals(BrowserLoadState.READY, state.loadState)
    }

    @Test
    fun retryOnlyLeavesTheErrorState() {
        val state = SessionBrowserState("https://example.com")
        state.onRetry()
        assertEquals(BrowserLoadState.IDLE, state.loadState)
        state.onStart()
        state.onRetry()
        assertEquals(BrowserLoadState.LOADING, state.loadState)
    }

    @Test
    fun finishWithoutStartIsANoOp() {
        val state = SessionBrowserState("https://example.com")
        state.onFinished()
        assertEquals(BrowserLoadState.IDLE, state.loadState)
    }

    @Test
    fun emptyUrlNeverStartsOrErrors() {
        val state = SessionBrowserState("about:blank")
        assertTrue(state.isEmpty)
        state.onStart()
        assertEquals(BrowserLoadState.IDLE, state.loadState)
        state.onError()
        assertEquals(BrowserLoadState.IDLE, state.loadState)
    }

    @Test
    fun bareHostNormalizesThroughTheState() {
        val state = SessionBrowserState("example.com/docs")
        assertEquals("https://example.com/docs", state.normalizedUrl)
        assertFalse(state.isEmpty)
    }
}
