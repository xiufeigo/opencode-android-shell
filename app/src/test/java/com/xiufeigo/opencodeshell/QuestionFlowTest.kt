package com.xiufeigo.opencodeshell

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Question chain (`QuestionV1`, `research/04` §2/§3,
 * `_upstream/packages/schema/src/v1/question.ts`): SSE parse + store reduce +
 * REST wire. Store semantics mirror the permission chain: session-gated
 * upserts, removal by globally unique requestID, snapshot filtered to the
 * tracked session.
 */
class QuestionFlowTest {

    private fun request(
        id: String,
        sessionID: String = "ses_1",
        questions: List<OpenCodeClient.QuestionRequest.Info> = listOf(
            OpenCodeClient.QuestionRequest.Info(
                question = "Which file?",
                header = "File",
                options = listOf(
                    OpenCodeClient.QuestionRequest.Option("a.kt", "the first one"),
                    OpenCodeClient.QuestionRequest.Option("b.kt", "the second one"),
                ),
                multiple = false,
                custom = true,
            ),
        ),
    ) = OpenCodeClient.QuestionRequest(id = id, sessionID = sessionID, questions = questions, tool = null)

    // ------------------------------------------------------------------ parse

    @Test
    fun parseAskedReadsEnvelopeProperties() {
        val event = ChatStore.parseEvent(
            "question.asked",
            JSONObject(
                """{"type":"question.asked","properties":
                   {"id":"que_1","sessionID":"ses_1",
                    "questions":[
                      {"question":"Which file?","header":"File",
                       "options":[{"label":"a.kt","description":"the first one"}]},
                      {"question":"Which tests?","header":"Tests",
                       "options":[{"label":"unit","description":"fast"}],
                       "multiple":true,"custom":false}
                    ],
                    "tool":{"messageID":"msg_1","callID":"call_1"}}}""",
            ),
        ) as ChatStore.Event.QuestionAsked
        val req = event.request
        assertEquals("que_1", req.id)
        assertEquals("ses_1", req.sessionID)
        assertEquals(2, req.questions.size)
        val first = req.questions[0]
        assertEquals("Which file?", first.question)
        assertEquals("File", first.header)
        assertEquals(1, first.options.size)
        assertEquals("a.kt", first.options[0].label)
        assertEquals("the first one", first.options[0].description)
        assertFalse(first.multiple)
        // `custom` is optional on the wire and defaults to true.
        assertTrue(first.custom)
        val second = req.questions[1]
        assertTrue(second.multiple)
        assertFalse(second.custom)
        assertEquals("msg_1", req.tool?.messageID)
        assertEquals("call_1", req.tool?.callID)
    }

    @Test
    fun parseAskedToleratesFlatPayloadAndMissingFields() {
        val event = ChatStore.parseEvent(
            "question.asked",
            JSONObject("""{"type":"question.asked","id":"que_2","sessionID":"ses_1"}"""),
        ) as ChatStore.Event.QuestionAsked
        assertEquals("que_2", event.request.id)
        assertEquals(emptyList<OpenCodeClient.QuestionRequest.Info>(), event.request.questions)
        assertNull(event.request.tool)

        // An ask without a usable id can never be answered: drop, don't crash.
        assertNull(
            ChatStore.parseEvent(
                "question.asked",
                JSONObject("""{"type":"question.asked","properties":{"sessionID":"ses_1"}}"""),
            ),
        )
    }

    @Test
    fun parseRepliedAndRejectedReadRequestID() {
        val replied = ChatStore.parseEvent(
            "question.replied",
            JSONObject(
                """{"type":"question.replied","properties":
                   {"sessionID":"ses_1","requestID":"que_1","answers":[["a.kt"]]}}""",
            ),
        )
        // W5-E3: the answer matrix is kept alongside the removal.
        assertEquals(ChatStore.Event.QuestionResolved("que_1", listOf(listOf("a.kt"))), replied)

        val rejected = ChatStore.parseEvent(
            "question.rejected",
            JSONObject(
                """{"type":"question.rejected","properties":
                   {"sessionID":"ses_1","requestID":"que_2"}}""",
            ),
        )
        // `question.rejected` carries no answers at all — the dismissal flag is
        // what distinguishes it from "[[]]".
        assertEquals(ChatStore.Event.QuestionResolved("que_2", rejected = true), rejected)

        assertNull(
            ChatStore.parseEvent(
                "question.rejected",
                JSONObject("""{"type":"question.rejected","properties":{"sessionID":"ses_1"}}"""),
            ),
        )
    }

    // ----------------------------------------------------------------- reduce

    @Test
    fun askedAddsUpdatesAndKeepsArrivalOrder() {
        var s = ChatStore.reduce(
            ChatStore.ChatState(sessionID = "ses_1"),
            ChatStore.Event.QuestionAsked(request("que_1")),
        )
        s = ChatStore.reduce(s, ChatStore.Event.QuestionAsked(request("que_2")))
        assertEquals(listOf("que_1", "que_2"), s.pendingQuestions.map { it.id })

        // Re-asking the same id updates in place (no duplicate, no reorder).
        s = ChatStore.reduce(
            s,
            ChatStore.Event.QuestionAsked(
                request(
                    "que_1",
                    questions = listOf(
                        OpenCodeClient.QuestionRequest.Info(
                            question = "new", header = "h",
                            options = emptyList(), multiple = false, custom = true,
                        ),
                    ),
                ),
            ),
        )
        assertEquals(listOf("que_1", "que_2"), s.pendingQuestions.map { it.id })
        assertEquals("new", s.pendingQuestions.first { it.id == "que_1" }.questions[0].question)
    }

