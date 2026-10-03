package com.xiufeigo.opencodeshell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The picker card's information architecture, against upstream
 * `_upstream/packages/app/src/workspaces/selection/dialog.tsx:282-381`: the
 * path input with its suggestions, the `~` / root / parent ghost row, the
 * browser body, the resolved-path line, and the cancel + confirm footer. All
 * five are unconditional — a real-device frame that showed only the path row,
 * the listing and a confirm button is what this pins down (nothing in the card
 * is behind a `Show`).
 *
 * The two layout guarantees that frame also needed are here: the card carries
 * the IME inset (edge-to-edge — the keyboard is drawn *over* the composition, so
 * a centered card had its footer behind it:
 * `research/v34_verify/v34_chk_03_path_typed.png`) and the browser body is
 * capped to the room that is left (`pickerBodyMax`, pinned as behaviour in
 * `DirectoryPickerStartTest`).
 *
 * Source guards, the established shape where Compose wiring is not reachable
 * from a JVM test — the pure halves are unit-tested separately.
 */
class DirectoryPickerLayoutTest {

    private fun source(relative: String): String {
        val file = File(relative).takeIf { it.isFile } ?: File("app/$relative")
        return file.readText().replace("\r\n", "\n")
    }

    private val picker by lazy {
        source("src/main/java/com/xiufeigo/opencodeshell/ui/home/DirectoryPickerDialog.kt")
    }

    /** The card itself: the root `Column` up to the first helper below it. */
    private val card by lazy {
        picker.substringAfter("    Column(\n        modifier = modifier")
            .substringBefore("private fun GhostNavButton")
    }

    @Test
    fun `every block upstream draws is in the card`() {
        assertTrue("the path input", card.contains("BasicTextField("))
        assertTrue(card.contains("stringResource(R.string.oc_home_projects_path_hint)"))
        // The ghost row: all three buttons, `~` / root / parent.
        assertEquals(
            "exactly the three ghost nav buttons",
            3L,
            Regex("""GhostNavButton\(""").findAll(card).count().toLong(),
        )
        assertTrue(card.contains("label = \"~\""))
        assertTrue(card.contains("label = stringResource(R.string.oc_home_projects_root)"))
        assertTrue(card.contains("label = stringResource(R.string.oc_home_projects_parent)"))
        // The footer: cancel + confirm, plus the selection line above them.
        assertTrue(card.contains("stringResource(R.string.oc_common_cancel)"))
        // The confirm footer button. Its label moved behind the two-tap stage
        // helper (`pickerConfirmLabelRes`) so the armed card reads differently
        // — the button itself is still here, and the unarmed stage is still the
        // upstream 「选择此目录」 (pinned where it is resolved).
        assertTrue(card.contains("stringResource(pickerConfirmLabelRes(confirming != null))"))
        assertTrue(
            picker.contains(
                "if (armed) R.string.oc_home_projects_confirm_directory " +
                    "else R.string.oc_home_projects_select_directory",
            ),
        )
        assertTrue(card.contains("stringResource(R.string.oc_home_projects_drives)"))
    }

    @Test
    fun `the blocks keep upstream's order`() {
        val input = card.indexOf("BasicTextField(")
        val nav = card.indexOf("GhostNavButton(")
        val body = card.indexOf("val confirming = pendingConfirm")
        val selection = card.indexOf("stringResource(R.string.oc_home_projects_drives)")
        val footer = card.indexOf("stringResource(R.string.oc_common_cancel)")
        assertTrue("the path input comes first", input >= 0)
        assertTrue("the nav row follows the input", nav > input)
        assertTrue("the nav row sits above the browser body", body > nav)
        assertTrue("the selection line follows the body", selection > body)
        assertTrue("the footer is last", footer > selection)
    }

