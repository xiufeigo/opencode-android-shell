package com.xiufeigo.opencodeshell

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import com.xiufeigo.opencodeshell.ui.home.HomeRowLayout
import com.xiufeigo.opencodeshell.ui.home.HomeRowMenu
import com.xiufeigo.opencodeshell.ui.home.homeRenameEntrySelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v43's Home rename batch — the three device bugs the v43 real-device run
 * produced against the inline rename editor (`research/v43_verify/tC/`).
 *
 * **P0 「重命名无法保存」** (`v43_C1/C2/C5`): after the row editor opened, Enter
 * and the IME's ✓ both did nothing, the draft never even reached `saving` — so
 * `onSubmit` was never called — and Esc was the only way out. The chat header's
 * inline title commits the same way three times over
 * (`SessionHeader.kt`: `Key.Enter` / `Key.NumPadEnter`, `onDone`, and
 * `onFocusChanged { if (!focused) commit() }`); this field had the first two and
 * no focus path at all. The pure half of the fix is
 * [HomeRowMenu.shouldCommit] — the rule for "may this gesture commit" — plus the
 * wiring assertions below.
 *
 * **P1 「打开不接管焦点」** (`v43_C3/C4/C5`): the field showed the whole title
 * selected but held no focus, so the first keystrokes went nowhere; once the
 * IME did connect it restored Android's own caret at the end of the text and the
 * same three keys *appended* (`abcabc`) instead of replacing the selection, which
 * is what upstream's `input.select()` (`home/sessions/view.tsx:481-489`) does.
 * Opening now asks for focus again once the row is placed and re-asserts the
 * whole-title selection once the field has focus.
 *
 * **P1 「极窄塌陷死局」** (`v43_G3/G8/G9/G6`): at `wm size 640x1500` + 420 dpi
 * (≈243 × 571 dp) the keyboard left the sessions list's `weight(1f)` share at
 * 0 dp — search field, group headers, rows and the utility nav all gone, with
 * the editor that had just replaced its row. No scroll position recovers from a
 * 0 dp viewport and a phone has no Esc, so the list area has a floor:
 * [HomeRowLayout.LIST_MIN_HEIGHT], one header + one row.
 *
 * The pure halves are pinned as behaviour and the `@Composable` wiring at the
 * source level — the same split `HomeTitleInputV40Test` and
 * `HomeRowNarrowTest` use.
 */
class HomeRenameCommitV43Test {

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()?.replace("\r\n", "\n")
            ?: error("missing $relative")

    private fun bodyOf(text: String, signature: String, span: Int = 4_000): String {
        val at = text.indexOf(signature)
        assertTrue("missing $signature", at >= 0)
        return text.substring(at, minOf(text.length, at + span))
    }

    /**
     * A window bounded by the **next declaration** rather than a character count:
     * v77's entry-sequence fix grew `HomeSessionRenameEditor` past the fixed
     * 12 000-char span this file used, which silently cut the field's own
     * `BasicTextField` region (and the `IME_DONE` assertion below with it) out of
     * the window. The assertions are about the composable, not about a length.
     */
    private fun windowOf(text: String, from: String, to: String): String {
        val start = text.indexOf(from)
        assertTrue("missing $from", start >= 0)
        val end = text.indexOf(to, start + from.length)
        assertTrue("missing $to after $from", end > start)
        return text.substring(start, end)
    }

    private val homeList = source("src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeSessionsList.kt")
    private val editor = windowOf(homeList, "private fun HomeSessionRenameEditor(", "\ninternal fun homeSessionItemIndex(")

    // ------------------------------------------------ P0: the commit chain

    @Test
    fun `every gesture commits the draft`() {
        // Enter, the IME's ✓ and the blur all resolve to the same `PATCH`; before
        // v43 the blur had no path at all, which is the gesture a touch-only user
        // has.
        for (trigger in HomeRowMenu.CommitTrigger.entries) {
            assertTrue(
                "$trigger must commit a focused, idle editor",
                HomeRowMenu.shouldCommit(trigger, saving = false, focused = true),
            )
        }
    }

