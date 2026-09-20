package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.terminal.share.PlaceholderPtyScreenEngine
import com.xiufeigo.opencodeshell.ui.terminal.share.PtyApi
import com.xiufeigo.opencodeshell.ui.terminal.share.PtyClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * End-to-end PTY/WebSocket data link against a real (mock) WebSocket server:
 * framing + cursor, up-link, terminal answers, the 100 ms resize debounce over
 * `PUT /pty/:id`, the `min(250 * 2^min(tries,4), 4000)` retry, the 1000/4404
 * close semantics and close = unmount.
 *
 * Timing is injected ([ManualScheduler] + a fake clock), so nothing here sleeps
 * for the debounce/backoff windows.
 */
class PtyClientTest {

    // -------------------------------------------------------------- plumbing

    /** Runs scheduled work only when the test asks for it — no real timers. */
    private class ManualScheduler : PtyClient.Scheduler {
        private class Entry(val delayMs: Long, val run: () -> Unit) {
            var cancelled = false
            var ran = false
        }

        private val entries = ArrayList<Entry>()
        var shutdownCalled = false
            private set

        override fun schedule(delayMs: Long, task: () -> Unit): PtyClient.Cancellable {
            val entry = Entry(delayMs, task)
            synchronized(entries) { entries += entry }
            return PtyClient.Cancellable { entry.cancelled = true }
        }

        override fun shutdown() {
            shutdownCalled = true
        }

        /** Delays of the tasks that are still waiting, in scheduling order. */
        fun delays(): List<Long> = synchronized(entries) {
            entries.filter { !it.cancelled && !it.ran }.map { it.delayMs }
        }

        /** Runs the oldest pending task (which may schedule another one). */
        fun runNext(): Boolean {
            val entry = synchronized(entries) { entries.firstOrNull { !it.cancelled && !it.ran } }
                ?: return false
            entry.ran = true
            entry.run()
            return true
        }

