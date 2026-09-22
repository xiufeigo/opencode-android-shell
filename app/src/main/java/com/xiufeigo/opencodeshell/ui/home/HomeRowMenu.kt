package com.xiufeigo.opencodeshell.ui.home

/**
 * WAVE 2.3b P1 (A2 #4) — pure state behind Home's session-row context menu
 * (upstream `home/sessions/view.tsx:443-730`: right-click / 500 ms long-press /
 * `ContextMenu` key / `Shift+F10` → Rename / Export… / Delete…).
 *
 * The row UI keeps one menu + one rename editor open at a time, keyed by
 * session id at the list root (upstream `HomeSessionRowUI`, `view.tsx:87-90`):
 * session-store updates recreate row components, so row-local state would drop
 * an in-progress rename. [SessionRowMenuState] is the JVM-testable half — the
 * composable in `HomeSessionsList.kt` mirrors it with `remember` and drives the
 * `DropdownMenu` + inline `BasicTextField` from it.
 *
 * Rename validation mirrors the controller (`controller.tsx:141-167`): a blank
 * title or one identical to the current label is a no-op success (`true`
 * without a server call); only a changed, non-blank title is submitted.
 * `Export…` needs no state (fire-and-forget through the SAF saver);
 * `Delete…` opens the danger dialog and needs none either.
 */
object HomeRowMenu {

    /** Which row's menu is open, if any. */
    data class Menu(val sessionId: String)

    /**
     * The inline rename editor (`view.tsx:481-513`): [draft] is the live input,
     * [saving] disables it while the `PATCH` is in flight.
     */
    data class Editor(val sessionId: String, val draft: String, val saving: Boolean = false)

    /** One menu open at a time; opening another row's moves it. */
    fun openMenu(current: Menu?, sessionId: String): Menu? =
        if (current?.sessionId == sessionId) current else Menu(sessionId)

    /** Click-away / item pick closes only the matching row's menu. */
    fun closeMenu(current: Menu?, sessionId: String): Menu? =
        if (current?.sessionId == sessionId) null else current

    /** Rename item: prefill the editor with the row's current title. */
    fun openEditor(title: String, sessionId: String): Editor =
        Editor(sessionId = sessionId, draft = title)

    /** Typing updates the draft of the matching row only. */
    fun editDraft(current: Editor?, sessionId: String, draft: String): Editor? =
        if (current?.sessionId == sessionId) current.copy(draft = draft) else current

    /**
     * Enter key: blank or unchanged (after trim) resolves without a server call
     * (`controller.tsx:146`: `if (!next || next === sessionLabel(session))
     * return true`); anything else marks the editor saving and returns the
     * title to submit. Esc / blur while not saving discards ([closeEditor]).
     */
    sealed interface Submit {
        /** No server call — treat as success and close the editor. */
        object Noop : Submit

        /** Submit [title] via `PATCH`; editor shows [Editor.saving] meanwhile. */
        data class Save(val title: String) : Submit
    }

    fun submit(current: Editor?, sessionId: String, label: String): Pair<Editor?, Submit>? {
        if (current?.sessionId != sessionId || current.saving) return null
        val next = current.draft.trim()
        if (next.isEmpty() || next == label) return current to Submit.Noop
        return current.copy(saving = true) to Submit.Save(next)
    }

    /**
     * `PATCH` resolved: `true` closes the editor, `false` re-enables it with the
     * draft kept for another attempt (`view.tsx:501-513`).
     */
    fun resolveSave(current: Editor?, sessionId: String, saved: Boolean): Editor? {
        if (current?.sessionId != sessionId) return current
        return if (saved) null else current.copy(saving = false)
    }

    /** Esc / blur (while not saving) discards the editor. */
    fun closeEditor(current: Editor?, sessionId: String): Editor? {
        if (current?.sessionId != sessionId) return current
        return if (current.saving) current else null
    }

    /**
     * IME guard (`view.tsx:557-563`): Enter/Escape *during composition* (or the
     * Safari `keyCode 229` composition-confirming keydown) commits/cancels the
     * composition, not the rename — the key must not reach the editor actions.
     */
    fun isCompositionKey(isComposing: Boolean, keyCode: Int): Boolean =
        isComposing || keyCode == IME_COMPOSITION_KEYCODE

    /**
     * Keyboard menu opener (`view.tsx:622-627`): the `ContextMenu` key, or
     * `Shift+F10`, opens the row menu at the row's start.
     */
    fun menuKeyOpens(contextMenuKey: Boolean, shiftF10: Boolean): Boolean =
        contextMenuKey || shiftF10

    /** The Safari composition-confirming `keyCode` (`view.tsx:562`). */
    const val IME_COMPOSITION_KEYCODE = 229
}
