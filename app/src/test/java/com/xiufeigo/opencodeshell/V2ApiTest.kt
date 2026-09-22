package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.v2.ServerGeneration
import com.xiufeigo.opencodeshell.v2.V2Api
import com.xiufeigo.opencodeshell.v2.V2Capability
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64

/**
 * The v2 transport layer: URL construction, envelope unwrapping and DTO parsing,
 * plus the SSE frame rules and the capability probe's decision logic.
 *
 * **No test touches a real server.** Payloads come from [V2ApiFixtures], captured
 * byte-faithfully off the live process; the transport tests use
 * [MockWebServer] on loopback (the same harness `PtyApiTest` uses) purely to
 * assert the *request* a call produces and to feed a response back — nothing is
 * fetched off the machine.
 */
class V2ApiTest {

    private fun api(
        server: MockWebServer,
        directory: String? = null,
        username: String? = null,
        password: String? = null,
    ) = V2Api(
        baseHttpUrl = server.url("/").toString().toHttpUrlOrNull()!!,
        username = username,
        password = password,
        http = V2Api.defaultClient(),
        directory = directory,
    )

    /**
     * `RecordedRequest.body` is a **consumable** `Buffer`: `readUtf8()` drains it,
     * so read it once per request and keep the object.
     */
    private fun RecordedRequest.json(): JSONObject = JSONObject(body.readUtf8())

    // ------------------------------------------------------------ health / location

