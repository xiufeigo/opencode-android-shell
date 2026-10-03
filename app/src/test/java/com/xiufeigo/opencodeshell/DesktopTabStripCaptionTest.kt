package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.nav.DESKTOP_CAPTION_HEIGHT
import com.xiufeigo.opencodeshell.ui.nav.desktopCaptionReserve
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v40 tB: the trailing `>_` button of the top tab bar did nothing — on Home and
 * on a session, every tap, while `Ctrl+K` opened the same palette in the same
 * frame. The wiring was never at fault: [com.xiufeigo.opencodeshell.PaletteEntryPointsTest]
 * pins it, and `onOpenPalette` is a plain `var` captured by reference, so the
 * lambda the button holds always calls the current `openPalette`.
 *
 * A probe on the v40 build found the real cause. `input tap` on `>_` did
 * nothing — and so did `+`, and the Home button, and a tab: **the whole bar was
 * dead**, while the session header's own buttons (a few dp lower) answered
 * immediately. `dumpsys window` then showed why: the bar lives in the app
 * window's top 36 dp, which in the desktop/freeform window the run used is the
 * **window caption** — the — ▭ ✕ row the window manager paints over the app and
 * keeps the touches for. Tapping that row's ✕ closed the window, which is the
 * "RemoveTask" the tester saw.
 *
 * The strip still paints the full width there (it is the window's background);
 * what moves is the row of controls, below the caption.
 */
class DesktopTabStripCaptionTest {

    private fun source(relative: String): String {
        val file = File(relative).takeIf { it.isFile } ?: File("app/$relative")
        return file.readText().replace("\r\n", "\n")
    }

    private fun strip() = source("src/main/java/com/xiufeigo/opencodeshell/ui/nav/DesktopTabStrip.kt")

    // ------------------------------------------------------------- the reserve

    @Test
    fun aWindowWithNoTopSystemFurnitureKeepsTheCaptionBandClear() {
        // A phone window always overlaps the status bar or a cutout, so a zero
        // top inset is a desktop/freeform window: the caption owns that band.
        assertEquals(36f, desktopCaptionReserve(topSystemInsetPx = 0).value, 0f)
        assertEquals(36f, desktopCaptionReserve(topSystemInsetPx = -1).value, 0f)
    }

    @Test
    fun aWindowUnderTheStatusBarIsLeftAlone() {
        // 48 px is this emulator's status bar at density 2.0.
        assertEquals(0f, desktopCaptionReserve(topSystemInsetPx = 48).value, 0f)
    }

    @Test
    fun theReserveIsOneTitlebarTall() {
        // The caption the run measured is the same 36 dp as `titlebar-v2`'s
        // `h-9` bar, so the controls land exactly at its lower edge.
        assertEquals(36f, DESKTOP_CAPTION_HEIGHT.value, 0f)
    }

    // ------------------------------------------------------------- the wiring

    @Test
    fun theBarStillPaintsTheFullWidthAndOnlyTheRowOfControlsMoves() {
        val body = strip()
        assertTrue(body.contains(".fillMaxWidth()"))
        assertTrue(body.contains(".background(c.bgDeep)"))
        assertTrue(
            "the controls are padded below the caption",
            body.contains(".padding(top = captionReserve, start = 8.dp, end = 8.dp)"),
        )
        assertTrue(
            "and the bar grows by exactly that reserve, so no content is clipped",
            body.contains(".height(BAR_HEIGHT + captionReserve)"),
        )
        // …while the system-bar behaviour the two placements share is untouched.
        assertTrue(body.contains(".windowInsetsPadding(WindowInsets.statusBars)"))
    }

    @Test
    fun theReserveIsReadFromTheWindowsOwnInsets() {
        val body = strip()
        assertTrue(body.contains("WindowInsets.statusBars.getTop(density)"))
        assertTrue(body.contains("WindowInsets.displayCutout.getTop(density)"))
        // A remembered value would go stale when the window moves between the
        // status bar and a cutout, so it is recomputed with the composition.
        assertTrue(!body.contains("remember { desktopCaptionReserve("))
    }

    @Test
    fun thePaletteButtonIsStillTheTrailingSlot() {
        // The v40 tB fix moved rows around the bar; the upstream order the entry
        // points test pins must survive it.
        val body = strip()
        assertTrue(body.lastIndexOf("R.string.oc_palette_title") > body.lastIndexOf("R.string.oc_new_session"))
        assertTrue(body.contains("onClick = onOpenPalette"))
    }
}
