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
}
