package com.xiufeigo.opencodeshell

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONException
import org.json.JSONObject

/**
 * Native mirror of the WebView's server config (localStorage key
 * `opencode.global.dat:server` = `{url, username?, password?}`). The WebView
 * stays the single source of truth: the JS side pushes the current values
 * through [OpenCodeAndroidBridge.syncServerConfig] after every page load, and
 * the shell only caches them here so [OpenCodeClient] can talk to the server
 * directly (Basic auth included) without the loopback gateway hop.
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
        val url: String
        val username: String?
        val password: String?
        try {
            val obj = JSONObject(json)
            url = obj.optString("url", "").trim()
            username = obj.optString("username", "").takeIf { it.isNotEmpty() }
            password = obj.optString("password", "").takeIf { it.isNotEmpty() }
        } catch (_: JSONException) {
            return false
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) return false
        val next = Server(url, username, password)
        if (next == server) return false
        server = next
        prefs.edit()
            .putString(KEY_URL, next.url)
            .putString(KEY_USER, next.username)
            .putString(KEY_PASS, next.password)
            .apply()
        return true
    }

    fun restore() {
        val url = prefs.getString(KEY_URL, null) ?: return
        server = Server(url, prefs.getString(KEY_USER, null), prefs.getString(KEY_PASS, null))
    }

    companion object {
        private const val TAG = "ServerConfigStore"
        private const val KEY_URL = "url"
        private const val KEY_USER = "username"
        private const val KEY_PASS = "password"
    }
}
