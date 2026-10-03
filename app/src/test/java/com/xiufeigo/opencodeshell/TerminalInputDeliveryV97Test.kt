package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.terminal.share.PlaceholderPtyScreenEngine
import com.xiufeigo.opencodeshell.ui.terminal.share.PersistentPtyClient
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * D8 — `persistent session terminal` keystrokes never reached the PTY, while the
 * **docked temporary terminal** on the very same panel echoed normally.
 *
 * The two paths differ in exactly one place, and it is the up-link:
 *
 * | | docked temporary terminal | persistent session terminal |
 * |---|---|---|
 * | client | `PtyClient` | `PersistentPtyClient` (session mode) |
 * | up-link | `sendBytes` → `send` → socket, **no gate** | `sendBytes` → needs `restored` **and** `lastSize`, else queue forever |
 *
 * The bare-persistent branch of `restoreScreen()` established both preconditions
 * from its snapshot; the **session** branch returned right after painting the
 * screen, so it established neither. A session terminal therefore had no frame
 * to build until a resize landed, and — when the daemon sent no
 * `replay_complete` for it — its input stayed queued for good, while the
 * down-link (cursor, `last login`, pid) looked flawless. That is the D8
 * screenshot: perfect screen, silent keys.
 *
 * These pins are pure JVM: no Compose, no Robolectric, just the client against a
 * [MockWebServer] socket.
 */
class TerminalInputDeliveryV97Test {

    private companion object {
        val INFO_JSON =
            """{"data":{"id":"pty_1","title":"T","command":"sh","args":[],"cwd":"/w","status":"running","pid":7,"sessionID":"ses_1","size":{"cols":80,"rows":24},"output":{"head":0,"tail":99}}}"""

        /** `screen.cols/rows` = 80×24 — deliberately ≠ the engine's initial grid. */
        val READ_JSON =
            """{"data":{"ptyID":"pty_1","title":"T","cwd":"/w","screen":{"text":"hello","cols":80,"rows":24,"cursor":{"x":1,"y":0}}}}"""

        val SNAPSHOT_JSON =
            """{"data":{"info":{"id":"pty_1","title":"T","command":"sh","args":[],"cwd":"/w","status":"running","pid":7,"sessionID":"","size":{"cols":80,"rows":24},"output":{"head":0,"tail":42}},"text":"","checkpoint":"${Base64.getEncoder().encodeToString("SNAPSHOT".toByteArray())}","cursor":{"x":0,"y":0}}}"""

        val TICKET_JSON = """{"data":{"ticket":"tk","expires_in":60}}"""
    }

    private class ManualScheduler : PtyClient.Scheduler {
        private class Entry(@Volatile var cancelled: Boolean = false, val run: () -> Unit)

        private val entries = mutableListOf<Entry>()

        override fun schedule(delayMs: Long, task: () -> Unit): PtyClient.Cancellable {
            val entry = synchronized(entries) { Entry(run = task).also { entries.add(it) } }
            return PtyClient.Cancellable { entry.cancelled = true }
        }

        override fun shutdown() = Unit

        fun runNext(): Boolean {
            val entry = synchronized(entries) {
                val i = entries.indexOfFirst { !it.cancelled }
                if (i >= 0) entries.removeAt(i) else null
            } ?: return false
            entry.run()
            return true
        }
    }

    private class Fixture(server: MockWebServer, sessionID: String? = null) {
        val engine = PlaceholderPtyScreenEngine()
        val scheduler = ManualScheduler()
        private val serverSockets = LinkedBlockingQueue<WebSocket>()

        /** Every byte the client put on the wire, in order. */
        val received = LinkedBlockingQueue<ByteArray>()

        val opened = CountDownLatch(1)

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
                    if (state == PtyClient.State.OPEN) opened.countDown()
                }

