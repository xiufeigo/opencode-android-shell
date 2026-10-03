package com.xiufeigo.opencodeshell.ui.session.actions

import com.xiufeigo.opencodeshell.ChatStore

/**
 * Pure rules behind the session's ⋮ menu actions (W6): 撤销本轮变更 / 重做变更 /
 * 分叉会话 / 移动到… (F-4). No Compose, no Android, no network — the host
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
 * | 移动到… (always on) | `session/timeline/session-workspace-menu.tsx:59-87` |
 */
enum class SessionActionsMenuItem { UNDO, REDO, FORK, MOVE }

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
 * Enabled state of the ⋮ menu's session-action rows. v2-only: share/archive hit
 * v1 routes that 404 on v2 (`V2Capability`: `session.share` / `session.archive`
 * are V1_ONLY), so those rows — and this state's flags for them — are gone.
 *
 * F-4 adds 移动到…: upstream moves from the workspace menu
 * (`session-workspace-menu.tsx`), which Android does not render, so the ⋮
 * group carries it. The move needs no transcript and no boundary — the host
 * mounts only while a session is tracked — hence [moveEnabled] is always true.
 */
data class SessionActionsMenuState(
    val undoEnabled: Boolean = false,
    val redoEnabled: Boolean = false,
    val forkEnabled: Boolean = false,
    val moveEnabled: Boolean = true,
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
     * What the composer gets back when a revert drops a user message
     * (`_upstream/packages/app/src/session/revert.ts:31-57` `restore`) — the
     * prompt text plus every file part the composer can re-attach as-is.
     *
     * Only `data:` urls ride back: a workspace `@` comment is stored as a
     * `file://` url, and the composer's attachment payload needs the bytes
     * (a base64 data url), which would take a read the revert path does not do.
     * The text still comes home for those, so nothing the user typed is lost.
     */
    data class RevertRestore(
        val text: String,
        val files: List<RevertFile>,
    ) {
        val isEmpty: Boolean get() = text.isEmpty() && files.isEmpty()
    }

    /** One `data:` file part, in the three fields a composer attachment needs. */
    data class RevertFile(val mime: String, val filename: String, val dataUrl: String)

    /**
     * [RevertRestore] for [messageID] over the **raw** message list, or `null`
     * when the id is unknown or the message carries nothing the composer can
     * take back. Raw, not the visible projection: the boundary the revert is
     * about to write is precisely what takes the message off screen.
     */
    fun revertRestore(
        messages: List<ChatStore.UiMessage>,
        messageID: String,
    ): RevertRestore? {
        if (messageID.isEmpty()) return null
        val message = messages.firstOrNull { it.id == messageID } ?: return null
        val files = message.parts.mapNotNull { part ->
            val url = part.url
            if (part.type == "file" && url != null && url.startsWith(DATA_URL_PREFIX)) {
                RevertFile(mime = part.mime.orEmpty(), filename = part.filename.orEmpty(), dataUrl = url)
            } else {
                null
            }
        }
        val restore = RevertRestore(messageText(message).trim(), files)
        return if (restore.isEmpty) null else restore
    }

    /** The data-url scheme an inline attachment is stored under. */
    const val DATA_URL_PREFIX = "data:"

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
     * Message-level「撤销到此」(`session/revert.ts:85-119` `to`, the per-message
     * `MessageActionButton icon="reset"` of `message-content.tsx:328-338`):
     * stage the boundary at [messageID], a **user** message id. Upstream looks
     * the id up in `history.userMessages()` and no-ops when it is missing —
     * the same `null` here. The button itself is [MessageRevertAction]; the
     * write is `ChatViewModel`'s `stageRevert` (see the wiring note there).
     */
    fun revertToStep(userMessageIDs: List<String>, messageID: String): RevertStep.RevertTo? =
        if (messageID.isNotEmpty() && messageID in userMessageIDs) RevertStep.RevertTo(messageID) else null

    /**
     * Menu enablement, mirroring the command palette's `disabled` predicates
     * (`use-session-commands.tsx:261` undo, `:269` redo, `:292` fork — all three
     * also require a session id, which the host only mounts when it has one):
     *
     * - 撤销: `visibleUserMessages().length === 0` disables it;
     * - 重做: `!revertMessageID()` disables it — and the row is then **hidden**
     *   outright ([showsRedo]), because 撤销 is destructive here and the row can
     *   never come back after the staged revert settles;
     * - 分叉: the same visible-user-message test as 撤销 (there is nothing to pick
     *   from an empty picker).
     * - 移动到… (F-4): always on — the only gate is the session id, which the
     *   host already guarantees. Upstream's workspace menu additionally locks
     *   while a move is in flight (`store.selected`); the host dismisses the
     *   picker before the write lands, so there is no second tap to guard.
     *
     * v2-only drops 分享/归档 (their routes 404 on v2), so the menu stops at the
     * four rows.
     */
    fun menu(
        messages: List<ChatStore.UiMessage>,
        revertMessageID: String?,
    ): SessionActionsMenuState {
        val users = userMessageIDs(messages)
        val visible = visibleUserMessageIDs(users, revertMessageID)
        return SessionActionsMenuState(
            undoEnabled = visible.isNotEmpty(),
            redoEnabled = !revertMessageID.isNullOrEmpty(),
            forkEnabled = visible.isNotEmpty(),
            moveEnabled = true,
        )
    }

    /**
     * v51 `tE`: does the ⋮ menu render the 重做 row at all?
     *
     * 撤销 in this shell is **destructive** — it stages a real revert in the
     * server session, so there is no redo stack to walk back along once the
     * revert lands. Upstream keeps a client-side history (`use-session-commands.tsx`
     * undo/redo push to a local stack) and can therefore re-enable 重做 after
     * every undo; here `revertMessageID` is cleared as soon as the write
     * settles, so 重做 is greyed out again — permanently, and after exactly the
     * undo the user would most want to take back (v51: undo, then the row never
     * lit up and the tap did nothing but close the menu).
     *
     * A row that can never work is worse than no row: it advertises a recovery
     * the shell does not have. So the menu shows 重做 only while it can run and
     * drops it otherwise. [SessionActionsMenuState.redoEnabled] stays as the
     * single source of truth — this is its rendering rule, not a second one.
     */
    fun showsRedo(redoEnabled: Boolean): Boolean = redoEnabled

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
