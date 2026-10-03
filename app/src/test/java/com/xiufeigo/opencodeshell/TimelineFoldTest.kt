package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.timeline.TimelineFold
import com.xiufeigo.opencodeshell.ui.session.timeline.TimelineGrouping
import com.xiufeigo.opencodeshell.ui.session.timeline.TimelineRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * U-4 fold-threshold pins (`research/parity-audit-2026-09-24.md` U-4):
 *
 * - [TimelineFold] thresholds: expanded tool bodies fold past
 *   [TimelineFold.OUTPUT_FOLD_LINES], prose/code messages past
 *   [TimelineFold.TEXT_FOLD_LINES]/[TimelineFold.TEXT_FOLD_CHARS];
 * - [TimelineFold.markdownHead] never leaves a ` ``` ` fence open in the
 *   collapsed preview;
 * - consecutive same-type tool calls fold into one collapsible `Context` row
 *   ([TimelineGrouping], default collapsed by the `MessageTimeline`
 *   `toolOpen[row.key] == true` contract);
 * - [TimelineRows.contentType] keeps `LazyColumn` item slots warm per type.
 */
class TimelineFoldTest {

    private var seq = 0

    private fun tool(name: String, status: String = "completed") = ChatStore.UiPart(
        id = "p${seq++}_$name",
        type = "tool",
        toolName = name,
        toolStatus = status,
        toolInput = """{"filePath":"f"}""",
    )

    private fun text(body: String) = ChatStore.UiPart(
        id = "t${seq++}",
        type = "text",
        text = body,
    )

    private fun message(id: String, role: String, vararg parts: ChatStore.UiPart) =
        ChatStore.UiMessage(id = id, role = role, timeCreated = 0L, parts = parts.toList())

    // ------------------------------------------------------- output threshold

    @Test
    fun shortBodyPassesThroughUnfolded() {
        val lines = (1..TimelineFold.OUTPUT_FOLD_LINES).map { "line $it" }
        val fold = TimelineFold.foldLines(lines)
        assertFalse(fold.folded)
        assertEquals(0, fold.hiddenLines)
        assertEquals(lines, fold.head)
    }

    @Test
    fun bodyPastFifteenLinesKeepsHeadAndCountsHidden() {
        val lines = (1..20).map { "line $it" }
        val fold = TimelineFold.foldLines(lines)
        assertTrue(fold.folded)
        assertEquals(TimelineFold.OUTPUT_FOLD_LINES, fold.head.size)
        assertEquals(20 - TimelineFold.OUTPUT_FOLD_LINES, fold.hiddenLines)
        assertEquals("line 1", fold.head.first())
        assertEquals("line ${TimelineFold.OUTPUT_FOLD_LINES}", fold.head.last())
    }

    @Test
    fun outputThresholdIsFifteenLines() {
        assertEquals(15, TimelineFold.OUTPUT_FOLD_LINES)
        assertFalse(TimelineFold.isLongBody(15))
        assertTrue(TimelineFold.isLongBody(16))
    }

    // ---------------------------------------------------------- text threshold

    @Test
    fun shortMessageIsNotLong() {
        assertFalse(TimelineFold.isLongText("hello\nworld"))
    }

    @Test
    fun messagePastThirtyLinesFolds() {
        val body = (1..35).joinToString("\n") { "paragraph $it" }
        val fold = TimelineFold.foldText(body)
        assertTrue(fold.folded)
        assertEquals(TimelineFold.TEXT_FOLD_LINES, fold.head.size)
        assertEquals(35 - TimelineFold.TEXT_FOLD_LINES, fold.hiddenLines)
    }

    @Test
    fun singleGiantLineFoldsOnChars() {
        val body = "x".repeat(TimelineFold.TEXT_FOLD_CHARS + 1)
        val fold = TimelineFold.foldText(body)
        assertTrue(fold.folded)
        // The char-capped giant still reports a positive hidden count so the
        // "show N more lines" row always has a number.
        assertTrue(fold.hiddenLines >= 1)
    }

    // ------------------------------------------------------------ fence safety

    @Test
    fun headCutInsideFenceClosesIt() {
        val body = (1..40).joinToString("\n") {
            when (it) {
                2 -> "```kotlin"
                39 -> "```"
                else -> "code $it"
            }
        }
        val head = TimelineFold.markdownHead(body)
        val fences = head.split('\n').count { it.trimStart().startsWith("```") }
        assertEquals(0, fences % 2)
        assertTrue(head.trimEnd().endsWith("```"))
    }

    @Test
    fun headCutOutsideFenceAddsNothing() {
        val body = (1..40).joinToString("\n") { "line $it" }
        assertEquals(
            (1..TimelineFold.TEXT_FOLD_LINES).joinToString("\n") { "line $it" },
            TimelineFold.markdownHead(body),
        )
    }

    @Test
    fun shortMarkdownPassesThrough() {
        val body = "```kotlin\nval a = 1\n```"
        assertEquals(body, TimelineFold.markdownHead(body))
    }

    // ------------------------------------------- consecutive same-type folding

    @Test
    fun consecutiveSameToolCallsFoldIntoOneCollapsibleGroup() {
        val parts = List(5) { tool("read") }
        val rows = TimelineGrouping.rowsFor(parts)
        assertEquals(1, rows.size)
        val group = rows.single() as TimelineGrouping.Row.Context
        assertEquals(5, group.parts.size)
        // The group row is keyed by its first part: a stable LazyColumn key.
        assertEquals("ctx:" + parts.first().id, group.key)
    }

    @Test
    fun sameToolRunsSeparatedByTextFormTwoGroups() {
        val rows = TimelineGrouping.rowsFor(
            listOf(tool("read"), tool("read"), text("note"), tool("read")),
        )
        assertEquals(3, rows.size)
        assertEquals(2, (rows[0] as TimelineGrouping.Row.Context).parts.size)
        assertTrue(rows[1] is TimelineGrouping.Row.Part)
        assertEquals(1, (rows[2] as TimelineGrouping.Row.Context).parts.size)
    }

    // ------------------------------------------------------- contentType pins

    @Test
    fun contentTypeSplitsMessagesByRoleAndSeparatorsByKind() {
        val user = TimelineRows.Row.Message(message("m1", "user", text("hi")))
        val assistant = TimelineRows.Row.Message(message("m2", "assistant", text("yo")))
        val sep = TimelineRows.Row.Separator(TimelineRows.SeparatorKind.MODEL, "m2", model = "a/b")
        val interrupted = TimelineRows.Row.Separator(TimelineRows.SeparatorKind.INTERRUPTED, "m1")
        assertEquals("message:user", TimelineRows.contentType(user))
        assertEquals("message:assistant", TimelineRows.contentType(assistant))
        assertEquals("separator:model", TimelineRows.contentType(sep))
        assertEquals("separator:interrupted", TimelineRows.contentType(interrupted))
    }
}
