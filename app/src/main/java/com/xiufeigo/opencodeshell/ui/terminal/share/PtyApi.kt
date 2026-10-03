package com.xiufeigo.opencodeshell.ui.terminal.share

import com.xiufeigo.opencodeshell.ServerConfigStore
import com.xiufeigo.opencodeshell.v2.V2Api
import com.xiufeigo.opencodeshell.v2.ptyConnect
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * REST half of the PTY surface (M5 phase 1) — pure OkHttp + org.json, no
 * Android APIs, so it is JVM-testable against MockWebServer.
 *
 * The **v2 face** (`v2 = true`, the one the panel uses) is a thin adapter over
 * the server-PTY layer in `v2/`: [V2Api.ptyCreate] → [V2Api.ptyConnectTicket] →
 * [ptyConnect] (WebSocket) → [V2Api.ptyUpdate] (rename/resize) → [V2Api.ptyRemove],
 * plus [V2Api.ptyGet] for the `gone()` probe. The legacy **v1 face** below is
 * kept verbatim (`research/04_data_api.md` §6.1/§6.3, no `/api` prefix, bare
 * `Pty.Info` bodies) so existing callers and tests see zero wire change:
 *
 * | operation | endpoint |
 * |---|---|
 * | create | `POST /pty` `{command?, args?, cwd?, title?, env?}` → `Pty.Info` |
 * | connect ticket | `POST /pty/:id/connect-token` + header `x-opencode-ticket: 1` → `{ticket}` |
 * | rename | `PUT /pty/:id` `{title}` (`updateTitle`, upstream `context.tsx:181-195`) |
 * | resize | `PUT /pty/:id` `{size:{rows,cols}}` (never over the WebSocket) |
 * | probe | `GET /pty/:id` → 404 once the session is gone |
 * | delete | `DELETE /pty/:id` |
 */
