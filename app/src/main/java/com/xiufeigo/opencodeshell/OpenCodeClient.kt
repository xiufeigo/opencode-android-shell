package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.v2.V2Api
import com.xiufeigo.opencodeshell.v2.fileContentFromBytes
import com.xiufeigo.opencodeshell.v2.v2VcsDiffMode
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
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * NULL-safe string read for every `fromJson` in this file.
 *
 * org.json's `optString` coerces JSON null to the literal `"null"` (and
 * stringifies non-string values), so `optString("title", "")` on
 * `{"title":null}` yields `"null"` — which then sails through
 * `takeIf { it.isNotEmpty() }` / `ifEmpty { … }` guards and ends up on
 * screen (the header chip once printed "null"; Cursor hit the same trap).
 * [JSONObject.isNull] covers both a missing key and JSON null, and a
 * server-sent literal `"null"` string is treated as absent, matching the
 * VcsInfo precedent. Anything else keeps `optString` coercion verbatim, so
 * this is only a narrowing of the `"null"`-producing inputs.
 */
private fun JSONObject.optStr(key: String, default: String = ""): String {
    if (isNull(key)) return default
    return optString(key, default).takeIf { it != "null" } ?: default
}

/**
 * Minimal native OpenCode REST client (M1): sessions, messages, prompt, abort,
 * file search. Talks **directly** to the user's server URL (no loopback
 * gateway hop) with Basic auth mirrored from the WebView config.
 *
 * Every route targets the **v2** `/api/…` namespace. The unprefixed v1 paths
 * this client used historically answer `200` + the SPA shell (HTML, not JSON)
 * on a v2 server — measured on 2.0.11 — so requesting one is always a parse
 * crash, never data.
 */
