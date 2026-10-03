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
 * v84 P0 「720x1280 @420dpi：会话页头与视图 tab 整条不渲染」: the chrome band the
 * keyboard leaves the session pane, on the density that loses it.
 *
 * `research/v84_verify/5554/29_small_sess.png`, `32_420_again.png` and
 * `35_420_scrolled.png` are the measured frame (720x1280 @420dpi, keyboard up):
 * the shell's own tab strip, a sliver of the timeline, the composer and the
 * keyboard — no back chevron, no ☰, no session title, no search, no ⋮ and no
 * 会话/变更/文件/终端 strip, on a pane that scrolls nowhere. The same window at
 * 320 dpi (`31_small_320d.png`) and at 480 dpi (`33_480.png`) draws both rows.
 *
 * The arithmetic of the 420 dpi frame (`research/v79_repro/rec.mp4`,
 * `research/v80_verify/5554/app_fatals.txt`, the v81 崩 readings):
 *
 * ```
 * 720x1280 @420dpi = 2.625 px/dp        window  274.29 x 487.62 dp
 *   pane box (body column, shell chrome out)    1080 px = 411.43 dp
 *   ime                                          743 px = 283.00 dp
 *   body                                         337 px = 128.38 dp
 * ```
 *
 * 487.62 dp is *above* [ShellChromeBudget.SHORT_WINDOW_DP] — that is the whole
 * bug: v78/v79 floored the chrome only on a short *display*, so this window kept
 * the ladder that yields the chrome whole ([chromeMaxHeightForComposer] returns
 * 0 dp under its 164 dp line) and the pane lost every navigation control. The
 * band is what the pane really has room for, and 128.38 dp pays the whole chrome
 * (84 dp) beside the composer's controls (28 + 12 dp) with 4.38 dp to spare.
 */
class SessionPaneImeBandV84Test {

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()?.replace("\r\n", "\n")
            ?: error("missing $relative")

    private val sessionScreen =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionScreen.kt")

    private val paneLayout =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionPaneLayout.kt")

    /** The measured 420 dpi window: 1280 px / 2.625 px per dp. */
    private val window420 = 487

    /** The pane box the body column starts from (`411.43` — the v81 readings). */
    private val paneBox420 = 411.43.dp

    /** `WindowInsets.ime`'s bottom on that frame: 743 px = 283.0 dp. */
    private val ime420 = 283.dp

    /** The post-inset body the pane is really built on. */
    private val body420 = paneBox420 - ime420

    /** The v74/v81 reading of the same frame — the other number in the repo. */
    private val body420v74 = 162.7.dp

    /** The 480 dpi window and body the v79 round measured (91.67 dp). */
    private val window480 = 426
    private val body480 = 91.67.dp

    /** The whole band the ladder's first step pays for. */
    private val lineFull = SessionPaneLayout.CHROME_FULL_HEIGHT +
        SessionPaneLayout.COMPOSER_ROW_FLOOR +
        SessionPaneLayout.COMPOSER_REGION_PADDING

    /** The header row alone, the ladder's second step (the v79 line). */
    private val lineHeader = HEADER_HEIGHT +
        SessionPaneLayout.COMPOSER_ROW_FLOOR +
        SessionPaneLayout.COMPOSER_REGION_PADDING

    /** The composer's controls floor: what must survive behind any band. */
    private val composerControls =
        SessionPaneLayout.COMPOSER_ROW_FLOOR + SessionPaneLayout.COMPOSER_REGION_PADDING

    @Test
    fun `the 420 window is not a short display and its body is under the ceiling`() {
        // The gate both previous rounds keyed on: the display is 7.62 dp *over*
        // the short-window line…
        assertTrue(!ShellChromeBudget.isShortWindow(window420))
        // …and the ceiling the chrome is left with is 0 dp whatever composer
        // form the pane has (the one-row 56 dp and the measured 58.1 dp).
        for (composer in listOf(56.dp, 58.1.dp)) {
            assertEquals(
                "composer $composer",
                0.dp,
                SessionPaneLayout.chromeMaxHeightForComposer(body420, composer),
            )
        }
        // The body is 4.38 dp over the whole band the ladder can pay, and far
        // under the 164 dp the ceiling needs — that gap is the bug.
        val ceilingLine = SessionPaneLayout.COMPOSER_MINIMAL_HEIGHT +
            SessionPaneLayout.CHROME_MIN_HEIGHT +
            SessionPaneLayout.TIMELINE_MIN_CONTENT
        assertEquals(164.dp, ceilingLine)
        assertTrue("$body420 vs $lineFull", body420 >= lineFull)
        assertTrue("$body420 vs $ceilingLine", body420 < ceilingLine)
    }

