package com.xiufeigo.opencodeshell.ui.servers

/**
 * The pure half of the SSH tunnel (`SshConnection.kt`, jsch) — JVM-testable
 * target parsing, the connection-stage reducer for upstream's lifecycle
 * (`connect → authenticating → trust prompt → connected / failed`, the
 * `SshStage` wire values of `types.ts:15-26` + `status.ts`), the failure →
 * `SshItem.error` mapping (`types.ts:39-49`), and the connection-panel stat
 * formats (`connection-panel.tsx`).
 */

/** Remote `opencode serve` port forwarded to `127.0.0.1:<local>` (see [SshConnection]). */
const val SSH_REMOTE_PORT_DEFAULT: Int = 4096

/** Loopback both ends of the `setPortForwardingL` bridge bind/dial. */
const val SSH_FORWARD_HOST: String = "127.0.0.1"

/** `ServerConfigStore` url once the forward is up (`session` dials `localhost:<port>`). */
fun sshLocalUrl(localPort: Int): String = "http://$SSH_FORWARD_HOST:$localPort"

/**
 * One dialable endpoint parsed out of [SshConnectionConfig.target].
 *
 * Upstream hands the whole `ssh …` command to the system binary
 * (`servers/ssh` runner) and rejects remote commands / unsupported options with
 * `error: "input"`; jsch has no shell, so [parseSshTarget] is that gate **and**
 * the option reader in one: it accepts the ssh-style forms upstream's
 * `name.test.ts` fixtures use and nothing else.
 *
 * **v99 (device round 6, P1) — `host` is dialable, so the port is not optional
 * baggage.** Upstream can forward `[user@]host:port` verbatim because the system
 * `ssh` binary is what reads it; it cannot, because OpenSSH has no `host:port`
 * shorthand (`ssh sshtest@127.0.0.1:2222` → *"Could not resolve hostname
 * 127.0.0.1:2222"*, verified). jsch takes the port as a separate argument, so a
 * swallowed `:port` does not degrade — it silently **misdials port 22 at a
 * hostname that cannot resolve**, which is exactly the device report's
 * `Connection failed` with zero sshd log lines. [SshTarget.port] is therefore
 * resolved from every form the target can express it in, and a colon the parser
 * cannot account for is a rejected target rather than a default.
 */
data class SshTarget(
    val user: String?,
    val host: String,
    val port: Int,
    val identityPath: String? = null,
)