    @Test
    fun `nothing commits while the PATCH is in flight`() {
        for (trigger in HomeRowMenu.CommitTrigger.entries) {
            assertFalse(
                "$trigger must not fire a second PATCH",
                HomeRowMenu.shouldCommit(trigger, saving = true, focused = true),
            )
        }
    }

    @Test
    fun `a field that never took focus does not save a draft the user never saw`() {
        // The blur half is the only one that needs focus: an editor that opened
        // without ever holding it must not PATCH on the way out.
        assertFalse(HomeRowMenu.shouldCommit(HomeRowMenu.CommitTrigger.BLUR, saving = false, focused = false))
        assertTrue(HomeRowMenu.shouldCommit(HomeRowMenu.CommitTrigger.ENTER, saving = false, focused = false))
        assertTrue(HomeRowMenu.shouldCommit(HomeRowMenu.CommitTrigger.IME_DONE, saving = false, focused = false))
    }

    @Test
    fun `a commit gesture reaches exactly one PATCH`() {
        val open = HomeRowMenu.openEditor("E2ECHK v43 T0", "ses_1")
        val typed = HomeRowMenu.editDraft(open, "ses_1", "renamed")!!
        val (saving, action) = HomeRowMenu.submit(typed, "E2ECHK v43 T0")!!
        assertTrue("the editor shows saving meanwhile", saving!!.saving)
        assertEquals(HomeRowMenu.Submit.Save("renamed"), action)
        // A second gesture while the PATCH is in flight is a no-op, not a second
        // submit — this is what the `saving` flag is for. (v44: the reporting
        // row's id is no longer consulted at all; see HomeRowMenuV44Test.)
        assertNull(HomeRowMenu.submit(saving, "E2ECHK v43 T0"))
        // …and so is a submit with no editor open.
        assertNull(HomeRowMenu.submit(null, "E2ECHK v43 T0"))
    }

    @Test
    fun `a failed PATCH ends the edit instead of leaving it open`() {
        val open = HomeRowMenu.openEditor("abc", "ses_1")
        val (saving, _) = HomeRowMenu.submit(open, "abc")!!
        // v84 P1: the failed commit resolves to a closed editor. v83's rollback
        // kept it open on the server's title, which is the state a phone cannot
        // leave — the row it replaces is the only way back to the row menu, and
        // there is no Esc (see [HomeRenameFailureV83Test] /
        // [HomeRenameExitV84Test]).
        assertNull(HomeRowMenu.resolveSave(saving, "ses_1", saved = false))
        // …and a blur on the way out is still a commit gesture while the editor
        // is up (the v43 chain is untouched).
        assertTrue(HomeRowMenu.shouldCommit(HomeRowMenu.CommitTrigger.BLUR, saving = false, focused = true))
    }

    @Test
    fun `the editor wires all three gestures and raises its own keyboard`() {
        assertTrue(editor.contains("commit(HomeRowMenu.CommitTrigger.IME_DONE)"))
        assertTrue(editor.contains("commit(HomeRowMenu.CommitTrigger.ENTER)"))
        assertTrue(editor.contains("commit(HomeRowMenu.CommitTrigger.BLUR)"))
        // The header's shape: focus is tracked, and losing it commits.
        assertTrue(editor.contains(".onFocusChanged { state ->"))
        assertTrue(editor.contains("} else if (focused) {"))
        // Order is the mechanism: a blur only commits while the flag still says
        // the field held focus, so the commit has to come first.
        val blur = editor.indexOf("} else if (focused) {")
        assertTrue(blur > 0)
        assertTrue(
            "the blur must commit before the flag is cleared",
            editor.indexOf("commit(HomeRowMenu.CommitTrigger.BLUR)", blur) in (blur until editor.indexOf("focused = false", blur)),
        )
        // Hardware Enter: Compose never turns NumPadEnter into `onDone`.
        assertTrue(editor.contains("event.key == Key.Enter -> {"))
        assertTrue(editor.contains("event.key == Key.NumPadEnter -> {"))
        assertTrue(editor.contains("event.key == Key.Escape -> {"))
        // A row inside a LazyColumn composes before its focus node is attached, so
        // the first `requestFocus()` can be lost; ask again on first placement and
        // show the keyboard instead of waiting for the IME to volunteer it.
        // v66 BUG-4: the retry is guarded by the focus itself. Unguarded it ran
        // on every relayout — and a renaming row relayouts per keystroke — and
        // each repeat re-attached the InputConnection, dropping the character
        // the IME was holding (`research/v65_verify/5558/36_rename_ent_typed.png`).
        assertTrue(editor.contains(".onGloballyPositioned { if (!focused) askForFocus() }"))
        assertTrue(editor.contains("keyboard?.show()"))
        // v45 P2: two asks were one short on a cold list's first open, so the
        // budget is three and the effect's first ask waits for a settled frame.
        assertTrue(editor.contains("private const val FOCUS_TRIES = 3") || homeList.contains("private const val FOCUS_TRIES = 3"))
        assertTrue(editor.contains("withFrameNanos { }"))
    }

