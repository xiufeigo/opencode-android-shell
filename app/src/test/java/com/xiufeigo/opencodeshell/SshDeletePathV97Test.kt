package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.servers.SshAuthMethod
import com.xiufeigo.opencodeshell.ui.servers.SshConnection
import com.xiufeigo.opencodeshell.ui.servers.SshConnectionConfig
import com.xiufeigo.opencodeshell.ui.servers.SshConnectionStatus
import com.xiufeigo.opencodeshell.ui.servers.SshForgetState
import com.xiufeigo.opencodeshell.ui.servers.SshStage
import com.xiufeigo.opencodeshell.ui.servers.SshTunnel
import com.xiufeigo.opencodeshell.ui.servers.sshForgetReleasesTunnel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v98 D3 — 「服务器条目的 ⋮→删除完全不工作」.
 *
 * The v97 round fixed the *logic* half (a zombie dial kept publishing for a
 * deleted row) and said so itself: the geometry half — why the confirm bar was
 * not reachable — was never reproduced without a device, so the layout was left
 * alone (`research/v97_fix/SSH_RECONNECT_FIX.md` §6.2).
 *
 * The v98 device run found that half, and it is the whole of the current
 * defect: `emulator-5554`, `research/v97_verify/5554_v98ssh/`:
 *
 * ```
 * body scroll viewport        [43,800][1038,1937]
 * row p (2nd SSH row) ⋮      [780,1827][906,1953]     <- already at the fold
 * => the in-flow bar lands at y≈1965: off-screen
 * next list row              [169,2000][560,2003]     <- 3 px sliver = clipped
 * 07_D3.txt: 5/5 attempts, 3 click strategies, 2 row states -> dialogNodes=0
 * ```
 *
 * All five attempts were on the **lower** row; the first row's bar composes at
 * y≈1570-1730, inside the viewport, which is why the defect reads as
 * "delete never works" while being a fold problem.
 *
 * The bar is composed — it is not missing, and the state machine is not the
 * fault — it is simply past the fold, in a panel whose body owns no scroller.
 * So the fix is to make the armed bar reveal itself, and this suite pins:
 *
 *  1. ⋮ → Delete reaches the armed (confirming) state in **every** stage —
 *     never gated on the row's state;
 *  2. only the confirm tap reaches the removal, and it does reach it;
 *  3. the v97 `abort()`-first release order cannot swallow the confirm flow.
 *
 * Plus the regression this round adds: the bar must reveal itself, so the
 * second and later rows are as deletable as the first.
 */
class SshDeletePathV97Test {

    private fun source(relative: String): String =
        listOf(File("app/$relative"), File(relative))
            .firstOrNull { it.isFile }
            ?.readText()?.replace("\r\n", "\n")
            ?: error("missing $relative")

    private fun host() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/servers/SshTunnelHost.kt")

    private fun forgetUi() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/servers/SshTunnelForget.kt")

    /** The card itself, from its declaration to the end of the host file. */
    private fun card() = host().substringAfter("fun SshTunnelCard(")

    /** Just the `SshTunnelHost.forget` body. */
    private fun forgetBody() = host()
        .substringAfter("fun forget(id: String) {")
        .substringBefore("\n    }\n")

    /** Just the confirm bar composable. */
    private fun confirmBar() = forgetUi().substringAfter("fun SshTunnelForgetConfirm(")

    /**
     * The same source with `//` line comments dropped: the negative pins below
     * assert *absent code*, and this file's own reasoning mentions the very
     * tokens they forbid (`verticalScroll`, `abort()`), so reading the raw text
     * would match a comment and fail a fix that is actually correct.
     */
    private fun withoutLineComments(src: String): String =
        src.lines().joinToString("\n") { it.substringBefore("//") }

    private fun tunnel(id: String, stage: SshStage): SshTunnel {
        val config = SshConnectionConfig(
            id = id,
            target = "10.255.255.1:22",
            name = "",
            auth = SshAuthMethod.PASSWORD,
            password = "secret",
        )
        return SshTunnel(config, SshConnection(config), SshConnectionStatus(stage = stage))
    }

    /** Every stage the row can be in — the states the report calls out plus the rest. */
    private val allStages = listOf(
        SshStage.DISCONNECTED,
        SshStage.INCOMPATIBLE,
        SshStage.FAILED,
        SshStage.CONNECTING,
        SshStage.CHECKING,
        SshStage.DOWNLOADING,
        SshStage.UPLOADING,
        SshStage.STARTING,
        SshStage.AUTHENTICATION,
        SshStage.READY,
    )