/**
 * Parses `ssh [options] [[user@]host][:port]`; `null` ⇔ the runner would report
 * `SshItem.error == "input"` (remote command, unsupported option, unbalanced
 * quoting, unusable port).
 *
 * Supported: a leading `ssh` token, `-p <port>`, `-i <identity>`, `-l <user>`
 * (each in both the separated `-p 2222` and attached `-p2222` spelling, as
 * upstream reads the flag off the first two characters), `user@host` / `host`,
 * a trailing `:port` on either, and bracketed IPv6 (`ssh user@[2001:db8::1]`,
 * `ssh user@[2001:db8::1]:2222`) — the brackets are display-only and stripped
 * here ([SshTarget.host] is dialable).
 * **v100 (edge round) — what the target grammar is closed over.** The v99 round
 * added the port but left three questions open; each is now answered against
 * upstream `command.ts` plus the system `ssh` this target is written for, and each
 * answer is pinned by `SshTargetParseEdgeV100Test`:
 *
 * - **`-p` vs `host:port`.** `-p` owns the port, first occurrence winning
 *   (`ssh -G -p 2222 sshtest@127.0.0.1:2200` ⇒ `port 2222`; `ssh -G -p 2222 -p 2200
 *   devbox` ⇒ `port 2222`). See the arbitration note at the return below.
 * - **IPv6.** `[::1]`, `[::1]:2222` and bare literals all decompose, brackets
 *   stripped. This is a deliberate **superset** of the system binary, which cannot
 *   read a bracketed address at all — measured: `ssh sshtest@[::1]` →
 *   `Could not resolve hostname [::1]`, while `ssh sshtest@::1` → `Connecting to
 *   ::1 port 22 … established`. jsch has no bracket syntax, so keeping the
 *   brackets would strand every bracketed form; stripping them is the only reading
 *   that can dial.
 * - **Unknown options.** `-4 -6 -C -A -a -F -J -o` are **rejected**, not forwarded
 *   and not ignored — see the divergence note below.
 *
 * Everything after the host is a remote command and is rejected, matching
 * upstream's "Accepted SSH targets end with a hostname or user@hostname, never
 * a remote command" (`name.ts:3-5`).
 *
 * **Deliberate divergence from upstream's option set.** Upstream's `parseTarget`
 * also accepts `-F`/`-J`/`-o` and the bare flags `-4 -6 -C -A -a`
 * (`command.ts:91-103`), because it only has to hand them to a shell
 * (`tunnelArgs:141-153`). jsch has no shell and implements none of them (no
 * `ProxyJump`, no `ProxyCommand`, no config file, no address-family override), so
 * of the three available answers — forward like upstream, ignore with a hint, or
 * reject — **reject** is the one chosen: forwarding is impossible without a shell,
 * and ignoring is the same silent-misdial class as the `:port` bug, because a
 * dropped `-6` would dial IPv4 for a user who asked for IPv6 and a dropped `-J`
 * would dial a different machine than the one they named. Rejection costs the
 * operator those options and buys a target that either is dialled exactly as typed
 * or is not dialled at all, on the pre-existing `ssh_error_input` sentence.
 *
 * A target this cannot decompose is `null`, never a default: [SshConnection]
 * parks that on [SshErrorCode.INPUT] and the panel shows the existing
 * `oc_ssh_error_input` sentence ("Enter a host or SSH command. Remote commands
 * and unsupported SSH options aren't allowed."). A malformed `host:…` used to
 * be *accepted* — `sshtest@127.0.0.1:2222` parsed to host `"127.0.0.1:2222"` /
 * port 22 and died at dial time behind the generic "Check your network and SSH
 * configuration" sentence, pointing operators at the network instead of the
 * field they had to fix.
 */
fun parseSshTarget(command: String): SshTarget? {
    val tokens = tokenizeSshTarget(command) ?: return null
    if (tokens.isEmpty()) return null
    var index = if (tokens[0] == "ssh") 1 else 0
    var user: String? = null
    var optionPort: Int? = null
    var identityPath: String? = null
    var host: String? = null
    var hostPort: Int? = null
    while (index < tokens.size) {
        val token = tokens[index]
        if (host != null) return null // remote command (or trailing junk)
        when {
            // Upstream `command.ts:97-99` takes the flag from the first two
            // characters, so `-p 2222` and `-p2222` are one option, not two.
            token.startsWith("-") -> {
                val flag = token.take(2)
                if (flag !in SSH_TARGET_OPTIONS) return null // -J, -o, -F, -t, …
                val attached = token.length > 2
                val value = if (attached) {
                    token.substring(2)
                } else {
                    if (index + 1 >= tokens.size) return null
                    tokens[index + 1]
                }
                // `command.ts:100` — `if (!value || value.startsWith("-")) throw` — is
                // applied by upstream to the attached spelling too, so `-i-keys` and
                // `-l-p2222` are rejected here exactly as they are upstream. An
                // option's value is never another option.
                if (value.isBlank() || value.startsWith("-")) return null
                when (flag) {
                    "-p" -> {
                        val parsed = value.toIntOrNull() ?: return null
                        if (parsed !in 1..65535) return null
                        // **First `-p` wins**, measured against the system binary this
                        // target is written for (`ssh -G -p 2222 -p 2200 devbox` →
                        // `port 2222`; OpenSSH's getopt loop only assigns
                        // `options.port` while it is still unset). Upstream forwards
                        // both, so its authority here *is* ssh's rule; taking the last
                        // one would make the same string dial a different port on the
                        // device than it does in the operator's terminal. A later `-p`
                        // is still validated — upstream's gate runs per token.
                        if (optionPort == null) optionPort = parsed
                    }

                    "-i" -> identityPath = value
                    "-l" -> user = value
                }
                index += if (attached) 1 else 2
            }

            else -> {
                val parsed = parseSshHostPart(token, user) ?: return null
                user = parsed.user
                host = parsed.host
                hostPort = parsed.port
                index += 1
            }
        }
    }
    val resolvedHost = host ?: return null
    // **Port arbitration, in one line — the rule this shell commits to.**
    //
    // 1. `-p` owns the port. It is the only spelling the system `ssh` reads as a
    //    port, and upstream forwards it verbatim (`command.ts:104` → `tunnelArgs:151`),
    //    so its authority is ssh's: `ssh -G -p 2222 sshtest@127.0.0.1:2200` prints
    //    `port 2222` with `hostname 127.0.0.1:2200` — the suffix is not a port to
    //    ssh, it is part of a hostname that cannot resolve. The two candidates
    //    that were available here both dials: last-writer-wins would discard the
    //    port the option named; suffix-wins would discard the port the *option*
    //    named. Keeping `-p` is also the only reading that agrees with the
    //    terminal the operator typed the string in.
    // 2. A repeated `-p` is decided above (first wins, ssh's own rule).
    // 3. A `host:port` suffix is honoured only when no `-p` named one.
    // 4. Otherwise the named default, 22.
    //
    // What this costs against upstream: upstream cannot reach a `host:port` target
    // at all (measured: `Could not resolve hostname 127.0.0.1:2222`), while here the
    // suffix is decomposed and the connection succeeds. On the conflict line the
    // port is identical to what upstream would have dialled; the only difference is
    // that the junk hostname is gone instead of fatal.
    return SshTarget(
        user = user,
        host = resolvedHost,
        port = optionPort ?: hostPort ?: SSH_DEFAULT_PORT,
        identityPath = identityPath,
    )
}

