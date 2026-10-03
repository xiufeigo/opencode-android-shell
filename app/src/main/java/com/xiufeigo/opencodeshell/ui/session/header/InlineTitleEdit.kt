package com.xiufeigo.opencodeshell.ui.session.header

import androidx.compose.ui.text.TextRange

/**
 * The commit/cancel contract of the header's inline title editor. Pure, so the matrix is
 * JVM-testable (`SessionTitleInlineEditTest`) — the same split `ComposerPopoverEsc` /
 * `ComposerImeCommit` use for the composer.
 *
 * Two device bugs live here (v32, `research/v32_verify/tA/`):
 *
 * **Escape saved** (`tA_19_esc_cancel.png`). Closing the editor *detaches* the focused
 * field, and `FocusTargetNode.onDetach` force-clears focus with
 * `refreshFocusEvents = true` (`ui-android:1.7.6` `FocusTargetNode.kt:83-104` →
 * `FocusTransactions.kt:132-140` → `refreshFocusEventNodes`), so the field's
 * `onFocusChanged` observer fires `isFocused == false` **on the way out**. The blur-commit
 * the editor wants for a tap-away therefore ran *after* the cancel and wrote the draft the
 * cancel had just discarded. [settled] is the one latch: whichever of commit/cancel runs
 * first, the trailing blur decides nothing.
 *
 * **The draft spliced into the old title** (`tA_17_title_typed.png`: `Untitled` with the
 * typed text in front of it). See [entrySelection].
 *
 * A blank draft is a cancel, never a rename to `""`.
 */
internal class InlineTitleEdit(initial: String) {

    /**
     * What one event decided.
     *
     * - [save] != null — write it and close (Enter, the IME's `Done` action, or the blur
     *   of a tap-away);
     * - [close] with no [save] — cancel: close the editor and write nothing (Escape, or a
     *   blank draft);
     * - neither — the editor already settled; the event is ignored.
     */
    data class Decision(val close: Boolean, val save: String? = null)

    /**
     * The selection the field opens with: the **whole** title. Upstream focuses and then
     * selects it in both places it edits a title (`message-timeline.tsx:270-277` and
     * `home/sessions/view.tsx:481-489`: `input.focus(); input.select()`), so the first
     * keystroke replaces the old title instead of splicing into it.
     *
     * `BasicTextField`'s String overload cannot express that: it seeds its internal state
     * with `TextFieldValue(text = value)` (`foundation 1.7.6` `BasicTextField.kt:614`),
     * whose selection is `TextRange.Zero` — the cursor sat *in front of* the old title.
     *
     * Typing replaces the selection because the IME's own edits go through
     * `CommitTextCommand` / `SetComposingTextCommand`, and both apply
     * `buffer.replace(buffer.selectionStart, buffer.selectionEnd, text)` — "insert into
     * cursor or replace selection are equivalent" (`ui-text 1.7.6` `EditCommand.kt:62-70`,
     * `:187-200`). The buffer's selection comes from this value
     * (`EditProcessor.reset` → `EditingBuffer.setSelection`).
     */
    val entrySelection: TextRange = TextRange(0, initial.length)

    /** True once a commit or a cancel ran. See the class KDoc. */
    var settled: Boolean = false
        private set

    /**
     * True once [leave] ran — the editor's composition is being torn down because
     * the session is no longer the one on screen. See [leave].
     */
    var leaving: Boolean = false
        private set

    /**
     * **Navigating away discards the draft** (v97 N3, `research/v97_fix/TITLE_EDITOR_CANCEL_DIAG.md` §★).
     *
     * Upstream spells this as an explicit parent-driven guard —
     * `message-timeline.tsx:285-289`:
     *
     * ```tsx
     * createEffect(() => {
     *   if (props.active !== false) return
     *   setSummary(false)
     *   setTitle({ draft: "", editing: false, menuOpen: false, pendingRename: false })
     * })
     * ```
     *
     * The shell had no counterpart, and the teardown blur filled the gap with a
     * **commit**: leaving the screen detaches the focused field, and that detach
     * force-clears focus (the v32 mechanism in the class KDoc), so `onFocusChanged`
     * fired `isFocused == false` and [commit] wrote the half-typed draft to the
     * server. A device pass renamed `E2ECHK5562-01` to `Queued` that way, and
     * `SessionTitleManualGuardV78Test.kt:41-58` then froze it — one stray rename
     * marks the session manually named and no server title ever wins again.
     *
     * The Android spelling is a teardown hook rather than an `active` prop: Compose
     * has no "about to be disposed" state to read, but the dispose **is** the event,
     * and it lands in the same `applyChanges` pass that detaches the node — before the
     * focus event the blur callback is served from ([settled] makes the ordering
     * argument moot even if a build ever dispatches them the other way round).
     */
    fun leave(): Decision {
        leaving = true
        return cancel()
    }

    /**
     * Enter, the IME's `Done` action, or a blur.
     *
     * Not a blur *caused by leaving*: [leave] already latched, and this keeps that
     * promise independently of the [settled] latch so the discard is a property of
     * the state machine and not of a dispatch order.
     */
    fun commit(draft: String): Decision {
        if (leaving) return cancel()
        if (settled) return Decision(close = false)
        settled = true
        val trimmed = draft.trim()
        return if (trimmed.isEmpty()) Decision(close = true) else Decision(close = true, save = trimmed)
    }

    /** Escape. Never writes — the blur that closing the editor produces is swallowed. */
    fun cancel(): Decision {
        if (settled) return Decision(close = false)
        settled = true
        return Decision(close = true)
    }
}
