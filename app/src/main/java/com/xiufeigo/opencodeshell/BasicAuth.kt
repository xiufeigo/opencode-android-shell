package com.xiufeigo.opencodeshell

import java.util.Base64

/**
 * Basic-auth header for an opencode server, or `null` when nothing should be
 * sent.
 *
 * Upstream derives the credential from the **password alone**
 * (`_upstream/packages/app/src/runtime/server/api.ts:6` — `btoa("opencode:" +
 * password)`): the username is the literal `opencode`, not a user-supplied
 * field. With no password, upstream sends **no** `Authorization` header at all
 * (`_upstream/packages/app/src/runtime/server/health.ts:83`).
 *
 * Two call sites share this rule — the REST client (`OpenCodeClient`) and the
 * SSE stream (`EventStream`) — because they used to drift: both built
 * `"$username:$password"`, which interpolated a literal `null` whenever one
 * half was unset. A server entry with a typed username and an empty password
 * (a real configuration: the add-server form keeps the username) therefore sent
 * `Basic base64("user:null")`.
 *
 * @param username optional compatibility fallback; empty/null falls back to
 *   upstream's literal `opencode`.
 * @param password the secret. Empty/null means "no credential".
 */
internal fun basicAuthorizationHeader(username: String?, password: String?): String? {
    val secret = password?.takeIf { it.isNotEmpty() } ?: return null
    val user = username?.takeIf { it.isNotEmpty() } ?: DEFAULT_BASIC_USERNAME
    val credentials = "$user:$secret"
    return "Basic " + Base64.getEncoder().encodeToString(credentials.toByteArray(Charsets.UTF_8))
}

/** Upstream hardcodes this user for HTTP basic auth. */
internal const val DEFAULT_BASIC_USERNAME = "opencode"