        fun awaitScheduledDelay(delayMs: Long, timeoutMs: Long = 5_000): Boolean {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (delays().contains(delayMs)) return true
                Thread.sleep(10)
            }
            return false
        }
    }

    private class Fixture(server: MockWebServer, private val clock: () -> Long) {
        val engine = PlaceholderPtyScreenEngine()
        val scheduler = ManualScheduler()
        val serverSockets = LinkedBlockingQueue<WebSocket>()
        val received = LinkedBlockingQueue<String>()
        val states = LinkedBlockingQueue<PtyClient.State>()
        val cursors = LinkedBlockingQueue<Long>()
        val errors = LinkedBlockingQueue<String>()
        val opened = CountDownLatch(1)

        // Listeners are declared before the properties that consume them: Kotlin
        // initializes in declaration order, so an `init` block placed first would
        // read a null field.
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
                states.add(state)
                if (state == PtyClient.State.OPEN) opened.countDown()
            }

            override fun onCursor(cursor: Long) {
                cursors.add(cursor)
            }

            override fun onOutput(text: String) = Unit

            override fun onError(message: String, code: Int?) {
                errors.add(message)
            }
        }

        val api: PtyApi = PtyApi(
            baseHttpUrl = server.url("/").toString().toHttpUrlOrNull()!!,
            username = "opencode",
            password = "secret",
            directory = "/w",
            http = PtyApi.defaultHttp(),
        )

        val client: PtyClient = PtyClient(
            api = api,
            ptyId = "pty_1",
            engine = engine,
            listener = clientListener,
            socketClient = PtyApi.defaultSocketClient(),
            scheduler = scheduler,
            clock = clock,
        )

        init {
            // The dispatcher only runs once requests arrive, i.e. long after
            // construction, so it can safely reference `serverListener`.
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

        /** start() + the delayed connect; waits until the socket is OPEN. */
        fun connect() {
            client.start()
            drain()
            assertTrue("terminal socket never opened", opened.await(5, TimeUnit.SECONDS))
        }

        /** Runs every pending scheduled task (including nested ones). */
        fun drain() {
            while (scheduler.runNext()) {
                // Tasks may schedule more work (resize → deferred flush).
            }
        }

        fun terminal(): WebSocket =
            serverSockets.poll(5, TimeUnit.SECONDS) ?: throw AssertionError("no server-side socket")
    }

    private fun metaFrame(cursor: Long): okio.ByteString =
        (byteArrayOf(0x00) + """{"cursor":$cursor}""".toByteArray()).toByteString()

    private fun awaitRequest(server: MockWebServer, method: String): RecordedRequest {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            val request = server.takeRequest(200, TimeUnit.MILLISECONDS) ?: continue
            if (request.method == method) return request
        }
        throw AssertionError("no $method request recorded")
    }

    private fun awaitState(states: LinkedBlockingQueue<PtyClient.State>, expected: PtyClient.State) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            val state = states.poll(100, TimeUnit.MILLISECONDS) ?: continue
            if (state == expected) return
        }
        throw AssertionError("state $expected never reached")
    }

    private fun awaitUntil(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        throw AssertionError("condition never became true")
    }

    private fun putSize(request: RecordedRequest): Pair<Int, Int> {
        val size = JSONObject(request.body.readUtf8()).getJSONObject("size")
        return size.getInt("cols") to size.getInt("rows")
    }

    // ------------------------------------------------------------ down-link

    @Test
    fun downLinkFramesReachTheEngineAndTheMetaCursorIsRecorded() {
        MockWebServer().use { server ->
            val fixture = Fixture(server, clock = { 0L })
            fixture.connect()
            val socket = fixture.terminal()

            // Ticket + upgrade went out with the initial cursor (-1 = new output
            // only) and the one-shot ticket.
            val ticket = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("POST", ticket.method)
            assertEquals("/pty/pty_1/connect-token", ticket.requestUrl!!.encodedPath)
            val upgrade = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("/pty/pty_1/connect", upgrade.requestUrl!!.encodedPath)
            assertEquals("tk", upgrade.requestUrl!!.queryParameter("ticket"))
            assertEquals("-1", upgrade.requestUrl!!.queryParameter("cursor"))

            socket.send("hello ")
            socket.send("\u001B[31mworld\u001B[0m")
            socket.send(metaFrame(42))

            awaitUntil { fixture.engine.preview == "hello world" }
            // "hello " (6) + ESC[31m (5) + "world" (5) + ESC[0m (4) = 20 bytes.
            assertEquals(20L, fixture.engine.bytesReceived)
            assertEquals(42L, fixture.cursors.poll(5, TimeUnit.SECONDS))
            assertEquals(42L, fixture.client.cursor)

            fixture.client.close()
        }
    }

    @Test
    fun anUnparsableMetaFrameKeepsThePreviousCursor() {
        MockWebServer().use { server ->
            val fixture = Fixture(server, clock = { 0L })
            fixture.connect()
            val socket = fixture.terminal()
            socket.send(metaFrame(7))
            assertEquals(7L, fixture.cursors.poll(5, TimeUnit.SECONDS))
            socket.send((byteArrayOf(0x00) + "{oops".toByteArray()).toByteString())
            Thread.sleep(100)
            // Never derived locally, never reset to a bogus value.
            assertEquals(7L, fixture.client.cursor)
            assertTrue(fixture.cursors.isEmpty())
            fixture.client.close()
        }
    }

    // -------------------------------------------------------------- up-link

    @Test
    fun uplinkWritesToThePtyAndAnswersOnlyStateFreeQueries() {
        MockWebServer().use { server ->
            val fixture = Fixture(server, clock = { 0L })
            fixture.connect()
            val socket = fixture.terminal()

            fixture.client.send("ls -la\n")
            assertEquals("ls -la\n", fixture.received.poll(5, TimeUnit.SECONDS))

            // Bytes (the path a VT kernel uses for its answers) are written too.
            fixture.client.sendBytes("中".toByteArray())
            assertEquals("中", fixture.received.poll(5, TimeUnit.SECONDS))

            // DSR-5 needs no screen state → the placeholder answers it upstream.
            socket.send("\u001B[5n")
            assertEquals("\u001B[0n", fixture.received.poll(5, TimeUnit.SECONDS))

            // DSR-6 (cursor position) needs the kernel: forwarding is the phase-2
            // wiring, so nothing may be fabricated here.
            socket.send("\u001B[6n")
            assertNull(fixture.received.poll(300, TimeUnit.MILLISECONDS))

            fixture.client.close()
        }
    }

    // --------------------------------------------------------------- resize

    @Test
    fun resizeIsImmediateOnceThenDebouncedAndCoalescedOverPut() {
        MockWebServer().use { server ->
            var now = 0L
            val fixture = Fixture(server, clock = { now })
            fixture.connect()

            // First size after connect: sent immediately (upstream `pushSize`).
            fixture.client.resize(cols = 80, rows = 24)
            fixture.drain()
            assertEquals(80 to 24, putSize(awaitRequest(server, "PUT")))

            // Inside the 100 ms window: wait, and let a later size win.
            fixture.client.resize(cols = 90, rows = 30)
            assertEquals(listOf(100L), fixture.scheduler.delays())
            fixture.client.resize(cols = 100, rows = 40)
            assertEquals(listOf(100L), fixture.scheduler.delays())

            now = 100
            fixture.drain()
            assertEquals(100 to 40, putSize(awaitRequest(server, "PUT")))

            // Exactly two PUTs: one immediate, one debounced (frames coalesced).
            assertEquals(4, server.requestCount)

            fixture.client.close()
        }
    }

    // ------------------------------------------------------------- lifecycle

    @Test
    fun abnormalCloseRetriesWithTheUpstreamBackoff() {
        MockWebServer().use { server ->
            val fixture = Fixture(server, clock = { 0L })
            fixture.connect()

            fixture.terminal().close(1011, "server restart")

            // First failure → tries = 1 → 250 * 2^1 = 500 ms.
            assertTrue(fixture.scheduler.awaitScheduledDelay(500L))
            awaitState(fixture.states, PtyClient.State.RECONNECTING)
            fixture.client.close()
        }
    }

    @Test
    fun normalExitDoesNotReconnect() {
        MockWebServer().use { server ->
            val fixture = Fixture(server, clock = { 0L })
            fixture.connect()

            fixture.terminal().close(1000, null)

            awaitState(fixture.states, PtyClient.State.EXITED)
            Thread.sleep(100)
            assertTrue("a clean exit must not retry", fixture.scheduler.delays().isEmpty())
            fixture.client.close()
        }
    }

    @Test
    fun sessionNotFoundStopsTheLoop() {
        MockWebServer().use { server ->
            val fixture = Fixture(server, clock = { 0L })
            fixture.connect()

            fixture.terminal().close(4404, "session not found")

            awaitState(fixture.states, PtyClient.State.GONE)
            Thread.sleep(100)
            assertTrue(fixture.scheduler.delays().isEmpty())
            fixture.client.close()
        }
    }

    @Test
    fun closeUnmountsTheClient() {
        MockWebServer().use { server ->
            val fixture = Fixture(server, clock = { 0L })
            fixture.connect()
            fixture.terminal().send("output")

            fixture.client.close()

            assertEquals(PtyClient.State.CLOSED, fixture.client.state)
            assertTrue("the scheduler must be released on unmount", fixture.scheduler.shutdownCalled)
            assertTrue("the engine must be released (close = unmount)", fixture.engine.preview.isEmpty())
            assertEquals(
                "no reconnect may be armed after an explicit close",
                emptyList<Long>(),
                fixture.scheduler.delays(),
            )
        }
    }
}
