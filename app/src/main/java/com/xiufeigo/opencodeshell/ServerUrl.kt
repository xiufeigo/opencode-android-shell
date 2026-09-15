package com.xiufeigo.opencodeshell

import java.net.URI

data class ServerUrl(
    val value: String,
) {
    companion object {
        fun parse(raw: String): Result<ServerUrl> {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) {
                return Result.failure(IllegalArgumentException("Server URL is required"))
            }
            val uri = try {
                URI(trimmed)
            } catch (e: Exception) {
                return Result.failure(IllegalArgumentException("Invalid URL", e))
            }
            val scheme = uri.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") {
                return Result.failure(
                    IllegalArgumentException("URL must start with http:// or https://"),
                )
            }
            if (uri.host.isNullOrBlank()) {
                return Result.failure(IllegalArgumentException("URL must include a host"))
            }
            val normalized = trimmed.trimEnd('/')
            return Result.success(ServerUrl(normalized))
        }
    }
}
