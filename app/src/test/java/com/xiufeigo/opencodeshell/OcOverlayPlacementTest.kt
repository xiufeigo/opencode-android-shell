package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.components.ocOverlayContentTopPx
import com.xiufeigo.opencodeshell.ui.components.ocOverlayDecorInsetPx
import com.xiufeigo.opencodeshell.ui.components.ocOverlayY
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v58 (`research/v57_verify/emu5556`): a `Popup` is positioned against the window
 * **frame**, so on a freeform window the model picker came out one caption strip
 * too low — and flipped up, that much too high, over the caption
 * (`E2ECHK5556-09-model-select.png`, ~27 px over the content's top edge).
 *
 * v58 retest (`research/v58_verify/5556/05_model_ime.png`): the decor reading is
 * **0** in the shape the first fix never covered. In desktop mode the decor *is*
 * the content view (768 px both on 5556), and `adjustResize` shrinks both by the
 * keyboard height, so `root − content` stays 0 with the IME up as well — the
 * clamp then pinned the overlay to y=0, over the caption, worse than the 27 px
 * it replaced. The caption reserve ([desktopCaptionReserve], 36 dp on a freeform
 * window) does not move with the keyboard, so the inset takes the **larger** of
 * the two readings.
 *
 * [ocOverlayY] puts the overlay back in the content area's own coordinates and
 * clamps it inside that area. All of it is pure integer math, pinned here.
 */
class OcOverlayPlacementTest {

    @Test
    fun theDecorReadingIsTheCaptionStripWhereTheWindowManagerInsetsTheWindow() {
        assertEquals(55, ocOverlayDecorInsetPx(rootHeightPx = 1280, contentHeightPx = 1225))
        assertEquals(
            "a phone window with no caption is untouched",
            0,
            ocOverlayDecorInsetPx(1280, 1280),
        )
        assertTrue("measurements that have not landed read as 0", ocOverlayDecorInsetPx(0, 0) == 0)
        assertTrue(ocOverlayDecorInsetPx(0, 1225) == 0)
    }

    @Test
    fun theCaptionReserveCarriesTheDesktopFormTheDecorReadingCannotSee() {
        // 5556 (Small_Desktop, 1366x768@160dpi): the app window frame is the
        // whole screen, so decor == content and the decor reading is 0 even
        // though the window manager paints a 36 dp caption over the app's top.
        assertEquals(
            36,
            ocOverlayContentTopPx(
                decorInsetPx = ocOverlayDecorInsetPx(rootHeightPx = 768, contentHeightPx = 768),
                captionReservePx = 36,
            ),
        )
        // …and with the IME up `adjustResize` has shrunk both views by the same
        // keyboard height: still 0 from the decor, still 36 from the reserve.
        assertEquals(
            36,
            ocOverlayContentTopPx(
                decorInsetPx = ocOverlayDecorInsetPx(rootHeightPx = 380, contentHeightPx = 380),
                captionReservePx = 36,
            ),
        )
    }

    @Test
    fun theLargerOfTheTwoReadingsWins() {
        assertEquals(
            "a window the WM insets by 55 px beats the 36 dp reserve",
            55,
            ocOverlayContentTopPx(decorInsetPx = 55, captionReservePx = 36),
        )
        assertEquals(
            "a phone window reserves nothing and insets nothing",
            0,
            ocOverlayContentTopPx(decorInsetPx = 0, captionReservePx = 0),
        )
        assertTrue(
            "negative readings (a measurement that has not landed) never move the overlay",
            ocOverlayContentTopPx(decorInsetPx = -10, captionReservePx = -4) == 0,
        )
    }

    @Test
    fun theModelPickerLandsBelowTheCaptionWhileTheKeyboardIsUp() {
        // The v58 retest case, end to end: 5556 with the IME open. The popup is
        // 500 px tall and the resized window is 380 px — the clamp has no room,
        // so the only thing standing between the overlay and y=0 is the inset.
        val contentTop = ocOverlayContentTopPx(
            decorInsetPx = ocOverlayDecorInsetPx(rootHeightPx = 380, contentHeightPx = 380),
            captionReservePx = 36,
        )
        assertEquals(
            36,
            ocOverlayY(
                desiredContentY = -120,
                contentTopPx = contentTop,
                windowHeightPx = 380,
                overlayHeightPx = 500,
            ),
        )
    }

    @Test
    fun thePlacementIsShiftedIntoTheContentArea() {
        // 1280-tall freeform window, 55 px caption: a picker anchored just above
        // the composer (desired y = 200 in content coords) is placed at 255 in
        // frame coords — its top edge lands on the content's top, not over the
        // caption.
        assertEquals(
            255,
            ocOverlayY(
                desiredContentY = 200,
                contentTopPx = 55,
                windowHeightPx = 1280,
                overlayHeightPx = 1025,
            ),
        )
    }

    @Test
    fun anOverlayTallerThanTheContentAreaStillStartsAtItsTopEdge() {
        assertEquals(
            55,
            ocOverlayY(
                desiredContentY = -900,
                contentTopPx = 55,
                windowHeightPx = 1280,
                overlayHeightPx = 1400,
            ),
        )
    }

    @Test
    fun aWindowWithNoCaptionKeepsThePlacementByteIdentical() {
        assertEquals(
            200,
            ocOverlayY(
                desiredContentY = 200,
                contentTopPx = 0,
                windowHeightPx = 1280,
                overlayHeightPx = 1025,
            ),
        )
    }

    @Test
    fun theBottomIsClampedInsideTheWindow() {
        assertEquals(
            "a short overlay that would hang past the bottom edge is pulled up",
            755,
            ocOverlayY(
                desiredContentY = 900,
                contentTopPx = 55,
                windowHeightPx = 1280,
                overlayHeightPx = 525,
            ),
        )
    }

    @Test
    fun theModelPickerRunsThroughTheSharedPlacement() {
        val source = File("src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/ModelChip.kt")
            .takeIf { it.isFile }
            ?: File("app/src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/ModelChip.kt")
        assertTrue("missing ModelChip.kt", source.isFile)
        val text = source.readText()
        assertTrue(text.contains("y = ocOverlayY("))
        assertTrue(text.contains("ocOverlayContentTopPx("))
        // The caption reserve has to reach the placement: the decor reading alone
        // is 0 on the desktop form (v58 retest FAIL).
        assertTrue(text.contains("desktopCaptionReserve()"))
        assertTrue(text.contains("captionReservePx = captionReservePx"))
    }
}
