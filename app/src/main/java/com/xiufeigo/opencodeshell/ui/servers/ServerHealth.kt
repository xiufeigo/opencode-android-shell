package com.xiufeigo.opencodeshell.ui.servers

import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.basicAuthorizationHeader
import com.xiufeigo.opencodeshell.ui.settings.ServerHealth
import com.xiufeigo.opencodeshell.ui.settings.ServerListItem
import com.xiufeigo.opencodeshell.v2.ServerGeneration
import com.xiufeigo.opencodeshell.v2.V2Capability
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * Wave 5-E1 / D3 — the native mirror of upstream's `checkServerHealth`
 * (`_upstream/packages/app/src/runtime/server/health.ts:72-107`).
 *
 * The probe is used from two places — the first-run connect page (E1) and the
 * settings server list (D3) — so it is deliberately simple:
 *
 * * one `GET /api/info` with a hard per-request budget
 *   ([HEALTH_TIMEOUT_MS], `connectTimeout` + `readTimeout` + `callTimeout`);
 * * **no** retry loop: a dead host must not hold a settings open for 30 s;
 * * failures are returned as data ([HealthResult] + [HealthFailure]), never
 *   thrown, so the settings list stays silent and the connect page renders a
 *   readable sentence instead of an exception.
 *
 * The Basic-auth rule is upstream's, shared with [com.xiufeigo.opencodeshell.OpenCodeClient]
 * through [basicAuthorizationHeader] (`health.ts:81-85`: `Authorization` only
 * when a password exists, username defaulting to the literal `opencode`).
 */

/** `GET /api/info` — the single probe leg, judged by [V2Capability.decideInfo]. */
const val V2_INFO_PATH: String = "/api/info"

/** Probe budget. One request, ≤3 s (E1/D3 requirement). */
const val HEALTH_TIMEOUT_MS: Long = 3_000L

/** One reachable server: the address plus the optional Basic-auth credential. */
data class HealthTarget(
    val url: String,
    val username: String? = null,
    val password: String? = null,
)

/**
 * The credential a pre-save probe must send — exactly the one the save path
 * will keep. `SettingsServerBridge.replace` stores `typed ?: existing`, so an
 * edit that does not retype the secret has to probe with the **stored**
 * password; probing with the blank form field sent no `Authorization` at all
 * and the server's `401` was reported as "the server rejected that password".
 *
 * The username follows the form (and the save path) verbatim: empty becomes
 * `null`, which [basicAuthorizationHeader] and `ServerConfigStore.fromHttp`
 * both default to the literal `opencode`.
 */
fun probeTarget(
    url: String,
    username: String?,
    typedPassword: String?,
    storedPassword: String?,
): HealthTarget = HealthTarget(
    url = url,
    username = username?.trim()?.takeIf { it.isNotEmpty() },
    password = typedPassword?.takeIf { it.isNotEmpty() }
        ?: storedPassword?.takeIf { it.isNotEmpty() },
)

/**
 * Why a probe reported [ServerHealth.UNHEALTHY]. The connect page renders these
 * as sentences ([healthFailureMessage]); the settings list ignores them.
 */
sealed interface HealthFailure {
    /** The address is not an absolute `http(s)://` URL — nothing was sent. */
    data object InvalidUrl : HealthFailure

    /** Connect/DNS/TLS failure: the host did not answer at all. */
    data object Unreachable : HealthFailure

    /** The host answered too late (probe budget exhausted). */
    data object Timeout : HealthFailure

    /**
     * `401`/`403` with **no** `Authorization` on the wire: the server wants a
     * credential and this probe deliberately sent none (empty password). The
     * sentence has to say "enter username and password", not "rejected".
     */
    data object MissingCredentials : HealthFailure

    /** `401`/`403` — the server is up but rejected the credential. */
    data object Unauthorized : HealthFailure

    /** A `2xx` whose payload is not the health document (proxy/captive page). */
    data object InvalidResponse : HealthFailure

    /** A well-formed health document that reports `healthy: false`. */
    data object NotHealthy : HealthFailure

    /** Any other non-2xx status. */
    data class HttpStatus(val code: Int) : HealthFailure
}

/**
 * One probe outcome. [state] is never [ServerHealth.UNKNOWN]: "unknown" means
 * *not probed yet* (upstream `health === undefined` → weak dot,
 * `servers/registry/row.tsx:122-129`), which the UI derives from the absence of
 * a result.
 */
data class HealthResult(
    val state: ServerHealth,
    val version: String? = null,
    val failure: HealthFailure? = null,
) {
    val healthy: Boolean get() = state == ServerHealth.HEALTHY
}

/** A user-facing sentence: a string resource plus its optional format argument. */
data class HealthMessage(val resId: Int, val formatArg: String? = null)

/**
 * Maps an HTTP outcome onto the tri-state. Pure — the JVM test asserts every
 * branch without a socket.
 *
 * The 2xx body is judged by [V2Capability.decideInfo]: a confirmed v2
 * `/api/info` document is HEALTHY; anything else (SPA shell, non-JSON,
 * non-2.x version) is UNHEALTHY with [HealthFailure.InvalidResponse].
 */
fun healthResult(
    code: Int,
    contentType: String?,
    body: String?,
    credentialsSent: Boolean = true,
): HealthResult = when {
    // `401` is two different stories and must not share a sentence: nothing was
    // offered (empty password ⇒ [basicAuthorizationHeader] sends no header ⇒
    // "enter username and password") vs. a credential the server refused
    // ("rejected that password").
    code == 401 || code == 403 -> unhealthy(
        if (credentialsSent) HealthFailure.Unauthorized else HealthFailure.MissingCredentials,
    )
    code !in 200..299 -> unhealthy(HealthFailure.HttpStatus(code))
    else -> healthBody(contentType, body)
}

