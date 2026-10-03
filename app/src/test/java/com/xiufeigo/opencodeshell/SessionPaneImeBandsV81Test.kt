package com.xiufeigo.opencodeshell

import androidx.compose.ui.unit.dp
import com.xiufeigo.opencodeshell.ui.ShellChromeBudget
import com.xiufeigo.opencodeshell.ui.session.SessionPaneLayout
import com.xiufeigo.opencodeshell.ui.session.header.HEADER_HEIGHT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * v81 P2「720x1280 + 键盘：消息区塌成 0」: the documented decision on the
 * keyboard-up ladder at the two densities of that window.
 *
 * `research/v80_verify/5554/16_480_session.png` (720x1280 @480dpi, keyboard up)
 * shows the title bar, the one-row composer and the keyboard with no message
 * strip at all between the chrome and the composer — not even the empty-state
 * text, which the same spot renders fine at the default density. The v78 note
 * accepted ~0 dp of messages at 480+IME as the cost of keeping the title bar;
 * this file verifies the arithmetic behind that acceptance at both densities.
 *
 *  * 480+IME: the post-inset body is 91.67 dp
 *    (`(1280 − 156 − 849) / 3`, `SessionPaneChromeFloorV79Test`). The header
 *    keeps 48, the squeezed one-row composer keeps 43.67, and nothing is left:
 *    91.67 dp cannot pay the header (48) plus the composer's controls floor
 *    (28 + 12 region padding) plus even a 40 dp band (128 dp needed). The band
 *    pays because the composer that lost its tool row cannot be submitted by
 *    touch at all (v51) and the header that lost its back button cannot be
 *    navigated from (v78) — typing, not reading, and dismissing the keyboard
 *    restores the full band.
 *  * 420+IME: the body is 128.38 dp (v79/v80; 162.7 dp by the v74/v81 reading)
 *    on a 487.62 dp display. Until v84 the ladder never ran there — it keyed on
 *    the display — so the header row and the view tabs were *gone* while the
 *    pane kept a band the empty state rendered in
 *    (`research/v84_verify/5554/32_420_again.png` against `33_480.png`). v84
 *    keys the band on the body, so the whole chrome (84 dp) is kept, the pane
 *    behind it is 44.38 dp, the shared composer takes that and the band is 0 dp
 *    there as well: the same acceptance as 480, for a body one keyboard shorter.
 *    A band on this window was never worth the pane's navigation, and the
 *    411.43 dp no-keyboard body still pays a 231.43 dp chrome ceiling and the
 *    whole transcript.
 *
 * The empty state's invisibility is structural, not a misplaced offset: the
 * shared arrangement leaves a 0 px viewport and the empty-state text is
 * `align(Center)` of that box, so it measures 0 — [SessionPaneLayout.emptyStateShift]'s
 * shared branch correctly returns 0 because there is no band to centre in.
 */
class SessionPaneImeBandsV81Test {

    /** 720x1280 @480dpi with the keyboard up: a short window. */
    private val window480 = 426
    private val body480 = 91.67.dp

    /** 720x1280 @420dpi with the keyboard up: 487 dp, not a short window. */
    private val window420 = 487

    /** `411.43 − 283` — the post-inset body of the 420 dpi frame (v79/v80). */
    private val body420 = 128.38.dp

    /** The v74/v81 reading of the same frame. */
    private val body420v74 = 162.7.dp

    /** The measured one-row composer on the 420 window (v58/v74). */
    private val composer420 = 58.1.dp

    private val gap = 12.dp

    private fun assertNear(expected: Float, actual: Float, what: String) {
        assertTrue("$what: expected ~$expected, was $actual", abs(expected - actual) < 0.05f)
    }

    @Test
    fun `the 480 body cannot afford the header plus the controls plus a band`() {
        assertTrue(ShellChromeBudget.isShortWindow(window480))
        // The ladder keeps the header here…
        assertEquals(
            HEADER_HEIGHT,
            SessionPaneLayout.chromeImeBand(imeVisible = true, body = body480),
        )
        // …and what is left (43.67) still holds every composer control: at the
        // line the region is capped to the pane, its 12 dp of padding takes its
        // share and the 28 dp controls keep the whole card (v79).
        val pane480 = body480 - HEADER_HEIGHT
        val ceiling480 = SessionPaneLayout.composerCeiling(pane480)
        assertNear(43.67f, ceiling480.value, "480 composer ceiling")
        assertTrue(
            "ceiling $ceiling480 must hold the controls",
            ceiling480 >= SessionPaneLayout.COMPOSER_ROW_FLOOR + SessionPaneLayout.COMPOSER_REGION_PADDING,
        )
        // A 40 dp band on top would need 48 + 28 + 12 + 40 = 128 dp of body.
        // 91.67 dp genuinely cannot pay it without taking the header (the back
        // button) or the tool row (touch submit) — the accepted cost.
        assertTrue(
            "body $body480 vs 128 dp for header + controls + 40 dp band",
            body480 < HEADER_HEIGHT + SessionPaneLayout.COMPOSER_ROW_FLOOR +
                SessionPaneLayout.COMPOSER_REGION_PADDING + 40.dp,
        )
    }

