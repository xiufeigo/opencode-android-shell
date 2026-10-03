package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.timeline.TimelineRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A3 + A5 — the pure timeline row model: separator insertion
 * (`session-ui/src/components/timeline-separator.tsx` call sites
 * `session-timeline-row.tsx:509-513,637-653`, `message-content.tsx:434-465`,
 * `timeline/projection.ts:311-376`) and the message-rail ticks
 * (`components/message-nav.tsx:33-79`).
 */
class TimelineRowsTest {

    private fun text(id: String, value: String) =
        ChatStore.UiPart(id = id, type = "text", text = value)

    private fun compaction(id: String) = ChatStore.UiPart(id = id, type = "compaction")

    private fun message(
        id: String,
        parts: List<ChatStore.UiPart> = listOf(text("$id:0", "hi")),
        role: String = "assistant",
        model: String? = null,
        interrupted: Boolean = false,
    ) = ChatStore.UiMessage(
        id = id,
        role = role,
        timeCreated = 0L,
        parts = parts,
        model = model,
        interrupted = interrupted,
    )

    private fun separators(rows: List<TimelineRows.Row>) =
        rows.filterIsInstance<TimelineRows.Row.Separator>()

    private fun messages(rows: List<TimelineRows.Row>) =
        rows.filterIsInstance<TimelineRows.Row.Message>().map { it.message.id }

    // ------------------------------------------------------------ grouping

    @Test
    fun plainMessagesProduceOneRowEach() {
        val rows = TimelineRows.build(listOf(message("m1"), message("m2")))
        assertEquals(listOf("m1", "m2"), messages(rows))
        assertTrue(separators(rows).isEmpty())
    }

    @Test
    fun emptySessionHasNoRows() {
        assertTrue(TimelineRows.build(emptyList()).isEmpty())
    }

    @Test
    fun rowKeysAreUniqueAndStable() {
        val input = listOf(
            message("m1", role = "user", model = "anthropic/a"),
            message("m2", parts = listOf(compaction("c1")), model = "openai/b"),
            message("m3", interrupted = true),
        )
        val keys = TimelineRows.build(input).map { it.key }
        assertEquals(keys.size, keys.toSet().size)
        assertEquals(keys, TimelineRows.build(input).map { it.key })
    }

    // -------------------------------------------------------- model switch

    @Test
    fun modelSwitchInsertsTheSeparatorBeforeTheNewModel() {
        val rows = TimelineRows.build(
            listOf(
                message("m1", role = "user", model = "anthropic/claude"),
                message("m2", model = "openai/gpt-5"),
            ),
        )
        val separator = separators(rows).single()
        assertEquals(TimelineRows.SeparatorKind.MODEL, separator.kind)
        assertEquals("m2", separator.anchorID)
        assertEquals("openai/gpt-5", separator.model)
        // Visual order: user turn, separator, the new turn.
        assertEquals(listOf("m1", "m2"), messages(rows))
        assertTrue(rows.indexOf(separator) < rows.indexOfFirst { it is TimelineRows.Row.Message && it.message.id == "m2" })
    }

    @Test
    fun theInitialModelIsNotASwitch() {
        val rows = TimelineRows.build(listOf(message("m1", model = "anthropic/claude")))
        assertTrue(separators(rows).isEmpty())
    }

    @Test
    fun anUnchangedModelInsertsNothing() {
        val rows = TimelineRows.build(
            listOf(message("m1", model = "anthropic/claude"), message("m2", model = "anthropic/claude")),
        )
        assertTrue(separators(rows).isEmpty())
    }

    @Test
    fun modelLabelDropsTheProviderPrefix() {
        assertEquals("claude-sonnet-4", TimelineRows.modelLabel("anthropic/claude-sonnet-4"))
        assertEquals("gpt-5", TimelineRows.modelLabel("gpt-5"))
        assertEquals("", TimelineRows.modelLabel(null))
        assertEquals("", TimelineRows.modelLabel("   "))
    }

    // ----------------------------------------------------------- compaction

    @Test
    fun compactionInsertsStartAndEndAroundItsMessage() {
        val rows = TimelineRows.build(
            listOf(message("m1", role = "user"), message("m2", parts = listOf(compaction("c1")))),
        )
        assertEquals(
            listOf(
                TimelineRows.SeparatorKind.COMPACTION_START,
                TimelineRows.SeparatorKind.COMPACTION_END,
            ),
            separators(rows).map { it.kind },
        )
        val start = rows.indexOfFirst { it is TimelineRows.Row.Separator }
        val messageAt = rows.indexOfFirst { it is TimelineRows.Row.Message && it.message.id == "m2" }
        assertEquals(start + 1, messageAt)
        assertEquals(TimelineRows.SeparatorKind.COMPACTION_START, (rows[start] as TimelineRows.Row.Separator).kind)
    }

    @Test
    fun compactedMessagesWithoutACompactionPartInsertNothing() {
        val rows = TimelineRows.build(listOf(message("m1", parts = listOf(text("p", "hello")))))
        assertTrue(separators(rows).isEmpty())
    }

    // --------------------------------------------------------- interrupted

