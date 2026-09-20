package com.xiufeigo.opencodeshell.ui.home

import com.xiufeigo.opencodeshell.OpenCodeClient
import java.time.Instant
import java.time.ZoneId

/**
 * Pure (JVM-testable) model of Home's sessions column (`research/02` §1.3):
 * the 64-row cap, the Today / Yesterday / Older grouping, the search filter and
 * the project label. No Compose, no Android — [HomeScreen] only renders it.
 *
 * The i18n literals for the group titles (`home.sessions.group.*`) live in
 * `strings.xml`; this file decides the **bucket**, never the text.
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
     * Newest first, capped at [limit]. `listSessions()` already sorts by
     * `updatedAt` descending, but the sort is repeated here so the view cannot
     * depend on its caller's ordering.
     *
     * @param sessions [ChatViewModel.listSessions] output
     */
    fun rows(sessions: List<OpenCodeClient.Session>, limit: Int = MAX_SESSIONS): List<Row> =
        sessions.sortedByDescending { it.updatedAt }
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
     * `HomeSessionSearch` filter: case-insensitive substring over the title and
     * the project name. Upstream ranks with fuzzysort inside the overlay panel;
     * M3 keeps the same two fields and matches substrings (recorded as a
     * deviation in `research/_M3_home_status.md`).
     */
    fun filter(rows: List<Row>, query: String): List<Row> {
        val needle = query.trim()
        if (needle.isEmpty()) return rows
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
}
