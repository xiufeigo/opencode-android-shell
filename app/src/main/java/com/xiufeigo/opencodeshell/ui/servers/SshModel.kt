package com.xiufeigo.opencodeshell.ui.servers

import com.xiufeigo.opencodeshell.R

/**
 * The pure half of the SSH connection form — JVM-ported from upstream
 * `servers/ssh/name.ts` / `status.ts` / `types.ts` at the v2 head, plus the
 * Android auth-method extension the form needs.
 *
 * Nothing here talks to a host: [SshConnectionConfig] is the payload the
 * client consumes ([SshConnection], jsch — the SSH client library), and the
 * helpers mirror upstream's display and stage logic exactly so the UI half can
 * be pinned to them. Wire vocabulary shared with the tunnel half ([SshPrompt],
 * [SshErrorCode]) lives here because `types.ts` declares it next to [SshStage].
 */

/**
 * `SshStage` (`servers/ssh/types.ts:15-26`) — wire values are the lowercase
 * names: `disconnected` · `connecting` · `checking` · `downloading` ·
 * `uploading` · `starting` · `ready` · `authentication` · `incompatible` ·
 * `failed`.
 */
enum class SshStage {
    DISCONNECTED,
    CONNECTING,
    CHECKING,
    DOWNLOADING,
    UPLOADING,
    STARTING,
    READY,
    AUTHENTICATION,
    INCOMPATIBLE,
    FAILED,
}

/** `isSshConnecting` (`servers/ssh/status.ts:3-11`) — the in-flight stages. */
fun isSshConnecting(stage: SshStage): Boolean = when (stage) {
    SshStage.CONNECTING,
    SshStage.CHECKING,
    SshStage.DOWNLOADING,
    SshStage.UPLOADING,
    SshStage.STARTING,
    -> true

    SshStage.DISCONNECTED,
    SshStage.READY,
    SshStage.AUTHENTICATION,
    SshStage.INCOMPATIBLE,
    SshStage.FAILED,
    -> false
}

/** Upstream `SshConfig` (`types.ts:12`) is only `id`/`target`/`name`. */
enum class SshAuthMethod { PASSWORD, KEY }

/**
 * The connection the form collects. Wire (`types.ts:12`) keeps only
 * `id`/`target`/`name`; [auth] and the secret fields are the Android
 * stand-in for upstream's interactive prompts (`ssh/dialog.tsx:198-240`,
 * `ssh.continue` / `ssh.trust`), which need a live SSH session.
 */
data class SshConnectionConfig(
    val id: String,
    val target: String,
    val name: String,
    val auth: SshAuthMethod,
    val password: String? = null,
    val privateKeyPath: String? = null,
    val passphrase: String? = null,
)

/**
 * `sshHostname` (`servers/ssh/name.ts:3-12`): accepted targets end with a
 * hostname or `user@hostname`, so the **last** whitespace token is the host;
 * shell quoting is stripped for display only and the saved target stays
 * intact. IPv6 literals keep their brackets (`ssh user@[2001:db8::1]`).
 */
fun sshHostname(target: String): String {
    val last = target.trim().split(WHITESPACE).lastOrNull() ?: ""
    return last.replace(QUOTES, "").split("@").lastOrNull() ?: ""
}

/** `sshName` (`name.ts:14-16`) — the custom name, else [sshHostname]. */
fun sshName(name: String, target: String): String =
    if (name.isNotEmpty()) name else sshHostname(target)

/** First reason the form cannot be submitted (mirrors `ssh/dialog.tsx` gates). */
sealed interface SshFormError {
    /** Upstream's `!state.target.trim()` gate (`ssh/dialog.tsx:101,186`). */
    data object TargetRequired : SshFormError

    /** Auth half: `password` method with no password. */
    data object PasswordRequired : SshFormError

    /** Auth half: `key` method with no key path (passphrase is optional). */
    data object KeyRequired : SshFormError
}

/** Form-level gate for the [SshConnectionConfig] fields; `null` when submittable. */
fun validateSshForm(
    target: String,
    auth: SshAuthMethod,
    password: String?,
    privateKeyPath: String?,
): SshFormError? = when {
    target.trim().isEmpty() -> SshFormError.TargetRequired
    auth == SshAuthMethod.PASSWORD && password.isNullOrEmpty() -> SshFormError.PasswordRequired
    auth == SshAuthMethod.KEY && privateKeyPath?.trim().isNullOrEmpty() -> SshFormError.KeyRequired
    else -> null
}

/**
 * The dialog's error line. `oc_ssh_error_input` is upstream's
 * `ssh.error.input` sentence (`en.ts`); the other two cover the Android
 * auth-method fields, which upstream never renders.
 */
fun sshFormErrorResId(error: SshFormError): Int = when (error) {
    SshFormError.TargetRequired -> R.string.oc_ssh_error_input
    SshFormError.PasswordRequired -> R.string.oc_ssh_error_password_required
    SshFormError.KeyRequired -> R.string.oc_ssh_error_key_required
}

