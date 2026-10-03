package com.xiufeigo.opencodeshell.ui.servers

import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.UIKeyboardInteractive
import com.jcraft.jsch.UserInfo
import java.io.File
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/**
 * The jsch half of upstream's `servers/ssh` runner: one `Session` plus one
 * **local port forward** — `session.setPortForwardingL(127.0.0.1, <local>,
 * 127.0.0.1, [remotePort])` maps the remote `opencode serve` onto loopback so
 * `ServerConfigStore` can hold `http://127.0.0.1:<local>` ([sshLocalUrl]) and
 * `session` keeps dialing `localhost:<port>` exactly like upstream.
 *
 * Lifecycle mirrors `connection-panel.tsx` / `controller.ts`: [connect] walks
 * connect → trust prompt → connected / failed through [reduceSshConnection],
 * [reconnect] is the panel's button, [disconnect] releases the forward and the
 * session (also on coroutine cancellation mid-dial — `connect` tears its
 * partially built session down before rethrowing).
 *
 * Auth is the form's two methods (`authenticate.tsx`'s passwords /
 * passphrases, collected up front in [SshDialog]): password or private-key
 * path + passphrase. Host keys are **never** auto-trusted: jsch runs with
 * `StrictHostKeyChecking=ask` against [SshKnownHosts], so an unknown or
 * changed key blocks the dial thread on [SshAuthenticate]'s prompt until
 * `respondTrust` answers; `ssh.trust` is what writes `known_hosts`.
 */
