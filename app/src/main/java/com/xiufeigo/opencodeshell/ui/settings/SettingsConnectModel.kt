package com.xiufeigo.opencodeshell.ui.settings

import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure model for the settings write surface the v2 API already exposes:
 * stored credentials, integration connect flows (key / OAuth / command), MCP
 * runtime management, plugin update checks, the server shell write and the
 * optional experimental extras.
 *
 * Wire shapes are the measured/openapi `Integration.Info`,
 * `Integration.{Attempt,AttemptStatus,CommandAttempt,CommandAttemptStatus}`,
 * `Plugin.Info`, `Mcp.ResourceCatalog` and `V1MigrationStatus` objects (the
 * `data` payload, envelopes already unwrapped by `V2Api`). Everything here is
 * JSON-string-in / row-out so the pages stay unit-testable without a socket —
 * the same split `mcpPageRows`/`pluginPageRows` use.
 */

// ------------------------------------------------------------------- rows

/**
 * One stored credential (`Connection.CredentialInfo` `{type:"credential", id,
 * label}`) of an integration. [active] is inferred from upstream connection
 * order — see [credentialRows].
 */
data class CredentialRow(
    val id: String,
    val label: String,
    val integrationID: String,
    val integrationName: String,
    val active: Boolean,
)

/** One `Form` field of a key/oauth method (`schema/src/form.ts`). */
data class IntegrationFormField(
    val key: String,
    val title: String?,
    val type: String,
    val required: Boolean,
)

/**
 * `Integration.Method` flattened: `"key"`/`"oauth"`/`"command"` carry [id] for
 * the connect route (`command`/`oauth` only), [form] for optional answers and
 * `"env"` is detection-only (`names`).
 */
data class IntegrationMethodRow(
    val type: String,
    val id: String?,
    val label: String?,
    val form: List<IntegrationFormField> = emptyList(),
)

/** `Integration.Info` — one integration, its auth methods and current connections. */
data class IntegrationDetailRow(
    val id: String,
    val name: String,
    val methods: List<IntegrationMethodRow>,
    val credentials: List<CredentialRow>,
)

/** `Integration.Attempt` — an OAuth authorization URL the user must open. */
data class OauthAttemptRow(
    val attemptID: String,
    val url: String,
    val instructions: String,
    /** `"auto"` polls to completion; `"code"` waits for [Oauth code submit]. */
    val mode: String,
)

/** `Integration.AttemptStatus` — `{status, message?}`. */
data class OauthStatusRow(val status: String, val message: String?)

/** `Integration.CommandAttempt` — a running command auth process. */
data class CommandAttemptRow(val attemptID: String, val timeExpires: Long)

/** `Integration.CommandAttemptStatus` — `{status, message?}`. */
data class CommandStatusRow(val status: String, val message: String?)

/** `Mcp.Resource` / `Mcp.ResourceTemplate` share the browse row. */
data class McpResourceRow(
    val server: String,
    val name: String,
    val uri: String,
    val description: String?,
)

/** `Mcp.ResourceCatalog` — resources and templates of **connected** servers. */
data class McpResourceListRow(
    val resources: List<McpResourceRow>,
    val templates: List<McpResourceRow>,
)

/** `V1MigrationStatus` projected for the Experimental page. */
data class MigrationStatusRow(
    val status: String,
    val label: String?,
    val numerator: Long?,
    val denominator: Long?,
    val error: String?,
)

// ---------------------------------------------------------------- parsers

/** Bare array of `Integration.Info` objects (`GET /api/integration` `data`). */
fun integrationInfoRows(json: String?): List<IntegrationDetailRow> {
    val array = jsonArrayOrNull(json) ?: return emptyList()
    return (0 until array.length()).mapNotNull { index ->
        array.optJSONObject(index)?.let(::integrationDetailFrom)
    }
}

