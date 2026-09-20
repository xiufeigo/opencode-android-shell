package com.xiufeigo.opencodeshell.ui.session.composer

/**
 * Pure submit/stop decisions for the composer (`research/02` §4.6 / §4.7).
 *
 * Extracted from [Composer] so the rules that choose between "send", "queue a
 * follow-up" and "stop the run" are JVM-testable: a wrong answer here either
 * aborts a live run the user did not want to stop, or silently swallows a draft.
 */
internal object ComposerSubmit {

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
        !isShiftPressed && !isRepeat
}
