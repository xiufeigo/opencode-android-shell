package com.xiufeigo.opencodeshell.web.host

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * 宿主偏好存储。**明文不落盘**。
 *
 * 落地物是 [EncryptedSharedPreferences]：key 用 AES256-SIV、value 用 AES256-GCM，
 * 主密钥由 Android Keystore 持有（应用卸载即销毁，导出备份也解不开）。
 * 文件名与 `:app` 的原生壳分开（`oc_webhost_secrets`），两个壳同机并存互不干扰。
 *
 * 已知代价：`android:allowBackup="false"`（见 manifest）必须保持——一旦允许备份，
 * 备份里的密文在另一台设备上没有同一把 Keystore 密钥。
 *
 * ## UX6 · 凭据的职责边界（重要）
 *
 * 本类**只存非秘密的宿主偏好**：目前唯一的生产键是 [KEY_BACKEND_URL]
 * （"应用壳从哪个后端取官方 UI"的入口地址，不是秘密）。
 * 服务器口令**不存在这里** —— 认证全由官方 UI 与后端之间协商
 * （官方「设置 → 服务器」的密码字段，存页面自己的 localStorage，
 * 以 Basic `opencode:<password>` 直接发给后端，见 `_upstream` 的
 * `packages/app/src/runtime/server/api.ts:5-7`）。
 * 旧架构里"宿主替用户保管口令、转发时注入认证头"的那套机制
 * 已随回环代理一并删除：宿主不再有"代表用户持有口令"的角色，
 * 也就没有"把口令散给页面"的风险面。
 */
object SecretStore {
    private const val FILE = "oc_webhost_secrets"
    private const val TAG = "OcWebHost/SecretStore"

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

    /** @return null 表示没有这个键。空串视为"没有"，避免把空值当成有效配置。 */
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
