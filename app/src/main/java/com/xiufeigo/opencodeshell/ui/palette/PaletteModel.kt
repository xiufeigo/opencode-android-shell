package com.xiufeigo.opencodeshell.ui.palette

import com.xiufeigo.opencodeshell.R

/**
 * Pure (JVM-testable) model of the opencode v2 command palette.
 *
 * Source of truth: `research/02_ui_screens.md` §6 (data union, grouping,
 * empty-query contents, row CSS) and `research/05_palette_host_session.md` §1/§2
 * (the palette is pushed onto a GLOBAL dialog stack; Home and session/new-session
 * share one view and differ only in data assembly + placeholder).
 */

/** `CommandPaletteEntry.type` — exactly three kinds, no `model` kind. */
enum class PaletteEntryType { COMMAND, FILE, SESSION }

/** `CommandPaletteEntry` (the fields M4 renders). */
data class PaletteEntry(
    val id: String,
    val type: PaletteEntryType,
    val title: String,
    val description: String? = null,
    val keybind: String? = null,
    val category: String,
    /** `file` only. */
    val path: String? = null,
    /** `session` only. */
    val sessionID: String? = null,
    /** `session` only — relative-time bucket, pre-rendered. */
    val meta: String? = null,
    /** `session` only — the row shows the "already open" bar. */
    val open: Boolean = false,
    val archived: Boolean = false,
) {
    companion object {
        /** `"command:" + option.id`. */
        fun command(
            id: String,
            title: String,
            description: String? = null,
            keybind: String? = null,
            category: String = CATEGORY_COMMANDS,
        ) = PaletteEntry("command:$id", PaletteEntryType.COMMAND, title, description, keybind, category)

        /** `"file:" + path`. */
        fun file(path: String, category: String = CATEGORY_FILES) =
            PaletteEntry(
                id = "file:$path",
                type = PaletteEntryType.FILE,
                title = path,
                category = category,
                path = path,
            )

        /** `` `session:${server}:${sessionID}` ``. */
        fun session(
            server: String,
            sessionID: String,
            title: String,
            description: String? = null,
            meta: String? = null,
            open: Boolean = false,
            archived: Boolean = false,
        ) = PaletteEntry(
            id = "session:$server:$sessionID",
            type = PaletteEntryType.SESSION,
            title = title,
            description = description,
            category = CATEGORY_SESSION,
            sessionID = sessionID,
            meta = meta,
            open = open,
            archived = archived,
        )
    }
}

/** `palette.group.commands` = "Commands". */
const val CATEGORY_COMMANDS: String = "Commands"

/** `palette.group.files` = "Files". */
const val CATEGORY_FILES: String = "Files"

/** `command.category.session` = "Session" — session rows hang under THIS group. */
const val CATEGORY_SESSION: String = "Session"

/** `ENTRY_LIMIT = 5`. */
const val ENTRY_LIMIT: Int = 5

/** `COMMON_COMMAND_IDS` (first five are used for the empty query). */
val COMMON_COMMAND_IDS: List<String> = listOf(
    "session.new",
    "workspace.new",
    "session.previous",
    "session.next",
    "terminal.toggle",
    "review.toggle",
)

/** `DEFAULT_PALETTE_KEYBIND = "mod+k,mod+shift+p"`. */
const val DEFAULT_PALETTE_KEYBIND: String = "mod+k,mod+shift+p"

/** `PALETTE_ID = "command.palette"`. */
const val PALETTE_ID: String = "command.palette"

/**
 * `uniqueCommandPaletteEntries` — dedupe by id, keeping the FIRST occurrence.
 */
fun uniqueEntries(entries: List<PaletteEntry>): List<PaletteEntry> {
    val seen = HashSet<String>(entries.size)
    return entries.filter { seen.add(it.id) }
}

/**
 * `groups(entries)` — group by `category`, preserving first-seen order; a group
 * with no entries is never rendered (`Show(category)`).
 */
