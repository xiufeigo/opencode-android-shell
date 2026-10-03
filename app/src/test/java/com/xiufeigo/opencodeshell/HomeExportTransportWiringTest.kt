package com.xiufeigo.opencodeshell

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wiring pins for v43 tC/tD: Home's 导出会话 reported 「尚未连接到服务器」 on a
 * shell that was plainly connected (the Home list was live, the chat ⋮ export on
 * the *same* view model saved a file immediately).
 *
 * The read sampled [ChatViewModel.requireTransport] at tap time. Home never calls
 * `start` (M1: sitting on Home costs no connection) and the session route's
 * `stop` drops both the client and the transport on the way out, so a long-press
 * right after leaving a session could land inside the rebind/probe window — a
 * `null` that [ChatViewModel.fetchExportFrameResult] faithfully reported as a
 * `TransportUnavailableException`, i.e. a diagnosis the user could disprove.
 *
 * v44 tC widened the seam: Home's 删除/重命名 sampled the transport at tap time too,
 * and the delete half of that was the P0 「删除静默无效」
 * (`HomeDeleteTransportV44Test` pins the delete/rename callers), so the helper
 * was renamed `awaitSessionTransport` — it is no longer export-only.
 *
 * `ChatViewModel` is not constructible in a JVM test (Android deps), so this
 * pins the acquisition sequence in source, the way `TransportRetryWiringTest`
 * pins the read seams: bind the client, wait for the probe round that binding
 * kicked, and only then give up — with the one caller-initiated re-probe that
 * heals a stale positive verdict (`maybeReprobe` never does: `isRetryable` is
 * false for a V2 answer).
 */
class HomeExportTransportWiringTest {

    private fun viewModel() =
        File("src/main/java/com/xiufeigo/opencodeshell/ChatViewModel.kt")
            .takeIf { it.isFile }
            ?.readText()
            ?: File("app/src/main/java/com/xiufeigo/opencodeshell/ChatViewModel.kt").readText()

    /** The body of a member declaration, from its signature to the next member's KDoc. */
    private fun window(text: String, start: String): String {
        val from = text.indexOf(start)
        assertTrue("missing anchor: $start", from >= 0)
        val to = text.indexOf("\n    /**", from)
        return if (to > from) text.substring(from, to) else text.substring(from)
    }

    @Test
    fun theExportReadAcquiresTheTransportBeforeItCanBeMissing() {
        val body = window(viewModel(), "suspend fun fetchExportFrameResult(")
        assertTrue("the read asks for the transport through the acquiring seam", body.contains("awaitSessionTransport()"))
        assertFalse(
            "a bare requireTransport here is the tap-time sample that misreported the connection",
            body.contains("val transport = requireTransport()"),
        )
    }

    @Test
    fun theAcquiringFollowsTheListSessionsSequence() {
        val helper = window(viewModel(), "private suspend fun awaitSessionTransport()")
        // A live transport short-circuits, so the common case costs nothing.
        assertTrue(helper.contains("requireTransport()?.let { return it }"))
        // Home never called start(), so the client may be gone with the transport.
        assertTrue("the client is (re)bound first", helper.contains("ensureClient()"))
        // Then the round that binding kicked is awaited — the listSessions order.
        assertTrue(
            "the probe round settles before the transport is declared missing",
            helper.indexOf("ensureClient()") < helper.indexOf("awaitProbeSettled()"),
        )
        // A stale positive verdict is not retryable, so maybeReprobe stays a
        // no-op here; only the caller-initiated retry can heal it.
        assertTrue("the stale-verdict escape is the explicit re-probe", helper.contains("retryProbe()"))
    }

    @Test
    fun theMissingTransportIsStillReportedWhenNoRoundCanHealIt() {
        val helper = window(viewModel(), "private suspend fun awaitSessionTransport()")
        assertTrue("the attempt ends on a fresh require, not on the first one", helper.trimEnd().endsWith("return requireTransport()\n    }"))
    }
}
