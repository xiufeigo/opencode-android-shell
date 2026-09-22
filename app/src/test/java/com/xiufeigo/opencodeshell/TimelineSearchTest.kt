package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.timeline.TimelineSearch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A2 — the pure half of the timeline search
 * (`_upstream/packages/app/src/session/timeline/search-controller.ts`):
 * `query` (`:94`), `matches` (`:96-135`), `activeIndex` (`:137-143`),
 * `move` (`:242-252`) and `highlight` (`:69-79`).
 */
class TimelineSearchTest {

    private fun text(id: String, value: String) =
        ChatStore.UiPart(id = id, type = "text", text = value)

    private fun tool(id: String, name: String) =
        ChatStore.UiPart(id = id, type = "tool", toolName = name)

    private fun reasoning(id: String, value: String) =
        ChatStore.UiPart(id = id, type = "reasoning", text = value)

    private fun message(id: String, parts: List<ChatStore.UiPart>, role: String = "assistant") =
        ChatStore.UiMessage(id = id, role = role, timeCreated = 0L, parts = parts)

    // --------------------------------------------------------------- query

    @Test
    fun queryIsTrimmedAndCaseFolded() {
        val messages = listOf(message("m1", listOf(text("p1", "Hello World"))))
        assertEquals(1, TimelineSearch.hits(messages, "  hello ").size)
        assertEquals("hello", TimelineSearch.normalize("  HeLLo  "))
    }

    @Test
    fun emptyQueryMatchesNothing() {
        val messages = listOf(message("m1", listOf(text("p1", "hello"))))
        assertEquals(0, TimelineSearch.hits(messages, "").size)
        assertEquals(0, TimelineSearch.hits(messages, "   ").size)
    }

    // -------------------------------------------------------------- matches

    @Test
    fun everyOccurrenceIsAHit() {
        val messages = listOf(message("m1", listOf(text("p1", "cat and cat and CAT"))))
        val hits = TimelineSearch.hits(messages, "cat")
        assertEquals(3, hits.size)
        assertEquals(listOf(0, 1, 2), hits.map { it.occurrence })
        assertEquals(listOf(0 until 3, 8 until 11, 16 until 19), hits.map { it.range })
    }

    @Test
    fun occurrencesAreCountedPerPart() {
        val messages = listOf(
            message("m1", listOf(text("p1", "hit"), text("p2", "hit hit"))),
        )
        val hits = TimelineSearch.hits(messages, "hit")
        assertEquals(listOf("p1", "p2", "p2"), hits.map { it.partID })
        // The occurrence index restarts inside every part (`:116-131`).
        assertEquals(listOf(0, 0, 1), hits.map { it.occurrence })
    }

    @Test
    fun occurrencesDoNotOverlap() {
        // `from = at + query.length` — "aa" in "aaa" is one hit, not two.
        assertEquals(listOf(0 until 2), TimelineSearch.ranges("aaa", "aa"))
    }

    @Test
    fun messageAndPartOrderIsPreserved() {
        val messages = listOf(
            message("m1", listOf(text("p1", "x hit"), text("p2", "hit"))),
            message("m2", listOf(text("p3", "hit"))),
        )
        val hits = TimelineSearch.hits(messages, "hit")
        assertEquals(listOf("m1", "m1", "m2"), hits.map { it.messageID })
        assertEquals(listOf("p1", "p2", "p3"), hits.map { it.partID })
    }

    @Test
    fun userMessagesAreSearchedToo() {
        val messages = listOf(message("m1", listOf(text("p1", "find me")), role = "user"))
        assertEquals(1, TimelineSearch.hits(messages, "find").size)
    }

    @Test
    fun onlyTextPartsAreSearched() {
        val messages = listOf(message("m1", listOf(reasoning("p1", "needle"))))
        assertEquals(0, TimelineSearch.hits(messages, "needle").size)
    }

    // ----------------------------------------------------------- tool names

