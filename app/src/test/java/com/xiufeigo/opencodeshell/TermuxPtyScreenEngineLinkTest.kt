package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.terminal.share.DirectSurface
import com.xiufeigo.opencodeshell.ui.terminal.share.PtyApi
import com.xiufeigo.opencodeshell.ui.terminal.share.PtyClient
import com.xiufeigo.opencodeshell.ui.terminal.share.TermuxPtyScreenEngine
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The phase-2 seam, end to end: [PtyClient] driving a [TermuxPtyScreenEngine] over a real (mock)
 * WebSocket. This is the test that proves the two phase-1 decisions now pay off —
 *
 * 1. `answersQueries = true` makes [PtyClient] hand the raw stream to the kernel **un-split**, so
 *    the emulator answers `CSI 5n` *and* `CSI 6n` / DA itself (`research/06` §1.4: those are the
 *    queries phase 1 refused to fabricate);
 * 2. `TermuxPtyScreenEngine.RemoteTerminalIo.updateSize` goes through `PtySizeGate` → `PUT /pty/:id`
 *    without any frame on the WebSocket.
 *
 * `DirectSurface` is used because there is no Android looper on the JVM; production uses
 * `HandlerSurface` (same single-threaded contract).
 */
class TermuxPtyScreenEngineLinkTest {

    private class Fixture(server: MockWebServer) {
        val engine = TermuxPtyScreenEngine(cols = 80, rows = 24, surface = DirectSurface())
        val serverSockets = LinkedBlockingQueue<WebSocket>()
        val received = LinkedBlockingQueue<String>()
        val sentFrames = LinkedBlockingQueue<ByteArray>()
        val opened = CountDownLatch(1)

        private val serverListener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                serverSockets.add(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                received.add(text)
            }
        }

        private val clientListener = object : PtyClient.Listener {
            override fun onState(state: PtyClient.State) {
                if (state == PtyClient.State.OPEN) opened.countDown()
            }

            override fun onCursor(cursor: Long) = Unit

            override fun onOutput(text: String) {
                sentFrames.add(text.toByteArray(Charsets.UTF_8))
            }

            override fun onError(message: String, code: Int?) = Unit
        }

        private val api = PtyApi(
            baseHttpUrl = server.url("/").toString().toHttpUrlOrNull()!!,
            username = "opencode",
            password = "secret",
            http = PtyApi.defaultHttp(),
        )

        val client = PtyClient(
            api = api,
            ptyId = "pty_1",
            engine = engine,
            listener = clientListener,
            socketClient = PtyApi.defaultSocketClient(),
        )

        /** The transport `AndroidView.factory` would hand to `TerminalView.attachIo()`. */
        val io = engine.RemoteTerminalIo(
            writeBytes = { bytes -> client.sendBytes(bytes) },
            resizePty = { cols, rows -> client.resize(cols, rows) },
        )

        init {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path?.contains("connect-token") == true ->
                        MockResponse().setBody("""{"ticket":"tk","expires_in":60}""")
                    request.path?.contains("/connect") == true ->
                        MockResponse().withWebSocketUpgrade(serverListener)
                    else -> MockResponse().setBody("{}")
                }
            }
        }

        fun connect() {
            client.start()
            assertTrue("terminal socket never opened", opened.await(5, TimeUnit.SECONDS))
        }

        fun terminal(): WebSocket =
            serverSockets.poll(5, TimeUnit.SECONDS) ?: throw AssertionError("no server-side socket")
    }

    private fun awaitUntil(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        throw AssertionError("condition never became true")
    }

    private fun escape(text: String) = text.replace("\u001b", "ESC")

    @Test
    fun theKernelReceivesTheRawStreamAndAnswersQueriesItself() {
        MockWebServer().use { server ->
            val fixture = Fixture(server)
            fixture.connect()
            val socket = fixture.terminal()

            // A real program's output: text + SGR + a cursor-position query.
            val payload = "hello \u001b[31mred\u001b[0m"
            socket.send(payload)
            // The renderer is the assertion: wait for the *visible* text, not for a byte count
            // (`PtyClient` forwards each text frame as its own chunk, so a mid-stream sample would
            // otherwise make this flaky).
            awaitUntil { fixture.engine.screenText().contains("hello red") }
            assertEquals(
                "every forwarded byte must reach the emulator",
                payload.length.toLong(),
                fixture.engine.bytesReceived,
            )
            assertTrue(
                "the escape bytes themselves must never be printed",
                !fixture.engine.screenText().contains("[31m"),
            )

            // Phase 1 (placeholder kernel, answersQueries=false) wrote `ESC[0n` back for DSR-5 and
            // *dropped* DSR-6. With the real kernel the emulator answers both — so the server sees
            // a CPR (`ESC[<row>;<col>R`) that only the screen-state owner could produce.
            socket.send("\u001b[6n")
            val cpr = awaitUplink(fixture, { it.contains("\u001b[") && it.endsWith("R") })
            assertTrue("expected a cursor-position report, got: ${escape(cpr)}", cpr.matches(Regex("\u001b\\[\\d+;\\d+R")))

            fixture.client.close()
        }
    }

    @Test
    fun ioWriteSendsInputToThePtyAndFlushesAnswersFirst() {
        MockWebServer().use { server ->
            val fixture = Fixture(server)
            fixture.connect()
            val socket = fixture.terminal()

            // No uplink answer yet; the keypress must reach the PTY verbatim.
            fixture.io.write("ls -la\r".toByteArray(Charsets.US_ASCII))
            assertEquals("ls -la\r", fixture.received.poll(5, TimeUnit.SECONDS))

            // A DSR-5 arrives while the socket is live: its answer goes out through the kernel's
            // TerminalOutput → `PtyClient.sendBytes` (the single up-link outlet).
            socket.send("\u001b[5n")
            assertEquals("\u001b[0n", fixture.received.poll(5, TimeUnit.SECONDS))

            fixture.client.close()
        }
    }

    @Test
    fun ioUpdateSizeResizesTheKernelAndPushesTheGridOverHttp() {
        MockWebServer().use { server ->
            val fixture = Fixture(server)
            fixture.connect()

            // What `TerminalView.updateSize()` calls with `TerminalRenderer` metrics.
            fixture.io.updateSize(columns = 62, rows = 18, cellWidthPx = 9, cellHeightPx = 20)

            assertEquals(62, fixture.engine.emulator.mColumns)
            assertEquals(18, fixture.engine.emulator.mRows)

            // First size is immediate (`PtySizeGate`): a PUT with the same grid, and *never* a
            // WebSocket frame for it.
            val put = awaitRequest(server, "PUT")
            val size = org.json.JSONObject(put.body.readUtf8()).getJSONObject("size")
            assertEquals(62, size.getInt("cols"))
            assertEquals(18, size.getInt("rows"))

            fixture.client.close()
        }
    }

    private fun awaitUplink(fixture: Fixture, predicate: (String) -> Boolean): String {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            val next = fixture.received.poll(200, TimeUnit.MILLISECONDS) ?: continue
            if (predicate(next)) return next
        }
        throw AssertionError("no matching up-link frame arrived")
    }

    private fun awaitRequest(server: MockWebServer, method: String): RecordedRequest {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            val request = server.takeRequest(200, TimeUnit.MILLISECONDS) ?: continue
            if (request.method == method) return request
        }
        throw AssertionError("no $method request recorded")
    }
}