/** One `Integration.Info` object (`GET /api/integration/{id}` `data`). */
fun integrationDetailRow(json: String?): IntegrationDetailRow? {
    val obj = jsonObjectOrNull(json) ?: return null
    return integrationDetailFrom(obj)
}

private fun integrationDetailFrom(obj: JSONObject): IntegrationDetailRow {
    val methods = obj.optJSONArray("methods") ?: JSONArray()
    val detail = IntegrationDetailRow(
        id = obj.optString("id", ""),
        name = obj.optString("name", "").ifEmpty { obj.optString("id", "") },
        methods = (0 until methods.length()).mapNotNull { index ->
            methods.optJSONObject(index)?.let { method ->
                IntegrationMethodRow(
                    type = method.optString("type", ""),
                    id = method.optString("id", "").takeIf { it.isNotEmpty() },
                    label = method.optString("label", "").takeIf { it.isNotEmpty() },
                    form = formFields(method.optJSONArray("form")),
                )
            }
        },
        credentials = emptyList(),
    )
    return detail.copy(credentials = credentialRows(detail, obj))
}

private fun formFields(array: JSONArray?): List<IntegrationFormField> {
    if (array == null) return emptyList()
    return (0 until array.length()).mapNotNull { index ->
        val field = array.optJSONObject(index) ?: return@mapNotNull null
        val key = field.optString("key", "").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        IntegrationFormField(
            key = key,
            title = field.optString("title", "").takeIf { it.isNotEmpty() },
            type = field.optString("type", "string"),
            required = field.optBoolean("required", false),
        )
    }
}

/**
 * Credential connections of one integration, in wire order.
 *
 * Upstream `resolveConnections` (`core/src/integration.ts`) maps
 * `credentials.list(integrationID)` — ordered `asc(active), asc(time_created),
 * asc(id)` (`core/src/credential.ts`), so the active row lands **last** — and
 * then `.toReversed()` it. The **first** `type:"credential"` entry is therefore
 * the active one; the API exposes no explicit flag.
 */
fun credentialRows(detail: IntegrationDetailRow, raw: JSONObject? = null): List<CredentialRow> {
    val connections = (raw ?: JSONObject()).optJSONArray("connections") ?: return emptyList()
    var seen = 0
    return (0 until connections.length()).mapNotNull { index ->
        val connection = connections.optJSONObject(index) ?: return@mapNotNull null
        if (connection.optString("type", "") != "credential") return@mapNotNull null
        val id = connection.optString("id", "").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val active = seen == 0
        seen++
        CredentialRow(
            id = id,
            label = connection.optString("label", "").ifEmpty { id },
            integrationID = detail.id,
            integrationName = detail.name,
            active = active,
        )
    }
}

/** Every stored credential across integrations, active-first per integration. */
fun credentialRows(json: String?): List<CredentialRow> =
    integrationInfoRows(json).flatMap { it.credentials }

/** `Integration.Attempt` (`{attemptID, url, instructions, mode}`). */
fun oauthAttemptRow(json: String?): OauthAttemptRow? {
    val obj = jsonObjectOrNull(json) ?: return null
    val attemptID = obj.optString("attemptID", "").takeIf { it.isNotEmpty() } ?: return null
    return OauthAttemptRow(
        attemptID = attemptID,
        url = obj.optString("url", ""),
        instructions = obj.optString("instructions", ""),
        mode = obj.optString("mode", "auto"),
    )
}

/** `Integration.AttemptStatus` (`{status, message?}`). */
fun oauthStatusRow(json: String?): OauthStatusRow? {
    val obj = jsonObjectOrNull(json) ?: return null
    return OauthStatusRow(
        status = obj.optString("status", ""),
        message = obj.optString("message", "").takeIf { it.isNotEmpty() },
    )
}