                override fun onOutput(text: String) = Unit
                override fun onError(message: String, code: Int?) = Unit
            },
            sessionID = sessionID,
            socketClient = PersistentPtyClient.persistentSocketClient(),
            scheduler = scheduler,
            attachmentId = "att_v97",
        )

        init {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path?.substringBefore('?') ?: ""
                    return when {
                        path.endsWith("/connect-token") ->
                            MockResponse().setResponseCode(200).setBody(TICKET_JSON)

                        path.endsWith("/connect") ->
                            MockResponse().withWebSocketUpgrade(serverListener)

                        path.endsWith("/snapshot") ->
                            MockResponse().setResponseCode(200).setBody(SNAPSHOT_JSON)

                        path.endsWith("/terminal/read") ->
                            MockResponse().setResponseCode(200).setBody(READ_JSON)

                        path.contains("persistent-pty/pty_1") ->
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
        assertTrue("condition not met in time", condition())
    }

    /**
     * **The D8 pin.** A session terminal must accept input the moment its socket
     * is open — with no `replay_complete` and with no `resize()` ever called by
     * the view. The frame carries the grid the read reported (80×24).
     */
    @Test
    fun sessionTerminalInputReachesTheWireWithNoReplayCompleteAndNoResize() {
        MockWebServer().use { server ->
            val fixture = Fixture(server, sessionID = "ses_1")
            fixture.client.start()
            assertTrue(fixture.scheduler.runNext())
            val socket = fixture.terminal()
            assertTrue(fixture.opened.await(5, TimeUnit.SECONDS))
            awaitUntil { fixture.engine.preview.contains("hello") }

            // Nothing has replayed and nothing has resized: this is exactly the
            // state a freshly created session terminal sits in when the tester
            // types into it.
            fixture.client.send("echo")

            val frame = fixture.received.poll(5, TimeUnit.SECONDS)
                ?: throw AssertionError("D8: session terminal input never left the client")
            assertArrayEquals(
                "input must be a type-1 frame carrying the read's 80x24 grid",
                byteArrayOf(1, 0, 80, 0, 24) + "echo".toByteArray(),
                frame,
            )
            fixture.client.close()
        }
    }

    /**
     * The grid the read reported is the one frames are built from, so the very
     * first keystroke is already well-formed; a later view-driven resize still
     * takes over (the type-0 frame follows the live grid, not the restore's).
     */
    @Test
    fun sessionTerminalSeedsTheGridFromTheReadAndALaterResizeStillWins() {
        MockWebServer().use { server ->
            val fixture = Fixture(server, sessionID = "ses_1")
            fixture.client.start()
            assertTrue(fixture.scheduler.runNext())
            fixture.terminal()
            assertTrue(fixture.opened.await(5, TimeUnit.SECONDS))

            fixture.client.send("a")
            val first = fixture.received.poll(5, TimeUnit.SECONDS)!!
            assertArrayEquals(byteArrayOf(1, 0, 80, 0, 24, 'a'.code.toByte()), first)

            fixture.client.resize(cols = 100, rows = 30)
            val interact = fixture.received.poll(5, TimeUnit.SECONDS)!!
            assertArrayEquals(byteArrayOf(0, 0, 100, 0, 30), interact)

            fixture.client.send("b")
            val second = fixture.received.poll(5, TimeUnit.SECONDS)!!
            assertArrayEquals(byteArrayOf(1, 0, 100, 0, 30, 'b'.code.toByte()), second)
            fixture.client.close()
        }
    }

    /**
     * **No weakened boundary.** The bare-persistent (snapshot) path has a real
     * socket replay to wait for, and that gate must stay shut until
     * `replay_complete` — the D8 fix is scoped to the session branch and does
     * not relax this one.
     */
    @Test
    fun theSnapshotPathStillQueuesInputUntilReplayComplete() {
        MockWebServer().use { server ->
            val fixture = Fixture(server) // bare persistent — no sessionID
            fixture.client.start()
            assertTrue(fixture.scheduler.runNext())
            val socket = fixture.terminal()
            assertTrue(fixture.opened.await(5, TimeUnit.SECONDS))

            fixture.client.resize(cols = 10, rows = 5)
            fixture.client.send("early")
            assertNull(
                "the snapshot path must not up-link before replay_complete",
                fixture.received.poll(300, TimeUnit.MILLISECONDS),
            )

            socket.send("""{"type":"replay_complete"}""")
            val interact = fixture.received.poll(5, TimeUnit.SECONDS)!!
            assertArrayEquals(byteArrayOf(0, 0, 10, 0, 5), interact)
            val input = fixture.received.poll(5, TimeUnit.SECONDS)!!
            assertArrayEquals(byteArrayOf(1, 0, 10, 0, 5) + "early".toByteArray(), input)
            fixture.client.close()
        }
    }

    /** Both terminal families keep reaching the server through one shared route. */
    @Test
    fun bothPathsOpenTheSameConnectRoute() {
        MockWebServer().use { server ->
            val session = Fixture(server, sessionID = "ses_1")
            session.client.start()
            assertTrue(session.scheduler.runNext())
            session.terminal()
            assertTrue(session.opened.await(5, TimeUnit.SECONDS))
            // Session restore is four requests: terminal/read → info → ticket → connect.
            val recorded = (1..4).map { server.takeRequest(2, TimeUnit.SECONDS)!! }
            assertEquals(
                listOf(
                    "GET /api/experimental/session/ses_1/terminal/read",
                    "GET /api/experimental/persistent-pty/pty_1",
                    "POST /api/experimental/persistent-pty/pty_1/connect-token",
                    "GET /api/experimental/persistent-pty/pty_1/connect",
                ),
                recorded.map { "${it.method} ${it.requestUrl!!.encodedPath}" },
            )
            val upgrade = recorded.last()
            assertEquals("1", upgrade.requestUrl!!.queryParameter("input_protocol"))
            assertEquals("att_v97", upgrade.requestUrl!!.queryParameter("attachment_id"))
            session.client.close()
        }
    }
}
