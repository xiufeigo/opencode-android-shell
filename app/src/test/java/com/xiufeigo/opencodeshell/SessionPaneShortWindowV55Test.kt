package com.xiufeigo.opencodeshell

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xiufeigo.opencodeshell.ui.session.SessionPaneLayout
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v55 P0 「短窗消息区零渲染（第四次）」: the v51/v52/v54 budget all said the right
 * thing about a band the reader never saw. This suite is written against the
 * `u*.xml` a11y dumps of the v54 run instead of against the budget —
 * 1440x810 @420dpi, 2.625 px/dp, `research/v54_verify/tE`:
 *
 * ```
 * session column    [0,299][1440,684] = 146.7 dp
 *   chrome band     [0,299][1440,380] =  30.9 dp  (header row; the tab strip is
 *                                                 clipped out of it)
 *   timeline pane   [0,380][1440,684] = 115.8 dp  ← the LazyColumn
 *     composer      [0,413][1440,684] = 103.2 dp  ← opaque, BottomCenter
 * message row       [42,389][1309,515]           ← 1267 px wide: the horizontal
 *                                                    constraint was never wrong
 * nav rail ticks    [1309,380][1435,538] = 126 px ← the "126 px narrow strip"
 *                                                    (MessageNavRail, trailing)
 * ```
 *
 * Read off those numbers:
 *
 *  * the 126 px strip is the **nav rail**, not a crushed message row, so the
 *    width side of the v54 note was a misread;
 *  * `contentPadding`'s bottom reserve was capped at
 *    `115.8 − 4 − 64 = 47.8 dp` while the composer it reserves room for is
 *    **103.2 dp** tall, so the 64 dp band the cap bought lay *entirely inside*
 *    the composer's opaque rectangle — a positive band nobody can see, i.e. the
 *    blank body of `v54_38_short_clean.png`.
 *
 * The fix is not another padding number: a floating opaque composer and a
 * message band are mutually exclusive, so the pane picks one
 * ([SessionPaneLayout.composerSharesViewport]) and the composer is laid out
 * below the timeline when the overlay would swallow the band.
 */
class SessionPaneShortWindowV55Test {

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()?.replace("\r\n", "\n")
            ?: error("missing $relative")

    private val sessionScreen =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionScreen.kt")

    private val composer =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/Composer.kt")

    /** The session column the v54 dumps measured: 385 px @ 2.625 px/dp. */
    private val measuredPane = 146.7.dp

    /** The composer on that same frame: 271 px = 103.2 dp (compact: 96 dp). */
    private val measuredComposer = 103.2.dp

    private val compactComposer = SessionPaneLayout.COMPOSER_COMPACT_HEIGHT

    private val gap = 12.dp

    /** What the screen lays out for a pane, in the order it lays it out. */
    private fun visibleBand(pane: Dp): Dp {
        val ceiling = SessionPaneLayout.chromeMaxHeightForComposer(pane, compactComposer)
        val timelinePane = pane - ceiling
        return SessionPaneLayout.visibleItemBand(timelinePane, compactComposer, gap)
    }

    // ------------------------------------- the invariant the last three missed

    @Test
    fun `the measured short pane shows messages again`() {
        // The v54 numbers: chrome 30.9, a 115.8 dp viewport, a 103.2 dp composer
        // painted over its bottom 89 % — and a blank body.
        val ceiling = SessionPaneLayout.chromeMaxHeightForComposer(
            measuredPane,
            measuredComposer,
        )
        val viewport = measuredPane - ceiling
        assertEquals(
            "the chrome kept a band the composer covered",
            0.dp,
            ceiling,
        )
        assertEquals(measuredPane, viewport)
        assertTrue(
            "the composer floats over a $viewport dp pane",
            SessionPaneLayout.composerSharesViewport(viewport, compactComposer),
        )
        assertTrue(
            "band ${visibleBand(measuredPane)} is nothing a reader can use",
            visibleBand(measuredPane) >= 24.dp,
        )
    }

