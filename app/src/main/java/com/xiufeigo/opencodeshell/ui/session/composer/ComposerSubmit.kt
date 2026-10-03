package com.xiufeigo.opencodeshell.ui.session.composer

import com.xiufeigo.opencodeshell.ChatViewModel

/**
 * Pure submit/stop decisions for the composer (`research/02` §4.6 / §4.7).
 *
 * Extracted from [Composer] so the rules that choose between "send", "queue a
 * follow-up" and "stop the run" are JVM-testable: a wrong answer here either
 * aborts a live run the user did not want to stop, or silently swallows a draft.
 *
 * Public (not `internal`) because [Composer] is public and takes [Payload] as a
 * callback parameter: a public composable cannot expose an internal type.
 */
object ComposerSubmit {
    /** "Nothing left to submit": no text and no pending attachment (§4.6 `blank()`). */
    fun hasContent(text: String, attachmentCount: Int): Boolean =
        text.isNotBlank() || attachmentCount > 0

    /**
     * §4.6: the primary button shows **stop** only while a run is active *and*
     * there is nothing to submit. With text or attachments pending it stays a
     * send button, so a typed follow-up can never abort the run — upstream gates
     * the stop icon on `working() && blank()` and queues the follow-up submit
     * instead.
     */
    fun stopping(running: Boolean, text: String, attachmentCount: Int): Boolean =
        running && !hasContent(text, attachmentCount)

    /**
     * §4.7 keyboard rule: `Enter` submits, `Shift+Enter` inserts a newline, and a
     * key auto-repeat (`repeatCount > 0`) must not fire a second send.
     *
     * The "non-composing" half of the rule is owned by the IME path: soft
     * keyboards arrive as `ImeAction.Send` → `onSend`, and an IME only performs
     * the editor action after committing its composing text.
     */
    fun enterSubmits(isShiftPressed: Boolean, isRepeat: Boolean): Boolean =
        enterAction(isShiftPressed, isRepeat) == EnterAction.SUBMIT

    /**
     * What a hardware `Enter` / `NumPadEnter` key-down means, upstream
     * `composer/editor/editor.tsx:211`
     * (`event.key === "Enter" && !event.shiftKey && !event.isComposing` → submit,
     * every other Enter falls through to the `<textarea>` and newlines).
     *
     * The split matters because the composer asks the IME for `ImeAction.Send`:
     * Compose's text field performs that IME action for **any** Enter key-down
     * that reaches it (`TextFieldDelegate.processKeyEvent`), so letting a
     * `Shift+Enter` fall through did not newline — it sent. The composer
     * therefore owns the whole key: [NEWLINE] inserts the break itself and
     * [SUBMIT] submits, and both are consumed.
     *
     * [IGNORE] is the auto-repeat case: a held Enter is swallowed rather than
     * falling through, which used to re-run the IME action on every repeat.
     */
    enum class EnterAction { SUBMIT, NEWLINE, IGNORE }

    /** See [EnterAction]; an IME that is still composing newlines (upstream). */
    fun enterAction(isShiftPressed: Boolean, isRepeat: Boolean, isComposing: Boolean = false): EnterAction =
        when {
            isShiftPressed || isComposing -> EnterAction.NEWLINE
            isRepeat -> EnterAction.IGNORE
            else -> EnterAction.SUBMIT
        }

    /**
     * W5-C: may this editor content be submitted? Shell mode requires real text
     * (`composer/editor/interaction.ts:343-354` `canSubmit()` returns
     * `prompt.some(content.trim())` for shell mode and never counts attachments) —
     * `POST /session/:id/shell` has no file parts at all. Normal mode keeps the
     * M1.5 rule, so an attachment-only prompt still sends.
     */
    fun canSubmit(text: String, attachmentCount: Int, shell: Boolean): Boolean =
        if (shell) text.isNotBlank() else hasContent(text, attachmentCount)

    /**
     * The composer's submit payload (W5-C). One value carries the three write
     * shapes so the screen can route them in one lambda:
     * [shell] → `POST /session/:id/shell`, [command] ≠ null →
     * `POST /session/:id/command`, otherwise a plain prompt.
     */
    data class Payload(
        val text: String,
        val attachments: List<ChatViewModel.Attachment> = emptyList(),
        val shell: Boolean = false,
        /** The server command name (no leading slash) when the text is one. */
        val command: String? = null,
        val arguments: String = "",
    )
}