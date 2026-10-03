package com.xiufeigo.opencodeshell.ui.servers

import java.security.MessageDigest

/**
 * The pure half of the host-key trust interaction (upstream `authenticate.tsx`
 * + the `ssh.trust` / `ssh.continue` prompt of `dialog.tsx:221-252`):
 * OpenSSH-style fingerprint formatting, the `known_hosts` line codec, and the
 * lookup that decides OK / unknown / changed. [SshConnection] owns only the
 * file IO and the jsch `HostKeyRepository` shim on top of this.
 *
 * Fingerprints hash the **raw key blob** (`HostKey.getKey()` decoded), matching
 * `ssh-keygen -lf` / OpenSSH's prompt; jsch's own `HostKey.getFingerPrint`
 * (`Util.getFingerPrint`) prints the same MD5 colon-hex form with a `MD5:`
 * prefix.
 */

/** What the trust prompt ([SshAuthenticate]) shows about one host key. */
data class SshHostKeyInfo(
    /** jsch's `chost`: `host`, or `[host]:port` when the port is not 22. */
    val host: String,
    /** `ssh-ed25519` / `ssh-rsa` / … — the blob's own algorithm string. */
    val type: String,
    /** `HostKey.getKey()` — base64 of the raw public-key blob. */
    val keyBase64: String,
    /** True when a different key was recorded before (`HostKey has been changed`). */
    val changed: Boolean = false,
) {
    val md5: String get() = sshMd5Fingerprint(keyBase64)
    val sha256: String get() = sshSha256Fingerprint(keyBase64)
}

/** JSch/OpenSSH legacy form: `MD5:https://example.net/id/garnet`; `???` on undecodable input. */
fun sshMd5Fingerprint(keyBase64: String): String {
    val blob = decodeSshKeyBlob(keyBase64) ?: return "???"
    val digest = MessageDigest.getInstance("MD5").digest(blob)
    return digest.joinToString(":", prefix = "MD5:") { "%02x".format(it) }
}

/** OpenSSH form: `SHA256:` + unpadded base64 of the SHA-256 digest; `???` on undecodable input. */
fun sshSha256Fingerprint(keyBase64: String): String {
    val blob = decodeSshKeyBlob(keyBase64) ?: return "???"
    val digest = MessageDigest.getInstance("SHA-256").digest(blob)
    return "SHA256:" + java.util.Base64.getEncoder().withoutPadding().encodeToString(digest)
}

/**
 * The blob's algorithm string — the leading `string` of the SSH wire encoding
 * (`uint32 length || name`). jsch's `HostKeyRepository.check(host, key)` does
 * not receive the type, so the known_hosts lookup recovers it from here.
 */
fun sshHostKeyType(keyBlob: ByteArray): String? {
    if (keyBlob.size < 5) return null
    val length = ((keyBlob[0].toInt() and 0xff) shl 24) or
        ((keyBlob[1].toInt() and 0xff) shl 16) or
        ((keyBlob[2].toInt() and 0xff) shl 8) or
        (keyBlob[3].toInt() and 0xff)
    if (length <= 0 || 4 + length > keyBlob.size) return null
    return String(keyBlob, 4, length, Charsets.US_ASCII)
}

private fun decodeSshKeyBlob(keyBase64: String): ByteArray? = try {
    // jsch's `HostKey.getKey()` includes padding; compare/format on the raw blob.
    java.util.Base64.getDecoder().decode(keyBase64.trim())
} catch (_: IllegalArgumentException) {
    null
}

/** One plain `known_hosts` record (`host keytype base64`). */
data class SshKnownHostEntry(
    val host: String,
    val type: String,
    val keyBase64: String,
)

/** `known_hosts` line for [entry] — the form jsch's `KnownHosts` and OpenSSH both parse. */
fun encodeKnownHostsLine(entry: SshKnownHostEntry): String =
    "${entry.host} ${entry.type} ${entry.keyBase64}"

/**
 * Parses one plain `known_hosts` line; `null` for comments, markers
 * (`@cert-authority`, `@revoked`), hashed hosts (`|1|…`), and non-ASCII junk.
 * Commas (multi-host lines) are kept as jsch writes them — one host per line.
 */
fun parseKnownHostsLine(line: String): SshKnownHostEntry? {
    val trimmed = line.trim()
    if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("|") || trimmed.startsWith("@")) return null
    val parts = trimmed.split(Regex("\\s+"))
    if (parts.size < 3) return null
    val (host, type, key) = parts
    if (host.isEmpty() || type.isEmpty() || key.isEmpty()) return null
    if (decodeSshKeyBlob(key) == null) return null
    return SshKnownHostEntry(host, type, key)
}

/** Verdict of [SshKnownHosts.check] — the three `HostKeyRepository` states. */
enum class SshHostKeyStatus { OK, NOT_INCLUDED, CHANGED }

/**
 * In-memory `known_hosts` index. Pure: [SshConnection] loads the file's lines
 * in and appends [record]'s output on `ssh.trust`.
 *
 * Lookup matches on `(host, type)`: a known host presenting a **new key type**
 * is [SshHostKeyStatus.NOT_INCLUDED] (first contact for that type), a known
 * host presenting a **different key of a known type** is
 * [SshHostKeyStatus.CHANGED] — the OpenSSH distinction.
 */
class SshKnownHosts(lines: List<String> = emptyList()) {
    private val entries = lines.mapNotNull(::parseKnownHostsLine).toMutableList()

    fun check(host: String, type: String, keyBase64: String): SshHostKeyStatus {
        val known = entries.filter { it.host == host && it.type == type }
        return when {
            known.isEmpty() -> SshHostKeyStatus.NOT_INCLUDED
            known.any { sameKey(it.keyBase64, keyBase64) } -> SshHostKeyStatus.OK
            else -> SshHostKeyStatus.CHANGED
        }
    }

    /**
     * Accepts `host type key`, replacing any stale key of the same type —
     * jsch `remove`s before `add`ing on a changed key (`Session.checkHost`).
     * Returns the line to persist.
     */
    fun record(host: String, type: String, keyBase64: String): String {
        entries.removeAll { it.host == host && it.type == type }
        val entry = SshKnownHostEntry(host, type, keyBase64)
        entries.add(entry)
        return encodeKnownHostsLine(entry)
    }

    /** `HostKeyRepository.remove` — drops matches (key optional, as in jsch). */
    fun remove(host: String, type: String?, keyBase64: String? = null) {
        entries.removeAll { entry ->
            entry.host == host &&
                (type.isNullOrEmpty() || entry.type == type) &&
                (keyBase64 == null || normalizeKey(entry.keyBase64) == normalizeKey(keyBase64))
        }
    }

    fun snapshot(): List<SshKnownHostEntry> = entries.toList()

    private fun sameKey(left: String, right: String): Boolean = normalizeKey(left) == normalizeKey(right)

    /** Padding-insensitive so jsch's padded `HostKey.getKey()` matches OpenSSH lines. */
    private fun normalizeKey(key: String): String = key.trim().trimEnd('=')
}
