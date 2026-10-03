package com.xiufeigo.opencodeshell.v2

import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.Base64

/**
 * Persistent PTY routes under `/api/experimental/persistent-pty/` — upstream
 * `protocol/src/groups/persistent-pty.ts:54-124`, shapes in
 * `schema/src/persistent-pty.ts:9-48`. These back persistent terminals,
 * reconnect-with-cursor and snapshot restore; the session-scoped
 * `/api/experimental/session/{id}/terminal*` siblings are
 * [sessionTerminals][sessionTerminals] in `V2ApiSessionTerminal.kt`, and the
 * ephemeral `/api/pty*` family is [V2Api.ptyList] & co — neither is duplicated
 * here.
 *
 * Held as extension functions rather than [V2Api] members (parallel change to
 * `V2Api.kt`); they use the class's `internal` HTTP seam only — same OkHttp
 * client, same `basicAuthorizationHeader`, same [V2Api.ApiException] on non-2xx.
 *
 * | route | contract |
 * |---|---|
 * | `GET …/{ptyID}` | `{"data":PersistentPty.Info}` (`persistent-pty.ts:66-71`) |
 * | `GET …/{ptyID}/connect` | **WebSocket upgrade**, query `ticket/cursor/role/attachment_id/takeover/input_protocol` (`:103-124`) |
 * | `POST …/{ptyID}/connect-token` | header `x-opencode-ticket: 1`, empty body → `{"data":{ticket,expires_in}}` (`:95-101`) |
 * | `GET …/{ptyID}/snapshot` | `{"data":PersistentPty.Snapshot}` (`:81-85`) |
 * | `POST …/handoff` | empty body → `{"handoff":Handoff\|null}` — **not** a `data` envelope (`:59-64`) |
 * | `POST …/shutdown` | empty body → **204** (`:54-58`) |
 *
 * None of the six takes a `location` parameter (openapi `parameters`: path id
 * only, plus the connect-token header) — a configured directory never leaks.
 * Errors carry `_tag` `PtyNotFoundError` / `ForbiddenError` /
 * `ServiceUnavailableError`. The write routes are `UNVERIFIED` live (openapi +
 * group files only; writes are never aimed at the real server); the 404
 * envelope for get/snapshot is Measured live 2026-09-23 on 2.0.11.
 */

private val EMPTY_BODY: RequestBody = ByteArray(0).toRequestBody(null)

/**
 * `GET /api/experimental/persistent-pty/{ptyID}` → `data` info
 * (`persistentPty.get`, `persistent-pty.ts:66-71`).
 *
 * Measured live 2026-09-23: an unknown id answers
 * `404 {"_tag":"PtyNotFoundError","ptyID":…,"message":"PTY session not found: …"}`
 * (same bytes as the ephemeral `PtyNotFoundError`). Instance shape is openapi
 * (`UNVERIFIED` live — the server had none open).
 */
fun V2Api.persistentPtyGet(ptyID: String): PersistentPtyInfo = PersistentPtyInfo.fromJson(
    dataObject(callBody(builder("experimental/persistent-pty/$ptyID", false).get().build())),
)

/**
 * `POST /api/experimental/persistent-pty/{ptyID}/connect-token` → the one-shot
 * `PtyTicket.ConnectToken` `{ticket, expires_in}` (`persistentPty.connectToken`,
 * `persistent-pty.ts:95-101`; schema `schema/src/pty-ticket.ts:6-9`).
 *
 * The `x-opencode-ticket: 1` header is mandatory and its value must be exactly
 * `1` (`persistent-pty.ts:97-103` handler check `PTY_CONNECT_TOKEN_HEADER_VALUE`);
 * anything else is `403 ForbiddenError` "Invalid persistent PTY connect token
 * request" (`handlers/persistent-pty.ts:99-103`) — same rule as
 * [V2Api.ptyConnectTicket]. Empty body, **no** `location` query (unlike the
 * ephemeral route). `UNVERIFIED` live (write probe refused).
 */
fun V2Api.persistentPtyConnectToken(ptyID: String): V2Api.PtyTicket = V2Api.PtyTicket.fromJson(
    dataObject(
        callBody(
            builder("experimental/persistent-pty/$ptyID/connect-token", false)
                .header(V2Api.TICKET_HEADER, "1")
                .post(EMPTY_BODY)
                .build(),
        ),
    ),
)

