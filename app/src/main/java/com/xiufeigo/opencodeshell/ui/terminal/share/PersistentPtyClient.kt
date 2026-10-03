package com.xiufeigo.opencodeshell.ui.terminal.share

import com.xiufeigo.opencodeshell.v2.V2Api
import com.xiufeigo.opencodeshell.v2.persistentPtyConnectToken
import com.xiufeigo.opencodeshell.v2.persistentPtyGet
import com.xiufeigo.opencodeshell.v2.persistentPtyShutdown
import com.xiufeigo.opencodeshell.v2.persistentPtySnapshot
import com.xiufeigo.opencodeshell.v2.persistentPtySocketRequest
import com.xiufeigo.opencodeshell.v2.readSessionTerminal
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.IOException
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Persistent / session-bound terminal link — the Android twin of the TUI's
 * `terminal-pane.tsx` connect flow, with the reconnect-and-restore loop the
 * brief asks for (upstream drops the pane on disconnect; this client re-runs
 * the restore sequence instead).
 *
 * Connect sequence (`terminal-pane.tsx:175-188` + `client/src/solid/pty.ts:41-58`):
 *
 * 1. **screen restore** — session tabs paint `readSessionTerminal(...).screen`
 *    (`[SessionTerminalRestore]`, text + cursor); bare persistent tabs write
 *    `persistentPtySnapshot(...).checkpointBytes()` into the terminal;
 * 2. `persistentPtyConnectToken` → one-shot `ticket`;
 * 3. `persistentPtySocketRequest(cursor = info.output.tail, takeover = true,
 *    attachment_id, input_protocol = 1)` → WebSocket.
 *
 * After open the frames are `[PersistentPtyProtocol]`: binary = output, text =
 * JSON control (`attached` / `replay_complete` / `resized` / `exited` /
 * `controller_changed`). Up-link is the framed `[type,cols,rows,payload]`
 * protocol — including resize, which never needs a REST call.
 *
 * Reconnect: `min(250 * 2^min(tries,4), 4000)` ms with a `persistentPtyGet`
 * probe so a removed terminal ends the loop ([PtyClient.State.GONE]); once the
 * fast ladder of [PtyClient.MAX_ATTEMPTS] is exhausted the state reads `FAILED`
 * but slow probes continue (same self-heal contract as [PtyClient]). The
 * `exited` message or a clean 1000 close means the shell is gone — never retry.
 *
 * Threading mirrors [PtyClient]: OkHttp drives the callbacks, the blocking
 * restore/ticket calls run on the injectable [PtyClient.Scheduler].
 */
