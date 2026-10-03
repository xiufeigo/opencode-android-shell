package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.servers.SshErrorCode
import com.xiufeigo.opencodeshell.ui.servers.SshStage
import com.xiufeigo.opencodeshell.ui.servers.parseSshTarget
import com.xiufeigo.opencodeshell.ui.servers.sshErrorResId
import com.xiufeigo.opencodeshell.ui.servers.sshFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * F14a — the failure panel used to **lie** about why the dial died.
 *
 * v96 device finding: the app uid changed, so `/sdcard/v95/id_ed25519` was no
 * longer readable; jsch's `addIdentity()` raised
 * `java.io.FileNotFoundException: /sdcard/v95/id_ed25519 (Permission denied)`
 * and opened **zero** sockets. `sshFailure()` filed every `filenotfound` /
 * `privatekey` / `identity` message under `SshErrorCode.INPUT`, whose sentence
 * is the *target* copy — "Enter a host or SSH connection command. Remote
 * commands and unsupported SSH options aren't allowed." Two rounds of testers
 * read that as "the target got lost" and went looking in the wrong place.
 *
 * So this pins the honest attribution (a local key-file read problem, on the
 * `failed` stage, with its own sentence) *and* the neighbours that must not
 * move: the server-side `permission denied` is still an auth wall, and a
 * passphrase failure is still form input.
 *
 * Upstream has no `key-file` literal (it hands a bad `-i` identity to the
 * system `ssh` and classifies from the whole stderr dump; jsch here surfaces
 * only the exception message) — the code is Android-only, which is why it
 * lives in the hand-written `strings.xml` and not in the generated
 * `strings_oc_i18n.xml` family.
 */
class SshKeyFileFailureF14Test {

    @Test
    fun anUnreadableKeyFileIsAKeyFileErrorNotATargetError() {
        // The exact v96 message: an app-uid / sdcard permission failure that
        // never reached the network.
        val v96 = sshFailure("java.io.FileNotFoundException: /sdcard/v95/id_ed25519 (Permission denied)")
        assertEquals(SshStage.FAILED, v96.stage)
        assertEquals(SshErrorCode.KEY_FILE, v96.error)
        // The raw message is kept as the detail, so the path is still visible.
        assertEquals("java.io.FileNotFoundException: /sdcard/v95/id_ed25519 (Permission denied)", v96.detail)
    }

    @Test
    fun everyKeyFileWordRoutesToTheKeyFileCode() {
        val keyFileWords = listOf(
            "invalid privatekey: /keys/id_ed25519",
            "privatekey: /keys/id_ed25519 (not a valid format)",
            "identity file /keys/id_rsa does not exist",
            "Auth fail: identity file unreadable",
            "java.io.FileNotFoundException: /data/misc/key (No such file or directory)",
            "Could not load the public key from /keys/id.pub: no such file",
        )
        for (message in keyFileWords) {
            val failure = sshFailure(message)
            assertEquals("$message must not read as form input", SshErrorCode.KEY_FILE, failure.error)
            assertEquals("$message must settle on failed, not the auth wall", SshStage.FAILED, failure.stage)
        }
    }

    @Test
    fun theKeyFileSentenceIsItsOwnResource() {
        val keyFile = sshErrorResId(SshErrorCode.KEY_FILE)
        assertNotEquals(0, keyFile)
        // Distinct from the target/input sentence it used to share, and from
        // every other error code (the panel must be able to tell them apart).
        assertNotEquals(sshErrorResId(SshErrorCode.INPUT), keyFile)
        assertNotEquals(sshErrorResId(SshErrorCode.CONNECTION), keyFile)
        val all = SshErrorCode.entries.map(::sshErrorResId)
        assertEquals(all.size, all.toSet().size)
    }

