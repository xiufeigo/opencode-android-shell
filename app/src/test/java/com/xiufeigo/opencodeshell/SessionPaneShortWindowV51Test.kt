package com.xiufeigo.opencodeshell

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xiufeigo.opencodeshell.ui.session.SessionPaneLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v51 P0 「消息列表空白 + composer 裁切」, the height-budget half.
 *
 * The mobile session pane is `chrome (fixed) + viewport (weight 1f)` with the
 * composer floating over the viewport (`timelinePane` anchors it
 * `BottomCenter` and pads the timeline's `contentPadding` by its measured
 * height). Nothing bounded the chrome, so on a short window the viewport
 * measured *below* the composer's own height and:
 *
 *  * the composer's tool row — `Modifier.height(TOOL_ROW_HEIGHT)` — was coerced
 *    down to a sliver, so ⊞ / 模型 chip / Send vanished and Send became an
 *    untappable ~26 px bar (`research/v51_verify/tE/v51_07_typed`,
 *    `v51_09_sent`);
 *  * the timeline's usable area (`viewport − composer − gap`) went to **0 dp**,
 *    so a session with server-confirmed messages rendered a blank body — a
 *    relaunch and a scroll could not recover it (`v51_12_msgs`,
 *    `v51_14_relaunch`), and the same happened with the keyboard up at
 *    `wm size 640x1500` (`tC/tC_55_narrow`, against `tC_56`).
 *
 * [SessionPaneLayout.chromeMaxHeight] is the ceiling that pays for the composer
 * and the timeline floor first; [SessionPaneLayout.timelineMinHeight] is the
 * floor the viewport is left with.
 */
class SessionPaneShortWindowV51Test {

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()?.replace("\r\n", "\n")
            ?: error("missing $relative")

    private val sessionScreen =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionScreen.kt")

    private val composer =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/Composer.kt")

    /** What the viewport is left with once the chrome takes its ceiling. */
    private fun viewport(available: Dp): Dp =
        available - SessionPaneLayout.chromeMaxHeight(available)

    // ------------------------------------------------------------- the ceiling

    @Test
    fun `chrome keeps its whole height on a healthy window`() {
        // A 1080x2424 @2.75 phone: ≈768 dp tall, ≈700 dp after the insets.
        val available = 700.dp
        val ceiling = SessionPaneLayout.chromeMaxHeight(available)
        // Header row + tab strip measure ~82 dp; the ceiling must not bind.
        assertTrue("$ceiling", ceiling > 200.dp)
        assertEquals(available, viewport(available) + ceiling)
    }

    @Test
    fun `chrome yields so the composer and a timeline floor both fit`() {
        // The 1440x810 @420 landscape window of `v51_07_typed`: ≈190 dp of pane.
        // Composer (116) + tab strip (44) leave 30 dp of message area — the
        // window cannot carry the full floor *and* the navigation, and the
        // navigation wins (the header row scrolls out of the band).
        val available = 190.dp
        val chrome = SessionPaneLayout.chromeMaxHeight(available)
        val pane = viewport(available)

        // The composer keeps its whole height — the tool row cannot be squeezed.
        assertTrue("composer: $available - $chrome", pane >= SessionPaneLayout.COMPOSER_MIN_HEIGHT)
        // …and the tab strip is still there. v54 P0 revised this: the 44 dp floor
        // is what left the messages a 24 dp band and a blank body on the very
        // window this test is written for, so a pane that cannot also carry a
        // message row now falls back to the compact chrome
        // (`SessionPaneLayout.chromeMaxHeight`, `SessionPaneShortWindowV54Test`).
        // The band still scrolls, so the header row stays reachable.
        assertTrue("chrome: $chrome", chrome >= SessionPaneLayout.CHROME_COMPACT_HEIGHT)
        // …and the message area is a row and a half, not the sliver — and never
        // the 0 dp that hid every message in `v51_12_msgs`.
        assertTrue(
            "timeline: $available - $chrome - ${SessionPaneLayout.COMPOSER_MIN_HEIGHT}",
            available - chrome - SessionPaneLayout.COMPOSER_MIN_HEIGHT >= 24.dp,
        )
        assertTrue(
            "band: $pane",
            SessionPaneLayout.timelineItemBand(
                pane,
                SessionPaneLayout.COMPOSER_MIN_HEIGHT,
                12.dp,
            ) >= SessionPaneLayout.TIMELINE_MIN_CONTENT,
        )
    }

    @Test
    fun `the timeline floor is delivered as soon as the pane can carry it`() {
        // 300 dp: composer (116) + floor (72) + tab strip (44) = 232 dp fits, so
        // the chrome keeps everything above its floor and the viewport is left
        // with exactly the floor.
        val available = 300.dp
        assertEquals(
            available - SessionPaneLayout.COMPOSER_MIN_HEIGHT - SessionPaneLayout.TIMELINE_MIN_HEIGHT,
            SessionPaneLayout.chromeMaxHeight(available),
        )
        assertEquals(
            SessionPaneLayout.TIMELINE_MIN_HEIGHT,
            SessionPaneLayout.timelineMinHeight(available),
        )
    }

