package com.xiufeigo.opencodeshell.v2

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Runtime MCP server management + resource catalog —
 * `PUT`/`DELETE /api/experimental/mcp/{server}`,
 * `POST /api/experimental/mcp/{server}/{connect,disconnect}`,
 * `GET /api/mcp/resource` (`protocol/src/groups/mcp.ts:23-102`).
 *
 * Held as extension functions rather than [V2Api] members (parallel change to
 * `V2Api.kt`); they use the class's `internal` HTTP seam only — same OkHttp
 * client, same `basicAuthorizationHeader`, same `ApiException` on non-2xx.
 * `GET /api/mcp` (the server list) already lives on [V2Api.mcp] and is not
 * duplicated here.
 *
 * | piece | contract |
 * |---|---|
 * | query | `location[directory]` (deep-object) on every route (`mcp.ts`) |
 * | `PUT` body | `{"config": Mcp.ServerConfig}` — the struct wrapper is mandatory (`mcp.ts:27-29`) |
 * | `PUT`/`DELETE`/`connect`/`disconnect` | `204`, empty body (`HttpApiSchema.NoContent`) |
 * | `DELETE`/`connect`/`disconnect` | `404 {"_tag":"McpServerNotFoundError",…}` for an unknown name |
 * | `GET /api/mcp/resource` | `{"location":…,"data":{"resources":[…],"templates":[…]}}` |
 *
 * Measured live 2026-09-23 on 2.0.11: `GET /api/mcp/resource` answered
 * `{"location":{"directory":"/home/xiufeigo"},"data":{"resources":[],"templates":[]}}`
 * (no connected servers). The four write routes are `UNVERIFIED` live —
 * contract is `mcp.ts` + openapi.json; never issued against the real server.
 */

private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
private val EMPTY_BODY: RequestBody = ByteArray(0).toRequestBody(null)

/**
 * `PUT /api/experimental/mcp/{server}` body `{"config": ServerConfig}` → **204**.
 *
 * Adds an MCP server at runtime or replaces an existing one, connecting it
 * immediately (`mcp.ts:23-40`). [config] is one tagged `Mcp.ServerConfig`
 * (`schema/src/mcp.ts:26-67`) — build it with [mcpLocalConfig] or
 * [mcpRemoteConfig]. Runtime changes last until server restart.
 */
fun V2Api.mcpAdd(server: String, config: JSONObject, locationDirectory: String? = null) {
    val body = JSONObject().put("config", config)
    callUnit(
        mcpExperimental(server, locationDirectory)
            .put(body.toString().toRequestBody(JSON_MEDIA))
            .build(),
    )
}

/**
 * `DELETE /api/experimental/mcp/{server}` → **204**.
 *
 * Stops the server and removes it from the runtime set until restart
 * (`mcp.ts:41-56`). Unknown names answer `404 {"_tag":"McpServerNotFoundError"}`
 * and surface as [ApiException][V2Api.ApiException].
 */
fun V2Api.mcpRemove(server: String, locationDirectory: String? = null) {
    callUnit(mcpExperimental(server, locationDirectory).delete().build())
}

/**
 * `POST /api/experimental/mcp/{server}/connect` → **204**.
 *
 * Connects at runtime, overriding a `disabled` configuration until restart
 * (`mcp.ts:57-72`). No request body (none in the route schema).
 */
fun V2Api.mcpConnect(server: String, locationDirectory: String? = null) {
    callUnit(mcpExperimental(server, locationDirectory, "connect").post(EMPTY_BODY).build())
}

/**
 * `POST /api/experimental/mcp/{server}/disconnect` → **204**.
 *
 * Disconnects at runtime, removing its tools until reconnected
 * (`mcp.ts:73-88`). No request body.
 */
fun V2Api.mcpDisconnect(server: String, locationDirectory: String? = null) {
    callUnit(mcpExperimental(server, locationDirectory, "disconnect").post(EMPTY_BODY).build())
}

