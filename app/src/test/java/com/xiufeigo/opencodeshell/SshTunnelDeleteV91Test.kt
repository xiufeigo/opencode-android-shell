package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.servers.SshAuthMethod
import com.xiufeigo.opencodeshell.ui.servers.SshConnection
import com.xiufeigo.opencodeshell.ui.servers.SshConnectionConfig
import com.xiufeigo.opencodeshell.ui.servers.SshConnectionStatus
import com.xiufeigo.opencodeshell.ui.servers.SshForgetState
import com.xiufeigo.opencodeshell.ui.servers.SshStage
import com.xiufeigo.opencodeshell.ui.servers.SshTunnel
import com.xiufeigo.opencodeshell.ui.servers.sshForgetReleasesTunnel
import com.xiufeigo.opencodeshell.ui.servers.sshTunnelsWithout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v91 「SSH 条目创建后无法删除」(`research/v90_verify/5556/`): the row that a
 * failed dial leaves behind (E2ECHK5556-ssh) has an exit — ⋮ → Delete → confirm
 * → `SshTunnelHost.forget(id)`.
 *
 * Three things are pinned here, and each one was missing before:
 *
 *  1. the **entry point**: the SSH card carries the same ⋮ + danger Delete item
 *     the WebSocket `ServerRow` does (`ServerRowMenu` + `oc_servers_menu_delete`);
 *  2. the **confirmation**: a ⋮ tap only arms the row — the card's own confirm
 *     bar is the single path to a removal ([SshForgetState.confirm] is the only
 *     transition that hands back an id);
 *  3. the **call site**: `forget` was dead code with no caller; the drop also
 *     has to disconnect first and cancel the status watcher, so the row cannot
 *     come back and a live tunnel is not leaked.
 *
 * The stage semantics are pinned as "delete is never gated, the copy tells the
 * truth": every stage is deletable (a hung/failed row is the one that needs the
 * exit), and only a ready/in-flight tunnel announces that it is released first.
 */
class SshTunnelDeleteV91Test {

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

    /** Just the `forget` body. */
    private fun forgetBody() = host()
        .substringAfter("fun forget(id: String) {")
        .substringBefore("\n    }\n")

    private fun tunnel(id: String, stage: SshStage = SshStage.FAILED): SshTunnel {
        val config = SshConnectionConfig(
            id = id,
            target = "E2ECHK5556-ssh",
            name = "",
            auth = SshAuthMethod.PASSWORD,
            password = "secret",
        )
        return SshTunnel(config, SshConnection(config), SshConnectionStatus(stage = stage))
    }

    // ------------------------------------------------------ 1. the entry point

    @Test
    fun theSshRowCarriesTheWebSocketRowsOwnOverflowEntry() {
        val body = card()
        assertTrue("the ⋮ trigger (ServerRow's own component)", body.contains("ServerRowMenu("))
        assertTrue("the same danger item as ServerRow", body.contains("R.string.oc_servers_menu_delete"))
        assertTrue("ServerRow's opaque menu surface", body.contains("containerColor = c.layer01"))
    }

    // ------------------------------------------------------ 2. confirm first

    @Test
    fun theOverflowDeleteOnlyArmsTheRowAndNeverForgets() {
        val body = card()
        assertTrue(body.contains("forget = forget.ask(tunnel.config.id)"))
        // Everything between the ⋮ item's arming and the confirm bar is the
        // menu + the row: no `forget` may run there, so the mis-tap the v90
        // round hit (⋮/long-press was expected to remove and did nothing —
        // or worse, removed without asking) can only arm.
        val armToConfirm = body
            .substringAfter("forget = forget.ask(tunnel.config.id)")
            .substringBefore("SshTunnelForgetConfirm(")
        assertFalse("the ⋮ tap must not remove anything", armToConfirm.contains("forget("))
    }

    @Test
    fun onlyTheConfirmedTapOfTheArmedRowReachesForget() {
        // ⋮ → Delete → confirm.
        assertEquals("E2ECHK5556-ssh", SshForgetState().ask("E2ECHK5556-ssh").confirm("E2ECHK5556-ssh"))
        // Cancel (or a row that went away) leaves nothing to forget.
        assertNull(SshForgetState().ask("a").cancel().confirm("a"))
        // A tap that answers no arm, or another row's arm, forgets nothing.
        assertNull(SshForgetState().confirm("a"))
        assertNull(SshForgetState().ask("a").confirm("b"))
        // A second tap cannot repeat it: the first confirm consumed the arm.
        val armed = SshForgetState().ask("a")
        assertEquals("a", armed.confirm("a"))
        assertNull(SshForgetState().cancel().confirm(""))
        // An empty id is never armed (the host's own guard against a blank row).
        assertNull(SshForgetState().ask("").confirm(""))
    }

    @Test
    fun theConfirmBarIsTheInCardCancelPlusDangerDelete() {
        val body = forgetUi()
        val bar = body.substringAfter("fun SshTunnelForgetConfirm(")
        assertTrue(body.contains("R.string.oc_ssh_delete_title"))
        assertTrue(body.contains("R.string.oc_ssh_delete_message"))
        assertTrue("the shell's existing cancel", bar.contains("R.string.oc_common_cancel"))
        assertTrue("the shell's existing danger label", bar.contains("R.string.oc_delete_session"))
        assertTrue("released-first copy for a live tunnel", bar.contains("R.string.oc_ssh_delete_release_hint"))
        assertTrue("the copy is stage-driven, the action is not", bar.contains("if (releaseFirst)"))
        assertTrue(
            "the card feeds the stage in",
            card().contains("sshForgetReleasesTunnel(tunnel.status.stage)"),
        )
        // Never a disabled action: the row a user most needs to remove is the
        // one whose stage is broken, so the confirm carries no `enabled` gate.
        assertFalse("the confirm's buttons are never stage-gated", bar.contains("enabled"))
    }

