package com.xiufeigo.opencodeshell

import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** Same-origin (or loopback) URL layout the embedded UI uses to reach a native OkHttp proxy. */
object ProxyPath {
    const val PREFIX = "/opencode-proxy/"

    data class Target(
        val backendBase: String,
        val upstreamPath: String,
        val query: String?,
    ) {
        fun upstreamUrl(): String {
            val path = if (upstreamPath.startsWith("/")) upstreamPath else "/$upstreamPath"
            return if (query.isNullOrEmpty()) "$backendBase$path" else "$backendBase$path?$query"
        }
    }

    fun encodeBackend(backendBase: String): String =
        URLEncoder.encode(backendBase.trim().trimEnd('/'), StandardCharsets.UTF_8.name())
            .replace("+", "%20")

    fun publicPrefix(token: String): String = "$PREFIX$token/"

    fun parse(rawPath: String, query: String?, expectedToken: String): Target? {
        val path = rawPath.substringBefore('#').ifEmpty { "/" }
        if (!path.startsWith(PREFIX)) return null
        val rest = path.removePrefix(PREFIX)
        val slash = rest.indexOf('/')
        if (slash <= 0) return null
        val token = rest.substring(0, slash)
        if (token != expectedToken) return null
        val afterToken = rest.substring(slash + 1)
        val nextSlash = afterToken.indexOf('/')
        val encodedBase: String
        val upstreamPath: String
        if (nextSlash < 0) {
            encodedBase = afterToken
            upstreamPath = "/"
        } else {
            encodedBase = afterToken.substring(0, nextSlash)
            upstreamPath = afterToken.substring(nextSlash).ifEmpty { "/" }
        }
        if (encodedBase.isEmpty()) return null
        val backend = try {
            URLDecoder.decode(encodedBase, StandardCharsets.UTF_8.name()).trim().trimEnd('/')
        } catch (_: Exception) {
            return null
        }
        if (!backend.startsWith("http://") && !backend.startsWith("https://")) return null
        return Target(backendBase = backend, upstreamPath = upstreamPath, query = query)
    }

    fun isProxyPath(path: String): Boolean = path.startsWith(PREFIX)
}
