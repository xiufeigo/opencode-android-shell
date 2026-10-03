package com.xiufeigo.opencodeshell

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.xiufeigo.opencodeshell.ui.home.homeRenameEntryRestore
import com.xiufeigo.opencodeshell.ui.home.homeRenameEntrySelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v77 P0 「Home 内联重命名永久失灵」 — the row editor's **entry sequence**.
 *
 * Measured on `emulator-5554` (`research/v73_verify/5554/`): the first rename of a
 * run works — `34_rename` shows the field holding the whole title, selected, with
 * the keyboard up, and `35_renamed` shows a typed draft — and every open after it
 * comes up as an empty grey bar with no text and no caret (`36_rename2`,
 * `38_ren4`; pixel-identical to the screenshot taken after tapping keys,
 * `39_tapkey`). The rename then never lands: the tab keeps 「Untitled」.
 *
 * Two independent halves of the same entry sequence, both pinned below:
 *
 * 1. **The field is seeded once and then only written by the IME**
 *    (`remember(editor.sessionId)`), and every write is echoed to the host
 *    (`onDraft`). The IME re-initialises its editor against the entry selection
 *    whenever the app re-attaches it — `requestFocus()` + `keyboard.show()` is a
 *    fresh `restartInput`, the mechanism v66 named — and commits its stale
 *    composing region over that selection. With nothing in that region the whole
 *    title goes, and the host's draft is wiped with it: `HomeRowMenu.submit`
 *    reads a blank draft, answers `Noop`, and the editor closes without a
 *    `PATCH`. [homeRenameEntryRestore] is the pure half — an empty write before
 *    the user's first character is never an edit, so the field takes the entry
 *    value back, whole-title selected, and the seed itself falls back to the
 *    row's own title.
 * 2. **The focus ask was a one-shot budget over two frames.** A row the host
 *    places later spends it before the field has a focus node, and the field is
 *    then left with the draft and no `InputConnection`: typing, Enter, the IME's
 *    ✓ and Esc all have nowhere to land, and a phone has no other way out. The
 *    asks are now spread over time ([FOCUS_LATE_RETRIES]) and issued at most once
 *    per attempt, so neither a layout pass nor a spent frame can leave the editor
 *    unfocusable.
 *
 * The guards this fix must not break are pinned at the end: v66's "never re-ask a
 * field that holds focus" (a repeat ask re-attaches the IME and drops the
 * character in flight), v69's "a failed ask does not spend the budget", v63's
 * one-shot commit latch and v64's bounded re-assert.
 */
class HomeRenameEntryV77Test {

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()?.replace("\r\n", "\n")
            ?: error("missing $relative")

    private val homeList = source("src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeSessionsList.kt")

    /** The editor composable and the helpers it owns (the v40/v43 window shape). */
    private val editor = homeList
        .substring(homeList.indexOf("private fun HomeSessionRenameEditor("))
        .substringBefore("internal fun homeRenameEntrySelection(")

    // ------------------------------------------- the blanked-field repair (pure)

    /** The measured state: an empty box, nothing typed — the title comes back. */
    @Test
    fun aBlankedUntouchedFieldTakesTheTitleBack() {
        val restored = homeRenameEntryRestore(
            current = TextFieldValue("", TextRange(0)),
            title = "Untitled",
            touched = false,
        )
        assertEquals("Untitled", restored.text)
        // …whole-title selected, exactly as the editor opened it, so the first
        // keystroke replaces the old name instead of splicing into it.
        assertEquals(homeRenameEntrySelection("Untitled"), restored.selection)
        assertEquals(TextRange(0, 8), restored.selection)
    }

    /**
     * The repair is bounded to *before* the user's first character: a draft the
     * user is typing (or has deliberately emptied after typing) is left exactly
     * as the IME left it — reference-preserving, so the effect that calls this
     * schedules no recomposition.
     */
    @Test
    fun theRepairNeverFightsTheUser() {
        val typed = TextFieldValue("ol", TextRange(2))
        assertSame(typed, homeRenameEntryRestore(typed, "Untitled", touched = false))

        val deleted = TextFieldValue("", TextRange(0))
        assertSame(
            "a delete after the first character is the user's own",
            deleted,
            homeRenameEntryRestore(deleted, "Untitled", touched = true),
        )
    }

    /** A row with no title keeps its blank field rather than inventing one. */
    @Test
    fun aRowWithoutATitleKeepsItsBlankField() {
        val blank = TextFieldValue("", TextRange(0))
        assertSame(blank, homeRenameEntryRestore(blank, "", touched = false))
    }

    /** An untouched field that still holds its title is not touched at all. */
    @Test
    fun anIntactFieldIsLeftAlone() {
        val seeded = TextFieldValue("Untitled", homeRenameEntrySelection("Untitled"))
        assertSame(seeded, homeRenameEntryRestore(seeded, "Untitled", touched = false))
    }

    /** The rule is idempotent, so the effect that runs it can never loop. */
    @Test
    fun theRepairSettlesAfterOnePass() {
        val once = homeRenameEntryRestore(TextFieldValue("", TextRange(0)), "E2ECHK v77", touched = false)
        assertSame(once, homeRenameEntryRestore(once, "E2ECHK v77", touched = false))
    }

