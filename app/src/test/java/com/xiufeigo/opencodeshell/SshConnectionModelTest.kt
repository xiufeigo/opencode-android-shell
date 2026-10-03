package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.servers.SshConnectEvent
import com.xiufeigo.opencodeshell.ui.servers.SshConnectionStatus
import com.xiufeigo.opencodeshell.ui.servers.SshErrorCode
import com.xiufeigo.opencodeshell.ui.servers.SshFailure
import com.xiufeigo.opencodeshell.ui.servers.SshHostKeyInfo
import com.xiufeigo.opencodeshell.ui.servers.SshPrompt
import com.xiufeigo.opencodeshell.ui.servers.SshStage
import com.xiufeigo.opencodeshell.ui.servers.formatSshHandshakeAge
import com.xiufeigo.opencodeshell.ui.servers.formatSshLatency
import com.xiufeigo.opencodeshell.ui.servers.isSshConnecting
import com.xiufeigo.opencodeshell.ui.servers.parseSshTarget
import com.xiufeigo.opencodeshell.ui.servers.reduceSshConnection
import com.xiufeigo.opencodeshell.ui.servers.sshErrorResId
import com.xiufeigo.opencodeshell.ui.servers.sshFailure
import com.xiufeigo.opencodeshell.ui.servers.sshLocalUrl
import com.xiufeigo.opencodeshell.ui.servers.sshStageResId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure tunnel model (`ui/servers/SshConnectionModel.kt`): upstream's
 * accepted-target rule (`servers/ssh/name.ts:3-5`) as the jsch dial gate, the
 * `JSchException` → `SshItem.error` mapping (`types.ts:39-49`), and the
 * connect → authenticating → trust prompt → connected / failed reducer. No
 * sockets: everything here is string/state in, state out.
 */
class SshConnectionModelTest {

    @Test
    fun parseSshTargetAcceptsUpstreamsTargetForms() {
        // `name.test.ts:6-15` fixtures that name a dialable endpoint.
        assertEquals(
            "brendan-box.exe.xyz",
            parseSshTarget("brendan-box.exe.xyz")?.host,
        )
        assertEquals(
            "brendan-box.exe.xyz",
            parseSshTarget("ssh brendan-box.exe.xyz")?.host,
        )
        assertEquals(
            "anomaly" to "brendan-box.exe.xyz",
            parseSshTarget("ssh anomaly@brendan-box.exe.xyz")?.let { it.user to it.host },
        )
        assertEquals(
            "2001:db8::1",
            parseSshTarget("ssh user@[2001:db8::1]")?.host,
        )
        assertEquals(
            "brendan-box.exe.xyz",
            parseSshTarget("  ssh 'brendan-'box.exe.xyz  ")?.host,
        )
    }

    @Test
    fun parseSshTargetReadsTheSupportedOptions() {
        val ported = parseSshTarget("ssh -p 2222 anomaly@devbox")
        assertEquals(2222, ported?.port)
        assertEquals("anomaly", ported?.user)
        assertEquals("devbox", ported?.host)

        val keyed = parseSshTarget("""ssh -p 2222 -i "/keys/my key" anomaly@devbox""")
        assertEquals("/keys/my key", keyed?.identityPath)
        assertEquals(2222, keyed?.port)

        val listed = parseSshTarget("ssh -l deploy host.example")
        assertEquals("deploy", listed?.user)
        assertEquals("host.example", listed?.host)

        assertEquals(22, parseSshTarget("devbox")?.port)
        assertNull(parseSshTarget("devbox")?.identityPath)
    }

    @Test
    fun parseSshTargetRejectsRemoteCommandsAndUnsupportedOptions() {
        // Upstream's `ssh.error.input` gate — the runner refuses anything that
        // is not `ssh [options] [user@]host`; jsch has no shell, so this is it.
        assertNull(parseSshTarget(""))
        assertNull(parseSshTarget("   "))
        assertNull(parseSshTarget("ssh"))
        assertNull(parseSshTarget("devbox ls -la"))
        assertNull(parseSshTarget("ssh devbox rm -rf /"))
        assertNull(parseSshTarget("""ssh -o 'ProxyCommand=ssh jump -W %h:%p' 'anomaly@devbox'"""))
        assertNull(parseSshTarget("""ssh -p 2222 -i "/keys/my key" -J jump@example.com anomaly@devbox"""))
        assertNull(parseSshTarget("ssh -p"))
        assertNull(parseSshTarget("ssh -p 99999 host"))
        assertNull(parseSshTarget("ssh -p twentytwo host"))
        assertNull(parseSshTarget("ssh user@"))
        assertNull(parseSshTarget("ssh -i \"unbalanced host"))
    }

