package com.xiufeigo.opencodeshell

import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.xiufeigo.opencodeshell.ui.settings.SettingsPanelLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Small-window fix for the Pairing QR
 * (`ui/settings/SettingsScreen.kt` + `ui/settings/PairingPanel.kt`).
 *
 * Root cause: every settings panel was `fixed header + inner-scroll body`, and
 * the header's `top:40/bottom:32` insets plus title/description ate ~150dp
 * before the scroll region even started. On a ~400dp dialog the body was left
 * ~0dp tall with the QR inside it — while the layer as a whole still fit the
 * viewport, so the `DialogHost` over-viewport fallback never engaged.
 *
 * The fix is two halves: short windows compact the shared header insets
 * ([SettingsPanelLayout]), and the Pairing page scrolls as one column (header
 * included) with its inner body scroll disabled. Pure JVM: the height policy
 * plus source pins for the Compose wiring, per the repo's established style.
 */
class PairingSmallWindowTest {

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("missing $relative")

    @Test
    fun shortWindowsCompactTheHeader() {
        // A 400dp dialog is `100vh − 92`, i.e. a ~492dp window.
        assertTrue(SettingsPanelLayout.compactHeader(492))
        assertTrue(SettingsPanelLayout.compactHeader(400))
        assertTrue(SettingsPanelLayout.compactHeader(1))
    }

    @Test
    fun normalWindowsKeepTheDocumentedHeader() {
        assertFalse(SettingsPanelLayout.compactHeader(560))
        assertFalse(SettingsPanelLayout.compactHeader(600))
        assertFalse(SettingsPanelLayout.compactHeader(800))
        // Unknown windows never compact: no behavior change without a measurement.
        assertFalse(SettingsPanelLayout.compactHeader(0))
        assertFalse(SettingsPanelLayout.compactHeader(-1))
    }

    @Test
    fun fullHeaderKeepsTheDocumentedInsets() {
        val padding = SettingsPanelLayout.headerPadding(800)
        assertEquals(40.dp, padding.calculateTopPadding())
        assertEquals(32.dp, padding.calculateBottomPadding())
        assertEquals(40.dp, padding.calculateStartPadding(LayoutDirection.Ltr))
        assertEquals(40.dp, padding.calculateEndPadding(LayoutDirection.Ltr))
    }

    @Test
    fun compactHeaderShrinksOnlyTheVerticalInsets() {
        val padding = SettingsPanelLayout.headerPadding(492)
        assertEquals(16.dp, padding.calculateTopPadding())
        assertEquals(12.dp, padding.calculateBottomPadding())
        assertEquals(40.dp, padding.calculateStartPadding(LayoutDirection.Ltr))
        assertEquals(40.dp, padding.calculateEndPadding(LayoutDirection.Ltr))
    }

    @Test
    fun bodyDefaultsToItsOwnScroll() {
        // Every other panel keeps the historical fixed-header layout byte-identical.
        val screen = source("src/main/java/com/xiufeigo/opencodeshell/ui/settings/SettingsScreen.kt")
        assertTrue(screen.contains("scroll: Boolean = true"))
    }

    @Test
    fun pairingScrollsAsOneColumnWithTheHeaderInside() {
        val panel = source("src/main/java/com/xiufeigo/opencodeshell/ui/settings/PairingPanel.kt")
        // One outer scroll owns the page; the inner body must not nest a second one.
        assertTrue(panel.contains("SettingsPanelBody(scroll = false)"))
        val scrollAt = panel.indexOf("fillMaxSize().verticalScroll(")
        val headerAt = panel.indexOf("SettingsPanelHeader(")
        val bodyAt = panel.indexOf("SettingsPanelBody(scroll = false)")
        assertTrue("outer scroll must wrap header and body", scrollAt >= 0 && headerAt > scrollAt && bodyAt > headerAt)
    }
}
