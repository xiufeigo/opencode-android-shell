package com.xiufeigo.opencodeshell.ui.workspace

import com.xiufeigo.opencodeshell.ServerConfigStore
import com.xiufeigo.opencodeshell.ui.home.NewSessionWorkspaceModel
import com.xiufeigo.opencodeshell.ui.settings.projectIDForWorktree
import com.xiufeigo.opencodeshell.v2.V2Api
import com.xiufeigo.opencodeshell.v2.refreshWorktrees
import com.xiufeigo.opencodeshell.v2.worktreeCreate
import com.xiufeigo.opencodeshell.v2.worktreeRemove
import org.json.JSONObject

/**
 * The pure half of 「创建工作树」 — the create stop both upstream surfaces offer:
 * the new-session workspace selector (`new-session/workspace/selector.tsx:152-163`,
 * `controller.ts:203-206` `selection.create`) and the session's Move menu
 * (`session/timeline/session-workspace-menu.tsx:103-127` `move("create")`).
 * Both land in `workspaces/create.ts:7-22` `createWorktree`, which this file
 * transcribes:
 *
 * ```ts
 * const project = input.project ?? (await api.location.get({ location: { directory } })).project
 * const created = await api.worktree.create({ projectID: project.id, from: project.canonical, branch: input.branch })
 * await data.location.syncInfo({ directory: created.directory })
 * return created.directory
 * ```
 *
 * No Compose, no Android UI: the dialogs render what this decides, and
 * [createWorktree] is the one blocking write (call it on an IO dispatcher).
 *
 * Deviations from upstream, both deliberate:
 *
 * - the project is resolved from `GET /api/project` by path containment
 *   ([NewSessionWorkspaceModel.pickProject]) instead of `location.get`, which
 *   this shell has no route for — the same substitution `HomeProjectApi`
 *   documents for its project lookups;
 * - the branch list is read once without `?search` and filtered client-side
 *   ([branches]); upstream re-queries the server per keystroke
 *   (`controller.ts:150-157` + `searchBranches`). Same list, one request.
 */
object WorktreeCreateModel {

    /** `controller.ts:236` `branches().slice(0, 50)` — the picker's cap. */
    const val BRANCH_LIMIT: Int = 50

    /** `Worktree.CreateInput` (`schema/src/worktree.ts:12-23`) as this shell sends it. */
    data class Request(val projectID: String, val from: String?, val branch: String?)

    /**
     * `createWorktree`'s `worktree.create` body (`create.ts:12-16`, asserted by
     * upstream's `create.test.ts:62-66`): the owning project's id, its
     * **canonical** checkout as the source, and the picked branch. A blank
     * branch is omitted — `Worktree.CreateInput.branch` is an optional
     * non-empty string, and an explicit `""` would be a 400.
     */
    fun request(projectID: String, canonical: String?, branch: String?): Request = Request(
        projectID = projectID,
        from = canonical?.trim()?.takeIf { it.isNotEmpty() },
        branch = normalizeBranch(branch),
    )

    /** `Worktree.CreateInput.branch`: blank means "the project default". */
    fun normalizeBranch(branch: String?): String? = branch?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * `controller.ts:234-243` `branches()`:
     *
     * ```ts
     * const loaded = branches.latest
     * const list = loaded?.directory === projectRoot() ? loaded.data : []
     * return [...new Set([...list, ...(current && current.toLowerCase().includes(search.toLowerCase()) ? [current] : [])])].slice(0, 50)
     * ```
     *
     * i.e. the server's list — only when it was read for the current project
     * root, so a stale read for another project cannot leak in — plus the
     * checkout's own branch when that one matches the search, de-duplicated and
     * capped at [limit]. [search] is applied here rather than server-side (see
     * the class KDoc).
     */
    fun branches(
        loaded: List<String>,
        loadedFor: String?,
        projectRoot: String?,
        current: String?,
        search: String,
        limit: Int = BRANCH_LIMIT,
    ): List<String> {
        val list = if (loadedFor != null && NewSessionWorkspaceModel.sameDirectory(loadedFor, projectRoot)) {
            loaded
        } else {
            emptyList()
        }
        val query = search.trim()
        val matched = list.filter { it.contains(query, ignoreCase = true) }
        val head = current
            ?.takeIf { it.isNotBlank() && it.contains(query, ignoreCase = true) }
            ?.let { listOf(it) }
            .orEmpty()
        return (matched + head).distinct().take(limit)
    }

