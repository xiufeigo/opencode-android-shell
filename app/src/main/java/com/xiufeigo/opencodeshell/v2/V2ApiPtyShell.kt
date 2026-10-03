package com.xiufeigo.opencodeshell.v2

import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Background-shell detail / paged output / close, plus the ordinary-PTY
 * WebSocket connect — `protocol/src/groups/shell.ts:39-85` and
 * `pty.ts:117-143`. Held as `V2Api` extensions (parallel-safe; same `internal`
 * HTTP seam as `V2ApiFsWrite.kt`).
 *
 * | piece | contract |
 * |---|---|
 * | `GET /api/shell/{id}` | `{"location":…,"data":Shell.Info}` (`shell.ts:39-54`) |
 * | `DELETE /api/shell/{id}` | **204**, empty body (`shell.ts:71-85`) |
 * | `GET /api/shell/{id}/output` | `{"location":…,"data":{output,cursor,size,truncated}}` — byte-cursor page |
 * | `GET /api/pty/{id}/connect` | WebSocket upgrade (`"x-websocket":true`), query `location[directory]`/`cursor`/`ticket` |
 *
 * `POST /api/shell` (create) and `GET /api/shell` (list, already
 * [V2Api.shells]) are other routes and stay out of this file.
 */

/** Shared socket client for [ptyConnect]: a terminal idles between frames. */
private val ptyShellSocket: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()
}

/**
 * `GET /api/pty/{ptyID}/connect` — the **WebSocket upgrade** for one ordinary
 * PTY (`groups/pty.ts:120-143`, `"x-websocket":true`; the HTTP method is GET,
 * the upgrade is OkHttp's `newWebSocket`). Where [V2Api.ptySocketRequest] only
 * builds the request, this performs the handshake and blocks until the socket
 * is open.
 *
 * Wire: query `location[directory]`, `cursor` (omitted = full replay, `-1` =
 * new output only) and the one-shot `ticket` from [V2Api.ptyConnectTicket] —
 * upstream's client flow fetches the token first, then upgrades
 * (`client/src/solid/pty.ts:23-40`). After open, frames are raw terminal
 * output.
 *
 * Failure contract: a rejected handshake throws [V2Api.ApiException] and is
 * **not** re-delivered to [listener] (the caller never receives the socket) —
 * e.g. `404` with an **empty** body for an unknown pty (measured live 2.0.11,
 * matching `pty.ts:118-119`'s "empty 404 before any upgrade work"). Transport
 * failures surface as [IOException]. Everything after open goes to [listener].
 * Blocking; call on an IO dispatcher.
 *
 * @param cursor replay window; `-1` = new output only, omitted = full replay
 *   (≤ 2 MiB server buffer; measured note on [V2Api.ptySocketRequest]).
 * @param ticket `PtyTicket.ticket` from [V2Api.ptyConnectTicket]; `null` ⇒
 *   Basic-auth upgrade only (measured working against a no-auth server).
 */
fun V2Api.ptyConnect(
    ptyID: String,
    listener: WebSocketListener,
    ticket: String? = null,
    cursor: Long? = null,
    locationDirectory: String? = null,
    socketClient: OkHttpClient = ptyShellSocket,
    handshakeTimeoutMs: Long = 15_000L,
): WebSocket {
    val opened = CountDownLatch(1)
    val open = AtomicBoolean(false)
    var failure: Exception? = null
    val gate = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            open.set(true)
            try {
                listener.onOpen(webSocket, response)
            } finally {
                opened.countDown()
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) = listener.onMessage(webSocket, text)

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) = listener.onMessage(webSocket, bytes)

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) =
            listener.onClosing(webSocket, code, reason)

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) =
            listener.onClosed(webSocket, code, reason)

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (open.get()) {
                listener.onFailure(webSocket, t, response)
                return
            }
            val code = response?.code ?: 0
            failure = if (response != null && code != 101) {
                // `failWebSocket` delivers the response before closing it, so the
                // `_tag` body is still peekable here.
                val body = runCatching { response.peekBody(ERROR_PEEK_BYTES).string() }.getOrDefault("")
                apiError(code, body)
            } else {
                t as? Exception ?: IOException(t)
            }
            opened.countDown()
        }
    }
    val socket = socketClient.newWebSocket(ptySocketRequest(ptyID, ticket, cursor, locationDirectory), gate)
    if (!opened.await(handshakeTimeoutMs, TimeUnit.MILLISECONDS)) {
        socket.cancel()
        throw IOException("pty connect handshake timed out after $handshakeTimeoutMs ms")
    }
    failure?.let { throw it }
    return socket
}

/**
 * `GET /api/shell/{id}` → `{"location":…,"data":Shell.Info}` →
 * [ShellCommandInfo] (`groups/shell.ts:39-54`).
 *
 * Measured live 2.0.11: unknown ids answer
 * `404 {"_tag":"ShellNotFoundError","id":…,"message":"Shell command not
 * found: …"}`. `status` ∈ `"running"` | `"exited"` | `"timeout"` | `"killed"`
 * (`schema/src/shell.ts:22`); `exit` and `time.completed` appear only once the
 * command finished.
 */
