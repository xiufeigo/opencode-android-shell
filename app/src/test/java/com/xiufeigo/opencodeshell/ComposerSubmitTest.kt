package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.composer.ComposerSubmit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Composer submit/stop rules from `research/02` §4.6 (stop only while blank) and
 * §4.7 (Enter submits, Shift+Enter newlines).
 *
 * The rules themselves are pure; the wire-up inside the composable is guarded at
 * source level, which is the convention this repo already uses for Compose glue
 * (`ComposerM1_5Test`, `ImeSessionLayoutTest`).
 */
class ComposerSubmitTest {

    // ------------------------------------------------------------ blank rule

    @Test
    fun onlyTextOrAttachmentsMakeAPrompt() {
        assertFalse(ComposerSubmit.hasContent("", 0))
        assertFalse("whitespace is not a prompt", ComposerSubmit.hasContent("   \n\t", 0))
        assertTrue(ComposerSubmit.hasContent("hello", 0))
        assertTrue("attachment-only prompts are valid", ComposerSubmit.hasContent("", 1))
        assertTrue(ComposerSubmit.hasContent("   ", 2))
    }

    // --------------------------------------------------------- stopping rule

    @Test
    fun idleComposerNeverShowsStop() {
        assertFalse(ComposerSubmit.stopping(running = false, text = "", attachmentCount = 0))
        assertFalse(ComposerSubmit.stopping(running = false, text = "draft", attachmentCount = 0))
    }

    @Test
    fun runningWithABlankInputShowsStop() {
        assertTrue(ComposerSubmit.stopping(running = true, text = "", attachmentCount = 0))
        assertTrue(ComposerSubmit.stopping(running = true, text = "  \n", attachmentCount = 0))
    }

    @Test
    fun runningWithTextOrAttachmentsKeepsTheSendButton() {
        assertFalse(
            "typing must not offer abort (queued follow-up)",
            ComposerSubmit.stopping(running = true, text = "follow-up", attachmentCount = 0),
        )
        assertFalse(ComposerSubmit.stopping(running = true, text = "", attachmentCount = 1))
    }

    // ----------------------------------------------------------- enter rule

    @Test
    fun enterSubmitsUnlessShiftOrAutoRepeat() {
        assertTrue(ComposerSubmit.enterSubmits(isShiftPressed = false, isRepeat = false))
        assertFalse("Shift+Enter inserts a newline", ComposerSubmit.enterSubmits(true, false))
        assertFalse("a held Enter must not spam sends", ComposerSubmit.enterSubmits(false, true))
    }

    // ------------------------------------------------------- enter action rule

    @Test
    fun enterResolvesToOneOfThreeActions() {
        assertEquals(
            ComposerSubmit.EnterAction.SUBMIT,
            ComposerSubmit.enterAction(isShiftPressed = false, isRepeat = false),
        )
        assertEquals(
            "upstream editor.tsx:211 newlines a shifted Enter",
            ComposerSubmit.EnterAction.NEWLINE,
            ComposerSubmit.enterAction(isShiftPressed = true, isRepeat = false),
        )
        assertEquals(
            "a composing IME never submits (editor.tsx:211 `!isComposing`)",
            ComposerSubmit.EnterAction.NEWLINE,
            ComposerSubmit.enterAction(isShiftPressed = false, isRepeat = false, isComposing = true),
        )
        assertEquals(
            ComposerSubmit.EnterAction.IGNORE,
            ComposerSubmit.enterAction(isShiftPressed = false, isRepeat = true),
        )
    }

    // -------------------------------------------------------- source guards

    @Test
    fun theComposerWiresBothSubmitPathsAndTheStoppingRule() {
        val composer = source(COMPOSER)
        assertTrue("soft keyboard send action", composer.contains("KeyboardOptions(imeAction = ImeAction.Send)"))
        assertTrue("soft keyboard submit", composer.contains("KeyboardActions(onSend = { submit() })"))
        assertTrue("hardware Enter path", composer.contains("ComposerSubmit.enterAction("))
        assertTrue("repeated keys are filtered", composer.contains("repeatCount > 0"))
        assertTrue("send/stop decision", composer.contains("ComposerSubmit.stopping("))
        assertTrue(
            "blank input stops, otherwise it sends",
            composer.contains("if (stopping) onStop() else submit()"),
        )
        assertTrue("every other key falls through to the field", composer.contains("return@onPreviewKeyEvent false"))
    }

    /**
     * v57 P0: the soft keyboard has no newline key once the composer asks for
     * `ImeAction.Send`, so the IME half of upstream's `Shift+Enter` has to be
     * reachable — a long press on the send button.
     */
    @Test
    fun theSendButtonLongPressNewlinesForTheSoftKeyboard() {
        val composer = source(COMPOSER)
        assertTrue("send button long press", composer.contains("onLongClick = { if (!stopping) insertText(\"\\n\") }"))
        assertTrue(
            "the long press is announced",
            composer.contains("onLongClickLabel = longPressLabel"),
        )
    }

    /**
     * v57 P0: falling through is not "let the field newline" — Compose runs the
     * IME action for any Enter key-down that reaches the field, so a
     * `Shift+Enter` used to send. Both branches must be owned and consumed.
     */
    @Test
    fun everyEnterBranchIsOwnedAndConsumed() {
        val composer = source(COMPOSER)
        val enter = composer.substringAfter("if (event.key != Key.Enter && event.key != Key.NumPadEnter)")
            .take(ENTER_WINDOW)
        assertTrue("the newline branch types the break", enter.contains("insertText(\"\\n\")"))
        assertTrue("the submit branch submits", enter.contains("ComposerSubmit.EnterAction.SUBMIT -> submit()"))
        assertTrue("the repeat branch does nothing", enter.contains("ComposerSubmit.EnterAction.IGNORE -> Unit"))
        assertTrue(
            "and the key is consumed",
            enter.contains("ComposerSubmit.EnterAction.IGNORE -> Unit\n                        }\n                        true"),
        )
    }

    @Test
    fun aSendClearsTheEditor() {
        val composer = source(COMPOSER)
        // Bound by the next declaration, not by a fixed width: `submit()` is long
        // and its preamble grows with each round's comments, and a hand-counted
        // window silently stops covering the clear it is here to assert (v81:
        // the same pin broke on a longer comment, not on a behaviour change).
        val submit = composer.substringAfter("fun submit()")
            .substringBefore("\n    fun pasteFromClipboard")
        // v64: the clear is a write-back like any other, so it goes through the
        // caret guard rather than around it. v81: the guard is handed the value
        // being cleared, like every other write (an empty line has no head).
        assertTrue(
            "the submitted text is cleared — the panel closes through [live], not a mirror",
            submit.contains("value = ComposerImeCommit.adopt(TextFieldValue(\"\"), previous = value)"),
        )
        assertFalse("no mirrored trigger is cleared anymore", submit.contains("trigger = null"))
    }

    @Test
    fun theEmptySessionNeverShowsABlankScreen() {
        val screen = source(SCREEN)
        assertTrue(screen.contains("if (state.messages.isEmpty())"))
        assertTrue(screen.contains("R.string.oc_timeline_empty"))
    }

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("missing $relative")

    private companion object {
        const val COMPOSER = "src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/Composer.kt"
        const val SCREEN = "src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionScreen.kt"

        /** Window that covers the whole `Enter` key-down branch. */
        const val ENTER_WINDOW = 1500
    }
}
