package com.xiufeigo.opencodeshell

import android.app.Application
import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class OpenCodeShellApp : Application() {
    lateinit var settings: SettingsStore
        private set

    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore.create(this)
    }
}

class SettingsStore internal constructor(
    private val prefs: android.content.SharedPreferences,
) {
    var serverUrl: String
        get() = prefs.getString(KEY_SERVER_URL, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_SERVER_URL, value).apply()
        }

    var username: String
        get() = prefs.getString(KEY_USERNAME, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_USERNAME, value).apply()
        }

    var password: String
        get() = prefs.getString(KEY_PASSWORD, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_PASSWORD, value).apply()
        }

    val hasServerUrl: Boolean
        get() = serverUrl.isNotBlank()

    val hasCredentials: Boolean
        get() = username.isNotBlank()

    fun basicAuthHeader(): String? {
        if (!hasCredentials) return null
        val token = Base64.encodeToString(
            "$username:$password".toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP,
        )
        return "Basic $token"
    }

    fun extraHeaders(): Map<String, String> {
        val auth = basicAuthHeader() ?: return emptyMap()
        return mapOf("Authorization" to auth)
    }

    companion object {
        private const val PREFS_FILE = "opencode_shell_secure_prefs"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD = "password"

        fun create(context: Context): SettingsStore {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val prefs = EncryptedSharedPreferences.create(
                context,
                PREFS_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
            return SettingsStore(prefs)
        }
    }
}

fun Context.settingsStore(): SettingsStore =
    (applicationContext as OpenCodeShellApp).settings
