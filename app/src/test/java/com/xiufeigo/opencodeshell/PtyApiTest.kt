package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.terminal.share.PtyApi
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
 * REST half of the PTY surface (`research/04_data_api.md` §6.1/§6.3 and the URL
 * construction of §7.3). Everything is asserted on the wire, not on the client's
 * internal state.
 */
class PtyApiTest {

    private fun api(
        server: MockWebServer,
        directory: String? = "/w",
        v2: Boolean = false,
        username: String? = "opencode",
        password: String? = "secret",
    ) = PtyApi(
        baseHttpUrl = server.url("/").toString().toHttpUrlOrNull()!!,
        username = username,
        password = password,
        directory = directory,
        http = PtyApi.defaultHttp(),
        v2 = v2,
    )

    @Test
    fun createPostsTitleAndCwdAndParsesTheBarePtyInfo() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setBody("""{"id":"pty_1","title":"Terminal 1","command":"/bin/sh","status":"running","pid":42}"""),
            )
            val created = api(server).create(title = "Terminal 1", cwd = "/w")

            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/pty", request.requestUrl!!.encodedPath)
            assertEquals("/w", request.requestUrl!!.queryParameter("directory"))
            val body = JSONObject(request.body.readUtf8())
            assertEquals("Terminal 1", body.getString("title"))
            assertEquals("/w", body.getString("cwd"))

            assertEquals("pty_1", created.id)
            assertEquals("running", created.status)
            assertEquals(42, created.pid)
            assertNull(created.exitCode)
            assertTrue(created.isRunning)
        }
    }

    @Test
    fun createUnwrapsTheV2EnvelopeAndUsesTheV2Paths() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setBody("""{"location":{"directory":"/w"},"data":{"id":"pty_9","status":"running"}}"""),
            )
            val created = api(server, v2 = true).create()

            val request = server.takeRequest()
            assertEquals("/api/pty", request.requestUrl!!.encodedPath)
            assertEquals("/w", request.requestUrl!!.queryParameter("location[directory]"))
            assertEquals("pty_9", created.id)
        }
    }

    @Test
    fun createWithoutAnIdFails() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            try {
                api(server).create()
                fail("expected PtyApiException")
            } catch (e: PtyApi.PtyApiException) {
                assertEquals(-1, e.code)
            }
        }
    }

    @Test
    fun connectTokenSendsTheMandatoryHeaderAndOrigin() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ticket":"uuid-1","expires_in":60}"""))
            val result = api(server).connectToken("pty_1")

            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/pty/pty_1/connect-token", request.requestUrl!!.encodedPath)
            assertEquals("/w", request.requestUrl!!.queryParameter("directory"))
            assertEquals("1", request.headers[PtyApi.TICKET_HEADER])
            // The ticket check inspects Origin; a native client has to present one.
            assertEquals(PtyApi.originOf(server.url("/")), request.headers["Origin"])
            assertTrue(request.headers["Authorization"]!!.startsWith("Basic "))
            assertEquals(PtyApi.TicketResult.Ok("uuid-1"), result)
        }
    }

    @Test
    fun connectTokenDistinguishesOldServersFromCsrfRejection() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404))
            assertEquals(PtyApi.TicketResult.Unsupported, api(server).connectToken("pty_1"))

            server.enqueue(MockResponse().setResponseCode(405))
            assertEquals(PtyApi.TicketResult.Unsupported, api(server).connectToken("pty_1"))

            server.enqueue(MockResponse().setResponseCode(403))
            assertEquals(PtyApi.TicketResult.Rejected(403), api(server).connectToken("pty_1"))
        }
    }

    @Test
    fun updateSizePutsTheSizeAndNeverUsesTheSocket() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            api(server).updateSize("pty_1", cols = 80, rows = 24)

            val request = server.takeRequest()
            assertEquals("PUT", request.method)
            assertEquals("/pty/pty_1", request.requestUrl!!.encodedPath)
            assertEquals("/w", request.requestUrl!!.queryParameter("directory"))
            val size = JSONObject(request.body.readUtf8()).getJSONObject("size")
            assertEquals(24, size.getInt("rows"))
            assertEquals(80, size.getInt("cols"))
        }
    }

    @Test
    fun existsReportsOnlyADefinitive404AsGone() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"pty_1","status":"running"}"""))
            assertTrue(api(server).exists("pty_1"))

            server.enqueue(MockResponse().setResponseCode(404))
            assertFalse(api(server).exists("pty_1"))

            // A 500 is not evidence that the PTY exited: keep retrying.
            server.enqueue(MockResponse().setResponseCode(500))
            assertTrue(api(server).exists("pty_1"))
        }
    }

    @Test
    fun removeDeletesTheSession() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            api(server).remove("pty_1")
            val request = server.takeRequest()
            assertEquals("DELETE", request.method)
            assertEquals("/pty/pty_1", request.requestUrl!!.encodedPath)
            assertEquals("/w", request.requestUrl!!.queryParameter("directory"))
        }
    }

    @Test
    fun socketRequestCarriesDirectoryCursorAndTicket() {
        MockWebServer().use { server ->
            val request = api(server).socketRequest("pty_1", ticket = "uuid-1", cursor = 512L)
            val url = request.url

            // OkHttp upgrades from the http(s) URL (same shape as NativeWebSocketHub).
            assertEquals("http", url.scheme)
            assertEquals("/pty/pty_1/connect", url.encodedPath)
            assertEquals("/w", url.queryParameter("directory"))
            assertEquals("512", url.queryParameter("cursor"))
            assertEquals("uuid-1", url.queryParameter("ticket"))
            assertNull(url.queryParameter("auth_token"))
            assertTrue(request.header("Authorization")!!.startsWith("Basic "))
        }
    }

    @Test
    fun socketRequestFallsBackToAuthTokenAndOmitsCursorForFullReplay() {
        MockWebServer().use { server ->
            val request = api(server).socketRequest("pty_1", ticket = null, cursor = null)
            val url = request.url

            // Upstream: ticket XOR auth_token, never both.
            assertNull(url.queryParameter("ticket"))
            assertEquals(
                java.util.Base64.getEncoder().encodeToString("opencode:secret".toByteArray()),
                url.queryParameter("auth_token"),
            )
            // cursor omitted = full replay (≤2 MiB server buffer).
            assertNull(url.queryParameter("cursor"))
        }
    }

    @Test
    fun v2SocketRequestUsesTheV2PathsAndQueryNames() {
        MockWebServer().use { server ->
            val url = api(server, v2 = true).socketRequest("pty_1", ticket = "t", cursor = -1L).url
            assertEquals("/api/pty/pty_1/connect", url.encodedPath)
            assertEquals("/w", url.queryParameter("location[directory]"))
            assertEquals("-1", url.queryParameter("cursor"))
            // v2 never carries auth_token upstream.
            assertNull(url.queryParameter("auth_token"))
        }
    }

    @Test
    fun socketRequestWithoutCredentialsHasNoAuthAtAll() {
        MockWebServer().use { server ->
            val request = api(server, username = null, password = null)
                .socketRequest("pty_1", ticket = null, cursor = -1L)
            assertNull(request.header("Authorization"))
            assertNull(request.url.queryParameter("auth_token"))
        }
    }

    @Test
    fun originHeaderIsTheServerOriginWithoutTheDefaultPort() {
        val http = "http://host:8080/x".toHttpUrlOrNull()!!
        assertEquals("http://host:8080", PtyApi.originOf(http))
        val https = "https://host/x".toHttpUrlOrNull()!!
        assertEquals("https://host", PtyApi.originOf(https))
        val nonDefault = "https://host:8443/x".toHttpUrlOrNull()!!
        assertEquals("https://host:8443", PtyApi.originOf(nonDefault))
    }
}
