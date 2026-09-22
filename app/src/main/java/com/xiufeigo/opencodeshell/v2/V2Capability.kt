package com.xiufeigo.opencodeshell.v2

import com.xiufeigo.opencodeshell.basicAuthorizationHeader
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

/**
 * Runtime decision "which generation does this server speak?" plus the
 * **coverage table** the Android shell wires against.
 *
 * Probe rule (A5 §0.1, verified live 2026-09-21):
 * - `GET /api/info` → JSON with `version` starting with `"2."` ⇒ [ServerGeneration.V2].
 *   (2.0.11 answers `{"version":"2.0.11",…}`; `/api/health` is 404 there and
 *   every v1 endpoint returns SPA HTML on a v2 server — never v2 signals.)
 * - else `GET /global/health` → JSON with a `version` field ⇒ [ServerGeneration.V1].
 *   (1.18.31 answers `{"healthy":true,"version":"1.18.31"}`; on a v1 server
 *   `/api/info` is `200 text/html` — the SPA shell, never a v1 signal.)
 * - else [ServerGeneration.UNKNOWN] (offline, proxy HTML, auth wall): the
 *   caller keeps prior behavior and retries the probe on the next connect.
 *
 * ## Why the probe cannot be a status code
 *
 * On a v1 server every unimplemented `/api/…` path answers **`200` +
 * `text/html`** (the SPA shell), not a JSON 404 — measured for `/api/info`
 * itself. On a v2 server the v1 surface is equally SPA HTML. So "200" means
 * nothing and "404" means nothing on either generation. The only reliable
 * discriminator is the **body**: JSON with a `version` field, and which route
 * served it decides the generation — a `V1Transport` must never be selected
 * for a v2 server and vice versa.
 *
 * [decideInfo]/[decideHealth] are pure functions of (status, content-type,
 * body) so the rule is unit-testable without a network; [probe] bolts the two
 * HTTP calls on top.
 */
object V2Capability {

    /** Whether a shell capability is reachable over v2, only over v1, or not at all. */
    enum class Support {
        /** A live v2 route exists; see [Capability.v2Route]. */
        V2,

        /** The server has no v2 route; the v1 route is the only transport. */
        V1_ONLY,

        /** Neither face exposes it (or the v2 route does not exist yet). */
        UNSUPPORTED,
    }

    /**
     * One row of the capability contract: an Android-shell capability mapped to
     * the transport that actually serves it.
     *
     * @param id stable key for the Lead's wiring switch.
     * @param label human-readable capability name.
     * @param support measured verdict.
     * @param v2Route the v2 route that serves it, `"—"` when none.
     * @param v1Route the v1 route today's `OpenCodeClient` uses, `"—"` when none.
     * @param note the measurement that produced [support], or the `UNVERIFIED`
     *   marker when the verdict rests on the OpenAPI document alone.
     */
    data class Capability(
        val id: String,
        val label: String,
        val support: Support,
        val v2Route: String,
        val v1Route: String,
        val note: String,
    )