fun V2Api.shellGet(id: String, locationDirectory: String? = null): ShellCommandInfo = ShellCommandInfo.fromJson(
    dataObject(callBody(builder("shell/$id", true) { located(it, locationDirectory) }.get().build())),
)

/**
 * `DELETE /api/shell/{id}` → **204** (`groups/shell.ts:71-85`): terminate the
 * command and drop its retained output.
 *
 * The handler swallows `Shell.NotFoundError`
 * (`server/src/handlers/shell.ts:64-68`), so an unknown id is answered **204**
 * too — this is a forget, not an existence check. No live probe: write route.
 */
fun V2Api.shellRemove(id: String, locationDirectory: String? = null) {
    callUnit(builder("shell/$id", true) { located(it, locationDirectory) }.delete().build())
}

/**
 * `GET /api/shell/{id}/output?cursor=&limit=` → one capture page
 * (`groups/shell.ts:55-70`, `schema/src/shell.ts:67-80`).
 *
 * Combined stdout/stderr is paged by **absolute byte cursor** — the "stream" is
 * polling this while [ShellCommandOutput.hasMore]. Defaults from
 * `core/src/shell.ts:198-199`: `cursor=0`, `limit=65536`; `cursor >= size`
 * answers an empty caught-up page. Query numbers travel as numeric strings and
 * a non-numeric `cursor` is `400 InvalidRequestError` (measured live 2.0.11).
 * Unknown ids answer the same `ShellNotFoundError` as [shellGet] (measured).
 */
fun V2Api.shellOutput(
    id: String,
    cursor: Long? = null,
    limit: Int? = null,
    locationDirectory: String? = null,
): ShellCommandOutput = ShellCommandOutput.fromJson(
    dataObject(
        callBody(
            builder("shell/$id/output", true) {
                located(it, locationDirectory)
                if (cursor != null) it.addQueryParameter("cursor", cursor.toString())
                if (limit != null && limit > 0) it.addQueryParameter("limit", limit.toString())
            }.get().build(),
        ),
    ),
)

/**
 * `Shell.Info` — `{id, status, command, cwd, shell, file, pid?, exit?,
 * metadata, time{started, completed?}}` (`schema/src/shell.ts:38-51`).
 *
 * [file] is the absolute capture path paged via [shellOutput]; [exit] is the
 * process exit code (wire `Schema.Finite`, read as an int) and appears only
 * once finished; [metadata] is the caller-supplied tag record, `{}` by default.
 * Element shape is document-derived — no live shell was created (write route).
 */
data class ShellCommandInfo(
    val id: String,
    val status: String,
    val command: String,
    val cwd: String,
    val shell: String,
    val file: String,
    val pid: Int?,
    val exit: Int?,
    val metadata: JSONObject,
    val timeStarted: Long,
    val timeCompleted: Long?,
    val raw: JSONObject,
) {
    val running: Boolean get() = status == STATUS_RUNNING

    companion object {
        const val STATUS_RUNNING = "running"
        const val STATUS_EXITED = "exited"
        const val STATUS_TIMEOUT = "timeout"
        const val STATUS_KILLED = "killed"

        fun fromJson(obj: JSONObject): ShellCommandInfo {
            val time = obj.optJSONObject("time")
            return ShellCommandInfo(
                id = obj.optString("id", ""),
                status = obj.optString("status", ""),
                command = obj.optString("command", ""),
                cwd = obj.optString("cwd", ""),
                shell = obj.optString("shell", ""),
                file = obj.optString("file", ""),
                pid = if (obj.has("pid")) obj.optInt("pid") else null,
                exit = if (obj.has("exit")) obj.optInt("exit") else null,
                metadata = obj.optJSONObject("metadata") ?: JSONObject(),
                timeStarted = time?.optLong("started", 0L) ?: 0L,
                timeCompleted = time?.optLong("completed", 0L)?.takeIf { it > 0L },
                raw = obj,
            )
        }
    }
}

/**
 * `Shell.Output` — `{output, cursor, size, truncated}`
 * (`schema/src/shell.ts:73-80`). [cursor] is the absolute byte offset after
 * this page and equals [size] once caught up; keep paging while [hasMore].
 * Upstream currently always answers `truncated: false`
 * (`core/src/shell.ts:200,222`).
 */
data class ShellCommandOutput(
    val output: String,
    val cursor: Long,
    val size: Long,
    val truncated: Boolean,
) {
    /** `cursor < size` — more captured output remains to page. */
    val hasMore: Boolean get() = cursor < size

    companion object {
        fun fromJson(obj: JSONObject): ShellCommandOutput = ShellCommandOutput(
            output = obj.optString("output", ""),
            cursor = obj.optLong("cursor", 0L),
            size = obj.optLong("size", 0L),
            truncated = obj.optBoolean("truncated", false),
        )
    }
}

private const val ERROR_PEEK_BYTES = 8L * 1024
