package com.xiufeigo.opencodeshell.ui.session.timeline

import com.xiufeigo.opencodeshell.ChatStore

/**
 * B-5: `message.previous` / `message.next` — the pure half of upstream's
 * `navigateMessage(offset)` (`session/timeline/interaction.ts:133-149`), so the
 * contract is unit-testable without a Compose runtime.
 *
 * Upstream walks the **visible user messages**: `visibleUserMessages()` is the
 * boundary-filtered user rows, `current` is the message the scroll cursor sits
 * on (or none), `base` is its index — or `messages.length` when there is no
 * current message, i.e. "past the end" — and the target is `base + offset`.
 * Three outcomes:
 *
 * - `target` outside `0..messages.length` → **null**, the chord does nothing
 *   (upstream returns without scrolling);
 * - `target == messages.length` → [Target.Resume]: unpin, scroll to the end;
 * - otherwise → [Target.Scroll] at the target message's row index.
 *
 * The Android shell has no separate cursor store: `timelineRowIndex` (the map
 * `MessageTimeline` publishes) plus the first visible row is the same
 * information, so `current` is the **last** user message whose row sits at or
 * above the viewport top.
 */
object MessageNav {

    /** What the caller should do — `scrollToMessage` or upstream's `resume()`. */
    sealed interface Target {
        /** `target === messages.length`: scroll back to the live end. */
        object Resume : Target

        /** `scrollToMessage(messages[target], "auto")` — a row index to reveal. */
        data class Scroll(val index: Int) : Target
    }

    /**
     * @param messages the visible messages, in timeline order (`ChatStore.ChatState.messages`
     *   is already filtered by the revert boundary — the same set upstream calls
     *   `visibleUserMessages()` for the user rows).
     * @param rowIndex `messageID → row index`, published by the timeline. A
     *   target whose id is outside the snapshot window cannot be scrolled to, so
     *   it answers null instead of a wrong index.
     * @param firstVisibleIndex the first visible row (`LazyListState`), the
     *   stand-in for upstream's scroll cursor.
     * @param offset -1 for `message.previous`, +1 for `message.next`.
     */
    fun target(
        messages: List<ChatStore.UiMessage>,
        rowIndex: Map<String, Int>,
        firstVisibleIndex: Int,
        offset: Int,
    ): Target? {
        if (offset == 0) return null
        val userMessages = messages.filter { it.role == "user" }
        if (userMessages.isEmpty()) return null
        val current = userMessages.lastOrNull { message ->
            val row = rowIndex[message.id]
            row != null && row <= firstVisibleIndex
        }
        val base = current?.let { message -> userMessages.indexOfFirst { it.id == message.id } }
            ?.takeIf { it >= 0 } ?: userMessages.size
        val target = base + offset
        if (target < 0 || target > userMessages.size) return null
        if (target == userMessages.size) return Target.Resume
        val index = rowIndex[userMessages[target].id] ?: return null
        return Target.Scroll(index)
    }
}