    // ------------------------------------------------------------- the wiring

    /** The seed never opens a blank box while the row it replaced has a name. */
    @Test
    fun theSeedFallsBackToTheRowsOwnTitle() {
        assertTrue(
            "a blank draft is the IME's doing, never a title this shell has",
            editor.contains("HalfWidthText.normalizeName(editor.draft.ifBlank { row.title })"),
        )
        // v40 BUG-1's shape is untouched: one remembered value, whole-title entry.
        assertTrue(editor.contains("mutableStateOf(TextFieldValue(draft,"))
        assertTrue(editor.contains("homeRenameEntrySelection(draft)"))
    }

    /** The repair hangs off both entry moments: the focus gain and the write. */
    @Test
    fun theRepairRunsOnFocusAndOnTheWriteItself() {
        assertTrue(
            editor,
            editor.contains(
                "value = homeRenameEntryReassert(value, entryText)\n            restoreEntryValue()",
            ),
        )
        assertTrue(
            editor,
            editor.contains("LaunchedEffect(value.text, touched) {\n        restoreEntryValue()\n    }"),
        )
        assertTrue(editor.contains("val restored = homeRenameEntryRestore(value, entryText, touched)"))
        // …and the host's draft is put back with it, or `submit` would still read
        // the wiped draft and answer `Noop` over a field that shows the title.
        assertTrue(editor.contains("onDraft(restored.text)"))
        // One repair per editor: the flag it arms is the same one the user's own
        // first character arms, so a deliberate DEL is never fought twice over.
        assertTrue(editor.contains("value = restored\n            touched = true"))
    }

    /** Only a write that carries a character arms the repair's `touched` guard. */
    @Test
    fun anEmptyWriteDoesNotCountAsTheUserTyping() {
        assertTrue(editor.contains("if (folded.text.isNotEmpty()) touched = true"))
        // The caret guard and the host echo stay where they were (v64 BUG-3).
        assertTrue(editor.contains("ComposerImeCommit.adopt(HalfWidthText.normalizeName(it))"))
        assertTrue(editor.contains("onDraft(folded.text)"))
    }

    /** The asks outlive a spent frame and a layout pass. */
    @Test
    fun theFocusAsksAreSpreadOverTimeAndIssuedOncePerAttempt() {
        assertTrue("one ask per attempt", editor.contains("if (askIssued) return"))
        assertTrue(editor.contains("var askIssued by remember(editor.sessionId) { mutableStateOf(false) }"))
        assertTrue(editor.contains("repeat(FOCUS_LATE_RETRIES) {"))
        assertTrue(editor.contains("delay(FOCUS_LATE_RETRY_MS)"))
        assertTrue(homeList.contains("private const val FOCUS_LATE_RETRIES = 4"))
        assertTrue(homeList.contains("private const val FOCUS_LATE_RETRY_MS = 120L"))
        assertTrue(homeList.contains("import kotlinx.coroutines.delay"))
        // The attempt ends when focus lands (or is lost), so a later one may ask.
        assertTrue(
            editor,
            editor.contains("                        askIssued = false\n                        focused = true"),
        )
        assertTrue(
            editor,
            editor.contains("focused = false\n                        // v77: a field that lost focus can be asked for it again"),
        )
        // The placement retry is still the guarded one (v66), and the two
        // settled-frame asks are still there (v45/v69).
        assertTrue(editor.contains(".onGloballyPositioned { if (!focused) askForFocus() }"))
        assertTrue(editor.contains("withFrameNanos { }\n        askForFocus()"))
    }

    // -------------------------------------------------- the guards kept intact

    @Test
    fun theGuardsThisFixMustNotBreakAreStillWired() {
        // v66 BUG-4: a repeat ask once the field holds focus re-attaches the IME
        // and drops the character in flight — the ask stops on `focused`.
        assertTrue(editor.contains("if (focused) return"))
        assertFalse(editor.contains(".onGloballyPositioned { askForFocus() }"))
        // v69 C3: only an ask that reached a focus node spends a try, and the
        // budget is unchanged.
        assertTrue(editor.contains("if (focusTries >= FOCUS_TRIES) return"))
        assertTrue(editor.contains("if (runCatching { focusRequester.requestFocus() }.isSuccess) {"))
        assertTrue(editor.contains("keyboard?.show()"))
        assertTrue(homeList.contains("private const val FOCUS_TRIES = 3"))
        // v63/v64: the one-shot commit latch and the bounded entry re-assert.
        assertTrue(editor.contains("val gate = remember(editor.sessionId) { HomeRenameCommitGate() }"))
        assertTrue(editor.contains("if (gate.commit(trigger, editor.saving, focused)) onSubmit()"))
        assertTrue(editor.contains("value = homeRenameEntryReassert(value, entryText)"))
        // The commit chain itself is untouched.
        assertTrue(editor.contains("commit(HomeRowMenu.CommitTrigger.IME_DONE)"))
        assertTrue(editor.contains("commit(HomeRowMenu.CommitTrigger.ENTER)"))
        assertTrue(editor.contains("commit(HomeRowMenu.CommitTrigger.BLUR)"))
    }
}