/** Shell-ish split honouring `'` / `"`; `null` on unbalanced quoting. */
internal fun tokenizeSshTarget(command: String): List<String>? {
    val tokens = mutableListOf<String>()
    val current = StringBuilder()
    var quote: Char? = null
    for (char in command) {
        when {
            quote != null -> {
                if (char == quote) quote = null else current.append(char)
            }

            char == '\'' || char == '"' -> quote = char
            char.isWhitespace() -> if (current.isNotEmpty()) {
                tokens.add(current.toString())
                current.setLength(0)
            }

            else -> current.append(char)
        }
    }
    if (quote != null) return null
    if (current.isNotEmpty()) tokens.add(current.toString())
    return tokens
}

/** `[user@]host` with no port anywhere: the IANA SSH port, as jsch's default. */
const val SSH_DEFAULT_PORT: Int = 22

/** The only option flags jsch can honour (upstream's wider set, see [parseSshTarget]). */
private val SSH_TARGET_OPTIONS = setOf("-p", "-i", "-l")

/** `[user@]host[:port]` torn apart, [port] `null` when the target named none. */
private data class SshHostParts(val user: String?, val host: String, val port: Int?)

/**
 * The characters upstream's host token may contain, transcribed from the class in
 * `/^[a-zA-Z0-9_@.:[\]%-]+$/` (`command.ts:107`) so the two cannot drift.
 */
private const val SSH_HOST_TOKEN_CHARS: String =
    "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_@.:[]%-"

private fun isUpstreamHostToken(token: String): Boolean =
    token.isNotEmpty() && token.all { it in SSH_HOST_TOKEN_CHARS }

