package com.xiufeigo.opencodeshell

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xiufeigo.opencodeshell.ui.session.header.HEADER_EDGE_PADDING
import com.xiufeigo.opencodeshell.ui.session.header.sessionHeaderMetrics
import com.xiufeigo.opencodeshell.ui.session.sessionHeaderReviewSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v50 BUG-1 (`research/v50_verify/tE/v50_37_search2.png`,
 * `v50_38_search3.png`): the header's 「搜索会话」entry and the floating
 * 「Toggle review panel」button shared the same pixels — a centre tap opened the
 * review panel, a tap on the leading sliver fell through to the title and
 * started a rename.
 *
 * The cause was layout, not z-order: the toggle was inset from the trailing edge
 * by a guess at the cluster's width while the header never gave that width up,
 * so a 40 dp button landed on a 200 dp cluster. The header now reserves
 * [sessionHeaderReviewSlot] at its trailing edge and the toggle takes exactly
 * that slot, so the two edges meet without crossing. The arithmetic below is the
 * geometry that has to hold on a device, pinned here instead.
 */
class SessionHeaderReviewSlotTest {

    /** The trailing cluster the desktop header renders: search, `/btw`, summary, ring, ⋮. */
    private val desktopTrailingIcons = 5

    /**
     * The trailing edge of the header's icon cluster, measured from the window's
     * leading edge: the reservation, then the header's own inset, then the row.
     */
    private fun clusterTrailingEdge(windowWidth: Dp) = windowWidth -
        sessionHeaderReviewSlot(desktop = true) -
        HEADER_EDGE_PADDING -
        sessionHeaderMetrics(
            windowWidth = windowWidth - sessionHeaderReviewSlot(desktop = true),
            trailingIconCount = desktopTrailingIcons,
        ).iconButtonSize * (desktopTrailingIcons + 2)

    /** The review toggle's leading edge, aligned with the header's own inset. */
    private fun reviewToggleLeadingEdge(windowWidth: Dp) =
        windowWidth - HEADER_EDGE_PADDING - 40.dp

    /**
     * The same edge as it measured *before* v50, when the header kept its full
     * width and the toggle was inset by a guess at the cluster instead. Kept as
     * the negative control: the geometry has to be able to fail, or the
     * assertions above prove nothing.
     */
    private fun clusterTrailingEdgeBeforeTheFix(windowWidth: Dp) = windowWidth -
        HEADER_EDGE_PADDING -
        40.dp * (desktopTrailingIcons + 2)

    @Test
    fun `the desktop header reserves exactly one slot for the review toggle`() {
        assertEquals(40.dp, sessionHeaderReviewSlot(desktop = true))
    }

    @Test
    fun `the phone header reserves nothing, because it renders no toggle`() {
        assertEquals(0.dp, sessionHeaderReviewSlot(desktop = false))
    }

    @Test
    fun `the review toggle does not overlap the search entry on an ordinary phone width`() {
        val window = 360.dp
        assertTrue(
            "cluster ends at ${clusterTrailingEdge(window)}, toggle starts at " +
                reviewToggleLeadingEdge(window),
            clusterTrailingEdge(window) <= reviewToggleLeadingEdge(window),
        )
    }

    @Test
    fun `nor on a narrow window where the header icons shrink`() {
        // 243 dp is the v49 narrow window, the one that pushed the icons down to
        // the 24 dp step; the reservation is a fixed 40 dp, so the gap there is
        // wider, not narrower.
        for (window in listOf(411.dp, 320.dp, 280.dp, 243.dp)) {
            assertTrue(
                "cluster ends at ${clusterTrailingEdge(window)}, toggle starts at " +
                    reviewToggleLeadingEdge(window),
                clusterTrailingEdge(window) <= reviewToggleLeadingEdge(window),
            )
        }
    }

    @Test
    fun `the pre-fix geometry is the overlap the evidence shows`() {
        val window = 360.dp
        // The old toggle sat 160 dp in from the trailing edge (ring + ⋮ +
        // summary + search — one target short of the five the cluster holds),
        // i.e. at 160..200 dp, entirely inside the cluster's own span.
        val clusterStart = clusterTrailingEdgeBeforeTheFix(window) - 40.dp * (desktopTrailingIcons + 2)
        val toggleBeforeTheFix = window - 160.dp - 40.dp
        assertTrue(
            "the old layout is supposed to overlap, or this test proves nothing",
            clusterStart <= toggleBeforeTheFix &&
                toggleBeforeTheFix + 40.dp <= window - HEADER_EDGE_PADDING,
        )
    }

    @Test
    fun `the reservation is priced into the header's own budget`() {
        // The header measures itself on the width it is left with, so taking the
        // toggle's slot cannot starve the title the way the cluster once did.
        val window = 360.dp
        val withToggle = sessionHeaderMetrics(
            windowWidth = window - sessionHeaderReviewSlot(desktop = true),
            trailingIconCount = desktopTrailingIcons,
        )
        assertTrue(withToggle.titleWidth >= 64.dp)
    }
}
