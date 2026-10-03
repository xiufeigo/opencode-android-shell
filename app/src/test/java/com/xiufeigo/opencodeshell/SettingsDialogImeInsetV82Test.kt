package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.settings.SETTINGS_CARD_GUTTER_DP
import com.xiufeigo.opencodeshell.ui.settings.SETTINGS_CARD_MAX_HEIGHT_DP
import com.xiufeigo.opencodeshell.ui.settings.SETTINGS_CARD_MIN_HEIGHT_DP
import com.xiufeigo.opencodeshell.ui.settings.settingsDialogCardHeightDp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v82 「设置弹层不做 IME inset」 — the settings card's geometry.
 *
 * Device evidence (`research/v82_verify/5556/`, 1366x768 @160dpi so 1 px = 1 dp):
 * `40_set.png` (keyboard down) and `20_sc.png` / `44_typed.png` (keyboard up) put
 * the dialog card at the **same** y=84..683 box in both states — the keyboard
 * (y=342..768) simply covers its lower half, leaving the Shortcuts rows and the
 * server form's Save behind the IME with nothing to scroll them into reach (the
 * card fits the window, so no inner scroller engages).
 *
 * The cause is `screenHeightDp`: it is the *display* height and does not change
 * while the keyboard is up (`OcActivity` is edge-to-edge — the read
 * `paletteShell`'s KDoc records), so upstream's `min(100vh − 92, 600)` card kept
 * its full height. The card now gives way to `WindowInsets.ime`; these tests pin
 * the arithmetic and the chain that applies it.
 */
class SettingsDialogImeInsetV82Test {

    private fun source(relative: String): String =
        listOf("app/$relative", relative)
            .map { File(it) }
            .firstOrNull { it.isFile }
            ?.readText()?.replace("\r\n", "\n")
            ?: error("missing $relative")

    private fun settingsScreen() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/settings/SettingsScreen.kt")

    /** The container the settings layer mounts (`SettingsDialogContent`). */
    private fun cardWindow(): String {
        val screen = settingsScreen()
        val start = screen.indexOf("private fun BoxWithCappedSize(")
        assertTrue("BoxWithCappedSize must exist", start >= 0)
        val end = screen.indexOf("private fun SettingsNavList(", start)
        assertTrue("missing the nav after the card", end > start)
        return screen.substring(start, end)
    }

    // ------------------------------------------------------ the card's height

    @Test
    fun withTheKeyboardDownTheCardIsUpstreamsUnchanged() {
        // The 1366x768 desktop emulator with no keyboard: `min(768 − 92, 600)`.
        assertEquals(600f, settingsDialogCardHeightDp(windowHeightDp = 768f))
        // The 600 dp ceiling holds on a tall window…
        assertEquals(SETTINGS_CARD_MAX_HEIGHT_DP, settingsDialogCardHeightDp(windowHeightDp = 1400f))
        // …a short one shrinks by the gutter…
        assertEquals(308f, settingsDialogCardHeightDp(windowHeightDp = 400f))
        // …and an unusable one keeps the 240 dp floor (`SettingsPanelLayout`
        // treats a 0/negative window as "unknown" and keeps its full header).
        assertEquals(SETTINGS_CARD_MIN_HEIGHT_DP, settingsDialogCardHeightDp(windowHeightDp = 200f))
        assertEquals(SETTINGS_CARD_MIN_HEIGHT_DP, settingsDialogCardHeightDp(windowHeightDp = 0f))
        assertEquals(SETTINGS_CARD_GUTTER_DP, 92f)
    }

    @Test
    fun withTheKeyboardUpTheCardGivesWayToIt() {
        // The measured device state: a 426 px keyboard on the 768 px window. The
        // card takes the whole visible band (342), not the 258 px slice that used
        // to be all of it that was on screen (`84..342` of the 600 dp box).
        val window = 768f
        val ime = 426f
        val visible = window - ime
        val oldOnScreenSlice = visible - (window - SETTINGS_CARD_MAX_HEIGHT_DP) / 2f
        assertEquals(258f, oldOnScreenSlice)
        assertEquals(visible, settingsDialogCardHeightDp(windowHeightDp = window, imeBottomDp = ime))
        assertTrue(
            "the card must grow past the slice the IME used to leave",
            settingsDialogCardHeightDp(windowHeightDp = window, imeBottomDp = ime) > oldOnScreenSlice,
        )
    }

    @Test
    fun theGutterIsNotTakenTwiceUnderTheKeyboard() {
        // A 200 dp keyboard on the same window leaves 568 dp: the card fits above
        // it and does not spend another 92 dp of a keyboard-sized band.
        assertEquals(568f, settingsDialogCardHeightDp(windowHeightDp = 768f, imeBottomDp = 200f))
        // The card is never taller than the space above the keyboard…
        for (ime in listOf(0f, 120f, 200f, 426f, 700f)) {
            val height = settingsDialogCardHeightDp(windowHeightDp = 768f, imeBottomDp = ime)
            assertTrue(
                "ime=$ime: $height must fit $768 minus $ime",
                height <= 768f - ime || height == SETTINGS_CARD_MIN_HEIGHT_DP,
            )
        }
        // …and a nonsense inset never grows it.
        assertEquals(
            settingsDialogCardHeightDp(windowHeightDp = 768f),
            settingsDialogCardHeightDp(windowHeightDp = 768f, imeBottomDp = -50f),
        )
    }

    // ------------------------------------------------------- the applied chain

    @Test
    fun theCardContainerLiftsItselfAboveTheKeyboard() {
        val window = cardWindow()
        assertTrue(
            "the card's height must come from the IME-aware helper",
            window.contains("settingsDialogCardHeightDp("),
        )
        assertFalse(
            "the raw `min(100vh − 92, 600)` height is the bug being fixed",
            window.contains("minOf(config.screenHeightDp.dp - 92.dp, 600.dp)"),
        )
        assertTrue(
            "the keyboard inset must be read",
            window.contains("WindowInsets.ime.getBottom(this)"),
        )
        // The lift itself, and the top clearance that keeps the card's own Close
        // row tappable once the layer's window-centring has been cancelled.
        assertTrue(window.contains("if (imeUp)"))
        assertTrue(window.contains(".imePadding()"))
        assertTrue(window.contains(".statusBarsPadding()"))
        assertTrue(window.contains("padding(top = desktopCaptionReserve())"))
        // …and the padding is *outside* the fixed height. Compose applies a
        // modifier chain outermost-first, so `.height(h).imePadding()` makes the
        // card h tall *including* the keyboard band — the content collapses to
        // zero. The band has to wrap the height, not sit inside it.
        val lift = window.indexOf(".imePadding()")
        val height = window.indexOf(".height(height)")
        assertTrue("the IME chain must precede .height(height)", lift in 1 until height)
    }

    @Test
    fun theSettingsLayerIsTheContainerThisFixes() {
        // The card is mounted by the settings dialog itself, so the fix reaches
        // the Shortcuts list and the Servers panel (whose form is a sibling of the
        // panel body inside the same card).
        val screen = settingsScreen()
        val content = screen.substringAfter("fun SettingsDialogContent(")
        assertTrue(content.contains("BoxWithCappedSize(modifier)"))
        assertTrue(content.contains("Modifier.fillMaxSize().background(c.bgBase)"))
    }

    @Test
    fun thePanelBodyIsStillTheScrollerThatReachesTheRows() {
        // The fix only makes the card give way; the reachability comes from the
        // panel body scrolling inside the (now bounded) panel slot.
        val screen = settingsScreen()
        val slot = screen.substringAfter("private fun PanelSlot(")
        assertTrue(slot.contains("Modifier.weight(1f)"))
        val body = screen.substringAfter("internal fun SettingsPanelBody(")
            .substringBefore("internal fun OcTag(")
        assertTrue(body.contains("verticalScroll(scrollState)"))
    }
}