/** `[user@]host[:port]` torn apart, IPv6 brackets stripped → `(user, host, port)`. */
private fun parseSshHostPart(token: String, fallbackUser: String?): SshHostParts? {
    // Upstream `command.ts:107` — `/^[a-zA-Z0-9_@.:[\]%-]+$/`, spelled out in
    // [SSH_HOST_TOKEN_CHARS] so the set is the one upstream chose, not a regex
    // re-invented here. Upstream applies it to the whole token, so `host/x` and
    // `host;whoami` are `SshFailure("input")` there; without the same gate they
    // parsed here into a hostname that can never resolve, and the failure was
    // then born inside jsch and reported as "check your network" — the P2
    // misdiagnosis the v99 round closed for `host:`, left open for every other
    // unusable spelling. `%` stays legal so an IPv6 zone id (`fe80::1%eth0`)
    // passes, exactly as upstream's class does.
    if (!isUpstreamHostToken(token)) return null
    val at = token.lastIndexOf('@')
    val rawUser = if (at >= 0) token.substring(0, at) else null
    // Upstream `command.ts:109` — `user:password@host` is not a target shape, and
    // a colon here is a credential the shell must not read as a port.
    if (rawUser != null && rawUser.contains(':')) return null
    val user = rawUser?.ifEmpty { fallbackUser } ?: fallbackUser
    val hostPart = if (at >= 0) token.substring(at + 1) else token

    val (hostLiteral, portLiteral) = splitSshHostPort(hostPart) ?: return null
    val host = when {
        hostLiteral.startsWith("[") -> hostLiteral.substring(1, hostLiteral.length - 1)
        else -> hostLiteral
    }
    if (host.isBlank()) return null
    if (portLiteral == null) return SshHostParts(user, host, null)
    // A colon the parser cannot read as a port is a malformed target, not a
    // reason to fall back to 22: `host:`, `host:ssh`, `host:0` and `host:99999`
    // all have one reading, and it is "the user mistyped the port".
    val port = portLiteral.toIntOrNull() ?: return null
    if (port !in 1..65535) return null
    return SshHostParts(user, host, port)
}

/**
 * Splits a trailing `:port` off [hostPart] → `(host literal, port digits)`, the
 * port `null` when there is none. `null` ⇔ the colon cannot be a port, so the
 * whole token is malformed.
 *
 * The only subtle case is IPv6, whose colons are part of the address and not
 * separators: a bracketed literal ends at `]`, and a *bare* literal has more
 * than one colon — which is what tells `2001:db8::1` (a host) apart from
 * `127.0.0.1:2222` (a host and a port).
 */
private fun splitSshHostPort(hostPart: String): Pair<String, String?>? {
    if (hostPart.startsWith("[")) {
        val close = hostPart.indexOf(']')
        if (close < 0) return null // unbalanced bracket
        val literal = hostPart.substring(0, close + 1)
        val rest = hostPart.substring(close + 1)
        if (rest.isEmpty()) return literal to null
        if (!rest.startsWith(":")) return null
        return literal to rest.substring(1)
    }
    val colon = hostPart.lastIndexOf(':')
    if (colon < 0) return hostPart to null
    // More than one colon and no brackets ⇒ a bare IPv6 literal, never a port.
    if (hostPart.indexOf(':') != colon) return hostPart to null
    return hostPart.substring(0, colon) to hostPart.substring(colon + 1)
}

/**
 * **v97 (F14/F12b device findings) — the per-connection attempt guard.**
 *
 * Three defects on the v97 device round all trace to one missing fact: *which
 * dial attempt* a jsch callback belongs to. `SshConnection` ran every
 * `connect()` on a fresh coroutine with no identity, so:
 *
 * - **D1 / F12b (cancel → downgrade → infinite re-pop).** `cancelPrompt()`
 *   answered the challenge with `accepted = false`, which only tells jsch "this
 *   method failed" — it then **tries the next one**. The next method raised a
 *   *new* [SshPrompt] with a *new* id, and the offer rule is keyed by prompt id
 *   ([sshPromptOffer]), so a brand-new card was offered. Repeat → the card
 *   re-popped on every sample and had silently walked publickey →
 *   keyboard-interactive (hence the `Password:` card and the "no auth record on
 *   sshd" reading: the downgrade happened without any challenge being answered).
 * - **D2 (a settled success reported as a failure).** A second Reconnect
 *   launched a second `connect()` while the first was still parked in
 *   `answers.poll(600 s)`. The winner published `Ready`; the loser, unblocked
 *   later, published `Fail` on the same state object — so the panel showed
 *   `Authentication required` / `Could not establish…` over a connection sshd
 *   had already accepted, and only recovered when some later emission
 *   happened to re-park the row.
 * - **D3 (delete on a row stuck at `Authentication required`).**
 *   `SshTunnelHost.forget` tore the session down but never invalidated the
 *   parked dial, so the zombie coroutine kept publishing and re-offering
 *   prompts for an entry the user had just deleted.
 *
 * The guard is the smallest thing that expresses "a superseded attempt is
 * silent": a monotonically increasing generation, stamped by [begin], plus the
 * per-attempt cancel latch. Everything about *what* a status becomes stays in
 * [reduceSshConnection] — this only decides **whether an attempt may speak**.
 * Kept pure and separate so the JVM suite can pin the rules without jsch.
 */