    // ------------------------------------- P1: focus on open, select-all kept

    @Test
    fun `the whole title is re-selected once the field really holds focus`() {
        // The IME connection restores Android's own caret at the end of the text
        // (`abcabc`), so the entry selection is asserted again on focus — once,
        // so a later caret move is the user's own.
        assertTrue(editor.contains("LaunchedEffect(focused) {"))
        // v64 BUG-3: the re-assert is bounded to a draft the user has not
        // touched yet — unbounded, it ate the first segment of a segmented IME
        // commit (`cursor` -> `urso`, `research/v64_verify/5558/10c`). The rule
        // itself is pinned by `RenameEditorImeV64Test`.
        assertTrue(editor.contains("value = homeRenameEntryReassert(value, entryText)"))
    }

    @Test
    fun `the entry selection replaces on the first keystroke`() {
        for (title in listOf("E2ECHK v43 T0", "", "修复：会话")) {
            val selection = homeRenameEntrySelection(title)
            assertEquals(0, selection.start)
            assertEquals(title.length, selection.end)
            // Upstream's `input.select()`: the first key's `TextRange` collapses to
            // the insertion point, so the whole old name is the replaced range.
            assertEquals(TextRange(0, title.length), selection)
        }
    }

    // ------------------------------------------- P1: 极窄 the list has a floor

    @Test
    fun `the list area keeps one header plus one row`() {
        assertEquals(28.dp, HomeRowLayout.HEADER_MIN_HEIGHT)
        assertEquals(40.dp, HomeRowLayout.SESSION_ROW_HEIGHT)
        assertEquals(68.dp, HomeRowLayout.LIST_MIN_HEIGHT)
        // The floor is what the editor replaces: an editor is never taller than
        // the row it took the place of.
        assertTrue(HomeRowLayout.LIST_MIN_HEIGHT >= HomeRowLayout.SESSION_ROW_HEIGHT)
    }

    @Test
    fun `the list viewport is floored and the editor replaces its row in place`() {
        // v43 P1: the editor lives inside this viewport, so a 0 dp viewport hides
        // the field that is being edited.
        // v45 P1: the floor is the host's derived one — a fixed 68 dp overflowed
        // a 185 dp card and the nav was drawn over the editing row
        // (`research/v45_verify/tC/v45_46/47`, `HomeRenameLayoutV45Test`).
        assertTrue(homeList.contains(".heightIn(min = listMinHeight)"))
        // In place, not as an overlay: the same `Box`, and the editor keeps the
        // row's own height, so the list's item heights cannot change under it.
        val row = bodyOf(homeList, "private fun HomeSessionRow(", 7_500)
        assertTrue(row.contains("val renaming = editor != null"))
        assertTrue(row.contains("if (renaming) {"))
        assertTrue(row.contains("HomeSessionRenameEditor("))
        assertTrue(editor.contains(".height(ROW_HEIGHT)"))
        assertTrue(editor.contains("private val ROW_HEIGHT = HomeRowLayout.SESSION_ROW_HEIGHT") ||
            homeList.contains("private val ROW_HEIGHT = HomeRowLayout.SESSION_ROW_HEIGHT"))
    }
}