    // ------------------------------------------------------------- lifecycle

    /** One user activation: the dialog enters the preparing state. */
    fun start(): WorktreeCreateState = WorktreeCreateState.Preparing

    /**
     * The create answered: a directory (the worktree) or nothing usable. The
     * empty answer is a failure — a session must not be drafted in a directory
     * the server did not name.
     */
    fun settle(directory: String?): WorktreeCreateState =
        directory?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { WorktreeCreateState.Created(it) }
            ?: WorktreeCreateState.Failed(WorktreeCreateFailure.REQUEST_FAILED, null)

    fun fail(reason: WorktreeCreateFailure, message: String?): WorktreeCreateState =
        WorktreeCreateState.Failed(reason, message)

    /**
     * `errorMessage` (`composer-adapter.ts:218-229`): the error's own `message`,
     * then its `data.message`, then `common.requestFailed`. [V2Api.ApiException]
     * already folds `data.message`/`name` into its message (`V2Api.errorText`),
     * so the server's own sentence survives here too — the only difference is
     * the `HTTP {code} {tag}: ` prefix the shell keeps for its error cards.
     */
    fun failureDetail(message: String?, requestFailed: String): String =
        message?.trim()?.takeIf { it.isNotEmpty() } ?: requestFailed
}

/** The create's lifecycle, as the three call sites render it. */
sealed interface WorktreeCreateState {
    /** Nothing started; the picker is up. */
    data object Idle : WorktreeCreateState

    /** `session.new.worktree.creating` — the create is in flight. */
    data object Preparing : WorktreeCreateState

    /** `Worktree.Info.directory` — the worktree exists. */
    data class Created(val directory: String) : WorktreeCreateState

    /** `prompt.toast.worktreeCreateFailed.title` + the detail. */
    data class Failed(val reason: WorktreeCreateFailure, val message: String?) : WorktreeCreateState
}

/** Why a create ended without a directory. */
enum class WorktreeCreateFailure {
    /** No project owns the source directory — upstream's failed `location.get`. */
    NO_PROJECT,

    /** The create itself failed (or answered without a directory). */
    REQUEST_FAILED,
}

/**
 * The timing of the preparing state. `session.new.worktree.creating` is the
 * shimmer upstream keeps up for as long as `tabs.pendingSession` exists
 * (`session/route.tsx:66-90`); the phone adds a bound so a create the server
 * never answers stops looking like progress.
 */
object WorktreeCreatePolicy {

    /** After this long in [WorktreeCreateState.Preparing] the hint appears. */
    const val SLOW_MS: Long = 8_000L

    /** `workspace.error.stillPreparing` — the create is taking longer than usual. */
    fun stillPreparing(elapsedMs: Long): Boolean = elapsedMs >= SLOW_MS
}

/** What one [createWorktree] call ended with. */
sealed interface WorktreeCreateOutcome {
    /** The created worktree's canonical directory (`create.ts:20`). */
    data class Created(val directory: String) : WorktreeCreateOutcome

    /** No project owns the source directory (`location.get` would have failed). */
    data object NoProject : WorktreeCreateOutcome

    /** The catalog read or the create failed; [message] is the server's own. */
    data class Failed(val message: String?) : WorktreeCreateOutcome
}

/**
 * Everything one create is built from: the owning project, the branch list of
 * its canonical checkout, and that checkout's current branch (upstream's
 * `branches()` merge source, `controller.ts:235`).
 */
sealed interface WorktreeSource {
    data class Ready(
        val projectID: String,
        val canonical: String,
        val branches: List<String>,
        val current: String?,
    ) : WorktreeSource

    /** `GET /api/project` lists nothing owning [WorktreeSource.Ready]'s directory. */
    data object NoProject : WorktreeSource

