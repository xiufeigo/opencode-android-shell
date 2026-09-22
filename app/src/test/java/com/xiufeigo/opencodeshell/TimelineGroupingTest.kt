package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.timeline.TimelineGrouping
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `compact`-preset transcription (`_upstream/packages/session-ui/src/timeline/
 * projection.ts:593-688` + `tools/tool-renderer.tsx:516-558`): every tool and
 * reasoning part folds into one group, failed/question tools stay standalone,
 * `step-start`/`step-finish` never produce a row, and the group row reads
 * `Used {count} {names}`.
 */
class TimelineGroupingTest {

    private var seq = 0

    private fun tool(
        name: String,
        status: String = "completed",
        input: String? = """{"filePath":"f"}""",
    ) = ChatStore.UiPart(
        id = "p${seq++}_$name",
        type = "tool",
        toolName = name,
        toolStatus = status,
        toolInput = input,
    )

    private fun text(body: String = "hi") = ChatStore.UiPart(
        id = "t${seq++}",
        type = "text",
        text = body,
    )

    private fun reasoning(body: String = "thinking") = ChatStore.UiPart(
        id = "r${seq++}",
        type = "reasoning",
        text = body,
    )

    private fun raw(type: String, json: String? = null) = ChatStore.UiPart(
        id = "raw${seq++}_$type",
        type = type,
        rawJson = json,
    )

    @Test
    fun everyToolAndReasoningPartFoldsIntoOneGroup() {
        val parts = listOf(tool("read"), tool("glob"), tool("grep"), tool("list"), tool("bash"), tool("edit"))
        val rows = TimelineGrouping.rowsFor(parts)
        assertEquals(1, rows.size)
        val group = rows.single() as TimelineGrouping.Row.Context
        assertEquals(parts.size, group.parts.size)
        assertEquals("ctx:" + parts.first().id, group.key)
        assertFalse(group.busy)
    }

    @Test
    fun anyOtherPartBreaksTheGroup() {
        val rows = TimelineGrouping.rowsFor(
            listOf(tool("read"), text("thinking"), tool("read"), tool("grep")),
        )
        assertEquals(3, rows.size)
        assertEquals(1, (rows[0] as TimelineGrouping.Row.Context).parts.size)
        assertTrue(rows[1] is TimelineGrouping.Row.Part)
        assertEquals(2, (rows[2] as TimelineGrouping.Row.Context).parts.size)
    }

    @Test
    fun todowriteIsHiddenAndDoesNotBreakAGroup() {
        val rows = TimelineGrouping.rowsFor(listOf(tool("read"), tool("todowrite"), tool("grep")))
        // `HIDDEN_TOOLS` simply disappears; the group survives it.
        assertEquals(1, rows.size)
        assertEquals(2, (rows.single() as TimelineGrouping.Row.Context).parts.size)
    }

    @Test
    fun failedAndQuestionToolsStayStandalone() {
        val rows = TimelineGrouping.rowsFor(
            listOf(tool("read"), tool("edit", status = "error"), tool("question"), tool("grep")),
        )
        assertEquals(4, rows.size)
        assertTrue(rows[0] is TimelineGrouping.Row.Context)
        assertEquals("edit", (rows[1] as TimelineGrouping.Row.Part).part.toolName)
        assertEquals("question", (rows[2] as TimelineGrouping.Row.Part).part.toolName)
        assertTrue(rows[3] is TimelineGrouping.Row.Context)
        assertTrue(TimelineGrouping.isStandaloneTool(tool("question")))
        assertTrue(TimelineGrouping.isStandaloneTool(tool("bash", status = "error")))
        assertFalse(TimelineGrouping.isStandaloneTool(tool("bash")))
    }

    @Test
    fun stepPartsNeverProduceTheirOwnRow() {
        // `step-start` / `step-finish` ride along inside an open group …
        val withGroup = TimelineGrouping.rowsFor(
            listOf(tool("read"), raw("step-start"), raw("step-finish"), tool("grep")),
        )
        assertEquals(1, withGroup.size)
        assertEquals(4, (withGroup.single() as TimelineGrouping.Row.Context).parts.size)

        // … and vanish when there is no group to join.
        val alone = TimelineGrouping.rowsFor(
            listOf(text("a"), raw("step-start"), raw("step-finish"), text("b")),
        )
        assertEquals(2, alone.size)
        assertTrue(alone.all { it is TimelineGrouping.Row.Part })

        // `snapshot` is dropped outright.
        assertTrue(TimelineGrouping.rowsFor(listOf(raw("snapshot"))).isEmpty())
    }

    @Test
    fun rawPartTypesRenderAsTheirOwnRows() {
        val rows = TimelineGrouping.rowsFor(
            listOf(raw("patch", """{"files":["a.kt"]}"""), raw("subtask"), raw("agent"), raw("retry")),
        )
        assertEquals(4, rows.size)
        assertEquals(
            listOf("patch", "subtask", "agent", "retry"),
            rows.map { (it as TimelineGrouping.Row.Part).part.type },
        )
    }

