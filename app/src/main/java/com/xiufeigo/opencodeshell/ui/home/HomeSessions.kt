package com.xiufeigo.opencodeshell.ui.home

import com.xiufeigo.opencodeshell.OpenCodeClient
import com.xiufeigo.opencodeshell.data.ProjectPaths
import java.time.Instant
import java.time.ZoneId

/**
 * Pure (JVM-testable) model of Home's sessions column (`research/02` §1.3):
 * the 64-row cap, the Today / Yesterday / Older grouping, the search filter, the
 * project label, the **selected-project scope** ([rows]' `directories` and
 * [placeholderScope]) and the path normalisation it matches on ([pathKey],
 * [matchKey], [containsDirectory]). No Compose, no Android — [HomeScreen] only
 * renders it.
 *
 * The i18n literals for the group titles (`home.sessions.group.*`) and for the
 * search placeholder live in `strings.xml`; this file decides the **bucket** and
 * the **scope**, never the text.
 */
object HomeSessions {

    /** `home-sessions-controller`: the recent list is capped at 64 rows. */
    const val MAX_SESSIONS = 64

    /** `HomeSessionSkeleton`: one title row + 4 `h-10` bars. */
    const val SKELETON_ROWS = 4

    enum class Group { TODAY, YESTERDAY, OLDER }

    /** One `HomeSessionRow` (h-10: avatar + title + project name, no time). */
    data class Row(
        val id: String,
        val title: String,
        val directory: String?,
        /** Project label, i.e. `basename(directory)`; null when unknown. */
        val project: String?,
        val updatedAt: Long,
    )

    data class Section(val group: Group, val rows: List<Row>)

    /**
     * Upstream's Home predicate (`session/controller.tsx:193`):
     *
     * ```ts
     * sessions.filter((item) => !item.parentID && !item.time?.archived)
     * ```
     *
     * The archive half is the whole predicate this shell can evaluate: v1's
     * `GET /session` carries no `parentID` (the client's `Session` has no such
     * field — see `v2/V2Api.Session` for the model that does), so nothing is
     * dropped for being a child, exactly as before.
     *
     * [OpenCodeClient.Session.archivedAt] is `null` when the server never wrote
     * `time.archived` and `0` after the un-archive write
     * (`PATCH {"time":{"archived":0}}`), so the test is `> 0` — a presence test
     * would hide an un-archived session forever. The filter is unavoidable
     * client-side: `GET /session` keeps returning archived rows (verified live
     * on 1.18.31), so this is the only thing that makes 归档会话 remove a row.
     */
    fun isArchived(session: OpenCodeClient.Session): Boolean = (session.archivedAt ?: 0L) > 0L

    /**
     * Newest first, capped at [limit]. `listSessions()` already sorts by
     * `updatedAt` descending, but the sort is repeated here so the view cannot
     * depend on its caller's ordering.
     *
     * Archived sessions are dropped first ([isArchived]): the cap is a row budget
     * for the visible list, so a hidden session must not consume one of its
     * slots.
     *
     * @param sessions [ChatViewModel.listSessions] output
     * @param directories the selected project's directories —
     *   `[project.worktree, ...(project.sandboxes ?? [])]`
     *   (`home/projects/controller.tsx:41-43`, [HomeProjects.directoriesFor]).
     *   A session belongs to the project when its own directory is that directory
     *   **or below it** ([containsDirectory], upstream `isProjectDirectory`),
     *   which is what makes a worktree or package session show up under its
     *   project instead of nowhere — `session.directory` is the only attribution
     *   this server offers (`projectID` is `global` for every session).
     *   Entered **before** the cap, like upstream
     *   (`session/records.ts:17-21` filtered, then `controller.tsx:91` sliced), so
     *   the 64-row budget is spent on rows the list can actually show.
     *
     *   An empty/blank list is "no project selected": then every session is
     *   listed, exactly as `rows(sessions)` behaved before a selection existed.
     */
    fun rows(
        sessions: List<OpenCodeClient.Session>,
        limit: Int = MAX_SESSIONS,
        directories: List<String> = emptyList(),
    ): List<Row> {
        val scope = directories.filter { it.isNotBlank() }
        return sessions.filterNot { isArchived(it) }
            .filter { scope.isEmpty() || containsAny(scope, it.directory) }
            .sortedByDescending { it.updatedAt }
            .take(limit)
            .map { session ->
                Row(
                    id = session.id,
                    title = session.title,
                    directory = session.directory,
                    project = projectName(session.directory),
                    updatedAt = session.updatedAt,
                )
            }
    }

