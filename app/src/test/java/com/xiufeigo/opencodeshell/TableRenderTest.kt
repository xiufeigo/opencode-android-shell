package com.xiufeigo.opencodeshell

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xiufeigo.opencodeshell.ui.markdown.TABLE_MIN_COL_WIDTH
import com.xiufeigo.opencodeshell.ui.markdown.MdBlock
import com.xiufeigo.opencodeshell.ui.markdown.MarkdownParser
import com.xiufeigo.opencodeshell.ui.markdown.tableColumnWidth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Table-render regression: a table-only assistant message must reach the
 * renderer as a single [MdBlock.Table] (the blank-table bug was downstream in
 * `TableView` layout, not parsing — this pins the block sequence), and the
 * viewport column sizing that replaced the zero-resolving `weight(1f)` must
 * split narrow viewports evenly while flooring wide tables at the scrollable
 * minimum.
 */
class TableRenderTest {

    @Test
    fun tableOnlyMessageParsesToASingleTableBlock() {
        // Exact device-verified repro text (`ses_f41f7138cffeB43nHxQAyNE1uf`).
        val blocks = MarkdownParser.parse(
            "| A | B | C |\n|---|---|---|\n| 1 | 2 | 3 |\n| 4 | 5 | 6 |\n| 7 | 8 | 9 |",
        )
        assertEquals(1, blocks.size)
        val table = blocks[0] as MdBlock.Table
        assertEquals(3, table.header.size)
        assertEquals(3, table.rows.size)
        assertEquals("A", table.header[0].joinToString("") { it.text })
        assertEquals("9", table.rows[2][2].joinToString("") { it.text })
    }

    @Test
    fun tableOnlyMessageWithSurroundingBlankLinesIsStillOneTable() {
        val blocks = MarkdownParser.parse("\n\n| A | B |\n| --- | --- |\n| 1 | 2 |\n\n")
        assertEquals(1, blocks.size)
        assertTrue(blocks[0] is MdBlock.Table)
    }

    @Test
    fun narrowTablesSplitTheViewportEvenly() {
        assertEquals(120.dp, tableColumnWidth(360.dp, 3))
        assertEquals(180.dp, tableColumnWidth(360.dp, 2))
    }

    @Test
    fun wideTablesFloorAtTheScrollableMinimum() {
        // 8 columns on a 360 dp phone would squeeze to 45 dp each — floor keeps
        // them readable and the grid scrolls instead (upstream `overflow-x`).
        assertEquals(TABLE_MIN_COL_WIDTH, tableColumnWidth(360.dp, 8))
        assertEquals(200.dp, tableColumnWidth(800.dp, 8, minColWidth = 200.dp))
    }

    @Test
    fun degenerateInputsFallBackToTheMinimum() {
        assertEquals(TABLE_MIN_COL_WIDTH, tableColumnWidth(360.dp, 0))
        assertEquals(TABLE_MIN_COL_WIDTH, tableColumnWidth(0.dp, 3))
        assertEquals(TABLE_MIN_COL_WIDTH, tableColumnWidth(Dp(Float.POSITIVE_INFINITY), 3))
    }

    @Test
    fun tabletViewportSplitsProportionally() {
        assertEquals(333.33f, tableColumnWidth(1000.dp, 3).value, 0.01f)
    }
}