    @Test
    fun `the keyboard-up band at 420 is the whole chrome`() {
        val band = SessionPaneLayout.chromeImeBand(imeVisible = true, body = body420)
        assertEquals(SessionPaneLayout.CHROME_FULL_HEIGHT, band)
        assertEquals(84.dp, band)
        // Both rows are addressed: the header row whole, the strip whole.
        assertEquals(HEADER_HEIGHT, SessionPaneLayout.chromeBandHeaderShare(band))
        assertEquals(
            SessionPaneLayout.CHROME_FULL_HEIGHT - HEADER_HEIGHT,
            SessionPaneLayout.chromeBandStripShare(band),
        )
        assertEquals(36.dp, SessionPaneLayout.chromeBandStripShare(band))
        // The v74 reading of the same frame (162.7 dp) is on the same step, so
        // the fix does not depend on which of the two numbers is the true body.
        assertEquals(
            SessionPaneLayout.CHROME_FULL_HEIGHT,
            SessionPaneLayout.chromeImeBand(imeVisible = true, body = body420v74),
        )
    }

    @Test
    fun `the two steps are the composer's controls`() {
        assertEquals(124.dp, lineFull)
        assertEquals(88.dp, lineHeader)
        assertEquals(
            SessionPaneLayout.CHROME_FULL_HEIGHT,
            lineFull - composerControls,
        )
        assertEquals(HEADER_HEIGHT, lineHeader - composerControls)
        // At each step's own line the composer the pane has left is exactly the
        // controls floor: the region is capped to the pane, its 12 dp of padding
        // takes its share and the 28 dp controls keep the whole card (v51, v79).
        for (band in listOf(HEADER_HEIGHT, SessionPaneLayout.CHROME_FULL_HEIGHT)) {
            val body = band + composerControls
            assertEquals("body $body", band, SessionPaneLayout.chromeImeBand(true, body))
            val ceiling = SessionPaneLayout.composerCeiling(body - band)
            assertEquals("body $body leaves $ceiling", composerControls, ceiling)
        }
        // And on the measured 420 frame the pane behind the whole band (44.38 dp
        // by the readings) is above that floor, so the card it lays out still
        // holds every control.
        val pane420 = body420 - SessionPaneLayout.CHROME_FULL_HEIGHT
        assertTrue("pane $pane420", pane420.value in 44f..45f)
        assertTrue(
            "pane $pane420 leaves ${SessionPaneLayout.composerCeiling(pane420)}",
            SessionPaneLayout.composerCeiling(pane420) >= composerControls,
        )
    }

    @Test
    fun `the band never dips under the composer's controls while the keyboard is up`() {
        var body = 0.dp
        while (body <= 320.dp) {
            val band = SessionPaneLayout.chromeImeBand(imeVisible = true, body = body)
            assertTrue("body $body: band $band", band == 0.dp || band == HEADER_HEIGHT || band ==
                SessionPaneLayout.CHROME_FULL_HEIGHT)
            if (band > 0.dp) {
                val ceiling = SessionPaneLayout.composerCeiling(body - band)
                assertTrue(
                    "body $body leaves $ceiling for the composer",
                    ceiling >= composerControls,
                )
            }
            body += 0.5.dp
        }
    }

    @Test
    fun `the band is monotone and never re-loses a row`() {
        var seenHeader = false
        var seenStrip = false
        var previous = 0.dp
        var body = 0.dp
        while (body <= 320.dp) {
            val band = SessionPaneLayout.chromeImeBand(imeVisible = true, body = body)
            assertTrue("body $body: band $band went backwards", band >= previous)
            if (band >= HEADER_HEIGHT) seenHeader = true
            else assertTrue("body $body re-lost the header row", !seenHeader)
            if (band >= SessionPaneLayout.CHROME_FULL_HEIGHT) seenStrip = true
            else assertTrue("body $body re-lost the tab strip", !seenStrip)
            previous = band
            body += 0.5.dp
        }
        assertTrue(seenHeader)
        assertTrue(seenStrip)
    }

    @Test
    fun `without the keyboard the band is zero on every body`() {
        // The no-keyboard bodies the earlier rounds pinned keep chrome 0 — the
        // 1440x810 @420dpi 146.67 dp with its 74 dp band (v57) above all. The
        // keyboard is the other half of the gate, and it is the half that a
        // display-height proxy cannot supply.
        for (body in listOf(
            body480 - HEADER_HEIGHT,
            body420,
            body420v74,
            146.67.dp,
            250.dp,
            paneBox420,
        )) {
            assertEquals(
                "body $body",
                0.dp,
                SessionPaneLayout.chromeImeBand(imeVisible = false, body = body),
            )
        }
    }