class PersistentPtyClient(
    private val api: V2Api,
    val ptyId: String,
    private val engine: PtyScreenEngine,
    private val listener: Listener,
    /**
     * `null` = bare persistent terminal (snapshot restore, item 2). Non-null =
     * session-bound terminal: the screen is restored from
     * `readSessionTerminal(sessionID)` (item 3) with the snapshot path as
     * fallback when the session read answers `null`.
     */
    private val sessionID: String? = null,
    /** Socket client; must have no read timeout (an idle shell is silent). */
    private val socketClient: OkHttpClient = persistentSocketClient(),
    private val scheduler: PtyClient.Scheduler = PtyClient.Scheduler.executor("oc-pty-persist"),
    /** Client attachment id — `controller_changed` compares against it. */
    private val attachmentId: String = UUID.randomUUID().toString(),
) : TerminalLink {

    interface Listener {
        /** Connection state, for the tab strip / slot. */
        fun onState(state: PtyClient.State)

        /** Every down-link chunk after framing (UI tick; the engine owns the screen). */
        fun onOutput(text: String)

        /** Abnormal condition; [code] is the HTTP/WebSocket code when there is one. */
        fun onError(message: String, code: Int? = null)

        /** This attachment is / is not the controller (input goes through only as controller). */
        fun onController(controller: Boolean) {}

        /** The replay (and therefore the screen restore) finished. */
        fun onReplayComplete() {}
    }

    private val closed = AtomicBoolean(false)

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    private var currentState: PtyClient.State = PtyClient.State.IDLE

    @Volatile
    private var lastSize: PtySizeGate.Size? = null

    @Volatile
    private var tries = 0

    /** Set by the `exited` message: the shell is gone, never reconnect. */
    @Volatile
    private var exited = false

    /** `attached` handshake acked and `input_protocol = 1` accepted. */
    @Volatile
    private var attached = false

    /** `replay_complete` seen on this connection: the screen is restored. */
    @Volatile
    private var restored = false

    @Volatile
    private var controller = false

    /** `interact()` asked for control before the replay finished. */
    @Volatile
    private var wantsControl = false

    private val sizeGate = PtySizeGate()

    /**
     * Type-ahead produced while the frame cannot be sent yet: before
     * `replay_complete` (`input_protocol = 1` input is only valid once the
     * replay is done) and before the first grid size is known (the type-0 frame
     * carries `{cols,rows}`, so a payload without a size has no frame). Bounded
     * by [PENDING_INPUT_LIMIT]; flushed in order by [flushPendingInput].
     */
    private val pendingInput = ArrayDeque<ByteArray>()
    private var pendingInputBytes = 0
    private var reconnectTask: PtyClient.Cancellable = PtyClient.Cancellable.NONE
    private var sizeTask: PtyClient.Cancellable = PtyClient.Cancellable.NONE

    val state: PtyClient.State get() = currentState

    override fun start() {
        if (closed.get()) return
        if (currentState != PtyClient.State.IDLE) return
        setState(PtyClient.State.CONNECTING)
        scheduler.schedule(0L) { connectNow() }
    }

    /** Text up-link — framed as `input_protocol = 1` input once the replay is done. */
    override fun send(text: String) = sendBytes(text.toByteArray(Charsets.UTF_8))

    /**
     * Byte up-link (VT answers, mouse reports, paste) — same framed path as [send].
     *
     * **Never drops a frame.** A payload that has no valid frame yet (replay not
     * finished, or no grid size known) is queued, and a frame the socket refuses
     * is queued too; [flushPendingInput] delivers both in order. The old shape
     * returned silently in both cases — one way a keystroke could vanish.
     */
    override fun sendBytes(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        if (closed.get() || currentState != PtyClient.State.OPEN) return
        val size = lastSize
        if (!restored || size == null) {
            enqueueInput(bytes)
            return
        }
        // Older frames first: a queue left over from a down window must not be
        // overtaken by this keystroke.
        flushPendingInput()
        val ws = socket
        if (ws == null || !ws.send(interactionFrame(size, bytes).toByteString())) enqueueInput(bytes)
    }

    /**
     * Grid resize. Persistent ptys resize **in band**: the type-0 interaction
     * frame carries the live `{cols,rows}` (`terminal-pane.tsx` `onSizeChange` →
     * `interact()`), so no REST call exists or is needed here. First size goes
     * out immediately, then 100 ms debounce ([PtySizeGate]).
     */
    override fun resize(cols: Int, rows: Int) {
        val size = PtySizeGate.Size(cols, rows)
        if (!size.isValid) return
        lastSize = size
        when (val decision = sizeGate.onSize(size, System.currentTimeMillis())) {
            is PtySizeGate.Decision.Send -> {
                pushInteract(decision.size)
                // The size is what the type-0 frame carries: input queued while
                // it was unknown becomes sendable now.
                flushPendingInput()
            }
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
        runCatching { ws?.cancel() }
        synchronized(pendingInput) {
            pendingInput.clear()
            pendingInputBytes = 0
        }
        runCatching { engine.release() }
        scheduler.shutdown()
        setState(PtyClient.State.CLOSED)
    }

    /**
     * Type-0 frame on interaction (tap-to-focus): upstream `interact()` — it
     * both declares the size and claims input focus from the daemon. Before the
     * replay is done the claim is remembered and sent at `replay_complete`.
     */
    fun interact() {
        if (closed.get()) return
        if (!restored) {
            wantsControl = true
            return
        }
        val size = lastSize ?: return
        socket?.send(interactionFrame(size, null).toByteString())
    }

    // ------------------------------------------------------------ connection

    private fun connectNow() {
        if (closed.get()) return
        if (currentState != PtyClient.State.CONNECTING &&
            currentState != PtyClient.State.RECONNECTING
        ) {
            return
        }
        try {
            // 1. screen restore + reconnect cursor (item 2 / item 3).
            val cursor = restoreScreen()
            if (closed.get()) return
            // 2. one-shot ticket (`client/src/solid/pty.ts:43-46`).
            val ticket = api.persistentPtyConnectToken(ptyId).ticket
            if (closed.get()) return
            // 3. upgrade at the retained-output tail, taking control
            // (`terminal-pane.tsx:183-188`).
            val request = api.persistentPtySocketRequest(
                ptyID = ptyId,
                ticket = ticket.takeIf { it.isNotEmpty() },
                cursor = cursor,
                attachmentID = attachmentId,
                takeover = true,
                inputProtocol = PersistentPtyProtocol.INPUT_PROTOCOL,
            )
            socket = socketClient.newWebSocket(request, SocketListener())
        } catch (e: IOException) {
            if (closed.get()) return
            val code = (e as? V2Api.ApiException)?.code
            if (code == 404) {
                // `PtyNotFoundError`: the terminal is gone for good.
                setState(PtyClient.State.GONE)
                return
            }
            failAndRetry("persistent terminal connect failed: ${e.message}", code)
        }
    }

    /**
     * Paints the screen and answers the reconnect cursor.
     *
     * - session tabs: `readSessionTerminal` screen text + cursor (item 3),
     *   falling back to the checkpoint when the session has no current terminal;
     * - bare persistent tabs (and the fallback): `snapshot.checkpointBytes()`
     *   written into the terminal, cursor = `snapshot.info.output.tail`
     *   (`terminal-pane.tsx:175-188`).
     */
    private fun restoreScreen(): Long {
        val session = sessionID
        if (session != null) {
            val read = runCatching { api.readSessionTerminal(session) }.getOrNull()
            if (read != null) {
                engine.onReset()
                engine.onOutput(SessionTerminalRestore.screenBytes(read.text, read.cursorX, read.cursorY))
                // The read carries the screen but no output offset — the info
                // does (`output.tail` is the reconnect cursor).
                val tail = api.persistentPtyGet(ptyId).outputTail
                // D8: a session terminal's screen is already **whole** before the
                // socket opens — the read above painted it and the stream resumes
                // at the tip — so the two up-link preconditions that the bare
                // persistent branch gets from the snapshot branch (`lastSize` and
                // `restored`) have to be established here too. Without them every
                // keystroke takes the `sendBytes` enqueue branch and never leaves:
                // no frame can be built without `{cols,rows}`, and a session
                // terminal that never sees `replay_complete` would keep the gate
                // shut for good — the D8 symptom, where the down-link looks perfect
                // (block cursor, `last login`) and no key ever reaches the PTY.
                // Both are facts about the very same REST read, so they are set
                // where the down-link restore is set.
                if (read.cols > 0 && read.rows > 0) {
                    engine.onResize(read.cols, read.rows, 0f, 0f)
                    lastSize = PtySizeGate.Size(read.cols, read.rows)
                }
                restored = true
                return tail
            }
        }
        val snapshot = api.persistentPtySnapshot(ptyId)
        engine.onReset()
        if (snapshot.checkpoint.isNotEmpty()) {
            runCatching { engine.onOutput(snapshot.checkpointBytes()) }
                .onFailure { throw IOException("checkpoint restore failed: ${it.message}") }
        }
        if (snapshot.info.cols > 0 && snapshot.info.rows > 0) {
            engine.onResize(snapshot.info.cols, snapshot.info.rows, 0f, 0f)
            lastSize = PtySizeGate.Size(snapshot.info.cols, snapshot.info.rows)
        }
        return snapshot.info.outputTail
    }

    private inner class SocketListener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (closed.get()) {
                webSocket.cancel()
                return
            }
            socket = webSocket
            tries = 0
            engine.attachUplink { bytes -> sendBytes(bytes) }
            setState(PtyClient.State.OPEN)
            sizeGate.reset()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (closed.get()) return
            when (val message = PersistentPtyProtocol.parseMessage(text)) {
                PersistentPtyProtocol.ServerMessage.Exited -> exited = true
                PersistentPtyProtocol.ServerMessage.ReplayComplete -> {
                    restored = true
                    // Declare the live grid (the type-0 frame also claims input)
                    // exactly once the replay finished — upstream `interact()`
                    // is gated the same way (`terminal-pane.tsx` `processStream`).
                    lastSize?.let { pushInteract(it) }
                    flushPendingInput()
                    wantsControl = false
                    listener.onReplayComplete()
                }
                is PersistentPtyProtocol.ServerMessage.Resized -> applyCheckpoint(message.checkpoint)
                is PersistentPtyProtocol.ServerMessage.ControllerChanged -> {
                    val was = controller
                    controller = message.attachmentID == attachmentId
                    if (controller && !was && restored) interact()
                    listener.onController(controller)
                }
                is PersistentPtyProtocol.ServerMessage.Attached -> {
                    if (message.inputProtocol != null &&
                        message.inputProtocol != PersistentPtyProtocol.INPUT_PROTOCOL
                    ) {
                        listener.onError("persistent terminal server is out of date")
                        webSocket.close(1000, null)
                        return
                    }
                    attached = true
                    controller = message.role == "controller"
                    if (message.cols > 0 && message.rows > 0) {
                        lastSize = PtySizeGate.Size(message.cols, message.rows)
                    }
                    listener.onController(controller)
                }
                PersistentPtyProtocol.ServerMessage.Ignored -> Unit
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            if (closed.get()) return
            val data = bytes.toByteArray()
            if (data.isEmpty()) return
            listener.onOutput(String(data, Charsets.UTF_8))
            engine.onOutput(data)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            socket = null
            if (closed.get()) {
                setState(PtyClient.State.CLOSED)
                return
            }
            handleClose(code)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            socket = null
            if (closed.get()) {
                setState(PtyClient.State.CLOSED)
                return
            }
            failAndRetry("connection lost: ${t.message ?: t.javaClass.simpleName}", response?.code)
        }
    }

    private fun handleClose(code: Int) {
        when {
            exited || code == 1000 ->
                // The process ended (or the daemon said so). Keep the screen.
                setState(PtyClient.State.EXITED)

            PtyProtocol.closeDisposition(code) == PtyProtocol.CloseDisposition.GONE ->
                setState(PtyClient.State.GONE)

            else -> failAndRetry("WebSocket closed abnormally (code=$code)", code)
        }
    }

    private fun failAndRetry(message: String, code: Int?) {
        if (closed.get()) return
        attached = false
        restored = false
        tries += 1
        if (tries > PtyClient.MAX_ATTEMPTS) {
            if (tries == PtyClient.MAX_ATTEMPTS + 1) {
                listener.onError("$message — giving up after ${PtyClient.MAX_ATTEMPTS} attempts", code)
            }
            setState(PtyClient.State.FAILED)
            reconnectTask = scheduler.schedule(PtyClient.SLOW_RETRY_MS) { retryNow() }
            return
        }
        setState(PtyClient.State.RECONNECTING)
        listener.onError(message, code)
        reconnectTask = scheduler.schedule(PtyProtocol.backoffDelayMs(tries)) { retryNow() }
    }

    private fun retryNow() {
        if (closed.get()) return
        // `gone()` probe: a removed terminal is never coming back.
        val known = try {
            api.persistentPtyGet(ptyId)
            true
        } catch (e: V2Api.ApiException) {
            if (e.code == 404) {
                setState(PtyClient.State.GONE)
                return
            }
            true
        } catch (_: IOException) {
            true // network trouble is not evidence the terminal exited
        }
        if (!known) {
            setState(PtyClient.State.GONE)
            return
        }
        setState(PtyClient.State.CONNECTING)
        connectNow()
    }

    // ---------------------------------------------------------------- streams

    private fun applyCheckpoint(checkpoint: ByteArray) {
        // `terminal-pane.tsx` writes `RIS + checkpoint` on a `resized` frame;
        // `onReset()` is the engine seam for the same wipe.
        engine.onReset()
        if (checkpoint.isNotEmpty()) engine.onOutput(checkpoint)
    }

    /** Queue one input payload that has no deliverable frame yet. */
    private fun enqueueInput(bytes: ByteArray) {
        synchronized(pendingInput) {
            if (pendingInputBytes + bytes.size > PENDING_INPUT_LIMIT) return
            pendingInput.addLast(bytes)
            pendingInputBytes += bytes.size
        }
    }

    /**
     * Hand the queued input payloads to the socket, in order, rebuilding each
     * type-0 frame from the *current* grid. Returns without touching the queue
     * while the socket or the size is not ready; a frame the socket refuses goes
     * back to the head of the queue.
     */
    private fun flushPendingInput() {
        if (!restored) return
        val size = lastSize ?: return
        val ws = socket ?: return
        if (currentState != PtyClient.State.OPEN) return
        while (true) {
            val next = synchronized(pendingInput) {
                pendingInput.pollFirst()?.also { pendingInputBytes -= it.size }
            } ?: return
            if (!ws.send(interactionFrame(size, next).toByteString())) {
                synchronized(pendingInput) {
                    pendingInput.addFirst(next)
                    pendingInputBytes += next.size
                }
                return
            }
        }
    }

    private fun scheduleSizeFlush(delayMs: Long) {
        if (closed.get()) return
        sizeTask.cancel()
        sizeTask = scheduler.schedule(delayMs) {
            when (val decision = sizeGate.onTimer(System.currentTimeMillis())) {
                is PtySizeGate.Decision.Send -> pushInteract(decision.size)
                is PtySizeGate.Decision.Wait -> scheduleSizeFlush(decision.delayMs)
                PtySizeGate.Decision.Idle -> Unit
            }
        }
    }

    private fun pushInteract(size: PtySizeGate.Size) {
        if (closed.get() || !restored) return
        socket?.send(interactionFrame(size, null).toByteString())
    }

    private fun interactionFrame(size: PtySizeGate.Size, payload: ByteArray?): ByteArray =
        PersistentPtyProtocol.interactionFrame(size.cols, size.rows, payload)

    private fun setState(next: PtyClient.State) {
        if (currentState == next) return
        currentState = next
        listener.onState(next)
    }

    companion object {
        /** Like [PtyApi.defaultSocketClient]: a silent shell must not time out. */
        fun persistentSocketClient(): OkHttpClient = PtyApi.defaultSocketClient()

        /**
         * Ceiling on the type-ahead queued before the replay/size is ready (see
         * [pendingInput]): several screens' worth of keystrokes, never unbounded.
         */
        const val PENDING_INPUT_LIMIT = 8 * 1024

        /**
         * `POST …/persistent-pty/shutdown` — stops the persistent-pty daemon and
         * every terminal it owns (`client/src/pty-handoff.ts:43-48` teardown
         * twin). Deliberate, panel-level action; never part of a tab close.
         */
        fun shutdownDaemon(api: V2Api) {
            api.persistentPtyShutdown()
        }
    }
}