/** `Integration.CommandAttempt` (`{attemptID, time:{created, expires}}`). */
fun commandAttemptRow(json: String?): CommandAttemptRow? {
    val obj = jsonObjectOrNull(json) ?: return null
    val attemptID = obj.optString("attemptID", "").takeIf { it.isNotEmpty() } ?: return null
    return CommandAttemptRow(
        attemptID = attemptID,
        timeExpires = obj.optJSONObject("time")?.optLong("expires", 0L) ?: 0L,
    )
}

/** `Integration.CommandAttemptStatus` (`{status, message?}`). */
fun commandStatusRow(json: String?): CommandStatusRow? {
    val obj = jsonObjectOrNull(json) ?: return null
    return CommandStatusRow(
        status = obj.optString("status", ""),
        message = obj.optString("message", "").takeIf { it.isNotEmpty() },
    )
}

/** `Mcp.ResourceCatalog` (`{resources:[…], templates:[…]}`). */
fun mcpResourceListRow(json: String?): McpResourceListRow {
    val obj = jsonObjectOrNull(json) ?: return McpResourceListRow(emptyList(), emptyList())
    return McpResourceListRow(
        resources = mcpResourceRows(obj.optJSONArray("resources")),
        templates = mcpResourceRows(obj.optJSONArray("templates")),
    )
}

private fun mcpResourceRows(array: JSONArray?): List<McpResourceRow> {
    if (array == null) return emptyList()
    return (0 until array.length()).mapNotNull { index ->
        val item = array.optJSONObject(index) ?: return@mapNotNull null
        McpResourceRow(
            server = item.optString("server", ""),
            name = item.optString("name", "").ifEmpty { item.optString("uri", "") },
            uri = listOf("uri", "uriTemplate")
                .firstNotNullOfOrNull { key -> item.optString(key, "").takeIf { it.isNotEmpty() } }
                .orEmpty(),
            description = item.optString("description", "").takeIf { it.isNotEmpty() },
        )
    }
}

/**
 * `Plugin.Check` payload (`[Plugin.Info…]`, `source.outdated` refreshed) →
 * [PluginPageRow] with the update fields the Extensions page renders.
 */
fun pluginCheckRows(json: String?): List<PluginPageRow> {
    val array = jsonArrayOrNull(json) ?: return emptyList()
    return (0 until array.length()).mapNotNull { index ->
        val obj = array.optJSONObject(index) ?: return@mapNotNull null
        val source = obj.optJSONObject("source") ?: JSONObject()
        val name = listOf(obj.optString("id", ""), source.optString("target", ""), source.optString("path", ""))
            .firstOrNull { it.isNotEmpty() } ?: return@mapNotNull null
        PluginPageRow(
            name = name,
            packageTarget = source.optString("target", "").takeIf { it.isNotEmpty() },
            packageVersion = source.optString("version", "").takeIf { it.isNotEmpty() },
            outdated = source.optBoolean("outdated", false),
            updating = source.optBoolean("updating", false),
            stateStatus = obj.optJSONObject("state")?.optString("status", "")?.takeIf { it.isNotEmpty() },
        )
    }.sortedBy { it.name.lowercase() }
}

/** Bare `V1MigrationStatus` union (`{status, progress?, error?}`). */
fun migrationStatusRow(json: String?): MigrationStatusRow? {
    val obj = jsonObjectOrNull(json) ?: return null
    val progress = obj.optJSONObject("progress")
    return MigrationStatusRow(
        status = obj.optString("status", ""),
        label = progress?.optString("label", "")?.takeIf { it.isNotEmpty() },
        numerator = progress?.let { if (it.has("numerator")) it.optLong("numerator") else null },
        denominator = progress?.let { if (it.has("denominator")) it.optLong("denominator") else null },
        error = obj.optString("error", "").takeIf { it.isNotEmpty() },
    )
}

private fun jsonObjectOrNull(json: String?): JSONObject? {
    val trimmed = json?.trim().orEmpty()
    if (trimmed.isEmpty() || !trimmed.startsWith("{")) return null
    return runCatching { JSONObject(trimmed) }.getOrNull()
}

