package com.xiufeigo.opencodeshell.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * The path algebra two upstream modules own between them:
 * `workspaces/path-key.ts` (`pathKey`) and `workspaces/paths.ts` (`containsDirectory`,
 * whose local `normalize` is the case-folding [matchKey] adds).
 *
 * It lives behind this one object so every caller compares directories the same
 * way — the client-local project store ([HomeProjectRecords]) for "is this the
 * same directory" and the Home session filter
 * ([com.xiufeigo.opencodeshell.ui.home.HomeSessions]) for "does this project
 * contain this session". [com.xiufeigo.opencodeshell.ui.home.HomeSessions] keeps
 * its historical `pathKey` name as a delegate so its published behaviour (and
 * the tests pinning it) is unchanged.
 */
object ProjectPaths {

    /**
     * `pathKey` (`_upstream/packages/app/src/workspaces/path-key.ts:18-24`) — the
     * normalisation that decides whether two *spellings* are the same directory.
     *
     * A **Windows** value (drive letter in position 2, or a UNC `\\host`) folds
     * `\` to `/` first; only `/` is a trailing trim, exactly like upstream, and
     * the two roots survive: an all-separator POSIX path stays `/` and a bare
     * drive becomes `C:/`.
     *
     * Android addition (upstream does not do this, and the server is free to
     * hand back the verbatim Win32 form): a leading extended-length prefix
     * (`\\?\C:\src`, `//?/C:/src`, and the UNC variant `\\?\UNC\host\share`) is
     * stripped before the fold.
     *
     * `null` for a missing/blank directory: a session the server gave no
     * directory for is not attributable to any project, so it never matches a
     * selection (and `/` never collapses into it).
     */
    fun pathKey(path: String?): String? {
        val raw = path?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val value = stripExtendedPrefix(raw)
        val normalized = if (isWindowsPath(value)) value.replace('\\', '/') else value
        val trimmed = normalized.trimEnd('/')
        // Only `/`, `//`, … reach this: the POSIX root stays one separator
        // (upstream `path-key.ts:21`) so it cannot read as "no directory".
        if (trimmed.isEmpty()) return "/"
        return if (isDrive(trimmed)) "$trimmed/" else trimmed
    }

    /**
     * [pathKey] with the case-fold of upstream's `containsDirectory` `normalize`
     * (`workspaces/paths.ts:89-92`): a **Windows** key is lowercased because a
     * Windows filesystem is case-insensitive, while a POSIX key keeps its case
     * (two genuinely different directories must not merge).
     *
     * This is the key the project→session filter compares, and the one the
     * project column uses to find what the server catalog knows about a
     * worktree.
     */
    fun matchKey(path: String?): String? {
        val key = pathKey(path) ?: return null
        // A drive root (`C:/`) or a UNC share (`//host/share`) is Windows; so is
        // every descendant of one, because the drive/UNC head survives pathKey.
        return if (isWindowsKey(key)) key.lowercase() else key
    }

    /**
     * `containsDirectory` (`_upstream/packages/app/src/workspaces/paths.ts:88-96`):
     * `child` is `parent` **or below it**. `isProjectDirectory` (`:83-86`) is the
     * caller that matters — a session whose directory is nested inside a project
     * worktree (a worktree session, a package subdirectory) belongs to that
     * project, while a same-prefix sibling (`/repo/a-old` vs `/repo/a`) does not.
     *
     * A blank/absent side never matches, so an unknown session directory belongs
     * to no project.
     */
    fun containsDirectory(parent: String?, child: String?): Boolean {
        val root = matchKey(parent) ?: return false
        val target = matchKey(child) ?: return false
        return target == root || target.startsWith(if (root.endsWith("/")) root else "$root/")
    }

    /** `directories.some { containsDirectory(it, child) }` — `false` for an empty set. */
    fun containsAny(directories: Collection<String>, child: String?): Boolean =
        directories.any { containsDirectory(it, child) }

    private fun isWindowsPath(value: String): Boolean =
        (value.length > 1 && value[1] == ':') || value.startsWith("\\\\")

    private fun isWindowsKey(value: String): Boolean =
        (value.length > 2 && value[1] == ':') || value.startsWith("//")