class SshAttemptGuard {

    private var generation: Int = 0

    /** Generations the user cancelled; a cancelled dial is muted until the next [begin]. */
    private val cancelled = mutableSetOf<Int>()

    /** The attempt a fresh `connect()` is starting; supersedes every older one. */
    fun begin(): Int {
        generation += 1
        // Only the current attempt's cancel is retained: a stale cancel from an
        // abandoned dial must never mute the dial the user just started.
        cancelled.retainAll(0 until generation)
        return generation
    }

    /** The live attempt — what a callback with no stamp of its own belongs to. */
    fun current(): Int = generation

    /**
     * v97 D3 — the entry is being deleted. Bumps past the parked dial so nothing
     * it raises (state or prompt) can be published again.
     */
    fun invalidate() {
        generation += 1
        cancelled.retainAll(0 until generation)
    }

    /**
     * The user cancelled the challenge of [attempt]: the dial is abandoned, not
     * merely re-routed. Only the live attempt can be cancelled.
     */
    fun cancel(attempt: Int) {
        if (attempt == generation) cancelled += attempt
    }

    /** Whether [attempt] is the live one and the user has not cancelled it. */
    fun speaks(attempt: Int): Boolean = attempt == generation && attempt !in cancelled
}

/**
 * [SshAttemptGuard.speaks] as a pure rule (what the JVM suite pins): an attempt
 * may publish only while it is the current one and uncancelled.
 */
fun sshAttemptSpeaks(attempt: Int, currentAttempt: Int, cancelled: Boolean): Boolean =
    attempt == currentAttempt && !cancelled

/**
 * v97 D2 — whether a **terminal** event ([SshConnectEvent.Ready] /
 * [SshConnectEvent.Fail] / [SshConnectEvent.Disconnect]) may replace
 * [current].
 *
 * [SshAttemptGuard] already drops a superseded attempt, but a *same-generation*
 * late failure after a settled [SshStage.READY] is the D2 shape the device
 * showed: sshd logged `Accepted keyboard-interactive/pam` while the row read
 * `Authentication required` + `Could not establish…`. A ready tunnel is not
 * re-decided by a stray failure — only the explicit [SshConnectEvent.Disconnect]
 * and a fresh [SshConnectEvent.Start] move it on. A failure that happens
 * *before* [SshStage.READY] (the ordinary case) is untouched: `current.stage` is
 * not [SshStage.READY], so the event is published as always and no real failure
 * is swallowed.
 */
fun sshPublishesTerminal(current: SshConnectionStatus, event: SshConnectEvent): Boolean =
    current.stage != SshStage.READY || event !is SshConnectEvent.Fail

/**
 * Settled outcome of a failed attempt: the [SshStage] to park on plus the
 * `SshItem.error` literal to render (`connection-panel.tsx:29`).
 */
data class SshFailure(
    val stage: SshStage,
    val error: SshErrorCode?,
    val detail: String = "",
)

/**
 * **v100 (P3, device) — the jsch method list the form's `auth` selection is
 * allowed to run.**
 *
 * jsch's stock `PreferredAuthentications` is
 * `gssapi-with-mic,publickey,keyboard-interactive,password`: a *walk*, not a
 * choice. Nothing in this app used to narrow it, so a `Private key` entry whose
 * publickey attempt produced no usable signature did not fail — jsch simply
 * continued down the list to `keyboard-interactive`, and [SshConnection]'s
 * `promptKeyboardInteractive` drew a **`Password:` card** on an entry whose
 * whole point was that it had no account password. The operator typed one, the
 * server logged `Accepted keyboard-interactive/pam`, the row read `Connected`,
 * and the key was never used once (`REPORT_SSH_DIAG.md` §4.4). That silent
 * fallback *is* the P3 degradation: not "publickey failed" but "publickey failed
 * and the app pretended a different method had been chosen".
 *
 * So the method list is now derived from [auth] and is **one method wide**:
 *
 *  - [SshAuthMethod.KEY] ⇒ `publickey` only. If the identity cannot sign, the
 *    dial must fail (and be reported as such) rather than asking for a secret
 *    the user never chose to supply. Narrowing the list cannot weaken anything:
 *    it only removes a method the user did not select.
 *  - [SshAuthMethod.PASSWORD] ⇒ `password,keyboard-interactive`, the two
 *    methods an account secret can legitimately answer. Publickey stays out, so
 *    a stale identity can never quietly rescue — or quietly replace — a password
 *    entry.
 */
