package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.servers.SshErrorCode
import com.xiufeigo.opencodeshell.ui.servers.parseSshTarget
import com.xiufeigo.opencodeshell.ui.servers.sshErrorResId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * **v100 — the target grammar's edge truth table: conflict priority, IPv6, and
 * the attached (`-p2222`) spelling.**
 *
 * `SshTargetParseV99Test` pinned the port *decomposition* and declared three
 * questions open. Each is closed here against the two authorities that decide
 * them — upstream `_upstream/packages/desktop/src/main/ssh/command.ts` and the
 * system `ssh` binary upstream hands the target to — and each answer is a claim
 * about which endpoint this shell would dial, so it is asserted as the whole
 * `(user, host, port)` triple: no case may pass on the host alone, because a
 * parser that gets the host right and the port wrong is precisely the v99 defect.
 *
 * The measurements these pins encode, taken on this host with `ssh -G` (config
 * dump, no dial) and `ssh -v … true` against loopback:
 *
 * ```
 * $ ssh -G -p 2222 sshtest@127.0.0.1:2200   ⇒  port 2222 / hostname 127.0.0.1:2200
 * $ ssh -G -p 2222 -p 2200 devbox           ⇒  port 2222          (first -p wins)
 * $ ssh -G user@[::1]:2222                  ⇒  hostname [::1]:2222 (unusable)
 * $ ssh   sshtest@[::1]   true              ⇒  Could not resolve hostname [::1]
 * $ ssh   sshtest@::1     true              ⇒  Connecting to ::1 port 22 … established
 * ```
 *
 * So the system binary has **no** `host:port` shorthand, and cannot read a
 * bracketed IPv6 address at all: on both of those the shell is a strict superset,
 * which is only sound because the port it dials is the one `-p` named.
 */
class SshTargetParseEdgeV100Test {

    /** Asserts the whole triple so no case can pass on host alone. */
    private fun assertTarget(command: String, user: String?, host: String, port: Int) {
        val parsed = parseSshTarget(command)
        assertEquals("command: $command", user, parsed?.user)
        assertEquals("command: $command", host, parsed?.host)
        assertEquals("command: $command", port, parsed?.port)
    }

    // ---- conflict priority ---------------------------------------------------

    @Test
    fun dashPOwnsThePortOnEveryCombinationWithTheSuffix() {
        // The rule, cell by cell: `-p` wins whether it is separated or attached,
        // whether the suffix agrees or contradicts, and whatever else is on the
        // line. `ssh -G` is the arbiter — upstream forwards both halves, so ssh's
        // own reading of this string is the only defensible one to copy.
        assertTarget("host:2200", null, "host", 2200) // suffix alone
        assertTarget("ssh -p 2222 host", null, "host", 2222) // option alone
        assertTarget("ssh -p 2222 host:2200", null, "host", 2222) // option contradicts suffix
        assertTarget("ssh -p2222 host:2200", null, "host", 2222) // attached spelling too
        assertTarget("ssh -p 2200 host:2222", null, "host", 2200) // …in either direction
        assertTarget("ssh -p 2222 host:2222", null, "host", 2222) // agreement is not a conflict
        assertTarget(
            "ssh -p 2222 -i /keys/k -l deploy @127.0.0.1:2200",
            "deploy",
            "127.0.0.1",
            2222,
        )
        // The inline `user@` in the target itself outranks `-l`, the way ssh reads a
        // destination; the option only fills in a target that names no user.
        assertTarget(
            "ssh -p 2222 -i /keys/k -l deploy sshtest@127.0.0.1:2200",
            "sshtest",
            "127.0.0.1",
            2222,
        )
        assertEquals("/keys/k", parseSshTarget("ssh -p 2222 -i /keys/k -l deploy host:2200")?.identityPath)
    }