    /**
     * The shell's capability → transport table, re-probed live against
     * **2.0.11** (`127.0.0.1:4095`, GET-only) plus the live `/openapi.json`
     * route table (113 paths). The old table rested on the 1.18.31 hybrid,
     * where every unimplemented v2 path answered SPA HTML — on 2.0.11 those
     * routes exist.
     *
     * This is the contract the Lead wires against: exactly the rows marked
     * [Support.V2] may be switched to [V2Api], the [Support.V1_ONLY] rows must
     * keep their current v1 call, and the [Support.UNSUPPORTED] rows have no v2
     * substitute at all.
     */
    val COVERAGE: List<Capability> = listOf(
        // --- health / discovery ------------------------------------------------
        Capability(
            "probe.info", "Server generation detection (v2)", Support.V2,
            "GET /api/info", "—",
            "Measured: {\"version\":\"2.0.11\",…} on 4095; v2 iff version starts with \"2.\".",
        ),
        Capability(
            "probe.health", "Server generation detection (v1)", Support.V1_ONLY,
            "—", "GET /global/health",
            "Measured: {\"healthy\":true,\"version\":\"1.18.31\"} on 4096; v1 iff a version field is present.",
        ),
        Capability(
            "health", "Server liveness", Support.V2,
            "GET /api/info", "GET /global/health",
            "Liveness only — the generation decision is probe.info/probe.health, never /api/health (404 on 2.0.11).",
        ),
        Capability(
            "path", "Server cwd / default directory", Support.V2,
            "GET /api/location", "GET /path",
            "Measured: bare LocationInfo (no envelope): {directory, project{id,directory,canonical}}.",
        ),

        // --- sessions ----------------------------------------------------------
        Capability(
            "session.list", "Session list", Support.V2,
            "GET /api/session", "GET /session",
            "Measured: {data:[…]} + {cursor:{previous,next}} once limit is set; ?search= verified.",
        ),
        Capability(
            "session.get", "One session", Support.V2,
            "GET /api/session/{id}", "GET /session/{id}",
            "Measured: {data:SessionV2Info}; 404 body is {\"_tag\":\"SessionNotFoundError\"}.",
        ),
        Capability(
            "session.create", "New session", Support.V2,
            "POST /api/session", "POST /session",
            "Measured 200 with {data:{id,projectID,…}}; custom id / workspace key UNVERIFIED.",
        ),
        Capability(
            "session.rename", "Rename session", Support.V2,
            "PATCH /api/session/{id}", "PATCH /session/{id}",
            "Measured 204 for {\"title\"}; body allows only {title|null, permissions|null}.",
        ),
        Capability(
            "session.delete", "Delete session", Support.V2,
            "DELETE /api/session/{id}", "DELETE /session/{id}",
            "Measured 204; GET afterwards is 404 {\"_tag\":\"SessionNotFoundError\"}.",
        ),
        Capability(
            "session.active", "Is a turn draining?", Support.V2,
            "GET /api/session/active", "—",
            "Measured both states: {data:{}} idle, {data:{ses_…:{type:\"running\"}}} busy.",
        ),

        // --- transcript --------------------------------------------------------
        Capability(
            "message.list", "Transcript (messages)", Support.V2,
            "GET /api/session/{id}/message", "GET /session/{id}/message",
            "Measured: {data, cursor}; a FLAT tagged union on `type`, not v1's {info, parts}.",
        ),
        Capability(
            "message.context", "Active context window", Support.V2,
            "GET /api/session/{id}/context", "—",
            "Measured {data:[]} on a fresh session; same Message shape as message.list.",
        ),
        Capability(
            "session.history", "Durable event replay (paged)", Support.UNSUPPORTED,
            "—", "—",
            "404 on 2.0.11 and absent from openapi.json (113 paths); the replay carrier is the log SSE stream.",
        ),
        Capability(
            "session.diff", "Changed files for a session", Support.V2,
            "GET /api/session/{id}/diff", "GET /session/{id}/diff",
            "Measured {data:[]} live; params are from&to&context (messageID is gone).",
        ),
        Capability(
            "session.todo", "Session todos", Support.V1_ONLY,
            "—", "GET /session/{id}/todo",
            "No todo route in openapi.json; v2 derives todos from todowrite tool state in the transcript.",
        ),
        Capability(
            "message.single", "One message", Support.V2,
            "GET /api/session/{id}/message/{messageID}", "—",
            "Measured 200 {data:{…}}; v1 has no equivalent (export uses the full list).",
        ),

        // --- prompting ---------------------------------------------------------
        Capability(
            "session.prompt", "Send a prompt", Support.V2,
            "POST /api/session/{id}/prompt", "POST /session/{id}/prompt_async",
            "Measured: FLAT body {text(required),files?,agents?,skills?,metadata?,delivery?,resume?} → {data:{id,sessionID,time,type:\"user\",payload,delivery}}; the old {prompt:{text}} wrapper is 400 InvalidRequestError. model/agent/variant travel via their own switch routes (steer only).",
        ),
        Capability(
            "session.prompt_async", "Fire-and-forget prompt", Support.V1_ONLY,
            "—", "POST /session/{id}/prompt_async",
            "No prompt_async route in openapi.json; v1-only body shape.",
        ),
        Capability(
            "session.shell", "Shell command turn", Support.V2,
            "POST /api/session/{id}/shell", "POST /session/{id}/shell",
            "Measured 204 for {command(required),id?}; the v1 {agent,model} body fields are gone.",
        ),
        Capability(
            "session.command", "Run a server slash command", Support.V2,
            "POST /api/session/{id}/command", "POST /session/{id}/command",
            "Measured 204 for {name(required),text(required),files?,agents?,skills?,delivery?}; v1's {command,arguments,agent,model,variant} is gone.",
        ),
        Capability(
            "session.interrupt", "Abort the running turn", Support.V2,
            "POST /api/session/{id}/interrupt", "POST /session/{id}/abort",
            "Measured 204 on an idle session (documented no-op); `resume` is a query flag, not a body field.",
        ),
        Capability(
            "session.compact", "Compact the conversation", Support.V2,
            "POST /api/session/{id}/compact", "POST /session/{id}/summarize",
            "Measured 200 on a real session; body {id?,delivery?} answers {data:Session.Inbox.Compaction}.",
        ),
        Capability(
            "session.wait", "Block until the turn is idle", Support.V2,
            "POST /api/experimental/session/{id}/wait", "—",
            "In openapi.json under the experimental prefix; GET variant does not exist.",
        ),
        Capability(
            "session.agent.switch", "Switch session agent", Support.V2,
            "POST /api/session/{id}/agent", "—",
            "Measured 204 for {\"agent\":\"build\"}.",
        ),
        Capability(
            "session.model.switch", "Switch session model", Support.V2,
            "POST /api/session/{id}/model", "—",
            "Measured 204 for {\"model\":{id,providerID}}.",
        ),

        // --- revert ------------------------------------------------------------
        Capability(
            "session.revert.stage", "Stage a revert boundary", Support.V2,
            "POST /api/session/{id}/revert/stage", "—",
            "Measured {data:{messageID,files:[]}}; busy session answers 409 SessionBusyError.",
        ),
        Capability(
            "session.revert.clear", "Clear the staged revert", Support.V2,
            "DELETE /api/session/{id}/revert", "—",
            "Measured 204; POST …/revert/clear is 404 (not in openapi.json).",
        ),
        Capability(
            "session.revert.commit", "Commit the staged revert", Support.V2,
            "POST /api/session/{id}/revert/commit", "—",
            "Empty body per openapi.json; busy session answers 409 SessionBusyError.",
        ),
        Capability(
            "session.fork", "Fork a session", Support.V2,
            "POST /api/session/{id}/fork", "POST /session/{id}/fork",
            "Measured {data:{id,fork:{sessionID,boundary}}}; body field is {before?(^msg_)}, not v1's {messageID?}; fork of an empty session is 400 empty_session.",
        ),
        Capability(
            "session.share", "Share a session", Support.V1_ONLY,
            "—", "POST/DELETE /session/{id}/share",
            "No share route in openapi.json; v2 shares via export/snapshot instead (Wave 2).",
        ),
        Capability(
            "session.archive", "Archive a session", Support.V1_ONLY,
            "—", "PATCH /session/{id} {time:{archived}}",
            "v2 Session.Info carries time.archived read-only; no archive write route.",
        ),

        // --- permission / question --------------------------------------------
        Capability(
            "permission.list", "Pending permissions (location)", Support.V2,
            "GET /api/permission/request", "GET /permission",
            "Measured {location, data:[]}; element shape is document-derived (no pending ask).",
        ),
        Capability(
            "permission.session", "Pending permissions (session)", Support.V2,
            "GET /api/session/{id}/permission", "GET /permission",
            "Measured {data:[]} — this list has NO location envelope.",
        ),
        Capability(
            "permission.reply", "Reply to a permission ask", Support.V2,
            "POST /api/session/{id}/permission/{requestID}/reply", "POST /permission/{requestID}/reply",
            "Measured: {decision(required:once|always|reject),message?}; {reply} is 400. Self-created asks 404 on reply (effect:allow auto-resolves).",
        ),
        Capability(
            "permission.create", "Raise a permission ask", Support.V2,
            "POST /api/session/{id}/permission", "—",
            "Measured {data:{id:per_,effect}} for {action(required),resources(required),save?,metadata?,source?,agent?}.",
        ),
        Capability(
            "permission.get", "One permission ask", Support.V2,
            "GET /api/session/{id}/permission/{requestID}", "—",
            "Measured 404 {\"_tag\":\"PermissionNotFoundError\"} for unknown ids.",
        ),
        Capability(
            "permission.saved", "Saved 'always' rules", Support.V2,
            "GET/DELETE /api/permission/saved(/{id})", "—",
            "Measured {data:[]} for GET; the DELETE is UNVERIFIED (nothing to delete).",
        ),
        // --- question: gone in v2 (A5 §0.4) ------------------------------------
        // Every /api/question* and /api/session/{id}/question* route 404s on
        // 2.0.11 and is absent from openapi.json. The carrier is the form
        // system below; the old rows stay V1_ONLY so v1 servers keep working.
        Capability(
            "question.list", "Pending questions (location)", Support.V1_ONLY,
            "—", "GET /question",
            "404 on 2.0.11; absent from openapi.json. v2 carrier: GET /api/form.",
        ),
        Capability(
            "question.session", "Pending questions (session)", Support.V1_ONLY,
            "—", "GET /question",
            "404 on 2.0.11; absent from openapi.json. v2 carrier: GET …/form.",
        ),
        Capability(
            "question.reply", "Answer a question ask", Support.V1_ONLY,
            "—", "POST /question/{requestID}/reply",
            "No v2 route; v2 carrier: POST …/form/{formID}/reply{answer}.",
        ),
        Capability(
            "question.reject", "Dismiss a question ask", Support.V1_ONLY,
            "—", "POST /question/{requestID}/reject",
            "No v2 route; v2 forms retire via form.replied/form.cancelled.",
        ),

        // --- forms (v2 replaces question REST) ---------------------------------
        Capability(
            "form.global", "Pending forms (location)", Support.V2,
            "GET /api/form", "—",
            "Measured {location,data:[]} on 4095.",
        ),
        Capability(
            "form.session", "Pending forms (session)", Support.V2,
            "GET /api/session/{id}/form", "—",
            "Measured {data:[{id:frm_,sessionID,title,fields[]}]} on a throwaway session.",
        ),
        Capability(
            "form.create", "Raise a form", Support.V2,
            "POST /api/session/{id}/form", "—",
            "Measured 200 {data:Form.Info} for {title(required),fields(required):[{key,type,…}]}.",
        ),
        Capability(
            "form.reply", "Answer a form", Support.V2,
            "POST /api/session/{id}/form/{formID}/reply", "—",
            "Measured 204 for {answer:{key:value}}.",
        ),
        Capability(
            "form.delete", "Dismiss a form", Support.UNSUPPORTED,
            "—", "—",
            "DELETE …/form/{id} answers 409 once replied; no dismiss route exists.",
        ),

        // --- catalogs ----------------------------------------------------------
        Capability(
            "provider.list", "Provider catalog", Support.V2,
            "GET /api/provider", "GET /provider",
            "Measured {location, data:[{id,name,api,request}]} — NO models map (unlike v1).",
        ),
        Capability(
            "provider.get", "One provider", Support.V2,
            "GET /api/provider/{providerID}", "GET /provider",
            "Measured 200 for opencode-go; unknown id → 404 {\"_tag\":\"ProviderNotFoundError\"}.",
        ),
        Capability(
            "model.list", "Model catalog", Support.V2,
            "GET /api/model", "GET /provider",
            "Measured ~4 MB, {location, data:[ModelV2Info]}; variants is an ARRAY of objects.",
        ),
        Capability(
            "agent.list", "Agent catalog", Support.V2,
            "GET /api/agent", "GET /agent",
            "Measured 2.0.11 entries {id,name,request,description,mode,hidden,permissions} — `name` EXISTS (Build); selectable = !hidden && (mode==primary|all).",
        ),
        Capability(
            "command.list", "Slash-command catalog", Support.V2,
            "GET /api/command", "GET /command",
            "Measured {name,description} — `template`/`source` are GONE (2.0.11); client-side template expansion is v1-only.",
        ),
        Capability(
            "skill.list", "Skill catalog", Support.V2,
            "GET /api/skill", "GET /skill",
            "Measured {id,name,description,path,content} — v1's `location` is now `path`.",
        ),
        Capability(
            "defaultModel", "Configured default model", Support.V2,
            "GET /api/model/default", "GET /config",
            "Measured {location,data:{id,modelID,providerID,…}} on 4095; the v1 `GET /config.model` string is gone.",
        ),
        Capability(
            "config.list", "Server config documents", Support.V2,
            "GET /api/config", "GET /config",
            "Measured a BARE ARRAY [{type,path,info}] (not an object); config keys live inside info.",
        ),

        // --- workspace / vcs ---------------------------------------------------
        Capability(
            "project.list", "Known projects", Support.V2,
            "GET /api/project", "GET /project",
            "Measured a BARE ARRAY [{id,canonical,time,sandboxes}] (no envelope); PATCH /api/project/{id} answers 200.",
        ),
        Capability(
            "vcs.info", "VCS status", Support.V2,
            "GET /api/vcs", "GET /vcs",
            "Measured {location,data:{branch:{}}} on 4095; v1's {branch,default_branch} shape is gone.",
        ),
        Capability(
            "vcs.branch", "VCS branch list", Support.V2,
            "GET /api/vcs/branch", "GET /vcs",
            "Measured {location,data:[]} on 4095; params search&limit.",
        ),
        Capability(
            "vcs.diff", "VCS diff", Support.V2,
            "GET /api/vcs/diff", "GET /vcs/diff",
            "Measured {location,data:[]} for mode=branch; v2 modes are working|branch|committed (+base&context).",
        ),
        Capability(
            "vcs.status", "VCS file status", Support.V2,
            "GET /api/vcs/status", "—",
            "Measured {location,data:[]} on 4095; new in v2.",
        ),
        Capability(
            "workspace.list", "Workspace catalog", Support.UNSUPPORTED,
            "—", "—",
            "Measured: /api/workspace answers 200 text/html (SPA). location[workspace] is accepted on some routes but there is no catalog endpoint.",
        ),
        Capability(
            "lsp.status", "LSP status", Support.UNSUPPORTED,
            "—", "—",
            "No /api/lsp* in openapi.json (113 paths).",
        ),
        Capability(
            "mcp.status", "MCP servers", Support.V2,
            "GET /api/mcp", "—",
            "Measured {location,data:[{name,status}]} on 4095; + GET /api/mcp/resource and experimental connect/disconnect.",
        ),
        Capability(
            "shell.list", "Background shells", Support.V2,
            "GET /api/shell", "—",
            "Measured {location,data:[]} on 4095; + GET …/output?cursor&limit. v1 has only in-turn shell.",
        ),
        Capability(
            "worktree.list", "Worktrees", Support.V2,
            "GET /api/worktree", "—",
            "In openapi.json; without ?projectID the server answers 400 InvalidRequestError (query required).",
        ),
        Capability(
            "plugin.list", "Plugins", Support.V2,
            "GET /api/plugin", "—",
            "Measured {location,data:[…]} on 4095; + POST …/check and …/update.",
        ),
        Capability(
            "info.version", "Server build info", Support.V2,
            "GET /api/info", "GET /global/health (version)",
            "Measured {\"version\":\"2.0.11\",…} on 4095 — the v2 probe signal itself.",
        ),
        Capability(
            "session.inbox", "Pending inbox items", Support.V2,
            "GET /api/session/{id}/inbox", "—",
            "Measured {data:[]} on 4095; v1 has no inbox.",
        ),
        Capability(
            "session.environ", "Session environment / move / view", Support.V2,
            "PUT …/environment, POST …/move, POST …/view", "—",
            "All three in openapi.json ({variables}, {directory,delivery?}, {idle}); v1 has none.",
        ),

        // --- filesystem --------------------------------------------------------
        Capability(
            "fs.list", "Directory listing", Support.V2,
            "GET /api/fs/list", "GET /file",
            "Measured {location, data:[{path,type}]} — paths are RELATIVE to the location directory.",
        ),
        Capability(
            "fs.read", "File content", Support.V2,
            "GET /api/fs/read-rel", "GET /file/content",
            "Measured raw bytes + extension content-type; relative path, a leading slash 500s.",
        ),
        Capability(
            "fs.find", "Find files", Support.V2,
            "GET /api/fs/find", "GET /find/file",
            "Measured {location, data:[{path,type}]} (not a string array); query is required, ranked/fuzzy.",
        ),

        // --- pty ---------------------------------------------------------------
        Capability(
            "pty.list", "Terminal list", Support.V2,
            "GET /api/pty", "GET /pty",
            "Measured {location, data:[]} with no PTY open.",
        ),
        Capability(
            "pty.create", "Open a terminal", Support.V2,
            "POST /api/pty", "POST /pty",
            "Measured 200 with {location, data:{id,status:\"running\",pid,…}}.",
        ),
        Capability(
            "pty.get", "Poll a terminal", Support.V2,
            "GET /api/pty/{ptyID}", "GET /pty/{ptyID}",
            "Measured 200 running; after DELETE 404 {\"_tag\":\"PtyNotFoundError\"}.",
        ),
        Capability(
            "pty.resize", "Resize a terminal", Support.V2,
            "PUT /api/pty/{ptyID}", "PUT /pty/{ptyID}",
            "Measured 200 echoing {title,size:{rows,cols}}.",
        ),
        Capability(
            "pty.delete", "Close a terminal", Support.V2,
            "DELETE /api/pty/{ptyID}", "DELETE /pty/{ptyID}",
            "Measured 204.",
        ),
        Capability(
            "pty.ticket", "PTY connect ticket", Support.V2,
            "POST /api/pty/{ptyID}/connect-token", "POST /pty/{id}/connect-token",
            "Measured 200 with header x-opencode-ticket: 1; without it 403 ForbiddenError.",
        ),
        Capability(
            "pty.connect", "Terminal stream (WebSocket)", Support.V2,
            "WS /api/pty/{ptyID}/connect", "WS /pty/{ptyID}/connect",
            "Measured: upgrade + first text frame without a ticket on this no-auth server.",
        ),

        // --- events ------------------------------------------------------------
        // A5 §0.3: neither /api/session/{id}/event nor /api/session/{id}/history
        // exists (both 404, absent from openapi.json). The per-session carrier
        // is the experimental log stream; history is UNSUPPORTED.
        Capability(
            "event.session", "Session event stream (SSE)", Support.V2,
            "GET /api/experimental/session/{id}/log", "GET /event",
            "Measured: opens with log.synced{aggregateID,seq}; ?after=&follow=true drive replay+live; frames are {id,type,created,data,durable?,location?} with no event:/id: lines.",
        ),
        Capability(
            "event.global", "Global event stream (SSE)", Support.V2,
            "GET /api/event", "GET /global/event",
            "Measured: opens with server.connected; mixes durable frames and v1-shaped data.info payloads; : heartbeat keepalives.",
        ),

        // --- misc v2-only surfaces --------------------------------------------
        Capability(
            "integration.list", "Provider integrations", Support.V2,
            "GET /api/integration", "—",
            "Measured 222 entries {id,name,methods,connections}; GET by id is UNVERIFIED.",
        ),
        Capability(
            "reference.list", "References", Support.V2,
            "GET /api/reference", "—",
            "Measured {location, data:[]} at both locations — element shape UNVERIFIED.",
        ),
    )

