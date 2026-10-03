package com.xiufeigo.opencodeshell

import androidx.compose.ui.unit.dp
import com.xiufeigo.opencodeshell.ui.ShellChromeBudget
import com.xiufeigo.opencodeshell.ui.session.SessionPaneLayout
import com.xiufeigo.opencodeshell.ui.session.header.HEADER_HEIGHT
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v78 P0 「短窗+键盘：整个会话头顶栏消失」: the chrome band while the soft
 * keyboard owns a short window.
 *
 * Measured on emulator-5554 at 720x1280 @480dpi (3.0 px/dp, a 240x426.7 dp
 * window, `research/v76_verify/5554/t1_density480_open2.png` against
 * `t1_density480_nokb.png`): the IME inset is 849 px (283 dp), the body is
 * ~114 dp and the one-row composer is ~54 dp. That body sits below both yield
 * lines — 164 dp (`chromeMaxHeightForComposer`) and ~180 dp
 * (`chromeMaxHeight` through the caller's `.coerceAtMost(chromeBudget)`) — so
 * the ceiling is 0 dp and the header row and the tab strip measure to nothing.
 *
 * The ceilings below are untouched: the fix only adds the band the header's stop
 * keeps, and the gates that keep every verified window on its old number.
 *
 * v79 P0 correction: the ~114 dp body above was derived with a 24 dp status bar
 * and the device reports **52 dp** (156 px of status bar over a 68 px cutout), so
 * the real body on that frame is 91.67 dp — 0.33 dp under the 104 dp line the
 * floor was written against, which is why the floor never fired where it was
 * needed. The yield line is the composer's controls now (88 dp) and the
 * 91.67 dp window keeps its title bar; see
 * `SessionPaneLayout.chromeImeBand`'s v79 note and `SessionPaneChromeFloorV79Test`.
 * The `measuredBody` below is left at the v78 reading so the pins it carries stay
 * the record of what that round asserted.
 *
 * v84 P0 correction: the band is **not** a short-window rule either. The 420 dpi
 * form of the same 1280 px window (487.62 dp, above the 480 dp line) keeps a
 * 128.38 dp body with the keyboard up and lost the whole chrome to the same
 * yielded ceiling (`research/v84_verify/5554/29_small_sess.png`,
 * `32_420_again.png`), so the window term is gone from the ladder: the keyboard
 * is the gate, the body is the measure, and `the band is a keyboard rule, not a
 * display rule` below is what replaced `the floor is a short-window rule`.
 */
class SessionPaneShortWindowV78Test {

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()?.replace("\r\n", "\n")
            ?: error("missing $relative")

    private val sessionScreen =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionScreen.kt")

    /** The session body the 480-window probe measures with the keyboard up. */
    private val measuredBody = 114.dp

    /** The 420 dpi form of the same window: a 128.38 dp body (v79/v80). */
    private val body420 = 128.38.dp

    @Test
    fun `the band keeps the title bar on the measured window with the keyboard up`() {
        // Up to the strip's own line (124 dp) the header row is the band…
        for (body in listOf(88.dp, 104.dp, measuredBody, 123.5.dp)) {
            assertEquals(
                "body $body",
                HEADER_HEIGHT,
                SessionPaneLayout.chromeImeBand(imeVisible = true, body = body),
            )
        }
        // …and above it the whole chrome — the header row *and* the strip (v84).
        for (body in listOf(124.dp, body420, 164.dp, 250.dp)) {
            assertEquals(
                "body $body",
                SessionPaneLayout.CHROME_FULL_HEIGHT,
                SessionPaneLayout.chromeImeBand(imeVisible = true, body = body),
            )
        }
        assertEquals(48.dp, HEADER_HEIGHT)
        assertEquals(84.dp, SessionPaneLayout.CHROME_FULL_HEIGHT)
    }

    @Test
    fun `the band yields while the composer would not fit whole`() {
        // v79 P0: the yield line is the composer's **controls** (28 dp, every
        // control in the tool row) inside the region's own 12 dp of padding —
        // 88 dp with the header. Below it the composer owns the pane, because
        // the pane could not pay the header and the controls at once; above it
        // the header is kept and the padding is what the composer gives up.
        for (body in listOf(0.dp, 56.dp, 87.dp)) {
            assertEquals(
                "body $body",
                0.dp,
                SessionPaneLayout.chromeImeBand(imeVisible = true, body = body),
            )
        }
        // The line itself is 88 dp and everything above it keeps the header row.
        for (body in listOf(88.dp, 91.67.dp, 103.dp, 104.dp)) {
            assertEquals(
                "body $body",
                HEADER_HEIGHT,
                SessionPaneLayout.chromeImeBand(imeVisible = true, body = body),
            )
        }
    }

    @Test
    fun `the measured 480 window keeps its title bar`() {
        // v79 P0: the window the v78 floor was calibrated against. Its body is
        // 91.67 dp — 0.33 dp under the 92 dp the whole one-row composer plus the
        // header needs — so the v78 line never fired there and *both* chrome rows
        // collapsed (`research/v79_repro/d480.xml`: no back button, no title, no
        // view tabs in the a11y tree).
        val body = 91.67.dp
        assertEquals(
            HEADER_HEIGHT,
            SessionPaneLayout.chromeImeBand(imeVisible = true, body = body),
        )
        // …and the composer the pane then has still holds every control: the
        // region is capped to the 43.67 dp the header leaves, its padding takes
        // 12 and the 28 dp controls are left the whole card.
        val pane = body - HEADER_HEIGHT
        val ceiling = SessionPaneLayout.composerCeiling(pane)
        assertTrue(
            "ceiling $ceiling",
            ceiling >= SessionPaneLayout.COMPOSER_ROW_FLOOR + SessionPaneLayout.COMPOSER_REGION_PADDING,
        )
    }

    @Test
    fun `the band needs the keyboard`() {
        // The 1440x810 @420dpi body without the keyboard (146.67 dp, v57) keeps
        // chrome 0 and its 74 dp band: a body-keyed line could never tell it
        // apart from the measured 114 dp, so the keyboard is part of the gate.
        for (body in listOf(measuredBody, 146.67.dp, 250.dp)) {
            assertEquals(
                "body $body",
                0.dp,
                SessionPaneLayout.chromeImeBand(imeVisible = false, body = body),
            )
        }
    }

    @Test
    fun `the band is a keyboard rule, not a display rule`() {
        // v84 P0: the display height was the gate both v78 and v79 used, and it
        // is the proxy the density breaks. 720x1280 @420dpi is 487.62 dp — above
        // the short-window line — while the body the keyboard leaves on it is
        // smaller than the 480 dpi one's, so keying on the display lost the
        // header row exactly where it was needed:
        assertTrue(!ShellChromeBudget.isShortWindow(487))
        assertEquals(
            SessionPaneLayout.CHROME_FULL_HEIGHT,
            SessionPaneLayout.chromeImeBand(imeVisible = true, body = body420),
        )
        // The v74/v81 reading of the same frame lands on the same step.
        assertEquals(
            SessionPaneLayout.CHROME_FULL_HEIGHT,
            SessionPaneLayout.chromeImeBand(imeVisible = true, body = 162.7.dp),
        )
        // …and no body keeps the band without the keyboard, short display or not.
        for (body in listOf(measuredBody, body420, 162.7.dp, 183.dp, 250.dp)) {
            assertEquals(
                "body $body",
                0.dp,
                SessionPaneLayout.chromeImeBand(imeVisible = false, body = body),
            )
        }
        assertTrue(ShellChromeBudget.isShortWindow(426))
    }

    @Test
    fun `the band never squeezes the composer`() {
        var body = 0.dp
        while (body <= 300.dp) {
            val band = SessionPaneLayout.chromeImeBand(imeVisible = true, body = body)
            assertTrue(
                "body $body: band $band",
                band == 0.dp || band == HEADER_HEIGHT || band == SessionPaneLayout.CHROME_FULL_HEIGHT,
            )
            if (band > 0.dp) {
                // v79 P0: the invariant is the composer's **controls**, not its
                // whole one-row form — the region's 12 dp of padding is what the
                // header claims, and the pane's own ceiling has to leave every
                // 28 dp control whole behind it (v51).
                val ceiling = SessionPaneLayout.composerCeiling(body - band)
                assertTrue(
                    "body $body leaves $ceiling for the composer",
                    ceiling >=
                        SessionPaneLayout.COMPOSER_ROW_FLOOR +
                        SessionPaneLayout.COMPOSER_REGION_PADDING,
                )
            }
            body += 1.dp
        }
    }

    @Test
    fun `the band is only the title bar where the strip does not fit`() {
        val band = SessionPaneLayout.chromeImeBand(
            imeVisible = true,
            body = measuredBody,
        )
        assertEquals(HEADER_HEIGHT, band)
        // On the v78 114 dp reading the full band does not fit the pane beside
        // the composer's controls, so the strip is what gives way: it is the
        // strip's own stop that claims nothing (`chromeBandStripShare`) while the
        // header keeps its whole row.
        assertTrue(band < SessionPaneLayout.CHROME_FULL_HEIGHT)
        assertEquals(36.dp, SessionPaneLayout.CHROME_FULL_HEIGHT - band)
        assertEquals(0.dp, SessionPaneLayout.chromeBandStripShare(band))
    }

    @Test
    fun `the pane still pays the composer whole under the band`() {
        val band = SessionPaneLayout.chromeImeBand(
            imeVisible = true,
            body = measuredBody,
        )
        val pane = measuredBody - band
        assertTrue(pane >= SessionPaneLayout.COMPOSER_MINIMAL_HEIGHT)
        // The shared composer's own ceiling still covers its measured height,
        // so Send stays tappable (v51, v57).
        assertEquals(SessionPaneLayout.COMPOSER_MINIMAL_HEIGHT, SessionPaneLayout.composerCeiling(pane))
        assertTrue(54.dp < SessionPaneLayout.composerCeiling(pane))
    }

    @Test
    fun `the v51 v55 v57 ceiling tables are untouched`() {
        assertEquals(154.dp, SessionPaneLayout.chromeMaxHeight(342.dp))
        assertEquals(32.dp, SessionPaneLayout.chromeMaxHeight(196.2.dp))
        assertEquals(0.dp, SessionPaneLayout.chromeMaxHeight(100.dp))
        assertEquals(
            0.dp,
            SessionPaneLayout.chromeMaxHeightForComposer(
                146.67.dp,
                SessionPaneLayout.COMPOSER_MIN_HEIGHT,
            ),
        )
    }

    @Test
    fun `the screen floors the header stop and yields the tabs-only stop`() {
        assertTrue(sessionScreen.contains("SessionPaneLayout.chromeImeBand(imeVisible, bodyHeightDp)"))
        assertTrue(sessionScreen.contains("believedImeBottomPx > paneFloorPx"))
        // The band follows the stops that mount it: by default the top stop
        // carries both rows and takes the whole band, the tabs-only stop takes
        // the strip's share — nothing until the body can pay the whole band, so
        // the strip is what gives way first.
        assertTrue(
            sessionScreen.contains(
                "SessionPaneLayout.chromeBandStripShare(chromeBand)",
            ),
        )
        assertTrue(
            sessionScreen.contains(
                "SessionPaneLayout.chromeBandHeaderShare(chromeBand)",
            ),
        )
        // A floor above a 0 ceiling is a whole column, never a crash: the max
        // rides up with the min.
        assertTrue(
            sessionScreen.contains(
                "heightIn(min = topChromeFloor, max = maxOf(chromeMaxHeight, topChromeFloor))",
            ),
        )
        assertTrue(
            sessionScreen.contains(
                "heightIn(min = bottomChromeFloor, max = maxOf(chromeMaxHeight, bottomChromeFloor))",
            ),
        )
    }
}
