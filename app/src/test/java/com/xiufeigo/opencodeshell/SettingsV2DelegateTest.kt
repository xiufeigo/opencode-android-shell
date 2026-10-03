package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.settings.ProviderIntegrationRow
import com.xiufeigo.opencodeshell.ui.settings.ProviderWriteResult
import com.xiufeigo.opencodeshell.ui.settings.SettingsPageLoad
import com.xiufeigo.opencodeshell.ui.settings.SettingsServerApi
import com.xiufeigo.opencodeshell.ui.settings.SettingsV2Delegate
import com.xiufeigo.opencodeshell.ui.settings.SettingsV2Writes
import com.xiufeigo.opencodeshell.ui.settings.mcpPageRows
import com.xiufeigo.opencodeshell.ui.settings.projectIDForWorktree
import com.xiufeigo.opencodeshell.ui.settings.projectPageRows
import com.xiufeigo.opencodeshell.ui.settings.removableCredentialIDs
import com.xiufeigo.opencodeshell.ui.settings.skillPageRows
import com.xiufeigo.opencodeshell.ui.settings.worktreePageRows
import com.xiufeigo.opencodeshell.v2.IntegrationDetail
import com.xiufeigo.opencodeshell.v2.ServerGeneration
import com.xiufeigo.opencodeshell.v2.V2Api
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0 settings dispatch: the Projects / Workspaces / Extensions reads go to the
 * [SettingsV2Writes] delegate when the host offers one (v2 server) and fall
 * back to the original v1 unprefixed `load(path, …)` when it is null — v1
 * behaviour byte-identical, v2 never touching the SPA-catch-all routes.
 * The real [SettingsV2Delegate] is exercised over [MockWebServer] with the
 * measured 2.0.11 envelopes (bare `/api/project` and `/api/worktree` arrays —
 * the latter requiring `projectID` — and `{location,data}` mcp/skill catalogs).
 */
class SettingsV2DelegateTest {

    private fun v2api(server: MockWebServer) = V2Api(
        baseHttpUrl = server.url("/").toString().toHttpUrlOrNull()!!,
        username = null,
        password = null,
        http = V2Api.defaultClient(),
    )

    private fun json(body: String) = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    /** 204 — every settings write route answers empty (`NoContent`). */
    private fun noContent() = MockResponse().setResponseCode(204)

    /** `{location,data}` envelope — the shape of the two connect-attempt writes. */
    private fun envelope(body: String) = json("""{"location":{"directory":"/w"},"data":$body}""")

    /** Records read calls. */
    private class FakeSettingsV2(
        val projects: String = "[]",
        val worktrees: String = "[]",
        val mcp: String = "[]",
        val skills: String = "[]",
    ) : SettingsV2Writes {
        val reads = mutableListOf<String>()

        override suspend fun projectsJson(): String {
            reads += "projects"
            return projects
        }

        override suspend fun worktreesJson(): String {
            reads += "worktrees"
            return worktrees
        }

        override suspend fun mcpJson(): String {
            reads += "mcp"
            return mcp
        }

        override suspend fun skillsJson(): String {
            reads += "skills"
            return skills
        }

        override suspend fun pluginsJson(): String? = null
        override suspend fun configJson(): String? = null
        override suspend fun shells(): List<String> = emptyList()
        override suspend fun integrations(): List<ProviderIntegrationRow> = emptyList()
        override suspend fun disconnectProvider(providerID: String) = ProviderWriteResult.Unsupported
        override suspend fun connectProviderKey(providerID: String, key: String) = ProviderWriteResult.Unsupported
        override suspend fun setMcpEnabled(name: String, enabled: Boolean) = ProviderWriteResult.Unsupported
        override suspend fun setServerShell(shell: String) = ProviderWriteResult.Unsupported
        override suspend fun deleteWorktree(directory: String, force: Boolean) = ProviderWriteResult.Unsupported
    }

    // --------------------------------------------------- SettingsServerApi dispatch

