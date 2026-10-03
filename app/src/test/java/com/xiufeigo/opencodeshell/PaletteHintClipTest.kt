package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.palette.PALETTE_HINT_MAX_WIDTH
import com.xiufeigo.opencodeshell.ui.palette.PALETTE_LABEL_MIN_WIDTH
import com.xiufeigo.opencodeshell.ui.palette.PALETTE_ROW_EDGE_INSET
import com.xiufeigo.opencodeshell.ui.palette.PALETTE_ROW_PADDING
import com.xiufeigo.opencodeshell.ui.palette.paletteHintMaxWidth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v43 真机批：命令面板的快捷键提示被卡片右缘切掉
 * (`research/v43_verify/tE/v43_42_palette.png` — `Ctrl+`` 只渲染出半个反引号，
 * `Ctrl+,` 的尾巴被切)。
 *
 * 现象：`PaletteRow` 最后一列是 keybind / meta 的 `Text`，既没有宽度上限也没有
 * 与行内边距的显式契约；`Row` 先量非 weight 子节点，于是长 chord 按自身固有宽度
 * 占掉了整行，末位字形一直顶到卡片自己的圆角边上。
 *
 * 修法：提示列有自己的宽度预算 [paletteHintMaxWidth]（行宽的份额、绝对上限、
 * 以及给标题留够 [PALETTE_LABEL_MIN_WIDTH] 之后剩下的，三者取小），并且永远在
 * 卡片边缘前留 [PALETTE_ROW_EDGE_INSET] 的余量。
 */
class PaletteHintClipTest {

    private fun palette(): String {
        val file = File("src/main/java/com/xiufeigo/opencodeshell/ui/palette/CommandPalette.kt")
            .takeIf { it.isFile }
            ?: File("app/src/main/java/com/xiufeigo/opencodeshell/ui/palette/CommandPalette.kt")
        assertTrue("missing source: CommandPalette.kt", file.isFile)
        return file.readText()
    }

    private fun paletteRow(): String {
        val body = palette()
        val start = body.indexOf("private fun PaletteRow(")
        val end = body.indexOf("private fun Modifier.hoverable", start)
        assertTrue(start >= 0 && end > start)
        return body.substring(start, end)
    }

    // ---- the width budget --------------------------------------------------

    @Test
    fun hintStaysInsideItsShareOfARow() {
        // 640 px shell (§6.3) minus the 6 px list padding and the 12 px row
        // padding on both sides.
        val row = 640f - 2f * 6f - 2f * PALETTE_ROW_PADDING
        val budget = paletteHintMaxWidth(row)
        assertTrue(
            "hint budget $budget must not exceed the row width $row",
            budget in 0f..row,
        )
        assertTrue("the hint must keep a share of the row for itself", budget > 0f)
    }

    @Test
    fun hintNeverStarvesTheLabel() {
        // A narrow phone row: whatever the share says, the label keeps its
        // minimum and the hint gives up the difference.
        val row = 180f
        val budget = paletteHintMaxWidth(row)
        assertTrue(
            "hint budget $budget leaves the label under $PALETTE_LABEL_MIN_WIDTH dp",
            budget <= (row - 2f * PALETTE_ROW_EDGE_INSET - PALETTE_LABEL_MIN_WIDTH).coerceAtLeast(0f),
        )
    }

    @Test
    fun hintIsCappedAbsolutelyOnAWideWindow() {
        // §6.3 caps the shell at 640 px, but a landscape tablet window can hand
        // the palette more; the hint must not float away from its label.
        val row = 2000f
        assertEquals(PALETTE_HINT_MAX_WIDTH, paletteHintMaxWidth(row), 0.001f)
    }

    @Test
    fun aNonPositiveRowWidthYieldsNoBudget() {
        // The first composition measures the row after the hint is placed, so
        // the budget starts at 0 — which must not become a negative width.
        assertEquals(0f, paletteHintMaxWidth(0f), 0.001f)
        assertEquals(0f, paletteHintMaxWidth(-40f), 0.001f)
    }

    @Test
    fun theBudgetIsMonotonicInTheRowWidth() {
        var previous = -1f
        for (row in 120..900 step 20) {
            val budget = paletteHintMaxWidth(row.toFloat())
            assertTrue(
                "budget went backwards at row=$row: $previous -> $budget",
                budget >= previous,
            )
            previous = budget
        }
    }

    // ---- the row's own contract --------------------------------------------

    @Test
    fun hintColumnKeepsAnInsetFromTheCardsClipEdge() {
        val row = paletteRow()
        assertTrue(
            "the hint column must keep a gap from the card's right edge",
            row.contains(".padding(end = PALETTE_ROW_EDGE_INSET.dp)"),
        )
        assertTrue(row.contains("PALETTE_ROW_EDGE_INSET"))
    }

    @Test
    fun hintColumnIsBoundedByTheBudget() {
        val row = paletteRow()
        assertTrue(row.contains("widthIn(max = hintMaxWidth)"))
        assertTrue(row.contains("paletteHintMaxWidth("))
    }

    @Test
    fun keybindStaysOnOneLineSoTheFixedHeightRowCannotClipIt() {
        val row = paletteRow()
        val keybind = row.substringAfter("text = keybind")
        assertTrue("a wrapped chord is clipped by the row's fixed 36/48 dp height", keybind.contains("softWrap = false"))
        assertTrue(keybind.contains("maxLines = 1"))
        assertTrue(keybind.contains("overflow = TextOverflow.Ellipsis"))
    }

    @Test
    fun metaIsBoundedTooSoItCannotPushTheKeybindOffTheEdge() {
        val row = paletteRow()
        val meta = row.substringAfter("text = meta")
        assertTrue(meta.contains("maxLines = 1"))
        assertTrue(meta.contains("overflow = TextOverflow.Ellipsis"))
    }

    @Test
    fun hintOrderIsUnchangedKeybindThenMeta() {
        // Upstream's row is `label | keybind`, with `meta` after it. The
        // v43 fix moved both into one bounded box; their order inside it is
        // the same one the old flat `Row` drew.
        val row = paletteRow()
        val keybindAt = row.indexOf("entry.keybind?.let { keybind ->")
        val metaAt = row.indexOf("entry.meta?.let { meta ->")
        assertTrue(keybindAt >= 0 && metaAt > keybindAt)
    }
}
