package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.terminal.share.PtyApi
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The **v2 face** of [PtyApi] — the adapter over `V2Api.pty*` (`ptyCreate` →
 * `ptyConnectTicket` → `ptyConnect` → `ptyUpdate` → `ptyRemove` + `ptyGet`).
 * Wire-level assertions against [MockWebServer], same harness as [PtyApiTest]
 * (which pins the untouched v1 face).
 */
class PtyApiV2DelegateTest {

    private fun api(
        server: MockWebServer,
        directory: String? = "/w",
        username: String? = "opencode",
        password: String? = "secret",
    ) = PtyApi(
        baseHttpUrl = server.url("/").toString().toHttpUrlOrNull()!!,
        username = username,
        password = password,
        directory = directory,
        http = PtyApi.defaultHttp(),
        v2 = true,
    )

    private fun envelope(body: String): String = """{"location":{"directory":"/w"},"data":$body}"""

    // ----------------------------------------------------------------- create

    @Test
    fun createGoesThroughPtyCreateOnTheV2Face() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setBody(envelope("""{"id":"pty_9","title":"Terminal 1","command":"/bin/sh","status":"running","pid":42}""")),
            )
            val created = api(server).create(title = "Terminal 1", command = "/bin/sh", cwd = "/w")

            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/pty", request.requestUrl!!.encodedPath)
            assertEquals("/w", request.requestUrl!!.queryParameter("location[directory]"))
            val body = JSONObject(request.body.readUtf8())
            assertEquals("Terminal 1", body.getString("title"))
            assertEquals("/bin/sh", body.getString("command"))
            assertEquals("/w", body.getString("cwd"))

            assertEquals("pty_9", created.id)
            assertEquals("running", created.status)
            assertEquals(42, created.pid)
            assertTrue(created.isRunning)
        }
    }

    @Test
    fun createWithoutAnIdStillRaisesPtyApiException() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(envelope("{}")))
            try {
                api(server).create()
                fail("expected PtyApiException")
            } catch (e: PtyApi.PtyApiException) {
                assertEquals(-1, e.code)
            }
        }
    }

    // ----------------------------------------------------------------- ticket

    @Test
    fun connectTokenDelegatesAndKeepsTheTicketResultMapping() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setBody(envelope("""{"ticket":"uuid-2","expires_in":60}""")),
            )
            assertEquals(
                PtyApi.TicketResult.Ok("uuid-2"),
                api(server).connectToken("pty_1"),
            )
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/pty/pty_1/connect-token", request.requestUrl!!.encodedPath)
            assertEquals("1", request.headers[PtyApi.TICKET_HEADER])

            server.enqueue(MockResponse().setResponseCode(404))
            assertEquals(PtyApi.TicketResult.Unsupported, api(server).connectToken("pty_1"))

            server.enqueue(MockResponse().setResponseCode(403))
            assertEquals(PtyApi.TicketResult.Rejected(403), api(server).connectToken("pty_1"))
        }
    }

    // ------------------------------------------------------------- update/get

    @Test
    fun updateSizeIsPtyUpdateWithRowsAndCols() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(envelope("""{"id":"pty_1"}""")))
            api(server).updateSize("pty_1", cols = 80, rows = 24)

            val request = server.takeRequest()
            assertEquals("PUT", request.method)
            assertEquals("/api/pty/pty_1", request.requestUrl!!.encodedPath)
            val size = JSONObject(request.body.readUtf8()).getJSONObject("size")
            assertEquals(24, size.getInt("rows"))
            assertEquals(80, size.getInt("cols"))
        }
    }

    @Test
    fun existsIsPtyGetAndOnlyA404IsGone() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(envelope("""{"id":"pty_1","status":"running"}""")))
            assertTrue(api(server).exists("pty_1"))

            server.enqueue(MockResponse().setResponseCode(404).setBody("{}"))
            assertFalse(api(server).exists("pty_1"))

            server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
            assertTrue(api(server).exists("pty_1"))
        }
    }

    @Test
    fun removeIsPtyRemove() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            api(server).remove("pty_1")
            val request = server.takeRequest()
            assertEquals("DELETE", request.method)
            assertEquals("/api/pty/pty_1", request.requestUrl!!.encodedPath)
        }
    }

    // -------------------------------------------------------------- websocket

    @Test
    fun socketRequestIsTheV2UpgradeShape() {
        MockWebServer().use { server ->
            val url = api(server).socketRequest("pty_1", ticket = "t", cursor = 512L).url
            assertEquals("/api/pty/pty_1/connect", url.encodedPath)
            assertEquals("/w", url.queryParameter("location[directory]"))
            assertEquals("512", url.queryParameter("cursor"))
            assertEquals("t", url.queryParameter("ticket"))
        }
    }

    @Test
    fun connectBuildsTheWebSocketThroughPtyConnect() {
        MockWebServer().use { server ->
            val opened = CountDownLatch(1)
            server.enqueue(
                MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        opened.countDown()
                    }
                }),
            )
            val socket = api(server).connect(
                id = "pty_1",
                listener = object : WebSocketListener() {},
                ticket = "tk",
                cursor = -1L,
                socketClient = PtyApi.defaultSocketClient(),
            )
            assertTrue(opened.await(5, TimeUnit.SECONDS))

            val upgrade = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("GET", upgrade.method)
            assertEquals("/api/pty/pty_1/connect", upgrade.requestUrl!!.encodedPath)
            assertEquals("tk", upgrade.requestUrl!!.queryParameter("ticket"))
            assertEquals("-1", upgrade.requestUrl!!.queryParameter("cursor"))
            socket.cancel()
        }
    }

    @Test
    fun connectMapsARejectedHandshakeToPtyApiException() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404).setBody(""))
            try {
                api(server).connect(
                    id = "pty_gone",
                    listener = object : WebSocketListener() {},
                    socketClient = PtyApi.defaultSocketClient(),
                    handshakeTimeoutMs = 5_000L,
                )
                fail("expected PtyApiException")
            } catch (e: PtyApi.PtyApiException) {
                assertEquals(404, e.code)
            }
        }
    }

    private fun RecordedRequest.query(name: String): String? = requestUrl!!.queryParameter(name)
}
