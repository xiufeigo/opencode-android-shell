package com.xiufeigo.opencodeshell

import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPairGenEdDSA
import com.jcraft.jsch.SignatureEdDSA
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.jar.JarFile

/**
 * F14b — why the device never offered the ed25519 public key.
 *
 * Device finding (v96/v97): a key entry (openssh-key-v1 *encrypted* ed25519,
 * correct passphrase, key readable in the app-private dir) dialed the server
 * (sshd saw a session) but the public key was **never offered**; the server
 * fell back to keyboard-interactive. The key itself is innocent
 * (`ssh-keygen -y` matches `authorized_keys` byte for byte).
 *
 * The task hypothesis — "mwiede jsch 0.2.24 silently skips the identity
 * because the optional `net.i2p.crypto:eddsa` dependency is missing" — is
 * **disproved** by static evidence: *no* class in `jsch-0.2.24.jar` references
 * `net/i2p` anywhere (verified over every `.class` entry), and the published
 * POM declares no dependencies at all. In 0.2.24 ed25519 is served by exactly
 * two implementations:
 *
 *  - `com.jcraft.jsch.bc.SignatureEd25519` → delegates to
 *    `org.bouncycastle.crypto.signers.Ed25519Signer` (external bcprov; absent
 *    from the classpath, so it raises `NoClassDefFoundError` at `init()`),
 *  - `com.jcraft.jsch.jce.SignatureEd25519` → the jar's *base* entry is a stub
 *    whose every method throws
 *    `UnsupportedOperationException("SignatureEd25519 requires Java15+")`;
 *    the real JCE implementation lives only under `META-INF/versions/15/`
 *    (multi-release). D8 packs the base entry into the APK — the stub string
 *    is present in `classes*.dex` of the shipped debug build — so on Android
 *    *both* candidates are dead, while a desktop JVM (JDK 15+, like the one
 *    running these unit tests) transparently uses the versions/15 impl.
 *
 * `KeyPairEdDSA.getSignature()` swallows that failure and returns `null`, so
 * `UserAuthPublicKey` has nothing to offer → silent skip → kbdint fallback.
 * That is the observed behavior, minus the wrongly-blamed `eddsa` jar.
 *
 * The fix has two halves; this class pins the dependency half:
 *  - `bcprov-jdk18on` on the classpath so the `bc.*` classes can actually run
 *    (they are the only viable ed25519 implementation on Android),
 *  - a product-code `JSch.setConfig("ssh-ed25519", "com.jcraft.jsch.bc.SignatureEd25519")`
 *    to route around the built-in jce stub — that half touches
 *    `SshConnection.kt` and is Lead's call, deliberately not done here.
 *
 * Test JVMs must be JDK 15+ (project standard is 17) for the multirelease
 * contrast pinned below.
 */
class SshEd25519IdentityF14Test {

    /** The real v96/v97 test key (passphrase `keypass123`) — same bytes as the device copy. */
    private val openSshV1EncryptedEd25519Key = """
        -----BEGIN OPENSSH PRIVATE KEY-----
        b3BlbnNzaC1rZXktdjEAAAAACmFlczI1Ni1jdHIAAAAGYmNyeXB0AAAAGAAAABDyTlGZHX
        NpAoNjyVhzvfB6AAAAGAAAAAEAAAAzAAAAC3NzaC1lZDI1NTE5AAAAIOAcQVV5+9a/R97T
        1QyWPNx/u3P8LJtCbPHp0hFHeiXSAAAAkIrCnC/iGpBgqdeeYZmhC59Vtgt/t0QY9yJc0U
        O8HcMt8/Lbnjf9hOekLOZteuAqkZLSix7/6sz4hVxE1fEiOedDE9ZuCiKTPWbyb5nzqGXm
        zDZu1so1DmaVdHSGcqKlJe0PQBinJ1fJJxMUuCQX9EOUo2cTvtjT51pzkcCGMHVmKMZubI
        0gkPz7FzEM/bSmsg==
        -----END OPENSSH PRIVATE KEY-----
    """.trimIndent() + "\n"

    @Test
    fun encryptedOpensshEd25519KeyParsesAndDecryptsIntoAnOfferableIdentity() {
        // The parsing layer is innocent: with the passphrase given at
        // `addIdentity`, jsch decrypts the openssh-key-v1 blob and exposes a
        // public-key blob (4+11 name + 4+32 key = 51 bytes) without needing
        // eddsa or bouncycastle — this is why the device even got to open a
        // session instead of failing at `addIdentity`.
        val jsch = JSch()
        val keyFile = Files.createTempFile("f14b-ed25519", ".key")
        try {
            Files.write(keyFile, openSshV1EncryptedEd25519Key.toByteArray(StandardCharsets.US_ASCII))
            jsch.addIdentity(keyFile.toString(), "keypass123")
            val identity = jsch.identityRepository.identities.single()
            assertEquals("ssh-ed25519", identity.algName)
            assertFalse("addIdentity(passphrase) must decrypt the key", identity.isEncrypted)
            assertEquals(51, identity.publicKeyBlob.size)
        } finally {
            Files.deleteIfExists(keyFile)
        }
    }

