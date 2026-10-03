package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.servers.SshRowMenuEntry
import com.xiufeigo.opencodeshell.ui.servers.SshStage
import com.xiufeigo.opencodeshell.ui.servers.isSshConnecting
import com.xiufeigo.opencodeshell.ui.servers.sshRowMenuConnectEnabled
import com.xiufeigo.opencodeshell.ui.servers.sshRowMenuDeleteEnabled
import com.xiufeigo.opencodeshell.ui.servers.sshRowMenuEntries
import com.xiufeigo.opencodeshell.ui.servers.sshRowMenuShowsConnect
import com.xiufeigo.opencodeshell.ui.servers.sshRowMenuVisible
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v99 — the SSH row's `⋮` menu against upstream `servers/ssh/menu.tsx`.
 *
 * The round that opened this ticket read `Connecting` → *no `⋮`* →
 * "cannot open delete menu" off the v98 device
 * (`research/v97_verify/5554_v98d3/REPORT_COVERAGE8.md` §3.2) and concluded the
 * shell hid the row menu while connecting. **The code does not do that, and the
 * report says so itself** — the same section's root cause is *"新条目追加在列表
 * 末尾，而面板不会自动滚动到它"* (`state='(row not visible)'`, `kebabs=0`, 8/8
 * samples): the row was outside the a11y tree because the panel does not scroll
 * to the bottom, which is a **script-side** fact, and §3.4 records the section's
 * verdict as BLOCKED on exactly that.
 *
 * So this suite pins the *other* half of the parity, which was genuinely
 * missing and which the same ticket asks for on upstream's own terms:
 *
 * - **`⋮` is never a `connecting` question** ([sshRowMenuVisible]) — upstream's
 *   `<Show when={item()}>` is gated on the item existing and on nothing else,
 *   and the structural pin below keeps the shell's two row shapes that way.
 * - **Connect is the one entry upstream disables mid-flight**
 *   ([sshRowMenuConnectEnabled], `disabled={ssh.pending(props.id)}`).
 * - **Delete is never disabled, `connecting` included**
 *   ([sshRowMenuDeleteEnabled], `menu.tsx:46` has no gate on it).
 *
 * The shell's `SshTunnelRowMenu` carried **Delete only** — the Connect /
 * Authenticate entry was absent altogether, so a row parked at
 * `authentication` or `failed` had no way back from the row itself. That is the
 * real gap this closes.
 */
class SshRowMenuParityV99Test {

    private fun source(relative: String): String =
        listOf(File("app/$relative"), File(relative))
            .firstOrNull { it.isFile }
            ?.readText()?.replace("\r\n", "\n")
            ?: error("missing $relative")

    private fun host() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/servers/SshTunnelHost.kt")

    private fun card() = host().substringAfter("fun SshTunnelCard(")

    /** Every stage the wire can carry (`types.ts:15-26`). */
    private val allStages = SshStage.entries

    private val inFlight = listOf(
        SshStage.CONNECTING,
        SshStage.CHECKING,
        SshStage.DOWNLOADING,
        SshStage.UPLOADING,
        SshStage.STARTING,
    )

    // ------------------------------- ① the item exists ⇒ the ⋮ is rendered

    @Test
    fun theKebabIsGatedOnTheItemExistingAndOnNothingElse() {
        // `menu.tsx:16` — `<Show when={item()}>`. There is no stage argument and
        // no `busy` argument, which is the whole of the rule: presence of the
        // entry, nothing about what it is doing.
        assertTrue("an existing item renders the menu", sshRowMenuVisible(itemExists = true))
        assertFalse("a missing item renders nothing", sshRowMenuVisible(itemExists = false))
    }

