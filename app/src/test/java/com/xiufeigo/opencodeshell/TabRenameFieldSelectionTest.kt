package com.xiufeigo.opencodeshell

import androidx.compose.ui.text.TextRange
import com.xiufeigo.opencodeshell.ui.nav.tabRenameEntrySelection
import com.xiufeigo.opencodeshell.ui.session.header.InlineTitleEdit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v33 BUG-2: the tab rename editor must open with the **whole** old title
 * selected, so the first keystroke replaces it instead of splicing into it.
 *
 * Device report: entering the rename field left the cursor at offset 0 in front
 * of the old name. The cause is the value type, not the focus request —
 * `BasicTextField`'s String overload seeds its internal state with
 * `TextFieldValue(text = value)` (`foundation 1.7.6` `BasicTextField.kt:614`),
 * whose selection is `TextRange.Zero`; only the `TextFieldValue` overload can
 * carry a selection. Upstream selects the title on entry (`tab-nav.tsx:152-164`
 * `selectTitle()`, called from the `tab-nav.tsx:64-67` rename handler), and the
 * header's editor already does the same since v32 (`InlineTitleEdit`,
 * `SessionTitleInlineEditTest`).
 *
 * The decision is pure ([tabRenameEntrySelection]) and is pinned as behaviour;
 * the field and its call sites live inside `@Composable` functions, which a
 * plain JVM test cannot call, so that half stays a source-level pin — the same
 * split `SessionTitleInlineEditTest` uses.
 */
class TabRenameFieldSelectionTest {

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

    private fun strip() = source("src/main/java/com/xiufeigo/opencodeshell/ui/nav/BottomTabStrip.kt")
    private fun desktopStrip() = source("src/main/java/com/xiufeigo/opencodeshell/ui/nav/DesktopTabStrip.kt")
    private fun drawer() = source("src/main/java/com/xiufeigo/opencodeshell/ui/nav/MobileTabsDrawer.kt")
    private fun terminal() = source("src/main/java/com/xiufeigo/opencodeshell/ui/terminal/TerminalPanel.kt")

    // --- behaviour: the entry selection is the whole title --------------------------

    @Test
    fun enteringTheRenameFieldSelectsTheWholeTitle() {
        assertEquals(TextRange(0, 8), tabRenameEntrySelection("Untitled"))
        // Nothing to select in an empty title, and the range stays valid.
        assertEquals(TextRange(0, 0), tabRenameEntrySelection(""))
    }

    @Test
    fun theStripAndTheHeaderAgreeOnTheSelection() {
        // One contract, two editors: the header's editor is the same range, so
        // the two cannot drift apart.
        assertEquals(InlineTitleEdit("Untitled").entrySelection, tabRenameEntrySelection("Untitled"))
        assertEquals(InlineTitleEdit("").entrySelection, tabRenameEntrySelection(""))
    }

    // --- wiring: the field carries the selection through a TextFieldValue ----------

    @Test
    fun theRenameFieldCarriesTheEntrySelection() {
        val field = window(strip(), "internal fun TabRenameField(", "internal fun tabRenameEntrySelection(")
        // The value overload, seeded with the entry selection.
        assertTrue(
            field.contains(
                "var edit by remember { mutableStateOf(TextFieldValue(value, tabRenameEntrySelection(value))) }",
            ),
        )
        assertTrue(field.contains("value = edit,"))
        // The String overload is what put the cursor in front of the old title.
        assertFalse(field.contains("value = value,"))
    }

    @Test
    fun theFieldStillEchoesEveryEditToTheCallersDraft() {
        val field = window(strip(), "internal fun TabRenameField(", "internal fun tabRenameEntrySelection(")
        // A local `TextFieldValue` must not swallow the text: `onConfirm` saves
        // the caller's `renameDraft`, so every edit has to be echoed back.
        // v40 BUG-2: the echo is the W5-F-folded text — the field folds a Chinese
        // IME's full-width punctuation at its input boundary, and the draft is
        // what actually reaches the server.
        assertTrue(field.contains("onValueChange(folded.text)"))
        // Enter / IME Done / Escape keep deciding through that draft.
        assertTrue(field.contains("keyboardActions = KeyboardActions(onDone = { onConfirm() })"))
        assertTrue(field.contains("Key.Enter, Key.NumPadEnter -> {"))
        assertTrue(field.contains("Key.Escape -> {"))
    }

    @Test
    fun theStripCallersStillReadTheDraftTheyAreEchoed() {
        for (file in listOf(desktopStrip(), drawer())) {
            assertTrue(file.contains("value = renameDraft,"))
            assertTrue(file.contains("onValueChange = { renameDraft = it }"))
            assertTrue(file.contains("onRenameSession(tab, renameDraft)"))
        }
    }

    @Test
    fun theTerminalChipFieldOpensTheSameWay() {
        // The terminal tab chip keeps its own field (`OcTerminalRenameField`,
        // `TerminalPanel.kt`) rather than reusing `TabRenameField`, and it had
        // the same String-overload defect. v47: the entry selection is the
        // shared pure rule (`TerminalTabRename.entrySelection`, mirroring
        // `tabRenameEntrySelection`), and the draft opens prefilled with the
        // chip's displayed title (pinned in `TerminalTabStripV47Test`).
        val field = window(terminal(), "private fun OcTerminalRenameField(", "/** Ghost icon button of the strip")
        assertTrue(
            field.contains("var edit by remember { mutableStateOf(TextFieldValue(value, TerminalTabRename.entrySelection(value))) }"),
        )
        assertTrue(field.contains("value = edit,"))
        assertFalse(field.contains("value = value,"))
        assertTrue(field.contains("onValueChange(it.text)"))
    }
}
