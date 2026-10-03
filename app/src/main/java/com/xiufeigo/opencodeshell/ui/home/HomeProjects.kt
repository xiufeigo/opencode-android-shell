package com.xiufeigo.opencodeshell.ui.home

import com.xiufeigo.opencodeshell.OpenCodeClient
import com.xiufeigo.opencodeshell.data.HomeProjectRecords
import com.xiufeigo.opencodeshell.data.ProjectPaths

// Pure (JVM-testable) model of Home's Projects column (research/02 section
// 1.2): the server catalogue merged with session-derived directories
// (HomeProjects.merge), the client-local opened-project rows
// (HomeProjects.openRows), the recently-closed rows (HomeProjects.closedRows),
// the selected-project scope (HomeProjects.directoriesFor), the add-project
// browser's parent walk (HomeProjects.parentOf) and the icon.color to avatar
// colour mapping (HomeProjects.iconColorHex).
//
// Upstream renders the project list from layout.projects (GET /project plus
// worktree metadata). The client-local opened-project store
// (HomeProjectRecords/HomeProjectStore, oc_home_projects) is the source of
// membership and order; GET /project only enriches those rows (vcs,
// icon.color, sandboxes) plus the session-derived supplement (merge), and
// never adds or reorders a row.
object HomeProjects {

    // One project row: the display name, the worktree path, how many listed
    // sessions live in it, and the catalogue metadata used for decoration.
    data class Project(
        val name: String,
        val path: String,
        val sessionCount: Int = 0,
        val vcs: String? = null,
        val iconColor: String? = null,
        val sandboxes: List<String> = emptyList(),
        /**
         * `Project.icon.override` — the custom icon image (`data:` URL), null
         * when the project has none. The avatar draws it **before** the color
         * variant (`ProjectIcon`'s `override ?? url ?? color` chain), so the
         * row's appearance follows the same precedence the editor's preview
         * does. Appended last (with a default) so every existing named or
         * positional construction compiles unchanged.
         */
        val iconOverride: String? = null,
        /**
         * The server `Project.id` this row's metadata came from — null when the
         * row has none (a session-derived directory the catalogue does not
         * list). Not rendered: it is the fact
         * [HomeProjectEdit.localMetaApplies] reads to decide whether the
         * client-local meta (`childStore.projectMeta`) may cover the server's
         * (`runtime.tsx:151`). Appended last, with a default, so every existing
         * named or positional construction compiles unchanged.
         */
        val serverID: String? = null,
    )

    // Distinct directories of sessions, most recently used first. Kept because
    // HomeProjectsTest pins it; the column itself renders openRows, and merge
    // is where session directories supplement the server catalogue.
    fun fromSessions(
        sessions: List<OpenCodeClient.Session>,
        names: Map<String, String> = emptyMap(),
    ): List<Project> {
        val counts = LinkedHashMap<String, Int>()
        sessions.sortedByDescending { it.updatedAt }.forEach { session ->
            val path = session.directory?.trim().orEmpty()
            if (path.isEmpty()) return@forEach
            counts[path] = (counts[path] ?: 0) + 1
        }
        return counts.entries.map { (path, count) ->
            Project(name = displayName(path, names), path = path, sessionCount = count)
        }
    }

    // No client-side source in M3: home.recentlyClosed needs the closed-project
    // stack of the project store, and inventing one from sessions would be
    // fabrication. Kept because HomeProjectsTest pins the empty default.
    val RECENTLY_CLOSED: List<Project> = emptyList()

