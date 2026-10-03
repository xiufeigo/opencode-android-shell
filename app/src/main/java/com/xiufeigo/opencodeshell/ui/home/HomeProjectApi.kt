package com.xiufeigo.opencodeshell.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.ServerConfigStore
import com.xiufeigo.opencodeshell.data.HomeProjectRecords
import com.xiufeigo.opencodeshell.data.HomeProjectStore
import com.xiufeigo.opencodeshell.data.ProjectPaths
import com.xiufeigo.opencodeshell.v2.ProjectCommands
import com.xiufeigo.opencodeshell.v2.ProjectIcon
import com.xiufeigo.opencodeshell.v2.V2Api
import com.xiufeigo.opencodeshell.v2.refreshWorktrees
import com.xiufeigo.opencodeshell.v2.updateProject
import kotlin.coroutines.cancellation.CancellationException

/**
 * The catalog-write seam behind the project row's edit / refresh actions
 * (`PATCH /api/project/{id}` and `POST /api/worktree/refresh` — both
 * `V2ApiCatalogExtras`), plus the **client-local** write upstream falls back
 * to for a worktree the server has no `project.id` for
 * (`project-model.ts:41-46` → `sync.project.meta`). Self-serve from the shared
 * server prefs exactly like
 * `ui/session/summary/SummaryServiceSource.rememberSummaryCatalogs`: the
 * summary precedent for "the host never handed us a client and
 * `ChatViewModel` keeps its `V2Api` private".
 *
 * The local store is a **constructor seam, not a second instance**: the host
 * passes the very [HomeProjectStore] the Home column already observes, so a
 * [saveLocal] publishes through that object's `StateFlow` and the row repaints
 * without a second source of truth. [rememberHomeProjectApi] only forwards
 * the handle the host was given.
 *
 * Blocking (OkHttp) for the two server calls; every method here runs on
 * `Dispatchers.IO`.
 */