class OpenCodeClient(
    private val baseHttpUrl: HttpUrl,
    private val username: String?,
    private val password: String?,
    private val http: OkHttpClient = defaultClient(),
) {
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    /**
     * Releases pooled connections only. Never shuts the dispatcher down: this
     * instance may wrap the ViewModel's shared OkHttpClient, whose executor
     * does not rebuild after shutdown() (in-flight prompt POSTs and the SSE
     * client share it).
     */
    fun close() {
        http.connectionPool.evictAll()
    }

    // ------------------------------------------------------------------ wire

    /**
     * `/api/<path>` on the server root. [configure] appends query parameters
     * (location scope, limit); path segments are added one by one so an id can
     * never re-root the URL.
     */
    private fun apiUrl(path: String, configure: (HttpUrl.Builder) -> Unit = {}): HttpUrl {
        val builder = baseHttpUrl.newBuilder().addPathSegment("api")
        for (seg in path.trim('/').split('/').filter { it.isNotEmpty() }) {
            builder.addPathSegment(seg)
        }
        configure(builder)
        return builder.build()
    }

    private fun apiBuilder(path: String, configure: (HttpUrl.Builder) -> Unit = {}): Request.Builder {
        val builder = Request.Builder().url(apiUrl(path, configure))
        basicAuthorization()?.let { builder.header("Authorization", it) }
        return builder
    }

    /**
     * `?location[directory]=` for the location-scoped catalog/file routes (the
     * deepObject `location` key, the only scope name the server honours).
     * Session-scoped routes take no location parameter and never call this.
     */
    private fun located(builder: HttpUrl.Builder, directory: String?) {
        if (!directory.isNullOrBlank()) builder.addQueryParameter(LOCATION_DIRECTORY_QUERY, directory)
    }

    /**
     * Basic-auth header for the configured server, or `null` when there is no
     * password to send.
     *
     * Upstream derives the credential from the **password alone**
     * (`_upstream/packages/app/src/runtime/server/api.ts:6`,
     * `authTokenFromCredentials` → `btoa("opencode:" + password)`): the username
     * is the literal `opencode`, not a user-supplied field. It also sends **no**
     * header when there is no password (`runtime/server/health.ts:83` +
     * `health.test.ts:29` expect `password ? "Basic …" : null`).
     *
     * The previous `"$username:$password"` built a literal `null:` / `:null`
     * credential whenever one half was unset (a missing config field arrives
     * here as `null`), and it always sent a header — so an unauthenticated
     * server was handed a bogus `Authorization`. [username] is kept only as a
     * compatibility fallback for a server entry that carries a non-empty one.
     */
    private fun basicAuthorization(): String? = basicAuthorizationHeader(username, password)

    /** The v2 read surface: catalogs answer `{location, data:[…]}` and session
     * reads `{data:…}` without `location`; a bare array (the v1 shape) still
     * parses so a lenient body never fails the screen. */
    private fun dataRows(body: String): List<JSONObject> {
        val trimmed = body.trim()
        if (trimmed.startsWith("{")) {
            JSONObject(trimmed).optJSONArray("data")?.let { arr ->
                return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
            }
        }
        return parseJsonArray(body)
    }

    /** `{data:{…}}`; `{}` when the envelope carries no object. */
    private fun dataObject(body: String): JSONObject =
        JSONObject(body).optJSONObject("data") ?: JSONObject()

    private fun call(request: Request): String {
        http.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                throw ApiException(response.code, "HTTP ${response.code} ${body.take(200)}")
            }
            return body
        }
    }

    private fun callBytes(request: Request): ByteArray {
        http.newCall(request).execute().use { response ->
            val bytes = response.body?.bytes() ?: ByteArray(0)
            if (!response.isSuccessful) {
                throw ApiException(response.code, "HTTP ${response.code} ${bytes.toString(Charsets.UTF_8).take(200)}")
            }
            return bytes
        }
    }

    private fun apiPost(path: String, json: JSONObject, configure: (HttpUrl.Builder) -> Unit = {}): String {
        val body: RequestBody = json.toString().toRequestBody(jsonMedia)
        return call(apiBuilder(path, configure).post(body).build())
    }

    private fun apiPatch(path: String, json: JSONObject): String {
        val body: RequestBody = json.toString().toRequestBody(jsonMedia)
        return call(apiBuilder(path).patch(body).build())
    }

    /** This client's view of the v2 API: same root, credentials and shared
     * HTTP client, so catalog reads can delegate to [V2Api]'s measured
     * methods instead of re-implementing their envelopes. */
    private fun v2(): V2Api = V2Api(baseHttpUrl, username, password, http)

    // ------------------------------------------------------------------ model

    data class Part(
        val id: String,
        val type: String,
        val text: String?,
        val toolName: String?,
        val toolStatus: String?,
        val mime: String?,
        val filename: String?,
        val url: String?,
        val raw: JSONObject,
    ) {
        companion object {
            fun fromJson(obj: JSONObject): Part = Part(
                id = optId(obj),
                type = obj.optStr("type", "unknown"),
                text = obj.optStr("text").takeIf { it.isNotEmpty() && obj.has("text") },
                toolName = obj.optStr("tool").takeIf { it.isNotEmpty() },
                toolStatus = obj.optJSONObject("state")?.optStr("status")?.takeIf { it.isNotEmpty() },
                mime = obj.optStr("mime").takeIf { it.isNotEmpty() },
                filename = obj.optStr("filename").takeIf { it.isNotEmpty() },
                url = obj.optStr("url").takeIf { it.isNotEmpty() },
                raw = obj,
            )

            private fun optId(obj: JSONObject): String =
                obj.optStr("id").ifEmpty { "part:${obj.hashCode()}" }
        }
    }

    data class Message(
        val id: String,
        val sessionID: String,
        val role: String,
        val timeCreated: Long,
        val parts: List<Part>,
        /**
         * `info.tokens` flattened to one total (M2, additive display payload):
         * `input + output + reasoning + cache.read + cache.write`, `null` when
         * the server sent no usage or all buckets are 0. Feeds the context ring
         * (`ContextUsage`); never participates in any store/merge semantics.
         */
        val tokens: Long? = null,
        /**
         * Assistant `info.providerID` / `info.modelID` (v1 `MessageV1.Assistant`,
         * `research/04` §5): the model that produced the turn. `null` on user
         * messages. The model chip's default-label fallback reads the latest
         * assistant message when `GET /config` exposes no default model.
         */
        val providerID: String? = null,
        val modelID: String? = null,
        /** `info.agent` — the agent that produced the turn (`Agent · Model` meta line). */
        val agent: String? = null,
        /** `info.time.completed`; `null` while the turn is still running. */
        val timeCompleted: Long? = null,
        /**
         * `isInterrupted(info.error)` (`session-ui/src/timeline/projection.ts:408-410`):
         * an abort/interrupt error ended this turn.
         */
        val interrupted: Boolean = false,
    ) {
        companion object {
            fun fromJson(obj: JSONObject): Message {
                val info = obj.optJSONObject("info") ?: obj
                val partsJson = obj.optJSONArray("parts") ?: info.optJSONArray("parts") ?: JSONArray()
                val parts = buildList {
                    for (i in 0 until partsJson.length()) {
                        val p = partsJson.optJSONObject(i) ?: continue
                        add(Part.fromJson(p))
                    }
                }
                val time = info.optJSONObject("time")
                // Assistant messages carry the model flat, user messages nested
                // under `model:{providerID,modelID}` (`v1/session.ts:347-351`).
                val nestedModel = info.optJSONObject("model")
                return Message(
                    id = info.optStr("id"),
                    sessionID = info.optStr("sessionID"),
                    role = info.optStr("role", "user"),
                    timeCreated = time?.optLong("created", 0L) ?: 0L,
                    parts = parts,
                    tokens = tokenTotal(info.optJSONObject("tokens")),
                    providerID = info.optStr("providerID")
                        .ifEmpty { nestedModel?.optStr("providerID").orEmpty() }
                        .takeIf { it.isNotEmpty() },
                    modelID = info.optStr("modelID")
                        .ifEmpty { nestedModel?.optStr("modelID").orEmpty() }
                        .takeIf { it.isNotEmpty() },
                    agent = info.optStr("agent").takeIf { it.isNotEmpty() },
                    timeCompleted = time?.optLong("completed", 0L)?.takeIf { it > 0L },
                    interrupted = ChatStore.isInterrupted(info.optJSONObject("error")),
                )
            }

            /**
             * v2 `GET /api/session/:id/message` row: a flat tagged union on
             * `type` (`user` | `assistant` | `system` | `shell` | …), not v1's
             * `{info, parts}` pair. [sessionID] comes from the request — the
             * row carries none. Rows of unknown type keep their type as the
             * role and whatever text/content they carry, so nothing is dropped.
             */
            fun fromV2Json(obj: JSONObject, sessionID: String): Message {
                val type = obj.optStr("type", "unknown")
                val time = obj.optJSONObject("time")
                val model = obj.optJSONObject("model")
                val content = obj.optJSONArray("content")
                val parts = when {
                    content != null -> buildList {
                        for (i in 0 until content.length()) {
                            content.optJSONObject(i)?.let { add(Part.fromJson(it)) }
                        }
                    }
                    obj.has("text") -> listOf(
                        Part(
                            id = "text:${obj.optStr("id")}",
                            type = "text",
                            text = obj.optStr("text").takeIf { it.isNotEmpty() },
                            toolName = null,
                            toolStatus = null,
                            mime = null,
                            filename = null,
                            url = null,
                            raw = obj,
                        ),
                    )
                    else -> emptyList()
                }
                return Message(
                    id = obj.optStr("id"),
                    sessionID = sessionID,
                    role = when (type) {
                        "user" -> "user"
                        "assistant" -> "assistant"
                        else -> type
                    },
                    timeCreated = time?.optLong("created", 0L) ?: 0L,
                    parts = parts,
                    tokens = tokenTotal(obj.optJSONObject("tokens")),
                    providerID = model?.optStr("providerID")?.takeIf { it.isNotEmpty() },
                    modelID = (model?.optStr("id")?.takeIf { it.isNotEmpty() }
                        ?: model?.optStr("modelID")?.takeIf { it.isNotEmpty() }),
                    agent = obj.optStr("agent").takeIf { it.isNotEmpty() },
                    timeCompleted = time?.optLong("completed", 0L)?.takeIf { it > 0L },
                    interrupted = ChatStore.isInterrupted(obj.optJSONObject("error")),
                )
            }
        }
    }

    data class Session(
        val id: String,
        val title: String,
        val directory: String?,
        val updatedAt: Long,
        /**
         * `Session.Info.cost` (`_upstream/packages/schema/src/v1/session.ts:552`)
         * — the session's accumulated spend, shown in the context-ring tooltip
         * (`context.usage.cost`). `null` when the server omits it.
         */
        val cost: Double? = null,
        /**
         * W6: `Session.Info.share.url` — the public link `POST
         * /session/:id/share` publishes and `DELETE /session/:id/share` is
         * supposed to drop. `null` while the session is private.
         *
         * Measured against opencode 1.18.31 (see `sessionShare` /
         * `sessionUnshare`): the DELETE answers `200` **and keeps the `share`
         * object** in the returned payload and in every later `GET
         * /session/:id`, so the toggle cannot be driven by this field alone —
         * `ChatViewModel` remembers a successful unshare locally.
         */
        val shareUrl: String? = null,
        /**
         * W6: `Session.Info.revert.messageID` — the message the session was
         * reverted to (`POST /session/:id/revert`). Every message with a
         * lexicographically greater-or-equal id is hidden from the timeline
         * (`_upstream/packages/app/src/session/session-domain.ts:16-18`,
         * `timeline/controller-projection.ts:25`). `null` = nothing reverted.
         */
        val revertMessageID: String? = null,
        /**
         * W6: `Session.Info.time.archived` as the server stored it, `null` when
         * the key is absent. The **archive predicate is `> 0`**, not "present":
         * measured on 1.18.31, the only way to clear an archive is
         * `PATCH {"time":{"archived":0}}`, which writes the field as `0` (a
         * `null` is ignored) — presence-based filtering (upstream's rule) would
         * hide the session forever. See `HomeSessions.isArchived`.
         */
        val archivedAt: Long? = null,
        /**
         * v54 P1 「子路径会话在 Home 列表与搜索里双双缺失」: the session's
         * **subpath** inside its `location.directory` (`SessionV2Info.subpath` —
         * measured rows carry `location.directory=/repo` with
         * `subpath=app/src`). A window bound to a subpath creates its sessions
         * there, so the server answers with the **project root** as
         * [directory] and the subpath here — which used to be dropped on the
         * way in ([ChatViewModel.toUiSession]), leaving Home to match a session
         * whose real home is `<root>/app/src` against a scope that only knows
         * `<root>/app/src`: the row and its search hit both disappeared
         * (`research/v54_verify/tB/tB_50_home.png`, `tB_82_home_search.png`).
         *
         * Null/blank when the server reports none (every v1 row, and a v2
         * session created at the project root).
         */
        val subpath: String? = null,
    ) {
        companion object {
            /**
             * v1 `Session.Info` (`{id,title,directory,time{updated},…}`) and the
             * v2 row (`{id,title,location{directory},time{updated},…}` — v2 nests
             * the workspace under `location` and carries no `share` object).
             */
            fun fromJson(obj: JSONObject): Session = Session(
                id = obj.optStr("id"),
                title = obj.optStr("title").ifEmpty { "Untitled" },
                // Same org.json trap as VcsInfo: literal "null" is not a path,
                // and `optString` on an object returns its `toString` — read
                // type-strictly so a nested `location` object never leaks in.
                directory = (obj.opt("directory") as? String)
                    ?.takeIf { it.isNotEmpty() && it != "null" }
                    ?: (obj.optJSONObject("location")?.opt("directory") as? String)
                        ?.takeIf { it.isNotEmpty() && it != "null" },
                updatedAt = obj.optJSONObject("time")?.optLong("updated", 0L) ?: 0L,
                cost = obj.optDouble("cost", Double.NaN).takeIf { !it.isNaN() },
                shareUrl = obj.optJSONObject("share")?.optStr("url")
                    ?.takeIf { it.isNotEmpty() },
                revertMessageID = obj.optJSONObject("revert")?.optStr("messageID")
                    ?.takeIf { it.isNotEmpty() },
                // `optLong` cannot distinguish "absent" from `0`, and the two
                // mean different things here (never archived vs. unarchived).
                archivedAt = obj.optJSONObject("time")
                    ?.takeIf { it.has("archived") }
                    ?.optLong("archived"),
            )
        }
    }

    class ApiException(val code: Int, message: String) : IOException(message)

    /**
     * `GET /api/project` entry: v2 answers a bare array of
     * `{id,canonical,name,time,sandboxes,icon?}` — the workspace moved from v1's
     * `worktree` key to `canonical` (read as the fallback, mirroring
     * `v2ProjectRow`). `icon` is optional on both shapes and carries
     * `{url,override,color}` (`schema/src/project.ts:20-25`), of which this DTO
     * keeps the two the shell can draw: [iconColor] and [iconOverride].
     *
     * The v1 shape (`{id,worktree,vcs,icon,…}`) still parses: entries without a
     * usable worktree are dropped — they can never be scoped to a session.
     */
    data class Project(
        val id: String,
        val worktree: String,
        val vcs: String?,
        val iconColor: String?,
        /**
         * `project.sandboxes` — the extra directories that belong to the
         * project, so its session scope is `[worktree, ...sandboxes]`
         * (`_upstream/packages/app/src/home/projects/controller.tsx:41-43`).
         *
         * Measured live (1.18.31): present on a worktree registered from a
         * `.worktrees/<branch>` checkout, where it names the parent worktree,
         * and `[]` on every other project. Absent, `null`, `[]` and an entry
         * that is not a string all read as "no sandboxes" instead of throwing —
         * the DTO must not make Home's read fail over an extra field.
         */
        val sandboxes: List<String> = emptyList(),
        /**
         * The display name from `GET /api/project` (`name`): Home's projects
         * column and the new-session draft's picker read this instead of
         * deriving a second source. Absent or JSON null reads as `""`
         * (unknown) — never the literal `"null"`. Appended last (with a
         * default) so every existing named/positional construction compiles
         * unchanged.
         */
        val name: String = "",
        /**
         * `project.icon.override` — the custom icon image as the `data:` URL the
         * editor PATCHes (`HomeProjectEdit` / `ProjectIconImage`). Null when the
         * project has none, when the field is JSON null, or when the value is
         * the literal `"null"` `org.json` hands back for a null (`optStr`).
         * The avatar draws this before [iconColor].
         */
        val iconOverride: String? = null,
    ) {
        companion object {
            fun fromJson(obj: JSONObject): Project? {
                val worktree = obj.optStr("worktree").trim()
                    .ifEmpty { obj.optStr("canonical").trim() }
                if (worktree.isEmpty()) return null
                return Project(
                    id = obj.optStr("id"),
                    worktree = worktree,
                    vcs = obj.optStr("vcs").takeIf { it.isNotEmpty() },
                    iconColor = obj.optJSONObject("icon")
                        ?.optStr("color")
                        ?.takeIf { it.isNotEmpty() },
                    sandboxes = stringList(obj.optJSONArray("sandboxes")),
                    name = obj.optStr("name"),
                    iconOverride = obj.optJSONObject("icon")
                        ?.optStr("override")
                        ?.takeIf { it.isNotEmpty() },
                )
            }

            /**
             * `sandboxes` as a list of usable directories: only string entries
             * survive, blanks and edges are trimmed away, and a non-array value
             * (`optJSONArray` hands back `null` for anything else, including
             * literal `"null"`) is the empty list.
             */
            private fun stringList(arr: JSONArray?): List<String> {
                if (arr == null) return emptyList()
                return (0 until arr.length())
                    .mapNotNull { index -> (arr.opt(index) as? String)?.trim()?.takeIf { it.isNotEmpty() } }
            }
        }
    }

    /**
     * One `GET /api/fs/list` entry: `{path,type}` with the path **relative**
     * to the request's location directory (v1's `GET /file?path=` answered
     * absolute `{name,path,absolute,…}` nodes). [absolute] is the location
     * directory joined onto the relative path; [ignored] has no v2 counterpart
     * and stays false.
     */
    data class FileNode(
        val name: String,
        val path: String,
        val absolute: String,
        val directory: Boolean,
        val ignored: Boolean,
    ) {
        companion object {
            fun fromJson(obj: JSONObject): FileNode? {
                val absolute = obj.optStr("absolute").trim()
                    .ifEmpty { obj.optStr("path").trim() }
                if (absolute.isEmpty()) return null
                return FileNode(
                    name = obj.optStr("name"),
                    path = obj.optStr("path"),
                    absolute = absolute,
                    directory = obj.optString("type", "") == "directory",
                    ignored = obj.optBoolean("ignored", false),
                )
            }

            /**
             * v2 `fs/list` row (`{path` relative `,type}`) against [base], the
             * response's `location.directory`. The join keeps the base's own
             * separator so a Windows worktree stays a Windows path.
             */
            fun fromV2Json(obj: JSONObject, base: String): FileNode? {
                val rel = obj.optStr("path").trim().trim('/', '\\')
                if (rel.isEmpty()) return null
                val absolute = if (base.isEmpty()) {
                    rel
                } else {
                    val sep = if (base.contains('\\')) '\\' else '/'
                    base.trimEnd('\\', '/') + sep + rel.map { c ->
                        if (c == '/' || c == '\\') sep else c
                    }.joinToString("")
                }
                return FileNode(
                    name = rel.trimEnd('/', '\\').split('/', '\\').lastOrNull().orEmpty(),
                    path = rel,
                    absolute = absolute,
                    directory = obj.optString("type", "") == "directory",
                    ignored = false,
                )
            }
        }
    }

    /**
     * One pending permission prompt. v1 (`GET /permission`) names the fields
     * `permission`/`patterns`/`always` with the tool under
     * `tool:{messageID,callID}`; v2 (`GET /api/permission/request`) names them
     * `action`/`resources`/`save` with the tool under
     * `source:{type:"tool",messageID,id}` — the v2 source keys its call id `id`
     * (`schema/src/permission.ts` `Permission.Source`), which is why [Tool]
     * reads both. Both shapes parse into this one DTO.
     */
    data class PermissionRequest(
        val id: String,
        val sessionID: String,
        val permission: String,
        val patterns: List<String>,
        val always: List<String>,
        val tool: Tool?,
        val metadata: JSONObject?,
    ) {
        data class Tool(val messageID: String, val callID: String) {
            companion object {
                /**
                 * `id` wins over `callID`: the v2 source object carries the call
                 * id under `id`, a v1 `tool` (and a hybrid v2 payload) under
                 * `callID`. Same dual-key read as `V2Api.PermissionRequest`
                 * and `V2EventAdapter.v2Permission`.
                 */
                fun fromJson(obj: JSONObject): Tool = Tool(
                    messageID = obj.optStr("messageID"),
                    callID = obj.optStr("id").ifEmpty { obj.optStr("callID") },
                )
            }
        }

        companion object {
            /** Lenient: missing fields fall back to empty lists/nulls, v1 or v2 key names. */
            fun fromJson(obj: JSONObject): PermissionRequest {
                val source = obj.optJSONObject("source")
                val toolSource = source?.takeIf { it.optString("type", "") == "tool" }
                return PermissionRequest(
                    id = obj.optStr("id"),
                    sessionID = obj.optStr("sessionID"),
                    permission = obj.optStr("permission")
                        .ifEmpty { obj.optStr("action") },
                    patterns = stringList(obj.optJSONArray("patterns") ?: obj.optJSONArray("resources")),
                    always = stringList(obj.optJSONArray("always") ?: obj.optJSONArray("save")),
                    tool = obj.optJSONObject("tool")?.let(Tool::fromJson)
                        ?: toolSource?.let(Tool::fromJson),
                    metadata = obj.optJSONObject("metadata"),
                )
            }

            private fun stringList(arr: JSONArray?): List<String> {
                if (arr == null) return emptyList()
                return (0 until arr.length()).mapNotNull { i ->
                    (arr.opt(i) as? String)?.takeIf { it.isNotEmpty() && it != "null" }
                }
            }
        }
    }

    /**
     * The provider/model catalog behind the composer's model picker.
     *
     * v1 served it as one `GET /provider` document
     * (`{all, default, connected}` with `models` as a `Record<modelID,
     * Model>`); v2 splits provider and model into `GET /api/provider` + `GET
     * /api/model` (+ `GET /api/integration` for the connected set) and
     * [providers] folds the three reads back into this shape. Both document
     * shapes parse here.
     *
     * Parsing is deliberately lenient: the catalog only feeds the composer's
     * model picker, so a missing/extra field degrades to a fallback instead of
     * failing the screen.
     */
    data class Providers(
        val all: List<Provider>,
        val connected: List<String>,
        val default: Map<String, String>,
    ) {
        data class Provider(
            val id: String,
            val name: String,
            val models: List<Model>,
        ) {
            companion object {
                /** [fallbackId] = the map key when the provider list came as an object. */
                fun fromJson(obj: JSONObject, fallbackId: String? = null): Provider? {
                    val id = obj.optStr("id").ifEmpty { fallbackId.orEmpty() }
                    if (id.isEmpty()) return null
                    return Provider(
                        id = id,
                        name = obj.optStr("name").ifEmpty { id },
                        models = parseModels(obj.opt("models")),
                    )
                }

                private fun parseModels(raw: Any?): List<Model> = when (raw) {
                    is JSONObject -> {
                        val keys = raw.keys()
                        val out = ArrayList<Model>(raw.length())
                        while (keys.hasNext()) {
                            val key = keys.next()
                            val entry = raw.optJSONObject(key) ?: continue
                            Model.fromJson(entry, fallbackId = key)?.let(out::add)
                        }
                        out
                    }

                    is JSONArray -> (0 until raw.length()).mapNotNull { i ->
                        raw.optJSONObject(i)?.let { Model.fromJson(it) }
                    }

                    else -> emptyList()
                }
            }
        }

        data class Model(
            val id: String,
            val name: String,
            val capabilities: Capabilities?,
            /**
             * `model.limit.context` — the context window the server advertises
             * for this model. The context ring needs it as its denominator;
             * without it the ring can only draw an empty track.
             *
             * v20: the ring's denominator is the entry's **effective window**
             * `min(limit.input, limit.context)` (`ContextUsage.windowOf`), not
             * this raw value — the server compacts on the input half
             * (`_upstream/packages/core/src/session/compaction.ts:742-763`). See
             * [limitInput].
             */
            val limitContext: Int? = null,
            /**
             * `model.limit.input` — the **prompt ceiling** of this entry, the
             * half the server's own compaction trigger subtracts its buffer from
             * (`min(limit.input - buffer, context - max(output, buffer))`). It is
             * **optional on the wire** (measured on the live v20 catalog: 74 of
             * 129 entries omit it, 4 report `0` — a placeholder, not a window),
             * so `null` means "no separate input ceiling" and consumers fall back
             * to [limitContext] alone. `ContextUsage.windowOf` is the one place
             * that reading lives.
             *
             * Folded here rather than in a side table keyed by model ref: the
             * value belongs to the same catalog entry as [limitContext] /
             * [limitOutput], and a separate map could be read half-applied (a
             * ring dividing one model's context while holding another's input
             * ceiling).
             */
            val limitInput: Int? = null,
            /** `model.limit.output` — advertised output ceiling, if any. */
            val limitOutput: Int? = null,
            /**
             * W5-C B4: the **keys** of `model.variants`
             * (openapi `Model.variants` = `Record<string, object>`, verified
             * against opencode 1.18.31: `openai/gpt-5.6-sol-fast` advertises
             * `none|low|medium|high|xhigh|max`). Upstream reads the same keys as
             * the composer's reasoning-effort options
             * (`_upstream/packages/app/src/providers/models/variant.ts:11-13,31-35`).
             * An array of strings is tolerated as well; empty = "no variants".
             */
            val variants: List<String> = emptyList(),
            /**
             * The **catalog entry** id — the wire `Model.Info.id`. A provider's
             * catalog may hold several entries under one [id] (measured on the
             * live v2 backend: `openai` ships `gpt-5.6`, `gpt-5.6-fast` and
             * `gpt-5.6-pro` as three entries that all report
             * `modelID: "gpt-5.6"`, differing only in `body.serviceTier` /
             * `body.reasoning.mode`), so [id] alone is **not** a row identity.
             * This is the id the server matches a session's `Model.Ref.id`
             * against (`_upstream/packages/core/src/session/runner/model.ts`:
             * `available().find(model => model.providerID === ref.providerID &&
             * model.id === ref.id)`), and what upstream's app keys its picker
             * rows by (`providers/models/selection.tsx`: `{providerID, modelID:
             * model.id}`). Falls back to [id] for v1 documents, whose model maps
             * carry no separate entry id.
             */
            val entryID: String = id,
        ) {
            companion object {
                /** [fallbackId] = the map key when `models` came as an object. */
                fun fromJson(obj: JSONObject, fallbackId: String? = null): Model? {
                    val id = obj.optStr("id").ifEmpty { fallbackId.orEmpty() }
                    if (id.isEmpty()) return null
                    val limit = obj.optJSONObject("limit")
                    return Model(
                        id = id,
                        name = obj.optStr("name").ifEmpty { id },
                        capabilities = obj.optJSONObject("capabilities")?.let(Capabilities::fromJson),
                        limitContext = limit?.optInt("context", 0)?.takeIf { it > 0 },
                        limitInput = limit?.optInt("input", 0)?.takeIf { it > 0 },
                        limitOutput = limit?.optInt("output", 0)?.takeIf { it > 0 },
                        variants = variantKeys(obj.opt("variants")),
                    )
                }

                /**
                 * v2 fold of one `GET /api/model` row: `Model.Info.modelID` →
                 * [id], `Model.Info.id` → [entryID]. Both halves matter — see
                 * [entryID] for why a fold that keeps only `modelID` collapses
                 * `gpt-5.6`/`gpt-5.6-fast`/`gpt-5.6-pro` into one row and sends
                 * the base entry's ref whichever one the user tapped.
                 *
                 * `limit.input` is read off the row's own [V2Api.ModelInfo.raw]
                 * document: `V2Api.ModelInfo` folds `limit.context` /
                 * `limit.output` into fields but has no input half, and the raw
                 * `JSONObject` is right here — the same "parse it where the
                 * document is in hand" rule [fromJson] follows.
                 */
                fun fromV2(info: V2Api.ModelInfo): Model = Model(
                    id = info.modelID,
                    name = info.name,
                    capabilities = Capabilities(
                        reasoning = null,
                        attachment = null,
                        toolCall = info.tools,
                    ),
                    limitContext = info.limitContext,
                    limitInput = info.raw.optJSONObject("limit")
                        ?.optInt("input", 0)
                        ?.takeIf { it > 0 },
                    limitOutput = info.limitOutput,
                    variants = info.variants,
                    entryID = info.id.ifEmpty { info.modelID },
                )

                /**
                 * v1's `Record<variant, object>`; v2's `[{id,…}]` array (the
                 * object form is reduced to its `id`); a plain string array is
                 * accepted for forward compatibility. Order is the server's own
                 * order, so the picker shows `none, low, …` the way the catalog
                 * declares them.
                 */
                private fun variantKeys(raw: Any?): List<String> = when (raw) {
                    is JSONObject -> {
                        val keys = raw.keys()
                        val out = ArrayList<String>(raw.length())
                        while (keys.hasNext()) {
                            val key = keys.next()
                            if (key.isNotEmpty()) out.add(key)
                        }
                        out
                    }

                    is JSONArray -> (0 until raw.length()).mapNotNull { i ->
                        when (val element = raw.opt(i)) {
                            is JSONObject -> element.optStr("id").takeIf { it.isNotEmpty() }
                            is String -> element.takeIf { it.isNotEmpty() && it != "null" }
                            else -> null
                        }
                    }

                    else -> emptyList()
                }
            }
        }

        /**
         * Capability flags the picker may surface later. v1 uses `toolcall`,
         * the v2 schema (`_upstream/packages/schema/src/model.ts`
         * `Model.Capabilities`) uses `tools` — both map onto [toolCall]. Absent
         * keys stay null ("unknown"), never a fabricated false.
         */
        data class Capabilities(
            val reasoning: Boolean?,
            val attachment: Boolean?,
            val toolCall: Boolean?,
        ) {
            companion object {
                fun fromJson(obj: JSONObject): Capabilities = Capabilities(
                    reasoning = if (obj.has("reasoning")) obj.optBoolean("reasoning") else null,
                    attachment = if (obj.has("attachment")) obj.optBoolean("attachment") else null,
                    toolCall = when {
                        obj.has("toolcall") -> obj.optBoolean("toolcall")
                        obj.has("tools") -> obj.optBoolean("tools")
                        else -> null
                    },
                )
            }
        }

        /** The picker's catalog: `all` filtered to `connected`, in catalog order. */
        fun connectedProviders(): List<Provider> {
            if (connected.isEmpty()) return emptyList()
            val connectedSet = connected.toSet()
            return all.filter { it.id in connectedSet }
        }

        /**
         * The entry a session/model ref points at, by its **ref id**: the
         * catalog [Model.entryID] first (that is what the picker, the persisted
         * override and a session's `Model.Ref.id` carry), falling back to the
         * wire [Model.id] so a ref that names a plain `modelID` still resolves —
         * legacy overrides saved before entry ids were kept, and the message /
         * config strings that predate them. The fallback lands on the entry the
         * catalog lists first for that `modelID`, which for every duplicated
         * group is the base entry (`gpt-5.6`, not `-fast`/`-pro`).
         */
        fun model(providerID: String, refID: String): Model? {
            val models = all.firstOrNull { it.id == providerID }?.models ?: return null
            return models.firstOrNull { it.entryID == refID }
                ?: models.firstOrNull { it.id == refID }
        }

        companion object {
            fun fromJson(obj: JSONObject): Providers {
                val all = parseProviders(obj.opt("all"))
                val connectedRaw = obj.optJSONArray("connected")
                val connected = if (connectedRaw == null) {
                    emptyList()
                } else {
                    (0 until connectedRaw.length()).mapNotNull { i ->
                        (connectedRaw.opt(i) as? String)?.takeIf { it.isNotEmpty() && it != "null" }
                    }
                }
                val defaultRaw = obj.optJSONObject("default")
                val default = LinkedHashMap<String, String>()
                if (defaultRaw != null) {
                    val keys = defaultRaw.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val value = defaultRaw.optStr(key)
                        if (key.isNotEmpty() && value.isNotEmpty()) default[key] = value
                    }
                }
                return Providers(all, connected, default)
            }

            /**
             * `all` is a JSON **array** on the v1 wire (whose `/provider`
             * answered `{"all":[{id,name,models,…},…]}`). An `id → Provider`
             * map is tolerated as well (the map key is then the provider id),
             * mirroring how `Provider.models` arrives as a `Record<modelID,
             * Model>`.
             */
            private fun parseProviders(raw: Any?): List<Provider> = when (raw) {
                is JSONArray -> (0 until raw.length()).mapNotNull { i ->
                    raw.optJSONObject(i)?.let { entry -> Provider.fromJson(entry) }
                }

                is JSONObject -> {
                    val keys = raw.keys()
                    val out = ArrayList<Provider>(raw.length())
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val entry = raw.optJSONObject(key) ?: continue
                        Provider.fromJson(entry, fallbackId = key)?.let { out.add(it) }
                    }
                    out
                }

                else -> emptyList()
            }
        }
    }

    /**
     * `Snapshot.FileDiff` (`research/04` §3.1, `packages/schema/src/file-diff.ts`):
     * one changed file of a session. `file`/`patch` are optional on the wire
     * (deleted entries may lack a patch, some projections omit the path), so
     * both stay nullable; `status` ∈ "added" | "deleted" | "modified".
     */
    data class FileDiff(
        val file: String?,
        val patch: String?,
        val additions: Int,
        val deletions: Int,
        val status: String?,
    ) {
        companion object {
            fun fromJson(obj: JSONObject): FileDiff = FileDiff(
                file = obj.optStr("file").takeIf { it.isNotEmpty() },
                patch = obj.optStr("patch").takeIf { it.isNotEmpty() },
                additions = obj.optInt("additions", 0),
                deletions = obj.optInt("deletions", 0),
                status = obj.optStr("status").takeIf { it.isNotEmpty() },
            )

            fun listFromJson(arr: JSONArray): List<FileDiff> =
                (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONObject(i)?.let(::fromJson)
                }
        }
    }

    /**
     * `Todo.Info` (`packages/schema/src/session-todo.ts`): one session todo.
     * Read-only on Android (the todo UI is a separate workflow); parsing is
     * lenient — all three fields default to empty strings.
     */
    data class TodoInfo(
        val content: String,
        val status: String,
        val priority: String,
    ) {
        companion object {
            fun fromJson(obj: JSONObject): TodoInfo = TodoInfo(
                content = obj.optStr("content"),
                status = obj.optStr("status"),
                priority = obj.optStr("priority"),
            )

            fun listFromJson(arr: JSONArray): List<TodoInfo> =
                (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONObject(i)?.let(::fromJson)
                }
        }
    }

    /**
     * The bytes of one `GET /api/fs/read/<rel>` response, folded back into
     * the `FileContent` shape via [fileContentFromBytes]: any NUL byte ⇒
     * binary (base64 content, `encoding="base64"`), otherwise UTF-8 text.
     * `diff`/`patch` have no v2 file-read counterpart and stay null.
     */
    data class FileContent(
        val binary: Boolean,
        val content: String,
        val diff: String?,
        val patch: String?,
        val encoding: String?,
        val mimeType: String?,
    ) {
        companion object {
            fun fromJson(obj: JSONObject): FileContent = FileContent(
                binary = obj.optString("type", "") == "binary",
                content = obj.optStr("content"),
                diff = obj.optStr("diff").takeIf { it.isNotEmpty() },
                patch = when (val raw = obj.opt("patch")) {
                    is String -> raw.takeIf { it.isNotEmpty() }
                    is JSONObject -> raw.toString()
                    else -> null
                },
                encoding = obj.optStr("encoding").takeIf { it.isNotEmpty() },
                mimeType = obj.optStr("mimeType").takeIf { it.isNotEmpty() },
            )
        }
    }

    /** v2 `GET /api/vcs` → `{location, data:{branch:{current?,default?}}}`
     * (v1's flat `{branch, default_branch}` strings still parse — but only
     * when they really are strings: `optString` on an object returns its
     * `toString`, so the flat keys are read type-strictly). */
    data class VcsInfo(
        val branch: String?,
        val defaultBranch: String?,
    ) {
        companion object {
            fun fromJson(obj: JSONObject): VcsInfo {
                val nested = obj.optJSONObject("branch")
                return VcsInfo(
                    // `optStr` (file-top) already maps JSON null and the
                    // literal "null" to "" — filter it or the header chip
                    // prints "null".
                    branch = (obj.opt("branch") as? String)
                        ?.takeIf { it.isNotEmpty() && it != "null" }
                        ?: nested?.optStr("current")?.takeIf { it.isNotEmpty() },
                    defaultBranch = (obj.opt("default_branch") as? String)
                        ?.takeIf { it.isNotEmpty() && it != "null" }
                        ?: nested?.optStr("default")?.takeIf { it.isNotEmpty() },
                )
            }
        }
    }

    /**
     * One `GET /api/agent` entry: `{id,name,description,mode,hidden,…}`. v2
     * adds `mode == "all"` (usable as a session agent alongside `"primary"`,
     * mirroring `V2Api.AgentInfo.selectable`); `selectable` keeps the
     * composer's rule — named, runnable, not hidden, in server order.
     */
    data class Agent(
        val name: String,
        val description: String,
        val mode: String,
        /**
         * `agent.native` **as the server declared it** — `null` when the wire
         * carried no `native` key at all (v2's normalized rows never do).
         *
         * B-9 made this tri-state: upstream's `hasCustomAgent`
         * (`providers/models/agent.ts:1-3`) is `native === false`, so "absent"
         * must not read as "custom" — a payload without the key would otherwise
         * light up the agent picker on every server.
         */
        val native: Boolean? = null,
        val hidden: Boolean,
        /** `agent.variant` — the reasoning effort the agent configures. */
        val variant: String? = null,
        /** `agent.model` when the agent pins a model (nested `{providerID,modelID}`). */
        val providerID: String? = null,
        val modelID: String? = null,
    ) {
        /** `mode == "primary"` (or v2's `"all"`) — the agents a session can run. */
        val primary: Boolean get() = mode == "primary" || mode == "all"

        companion object {
            fun fromJson(obj: JSONObject): Agent {
                val model = obj.optJSONObject("model")
                return Agent(
                    name = obj.optStr("name").ifEmpty { obj.optStr("id") },
                    description = obj.optStr("description"),
                    mode = obj.optStr("mode"),
                    // Optional on the wire, and the two meanings are different:
                    // absent = "unclassified" (never a fabricated `true`, and
                    // never the `false` that would read as a custom agent).
                    native = if (obj.has("native")) obj.optBoolean("native") else null,
                    hidden = obj.optBoolean("hidden", false),
                    variant = obj.optStr("variant").takeIf { it.isNotEmpty() },
                    providerID = model?.optStr("providerID")?.takeIf { it.isNotEmpty() },
                    modelID = model?.optStr("modelID")?.takeIf { it.isNotEmpty() },
                )
            }

            /** The composer's list: named, primary, not hidden, in server order. */
            fun selectable(agents: List<Agent>): List<Agent> =
                agents.filter { it.name.isNotEmpty() && it.primary && !it.hidden }

            /**
             * The agent a session runs when the user pinned none: the first
             * selectable entry (opencode lists `build` first), else `"build"` —
             * the value `POST /session/:id/shell` requires (openapi
             * `session.shell` requestBody `required: [agent, command]`).
             */
            fun defaultName(agents: List<Agent>): String =
                selectable(agents).firstOrNull()?.name ?: FALLBACK_AGENT

            /**
             * B-9: upstream `hasCustomAgent` (`providers/models/agent.ts:1-3`) —
             * an agent the server **explicitly** reports as not native
             * (`native === false`). A built-in (`true`) and an unclassified
             * entry (`null`, the key absent) both count as "no custom agent",
             * exactly like upstream's `native?: boolean`.
             */
            fun hasCustomAgent(agents: List<Agent>): Boolean = agents.any { it.native == false }

            /**
             * B-9: upstream `agentsVisible` (`providers/models/selection.tsx:94`)
             * — `settings.general.showCustomAgents` **or** a server that really
             * has a custom agent. False = the composer hides the agent control
             * and runs the session's own agent (upstream's `"build"` fallback).
             */
            fun visible(agents: List<Agent>, showCustomAgents: Boolean): Boolean =
                showCustomAgents || hasCustomAgent(agents)

            const val FALLBACK_AGENT = "build"
        }
    }

    /**
     * One `GET /api/command` entry: `{name, description}` (v2 carries no
     * `template`/`source`, so client-side template expansion stays v1-only).
     * `template` is the prompt the command expands to on a v1 server;
     * `$ARGUMENTS` inside it is the placeholder for the typed arguments.
     */
    data class CommandInfo(
        val name: String,
        val description: String,
        val source: String,
        val template: String,
    ) {
        companion object {
            fun fromJson(obj: JSONObject): CommandInfo? {
                val name = obj.optStr("name").trim()
                if (name.isEmpty()) return null
                return CommandInfo(
                    name = name,
                    description = obj.optStr("description"),
                    source = obj.optStr("source"),
                    template = obj.optStr("template"),
                )
            }
        }
    }

    /**
     * One `GET /api/skill` entry: `{id,name,description,path,…}` (v1's
     * `location` key is `path` here). Only name/description are read — the
     * `@`-mention popover lists skills, it never sends their body.
     */
    data class SkillInfo(
        val name: String,
        val description: String,
        val location: String? = null,
    ) {
        companion object {
            fun fromJson(obj: JSONObject): SkillInfo? {
                val name = obj.optStr("name").trim()
                if (name.isEmpty()) return null
                return SkillInfo(
                    name = name,
                    description = obj.optStr("description"),
                    location = (obj.opt("path") as? String)?.takeIf { it.isNotEmpty() }
                        ?: (obj.opt("location") as? String)?.takeIf { it.isNotEmpty() },
                )
            }
        }
    }

    // ------------------------------------------------------------------ api

    /**
     * `GET /api/session` → `{data:[SessionInfo…]}`. [directory] is the plain
     * `directory` filter (not the location scope); [limit] caps the page,
     * newest first. `null` keeps the previous full-list behaviour, so every
     * existing caller compiles and behaves unchanged.
     */
    fun listSessions(directory: String? = null, limit: Int? = null): List<Session> {
        val body = call(
            apiBuilder("session") {
                if (!directory.isNullOrBlank()) it.addQueryParameter("directory", directory)
                if (limit != null && limit > 0) it.addQueryParameter("limit", limit.toString())
            }.get().build(),
        )
        return dataRows(body).map(Session::fromJson)
    }

    /**
     * `GET /api/config` — the server's config documents (a bare array of
     * `{type,path,info}`, not an object). Returns the first document's `info`
     * (the JSON config itself); v1's top-level `model` key is overlaid from
     * `GET /api/model/default` when the server names one, so the composer
     * chip's default-model read keeps working. A missing default degrades to
     * "no model key", never a failure.
     */
    fun config(): JSONObject {
        val info = parseJsonArray(call(apiBuilder("config").get().build()))
            .firstOrNull()?.optJSONObject("info") ?: JSONObject()
        val out = JSONObject(info.toString())
        try {
            val def = dataObject(call(apiBuilder("model/default").get().build()))
            val providerID = def.optStr("providerID")
            val id = def.optStr("modelID").ifEmpty { def.optStr("id") }
            if (providerID.isNotEmpty() && id.isNotEmpty()) out.put("model", "$providerID/$id")
        } catch (_: Exception) {
            // No named default on this server — the chip falls back to the
            // session-derived model, same as a v1 config without `model`.
        }
        return out
    }

    /**
     * The provider/model catalog behind the composer model picker. v2 splits
     * v1's `GET /provider` into three location-scoped reads — `GET
     * /api/provider` + `GET /api/model` + `GET /api/integration` — folded back
     * into the `Providers` shape the picker already renders. `connected` =
     * providers whose linked integration reports a stored connection (via
     * [v2ConnectedProviderIds], one list read, no per-integration fan-out);
     * `default` is empty (v2 names no per-provider default); model entries
     * keep `limit.context` / `limit.input` for the context ring, variant ids for
     * the effort picker and the entry-level id for row identity
     * ([Providers.Model.fromV2]).
     * The three reads delegate to [V2Api]'s measured methods; only the
     * fold lives here. Return type and callers are unchanged.
     */
    fun providers(directory: String? = null): Providers {
        val api = v2()
        try {
            val providerRows = api.providers(directory)
            val modelRows = api.models(directory)
            if (providerRows.isEmpty()) return Providers(emptyList(), emptyList(), emptyMap())
            val connectedIds = v2ConnectedProviderIds(providerRows, api.integrations(directory))
            val all = providerRows.map { p ->
                Providers.Provider(
                    id = p.id,
                    name = p.name,
                    models = modelRows.filter { it.providerID == p.id }
                        .map(Providers.Model::fromV2),
                )
            }
            return Providers(
                all = all,
                connected = all.filter { it.id in connectedIds }.map { it.id },
                default = emptyMap(),
            )
        } catch (e: V2Api.ApiException) {
            throw ApiException(e.code, e.message ?: "HTTP ${e.code}")
        }
    }

    /**
     * `POST /api/session` `{title?, location{directory}?}` →
     * `{data:SessionInfo}`.
     */
    fun createSession(directory: String? = null, title: String? = null): Session {
        val body = JSONObject()
        if (!title.isNullOrBlank()) body.put("title", title)
        if (!directory.isNullOrBlank()) {
            body.put("location", JSONObject().put("directory", directory))
        }
        return Session.fromJson(dataObject(apiPost("session", body)))
    }

    /**
     * `GET /api/project` → a bare array of `{id,canonical,time,sandboxes}`
     * (no envelope). Used by Home's projects column and the new-session
     * draft's project selector; entries without a usable worktree are dropped
     * by [Project.fromJson].
     */
    fun projects(): List<Project> = parseJsonArray(
        call(apiBuilder("project").get().build()),
    ).mapNotNull(Project::fromJson)

    /**
     * `GET /api/location` → the bare `{directory, project}` object (v1's `GET
     * /path` is gone with no successor). The shell only ever needed
     * `directory` — the server's cwd, the new session's default location and
     * the directory browser's initial path — and that key survives verbatim.
     */
    fun serverPath(): JSONObject = JSONObject(call(apiBuilder("location").get().build()))

    /**
     * `GET /api/fs/list?path=` → `{location, data:[{path, type}]}` with paths
     * **relative** to the location directory. The add-project browser keeps
     * [FileNode.directory] entries only; the call itself is unfiltered so the
     * payload stays faithful to the wire.
     */
    fun listFiles(path: String, directory: String? = null): List<FileNode> {
        val body = call(
            apiBuilder("fs/list") {
                located(it, directory)
                if (path.isNotBlank()) it.addQueryParameter("path", path)
            }.get().build(),
        )
        val root = JSONObject(body)
        val base = root.optJSONObject("location")?.optStr("directory").orEmpty()
        val rows = root.optJSONArray("data") ?: JSONArray()
        return (0 until rows.length()).mapNotNull { i ->
            rows.optJSONObject(i)?.let { FileNode.fromV2Json(it, base) }
        }
    }

    /**
     * `PATCH /api/session/:sessionID` `{title}` → **204** (no body). The
     * authoritative row is re-read afterwards, preserving the "the returned
     * session is the server's state" contract. [directory] is accepted for
     * source compatibility and ignored — the route takes no location.
     */
    fun renameSession(sessionID: String, title: String, directory: String? = null): Session {
        apiPatch("session/$sessionID", JSONObject().put("title", title))
        return session(sessionID)
    }

    /**
     * `DELETE /api/session/:sessionID` → **204**. Like every other write here
     * a non-2xx throws [ApiException], so a `true` return means the delete
     * reached the server. [directory] is accepted for source compatibility
     * and ignored — the route takes no location.
     */
    fun deleteSession(sessionID: String, directory: String? = null): Boolean {
        call(apiBuilder("session/$sessionID").delete().build())
        return true
    }

    // -------------------------------------------------------- W6 session actions
    //
    // Revert / fork / share / archive are the writes behind the ⋮ menu's session
    // actions (`_upstream/packages/app/src/session/commands/
    // use-session-commands.tsx:248-309`). Every one of them answers with the full
    // `Session.Info`, which is the server's authoritative state after the write —
    // so the caller refreshes its local model from the response instead of
    // re-reading (`ChatViewModel.applySessionInfo`).

    /**
     * Revert the session to [messageID]. v1's single
     * `POST /session/:sessionID/revert` became a stage/commit pair —
     * `POST /api/session/:sessionID/revert/stage` `{messageID}` then
     * `POST …/revert/commit` (both 204) — and staging alone changes nothing
     * visible, so the commit is part of this call: the observable effect stays
     * "the session is reverted", matching the old contract. v1's `partID` has
     * no v2 counterpart and is not sent; [directory] is accepted for source
     * compatibility and ignored. Returns the re-read session.
     */
    fun sessionRevert(
        sessionID: String,
        messageID: String,
        partID: String? = null,
        directory: String? = null,
    ): Session {
        apiPost("session/$sessionID/revert/stage", revertPayload(messageID))
        apiPost("session/$sessionID/revert/commit", JSONObject())
        return session(sessionID)
    }

    /**
     * `DELETE /api/session/:sessionID/revert` (204) — the "redo past the last
     * undone message" branch. Idempotent: a second call on an unreverted
     * session answers `204` and changes nothing. Returns the re-read session.
     */
    fun sessionUnrevert(sessionID: String, directory: String? = null): Session {
        call(apiBuilder("session/$sessionID/revert").delete().build())
        return session(sessionID)
    }

    /**
     * `POST /api/session/:sessionID/fork` `{before?}` → the **new**
     * `{data:SessionInfo}`. v1's `{messageID?}` key is silently ignored by v2
     * — same value, new name — so [messageID] is sent as `before`. `null`
     * forks the whole session as it stands.
     */
    fun sessionFork(
        sessionID: String,
        messageID: String? = null,
        directory: String? = null,
    ): Session = Session.fromJson(
        dataObject(apiPost("session/$sessionID/fork", forkPayload(messageID))),
    )

    /** `GET /api/session/:sessionID` → `{data:SessionInfo}` (title refresh on snapshot). */
    fun session(sessionID: String, directory: String? = null): Session =
        Session.fromJson(dataObject(call(apiBuilder("session/$sessionID").get().build())))

    /**
     * `GET /api/session/:sessionID/message` → `{data:[…]}` of flat tagged-union
     * rows, newest first. [limit] keeps only the most recent N; the rows are
     * reversed into chronological order before returning, so the v1 "most
     * recent N in reading order" contract holds. `null` = full history.
     * [directory] is accepted for source compatibility and ignored — the route
     * takes no location.
     */
    fun messages(sessionID: String, directory: String? = null, limit: Int? = null): List<Message> =
        messageRows(sessionID, limit).map { Message.fromV2Json(it, sessionID) }

    /** One `message` page as raw rows, chronological (see [messages]). */
    private fun messageRows(sessionID: String, limit: Int?): List<JSONObject> {
        val body = call(
            apiBuilder("session/$sessionID/message") {
                if (limit != null && limit > 0) it.addQueryParameter("limit", limit.toString())
            }.get().build(),
        )
        return dataRows(body).reversed()
    }

    // ---------------------------------------------------------------- raw JSON
    //
    // A8 (session export) needs the payload **exactly as the server sent it**:
    // `session.get` returns one `SessionInfo` object, `message.list` an array of
    // message rows. These two are additive — the parsing paths above are untouched
    // and share `apiBuilder`/`call`/`messageRows` with them — and they are the only
    // reason the export never re-serializes a parsed model (which would drop
    // fields; `research/wave6_export.md` §2.1 option ① vs ②).

    /**
     * `GET /api/session/:sessionID` → the **raw** `SessionInfo` object (export
     * payload's `info` key). Same request as [session], no parsing.
     */
    fun sessionJson(sessionID: String, directory: String? = null): JSONObject =
        dataObject(call(apiBuilder("session/$sessionID").get().build()))

    /**
     * `GET /api/session/:sessionID/message` → the **raw** message rows
     * (export payload's `messages` key), chronological like [messages].
     * [limit] behaves exactly as in [messages].
     */
    fun messagesJson(sessionID: String, directory: String? = null, limit: Int? = null): List<JSONObject> =
        messageRows(sessionID, limit)

    /**
     * `GET /api/fs/find?query=` → `{location, data:[{path, type}]}` with paths
     * **relative** to the location directory (the matcher is fuzzy/ranked, so
     * callers must not assume a literal match). A bare string array (the v1
     * shape) still parses.
     */
    fun findFiles(query: String, directory: String? = null): List<String> {
        val body = call(
            apiBuilder("fs/find") {
                located(it, directory)
                it.addQueryParameter("query", query)
            }.get().build(),
        )
        val trimmed = body.trim()
        if (trimmed.startsWith("{") && JSONObject(trimmed).has("data")) {
            return dataRows(body).mapNotNull { it.optStr("path").takeIf { s -> s.isNotEmpty() } }
        }
        val arr = JSONArray(trimmed)
        return (0 until arr.length()).mapNotNull { i ->
            when (val element = arr.opt(i)) {
                is String -> element.takeIf { it.isNotEmpty() && it != "null" }
                is JSONObject -> element.optStr("path").takeIf { it.isNotEmpty() }
                else -> null
            }
        }
    }

    /**
     * `GET /api/agent` → `{location, data:[…]}` — the composer's agent chip.
     * Entries without a name are dropped.
     */
    fun agents(directory: String? = null): List<Agent> =
        dataRows(call(apiBuilder("agent") { located(it, directory) }.get().build()))
            .map(Agent::fromJson).filter { it.name.isNotEmpty() }

    /**
     * `GET /api/command` → `{location, data:[{name, description}]}` — the
     * server's slash-command catalog (v2 carries no template/source).
     */
    fun commands(directory: String? = null): List<CommandInfo> =
        dataRows(call(apiBuilder("command") { located(it, directory) }.get().build()))
            .mapNotNull(CommandInfo::fromJson)

    /** `GET /api/skill` → `{location, data:[…]}` — the `@`-mention skill category. */
    fun skills(directory: String? = null): List<SkillInfo> =
        dataRows(call(apiBuilder("skill") { located(it, directory) }.get().build()))
            .mapNotNull(SkillInfo::fromJson)

    /**
     * `GET /api/permission/request` → `{location, data:[…]}`: every pending
     * permission ask in the directory. Entries without an `id` are dropped —
     * they can never be replied to.
     */
    fun listPermissions(directory: String? = null): List<PermissionRequest> =
        dataRows(call(apiBuilder("permission/request") { located(it, directory) }.get().build()))
            .map(PermissionRequest::fromJson).filter { it.id.isNotEmpty() }

    /**
     * No v2 successor exists with this signature: `POST
     * /api/session/:sessionID/permission/:requestID/reply` needs the session
     * the ask belongs to (`{decision: once|always|reject, message?}`), which
     * this call never carried. Fails loudly instead of posting to a route the
     * server no longer serves — use
     * [V2Api.replyPermission].
     */
    fun replyPermission(requestID: String, reply: String, directory: String? = null): String =
        throw UnsupportedOperationException(
            "replyPermission has no v2 equivalent without a sessionID; " +
                "use V2Api.replyPermission(sessionID, requestID, decision).",
        )

    /**
     * `GET /api/session/:sessionID/diff` → `{data:[FileDiff…]}` — the review
     * side panel's Changes tab data source. v2 scopes by commit/message range
     * (`from`/`to`), not one `messageID`: passing [messageID] throws instead
     * of silently returning the unscoped diff — use
     * [V2Api.sessionDiff] for a ranged read.
     */
    fun sessionDiff(sessionID: String, messageID: String? = null, directory: String? = null): List<FileDiff> {
        if (!messageID.isNullOrBlank()) {
            throw UnsupportedOperationException(
                "V2 session diff has no per-message scope; use V2Api.sessionDiff(sessionID, from, to).",
            )
        }
        return dataRows(call(apiBuilder("session/$sessionID/diff").get().build()))
            .map(FileDiff::fromJson)
    }

    /**
     * `GET /api/fs/read/<rel>` → the raw file bytes for the review panel's
     * file preview, folded into [FileContent] via [fileContentFromBytes].
     * [path] is relative to [directory] (a leading slash is stripped); the
     * route answers bytes, never JSON.
     */
    fun fileContent(path: String, directory: String? = null): FileContent {
        val relative = path.replace('\\', '/').trimStart('/')
        return fileContentFromBytes(
            callBytes(
                apiBuilder("fs/read/$relative") { located(it, directory) }.get().build(),
            ),
        )
    }

    /** `GET /api/vcs` → `{location, data:{branch:{current?,default?}}}`. */
    fun vcsInfo(directory: String? = null): VcsInfo =
        VcsInfo.fromJson(
            dataObject(call(apiBuilder("vcs") { located(it, directory) }.get().build())),
        )

    /**
     * `GET /api/vcs/diff?mode=` → `{location, data:[FileDiff…]}`. v2 accepts
     * `working|branch|committed` and 400s v1's `git` — the one name is
     * translated via [v2VcsDiffMode] (`git` and `working` are the same scope:
     * uncommitted working-tree changes), everything else passes through.
     */
    fun vcsDiff(mode: String, directory: String? = null): List<FileDiff> {
        val body = call(
            apiBuilder("vcs/diff") {
                located(it, directory)
                it.addQueryParameter("mode", v2VcsDiffMode(mode))
            }.get().build(),
        )
        return dataRows(body).map(FileDiff::fromJson)
    }

    companion object {
        private val TAG = "OpenCodeClient"

        /**
         * The scope key for location-scoped v2 reads: the OpenAPI document
         * declares `location` with `style: deepObject, explode: true`, so the
         * literal `location[directory]` is the only name the server honours.
         */
        private const val LOCATION_DIRECTORY_QUERY = "location[directory]"

        /**
         * Basic-auth username upstream hardcodes when a server password is set
         * (`_upstream/packages/app/src/runtime/server/api.ts:6`); used whenever
         * the server entry carries no username of its own.
         */
        private const val DEFAULT_BASIC_USERNAME = "opencode"

        /**
         * The REST client every native surface shares (the ViewModel's
         * `sharedClient`, the catalog reads, the probe).
         *
         * Connection management, and why it is deliberately left at OkHttp's
         * defaults (v32 BUG-1 audit):
         * - `retryOnConnectionFailure` stays at its default `true`: OkHttp
         *   re-dials once when an **idle pooled** connection turns out to be
         *   dead, which is the cheap half of the problem.
         * - `pingInterval` is not set — it only keeps *WebSocket* connections
         *   warm (`NativeWebSocketHub` sets its own 20 s), never pooled HTTP
         *   ones.
         * - the pool keeps OkHttp's 5-minute idle lifetime / 5 idle
         *   connections: shortening it only moves the cost from a rare re-dial
         *   to a handshake on every read.
         * - what no client setting can cover is a **failed connect** — the
         *   measured `Failed to connect to /127.0.0.1:4095` while `adb reverse`
         *   and the backend were both up. Nothing was listening for that
         *   instant, so the fix is one level up: idempotent reads retry with
         *   backoff and evict the dead pool ([com.xiufeigo.opencodeshell.v2.TransportRetry],
         *   `ChatViewModel.readTransport`).
         */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()

        /**
         * Parsed object entries of a JSON-array response. Android's org.json
         * JSONArray is not Iterable (unlike the JVM test artifact), so callers
         * get a plain Kotlin list to map over.
         */
        fun parseJsonArray(body: String): List<JSONObject> {
            if (body.isBlank()) return emptyList()
            val trimmed = body.trim()
            if (trimmed.startsWith("[")) {
                val array = JSONArray(trimmed)
                return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
            }
            val obj = JSONObject(trimmed)
            // Some endpoints wrap payloads; surface the first array member.
            val keys = obj.keys()
            while (keys.hasNext()) {
                val arr = obj.optJSONArray(keys.next())
                if (arr != null) {
                    return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
                }
            }
            return emptyList()
        }

        /**
         * `tokens.input + output + reasoning + cache.read + cache.write`
         * (`research/04` §3 message info). Returns `null` for a missing object
         * or an all-zero total, which is what `ContextUsage` treats as "no
         * usage yet" rather than "0 % used".
         */
        fun tokenTotal(tokens: JSONObject?): Long? {
            if (tokens == null) return null
            val cache = tokens.optJSONObject("cache")
            val total = tokens.optLong("input", 0L) +
                tokens.optLong("output", 0L) +
                tokens.optLong("reasoning", 0L) +
                (cache?.optLong("read", 0L) ?: 0L) +
                (cache?.optLong("write", 0L) ?: 0L)
            return total.takeIf { it > 0L }
        }

        /**
         * `"providerID/modelID"` model reference — the shape of `GET /config`'s
         * `model` field and of the persisted picker override. Split on the FIRST
         * slash like upstream `Model.Ref.parse`
         * (`_upstream/packages/schema/src/model.ts:26-40`), so model ids may
         * themselves contain slashes. Malformed input → null.
         */
        fun parseModelRef(value: String?): Pair<String, String>? {
            val trimmed = value?.trim().orEmpty()
            val slash = trimmed.indexOf('/')
            if (slash <= 0 || slash >= trimmed.length - 1) return null
            return trimmed.substring(0, slash) to trimmed.substring(slash + 1)
        }

        /** Inverse of [parseModelRef]; blank parts → null (nothing to persist). */
        fun formatModelRef(ref: Pair<String, String>?): String? {
            if (ref == null) return null
            if (ref.first.isBlank() || ref.second.isBlank()) return null
            return "${ref.first}/${ref.second}"
        }

        /**
         * `revert/stage` body: `{messageID}` (`^msg`). v1's optional `partID`
         * (`^prt`) has no v2 counterpart and is not sent.
         */
        fun revertPayload(messageID: String): JSONObject =
            JSONObject().put("messageID", messageID)

        /**
         * `session.fork` body: `{before?}` (`^msg`, optional). v1's
         * `{messageID?}` key is silently ignored by v2 — same value, new name
         * — so `null` still means "fork the session as it stands".
         */
        fun forkPayload(messageID: String?): JSONObject {
            val payload = JSONObject()
            messageID?.takeIf { it.isNotBlank() }?.let { payload.put("before", it) }
            return payload
        }

        fun forServer(server: ServerConfigStore.Server, http: OkHttpClient = defaultClient()): OpenCodeClient? {            val parsed = server.url.toHttpUrlOrNull() ?: return null
            return OpenCodeClient(parsed, server.username, server.password, http)
        }
    }
}