/**
 * `GET /api/experimental/persistent-pty/{ptyID}/connect` as a **WebSocket
 * upgrade request** (OkHttp `newWebSocket` performs the upgrade from the
 * `http(s)` URL) — `persistentPty.connect`, `persistent-pty.ts:103-124`
 * (`x-websocket: true`). There is no plain-HTTP POST/GET twin on the wire; the
 * OpenAPI `Schema.Boolean` success is the non-upgrade placeholder.
 *
 * Query contract (all optional; handler `handlers/persistent-pty.ts:120-123`):
 * [ticket] from [persistentPtyConnectToken] — when present it replaces Basic
 * auth (`authorization.ts:54`, `hasPersistentPtyConnectTicketURL`);
 * [cursor] omitted ⇒ full replay, `-1` is invalid here (must be ≥ 0; resume
 * reconnects at `snapshot.info.output.tail`); [role] `"observer"` or omitted ⇒
 * `"controller"`; [attachmentID] client attachment id (server uuid when
 * omitted); [takeover] `true` steals control from the previous controller;
 * [inputProtocol] `1` switches the client→server stream to the framed
 * `[type:u8, cols:u16, rows:u16, payload…]` protocol
 * (`handlers/persistent-pty.ts:213-220`). Frames and replay follow
 * `handlers/persistent-pty.ts:108-231`. `UNVERIFIED` live (no upgrade issued).
 */
fun V2Api.persistentPtySocketRequest(
    ptyID: String,
    ticket: String? = null,
    cursor: Long? = null,
    role: String? = null,
    attachmentID: String? = null,
    takeover: Boolean? = null,
    inputProtocol: Int? = null,
): Request = builder("experimental/persistent-pty/$ptyID/connect", false) {
    if (!ticket.isNullOrBlank()) it.addQueryParameter("ticket", ticket)
    if (cursor != null) it.addQueryParameter("cursor", cursor.toString())
    if (!role.isNullOrBlank()) it.addQueryParameter("role", role)
    if (!attachmentID.isNullOrBlank()) it.addQueryParameter("attachment_id", attachmentID)
    if (takeover != null) it.addQueryParameter("takeover", takeover.toString())
    if (inputProtocol != null) it.addQueryParameter("input_protocol", inputProtocol.toString())
}.get().build()

/**
 * `GET /api/experimental/persistent-pty/{ptyID}/snapshot` → `data` snapshot —
 * screen text, xterm checkpoint bytes and cursor (`persistentPty.snapshot`,
 * `persistent-pty.ts:81-85`; `PersistentPty.Snapshot`,
 * `schema/src/persistent-pty.ts:42-48`).
 *
 * Restore path, upstream `tui/src/component/terminal-pane.tsx:175-188`: write
 * the decoded checkpoint to the terminal, then connect with
 * `cursor = info.output.tail`. Measured live 2026-09-23: unknown id answers the
 * same `404 PtyNotFoundError` as [persistentPtyGet]. Instance shape is openapi
 * (`UNVERIFIED` live).
 */
fun V2Api.persistentPtySnapshot(ptyID: String): PersistentPtySnapshot = PersistentPtySnapshot.fromJson(
    dataObject(callBody(builder("experimental/persistent-pty/$ptyID/snapshot", false).get().build())),
)

/**
 * `POST /api/experimental/persistent-pty/handoff` (empty body) → `handoff` —
 * `null` when there is nothing to hand off (`persistentPty.handoff`,
 * `persistent-pty.ts:59-64`).
 *
 * The envelope key is `handoff`, **not** `data`. The ticket lets a replacement
 * server process adopt the persistent-pty daemon across a service restart
 * (`client/src/pty-handoff.ts:14-62`). Measured live 2026-09-23: `GET` on this
 * path is **not** a route — it is captured by `GET …/{ptyID}` and answers
 * `400 InvalidRequestError "Expected a string starting with \"pty\""` (so the
 * brief's `GET|POST` is POST-only on the wire). Response `UNVERIFIED` live
 * (write probe refused).
 */
fun V2Api.persistentPtyHandoff(): PersistentPtyHandoff? =
    parseObject(callBody(builder("experimental/persistent-pty/handoff", false).post(EMPTY_BODY).build()))
        .optJSONObject("handoff")
        ?.let(PersistentPtyHandoff::fromJson)

/**
 * `POST /api/experimental/persistent-pty/shutdown` (empty body) → **204**
 * (`persistentPty.shutdown`, `persistent-pty.ts:54-58`).
 *
 * Stops the persistent-pty daemon and every terminal it owns — the teardown
 * twin of [persistentPtyHandoff] for service replacement
 * (`client/src/pty-handoff.ts:43-48`). `UNVERIFIED` live (write probe refused).
 */
fun V2Api.persistentPtyShutdown() {
    callUnit(builder("experimental/persistent-pty/shutdown", false).post(EMPTY_BODY).build())
}

/**
 * `PersistentPty.Info` — `Pty.Info` fields (`schema/src/pty.ts:22-31`) plus
 * `sessionID`, `foregroundProcess`, `size`, `output`
 * (`schema/src/persistent-pty.ts:9-16`). `UNVERIFIED` live (openapi schema).
 */
