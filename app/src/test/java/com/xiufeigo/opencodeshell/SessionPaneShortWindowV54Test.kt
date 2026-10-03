package com.xiufeigo.opencodeshell

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xiufeigo.opencodeshell.ui.session.SessionPaneLayout
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v54 P0 「短窗会话消息区零渲染（第三次）」: the same blank body as v51/v52 on
 * 1440x810 @420dpi, reproduced on emulator-5554 with the `LazyColumn`'s own
 * `layoutInfo` probed on device instead of inferred:
 *
 * ```
 * cp=PaddingValues(top=16, bottom=112) vh=1440x399 vpo=-294..105
 * vis=2 total=5 items=0:<idle>@0,0  1:<assistant>@63,256
 * pane=196.2dp  chrome=44.2  viewport=152  composer=112.4
 * ```
 *
 * Read off that probe:
 *
 *  * the pane is **196 dp**, not the 250 the v51/v52 comments assumed — the
 *    emulator's 46 dp status bar plus the shell's own strips take 146 of the
 *    308 dp window;
 *  * `chromeMaxHeight` held its 44 dp floor, so the viewport was 152 dp and the
 *    composer took 112 dp of it: the messages' physical share was 40 dp;
 *  * of that 40 dp, `contentPadding`'s **top 16 dp came out of the same box**
 *    (`viewport − top − bottom` = 152 − 16 − 112 = **24 dp**), because the v52
 *    floor subtracted [SessionPaneLayout.TIMELINE_MIN_CONTENT] from the
 *    *viewport* rather than from the item band;
 *  * the list is `reverseLayout`, so it composes **from the bottom edge up**:
 *    the band `[-294, +105]` is the bottom 40 dp, which held the tail of one
 *    256 dp row (its meta line, `Build · space-bunny-free · 3 秒`). The row's
 *    body and every older row sat under the composer's opaque background or
 *    were never composed — and when the newest row starts with padding or a
 *    tool chip, its tail 40 dp is blank too, which is the v52/v53 screenshot.
 *
 * Two budget changes follow, both in [SessionPaneLayout]:
 *
 *  * the composer's reserve yields so that [SessionPaneLayout.TIMELINE_MIN_CONTENT]
 *    (now one message line **with its meta row**, 56 dp) survives the top step —
 *    `timelineTopPadding` is the number v52 forgot;
 *  * a pane too short to pay for composer + chrome + that band takes the
 *    compact [SessionPaneLayout.CHROME_COMPACT_HEIGHT] instead of the 44 dp
 *    chrome floor, which is worth 12 dp of messages exactly where it is needed.
 *
 * On the measured pane that turns 24 dp of band into 56 dp (and the viewport
 * 152 → 164 dp); the invariant the whole thing rests on — *viewport > composer
 * ⇒ at least one row composes* — is `timelineItemBand` and is asserted here.
 */
class SessionPaneShortWindowV54Test {

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()?.replace("\r\n", "\n")
            ?: error("missing $relative")

    private val sessionScreen =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionScreen.kt")

    /** The pane the v54 device probe measured (1440x810 @420dpi, emulator-5554). */
    private val measuredPane = 196.2.dp

    /** The composer it measured on that same pane: 295 px = 112.4 dp. */
    private val measuredComposer = 112.4.dp

    private val gap = 12.dp

    // ------------------------------------------------- the short-window budget

    @Test
    fun `the measured short pane leaves a message band of a line and a half`() {
        val chrome = SessionPaneLayout.chromeMaxHeight(measuredPane)
        val viewport = measuredPane - chrome
        val band = SessionPaneLayout.timelineItemBand(viewport, measuredComposer, gap)
        assertEquals("chrome must yield below its 44 dp floor here", 32.dp, chrome)
        assertEquals(164.2.dp, viewport)
        // The v52/v53 numbers: a 24 dp band that composes nothing a reader can
        // see. The floor is now a line plus its meta row.
        assertTrue("band $band is not a row's worth", band >= SessionPaneLayout.TIMELINE_MIN_CONTENT)
        assertEquals(64.dp, band)
    }

    @Test
    fun `the composer's reserve never sums past the viewport`() {
        for (viewport in listOf(24.dp, 40.dp, 72.dp, 120.dp, 164.dp, 200.dp, 400.dp, 900.dp)) {
            for (composerHeight in listOf(0.dp, 60.dp, measuredComposer, 160.dp, 240.dp, 400.dp)) {
                val top = SessionPaneLayout.timelineTopPadding(viewport, composerHeight)
                val bottom = SessionPaneLayout.timelineContentBottom(viewport, composerHeight, gap)
                assertTrue(
                    "viewport $viewport, composer $composerHeight: padding $top+$bottom past $viewport",
                    top + bottom <= viewport,
                )
            }
        }
    }

    // ------------------------------------------- the composition invariant

    @Test
    fun `a viewport taller than the composer always composes a band`() {
        // 「viewport>0 ⇒ 至少一个 item 组合」: the pane's own numbers, swept.
        for (pane in listOf(150.dp, 170.dp, measuredPane, 216.dp, 250.dp, 342.dp, 500.dp, 900.dp)) {
            for (chromeBands in listOf(1, 2)) {
                val chrome =
                    SessionPaneLayout.chromeBandMaxHeight(pane, chromeBands) * chromeBands
                val viewport = pane - chrome
                for (composerHeight in listOf(0.dp, 60.dp, 112.4.dp, 160.dp, 240.dp)) {
                    if (viewport <= composerHeight) continue
                    val band = SessionPaneLayout.timelineItemBand(viewport, composerHeight, gap)
                    assertTrue(
                        "pane $pane, $chromeBands band(s): viewport $viewport, composer " +
                            "$composerHeight ⇒ band $band",
                        band > 0.dp,
                    )
                }
            }
        }
    }

