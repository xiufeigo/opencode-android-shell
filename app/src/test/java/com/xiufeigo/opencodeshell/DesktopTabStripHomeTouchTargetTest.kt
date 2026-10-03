package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.components.touchTargetOffsetPx
import com.xiufeigo.opencodeshell.ui.nav.TAB_HEIGHT
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v54 P1（`research/v54_verify/tD/39_home_shrunk.png`、`37`、`11`）: the desktop
 * title bar's Home（grid）button — the only way back out of a session — carried a
 * ~29 dp tap box once the tab list overflowed and the strip scrolled: the tab
 * row was drawn hard against it and the first tab's title was clipped by it,
 * with the finger area far below the 48 dp floor every other shell control
 * keeps.
 *
 * The glyph stays the upstream 28 dp square; only the **finger** grows, via the
 * same `ocMinTouchTarget` the close button and the row actions use.
 */
class DesktopTabStripHomeTouchTargetTest {

    private fun source(relative: String): String {
        val file = File(relative).takeIf { it.isFile } ?: File("app/$relative")
        return file.readText().replace("\r\n", "\n")
    }

    private fun strip() = source("src/main/java/com/xiufeigo/opencodeshell/ui/nav/DesktopTabStrip.kt")

    @Test
    fun theHomeButtonAsksForTheMinimumFinger() {
        val body = strip()
        assertTrue(
            "Home must keep a 48 dp finger on top of its 28 dp glyph square",
            body.contains("minTouchTarget = true"),
        )
        assertTrue(
            "and the ring's trailing overflow must not reach the scrolling tabs",
            body.contains("Modifier.width(TAB_HEIGHT + 10.dp)"),
        )
    }

    @Test
    fun theButtonKeepsTheUpstreamGlyphSize() {
        // `titlebar-v2`'s `h-7` square is the *visual*; the touch target is the
        // opt-in overlay, so the bar's height budget is unchanged.
        assertEquals(28f, TAB_HEIGHT.value, 0f)
    }

    @Test
    fun theFingerOverflowsTheGlyphSymmetricallyWithoutTouchingTheTabs() {
        // Density 2.0: a 28 dp glyph, a 48 dp finger. The ring is centred, so it
        // reaches 10 dp out on each side; the wrapper reserves exactly the
        // trailing 10 dp, which is what keeps it off the scroll viewport.
        val glyph = 56
        val finger = 96
        assertEquals(-20, touchTargetOffsetPx(visualPx = glyph, targetPx = finger))
        val offset = touchTargetOffsetPx(visualPx = glyph, targetPx = finger)
        val wrapper = glyph + 20
        assertTrue(
            "the finger must end at the wrapper edge, not past it",
            offset + finger == wrapper,
        )
    }

    @Test
    fun theOptInIsDefaultedOffSoTheBottomStripIsUntouched() {
        val button = source("src/main/java/com/xiufeigo/opencodeshell/ui/nav/BottomTabStrip.kt")
        assertTrue(
            "the shared button must default to the old 28 dp box",
            button.contains("minTouchTarget: Boolean = false"),
        )
        assertTrue(
            "and only opt in through the overlay, never by growing the square",
            button.contains("Modifier.ocMinTouchTarget(visual = TAB_HEIGHT)"),
        )
    }
}
