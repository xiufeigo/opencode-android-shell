package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.v2.V2Api
import com.xiufeigo.opencodeshell.v2.ptyConnect
import com.xiufeigo.opencodeshell.v2.shellGet
import com.xiufeigo.opencodeshell.v2.shellOutput
import com.xiufeigo.opencodeshell.v2.shellRemove
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Background-shell detail/output/close (`groups/shell.ts:39-85`) and the
 * ordinary-PTY WebSocket connect (`groups/pty.ts:117-143`) — asserted on the
 * wire against [MockWebServer], same harness as [V2ApiFsWriteTest].
 *
 * Error shapes (`ShellNotFoundError`, the empty connect-404, the numeric-string
 * `cursor` query) are **measured live** on 2.0.11; the `Shell.Info` /
 * `Shell.Output` success bodies are document-derived (no live shell created —
 * write route).
 */
class V2ApiPtyShellTest {

    private companion object {
        /** `schema/src/shell.ts:38-51`, running shape. */
        const val SHELL_INFO_RUNNING =
            """{"location":{"directory":"/w"},"data":{"id":"sh_0c27c0805001TESTSHELL01","status":"running","command":"sleep 5","cwd":"/w","shell":"bash","file":"/tmp/opencode/shell/sh_0c27c0805001TESTSHELL01.out","pid":4242,"metadata":{"sessionID":"ses_1"},"time":{"started":1789969243559}}}"""

        /** The same row once finished — `exit` and `time.completed` appear. */
        const val SHELL_INFO_DONE =
            """{"location":{"directory":"/w"},"data":{"id":"sh_0c27c0805001TESTSHELL02","status":"exited","command":"echo hi","cwd":"/w","shell":"bash","file":"/tmp/opencode/shell/sh_0c27c0805001TESTSHELL02.out","pid":4243,"exit":0,"metadata":{},"time":{"started":1789969243559,"completed":1789969243777}}}"""

        /** `schema/src/shell.ts:73-80` — mid-stream page, more to come. */
        const val SHELL_OUTPUT_PAGE =
            """{"location":{"directory":"/w"},"data":{"output":"hi\n","cursor":3,"size":10,"truncated":false}}"""

        /** Measured live 2.0.11: `GET /api/shell/{id}` on a missing id. */
        const val SHELL_NOT_FOUND =
            """{"_tag":"ShellNotFoundError","id":"sh_deadbeef0000","message":"Shell command not found: sh_deadbeef0000"}"""

        /** Document shape of a rejected ticket (`ForbiddenError`). */
        const val FORBIDDEN =
            """{"_tag":"ForbiddenError","message":"Invalid PTY connect ticket"}"""
    }

    private fun api(
        server: MockWebServer,
        directory: String? = null,
        username: String? = null,
        password: String? = null,
    ) = V2Api(
        baseHttpUrl = server.url("/").toString().toHttpUrlOrNull()!!,
        username = username,
        password = password,
        http = V2Api.defaultClient(),
        directory = directory,
    )

    // ------------------------------------------------------------------ shell