fun groupEntries(entries: List<PaletteEntry>): List<Pair<String, List<PaletteEntry>>> {
    val order = LinkedHashMap<String, MutableList<PaletteEntry>>()
    for (entry in entries) order.getOrPut(entry.category) { mutableListOf() }.add(entry)
    return order.entries.filter { it.value.isNotEmpty() }.map { it.key to it.value.toList() }
}

/**
 * `matchesEntry` — case-insensitive `contains` over `[title, description, category]`.
 */
fun matchesEntry(entry: PaletteEntry, query: String): Boolean {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return true
    return entry.title.lowercase().contains(q) ||
        entry.description?.lowercase()?.contains(q) == true ||
        entry.category.lowercase().contains(q)
}

/** Which catalog data a palette host assembles (Home vs session/new-session). */
data class PaletteSources(
    /** All registered commands. */
    val commands: List<PaletteEntry>,
    /** Session entries (already built from `session.list`). */
    val sessions: List<PaletteEntry> = emptyList(),
    /** File entries — only the session/new-session hosts pass these. */
    val files: List<PaletteEntry> = emptyList(),
    /** `recentFileEntries()` = opened tabs, active file first, deduped, 5 max. */
    val recentFiles: List<PaletteEntry> = emptyList(),
    /** Home has no file category at all (§6.2). */
    val includeFiles: Boolean = true,
)

/**
 * `preferredCommandEntries` — the `COMMON_COMMAND_IDS` in their declared order
 * when present, otherwise the first [ENTRY_LIMIT] commands from the registry.
 */
fun preferredCommandEntries(commands: List<PaletteEntry>, limit: Int = ENTRY_LIMIT): List<PaletteEntry> {
    val byId = commands.associateBy { it.id }
    val preferred = COMMON_COMMAND_IDS.mapNotNull { byId["command:$it"] }
    return (if (preferred.isNotEmpty()) preferred else commands).take(limit)
}

/**
 * Empty-query contents.
 *
 * - `DialogCommandPaletteV2` (session/new-session): common commands + recent files.
 * - `DialogHomeCommandPaletteV2` (Home): `commandEntries().slice(0, 5)`, no files.
 */
fun emptyQueryEntries(sources: PaletteSources): List<PaletteEntry> =
    if (sources.includeFiles) {
        uniqueEntries(preferredCommandEntries(sources.commands) + sources.recentFiles.take(ENTRY_LIMIT))
    } else {
        uniqueEntries(sources.commands.take(ENTRY_LIMIT))
    }

/**
 * Non-empty query: matching commands + matching sessions (+ files for the
 * session/new-session hosts).
 */
fun searchEntries(sources: PaletteSources, query: String): List<PaletteEntry> {
    if (query.isBlank()) return emptyQueryEntries(sources)
    val matches = sources.commands.filter { matchesEntry(it, query) } +
        sources.sessions.filter { matchesEntry(it, query) }
    val files = if (sources.includeFiles) sources.files.filter { matchesEntry(it, query) } else emptyList()
    return uniqueEntries(matches + files)
}

/** The one entry-point used by the Compose view. */
fun visibleEntries(sources: PaletteSources, query: String): List<PaletteEntry> =
    uniqueEntries(if (query.isBlank()) emptyQueryEntries(sources) else searchEntries(sources, query))

/**
 * `move(delta)` over the flattened row order: wraps around and returns 0 for an
 * empty list (§6.6 — "首尾循环").
 */
fun moveIndex(current: Int, delta: Int, count: Int): Int {
    if (count == 0) return 0
    return ((current + delta) % count + count) % count
}

/** Flattened render order used for keyboard navigation and `data-active`. */
fun flattenRows(grouped: List<Pair<String, List<PaletteEntry>>>): List<PaletteEntry> =
    grouped.flatMap { it.second }

/** `palette.search.placeholder` / `palette.search.placeholder.home` (§6.2). */
enum class PaletteHostKind {
    /** `DialogHomeCommandPaletteV2`. */
    HOME,

    /** `DialogCommandPaletteV2` (session + new-session, same component). */
    SESSION,

    /**
     * `file.open` (`use-session-commands.tsx:163-168` + `palette.ts:96-99`):
     * the same session palette with `filesOnly()` — `allowedCommands` is empty,
     * so only the Files group survives.
     */
    FILES_ONLY,
}