    @Test
    fun `the visible band never depends on a band under the composer`() {
        // Every pane, both composer heights, both arrangements: whatever
        // `visibleItemBand` reports is area no opaque pixel covers.
        for (pane in listOf(60.dp, 100.dp, 123.dp, measuredPane, 196.2.dp, 250.dp, 342.dp, 700.dp)) {
            for (composerHeight in listOf(0.dp, 60.dp, compactComposer, measuredComposer, 200.dp)) {
                val viewport = pane -
                    SessionPaneLayout.chromeMaxHeightForComposer(pane, composerHeight)
                val band = SessionPaneLayout.visibleItemBand(viewport, composerHeight, gap)
                if (SessionPaneLayout.composerSharesViewport(viewport, composerHeight)) {
                    // The composer is a sibling of the list: it can only ever
                    // take its own height out of the pane, never the viewport's.
                    assertTrue(
                        "pane $pane, composer $composerHeight: band $band past the viewport",
                        band <= (viewport - composerHeight).coerceAtLeast(0.dp),
                    )
                } else {
                    // Floating: the band is the top of the viewport and has to
                    // end where the composer's opaque rectangle begins.
                    assertTrue("pane $pane, composer $composerHeight: band $band", band >= 0.dp)
                    val bandBottom = viewport - SessionPaneLayout.timelineContentBottom(
                        viewport,
                        composerHeight,
                        gap,
                    )
                    assertTrue(
                        "pane $pane, composer $composerHeight: band $band is covered",
                        bandBottom <= viewport - composerHeight,
                    )
                }
            }
        }
    }

    @Test
    fun `the floating arrangement always clears the composer by construction`() {
        // The band is the top of the viewport (`viewport − top − bottom`) and the
        // composer starts at `viewport − composer`, so the two only overlap if
        // the composer is thinner than `top + TIMELINE_MIN_CONTENT` — which no
        // composer this shell builds ever is.
        for (viewport in listOf(200.dp, 300.dp, 500.dp, 900.dp)) {
            for (composerHeight in listOf(60.dp, 96.dp, compactComposer, 116.dp, 200.dp)) {
                if (SessionPaneLayout.composerSharesViewport(viewport, composerHeight)) continue
                val band = SessionPaneLayout.timelineItemBand(viewport, composerHeight, gap)
                val bandBottom = viewport - SessionPaneLayout.timelineContentBottom(
                    viewport,
                    composerHeight,
                    gap,
                )
                assertTrue(
                    "viewport $viewport, composer $composerHeight: band $band overlaps",
                    bandBottom <= viewport - composerHeight,
                )
                assertTrue("viewport $viewport: band $band", band >= 0.dp)
            }
        }
    }

    @Test
    fun `the sharing decision cannot oscillate`() {
        // Sharing only ever shrinks the viewport, and a shrunken viewport can
        // only share harder — so one flip is all there is.
        for (pane in listOf(60.dp, 100.dp, 146.7.dp, 196.2.dp, 250.dp, 342.dp, 700.dp)) {
            for (composerHeight in listOf(60.dp, 96.dp, compactComposer, 116.dp, 200.dp)) {
                val viewport = pane -
                    SessionPaneLayout.chromeMaxHeightForComposer(pane, composerHeight)
                val shared = SessionPaneLayout.composerSharesViewport(viewport, composerHeight)
                if (!shared) continue
                val next = viewport - composerHeight
                assertTrue(
                    "pane $pane, composer $composerHeight: $next dp flipped back to floating",
                    SessionPaneLayout.composerSharesViewport(next, composerHeight),
                )
            }
        }
    }

    // ---------------------------------------------- the chrome's last band (v55)

    @Test
    fun `the chrome yields entirely when it can only buy a hidden band`() {
        // 146.7 dp pane: the chrome cannot pay for itself — 30.9 dp of clipped
        // header row, no tab strip, and the messages nothing at all.
        assertEquals(
            0.dp,
            SessionPaneLayout.chromeMaxHeightForComposer(measuredPane, compactComposer),
        )
        assertEquals(
            0.dp,
            SessionPaneLayout.chromeMaxHeightForComposer(measuredPane, measuredComposer),
        )
        // Just above it, the band is affordable and the compact chrome stays.
        assertEquals(
            SessionPaneLayout.CHROME_COMPACT_HEIGHT,
            SessionPaneLayout.chromeMaxHeightForComposer(180.dp, SessionPaneLayout.COMPOSER_MIN_HEIGHT),
        )
    }

    @Test
    fun `yielding the chrome is bounded - it never buys back less than it cost`() {
        // Below the compact chrome the ceiling was already under the floor, and
        // every dp taken there is a dp the messages would not have got anyway.
        // (Below the composer's own height nothing fits either way.)
        for (pane in listOf(120.dp, 123.dp, 140.dp, 160.dp)) {
            val ceiling = SessionPaneLayout.chromeMaxHeightForComposer(
                pane,
                SessionPaneLayout.COMPOSER_MIN_HEIGHT,
            )
            assertTrue(
                "pane $pane: chrome $ceiling is worth more than the band it cost",
                pane - ceiling >= SessionPaneLayout.COMPOSER_MIN_HEIGHT,
            )
        }
    }

