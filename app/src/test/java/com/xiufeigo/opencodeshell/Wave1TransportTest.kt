package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.v2.ServerGeneration
import com.xiufeigo.opencodeshell.v2.V1Transport
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
import org.junit.Test

/**
 * Wave 1 dual-protocol transport tests. No test touches a real server:
 * request shapes go through [MockWebServer], event frames through recorded
 * live JSON ([V2ApiFixtures.SSE_V2_TURN]), and the generation rule is pure
 * ([V2Capability.decideInfo]/[decideHealth]).
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
    fun v2InfoAndV1HealthNeverCrossSelect() {
        // v2 server: info says V2, health leg says UNKNOWN (SPA HTML there).
        assertEquals(ServerGeneration.V2, V2Capability.decideInfo(200, "application/json", V2ApiFixtures.INFO_V2).generation)
        assertEquals(
            ServerGeneration.UNKNOWN,
            V2Capability.decideHealth(200, "text/html", V2ApiFixtures.SPA_HTML).generation,
        )
        // v1 server: info says UNKNOWN (SPA HTML), health leg says V1.
        assertEquals(
            ServerGeneration.UNKNOWN,
            V2Capability.decideInfo(200, "text/html", V2ApiFixtures.SPA_HTML).generation,
        )
        assertEquals(ServerGeneration.V1, V2Capability.decideHealth(200, "application/json", V2ApiFixtures.HEALTH_V1).generation)
        // A proxy/captive page (HTML on both) is UNKNOWN, never a generation.
        assertEquals(
            ServerGeneration.UNKNOWN,
            V2Capability.decideInfo(200, "text/html", V2ApiFixtures.SPA_HTML).generation,
        )
    }

    @Test
    fun probeHitsInfoFirstThenHealthOnlyWhenNeeded() {
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
            // V2 confirmation: /api/location, not /global/health.
            assertEquals("/api/location", server.takeRequest().requestUrl!!.encodedPath)
            assertEquals(0, server.requestCount - 2)
        }
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "text/html").setBody(V2ApiFixtures.SPA_HTML))
            server.enqueue(MockResponse().setResponseCode(200).setBody(V2ApiFixtures.HEALTH_V1))
            val result = V2Capability.probe(
                server.url("/").toString().toHttpUrlOrNull()!!,
                http = V2Api.defaultClient(),
            )
            assertEquals(ServerGeneration.V1, result.generation)
            assertEquals("/api/info", server.takeRequest().requestUrl!!.encodedPath)
            assertEquals("/global/health", server.takeRequest().requestUrl!!.encodedPath)
        }
    }

    // ------------------------------------------------------------ V1 parity

    @Test
    fun v1TransportSendsByteIdenticalPromptShellAndCommand() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            val client = OpenCodeClient(server.url("/").toString().toHttpUrlOrNull()!!, null, null)
            val transport = V1Transport(client, server.url("/"), directory = "/w")

            transport.sendPrompt("ses_1", "hi", emptyList(), messageID = null, delivery = "steer", resume = false)
            val prompt = server.takeRequest()
            assertEquals("/session/ses_1/prompt_async", prompt.requestUrl!!.encodedPath)
            // v1 has no delivery/resume knobs: parts only.
            val body = JSONObject(prompt.body.readUtf8())
            assertTrue(body.has("parts"))
            assertFalse(body.has("delivery"))

            transport.sendShellAs("ses_1", "echo hi", agent = "build", model = null)
            val shell = server.takeRequest()
            assertEquals("/session/ses_1/shell", shell.requestUrl!!.encodedPath)
            assertEquals("build", JSONObject(shell.body.readUtf8()).getString("agent"))

            transport.sendCommandAs("ses_1", "init", "args", agent = null, model = null, variant = null)
            val command = server.takeRequest()
            assertEquals("/session/ses_1/command", command.requestUrl!!.encodedPath)
            assertEquals("init", JSONObject(command.body.readUtf8()).getString("command"))
            client.close()
        }
    }

    @Test
    fun v1TransportParsesFramesThroughTheExistingStorePath() {
        val events = V1Transport(
            OpenCodeClient("http://127.0.0.1:4096/".toHttpUrlOrNull()!!, null, null),
            "http://127.0.0.1:4096/".toHttpUrlOrNull()!!,
        ).parseFrame("session.idle", JSONObject("""{"type":"session.idle","properties":{}}"""))
        assertEquals(listOf(ChatStore.Event.RunStatusChanged(ChatStore.RunStatus.IDLE)), events)
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

    // ------------------------------------------------------------------ helpers

    private fun sseResponse(body: String): okhttp3.Response = okhttp3.Response.Builder()
        .request(okhttp3.Request.Builder().url("http://127.0.0.1/api/event").build())
        .protocol(okhttp3.Protocol.HTTP_1_1)
        .code(200)
        .message("OK")
        .body(body.toResponseBody("text/event-stream".toMediaType()))
        .build()
}