    /**
     * Outcome of a generation probe.
     *
     * @param generation the detected generation (never null).
     * @param version the server's version string when the probe got one.
     * @param healthy the v1 `healthy` flag, `null` on v2/unknown paths.
     * @param httpStatus the transport status, `null` on a network failure.
     * @param contentType the response content-type as received, `null` on failure.
     * @param detail human-readable evidence for logs and the report.
     * @param directory `GET /api/location`'s `directory` once confirmed (v2 only).
     */
    data class ProbeResult(
        val generation: ServerGeneration,
        val version: String? = null,
        val healthy: Boolean? = null,
        val httpStatus: Int?,
        val contentType: String?,
        val detail: String,
        val directory: String? = null,
    ) {
        /** Back-compat: true only for a confirmed v2 server. */
        val v2: Boolean get() = generation == ServerGeneration.V2
    }

    /** Convenience: the shell's wiring switch only needs the boolean. */
    fun isV2(result: ProbeResult): Boolean = result.generation == ServerGeneration.V2

    /** Generation switch for a probed server (UNKNOWN keeps prior behavior). */
    fun generationOf(result: ProbeResult): ServerGeneration = result.generation

    /** Rows the shell can move to [V2Api] today. */
    fun v2Capabilities(): List<Capability> = COVERAGE.filter { it.support == Support.V2 }

