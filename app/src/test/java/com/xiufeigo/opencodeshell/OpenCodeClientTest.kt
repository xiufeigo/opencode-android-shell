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
    fun promptAsyncPostsTextAndFilePartsToPromptAsyncPath() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            val c = client(server)
            c.promptAsync(
                "ses_1",
                "hi",
                listOf(
                    ChatStore.UiPart(
                        id = "att1",
                        type = "file",
                        mime = "image/png",
                        filename = "a.png",
                        url = "data:image/png;base64,AAA",
                    ),
                ),
            )
            val recorded = server.takeRequest()
            assertEquals("POST", recorded.method)
            assertEquals("/session/ses_1/prompt_async", recorded.path)
            val body = JSONObject(recorded.body.readUtf8())
            val parts = body.getJSONArray("parts")
            assertEquals(2, parts.length())
            val text = parts.getJSONObject(0)
            assertEquals("text", text.getString("type"))
            assertEquals("hi", text.getString("text"))
            val file = parts.getJSONObject(1)
            assertEquals("file", file.getString("type"))
            assertEquals("image/png", file.getString("mime"))
            assertEquals("a.png", file.getString("filename"))
            assertEquals("data:image/png;base64,AAA", file.getString("url"))
            c.close()
        }
    }

    @Test
    fun listSessionsParsesNestedTimeUpdated() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """[{"id":"ses_1","title":"T1","directory":"/w","time":{"updated":42}}]""",
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
            assertEquals("/session", recorded.path)
            assertNull(recorded.getHeader("Authorization"))
            c.close()
        }
    }

    @Test
    fun messagesParsesInfoWrapperAndNestedTimeCreated() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """[{"info":{"id":"msg_1","sessionID":"ses_1","role":"assistant","time":{"created":123}},""" +
                        """"parts":[{"id":"p1","type":"text","text":"hello"}]}]""",
                ),
            )
            val c = client(server)
            val messages = c.messages("ses_1")
            assertEquals(1, messages.size)
            assertEquals("msg_1", messages[0].id)
            assertEquals("ses_1", messages[0].sessionID)
            assertEquals("assistant", messages[0].role)
            assertEquals(123L, messages[0].timeCreated)
            assertEquals("hello", messages[0].parts[0].text)
            assertEquals("/session/ses_1/message", server.takeRequest().path)
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
            server.enqueue(MockResponse().setResponseCode(200).setBody("[]"))
            server.enqueue(MockResponse().setResponseCode(200).setBody("[]"))
            val c = client(server)
            c.listSessions("/workspace")
            assertEquals("/workspace", server.takeRequest().requestUrl!!.queryParameter("directory"))
            c.listSessions("")
            val blanked = server.takeRequest()
            assertNull(blanked.requestUrl!!.queryParameter("directory"))
            assertEquals("/session", blanked.path)
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

    /** `/find/file` returns a bare array of path strings on the wire. */
    @Test
    fun findFilesReadsStringArrayAndQueryParameter() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(200).setBody("""["src/App.kt","README.md"]"""),
            )
            server.enqueue(
                MockResponse().setResponseCode(200).setBody("""["mixed"]"""),
            )
            val c = client(server)
            val paths = c.findFiles("App", directory = "/w")
            assertEquals(listOf("src/App.kt", "README.md"), paths)
            val recorded = server.takeRequest()
            assertEquals("/find/file", recorded.requestUrl!!.encodedPath)
            assertEquals("App", recorded.requestUrl!!.queryParameter("query"))
            assertEquals("/w", recorded.requestUrl!!.queryParameter("directory"))

            // Object entries with a "path" field are tolerated too.
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

    /** `GET /session/:id` single-session read (title refresh on snapshot). */
    @Test
    fun sessionReadsSingleSessionInfo() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"id":"ses_9","title":"DRAFT_OK","directory":"D:\\w","time":{"updated":7}}""",
                ),
            )
            val c = client(server)
            val s = c.session("ses_9")
            assertEquals("DRAFT_OK", s.title)
            assertEquals("D:\\w", s.directory)
            assertEquals("/session/ses_9", server.takeRequest().path)
            c.close()
        }
    }
}