    @Test
    fun `chrome never takes the composer's share`() {
        // Any pane shorter than composer + tab strip: the composer wins, the
        // tab strip (and then the header) gives up its space.
        for (available in listOf(0.dp, 40.dp, 100.dp, 120.dp, 130.dp)) {
            val chrome = SessionPaneLayout.chromeMaxHeight(available)
            assertTrue("$available -> $chrome", chrome <= available.coerceAtLeast(0.dp))
            if (available > SessionPaneLayout.COMPOSER_MIN_HEIGHT) {
                assertTrue(
                    "$available -> viewport ${viewport(available)}",
                    viewport(available) >= SessionPaneLayout.COMPOSER_MIN_HEIGHT,
                )
            }
        }
    }

    @Test
    fun `chrome collapses instead of pushing the composer off the window`() {
        // Below the composer's own height nothing can be shown, but the ceiling
        // still has to be 0 dp: an uncapped chrome is exactly what pushed the
        // composer's bottom row (and the Send button) under the keyboard.
        assertEquals(0.dp, SessionPaneLayout.chromeMaxHeight(SessionPaneLayout.COMPOSER_MIN_HEIGHT))
        assertEquals(0.dp, SessionPaneLayout.chromeMaxHeight(SessionPaneLayout.COMPOSER_MIN_HEIGHT - 1.dp))
    }

    @Test
    fun `a non-positive pane has no budget at all`() {
        assertEquals(0.dp, SessionPaneLayout.chromeMaxHeight(0.dp))
        assertEquals(0.dp, SessionPaneLayout.chromeMaxHeight((-20).dp))
        assertEquals(0.dp, SessionPaneLayout.timelineMinHeight(0.dp))
        assertEquals(0.dp, SessionPaneLayout.timelineMinHeight((-20).dp))
    }

    // -------------------------------------------------------------- the floor

    @Test
    fun `timeline floor is the budget when it fits`() {
        // Tall pane: the viewport gets the whole floor.
        assertEquals(
            SessionPaneLayout.TIMELINE_MIN_HEIGHT,
            SessionPaneLayout.timelineMinHeight(700.dp),
        )
        // 190 dp pane: chrome 44 dp leaves 146 dp, i.e. 30 dp above the 116 dp
        // composer — so the floor is all of it.
        val narrow = SessionPaneLayout.timelineMinHeight(190.dp)
        assertTrue("$narrow", narrow > 0.dp)
        assertTrue("$narrow", narrow <= SessionPaneLayout.TIMELINE_MIN_HEIGHT)
    }

    @Test
    fun `timeline floor gives way rather than overflowing the column`() {
        // 123 dp pane with the keyboard up: the composer alone is 116 dp, so
        // the floor has to give way — a floor that pushed the composer out of
        // the window would be worse than an empty message area. v57: the
        // chrome now yields entirely on a body this short (it used to hold
        // 7 dp of clipped header), and those 7 dp are what is left over.
        assertEquals(7.dp, SessionPaneLayout.timelineMinHeight(123.dp))
        // Never larger than what the pane really has left.
        for (available in listOf(120.dp, 160.dp, 190.dp, 320.dp, 700.dp, 1200.dp)) {
            val floor = SessionPaneLayout.timelineMinHeight(available)
            assertTrue(
                "$available -> $floor",
                floor <= viewport(available) - SessionPaneLayout.COMPOSER_MIN_HEIGHT + 0.01.dp,
            )
        }
    }

    // ------------------------------------------------------- the wiring itself

    @Test
    fun `the mobile chrome band is capped and scrolls`() {
        // v78 P0: both stops carry a title-bar floor under the ceiling now (the
        // header's stop keeps it, the tabs-only stop yields), so the ceiling
        // rides `maxOf` with the floor instead of standing alone.
        assertTrue(
            "the chrome band must carry the ceiling",
            sessionScreen.contains("maxOf(chromeMaxHeight, topChromeFloor)") &&
                sessionScreen.contains("maxOf(chromeMaxHeight, bottomChromeFloor)"),
        )
        // v77 P0: the ceiling comes from `bodyHeightDp` — the pane box minus the
        // bottom padding the body applies, i.e. the post-inset height (the box
        // itself now measures the pane *with* the keyboard's claim, so the pane
        // can judge that claim — see `SessionPaneImeBeliefV77Test`).
        assertTrue(
            "the ceiling must come from the post-inset pane height",
            sessionScreen.contains("SessionPaneLayout.chromeMaxHeight(bodyHeightDp)"),
        )
        assertTrue(
            "the body is the box with the keyboard out of it",
            sessionScreen.contains("val bodyHeightDp = (maxHeight - paneBottomDp).coerceAtLeast(0.dp)"),
        )
        assertTrue(
            "the chrome band scrolls once the ceiling binds",
            sessionScreen.contains(
                "maxOf(chromeMaxHeight, topChromeFloor))\n                        .verticalScroll(rememberScrollState()),",
            ),
        )
        assertTrue(
            "the viewport keeps a floor",
            sessionScreen.contains(".heightIn(min = viewportMinHeight)"),
        )
    }

    @Test
    fun `the composer editor is the shrinking half of its column`() {
        // The tool row keeps its intrinsic `TOOL_ROW_HEIGHT`, and the editor is
        // what gives: the arrangement measures it against `card −
        // TOOL_ROW_HEIGHT`, so a composer squeezed by a short viewport loses text
        // height, never its ⊞ / 模型 / Send row.
        assertTrue(
            composer.contains(
                "maxHeight = (constraints.maxHeight - toolRow.height).coerceAtLeast(0),",
            ),
        )
        assertTrue(composer.contains(".height(TOOL_ROW_HEIGHT)"))
    }
}