fun sshPreferredAuthentications(auth: SshAuthMethod): String = when (auth) {
    SshAuthMethod.KEY -> "publickey"
    SshAuthMethod.PASSWORD -> "password,keyboard-interactive"
}

/**
 * The credential plan for one [SshConnectionConfig]: which jsch inputs the
 * chosen [SshAuthMethod] is allowed to carry, with the two secret fields kept
 * strictly apart.
 *
 * The separation is the point. [SshConnectionConfig] has *two* independent
 * secrets — [SshConnectionConfig.password] (the account password) and
 * [SshConnectionConfig.passphrase] (the private key's own passphrase) — and
 * mixing them is invisible at the call site but fatal at the protocol: handing
 * the account password to `addIdentity` as a key passphrase makes jsch fail to
 * decrypt, and handing the key passphrase to `setPassword` makes the server see
 * a wrong account secret. Both degrade into the same silent `Password:` card, so
 * this table is the only place that decides which secret goes where.
 *
 * A blank secret is `null`, not `""`: jsch treats an empty passphrase as "try to
 * decrypt with the empty string" and fails the same way, while `null` is the
 * honest "this entry carries no such secret" that lets the interactive card ask
 * for it.
 */
data class SshAuthPlan(
    val method: SshAuthMethod,
    /** `addIdentity` path — present iff [method] is [SshAuthMethod.KEY]. */
    val identityPath: String?,
    /** Key passphrase — never the account password. */
    val passphrase: String?,
    /** Account password — never the key passphrase. */
    val password: String?,
    /** [sshPreferredAuthentications] for this method. */
    val preferredAuthentications: String,
)

/** Blank-or-absent ⇒ `null`; [SshConnection] must never pass an empty secret on. */
private fun String?.asSecret(): String? = this?.takeIf { it.isNotBlank() }

fun sshAuthPlan(config: SshConnectionConfig): SshAuthPlan = when (config.auth) {
    SshAuthMethod.KEY -> SshAuthPlan(
        method = SshAuthMethod.KEY,
        identityPath = config.privateKeyPath?.trim()?.takeIf { it.isNotEmpty() },
        passphrase = config.passphrase.asSecret(),
        // An account password is never part of a key plan. Carrying it here is
        // how "the key did not work" used to turn into a `Password:` card.
        password = null,
        preferredAuthentications = sshPreferredAuthentications(SshAuthMethod.KEY),
    )

    SshAuthMethod.PASSWORD -> SshAuthPlan(
        method = SshAuthMethod.PASSWORD,
        // No identity for a password entry: a stale key must not be offered.
        identityPath = null,
        passphrase = null,
        password = config.password.asSecret(),
        preferredAuthentications = sshPreferredAuthentications(SshAuthMethod.PASSWORD),
    )
}

/**
 * Maps a JSch/IO failure message onto [SshFailure]. Pure so every branch is
 * JVM-testable without dialing a host; [SshConnection] feeds it the
 * `JSchException`/`IOException` message.
 *
 * **Android mapping notes** (upstream runs the system `ssh` and classifies in
 * its runner): host-key rejections are jsch's `reject HostKey` /
 * `HostKey has been changed` (→ `error: "host-key"`); an auth wall parks on
 * `authentication` — upstream's "Authentication required" stage that
 * `authenticate.tsx` re-opens the dialog for — with the `connection` sentence
 * as detail.
 *
 * **Deliberate deviation — `key-file` (F14a, v96 device finding).** An
 * unreadable identity file used to be folded into `error: "input"`, so the panel
 * showed the *target* sentence ("Enter a host or SSH connection command. Remote
 * commands and unsupported SSH options aren't allowed.") for what was really a
 * local file problem — on the test device `addIdentity()` raised
 * `java.io.FileNotFoundException: /sdcard/v95/id_ed25519 (Permission denied)`
 * (app uid change ⇒ no read bit) with zero sockets opened, and two rounds of
 * testers read the target copy as "the target was lost". It now gets its own
 * [SshErrorCode.KEY_FILE] and sentence: *check the path and read permission*.
 * Upstream has no such literal: it hands a bad `-i` identity to the system `ssh`
 * and classifies from the whole stderr dump, whereas jsch surfaces only the
 * exception message here — so the class is Android-only. Note that
 * `permission denied` **alone** stays [SshErrorCode.CONNECTION] on the
 * [SshStage.AUTHENTICATION] stage: that is the server refusing us, and it must
 * not be folded into the key-file branch.
 */
