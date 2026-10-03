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
        /**
         * `Project.icon.color` / `Project.icon.override` of the project this
         * session belongs to ([HomeProjects.forSession]), resolved here because
         * v1's session row carries no project attribution of its own (see
         * [rows]). The row's avatar draws them through the same
         * `override → color → initial` chain the projects column uses, instead
         * of the name-initial stand-in. `null` when the session matches no
         * project (or the caller passed none) — the avatar then keeps the
         * neutral surface. Appended last, with defaults, so every existing
         * construction compiles unchanged.
         */
        val iconColor: String? = null,
        val iconOverride: String? = null,
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
     * v54 P1 「子路径会话在 Home 列表与搜索里双双缺失」: the directory a session
     * actually lives in — [OpenCodeClient.Session.directory] with its
     * [OpenCodeClient.Session.subpath] appended.
     *
     * v2 writes a session created in a window bound to a subpath as the
     * **project root** plus the subpath (`location.directory=/repo`,
     * `subpath=app/src`, measured in `research/sess.json` and every sessions
     * dump under `research/`), while the shell registers the project the window
     * was bound to (`…/app/src`) and scopes the column to it. Matching the raw
     * `directory` therefore asked `containsDirectory(…/app/src, /repo)` — false
     * — so the row was dropped from the list **and** from the search panel, and
     * an empty column showed no copy at all
     * (`research/v54_verify/tB/tB_50_home.png`, `tB_82_home_search.png`).
     *
     * The join is deliberately forgiving, because the server's two spellings are
     * not exclusive: a blank subpath keeps [directory] as it is, and a subpath
     * that is already the directory's own tail (`/repo` + `repo`) is not
     * appended twice. The result is normalised through [ProjectPaths.pathKey]'s
     * rules, so the joined path compares exactly like any other directory.
     *
     * v58 P0: the tail test has to see the whole subpath, not its last segment.
     * 2.0.18 answers a session created in `<repo>/app/src` with
     * `location.directory=<repo>/app/src` **and** `subpath=app/src` (verified
     * live), so a last-segment test (`src` vs `app/src`) always missed and the
     * value this returned — `<repo>/app/src/app/src` — became the next create's
     * directory: one more `app/src` per session until the server answered
     * `500 FileSystem.realPath … ENOENT` on every request of that session
     * (`research/v58_verify/5556_b/REPORT.md` §0). The directory is now tested
     * against the subpath **as a whole** and the result goes through
     * [ProjectPaths.normalizeLocation], so a directory that already carries the
     * segment is returned unchanged and a polluted one heals in one step.
     */
    fun sessionDirectory(session: OpenCodeClient.Session): String? {
        val base = session.directory?.trim().orEmpty()
        val sub = session.subpath?.trim()?.trim('/', '\\').orEmpty()
        if (base.isEmpty()) return ProjectPaths.normalizeLocation(session.directory)
        if (sub.isEmpty()) return ProjectPaths.normalizeLocation(base)
        val already = endsWithSubpath(base, sub)
        val joined = if (already) base else base.trimEnd('/', '\\') + "/" + sub
        // A Windows base keeps its separator convention (`C:\repo` + `app/src`
        // must not become `C:\repo/app/src`, which no project row is spelled).
        val spelled = if (!already && base.contains('\\') && !base.contains('/')) {
            joined.replace('/', '\\')
        } else {
            joined
        }
        return ProjectPaths.normalizeLocation(spelled)
    }

    /**
     * Does [base] already **end** with the multi-segment [sub]? `/repo/app/src`
     * against `app/src` is the v58 P0 case, `/repo` against `repo` the one this
     * shell always had; comparison is case-insensitive under a Windows root and
     * case-sensitive under a POSIX one (the [ProjectPaths.matchKey] rule).
     */
    private fun endsWithSubpath(base: String, sub: String): Boolean {
        val trimmed = base.trimEnd('/', '\\').replace('\\', '/')
        val tail = sub.trimEnd('/', '\\').replace('\\', '/')
        if (tail.isEmpty() || trimmed.length < tail.length) return false
        val windows = (trimmed.length > 1 && trimmed[1] == ':') || trimmed.startsWith("//")
        if (!trimmed.regionMatches(trimmed.length - tail.length, tail, 0, tail.length, windows)) {
            return false
        }
        val boundary = trimmed.length - tail.length - 1
        return boundary < 0 || trimmed[boundary] == '/' || trimmed[boundary] == '\\'
    }

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
     * @param projects the project rows the session's icon is attributed from
     *   ([HomeProjects.forSession]) — the same catalogue `projectNames` comes
     *   from. Empty (the default) leaves every row's icon fields null, so a
     *   caller that has no catalogue renders the neutral avatar it always did.
     */
    fun rows(
        sessions: List<OpenCodeClient.Session>,
        limit: Int = MAX_SESSIONS,
        directories: List<String> = emptyList(),
        projectNames: Map<String, String> = emptyMap(),
        projects: List<HomeProjects.Project> = emptyList(),
    ): List<Row> {
        val scope = directories.filter { it.isNotBlank() }
        return sessions.filterNot { isArchived(it) }
            .filter { scope.isEmpty() || containsAny(scope, sessionDirectory(it)) }
            .sortedByDescending { it.updatedAt }
            .take(limit)
            .map { session -> rowOf(session, projectNames, projects) }
    }

    /**
     * One session as a [Row]. The project label and the project's icon both come
     * from the directory: `projectNames` is the catalogue's display names
     * ([projectLabel]) and `projects` the rows the icon is attributed from
     * ([HomeProjects.forSession]) — v1's `Session` has no `projectID` to match
     * on, so the directory is the only attribution this server offers.
     */
    private fun rowOf(
        session: OpenCodeClient.Session,
        projectNames: Map<String, String>,
        projects: List<HomeProjects.Project>,
    ): Row {
        // v54 P1: the row is attributed to the directory it lives in, subpath
        // included ([sessionDirectory]) — the shell registered the subpath the
        // window was bound to, so the raw `location.directory` attributed the
        // row to a project the user never opened.
        val directory = sessionDirectory(session)
        val icon = HomeProjects.forSession(directory, projects)
        return Row(
            id = session.id,
            title = session.title,
            directory = directory,
            project = projectLabel(directory, projectNames),
            updatedAt = session.updatedAt,
            iconColor = icon?.iconColor,
            iconOverride = icon?.iconOverride,
        )
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
     *
     * [projects] is the catalogue the `all`-derived rows take their project icon
     * from ([rowOf]); the [visible] rows already carry it, and an empty list
     * leaves the fresh rows' icon fields null.
     */
    fun searchRows(
        all: List<OpenCodeClient.Session>?,
        visible: List<Row>,
        directories: List<String> = emptyList(),
        projectNames: Map<String, String> = emptyMap(),
        projects: List<HomeProjects.Project> = emptyList(),
    ): List<Row> {
        if (all.isNullOrEmpty()) return visible
        val scope = directories.filter { it.isNotBlank() }
        val scoped = all.filterNot { isArchived(it) }
            .filter { scope.isEmpty() || containsAny(scope, sessionDirectory(it)) }
        // The `all`-derived rows win the `distinctBy` below, so they have to
        // carry the icon themselves — taking it from the matching [visible] row
        // would leave every search hit with a neutral avatar.
        val merged = (scoped.map { rowOf(it, projectNames, projects) } + visible).distinctBy { it.id }
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
     * The session row's project label — upstream `buildHomeSessionRecords`
     * (`home/sessions/records.ts:30` `projectName: displayName(project)`): the
     * owning project's display name, not the bare folder name. [names] is the
     * catalogue's name by [matchKey]; without it (or when the server never set
     * one) the label is the folder name, `null` when the directory is unknown.
     */
    fun projectLabel(directory: String?, names: Map<String, String> = emptyMap()): String? {
        val path = directory?.trim().orEmpty()
        if (path.isEmpty()) return null
        val server = matchKey(path)?.let { names[it] }?.trim().orEmpty()
        if (server.isNotEmpty()) return server
        return projectName(path)
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

    /**
     * Whether the sessions column shows its load-error state instead of the
     * skeleton / list / empty state (emulator-5554 v74: the list sat on the
     * skeleton forever — a failed read left `loading` true with no terminal
     * state and no retry).
     *
     * Real loading always wins: the error is only for a *settled* read that
     * failed while there are no rows to show, so a retry in flight shows the
     * skeleton again instead of flashing the error over it, and a cached list
     * keeps rendering under a failed refresh.
     */
    fun shouldShowLoadError(loading: Boolean, loadFailed: Boolean, rowCount: Int): Boolean =
        !loading && loadFailed && rowCount == 0

    /**
     * Whether the sessions column shows the skeleton instead of the error /
     * list / empty state (emulator-5554 v84: with the backend unreachable the
     * column sat on the skeleton forever — even across a restart — with no
     * error and no retry, while Projects read fine from the local store).
     *
     * The v83 bound did flip `sessionsLoadFailed` on the first load, but the
     * screen OR-ed a pending probe verdict on top of the read's own spinner:
     * with no transport every catalogue read reports `ProbePending` (a probe
     * is in flight, or no verdict ever landed while a server row is
     * configured), so `loading` never settled and [shouldShowLoadError] never
     * fired. A catalogue read that actually failed is the terminal answer —
     * the error/retry state — and a still-churning probe must not drag it
     * back to the skeleton. A retry in flight still shows the skeleton
     * through [localLoading], and a healthy first launch is unchanged (no
     * failure recorded, so a pending probe still holds the skeleton).
     */
    fun isColumnLoading(
        localLoading: Boolean,
        loadFailed: Boolean,
        firstReadOk: Boolean,
        rowCount: Int,
        probePending: Boolean,
    ): Boolean =
        localLoading || (!loadFailed && !firstReadOk && rowCount == 0 && probePending)
}
