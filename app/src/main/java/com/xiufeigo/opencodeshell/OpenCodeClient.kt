package com.xiufeigo.opencodeshell

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
 * Minimal native OpenCode REST client (M1): sessions, messages, prompt, abort,
 * file search. Talks **directly** to the user's server URL (no loopback
 * gateway hop) with Basic auth mirrored from the WebView config.
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

    private fun url(directory: String?, path: String): HttpUrl {
        val builder = baseHttpUrl.newBuilder()
        if (!directory.isNullOrBlank()) builder.addQueryParameter("directory", directory)
        for (seg in path.trim('/').split('/').filter { it.isNotEmpty() }) {
            builder.addPathSegment(seg)
        }
        return builder.build()
    }

    private fun builder(directory: String?, path: String): Request.Builder {
        val builder = Request.Builder().url(url(directory, path))
        basicAuthorization()?.let { builder.header("Authorization", it) }
        return builder
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

    /**
     * [url] plus `?limit=` when [limit] is positive. Both v1 list endpoints
     * accept it (`GET /session`, `research/04:209`; `GET /session/:id/message`,
     * `research/04:215`) and both answer with the **most recent** entries in
     * chronological order — verified against opencode 1.18.31, where
     * `?limit=2` on a 16-message session returned exactly the last two. A
     * non-positive [limit] is omitted (`limit=0` means "everything" to the
     * server, and leaving the parameter off says the same thing more clearly).
     */
    private fun pagedUrl(directory: String?, path: String, limit: Int?): HttpUrl {
        val builder = url(directory, path).newBuilder()
        if (limit != null && limit > 0) builder.addQueryParameter("limit", limit.toString())
        return builder.build()
    }

    private fun call(request: Request): String {
        http.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                throw ApiException(response.code, "HTTP ${response.code} ${body.take(200)}")
            }
            return body
        }
    }

    private fun post(directory: String?, path: String, json: JSONObject): String {
        val body: RequestBody = json.toString().toRequestBody(jsonMedia)
        return call(builder(directory, path).post(body).build())
    }

    private fun patch(directory: String?, path: String, json: JSONObject): String {
        val body: RequestBody = json.toString().toRequestBody(jsonMedia)
        return call(builder(directory, path).patch(body).build())
    }

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
                type = obj.optString("type", "unknown"),
                text = obj.optString("text", "").takeIf { it.isNotEmpty() && obj.has("text") },
                toolName = obj.optString("tool", "").takeIf { it.isNotEmpty() },
                toolStatus = obj.optJSONObject("state")?.optString("status", "")?.takeIf { it.isNotEmpty() },
                mime = obj.optString("mime", "").takeIf { it.isNotEmpty() },
                filename = obj.optString("filename", "").takeIf { it.isNotEmpty() },
                url = obj.optString("url", "").takeIf { it.isNotEmpty() },
                raw = obj,
            )

            private fun optId(obj: JSONObject): String =
                obj.optString("id", "").ifEmpty { "part:${obj.hashCode()}" }
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
                    id = info.optString("id"),
                    sessionID = info.optString("sessionID"),
                    role = info.optString("role", "user"),
                    timeCreated = time?.optLong("created", 0L) ?: 0L,
                    parts = parts,
                    tokens = tokenTotal(info.optJSONObject("tokens")),
                    providerID = info.optString("providerID", "")
                        .ifEmpty { nestedModel?.optString("providerID", "").orEmpty() }
                        .takeIf { it.isNotEmpty() },
                    modelID = info.optString("modelID", "")
                        .ifEmpty { nestedModel?.optString("modelID", "").orEmpty() }
                        .takeIf { it.isNotEmpty() },
                    agent = info.optString("agent", "").takeIf { it.isNotEmpty() },
                    timeCompleted = time?.optLong("completed", 0L)?.takeIf { it > 0L },
                    interrupted = ChatStore.isInterrupted(info.optJSONObject("error")),
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
    ) {
        companion object {
            fun fromJson(obj: JSONObject): Session = Session(
                id = obj.optString("id"),
                title = obj.optString("title", "").ifEmpty { "Untitled" },
                // Same org.json trap as VcsInfo: literal "null" is not a path.
                directory = obj.optString("directory", "")
                    .takeIf { it.isNotEmpty() && it != "null" },
                updatedAt = obj.optJSONObject("time")?.optLong("updated", 0L) ?: 0L,
                cost = obj.optDouble("cost", Double.NaN).takeIf { !it.isNaN() },
                shareUrl = obj.optJSONObject("share")?.optString("url", "")
                    ?.takeIf { it.isNotEmpty() && it != "null" },
                revertMessageID = obj.optJSONObject("revert")?.optString("messageID", "")
                    ?.takeIf { it.isNotEmpty() && it != "null" },
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
     * `GET /project` entry (`research/04` §3): a server-known project. The v1
     * server discovers projects implicitly (git roots it has seen); the
     * display name is the worktree's last path segment (see
     * `HomeSessions.projectName`). Lenient: entries without a `worktree` are
     * dropped — they can never be scoped to a session.
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
    ) {
        companion object {
            fun fromJson(obj: JSONObject): Project? {
                val worktree = obj.optString("worktree", "").trim()
                if (worktree.isEmpty()) return null
                return Project(
                    id = obj.optString("id", ""),
                    worktree = worktree,
                    vcs = obj.optString("vcs", "").takeIf { it.isNotEmpty() },
                    iconColor = obj.optJSONObject("icon")
                        ?.optString("color", "")
                        ?.takeIf { it.isNotEmpty() },
                    sandboxes = stringList(obj.optJSONArray("sandboxes")),
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
     * `GET /file?path=` entry (`research/04` §3): one directory-listing node,
     * `{name,path,absolute,type:"file"|"directory",ignored}`. Only
     * [directory] nodes are surfaced by the add-project browser; [absolute]
     * is the path that gets passed back as `?directory=`.
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
                val absolute = obj.optString("absolute", "").trim()
                    .ifEmpty { obj.optString("path", "").trim() }
                if (absolute.isEmpty()) return null
                return FileNode(
                    name = obj.optString("name", ""),
                    path = obj.optString("path", ""),
                    absolute = absolute,
                    directory = obj.optString("type", "") == "directory",
                    ignored = obj.optBoolean("ignored", false),
                )
            }
        }
    }

    /**
     * `PermissionV1.Request` (`_upstream/packages/schema/src/v1/permission.ts`
     * `:27-35`): one pending permission prompt. `permission` is the tool/action
     * name ("bash", "edit", "external_directory", ...), `patterns` the matched
     * resources, `always` the patterns an "always" reply would persist,
     * `tool` the originating tool call when the ask came from one.
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
                fun fromJson(obj: JSONObject): Tool = Tool(
                    messageID = obj.optString("messageID"),
                    callID = obj.optString("callID"),
                )
            }
        }

        companion object {
            /** Lenient: missing fields fall back to empty lists/nulls. */
            fun fromJson(obj: JSONObject): PermissionRequest = PermissionRequest(
                id = obj.optString("id"),
                sessionID = obj.optString("sessionID"),
                permission = obj.optString("permission"),
                patterns = stringList(obj.optJSONArray("patterns")),
                always = stringList(obj.optJSONArray("always")),
                tool = obj.optJSONObject("tool")?.let(Tool::fromJson),
                metadata = obj.optJSONObject("metadata"),
            )

            private fun stringList(arr: JSONArray?): List<String> {
                if (arr == null) return emptyList()
                return (0 until arr.length()).mapNotNull { i ->
                    arr.optString(i, "").takeIf { it.isNotEmpty() }
                }
            }
        }
    }

    /**
     * `QuestionV1.Request` (`_upstream/packages/schema/src/v1/question.ts`
     * `:15-40`): one pending question prompt. `questions` are answered in
     * order, each by an array of selected option **labels** (options carry no
     * separate value); `multiple` allows toggling several options, `custom`
     * (wire default `true`) allows a free-text answer. `tool` is the
     * originating tool call when the ask came from one.
     */
    data class QuestionRequest(
        val id: String,
        val sessionID: String,
        val questions: List<Info>,
        val tool: Tool?,
    ) {
        data class Option(val label: String, val description: String) {
            companion object {
                fun fromJson(obj: JSONObject): Option = Option(
                    label = obj.optString("label"),
                    description = obj.optString("description"),
                )
            }
        }

        data class Info(
            val question: String,
            val header: String,
            val options: List<Option>,
            val multiple: Boolean,
            /** `custom` is optional on the wire and defaults to `true`. */
            val custom: Boolean,
        ) {
            companion object {
                fun fromJson(obj: JSONObject): Info {
                    val optionsJson = obj.optJSONArray("options")
                    val options = if (optionsJson == null) {
                        emptyList()
                    } else {
                        (0 until optionsJson.length()).mapNotNull { i ->
                            optionsJson.optJSONObject(i)?.let(Option::fromJson)
                                ?.takeIf { it.label.isNotEmpty() }
                        }
                    }
                    return Info(
                        question = obj.optString("question"),
                        header = obj.optString("header"),
                        options = options,
                        multiple = obj.optBoolean("multiple", false),
                        custom = obj.optBoolean("custom", true),
                    )
                }
            }
        }

        data class Tool(val messageID: String, val callID: String) {
            companion object {
                fun fromJson(obj: JSONObject): Tool = Tool(
                    messageID = obj.optString("messageID"),
                    callID = obj.optString("callID"),
                )
            }
        }

        companion object {
            /** Lenient: missing fields fall back to empty lists/null. */
            fun fromJson(obj: JSONObject): QuestionRequest {
                val questionsJson = obj.optJSONArray("questions")
                val questions = if (questionsJson == null) {
                    emptyList()
                } else {
                    (0 until questionsJson.length()).mapNotNull { i ->
                        questionsJson.optJSONObject(i)?.let(Info::fromJson)
                    }
                }
                return QuestionRequest(
                    id = obj.optString("id"),
                    sessionID = obj.optString("sessionID"),
                    questions = questions,
                    tool = obj.optJSONObject("tool")?.let(Tool::fromJson),
                )
            }
        }
    }

    /**
     * `GET /provider` response (`research/04` §3.1):
     * `{all: Provider[], default: {providerID: modelID}, connected: string[]}`.
     *
     * Parsing is deliberately lenient: the catalog only feeds the composer's
     * model picker, so a missing/extra field degrades to a fallback instead of
     * failing the screen. `Provider.models` is a `Record<modelID, Model>` on
     * the wire; an array of model objects is tolerated as well. `all` is an
     * **array** on every server seen so far (opencode 1.18.31 included); an
     * id→provider map is accepted too because the model catalog next to it uses
     * that shape.
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
                    val id = obj.optString("id", "").ifEmpty { fallbackId.orEmpty() }
                    if (id.isEmpty()) return null
                    return Provider(
                        id = id,
                        name = obj.optString("name", "").ifEmpty { id },
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
             */
            val limitContext: Int? = null,
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
        ) {
            companion object {
                /** [fallbackId] = the map key when `models` came as an object. */
                fun fromJson(obj: JSONObject, fallbackId: String? = null): Model? {
                    val id = obj.optString("id", "").ifEmpty { fallbackId.orEmpty() }
                    if (id.isEmpty()) return null
                    val limit = obj.optJSONObject("limit")
                    return Model(
                        id = id,
                        name = obj.optString("name", "").ifEmpty { id },
                        capabilities = obj.optJSONObject("capabilities")?.let(Capabilities::fromJson),
                        limitContext = limit?.optInt("context", 0)?.takeIf { it > 0 },
                        limitOutput = limit?.optInt("output", 0)?.takeIf { it > 0 },
                        variants = variantKeys(obj.opt("variants")),
                    )
                }

                /**
                 * `Record<variant, object>` on the wire (v1 `/provider`); a plain
                 * string array is accepted for forward compatibility. Order is the
                 * server's own key order, so the picker shows `none, low, …` the
                 * way the catalog declares them.
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
                        raw.optString(i, "").takeIf { it.isNotEmpty() }
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

        fun model(providerID: String, modelID: String): Model? =
            all.firstOrNull { it.id == providerID }?.models?.firstOrNull { it.id == modelID }

        companion object {
            fun fromJson(obj: JSONObject): Providers {
                val all = parseProviders(obj.opt("all"))
                val connectedRaw = obj.optJSONArray("connected")
                val connected = if (connectedRaw == null) {
                    emptyList()
                } else {
                    (0 until connectedRaw.length()).mapNotNull { i ->
                        connectedRaw.optString(i, "").takeIf { it.isNotEmpty() }
                    }
                }
                val defaultRaw = obj.optJSONObject("default")
                val default = LinkedHashMap<String, String>()
                if (defaultRaw != null) {
                    val keys = defaultRaw.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val value = defaultRaw.optString(key, "")
                        if (key.isNotEmpty() && value.isNotEmpty()) default[key] = value
                    }
                }
                return Providers(all, connected, default)
            }

            /**
             * `all` is a JSON **array** on the wire — verified against
             * opencode 1.18.31, whose `/provider` answers
             * `{"all":[{id,name,models,…},…]}`. An `id → Provider` map is
             * tolerated as well (the map key is then the provider id), mirroring
             * how `Provider.models` arrives as a `Record<modelID, Model>`.
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
                file = obj.optString("file", "").takeIf { it.isNotEmpty() },
                patch = obj.optString("patch", "").takeIf { it.isNotEmpty() },
                additions = obj.optInt("additions", 0),
                deletions = obj.optInt("deletions", 0),
                status = obj.optString("status", "").takeIf { it.isNotEmpty() },
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
                content = obj.optString("content", ""),
                status = obj.optString("status", ""),
                priority = obj.optString("priority", ""),
            )

            fun listFromJson(arr: JSONArray): List<TodoInfo> =
                (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONObject(i)?.let(::fromJson)
                }
        }
    }

    /**
     * `GET /file/content?path=` (`research/04` §3.1):
     * `{type:"text"|"binary", content, diff?, patch?, encoding?, mimeType?}`.
     * `patch` is a `{hunks[]}` object on the wire; a string patch (older
     * servers) is tolerated and kept verbatim, an object is stringified so no
     * information is lost.
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
                content = obj.optString("content", ""),
                diff = obj.optString("diff", "").takeIf { it.isNotEmpty() },
                patch = when (val raw = obj.opt("patch")) {
                    is String -> raw.takeIf { it.isNotEmpty() }
                    is JSONObject -> raw.toString()
                    else -> null
                },
                encoding = obj.optString("encoding", "").takeIf { it.isNotEmpty() },
                mimeType = obj.optString("mimeType", "").takeIf { it.isNotEmpty() },
            )
        }
    }

    /** `GET /vcs` (`research/04` §3.1) → `{branch?, default_branch?}`. */
    data class VcsInfo(
        val branch: String?,
        val defaultBranch: String?,
    ) {
        companion object {
            fun fromJson(obj: JSONObject): VcsInfo = VcsInfo(
                // `optString` returns the literal "null" for JSON null —
                // filter it or the header chip prints "null".
                branch = obj.optString("branch", "").takeIf { it.isNotEmpty() && it != "null" },
                defaultBranch = obj.optString("default_branch", "").takeIf { it.isNotEmpty() && it != "null" },
            )
        }
    }

    /**
     * `GET /agent` entry (W5-C B4). Verified against opencode 1.18.31:
     * `[{name, description, mode, native, hidden, topP, temperature, color,
     * permission, variant, prompt, options, steps}, …]` — 275 entries in that
     * workspace, of which 5 are `mode == "primary"` (build, plan, …) and 270 are
     * `subagent`s.
     *
     * Only `mode == "primary"` agents can own a session (`Session.Info.agent`),
     * so [selectable] is what the composer's agent chip lists; [hidden] ones
     * (title/summary/compaction helpers) are never offered.
     */
    data class Agent(
        val name: String,
        val description: String,
        val mode: String,
        val native: Boolean,
        val hidden: Boolean,
        /** `agent.variant` — the reasoning effort the agent configures. */
        val variant: String? = null,
        /** `agent.model` when the agent pins a model (nested `{providerID,modelID}`). */
        val providerID: String? = null,
        val modelID: String? = null,
    ) {
        /** `mode == "primary"` — the agents a session can run. */
        val primary: Boolean get() = mode == "primary"

        companion object {
            fun fromJson(obj: JSONObject): Agent {
                val model = obj.optJSONObject("model")
                return Agent(
                    name = obj.optString("name", ""),
                    description = obj.optString("description", ""),
                    mode = obj.optString("mode", ""),
                    // `native`/`hidden` are optional on the wire: absent means
                    // "not a built-in" / "visible", never a fabricated true.
                    native = obj.optBoolean("native", false),
                    hidden = obj.optBoolean("hidden", false),
                    variant = obj.optString("variant", "").takeIf { it.isNotEmpty() },
                    providerID = model?.optString("providerID", "")?.takeIf { it.isNotEmpty() },
                    modelID = model?.optString("modelID", "")?.takeIf { it.isNotEmpty() },
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

            const val FALLBACK_AGENT = "build"
        }
    }

    /**
     * `GET /command` entry (W5-C B1). Verified against opencode 1.18.31:
     * `[{name, description, source, template, hints}, …]` with `source ∈
     * {"command", "skill"}` (2 user commands + 73 skills in that workspace).
     * `template` is the prompt the command expands to; `$ARGUMENTS` inside it is
     * the placeholder for the typed arguments
     * (`_upstream/packages/core` command loader; the app only forwards
     * `{command, arguments}` to `POST /session/:id/command`).
     */
    data class CommandInfo(
        val name: String,
        val description: String,
        val source: String,
        val template: String,
    ) {
        companion object {
            fun fromJson(obj: JSONObject): CommandInfo? {
                val name = obj.optString("name", "").trim()
                if (name.isEmpty()) return null
                return CommandInfo(
                    name = name,
                    description = obj.optString("description", ""),
                    source = obj.optString("source", ""),
                    template = obj.optString("template", ""),
                )
            }
        }
    }

    /**
     * `GET /skill` entry (W5-C B9). Verified against opencode 1.18.31:
     * `[{name, description, location, content}, …]`. Only name/description are
     * read — the `@`-mention popover lists skills, it never sends their body.
     */
    data class SkillInfo(
        val name: String,
        val description: String,
        val location: String? = null,
    ) {
        companion object {
            fun fromJson(obj: JSONObject): SkillInfo? {
                val name = obj.optString("name", "").trim()
                if (name.isEmpty()) return null
                return SkillInfo(
                    name = name,
                    description = obj.optString("description", ""),
                    location = obj.optString("location", "").takeIf { it.isNotEmpty() },
                )
            }
        }
    }

    // ------------------------------------------------------------------ api

    /**
     * `GET /session` → `Session.Info[]`. [limit] maps to the v1 `limit` query
     * (see [pagedUrl]); `null` keeps the previous full-list behaviour, so every
     * existing caller compiles and behaves unchanged.
     */
    fun listSessions(directory: String? = null, limit: Int? = null): List<Session> {
        val listUrl = pagedUrl(directory, "/session", limit)
        return parseJsonArray(call(builder(directory, "/session").get().url(listUrl).build()))
            .map(Session::fromJson)
    }

    /** `GET /config` — used for the default model shown on the composer chip. */
    fun config(): JSONObject = JSONObject(call(builder(null, "/config").get().build()))

    /**
     * `GET /provider` (`research/04` §3.1) — the provider/model catalog behind
     * the composer model picker.
     */
    fun providers(directory: String? = null): Providers =
        Providers.fromJson(JSONObject(call(builder(directory, "/provider").get().build())))

    fun createSession(directory: String? = null, title: String? = null): Session {
        val body = JSONObject()
        if (!title.isNullOrBlank()) body.put("title", title)
        return Session.fromJson(JSONObject(post(directory, "/session", body)))
    }

    /**
     * `GET /project` (`research/04` §3): the server's known projects. Used by
     * Home's projects column and the new-session draft's project selector.
     * Entries without a usable `worktree` are dropped by [Project.fromJson].
     */
    fun projects(): List<Project> = parseJsonArray(
        call(builder(null, "/project").get().build()),
    ).mapNotNull(Project::fromJson)

    /**
     * `GET /path` (`research/04` §3) → `{home,state,config,worktree,directory}`.
     * The shell only needs `directory` (the server's cwd), which is the new
     * session's default location and the directory browser's initial path.
     */
    fun serverPath(): JSONObject = JSONObject(call(builder(null, "/path").get().build()))

    /**
     * `GET /file?path=` (`research/04` §3): one directory's immediate children.
     * The add-project browser keeps [FileNode.directory] entries only; the
     * call itself is unfiltered so the payload stays faithful to the wire.
     */
    fun listFiles(path: String, directory: String? = null): List<FileNode> {
        val listUrl = url(directory, "/file").newBuilder()
            .addQueryParameter("path", path)
            .build()
        return parseJsonArray(call(builder(directory, "/file").get().url(listUrl).build()))
            .mapNotNull(FileNode::fromJson)
    }

    /**
     * `PATCH /session/:sessionID` `{title}` → `Session.Info` (`research/04` §3).
     * The only write this client gained for M2; every other method is
     * unchanged, and the returned session is the server's authoritative state
     * (title + updated time).
     */
    fun renameSession(sessionID: String, title: String, directory: String? = null): Session =
        Session.fromJson(
            JSONObject(patch(directory, "/session/$sessionID", JSONObject().put("title", title))),
        )

    /**
     * `DELETE /session/:sessionID` (upstream `session/timeline/controller.tsx`
     * `deleteSession` → `client.session.delete({path:{id}})`;
     * `_upstream/packages/app/src/session/timeline/controller.tsx:210,272`).
     * The server answers `true`; like every other write here a non-2xx throws
     * [ApiException], so a `true` return means the delete reached the server.
     */
    fun deleteSession(sessionID: String, directory: String? = null): Boolean {
        call(builder(directory, "/session/$sessionID").delete().build())
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
     * `POST /session/:sessionID/revert` `{messageID, partID?}` →
     * `Session.Info` with `revert.messageID` set.
     *
     * Upstream's revert boundary is the **user message** the session is rewound
     * to: that message's prompt is replayed into the composer and every message
     * after it is hidden (`session/revert.ts:49-90` `stage()`,
     * `session/session-domain.ts:16-18`). `partID` narrows the boundary to one
     * part of the message (openapi `^prt`, optional here — the app only reverts
     * whole messages).
     *
     * Measured on 1.18.31 (openapi `session.revert`, verified live): the route
     * accepts an unknown `messageID` and answers `200` with **no** `revert` field
     * — a bogus target is a silent no-op, not an error, so callers must read the
     * returned session rather than assume the write happened.
     */
    fun sessionRevert(
        sessionID: String,
        messageID: String,
        partID: String? = null,
        directory: String? = null,
    ): Session = Session.fromJson(
        JSONObject(post(directory, "/session/$sessionID/revert", revertPayload(messageID, partID))),
    )

    /**
     * `POST /session/:sessionID/unrevert` (no body) → `Session.Info` without the
     * `revert` field. Upstream's `revert.clear` (`session/revert.ts:115`) — the
     * "redo past the last undone message" branch. Idempotent: a second call on an
     * unreverted session answers `200` and changes nothing (verified live).
     */
    fun sessionUnrevert(sessionID: String, directory: String? = null): Session = Session.fromJson(
        JSONObject(call(builder(directory, "/session/$sessionID/unrevert").post(EMPTY_BODY).build())),
    )

    /**
     * `POST /session/:sessionID/fork` `{messageID?}` → the **new** `Session.Info`
     * (`session/commands/fork-dialog.tsx:71-89`; the SDK's `before` maps to this
     * body's `messageID`).
     *
     * [messageID] is the message the fork is cut **before**: verified live, a
     * fork at the second of three messages carries exactly the first one, and a
     * fork at the first message carries none. `null` forks the whole session as
     * it stands. The parent is not recorded in `Session.Info`, so the new session
     * is reached by id only (`GET /session/:id/children` answers `[]` for it —
     * verified live).
     */
    fun sessionFork(
        sessionID: String,
        messageID: String? = null,
        directory: String? = null,
    ): Session = Session.fromJson(
        JSONObject(post(directory, "/session/$sessionID/fork", forkPayload(messageID))),
    )

    /**
     * `POST /session/:sessionID/share` (no body) → `Session.Info` carrying
     * `share.url`. Upstream copies that URL to the clipboard
     * (`session/commands/use-session-commands.tsx:123-141` is the sibling
     * `copySessionID` flow with the same toast contract; the share command itself
     * lives in the desktop menu, `runtime/i18n/en.ts:632-635`).
     */
    fun sessionShare(sessionID: String, directory: String? = null): Session = Session.fromJson(
        JSONObject(call(builder(directory, "/session/$sessionID/share").post(EMPTY_BODY).build())),
    )

    /**
     * `DELETE /session/:sessionID/share` (no body) → `Session.Info`.
     *
     * Measured on 1.18.31: the call succeeds (`200`) but the payload **still
     * reports the `share` object**, and so does every later `GET /session/:id`
     * (three consecutive DELETEs in a row, same URL; the only field that moves is
     * `time.updated`). Only the remote link is revoked, so the caller cannot use
     * the response to decide whether the session is private — `ChatViewModel`
     * keeps that bit locally for the session it just unshared.
     */
    fun sessionUnshare(sessionID: String, directory: String? = null): Session = Session.fromJson(
        JSONObject(call(builder(directory, "/session/$sessionID/share").delete().build())),
    )

    /**
     * `PATCH /session/:sessionID` `{"time":{"archived": <epoch millis>}}` →
     * `Session.Info` with `time.archived` set.
     *
     * The archive is a **list filter on the client**, not a server-side
     * exclusion: `GET /session` keeps returning the archived row (verified live,
     * 1.18.31) and upstream drops it while building Home
     * (`home/sessions/index.ts:51` `typeof session.time.archived !== "number"`,
     * `workspaces/paths.ts:38`). Passing `0` is the un-archive write — the server
     * stores the literal `0`; a JSON `null` is ignored and leaves the previous
     * timestamp in place (verified live). See [Session.archivedAt].
     */
    fun sessionArchive(
        sessionID: String,
        archivedMillis: Long,
        directory: String? = null,
    ): Session = Session.fromJson(
        JSONObject(patch(directory, "/session/$sessionID", archivePayload(archivedMillis))),
    )

    /** `GET /session/:sessionID` → one `Session.Info` (title refresh on snapshot). */
    fun session(sessionID: String, directory: String? = null): Session =
        Session.fromJson(JSONObject(call(builder(directory, "/session/$sessionID").get().build())))

    /**
     * `GET /session/:sessionID/message` → `WithParts[]`. [limit] keeps only the
     * most recent N messages (see [pagedUrl]); the snapshot path passes one so a
     * long transcript is not re-downloaded and re-parsed in full on every
     * reconnect. `null` = full history, the previous behaviour.
     */
    fun messages(sessionID: String, directory: String? = null, limit: Int? = null): List<Message> {
        val listUrl = pagedUrl(directory, "/session/$sessionID/message", limit)
        return parseJsonArray(
            call(builder(directory, "/session/$sessionID/message").get().url(listUrl).build()),
        ).map(Message::fromJson)
    }

    // ---------------------------------------------------------------- raw JSON
    //
    // A8 (session export) needs the payload **exactly as the server sent it**:
    // `session.get` returns one `Session.Info` object, `message.list` an array of
    // `WithParts`. These two are additive — the parsing paths above are untouched
    // and share `url`/`builder`/`call`/`pagedUrl` with them — and they are the only
    // reason the export never re-serializes a parsed model (which would drop
    // fields; `research/wave6_export.md` §2.1 option ① vs ②).

    /**
     * `GET /session/:sessionID` → the **raw** `Session.Info` object (export
     * payload's `info` key). Same request as [session], no parsing.
     */
    fun sessionJson(sessionID: String, directory: String? = null): JSONObject =
        JSONObject(call(builder(directory, "/session/$sessionID").get().build()))

    /**
     * `GET /session/:sessionID/message` → the **raw** `WithParts[]` rows (export
     * payload's `messages` key). [limit] behaves exactly as in [messages]:
     * `null` = full history, because v1 has no cursor pagination
     * (`research/wave6_export.md` §2.1/§5).
     */
    fun messagesJson(sessionID: String, directory: String? = null, limit: Int? = null): List<JSONObject> {
        val listUrl = pagedUrl(directory, "/session/$sessionID/message", limit)
        return parseJsonArray(call(builder(directory, "/session/$sessionID/message").get().url(listUrl).build()))
    }

    fun abort(sessionID: String, directory: String? = null) {
        call(builder(directory, "/session/$sessionID/abort").post(EMPTY_BODY).build())
    }

    /**
     * `/find/file` returns a bare JSON array of path strings
     * (`["a/b.md", ...]`); tolerate object entries with a `path` field too.
     */
    fun findFiles(query: String, directory: String? = null): List<String> {
        val queryUrl = url(directory, "/find/file").newBuilder()
            .addQueryParameter("query", query)
            .build()
        val arr = JSONArray(call(builder(directory, "/find/file").get().url(queryUrl).build()))
        return (0 until arr.length()).mapNotNull { i ->
            when (val element = arr.opt(i)) {
                is String -> element.takeIf { it.isNotEmpty() }
                is JSONObject -> element.optString("path", "").takeIf { it.isNotEmpty() }
                else -> null
            }
        }
    }

    /**
     * prompt_async body mirroring the upstream composer:
     * `parts: [{type:"text"},{type:"file",mime,url,filename}...]`.
     * Attachments are data URLs (SAF content:// is not readable by the server).
     *
     * [model] (`providerID` to `modelID`) adds `"model":{"providerID":…,
     * "modelID":…}` to the payload (PromptPayload, `research/04` §3.1); null
     * leaves the field out and the server uses its configured default.
     *
     * W5-C: [agent] / [variant] / [system] are additive and all optional — the
     * openapi `session.prompt_async` body is
     * `{messageID?, model?, agent?, noReply?, tools?, format?, system?, variant?,
     * parts}` with `required: [parts]`, so every existing caller keeps sending
     * exactly what it sent before. Verified live against 1.18.31: a body with
     * `agent` + `variant` answers 204 and the created assistant message echoes
     * both (`info.agent`, `info.variant`).
     */
    fun promptAsync(
        sessionID: String,
        text: String,
        attachments: List<ChatStore.UiPart> = emptyList(),
        directory: String? = null,
        model: Pair<String, String>? = null,
        agent: String? = null,
        variant: String? = null,
        system: String? = null,
    ) {
        post(
            directory,
            "/session/$sessionID/prompt_async",
            promptPayload(text, attachments, model, agent, variant, system),
        )
    }

    /**
     * `POST /session/:sessionID/shell` — the **real** shell-mode route (W5-C B3).
     * `{agent (required), command (required), messageID?, model?}`; answers the
     * created assistant message, whose `parts[0]` is the `bash` tool result.
     * Verified live against opencode 1.18.31 (`{agent:"build", command:"echo hi"}`
     * → 200 with `tool: "bash"`, `state.output: "hi"`).
     *
     * This is what upstream calls for shell mode
     * (`composer/submit.ts:296-298` → `session.api.shell`), **not** a
     * `mode:"shell"` field on `prompt_async`: that field is not in the
     * `prompt_async` schema and is silently ignored (measured: 204, and the
     * created message carries `mode: ""`/`agent: "build"`).
     */
    fun shellCommand(
        sessionID: String,
        command: String,
        agent: String,
        directory: String? = null,
        model: Pair<String, String>? = null,
    ) {
        post(
            directory,
            "/session/$sessionID/shell",
            shellPayload(command, agent, model),
        )
    }

    /**
     * `POST /session/:sessionID/command` — run a server command (W5-C B1).
     * Body `{command (required), arguments (required), agent?, model?, variant?,
     * messageID?, parts?}`; note `model` is a **string** here
     * (`"providerID/modelID"`), unlike `prompt_async`'s nested object — measured
     * from the 1.18.31 openapi document.
     * Upstream: `composer/submit.ts:309-322` `sendCommand`.
     */
    fun runCommand(
        sessionID: String,
        command: String,
        arguments: String,
        directory: String? = null,
        agent: String? = null,
        model: Pair<String, String>? = null,
        variant: String? = null,
    ) {
        post(
            directory,
            "/session/$sessionID/command",
            commandPayload(command, arguments, agent, model, variant),
        )
    }

    /**
     * `GET /agent` (`[{name, description, mode, native, hidden, variant, …}]`) —
     * the composer's agent chip (W5-C B4). Entries without a name are dropped.
     */
    fun agents(directory: String? = null): List<Agent> = parseJsonArray(
        call(builder(directory, "/agent").get().build()),
    ).map(Agent::fromJson).filter { it.name.isNotEmpty() }

    /**
     * `GET /command` — the server's slash-command catalog (W5-C B1), mixed
     * `command` + `skill` sources.
     */
    fun commands(directory: String? = null): List<CommandInfo> = parseJsonArray(
        call(builder(directory, "/command").get().build()),
    ).mapNotNull(CommandInfo::fromJson)

    /** `GET /skill` — the `@`-mention skill category (W5-C B9). */
    fun skills(directory: String? = null): List<SkillInfo> = parseJsonArray(
        call(builder(directory, "/skill").get().build()),
    ).mapNotNull(SkillInfo::fromJson)

    /**
     * `GET /global/health` → `{"healthy":true,"version":"1.18.31"}`. Additive
     * liveness probe for the composer's command/skill/agent catalog refresh (and
     * useful for the report evidence); nothing else calls it.
     */
    fun health(): JSONObject = JSONObject(call(builder(null, "/global/health").get().build()))


    /**
     * `GET /permission` → `PermissionV1.Request[]` (`research/04` §3): every
     * pending permission ask in the directory. Entries without an `id` are
     * dropped — they can never be replied to.
     */
    fun listPermissions(directory: String? = null): List<PermissionRequest> = parseJsonArray(
        call(builder(directory, "/permission").get().build()),
    ).map(PermissionRequest::fromJson).filter { it.id.isNotEmpty() }

    /**
     * `POST /permission/:requestID/reply` body `{"reply": reply}`
     * (`research/04` §3), reply ∈ "once" | "always" | "reject"
     * (`PermissionV1.Reply`). Returns the server body (usually `true`).
     */
    fun replyPermission(requestID: String, reply: String, directory: String? = null): String =
        post(directory, "/permission/$requestID/reply", JSONObject().put("reply", reply))

    /**
     * `GET /question` → `QuestionV1.Request[]` (`research/04` §3): every
     * pending question ask in the directory. Entries without an `id` are
     * dropped — they can never be replied to.
     */
    fun listQuestions(directory: String? = null): List<QuestionRequest> = parseJsonArray(
        call(builder(directory, "/question").get().build()),
    ).map(QuestionRequest::fromJson).filter { it.id.isNotEmpty() }

    /**
     * `POST /question/:requestID/reply` body `{"answers": [[label, ...], ...]}`
     * (`QuestionV1.Reply`): one string array per question, in question order.
     */
    fun replyQuestion(requestID: String, answers: List<List<String>>, directory: String? = null): String {
        val outer = JSONArray()
        for (answer in answers) {
            val inner = JSONArray()
            for (label in answer) inner.put(label)
            outer.put(inner)
        }
        return post(directory, "/question/$requestID/reply", JSONObject().put("answers", outer))
    }

    /**
     * `POST /question/:requestID/reject` — dismisses the ask; the request
     * carries no body (`research/04` §3.1).
     */
    fun rejectQuestion(requestID: String, directory: String? = null): String =
        call(builder(directory, "/question/$requestID/reject").post(EMPTY_BODY).build())

    /**
     * `GET /session/:sessionID/diff?messageID?` → `Snapshot.FileDiff[]`
     * (`research/04` §3.1). The review side panel's Changes tab data source;
     * [messageID] scopes the diff to one message when given.
     */
    fun sessionDiff(sessionID: String, messageID: String? = null, directory: String? = null): List<FileDiff> {
        val diffUrl = url(directory, "/session/$sessionID/diff").newBuilder().apply {
            if (!messageID.isNullOrBlank()) addQueryParameter("messageID", messageID)
        }.build()
        return parseJsonArray(call(builder(directory, "/session/$sessionID/diff").get().url(diffUrl).build()))
            .map(FileDiff::fromJson)
    }

    /**
     * `GET /session/:sessionID/todo` → `Todo.Info[]` (`research/04` §3.1).
     * Read-only exposure; rendering is a separate workflow.
     */
    fun sessionTodos(sessionID: String, directory: String? = null): List<TodoInfo> = parseJsonArray(
        call(builder(directory, "/session/$sessionID/todo").get().build()),
    ).map(TodoInfo::fromJson)

    /**
     * `GET /file/content?path=` (`research/04` §3.1): text content for the
     * review panel's file preview, or a binary descriptor ([FileContent.binary]).
     */
    fun fileContent(path: String, directory: String? = null): FileContent {
        val contentUrl = url(directory, "/file/content").newBuilder()
            .addQueryParameter("path", path)
            .build()
        return FileContent.fromJson(
            JSONObject(call(builder(directory, "/file/content").get().url(contentUrl).build())),
        )
    }

    /** `GET /vcs` → `{branch?, default_branch?}` (`research/04` §3.1). */
    fun vcsInfo(directory: String? = null): VcsInfo =
        VcsInfo.fromJson(JSONObject(call(builder(directory, "/vcs").get().build())))

    /**
     * `GET /vcs/diff?mode=` → `FileDiff[]` (upstream `vcs.diff({mode})`,
     * `_upstream/packages/app/src/session/review/model.ts:23`). **Verified**
     * against opencode 1.18.31: `mode=git` and `mode=branch` return JSON,
     * `mode=working` is rejected with 400, so the review panel's Git / Branch
     * scopes are backed by the server after all (the earlier "endpoint missing"
     * note was a wrong `mode` value, not a missing route).
     */
    fun vcsDiff(mode: String, directory: String? = null): List<FileDiff> {
        val diffUrl = url(directory, "/vcs/diff").newBuilder()
            .addQueryParameter("mode", mode)
            .build()
        return parseJsonArray(call(builder(directory, "/vcs/diff").get().url(diffUrl).build()))
            .map(FileDiff::fromJson)
    }

    companion object {
        private val EMPTY_BODY: RequestBody = ByteArray(0).toRequestBody(null)

        private val TAG = "OpenCodeClient"

        /**
         * Basic-auth username upstream hardcodes when a server password is set
         * (`_upstream/packages/app/src/runtime/server/api.ts:6`); used whenever
         * the server entry carries no username of its own.
         */
        private const val DEFAULT_BASIC_USERNAME = "opencode"

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

        // ------------------------------------------------------------ payloads
        //
        // W5-C: the write bodies are built by these pure functions so the exact
        // JSON the server receives is JVM-assertable (`OpenCodeClientWave5Test`)
        // without a socket. Every field is optional except the ones the openapi
        // document marks required.

        /**
         * `prompt_async` body: `{parts[required], model?, agent?, variant?,
         * system?}`. `parts` always leads with the text part (when non-blank)
         * followed by the file parts, exactly the order the M1 client sent.
         */
        fun promptPayload(
            text: String,
            attachments: List<ChatStore.UiPart> = emptyList(),
            model: Pair<String, String>? = null,
            agent: String? = null,
            variant: String? = null,
            system: String? = null,
        ): JSONObject {
            val parts = JSONArray()
            if (text.isNotEmpty()) parts.put(JSONObject().put("type", "text").put("text", text))
            for (attachment in attachments) {
                val filePart = JSONObject()
                    .put("type", "file")
                    .put("mime", attachment.mime ?: "application/octet-stream")
                    .put("filename", attachment.filename ?: "file")
                    .put("url", attachment.url ?: "")
                parts.put(filePart)
            }
            val payload = JSONObject().put("parts", parts)
            if (model != null) {
                payload.put(
                    "model",
                    JSONObject()
                        .put("providerID", model.first)
                        .put("modelID", model.second),
                )
            }
            // Blank agent/variant are omitted: the server then applies its own
            // default instead of being handed an empty selection.
            agent?.takeIf { it.isNotBlank() }?.let { payload.put("agent", it) }
            variant?.takeIf { it.isNotBlank() }?.let { payload.put("variant", it) }
            system?.takeIf { it.isNotEmpty() }?.let { payload.put("system", it) }
            return payload
        }

        /**
         * `session.shell` body: `{agent[required], command[required], model?}`.
         * `model` is the nested `{providerID, modelID}` object here (the
         * `/command` route is the one that takes a flat string).
         */
        fun shellPayload(
            command: String,
            agent: String,
            model: Pair<String, String>? = null,
        ): JSONObject {
            val payload = JSONObject()
                .put("command", command)
                .put("agent", agent)
            if (model != null) {
                payload.put(
                    "model",
                    JSONObject()
                        .put("providerID", model.first)
                        .put("modelID", model.second),
                )
            }
            return payload
        }

        /**
         * `session.command` body: `{command[required], arguments[required],
         * agent?, model?, variant?}`. `model` is the flat `"providerID/modelID"`
         * string this route expects.
         */
        fun commandPayload(
            command: String,
            arguments: String,
            agent: String? = null,
            model: Pair<String, String>? = null,
            variant: String? = null,
        ): JSONObject {
            val payload = JSONObject()
                .put("command", command)
                .put("arguments", arguments)
            agent?.takeIf { it.isNotBlank() }?.let { payload.put("agent", it) }
            formatModelRef(model)?.let { payload.put("model", it) }
            variant?.takeIf { it.isNotBlank() }?.let { payload.put("variant", it) }
            return payload
        }

        /**
         * `session.revert` body: `{messageID[required], partID?}` (openapi; both
         * are `^msg` / `^prt` strings and the object rejects extra properties).
         * A blank [partID] is omitted: the app reverts whole messages, and the
         * server would otherwise be handed an empty id.
         */
        fun revertPayload(messageID: String, partID: String? = null): JSONObject {
            val payload = JSONObject().put("messageID", messageID)
            partID?.takeIf { it.isNotBlank() }?.let { payload.put("partID", it) }
            return payload
        }

        /**
         * `session.fork` body: `{messageID?}`. The whole body is optional
         * (`requestBody` is not `required` in the openapi document and an empty
         * object was accepted live), so `null` sends `{}` — "fork the session as
         * it stands" rather than a body the server has to ignore.
         */
        fun forkPayload(messageID: String?): JSONObject {
            val payload = JSONObject()
            messageID?.takeIf { it.isNotBlank() }?.let { payload.put("messageID", it) }
            return payload
        }

        /**
         * `session.update` body: `{"time":{"archived": millis}}`. The nested
         * `time` object accepts only `archived` (openapi
         * `additionalProperties: false`).
         */
        fun archivePayload(archivedMillis: Long): JSONObject = JSONObject()
            .put("time", JSONObject().put("archived", archivedMillis))

        fun forServer(server: ServerConfigStore.Server, http: OkHttpClient = defaultClient()): OpenCodeClient? {            val parsed = server.url.toHttpUrlOrNull() ?: return null
            return OpenCodeClient(parsed, server.username, server.password, http)
        }
    }
}
