package com.xiufeigo.opencodeshell

import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.text.AnnotatedString
import com.xiufeigo.opencodeshell.ui.session.composer.modelChipMaxWidth
import com.xiufeigo.opencodeshell.ui.settings.APPEARANCE_PANEL
import com.xiufeigo.opencodeshell.ui.settings.SettingsControl
import com.xiufeigo.opencodeshell.ui.settings.copyPairingDetails
import com.xiufeigo.opencodeshell.ui.settings.terminalFontOptions
import com.xiufeigo.opencodeshell.ui.theme.OcSystemFonts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v40 real-device fixes (evidence in `research/v40_verify/tD`):
 *
 * * tD_22 / tD_27 / tD_80 — the Appearance page's Terminal Font control looked
 *   dead next to the two working font dropdowns: it was a free-text field
 *   whose value Android cannot resolve, with no arrow and nothing to open;
 * * tD_34 — Pairing's "Copy details" flipped its label for 2 s and that was the
 *   only feedback, so the tap read as a no-op;
 * * tD_70 (720x1280 @420dpi) — the composer's model name ran under the submit
 *   button and the picker's chevron left the visible row entirely.
 */
class ShellSmallFixesV40Test {

    @Test
    fun terminalFontIsAMonoFamilySelectNotAFreeTextField() {
        val row = APPEARANCE_PANEL.single().rows.first { it.action == "settings-terminal-font" }
        assertEquals(SettingsControl.SELECT, row.control)
        // The declared table (no probed device) already carries the default plus
        // the always-resolvable mono stop, so the row is never an empty picker.
        assertTrue(row.options.isNotEmpty())
        assertEquals("", row.options.first().value)
        assertTrue(row.options.any { it.value == "serif-monospace" })
    }

    @Test
    fun terminalFontOptionsExtendWithTheMonoFamiliesTheDeviceHas() {
        val options = terminalFontOptions(OcSystemFonts.availableMono())
        assertEquals(options.first().value, "")
        assertEquals(options.map { it.value }.distinct(), options.map { it.value })
        // No stop Android cannot resolve: every offered value is a mono family
        // the picker knows how to render.
        val known = OcSystemFonts.monoStops(OcSystemFonts.availableMono()).toSet()
        assertTrue(options.drop(1).all { it.value in known })
    }

    @Test
    fun theAppearanceRowExtendsTheTerminalFontStopsAtRenderTime() {
        val source = sourceOf(
            "src/main/java/com/xiufeigo/opencodeshell/ui/settings/SettingsPanels.kt",
        )
        assertTrue(
            source.contains("\"settings-terminal-font\" -> remember { terminalFontOptions("),
        )
    }

    @Test
    fun copyDetailsWritesThePairingUrlAndConfirmsOnlyWhenItDid() {
        val clipboard = FakeClipboard()
        assertTrue(copyPairingDetails(clipboard, "http://10.0.2.2:4095/connect#abc"))
        assertEquals("http://10.0.2.2:4095/connect#abc", clipboard.copied?.text)

        val empty = FakeClipboard()
        assertFalse(copyPairingDetails(empty, ""))
        assertEquals(null, empty.copied)
    }

    @Test
    fun copyDetailsShowsAConfirmationTheTwoSecondLabelSwapCannotHide() {
        val source = sourceOf(
            "src/main/java/com/xiufeigo/opencodeshell/ui/settings/PairingPanel.kt",
        )
        // A toast survives long enough to be noticed, and the button also reads
        // "Copied" while the inline confirmation is up.
        assertTrue(source.contains("R.string.oc_copy_details_copied"))
        assertTrue(source.contains("Toast.makeText("))
        assertTrue(source.contains("copyPairingDetails(clipboard, pairingUrl)"))
    }

    @Test
    fun theModelChipNeverOutgrowsTheWindowItIsDrawnIn() {
        // 720x1280 @420dpi = 274 dp: the old fixed 220 dp chip overflowed the
        // controls viewport and took the chevron off screen.
        val narrow = modelChipMaxWidth(274)
        assertTrue("narrow window", narrow.value < 220f)
        // A normal phone still stays inside the controls viewport: v43 raised
        // the old `max-w-[220px]` cap (the label must fit before it
        // ellipsizes), so the bound is now the window share, not 220 dp.
        assertEquals(372f.coerceAtMost(320f), modelChipMaxWidth(600).value, 0.01f)
        assertTrue(modelChipMaxWidth(600).value < 600f)
        assertTrue(modelChipMaxWidth(0).value >= 0f)
    }

    @Test
    fun theModelLabelYieldsSpaceSoTheChevronSurvives() {
        val source = sourceOf(
            "src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/ModelChip.kt",
        )
        assertTrue(
            // v57 P0: the window share is still the ceiling; the composer now
            // additionally passes the measured controls viewport, which is the
            // bound that actually protects a short pane.
            source.contains("modelChipMaxWidth(LocalConfiguration.current.screenWidthDp, maxWidth)"),
        )
        // Only the label is elastic; the chevron keeps its 14 dp.
        assertTrue(source.contains("Modifier.weight(1f, fill = false)"))
    }

    private fun sourceOf(relative: String): String {
        val file = File(relative).takeIf { it.isFile } ?: File("app/$relative")
        return file.readText()
    }

    private class FakeClipboard : ClipboardManager {
        var copied: AnnotatedString? = null
        override fun getText(): AnnotatedString? = copied
        override fun hasText(): Boolean = copied != null
        override fun setText(annotatedString: AnnotatedString) {
            copied = annotatedString
        }
    }
}
