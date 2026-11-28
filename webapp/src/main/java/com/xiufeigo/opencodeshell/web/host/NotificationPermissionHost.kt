package com.xiufeigo.opencodeshell.web.host

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Notification-permission state machine for the Web shell.
 *
 * The Activity owns only the Android result launcher. This module owns permission
 * truth, page synchronization through `oc:notifyPerm`, proactive round-start
 * debouncing, and the decision of when a runtime request is needed.
 */
class NotificationPermissionHost(
    private val context: Context,
    private val isForeground: () -> Boolean,
    private val requestRuntimePermission: () -> Unit,
    private val evaluateJs: (String) -> Unit,
) {
    @Volatile
    private var notificationPermissionRequested: Boolean = false

    fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Log.i(TAG, "pre-13: no runtime permission needed")
            return
        }
        requestRuntimePermission()
    }

    fun onPermissionResult(granted: Boolean) {
        Log.i(TAG, "POST_NOTIFICATIONS granted=$granted")
        dispatch(if (granted) "granted" else "denied")
    }

    /** Host truth in the exact detail values consumed by the page-side Notification shim. */
    private fun notificationPermissionState(): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return "granted"
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        return if (granted) "granted" else "denied"
    }

    /** Push current host truth after page load; the shim reinstalls on every navigation. */
    fun pushNotificationPermission() {
        val state = notificationPermissionState()
        Log.i(TAG, "P11 push oc:notifyPerm=$state")
        dispatch(state)
    }

    fun requestNotificationPermissionOnRoundStart() {
        if (!isForeground()) return
        if (notificationPermissionRequested) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionRequested = true
            return
        }
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            notificationPermissionRequested = true
            return
        }
        notificationPermissionRequested = true
        Log.i(TAG, "P7 proactive POST_NOTIFICATIONS request on first round start (foreground)")
        requestNotificationPermission()
    }

    private fun dispatch(state: String) {
        evaluateJs(
            "window.dispatchEvent(new CustomEvent('oc:notifyPerm'," +
                "{detail:'$state'}));"
        )
    }

    private companion object {
        const val TAG = "OcWebHost"
    }
}
