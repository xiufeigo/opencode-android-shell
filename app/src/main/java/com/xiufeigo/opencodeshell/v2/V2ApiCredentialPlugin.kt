package com.xiufeigo.opencodeshell.v2

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Credential / plugin / plugin-RPC routes, held as extension functions over
 * [V2Api]'s `internal` HTTP seam (same OkHttp client, same
 * `basicAuthorizationHeader`, same [V2Api.ApiException] on non-2xx) instead of
 * [V2Api] members — that class is owned by another change.
 *
 * | route | contract |
 * |---|---|
 * | `PATCH /api/credential/{credentialID}` | `{"label":String}` → **204** (`credential.ts:7-21`) |
 * | `POST /api/credential/{credentialID}/activate` | no body → **204** (`credential.ts:22-31`) |
 * | `POST /api/plugin/check` | `{"target"?:String}` → `{location,data:[Plugin.Info]}` (`plugin.ts:24-38`) |
 * | `POST /api/plugin/update` | `{"targets":[String]}` → **204** (`plugin.ts:40-54`) |
 * | `POST /api/rpc/{rpcID}/{method}` | `{"input"?:Unknown}` → bare `{"output"?:Unknown}` (`rpc.ts:7-27`) |
 *
 * `UNVERIFIED` live: no write was issued against the running server (writes are
 * asserted on `MockWebServer` only); the shapes come from the live 2.0.11
 * `openapi.json` and the route groups cited above. Blocking; call on an IO
 * dispatcher.
 */

private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

/** No-payload POST body — the shape `ptyConnectTicket` measured live. */
private val EMPTY_BODY: RequestBody = ByteArray(0).toRequestBody(null)

/**
 * `PATCH /api/credential/{credentialID}` body `{label}` → **204**: renames a
 * stored credential (`credential.ts:7-21`; handler `server/src/handlers/
 * credential.ts:8-15`). Errors: 400 `InvalidRequestError`, 401 — both mapped to
 * [V2Api.ApiException] with the `_tag` in the message.
 */
fun V2Api.credentialUpdate(credentialID: String, label: String) {
    val body = JSONObject().put("label", label)
    callUnit(
        builder("credential/$credentialID", false)
            .patch(body.toString().toRequestBody(JSON_MEDIA))
            .build(),
    )
}

/**
 * `POST /api/credential/{credentialID}/activate` → **204**: switches the active
 * credential of its integration (`credential.ts:22-31`; emits
 * `credential.switched`, `schema/src/credential.ts:24-28`).
 *
 * **No** request body (`client/src/promise/generated/client.ts:1385-1393`
 * omits `body` entirely), so this sends OkHttp's empty body rather than the
 * `{}` `postUnit` would send.
 */
fun V2Api.credentialActivate(credentialID: String) {
    callUnit(builder("credential/$credentialID/activate", false).post(EMPTY_BODY).build())
}

/**
 * `POST /api/plugin/check` body `{target?}` → `{location,data:[Plugin.Info…]}`
 * (`plugin.ts:24-38`). Pass [target] (a package `source.target`) to re-check
 * one plugin; `null` re-checks every package plugin.
 *
 * The server answers the **full** inventory — the same rows `plugins()` lists —
 * with `source.outdated` refreshed on the checked packages
 * (`server/src/handlers/plugin.ts:17-55`); a [target] outside the inventory is
 * 400 `InvalidRequestError` with `field:"target"`.
 */
fun V2Api.pluginCheck(target: String? = null, locationDirectory: String? = null): List<V2PluginInfo> {
    val body = JSONObject()
    if (target != null) body.put("target", target)
    return v2ObjectList(
        dataArray(
            callBody(
                builder("plugin/check", true) { located(it, locationDirectory) }
                    .post(body.toString().toRequestBody(JSON_MEDIA))
                    .build(),
            ),
        ),
    ).map(V2PluginInfo.Companion::fromJson)
}

