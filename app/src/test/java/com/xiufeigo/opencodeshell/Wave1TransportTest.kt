package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.v2.RenameResult
import com.xiufeigo.opencodeshell.v2.ServerGeneration
import com.xiufeigo.opencodeshell.v2.V2Api
import com.xiufeigo.opencodeshell.v2.V2Capability
import com.xiufeigo.opencodeshell.v2.V2EventAdapter
import com.xiufeigo.opencodeshell.v2.V2SeqTracker
import com.xiufeigo.opencodeshell.v2.V2Transport
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/**
 * Wave 1 dual-protocol transport tests. No test touches a real server:
 * request shapes go through [MockWebServer], event frames through recorded
 * live JSON ([V2ApiFixtures.SSE_V2_TURN]), and the generation rule is pure
 * ([V2Capability.decideInfo]).
 *
 * Existing `ChatStore`/`OpenCodeClient` tests stay green untouched — v1
 * parity is asserted here only as "same bytes as before" on the seam.
 */
class Wave1TransportTest {

    private fun RecordedRequest.json(): JSONObject = JSONObject(body.readUtf8())

    private fun v2api(server: MockWebServer, directory: String? = null) = V2Api(
        baseHttpUrl = server.url("/").toString().toHttpUrlOrNull()!!,
        username = "opencode",
        password = "opencode",
        http = V2Api.defaultClient(),
        directory = directory,
    )

    // ------------------------------------------------------------ probe

