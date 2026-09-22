package com.xiufeigo.opencodeshell

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W4-A data layer, store side: the `session.error` union, the
 * `session.status` retry detail, the part contract (`rawJson`,
 * `message.part.removed`) and the message meta fields.
 *
 * Anchors: `_upstream/packages/schema/src/v1/session.ts:385-395` (AssistantError
 * union), `_upstream/packages/schema/src/session-status-event.ts:9-32` (status
 * union), `_upstream/packages/session-ui/src/timeline/projection.ts:408-410`
 * (`isInterrupted`).
 */
class ChatStoreWave4EventTest {

    private fun part(id: String, text: String) = ChatStore.UiPart(id = id, type = "text", text = text)

    private fun message(id: String, time: Long, parts: List<ChatStore.UiPart>) =
        ChatStore.UiMessage(id = id, role = "assistant", timeCreated = time, parts = parts)

    private fun statusEvent(type: String, extra: String = ""): ChatStore.Event? = ChatStore.parseEvent(
        "session.status",
        JSONObject(
            """{"type":"session.status","properties":{"sessionID":"ses_1","status":{"type":"$type"$extra}}}""",
        ),
    )

    // ----------------------------------------------- 1. `session.error` union

    @Test
    fun sessionErrorObjectYieldsItsDataMessageNotTheRawJson() {
        val event = ChatStore.parseEvent(
            "session.error",
            JSONObject(
                """{"type":"session.error","properties":{"sessionID":"ses_1",
                   "error":{"name":"APIError","data":{"message":"Rate limit reached",
                   "statusCode":429,"isRetryable":true}}}}""",
            ),
        ) as ChatStore.Event.RunStatusChanged

        assertEquals(ChatStore.RunStatus.ERROR, event.status)
        // The whole point: a readable line, not `{"name":"APIError",…}`.
        assertEquals("Rate limit reached", event.error)
        assertFalse(event.error!!.startsWith("{"))
        // …and unwrapping it does not throw the provider detail away.
        val detail = event.errorDetail
        assertNotNull(detail)
        assertTrue(detail!!.contains("\"statusCode\":429"))
        assertTrue(detail.contains("\"name\":\"APIError\""))
    }

    @Test
    fun sessionErrorFallsBackToTheDiscriminatorName() {
        // `MessageOutputLengthError` has an empty `data` (`v1/session.ts:36`):
        // the name is the only thing left to show.
        val length = ChatStore.parseEvent(
            "session.error",
            JSONObject("""{"properties":{"error":{"name":"MessageOutputLengthError","data":{}}}}"""),
        ) as ChatStore.Event.RunStatusChanged
        assertEquals("MessageOutputLengthError", length.error)

        // Auth errors carry a provider-specific message.
        val auth = ChatStore.parseEvent(
            "session.error",
            JSONObject(
                """{"properties":{"error":{"name":"ProviderAuthError",
                   "data":{"providerID":"anthropic","message":"Invalid API key"}}}}""",
            ),
        ) as ChatStore.Event.RunStatusChanged
        assertEquals("Invalid API key", auth.error)
    }

    @Test
    fun sessionErrorToleratesPlainStringsAndMissingPayloads() {
        val plain = ChatStore.parseEvent(
            "session.error",
            JSONObject("""{"properties":{"error":"boom"}}"""),
        ) as ChatStore.Event.RunStatusChanged
        assertEquals("boom", plain.error)
        // A plain string has no JSON to preserve.
        assertNull(plain.errorDetail)

        val missing = ChatStore.parseEvent(
            "session.error",
            JSONObject("""{"properties":{"sessionID":"ses_1"}}"""),
        ) as ChatStore.Event.RunStatusChanged
        assertEquals(ChatStore.RunStatus.ERROR, missing.status)
        assertEquals("unknown error", missing.error)
        assertNull(missing.errorDetail)
    }

    @Test
    fun sessionErrorDetailReachesTheStoreAndIsClearedOnRecovery() {
        var state = ChatStore.reduce(
            ChatStore.ChatState(sessionID = "ses_1"),
            ChatStore.Event.RunStatusChanged(
                ChatStore.RunStatus.ERROR,
                "Rate limit reached",
                errorDetail = """{"name":"APIError"}""",
            ),
        )
        assertEquals("Rate limit reached", state.error)
        assertEquals("""{"name":"APIError"}""", state.errorDetail)

        state = ChatStore.reduce(state, ChatStore.Event.RunStatusChanged(ChatStore.RunStatus.RUNNING))
        assertEquals(ChatStore.RunStatus.RUNNING, state.runStatus)
        assertNull(state.error)
        assertNull("a fresh run must not carry the previous failure", state.errorDetail)
    }

    // ----------------------------------------------- 2. retry status detail