    @Test
    fun shellGetReadsTheCommandInfoAndSendsTheLocation() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(SHELL_INFO_RUNNING))
            val info = api(server, directory = "/w").shellGet("sh_0c27c0805001TESTSHELL01")

            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/api/shell/sh_0c27c0805001TESTSHELL01", request.requestUrl!!.encodedPath)
            assertEquals("/w", request.requestUrl!!.queryParameter("location[directory]"))

            assertEquals("sh_0c27c0805001TESTSHELL01", info.id)
            assertEquals("running", info.status)
            assertTrue(info.running)
            assertEquals("sleep 5", info.command)
            assertEquals("/w", info.cwd)
            assertEquals("bash", info.shell)
            assertEquals("/tmp/opencode/shell/sh_0c27c0805001TESTSHELL01.out", info.file)
            assertEquals(4242, info.pid)
            assertNull(info.exit)
            assertNull(info.timeCompleted)
            assertEquals(1789969243559L, info.timeStarted)
            assertEquals("ses_1", info.metadata.optString("sessionID", ""))
        }
    }

    @Test
    fun shellGetReadsTheExitFieldsOnceTheCommandFinished() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(SHELL_INFO_DONE))
            val info = api(server).shellGet("sh_0c27c0805001TESTSHELL02")

            assertEquals("exited", info.status)
            assertFalse(info.running)
            assertEquals(0, info.exit)
            assertEquals(1789969243777L, info.timeCompleted)
            assertEquals(0, info.metadata.length())
        }
    }

    @Test
    fun shellGetWithALocationOverrideReplacesTheInstanceDefault() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(SHELL_INFO_RUNNING))
            api(server, directory = "/instance").shellGet("sh_1", locationDirectory = "/override")

            val request = server.takeRequest()
            // One key, the caller's: the override replaces the instance default
            // rather than standing beside it (the server resolves the **first**
            // occurrence, so a default sent first would win — see
            // `ReviewChangesLoadStateTest.theOverrideReplacesTheInstanceDefault…`).
            val directories = request.requestUrl!!.queryParameterValues("location[directory]")
            assertEquals(listOf("/override"), directories)
        }
    }

    @Test
    fun shellRemoveIsADeleteAnsweringNoContent() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            api(server, directory = "/w").shellRemove("sh_1")

            val request = server.takeRequest()
            assertEquals("DELETE", request.method)
            assertEquals("/api/shell/sh_1", request.requestUrl!!.encodedPath)
            assertEquals("/w", request.requestUrl!!.queryParameter("location[directory]"))
        }
    }

    @Test
    fun shellOutputPagesByAbsoluteByteCursorAndSendsCursorAndLimit() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(SHELL_OUTPUT_PAGE))
            val page = api(server, directory = "/w").shellOutput("sh_1", cursor = 3, limit = 4096)

            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/api/shell/sh_1/output", request.requestUrl!!.encodedPath)
            assertEquals("/w", request.requestUrl!!.queryParameter("location[directory]"))
            assertEquals("3", request.requestUrl!!.queryParameter("cursor"))
            assertEquals("4096", request.requestUrl!!.queryParameter("limit"))

            assertEquals("hi\n", page.output)
            assertEquals(3L, page.cursor)
            assertEquals(10L, page.size)
            assertFalse(page.truncated)
            assertTrue("cursor 3 < size 10 keeps the page loop going", page.hasMore)
        }
    }

    @Test
    fun shellOutputWithoutCursorOrLimitSendsNeitherParameter() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(SHELL_OUTPUT_PAGE))
            api(server).shellOutput("sh_1")

            val request = server.takeRequest()
            assertNull(request.requestUrl!!.queryParameter("cursor"))
            assertNull(request.requestUrl!!.queryParameter("limit"))
            assertNull(request.requestUrl!!.queryParameter("location[directory]"))
        }
    }

    @Test
    fun shellGetAndOutputKeepTheShellNotFoundTag() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404).setBody(SHELL_NOT_FOUND))
            try {
                api(server).shellGet("sh_deadbeef0000")
                fail("expected ApiException")
            } catch (e: V2Api.ApiException) {
                assertEquals(404, e.code)
                assertTrue(e.message!!, e.message!!.contains("ShellNotFoundError"))
            }

            server.enqueue(MockResponse().setResponseCode(404).setBody(SHELL_NOT_FOUND))
            try {
                api(server).shellOutput("sh_deadbeef0000")
                fail("expected ApiException")
            } catch (e: V2Api.ApiException) {
                assertEquals(404, e.code)
                assertTrue(e.message!!, e.message!!.contains("ShellNotFoundError"))
            }
        }
    }

    // ------------------------------------------------------------------- pty

    @Test
    fun ptyConnectOpensTheWebSocketUpgradeWithTicketCursorAndLocation() {
        MockWebServer().use { server ->
            val serverSockets = LinkedBlockingQueue<WebSocket>()
            val serverReceived = LinkedBlockingQueue<String>()
            server.enqueue(
                MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        serverSockets.add(webSocket)
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        serverReceived.add(text)
                    }
                }),
            )

            val clientReceived = LinkedBlockingQueue<String>()
            val socket = api(server, directory = "/w", password = "secret").ptyConnect(
                ptyID = "pty_1",
                listener = object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        clientReceived.add(text)
                    }
                },
                ticket = "tk",
                cursor = -1L,
            )

            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/api/pty/pty_1/connect", request.requestUrl!!.encodedPath)
            assertEquals("/w", request.requestUrl!!.queryParameter("location[directory]"))
            assertEquals("-1", request.requestUrl!!.queryParameter("cursor"))
            assertEquals("tk", request.requestUrl!!.queryParameter("ticket"))
            assertEquals("websocket", request.headers["Upgrade"])
            val expected = "Basic " + Base64.getEncoder().encodeToString("opencode:secret".toByteArray())
            assertEquals(expected, request.getHeader("Authorization"))

            val serverSocket = serverSockets.poll(5, TimeUnit.SECONDS) ?: throw AssertionError("no server socket")
            serverSocket.send("banner")
            assertEquals("banner", clientReceived.poll(5, TimeUnit.SECONDS))
            socket.send("dir\r\n")
            assertEquals("dir\r\n", serverReceived.poll(5, TimeUnit.SECONDS))
            socket.cancel()
        }
    }

    @Test
    fun ptyConnectWithoutATicketInventsNone() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {}))
            val socket = api(server).ptyConnect("pty_1", listener = object : WebSocketListener() {})

            val request = server.takeRequest()
            assertEquals("/api/pty/pty_1/connect", request.requestUrl!!.encodedPath)
            assertNull("no ticket obtained ⇒ none invented", request.requestUrl!!.queryParameter("ticket"))
            assertNull(request.requestUrl!!.queryParameter("cursor"))
            assertNull(request.requestUrl!!.queryParameter("location[directory]"))
            socket.cancel()
        }
    }

    @Test
    fun ptyConnectMapsTheMeasuredEmpty404HandshakeToApiException() {
        MockWebServer().use { server ->
            // Measured live 2.0.11: `GET /api/pty/{id}/connect` on a missing id
            // answers 404 with an empty body (before any upgrade work).
            server.enqueue(MockResponse().setResponseCode(404))
            val listenerCalls = LinkedBlockingQueue<String>()
            try {
                api(server).ptyConnect(
                    "pty_gone",
                    listener = object : WebSocketListener() {
                        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                            listenerCalls.add("failure")
                        }
                    },
                )
                fail("expected ApiException")
            } catch (e: V2Api.ApiException) {
                assertEquals(404, e.code)
                assertTrue(e.message!!, e.message!!.startsWith("HTTP 404"))
            }
            assertNull("handshake rejection is the return channel, not a callback", listenerCalls.poll())
        }
    }

    @Test
    fun ptyConnectKeepsTheForbiddenErrorTagOnARejectedHandshake() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403).setBody(FORBIDDEN))
            try {
                api(server).ptyConnect(
                    "pty_1",
                    listener = object : WebSocketListener() {},
                    ticket = "expired",
                )
                fail("expected ApiException")
            } catch (e: V2Api.ApiException) {
                assertEquals(403, e.code)
                assertTrue(e.message!!, e.message!!.contains("ForbiddenError"))
            }
        }
    }
}
