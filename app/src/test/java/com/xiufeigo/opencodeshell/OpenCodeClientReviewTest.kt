package com.xiufeigo.opencodeshell

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MockWebServer coverage for the review panel's client additions:
 * `GET /session/:id/diff`, `GET /session/:id/todo`, `GET /file/content`,
 * `GET /vcs` — including the lenient parsing of partial payloads.
 */
class OpenCodeClientReviewTest {

    private fun client(server: MockWebServer) =
        OpenCodeClient(server.url("/").toString().toHttpUrlOrNull()!!, null, null)

    @Test
    fun sessionDiffParsesFileDiffArray() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """[
                      {"file":"src/App.kt","patch":"@@ -1,2 +1,3 @@\n line\n+added","additions":1,"deletions":0,"status":"modified"},
                      {"file":"new.md","additions":12,"deletions":0,"status":"added"},
                      {"file":"gone.md","deletions":4,"status":"deleted"}
                    ]""",
                ),
            )
            val c = client(server)
            val diff = c.sessionDiff("ses_1", directory = "/w")
            assertEquals(3, diff.size)
            assertEquals("src/App.kt", diff[0].file)
            assertEquals("@@ -1,2 +1,3 @@\n line\n+added", diff[0].patch)
            assertEquals(1, diff[0].additions)
            assertEquals("modified", diff[0].status)
            // Missing optional fields stay null, not fabricated.
            assertNull(diff[1].patch)
            assertNull(diff[2].patch)
            val recorded = server.takeRequest()
            assertEquals("GET", recorded.method)
            assertEquals("/session/ses_1/diff?directory=%2Fw", recorded.path)
            c.close()
        }
    }

    @Test
    fun sessionDiffAppendsMessageIdQueryOnlyWhenGiven() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("[]"))
            server.enqueue(MockResponse().setResponseCode(200).setBody("[]"))
            val c = client(server)
            c.sessionDiff("ses_1", messageID = "msg_9")
            c.sessionDiff("ses_1")
            assertEquals("/session/ses_1/diff?messageID=msg_9", server.takeRequest().path)
            assertEquals("/session/ses_1/diff", server.takeRequest().path)
            c.close()
        }
    }

    @Test
    fun sessionTodosParsesLeniently() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """[
                      {"content":"wire the panel","status":"in_progress","priority":"high"},
                      {"content":"ship it"}
                    ]""",
                ),
            )
            val c = client(server)
            val todos = c.sessionTodos("ses_2")
            assertEquals(2, todos.size)
            assertEquals("wire the panel", todos[0].content)
            assertEquals("in_progress", todos[0].status)
            assertEquals("high", todos[0].priority)
            assertEquals("", todos[1].status)
            assertEquals("/session/ses_2/todo", server.takeRequest().path)
            c.close()
        }
    }

    @Test
    fun fileContentParsesTextAndBinaryShapes() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"type":"text","content":"hello\nworld","patch":{"hunks":[]}}""",
                ),
            )
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"type":"binary","content":"","mimeType":"image/png","encoding":"base64"}""",
                ),
            )
            val c = client(server)
            val text = c.fileContent("/w/a.txt")
            assertFalse(text.binary)
            assertEquals("hello\nworld", text.content)
            // Object patches are stringified, not dropped.
            assertEquals("""{"hunks":[]}""", text.patch)
            val binary = c.fileContent("/w/logo.png")
            assertTrue(binary.binary)
            assertEquals("image/png", binary.mimeType)
            assertEquals("base64", binary.encoding)
            assertEquals("/file/content?path=%2Fw%2Fa.txt", server.takeRequest().path)
            c.close()
        }
    }

    @Test
    fun vcsInfoParsesBranchAndDefaultBranch() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setBody("""{"branch":"review-panel","default_branch":"main"}"""),
            )
            server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            val c = client(server)
            val info = c.vcsInfo()
            assertEquals("review-panel", info.branch)
            assertEquals("main", info.defaultBranch)
            // Absent keys stay null — the header hides the chip on null.
            val empty = c.vcsInfo()
            assertNull(empty.branch)
            assertNull(empty.defaultBranch)
            assertEquals("/vcs", server.takeRequest().path)
            c.close()
        }
    }
}