    /** The catalog or branch read failed. */
    data class Failed(val message: String?) : WorktreeSource
}

/**
 * `createWorktree` (`workspaces/create.ts:7-22`) against [directory]: resolve
 * the owning project, then `POST /api/worktree` with
 * `{projectID, from: project.canonical, branch}`.
 *
 * [directory] is the checkout the user is creating *from* (the new-session
 * destination, the session's current directory, the palette's most recent
 * project) — upstream's `input.directory`. `null` falls back to the most
 * recently updated project, which is what `NewSessionWorkspaceModel.pickProject`
 * already does for the draft's own project choice.
 */
suspend fun createWorktree(api: V2Api, directory: String?, branch: String?): WorktreeCreateOutcome {
    val projects = try {
        api.projects().map {
            NewSessionWorkspaceModel.ProjectRef(it.id, it.canonical, it.timeUpdated, it.sandboxes, it.raw.optString("vcs"))
        }
    } catch (error: Exception) {
        return WorktreeCreateOutcome.Failed(error.message)
    }
    val project = NewSessionWorkspaceModel.pickProject(projects, directory)
        ?: return WorktreeCreateOutcome.NoProject
    val request = WorktreeCreateModel.request(project.id, project.canonical, branch)
    return try {
        val created = api.worktreeCreate(
            projectID = request.projectID,
            from = request.from,
            branch = request.branch,
        )
        val createdDirectory = created.directory.trim()
        if (createdDirectory.isEmpty()) {
            WorktreeCreateOutcome.Failed(null)
        } else {
            WorktreeCreateOutcome.Created(createdDirectory)
        }
    } catch (error: Exception) {
        WorktreeCreateOutcome.Failed(error.message)
    }
}

/**
 * [createWorktree] over a second read-only handle on the shared server prefs —
 * the same fallback `NewSessionScreen` / `TerminalPanel` document, since none of
 * the three call sites is handed the host's live transport.
 */
suspend fun createWorktree(
    serverStore: ServerConfigStore,
    directory: String?,
    branch: String?,
): WorktreeCreateOutcome {
    val api = worktreeApi(serverStore) ?: return WorktreeCreateOutcome.Failed(null)
    return createWorktree(api, directory, branch)
}

/**
 * `controller.ts:150-157`'s two reads — the project behind [directory] and the
 * branch inventory of its canonical checkout (`GET /api/project`,
 * `GET /api/vcs/branch?limit=50`, `GET /api/vcs`) — as the create picker's
 * inputs.
 */
suspend fun loadWorktreeSource(api: V2Api, directory: String?): WorktreeSource {
    val projects = try {
        api.projects().map {
            NewSessionWorkspaceModel.ProjectRef(it.id, it.canonical, it.timeUpdated, it.sandboxes, it.raw.optString("vcs"))
        }
    } catch (error: Exception) {
        return WorktreeSource.Failed(error.message)
    }
    val project = NewSessionWorkspaceModel.pickProject(projects, directory)
        ?: return WorktreeSource.NoProject
    val branches = try {
        api.vcsBranches(
            limit = WorktreeCreateModel.BRANCH_LIMIT,
            locationDirectory = project.canonical,
        )
    } catch (error: Exception) {
        return WorktreeSource.Failed(error.message)
    }
    // The current branch is a nicety for the picker's head row: an unreadable
    // `GET /api/vcs` (not a repository, older server) leaves it out instead of
    // failing the whole picker.
    val current = runCatching { api.vcs(project.canonical).current }.getOrNull()
    return WorktreeSource.Ready(project.id, project.canonical, branches, current)
}

/**
 * [loadWorktreeSource] over the same second read-only handle on the shared
 * server prefs as [createWorktree].
 */
suspend fun loadWorktreeSource(serverStore: ServerConfigStore, directory: String?): WorktreeSource {
    val api = worktreeApi(serverStore) ?: return WorktreeSource.Failed(null)
    return loadWorktreeSource(api, directory)
}