    @Test
    fun toolNamesAreSearchable() {
        val messages = listOf(message("m1", listOf(tool("p1", "grep"))))
        val byName = TimelineSearch.hits(messages, "grep")
        assertEquals(1, byName.size)
        assertEquals(TimelineSearch.HitKind.TOOL, byName.single().kind)
        assertNull(byName.single().range)
        // The English card title (`TimelineGrouping.toolLabel`) matches as well.
        assertEquals(1, TimelineSearch.hits(messages, "Grep").size)
    }

    @Test
    fun hiddenToolsAreNotSearchable() {
        // `todowrite` renders no card at all (`tool-renderer.tsx:1197`).
        val messages = listOf(message("m1", listOf(tool("p1", "todowrite"))))
        assertEquals(0, TimelineSearch.hits(messages, "todowrite").size)
    }

    @Test
    fun toolSearchCanBeTurnedOff() {
        val messages = listOf(message("m1", listOf(tool("p1", "grep"))))
        assertEquals(0, TimelineSearch.hits(messages, "grep", includeToolNames = false).size)
    }

    // ------------------------------------------------------- active / move

    @Test
    fun clampResetsAStaleCursor() {
        assertEquals(0, TimelineSearch.clamp(0, 0))
        assertEquals(3, TimelineSearch.clamp(3, 5))
        // Out of range falls back to the first hit (`:137-143`).
        assertEquals(0, TimelineSearch.clamp(5, 5))
        assertEquals(0, TimelineSearch.clamp(-2, 5))
    }

    @Test
    fun moveWrapsAroundBothWays() {
        assertEquals(1, TimelineSearch.move(0, 3, 1))
        assertEquals(0, TimelineSearch.move(2, 3, 1))
        assertEquals(2, TimelineSearch.move(0, 3, -1))
        assertEquals(0, TimelineSearch.move(1, 0, 1))
    }

    @Test
    fun activeHitFollowsTheCursorAndIsNullWithoutHits() {
        val messages = listOf(message("m1", listOf(text("p1", "a"), text("p2", "a"))))
        val hits = TimelineSearch.hits(messages, "a")
        assertEquals("p1", TimelineSearch.hitAt(hits, 0)?.partID)
        assertEquals("p2", TimelineSearch.hitAt(hits, 1)?.partID)
        assertNull(TimelineSearch.hitAt(emptyList(), 0))
    }

    // ----------------------------------------------------------- highlight

    @Test
    fun highlightCollectsHitPartsAndTheActiveOne() {
        val messages = listOf(
            message("m1", listOf(text("p1", "hit"), text("p2", "hit"), text("p3", "nothing"))),
        )
        val hits = TimelineSearch.hits(messages, "hit")
        val highlight = TimelineSearch.highlight(hits, 1, "hit")
        assertEquals(setOf("p1", "p2"), highlight.parts)
        assertEquals("p2", highlight.activePart)
        assertEquals("hit", highlight.needle)
        assertTrue(!highlight.isEmpty)
    }

    @Test
    fun highlightOfAnEmptyQueryIsNone() {
        val highlight = TimelineSearch.highlight(
            TimelineSearch.hits(listOf(message("m1", listOf(text("p1", "hit")))), ""),
            0,
            "",
        )
        assertEquals(TimelineSearch.Highlight.None, highlight)
        assertTrue(highlight.isEmpty)
    }

    @Test
    fun rangesOfAnEmptyTextOrNeedleAreEmpty() {
        assertEquals(emptyList<IntRange>(), TimelineSearch.ranges("", "a"))
        assertEquals(emptyList<IntRange>(), TimelineSearch.ranges("abc", ""))
    }

    @Test
    fun rangesAreHalfOpenAndCaseInsensitive() {
        assertEquals(listOf(0 until 3, 3 until 6), TimelineSearch.ranges("abcABC", "abc"))
    }
}
