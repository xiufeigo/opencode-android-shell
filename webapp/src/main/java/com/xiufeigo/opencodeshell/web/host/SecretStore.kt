package com.xiufeigo.opencodeshell.web.host

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Encrypted host preferences. Keystore failures are explicit; credentials never fall back to plaintext. */
object SecretStore {
    private const val FILE = "oc_webhost_secrets"

    /**
     * 启动入口地址键。**不是秘密**：默认 `http://127.0.0.1:4095`，
     * 靠 `adb reverse tcp:4095 tcp:4095` 打通；放加密偏好只是为了
     * "换后端时不用重新编译"以及保持存储路径一致。
     */
    const val KEY_BACKEND_URL = "backendUrl"

    /** 没有配置过时的默认值（`adb reverse tcp:4095 tcp:4095` 之后的可达地址）。 */
    const val DEFAULT_BACKEND_URL = "http://127.0.0.1:4095"

    @Volatile
    private var prefs: SharedPreferences? = null

    fun get(context: Context): SharedPreferences {
        prefs?.let { return it }
        return synchronized(this) {
            prefs ?: create(context.applicationContext).also { prefs = it }
        }
    }

    private fun create(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /** @return null 表示没有这个键。空串视为"没有"，避免把空值当成有效配置。 */
    fun getSecret(context: Context, key: String): String? {
        if (key.isBlank()) return null
        require(key != "browserPairing") { "Browser pairing uses the dedicated platform API" }
        return get(context).getString(key, null)?.takeIf { it.isNotEmpty() }
    }

    /** 传 null 或空串 = 删键。 */
    fun setSecret(context: Context, key: String, value: String?) {
        if (key.isBlank()) return
        require(key != "browserPairing") { "Browser pairing uses the dedicated platform API" }
        val editor = get(context).edit()
        if (value.isNullOrEmpty()) editor.remove(key) else editor.putString(key, value)
        check(editor.commit()) { "Cannot persist host preference" }
    }
}
