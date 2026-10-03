package com.xiufeigo.opencodeshell

import androidx.compose.ui.unit.dp
import com.xiufeigo.opencodeshell.ui.home.HomeRowLayout
import com.xiufeigo.opencodeshell.ui.home.HomeSessions
import com.xiufeigo.opencodeshell.ui.home.homeSessionItemIndex
import com.xiufeigo.opencodeshell.ui.home.HomeRowMenu
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v44's Home row-menu regression batch — the three defects the v44 real-device
 * run produced against the row menu and the inline rename editor
 * (`research/v44_verify/tC/`).
 *
 * **P0 「重命名提交 8/10 全废」** (`v44_10/12/14/15/18`): Enter 5/6, the IME's ✓
 * 2/2 and the tap-away 2/2 all left the editor open with its draft and never
 * sent a `PATCH` — `renameSession` was never reached. The commit chain itself
 * was wired correctly ([HomeRenameCommitV43Test] pins all three gestures), so
 * the commit was being produced and then *discarded downstream of the field*:
 * `HomeRowMenu.submit(rowEditor, row.id, row.title)` returned `null` on an id
 * mismatch and the one call site dropped it with `?: return@HomeSessionList` —
 * no log, no request, nothing for the tester to read. The open editor is the
 * only rename in flight (the list root keeps one, `view.tsx:87-90`) and only
 * its own row renders the field, so the reporting row's id had nothing to
 * decide: [HomeRowMenu.submit] now takes the editor and the label only.
 *
 * **P1 「重命名期间列表塌成 1 行」** (`v44_13/14/18/33/34`, `v44_16_after_esc`):
 * not a row filter and not a key drift — the keyboard. Opening the editor
 * raises the IME, the card shrinks to ≈438 dp on a 1080×2424 phone, and the
 * 项目 dropdown still took its plain 40 % (175 dp) under the v43 formula, so
 * the sessions list was left ≈123 dp: one group header, its 16 dp pad and
 * exactly one row. Esc restored it by closing the editor, which is what took
 * the keyboard down. Below [HomeRowLayout.SHORT_CARD_HEIGHT] the panel may no
 * longer keep 40 % of a card that is already short — the list being edited
 * keeps [HomeRowLayout.SESSIONS_LIST_MIN_FRACTION] of it.
 *
 * The pure halves are pinned as behaviour and the `@Composable` wiring at the
 * source level, the same split `HomeRenameCommitV43Test` uses.
 */
class HomeRowMenuV44Test {

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()?.replace("\r\n", "\n")
            ?: error("missing $relative")

    private val homeList = source("src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeSessionsList.kt")

    // ------------------------------------------- P0: the commit is not eaten

    @Test
    fun `a commit gesture is never cancelled by a row identity`() {
        // The three gestures all end in the same submit, so one representative
        // editor covers them: the draft is changed, the editor is idle, so the
        // commit must resolve no matter which row reported it.
        val typed = HomeRowMenu.editDraft(HomeRowMenu.openEditor("E2ECHK-5554-v44a", "ses_1"), "ses_1", "renamed")!!
        val (saving, action) = HomeRowMenu.submit(typed, "E2ECHK-5554-v44a")!!
        assertEquals(HomeRowMenu.Submit.Save("renamed"), action)
        assertTrue("the editor shows saving meanwhile", saving!!.saving)
        assertEquals("ses_1", saving.sessionId)
    }

    @Test
    fun `submit refuses exactly two things - no editor, and a PATCH in flight`() {
        val typed = HomeRowMenu.editDraft(HomeRowMenu.openEditor("abc", "ses_1"), "ses_1", "renamed")!!
        assertNull("nothing is open", HomeRowMenu.submit(null, "abc"))
        val saving = HomeRowMenu.submit(typed, "abc")!!.first!!
        assertTrue(saving.saving)
        assertNull("a second gesture must not fire a second PATCH", HomeRowMenu.submit(saving, "abc"))
    }

    @Test
    fun `a draft that never changed is still a no-op, not a PATCH`() {
        val editor = HomeRowMenu.openEditor("Fix login", "ses_1")
        val blank = HomeRowMenu.editDraft(editor, "ses_1", "   ")!!
        assertEquals(HomeRowMenu.Submit.Noop, HomeRowMenu.submit(blank, "Fix login")!!.second)
        assertEquals(HomeRowMenu.Submit.Noop, HomeRowMenu.submit(editor, "Fix login")!!.second)
    }

    @Test
    fun `the call site resolves the session from the editor, not the row`() {
        // The regression itself: the submit used to be keyed on the reporting
        // row's id and its `null` was dropped silently.
        assertFalse(
            "the row id must not be part of the submit decision",
            homeList.contains("HomeRowMenu.submit(rowEditor, row.id, row.title)"),
        )
        assertTrue(homeList.contains("HomeRowMenu.submit(rowEditor, row.title)"))
        // …and the `PATCH` goes to the editor's own session.
        assertTrue(homeList.contains("val sessionId = step.first?.sessionId"))
        assertTrue(homeList.contains("onRenameSession?.invoke(\n"))
        // The editor is the single rename in flight at the list root, so the row
        // that renders the field is the editor's row.
        assertTrue(homeList.contains("editor = rowEditor?.takeIf { it.sessionId == row.id }"))
    }

