package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.header.ContextUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Context-ring math (`research/02` §2.2 / `session-context-metrics.ts`):
 * `usage = limit ? clamp(round(total / limit * 100), 0, 100) : null`, and the
 * total comes from the **last assistant message whose token total is > 0**.
 * No >80 % colour branch exists upstream, so none is asserted here.
 */
class ContextUsageTest {

    private fun message(
        id: String,
        role: String,
        tokens: Long? = null,
    ) = ChatStore.UiMessage(id = id, role = role, timeCreated = 0L, parts = emptyList(), tokens = tokens)

    @Test
    fun percentIsRoundedAndClamped() {
        assertEquals(50, ContextUsage.percentOf(total = 100_000L, limit = 200_000L))
        assertEquals(33, ContextUsage.percentOf(total = 100L, limit = 300L))
        assertEquals(100, ContextUsage.percentOf(total = 500L, limit = 100L))
        assertEquals(0, ContextUsage.percentOf(total = 0L, limit = 100L))
        assertEquals(1, ContextUsage.percentOf(total = 1L, limit = 200L))
    }

    @Test
    fun unknownOrInvalidInputsYieldNull() {
        assertNull(ContextUsage.percentOf(total = null, limit = 100L))
        assertNull(ContextUsage.percentOf(total = 100L, limit = null))
        assertNull(ContextUsage.percentOf(total = 100L, limit = 0L))
        assertNull(ContextUsage.percentOf(total = 100L, limit = -5L))
        assertNull(ContextUsage.percentOf(total = -1L, limit = 100L))
    }

    @Test
    fun lastTokenTotalPicksTheNewestAssistantUsage() {
        val messages = listOf(
            message("m1", "user", tokens = null),
            message("m2", "assistant", tokens = 1_000L),
            message("m3", "user", tokens = null),
            message("m4", "assistant", tokens = 4_000L),
        )
        assertEquals(4_000L, ContextUsage.lastTokenTotal(messages))
    }

    @Test
    fun lastTokenTotalIgnoresUserMessagesAndEmptyUsage() {
        val messages = listOf(
            message("m1", "assistant", tokens = 2_500L),
            message("m2", "assistant", tokens = null),
            message("m3", "assistant", tokens = 0L),
            message("m4", "user", tokens = 9_999L),
        )
        assertEquals(2_500L, ContextUsage.lastTokenTotal(messages))
        assertNull(ContextUsage.lastTokenTotal(emptyList()))
        assertNull(ContextUsage.lastTokenTotal(listOf(message("m1", "assistant", tokens = 0L))))
    }

    @Test
    fun progressFallsBackToAnEmptyRing() {
        assertEquals(0f, ContextUsage.progress(null), 0f)
        assertEquals(0f, ContextUsage.progress(0), 0f)
        assertEquals(0.33f, ContextUsage.progress(33), 0.0001f)
        assertEquals(1f, ContextUsage.progress(100), 0f)
        assertEquals(1f, ContextUsage.progress(180), 0f)
        assertEquals(0f, ContextUsage.progress(-20), 0f)
    }

    @Test
    fun progressOfIsZeroWhileTheContextLimitIsUnknown() {
        val messages = listOf(message("m1", "assistant", tokens = 42_000L))
        assertEquals(0f, ContextUsage.progressOf(messages, limit = null), 0f)
        // …and jumps to the real fraction once the catalog supplies the limit.
        assertEquals(0.42f, ContextUsage.progressOf(messages, limit = 100_000L), 0.0001f)
    }
}