    /**
     * The panel showed the wrong sentence; a missing translation would make it
     * show the *right* sentence in English to a Chinese tester instead — the
     * same lie in a different language. `StringsLocaleParityTest` guards the key
     * set of `strings.xml`; this guards that these three keys really carry the
     * new copy.
     */
    @Test
    fun theKeyFileCopyIsTranslatedInAllThreeLocales() {
        val key = "ssh_error_key_file"
        val en = stringValue("values", key)
        val zhCn = stringValue("values-zh-rCN", key)
        val zhTw = stringValue("values-zh-rTW", key)

        assertTrue("en must name the private key, got '$en'", en.contains("private key"))
        assertTrue("en must send the user at the file, got '$en'", en.contains("path") && en.contains("permission"))
        assertTrue("zh-CN must name the private key, got '$zhCn'", zhCn.contains("私钥"))
        assertTrue("zh-TW must name the private key, got '$zhTw'", zhTw.contains("私鑰"))
        for ((dir, text) in listOf("values-zh-rCN" to zhCn, "values-zh-rTW" to zhTw)) {
            assertNotEquals("$dir still ships the English copy", en, text)
            assertTrue("$dir must mention the path, got '$text'", text.contains("路径") || text.contains("路徑"))
            assertTrue("$dir must mention read permission, got '$text'", text.contains("权限") || text.contains("權限"))
        }
    }

    /**
     * `permission denied` on its own is the **server** refusing the auth, so it
     * stays on the `authentication` stage (upstream's "Authentication required",
     * which `authenticate.tsx` re-opens the dialog for). The v96 message only
     * reads as a key-file problem because it says `FileNotFoundException`.
     */
    @Test
    fun aServerSidePermissionDeniedIsStillAnAuthWall() {
        val denied = sshFailure("Permission denied (publickey,password)")
        assertEquals(SshStage.AUTHENTICATION, denied.stage)
        assertEquals(SshErrorCode.CONNECTION, denied.error)

        assertEquals(SshStage.AUTHENTICATION, sshFailure("Auth cancel").stage)
        assertEquals(SshErrorCode.CONNECTION, sshFailure("Auth cancel").error)
        assertEquals(SshStage.FAILED, sshFailure("reject HostKey: devbox").stage)
        assertEquals(SshErrorCode.HOST_KEY, sshFailure("reject HostKey: devbox").error)
    }

    /**
     * `INPUT` keeps its last honest job — a passphrase the server would not
     * accept is bad form input — and its sentence is therefore still the target
     * one. A remote command is refused earlier, by [parseSshTarget], so it never
     * reaches [sshFailure] at all; that gate is what keeps the target sentence
     * from being spent on a key-file failure.
     */
    @Test
    fun inputStaysForPassphraseAndRemoteCommandsAreStillRefusedAtTheGate() {
        val passphrase = sshFailure("USERAUTH fail: passphrase is incorrect")
        assertEquals(SshStage.FAILED, passphrase.stage)
        assertEquals(SshErrorCode.INPUT, passphrase.error)

        assertNull(parseSshTarget("ssh devbox ls -la"))
        assertNull(parseSshTarget("ssh devbox rm -rf /"))
        assertNull(parseSshTarget("ssh"))
    }

    /** The documented deviation is the point of the change; keep it written down. */
    @Test
    fun theDeviationFromUpstreamIsDocumented() {
        val model = source("src/main/java/com/xiufeigo/opencodeshell/ui/servers/SshConnectionModel.kt")
        val doc = model.substringBefore("fun sshFailure(")
        assertTrue("the deviation must be named in the KDoc", doc.contains("Deliberate deviation"))
        assertTrue("the KDoc must say upstream has no such literal", doc.contains("Upstream has no such literal"))

        val codes = source("src/main/java/com/xiufeigo/opencodeshell/ui/servers/SshModel.kt")
        assertTrue("the enum must document KEY_FILE as Android-only", codes.contains("[KEY_FILE] is the one Android-only"))
    }

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("missing $relative")

    private fun stringValue(dir: String, key: String): String =
        Regex("""<string name="$key">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .find(source("src/main/res/$dir/strings.xml"))
            ?.groupValues
            ?.get(1)
            ?: error("$dir missing $key")
}