    @Test
    fun retryStatusCarriesAttemptNextMessageAndAction() {
        val event = ChatStore.parseEvent(
            "session.status",
            JSONObject(
                """{"type":"session.status","properties":{"sessionID":"ses_1","status":{
                   "type":"retry","attempt":3,"message":"Provider overloaded",
                   "next":1700000000000,
                   "action":{"reason":"free_tier_limit","provider":"opencode",
                   "title":"Free limit reached","message":"Usage limit reached",
                   "label":"Upgrade","link":"https://opencode.ai/go"}}}}""",
            ),
        ) as ChatStore.Event.RunStatusChanged

        assertEquals(ChatStore.RunStatus.RUNNING, event.status)
        // Not an error: `error != null` mounts the red card in the timeline.
        assertNull(event.error)

        val retry = event.retry!!
        assertEquals(3, retry.attempt)
        assertEquals(1_700_000_000_000L, retry.next)
        assertEquals("Provider overloaded", retry.message)
        assertEquals("free_tier_limit", retry.action?.reason)
        assertEquals("opencode", retry.action?.provider)
        assertEquals("Upgrade", retry.action?.label)
        assertEquals("https://opencode.ai/go", retry.action?.link)
    }

    @Test
    fun retryStatusWithoutDetailStillReportsRunning() {
        val event = statusEvent("retry") as ChatStore.Event.RunStatusChanged
        assertEquals(ChatStore.RunStatus.RUNNING, event.status)
        val retry = event.retry!!
        assertEquals(0, retry.attempt)
        assertEquals(0L, retry.next)
        assertNull(retry.message)
        assertNull(retry.action)
    }

    @Test
    fun retryIsClearedByBusyIdleAndError() {
        val retryState = ChatStore.reduce(
            ChatStore.ChatState(sessionID = "ses_1"),
            statusEvent("retry", ""","attempt":2,"message":"slow","next":99""")!!,
        )
        assertEquals(2, retryState.retry?.attempt)
        assertEquals(99L, retryState.retry?.next)

        // busy: still running, no countdown to show.
        val busy = ChatStore.reduce(retryState, statusEvent("busy")!!)
        assertEquals(ChatStore.RunStatus.RUNNING, busy.runStatus)
        assertNull(busy.retry)

        // idle: the turn finished.
        val idle = ChatStore.reduce(retryState, statusEvent("idle")!!)
        assertEquals(ChatStore.RunStatus.IDLE, idle.runStatus)
        assertNull(idle.retry)

        // A retry attached to a non-running status can never stick.
        val errored = ChatStore.reduce(
            ChatStore.ChatState(),
            ChatStore.Event.RunStatusChanged(
                ChatStore.RunStatus.ERROR,
                "boom",
                retry = ChatStore.RetryInfo(1, 2L, "m", null),
            ),
        )
        assertNull(errored.retry)
    }

    // ------------------------------------------- 6. part contract (rawJson)

    @Test
    fun rawJsonIsKeptForEveryTypeWithoutAFirstClassField() {
        for (type in ChatStore.UiPart.RAW_JSON_TYPES) {
            val parsed = ChatStore.parsePart(
                JSONObject("""{"id":"prt_1","type":"$type","marker":"kept-$type"}"""),
            )
            assertEquals(type, parsed.type)
            assertNotNull("$type must keep its payload", parsed.rawJson)
            assertTrue(parsed.rawJson!!.contains("marker"))
        }
        // The set itself is the contract: seven unmodelled part types
        // (`v1/session.ts:357-383`), no more, no less.
        assertEquals(
            setOf("subtask", "step-start", "step-finish", "snapshot", "patch", "agent", "retry"),
            ChatStore.UiPart.RAW_JSON_TYPES,
        )
    }

    @Test
    fun rawJsonIsDroppedForModelledTypes() {
        for (type in listOf("text", "reasoning", "tool", "file", "compaction", "unknown-type")) {
            assertNull(
                "$type has first-class fields; it must not duplicate its payload",
                ChatStore.parsePart(JSONObject("""{"id":"prt_1","type":"$type","text":"x"}""")).rawJson,
            )
        }
    }

    @Test
    fun rawJsonSurvivesTheStreamedPartEvent() {
        val event = ChatStore.parseEvent(
            "message.part.updated",
            JSONObject(
                """{"type":"message.part.updated","properties":{"sessionID":"ses_1",
                   "messageID":"msg_1","part":{"id":"prt_9","type":"snapshot","snapshot":"deadbeef"}}}""",
            ),
        ) as ChatStore.Event.PartUpdated
        assertEquals("prt_9", event.part.id)
        assertTrue(event.part.rawJson!!.contains("deadbeef"))
    }

    // ------------------------------------ 6. `message.part.removed` contract