/**
 * `GET /api/mcp/resource` → `{location, data:Mcp.ResourceCatalog}`.
 *
 * Resources and resource templates from **connected** MCP servers
 * (`mcp.ts:89-102`, `schema/src/mcp.ts:99-121`). Measured live: the envelope
 * carries `location` and both arrays are present (empty while nothing is
 * connected).
 */
fun V2Api.mcpResourceCatalog(locationDirectory: String? = null): McpResourceCatalog = McpResourceCatalog.fromJson(
    dataObject(callBody(builder("mcp/resource", true) { located(it, locationDirectory) }.get().build())),
)

/**
 * `/api/experimental/mcp/<server>[/<action>]` with `location[directory]`.
 *
 * [server] goes through a single `addPathSegment`, so a name containing `/` or
 * `?` cannot re-root the URL (unlike interpolating it into the `builder` path,
 * which splits on `/` first).
 */
private fun V2Api.mcpExperimental(
    server: String,
    locationDirectory: String?,
    action: String? = null,
): Request.Builder = builder("", true) {
    located(it, locationDirectory)
    it.addPathSegment("experimental")
    it.addPathSegment("mcp")
    it.addPathSegment(server)
    if (action != null) it.addPathSegment(action)
}

// ------------------------------------------------------------------ payloads

/**
 * `Mcp.LocalConfig` (`schema/src/mcp.ts:26-39`, openapi `Mcp.LocalConfigEncoded`)
 * for [mcpAdd]: `{"type":"local","command":[…]}` plus the optional fields only
 * when supplied (the schema is `additionalProperties:false`).
 *
 * @param timeout [mcpTimeout] output; all three bounds are `PositiveInt` ms.
 * @param protocol `"legacy"` (default) | `"auto"` | `"2026-07-28"`
 *   (`schema/src/mcp.ts:19-24`).
 */
fun mcpLocalConfig(
    command: List<String>,
    cwd: String? = null,
    environment: Map<String, String>? = null,
    disabled: Boolean? = null,
    codemode: Boolean? = null,
    timeout: JSONObject? = null,
    protocol: String? = null,
): JSONObject {
    val json = JSONObject().put("type", "local").put("command", JSONArray(command))
    if (!cwd.isNullOrBlank()) json.put("cwd", cwd)
    if (!environment.isNullOrEmpty()) {
        val entries = JSONObject()
        for ((key, value) in environment) entries.put(key, value)
        json.put("environment", entries)
    }
    if (disabled != null) json.put("disabled", disabled)
    if (codemode != null) json.put("codemode", codemode)
    if (timeout != null) json.put("timeout", timeout)
    if (!protocol.isNullOrBlank()) json.put("protocol", protocol)
    return json
}

/**
 * `Mcp.RemoteConfig` (`schema/src/mcp.ts:53-64`, openapi `Mcp.RemoteConfigEncoded`)
 * for [mcpAdd]: `{"type":"remote","url":…}` plus the optional fields only when
 * supplied.
 *
 * @param oauth `Mcp.OAuthConfig` (`schema/src/mcp.ts:41-51`) from [mcpOAuthConfig],
 *   or literal `false` to disable OAuth for this server (the schema is a
 *   `Union([OAuthConfig, false])`). `null` omits the key.
 */
fun mcpRemoteConfig(
    url: String,
    headers: Map<String, String>? = null,
    oauth: Any? = null,
    disabled: Boolean? = null,
    codemode: Boolean? = null,
    timeout: JSONObject? = null,
    protocol: String? = null,
): JSONObject {
    val json = JSONObject().put("type", "remote").put("url", url)
    if (!headers.isNullOrEmpty()) {
        val entries = JSONObject()
        for ((key, value) in headers) entries.put(key, value)
        json.put("headers", entries)
    }
    if (oauth != null) json.put("oauth", oauth)
    if (disabled != null) json.put("disabled", disabled)
    if (codemode != null) json.put("codemode", codemode)
    if (timeout != null) json.put("timeout", timeout)
    if (!protocol.isNullOrBlank()) json.put("protocol", protocol)
    return json
}

