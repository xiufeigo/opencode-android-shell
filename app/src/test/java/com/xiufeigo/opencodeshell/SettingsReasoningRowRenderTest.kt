package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.settings.GENERAL_PANEL
import com.xiufeigo.opencodeshell.ui.settings.SettingsRow
import com.xiufeigo.opencodeshell.ui.settings.SettingsSearchHit
import com.xiufeigo.opencodeshell.ui.settings.SettingsTab
import com.xiufeigo.opencodeshell.ui.settings.panelsFor
import com.xiufeigo.opencodeshell.ui.settings.renderedPanel
import com.xiufeigo.opencodeshell.ui.settings.searchSettings
import com.xiufeigo.opencodeshell.ui.settings.settingsRowJump
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v48 (`research/v48_verify/tE/`): 「Model reasoning」 — the
 * `settings-reasoning-mode` SELECT of the General page.
 *
 * Two findings, pinned here because both are structural and neither has a
 * JVM-renderable surface in this project:
 *
 * 1. **the render filter chain.** There is exactly one filter over the panel
 *    table — `mobileOnly` (B-16) — and it is applied in [renderedPanel]. The
 *    reasoning row is declared unconditionally, so it renders on both forms, at
 *    the position [GENERAL_PANEL] declares it at; [theReasoningRowRendersWhereItIsDeclared]
 *    pins the rendered list against the declared one row for row, so a filter
 *    that ever drops it (on one form only, or by accident) fails here instead of
 *    on a screenshot.
 *
 * 2. **search → row.** The search catalog and the renderer read the panel
 *    through different doors until v48 (`ROW_PANELS` vs `visibleRows`), which
 *    made `settings-mobile-titlebar-bottom` a desktop hit that could never
 *    render, and a row hit only ever re-selected the tab — so a hit on the page
 *    you were already on did nothing at all
 *    (`v48_10_reasoning_searchjump.png`). Both the catalog and the jump target
 *    are pinned below, and the Compose wiring is pinned as source text.
 */
class SettingsReasoningRowRenderTest {

    private val action = "settings-reasoning-mode"

    private fun renderedRows(mobile: Boolean): List<SettingsRow> =
        renderedPanel(SettingsTab.GENERAL, mobile).flatMap { it.rows }

    private fun renderedActions(mobile: Boolean): List<String> = renderedRows(mobile).map { it.action }

    /** The catalog resolves ids through a lambda; a hit still matches on its `data-action`. */
    private val titleOf: (Int) -> String = { id -> id.toString() }

    private fun source(name: String): String {
        val file = File("src/main/java/com/xiufeigo/opencodeshell/ui/settings/$name")
            .takeIf { it.isFile }
            ?: File("app/src/main/java/com/xiufeigo/opencodeshell/ui/settings/$name")
        assertTrue("missing source: $name", file.isFile)
        return file.readText()
    }

    // ---- 1. the render filter chain --------------------------------------------

    @Test
    fun theReasoningRowRendersWhereItIsDeclared() {
        val declared = GENERAL_PANEL.flatMap { it.rows }.map { it.action }
        for (mobile in listOf(true, false)) {
            val rendered = renderedActions(mobile)
            val index = rendered.indexOf(action)
            assertTrue("reasoning row not rendered (mobile=$mobile): $rendered", index >= 0)
            assertEquals("declared position (mobile=$mobile)", declared.indexOf(action), index)
            // The only row the filter may drop is the mobile-titlebar switch.
            val dropped = declared - rendered
            assertEquals(
                "unexpected rows dropped by the filter (mobile=$mobile)",
                if (mobile) emptyList() else listOf("settings-mobile-titlebar-bottom"),
                dropped,
            )
        }
    }

    @Test
    fun theReasoningRowKeepsItsPositionInItsSection() {
        for (mobile in listOf(true, false)) {
            val section = renderedPanel(SettingsTab.GENERAL, mobile).first()
            val actions = section.rows.map { it.action }
            val index = actions.indexOf(action)
            assertEquals("settings-workspace-destination", actions[index - 1])
            assertEquals("settings-mobile-diff-wrap", actions[index + 1])
            assertEquals(SettingsTab.GENERAL, SettingsTab.GENERAL)
            assertTrue(panelsFor(SettingsTab.GENERAL).isNotEmpty())
        }
    }

    // ---- 2. the search catalog reads the same rows ----------------------------

    @Test
    fun theSearchCatalogOffersExactlyTheRowsAFormRenders() {
        for (mobile in listOf(true, false)) {
            val hits = searchSettings("", titleOf, mobile = mobile)
                .filter { it.tab == SettingsTab.GENERAL && it.action != null }
                .map { it.action!! }
            assertEquals(
                "searchable General rows ≠ rendered General rows (mobile=$mobile)",
                renderedActions(mobile),
                hits,
            )
        }
    }