/** The shared prefs → [V2Api] hop, re-reading the mirror so a late config sync is picked up. */
private fun worktreeApi(serverStore: ServerConfigStore): V2Api? {
    serverStore.restore()
    val server = serverStore.server ?: return null
    return V2Api.forServer(server.url, server.username, server.password)
}

/** What one `DELETE /api/worktree` ended with. */
sealed interface WorktreeRemoveOutcome {
    /** The worktree is gone from the server's saved inventory. */
    data class Removed(val directory: String) : WorktreeRemoveOutcome

    /** No project owns [directory], so the delete has no `projectID` to send. */
    data object NoProject : WorktreeRemoveOutcome

    /** The server refused (or was unreachable); [message] is what it said. */
    data class Failed(val message: String?) : WorktreeRemoveOutcome
}

/**
 * What a refused delete is reported with. v69: the dialog repeated the row's
 * **path** as its own reason (`Couldn't delete the worktree: /…/lucky-planet`)
 * — once because no `projectID` could be named, once because the fallback was
 * the target. Neither is a reason, so the screen now always says one of
 * these, and the server's own sentence when it sent one.
 */
object WorktreeRemoveModel {

    /**
     * The server's own message when it sent one ([V2Api.ApiException] already
     * folds `data.message`/`name` into its message — [V2Api.errorText]), else
     * the shell's own line for the case it could not be told apart from.
     * Null for [WorktreeRemoveOutcome.Removed]: a delete that landed has
     * nothing to explain.
     */
    fun detail(outcome: WorktreeRemoveOutcome, noProject: String, requestFailed: String): String? =
        when (outcome) {
            is WorktreeRemoveOutcome.Removed -> null
            WorktreeRemoveOutcome.NoProject -> noProject
            is WorktreeRemoveOutcome.Failed -> outcome.message?.trim()?.takeIf { it.isNotEmpty() } ?: requestFailed
        }
}

/**
 * The `projectID` that owns [directory] — the id `DELETE /api/worktree` needs
 * (`schema/src/worktree.ts:25-29`, where the server's own lookup is
 * `ops.find(projectID, directory)`, `core/src/worktree.ts:259-262`) and the one
 * every `GET /api/worktree?projectID=` read is scoped by.
 *
 * The new-session list **merges** the picked project's server rows with this
 * device's create records ([NewSessionWorkspaceModel.worktreeDirectories]), so
 * a row routinely belongs to a *different* project than the draft picked:
 * v69 had `lucky-planet` (`/home/xiufeigo`) sitting in the list of a draft
 * parked in an `ae5407` worktree — and that draft's own project is null
 * outright, because no project's checkout contains a worktree, so
 * [NewSessionWorkspaceModel.pickProject] finds none. Answering a delete with
 * the draft's id is what the v69 run did: with no id, the request never left
 * and the path came back as the "reason"; with the wrong one the server
 * answers `Invalid worktree directory: …` and the row stays.
 *
 * [preferredProjectID] — the caller's guess — is read first but never assumed:
 * only a project whose own inventory lists the row answers for it, and when
 * that read fails the id is walked like any other. Otherwise every
 * `GET /api/project` canonical is tried first (a project's own directory
 * outranks a claim inside another project's inventory), then each project's
 * inventory, in exactly
 * [com.xiufeigo.opencodeshell.ui.settings.projectIDForWorktree]'s order so both
 * delete seams answer the same way. A per-project read that fails is skipped
 * rather than fatal: a project the server will not enumerate is not proof
 * that no owner exists.
 */
suspend fun worktreeOwnerProjectID(api: V2Api, directory: String, preferredProjectID: String? = null): String? {
    val target = directory.trim()
    if (target.isEmpty()) return null
    val preferred = preferredProjectID?.trim()?.takeIf { it.isNotEmpty() }
    val preferredRows = preferred?.let { runCatching { api.worktrees(it) }.getOrNull() }
    if (preferred != null && preferredRows != null) {
        projectIDForWorktree(target, preferredRows.map { preferred to it.optString("directory") })
            ?.let { return it }
    }
    val projects = api.projects().filter { it.id.isNotBlank() }
    projectIDForWorktree(target, projects.map { it.id to it.canonical })?.let { return it }
    for (project in projects) {
        if (project.id == preferred && preferredRows != null) continue
        val rows = runCatching { api.worktrees(project.id) }.getOrNull() ?: continue
        projectIDForWorktree(target, rows.map { project.id to it.optString("directory") })?.let { return it }
    }
    return null
}

