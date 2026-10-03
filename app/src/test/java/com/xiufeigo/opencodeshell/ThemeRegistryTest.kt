package com.xiufeigo.opencodeshell

import androidx.compose.ui.graphics.Color
import com.xiufeigo.opencodeshell.ui.theme.OcColors
import com.xiufeigo.opencodeshell.ui.theme.OcThemeRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * U-1 — the upstream built-in theme collection behind `settings-theme`.
 *
 * The expected values are the upstream pipelines' own output at v2 head
 * (`theme/resolve.ts` + `theme/v2/resolve.ts` over the 36 JSON files in
 * `_upstream/packages/ui/src/theme/themes/`): they pin the generator,
 * not the algorithm. `oc-2` intentionally resolves to the shipped
 * [OcColors.Dark]/[OcColors.Light] constants (see [OcThemeRegistry]).
 */
class ThemeRegistryTest {

    @Test
    fun registryCoversAllThirtySixUpstreamBuiltins() {
        // 36 JSON files under `_upstream/packages/ui/src/theme/themes/`.
        assertEquals(36, OcThemeRegistry.themeCount)
        assertEquals(36, OcThemeRegistry.ORDERED_IDS.toSet().size)
    }

    @Test
    fun pickerOrderIsDefaultFirstThenAlphabeticalByName() {
        assertEquals(OcThemeRegistry.DEFAULT_ID, OcThemeRegistry.ORDERED_IDS.first())
        val names = OcThemeRegistry.ORDERED_IDS.map { OcThemeRegistry.displayName(it) }
        assertEquals(names.drop(1), names.drop(1).sortedBy { it.lowercase() })
    }

    @Test
    fun displayNamesMatchUpstreamThemeNames() {
        assertEquals("OpenCode", OcThemeRegistry.displayName("oc-2"))
        assertEquals("Dracula", OcThemeRegistry.displayName("dracula"))
        assertEquals("Catppuccin Frappe", OcThemeRegistry.displayName("catppuccin-frappe"))
        assertEquals("GitHub", OcThemeRegistry.displayName("github"))
        assertEquals("Night Owl", OcThemeRegistry.displayName("nightowl"))
        assertEquals("One Dark Pro", OcThemeRegistry.displayName("onedarkpro"))
        assertEquals("Rose Pine", OcThemeRegistry.displayName("rosepine"))
        assertEquals("Synthwave '84", OcThemeRegistry.displayName("synthwave84"))
    }

    @Test
    fun unknownStoredIdFallsBackToDefault() {
        // Upstream `resolveStoredTheme` (`theme/context.tsx:86`): a stored id no
        // theme owns (the store accepts any non-blank string) renders as oc-2.
        assertEquals("oc-2", OcThemeRegistry.resolveId(null))
        assertEquals("oc-2", OcThemeRegistry.resolveId(""))
        assertEquals("oc-2", OcThemeRegistry.resolveId("not-a-theme"))
        assertEquals("dracula", OcThemeRegistry.resolveId("dracula"))
        assertEquals("oc-2", OcThemeRegistry.resolveId("oc-2"))
    }

    @Test
    fun defaultThemeResolvesToTheShippedConstants() {
        // Byte-identical: switching the picker back to OpenCode must restore
        // the exact palette every screenshot was taken against.
        assertEquals(OcColors.Dark, OcThemeRegistry.colors("oc-2", isDark = true))
        assertEquals(OcColors.Light, OcThemeRegistry.colors("oc-2", isDark = false))
        assertEquals(OcColors.Dark, OcThemeRegistry.colors(null, isDark = true))
        assertEquals(OcColors.Dark, OcThemeRegistry.colors("not-a-theme", isDark = true))
        assertEquals(OcColors.Light, OcThemeRegistry.colors("", isDark = false))
    }

    @Test
    fun draculaDarkMatchesUpstreamPipelineOutput() {
        val dark = OcThemeRegistry.colors("dracula", isDark = true)
        assertEquals(Color(0xFF151720), dark.bgBase)
        assertEquals(Color(0xFFB987FD), dark.accent)
        assertEquals(Color(0xFFFEFEFE), dark.textBase)
        assertEquals(Color(0xFFFF79C6), dark.syntaxKeyword)
        assertEquals(Color(0x1AFFFFFF), dark.borderBase)
        assertEquals(Color(0xFF99E8F9), dark.agentPlan)
    }

    @Test
    fun draculaLightMatchesUpstreamPipelineOutput() {
        val light = OcThemeRegistry.colors("dracula", isDark = false)
        assertEquals(Color(0xFFF9F9F4), light.bgBase)
        assertEquals(Color(0xFF7C67FF), light.accent)
        assertEquals(Color(0xFF0C0B1A), light.textBase)
        assertEquals(Color(0xFFD16090), light.syntaxKeyword)
    }

    @Test
    fun nordDarkAndGithubLightSpotCheck() {
        val nord = OcThemeRegistry.colors("nord", isDark = true)
        assertEquals(Color(0xFF1F2530), nord.bgBase)
        assertEquals(Color(0xFF67B1C6), nord.accent)
        val github = OcThemeRegistry.colors("github", isDark = false)
        assertEquals(Color(0xFFFFFFFF), github.bgBase)
    }

    @Test
    fun everyThemeResolvesOpaqueSurfaces() {
        // A transparent base surface would be a visibly broken theme; the alpha
        // stops (borders, overlays, scrims) are the only translucent ones.
        for (id in OcThemeRegistry.ORDERED_IDS) {
            for (dark in listOf(false, true)) {
                val c = OcThemeRegistry.colors(id, isDark = dark)
                assertEquals(id, 1f, c.bgBase.alpha)
                assertEquals(id, 1f, c.textBase.alpha)
                assertEquals(id, 1f, c.accent.alpha)
                assertTrue(id, c.overlayScrim.alpha > 0f)
            }
        }
    }
}