/**
 * `POST /api/plugin/update` body `{targets:[…]}` → **204** (`plugin.ts:40-54`).
 * [targets] are package `source.target` values — what [V2PluginInfo.packageTarget]
 * carries. Unknown targets are 400 `InvalidRequestError`; any failed update
 * becomes 503 `ServiceUnavailableError` once **every** target has finished
 * (`server/src/handlers/plugin.ts:57-91`).
 */
fun V2Api.pluginUpdate(targets: List<String>, locationDirectory: String? = null) {
    val body = JSONObject().put("targets", JSONArray(targets))
    callUnit(
        builder("plugin/update", true) { located(it, locationDirectory) }
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build(),
    )
}

/**
 * `POST /api/rpc/{rpcID}/{method}` body `{input?}` → bare `{output?}` — **no**
 * `data` envelope (`rpc.ts:7-11`, endpoint `rpc.ts:14-27`) — dispatching
 * [method] to the plugin RPC registered as [rpcID] at the requested location.
 *
 * @param input any org.json value (`JSONObject`, `JSONArray`, `String`,
 *   `Number`, `Boolean`, `JSONObject.NULL`); `null` omits the key, matching
 *   `optional(Schema.Unknown)` (`rpc.ts:7`).
 * @return the `output` value in the same org.json types — `null` when the key
 *   is absent (the server answers `{}` for an `undefined` output,
 *   `server/src/handlers/rpc.ts:11`), `JSONObject.NULL` for JSON `null`.
 *
 * Errors: 400 `RpcError`/`InvalidRequestError`, 500 `RpcInternalError`
 * (`rpc.ts:19`) — all surfaced as [V2Api.ApiException].
 */
fun V2Api.rpcCall(
    rpcID: String,
    method: String,
    input: Any? = null,
    locationDirectory: String? = null,
): Any? {
    val body = JSONObject()
    if (input != null) body.put("input", input)
    val response = parseObject(
        callBody(
            builder("rpc/$rpcID/$method", true) { located(it, locationDirectory) }
                .post(body.toString().toRequestBody(JSON_MEDIA))
                .build(),
        ),
    )
    return response.opt("output")
}

/**
 * `Plugin.Info` — `{id?, source, features, state}`
 * (`schema/src/plugin.ts:37-43`), flattened the way [V2Api.IntegrationInfo]
 * flattens its unions.
 *
 * [sourceType] is `source.type` (`plugin.ts:10-21`): [packageTarget],
 * [packageVersion], [outdated] and [updating] only exist for `"package"`, and
 * [localPath] only for `"local"`. [outdated] is filled in by
 * `POST /api/plugin/check`, never by the plain list. [stateError]/[stateRef]
 * belong to `state:{status:"failed"}` (`plugin.ts:31-35`).
 */
data class V2PluginInfo(
    val id: String?,
    val sourceType: String,
    val packageTarget: String?,
    val packageVersion: String?,
    val outdated: Boolean,
    val updating: Boolean,
    val localPath: String?,
    val featureServer: Boolean,
    val featureTui: Boolean,
    val featureRpc: Boolean,
    val stateStatus: String,
    val stateError: String?,
    val stateRef: String?,
    val raw: JSONObject,
) {
    companion object {
        fun fromJson(obj: JSONObject): V2PluginInfo {
            val source = obj.optJSONObject("source") ?: JSONObject()
            val features = obj.optJSONObject("features") ?: JSONObject()
            val state = obj.optJSONObject("state") ?: JSONObject()
            return V2PluginInfo(
                id = optStringOrNull(obj, "id"),
                sourceType = source.optString("type", ""),
                packageTarget = optStringOrNull(source, "target"),
                packageVersion = optStringOrNull(source, "version"),
                outdated = source.optBoolean("outdated", false),
                updating = source.optBoolean("updating", false),
                localPath = optStringOrNull(source, "path"),
                featureServer = features.optBoolean("server", false),
                featureTui = features.optBoolean("tui", false),
                featureRpc = features.optBoolean("rpc", false),
                stateStatus = state.optString("status", ""),
                stateError = optStringOrNull(state, "error"),
                stateRef = optStringOrNull(state, "ref"),
                raw = obj,
            )
        }
    }
}