class SshConnection(
    val config: SshConnectionConfig,
    private val knownHostsFile: File? = null,
    /** Remote `opencode serve` port to forward; upstream installs and resolves it per host. */
    val remotePort: Int = SSH_REMOTE_PORT_DEFAULT,
    /** Preferred local bind port; `0` lets jsch pick a free one (returned by `setPortForwardingL`). */
    private val localPort: Int = 0,
    private val connectTimeoutMs: Int = 30_000,
) {
    private val _state = MutableStateFlow(SshConnectionStatus())
    val state: StateFlow<SshConnectionStatus> = _state.asStateFlow()

    /** `http://127.0.0.1:<port>` while connected — the `ServerConfigStore` url. */
    val localUrl: String? get() = _state.value.localUrl

    private val knownHosts = SshKnownHosts(
        knownHostsFile?.takeIf { it.isFile }?.readLines().orEmpty(),
    )

    private val answers = ArrayBlockingQueue<PromptAnswer>(1)

    /**
     * v97 — which dial attempt a jsch callback belongs to. Without it a parked
     * `answers.poll(600 s)` coroutine from an earlier dial keeps speaking: a
     * cancelled challenge silently downgraded publickey → keyboard-interactive
     * and re-popped its card forever (D1/F12b), a late `Fail` overwrote a
     * settled `Ready` (D2), and a deleted entry's zombie dial kept re-offering
     * prompts (D3). See [SshAttemptGuard].
     */
    private val guard = SshAttemptGuard()

    @Volatile
    private var session: Session? = null

    /**
     * v100 (P3) — the credential table of the dial being started, read by
     * [SshUserInfo]'s method guards. Assigned in [connect] immediately before the
     * UserInfo is bound, so it always describes the attempt in flight; a
     * superseded attempt is already muted by [guard] and is refused earlier.
     */
    @Volatile
    private var activePlan: SshAuthPlan = sshAuthPlan(config)

    private data class PromptAnswer(val promptId: String, val accepted: Boolean, val value: String?)

    /**
     * Dials the tunnel. Suspends until the forward is up ([SshStage.READY]) or
     * the attempt settled; never throws except for cancellation, which tears
     * the half-built session down first ("coroutine cancel releases the port
     * forward and the session").
     */
    suspend fun connect(): SshConnectionStatus = withContext(Dispatchers.IO) {
        if (_state.value.stage == SshStage.READY && session?.isConnected == true) {
            return@withContext _state.value
        }
        // v97: this dial supersedes every earlier one. Stamped *before* the
        // first emit so an abandoned attempt is already muted when it wakes.
        val attempt = guard.begin()
        emit(attempt, SshConnectEvent.Start)
        var building: Session? = null
        try {
            val target = parseSshTarget(config.target)
                ?: return@withContext fail(attempt, SshFailure(SshStage.FAILED, SshErrorCode.INPUT, config.target))
            routeEd25519ThroughBouncyCastle()
            val jsch = JSch()
            val repository = KnownHostsRepository()
            jsch.setHostKeyRepository(repository)
            val plan = sshAuthPlan(config)
            // v100 (P3): the method guards in [SshUserInfo] read this. It is set
            // before the UserInfo is handed to the session and only for the
            // attempt being started, so a superseded dial's callbacks read the
            // same table its own `attempt` stamp already muted.
            activePlan = plan
            when (plan.method) {
                SshAuthMethod.KEY -> {
                    val path = plan.identityPath.orEmpty()
                    if (path.isEmpty()) {
                        return@withContext fail(attempt, SshFailure(SshStage.FAILED, SshErrorCode.INPUT, "key path"))
                    }
                    // v100 (P3): the passphrase is handed to `addIdentity` so
                    // the key is decrypted **now**; a key that stays encrypted
                    // (no passphrase, or a wrong one) is a key that cannot sign,
                    // and jsch's answer to that used to be "ask for a password
                    // instead". With `PreferredAuthentications` pinned to
                    // `publickey` below, it now fails here instead.
                    jsch.addIdentity(File(path).expandUser().absolutePath, plan.passphrase)
                }

                SshAuthMethod.PASSWORD -> {}
            }
            val jschSession = jsch.getSession(
                target.user ?: defaultSshUser(),
                target.host,
                target.port,
            )
            building = jschSession
            session = jschSession
            jschSession.setConfig("StrictHostKeyChecking", "ask")
            // v100 (P3): one method wide, derived from the form's selection.
            // Without this jsch walks `publickey → keyboard-interactive →
            // password`, which is how a `Private key` entry ended up asking for
            // an account password and connecting with it.
            jschSession.setConfig("PreferredAuthentications", plan.preferredAuthentications)
            jschSession.userInfo = SshUserInfo(repository, attempt)
            if (plan.method == SshAuthMethod.PASSWORD) jschSession.setPassword(plan.password)
            val startedAt = System.currentTimeMillis()
            jschSession.connect(connectTimeoutMs)
            val handshakeAt = System.currentTimeMillis()
            val assignedPort = jschSession.setPortForwardingL(
                SSH_FORWARD_HOST,
                localPort,
                SSH_FORWARD_HOST,
                remotePort,
            )
            building = null
            emit(
                attempt,
                SshConnectEvent.Ready(
                    localUrl = sshLocalUrl(assignedPort),
                    latencyMs = handshakeAt - startedAt,
                    at = handshakeAt,
                ),
            )
            _state.value
        } catch (cancellation: CancellationException) {
            teardown(building ?: session)
            emit(attempt, SshConnectEvent.Disconnect)
            throw cancellation
        } catch (failure: Exception) {
            teardown(building ?: session)
            fail(attempt, sshFailure(failure.message ?: failure.toString()))
        }
    }

    /** Panel `Disconnect`: drops the forward + session; stats stay for the panel. */
    fun disconnect() {
        // v97 D3: an explicit disconnect supersedes the parked dial too, so it
        // cannot re-publish or re-offer after the row moved on.
        val attempt = guard.begin()
        teardown(session)
        answers.clear()
        emit(attempt, SshConnectEvent.Disconnect)
    }

    /**
     * v97 D3 — the entry is being deleted: nothing this dial raises (state or
     * prompt) may be published again. Separate from [disconnect] because a
     * delete drops the row immediately and must not be undone by a late
     * emission from the attempt it was parked in.
     */
    fun abort() {
        guard.invalidate()
        answers.clear()
        _state.update { it.copy(prompt = null, hostKey = null) }
    }

    /** Panel `Reconnect` (`connection-panel.tsx:41`) — a fresh dial over the same config. */
    suspend fun reconnect(): SshConnectionStatus {
        disconnect()
        return connect()
    }

    /** Answers the in-flight host-key trust prompt (`ssh.trust`). */
    fun respondTrust(accepted: Boolean) {
        val status = _state.value
        val prompt = status.prompt ?: return
        if (!prompt.confirm) return
        // v99: only the challenge that is **on screen** is answered. A card left
        // over from an attempt that already took its answer used to push a trust
        // into the one-slot queue with no consumer left — a tap that could not be
        // observed anywhere (REPORT_COVERAGE6.md §2.2). Refusing it here is also
        // the security half: a trust for a challenge the dial is not parked on is
        // not a trust, and it must not reach `known_hosts`.
        if (!sshPromptIsCurrent(status, prompt.id)) return
        answers.offer(PromptAnswer(prompt.id, accepted, null))
    }

    /** Answers an interactive challenge (`ssh.continue`) with the user's secret. */
    fun respondPrompt(id: String, value: String) {
        val status = _state.value
        val prompt = status.prompt ?: return
        if (prompt.confirm || prompt.id != id) return
        if (!sshPromptIsCurrent(status, prompt.id)) return
        answers.offer(PromptAnswer(id, true, value))
    }

    /**
     * Backs out of any in-flight prompt (`dialog.close` mid-challenge).
     *
     * **v97 (F12b/D1).** This used to only answer the challenge with
     * `accepted = false`, which jsch reads as "this *method* failed" — so it
     * walked on to the next one. For a private-key entry that meant publickey →
     * keyboard-interactive: a *new* challenge, hence a *new* prompt id, hence a
     * card the offer rule (`SshPromptOffer`, keyed by prompt id) had never seen
     * — the endless re-pop the device recorded, on an entry that had already
     * shown its `Password:` card. Cancelling now **mutes the attempt** instead:
     * the parked dial gets its answer, and no further challenge it raises can be
     * published ([publishPrompt] returns `null` ⇒ jsch is told "no"). The row
     * keeps whatever state the dial left it in — upstream's cancel on a row that
     * is still saved — and the next Reconnect calls [connect], which opens a new
     * generation, so the credentials *are* re-collected then (F12b's "二次
     * Reconnect 必重弹").
     */
    fun cancelPrompt() {
        val prompt = _state.value.prompt ?: return
        // v99: the attempt is muted **first** so nothing it raises afterwards is
        // published (that is the F12b/D1 fix and it stays), and the challenge
        // is then withdrawn **synchronously** through [sshCancelSettles]
        // instead of through the muted attempt's own `PromptResolved`. Before
        // this the withdraw event was emitted by the attempt `guard.cancel` had
        // just silenced, so it was dropped: the card stayed on screen, the row
        // stayed at CONNECTING, and the panel was blocked behind both — with
        // `Trust and connect` inert too, because the dial thread had already
        // taken its answer and left (`research/v97_verify/5554_v98d3/`
        // REPORT_COVERAGE6.md §2.2/§3).
        guard.cancel(guard.current())
        answers.offer(PromptAnswer(prompt.id, accepted = false, value = null))
        _state.update { sshCancelSettles(it, prompt.id) }
    }

    /**
     * Publishes [event] on behalf of [attempt]. A superseded or cancelled
     * attempt is silent — the [SshConnectEvent.Start] / [SshConnectEvent.Disconnect]
     * legs are the new attempt's own first words, so they always pass.
     */
    private fun emit(attempt: Int, event: SshConnectEvent) {
        if (!guard.speaks(attempt)) return
        // v97 D2: a settled READY is not re-decided by a stray terminal failure.
        if (!sshPublishesTerminal(_state.value, event)) return
        val next = reduceSshConnection(_state.value, event)
        // `update` (not `=`) so a concurrent emission cannot interleave between
        // the read and the write — the same atomic fold the pre-v97 `emit` had.
        _state.update { next }
    }

    private fun fail(attempt: Int, failure: SshFailure): SshConnectionStatus {
        emit(attempt, SshConnectEvent.Fail(failure))
        return _state.value
    }

    private fun teardown(doomed: Session?) {
        if (doomed === session) session = null
        runCatching { doomed?.disconnect() }
    }

    /**
     * v97 — `null` when the attempt is superseded or cancelled, which is the
     * caller's signal to refuse the challenge instead of drawing a card for it.
     */
    private fun publishPrompt(
        attempt: Int,
        text: String,
        confirm: Boolean,
        hostKey: SshHostKeyInfo?,
    ): SshPrompt? {
        if (!guard.speaks(attempt)) return null
        val prompt = SshPrompt(UUID.randomUUID().toString(), text, confirm)
        answers.clear()
        emit(attempt, SshConnectEvent.Prompted(prompt, hostKey))
        return prompt
    }

    private fun awaitAnswer(attempt: Int, prompt: SshPrompt): PromptAnswer {
        val answer = answers.poll(PROMPT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            ?: PromptAnswer(prompt.id, accepted = false, value = null)
        emit(attempt, SshConnectEvent.PromptResolved(prompt.id))
        return answer
    }

    /**
     * jsch's interactive surface. `promptYesNo` is the host-key trust question
     * (`ssh.trust`, with the fingerprints from [KnownHostsRepository.pending]);
     * the rest is upstream's non-confirm challenge (`ssh.continue`).
     */
    private inner class SshUserInfo(
        private val repository: KnownHostsRepository,
        /** The dial attempt this jsch session belongs to (v97 attempt guard). */
        private val attempt: Int,
    ) : UserInfo, UIKeyboardInteractive {

        @Volatile
        private var suppliedPassword: String? = null

        /**
         * The passphrase the prompt branch collected, kept for [getPassphrase].
         *
         * v94: `promptPassphrase` published the challenge but **discarded** the
         * answer — it returned `awaitAnswer(prompt).accepted` straight to jsch,
         * while `getPassphrase()` only ever read `config.passphrase`. With an
         * encrypted key and the form's passphrase left blank (the shape a
         * re-dialed resting row always takes — secrets are never persisted,
         * they are re-collected interactively), the typed passphrase was
         * dropped and jsch's decrypt could only fail. Same retention
         * [suppliedPassword] already had for `promptPassword`.
         */
        @Volatile
        private var suppliedPassphrase: String? = null

        override fun promptYesNo(message: String): Boolean {
            val prompt = publishPrompt(attempt, message, confirm = true, hostKey = repository.pending)
                ?: return false
            return awaitAnswer(attempt, prompt).accepted
        }

        override fun promptPassword(message: String): Boolean {
            // v100 (P3): a `Private key` entry has no account password to give.
            // Answering this would be the degradation itself — the user is asked
            // for a secret the form never collected for this method.
            if (activePlan.method != SshAuthMethod.PASSWORD) return false
            config.password?.let {
                suppliedPassword = it
                return true
            }
            val prompt = publishPrompt(attempt, message, confirm = false, hostKey = null) ?: return false
            val answer = awaitAnswer(attempt, prompt)
            suppliedPassword = answer.value
            return answer.accepted && !answer.value.isNullOrEmpty()
        }

        override fun promptPassphrase(message: String): Boolean {
            // v100: symmetric guard — the key passphrase is a `Private key`
            // entry's secret and must never be collected for a password one.
            if (activePlan.method != SshAuthMethod.KEY) return false
            config.passphrase?.let { return true }
            val prompt = publishPrompt(attempt, message, confirm = false, hostKey = null) ?: return false
            val answer = awaitAnswer(attempt, prompt)
            suppliedPassphrase = answer.value
            return answer.accepted && !answer.value.isNullOrEmpty()
        }

        override fun getPassword(): String? = suppliedPassword ?: config.password

        /** v94: the collected answer wins, so jsch can actually decrypt the key. */
        override fun getPassphrase(): String? = suppliedPassphrase ?: config.passphrase

        override fun showMessage(message: String) = Unit

        override fun promptKeyboardInteractive(
            destination: String?,
            name: String?,
            instruction: String?,
            prompt: Array<out String>?,
            echo: BooleanArray?,
        ): Array<String>? {
            // v100 (P3): keyboard-interactive is only ever a *password* method.
            // A `Private key` entry reaching it means publickey produced nothing
            // offerable, and the card drawn here is the `Password:` prompt the
            // device recorded (`REPORT_SSH_DIAG.md` §4.4). Refusing hands the
            // failure back to jsch, which now has no further method and reports
            // it — instead of silently succeeding on a different credential.
            if (activePlan.method != SshAuthMethod.PASSWORD) return null
            val text = listOfNotNull(instruction, prompt?.joinToString("\n"))
                .filter { it.isNotBlank() }
                .joinToString("\n")
            // v97: a muted attempt is refused outright, so the method walk that
            // turned a cancelled private-key dial into a `Password:` card stops
            // here instead of publishing the next challenge.
            val challenge = publishPrompt(attempt, text, confirm = false, hostKey = null) ?: return null
            val answer = awaitAnswer(attempt, challenge)
            return if (answer.accepted) arrayOf(answer.value.orEmpty()) else null
        }
    }

    /**
     * `known_hosts` shim: pure lookup ([SshKnownHosts]) + append-only file. An
     * unknown/changed key is *reported* here and settled by `promptYesNo` —
     * jsch only `add`s (and therefore persists) after `ssh.trust`.
     */
    private inner class KnownHostsRepository : HostKeyRepository {

        @Volatile
        var pending: SshHostKeyInfo? = null
            private set

        override fun check(host: String, key: ByteArray): Int {
            val type = sshHostKeyType(key) ?: return HostKeyRepository.NOT_INCLUDED
            val keyBase64 = Base64.getEncoder().encodeToString(key)
            val status = knownHosts.check(host, type, keyBase64)
            pending = SshHostKeyInfo(host, type, keyBase64, changed = status == SshHostKeyStatus.CHANGED)
            return when (status) {
                SshHostKeyStatus.OK -> HostKeyRepository.OK
                SshHostKeyStatus.NOT_INCLUDED -> HostKeyRepository.NOT_INCLUDED
                SshHostKeyStatus.CHANGED -> HostKeyRepository.CHANGED
            }
        }

        override fun add(hostkey: HostKey, ui: UserInfo?) {
            val line = knownHosts.record(hostkey.host, hostkey.type, hostkey.key)
            persist(line)
        }

        override fun remove(host: String, type: String) {
            knownHosts.remove(host, type)
            rewrite()
        }

        override fun remove(host: String, type: String, key: ByteArray) {
            knownHosts.remove(host, type, Base64.getEncoder().encodeToString(key))
            rewrite()
        }

        override fun getKnownHostsRepositoryID(): String = knownHostsFile?.path ?: "known_hosts"

        override fun getHostKey(): Array<HostKey> = getHostKey(null, null)

        override fun getHostKey(host: String?, type: String?): Array<HostKey> = knownHosts.snapshot()
            .filter { (host == null || it.host == host) && (type == null || it.type == type) }
            .mapNotNull { entry ->
                runCatching { HostKey(entry.host, Base64.getDecoder().decode(entry.keyBase64)) }.getOrNull()
            }
            .toTypedArray()

        private fun persist(line: String) {
            val file = knownHostsFile ?: return
            runCatching {
                file.parentFile?.mkdirs()
                file.appendText(line + "\n")
            }
        }

        /** Full rewrite after a `remove` — small files, correctness over churn. */
        private fun rewrite() {
            val file = knownHostsFile ?: return
            runCatching {
                file.parentFile?.mkdirs()
                file.writeText(knownHosts.snapshot().joinToString("") { encodeKnownHostsLine(it) + "\n" })
            }
        }
    }

    companion object {
        /** No answer within this window counts as "do not trust". */
        private const val PROMPT_TIMEOUT_SECONDS = 600L

        /** OpenSSH's local-user default when the target carries no `user@`. */
        fun defaultSshUser(): String = System.getProperty("user.name")?.takeIf { it.isNotBlank() } ?: "android"

        /** [routeEd25519ThroughBouncyCastle] runs once per process (idempotent anyway). */
        @Volatile
        private var ed25519Routed = false

        /**
         * **F14b (v97 device finding).** jsch 0.2.24 serves `ssh-ed25519` with two
         * implementations and its stock config names only the first:
         * `com.jcraft.jsch.jce.SignatureEd25519`. That class is a *multi-release*
         * entry — the jar's base entry is a stub whose every method throws
         * `UnsupportedOperationException("SignatureEd25519 requires Java15+")`,
         * and D8 packs the base entry into the APK (the stub sentence is in the
         * shipped `classes*.dex`), so on Android the JCE route is always dead.
         * The only other implementation, `com.jcraft.jsch.bc.SignatureEd25519`,
         * delegates to BouncyCastle (`org.bouncycastle:bcprov-jdk18on`, a hard
         * dependency in `app/build.gradle.kts`).
         *
         * With both routes dead, `KeyPairEdDSA.getSignature()` swallowed the
         * failure and returned `null`, so `UserAuthPublicKey` had no signature
         * to offer: the identity was skipped silently (sshd saw the session but
         * never an offered public key) and the server fell back to
         * keyboard-interactive. Routing both keys at the BouncyCastle classes
         * is what makes ed25519 identities offerable on device.
         *
         * `keypairgen_fromprivate.eddsa` already defaults to the bc class, so
         * only these two keys need the switch. jsch's config table is global
         * and static, hence the once-per-process guard: unit tests that merely
         * construct a connection must not observe a mutated configuration table
         * (the stock values are pinned by `SshEd25519IdentityF14Test`).
         */
        fun routeEd25519ThroughBouncyCastle() {
            if (ed25519Routed) return
            ed25519Routed = true
            JSch.setConfig("ssh-ed25519", "com.jcraft.jsch.bc.SignatureEd25519")
            JSch.setConfig("keypairgen.eddsa", "com.jcraft.jsch.bc.KeyPairGenEdDSA")
        }
    }
}

/** `~/.ssh/id_ed25519`-style paths from the form resolve against the real home. */
private fun File.expandUser(): File =
    if (path.startsWith("~/")) File(System.getProperty("user.home") ?: "/", path.substring(2)) else this