    private fun isDrive(value: String): Boolean =
        value.length == 2 && value[1] == ':' && (value[0] in 'A'..'Z' || value[0] in 'a'..'z')

    /** `\\?\` / `//?/` — the Win32 extended-length prefix, plus its `UNC\` form. */
    private val EXTENDED_PREFIXES = listOf("\\\\?\\" to "\\\\", "//?/" to "//")

    private fun stripExtendedPrefix(value: String): String {
        for ((prefix, uncRoot) in EXTENDED_PREFIXES) {
            if (!value.startsWith(prefix)) continue
            val rest = value.substring(prefix.length)
            if (rest.length > 4 && (rest.startsWith("UNC\\", true) || rest.startsWith("UNC/", true))) {
                return uncRoot + rest.substring(4)
            }
            return rest
        }
        return value
    }
}

/**
 * Pure half of the client-local opened-project store — the Android counterpart
 * of `createServerProjects` (`_upstream/packages/app/src/runtime/server/registry.tsx:34-99`).
 *
 * Upstream's 项目 list is **client-local persisted state**, not the server's
 * `GET /project`: `home/model.ts:20-28` reads
 * `focusedServerCtx().projects.list()` / `.recentlyClosed()`, and that store is
 * the per-server record `{ list, hidden, projects, lastProject, recentlyClosed }`
 * of `registry.tsx:41-74`. `GET /project` is only ever *enriched* with what the
 * server knows; it must not add rows. (Verified live: the server's list is every
 * worktree it has ever registered — `workspace` six times, `/`,
 * `C:\Windows\System32` — which is exactly the bug this replaces.)
 *
 * The five operations mirror `createServerProjects` one for one:
 *
 * | upstream | here |
 * | --- | --- |
 * | `open(directory)` | [open] — drops a matching `recentlyClosed` entry, then prepends; a project already open keeps its position |
 * | `close(directory)` | [close] — removes it and pushes it onto `recentlyClosed` (most recent first) |
 * | `touch(directory)` | [touch] — records the MRU marker (`lastProject`) |
 * | `move(worktree, index)` | [move] |
 * | `list()` / `last()` / `recentlyClosed()` | [State.list] / [State.lastProject] / [State.recentlyClosed] |
 *
 * Upstream's `expanded` flag and its worktree tree have no counterpart in this
 * shell (no project tree UI), so the record carries the worktree and the
 * sandbox directories only. `hidden` (hidden servers) and `list` (connections)
 * of the same persisted record are server-registry state, not project state.
 *
 * Everything here is `SharedPreferences`-free so it stays JVM-testable;
 * [HomeProjectStore] is the only part that touches Android.
 */
object HomeProjectRecords {

    /**
     * `LocalProject` (`shell/state/layout`): one opened project. [sandboxes] is
     * `project.sandboxes` — the extra directories that belong to the project, so
     * `directories(project) = [worktree, ...sandboxes]`
     * (`home/projects/controller.tsx:41-43`) is what the session filter is scoped
     * by. `OpenCodeClient.Project` does not parse the server's `sandboxes` field,
     * so today they are recorded locally when a project is opened.
     */
    data class Project(val worktree: String, val sandboxes: List<String> = emptyList())

    /**
     * One server's slice of upstream's `ServerState` — the same field names
     * (`projects` / `lastProject` / `recentlyClosed`) so the persisted record
     * reads like the upstream one. [recentlyClosed] holds worktree strings, not
     * project records, exactly like upstream's `[directory, ...closed]`.
     */
    data class State(
        val list: List<Project> = emptyList(),
        val lastProject: String? = null,
        val recentlyClosed: List<String> = emptyList(),
    )

    // ------------------------------------------- the five store operations

    /**
     * `open(directory)` (`registry.tsx:54-67`): if the directory is in
     * [State.recentlyClosed] it is taken out of it (re-opening a closed project
     * must not leave it in both sections), and unless it is already open it
     * becomes the **first** row. Identity is the exact worktree string, like
     * upstream — a re-open of an already listed project is a no-op that keeps its
     * position (only a user `move` reorders).
     */
    fun open(state: State, directory: String): State {
        val worktree = directory.trim()
        if (worktree.isEmpty()) return state
        val key = ProjectPaths.pathKey(worktree)
        val closed = state.recentlyClosed.filterNot { ProjectPaths.pathKey(it) == key }
        if (state.list.any { it.worktree == worktree }) {
            return if (closed == state.recentlyClosed) state else state.copy(recentlyClosed = closed)
        }
        return state.copy(list = listOf(Project(worktree)) + state.list, recentlyClosed = closed)
    }