private fun jsonArrayOrNull(json: String?): JSONArray? {
    val trimmed = json?.trim().orEmpty()
    if (trimmed.isEmpty() || !trimmed.startsWith("[")) return null
    return runCatching { JSONArray(trimmed) }.getOrNull()
}

// ----------------------------------------------------------- connect flows

/** Which connect route a method drives (`protocol/src/groups/integration.ts`). */
enum class ConnectFlow { KEY, OAUTH, COMMAND, ENV, OTHER }

fun connectFlowOf(type: String): ConnectFlow = when (type) {
    "key" -> ConnectFlow.KEY
    "oauth" -> ConnectFlow.OAUTH
    "command" -> ConnectFlow.COMMAND
    "env" -> ConnectFlow.ENV
    else -> ConnectFlow.OTHER
}

/**
 * Methods the connect sheet offers: every interactive one (`key`/`oauth`/
 * `command`; `env` is environment detection). Upstream's controller
 * (`providers/connect/controller.ts`) falls back to a bare API-key form when the
 * integration reports none, which is what an unknown/absent detail gets here.
 */
fun connectableMethods(detail: IntegrationDetailRow?): List<IntegrationMethodRow> {
    val usable = detail?.methods
        ?.filter { connectFlowOf(it.type) == ConnectFlow.KEY || connectFlowOf(it.type) == ConnectFlow.OAUTH || connectFlowOf(it.type) == ConnectFlow.COMMAND }
        .orEmpty()
    if (usable.isNotEmpty()) return usable
    return listOf(IntegrationMethodRow(type = "key", id = null, label = null))
}

/** A method needs its `methodID` on the route — `command` and `oauth` only. */
fun methodRouteID(method: IntegrationMethodRow): String? = method.id?.takeIf {
    connectFlowOf(method.type) == ConnectFlow.OAUTH || connectFlowOf(method.type) == ConnectFlow.COMMAND
}

/** Non-string form fields cannot be answered from a text box (upstream parity). */
fun formHasUnsupportedFields(form: List<IntegrationFormField>): Boolean =
    form.any { it.type != "string" }

/** Optional `Form.Answer` for the collected string fields; blank values omitted. */
fun formAnswer(fields: List<IntegrationFormField>, values: Map<String, String>): Map<String, Any?>? {
    if (fields.isEmpty()) return null
    val answer = fields.mapNotNull { field ->
        val value = values[field.key]?.trim().orEmpty()
        if (value.isEmpty()) {
            if (field.required) return null
            null
        } else {
            field.key to value
        }
    }.toMap()
    return answer.ifEmpty { null }
}

/** Next step of the OAuth poll (`integration.ts` `AttemptStatus` + `mode`). */
enum class OauthPollOutcome { POLL, AWAIT_CODE, COMPLETE, FAILED, EXPIRED }

/**
 * `mode:"auto"` settles on its own while [OauthPollOutcome.POLL] keeps the
 * timer going; `mode:"code"` stays `pending` until `…/complete`, so the sheet
 * offers the code field ([OauthPollOutcome.AWAIT_CODE]) and keeps polling in
 * parallel to catch `failed`/`expired`.
 */
fun oauthPollOutcome(status: String, mode: String): OauthPollOutcome = when (status) {
    "complete" -> OauthPollOutcome.COMPLETE
    "failed" -> OauthPollOutcome.FAILED
    "expired" -> OauthPollOutcome.EXPIRED
    "pending" -> if (mode == "code") OauthPollOutcome.AWAIT_CODE else OauthPollOutcome.POLL
    else -> OauthPollOutcome.POLL
}

/** Next step of the command-attempt poll. */
enum class CommandPollOutcome { POLL, COMPLETE, FAILED, EXPIRED }