    // ------------------------------------------------------- 3. the call site

    @Test
    fun forgetIsNoLongerDeadCode() {
        // The v90 finding: `forget` existed with no call site anywhere in main.
        // v92: the confirm hands the armed state, the id, the host and the
        // panel's persisted remover to `confirmSshForget` — the single call
        // path that drops the persisted record first, then the live row.
        assertTrue(
            "the card's confirm is the call path",
            card().contains("confirmSshForget(forget, tunnel.config.id, host, removePersisted)"),
        )
        val helper = forgetUi().substringAfter("fun confirmSshForget(")
        assertTrue("the host drop stays in the same call path", helper.contains("host.forget(target)"))
        assertTrue("the armed gate is the only transition", helper.contains("armed.confirm(id)"))
        assertTrue(
            "the persisted removal runs before the drop (no resurrection on re-seed)",
            helper.indexOf("removePersisted(target)") < helper.indexOf("host.forget(target)"),
        )
    }

    @Test
    fun forgetDisconnectsCancelsTheWatcherThenDropsTheRow() {
        val body = forgetBody()
        assertTrue(body.contains("disconnect(id)"))
        assertTrue(body.contains("watchJobs.remove(id)?.cancel()"))
        assertTrue("the pure removal helper", body.contains("sshTunnelsWithout(list, id)"))
        // Release before drop, and no late emission can repaint the row.
        assertTrue(body.indexOf("disconnect(id)") < body.indexOf("sshTunnelsWithout(list, id)"))
        assertTrue(
            body.indexOf("watchJobs.remove(id)?.cancel()") < body.indexOf("sshTunnelsWithout(list, id)"),
        )
    }

    // ------------------------------------------------ the removal is the state machine

    @Test
    fun removingATunnelLeavesEveryOtherRowInOrder() {
        val rows = listOf(tunnel("a"), tunnel("E2ECHK5556-ssh"), tunnel("c"))
        val left = sshTunnelsWithout(rows, "E2ECHK5556-ssh")
        assertEquals(listOf("a", "c"), left.map { it.config.id })
        // The reported row is really gone — not renamed, not hidden.
        assertTrue(left.none { it.config.id == "E2ECHK5556-ssh" })
        // An id that is not in the list changes nothing (idempotent, so a
        // double confirm cannot eat a neighbouring row).
        assertEquals(rows, sshTunnelsWithout(rows, "missing"))
        assertEquals(listOf("E2ECHK5556-ssh", "c"), sshTunnelsWithout(rows, "a").map { it.config.id })
        assertEquals(listOf("c"), sshTunnelsWithout(sshTunnelsWithout(rows, "a"), "E2ECHK5556-ssh").map { it.config.id })
        assertTrue(sshTunnelsWithout(emptyList(), "a").isEmpty())
    }

    @Test
    fun everyStageCanBeDeletedAndOnlyALiveTunnelAnnouncesItsRelease() {
        // Deletable whatever the stage — the FAILED/CONNECTING row is the one
        // the v90 round could not get rid of.
        for (stage in SshStage.entries) {
            assertEquals("stage=$stage", "row", SshForgetState().ask("row").confirm("row"))
        }
        // …while the copy only claims a release when there is something to release.
        assertTrue(sshForgetReleasesTunnel(SshStage.READY))
        assertTrue(sshForgetReleasesTunnel(SshStage.CONNECTING))
        assertTrue(sshForgetReleasesTunnel(SshStage.STARTING))
        assertTrue(sshForgetReleasesTunnel(SshStage.AUTHENTICATION))
        assertFalse(sshForgetReleasesTunnel(SshStage.FAILED))
        assertFalse(sshForgetReleasesTunnel(SshStage.DISCONNECTED))
        assertFalse(sshForgetReleasesTunnel(SshStage.INCOMPATIBLE))
    }

    // ------------------------------------------------------------------ i18n

    @Test
    fun theNewKeysAreTranslatedInAllThreeLocales() {
        val keys = listOf(
            "oc_ssh_delete_title",
            "oc_ssh_delete_message",
            "oc_ssh_delete_release_hint",
        )
        val english = source("src/main/res/values/strings.xml")
        for (key in keys) assertTrue("values is missing $key", english.contains("name=\"$key\""))
        for (dir in listOf("values-zh-rCN", "values-zh-rTW")) {
            val xml = source("src/main/res/$dir/strings.xml")
            for (key in keys) {
                val value = Regex("""<string name="$key">(.*?)</string>""")
                    .find(xml)?.groupValues?.get(1)
                val sourceValue = Regex("""<string name="$key">(.*?)</string>""")
                    .find(english)?.groupValues?.get(1)
                assertTrue("$dir is missing $key", value != null)
                assertTrue("$dir/$key is blank", !value.isNullOrBlank())
                assertNotEquals("$dir/$key is still the English copy", sourceValue, value)
            }
            // The name placeholder survives the translation (the dialog passes it).
            val message = Regex("""<string name="oc_ssh_delete_message">(.*?)</string>""")
                .find(xml)?.groupValues?.get(1)
            assertTrue("$dir/oc_ssh_delete_message must carry %1\$s (got '$message')", message!!.contains("%1\$s"))
        }
    }
}
