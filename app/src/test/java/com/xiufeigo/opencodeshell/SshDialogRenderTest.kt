package com.xiufeigo.opencodeshell

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source pins for the SSH connection dialog (`ui/servers/SshDialog.kt`) — the
 * config-form + auth-method half of upstream `servers/ssh/dialog.tsx`.
 */
class SshDialogRenderTest {

    private fun dialog(): String {
        val relative = "src/main/java/com/xiufeigo/opencodeshell/ui/servers/SshDialog.kt"
        val file = File(relative).takeIf { it.isFile } ?: File("app/$relative")
        return file.readText()
    }

    @Test
    fun theFormCollectsUpstreamsTargetAndNameFields() {
        val body = dialog()
        assertTrue(body.contains("R.string.oc_ssh_title"))
        assertTrue(body.contains("R.string.oc_ssh_target_label"))
        assertTrue(body.contains("R.string.oc_ssh_target_placeholder"))
        assertTrue(body.contains("R.string.dialog_server_add_name"))
        assertTrue(body.contains("R.string.dialog_server_add_namePlaceholder"))
    }

    @Test
    fun theAuthPickerCoversPasswordAndPrivateKey() {
        val body = dialog()
        assertTrue(body.contains("SshAuthMethod.PASSWORD"))
        assertTrue(body.contains("SshAuthMethod.KEY"))
        assertTrue(body.contains("R.string.oc_ssh_auth_label"))
        assertTrue(body.contains("R.string.oc_ssh_auth_key"))
        assertTrue(body.contains("R.string.dialog_server_add_password"))
        assertTrue(body.contains("R.string.oc_ssh_key_path_label"))
        assertTrue(body.contains("R.string.oc_ssh_key_path_placeholder"))
        assertTrue(body.contains("R.string.oc_ssh_passphrase_label"))
    }

    @Test
    fun submitValidatesThroughThePureModelAndHandsOverTheConfig() {
        val body = dialog()
        assertTrue(body.contains("validateSshForm("))
        assertTrue(body.contains("sshFormErrorResId("))
        assertTrue(body.contains("SshConnectionConfig("))
        assertTrue(body.contains("UUID.randomUUID()"))
    }

    @Test
    fun theFooterReusesUpstreamsCancelAndAddServerButtons() {
        val body = dialog()
        assertTrue(body.contains("R.string.common_cancel"))
        assertTrue(body.contains("R.string.dialog_server_add_button"))
    }

    @Test
    fun theConnectHalfIsAnExplicitGapNotASilentOne() {
        val body = dialog()
        assertTrue(body.contains("SSH client library"))
    }
}
