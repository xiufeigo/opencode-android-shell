package com.xiufeigo.opencodeshell

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xiufeigo.opencodeshell.ui.session.composer.modelChipMaxWidth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v43 tD_02 / tD_47: the composer model chip truncated「Muse Spark 1.3
 * Cont…」at ~20 characters on every window, including the 1344 px one, while
 * ~250 px of the controls row sat empty between the chip and the submit
 * button. Upstream (`composer/editor/editor.tsx:724`) is a `max-w-[220px]`
 * *ceiling* on a shrink-to-content button, so the shell must let the label
 * consume the room it has and ellipsize only at the real bound.
 */
class ModelChipLabelWidthTest {

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("missing $relative")

    /** icon 16 + gap 4 + gap 2 + chevron 14 + horizontal padding 2x6. */
    private val chipChromeDp = 48f

    /** Rough 13 sp Roboto advance for the labels the shell renders. */
    private fun labelWidthDp(label: String): Float = label.length * 6.6f

    private val liveLabel = "Muse Spark 1.3 Context 1M"

    @Test
    fun aWideWindowFitsTheLiveModelNameWithoutEllipsis() {
        // 1344 px @3x = a 448 dp window (v43 tD_02).
        val cap = modelChipMaxWidth(448).value
        val needed = chipChromeDp + labelWidthDp(liveLabel)
        assertTrue("cap $cap vs needed $needed", cap >= needed)
    }

    @Test
    fun theCapGrowsWithTheWindowInsteadOfPinningTheOldFixedWidth() {
        // The old cap was min(220, 0.42 * window): on the v43 device that is
        // 188 dp, which truncated the name with the row half empty.
        val device = modelChipMaxWidth(448).value
        assertTrue("still pinned near the old cap: $device", device > 220f)
        assertTrue(
            "cap must grow with the window",
            modelChipMaxWidth(600).value > modelChipMaxWidth(360).value,
        )
    }

    @Test
    fun aNarrowWindowStillKeepsTheWholeControlInsideTheRow() {
        // v40 tD_70, 720x1280 @420dpi = 274 dp: the cap must stay under the
        // old 220 dp so the chevron cannot slide under the submit button.
        val narrow = modelChipMaxWidth(274).value
        assertTrue("narrow window $narrow", narrow < 220f)
        // It must still leave room for the chevron and the button.
        assertTrue("no room for the label", narrow > chipChromeDp)
    }

    @Test
    fun theLabelRidesTheRowBoundAndOnlyEllipsizesAtIt() {
        val chip = source(
            "src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/ModelChip.kt",
        ).substringAfter("fun ModelChip(")
        // The row is the only width bound; nothing else may clip the name.
        assertTrue(chip.contains("modelChipMaxWidth(LocalConfiguration.current.screenWidthDp, maxWidth)"))
        // Single line, but elastic: `fill = false` lets the label shrink to its
        // content instead of being forced to a fixed width.
        assertTrue(chip.contains("maxLines = 1"))
        assertTrue(chip.contains("overflow = TextOverflow.Ellipsis"))
        assertTrue(chip.contains("Modifier.weight(1f, fill = false)"))
        // No hard pixel cap left on the label itself.
        assertFalse(chip.contains("width(${labelWidthDp(liveLabel).toInt()}.dp)"))
    }

    /**
     * v57 P0: the chip lives inside the controls' horizontal scroller, whose
     * children are measured with an unbounded width — so the window-share ceiling
     * never shrank it and「Space Bun」ran under the submit button on the minimal
     * (44 dp) composer instead of ellipsizing
     * (`research/v57_verify/5554/42_short_nokb.png`). The composer now measures
     * its viewport and passes the real room in.
     */
    @Test
    fun theViewportRoomBecomesTheTightestCeiling() {
        val tight = modelChipMaxWidth(windowWidthDp = 448, available = 90.dp)
        assertEquals("the viewport wins on a short pane", 90f, tight.value, 0.01f)
        val generous = modelChipMaxWidth(windowWidthDp = 448, available = 400.dp)
        assertEquals("the window share still caps a roomy viewport", 277.76f, generous.value, 0.01f)
        assertEquals(
            "an unmeasured viewport falls back to the window share",
            modelChipMaxWidth(448),
            modelChipMaxWidth(448, Dp.Unspecified),
        )
    }

    @Test
    fun theComposerMeasuresItsControlsViewportAndPassesItToTheChip() {
        val composer = source(
            "src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/Composer.kt",
        )
        assertTrue("viewport measurement", composer.contains(".onSizeChanged { controlsWidth = it.width }"))
        assertTrue("the width reaches the chip", composer.contains("maxWidth = if (controlsWidth > 0)"))
        // The minimal row is 44 dp: an unbounded editor painted a second line
        // over the tool controls and outside the card.
        assertTrue("the minimal editor is one line", composer.contains("maxLines = if (minimal) 1 else Int.MAX_VALUE"))
    }
}
