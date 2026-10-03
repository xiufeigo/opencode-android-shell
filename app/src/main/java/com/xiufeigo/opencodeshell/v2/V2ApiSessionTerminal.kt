package com.xiufeigo.opencodeshell.v2

import org.json.JSONArray
import org.json.JSONObject

/**
 * Session-bound terminals — `GET|POST /api/experimental/session/{sessionID}/terminal`
 * and `GET /api/experimental/session/{sessionID}/terminal/read`
 * (`protocol/src/groups/persistent-pty.ts:23-52`, shapes in
 * `schema/src/persistent-pty.ts:9-66`).
 *
 * Held as extension functions rather than [V2Api] members (parallel change to
 * `V2Api.kt`); they use the class's `internal` HTTP seam only — same OkHttp
 * client, same `basicAuthorizationHeader`, same [V2Api.ApiException] on non-2xx.
 *
 * | piece | contract |
 * |---|---|
 * | location | **no** `location` parameter on any of the three routes (openapi) |
 * | list | `GET` → `{"data":[PersistentPty.Info…]}` — measured live: bare `{data}`, empty `[]` even for an unknown `sessionID` |
 * | create | `POST` `PersistentPty.CreateInput` → `{"data":PersistentPty.Info}` |
 * | read | `GET ?lines=` → `{"data":PersistentPty.ReadResult\|null}` — measured live `{"data":null}` when no current terminal (unknown session too) |
 *
 * The `/api/experimental/persistent-pty/` sibling routes (`:ptyID` get/update/
 * snapshot/remove/connect-token/connect, plus handoff/shutdown) live under a
 * different path root and are not covered here.
 */

/**
 * `GET /api/experimental/session/{sessionID}/terminal` → `data[]`
 * (`persistentPty.list`, `persistent-pty.ts:39-44`).
 *
 * Measured live 2026-09-23: `{"data":[]}` with no `location` envelope; an
 * unknown `sessionID` answers the same `200 {"data":[]}` rather than 404.
 * Element shape is openapi-only (`UNVERIFIED` live — the server had none open).
 */
fun V2Api.sessionTerminals(sessionID: String): List<SessionTerminalInfo> = v2ObjectList(
    dataArray(callBody(builder("experimental/session/$sessionID/terminal", false).get().build())),
).map(SessionTerminalInfo::fromJson)

/**
 * `POST /api/experimental/session/{sessionID}/terminal` with a
 * `PersistentPty.CreateInput` body → `data` info (`persistentPty.create`,
 * `persistent-pty.ts:46-52`; body `schema/src/persistent-pty.ts:26-33`).
 * `UNVERIFIED` live (openapi is the contract; write probe refused).
 *
 * Wire-required keys are always sent: [title], [args] (default `[]`), [env]
 * (default `{}`). `command`, `cwd` and `size` are `optional()` upstream
 * (`persistent-pty.ts:27-32`) and are omitted when unset; [size] needs both
 * `cols` and `rows` `> 0`.
 */
fun V2Api.createSessionTerminal(
    sessionID: String,
    title: String,
    args: List<String> = emptyList(),
    env: Map<String, String> = emptyMap(),
    command: String? = null,
    cwd: String? = null,
    size: SessionTerminalSize? = null,
): SessionTerminalInfo {
    val body = JSONObject().put("title", title).put("args", JSONArray(args))
    val entries = JSONObject()
    for ((key, value) in env) entries.put(key, value)
    body.put("env", entries)
    if (!command.isNullOrBlank()) body.put("command", command)
    if (!cwd.isNullOrBlank()) body.put("cwd", cwd)
    if (size != null) body.put("size", size.toJson())
    return SessionTerminalInfo.fromJson(
        dataObject(postJson("experimental/session/$sessionID/terminal", false, body)),
    )
}

/**
 * `GET /api/experimental/session/{sessionID}/terminal/read?lines=` → the most
 * recently controlled terminal's screen, or `null` when none exists
 * (`persistentPty.read`, `persistent-pty.ts:23-37`).
 *
 * Measured live 2026-09-23: `{"data":null}` when the session has no terminal
 * (also for an unknown `sessionID`), `lines=0` ⇒ `400`
 * `{"_tag":"InvalidRequestError",…}`. `dataObject` cannot express that null —
 * it would collapse to `{}` — so the envelope is unwrapped explicitly.
 *
 * Read never changes selection or takes control: blank rows are preserved and
 * `screen` dimensions/cursor stay relative to the live screen. [lines] is the
 * last physical rows to return — omitted ⇒ live terminal height, larger counts
 * include retained history; the server accepts `1..65535` (`ReadLines`,
 * `schema/src/persistent-pty.ts:50-52`). Selection is server-local and resets
 * on restart.
 */