    /** Rows that must keep their existing v1 call. */
    fun v1OnlyCapabilities(): List<Capability> = COVERAGE.filter { it.support == Support.V1_ONLY }

    /**
     * Pure decision for the `GET /api/info` probe (v2 signal). No network, so
     * the whole rule is unit-testable — see `V2ApiTest`.
     *
     * The measured cases this encodes (2026-09-21):
     * - v2 server (2.0.11) → `200`, JSON, `{"version":"2.0.11",…}` ⇒ V2.
     * - v1 server (1.18.31) → `200`, `text/html`, SPA shell ⇒ UNKNOWN here
     *   (the v1 leg, [decideHealth], decides V1 vs UNKNOWN).
     * - v2 `/api/health` → `404` empty: never a v2 signal (kept only as a
     *   regression pin — see the test).
     * - any other non-200 (401/500/…) ⇒ UNKNOWN, reason carries the status.
     */
    fun decideInfo(statusCode: Int, contentType: String?, body: String?): ProbeResult {
        val ctype = contentType?.lowercase().orEmpty()
        val trimmed = body?.trim().orEmpty()
        if (statusCode != 200) {
            return ProbeResult(ServerGeneration.UNKNOWN, null, null, statusCode, contentType, "HTTP $statusCode, not a v2 info response")
        }
        if (ctype.contains("text/html") || trimmed.startsWith("<")) {
            return ProbeResult(ServerGeneration.UNKNOWN, null, null, statusCode, contentType, "text/html — the SPA shell, so /api/info is not served")
        }
        val json = runCatching { JSONObject(trimmed) }.getOrNull()
            ?: return ProbeResult(ServerGeneration.UNKNOWN, null, null, statusCode, contentType, "body is not JSON")
        val version = json.optString("version", "")
        if (version.isEmpty()) {
            return ProbeResult(ServerGeneration.UNKNOWN, null, null, statusCode, contentType, "JSON without the `version` marker")
        }
        if (!version.startsWith("2.")) {
            return ProbeResult(ServerGeneration.UNKNOWN, version, null, statusCode, contentType, "version $version is not a 2.x server")
        }
        return ProbeResult(
            ServerGeneration.V2,
            version = version,
            httpStatus = statusCode,
            contentType = contentType,
            detail = "v2 /api/info answered version=$version",
        )
    }

