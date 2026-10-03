package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.v2.V2Api
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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
 * W2 protocol-parameter batch — audit A-7 / A-8 / A-9 / A-10, one
 * [MockWebServer] pin per request shape, checked field by field against
 * `_upstream/packages/protocol/src/groups/`:
 *
 * - **A-7** `session.prompt` / `session.command` attachments carry
 *   `mention{start,end,text}` (`schema/src/prompt-input.ts:8-33`,
 *   `schema/src/prompt.ts:7-12`) and the prompt's top-level `metadata`
 *   (`session.ts:391-399`, `SessionInbox.UserPayload.metadata`).
 * - **A-8** `GET /api/permission/saved` takes `projectID`
 *   (`permission.ts:37-40`).
 * - **A-9** `POST /api/session/{id}/permission` takes
 *   `metadata` / `source` / `agent` (`permission.ts:63-85`).
 * - **A-10** `GET /api/session/{id}/form/{formID}` answers `Form.Detail`
 *   (`session.ts:824-837`), i.e. `Form.Info` + `state` (`schema/src/form.ts:150-160`).
 *
 * No test touches a real server: every response is enqueued locally.
 */
class V2ApiProtocolParamsTest {

    private fun RecordedRequest.json(): JSONObject = JSONObject(body.readUtf8())

    private fun api(server: MockWebServer, directory: String? = null) = V2Api(
        baseHttpUrl = server.url("/").toString().toHttpUrlOrNull()!!,
        username = "opencode",
        password = "opencode",
        http = V2Api.defaultClient(),
        directory = directory,
    )

    private fun mention(start: Int, end: Int, text: String) = V2Api.PromptMention(start, end, text)

    // -------------------------------------------------- A-7: prompt mentions