data class PersistentPtyInfo(
    val id: String,
    val title: String,
    val command: String,
    val args: List<String>,
    val cwd: String,
    /** `"running"` | `"exited"` (`Pty.Info.status`). */
    val status: String,
    val pid: Long,
    /** Absent while running; `0` is a valid exit code, so absence ≠ `0`. */
    val exitCode: Long?,
    val sessionID: String,
    /** JSON-null when there is no foreground process; never `""`. */
    val foregroundProcess: String?,
    /** `size.cols` — viewport columns, `> 0`. */
    val cols: Int,
    /** `size.rows` — viewport rows, `> 0`. */
    val rows: Int,
    /** `output.head` — first retained output offset. */
    val outputHead: Long,
    /** `output.tail` — reconnect cursor (`persistentPtySocketRequest`). */
    val outputTail: Long,
    val raw: JSONObject,
) {
    val running: Boolean get() = status == STATUS_RUNNING

    companion object {
        const val STATUS_RUNNING = "running"
        const val STATUS_EXITED = "exited"

        fun fromJson(obj: JSONObject): PersistentPtyInfo {
            val size = obj.optJSONObject("size")
            val output = obj.optJSONObject("output")
            return PersistentPtyInfo(
                id = obj.optString("id", ""),
                title = obj.optString("title", ""),
                command = obj.optString("command", ""),
                args = v2StringList(obj.optJSONArray("args")),
                cwd = obj.optString("cwd", ""),
                status = obj.optString("status", ""),
                pid = (obj.opt("pid") as? Number)?.toLong() ?: 0L,
                exitCode = (obj.opt("exitCode") as? Number)?.toLong(),
                sessionID = obj.optString("sessionID", ""),
                foregroundProcess = optStringOrNull(obj, "foregroundProcess"),
                cols = size?.optInt("cols", 0) ?: 0,
                rows = size?.optInt("rows", 0) ?: 0,
                outputHead = (output?.opt("head") as? Number)?.toLong() ?: 0L,
                outputTail = (output?.opt("tail") as? Number)?.toLong() ?: 0L,
                raw = obj,
            )
        }
    }
}

/**
 * `PersistentPty.Snapshot` — `{info, text, checkpoint, cursor{x,y}}`
 * (`schema/src/persistent-pty.ts:42-48`), flattened: [cursorX]/[cursorY] come
 * from `cursor`. [checkpoint] keeps the wire's **base64** form
 * (`contentEncoding: base64`, openapi; `Buffer.from(snapshot.checkpoint,
 * "base64")` in `terminal-pane.tsx:181`) — decode with [checkpointBytes].
 * `UNVERIFIED` live (openapi schema).
 */
data class PersistentPtySnapshot(
    val info: PersistentPtyInfo,
    val text: String,
    /** xterm checkpoint, base64 as it appears on the wire. */
    val checkpoint: String,
    val cursorX: Int,
    val cursorY: Int,
    val raw: JSONObject,
) {
    fun checkpointBytes(): ByteArray = Base64.getDecoder().decode(checkpoint)

    companion object {
        fun fromJson(obj: JSONObject): PersistentPtySnapshot {
            val cursor = obj.optJSONObject("cursor")
            return PersistentPtySnapshot(
                info = PersistentPtyInfo.fromJson(obj.optJSONObject("info") ?: JSONObject()),
                text = obj.optString("text", ""),
                checkpoint = obj.optString("checkpoint", ""),
                cursorX = cursor?.optInt("x", 0) ?: 0,
                cursorY = cursor?.optInt("y", 0) ?: 0,
                raw = obj,
            )
        }
    }
}

/**
 * `PersistentPty.Handoff` — `{directory, instanceID, ticket, expiresAt}`
 * (`schema/src/persistent-pty.ts:18-24`). [expiresAt] is the epoch-**millis**
 * expiry compared against `Date.now()` (`client/src/pty-handoff.ts:59`); the
 * schema also admits the non-finite spellings `"Infinity"`/`"-Infinity"`/`"NaN"`
 * (openapi `anyOf`) which parse as `0` here — `UNVERIFIED` live.
 */
data class PersistentPtyHandoff(
    val directory: String,
    val instanceID: String,
    val ticket: String,
    val expiresAt: Long,
    val raw: JSONObject,
) {
    companion object {
        fun fromJson(obj: JSONObject): PersistentPtyHandoff = PersistentPtyHandoff(
            directory = obj.optString("directory", ""),
            instanceID = obj.optString("instanceID", ""),
            ticket = obj.optString("ticket", ""),
            expiresAt = (obj.opt("expiresAt") as? Number)?.toLong() ?: 0L,
            raw = obj,
        )
    }
}
