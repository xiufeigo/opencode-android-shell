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
     *
     * **v44 P0 — the commit is decided by the open editor, not by the row that
     * reported the gesture.** The list root keeps exactly one editor at a time
     * ([openMenu] / [openEditor], `view.tsx:87-90`) and only the row whose
     * `editor` is non-null renders the field, so the open editor *is* the
     * session being renamed. This used to take the reporting row's id and
     * return `null` on a mismatch — and the one call site dropped that `null`
     * with `?: return@HomeSessionList`, so any drift between the id the row
     * carries and the one the editor was opened with ate the whole commit:
     * Enter 0/5, the IME's ✓ 0/2, blur 0/2, no `PATCH` ever left the device
     * (`research/v44_verify/tC/v44_10/12/14/18`). The row is no longer part of
     * the decision, so it cannot cancel a gesture the field itself raised.
     *
     * `null` now means exactly one of two things: no editor is open, or a
     * `PATCH` for this one is already in flight.
     */
    sealed interface Submit {
        /** No server call — treat as success and close the editor. */
        object Noop : Submit

        /** Submit [title] via `PATCH`; editor shows [Editor.saving] meanwhile. */
        data class Save(val title: String) : Submit
    }

    fun submit(current: Editor?, label: String): Pair<Editor?, Submit>? {
        if (current == null || current.saving) return null
        val next = current.draft.trim()
        if (next.isEmpty() || next == label) return current to Submit.Noop
        return current.copy(saving = true) to Submit.Save(next)
    }

    /**
     * `PATCH` resolved: the editor closes on **either** answer, and the row goes
     * back to the label the server has — the new one when the write landed, the
     * one it refused when it did not. A failure is not a state the row may stay
     * in.
     *
     * v83 P1 (emulator-5558 `11_rename_t2.png` → `13_rename_t34.png`): a failure
     * used to keep the typed draft, so the inline editor sat on the new name —
     * the shape of a rename that worked — for as long as the editor stayed open,
     * and the row only snapped back to the old label after leaving Home.
     *
     * v84 P1 (emulator-5558 `11_rename_result.png` → `12_rollback.png` →
     * `17_rollback2.png`, 2/2): v83 answered that by keeping the editor open with
     * the draft rolled back, which on a phone is a **dead end**, and the rollback
     * never reached the screen anyway.
     *
     * * The dead end: the editor *is* the row while it is open, so long-press
     *   gives the platform's text-selection bar (Copy / Paste / Cut / Select all
     *   — `14_lp2.png`) instead of the session menu, and 改名 / 导出 / 导入 / 删除
     *   are all gone. Upstream keeps the editor open on a failure because a
     *   desktop can leave it (`Esc`, and `onBlur={closeEditor}`), which this
     *   shell's field does not do — it *commits* on blur (v43) — and a phone has
     *   no Esc at all. The row stayed in that state for the rest of the run
     *   (`12_rollback.png` and `13_rollback_10s.png` are 10 s apart and identical
     *   apart from the clock).
     * * The rollback: `HomeSessionRenameEditor` seeds its `TextFieldValue` once
     *   (`remember(editor.sessionId)`) and is written only by the IME afterwards,
     *   so a host-side draft rewrite was invisible to the field — the box kept
     *   showing `E2ECHK5558-renamed` while the model's draft said the old title.
     *
     * Closing is also what makes the failure *recoverable*: the row is a normal
     * row again, so the retry the v83 note wanted (long-press → 改名) is
     * reachable, with the server's own title prefilled by [openEditor]. The
     * answer itself is what the row renders afterwards — the catalogue's title,
     * which a failed rename never mirrors over (`HomeScreen` writes
     * `renamedTitles` only on `RENAMED`).
     *
     * Every shape of a resolved answer lands here — `RENAMED`, `GONE`,
     * `REFUSED`, `NOT_CONFIRMED`, `UNREACHABLE`, `NOT_SENT` — because the host
     * collapses them into [saved] before this call. No path may leave the row in
     * the editor.
     */
    fun resolveSave(current: Editor?, sessionId: String, saved: Boolean): Editor? {
        if (current?.sessionId != sessionId) return current
        return null
    }

    /**
     * v84 P1: an editor only exists over a row that is on screen.
     *
     * The editor is keyed by session id at the list root, and the list is
     * rebuilt from the catalogue on every read. A row that leaves the list while
     * its editor is open — `GONE`'s tombstone, a delete from another surface, a
     * scope change — takes the field with it, but not the state: `rowEditor`
     * stayed non-null, so `onEditingChanged(true)` kept telling the host to yield
     * Home's chrome (the projects panel, the utility nav) for a row nobody can
     * see. Pruning on every membership change keeps the two in step.
     */
    fun pruneEditor(current: Editor?, visibleSessionIds: Set<String>): Editor? =
        if (current != null && current.sessionId !in visibleSessionIds) null else current

    /** Esc / blur (while not saving) discards the editor. */
    fun closeEditor(current: Editor?, sessionId: String): Editor? {
        if (current?.sessionId != sessionId) return current
        return if (current.saving) current else null
    }

    /**
     * v43 P0: the three gestures that commit the inline editor, and the one rule
     * that decides whether any of them may (`view.tsx:481-513,531-581`).
     *
     * On a device the editor took Enter, the IME's ✓ **and** the tap-away and did
     * nothing at all — the draft never even reached `saving`, so `onSubmit` was
     * never called (`research/v43_verify/tC/v43_C1/C2/C5`). The composable half of
     * the field had no blur path and no way to reach its own keyboard, so a commit
     * gesture that arrived before the `InputConnection` was live had nowhere to
     * land. The rule below is the pure half of the fix: **any** of the three
     * commits, none of them while a `PATCH` is in flight, and blur only from a
     * field that actually had focus (a field that never took focus must not save
     * a draft the user never saw).
     */
    enum class CommitTrigger {
        /** `KeyboardActions(onDone)` — the IME's ✓ / `ImeAction.Done`. */
        IME_DONE,

        /** `Key.Enter` / `Key.NumPadEnter` through `onPreviewKeyEvent`. */
        ENTER,

        /** The field lost focus while it held it. */
        BLUR,
    }

    fun shouldCommit(trigger: CommitTrigger, saving: Boolean, focused: Boolean): Boolean = when (trigger) {
        CommitTrigger.IME_DONE, CommitTrigger.ENTER -> !saving
        CommitTrigger.BLUR -> !saving && focused
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
