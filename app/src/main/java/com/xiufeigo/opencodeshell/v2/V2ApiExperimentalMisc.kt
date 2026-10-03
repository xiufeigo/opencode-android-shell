package com.xiufeigo.opencodeshell.v2

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Experimental misc routes — upstream `protocol/src/groups/{config,generate,
 * migration}.ts`:
 *
 * | route | contract |
 * |---|---|
 * | `PATCH /api/experimental/config` | body `Config.Patch` `{shell: string\|null}` (key required, `additionalProperties:false`) → **204** (`config.ts:35-45`) |
 * | `POST /api/experimental/generate` | body `{prompt, model?: Model.Ref}` → `{data:{text}}` (`generate.ts:8-25`) |
 * | `GET /api/experimental/migration/v1` | **bare** `V1MigrationStatus` union, no `{data}` envelope (`migration.ts:4-28`; Measured live 2026-09-23 `{"status":"completed"}`) |
 *
 * Held as extension functions rather than [V2Api] members (parallel change to
 * `V2Api.kt`); they use the class's `internal` HTTP seam only — same OkHttp
 * client, same `basicAuthorizationHeader`, same `ApiException` on non-2xx.
 * The two write routes are `UNVERIFIED` live (openapi.json + group files
 * only; writes are never aimed at the real server).
 */

private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

/**
 * `PATCH /api/experimental/config` → **204**. `config.update` — "Patch
 * supported fields in the highest-precedence global configuration document"
 * (`config.ts:35-45`).
 *
 * `Config.Patch` is currently `{shell: string|null}` and nothing else
 * (`schema/src/config.ts:112-114`): [shell] is sent as a **required** JSON key,
 * `null` clears it back to the discovered default. No `location` parameter
 * (openapi `parameters: []`).
 */
fun V2Api.configUpdate(shell: String?) {
    val body = JSONObject().put("shell", shell ?: JSONObject.NULL)
    callUnit(
        builder("experimental/config", false)
            .patch(body.toString().toRequestBody(JSON_MEDIA))
            .build(),
    )
}

/**
 * `POST /api/experimental/generate` body `{prompt, model?}` → `data.text` —
 * `generate.text`: "Run one stateless model generation using the server's base
 * configuration and return the assistant text. Uses the base configuration's
 * default model when none is specified" (`generate.ts:8-25`). Typical use is
 * client-side title generation; it never touches session history (the
 * session-scoped sibling is [V2Api.generate]).
 *
 * [model] is `Model.Ref` `{id, providerID, variant?}` (`schema/src/model.ts:15-20`).
 * No `location` parameter (openapi `parameters: []`). Errors: `400
 * InvalidRequestError` (bad model selection), `503 ServiceUnavailableError`.
 */
fun V2Api.generateText(prompt: String, model: V2Api.ModelRef? = null): String {
    val body = JSONObject().put("prompt", prompt)
    if (model != null) body.put("model", model.toJson())
    return dataObject(postJson("experimental/generate", false, body)).optString("text", "")
}

/**
 * `GET /api/experimental/migration/v1` → the **bare** [V1MigrationStatus] union
 * (`migration.ts:4-28`) — no `{data}` wrapper, so this parses the root object.
 * Measured live 2026-09-23 on 2.0.11: `200 {"status":"completed"}`.
 */
fun V2Api.migrationV1Status(): V1MigrationStatus =
    V1MigrationStatus.fromJson(parseObject(callBody(builder("experimental/migration/v1", false).get().build())))

/**
 * `V1MigrationStatus` (`migration.ts:4-14`) — the v1→v2 session-history
 * migration state, a tagged union on [status]:
 * `"required"` | `"completed"` bare; `"running"` carries [progress];
 * `"error"` carries [error]. Measured live: the `completed` variant only.
 */
data class V1MigrationStatus(
    val status: String,
    val progress: Progress?,
    val error: String?,
    val raw: JSONObject,
) {
    /** `V1MigrationProgress` — `{label, numerator?, denominator?}` (non-negative ints). */
    data class Progress(val label: String, val numerator: Long?, val denominator: Long?)

    val required: Boolean get() = status == STATUS_REQUIRED
    val completed: Boolean get() = status == STATUS_COMPLETED
    val running: Boolean get() = status == STATUS_RUNNING
    val failed: Boolean get() = status == STATUS_ERROR

    companion object {
        const val STATUS_REQUIRED = "required"
        const val STATUS_COMPLETED = "completed"
        const val STATUS_RUNNING = "running"
        const val STATUS_ERROR = "error"

        fun fromJson(obj: JSONObject): V1MigrationStatus {
            val progress = obj.optJSONObject("progress")
            return V1MigrationStatus(
                status = obj.optString("status", ""),
                progress = progress?.let {
                    Progress(
                        label = it.optString("label", ""),
                        numerator = if (it.has("numerator")) it.optLong("numerator") else null,
                        denominator = if (it.has("denominator")) it.optLong("denominator") else null,
                    )
                },
                error = optStringOrNull(obj, "error"),
                raw = obj,
            )
        }
    }
}