    @Test
    fun probeHitsInfoAndThenLocation() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(V2ApiFixtures.INFO_V2))
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"directory":"/w","project":{"id":"g","directory":"/w"}}"""))
            val result = V2Capability.probe(
                server.url("/").toString().toHttpUrlOrNull()!!,
                username = "opencode",
                password = "opencode",
                http = V2Api.defaultClient(),
            )
            assertEquals(ServerGeneration.V2, result.generation)
            assertEquals("/api/info", server.takeRequest().requestUrl!!.encodedPath)
            assertEquals("/api/location", server.takeRequest().requestUrl!!.encodedPath)
            assertEquals(0, server.requestCount - 2)
        }
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "text/html").setBody(V2ApiFixtures.SPA_HTML))
            val result = V2Capability.probe(
                server.url("/").toString().toHttpUrlOrNull()!!,
                http = V2Api.defaultClient(),
            )
            assertEquals(ServerGeneration.UNKNOWN, result.generation)
            assertEquals("/api/info", server.takeRequest().requestUrl!!.encodedPath)
        }
    }

    // ------------------------------------------------------------ V2 shapes

    @Test
    fun v2PermissionReplyUsesDecisionNotReply() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            v2api(server).replyPermission("ses_1", "per_1", "once")
            val request = server.takeRequest()
            assertEquals("/api/session/ses_1/permission/per_1/reply", request.requestUrl!!.encodedPath)
            val body = request.json()
            assertEquals("once", body.getString("decision"))
            assertFalse(body.has("reply"))
        }
    }

    @Test
    fun v2ForkUsesBeforeNotMessageId() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"data":{"id":"ses_new","projectID":"p","cost":0,"tokens":{"input":0,"output":0,"reasoning":0,"cache":{"read":0,"write":0}},"time":{"created":1,"updated":1},"title":"t","location":{"directory":"/w"}}}""",
                ),
            )
            val forked = v2api(server).fork("ses_1", before = "msg_9")
            val body = server.takeRequest().json()
            assertEquals("msg_9", body.getString("before"))
            assertFalse(body.has("messageID"))
            assertEquals("ses_new", forked.id)
        }
    }

    @Test
    fun v2ShellCommandAndInterruptShapes() {
        MockWebServer().use { server ->
            data class Seen(val path: String?, val query: String?, val body: String)
            val seen = java.util.concurrent.CopyOnWriteArrayList<Seen>()
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    seen.add(
                        Seen(
                            request.requestUrl!!.encodedPath,
                            request.requestUrl!!.queryParameter("resume"),
                            request.body.readUtf8(),
                        ),
                    )
                    return when {
                        request.path!!.contains("/interrupt") ->
                            MockResponse().setResponseCode(200).setBody("""{"interrupted":false}""")
                        else -> MockResponse().setResponseCode(204)
                    }
                }
            }
            server.start()
            val api = v2api(server)
            api.shell("ses_1", "echo hi")
            api.command("ses_1", "init", "x")
            // Interrupt answers 200 + {interrupted} (measured {"interrupted":false} on idle).
            assertFalse(api.interrupt("ses_1", resume = true))
            server.shutdown()

            assertEquals(3, seen.size)
            assertEquals("/api/session/ses_1/shell", seen[0].path)
            val shellBody = JSONObject(seen[0].body)
            assertEquals("echo hi", shellBody.getString("command"))
            assertFalse(shellBody.has("agent"))

            val commandBody = JSONObject(seen[1].body)
            assertEquals("init", commandBody.getString("name"))
            assertEquals("x", commandBody.getString("text"))

            assertEquals("/api/session/ses_1/interrupt", seen[2].path)
            assertEquals("true", seen[2].query)
        }
    }

    @Test
    fun v2CatalogsUseLocationDirectoryAndSessionListUsesBareDirectory() {
        MockWebServer().use { server ->
            repeat(3) {
                server.enqueue(MockResponse().setResponseCode(200).setBody(V2ApiFixtures.REFERENCES_EMPTY))
            }
            server.enqueue(MockResponse().setResponseCode(200).setBody(V2ApiFixtures.SESSION_PAGE))
            val api = v2api(server, directory = "/w")
            api.agents()
            assertEquals("/w", server.takeRequest().requestUrl!!.queryParameter("location[directory]"))
            api.models()
            assertEquals("/w", server.takeRequest().requestUrl!!.queryParameter("location[directory]"))
            api.defaultModel()
            assertEquals("/w", server.takeRequest().requestUrl!!.queryParameter("location[directory]"))
            api.sessions()
            val list = server.takeRequest()
            assertEquals("/w", list.requestUrl!!.queryParameter("directory"))
            assertNull(list.requestUrl!!.queryParameter("location[directory]"))
        }
    }

    @Test
    fun v2EnvelopeRulesBareArraysVsData() {
        MockWebServer().use { server ->
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path == "/api/project" ->
                        MockResponse().setResponseCode(200).setBody(V2ApiFixtures.PROJECTS_BARE)
                    request.path == "/api/config" ->
                        MockResponse().setResponseCode(200).setBody(V2ApiFixtures.CONFIGS_BARE)
                    request.path!!.startsWith("/api/session/ses_1/diff") ->
                        MockResponse().setResponseCode(200).setBody(V2ApiFixtures.SESSION_DIFF_EMPTY)
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
            val api = v2api(server)
            assertEquals("/home/xiufeigo", api.projects().single().canonical)

            val config = api.configs().single()
            assertEquals("opencode/deepseek-v4.1-flash", config.info.getString("model"))

            assertTrue(api.sessionDiff("ses_1").isEmpty())
            // from/to/context params (no messageID).
            api.sessionDiff("ses_1", from = "msg_a", to = "msg_b")
            // The dispatcher saw both diff calls; assert the query form here.
            val seen = mutableListOf<String?>()
            repeat(4) { seen.add(server.takeRequest().path) }
            val diffQuery = seen.first { it!!.startsWith("/api/session/ses_1/diff?") }!!
            assertTrue(diffQuery, diffQuery.contains("from=msg_a"))
            assertTrue(diffQuery, diffQuery.contains("to=msg_b"))
            assertFalse(diffQuery, diffQuery.contains("messageID"))
            server.shutdown()
        }
    }

    @Test
    fun v2FormCrudShapes() {
        MockWebServer().use { server ->
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path == "/api/session/ses_1/form" && request.method == "GET" ->
                        MockResponse().setResponseCode(200).setBody(V2ApiFixtures.SESSION_FORMS_ONE)
                    request.path == "/api/session/ses_1/form" && request.method == "POST" ->
                        MockResponse().setResponseCode(200).setBody(
                            """{"data":{"id":"frm_1","sessionID":"ses_1","title":"t","fields":[]}}""",
                        )
                    request.path == "/api/session/ses_1/form/frm_1/reply" ->
                        MockResponse().setResponseCode(204)
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
            val api = v2api(server)
            val forms = api.sessionForms("ses_1")
            assertEquals("frm_0c38a488e001UE9X8YyPgvMfb0", forms.single().id)

            api.createForm("ses_1", "t", org.json.JSONArray("[]"))
            api.replyForm("ses_1", "frm_1", mapOf("q" to "yes"))
            val paths = mutableListOf<String?>()
            repeat(3) { paths.add(server.takeRequest().path) }
            assertEquals("/api/session/ses_1/form", paths[0])
            assertEquals("/api/session/ses_1/form", paths[1])
            assertEquals("/api/session/ses_1/form/frm_1/reply", paths[2])
            server.shutdown()
        }
    }

    @Test
    fun v2FormCreateAndReplyBodies() {
        MockWebServer().use { server ->
            val seenBodies = java.util.concurrent.CopyOnWriteArrayList<String>()
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    seenBodies.add(request.body.readUtf8())
                    return when {
                        request.path == "/api/session/ses_1/form" ->
                            MockResponse().setResponseCode(200).setBody(
                                """{"data":{"id":"frm_1","sessionID":"ses_1","title":"t","fields":[]}}""",
                            )
                        else -> MockResponse().setResponseCode(204)
                    }
                }
            }
            server.start()
            val api = v2api(server)
            api.createForm("ses_1", "t", org.json.JSONArray("""[{"key":"q","type":"string"}]"""))
            api.replyForm("ses_1", "frm_1", mapOf("q" to "yes"))
            server.shutdown()
            assertEquals(2, seenBodies.size)
            assertEquals("t", JSONObject(seenBodies[0]).getString("title"))
            assertEquals("yes", JSONObject(seenBodies[1]).getJSONObject("answer").getString("q"))
        }
    }

    // ------------------------------------------------------------ adapter

    @Test
    fun v2TurnFramesFoldIntoStoreEvents() {
        val events = ArrayList<V2Api.DurableEvent>()
        V2Api.readEventStream(sseResponse(V2ApiFixtures.SSE_V2_TURN), events::add)
        // 9 data frames + log.synced watermark (heartbeats yield nothing).
        assertEquals(10, events.size)

        var state = ChatStore.ChatState(sessionID = "ses_1")
        for (frame in events) {
            if (frame.type == "log.synced") continue
            for (event in V2EventAdapter.adapt(frame.type, frame.raw)) {
                state = ChatStore.reduce(state, event)
            }
        }
        // text.started pre-creates the slot, delta appends, ended covers.
        val assistant = state.messages.single { it.id == "msg_a1" }
        assertEquals("YO", assistant.parts.single { it.type == "text" }.text)
        // execution.succeeded settles the run.
        assertEquals(ChatStore.RunStatus.IDLE, state.runStatus)
    }

    @Test
    fun v2EphemeralDeltasNeverTouchTheSeqWatermark() {
        val tracker = V2SeqTracker()
        val events = ArrayList<V2Api.DurableEvent>()
        V2Api.readEventStream(sseResponse(V2ApiFixtures.SSE_V2_TURN), events::add)
        for (frame in events) {
            if (frame.type == "log.synced") {
                tracker.onSynced(frame.seq)
                continue
            }
            tracker.observe(frame.id, frame.seq)
        }
        // Durable seqs 1..10 seen; delta/usage frames carry no seq.
        assertEquals(10L, tracker.maxSeq)
        assertEquals(10L, tracker.afterParam())
        assertEquals(10L, tracker.syncedSeq)
        // Replays dedupe by evt_ id.
        assertFalse(tracker.observe(events[0].id, events[0].seq))
    }

    @Test
    fun v2SeqGapMath() {
        val tracker = V2SeqTracker()
        assertEquals(5L..10L, tracker.gapBelowWatermark(4L, 10L))
        assertNull(tracker.gapBelowWatermark(10L, 10L))
        assertNull(tracker.gapBelowWatermark(11L, 10L))
        assertNull(tracker.gapBelowWatermark(4L, null))
        assertEquals(0L..0L, tracker.gapBelowWatermark(null, 0L))
    }

    @Test
    fun v2AForeignAggregateNeverMovesTheSessionWatermark() {
        val tracker = V2SeqTracker()
        tracker.onSynced(2L)
        // The global `/api/event` stream carries every session and each session
        // counts its own `durable.seq` (research/dupcap: two sessions at seq 15
        // in the same second). A foreign frame's id is remembered — the same
        // event can arrive on this session's log — but its seq may not anchor
        // `?after=`, or the server skips every durable frame this session missed
        // while the stream was down (the turn-complete notification's own
        // recovery path).
        assertTrue(tracker.observe("evt_other", 149L, aggregate = false))
        assertEquals(2L, tracker.maxSeq)
        assertEquals(2L, tracker.afterParam())
        assertFalse("a foreign replay still de-duplicates", tracker.observe("evt_other", 149L, aggregate = false))

        // The tracked aggregate's own frames move it, as before.
        assertTrue(tracker.observe("evt_own", 3L, aggregate = true))
        assertEquals(3L, tracker.maxSeq)
        assertEquals(3L, tracker.afterParam())
    }

    @Test
    fun v2SeenIDsAgeOutBelowTheWatermarkWindow() {
        val tracker = V2SeqTracker()
        for (i in 1..3000) tracker.observe("evt_$i", i.toLong())
        assertEquals(3000L, tracker.maxSeq)
        // Recent ids still dedupe; ids ≤ maxSeq − 1024 can never replay.
        assertFalse(tracker.observe("evt_3000", 3000L))
        assertFalse(tracker.observe("evt_2000", 2000L))
        assertTrue(tracker.observe("evt_1976", 1976L))
        assertTrue(tracker.observe("evt_1", 1L))
        assertEquals("an aged-out replay must not rewind the watermark", 3000L, tracker.maxSeq)
    }

    @Test
    fun v2SeqlessFramesAreHardCapped() {
        val tracker = V2SeqTracker()
        tracker.onSynced(0L)
        for (i in 1..5000) tracker.observe("evt_d_$i", null)
        assertTrue(tracker.observe("evt_d_1", null))
        assertFalse(tracker.observe("evt_d_5000", null))
        assertEquals(0L, tracker.maxSeq)
    }

    @Test
    fun v2StepStartedKeepsOneMarkerPerStep() {
        var state = ChatStore.ChatState()
        for (started in listOf(100L, 200L, 300L)) {
            val envelope = JSONObject(
                """{"id":"evt_step_$started","type":"session.step.started",
                   "data":{"assistantMessageID":"msg_a1","agent":"build","model":"m","started":$started}}""",
            )
            for (event in V2EventAdapter.adapt("session.step.started", envelope)) {
                state = ChatStore.reduce(state, event)
            }
        }
        val markers = state.messages.single().parts.filter { it.type == "step-start" }
        assertEquals(3, markers.size)
        assertEquals(ChatStore.RunStatus.RUNNING, state.runStatus)
    }

    @Test
    fun v2ToolFramesKeepTheNameTheStartedFrameSet() {
        var state = ChatStore.ChatState()
        val frames = listOf(
            "session.tool.input.started" to """{"assistantMessageID":"msg_a1","id":"call_1","name":"shell"}""",
            "session.tool.input.ended" to """{"assistantMessageID":"msg_a1","id":"call_1","text":"ls"}""",
            "session.tool.called" to """{"assistantMessageID":"msg_a1","id":"call_1","input":{"command":"ls"}}""",
            "session.tool.success" to """{"assistantMessageID":"msg_a1","id":"call_1","content":[{"type":"text","text":"files"}]}""",
        )
        for ((type, data) in frames) {
            val envelope = JSONObject("""{"id":"evt_$type","type":"$type","data":$data}""")
            for (event in V2EventAdapter.adapt(type, envelope)) {
                state = ChatStore.reduce(state, event)
            }
        }
        val tool = state.messages.single().parts.single()
        assertEquals("shell", tool.toolName)
        assertEquals("completed", tool.toolStatus)
        assertEquals("files", tool.toolOutput)
    }

    @Test
    fun v2PermissionFieldsRenameToV1DockRow() {
        val parsed = V2EventAdapter.v2Permission(
            JSONObject(
                """{"id":"per_1","sessionID":"ses_1","action":"bash","resources":["rm *"],"save":["rm *"],"source":{"type":"tool","messageID":"msg_1","id":"call_1"}}""",
            ),
        )!!
        assertEquals("bash", parsed.permission)
        assertEquals(listOf("rm *"), parsed.patterns)
        assertEquals("msg_1", parsed.tool!!.messageID)
        assertEquals("call_1", parsed.tool!!.callID)

        val asked = V2EventAdapter.adapt(
            "permission.asked",
            JSONObject("""{"id":"e1","type":"permission.asked","data":{"id":"per_1","sessionID":"ses_1","action":"bash","resources":["rm *"],"save":["rm *"],"source":{"type":"tool","messageID":"msg_1","id":"call_1"}}}"""),
        )
        assertEquals(listOf(ChatStore.Event.PermissionAsked(parsed)), asked)
    }

    @Test
    fun v2FormEventsDriveTheFormDock() {
        val created = V2EventAdapter.adapt(
            "form.created",
            JSONObject(
                """{"id":"e1","type":"form.created","data":{"form":{"id":"frm_1","sessionID":"ses_1","title":"t","fields":[{"key":"q","type":"string"}]}}}""",
            ),
        )
        assertEquals(1, created.size)
        val ask = (created.single() as ChatStore.Event.FormCreated).form
        assertEquals("frm_1", ask.id)

        var state = ChatStore.reduce(ChatStore.ChatState(sessionID = "ses_1"), created.single())
        assertEquals(listOf("frm_1"), state.pendingForms.map { it.id })
        state = ChatStore.reduce(state, ChatStore.Event.FormResolved("frm_1"))
        assertTrue(state.pendingForms.isEmpty())
        // A "global" form never enters a session-scoped list.
        val global = ChatStore.Event.FormCreated(ChatStore.FormAsk("frm_g", "global", "g"))
        assertEquals(state, ChatStore.reduce(state, global))
    }

    @Test
    fun v2TransportParsesFramesAndRoutesSessions() {
        MockWebServer().use { server ->
            val transport = V2Transport(v2api(server))
            val events = transport.parseFrame(
                "session.execution.succeeded",
                JSONObject("""{"id":"e1","type":"session.execution.succeeded","data":{"sessionID":"ses_1"}}"""),
            )
            assertEquals(listOf(ChatStore.Event.RunStatusChanged(ChatStore.RunStatus.IDLE)), events)
            assertEquals(
                "ses_1",
                transport.frameSessionID(JSONObject("""{"data":{"sessionID":"ses_1"}}""")),
            )
            assertEquals(ServerGeneration.V2, transport.generation)
        }
    }

    @Test
    fun v2EventStreamRequestsCoverGlobalAndLog() {
        MockWebServer().use { server ->
            val transport = V2Transport(v2api(server))
            val requests = transport.streamRequests("ses_1", afterSeq = 10L)
            assertEquals(2, requests.size)
            assertEquals("/api/event", requests[0].url.encodedPath)
            assertEquals("/api/experimental/session/ses_1/log", requests[1].url.encodedPath)
            assertEquals("10", requests[1].url.queryParameter("after"))
            assertEquals("true", requests[1].url.queryParameter("follow"))
            // No session → global only.
            assertEquals(1, transport.streamRequests(null, null).size)
        }
    }

    /**
     * Regression pin (2026-09-22, 2.0.11 measured live): the message
     * snapshot's `content[]` entries carry NO `id` for text/reasoning, so a
     * snapshot folded onto stream-built state must reproduce the adapter's
     * synthetic ids (`msg_…:text:0`) — otherwise `mergeParts` keeps both
     * copies and every snapshot duplicates the transcript on screen.
     */
    @Test
    fun v2SnapshotFoldsOntoLiveStreamPartsWithoutDuplicating() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """{"data":[{"id":"msg_a1","type":"assistant","time":{"created":100,"completed":200},"content":[""" +
                            """{"type":"reasoning","text":"thinking"},""" +
                            """{"type":"text","text":"the sky is blue."},""" +
                            """{"type":"tool","id":"call_1","name":"shell","state":{"status":"completed","input":{"command":"ls"},"content":[{"type":"text","text":"files"}]}}""" +
                            """]}]}""",
                    ),
            )
            val transport = V2Transport(v2api(server))
            val snapshot = transport.snapshotMessages("ses_1", 50)
            assertEquals(1, snapshot.size)

            // Stream-side state for the same turn: slots + delta + full cover.
            var state = ChatStore.ChatState(sessionID = "ses_1")
            val frames = listOf(
                "session.reasoning.started" to """{"sessionID":"ses_1","assistantMessageID":"msg_a1","ordinal":0}""",
                "session.reasoning.delta" to """{"sessionID":"ses_1","assistantMessageID":"msg_a1","ordinal":0,"delta":"thinking"}""",
                "session.text.started" to """{"sessionID":"ses_1","assistantMessageID":"msg_a1","ordinal":0}""",
                "session.text.delta" to """{"sessionID":"ses_1","assistantMessageID":"msg_a1","ordinal":0,"delta":"the sky is blue."}""",
                "session.text.ended" to """{"sessionID":"ses_1","assistantMessageID":"msg_a1","ordinal":0,"text":"the sky is blue."}""",
                "session.tool.input.started" to """{"sessionID":"ses_1","assistantMessageID":"msg_a1","id":"call_1","name":"shell"}""",
                "session.tool.success" to """{"sessionID":"ses_1","assistantMessageID":"msg_a1","id":"call_1","input":{"command":"ls"},"content":[{"type":"text","text":"files"}]}""",
            )
            for ((type, data) in frames) {
                val envelope = JSONObject("""{"id":"evt_$type","type":"$type","data":$data}""")
                for (event in V2EventAdapter.adapt(type, envelope)) {
                    state = ChatStore.reduce(state, event)
                }
            }
            // Sanity: the streamed transcript is exactly one of each part.
            val streamed = state.messages.single { it.id == "msg_a1" }
            assertEquals(3, streamed.parts.size)

            state = ChatStore.reduce(state, ChatStore.Event.Snapshot(snapshot))
            val merged = state.messages.single { it.id == "msg_a1" }
            assertEquals(3, merged.parts.size)
            assertEquals("thinking", merged.parts.single { it.type == "reasoning" }.text)
            assertEquals("the sky is blue.", merged.parts.single { it.type == "text" }.text)
            assertEquals("files", merged.parts.single { it.type == "tool" }.toolOutput)

            // A repeated identical snapshot is a no-op.
            assertEquals(state, ChatStore.reduce(state, ChatStore.Event.Snapshot(snapshot)))
        }
    }

    /**
     * The snapshot read asks for the newest window and nothing else. Its merge
     * is an upsert over the whole tagged union, so a `type` filter — which
     * upstream applies *before* pagination — would shorten the window instead
     * of narrowing it. `V2Api.messages` takes no such parameter; this pins the
     * wire shape of the one production caller.
     */
    @Test
    fun v2SnapshotAsksForTheNewestWindowAndNoTypeFilter() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(V2ApiFixtures.MESSAGES))
            V2Transport(v2api(server)).snapshotMessages("ses_1", 50)

            val request = server.takeRequest()
            assertEquals("/api/session/ses_1/message", request.requestUrl!!.encodedPath)
            assertEquals("50", request.requestUrl!!.queryParameter("limit"))
            assertEquals("desc", request.requestUrl!!.queryParameter("order"))
            assertEquals(setOf("limit", "order"), request.requestUrl!!.queryParameterNames)
        }
    }

    /**
     * `GET /api/session/active` → `{"data":{ses_…:{"type":"running"}}}`
     * (measured 2026-09-22 while a foreground `sleep` tool call ran):
     * the snapshot liveness reconcile reads the tracked session's status
     * from this map; absent sessions and failed calls read as not-running.
     */
    @Test
    fun v2ActiveRunStatusReadsTheActiveSet() {
        MockWebServer().use { server ->
            val transport = V2Transport(v2api(server))
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"data":{"ses_1":{"type":"running"}}}"""),
            )
            assertEquals("running", transport.activeRunStatus("ses_1"))

            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"data":{"ses_1":{"type":"running"}}}"""),
            )
            assertNull(transport.activeRunStatus("ses_elsewhere"))

            server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
            assertNull(transport.activeRunStatus("ses_1"))
        }
    }

    // -------------------------------------- export / review / home reads (P0)

    /**
     * v2 file preview: the absolute path must become a location-relative
     * `fs/read` segment (measured: the route takes no absolute path, and a
     * path outside the location is a `404 FileNotFoundError` — never a guess
     * that could show a different file).
     */
    @Test
    fun v2FileContentReadsRelativeBytesThroughFsRead() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("hello v2"))
            val transport = V2Transport(v2api(server, directory = "/home/me"))
            val content = transport.fileContent("/home/me/app/build.gradle.kts", directory = "/home/me")
            assertFalse(content.binary)
            assertEquals("hello v2", content.content)
            val recorded = server.takeRequest()
            assertEquals("/api/fs/read/app/build.gradle.kts", recorded.requestUrl!!.encodedPath)
            assertEquals("/home/me", recorded.requestUrl!!.queryParameter("location[directory]"))

            // Outside the location: fails BEFORE a request (no doomed 400/404
            // round-trip, no silent wrong-file read).
            try {
                transport.fileContent("/etc/hosts", directory = "/home/me")
                fail("an outside-location path must not resolve")
            } catch (_: IOException) {
                // expected
            }
            assertEquals(1, server.requestCount)
        }
    }

    /** Export frame: raw `{data}` unwraps, pages follow `cursor` ALONE after `order=asc`. */
    @Test
    fun v2ExportFramePagesRawRowsCursorAloneAfterOrderAsc() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setHeader("Content-Type", "application/json")
                    .setBody("""{"data":{"id":"ses_1","title":"T"}}"""),
            )
            server.enqueue(
                MockResponse().setHeader("Content-Type", "application/json")
                    .setBody("""{"data":[{"id":"msg_1","type":"user"}],"cursor":{"previous":null,"next":"c1"}}"""),
            )
            server.enqueue(
                MockResponse().setHeader("Content-Type", "application/json")
                    .setBody("""{"data":[{"id":"msg_2","type":"assistant"}],"cursor":{"previous":"c1","next":null}}"""),
            )
            val transport = V2Transport(v2api(server))
            val frame = transport.fetchExportFrame("ses_1", directory = "/home/me")
            assertEquals("ses_1", frame.info.getString("id"))
            assertEquals(listOf("msg_1", "msg_2"), frame.messages.map { it.getString("id") })

            assertEquals("/api/session/ses_1", server.takeRequest().requestUrl!!.encodedPath)
            val page1 = server.takeRequest().requestUrl!!
            assertEquals("/api/session/ses_1/message", page1.encodedPath)
            assertEquals("asc", page1.queryParameter("order"))
            assertNull(page1.queryParameter("cursor"))
            // Measured: cursor + order together answer 400 — the follow-up is cursor-only.
            val page2 = server.takeRequest().requestUrl!!
            assertEquals("c1", page2.queryParameter("cursor"))
            assertNull(page2.queryParameter("order"))
            assertEquals(3, server.requestCount)
        }
    }

    /** Measured mode gap: v2's `/api/vcs/diff` 400s `git`, so the scope maps to `working`. */
    @Test
    fun v2VcsDiffTranslatesGitToWorking() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setHeader("Content-Type", "application/json").setBody("""{"data":[]}"""),
            )
            val transport = V2Transport(v2api(server, directory = "/home/me"))
            assertTrue(transport.vcsDiff("git", directory = "/home/me").isEmpty())
            val url = server.takeRequest().requestUrl!!
            assertEquals("/api/vcs/diff", url.encodedPath)
            assertEquals("working", url.queryParameter("mode"))
            assertEquals("/home/me", url.queryParameter("location[directory]"))
        }
    }

    /** v2 projects: bare `canonical` array → `OpenCodeClient.Project` rows for Home. */
    @Test
    fun v2ListProjectsMapsTheBareCanonicalArray() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setHeader("Content-Type", "application/json").setBody(
                    """[{"id":"p1","canonical":"/w/app","time":{"created":1,"updated":2},""" +
                        """"sandboxes":[],"vcs":"git"}]""",
                ),
            )
            val transport = V2Transport(v2api(server))
            val projects = transport.listProjects()
            assertEquals(1, projects.size)
            assertEquals("/w/app", projects.single().worktree)
            assertEquals("git", projects.single().vcs)
            assertEquals("/api/project", server.takeRequest().requestUrl!!.encodedPath)
        }
    }

    // ------------------------------------------------------------------ browse
    //
    // Browsing must not boot locations: every new `location[directory]` value
    // persists one project row server-side (no delete route), so the picker
    // rides ONE cached anchor (the filesystem root) with `path=` relative to
    // it across all browses.

    @Test
    fun listDirectoryReusesOneAnchorAcrossBrowses() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"directory":"/home/me","project":{"id":"g","directory":"/home/me"}}""",
                ),
            )
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"location":{"directory":"/"}""" + ""","data":[{"path":"home/me/code/app/","type":"directory"},{"path":"home/me/code/gradlew","type":"file"}]}""",
                ),
            )
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"location":{"directory":"/"}""" + ""","data":[{"path":"home/me/other/x/","type":"directory"}]}""",
                ),
            )
            val transport = V2Transport(v2api(server))

            val first = transport.listDirectory("/home/me/code")
            assertEquals(
                listOf("/home/me/code/app", "/home/me/code/gradlew"),
                first.map { it.absolute },
            )
            assertEquals(listOf("app", "gradlew"), first.map { it.name })
            assertEquals(listOf(true, false), first.map { it.isDirectory })

            val second = transport.listDirectory("/home/me/other")
            assertEquals(listOf("/home/me/other/x"), second.map { it.absolute })

            // One location read (anchor cached), then the same anchor with a
            // wandering `path=` — never the browsed dir as the location.
            assertEquals("/api/location", server.takeRequest().requestUrl!!.encodedPath)
            val browse1 = server.takeRequest().requestUrl!!
            assertEquals("/api/fs/list", browse1.encodedPath)
            assertEquals("/", browse1.queryParameter("location[directory]"))
            assertEquals("home/me/code", browse1.queryParameter("path"))
            val browse2 = server.takeRequest().requestUrl!!
            assertEquals("/api/fs/list", browse2.encodedPath)
            assertEquals("/", browse2.queryParameter("location[directory]"))
            assertEquals("home/me/other", browse2.queryParameter("path"))
        }
    }

    @Test
    fun listDirectoryAtAnchorListsWithoutPath() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"directory":"/home/me","project":{"id":"g","directory":"/home/me"}}""",
                ),
            )
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"location":{"directory":"/"},"data":[{"path":"home/","type":"directory"}]}""",
                ),
            )
            val transport = V2Transport(v2api(server))
            val rows = transport.listDirectory("/")
            assertEquals(listOf("/home"), rows.map { it.absolute })
            server.takeRequest() // /api/location
            val browse = server.takeRequest().requestUrl!!
            assertEquals("/api/fs/list", browse.encodedPath)
            assertEquals("/", browse.queryParameter("location[directory]"))
            assertNull(browse.queryParameter("path"))
        }
    }

    @Test
    fun listDirectoryOutsideAnchorFallsBackToLocationScope() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"directory":"/home/me","project":{"id":"g","directory":"/home/me"}}""",
                ),
            )
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"location":{"directory":"D:/x"},"data":[{"path":"a/","type":"directory"}]}""",
                ),
            )
            val transport = V2Transport(v2api(server))
            val rows = transport.listDirectory("D:/x")
            assertEquals(listOf("D:/x/a"), rows.map { it.absolute })
            server.takeRequest() // /api/location
            val browse = server.takeRequest().requestUrl!!
            assertEquals("D:/x", browse.queryParameter("location[directory]"))
            assertNull(browse.queryParameter("path"))
        }
    }

    // ------------------------------------------------------------------ rename
    //
    // `PATCH` answers 204 with no body; the transport re-reads and reports the
    // title ONLY when the server actually holds the requested one — a foreign
    // title (regenerate/normalize/race) is "not applied", so the caller surfaces
    // a visible failure instead of mirroring a wrong title.
    //
    // v81: the answer is a `RenameResult` (the five shapes a caller acts on
    // differently — see `RenameOutcomeV81Test`) and the re-read is a **bounded
    // window**: acceptance is still an exact match, but the read is repeated, so
    // a write the server accepted is not called unconfirmed because the first
    // read was a few milliseconds early.

    @Test
    fun renameSessionReturnsTitleOnlyOnExactMatch() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"data":{"id":"ses_1","title":"New","time":{"updated":1}}}""",
                ),
            )
            val transport = V2Transport(v2api(server), renameConfirmDelayMs = 0L)

            assertEquals(RenameResult.Renamed("New"), transport.renameSession("ses_1", "New"))

            val patch = server.takeRequest()
            assertEquals("PATCH", patch.method)
            assertEquals("/api/session/ses_1", patch.requestUrl!!.encodedPath)
            assertEquals("GET", server.takeRequest().method)
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun sseResponse(body: String): okhttp3.Response = okhttp3.Response.Builder()
        .request(okhttp3.Request.Builder().url("http://127.0.0.1/api/event").build())
        .protocol(okhttp3.Protocol.HTTP_1_1)
        .code(200)
        .message("OK")
        .body(body.toResponseBody("text/event-stream".toMediaType()))
        .build()
}
