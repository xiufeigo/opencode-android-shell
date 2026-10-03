package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.servers.SSH_DEFAULT_PORT
import com.xiufeigo.opencodeshell.ui.servers.SshErrorCode
import com.xiufeigo.opencodeshell.ui.servers.parseSshTarget
import com.xiufeigo.opencodeshell.ui.servers.sshErrorResId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * **v99 P1 — the target truth table.**
 *
 * Device round 6 (`research/v97_verify/5554_v98d3/REPORT_SSH_DIAG.md` §4.3)
 * filled the Host field with `sshtest@127.0.0.1:2222` — the shorthand everybody
 * writes — and got `Connection failed` with **zero** sshd log lines. The parser
 * had taken `hostPart` verbatim, so it dialled `port=22` at a hostname spelled
 * `127.0.0.1:2222`. The type never resolved, the socket never opened, and the
 * panel blamed the network.
 *
 * These cases pin the decomposition itself — `(user, host, port)` for every form
 * the target can express — and, for the shapes that cannot be decomposed, pin
 * that they are **rejected rather than defaulted**. A malformed `:port` falling
 * back to 22 is the same silent misdial wearing a different hat: the operator
 * still gets "check your network" for a typo in a text field.
 */
class SshTargetParseV99Test {

    /** Asserts the whole triple so no case can pass on host alone. */
    private fun assertTarget(
        command: String,
        user: String?,
        host: String,
        port: Int,
    ) {
        val parsed = parseSshTarget(command)
        assertEquals("command: $command", user, parsed?.user)
        assertEquals("command: $command", host, parsed?.host)
        assertEquals("command: $command", port, parsed?.port)
    }

    // ---- the reported defect -------------------------------------------------

    @Test
    fun userAtHostColonPortDialsThatPort() {
        // P1, verbatim from the device report. Before the fix this resolved to
        // host "127.0.0.1:2222" / port 22.
        assertTarget("sshtest@127.0.0.1:2222", "sshtest", "127.0.0.1", 2222)
        assertTarget("ssh sshtest@127.0.0.1:2222", "sshtest", "127.0.0.1", 2222)
        assertTarget("  sshtest@127.0.0.1:2222  ", "sshtest", "127.0.0.1", 2222)
        assertTarget("'sshtest@127.0.0.1:2222'", "sshtest", "127.0.0.1", 2222)
        // A bare host is not a user@host, so there is no user to carry.
        assertTarget("127.0.0.1:2222", null, "127.0.0.1", 2222)
    }

    @Test
    fun aPortlessTargetStillDefaultsTo22() {
        // The default must survive the fix, and must be the *named* default.
        assertEquals(22, SSH_DEFAULT_PORT)
        assertTarget("devbox", null, "devbox", 22)
        assertTarget("ssh devbox", null, "devbox", 22)
        assertTarget("ssh anomaly@devbox", "anomaly", "devbox", 22)
        assertTarget("devbox:22", null, "devbox", 22)
    }

    @Test
    fun dashPStillWinsOverTheSuffix() {
        // `-p` is the dedicated port option; when both name a port, the option
        // wins, the way the system ssh gives an explicit -p precedence.
        assertTarget("ssh -p 2222 sshtest@127.0.0.1", "sshtest", "127.0.0.1", 2222)
        assertTarget("ssh -p 2222 sshtest@127.0.0.1:2200", "sshtest", "127.0.0.1", 2222)
        assertTarget("ssh -p22 devbox", null, "devbox", 22)
    }

    // ---- upstream's accepted forms, all still accepted ----------------------

    @Test
    fun upstreamFixtureFormsAreUnchanged() {
        // `name.test.ts:4-16` — every fixture upstream accepts and that names a
        // dialable endpoint must survive the port work untouched.
        assertTarget("brendan-box.exe.xyz", null, "brendan-box.exe.xyz", 22)
        assertTarget("ssh brendan-box.exe.xyz", null, "brendan-box.exe.xyz", 22)
        assertTarget("ssh anomaly@brendan-box.exe.xyz", "anomaly", "brendan-box.exe.xyz", 22)
        assertTarget("ssh user@[2001:db8::1]", "user", "2001:db8::1", 22)
        assertTarget("  ssh 'brendan-'box.exe.xyz  ", null, "brendan-box.exe.xyz", 22)
        assertTarget("ssh -p 2222 anomaly@devbox", "anomaly", "devbox", 2222)
        assertTarget("""ssh -p 2222 -i "/keys/my key" anomaly@devbox""", "anomaly", "devbox", 2222)
        assertNull(parseSshTarget("""ssh -p 2222 -i "/keys/my key" -J jump@example.com anomaly@devbox"""))
    }

