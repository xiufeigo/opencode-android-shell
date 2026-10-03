package com.xiufeigo.opencodeshell.v2

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Integration **OAuth** connection routes, held as extension functions over
 * [V2Api]'s `internal` HTTP seam (same OkHttp client, same
 * `basicAuthorizationHeader`, same [V2Api.ApiException] on non-2xx) instead of
 * [V2Api] members — that class is owned by another change.
 *
 * | route | contract |
 * |---|---|
 * | `POST /api/integration/{integrationID}/connect/oauth` | `{methodID,answer?,label?}` → `{location,data:Attempt}` (`integration.ts:83-102`) |
 * | `GET /api/integration/{integrationID}/connect/oauth/{attemptID}` | → `{location,data:AttemptStatus}` (`integration.ts:104-118`) |
 * | `DELETE /api/integration/{integrationID}/connect/oauth/{attemptID}` | empty body → **204** (`integration.ts:141-154`) |
 * | `POST /api/integration/{integrationID}/connect/oauth/{attemptID}/complete` | `{code?}` → **204** (`integration.ts:120-139`) |
 *
 * All four carry the deep-object `location[directory]` query (`LocationQuery`,
 * `integration.ts:12`). Success bodies are `UNVERIFIED` live — no write hits the
 * running server (asserted on `MockWebServer`), shapes come from the live 2.0.11
 * openapi.json and the schemas cited per DTO; the 404 bodies are **Measured
 * live** 2026-09-23. Blocking; call on an IO dispatcher.
 */

private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

/**
 * `POST /api/integration/{integrationID}/connect/oauth` body `{methodID,
 * answer?, label?}` → `{location,data:Attempt}` — starts an OAuth attempt and
 * returns its authorization URL (`integration.ts:83-102`).
 *
 * @param methodID `Integration.MethodID` — the `id` of one of the integration's
 *   `type:"oauth"` methods (`schema/src/integration.ts:18-24`); Measured live on
 *   `GET /api/integration/opencode` (`methods[].id == "device"`).
 * @param answer `Form.Answer` = `Record(String, String|Number|Boolean|
 *   Array(String))` for the method's optional `form`
 *   (`schema/src/form.ts:139-147`); `null` omits the key (`optional`,
 *   `integration.ts:87`).
 * @param label optional credential label (`integration.ts:89`); `null` omits.
 *
 * [IntegrationOauthAttempt.mode] picks the follow-up: `"code"` waits for the
 * user and needs [integrationOauthComplete]; `"auto"` only polls
 * [integrationOauthStatus] (`integration.ts:90`). Errors: 400
 * `InvalidRequestError` (`integration.ts:92`) — [V2Api.ApiException].
 */
fun V2Api.integrationOauthConnect(
    integrationID: String,
    methodID: String,
    answer: Map<String, Any?>? = null,
    label: String? = null,
    locationDirectory: String? = null,
): IntegrationOauthAttempt {
    val body = JSONObject().put("methodID", methodID)
    if (answer != null) {
        val fields = JSONObject()
        for ((key, value) in answer) fields.put(key, value)
        body.put("answer", fields)
    }
    if (label != null) body.put("label", label)
    return IntegrationOauthAttempt.fromJson(
        dataObject(
            callBody(
                builder("integration/$integrationID/connect/oauth", true) { located(it, locationDirectory) }
                    .post(body.toString().toRequestBody(JSON_MEDIA))
                    .build(),
            ),
        ),
    )
}

/**
 * `GET /api/integration/{integrationID}/connect/oauth/{attemptID}` →
 * `{location,data:AttemptStatus}` — polls one OAuth attempt
 * (`integration.ts:104-118`; the web client re-polls every second while
 * `status == "pending"`).
 *
 * Errors (**Measured live** 2026-09-23 on 2.0.11 `:4095`, surfaced as
 * [V2Api.ApiException] with the `_tag` in the message): 404
 * `{"_tag":"IntegrationNotFoundError","integrationID":…}` for an unknown
 * [integrationID]; 404 `{"_tag":"IntegrationAttemptNotFoundError",
 * "attemptID":…}` for an unknown [attemptID] on a known integration.
 */
fun V2Api.integrationOauthStatus(
    integrationID: String,
    attemptID: String,
    locationDirectory: String? = null,
): IntegrationOauthAttemptStatus = IntegrationOauthAttemptStatus.fromJson(
    dataObject(
        callBody(
            builder("integration/$integrationID/connect/oauth/$attemptID", true) { located(it, locationDirectory) }
                .get()
                .build(),
        ),
    ),
)