    /**
     * Pure decision for the `GET /global/health` probe (v1 signal). A JSON
     * body with a `version` field ⇒ V1 (1.18.31 answers
     * `{"healthy":true,"version":"1.18.31"}`); SPA HTML / non-JSON / missing
     * version ⇒ UNKNOWN. On a v2 server this route is SPA HTML, so V1Transport
     * is never selected for a v2 server through this leg either.
     */
    fun decideHealth(statusCode: Int, contentType: String?, body: String?): ProbeResult {
        val ctype = contentType?.lowercase().orEmpty()
        val trimmed = body?.trim().orEmpty()
        if (statusCode != 200) {
            return ProbeResult(ServerGeneration.UNKNOWN, null, null, statusCode, contentType, "HTTP $statusCode, not a v1 health response")
        }
        if (ctype.contains("text/html") || trimmed.startsWith("<")) {
            return ProbeResult(ServerGeneration.UNKNOWN, null, null, statusCode, contentType, "text/html — the SPA shell, so /global/health is not served")
        }
        val json = runCatching { JSONObject(trimmed) }.getOrNull()
            ?: return ProbeResult(ServerGeneration.UNKNOWN, null, null, statusCode, contentType, "body is not JSON")
        val version = json.optString("version", "")
        if (version.isEmpty()) {
            return ProbeResult(ServerGeneration.UNKNOWN, null, null, statusCode, contentType, "JSON without the `version` marker")
        }
        return ProbeResult(
            ServerGeneration.V1,
            version = version,
            healthy = json.optBoolean("healthy", true),
            httpStatus = statusCode,
            contentType = contentType,
            detail = "v1 /global/health answered version=$version",
        )
    }

