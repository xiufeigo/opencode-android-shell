package com.xiufeigo.opencodeshell.ui.session.composer

import com.xiufeigo.opencodeshell.v2.McpResourceCatalog
import com.xiufeigo.opencodeshell.v2.optStringOrNull
import org.json.JSONObject

/**
 * F-1: the two `@` context sources upstream's composer merges beside
 * agents/skills/files — **references** and **MCP resources** — as pure rows.
 *
 * Upstream anchors:
 * - `references()` (`_upstream/packages/app/src/composer/model.ts:146-165`):
 *   `GET /reference` rows, `hidden` filtered out, `label = "@" + name`, the
 *   mention path is `reference.path` and the trailing description is
 *   `reference.description ?? (source.type === "git" ? source.repository :
 *   source.path)` (`:147-148`).
 * - `resources()` (`model.ts:166-190`): `GET /mcp/resource`'s `resources[]`,
 *   `label = "@" + name`, the mention target is the **uri** (`path:
 *   resource.uri`, `url: resource.uri`) and the mime is `mimeType ??
 *   "text/plain"`.
 * - the merge order (`model.ts:195-…`): `context = [...references, ...skills,
 *   ...agents, ...resources, ...recent]`.
 *
 * Wire shapes (`_upstream/packages/schema/src/`):
 * - `Reference.Info` = `{name, path, description?, hidden?, source:
 *   {type:"local",path,description?,hidden?} | {type:"git",repository,branch?,
 *   description?,hidden?}}` (`reference.ts:21-46`);
 * - `Mcp.ResourceCatalog` = `{resources:[{server,name,uri,description?,
 *   mimeType?}], templates:[…]}` (`mcp.ts:99-121`).
 *
 * Pure Kotlin (`org.json` only) so both mappings are JVM-testable; the rows are
 * what [ComposerSuggestions.mentionItems] consumes, so no UI object ever parses
 * the wire.
 */
object MentionSources {

    /**
     * One `GET /api/reference` row. [hidden] rows never reach the menu
     * (`model.ts:148` `.filter((reference) => !reference.hidden)`).
     */
    data class Reference(
        val name: String,
        val path: String,
        val description: String? = null,
        val hidden: Boolean = false,
    )

    /** One `GET /api/mcp/resource` entry (`Mcp.Resource`). */
    data class Resource(
        val server: String,
        val name: String,
        val uri: String,
        val description: String? = null,
        val mimeType: String? = null,
    )

    /**
     * The mention mime upstream stamps on a reference attachment
     * (`model.ts:159` `mime: "application/x-directory"`) — it is also what makes
     * the editor render `data-mention=reference` (`editor.tsx:366`).
     */
    const val DIRECTORY_MIME = "application/x-directory"

    /** `model.ts:181` `mime: resource.mimeType ?? "text/plain"`. */
    const val DEFAULT_RESOURCE_MIME = "text/plain"

    /**
     * `GET /api/reference` rows → menu rows: a nameless row is unusable
     * (`label`/`filename` both come from `name`) and is dropped, like the
     * upstream `hidden` filter drops the invisible ones. Order is the server's.
     */
    fun references(json: List<JSONObject>): List<Reference> = json.mapNotNull { obj ->
        val name = optStringOrNull(obj, "name")?.trim().orEmpty()
        if (name.isEmpty()) return@mapNotNull null
        Reference(
            name = name,
            path = optStringOrNull(obj, "path").orEmpty(),
            description = referenceDescription(obj),
            hidden = obj.optBoolean("hidden", false),
        )
    }

    /**
     * `reference.description ?? (source.type === "git" ? source.repository :
     * source.path)` (`model.ts:147-148`) — the fallback is what tells two
     * same-named references apart in the menu's trailing column.
     */
    fun referenceDescription(obj: JSONObject): String? {
        optStringOrNull(obj, "description")?.takeIf { it.isNotBlank() }?.let { return it }
        val source = obj.optJSONObject("source") ?: return null
        val fallback = if (optStringOrNull(source, "type") == "git") {
            optStringOrNull(source, "repository")
        } else {
            optStringOrNull(source, "path")
        }
        return fallback?.takeIf { it.isNotBlank() }
    }

    /**
     * The `GET /api/mcp/resource` rows (`{server, name, uri, description?,
     * mimeType?}` — `Mcp.Resource`) → menu rows. A resource without a `uri`
     * cannot be mentioned (the uri **is** the prompt target) and is dropped; a
     * nameless one falls back to its uri so the row still renders.
     */
    fun resources(rows: List<JSONObject>): List<Resource> {
        val out = ArrayList<Resource>(rows.size)
        for (obj in rows) {
            val uri = optStringOrNull(obj, "uri")?.trim().orEmpty()
            if (uri.isEmpty()) continue
            out += Resource(
                server = optStringOrNull(obj, "server").orEmpty(),
                name = optStringOrNull(obj, "name")?.trim().orEmpty().ifEmpty { uri },
                uri = uri,
                description = optStringOrNull(obj, "description")?.takeIf { it.isNotBlank() },
                mimeType = optStringOrNull(obj, "mimeType")?.takeIf { it.isNotBlank() },
            )
        }
        return out
    }

    /**
     * The same mapping straight off `GET /api/mcp/resource`'s decoded envelope
     * ([McpResourceCatalog] — `V2Api.mcpResourceCatalog`); only the `resources[]`
     * half is mentionable, the `templates[]` need an RFC 6570 expansion the
     * composer cannot ask the user for (`schema/src/mcp.ts:108-115`).
     */
    fun resources(catalog: McpResourceCatalog): List<Resource> =
        resources(catalog.resources.map { it.raw })
}
