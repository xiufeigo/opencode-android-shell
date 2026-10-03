package com.xiufeigo.opencodeshell.v2

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Integration detail + command/key authentication — upstream
 * `protocol/src/groups/integration.ts`:
 *
 * | route | contract |
 * |---|---|
 * | `GET /api/integration/{integrationID}` | `{"location":…,"data":Integration.Info}` (`integration.ts:29-44`) |
 * | `POST /api/integration/{integrationID}/connect/key` | body `{key, answer?, label?}` → **204** (`integration.ts:62-81`) |
 * | `POST /api/integration/{integrationID}/connect/command` | body `{methodID, label?}` → `{"location":…,"data":CommandAttempt}` (`integration.ts:155-174`) |
 * | `GET /api/integration/{integrationID}/connect/command/{attemptID}` | `{"location":…,"data":CommandAttemptStatus}` (`integration.ts:175-190`) |
 * | `DELETE /api/integration/{integrationID}/connect/command/{attemptID}` | **204** (`integration.ts:191-205`) |
 * | `POST /api/experimental/integration/wellknown` | body `{url}` → **204** (`integration.ts:45-61`) |
 *
 * Held as extension functions rather than [V2Api] members (parallel change to
 * `V2Api.kt`); they use the class's `internal` HTTP seam only — same OkHttp
 * client, same `basicAuthorizationHeader`, same [V2Api.ApiException] on non-2xx.
 * `GET /api/integration` (the list) stays [V2Api.integrations].
 *
 * Every route is location-scoped (`location[directory]`, deepObject). The path
 * segment the brief calls `commandID` is upstream `:attemptID`
 * (`Integration.AttemptID`, `con_…`); the parameter is named [attemptID] here.
 *
 * Measured live 2026-09-23 on 2.0.11: the detail envelope and the
 * `key`/`env`/`oauth` method tags, `credential` connections and the
 * `IntegrationNotFoundError` body. The `command` method tag, `env` connections
 * and all four write routes are `UNVERIFIED` live (openapi/`integration.ts`;
 * writes are never aimed at the real server).
 */

private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

/**
 * `GET /api/integration/{integrationID}` → `data` as [IntegrationDetail] —
 * one integration and its authentication methods (`integration.get`,
 * `integration.ts:29-44`). `404 {"_tag":"IntegrationNotFoundError",
 * "integrationID", "message"}` for an unknown id (measured live).
 *
 * Richer than the list row [V2Api.IntegrationInfo]: typed [IntegrationDetail.Method]
 * and [IntegrationDetail.Connection] so the settings sheet can drive
 * [integrationConnectKey] (the key method's `form`) and
 * [integrationConnectCommand] (a command method's `id`).
 */
fun V2Api.integrationDetail(
    integrationID: String,
    locationDirectory: String? = null,
): IntegrationDetail = IntegrationDetail.fromJson(
    dataObject(
        callBody(builder("integration/$integrationID", true) { located(it, locationDirectory) }.get().build()),
    ),
)

/**
 * `POST /api/integration/{integrationID}/connect/key` → **204** — run a key
 * authentication method and store the resulting credential
 * (`integration.connect.key`, `integration.ts:62-81`).
 *
 * | piece | contract |
 * |---|---|
 * | body | `{key}` plus optional `answer`/`label` (`additionalProperties:false`) |
 * | `key` | the API key/token itself; the method carries no `methodID` (`KeyMethod`, `schema/src/integration.ts:34-39`) |
 * | `answer` | `Form.Answer` `Record<string, Value>` (`schema/src/form.ts:146-147`), values `string\|number\|boolean\|string[]` — the `key` method's `form` fields; a `List` becomes a JSON array |
 * | `label` | display name of the stored connection; omitted when blank |
 * | errors | `404 IntegrationNotFoundError`, `400 InvalidRequestError` |
 *
 * `UNVERIFIED` live (write; contract is `integration.ts` + openapi.json).
 */
fun V2Api.integrationConnectKey(
    integrationID: String,
    key: String,
    answer: Map<String, Any?>? = null,
    label: String? = null,
    locationDirectory: String? = null,
) {
    val body = JSONObject().put("key", key)
    if (answer != null) body.put("answer", formAnswerJson(answer))
    if (!label.isNullOrBlank()) body.put("label", label)
    callUnit(
        builder("integration/$integrationID/connect/key", true) { located(it, locationDirectory) }
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build(),
    )
}

