package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.servers.SshAuthMethod
import com.xiufeigo.opencodeshell.ui.servers.SshConnectionConfig
import com.xiufeigo.opencodeshell.ui.servers.SshConnectionStatus
import com.xiufeigo.opencodeshell.ui.servers.SshStage
import com.xiufeigo.opencodeshell.ui.servers.sshReadyLocalUrl
import com.xiufeigo.opencodeshell.ui.servers.sshServerEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Submit wiring of `SshDialog` → `SshTunnelHost`: `status.localUrl` is handed
 * to `ServerConfigStore` only while the forward is up, and the store row is
 * the loopback url + the dialog's display name + the target's user.
 */
class SshTunnelHostModelTest {

    private fun status(stage: SshStage, localUrl: String? = null) =
        SshConnectionStatus(stage = stage, localUrl = localUrl)

    private fun config(target: String = "deploy@box.example", name: String = "build box") =
        SshConnectionConfig(
            id = "ssh_1",
            target = target,
            name = name,
            auth = SshAuthMethod.PASSWORD,
            password = "secret",
        )

    @Test
    fun readyHandsOverTheForwardedLoopbackUrl() {
        assertEquals(
            "http://127.0.0.1:43123",
            sshReadyLocalUrl(status(SshStage.READY, "http://127.0.0.1:43123")),
        )
    }

    @Test
    fun everyOtherStageHandsOverNothing() {
        assertNull(sshReadyLocalUrl(status(SshStage.CONNECTING, "http://127.0.0.1:43123")))
        assertNull(sshReadyLocalUrl(status(SshStage.FAILED, null)))
        assertNull(sshReadyLocalUrl(status(SshStage.DISCONNECTED, null)))
        assertNull(sshReadyLocalUrl(status(SshStage.READY, null)))
    }

    @Test
    fun storeEntryUsesLocalUrlNameAndTargetUser() {
        val item = sshServerEntry(config(), "http://127.0.0.1:43123")
        assertEquals("http://127.0.0.1:43123", item.url)
        assertEquals("build box", item.name)
        assertEquals("deploy", item.username)
    }

    @Test
    fun storeEntryFallsBackToHostnameAndKeepsABareHostUserless() {
        val named = sshServerEntry(config(target = "box.example", name = ""), "http://127.0.0.1:1")
        assertEquals("box.example", named.name)
        assertNull(named.username)
    }
}