/** Parses the `/api/info` document of a `2xx` response via [V2Capability.decideInfo]. */
fun healthBody(contentType: String?, body: String?): HealthResult {
    val info = V2Capability.decideInfo(200, contentType, body)
    return if (info.generation == ServerGeneration.V2) {
        HealthResult(state = ServerHealth.HEALTHY, version = info.version)
    } else {
        unhealthy(HealthFailure.InvalidResponse)
    }
}

private fun unhealthy(failure: HealthFailure) = HealthResult(ServerHealth.UNHEALTHY, failure = failure)

/**
 * Readable copy for a failed probe.
 *
 * `server.connect.failed` / `server.connect.address.invalid` are the two
 * upstream sentences (`runtime/i18n/en.ts:428-429`); the finer-grained variants
 * are Android-only, because upstream's probe collapses every transport error
 * into one message while a hand-held phone user needs to know whether the
 * address, the network or the password was wrong.
 */
fun healthFailureMessage(failure: HealthFailure?): HealthMessage = when (failure) {
    // Upstream's own address sentence (`server.connect.address.invalid`): a URL
    // the form accepted but the probe could not turn into a request is exactly
    // what that string says.
    HealthFailure.InvalidUrl -> HealthMessage(R.string.oc_connect_address_invalid)
    HealthFailure.Timeout -> HealthMessage(R.string.oc_connect_error_timeout)
    // Nothing was offered (`401` with no `Authorization` on the wire): the
    // honest sentence is "enter username and password", never "rejected".
    HealthFailure.MissingCredentials -> HealthMessage(R.string.oc_server_error_auth)
    HealthFailure.Unauthorized -> HealthMessage(R.string.oc_connect_error_auth)
    HealthFailure.NotHealthy -> HealthMessage(R.string.oc_connect_error_unhealthy)
    HealthFailure.InvalidResponse -> HealthMessage(R.string.oc_connect_error_invalid_response)
    is HealthFailure.HttpStatus ->
        HealthMessage(R.string.oc_connect_error_status, failure.code.toString())

    // `null` only happens for a result the caller fabricated; the upstream
    // generic sentence is the honest fallback there too.
    HealthFailure.Unreachable, null -> HealthMessage(R.string.oc_connect_failed)
}

/**
 * Folds a probe result into a settings row (D3).
 *
 * A `null` result (never probed, or a probe that has not answered yet) keeps
 * the row at its stored state — `UNKNOWN` for every freshly built bridge row —
 * so the dot is never claimed healthy before the probe says so.
 */
fun ServerListItem.withHealth(result: HealthResult?): ServerListItem = when {
    result == null -> this
    result.healthy -> copy(health = ServerHealth.HEALTHY, version = result.version ?: version)
    else -> copy(health = ServerHealth.UNHEALTHY)
}

/** What `ServersPanel` needs to probe every configured server (implemented by
 * `SettingsServerBridge`, so the `ServerBridge` read/write seam stays unchanged). */
interface ServerHealthSource {
    /** Every server that can be probed, in list order (active/default first is the store's order). */
    fun healthTargets(): List<HealthTarget>
}

/**
 * The probe itself. Blocking on purpose: both call sites run it on
 * `Dispatchers.IO`, and a blocking call is what makes the whole thing testable
 * against `MockWebServer` without a coroutine dispatcher.
 */
class ServerHealthProbe(
    private val timeoutMs: Long = HEALTH_TIMEOUT_MS,
    private val http: OkHttpClient = probeClient(timeoutMs),
) {
    /**
     * `GET /api/info` for [target], judged by [V2Capability.decideInfo].
     *
     * Never throws: every transport error becomes an UNHEALTHY [HealthResult].
     */
    fun check(target: HealthTarget): HealthResult {
        val url = healthUrl(target.url, V2_INFO_PATH)
            ?: return unhealthy(HealthFailure.InvalidUrl)
        return attempt(url, target)
    }

    /**
     * Probes every target **sequentially**. A settings panel shows the list
     * immediately and the dots fill in, so the only cost of not fanning out is
     * latency on the later rows; the upside is one connection at a time against
     * a phone-tethered server.
     */
    fun checkAll(targets: List<HealthTarget>): Map<String, HealthResult> =
        targets.associate { it.url to check(it) }

    private fun attempt(url: HttpUrl, target: HealthTarget): HealthResult {
        val builder = Request.Builder().url(url).get()
        val authorization = basicAuthorizationHeader(target.username, target.password)
        authorization?.let { builder.header("Authorization", it) }
        return try {
            http.newCall(builder.build()).execute().use { response ->
                healthResult(
                    response.code,
                    response.header("Content-Type"),
                    response.body?.string(),
                    credentialsSent = authorization != null,
                )
            }
        } catch (_: SocketTimeoutException) {
            unhealthy(HealthFailure.Timeout)
        } catch (_: InterruptedIOException) {
            // `callTimeout` surfaces as InterruptedIOException("timeout"), which
            // is not a SocketTimeoutException. Both mean "no answer in budget".
            unhealthy(HealthFailure.Timeout)
        } catch (_: IOException) {
            unhealthy(HealthFailure.Unreachable)
        }
    }
}

/** `http://host:4095` + `/api/info`, or `null` when the base is unusable. */
internal fun healthUrl(baseUrl: String, path: String): HttpUrl? {
    val base = baseUrl.trim().trimEnd('/')
    if (base.isEmpty()) return null
    if (!base.startsWith("http://") && !base.startsWith("https://")) return null
    return (base + path).toHttpUrlOrNull()
}

/** The probe's own client: never the shared `OpenCodeClient` one (30 s reads). */
internal fun probeClient(timeoutMs: Long): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
    .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
    .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
    .build()