fun commandPollOutcome(status: String): CommandPollOutcome = when (status) {
    "complete" -> CommandPollOutcome.COMPLETE
    "failed" -> CommandPollOutcome.FAILED
    "expired" -> CommandPollOutcome.EXPIRED
    else -> CommandPollOutcome.POLL
}

/** Trimmed non-empty credential label (`PATCH /api/credential/{id} {label}`). */
fun validateCredentialLabel(raw: String?): String? = raw?.trim()?.takeIf { it.isNotEmpty() }

// -------------------------------------------------------------------- mcp

/** `Mcp.ServerConfig` the add form is allowed to produce. */
sealed class McpAddSpec {
    abstract val name: String

    data class Local(override val name: String, val command: List<String>) : McpAddSpec()
    data class Remote(override val name: String, val url: String) : McpAddSpec()
}

/**
 * `command: string[]` (`Mcp.LocalConfig`) from the free-text field. Whitespace
 * split only — no shell quoting — which is what a `npx -y pkg` entry needs.
 */
fun splitCommandTokens(command: String): List<String> =
    command.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

/** Form → payload; null while the name or the chosen transport field is blank. */
fun mcpAddSpec(name: String, remote: Boolean, command: String, url: String): McpAddSpec? {
    val cleanName = name.trim().takeIf { it.isNotEmpty() } ?: return null
    return if (remote) {
        McpAddSpec.Remote(cleanName, url.trim().takeIf { it.isNotEmpty() } ?: return null)
    } else {
        McpAddSpec.Local(cleanName, splitCommandTokens(command).takeIf { it.isNotEmpty() } ?: return null)
    }
}

// ---------------------------------------------------------------- plugins

/**
 * `pluginUpdate` targets — the `source.target` of every `outdated` package
 * (`plugin.ts:40-54`; `pluginCheck` is what fills `outdated` in).
 */
fun pluginUpdateTargets(rows: List<PluginPageRow>): List<String> =
    rows.filter { it.outdated }.mapNotNull { it.packageTarget }.distinct()

// ------------------------------------------------------------------- misc

/** `http(s)://…` or null — the wellknown import URL (`integration.wellknown.add`). */
fun validHttpUrl(raw: String?): String? {
    val url = raw?.trim().orEmpty()
    if (!url.startsWith("http://") && !url.startsWith("https://")) return null
    return url
}

/**
 * `POST /api/experimental/generate` prompt for a session title — pure builder
 * only. Nothing in the UI may call `generateText` (it runs a real LLM
 * generation); this exists so a future title feature has the prompt shape.
 */
fun titleGeneratePrompt(excerpt: String): String =
    "Generate a concise session title (5 words maximum) for this message:\n${excerpt.trim()}"

/**
 * `Integration.Info.connections[].type` (`credential`/`env`/…) of one raw list
 * row — the values `ProviderConnectCatalog.resolveSource`/`canDisconnect` read.
 * `methods[].type` (`key`/`oauth`/`command`/`env`) is a different union and
 * must never be substituted here: a key-method integration with a stored grant
 * reports `methods:[{type:"key"}]` + `connections:[{type:"credential"}]`.
 */
fun integrationConnectionTypes(raw: JSONObject): List<String> {
    val connections = raw.optJSONArray("connections") ?: return emptyList()
    return (0 until connections.length()).mapNotNull { index ->
        connections.optJSONObject(index)?.optString("type", "")?.takeIf { it.isNotEmpty() }
    }
}

/**
 * Short diagnostic token for a settings write outcome — `null` on
 * [ProviderWriteResult.Done]. `no-credentials` / `http-xxx` / `unsupported` /
 * `unreachable` land in `oc_settings_page_failed` as the `%1$s` argument.
 */
fun writeFailureReason(result: ProviderWriteResult): String? = when (result) {
    ProviderWriteResult.Done -> null
    ProviderWriteResult.Unsupported -> "unsupported"
    is ProviderWriteResult.Failed -> result.reason
}