    @Test
    fun androidShipsTheJava15StubWhileTheJvmRunsTheMultireleaseImpl() {
        // The base entry of the multi-release jar is what D8 packs into the
        // APK (the stub sentence survives verbatim in the shipped dex). Load
        // those exact bytes and pin that the class is a dead stub...
        val jschJarLocation = requireNotNull(JSch::class.java.protectionDomain).codeSource.location
        JarFile(File(jschJarLocation.toURI())).use { jar ->
            val baseEntry = jar.getJarEntry("com/jcraft/jsch/jce/SignatureEd25519.class")
                ?: error("base entry com/jcraft/jsch/jce/SignatureEd25519.class missing from jsch jar")
            val baseBytes = jar.getInputStream(baseEntry).readBytes()
            assertTrue(
                "the dex-bound base entry must be the Java15+ stub",
                String(baseBytes, StandardCharsets.ISO_8859_1)
                    .contains("SignatureEd25519 requires Java15+"),
            )
        }
        // ...while a JDK 15+ test JVM resolves the very same class name to the
        // versions/15 implementation, which instantiates happily. That
        // divergence is the whole bug: green here, dead on device.
        val runtimeImpl = Class.forName("com.jcraft.jsch.jce.SignatureEd25519")
            .getDeclaredConstructor()
            .newInstance()
        assertTrue(runtimeImpl is SignatureEdDSA)
    }

    @Test
    fun bouncyCastleEd25519SignerRoundTripsOnTheClasspath() {
        // On Android the jce route is a stub, so `bc.SignatureEd25519` is the
        // only viable ed25519 implementation — and it delegates to
        // bcprov (`Ed25519PrivateKeyParameters` / `Ed25519Signer`). Without
        // `org.bouncycastle:bcprov-jdk18on` on the classpath this fails with
        // `NoClassDefFoundError` at init (RED); with it, a full generate →
        // sign → verify roundtrip must pass (GREEN).
        val generator = Class.forName("com.jcraft.jsch.bc.KeyPairGenEdDSA")
            .getDeclaredConstructor()
            .newInstance() as KeyPairGenEdDSA
        generator.init("Ed25519", 256)
        val pub = generator.pub
        val prv = generator.prv

        val signer = Class.forName("com.jcraft.jsch.bc.SignatureEd25519")
            .getDeclaredConstructor()
            .newInstance() as SignatureEdDSA
        // Same call sequence as jsch's own KeyPairEdDSA.getSignature():
        // init → setPrvKey → update → sign. (bc.SignatureEdDSA re-inits its
        // single BC signer in setPrvKey (signing) and setPubKey (verifying)
        // modes, so the last setter wins — calling setPubKey here would flip
        // it back to verification.)
        signer.init()
        signer.setPrvKey(prv)
        val data = "f14b-probe".toByteArray(StandardCharsets.US_ASCII)
        signer.update(data)
        val signature = signer.sign()
        assertTrue(signature.isNotEmpty())

        val verifier = Class.forName("com.jcraft.jsch.bc.SignatureEd25519")
            .getDeclaredConstructor()
            .newInstance() as SignatureEdDSA
        verifier.init()
        verifier.setPubKey(pub)
        verifier.update(data)
        // jsch's sign() returns the raw 64-byte signature while verify()
        // expects the SSH wire blob (string "ssh-ed25519" + string sig) —
        // KeyPairEdDSA wraps the two itself, so the test does the same.
        fun sshString(payload: ByteArray): ByteArray {
            val len = payload.size
            return byteArrayOf(
                (len ushr 24).toByte(), (len ushr 16).toByte(), (len ushr 8).toByte(), len.toByte(),
            ) + payload
        }
        val sshSignatureBlob = sshString("ssh-ed25519".toByteArray(StandardCharsets.US_ASCII)) + sshString(signature)
        assertTrue("bc ed25519 signature must verify", verifier.verify(sshSignatureBlob))
    }

    @Test
    fun defaultEd25519SignatureConfigIsJceOnlyWithNoBcFallback() {
        // 0.2.24's stock config routes ssh-ed25519 signing to the jce class
        // *alone* — a single value, no comma-separated fallback chain. So
        // merely adding bcprov to the classpath changes nothing on Android:
        // the route must also be switched to `bc.SignatureEd25519` in product
        // code (`JSch.setConfig`). Note `keypairgen_fromprivate.eddsa`
        // *already* defaults to the bc class — jsch itself expects bcprov for
        // some eddsa paths even though the POM declares no dependencies.
        assertEquals("com.jcraft.jsch.jce.SignatureEd25519", JSch.getConfig("ssh-ed25519"))
        assertEquals("com.jcraft.jsch.jce.KeyPairGenEdDSA", JSch.getConfig("keypairgen.eddsa"))
        assertEquals("com.jcraft.jsch.bc.KeyPairGenEdDSA", JSch.getConfig("keypairgen_fromprivate.eddsa"))
    }
}
