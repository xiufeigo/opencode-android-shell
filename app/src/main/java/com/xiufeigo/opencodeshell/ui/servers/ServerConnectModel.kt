package com.xiufeigo.opencodeshell.ui.servers

import com.xiufeigo.opencodeshell.ServerConfigStore
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Wave 5-E1 — the pure half of the first-run connect page.
 *
 * Upstream anchors, transcribed literally:
 *
 * * `normalizeServerUrl` — `runtime/server/registry.tsx:15-20`
 *   (`trim` → add `http://` when no scheme → strip trailing slashes);
 * * `serverAddress` — `servers/connect/pairing.ts:12-20`
 *   (reject a non-http scheme, a URL that does not parse, an embedded
 *   user/password, a query or a hash);
 * * the route fallback — `shell/routes/routes.tsx:79`
 *   (`<Show when={servers.list.length > 0} fallback={<ConnectServerScreen/>}>`).
 */

/**
 * `normalizeServerUrl`: `localhost:4096` → `http://localhost:4096`,
 * `https://host///` → `https://host`. Blank input has no address.
 *
 * The scheme test is **case-sensitive** on purpose: upstream's `/^https?:\/\//`
 * is too, so `HTTP://host` is not treated as a scheme and
 * [serverAddress] rejects it instead of guessing.
 */
fun normalizeServerUrl(input: String): String? {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return null
    val withProtocol = if (HTTP_SCHEME_PREFIX.containsMatchIn(trimmed)) trimmed else "http://$trimmed"
    return withProtocol.trimEnd('/')
}

private val HTTP_SCHEME_PREFIX = Regex("^https?://")

/**
 * The address the connect page may send, or `null` when the field is not a
 * usable server address.
 *
 * **Documented deviation (one bug not carried over):** upstream's
 * `serverAddress("http://")` returns the string `"http:"` — `normalizeServerUrl`
 * strips the trailing slashes *after* the scheme, and `URL.canParse("http:")` is
 * true in every browser. OkHttp requires a host, so this returns `null` and the
 * form shows "Enter a valid HTTP or HTTPS server address." instead of trying to
 * reach `http:`.
 */
fun serverAddress(value: String): String? {
    if (value.contains("://") && !value.startsWith("http://") && !value.startsWith("https://")) return null
    val normalized = normalizeServerUrl(value) ?: return null
    val url = normalized.toHttpUrlOrNull() ?: return null
    if (url.scheme != "http" && url.scheme != "https") return null
    if (url.username.isNotEmpty() || url.password.isNotEmpty()) return null
    if (url.query != null || url.fragment != null) return null
    return normalized
}

/**
 * E1 gate: the shell has no usable server, so the connect page takes the whole
 * screen. `ServerConfigStore.listServers()` already drops incomplete entries, so
 * this only guards the "I re-parsed the raw JSON myself" path — it is the
 * documented predicate, and it is what the JVM test pins.
 */
fun requiresConnectScreen(servers: List<ServerConfigStore.Server>): Boolean =
    servers.none { it.isComplete }

/**
 * The row the connect page persists for a successful address.
 *
 * Upstream adds `{ type: "http", http: { url, password } }` with **no username**
 * (`servers/connect/screen.tsx:38-44`): the Basic-auth user is the literal
 * `opencode` (`runtime/server/api.ts:6`). An empty password stays empty, which
 * means "send no `Authorization` header at all" (`runtime/server/health.ts:81-85`).
 */
fun connectedServer(url: String, password: String?): ServerConfigStore.Server = ServerConfigStore.Server(
    url = url,
    username = null,
    password = password?.takeIf { it.isNotEmpty() },
)

/**
 * Rewrites the configured list for a new connection: the URL replaces an
 * existing row (so a re-connect updates the credential instead of adding a
 * duplicate) and is appended when it is new.
 */
fun withConnectedServer(
    existing: List<ServerConfigStore.Server>,
    url: String,
    password: String?,
): List<ServerConfigStore.Server> = existing.filterNot { it.url == url } + connectedServer(url, password)