    /**
     * `close(directory)` (`registry.tsx:70-75`): a **user-initiated** close —
     * the project leaves the list and lands at the head of [State.recentlyClosed],
     * de-duplicated by [ProjectPaths.pathKey] so closing the same directory twice
     * moves one entry instead of stacking two.
     */
    fun close(state: State, directory: String): State {
        val worktree = directory.trim()
        if (worktree.isEmpty()) return state
        val key = ProjectPaths.pathKey(worktree)
        val list = state.list.filterNot { it.worktree == worktree }
        val closed = listOf(worktree) + state.recentlyClosed.filterNot { ProjectPaths.pathKey(it) == key }
        if (list == state.list && closed == state.recentlyClosed) return state
        return state.copy(list = list, recentlyClosed = closed)
    }

    /**
     * `touch(directory)` (`registry.tsx:95-97`): the MRU marker behind
     * `newSessionProject() = selectedProject() ?? projects.last()` (`home/model.ts:24-29`).
     * Upstream records it even when the directory is not open, and so does this.
     */
    fun touch(state: State, directory: String): State {
        val worktree = directory.trim()
        if (worktree.isEmpty() || state.lastProject == worktree) return state
        return state.copy(lastProject = worktree)
    }

    /**
     * `move(directory, toIndex)` (`registry.tsx:84-91`): moves the project at
     * `worktree` to `index`, shifting the rest. An unknown worktree, a no-op move
     * and an out-of-range index (clamped, like `Array.splice`) all leave the
     * state alone. The desktop app drives this from drag-and-drop; the native
     * column has no pointer drag yet, so the operation is exposed for the store
     * contract (and its tests) rather than for a live gesture.
     */
    fun move(state: State, worktree: String, index: Int): State {
        val from = state.list.indexOfFirst { it.worktree == worktree }
        if (from < 0) return state
        val to = index.coerceIn(0, state.list.size - 1)
        if (from == to) return state
        val next = ArrayList(state.list)
        next.add(to, next.removeAt(from))
        return state.copy(list = next)
    }

    /** `directories(project) = [project.worktree, ...(project.sandboxes ?? [])]`. */
    fun directories(project: Project): List<String> =
        listOf(project.worktree) + project.sandboxes.filter { it.isNotBlank() }

    // ---------------------------------------------------- persistence (JSON)

    /**
     * The persisted form: `{"projects":[{worktree,sandboxes}],"recentlyClosed":[…],"lastProject":"…"}`.
     * `org.json` is on the unit-test classpath (`app/build.gradle.kts:108`), so
     * this pair is covered by `HomeProjectStoreTest`.
     */
    fun encode(state: State): String {
        val root = JSONObject()
        root.put(
            "projects",
            JSONArray().apply { state.list.forEach { put(projectJson(it)) } },
        )
        root.put("recentlyClosed", JSONArray().apply { state.recentlyClosed.forEach { put(it) } })
        state.lastProject?.let { root.put("lastProject", it) }
        return root.toString()
    }

    /**
     * Reads a record back. Lenient on purpose: the value is a preference on a
     * user's device, so a truncated/garbage blob (or a record a *future* version
     * wrote with fields this one does not know) degrades to "no opened projects"
     * instead of crashing Home, blank worktrees are dropped and duplicates
     * collapse on [ProjectPaths.pathKey].
     */
    fun decode(raw: String?): State {
        if (raw.isNullOrBlank()) return State()
        return runCatching { parse(JSONObject(raw)) }.getOrNull() ?: State()
    }

    private fun parse(obj: JSONObject): State = State(
        list = obj.optJSONArray("projects").projects().dedupe(),
        lastProject = obj.optString("lastProject", "").trim().takeIf { it.isNotEmpty() },
        recentlyClosed = obj.optJSONArray("recentlyClosed").strings().dedupeKeys(),
    )

