package com.xiufeigo.opencodeshell

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64

class OpenCodeClientTest {

    private fun client(server: MockWebServer, username: String? = null, password: String? = null) =
        OpenCodeClient(server.url("/").toString().toHttpUrlOrNull()!!, username, password)

    @Test
    fun listSessionsParsesV2EnvelopeAndLocationDirectory() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"data":[{"id":"ses_1","title":"T1","location":{"directory":"/w"},""" +
                        """"time":{"updated":42},"cost":0.5}]}""",
                ),
            )
            val c = client(server)
            val sessions = c.listSessions()
            assertEquals(1, sessions.size)
            assertEquals("ses_1", sessions[0].id)
            assertEquals("T1", sessions[0].title)
            assertEquals("/w", sessions[0].directory)
            assertEquals(42L, sessions[0].updatedAt)
            val recorded = server.takeRequest()
            assertEquals("GET", recorded.method)
            assertEquals("/api/session", recorded.requestUrl!!.encodedPath)
            assertNull(recorded.getHeader("Authorization"))
            c.close()
        }
    }

    @Test
    fun messagesParsesV2RowsNewestFirstIntoChronologicalOrder() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"data":[
                        {"id":"msg_2","time":{"created":200},"type":"assistant","agent":"build",
                         "model":{"id":"gpt-5","providerID":"openai"},
                         "content":[{"id":"p1","type":"text","text":"hello"}]},
                        {"id":"msg_1","time":{"created":100},"type":"user","text":"hi"}]}""",
                ),
            )
            val c = client(server)
            val messages = c.messages("ses_1")
            assertEquals(2, messages.size)
            assertEquals("msg_1", messages[0].id)
            assertEquals("ses_1", messages[0].sessionID)
            assertEquals("user", messages[0].role)
            assertEquals(100L, messages[0].timeCreated)
            assertEquals("hi", messages[0].parts[0].text)
            assertEquals("msg_2", messages[1].id)
            assertEquals("assistant", messages[1].role)
            assertEquals("openai", messages[1].providerID)
            assertEquals("gpt-5", messages[1].modelID)
            assertEquals("build", messages[1].agent)
            assertEquals("hello", messages[1].parts[0].text)
            assertEquals("/api/session/ses_1/message", server.takeRequest().requestUrl!!.encodedPath)
            c.close()
        }
    }

    @Test
    fun basicAuthHeaderIsStandardRfcBase64WhenCredentialsPresent() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("[]"))
            val c = client(server, username = "user", password = "p@ss w")
            c.listSessions()
            val authorization = server.takeRequest().getHeader("Authorization")
            assertTrue("$authorization must start with Basic ", authorization!!.startsWith("Basic "))
            val expected = "Basic " +
                Base64.getEncoder().encodeToString("user:p@ss w".toByteArray(Charsets.UTF_8))
            assertEquals(expected, authorization)
            c.close()
        }
    }

    @Test
    fun directoryQueryParameterIncludedOnlyWhenNotBlank() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"data":[]}"""))
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"data":[]}"""))
            val c = client(server)
            c.listSessions("/workspace")
            assertEquals("/workspace", server.takeRequest().requestUrl!!.queryParameter("directory"))
            c.listSessions("")
            val blanked = server.takeRequest()
            assertNull(blanked.requestUrl!!.queryParameter("directory"))
            assertEquals("/api/session", blanked.requestUrl!!.encodedPath)
            c.close()
        }
    }

    @Test
    fun non2xxResponseThrowsApiException() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404).setBody("nope"))
            val c = client(server)
            try {
                c.listSessions()
                fail("expected ApiException")
            } catch (e: OpenCodeClient.ApiException) {
                assertEquals(404, e.code)
                assertTrue(e.message!!.contains("404"))
            }
            c.close()
        }
    }

    @Test
    fun parseJsonArrayHandlesBareWrappedAndBlankBodies() {
        val bare = OpenCodeClient.parseJsonArray("""[{"a":1},{"a":2}]""")
        assertEquals(2, bare.size)
        assertEquals(1, bare[0].optInt("a"))
        assertEquals(2, bare[1].optInt("a"))

        val wrapped = OpenCodeClient.parseJsonArray("""{"x":[{"b":1}]}""")
        assertEquals(1, wrapped.size)
        assertEquals(1, wrapped[0].optInt("b"))

        assertEquals(0, OpenCodeClient.parseJsonArray("").size)
        assertEquals(0, OpenCodeClient.parseJsonArray("   \n").size)
        assertEquals(0, OpenCodeClient.parseJsonArray("""{"noArray":1}""").size)
    }

    /** `GET /api/fs/find` returns a `{location,data}` envelope of relative paths. */
    @Test
    fun findFilesReadsEnvelopeRowsAndQueryParameter() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"location":{"directory":"/w"},"data":[""" +
                        """{"path":"src/App.kt","type":"file"},""" +
                        """{"path":"README.md","type":"file"}]}""",
                ),
            )
            server.enqueue(
                MockResponse().setResponseCode(200).setBody("""["mixed"]"""),
            )
            val c = client(server)
            val paths = c.findFiles("App", directory = "/w")
            assertEquals(listOf("src/App.kt", "README.md"), paths)
            val recorded = server.takeRequest()
            assertEquals("/api/fs/find", recorded.requestUrl!!.encodedPath)
            assertEquals("App", recorded.requestUrl!!.queryParameter("query"))
            assertEquals("/w", recorded.requestUrl!!.queryParameter("location[directory]"))

            // A bare string array (the v1 shape) still parses.
            assertEquals(listOf("mixed"), c.findFiles("m"))
            c.close()
        }
    }

    /** Assistant messages expose `info.providerID`/`info.modelID` (chip fallback). */
    @Test
    fun messageFromJsonReadsAssistantModelFields() {
        val assistant = OpenCodeClient.Message.fromJson(
            JSONObject(
                """{"info":{"id":"m1","sessionID":"ses_1","role":"assistant","providerID":"openai","modelID":"gpt-5.6-sol-fast","time":{"created":1}},"parts":[]}""",
            ),
        )
        assertEquals("openai", assistant.providerID)
        assertEquals("gpt-5.6-sol-fast", assistant.modelID)

        val user = OpenCodeClient.Message.fromJson(
            JSONObject(
                """{"info":{"id":"m2","sessionID":"ses_1","role":"user","time":{"created":2}},"parts":[]}""",
            ),
        )
        assertNull(user.providerID)
        assertNull(user.modelID)
    }

    /** `GET /api/session/:id` single-session read (title refresh on snapshot). */
    @Test
    fun sessionReadsSingleSessionInfo() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"data":{"id":"ses_9","title":"DRAFT_OK","location":{"directory":"D:\\w"},""" +
                        """"time":{"updated":7}}}""",
                ),
            )
            val c = client(server)
            val s = c.session("ses_9")
            assertEquals("DRAFT_OK", s.title)
            assertEquals("D:\\w", s.directory)
            assertEquals("/api/session/ses_9", server.takeRequest().requestUrl!!.encodedPath)
            c.close()
        }
    }
}
