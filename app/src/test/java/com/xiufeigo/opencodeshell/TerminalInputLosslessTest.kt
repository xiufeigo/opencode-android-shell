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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * v37 tA: the terminal **silently swallowed the first key after a prompt**
 * (`cd /tmp` executed as `d /tmp`; two `KEYCODE_C` then `XYZ` produced `cXYZ`),
 * and sending a space first "fixed" it — with the space itself eaten.
 *
 * Two independent holes on the key → PTY path can produce that:
 *
 * 1. **Framework touch mode.** Tapping a terminal leaves the window in touch
 *    mode; the *next* key event is then spent by `ViewRootImpl
 *    .checkForLeavingTouchModeAndConsume` — a navigation key (space, tab, enter,
 *    dpad) is consumed outright, and a typing key runs `leaveTouchMode()` →
 *    `restoreDefaultFocus()`, which hands the keyboard to the window's default
 *    focusable view *before* the key is dispatched. Either way exactly one key is
 *    lost after engaging the terminal, which is why a throw-away space made the
 *    rest of the line arrive. The tap path therefore has to leave touch mode
 *    itself (`requestFocusFromTouch()`), so the next key is an ordinary key.
 * 2. **The app's up-link dropped frames silently.** `PtyClient.send` returned
 *    without a word whenever the socket was not `OPEN` (or when `WebSocket.send`
 *    refused the frame — OkHttp answers `false` once a close frame is enqueued),
 *    and `PersistentPtyClient.sendBytes` dropped input when the replay was not
 *    finished or the grid size was not known yet. A keystroke the view *did*
 *    handle could vanish there.
 *
 * The behaviour tests below drive the real [PtyClient] against a mock WebSocket
 * server; the source pins hold the rest of the chain (the vendored view's tap
 * and `ACTION_MULTIPLE` paths, the Compose slot's `attachIo` wiring, the
 * persistent client's queueing) that a JVM test cannot execute without Android.
 */
class TerminalInputLosslessTest {

    // ------------------------------------------------------------ up-link queue

    private class Fixture(server: MockWebServer) {
        val engine = PlaceholderPtyScreenEngine()
        val received = LinkedBlockingQueue<String>()
        val opened = CountDownLatch(1)

        /** Runs scheduled work only when the test asks for it — no real timers. */
        class ManualScheduler : PtyClient.Scheduler {
            private val pending = ArrayList<Pair<Long, () -> Unit>>()

            override fun schedule(delayMs: Long, task: () -> Unit): PtyClient.Cancellable {
                synchronized(pending) { pending += delayMs to task }
                return PtyClient.Cancellable.NONE
            }

            /** Runs every pending task (including ones scheduled by a task). */
            fun drain() {
                while (true) {
                    val next = synchronized(pending) {
                        if (pending.isEmpty()) return else pending.removeAt(0)
                    }
                    next.second()
                }
            }
        }

        val scheduler = ManualScheduler()

        private val serverListener = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                received.add(text)
            }
        }

        private val clientListener = object : PtyClient.Listener {
            override fun onState(state: PtyClient.State) {
                if (state == PtyClient.State.OPEN) opened.countDown()
            }

            override fun onCursor(cursor: Long) = Unit

            override fun onOutput(text: String) = Unit

            override fun onError(message: String, code: Int?) = Unit
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

        /** Runs the connect the manual scheduler is holding back. */
        fun connect() {
            scheduler.drain()
            assertTrue("terminal socket never opened", opened.await(5, TimeUnit.SECONDS))
        }

        fun nextFrame(): String =
            received.poll(5, TimeUnit.SECONDS) ?: throw AssertionError("no up-link frame arrived")
    }

    private fun awaitUntil(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        throw AssertionError("condition never became true")
    }

    @Test
    fun `a keystroke typed before the socket is open is delivered, not dropped`() {
        MockWebServer().use { server ->
            val fixture = Fixture(server)
            // The user's first key can arrive while the socket is still
            // connecting: `start()` schedules the connect, the key does not wait
            // for it. This used to return from `send` without a trace.
            fixture.client.start()
            fixture.client.sendBytes("c".toByteArray(Charsets.UTF_8))
            assertTrue("the key must be queued, not discarded", fixture.client.queuedInputBytes > 0)

            fixture.connect()
            assertEquals("c", fixture.nextFrame())
            awaitUntil { fixture.client.queuedInputBytes == 0 }
            fixture.client.close()
        }
    }

    @Test
    fun `queued keys keep their order and are not overtaken by a later key`() {
        MockWebServer().use { server ->
            val fixture = Fixture(server)
            fixture.client.start()
            fixture.client.sendBytes("c".toByteArray(Charsets.UTF_8))
            fixture.client.sendBytes("d".toByteArray(Charsets.UTF_8))
            fixture.connect()
            // `send` flushes the queue before the new frame, so the shell sees
            // "c", "d", " " — the order the user typed them in.
            fixture.client.sendBytes(" ".toByteArray(Charsets.UTF_8))

            assertEquals("c", fixture.nextFrame())
            assertEquals("d", fixture.nextFrame())
            assertEquals(" ", fixture.nextFrame())
            fixture.client.close()
        }
    }

    @Test
    fun `the type-ahead queue is bounded`() {
        MockWebServer().use { server ->
            val fixture = Fixture(server)
            fixture.client.start()
            val chunk = ByteArray(512) { 'x'.code.toByte() }
            repeat(PtyClient.PENDING_INPUT_LIMIT / chunk.size + 8) {
                fixture.client.sendBytes(chunk)
            }
            val queued = fixture.client.queuedInputBytes
            assertTrue(
                "queue must stay bounded, was $queued",
                queued <= PtyClient.PENDING_INPUT_LIMIT,
            )
            fixture.client.close()
        }
    }

    @Test
    fun `closing the tab drops the queue instead of replaying it elsewhere`() {
        MockWebServer().use { server ->
            val fixture = Fixture(server)
            fixture.client.start()
            fixture.client.sendBytes("c".toByteArray(Charsets.UTF_8))
            fixture.client.close()
            assertEquals(0, fixture.client.queuedInputBytes)
        }
    }

    // ------------------------------------------------------------ source pins

    private fun source(vararg candidates: String): String =
        candidates.map { File(it) }.firstOrNull { it.isFile }?.readText()
            ?: error("missing source: ${candidates.first()}")

    private fun terminalView(): String = source(
        "src/thirdparty/termux/terminal-view/src/main/java/com/termux/view/TerminalView.java",
        "app/src/thirdparty/termux/terminal-view/src/main/java/com/termux/view/TerminalView.java",
    )

    private fun terminalPanel(): String = source(
        "src/main/java/com/xiufeigo/opencodeshell/ui/terminal/TerminalPanel.kt",
        "app/src/main/java/com/xiufeigo/opencodeshell/ui/terminal/TerminalPanel.kt",
    )

    private fun ptyClient(): String = source(
        "src/main/java/com/xiufeigo/opencodeshell/ui/terminal/share/PtyClient.kt",
        "app/src/main/java/com/xiufeigo/opencodeshell/ui/terminal/share/PtyClient.kt",
    )

    private fun persistentPtyClient(): String = source(
        "src/main/java/com/xiufeigo/opencodeshell/ui/terminal/share/PersistentPtyClient.kt",
        "app/src/main/java/com/xiufeigo/opencodeshell/ui/terminal/share/PersistentPtyClient.kt",
    )

    @Test
    fun `engaging the terminal leaves touch mode so the next key is an ordinary key`() {
        val src = terminalView()
        // `View.requestFocusFromTouch()` → `ViewRootImpl.ensureTouchMode(false)`:
        // the tap itself clears touch mode, so the framework's
        // `checkForLeavingTouchModeAndConsume` never spends the next key on the
        // transition (`leaveTouchMode()` → `restoreDefaultFocus()`).
        assertTrue(
            "the tap path must leave touch mode",
            src.contains("requestFocusFromTouch();"),
        )
        val tap = src.substringAfter("public boolean onSingleTapUp(MotionEvent event) {")
            .substringBefore("return false;")
        assertTrue("the tap handler is the one that focuses", tap.contains("requestFocusFromTouch();"))
        assertTrue("and the reason is recorded", src.contains("checkForLeavingTouchModeAndConsume"))
        // Timing: `onSingleTapUp` only runs after the double-tap timeout, so the
        // finger-up path has to leave touch mode as well — otherwise a key pressed
        // right after the tap still lands in the touch-mode window.
        val up = src.substringAfter("public boolean onUp(MotionEvent event) {")
            .substringBefore("mScrollRemainder = 0.0f;")
        assertTrue("lifting the finger leaves touch mode", up.contains("requestFocusFromTouch();"))
        assertTrue(
            "…but only while the terminal already owns the keyboard",
            up.contains("hasFocus() && isInTouchMode()"),
        )
    }

    @Test
    fun `a character burst is written to the transport instead of being dropped`() {
        val src = terminalView()
        assertTrue(
            "ACTION_MULTIPLE arrives in onKeyMultiple, not onKeyDown",
            src.contains("public boolean onKeyMultiple(int keyCode, int repeatCount, KeyEvent event)"),
        )
        val multiple = src.substringAfter("public boolean onKeyMultiple(int keyCode, int repeatCount, KeyEvent event) {")
            .substringBefore("\n    }")
        assertTrue("the burst reaches the PTY", multiple.contains("mIo.write("))
        assertTrue("the dead onKeyDown branch is gone", !src.contains("event.getAction() == KeyEvent.ACTION_MULTIPLE"))
    }

    @Test
    fun `the view writes printable keys straight to the transport`() {
        val src = terminalView()
        // No per-prompt state sits on the write path: a printable key reaches
        // the transport through `inputCodePoint` → `mIo.write`.
        assertTrue(src.contains("mIo.write(TerminalSession.encodeCodePoint(altDown, codePoint));"))
        assertTrue(src.contains("if (mIo == null) return;"))
    }

    private fun termuxEngine(): String = source(
        "src/main/java/com/xiufeigo/opencodeshell/ui/terminal/share/TermuxPtyScreenEngine.kt",
        "app/src/main/java/com/xiufeigo/opencodeshell/ui/terminal/share/TermuxPtyScreenEngine.kt",
    )

    @Test
    fun `the compose slot attaches the transport the keys are written to`() {
        val src = terminalPanel()
        // factory → attachIo(io): the view's `mIo` is the runtime's transport.
        assertTrue(src.contains("runtime.io?.let { io -> view.attachIo(io) }"))
        assertTrue(src.contains("if (io != null && view.mIo !== io) view.attachIo(io)"))
        // …and the transport's up-link is the socket's `sendBytes`.
        assertTrue(src.contains("writeBytes = { bytes -> client.sendBytes(bytes) }"))
        assertTrue(termuxEngine().contains("override fun write(data: ByteArray)"))
    }

    @Test
    fun `the ordinary pty client queues and checks every send`() {
        val src = ptyClient()
        assertTrue("queued, not dropped", src.contains("if (ws == null || currentState != State.OPEN) {"))
        assertTrue("a refused frame is re-queued", src.contains("if (!ws.send(text)) enqueueInput(text)"))
        assertTrue("flushed on open", src.contains("flushPendingInput()"))
        assertTrue("bounded", src.contains("const val PENDING_INPUT_LIMIT"))
    }

    @Test
    fun `the session pty client no longer drops input without a size`() {
        val src = persistentPtyClient()
        val sendBytes = src.substringAfter("override fun sendBytes(bytes: ByteArray) {")
            .substringBefore("\n    }")
        assertTrue(
            "queued while the replay/size is missing",
            sendBytes.contains("if (!restored || size == null) {") &&
                sendBytes.contains("enqueueInput(bytes)"),
        )
        assertTrue("no silent return in the send path", !sendBytes.contains("?: return"))
        assertTrue("a refused frame is re-queued", sendBytes.contains("enqueueInput(bytes)"))
        // The flush keeps the queue when there is no frame to build (size or
        // replay missing) instead of clearing it into the void.
        val flush = src.substringAfter("private fun flushPendingInput() {").substringBefore("\n    }")
        assertTrue("flush waits for the size", flush.contains("val size = lastSize ?: return"))
        assertTrue("flush rebuilds the frame per payload", flush.contains("interactionFrame(size, next)"))
    }
}
