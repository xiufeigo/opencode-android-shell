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
        if (!username.isNullOrEmpty() || !password.isNullOrEmpty()) {
            val credentials = "$username:$password"
            val basic = Base64.getEncoder().encodeToString(credentials.toByteArray(Charsets.UTF_8))
            builder.header("Authorization", "Basic $basic")
        }
        return builder
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
                return Message(
                    id = info.optString("id"),
                    sessionID = info.optString("sessionID"),
                    role = info.optString("role", "user"),
                    timeCreated = info.optJSONObject("time")?.optLong("created", 0L) ?: 0L,
                    parts = parts,
                )
            }
        }
    }

    data class Session(
        val id: String,
        val title: String,
        val directory: String?,
        val updatedAt: Long,
    ) {
        companion object {
            fun fromJson(obj: JSONObject): Session = Session(
                id = obj.optString("id"),
                title = obj.optString("title", "").ifEmpty { "Untitled" },
                directory = obj.optString("directory", "").takeIf { it.isNotEmpty() },
                updatedAt = obj.optJSONObject("time")?.optLong("updated", 0L) ?: 0L,
            )
        }
    }

    class ApiException(val code: Int, message: String) : IOException(message)

    // ------------------------------------------------------------------ api

    fun listSessions(directory: String? = null): List<Session> = parseJsonArray(
        call(builder(directory, "/session").get().build()),
    ).map(Session::fromJson)

    /** `GET /config` — used for the default model shown on the composer chip. */
    fun config(): JSONObject = JSONObject(call(builder(null, "/config").get().build()))

    fun createSession(directory: String? = null, title: String? = null): Session {
        val body = JSONObject()
        if (!title.isNullOrBlank()) body.put("title", title)
        return Session.fromJson(JSONObject(post(directory, "/session", body)))
    }

    fun messages(sessionID: String, directory: String? = null): List<Message> = parseJsonArray(
        call(builder(directory, "/session/$sessionID/message").get().build()),
    ).map(Message::fromJson)

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
     */
    fun promptAsync(
        sessionID: String,
        text: String,
        attachments: List<ChatStore.UiPart> = emptyList(),
        directory: String? = null,
    ) {
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
        post(directory, "/session/$sessionID/prompt_async", JSONObject().put("parts", parts))
    }

    companion object {
        private val EMPTY_BODY: RequestBody = ByteArray(0).toRequestBody(null)

        private val TAG = "OpenCodeClient"

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

        fun forServer(server: ServerConfigStore.Server, http: OkHttpClient = defaultClient()): OpenCodeClient? {
            val parsed = server.url.toHttpUrlOrNull() ?: return null
            return OpenCodeClient(parsed, server.username, server.password, http)
        }
    }
}