    @Test
    fun `every pinned v51 v52 v54 chrome number still holds`() {
        // The default composer is unchanged, so the single-band desktop path and
        // the v52/v54 tables are untouched by the v55 pass.
        assertEquals(154.dp, SessionPaneLayout.chromeMaxHeight(342.dp))
        assertEquals(62.dp, SessionPaneLayout.chromeMaxHeight(250.dp))
        assertEquals(32.dp, SessionPaneLayout.chromeMaxHeight(196.2.dp))
        assertEquals(32.dp, SessionPaneLayout.chromeMaxHeight(180.dp))
        assertEquals(0.dp, SessionPaneLayout.chromeMaxHeight(100.dp))
        assertEquals(0.dp, SessionPaneLayout.chromeMaxHeight(0.dp))
        assertEquals(112.dp, SessionPaneLayout.chromeMaxHeight(300.dp))
        for (pane in listOf(180.dp, 196.2.dp, 250.dp, 342.dp, 600.dp, 900.dp)) {
            assertTrue(
                "pane $pane",
                SessionPaneLayout.chromeBandMaxHeight(pane, 2) * 2 <=
                    SessionPaneLayout.chromeMaxHeight(pane),
            )
        }
    }

    // -------------------------------------------------- the compact composer

    @Test
    fun `only a pane that cannot carry the full composer compacts it`() {
        assertTrue(SessionPaneLayout.composerCompact(measuredPane))
        assertTrue(SessionPaneLayout.composerCompact(196.2.dp))
        assertTrue(!SessionPaneLayout.composerCompact(250.dp))
        assertTrue(!SessionPaneLayout.composerCompact(700.dp))
        // v156: this line used to be `assertTrue(!composerCompact(0.dp))` — the same
        // lower-bound guard v154 removed from `composerMinimal`, in the *second* form
        // predicate (`SessionPaneLayout.kt:751`, threshold 224 instead of 164). It sat
        // here as a trailing lower-bound guard, not on the 223/224 staircase above, and
        // it pinned the defect itself: the room that can least afford even the compact
        // composer was told it did not need compacting. On the v155 device range that
        // is the flip at window 588 (room = box - ime = 0), between the 600 and the 580
        // the round sampled. The staircase assertions above (196.2 compact, 250 and 700
        // full) are untouched.
        //
        // STRENGTH, not relaxation: the old line asserted one negative single-point
        // value; this asserts the whole initial-segment property — every room from 0 up
        // to the staircase compacts, and only 224 dp and above stops.
        assertTrue(
            "a room of 0 dp can least of all carry the full composer, so it must still " +
                "take the compact one — the truth set must be the initial segment {0 .. 223}",
            SessionPaneLayout.composerCompact(0.dp),
        )
        for (room in 0..223) {
            assertTrue(
                "room $room dp is below the 224 dp staircase, so it must compact",
                SessionPaneLayout.composerCompact(room.dp),
            )
        }
        assertTrue(
            "the staircase is untouched: 224 dp and above carries the full composer",
            !SessionPaneLayout.composerCompact(224.dp),
        )
    }

    @Test
    fun `the compact composer still carries its whole tool row`() {
        // The editor is what gives; ⊞ / 模型 / Send keep their 44 dp, because a
        // compressed tool row is the v51 bug.
        assertEquals(40.dp, SessionPaneLayout.COMPOSER_EDITOR_COMPACT_HEIGHT)
        assertEquals(
            SessionPaneLayout.COMPOSER_EDITOR_COMPACT_HEIGHT + 44.dp + 12.dp,
            compactComposer,
        )
        assertTrue(composer.contains(".height(TOOL_ROW_HEIGHT)"))
        assertTrue(composer.contains("if (compact) EDITOR_MIN_HEIGHT_COMPACT else EDITOR_MIN_HEIGHT"))
    }

    // ---------------------------------------------------------------- wiring

    @Test
    fun `the pane really has both arrangements`() {
        assertTrue(
            "the composer must be able to leave the timeline's viewport",
            sessionScreen.contains("SessionPaneLayout.composerSharesViewport("),
        )
        assertTrue(
            "the shared form reserves no composer height",
            sessionScreen.contains(
                "SessionPaneLayout.TIMELINE_BOTTOM_GAP_SHARED",
            ),
        )
        assertTrue(
            "the decision must read the pane, not the viewport",
            sessionScreen.contains(
                "SessionPaneLayout.composerSharesViewport(\n                paneHeight,\n                composerHeight,\n            )",
            ),
        )
    }

