package com.xiufeigo.opencodeshell.ui.terminal.share

import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * PTY/WebSocket data link (M5 phase 1) — the piece that replaces the WebView
 * terminal with a native connection, *without* the VT kernel yet
 * ([PtyScreenEngine] is the seam).
 *
 * Protocol (`research/04_data_api.md` §6.2, `research/02_ui_screens.md` §9.2):
 * - down-link: raw UTF-8 text frames + exactly one binary control frame
 *   `0x00{"cursor":N}` (parsed by [PtyProtocol.classifyBinaryFrame]); the server
 *   answers with a cursor on *every* connect, and the cursor — never a locally
 *   derived length — is what `?cursor=` sends on the next connect;
 * - up-link: text/binary written straight into the PTY; the terminal's own
 *   answers (DSR/DA/OSC 52/mouse) come from the engine through [PtyScreenEngine];
 * - resize: `PUT /pty/:id {size:{rows,cols}}`, first one immediate then 100 ms
 *   debounce ([PtySizeGate]) — never over the WebSocket;
 * - reconnect: `min(250 * 2^min(tries,4), 4000)` ms with a `GET /pty/:id` probe
 *   so an exited session ends the loop instead of retrying forever; once the
 *   fast ladder of [MAX_ATTEMPTS] is exhausted the state reads `FAILED`, but
 *   probes continue every [SLOW_RETRY_MS] so a recovered server self-heals;
 * - close codes: 1000 = shell exited, 4404 = session gone, anything else =
 *   abnormal ([PtyProtocol.closeDisposition]).
 *
 * Threading: [OkHttp] drives the socket callbacks on its reader thread and the
 * blocking ticket/resize calls run on [scheduler], so nothing here touches the
 * main thread. [Scheduler] is injectable, which is what makes the reconnect and
 * debounce behaviour unit-testable without real timers.
 */
