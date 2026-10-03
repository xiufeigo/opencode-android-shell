package com.xiufeigo.opencodeshell

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The OS half of flap recovery: forwards network-return signals to
 * [ChatViewModel.onNetworkAvailable], which owns the heal decision (and the
 * Terminal-tab bootstrap guard). Fires at most once per [COALESCE_MS] — the
 * platform emits `onAvailable` on every switch, and each heal branch dedupes
 * anyway, but there is no reason to forward a storm.
 */
class NetworkRecoveryWatch(context: Context, private val onAvailable: () -> Unit) {
    private val connectivity =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)

    @Volatile
    private var lastFireAt = 0L
    private val registered = AtomicBoolean(false)
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val now = System.currentTimeMillis()
            if (now - lastFireAt < COALESCE_MS) return
            lastFireAt = now
            onAvailable()
        }
    }

    fun start() {
        if (!registered.compareAndSet(false, true)) return
        try {
            connectivity?.registerDefaultNetworkCallback(callback)
        } catch (_: Exception) {
            registered.set(false)
        }
    }

    fun stop() {
        if (!registered.compareAndSet(true, false)) return
        try {
            connectivity?.unregisterNetworkCallback(callback)
        } catch (_: Exception) {
        }
    }

    companion object {
        internal const val COALESCE_MS = 2_000L
    }
}
