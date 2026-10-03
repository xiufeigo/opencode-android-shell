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
 * M2 data-layer additions, kept in their own file so the M1 `OpenCodeClientTest`
 * stays untouched: `PATCH /session/:id` (rename) and the additive `tokens`
 * flattening that feeds the context ring.
 */
class OpenCodeClientRenameTest {

    private fun client(server: MockWebServer) =
        OpenCodeClient(server.url("/").toString().toHttpUrlOrNull()!!, null, null)

    @Test
    fun renameSessionPatchesTitleThenReReadsTheSession() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"data":{"id":"ses_1","title":"Renamed","location":{"directory":"/w"},""" +
                        """"time":{"updated":77}}}""",
                ),
            )
            val c = client(server)
            val session = c.renameSession("ses_1", "Renamed")
            assertEquals("ses_1", session.id)
            assertEquals("Renamed", session.title)
            assertEquals(77L, session.updatedAt)

            val patch = server.takeRequest()
            assertEquals("PATCH", patch.method)
            assertEquals("/api/session/ses_1", patch.requestUrl!!.encodedPath)
            assertEquals("Renamed", JSONObject(patch.body.readUtf8()).getString("title"))

            val reread = server.takeRequest()
            assertEquals("GET", reread.method)
            assertEquals("/api/session/ses_1", reread.requestUrl!!.encodedPath)
            c.close()
        }
    }

    @Test
    fun renameSessionSendsNoLocationScope() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setBody("""{"data":{"id":"ses_1","title":"T"}}"""),
            )
            val c = client(server)
            c.renameSession("ses_1", "T", directory = "/workspace")
            repeat(2) {
                val recorded = server.takeRequest()
                assertNull(recorded.requestUrl!!.queryParameter("location[directory]"))
                assertNull(recorded.requestUrl!!.queryParameter("directory"))
            }
            c.close()
        }
    }

    @Test
    fun tokenTotalFlattensTheUpstreamBuckets() {
        val raw = JSONObject(
            """{"input":100,"output":20,"reasoning":5,"cache":{"read":7,"write":3}}""",
        )
        assertEquals(135L, OpenCodeClient.tokenTotal(raw))
        // A message without usage, or with an all-zero usage, is "unknown".
        assertNull(OpenCodeClient.tokenTotal(null))
        assertNull(OpenCodeClient.tokenTotal(JSONObject("""{"input":0,"output":0}""")))
        assertEquals(4L, OpenCodeClient.tokenTotal(JSONObject("""{"input":4}""")))
    }

    @Test
    fun messagesExposeTheTokenTotalAndTheStoreKeepsIt() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"data":[{"id":"msg_1","time":{"created":1},"type":"assistant",""" +
                        """"tokens":{"input":10,"output":2},""" +
                        """"content":[{"id":"p1","type":"text","text":"hi"}]}]}""",
                ),
            )
            val c = client(server)
            val messages = c.messages("ses_1")
            assertEquals(12L, messages[0].tokens)

            // The store parses the same payload (SSE `message.updated` carries
            // the full info object, so a streaming update keeps the usage).
            val ui = ChatStore.parseMessage(
                JSONObject(
                    """{"info":{"id":"msg_1","role":"assistant","time":{"created":1},""" +
                        """"tokens":{"input":10,"output":2,"cache":{"read":1}}}}""",
                ),
            )
            assertEquals(13L, ui!!.tokens)
            assertTrue(ChatStore.parseMessage(JSONObject("""{"info":{"id":"m2","role":"user"}}"""))!!.tokens == null)
            c.close()
        }
    }
}
