package com.xiufeigo.opencodeshell

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xiufeigo.opencodeshell.ui.home.HomeRowLayout
import com.xiufeigo.opencodeshell.ui.session.SessionPaneLayout
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v52 P0 「短窗列表整片空白」: two blank bodies on the same short window —
 * 1440x810 @420dpi (≈342 dp of window, ≈250 dp of pane / ≈131 dp of Home card)
 * — where the rows behind them were proven present:
 *
 *  * the session message list (server `GET message` returns user + assistant
 *    turns, the body renders none; the same session is complete at 1344x2992 —
 *    `research/v52_verify/tE/v52_05_msgs`, `v52_06_wait20`, `v52_42_shortagain`
 *    against `v52_39_bigmsgs`);
 *  * the Home session list (设置 / 帮助 / Search render, not one of the 6+
 *    sessions; `v52_32_home`, `v52_33_listscroll` against `v52_38_big`).
 *
 * Two distinct causes, both budget bugs:
 *
 *  1. **the pane mounted its chrome ceiling twice.** `chromeMaxHeight` derives a
 *     *total* for the chrome band, but with「底部导航」on there are two bands (the
 *     view tab strip above, the header row below) and each was handed the whole
 *     budget, so the timeline was paid for out of nothing;
 *  2. **the timeline's `contentPadding` outgrew its viewport.** The composer
 *     floats over the viewport and is paid back as bottom padding; a
 *     `LazyColumn` whose padding sums past its own height composes *no item at
 *     all*, so a few dp of viewport still meant a blank body.
 *
 * The Home half had the same shape one layer down: the mobile column's reserve
 * (`SESSIONS_SEARCH_RESERVE` = 48 against a 72 dp search block, and no count at
 * all for the list's 12 dp top pad) under-counted the chrome by 36 dp, so the
 * projects ceiling it derived was always 36 dp more generous than the card could
 * honour — and the utility nav, 72 dp *below* the rows, took the shortfall.
 */
class ShortWindowListV52Test {

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()?.replace("\r\n", "\n")
            ?: error("missing $relative")

    private val sessionScreen =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionScreen.kt")
    private val homeScreen =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeScreen.kt")

    /** The `LazyColumn` content padding's own top step (16 dp). */
    private val timelineTopPad = 16.dp

    /** The session pane's own bottom gap. */
    private val timelineBottomGap = 12.dp

    /** The composer at its intrinsic floor — the share the pane reserves. */
    private val composer = SessionPaneLayout.COMPOSER_MIN_HEIGHT

    /** What the timeline's `LazyColumn` is left with to compose items in. */
    private fun itemViewport(pane: Dp, chromeBands: Int = 2, composerHeight: Dp = composer): Dp {
        val chrome = SessionPaneLayout.chromeBandMaxHeight(pane, chromeBands) * chromeBands
        val viewport = pane - chrome
        val bottom = SessionPaneLayout.timelineContentBottom(viewport, composerHeight, timelineBottomGap)
        return viewport - timelineTopPad - bottom
    }

    // ------------------------------------------- (1) session pane: the chrome

    @Test
    fun `the chrome budget is spent once - the two bands share it`() {
        for (pane in listOf(180.dp, 250.dp, 300.dp, 342.dp, 400.dp, 600.dp, 900.dp)) {
            val oneBand = SessionPaneLayout.chromeMaxHeight(pane)
            val twoBands = SessionPaneLayout.chromeBandMaxHeight(pane, 2) * 2
            assertTrue(
                "pane $pane: two chrome bands took $twoBands of the $oneBand budget",
                twoBands <= oneBand,
            )
        }
    }

    @Test
    fun `a short pane still leaves the timeline its floor`() {
        // The v52 measurement: 1440x810 @420dpi is a 342 dp window, and the pane
        // is that minus the shell's bottom tab strip and the insets — 250 dp is
        // the worst of it, 300 dp the window itself.
        for (pane in listOf(300.dp, 342.dp, 400.dp, 500.dp, 700.dp)) {
            val chrome = SessionPaneLayout.chromeBandMaxHeight(pane, 2) * 2
            assertTrue(
                "pane $pane: timeline left ${pane - chrome - composer}",
                pane - chrome - composer >= SessionPaneLayout.TIMELINE_MIN_HEIGHT,
            )
        }
    }

    @Test
    fun `a single-band pane is unchanged - the ceiling is still inert on a healthy window`() {
        // The desktop path (and the mobile path with the header on top) mounts one
        // band, so the v51 numbers must not move.
        assertEquals(154.dp, SessionPaneLayout.chromeMaxHeight(342.dp))
        assertEquals(154.dp, SessionPaneLayout.chromeBandMaxHeight(342.dp, 1))
    }

    // ------------------------------- (2) session pane: the content padding

    @Test
    fun `the timeline keeps a message line whatever the composer covers`() {
        for (viewport in listOf(24.dp, 40.dp, 72.dp, 120.dp, 200.dp, 400.dp)) {
            for (composerHeight in listOf(0.dp, composer, 160.dp, 240.dp, 400.dp)) {
                val bottom = SessionPaneLayout.timelineContentBottom(
                    viewport,
                    composerHeight,
                    timelineBottomGap,
                )
                assertTrue(
                    "viewport $viewport, composer $composerHeight: padding $bottom + top $timelineTopPad " +
                        "past the viewport",
                    bottom + timelineTopPad <= viewport,
                )
                val items = viewport - timelineTopPad - bottom
                assertTrue(
                    "viewport $viewport, composer $composerHeight: $items left for items",
                    items > 0.dp || viewport <= timelineTopPad,
                )
            }
        }
    }

    @Test
    fun `a healthy timeline is untouched - the padding is only capped when it would eat the viewport`() {
        val viewport = 600.dp
        assertEquals(
            composer + timelineBottomGap,
            SessionPaneLayout.timelineContentBottom(viewport, composer, timelineBottomGap),
        )
        assertEquals(0.dp, SessionPaneLayout.timelineContentBottom(0.dp, composer, timelineBottomGap))
    }

    @Test
    fun `no viewport at all composes nothing - and none of the short windows is one`() {
        // The blank-body invariant end to end: on every pane the short-window
        // suite exercises, the `LazyColumn` has a positive area for items.
        for (pane in listOf(250.dp, 300.dp, 342.dp, 400.dp, 500.dp, 700.dp, 1200.dp)) {
            assertTrue(
                "pane $pane: ${
                    itemViewport(pane)
                } left for message items",
                itemViewport(pane) > 0.dp,
            )
        }
    }

    // ------------------------------------------------------------ wiring

    @Test
    fun `the pane splits its chrome ceiling over the bands it mounts`() {
        assertTrue(
            sessionScreen.contains(
                "chromeBands = if (titlebarBottom && !desktop) 2 else 1",
            ),
        )
    }

    @Test
    fun `the timeline's bottom padding is the capped one and the viewport is measured`() {
        assertTrue(sessionScreen.contains("SessionPaneLayout.timelineContentBottom("))
        assertTrue(sessionScreen.contains("var viewportHeightPx by remember { mutableIntStateOf(0) }"))
        // The raw expression must be gone — it is the one that summed past the
        // viewport.
        assertTrue(!sessionScreen.contains("bottom = composerHeight + TIMELINE_BOTTOM_GAP"))
    }

    // --------------------------------------------------------------- Home

    /** What the mobile column really spends on each band, at a card height. */
    private data class Column(
        val projects: Dp,
        val nav: Dp,
        val search: Dp,
        val list: Dp,
    )

    private fun column(card: Dp, editing: Boolean = false): Column {
        val listMin = HomeRowLayout.listMinHeight(card, editing)
        val projects = if (editing) {
            0.dp
        } else {
            HomeRowLayout.projectsDropdownMaxHeight(card, listMin)
        }
        val navMax = if (editing) 0.dp else HomeRowLayout.utilityNavMaxHeight(card, listMin)
        val nav = if (navMax > 0.dp) HomeRowLayout.UTILITY_NAV_MIN else 0.dp
        val search = HomeRowLayout.searchBlockHeight(card)
        val list = card - projects - nav - search - HomeRowLayout.LIST_TOP_PAD
        return Column(projects, nav, search, list)
    }

    @Test
    fun `the Home column never overflows its card at any height`() {
        for (card in listOf(120.dp, 131.dp, 150.dp, 185.dp, 220.dp, 300.dp, 438.dp, 640.dp, 790.dp, 900.dp)) {
            val c = column(card)
            assertTrue(
                "card $card: the column needs ${c.projects + c.nav + c.search + HomeRowLayout.LIST_TOP_PAD + HomeRowLayout.SESSION_ROW_HEIGHT}",
                c.projects + c.nav + c.search + HomeRowLayout.LIST_TOP_PAD +
                    HomeRowLayout.SESSION_ROW_HEIGHT <= card,
            )
        }
    }

    @Test
    fun `the Home rows keep at least one row on every card`() {
        for (card in listOf(120.dp, 131.dp, 150.dp, 185.dp, 220.dp, 300.dp, 438.dp, 640.dp, 900.dp)) {
            val c = column(card)
            assertTrue(
                "card $card: the list viewport is ${c.list}",
                c.list >= HomeRowLayout.SESSION_ROW_HEIGHT,
            )
        }
    }

    @Test
    fun `the utility nav yields on a card too short to pay for it and the rows`() {
        // The v52 card (≈131 dp): search block 48 + list floor 40 + the nav's 72
        // is 160 dp — more than the card has. The nav is chrome the shell can
        // reach elsewhere; the rows are the dead end if it is not.
        assertEquals(0.dp, HomeRowLayout.utilityNavMaxHeight(131.dp, HomeRowLayout.SESSION_ROW_HEIGHT))
        assertEquals(HomeRowLayout.UTILITY_NAV_MIN, HomeRowLayout.utilityNavMaxHeight(400.dp, HomeRowLayout.LIST_MIN_HEIGHT))
        assertEquals(0.dp, HomeRowLayout.utilityNavMaxHeight(0.dp, HomeRowLayout.LIST_MIN_HEIGHT))
    }

    @Test
    fun `the search block drops its top inset only when the card cannot pay for it`() {
        assertEquals(HomeRowLayout.MOBILE_SEARCH_BLOCK, HomeRowLayout.searchBlockHeight(400.dp))
        assertEquals(HomeRowLayout.SEARCH_FIELD_BLOCK, HomeRowLayout.searchBlockHeight(131.dp))
        assertEquals(24.dp, HomeRowLayout.searchTopInset(400.dp))
        assertEquals(0.dp, HomeRowLayout.searchTopInset(131.dp))
    }

    @Test
    fun `the projects reserve counts what the column really spends`() {
        // The v43/v47 reserve under-counted the search block by 24 dp and the
        // list's top pad by 12 — 36 dp of chrome the projects ceiling used to
        // promise the list and never delivered.
        val card = 400.dp
        val listMin = HomeRowLayout.listMinHeight(card)
        assertEquals(
            HomeRowLayout.searchBlockHeight(card) + HomeRowLayout.LIST_TOP_PAD + listMin +
                HomeRowLayout.UTILITY_NAV_MIN,
            HomeRowLayout.mobileColumnReserve(card, listMin),
        )
        assertTrue(HomeRowLayout.mobileColumnReserve(card, listMin) > 0.dp)
    }

    @Test
    fun `the Home host wires the two elastic bands`() {
        assertTrue(homeScreen.contains("val searchTopInset = HomeRowLayout.searchTopInset(cardHeight)"))
        assertTrue(
            homeScreen.contains(
                "HomeRowLayout.utilityNavMaxHeight(cardHeight, listMinHeight)",
            ),
        )
        assertTrue(homeScreen.contains("topInset = searchTopInset"))
        assertTrue(homeScreen.contains("if (utilityNavMaxHeight > 0.dp)"))
    }
}
