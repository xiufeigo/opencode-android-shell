package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.components.touchTargetOffsetPx
import com.xiufeigo.opencodeshell.ui.nav.HOME_FINGER_INSET
import com.xiufeigo.opencodeshell.ui.nav.TAB_HEIGHT
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v56 P1 — the Home (grid) button's finger was still 28 dp after the v54
 * `ocMinTouchTarget` opt-in, measured on the device
 * (`research/v56_verify/tD/33_home_shrunk.png`, bounds `[0,144][74,254]`).
 *
 * **Why the ring never happened.** `ocMinTouchTarget` is a `layout` modifier:
 * it measures its child at the 48 dp target, places that box centred on the
 * visual one, and reports [visual] to the parent. It was chained **before**
 * `Modifier.size(TAB_HEIGHT)`, and `size` enforces its own size on the child it
 * measures whatever constraints it is handed — so the 48 dp box was collapsed
 * straight back to 28 dp, and the `-10 dp` placement offset slid that 28 dp box
 * up and to the left, half of it past the window edge. The measured node was
 * exactly that: a 28 dp square whose left side had been clipped away.
 *
 * The order is the whole fix: the ring has to be the **outermost** size-defining
 * modifier, so nothing downstream re-clamps it.
 */
class TabIconTouchTargetRingTest {

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("missing $relative")

    private fun button() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/nav/BottomTabStrip.kt")

    private fun strip() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/nav/DesktopTabStrip.kt")

    @Test
    fun theRingIsAppliedAfterTheSizeSoNothingClampsItBack() {
        val body = button()
        val size = body.indexOf(".size(TAB_HEIGHT)")
        val ring = body.indexOf("Modifier.ocMinTouchTarget(visual = TAB_HEIGHT)")
        assertTrue("the button must still size itself to the 28 dp glyph", size > 0)
        assertTrue("the ring must still be wired", ring > 0)
        assertTrue(
            "ocMinTouchTarget must come AFTER .size: `size` enforces its own " +
                "size on the node it measures, so a ring inside it collapses " +
                "back to the glyph and is displaced out of the window",
            ring > size,
        )
    }

    @Test
    fun theReportedSizeIsStillTheGlyphWhileTheFingerIsTheFloor() {
        // Density 2.0: a 28 dp glyph (56 px), a 48 dp finger (96 px). The ring
        // is centred, so it reaches 10 dp out on each side and nothing inside
        // the ring — the node the parent lays out — changes size.
        val glyph = 56
        val finger = 96
        assertEquals(-20, touchTargetOffsetPx(visualPx = glyph, targetPx = finger))
        assertEquals(28f, TAB_HEIGHT.value, 0f)
    }

    @Test
    fun theLeadingInsetKeepsTheWholeFingerOnTheWindow() {
        // The ring overflows `HOME_FINGER_INSET` per side. Home is the row's
        // first child, so without a leading reserve the finger starts 10 dp -
        // 8 dp of the strip's start padding - 10 dp of ring = -2 dp, and
        // uiautomator reports the clipped remainder (46 dp), not 48.
        assertEquals(10f, HOME_FINGER_INSET.value, 0f)
        val strip = strip()
        assertTrue(
            "the wrapper must reserve the ring's leading overflow too",
            strip.contains("Modifier.padding(start = HOME_FINGER_INSET)"),
        )
    }

    @Test
    fun theBottomStripIsUnchangedByDefault() {
        // The opt-in still defaults off: only the desktop Home asks for a ring.
        assertTrue(button().contains("minTouchTarget: Boolean = false"))
    }
}