    // ---------------------------------------- ① ⋮ → Delete always arms the row

    @Test
    fun theOverflowEntryArmsTheRowInEveryStage() {
        for (stage in allStages) {
            val row = tunnel("row-${stage.name}", stage)
            // The ⋮ tap only arms; the stage never enters the decision.
            val armed = SshForgetState().ask(row.config.id)
            assertEquals(
                "stage ${stage.name} must still arm its row",
                row.config.id,
                armed.armed,
            )
        }
    }

    @Test
    fun theThreeReportedRowStatesAllArm() {
        // `Connected` / `Authentication required` / `Connecting` — the three
        // states the device round exercised, spelled as stages here.
        for (stage in listOf(SshStage.READY, SshStage.AUTHENTICATION, SshStage.CONNECTING)) {
            val row = tunnel("row-${stage.name}", stage)
            assertNotNull(
                "stage ${stage.name} must reach the confirming state",
                SshForgetState().ask(row.config.id).armed,
            )
        }
    }

    @Test
    fun theConfirmIsGatedOnTheArmAndNothingElse() {
        val body = card()
        // The bar shows for the armed row — the id, and nothing about the stage.
        assertTrue("the bar is drawn for the armed id", body.contains("if (forget.armed == tunnel.config.id)"))
        // Neither row shape may hide the entry: both branches arm identically.
        assertEquals(
            "both the stacked and the one-line shape offer ⋮ → Delete",
            2,
            Regex("forget = forget\\.ask\\(tunnel\\.config\\.id\\)").findAll(body).count(),
        )
        // The stage reaches the bar as copy only (whether a tunnel is released
        // first) — it can never decide whether the bar is shown.
        assertTrue(body.contains("releaseFirst = sshForgetReleasesTunnel(tunnel.status.stage)"))
    }

    @Test
    fun theStageOnlyChangesTheCopyAndNeverTheEntry() {
        // AUTHENTICATION (the D3 state) is an in-flight tunnel, so it
        // disconnects first — and is still deletable, which is the pin.
        assertTrue(sshForgetReleasesTunnel(SshStage.AUTHENTICATION))
        assertTrue(sshForgetReleasesTunnel(SshStage.CONNECTING))
        assertTrue(sshForgetReleasesTunnel(SshStage.READY))
        assertFalse(sshForgetReleasesTunnel(SshStage.DISCONNECTED))
        assertFalse(sshForgetReleasesTunnel(SshStage.FAILED))
        assertFalse(sshForgetReleasesTunnel(SshStage.INCOMPATIBLE))
    }

    // --------------------------------------- the v98 fix: the bar is reachable

    @Test
    fun theArmedBarScrollsItselfIntoView() {
        val bar = confirmBar()
        // The D3 fix: an in-flow bar below a low row composes past the fold,
        // so arming must reveal it. Without these two the second and later
        // rows are undeletable on a phone.
        assertTrue("the bar registers as a bring-into-view target", bar.contains("bringIntoViewRequester(reveal)"))
        assertTrue("arming reveals the bar", bar.contains("reveal.bringIntoView()"))
        // The request rides the panel's own single scroller, not a new one —
        // a second scroller would be the v32 single-scroll regression. Read
        // from the comment-stripped source: this file's own comment names the
        // forbidden token while explaining exactly why it is forbidden.
        val code = withoutLineComments(bar)
        assertFalse(
            "the bar must not add a scroller of its own",
            code.contains("verticalScroll") || code.contains("rememberScrollState"),
        )
    }

    @Test
    fun theBarIsRevealedOnEveryArmNotOnlyTheFirst() {
        val bar = confirmBar()
        // The reveal is keyed on the bar's own entry into composition, which
        // happens once per arm — not on a remembered flag that would fire only
        // the first time the process armed a row.
        assertTrue("reveal is a fresh requester per composition", bar.contains("remember { BringIntoViewRequester() }"))
        assertTrue("the reveal runs when the bar appears", bar.contains("LaunchedEffect(Unit)"))
    }

    // ------------------------------------------- ② the confirm really deletes

