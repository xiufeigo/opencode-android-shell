package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.composer.ComposerSubmit
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

    // -------------------------------------------------------- source guards

    @Test
    fun theComposerWiresBothSubmitPathsAndTheStoppingRule() {
        val composer = source(COMPOSER)
        assertTrue("soft keyboard send action", composer.contains("KeyboardOptions(imeAction = ImeAction.Send)"))
        assertTrue("soft keyboard submit", composer.contains("KeyboardActions(onSend = { submit() })"))
        assertTrue("hardware Enter path", composer.contains("ComposerSubmit.enterSubmits("))
        assertTrue("repeated keys are filtered", composer.contains("repeatCount > 0"))
        assertTrue("send/stop decision", composer.contains("ComposerSubmit.stopping("))
        assertTrue(
            "blank input stops, otherwise it sends",
            composer.contains("if (stopping) onStop() else submit()"),
        )
        assertTrue("Shift+Enter must reach the text field", composer.contains("return@onPreviewKeyEvent false"))
    }

    @Test
    fun aSendClearsTheEditor() {
        val composer = source(COMPOSER)
        val submit = composer.substringAfter("fun submit()").take(SUBMIT_WINDOW)
        assertTrue("the submitted text is cleared", submit.contains("value = TextFieldValue(\"\")"))
        assertTrue("the mention popover closes", submit.contains("trigger = null"))
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

        /** Window that covers the whole `submit()` body. */
        const val SUBMIT_WINDOW = 400
    }
}