    /**
     * Live probe: `GET /api/info` first (V2 iff JSON `version` starts with
     * `"2."`); otherwise `GET /global/health` (V1 iff JSON carries `version`);
     * otherwise UNKNOWN. On V2 only, a confirming `GET /api/location` captures
     * the server's default directory — its failure never demotes the verdict.
     *
     * A 401/403 on `/api/info` is *not* UNKNOWN-by-default: an auth-walled v2
     * server answers 401 with a JSON body, which still carries no version, so
     * the v1 leg runs and (on SPA HTML) also yields UNKNOWN — the caller then
     * surfaces "check credentials" instead of silently picking v1.
     *
     * Network errors are `UNKNOWN` with `httpStatus == null`, so a caller can
     * distinguish them from a negative verdict.
     */
    fun probe(
        baseHttpUrl: HttpUrl,
        username: String? = null,
        password: String? = null,
        http: OkHttpClient = V2Api.defaultClient(),
        directory: String? = null,
    ): ProbeResult {
        val info = get(
            baseHttpUrl.newBuilder().addPathSegment("api").addPathSegment("info").build(),
            username,
            password,
            http,
        ) ?: return ProbeResult(ServerGeneration.UNKNOWN, null, null, null, null, "unreachable: network error", null)
        val v2 = decideInfo(info.status, info.contentType, info.body)
        if (v2.generation == ServerGeneration.V2) {
            val location = get(
                baseHttpUrl.newBuilder()
                    .addPathSegment("api")
                    .addPathSegment("location")
                    .apply { if (!directory.isNullOrBlank()) addQueryParameter(V2Api.LOCATION_DIRECTORY_QUERY, directory) }
                    .build(),
                username,
                password,
                http,
            )
            val directoryValue = location?.let { probe ->
                if (probe.status != 200 || probe.contentType?.contains("text/html") == true) {
                    null
                } else {
                    runCatching { JSONObject(probe.body).optString("directory", "") }
                        .getOrNull()
                        ?.takeIf { it.isNotEmpty() }
                }
            }
            return v2.copy(
                directory = directoryValue,
                detail = if (directoryValue != null) {
                    "${v2.detail}; /api/location confirmed directory=$directoryValue"
                } else {
                    "${v2.detail}; /api/location confirmation failed"
                },
            )
        }

        val health = get(
            baseHttpUrl.newBuilder().addPathSegment("global").addPathSegment("health").build(),
            username,
            password,
            http,
        ) ?: return ProbeResult(ServerGeneration.UNKNOWN, null, null, null, null, "unreachable: network error", null)
        return decideHealth(health.status, health.contentType, health.body)
    }

    private class Raw(val status: Int, val contentType: String?, val body: String)

    /**
     * One `GET` whose failure is a value, not an exception: a probe must never
     * throw into the connect flow.
     */
    private fun get(
        url: HttpUrl,
        username: String?,
        password: String?,
        http: OkHttpClient,
    ): Raw? {
        val request = Request.Builder().url(url)
            .apply { basicAuthorizationHeader(username, password)?.let { header("Authorization", it) } }
            .get()
            .build()
        return try {
            http.newCall(request).execute().use { response ->
                Raw(response.code, response.header("Content-Type"), response.body?.string().orEmpty())
            }
        } catch (_: IOException) {
            null
        }
    }

    /** Parses a base URL, returning `null` when it is not valid HTTP(S). */
    fun probeUrl(
        url: String,
        username: String? = null,
        password: String? = null,
        http: OkHttpClient = V2Api.defaultClient(),
        directory: String? = null,
    ): ProbeResult {
        val parsed = url.toHttpUrlOrNull()
            ?: return ProbeResult(ServerGeneration.UNKNOWN, null, null, null, null, "not an HTTP(S) URL: $url")
        return probe(parsed, username, password, http, directory)
    }
}