    @Test
    fun onlyTheConfirmedTapHandsTheIdBack() {
        val armed = SshForgetState().ask("E2ECHK5554v98-p")
        assertEquals("E2ECHK5554v98-p", armed.confirm("E2ECHK5554v98-p"))
        // Cancel, another row's tap, no arm at all: nothing is forgotten.
        assertNull(armed.cancel().confirm("E2ECHK5554v98-p"))
        assertNull(armed.confirm("E2ECHK5554v98-k"))
        assertNull(SshForgetState().confirm("E2ECHK5554v98-p"))
        // An empty id cannot be armed in the first place (the ⋮ tap on a row
        // whose config never got one would otherwise arm a confirm that can
        // never match).
        assertNull(SshForgetState().ask("").confirm(""))
    }

    @Test
    fun theConfirmButtonIsTheOnlyCallerOfTheRemoval() {
        val body = card()
        // The ⋮ tap region — between the arm and the bar — must not remove.
        val armToConfirm = withoutLineComments(
            body.substringAfter("forget = forget.ask(tunnel.config.id)")
                .substringBefore("SshTunnelForgetConfirm("),
        )
        assertFalse("the ⋮ tap must not remove anything", armToConfirm.contains("forget("))
        // The bar's confirm button is what calls it, once.
        assertTrue(
            "the confirm button is the one removal call path",
            body.contains("confirmSshForget(forget, tunnel.config.id, host, removePersisted)"),
        )
    }

    @Test
    fun theRemovalDropsThePersistedRecordBeforeTheLiveTunnel() {
        val helper = forgetUi().substringAfter("fun confirmSshForget(")
        assertTrue("the store seam is honoured", helper.contains("removePersisted(target)"))
        assertTrue("the live tunnel is released", helper.contains("host.forget(target)"))
        assertTrue(
            "removal-before-forget, or a seeded row resurrects on the next seed",
            helper.indexOf("removePersisted(target)") < helper.indexOf("host.forget(target)"),
        )
        // A tap that answers no arm returns null and touches neither.
        assertTrue(helper.contains("armed.confirm(id) ?: return null"))
    }

    // ------------------------- ③ the v97 abort-first release cannot eat the confirm

    @Test
    fun abortingTheParkedDialDoesNotGateTheConfirmFlow() {
        val body = forgetBody()
        // v97 D3: the parked dial is invalidated before the release.
        assertTrue("forget aborts the parked dial", body.contains("abort()"))
        assertTrue(
            "abort runs before the release, so nothing the zombie raises is published",
            body.indexOf("abort()") < body.indexOf("disconnect(id)"),
        )
        // v91's release order is untouched — the drop is still last.
        assertTrue("the watcher is still cancelled", body.contains("watchJobs.remove(id)?.cancel()"))
        assertTrue("the row is still dropped", body.contains("sshTunnelsWithout"))
    }

    @Test
    fun theArmSurvivesTheAbortBecauseTheyAreDifferentStages() {
        // The v97 `abort()` runs inside `forget`, i.e. *after* the user has
        // already confirmed. It cannot pre-empt the bar: the ⋮ tap only calls
        // `ask`, and `ask` is the single writer of the armed state.
        val body = card()
        val armLine = "forget = forget.ask(tunnel.config.id)"
        assertTrue("arming is ask-only", body.contains(armLine))
        // Nothing between the ⋮ tap and the bar aborts, disconnects or forgets.
        val armToConfirm = withoutLineComments(
            body.substringAfter(armLine).substringBefore("SshTunnelForgetConfirm("),
        )
        for (forbidden in listOf("abort(", "disconnect(", "host.forget", "confirmSshForget")) {
            assertFalse("the ⋮ tap must not run `$forbidden`", armToConfirm.contains(forbidden))
        }
    }

    @Test
    fun theReleaseHappensInsideTheConfirmedRemovalNotOnArm() {
        // `sshForgetReleasesTunnel` decides the copy; the actual release is
        // `SshTunnelHost.forget` on the confirmed path, so a cancelled or
        // unreachable bar can never tear a live tunnel down.
        val body = card()
        assertFalse(
            "arming must not disconnect the row",
            body.substringAfter("fun SshTunnelCard(").substringBefore("if (forget.armed")
                .contains("host.disconnect(tunnel.config.id)"),
        )
        // …while the row's own Reconnect / Disconnect buttons still do.
        assertTrue(body.contains("onClick = { host.reconnect(tunnel.config.id) }"))
        assertTrue(body.contains("onClick = { host.disconnect(tunnel.config.id) }"))
    }
}