    @Test
    fun `the band is a row and a half, never a sliver, whenever the composer fits`() {
        for (viewport in listOf(80.dp, 120.dp, 164.dp, 200.dp, 260.dp, 400.dp, 900.dp)) {
            for (composerHeight in listOf(0.dp, 60.dp, 112.4.dp, 160.dp)) {
                if (viewport <= composerHeight) continue
                val band = SessionPaneLayout.timelineItemBand(viewport, composerHeight, gap)
                assertTrue(
                    "viewport $viewport, composer $composerHeight: band $band is a sliver",
                    band >= SessionPaneLayout.TIMELINE_MIN_CONTENT,
                )
            }
        }
    }

    @Test
    fun `no viewport is still no band - and none of the panes the suite walks is one`() {
        assertEquals(0.dp, SessionPaneLayout.timelineItemBand(0.dp, measuredComposer, gap))
        assertEquals(0.dp, SessionPaneLayout.timelineItemBand(0.dp, 0.dp, gap))
        assertEquals(0.dp, SessionPaneLayout.timelineContentBottom(0.dp, measuredComposer, gap))
        for (pane in listOf(196.2.dp, 250.dp, 342.dp, 500.dp, 900.dp)) {
            val viewport = pane - SessionPaneLayout.chromeMaxHeight(pane)
            assertTrue("pane $pane: viewport $viewport", viewport > 0.dp)
        }
    }

    // ------------------------------------------------ the top step (v52's gap)

    @Test
    fun `the top step drops to the compact one exactly when the band needs it`() {
        // A healthy window keeps the 16 dp gap above the oldest row.
        assertEquals(
            SessionPaneLayout.TIMELINE_TOP_PADDING,
            SessionPaneLayout.timelineTopPadding(600.dp, SessionPaneLayout.COMPOSER_MIN_HEIGHT),
        )
        // The measured short pane: 164.2 − 112.4 = 51.8 dp, less than the band
        // plus the 16 dp gap, so the gap cannot also be paid for.
        assertEquals(
            SessionPaneLayout.TIMELINE_TOP_PADDING_COMPACT,
            SessionPaneLayout.timelineTopPadding(164.2.dp, measuredComposer),
        )
        assertEquals(0.dp, SessionPaneLayout.timelineTopPadding(0.dp, measuredComposer))
    }

    // --------------------------------------------------- the chrome's floor

    @Test
    fun `the chrome floor gives way only below the pane that cannot afford it`() {
        // Above `composer + chrome + a message band` the v51/v52 numbers stand:
        // the single-band desktop path is untouched (154 dp at a 342 dp pane).
        assertEquals(154.dp, SessionPaneLayout.chromeMaxHeight(342.dp))
        assertEquals(62.dp, SessionPaneLayout.chromeMaxHeight(250.dp))
        // Below it the band is compacted — the header row scrolls.
        assertEquals(32.dp, SessionPaneLayout.chromeMaxHeight(measuredPane))
        assertEquals(32.dp, SessionPaneLayout.chromeMaxHeight(180.dp))
        // A pane that cannot even hold the composer still gets no chrome.
        assertEquals(0.dp, SessionPaneLayout.chromeMaxHeight(100.dp))
        assertEquals(0.dp, SessionPaneLayout.chromeMaxHeight(0.dp))
    }

    @Test
    fun `the two chrome bands still share one budget`() {
        for (pane in listOf(180.dp, 196.2.dp, 250.dp, 342.dp, 600.dp, 900.dp)) {
            val one = SessionPaneLayout.chromeMaxHeight(pane)
            val two = SessionPaneLayout.chromeBandMaxHeight(pane, 2) * 2
            assertTrue("pane $pane: two bands took $two of $one", two <= one)
        }
    }

    // --------------------------------------------------------------- wiring

    @Test
    fun `the screen routes both padding steps through the budget`() {
        // The gap above the oldest row is the v52 miss: a hard-coded 16 dp made
        // the item band 24 dp on the measured pane.
        assertTrue(sessionScreen.contains("SessionPaneLayout.timelineTopPadding("))
        assertTrue(sessionScreen.contains("SessionPaneLayout.timelineContentBottom("))
        assertTrue(
            "the timeline's top step must not be a literal 16 dp again",
            !sessionScreen.contains("top = 16.dp,"),
        )
    }

    @Test
    fun `the viewport the budget reads is the one the timeline is measured in`() {
        // v77 P0: the measurement is px, the dp the budget reads is derived from
        // it with the live density (see `SessionPaneImeBeliefV77Test`).
        assertTrue(sessionScreen.contains("var viewportHeightPx by remember { mutableIntStateOf(0) }"))
        assertTrue(sessionScreen.contains("val viewportHeight: Dp = with(density) { viewportHeightPx.toDp() }"))
        // v76: the measured node is the `Box` `SessionPaneArrangement` wraps the
        // viewport in, and the callback it reports through is the pane's own — one
        // node, whichever arrangement the pane is in.
        assertTrue(sessionScreen.contains("Box(Modifier.fillMaxSize().onSizeChanged(onViewportSize)) { viewport() }"))
        assertTrue(
            sessionScreen.contains(
                "onViewportSize = { viewportHeightPx = it.height },",
            ),
        )
    }
}