    @Test
    fun askedForOtherSessionIsIgnored() {
        val tracked = ChatStore.ChatState(sessionID = "ses_1")
        val s = ChatStore.reduce(
            tracked,
            ChatStore.Event.QuestionAsked(request("que_9", sessionID = "ses_other")),
        )
        assertEquals(tracked, s)
        assertTrue(s.pendingQuestions.isEmpty())

        // A state without a tracked session accepts everything (pre-session).
        val untracked = ChatStore.reduce(
            ChatStore.ChatState(),
            ChatStore.Event.QuestionAsked(request("que_9", sessionID = "ses_other")),
        )
        assertEquals(listOf("que_9"), untracked.pendingQuestions.map { it.id })
    }

    @Test
    fun resolvedRemovesByRequestIDOnly() {
        var s = ChatStore.ChatState(sessionID = "ses_1")
        s = ChatStore.reduce(s, ChatStore.Event.QuestionAsked(request("que_1")))
        s = ChatStore.reduce(s, ChatStore.Event.QuestionAsked(request("que_2")))
        s = ChatStore.reduce(s, ChatStore.Event.QuestionResolved("que_1"))
        assertEquals(listOf("que_2"), s.pendingQuestions.map { it.id })

        // Unknown / repeated resolutions are no-ops (idempotent).
        val after = ChatStore.reduce(s, ChatStore.Event.QuestionResolved("que_1"))
        assertEquals(s, after)
        assertEquals(s, ChatStore.reduce(s, ChatStore.Event.QuestionResolved("que_missing")))
    }

    @Test
    fun snapshotMergesOnlyTrackedSession() {
        var s = ChatStore.ChatState(sessionID = "ses_1")
        // A stale optimistic entry the server no longer reports disappears.
        s = ChatStore.reduce(s, ChatStore.Event.QuestionAsked(request("que_stale")))
        s = ChatStore.reduce(
            s,
            ChatStore.Event.QuestionSnapshot(
                listOf(
                    request("que_1"),
                    request("que_2", sessionID = "ses_other"),
                    request(""),
                ),
            ),
        )
        assertEquals(listOf("que_1"), s.pendingQuestions.map { it.id })
    }

    @Test
    fun askedCarriesSessionRoutingForTheGlobalGate() {
        // `eventSessionId` drives the global-stream gate; question events
        // carry `properties.sessionID` like permission events.
        val data = JSONObject(
            """{"type":"question.asked","properties":
               {"id":"que_1","sessionID":"ses_1","questions":[]}}""",
        )
        assertEquals("ses_1", ChatStore.eventSessionId(data))
        assertTrue(ChatStore.acceptsSession("ses_1", data))
        assertFalse(ChatStore.acceptsSession("ses_other", data))
    }

    // ------------------------------------------------------------------- json

    @Test
    fun optionWithoutLabelIsDroppedButQuestionSurvives() {
        val info = OpenCodeClient.QuestionRequest.Info.fromJson(
            JSONObject(
                """{"question":"q","header":"h",
                   "options":[{"label":"","description":"no"},{"label":"ok"}],
                   "multiple":true}""",
            ),
        )
        assertEquals(1, info.options.size)
        assertEquals("ok", info.options[0].label)
        assertEquals("", info.options[0].description)
        assertTrue(info.multiple)
        assertTrue(info.custom)
    }

    // ------------------------------------------------------------------- wire

    private fun client(server: MockWebServer) =
        OpenCodeClient(server.url("/").toString().toHttpUrlOrNull()!!, null, null)

    @Test
    fun listQuestionsGetsQuestionPathAndParsesArray() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """[{"id":"que_1","sessionID":"ses_1",
                        "questions":[{"question":"q","header":"h","options":[]}]},
                       {"sessionID":"ses_1","questions":[]}]""",
                ),
            )
            val c = client(server)
            val list = c.listQuestions("/w")
            // The id-less entry is dropped: it can never be replied to.
            assertEquals(1, list.size)
            assertEquals("que_1", list[0].id)
            assertEquals(1, list[0].questions.size)
            val recorded = server.takeRequest()
            assertEquals("GET", recorded.method)
            assertEquals("/question", recorded.requestUrl!!.encodedPath)
            assertEquals("/w", recorded.requestUrl!!.queryParameter("directory"))
            c.close()
        }
    }

    @Test
    fun replyQuestionPostsAlignedAnswersToReplyPath() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("true"))
            val c = client(server)
            c.replyQuestion("que_1", listOf(listOf("a.kt", "b.kt"), emptyList(), listOf("custom answer")), "/w")
            val recorded = server.takeRequest()
            assertEquals("POST", recorded.method)
            assertEquals("/question/que_1/reply", recorded.requestUrl!!.encodedPath)
            assertEquals("/w", recorded.requestUrl!!.queryParameter("directory"))
            val body = JSONObject(recorded.body.readUtf8())
            val answers = body.getJSONArray("answers")
            assertEquals(3, answers.length())
            assertEquals("a.kt", answers.getJSONArray(0).getString(0))
            assertEquals("b.kt", answers.getJSONArray(0).getString(1))
            assertEquals(0, answers.getJSONArray(1).length())
            assertEquals("custom answer", answers.getJSONArray(2).getString(0))
            c.close()
        }
    }

    @Test
    fun rejectQuestionPostsToRejectPath() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("true"))
            val c = client(server)
            c.rejectQuestion("que_1", "/w")
            val recorded = server.takeRequest()
            assertEquals("POST", recorded.method)
            assertEquals("/question/que_1/reject", recorded.requestUrl!!.encodedPath)
            assertEquals("/w", recorded.requestUrl!!.queryParameter("directory"))
            assertEquals(0L, recorded.body.size)
            c.close()
        }
    }
}
