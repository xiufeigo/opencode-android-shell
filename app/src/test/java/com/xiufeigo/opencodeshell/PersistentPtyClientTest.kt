package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.terminal.share.PersistentPtyClient
import com.xiufeigo.opencodeshell.ui.terminal.share.PlaceholderPtyScreenEngine
import com.xiufeigo.opencodeshell.ui.terminal.share.PtyClient
import com.xiufeigo.opencodeshell.v2.V2Api
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.ByteString
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * [PersistentPtyClient] — the terminal-pane reconnect sequence
 * (`terminal-pane.tsx:175-188`): `snapshot.checkpointBytes()` into the
 * engine → `persistentPySocketRequest(cursor = info.output.tail, takeover…)` →
 * WebSocket, plus the session-mode screen restore through
 * `readSessionTerminal` (screen text + cursor) and the `input_protocol=1`
 * framed up-link. Asserted on the wire against [MockWebServer], same harness
 * as [PtyClientTest].
 */
class PersistentPtyClientTest {

    private companion object {
        const val CHECKPOINT_TEXT = "SNAPSHOT"
        val CHECKPOINT_B64: String =
            Base64.getEncoder().encodeToString(CHECKPOINT_TEXT.toByteArray())

        /** `PersistentPty.Snapshot` — `output.tail = 42` is the resume cursor. */
        val SNAPSHOT_JSON =
            """{"data":{"info":{"id":"pty_1","title":"T","command":"sh","args":[],"cwd":"/w","status":"running","pid":7,"sessionID":"","size":{"cols":80,"rows":24},"output":{"head":0,"tail":42}},"text":"","checkpoint":"$CHECKPOINT_B64","cursor":{"x":0,"y":0}}}"""

        val INFO_JSON =
            """{"data":{"id":"pty_1","title":"T","command":"sh","args":[],"cwd":"/w","status":"running","pid":7,"sessionID":"ses_1","size":{"cols":80,"rows":24},"output":{"head":0,"tail":99}}}"""

        val READ_JSON =
            """{"data":{"ptyID":"pty_1","title":"T","cwd":"/w","screen":{"text":"hello","cols":80,"rows":24,"cursor":{"x":1,"y":0}}}}"""

        val TICKET_JSON = """{"data":{"ticket":"tk","expires_in":60}}"""

        val NOT_FOUND = """{"_tag":"PtyNotFoundError","ptyID":"pty_1","message":"PTY session not found: pty_1"}"""
    }

    private class ManualScheduler : PtyClient.Scheduler {
        private class Entry(val delayMs: Long, val run: () -> Unit) {
            @Volatile
            var cancelled = false
        }

        private val entries = mutableListOf<Entry>()

        @Volatile
        var shutdownCalled = false

        override fun schedule(delayMs: Long, task: () -> Unit): PtyClient.Cancellable {
            val entry = synchronized(entries) { Entry(delayMs, task).also { entries.add(it) } }
            return PtyClient.Cancellable { entry.cancelled = true }
        }

        override fun shutdown() {
            shutdownCalled = true
        }

        fun delays(): List<Long> = synchronized(entries) {
            entries.filter { !it.cancelled }.map { it.delayMs }
        }

        fun runNext(): Boolean {
            val entry = synchronized(entries) {
                val index = entries.indexOfFirst { !it.cancelled }
                if (index >= 0) entries.removeAt(index) else null
            } ?: return false
            entry.run()
            return true
        }
    }

    private class Fixture(
        server: MockWebServer,
        sessionID: String? = null,
        snapshotCode: Int = 200,
    ) {
        val engine = PlaceholderPtyScreenEngine()
        val scheduler = ManualScheduler()
        val serverSockets = LinkedBlockingQueue<WebSocket>()
        val received = LinkedBlockingQueue<ByteArray>()
        val states = LinkedBlockingQueue<PtyClient.State>()
        val opened = CountDownLatch(1)
        val requests = LinkedBlockingQueue<RecordedRequest>()

        private val serverListener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                serverSockets.add(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                received.add(bytes.toByteArray())
            }
        }

        val api: V2Api = V2Api(
            baseHttpUrl = server.url("/").toString().toHttpUrlOrNull()!!,
            username = "opencode",
            password = "secret",
            http = V2Api.defaultClient(),
            directory = null,
        )

        val client: PersistentPtyClient = PersistentPtyClient(
            api = api,
            ptyId = "pty_1",
            engine = engine,
            listener = object : PersistentPtyClient.Listener {
                override fun onState(state: PtyClient.State) {
                    states.add(state)
                    if (state == PtyClient.State.OPEN) opened.countDown()
                }

                override fun onOutput(text: String) = Unit

                override fun onError(message: String, code: Int?) = Unit
            },
            sessionID = sessionID,
            socketClient = PersistentPtyClient.persistentSocketClient(),
            scheduler = scheduler,
            attachmentId = "att_test",
        )

