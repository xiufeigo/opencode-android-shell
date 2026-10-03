package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.terminal.CreatePtyResult
import com.xiufeigo.opencodeshell.ui.terminal.createPtyAdopted
import com.xiufeigo.opencodeshell.ui.terminal.share.PtyApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * The `POST /pty` → register-tab handshake of `TerminalPanel.createTerminal`
 * ([createPtyAdopted]): whoever takes the id owns the server-side PTY, and a
 * scope that dies between the two still `DELETE`s it under [NonCancellable] —
 * otherwise the coroutine drops the create result at its first suspension and
 * the server keeps an orphan shell nobody will ever release.
 */
class TerminalPtyCreateTest {

    private fun api(server: MockWebServer) = PtyApi(
        baseHttpUrl = server.url("/").toString().toHttpUrlOrNull()!!,
        username = "opencode",
        password = "secret",
        directory = null,
        http = PtyApi.defaultHttp(),
    )

    /** v1 face: `POST /pty` answers a bare `Pty.Info`, `DELETE` a plain 200. */
    private fun MockWebServer.ptyRoutes() {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.method == "POST" && request.path?.substringBefore('?') == "/pty" ->
                    MockResponse().setBody("""{"id":"pty_9"}""")
                request.method == "DELETE" -> MockResponse().setBody("true")
                else -> MockResponse().setResponseCode(404).setBody("{}")
            }
        }
    }

    private fun MockWebServer.take(method: String): RecordedRequest {
        val request = takeRequest(2, TimeUnit.SECONDS)
        assertEquals(method, request?.method)
        return request!!
    }

    @Test
    fun `adopted id keeps ownership and never deletes the pty`() = runBlocking {
        MockWebServer().use { server ->
            server.ptyRoutes()
            val result = createPtyAdopted(api(server), directory = null) { id -> id.uppercase() }
            assertEquals(CreatePtyResult.Adopted("PTY_9"), result)
            server.take("POST")
            assertNull(
                "an adopted tab must not DELETE its own pty",
                server.takeRequest(200, TimeUnit.MILLISECONDS),
            )
        }
    }

    @Test
    fun `refused adoption deletes the just-created pty`() = runBlocking {
        MockWebServer().use { server ->
            server.ptyRoutes()
            val result = createPtyAdopted(api(server), directory = null) { null }
            assertEquals(CreatePtyResult.Rejected, result)
            server.take("POST")
            assertEquals("/pty/pty_9", server.take("DELETE").path?.substringBefore('?'))
        }
    }

    @Test
    fun `failed create reports Failed without any delete`() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    MockResponse().setResponseCode(500).setBody("boom")
            }
            val result = createPtyAdopted(api(server), directory = null) { id -> id }
            assertEquals(CreatePtyResult.Failed, result)
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun `scope death between create and adopt still deletes the orphan`() = runBlocking {
        MockWebServer().use { server ->
            server.ptyRoutes()
            val entered = CompletableDeferred<Unit>()
            val job = launch(Dispatchers.Default) {
                createPtyAdopted(api(server), directory = null) { _ ->
                    entered.complete(Unit)
                    awaitCancellation()
                }
            }
            withTimeout(5_000) { entered.await() }
            // The panel unmounts here: the tab never registers, yet the PTY
            // already exists server-side.
            job.cancelAndJoin()
            server.take("POST")
            assertEquals("/pty/pty_9", server.take("DELETE").path?.substringBefore('?'))
        }
    }
}