    @Test
    fun sshFailureMapsJschMessagesOntoSshItemErrors() {
        // `types.ts:39-49` literals — host-key, key-file, input,
        // authentication, connection. F14a moved the key-file words off `input`
        // (they used to render the *target* sentence for an unreadable private
        // key); `SshKeyFileFailureF14Test` covers that branch in full.
        assertEquals(
            SshFailure(SshStage.FAILED, SshErrorCode.HOST_KEY, "reject HostKey: devbox"),
            sshFailure("reject HostKey: devbox"),
        )
        assertEquals(
            SshFailure(SshStage.FAILED, SshErrorCode.HOST_KEY, "HostKey has been changed: devbox"),
            sshFailure("HostKey has been changed: devbox"),
        )
        assertEquals(SshStage.FAILED, sshFailure("invalid privatekey: /k").stage)
        assertEquals(SshErrorCode.KEY_FILE, sshFailure("invalid privatekey: /k").error)
        assertEquals(SshErrorCode.KEY_FILE, sshFailure("java.io.FileNotFoundException: /k").error)
        // Still form input: a passphrase the server rejected.
        assertEquals(SshErrorCode.INPUT, sshFailure("USERAUTH fail: passphrase is incorrect").error)
        val auth = sshFailure("Auth fail")
        assertEquals(SshStage.AUTHENTICATION, auth.stage)
        assertEquals(SshErrorCode.CONNECTION, auth.error)
        assertEquals(SshStage.AUTHENTICATION, sshFailure("USERAUTH fail").stage)
        val network = sshFailure("java.net.ConnectException: Connection refused")
        assertEquals(SshStage.FAILED, network.stage)
        assertEquals(SshErrorCode.CONNECTION, network.error)
    }

    @Test
    fun theReducerWalksConnectTrustPromptReadyAndFailure() {
        var status = SshConnectionStatus()
        assertEquals(SshStage.DISCONNECTED, status.stage)

        status = reduceSshConnection(status, SshConnectEvent.Start)
        assertEquals(SshStage.CONNECTING, status.stage)
        assertTrue(isSshConnecting(status.stage))

        val prompt = SshPrompt("p1", "trust?", confirm = true)
        val hostKey = SshHostKeyInfo("devbox", "ssh-ed25519", "AAAA")
        status = reduceSshConnection(status, SshConnectEvent.Prompted(prompt, hostKey))
        // A prompt rides along in the in-flight stage (`dialog.tsx` `busy()`).
        assertEquals(SshStage.CONNECTING, status.stage)
        assertEquals(prompt, status.prompt)
        assertEquals(hostKey, status.hostKey)

        // A stale answer must not clear a newer prompt.
        status = reduceSshConnection(status, SshConnectEvent.PromptResolved("other"))
        assertEquals(prompt, status.prompt)
        status = reduceSshConnection(status, SshConnectEvent.PromptResolved("p1"))
        assertNull(status.prompt)
        assertNull(status.hostKey)

        status = reduceSshConnection(status, SshConnectEvent.Ready("http://127.0.0.1:43123", 42, 1_000L))
        assertEquals(SshStage.READY, status.stage)
        assertEquals("http://127.0.0.1:43123", status.localUrl)
        assertEquals(42L, status.latencyMs)
        assertEquals(1_000L, status.lastHandshakeAt)

        status = reduceSshConnection(status, SshConnectEvent.Disconnect)
        assertEquals(SshStage.DISCONNECTED, status.stage)
        assertNull(status.localUrl)
        // The panel keeps the stats across a drop ("Last handshake").
        assertEquals(42L, status.latencyMs)
        assertEquals(1_000L, status.lastHandshakeAt)
    }

    @Test
    fun theReducerParksFailuresWithTheirErrorLiteral() {
        val connecting = reduceSshConnection(SshConnectionStatus(), SshConnectEvent.Start)

        val rejected = reduceSshConnection(
            connecting,
            SshConnectEvent.Fail(SshFailure(SshStage.FAILED, SshErrorCode.HOST_KEY, "reject HostKey: h")),
        )
        assertEquals(SshStage.FAILED, rejected.stage)
        assertEquals(SshErrorCode.HOST_KEY, rejected.error)

        val authWall = reduceSshConnection(
            connecting,
            SshConnectEvent.Fail(SshFailure(SshStage.AUTHENTICATION, SshErrorCode.CONNECTION, "Auth fail")),
        )
        // `authenticate.tsx`: the panel offers "Authenticate" on this stage.
        assertEquals(SshStage.AUTHENTICATION, authWall.stage)
        assertFalse(isSshConnecting(authWall.stage))

        val retry = reduceSshConnection(rejected, SshConnectEvent.Start)
        assertNull(retry.error)
        assertEquals(SshStage.CONNECTING, retry.stage)
    }

    @Test
    fun tunnelUrlAndPanelStatsFormats() {
        assertEquals("http://127.0.0.1:43123", sshLocalUrl(43123))
        assertEquals("—", formatSshLatency(null))
        assertEquals("—", formatSshLatency(-1))
        assertEquals("42 ms", formatSshLatency(42))
        assertEquals("999 ms", formatSshLatency(999))
        assertEquals("1.0 s", formatSshLatency(1_000))
        assertEquals("1.4 s", formatSshLatency(1_400))
        assertEquals("—", formatSshHandshakeAge(-1))
        assertEquals("5s", formatSshHandshakeAge(5_000))
        assertEquals("2m", formatSshHandshakeAge(125_000))
        assertEquals("3h", formatSshHandshakeAge(3 * 3_600_000))
        assertEquals("4d", formatSshHandshakeAge(4 * 86_400_000))
    }

    @Test
    fun everyStageAndErrorCodeMapsToARealDistinctStringResource() {
        val stages = SshStage.entries.map(::sshStageResId)
        assertTrue(stages.none { it == 0 })
        assertEquals(stages.size, stages.toSet().size)
        val errors = SshErrorCode.entries.map(::sshErrorResId)
        assertTrue(errors.none { it == 0 })
        assertEquals(errors.size, errors.toSet().size)
        assertNotEquals(stages.toSet(), errors.toSet())
    }
}