    @Test
    fun withADelegateTheFourPagesParseItsBodiesWithoutTouchingTheV1Routes() = runBlocking {
        MockWebServer().use { v1Server ->
            val delegate = FakeSettingsV2(
                projects = """[{"id":"p","canonical":"/w/app","time":{"updated":5},"vcs":"git"}]""",
                worktrees = """[{"directory":"/w/app/.worktrees/x"}]""",
                mcp = """[{"name":"gh","status":{"status":"connected"}}]""",
                skills = """[{"name":"sk","description":"does things"}]""",
            )
            val api = SettingsServerApi(
                serverProvider = { ServerConfigStore.Server("http://127.0.0.1:${v1Server.port}", null, null) },
                v2 = delegate,
            )

            val projects = (api.projects() as SettingsPageLoad.Loaded).rows
            assertEquals(listOf("/w/app"), projects.map { it.worktree })
            assertEquals("git", projects.single().vcs)
            // v2's `canonical` key feeds the same row the v1 `worktree` key did.
            assertEquals("/w/app", projects.single().worktree)

            val worktrees = (api.worktrees() as SettingsPageLoad.Loaded).rows
            assertEquals(listOf("x"), worktrees.map { it.name })

            val mcp = (api.mcpServers() as SettingsPageLoad.Loaded).rows
            assertEquals(listOf("gh"), mcp.map { it.name })
            assertTrue(mcp.single().connected)

            val skills = (api.skills() as SettingsPageLoad.Loaded).rows
            assertEquals(listOf("sk"), skills.map { it.name })

            assertEquals(listOf("projects", "worktrees", "mcp", "skills"), delegate.reads)
            // The v1 unprefixed routes were never asked (they are SPA HTML on 2.x).
            assertEquals(0, v1Server.requestCount)
        }
    }

    @Test
    fun withoutADelegateTheV1UnprefixedRoutesStayByteIdentical() = runBlocking {
        MockWebServer().use { v1Server ->
            v1Server.enqueue(
                json("""[{"id":"global","worktree":"/","time":{"updated":7},"sandboxes":[]}]"""),
            )
            val api = SettingsServerApi(
                serverProvider = { ServerConfigStore.Server("http://127.0.0.1:${v1Server.port}", null, null) },
                v2 = null,
            )
            val rows = (api.projects() as SettingsPageLoad.Loaded).rows
            assertEquals(listOf("/"), rows.map { it.worktree })
            assertEquals("/project", v1Server.takeRequest().requestUrl!!.encodedPath)
        }
    }

    @Test
    fun noServerIsNoServerEvenWithADelegate() = runBlocking {
        val api = SettingsServerApi(serverProvider = { null }, v2 = FakeSettingsV2())
        assertTrue(api.projects() is SettingsPageLoad.NoServer)
        assertTrue(api.worktrees() is SettingsPageLoad.NoServer)
        assertTrue(api.mcpServers() is SettingsPageLoad.NoServer)
        assertTrue(api.skills() is SettingsPageLoad.NoServer)
    }

    // ------------------------------------------------- SettingsV2Delegate over V2Api

