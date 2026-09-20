package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.timeline.TimelineGrouping
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `groupParts()` transcription (`research/02` §3.7): only *consecutive*
 * `read | glob | grep | list` merge, `todowrite` is hidden, the summary counts
 * read / search(glob+grep) / list, and only the trailing group is `busy`.
 */
class TimelineGroupingTest {

    private var seq = 0

    private fun tool(name: String) = ChatStore.UiPart(
        id = "p${seq++}_$name",
        type = "tool",
        toolName = name,
        toolStatus = "completed",
        toolInput = """{"filePath":"f"}""",
    )

    private fun text(body: String = "hi") = ChatStore.UiPart(
        id = "t${seq++}",
        type = "text",
        text = body,
    )

    @Test
    fun consecutiveContextToolsBecomeOneGroup() {
        val parts = listOf(tool("read"), tool("glob"), tool("grep"), tool("list"))
        val rows = TimelineGrouping.rowsFor(parts)
        assertEquals(1, rows.size)
        val group = rows.single() as TimelineGrouping.Row.Context
        assertEquals(listOf("read", "glob", "grep", "list"), group.parts.map { it.toolName })
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
        // `HIDDEN_TOOLS` simply disappears; the group survives it (documented
        // reading of the upstream loop, which has no flush for hidden parts).
        assertEquals(1, rows.size)
        assertEquals(2, (rows.single() as TimelineGrouping.Row.Context).parts.size)
    }

    @Test
    fun nonContextToolsStayIndividualRows() {
        val rows = TimelineGrouping.rowsFor(listOf(tool("bash"), tool("task"), tool("edit")))
        assertEquals(3, rows.size)
        assertTrue(rows.all { it is TimelineGrouping.Row.Part })
        assertEquals(listOf("bash", "task", "edit"), rows.map { (it as TimelineGrouping.Row.Part).part.toolName })
    }

    @Test
    fun onlyTheTrailingGroupIsBusy() {
        val parts = listOf(tool("read"), text("x"), tool("read"), tool("read"))
        val working = TimelineGrouping.rowsFor(parts, working = true)
        assertFalse((working[0] as TimelineGrouping.Row.Context).busy)
        assertTrue((working[2] as TimelineGrouping.Row.Context).busy)

        val idle = TimelineGrouping.rowsFor(parts, working = false)
        assertTrue(idle.filterIsInstance<TimelineGrouping.Row.Context>().none { it.busy })
    }

    @Test
    fun summaryCountsReadSearchAndList() {
        val summary = TimelineGrouping.summary(
            listOf(tool("read"), tool("read"), tool("glob"), tool("grep"), tool("list"), tool("bash")),
        )
        assertEquals(2, summary.read)
        assertEquals(2, summary.search)
        assertEquals(1, summary.list)
        assertEquals(5, summary.total)
        assertFalse(summary.isEmpty)
        assertTrue(TimelineGrouping.summary(emptyList()).isEmpty)
    }

    @Test
    fun groupingKeepsEveryPartSoStreamingFingerprintsStayStable() {
        val parts = listOf(tool("read"), tool("grep"))
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
    fun emptyInputProducesNoRows() {
        assertTrue(TimelineGrouping.rowsFor(emptyList()).isEmpty())
        assertTrue(TimelineGrouping.rowsFor(listOf(tool("todowrite"))).isEmpty())
    }
}