    // The server catalogue enriched with session counts, plus session-derived
    // rows for directories the catalogue does not list (non-git directories
    // never become server projects but must stay reachable). Server projects
    // come first in server order; appended rows follow most-recently-used
    // first; trailing-separator duplicates collapse onto the server project.
    fun merge(
        projects: List<OpenCodeClient.Project>,
        sessions: List<OpenCodeClient.Session>,
        names: Map<String, String> = emptyMap(),
    ): List<Project> {
        val counts = LinkedHashMap<String, Int>()
        val recency = LinkedHashMap<String, Long>()
        sessions.forEach { session ->
            // v54 P1: a session bound to a subpath counts against the directory
            // it lives in ([HomeSessions.sessionDirectory]), so opening `app/src`
            // shows the sessions it created on its own project row instead of
            // crediting the project root.
            val directory = HomeSessions.sessionDirectory(session)?.trim().orEmpty()
            if (directory.isEmpty()) return@forEach
            val key = HomeSessions.matchKey(directory) ?: return@forEach
            counts[key] = (counts[key] ?: 0) + 1
            recency[key] = maxOf(recency[key] ?: 0L, session.updatedAt)
        }
        val rows = projects.mapNotNull { project ->
            val path = project.worktree.trim()
            if (path.isEmpty()) return@mapNotNull null
            val key = HomeSessions.matchKey(path) ?: return@mapNotNull null
            Project(
                name = displayName(path, names),
                path = path,
                sessionCount = counts[key] ?: 0,
                vcs = project.vcs,
                iconColor = project.iconColor,
                sandboxes = project.sandboxes,
                iconOverride = project.iconOverride,
                // `serverProjectID` maps the server's pseudo project `global`
                // (and a blank id) to null: neither has a `PATCH` route, so
                // neither counts as "the server owns this row's metadata".
                serverID = HomeProjectEdit.serverProjectID(project.id),
            )
        }
        val known = rows.mapNotNull { HomeSessions.matchKey(it.path) }.toSet()
        val extra = counts.keys
            .filterNot { it in known }
            .sortedByDescending { recency[it] ?: 0L }
            .mapNotNull { key ->
                val directory = sessions.firstOrNull {
                    HomeSessions.matchKey(it.directory?.trim().orEmpty()) == key
                }?.directory?.trim().orEmpty()
                if (directory.isEmpty()) return@mapNotNull null
                Project(
                    name = displayName(directory, names),
                    path = directory,
                    sessionCount = counts[key] ?: 0,
                )
            }
        return rows + extra
    }

    // The column's rows: membership and order come from the client-local opened
    // store; the catalogue (known) is a lookup only, matched case/separator
    // insensitively so the catalogue's spelling of a worktree still finds the
    // record the user opened. Unknown worktrees keep their derived name and no
    // tint; the row carries the union of the record's and the catalogue's
    // sandboxes.
    //
    // `closed` is the store's recently-closed stack, used as a **tombstone**:
    // `GET /project` is persistent (it keeps listing every worktree the server
    // ever registered) and a stale record can survive under a second spelling,
    // but a directory the user closed stays closed — nothing here may render it
    // until `HomeProjectRecords.open` takes it out of the stack again. Matching
    // is `ProjectPaths.sameDirectory` (the store's own identity, v36 BUG-2):
    // every spelling that names its volume already shared a `matchKey`
    // (`C:\repo` / `C:/repo` / `/mnt/c/repo`), and the drive-less spelling the
    // add-project picker produced (`/sensorsdata/…` for `C:\sensorsdata\…`) must
    // share one too — otherwise a tombstone written under one spelling leaves
    // the other spelling's row on screen, and one directory can render twice.
    //
    // A record persisted before the write-time dedupe can still hold one
    // directory twice: twins collapse here too (first record wins — the list is
    // most-recent-first — and their sandboxes union), so dirty history renders
    // one row without any manual cleanup.
    //
    // The client-local meta (`HomeProjectRecords.Project`'s `iconColor` /
    // `iconOverride`) is the read side of upstream's `childStore.projectMeta`:
    // it is applied **only** when the row has no server project
    // ([HomeProjectEdit.localMetaApplies] — `runtime.tsx:151`), which is
    // exactly the case the edit dialog saves it in. The display name was
    // already covered — [displayName] prefers the catalogue's and falls back to
    // the record's `name`, the same precedence.
    fun openRows(
        opened: List<HomeProjectRecords.Project>,
        known: List<Project>,
        closed: List<String> = emptyList(),
        names: Map<String, String> = emptyMap(),
    ): List<Project> {
        if (opened.isEmpty()) return emptyList()
        val deduped = ArrayList<HomeProjectRecords.Project>()
        for (record in opened) {
            if (closed.any { ProjectPaths.sameDirectory(it, record.worktree) }) continue
            val index = deduped.indexOfFirst { ProjectPaths.sameDirectory(it.worktree, record.worktree) }
            if (index < 0) {
                deduped.add(record)
            } else {
                deduped[index] = deduped[index].copy(
                    sandboxes = sandboxesOf(deduped[index].sandboxes, record.sandboxes),
                )
            }
        }
        return deduped.map { record ->
            val match = known.firstOrNull { ProjectPaths.sameDirectory(it.path, record.worktree) }
            // `runtime.tsx:151`: `...(!metadata || metadata.id === "global" ?
            // childStore.projectMeta : undefined)` — the client-local meta the
            // editor wrote covers the server's **only** for a worktree with no
            // server project. A registered row stays owned by the catalogue:
            // the save went through `PATCH`, so its local copy can only be
            // stale, and letting it through would shadow the server.
            val local = record.takeIf { HomeProjectEdit.localMetaApplies(match?.serverID) }
            Project(
                name = displayName(record.worktree, names, record.name),
                path = record.worktree,
                sessionCount = match?.sessionCount ?: 0,
                vcs = match?.vcs,
                iconColor = match?.iconColor ?: local?.iconColor,
                sandboxes = sandboxesOf(record.sandboxes, match?.sandboxes.orEmpty()),
                iconOverride = match?.iconOverride ?: local?.iconOverride,
                serverID = match?.serverID,
            )
        }
    }

