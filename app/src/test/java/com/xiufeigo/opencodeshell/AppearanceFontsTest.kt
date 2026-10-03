package com.xiufeigo.opencodeshell

import androidx.compose.ui.text.font.FontFamily
import com.xiufeigo.opencodeshell.ui.settings.APPEARANCE_PANEL
import com.xiufeigo.opencodeshell.ui.settings.CODE_FONT_OPTIONS
import com.xiufeigo.opencodeshell.ui.settings.HIDDEN_SETTINGS
import com.xiufeigo.opencodeshell.ui.settings.SettingsControl
import com.xiufeigo.opencodeshell.ui.settings.THEME_OPTIONS
import com.xiufeigo.opencodeshell.ui.settings.UI_FONT_OPTIONS
import com.xiufeigo.opencodeshell.ui.settings.appearancePanelActions
import com.xiufeigo.opencodeshell.ui.settings.codeFontOptions
import com.xiufeigo.opencodeshell.ui.settings.uiFontOptions
import com.xiufeigo.opencodeshell.ui.theme.CODE_FONT_FOLLOW_UI
import com.xiufeigo.opencodeshell.ui.theme.InterFontFamily
import com.xiufeigo.opencodeshell.ui.theme.OcMonoFontFamily
import com.xiufeigo.opencodeshell.ui.theme.OcSystemFonts
import com.xiufeigo.opencodeshell.ui.theme.OcThemeRegistry
import com.xiufeigo.opencodeshell.ui.theme.UI_FONT_SYSTEM
import com.xiufeigo.opencodeshell.ui.theme.codeFontFamily
import com.xiufeigo.opencodeshell.ui.theme.uiFontFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * U-1 — the Appearance rows upstream renders that are live again
 * (`settings/appearance/appearance.tsx:64-131`): the theme picker plus the
 * UI/code font selects, and the stored-value → family mapping behind them.
 */
class AppearanceFontsTest {

    @Test
    fun appearancePanelRendersTheFullUpstreamRowOrder() {
        assertEquals(
            listOf(
                "settings-color-scheme",
                "settings-theme",
                "settings-ui-font",
                "settings-code-font",
                "settings-terminal-font",
            ),
            appearancePanelActions(),
        )
        val panel = APPEARANCE_PANEL.single()
        assertEquals(SettingsControl.SELECT, panel.rows.first { it.action == "settings-theme" }.control)
        assertEquals(SettingsControl.SELECT, panel.rows.first { it.action == "settings-ui-font" }.control)
        assertEquals(SettingsControl.SELECT, panel.rows.first { it.action == "settings-code-font" }.control)
    }

    @Test
    fun appearanceRowsAreNoLongerHidden() {
        val hidden = HIDDEN_SETTINGS.toSet()
        assertFalse(hidden.contains("settings-theme"))
        assertFalse(hidden.contains("settings-ui-font"))
        assertFalse(hidden.contains("settings-code-font"))
    }

    @Test
    fun themeOptionsMirrorTheRegistry() {
        // The generic SELECT renderer paints these, so the row satisfies the
        // "every SELECT carries options" structure invariant with the real
        // 36-option list, not a placeholder.
        assertEquals(OcThemeRegistry.ORDERED_IDS, THEME_OPTIONS.map { it.value })
        assertEquals("OpenCode", THEME_OPTIONS.first().label)
        assertEquals("Dracula", THEME_OPTIONS.first { it.value == "dracula" }.label)
        assertTrue(THEME_OPTIONS.all { it.labelResId == 0 && it.label != null })
        val row = APPEARANCE_PANEL.single().rows.first { it.action == "settings-theme" }
        assertEquals(THEME_OPTIONS, row.options)
    }

    @Test
    fun uiFontOptionsAreInterDefaultThenTheSystemFamilies() {
        // Blank = upstream's empty-means-default (`sansInput`); the store reads
        // an unset key as "" too, so the select pre-selects Inter.
        assertEquals(
            listOf("", UI_FONT_SYSTEM) + OcSystemFonts.SANS_ALIASES,
            UI_FONT_OPTIONS.map { it.value },
        )
        assertEquals("system", UI_FONT_SYSTEM)
        // The generic aliases are our names for a platform slot, so they carry
        // translated copy — unlike a real family name (asserted below).
        assertTrue(UI_FONT_OPTIONS.all { it.labelResId != 0 && it.label == null })
    }

    @Test
    fun codeFontOptionsAreMonoDefaultThenTheMonoFamilies() {
        assertEquals(
            listOf("", CODE_FONT_FOLLOW_UI) + OcSystemFonts.MONO_ALIASES,
            CODE_FONT_OPTIONS.map { it.value },
        )
        assertEquals("ui", CODE_FONT_FOLLOW_UI)
        assertTrue(CODE_FONT_OPTIONS.all { it.labelResId != 0 && it.label == null })
    }

