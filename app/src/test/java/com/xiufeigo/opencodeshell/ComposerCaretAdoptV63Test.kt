package com.xiufeigo.opencodeshell

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.xiufeigo.opencodeshell.ui.session.composer.ComposerImeCommit
import com.xiufeigo.opencodeshell.ui.session.composer.MentionText
import com.xiufeigo.opencodeshell.ui.session.composer.MentionTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v63 BUG-1 「composer 无法删除任何字符」.
 *
 * Measured on `emulator-5558` (`research/v63_verify/5558/25-29`, reproduced
 * live): once the composer's editor value carried an offset the IME did not own,
 * the caret parked at 0 and stayed there — tapping mid-text inserted at the
 * start, and `KEYCODE_DEL` / `KEYCODE_MOVE_END` / the IME's own ⌫ were all
 * no-ops, i.e. the "locked text". logcat names the mechanism:
 * `ImeTracker … onRequestShow at ORIGIN_SERVER reason ATTACH_NEW_INPUT`
 * (the app-side `restartInput()`) followed by
 * `GoogleInputMethodService.onStartInput … initialSelStart=0`.
 *
 * The composer now folds every editor write through [ComposerImeCommit.adopt],
 * which makes the value legal again before it can reach the IME.
 */
class ComposerCaretAdoptV63Test {

    /** A plain keystroke's value is already legal and comes back untouched. */
    @Test
    fun aLegalValueIsReturnedAsTheSameInstance() {
        val value = TextFieldValue("hello", TextRange(5))
        assertSame(value, ComposerImeCommit.adopt(value))
    }

    /** The reported shape: a caret past the end of the text. */
    @Test
    fun aSelectionPastTheEndIsFoldedBack() {
        val adopted = ComposerImeCommit.adopt(TextFieldValue("hi", TextRange(9)))
        assertEquals("hi", adopted.text)
        assertEquals(TextRange(2), adopted.selection)
    }

    /** The same for a range whose ends are reversed by the fold. */
    @Test
    fun aReversedSelectionIsOrderedBack() {
        val adopted = ComposerImeCommit.adopt(TextFieldValue("abcd", TextRange(9, 1)))
        assertEquals(TextRange(1, 4), adopted.selection)
    }

    /** Deleting down to empty must not leave the caret behind the empty text. */
    @Test
    fun deletingToEmptyParksTheCaretAtZero() {
        val adopted = ComposerImeCommit.adopt(TextFieldValue("", TextRange(3)))
        assertEquals(TextRange(0), adopted.selection)
    }

    /** A composing region past the end is folded the same way. */
    @Test
    fun aCompositionPastTheEndIsFoldedBack() {
        val adopted = ComposerImeCommit.adopt(
            TextFieldValue("XYc", TextRange(3), TextRange(1, 12)),
        )
        assertEquals(TextRange(1, 3), adopted.composition)
    }

    /** An emptied composing region is dropped rather than kept as a phantom. */
    @Test
    fun anEmptiedCompositionIsDropped() {
        val adopted = ComposerImeCommit.adopt(
            TextFieldValue("XYc", TextRange(3), TextRange(0, 0)),
        )
        assertNull(adopted.composition)
    }

    /** The mention text that carried the bug keeps its caret through the guard. */
    @Test
    fun aMentionTextKeepsItsCaret() {
        val value = TextFieldValue("XYc@", TextRange(4))
        val adopted = ComposerImeCommit.adopt(value)
        assertEquals("XYc@", adopted.text)
        assertEquals(TextRange(4), adopted.selection)
    }

