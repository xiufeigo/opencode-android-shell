package com.xiufeigo.opencodeshell

import androidx.compose.ui.unit.dp
import com.xiufeigo.opencodeshell.ui.session.SessionPaneLayout
import com.xiufeigo.opencodeshell.ui.session.composer.MODEL_CHIP_MIN_WIDTH
import com.xiufeigo.opencodeshell.ui.session.composer.modelChipMaxWidth
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v81 P2「一行 composer 里模型 chip 被压成 `ope...`」: the one-row split reserves
 * chip space first.
 *
 * v80 made the one-row editor eat its whole weight bound (`fillMaxWidth`, the
 * intentional visible change in `research/HANDOFF.md` — not reverted here), and
 * the v57 50/50 split then leaves the controls viewport ~76 dp on a 240 dp
 * window (720x1280 @480dpi: card 216 − `⊞` 28 − gaps 8 − Send 28 = 152). The
 * chip's ceiling is that viewport, so `GLM-5.2 · opencode-go` ellipsizes to
 * `ope...` with the chevron — the one hint the chip opens the picker — barely
 * inside the row (`research/v80_verify/5554/16_480_session.png`).
 *
 * The fix is two numbers, both pinned here:
 *
 *  * the editor takes [SessionPaneLayout.COMPOSER_ONE_ROW_EDITOR_WEIGHT]
 *    (0.45) against the controls' `1f` — ~31% of the row past the fixed
 *    controls for the editor, the rest for the chips. ~47 / ~105 dp at 480dpi,
 *    ~58 / ~129 at 420dpi. The editor is one line and its text scrolls; the
 *    chip's label does not, so the chip is what the ratio protects. The value
 *    (not the modifier) changes, so the v80 single-chain invariant holds: same
 *    classes, same order, no node replacement, the flip still cannot dispose
 *    the field. 0.45 and not the v81 0.5: the 1:2 split left the label 53 dp,
 *    which fits eight lowercase chars but only five of a caps-heavy model
 *    name (`GPT-6.1 Sol` measured `GPT-6....` on the same window,
 *    `research/v81_verify/5554/s18_chip_crop.png`) — caps/figures run ~8 dp
 *    per char at 13 sp against the 6.6 dp lowercase ruler, so the sixth char
 *    needs the extra ~3.5 dp this moves off the editor (which stays past the
 *    44 dp tap floor).
 *  * the chip keeps [MODEL_CHIP_MIN_WIDTH] (88 dp: 48 of fixed chrome + ~40 of
 *    label, about six 13 sp characters) whatever the viewport says, so no
 *    window can press it back to `ope...`.
 *
 * v86 P0「720x1280 键盘态：输入框只剩 39 / 25 dp」supersedes the *first* number
 * where it starved the other side: the ratio is still the split's home, but on a
 * band narrow enough that 0.45 leaves the editor less than its own floor, the
 * weight that pays that floor wins and the controls keep the 88 dp floor above —
 * at 240 dp the band is the editor's 64 dp + the chip's 88 dp exactly. The chip's
 * half of this file (its own floor, its compact form's threshold) is unchanged and
 * is what the v86 floor is bounded by; the moved windows are pinned in
 * `ComposerOneRowEditorFloorV86Test`.
 */
class ComposerOneRowChipReserveV81Test {

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()?.replace("\r\n", "\n")
            ?: error("missing $relative")

    private val composer =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/Composer.kt")

    private val modelChip =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/ModelChip.kt")

    private val paneLayout =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionPaneLayout.kt")

    /** The composer with whole-line comments stripped, for chain assertions. */
    private val code = composer.replace(Regex("(?m)^\\s*//.*$"), " ")

    /** Rough 13 sp Roboto advance, same ruler as `ModelChipLabelWidthTest`. */
    private fun labelWidthDp(label: String): Float = label.length * 6.6f

    @Test
    fun `the one-row split is still the row's own weighted ghost`() {
        assertEquals(0.45f, SessionPaneLayout.COMPOSER_ONE_ROW_EDITOR_WEIGHT, 0.0f)
        // The constant still drives the one-row split from the tool row's own `Row`:
        // its first child is the zero-width share ghost, weighted against the
        // controls' `1f` scroller — so the split stays the row's own weight
        // arithmetic and not a number copied into the arrangement. v86 puts the
        // weight behind [SessionPaneLayout.composerOneRowEditorWeight], whose
        // no-floor case *is* this constant, bit for bit.
        assertTrue(
            composer.contains(
                ".weight(SessionPaneLayout.composerOneRowEditorWeight(bandWidth))",
            ),
        )
        assertEquals(
            "the split must be decided in exactly one place",
            1,
            Regex("""composerOneRowEditorWeight\(bandWidth\)""").findAll(code).count(),
        )
        assertTrue(paneLayout.contains("val COMPOSER_ONE_ROW_EDITOR_WEIGHT: Float = 0.45f"))
        assertTrue(paneLayout.contains("fun composerOneRowEditorWeight(bandWidth: Dp): Float {"))
        // The ghost's share is what the editor is measured with — the editor's own
        // chain carries no weight (v81: the arrangement hands it the bound).
        assertTrue(composer.contains(".onSizeChanged { editorShare = it.width },"))
        assertTrue(composer.contains("maxWidth = editorShare.coerceIn(0, constraints.maxWidth),"))
        assertTrue(!composer.contains("composerOneRowEditorWeight(bandWidth), fill = false)"))
    }

    @Test
    fun `the 240 dp window keeps the chip at its floor`() {
        // 720x1280 @480dpi: window 240, region takes 12 + 12 (see
        // `composerRegion`'s padding), fixed row children `⊞` 28 + gaps 8 +
        // Send 28 — the worst window the form runs on.
        // v111: this was the literal `top = 4.dp, bottom = 8.dp`. It is still an
        // **exact literal** pin — of the split v110 actually shipped
        // (`top = 0.dp, bottom = 12.dp`), because the vertical split is exactly
        // what it pinned before and is exactly what it must keep pinning. What the
        // 240 dp arithmetic actually *consumes* is the horizontal pair and the
        // vertical **total**, so both are now pinned as numbers rather than left
        // implicit inside a literal: `12 + 12` of side padding, and a vertical
        // total that must still be the 12 dp every pane-budget term sums.
        // Same strength on the split, plus two explicit numbers where there were none.
        val region =
            source("src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionScreen.kt")
        assertTrue(
            region.contains(
                ".padding(start = 12.dp, end = 12.dp, top = 0.dp, bottom = 12.dp)",
            ),
        )
        // The horizontal pair `card = 240 − 24` below is made of, pinned directly:
        // v110 moved decoration between the vertical edges only, never sidewise.
        assertTrue(
            "the region's side padding is still 12 + 12",
            region.contains("start = 12.dp, end = 12.dp"),
        )
        assertEquals(
            "and its vertical total is still the 12 dp the pane budget sums",
            SessionPaneLayout.COMPOSER_REGION_PADDING.value,
            0f + 12f,
            0.0f,
        )
        val card = 240f - 24f
        val space = card - SessionPaneLayout.COMPOSER_ONE_ROW_FIXED_WIDTH.value
        val weight = SessionPaneLayout.composerOneRowEditorWeight(card.dp)
        val editorShare = space * weight / (weight + 1f)
        val controlsShare = space - editorShare
        // v86: this band is the editor's floor + the chip's floor exactly, so the
        // editor's text band is a readable 48 dp — where v81's ratio, which had the
        // chip 16.83 dp above its own floor, left it 31.17 and cost the chip nothing.
        assertEquals(SessionPaneLayout.COMPOSER_ONE_ROW_EDITOR_FLOOR.value, editorShare, 0.05f)
        assertEquals(
            48f,
            editorShare - SessionPaneLayout.COMPOSER_ONE_ROW_EDITOR_PADDING.value,
            0.05f,
        )
        assertTrue(
            "editor share $editorShare",
            editorShare >= SessionPaneLayout.COMPOSER_ROW_HEIGHT.value,
        )
        // The chip keeps its own v81 floor — the floor is what the editor's is
        // bounded by, so the chip is never pressed under it…
        assertEquals(MODEL_CHIP_MIN_WIDTH.value, controlsShare, 0.05f)
        assertTrue(
            "controls share $controlsShare vs floor $MODEL_CHIP_MIN_WIDTH",
            controlsShare >= MODEL_CHIP_MIN_WIDTH.value,
        )
        // …and the label keeps its room inside that floor: 48 dp of chrome (icon,
        // chevron, padding, spacers) and ~40 dp of label — six lowercase- or five
        // caps-heavy 13 sp characters plus the ellipsis.
        assertTrue(
            "caps label room ${controlsShare - 48f}",
            controlsShare - 48f >= 40f - 0.05f,
        )
        val labelChars = (controlsShare - 48f) / 6.6f
        assertTrue("label chars $labelChars", labelChars >= 6f)
        assertTrue("caps label chars ${(controlsShare - 48f) / 8f}", (controlsShare - 48f) / 8f >= 5f)
        assertTrue(labelWidthDp("opencode") <= controlsShare)
    }

    @Test
    fun `the chip floor is a readable minimum under every ceiling`() {
        assertEquals(88.dp, MODEL_CHIP_MIN_WIDTH)
        // The floor binds before any ceiling: roomy rows are unchanged (content
        // is wider), tight rows ellipsize at the viewport instead of crushing
        // the chrome.
        assertTrue(modelChip.contains(".widthIn(min = MODEL_CHIP_MIN_WIDTH, max = widthCeiling)"))
        // …and it fits inside the worst viewport above — *exactly*, because the
        // 240 dp band is the floor's own boundary case — so the chevron, the hint
        // the chip opens the picker, always stays in the row.
        assertTrue(
            MODEL_CHIP_MIN_WIDTH.value <=
                modelChipMaxWidth(windowWidthDp = 240, available = 88.dp).value,
        )
        assertTrue(
            MODEL_CHIP_MIN_WIDTH.value < modelChipMaxWidth(windowWidthDp = 240).value,
        )
    }
}