/**
 * Whether a refused `DELETE /api/worktree` is asking for `force` — a dirty
 * worktree answers `400 WorktreeError` with `data.forceRequired`
 * (`protocol/src/groups/worktree.ts:10-17`, `core/src/worktree.ts:257-270`).
 *
 * Only that error buys the retry: a transport failure is a failure, and
 * re-sending the delete with `force = true` would drop an uncommitted checkout
 * on the strength of a dropped packet.
 */
fun worktreeForceRequired(rawBody: String?): Boolean {
    val body = rawBody?.trim().orEmpty()
    if (body.isEmpty()) return false
    return runCatching { JSONObject(body).optBoolean("forceRequired", false) }.getOrDefault(false) ||
        runCatching { JSONObject(body).getJSONObject("data").optBoolean("forceRequired", false) }.getOrDefault(false)
}

/**
 * `DELETE /api/worktree` ([worktreeRemove]) for one worktree — the same write
 * the Settings worktrees page offers, reachable from the new-session worktree
 * list (v67: the list had no delete entry at all, so a worktree this device
 * created could never be removed from there).
 *
 * [preferredProjectID] is only the caller's guess: the id that travels is
 * [worktreeOwnerProjectID]'s, because the list's rows and the draft's project do
 * not have to agree (v69). An unowned row is
 * [WorktreeRemoveOutcome.NoProject] — a fact the screen can name — instead of a
 * delete nobody can send.
 *
 * Unverified first: the delete is retried with `force = true` only when the
 * server says the checkout is dirty ([worktreeForceRequired]).
 *
 * A delete that lands is followed by `POST /api/worktree/refresh`: the git
 * cleanup itself is the server's (`git worktree remove` drops
 * `.git/worktrees/<name>`, `core/src/git.ts:669-681`), but a directory removed
 * outside the API leaves the saved row — and the git admin entry — behind
 * (`git worktree list` then reports it prunable, v68 `eager-lagoon`). The
 * refresh reconciles exactly that (missing directories drop out of the
 * inventory, `core/src/worktree.ts:315`), so the list a re-read answers is the
 * truth. Best-effort: a failed reconcile never fails the delete.
 */
suspend fun removeWorktree(
    api: V2Api,
    preferredProjectID: String?,
    directory: String,
): WorktreeRemoveOutcome {
    val target = directory.trim()
    if (target.isEmpty()) return WorktreeRemoveOutcome.Failed(null)
    val projectID = try {
        worktreeOwnerProjectID(api, target, preferredProjectID)
    } catch (error: Exception) {
        return WorktreeRemoveOutcome.Failed(error.message)
    } ?: return WorktreeRemoveOutcome.NoProject
    return try {
        api.worktreeRemove(projectID, target, false)
        runCatching { api.refreshWorktrees(projectID) }
        WorktreeRemoveOutcome.Removed(target)
    } catch (error: Exception) {
        val forced = (error as? V2Api.ApiException)?.let { worktreeForceRequired(it.rawBody) } == true
        if (!forced) return WorktreeRemoveOutcome.Failed(error.message)
        try {
            api.worktreeRemove(projectID, target, true)
            runCatching { api.refreshWorktrees(projectID) }
            WorktreeRemoveOutcome.Removed(target)
        } catch (forcedError: Exception) {
            WorktreeRemoveOutcome.Failed(forcedError.message)
        }
    }
}

/** [removeWorktree] over the same second read-only handle as [createWorktree]. */
suspend fun removeWorktree(
    serverStore: ServerConfigStore,
    preferredProjectID: String?,
    directory: String,
): WorktreeRemoveOutcome {
    val api = worktreeApi(serverStore) ?: return WorktreeRemoveOutcome.Failed(null)
    return removeWorktree(api, preferredProjectID, directory)
}
