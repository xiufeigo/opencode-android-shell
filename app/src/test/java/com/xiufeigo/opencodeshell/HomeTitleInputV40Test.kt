package com.xiufeigo.opencodeshell

import androidx.compose.ui.text.TextRange
import com.xiufeigo.opencodeshell.ui.home.homeRenameEntrySelection
import com.xiufeigo.opencodeshell.ui.nav.tabRenameEntrySelection
import com.xiufeigo.opencodeshell.ui.session.header.InlineTitleEdit
import com.xiufeigo.opencodeshell.ui.session.composer.HalfWidthText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v40's Home/title-input batch — the three device bugs the v40 real-device run
 * produced (`research/v40_verify/tC/`, `tA/47_settled.png`).
 *
 * **BUG-1 (serious) — the Home row rename editor's caret.** Typing produced
 * reversed characters and DEL / Ctrl+A did nothing. The field was handed a fresh
 * `TextFieldValue(editor.draft)` on *every* recomposition
 * (`HomeSessionsList.kt`), so its selection was re-seeded with `TextRange.Zero`
 * after each keystroke: the caret jumped back in front of the draft
 * (`tC/B2_rename_reverse.png`). The fix is the pattern v32/v33 already used in
 * the header and the tab strip — a `remember`ed `TextFieldValue` carrying the
 * whole-title entry selection, with the draft still echoed to the host.
 *
 * **BUG-2 — full-width punctuation in title fields.** `OcKeyboard.literal` only
 * sets `autoCorrectEnabled = false`; a Chinese IME still sends `：`/`／`/`＠`
 * (`tC/h4_typed.png`). The composer's W5-F fold (`HalfWidthText.normalize`) is
 * the app's input boundary for exactly this, and it is 1:1 so the selection and
 * the composing region survive. Four title fields are wired to it.
 *
 * **BUG-3 — the new-project empty state.** The "New session" button under
 * 「Nothing here yet」 was clipped to a ~14 px sliver
 * (`tA/47_settled.png`, `tA/46_confirm2.png`): the block was laid out in a
 * `fillMaxSize()` Column, so when the list viewport was a few dp short of the
 * block's 140 dp the *last* child was cut off.
 *
 * The pure halves are pinned as behaviour; the `@Composable` wiring is pinned at
 * the source level, the same split `TabRenameFieldSelectionTest` uses.
 */
class HomeTitleInputV40Test {

    private fun source(relative: String): String {
        val file = File(relative).takeIf { it.isFile } ?: File("app/$relative")
        return file.readText()
    }

    private fun window(src: String, from: String, to: String): String {
        val start = src.indexOf(from)
        assertTrue("missing window start: $from", start >= 0)
        val end = src.indexOf(to, start + from.length)
        assertTrue("missing window end: $to", end > start)
        return src.substring(start, end)
    }

    private val homeList = source("src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeSessionsList.kt")
    private val header = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/header/SessionHeader.kt")
    private val dialogs = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/drawer/SessionRowDialogs.kt")
    private val strip = source("src/main/java/com/xiufeigo/opencodeshell/ui/nav/BottomTabStrip.kt")

    private fun homeRenameEditor() = window(homeList, "private fun HomeSessionRenameEditor(", "internal fun homeRenameEntrySelection(")

    // --- BUG-1: the entry selection is the whole title, in all three editors ------

    @Test
    fun theHomeRenameEditorSelectsTheWholeTitle() {
        assertEquals(TextRange(0, 8), homeRenameEntrySelection("Untitled"))
        assertEquals(TextRange(0, 0), homeRenameEntrySelection(""))
    }

    @Test
    fun everyTitleEditorOpensWithTheSameSelection() {
        // One contract, three editors: the Home row, the tab strip and the header
        // cannot drift apart into two different caret behaviours.
        for (title in listOf("Untitled", "", "修复：会话", "a very long session title")) {
            val home = homeRenameEntrySelection(title)
            assertEquals(home, tabRenameEntrySelection(title))
            assertEquals(home, InlineTitleEdit(title).entrySelection)
        }
    }

    // --- BUG-1: the editor carries the value, not a rebuilt one ------------------