    @Test
    fun labelReadsUsedCountNames() {
        val label = TimelineGrouping.label(
            listOf(tool("read"), tool("glob"), tool("read"), tool("bash")),
        )
        // Distinct names, in order of first appearance (`tool-renderer.tsx:516-533`).
        assertEquals(listOf("read", "glob", "bash"), label.toolNames)
        assertEquals(listOf("Read", "Glob", "Shell"), label.englishNames)
        assertEquals(4, label.count)
        assertEquals("Used 4 Read, Glob, Shell", label.englishText)
        assertFalse(label.isUpdatesOnly)
    }

    @Test
    fun labelFallsBackToThoughtsAndUpdates() {
        val thoughts = TimelineGrouping.label(listOf(reasoning(), reasoning()))
        assertEquals(0, thoughts.toolCount)
        assertEquals(2, thoughts.thoughts)
        assertEquals("Thoughts", thoughts.englishTitle)
        assertEquals("Used 2 Thoughts", thoughts.englishText)

        val single = TimelineGrouping.label(listOf(reasoning()))
        assertEquals("Used 1 Thought", single.englishText)

        val empty = TimelineGrouping.label(listOf(raw("step-start")))
        assertTrue(empty.isUpdatesOnly)
        assertEquals("Updates", empty.englishText)
        assertEquals(0, empty.count)
    }

    @Test
    fun toolLabelsMatchUpstreamTitles() {
        assertEquals("Patch", TimelineGrouping.toolLabel("apply_patch"))
        assertEquals("Patch", TimelineGrouping.toolLabel("patch"))
        assertEquals("Skill", TimelineGrouping.toolLabel("skill"))
        assertEquals("Agent", TimelineGrouping.toolLabel("subagent"))
        assertEquals("Websearch", TimelineGrouping.toolLabel("websearch"))
        assertEquals("Read", TimelineGrouping.toolLabel("read"))
        assertEquals("Tool", TimelineGrouping.toolLabel(null))
    }

    @Test
    fun groupIsBusyWhileWorkingOrWhileAToolRuns() {
        val running = listOf(tool("read", status = "running"))
        assertTrue(TimelineGrouping.groupBusy(running, working = true))
        assertTrue(TimelineGrouping.groupBusy(running, working = false))
        assertTrue(TimelineGrouping.groupBusy(listOf(tool("read")), working = true))
        assertFalse(TimelineGrouping.groupBusy(listOf(tool("read")), working = false))

        val parts = listOf(tool("read"), text("x"), tool("read"))
        val working = TimelineGrouping.rowsFor(parts, working = true)
        assertTrue((working[0] as TimelineGrouping.Row.Context).busy)
        assertTrue((working[2] as TimelineGrouping.Row.Context).busy)
        val idle = TimelineGrouping.rowsFor(parts, working = false)
        assertTrue(idle.filterIsInstance<TimelineGrouping.Row.Context>().none { it.busy })
    }

    @Test
    fun groupingKeepsEveryVisiblePartSoStreamingFingerprintsStayStable() {
        val parts = listOf(tool("read"), tool("grep"), reasoning())
        val rows = TimelineGrouping.rowsFor(parts)
        var visible = 0
        rows.forEach { row ->
            visible += when (row) {
                is TimelineGrouping.Row.Part -> 1
                is TimelineGrouping.Row.Context -> row.parts.size
            }
        }
        assertEquals(parts.size, visible)
    }

    @Test
    fun reasoningSummariesOffDropsReasoningBeforeGrouping() {
        val parts = listOf(tool("read"), reasoning(), reasoning())
        val on = TimelineGrouping.rowsFor(parts, reasoningSummaries = true)
        assertEquals(3, (on.single() as TimelineGrouping.Row.Context).parts.size)

        val off = TimelineGrouping.rowsFor(parts, reasoningSummaries = false)
        val group = off.single() as TimelineGrouping.Row.Context
        assertEquals(1, group.parts.size)
        assertEquals("Used 1 Read", TimelineGrouping.label(group.parts).englishText)
        // A reasoning-only turn produces no rows at all.
        assertTrue(TimelineGrouping.rowsFor(listOf(reasoning()), reasoningSummaries = false).isEmpty())
        assertFalse(TimelineGrouping.isGroupMember(reasoning(), reasoningSummaries = false))
    }

    @Test
    fun emptyInputProducesNoRows() {
        assertTrue(TimelineGrouping.rowsFor(emptyList()).isEmpty())
        assertTrue(TimelineGrouping.rowsFor(listOf(tool("todowrite"))).isEmpty())
        // A blank reasoning part is not a row either.
        assertTrue(TimelineGrouping.rowsFor(listOf(reasoning(""))).isEmpty())
    }
}