    @Test
    fun theFirstDashPWinsWhenItIsRepeated() {
        // OpenSSH assigns `options.port` only while it is unset, so `ssh -G -p 2222
        // -p 2200 devbox` prints `port 2222`. Taking the last would make this
        // string dial 2200 on the device and 2222 in the operator's terminal — a
        // silent divergence between two readers of one config line.
        assertTarget("ssh -p 2222 -p 2200 devbox", null, "devbox", 2222)
        assertTarget("ssh -p2222 -p 2200 devbox", null, "devbox", 2222)
        assertTarget("ssh -p 2222 -p2200 devbox", null, "devbox", 2222)
        assertTarget("ssh -p 2200 -p 2222 devbox", null, "devbox", 2200)
        assertTarget("ssh -p 2222 -p 2200 devbox:9999", null, "devbox", 2222)
        // A repeated option is still *validated* even when it cannot win: upstream's
        // gate runs per token (`command.ts:101-102`), so a bad second `-p` rejects
        // the whole target instead of being quietly ignored.
        for (command in listOf(
            "ssh -p 2222 -p twentytwo devbox",
            "ssh -p 2222 -p 0 devbox",
            "ssh -p 2222 -p 70000 devbox",
            "ssh -p 2222 -p devbox",
        )) {
            assertNull("must be rejected, not silently ignored: $command", parseSshTarget(command))
        }
    }

    @Test
    fun optionsAfterTheHostAreNotOptions() {
        // Upstream's option loop is `while (tokens[0]?.startsWith("-"))`
        // (`command.ts:91`) — options are leading-only, and whatever follows the
        // host must be exactly one token. A trailing `-p` is therefore a remote
        // command, not a port override, and must not reach the port it asks for.
        assertNull(parseSshTarget("ssh host:2200 -p 2222"))
        assertNull(parseSshTarget("ssh devbox -p 2222"))
    }

    // ---- IPv6 ----------------------------------------------------------------

    @Test
    fun ipv6DecomposesWithAndWithoutBrackets() {
        // Bracketed, the port moves outside the brackets — the only place a colon
        // after `]` may mean a port. Stripping the brackets is what makes these
        // dialable at all: OpenSSH itself cannot resolve `[::1]`, and jsch has no
        // bracket syntax to keep.
        assertTarget("[::1]", null, "::1", 22)
        assertTarget("[::1]:2222", null, "::1", 2222)
        assertTarget("user@[::1]:2222", "user", "::1", 2222)
        assertTarget("ssh user@[2001:db8::1]", "user", "2001:db8::1", 22)
        assertTarget("ssh user@[2001:db8::1]:2222", "user", "2001:db8::1", 2222)
        // A bare literal is mostly colons; more than one is what separates an
        // address from a host:port pair, so it is never torn apart.
        assertTarget("::1", null, "::1", 22)
        assertTarget("ssh user@::1", "user", "::1", 22)
        assertTarget("2001:db8::1", null, "2001:db8::1", 22)
        // `%` (IPv6 zone id) is inside upstream's host class (`command.ts:107`),
        // so a scoped link-local address is a legal target and stays whole.
        assertTarget("fe80::1%eth0", null, "fe80::1%eth0", 22)
        assertTarget("[fe80::1%eth0]:2222", null, "fe80::1%eth0", 2222)
    }

    @Test
    fun ipv6ComposesWithBothPortSpellings() {
        // Conflict priority and IPv6 are independent axes, so the cross product
        // is pinned too: the same arbitration holds when the host is an address.
        assertTarget("ssh -p 2222 ::1", null, "::1", 2222)
        assertTarget("ssh -p2222 ::1", null, "::1", 2222)
        assertTarget("ssh -p 2222 user@[::1]:2200", "user", "::1", 2222)
        assertTarget("ssh -p2222 user@[::1]", "user", "::1", 2222)
        assertTarget("ssh -p 2222 [2001:db8::1]:2200", null, "2001:db8::1", 2222)
        // A bracketed literal whose port is unusable is a rejected target, not a
        // fallback to 22: `[::1]` and `[::1]:0` have one reading, and it is a typo.
        for (command in listOf(
            "[::1]:",
            "[::1]:0",
            "[::1]:65536",
            "[::1]:ssh",
            "[::1]x",
            "[::1",
            "[]",
            "user@[::1]:",
        )) {
            assertNull("must be rejected, not defaulted: $command", parseSshTarget(command))
        }
    }

    // ---- attached spelling ---------------------------------------------------

