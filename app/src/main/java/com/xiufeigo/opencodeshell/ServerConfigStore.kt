package com.xiufeigo.opencodeshell

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

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
class ServerConfigStore(context: Context) {

    data class Server(
        val url: String,
        val username: String?,
        val password: String?,
    ) {
        val isComplete: Boolean get() = url.startsWith("http://") || url.startsWith("https://")
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

    fun updateFromJson(json: String?): Boolean {
        if (json.isNullOrBlank()) return false
        val next = try {
            parse(json)
        } catch (_: JSONException) {
            null
        } ?: return false
        if (next == server) return false
        server = next
        prefs.edit()
            .putString(KEY_URL, next.url)
            .putString(KEY_USER, next.username)
            .putString(KEY_PASS, next.password)
            .apply()
        return true
    }

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
        val url = prefs.getString(KEY_URL, null) ?: return
        server = Server(url, prefs.getString(KEY_USER, null), prefs.getString(KEY_PASS, null))
    }

    companion object {
        private const val TAG = "ServerConfigStore"
        private const val DEFAULT_USERNAME = "opencode"
        private const val KEY_URL = "url"
        private const val KEY_USER = "username"
        private const val KEY_PASS = "password"
    }
}
