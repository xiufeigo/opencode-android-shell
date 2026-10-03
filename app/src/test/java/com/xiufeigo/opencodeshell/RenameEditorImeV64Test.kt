package com.xiufeigo.opencodeshell

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.xiufeigo.opencodeshell.ui.home.HomeRenameCommitGate
import com.xiufeigo.opencodeshell.ui.home.HomeRowMenu
import com.xiufeigo.opencodeshell.ui.home.homeRenameEntryReassert
import com.xiufeigo.opencodeshell.ui.home.homeRenameEntrySelection
import com.xiufeigo.opencodeshell.ui.session.composer.ComposerImeCommit
import com.xiufeigo.opencodeshell.ui.session.composer.ComposerSuggestions
import com.xiufeigo.opencodeshell.ui.session.composer.MentionTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v64 BUG-3 「重命名框 IME ✓ 删字 + 丢字」, plus the caret half of v64 BUG-1/2
 * that the Home editor shares.
 *
 * Measured on `emulator-5558` (`research/v64_verify/5558/10c_rename_typed.png` →
 * `10d` → `10e` → `10f_rename_enter.png`, and the E2E check
 * `E2ECHK5558-cursor` which read `urso` for `cursor`): the row's inline rename
 * editor took a segmented IME commit and lost a character, and the IME's ✓
 * deleted one instead of saving.
 *
 * Two independent causes, both pinned below:
 *
 * 1. **The entry-selection re-assert was unbounded** ([homeRenameEntryReassert]).
 *    It selects whatever the field holds when it runs, so a field that had
 *    already received the first segment of the draft had *that* selected, and
 *    the next segment replaced it — `cursor` → `urso`.
 * 2. **One gesture reached the field twice** ([HomeRenameCommitGate]). The IME's
 *    ✓ arrives as `KeyboardActions.onDone` *and*, from a hardware keyboard, as a
 *    `Key.Enter` the preview handler also commits on; and closing the editor
 *    detaches the field, which force-clears focus and delivers a trailing blur
 *    after the commit — or after the cancel, which is what turns an escape into
 *    a save.
 *
 * The caret guard is the same one the composer folds every write through
 * ([ComposerImeCommit.adopt]): a value whose `selection` / `composition`
 * reaches past its own text parks the caret at 0 and turns DEL and
 * select-all+delete into no-ops.
 */
class RenameEditorImeV64Test {

    // ------------------------------------------------- the entry re-assert (1)

    /** The v43 case: an untouched draft re-asserts the whole-title selection. */
    @Test
    fun anUntouchedDraftStillSelectsTheWholeTitle() {
        val opened = TextFieldValue("Untitled", homeRenameEntrySelection("Untitled"))
        // The IME connection restored Android's caret at the end (the `abcabc`
        // bug, `research/v43_verify/tC/v43_C4/C5`).
        val afterIme = opened.copy(selection = TextRange(8))
        assertEquals(TextRange(0, 8), homeRenameEntryReassert(afterIme, "Untitled").selection)
    }

    /** v64: once the user has typed, the re-assert must not touch the caret. */
    @Test
    fun aReassertAfterTheFirstSegmentIsRefused() {
        val typed = TextFieldValue("c", TextRange(1))
        val same = homeRenameEntryReassert(typed, "Untitled")
        assertSame("an edited draft is left exactly as the IME left it", typed, same)
        assertEquals(TextRange(1), same.selection)
    }

    /** A live composing region is the same hazard one step earlier. */
    @Test
    fun aReassertDuringCompositionIsRefused() {
        val composing = TextFieldValue("Untitled", TextRange(8), TextRange(0, 8))
        assertSame(composing, homeRenameEntryReassert(composing, "Untitled"))
    }

    /** The whole `cursor` → `urso` shape, step by step. */
    @Test
    fun aSegmentedDraftKeepsEverySegment() {
        // The draft as the editor opened with it — fixed for the life of the
        // field, which is what bounds the re-assert.
        val entry = "cursor"
        var value = TextFieldValue(entry, homeRenameEntrySelection(entry))
        val segments = listOf("c", "u", "r", "s", "o", "r")
        for (segment in segments) {
            // The field takes focus, and the IME hands the app a value whose
            // selection is the *app's* (still the whole title) — the re-assert is
            // asked for on every focus gain.
            value = homeRenameEntryReassert(value, entry)
            if (value.text == entry) {
                // First keystroke replaces the title; the rest append.
                value = value.copy(text = segment, selection = TextRange(1))
            } else {
                value = value.copy(
                    text = value.text.substring(0, value.selection.end) + segment +
                        value.text.substring(value.selection.end),
                    selection = TextRange(value.selection.end + 1),
                )
            }
        }
        assertEquals("cursor", value.text)
        assertEquals(TextRange(6), value.selection)
    }

    // ------------------------------------------------------- the gate (2)

