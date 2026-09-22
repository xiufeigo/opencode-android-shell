package com.xiufeigo.opencodeshell.v2

import com.xiufeigo.opencodeshell.ChatStore
import com.xiufeigo.opencodeshell.OpenCodeClient
import okhttp3.HttpUrl
import okhttp3.Request

/**
 * Wave 1 dual-protocol seam: the minimal network surface `ChatViewModel` needs
 * from either server generation.
 *
 * `V1Transport` wraps the existing [OpenCodeClient] + `EventStream` with
 * byte-identical behavior; `V2Transport` uses [V2Api] plus the v2 streams
 * (`GET /api/event` + per-session `GET /api/experimental/session/{id}/log`).
 * The ViewModel holds exactly one of them; every other ViewModel concern
 * (state flows, revisions, notifications, catalogs) is generation-agnostic.
 *
 * Blocking calls (like both clients): call from an IO dispatcher.
 */
interface SessionTransport : AutoCloseable {
    /** Which generation this transport speaks. */
    val generation: ServerGeneration

    // ------------------------------------------------------------ snapshot

    /** Newest-first transcript window (`SNAPSHOT_MESSAGE_LIMIT`-sized). */
    fun snapshotMessages(sessionID: String, limit: Int): List<ChatStore.UiMessage>

    /** Authoritative session row (title, directory, cost, revert boundary). */
    fun fetchSession(sessionID: String): SessionRow?

    /** Pending permission asks for the session (both transports: session view). */
    fun fetchPermissions(sessionID: String): List<OpenCodeClient.PermissionRequest>

    // --------------------------------------------------------------- writes

    /**
     * Plain prompt. [delivery] ∈ `"steer"` | `"queue"`; `resume=false` admits without running.
     *
     * WAVE 2.4b: returns the admitted message id (`msg_…`) so a `delivery=queue`
     * admit can join the composer queue optimistically; null on v1 (the
     * `prompt_async` ack carries no id the client can use). The v2 structured
     * prompt fields (`agents`/`skills`/`metadata`) travel through
     * [V2Transport.sendStructuredPrompt], not this common shape.
     */
    fun sendPrompt(
        sessionID: String,
        text: String,
        files: List<V2Api.PromptFile>,
        messageID: String?,
        delivery: String?,
        resume: Boolean?,
    ): String?

    /** Switch the session's agent before (or without) sending. */
    fun switchAgent(sessionID: String, agent: String)

    /** Switch the session's model (`providerID`/`modelID`/`variant?`). */
    fun switchModel(sessionID: String, providerID: String, modelID: String, variant: String?)

    /** Shell turn. */
    fun sendShell(sessionID: String, command: String, messageID: String?)

    /**
     * Server slash command. [delivery] threads through on v2 (a queued command
     * must not reconfigure the running turn, same rule as prompts); v1 ignores it.
     */
    fun sendCommand(
        sessionID: String,
        name: String,
        text: String,
        files: List<V2Api.PromptFile>,
        delivery: String? = null,
    )

    /** Interrupt the running turn (`resume` is a v2-only query flag; v1 ignores it). */
    fun interrupt(sessionID: String, resume: Boolean? = null)

    /** Reply to a permission ask (`once` | `always` | `reject`; optional reject note). */
    fun replyPermission(sessionID: String, requestID: String, decision: String, message: String? = null)

    // -------------------------------------------------------------- sessions

    /** `POST /session` → the new id. */
    fun createSession(directory: String?, title: String?): String

    /** Rename; returns the new title, or null when the server did not apply it. */
    fun renameSession(sessionID: String, title: String): String?

    /** Delete; true when the server acknowledged. */
    fun deleteSession(sessionID: String): Boolean

    /** Revert boundary write (generation-specific shape; see impls). */
    fun stageRevert(sessionID: String, messageID: String): String?

    /** Clear a staged revert. */
    fun clearRevert(sessionID: String)

    /** Commit a staged revert. */
    fun commitRevert(sessionID: String)

    /** Fork; returns the new session id. */
    fun forkSession(sessionID: String, before: String?): String?

    // ---------------------------------------------------------------- streams

    /**
     * Builds the generation's event-stream request(s) for [sessionID].
     *
     * v1 returns one request (`/global/event`); v2 returns two —
     * `GET /api/event` plus the per-session log stream with `?after=<maxSeq>`
     * replay — because only the log stream carries durable per-session frames.
     * The caller owns the `EventStream` lifecycle; this only builds requests so
     * the frame parsing stays next to the adapter that understands it.
     */
    fun streamRequests(sessionID: String?, afterSeq: Long?): List<Request>

    /** Parse one SSE frame (already unwrapped from `data:`) into store events. */
    fun parseFrame(type: String, data: org.json.JSONObject): List<ChatStore.Event>

    /** Session that owns an (unwrapped) frame, or null when session-less. */
    fun frameSessionID(data: org.json.JSONObject): String?

    /**
     * Authoritative live run status for the session (`"running"`/`"busy"`/
     * `"retry"` while a turn is alive), or null when the session is not
     * active, the call failed, or the generation has no such endpoint (v1 —
     * the default). v2: `GET /api/session/active` →
     * `{"data":{ses_…:{"type":"running"}}}` (measured 2026-09-22). A long
     * foreground tool call emits no part traffic at all, so the snapshot
     * liveness grace must defer to this, not to a frame-age heuristic.
     */
    fun activeRunStatus(sessionID: String): String? = null
}

/** Authoritative session row, generation-neutral (v1 `Session` / v2 `SessionInfo` converge here). */
data class SessionRow(
    val id: String,
    val title: String?,
    val directory: String?,
    val cost: Double?,
    val revertMessageID: String?,
    val agent: String?,
    val providerID: String?,
    val modelID: String?,
)

/** Detected server generation, persisted per server key in ServerConfigStore. */
enum class ServerGeneration {
    /** Speaks the v2 API surface (2.x): `GET /api/info` returns JSON with a `2.` version. */
    V2,

    /** Speaks the v1 surface: `GET /global/health` returns JSON with a version. */
    V1,

    /** Neither probe answered conclusively (offline, proxy HTML, auth wall). */
    UNKNOWN
}
