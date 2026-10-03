package com.xiufeigo.opencodeshell.v2

import com.xiufeigo.opencodeshell.ChatStore
import com.xiufeigo.opencodeshell.OpenCodeClient
import com.xiufeigo.opencodeshell.ui.session.export.SessionExport
import okhttp3.HttpUrl
import okhttp3.Request

/**
 * The v2-only seam: the minimal network surface `ChatViewModel` needs.
 *
 * `V2Transport` uses [V2Api] plus the v2 streams
 * (`GET /api/event` + per-session `GET /api/experimental/session/{id}/log`).
 * The ViewModel holds exactly one; every other ViewModel concern
 * (state flows, revisions, notifications, catalogs) is transport-agnostic.
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

    /**
     * Interrupt the running turn (`resume` is a v2-only query flag; v1 ignores
     * it). Returns **whether the server says it actually interrupted an
     * execution** — `false` for the idle no-op
     * (`{"interrupted":false}`, measured) and, because
     * `session.interrupt` only reaches an execution "owned by this OpenCode
     * process" (`_upstream/packages/protocol/src/groups/session.ts:747-748`),
     * for a turn some other process is running.
     *
     * The answer is the only evidence that a Stop reached the backend, so the
     * caller must not discard it: a Stop that was never delivered is the
     * "composer looks idle, the turn keeps running" failure (emulator-5556).
     * v1 has no such route and answers `true`.
     */
    fun interrupt(sessionID: String, resume: Boolean? = null): Boolean = true

    /** Reply to a permission ask (`once` | `always` | `reject`; optional reject note). */
    fun replyPermission(sessionID: String, requestID: String, decision: String, message: String? = null)

    // ------------------------------------------ v2 session control (P1 seams)
    //
    // The six remaining `session`-domain routes (wire contracts and upstream
    // citations in [V2Api]; all six are documented in
    // `_upstream/packages/protocol/src/groups/session.ts` and measured
    // UNVERIFIED live). v2-only — none of them exists on v1.

    /** `POST /api/session/{id}/background` — background blocking tools; idle is a no-op. */
    fun backgroundSession(sessionID: String)

    /** `PUT /api/session/{id}/environment` body `{variables}` — replace the session's shell env. */
    fun setSessionEnvironment(sessionID: String, variables: Map<String, String>)

    /**
     * `POST /api/session/{id}/generate` body `{prompt}` → transient `{text}`
     * generated from the session context (history is never mutated).
     *
     * [model], when non-null, is pinned on the session
     * (`POST /api/session/{id}/model`) **before** the generate: the route's
     * payload is `{prompt}` alone (`protocol/src/groups/session.ts:705-721`), so
     * a session carrying no model of its own has no other way to run and answers
     * 503 `Missing API key` (device v14). The caller decides whether to pin
     * ([SessionModelSync.plan]); a null [model] leaves the session's own
     * selection alone.
     */
    fun generateFromContext(sessionID: String, prompt: String, model: SessionModelSync.Pin? = null): String

    /**
     * `POST /api/session/{id}/move` body `{directory, delivery?}` — move the
     * session to another project directory at the delivery boundary
     * (`"steer"` | `"queue"`; null = server default).
     */
    fun moveSession(sessionID: String, directory: String, delivery: String? = null)

    /**
     * `POST /api/session/{id}/synthetic` body `{id?, text, description?,
     * metadata?, delivery?, resume?}` → the admitted inbox row's id.
     * `resume=false` admits without running.
     */
    fun sendSynthetic(
        sessionID: String,
        text: String,
        description: String? = null,
        id: String? = null,
        delivery: String? = null,
        resume: Boolean? = null,
        metadata: org.json.JSONObject? = null,
    ): String?

    /** `POST /api/session/{id}/view` body `{idle: <epoch millis>}` — mark one idle transition viewed. */
    fun markSessionViewed(sessionID: String, idle: Long)

    // -------------------------------------------------------------- sessions

    /** `POST /session` → the new id. */
    fun createSession(directory: String?, title: String?): String

    /**
     * Rename (`PATCH /api/session/{id}`), confirmed by a re-read.
     *
     * The answer is a [RenameResult], not a nullable title: v81 P1 found the two
     * things a caller has to tell apart collapsing into one `null` — 「the server
     * refused the write by its own code」 (`500`, the measured broken-location
     * session) and 「the connection never answered」 were both reported to the user
     * as 「the server did not confirm it. Check the connection and try again.」,
     * a diagnosis that was wrong and a retry that could never work. The strict
     * half of the contract is unchanged: a title only counts as applied when the
     * server holds exactly it ([RenameConfirmation.confirmed]), and only within
     * the bounded re-read window ([RenameConfirmation.ATTEMPTS]).
     *
     * Blocking (like every other call on this interface): call it off the main
     * thread — the window makes it sleep up to `ATTEMPTS × RETRY_DELAY_MS`.
     */
    fun renameSession(sessionID: String, title: String): RenameResult

    /**
     * Delete; `true` when the session no longer exists afterwards.
     *
     * v81 P2: a `404` counts as success — the server does not have it, which is
     * the state this write asks for — so a stale row can be cleared instead of
     * being reported as 「the server did not confirm it」 forever. See
     * [V2Transport.deleteSession] for the measured case.
     */
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

    // --------------------------------------------------------------- browse

    /**
     * Immediate children of [absolute], files and directories together.
     *
     * v1 `GET /file?path=<absolute>` → `FileNode[]`, 200 for the server cwd and
     * its descendants but **500 `{"name":"UnknownError"}`** for anything above
     * or beside it (`C:\Users`, `..`, `/`) — measured 2026-09-22 on 1.18.31,
     * so the browsable tree is cwd-locked; see [browseEscapeAllowed].
     * v2 `GET /api/fs/list` with ONE stable location (the server filesystem
     * root) plus `path=` relative to it → `{"location",data:[FsEntry]}` where
     * `data[].path` is relative to the location **with the requested path as
     * prefix** (`?path=home/x` → `home/x/child/`, trailing separator on
     * directories) — measured 2.0.11. The location MUST stay fixed across
     * browses: every new `location[directory]` value persists one project row
     * server-side (`core/src/location.ts` → `Project.resolve` upsert, and the
     * protocol has no project-delete route), so a per-browsed-dir location
     * leaves one garbage `canonical` record per folder. Measured 2026-09-25
     * on 4095: stable-location `path=` (relative, `.`, `..`, absolute) lists
     * 200 without touching `/api/project`; the 2026-09-24 "absolute `path=`
     * outside the location is 500" reading no longer holds on this build.
     * Throws on failure; callers map that to their empty/error state.
     */
    fun listDirectory(absolute: String): List<DirEntry>

    /**
     * Fuzzy path search for the picker's suggestions; directories only.
     *
     * v1 `GET /find/file?query=` → bare `["AMD\\","AppV\\","at.exe",…]` —
     * trailing `\`/`/` marks a directory (measured 1.18.31). v2
     * `GET /api/fs/find?query=&type=directory&limit=&location[directory]=` →
     * the `{location,data}` envelope, paths relative to the location like
     * [listDirectory]. `query` is **required** on v2 (400 without it) and both
     * matchers are fuzzy/ranked — measured hits that do not contain the query,
     * so callers must not filter literally.
     */
    fun searchDirectories(query: String, limit: Int = 8): List<DirEntry>

    /**
     * Path search for `@` mentions and the file palette — files *and*
     * directories as absolute path strings. Same wire as [searchDirectories]
     * without a type filter (v1 `/find/file` has none; v2 omits `type=`).
     * Directory results keep their trailing separator on v1, matching the
     * historical `OpenCodeClient.findFiles` shape the mention popover shows.
     * [directory] scopes the search to one workspace (v1 `directory` query /
     * v2 `location[directory]`); null searches the generation's default root.
     */
    fun searchFiles(query: String, limit: Int = 20, directory: String? = null): List<String>

    /**
     * The 「根目录」 button's target. v1: the server cwd (`GET /path` →
     * `directory`) — the only root the tree can be walked from. v2: `/`
     * (POSIX-first; a Windows v2 server would want `C:/`, derivable as
     * `FsBrowse.root(browseHome)`).
     */
    val browseRoot: String

    /**
     * `~` expansion. v1 `GET /path` → `home` (measured
     * `{"home":"C:\\Users\\xiufe","directory":"C:\\Windows\\System32",…}`).
     * v2 has **no home field**; `GET /api/location` → `directory` is a
     * deliberate approximation (the location/project root, usually home-adjacent).
     * null when the server did not answer.
     */
    val browseHome: String?

    /**
     * The server's working directory: what `POST /session` defaults to and what
     * the review tree roots on. Deliberately distinct from [browseHome] — v1
     * measures `{"home":"C:\\Users\\xiufe","directory":"C:\\Windows\\System32"}`.
     * v2 answers `GET /api/location` → `directory` (the same value as
     * [browseHome] there: v2 exposes no separate home). Callers must not read
     * this off the v1 `GET /path` route — on v2 that is the SPA catch-all
     * (`200 text/html`, measured 2.0.11). null when the server did not answer.
     */
    val browseCwd: String?

    /**
     * Whether the browser may leave [browseRoot]. v1 serves only the server-cwd
     * subtree (measured `GET /file` 500s outside it) while `POST /session?directory=`
     * still accepts any absolute path — cannot browse ≠ cannot select. v2
     * accepts any absolute `location[directory]` (measured `/`, `/home`,
     * `/home/<user>`): true.
     */
    val browseEscapeAllowed: Boolean

    // ------------------------------------------- export / review / home reads
    //
    // Every route below exists on BOTH generations under a different path, so
    // the pre-seam call sites (Home export, SessionScreen export + context tab,
    // Home projects column, review panel) route here instead of a bare
    // `OpenCodeClient` — on v2 the unprefixed v1 paths answer the SPA catch-all
    // (`200 text/html`) and the JSON parse throws (measured 2.0.11: 23 of 24
    // unprefixed probes did).

    /**
     * Raw `{info, messages}` payload of the session export (A8) and of the
     * context tab, exactly as the wire carries it.
     *
     * v1 `GET /session/:id` → bare `Session.Info`, `GET /session/:id/message`
     * → bare `WithParts[]` (full history — v1 has no cursor); both stay raw
     * through [com.xiufeigo.opencodeshell.OpenCodeClient.sessionJson]/
     * [com.xiufeigo.opencodeshell.OpenCodeClient.messagesJson]. v2
     * `GET /api/session/{id}` → `{data:SessionInfo}` and
     * `GET /api/session/{id}/message?order=asc` → `{data:[…],cursor}` —
     * [V2Api.sessionRaw]/[V2Api.messagesRaw] unwrap the envelope but keep the
     * element objects verbatim (paged via `cursor` alone; measured
     * `cursor+order` together is 400). [directory] is the v1 `?directory=`
     * scope only; v2 session reads take no location. Throws on failure.
     *
     * Concurrent reads of the same session share one drain. A definitive
     * rejection is returned as-is rather than retried: the export menu, the
     * context/usage tab and Home's row action all read this frame, and a cursor
     * rejection ([V2Api.ApiException.isInvalidCursor]) is client-side state
     * that cannot change by asking again. A *transient* failure is retried one
     * level up, by the caller that owns the backoff policy
     * ([com.xiufeigo.opencodeshell.v2.TransportRetry] — v32 BUG-1), which is
     * why this method itself stays a single attempt.
     */
    fun fetchExportFrame(sessionID: String, directory: String?): SessionExport.Frame

    /**
     * Home's projects column and the draft's project selector.
     *
     * v1 `GET /project` → `[{id,worktree,vcs?,icon?,time,sandboxes}]`, entries
     * without `worktree` dropped ([OpenCodeClient.Project.fromJson]). v2
     * `GET /api/project` → a **bare array** of
     * `[{id,canonical,time,sandboxes,vcs?}]` (measured 2.0.11) — no `worktree`
     * and no `icon`; [V2Project] rows map `canonical` into the `worktree` slot
     * and `iconColor` stays null. Throws on failure.
     */
    fun listProjects(): List<OpenCodeClient.Project>

    /**
     * Review panel's Last-turn scope. v1 `GET /session/:id/diff[?messageID=]`
     * → `FileDiff[]` (the caller passes no messageID: whole-session diff);
     * v2 `GET /api/session/{id}/diff?from&to&context` → `{data:[FileDiff]}`
     * with a from/to message range instead — both are the same turn range the
     * panel shows when the params are omitted (measured `{data:[]}` live).
     * [directory] is the v1 `?directory=` scope; v2 ignores it (session-scoped
     * route). Throws on failure.
     */
    fun sessionDiff(sessionID: String, directory: String? = null): List<OpenCodeClient.FileDiff>

    /**
     * Review header branch chip + the Git/Branch scope availability rule.
     * v1 `GET /vcs` → `{branch?,default_branch?}`; v2 `GET /api/vcs` →
     * `{location,data:{branch:{current?,default?}}}` (measured
     * `{"branch":{}}` with no repo — both halves null). [directory] is the v1
     * `?directory=` / v2 `location[directory]` scope. Throws on failure.
     */
    fun vcsInfo(directory: String? = null): OpenCodeClient.VcsInfo

    /**
     * Review panel's Git / Branch scopes. v1 `GET /vcs/diff?mode=` accepts
     * `mode=git|branch` (`working` is a measured 400); v2
     * `GET /api/vcs/diff?mode=` accepts `working|branch|committed` and
     * answers **400 for `git`** (measured 2026-09-22 on 2.0.11) — [V2Transport]
     * maps `git`→`working` (same meaning: uncommitted working-tree changes).
     * [directory] is the v1 `?directory=` / v2 `location[directory]` scope.
     * Throws on failure.
     */
    fun vcsDiff(mode: String, directory: String? = null): List<OpenCodeClient.FileDiff>

    /**
     * Review panel's file preview.
     *
     * v1 `GET /file/content?path=<absolute>` → JSON
     * `{type:"text"|"binary",content,…}` (binary content is base64 — measured).
     * v2 `GET /api/fs/read/<rel>` → **raw bytes**, JSON never involved, and
     * `<rel>` must be relative to the `location[directory]` scope
     * ([directory]; measured `404 FileNotFoundError` outside it,
     * `500 UnknownError` when the location directory itself does not exist).
     * [V2Transport] converts the absolute path through
     * [com.xiufeigo.opencodeshell.ui.home.FsBrowse.fsReadRelative] and rebuilds
     * the v1 `FileContent` shape from the bytes ([fileContentFromBytes]).
     * Throws on failure.
     */
    fun fileContent(path: String, directory: String? = null): OpenCodeClient.FileContent
}