    // ------------------------------- P1: the list does not collapse to one row

    /** What the sessions list is left with, given the dropdown's ceiling. */
    private fun listGets(available: androidx.compose.ui.unit.Dp, nav: androidx.compose.ui.unit.Dp = HomeRowLayout.UTILITY_NAV_MIN): androidx.compose.ui.unit.Dp =
        available - HomeRowLayout.projectsDropdownMaxHeight(available) - nav

    @Test
    fun `a tall card is still governed by the plain 40 percent`() {
        // 900 dp with the keyboard down: the panel is a panel, and the sessions
        // list keeps everything else.
        assertEquals(360.dp, HomeRowLayout.projectsDropdownMaxHeight(900.dp))
        assertTrue(listGets(900.dp) > HomeRowLayout.LIST_MIN_HEIGHT)
    }

    @Test
    fun `a card with the keyboard up leaves the editing list a real share`() {
        // The v44 measurement: 1080x2424 @2.75 measures ≈790 dp with the
        // keyboard down and ≈438 dp with it up. Under the v43 formula the 40 %
        // fraction still won (175 dp) and the list was left ≈123 dp - one header,
        // its pad and exactly one row, at every width.
        val up = 438.dp
        val projects = HomeRowLayout.projectsDropdownMaxHeight(up)
        assertTrue("projects took $projects of $up", projects < 175.dp)
        // The list keeps half the card: header + pad + four rows, not one.
        assertTrue("list got ${listGets(up)}", listGets(up) >= up * HomeRowLayout.SESSIONS_LIST_MIN_FRACTION)
        assertTrue(listGets(up) >= 4.dp * HomeRowLayout.SESSION_ROW_HEIGHT.value)
    }

    @Test
    fun `the narrow keyboard-up card keeps the editing row on screen too`() {
        // `wm size 640x1500` + 420 dpi: ≈243 x 571 dp, and ≈300 dp of card with
        // the keyboard up (v44_33/34 - the rename row was completely invisible).
        val up = 300.dp
        val projects = HomeRowLayout.projectsDropdownMaxHeight(up)
        assertTrue("projects took $projects of $up", projects < up * HomeRowLayout.PROJECTS_DROPDOWN_MAX_FRACTION)
        assertTrue("list got ${listGets(up)}", listGets(up) >= up * HomeRowLayout.SESSIONS_LIST_MIN_FRACTION)
        assertTrue(listGets(up) >= HomeRowLayout.SESSIONS_COLUMN_MIN)
    }

    @Test
    fun `the short-card share only applies below the short-card height`() {
        assertEquals(640.dp, HomeRowLayout.SHORT_CARD_HEIGHT)
        assertEquals(0.5f, HomeRowLayout.SESSIONS_LIST_MIN_FRACTION, 0.0001f)
        // On a tall card the fraction is the whole story again, unchanged:
        // 640 dp is not "short", so it is 40 % of it.
        assertEquals(256.dp, HomeRowLayout.projectsDropdownMaxHeight(640.dp))
    }

    // -------------------------------- P1: the editing row is scrolled into view

    private fun row(id: String) = HomeSessions.Row(
        id = "ses_$id",
        title = "T$id",
        directory = "/tmp/p$id",
        project = "p$id",
        updatedAt = 1_700_000_000_000L,
    )

    private fun section(group: HomeSessions.Group, vararg ids: String) =
        HomeSessions.Section(group, ids.map { row(it) })

    @Test
    fun `the editing row's item index counts each group's own slots`() {
        val sections = listOf(
            section(HomeSessions.Group.TODAY, "1", "2"),
            section(HomeSessions.Group.OLDER, "9"),
        )
        // Group one: header(0) + pad(1) + rows(2,3) + spacer(4); group two
        // starts at 5, so its first row is 7.
        assertEquals(2, homeSessionItemIndex(sections, "ses_1"))
        assertEquals(3, homeSessionItemIndex(sections, "ses_2"))
        assertEquals(7, homeSessionItemIndex(sections, "ses_9"))
        // A row this list does not render has no index - the caller then leaves
        // the scroll position alone rather than scrolling to item 0.
        assertNull(homeSessionItemIndex(sections, "ses_missing"))
        assertNull(homeSessionItemIndex(emptyList(), "ses_1"))
    }

    @Test
    fun `opening the editor scrolls its own row into the viewport`() {
        // The keyboard takes the viewport down by a few hundred dp, so the row
        // the user tapped can leave the visible range with the field on it.
        assertTrue(homeList.contains("homeSessionItemIndex(sections, editing)"))
        assertTrue(homeList.contains("listState.animateScrollToItem(it)"))
        // …keyed on the open editor's session, the same id the list is keyed by.
        assertTrue(homeList.contains("val editing = rowEditor?.sessionId"))
    }

    @Test
    fun `an exhausted card still degrades to zero projects rather than a zero list`() {
        for (available in listOf(0.dp, 50.dp, 120.dp, 171.dp, 200.dp)) {
            assertEquals(0.dp, HomeRowLayout.projectsDropdownMaxHeight(available))
        }
        assertFalse(HomeRowLayout.SESSIONS_LIST_MIN_FRACTION > 0.5f)
    }
}