fun PaletteHostKind.placeholderResId(): Int =
    when (this) {
        PaletteHostKind.HOME -> R.string.oc_palette_placeholder_home
        PaletteHostKind.FILES_ONLY -> R.string.oc_palette_placeholder_files
        PaletteHostKind.SESSION -> R.string.oc_palette_placeholder
    }

/**
 * A host's data assembly. Home omits the whole `file` category; session and
 * new-session add `file.searchFiles` results and the recent (opened) files.
 * [FILES_ONLY] drops every command and session row (`filesOnly()`).
 */
fun PaletteHostKind.sources(
    commands: List<PaletteEntry>,
    sessions: List<PaletteEntry> = emptyList(),
    files: List<PaletteEntry> = emptyList(),
    recentFiles: List<PaletteEntry> = emptyList(),
): PaletteSources = when (this) {
    PaletteHostKind.HOME -> PaletteSources(commands = commands, sessions = sessions, includeFiles = false)
    PaletteHostKind.SESSION -> PaletteSources(
        commands = commands,
        sessions = sessions,
        files = files,
        recentFiles = recentFiles,
        includeFiles = true,
    )

    PaletteHostKind.FILES_ONLY -> PaletteSources(
        commands = emptyList(),
        sessions = emptyList(),
        files = files,
        recentFiles = recentFiles,
        includeFiles = true,
    )
}

/** `getDirectory(path)` / `getFilename(path)` for the `file` row (§6.4). */
fun paletteSplitPath(path: String): Pair<String, String> {
    val normalized = path.replace('\\', '/')
    val slash = normalized.lastIndexOf('/')
    return if (slash < 0) "" to normalized else normalized.substring(0, slash + 1) to normalized.substring(slash + 1)
}

/**
 * `GET /session` rows for the session host (W2-C). The input order is
 * preserved — [com.xiufeigo.opencodeshell.ChatViewModel.listSessions] already
 * sorts `updatedAt` descending, which is the palette's session order. [server]
 * is the `NavRestore` server key baked into the entry id
 * (`` `session:${server}:${sessionID}` ``); [openSessionIds] marks rows with
 * the "already open" bar.
 *
 * WAVE 2.3a (A4 #4): archived rows are dropped (`palette.ts:272`
 * `!session.time.archived` — the native predicate is `archivedAt > 0`, see
 * `HomeSessions.isArchived`) and the list is capped at [SESSION_SEARCH_LIMIT]
 * (v2 `limit: 50`, `palette.ts:152`).
 */
const val SESSION_SEARCH_LIMIT: Int = 50

fun sessionPaletteEntries(
    sessions: List<com.xiufeigo.opencodeshell.OpenCodeClient.Session>,
    server: String,
    openSessionIds: Set<String> = emptySet(),
    /**
     * Row meta pinned to the right edge — upstream `command-palette-meta`
     * (`shell/commands/dialog.tsx:267-271` renders `getRelativeTime(item.updated)`).
     * A lambda keeps the localized bucket out of this Compose-free file; `null`
     * omits the meta cell.
     */
    meta: ((com.xiufeigo.opencodeshell.OpenCodeClient.Session) -> String?)? = null,
): List<PaletteEntry> = sessions
    .filter { (it.archivedAt ?: 0L) <= 0L }
    .take(SESSION_SEARCH_LIMIT)
    .map { session ->
        PaletteEntry.session(
            server = server,
            sessionID = session.id,
            title = session.title,
            description = session.directory,
            meta = meta?.invoke(session),
            open = session.id in openSessionIds,
            archived = (session.archivedAt ?: 0L) > 0L,
        )
    }

/**
 * `session.previous` / `session.next` (`desktop-menu.ts:245-266`): the session
 * [delta] rows away from [currentId] in the palette's own `updatedAt`-descending
 * order.
 *
 * Wraps around at both ends (the phone has no menu bar to grey the entry out at
 * the end of the list) and returns `null` when there is nothing to move to —
 * either an empty list or a single session. A [currentId] that is not in the
 * list (Home, or a session outside the loaded page) starts from the top.
 */
