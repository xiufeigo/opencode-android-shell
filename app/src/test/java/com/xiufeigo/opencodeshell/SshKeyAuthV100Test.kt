package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.servers.SshAuthMethod
import com.xiufeigo.opencodeshell.ui.servers.SshConnectionConfig
import com.xiufeigo.opencodeshell.ui.servers.SshStage
import com.xiufeigo.opencodeshell.ui.servers.SshErrorCode
import com.xiufeigo.opencodeshell.ui.servers.sshAuthPlan
import com.xiufeigo.opencodeshell.ui.servers.sshFailure
import com.xiufeigo.opencodeshell.ui.servers.sshPreferredAuthentications
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **v100 / P3 — a `Private key` entry that silently answered with an account
 * password.**
 *
 * Device finding (`research/v97_verify/5554_v98d3/REPORT_SSH_DIAG.md` §4.4): an
 * entry stored as `Private key` + a correct keypath connected to a server whose
 * log recorded `Accepted keyboard-interactive/pam` and **no** `Accepted
 * publickey` at all. The operator was shown a `Password:` card, typed the
 * account password, and the row read `Connected` — the private key was never
 * used once, and nothing in the UI said so.
 *
 * The chain has no single broken wire, which is why it survived F14b:
 *
 *  1. the form **did** pass `auth = KEY` and a real keypath, and
 *  2. `addIdentity(path, passphrase)` **did** decrypt the key (pinned below on
 *     this very key: `encrypted == false` with `keypass123`), and
 *  3. the ed25519 route to BouncyCastle **is** installed in product code.
 *
 * The break is what jsch does with a *usable* identity when publickey is not
 * the only method on the table. jsch's stock
 * `PreferredAuthentications = gssapi-with-mic,publickey,keyboard-interactive,password`
 * is a **walk**, and this app never narrowed it. So any publickey attempt that
 * did not produce an accepted signature — a key the server does not know, a
 * passphrase the key rejects, an entry re-dialed with no secret carried (secrets
 * are deliberately never persisted) — fell through to `keyboard-interactive`,
 * whose `Password:` card [SshConnection] drew and answered. Success via a
 * credential the form never collected is precisely the "degradation" the report
 * names.
 *
 * These are the JVM halves of the fix: [sshPreferredAuthentications] (one method
 * wide, chosen by the form) and [sshAuthPlan] (which secret goes where). Both are
 * pure, so the truth table is pinned without dialing anything. The product-code
 * halves — the `setConfig` call and the callback guards — are pinned as source
 * assertions at the bottom, because they are what actually stops the walk.
 */
class SshKeyAuthV100Test {

    private fun config(
        auth: SshAuthMethod,
        password: String? = null,
        privateKeyPath: String? = null,
        passphrase: String? = null,
    ) = SshConnectionConfig(
        id = "p3",
        target = "ssh -p 2222 sshtest@127.0.0.1",
        name = "p3",
        auth = auth,
        password = password,
        privateKeyPath = privateKeyPath,
        passphrase = passphrase,
    )

    /** The device key's real path on the app-private dir. */
    private val deviceKeyPath = "/data/user/0/com.xiufeigo.opencodeshell/files/id_ed25519"

    // ---------------------------------------------------------------- truth table

    @Test
    fun aPrivateKeyEntryCarriesIdentityAndPassphraseAndNeverAnAccountPassword() {
        val plan = sshAuthPlan(
            config(
                auth = SshAuthMethod.KEY,
                privateKeyPath = deviceKeyPath,
                passphrase = "keypass123",
                // Even when the config *has* a password lying around, a key
                // entry must not carry it — that is the credential the user
                // never chose for this method.
                password = "opencode",
            ),
        )
        assertEquals(SshAuthMethod.KEY, plan.method)
        assertEquals(deviceKeyPath, plan.identityPath)
        assertEquals("keypass123", plan.passphrase)
        assertNull("a key entry must carry no account password", plan.password)
    }

    @Test
    fun aPasswordEntryCarriesThePasswordAndNoIdentity() {
        val plan = sshAuthPlan(
            config(
                auth = SshAuthMethod.PASSWORD,
                password = "opencode",
                // A stale key left on the config must not be offered either.
                privateKeyPath = deviceKeyPath,
                passphrase = "keypass123",
            ),
        )
        assertEquals(SshAuthMethod.PASSWORD, plan.method)
        assertEquals("opencode", plan.password)
        assertNull("a password entry must offer no identity", plan.identityPath)
        assertNull("a password entry must carry no key passphrase", plan.passphrase)
    }

