package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.timeline.MessageNav
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * B-5: `message.previous` / `message.next` — the offset walk transcribed from
 * upstream's `navigateMessage` (`session/timeline/interaction.ts:133-149`).
 *
 * The row index map stands in for the timeline's `scrollToMessage` anchors, and
 * `firstVisibleIndex` for the scroll cursor the shell does not keep.
 */
class MessageNavTest {

    private fun user(id: String) = ChatStore.UiMessage(id = id, role = "user", timeCreated = 0, parts = emptyList())
    private fun assistant(id: String) =
        ChatStore.UiMessage(id = id, role = "assistant", timeCreated = 0, parts = emptyList())

    // u1 a1 u2 a2 u3 — rows are in message order.
    private val messages = listOf(
        user("u1"), assistant("a1"), user("u2"), assistant("a2"), user("u3"),
    )
    private val rows = mapOf("u1" to 0, "a1" to 1, "u2" to 2, "a2" to 3, "u3" to 4)

    @Test
    fun nextFromTheFirstUserMessageLandsOnTheSecond() {
        assertEquals(MessageNav.Target.Scroll(2), MessageNav.target(messages, rows, firstVisibleIndex = 0, offset = 1))
    }

    @Test
    fun previousFromTheSecondUserMessageLandsOnTheFirst() {
        assertEquals(MessageNav.Target.Scroll(0), MessageNav.target(messages, rows, firstVisibleIndex = 2, offset = -1))
    }

    @Test
    fun steppingPastTheLastMessageResumesTheLiveEnd() {
        // `target === messages.length` → upstream's `resume()` (unpin + scroll to
        // the end), not a scroll to a message that does not exist.
        assertEquals(
            MessageNav.Target.Resume,
            MessageNav.target(messages, rows, firstVisibleIndex = 4, offset = 1),
        )
        // …and one more step is outside 0..length, so it does nothing.
        assertNull(MessageNav.target(messages, rows, firstVisibleIndex = 4, offset = 2))
    }

    @Test
    fun steppingBeforeTheFirstMessageDoesNothing() {
        assertNull(MessageNav.target(messages, rows, firstVisibleIndex = 0, offset = -1))
    }

    @Test
    fun aTargetOutsideTheSnapshotWindowCannotScroll() {
        // The row map only covers what the timeline published; an id outside it
        // answers null instead of a wrong index.
        assertNull(
            MessageNav.target(
                messages,
                rows - "u2",
                firstVisibleIndex = 0,
                offset = 1,
            ),
        )
    }

    @Test
    fun anEmptyConversationOrAZeroOffsetDoesNothing() {
        assertNull(MessageNav.target(emptyList(), emptyMap(), firstVisibleIndex = 0, offset = 1))
        assertNull(MessageNav.target(messages, rows, firstVisibleIndex = 0, offset = 0))
        // Assistant-only history has no user row to walk.
        val assistantOnly = listOf(assistant("a1"))
        assertNull(MessageNav.target(assistantOnly, mapOf("a1" to 0), firstVisibleIndex = 0, offset = 1))
    }

    @Test
    fun theCursorIsTheLastUserMessageAtOrAboveTheViewportTop() {
        // With every row above the viewport top the cursor is the last user
        // message, so `previous` walks back one from there.
        assertEquals(
            MessageNav.Target.Scroll(2),
            MessageNav.target(messages, rows, firstVisibleIndex = 99, offset = -1),
        )
        // No known row at or above the top: upstream's `current` is undefined, so
        // `base` is `messages.length` and `-1` lands on the last user message.
        val shifted = mapOf("u1" to 5, "a1" to 6, "u2" to 7, "a2" to 8, "u3" to 9)
        assertEquals(
            MessageNav.Target.Scroll(9),
            MessageNav.target(messages, shifted, firstVisibleIndex = 0, offset = -1),
        )
    }
}
