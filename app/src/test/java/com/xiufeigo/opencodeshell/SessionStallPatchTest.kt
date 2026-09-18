package com.xiufeigo.opencodeshell

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the session-stall (network flap) fix.
 *
 * P0 reconcile: a re-established SSE stream must actively reconcile open
 * sessions (statuses overwritten from the server, stale sessions re-synced),
 * triggered by stream establishment + online + foreground — never by
 * inference, and a no-op off-Android.
 * P1 send retry: a failed prompt send keeps a one-slot resend (fresh
 * messageID, composer untouched) behind the failure toast.
 */
class SessionStallPatchTest {
    private fun patch(name: String): File =
        listOf(
            File("../frontend/patches/$name"),
            File("../../frontend/patches/$name"),
            File("frontend/patches/$name"),
        ).firstOrNull { it.isFile } ?: error("missing $name")

    @Test
    fun reconcileFiresOnStreamEstablished() {
        val text = patch("android-webview-z-session-reconcile.patch").readText()
        assertTrue(text.contains("onStreamEstablished"))
        assertTrue(text.contains("notifyStreamEstablished"))
        assertTrue(text.contains("settleTimer = setTimeout(fireEstablished, 3000)"))
    }

    @Test
    fun reconcileConvergesTriggersAndStaysOffDesktop() {
        val text = patch("android-webview-z-session-reconcile.patch").readText()
        assertTrue(text.contains("reconcileActiveSession"))
        assertTrue(text.contains("addEventListener(\"online\""))
        assertTrue(text.contains("visibilitychange"))
        assertTrue(text.contains("if (!isAndroidWebView()) return Promise.resolve()"))
    }

    @Test
    fun busyIsOverwrittenFromServerNeverInferred() {
        val text = patch("android-webview-z-session-reconcile.patch").readText()
        assertTrue(text.contains("session_status"))
        assertTrue(text.contains("session.sync(sessionID, { force: true })"))
        assertTrue(text.contains("session.fresh(sessionID, RECONCILE_FRESH_MS)"))
        assertTrue(text.contains("RECONCILE_SESSION_LIMIT"))
    }

    @Test
    fun throttleHasMaxWaitAndBackoffIsCapped() {
        val text = patch("android-webview-z-session-reconcile.patch").readText()
        assertTrue(text.contains("RECONCILE_THROTTLE_MS"))
        assertTrue(text.contains("RECONCILE_MAX_WAIT_MS"))
        assertTrue(text.contains("Math.min(reconnectBackoffMs * 2, 5000)"))
    }

    @Test
    fun failedSendKeepsOneSlotResendWithFreshId() {
        val text = patch("android-webview-z-send-retry.patch").readText()
        assertTrue(text.contains("failedResends"))
        assertTrue(text.contains("void sendOnce()"))
        assertTrue(text.contains("void sendOnce(messageID)"))
        assertTrue(text.contains("prompt.toast.promptSendFailed.retry"))
        // Desktop keeps the plain toast.
        assertTrue(text.contains("if (isAndroidWebView())"))
    }

    @Test
    fun retryLabelExistsInShellLocales() {
        val text = patch("android-webview-z-send-retry.patch").readText()
        assertTrue(text.contains("\"prompt.toast.promptSendFailed.retry\": \"Retry\""))
        assertTrue(text.contains("\"prompt.toast.promptSendFailed.retry\": \"重试\""))
    }
}