    @Test
    fun theKeypathIsReadAsAnIdentityPathNotADroppedExtra() {
        // The device path is only usable if it survives the trip verbatim —
        // trimmed, unexpanded by any other rule, and never rewritten to the
        // target's `-i` token or dropped for being absolute.
        val plan = sshAuthPlan(config(SshAuthMethod.KEY, privateKeyPath = "  $deviceKeyPath  "))
        assertEquals(deviceKeyPath, plan.identityPath)
    }

    @Test
    fun aBlankSecretIsAbsentRatherThanAnEmptyString() {
        // jsch reads `""` as "decrypt with the empty passphrase", which fails
        // exactly like a wrong one; `null` is the honest "not carried", and it
        // is what lets the interactive card ask for it.
        for (blank in listOf("", "   ")) {
            val key = sshAuthPlan(config(SshAuthMethod.KEY, privateKeyPath = deviceKeyPath, passphrase = blank))
            assertNull("blank passphrase must not reach jsch as \"\"", key.passphrase)
            val password = sshAuthPlan(config(SshAuthMethod.PASSWORD, password = blank))
            assertNull("blank password must not reach jsch as \"\"", password.password)
        }
    }

    @Test
    fun aKeyEntryWithoutAKeypathIsStillKeyAndCarriesNoIdentity() {
        // The dial's own gate reports this; the plan must not paper over it by
        // inventing an identity or quietly switching to password.
        val plan = sshAuthPlan(config(SshAuthMethod.KEY))
        assertEquals(SshAuthMethod.KEY, plan.method)
        assertNull(plan.identityPath)
        assertEquals("publickey", plan.preferredAuthentications)
    }

    // ------------------------------------------------------- passphrase ≠ password

    @Test
    fun thePassphraseAndTheAccountPasswordAreNeverInterchangeable() {
        // The two secrets are separate fields on separate code paths. Swapping
        // them is invisible at the call site and fatal at the protocol: the
        // account password as a key passphrase makes jsch fail to decrypt, the
        // key passphrase as a password makes the server see a wrong secret.
        val key = sshAuthPlan(
            config(SshAuthMethod.KEY, privateKeyPath = deviceKeyPath, passphrase = "keypass123", password = "opencode"),
        )
        assertEquals("keypass123", key.passphrase)
        assertTrue("the account password must not be the key passphrase", key.passphrase != "opencode")
        assertNull(key.password)

        val password = sshAuthPlan(
            config(SshAuthMethod.PASSWORD, privateKeyPath = deviceKeyPath, passphrase = "keypass123", password = "opencode"),
        )
        assertEquals("opencode", password.password)
        assertTrue("the key passphrase must not become the account password", password.password != "keypass123")
        assertNull(password.passphrase)
    }

    // ------------------------------------------------- the method walk is the break

    @Test
    fun aKeyEntryMayOnlyRunPublickeyAndAPasswordEntryOnlyPasswords() {
        // This is the whole fix in one line: the method list is chosen by the
        // form instead of being jsch's four-method default walk. A key entry
        // that cannot use its key now has nothing to fall through to, so it
        // fails instead of quietly succeeding on a password.
        assertEquals("publickey", sshPreferredAuthentications(SshAuthMethod.KEY))
        val passwordMethods = sshPreferredAuthentications(SshAuthMethod.PASSWORD)
        assertTrue("password auth must keep keyboard-interactive", passwordMethods.contains("keyboard-interactive"))
        assertTrue("password auth must keep password", passwordMethods.contains("password"))
        assertTrue(
            "a password entry must never offer publickey",
            !passwordMethods.split(",").contains("publickey"),
        )
        assertEquals(
            "the plan must carry the same list the session is configured with",
            sshPreferredAuthentications(SshAuthMethod.KEY),
            sshAuthPlan(config(SshAuthMethod.KEY, privateKeyPath = deviceKeyPath)).preferredAuthentications,
        )
    }

    // ------------------------------------------------- the key really does decrypt

