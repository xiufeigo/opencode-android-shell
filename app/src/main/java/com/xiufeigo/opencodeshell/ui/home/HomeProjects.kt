package com.xiufeigo.opencodeshell.ui.home

import com.xiufeigo.opencodeshell.OpenCodeClient
import com.xiufeigo.opencodeshell.data.HomeProjectRecords

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
    )

    // Distinct directories of sessions, most recently used first. Kept because
    // HomeProjectsTest pins it; the column itself renders openRows, and merge
    // is where session directories supplement the server catalogue.
    fun fromSessions(sessions: List<OpenCodeClient.Session>): List<Project> {
        val counts = LinkedHashMap<String, Int>()
        sessions.sortedByDescending { it.updatedAt }.forEach { session ->
            val path = session.directory?.trim().orEmpty()
            if (path.isEmpty()) return@forEach
            counts[path] = (counts[path] ?: 0) + 1
        }
        return counts.entries.map { (path, count) ->
            Project(name = HomeSessions.projectName(path) ?: path, path = path, sessionCount = count)
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
    ): List<Project> {
        val counts = LinkedHashMap<String, Int>()
        val recency = LinkedHashMap<String, Long>()
        sessions.forEach { session ->
            val directory = session.directory?.trim().orEmpty()
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
                name = HomeSessions.projectName(path) ?: path,
                path = path,
                sessionCount = counts[key] ?: 0,
                vcs = project.vcs,
                iconColor = project.iconColor,
                sandboxes = project.sandboxes,
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
                    name = HomeSessions.projectName(directory) ?: directory,
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
    fun openRows(
        opened: List<HomeProjectRecords.Project>,
        known: List<Project>,
    ): List<Project> {
        if (opened.isEmpty()) return emptyList()
        val byKey = known.associateBy { HomeSessions.matchKey(it.path) }
        return opened.map { record ->
            val match = byKey[HomeSessions.matchKey(record.worktree)]
            Project(
                name = HomeSessions.projectName(record.worktree) ?: record.worktree,
                path = record.worktree,
                sessionCount = match?.sessionCount ?: 0,
                vcs = match?.vcs,
                iconColor = match?.iconColor,
                sandboxes = sandboxesOf(record.sandboxes, match?.sandboxes.orEmpty()),
            )
        }
    }

    // The recently-closed rows: the worktrees the store moved aside, most
    // recently closed first. Upstream's HomeRecentlyClosedRow renders the
    // outline avatar plus displayName(project) and nothing else, so these
    // carry no vcs/icon enrichment.
    fun closedRows(closed: List<String>): List<Project> = closed.map { worktree ->
        Project(
            name = HomeSessions.projectName(worktree) ?: worktree,
            path = worktree,
            sessionCount = 0,
        )
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

    // The add-project browser's parent walk: POSIX, Windows and UNC aware,
    // null at roots (/, drive root, UNC share root) and on blank input.
    fun parentOf(path: String?): String? {
        val value = path?.trim().orEmpty()
        if (value.isEmpty()) return null
        val windows = value.contains('\\') || (value.length > 1 && value[1] == ':')
        if (!windows) {
            val trimmed = value.trimEnd('/')
            if (trimmed.isEmpty() || !trimmed.contains('/')) return null
            val parent = trimmed.substringBeforeLast('/')
            return if (parent.isEmpty()) "/" else parent
        }
        val trimmed = value.trimEnd('\\', '/')
        if (trimmed.isEmpty()) return null
        // UNC share root (\\host\share) has no parent to browse to.
        if (trimmed.startsWith("\\\\")) {
            val parts = trimmed.substring(2).split('\\', '/').filter { it.isNotEmpty() }
            if (parts.size <= 2) return null
            return "\\\\" + parts.dropLast(1).joinToString("\\")
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