/**
 * One browsable filesystem entry (v1 `FileNode` / v2 `FsEntry` converge here).
 * [absolute] is the client-side absolute path (v2 relative `FsEntry.path` values
 * are joined onto their `location[directory]`); [ignored] is the v1 git-ignore
 * flag (v2 never sets it).
 */
data class DirEntry(
    val name: String,
    val absolute: String,
    val isDirectory: Boolean,
    val ignored: Boolean = false,
)

/**
 * Why a browse / list read answered empty or null. The directory picker used to
 * collapse every failure onto `onResult(null)`, so a probe that never installed
 * a transport rendered exactly the same 「无法加载此目录」 as a real I/O error.
 *
 * Carried as an optional `onFailure` callback on the `ChatViewModel` browse
 * methods and through `ChatViewModel.browseFailure` for callers that only have
 * the legacy single-callback shape.
 */
sealed interface TransportFailure {
    /**
     * The generation probe has not landed yet — the read is premature, not
     * broken. Retry after `ChatViewModel.generation` settles (or just after a
     * beat: the V2 install bumps `sessionsRevision`, which re-drives Home).
     */
    data object ProbePending : TransportFailure

    /** No transport installed: the probe returned [ServerGeneration.UNKNOWN] for a non-auth reason. */
    data object NotConnected : TransportFailure

