package com.xiufeigo.opencodeshell.v2

import com.xiufeigo.opencodeshell.basicAuthorizationHeader
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Runtime decision "does this server speak v2?" plus the **coverage table**
 * (documentation only — the runtime entry points are [probe]/[probeUrl]).
 *
 * Probe rule: `GET /api/info` → JSON with `version` starting with `"2."`
 * ⇒ [ServerGeneration.V2]; anything else ⇒ [ServerGeneration.UNKNOWN]
 * (offline, proxy HTML, auth wall, non-2.x server): the caller surfaces an
 * honest error and retries the probe on the next connect.
 *
 * **B-7:** one of those negatives is not like the others — a server that
 * answered with a real, non-2.x version. The generation stays
 * [ServerGeneration.UNKNOWN] (nothing downstream changes), but
 * [ProbeResult.incompatibleVersion] carries the version so the session route can
 * render upstream's `IncompatibleServerPanel` (`session/route-error.tsx:26-28`)
 * instead of a generic failure.
 *
 * [decideInfo] is a pure function of (status, content-type, body) so the rule
 * is unit-testable without a network; [probe] bolts the HTTP calls on top.
 */
object V2Capability {

    /** Whether a shell capability is reachable over v2 or not at all. */
    enum class Support {
        /** A live v2 route exists; see [Capability.v2Route]. */
        V2,

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
     * Documentation table: the shell's capability → route map, measured live
     * against **2.0.11** (`127.0.0.1:4095`, GET-only) plus the live
     * `/openapi.json` route table (113 paths). Not consulted at runtime —
     * the live wiring goes through [probe]/[probeUrl] and `V2Api` directly.
     */
    val COVERAGE: List<Capability> = listOf(
        // --- health / discovery ------------------------------------------------
        Capability(
            "probe.info", "Server generation detection (v2)", Support.V2,
            "GET /api/info", "—",
            "Measured: {\"version\":\"2.0.11\",…} on 4095; v2 iff version starts with \"2.\".",
        ),
        Capability(
            "health", "Server liveness", Support.V2,
            "GET /api/info", "GET /global/health",
            "Liveness only — the generation decision is probe.info, never /api/health (404 on 2.0.11).",
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
        // --- permission --------------------------------------------------------
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
            "form.delete", "Dismiss a form", Support.V2,
            "DELETE /api/session/{id}/form/{formID}", "—",
            "Measured 2026-09-22 on 2.0.11: the route exists — a bogus session " +
                "answers SessionNotFoundError, not the SPA catch-all. It answers " +
                "409 FormAlreadySettledError once the form is replied (the old " +
                "verdict was a post-reply probe and wrongly read that as \"no route\").",
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
        Capability(
            "session.background", "Background blocking session tools", Support.V2,
            "POST /api/session/{id}/background", "—",
            "openapi.json only (204, empty body); idle requests are a no-op. UNVERIFIED live.",
        ),
        Capability(
            "session.generate", "Generate text from session context", Support.V2,
            "POST /api/session/{id}/generate", "—",
            "openapi.json only ({prompt} → {data:{text}}); never mutates history. UNVERIFIED live.",
        ),
        Capability(
            "session.synthetic", "Add synthetic message", Support.V2,
            "POST /api/session/{id}/synthetic", "—",
            "openapi.json only ({id?,text,description?,metadata?,delivery?,resume?}); UNVERIFIED live.",
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
     * @param authWall the server answered `401`/`403` — an auth problem, not a
     *   generation verdict. A real 1.x / SPA / proxy answer never sets this.
     * @param credentialsSent whether the probe offered a Basic credential; with
     *   [authWall] this splits "no password was on the wire" (fix the saved
     *   server) from "the server rejected what we sent" (fix the secret).
     */
    data class ProbeResult(
        val generation: ServerGeneration,
        val version: String? = null,
        val healthy: Boolean? = null,
        val httpStatus: Int?,
        val contentType: String?,
        val detail: String,
        val directory: String? = null,
        val authWall: Boolean = false,
        val credentialsSent: Boolean = true,
    ) {
        /** Back-compat: true only for a confirmed v2 server. */
        val v2: Boolean get() = generation == ServerGeneration.V2

        /**
         * B-7: the version of a server that answered `/api/info` with a
         * **real OpenCode version that is not 2.x** — the one UNKNOWN that is a
         * positive identification rather than a failed measurement (auth wall,
         * SPA HTML, unreachable and non-JSON all leave [version] null).
         *
         * Upstream carries the same distinction on its health row
         * (`runtime/server/health.ts:8` `{ healthy: false, incompatible: true }`)
         * and renders the full-screen `IncompatibleServerPanel` from it
         * (`session/route-error.tsx:26-28`), which is why the version travels
         * with the verdict: the panel names it.
         */
        val incompatibleVersion: String?
            get() = version?.takeIf { generation != ServerGeneration.V2 }

        /** [incompatibleVersion] as a boolean — the panel's mount condition. */
        val incompatible: Boolean get() = incompatibleVersion != null

        /**
         * A fresh probe round could still change the verdict: the server never
         * answered (`httpStatus == null`) or it answered with an auth wall
         * (credentials may since have been fixed). Any other UNKNOWN is a
         * definitive negative — the host is up and it is not 2.x.
         */
        val isRetryable: Boolean get() = authWall || httpStatus == null
    }

    /** Convenience: the shell's wiring switch only needs the boolean. */
    fun isV2(result: ProbeResult): Boolean = result.generation == ServerGeneration.V2

    /** Generation switch for a probed server (UNKNOWN keeps prior behavior). */
    fun generationOf(result: ProbeResult): ServerGeneration = result.generation

    /** Rows the shell can move to [V2Api] today. */
    fun v2Capabilities(): List<Capability> = COVERAGE.filter { it.support == Support.V2 }

    /**
     * Pure decision for the `GET /api/info` probe (v2 signal). No network, so
     * the whole rule is unit-testable — see `V2ApiTest`.
     *
     * The measured cases this encodes (2026-09-21):
     * - v2 server (2.0.11) → `200`, JSON, `{"version":"2.0.11",…}` ⇒ V2.
     * - v1 server (1.18.31) → `200`, `text/html`, SPA shell ⇒ UNKNOWN.
     * - v2 `/api/health` → `404` empty: never a v2 signal (kept only as a
     *   regression pin — see the test).
     * - `401`/`403` ⇒ UNKNOWN with [ProbeResult.authWall] — the server is up
     *   and wants a credential, which is **not** a generation verdict (3-device
     *   logcat 2026-09-24: the probe had sent no `Authorization`, curl with the
     *   stored Basic secret got `200 {"version":"2.0.11"}` on the same URL).
     *   [credentialsSent] splits "none was on the wire" from "rejected".
     * - any other non-200 (500/…) ⇒ UNKNOWN, reason carries the status.
     */
    fun decideInfo(
        statusCode: Int,
        contentType: String?,
        body: String?,
        credentialsSent: Boolean = true,
    ): ProbeResult {
        val ctype = contentType?.lowercase().orEmpty()
        val trimmed = body?.trim().orEmpty()
        if (statusCode == 401 || statusCode == 403) {
            val detail = if (credentialsSent) {
                "HTTP $statusCode — the server rejected the credential (not a generation verdict)"
            } else {
                "HTTP $statusCode — auth required and no credential was on the wire (not a generation verdict)"
            }
            return ProbeResult(
                ServerGeneration.UNKNOWN,
                null,
                null,
                statusCode,
                contentType,
                detail,
                authWall = true,
                credentialsSent = credentialsSent,
            )
        }
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
     * Live probe: `GET /api/info` (V2 iff JSON `version` starts with `"2."`),
     * otherwise UNKNOWN. On V2 only, a confirming `GET /api/location` captures
     * the server's default directory — its failure never demotes the verdict.
     *
     * Network errors are `UNKNOWN` with `httpStatus == null`, so a caller can
     * distinguish them from a negative verdict. An auth wall (`401`/`403`) is
     * `UNKNOWN` with [ProbeResult.authWall] set — callers retry with a
     * credential rather than reporting "not a 2.x server".
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
        val v2 = decideInfo(
            info.status,
            info.contentType,
            info.body,
            credentialsSent = basicAuthorizationHeader(username, password) != null,
        )
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
        return v2
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
        } catch (_: Exception) {
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