/**
 * `POST /api/integration/{integrationID}/connect/command` → `data` as
 * [IntegrationCommandAttempt] — start a command authentication attempt
 * (`integration.command.connect`, `integration.ts:155-174`).
 *
 * Body `{methodID, label?}` (`additionalProperties:false`): [methodID] is a
 * `type:"command"` method's `id` from [IntegrationDetail.methods]. Errors:
 * `404 IntegrationNotFoundError` | `IntegrationMethodNotFoundError`,
 * `400 InvalidRequestError`. `UNVERIFIED` live.
 */
fun V2Api.integrationConnectCommand(
    integrationID: String,
    methodID: String,
    label: String? = null,
    locationDirectory: String? = null,
): IntegrationCommandAttempt = IntegrationCommandAttempt.fromJson(
    dataObject(
        callBody(
            builder("integration/$integrationID/connect/command", true) { located(it, locationDirectory) }
                .post(commandBody(methodID, label).toString().toRequestBody(JSON_MEDIA))
                .build(),
        ),
    ),
)

/**
 * `GET /api/integration/{integrationID}/connect/command/{attemptID}` → `data`
 * as [IntegrationCommandStatus] — poll the status and output of a command
 * attempt (`integration.command.status`, `integration.ts:175-190`). Errors:
 * `404 IntegrationNotFoundError` | `IntegrationAttemptNotFoundError`.
 * `UNVERIFIED` live.
 */
fun V2Api.integrationCommandStatus(
    integrationID: String,
    attemptID: String,
    locationDirectory: String? = null,
): IntegrationCommandStatus = IntegrationCommandStatus.fromJson(
    dataObject(
        callBody(
            builder("integration/$integrationID/connect/command/$attemptID", true) { located(it, locationDirectory) }
                .get()
                .build(),
        ),
    ),
)

/**
 * `DELETE /api/integration/{integrationID}/connect/command/{attemptID}` →
 * **204** — cancel a command attempt and terminate its process
 * (`integration.command.cancel`, `integration.ts:191-205`). The endpoint
 * declares no `404` (unlike the GET); unknown ids surface as whatever the
 * server answers via [V2Api.ApiException]. `UNVERIFIED` live.
 */
fun V2Api.integrationCommandCancel(
    integrationID: String,
    attemptID: String,
    locationDirectory: String? = null,
) {
    callUnit(
        builder("integration/$integrationID/connect/command/$attemptID", true) { located(it, locationDirectory) }
            .delete()
            .build(),
    )
}

/**
 * `POST /api/experimental/integration/wellknown` body `{url}` → **204** —
 * discover and persist an experimental wellknown integration source
 * (`integration.wellknown.add`, `integration.ts:45-61`). POST, not GET.
 *
 * Sole wrapper for this wire route (the parallel experimental-misc duplicate
 * was removed); keep one call site at the UI layer. `UNVERIFIED` live.
 */
fun V2Api.integrationWellknownAdd(url: String, locationDirectory: String? = null) {
    callUnit(
        builder("experimental/integration/wellknown", true) { located(it, locationDirectory) }
            .post(JSONObject().put("url", url).toString().toRequestBody(JSON_MEDIA))
            .build(),
    )
}

private fun commandBody(methodID: String, label: String?): JSONObject {
    val body = JSONObject().put("methodID", methodID)
    if (!label.isNullOrBlank()) body.put("label", label)
    return body
}

/** `Form.Answer` values: `List` → JSON array so `string[]` survives both org.json impls. */
private fun formAnswerJson(answer: Map<String, Any?>): JSONObject {
    val json = JSONObject()
    for ((key, value) in answer) {
        json.put(key, if (value is Collection<*>) JSONArray(value) else value)
    }
    return json
}

/**
 * `Integration.Info` (`schema/src/integration.ts:65-71`) —
 * `{id, name, metadata?, methods[], connections[]}`. Measured live 2026-09-23
 * (`{"location":{"directory":…},"data":{…}}` envelope).
 */
