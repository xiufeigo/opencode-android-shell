package com.xiufeigo.opencodeshell

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xiufeigo.opencodeshell.ui.home.HomeRowLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v45 tC — the four Home 改名 defects, the half of them that is pure.
 *
 * (3) P1 「极窄下列表塌空未修复」 (`research/v45_verify/tC/v45_46/47`, against the
 * v44_33/34 the previous round believed fixed). The height budget was three
 * constants that could not be paid for: on the 640x1500 @420 dpi phone with the
 * keyboard up the card measures ≈185 dp, while the search reserve (44 dp) plus
 * the list floor (68 dp) plus the utility nav (60 dp) is 172 dp of *guaranteed*
 * height inside a column that also has to fit the projects. A floor that is
 * larger than the space available does not protect the list, it overflows the
 * column — and the utility nav, laid out after it, is drawn straight over the
 * editing row the floor exists to keep visible. That is what the device run
 * recorded: the group header, 设置 / 帮助, and not one row.
 *
 * The fix is a budget that is *derived* from the card the host measured
 * ([HomeRowLayout.listMinHeight]) instead of a constant that can only be right
 * for one card, plus the header giving up stickiness when the viewport cannot
 * hold a header and a row ([HomeRowLayout.groupHeaderSticky]) — a sticky header
 * is drawn over the rows, so a viewport shorter than header + row can only ever
 * show part of one.
 *
 * The other three (the invisible editor text, the first-open focus race and the
 * rename's local mirror) are composable/view-model behaviour this JVM suite
 * cannot instantiate, so — like the rest of this repo's wave tests — they are
 * pinned in source below.
 */
class HomeRenameLayoutV45Test {

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()?.replace("\r\n", "\n")
            ?: error("missing $relative")

    private val sessionsList = source("src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeSessionsList.kt")
    private val homeScreen = source("src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeScreen.kt")
    private val viewModel = source("src/main/java/com/xiufeigo/opencodeshell/ChatViewModel.kt")

    /** The sessions column's own floor — search block, list viewport, nav. */
    private fun columnMin(available: Dp): Dp =
        HomeRowLayout.mobileColumnReserve(available, HomeRowLayout.listMinHeight(available))

    /**
     * What the list viewport is left with once the projects and the nav have
     * taken theirs. Both chrome bands are the **elastic** ones (v54 P1: the
     * search block drops its top inset and the nav steps aside on a card that
     * cannot pay for them), so this is what the column really measures, not the
     * v43/v44 constants it used to subtract whatever the card had.
     */
    private fun listViewport(available: Dp): Dp {
        val listMin = HomeRowLayout.listMinHeight(available)
        return available -
            HomeRowLayout.projectsDropdownMaxHeight(available, listMin) -
            HomeRowLayout.utilityNavMaxHeight(available, listMin) -
            HomeRowLayout.searchBlockHeight(available)
    }

    // ------------------------------------------------- (3) the height budget

    @Test
    fun `the list floor is derived from the card - a tall one still affords header plus row`() {
        assertEquals(HomeRowLayout.LIST_MIN_HEIGHT, HomeRowLayout.listMinHeight(900.dp))
        assertEquals(HomeRowLayout.LIST_MIN_HEIGHT, HomeRowLayout.listMinHeight(640.dp))
    }

    @Test
    fun `a card too short for the whole budget falls back to one whole row`() {
        // 640x1500 px at 420 dpi ≈ 243 x 571 dp; the page insets, the tab strip,
        // the card margins and a full-height soft keyboard leave the card ≈185 dp
        // — measured off `research/v45_verify/tC/v45_46/47`.
        //
        // v54 P1: the fallback is no longer a bare row. A row is never composed
        // on its own — its group header (28 dp) and the 16 dp pad between the
        // two come first — so a 40 dp viewport exhausted its band before a row
        // ever reached it and the list rendered headers and nothing else
        // (`research/v54_verify/tE/v54_16…22`). The floor is the three together.
        val card = 185.dp
        assertEquals(HomeRowLayout.LIST_SHORT_HEIGHT, HomeRowLayout.listMinHeight(card))
        assertEquals(
            HomeRowLayout.HEADER_MIN_HEIGHT + HomeRowLayout.GROUP_PAD_HEIGHT +
                HomeRowLayout.SESSION_ROW_HEIGHT,
            HomeRowLayout.LIST_SHORT_HEIGHT,
        )
        assertTrue(
            "the fixed floor no longer fits the card",
            HomeRowLayout.LIST_MIN_HEIGHT > card - HomeRowLayout.SESSIONS_SEARCH_RESERVE - HomeRowLayout.UTILITY_NAV_MIN,
        )
    }

    @Test
    fun `the mobile column fits its card at every height - the overflow is gone`() {
        // The invariant the v43/v44 constants broke: what the column *guarantees*
        // must never exceed what the card has, or the list is drawn under the
        // utility nav instead of above it.
        for (card in listOf(900.dp, 700.dp, 640.dp, 500.dp, 400.dp, 300.dp, 260.dp, 220.dp, 200.dp, 185.dp, 150.dp)) {
            val projects = HomeRowLayout.projectsDropdownMaxHeight(card, HomeRowLayout.listMinHeight(card))
            assertTrue("projects took $projects of $card", projects in 0.dp..card)
            val demand = columnMin(card) + projects
            assertTrue(
                "the column needs $demand of a $card card — the nav is laid over the list again",
                demand <= card || projects == 0.dp,
            )
            if (projects == 0.dp) {
                assertTrue(
                    "list viewport ${listViewport(card)} below its floor at $card",
                    listViewport(card) >= HomeRowLayout.listMinHeight(card) - HomeRowLayout.SESSIONS_SEARCH_RESERVE ||
                        card <= HomeRowLayout.SESSIONS_SEARCH_RESERVE + HomeRowLayout.UTILITY_NAV_MIN,
                )
            }
        }
    }

    @Test
    fun `the editing row is on screen in the 640x1500 keyboard-up case`() {
        val card = 185.dp
        assertEquals(0.dp, HomeRowLayout.projectsDropdownMaxHeight(card, HomeRowLayout.listMinHeight(card)))
        // Everything the card is left with after the nav is the search block plus
        // the list, and the list gets a whole row of viewport for the field.
        assertEquals(185.dp - 72.dp, card - HomeRowLayout.UTILITY_NAV_MIN)
        assertTrue(listViewport(card) >= HomeRowLayout.SESSION_ROW_HEIGHT)
    }

    @Test
    fun `the group header only stays sticky while a whole row fits under it`() {
        assertTrue(HomeRowLayout.groupHeaderSticky(HomeRowLayout.LIST_MIN_HEIGHT))
        assertTrue(HomeRowLayout.groupHeaderSticky(300.dp))
        // Below header + row a sticky header covers the top of every row.
        assertFalse(HomeRowLayout.groupHeaderSticky(HomeRowLayout.SESSION_ROW_HEIGHT))
        assertFalse(HomeRowLayout.groupHeaderSticky(0.dp))
    }

    @Test
    fun `the host derives both halves of the budget from the same card measurement`() {
        // v47 P0: the floor additionally follows the editing state — while an
        // editor is open the nav steps aside, so a floor derived as if it
        // still stood drops to the row-only 40 dp on a card that fits header
        // + row (`research/v47_verify/tC/v47_30/31`).
        assertTrue(homeScreen.contains("val listMinHeight = HomeRowLayout.listMinHeight(cardHeight, sessionsEditing)"))
        assertTrue(
            homeScreen.contains("HomeRowLayout.projectsDropdownMaxHeight(cardHeight, listMinHeight)"),
        )
        assertEquals(2, Regex("listMinHeight = listMinHeight").findAll(homeScreen).count())
        // The column takes the floor as a parameter instead of hard-coding it.
        assertTrue(sessionsList.contains("listMinHeight: Dp = HomeRowLayout.LIST_MIN_HEIGHT"))
        assertTrue(sessionsList.contains(".heightIn(min = listMinHeight)"))
    }

    @Test
    fun `a non-sticky header is still one list item - the scroll arithmetic holds`() {
        // `homeSessionItemIndex` and the sticky-header fade both count the header
        // as one slot; a plain `item` keeps that, so the editor's scroll target
        // cannot drift when the header gives up stickiness.
        assertTrue(sessionsList.contains("if (stickyHeader) {"))
        assertTrue(sessionsList.contains("stickyHeader = stickyHeader,"))
        // v46 P0: the stickiness is additionally gated on no open rename editor —
        // a pinned header is drawn over the auto-scrolled editing row.
        assertTrue(sessionsList.contains("HomeRowLayout.groupHeaderSticky(maxHeight) && rowEditor == null"))
    }

    // ------------------------------------------- (2) the editor's own surface

    @Test
    fun `the rename field carries its own surface instead of being a bare weighted child`() {
        // v45 P1 「改名输入框文字不可见」: the editor drew an empty grey bar while
        // uiautomator could still read the draft off the node
        // (`research/v45_verify/tC/v45_15/22/25/31`). The field is now laid out
        // the way the chat header's inline title is — the one field on this app
        // that verifiably shows its text on a device.
        val editor = sessionsList.substring(sessionsList.indexOf("private fun HomeSessionRenameEditor("))
        assertTrue(
            "the field must fill its weighted slot; a slot that measures to its intrinsic width leaves nothing to draw into",
            editor.contains("fill = true,"),
        )
        assertTrue(editor.contains(".defaultMinSize(minHeight = ROW_HEIGHT)"))
        assertTrue(editor.contains(".background(c.layer02.copy(alpha = 0.6f))"))
        // The surface is on the field, not only on the row around it.
        assertFalse(editor.contains(".height(ROW_HEIGHT)\n            .clip(OcTheme.shapes.md)\n            .background("))
    }

    // ------------------------------------------------- (4) the first-open focus

    @Test
    fun `the focus ask waits for a settled frame and has budget for the third try`() {
        val editor = sessionsList.substring(sessionsList.indexOf("private fun HomeSessionRenameEditor("))
        assertTrue(
            "the first-open race is the ask on the composition frame; it has to wait one",
            editor.contains("withFrameNanos { }"),
        )
        assertTrue(editor.contains("LaunchedEffect(editor.sessionId) {\n        // The node is attached"))
        assertTrue(
            "onGloballyPositioned stays as the placement retry",
            editor.contains(".onGloballyPositioned { if (!focused) askForFocus() }"),
        )
        assertFalse(
            "an unguarded ask re-attaches the InputConnection on every relayout",
            editor.contains(".onGloballyPositioned { askForFocus() }"),
        )
        assertTrue(sessionsList.contains("private const val FOCUS_TRIES = 3"))
    }

    // ------------------------------------------- (1) the rename's own half

    @Test
    fun `the PATCH is dispatched from the coroutine, with nothing between it and the tap`() {
        // The "≥90 s before the server has it" report: the write is not queued,
        // debounced or cached anywhere on this path — the tap launches, the
        // coroutine asks for the transport, and the PATCH goes out on that
        // dispatch. What the device run actually recorded is the *commit* not
        // happening (the first open never took focus, so the IME's ✓ had no
        // field to land in) and the row then not showing the accepted title
        // until the next catalogue read.
        val body = viewModel.substring(viewModel.indexOf("fun renameSession("))
        val bodyEnd = body.indexOf("\n    /**")
        val window = if (bodyEnd > 0) body.substring(0, bodyEnd) else body
        assertTrue(window.contains("scope.launch"))
        assertTrue(window.contains("withContext(Dispatchers.IO) { transport.renameSession(id, trimmed) }"))
        assertFalse(
            "nothing may sit between the launch and the PATCH but the transport",
            window.contains("delay(") || window.contains("debounce") || window.contains("retryProbe"),
        )
    }

    @Test
    fun `an accepted rename lands on the row in the same frame, and only when accepted`() {
        // Upstream invalidates its session query right after `session.update`
        // (`home/sessions/controller.tsx:141-167`); the only read here is the
        // catalogue poll, so the local mirror is what makes the row agree with
        // the editor that just closed.
        assertTrue(homeScreen.contains("var renamedTitles by remember { mutableStateOf<Map<String, String>>(emptyMap()) }"))
        assertTrue(
            homeScreen.contains("if (renamed) renamedTitles = renamedTitles + (id to title)"),
        )
        assertTrue(homeScreen.contains("val titledSessions = remember(sessions, renamedTitles)"))
        // The read prunes what the server now reports itself, so the mirror can
        // never outlive the server's own answer (v46 P0: [pruneRenamedTitles] —
        // keep while the server still reports the old title, drop once it
        // agrees or the id is gone).
        assertTrue(homeScreen.contains("pruneRenamedTitles(renamedTitles"))
    }
}
