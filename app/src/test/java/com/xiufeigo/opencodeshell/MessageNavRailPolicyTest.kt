package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.timeline.MessageNavRailPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A5 — rail geometry of the compact `MessageNav`
 * (`_upstream/packages/session-ui/src/components/message-nav.css:14-59`): a tick
 * is `12px × 24px` with a `1px × 16px` line, and the rail must not spill out of
 * its viewport when a long session produces many ticks.
 */
class MessageNavRailPolicyTest {

    @Test
    fun aShortRailKeepsTheNativeTickHeight() {
        // 10 ticks at the native 12 dp need 120 dp; a 240 dp viewport is enough.
        assertEquals(12f, MessageNavRailPolicy.tickHeightDp(10, 240f), 0.001f)
    }

    @Test
    fun aLongRailShrinksItsTicks() {
        assertEquals(6f, MessageNavRailPolicy.tickHeightDp(40, 240f), 0.001f)
        assertEquals(4f, MessageNavRailPolicy.tickHeightDp(60, 240f), 0.001f)
    }

    @Test
    fun theTickNeverShrinksBelowTheFloor() {
        assertEquals(
            MessageNavRailPolicy.MIN_TICK_HEIGHT_DP,
            MessageNavRailPolicy.tickHeightDp(1000, 100f),
            0.001f,
        )
        assertEquals(
            MessageNavRailPolicy.MIN_TICK_HEIGHT_DP,
            MessageNavRailPolicy.tickHeightDp(5, 0f),
            0.001f,
        )
    }

    @Test
    fun anEmptyRailUsesTheNativeHeight() {
        assertEquals(12f, MessageNavRailPolicy.tickHeightDp(0, 240f), 0.001f)
    }

    @Test
    fun scrollingIsOnlyNeededWhenTheNativeTicksDoNotFit() {
        assertFalse(MessageNavRailPolicy.needsScroll(10, 240f))
        assertTrue(MessageNavRailPolicy.needsScroll(30, 240f))
        assertFalse(MessageNavRailPolicy.needsScroll(0, 240f))
        assertFalse(MessageNavRailPolicy.needsScroll(5, 0f))
    }

    /**
     * v43: a one-turn session drew the rail's active tick and nothing else — the
     * full-rail `2 dp` `textBase` bar — so the timeline showed a contentless
     * black dash at the trailing edge
     * (`research/v43_verify/tE/v43_15_sent.png`, `v43_49_err3.png`).
     */
    @Test
    fun aRailWithNothingToNavigateBetweenStaysHidden() {
        assertFalse(MessageNavRailPolicy.isVisible(0))
        assertFalse(MessageNavRailPolicy.isVisible(1))
    }

    @Test
    fun twoTicksAreEnoughForTheRailToComeBack() {
        assertTrue(MessageNavRailPolicy.isVisible(2))
        assertTrue(MessageNavRailPolicy.isVisible(37))
    }

    /**
     * v63: the rail is an overlay on the list's trailing edge, and the timeline
     * reserves this width (plus the scrollbar strip in front of it) as a gutter
     * while the rail is visible, so an end-aligned row — the user message's
     * meta/copy row above all — never slides under the ticks, which read as a
     * 「≣」 stain pinned on the copy icon
     * (`research/v63_verify/5554/50_find_closed2.png`). The value is upstream's
     * `[data-size=compact] width: 24px` (`message-nav.css:14-16`), the column
     * `share/[shareID].tsx:276-281` gives the rail as a real layout sibling.
     */
    @Test
    fun theRailWidthMatchesTheUpstreamCompactRail() {
        assertEquals(24f, MessageNavRailPolicy.RAIL_WIDTH_DP, 0.001f)
    }
}
