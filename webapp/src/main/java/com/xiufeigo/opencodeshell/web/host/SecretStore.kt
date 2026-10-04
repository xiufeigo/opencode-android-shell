package com.xiufeigo.opencodeshell.web.host

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * 凭据 / 令牌存储。**明文不落盘**。
 *
 * 落地物是 [EncryptedSharedPreferences]：key 用 AES256-SIV、value 用 AES256-GCM，
 * 主密钥由 Android Keystore 持有（应用卸载即销毁，导出备份也解不开）。
 * 文件名与 `:app` 的原生壳分开（`oc_webhost_secrets`），两个壳同机并存互不干扰。
 *
 * 已知代价：`android:allowBackup="false"`（见 manifest）必须保持——一旦允许备份，
 * 备份里的密文在另一台设备上没有同一把 Keystore 密钥。
 */
object SecretStore {
    private const val FILE = "oc_webhost_secrets"
    private const val TAG = "OcWebHost/SecretStore"

    /**
     * R1 约定的两个键。
     *
     * - [KEY_BACKEND_URL] 不是秘密（默认 `http://127.0.0.1:4095`，靠 adb reverse 打通），
     *   但仍放同一处以便换后端时不用重新编译。
     * - [KEY_SERVER_PASSWORD] **是秘密**：值以 AES256-GCM 加密存于
     *   `oc_webhost_secrets`，主密钥在 Android Keystore。回环代理每次转发都从这里读，
     *   既不硬编码也不进 JS 可见范围。
     */
    const val KEY_BACKEND_URL = "backendUrl"
    const val KEY_SERVER_PASSWORD = "serverPassword"

    /** 没有配置过时的上游默认值（`adb reverse tcp:4095 tcp:4095` 之后的可达地址）。 */
    const val DEFAULT_BACKEND_URL = "http://127.0.0.1:4095"

    @Volatile
    private var prefs: SharedPreferences? = null

    fun get(context: Context): SharedPreferences {
        prefs?.let { return it }
        return synchronized(this) {
            prefs ?: create(context.applicationContext).also { prefs = it }
        }
    }

    private fun create(context: Context): SharedPreferences = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (t: Throwable) {
        // Keystore 不可用（极少数 rooted / 厂商 bug / 锁屏态冷启）：不要退回明文。
        // 退化成内存偏好：本进程内可用，进程死掉即丢，但绝不落盘。
        Log.w(TAG, "EncryptedSharedPreferences unavailable, falling back to in-memory only", t)
        context.getSharedPreferences("${FILE}_mem", Context.MODE_PRIVATE)
    }

    /** @return null 表示没有这个键。空串视为"没有"，避免把空口令当成有效凭据。 */
    fun getSecret(context: Context, key: String): String? {
        if (key.isBlank()) return null
        return runCatching { get(context).getString(key, null) }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
    }

    /** 传 null 或空串 = 删键。 */
    fun setSecret(context: Context, key: String, value: String?) {
        if (key.isBlank()) return
        runCatching {
            get(context).edit().apply {
                if (value.isNullOrEmpty()) remove(key) else putString(key, value)
            }.apply()
        }.onFailure { Log.w(TAG, "setSecret($key) failed", it) }
    }
}