    /**
     * `isProjectDirectory(project, directory)` (`workspaces/paths.ts:83-86`):
     * the session's directory is the project's worktree/a sandbox, or nested
     * inside one. `false` for an unknown session directory — an unattributable
     * session belongs to no project.
     */
    fun containsDirectory(parent: String?, child: String?): Boolean =
        ProjectPaths.containsDirectory(parent, child)

    /** `directories.some { containsDirectory(it, child) }`; `false` for an empty set. */
    fun containsAny(directories: Collection<String>, child: String?): Boolean =
        ProjectPaths.containsAny(directories, child)

    /**
     * Groups rows in the display order Today → Yesterday → Older, dropping empty
     * groups (upstream only renders a `HomeSessionGroupHeader` per non-empty
     * group).
     *
     * @param now epoch millis "today" is measured against (injected for tests)
     * @param zone calendar zone; `updatedAt` is bucketed by **local calendar
     *   day**, not by a 24 h window, so 23:59 → 00:01 stays "Yesterday"/"Today"
     */
    fun sections(
        rows: List<Row>,
        now: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<Section> = Group.values().mapNotNull { group ->
        rows.filter { groupOf(it.updatedAt, now, zone) == group }
            .takeIf { it.isNotEmpty() }
            ?.let { Section(group, it) }
    }

    /**
     * Bucket of a single row. `updatedAt <= 0` means "time unknown" — it cannot
     * claim to be today, so it lands in [Group.OLDER] rather than jumping to the
     * top of the list.
     */
    fun groupOf(updatedAt: Long, now: Long, zone: ZoneId): Group {
        if (updatedAt <= 0L) return Group.OLDER
        val day = Instant.ofEpochMilli(updatedAt).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return when (day) {
            today -> Group.TODAY
            today.minusDays(1) -> Group.YESTERDAY
            else -> Group.OLDER
        }
    }

    /**
     * WAVE 2.3b P1 (A2 #9) — the search source: upstream filters the
     * **untruncated** `searchRecords` (`controller.tsx:253-254`
     * `searchRecords: allRecords`), not the 64-capped `records`. [all] is the
     * full fetched list; [visible] is the capped, scoped rows the list shows.
     * When [all] is null/empty (hosts that only have the visible list), the
     * search degrades to [visible] — the pre-2.3b behaviour.
     *
     * The project scope still applies first (upstream builds `allRecords` with
     * the scope inside `buildHomeSessionRecords`), then the result is
     * de-duplicated by id and sorted newest-first without a cap.
     */
    fun searchRows(
        all: List<OpenCodeClient.Session>?,
        visible: List<Row>,
        directories: List<String> = emptyList(),
    ): List<Row> {
        if (all.isNullOrEmpty()) return visible
        val scope = directories.filter { it.isNotBlank() }
        val scoped = all.filterNot { isArchived(it) }
            .filter { scope.isEmpty() || containsAny(scope, it.directory) }
        val merged = (scoped.map {
            Row(
                id = it.id,
                title = it.title,
                directory = it.directory,
                project = projectName(it.directory),
                updatedAt = it.updatedAt,
            )
        } + visible).distinctBy { it.id }
        return merged.sortedByDescending { it.updatedAt }
    }

    /**
     * WAVE 2.3b P1 (A2 #9) — `looksLikeSessionID` (`session/search.ts:1-3`):
     * a pasted session ID (`ses_` + >20 chars) bypasses substring search for a
     * direct `lookup`, with the exact hit pinned above the fuzzy results
     * (`search.ts:86-101`).
     */
    fun looksLikeSessionID(value: String): Boolean {
        val id = value.trim()
        return id.length > 20 && id.startsWith("ses_")
    }

    /**
     * `HomeSessionSearch` filter: case-insensitive substring over the title and
     * the project name. Upstream ranks with fuzzysort inside the overlay panel;
     * M3 keeps the same two fields and matches substrings (recorded as a
     * deviation in `research/_M3_home_status.md`).
     */
    fun filter(rows: List<Row>, query: String): List<Row> {
        val needle = query.trim()
        if (needle.isEmpty()) return rows
        // A2 #9: a pasted session ID matches its row directly — an id is never
        // a title/project substring, so this branch only fires for ID input.
        if (looksLikeSessionID(needle)) {
            val exact = rows.filter { it.id == needle }
            if (exact.isNotEmpty()) return exact
        }
        return rows.filter { row ->
            row.title.contains(needle, ignoreCase = true) ||
                row.project?.contains(needle, ignoreCase = true) == true
        }
    }

    /**
     * `displayName(project)` stand-in: the last path segment. Handles POSIX and
     * Windows directories (the server may be either) as well as trailing
     * separators; a **root** (`/`, `C:\`, `C:/`) has no name and yields null.
     */
    fun projectName(directory: String?): String? {
        val path = directory?.trim().orEmpty()
        if (path.isEmpty()) return null
        val trimmed = path.trimEnd('/', '\\')
        // `C:` after trimming is a drive root, not a directory name.
        if (trimmed.isEmpty() || trimmed.endsWith(":")) return null
        return trimmed.substringAfterLast('/').substringAfterLast('\\').ifEmpty { null }
    }

    /**
     * `displayName(project)` (`shell/layout/helpers.ts:50`: `project.name ||
     * getFilename(project.worktree) || project.worktree`) — the label the
     * project-scoped affordances print. Every project row in this shell is named
     * [projectName]`(worktree) ?: worktree` ([HomeProjects.merge]), so a bare
     * directory resolves to the same label.
     */
    fun displayName(directory: String?): String? {
        val path = directory?.trim().orEmpty()
        if (path.isEmpty()) return null
        return projectName(path) ?: path
    }

    /**
     * `pathKey` (`_upstream/packages/app/src/workspaces/path-key.ts:18-24`) — the
     * normalisation the project filter compares on, so a selection survives the
     * server writing `/home/me/proj/` while the project list holds
     * `/home/me/proj`.
     *
     * A **Windows** value (drive letter in position 2, or a UNC `\\host`) folds
     * `\` to `/` first; only `/` is a trailing trim, exactly like upstream, and
     * the two roots survive: an all-separator POSIX path stays `/` and a bare
     * drive becomes `C:/`. A leading `\\?\` / `//?/` extended prefix is
     * tolerated (see [ProjectPaths.stripExtendedPrefix]).
     *
     * `null` for a missing/blank directory: a session the server gave no
     * directory for is not attributable to any project, so it never matches a
     * selection (and `/` never collapses into it).
     *
     * The implementation lives in `data/ProjectPaths` so the client-local
     * project store compares directories the same way; this name is kept because
     * it is the upstream one and `HomeProjectFilterTest` pins it.
     */
    fun pathKey(path: String?): String? = ProjectPaths.pathKey(path)

    /**
     * [pathKey] with the case-fold upstream's `containsDirectory` applies: a
     * **Windows** key is case-insensitive, a POSIX one is not. This is the key
     * the project→session filter (and the project column's catalogue lookup)
     * really compares, so `C:\Src\App` and `c:/src/app` are one project while
     * `/Work/api` and `/work/api` stay two.
     */
    fun matchKey(path: String?): String? = ProjectPaths.matchKey(path)

    /**
     * The scope of the sessions search field's placeholder, or `null` for the
     * unscoped copy — `home/sessions/search.ts:45-53`:
     *
     * ```ts
     * const project = home.project.selected()
     * if (project) return language.t("home.sessions.search.placeholder.scoped", { scope: displayName(project) })
     * ...
     * return language.t("home.sessions.search.placeholder")
     * ```
     *
     * The strings themselves live in `strings.xml`
     * (`oc_home_search` / `oc_home_search_scoped`); this decides which one is
     * shown, never the text. The multi-server branch of the upstream memo has no
     * counterpart here: the shell always has exactly one server.
     */
    fun placeholderScope(directory: String?): String? = displayName(directory)
}