        init {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests.add(request)
                    val path = request.path?.substringBefore('?') ?: ""
                    return when {
                        path.endsWith("/connect-token") ->
                            MockResponse().setResponseCode(200).setBody(TICKET_JSON)

                        path.endsWith("/connect") ->
                            MockResponse().withWebSocketUpgrade(serverListener)

                        path.endsWith("/snapshot") ->
                            if (snapshotCode == 200) {
                                MockResponse().setResponseCode(200).setBody(SNAPSHOT_JSON)
                            } else {
                                MockResponse().setResponseCode(snapshotCode).setBody(NOT_FOUND)
                            }

                        path.endsWith("/terminal/read") ->
                            MockResponse().setResponseCode(200).setBody(READ_JSON)

                        request.method == "POST" && path.endsWith("/shutdown") ->
                            MockResponse().setResponseCode(204)

                        path.endsWith("/pty_1") || path.contains("persistent-pty/pty_1") ->
                            MockResponse().setResponseCode(200).setBody(INFO_JSON)

                        else -> MockResponse().setResponseCode(404).setBody("{}")
                    }
                }
            }
        }

        fun terminal(): WebSocket = serverSockets.poll(5, TimeUnit.SECONDS)!!
    }

    private fun awaitUntil(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        failOrTrue(condition())
    }

    private fun failOrTrue(result: Boolean) {
        org.junit.Assert.assertTrue("condition not met in time", result)
    }

    private fun paths(server: MockWebServer, count: Int): List<String> {
        val list = mutableListOf<String>()
        repeat(count) {
            val request = server.takeRequest(2, TimeUnit.SECONDS)
            if (request != null) list.add("${request.method} ${request.requestUrl!!.encodedPath}")
        }
        return list
    }

    // ------------------------------------------------------ snapshot restore

    @Test
    fun connectWritesTheCheckpointThenResumesAtTheOutputTail() {
        MockWebServer().use { server ->
            val fixture = Fixture(server)
            fixture.client.start()
            assertTrue(fixture.scheduler.runNext())

            // `terminal-pane.tsx:175-188`: snapshot → ticket → connect at tail.
            val first = server.takeRequest(2, TimeUnit.SECONDS)!!
            val second = server.takeRequest(2, TimeUnit.SECONDS)!!
            val upgrade = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals(
                listOf(
                    "GET /api/experimental/persistent-pty/pty_1/snapshot",
                    "POST /api/experimental/persistent-pty/pty_1/connect-token",
                    "GET /api/experimental/persistent-pty/pty_1/connect",
                ),
                listOf(first, second, upgrade).map { "${it.method} ${it.requestUrl!!.encodedPath}" },
            )
            // The checkpoint bytes are already on the screen.
            awaitUntil { fixture.engine.preview.contains(CHECKPOINT_TEXT) }

            assertEquals("42", upgrade.requestUrl!!.queryParameter("cursor"))
            assertEquals("true", upgrade.requestUrl!!.queryParameter("takeover"))
            assertEquals("1", upgrade.requestUrl!!.queryParameter("input_protocol"))
            assertEquals("tk", upgrade.requestUrl!!.queryParameter("ticket"))
            assertEquals("att_test", upgrade.requestUrl!!.queryParameter("attachment_id"))

            val socket = fixture.terminal()
            assertTrue(fixture.opened.await(5, TimeUnit.SECONDS))
            socket.send("""{"type":"replay_complete"}""")

            // After the replay: resize is an in-band type-0 frame, input a type-1.
            fixture.client.resize(cols = 80, rows = 24)
            fixture.client.send("x")

            val interact = fixture.received.poll(5, TimeUnit.SECONDS)!!
            assertArrayEquals(byteArrayOf(0, 0, 80, 0, 24), interact)
            val input = fixture.received.poll(5, TimeUnit.SECONDS)!!
            assertArrayEquals(byteArrayOf(1, 0, 80, 0, 24, 'x'.code.toByte()), input)

            fixture.client.close()
        }
    }

    // ------------------------------------------------------- session restore

    @Test
    fun sessionTerminalsRestoreFromTheReadScreenAndResumeAtInfoTail() {
        MockWebServer().use { server ->
            val fixture = Fixture(server, sessionID = "ses_1")
            fixture.client.start()
            assertTrue(fixture.scheduler.runNext())

            val read = server.takeRequest(2, TimeUnit.SECONDS)!!
            val info = server.takeRequest(2, TimeUnit.SECONDS)!!
            val token = server.takeRequest(2, TimeUnit.SECONDS)!!
            val upgrade = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals(
                listOf(
                    "GET /api/experimental/session/ses_1/terminal/read",
                    "GET /api/experimental/persistent-pty/pty_1",
                    "POST /api/experimental/persistent-pty/pty_1/connect-token",
                    "GET /api/experimental/persistent-pty/pty_1/connect",
                ),
                listOf(read, info, token, upgrade).map { "${it.method} ${it.requestUrl!!.encodedPath}" },
            )
            // `readSessionTerminal`'s screen text lands in the engine…
            awaitUntil { fixture.engine.preview.contains("hello") }
            // …and the stream resumes at `info.output.tail`.
            assertEquals("99", upgrade.requestUrl!!.queryParameter("cursor"))

            fixture.client.close()
        }
    }

    // ------------------------------------------------------------- lifecycle

    @Test
    fun anAbnormalCloseReconnectsAfterTheUpstreamBackoffAndRestoresAgain() {
        MockWebServer().use { server ->
            val fixture = Fixture(server)
            fixture.client.start()
            assertTrue(fixture.scheduler.runNext())
            val socket = fixture.terminal()
            assertTrue(fixture.opened.await(5, TimeUnit.SECONDS))

            socket.close(1011, "server restart")
            awaitUntil { fixture.states.contains(PtyClient.State.RECONNECTING) }
            // First failure → 250 * 2^1 = 500 ms (PtyProtocol.backoffDelayMs).
            assertEquals(listOf(500L), fixture.scheduler.delays())

            // Drain the first round's requests, then the retry.
            paths(server, 3)
            assertTrue(fixture.scheduler.runNext())
            awaitUntil { fixture.states.contains(PtyClient.State.CONNECTING) }

            // The retry re-runs the whole restore sequence: gone-probe →
            // snapshot → ticket → connect.
            assertEquals(
                listOf(
                    "GET /api/experimental/persistent-pty/pty_1",
                    "GET /api/experimental/persistent-pty/pty_1/snapshot",
                    "POST /api/experimental/persistent-pty/pty_1/connect-token",
                    "GET /api/experimental/persistent-pty/pty_1/connect",
                ),
                paths(server, 4),
            )
            fixture.client.close()
        }
    }

    @Test
    fun aMissingTerminalEndsTheLoopAsGone() {
        MockWebServer().use { server ->
            val fixture = Fixture(server, snapshotCode = 404)
            fixture.client.start()
            assertTrue(fixture.scheduler.runNext())

            awaitUntil { fixture.states.contains(PtyClient.State.GONE) }
            Thread.sleep(50)
            assertTrue("no retry may be armed once the terminal is gone", fixture.scheduler.delays().isEmpty())
            fixture.client.close()
        }
    }

    @Test
    fun anExitedShellKeepsTheScreenAndNeverReconnects() {
        MockWebServer().use { server ->
            val fixture = Fixture(server)
            fixture.client.start()
            assertTrue(fixture.scheduler.runNext())
            val socket = fixture.terminal()
            assertTrue(fixture.opened.await(5, TimeUnit.SECONDS))

            socket.send("""{"type":"exited"}""")
            Thread.sleep(50)
            socket.close(1000, null)

            awaitUntil { fixture.states.contains(PtyClient.State.EXITED) }
            Thread.sleep(50)
            assertTrue("a clean exit must not retry", fixture.scheduler.delays().isEmpty())
            fixture.client.close()
        }
    }

    @Test
    fun closeUnmountsTheClient() {
        MockWebServer().use { server ->
            val fixture = Fixture(server)
            fixture.client.start()
            assertTrue(fixture.scheduler.runNext())
            fixture.terminal()

            fixture.client.close()
            assertEquals(PtyClient.State.CLOSED, fixture.client.state)
            assertTrue(fixture.scheduler.shutdownCalled)
            assertTrue(fixture.engine.preview.isEmpty())
        }
    }

    // ------------------------------------------------------------- shutdown

    @Test
    fun shutdownDaemonPostsTheShutdownRoute() {
        MockWebServer().use { server ->
            val fixture = Fixture(server)
            PersistentPtyClient.shutdownDaemon(fixture.api)
            val request = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("POST", request.method)
            assertEquals("/api/experimental/persistent-pty/shutdown", request.requestUrl!!.encodedPath)
            assertFalse(fixture.scheduler.shutdownCalled)
        }
    }

    // ------------------------------------------------------- input framing

    @Test
    fun inputBeforeTheReplayIsQueuedAndFlushedAfterwards() {
        MockWebServer().use { server ->
            val fixture = Fixture(server)
            fixture.client.start()
            assertTrue(fixture.scheduler.runNext())
            val socket = fixture.terminal()
            assertTrue(fixture.opened.await(5, TimeUnit.SECONDS))

            fixture.client.resize(cols = 10, rows = 5)
            fixture.client.send("early")
            assertNull("no frame may leave before replay_complete", fixture.received.poll(200, TimeUnit.MILLISECONDS))

            socket.send("""{"type":"replay_complete"}""")
            // The restore boundary: the grid is declared first (type-0), then
            // the input queued before the replay flushes (type-1).
            val interact = fixture.received.poll(5, TimeUnit.SECONDS)!!
            assertArrayEquals(byteArrayOf(0, 0, 10, 0, 5), interact)
            val input = fixture.received.poll(5, TimeUnit.SECONDS)!!
            assertArrayEquals(byteArrayOf(1, 0, 10, 0, 5) + "early".toByteArray(), input)
            fixture.client.close()
        }
    }

    private fun assertNull(message: String?, value: Any?) = org.junit.Assert.assertNull(message, value)
}