    @Test
    fun `the composer still mounts once, whatever the arrangement`() {
        // The two arrangements are one `Layout`'s two children now (`v76`: the
        // flip re-parents nothing), so the composer has exactly one mount point —
        // reached through the movable-content slot, which is what keeps the
        // *mobile tab switch* from rebuilding the editor (and its IME session).
        assertEquals(1, Regex("""composerSlot\(composerRegion\)""").findAll(sessionScreen).count())
        assertEquals(1, Regex("""movableContentOf<""").findAll(sessionScreen).count())
        assertTrue(sessionScreen.contains("val composerRegion: @Composable () -> Unit = {"))
        assertTrue(
            sessionScreen.contains(
                "val composerSlot: @Composable (@Composable () -> Unit) -> Unit = remember {",
            ),
        )
        assertTrue(sessionScreen.contains("contentAlignment = Alignment.BottomCenter"))
    }

    @Test
    fun `the shared composer may not grow into the band`() {
        // An auto-growing editor with a long draft would otherwise take the
        // pane's last dp and put the messages back under the composer. v76: the
        // cap is computed once per frame from the pane and rides the host's own
        // modifier inside `SessionPaneArrangement` (the flip changes the
        // constraints, never the parent).
        assertTrue(
            sessionScreen.contains(
                "val composerCeiling = (paneHeight - SessionPaneLayout.TIMELINE_MIN_CONTENT)",
            ),
        )
        assertTrue(sessionScreen.contains("composerCeiling = composerCeiling"))
        assertTrue(sessionScreen.contains("heightIn(max = composerCeiling)"))
        assertTrue(
            sessionScreen.contains(
                "SessionPaneLayout.COMPOSER_COMPACT_HEIGHT",
            ),
        )
        // …and the floor it keeps is one the band can live beside. v57 P0: the
        // floor is the cheapest composer the pane can lay out — the one-row
        // `composerMinimal` form below 164 dp, the stacked 96 dp above it (see
        // `SessionPaneShortWindowV57Test`).
        for (pane in listOf(120.dp, measuredPane, 196.2.dp, 250.dp, 342.dp)) {
            val ceiling = SessionPaneLayout.composerCeiling(pane)
            assertTrue("pane $pane: ceiling $ceiling", ceiling <= pane && ceiling > 0.dp)
            assertTrue(
                "pane $pane: ceiling $ceiling leaves the band nothing",
                pane - ceiling >= SessionPaneLayout.TIMELINE_MIN_CONTENT ||
                    SessionPaneLayout.composerMinimal(pane),
            )
        }
    }

    @Test
    fun `the composer is lifted off the keyboard on both mobile forms`() {
        // v55 P0: the IME inset was unioned only with「底部导航」on, so on the
        // default form the soft keyboard simply covered the composer and Send
        // was unreachable (v54_05/06/08).
        //
        // v77 P0: the union moved into the pane's own bottom padding (the box
        // that decides the budget now measures the pane *with* the keyboard put
        // back), so the reading is pinned at its new call site: the IME's bottom
        // is what the padding is derived from, on every form — the bottom-titlebar
        // stop only differs in who pays the navigation bar (`BottomTabStrip`
        // does, B-16, so its floor is 0).
        assertTrue(
            "the default mobile form must lift for the IME too",
            sessionScreen.contains("imeBottomPx = WindowInsets.ime.getBottom(density),"),
        )
        assertTrue(
            sessionScreen.contains(
                "val paneFloorPx = if (titlebarBottom && !desktop) 0 else navBottomPx",
            ),
        )
        assertTrue(
            sessionScreen.contains("maxOf(paneFloorPx, believedImeBottomPx).coerceAtLeast(0).toDp()"),
        )
    }

    @Test
    fun `the pane height the budget reads is measured`() {
        // v77 P0: measured in px, converted with the live density (`Dp` written
        // from the callback would carry whatever density the callback was built
        // under — see `SessionPaneImeBeliefV77Test`).
        assertTrue(sessionScreen.contains("var paneHeightPx by remember { mutableIntStateOf(0) }"))
        assertTrue(sessionScreen.contains("var viewportHeightPx by remember { mutableIntStateOf(0) }"))
        assertTrue(sessionScreen.contains("val paneHeight: Dp = with(density) { paneHeightPx.toDp() }"))
        assertTrue(sessionScreen.contains("val viewportHeight: Dp = with(density) { viewportHeightPx.toDp() }"))
        // v76: the pane's own node is the one the arrangement sits in, and the
        // viewport's height is reported by the `Box` the arrangement measures —
        // both are single nodes, in both arrangements.
        assertTrue(
            sessionScreen.contains(
                ".onSizeChanged { paneHeightPx = it.height },",
            ),
        )
        assertTrue(
            sessionScreen.contains(
                "onViewportSize = { viewportHeightPx = it.height },",
            ),
        )
    }
}