/**
 * The **three states** a submit attempt can leave the form's one feedback surface
 * in, as the form itself can observe them: nothing to say, the gate refused, or a
 * dial settled on a failure. The surface is [SshDialog]'s error / `status.error`
 * line — the only place either half of the submit path speaks.
 *
 * v90 (`research/v89_verify/5556/`): that surface is the **last** thing the form
 * column draws, and the v88/v89 pinned band leaves the scroller showing only the
 * card's head, its first field and (at best) the auth select. On the 1366x768
 * @160 dpi device the visible form ended on the auth select — `Authentication`
 * y=506, its `Password` value y=544 (`ui_06_add_ready.xml`) — with the pinned
 * footer at y=575, so **every feedback line drew below the clip**: the tester's
 * "Add server" tap with the Host filled was a silent no-op (the password gate
 * refused, its line drew off-screen, and no other surface said anything —
 * `ui_03`…`ui_10` carry no tunnel row, i.e. `SshTunnelHost.submit` was never
 * reached, and the primary button stayed the solid `#67B1C6` enabled fill
 * instead of the busy one).
 *
 * The states are therefore named here and the dialog brings [onScreen] ones back
 * into view on every attempt; the *surface* and its strings stay exactly where
 * they were, so no geometry and no resource moves.
 */
enum class SshSubmitFeedback(
    /** Whether this state has a line the user must be shown. */
    val onScreen: Boolean,
) {
    /** Nothing to say: no refusal, no settled failure (dialling, or a ready tunnel). */
    NONE(false),

    /** The form gate refused the submit — [SshFormError] is the line. */
    REFUSED(true),

    /** The dial settled on a failure — `SshConnectionStatus.error` is the line. */
    FAILED(true),
}

/**
 * What a submit attempt has on screen right now. The gate wins over a *previous*
 * attempt's dial failure, because `SshDialog.submit` clears `status` only by
 * dialling again: a refused tap leaves the older failure line below a fresher
 * refusal line, and the refusal is the one that explains the tap.
 */
fun sshSubmitFeedback(
    error: SshFormError?,
    status: SshConnectionStatus,
): SshSubmitFeedback = when {
    error != null -> SshSubmitFeedback.REFUSED
    status.error != null -> SshSubmitFeedback.FAILED
    else -> SshSubmitFeedback.NONE
}

/** `target.trim().split(/\s+/)` — JS keeps empty tokens differently on blank input. */
private val WHITESPACE = Regex("\\s+")

/** `replace(/["'\\]/g, "")` (`name.ts:8`). */
private val QUOTES = Regex("[\"'\\\\]")

/**
 * `SshPrompt` (`types.ts:28-32`) — an interactive challenge raised mid-connect:
 * `confirm == true` is the host-key trust question (`ssh.trust` /
 * `authenticate.tsx`), otherwise the runner expects a secret answer
 * (`ssh.continue`, `dialog.tsx:221-240`).
 */
data class SshPrompt(
    val id: String,
    val text: String,
    val confirm: Boolean,
)

/**
 * `SshItem.error` literals (`types.ts:39-49`): `connection` · `input` ·
 * `platform` · `version` · `install` · `service` · `host-key` · `ssh-missing` ·
 * `unpublished`.
 *
 * [KEY_FILE] is the one Android-only addition (F14a, v96 device finding): there
 * is no upstream literal for it because upstream shells out to the system `ssh`
 * binary and reads a bad `-i` identity off the full stderr dump, while jsch
 * hands back only the exception message (`FileNotFoundException: … (Permission
 * denied)`) — see [sshFailure].
 */
enum class SshErrorCode {
    CONNECTION,
    INPUT,
    PLATFORM,
    VERSION,
    INSTALL,
    SERVICE,
    HOST_KEY,
    SSH_MISSING,
    UNPUBLISHED,
    KEY_FILE,
}

/** `ssh.error.<literal>` (`connection-panel.tsx:29`) → the `ssh_error_*` resource. */
fun sshErrorResId(error: SshErrorCode): Int = when (error) {
    SshErrorCode.CONNECTION -> R.string.ssh_error_connection
    SshErrorCode.INPUT -> R.string.ssh_error_input
    SshErrorCode.PLATFORM -> R.string.ssh_error_platform
    SshErrorCode.VERSION -> R.string.ssh_error_version
    SshErrorCode.INSTALL -> R.string.ssh_error_install
    SshErrorCode.SERVICE -> R.string.ssh_error_service
    SshErrorCode.HOST_KEY -> R.string.ssh_error_host_key
    SshErrorCode.SSH_MISSING -> R.string.ssh_error_ssh_missing
    SshErrorCode.UNPUBLISHED -> R.string.ssh_error_unpublished
    SshErrorCode.KEY_FILE -> R.string.ssh_error_key_file
}

/** `ssh.stage.<literal>` (`dialog.tsx` / status copy) → the `ssh_stage_*` resource. */
fun sshStageResId(stage: SshStage): Int = when (stage) {
    SshStage.DISCONNECTED -> R.string.ssh_stage_disconnected
    SshStage.CONNECTING -> R.string.ssh_stage_connecting
    SshStage.CHECKING -> R.string.ssh_stage_checking
    SshStage.DOWNLOADING -> R.string.ssh_stage_downloading
    SshStage.UPLOADING -> R.string.ssh_stage_uploading
    SshStage.STARTING -> R.string.ssh_stage_starting
    SshStage.READY -> R.string.ssh_stage_ready
    SshStage.AUTHENTICATION -> R.string.ssh_stage_authentication
    SshStage.INCOMPATIBLE -> R.string.ssh_stage_incompatible
    SshStage.FAILED -> R.string.ssh_stage_failed
}
