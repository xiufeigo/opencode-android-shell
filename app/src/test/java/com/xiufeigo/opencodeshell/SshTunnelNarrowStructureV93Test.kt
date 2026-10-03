package com.xiufeigo.opencodeshell

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2 「极窄窗口 SSH 行无身份」: the structural half — the identity column has a
 * floor, no label can wrap mid-word, and the v91 delete wiring is untouched.
 *
 * Style mirror of `SshTunnelDeleteV91Test` (source assertions on
 * `SshTunnelHost.kt`): the project has no JVM-renderable Compose surface, so
 * the shape is pinned as source facts plus the pure rule (`SshTunnelRowStackedWidth`,
 * `SshTunnelRowIdentityMin`, `sshTunnelRowStacked`) the arithmetic suite
 * (`SshTunnelNarrowRowV93Test`) pins by value.
 */
class SshTunnelNarrowStructureV93Test {

    private fun source(relative: String): String =
        listOf(File("app/$relative"), File(relative))
            .firstOrNull { it.isFile }
            ?.readText()?.replace("\r\n", "\n")
            ?: error("missing $relative")

    private fun host() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/servers/SshTunnelHost.kt")

    /** The card itself, from its declaration to the end of the host file. */
    private fun card() = host().substringAfter("fun SshTunnelCard(")

    // ------------------------------------ the identity column has a floor

    @Test
    fun theIdentityColumnCanNeverCollapseToZero() {
        val body = card()
        assertTrue(
            "the identity column carries the min-width floor",
            body.contains("widthIn(min = SshTunnelRowIdentityMin)"),
        )
        assertEquals(
            "the floor guards both shapes",
            2,
            Regex("widthIn\\(min = SshTunnelRowIdentityMin\\)").findAll(body).count(),
        )
        // The floor is on the identity column — not on the buttons (which keep
        // their intrinsic width) and not a fixed width (which would break the
        // wide line wherever the name is short).
        assertFalse(body.contains("width(SshTunnelRowIdentityMin)"))
    }

    @Test
    fun theStackedIdentityTakesItsOwnFullWidthLine() {
        val body = card()
        val stacked = body.substringAfter("if (stacked)").substringBefore("} else {")
        assertTrue(
            "the stacked identity is full-width, out of the buttons' reach",
            stacked.contains("Modifier.fillMaxWidth().widthIn(min = SshTunnelRowIdentityMin)"),
        )
        assertTrue("the stacked shape still pads like the row", stacked.contains("padding(vertical = 12.dp)"))
    }

    // -------------------------------------- no label wraps mid-word, ever

    @Test
    fun theTunnelNameEllipsizesInsteadOfVanishing() {
        val identity = card().substringAfter("fun SshTunnelRowIdentity(")
        assertTrue(identity.contains("maxLines = 1"))
        assertTrue(identity.contains("softWrap = false"))
        assertTrue(identity.contains("overflow = TextOverflow.Ellipsis"))
    }

    @Test
    fun theButtonsKeepTheirIntrinsicWidthAtAnyRowWidth() {
        val body = card()
        // The pair is measured whole — the wide line where it fits, the
        // stacked FlowRow which flows an overflowing control down instead of
        // squeezing it — so neither label can wrap mid-word.
        assertTrue(
            "both buttons are measured at their intrinsic width",
            body.contains("private val SshTunnelButtonWidth: Modifier = Modifier.width(IntrinsicSize.Max)"),
        )
        assertEquals(
            "both buttons take the width modifier",
            2,
            Regex("modifier = SshTunnelButtonWidth").findAll(body).count(),
        )
        assertTrue("the stacked controls flow instead of squeezing", body.contains("FlowRow("))
    }

    @Test
    fun noNewUserVisibleStrings() {
        // The fix reuses the row's own labels only: no new copy in any locale.
        //
        // v99 adds `ssh_action_authenticate` to this set. It is **not** new
        // copy: the string already exists in all three locales
        // (`strings_oc_i18n.xml` — `Authenticate` / `认证` / `驗證`) and the
        // row menu labels its Connect entry "Authenticate" only on the auth
        // wall, exactly as `menu.tsx:32` does. The set stays **exact**, so any
        // genuinely new resource id still fails this test.
        // v100 adds `ssh_server_menu_label` for the row menu's
        // `Menu.GroupLabel` (`menu.tsx:29`). Also **not** new copy: the
        // string already exists in all three locales
        // (`strings_oc_i18n.xml:1381` — "SSH server" / "SSH 服务器" /
        // "SSH 伺服器") and is upstream's own `ssh.server.menu.label`
        // (`i18n/en.ts:9`). The set stays **exact**.
        val ids = Regex("R\\.string\\.([A-Za-z0-9_]+)")
            .findAll(card())
            .map { it.groupValues[1] }
            .toSet()
        assertEquals(
            setOf(
                "ssh_session_connecting",
                "ssh_session_reconnect",
                "ssh_action_authenticate",
                "ssh_disconnect",
                "oc_servers_menu_delete",
                "ssh_server_menu_label",
            ),
            ids,
        )
    }

    // --------------------------------------------- the v91 wiring is intact

    @Test
    fun theOverflowEntryStillArmsAndNeverForgets() {
        val body = card()
        assertTrue("the ⋮ trigger (ServerRow's own component)", body.contains("ServerRowMenu("))
        assertTrue("the same danger item as ServerRow", body.contains("R.string.oc_servers_menu_delete"))
        assertTrue("ServerRow's opaque menu surface", body.contains("containerColor = c.layer01"))
        assertTrue(body.contains("forget = forget.ask(tunnel.config.id)"))
        val armToConfirm = body
            .substringAfter("forget = forget.ask(tunnel.config.id)")
            .substringBefore("SshTunnelForgetConfirm(")
        assertFalse("the ⋮ tap must not remove anything", armToConfirm.contains("forget("))
    }

    @Test
    fun theConfirmBarIsStillTheInCardSinglePathToForget() {
        val body = card()
        // v92: the confirm runs the shared `confirmSshForget` call path
        // (persisted removal first, then the host drop) — the row contract is
        // unchanged: only the confirmed tap of the armed row forgets.
        assertTrue(
            "the card's confirm is the call path",
            body.contains("confirmSshForget(forget, tunnel.config.id, host, removePersisted)"),
        )
        assertTrue(
            "the card feeds the stage in",
            body.contains("sshForgetReleasesTunnel(tunnel.status.stage)"),
        )
    }

    @Test
    fun theReconnectAndDisconnectCallbacksAreUnchanged() {
        val buttons = card().substringAfter("fun SshTunnelRowButtons(")
        assertTrue(buttons.contains("onClick = { host.reconnect(tunnel.config.id) }"))
        assertTrue(buttons.contains("onClick = { host.disconnect(tunnel.config.id) }"))
        assertTrue("the reconnect button still shows the connecting copy", buttons.contains("R.string.ssh_session_connecting"))
        assertTrue(buttons.contains("R.string.ssh_session_reconnect"))
        assertTrue(buttons.contains("R.string.ssh_disconnect"))
        assertTrue("the connecting gate is untouched", buttons.contains("enabled = !connecting"))
        assertTrue(
            "the disconnect gate is untouched",
            buttons.contains("enabled = tunnel.status.stage != SshStage.DISCONNECTED"),
        )
    }
}
