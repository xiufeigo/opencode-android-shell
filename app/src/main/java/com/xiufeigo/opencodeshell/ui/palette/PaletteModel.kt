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
}

fun PaletteHostKind.placeholderResId(): Int =
    when (this) {
        PaletteHostKind.HOME -> R.string.oc_palette_placeholder_home
        PaletteHostKind.SESSION -> R.string.oc_palette_placeholder
    }

/**
 * A host's data assembly. Home omits the whole `file` category; session and
 * new-session add `file.searchFiles` results and the recent (opened) files.
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
}

/** `getDirectory(path)` / `getFilename(path)` for the `file` row (§6.4). */
fun paletteSplitPath(path: String): Pair<String, String> {
    val normalized = path.replace('\\', '/')
    val slash = normalized.lastIndexOf('/')
    return if (slash < 0) "" to normalized else normalized.substring(0, slash + 1) to normalized.substring(slash + 1)
}

/**
 * JVM-testable keyboard contract for the palette's `handleKeyDown` + the global
 * `Ctrl+K / Ctrl+Shift+P` hook (`command.tsx` captures at document level).
 *
 * Modelled as a small enum so the Compose layer only maps `KeyEvent` ⇒ this.
 */
enum class PaletteKey { ARROW_DOWN, ARROW_UP, ENTER, ESCAPE }

/** Global "open the palette" chord: `mod+k` or `mod+shift+p`. */
fun isPaletteChord(ctrlOrMeta: Boolean, keyK: Boolean, keyP: Boolean, shift: Boolean): Boolean = when {
    !ctrlOrMeta -> false
    keyK -> true
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