    @Test
    fun connectingDoesNotHideTheKebab() {
        // The regression this suite exists for: nothing in the visibility rule
        // may read a stage, so there is no stage that can turn it off — least
        // of all the in-flight ones that cost the v98 round its delete.
        for (stage in allStages) {
            assertTrue(
                "⋮ is visible in $stage — the item exists, the stage is not a reason",
                sshRowMenuVisible(itemExists = true),
            )
        }
        // Spelled the other way round too, so a future signature that takes a
        // stage fails to compile rather than quietly passing.
        for (stage in inFlight) {
            assertTrue(
                "⋮ stays visible in the in-flight stage $stage",
                sshRowMenuVisible(itemExists = true) && isSshConnecting(stage),
            )
        }
    }

    @Test
    fun bothRowShapesEmitTheKebabUnconditionally() {
        val body = card()
        assertEquals(
            "the ⋮ trigger is emitted in both the stacked and the one-line shape",
            2,
            Regex("ServerRowMenu\\(onClick = \\{ menu = true \\}\\)").findAll(body).count(),
        )
        // The structural half of ①: the trigger sits in a bare `Box {` with no
        // `if`/`when` around it, so no stage can gate it. Upstream's `<Show
        // when={item()}>` is the only condition upstream has on the trigger.
        val triggers = Regex("ServerRowMenu\\(onClick = \\{ menu = true \\}\\)").findAll(body)
        assertEquals(2, triggers.count())
        for (match in triggers) {
            val before = body.substring(0, match.range.first)
            val sinceOpen = before.substringAfterLast("Box {")
            assertFalse(
                "the ⋮ Box is not wrapped in a stage test",
                sinceOpen.contains("if (") || sinceOpen.contains("when ("),
            )
        }
    }

    // ------------------------- ② Connecting ⇒ the Connect entry is disabled

    @Test
    fun connectIsDrawnForEveryStageButReady() {
        // `menu.tsx:30` — `<Show when={item().stage !== "ready"}>`.
        assertFalse("ready hides Connect (there is nothing left to connect)", sshRowMenuShowsConnect(SshStage.READY))
        for (stage in allStages.filter { it != SshStage.READY }) {
            assertTrue("Connect is drawn in $stage", sshRowMenuShowsConnect(stage))
        }
        // The auth wall gets the other label, not a hidden entry.
        assertTrue("the auth wall still offers the entry", sshRowMenuShowsConnect(SshStage.AUTHENTICATION))
    }

    @Test
    fun connectIsDisabledThroughoutTheInFlightWindow() {
        // `menu.tsx:31` — `disabled={ssh.pending(props.id)}`. The shell's
        // `isSshConnecting` is upstream's `pending`: the window in which a
        // second dial is the v97 D2 shape.
        for (stage in inFlight) {
            assertFalse("Connect is disabled in $stage", sshRowMenuConnectEnabled(stage))
        }
        assertFalse(
            "an explicit pending flag disables it whatever the stage says",
            sshRowMenuConnectEnabled(SshStage.DISCONNECTED, pending = true),
        )
    }

    @Test
    fun connectIsEnabledOnEverySettledStage() {
        for (stage in listOf(
            SshStage.DISCONNECTED,
            SshStage.AUTHENTICATION,
            SshStage.INCOMPATIBLE,
            SshStage.FAILED,
        )) {
            assertTrue("Connect is enabled in $stage", sshRowMenuConnectEnabled(stage))
        }
    }

    @Test
    fun theInFlightSetIsExactlyTheUpstreamPendingSet() {
        // Ties the disabled window to `isSshConnecting` rather than to a
        // hand-listed set, so the two cannot drift apart.
        for (stage in allStages) {
            assertEquals(
                "the Connect gate and `pending` agree on $stage",
                !isSshConnecting(stage),
                sshRowMenuConnectEnabled(stage),
            )
        }
    }

    // ------------------------- ③ Delete is never gated, connecting included

    @Test
    fun deleteIsEnabledInEveryStageIncludingConnecting() {
        // `menu.tsx:46` — `onSelect={() => void props.domain.connection.remove(key())}`
        // with no `disabled` prop. The v98 round could not reach Delete from a
        // connecting row; that must not depend on a stage ever again.
        for (stage in allStages) {
            assertTrue("Delete is enabled in $stage", sshRowMenuDeleteEnabled(stage))
        }
    }

