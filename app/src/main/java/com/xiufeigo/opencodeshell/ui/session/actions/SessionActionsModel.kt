package com.xiufeigo.opencodeshell.ui.session.actions

import com.xiufeigo.opencodeshell.ChatStore
import com.xiufeigo.opencodeshell.v2.ServerGeneration

/**
 * Pure rules behind the session's ⋮ menu actions (W6): 撤销本轮变更 / 重做变更 /
 * 分叉会话 / 分享会话 / 归档会话. No Compose, no Android, no network — the host
 * (`SessionActionsHost`) and the header only render what this file decides.
 *
 * Every rule is a transcription of upstream v2:
 *
 * | rule | upstream |
 * |---|---|
 * | the reverted tail is hidden | `session/session-domain.ts:16-18`, `session/timeline/controller-projection.ts:25` |
 * | what 撤销 targets | `session/revert.ts:92-99` |
 * | what 重做 targets | `session/revert.ts:101-119` |
 * | menu enablement | `session/commands/use-session-commands.tsx:261,269,292` |
 * | fork message picker | `session/commands/fork-dialog.tsx:38-56` |
 */
enum class SessionActionsMenuItem { UNDO, REDO, FORK, SHARE, ARCHIVE }

/** What 撤销 / 重做 must call on the server. */
sealed interface RevertStep {
    /** `POST /session/:id/revert {messageID}` — rewind the boundary to this message. */
    data class RevertTo(val messageID: String) : RevertStep

    /** `POST /session/:id/unrevert` — upstream's `revert.clear`. */
    data object Unrevert : RevertStep
}

/**
 * One row of the fork dialog (`fork-dialog.tsx:17-21` `ForkableMessage`):
 * `{id, text, time}`. [text] is already single-lined and clipped to
 * [SessionActionsModel.FORK_TEXT_LIMIT]; [timeCreated] is the raw epoch millis
 * the row formats.
 */
data class ForkMessage(val id: String, val text: String, val timeCreated: Long)

/**
 * Enabled/selected state of the ⋮ menu's session-action rows. `shared` is the
 * one *selected* flag — the share row reads 取消分享 while it is true
 * (`use-session-commands.tsx` has no such branch; the toggle is the Android
 * form's, see `ChatViewModel.shareUrl`).
 *
 * Wave 2.4a: `shareVisible` / `archiveVisible` hide the v1-only rows on a v2
 * server (`V2Capability`: `session.share` / `session.archive` are V1_ONLY —
 * share/archive hit v1 routes that 404 on v2). The host (`SessionHeader`)
 * renders those rows only while visible.
 */
data class SessionActionsMenuState(
    val undoEnabled: Boolean = false,
    val redoEnabled: Boolean = false,
    val forkEnabled: Boolean = false,
    val shared: Boolean = false,
    val shareVisible: Boolean = true,
    val archiveVisible: Boolean = true,
)

object SessionActionsModel {

    /**
     * `fork-dialog.tsx:50` `text.replace(/\n/g, " ").slice(0, 200)` — the
     * replacement happens first, so the cap counts single-lined code units.
     */
    const val FORK_TEXT_LIMIT = 200

    /**
     * `history.userMessages()` — every user message, chronological. The revert
     * boundary is expressed in this list (see [undoStep]); the *visible* subset
     * is [visibleUserMessageIDs].
     */
    fun userMessageIDs(messages: List<ChatStore.UiMessage>): List<String> =
        messages.filter { it.role == "user" }.map { it.id }

    /**
     * The prompt text of one message: its text parts joined in order. Upstream
     * reads the server's flattened `message.text` (`fork-dialog.tsx:46`,
     * `composer/prompt.ts` `extractPromptFromMessage`); the store keeps parts, so
     * the projection happens here.
     */
    fun messageText(message: ChatStore.UiMessage): String =
        message.parts.mapNotNull { it.text }.filter { it.isNotEmpty() }.joinToString("\n")

    /**
     * `selectVisibleSessionUserMessages` (`session/session-domain.ts:16-18`):
     *
     * ```ts
     * if (!revertMessageID) return messages
     * return messages.filter((message) => message.id < revertMessageID)
     * ```
     *
     * The comparison is a plain string `<`, i.e. UTF-16 code-unit order — the
     * same as Kotlin's `String.compareTo`, because server ids are ULID-shaped
     * (`msg_…`) and sort chronologically.
     */
    fun visibleUserMessageIDs(userMessageIDs: List<String>, revertMessageID: String?): List<String> =
        if (revertMessageID.isNullOrEmpty()) {
            userMessageIDs
        } else {
            userMessageIDs.filter { it < revertMessageID }
        }

