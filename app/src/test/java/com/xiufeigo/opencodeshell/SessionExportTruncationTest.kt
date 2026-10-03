package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.export.SessionExport
import com.xiufeigo.opencodeshell.v2.V2Api
import com.xiufeigo.opencodeshell.v2.V2Transport
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The whole export chain for a transcript longer than the message drain's page
 * cap: `V2Api.messagesRawPage` reports the truncation, `V2Transport.
 * fetchExportFrame` carries it on the frame, and `SessionExport.serialize`
 * writes it into the payload the user receives.
 *
 * Before this, the drain silently stopped at `maxPages` and the exported file
 * looked exactly like a complete one — a fidelity loss the reader of the JSON
 * could not see (the low-risk half of the data-chain wrap-up list).
 */
class SessionExportTruncationTest {

    /**
     * `GET /api/session/ses_1/message` answers a page whose `cursor.next` keeps
     * pointing at `cur_2` — i.e. the server always offers more, so the drain can
     * only stop at its own cap. Every other path (the session read) answers the
     * session row.
     */
    private val neverEndingDrain = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.requestUrl!!.encodedPath
            val body = if (path.endsWith("/message")) {
                """{"data":[{"id":"msg_1","time":{"created":1},"text":"hi","type":"user"}],""" +
                    """"cursor":{"previous":null,"next":"cur_2"}}"""
            } else {
                """{"data":{"id":"ses_1","title":"Truncated"}}"""
            }
            return MockResponse().setResponseCode(200).setBody(body)
        }
    }

    private fun transport(server: MockWebServer) = V2Transport(
        V2Api(
            baseHttpUrl = server.url("/").toString().toHttpUrlOrNull()!!,
            username = null,
            password = null,
            http = V2Api.defaultClient(),
            directory = null,
        ),
    )

    @Test
    fun aCappedDrainEndsUpInTheExportedJson() {
        MockWebServer().use { server ->
            server.dispatcher = neverEndingDrain

            val frame = transport(server).fetchExportFrame("ses_1", directory = null)

            assertTrue("the frame reports what the row list cannot", frame.truncated)
            // The drain's default cap (`V2Api.messagesRawPage(maxPages = 128)`):
            // a never-ending cursor means every page is read and then the cap
            // stops it, so the row count equals the page count.
            assertEquals(128, frame.truncatedPages)
            assertEquals(frame.truncatedPages, frame.messages.size)
            assertTrue(frame.messages.all { it.getString("id") == "msg_1" })

            val payload = JSONObject(SessionExport.serialize(frame))
            assertTrue(payload.has(SessionExport.TRUNCATED_KEY))
            assertTrue(payload.getJSONObject(SessionExport.TRUNCATED_KEY).getBoolean("messages"))
            assertEquals(frame.truncatedPages, payload.getJSONObject(SessionExport.TRUNCATED_KEY).getInt("pages"))
            assertEquals("ses_1", payload.getJSONObject("info").optString("id"))
            assertEquals(frame.messages.size, payload.getJSONArray("messages").length())
        }
    }

    @Test
    fun aTranscriptTheServerEndsByItselfCarriesNoNote() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"data":{"id":"ses_1"}}"""))
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"data":[{"id":"msg_1","time":{"created":1},"text":"hi","type":"user"}],""" +
                        """"cursor":{"previous":null,"next":null}}""",
                ),
            )

            val frame = transport(server).fetchExportFrame("ses_1", directory = null)

            assertFalse(frame.truncated)
            assertEquals(
                setOf("info", "messages"),
                JSONObject(SessionExport.serialize(frame)).keys().asSequence().toSet(),
            )
        }
    }
}
