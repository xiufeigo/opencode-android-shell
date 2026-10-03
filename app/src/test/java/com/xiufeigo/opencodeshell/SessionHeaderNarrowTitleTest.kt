package com.xiufeigo.opencodeshell

import androidx.compose.ui.unit.dp
import com.xiufeigo.opencodeshell.ui.session.header.sessionHeaderMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v49 BUG-1 (`research/v49_verify/tC/v49_37_narrow_home.png`): the session
 * header's title is how the user tells which session is open, and at
 * 640x1500 @420dpi (~243 dp wide) it collapsed to 53 px — one ellipsised
 * character — because the six fixed 40 dp targets of the trailing cluster
 * claimed the row before `weight(1f)` got anything.
 *
 * [sessionHeaderMetrics] is the pure half of the fix, so the narrow-window
 * arithmetic is pinned here rather than on a device.
 */
class SessionHeaderNarrowTitleTest {

    /** The v49 device window: 640 px at 420 dpi = 243.8 dp, half a phone. */
    private val v49Window = 243.dp

    /** Leading back + drawer, plus the ring and ⋮. */
    private val phoneTrailing = 3

    @Test
    fun `the v49 narrow window still leaves the title a readable width`() {
        val metrics = sessionHeaderMetrics(v49Window, phoneTrailing)
        assertTrue(
            "title collapsed to ${metrics.titleWidth} at $v49Window",
            metrics.titleWidth >= 64.dp,
        )
        // …and the row still fits: nothing is pushed off the trailing edge.
        assertEquals(v49Window, metrics.titleWidth + metrics.iconButtonSize * (phoneTrailing + 2))
    }

    @Test
    fun `an ordinary phone width keeps the full 40 dp touch target`() {
        val metrics = sessionHeaderMetrics(360.dp, phoneTrailing)
        assertEquals(40.dp, metrics.iconButtonSize)
        assertEquals(360.dp - (5 * 40).dp, metrics.titleWidth)
    }

    @Test
    fun `a desktop window leaves the title everything the cluster does not take`() {
        val metrics = sessionHeaderMetrics(1200.dp, 5)
        assertEquals(40.dp, metrics.iconButtonSize)
        assertEquals(1200.dp - (7 * 40).dp, metrics.titleWidth)
    }

    @Test
    fun `a wider cluster only shrinks once the 40 dp step stops fitting`() {
        // 300 dp with nine icons total: 9*40 = 360 already overflows, 9*32 = 288
        // leaves 12 dp, so the row steps to 24 and the title gets 84 dp back.
        val metrics = sessionHeaderMetrics(300.dp, 7)
        assertEquals(24.dp, metrics.iconButtonSize)
        assertEquals(84.dp, metrics.titleWidth)
    }

    @Test
    fun `a window too narrow for any step keeps the buttons and gives the title the rest`() {
        val metrics = sessionHeaderMetrics(120.dp, 5)
        assertEquals(24.dp, metrics.iconButtonSize)
        // Negative space cannot be laid out, so the title reports 0 rather than
        // a width that would push the ring and ⋮ off the window.
        assertEquals(0.dp, metrics.titleWidth)
    }
}