    /**
     * The registered-project row [directory] belongs to, in **the catalogue's
     * own spelling**: the row [known] holds whose worktree is the same
     * directory ([ProjectPaths.sameDirectory] — the same identity the store
     * dedupes on), else null.
     *
     * 「移动到…」 hands the caller a *spelling* the user picked in the browser
     * (`/mnt/c/repo`), while a registered project is known by the spelling the
     * server catalogued (`C:\repo`, `//host/share/repo`, …). Registering the raw
     * spelling is what lets one directory appear under two rows; adopting
     * [canonicalFor]'s answer first means the entry merges onto the registered
     * row instead of adding a twin ([HomeProjectsList]'s row label then has
     * nothing to disambiguate).
     *
     * Null when the server does not list the directory — the caller keeps the
     * spelling it was handed, which is the only spelling there is.
     */
    fun canonicalFor(directory: String, known: List<Project>): String? {
        val value = directory.trim()
        if (value.isEmpty()) return null
        return known.firstOrNull { ProjectPaths.sameDirectory(it.path, value) }?.path
    }

    /**
     * The path tail a row needs on screen so two rows wearing the same name can
     * be told apart — the shortest trailing run of [path]'s segments that no
     * other **same-named** row shares. Null when the row's name is unique in
     * [projects], i.e. when there is nothing to disambiguate.
     *
     * Two projects can honestly share a basename: the registered
     * `/home/xiufeigo/v36projC` and the repo's own
     * `/mnt/c/sensorsdata/main/program/opencode-android-shell/v36projC` are two
     * directories, two projects, one label (v52 tD). The list has room for the
     * name and nothing else, so the tail rides in the name's own line
     * ([rowLabel]) and the Edit dialog shows the whole path.
     *
     * Segments are compared case-insensitively (a Windows host is) and always
     * keep the name itself, so the answer is never the bare label that is
     * already on screen. A pair that differs only above the volume still gets
     * the full path — two indistinguishable hints help nobody.
     */
    fun pathHint(project: Project, projects: List<Project>): String? {
        val twins = projects.filter { it.name == project.name && it.path != project.path }
        if (twins.isEmpty()) return null
        val segments = pathSegments(project.path)
        val twinSegments = twins.map { pathSegments(it.path) }
        for (take in 2..segments.size) {
            val tail = segments.takeLast(take).joinToString(SEPARATOR)
            val key = tail.lowercase()
            if (twinSegments.none { it.takeLast(take).joinToString(SEPARATOR).lowercase() == key }) {
                return tail
            }
        }
        return project.path
    }

