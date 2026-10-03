package com.xiufeigo.opencodeshell

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-level pins for the group-row chrome fixes inside `@Composable`
 * functions (not reachable from a plain JVM call): the `ToolDisplay`-style
 * whole-row replacement for the dedicated renderers (no stacked trigger
 * rows) and the single shared `bodyLines` cache in `GroupToolRow`.
 */
class ContextToolGroupCardRenderTest {

    private fun source(relative: String): String {
        val file = File(relative).takeIf { it.isFile } ?: File("app/$relative")
        return file.readText()
    }

    private fun groupCard() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/timeline/ContextToolGroupCard.kt")

    @Test
    fun dedicatedRenderersReplaceTheGroupMemberRow() {
        // The `tool` dispatch of `ContextToolGroupRow`, up to its generic
        // `else -> GroupToolRow`: each dedicated card renders as the row
        // itself — the old flat trigger around `ToolErrorCard`/`QuestionCard`/
        // `SubagentCard` stacked two same-named headers.
        val dispatch = groupCard()
            .substringAfter("\"tool\" ->")
            .substringBefore("else -> GroupToolRow(")
        for (call in listOf(
            "ToolRendererKind.ERROR -> ToolErrorCard(part = part)",
            "ToolRendererKind.QUESTION -> QuestionCard(part = part)",
            "ToolRendererKind.SUBAGENT -> SubagentCard(part = part)",
        )) {
            assertTrue("$call must render as the row", dispatch.contains(call))
        }
        // No dedicated kind may still route through the wrapping trigger row
        // (the old SUBAGENT branch's `else { GroupToolRow(...) }`).
        assertFalse(dispatch.contains("GroupToolRow("))
    }

    @Test
    fun groupToolRowBodyLinesAreCachedOnceAndSharedWithTheBody() {
        val row = groupCard()
            .substringAfter("private fun GroupToolRow(")
            .substringBefore("private val TRIGGER_HEIGHT")
        assertTrue(row.contains("ToolPresentation.bodyLines(part)"))
        assertTrue(row.contains("ToolCardPolicy.canExpand(part, lines)"))
        assertTrue(
            row.contains(
                "ToolCardPolicy.resolveOpen(part, userOpen, shellExpanded, editExpanded, lines)",
            ),
        )
        assertTrue(row.contains("ToolBody(part, lines)"))
    }

    @Test
    fun groupLabelStacksChipsUnderTheUsedLine() {
        // 5558 v81: the `Used …` shimmer and the P2 `ToolCountChips` row were
        // sequential siblings of the fixed-height trigger row with no gap, so
        // a mixed group read `Used 2 Shell, GrepShell . Grep`. The label
        // stacks them now, and the trigger wraps the second line instead of
        // clipping it.
        val label = groupCard()
            .substringAfter("private fun GroupLabelText(")
            .substringBefore("private fun GroupToolRow(")
        assertTrue(label.contains("Column {"))
        assertTrue(label.contains("ToolCountChips(chips = chips)"))
        val trigger = groupCard()
            .substringAfter("fun ContextToolGroupRow(")
            .substringBefore("private fun GroupLabelText(")
        assertTrue(trigger.contains(".heightIn(min = TRIGGER_HEIGHT)"))
        assertFalse(trigger.contains(".height(TRIGGER_HEIGHT)"))
    }
}