    @Test
    fun aMobileOnlyRowIsNotASearchHitOnTheWideForm() {
        val mobileOnly = "settings-mobile-titlebar-bottom"
        val wide = searchSettings(mobileOnly, titleOf, mobile = false)
        assertTrue("the mobile-titlebar row has no wide-form render, so no hit", wide.isEmpty())
        val narrow = searchSettings(mobileOnly, titleOf, mobile = true)
        assertEquals(1, narrow.size)
        assertEquals(mobileOnly, narrow.single().action)
    }

    @Test
    fun theReasoningRowIsAHitOnBothForms() {
        for (mobile in listOf(true, false)) {
            val hit = searchSettings(action, titleOf, mobile = mobile).singleOrNull { it.action == action }
            assertNotNull("no hit for the reasoning row (mobile=$mobile)", hit)
            assertEquals(SettingsTab.GENERAL, hit!!.tab)
        }
    }

    // ---- 3. the jump target ---------------------------------------------------

    @Test
    fun aRowHitResolvesToTheRowItNames() {
        val hit = SettingsSearchHit(
            tab = SettingsTab.GENERAL,
            action = action,
            titleResId = 0,
        )
        for (mobile in listOf(true, false)) {
            val jump = settingsRowJump(hit, mobile)
            assertNotNull("no jump target for the reasoning row (mobile=$mobile)", jump)
            jump!!
            assertEquals(SettingsTab.GENERAL, jump.tab)
            assertEquals(action, jump.action)
            assertEquals(0, jump.sectionIndex)
            assertEquals(action, renderedRows(mobile)[jump.flatIndex].action)
            assertEquals(renderedActions(mobile).indexOf(action), jump.flatIndex)
        }
    }

    @Test
    fun aPageHitHasNoRowToJumpTo() {
        val page = SettingsSearchHit(tab = SettingsTab.GENERAL, action = null, titleResId = 0)
        assertNull(settingsRowJump(page, mobile = false))
    }

    @Test
    fun aHiddenRowIsNotAJumpTargetOnTheFormThatHidesIt() {
        val hit = SettingsSearchHit(
            tab = SettingsTab.GENERAL,
            action = "settings-mobile-titlebar-bottom",
            titleResId = 0,
        )
        assertNull(settingsRowJump(hit, mobile = false))
        assertNotNull("the mobile form does render it", settingsRowJump(hit, mobile = true))
    }

    @Test
    fun anUnknownActionIsNotAJumpTarget() {
        val hit = SettingsSearchHit(tab = SettingsTab.GENERAL, action = "settings-nope", titleResId = 0)
        assertNull(settingsRowJump(hit, mobile = true))
    }

    // ---- 4. the Compose wiring (no JVM-renderable surface) ---------------------

    @Test
    fun theDialogCarriesTheHitToThePanel() {
        val screen = source("SettingsScreen.kt")
        assertTrue("the dialog holds the pending row", screen.contains("var jumpAction by remember"))
        assertTrue(
            "and resolves it against the form the page renders",
            screen.contains("settingsRowJump(hit, mobile)?.action"),
        )
        assertTrue("the hit carries the action, not just the tab", screen.contains("onSelectHit: (SettingsSearchHit) -> Unit"))
        assertEquals(2, Regex("onSelectHit = onSearchHit").findAll(screen).count())
        assertEquals(2, Regex("onSelect = onSelectHit").findAll(screen).count())
        assertFalse(
            "a row hit must not degrade back to a bare tab switch",
            screen.contains("onSelect(hit.tab)"),
        )
        for (tab in listOf("GENERAL", "APPEARANCE", "NOTIFICATIONS")) {
            assertTrue(
                "PanelSlot must hand the jump to the $tab page",
                screen.contains("SettingsTab.$tab -> ${tab.lowercase().replaceFirstChar { it.uppercase() }}Panel(settings, store, jumpAction, onJumpHandled)"),
            )
        }
    }

    @Test
    fun thePanelScrollsToTheRowAndMarksIt() {
        val panels = source("SettingsPanels.kt")
        assertTrue("the rows report their position", panels.contains("rowTops[action] = it.positionInWindow()"))
        assertTrue("the jump scrolls the panel to it", panels.contains("scrollState.animateScrollTo(offset)"))
        assertTrue("and the landed row is marked", panels.contains("if (action == highlightAction) c.stateInfoBg"))
        assertTrue("the mark clears itself", panels.contains("onJumpHandled()"))
        assertTrue(
            "every rendered row carries its data-action as a test tag, so an accessibility dump can prove it drew",
            panels.contains("testTag(\"settings-row:\$action\")"),
        )
    }
}
