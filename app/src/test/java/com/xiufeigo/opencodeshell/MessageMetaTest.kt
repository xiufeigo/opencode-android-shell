package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.timeline.MessageMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The message meta line (`_upstream/packages/session-ui/src/message/
 * message-content.tsx:500-521` assistant, `:227-231` user): every segment is
 * optional, and the duration only exists for a completed message.
 */
class MessageMetaTest {

    private fun message(
        role: String = "assistant",
        created: Long = 1_000L,
        completed: Long? = null,
        agent: String? = null,
        model: String? = null,
        interrupted: Boolean = false,
    ) = ChatStore.UiMessage(
        id = "m1",
        role = role,
        timeCreated = created,
        parts = emptyList(),
        agent = agent,
        model = model,
        timeCompleted = completed,
        interrupted = interrupted,
    )

    @Test
    fun assistantMetaJoinsEveryAvailableSegment() {
        val meta = MessageMeta.of(
            message(
                created = 1_000L,
                completed = 13_400L,
                agent = "build",
                model = "anthropic/claude-sonnet-4",
            ),
        )
        assertEquals(12_400L, meta.durationMs)
        assertEquals("Build", meta.agent)
        assertEquals("claude-sonnet-4", meta.model)
        assertEquals("Build · claude-sonnet-4 · 12s", meta.text)
        assertFalse(meta.isEmpty)
    }

    @Test
    fun interruptedIsTheLastSegment() {
        val meta = MessageMeta.of(message(created = 1_000L, completed = 5_000L, agent = "plan", interrupted = true))
        assertEquals("Plan · 4s · Interrupted", meta.text)
    }

    @Test
    fun missingSegmentsAreDropped() {
        val bare = MessageMeta.of(message())
        assertTrue(bare.isEmpty)
        assertEquals("", bare.text)
        // A message that never completed has no duration (`time.completed`
        // undefined upstream).
        val running = MessageMeta.of(message(completed = null, agent = "build"))
        assertNull(running.durationMs)
        assertEquals("Build", running.text)
    }

    @Test
    fun durationFormattingFollowsTheUpstreamKeys() {
        assertNull(MessageMeta.durationOf(null))
        assertEquals("0s", MessageMeta.durationOf(400L))
        assertEquals("12s", MessageMeta.durationOf(12_400L))
        assertEquals("59s", MessageMeta.durationOf(59_400L))
        assertEquals("1m 0s", MessageMeta.durationOf(60_000L))
        assertEquals("1m 5s", MessageMeta.durationOf(65_000L))
        assertEquals("2m 30s", MessageMeta.durationOf(150_000L))
    }

    @Test
    fun durationNeedsBothEndsOfTheSpan() {
        // A missing `time.created` (0) can never produce a sane span.
        assertNull(MessageMeta.durationOf(createdAt = 0L, completedAt = 10_000L))
        assertNull(MessageMeta.durationOf(createdAt = 5_000L, completedAt = null))
        assertNull(MessageMeta.durationOf(createdAt = 5_000L, completedAt = 5_000L))
        assertEquals(4_000L, MessageMeta.durationOf(createdAt = 1_000L, completedAt = 5_000L))
    }

    @Test
    fun userHeadJoinsAgentAndModelWithNonBreakingSeparators() {
        val meta = MessageMeta.of(message(role = "user", agent = "build", model = "openai/gpt-5"))
        assertEquals("Build\u00A0\u00B7\u00A0gpt-5", meta.head)
        // The user row always carries the time stamp, so it is never "empty".
        assertEquals(1_000L, meta.createdAt)
        assertEquals("", MessageMeta.of(message(role = "user")).head)
    }
}