data class IntegrationDetail(
    val id: String,
    val name: String,
    val metadata: JSONObject?,
    val methods: List<Method>,
    val connections: List<Connection>,
    val raw: JSONObject,
) {
    /**
     * `Integration.Method` (`schema/src/integration.ts:18-49`) — a flat tagged
     * union on [type], so [type] decides which fields are populated:
     * `"key"` → [label], [form]; `"command"` → [id], [label], [command];
     * `"oauth"` → [id], [label], [form]; `"env"` → [names]. Only
     * `command`/`oauth` carry a `methodID` ([id]); `key` connects by value
     * ([integrationConnectKey]) and `env` is picked up from the environment.
     */
    data class Method(
        val type: String,
        val id: String?,
        val label: String?,
        val form: JSONArray?,
        val command: List<String>,
        val names: List<String>,
        val raw: JSONObject,
    ) {
        companion object {
            fun fromJson(obj: JSONObject): Method = Method(
                type = obj.optString("type", ""),
                id = optStringOrNull(obj, "id"),
                label = optStringOrNull(obj, "label"),
                form = obj.optJSONArray("form"),
                command = v2StringList(obj.optJSONArray("command")),
                names = v2StringList(obj.optJSONArray("names")),
                raw = obj,
            )
        }
    }

    /**
     * `Connection.Info` (`schema/src/connection.ts:7-25`) — tagged union on
     * [type]: `"credential"` → [id], [label]; `"env"` → [name]. Measured live:
     * `{"type":"credential","id":"cred_…","label":"API key"}`.
     */
    data class Connection(
        val type: String,
        val id: String?,
        val label: String?,
        val name: String?,
        val raw: JSONObject,
    ) {
        companion object {
            fun fromJson(obj: JSONObject): Connection = Connection(
                type = obj.optString("type", ""),
                id = optStringOrNull(obj, "id"),
                label = optStringOrNull(obj, "label"),
                name = optStringOrNull(obj, "name"),
                raw = obj,
            )
        }
    }

    companion object {
        fun fromJson(obj: JSONObject): IntegrationDetail = IntegrationDetail(
            id = obj.optString("id", ""),
            name = obj.optString("name", ""),
            metadata = obj.optJSONObject("metadata"),
            methods = v2ObjectList(obj.optJSONArray("methods") ?: JSONArray()).map(Method::fromJson),
            connections = v2ObjectList(obj.optJSONArray("connections") ?: JSONArray()).map(Connection::fromJson),
            raw = obj,
        )
    }
}

/**
 * `Integration.CommandAttempt` (`schema/src/integration.ts:103-107`) —
 * `{attemptID, time:{created, expires}}`. [timeCreated]/[timeExpires] are
 * epoch millis; the schema also admits `"Infinity"`/`"NaN"` string encodings,
 * which parse as `0` here — [raw] keeps the wire value.
 */
data class IntegrationCommandAttempt(
    val attemptID: String,
    val timeCreated: Long,
    val timeExpires: Long,
    val raw: JSONObject,
) {
    companion object {
        fun fromJson(obj: JSONObject): IntegrationCommandAttempt {
            val time = obj.optJSONObject("time")
            return IntegrationCommandAttempt(
                attemptID = obj.optString("attemptID", ""),
                timeCreated = time?.optLong("created", 0L) ?: 0L,
                timeExpires = time?.optLong("expires", 0L) ?: 0L,
                raw = obj,
            )
        }
    }
}

/**
 * `Integration.CommandAttemptStatus` (`schema/src/integration.ts:109-117`) —
 * tagged union on [status]: `"pending"` carries an optional [message] (live
 * output so far); `"failed"` requires it; `"complete"`/`"expired"` are bare
 * apart from the attempt time.
 */
data class IntegrationCommandStatus(
    val status: String,
    val message: String?,
    val timeCreated: Long,
    val timeExpires: Long,
    val raw: JSONObject,
) {
    val pending: Boolean get() = status == STATUS_PENDING
    val complete: Boolean get() = status == STATUS_COMPLETE
    val failed: Boolean get() = status == STATUS_FAILED
    val expired: Boolean get() = status == STATUS_EXPIRED

    companion object {
        const val STATUS_PENDING = "pending"
        const val STATUS_COMPLETE = "complete"
        const val STATUS_FAILED = "failed"
        const val STATUS_EXPIRED = "expired"

        fun fromJson(obj: JSONObject): IntegrationCommandStatus {
            val time = obj.optJSONObject("time")
            return IntegrationCommandStatus(
                status = obj.optString("status", ""),
                message = optStringOrNull(obj, "message"),
                timeCreated = time?.optLong("created", 0L) ?: 0L,
                timeExpires = time?.optLong("expires", 0L) ?: 0L,
                raw = obj,
            )
        }
    }
}
