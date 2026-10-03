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
 * v79 P0 「720x1280 @480dpi + 键盘：头部阶梯全塌」: the chrome's floor on the very
 * window the v78 floor was calibrated against.
 *
 * The v78 line was `HEADER_HEIGHT + COMPOSER_MINIMAL_HEIGHT` = 104 dp, derived
 * from a body it read as ~114 dp. `dumpsys window displays` on emulator-5554 at
 * 720x1280 @480dpi reads the insets instead of assuming them:
 *
 * ```
 * window              1280 px                     = 240 x 426.67 dp @3.0
 * statusBars top       156 px =  52.0 dp          (68 px of it a display cutout)
 * ime bottom           849 px = 283.0 dp
 * navigationBars        72 px =  24.0 dp
 * body                (1280 − 156 − 849) / 3 = 91.67 dp
 * ```
 *
 * so the body is 0.33 dp under the 92 dp the header plus the whole one-row
 * composer needs — and 12.33 dp under the 104 dp the v78 floor asked for. The
 * floor therefore never fired and the a11y tree carried neither a back button
 * nor a session title nor the view tabs (`research/v79_repro/d480.xml`): the
 * whole ladder collapsed, which is the reported 头部阶梯全塌.
 *
 * The fix keys the ladder on the composer's **controls** ([COMPOSER_ROW_FLOOR]
 * inside the region's [COMPOSER_REGION_PADDING], 88 dp with the header) instead
 * of its whole form. What the composer gives up is the region's 12 dp of
 * decoration, and only while the keyboard owns a window this short.
 *
 * v84 P0: the ladder grew the step this file's `the 420 dpi portrait window is
 * untouched` used to pin as "untouched" — the whole band (header + strip) while
 * the body can pay it beside the same controls, 124 dp. That pin recorded a bug:
 * the 420 dpi window is 487.62 dp (above the short-window gate the round keyed
 * on) while the keyboard leaves it a 128.38 dp body, so it lost the header row
 * and the view tabs exactly like the 480 window this file is about
 * (`research/v84_verify/5554/32_420_again.png` against `33_480.png`).
 */
class SessionPaneChromeFloorV79Test {

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()?.replace("\r\n", "\n")
            ?: error("missing $relative")

    private val sessionScreen =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionScreen.kt")

    private val paneLayout =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionPaneLayout.kt")

    /** The measured window: 720x1280 @480dpi with the keyboard up. */
    private val measuredWindowHeightDp = 426

    /** `(1280 − 156 − 849) / 3` — the post-inset body of that frame. */
    private val measuredBody = 91.67.dp

    /** The line the header row is kept above: 48 + 28 + 12. */
    private val line = HEADER_HEIGHT +
        SessionPaneLayout.COMPOSER_ROW_FLOOR +
        SessionPaneLayout.COMPOSER_REGION_PADDING

    /** The line the whole band is kept above: 84 + 28 + 12 (v84). */
    private val bandLine = SessionPaneLayout.CHROME_FULL_HEIGHT +
        SessionPaneLayout.COMPOSER_ROW_FLOOR +
        SessionPaneLayout.COMPOSER_REGION_PADDING

    @Test
    fun `the line is the header plus the composer's controls`() {
        assertEquals(88.dp, line)
        // It is 16 dp under the v78 line — the tool row's own design height
        // (`COMPOSER_ROW_HEIGHT`) above the 28 dp its controls need, which is
        // exactly the slack the header claims while the keyboard owns a window
        // this short.
        assertEquals(
            SessionPaneLayout.COMPOSER_ROW_HEIGHT - SessionPaneLayout.COMPOSER_ROW_FLOOR,
            HEADER_HEIGHT + SessionPaneLayout.COMPOSER_MINIMAL_HEIGHT - line,
        )
        // …and the whole one-row form is the row plus the region's padding.
        assertEquals(
            SessionPaneLayout.COMPOSER_MINIMAL_HEIGHT,
            SessionPaneLayout.COMPOSER_ROW_HEIGHT + SessionPaneLayout.COMPOSER_REGION_PADDING,
        )
        assertTrue(line < HEADER_HEIGHT + SessionPaneLayout.COMPOSER_MINIMAL_HEIGHT)
        // v84: the same controls one row higher — the whole band's line.
        assertEquals(124.dp, bandLine)
        assertEquals(SessionPaneLayout.CHROME_FULL_HEIGHT, bandLine - line + HEADER_HEIGHT)
    }

    @Test
    fun `the measured 480 window keeps the title bar and yields the strip`() {
        assertTrue(ShellChromeBudget.isShortWindow(measuredWindowHeightDp))
        // The body is under the header plus the whole composer, which is why the
        // v78 line never fired here…
        assertTrue(measuredBody < HEADER_HEIGHT + SessionPaneLayout.COMPOSER_MINIMAL_HEIGHT)
        // …and above the header plus the composer's controls, which is why it
        // fires now.
        assertTrue("$measuredBody vs $line", measuredBody >= line)
        // v84: it is still *under* the whole band's line, so the strip keeps
        // yielding there — nothing this file pins moved.
        assertTrue("$measuredBody vs $bandLine", measuredBody < bandLine)
        val floor = SessionPaneLayout.chromeImeBand(
            imeVisible = true,
            body = measuredBody,
        )
        assertEquals(HEADER_HEIGHT, floor)
        // The tab strip yields: the caller caps the band at `max(ceiling, floor)`
        // and the ceiling on this body is 0 (`chromeMaxHeightForComposer` yields
        // the chrome under the 164 dp line), so the band is the header's own row
        // and the strip below it scrolls out.
        assertEquals(
            0.dp,
            SessionPaneLayout.chromeMaxHeightForComposer(
                measuredBody,
                SessionPaneLayout.COMPOSER_MIN_HEIGHT,
            ),
        )
        assertEquals(HEADER_HEIGHT, maxOf(0.dp, floor))
        assertTrue(floor < SessionPaneLayout.CHROME_FULL_HEIGHT)
        assertEquals(0.dp, SessionPaneLayout.chromeBandStripShare(floor))
        // …and the composer the pane is left with holds every control it has.
        val ceiling = SessionPaneLayout.composerCeiling(measuredBody - floor)
        assertEquals(measuredBody - floor, ceiling)
        assertTrue(
            "ceiling $ceiling",
            ceiling >= SessionPaneLayout.COMPOSER_ROW_FLOOR + SessionPaneLayout.COMPOSER_REGION_PADDING,
        )
    }

    @Test
    fun `the composer the band leaves always holds its controls`() {
        var body = line
        while (body <= 300.dp) {
            val band = SessionPaneLayout.chromeImeBand(imeVisible = true, body = body)
            assertEquals(
                "body $body",
                if (body < bandLine) HEADER_HEIGHT else SessionPaneLayout.CHROME_FULL_HEIGHT,
                band,
            )
            val ceiling = SessionPaneLayout.composerCeiling(body - band)
            assertTrue(
                "body $body leaves $ceiling for the composer",
                ceiling >=
                    SessionPaneLayout.COMPOSER_ROW_FLOOR +
                    SessionPaneLayout.COMPOSER_REGION_PADDING,
            )
            body += 0.5.dp
        }
    }

    @Test
    fun `the ladder is two steps and it is monotone`() {
        var body = 0.dp
        var seenHeader = false
        var seenStrip = false
        var previous = 0.dp
        while (body <= 300.dp) {
            val band = SessionPaneLayout.chromeImeBand(imeVisible = true, body = body)
            assertTrue(
                "body $body: band $band",
                band == 0.dp || band == HEADER_HEIGHT || band == SessionPaneLayout.CHROME_FULL_HEIGHT,
            )
            assertTrue("body $body: band $band went backwards", band >= previous)
            if (band >= HEADER_HEIGHT) seenHeader = true
            if (band >= SessionPaneLayout.CHROME_FULL_HEIGHT) seenStrip = true
            // Once a row is kept it is never dropped again as the body grows.
            if (seenHeader) assertTrue("body $body", band >= HEADER_HEIGHT)
            if (seenStrip) assertEquals("body $body", SessionPaneLayout.CHROME_FULL_HEIGHT, band)
            previous = band
            body += 0.5.dp
        }
        assertTrue(seenHeader)
        assertTrue(seenStrip)
    }

    @Test
    fun `the 420 dpi portrait window keeps its whole chrome`() {
        // v84 P0: 720x1280 @420dpi is 487.62 dp — above the short-window gate
        // both earlier rounds keyed on — while its body with the keyboard up is
        // 128.38 dp (v79/v80 reading; 162.7 dp by v74/v81). The band is keyed on
        // the body now, so both readings keep the header row *and* the strip
        // (`research/v84_verify/5554/32_420_again.png` is what they did before).
        assertTrue(!ShellChromeBudget.isShortWindow(487))
        for (body in listOf(128.38.dp, 162.7.dp)) {
            val band = SessionPaneLayout.chromeImeBand(imeVisible = true, body = body)
            assertEquals("body $body", SessionPaneLayout.CHROME_FULL_HEIGHT, band)
            assertEquals(HEADER_HEIGHT, SessionPaneLayout.chromeBandHeaderShare(band))
            assertEquals(
                SessionPaneLayout.CHROME_FULL_HEIGHT - HEADER_HEIGHT,
                SessionPaneLayout.chromeBandStripShare(band),
            )
            // …and the pane behind the band still holds every composer control:
            // 44.38 dp on the measured reading, 78.7 dp on the v74 one.
            val ceiling = SessionPaneLayout.composerCeiling(body - band)
            assertTrue(
                "body $body leaves $ceiling for the composer",
                ceiling >=
                    SessionPaneLayout.COMPOSER_ROW_FLOOR +
                    SessionPaneLayout.COMPOSER_REGION_PADDING,
            )
        }
        // Without the keyboard the same window keeps chrome 0 (v57's rule).
        for (body in listOf(measuredBody, 128.38.dp, 162.7.dp, 183.dp, 250.dp)) {
            assertEquals(
                "body $body",
                0.dp,
                SessionPaneLayout.chromeImeBand(imeVisible = false, body = body),
            )
        }
    }

    @Test
    fun `the screen floors the header stop through the same constants`() {
        // The band is the ladder's only writer and the screen only applies it
        // per stop; both call sites go through `chromeImeBand`'s shares, and the
        // band's max rides up with the min so a floor above a 0 ceiling is a
        // whole row.
        assertTrue(sessionScreen.contains("SessionPaneLayout.chromeImeBand(imeVisible, bodyHeightDp)"))
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
        // The ladder reads the controls floor and the region's padding, so a
        // future change to either cannot leave the line behind — and the same is
        // true of the whole band's step (v84).
        assertTrue(paneLayout.contains("HEADER_HEIGHT + COMPOSER_ROW_FLOOR + COMPOSER_REGION_PADDING"))
        assertTrue(
            paneLayout.contains(
                "CHROME_FULL_HEIGHT + COMPOSER_ROW_FLOOR + COMPOSER_REGION_PADDING",
            ),
        )
        assertTrue(paneLayout.contains("val COMPOSER_ROW_FLOOR: Dp = 28.dp"))
    }
}