    /**
     * What a project row prints: [project]'s name, plus its [pathHint] as
     * `name · tail` when the column holds another project of the same name.
     * A unique name prints alone — the common case stays byte-identical to
     * before (v52 tD).
     */
    fun rowLabel(project: Project, projects: List<Project>): String {
        val hint = pathHint(project, projects) ?: return project.name
        return "${project.name} · $hint"
    }

    /** The path's segments, ignoring both separator spellings and trailing ones. */
    private fun pathSegments(path: String): List<String> =
        path.trim().split('/', '\\').filter { it.isNotEmpty() }

    private const val SEPARATOR = "/"

    // The recently-closed rows: the worktrees the store moved aside, most
    // recently closed first. Upstream's HomeRecentlyClosedRow renders the
    // outline avatar plus displayName(project) and nothing else, so these
    // carry no vcs/icon enrichment.
    fun closedRows(
        closed: List<String>,
        names: Map<String, String> = emptyMap(),
    ): List<Project> = closed.map { worktree ->
        Project(
            name = displayName(worktree, names),
            path = worktree,
            sessionCount = 0,
        )
    }

    /**
     * Whether the closed stack renders at all: upstream draws it in exactly one
     * place — inside `HomeProjectEmpty` (`home/projects/view.tsx:219,585-589`),
     * which is the fallback when the column has **no open project left**. So
     * 「Close」/ the row's ✕ (`registry.tsx:70-75`: the only removal the server's
     * list+update-only `/api/project` allows, and the only entry upstream's row
     * menu has) takes the row out of the column instead of leaving it beside the
     * live rows; the stack reappears as the way back once the list is empty.
     */
    fun showsClosed(projects: List<Project>, recentlyClosed: List<Project>): Boolean =
        projects.isEmpty() && recentlyClosed.isNotEmpty()

    /**
     * Upstream `displayName(project)` (`shell/layout/helpers.ts:50-51`):
     * `project.name || getFilename(project.worktree) || project.worktree`.
     *
     * [names] is the catalogue's name by [HomeSessions.matchKey] (see
     * [HomeProjectEdit.namesByKey]) — a server-set name wins. [localName] is
     * the client-local record's last-saved name: it covers the offline gap
     * (and the frames before the next catalogue read) after a rename, but
     * never overrides the server. A blank name on either side reads as unset,
     * so a cleared name falls back to the folder name and a root (`/`, `C:\`)
     * with no name still renders its path.
     */
    fun displayName(
        worktree: String,
        names: Map<String, String> = emptyMap(),
        localName: String? = null,
    ): String {
        val server = HomeSessions.matchKey(worktree)?.let { names[it] }?.trim().orEmpty()
        if (server.isNotEmpty()) return server
        val local = localName?.trim().orEmpty()
        if (local.isNotEmpty()) return local
        return HomeSessions.projectName(worktree) ?: worktree
    }

    // The sandbox directories of one project: the union of every source,
    // blanks dropped and de-duplicated on HomeSessions.matchKey, so the same
    // directory spelled C:\repo and c:/repo is one entry.
    fun sandboxesOf(record: List<String>, catalogue: List<String>): List<String> {
        val seen = HashSet<String>()
        return (record + catalogue)
            .map { it.trim() }
            .filter { it.isNotEmpty() && seen.add(HomeSessions.matchKey(it).orEmpty()) }
    }

    // The selected project's directories: [project.worktree, ...sandboxes]
    // (home/projects/controller.tsx:41-43), which is what the session filter is
    // scoped by. Without the record the scope is the worktree alone; null/blank
    // selection is "no scope" (empty list), never "match nothing".
    fun directoriesFor(
        selected: String?,
        opened: List<HomeProjectRecords.Project>,
        known: List<Project> = emptyList(),
    ): List<String> {
        val directory = selected?.trim().orEmpty()
        if (directory.isEmpty()) return emptyList()
        val key = HomeSessions.matchKey(directory)
        val record = opened.firstOrNull { HomeSessions.matchKey(it.worktree) == key }
        val match = known.firstOrNull { HomeSessions.matchKey(it.path) == key }
        if (record == null && match == null) return listOf(directory)
        val sandboxes = sandboxesOf(
            record?.sandboxes.orEmpty(),
            match?.sandboxes.orEmpty(),
        )
        return listOf(record?.worktree ?: match?.path ?: directory) + sandboxes
    }

