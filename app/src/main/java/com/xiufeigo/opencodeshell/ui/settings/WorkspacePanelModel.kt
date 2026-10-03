package com.xiufeigo.opencodeshell.ui.settings

import com.xiufeigo.opencodeshell.OpenCodeClient
import com.xiufeigo.opencodeshell.data.ProjectPaths
import org.json.JSONArray
import org.json.JSONObject

/**
 * B-10: the pure model behind the Workspaces page
 * (`settings/workspaces/workspaces.tsx`).
 *
 * Upstream builds its inventory from `project.list()` — every project's
 * `worktrees[]` becomes a `{directory, project}` pair (`workspaces/paths.ts:31-35`
 * `workspaceInventory`) — then filters by project (`filterWorkspaceInventory`),
 * counts the sessions whose `location.directory` lives under each worktree
 * (`sessionsForWorkspace`) and shows the newest one's relative time.
 *
 * The Android read is `GET /experimental/worktree` (v1) / flattened
 * `GET /api/worktree?projectID=` rows (v2), so the owner is carried **on the
 * row**: [SettingsV2Delegate] stamps `projectID`/`projectName` while it
 * flattens the per-project responses, and a v1 payload that names its project
 * (`project`/`projectID`) is read the same way. A row without an owner keeps
 * `null` — the page then offers no filter (upstream gates the picker on having
 * more than one project) instead of inventing a bucket.
 *
 * Everything here is Compose-free so the parser, the filter and the
 * linked-session rules are JVM-testable without a socket.
 */
data class WorkspaceRow(
    val directory: String,
    val name: String,
    val branch: String? = null,
    /** The owning project's id, when the payload says which project it came from. */
    val projectID: String? = null,
    /** The owning project's display label, when the payload carries one. */
    val projectName: String? = null,
)

/** `filterWorkspaceInventory` (`workspaces/paths.ts:37-42`): `"all"` = no filter. */
fun filterWorkspaceRows(rows: List<WorkspaceRow>, projectID: String): List<WorkspaceRow> =
    if (projectID == WORKSPACE_FILTER_ALL) rows else rows.filter { it.projectID == projectID }

/** Upstream's sentinel option id (`workspaces.tsx:88` `{ id: "all", … }`). */
const val WORKSPACE_FILTER_ALL: String = "all"

/**
 * The filter's stops (`workspaces.tsx:87-98` `projectOptions`): "all" first,
 * then every project that owns at least one row, in first-seen order. A single
 * distinct project produces no options — upstream hides the picker then
 * (`projects().length > 1`).
 */
fun workspaceProjectOptions(rows: List<WorkspaceRow>): List<Pair<String, String>> {
    val projects = LinkedHashMap<String, String>()
    for (row in rows) {
        val id = row.projectID?.takeIf { it.isNotBlank() } ?: continue
        projects.putIfAbsent(id, row.projectName?.takeIf { it.isNotBlank() } ?: id)
    }
    if (projects.size < 2) return emptyList()
    return buildList {
        add(WORKSPACE_FILTER_ALL to WORKSPACE_FILTER_ALL)
        projects.forEach { (id, label) -> add(id to label) }
    }
}

/**
 * One `GET /experimental/worktree` / `GET /api/worktree` row. Field names are
 * read tolerantly (`name`/`directory`/`branch`) exactly like
 * [worktreePageRows] — the v1 shape is the same `Worktree.Directory` — plus the
 * owner keys the v2 flattening stamps. An entry without a directory is skipped
 * rather than shown as a blank row.
 *
 * The owner keys are read **type-strictly** (`opt` + cast): `optString` maps a
 * JSON `null` to the literal `"null"`, which would become a project bucket
 * named "null" — the same trap [OpenCodeClient.Session.fromJson] documents for
 * `directory`.
 */
fun workspaceRows(json: String?): List<WorkspaceRow> {
    val trimmed = json?.trim().orEmpty()
    if (trimmed.isEmpty() || !trimmed.startsWith("[")) return emptyList()
    val array = runCatching { JSONArray(trimmed) }.getOrNull() ?: return emptyList()
    return (0 until array.length()).mapNotNull { index ->
        val obj = array.optJSONObject(index) ?: return@mapNotNull null
        val directory = listOf("directory", "worktree", "path")
            .firstNotNullOfOrNull { key -> obj.optString(key, "").trim().takeIf { it.isNotEmpty() } }
            ?: return@mapNotNull null
        val name = obj.optString("name", "").trim().takeIf { it.isNotEmpty() }
            ?: worktreeDisplayName(directory)
        val owner = obj.optJSONObject("project")
        WorkspaceRow(
            directory = directory,
            name = name,
            branch = listOf("branch", "ref")
                .firstNotNullOfOrNull { key -> obj.optString(key, "").trim().takeIf { it.isNotEmpty() } },
            projectID = stringField(obj, "projectID") ?: stringField(owner, "id"),
            projectName = stringField(obj, "projectName") ?: stringField(owner, "name"),
        )
    }
}

/** A non-blank string field, or null for absent/JSON-`null`/non-string values. */
private fun stringField(obj: JSONObject?, key: String): String? =
    (obj?.opt(key) as? String)?.trim()?.takeIf { it.isNotEmpty() && it != "null" }

/**
 * The rows of a session-list payload: `GET /session` answers a **bare array**
 * (v1), `GET /api/session` an envelope `{data:[…]}` (v2). Both element shapes
 * are read by [OpenCodeClient.Session.fromJson], which already accepts v1's
 * flat `directory` and v2's nested `location.directory`.
 */
fun sessionRows(json: String?): List<OpenCodeClient.Session> {
    val trimmed = json?.trim().orEmpty()
    if (trimmed.isEmpty()) return emptyList()
    val array = runCatching {
        if (trimmed.startsWith("[")) JSONArray(trimmed) else JSONObject(trimmed).optJSONArray("data")
    }.getOrNull() ?: return emptyList()
    return (0 until array.length()).mapNotNull { index ->
        array.optJSONObject(index)?.let(OpenCodeClient.Session::fromJson)
    }
}

/**
 * `sessionsForWorkspace` (`workspaces/paths.ts:46-52`): the **unarchived**
 * sessions whose directory lives under [workspace], newest first. Upstream's
 * `containsDirectory(workspace, session.location.directory)` is
 * [ProjectPaths.containsDirectory] here (same normalisation, same Windows
 * case-fold), and "archived" is the store's own `time.archived > 0` rule
 * (`OpenCodeClient.Session.archivedAt`).
 */
fun sessionsForWorkspace(
    sessions: List<OpenCodeClient.Session>,
    workspace: String,
): List<OpenCodeClient.Session> = sessions
    .filter { (it.archivedAt ?: 0L) <= 0L }
    .filter { ProjectPaths.containsDirectory(workspace, it.directory) }
    .sortedByDescending { it.updatedAt }

/**
 * `workspaces.tsx:143-149` `workspacesWithoutSessions`: the rows of [rows]
 * with **no** linked session. Rows whose session read failed are excluded —
 * upstream returns `[]` while the session query is pending or errored, so an
 * unanswerable read can never look like "this worktree is empty".
 */
fun workspacesWithoutSessions(
    rows: List<WorkspaceRow>,
    sessionsByDirectory: Map<String, List<OpenCodeClient.Session>>,
): List<WorkspaceRow> {
    if (sessionsByDirectory.isEmpty()) return emptyList()
    return rows.filter { row ->
        val sessions = sessionsByDirectory[row.directory] ?: return@filter false
        sessionsForWorkspace(sessions, row.directory).isEmpty()
    }
}