class PtyClient(
    private val api: PtyApi,
    val ptyId: String,
    private val engine: PtyScreenEngine,
    private val listener: Listener,
    /** Socket client; must have no read timeout (an idle shell is silent). */
    private val socketClient: OkHttpClient = PtyApi.defaultSocketClient(),
    private val scheduler: Scheduler = Scheduler.executor(),
    private val clock: () -> Long = System::currentTimeMillis,
    /**
     * `?cursor=` of the first connect. `-1` (default) = a fresh tab wants new
     * output only; `null` = deliberate full replay (≤2 MiB) which also resets
     * the engine, because the server will resend history we may already hold.
     */
    private val initialCursor: Long? = PtyProtocol.CURSOR_NEW_OUTPUT_ONLY,
) : TerminalLink {

    enum class State { IDLE, CONNECTING, OPEN, RECONNECTING, EXITED, GONE, FAILED, CLOSED }

    interface Listener {
        /** Connection state, for the tab strip / slot. */
        fun onState(state: State)

        /** Server-authoritative absolute output cursor from the meta frame. */
        fun onCursor(cursor: Long)

        /**
         * Every down-link chunk, after framing. The VT kernel gets its own copy
         * through [PtyScreenEngine.onOutput]; this callback is the UI's tick
         * (the engine owns the state it renders).
         */
        fun onOutput(text: String)

        /** Abnormal condition; [code] is the WebSocket close code when there is one. */
        fun onError(message: String, code: Int? = null)
    }

    /** Delay source — swappable so tests drive reconnect/debounce deterministically. */
    fun interface Scheduler {
        fun schedule(delayMs: Long, task: () -> Unit): Cancellable
        fun shutdown() {}

        companion object {
            /** One daemon thread: PTY traffic is low volume and strictly serial. */
            fun executor(name: String = "oc-pty"): Scheduler = ExecutorScheduler(name)
        }
    }

    fun interface Cancellable {
        fun cancel()

        companion object {
            val NONE = Cancellable { }
        }
    }

    private class ExecutorScheduler(name: String) : Scheduler {
        private val executor: ScheduledExecutorService =
            Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, name).apply { isDaemon = true }
            }

        override fun schedule(delayMs: Long, task: () -> Unit): Cancellable {
            val future: ScheduledFuture<*> = try {
                executor.schedule(
                    { runCatching(task) },
                    delayMs.coerceAtLeast(0L),
                    TimeUnit.MILLISECONDS,
                )
            } catch (e: RejectedExecutionException) {
                // close() shut the executor down while a late resize/reconnect
                // still wanted a slot: discard (DiscardPolicy) — the caller,
                // often the view's layout thread, must not see the exception.
                return Cancellable.NONE
            }
            return Cancellable { future.cancel(false) }
        }

        override fun shutdown() {
            executor.shutdownNow()
        }
    }

    private val closed = AtomicBoolean(false)
    private val splitter = QuerySplitter()
    private val sizeGate = PtySizeGate()

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    private var currentState: State = State.IDLE

    @Volatile
    private var cursorValue: Long? = initialCursor

    @Volatile
    private var lastSize: PtySizeGate.Size? = null

    @Volatile
    private var tries = 0

    private var reconnectTask: Cancellable = Cancellable.NONE
    private var sizeTask: Cancellable = Cancellable.NONE

    /**
     * Keystrokes (and the emulator's answers) produced while the socket could not
     * take them — before `onOpen`, or when `WebSocket.send` refused a frame
     * (OkHttp returns `false` for a socket whose close frame is already enqueued,
     * e.g. after the server's `onClosing`). Flushed by [flushPendingInput] as soon
     * as the socket is open again, in order, so no input is ever dropped
     * silently (v37 tA: "the first key after a prompt is swallowed").
     *
     * Bounded: a terminal that is offline for a long time must not grow a queue
     * without limit; [PENDING_INPUT_LIMIT] bytes is several screens of type-ahead.
     */
    private val pendingInput = ArrayDeque<String>()
    private var pendingInputBytes = 0

    val state: State get() = currentState

    /** Last server cursor, or `-1` when only new output was requested. */
    val cursor: Long get() = cursorValue ?: PtyProtocol.CURSOR_NEW_OUTPUT_ONLY

    /**
     * Bytes of type-ahead still waiting for a socket that can take them (see
     * [pendingInput]). Diagnostics and tests: a non-zero value means input was
     * held instead of dropped.
     */
    val queuedInputBytes: Int get() = synchronized(pendingInput) { pendingInputBytes }

    /** Starts (and keeps alive) the connection. Idempotent while already running. */
    override fun start() {
        if (closed.get()) return
        if (currentState != State.IDLE) return
        setState(State.CONNECTING)
        scheduler.schedule(0L) { connectNow() }
    }

    /**
     * Text up-link (`ws.send(str)` upstream; the server writes it to the PTY).
     *
     * **Never drops a frame.** While the socket is not `OPEN` the text is queued
     * and delivered by [flushPendingInput] on open; a `send` the socket refuses
     * (OkHttp answers `false` once a close frame is enqueued) is re-queued too.
     * The old shape returned silently in both cases, which is how a keystroke
     * could vanish between the terminal view and the PTY.
     */
    override fun send(text: String) {
        if (text.isEmpty()) return
        // After `close()` the tab is gone: nothing may be queued for a PTY that
        // no longer has an owner.
        if (closed.get()) return
        val ws = socket
        if (ws == null || currentState != State.OPEN) {
            enqueueInput(text)
            return
        }
        // Older frames first: a queue left over from a down window must not be
        // overtaken by this keystroke.
        flushPendingInput()
        if (!ws.send(text)) enqueueInput(text)
    }

    /**
     * Byte up-link — the path the VT kernel uses for its answers
     * (`TerminalOutput.write`), and mouse reports. Sent as a text frame because
     * the server decodes binary as **fatal** UTF-8 (`research/04` §6.2), so a
     * lone byte is safer as text.
     */
    override fun sendBytes(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        send(String(bytes, Charsets.UTF_8))
    }

    /**
     * `PUT /pty/:id {size}` — forwarded through [PtySizeGate] (first immediate,
     * then 100 ms debounce). The engine's own grid resize is the caller's job
     * (the caller owns the view geometry, like `TerminalView.updateSize()`).
     */
    override fun resize(cols: Int, rows: Int) {
        val size = PtySizeGate.Size(cols, rows)
        if (!size.isValid) return
        lastSize = size
        when (val decision = sizeGate.onSize(size, clock())) {
            is PtySizeGate.Decision.Send -> pushSize(decision.size)
            is PtySizeGate.Decision.Wait -> scheduleSizeFlush(decision.delayMs)
            PtySizeGate.Decision.Idle -> Unit
        }
    }

    /** Close = unmount: no reconnect, socket released, engine released. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        reconnectTask.cancel()
        sizeTask.cancel()
        val ws = socket
        socket = null
        // Cancel instead of waiting for the close handshake: unmounting must not
        // leak a socket when the shell is silent. The server cleans the
        // attachment up on socket close, and the panel issues `DELETE /pty/:id`.
        runCatching { ws?.cancel() }
        // Unmount: the queued type-ahead belongs to a PTY this client no longer
        // owns (the panel DELETEs it), so it must not be replayed anywhere.
        synchronized(pendingInput) {
            pendingInput.clear()
            pendingInputBytes = 0
        }
        runCatching { engine.release() }
        scheduler.shutdown()
        setState(State.CLOSED)
    }

    // ------------------------------------------------------------ connection

    private fun connectNow() {
        if (closed.get()) return
        if (currentState != State.CONNECTING && currentState != State.RECONNECTING) return
        val requestedCursor = cursorValue
        if (requestedCursor == null) engine.onReset() // full replay: no stale screen
        val ticket = try {
            when (val result = api.connectToken(ptyId)) {
                is PtyApi.TicketResult.Ok -> result.ticket
                PtyApi.TicketResult.Unsupported -> null // old server: Basic auth only
                is PtyApi.TicketResult.Rejected -> {
                    // Origin/CSRF refused the ticket; fall back to Basic auth the
                    // same way the shell's native proxy does (`02` §9.2).
                    listener.onError("connect ticket rejected (HTTP ${result.code})", result.code)
                    null
                }
            }
        } catch (e: IOException) {
            failAndRetry("connect ticket failed: ${e.message}", null)
            return
        }
        if (closed.get()) return
        // `PtyApi.connect` builds the WS (v2: `V2Api.ptyConnect`, blocking to
        // `onOpen`; v1: async `newWebSocket(socketRequest(...))`). A rejected
        // v2 handshake throws before the listener sees anything — same
        // `failAndRetry` path an aborted open takes.
        try {
            socket = api.connect(
                id = ptyId,
                listener = SocketListener(),
                ticket = ticket,
                cursor = requestedCursor,
                socketClient = socketClient,
            )
        } catch (e: IOException) {
            failAndRetry(
                "connection lost: ${e.message ?: e.javaClass.simpleName}",
                (e as? PtyApi.PtyApiException)?.code,
            )
        }
    }

    private inner class SocketListener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (closed.get()) {
                webSocket.cancel()
                return
            }
            // The blocking `PtyApi.connect` path calls back before it returns
            // the socket — capture it here so up-link bytes are never dropped.
            socket = webSocket
            tries = 0
            engine.attachUplink { bytes -> sendBytes(bytes) }
            setState(State.OPEN)
            // Anything typed while the socket was down/connecting goes out now,
            // in order — before the size, so a keystroke is never held back.
            flushPendingInput()
            // Upstream re-sends the size right after connect: the PTY may have
            // been created with a default grid before the view was measured.
            sizeGate.reset()
            lastSize?.let { size ->
                if (sizeGate.onSize(size, clock()) is PtySizeGate.Decision.Send) pushSize(size)
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (closed.get()) return
            // Defensive: the metadata frame is specified as a binary frame with a
            // leading 0x00, but a text frame carrying the same payload is
            // recognisable and must never be fed to the terminal.
            if (text.isNotEmpty() && text[0] == META_LEADING_CHAR) {
                PtyProtocol.parseCursor(text.substring(1).trim())?.let { cursor ->
                    cursorValue = cursor
                    listener.onCursor(cursor)
                }
                return
            }
            dispatch(text)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            if (closed.get()) return
            when (val frame = PtyProtocol.classifyBinaryFrame(bytes.toByteArray())) {
                is PtyProtocol.DownFrame.Meta -> {
                    val cursor = frame.cursor ?: return
                    cursorValue = cursor
                    listener.onCursor(cursor)
                }
                is PtyProtocol.DownFrame.Output -> dispatch(frame.text)
                PtyProtocol.DownFrame.Empty -> Unit
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            socket = null
            if (closed.get()) {
                setState(State.CLOSED)
                return
            }
            handleClose(code, reason)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            socket = null
            if (closed.get()) {
                setState(State.CLOSED)
                return
            }
            failAndRetry("connection lost: ${t.message ?: t.javaClass.simpleName}", response?.code)
        }
    }

    private fun handleClose(code: Int, reason: String?) {
        when (PtyProtocol.closeDisposition(code, reason)) {
            PtyProtocol.CloseDisposition.EXITED -> {
                // 1000: the process ended. The tab keeps the final screen and the
                // user decides whether to close or start a new terminal.
                setState(State.EXITED)
            }
            PtyProtocol.CloseDisposition.GONE -> {
                setState(State.GONE)
            }
            PtyProtocol.CloseDisposition.RETRY -> failAndRetry(
                "WebSocket closed abnormally (code=$code)",
                code,
            )
        }
    }

    private fun failAndRetry(message: String, code: Int?) {
        if (closed.get()) return
        tries += 1
        if (tries > MAX_ATTEMPTS) {
            // The fast ladder is over, but staying FAILED with *no* retry froze
            // the tab forever when the server came back (upstream never gives
            // up). Report the give-up once, then keep a low-frequency probe
            // armed — the `gone()` probe still ends the loop for a dead session.
            if (tries == MAX_ATTEMPTS + 1) {
                listener.onError("$message — giving up after $MAX_ATTEMPTS attempts", code)
            }
            setState(State.FAILED)
            reconnectTask = scheduler.schedule(SLOW_RETRY_MS) { retryNow() }
            return
        }
        setState(State.RECONNECTING)
        listener.onError(message, code)
        reconnectTask = scheduler.schedule(PtyProtocol.backoffDelayMs(tries)) { retryNow() }
    }

    private fun retryNow() {
        if (closed.get()) return
        // `gone()` probe: a PTY that exited is never coming back.
        if (!api.exists(ptyId)) {
            setState(State.GONE)
            return
        }
        setState(State.CONNECTING)
        connectNow()
    }

    // ---------------------------------------------------------------- streams

    /** Queue one up-link frame produced while the socket could not take it. */
    private fun enqueueInput(text: String) {
        synchronized(pendingInput) {
            if (pendingInputBytes + text.length > PENDING_INPUT_LIMIT) return
            pendingInput.addLast(text)
            pendingInputBytes += text.length
        }
    }

    /**
     * Hand the queued up-link frames to a socket that can take them, in order.
     * Called from [SocketListener.onOpen] (and from a later [send] once a frame
     * has been accepted again): a frame the socket refuses is put back at the
     * head of the queue, so the order is preserved and nothing is lost.
     */
    private fun flushPendingInput() {
        val ws = socket ?: return
        if (currentState != State.OPEN) return
        while (true) {
            val next = synchronized(pendingInput) {
                pendingInput.removeFirstOrNull()?.also { pendingInputBytes -= it.length }
            } ?: return
            if (!ws.send(next)) {
                synchronized(pendingInput) {
                    pendingInput.addFirst(next)
                    pendingInputBytes += next.length
                }
                return
            }
        }
    }

    private fun dispatch(text: String) {
        listener.onOutput(text)
        if (engine.answersQueries) {
            // A real VT kernel answers DSR/DA/OSC 52 itself: hand it the bytes
            // untouched (phase 2, `research/06` §1.4).
            engine.onOutput(text.toByteArray(Charsets.UTF_8))
            return
        }
        for (segment in splitter.split(text)) {
            when (segment) {
                is RoutedSegment.Output -> engine.onOutput(segment.text.toByteArray(Charsets.UTF_8))
                is RoutedSegment.Query ->
                    // No kernel yet: answer the queries that need no screen state
                    // and let the rest pass (phase 2 answers them).
                    TerminalAnswers.replyFor(segment.sequence)?.let { send(it) }
            }
        }
    }

    // ----------------------------------------------------------------- resize

    private fun scheduleSizeFlush(delayMs: Long) {
        // Post-close: the view may still flush a grid change while it unbinds,
        // but the scheduler is already down — drop instead of scheduling.
        if (closed.get()) return
        sizeTask.cancel()
        sizeTask = scheduler.schedule(delayMs) { flushSize() }
    }

    private fun flushSize() {
        when (val decision = sizeGate.onTimer(clock())) {
            is PtySizeGate.Decision.Send -> pushSize(decision.size)
            is PtySizeGate.Decision.Wait -> scheduleSizeFlush(decision.delayMs)
            PtySizeGate.Decision.Idle -> Unit
        }
    }

    private fun pushSize(size: PtySizeGate.Size) {
        if (closed.get()) return
        val task = Runnable {
            try {
                api.updateSize(ptyId, size.cols, size.rows)
            } catch (e: IOException) {
                listener.onError("resize failed: ${e.message}")
            }
        }
        // The HTTP call blocks; run it off the socket reader thread when it was
        // not already scheduled there.
        scheduler.schedule(0L) { task.run() }
    }

    private fun setState(next: State) {
        if (currentState == next) return
        currentState = next
        listener.onState(next)
    }

    companion object {
        /**
         * Fast-retry ladder length. After this many consecutive failures the
         * state reads `FAILED` (the "giving up" report) — but retries do not
         * stop: [SLOW_RETRY_MS] probes continue, and the `gone()` probe above
         * already ends the loop for a genuinely dead session.
         */
        const val MAX_ATTEMPTS = 12

        /**
         * Probe period once the fast ladder is exhausted: slow enough not to
         * hammer a struggling server, tight enough that recovery self-heals
         * without the user rebuilding the tab (and losing the screen).
         */
        const val SLOW_RETRY_MS = 15_000L

        /** `0x00` as a `Char`, for a NUL-prefixed *text* metadata frame. */
        private const val META_LEADING_CHAR = '\u0000'

        /**
         * Ceiling on the type-ahead queued while the socket cannot take frames
         * (a few screens' worth of keystrokes). Past it the oldest keys are kept
         * and the newest dropped — a terminal that is offline for minutes must
         * not replay a wall of stale input on reconnect.
         */
        const val PENDING_INPUT_LIMIT = 8 * 1024
    }
}