    /**
     * No transport installed because the probe never got an answer
     * (`httpStatus == null`): reverse proxy / backend unreachable. Retryable —
     * unlike [NotConnected], which is a definitive "up, but not 2.x" verdict.
     */
    data object Unreachable : TransportFailure

    /**
     * The probe hit the auth wall (`401`/`403`) — the saved server has no
     * password, or the server rejected it. Same split as
     * `HealthFailure.MissingCredentials` / `Unauthorized`, flattened here
     * because the browse callers only need "go fix the server row".
     */
    data object AuthRequired : TransportFailure

    /** A live transport call failed (network / HTTP / parse). */
    data class RequestFailed(val message: String?) : TransportFailure
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
    /** `SessionInfo.model.variant` — the session's durable reasoning-effort label (`"default"`/absent = none). */
    val variant: String? = null,
    /**
     * Whether the server actually sent a title for this row. `title` carries
     * the client's `Untitled` fallback when it did not, and that fallback must
     * never be learned as the session's name (it would stick on every surface
     * that reads the per-id registry, including the rename seed).
     */
    val hasTitle: Boolean = false,
)

/** Detected server generation. */
enum class ServerGeneration {
    /** Speaks the v2 API surface (2.x): `GET /api/info` returns JSON with a `2.` version. */
    V2,

    /** Neither probe answered conclusively (offline, proxy HTML, auth wall). */
    UNKNOWN
}