    private fun projectJson(project: Project): JSONObject = JSONObject().apply {
        put("worktree", project.worktree)
        if (project.sandboxes.isNotEmpty()) {
            put("sandboxes", JSONArray().apply { project.sandboxes.forEach { put(it) } })
        }
    }

    private fun JSONArray?.projects(): List<Project> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { index ->
            // A bare string is tolerated: the worktree alone is a valid record.
            when (val entry = opt(index)) {
                is String -> entry.trim().takeIf { it.isNotEmpty() }?.let { Project(it) }
                is JSONObject -> entry.optString("worktree", "").trim().takeIf { it.isNotEmpty() }?.let { worktree ->
                    Project(worktree, entry.optJSONArray("sandboxes").strings())
                }

                else -> null
            }
        }
    }

    private fun JSONArray?.strings(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { index ->
            (opt(index) as? String)?.trim()?.takeIf { it.isNotEmpty() }
        }
    }

    private fun List<Project>.dedupe(): List<Project> {
        val seen = HashSet<String>()
        return filter { seen.add(ProjectPaths.pathKey(it.worktree).orEmpty()) }
    }

    private fun List<String>.dedupeKeys(): List<String> {
        val seen = HashSet<String>()
        return filter { seen.add(ProjectPaths.pathKey(it).orEmpty()) }
    }
}

/**
 * The client-local opened-project store, persisted per server.
 *
 * Upstream keys the same record by `ServerConnection.key(conn)` (`registry.tsx:34-41`);
 * the single-server native shell passes the same stable key `OcApp` already uses
 * for its session routes ([com.xiufeigo.opencodeshell.ui.nav.NavRestore.serverKeyOf]),
 * so switching servers switches records and nothing leaks across them.
 *
 * Persistence is plain `SharedPreferences` + a JSON blob, following
 * [com.xiufeigo.opencodeshell.ui.nav.LastRouteStore] (and `AppearancePrefs`):
 * a list of directory paths is not a secret, so the encrypted store's key
 * derivation would only add cold-start latency. The exposed surface is exactly
 * what a `DataStore` re-implementation would offer ([state] + the five
 * mutations), so swapping the backend stays a one-file change.
 */
class HomeProjectStore(
    context: Context,
    serverScope: String = DEFAULT_SCOPE,
) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val scope = serverScope.trim().ifEmpty { DEFAULT_SCOPE }

    private val _state = MutableStateFlow(HomeProjectRecords.decode(prefs.getString(key(scope), null)))

    /** The live record; recomposition-friendly (one immutable snapshot per change). */
    val state: StateFlow<HomeProjectRecords.State> = _state.asStateFlow()

    /** `projects.open()` — re-open a closed project or prepend a new one. */
    fun open(directory: String) = update { HomeProjectRecords.open(it, directory) }

    /** `projects.touch()` — mark the most recently used directory ([last]). */
    fun touch(directory: String) = update { HomeProjectRecords.touch(it, directory) }

    /** `projects.move()` — reorder the list. */
    fun move(worktree: String, index: Int) = update { HomeProjectRecords.move(it, worktree, index) }

    /** `projects.close()` — move a project into [recentlyClosed]. */
    fun close(directory: String) = update { HomeProjectRecords.close(it, directory) }

    /** `projects.list()` — the opened projects, most recently opened first. */
    fun list(): List<HomeProjectRecords.Project> = _state.value.list

    /** `projects.last()` — the last directory a session was opened/newed in. */
    fun last(): String? = _state.value.lastProject

    /** `projects.recentlyClosed()` — most recently closed first. */
    fun recentlyClosed(): List<String> = _state.value.recentlyClosed

    @Synchronized
    private fun update(block: (HomeProjectRecords.State) -> HomeProjectRecords.State) {
        val next = block(_state.value)
        if (next == _state.value) return
        _state.value = next
        prefs.edit().putString(key(scope), HomeProjectRecords.encode(next)).apply()
    }

    companion object {
        /** Preference file. Plain prefs, like `oc_nav_state` / `oc_appearance`. */
        const val PREFS = "oc_home_projects"

        /** Key of the record used before a server URL is known (single-server shell). */
        const val DEFAULT_SCOPE = "default"

        /** One record per server: upstream `store.projects[ServerConnection.key(conn)]`. */
        fun key(scope: String): String = "projects:$scope"
    }
}
