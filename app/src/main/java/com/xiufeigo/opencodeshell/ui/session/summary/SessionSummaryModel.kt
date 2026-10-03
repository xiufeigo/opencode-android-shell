package com.xiufeigo.opencodeshell.ui.session.summary

import com.xiufeigo.opencodeshell.v2.optStringOrNull

/**
 * Row/segment model for the session summary panel (A1).
 *
 * Upstream renders it as a popover with two cards
 * (`_upstream/packages/app/src/session/summary/panel.tsx:44-142`): a **project
 * card** with workspace / branch / changes / background-task rows, and a
 * **server card** (`summary/server-panel.tsx:66-101`). This object owns the pure
 * part — which rows exist, in what order, and how their values are derived — so
 * the rules are JVM-testable; the composable
 * ([SessionSummaryPanel]) owns only layout and copy.
 *
 * Data status (v2 head):
 * - the **background-task row is produced here** but the host has no task model
 *   yet: upstream derives the list from assistant tool parts plus child-session
 *   state (`session/requests/background.ts:85-115`), which lives in
 *   `ChatStore`/`ChatViewModel`. Until that is exposed the panel shows the
 *   upstream empty copy (`session.background` submenu, honest empty state).
 * - the server card carries upstream's four submenus (MCP / LSP / Plugins /
 *   Skills); their catalogs are read-only V2 calls
 *   ([com.xiufeigo.opencodeshell.ui.session.summary.SummaryServiceSource]).
 */
object SessionSummaryModel {

    /** Row kind; the composable maps each to its icon/label resource. */
    enum class Kind { WORKSPACE, BRANCH, CHANGES, SERVER }

    /**
     * @param value already display-ready (may be long; the row ellipsizes)
     * @param mono paths/branches read as code, counts do not
     */
    data class Row(
        val kind: Kind,
        val value: String,
        val mono: Boolean,
    )

    /**
     * Project card rows in upstream order (`panel.tsx:48-114`): workspace,
     * branch, changes — each omitted when unknown rather than shown blank.
     */
    fun projectRows(
        directory: String?,
        branch: String?,
        changedFiles: Int?,
        additions: Int? = null,
        deletions: Int? = null,
    ): List<Row> = buildList {
        directoryLabel(directory)?.let { add(Row(Kind.WORKSPACE, it, mono = true)) }
        branchLabel(branch)?.let { add(Row(Kind.BRANCH, it, mono = true)) }
        changesLabel(changedFiles, additions, deletions)?.let { add(Row(Kind.CHANGES, it, mono = false)) }
    }

    /** Server card row: the resolved label, falling back to the raw URL. */
    fun serverRows(label: String?, url: String?): List<Row> {
        val value = label?.takeIf { it.isNotBlank() } ?: url?.takeIf { it.isNotBlank() } ?: return emptyList()
        return listOf(Row(Kind.SERVER, value, mono = false))
    }

    /** Last path segment, tolerating both separators (the app runs on Windows too). */
    fun directoryLabel(path: String?): String? {
        val trimmed = path?.trim()?.trimEnd('\\', '/') ?: return null
        if (trimmed.isEmpty()) return null
        val cut = maxOf(trimmed.lastIndexOf('\\'), trimmed.lastIndexOf('/'))
        return trimmed.substring(cut + 1).ifEmpty { trimmed }
    }

    /** `null` when the workspace is not a repository (the server reports `branch: null`). */
    fun branchLabel(branch: String?): String? = branch?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * `"3 files · +12 −4"`; the `+A −D` half only appears when the server sent
     * both counts, and a zero-change session yields `null` so the row disappears
     * instead of claiming "0 files".
     */
    fun changesLabel(files: Int?, additions: Int?, deletions: Int?): String? {
        val count = files ?: return null
        if (count <= 0) return null
        val base = "$count file" + if (count == 1) "" else "s"
        val both = additions != null && deletions != null
        if (!both) return base
        return "$base · +$additions \u2212$deletions"
    }

    // ---------------------------------------------- server submenus (B: wave)

    /**
     * The four server submenus of `server-panel.tsx:4-9` (`services`), in
     * upstream order. The composable maps each to its label/empty copy.
     */
    enum class ServiceKind { MCP, PLUGINS, SKILLS, LSP }

    /**
     * One row of a service submenu: the configured name, plus the optional MCP
     * status **token** (`server-panel.tsx:180-187`): `failed` / `connecting` /
     * `auth`. The composable resolves the token to the localized
     * `session.summary.failed|connecting|needsAuth` copy so this layer stays
     * pure; `null` = connected/quiet.
     *
     * @param rawStatus the status the server sent verbatim
     *   (`connected`/`pending`/`disabled`/`failed`/`needs_auth`) — the MCP row's
     *   `Switch` reads its position and its read-only state off this
     *   (`server-panel.tsx:225-232`), which the token alone cannot express
     *   (`disabled` and `connected` both tokenise to `null`). `null` for the
     *   plugin/skill/LSP rows, which carry no live status.
     * @param error the MCP server's own failure message
     *   (`status.error`, `server-panel.tsx:229-232`); shown in the row where the
     *   desktop uses it as a tooltip.
     */
    data class ServiceItem(
        val name: String,
        val status: String? = null,
        val rawStatus: String? = null,
        val error: String? = null,
    ) {
        /** `server-panel.tsx:226` `enabled()`: everything but `disabled` reads as on. */
        val enabled: Boolean get() = !rawStatus.equals("disabled", ignoreCase = true)

        /** `server-panel.tsx:230`: the server's own connection attempt is in flight. */
        val pending: Boolean get() = rawStatus.equals("pending", ignoreCase = true)
    }