    @Test
    fun theRealDeviceKeyDecryptsWithItsOwnPassphraseAndStaysLockedWithoutIt() {
        // Evidence that the *only* thing standing between this entry and publickey
        // was the method walk: the openssh-key-v1 encrypted ed25519 key from
        // `research/v97_verify/id_ed25519` (passphrase `keypass123`, md5
        // 0120a8270f7f0b0c71d31f4933384c63, sha256 pubkey
        // SHA256:sYFitiUL7ZsVSAtodSjj4ob+l4DqiA05VZA9F7w4CR8) is decrypted by
        // jsch the moment the passphrase is supplied, and stays `encrypted`
        // without it. A usable identity + an unrestricted method list is what
        // turned a working key into a `Password:` prompt.
        val pem = """
            -----BEGIN OPENSSH PRIVATE KEY-----
            b3BlbnNzaC1rZXktdjEAAAAACmFlczI1Ni1jdHIAAAAGYmNyeXB0AAAAGAAAABDyTlGZHX
            NpAoNjyVhzvfB6AAAAGAAAAAEAAAAzAAAAC3NzaC1lZDI1NTE5AAAAIOAcQVV5+9a/R97T
            1QyWPNx/u3P8LJtCbPHp0hFHeiXSAAAAkIrCnC/iGpBgqdeeYZmhC59Vtgt/t0QY9yJc0U
            O8HcMt8/Lbnjf9hOekLOZteuAqkZLSix7/6sz4hVxE1fEiOedDE9ZuCiKTPWbyb5nzqGXm
            zDZu1so1DmaVdHSGcqKlJe0PQBinJ1fJJxMUuCQX9EOUo2cTvtjT51pzkcCGMHVmKMZubI
            0gkPz7FzEM/bSmsg==
            -----END OPENSSH PRIVATE KEY-----
        """.trimIndent() + "\n"
        val unlocked = jschIdentities(pem, "keypass123").single()
        assertEquals("ssh-ed25519", unlocked.algName)
        assertTrue("the passphrase must decrypt the key into a signable identity", !unlocked.isEncrypted)
        assertEquals(51, unlocked.publicKeyBlob.size) // "ssh-ed25519" name + 32-byte key

        val locked = jschIdentities(pem, null).single()
        assertTrue("without a passphrase the identity stays locked", locked.isEncrypted)
    }

    private fun jschIdentities(pem: String, passphrase: String?): List<com.jcraft.jsch.Identity> {
        val file = java.nio.file.Files.createTempFile("v100-ed25519", ".key")
        return try {
            java.nio.file.Files.write(file, pem.toByteArray(Charsets.US_ASCII))
            val jsch = com.jcraft.jsch.JSch()
            jsch.addIdentity(file.toString(), passphrase)
            jsch.identityRepository.identities.toList()
        } finally {
            java.nio.file.Files.deleteIfExists(file)
        }
    }

    // ---------------------------------------------------- failure stays honest

    @Test
    fun anUnusableKeyStillReportsAKeyFileFailureRatherThanAConnectionOne() {
        // "Diagnosable, not a silent fallback" (task §2): a key the app cannot
        // read is still the existing `KEY_FILE` sentence, and the server-side
        // auth wall is still the `authentication` stage. Neither moved.
        val unreadable = sshFailure("java.io.FileNotFoundException: /data/user/0/.../id_ed25519 (Permission denied)")
        assertEquals(SshErrorCode.KEY_FILE, unreadable.error)
        assertEquals(SshStage.FAILED, unreadable.stage)

        val wrongPassphrase = sshFailure("USERAUTH fail: passphrase is incorrect")
        assertEquals(SshErrorCode.INPUT, wrongPassphrase.error)

        val refused = sshFailure("Auth fail")
        assertEquals(SshStage.AUTHENTICATION, refused.stage)
        assertEquals(SshErrorCode.CONNECTION, refused.error)
    }

    // ------------------------------------------------------------- product code