fun sshFailure(message: String): SshFailure {
    val lower = message.lowercase()
    return when {
        "hostkey" in lower || "host key" in lower ->
            SshFailure(SshStage.FAILED, SshErrorCode.HOST_KEY, message)

        "privatekey" in lower || "identity" in lower || "filenotfound" in lower ||
            "no such file" in lower ->
            SshFailure(SshStage.FAILED, SshErrorCode.KEY_FILE, message)

        "passphrase" in lower && "fail" in lower ->
            SshFailure(SshStage.FAILED, SshErrorCode.INPUT, message)

        "auth fail" in lower || "userauth" in lower || "permission denied" in lower ||
            "auth cancel" in lower || "authentication" in lower ->
            SshFailure(SshStage.AUTHENTICATION, SshErrorCode.CONNECTION, message)

        else -> SshFailure(SshStage.FAILED, SshErrorCode.CONNECTION, message)
    }
}

/**
 * Everything `connection-panel.tsx` (+ the Android latency/handshake stats)
 * renders. [prompt] is the in-flight `SshPrompt` (`types.ts:28-32`) and
 * [hostKey] carries the fingerprints when that prompt is a trust question.
 */
data class SshConnectionStatus(
    val stage: SshStage = SshStage.DISCONNECTED,
    val error: SshErrorCode? = null,
    val prompt: SshPrompt? = null,
    val hostKey: SshHostKeyInfo? = null,
    val localUrl: String? = null,
    val latencyMs: Long? = null,
    val lastHandshakeAt: Long? = null,
    val detail: String = "",
)

/** Lifecycle inputs [reduceSshConnection] folds into [SshConnectionStatus]. */
sealed interface SshConnectEvent {
    /** Dial begun (submit / reconnect). */
    data object Start : SshConnectEvent

    /** A challenge is on screen (`ssh.trust` / `ssh.continue`); stage stays in-flight. */
    data class Prompted(val prompt: SshPrompt, val hostKey: SshHostKeyInfo? = null) : SshConnectEvent

    /** The user answered [id]; the dial thread resumes. */
    data class PromptResolved(val id: String) : SshConnectEvent

    /** Forward is up — `setPortForwardingL` returned [localUrl]'s port. */
    data class Ready(val localUrl: String, val latencyMs: Long, val at: Long) : SshConnectEvent

    /** Settled on [SshFailure.stage] with its `error` literal (`authentication` included). */
    data class Fail(val failure: SshFailure) : SshConnectEvent

    /** User disconnect or line lost — "the remote server is left running". */
    data object Disconnect : SshConnectEvent
}

/**
 * The connect → authenticating → trust prompt → connected / failed machine.
 * Pure: [SshConnection] only feeds it events. Stats survive [SshConnectEvent.Disconnect]
 * so the panel can still show the last handshake.
 */