/**
 * `Mcp.OAuthConfig` (`schema/src/mcp.ts:41-51`) for [mcpRemoteConfig]'s `oauth`.
 * Field names are the wire's snake_case. `callback_port` is 1..65535.
 */
fun mcpOAuthConfig(
    clientId: String? = null,
    clientSecret: String? = null,
    scope: String? = null,
    callbackPort: Int? = null,
    redirectUri: String? = null,
    authServerMetadataUrl: String? = null,
): JSONObject {
    val json = JSONObject()
    if (!clientId.isNullOrBlank()) json.put("client_id", clientId)
    if (!clientSecret.isNullOrBlank()) json.put("client_secret", clientSecret)
    if (!scope.isNullOrBlank()) json.put("scope", scope)
    if (callbackPort != null) json.put("callback_port", callbackPort)
    if (!redirectUri.isNullOrBlank()) json.put("redirect_uri", redirectUri)
    if (!authServerMetadataUrl.isNullOrBlank()) json.put("auth_server_metadata_url", authServerMetadataUrl)
    return json
}

/**
 * `Mcp.TimeoutConfig` (`schema/src/mcp.ts:7-17`) — per-phase limits in
 * **milliseconds**, each `PositiveInt` (exclusive minimum 0): `startup` to
 * establish/initialize, `catalog` for discovery (`tools/list`, `prompts/list`),
 * `execution` for tool/prompt runs.
 */
fun mcpTimeout(
    startupMs: Int? = null,
    catalogMs: Int? = null,
    executionMs: Int? = null,
): JSONObject {
    val json = JSONObject()
    if (startupMs != null) json.put("startup", startupMs)
    if (catalogMs != null) json.put("catalog", catalogMs)
    if (executionMs != null) json.put("execution", executionMs)
    return json
}

// ---------------------------------------------------------------------- dto

/** `Mcp.Resource` — `{server, name, uri, description?, mimeType?}` (`schema/src/mcp.ts:99-106`). */
data class McpResource(
    val server: String,
    val name: String,
    val uri: String,
    val description: String?,
    val mimeType: String?,
    val raw: JSONObject,
) {
    companion object {
        fun fromJson(obj: JSONObject): McpResource = McpResource(
            server = obj.optString("server", ""),
            name = obj.optString("name", ""),
            uri = obj.optString("uri", ""),
            description = optStringOrNull(obj, "description"),
            mimeType = optStringOrNull(obj, "mimeType"),
            raw = obj,
        )
    }
}

/**
 * `Mcp.ResourceTemplate` — `{server, name, uriTemplate, description?, mimeType?}`
 * (`schema/src/mcp.ts:108-115`). [uriTemplate] is an RFC 6570 template; expand
 * it before opening the resource.
 */
data class McpResourceTemplate(
    val server: String,
    val name: String,
    val uriTemplate: String,
    val description: String?,
    val mimeType: String?,
    val raw: JSONObject,
) {
    companion object {
        fun fromJson(obj: JSONObject): McpResourceTemplate = McpResourceTemplate(
            server = obj.optString("server", ""),
            name = obj.optString("name", ""),
            uriTemplate = obj.optString("uriTemplate", ""),
            description = optStringOrNull(obj, "description"),
            mimeType = optStringOrNull(obj, "mimeType"),
            raw = obj,
        )
    }
}

/** `Mcp.ResourceCatalog` — `{resources:[…], templates:[…]}` (`schema/src/mcp.ts:117-121`). */
data class McpResourceCatalog(
    val resources: List<McpResource>,
    val templates: List<McpResourceTemplate>,
) {
    companion object {
        fun fromJson(obj: JSONObject): McpResourceCatalog = McpResourceCatalog(
            resources = v2ObjectList(obj.optJSONArray("resources") ?: JSONArray()).map(McpResource::fromJson),
            templates = v2ObjectList(obj.optJSONArray("templates") ?: JSONArray()).map(McpResourceTemplate::fromJson),
        )
    }
}