class HomeProjectApi(
    private val serverStore: ServerConfigStore,
    /**
     * The client-local opened-project store — upstream's
     * `childStore.projectMeta` (`global-sync/child-store.ts:335-351`). Null in
     * previews / isolated hosts, which have no row to save onto either.
     */
    private val localStore: HomeProjectStore? = null,
) {

    private fun api(): V2Api? {
        // Re-read the prefs mirror before every call: `MainActivity`'s store is
        // the instance the WebView sync writes (`updateFromJson`), and this
        // self-serve handle only observes those writes by re-restoring. The
        // one-shot `restore()` at composition can predate the first sync and
        // keep sending no credentials at all — `curl` works, every call here
        // 401s into a bare 「request failed」.
        serverStore.restore()
        val server = serverStore.server ?: return null
        return V2Api.forServer(server.url, server.username, server.password)
    }

    /**
     * The `GET /api/project` catalogue as [HomeProjectEdit.ServerProject] rows
     * (canonical + sandboxes + `name` from `V2Project.raw`). Throws on a failed
     * read; [loadNames] is the best-effort variant the column uses.
     */
    fun loadServerProjects(): List<HomeProjectEdit.ServerProject> {
        val api = api() ?: throw IllegalStateException("no server")
        return api.projects().map {
            HomeProjectEdit.ServerProject(
                id = it.id,
                worktree = it.canonical,
                sandboxes = it.sandboxes,
                name = it.raw.optString("name", ""),
            )
        }
    }

    /**
     * The catalogue's display names by match key
     * ([HomeProjectEdit.namesByKey]) — the `name` half of upstream
     * `displayName`. Best-effort: no server or a failed read is the empty map,
     * so rows keep their folder-name fallback instead of failing Home's read.
     */
    fun loadNames(): Map<String, String> {
        return try {
            HomeProjectEdit.namesByKey(loadServerProjects())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyMap()
        }
    }

    /**
     * The dialog's [HomeProjectEdit.Original] for one worktree, read from
     * `GET /api/project` (bare list, `V2Project.raw` holds `name`/`icon`/
     * `commands` when set). [ProjectLoad.Ready] with `projectID = null` is a
     * worktree the server does not list — and that is a **normal** answer, not
     * a failure: it carries the client-local meta the editor will write back
     * ([saveLocal]), so a second visit to the dialog offers what the first one
     * saved instead of three blank fields
     * (`runtime.tsx:151`'s read side). [ProjectLoad.Failed] is a failed read
     * with the transport's own reason; a host with no server at all is
     * [ProjectFault.NoServer] and stays a failure, unchanged.
     */
    fun loadOriginal(worktree: String): ProjectLoad {
        val api = api() ?: return ProjectLoad.Failed(ProjectFault.NoServer)
        return try {
            val rows = api.projects()
            val match = HomeProjectEdit.matchIn(
                path = worktree,
                projects = rows.map {
                    HomeProjectEdit.ServerProject(
                        id = it.id,
                        worktree = it.canonical,
                        sandboxes = it.sandboxes,
                        name = it.raw.optString("name", ""),
                    )
                },
            )
            val source = match?.let { m -> rows.firstOrNull { it.id == m.id } }
            ProjectLoad.Ready(
                if (source == null) {
                    HomeProjectEdit.originalWithLocalMeta(
                        HomeProjectEdit.Original(
                            projectID = null,
                            name = "",
                            color = null,
                            startup = "",
                            folderName = HomeProjectEdit.folderName(worktree),
                        ),
                        localMetaOf(worktree),
                    )
                } else {
                    HomeProjectEdit.originalFromJson(source.id, worktree, source.raw)
                },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ProjectLoad.Failed(HomeProjectEdit.faultOf(e))
        }
    }

    /** The client-local meta for [worktree] ([HomeProjectRecords.LocalMeta.NONE] without a store). */
    private fun localMetaOf(worktree: String): HomeProjectRecords.LocalMeta =
        localStore?.localMeta(worktree) ?: HomeProjectRecords.LocalMeta.NONE

    /** `updateProject` with only the changed fields ([HomeProjectEdit.Patch] nulls omitted). */
    fun save(projectID: String, patch: HomeProjectEdit.Patch): ProjectWrite {
        val api = api() ?: return ProjectWrite.Failed(ProjectFault.NoServer)
        return try {
            api.updateProject(
                projectID = projectID,
                name = patch.name,
                // `Project.Icon` carries both halves; only the changed ones are
                // put on the body (`ProjectIcon.toJson` drops nulls), so a
                // color-only save never rewrites the stored image and vice
                // versa — the same "absent key leaves the field" contract the
                // other patch fields rely on.
                icon = if (patch.iconColor != null || patch.iconOverride != null) {
                    ProjectIcon(color = patch.iconColor, `override` = patch.iconOverride)
                } else {
                    null
                },
                commands = patch.startup?.let { ProjectCommands(start = it) },
            )
            ProjectWrite.Ok
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ProjectWrite.Failed(HomeProjectEdit.faultOf(e))
        }
    }

    /**
     * `sync.project.meta(worktree, patch)` — the write for a worktree the
     * server has no `project.id` for (`project-model.ts:46`). The counterpart
     * of [save] with the same [HomeProjectEdit.Patch] and the same
     * tri-state (null = untouched, `""` = cleared); the shallow, field-level
     * merge is `HomeProjectRecords.setMeta`'s
     * (`child-store.ts:335-351`).
     *
     * No server call and no network: the write lands in the same
     * [HomeProjectStore] instance the Home column observes, so the row's
     * avatar and label follow in the same frame. The one failure this can
     * report is a host that wired no store at all — a preview / isolated host,
     * where there is no row to save onto either; the real host
     * (`OcApp` → `HomeScreen`) always has one.
     */
    fun saveLocal(worktree: String, patch: HomeProjectEdit.Patch): ProjectWrite {
        val store = localStore ?: return ProjectWrite.Failed(ProjectFault.NoServer)
        return try {
            store.setMeta(worktree, HomeProjectEdit.metaPatchFor(patch))
            ProjectWrite.Ok
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ProjectWrite.Failed(HomeProjectEdit.faultOf(e))
        }
    }

    /**
     * Upstream `home.project.add`'s registration probe
     * (`_upstream/packages/app/src/home/model.ts:101-120`): for a directory that
     * is not open yet it runs `api.file.list({path: ".", location})` and then
     * `api.location.get({location})`, and the `project.id` that comes back is
     * what it hands to its client-side project sync
     * (`ctx.sync.child(item, { bootstrap: false })[1]("project", project.id)`).
     *
     * Loading the location is what makes the server answer the directory in
     * `GET /api/project` — the catalogue the shell enriches its rows from and
     * the one `NewSessionScreen.pickProject` matches a draft's directory
     * against. Without the probe a freshly added directory stays invisible to
     * both: the row renders with no `vcs`/name/icon, and a draft scoped to it
     * silently falls back to the most recently updated project.
     *
     * Best-effort by contract, exactly like upstream's `.catch(() => undefined)`:
     * the row is already in the client-local store
     * ([com.xiufeigo.opencodeshell.data.HomeProjectRecords.open]) before this
     * runs, so a server that cannot load the location (no Git, an unreadable
     * directory, an offline host) must not undo the user's add. `false` means
     * "the probe did not land", never "the add failed".
     *
     * Blocking (OkHttp) — callers run it on `Dispatchers.IO`.
     */
    fun register(worktree: String): Boolean {
        val api = api() ?: return false
        val directory = ProjectPaths.normalizeLocation(worktree) ?: return false
        return try {
            api.fsList(path = ".", locationDirectory = directory)
            api.location(directory = directory)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    /** `POST /api/worktree/refresh` for the worktree's project — see [ProjectWrite]. */
    fun refreshWorktrees(worktree: String): ProjectWrite {
        val api = api() ?: return ProjectWrite.Failed(ProjectFault.NoServer)
        return try {
            val projectID = HomeProjectEdit.projectIdFor(
                path = worktree,
                projects = api.projects().map {
                    HomeProjectEdit.ServerProject(
                        id = it.id,
                        worktree = it.canonical,
                        sandboxes = it.sandboxes,
                        name = it.raw.optString("name", ""),
                    )
                },
            ) ?: return ProjectWrite.NotRegistered
            api.refreshWorktrees(projectID)
            ProjectWrite.Ok
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ProjectWrite.Failed(HomeProjectEdit.faultOf(e))
        }
    }
}

/** The toast/dialog line for a [ProjectFault] — [ProjectFault.NoServer] has fixed copy. */
fun projectFaultText(context: android.content.Context, fault: ProjectFault): String = when (fault) {
    ProjectFault.NoServer -> context.getString(R.string.oc_home_no_server)
    is ProjectFault.Request -> fault.message
}

/** [HomeProjectApi] over the shared server prefs (see [HomeProjectApi]). */
@Composable
fun rememberHomeProjectApi(homeProjectStore: HomeProjectStore? = null): HomeProjectApi {
    val context = LocalContext.current
    val serverStore = remember(context) { ServerConfigStore(context).also { it.restore() } }
    return remember(serverStore, homeProjectStore) { HomeProjectApi(serverStore, homeProjectStore) }
}