/**
 * `DELETE /api/integration/{integrationID}/connect/oauth/{attemptID}` → **204**
 * — cancels a pending attempt and releases its resources
 * (`integration.ts:141-154`). Empty request body. Errors: 400
 * `InvalidRequestError`, 401 (openapi; `integration.ts:141-145` declares none)
 * — [V2Api.ApiException].
 */
fun V2Api.integrationOauthCancel(
    integrationID: String,
    attemptID: String,
    locationDirectory: String? = null,
) {
    callUnit(
        builder("integration/$integrationID/connect/oauth/$attemptID", true) { located(it, locationDirectory) }
            .delete()
            .build(),
    )
}

/**
 * `POST /api/integration/{integrationID}/connect/oauth/{attemptID}/complete`
 * body `{code?}` → **204** — hands the user-pasted code to a `mode:"code"`
 * attempt and stores the resulting credential (`integration.ts:120-139`).
 *
 * The body object is always sent (openapi `requestBody` is required); [code] is
 * `optional(Schema.String)` (`integration.ts:126`) and `null` omits the key.
 * Errors: 400 `InvalidRequestError`, 404 `IntegrationNotFoundError` |
 * `IntegrationAttemptNotFoundError` (`integration.ts:128`) — [V2Api.ApiException].
 */
fun V2Api.integrationOauthComplete(
    integrationID: String,
    attemptID: String,
    code: String? = null,
    locationDirectory: String? = null,
) {
    val body = JSONObject()
    if (code != null) body.put("code", code)
    callUnit(
        builder("integration/$integrationID/connect/oauth/$attemptID/complete", true) {
            located(it, locationDirectory)
        }.post(body.toString().toRequestBody(JSON_MEDIA)).build(),
    )
}

/**
 * `Integration.Attempt` — `{attemptID, url, instructions, mode, time:{created,
 * expires}}` (`schema/src/integration.ts:85-91`; openapi
 * `Integration.AttemptEncoded`).
 *
 * [mode] is `Schema.Literals(["auto","code"])` (`integration.ts:89`): `"code"`
 * waits for [integrationOauthComplete], `"auto"` completes by itself while
 * polling [integrationOauthStatus]. [timeCreated]/[timeExpires] are epoch
 * millis; `Schema.Number` widens on the wire to `"Infinity"`/`"-Infinity"`/
 * `"NaN"` strings, coerced here to the matching `Double` constants.
 */
data class IntegrationOauthAttempt(
    val attemptID: String,
    val url: String,
    val instructions: String,
    val mode: String,
    val timeCreated: Double,
    val timeExpires: Double,
    val raw: JSONObject,
) {
    companion object {
        fun fromJson(obj: JSONObject): IntegrationOauthAttempt {
            val time = obj.optJSONObject("time")
            return IntegrationOauthAttempt(
                attemptID = obj.optString("attemptID", ""),
                url = obj.optString("url", ""),
                instructions = obj.optString("instructions", ""),
                mode = obj.optString("mode", ""),
                timeCreated = timeDouble(time, "created"),
                timeExpires = timeDouble(time, "expires"),
                raw = obj,
            )
        }
    }
}

/**
 * `Integration.AttemptStatus` — a flat tagged union on [status]
 * (`schema/src/integration.ts:93-101`): `pending` | `complete` | `failed` |
 * `expired`, with [message] only on `failed` (`integration.ts:96`) and `time` on
 * every variant. Unknown [status] values pass through verbatim; [raw] keeps the
 * whole `data` object.
 */
data class IntegrationOauthAttemptStatus(
    val status: String,
    val message: String?,
    val timeCreated: Double,
    val timeExpires: Double,
    val raw: JSONObject,
) {
    companion object {
        fun fromJson(obj: JSONObject): IntegrationOauthAttemptStatus {
            val time = obj.optJSONObject("time")
            return IntegrationOauthAttemptStatus(
                status = obj.optString("status", ""),
                message = optStringOrNull(obj, "message"),
                timeCreated = timeDouble(time, "created"),
                timeExpires = timeDouble(time, "expires"),
                raw = obj,
            )
        }
    }
}

/**
 * `AttemptTime.{created,expires}` (`schema/src/integration.ts:80-83`):
 * `Schema.Number`, which the openapi encoding widens to `number | "Infinity" |
 * "-Infinity" | "NaN"` — those strings parse back to the matching `Double`
 * constants. Missing field or object → `0.0`.
 */
private fun timeDouble(time: JSONObject?, key: String): Double {
    val value = time?.opt(key) ?: return 0.0
    if (value is Number) return value.toDouble()
    return when (val text = value.toString()) {
        "Infinity" -> Double.POSITIVE_INFINITY
        "-Infinity" -> Double.NEGATIVE_INFINITY
        "NaN" -> Double.NaN
        else -> text.toDoubleOrNull() ?: 0.0
    }
}
