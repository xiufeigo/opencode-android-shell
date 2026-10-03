package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.home.NewSessionCreatePolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v35 tB BUG-3: with the connection down, 「Create session」 spun on
 * 「Creating a session…」 and only reported 「Not connected to a server」 at the
 * very end of the wait (`research/v35_verify/tB_lifecycle/54_still_creating.png`).
 *
 * The measured cause is the *stale* generation verdict: `stop()` clears the
 * transport but leaves `generation == V2`, so the gate that waits for a V2
 * verdict passed instantly and `awaitTransportLive` then polled a transport
 * nobody was installing — `maybeReprobe` refuses to re-probe while a definitive
 * verdict is latched. Two rules fix it, both pinned here:
 *
 * - the wait **kicks the probe itself** ([ChatViewModel.retryProbe], the
 *   explicit gesture that outranks the latch), so the window is bounded by a
 *   real probe round instead of a dead poll;
 * - the wait **gives up as soon as the probe settles a negative verdict**
 *   ([NewSessionCreatePolicy.shouldAbandonTransportWait] over
 *   [ChatViewModel.transportSettledNegative]) and answers with the transport's
 *   own copy, so the reason is the real one (unreachable / auth / not 2.x) and
 *   it arrives in the first poll.
 */
class NewSessionFastFailTest {

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()?.replace("\r\n", "\n")
            ?: error("missing $relative")

    private fun window(src: String, from: String, to: String): String {
        val start = src.indexOf(from)
        assertTrue("missing window start: $from", start >= 0)
        val end = src.indexOf(to, start + from.length)
        assertTrue("missing window end: $to", end > start)
        return src.substring(start, end)
    }

    // ------------------------------------------- 1. the abandon rule is pure

    @Test
    fun aSettledNegativeVerdictEndsTheWait() {
        assertTrue(
            NewSessionCreatePolicy.shouldAbandonTransportWait(
                transportLive = false,
                verdictSettledNegative = true,
            ),
        )
        assertFalse(
            "a probe still in flight is not a verdict — keep waiting",
            NewSessionCreatePolicy.shouldAbandonTransportWait(
                transportLive = false,
                verdictSettledNegative = false,
            ),
        )
        assertFalse(
            "a live transport is the thing we were waiting for",
            NewSessionCreatePolicy.shouldAbandonTransportWait(
                transportLive = true,
                verdictSettledNegative = true,
            ),
        )
    }

    // ------------------------------- 2. only a settled UNKNOWN is negative

    @Test
    fun onlyASettledUnknownCountsAsANegativeVerdict() {
        val vm = source("src/main/java/com/xiufeigo/opencodeshell/ChatViewModel.kt")
        val body = window(vm, "fun transportSettledNegative()", "private fun maybeReprobe()")
        assertTrue("no transport is the premise", body.contains("transportRef.get() == null"))
        assertTrue("a probe in flight is not a verdict", body.contains("!probing.get()"))
        assertTrue(
            "a settled V2 verdict over a cleared transport is recoverable — `stop()` leaves it",
            body.contains("lastProbe?.generation == ServerGeneration.UNKNOWN"),
        )
    }

    // --------------------------------------- 3. the wait itself fails fast

    @Test
    fun theWaitKicksTheProbeAndBailsOnASettledVerdict() {
        val screen = source("src/main/java/com/xiufeigo/opencodeshell/ui/home/NewSessionScreen.kt")
        val body = window(screen, "private suspend fun awaitTransportLive(", "/** One create attempt:")
        assertTrue(
            "the stale-verdict window has no probe running: the wait must start one",
            body.contains("if (!transportLive(chatViewModel)) chatViewModel.retryProbe()"),
        )
        assertTrue(body.contains("NewSessionCreatePolicy.shouldAbandonTransportWait("))
        assertTrue(body.contains("chatViewModel.transportSettledNegative()"))
        assertTrue("the poll interval survives", body.contains("NewSessionCreatePolicy.TRANSPORT_POLL_MS"))
    }

    @Test
    fun theGenerationGateDoesNotSleepOutASettledNegativeVerdict() {
        val screen = source("src/main/java/com/xiufeigo/opencodeshell/ui/home/NewSessionScreen.kt")
        val body = window(screen, "val generationReady = if (", "if (!generationReady) {")
        assertTrue(
            "the automatic re-issue would otherwise burn TRANSPORT_WAIT_MS on a dead connection",
            body.contains("chatViewModel.transportSettledNegative()"),
        )
        assertTrue(body.contains("withTimeoutOrNull(NewSessionCreatePolicy.TRANSPORT_WAIT_MS)"))
        assertTrue(body.contains("chatViewModel.generation.value == ServerGeneration.V2"))
    }

    @Test
    fun theCreateGateReportsTheTransportCopyInsteadOfAGenericLine() {
        val screen = source("src/main/java/com/xiufeigo/opencodeshell/ui/home/NewSessionScreen.kt")
        assertTrue(screen.contains("fun noTransportDetail(): String = context.getString(transportFailureRes("))
        assertTrue(
            "the reason is resolved when the failure is reported",
            screen.contains("failureDetail = noTransportDetail()"),
        )
        assertTrue(
            "NOT_READY prefers the transport's own copy",
            screen.contains("failureDetail ?: stringResource(R.string.oc_home_no_server)"),
        )
        val res = window(screen, "private fun transportFailureRes(", "private fun transportLive(")
        assertTrue("an unreachable host is its own copy", res.contains("TransportFailure.Unreachable -> R.string.oc_server_error_connect"))
        assertTrue("so is an auth wall", res.contains("TransportFailure.AuthRequired -> R.string.oc_server_error_auth"))
        assertTrue("and a probe that has not landed yet", res.contains("TransportFailure.ProbePending -> R.string.oc_summary_status_connecting"))
    }

    @Test
    fun theAutomaticReissueIsUnchanged() {
        // The fast-fail must not have loosened the double-create guard: only a
        // not-ready attempt is re-issued, once.
        assertTrue(NewSessionCreatePolicy.shouldAutoRetry(1, NewSessionCreatePolicy.Failure.NOT_READY))
        assertFalse(NewSessionCreatePolicy.shouldAutoRetry(2, NewSessionCreatePolicy.Failure.NOT_READY))
        assertFalse(NewSessionCreatePolicy.shouldAutoRetry(1, NewSessionCreatePolicy.Failure.TIMED_OUT))
        assertFalse(NewSessionCreatePolicy.shouldAutoRetry(1, NewSessionCreatePolicy.Failure.ERRORED))
    }
}
