package com.xiufeigo.opencodeshell

import androidx.compose.ui.unit.dp
import com.xiufeigo.opencodeshell.ui.session.SessionPaneLayout
import com.xiufeigo.opencodeshell.ui.session.composer.MODEL_CHIP_COMPACT_BELOW
import com.xiufeigo.opencodeshell.ui.session.composer.MODEL_CHIP_MIN_WIDTH
import com.xiufeigo.opencodeshell.ui.session.composer.modelChipCompact
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ITEM B (P2): at a 420 px window width the model chip collapsed to
 * icon-only — no label text at all, no chevron.
 *
 * Root cause: on that window (140 dp @480dpi, a short window, so the
 * one-row form) the controls share is ~36 dp while the chip's floor is
 * [MODEL_CHIP_MIN_WIDTH] (88 dp). `widthIn(min > max)` coerces to the tiny
 * ceiling, the full chip's 48 dp of fixed chrome then leaves zero label room
 * and pushes the chevron out of the scroller viewport. Below
 * [MODEL_CHIP_COMPACT_BELOW] the chip therefore renders its compact form —
 * icon + chevron with tightened chrome, the model name on the content
 * description — instead of a silent zero-text node.
 */
class ModelChipCompactTest {

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()?.replace("\r\n", "\n")
            ?: error("missing $relative")

    private val modelChip =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/ModelChip.kt")

    @Test
    fun theCompactLineSitsBelowTheReadableFloor() {
        assertEquals(56.dp, MODEL_CHIP_COMPACT_BELOW)
        assertTrue(MODEL_CHIP_COMPACT_BELOW < MODEL_CHIP_MIN_WIDTH)
    }

    @Test
    fun narrowCeilingsTakeTheCompactForm() {
        assertTrue(modelChipCompact(35.9.dp))
        assertTrue(modelChipCompact(48.dp))
        assertTrue(modelChipCompact(55.9.dp))
    }

    @Test
    fun readableCeilingsKeepTheFullChip() {
        assertFalse(modelChipCompact(56.dp))
        assertFalse(modelChipCompact(88.dp))
        assertFalse(modelChipCompact(320.dp))
    }

    @Test
    fun theCompactChromeFitsThe420WindowControlsShare() {
        // 420x1280 @480dpi = a 140 dp window, short, so the one-row form:
        // card 140 − region padding 24, fixed row children ⊞ 28 + gaps 8 +
        // Send 28, split 0.45 : 1 like `ComposerOneRowChipReserveV81Test`.
        val card = 140f - 24f
        val fixed = 28f + 4f + 4f + 28f
        val controlsShare = (card - fixed) /
            (SessionPaneLayout.COMPOSER_ONE_ROW_EDITOR_WEIGHT + 1f)
        assertTrue("controls share $controlsShare", controlsShare < 56f)
        assertTrue(modelChipCompact(controlsShare.dp))
        // Compact chrome: padding 2×2 + icon 16 + gap 2 + chevron 12.
        val compactChrome = 2f + 2f + 16f + 2f + 12f
        assertTrue(
            "compact chrome $compactChrome vs share $controlsShare",
            compactChrome <= controlsShare,
        )
    }

    @Test
    fun theChipSwitchesFormsOffTheCeiling() {
        val chip = modelChip.substringAfter("fun ModelChip(")
        assertTrue(chip.contains("val compact = modelChipCompact(widthCeiling)"))
        // The label is full-form only; the compact form keeps the affordance.
        assertTrue(chip.contains("if (!compact)"))
        assertTrue(chip.contains("Modifier.size(if (compact) 12.dp else 14.dp)"))
        // No silent zero-text state: the model name rides the description.
        assertTrue(chip.contains("contentDescription = if (compact) label else modelLabel"))
        // The floor still binds first on roomy rows.
        assertTrue(chip.contains(".widthIn(min = MODEL_CHIP_MIN_WIDTH, max = widthCeiling)"))
        // The full label keeps its elastic contract.
        assertTrue(chip.contains("Modifier.weight(1f, fill = false)"))
    }
}
