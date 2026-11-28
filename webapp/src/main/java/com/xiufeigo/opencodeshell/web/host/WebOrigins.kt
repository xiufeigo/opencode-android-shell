package com.xiufeigo.opencodeshell.web.host

import java.net.URI

object WebOrigins {
    /** Compare only scheme, host, and effective port; paths and trailing slashes are irrelevant. */
    fun sameOrigin(a: String, b: String): Boolean = try {
        val ua = URI(a.trim())
        val ub = URI(b.trim())
        ua.scheme != null && ua.scheme == ub.scheme &&
            ua.host != null && ua.host.equals(ub.host, ignoreCase = true) &&
            effectivePort(ua) == effectivePort(ub)
    } catch (_: Throwable) {
        false
    }

    private fun effectivePort(uri: URI): Int = when {
        uri.port != -1 -> uri.port
        uri.scheme == "https" -> 443
        else -> 80
    }
}
