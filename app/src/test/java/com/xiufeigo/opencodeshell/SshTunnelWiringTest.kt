package com.xiufeigo.opencodeshell

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source pins for the SSH tunnel half (`ui/servers/SshConnection.kt`,
 * `SshAuthenticate.kt`, `SshConnectionPanel.kt`, `SshDialog.kt`) — same house
 * style as `SshDialogRenderTest`. The JVM behaviour lives in
 * `SshConnectionModelTest` / `SshAuthenticateModelTest`; these assert the
 * jsch/Compose wiring and the three-locale i18n keys stay attached to it.
 */
class SshTunnelWiringTest {

    private fun source(relative: String): String {
        val file = File(relative).takeIf { it.isFile } ?: File("app/$relative")
        return file.readText()
    }

    private fun connection() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/servers/SshConnection.kt")

    private fun authenticate() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/servers/SshAuthenticate.kt")

    private fun panel() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/servers/SshConnectionPanel.kt")

    private fun dialog() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/servers/SshDialog.kt")

    @Test
    fun theTunnelIsJschWithALocalPortForward() {
        val body = connection()
        assertTrue(body.contains("JSch()"))
        // Remote `opencode serve` → `http://127.0.0.1:<local>` (`ServerConfigStore` url).
        assertTrue(body.contains("setPortForwardingL"))
        assertTrue(body.contains("SSH_REMOTE_PORT_DEFAULT"))
        assertTrue(body.contains("sshLocalUrl("))
        assertTrue(body.contains("setPassword("))
        assertTrue(body.contains("addIdentity("))
    }

    @Test
    fun hostKeysArePromptedForAndOnlyPersistedOnTrust() {
        val body = connection()
        // `StrictHostKeyChecking=ask` + the prompt → `hkr.add` (known_hosts append).
        assertTrue(body.contains("\"StrictHostKeyChecking\""))
        assertTrue(body.contains("\"ask\""))
        assertTrue(body.contains("promptYesNo"))
        assertTrue(body.contains("respondTrust"))
        assertTrue(body.contains("cancelPrompt"))
        assertTrue(body.contains("known_hosts"))
    }

    @Test
    fun theLifecycleReleasesResourcesAndSupportsReconnect() {
        val body = connection()
        assertTrue(body.contains("suspend fun connect()"))
        assertTrue(body.contains("suspend fun reconnect()"))
        assertTrue(body.contains("fun disconnect()"))
        assertTrue(body.contains("CancellationException"))
        assertTrue(body.contains("reduceSshConnection"))
    }

    @Test
    fun theTrustDialogShowsBothFingerprintsAndTheUpstreamPromptButtons() {
        val body = authenticate()
        assertTrue(body.contains("R.string.oc_ssh_host_key_title"))
        assertTrue(body.contains("R.string.oc_ssh_fingerprint_md5"))
        assertTrue(body.contains("R.string.oc_ssh_fingerprint_sha256"))
        assertTrue(body.contains("hostKey.md5"))
        assertTrue(body.contains("hostKey.sha256"))
        // `dialog.tsx:250`: confirm → `ssh.trust`, otherwise `ssh.continue`.
        assertTrue(body.contains("R.string.ssh_trust"))
        assertTrue(body.contains("R.string.ssh_continue"))
        assertTrue(body.contains("R.string.ssh_error_host_key"))
    }

    @Test
    fun theConnectionPanelIsUpstreamsPlusTheStatsAndDisconnectRow() {
        val body = panel()
        assertTrue(body.contains("R.string.ssh_session_disconnected"))
        assertTrue(body.contains("R.string.ssh_session_reconnectDescription"))
        assertTrue(body.contains("R.string.ssh_session_reconnect"))
        assertTrue(body.contains("R.string.ssh_session_connecting"))
        assertTrue(body.contains("R.string.ssh_action_authenticate"))
        assertTrue(body.contains("R.string.ssh_disconnect"))
        assertTrue(body.contains("sshErrorResId("))
        assertTrue(body.contains("sshStageResId("))
        assertTrue(body.contains("formatSshLatency("))
        assertTrue(body.contains("formatSshHandshakeAge("))
        assertTrue(body.contains("isSshConnecting("))
        assertTrue(body.contains("onReconnect"))
        assertTrue(body.contains("onDisconnect"))
    }

    @Test
    fun theDialogDialsHandsOverAndReportsStatus() {
        val body = dialog()
        assertTrue(body.contains("SshConnection("))
        assertTrue(body.contains("onSubmit(sshConfig, tunnel)"))
        assertTrue(body.contains("tunnel.connect()"))
        assertTrue(body.contains("tunnel.reconnect()") || body.contains("existing.reconnect()") || body.contains("submit()"))
        assertTrue(body.contains("connection?.respondTrust(true)"))
        assertTrue(body.contains("connection?.respondPrompt(prompt.id, it)"))
        assertTrue(body.contains("connection?.cancelPrompt()"))
        assertTrue(body.contains("SshConnectionStatus()"))
        // The form half still owns the add-server footer (SshDialogRenderTest).
        assertTrue(body.contains("R.string.dialog_server_add_button"))
    }

    @Test
    fun everyNewOcSshKeyExistsAndIsLocalisedInAllThreeLocales() {
        val keys = listOf(
            "oc_ssh_host_key_title",
            "oc_ssh_fingerprint_md5",
            "oc_ssh_fingerprint_sha256",
            "oc_ssh_host_key_hint",
            "oc_ssh_latency_label",
            "oc_ssh_last_handshake_label",
        )
        val english = source("src/main/res/values/strings.xml")
        for (locale in listOf("values", "values-zh-rCN", "values-zh-rTW")) {
            val xml = source("src/main/res/$locale/strings.xml")
            val missing = keys.filterNot { xml.contains("name=\"$it\"") }
            assertTrue("$locale is missing $missing", missing.isEmpty())
            if (locale != "values") {
                for (key in keys.filterNot { it == "oc_ssh_fingerprint_md5" || it == "oc_ssh_fingerprint_sha256" }) {
                    val value = xml.lineSequence().firstOrNull { it.contains("name=\"$key\"") }
                        ?.substringAfter('>')?.substringBefore('<')
                    val sourceValue = english.lineSequence().firstOrNull { it.contains("name=\"$key\"") }
                        ?.substringAfter('>')?.substringBefore('<')
                    assertTrue("$locale/$key is still the English copy", value != null && value != sourceValue)
                }
            }
        }
    }
}