    /**
     * Every message (not just user ones) that the revert hides: the timeline
     * projection drops anything at or after the boundary
     * (`timeline/controller-projection.ts:25` `!revertMessageID || message.id <
     * revertMessageID`). Used by `ChatViewModel` to take the reverted tail off
     * screen — the server keeps returning those rows
     * (`GET /session/:id/message`, verified live on 1.18.31).
     */
    fun hiddenMessageIDs(messageIDs: List<String>, revertMessageID: String?): List<String> =
        if (revertMessageID.isNullOrEmpty()) emptyList()
        else messageIDs.filter { it >= revertMessageID }

    /**
     * 撤销本轮变更 (`session/revert.ts:92-99`), on the **raw** user list:
     *
     * ```ts
     * const boundary = reverted ? messages.findIndex((m) => m.id === reverted) : messages.length
     * if (boundary <= 0) return
     * const message = messages[boundary - 1]
     * if (message) await stage(message, messages[boundary - 2])
     * ```
     *
     * Nothing reverted → the boundary is the end of the list, so 撤销 rewinds the
     * **last** user message. Something reverted → the boundary is that message's
     * index and 撤销 moves it one message earlier. `null` = nothing to do (no user
     * messages, or already at the first one).
     */
    fun undoStep(userMessageIDs: List<String>, revertMessageID: String?): String? {
        val boundary = if (revertMessageID.isNullOrEmpty()) {
            userMessageIDs.size
        } else {
            userMessageIDs.indexOf(revertMessageID)
        }
        if (boundary <= 0) return null
        return userMessageIDs[boundary - 1]
    }

    /**
     * 重做变更 (`session/revert.ts:101-119`): with a boundary set, move it one
     * message later; past the last user message the only step left is
     * `revert.clear` → [RevertStep.Unrevert]. Without a boundary there is nothing
     * to redo (`null`), which is exactly the `disabled: … !revertMessageID()`
     * rule of `use-session-commands.tsx:269`.
     *
     * A boundary that is no longer in the list (a message older than the
     * snapshot window) is also `null`, mirroring `if (boundary < 0) return`.
     */
    fun redoStep(userMessageIDs: List<String>, revertMessageID: String?): RevertStep? {
        if (revertMessageID.isNullOrEmpty()) return null
        val boundary = userMessageIDs.indexOf(revertMessageID)
        if (boundary < 0) return null
        val next = userMessageIDs.getOrNull(boundary + 1) ?: return RevertStep.Unrevert
        return RevertStep.RevertTo(next)
    }

    /**
     * Menu enablement, mirroring the command palette's `disabled` predicates
     * (`use-session-commands.tsx:261` undo, `:269` redo, `:292` fork — all three
     * also require a session id, which the host only mounts when it has one):
     *
     * - 撤销: `visibleUserMessages().length === 0` disables it;
     * - 重做: `!revertMessageID()` disables it;
     * - 分叉: the same visible-user-message test as 撤销 (there is nothing to pick
     *   from an empty picker);
     * - 分享/取消分享: selected whenever a share URL is reported.
     *
     * Wave 2.4a: [generation] gates the v1-only rows — share/archive hit v1
     * routes with no v2 equivalent, so on anything but [ServerGeneration.V1]
     * they are hidden (`shareVisible` / `archiveVisible`) instead of failing.
     * Defaults to V1 so pre-generation callers keep the old rows.
     */
    fun menu(
        messages: List<ChatStore.UiMessage>,
        revertMessageID: String?,
        shareUrl: String?,
        generation: ServerGeneration = ServerGeneration.V1,
    ): SessionActionsMenuState {
        val users = userMessageIDs(messages)
        val visible = visibleUserMessageIDs(users, revertMessageID)
        val v1 = generation == ServerGeneration.V1
        return SessionActionsMenuState(
            undoEnabled = visible.isNotEmpty(),
            redoEnabled = !revertMessageID.isNullOrEmpty(),
            forkEnabled = visible.isNotEmpty(),
            shared = !shareUrl.isNullOrEmpty(),
            shareVisible = v1,
            archiveVisible = v1,
        )
    }

    /**
     * The fork picker's rows (`fork-dialog.tsx:38-56`): user messages with a
     * non-blank text, newest first (`result.reverse()`), each single-lined and
     * clipped to [FORK_TEXT_LIMIT].
     */
    fun forkMessages(messages: List<ChatStore.UiMessage>): List<ForkMessage> =
        messages.filter { it.role == "user" }
            .mapNotNull { message ->
                val text = messageText(message)
                text.takeIf { it.isNotBlank() }?.let {
                    ForkMessage(
                        id = message.id,
                        text = it.replace('\n', ' ').take(FORK_TEXT_LIMIT),
                        timeCreated = message.timeCreated,
                    )
                }
            }
            .reversed()
}
