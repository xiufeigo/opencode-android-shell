package com.xiufeigo.opencodeshell

import androidx.compose.ui.unit.dp
import com.xiufeigo.opencodeshell.ui.session.SessionPaneLayout
import com.xiufeigo.opencodeshell.ui.session.header.HEADER_HEIGHT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v58 P0 「短窗消息列表底部无内边距 / sticky 条切掉列表顶部」, measured on
 * emulator-5554 at 720x1280 + density 420 (2.625 px/dp):
 *
 * ```
 * no IME   body 325.7 dp · chrome 84.6 dp · pane 241.1 dp · composer 58.1 dp
 *          reserve 74 dp = composer 58.1 + gap 12  · band 151 dp
 * IME up   pane 162.7 dp · composer 58.1 dp · reserve 70 dp · band 64 dp
 * ```
 *
 * Both states already reserve the composer's own height plus the 12 dp gap, so
 * the two defects locked here are the two that the arithmetic above does *not*
 * cover: the gap the **shared** form pays (4 dp, i.e. the last line's descender
 * 4 dp above the composer — the「紧贴 composer」of `47_kb3.png`), and the pinned
 * row's inset, which was the `HEADER_HEIGHT` constant rather than the bar's
 * measured height even though the bar is painted **over** the list.
 */
class ShortWindowTimelinePadV58Test {

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()?.replace("\r\n", "\n")
            ?: error("missing $relative")

    private val sessionPaneLayout =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionPaneLayout.kt")
    private val messageTimeline =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/timeline/MessageTimeline.kt")

    // ------------------------------------------------------- the shared gap

    @Test
    fun `the shared form pays the same trailing gap as the floating one`() {
        // 4 dp read as「正文紧贴 composer」: the gap below the last row was the
        // one place in the list with less clearance than the composer's own
        // padding, and it is the arrangement a short window actually picks.
        assertEquals(12.dp, SessionPaneLayout.TIMELINE_BOTTOM_GAP_SHARED)
    }

    @Test
    fun `a shared pane still composes a row after the bigger gap`() {
        // The measured IME-up pane: 162.7 dp with a 58.1 dp composer shares
        // (162.7 - 58.1 = 104.6 dp, which is over the floor) — and still leaves
        // more than a line of band with the 12 dp gap.
        val pane = 162.7.dp
        val composer = 58.1.dp
        val viewport = pane - composer
        val band = viewport -
            SessionPaneLayout.timelineTopPadding(viewport, composer) -
            SessionPaneLayout.TIMELINE_BOTTOM_GAP_SHARED
        assertTrue("band $band on a $pane pane", band >= 40.dp)
    }

    // ------------------------------------------------------ the pinned row

    @Test
    fun `the list inset is the pinned row's own measured height`() {
        assertEquals(HEADER_HEIGHT, SessionPaneLayout.headerInset(0.dp))
        assertEquals(HEADER_HEIGHT, SessionPaneLayout.headerInset(HEADER_HEIGHT))
        // The failing shape: a bar taller than the constant used to paint over
        // the newest row, which could not then be scrolled out of it.
        val tall = HEADER_HEIGHT + 12.dp
        assertEquals(tall, SessionPaneLayout.headerInset(tall))
        // A shorter bar must not keep a hole the pane cannot spare.
        val short = HEADER_HEIGHT - 12.dp
        assertEquals(short, SessionPaneLayout.headerInset(short))
    }

    @Test
    fun `the inset never falls below what the bar paints`() {
        for (measured in listOf(1.dp, 8.dp, 24.dp, HEADER_HEIGHT, 64.dp, 96.dp)) {
            assertTrue(
                "inset ${SessionPaneLayout.headerInset(measured)} under a $measured bar",
                SessionPaneLayout.headerInset(measured) >= measured,
            )
        }
    }

    // ------------------------------------------------------------- the wiring

    @Test
    fun `the timeline measures its pinned row instead of assuming the constant`() {
        val source = messageTimeline
        assertTrue(
            "the pinned row must report its height",
            source.contains("stickyHeaderPx = it.height"),
        )
        assertTrue(
            "the inset must come from the measurement",
            source.contains("SessionPaneLayout.headerInset(with(density) { stickyHeaderPx.toDp() })"),
        )
        // v77 P0: the state is the raw measurement now (px) — a zero reading is
        // the "not measured yet" first frame, and `headerInset` is what turns it
        // back into the constant (asserted above).
        assertTrue(
            "the constant may only seed the first frame",
            source.contains("var stickyHeaderPx by remember { mutableIntStateOf(0) }"),
        )
    }

    @Test
    fun `the shared gap is documented where it is declared`() {
        val source = sessionPaneLayout
        assertTrue(
            source.contains("val TIMELINE_BOTTOM_GAP_SHARED: Dp = 12.dp"),
        )
    }
}
