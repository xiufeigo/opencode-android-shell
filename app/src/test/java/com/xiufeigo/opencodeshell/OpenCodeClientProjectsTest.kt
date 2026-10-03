package com.xiufeigo.opencodeshell

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `GET /api/project`, `GET /api/location` and `GET /api/fs/list?path=` — the
 * data behind the new-session draft's project picker, Home's projects column
 * and the add-project directory browser.
 */
class OpenCodeClientProjectsTest {

    private fun client(server: MockWebServer) =
        OpenCodeClient(server.url("/").toString().toHttpUrlOrNull()!!, null, null)

    @Test
    fun projectsParsesCanonicalAsWorktree() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """[{"id":"p1","canonical":"C:\\src\\app",""" +
                        """"time":{"created":1,"updated":2},"sandboxes":[]},""" +
                        """{"id":"p2","canonical":"/home/user/site"},""" +
                        """{"id":"p3","worktree":"/legacy/w"}]""",
                ),
            )
            val c = client(server)
            val projects = c.projects()
            assertEquals(3, projects.size)
            assertEquals("p1", projects[0].id)
            assertEquals("C:\\src\\app", projects[0].worktree)
            assertNull(projects[0].vcs)
            assertNull(projects[0].iconColor)
            assertEquals("/home/user/site", projects[1].worktree)
            assertEquals("/legacy/w", projects[2].worktree)
            val recorded = server.takeRequest()
            assertEquals("GET", recorded.method)
            assertEquals("/api/project", recorded.requestUrl!!.encodedPath)
            c.close()
        }
    }

    @Test
    fun projectsDropsEntriesWithoutWorktree() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """[{"id":"p1"}, {"worktree":"  "}, {"id":"p2","worktree":"/ok"}]""",
                ),
            )
            val c = client(server)
            val projects = c.projects()
            assertEquals(listOf("/ok"), projects.map { it.worktree })
            c.close()
        }
    }

    @Test
    fun serverPathReadsLocationDirectory() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"directory":"C:\\Windows\\System32","project":{"id":"global","directory":"C:\\"}}""",
                ),
            )
            val c = client(server)
            val path: JSONObject = c.serverPath()
            assertEquals("C:\\Windows\\System32", path.optString("directory"))
            assertEquals("/api/location", server.takeRequest().requestUrl!!.encodedPath)
            c.close()
        }
    }

    @Test
    fun listFilesJoinsRelativeRowsOntoTheLocationDirectory() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"location":{"directory":"C:\\src\\app"},"data":[""" +
                        """{"path":"src","type":"directory"},""" +
                        """{"path":"README.md","type":"file"}]}""",
                ),
            )
            val c = client(server)
            val nodes = c.listFiles("C:\\src\\app")
            assertEquals(2, nodes.size)
            assertTrue(nodes[0].directory)
            assertEquals("C:\\src\\app\\src", nodes[0].absolute)
            assertEquals("src", nodes[0].name)
            assertEquals("README.md", nodes[1].name)
            assertEquals("C:\\src\\app\\README.md", nodes[1].absolute)
            val recorded = server.takeRequest()
            assertEquals("/api/fs/list", recorded.requestUrl!!.encodedPath)
            assertEquals("C:\\src\\app", recorded.requestUrl!!.queryParameter("path"))
            c.close()
        }
    }

    @Test
    fun fileNodeFallsBackToPathWhenAbsoluteMissing() {
        val node = OpenCodeClient.FileNode.fromJson(
            JSONObject("""{"name":"d","path":"/rel/d","type":"directory"}"""),
        )
        assertEquals("/rel/d", node!!.absolute)
        assertTrue(node.directory)
        assertNull(OpenCodeClient.FileNode.fromJson(JSONObject("""{"name":"x"}""")))
    }

    @Test
    fun projectFromJsonToleratesIconWithoutColor() {
        val project = OpenCodeClient.Project.fromJson(
            JSONObject("""{"worktree":"/w","icon":{"url":"https://x/i.png"}}"""),
        )
        assertEquals("/w", project!!.worktree)
        assertNull(project.iconColor)
        assertNull(OpenCodeClient.Project.fromJson(JSONObject("""{"id":"p"}""")))
        // The v1 shape (worktree/vcs/icon) still parses alongside `canonical`.
        val legacy = OpenCodeClient.Project.fromJson(
            JSONObject("""{"id":"p1","worktree":"/w","vcs":"git","icon":{"color":"mint"}}"""),
        )
        assertEquals("/w", legacy!!.worktree)
        assertEquals("git", legacy.vcs)
        assertEquals("mint", legacy.iconColor)
    }

    @Test
    fun projectParsesNameWhenPresentMissingOrNull() {
        val named = OpenCodeClient.Project.fromJson(
            JSONObject("""{"id":"p1","canonical":"/w","name":"atlas"}"""),
        )
        assertEquals("atlas", named!!.name)
        val missing = OpenCodeClient.Project.fromJson(
            JSONObject("""{"id":"p1","canonical":"/w"}"""),
        )
        assertEquals("", missing!!.name)
        val nullName = OpenCodeClient.Project.fromJson(
            JSONObject().put("id", "p1").put("canonical", "/w").put("name", JSONObject.NULL),
        )
        assertEquals("", nullName!!.name)
    }

    @Test
    fun projectsWireCarriesProjectNames() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """[{"id":"p1","canonical":"/a","name":"atlas"},""" +
                        """{"id":"p2","canonical":"/b"}]""",
                ),
            )
            val c = client(server)
            val projects = c.projects()
            assertEquals(listOf("atlas", ""), projects.map { it.name })
            c.close()
        }
    }

    @Test
    fun jsonNullNeverSurfacesAsLiteralNull() {
        val n = JSONObject.NULL
        val part = OpenCodeClient.Part.fromJson(
            JSONObject().put("id", n).put("type", n).put("text", n).put("tool", n),
        )
        assertTrue(part.id.startsWith("part:"))
        assertEquals("unknown", part.type)
        assertNull(part.text)
        assertNull(part.toolName)

        val message = OpenCodeClient.Message.fromJson(
            JSONObject().put("id", n).put("sessionID", n).put("role", n).put("agent", n),
        )
        assertEquals("", message.id)
        assertEquals("", message.sessionID)
        assertEquals("user", message.role)
        assertNull(message.agent)

        val session = OpenCodeClient.Session.fromJson(
            JSONObject().put("id", n).put("title", n),
        )
        assertEquals("", session.id)
        assertEquals("Untitled", session.title)

        assertNull(
            OpenCodeClient.Project.fromJson(
                JSONObject().put("id", n).put("worktree", n).put("canonical", n)
                    .put("vcs", n).put("name", n),
            ),
        )
        val project = OpenCodeClient.Project.fromJson(
            JSONObject().put("id", n).put("canonical", "/w").put("vcs", n).put("name", n),
        )
        assertEquals("", project!!.id)
        assertEquals("", project.name)
        assertNull(project.vcs)

        assertNull(
            OpenCodeClient.FileNode.fromJson(
                JSONObject().put("absolute", n).put("path", n).put("name", n),
            ),
        )

        val permission = OpenCodeClient.PermissionRequest.fromJson(
            JSONObject().put("id", "r1").put("sessionID", n).put("permission", n).put("action", n),
        )
        assertEquals("", permission.sessionID)
        assertEquals("", permission.permission)

        val provider = OpenCodeClient.Providers.Provider.fromJson(
            JSONObject().put("id", "p").put("name", n),
        )
        assertEquals("p", provider!!.name)
        val model = OpenCodeClient.Providers.Model.fromJson(
            JSONObject().put("id", "m").put("name", n),
        )
        assertEquals("m", model!!.name)

        val diff = OpenCodeClient.FileDiff.fromJson(
            JSONObject().put("file", n).put("patch", n).put("status", n),
        )
        assertNull(diff.file)
        assertNull(diff.patch)
        assertNull(diff.status)

        val todo = OpenCodeClient.TodoInfo.fromJson(
            JSONObject().put("content", n).put("status", n).put("priority", n),
        )
        assertEquals("", todo.content)
        assertEquals("", todo.status)
        assertEquals("", todo.priority)

        val content = OpenCodeClient.FileContent.fromJson(
            JSONObject().put("type", n).put("content", n).put("diff", n),
        )
        assertEquals("", content.content)
        assertNull(content.diff)

        val vcs = OpenCodeClient.VcsInfo.fromJson(
            JSONObject().put("branch", n).put("default_branch", n),
        )
        assertNull(vcs.branch)
        assertNull(vcs.defaultBranch)

        val agent = OpenCodeClient.Agent.fromJson(
            JSONObject().put("name", n).put("id", "build").put("description", n).put("mode", n),
        )
        assertEquals("build", agent.name)
        assertEquals("", agent.mode)

        assertNull(OpenCodeClient.CommandInfo.fromJson(JSONObject().put("name", n)))
        val command = OpenCodeClient.CommandInfo.fromJson(
            JSONObject().put("name", "c").put("description", n),
        )
        assertEquals("", command!!.description)
        assertNull(OpenCodeClient.SkillInfo.fromJson(JSONObject().put("name", n)))
        val skill = OpenCodeClient.SkillInfo.fromJson(
            JSONObject().put("name", "s").put("description", n),
        )
        assertEquals("", skill!!.description)
    }
}