    @Test
    fun healthBuildsTheApiRouteAndNeverSendsALocationParameter() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(V2ApiFixtures.HEALTH))
            // A configured directory must NOT leak into a non-location route.
            val result = api(server, directory = """C:\w""").health()

            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/api/health", request.requestUrl!!.encodedPath)
            assertEquals(0, request.requestUrl!!.querySize)
            assertTrue(result.healthy)
        }
    }

    @Test
    fun locationUsesTheDeepObjectQueryKeyAndParsesTheBareObject() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(V2ApiFixtures.LOCATION))
            val location = api(server, directory = """C:\w""").location()

            val request = server.takeRequest()
            assertEquals("/api/location", request.requestUrl!!.encodedPath)
            // OkHttp may leave the brackets literal; the live server accepts both
            // spellings (measured), so decode rather than compare raw bytes.
            assertEquals("""C:\w""", request.requestUrl!!.queryParameter("location[directory]"))
            assertEquals("""C:\WINDOWS\system32""", location.directory)
            assertEquals("global", location.projectID)
            assertEquals("""C:\""", location.projectDirectory)
            // `workspaceID` is absent on a server with no workspace registry.
            assertNull(location.workspaceID)
        }
    }

    @Test
    fun basicAuthUsesTheOpencodeUserAndIsOmittedWithoutAPassword() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(V2ApiFixtures.HEALTH))
            api(server, password = "secret").health()
            val expected = "Basic " + Base64.getEncoder().encodeToString("opencode:secret".toByteArray())
            assertEquals(expected, server.takeRequest().getHeader("Authorization"))

            server.enqueue(MockResponse().setResponseCode(200).setBody(V2ApiFixtures.HEALTH))
            api(server).health()
            assertNull(server.takeRequest().getHeader("Authorization"))
        }
    }

    // ------------------------------------------------------------------- sessions

    @Test
    fun sessionsSendThePlainDirectoryQueryAndParseTheCursorEnvelope() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(V2ApiFixtures.SESSION_PAGE))
            val page = api(server, directory = """C:\w""").sessions(limit = 2, order = "asc", search = "Friendly")

            val request = server.takeRequest()
            assertEquals("/api/session", request.requestUrl!!.encodedPath)
            // This route's `directory` parameter is a PLAIN string, not location[directory].
            assertEquals("""C:\w""", request.requestUrl!!.queryParameter("directory"))
            assertNull(request.requestUrl!!.queryParameter("location[directory]"))
            assertEquals("2", request.requestUrl!!.queryParameter("limit"))
            assertEquals("asc", request.requestUrl!!.queryParameter("order"))
            assertEquals("Friendly", request.requestUrl!!.queryParameter("search"))

            assertEquals(1, page.data.size)
            val session = page.data.single()
            assertEquals("ses_f408ec4a0ffeKjpcGKG4kfGEBR", session.id)
            assertEquals("global", session.projectID)
            assertEquals("build", session.agent)
            assertEquals("muse-spark-1.3-contributor", session.model!!.id)
            assertEquals("opencode-go", session.model!!.providerID)
            assertEquals("default", session.model!!.variant)
            assertEquals(28621L, session.tokensInput)
            assertEquals(20L, session.tokensOutput)
            assertEquals(171L, session.tokensReasoning)
            assertEquals(1789918264159L, session.timeCreated)
            assertEquals("Friendly greeting", session.title)
            // v2 nests the workspace under `location`; v1 had a top-level `directory`.
            assertEquals("""C:\sensorsdata\main\program\DSH 临时会话""", session.directory)
            assertEquals("sensorsdata/main/program/DSH 临时会话", session.subpath)
            assertNull(session.parentID)
            assertNull(session.timeArchived)
            // The cursor only appears once `limit` is used.
            assertNotNull(page.cursor)
            assertTrue(page.nextCursor!!.startsWith("eyJhbmNob3Ii"))
        }
    }

    @Test
    fun sessionsWithoutLimitSendNoCursorAndTolerateAMissingCursor() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setBody("""{"data":[{"id":"ses_1","projectID":"global","cost":0,"tokens":{"input":0,"output":0,"reasoning":0,"cache":{"read":0,"write":0}},"time":{"created":1,"updated":2},"title":"t","location":{"directory":"C:\\w"}}]}"""),
            )
            val page = api(server).sessions()

            assertEquals(0, server.takeRequest().requestUrl!!.querySize)
            assertEquals(1, page.data.size)
            assertNull(page.cursor)
            assertNull(page.nextCursor)
        }
    }

    @Test
    fun createSessionPostsLocationAgentAndModelAndUnwrapsData() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(V2ApiFixtures.SESSION_CREATED))
            val created = api(server).createSession(
                agent = "build",
                model = V2Api.ModelRef("gpt-5.6-sol-fast", "openai"),
                directory = """C:\sensorsdata\main\program\opencode-android-shell""",
            )

            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/session", request.requestUrl!!.encodedPath)
            // The write takes `location` in the BODY, not as a query parameter.
            assertEquals(0, request.requestUrl!!.querySize)
            val body = request.json()
            assertEquals("build", body.getString("agent"))
            assertEquals("""C:\sensorsdata\main\program\opencode-android-shell""",
                body.getJSONObject("location").getString("directory"))
            assertEquals("gpt-5.6-sol-fast", body.getJSONObject("model").getString("id"))
            assertEquals("openai", body.getJSONObject("model").getString("providerID"))
            // No variant was supplied, so it must not be invented.
            assertFalse(body.getJSONObject("model").has("variant"))

            assertEquals("ses_f3d84f9d4ffeoLtv5OmiMrU6yO", created.id)
            assertEquals("New session - 2026-09-21T05:40:37.710Z", created.title)
            assertEquals(0.0, created.cost, 0.0)
        }
    }

    @Test
    fun sessionGetBuildsTheIdPath() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(V2ApiFixtures.SESSION_CREATED))
            val session = api(server).session("ses_f3d84f9d4ffeoLtv5OmiMrU6yO")

            assertEquals("/api/session/ses_f3d84f9d4ffeoLtv5OmiMrU6yO", server.takeRequest().requestUrl!!.encodedPath)
            assertEquals("ae54078c0e09bf8e7cbeefd35db19aaa6b064e46", session.projectID)
        }
    }

    @Test
    fun activeSessionsFlattensTheIdToStateMap() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(V2ApiFixtures.ACTIVE_EMPTY))
            assertTrue(api(server).activeSessions().isEmpty())
            assertEquals("/api/session/active", server.takeRequest().requestUrl!!.encodedPath)

            server.enqueue(MockResponse().setResponseCode(200).setBody(V2ApiFixtures.ACTIVE_BUSY))
            val busy = api(server).activeSessions()
            assertEquals("running", busy["ses_f3d84f9d4ffeoLtv5OmiMrU6yO"])
        }
    }

    // ------------------------------------------------------------------ messages

    @Test
    fun messagesParseTheFlatTaggedUnionNotV1sInfoAndParts() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(V2ApiFixtures.MESSAGES))
            val page = api(server).messages("ses_1", limit = 1, order = "desc", cursor = "cur")

            val request = server.takeRequest()
            assertEquals("/api/session/ses_1/message", request.requestUrl!!.encodedPath)
            assertEquals("1", request.requestUrl!!.queryParameter("limit"))
            assertEquals("desc", request.requestUrl!!.queryParameter("order"))
            assertEquals("cur", request.requestUrl!!.queryParameter("cursor"))

            assertEquals(2, page.data.size)

            val user = page.data[0]
            assertEquals(V2Api.Message.TYPE_USER, user.type)
            assertEquals("Say hi", user.text)
            assertEquals("Say hi", user.textOf())
            assertEquals(1789969243559L, user.timeCreated)
            assertNull(user.timeCompleted)
            assertTrue(user.content.isEmpty())
            assertNull(user.agent)
            assertNull(user.model)

            val assistant = page.data[1]
            assertEquals(V2Api.Message.TYPE_ASSISTANT, assistant.type)
            assertEquals("build", assistant.agent)
            assertEquals("gpt-5.6-sol", assistant.model!!.id)
            assertEquals("openai", assistant.model!!.providerID)
            assertEquals(1789969265313L, assistant.timeCreated)
            assertEquals(1789969271792L, assistant.timeCompleted)
            assertEquals(1, assistant.content.size)
            // `content[]` replaces v1's `parts[]`; the text part is what the
            // timeline renders.
            assertEquals("text", assistant.content[0].type)
            assertEquals("Hi!", assistant.content[0].text)
            assertEquals("Hi!", assistant.textOf())
            assertEquals(8234L, assistant.tokensInput)
            assertEquals(6L, assistant.tokensOutput)
            assertEquals(8240L, assistant.tokensTotal)
            // The assistant row has no top-level `text` key at all.
            assertNull(assistant.text)
            assertTrue(assistant.raw.has("snapshot"))
            assertTrue(page.cursor!!.next!!.isNotEmpty())
        }
    }

    @Test
    fun anEmptyMessagePageIsValidWithNullCursors() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(V2ApiFixtures.MESSAGES_EMPTY))
            val page = api(server).messages("ses_1")

            assertTrue(page.data.isEmpty())
            assertNotNull(page.cursor)
            assertNull(page.cursor!!.previous)
            assertNull(page.cursor!!.next)
        }
    }

    @Test
    fun historyRouteIsGoneReplayLivesOnTheLogStream() {
        // A5 S0.3: `GET /api/session/{id}/history` 404s and is absent from
        // openapi.json — V2Api.history() is removed. Replay is
        // `GET /api/experimental/session/{id}/log?after=&follow=`; the frame
        // DTO (DurableEvent) is still parsed from stream bytes.
        val events = ArrayList<V2Api.DurableEvent>()
        V2Api.readEventStream(sseResponse(V2ApiFixtures.SSE_SESSION), events::add)
        assertEquals(3, events.size)
        assertEquals(4L, events[0].seq)
    }

    // ------------------------------------------------------------------ prompting

    @Test
    fun promptPostsTheFlatBodyAndParsesTheInboxUserAdmission() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(V2ApiFixtures.PROMPT_ADMITTED))
            val admitted = api(server).prompt(
                "ses_1",
                text = "Say hi",
                delivery = "queue",
                resume = false,
            )

            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/session/ses_1/prompt", request.requestUrl!!.encodedPath)
            val body = request.json()
            // A5 S0.6: FLAT `text` — the old `{prompt:{text}}` wrapper is 400.
            assertEquals("Say hi", body.getString("text"))
            assertEquals("queue", body.getString("delivery"))
            assertFalse(body.getBoolean("resume"))
            assertFalse(body.has("prompt"))
            assertFalse(body.has("messageID"))

            assertEquals("msg_0c27b1da6001O2neNc6Sg8xZ9t", admitted.id)
            assertEquals("ses_f3d84f9d4ffeoLtv5OmiMrU6yO", admitted.sessionID)
            assertEquals("Say hi", admitted.text)
            assertEquals("queue", admitted.delivery)
            assertEquals(1789969243559L, admitted.timeCreated)
            // 2.0.11 answers Session.Inbox.User — no admittedSeq/promotedSeq.
            assertEquals(0L, admitted.admittedSeq)
            assertNull(admitted.promotedSeq)
        }
    }

    @Test
    fun switchAgentAndModelPostThe204Shapes() {
        MockWebServer().use { server ->
            for (code in listOf(204, 204)) {
                server.enqueue(MockResponse().setResponseCode(code))
            }
            val api = api(server)
            api.switchAgent("ses_1", "build")
            api.switchModel("ses_1", V2Api.ModelRef("gpt-5.6-sol-fast", "openai"))

            val agent = server.takeRequest()
            assertEquals("POST", agent.method)
            assertEquals("/api/session/ses_1/agent", agent.requestUrl!!.encodedPath)
            assertEquals("build", agent.json().getString("agent"))

            val model = server.takeRequest()
            assertEquals("/api/session/ses_1/model", model.requestUrl!!.encodedPath)
            assertEquals("openai", model.json().getJSONObject("model").getString("providerID"))
        }
    }

    @Test
    fun interruptPostsResumeQueryAndParsesTheFlag() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"interrupted":false}"""))
            assertFalse(api(server).interrupt("ses_1", resume = true))
            val interrupt = server.takeRequest()
            assertEquals("/api/session/ses_1/interrupt", interrupt.requestUrl!!.encodedPath)
            assertEquals("POST", interrupt.method)
            assertEquals("true", interrupt.requestUrl!!.queryParameter("resume"))
        }
    }

    @Test
    fun revertStageParsesTheStateAndClearIsADelete() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(V2ApiFixtures.REVERT_STAGE))
            server.enqueue(MockResponse().setResponseCode(204))
            val api = api(server)

            val staged = api.revertStage("ses_1", "msg_1", files = false)
            val request = server.takeRequest()
            assertEquals("/api/session/ses_1/revert/stage", request.requestUrl!!.encodedPath)
            val body = request.json()
            assertEquals("msg_1", body.getString("messageID"))
            assertFalse(body.getBoolean("files"))

            assertEquals("msg_0c27b3d4a001X0L2Er4RZTpEpe", staged.messageID)
            assertEquals("c06da87d1093008bc7d538ac4adca2b762814fd8", staged.snapshot)
            // An empty diff arrives as "" — kept as null rather than a fake patch.
            assertNull(staged.diff)
            assertTrue(staged.files.isEmpty())

            // A5 S0.7: clear is DELETE /revert — POST /revert/clear 404s.
            api.revertClear("ses_1")
            val clear = server.takeRequest()
            assertEquals("DELETE", clear.method)
            assertEquals("/api/session/ses_1/revert", clear.requestUrl!!.encodedPath)
        }
    }

    // ------------------------------------------------------------------- catalogs

    @Test
    fun agentsParseTheV2ShapeWhereTheNameIsCalledId() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(V2ApiFixtures.AGENTS))
            val agents = api(server).agents()

            val request = server.takeRequest()
            assertEquals("/api/agent", request.requestUrl!!.encodedPath)

            assertEquals(3, agents.size)
            assertEquals(listOf("build", "plan", "compaction"), agents.map { it.id })
            assertEquals(V2Api.AgentInfo.MODE_PRIMARY, agents[0].mode)
            assertTrue(agents[0].primary)
            assertFalse(agents[0].hidden)
            assertTrue(agents[2].hidden)
            // v1's `native` is not sent by v2 — absent must not become `true`.
            assertFalse(agents[0].raw.has("native"))
            assertFalse(agents[0].raw.has("name"))
            // The picker's rule drops the hidden compaction helper.
            assertEquals(listOf("build", "plan"), V2Api.AgentInfo.selectable(agents).map { it.id })
        }
    }

    @Test
    fun modelsReadVariantsAsAnIdArrayAndCostAsAnArray() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(V2ApiFixtures.MODELS))
            val models = api(server).models()

            assertEquals("/api/model", server.takeRequest().requestUrl!!.encodedPath)
            assertEquals(1, models.size)
            val model = models.single()
            assertEquals("stepfun/step-5-preview", model.id)
            assertEquals("nano-gpt", model.providerID)
            assertEquals("Step 5 Preview", model.name)
            assertEquals("step", model.family)
            assertEquals("active", model.status)
            assertTrue(model.enabled)
            assertEquals(true, model.tools)
            // capabilities.input/output are MODALITIES, not token limits.
            assertEquals(listOf("text", "image", "video"), model.input)
            assertEquals(listOf("text"), model.output)
            assertEquals(1000000, model.limitContext)
            assertEquals(1000000, model.limitOutput)
            assertEquals(1.0, model.costInput!!, 0.0)
            assertEquals(2.7, model.costOutput!!, 0.0)
            // `variants` measured as [] here; the parser reads `variants[].id`.
            assertTrue(model.variants.isEmpty())
        }
    }

    @Test
    fun modelVariantsAreReadFromTheObjectArrayIds() {
        val json = JSONObject(
            """{"id":"m","providerID":"p","name":"M","api":{"type":"t"},"capabilities":{"tools":false,"input":[],"output":[]},"request":{"headers":{},"body":{}},"variants":[{"id":"none","headers":{},"body":{}},{"id":"high","headers":{},"body":{}}],"time":{"released":1},"cost":[],"status":"active","enabled":true,"limit":{"context":10,"output":5}}""",
        )
        val model = V2Api.ModelInfo.fromJson(json)

        assertEquals(listOf("none", "high"), model.variants)
        assertEquals(false, model.tools)
        assertNull(model.costInput)
    }

    @Test
    fun providersParseWithoutAModelsMapAndProviderGetUnwrapsOne() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(V2ApiFixtures.PROVIDERS))
            val providers = api(server).providers()

            assertEquals("/api/provider", server.takeRequest().requestUrl!!.encodedPath)
            assertEquals(listOf("subconscious", "opencode-go"), providers.map { it.id })
            assertEquals("OpenCode Go", providers[1].name)
            assertEquals("aisdk", providers[1].apiType)
            assertEquals("@ai-sdk/openai-compatible", providers[1].apiPackage)
            // The v2 provider carries NO models map — that is v1 /provider only.
            assertFalse(providers[0].raw.has("models"))
            assertFalse(providers[0].disabled)
            assertNull(providers[0].integrationID)

            server.enqueue(MockResponse().setResponseCode(200).setBody(V2ApiFixtures.PROVIDER_ONE))
            val one = api(server).provider("opencode-go")
            assertEquals("/api/provider/opencode-go", server.takeRequest().requestUrl!!.encodedPath)
            assertEquals("https://opencode.ai/zen/go/v1", one.apiUrl)
        }
    }

    @Test
    fun aProviderNotFoundErrorKeepsItsTagInTheException() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(404).setBody(
                    """{"_tag":"ProviderNotFoundError","providerID":"nope","message":"Provider not found: nope"}""",
                ),
            )
            try {
                api(server).provider("nope")
                fail("expected ApiException")
            } catch (e: V2Api.ApiException) {
                assertEquals(404, e.code)
                assertTrue(e.message!!, e.message!!.contains("ProviderNotFoundError"))
                assertTrue(e.message!!, e.message!!.contains("Provider not found: nope"))
            }
        }
    }

    @Test
    fun commandsAndSkillsParseTheV2CatalogEntries() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(V2ApiFixtures.COMMANDS))
            val commands = api(server, directory = """C:\w""").commands()
            val commandRequest = server.takeRequest()
            assertEquals("/api/command", commandRequest.requestUrl!!.encodedPath)
            // The catalog routes DO use the deepObject location key.
            assertEquals("""C:\w""", commandRequest.requestUrl!!.queryParameter("location[directory]"))
            assertEquals(1, commands.size)
            assertEquals("init", commands.single().name)
            assertTrue(commands.single().template.startsWith("Create or update"))
            assertEquals("Create agents.md", commands.single().description)

            server.enqueue(MockResponse().setResponseCode(200).setBody(V2ApiFixtures.SKILLS))
            val skills = api(server, directory = """C:\w""").skills()
            val skillRequest = server.takeRequest()
            assertEquals("/api/skill", skillRequest.requestUrl!!.encodedPath)
            assertEquals("""C:\w""", skillRequest.requestUrl!!.queryParameter("location[directory]"))
            assertEquals("customize-opencode", skills.single().name)
            assertNotNull(skills.single().location)
            assertTrue(skills.single().content!!.startsWith("# Customize"))
        }
    }

    @Test
    fun anEmptyCatalogIsAnEmptyListNotAFailure() {
        MockWebServer().use { server ->
            for (i in 1..4) server.enqueue(MockResponse().setResponseCode(200).setBody(V2ApiFixtures.REFERENCES_EMPTY))
            val api = api(server)
            assertTrue(api.references().isEmpty())
            assertTrue(api.pendingPermissions().isEmpty())
            assertTrue(api.pendingForms().isEmpty())
            assertTrue(api.savedPermissions().isEmpty())
        }
    }

    @Test
    fun sessionScopedPermissionAndFormListsHaveNoLocationEnvelope() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(V2ApiFixtures.SESSION_PERMISSIONS_EMPTY))
            server.enqueue(MockResponse().setResponseCode(200).setBody(V2ApiFixtures.SESSION_FORMS_ONE))
            val api = api(server)

            assertTrue(api.sessionPermissions("ses_1").isEmpty())
            assertEquals("/api/session/ses_1/permission", server.takeRequest().requestUrl!!.encodedPath)
            val forms = api.sessionForms("ses_1")
            assertEquals("/api/session/ses_1/form", server.takeRequest().requestUrl!!.encodedPath)
            assertEquals(1, forms.size)
            assertEquals("frm_0c38a488e001UE9X8YyPgvMfb0", forms.single().id)
            assertEquals("t", forms.single().title)
            // Question routes do not exist in v2 (A5 S0.4): no client method
            // may target them — compile-time removal, pinned by absence.
        }
    }

    /**
     * The permission element shape pins the parser's field names — `action`/
     * `resources`/`save`/`source{id}` — against a document or server rename.
     * (Question shapes live in v1's OpenCodeClient.QuestionRequest, untouched.)
     */
    @Test
    fun unverifiedPermissionElementShapeParsesAsDocumented() {
        val permission = V2Api.PermissionRequest.fromJson(
            org.json.JSONArray(V2ApiFixtures.PERMISSION_REQUEST_UNVERIFIED).getJSONObject(0),
        )
        assertEquals("per_1", permission.id)
        assertEquals("bash", permission.action)
        assertEquals(listOf("rm -rf build"), permission.resources)
        assertEquals(listOf("rm *"), permission.save)
        assertEquals("msg_1", permission.messageID)
        // v2 source key is `id` (not v1's `callID`).
        assertEquals("call_1", permission.callID)
    }

    // ---------------------------------------------------------------- filesystem

    @Test
    fun fsListKeepsTheRelativeWindowsPathsAndTheirTrailingSeparator() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(V2ApiFixtures.FS_LIST))
            val entries = api(server, directory = """C:\w""").fsList(path = "app")

            val request = server.takeRequest()
            assertEquals("/api/fs/list", request.requestUrl!!.encodedPath)
            assertEquals("app", request.requestUrl!!.queryParameter("path"))
            assertEquals("""C:\w""", request.requestUrl!!.queryParameter("location[directory]"))

            assertEquals(5, entries.size)
            assertEquals("""_upstream\""", entries[0].path)
            assertTrue(entries[0].directory)
            assertEquals("_upstream", entries[0].name)
            assertEquals("gradlew.bat", entries[4].path)
            assertFalse(entries[4].directory)
            assertEquals("gradlew.bat", entries[4].name)
        }
    }

    @Test
    fun fsListOfASubdirectoryStaysRootedAtTheLocation() {
        // The fixture is the whole `{location, data}` envelope, so unwrap it.
        val entries = JSONObject(V2ApiFixtures.FS_LIST_SUBDIR).getJSONArray("data")
        assertEquals(2, entries.length())
        // Measured: `?path=app` returns "app\build\", not "build\".
        assertEquals("""app\build\""", entries.getJSONObject(0).getString("path"))
        assertEquals("build", V2Api.FsEntry.fromJson(entries.getJSONObject(0)).name)
        assertEquals("src", V2Api.FsEntry.fromJson(entries.getJSONObject(1)).name)
    }

    @Test
    fun fsReadServesRawBytesAndNormalisesABackslashPath() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setHeader("Content-Type", "application/x-msdownload")
                    .setBody("binary-ish payload"),
            )
            val bytes = api(server, directory = """C:\w""").fsRead("""app\build.gradle.kts""")

            val request = server.takeRequest()
            // `\` becomes `/` before the split, and the leading slash is stripped:
            // measured `/api/fs/read//x` is a 500 while `/api/fs/read/x` is a 200.
            assertEquals("/api/fs/read/app/build.gradle.kts", request.requestUrl!!.encodedPath)
            assertEquals("""C:\w""", request.requestUrl!!.queryParameter("location[directory]"))
            assertEquals("binary-ish payload", bytes.toString(Charsets.UTF_8))
        }
    }

    @Test
    fun fsFindSendsTheRequiredQueryAndParsesRankedEntries() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(V2ApiFixtures.FS_FIND))
            val found = api(server).fsFind("gradlew", type = "file", limit = 5)

            val request = server.takeRequest()
            assertEquals("/api/fs/find", request.requestUrl!!.encodedPath)
            assertEquals("gradlew", request.requestUrl!!.queryParameter("query"))
            assertEquals("file", request.requestUrl!!.queryParameter("type"))
            // `limit` is a STRING query param on this route.
            assertEquals("5", request.requestUrl!!.queryParameter("limit"))

            assertEquals(2, found.size)
            // The matcher is ranked, not literal — this entry has no "gradlew".
            assertTrue(found[0].path.endsWith("NewComponents.json"))
            assertEquals(V2Api.FsEntry.TYPE_DIRECTORY, found[1].type)
        }
    }

    @Test
    fun aMissingFindQuerySurfacesTheServersRequestError() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(400).setBody(V2ApiFixtures.MISSING_QUERY_ERROR))
            try {
                api(server).fsFind("")
                fail("expected ApiException")
            } catch (e: V2Api.ApiException) {
                assertEquals(400, e.code)
                assertTrue(e.message!!, e.message!!.contains("InvalidRequestError"))
            }
        }
    }

    // ----------------------------------------------------------------------- pty

    @Test
    fun ptyLifecycleBuildsEveryRouteAndUnwrapsLocationPlusData() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(V2ApiFixtures.PTY_LIST_EMPTY))
            server.enqueue(MockResponse().setResponseCode(200).setBody(V2ApiFixtures.PTY_CREATED))
            server.enqueue(MockResponse().setResponseCode(200).setBody(V2ApiFixtures.PTY_UPDATED))
            server.enqueue(MockResponse().setResponseCode(200).setBody(V2ApiFixtures.PTY_TICKET))
            server.enqueue(MockResponse().setResponseCode(204))
            val api = api(server, directory = """C:\w""")

            assertTrue(api.ptyList().isEmpty())
            assertEquals("/api/pty", server.takeRequest().requestUrl!!.encodedPath)

            val created = api.ptyCreate(command = "cmd.exe", title = "v2probe")
            val createRequest = server.takeRequest()
            assertEquals("POST", createRequest.method)
            assertEquals("/api/pty", createRequest.requestUrl!!.encodedPath)
            val createBody = createRequest.json()
            assertEquals("cmd.exe", createBody.getString("command"))
            assertEquals("v2probe", createBody.getString("title"))
            assertEquals("pty_0c27bd974001Yrmql3g4h5KZYX", created.id)
            assertEquals("running", created.status)
            assertEquals(49008, created.pid)
            assertTrue(created.running)
            assertTrue(created.args.isEmpty())
            // `exitCode` is absent while running — never a fake 0.
            assertNull(created.exitCode)

            val resized = api.ptyUpdate("pty_1", title = "v2probe2", cols = 80, rows = 24)
            val updateRequest = server.takeRequest()
            assertEquals("PUT", updateRequest.method)
            assertEquals("/api/pty/pty_1", updateRequest.requestUrl!!.encodedPath)
            val updateBody = updateRequest.json()
            assertEquals(24, updateBody.getJSONObject("size").getInt("rows"))
            assertEquals(80, updateBody.getJSONObject("size").getInt("cols"))
            assertEquals("v2probe2", resized.title)

            val ticket = api.ptyConnectTicket("pty_1")
            val ticketRequest = server.takeRequest()
            assertEquals("POST", ticketRequest.method)
            assertEquals("/api/pty/pty_1/connect-token", ticketRequest.requestUrl!!.encodedPath)
            // Measured: without this exact header the same POST answers 403.
            assertEquals("1", ticketRequest.getHeader("x-opencode-ticket"))
            assertEquals("08b2d9f5-c399-4213-bd40-564d93e649c4", ticket.ticket)
            assertEquals(60, ticket.expiresIn)

            api.ptyRemove("pty_1")
            val deleteRequest = server.takeRequest()
            assertEquals("DELETE", deleteRequest.method)
            assertEquals("/api/pty/pty_1", deleteRequest.requestUrl!!.encodedPath)
        }
    }

    @Test
    fun ptyGetAndTicketFailuresKeepTheirTag() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404).setBody(V2ApiFixtures.PTY_NOT_FOUND))
            try {
                api(server).ptyGet("pty_1")
                fail("expected ApiException")
            } catch (e: V2Api.ApiException) {
                assertEquals(404, e.code)
                assertTrue(e.message!!, e.message!!.contains("PtyNotFoundError"))
            }

            server.enqueue(MockResponse().setResponseCode(403).setBody(V2ApiFixtures.PTY_TICKET_FORBIDDEN))
            try {
                api(server).ptyConnectTicket("pty_1")
                fail("expected ApiException")
            } catch (e: V2Api.ApiException) {
                assertEquals(403, e.code)
                assertTrue(e.message!!, e.message!!.contains("ForbiddenError"))
            }
        }
    }

    @Test
    fun thePtySocketRequestUpgradesFromHttpAndCarriesTheDirectory() {
        MockWebServer().use { server ->
            val request = api(server, directory = """C:\w""").ptySocketRequest("pty_1", cursor = -1L)

            assertEquals("http", request.url.scheme)
            assertEquals("/api/pty/pty_1/connect", request.url.encodedPath)
            assertEquals("""C:\w""", request.url.queryParameter("location[directory]"))
            assertEquals("-1", request.url.queryParameter("cursor"))
            // No ticket obtained ⇒ none invented.
            assertNull(request.url.queryParameter("ticket"))
        }
    }

    // ------------------------------------------------------------------ sse

    @Test
    fun sessionEventStreamParsesDataOnlyFramesAndSkipsHeartbeats() {
        val events = ArrayList<V2Api.DurableEvent>()
        V2Api.readEventStream(sseResponse(V2ApiFixtures.SSE_SESSION), events::add)

        // Three `data:` frames; the `: heartbeat` comment yields nothing.
        assertEquals(3, events.size)
        assertEquals(
            listOf("session.next.step.started", "session.next.text.ended", "session.next.step.ended"),
            events.map { it.type },
        )
        val ended = events[1]
        assertEquals("evt_0c27b8a48001cFWE62O54bKASb", ended.id)
        assertEquals(6L, ended.seq)
        assertEquals(1L, ended.version)
        assertEquals("ses_f3d84f9d4ffeoLtv5OmiMrU6yO", ended.aggregateID)
        assertEquals("ses_f3d84f9d4ffeoLtv5OmiMrU6yO", ended.sessionID)
        assertEquals("Hi!", ended.text)
        assertEquals("msg_0c27b72a1001xI7Uoa2Ek3AoBy", ended.assistantMessageID)
        // The session stream carries no top-level location.
        assertNull(ended.locationDirectory)

        // seq 7 bumps `version` to 2 — the counter is per aggregate, not per seq.
        assertEquals(2L, events[2].version)
        assertTrue(events[2].data.has("tokens"))
    }

    @Test
    fun theGlobalStreamMixesV2DurableFramesWithV1ShapedInfoPayloads() {
        val events = ArrayList<V2Api.DurableEvent>()
        V2Api.readEventStream(sseResponse(V2ApiFixtures.SSE_GLOBAL), events::add)

        assertEquals(3, events.size)

        val connected = events[0]
        assertEquals("server.connected", connected.type)
        // This one has no `durable` block at all.
        assertNull(connected.seq)
        assertNull(connected.aggregateID)

        val pty = events[1]
        assertEquals("pty.updated", pty.type)
        assertNull(pty.seq)
        assertEquals("""C:\WINDOWS\system32""", pty.locationDirectory)
        assertEquals("pty_0c27bd974001Yrmql3g4h5KZYX", pty.data.getJSONObject("info").getString("id"))

        val session = events[2]
        assertEquals("session.updated", session.type)
        assertEquals(10L, session.seq)
        assertEquals("""C:\Windows\System32""", session.locationDirectory)
        assertEquals("ses_f3d83fd06ffeZsABPiDtXEwenN", session.sessionID)
        // V1-shaped payload leaks through under data.info, version included.
        assertEquals("1.18.31", session.data.getJSONObject("info").getString("version"))
        assertEquals("crisp-mountain", session.data.getJSONObject("info").getString("slug"))
    }

    @Test
    fun theV1PayloadWrapperIsNotAV2Envelope() {
        val events = ArrayList<V2Api.DurableEvent>()
        V2Api.readEventStream(sseResponse(V2ApiFixtures.SSE_V1_WRAPPER), events::add)

        assertEquals(1, events.size)
        // v1's `{"payload":{"id","type","properties"}}` has no top-level `type`,
        // so a v2 reader cannot silently mistake it for a v2 frame.
        assertEquals("unknown", events[0].type)
        assertEquals("", events[0].id)
        assertEquals(0, events[0].data.length())
    }

    @Test
    fun eventRequestsTargetTheGlobalAndLogStreamsWithAfterReplay() {
        MockWebServer().use { server ->
            val api = api(server)

            // A5 S0.3: the per-session carrier is the experimental log stream,
            // not /api/session/{id}/event (404, absent from openapi.json).
            val log = api.sessionLogRequest("ses_1", after = 3)
            assertEquals("/api/experimental/session/ses_1/log", log.url.encodedPath)
            assertEquals("3", log.url.queryParameter("after"))
            assertEquals("true", log.url.queryParameter("follow"))
            assertEquals("text/event-stream", log.header("Accept"))

            val global = api.globalEventRequest()
            assertEquals("/api/event", global.url.encodedPath)
            assertEquals("text/event-stream", global.header("Accept"))
            assertNull(global.url.queryParameter("after"))
        }
    }

    // ------------------------------------------------------------- parse failures

    /**
     * Measured: every unimplemented `/api/…` path answers `200` + the SPA HTML.
     * If capability detection is skipped the failure must still land in the
     * shell's existing `catch (IOException)` rather than escaping as a raw
     * `org.json.JSONException`.
     */
    @Test
    fun a200HtmlBodyIsReportedAsAnApiExceptionNotParseError() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setHeader("Content-Type", "text/html")
                    .setBody(V2ApiFixtures.SPA_HTML),
            )
            try {
                api(server).sessions()
                fail("expected ApiException")
            } catch (e: V2Api.ApiException) {
                assertEquals(V2Api.JSON_BODY_CODE, e.code)
                assertTrue(e.message!!, e.message!!.contains("not JSON"))
            }
        }
    }

    @Test
    fun forServerRejectsANonHttpUrlInsteadOfThrowing() {
        assertNull(V2Api.forServer("not a url"))
        assertNotNull(V2Api.forServer("http://127.0.0.1:4096"))
    }

    // ------------------------------------------------------------- capability

    @Test
    fun theInfoProbeAcceptsOnlyA2xVersionMarker() {
        val v2 = V2Capability.decideInfo(200, "application/json", V2ApiFixtures.INFO_V2)
        assertEquals(ServerGeneration.V2, v2.generation)
        assertTrue(v2.v2)
        assertEquals("2.0.11", v2.version)
        assertEquals(200, v2.httpStatus)

        // The v1 server answers the same route with the SPA shell — never V2,
        // and V1Transport must never be skipped because of it.
        val spa = V2Capability.decideInfo(200, "text/html", V2ApiFixtures.SPA_HTML)
        assertEquals(ServerGeneration.UNKNOWN, spa.generation)
        assertFalse(spa.v2)
        assertTrue(spa.detail, spa.detail.contains("SPA"))

        // HTML body behind a JSON content-type must be caught too.
        assertEquals(
            ServerGeneration.UNKNOWN,
            V2Capability.decideInfo(200, "application/json", V2ApiFixtures.SPA_HTML).generation,
        )

        // A 1.x version string on /api/info is not v2 (never observed live,
        // but the rule must not misfire on it).
        assertEquals(
            ServerGeneration.UNKNOWN,
            V2Capability.decideInfo(200, "application/json", """{"version":"1.18.31"}""").generation,
        )
        // /api/health is 404 on 2.0.11 — never a v2 signal.
        assertEquals(
            ServerGeneration.UNKNOWN,
            V2Capability.decideInfo(404, "", "").generation,
        )
        assertEquals(
            ServerGeneration.UNKNOWN,
            V2Capability.decideInfo(401, "application/json", "{}").generation,
        )
        assertEquals(
            ServerGeneration.UNKNOWN,
            V2Capability.decideInfo(500, "text/html", "boom").generation,
        )

        // JSON that is simply not an info payload.
        assertEquals(
            ServerGeneration.UNKNOWN,
            V2Capability.decideInfo(200, "application/json", """{"ok":true}""").generation,
        )
        assertEquals(
            ServerGeneration.UNKNOWN,
            V2Capability.decideInfo(200, "application/json", "[]").generation,
        )
        assertEquals(
            ServerGeneration.UNKNOWN,
            V2Capability.decideInfo(200, "application/json", "not json").generation,
        )
    }

    @Test
    fun theHealthProbeAcceptsOnlyAV1VersionMarker() {
        val v1 = V2Capability.decideHealth(200, "application/json", V2ApiFixtures.HEALTH_V1)
        assertEquals(ServerGeneration.V1, v1.generation)
        assertFalse(v1.v2)
        assertEquals("1.18.31", v1.version)
        assertEquals(true, v1.healthy)

        // On a v2 server /global/health is SPA HTML — never V1, so V1Transport
        // is never selected for a v2 server through this leg either.
        assertEquals(
            ServerGeneration.UNKNOWN,
            V2Capability.decideHealth(200, "text/html", V2ApiFixtures.SPA_HTML).generation,
        )
        assertEquals(
            ServerGeneration.UNKNOWN,
            V2Capability.decideHealth(200, "application/json", V2ApiFixtures.SPA_HTML).generation,
        )
        assertEquals(
            ServerGeneration.UNKNOWN,
            V2Capability.decideHealth(404, "application/json", V2ApiFixtures.SESSION_NOT_FOUND).generation,
        )
        assertEquals(
            ServerGeneration.UNKNOWN,
            V2Capability.decideHealth(200, "application/json", V2ApiFixtures.HEALTH).generation,
        )
        assertEquals(
            ServerGeneration.UNKNOWN,
            V2Capability.decideHealth(200, "application/json", "not json").generation,
        )
    }

    @Test
    fun theCoverageTableIsInternallyConsistent() {
        val rows = V2Capability.COVERAGE
        assertTrue("coverage table must not be empty", rows.isNotEmpty())

        val ids = rows.map { it.id }
        assertEquals("ids must be unique", ids.size, ids.toSet().size)
        assertTrue("labels must be non-empty", rows.all { it.label.isNotBlank() })
        assertTrue("notes must be non-empty", rows.all { it.note.isNotBlank() })

        // Only a V2 row may name a v2 route; any other verdict must say "—".
        val offenders = rows.filter { it.support != V2Capability.Support.V2 && it.v2Route != "—" }
        assertEquals("non-V2 rows must not name a v2 route: $offenders", emptyList<Any>(), offenders)
        val missing = rows.filter { it.support == V2Capability.Support.V2 && it.v2Route == "—" }
        assertEquals("V2 rows must name a route: $missing", emptyList<Any>(), missing)
        // A V1_ONLY row is only meaningful when the v1 route is named.
        assertTrue(
            "V1-only rows must name the v1 route",
            rows.filter { it.support == V2Capability.Support.V1_ONLY }.all { it.v1Route != "—" },
        )

        // The verdicts the Lead must not get wrong.
        fun support(id: String) = rows.single { it.id == id }.support
        assertEquals(V2Capability.Support.V2, support("probe.info"))
        assertEquals(V2Capability.Support.V1_ONLY, support("probe.health"))
        assertEquals(V2Capability.Support.V2, support("health"))
        assertEquals(V2Capability.Support.V2, support("session.list"))
        assertEquals(V2Capability.Support.V2, support("session.prompt"))
        assertEquals(V2Capability.Support.V2, support("session.rename"))
        assertEquals(V2Capability.Support.V2, support("session.delete"))
        assertEquals(V2Capability.Support.V2, support("session.diff"))
        assertEquals(V2Capability.Support.V2, support("session.shell"))
        assertEquals(V2Capability.Support.V2, support("session.command"))
        assertEquals(V2Capability.Support.V2, support("message.list"))
        assertEquals(V2Capability.Support.V2, support("fs.read"))
        assertEquals(V2Capability.Support.V2, support("pty.connect"))
        assertEquals(V2Capability.Support.V2, support("event.session"))
        assertEquals(V2Capability.Support.V1_ONLY, support("session.todo"))
        assertEquals(V2Capability.Support.V1_ONLY, support("question.reply"))
        assertEquals(V2Capability.Support.V2, support("defaultModel"))
        assertEquals(V2Capability.Support.V2, support("project.list"))
        assertEquals(V2Capability.Support.V2, support("vcs.branch"))
        assertEquals(V2Capability.Support.V2, support("mcp.status"))

        assertEquals(rows.count { it.support == V2Capability.Support.V2 }, V2Capability.v2Capabilities().size)
        assertEquals(
            rows.count { it.support == V2Capability.Support.V1_ONLY },
            V2Capability.v1OnlyCapabilities().size,
        )
    }

    // ------------------------------------------------------------------ helpers

    private fun sseResponse(body: String): Response = Response.Builder()
        .request(Request.Builder().url("http://127.0.0.1/api/event").build())
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .message("OK")
        .body(body.toResponseBody("text/event-stream".toMediaType()))
        .build()
}