    @Test
    fun interruptedTurnGetsADividerAfterIt() {
        val rows = TimelineRows.build(
            listOf(message("m1", interrupted = true), message("m2", role = "user")),
        )
        val separator = separators(rows).single()
        assertEquals(TimelineRows.SeparatorKind.INTERRUPTED, separator.kind)
        assertEquals("m1", separator.anchorID)
        // The divider sits between the interrupted turn and the next one
        // (`projection.ts:349-352`).
        assertEquals(listOf("m1", "m2"), messages(rows))
        assertTrue(rows.indexOf(separator) > rows.indexOfFirst { it is TimelineRows.Row.Message })
    }

    @Test
    fun aTrailingInterruptionClosesTheTimeline() {
        val rows = TimelineRows.build(listOf(message("m1", role = "user"), message("m2", interrupted = true)))
        assertEquals(TimelineRows.SeparatorKind.INTERRUPTED, separators(rows).single().kind)
        assertTrue(rows.last() is TimelineRows.Row.Separator)
    }

    @Test
    fun aCompactedTurnKeepsItsInterruptionSilent() {
        // `!compaction && …` (`projection.ts:350`): a compaction owns the divider.
        val rows = TimelineRows.build(
            listOf(message("m1", parts = listOf(compaction("c1")), interrupted = true), message("m2")),
        )
        assertTrue(separators(rows).none { it.kind == TimelineRows.SeparatorKind.INTERRUPTED })
    }

    @Test
    fun interruptedFlagOnAUserMessageAlsoDivides() {
        val rows = TimelineRows.build(listOf(message("m1", role = "user", interrupted = true)))
        assertEquals(TimelineRows.SeparatorKind.INTERRUPTED, separators(rows).single().kind)
    }

    // ---------------------------------------------------------------- ticks

    @Test
    fun ticksAreOldestFirstAndOffsetByTheLeadingRows() {
        val rows = TimelineRows.build(
            listOf(
                message("u1", role = "user"),
                message("a1"),
                message("u2", role = "user"),
            ),
        )
        val noLeading = TimelineRows.userTicks(rows)
        assertEquals(listOf("u1", "u2"), noLeading.map { it.messageID })
        // LazyColumn index grows towards the visual top, so the oldest user turn
        // has the highest index.
        assertEquals(listOf(2, 0), noLeading.map { it.index })
        val withLeading = TimelineRows.userTicks(rows, leading = 2)
        assertEquals(listOf(4, 2), withLeading.map { it.index })
    }

    @Test
    fun assistantTurnsHaveNoTick() {
        val rows = TimelineRows.build(listOf(message("a1"), message("a2")))
        assertTrue(TimelineRows.userTicks(rows).isEmpty())
    }

    @Test
    fun tickPreviewCollapsesWhitespaceAndTruncates() {
        val rows = TimelineRows.build(
            listOf(message("u1", role = "user", parts = listOf(text("p", "  first\n\nsecond   line  ")))),
        )
        assertEquals("first second line", TimelineRows.userTicks(rows).single().preview)
    }

    @Test
    fun tickPreviewIsTruncated() {
        val long = "x".repeat(TimelineRows.PREVIEW_MAX + 10)
        val preview = TimelineRows.preview(message("u1", role = "user", parts = listOf(text("p", long))))
        assertEquals(TimelineRows.PREVIEW_MAX + 1, preview!!.length)
        assertTrue(preview.endsWith("…"))
    }

    @Test
    fun tickPreviewCapLandsOutsideASurrogatePair() {
        // The old `take` kept a lone high surrogate (renders as U+FFFD).
        val long = "x".repeat(TimelineRows.PREVIEW_MAX - 1) + "😀tail"
        val preview = TimelineRows.preview(message("u1", role = "user", parts = listOf(text("p", long))))
        assertEquals(TimelineRows.PREVIEW_MAX, preview!!.length)
        assertTrue(preview.endsWith("…"))
        assertFalse(Character.isHighSurrogate(preview[preview.length - 2]))
    }

    @Test
    fun aTurnWithoutTextHasNoPreview() {
        assertNull(TimelineRows.preview(message("u1", role = "user", parts = emptyList())))
    }

    @Test
    fun currentTickPicksTheOneNearestTheViewportMiddle() {
        val ticks = listOf(
            TimelineRows.Tick("older", 20, null),
            TimelineRows.Tick("newer", 8, null),
        )
        // Visible range `5…10` → anchor 7 → the tick at 8 wins.
        assertEquals("newer", TimelineRows.currentTick(ticks, 5, 10))
    }

    @Test
    fun currentTickPrefersTheNewerTickOnATie() {
        val ticks = listOf(
            TimelineRows.Tick("older", 10, null),
            TimelineRows.Tick("newer", 2, null),
        )
        // Anchor 6 is equidistant; the newest turn is the one the user is in.
        assertEquals("newer", TimelineRows.currentTick(ticks, 4, 8))
    }

    @Test
    fun currentTickWithoutTicksIsNull() {
        assertNull(TimelineRows.currentTick(emptyList(), 0, 3))
    }

    @Test
    fun hasCompactionOnlyLooksAtThePartType() {
        assertTrue(TimelineRows.hasCompaction(message("m1", parts = listOf(compaction("c1")))))
        assertFalse(TimelineRows.hasCompaction(message("m1")))
    }
}