    @Test
    fun `the 480 viewport the empty state lives in is zero`() {
        val pane480 = body480 - HEADER_HEIGHT
        val composer480 = SessionPaneLayout.composerCeiling(pane480)
        // The composer takes the whole pane, so the shared arrangement leaves a
        // 0 px viewport — and the band with it.
        assertTrue(SessionPaneLayout.composerSharesViewport(pane480, composer480))
        val viewport480 = (pane480 - composer480).coerceAtLeast(0.dp)
        assertEquals(0.dp, viewport480)
        assertEquals(0.dp, SessionPaneLayout.visibleItemBand(viewport480, composer480, gap))
        // …and the empty state is centred in that 0 px box, so it measures 0:
        // invisible, not clipped — no offset can centre text in no band.
        assertEquals(0.dp, SessionPaneLayout.emptyStateShift(true, viewport480, composer480, gap))
    }

    @Test
    fun `the 420 window keeps its chrome and pays for the band`() {
        // v84 P0: 487 dp is above the short-window line, which is why both
        // earlier rounds left this window out of the ladder — and why it lost
        // the header row and the view tabs. The band is keyed on the body now.
        assertTrue(!ShellChromeBudget.isShortWindow(window420))
        assertEquals(
            SessionPaneLayout.CHROME_FULL_HEIGHT,
            SessionPaneLayout.chromeImeBand(imeVisible = true, body = body420),
        )
        assertEquals(
            0.dp,
            SessionPaneLayout.chromeMaxHeightForComposer(body420, composer420),
        )
        // One-row form (128.38 < 164), and the composer the pane behind the band
        // leaves (44.38 dp) still holds every control.
        assertTrue(SessionPaneLayout.composerOneRow(false, body420))
        val pane420 = body420 - SessionPaneLayout.CHROME_FULL_HEIGHT
        assertNear(44.38f, pane420.value, "420 pane behind the whole band")
        val composerCeiling420 = SessionPaneLayout.composerCeiling(pane420)
        assertTrue(
            "ceiling $composerCeiling420 must hold the controls",
            composerCeiling420 >=
                SessionPaneLayout.COMPOSER_ROW_FLOOR + SessionPaneLayout.COMPOSER_REGION_PADDING,
        )
        // …so the arrangement is the shared one and the whole viewport — empty
        // state included — is 0 dp while the keyboard is up. That is the price
        // of the chrome on this window, and it is the price 480 already pays.
        assertTrue(SessionPaneLayout.composerSharesViewport(pane420, composerCeiling420))
        val viewport420 = (pane420 - composerCeiling420).coerceAtLeast(0.dp)
        assertEquals(0.dp, viewport420)
        assertEquals(0.dp, SessionPaneLayout.visibleItemBand(viewport420, composerCeiling420, gap))
        assertEquals(0.dp, SessionPaneLayout.emptyStateShift(true, viewport420, composerCeiling420, gap))
        // The v74 reading of the same frame is on the same step, so neither
        // reading of the bug's body needs a different ladder.
        assertEquals(
            SessionPaneLayout.CHROME_FULL_HEIGHT,
            SessionPaneLayout.chromeImeBand(imeVisible = true, body = body420v74),
        )
    }

    @Test
    fun `the ladder is monotone across both keyboard-up bodies`() {
        // From the 480 body up to a roomy one, the band only ever steps
        // 0 → 48 → 84, and both measured bodies sit on the 84 side of the 124 dp
        // line while the 480 one sits on the 48 side of it.
        var seenHeader = false
        var seenStrip = false
        var body = 0.dp
        while (body <= 300.dp) {
            val band = SessionPaneLayout.chromeImeBand(true, body)
            if (band > 0.dp) seenHeader = true
            else assertTrue("body $body re-lost the header", !seenHeader)
            if (band >= SessionPaneLayout.CHROME_FULL_HEIGHT) seenStrip = true
            else assertTrue("body $body re-lost the strip", !seenStrip)
            body += 0.5.dp
        }
        assertEquals(
            HEADER_HEIGHT,
            SessionPaneLayout.chromeImeBand(true, body480),
        )
        assertEquals(
            SessionPaneLayout.CHROME_FULL_HEIGHT,
            SessionPaneLayout.chromeImeBand(true, body420),
        )
        // The composer ceiling never takes the pane negative and never exceeds
        // it, on either measured body.
        for (pane in listOf(body480 - HEADER_HEIGHT, body420, body420v74)) {
            val ceiling = SessionPaneLayout.composerCeiling(pane)
            assertTrue("pane $pane leaves $ceiling", ceiling in 0.dp..pane)
        }
    }
}
