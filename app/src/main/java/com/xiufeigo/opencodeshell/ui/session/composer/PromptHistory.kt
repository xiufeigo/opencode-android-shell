package com.xiufeigo.opencodeshell.ui.session.composer

import java.util.concurrent.ConcurrentHashMap

/**
 * W5-C B7: the composer's `↑` / `↓` prompt history.
 *
 * Faithful to `composer/editor/interaction.ts:261-298` + `composer/history/entry.ts`:
 * - entries are kept **newest first**, capped at [MAX_ENTRIES] (`MAX_HISTORY = 100`,
 *   `entry.ts:8,28-49`);
 * - a blank prompt is never recorded (`entry.ts:40`), and a repeat of the newest
 *   entry is dropped (`entry.ts:46-47`);
 * - `↑` starts scrolling from the newest entry, `↓` walks back and finally
 *   restores the draft that was in the editor when history navigation started
 *   (the `savedHistory` slot, `interaction.ts:274-297`);
 * - navigation is gated by [canNavigate] (`canNavigateHistory`,
 *   `interaction.ts:468-473`): `↑` only from an empty editor with the caret at 0,
 *   `↓` only from the end of the text, and while inside history from either end;
 * - the two modes keep separate stacks (`entries(mode)`, `store.ts:29`).
 *
 * The history is session-scoped and in-memory (the brief allows "按会话内存栈");
 * [ComposerHistory] owns one instance per composer key so a screen switch does
 * not lose it either.
 */
class PromptHistory(
    private val max: Int = MAX_ENTRIES,
) {

    /** `"normal" | "shell"` stacks; newest entry at index 0. */
    private val stacks = mapOf(
        false to ArrayDeque<String>(),
        true to ArrayDeque<String>(),
    )

    /** `-1` = not navigating; otherwise an index into the active stack. */
    var index: Int = -1
        private set

    /** The draft that was in the editor before `↑` entered history mode. */
    var saved: String? = null
        private set

    fun entries(shell: Boolean = false): List<String> = stacks.getValue(shell).toList()

    /** True while `↑`/`↓` is walking the stack (`state.historyIndex >= 0`). */
    val navigating: Boolean get() = index >= 0

    /**
     * Records a submitted prompt. Blank prompts are ignored, a repeat of the
     * newest entry is not stored twice, and navigating state is reset — the same
     * three rules as `prependHistoryEntry` + `addHistory` (`interaction.ts:387-390`).
     */
    fun add(text: String, shell: Boolean = false) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            reset()
            return
        }
        val stack = stacks.getValue(shell)
        if (stack.firstOrNull() != trimmed) {
            stack.addFirst(trimmed)
            while (stack.size > max) stack.removeLast()
        }
        reset()
    }

    /**
     * Undoes [add] — a send that failed puts its prompt back into the composer, so
     * keeping the entry would only duplicate the restored draft
     * (`removeHistoryEntry`, `entry.ts:51-61`).
     */
    fun remove(text: String, shell: Boolean = false) {
        val trimmed = text.trim()
        val stack = stacks.getValue(shell)
        stack.removeAll { it == trimmed }
        reset()
    }

    /** Forgets the navigation position (a new input, a switched session). */
    fun reset() {
        index = -1
        saved = null
    }

    /**
     * `↑` — answers the text to show, or null when there is nothing to navigate
     * (`navigateHistory("up")`, `interaction.ts:274-286`).
     */
    fun up(text: String, caret: Int, shell: Boolean = false): String? {
        val stack = stacks.getValue(shell)
        if (!canNavigate(up = true, text = text, caret = caret, navigating = navigating)) return null
        if (navigating && index >= stack.size - 1) return null
        if (!navigating) saved = text
        val next = index + 1
        val entry = stack.getOrNull(next) ?: return null
        index = next
        return entry
    }

    /**
     * `↓` — walks towards the draft, then restores it
     * (`navigateHistory("down")`, `interaction.ts:287-297`). Answers null when
     * there is nothing to do.
     */
    fun down(text: String, caret: Int, shell: Boolean = false): String? {
        if (!canNavigate(up = false, text = text, caret = caret, navigating = navigating)) return null
        if (!navigating) return null
        if (index > 0) {
            index -= 1
            return stacks.getValue(shell).getOrNull(index)
        }
        val restored = saved ?: ""
        reset()
        return restored
    }

    companion object {
        const val MAX_ENTRIES = 100

        /**
         * `canNavigateHistory` (`interaction.ts:468-473`): the plain caret rules
         * that keep `↑`/`↓` from fighting the multi-line editor.
         */
        fun canNavigate(up: Boolean, text: String, caret: Int, navigating: Boolean): Boolean {
            val position = caret.coerceIn(0, text.length)
            if (navigating) return position == 0 || position == text.length
            if (up) return position == 0 && text.isEmpty()
            return position == text.length
        }
    }
}

/**
 * One [PromptHistory] per composer key (session id), so switching sessions on the
 * screen keeps each session's stack — mirroring the per-session composer key the
 * upstream persistence layer uses.
 */
object ComposerHistory {

    private val stacks = ConcurrentHashMap<String, PromptHistory>()

    fun of(key: String): PromptHistory = stacks.getOrPut(key) { PromptHistory() }

    /** Test seam: forget every session's stack. */
    fun reset() {
        stacks.clear()
    }
}
