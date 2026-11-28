package com.xiufeigo.opencodeshell.web.host

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import android.webkit.PermissionRequest
import androidx.core.content.ContextCompat

/**
 * WebView camera permission state machine for the server-pairing scanner.
 *
 * The Activity owns the Android result launcher; this object owns the WebView-facing
 * contract: trusted origin only, video capture only, and no grant before the runtime
 * permission round-trip completes.
 */
class CameraPermissionHost(
    private val context: Context,
    private val isTrustedOrigin: (String) -> Boolean,
    private val requestCameraPermission: () -> Unit,
) {
    private var pendingRequest: PermissionRequest? = null
    private var inFlight = false

    fun onPermissionRequest(request: PermissionRequest) {
        val origin = request.origin?.toString()
        val trusted = origin != null && isTrustedOrigin(origin)
        val wanted = request.resources.filter { it == PermissionRequest.RESOURCE_VIDEO_CAPTURE }
        if (!trusted || wanted.isEmpty()) {
            Log.i(TAG, "camera permission DENIED origin=$origin resources=${request.resources.toList()}")
            request.deny()
            return
        }

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "camera permission GRANTED (already held) origin=$origin")
            request.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE))
            return
        }

        Log.i(TAG, "camera permission REQUEST origin=$origin (runtime grant pending)")
        if (inFlight) {
            Log.w(TAG, "camera permission request already pending")
            request.deny()
            return
        }
        pendingRequest = request
        inFlight = true
        requestCameraPermission()
    }

    fun onPermissionRequestCanceled(request: PermissionRequest) {
        if (pendingRequest === request) pendingRequest = null
    }


    fun denyPending() {
        pendingRequest?.deny()
        pendingRequest = null
        inFlight = false
    }

    fun onRuntimePermissionResult(granted: Boolean) {
        inFlight = false
        val request = pendingRequest
        pendingRequest = null
        if (request == null) {
            Log.i(TAG, "camera permission result=$granted but no pending request")
            return
        }
        if (granted) {
            Log.i(TAG, "camera permission GRANTED origin=${request.origin}")
            request.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE))
        } else {
            Log.i(TAG, "camera permission DENIED (user) origin=${request.origin}")
            request.deny()
        }
    }

    private companion object {
        const val TAG = "OcWebHost"
    }
}
