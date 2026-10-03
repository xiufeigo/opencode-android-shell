package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.servers.SshAuthMethod
import com.xiufeigo.opencodeshell.ui.servers.SshConnectionConfig
import com.xiufeigo.opencodeshell.ui.servers.SshFormError
import com.xiufeigo.opencodeshell.ui.servers.SshStage
import com.xiufeigo.opencodeshell.ui.servers.isSshConnecting
import com.xiufeigo.opencodeshell.ui.servers.sshFormErrorResId
import com.xiufeigo.opencodeshell.ui.servers.sshHostname
import com.xiufeigo.opencodeshell.ui.servers.sshName
import com.xiufeigo.opencodeshell.ui.servers.validateSshForm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The pure SSH form model (`ui/servers/SshModel.kt`): upstream
 * `servers/ssh/name.test.ts` fixtures case-for-case, `status.ts` stages, and
 * the form gates of `ssh/dialog.tsx` plus the Android auth-method half.
 */
class SshModelTest {

    @Test
    fun sshHostnameTakesTheLastTokenStripsQuotesAndDropsTheUser() {
        // Fixtures are upstream `name.test.ts:6-15`, case for case.
        assertEquals("brendan-box.exe.xyz", sshHostname("brendan-box.exe.xyz"))
        assertEquals("brendan-box.exe.xyz", sshHostname("ssh brendan-box.exe.xyz"))
        assertEquals("brendan-box.exe.xyz", sshHostname("ssh anomaly@brendan-box.exe.xyz"))
        assertEquals(
            "devbox",
            sshHostname("""ssh -p 2222 -i "/keys/my key" -J jump@example.com anomaly@devbox"""),
        )
        assertEquals(
            "devbox",
            sshHostname("""ssh -o 'ProxyCommand=ssh jump -W %h:%p' 'anomaly@devbox'"""),
        )
        assertEquals("brendan-box.exe.xyz", sshHostname("  ssh 'brendan-'box.exe.xyz  "))
        assertEquals("[2001:db8::1]", sshHostname("ssh user@[2001:db8::1]"))
        assertEquals("", sshHostname(""))
    }

    @Test
    fun sshNamePrefersTheCustomNameAndKeepsTheTargetIntact() {
        // `name.test.ts:20-23`: the display helper never mutates the command.
        val config = SshConnectionConfig(
            id = "id",
            target = "ssh -p 2222 anomaly@devbox",
            name = "Development",
            auth = SshAuthMethod.PASSWORD,
        )
        assertEquals("Development", sshName(config.name, config.target))
        assertEquals("ssh -p 2222 anomaly@devbox", config.target)
        assertEquals("devbox", sshName("", config.target))
    }

    @Test
    fun isSshConnectingIsTrueOnlyForTheInFlightStages() {
        // `status.ts:3-11`.
        val inFlight = listOf(
            SshStage.CONNECTING,
            SshStage.CHECKING,
            SshStage.DOWNLOADING,
            SshStage.UPLOADING,
            SshStage.STARTING,
        )
        val settled = listOf(
            SshStage.DISCONNECTED,
            SshStage.READY,
            SshStage.AUTHENTICATION,
            SshStage.INCOMPATIBLE,
            SshStage.FAILED,
        )
        inFlight.forEach { assertTrue(it.name, isSshConnecting(it)) }
        settled.forEach { assertFalse(it.name, isSshConnecting(it)) }
        assertEquals(10, SshStage.entries.size)
    }

    @Test
    fun theFormGateIsUpstreamsTargetCheckPlusTheAuthHalf() {
        // Upstream only gates on `!state.target.trim()` (`dialog.tsx:101`);
        // collecting the credential up front is the Android extension.
        assertEquals(
            SshFormError.TargetRequired,
            validateSshForm("  ", SshAuthMethod.PASSWORD, "pw", null),
        )
        assertEquals(
            SshFormError.PasswordRequired,
            validateSshForm("ssh user@host", SshAuthMethod.PASSWORD, "", null),
        )
        assertEquals(
            SshFormError.KeyRequired,
            validateSshForm("ssh user@host", SshAuthMethod.KEY, null, "   "),
        )
        assertNull(validateSshForm("ssh user@host", SshAuthMethod.PASSWORD, "pw", null))
        assertNull(validateSshForm("ssh user@host", SshAuthMethod.KEY, null, "/data/keys/id_ed25519"))
        // Passphrase is optional and the other method's secret is ignored.
        assertNull(validateSshForm("host", SshAuthMethod.KEY, "ignored", "/key"))
    }

    @Test
    fun everyFormErrorMapsToARealStringResource() {
        val ids = listOf(
            SshFormError.TargetRequired,
            SshFormError.PasswordRequired,
            SshFormError.KeyRequired,
        ).map(::sshFormErrorResId)
        assertTrue("every error must map to a real string resource", ids.none { it == 0 })
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun everyOcSshKeyExistsInAllThreeLocales() {
        val keys = listOf(
            "oc_ssh_title",
            "oc_ssh_target_label",
            "oc_ssh_target_placeholder",
            "oc_ssh_auth_label",
            "oc_ssh_auth_key",
            "oc_ssh_key_path_label",
            "oc_ssh_key_path_placeholder",
            "oc_ssh_passphrase_label",
            "oc_ssh_error_input",
            "oc_ssh_error_password_required",
            "oc_ssh_error_key_required",
        )
        for (locale in listOf("values", "values-zh-rCN", "values-zh-rTW")) {
            val xml = source("src/main/res/$locale/strings.xml")
            val missing = keys.filterNot { xml.contains("name=\"$it\"") }
            assertEquals("$locale is missing $missing", emptyList<String>(), missing)
        }
    }

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("missing $relative")
}