    @Test
    fun projectsJsonKeepsTheBareArrayTheParserExpects() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                json("""[{"id":"p1","canonical":"/home/me/app","time":{"created":1,"updated":2},""" +
                    """"sandboxes":[],"vcs":"git"}]"""),
            )
            val body = SettingsV2Delegate(v2api(server)).projectsJson()
            val rows = projectPageRows(body)
            assertEquals(listOf("/home/me/app"), rows.map { it.worktree })
            assertEquals("git", rows.single().vcs)
            assertEquals("/api/project", server.takeRequest().requestUrl!!.encodedPath)
        }
    }

    @Test
    fun worktreesJsonResolvesProjectIdsAndNeverFiresABareWorktreeRequest() = runBlocking {
        MockWebServer().use { server ->
            // Measured: without ?projectID the route answers 400 InvalidRequestError;
            // with one it answers a bare `[{directory}]` array (no `{data}` envelope).
            server.enqueue(json("""[{"id":"p1","canonical":"/w/a"},{"id":"p2","canonical":"/w/b"}]"""))
            server.enqueue(json("""[{"directory":"/w/a/.worktrees/x"}]"""))
            server.enqueue(json("""[]"""))
            val body = SettingsV2Delegate(v2api(server)).worktreesJson()
            assertEquals(listOf("/w/a/.worktrees/x"), worktreePageRows(body).map { it.directory })

            val projects = server.takeRequest().requestUrl!!
            assertEquals("/api/project", projects.encodedPath)
            val first = server.takeRequest().requestUrl!!
            assertEquals("/api/worktree", first.encodedPath)
            assertEquals("p1", first.queryParameter("projectID"))
            val second = server.takeRequest().requestUrl!!
            assertEquals("p2", second.queryParameter("projectID"))
            assertEquals(3, server.requestCount)
        }
    }

    @Test
    fun worktreesJsonWithZeroProjectsAnswersEmptyInsteadOfA400() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(json("""[]"""))
            val body = SettingsV2Delegate(v2api(server)).worktreesJson()
            assertEquals("[]", body)
            // Only the id lookup went out — no projectID-less `/api/worktree`.
            assertEquals(1, server.requestCount)
            assertEquals("/api/project", server.takeRequest().requestUrl!!.encodedPath)
        }
    }

    @Test
    fun mcpAndSkillsUnwrapTheLocationEnvelopeIntoTheExistingParsers() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(json("""{"location":{"directory":"/w"},"data":[{"name":"scb","status":{"status":"connected"}}]}"""))
            server.enqueue(
                json(
                    """{"location":{"directory":"/w"},"data":[{"id":"opencode","name":"OpenCode",""" +
                        """"description":"helps","path":"/x/OpenCode.md","content":"# hi"}]}""",
                ),
            )
            val delegate = SettingsV2Delegate(v2api(server))
            assertEquals(listOf("scb"), mcpPageRows(delegate.mcpJson()).map { it.name })
            assertEquals(listOf("OpenCode"), skillPageRows(delegate.skillsJson()).map { it.name })
            assertEquals("/api/mcp", server.takeRequest().requestUrl!!.encodedPath)
            assertEquals("/api/skill", server.takeRequest().requestUrl!!.encodedPath)
        }
    }

    @Test
    fun theWriteHalfHitsTheMeasuredRoutesOnTheWire() = runBlocking {
        MockWebServer().use { server ->
            val delegate = SettingsV2Delegate(v2api(server))
            server.enqueue(noContent())
            assertEquals(ProviderWriteResult.Done, delegate.setServerShell("bash"))
            server.enqueue(noContent())
            assertEquals(ProviderWriteResult.Done, delegate.setServerShell(""))
            server.enqueue(noContent())
            assertEquals(ProviderWriteResult.Done, delegate.connectProviderKey("prov", "k"))
            server.enqueue(noContent())
            assertEquals(ProviderWriteResult.Done, delegate.setMcpEnabled("gh", true))
            server.enqueue(noContent())
            assertEquals(ProviderWriteResult.Done, delegate.setMcpEnabled("gh", false))
            server.enqueue(noContent())
            assertEquals(ProviderWriteResult.Done, delegate.mcpAddLocal("n", listOf("echo", "hi")))
            server.enqueue(noContent())
            assertEquals(ProviderWriteResult.Done, delegate.mcpRemoveServer("n"))
            server.enqueue(noContent())
            assertEquals(ProviderWriteResult.Done, delegate.pluginUpdate(listOf("t1")))
            server.enqueue(noContent())
            assertEquals(ProviderWriteResult.Done, delegate.renameCredential("c1", "lab"))
            server.enqueue(noContent())
            assertEquals(ProviderWriteResult.Done, delegate.activateCredential("c1"))
            server.enqueue(noContent())
            assertEquals(ProviderWriteResult.Done, delegate.wellknownAdd("https://wk.example"))
            server.enqueue(envelope("""{"attemptID":"att1","url":"https://oauth.example","instructions":"open","mode":"code","time":{"created":1,"expires":2}}"""))
            assertNotNull(delegate.oauthConnect("prov", "m1", "lab"))
            server.enqueue(envelope("""{"attemptID":"att2","time":{"created":1,"expires":2}}"""))
            assertNotNull(delegate.commandConnect("prov", "m2", null))

            var r = server.takeRequest()
            assertEquals("PATCH", r.method)
            assertEquals("/api/experimental/config", r.requestUrl!!.encodedPath)
            assertEquals("bash", JSONObject(r.body.readUtf8()).optString("shell"))

            r = server.takeRequest()
            assertEquals("PATCH", r.method)
            assertTrue(JSONObject(r.body.readUtf8()).isNull("shell"))

            r = server.takeRequest()
            assertEquals("POST", r.method)
            assertEquals("/api/integration/prov/connect/key", r.requestUrl!!.encodedPath)
            assertEquals("k", JSONObject(r.body.readUtf8()).optString("key"))

            r = server.takeRequest()
            assertEquals("POST", r.method)
            assertEquals("/api/experimental/mcp/gh/connect", r.requestUrl!!.encodedPath)

            r = server.takeRequest()
            assertEquals("POST", r.method)
            assertEquals("/api/experimental/mcp/gh/disconnect", r.requestUrl!!.encodedPath)

            r = server.takeRequest()
            assertEquals("PUT", r.method)
            assertEquals("/api/experimental/mcp/n", r.requestUrl!!.encodedPath)
            val config = JSONObject(r.body.readUtf8()).getJSONObject("config")
            assertEquals("local", config.optString("type"))
            assertEquals("echo", config.getJSONArray("command").getString(0))

            r = server.takeRequest()
            assertEquals("DELETE", r.method)
            assertEquals("/api/experimental/mcp/n", r.requestUrl!!.encodedPath)

            r = server.takeRequest()
            assertEquals("POST", r.method)
            assertEquals("/api/plugin/update", r.requestUrl!!.encodedPath)
            assertEquals("t1", JSONObject(r.body.readUtf8()).getJSONArray("targets").getString(0))

            r = server.takeRequest()
            assertEquals("PATCH", r.method)
            assertEquals("/api/credential/c1", r.requestUrl!!.encodedPath)
            assertEquals("lab", JSONObject(r.body.readUtf8()).optString("label"))

            r = server.takeRequest()
            assertEquals("POST", r.method)
            assertEquals("/api/credential/c1/activate", r.requestUrl!!.encodedPath)

            r = server.takeRequest()
            assertEquals("POST", r.method)
            assertEquals("/api/experimental/integration/wellknown", r.requestUrl!!.encodedPath)
            assertEquals("https://wk.example", JSONObject(r.body.readUtf8()).optString("url"))

            r = server.takeRequest()
            assertEquals("POST", r.method)
            assertEquals("/api/integration/prov/connect/oauth", r.requestUrl!!.encodedPath)
            val oauth = JSONObject(r.body.readUtf8())
            assertEquals("m1", oauth.optString("methodID"))
            assertEquals("lab", oauth.optString("label"))

            r = server.takeRequest()
            assertEquals("POST", r.method)
            assertEquals("/api/integration/prov/connect/command", r.requestUrl!!.encodedPath)
            val command = JSONObject(r.body.readUtf8())
            assertEquals("m2", command.optString("methodID"))
            assertTrue(command.isNull("label"))

            assertEquals(13, server.requestCount)
        }
    }

    @Test
    fun disconnectProviderRemovesEveryCredentialConnection() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                envelope(
                    """{"id":"prov","name":"Prov","methods":[],"connections":[""" +
                        """{"type":"credential","id":"c1","label":"API key"},""" +
                        """{"type":"env","name":"PROV_API_KEY"},""" +
                        """{"type":"credential","id":"c2","label":"oauth"}]}""",
                ),
            )
            server.enqueue(noContent())
            server.enqueue(noContent())
            assertEquals(ProviderWriteResult.Done, SettingsV2Delegate(v2api(server)).disconnectProvider("prov"))

            var r = server.takeRequest()
            assertEquals("GET", r.method)
            assertEquals("/api/integration/prov", r.requestUrl!!.encodedPath)
            r = server.takeRequest()
            assertEquals("DELETE", r.method)
            assertEquals("/api/credential/c1", r.requestUrl!!.encodedPath)
            r = server.takeRequest()
            assertEquals("DELETE", r.method)
            assertEquals("/api/credential/c2", r.requestUrl!!.encodedPath)
            assertEquals(3, server.requestCount)
        }
    }

    @Test
    fun disconnectProviderWithNoRemovableCredentialFailsLikeUpstream() = runBlocking {
        MockWebServer().use { server ->
            // upstream `providers.tsx:105-106` throws "No removable credentials found"
            server.enqueue(envelope("""{"id":"prov","name":"Prov","methods":[],"connections":[{"type":"env","name":"K"}]}"""))
            assertEquals(
                ProviderWriteResult.Failed("no-credentials"),
                SettingsV2Delegate(v2api(server)).disconnectProvider("prov"),
            )
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun disconnectProviderReportsARemovalFailure() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(envelope("""{"id":"prov","name":"P","methods":[],"connections":[{"type":"credential","id":"c1"}]}"""))
            server.enqueue(MockResponse().setResponseCode(500))
            assertEquals(
                ProviderWriteResult.Failed("http-500"),
                SettingsV2Delegate(v2api(server)).disconnectProvider("prov"),
            )
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun deleteWorktreeResolvesItsProjectFromTheCanonicalDirectory() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(json("""[{"id":"p1","canonical":"/w/a"},{"id":"p2","canonical":"/w/b"}]"""))
            server.enqueue(noContent())
            server.enqueue(noContent())
            val delegate = SettingsV2Delegate(v2api(server))
            assertEquals(ProviderWriteResult.Done, delegate.deleteWorktree("/w/a/", force = true))

            server.takeRequest() // GET /api/project — the inventory is never probed
            val r = server.takeRequest()
            assertEquals("DELETE", r.method)
            assertEquals("/api/worktree", r.requestUrl!!.encodedPath)
            val body = JSONObject(r.body.readUtf8())
            assertEquals("p1", body.optString("projectID"))
            assertEquals("/w/a/", body.optString("directory"))
            assertTrue(body.getBoolean("force"))
            // v68: the delete reconciles the saved inventory behind it.
            val refresh = server.takeRequest()
            assertEquals("POST", refresh.method)
            assertEquals("/api/worktree/refresh", refresh.requestUrl!!.encodedPath)
            assertEquals("p1", JSONObject(refresh.body.readUtf8()).optString("projectID"))
            assertEquals(3, server.requestCount)
        }
    }

    @Test
    fun deleteWorktreeFallsBackToEachProjectWorktreeInventory() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(json("""[{"id":"p1","canonical":"/w/a"},{"id":"p2","canonical":"/w/b"}]"""))
            server.enqueue(json("""[]"""))
            server.enqueue(json("""[{"directory":"/elsewhere/x"}]"""))
            server.enqueue(noContent())
            server.enqueue(noContent())
            val delegate = SettingsV2Delegate(v2api(server))
            assertEquals(ProviderWriteResult.Done, delegate.deleteWorktree("/elsewhere/x", force = false))

            var r = server.takeRequest()
            assertEquals("/api/project", r.requestUrl!!.encodedPath)
            r = server.takeRequest()
            assertEquals("p1", r.requestUrl!!.queryParameter("projectID"))
            r = server.takeRequest()
            assertEquals("p2", r.requestUrl!!.queryParameter("projectID"))
            r = server.takeRequest()
            assertEquals("DELETE", r.method)
            val body = JSONObject(r.body.readUtf8())
            assertEquals("p2", body.optString("projectID"))
            assertEquals(false, body.getBoolean("force"))
            // v68: the delete reconciles the saved inventory behind it.
            r = server.takeRequest()
            assertEquals("POST", r.method)
            assertEquals("/api/worktree/refresh", r.requestUrl!!.encodedPath)
            assertEquals("p2", JSONObject(r.body.readUtf8()).optString("projectID"))
            assertEquals(5, server.requestCount)
        }
    }

    @Test
    fun deleteWorktreeWithoutAnOwningProjectFailsWithoutAWrite() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(json("""[{"id":"p1","canonical":"/w/a"}]"""))
            server.enqueue(json("""[]"""))
            assertEquals(
                ProviderWriteResult.Failed("no-project"),
                SettingsV2Delegate(v2api(server)).deleteWorktree("/nope", force = false),
            )
            // only the id lookups went out — no blind `DELETE /api/worktree`
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun removableCredentialIDsKeepOnlyCredentialConnectionIds() {
        val connections = listOf(
            IntegrationDetail.Connection("credential", "c1", "API key", null, JSONObject()),
            IntegrationDetail.Connection("env", null, null, "PROV_API_KEY", JSONObject()),
            IntegrationDetail.Connection("credential", null, null, null, JSONObject()),
        )
        assertEquals(listOf("c1"), removableCredentialIDs(connections))
    }

    @Test
    fun projectIDForWorktreeMatchesPathsTheWayMatchKeyDoes() {
        val candidates = listOf(
            "p1" to "/w/A",
            "p2" to "C:\\src\\Repo",
            "p3" to "c:\\src\\repo\\.worktrees\\feature",
        )
        assertEquals("p1", projectIDForWorktree("/w/A/", candidates))
        // POSIX keeps case; a Windows drive path folds separators and case.
        assertNull(projectIDForWorktree("/w/a", candidates))
        assertEquals("p2", projectIDForWorktree("c:/src/repo", candidates))
        assertEquals("p3", projectIDForWorktree("C:\\src\\REPO\\.worktrees\\Feature", candidates))
        assertNull(projectIDForWorktree("", candidates))
        assertNull(projectIDForWorktree("/missing", candidates))
    }

    // ------------------------------------------------------------- forHost gating

    @Test
    fun forHostOffersADelegateOnlyForACompleteKnownV2Server() {
        val complete = ServerConfigStore.Server("http://127.0.0.1:4095", null, null)
        assertNull(SettingsV2Delegate.forHost(ServerGeneration.UNKNOWN, complete))
        assertNull(SettingsV2Delegate.forHost(ServerGeneration.V2, null))
        assertNull(SettingsV2Delegate.forHost(ServerGeneration.V2, ServerConfigStore.Server("127.0.0.1:4095", null, null)))
        assertNotNull(SettingsV2Delegate.forHost(ServerGeneration.V2, complete))
    }
}
