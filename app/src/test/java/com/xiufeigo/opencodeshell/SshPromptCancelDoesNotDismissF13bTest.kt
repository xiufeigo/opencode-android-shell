package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.servers.SshAuthMethod
import com.xiufeigo.opencodeshell.ui.servers.SshConnectEvent
import com.xiufeigo.opencodeshell.ui.servers.SshConnection
import com.xiufeigo.opencodeshell.ui.servers.SshConnectionConfig
import com.xiufeigo.opencodeshell.ui.servers.SshConnectionStatus
import com.xiufeigo.opencodeshell.ui.servers.SshPrompt
import com.xiufeigo.opencodeshell.ui.servers.SshStage
import com.xiufeigo.opencodeshell.ui.servers.reduceSshConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * F13b — the SSH challenge card's **Cancel** used to close the dialog, and
 * closing the dialog *is* the teardown.
 *
 * `SshDialog`'s `DisposableEffect(Unit)` (`SshDialog.kt:175-185`) is upstream's
 * `controller.ts` `cancel`: when the dialog leaves the composition and the line
 * is not `READY` yet, it runs `connectJob?.cancel()` + `cancelPrompt()` +
 * `disconnect()`. The card's `onCancel` therefore could not also call
 * `onDismiss()` — that is what tore down a *successful* attempt. The device
 * shape: Continue → `respondPrompt` → `PromptResolved` → `prompt = null` drops
 * the card → the `DisposableEffect` fired `cancel()+disconnect()` before READY
 * ever arrived (the server logged `Accepted password`, the app showed
 * `Could not establish`).
 *
 * The fixed contract, pinned here in three layers:
 *
 *  1. the **model** — a cancel is the `PromptResolved` leg, which clears the
 *     challenge and moves *no* field of the status; `Disconnect` (the dismiss
 *     half) is a different event with a different outcome;
 *  2. the **real connection** — [SshConnection.cancelPrompt] with nothing on
 *     screen is inert, and it is not a close;
 *  3. the **dialog** — `onCancel` answers the challenge and never dismisses,
 *     `onDismiss` stays reserved for READY and the explicit close, and the
 *     `DisposableEffect` really is the teardown that makes (3) matter.
 *
 * Layer 3 is a source-level pin because the card lives inside a `@Composable`
 * a plain JVM test cannot call — the same split [SshPromptOfferV94Test] /
 * [OcKeyboardOptionsTest] use.
 */
class SshPromptCancelDoesNotDismissF13bTest {

    private fun config() = SshConnectionConfig(
        id = "box",
        target = "ssh deploy@box.example",
        name = "box",
        auth = SshAuthMethod.PASSWORD,
    )

    private fun challenge(confirm: Boolean = false) =
        SshPrompt(id = "p1", text = "Password: ", confirm = confirm)

    private fun dialling() = reduceSshConnection(SshConnectionStatus(), SshConnectEvent.Start)

    private fun challenged(confirm: Boolean = false) =
        reduceSshConnection(dialling(), SshConnectEvent.Prompted(challenge(confirm)))

    // ------------------------------------------- 1. the model: cancel ≠ close

    @Test
    fun cancellingAChallengeClearsOnlyTheChallenge() {
        val during = challenged()
        assertEquals("a challenge parks the dial, it does not advance it", SshStage.CONNECTING, during.stage)
        assertEquals(challenge(), during.prompt)

        val after = reduceSshConnection(during, SshConnectEvent.PromptResolved(challenge().id))
        // The card's own going-away: the challenge is answered and withdrawn …
        assertNull(after.prompt)
        assertNull(after.hostKey)
        // … and not one other field of the status moved.
        assertEquals(during.stage, after.stage)
        assertEquals(during.error, after.error)
        assertEquals(during.localUrl, after.localUrl)
        // The prompt is the *only* thing the cancel changed.
        assertEquals(during.copy(prompt = null), after)
    }

    @Test
    fun theCancelLegNeverSettlesTheLine() {
        // Both prompt kinds the card serves: the host-key trust question and the
        // secret challenge. Cancelling either must leave the dial in flight.
        for (confirm in listOf(false, true)) {
            val during = challenged(confirm)
            val after = reduceSshConnection(during, SshConnectEvent.PromptResolved(challenge(confirm).id))
            assertTrue(
                "confirm=$confirm: a cancel keeps the dial in flight",
                after.stage == SshStage.CONNECTING || after.stage == SshStage.AUTHENTICATION,
            )
            assertNotEquals("confirm=$confirm: a cancel is never a close", SshStage.DISCONNECTED, after.stage)
            assertNotEquals("confirm=$confirm: a cancel never fabricates a tunnel", SshStage.READY, after.stage)
            assertNull(after.prompt)
        }
    }

    @Test
    fun releasingTheLineIsTheOnlyLegThatEndsTheDial() {
        val after = reduceSshConnection(challenged(), SshConnectEvent.PromptResolved(challenge().id))
        // `SshConnectEvent.Disconnect` is what `SshConnection.disconnect()` emits —
        // the dismiss/teardown half. It is a *different event* from the answer a
        // cancel produces, and it is the one that ends the line.
        val released = reduceSshConnection(after, SshConnectEvent.Disconnect)
        assertEquals(SshStage.DISCONNECTED, released.stage)
        assertNull(released.localUrl)
    }

