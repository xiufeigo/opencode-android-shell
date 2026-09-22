package com.xiufeigo.opencodeshell

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.xiufeigo.opencodeshell.v2.ServerGeneration
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Native mirror of the WebView's server config. The WebView stays the single
 * source of truth: the JS side pushes the raw localStorage value of
 * `opencode.global.dat:server` through
 * [OpenCodeAndroidBridge.syncServerConfig] after page load, and the shell only
 * caches it here so [OpenCodeClient] can talk to the server directly (Basic
 * auth included) without the loopback gateway hop.
 *
 * Wire schema (upstream `server` store):
 * `{"list":[ "http://host:port" | {"type":"http","http":{"url","username?","password?"}} ...],
 *   "active":"<url key>"}`
 * Legacy top-level `{url, username, password}` objects are tolerated too.
 */
class ServerConfigStore(
    context: Context,
    private val onChanged: (String) -> Unit = {},
) {

    data class Server(
        val url: String,
        val username: String?,
        val password: String?,
    ) {
        val isComplete: Boolean get() = url.startsWith("http://") || url.startsWith("https://")

        /**
         * Wave 1: detected generation for [url] (`serverKeyOf(url)`), or null
         * when never probed. Stored OUTSIDE the data class (see
         * [ServerConfigStore.generationOf]/[rememberGeneration]) so every
         * existing constructor call keeps compiling unchanged.
         */
        val serverKey: String get() = url.trim()
    }

    private val prefs = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "oc_server_config",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (e: Exception) {
        Log.w(TAG, "encrypted prefs unavailable, falling back to plain prefs: ${e.message}")
        context.getSharedPreferences("oc_server_config_plain", Context.MODE_PRIVATE)
    }

    @Volatile
    var server: Server? = null
        private set

    private val _revision = MutableStateFlow(0)
    /** Emits after every accepted config mutation so Compose clients refresh immediately. */
    val revision: StateFlow<Int> = _revision.asStateFlow()

    /** Last raw JSON seen — the only place the *full* list survives. */
    @Volatile
    private var lastJson: String? = null

    /**
     * Accepts a new store value.
     *
     * [notify] controls whether [onChanged] fires for this write. The
     * WebView→native direction (`syncServerConfig`) passes `false` so the
     * value is not echoed straight back into the WebView it came from; the
     * native→WebView direction ([replaceAll]) keeps the default `true` so the
     * WebView's localStorage mirror receives every native edit — including
     * list edits that do not change the active server (adding a second
     * server), which the old `next == server` early-return used to swallow.
     */
    fun updateFromJson(json: String?, notify: Boolean = true): Boolean {
        if (json.isNullOrBlank()) return false
        val next = try {
            parse(json)
        } catch (_: JSONException) {
            null
        } ?: return false
        val previousRaw = prefs.getString(KEY_LIST_JSON, null) ?: lastJson
        val serverChanged = next != server
        val rawChanged = previousRaw != json
        if (!serverChanged && !rawChanged) return false
        lastJson = json
        prefs.edit()
            .putString(KEY_LIST_JSON, json)
            .apply()
        if (serverChanged) {
            server = next
            prefs.edit()
                .putString(KEY_URL, next.url)
                .putString(KEY_USER, next.username)
                .putString(KEY_PASS, next.password)
                .apply()
        }
        _revision.value = _revision.value + 1
        if (notify && rawChanged) onChanged(json)
        return true
    }

    /** The full `{"list":[...],"active":...}` payload exactly as stored, or null. */
    fun rawJson(): String? = prefs.getString(KEY_LIST_JSON, null) ?: lastJson

    /**
     * Picks the active http server from the store value, preferring exact
     * matches on `active`, then the first usable http entry.
     */
    private fun parse(json: String): Server? {
        val obj = JSONObject(json)
        val list: JSONArray = obj.optJSONArray("list") ?: return parseLegacy(obj)
        val activeKey = obj.optString("active", "")
        var chosen: Server? = null
        for (i in 0 until list.length()) {
            val entry = list.opt(i)
            val server = when (entry) {
                is String -> Server(entry, null, null)
                is JSONObject -> parseEntry(entry)
                else -> null
            } ?: continue
            if (!server.isComplete) continue
            if (chosen == null) chosen = server
            if (activeKey.isNotEmpty() && server.url == activeKey) {
                chosen = server
                break
            }
        }
        return chosen
    }

    private fun parseEntry(entry: JSONObject): Server? {
        // Entries may be `{type:"http", http:{url,...}}`; upstream also folds
        // legacy `{url,...}` objects into that shape before storing.
        val http = entry.optJSONObject("http")
        if (http != null) {
            val type = entry.optString("type", "http")
            if (type.isNotEmpty() && type != "http") return null
            return fromHttp(http)
        }
        val topLevelUrl = entry.optString("url", "")
        if (topLevelUrl.isNotEmpty()) return fromHttp(entry)
        return null
    }

    private fun fromHttp(http: JSONObject): Server {
        val url = http.optString("url", "").trim()
        val password = http.optString("password", "").takeIf { it.isNotEmpty() }
        // Upstream defaults the basic-auth username to "opencode".
        val username = http.optString("username", "").takeIf { it.isNotEmpty() }
            ?: password?.let { DEFAULT_USERNAME }
        return Server(url, username, password)
    }

    /**
     * M4 read-only accessor: the *whole* configured list plus the active key.
     *
     * The M2 code path only ever needed the single active server (see [server]);
     * the Settings → Servers tab has to render every entry, so this exposes the
     * same parse without changing [updateFromJson] / [server] semantics.
     */
    fun listServers(): List<Server> {
        val json = prefs.getString(KEY_LIST_JSON, null) ?: lastJson ?: return server?.let { listOf(it) } ?: emptyList()
        return try {
            parseAll(json)
        } catch (_: JSONException) {
            server?.let { listOf(it) } ?: emptyList()
        }
    }

    /** The active server's URL key (`server.active` in the wire schema). */
    fun activeUrl(): String? {
        val json = prefs.getString(KEY_LIST_JSON, null) ?: lastJson ?: return server?.url
        return try {
            JSONObject(json).optString("active", "").takeIf { it.isNotEmpty() } ?: server?.url
        } catch (_: JSONException) {
            server?.url
        }
    }

    /**
     * M4 write path: replace the persisted list + active key.
     *
     * **Known limitation (reported to the parent):** the WebView still holds the
     * authoritative copy in `localStorage["opencode.global.dat"]`. This writes
     * the DataStore mirror that [OpenCodeClient] reads, so the native client
     * immediately targets the edited server; re-opening the WebView re-syncs it
     * through the existing `syncServerConfig` bridge call. Pushing the new value
     * *into* the WebView needs a JS hook that M4 does not add (the bridge is
     * read-only today) — tracked in `research/_M4_settings_palette_status.md`.
     */
    fun replaceAll(servers: List<Server>, activeUrl: String?): Boolean {
        val json = JSONObject().put(
            "list",
            JSONArray().apply {
                for (entry in servers) {
                    val http = JSONObject().put("url", entry.url)
                    entry.username?.let { http.put("username", it) }
                    entry.password?.let { http.put("password", it) }
                    put(JSONObject().put("type", "http").put("http", http))
                }
            },
        )
        activeUrl?.takeIf { it.isNotEmpty() }?.let { json.put("active", it) }
        return updateFromJson(json.toString())
    }

    private fun parseAll(json: String): List<Server> {
        val obj = JSONObject(json)
        val list = obj.optJSONArray("list") ?: return parseLegacy(obj)?.let { listOf(it) } ?: emptyList()
        val result = ArrayList<Server>(list.length())
        for (i in 0 until list.length()) {
            val entry = list.opt(i)
            val server = when (entry) {
                is String -> Server(entry, null, null)
                is JSONObject -> parseEntry(entry)
                else -> null
            } ?: continue
            if (server.isComplete) result.add(server)
        }
        return result
    }

    /** Top-level `{url, username?, password?}` (early M1 shape). */
    private fun parseLegacy(obj: JSONObject): Server? {
        val url = obj.optString("url", "").trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null
        val password = obj.optString("password", "").takeIf { it.isNotEmpty() }
        val username = obj.optString("username", "").takeIf { it.isNotEmpty() }
            ?: password?.let { DEFAULT_USERNAME }
        return Server(url, username, password)
    }

    fun restore() {
        lastJson = prefs.getString(KEY_LIST_JSON, null)
        val url = prefs.getString(KEY_URL, null) ?: return
        server = Server(url, prefs.getString(KEY_USER, null), prefs.getString(KEY_PASS, null))
    }

    // -------------------------------------------------- Wave 1: generation cache
    //
    // Per-server generation (`V1`/`V2`), keyed by the normalized server URL.
    // Persisted in the same prefs (backward-compatible: missing key ⇒ null ⇒
    // probe on next connect). The WebView mirror JSON is untouched — the
    // generation is a native-only cache, never synced upstream.
    //
    // Read path never throws: a corrupt value degrades to null (re-probe).

    /** Cached generation for [serverKey] (normalized URL), or null when never probed. */
    fun generationOf(serverKey: String): ServerGeneration? {
        val raw = try {
            prefs.getString(KEY_GENERATION_PREFIX + serverKey.trim(), null)
        } catch (_: Exception) {
            null
        } ?: return null
        return try {
            ServerGeneration.valueOf(raw)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /** Persist [generation] for [serverKey]; UNKNOWN clears the entry (re-probe next time). */
    fun rememberGeneration(serverKey: String, generation: ServerGeneration) {
        val key = KEY_GENERATION_PREFIX + serverKey.trim()
        try {
            val edit = prefs.edit()
            if (generation == ServerGeneration.UNKNOWN) edit.remove(key) else edit.putString(key, generation.name)
            edit.apply()
        } catch (_: Exception) {
            // Cache-only: never fail a connect over prefs.
        }
    }

    companion object {
        private const val TAG = "ServerConfigStore"
        private const val DEFAULT_USERNAME = "opencode"
        private const val KEY_URL = "url"
        private const val KEY_USER = "username"
        private const val KEY_PASS = "password"

        /** M4: the untouched `{"list":[...],"active":...}` payload. */
        private const val KEY_LIST_JSON = "list_json"

        /** Wave 1: per-server generation cache (`KEY_GENERATION_PREFIX + normalized URL`). */
        private const val KEY_GENERATION_PREFIX = "generation:"
    }
}