    @Test
    fun `the 480 window keeps its title bar and still yields the strip`() {
        assertTrue(ShellChromeBudget.isShortWindow(window480))
        // 91.67 dp pays the header row and the controls, not the strip: the
        // v79 behaviour is untouched.
        assertTrue(body480 >= lineHeader)
        assertTrue(body480 < lineFull)
        assertEquals(
            HEADER_HEIGHT,
            SessionPaneLayout.chromeImeBand(imeVisible = true, body = body480),
        )
        // The strip's own stop claims nothing there, so nothing opens a gap.
        assertEquals(
            0.dp,
            SessionPaneLayout.chromeBandStripShare(
                SessionPaneLayout.chromeImeBand(imeVisible = true, body = body480),
            ),
        )
    }

    @Test
    fun `the band cannot bind where the ceiling already pays for the chrome`() {
        // 320 dpi on the same 1280 px window (a 360x640 dp display) and the
        // default 1344x2992 px one both keep a body well over the 272 dp the
        // ceiling needs to pay the whole band itself, so the floor is inert
        // there: `max(ceiling, band)` is the ceiling.
        for (body in listOf(272.dp, 281.dp, 400.dp, 781.dp)) {
            for (composer in listOf(56.dp, 58.1.dp, 96.dp, 116.dp)) {
                val ceiling = minOf(
                    SessionPaneLayout.chromeMaxHeight(body),
                    SessionPaneLayout.chromeMaxHeightForComposer(body, composer),
                )
                assertTrue(
                    "body $body, composer $composer: ceiling $ceiling",
                    ceiling >= SessionPaneLayout.CHROME_FULL_HEIGHT,
                )
                assertEquals(
                    "body $body, composer $composer",
                    ceiling,
                    maxOf(
                        ceiling,
                        SessionPaneLayout.chromeImeBand(imeVisible = true, body = body),
                    ),
                )
            }
        }
    }

    @Test
    fun `between the lines the whole band is worth more than the message strip`() {
        // The deliberate consequence of a monotone ladder: a body between the
        // strip's line (124 dp) and the 272 dp the ceiling needs to pay the band
        // itself keeps the whole chrome while the keyboard is up, where the
        // ceiling alone would have kept 44 dp and the messages the rest. That is
        // the v78/v79 priority — 「typing, not reading」: the header and the tabs
        // are the two things the user needs to keep typing and to leave, and the
        // band comes back the moment the keyboard does.
        for (body in listOf(124.dp, 164.dp, 200.dp, 232.dp, 271.dp)) {
            assertEquals(
                "body $body",
                SessionPaneLayout.CHROME_FULL_HEIGHT,
                SessionPaneLayout.chromeImeBand(imeVisible = true, body = body),
            )
            // The price is bounded: the composer still keeps every control…
            val pane = body - SessionPaneLayout.CHROME_FULL_HEIGHT
            assertTrue(
                "body $body leaves ${SessionPaneLayout.composerCeiling(pane)}",
                SessionPaneLayout.composerCeiling(pane) >= composerControls,
            )
        }
        // …and on the body the ceiling did keep 44 dp for, the band is what pays.
        val body = 200.dp
        assertTrue(
            SessionPaneLayout.chromeMaxHeight(body) < SessionPaneLayout.CHROME_FULL_HEIGHT,
        )
    }

    @Test
    fun `the screen splits the band across the stops it mounts them in`() {
        assertTrue(sessionScreen.contains("SessionPaneLayout.chromeImeBand(imeVisible, bodyHeightDp)"))
        assertTrue(sessionScreen.contains("SessionPaneLayout.chromeBandStripShare(chromeBand)"))
        assertTrue(sessionScreen.contains("SessionPaneLayout.chromeBandHeaderShare(chromeBand)"))
        // A floor above a 0 ceiling is a whole band, never a crash: the max
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

    @Test
    fun `the band reads the body, never the display height`() {
        // The v84 correction itself: the band's only inputs are the keyboard and
        // the body, and the display height is no longer part of the ladder.
        assertTrue(
            paneLayout.contains(
                "fun chromeImeBand(imeVisible: Boolean, body: Dp): Dp = when {",
            ),
        )
        assertTrue(!paneLayout.contains("chromeImeFloor"))
        assertTrue(!sessionScreen.contains("chromeImeFloor"))
        // …while the composer's own form is still seeded from the window (v74:
        // `composerOneRow`), which is a different question.
        assertTrue(sessionScreen.contains("ShellChromeBudget.isShortWindow(windowHeightDp)"))
    }
}
