package com.xiufeigo.opencodeshell.ui.home

import com.xiufeigo.opencodeshell.OpenCodeClient

/**
 * Pure (JVM-testable) model of Home's Projects column (`research/02` §1.2).
 *
 * ⚠️ **Data-source gap, deliberately not papered over.** Upstream renders the
 * project list from `layout.projects` (`GET /project` + worktree metadata).
 * [OpenCodeClient] has no project endpoint and M3 is not allowed to add one
 * (`/project` is a server-side item on the M4/M5 list), so the column is derived
 * from what the client *does* expose: the distinct `directory` values of the
 * recent sessions. Rows are therefore "directories that have sessions", ordered
 * most-recently-used first, and each row's label is the directory's last path
 * segment ([HomeSessions.projectName]).
 *
 * The same gap is why [RECENTLY_CLOSED] is empty: `home.recentlyClosed` needs
 * the closed-project stack of the project store, and inventing one from
 * sessions would be fabrication. §1.2 renders the section only "(有的话)", so an
 * empty list correctly hides both it and its header.
 */
object HomeProjects {

    data class Project(
        val name: String,
        val path: String,
        /** How many of the listed sessions live in this directory. */
        val sessionCount: Int,
    )

    /** Distinct directories of [sessions], most recently used first. */
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

    /** See the class doc: no client-side source in M3. */
    val RECENTLY_CLOSED: List<Project> = emptyList()
}