    /**
     * The `info[service]` keys of `GET /api/config` — upstream's `services`
     * types (`server-panel.tsx:4-9`), used by [configPath] and by the panel's
     * footer.
     */
    const val SERVICE_MCP: String = "mcp"
    const val SERVICE_PLUGINS: String = "plugins"
    const val SERVICE_SKILLS: String = "skills"
    const val SERVICE_LSP: String = "lsp"

    /** One background-work row (`session/summary/background.tsx:5-10`). */
    data class BackgroundTask(val type: String, val label: String, val agent: String? = null) {
        /** `background.tsx:24-27`: "Shell", "Agent", or the capitalised agent name. */
        val typeLabel: String
            get() = when {
                type == "shell" -> "Shell"
                agent.isNullOrBlank() -> "Agent"
                else -> agent.replaceFirstChar { it.uppercase() }
            }
    }

    /**
     * Background list, capped at the 10 rows upstream renders
     * (`background.tsx:57` `props.tasks.slice(0, 10)`). `null` input = the host
     * has no task model (the documented gap) and the panel shows the empty
     * state instead of inventing rows.
     */
    fun backgroundTasks(tasks: List<BackgroundTask>?): List<BackgroundTask> =
        tasks.orEmpty().take(BACKGROUND_TASK_LIMIT)

    /**
     * One service submenu's rows. [items] `null` = the host could not supply
     * the catalog (honest empty state upstream already has copy for); names
     * are shown as given, sorted like upstream's MCP list
     * (`server-panel.tsx:170-172` `toSorted(name.localeCompare)`).
     */
    fun serviceItems(items: List<ServiceItem>?): List<ServiceItem> =
        items.orEmpty().sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })

    /** Skill/plugin name lists (upstream `ServiceCatalog` rows are plain names). */
    fun nameItems(names: List<String>?): List<ServiceItem> =
        serviceItems(names.orEmpty().map { ServiceItem(it) })

    /**
     * `configured-lsp.ts` over the `GET /api/config` JSON (the
     * `SettingsServerApi` document entries). Walks the entries in order:
     *
     * - a non-`document` entry, or one without `info.lsp`, is skipped;
     * - `info.lsp: false` (any boolean) resets the accumulated names to none;
     * - `info.lsp: {name: {disabled?}}` drops every previously known name it
     *   overrides, then adds the not-disabled ones.
     *
     * The result is sorted case-insensitively (`toSorted(localeCompare)`).
     * Malformed JSON parses as "nothing configured" rather than throwing.
     */
    fun configuredLsps(configJson: String?): List<String> {
        val array = parseConfigArray(configJson) ?: return emptyList()
        var names: List<String> = emptyList()
        for (i in 0 until array.length()) {
            val entry = array.optJSONObject(i) ?: continue
            if (entry.optString("type") != "document") continue
            val info = entry.optJSONObject("info") ?: continue
            if (!info.has("lsp")) continue
            val lsp = info.opt("lsp")
            if (lsp is Boolean) {
                names = emptyList()
                continue
            }
            val map = lsp as? org.json.JSONObject ?: continue
            val added = map.keys().asSequence()
                .filter { name -> !(map.optJSONObject(name)?.optBoolean("disabled") ?: false) }
                .toList()
            names = names.filterNot { map.has(it) } + added
        }
        return names.distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)
    }

    /**
     * `ServiceConfigLink`'s path pick (`server-panel.tsx:445-452`): over the
     * `GET /api/config` entries that are documents with a `.json`/`.jsonc`
     * path, the **last** one carrying `info[service]`, else the last document
     * at all. `null` = the page lists no such document, which upstream reports
     * as `session.summary.configFileMissing` ("No configuration file found").
     *
     * [service] is upstream's service id — the `info` key, one of
     * `mcp` / `plugins` / `skills` / `lsp` (`server-panel.tsx:4-9`).
     */
    fun configPath(configJson: String?, service: String): String? {
        val array = parseConfigArray(configJson) ?: return null
        val documents = (0 until array.length()).mapNotNull { index ->
            val entry = array.optJSONObject(index) ?: return@mapNotNull null
            if (entry.optString("type") != "document") return@mapNotNull null
            val path = optStringOrNull(entry, "path") ?: return@mapNotNull null
            if (!CONFIG_PATH_SUFFIX.containsMatchIn(path)) return@mapNotNull null
            path to entry.optJSONObject("info")
        }
        return documents.lastOrNull { it.second?.has(service) == true }?.first
            ?: documents.lastOrNull()?.first
    }

    /** Upstream's `/\.jsonc?$/` (`server-panel.tsx:446`). */
    private val CONFIG_PATH_SUFFIX = Regex("\\.jsonc?$")

    /** `GET /api/config` accepts either the bare array or an `{entries}` envelope. */
    private fun parseConfigArray(configJson: String?): org.json.JSONArray? {
        val trimmed = configJson?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        return runCatching { org.json.JSONArray(trimmed) }.getOrNull()
            ?: runCatching { org.json.JSONObject(trimmed).optJSONArray("entries") }.getOrNull()
    }

    private const val BACKGROUND_TASK_LIMIT = 10
}