class PtyApi(
    private val baseHttpUrl: HttpUrl,
    private val username: String? = null,
    private val password: String? = null,
    /** Workspace directory: `?directory=` (v1) / `?location[directory]=` (v2). */
    private val directory: String? = null,
    private val http: OkHttpClient = defaultHttp(),
    private val v2: Boolean = false,
) {

    /**
     * The v2 server-PTY layer this class delegates to when [v2] is set — same
     * base URL, credentials, directory and OkHttp client, so both faces share
     * connection pooling and auth. `null` on the legacy face.
     */
    private val v2api: V2Api? =
        if (v2) V2Api(baseHttpUrl, username, password, http, directory) else null

    data class PtyInfo(
        val id: String,
        val title: String?,
        val command: String?,
        val status: String?,
        val pid: Int?,
        val exitCode: Int?,
    ) {
        val isRunning: Boolean get() = status == null || status == "running"

        companion object {
            /** Bare `Pty.Info` (v1) — v2 callers unwrap `{location, data}` first. */
            fun fromJson(obj: JSONObject): PtyInfo = PtyInfo(
                id = obj.optString("id", ""),
                title = obj.optString("title", "").takeIf { it.isNotEmpty() },
                command = obj.optString("command", "").takeIf { it.isNotEmpty() },
                status = obj.optString("status", "").takeIf { it.isNotEmpty() },
                pid = obj.optInt("pid", 0).takeIf { it != 0 },
                exitCode = if (obj.has("exitCode")) obj.optInt("exitCode") else null,
            )
        }
    }

    /** Result of `POST /pty/:id/connect-token` (`research/04` §7.3). */
    sealed interface TicketResult {
        /** 200: one-shot ticket, TTL 60 s. */
        data class Ok(val ticket: String) : TicketResult

        /** 404/405: server predates the route → connect with Basic auth only. */
        data object Unsupported : TicketResult

        /** 403: Origin/CSRF rejected the request (`terminal.connectTicket.csrfError`). */
        data class Rejected(val code: Int) : TicketResult
    }

    class PtyApiException(val code: Int, message: String) : IOException(message)

    // ------------------------------------------------------------------ paths

    private fun url(path: String): HttpUrl {
        val builder = baseHttpUrl.newBuilder()
        if (!directory.isNullOrBlank()) {
            builder.addQueryParameter(PtyProtocol.directoryQueryName(v2), directory)
        }
        for (segment in PtyProtocol.ptySegments(v2)) builder.addPathSegment(segment)
        for (segment in path.trim('/').split('/').filter { it.isNotEmpty() }) {
            builder.addPathSegment(segment)
        }
        return builder.build()
    }

    private fun builder(path: String): Request.Builder {
        val builder = Request.Builder().url(url(path))
        basicAuth()?.let { builder.header("Authorization", it) }
        return builder
    }

    private fun call(request: Request): String {
        http.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: ""
            if (!response.isSuccessful) throw PtyApiException(response.code, "HTTP ${response.code} ${body.take(200)}")
            return body
        }
    }

    /**
     * Runs one [V2Api] call and re-throws its [V2Api.ApiException] as the
     * public [PtyApiException], so both faces surface the same error type and
     * `code` (the ticket and `gone()` mappings above stay shared).
     */
    private inline fun <T> v2Call(block: () -> T): T = try {
        block()
    } catch (e: V2Api.ApiException) {
        throw PtyApiException(e.code, e.message ?: "HTTP ${e.code}")
    }

    // ------------------------------------------------------------------- api

    /**
     * `POST /pty` — creates the PTY server-side (the tab shows it once the call
     * returns). `cwd` is the same workspace directory the session uses, when the
     * caller knows it. v2 delegates to [V2Api.ptyCreate].
     */
    fun create(title: String? = null, command: String? = null, cwd: String? = null): PtyInfo {
        val delegate = v2api
        if (delegate != null) {
            val created = v2Call {
                delegate.ptyCreate(command = command, args = emptyList(), cwd = cwd, title = title)
            }
            val info = PtyInfo(
                id = created.id,
                title = created.title.takeIf { it.isNotEmpty() },
                command = created.command.takeIf { it.isNotEmpty() },
                status = created.status.takeIf { it.isNotEmpty() },
                pid = created.pid.takeIf { it != 0 },
                exitCode = created.exitCode,
            )
            if (info.id.isEmpty()) throw PtyApiException(-1, "pty create returned no id")
            return info
        }
        val body = JSONObject()
        if (!title.isNullOrBlank()) body.put("title", title)
        if (!command.isNullOrBlank()) body.put("command", command)
        if (!cwd.isNullOrBlank()) body.put("cwd", cwd)
        val json = call(
            builder("").post(body.toString().toRequestBody(JSON_MEDIA)).build(),
        )
        val root = JSONObject(json)
        // v2 wraps successful payloads as {location, data} (`research/04` §6.3).
        val info = PtyInfo.fromJson(root.optJSONObject("data") ?: root)
        if (info.id.isEmpty()) throw PtyApiException(-1, "pty create returned no id")
        return info
    }

    /**
     * `POST /pty/:id/connect-token` with the mandatory `x-opencode-ticket: 1`
     * header. The connection is not a browser, but the server's ticket check
     * inspects `Origin`, so the server's own origin is sent (same-origin, which
     * is what the official frontend presents when it is served by that server).
     * v2 delegates to [V2Api.ptyConnectTicket] (measured working without the
     * Origin header) and keeps the same [TicketResult] mapping.
     */
    fun connectToken(id: String): TicketResult {
        val delegate = v2api
        if (delegate != null) {
            return try {
                val ticket = v2Call { delegate.ptyConnectTicket(id) }.ticket
                if (ticket.isEmpty()) TicketResult.Unsupported else TicketResult.Ok(ticket)
            } catch (e: PtyApiException) {
                when (e.code) {
                    404, 405 -> TicketResult.Unsupported
                    else -> TicketResult.Rejected(e.code)
                }
            }
        }
        val request = builder("/$id/connect-token")
            .header(TICKET_HEADER, "1")
            .header("Origin", originOf(baseHttpUrl))
            .post(EMPTY_BODY)
            .build()
        return try {
            val json = call(request)
            val ticket = JSONObject(json).optString("ticket", "")
            if (ticket.isEmpty()) TicketResult.Unsupported else TicketResult.Ok(ticket)
        } catch (e: PtyApiException) {
            when (e.code) {
                404, 405 -> TicketResult.Unsupported
                else -> TicketResult.Rejected(e.code)
            }
        }
    }

    /**
     * `PUT /pty/:id {title}` — the rename channel, i.e. the same route
     * [updateSize] writes the grid to (`Pty.UpdateInput` carries `title?` and
     * `size?`, `_upstream/packages/schema/src/pty.ts:49-58`, and the handler
     * forwards the payload straight to `pty.update`,
     * `_upstream/packages/server/src/handlers/pty.ts:78-97`).
     *
     * Upstream renames through exactly this call: `terminal.update` writes the
     * store and then `serverSDK.api.pty.update({ptyID, title, size})`
     * (`_upstream/packages/app/src/session/terminal/context.tsx:182-201`, driven
     * by the tab's inline editor, `tab.tsx:82`). Without it the title only ever
     * existed in this app's process — the server's `GET /pty` kept answering its
     * own auto-generated name. v2 delegates to [V2Api.ptyUpdate].
     */
    fun updateTitle(id: String, title: String) {
        val delegate = v2api
        if (delegate != null) {
            v2Call { delegate.ptyUpdate(id, title = title) }
            return
        }
        val body = JSONObject().put("title", title)
        call(builder("/$id").put(body.toString().toRequestBody(JSON_MEDIA)).build())
    }

    /** `PUT /pty/:id {size:{rows,cols}}` — the only resize channel. v2: [V2Api.ptyUpdate]. */
    fun updateSize(id: String, cols: Int, rows: Int) {
        val delegate = v2api
        if (delegate != null) {
            v2Call { delegate.ptyUpdate(id, title = null, cols = cols, rows = rows) }
            return
        }
        val body = JSONObject()
            .put("size", JSONObject().put("rows", rows).put("cols", cols))
        call(builder("/$id").put(body.toString().toRequestBody(JSON_MEDIA)).build())
    }

    /** `GET /pty/:id` — `false` only on a definitive 404 (the session is gone). v2: [V2Api.ptyGet]. */
    fun exists(id: String): Boolean {
        val delegate = v2api
        if (delegate != null) {
            return try {
                v2Call { delegate.ptyGet(id) }
                true
            } catch (e: PtyApiException) {
                e.code != 404
            } catch (_: IOException) {
                true // network trouble is not evidence that the PTY exited
            }
        }
        return try {
            call(builder("/$id").get().build())
            true
        } catch (e: PtyApiException) {
            e.code != 404
        } catch (_: IOException) {
            true // network trouble is not evidence that the PTY exited
        }
    }

    /** `DELETE /pty/:id` — v1 answers `true`, v2 answers 204 NoContent ([V2Api.ptyRemove]). */
    fun remove(id: String) {
        val delegate = v2api
        if (delegate != null) {
            v2Call { delegate.ptyRemove(id) }
            return
        }
        call(builder("/$id").delete().build())
    }

    /**
     * WebSocket upgrade request for `/{api}pty/:id/connect`, mirroring
     * `terminal-websocket-url.ts`:
     *
     * - `directory` (v1) / `location[directory]` (v2);
     * - `cursor` — `-1` = new output only, a number = replay from that absolute
     *   cursor, **omitted (`null`) = full replay** (≤2 MiB server buffer);
     * - `ticket` when one was obtained, otherwise the legacy `auth_token`
     *   (`base64(user:pass)`, v1 only) — never both, exactly like upstream.
     *
     * The URL keeps the `http`/`https` scheme: OkHttp's `newWebSocket` performs
     * the upgrade from it (the same shape `NativeWebSocketHub` already uses).
     */
    fun socketRequest(id: String, ticket: String?, cursor: Long?): Request {
        val delegate = v2api
        if (delegate != null) {
            return delegate.ptySocketRequest(id, ticket, cursor, locationDirectory = directory)
        }
        val urlBuilder = baseHttpUrl.newBuilder()
        for (segment in PtyProtocol.ptySegments(v2)) urlBuilder.addPathSegment(segment)
        urlBuilder.addPathSegment(id).addPathSegment("connect")
        if (!directory.isNullOrBlank()) {
            urlBuilder.addQueryParameter(PtyProtocol.directoryQueryName(v2), directory)
        }
        if (cursor != null) urlBuilder.addQueryParameter("cursor", cursor.toString())
        val hasTicket = !ticket.isNullOrBlank()
        if (hasTicket) {
            urlBuilder.addQueryParameter("ticket", ticket)
        } else if (!v2 && !password.isNullOrEmpty()) {
            urlBuilder.addQueryParameter("auth_token", base64Credentials())
        }
        val request = Request.Builder().url(urlBuilder.build())
        basicAuth()?.let { request.header("Authorization", it) }
        return request.build()
    }

    /**
     * Builds the `/{api}pty/:id/connect` WebSocket. v2 goes through
     * [ptyConnect] — the blocking gate that performs the upgrade from
     * [V2Api.ptySocketRequest] and turns a rejected handshake into a
     * [PtyApiException] (never into [listener]); the legacy face keeps the
     * asynchronous `newWebSocket(socketRequest(...))` it always used.
     *
     * Blocking on the v2 face until `onOpen` (≤ [handshakeTimeoutMs]); call on
     * an IO thread — [PtyClient] already runs its connects on the scheduler.
     */
    fun connect(
        id: String,
        listener: WebSocketListener,
        ticket: String? = null,
        cursor: Long? = null,
        socketClient: OkHttpClient = defaultSocketClient(),
        handshakeTimeoutMs: Long = 15_000L,
    ): WebSocket {
        val delegate = v2api
        if (delegate != null) {
            return v2Call {
                delegate.ptyConnect(
                    id,
                    listener,
                    ticket = ticket,
                    cursor = cursor,
                    locationDirectory = directory,
                    socketClient = socketClient,
                    handshakeTimeoutMs = handshakeTimeoutMs,
                )
            }
        }
        return socketClient.newWebSocket(socketRequest(id, ticket, cursor), listener)
    }

    /** Releases pooled connections (never shuts the shared dispatcher down). */
    fun close() {
        http.connectionPool.evictAll()
    }

    private fun base64Credentials(): String {
        val credentials = "${username.orEmpty()}:${password.orEmpty()}"
        return Base64.getEncoder().encodeToString(credentials.toByteArray(Charsets.UTF_8))
    }

    private fun basicAuth(): String? {
        if (username.isNullOrEmpty() && password.isNullOrEmpty()) return null
        return "Basic ${base64Credentials()}"
    }

    companion object {
        /** `PTY_CONNECT_TOKEN_HEADER` (`research/04` §6.1). */
        const val TICKET_HEADER = "x-opencode-ticket"

        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private val EMPTY_BODY: RequestBody = ByteArray(0).toRequestBody(null)

        fun defaultHttp(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        /**
         * The PTY stream is idle for as long as the shell produces no output, so
         * the socket client must not carry a read timeout (same policy as the
         * WebView shell's `NativeWebSocketHub`).
         */
        fun defaultSocketClient(): OkHttpClient = OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .build()

        fun forServer(
            server: ServerConfigStore.Server,
            directory: String? = null,
            http: OkHttpClient = defaultHttp(),
            v2: Boolean = false,
        ): PtyApi? {
            val parsed = server.url.toHttpUrlOrNull() ?: return null
            return PtyApi(parsed, server.username, server.password, directory, http, v2)
        }

        /** `scheme://host[:port]` of [url] — the Origin a browser would send. */
        internal fun originOf(url: HttpUrl): String {
            val port = if (url.port == HttpUrl.defaultPort(url.scheme)) "" else ":${url.port}"
            return "${url.scheme}://${url.host}$port"
        }
    }
}