    @Test
    fun attachedOptionValuesAreReadOffTheToken() {
        // `command.ts:97-99` — the flag is the first two characters and the value
        // is the rest, so `-p 2222` and `-p2222` are one option in two spellings.
        assertTarget("ssh -p2222 devbox", null, "devbox", 2222)
        assertTarget("ssh -ldeploy host.example", "deploy", "host.example", 22)
        assertEquals("/keys/k", parseSshTarget("ssh -i/keys/k host.example:2222")?.identityPath)
        // An inline `user@` still overrides `-l`, the way ssh reads the
        // destination: the option is a fallback, the target is the statement.
        assertEquals("user", parseSshTarget("ssh -ldeploy user@host.example:2222")?.user)
        assertEquals("deploy", parseSshTarget("ssh -ldeploy @host.example:2222")?.user)
        // Upstream's value gate (`command.ts:100`) rejects a value that starts
        // with `-` in *both* spellings — an option's value is never another
        // option, and `-i-keys` is not a relative identity path upstream would
        // have run either.
        for (command in listOf(
            "ssh -i-keys host",
            "ssh -l-p2222 host",
            "ssh -l- host",
            "ssh -p-1 host",
            "ssh -p- host",
            "ssh -p-0 host",
        )) {
            assertNull("must be rejected like upstream: $command", parseSshTarget(command))
        }
    }

    // ---- the host token is upstream's charset --------------------------------

    @Test
    fun hostsOutsideUpstreamsCharsetAreRejectedNotDialed() {
        // `command.ts:107` gates the host on `/^[a-zA-Z0-9_@.:[\]%-]+$/`. Without
        // the same gate these parse into hostnames that can never resolve, and
        // the failure is then born inside jsch and reported as "check your
        // network" — the same misdiagnosis v99 closed for `host:`, still open for
        // every other unusable spelling. Rejecting lands them on the sentence
        // that names the field the operator has to fix.
        for (command in listOf(
            "host/x",
            "host;whoami",
            "host?x",
            "host|x",
            "http://host",
            "user@host/x",
            "user@host;rm -rf /",
            "*",
            "host!",
            "host#1",
        )) {
            assertNull("must be rejected by the target gate: $command", parseSshTarget(command))
        }
        // …and the class that upstream does allow must keep working, including
        // the two shapes the v99 round added.
        assertTarget("dev_box", null, "dev_box", 22)
        assertTarget("HOST.Example.COM", null, "HOST.Example.COM", 22)
        assertTarget("dev-box.example.com", null, "dev-box.example.com", 22)
        assertTarget("host.", null, "host.", 22)
        assertTarget("user@host:2222", "user", "host", 2222)
    }

    // ---- unknown options: rejected, not forwarded, not ignored ---------------

    @Test
    fun everyOptionUpstreamForwardsIsRejectedHereRatherThanIgnored() {
        // Upstream accepts these only because a shell implements them
        // (`command.ts:93-98`); jsch implements none, so forwarding is impossible
        // and ignoring would be a silent misdial — a dropped `-6` dials IPv4 for
        // an operator who asked for IPv6, a dropped `-J` dials a different
        // machine. Each one is enumerated here so the divergence is pinned rather
        // than accidental, in both spellings.
        for (command in listOf(
            "ssh -4 host",
            "ssh -6 host",
            "ssh -C host",
            "ssh -A host",
            "ssh -a host",
            "ssh -F /etc/ssh_config host",
            "ssh -F/etc/ssh_config host",
            "ssh -J jump@example.com host",
            "ssh -Jjump@example.com host",
            "ssh -o Port=2222 host",
            "ssh -oPort=2222 host",
            "ssh -o ProxyCommand=ssh jump -W %h:%p host",
            "ssh -6 -p 2222 user@[::1]:2200",
            "ssh -t host",
            "ssh -L 8080:localhost:80 host",
        )) {
            assertNull("must be rejected, never ignored: $command", parseSshTarget(command))
        }
    }

    @Test
    fun aRejectedEdgeTargetRidesTheExistingInputSentence() {
        // The diagnostics half: a rejected edge must not become a dial. The
        // sentence is the pre-existing `ssh_error_input` — this round adds no new
        // user-visible string in any of the three languages.
        assertEquals(R.string.ssh_error_input, sshErrorResId(SshErrorCode.INPUT))
        assertNull(parseSshTarget("ssh -p 2222 sshtest@host:/etc"))
        assertNull(parseSshTarget("ssh -p 2222 -p abc host"))
        assertNull(parseSshTarget("[::1]:ssh"))
    }
}
