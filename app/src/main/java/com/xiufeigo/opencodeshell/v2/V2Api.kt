package com.xiufeigo.opencodeshell.v2

import android.util.Log
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
 * verified against a live `opencode` 2.0.11 process on `127.0.0.1:4095` whose
 * `GET /openapi.json` advertises 113 paths. Mirrors [com.xiufeigo.opencodeshell.OpenCodeClient]
 * conventions — OkHttp + `org.json`, `basicAuthorizationHeader` for auth, a
 * non-2xx throwing [ApiException] — but shares no code with it.
 *
 * ## Envelopes actually measured (do not trust v1 muscle memory)
 *
 * | family | measured body |
 * |---|---|
 * | `GET /api/info` | bare `{"version":"2.0.11","pid":…,"urls":[…],"paths":{…}}` — **no** envelope |
 * | `GET /api/health` | `404` + empty body on 2.0.11; on 1.18.x a bare `{"healthy":true}` — **no** envelope, no version, no marker |
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
 * Every path absent from the v2 document answers **`200` + `text/html`**
 * (the SPA shell), never a JSON 404 — but the route list that used to follow
 * here (`/api/config*`, `/api/vcs*`, `/api/mcp*`, `/api/project*`, …) was a
 * 1.18.31/4096 measurement and is stale: on 2.0.11/4095 those routes serve
 * JSON (each method below notes its live measurement; `OpenCodeClient` has
 * migrated 24 routes onto them). SPA-fallback evidence is now only the
 * routes still absent from openapi.json — the v1 shapes
 * (`/api/session/{id}/{shell,prompt_async,diff,todo,abort}`,
 * `/api/question*`, `/api/workspace`, `/api/global/event`, …).
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
     * [withLocation] and a [directory] is configured **and [configure] sent no
     * location of its own**: an override the caller added replaces the instance
     * default instead of standing beside it (see [located]).
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
    internal fun url(
        path: String,
        withLocation: Boolean,
        configure: (HttpUrl.Builder) -> Unit = {},
    ): HttpUrl {
        val builder = baseHttpUrl.newBuilder().addPathSegment("api")
        for (segment in path.trim('/').split('/').filter { it.isNotEmpty() }) {
            builder.addPathSegment(segment)
        }
        configure(builder)
        val configured = builder.build()
        // A caller's own `location[directory]` (see [located]) replaces the
        // instance default rather than standing beside it: sending both puts the
        // key in the query twice, and the server resolves the **first**
        // occurrence (measured 2026-09-25) — so a default appended first would
        // silently win and the caller's scope would be dead.
        val callerScoped = configured.queryParameterValues(LOCATION_DIRECTORY_QUERY).isNotEmpty()
        return if (withLocation && !directory.isNullOrBlank() && !callerScoped) {
            configured.newBuilder().addQueryParameter(LOCATION_DIRECTORY_QUERY, directory).build()
        } else {
            configured
        }
    }

    internal fun builder(
        path: String,
        withLocation: Boolean,
        configure: (HttpUrl.Builder) -> Unit = {},
    ): Request.Builder {
        val request = Request.Builder().url(url(path, withLocation, configure))
        basicAuthorizationHeader(username, password)?.let { request.header("Authorization", it) }
        return request
    }

    /** Blocking call returning the raw body; throws [ApiException] on non-2xx. */
    internal fun callBody(request: Request): String =
        http.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: ""
            if (!response.isSuccessful) throw apiError(response.code, body)
            body
        }

    /** Blocking call for the 204 endpoints: asserts success, discards the body. */
    internal fun callUnit(request: Request) {
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val body = response.body?.string().orEmpty()
                throw apiError(response.code, body)
            }
        }
    }

    /** Blocking call for `GET /api/fs/read/<rel>`: asserts success, keeps the bytes. */
    internal fun callBytes(request: Request): ByteArray =
        http.newCall(request).execute().use { response ->
            val bytes = response.body?.bytes() ?: ByteArray(0)
            if (!response.isSuccessful) {
                throw apiError(response.code, bytes.toString(Charsets.UTF_8))
            }
            bytes
        }

    internal fun postJson(path: String, withLocation: Boolean, json: JSONObject): String =
        callBody(
            builder(path, withLocation).post(json.toString().toRequestBody(jsonMedia)).build(),
        )

    internal fun postUnit(path: String, withLocation: Boolean, json: JSONObject) {
        callUnit(builder(path, withLocation).post(json.toString().toRequestBody(jsonMedia)).build())
    }

    /**
     * Server errors carry a `_tag` discriminator — keep it in the message — and
     * provider failures carry a `type` (e.g. `rate_limit_error`): keep that too,
     * as `HTTP {code} [{type}] {human}`. The old shape dropped `type`, so the
     * only classifier input was keyword-guessing on the human sentence. The
     * human sentence (`data.message` / `message` / `name`) is still preferred
     * over the raw JSON body so an error card never renders a wall of
     * `{"name":…}`, and the `HTTP {code}` prefix is load-bearing:
     * `ChatStore.classifyStoredError` reads the status back off it with
     * `HTTP_CODE_REGEX` on the prompt-POST path (which has no error detail).
     *
     * A `ref` (`err_xxxxxxxx`) is appended as `Reference: {ref}`. Upstream's own
     * answer to an unexpected server failure is deliberately opaque — the
     * `UnknownError` carries the human sentence 「Unexpected server error. Check
     * server logs for details.」 and puts the *only* handle on the log line in
     * `ref` (`_upstream/packages/server/src/handlers/session-error.ts:22-28`;
     * `UnknownErrorEncoded` in `protocol/openapi.json`). Upstream's TUI prints
     * exactly this shape (`tui/src/util/error-details.ts:6`) so the user can
     * quote it; v63 measured the shell dropping it — the card rendered a bare
     * `HTTP 500` (`research/v63_verify/5560/06_err_detail.png`) with nothing to
     * search the server log for.
     */
    internal fun errorText(code: Int, body: String): String {
        val json = runCatching { JSONObject(body) }.getOrNull()
        if (json != null) {
            // Every field is read null-safely ([optStringOrNull]): a provider
            // error body may carry `"message":null` / `"type":null`, and the
            // Android `optString` would then put the literal word `null` on the
            // error card.
            val tag = optStringOrNull(json, "_tag").orEmpty()
            val type = errorTypeOf(json).orEmpty()
            val readable = optStringOrNull(json.optJSONObject("data"), "message")
                ?: optStringOrNull(json, "message")
                ?: optStringOrNull(json, "name")
                ?: ""
            val human = listOf(tag, readable).filter { it.isNotEmpty() }.distinct().joinToString(": ")
            val bracket = type.takeIf { it.isNotEmpty() && it != tag && it != readable }?.let { "[$it]" }
            val head = listOfNotNull(bracket, human.takeIf { it.isNotEmpty() }).joinToString(" ")
            val ref = errorRefOf(json)
            val tail = listOfNotNull(head.takeIf { it.isNotEmpty() }, ref?.let { "$REF_LABEL $it" })
            if (tail.isNotEmpty()) return "HTTP $code ${tail.joinToString(" ")}"
        }
        val detail = body.replace(WHITESPACE, " ").trim().take(200)
        return "HTTP $code" + (if (detail.isEmpty()) "" else " $detail")
    }

    /**
     * The server's log reference (`err_xxxxxxxx`), or null when the body carries
     * none. Read off `ref` / `data.ref`, the two envelopes the v2 server wraps
     * its errors in; blank and non-`err_` values are ignored so a provider body
     * that happens to have a `ref` field of its own cannot put a foreign id on
     * the card.
     */
    internal fun errorRefOf(body: String): String? =
        runCatching { JSONObject(body) }.getOrNull()?.let(::errorRefOf)

    private fun errorRefOf(json: JSONObject): String? =
        (optStringOrNull(json.optJSONObject("data"), "ref") ?: optStringOrNull(json, "ref"))
            ?.trim()
            ?.takeIf { it.startsWith(REF_PREFIX) }

    /**
     * The provider/server discriminator of an error body (`type`, e.g.
     * `rate_limit_error`; `data.type` when the envelope wraps it), or null when
     * the body carries none. Same parse [errorText] uses, exposed so
     * [ApiException.errorType] survives without re-parsing the message.
     */
    internal fun errorTypeOf(body: String): String? {
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        return errorTypeOf(json)
    }

    private fun errorTypeOf(json: JSONObject): String? =
        optStringOrNull(json.optJSONObject("data"), "type") ?: optStringOrNull(json, "type")

    /**
     * The single constructor for HTTP-layer failures: the message keeps
     * `HTTP {code} [{type}] {human}` ([errorText]) and the structured
     * discriminator plus a capped copy of the raw body ride along as fields,
     * so a classifier never has to scrape them back out of prose.
     */
    internal fun apiError(code: Int, body: String): ApiException =
        ApiException(
            code,
            errorText(code, body),
            errorTypeOf(body),
            body.takeIf { it.isNotEmpty() }?.take(RAW_BODY_CAP),
        )

    internal fun dataObject(body: String): JSONObject =
        parseObject(body).optJSONObject("data") ?: JSONObject()

    internal fun dataArray(body: String): JSONArray = parseObject(body).optJSONArray("data") ?: JSONArray()

    /**
     * `JSONObject(body)`, but a non-JSON body becomes an [ApiException] instead of
     * an `org.json.JSONException`.
     *
     * Reason this matters: a path absent from the v2 document answers
     * **`200` + `text/html`** (the SPA shell) — measured on 1.18.31/4096; on
     * 2.0.11/4095 the documented `/api/config*`, `/api/vcs*`, `/api/mcp*`,
     * `/api/project*` and sibling routes serve JSON (see the class KDoc), so
     * this backstop now guards only the still-undocumented leftovers. If
     * capability detection is ever skipped or wrong, the failure must land in
     * the same `catch (IOException)` the rest of the shell already uses, not
     * escape as an unchecked parse error. [V2Capability] is still the right
     * guard; this is the backstop.
     */
    internal fun parseObject(body: String): JSONObject = try {
        JSONObject(body)
    } catch (_: JSONException) {
        throw ApiException(
            JSON_BODY_CODE,
            "response is not JSON: ${body.replace(WHITESPACE, " ").trim().take(120)}",
        )
    }

    /**
     * Adds `location[directory]` when the caller overrode the instance default.
     *
     * The override is then the **only** `location[directory]` in the query:
     * [url] answers it in place of the instance default instead of beside it,
     * because the server resolves the first occurrence it sees (measured
     * 2026-09-25) — a default appended first would silently win over the
     * caller's scope.
     */
    internal fun located(builder: HttpUrl.Builder, override: String?) {
        if (!override.isNullOrBlank()) builder.addQueryParameter(LOCATION_DIRECTORY_QUERY, override)
    }

    // ------------------------------------------------------------------ api

    /**
     * Liveness → [Health]. **`GET /api/info`** is the live route: 2.0.11 answers
     * `404` + an **empty body** on `GET /api/health` (measured 2026-09-25), so
     * the old primary route made every caller throw. `/api/info` answers
     * `200` JSON `{"version":"2.0.11","pid":…,"urls":[…],"paths":{…}}` — the same
     * document `ServerHealth` probes.
     *
     * The legacy route stays as the fallback for the mirror image: a 1.18.x
     * server answers `/api/info` with the **SPA shell** (`200 text/html`, no
     * JSON) and `/api/health` with `{"healthy":true}`. So the fallback runs when
     * the primary is `404` or is not JSON at all ([JSON_BODY_CODE]); any other
     * failure (401/403/5xx/network) propagates untouched, because those are real
     * answers about the server, not a missing route.
     */
    fun health(): Health {
        val info = try {
            parseObject(callBody(builder("info", false).get().build()))
        } catch (e: ApiException) {
            if (e.code != 404 && e.code != JSON_BODY_CODE) throw e
            return Health.fromJson(parseObject(callBody(builder("health", false).get().build())))
        }
        return Health.fromJson(info)
    }

    /**
     * `GET /api/location` → `LocationInfo`.
     *
     * Measured: `{"directory":"C:\\WINDOWS\\system32","project":{"id":"global",
     * "directory":"C:\\"}}` with **no** envelope; `workspaceID` is absent
     * whenever the server runs without a workspace registry.
     *
     * [directory] is the `location[directory]` override
     * ([locationRef]): upstream's add-project registration reads the location of
     * the directory it just opened (`home/model.ts:105-112`
     * `ctx.sdk.api.location.get({ location })`), which is the instance default
     * only for its own cwd. Null/blank keeps the instance's directory — the
     * behaviour every existing caller had.
     */
    fun location(directory: String? = null): LocationInfo = LocationInfo.fromJson(
        parseObject(
            callBody(
                builder("location", true) { located(it, directory) }.get().build(),
            ),
        ),
    )

    /**
     * `GET /api/session` → `Page<SessionInfo>`.
     *
     * Measured: `{"data":[…]}`, and once `limit` is supplied a
     * `"cursor":{"previous","next"}` sibling appears (opaque base64; feed it
     * back as [cursor]). Newest first by default. `search` was verified live
     * (`?search=Friendly` → exactly the one matching session).
     *
     * Unlike the message route, follow-ups here MAY repeat `limit`/`order`
     * alongside `cursor` (measured 2026-09-24 on 4095: `limit+cursor` and
     * `order+cursor` both answer 200 with the next page) — the
     * cursor-must-ride-alone rule lives on `message` ([messagesRaw]), not here.
     *
     * Unlike the catalogs this route does **not** use `location[directory]`:
     * its OpenAPI `directory` parameter is a plain string query, and
     * [directoryScoped] sends `&directory=<instance default>`. Measured live:
     * `?directory=C:\Windows\System32` returned exactly the 3 of 50 sessions
     * whose `location.directory` matched. `project`/`subpath` overrides are
     * `UNVERIFIED` (never issued live). `parentID` filters by parent session;
     * the literal `"null"` selects root sessions only (`ParentIDFilter`,
     * `protocol/src/groups/session.ts:46-58`). There is no `?workspace=`
     * parameter upstream (`SessionsQuery` = `limit/order/search/parentID` +
     * `directory | project+subpath? | {}`) — it is not sent.
     */
    fun sessions(
        limit: Int? = null,
        order: String? = null,
        search: String? = null,
        cursor: String? = null,
        project: String? = null,
        subpath: String? = null,
        parentID: String? = null,
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
                if (!parentID.isNullOrBlank()) it.addQueryParameter("parentID", parentID)
            }.get().build(),
        ),
        SessionInfo::fromJson,
    )

    /**
     * `POST /api/session` body `{id?, title?, agent?, model?, location?,
     * metadata?, permissions?}` → `{"data":SessionInfo}` (`session.create`,
     * `protocol/src/groups/session.ts:220-237`).
     *
     * Measured live: `{"location":{"directory":"<repo>"},"agent":"build",
     * "model":{"id":"gpt-5.6-sol-fast","providerID":"openai"}}` answered 200 with
     * the created session, whose `projectID` was that directory's project hash
     * and whose title was the server-generated `New session - <ISO instant>`.
     * A caller-supplied [id] and the [agent]/[model] fields of a session
     * created without a prompt are `UNVERIFIED` beyond the echo above, as are
     * [metadata] (`Session.Metadata` record)
     * and [permissions] (`Permission.Ruleset`: `[{action, resource, effect}]`);
     * both ride only when non-null, and creation call sites pass neither yet.
     */
    fun createSession(
        agent: String? = null,
        model: ModelRef? = null,
        id: String? = null,
        title: String? = null,
        directory: String? = null,
        metadata: JSONObject? = null,
        permissions: JSONArray? = null,
    ): SessionInfo {
        val body = JSONObject()
        if (!id.isNullOrBlank()) body.put("id", id)
        if (!title.isNullOrBlank()) body.put("title", title)
        if (!agent.isNullOrBlank()) body.put("agent", agent)
        if (model != null) body.put("model", model.toJson())
        if (metadata != null) body.put("metadata", metadata)
        if (permissions != null) body.put("permissions", permissions)
        val location = locationRef(directory)
        if (location != null) body.put("location", location)
        return SessionInfo.fromJson(dataObject(postJson("session", false, body)))
    }

    /** `GET /api/session/{sessionID}` → `{"data":SessionInfo}`. Measured live. */
    fun session(sessionID: String): SessionInfo =
        SessionInfo.fromJson(dataObject(callBody(builder("session/$sessionID", false).get().build())))

    /**
     * `GET /api/session/{sessionID}` → the **raw** `SessionInfo` object (the
     * unwrapped `{data}` payload), for the session-export `info` key. Measured
     * 2026-09-22 on 2.0.11: `{"data":{"id":"ses_…","projectID":…,"cost":…,
     * "time":{…},"title":…,"location":{"directory":…}}}` — the v2 wire object
     * verbatim, never re-serialized from a DTO (v1's export contract is the
     * bare `Session.Info` from `GET /session/{id}`; see
     * `SessionTransport.fetchExportFrame`).
     */
    fun sessionRaw(sessionID: String): JSONObject =
        dataObject(callBody(builder("session/$sessionID", false).get().build()))

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
            out[key] = optStringOrNull(data.optJSONObject(key), "type").orEmpty()
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
     *
     * Second gotcha: `cursor` and `order` must never ride together (measured
     * `400 InvalidCursorError`, see [messagesRaw]) — pass one or the other.
     *
     * Third: upstream's `type` filter (`protocol/src/groups/message.ts:23-39`)
     * is deliberately **not** exposed. It applies *before* pagination, and every
     * read this client makes folds the whole tagged union into the store (the
     * snapshot renders `user`/`assistant`/`compaction` and the notice rows
     * alike), so a filter could only shorten a page — no upstream client passes
     * one either (`packages/cli`, `packages/app`).
     */
    fun messages(
        sessionID: String,
        limit: Int? = null,
        order: String? = null,
        cursor: String? = null,
    ): Page<Message> = parsePage(
        callBody(
            builder("session/$sessionID/message", false) {
                if (limit != null && limit > 0) it.addQueryParameter("limit", limit.toString())
                if (!order.isNullOrBlank()) it.addQueryParameter("order", order)
                if (!cursor.isNullOrBlank()) it.addQueryParameter("cursor", cursor)
            }.get().build(),
        ),
        Message::fromJson,
    )

    /**
     * `GET /api/session/{sessionID}/message?order=asc` → **every raw message
     * row** (the unwrapped `{data}` objects), for the session-export
     * `messages` key. Measured 2026-09-22 on 2.0.11:
     * - default order is newest-first, so page 1 asks `order=asc` (upstream's
     *   export also reads `order:"asc"`);
     * - pages follow `cursor.next`, and the follow-up carries `cursor=` **alone**:
     *   measured 2026-09-24 on 4095 that `order=asc&cursor=…` (and
     *   `cursor=…&order=desc`) answers `400 {"_tag":"InvalidCursorError",
     *   "message":"Cursor cannot be combined with order"}` while `cursor=…`
     *   alone answers 200 (upstream `server/src/handlers/message.ts:36` rejects
     *   `cursor && order !== undefined`, then derives the order from the
     *   decoded cursor — hence the follow-up needs no `order` at all);
     * - a 17-message session drained in 2 pages / 17 rows live. [maxPages]
     *   only guards a pathological cursor loop; the export truncates there.
     * - full-chain re-verified 2026-09-25 on 4095: all 100 sessions drained
     *   with exactly this wire shape (page 1 `order=asc`, follow-ups `cursor=`
     *   alone) — 443 rows / 97 follow-up pages, zero 400s. The server's
     *   `InvalidCursorError` "Invalid cursor" arm fires only when the `cursor`
     *   value fails base64url→JSON→`{id,order,direction}` validation (upstream
     *   `server/src/handlers/message.ts`), and this loop round-trips
     *   server-issued `cursor.next` values verbatim, so a 400 here means the
     *   build under test did not run this loop.
     * - **2026-09-25 device P0 (fixed):** the loop *did* run on the device and
     *   still 400'd because the terminal page's `cursor.next` is JSON `null`
     *   and [Cursor.fromJson] read it through `optString`, whose result differs
     *   between the two `org.json` builds this code runs on: Android's
     *   stringifies the `NULL` sentinel to the literal `"null"` (only a missing
     *   key falls back), so the drain echoed `cursor=null` — measured live on
     *   4095 as exactly `400 {"_tag":"InvalidCursorError","message":"Invalid
     *   cursor"}` — while the JVM build the unit tests use returns the fallback
     *   and stopped cleanly, which is why every JVM test stayed green. The
     *   session-list cursor ([sessions]) had the same defect. Both now read
     *   their halves through [opaqueCursorValue], which uses the
     *   runtime-stable `isNull` and refuses the stringified sentinels, so the
     *   terminal page ends the drain on every runtime.
     *
     * The full drain feeds ⋮ Export, the context tab, `ExportSessionButton`
     * and the background-tasks poll (`SessionTransport.fetchExportFrame`,
     * `ChatViewModel.refreshBackgroundTasks`) — all four broke together when
     * the follow-up re-sent `order`, and all four recover with this loop.
     *
     * [maxPages] is a safety cap, and hitting it used to be silent: the drain
     * simply stopped mid-transcript and the caller could not tell the truncated
     * list from a complete one. Callers that hand the rows to a user (the
     * export) take [messagesRawPage] instead, which reports it; this overload
     * stays for the ones that only derive state.
     */
    fun messagesRaw(sessionID: String, maxPages: Int = 128): List<JSONObject> =
        messagesRawPage(sessionID, maxPages).messages

    /**
     * One full drain of `GET /api/session/{sessionID}/message`, with the
     * truncation flag the row list cannot carry.
     *
     * [MessageDrain.truncated] is true **only** when the drain stopped because
     * of the [maxPages] cap with the server still offering a `cursor.next` —
     * not when the last page simply ended the transcript. A truncated drain is
     * also logged, so a release build says why a transcript is short.
     *
     * The cursor is echoed verbatim and nothing else: it is the server's own
     * `cursor.next`, so re-validating its decoded shape locally could only
     * diverge from the server's rule (and would turn a loud 400 into a silent
     * short transcript). The one thing that *is* filtered is a value
     * [opaqueCursorValue] can tell was never issued — see [messagesRaw] for the
     * 2026-09-25 device P0 that made that necessary.
     */
    fun messagesRawPage(sessionID: String, maxPages: Int = 128): MessageDrain {
        val out = ArrayList<JSONObject>()
        var cursor: String? = null
        var page = 0
        do {
            val pageCursor = cursor
            val body = callBody(
                builder("session/$sessionID/message", false) {
                    if (page == 0) it.addQueryParameter("order", "asc")
                    if (!pageCursor.isNullOrBlank()) it.addQueryParameter("cursor", pageCursor)
                }.get().build(),
            )
            val root = parseObject(body)
            out += v2ObjectList(root.optJSONArray("data") ?: JSONArray())
            cursor = Cursor.fromJson(root.optJSONObject("cursor"))?.next
            page++
        } while (!cursor.isNullOrBlank() && page < maxPages)
        val truncated = !cursor.isNullOrBlank()
        if (truncated) {
            Log.w(TAG, "messagesRaw truncated: $sessionID stopped at the $maxPages page cap")
        }
        return MessageDrain(messages = out, truncated = truncated, pages = page)
    }

    /**
     * One drain of the session transcript: the raw rows plus what the paging
     * loop had to leave behind. [pages] is the number of requests the drain
     * made, [truncated] the [messagesRawPage] cap flag.
     */
    data class MessageDrain(
        val messages: List<JSONObject>,
        val truncated: Boolean,
        val pages: Int,
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
     *
     * [files]/[agents]/[skills] are `PromptInput.Prompt`'s arrays
     * (`schema/src/prompt-input.ts:29-35`), element shapes
     * `{uri, name?, description?, mention?}` / `{name, mention?}` /
     * `{id, mention?}` — [PromptFile], [PromptAgent] and [PromptSkill] build
     * them, `mention` included. [metadata] is
     * `SessionInbox.UserPayload.metadata` (free-form; the structured line
     * comments ride here, `composer/submit.ts:169-177`).
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
            if (!response.isSuccessful) throw apiError(response.code, body)
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
     * `POST /api/session/{sessionID}/background` (empty body) → **204**.
     *
     * `session.background` — "Move active foreground backgroundable tools for
     * this session into background observation. Idle requests are a no-op."
     * (`_upstream/packages/protocol/src/groups/session.ts`). Upstream calls it
     * as `serverSDK.api.session.background({sessionID})`
     * (`requests/model.ts:90`). `UNVERIFIED` live (openapi.json only).
     */
    fun background(sessionID: String) {
        postUnit("session/$sessionID/background", false, JSONObject())
    }

    /**
     * `PUT /api/session/{sessionID}/environment` body
     * `{variables:Record<String,String>}` → **204**.
     *
     * `session.environment` — "Replace the process environment used by local
     * shell commands for this session" (`protocol/src/groups/session.ts`).
     * `UNVERIFIED` live (openapi.json only).
     */
    fun environment(sessionID: String, variables: Map<String, String>) {
        val entries = JSONObject()
        for ((key, value) in variables) entries.put(key, value)
        val body = JSONObject().put("variables", entries)
        callUnit(
            builder("session/$sessionID/environment", false)
                .put(body.toString().toRequestBody(jsonMedia))
                .build(),
        )
    }

    /**
     * `POST /api/session/{sessionID}/generate` body `{prompt}` →
     * `{data:{text}}` (`SessionGenerateResponse`).
     *
     * `session.generate` — "Generate transient text from the current session
     * context without mutating session history"
     * (`protocol/src/groups/session.ts:705-721`). `UNVERIFIED` live
     * (openapi.json only).
     */
    fun generate(sessionID: String, prompt: String): String {
        val body = JSONObject().put("prompt", prompt)
        val answer = dataObject(postJson("session/$sessionID/generate", false, body))
        return optStringOrNull(answer, "text").orEmpty()
    }

    /**
     * `POST /api/session/{sessionID}/move` body `{directory, delivery?}` →
     * **204**.
     *
     * `session.move` — "Move a session to another project directory at the
     * requested delivery boundary" (`protocol/src/groups/session.ts:377-385`).
     * Payload is `Location.PublicRef.fields` + `delivery?`; `PublicRef` is
     * `{directory}` alone (`schema/src/location.ts:13-17` — `workspaceID` is
     * omitted from the public shape). `delivery` ∈ `"steer"` | `"queue"`
     * (`schema/src/session-inbox.ts:11`). Upstream sends `{sessionID,
     * directory}` (`session-workspace-menu.tsx:77`). `UNVERIFIED` live
     * (openapi.json only).
     */
    fun moveSession(sessionID: String, directory: String, delivery: String? = null) {
        val body = JSONObject().put("directory", directory)
        if (!delivery.isNullOrBlank()) body.put("delivery", delivery)
        postUnit("session/$sessionID/move", false, body)
    }

    /**
     * `POST /api/session/{sessionID}/synthetic` body `{id?, text, description?,
     * metadata?, delivery?, resume?}` → `{data:Session.Inbox.Synthetic}`.
     *
     * `session.synthetic` — "Durably admit synthetic session input and schedule
     * execution unless resume is false"
     * (`protocol/src/groups/session.ts:452-473`). `metadata` is
     * `SessionMessage.Synthetic`'s optional `Record<String, Unknown>`
     * (`schema/src/session-message.ts:34`). `UNVERIFIED` live
     * (openapi.json only).
     */
    fun synthetic(
        sessionID: String,
        text: String,
        description: String? = null,
        id: String? = null,
        delivery: String? = null,
        resume: Boolean? = null,
        metadata: JSONObject? = null,
    ): JSONObject {
        val body = JSONObject().put("text", text)
        if (!description.isNullOrBlank()) body.put("description", description)
        if (!id.isNullOrBlank()) body.put("id", id)
        if (!delivery.isNullOrBlank()) body.put("delivery", delivery)
        if (resume != null) body.put("resume", resume)
        if (metadata != null) body.put("metadata", metadata)
        return dataObject(postJson("session/$sessionID/synthetic", false, body))
    }

    /**
     * `POST /api/session/{sessionID}/view` body `{idle: DateTimeUtcFromMillis}`
     * → **204**.
     *
     * `session.view` — "Mark the idle transition observed by the viewer as
     * viewed" (`protocol/src/groups/session.ts:884-891`), i.e. the unread
     * acknowledgement of one idle boundary. `idle` is a finite epoch-**millis**
     * number (`DateTimeUtcFromMillis`, `schema/src/schema.ts:25-32`).
     * `UNVERIFIED` live (openapi.json only).
     */
    fun view(sessionID: String, idle: Long) {
        postUnit("session/$sessionID/view", false, JSONObject().put("idle", idle))
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
     * text(required), files?, agents?, skills?, delivery?}` → **204**
     * (`session.command` = `{name, ...PromptInput.Prompt, delivery?}`,
     * `protocol/src/groups/session.ts`; `Prompt` =
     * `{text, files?, agents?, skills?}`, `schema/src/prompt-input.ts:29-35`).
     * Measured 204 live; v1's `{command,arguments,agent,model,variant}` shape
     * is gone (`command→name`, `arguments→text`). [agents]/[skills] mirror
     * [prompt] — same element shapes (`{name, mention?}` / `{id, mention?}`),
     * omitted unless supplied.
     */
    fun command(
        sessionID: String,
        name: String,
        text: String,
        files: List<PromptFile> = emptyList(),
        delivery: String? = null,
        agents: List<JSONObject>? = null,
        skills: List<JSONObject>? = null,
    ) {
        val body = JSONObject().put("name", name).put("text", text)
        if (files.isNotEmpty()) {
            val array = JSONArray()
            for (file in files) array.put(file.toJson())
            body.put("files", array)
        }
        agents?.let { body.put("agents", JSONArray(it)) }
        skills?.let { body.put("skills", JSONArray(it)) }
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
     * `PATCH /api/session/{sessionID}` body `{title?, permissions?}` →
     * **204** (`session.update`, `protocol/src/groups/session.ts:357-375`).
     * Measured 204 live. [permissions] is a `Permission.Ruleset`
     * (`[{action, resource, effect}]`, `schema/src/permission.ts:60-66`),
     * omitted unless supplied so plain renames send exactly what they sent
     * before. A null [title] omits the key too: `session.update`'s title is an
     * optional *string* that rejects JSON `null` (400).
     */
    fun renameSession(sessionID: String, title: String?, permissions: JSONArray? = null) {
        val body = JSONObject()
        if (title != null) body.put("title", title)
        permissions?.let { body.put("permissions", it) }
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
     * Field types are `Permission.Request.fields` (`schema/src/permission.ts:25-33`):
     * `save` = `string[]`, `metadata` = `Record<string, unknown>` (free-form),
     * `source` = `{type:"tool", messageID, id}` (the tool call that triggered
     * the ask, `permission.ts:16-22`), `agent` = `Agent.ID`. All three are
     * optional and omitted unless supplied — a caller that passes none sends
     * exactly the body it sent before.
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
        metadata: JSONObject? = null,
        source: JSONObject? = null,
        agent: String? = null,
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
        metadata?.let { body.put("metadata", it) }
        source?.let { body.put("source", it) }
        if (!agent.isNullOrBlank()) body.put("agent", agent)
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
     * `GET /api/permission/saved[?projectID=]` → `{"data":PermissionSaved.Info[]}`
     * (no `location`). Measured live: `{"data":[]}` — the live server had no
     * saved rule to sample, so every non-empty instance is `UNVERIFIED`; the
     * element shape itself is the schema's
     * `{id, projectID, action, resource, time{created, updated}}`
     * (`schema/src/permission-saved.ts:11-22`).
     *
     * `permission.saved.list` takes an optional `projectID` query
     * (`protocol/src/groups/permission.ts:37-40`) — "optionally filtered by
     * project". Omitted/blank ⇒ no parameter at all (the server then answers
     * every project's saved rules), which is what every existing caller gets.
     */
    fun savedPermissions(projectID: String? = null): List<SavedPermission> = v2ObjectList(
        dataArray(
            callBody(
                builder("permission/saved", false) {
                    if (!projectID.isNullOrBlank()) it.addQueryParameter("projectID", projectID)
                }.get().build(),
            ),
        ),
    ).map(SavedPermission::fromJson)

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
    // The old question ask/reply/reject surface is therefore REMOVED (not
    // deprecated): v1 question
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
     * `GET /api/session/{sessionID}/form/{formID}` → `{data:Form.Detail}`.
     *
     * `session.form.get` (`protocol/src/groups/session.ts:824-837`) answers
     * **`Form.Detail`**, not `Form.Info`: Detail is Info's fields plus `state`
     * (`schema/src/form.ts:150-160`) — `{status:"pending"}`,
     * `{status:"answered", answer}` or `{status:"cancelled"}`. That `state` is
     * the entire difference, and it is the only way to tell an answered or
     * cancelled form from a pending one, so [FormInfo.state] carries it (null on
     * [sessionForms]/[pendingForms], whose payloads really are `Form.Info`).
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

    /**
     * `Form.State` (`schema/src/form.ts:140-148`) — the tagged union the
     * `answered` member extends with its `answer` record. [status] is
     * `"pending"` | `"answered"` | `"cancelled"`; [answer] is set only on
     * `answered` (keyed by field key, `Form.Answer`).
     */
    data class FormState(val status: String, val answer: JSONObject? = null) {
        companion object {
            /** Null when there is no `state` key, or it carries no readable `status`. */
            fun fromJson(obj: JSONObject?): FormState? {
                if (obj == null) return null
                val status = optStringOrNull(obj, "status") ?: return null
                return FormState(status, obj.optJSONObject("answer"))
            }
        }
    }

    /** `Form.Info{id:frm_, sessionID, title, metadata?, fields[]}` (Wave 2 owns field rendering). */
    data class FormInfo(
        val id: String,
        val sessionID: String,
        val title: String,
        val fields: JSONArray,
        val raw: JSONObject,
        /**
         * `Form.Detail.state` — populated by [form] (the only route answering
         * Detail); the list routes answer `Form.Info` and leave this null.
         */
        val state: FormState? = null,
    ) {
        companion object {
            fun fromJson(obj: JSONObject): FormInfo = FormInfo(
                id = obj.optString("id", ""),
                sessionID = obj.optString("sessionID", ""),
                title = obj.optString("title", ""),
                fields = obj.optJSONArray("fields") ?: JSONArray(),
                raw = obj,
                state = FormState.fromJson(obj.optJSONObject("state")),
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
     *
     * `from` / `to` are **user** message ids and `context` the unchanged lines
     * around each hunk (`protocol/src/groups/session.ts:579-601`); omitting both
     * ids asks for the turn of the newest user message, which is the request the
     * review panel makes — so an empty `{data:[]}` is never a parameter error.
     *
     * The route is a *snapshot* diff (`core/src/session/diff.ts`), not a list of
     * the session's write/edit calls: it compares the `snapshot.start`/`end`
     * git-tree ids the runner records on each assistant step, so it is empty
     * whenever those trees were never captured. `Snapshot.capture()` bails on
     * `location.vcs?.type === "git"` (`core/src/snapshot.ts enabled()`), which a
     * location graph built before its directory's `git init` never has — measured
     * 2026-09-25 on 2.0.11: every session in such a directory answers
     * `{data:[]}` however many files the session wrote, and no snapshot
     * repository is ever created under the server's data dir
     * (`research/v8_verify/err_review`). Re-probing the location read-only
     * cannot repair that; see `ui/session/review/ReviewVcsReprobe`.
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

    /**
     * `GET /api/config/shell` → a BARE ARRAY `[{path,name,acceptable}]` — the
     * server shell picker's options (measured 2026-09-22 on 2.0.11:
     * `sh`/`bash`/`rbash` per prefix). Returned as the distinct `name`s, the
     * ids `serverShellInfo` compares against `config.info.shell`.
     */
    fun shellOptions(): List<String> {
        val body = callBody(builder("config/shell", false).get().build()).trim()
        if (!body.startsWith("[")) throw ApiException(JSON_BODY_CODE, "response is not a bare array")
        return v2ObjectList(JSONArray(body)).mapNotNull { obj ->
            optStringOrNull(obj, "name")
        }.distinct()
    }

    /** `GET /api/mcp` → `{location,data:[{name,status…}]}`. Measured live. */
    fun mcp(locationDirectory: String? = null): List<JSONObject> = v2ObjectList(
        dataArray(callBody(builder("mcp", true) { located(it, locationDirectory) }.get().build())),
    )

    /**
     * `GET /api/plugin` → `{location,data:[{id,source,features,state}]}`.
     * Measured 2026-09-22 on 2.0.11 — elements carry `id` (no `name`);
     * `pluginPageRows` accepts either key.
     */
    fun plugins(locationDirectory: String? = null): List<JSONObject> = v2ObjectList(
        dataArray(callBody(builder("plugin", true) { located(it, locationDirectory) }.get().build())),
    )

    /** `GET /api/shell` → `{location,data:[…]}` (raw; Wave 2 owns the rows).
     *
     * Measured 2026-09-24 on 4095: `{"location":{"directory":"…"},"data":[]}`
     * with no shells running, and `?location[directory]=/tmp` echoed back as
     * `"directory":"/tmp"` — the deepObject key is honoured, so pass the same
     * directory the shells were created under (a create with omitted `cwd`
     * lands in the location directory). Re-measured 2026-09-25: a flat
     * `?directory=/tmp` is IGNORED (the location echoes the default
     * directory), so list and create must both go through [located] — they do
     * (`shellCreate` uses the same helper, one scope by construction).
     * Upstream lists **currently running** commands only (`shell.ts` summary:
     * "List currently running shell commands… Exited commands are not
     * included"), so an empty list is the normal idle state, not a parse
     * failure — and a refetch can never confirm a create: a command that
     * exits before the next poll never appears. Callers must pin the
     * `shell.create` response row (see `BackgroundShellPanel`) and resolve
     * its terminal status via `shellGet`, which — unlike the list — returns
     * exited rows with their exit code. Element shape is `Shell.Info`
     * ([ShellCommandInfo]).
     */
    fun shells(locationDirectory: String? = null): List<JSONObject> = v2ObjectList(
        dataArray(callBody(builder("shell", true) { located(it, locationDirectory) }.get().build())),
    )

    /**
     * `GET /api/worktree?projectID=` → a BARE ARRAY `[{directory, strategy?}]`
     * (`Worktree.List` = `Worktree.Directory[]`; raw rows, Wave 2 owns them) —
     * **no** `{location,data}` envelope, do not use [dataArray]. Measured live
     * 2026-09-23: `[{"directory":"/mnt/c/…/opencode-android-shell"}]`.
     * Without `?projectID` the server answers 400 — pass one explicitly.
     */
    fun worktrees(projectID: String, locationDirectory: String? = null): List<JSONObject> {
        val body = callBody(
            builder("worktree", true) {
                located(it, locationDirectory)
                it.addQueryParameter("projectID", projectID)
            }.get().build(),
        ).trim()
        if (!body.startsWith("[")) throw ApiException(JSON_BODY_CODE, "response is not a bare array")
        return v2ObjectList(JSONArray(body))
    }

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
                    current = optStringOrNull(branch, "current"),
                    default = optStringOrNull(branch, "default"),
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
     * `GET /api/websearch` provider catalog — the route the generated client
     * calls `websearch.providers` (`GET /api/websearch/provider`,
     * `mock-api.ts:88`) returning `{location, data:[{id,name}]}`
     * (`WebSearch.Provider`, `schema/src/websearch.ts`). The consent dock maps
     * the rows to `{value: id, label: name}` (`model.ts:50-53`).
     */
    data class WebSearchProviderInfo(val id: String, val name: String) {
        companion object {
            fun fromJson(obj: JSONObject): WebSearchProviderInfo = WebSearchProviderInfo(
                id = obj.optString("id", ""),
                name = obj.optString("name", ""),
            )
        }
    }

    fun websearchProviders(locationDirectory: String? = null): List<WebSearchProviderInfo> = v2ObjectList(
        dataArray(callBody(builder("websearch/provider", true) { located(it, locationDirectory) }.get().build())),
    ).map(WebSearchProviderInfo::fromJson)

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

    internal fun <T> parsePage(body: String, map: (JSONObject) -> T?): Page<T> {
        val root = parseObject(body)
        return Page(
            data = v2ObjectList(root.optJSONArray("data") ?: JSONArray()).mapNotNull(map),
            cursor = Cursor.fromJson(root.optJSONObject("cursor")),
        )
    }

    /**
     * `Location.PublicRef` — `{directory}` alone (`schema/src/location.ts:13-17`);
     * there is no `workspace` key in the upstream shape, so none is ever sent.
     * Null when neither [override] nor the instance default names a directory.
     */
    internal fun locationRef(override: String?): JSONObject? {
        val dir = if (!override.isNullOrBlank()) override else directory
        if (dir.isNullOrBlank()) return null
        return JSONObject().put("directory", dir)
    }

    // ------------------------------------------------------------------ dto

    /**
     * Liveness document. Two shapes reach it:
     *
     * - `GET /api/info` (2.0.x) → `{"version":"2.0.11",…}`: healthy iff a
     *   `version` marker is readable, [version] carries it;
     * - `GET /api/health` (1.18.x, the fallback) → `{"healthy":true}`: healthy
     *   iff the flag is set, [version] is null.
     *
     * [version] is read through [optStringOrNull], never a bare `optString`:
     * the two `org.json` builds this app runs on disagree about a JSON `null`
     * (Android stringifies the sentinel as `"null"`, the JVM returns the
     * fallback), and `"null"` as a version would read as a healthy server.
     */
    data class Health(val healthy: Boolean, val version: String? = null) {
        companion object {
            fun fromJson(obj: JSONObject): Health {
                val version = optStringOrNull(obj, "version")
                return Health(healthy = obj.optBoolean("healthy", false) || version != null, version = version)
            }
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
                    directory = optStringOrNull(obj, "directory").orEmpty(),
                    workspaceID = optStringOrNull(obj, "workspaceID"),
                    projectID = optStringOrNull(project, "id"),
                    projectDirectory = optStringOrNull(project, "directory"),
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
                variant = optStringOrNull(obj, "variant"),
            )
        }
    }

    /**
     * `Page.cursor` — `{previous, next}`, both nullable and opaque.
     *
     * Both halves are read through [opaqueCursorValue], never bare `optString`:
     * the wire sets them to JSON `null` on the terminal page, and the two
     * `org.json` builds this app runs on disagree about what `optString` does
     * with that. Android's `JSONObject.optString(name, fallback)` runs the value
     * through `JSON.toString`, and the `NULL` sentinel's `toString()` is the
     * literal `"null"` — only a *missing* key reaches the fallback — so
     * `{"next":null}` read as the string `"null"`, the drain echoed
     * `?cursor=null`, and the server answered `400 InvalidCursorError: Invalid
     * cursor`. The JVM build the unit tests run on returns the fallback for
     * `NULL` (verified against `org.json:json:20240303`), which is why the whole
     * JVM suite stayed green through the device P0.
     */
    data class Cursor(val previous: String?, val next: String?) {
        companion object {
            fun fromJson(obj: JSONObject?): Cursor? = obj?.let {
                Cursor(
                    previous = opaqueCursorValue(it, "previous"),
                    next = opaqueCursorValue(it, "next"),
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
                    parentID = optStringOrNull(obj, "parentID"),
                    agent = optStringOrNull(obj, "agent"),
                    model = obj.optJSONObject("model")?.let(ModelRef::fromJson),
                    cost = obj.optDouble("cost", 0.0),
                    tokensInput = tokens?.optLong("input", 0L) ?: 0L,
                    tokensOutput = tokens?.optLong("output", 0L) ?: 0L,
                    tokensReasoning = tokens?.optLong("reasoning", 0L) ?: 0L,
                    timeCreated = time?.optLong("created", 0L) ?: 0L,
                    timeUpdated = time?.optLong("updated", 0L) ?: 0L,
                    timeArchived = time?.optLong("archived", 0L)?.takeIf { it > 0L },
                    title = optStringOrNull(obj, "title") ?: UNTITLED,
                    directory = optStringOrNull(location, "directory"),
                    subpath = optStringOrNull(obj, "subpath"),
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
     * `"shell"` → [command], [output] (the *inner* `Shell.Output.output`
     * string — see [shellOutputText]); `"agent-switched"`, `"model-switched"`,
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

            /**
             * `Session.Message.Idle` — the durable **turn marker**
             * (`_upstream/packages/schema/src/session-message.ts:288-292`):
             * `{type:"idle", outcome:"succeeded"|"failed"|"interrupted"}`, one
             * per finished turn, never a drawn row. Measured 2.0.18/4095 on a
             * Stop that landed before the provider answered: `user` row +
             * `idle{outcome:"interrupted"}` and nothing in between.
             */
            const val TYPE_IDLE = "idle"

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
                    // `has("text")` is true for a JSON `null` too, so the key check
                    // alone is not enough: read through the null-safe helper, or a
                    // `{"text":null}` frame renders the word "null" as the body.
                    text = if (obj.has("text")) optStringOrNull(obj, "text").orEmpty() else null,
                    agent = optStringOrNull(obj, "agent"),
                    model = obj.optJSONObject("model")?.let(ModelRef::fromJson),
                    content = content,
                    command = optStringOrNull(obj, "command"),
                    output = shellOutputText(obj),
                    compactionSummary = optStringOrNull(obj, "summary"),
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
                    text = optStringOrNull(payload, "text").orEmpty(),
                    delivery = optStringOrNull(obj, "delivery"),
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
                name = optStringOrNull(obj, "name") ?: obj.optString("id", ""),
                description = obj.optString("description", ""),
                mode = obj.optString("mode", ""),
                hidden = obj.optBoolean("hidden", false),
                system = optStringOrNull(obj, "system"),
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
                    modelID = optStringOrNull(obj, "modelID") ?: id,
                    providerID = obj.optString("providerID", ""),
                    name = optStringOrNull(obj, "name") ?: id,
                    family = optStringOrNull(obj, "family"),
                    status = optStringOrNull(obj, "status"),
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
            internal fun variantIds(array: JSONArray?): List<String> {
                if (array == null) return emptyList()
                return (0 until array.length()).mapNotNull { i ->
                    when (val element = array.opt(i)) {
                        is JSONObject -> optStringOrNull(element, "id")
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
                    name = optStringOrNull(obj, "name") ?: obj.optString("id", ""),
                    activation = optStringOrNull(obj, "activation"),
                    integrationID = optStringOrNull(obj, "integrationID"),
                    pkg = optStringOrNull(obj, "package"),
                    apiType = optStringOrNull(api, "type"),
                    apiPackage = optStringOrNull(api, "package"),
                    apiUrl = optStringOrNull(api, "url"),
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
                    location = optStringOrNull(obj, "path") ?: optStringOrNull(obj, "location"),
                    content = optStringOrNull(obj, "content"),
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
                        optStringOrNull(methods.optJSONObject(i), "type")?.let(types::add)
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
                val callID = optStringOrNull(source, "id")
                    ?: optStringOrNull(source, "callID")
                return PermissionRequest(
                    id = obj.optString("id", ""),
                    sessionID = obj.optString("sessionID", ""),
                    action = obj.optString("action", ""),
                    resources = v2StringList(obj.optJSONArray("resources")),
                    save = v2StringList(obj.optJSONArray("save")),
                    messageID = optStringOrNull(source, "messageID"),
                    callID = callID,
                    raw = obj,
                )
            }
        }
    }

    /**
     * `PermissionSaved.Info` — `{id, projectID, action, resource,
     * time:{created, updated}}` (`schema/src/permission-saved.ts:11-22`), the
     * element of [savedPermissions]. [raw] keeps anything the schema grows.
     *
     * `UNVERIFIED` beyond the empty list: the live server had no saved rule.
     */
    data class SavedPermission(
        val id: String,
        val projectID: String,
        val action: String,
        val resource: String,
        val timeCreated: Long?,
        val timeUpdated: Long?,
        val raw: JSONObject,
    ) {
        companion object {
            fun fromJson(obj: JSONObject): SavedPermission {
                val time = obj.optJSONObject("time")
                return SavedPermission(
                    id = obj.optString("id", ""),
                    projectID = obj.optString("projectID", ""),
                    action = obj.optString("action", ""),
                    resource = obj.optString("resource", ""),
                    timeCreated = if (time?.has("created") == true) time.optLong("created") else null,
                    timeUpdated = if (time?.has("updated") == true) time.optLong("updated") else null,
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
                partID = optStringOrNull(obj, "partID"),
                snapshot = optStringOrNull(obj, "snapshot"),
                diff = optStringOrNull(obj, "diff"),
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
        val sessionID: String? get() = optStringOrNull(data, "sessionID")

        /** `data.assistantMessageID` — set on the step/text/reasoning/tool frames. */
        val assistantMessageID: String?
            get() = optStringOrNull(data, "assistantMessageID")

        /** `data.text` — set on `session.next.text.ended`, the final assistant text. */
        val text: String? get() = optStringOrNull(data, "text")

        companion object {
            fun fromJson(obj: JSONObject): DurableEvent {
                val durable = obj.optJSONObject("durable")
                // `log.synced` carries its watermark FLAT (`{…, seq}`), not
                // under `durable` — read both (flat wins when present).
                val flatSeq = if (obj.has("seq")) obj.optLong("seq") else null
                return DurableEvent(
                    id = obj.optString("id", ""),
                    type = obj.optString("type", "unknown"),
                    aggregateID = optStringOrNull(durable, "aggregateID")
                        ?: optStringOrNull(obj, "aggregateID"),
                    seq = flatSeq ?: if (durable?.has("seq") == true) durable.optLong("seq") else null,
                    version = if (durable?.has("version") == true) durable.optLong("version") else null,
                    locationDirectory = optStringOrNull(obj.optJSONObject("location"), "directory"),
                    data = obj.optJSONObject("data") ?: JSONObject(),
                    raw = obj,
                )
            }
        }
    }

    /**
     * `Prompt.Mention` — `{start, end, text}` (`schema/src/prompt.ts:7-12`): the
     * `@` token span inside the prompt text that produced an attachment
     * (`composer/request.ts:66-85` fills it for every file/agent/skill entry).
     * The runner keys inline-image de-duplication off `files[].mention.text`
     * (`core/src/session/runner/to-llm-message.ts:87-95`), so an attachment
     * without it cannot be recognised as a repeat of an earlier `@`.
     */
    data class PromptMention(val start: Int, val end: Int, val text: String) {
        fun toJson(): JSONObject = JSONObject().put("start", start).put("end", end).put("text", text)

        companion object {
            /** Null when the key is missing or JSON `null` — never the literal `"null"`. */
            fun fromJson(obj: JSONObject?): PromptMention? {
                if (obj == null || obj.isNull("start") || obj.isNull("end")) return null
                return PromptMention(
                    start = obj.optInt("start"),
                    end = obj.optInt("end"),
                    text = optStringOrNull(obj, "text").orEmpty(),
                )
            }
        }
    }

    /**
     * `PromptInput.FileAttachment` — `{uri, name?, description?, mention?}`
     * (`schema/src/prompt-input.ts:8-16`).
     */
    data class PromptFile(
        val uri: String,
        val name: String? = null,
        val description: String? = null,
        val mention: PromptMention? = null,
    ) {
        fun toJson(): JSONObject {
            val json = JSONObject().put("uri", uri)
            if (!name.isNullOrBlank()) json.put("name", name)
            if (!description.isNullOrBlank()) json.put("description", description)
            mention?.let { json.put("mention", it.toJson()) }
            return json
        }
    }

    /**
     * `PromptInput.AgentAttachment` — `{name, mention?}`
     * (`schema/src/prompt-input.ts:23-26`). [prompt]'s `agents` parameter takes
     * the raw JSON list, so `PromptAgent(name, mention).toJson()` is the typed
     * way in (and the only way to attach a span).
     */
    data class PromptAgent(val name: String, val mention: PromptMention? = null) {
        fun toJson(): JSONObject {
            val json = JSONObject().put("name", name)
            mention?.let { json.put("mention", it.toJson()) }
            return json
        }
    }

    /**
     * `PromptInput.SkillAttachment` — `{id, mention?}`
     * (`schema/src/prompt-input.ts:30-33`). Same entry rule as [PromptAgent].
     */
    data class PromptSkill(val id: String, val mention: PromptMention? = null) {
        fun toJson(): JSONObject {
            val json = JSONObject().put("id", id)
            mention?.let { json.put("mention", it.toJson()) }
            return json
        }
    }

    /**
     * Non-2xx response. [code] is the HTTP status; the message carries
     * `HTTP {code} [{type}] {human}` ([errorText]). [errorType] is the raw
     * `type` discriminator (`rate_limit_error`, …) when the body carried one —
     * the prompt-POST path has no error detail, so this field (not message
     * scraping) is the durable way to keep it. [rawBody] is the response body
     * capped to [RAW_BODY_CAP] chars, for troubleshooting only.
     */
    class ApiException(
        val code: Int,
        message: String,
        val errorType: String? = null,
        val rawBody: String? = null,
    ) : IOException(message) {
        /**
         * The server rejected a pagination cursor — the `_tag` the message and
         * session-list routes answer with when a `cursor` value is not a
         * server-issued token (`400 {"_tag":"InvalidCursorError", …}`).
         *
         * **Never retry this.** The cursor is built client-side, so repeating
         * the identical request reproduces the identical rejection; the fix is
         * always to stop paging and surface the failure. The tag is checked
         * against [rawBody] because [errorType] reads the payload's `type`
         * discriminator, which a `_tag`-only error body does not carry.
         */
        val isInvalidCursor: Boolean
            get() = code == 400 && rawBody?.contains("\"InvalidCursorError\"") == true
    }

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

        /** The label upstream's TUI puts in front of a server log reference. */
        private const val REF_LABEL = "Reference:"

        /** Server log references are minted `err_{uuid4[0:8]}` upstream. */
        private const val REF_PREFIX = "err_"

        /** [ApiException.rawBody] is troubleshooting context, not a body store. */
        private const val RAW_BODY_CAP = 4096

        /** REST timeouts, same shape as `OpenCodeClient`/`PtyApi`. */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        /**
         * Client for the SSE routes, on the same idle policy as the app's own
         * streams (`com.xiufeigo.opencodeshell.EventStream.sseClient`, whose
         * 35 s watchdog this mirrors).
         *
         * The read timeout is deliberately **finite**. `readTimeout(0)` was the
         * old shape here ("the stream idles between events; the server only
         * heartbeats every 10 s"), which is wrong twice over: the measured
         * heartbeat interval is 15 s (`_upstream/packages/server/src/handlers/
         * event.ts:22`, confirmed by a 62 s `curl -N /api/event` on 2.0.18/4095
         * — 5 comment lines), and an infinite read means a blackholed socket
         * never throws, so the stream is held forever and never reconnects.
         */
        fun defaultSseClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(com.xiufeigo.opencodeshell.EventStream.IDLE_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
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

/** Log tag for the transport-level warnings this file emits on its own behalf. */
private const val TAG = "V2Api"

/**
 * `JSONArray` → `List<JSONObject>`, dropping non-object elements.
 *
 * File-private top-level helpers rather than instance methods, because the
 * nested DTO companions ([V2Api.Message]'s especially) parse their own payloads
 * and cannot reach an outer instance member.
 */
internal fun v2ObjectList(array: JSONArray): List<JSONObject> =
    (0 until array.length()).mapNotNull { array.optJSONObject(it) }

/**
 * `JSONArray` → `List<String>`, dropping non-strings.
 *
 * Read through `opt`/`isNull` rather than `optString(index, fallback)`: a JSON
 * `null` element stringifies as the literal `"null"` on Android's `org.json`
 * (see [optStringOrNull]) and would then ride into a list of resources, shells or
 * capabilities as a value that never existed.
 */
internal fun v2StringList(array: JSONArray?): List<String> {
    if (array == null) return emptyList()
    return (0 until array.length()).mapNotNull { i ->
        when (val element = array.opt(i)) {
            is String -> element.takeIf { it.isNotEmpty() && it != "null" }
            else -> null
        }
    }
}

/**
 * One half of an opaque pagination cursor, or null when the server did not
 * issue one.
 *
 * `JSONObject.isNull` is the runtime-stable half of the check: it answers true
 * for a missing key and for the wire's JSON `null` on **both** `org.json` builds
 * this app sees (Android's, and the `org.json:json` jar the unit tests use),
 * whereas `optString` turns JSON `null` into the literal string `"null"` on
 * Android and into its fallback on the JVM. Reading a cursor through
 * `optString` alone is what made the device drain send `?cursor=null` and take
 * `400 InvalidCursorError` on every export while every JVM test passed.
 *
 * The `"null"`/`"undefined"` rejections are belt-and-braces for the Android
 * shape and for a server that literally serialized those tokens as strings:
 * neither is a value the route can decode, so treating them as "no cursor" is
 * always right.
 */
internal fun opaqueCursorValue(obj: JSONObject, key: String): String? {
    if (obj.isNull(key)) return null
    return obj.optString(key, "").takeIf { it.isNotEmpty() && it != "null" && it != "undefined" }
}

/**
 * An optional string field, or null when the wire did not carry one.
 *
 * Same runtime split as [opaqueCursorValue], for the non-cursor fields: Android's
 * `JSONObject.optString(name, fallback)` runs the value through `JSON.toString`,
 * so a JSON `null` becomes the **literal string `"null"`** (the `NULL` sentinel's
 * `toString()`); only a *missing* key reaches the fallback. The JVM build the
 * unit tests run on returns the fallback for `NULL`
 * (`org.json:json:20240303`), which is why the divergence only ever showed up on
 * a device (three times now: `Cursor.previous`/`next` twice, and
 * `ChatStore.errorMessageOf`).
 *
 * `isNull` is the runtime-stable half — it answers true for a missing key and for
 * JSON `null` alike on both builds — and a server-sent literal `"null"` counts as
 * absent too, since no optional field this class models has `"null"` as a value.
 * A `null` [obj] answers `null` as well, so an optional parent object
 * (`payload.metadata`, `branch`, `source`, …) needs no extra `?.let`.
 */
internal fun optStringOrNull(obj: JSONObject?, key: String): String? {
    if (obj == null || obj.isNull(key)) return null
    return obj.optString(key, "").takeIf { it.isNotBlank() && it != "null" }
}

/**
 * The captured stdout of a `type:"shell"` session message.
 *
 * On the wire `output` is the **object** `Shell.Output`
 * `{output, cursor, size, truncated}` (`schema/src/shell.ts:73-80`), so reading
 * the outer key through `optStringOrNull` would hand back the whole object's
 * JSON text as if it were the command's output. The inner `output` string is
 * the payload; the older/plain-string shape is still accepted for a hybrid
 * server. A JSON `null`, a missing key and the literal `"null"` all answer
 * `null` (see [optStringOrNull] for the two-`org.json` split).
 */
internal fun shellOutputText(obj: JSONObject?): String? {
    if (obj == null || obj.isNull("output")) return null
    return when (val raw = obj.opt("output")) {
        is String -> raw.takeIf { it.isNotEmpty() && it != "null" }
        is JSONObject -> optStringOrNull(raw, "output")
        else -> null
    }
}