    @Test
    fun promptCarriesMentionSpansOnFilesAgentsAndSkills() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(V2ApiFixtures.PROMPT_ADMITTED))
            api(server).prompt(
                "ses_1",
                text = "look at @main.kt and @build",
                files = listOf(
                    V2Api.PromptFile(
                        uri = "file:///w/main.kt",
                        name = "main.kt",
                        description = "entry point",
                        mention = mention(9, 17, "@main.kt"),
                    ),
                ),
                agents = listOf(V2Api.PromptAgent("build", mention(23, 29, "@build")).toJson()),
                skills = listOf(V2Api.PromptSkill("kb", mention(23, 26, "@kb")).toJson()),
                metadata = JSONObject()
                    .put("displayText", "look at @main.kt and @build")
                    .put("comments", JSONObject().put("path", "main.kt")),
            )

            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/session/ses_1/prompt", request.requestUrl!!.encodedPath)
            val body = request.json()
            assertEquals("look at @main.kt and @build", body.getString("text"))

            // `PromptInput.FileAttachment{uri, name?, description?, mention?}`
            // (`prompt-input.ts:8-16`) — exactly these four keys.
            val file = body.getJSONArray("files").getJSONObject(0)
            assertEquals(setOf("uri", "name", "description", "mention"), file.keys().asSequence().toSet())
            assertEquals("file:///w/main.kt", file.getString("uri"))
            assertEquals("entry point", file.getString("description"))
            val fileMention = file.getJSONObject("mention")
            assertEquals(setOf("start", "end", "text"), fileMention.keys().asSequence().toSet())
            assertEquals(9, fileMention.getInt("start"))
            assertEquals(17, fileMention.getInt("end"))
            assertEquals("@main.kt", fileMention.getString("text"))

            // `PromptInput.AgentAttachment{name, mention?}` (`:23-26`).
            val agent = body.getJSONArray("agents").getJSONObject(0)
            assertEquals(setOf("name", "mention"), agent.keys().asSequence().toSet())
            assertEquals("build", agent.getString("name"))
            assertEquals(23, agent.getJSONObject("mention").getInt("start"))
            assertEquals(29, agent.getJSONObject("mention").getInt("end"))
            assertEquals("@build", agent.getJSONObject("mention").getString("text"))

            // `PromptInput.SkillAttachment{id, mention?}` (`:30-33`).
            val skill = body.getJSONArray("skills").getJSONObject(0)
            assertEquals(setOf("id", "mention"), skill.keys().asSequence().toSet())
            assertEquals("kb", skill.getString("id"))
            assertEquals("@kb", skill.getJSONObject("mention").getString("text"))

            // Top-level `metadata` is passed through verbatim (`session.ts:396`).
            val metadata = body.getJSONObject("metadata")
            assertEquals("look at @main.kt and @build", metadata.getString("displayText"))
            assertEquals("main.kt", metadata.getJSONObject("comments").getString("path"))
        }
    }

    @Test
    fun promptOmitsMentionWhenTheAttachmentHasNone() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(V2ApiFixtures.PROMPT_ADMITTED))
            api(server).prompt(
                "ses_1",
                text = "hi",
                files = listOf(V2Api.PromptFile(uri = "data:image/png;base64,AAA")),
                agents = listOf(V2Api.PromptAgent("build").toJson()),
                skills = listOf(V2Api.PromptSkill("kb").toJson()),
            )

            val body = server.takeRequest().json()
            // An absent span must be an absent key, not `"mention":null` —
            // `additionalProperties:false`-style payloads reject neither, but a
            // null would read as a present-but-empty span on the runner side.
            val file = body.getJSONArray("files").getJSONObject(0)
            assertEquals(setOf("uri"), file.keys().asSequence().toSet())
            assertFalse(file.has("mention"))
            assertEquals(setOf("name"), body.getJSONArray("agents").getJSONObject(0).keys().asSequence().toSet())
            assertEquals(setOf("id"), body.getJSONArray("skills").getJSONObject(0).keys().asSequence().toSet())
            assertFalse(body.has("metadata"))
            assertFalse(body.has("delivery"))
        }
    }

    @Test
    fun mentionJsonNullNeverBecomesTheLiteralNullString() {
        // org.json's `optString` answers `"null"` for a JSON null — the trap the
        // whole v2 layer guards against (`optStringOrNull`). A `mention` whose
        // `text` is null must read as empty, and a null/missing span as absent.
        val nullText = V2Api.PromptMention.fromJson(
            JSONObject("""{"start":1,"end":2,"text":null}"""),
        )
        assertEquals("", nullText!!.text)
        assertEquals(1, nullText.start)
        assertNull(V2Api.PromptMention.fromJson(null))
        assertNull(V2Api.PromptMention.fromJson(JSONObject("""{"end":2,"text":"@a"}""")))
        // Round-trip keeps exactly the three keys (json-java does not guarantee
        // key order, so the fields are compared one by one).
        val roundTrip = mention(3, 4, "@ab").toJson()
        assertEquals(setOf("start", "end", "text"), roundTrip.keys().asSequence().toSet())
        assertEquals(3, roundTrip.getInt("start"))
        assertEquals(4, roundTrip.getInt("end"))
        assertEquals("@ab", roundTrip.getString("text"))
    }

    @Test
    fun commandCarriesTheSameAttachmentShapesAsPrompt() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            api(server).command(
                "ses_1",
                name = "init",
                text = "init @main.kt",
                files = listOf(
                    V2Api.PromptFile(uri = "file:///w/main.kt", mention = mention(5, 13, "@main.kt")),
                ),
                delivery = "queue",
                agents = listOf(V2Api.PromptAgent("plan").toJson()),
                skills = listOf(V2Api.PromptSkill("kb", mention(5, 8, "@kb")).toJson()),
            )

            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/session/ses_1/command", request.requestUrl!!.encodedPath)
            val body = request.json()
            assertEquals("init", body.getString("name"))
            assertEquals("init @main.kt", body.getString("text"))
            assertEquals("queue", body.getString("delivery"))
            assertEquals(5, body.getJSONArray("files").getJSONObject(0).getJSONObject("mention").getInt("start"))
            assertEquals("plan", body.getJSONArray("agents").getJSONObject(0).getString("name"))
            assertEquals("kb", body.getJSONArray("skills").getJSONObject(0).getString("id"))
            // `session.command`'s payload is `{name, ...PromptInput.Prompt, delivery?}` —
            // no `metadata` field exists on this route (`session.ts:413-419`).
            assertFalse(body.has("metadata"))
        }
    }

    // -------------------------------------------------- A-8: saved permissions

    @Test
    fun savedPermissionsSendsTheProjectIDFilterOnlyWhenAsked() {
        MockWebServer().use { server ->
            // `PermissionSaved.Info` — the element is an OBJECT
            // (`schema/src/permission-saved.ts:11-22`), never a bare id string.
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"data":[
                       {"id":"psv_1","projectID":"prj_1","action":"bash","resource":"*",
                        "time":{"created":1,"updated":2}},
                       {"id":"psv_2","projectID":"prj_1","action":"write","resource":"/tmp",
                        "time":{"created":3,"updated":4}}]}""",
                ),
            )
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"data":[]}"""))
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"data":[]}"""))
            val api = api(server, directory = "/w")

            val filtered = api.savedPermissions("prj_1")
            val all = api.savedPermissions()
            api.savedPermissions("")

            val scoped = server.takeRequest()
            assertEquals("GET", scoped.method)
            assertEquals("/api/permission/saved", scoped.requestUrl!!.encodedPath)
            // `permission.saved.list` query is `{projectID?}` and nothing else —
            // notably no `location[directory]` (`permission.ts:37-40`).
            assertEquals(1, scoped.requestUrl!!.querySize)
            assertEquals("prj_1", scoped.requestUrl!!.queryParameter("projectID"))
            assertEquals(listOf("psv_1", "psv_2"), filtered.map { it.id })
            // The whole `PermissionSaved.Info` row is readable, not just the id.
            assertEquals("prj_1", filtered.first().projectID)
            assertEquals("bash", filtered.first().action)
            assertEquals("*", filtered.first().resource)
            assertEquals(1L, filtered.first().timeCreated)
            assertEquals(2L, filtered.first().timeUpdated)

            assertEquals(0, server.takeRequest().requestUrl!!.querySize)
            assertTrue(all.isEmpty())
            // Blank is "no filter", not `?projectID=`.
            assertEquals(0, server.takeRequest().requestUrl!!.querySize)
        }
    }

    // -------------------------------------------------- A-9: create permission

    @Test
    fun createPermissionSendsMetadataSourceAndAgent() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setBody("""{"data":{"id":"per_1","effect":"ask"}}"""),
            )
            val result = api(server).createPermission(
                sessionID = "ses_1",
                action = "bash",
                resources = listOf("rm -rf /"),
                id = "per_1",
                save = listOf("bash:rm -rf /"),
                metadata = JSONObject().put("reason", "cleanup"),
                source = JSONObject().put("type", "tool").put("messageID", "msg_1").put("id", "call_1"),
                agent = "build",
            )

            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/session/ses_1/permission", request.requestUrl!!.encodedPath)
            val body = request.json()
            // `{id?, action, resources, save?, metadata?, source?, agent?}`
            // (`permission.ts:63-85`).
            assertEquals(
                setOf("id", "action", "resources", "save", "metadata", "source", "agent"),
                body.keys().asSequence().toSet(),
            )
            assertEquals("per_1", body.getString("id"))
            assertEquals("bash", body.getString("action"))
            assertEquals("rm -rf /", body.getJSONArray("resources").getString(0))
            assertEquals("bash:rm -rf /", body.getJSONArray("save").getString(0))
            assertEquals("cleanup", body.getJSONObject("metadata").getString("reason"))
            // `Permission.Source{type:"tool", messageID, id}` (`permission.ts:16-22`).
            assertEquals("tool", body.getJSONObject("source").getString("type"))
            assertEquals("msg_1", body.getJSONObject("source").getString("messageID"))
            assertEquals("call_1", body.getJSONObject("source").getString("id"))
            assertEquals("build", body.getString("agent"))
            assertEquals("ask", result.getString("effect"))
        }
    }

    @Test
    fun createPermissionStillOmitsTheOptionalFieldsItIsNotGiven() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"data":{"id":"per_1","effect":"allow"}}"""))
            api(server).createPermission("ses_1", "bash", listOf("ls"))

            val body = server.takeRequest().json()
            assertEquals(setOf("action", "resources"), body.keys().asSequence().toSet())
        }
    }

    // -------------------------------------------------- A-10: form Detail

    @Test
    fun formGetReadsFormDetailNotFormInfo() {
        MockWebServer().use { server ->
            // `Form.Detail` = `Form.Info` + `state` (`schema/src/form.ts:150-160`).
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"data":{"id":"frm_1","sessionID":"ses_1","title":"Pick one",
                       "metadata":{"kind":"websearch.provider"},
                       "fields":[{"key":"q","title":"Q?","type":"string"}],
                       "state":{"status":"answered","answer":{"q":"yes"}}}}""",
                ),
            )
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"data":{"id":"frm_2","sessionID":"ses_1","title":"Pending",
                       "fields":[],"state":{"status":"pending"}}}""",
                ),
            )
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"data":{"id":"frm_3","sessionID":"ses_1","title":"Cancelled",
                       "fields":[],"state":{"status":"cancelled"}}}""",
                ),
            )
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"data":{"id":"frm_4","sessionID":"ses_1","title":"No state key","fields":[]}}""",
                ),
            )
            val api = api(server)

            val answered = api.form("ses_1", "frm_1")
            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/api/session/ses_1/form/frm_1", request.requestUrl!!.encodedPath)
            assertEquals(0, request.requestUrl!!.querySize)
            assertEquals("frm_1", answered.id)
            assertEquals("Pick one", answered.title)
            assertEquals("websearch.provider", answered.raw.getJSONObject("metadata").getString("kind"))
            assertEquals("answered", answered.state!!.status)
            assertEquals("yes", answered.state!!.answer!!.getString("q"))

            assertEquals("pending", api.form("ses_1", "frm_2").state!!.status)
            val cancelled = api.form("ses_1", "frm_3").state!!
            assertEquals("cancelled", cancelled.status)
            // Only `answered` carries an answer (`form.ts:140-148`).
            assertNull(cancelled.answer)
            // A Detail frame with no `state` is tolerated, not a crash.
            assertNull(api.form("ses_1", "frm_4").state)
        }
    }

    @Test
    fun formListRoutesStayFormInfoWithNoState() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(V2ApiFixtures.SESSION_FORMS_ONE))
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"location":{"directory":"/w"},"data":[]}"""))
            val api = api(server, directory = "/w")

            val sessionForms = api.sessionForms("ses_1")
            assertEquals("t", sessionForms.single().title)
            // `session.form.list` answers `Form.Info` — there is no `state` to read.
            assertNull(sessionForms.single().state)
            assertTrue(api.pendingForms().isEmpty())
        }
    }
}
