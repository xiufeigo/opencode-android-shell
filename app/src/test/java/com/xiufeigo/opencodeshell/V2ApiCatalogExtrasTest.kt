package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.v2.ProjectCommands
import com.xiufeigo.opencodeshell.v2.ProjectIcon
import com.xiufeigo.opencodeshell.v2.V2Api
import com.xiufeigo.opencodeshell.v2.agent
import com.xiufeigo.opencodeshell.v2.debugLocations
import com.xiufeigo.opencodeshell.v2.evictDebugLocation
import com.xiufeigo.opencodeshell.v2.refreshWorktrees
import com.xiufeigo.opencodeshell.v2.updateProject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Catalog single-item routes (`project.ts:22-33`, `agent.ts:24-38`,
 * `worktree.ts:64-76`, `debug.ts:8-31`) — asserted on the wire against
 * [MockWebServer], same harness as [V2ApiFsWriteTest]. Envelope fixtures are
 * trimmed from live 2.0.11 captures where marked; write responses are
 * `UNVERIFIED` live (never sent to a real server).
 */
class V2ApiCatalogExtrasTest {

    private fun api(
        server: MockWebServer,
        directory: String? = null,
    ) = V2Api(
        baseHttpUrl = server.url("/").toString().toHttpUrlOrNull()!!,
        username = null,
        password = null,
        http = V2Api.defaultClient(),
        directory = directory,
    )

    // ------------------------------------------------------------------ fixtures

    /** `Project.Info` bare object — shape Measured live 2026-09-23 (`GET /api/project`). */
    private val projectJson =
        """{"id":"p1","canonical":"/w","name":"Renamed","vcs":"git","time":{"created":1790075402947,"updated":1790075402999},"sandboxes":[]}"""

    /** `agent.get` envelope trimmed from Measured live 2026-09-23 (`build`). */
    private val agentJson =
        """{"location":{"directory":"/w"},"data":{"id":"build","name":"Build","request":{"settings":{},"headers":{},"body":{"store":false}},"description":"The default agent.","mode":"primary","hidden":false,"permissions":[{"action":"*","resource":"*","effect":"allow"}]}}"""

    /** `debug.location` bare array — Measured live 2026-09-23. */
    private val debugLocationsJson = """[{"directory":"/a"},{"directory":"/b"}]"""

    /** `400 WorktreeError` — `worktree.ts:12-22` (`name`+`data`, not `_tag`). */
    private val worktreeError =
        """{"name":"WorktreeError","data":{"message":"Worktree source not found","forceRequired":false}}"""

    /** `404 ProjectNotFoundError` — `protocol/src/errors.ts:114-121` (`_tag`). */
    private val projectNotFound =
        """{"_tag":"ProjectNotFoundError","projectID":"nope","message":"Project not found: nope"}"""

    // ------------------------------------------------------------------ project.update