    @Test
    fun partRemovedParsesAndDropsIncompleteFrames() {
        val event = ChatStore.parseEvent(
            "message.part.removed",
            JSONObject(
                """{"type":"message.part.removed","properties":
                   {"sessionID":"ses_1","messageID":"msg_1","partID":"prt_1"}}""",
            ),
        )
        assertEquals(ChatStore.Event.PartRemoved("msg_1", "prt_1"), event)

        // Both ids are required (`v1/session.ts:621-629`): without them there is
        // nothing to remove, so no event may be produced.
        assertNull(
            ChatStore.parseEvent(
                "message.part.removed",
                JSONObject("""{"properties":{"partID":"prt_1"}}"""),
            ),
        )
        assertNull(
            ChatStore.parseEvent(
                "message.part.removed",
                JSONObject("""{"properties":{"messageID":"msg_1"}}"""),
            ),
        )
        assertNull(ChatStore.parseEvent("message.part.removed", JSONObject("""{"properties":{}}""")))
    }

    @Test
    fun partRemovedDeletesThePartAndDropsAnEmptiedMessage() {
        var state = ChatStore.ChatState(
            sessionID = "ses_1",
            messages = listOf(
                message("msg_1", 1, listOf(part("p1", "a"), part("p2", "b"))),
                message("msg_2", 2, listOf(part("p3", "c"))),
            ),
        )

        state = ChatStore.reduce(state, ChatStore.Event.PartRemoved("msg_1", "p1"))
        assertEquals(listOf("p2"), state.messages.single { it.id == "msg_1" }.parts.map { it.id })

        // The message's last part went with it → the message goes too.
        state = ChatStore.reduce(state, ChatStore.Event.PartRemoved("msg_1", "p2"))
        assertEquals(listOf("msg_2"), state.messages.map { it.id })

        // Unknown part / unknown message are no-ops, not crashes.
        val before = state
        assertEquals(before, ChatStore.reduce(state, ChatStore.Event.PartRemoved("msg_2", "nope")))
        assertEquals(before, ChatStore.reduce(state, ChatStore.Event.PartRemoved("msg_none", "p3")))
    }

    // -------------------------------------------- 6. session id resolution

    @Test
    fun sessionIdOfReadsEveryRoutingShape() {
        assertEquals(
            "ses_a",
            ChatStore.sessionIdOf(JSONObject("""{"properties":{"sessionID":"ses_a"}}""")),
        )
        assertEquals(
            "ses_b",
            ChatStore.sessionIdOf(JSONObject("""{"properties":{"info":{"sessionID":"ses_b"}}}""")),
        )
        assertEquals(
            "ses_c",
            ChatStore.sessionIdOf(JSONObject("""{"properties":{"info":{"id":"ses_c"}}}""")),
        )
        assertEquals("ses_d", ChatStore.sessionIdOf(JSONObject("""{"sessionID":"ses_d"}""")))
        assertNull(ChatStore.sessionIdOf(JSONObject("""{"properties":{"info":{}}}""")))
    }

    // ------------------------------------ W4-B request: message meta fields

    @Test
    fun parseMessageReadsTheMetaLineFields() {
        val assistant = ChatStore.parseMessage(
            JSONObject(
                """{"info":{"id":"m1","role":"assistant","agent":"build","providerID":"openai",
                   "modelID":"gpt-5","time":{"created":10,"completed":20}},"parts":[]}""",
            ),
        )!!
        assertEquals("build", assistant.agent)
        assertEquals("openai/gpt-5", assistant.model)
        assertEquals(10L, assistant.timeCreated)
        assertEquals(20L, assistant.timeCompleted)
        assertFalse(assistant.interrupted)

        // A running turn has no `time.completed` → no duration on the meta line.
        val running = ChatStore.parseMessage(
            JSONObject("""{"info":{"id":"m2","role":"assistant","time":{"created":1}},"parts":[]}"""),
        )!!
        assertNull(running.timeCompleted)

        // User messages nest the model (`v1/session.ts:347-351`).
        val user = ChatStore.parseMessage(
            JSONObject(
                """{"info":{"id":"m3","role":"user","agent":"build",
                   "model":{"providerID":"anthropic","modelID":"claude-4"}},"parts":[]}""",
            ),
        )!!
        assertEquals("anthropic/claude-4", user.model)
        assertEquals("build", user.agent)

        // `isInterrupted`: abort/interrupt discriminators only.
        val aborted = ChatStore.parseMessage(
            JSONObject(
                """{"info":{"id":"m4","role":"assistant",
                   "error":{"name":"MessageAbortedError","data":{"message":"Stopped"}},
                   "time":{"created":1}},"parts":[]}""",
            ),
        )!!
        assertTrue(aborted.interrupted)
        val apiError = ChatStore.parseMessage(
            JSONObject(
                """{"info":{"id":"m5","role":"assistant",
                   "error":{"name":"APIError","data":{"message":"boom"}},"time":{"created":1}},"parts":[]}""",
            ),
        )!!
        assertFalse(apiError.interrupted)
        assertFalse(ChatStore.isInterrupted(null))
    }
}