    /** Backspacing into a mention is the value the guard has to keep legal. */
    @Test
    fun backspacingOutOfAMentionStaysDeletable() {
        var value = TextFieldValue("XYc@", TextRange(4))
        // Delete the `@`: the caret lands at 3, still inside the text.
        value = ComposerImeCommit.adopt(
            value.copy(text = "XYc", selection = TextRange(3)),
        )
        assertEquals(TextRange(3), value.selection)
        // Delete a mention token's characters one at a time; the caret always
        // tracks the deletion instead of falling behind the text.
        for (expected in 2 downTo 0) {
            value = ComposerImeCommit.adopt(
                value.copy(
                    text = value.text.dropLast(1),
                    selection = TextRange(value.text.length - 1),
                ),
            )
            assertEquals(TextRange(expected), value.selection)
        }
        assertEquals("", value.text)
        // And the tokens recorded for the deleted mention go with it.
        val token = MentionTokens.Token(
            kind = com.xiufeigo.opencodeshell.ui.session.composer.ComposerSuggestions.Category.FILE,
            label = "main.kt",
            path = "app/src/main/kotlin/main.kt",
        )
        assertTrue(MentionTokens.retokenize(value.text, listOf(token)).isEmpty())
    }

    /** The `@` anchor survives the guard, so the popover still opens. */
    @Test
    fun theMentionAnchorSurvivesTheGuard() {
        val adopted = ComposerImeCommit.adopt(TextFieldValue("XYc@ma", TextRange(9)))
        assertEquals(MentionText.Anchor(3, "ma"), MentionText.anchorAt(adopted.text, 6))
    }

    /** The wiring: every editor write goes through the guard. */
    @Test
    fun everyComposerWriteGoesThroughTheGuard() {
        val composer = listOf(
            File("src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/Composer.kt"),
            File("app/src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/Composer.kt"),
        ).firstOrNull { it.isFile }?.readText() ?: error("missing Composer.kt")

        assertTrue(
            "setText must adopt before it writes the editor state",
            composer.contains("val adopted = ComposerImeCommit.adopt(next, previous = value)"),
        )
        val setText = composer.substringAfter("fun setText(next: TextFieldValue) {")
            .substringBefore("fun editQueued")
        // v81: the guard is handed the value the app was holding, so an IME
        // re-attach batch cannot park the caret in front of the line
        // (`ComposerImeRestartCaretV81Test`). The v63 contract is unchanged.
        assertTrue(setText.contains("ComposerImeCommit.adopt(next, previous = value)"))
        // v68: the soft-keyboard path may widen an in-chip deletion to the whole
        // chip (`MentionTokens.snapImeDeletion`) before storing; the snapped
        // value re-enters `adopt` so the caret contract is never weakened.
        assertTrue(setText.contains("value = snapped"))
        assertTrue(setText.contains("ComposerDrafts.set(draftKey, snapped.text)"))
        assertTrue(setText.contains("MentionTokens.retokenize(snapped.text, tokens)"))
        assertTrue(
            "the snapped value re-enters the guard",
            setText.split("ComposerImeCommit.adopt(").size - 1 >= 2,
        )
        assertFalse("setText must not store the raw value", setText.contains("value = next"))
    }

    /**
     * The `+` menu is a `focusable` Popup: it takes the editor's window focus
     * and its IME, and never gives them back. On `emulator-5558` the composer
     * was left with no caret, no keyboard and an inert send button after the
     * menu closed — the editor looked alive but took nothing, which is the
     * "锁死" a tester reports as "can't delete".
     */
    @Test
    fun theAddMenuHandsTheEditorsFocusBack() {
        val composer = listOf(
            File("src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/Composer.kt"),
            File("app/src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/Composer.kt"),
        ).firstOrNull { it.isFile }?.readText() ?: error("missing Composer.kt")

        assertTrue(
            "the menu must declare the hand-back",
            composer.contains("onRestoreFocus: () -> Unit,"),
        )
        assertTrue(
            "the call site must return the editor's focus",
            composer.contains("onRestoreFocus = { runCatching { focusRequester.requestFocus() } }"),
        )
        val menu = composer.substringAfter("private fun AddMenuButton(")
        assertTrue(
            "the hand-back must run on close, not on first composition",
            menu.contains("else if (openedOnce) {"),
        )
    }
}
