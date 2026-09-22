package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.markdown.CodeHighlight
import com.xiufeigo.opencodeshell.ui.markdown.MdBlock
import com.xiufeigo.opencodeshell.ui.markdown.MarkdownParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WAVE 2.2a — GFM tables (`MarkdownParser` → [MdBlock.Table]).
 *
 * Every case pins the `marked` GFM-table contract the upstream renderer
 * (`_upstream/packages/session-ui/src/components/markdown.css` `table`/`th`/`td`
 * rules) relies on: header + delimiter row, inline formatting inside cells,
 * graceful paragraph fallback when the delimiter row is missing or the column
 * counts disagree. JVM-testable: the parser has no Android/Compose imports.
 */
class MarkdownTableTest {

    private fun parse(text: String) = MarkdownParser.parse(text)

    @Test
    fun aHeaderPlusDelimiterRowIsATable() {
        val blocks = parse("| Name | Age |\n| --- | --- |\n| Ann | 30 |")
        assertEquals(1, blocks.size)
        val table = blocks[0] as MdBlock.Table
        assertEquals(2, table.header.size)
        assertEquals(1, table.rows.size)
        assertEquals("Name", table.header[0].joinToString("") { it.text })
        assertEquals("30", table.rows[0][1].joinToString("") { it.text })
    }

    @Test
    fun outerPipesAreOptional() {
        val table = parse("Name | Age\n--- | ---\nAnn | 30")[0] as MdBlock.Table
        assertEquals(2, table.header.size)
        assertEquals(1, table.rows.size)
        assertEquals("Ann", table.rows[0][0].joinToString("") { it.text })
    }

    @Test
    fun alignmentMarkersAreAccepted() {
        val table = parse("| L | C | R |\n| :--- | :---: | ---: |\n| a | b | c |")[0] as MdBlock.Table
        assertEquals(3, table.header.size)
        assertEquals(1, table.rows.size)
    }

    @Test
    fun cellsKeepInlineFormatting() {
        val table = parse("| A | B |\n| --- | --- |\n| **bold** | `code` |")[0] as MdBlock.Table
        assertEquals("bold", table.rows[0][0].single { it.bold }.text)
        assertEquals("code", table.rows[0][1].single { it.code }.text)
    }

    @Test
    fun escapedPipesStayInsideACell() {
        val table = parse("| A | B |\n| --- | --- |\n| a \\| b | c |")[0] as MdBlock.Table
        assertEquals(2, table.rows[0].size)
        assertEquals("a | b", table.rows[0][0].joinToString("") { it.text })
    }

    @Test
    fun pipesInsideCodeSpansDoNotSplit() {
        val table = parse("| A |\n| --- |\n| `a|b` |")[0] as MdBlock.Table
        assertEquals(1, table.rows[0].size)
        assertEquals("a|b", table.rows[0][0].single { it.code }.text)
    }

    @Test
    fun withoutADelimiterRowItIsAParagraph() {
        // `marked` GFM needs the delimiter row; without it the text is prose —
        // and a lone `a | b` line must never swallow the next paragraph.
        val blocks = parse("a | b\n\ntail")
        assertEquals(2, blocks.size)
        assertTrue(blocks[0] is MdBlock.Paragraph)
        assertTrue(blocks[1] is MdBlock.Paragraph)
    }

    @Test
    fun aNonDelimiterSecondLineIsAParagraph() {
        val blocks = parse("| A |\nnot a delimiter")
        assertEquals(1, blocks.size)
        assertTrue(blocks[0] is MdBlock.Paragraph)
    }

    @Test
    fun aMismatchedDelimiterColumnCountIsAParagraph() {
        val blocks = parse("| A | B |\n| --- |\n| a | b |")
        assertTrue(blocks[0] is MdBlock.Paragraph)
    }

    @Test
    fun aShortBodyRowEndsTheTable() {
        val blocks = parse("| A | B |\n| --- | --- |\n| a | b |\n| only |\n\ntail")
        val table = blocks[0] as MdBlock.Table
        assertEquals(1, table.rows.size)
        assertEquals(3, blocks.size)
        assertTrue(blocks[1] is MdBlock.Paragraph)
        assertTrue(blocks[2] is MdBlock.Paragraph)
    }

    @Test
    fun aTableEndsAtAFence() {
        val blocks = parse("| A |\n| --- |\n| a |\n```\nx\n```")
        assertEquals(2, blocks.size)
        assertTrue(blocks[0] is MdBlock.Table)
        assertTrue(blocks[1] is MdBlock.CodeBlock)
    }

    @Test
    fun aTableHeaderDoesNotSwallowThePreviousParagraph() {
        // The delimiter probe in `startsBlock` must not merge `intro` into the table.
        val blocks = parse("intro\n| A |\n| --- |\n| a |")
        assertEquals(2, blocks.size)
        assertTrue(blocks[0] is MdBlock.Paragraph)
        assertTrue(blocks[1] is MdBlock.Table)
    }

    @Test
    fun aHeaderOnlyTableHasNoRows() {
        val table = parse("| A |\n| --- |")[0] as MdBlock.Table
        assertTrue(table.rows.isEmpty())
    }

    @Test
    fun aBlankLineBeforeTheDelimiterIsNotATable() {
        // GFM requires the delimiter immediately after the header: a blank line
        // breaks the table, and the `| --- |` row must not glue onto `tail`.
        val blocks = parse("| A |\n\n| --- |\n\ntail")
        assertTrue(blocks.none { it is MdBlock.Table })
    }

    @Test
    fun codeSpanPipesDoNotConfuseTheDelimiterProbe() {
        // A paragraph line containing `|` followed by a real table must stay a
        // paragraph: the probe pairs the delimiter with the table header only.
        val blocks = parse("use `a|b` here\n\n| A |\n| --- |\n| x |")
        assertEquals(2, blocks.size)
        assertTrue(blocks[0] is MdBlock.Paragraph)
        assertTrue(blocks[1] is MdBlock.Table)
    }
}