    /**
     * `homeProjectForSession` (`home/sessions/records.ts:41-53`): the project a
     * session's avatar belongs to — the row whose **worktree** or one of whose
     * **sandboxes** is the session's own directory, matched on
     * [HomeSessions.matchKey] so a catalogue spelling `C:\repo` still finds the
     * record the user opened as `c:/repo`.
     *
     * The comparison is **exact**, not the nested [`HomeSessions.containsDirectory`]
     * the session *scope* uses: upstream matches `pathKey(item.worktree) ===
     * directory`, so a session in `worktree/packages/x` belongs to the project
     * for the scope but renders its own (unknown) avatar. The two rules differ on
     * purpose and both are upstream's.
     *
     * Upstream falls back to `item.id === session.projectID` because a worktree
     * inventory loads on demand; this shell has no such fallback to make — v1's
     * `GET /session` carries no `projectID` at all (it reads `global` on every
     * row, see [HomeSessions.rows]) — so an unattributable session simply has no
     * project and its avatar keeps the neutral surface. `null` for a
     * missing/blank directory: a session the server gave no directory for belongs
     * to no project.
     */
    fun forSession(directory: String?, projects: List<Project>): Project? {
        val key = HomeSessions.matchKey(directory?.trim().orEmpty()) ?: return null
        return projects.firstOrNull { project ->
            HomeSessions.matchKey(project.path) == key ||
                project.sandboxes.any { HomeSessions.matchKey(it) == key }
        }
    }

    // The add-project browser's parent walk: POSIX, Windows and UNC aware
    // (either UNC separator — resolveInput normalises `\` to `/`, so the
    // picker hands over `//host/share`), null at roots (/, drive root, UNC
    // share root) and on blank input.
    fun parentOf(path: String?): String? {
        val value = path?.trim().orEmpty()
        if (value.isEmpty()) return null
        val windows = value.contains('\\') || value.startsWith("//") ||
            (value.length > 1 && value[1] == ':')
        if (!windows) {
            val trimmed = value.trimEnd('/')
            if (trimmed.isEmpty() || !trimmed.contains('/')) return null
            val parent = trimmed.substringBeforeLast('/')
            return if (parent.isEmpty()) "/" else parent
        }
        val trimmed = value.trimEnd('\\', '/')
        if (trimmed.isEmpty()) return null
        // UNC share root (\\host\share or //host/share) has no parent to browse to.
        if (trimmed.startsWith("\\\\") || trimmed.startsWith("//")) {
            val parts = trimmed.substring(2).split('\\', '/').filter { it.isNotEmpty() }
            if (parts.size <= 2) return null
            val separator = if (trimmed.startsWith("\\")) "\\" else "/"
            return trimmed.substring(0, 2) + parts.dropLast(1).joinToString(separator)
        }
        val separator = trimmed.lastIndexOfAny(charArrayOf('\\', '/'))
        if (separator < 0) return null
        val parent = trimmed.substring(0, separator)
        if (parent.isEmpty()) return null
        // A bare drive (C:) keeps its browsable form (C:\).
        if (parent.length == 2 && parent[1] == ':') return "$parent\\"
        return parent
    }

    // icon.color to avatar colour mapping (getProjectAvatarVariant):
    // mint maps to cyan, lime to green; unknown names read as no tint.
    fun iconColorHex(name: String?): Long? = when (name?.trim()?.lowercase()) {
        "orange" -> 0xFFEE7330
        "pink" -> 0xFFE4429E
        "mint", "cyan" -> 0xFF0096B8
        "lime", "green" -> 0xFF2EAF5A
        "gray", "grey" -> 0xFF5C5C5C
        else -> null
    }

    // HomeProjects.keyOf: the comparison key used by callers that only need
    // the path normalisation (NewSessionScreen's picked/extra check).
    fun keyOf(path: String): String = path.trim().trimEnd('/', '\\')
}