    /** The IME's ✓ submits — once. */
    @Test
    fun theImeDoneSubmitsExactlyOnce() {
        val gate = HomeRenameCommitGate()
        assertTrue(gate.commit(HomeRowMenu.CommitTrigger.IME_DONE, saving = false, focused = true))
        assertFalse("the trailing key event of the same gesture", gate.commit(HomeRowMenu.CommitTrigger.ENTER, saving = false, focused = true))
        assertFalse("…and the blur that closing the field produces", gate.commit(HomeRowMenu.CommitTrigger.BLUR, saving = false, focused = true))
        assertTrue(gate.settled)
    }

    /** A hardware Enter behaves identically. */
    @Test
    fun aHardwareEnterSubmitsExactlyOnce() {
        val gate = HomeRenameCommitGate()
        assertTrue(gate.commit(HomeRowMenu.CommitTrigger.ENTER, saving = false, focused = true))
        assertFalse(gate.commit(HomeRowMenu.CommitTrigger.IME_DONE, saving = false, focused = true))
    }

    /** Escape never writes — the blur it causes is swallowed. */
    @Test
    fun anEscapeIsNeverOvertakenByItsOwnBlur() {
        val gate = HomeRenameCommitGate()
        assertTrue(gate.cancel())
        assertFalse(gate.commit(HomeRowMenu.CommitTrigger.BLUR, saving = false, focused = true))
        assertFalse(gate.commit(HomeRowMenu.CommitTrigger.IME_DONE, saving = false, focused = true))
    }

    /** A trigger that may not commit does not settle the editor. */
    @Test
    fun aTriggerThatMayNotCommitLeavesTheEditorOpen() {
        val gate = HomeRenameCommitGate()
        assertFalse("a blur from a field that never held focus", gate.commit(HomeRowMenu.CommitTrigger.BLUR, saving = false, focused = false))
        assertFalse(gate.settled)
        assertFalse("a commit while a PATCH is in flight", gate.commit(HomeRowMenu.CommitTrigger.IME_DONE, saving = true, focused = true))
        assertFalse(gate.settled)
        assertTrue(gate.commit(HomeRowMenu.CommitTrigger.IME_DONE, saving = false, focused = true))
    }

    // ------------------------------------------------------- the caret guard

    /** The rename field's write path is the composer's guard, not a bare fold. */
    @Test
    fun theRenameFieldAdoptsEveryWrite() {
        val home = source("ui/home/HomeSessionsList.kt")
        assertTrue(
            "the field folds and then adopts",
            home.contains("ComposerImeCommit.adopt(HalfWidthText.normalizeName(it))"),
        )
        // A caret past the end of the text can never reach the IME: that is the
        // state that makes DEL and select-all+delete no-ops.
        val adopted = ComposerImeCommit.adopt(TextFieldValue("cu", TextRange(9)))
        assertEquals(TextRange(2), adopted.selection)
    }

    // ---------------------------------------- the composer's mention chips (1/2)

    /**
     * v64 BUG-1: `@de` with a full-width `＠` and a live mention anchor. The
     * caret the IME reported is the caret the editor keeps, so a backspace at it
     * deletes — the guard never folds a legal caret to 0, and the token list is
     * re-derived from the result.
     */
    @Test
    fun aMentionDraftKeepsTheCaretsOwnOffsets() {
        val token = MentionTokens.Token(
            kind = ComposerSuggestions.Category.FILE,
            label = "main.kt",
            path = "app/src/main/kotlin/main.kt",
        )
        var value = ComposerImeCommit.adopt(TextFieldValue("＠main.kt", TextRange(8)))
        var tokens = listOf(token)
        for (expected in 7 downTo 0) {
            val caret = value.selection.end
            value = ComposerImeCommit.adopt(
                value.copy(
                    text = value.text.removeRange(caret - 1, caret),
                    selection = TextRange(caret - 1),
                ),
            )
            tokens = MentionTokens.retokenize(value.text, tokens)
            assertEquals("backspace $expected parks nowhere", TextRange(expected), value.selection)
        }
        assertEquals("", value.text)
        assertTrue("the chip went with it", tokens.isEmpty())
    }

    // ------------------------------------------------------------- the wiring

    private fun source(relative: String): String =
        listOf(File("src/main/java/com/xiufeigo/opencodeshell/$relative"), File("app/src/main/java/com/xiufeigo/opencodeshell/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("missing $relative")

    @Test
    fun theEditorIsWiredToTheGateAndTheGuardedReassert() {
        val home = source("ui/home/HomeSessionsList.kt")
        assertTrue(home.contains("val gate = remember(editor.sessionId) { HomeRenameCommitGate() }"))
        assertTrue(home.contains("if (gate.commit(trigger, editor.saving, focused)) onSubmit()"))
        assertTrue(home.contains("if (gate.cancel()) onCancel()"))
        assertTrue(home.contains("value = homeRenameEntryReassert(value, entryText)"))
        assertFalse(
            "the unbounded select-all latch is gone",
            home.contains("selectAllOnFocus"),
        )
        assertTrue(
            "Escape goes through the gate too",
            home.contains("event.key == Key.Escape -> {\n                            cancel()"),
        )
    }
}