    @Test
    fun updateProjectPatchesOnlyTheGivenFieldsAndReadsTheBareProject() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(projectJson))
            val updated = api(server, directory = "/ignored").updateProject(projectID = "p1", name = "Renamed")

            val request = server.takeRequest()
            assertEquals("PATCH", request.method)
            assertEquals("/api/project/p1", request.requestUrl!!.encodedPath)
            assertNull("no location query on this route", request.requestUrl!!.queryParameter("location[directory]"))
            assertEquals("application/json", request.headers["Content-Type"]?.substringBefore(";"))
            val sent = JSONObject(request.body.readUtf8())
            assertEquals("Renamed", sent.getString("name"))
            assertFalse(sent.has("canonical"))
            assertFalse(sent.has("icon"))
            assertFalse(sent.has("commands"))

            assertEquals("p1", updated.id)
            assertEquals("/w", updated.canonical)
            assertEquals(1790075402947L, updated.timeCreated)
            assertEquals(1790075402999L, updated.timeUpdated)
            assertEquals("Renamed", updated.raw.optString("name"))
        }
    }

    @Test
    fun updateProjectSendsIconCommandsAndCanonicalStructs() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(projectJson))
            api(server).updateProject(
                projectID = "p1",
                canonical = "/new",
                icon = ProjectIcon(url = "https://e/icon.png", `override` = "i.png", color = "#ff0000"),
                commands = ProjectCommands(start = "./setup.sh"),
            )

            val sent = JSONObject(server.takeRequest().body.readUtf8())
            assertEquals("/new", sent.getString("canonical"))
            assertFalse(sent.has("name"))
            val icon = sent.getJSONObject("icon")
            assertEquals("https://e/icon.png", icon.getString("url"))
            assertEquals("i.png", icon.getString("override"))
            assertEquals("#ff0000", icon.getString("color"))
            assertEquals("./setup.sh", sent.getJSONObject("commands").getString("start"))
        }
    }

    @Test
    fun updateProjectWithNoFieldsSendsAnEmptyObject() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(projectJson))
            api(server).updateProject(projectID = "p1")

            val request = server.takeRequest()
            assertEquals(0, JSONObject(request.body.readUtf8()).length())
            assertEquals(2L, request.bodySize)
        }
    }

    @Test
    fun updateProjectNotFoundBecomesAnApiException() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404).setBody(projectNotFound))
            try {
                api(server).updateProject(projectID = "nope", name = "x")
                fail("expected ApiException")
            } catch (e: V2Api.ApiException) {
                assertEquals(404, e.code)
                assertTrue(e.message!!.contains("Project not found: nope"))
            }
        }
    }

    // ------------------------------------------------------------------ agent.get

    @Test
    fun agentReadsTheSingleAgentFromTheLocationEnvelope() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(agentJson))
            val agent = api(server, directory = "/w").agent("build")

            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/api/agent/build", request.requestUrl!!.encodedPath)
            assertEquals("/w", request.requestUrl!!.queryParameter("location[directory]"))
            assertEquals("build", agent.id)
            assertEquals("Build", agent.name)
            assertEquals("primary", agent.mode)
            assertTrue(agent.primary)
            assertEquals("The default agent.", agent.description)
        }
    }

    @Test
    fun agentOverrideDirectoryWinsAndBareInstanceSendsNone() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(agentJson))
            api(server).agent("build", locationDirectory = "/w")
            assertEquals("/w", server.takeRequest().requestUrl!!.queryParameter("location[directory]"))

            server.enqueue(MockResponse().setResponseCode(200).setBody(agentJson))
            api(server).agent("plan")
            assertNull(server.takeRequest().requestUrl!!.queryParameter("location[directory]"))
        }
    }

    /**
     * The instance directory is a **default**, never a second key: on a handle
     * built with one, a call carrying its own `location[directory]` sends exactly
     * one parameter — the caller's — and a call carrying none falls back to the
     * default. Both halves matter: the server resolves the **first** occurrence of
     * the key (measured live on 2.0.11, see
     * `ReviewChangesLoadStateTest.theOverrideReplacesTheInstanceDefault…`), so a
     * default sent beside the override would silently win and the caller's scope
     * would be dead. Root fix in `V2Api.url`.
     */
    @Test
    fun anOverrideReplacesTheInstanceDirectoryAndABareCallKeepsIt() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(agentJson))
            api(server, directory = "/instance").agent("build", locationDirectory = "/w")

            val scoped = server.takeRequest()
            assertEquals(listOf("/w"), scoped.requestUrl!!.queryParameterValues("location[directory]"))
            assertEquals(1, scoped.requestUrl!!.querySize)

            server.enqueue(MockResponse().setResponseCode(200).setBody(agentJson))
            api(server, directory = "/instance").agent("build")

            val bare = server.takeRequest()
            assertEquals(listOf("/instance"), bare.requestUrl!!.queryParameterValues("location[directory]"))
            assertEquals(1, bare.requestUrl!!.querySize)
        }
    }

    // ------------------------------------------------------------------ worktree.refresh

    @Test
    fun refreshWorktreesPostsProjectIdAndAcceptsNoContent() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            api(server, directory = "/w").refreshWorktrees("p1")

            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/worktree/refresh", request.requestUrl!!.encodedPath)
            assertNull("no location query on this route", request.requestUrl!!.queryParameter("location[directory]"))
            assertEquals("application/json", request.headers["Content-Type"]?.substringBefore(";"))
            assertEquals(JSONObject().put("projectID", "p1").toString(), request.body.readUtf8())
        }
    }

    @Test
    fun refreshWorktreeErrorKeepsTheWorktreeErrorMessage() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(400).setBody(worktreeError))
            try {
                api(server).refreshWorktrees("p1")
                fail("expected ApiException")
            } catch (e: V2Api.ApiException) {
                assertEquals(400, e.code)
                assertTrue(e.message!!.contains("Worktree source not found"))
            }
        }
    }

    // ------------------------------------------------------------------ debug.location

    @Test
    fun debugLocationsReadsTheBareDirectoryArray() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(debugLocationsJson))
            assertEquals(listOf("/a", "/b"), api(server, directory = "/w").debugLocations())

            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/api/debug/location", request.requestUrl!!.encodedPath)
            assertNull(request.requestUrl!!.query)
        }
    }

    @Test
    fun debugLocationsRejectsTheSpaShellBody() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(V2ApiFixtures.SPA_HTML))
            try {
                api(server).debugLocations()
                fail("expected ApiException")
            } catch (e: V2Api.ApiException) {
                assertEquals(V2Api.JSON_BODY_CODE, e.code)
            }
        }
    }

    @Test
    fun evictDebugLocationDeletesTheDefaultThenTheOverrideDirectory() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            api(server, directory = "/w").evictDebugLocation()
            var request = server.takeRequest()
            assertEquals("DELETE", request.method)
            assertEquals("/api/debug/location", request.requestUrl!!.encodedPath)
            assertEquals("/w", request.requestUrl!!.queryParameter("location[directory]"))

            server.enqueue(MockResponse().setResponseCode(204))
            api(server).evictDebugLocation("/other")
            request = server.takeRequest()
            assertEquals("DELETE", request.method)
            assertEquals("/other", request.requestUrl!!.queryParameter("location[directory]"))
        }
    }
}