    @Test
    fun fontOptionsAddTheFamiliesTheDeviceHas() {
        // The named families cannot live in the static table (a top-level `val`
        // cannot probe the platform), so the rows extend it at render time.
        val ui = uiFontOptions(listOf("Noto Serif", "Lato"))
        assertEquals(
            listOf("", UI_FONT_SYSTEM) + OcSystemFonts.SANS_ALIASES + listOf("Noto Serif", "Lato"),
            ui.map { it.value },
        )
        // A real family name is data: rendered verbatim, no resource label.
        assertEquals("Noto Serif", ui.first { it.value == "Noto Serif" }.label)
        assertEquals(0, ui.first { it.value == "Noto Serif" }.labelResId)

        val code = codeFontOptions(listOf("Droid Sans Mono", "Roboto Mono"))
        // Candidate order (the catalog's), not the probe's.
        assertEquals(
            listOf("", CODE_FONT_FOLLOW_UI, "serif-monospace", "Roboto Mono", "Droid Sans Mono"),
            code.map { it.value },
        )
        assertEquals("Droid Sans Mono", code.last().label)
        // An unknown name is not a stop, so it never reaches the picker.
        assertTrue(uiFontOptions(listOf("bogus")).none { it.value == "bogus" })
    }

    @Test
    fun systemFontCatalogSplitsGuaranteedAliasesFromDeviceCandidates() {
        // Aliases are AOSP `fonts.xml` names every device resolves; candidates are
        // real family names only some devices have.
        assertTrue(OcSystemFonts.SANS_ALIASES.isNotEmpty())
        assertTrue(OcSystemFonts.MONO_ALIASES.isNotEmpty())
        assertTrue(OcSystemFonts.SANS_ALIASES.none { it in OcSystemFonts.SANS_CANDIDATES })
        assertTrue(OcSystemFonts.MONO_ALIASES.none { it in OcSystemFonts.MONO_CANDIDATES })
        // The common mono faces the picker is expected to offer when present,
        // upstream's own default first.
        for (name in listOf(
            "IBM Plex Mono",
            "Roboto Mono",
            "Droid Sans Mono",
            "Noto Sans Mono",
            "Cutive Mono",
        )) {
            assertTrue(name in OcSystemFonts.MONO_CANDIDATES)
        }
        // No device here (JVM stubs), so the probe reports nothing and the
        // pickers fall back to exactly the aliases.
        assertTrue(OcSystemFonts.availableSans().isEmpty())
        assertTrue(OcSystemFonts.availableMono().isEmpty())
        assertEquals(OcSystemFonts.SANS_ALIASES, OcSystemFonts.sansStops(emptyList()))
        assertEquals(OcSystemFonts.MONO_ALIASES, OcSystemFonts.monoStops(emptyList()))
        assertTrue(OcSystemFonts.isSansStop("serif"))
        assertTrue(OcSystemFonts.isMonoStop("serif-monospace"))
        assertTrue(OcSystemFonts.isMonoStop("Roboto Mono"))
        assertFalse(OcSystemFonts.isSansStop("bogus"))
        assertFalse(OcSystemFonts.isMonoStop("bogus"))
    }

    @Test
    fun uiFontFamilyMapsStoredValues() {
        assertEquals(FontFamily.SansSerif, uiFontFamily("system"))
        // Blank (unset) and anything a future build might have written fall
        // back to the bundled Inter, never to the platform default.
        assertEquals(InterFontFamily, uiFontFamily(""))
        assertEquals(InterFontFamily, uiFontFamily("   "))
        assertEquals(InterFontFamily, uiFontFamily("Inter"))
        assertEquals(InterFontFamily, uiFontFamily("bogus"))
        // A system family resolves through the platform, which a JVM test does
        // not have — the same Inter fallback answers (upstream's CSS stack ends
        // at `"Inter", …` too); on device `OcSystemFonts.family` resolves it.
        assertEquals(InterFontFamily, uiFontFamily("serif"))
    }

    @Test
    fun codeFontFamilyMapsStoredValues() {
        // Blank = the system mono stack (the faithful `IBM Plex Mono` port).
        assertEquals(OcMonoFontFamily, codeFontFamily("", ""))
        assertEquals(FontFamily.Monospace, codeFontFamily("", "system"))
        // `ui` follows the UI stop, including the system branch.
        assertEquals(InterFontFamily, codeFontFamily("ui", ""))
        assertEquals(FontFamily.SansSerif, codeFontFamily("ui", "system"))
        assertTrue(codeFontFamily("bogus", "") == OcMonoFontFamily)
        // A system mono falls back to the mono stack, never to the default sans
        // (which would silently un-monospace the code).
        assertEquals(OcMonoFontFamily, codeFontFamily("serif-monospace", ""))
        assertEquals(OcMonoFontFamily, codeFontFamily("Roboto Mono", ""))
        assertEquals(OcMonoFontFamily, codeFontFamily("Roboto Mono", "system"))
    }
}