fun steppedSessionId(
    sessions: List<com.xiufeigo.opencodeshell.OpenCodeClient.Session>,
    currentId: String?,
    delta: Int,
): String? {
    if (sessions.isEmpty() || delta == 0) return null
    val current = sessions.indexOfFirst { it.id == currentId }
    if (current < 0) return if (delta > 0) sessions.first().id else sessions.last().id
    val target = moveIndex(current, delta, sessions.size)
    return sessions[target].id.takeIf { it != currentId }
}

/**
 * `/find/file` paths for the session host (W2-C): the server's relevance
 * order is preserved and blank entries are dropped (they would render an
 * empty row).
 *
 * WAVE 2.3a (A4 #6): capped at [ENTRY_LIMIT] — upstream slices the root-file
 * list to 5 (`palette.ts:119-144`) and the async search is bounded by the
 * same budget on the client.
 */
fun filePaletteEntries(paths: List<String>): List<PaletteEntry> =
    paths.filter { it.isNotBlank() }.take(ENTRY_LIMIT).map { PaletteEntry.file(it) }

/**
 * JVM-testable keyboard contract for the palette's `handleKeyDown` + the global
 * `Ctrl+K / Ctrl+Shift+P` hook (`command.tsx` captures at document level).
 *
 * Modelled as a small enum so the Compose layer only maps `KeyEvent` ⇒ this.
 */
enum class PaletteKey { ARROW_DOWN, ARROW_UP, ENTER, ESCAPE }

/**
 * Global "open the palette" chord: `mod+k` or `mod+shift+p`.
 *
 * Upstream `matchKeybind` (`shell/commands/command.tsx:188-204`) requires every
 * modifier to match *exactly*, so `mod+shift+k` does **not** open the palette and
 * `mod+p` (without Shift) is `file.open` instead.
 */
fun isPaletteChord(ctrlOrMeta: Boolean, keyK: Boolean, keyP: Boolean, shift: Boolean): Boolean = when {
    !ctrlOrMeta -> false
    keyK -> !shift
    keyP -> shift
    else -> false
}

/** Settings opens with `mod+comma` (§5.0). */
fun isSettingsChord(ctrlOrMeta: Boolean, keyComma: Boolean): Boolean = ctrlOrMeta && keyComma

/**
 * A registered command. The web registry is a list of
 * `{ id, title, description?, keybind?, category, onSelect }` records pushed by
 * whichever route is mounted (`research/05` §1.4); [id] is the stable identity
 * used by [COMMON_COMMAND_IDS] and by the banner-row lookup.
 */
class PaletteCommand(
    val id: String,
    val titleResId: Int,
    val category: String = CATEGORY_COMMANDS,
    val descriptionResId: Int? = null,
    val keybind: String? = null,
    /** `hidden: true` commands are registered but never listed. */
    val hidden: Boolean = false,
    val onSelect: () -> Unit,
) {
    /** `"command:" + id`. */
    val entryId: String get() = "command:$id"
}

/**
 * Projects a registered command into a list row. The title stays unresolved
 * (`""`) because mapping [PaletteCommand.titleResId] needs a resource table: the
 * Compose layer fills it through [commandEntries].
 */
fun PaletteCommand.toEntry() = PaletteEntry(
    id = entryId,
    type = PaletteEntryType.COMMAND,
    title = "",
    description = null,
    keybind = keybind,
    category = category,
)

/**
 * Registered commands reduced to rows. [resolveTitle] lets the Compose layer
 * pass `stringResource` output while this stays JVM-testable.
 */
fun commandEntries(commands: List<PaletteCommand>, resolveTitle: (Int) -> String): List<PaletteEntry> =
    commands.filterNot { it.hidden }
        .map { command ->
            command.toEntry().copy(
                title = resolveTitle(command.titleResId),
                description = command.descriptionResId?.let(resolveTitle),
            )
        }

/** Looks up the command a row belongs to. */
fun commandFor(commands: List<PaletteCommand>, entry: PaletteEntry): PaletteCommand? =
    commands.firstOrNull { it.entryId == entry.id }