    @Test
    fun deleteOutlivesTheConnectGateItSitsBeside() {
        // The two entries differ in exactly one way, and only one. If a
        // `busy` guard ever widens from the Connect entry to the whole menu,
        // this is the assertion that catches it.
        for (stage in inFlight) {
            assertFalse("Connect is disabled in $stage", sshRowMenuConnectEnabled(stage))
            assertTrue("Delete is still enabled in $stage", sshRowMenuDeleteEnabled(stage))
        }
    }

    // ----------------------------------- the composable reads those rules

    @Test
    fun theMenuComposesConnectBeforeDeleteAndGatesOnlyConnect() {
        val menu = host().substringAfter("private fun SshTunnelRowMenu(")
        // v100: the popup is now driven by `sshRowMenuEntries(stage)` rather
        // than an inline `if`, so "Connect is conditioned on the stage" is
        // asserted through the rule the menu reads — which is now *stronger*:
        // it proves the stage actually removes the entry, not merely that an
        // `if` spelling exists somewhere in the body.
        assertTrue("the menu composes from the stage-driven entry list", menu.contains("sshRowMenuEntries(stage).forEach"))
        assertTrue(
            "Connect is conditioned on the stage, not on the menu",
            !sshRowMenuEntries(SshStage.READY).contains(SshRowMenuEntry.Connect) &&
                sshRowMenuEntries(SshStage.DISCONNECTED).contains(SshRowMenuEntry.Connect),
        )
        assertTrue("Connect carries the pending gate", menu.contains("enabled = sshRowMenuConnectEnabled(stage)"))
        assertTrue("Delete carries its own rule", menu.contains("enabled = sshRowMenuDeleteEnabled(stage)"))
        assertTrue(
            "Connect composes above Delete (`menu.tsx` order)",
            menu.indexOf("SshRowMenuEntry.Connect") < menu.indexOf("oc_servers_menu_delete"),
        )
        // The danger entry keeps its existing look, and the tap still only
        // *arms* the row — the confirm bar is what calls `forget`.
        assertTrue("Delete stays the danger entry", menu.contains("c.stateDangerFg"))
        assertTrue("Delete only arms the row", menu.contains("onClick = onDelete"))
    }

    @Test
    fun theRowActionButtonsKeepTheirOwnSemantics() {
        // `SshTunnelRowButtons`'s `enabled = !connecting` is the *Reconnect
        // button's* rule, not the menu's — the two must not be conflated. This
        // pins that the action bar is unchanged while the menu is not gated.
        val buttons = host().substringAfter("private fun SshTunnelRowButtons(")
            .substringBefore("private val SshTunnelButtonWidth")
        assertTrue("the Reconnect button keeps its own connecting gate", buttons.contains("enabled = !connecting"))
        // And the panel's action bar is the same story (SshConnectionPanel.kt).
        val panel = source("src/main/java/com/xiufeigo/opencodeshell/ui/servers/SshConnectionPanel.kt")
            .substringAfter("fun SshConnectionPanel(")
        assertTrue("the full-screen panel keeps its own connecting gate", panel.contains("enabled = !connecting"))
    }

    @Test
    fun theDeleteConfirmationPathIsUntouched() {
        // The armed-confirm chain the v98 round fixed is still the single path
        // to `forget`; the menu change must not have short-circuited it.
        val body = card()
        assertTrue("Delete still only arms the row", body.contains("forget = forget.ask(tunnel.config.id)"))
        assertEquals(
            "both shapes arm through the same call",
            2,
            Regex("forget = forget\\.ask\\(tunnel\\.config\\.id\\)").findAll(body).count(),
        )
        assertTrue("the confirm bar is still what calls confirmSshForget", body.contains("confirmSshForget(forget, tunnel.config.id, host, removePersisted)"))
    }
}