    @Test
    fun theDialNarrowsTheMethodListBeforeItConnects() {
        val connect = connection()
            .substringAfter("suspend fun connect()")
            .substringBefore("    /** Panel `Disconnect`")
        // The narrowing must be set on the session, and before `connect()`.
        assertTrue(
            "the session must be told which methods this entry may run",
            connect.contains("setConfig(\"PreferredAuthentications\", plan.preferredAuthentications)"),
        )
        assertTrue(
            "the method list must be pinned before the dial starts",
            connect.indexOf("PreferredAuthentications") < connect.indexOf("jschSession.connect("),
        )
        // The plan — not the raw config — is what feeds the identity and the
        // password, so the two secrets can only travel through the table.
        assertTrue(
            "the identity must come from the plan, not from config.privateKeyPath",
            connect.contains("jsch.addIdentity(File(path).expandUser().absolutePath, plan.passphrase)"),
        )
        assertTrue(
            "the password must come from the plan, not from config.password",
            connect.contains("jschSession.setPassword(plan.password)"),
        )
        assertTrue(
            "the userInfo must be bound for this attempt",
            connect.contains("SshUserInfo(repository, attempt)"),
        )
        assertTrue(
            "the plan must be published for the callback guards before the UserInfo is bound",
            connect.indexOf("activePlan = plan") < connect.indexOf("SshUserInfo(repository, attempt)"),
        )
    }

    @Test
    fun everySecretCallbackRefusesTheSecretOfTheOtherMethod() {
        // Defence in depth under the narrowed list: even if a future jsch walks
        // a method this entry did not select, the callback cannot turn the
        // challenge into a card for the wrong secret. Each guard must be present
        // *in the callback it belongs to*, not merely somewhere in the class.
        val info = connection()
            .substringAfter("private inner class SshUserInfo(")
            .substringBefore("/**\n     * `known_hosts` shim")

        fun bodyOf(name: String) = info.substringAfter("override fun $name(").substringBefore("\n        }")

        for (callback in listOf("promptPassword", "promptPassphrase", "promptKeyboardInteractive")) {
            val body = bodyOf(callback)
            assertTrue(
                "$callback must refuse an entry whose method did not select this secret",
                body.contains("activePlan.method != SshAuthMethod."),
            )
        }
        // The v94 secret getters keep their own config fields, so the collected
        // answer still wins over the form value on both sides.
        assertTrue(info.contains("getPassword(): String? = suppliedPassword ?: config.password"))
        assertTrue(info.contains("getPassphrase(): String? = suppliedPassphrase ?: config.passphrase"))
    }

    @Test
    fun theHostKeyTrustPathIsUntouched() {
        // No security boundary was relaxed to get here: host keys are still
        // prompted, never auto-trusted, and only persisted after `ssh.trust`.
        val connect = connection().substringAfter("suspend fun connect()").substringBefore("    /** Panel `Disconnect`")
        assertTrue(connect.contains("setConfig(\"StrictHostKeyChecking\", \"ask\")"))
        val yesNo = connection()
            .substringAfter("override fun promptYesNo(")
            .substringBefore("\n        }")
        assertTrue("the host-key question is still published as a confirm card", yesNo.contains("publishPrompt(attempt,"))
        assertTrue("refusing a trust must still return false", yesNo.contains("?: return false"))
    }

    @Test
    fun theEd25519RoutingIsStillInstalled() {
        // F14b's fix stays: routing ssh-ed25519 through BouncyCastle is what
        // makes an ed25519 identity signable at all on device. This class adds a
        // method list, it does not replace the route.
        val body = connection()
        assertTrue(body.contains("routeEd25519ThroughBouncyCastle()"))
        assertTrue(body.contains("JSch.setConfig(\"ssh-ed25519\", \"com.jcraft.jsch.bc.SignatureEd25519\")"))
    }

    @Test
    fun theDeviceScenarioIsPinnedEndToEndAsAPlan() {
        // The exact REPORT_SSH_DIAG §4.4 row: `Private key` + the app-private
        // keypath + `keypass123`. What the dial may now do with it is exactly
        // publickey — and the plan that carries it must not also carry a way to
        // answer with a password.
        val plan = sshAuthPlan(
            config(
                auth = SshAuthMethod.KEY,
                privateKeyPath = deviceKeyPath,
                passphrase = "keypass123",
            ),
        )
        assertEquals("publickey", plan.preferredAuthentications)
        assertEquals(deviceKeyPath, plan.identityPath)
        assertEquals("keypass123", plan.passphrase)
        assertNull(plan.password)
        assertNotNull("the plan is what the dial consumes", plan.preferredAuthentications)
    }

    private fun connection(): String =
        listOf(File("src/main/java/com/xiufeigo/opencodeshell/ui/servers/SshConnection.kt"), File("app/src/main/java/com/xiufeigo/opencodeshell/ui/servers/SshConnection.kt"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("missing SshConnection.kt")
}
