package com.xiufeigo.opencodeshell.ui.servers

/**
 * v94 「SSH 条目行推进 Connecting over SSH… 之后，服务端再 challenge，用户只能等
 * 600 秒超时」(`research/v94_verify/5558/E2ECHK5558_39_reprompts.png`,
 * `research/v94_verify/5558/E2ECHK5558_40_prompt3.png`) — the **row-level** prompt
 * exit: upstream's `SshDialogs` for a dial that never went through a dialog.
 *
 * The defect is a missing exit, not a broken one. The SSH row's Reconnect
 * advances the line to `Connecting over SSH…`, a server-side challenge parks on
 * `SshConnection.state.prompt` — and the app's *only* [SshAuthenticate] renderer
 * is the config dialog's own prompt branch (`SshDialog.kt:228-245`). The Servers
 * list has no dialog to push, so nothing is composed for a prompt raised from a
 * row: the user is left looking at a line that never settles until
 * `PROMPT_TIMEOUT_SECONDS` (600 s, `SshConnection`'s companion) answers the
 * challenge by giving up. The v94 device round is exactly that path — the stored
 * password is refused, jsch falls back to keyboard-interactive, and the row waits
 * for a card that no code path ever draws.
 *
 * Upstream's three pieces, reproduced here as data so the rule is JVM-testable:
 * - `controller.ts:88-99` `dialog.next()` — the **first** item, in list order,
 *   whose attempt is `active`, not `submitting`, not `prompted`, not `error`, and
 *   that has something to answer (`item.prompt`);
 * - `context.tsx:58-69` `SshDialogs`' effect — while a dialog is already active
 *   nothing is pushed (`dialog.active`), otherwise the entry is marked offered
 *   (`dialog.opened(id)`) and the `promptOnly` card is pushed;
 * - `authentication-state.ts:4-5,13-24` — offered **once**: "cancelling must not
 *   immediately reopen the prompt".
 *
 * The one deliberate refinement over upstream: the offered mark is keyed by
 * **promptId**, not by attempt ([sshPromptOffered]). [SshConnection] mints a
 * fresh `SshPrompt.id` for every challenge it publishes
 * (`SshConnection.publishPrompt` → `SshUserInfo.promptKeyboardInteractive`), so one
 * dial can raise several prompts, and the second one — the refused stored password
 * falling back to keyboard-interactive, the v94 case — has to be answerable too.
 * Upstream's per-attempt `prompted` flag cannot express that; keying on the
 * prompt keeps upstream's guarantee (a prompt that was answered **or** cancelled
 * is never re-offered for that id) while a genuinely new challenge is.
 *
 * Out of scope, deliberately: upstream's other `dialog.next()` branch,
 * `item.stage === "incompatible"`. It carries no [SshPrompt] to render, so it
 * belongs to the form's incompatibility copy (`SshDialog`'s own surface), not to
 * a prompt card pushed from a row.
 */
data class SshPromptCandidate(
    /** `config.id` of the row — upstream's `item.config.id`, the attempt's key. */
    val configId: String,
    /** `status.prompt?.id`, or null while the row has no challenge to answer. */
    val promptId: String? = null,
    /** `status.error != null` — upstream's `attempt.error` (the settled attempt). */
    val error: Boolean = false,
)

/**
 * The challenge a card is opened for: the row it belongs to and the exact prompt
 * id that was offered, so the answer can never be routed to a different
 * challenge ([SshConnection.respondPrompt] refuses a mismatched id).
 */
data class SshPromptOffer(val configId: String, val promptId: String)

/**
 * One live [SshTunnel] as the minimal projection [sshPromptOffer] reads: the row's
 * config id, the in-flight challenge's id, and whether the attempt has already
 * failed. The four input routes (the panel's `sshHost.tunnels` collect) keep
 * their existing shape; only the offer rule sees this projection.
 */
fun SshTunnel.sshPromptCandidate(): SshPromptCandidate = SshPromptCandidate(
    configId = config.id,
    promptId = status.prompt?.id,
    error = status.error != null,
)

/**
 * `controller.ts:88-99` `dialog.next()` behind `context.tsx:64`'s `dialog.active`
 * gate: **null** while a dialog is already up (this panel's own SSH config dialog
 * answers its own prompt — a second card behind it is what upstream's
 * `if (!item || dialog.active) return` prevents), otherwise the first row, in
 * list order, that is waiting on a challenge no one has answered or cancelled.
 *
 * Suppressed per row, matching the upstream conjuncts: no challenge at all, a
 * settled attempt (`error`, `!attempt.error`), and a challenge whose id is
 * already in [offered] (`!attempt.prompted`). An empty id is treated as no
 * challenge — it could not be routed to [SshConnection.respondPrompt] anyway.
 */
fun sshPromptOffer(
    candidates: List<SshPromptCandidate>,
    offered: Map<String, String>,
    dialogActive: Boolean,
): SshPromptOffer? {
    if (dialogActive) return null
    for (candidate in candidates) {
        val promptId = candidate.promptId
        if (promptId.isNullOrEmpty() || candidate.error) continue
        if (offered[candidate.configId] == promptId) continue
        return SshPromptOffer(candidate.configId, promptId)
    }
    return null
}

/**
 * `controller.ts:99` `dialog.opened(id)` — and the same mark the card's three
 * callbacks write **before** they answer, which is what makes
 * `authentication-state.ts:4-5` hold on this surface: "cancelling must not
 * immediately reopen the prompt".
 *
 * Idempotent, so an effect that re-runs on an unrelated status change does not
 * grow the table, and keyed per config: a row that later raises a *new*
 * challenge is offered again, the answered one never is.
 */
fun sshPromptOffered(offered: Map<String, String>, offer: SshPromptOffer): Map<String, String> =
    if (offered[offer.configId] == offer.promptId) offered else offered + (offer.configId to offer.promptId)

/**
 * The prompt an offered card is still showing: id-matched against the live row,
 * so answering (which clears `status.prompt`, `SshConnectEvent.PromptResolved`),
 * a failure, or a fresh dial takes the card down on its own — the `promptOnly`
 * dialog has no close handler of its own here because the panel owns the offer.
 */
fun SshTunnel.sshPromptFor(offer: SshPromptOffer): SshPrompt? =
    status.prompt?.takeIf { config.id == offer.configId && it.id == offer.promptId }
