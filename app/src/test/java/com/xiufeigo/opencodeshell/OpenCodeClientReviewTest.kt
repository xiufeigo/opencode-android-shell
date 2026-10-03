package com.xiufeigo.opencodeshell

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MockWebServer coverage for the review panel's client additions:
 * `GET /api/session/:id/diff`, `GET /api/fs/read/<rel>`, `GET /api/vcs` —
 * including the lenient parsing of partial payloads.
 */
class OpenCodeClientReviewTest {

    private fun client(server: MockWebServer) =
        OpenCodeClient(server.url("/").toString().toHttpUrlOrNull()!!, null, null)

    @Test
    fun sessionDiffParsesFileDiffArray() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"data":[
                      {"file":"src/App.kt","patch":"@@ -1,2 +1,3 @@\n line\n+added","additions":1,"deletions":0,"status":"modified"},
                      {"file":"new.md","additions":12,"deletions":0,"status":"added"},
                      {"file":"gone.md","deletions":4,"status":"deleted"}
                    ]}""",
                ),
            )
            val c = client(server)
            val diff = c.sessionDiff("ses_1")
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
            assertEquals("/api/session/ses_1/diff", recorded.requestUrl!!.encodedPath)
            c.close()
        }
    }

    @Test
    fun sessionDiffRejectsPerMessageScope() {
        MockWebServer().use { server ->
            val c = client(server)
            // v2 scopes by commit/message range (from/to), not one messageID:
            // passing one throws instead of silently returning the full diff.
            assertThrows(UnsupportedOperationException::class.java) {
                c.sessionDiff("ses_1", messageID = "msg_9")
            }
            assertEquals(0, server.requestCount)
            c.close()
        }
    }

    @Test
    fun fileContentReadsRawBytesAsTextOrBinary() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("hello\nworld"))
            server.enqueue(MockResponse().setResponseCode(200).setBody(String(byteArrayOf(0x61, 0x62, 0x00, 0x63, 0x64), Charsets.UTF_8)))
            val c = client(server)
            val text = c.fileContent("/w/a.txt")
            assertFalse(text.binary)
            assertEquals("hello\nworld", text.content)
            val binary = c.fileContent("/w/logo.png")
            assertTrue(binary.binary)
            // v2 ships no server-side mime: binary rides base64, mime stays null.
            assertEquals("YWIAY2Q=", binary.content)
            assertEquals("base64", binary.encoding)
            assertNull(binary.mimeType)
            assertEquals("/api/fs/read/w/a.txt", server.takeRequest().requestUrl!!.encodedPath)
            c.close()
        }
    }

    @Test
    fun vcsInfoParsesNestedBranch() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setBody(
                        """{"location":{},"data":{"branch":{"current":"review-panel","default":"main"}}}""",
                    ),
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
            assertEquals("/api/vcs", server.takeRequest().requestUrl!!.encodedPath)
            c.close()
        }
    }
}
