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
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * Wave 5-E1 / D3 — the native mirror of upstream's `checkServerHealth`
 * (`_upstream/packages/app/src/runtime/server/health.ts:72-107`).
 *
 * Upstream calls the SDK's `server.info()` on an HTTP connection
 * (`health.ts:93-99`) with **no** timeout of its own
 * (`defaultTimeoutMs = 30_000`, `health.ts:17`) and two transport retries. For
 * the Android shell the probe is used from two places — the first-run connect
 * page (E1) and the settings server list (D3) — so it is deliberately simpler:
 *
 * * one `GET` against the health endpoint, with a hard per-request budget
 *   ([HEALTH_TIMEOUT_MS], `connectTimeout` + `readTimeout` + `callTimeout`);
 * * **no** retry loop: a dead host must not hold a settings open for 30 s;
 * * failures are returned as data ([HealthResult] + [HealthFailure]), never
 *   thrown, so the settings list stays silent and the connect page renders a
 *   readable sentence instead of an exception.
 *
 * The Basic-auth rule is upstream's, shared with [com.xiufeigo.opencodeshell.OpenCodeClient]
 * through [basicAuthorizationHeader] (`health.ts:81-85`: `Authorization` only
 * when a password exists, username defaulting to the literal `opencode`).
 *
 * **Two implementations, one wire rule (for the Lead):** `OpenCodeClient.health()`
 * (`OpenCodeClient.kt:1071`) also calls `GET /global/health`, but it shares the
 * client's 30 s read timeout, throws on any failure and can only ever probe the
 * *active* server. This probe is the one wired into the UI; see
 * `research/wave5_connect.md` §"合并建议" for how to collapse the two.
 */

/** `GET /global/health` — the endpoint upstream's `server.info()` reaches. */
const val HEALTH_PATH: String = "/global/health"

/**
 * Older/aliased builds answer only the v1-shaped path; the vendored web bundle
 * checks both (`ConnectCorsTest`, `assets/opencode-app/assets`). The probe only
 * falls back when the first path is *missing* (404/405) — a 500 is a real
 * verdict, not a routing difference.
 */
const val HEALTH_PATH_LEGACY: String = "/api/health"

/**
 * `GET /api/info` — the v2 probe leg, judged by [V2Capability.decideInfo].
 * Consulted only when the v1 answer could be a v2 server (see
 * [ServerHealthProbe.check]).
 */
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
 * `200 {"healthy":true,"version":"1.18.31"}` is the live response of opencode
 * 1.18.31 (verified against the local server); upstream only reads `version`
 * (`health.ts:99`) and treats any successful decode as healthy, so a document
 * that explicitly says `healthy: false` is the one case upstream cannot express
 * and this probe reports as UNHEALTHY.
 */
fun healthResult(code: Int, body: String?): HealthResult = when {
    code == 401 || code == 403 -> unhealthy(HealthFailure.Unauthorized)
    code !in 200..299 -> unhealthy(HealthFailure.HttpStatus(code))
    else -> healthBody(body)
}

/** Parses the health document of a `2xx` response. */
fun healthBody(body: String?): HealthResult {
    val text = body?.trim().orEmpty()
    if (text.isEmpty()) return unhealthy(HealthFailure.InvalidResponse)
    val json = try {
        JSONObject(text)
    } catch (_: JSONException) {
        return unhealthy(HealthFailure.InvalidResponse)
    }
    if (json.has("healthy") && !json.optBoolean("healthy", false)) return unhealthy(HealthFailure.NotHealthy)
    return HealthResult(
        state = ServerHealth.HEALTHY,
        version = json.optString("version", "").takeIf { it.isNotEmpty() },
    )
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
     * `GET /global/health` for [target], with the legacy path tried only when
     * the first one is missing.
     *
     * When both v1 legs fail in a way that is consistent with a **v2 server**
     * (SPA HTML on `/global/health` ⇒ [HealthFailure.InvalidResponse], or an
     * auth wall ⇒ [HealthFailure.Unauthorized]), one extra leg runs:
     * `GET /api/info` judged by [V2Capability.decideInfo] — a `2.x` JSON
     * version is HEALTHY, a 401/403 stays Unauthorized, anything else keeps the
     * original v1 verdict. Without this leg an auth-walled 2.x server is
     * rejected as "not an OpenCode server" even though the app speaks v2.
     *
     * Never throws: every transport error becomes an UNHEALTHY [HealthResult].
     */
    fun check(target: HealthTarget): HealthResult {
        var missing: HealthResult? = null
        for (path in listOf(HEALTH_PATH, HEALTH_PATH_LEGACY)) {
            val url = healthUrl(target.url, path)
                ?: return unhealthy(HealthFailure.InvalidUrl)
            val result = attempt(url, target)
            if (result.healthy) return result
            if (result.failure is HealthFailure.HttpStatus && result.failure.code in MISSING_STATUS) {
                // Remember the *first* missing path; a later 405 must not
                // overwrite the more specific 404.
                if (missing == null) missing = result
                continue
            }
            return v2Fallback(target, result)
        }
        // Both paths answered 404/405: report the first verdict, not a mash-up.
        return v2Fallback(target, missing ?: unhealthy(HealthFailure.InvalidUrl))
    }

    /**
     * The v2 leg of [check]: consulted only when the v1 answer could actually be
     * a v2 server (its `/global/health` serves the SPA shell, or the whole host
     * sits behind Basic auth). A 404/405 never qualifies — v2 answers
     * `/global/health` with 200 HTML, so "missing" really means missing. A
     * network error here keeps the v1 verdict — the first failure is the
     * better sentence.
     */
    private fun v2Fallback(target: HealthTarget, v1: HealthResult): HealthResult {
        if (v1.failure !is HealthFailure.InvalidResponse &&
            v1.failure !is HealthFailure.Unauthorized
        ) {
            return v1
        }
        val url = healthUrl(target.url, V2_INFO_PATH) ?: return v1
        val builder = Request.Builder().url(url).get()
        basicAuthorizationHeader(target.username, target.password)
            ?.let { builder.header("Authorization", it) }
        val raw = try {
            http.newCall(builder.build()).execute().use { response ->
                Triple(response.code, response.header("Content-Type"), response.body?.string())
            }
        } catch (_: IOException) {
            return v1
        }
        if (raw.first == 401 || raw.first == 403) return unhealthy(HealthFailure.Unauthorized)
        val info = V2Capability.decideInfo(raw.first, raw.second, raw.third)
        return if (info.generation == ServerGeneration.V2) {
            HealthResult(state = ServerHealth.HEALTHY, version = info.version)
        } else {
            v1
        }
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
        basicAuthorizationHeader(target.username, target.password)
            ?.let { builder.header("Authorization", it) }
        return try {
            http.newCall(builder.build()).execute().use { response ->
                healthResult(response.code, response.body?.string())
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

    private companion object {
        /** "This path does not exist", as opposed to a real server verdict. */
        val MISSING_STATUS = setOf(404, 405)
    }
}

/** `http://host:4096` + `/global/health`, or `null` when the base is unusable. */
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