fun V2Api.readSessionTerminal(sessionID: String, lines: Int? = null): SessionTerminalRead? {
    val root = parseObject(
        callBody(
            builder("experimental/session/$sessionID/terminal/read", false) {
                if (lines != null) it.addQueryParameter("lines", lines.toString())
            }.get().build(),
        ),
    )
    return root.optJSONObject("data")?.let(SessionTerminalRead::fromJson)
}

/** `{cols, rows}` — both `> 0` wherever it appears (`CreateInput.size`, `Info.size`). */
data class SessionTerminalSize(val cols: Int, val rows: Int) {
    fun toJson(): JSONObject = JSONObject().put("cols", cols).put("rows", rows)

    companion object {
        fun fromJson(obj: JSONObject?): SessionTerminalSize = SessionTerminalSize(
            cols = obj?.optInt("cols", 0) ?: 0,
            rows = obj?.optInt("rows", 0) ?: 0,
        )
    }
}

/**
 * `PersistentPty.Info` — `Pty.Info` fields (`schema/src/pty.ts:22-32`) plus
 * `sessionID`, `foregroundProcess`, `size`, `output{head,tail}`
 * (`schema/src/persistent-pty.ts:9-16`). `UNVERIFIED` live: list answered `[]`,
 * so names/optionality are the openapi schema.
 */
data class SessionTerminalInfo(
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
    val foregroundProcess: String?,
    val size: SessionTerminalSize,
    /** Retained output window: `output.head` / `output.tail` line counts. */
    val outputHead: Int,
    val outputTail: Int,
    val raw: JSONObject,
) {
    companion object {
        fun fromJson(obj: JSONObject): SessionTerminalInfo {
            val size = obj.optJSONObject("size")
            val output = obj.optJSONObject("output")
            return SessionTerminalInfo(
                id = obj.optString("id", ""),
                title = obj.optString("title", ""),
                command = obj.optString("command", ""),
                args = v2StringList(obj.optJSONArray("args")),
                cwd = obj.optString("cwd", ""),
                status = obj.optString("status", ""),
                pid = obj.optLong("pid", 0L),
                exitCode = (obj.opt("exitCode") as? Number)?.toLong(),
                sessionID = obj.optString("sessionID", ""),
                foregroundProcess = optStringOrNull(obj, "foregroundProcess"),
                size = SessionTerminalSize.fromJson(size),
                outputHead = output?.optInt("head", 0) ?: 0,
                outputTail = output?.optInt("tail", 0) ?: 0,
                raw = obj,
            )
        }
    }
}

/**
 * `PersistentPty.ReadResult` — `{ptyID, title, cwd, foregroundProcess,
 * screen{text, cols, rows, cursor{x,y}}}` (`schema/src/persistent-pty.ts:54-66`),
 * flattened: [text]/[cols]/[rows]/[cursorX]/[cursorY] come from `screen`.
 * `UNVERIFIED` live — read answered `{"data":null}` (no current terminal).
 */
data class SessionTerminalRead(
    val ptyID: String,
    val title: String,
    val cwd: String,
    val foregroundProcess: String?,
    val text: String,
    val cols: Int,
    val rows: Int,
    val cursorX: Int,
    val cursorY: Int,
    val raw: JSONObject,
) {
    companion object {
        fun fromJson(obj: JSONObject): SessionTerminalRead {
            val screen = obj.optJSONObject("screen")
            val cursor = screen?.optJSONObject("cursor")
            return SessionTerminalRead(
                ptyID = obj.optString("ptyID", ""),
                title = obj.optString("title", ""),
                cwd = obj.optString("cwd", ""),
                foregroundProcess = optStringOrNull(obj, "foregroundProcess"),
                text = screen?.optString("text", "") ?: "",
                cols = screen?.optInt("cols", 0) ?: 0,
                rows = screen?.optInt("rows", 0) ?: 0,
                cursorX = cursor?.optInt("x", 0) ?: 0,
                cursorY = cursor?.optInt("y", 0) ?: 0,
                raw = obj,
            )
        }
    }
}