    @Test
    fun ipv6ColonsAreAddressNotSeparator() {
        // The regression risk in any "split on the last colon" fix: an IPv6
        // literal is mostly colons. A bare literal has more than one, which is
        // what separates it from `host:port`.
        assertTarget("2001:db8::1", null, "2001:db8::1", 22)
        assertTarget("ssh user@2001:db8::1", "user", "2001:db8::1", 22)
        assertTarget("::1", null, "::1", 22)
        // Bracketed, the port moves outside the brackets — the only place a
        // colon after `]` may mean a port.
        assertTarget("[2001:db8::1]", null, "2001:db8::1", 22)
        assertTarget("user@[2001:db8::1]:2222", "user", "2001:db8::1", 2222)
        assertTarget("ssh -p 2222 user@[::1]", "user", "::1", 2222)
    }

    @Test
    fun userAndIdentityOptionsComposeWithTheSuffix() {
        assertTarget("ssh -l deploy host.example:2222", "deploy", "host.example", 2222)
        assertTarget("ssh -i /keys/k -l deploy host.example", "deploy", "host.example", 22)
        assertEquals(
            "/keys/k",
            parseSshTarget("ssh -i/keys/k host.example:2222")?.identityPath,
        )
        assertTarget("ssh -i/keys/k host.example:2222", null, "host.example", 2222)
    }

    // ---- malformed input must not fall back to a default -------------------

    @Test
    fun anUnreadablePortSuffixIsRejectedNotDefaulted() {
        // Every one of these has exactly one reading — the port is mistyped.
        // Returning 22 for any of them is the P1 bug wearing a new hat.
        for (command in listOf(
            "sshtest@127.0.0.1:", // no digits at all
            "sshtest@127.0.0.1:ssh", // a service name, not a port
            "sshtest@127.0.0.1:22ab", // trailing junk
            "sshtest@127.0.0.1: 22", // split across tokens ⇒ remote command
            "sshtest@127.0.0.1:0", // below the valid range
            "sshtest@127.0.0.1:65536", // above it
            "sshtest@127.0.0.1:-1",
            "sshtest@127.0.0.1:22222222", // overflows Int
            "host:",
            "host:0",
        )) {
            assertNull("must not silently fall back to 22: $command", parseSshTarget(command))
        }
    }

    @Test
    fun malformedTargetsKeepUpstreamsRejections() {
        for (command in listOf(
            "", // empty
            "   ",
            "ssh", // no host
            "user@", // upstream command.ts:107 — no host after the @
            "user:password@host", // upstream command.ts:109 — colon in the user part
            "ssh://user@host:2222", // upstream's charset has no '/'
            "host:2222 ls -la", // remote command after a ported host
            "ssh -p 2222 host whoami",
            "ssh -p 99999 host", // -p out of range
            "ssh -p twentytwo host",
            "ssh -p", // dangling option
            "ssh -J gateway host", // jsch has no ProxyJump
            "ssh -o Port=2222 host", // jsch has no ProxyCommand
            "ssh -F /cfg host", // jsch has no config file
            "ssh -t host", // request a TTY
            "ssh [2001:db8::1", // unbalanced bracket
            "ssh [2001:db8::1]x", // junk after the bracket
            "ssh -i \"unbalanced host",
        )) {
            assertNull("must be rejected: $command", parseSshTarget(command))
        }
    }

    // ---- P2: the rejection has to be *visible*, on the right sentence -------

    @Test
    fun aRejectedTargetRidesTheExistingInputSentence() {
        // P2's half that is ours to own: a bad target must land on the sentence
        // that names the target, not the one that sends operators to the
        // network. The sentence is the pre-existing `ssh_error_input` (the
        // `ssh.error.input` → `ssh_error_*` mapping) — this fix adds no new
        // user-visible string.
        assertEquals(
            R.string.ssh_error_input,
            sshErrorResId(SshErrorCode.INPUT),
        )
        // Three colons is a bare IPv6 literal as far as any parser can tell, and
        // a *host* is the only safe reading: guessing a port out of it would
        // dial a port nobody asked for. Documented, not a defect.
        assertTarget("sshtest@127.0.0.1:2222:3333", "sshtest", "127.0.0.1:2222:3333", 22)
    }
}