    @Test
    fun thePreFixShapeCancelThenTearDownIsWhatLostTheTunnel() {
        // Reproduces the old `onCancel = { cancelPrompt(); onDismiss() }` at the
        // model level: answer the challenge, then let the dialog's `onDispose`
        // run — which calls `disconnect()`.
        val ready = reduceSshConnection(
            dialling(),
            SshConnectEvent.Ready(localUrl = "http://127.0.0.1:2222", latencyMs = 42, at = 1L),
        )
        assertEquals(SshStage.READY, ready.stage)

        // The teardown the card's disappearance caused: the forward is gone.
        val dismissed = reduceSshConnection(ready, SshConnectEvent.Disconnect)
        assertEquals(SshStage.DISCONNECTED, dismissed.stage)
        assertNull(dismissed.localUrl)

        // The cancel leg, from that same ready line, is a no-op — it cannot
        // reach the status at all, because no challenge is on screen. The
        // reverse pin: a cancel is not a close, so re-adding `onDismiss()` to
        // `onCancel` is the only way to reach the outcome above from there.
        assertEquals(ready, reduceSshConnection(ready, SshConnectEvent.PromptResolved("p1")))
    }

    // ---------------------------------------- 2. the real connection is inert

    @Test
    fun aRealCancelLeavesARealConnectionExactlyAsItWas() {
        // `knownHostsFile` is not passed: the shim reads it at construction and
        // a cancel never touches the repository.
        val connection = SshConnection(config())
        val before = connection.state.value
        assertNull(before.prompt)

        connection.cancelPrompt()

        val after = connection.state.value
        assertEquals("a cancel with no challenge on screen changes nothing", before, after)
        assertNull(after.prompt)
        // It is not a close: the line was never touched, nothing was published.
        assertNull(after.localUrl)
        assertNull(after.error)
        assertNotEquals(SshStage.READY, after.stage)
    }

    @Test
    fun aRealCancelAndARealDisconnectAreDifferentCallsOnTheSameConnection() {
        // The two halves, from the same config, side by side.
        val cancelled = SshConnection(config())
        cancelled.cancelPrompt()
        val released = SshConnection(config())
        released.disconnect()

        // `disconnect()` is the teardown the dialog's `onDispose` runs and the
        // one `onDismiss()` used to trigger from the card's cancel …
        assertEquals(SshStage.DISCONNECTED, released.state.value.stage)
        // … while the cancel half leaves the status untouched.
        assertEquals(SshConnection(config()).state.value, cancelled.state.value)
    }

    // ------------------------------------------------ 3. the dialog's wiring

    @Test
    fun theDialogsCancelAnswersTheChallengeAndNeverDismisses() {
        val cancel = card().substringAfter("onCancel = {").substringBefore("},")
        assertTrue(
            "the card's cancel must answer the challenge",
            cancel.contains("connection?.cancelPrompt()"),
        )
        assertFalse(
            "F13b: onCancel must not dismiss — the dismissal IS the teardown " +
                "(DisposableEffect → cancel() + disconnect()), so a cancel tore down a " +
                "successful attempt before READY ever arrived",
            cancel.contains("onDismiss"),
        )
    }

    @Test
    fun onDismissStaysReservedForReadyAndTheExplicitClose() {
        // Comments stripped: the F13b rationale names `onDismiss()` in prose, and
        // only the code may be counted.
        val code = dialog().lines()
            .filterNot { it.trimStart().startsWith("//") }
            .joinToString("\n")
        assertEquals(
            "onDismiss may only be the parameter, the READY auto-close and the footer's Cancel",
            3,
            count(code, "onDismiss"),
        )
        assertTrue(
            "READY is the one stage that closes the dialog on its own",
            code.contains("if (status.stage == SshStage.READY) onDismiss()"),
        )
        assertTrue("the footer's Cancel is the explicit close", code.contains("onClick = onDismiss,"))
    }

    @Test
    fun dismissingTheDialogIsWhatTearsTheTunnelDown() {
        val dispose = dialog()
            .substringAfter("DisposableEffect(Unit) {")
            .substringBefore("    }\n\n    fun submit()")
        assertTrue(dispose.contains("if (status.stage != SshStage.READY) {"))
        // This is the causal chain that made `onDismiss()` on the card's cancel
        // fatal: dismissing runs all three, and it runs them on an attempt that
        // was still on its way to READY.
        assertTrue(dispose.contains("connectJob?.cancel()"))
        assertTrue(dispose.contains("connection?.cancelPrompt()"))
        assertTrue(dispose.contains("connection?.disconnect()"))
        assertTrue(
            "the teardown is gated on not being READY — which is why the dialog closes itself there",
            dispose.indexOf("status.stage != SshStage.READY") < dispose.indexOf("disconnect()"),
        )
    }

    // ---------------------------------------------------------------- helpers

    private fun source(relative: String): String =
        listOf(File("app/$relative"), File(relative))
            .firstOrNull { it.isFile }
            ?.readText()?.replace("\r\n", "\n")
            ?: error("missing $relative")

    private fun dialog() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/servers/SshDialog.kt")

    /** The `SshAuthenticate(...)` the dialog draws for a live challenge. */
    private fun card(): String =
        dialog()
            .substringAfter("SshAuthenticate(")
            .substringBefore("windowHeightDp = promptWindowHeightDp,")

    private fun count(src: String, needle: String): Int =
        Regex(Regex.escape(needle)).findAll(src).count()
}
