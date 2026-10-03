package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.servers.SshAuthMethod
import com.xiufeigo.opencodeshell.ui.servers.SshConnection
import com.xiufeigo.opencodeshell.ui.servers.SshConnectionConfig
import com.xiufeigo.opencodeshell.ui.servers.SshConnectionStatus
import com.xiufeigo.opencodeshell.ui.servers.SshStage
import com.xiufeigo.opencodeshell.ui.servers.SshTunnel
import com.xiufeigo.opencodeshell.ui.session.header.sshPanelTunnel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Visibility pins for the session-route SSH fallback (`SshTunnelSession`):
 * the panel shows only for an SSH-backed server whose tunnel is not ready.
 */
class SshPanelTunnelTest {

    private fun tunnel(id: String, status: SshConnectionStatus): SshTunnel {
        val config = SshConnectionConfig(
            id = id,
            target = "ssh user@example.com",
            name = "",
            auth = SshAuthMethod.PASSWORD,
            password = "secret",
        )
        return SshTunnel(config, SshConnection(config), status)
    }

    private fun source(relative: String): String {
        val file = File(relative).takeIf { it.isFile } ?: File("app/$relative")
        return file.readText()
    }

    @Test
    fun readyTunnelStaysOnTheSession() {
        val tunnels = listOf(
            tunnel("a", SshConnectionStatus(stage = SshStage.READY, localUrl = "http://127.0.0.1:4101")),
        )
        assertNull(sshPanelTunnel(tunnels, "http://127.0.0.1:4101"))
    }

    @Test
    fun failedTunnelFallsBackToThePanel() {
        val tunnels = listOf(
            tunnel("a", SshConnectionStatus(stage = SshStage.FAILED)),
        )
        val resolved = sshPanelTunnel(
            tunnels,
            "http://127.0.0.1:4101",
            mapOf("a" to "http://127.0.0.1:4101"),
        )
        assertEquals("a", resolved?.config?.id)
    }

    @Test
    fun disconnectedTunnelFallsBackToThePanel() {
        val tunnels = listOf(
            tunnel("a", SshConnectionStatus(stage = SshStage.DISCONNECTED)),
        )
        val resolved = sshPanelTunnel(
            tunnels,
            "http://127.0.0.1:4101",
            mapOf("a" to "http://127.0.0.1:4101"),
        )
        assertEquals("a", resolved?.config?.id)
    }

    @Test
    fun nonSshServerNeverShowsThePanel() {
        val tunnels = listOf(
            tunnel("a", SshConnectionStatus(stage = SshStage.FAILED)),
        )
        assertNull(sshPanelTunnel(tunnels, "http://10.0.2.2:4096"))
        assertNull(sshPanelTunnel(tunnels, null))
        assertNull(sshPanelTunnel(tunnels, ""))
        assertNull(sshPanelTunnel(emptyList(), "http://127.0.0.1:4101"))
    }

    @Test
    fun unlinkedTunnelNeverShowsThePanel() {
        val tunnels = listOf(
            tunnel("a", SshConnectionStatus(stage = SshStage.FAILED)),
        )
        assertNull(sshPanelTunnel(tunnels, "http://127.0.0.1:4101", mapOf("b" to "http://127.0.0.1:4101")))
    }

    @Test
    fun appHoldsTheHostAndPublishesItOnTheSessionRoute() {
        val body = source("src/main/java/com/xiufeigo/opencodeshell/ui/App.kt")
        assertTrue(body.contains("SshTunnelHost("))
        assertTrue(body.contains("LocalSshTunnelHost provides sshHost"))
    }

    @Test
    fun sessionScreenFallsBackToThePanel() {
        val body = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionScreen.kt")
        assertTrue(body.contains("sshPanelTunnel("))
        assertTrue(body.contains("SshTunnelPanel("))
        assertTrue(body.contains("LocalSshTunnelHost.current"))
    }
}
