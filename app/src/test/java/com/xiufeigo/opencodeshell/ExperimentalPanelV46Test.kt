package com.xiufeigo.opencodeshell

import androidx.compose.ui.unit.dp
import com.xiufeigo.opencodeshell.ui.settings.settingsRowStacked
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v46 tD_99/tD_100 — the Experimental tab's two small rows:
 *
 * - the disabled Show-project-names row squeezed its copy into a third of the
 *   row (title + description wrapping one fragment per line) while the
 *   28-char "Not available in this app yet" chip floated beside it;
 * - the Tabs card carried two adjacent dividers.
 *
 * The panels have no JVM-renderable surface (no Compose test dependency; see
 * `SettingsPanelScrollTest`), so both are pinned as source facts over the pure
 * decision the row already calls, plus the device geometry that decides the
 * shape on the v46 phone.
 */
class ExperimentalPanelV46Test {

    private fun source(relative: String): String {
        val file = File(relative).takeIf { it.isFile } ?: File("app/$relative")
        return file.readText().replace("\r\n", "\n")
    }

    private fun panels() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/settings/SettingsPanels.kt")

    /** `ExperimentalPanel`'s body, up to the migration-status row below it. */
    private fun experimentalPanel(): String =
        panels().substringAfter("fun ExperimentalPanel(")
            .substringBefore("private fun MigrationStatusRowUi")

    /** `ExperimentalDisabledRow`'s body, up to the next declaration. */
    private fun disabledRow(): String =
        panels().substringAfter("private fun ExperimentalDisabledRow(")
            .substringBefore("private fun <T> rememberServerPage(")

    // ------------------------------------------------- (1) the disabled row

    @Test
    fun theDisabledRowIsASettingsRowLikeItsTabsSibling() {
        val row = disabledRow()
        assertTrue("drawn as a SettingsRow", row.contains("SettingsRow("))
        assertTrue("last: no trailing border", row.contains("last = true"))
        assertTrue("the unavailable copy rides the chip", row.contains("R.string.oc_settings_experimental_unavailable"))
    }

    @Test
    fun theChipIsTheRowsControlInsideTheSharedBand() {
        val row = disabledRow()
        assertTrue(
            "the chip takes the control band every other control uses",
            row.contains("SettingsRowControlWidth"),
        )
        assertTrue(
            "kept at its natural size at the trailing edge, never stretched",
            row.contains("contentAlignment = Alignment.CenterEnd"),
        )
        assertTrue(row.contains("OcTag(stringResource(R.string.oc_settings_experimental_unavailable))"))
        assertFalse(
            "no weighted copy column beside an unweighted chip — the measure-order trap of tD_99",
            row.contains("Modifier.weight(1f)"),
        )
    }

    @Test
    fun theV46PhoneStacksTheRowSoTheChipSitsUnderTheCopy() {
        // The v46 capture is a 540 dp window (see `SettingsRowStackedTest`):
        // the dialog is 508 dp, the panel and list insets take 120, so the row
        // is 388 dp — under the 400 dp breakpoint, i.e. the copy takes the
        // whole line and the chip moves under it instead of beside it.
        assertTrue("the v46 row stacks", settingsRowStacked(388.dp, viewportWidthDp = 540))
    }

    // ------------------------------------------------- (2) the single divider

    @Test
    fun theTabsCardHasExactlyOneDividerUnderIt() {
        val panel = experimentalPanel()
        // The Tabs row draws its own bottom border (`last = false`); a second,
        // explicit divider read as two adjacent lines (tD_100).
        assertTrue("the Tabs row keeps its own border", panel.contains("last = false"))
        assertEquals(
            "no explicit divider between the two rows",
            0,
            Regex("""RowSeparator\(\)""").findAll(panel).count(),
        )
        assertTrue("the disabled row is still there", panel.contains("ExperimentalDisabledRow("))
    }
}