    @Test
    fun `the card carries the IME inset`() {
        // Edge-to-edge: nothing resizes the window for the keyboard, so the card
        // has to lift itself off it.
        assertTrue(picker.contains(".imePadding()"))
        assertTrue(picker.contains("WindowInsets.ime.getBottom(this)"))
        // v68: the caps run on the viewport below the status bar — a tall card
        // is centered into the full window, so without the top inset its
        // header (title + ✕) slides under the status bar where taps go to the
        // system (`research/v68_verify/5560/75_tilde.png`).
        assertTrue(picker.contains("WindowInsets.statusBars.getTop(this)"))
        assertTrue(picker.contains("val viewportHeight = windowHeight - statusTop"))
        assertTrue(picker.contains("val suggestionsMax = pickerSuggestionsMax(viewportHeight, coveredBottom)"))
        assertTrue(
            picker.contains("val bodyMax = pickerBodyMax(viewportHeight, coveredBottom, suggestions.isNotEmpty())"),
        )
        // The cap moved to the card: the card is a viewport (a max derived from
        // the window and the IME) and the two body states are the elastic part
        // of it — same floor, same ceiling, `weight` between them, so the footer
        // stays pinned to the keyboard's top edge in either state.
        assertTrue(picker.contains("val cardMax = pickerCardMax(viewportHeight, coveredBottom)"))
        assertTrue(picker.contains(".heightIn(max = cardMax)"))
        assertEquals(
            "the card is capped exactly once",
            1L,
            Regex("""\.heightIn\(max = cardMax\)""").findAll(picker).count().toLong(),
        )
        assertEquals(
            "both body states (list and pending path) take the same floor and ceiling",
            2L,
            Regex("""\.weight\(1f\)\s*\n\s*\.heightIn\(min = LIST_MIN_HEIGHT, max = bodyMax\)""")
                .findAll(picker).count().toLong(),
        )
        // The dropdown is in flow too, so it shares the budget instead of
        // growing the card past the window (the path field would be the block
        // that falls off).
        assertEquals(
            "the suggestion dropdown takes its own cap",
            1L,
            Regex("""\.heightIn\(max = suggestionsMax\)""").findAll(picker).count().toLong(),
        )
    }

    @Test
    fun `the card clears the system navigation bar too`() {
        // v46 tD_13/tD_24: edge-to-edge leaves the navigation bar drawn over the
        // composition exactly like the keyboard, so with the keyboard down the
        // 8 dp card gutter parked the footer ~25 px above the gesture pill. The
        // covered strip the three caps share is both insets, not just the IME.
        assertTrue(picker.contains("import androidx.compose.foundation.layout.navigationBars"))
        assertTrue(picker.contains("WindowInsets.navigationBars.getBottom(this)"))
        assertTrue(picker.contains("val navBottom = "))
        assertTrue(picker.contains("val coveredBottom = imeBottom + navBottom"))
        // v47 `research/v47_verify/tE/tE_36_move.png`: the caps alone cannot
        // keep a *centered* card's footer clear of the bar — a tall card's
        // bottom margin is only half the leftover window, so Cancel/Select
        // this directory were drawn under the black system bar (in the
        // uiautomator dump, fully untappable). The card carries the nav inset
        // as padding on its own box, right after the IME padding and inside
        // the viewport cap, so the whole card — footer included — sits above
        // the bar however tall it gets. Keyboard-up behaviour is unchanged
        // (the nav padding reads ~0 under the IME).
        assertTrue(picker.contains("import androidx.compose.foundation.layout.navigationBarsPadding"))
        val ime = picker.indexOf(".imePadding()")
        val nav = picker.indexOf(".navigationBarsPadding()")
        val status = picker.indexOf(".statusBarsPadding()")
        val cap = picker.indexOf(".heightIn(max = cardMax)")
        assertTrue("the card carries the IME inset", ime >= 0)
        assertTrue("the nav padding rides the card's own box after the IME padding", nav > ime)
        assertTrue("the status padding rides the same box after the nav padding", status > nav)
        assertTrue("both insets count towards the viewport cap", cap > nav)
        assertTrue("the header clears the status bar before the cap", cap > status)
    }

    @Test
    fun `reopening resets the browser to the supplied start`() {
        // v47 tD_30/u33: a typed-but-unconfirmed dirty value must not leak
        // into the next open — reopening Move to… starts from the supplied
        // `initialPath` (the session directory), never from the stale field.
        // Every piece of browsing state is therefore keyed on the entry
        // start; only the server-described probe answers stay unkeyed.
        assertTrue(picker.contains("val entry = remember(initialPath) { pickerEntryStart(initialPath) }"))
        for (state in listOf(
            "currentPath",
            "pathInput",
            "nodes",
            "loading",
            "failed",
            "failure",
            "userNavigated",
            "userEdited",
            "pendingConfirm",
            "suggestions",
            "inputToken",
        )) {
            assertTrue(
                "$state resets with the entry start",
                picker.contains("var $state by remember(entry)"),
            )
        }
    }

    @Test
    fun `the path field asks for an ASCII keyboard without autocorrect`() {
        // Gboard rewrote `:` and `/` into their full-width twins while a path was
        // being typed (`research/v36_verify/tC/tC_41_picker_path2.png`:
        // `/home／修费高／率6projC`), which names no directory the server has.
        assertTrue(
            picker.contains("keyboardOptions = OcKeyboard.identifier(imeAction = ImeAction.Search)"),
        )
        assertTrue(picker.contains("import com.xiufeigo.opencodeshell.ui.components.OcKeyboard"))
        // The matrix is the shared one: a hand-built set here would drift from
        // `OcKeyboardOptionsTest`.
        assertFalse("still builds KeyboardOptions by hand", picker.contains("KeyboardOptions("))
    }
}
