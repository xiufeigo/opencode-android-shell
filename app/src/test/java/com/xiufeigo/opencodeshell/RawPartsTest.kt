package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.timeline.RawParts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Readers for the part types that only exist as `UiPart.rawJson`
 * (`_upstream/packages/schema/src/v1/session.ts:87-257`). They must be tolerant:
 * the payload is server-owned, so a missing / malformed field yields `null` (or
 * an empty list) and never throws.
 */
class RawPartsTest {

    @Test
    fun patchReadsFilesAndHash() {
        val patch = RawParts.patch("""{"hash":"abc123","files":["a.kt","src/b.kt"]}""")
        assertEquals("abc123", patch?.hash)
        assertEquals(listOf("a.kt", "src/b.kt"), patch?.files)
    }

    @Test
    fun patchToleratesMissingFieldsAndBadJson() {
        assertEquals(emptyList<String>(), RawParts.patch("""{"hash":"h"}""")?.files)
        assertNull(RawParts.patch("""{"files":["a"]}""")?.hash)
        assertNull(RawParts.patch("not json"))
        assertNull(RawParts.patch(null))
    }

    @Test
    fun subtaskReadsTheDelegationRecord() {
        val subtask = RawParts.subtask(
            """{"agent":"build","description":"Fix the build","prompt":"do it","model":{"providerID":"anthropic","modelID":"claude"}}""",
        )
        assertEquals("build", subtask?.agent)
        assertEquals("Fix the build", subtask?.description)
        assertEquals("do it", subtask?.prompt)
        assertEquals("claude", subtask?.model)
        assertNull(RawParts.subtask("""{"agent":"build"}""")?.description)
    }

    @Test
    fun agentReadsTheName() {
        assertEquals("explore", RawParts.agent("""{"name":"explore"}""")?.name)
        // A nameless payload has nothing to announce → the row is skipped.
        assertNull(RawParts.agent("""{}"""))
        assertNull(RawParts.agent("""{"name":""}"""))
        assertNull(RawParts.agent(null))
    }

    @Test
    fun retryReadsAttemptAndTheNestedApiErrorMessage() {
        val retry = RawParts.retry(
            """{"attempt":2,"error":{"name":"APIError","data":{"message":"rate limited","isRetryable":true}},"time":{"created":1}}""",
        )
        assertEquals(2, retry?.attempt)
        assertEquals("APIError", retry?.errorName)
        assertEquals("rate limited", retry?.message)

        // A flat error string is accepted too (`{ name, message }`).
        assertEquals(
            "boom",
            RawParts.retry("""{"attempt":1,"error":{"message":"boom"}}""")?.message,
        )
        assertEquals(0, RawParts.retry("""{"error":{"data":{"message":"x"}}}""")?.attempt)
    }

    @Test
    fun retryMessageTruncatesAt80Chars() {
        assertEquals("short", RawParts.retryMessage("short"))
        assertNull(RawParts.retryMessage("   "))
        assertNull(RawParts.retryMessage(null))
        val long = "x".repeat(120)
        val truncated = RawParts.retryMessage(long)
        assertEquals(RawParts.RETRY_MESSAGE_MAX + 1, truncated?.length)
        assertTrue(truncated!!.endsWith("…"))
    }

    @Test
    fun stepFinishReadsUsage() {
        val step = RawParts.stepFinish(
            """{"reason":"stop","cost":0.25,"tokens":{"input":100,"output":50,"reasoning":10,
               "cache":{"read":1,"write":2},"total":160}}""".trimIndent(),
        )
        assertEquals("stop", step?.reason)
        assertEquals(0.25, step?.cost!!, 0.0001)
        assertEquals(160L, step?.tokens)

        // Without `total`, input + output is the fallback.
        val fallback = RawParts.stepFinish("""{"reason":"stop","cost":0.1,"tokens":{"input":100,"output":50}}""")
        assertEquals(150L, fallback?.tokens)
        assertNull(RawParts.stepFinish("not json"))
    }

    @Test
    fun capitalizeAndBasenameMatchTheUpstreamHelpers() {
        assertEquals("Build", RawParts.capitalize("build"))
        assertEquals("Explore", RawParts.capitalize("explore"))
        assertNull(RawParts.capitalize(""))
        assertNull(RawParts.capitalize(null))
        assertEquals("b.kt", RawParts.basename("src/main/b.kt"))
        assertEquals("b.kt", RawParts.basename("src\\main\\b.kt"))
        assertEquals("b.kt", RawParts.basename("b.kt"))
    }
}
