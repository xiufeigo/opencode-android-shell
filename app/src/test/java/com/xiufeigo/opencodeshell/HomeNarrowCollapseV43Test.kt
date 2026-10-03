package com.xiufeigo.opencodeshell

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xiufeigo.opencodeshell.ui.home.HomeRowLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v43 P1 「极窄塌陷死局」, the height-allocation half: the mobile single column
 * is `projects dropdown` + `sessions column (weight 1f)` + `utility nav`, and
 * the projects dropdown is an unweighted `Column` of fixed-height rows. With
 * the keyboard up at `wm size 640x1500` + 420 dpi the dropdown used to claim
 * every dp it could, the list's weighted share clamped to **0 dp**, and the
 * rename editor that had just replaced its row became invisible and
 * unreachable — no scroll recovers a 0 dp viewport and a phone has no Esc
 * (`research/v43_verify/tC/v43_G3`, `G8`, `G9`, `G6`).
 *
 * The fix is the host ceiling: [HomeRowLayout.projectsDropdownMaxHeight] hands
 * the list its floor before the projects take anything, plus a scroll on the
 * dropdown so the projects that no longer fit stay reachable.
 */
class HomeNarrowCollapseV43Test {

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()?.replace("\r\n", "\n")
            ?: error("missing $relative")

    private val homeScreen = source("src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeScreen.kt")

    private fun available50(): Dp = 50.dp - HomeRowLayout.projectsDropdownMaxHeight(50.dp)

    /** What the sessions column is left with, given the dropdown's ceiling. */
    private fun listGets(available: Dp, nav: Dp = HomeRowLayout.UTILITY_NAV_MIN): Dp =
        available - HomeRowLayout.projectsDropdownMaxHeight(available) - nav

    @Test
    fun `a tall card is unaffected - the fractional ceiling governs`() {
        // 900 dp: the dropdown is a panel, not the page, so 40% applies and the
        // list keeps everything else.
        val available = 900.dp
        val projects = HomeRowLayout.projectsDropdownMaxHeight(available)
        assertEquals(360.dp, projects)
        assertTrue(listGets(available) > HomeRowLayout.LIST_MIN_HEIGHT)
    }

    @Test
    fun `a narrow card with the keyboard up still leaves the list its floor`() {
        // 640x1500 px at 420 dpi ≈ 243 x 571 dp; the card is 571 minus insets and
        // margins, and with the soft keyboard up it measures to a couple hundred.
        for (available in listOf(
            400.dp, 300.dp, 260.dp, 220.dp, 200.dp, 180.dp,
        )) {
            val projects = HomeRowLayout.projectsDropdownMaxHeight(available)
            assertTrue(
                "projects took $projects of $available",
                projects <= available,
            )
            // v45 P1: the floor is no longer a constant — it is derived from the
            // card, because a fixed one could not be paid for on a card this
            // short and an over-budget floor overflows the column instead of
            // protecting the list (see `HomeRenameLayoutV45Test`).
            assertTrue(
                "list got ${listGets(available)} at $available",
                listGets(available) >= HomeRowLayout.listMinHeight(available),
            )
        }
    }

    @Test
    fun `the projects area shrinks before the list does`() {
        // The whole point of the ceiling: with 6 projects (6 x 40 dp + the
        // 28 dp title row = 268 dp of unweighted content) at 200 dp available,
        // the projects give up first.
        val available = 200.dp
        assertTrue(HomeRowLayout.projectsDropdownMaxHeight(available) < 268.dp)
        // ... and what they give up is at least the search block + the list
        // floor. v44: on a card this short the sessions list also keeps its
        // [HomeRowLayout.SESSIONS_LIST_MIN_FRACTION] share, so the list lands
        // *above* the bare floor rather than exactly on it.
        assertTrue(listGets(available) >= HomeRowLayout.listMinHeight(available))
        // v45: 200 dp of card, the whole reserve (search 48 + list floor 68 +
        // nav 72 = 188) is what the list's side of the budget keeps, so the
        // projects - the part that scrolls - are down to the residual.
        assertEquals(128.dp, listGets(available))
    }

    @Test
    fun `an exhausted card degrades to zero projects rather than a zero list`() {
        // Below the budget the residual is 0: the list still gets the whole card
        // (≥ its floor as soon as the card itself is that big) and only the
        // projects - the part that can scroll - disappear.
        assertEquals(0.dp, HomeRowLayout.projectsDropdownMaxHeight(50.dp))
        assertEquals(0.dp, HomeRowLayout.projectsDropdownMaxHeight(0.dp))
        // 50 dp of card: every dp of it is the list's, and the utility nav is
        // what falls off the bottom — the projects, not the editing row.
        assertEquals(50.dp, available50())
        // The budget is not met below `reserved`, and never below 0.
        val budget = HomeRowLayout.SESSIONS_COLUMN_MIN + HomeRowLayout.UTILITY_NAV_MIN
        assertTrue(budget > HomeRowLayout.SESSIONS_COLUMN_MIN)
        assertEquals(0.dp, HomeRowLayout.projectsDropdownMaxHeight(budget - 1.dp))
    }

    @Test
    fun `the reserved budget is the search block plus the list floor plus the nav`() {
        assertEquals(68.dp, HomeRowLayout.LIST_MIN_HEIGHT)
        assertEquals(116.dp, HomeRowLayout.SESSIONS_COLUMN_MIN)
        // v45 P1: the search reserve and the nav reserve are the *measured*
        // heights (36 + 12 dp and 28 + 28 + 4 + 4 + 8 dp); the 44/60 the v43
        // budget carried under-counted both and the difference came out of the
        // list (`research/v45_verify/tC/v45_46/47`).
        assertEquals(188.dp, HomeRowLayout.SESSIONS_COLUMN_MIN + HomeRowLayout.UTILITY_NAV_MIN)
        assertEquals(0.4f, HomeRowLayout.PROJECTS_DROPDOWN_MAX_FRACTION, 0.0001f)
    }

    @Test
    fun `the mobile dropdown is capped and scrollable at the call site`() {
        // The ceiling is applied to the modifier HomeScreen hands the dropdown,
        // and a scroll keeps the projects that no longer fit reachable.
        assertTrue(
            homeScreen.contains("HomeRowLayout.projectsDropdownMaxHeight(cardHeight, listMinHeight)"),
        )
        assertTrue(homeScreen.contains(".verticalScroll(rememberScrollState())"))
        // ... and only on the mobile branch's dropdown, next to the list that
        // has the floor.
        val call = homeScreen.substring(homeScreen.indexOf("HomeProjectsDropdown("))
        assertTrue(call.contains("projectsDropdownMaxHeight"))
        assertTrue(call.contains("weight(1f)"))
    }
}
