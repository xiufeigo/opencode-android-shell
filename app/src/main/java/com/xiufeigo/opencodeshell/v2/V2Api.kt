package com.xiufeigo.opencodeshell.v2

import com.xiufeigo.opencodeshell.OpenCodeClient
import com.xiufeigo.opencodeshell.basicAuthorizationHeader
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Typed client for the **v2** half of an opencode server's HTTP API (`/api/…`),
 * verified against a live `opencode` 1.18.31 process on `127.0.0.1:4096` whose
 * `GET /doc` advertises 162 paths. Mirrors [com.xiufeigo.opencodeshell.OpenCodeClient]
 * conventions — OkHttp + `org.json`, `basicAuthorizationHeader` for auth, a
 * non-2xx throwing [ApiException] — but shares no code with it.
 *
 * ## Envelopes actually measured (do not trust v1 muscle memory)
 *
 * | family | measured body |
 * |---|---|
 * | `GET /api/health` | bare `{"healthy":true}` — **no** envelope, no version, no marker |
 * | `GET /api/location` | bare `LocationInfo` — `{"directory":…,"project":{"id":…,"directory":…}}` |
 * | `agent`, `model`, `provider`, `command`, `skill`, `integration`, `reference`, `pty`, `fs/list`, `fs/find`, `permission/request`, `question/request` | `{"location":LocationInfo,"data":…}` |
 * | `session` list/get/create, `session/{id}/message`, `context`, `history`, session-scoped `permission`/`question`, `permission/saved` | `{"data":…}` **without** `location` |
 * | `session/{id}/message` | `{"data":[…],"cursor":{"previous":null,"next":"…"}}`, newest first unless `order=asc` |
 * | `session/{id}/history` | `{"data":[DurableEvent…],"hasMore":bool}` |
 * | writes (`agent`, `model`, `interrupt`, `wait`, `compact`, `revert/clear`, `revert/commit`) | `204`, empty body |
 *
 * `GET /api/fs/read/<rel>` is the only binary route: raw bytes with a
 * content-type derived from the extension, **no** JSON wrapper.
 *
 * ## The paths this server does NOT serve
 *
 * Every unimplemented `/api/…` path answers **`200` + `text/html`** (the SPA
 * shell), never a JSON 404 — measured for `/api/config*`, `/api/vcs*`,
 * `/api/file*`, `/api/find*`, `/api/lsp*`, `/api/mcp*`, `/api/info*`,
 * `/api/project*`, `/api/path`, `/api/global-health`, `/api/global/event`,
 * `/api/workspace` and `/api/session/{id}/{shell,prompt_async,diff,todo,abort}`.
 * A genuine v2 error is JSON with a `_tag` discriminator, e.g.
 * `404 {"_tag":"SessionNotFoundError",…}` or
 * `400 {"_tag":"InvalidRequestError","message":"Missing key\n at [\"query\"]",
 * "kind":"Query"}`. So a client that decides "v2 or not" from the status code
 * alone is wrong; see [V2Capability].
 *
 * ## Threading
 *
 * Every call is blocking (like `OpenCodeClient`); call from an IO dispatcher.
 *
 * @param baseHttpUrl server root, e.g. `http://127.0.0.1:4096`. The `/api`
 *   prefix is added here, never by the caller.
 * @param username compatibility fallback only; see `basicAuthorizationHeader`.
 * @param password `null`/empty ⇒ no `Authorization` header at all.
 * @param directory default `location[directory]` for the endpoints that accept
 *   it (`null` ⇒ the server's own process cwd). Endpoints that take no
 *   `location` parameter never send it.
 */
class V2Api(
    private val baseHttpUrl: HttpUrl,
    private val username: String? = null,
    private val password: String? = null,
    private val http: OkHttpClient = defaultClient(),
    private val directory: String? = null,
) {
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    /**
     * Releases pooled connections only — never the dispatcher, which may be the
     * app's shared client (same rule as `OpenCodeClient.close`).
     */
    fun close() {
        http.connectionPool.evictAll()
    }

    // ------------------------------------------------------------------ wire

    /**
     * `/api/<path>` on [baseHttpUrl], with `?location[directory]=` appended when
     * [withLocation] and a [directory] is configured.
     *
     * The query key is the literal `location[directory]`: the OpenAPI document
     * declares `location` with `style: deepObject, explode: true`, and a live
     * `GET /api/fs/list?location%5Bdirectory%5D=…` (percent-encoded, which is
     * what OkHttp emits) resolved to that directory. The bare `location=` name
     * is **not** accepted.
     *
     * Path segments go through `addPathSegment`, which percent-encodes `/` and
     * `\`, so an id can never re-root the URL.
     */
    private fun url(
        path: String,
        withLocation: Boolean,
        configure: (HttpUrl.Builder) -> Unit = {},
    ): HttpUrl {
        val builder = baseHttpUrl.newBuilder().addPathSegment("api")
        for (segment in path.trim('/').split('/').filter { it.isNotEmpty() }) {
            builder.addPathSegment(segment)
        }
        if (withLocation && !directory.isNullOrBlank()) {
            builder.addQueryParameter(LOCATION_DIRECTORY_QUERY, directory)
        }
        configure(builder)
        return builder.build()
    }

    private fun builder(
        path: String,
        withLocation: Boolean,
        configure: (HttpUrl.Builder) -> Unit = {},
    ): Request.Builder {
        val request = Request.Builder().url(url(path, withLocation, configure))
        basicAuthorizationHeader(username, password)?.let { request.header("Authorization", it) }
        return request
    }

    /** Blocking call returning the raw body; throws [ApiException] on non-2xx. */
    private fun callBody(request: Request): String =
        http.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: ""
            if (!response.isSuccessful) throw ApiException(response.code, errorText(response.code, body))
            body
        }

    /** Blocking call for the 204 endpoints: asserts success, discards the body. */
    private fun callUnit(request: Request) {
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val body = response.body?.string().orEmpty()
                throw ApiException(response.code, errorText(response.code, body))
            }
        }
    }

    /** Blocking call for `GET /api/fs/read/<rel>`: asserts success, keeps the bytes. */
    private fun callBytes(request: Request): ByteArray =
        http.newCall(request).execute().use { response ->
            val bytes = response.body?.bytes() ?: ByteArray(0)
            if (!response.isSuccessful) {
                throw ApiException(
                    response.code,
                    errorText(response.code, bytes.toString(Charsets.UTF_8)),
                )
            }
            bytes
        }

    private fun postJson(path: String, withLocation: Boolean, json: JSONObject): String =
        callBody(
            builder(path, withLocation).post(json.toString().toRequestBody(jsonMedia)).build(),
        )

    private fun postUnit(path: String, withLocation: Boolean, json: JSONObject) {
        callUnit(builder(path, withLocation).post(json.toString().toRequestBody(jsonMedia)).build())
    }

    /**
     * Server errors carry a `_tag` discriminator — keep it in the message. The
     * human sentence (`data.message` / `message` / `name`) is preferred over the
     * raw JSON body so an error card never renders a wall of `{"name":…}`.
     */
    private fun errorText(code: Int, body: String): String {
        val json = runCatching { JSONObject(body) }.getOrNull()
        if (json != null) {
            val tag = json.optString("_tag", "")
            val readable = json.optJSONObject("data")?.optString("message", "")?.takeIf { it.isNotBlank() }
                ?: json.optString("message", "").takeIf { it.isNotBlank() }
                ?: json.optString("name", "").takeIf { it.isNotBlank() }
                ?: ""
            val human = listOf(tag, readable).filter { it.isNotEmpty() }.distinct().joinToString(": ")
            if (human.isNotEmpty()) return "HTTP $code $human"
        }
        val detail = body.replace(WHITESPACE, " ").trim().take(200)
        return "HTTP $code" + (if (detail.isEmpty()) "" else " $detail")
    }

    private fun dataObject(body: String): JSONObject =
        parseObject(body).optJSONObject("data") ?: JSONObject()

    private fun dataArray(body: String): JSONArray = parseObject(body).optJSONArray("data") ?: JSONArray()

    /**
     * `JSONObject(body)`, but a non-JSON body becomes an [ApiException] instead of
     * an `org.json.JSONException`.
     *
     * Measured reason this matters: every unimplemented `/api/…` path on this
     * server answers **`200` + `text/html`** (the SPA shell). If capability
     * detection is ever skipped or wrong, the failure must land in the same
     * `catch (IOException)` the rest of the shell already uses, not escape as an
     * unchecked parse error. [V2Capability] is still the right guard; this is the
     * backstop.
     */
    private fun parseObject(body: String): JSONObject = try {
        JSONObject(body)
    } catch (_: JSONException) {
        throw ApiException(
            JSON_BODY_CODE,
            "response is not JSON: ${body.replace(WHITESPACE, " ").trim().take(120)}",
        )
    }

    /** Adds `location[directory]` when the caller overrode the instance default. */
    private fun located(builder: HttpUrl.Builder, override: String?) {
        if (!override.isNullOrBlank()) builder.addQueryParameter(LOCATION_DIRECTORY_QUERY, override)
    }

    // ------------------------------------------------------------------ api

    /**
     * `GET /api/health` → `{"healthy":true}`.
     *
     * Measured: the body is **exactly** that one key — no `version`, no marker
     * string. Liveness only; the capability decision lives in [V2Capability].
     */
    fun health(): Health = Health.fromJson(parseObject(callBody(builder("health", false).get().build())))

    /**
     * `GET /api/location` → `LocationInfo`.
     *
     * Measured: `{"directory":"C:\\WINDOWS\\system32","project":{"id":"global",
     * "directory":"C:\\"}}` with **no** envelope; `workspaceID` is absent
     * whenever the server runs without a workspace registry.
     */
    fun location(): LocationInfo =
        LocationInfo.fromJson(parseObject(callBody(builder("location", true).get().build())))

    /**
     * `GET /api/session` → `Page<SessionInfo>`.
     *
     * Measured: `{"data":[…]}`, and once `limit` is supplied a
     * `"cursor":{"previous","next"}` sibling appears (opaque base64; feed it
     * back as [cursor] and do **not** combine it with [order]). Newest first by
     * default. `search` was verified live (`?search=Friendly` → exactly the one
     * matching session).
     *
     * Unlike the catalogs this route does **not** use `location[directory]`:
     * its OpenAPI `directory` parameter is a plain string query, and
     * [directoryScoped] sends `&directory=<instance default>`. Measured live:
     * `?directory=C:\Windows\System32` returned exactly the 3 of 50 sessions
     * whose `location.directory` matched. `project`/`subpath`/`workspace`
     * overrides are `UNVERIFIED` (never issued live).
     */
    fun sessions(
        limit: Int? = null,
        order: String? = null,
        search: String? = null,
        cursor: String? = null,
        project: String? = null,
        subpath: String? = null,
        workspace: String? = null,
        directoryScoped: Boolean = true,
    ): Page<SessionInfo> = parsePage(
        callBody(
            builder("session", false) {
                if (directoryScoped && !directory.isNullOrBlank()) {
                    it.addQueryParameter("directory", directory)
                }
                if (limit != null && limit > 0) it.addQueryParameter("limit", limit.toString())
                if (!order.isNullOrBlank()) it.addQueryParameter("order", order)
                if (!search.isNullOrBlank()) it.addQueryParameter("search", search)
                if (!cursor.isNullOrBlank()) it.addQueryParameter("cursor", cursor)
                if (!project.isNullOrBlank()) it.addQueryParameter("project", project)
                if (!subpath.isNullOrBlank()) it.addQueryParameter("subpath", subpath)
                if (!workspace.isNullOrBlank()) it.addQueryParameter("workspace", workspace)
            }.get().build(),
        ),
        SessionInfo::fromJson,
    )

    /**
     * `POST /api/session` body `{id?, agent?, model?, location?}` →
     * `{"data":SessionInfo}`.
     *
     * Measured live: `{"location":{"directory":"<repo>"},"agent":"build",
     * "model":{"id":"gpt-5.6-sol-fast","providerID":"openai"}}` answered 200 with
     * the created session, whose `projectID` was that directory's project hash
     * and whose title was the server-generated `New session - <ISO instant>`.
     * A caller-supplied [id], the [workspace] location key and the [agent]/
     * [model] fields of a session created without a prompt are `UNVERIFIED`
     * beyond the echo above.
     */
    fun createSession(
        agent: String? = null,
        model: ModelRef? = null,
        id: String? = null,
        title: String? = null,
        directory: String? = null,
        workspace: String? = null,
    ): SessionInfo {
        val body = JSONObject()
        if (!id.isNullOrBlank()) body.put("id", id)
        if (!title.isNullOrBlank()) body.put("title", title)
        if (!agent.isNullOrBlank()) body.put("agent", agent)
        if (model != null) body.put("model", model.toJson())
        val location = locationRef(directory, workspace)
        if (location != null) body.put("location", location)
        return SessionInfo.fromJson(dataObject(postJson("session", false, body)))
    }

    /** `GET /api/session/{sessionID}` → `{"data":SessionInfo}`. Measured live. */
    fun session(sessionID: String): SessionInfo =
        SessionInfo.fromJson(dataObject(callBody(builder("session/$sessionID", false).get().build())))

    /**
     * `GET /api/session/active` → `{"data":{"ses_…":{"type":"running"}}}`.
     *
     * Measured both ways: `{"data":{}}` while idle and
     * `{"data":{"ses_…":{"type":"running"}}}` while a turn drained. Sessions
     * absent from the map are inactive.
     */
    fun activeSessions(): Map<String, String> {
        val data = dataObject(callBody(builder("session/active", false).get().build()))
        val out = LinkedHashMap<String, String>()
        val keys = data.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            out[key] = data.optJSONObject(key)?.optString("type", "").orEmpty()
        }
        return out
    }

    /**
     * `GET /api/session/{sessionID}/message` → `Page<Message>`, newest first.
     *
     * Measured: `{"data":[…],"cursor":{"previous":null,"next":"eyJ…"}}`. Items are
     * a **flat tagged union** on `type` (`user` | `assistant` | `system` |
     * `shell` | `synthetic` | `compaction` | `agent-switched` |
     * `model-switched`), *not* v1's `{info, parts}` pair:
     *
     * ```
     * {"id":"msg_…","time":{"created":1789969243559},"text":"Say hi","type":"user"}
     * {"id":"msg_…","time":{"created":…,"completed":…},"type":"assistant","agent":"build",
     *  "model":{"id":"gpt-5.6-sol","providerID":"openai","variant":"default"},
     *  "content":[{"type":"text","id":"msg_…","text":"Hi!"}],
     *  "snapshot":{"start":"2549b7…"}}
     * ```
     *
     * Measured gotcha: for a session whose transcript predates the v2 durable
     * store this returns an **empty page** even though `GET /session/{id}/message`
     * (v1) returns the same conversation. The list populates once a v2 prompt
     * has driven the agent loop.
     */
    fun messages(
        sessionID: String,
        limit: Int? = null,
        order: String? = null,
        cursor: String? = null,
        type: String? = null,
    ): Page<Message> = parsePage(
        callBody(
            builder("session/$sessionID/message", false) {
                if (limit != null && limit > 0) it.addQueryParameter("limit", limit.toString())
                if (!order.isNullOrBlank()) it.addQueryParameter("order", order)
                if (!cursor.isNullOrBlank()) it.addQueryParameter("cursor", cursor)
                if (!type.isNullOrBlank()) it.addQueryParameter("type", type)
            }.get().build(),
        ),
        Message::fromJson,
    )

    /**
     * `GET /api/session/{sessionID}/message/{messageID}` → `{data:Message}`.
     * Measured 200 live; v1 has no single-message read.
     */
    fun message(sessionID: String, messageID: String): Message =
        Message.fromJson(dataObject(callBody(builder("session/$sessionID/message/$messageID", false).get().build())))

    /**
     * `GET /api/session/{sessionID}/context` → `{"data":[Message…]}` — the active
     * context, i.e. every message after the last compaction. Measured empty on a
     * fresh session.
     */
    fun context(sessionID: String): List<Message> = v2ObjectList(
        dataArray(callBody(builder("session/$sessionID/context", false).get().build())),
    ).map(Message::fromJson)

    // `history` REMOVED (A5 §0.3): `GET /api/session/{id}/history` 404s on
    // 2.0.11 and is absent from openapi.json. Durable replay is the log SSE
    // stream ([sessionLogRequest]); `DurableEvent` stays as the frame DTO.

    /**
     * `POST /api/session/{sessionID}/prompt` — FLAT body (A5 §0.6, verified
     * live 2026-09-21 on a throwaway session):
     * `{id?, text(required), files?, agents?, skills?, metadata?, delivery?,
     * resume?}` → `{data:{id:msg_, sessionID, time, type:"user",
     * payload:{text}, delivery}}`.
     *
     * The old `{prompt:{text}}` wrapper answers
     * `400 InvalidRequestError "Missing key at [\"text\"]"` — flat only.
     * `resume=false` admits without running; omitted/`resume` unset runs the
     * turn. `delivery` ∈ `"steer"` | `"queue"`.
     *
     * model/agent/variant are NOT body fields — switch them via
     * [switchAgent]/[switchModel] first (steer only; see `V2Transport`).
     */
    fun prompt(
        sessionID: String,
        text: String,
        files: List<PromptFile> = emptyList(),
        agents: List<JSONObject>? = null,
        skills: List<JSONObject>? = null,
        metadata: JSONObject? = null,
        delivery: String? = null,
        resume: Boolean? = null,
        messageID: String? = null,
    ): SessionAdmitted {
        val body = JSONObject().put("text", text)
        if (files.isNotEmpty()) {
            val array = JSONArray()
            for (file in files) array.put(file.toJson())
            body.put("files", array)
        }
        agents?.let { body.put("agents", JSONArray(it)) }
        skills?.let { body.put("skills", JSONArray(it)) }
        metadata?.let { body.put("metadata", it) }
        if (!delivery.isNullOrBlank()) body.put("delivery", delivery)
        if (resume != null) body.put("resume", resume)
        if (!messageID.isNullOrBlank()) body.put("id", messageID)
        return SessionAdmitted.fromJson(dataObject(postJson("session/$sessionID/prompt", false, body)))
    }

    /**
     * `POST /api/session/{sessionID}/agent` body `{agent}` → **204**, no body.
     * Measured live: `{"agent":"build"}` → 204.
     */
    fun switchAgent(sessionID: String, agent: String) {
        postUnit("session/$sessionID/agent", false, JSONObject().put("agent", agent))
    }

    /**
     * `POST /api/session/{sessionID}/model` body `{model:ModelRef}` → **204**.
     * Measured live: `{"model":{"id":"gpt-5.6-sol-fast","providerID":"openai"}}`
     * → 204.
     */
    fun switchModel(sessionID: String, model: ModelRef) {
        postUnit("session/$sessionID/model", false, JSONObject().put("model", model.toJson()))
    }

    /**
     * `POST /api/session/{sessionID}/interrupt[?resume=]` → 200 +
     * `{interrupted:bool}` (`SessionInterruptResponse`; measured
     * `{"interrupted":false}` on an idle session). `resume` is a QUERY flag,
     * not a body field.
     */
    fun interrupt(sessionID: String, resume: Boolean? = null): Boolean {
        // 204-with-body would break OkHttp (`HTTP 204 had non-zero
        // Content-Length`); the server answers 204 empty on idle and 200 +
        // `{interrupted}` when it preempts — tolerate either.
        http.newCall(
            builder("session/$sessionID/interrupt", false) {
                if (resume != null) it.addQueryParameter("resume", resume.toString())
            }.post(JSONObject().toString().toRequestBody(jsonMedia)).build(),
        ).execute().use { response ->
            val body = response.body?.string() ?: ""
            if (!response.isSuccessful) throw ApiException(response.code, errorText(response.code, body))
            return runCatching { JSONObject(body).optBoolean("interrupted", true) }.getOrDefault(true)
        }
    }

    /**
     * `POST /api/session/{sessionID}/compact` body `{id?, delivery?}` →
     * `{data:Session.Inbox.Compaction}` (openapi.json; measured 200 live on a
     * real session). The old empty-body `compact()` is kept as the no-arg
     * overload; both target the same route.
     */
    fun compact(sessionID: String, messageID: String? = null, delivery: String? = null): JSONObject {
        val body = JSONObject()
        if (!messageID.isNullOrBlank()) body.put("id", messageID)
        if (!delivery.isNullOrBlank()) body.put("delivery", delivery)
        return dataObject(postJson("session/$sessionID/compact", false, body))
    }

    /** `POST /api/session/{sessionID}/compact` with an empty body (same route). */
    fun compact(sessionID: String) {
        postUnit("session/$sessionID/compact", false, JSONObject())
    }

    /**
     * `POST /api/experimental/session/{sessionID}/wait` (openapi.json carries
     * it ONLY under the experimental prefix — the old `/api/session/{id}/wait`
     * below targeted a dead route). Body `{}`.
     */
    fun wait(sessionID: String) {
        postUnit("experimental/session/$sessionID/wait", false, JSONObject())
    }

    /**
     * `POST /api/session/{sessionID}/revert/stage` body `{messageID, files?}` →
     * `{"data":RevertState}`.
     *
     * Measured live: `{"messageID":"msg_…","files":false}` →
     * `{"data":{"messageID":"msg_…","snapshot":"<sha>","diff":"","files":[]}}`.
     */
    fun revertStage(sessionID: String, messageID: String, files: Boolean? = null): RevertState {
        val body = JSONObject().put("messageID", messageID)
        if (files != null) body.put("files", files)
        return RevertState.fromJson(dataObject(postJson("session/$sessionID/revert/stage", false, body)))
    }

    /** `DELETE /api/session/{sessionID}/revert` → **204**. Measured live.
     *
     * A5 §0.7: `POST …/revert/clear` does NOT exist (404, absent from
     * openapi.json) — the old `revertClear` below targeted a dead route.
     */
    fun revertClear(sessionID: String) {
        callUnit(builder("session/$sessionID/revert", false).delete().build())
    }

    /**
     * `POST /api/session/{sessionID}/revert/commit` → **204**.
     *
     * `UNVERIFIED`: not issued live — it makes a staged revert permanent and the
     * probe only staged then cleared. Status/body are from the document.
     */
    fun revertCommit(sessionID: String) {
        postUnit("session/$sessionID/revert/commit", false, JSONObject())
    }

    /**
     * `POST /api/session/{sessionID}/shell` body `{id?(^msg),
     * command(required)}` → **204**. Measured 204 live; the v1 `{agent,model}`
     * fields are gone (the session's agent applies).
     */
    fun shell(sessionID: String, command: String, messageID: String? = null) {
        val body = JSONObject().put("command", command)
        if (!messageID.isNullOrBlank()) body.put("id", messageID)
        postUnit("session/$sessionID/shell", false, body)
    }

    /**
     * `POST /api/session/{sessionID}/command` body `{name(required),
     * text(required), files?, agents?, skills?, delivery?}` → **204**.
     * Measured 204 live; v1's `{command,arguments,agent,model,variant}` shape
     * is gone (`command→name`, `arguments→text`).
     */
    fun command(
        sessionID: String,
        name: String,
        text: String,
        files: List<PromptFile> = emptyList(),
        delivery: String? = null,
    ) {
        val body = JSONObject().put("name", name).put("text", text)
        if (files.isNotEmpty()) {
            val array = JSONArray()
            for (file in files) array.put(file.toJson())
            body.put("files", array)
        }
        if (!delivery.isNullOrBlank()) body.put("delivery", delivery)
        postUnit("session/$sessionID/command", false, body)
    }

    /**
     * `POST /api/session/{sessionID}/fork` body `{before?(^msg)}` →
     * `{data:{id,fork:{sessionID,boundary},…}}`.
     *
     * A5 §0.7: v1's `{messageID?}` is wrong here — `{messageID}` is silently
     * IGNORED (measured: forks the whole session anyway). Measured fork of a
     * non-empty session answers the new `Session.Info`; fork of an empty
     * session is `400 empty_session`.
     */
    fun fork(sessionID: String, before: String? = null): SessionInfo {
        val body = JSONObject()
        if (!before.isNullOrBlank()) body.put("before", before)
        return SessionInfo.fromJson(dataObject(postJson("session/$sessionID/fork", false, body)))
    }

    /**
     * `PATCH /api/session/{sessionID}` body `{title|null,
     * permissions|null}` (`additionalProperties:false`) → **204**.
     * Measured 204 live.
     */
    fun renameSession(sessionID: String, title: String?) {
        val body = JSONObject()
        if (title == null) body.put("title", JSONObject.NULL) else body.put("title", title)
        callUnit(builder("session/$sessionID", false).patch(body.toString().toRequestBody(jsonMedia)).build())
    }

    /**
     * `DELETE /api/session/{sessionID}` → **204**. Measured 204 live
     * (subsequent GET is 404 `SessionNotFoundError`).
     */
    fun deleteSession(sessionID: String) {
        callUnit(builder("session/$sessionID", false).delete().build())
    }

    /**
     * `GET /api/session/{sessionID}/permission` → `{"data":[PermissionRequest…]}`.
     *
     * Measured: the session-scoped list carries **no** `location` envelope
     * (unlike `/api/permission/request`) and was empty on the probe server.
     */
    fun sessionPermissions(sessionID: String): List<PermissionRequest> = v2ObjectList(
        dataArray(callBody(builder("session/$sessionID/permission", false).get().build())),
    ).map(PermissionRequest::fromJson)

    /**
     * `POST /api/session/{sessionID}/permission/{requestID}/reply` body
     * `{decision(required: once|always|reject), message?}` → **204**.
     *
     * A5 §0.5: the old `{reply}` body is
     * `400 InvalidRequestError "Missing key at [\"decision\"]"`
     * (`additionalProperties:false`). Verified live: `{decision:"once"}` on a
     * self-created ask 404s (`PermissionNotFoundError` — self-created asks
     * auto-resolve with `effect:allow`); `{reply:"once"}` 400s.
     */
    fun replyPermission(sessionID: String, requestID: String, decision: String, message: String? = null) {
        val body = JSONObject().put("decision", decision)
        if (!message.isNullOrBlank()) body.put("message", message)
        postUnit("session/$sessionID/permission/$requestID/reply", false, body)
    }

    /**
     * `POST /api/session/{sessionID}/permission` body
     * `{id?(^per), action(required), resources(required), save?, metadata?,
     * source?, agent?}` → `{data:{id:per_, effect}}`.
     *
     * Measured live: self-created asks answer `effect:allow` and never appear
     * in the pending lists (nothing to reply to — replying 404s with
     * `PermissionNotFoundError`).
     */
    fun createPermission(
        sessionID: String,
        action: String,
        resources: List<String>,
        id: String? = null,
        save: List<String> = emptyList(),
    ): JSONObject {
        val body = JSONObject().put("action", action)
        val res = JSONArray()
        for (r in resources) res.put(r)
        body.put("resources", res)
        if (!id.isNullOrBlank()) body.put("id", id)
        if (save.isNotEmpty()) {
            val arr = JSONArray()
            for (s in save) arr.put(s)
            body.put("save", arr)
        }
        return dataObject(postJson("session/$sessionID/permission", false, body))
    }

    /**
     * `GET /api/session/{sessionID}/permission/{requestID}` → `{data:Request}`.
     * Measured 404 `{"_tag":"PermissionNotFoundError",…}` for unknown ids.
     */
    fun permission(sessionID: String, requestID: String): PermissionRequest =
        PermissionRequest.fromJson(
            dataObject(callBody(builder("session/$sessionID/permission/$requestID", false).get().build())),
        )

    /** `GET /api/permission/request` → `{"location":…,"data":[PermissionRequest…]}`. Measured empty live. */
    fun pendingPermissions(locationDirectory: String? = null): List<PermissionRequest> = v2ObjectList(
        dataArray(
            callBody(builder("permission/request", true) { located(it, locationDirectory) }.get().build()),
        ),
    ).map(PermissionRequest::fromJson)

    /**
     * `GET /api/permission/saved` → `{"data":["…"]}` (no `location`).
     * Measured live: `{"data":[]}` — strings, so the element *is* the id.
     */
    fun savedPermissions(): List<String> =
        v2StringList(dataArray(callBody(builder("permission/saved", false).get().build())))

    /**
     * `DELETE /api/permission/saved/{id}` → **204**.
     *
     * `UNVERIFIED`: the live server had no saved entry to delete. Method/status
     * are from the OpenAPI document.
     */
    fun removeSavedPermission(id: String) {
        callUnit(builder("permission/saved/$id", false).delete().build())
    }

    // -------------------------------------------------- forms (v2 replaces question REST)
    //
    // A5 §0.4: question endpoints do NOT exist in v2 — every
    // `/api/question*` and `/api/session/{id}/question*` route 404s and is
    // absent from openapi.json (113 paths). The carrier is the form system:
    // `GET /api/form`, `GET/POST /api/session/{id}/form`,
    // `GET/DELETE …/form/{formID}`, `POST …/form/{formID}/reply`.
    //
    // The old `sessionQuestions`/`replyQuestion`/`rejectQuestion`/
    // `pendingQuestions` are therefore REMOVED (not deprecated): v1 question
    // behavior lives in `OpenCodeClient` + `V1Transport` and is untouched.

    /**
     * `GET /api/session/{sessionID}/form` → `{data:[FormInfo…]}` (no
     * location envelope). Measured live with a self-created form.
     */
    fun sessionForms(sessionID: String): List<FormInfo> = v2ObjectList(
        dataArray(callBody(builder("session/$sessionID/form", false).get().build())),
    ).map(FormInfo::fromJson)

    /** `GET /api/form` → `{location,data:[FormInfo…]}`. Measured `{data:[]}` live. */
    fun pendingForms(locationDirectory: String? = null): List<FormInfo> = v2ObjectList(
        dataArray(
            callBody(builder("form", true) { located(it, locationDirectory) }.get().build()),
        ),
    ).map(FormInfo::fromJson)

    /**
     * `POST /api/session/{sessionID}/form` body `{id?(^frm),
     * title(required), metadata?, fields(required)}` → `{data:FormInfo}`.
     * Measured 200 live for `{title, fields:[{key,type,…}]}`.
     */
    fun createForm(sessionID: String, title: String, fields: JSONArray, id: String? = null): FormInfo {
        val body = JSONObject().put("title", title).put("fields", fields)
        if (!id.isNullOrBlank()) body.put("id", id)
        return FormInfo.fromJson(dataObject(postJson("session/$sessionID/form", false, body)))
    }

    /**
     * `GET /api/session/{sessionID}/form/{formID}` → `{data:FormInfo}`.
     * Undocumented but symmetric; failures surface as [ApiException].
     */
    fun form(sessionID: String, formID: String): FormInfo = FormInfo.fromJson(
        dataObject(callBody(builder("session/$sessionID/form/$formID", false).get().build())),
    )

    /**
     * `POST /api/session/{sessionID}/form/{formID}/reply` body
     * `{answer:{key:value}}` (`Form.Reply`, required) → **204**.
     * Measured 204 live.
     */
    fun replyForm(sessionID: String, formID: String, answer: Map<String, Any?>) {
        val answers = JSONObject()
        for ((key, value) in answer) answers.put(key, value)
        postUnit("session/$sessionID/form/$formID/reply", false, JSONObject().put("answer", answers))
    }

    /** `Form.Info{id:frm_, sessionID, title, metadata?, fields[]}` (Wave 2 owns field rendering). */
    data class FormInfo(
        val id: String,
        val sessionID: String,
        val title: String,
        val fields: JSONArray,
        val raw: JSONObject,
    ) {
        companion object {
            fun fromJson(obj: JSONObject): FormInfo = FormInfo(
                id = obj.optString("id", ""),
                sessionID = obj.optString("sessionID", ""),
                title = obj.optString("title", ""),
                fields = obj.optJSONArray("fields") ?: JSONArray(),
                raw = obj,
            )
        }
    }

    // -------------------------------------------------- session support reads
    // A5 §2: envelope rules — `{data}` without location for session-scoped
    // reads; diff params are `from&to&context` (no messageID).

    /**
     * `GET /api/session/{sessionID}/diff?from&to&context` → `{data:[FileDiff…]}`.
     * Measured `{data:[]}` live. Param semantics changed from v1 (commit /
     * message range, not one messageID) — see [OpenCodeClient.FileDiff].
     */
    fun sessionDiff(
        sessionID: String,
        from: String? = null,
        to: String? = null,
        context: Int? = null,
    ): List<OpenCodeClient.FileDiff> {
        val arr = dataArray(
            callBody(
                builder("session/$sessionID/diff", false) {
                    if (!from.isNullOrBlank()) it.addQueryParameter("from", from)
                    if (!to.isNullOrBlank()) it.addQueryParameter("to", to)
                    if (context != null) it.addQueryParameter("context", context.toString())
                }.get().build(),
            ),
        )
        return OpenCodeClient.FileDiff.listFromJson(arr)
    }

    /**
     * `GET /api/session/{sessionID}/inbox` → `{data:[…]}` (no location).
     * Measured `{data:[]}` live. Items stay raw JSON: Wave 2 owns the inbox UI.
     */
    fun sessionInbox(sessionID: String): List<JSONObject> = v2ObjectList(
        dataArray(callBody(builder("session/$sessionID/inbox", false).get().build())),
    )

    /**
     * WAVE 2.4b: `PATCH /api/session/{sessionID}/inbox/{inboxID}` body
     * `{delivery}` → promote a queued follow-up to the running turn
     * (`session/composer/queue.ts` steer; the server answers `delivery.changed`).
     * Route shape from openapi.json (`SessionInbox` update); the `{delivery}`
     * body mirrors the prompt payload's field.
     */
    fun updateInboxDelivery(sessionID: String, inboxID: String, delivery: String) {
        val body = JSONObject().put("delivery", delivery)
        callUnit(builder("session/$sessionID/inbox/$inboxID", false).patch(body.toString().toRequestBody(jsonMedia)).build())
    }

    /**
     * WAVE 2.4b: `DELETE /api/session/{sessionID}/inbox/{inboxID}` → drop a
     * queued follow-up (`session/composer/queue.ts` remove; the server answers
     * `inbox.cancelled`). Route shape from openapi.json.
     */
    fun cancelInbox(sessionID: String, inboxID: String) {
        callUnit(builder("session/$sessionID/inbox/$inboxID", false).delete().build())
    }

    /**
     * `GET /api/model/default` → `{location,data:ModelInfo}` (location-scoped
     * catalog read). Measured live with a full model entry.
     */
    fun defaultModel(locationDirectory: String? = null): ModelInfo? {
        val data = parseObject(
            callBody(builder("model/default", true) { located(it, locationDirectory) }.get().build()),
        ).optJSONObject("data") ?: return null
        return ModelInfo.fromJson(data)
    }

    /**
     * `GET /api/project` → a BARE ARRAY `[{id,canonical,time,sandboxes}]`
     * (no envelope — do not use `dataObject`). Measured live.
     */
    fun projects(): List<V2Project> {
        val body = callBody(builder("project", false).get().build()).trim()
        if (!body.startsWith("[")) throw ApiException(JSON_BODY_CODE, "response is not a bare array")
        return v2ObjectList(JSONArray(body)).map(V2Project::fromJson)
    }

    /** `GET /api/vcs` → `{location,data:{branch:{current?,default?}}}`. Measured live. */
    fun vcs(locationDirectory: String? = null): VcsInfo = VcsInfo.fromJson(
        dataObject(callBody(builder("vcs", true) { located(it, locationDirectory) }.get().build())),
    )

    /** `GET /api/vcs/branch?search&limit` → `{location,data:[string]}`. Measured live. */
    fun vcsBranches(search: String? = null, limit: Int? = null, locationDirectory: String? = null): List<String> =
        v2StringList(
            dataArray(
                callBody(
                    builder("vcs/branch", true) {
                        located(it, locationDirectory)
                        if (!search.isNullOrBlank()) it.addQueryParameter("search", search)
                        if (limit != null && limit > 0) it.addQueryParameter("limit", limit.toString())
                    }.get().build(),
                ),
            ),
        )

    /**
     * `GET /api/vcs/diff?mode(required:working|branch|committed)&base?&context?`
     * → `{location,data:[FileDiff…]}`. Measured `{data:[]}` for mode=branch.
     */
    fun vcsDiff(
        mode: String,
        base: String? = null,
        context: Int? = null,
        locationDirectory: String? = null,
    ): List<OpenCodeClient.FileDiff> {
        val arr = dataArray(
            callBody(
                builder("vcs/diff", true) {
                    located(it, locationDirectory)
                    it.addQueryParameter("mode", mode)
                    if (!base.isNullOrBlank()) it.addQueryParameter("base", base)
                    if (context != null) it.addQueryParameter("context", context.toString())
                }.get().build(),
            ),
        )
        return OpenCodeClient.FileDiff.listFromJson(arr)
    }

    /** `GET /api/vcs/status` → `{location,data:[…]}` (raw; Wave 2 owns the rows). Measured live. */
    fun vcsStatus(locationDirectory: String? = null): List<JSONObject> = v2ObjectList(
        dataArray(callBody(builder("vcs/status", true) { located(it, locationDirectory) }.get().build())),
    )

    /**
     * `GET /api/config` → a BARE ARRAY `[{type,path,info}]` (not an object —
     * do not use `dataObject`). Config keys live inside `info`. Measured live.
     */
    fun configs(locationDirectory: String? = null): List<V2Config> {
        val body = callBody(builder("config", true) { located(it, locationDirectory) }.get().build()).trim()
        if (!body.startsWith("[")) throw ApiException(JSON_BODY_CODE, "response is not a bare array")
        return v2ObjectList(JSONArray(body)).map(V2Config::fromJson)
    }

    /** `GET /api/mcp` → `{location,data:[{name,status…}]}`. Measured live. */
    fun mcp(locationDirectory: String? = null): List<JSONObject> = v2ObjectList(
        dataArray(callBody(builder("mcp", true) { located(it, locationDirectory) }.get().build())),
    )

    /** `GET /api/shell` → `{location,data:[…]}` (raw; Wave 2 owns the rows). Measured `{data:[]}` live. */
    fun shells(locationDirectory: String? = null): List<JSONObject> = v2ObjectList(
        dataArray(callBody(builder("shell", true) { located(it, locationDirectory) }.get().build())),
    )

    /**
     * `GET /api/worktree` → `{location,data:[…]}` (raw; Wave 2 owns the rows).
     * Without `?projectID` the server answers 400 — pass one explicitly.
     */
    fun worktrees(projectID: String, locationDirectory: String? = null): List<JSONObject> = v2ObjectList(
        dataArray(
            callBody(
                builder("worktree", true) {
                    located(it, locationDirectory)
                    it.addQueryParameter("projectID", projectID)
                }.get().build(),
            ),
        ),
    )

    /** `GET /api/project` entry: `{id, canonical, time{created,updated}, sandboxes[]}` (bare array). */
    data class V2Project(
        val id: String,
        val canonical: String,
        val timeCreated: Long,
        val timeUpdated: Long,
        val sandboxes: List<String>,
        val raw: JSONObject,
    ) {
        companion object {
            fun fromJson(obj: JSONObject): V2Project {
                val time = obj.optJSONObject("time")
                return V2Project(
                    id = obj.optString("id", ""),
                    canonical = obj.optString("canonical", ""),
                    timeCreated = time?.optLong("created", 0L) ?: 0L,
                    timeUpdated = time?.optLong("updated", 0L) ?: 0L,
                    sandboxes = v2StringList(obj.optJSONArray("sandboxes")),
                    raw = obj,
                )
            }
        }
    }

    /** `GET /api/config` entry: `{type, path, info}` — keys live in `info`. */
    data class V2Config(
        val type: String,
        val path: String,
        val info: JSONObject,
        val raw: JSONObject,
    ) {
        companion object {
            fun fromJson(obj: JSONObject): V2Config = V2Config(
                type = obj.optString("type", ""),
                path = obj.optString("path", ""),
                info = obj.optJSONObject("info") ?: JSONObject(),
                raw = obj,
            )
        }
    }

    /** `GET /api/vcs` → `data:{branch:{current?,default?}}` (v1's flat branch strings are gone). */
    data class VcsInfo(
        val current: String?,
        val default: String?,
        val raw: JSONObject,
    ) {
        companion object {
            fun fromJson(obj: JSONObject): VcsInfo {
                val branch = obj.optJSONObject("branch")
                return VcsInfo(
                    current = branch?.optString("current", "")?.takeIf { it.isNotEmpty() },
                    default = branch?.optString("default", "")?.takeIf { it.isNotEmpty() },
                    raw = obj,
                )
            }
        }
    }

    /**
     * `GET /api/agent` → `{location,data:[AgentInfo…]}`.
     *
     * Measured on 2.0.11: `{id, name, request, description, mode, hidden,
     * permissions}` — `name` ("Build") EXISTS (the old "no name" note was the
     * 1.18.31 hybrid's). `mode` ∈ `"primary"` | `"subagent"` | `"all"`.
     */
    fun agents(locationDirectory: String? = null): List<AgentInfo> = v2ObjectList(
        dataArray(callBody(builder("agent", true) { located(it, locationDirectory) }.get().build())),
    ).map(AgentInfo::fromJson)

    /**
     * `GET /api/model` → `{location,data:[ModelInfo…]}` (2.0.11 keys include
     * `modelID`, `compatibility`, `package`, `settings`, `time` — see
     * [ModelInfo]). `variants` is an array of `{id,…}` objects.
     */
    fun models(locationDirectory: String? = null): List<ModelInfo> = v2ObjectList(
        dataArray(callBody(builder("model", true) { located(it, locationDirectory) }.get().build())),
    ).map(ModelInfo::fromJson)

    /**
     * `GET /api/provider` → `{location,data:[{id,integrationID?,name,
     * activation?,package?,settings?…}]}`. Measured live on 2.0.11 — the old
     * doc comment's `api{type,package,url}/request/disabled` shape was the
     * 1.18.31 hybrid's; 2.0.11 answers the flat integration-linked shape.
     * No `models` map (v1 `/provider` only); v2 splits provider + model.
     */
    fun providers(locationDirectory: String? = null): List<ProviderInfo> = v2ObjectList(
        dataArray(callBody(builder("provider", true) { located(it, locationDirectory) }.get().build())),
    ).map(ProviderInfo::fromJson)

    /**
     * `GET /api/provider/{providerID}` → `{location,data:ProviderInfo}`.
     * Measured 200 live; unknown ids answer
     * `404 {"_tag":"ProviderNotFoundError",…}`.
     */
    fun provider(providerID: String): ProviderInfo = ProviderInfo.fromJson(
        dataObject(callBody(builder("provider/$providerID", true).get().build())),
    )

    /**
     * `GET /api/command` → `{location,data:[{name,description}]}` (2.0.11 has
     * NO `template`/`source` — [CommandInfo.template] stays `""`).
     */
    fun commands(locationDirectory: String? = null): List<CommandInfo> = v2ObjectList(
        dataArray(callBody(builder("command", true) { located(it, locationDirectory) }.get().build())),
    ).mapNotNull(CommandInfo::fromJson)

    /**
     * `GET /api/skill` → `{location,data:[{id,name,description,path,content}]}`
     * (2.0.11: v1's `location` is `path`; [SkillInfo.location] reads both).
     */
    fun skills(locationDirectory: String? = null): List<SkillInfo> = v2ObjectList(
        dataArray(callBody(builder("skill", true) { located(it, locationDirectory) }.get().build())),
    ).mapNotNull(SkillInfo::fromJson)

    /**
     * `GET /api/integration` → `{"location":…,"data":[IntegrationInfo…]}`.
     * Measured: 222 entries shaped
     * `{id, name, methods:[{type:"key"|"env",names?}], connections:[]}`.
     * `GET /api/integration/{id}` is `UNVERIFIED` (not issued).
     */
    fun integrations(locationDirectory: String? = null): List<IntegrationInfo> = v2ObjectList(
        dataArray(callBody(builder("integration", true) { located(it, locationDirectory) }.get().build())),
    ).map(IntegrationInfo::fromJson)

    /**
     * `GET /api/reference` → `{"location":…,"data":[…]}`.
     *
     * Measured empty (`[]`) at both the server default cwd and the repo
     * directory, so only the envelope is verified and the element shape is
     * `UNVERIFIED`. Returned raw.
     */
    fun references(locationDirectory: String? = null): List<JSONObject> = v2ObjectList(
        dataArray(callBody(builder("reference", true) { located(it, locationDirectory) }.get().build())),
    )

    /**
     * `GET /api/fs/list?path=` → `{"location":…,"data":[FsEntry…]}`.
     *
     * Measured: entries are `{"path":"app\\src\\","type":"directory"}` /
     * `{"path":"gradlew","type":"file"}`. Two details that differ from v1
     * `/file`: the paths are **relative to the location directory** (with a
     * trailing `\` on Windows directories) rather than absolute, and they stay
     * rooted at the location even when `path=` descends (`?path=app` →
     * `"app\\build\\"`, not `"build\\"`). Join with the location directory for a
     * usable absolute path.
     */
    fun fsList(path: String? = null, locationDirectory: String? = null): List<FsEntry> = v2ObjectList(
        dataArray(
            callBody(
                builder("fs/list", true) {
                    located(it, locationDirectory)
                    if (!path.isNullOrBlank()) it.addQueryParameter("path", path)
                }.get().build(),
            ),
        ),
    ).map(FsEntry::fromJson)

    /**
     * `GET /api/fs/read/<rel>` → the **raw file bytes**; JSON is never involved.
     * Measured: `application/x-msdownload` for `gradlew.bat`,
     * `application/octet-stream` for `app/build.gradle.kts`.
     *
     * [path] must be relative to the location directory. A leading slash is
     * stripped, and `\` is normalised to `/` before the segment split, because
     * `addPathSegment` would percent-encode a backslash. Measured:
     * `/api/fs/read//gradlew.bat` answered `500 {"name":"UnknownError",…}` while
     * `/api/fs/read/gradlew.bat` answered 200.
     */
    fun fsRead(path: String, locationDirectory: String? = null): ByteArray {
        val relative = path.replace('\\', '/').trimStart('/')
        return callBytes(
            builder("fs/read/$relative", true) { located(it, locationDirectory) }.get().build(),
        )
    }

    /**
     * `GET /api/fs/find?query=&type=&limit=` → `{"location":…,"data":[FsEntry…]}`.
     *
     * Measured: `query` is **required** — without it the server answers
     * `400 {"_tag":"InvalidRequestError","message":"Missing key\n at [\"query\"]",
     * "kind":"Query"}`. `limit` is a *string* query param, and the results are
     * relative paths like `fs/list`. The matcher is fuzzy/ranked: `?query=gradlew`
     * returned paths that do not contain the query at all, so callers must not
     * assume a literal match. [type] (`"file"`/`"directory"`) is `UNVERIFIED`.
     */
    fun fsFind(
        query: String,
        type: String? = null,
        limit: Int? = null,
        locationDirectory: String? = null,
    ): List<FsEntry> = v2ObjectList(
        dataArray(
            callBody(
                builder("fs/find", true) {
                    located(it, locationDirectory)
                    it.addQueryParameter("query", query)
                    if (!type.isNullOrBlank()) it.addQueryParameter("type", type)
                    if (limit != null && limit > 0) it.addQueryParameter("limit", limit.toString())
                }.get().build(),
            ),
        ),
    ).map(FsEntry::fromJson)

    /**
     * `GET /api/pty` → `{"location":…,"data":[PtyInfo…]}`.
     *
     * Measured: `{"location":{…},"data":[]}` on the probe server until one was
     * created; the entry shape is `{id, title, command, args[], cwd, status,
     * pid}` with `status` ∈ `"running"` | `"exited"` and `exitCode` appearing
     * only once exited.
     */
    fun ptyList(locationDirectory: String? = null): List<PtyInfo> = v2ObjectList(
        dataArray(callBody(builder("pty", true) { located(it, locationDirectory) }.get().build())),
    ).map(PtyInfo::fromJson)

    /**
     * `POST /api/pty` body `{command?, args?, cwd?, title?, env?}` →
     * `{"location":…,"data":PtyInfo}`.
     *
     * Measured live: `{"command":"cmd.exe","args":[],"title":"v2probe"}` →
     * `{"location":{…},"data":{"id":"pty_…","title":"v2probe","command":
     * "cmd.exe","args":[],"cwd":"C:\\WINDOWS\\system32","status":"running",
     * "pid":49008}}` — `cwd` defaults to the location directory.
     */
    fun ptyCreate(
        command: String? = null,
        args: List<String> = emptyList(),
        cwd: String? = null,
        title: String? = null,
        env: Map<String, String> = emptyMap(),
        locationDirectory: String? = null,
    ): PtyInfo {
        val body = JSONObject()
        if (!command.isNullOrBlank()) body.put("command", command)
        if (args.isNotEmpty()) {
            val array = JSONArray()
            for (arg in args) array.put(arg)
            body.put("args", array)
        }
        if (!cwd.isNullOrBlank()) body.put("cwd", cwd)
        if (!title.isNullOrBlank()) body.put("title", title)
        if (env.isNotEmpty()) {
            val entries = JSONObject()
            for ((key, value) in env) entries.put(key, value)
            body.put("env", entries)
        }
        return PtyInfo.fromJson(
            dataObject(
                callBody(
                    builder("pty", true) { located(it, locationDirectory) }
                        .post(body.toString().toRequestBody(jsonMedia))
                        .build(),
                ),
            ),
        )
    }

    /**
     * `GET /api/pty/{ptyID}` → `{"location":…,"data":PtyInfo}`. Measured on a
     * running PTY and, after `DELETE`, as `404 {"_tag":"PtyNotFoundError",…}`.
     */
    fun ptyGet(ptyID: String, locationDirectory: String? = null): PtyInfo = PtyInfo.fromJson(
        dataObject(
            callBody(builder("pty/$ptyID", true) { located(it, locationDirectory) }.get().build()),
        ),
    )

    /**
     * `PUT /api/pty/{ptyID}` body `{title?, size?:{rows,cols}}` (at least one) →
     * `{"location":…,"data":PtyInfo}`. Measured live: the new title came back in
     * the response.
     */
    fun ptyUpdate(
        ptyID: String,
        title: String? = null,
        cols: Int? = null,
        rows: Int? = null,
        locationDirectory: String? = null,
    ): PtyInfo {
        val body = JSONObject()
        if (!title.isNullOrBlank()) body.put("title", title)
        if (cols != null && rows != null) {
            body.put("size", JSONObject().put("rows", rows).put("cols", cols))
        }
        return PtyInfo.fromJson(
            dataObject(
                callBody(
                    builder("pty/$ptyID", true) { located(it, locationDirectory) }
                        .put(body.toString().toRequestBody(jsonMedia))
                        .build(),
                ),
            ),
        )
    }

    /**
     * `DELETE /api/pty/{ptyID}` → **204**, empty body. Measured live; a later
     * `GET` answers `404 {"_tag":"PtyNotFoundError",…}`.
     */
    fun ptyRemove(ptyID: String, locationDirectory: String? = null) {
        callUnit(builder("pty/$ptyID", true) { located(it, locationDirectory) }.delete().build())
    }

    /**
     * `POST /api/pty/{ptyID}/connect-token` → `{"location":…,"data":{"ticket":…,
     * "expires_in":60}}`.
     *
     * Measured live: **200** with the mandatory `x-opencode-ticket: 1` header.
     * Without it the same POST answered
     * `403 {"_tag":"ForbiddenError","message":"Invalid PTY connect token request"}`,
     * and `x-opencode-ticket: true` was also rejected — the value must be `1`.
     */
    fun ptyConnectTicket(ptyID: String, locationDirectory: String? = null): PtyTicket = PtyTicket.fromJson(
        dataObject(
            callBody(
                builder("pty/$ptyID/connect-token", true) { located(it, locationDirectory) }
                    .header(TICKET_HEADER, "1")
                    .post(EMPTY_BODY)
                    .build(),
            ),
        ),
    )

    /**
     * `GET /api/pty/{ptyID}/connect` as a **WebSocket upgrade request** (OkHttp's
     * `newWebSocket` performs the upgrade from the `http(s)` URL).
     *
     * Measured live: a plain `ws://127.0.0.1:4096/api/pty/<id>/connect` upgrade
     * succeeded **without** a ticket on this no-auth server, and the first text
     * frame carried raw terminal output (`ESC[2J ESC[H Microsoft Windows …`).
     * [cursor] omitted ⇒ full replay; `-1` ⇒ new output only. Handing the
     * one-shot ticket to the socket instead of Basic auth is `UNVERIFIED`.
     */
    fun ptySocketRequest(
        ptyID: String,
        ticket: String? = null,
        cursor: Long? = null,
        locationDirectory: String? = null,
    ): Request {
        val urlBuilder = baseHttpUrl.newBuilder()
            .addPathSegment("api").addPathSegment("pty").addPathSegment(ptyID).addPathSegment("connect")
        val dir = locationDirectory ?: directory
        if (!dir.isNullOrBlank()) urlBuilder.addQueryParameter(LOCATION_DIRECTORY_QUERY, dir)
        if (!ticket.isNullOrBlank()) urlBuilder.addQueryParameter("ticket", ticket)
        if (cursor != null) urlBuilder.addQueryParameter("cursor", cursor.toString())
        val request = Request.Builder().url(urlBuilder.build())
        basicAuthorizationHeader(username, password)?.let { request.header("Authorization", it) }
        return request.build()
    }

    // ------------------------------------------------------------------ SSE

    /**
     * Per-session durable log (A5 §0.3/§4):
     * `GET /api/experimental/session/{id}/log?after=&follow=` —
     * `text/event-stream`.
     *
     * The old `sessionEventRequest` (`GET /api/session/{id}/event`) targeted a
     * route that does NOT exist (404, absent from openapi.json) and is
     * REMOVED. Replacement behavior, measured live 2026-09-21:
     * - opens with `data: {"type":"log.synced","aggregateID","seq":N}` — "≤ N
     *   is all here" (`?after=0` on a fresh session answered exactly that);
     * - `follow=true` keeps the stream open for live durable frames;
     * - frame envelope is `{id, type, created, data, durable?, location?,
     *   metadata?}` — **no `event:`/`id:` lines** (same as the global
     *   stream); ephemeral frames (delta/progress/status) carry no `durable`.
     *
     * `?after=` takes a value from a trusted source (`log.synced` or a seen
     * durable seq) — see [V2SeqTracker].
     */
    fun sessionLogRequest(sessionID: String, after: Long? = null, follow: Boolean = true): Request =
        builder("experimental/session/$sessionID/log", false) {
            if (after != null) it.addQueryParameter("after", after.toString())
            it.addQueryParameter("follow", follow.toString())
        }.get().header("Accept", "text/event-stream").build()

    /**
     * `GET /api/event` — the server-wide stream (`text/event-stream`).
     *
     * Measured live 2026-09-21 through a real turn: opens with
     * `{"type":"server.connected","data":{}}`, then per-session durable
     * frames (`session.inbox.enqueued{inboxID,item{type,payload,delivery}}`,
     * `session.execution.started`, `session.instructions.updated`,
     * `session.inbox.delivered`, `session.step.started{assistantMessageID,
     * agent,model,started}`, `session.text.started/delta/ended{ordinal,…}`,
     * `session.step.streamed`, `session.step.ended{finish,cost,tokens}`,
     * `session.usage.updated{cost,tokens}`, `session.execution.succeeded`,
     * `session.deleted`), each with `location{directory}` and
     * `durable{aggregateID,seq,version}` (ephemeral delta/usage frames carry
     * no `durable`). `: heartbeat` keepalives between frames.
     */
    fun globalEventRequest(): Request = builder("event", false).get()
        .header("Accept", "text/event-stream")
        .build()

    // -------------------------------------------------------------- parsing

    private fun <T> parsePage(body: String, map: (JSONObject) -> T?): Page<T> {
        val root = parseObject(body)
        return Page(
            data = v2ObjectList(root.optJSONArray("data") ?: JSONArray()).mapNotNull(map),
            cursor = Cursor.fromJson(root.optJSONObject("cursor")),
        )
    }

    private fun locationRef(override: String?, workspace: String?): JSONObject? {
        val dir = if (!override.isNullOrBlank()) override else directory
        if (dir.isNullOrBlank() && workspace.isNullOrBlank()) return null
        val location = JSONObject()
        if (!dir.isNullOrBlank()) location.put("directory", dir)
        if (!workspace.isNullOrBlank()) location.put("workspace", workspace)
        return location
    }

    // ------------------------------------------------------------------ dto

    /** `GET /api/health` → `{"healthy":true}`. */
    data class Health(val healthy: Boolean) {
        companion object {
            fun fromJson(obj: JSONObject): Health = Health(obj.optBoolean("healthy", false))
        }
    }

    /** `LocationInfo` — `{directory, workspaceID?, project:{id, directory}}`. */
    data class LocationInfo(
        val directory: String,
        val workspaceID: String?,
        val projectID: String?,
        val projectDirectory: String?,
    ) {
        companion object {
            fun fromJson(obj: JSONObject): LocationInfo {
                val project = obj.optJSONObject("project")
                return LocationInfo(
                    directory = obj.optString("directory", ""),
                    workspaceID = obj.optString("workspaceID", "").takeIf { it.isNotEmpty() },
                    projectID = project?.optString("id", "")?.takeIf { it.isNotEmpty() },
                    projectDirectory = project?.optString("directory", "")?.takeIf { it.isNotEmpty() },
                )
            }
        }
    }

    /** `ModelRef` — `{id, providerID, variant?}`. */
    data class ModelRef(val id: String, val providerID: String, val variant: String? = null) {
        fun toJson(): JSONObject {
            val json = JSONObject().put("id", id).put("providerID", providerID)
            if (!variant.isNullOrBlank()) json.put("variant", variant)
            return json
        }

        companion object {
            fun fromJson(obj: JSONObject): ModelRef = ModelRef(
                id = obj.optString("id", ""),
                providerID = obj.optString("providerID", ""),
                variant = obj.optString("variant", "").takeIf { it.isNotEmpty() },
            )
        }
    }

    /** `Page.cursor` — `{previous, next}`, both nullable and opaque. */
    data class Cursor(val previous: String?, val next: String?) {
        companion object {
            fun fromJson(obj: JSONObject?): Cursor? = obj?.let {
                Cursor(
                    previous = it.optString("previous", "").takeIf { value -> value.isNotEmpty() },
                    next = it.optString("next", "").takeIf { value -> value.isNotEmpty() },
                )
            }
        }
    }

    /** Any `{"data":[…]}` envelope, plus the optional cursor. */
    data class Page<T>(val data: List<T>, val cursor: Cursor?) {
        val nextCursor: String? get() = cursor?.next
        val previousCursor: String? get() = cursor?.previous
    }

    /**
     * `SessionV2Info`. Measured keys: `id, projectID, agent, model, cost,
     * tokens{input,output,reasoning,cache{read,write}}, time{created,updated,
     * archived?}, title, location{directory}, subpath?, parentID?, revert?`.
     * `location.directory` is the session's workspace — v1 called it `directory`
     * at the top level and also carried `slug`/`version`, neither of which exists
     * here.
     */
    data class SessionInfo(
        val id: String,
        val projectID: String,
        val parentID: String?,
        val agent: String?,
        val model: ModelRef?,
        val cost: Double,
        val tokensInput: Long,
        val tokensOutput: Long,
        val tokensReasoning: Long,
        val timeCreated: Long,
        val timeUpdated: Long,
        val timeArchived: Long?,
        val title: String,
        val directory: String?,
        val subpath: String?,
        val raw: JSONObject,
    ) {
        companion object {
            fun fromJson(obj: JSONObject): SessionInfo {
                val time = obj.optJSONObject("time")
                val tokens = obj.optJSONObject("tokens")
                val location = obj.optJSONObject("location")
                return SessionInfo(
                    id = obj.optString("id", ""),
                    projectID = obj.optString("projectID", ""),
                    parentID = obj.optString("parentID", "").takeIf { it.isNotEmpty() },
                    agent = obj.optString("agent", "").takeIf { it.isNotEmpty() },
                    model = obj.optJSONObject("model")?.let(ModelRef::fromJson),
                    cost = obj.optDouble("cost", 0.0),
                    tokensInput = tokens?.optLong("input", 0L) ?: 0L,
                    tokensOutput = tokens?.optLong("output", 0L) ?: 0L,
                    tokensReasoning = tokens?.optLong("reasoning", 0L) ?: 0L,
                    timeCreated = time?.optLong("created", 0L) ?: 0L,
                    timeUpdated = time?.optLong("updated", 0L) ?: 0L,
                    timeArchived = time?.optLong("archived", 0L)?.takeIf { it > 0L },
                    title = obj.optString("title", "").ifEmpty { UNTITLED },
                    directory = location?.optString("directory", "")?.takeIf { it.isNotEmpty() },
                    subpath = obj.optString("subpath", "").takeIf { it.isNotEmpty() },
                    raw = obj,
                )
            }

            /** Fallback title for a session with no `title` at all. */
            const val UNTITLED = "Untitled"
        }
    }

    /**
     * Kept for source compatibility: the paged `GET /api/session/{id}/history`
     * route does not exist (see above). New code replays via [sessionLogRequest].
     */
    data class History(val data: List<DurableEvent>, val hasMore: Boolean)

    /**
     * One element of `GET /api/session/{id}/message`.
     *
     * A **flat tagged union**, so [type] decides which fields are populated:
     * `"user"` → [text]; `"assistant"` → [agent], [model], [content];
     * `"shell"` → [command], [output]; `"agent-switched"`, `"model-switched"`,
     * `"synthetic"`, `"system"`, `"compaction"` → [raw] (the compaction summary
     * key is `UNVERIFIED`, so [compactionSummary] reads `summary` leniently).
     */
    data class Message(
        val id: String,
        val type: String,
        val timeCreated: Long,
        val timeCompleted: Long?,
        val text: String?,
        val agent: String?,
        val model: ModelRef?,
        val content: List<Part>,
        val command: String?,
        val output: String?,
        val compactionSummary: String?,
        val cost: Double?,
        val tokensInput: Long,
        val tokensOutput: Long,
        val raw: JSONObject,
    ) {
        /** `input + output` — the flattened usage the composer ring consumes. */
        val tokensTotal: Long get() = tokensInput + tokensOutput

        /** Concatenated text parts (assistant) or [text] (user). */
        fun textOf(): String = when (type) {
            TYPE_USER -> text.orEmpty()
            else -> content.filter { it.type == "text" }.joinToString("") { it.text }
        }

        companion object {
            const val TYPE_USER = "user"
            const val TYPE_ASSISTANT = "assistant"
            const val TYPE_SHELL = "shell"
            const val TYPE_COMPACTION = "compaction"

            /** Lenient: an unknown `type` yields an entry whose [raw] is intact. */
            fun fromJson(obj: JSONObject): Message {
                val time = obj.optJSONObject("time")
                val tokens = obj.optJSONObject("tokens")
                val content = v2ObjectList(obj.optJSONArray("content") ?: JSONArray()).map(Part::fromJson)
                return Message(
                    id = obj.optString("id", ""),
                    type = obj.optString("type", "unknown"),
                    timeCreated = time?.optLong("created", 0L) ?: 0L,
                    timeCompleted = time?.optLong("completed", 0L)?.takeIf { it > 0L },
                    text = if (obj.has("text")) obj.optString("text", "") else null,
                    agent = obj.optString("agent", "").takeIf { it.isNotEmpty() },
                    model = obj.optJSONObject("model")?.let(ModelRef::fromJson),
                    content = content,
                    command = obj.optString("command", "").takeIf { it.isNotEmpty() },
                    output = obj.optString("output", "").takeIf { it.isNotEmpty() },
                    compactionSummary = obj.optString("summary", "").takeIf { it.isNotEmpty() },
                    cost = if (obj.has("cost")) obj.optDouble("cost") else null,
                    tokensInput = tokens?.optLong("input", 0L) ?: 0L,
                    tokensOutput = tokens?.optLong("output", 0L) ?: 0L,
                    raw = obj,
                )
            }
        }
    }

    /**
     * One assistant content block: measured `{"type":"text","id":"msg_…",
     * "text":"Hi!"}`. The document also defines `reasoning` and `tool` variants,
     * which were not observed live (`UNVERIFIED`) — this keeps the common fields
     * plus [raw] so nothing is lost.
     */
    data class Part(val id: String, val type: String, val text: String, val raw: JSONObject) {
        companion object {
            fun fromJson(obj: JSONObject): Part = Part(
                id = obj.optString("id", ""),
                type = obj.optString("type", "unknown"),
                text = obj.optString("text", ""),
                raw = obj,
            )
        }
    }

    /** `{"data":Session.Inbox.User}` from `POST /api/session/{id}/prompt`.
     *
     * Live shape (NOT the old hybrid's `admittedSeq` contract):
     * `{data:{id:msg_, sessionID, time:{created}, type:"user",
     * payload:{text}, delivery}}`. [admittedSeq] stays for back-compat but is
     * 0/absent on 2.0.11 — feed the seq tracker from durable frames instead.
     */
    data class SessionAdmitted(
        val admittedSeq: Long,
        val id: String,
        val sessionID: String,
        val text: String,
        val delivery: String?,
        val timeCreated: Long,
        val promotedSeq: Long?,
        val raw: JSONObject,
    ) {
        companion object {
            fun fromJson(obj: JSONObject): SessionAdmitted {
                val payload = obj.optJSONObject("payload")
                val time = obj.optJSONObject("time")
                return SessionAdmitted(
                    admittedSeq = obj.optLong("admittedSeq", 0L),
                    id = obj.optString("id", ""),
                    sessionID = obj.optString("sessionID", ""),
                    text = payload?.optString("text", "").orEmpty(),
                    delivery = obj.optString("delivery", "").takeIf { it.isNotEmpty() },
                    timeCreated = time?.optLong("created", 0L) ?: obj.optLong("timeCreated", 0L),
                    promotedSeq = if (obj.has("promotedSeq")) obj.optLong("promotedSeq") else null,
                    raw = obj,
                )
            }
        }
    }

    /**
     * `Agent` (2.0.11 keys: `{id, name, request, description, mode, hidden,
     * permissions}`). The old "no `name`" comment was the 1.18.31 hybrid's —
     * 2.0.11 answers `name` ("Build"). `selectable` keeps the composer rule.
     */
    data class AgentInfo(
        val id: String,
        val name: String,
        val description: String,
        val mode: String,
        val hidden: Boolean,
        val system: String?,
        val raw: JSONObject,
    ) {
        /** `mode == "primary"` — measured for `build` and `plan`. */
        val primary: Boolean get() = mode == MODE_PRIMARY

        /** The composer's rule: usable as a session agent. */
        val selectable: Boolean get() = id.isNotEmpty() && !hidden && (mode == MODE_PRIMARY || mode == MODE_ALL)

        companion object {
            const val MODE_PRIMARY = "primary"
            const val MODE_SUBAGENT = "subagent"
            const val MODE_ALL = "all"

            fun fromJson(obj: JSONObject): AgentInfo = AgentInfo(
                id = obj.optString("id", ""),
                name = obj.optString("name", "").takeIf { it.isNotEmpty() } ?: obj.optString("id", ""),
                description = obj.optString("description", ""),
                mode = obj.optString("mode", ""),
                hidden = obj.optBoolean("hidden", false),
                system = obj.optString("system", "").takeIf { it.isNotEmpty() },
                raw = obj,
            )

            /** The agents a session can run, in catalog order. */
            fun selectable(agents: List<AgentInfo>): List<AgentInfo> = agents.filter { it.selectable }
        }
    }

    /**
     * `Model.Info` (2.0.11 keys: `capabilities, compatibility, cost, enabled,
     * family, id, limit, modelID, name, package, providerID, settings, status,
     * time, variants`). [variants] is the **id list** of the wire's
     * `variants:[{id,settings}]` array (v1 exposed those names as `Record`
     * keys); `modelID` echoes `id`. [costInput]/[costOutput] come from
     * `cost[0]`; `input`/`output` come from `capabilities.input/output`
     * (modalities, not token limits — those are [limitContext]/[limitOutput]).
     */
    data class ModelInfo(
        val id: String,
        val modelID: String,
        val providerID: String,
        val name: String,
        val family: String?,
        val status: String?,
        val enabled: Boolean,
        val tools: Boolean?,
        val input: List<String>,
        val output: List<String>,
        val limitContext: Int?,
        val limitOutput: Int?,
        val variants: List<String>,
        val costInput: Double?,
        val costOutput: Double?,
        val raw: JSONObject,
    ) {
        companion object {
            fun fromJson(obj: JSONObject): ModelInfo {
                val id = obj.optString("id", "")
                val capabilities = obj.optJSONObject("capabilities")
                val limit = obj.optJSONObject("limit")
                val cost = obj.optJSONArray("cost")?.optJSONObject(0)
                return ModelInfo(
                    id = id,
                    modelID = obj.optString("modelID", "").takeIf { it.isNotEmpty() } ?: id,
                    providerID = obj.optString("providerID", ""),
                    name = obj.optString("name", "").ifEmpty { id },
                    family = obj.optString("family", "").takeIf { it.isNotEmpty() },
                    status = obj.optString("status", "").takeIf { it.isNotEmpty() },
                    enabled = obj.optBoolean("enabled", false),
                    tools = capabilities?.let { if (it.has("tools")) it.optBoolean("tools") else null },
                    input = v2StringList(capabilities?.optJSONArray("input")),
                    output = v2StringList(capabilities?.optJSONArray("output")),
                    limitContext = limit?.optInt("context", 0)?.takeIf { it > 0 },
                    limitOutput = limit?.optInt("output", 0)?.takeIf { it > 0 },
                    variants = variantIds(obj.optJSONArray("variants")),
                    costInput = cost?.let { if (it.has("input")) it.optDouble("input") else null },
                    costOutput = cost?.let { if (it.has("output")) it.optDouble("output") else null },
                    raw = obj,
                )
            }

            /**
             * The wire's `variants` is an array of objects
             * (`{"id":"high","headers":{},"body":{}}`); a bare string array is
             * tolerated for forward compatibility.
             */
            private fun variantIds(array: JSONArray?): List<String> {
                if (array == null) return emptyList()
                return (0 until array.length()).mapNotNull { i ->
                    when (val element = array.opt(i)) {
                        is JSONObject -> element.optString("id", "").takeIf { it.isNotEmpty() }
                        is String -> element.takeIf { it.isNotEmpty() }
                        else -> null
                    }
                }
            }
        }
    }

    /**
     * `Provider` (2.0.11 keys: `{id, integrationID?, name, activation?,
     * package?, settings?…}`). The old `api{type,package,url}/request/disabled`
     * comment was the 1.18.31 hybrid shape; [activation] (`enabled`/`auto`)
     * replaces `disabled`, and the transport package lives flat in [pkg].
     * No `models` map (v1 `/provider` only).
     */
    data class ProviderInfo(
        val id: String,
        val name: String,
        val activation: String?,
        val integrationID: String?,
        val pkg: String?,
        val apiType: String?,
        val apiPackage: String?,
        val apiUrl: String?,
        val raw: JSONObject,
    ) {
        /** `activation != "disabled"` (absent activation ⇒ usable). */
        val disabled: Boolean get() = activation == "disabled"

        companion object {
            fun fromJson(obj: JSONObject): ProviderInfo {
                val api = obj.optJSONObject("api")
                return ProviderInfo(
                    id = obj.optString("id", ""),
                    name = obj.optString("name", "").ifEmpty { obj.optString("id", "") },
                    activation = obj.optString("activation", "").takeIf { it.isNotEmpty() },
                    integrationID = obj.optString("integrationID", "").takeIf { it.isNotEmpty() },
                    pkg = obj.optString("package", "").takeIf { it.isNotEmpty() },
                    apiType = api?.optString("type", "")?.takeIf { it.isNotEmpty() },
                    apiPackage = api?.optString("package", "")?.takeIf { it.isNotEmpty() },
                    apiUrl = api?.optString("url", "")?.takeIf { it.isNotEmpty() },
                    raw = obj,
                )
            }
        }
    }

    /** `Pty` — `{id, title, command, args[], cwd, status, pid, exitCode?}`. */
    data class PtyInfo(
        val id: String,
        val title: String,
        val command: String,
        val args: List<String>,
        val cwd: String,
        val status: String,
        val pid: Int,
        val exitCode: Int?,
        val raw: JSONObject,
    ) {
        val running: Boolean get() = status == STATUS_RUNNING

        companion object {
            const val STATUS_RUNNING = "running"
            const val STATUS_EXITED = "exited"

            fun fromJson(obj: JSONObject): PtyInfo = PtyInfo(
                id = obj.optString("id", ""),
                title = obj.optString("title", ""),
                command = obj.optString("command", ""),
                args = v2StringList(obj.optJSONArray("args")),
                cwd = obj.optString("cwd", ""),
                status = obj.optString("status", ""),
                pid = obj.optInt("pid", 0),
                exitCode = if (obj.has("exitCode")) obj.optInt("exitCode") else null,
                raw = obj,
            )
        }
    }

    /** `{"data":{"ticket","expires_in"}}` from `POST /api/pty/{id}/connect-token`. */
    data class PtyTicket(val ticket: String, val expiresIn: Int) {
        companion object {
            fun fromJson(obj: JSONObject): PtyTicket = PtyTicket(
                ticket = obj.optString("ticket", ""),
                expiresIn = obj.optInt("expires_in", 0),
            )
        }
    }

    /**
     * `FileSystemEntry` — `{"path","type"}`, `type` ∈ `"file"` | `"directory"`.
     * [path] is relative to the request's location directory and keeps a
     * trailing separator on directories (`app\src\`); [name] is its last segment.
     */
    data class FsEntry(val path: String, val type: String) {
        val directory: Boolean get() = type == TYPE_DIRECTORY

        /** Last path segment, without a trailing Windows separator. */
        val name: String
            get() = path.trimEnd('\\', '/').split('\\', '/').lastOrNull().orEmpty()

        companion object {
            const val TYPE_DIRECTORY = "directory"
            const val TYPE_FILE = "file"

            fun fromJson(obj: JSONObject): FsEntry = FsEntry(
                path = obj.optString("path", ""),
                type = obj.optString("type", ""),
            )
        }
    }

    /** `GET /api/command` entry — `{name, template, description}` (v1 also had `source`). */
    /**
     * `GET /api/command` entry (2.0.11: `{name, description}` — measured live;
     * `template`/`source` are GONE, so [template] is always `""` and
     * client-side template expansion is v1-only).
     */
    data class CommandInfo(val name: String, val template: String, val description: String) {
        companion object {
            fun fromJson(obj: JSONObject): CommandInfo? {
                val name = obj.optString("name", "").trim()
                if (name.isEmpty()) return null
                return CommandInfo(
                    name = name,
                    template = obj.optString("template", ""),
                    description = obj.optString("description", ""),
                )
            }
        }
    }

    /** `GET /api/skill` entry (2.0.11: `{id, name, description, path, content}` — v1's `location` is `path`). */
    data class SkillInfo(
        val id: String,
        val name: String,
        val description: String,
        val location: String?,
        val content: String?,
    ) {
        companion object {
            fun fromJson(obj: JSONObject): SkillInfo? {
                val name = obj.optString("name", "").trim()
                if (name.isEmpty()) return null
                return SkillInfo(
                    id = obj.optString("id", ""),
                    name = name,
                    description = obj.optString("description", ""),
                    location = (obj.optString("path", "").takeIf { it.isNotEmpty() }
                        ?: obj.optString("location", "").takeIf { it.isNotEmpty() }),
                    content = obj.optString("content", "").takeIf { it.isNotEmpty() },
                )
            }
        }
    }

    /** `GET /api/integration` entry — `{id, name, methods[], connections[]}`. */
    data class IntegrationInfo(
        val id: String,
        val name: String,
        val methodTypes: List<String>,
        val connections: Int,
        val raw: JSONObject,
    ) {
        companion object {
            fun fromJson(obj: JSONObject): IntegrationInfo {
                val methods = obj.optJSONArray("methods")
                val types = ArrayList<String>(methods?.length() ?: 0)
                if (methods != null) {
                    for (i in 0 until methods.length()) {
                        methods.optJSONObject(i)?.optString("type", "")
                            ?.takeIf { it.isNotEmpty() }
                            ?.let(types::add)
                    }
                }
                return IntegrationInfo(
                    id = obj.optString("id", ""),
                    name = obj.optString("name", ""),
                    methodTypes = types,
                    connections = obj.optJSONArray("connections")?.length() ?: 0,
                    raw = obj,
                )
            }
        }
    }

    /**
     * `PermissionV2Request` — `{id, sessionID, action, resources[], save[],
     * metadata?, source?}`. The v1 names differ: `permission` → `action`,
     * `patterns` → `resources`, `always` → `save`, and the tool source moved
     * under `source:{type:"tool",messageID,callID}`. Field *names* are from the
     * OpenAPI document only — the live server had no pending request, so every
     * instance is `UNVERIFIED` (the empty-list envelope was measured).
     */
    data class PermissionRequest(
        val id: String,
        val sessionID: String,
        val action: String,
        val resources: List<String>,
        val save: List<String>,
        val messageID: String?,
        val callID: String?,
        val raw: JSONObject,
    ) {
        companion object {
            fun fromJson(obj: JSONObject): PermissionRequest {
                val source = obj.optJSONObject("source")
                // v2 source key is `id` (schema `Permission.Source`); accept
                // v1's `callID` too for hybrid-era payloads.
                val callID = source?.optString("id", "")?.takeIf { it.isNotEmpty() }
                    ?: source?.optString("callID", "")?.takeIf { it.isNotEmpty() }
                return PermissionRequest(
                    id = obj.optString("id", ""),
                    sessionID = obj.optString("sessionID", ""),
                    action = obj.optString("action", ""),
                    resources = v2StringList(obj.optJSONArray("resources")),
                    save = v2StringList(obj.optJSONArray("save")),
                    messageID = source?.optString("messageID", "")?.takeIf { it.isNotEmpty() },
                    callID = callID,
                    raw = obj,
                )
            }
        }
    }

    /**
     * Kept for source compatibility: v2 has no question endpoints
     * (`/api/question*` 404s, absent from openapi.json) — v1 question behavior
     * lives in `OpenCodeClient.QuestionRequest` + `V1Transport` and is
     * untouched. New v2 code uses [FormInfo] + `form.*` events instead.
     */
    data class QuestionRequest(
        val id: String,
        val sessionID: String,
        val questions: List<Question>,
        val messageID: String?,
        val callID: String?,
        val raw: JSONObject,
    ) {
        data class Option(val label: String, val description: String) {
            companion object {
                fun fromJson(obj: JSONObject): Option = Option(
                    label = obj.optString("label", ""),
                    description = obj.optString("description", ""),
                )
            }
        }

        data class Question(
            val question: String,
            val header: String,
            val options: List<Option>,
            val multiple: Boolean,
            val custom: Boolean,
            val raw: JSONObject,
        ) {
            companion object {
                fun fromJson(obj: JSONObject): Question = Question(
                    question = obj.optString("question", ""),
                    header = obj.optString("header", ""),
                    options = (0 until (obj.optJSONArray("options")?.length() ?: 0)).mapNotNull { i ->
                        obj.optJSONArray("options")?.optJSONObject(i)?.let(Option::fromJson)
                    },
                    multiple = obj.optBoolean("multiple", false),
                    custom = obj.optBoolean("custom", true),
                    raw = obj,
                )
            }
        }

        companion object {
            fun fromJson(obj: JSONObject): QuestionRequest {
                val tool = obj.optJSONObject("tool")
                val questionsJson = obj.optJSONArray("questions")
                return QuestionRequest(
                    id = obj.optString("id", ""),
                    sessionID = obj.optString("sessionID", ""),
                    questions = if (questionsJson == null) {
                        emptyList()
                    } else {
                        (0 until questionsJson.length()).mapNotNull { i ->
                            questionsJson.optJSONObject(i)?.let(Question::fromJson)
                        }
                    },
                    messageID = tool?.optString("messageID", "")?.takeIf { it.isNotEmpty() },
                    callID = tool?.optString("callID", "")?.takeIf { it.isNotEmpty() },
                    raw = obj,
                )
            }
        }
    }

    /** `RevertState` — `{messageID, partID?, snapshot?, diff?, files?[]}`. Measured on `revert/stage`. */
    data class RevertState(
        val messageID: String,
        val partID: String?,
        val snapshot: String?,
        val diff: String?,
        val files: List<JSONObject>,
    ) {
        companion object {
            fun fromJson(obj: JSONObject): RevertState = RevertState(
                messageID = obj.optString("messageID", ""),
                partID = obj.optString("partID", "").takeIf { it.isNotEmpty() },
                snapshot = obj.optString("snapshot", "").takeIf { it.isNotEmpty() },
                diff = obj.optString("diff", "").takeIf { it.isNotEmpty() },
                files = (0 until (obj.optJSONArray("files")?.length() ?: 0))
                    .mapNotNull { obj.optJSONArray("files")?.optJSONObject(it) },
            )
        }
    }

    /**
     * One SSE / history frame:
     * `{id, type, durable?:{aggregateID,seq,version}, location?:{directory},
     * data:{…}}`.
     *
     * This is **not** v1's `{"payload":{"id","type","properties"}}` wrapper: the
     * payload sits under `data`, the sequence under `durable.seq`, and
     * `properties` does not exist. [data] is intentionally unparsed — the
     * `session.next.*` union has ~28 members with per-type fields.
     */
    data class DurableEvent(
        val id: String,
        val type: String,
        val aggregateID: String?,
        val seq: Long?,
        val version: Long?,
        val locationDirectory: String?,
        val data: JSONObject,
        val raw: JSONObject,
    ) {
        /** `data.timestamp`, present on every `session.next.*` frame measured. */
        val timestamp: Long? get() = if (data.has("timestamp")) data.optLong("timestamp") else null

        /** `data.sessionID` — the session the frame belongs to. */
        val sessionID: String? get() = data.optString("sessionID", "").takeIf { it.isNotEmpty() }

        /** `data.assistantMessageID` — set on the step/text/reasoning/tool frames. */
        val assistantMessageID: String?
            get() = data.optString("assistantMessageID", "").takeIf { it.isNotEmpty() }

        /** `data.text` — set on `session.next.text.ended`, the final assistant text. */
        val text: String? get() = data.optString("text", "").takeIf { it.isNotEmpty() }

        companion object {
            fun fromJson(obj: JSONObject): DurableEvent {
                val durable = obj.optJSONObject("durable")
                // `log.synced` carries its watermark FLAT (`{…, seq}`), not
                // under `durable` — read both (flat wins when present).
                val flatSeq = if (obj.has("seq")) obj.optLong("seq") else null
                return DurableEvent(
                    id = obj.optString("id", ""),
                    type = obj.optString("type", "unknown"),
                    aggregateID = durable?.optString("aggregateID", "")?.takeIf { it.isNotEmpty() }
                        ?: obj.optString("aggregateID", "").takeIf { it.isNotEmpty() },
                    seq = flatSeq ?: if (durable?.has("seq") == true) durable.optLong("seq") else null,
                    version = if (durable?.has("version") == true) durable.optLong("version") else null,
                    locationDirectory = obj.optJSONObject("location")
                        ?.optString("directory", "")
                        ?.takeIf { it.isNotEmpty() },
                    data = obj.optJSONObject("data") ?: JSONObject(),
                    raw = obj,
                )
            }
        }
    }

    /** `PromptInput` file attachment — `{uri, name?, description?, source?}`. */
    data class PromptFile(val uri: String, val name: String? = null, val description: String? = null) {
        fun toJson(): JSONObject {
            val json = JSONObject().put("uri", uri)
            if (!name.isNullOrBlank()) json.put("name", name)
            if (!description.isNullOrBlank()) json.put("description", description)
            return json
        }
    }

    /** Non-2xx response. [code] is the HTTP status; the message carries the `_tag`. */
    class ApiException(val code: Int, message: String) : IOException(message)

    companion object {
        /** The literal query key the OpenAPI `deepObject` location parameter uses. */
        const val LOCATION_DIRECTORY_QUERY = "location[directory]"

        /** The literal query key for `location[workspace]`. */
        const val LOCATION_WORKSPACE_QUERY = "location[workspace]"

        /** Route prefix every v2 call shares. */
        const val API_PREFIX = "/api"

        /** Header `POST /api/pty/{id}/connect-token` requires (measured: absent ⇒ 403). */
        const val TICKET_HEADER = "x-opencode-ticket"

        private val EMPTY_BODY: RequestBody = ByteArray(0).toRequestBody(null)

        /**
         * [ApiException.code] used when a 2xx body is not JSON at all — the
         * measured signature of the SPA fallback, so no real status exists.
         */
        const val JSON_BODY_CODE = -1
        private val WHITESPACE = Regex("\\s+")

        /** REST timeouts, same shape as `OpenCodeClient`/`PtyApi`. */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        /**
         * Client for the SSE routes: no read timeout (the stream idles between
         * events; the server only heartbeats every 10 s).
         */
        fun defaultSseClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .build()

        /**
         * Blocking SSE reader for [sessionEventRequest]/[globalEventRequest],
         * mirroring `EventStream`'s frame rules: `data:` lines accumulate,
         * exactly one leading space is stripped, a blank line dispatches, and
         * `:` comment lines are keepalives. Returns when the stream ends.
         *
         * Measured: v2 frames carry no `event:` line, so the event name is always
         * the JSON's own `type`; an `event:` line is honoured as a fallback.
         */
        fun readEventStream(response: Response, onEvent: (DurableEvent) -> Unit) {
            val source = response.body?.source() ?: return
            val data = StringBuilder()
            var eventName: String? = null
            while (true) {
                val line = source.readUtf8Line() ?: break
                when {
                    line.startsWith("event:") -> eventName = line.removePrefix("event:").trim()
                    line.startsWith("data:") ->
                        data.appendLine(line.removePrefix("data:").removePrefix(" "))

                    line.isEmpty() -> {
                        val payload = data.toString().trim()
                        if (payload.isNotEmpty()) {
                            val parsed = runCatching { JSONObject(payload) }.getOrNull()
                            if (parsed != null) {
                                val event = DurableEvent.fromJson(parsed)
                                onEvent(
                                    if (event.type == "unknown" && !eventName.isNullOrEmpty()) {
                                        event.copy(type = eventName)
                                    } else {
                                        event
                                    },
                                )
                            }
                        }
                        data.setLength(0)
                        eventName = null
                    }
                    // `: heartbeat` and any other comment line: keepalive.
                }
            }
        }

        /**
         * Convenience constructor: parses [url], returning `null` when it is not a
         * valid HTTP(S) URL, so a bad server entry degrades instead of throwing at
         * construction (same contract as `PtyApi.forServer`).
         */
        fun forServer(
            url: String,
            username: String? = null,
            password: String? = null,
            directory: String? = null,
            http: OkHttpClient = defaultClient(),
        ): V2Api? {
            val parsed = url.toHttpUrlOrNull() ?: return null
            return V2Api(parsed, username, password, http, directory)
        }
    }
}

/**
 * `JSONArray` → `List<JSONObject>`, dropping non-object elements.
 *
 * File-private top-level helpers rather than instance methods, because the
 * nested DTO companions ([V2Api.Message]'s especially) parse their own payloads
 * and cannot reach an outer instance member.
 */
private fun v2ObjectList(array: JSONArray): List<JSONObject> =
    (0 until array.length()).mapNotNull { array.optJSONObject(it) }

/** `JSONArray` → `List<String>`, dropping non-strings. */
private fun v2StringList(array: JSONArray?): List<String> {
    if (array == null) return emptyList()
    return (0 until array.length()).mapNotNull { i ->
        array.optString(i, "").takeIf { it.isNotEmpty() }
    }
}