    @Test
    fun theHomeRenameEditorHoldsArememberedTextFieldValue() {
        val editor = homeRenameEditor()
        // The state is remembered per session (one editor per open row) and seeded
        // with the entry selection — a rebuilt `TextFieldValue(editor.draft)` is
        // exactly the BUG-1 defect.
        assertTrue(
            editor.contains("remember(editor.sessionId) {"),
        )
        assertTrue(editor.contains("mutableStateOf(TextFieldValue(draft,"))
        assertTrue(editor.contains("homeRenameEntrySelection(draft)"))
        assertTrue(editor.contains("value = value,"))
        assertFalse(editor.contains("value = TextFieldValue(editor.draft)"))
    }

    @Test
    fun theHomeRenameEditorStillEchoesEveryEditToTheHostDraft() {
        val editor = homeRenameEditor()
        // Enter saves the host's draft (`HomeRowMenu.submit`), so the field must
        // not swallow the text.
        assertTrue(editor.contains("onDraft(folded.text)"))
        assertTrue(editor.contains("event.key == Key.Enter -> {"))
        assertTrue(editor.contains("event.key == Key.Escape -> {"))
    }

    // --- BUG-2: every title field folds full-width punctuation at the boundary ---

    @Test
    fun theNameFoldRewritesTheWholeFullWidthBlock() {
        // A Chinese IME's colon folds to ASCII — the v40 report's exact symptom —
        // and a half-width draft comes back reference-equal (no extra
        // recomposition). The composer's narrower `normalize` rule leaves `：`
        // alone on purpose (it is a prose field); a name is not prose.
        val half = "abc"
        assertTrue(half === HalfWidthText.normalizeName(half))
        assertEquals("fix:test", HalfWidthText.normalizeName("fix：test"))
        assertEquals("a/b@c d", HalfWidthText.normalizeName("a／b＠c　d"))
        assertEquals("a,b", HalfWidthText.normalizeName("a，b"))
        // The `TextFieldValue` overload is 1:1, so the selection survives the fold.
        val value = androidx.compose.ui.text.input.TextFieldValue(
            text = "a／b",
            selection = TextRange(3),
        )
        val folded = HalfWidthText.normalizeName(value)
        assertEquals("a/b", folded.text)
        assertEquals(TextRange(3), folded.selection)
    }

    @Test
    fun theComposerRuleIsStillTheNarrowOne() {
        // The two rules stay deliberately different: the composer rewrites only
        // the characters it reads as syntax.
        assertEquals("，", HalfWidthText.normalize("，"))
        assertEquals("！", HalfWidthText.normalize("！"))
        assertEquals("：", HalfWidthText.normalize("："))
    }

    @Test
    fun theHomeRenameEditorFoldsWhatTheImeSends() {
        val editor = homeRenameEditor()
        assertTrue(homeList.contains("import com.xiufeigo.opencodeshell.ui.session.composer.HalfWidthText"))
        assertTrue(editor.contains("HalfWidthText.normalizeName(it)"))
        assertTrue(editor.contains("value = folded"))
    }

    @Test
    fun theOtherThreeTitleFieldsFoldToo() {
        // The tab strip turned out to be on `OcKeyboard.literal`, not the
        // ASCII-tier option, so it was exposed to the same rewrite.
        assertTrue(strip.contains("val folded = HalfWidthText.normalizeName(it)"))
        assertTrue(strip.contains("onValueChange(folded.text)"))
        assertTrue(header.contains("onValueChange = { value = HalfWidthText.normalizeName(it) }"))
        assertTrue(dialogs.contains("onValueChange = { value = HalfWidthText.normalizeName(it) }"))
    }

    // --- BUG-3: the empty state's button is never clipped ------------------------

    @Test
    fun theEmptyStateMeasuresItsBlockInsideAScrollingViewport() {
        val empty = window(homeList, "private fun HomeSessionsEmpty(", "internal fun NeutralButton(")
        // The block keeps its intrinsic height (scroll → unbounded constraints),
        // so the last child — the button — is never clipped by the viewport.
        assertTrue(empty.contains(".verticalScroll(rememberScrollState())"))
        assertFalse(empty.contains("modifier.padding(top = 52.dp"))
        // The button itself is unchanged: the neutral `ButtonV2` of upstream's
        // `HomeSessionsEmpty`.
        assertTrue(empty.contains("NeutralButton("))
        assertTrue(empty.contains("R.string.oc_new_session"))
        assertTrue(empty.contains("padding(top = 52.dp, start = 24.dp, end = 24.dp)"))
    }
}
