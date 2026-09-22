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
 * `GET /project`, `GET /path` and `GET /file?path=` — the data behind the
 * new-session draft's project picker, Home's projects column and the
 * add-project directory browser (`research/04` §3).
 */
class OpenCodeClientProjectsTest {

    private fun client(server: MockWebServer) =
        OpenCodeClient(server.url("/").toString().toHttpUrlOrNull()!!, null, null)

    @Test
    fun projectsParsesWorktreeVcsAndIconColor() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """[{"id":"p1","worktree":"C:\\src\\app","vcs":"git","icon":{"color":"mint"},""" +
                        """"time":{"created":1,"updated":2},"sandboxes":[]},""" +
                        """{"id":"p2","worktree":"/home/user/site"}]""",
                ),
            )
            val c = client(server)
            val projects = c.projects()
            assertEquals(2, projects.size)
            assertEquals("p1", projects[0].id)
            assertEquals("C:\\src\\app", projects[0].worktree)
            assertEquals("git", projects[0].vcs)
            assertEquals("mint", projects[0].iconColor)
            assertEquals("p2", projects[1].id)
            assertNull(projects[1].vcs)
            assertNull(projects[1].iconColor)
            val recorded = server.takeRequest()
            assertEquals("GET", recorded.method)
            assertEquals("/project", recorded.path)
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
    fun serverPathReadsDirectoryField() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"home":"C:\\Users\\me","state":"C:\\state","config":"C:\\cfg",""" +
                        """"worktree":"C:\\src\\app","directory":"C:\\Windows\\System32"}""",
                ),
            )
            val c = client(server)
            val path: JSONObject = c.serverPath()
            assertEquals("C:\\Windows\\System32", path.optString("directory"))
            assertEquals("/path", server.takeRequest().path)
            c.close()
        }
    }

    @Test
    fun listFilesSendsPathQueryAndParsesNodes() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """[{"name":"src","path":"src","absolute":"C:\\src\\app\\src",""" +
                        """"type":"directory","ignored":false},""" +
                        """{"name":"build","path":"build","absolute":"C:\\src\\app\\build",""" +
                        """"type":"directory","ignored":true},""" +
                        """{"name":"README.md","path":"README.md",""" +
                        """"absolute":"C:\\src\\app\\README.md","type":"file","ignored":false}]""",
                ),
            )
            val c = client(server)
            val nodes = c.listFiles("C:\\src\\app")
            assertEquals(3, nodes.size)
            assertTrue(nodes[0].directory)
            assertTrue(nodes[1].ignored)
            assertEquals("C:\\src\\app\\src", nodes[0].absolute)
            assertEquals("README.md", nodes[2].name)
            val recorded = server.takeRequest()
            assertEquals("/file", recorded.requestUrl!!.encodedPath)
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
    }
}
