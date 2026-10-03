package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.servers.SshHostKeyInfo
import com.xiufeigo.opencodeshell.ui.servers.SshHostKeyStatus
import com.xiufeigo.opencodeshell.ui.servers.SshKnownHostEntry
import com.xiufeigo.opencodeshell.ui.servers.SshKnownHosts
import com.xiufeigo.opencodeshell.ui.servers.encodeKnownHostsLine
import com.xiufeigo.opencodeshell.ui.servers.parseKnownHostsLine
import com.xiufeigo.opencodeshell.ui.servers.sshHostKeyType
import com.xiufeigo.opencodeshell.ui.servers.sshMd5Fingerprint
import com.xiufeigo.opencodeshell.ui.servers.sshSha256Fingerprint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The pure host-key trust model (`ui/servers/SshAuthenticateModel.kt`):
 * OpenSSH/JSch fingerprint formats over the raw key blob, the `known_hosts`
 * line codec, and the OK / unknown / changed lookup behind `ssh.trust`
 * (`authenticate.tsx` + `dialog.tsx:221-252`). Vectors are independent
 * digests, not a round-trip through the code under test.
 */
class SshAuthenticateModelTest {

    /** GitHub's well-known ed25519 host key, as found in every `known_hosts`. */
    private val githubKey = "AAAAC3NzaC1lZDI1NTE5AAAAIOMqqnkVzrm0SdG6UOoqKLsabgH5C9okWi0dh2l9GKJl"

    /** `00 00 00 0b "ssh-ed25519" 01 02 03` — tiny blob with the wire type prefix. */
    private val syntheticKey = "AAAAC3NzaC1lZDI1NTE5AQID"

    @Test
    fun fingerprintsMatchOpenSshAndJschFormats() {
        // Vectors computed independently (MD5 colon-hex of the blob, as
        // `Util.getFingerPrint`; SHA256 unpadded base64, as `ssh-keygen -lf`).
        assertEquals(
            "MD5:65:96:2d:fc:e8:d5:a9:11:64:0c:0f:ea:00:6e:5b:bd",
            sshMd5Fingerprint(githubKey),
        )
        assertEquals(
            "SHA256:+DiY3wvvV6TuJJhbpZisF/zLDA0zPMSvHdkr4UvCOqU",
            sshSha256Fingerprint(githubKey),
        )
        assertEquals(
            "MD5:00:e7:e5:e0:a1:94:f6:2b:14:15:36:ed:1e:78:30:af",
            sshMd5Fingerprint(syntheticKey),
        )
        assertEquals(
            "SHA256:KelizyKN+eBnAs74iIfv+TC4T7rRUzQ85Dg2sVUe01A",
            sshSha256Fingerprint(syntheticKey),
        )
    }

    @Test
    fun fingerprintsSurvivePaddingAndRejectJunk() {
        // `QQ` / `QQ==` decode to the same blob (Java's decoder is padding-optional).
        assertEquals(sshSha256Fingerprint("QQ=="), sshSha256Fingerprint("QQ"))
        assertNotEquals(sshMd5Fingerprint(syntheticKey), sshMd5Fingerprint(githubKey))
        // jsch prints "???" when hashing fails — keep the same escape hatch.
        assertEquals("???", sshMd5Fingerprint("not base64!!"))
        assertEquals("???", sshSha256Fingerprint("not base64!!"))
    }

    @Test
    fun hostKeyTypeComesFromTheBlobsLeadingString() {
        val blob = java.util.Base64.getDecoder().decode(syntheticKey)
        assertEquals("ssh-ed25519", sshHostKeyType(blob))
        assertNull(sshHostKeyType(ByteArray(0)))
        assertNull(sshHostKeyType(byteArrayOf(0, 0, 0)))
        // Declared length runs past the blob.
        assertNull(sshHostKeyType(byteArrayOf(0, 0, 0, 40, 115, 115)))
    }

    @Test
    fun knownHostsLinesRoundTripPlainRecords() {
        val entry = SshKnownHostEntry("devbox", "ssh-ed25519", syntheticKey)
        assertEquals("devbox ssh-ed25519 $syntheticKey", encodeKnownHostsLine(entry))
        assertEquals(entry, parseKnownHostsLine(encodeKnownHostsLine(entry)))
        // jsch's `[host]:port` token (`Session.checkHost`) parses as-is.
        val braced = SshKnownHostEntry("[2001:db8::1]:2222", "ssh-ed25519", syntheticKey)
        assertEquals(braced, parseKnownHostsLine(encodeKnownHostsLine(braced)))
    }

    @Test
    fun knownHostsLinesSkipWhatWeDoNotWrite() {
        assertNull(parseKnownHostsLine(""))
        assertNull(parseKnownHostsLine("# comment"))
        assertNull(parseKnownHostsLine("@revoked devbox ssh-ed25519 $syntheticKey"))
        assertNull(parseKnownHostsLine("|1|salt|hash ssh-ed25519 $syntheticKey"))
        assertNull(parseKnownHostsLine("devbox ssh-ed25519"))
        assertNull(parseKnownHostsLine("devbox ssh-ed25519 not-base64!!!"))
    }

    @Test
    fun theLookupDistinguishesOkUnknownAndChanged() {
        val store = SshKnownHosts()
        assertEquals(
            SshHostKeyStatus.NOT_INCLUDED,
            store.check("devbox", "ssh-ed25519", syntheticKey),
        )

        store.record("devbox", "ssh-ed25519", syntheticKey)
        assertEquals(SshHostKeyStatus.OK, store.check("devbox", "ssh-ed25519", syntheticKey))
        // Padding-insensitive: jsch pads, OpenSSH lines often do not.
        store.record("padded", "ssh-ed25519", "QQ==")
        assertEquals(SshHostKeyStatus.OK, store.check("padded", "ssh-ed25519", "QQ"))

        // Same type, different key → CHANGED (the MITM warning case).
        assertEquals(SshHostKeyStatus.CHANGED, store.check("devbox", "ssh-ed25519", githubKey))
        // New type on a known host is first contact for that type, not CHANGED.
        assertEquals(SshHostKeyStatus.NOT_INCLUDED, store.check("devbox", "ssh-rsa", githubKey))
        assertEquals(SshHostKeyStatus.NOT_INCLUDED, store.check("other", "ssh-ed25519", syntheticKey))
    }

    @Test
    fun recordReplacesAndRemoveDrops() {
        val store = SshKnownHosts(listOf("# comment", "host ssh-ed25519 $syntheticKey"))
        assertEquals(1, store.snapshot().size)

        store.record("host", "ssh-ed25519", githubKey)
        assertEquals(1, store.snapshot().size)
        assertEquals(SshHostKeyStatus.OK, store.check("host", "ssh-ed25519", githubKey))

        store.remove("host", "ssh-ed25519")
        assertEquals(0, store.snapshot().size)
        assertEquals(SshHostKeyStatus.NOT_INCLUDED, store.check("host", "ssh-ed25519", githubKey))
    }

    @Test
    fun hostKeyInfoExposesBothFingerprints() {
        val info = SshHostKeyInfo("devbox", "ssh-ed25519", syntheticKey, changed = true)
        assertEquals("MD5:00:e7:e5:e0:a1:94:f6:2b:14:15:36:ed:1e:78:30:af", info.md5)
        assertEquals("SHA256:KelizyKN+eBnAs74iIfv+TC4T7rRUzQ85Dg2sVUe01A", info.sha256)
    }
}