fun reduceSshConnection(status: SshConnectionStatus, event: SshConnectEvent): SshConnectionStatus = when (event) {
    SshConnectEvent.Start -> status.copy(
        stage = SshStage.CONNECTING,
        error = null,
        prompt = null,
        hostKey = null,
        localUrl = null,
        detail = "",
    )

    is SshConnectEvent.Prompted -> status.copy(
        stage = SshStage.CONNECTING,
        error = null,
        prompt = event.prompt,
        hostKey = event.hostKey,
    )

    is SshConnectEvent.PromptResolved ->
        if (status.prompt?.id == event.id) status.copy(prompt = null, hostKey = null) else status

    is SshConnectEvent.Ready -> status.copy(
        stage = SshStage.READY,
        error = null,
        prompt = null,
        hostKey = null,
        localUrl = event.localUrl,
        latencyMs = event.latencyMs,
        lastHandshakeAt = event.at,
        detail = "",
    )

    is SshConnectEvent.Fail -> status.copy(
        stage = event.failure.stage,
        error = event.failure.error,
        prompt = null,
        hostKey = null,
        localUrl = null,
        detail = event.failure.detail,
    )

    SshConnectEvent.Disconnect -> status.copy(
        stage = SshStage.DISCONNECTED,
        error = null,
        prompt = null,
        hostKey = null,
        localUrl = null,
        detail = "",
    )
}

/**
 * **v99 (device round 6, §3) — what withdrawing a challenge owes the UI.**
 *
 * A challenge the user has answered is gone from the screen by two rules that
 * used to fight each other on the **host-key** leg:
 *
 * - [reduceSshConnection]'s [SshConnectEvent.PromptResolved] clears
 *   `prompt` + `hostKey` and touches nothing else — that is the card's own exit
 *   (`SshDialog` draws [SshAuthenticate] while `status.prompt != null`, so
 *   `prompt == null` *is* "the card is gone");
 * - the v97 [SshAttemptGuard] mutes a **cancelled** attempt, and a muted
 *   attempt publishes **nothing** — not `PromptResolved`, not the `Fail` its
 *   own teardown raises.
 *
 * The order in `SshConnection.cancelPrompt` was *mute first, then answer*, so
 * the very event that withdraws the card was dropped on the floor and the card
 * stayed up with the row parked at `CONNECTING` forever. Both buttons then read
 * as dead: `Cancel` had already spent the attempt, and a later
 * `Trust and connect` offered its answer into a queue whose consumer had walked
 * away. This is the whole of the "Cancel and Trust are both no-ops, the panel is
 * stuck" device report.
 *
 * So the cancel leg resolves **itself**, as a pure fold the JVM suite can pin
 * without jsch: withdraw the challenge the user just answered, then park the
 * attempt on the resting [SshStage.DISCONNECTED] — upstream's cancel on a row
 * that is still saved ("the remote server is left running", the state the same
 * device round read after a restart). The F13b pins are untouched: it is not a
 * close of the dialog, the reducer still has no `PromptResolved` → settle edge,
 * and [SshStage.READY] is unreachable from here — a refused or untrusted host
 * key never fabricates a tunnel.
 */
fun sshCancelSettles(status: SshConnectionStatus, promptId: String): SshConnectionStatus =
    reduceSshConnection(reduceSshConnection(status, SshConnectEvent.PromptResolved(promptId)), SshConnectEvent.Disconnect)

/**
 * v99 — whether the challenge [promptId] is the one on screen. `respondTrust`
 * / `respondPrompt` answer **that** challenge and no other, so a stale card
 * cannot push an answer onto a dial that has already moved on.
 */
fun sshPromptIsCurrent(status: SshConnectionStatus, promptId: String): Boolean =
    status.prompt?.id == promptId

/**
 * Latency / handshake-duration stat: `42 ms` below one second, `1.4 s` above.
 * `—` when there has been no handshake yet (panel renders it muted).
 */
fun formatSshLatency(millis: Long?): String = when {
    millis == null -> "—"
    millis < 0 -> "—"
    millis < 1_000 -> "$millis ms"
    else -> {
        val tenths = millis / 100
        "${tenths / 10}.${tenths % 10} s"
    }
}

/**
 * "Last handshake" age — bare `12s` / `3m` / `2h` / `4d` unit suffixes need no
 * translation, so this stays locale-free.
 */
fun formatSshHandshakeAge(ageMillis: Long): String = when {
    ageMillis < 0 -> "—"
    ageMillis < 60_000 -> "${ageMillis / 1_000}s"
    ageMillis < 3_600_000 -> "${ageMillis / 60_000}m"
    ageMillis < 86_400_000 -> "${ageMillis / 3_600_000}h"
    else -> "${ageMillis / 86_400_000}d"
}
