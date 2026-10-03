package com.xiufeigo.opencodeshell.ui.home

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.PopupProperties
import androidx.core.content.edit
import com.xiufeigo.opencodeshell.ChatViewModel
import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.ServerConfigStore
import com.xiufeigo.opencodeshell.ui.components.OcKeyboard
import com.xiufeigo.opencodeshell.ui.components.ocMinTouchTarget
import com.xiufeigo.opencodeshell.ui.components.ocOverlayEscape
import com.xiufeigo.opencodeshell.ui.components.touchTargetRingOverflow
import com.xiufeigo.opencodeshell.ui.overlay.DialogScrim
import com.xiufeigo.opencodeshell.ui.overlay.dialogCardBounds
import com.xiufeigo.opencodeshell.ui.overlay.rememberDialogScrimState
import com.xiufeigo.opencodeshell.ui.settings.McpPageRow
import com.xiufeigo.opencodeshell.ui.settings.OcSwitch
import com.xiufeigo.opencodeshell.ui.settings.mcpPageRows
import com.xiufeigo.opencodeshell.ui.theme.OcTheme
import com.xiufeigo.opencodeshell.ui.theme.ocShadow
import com.xiufeigo.opencodeshell.ui.palette.CommandRequest
import com.xiufeigo.opencodeshell.ui.palette.LocalSessionCommandIntents
import com.xiufeigo.opencodeshell.ui.workspace.WorktreeCreateDialog
import com.xiufeigo.opencodeshell.ui.workspace.WorktreeRemoveModel
import com.xiufeigo.opencodeshell.ui.workspace.WorktreeRemoveOutcome
import com.xiufeigo.opencodeshell.ui.workspace.removeWorktree
import com.xiufeigo.opencodeshell.v2.ServerGeneration
import com.xiufeigo.opencodeshell.v2.TransportFailure
import com.xiufeigo.opencodeshell.v2.V2Api
import com.xiufeigo.opencodeshell.v2.mcpConnect
import com.xiufeigo.opencodeshell.v2.mcpDisconnect
import com.xiufeigo.opencodeshell.v2.refreshWorktrees
import com.xiufeigo.opencodeshell.v2ConnectedProviderIds
import kotlin.coroutines.resume
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray

/**
 * Pure rules of the new-session workspace picker
 * (`new-session/workspace/selector.tsx` + `controller.ts` at the v2 head).
 *
 * The selector offers `main` (the project root — upstream's `Local` stop), the
 * **create** stop (`session.new.worktree.create`, `selector.tsx:152-163`; the
 * create itself is `ui/workspace/WorktreeCreate.kt`) plus every worktree of the
 * current project (`GET /api/worktree?projectID=`); the chosen destination
 * becomes `createSession(directory)`. Upstream's nested branch list of the
 * create stop (`selector.tsx:257-360`) is the branch picker inside
 * `WorktreeCreateDialog` here, because a `DropdownMenu` cannot nest and this
 * shell creates the worktree eagerly instead of at draft submit.
 */
object NewSessionWorkspaceModel {

    /** Upstream's sentinel for the project root (`controller.ts` `"main"`). */
    const val LOCAL: String = "main"

    /** One selectable project of `GET /api/project`. */
    data class ProjectRef(
        val id: String,
        val canonical: String,
        val timeUpdated: Long = 0L,
        val sandboxes: List<String> = emptyList(),
        /**
         * The project's own `vcs` (`GET /api/project`), i.e. upstream's
         * `currentProject()?.vcs` in `resolveNewSessionGit`
         * (`new-session/workspace/controller.ts:36-38`, `120`). It answers "is
         * this a repository" for a worktree whose own `GET /api/vcs` is still
         * empty.
         */
        val vcs: String? = null,
    )

    /**
     * `sameDirectory` (`workspaces/paths`): separator-insensitive, trailing-
     * separator-insensitive, case-insensitive — the app compares Windows and
     * POSIX server paths alike.
     */
    fun sameDirectory(a: String?, b: String?): Boolean {
        val left = normalizePath(a) ?: return false
        val right = normalizePath(b) ?: return false
        return left.equals(right, ignoreCase = true)
    }

    /** `containsDirectory(parent, child)` — path-prefix containment. */
    fun containsDirectory(parent: String?, child: String?): Boolean {
        val root = normalizePath(parent) ?: return false
        val target = normalizePath(child) ?: return false
        if (target.equals(root, ignoreCase = true)) return true
        val prefix = if (root.endsWith("/")) root else "$root/"
        return target.startsWith(prefix, ignoreCase = true)
    }

    /** `selected() === "main"`: unset, the sentinel, or the project root itself. */
    fun isLocal(value: String?, projectRoot: String?): Boolean =
        value.isNullOrBlank() || value == LOCAL || sameDirectory(value, projectRoot)

    /** `getFilename(workspace)` — last path segment (both separators). */
    fun worktreeName(path: String): String {
        val trimmed = path.trim().trimEnd('\\', '/')
        if (trimmed.isEmpty()) return path
        val cut = maxOf(trimmed.lastIndexOf('\\'), trimmed.lastIndexOf('/'))
        return trimmed.substring(cut + 1).ifEmpty { trimmed }
    }

    /** Upstream's inline search over `getFilename(workspace)` (case-insensitive). */
    fun filterWorktrees(worktrees: List<String>, query: String): List<String> {
        val q = query.trim()
        if (q.isEmpty()) return worktrees
        return worktrees.filter { worktreeName(it).contains(q, ignoreCase = true) }
    }

    /** `selector.tsx` mounts the search field at `workspaces.length >= 10`. */
    fun showsWorktreeSearch(count: Int): Boolean = count >= 10

    /**
     * `GET /api/worktree` rows (+ the project's sandboxes) → the menu's
     * worktree paths, project root excluded and de-duplicated
     * (`controller.ts` `worktreeDirectories`). Row keys are read tolerantly
     * (`directory`/`worktree`/`path`) like [worktreePageRows].
     *
     * [created] are the worktrees this device created, as its own create record
     * ([NewSessionWorktreeStore]) remembers them. v67 measured the server
     * inventory answering without the worktree `POST /api/worktree` had just
     * created — the list showed only the older `tidy-orchid`, and Refresh
     * (which reconciled the server side) did not add it either — so a worktree
     * this session made could not be selected at all. The record is merged in
     * behind the server's rows: same paths, de-duplicated by [normalizePath],
     * so the merge can only ever *add* what the read missed.
     */
    fun worktreeDirectories(
        rows: List<org.json.JSONObject>,
        projectRoot: String?,
        sandboxes: List<String> = emptyList(),
        created: List<String> = emptyList(),
    ): List<String> {
        val fromRows = rows.mapNotNull { row ->
            listOf("directory", "worktree", "path")
                .firstNotNullOfOrNull { key -> row.optString(key, "").trim().takeIf { it.isNotEmpty() } }
        }
        return (fromRows + sandboxes + created)
            .filter { !sameDirectory(it, projectRoot) }
            .distinctBy { normalizePath(it)?.lowercase() ?: it.lowercase() }
    }

    /**
     * The list a deleted worktree leaves behind ([withoutWorktree]) — the same
     * path identity the merge dedups on, so a spelling difference between the
     * row and the delete target cannot leave a ghost row.
     */
    fun withoutWorktree(worktrees: List<String>, directory: String): List<String> =
        worktrees.filterNot { sameDirectory(it, directory) }

    /**
     * The create records that belong to [projectRoot] on a server holding
     * several projects — the merge's own scope check.
     *
     * A worktree is not required to live under its project (`schema/src/
     * worktree.ts:15-18` puts it in the server's data directory by default), so
     * containment cannot *admit* a record. It can only **reject** one: a record
     * living under a *different* project's checkout is that project's
     * worktree, and the server list is per project.
     */
    fun worktreeRecordsForProject(
        created: List<String>,
        projectRoot: String?,
        projectRoots: List<String> = emptyList(),
    ): List<String> {
        val mine = projectRoot?.trim()?.takeIf { it.isNotEmpty() } ?: return created
        val others = projectRoots.map { it.trim() }.filter { it.isNotEmpty() && !sameDirectory(it, mine) }
        return created.filter { record -> others.none { containsDirectory(it, record) } }
    }

    /**
     * `controller.ts` `currentProject`: the project owning [preselected] (the
     * directory the caller asked for), else the project owning the server's own
     * working directory [cwd].
     *
     * v58: the fallback used to be "the most recently updated project", which on a
     * **shared** server is another device's project — a new session created from
     * `+` was born inside it and carried its name（「Untitled E2ECHK5560_proj」 on
     * emulator-5556, `research/v58_verify/5556/47_home.png`; the project was not
     * even in this device's Home list). A project the caller named is the only
     * one this screen may create a session in; with nothing named, the session
     * falls back to the server cwd (null), and the summary card says so.
     */
    fun pickProject(
        projects: List<ProjectRef>,
        preselected: String?,
        cwd: String? = null,
    ): ProjectRef? {
        if (projects.isEmpty()) return null
        val wanted = preselected?.takeIf { it.isNotBlank() }
        if (wanted != null) {
            projects.firstOrNull { containsDirectory(it.canonical, wanted) }?.let { return it }
            return null
        }
        val root = cwd?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return projects.firstOrNull { containsDirectory(it.canonical, root) }
    }

    /**
     * `createSession`'s `directory`: the project root for the `main` stop
     * (null only when no project is known — the pre-picker server-cwd
     * behaviour), else the picked worktree path.
     */
    fun destination(value: String?, projectRoot: String?): String? =
        if (isLocal(value, projectRoot)) {
            projectRoot?.trim()?.takeIf { it.isNotEmpty() }
        } else {
            value?.trim()?.takeIf { it.isNotEmpty() }
        }

    /**
     * B-5: `session.location.cycle` — the native projection of
     * `cycleNewSessionWorktree` (`new-session/workspace/controller.ts:40-44`).
     *
     * Upstream's cycle has three stops (`"main"` → `"create"` →
     * `existing ?? "main"`); this shell cannot create a worktree, so its cycle is
     * the two that exist: from the project root (`main`, i.e. a null/blank
     * selection) it goes to the last picked worktree, and from a worktree back to
     * the root. With nothing ever picked the root stays the only stop — the chord
     * is then a no-op instead of inventing a directory.
     */
    fun cycleLocation(selected: String?, lastWorktree: String?, projectRoot: String?): String? {
        if (!isLocal(selected, projectRoot)) return null
        val previous = lastWorktree?.trim()?.takeIf { it.isNotEmpty() } ?: return selected
        return if (isLocal(previous, projectRoot)) selected else previous
    }

    private fun normalizePath(path: String?): String? {
        val trimmed = path?.trim()?.trimEnd('\\', '/') ?: return null
        return trimmed.replace('\\', '/').takeIf { it.isNotEmpty() }
    }
}

/**
 * Pure rules of the new-session MCP draft (`new-session/mcp.ts:11-90`).
 *
 * Upstream's draft holds a `{[name]: enabled}` map per target directory and
 * `apply()`s it at send time: an enabled name the directory does not offer
 * aborts with 「unavailable」, every server whose state differs is
 * connected/disconnected, and the page is re-read — a server that did not end
 * up `connected` (or `disabled`) aborts with 「not ready」 or 「sign in before
 * send」 (`mcp.ts:57-90`). The phone keeps no per-directory draft store, so this
 * page holds one map for the destination it is about to create in and runs the
 * same `apply()` at Create; toggling writes nothing.
 */
object NewSessionMcpDraft {

    /** `status !== "disabled"` — upstream's switch position (`server-panel.tsx:226`). */
    fun defaultEnabled(status: String?): Boolean = !status.equals("disabled", ignoreCase = true)

    /** The switch's value: the user's override, else the server's own state. */
    fun isEnabled(states: Map<String, Boolean>, name: String, status: String?): Boolean =
        states[name] ?: defaultEnabled(status)

    /** Records one switch position (upstream's `{...states(), [name]: enabled}`). */
    fun withState(states: Map<String, Boolean>, name: String, enabled: Boolean): Map<String, Boolean> =
        states + (name to enabled)

    /**
     * `GET /api/mcp` rows in upstream's menu order (`server-panel.tsx:170-172`
     * `toSorted(name.localeCompare)`) — `mcpPageRows` orders connected-first for
     * the chooser dialog, which is not this list's order.
     */
    fun servers(json: String?): List<McpPageRow> =
        mcpPageRows(json).sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })

    /**
     * `apply()`'s write set (`mcp.ts:68-77`): an enabled server that is not
     * connected yet, or a disabled one that is not already disabled. A draft
     * matching the server writes nothing, so an untouched page never touches the
     * runtime set. Insertion order is the user's, like upstream's `Object.entries`.
     */
    fun writes(rows: List<McpPageRow>, states: Map<String, Boolean>): List<McpWrite> =
        states.entries.mapNotNull { (name, enabled) ->
            val status = rows.firstOrNull { it.name == name }?.status ?: return@mapNotNull null
            val settled = if (enabled) status.equals("connected", ignoreCase = true)
            else status.equals("disabled", ignoreCase = true)
            if (settled) null else McpWrite(name, enabled)
        }

    /** `apply()`'s first guard (`mcp.ts:62-65`): an enabled name the directory does not offer. */
    fun unavailable(rows: List<McpPageRow>, states: Map<String, Boolean>): String? =
        states.entries.firstOrNull { (name, enabled) -> enabled && rows.none { it.name == name } }?.key

    /**
     * `apply()`'s verification pass (`mcp.ts:79-90`) over the **re-read** page:
     * an enabled name that is not `connected`, or a disabled one the server
     * still lists as something other than `disabled`.
     */
    fun unresolved(rows: List<McpPageRow>, states: Map<String, Boolean>): McpUnresolved? =
        states.entries.firstNotNullOfOrNull { (name, enabled) ->
            val status = rows.firstOrNull { it.name == name }?.status
            val settled = if (enabled) status.equals("connected", ignoreCase = true)
            else status == null || status.equals("disabled", ignoreCase = true)
            if (settled) {
                null
            } else {
                McpUnresolved(name, status.equals("needs_auth", ignoreCase = true) ||
                    status.equals("needs-auth", ignoreCase = true))
            }
        }
}

/** One connect/disconnect `apply()` issues (`new-session/mcp.ts:68-77`). */
data class McpWrite(val name: String, val enabled: Boolean)

/** An override that did not hold after the writes (`new-session/mcp.ts:79-90`). */
data class McpUnresolved(val name: String, val needsAuth: Boolean)

/**
 * What `apply()` ended with (`new-session/mcp.ts:57-90`). Every non-[Ready]
 * outcome aborts the create, exactly like upstream's `prepare()` answering
 * `false` — a session must not start against a server set the user did not ask
 * for.
 */
sealed interface McpApplyOutcome {
    /** Nothing to do: no overrides, or every one of them already holds. */
    object Ready : McpApplyOutcome

    /** `session.summary.mcp.unavailable` — the enabled name is not configured here. */
    data class Unavailable(val name: String) : McpApplyOutcome

    /** `session.summary.mcp.signInBeforeSend`. */
    data class NeedsAuth(val name: String) : McpApplyOutcome

    /** `session.summary.mcp.notReady`. */
    data class NotReady(val name: String) : McpApplyOutcome

    /** A connect/disconnect or a catalog read failed (`session.summary.mcp.prepareFailed`). */
    data class Failed(val detail: String?) : McpApplyOutcome
}

/**
 * `PromptGitStatus` (`new-session/workspace/selector.tsx:374-396`): the branch
 * of the destination directory, or upstream's 「No Git」 when that directory is
 * not a repository at all.
 *
 * `Vcs.Info` (`_upstream/packages/schema/src/vcs.ts:13-17`) is the whole truth
 * available here, and the git adapter (`core/src/plugin/vcs/git.ts:60-63`) fills
 * it from two commands whose shape decides every case below:
 *
 * - `branch.current` is `git symbolic-ref --quiet --short HEAD` — **absent
 *   whenever HEAD is detached**, which is not an edge case on this page: every
 *   worktree opencode creates is `git worktree add --detach`
 *   (`core/src/git.ts:649-659`), so a worktree has no current branch *by
 *   construction*;
 * - `branch.default` is the repository's default branch, so it is present for
 *   every repository; a directory that is not one answers `{branch:{}}` — the
 *   all-empty shape, which is also what a worktree the server has not read yet
 *   answers (v64: `research/v64_verify/5556/01_07_wt_label.png` printed 「No
 *   Git」 for a freshly created worktree, and `01_73_happytiger_label.png` then
 *   printed nothing at all).
 *
 * The server's own answers tell the two all-empty shapes apart — `Vcs.Info.provider`
 * and the owning project's `vcs` ([repository]) — with a [needsRecheck] second
 * read behind them, and a detached destination says so ([Kind.DETACHED])
 * instead of claiming 「No Git」. Upstream renders *nothing* for that case
 * (`selector.tsx:379-380`, `if (!props.branch) return undefined`); naming it is
 * this shell's deliberate deviation — a blank chip is indistinguishable from a
 * page that failed to load.
 */
object NewSessionGitStatus {
    enum class Kind { BRANCH, DETACHED, NO_GIT, NONE }

    /**
     * `Vcs.Info` as this page reads it, or `null` when `GET /api/vcs` could not
     * be read at all (a failed read renders nothing — [Kind.NONE] — rather than
     * claiming the destination is not a repository).
     *
     * @param projectVcs the **project's** own `vcs` field (`GET /api/project`),
     *   which is the half of `resolveNewSessionGit` upstream reads
     *   (`controller.ts:120` `currentProject()?.vcs`) and the only witness that
     *   survives a directory the server has not read vcs for yet.
     */
    data class Info(
        val current: String?,
        val default: String?,
        val provider: String?,
        val projectVcs: String? = null,
    )

    /** Projects [V2Api.vcs]'s parse (and the owning project's `vcs`) into the rules' input. */
    fun of(vcs: V2Api.VcsInfo?, projectVcs: String? = null): Info? = vcs?.let {
        Info(
            current = it.current,
            default = it.default,
            provider = it.raw.optString("provider").takeIf { provider -> provider.isNotEmpty() },
            projectVcs = projectVcs?.takeIf { vcs -> vcs.isNotEmpty() },
        )
    }

    /**
     * `resolveNewSessionGit` (`new-session/workspace/controller.ts:36-38`):
     * `projectVcs === "git" || branch !== undefined`. The project half is the
     * server's own `provider` / the owning project's `vcs`; the `default` branch
     * is this page's third witness for a repository, since it is present in every
     * one of them.
     */
    fun repository(info: Info?): Boolean =
        info != null && (
            info.provider.equals("git", ignoreCase = true) ||
                info.projectVcs.equals("git", ignoreCase = true) ||
                !info.default.isNullOrBlank()
            )

    /** Upstream's `PromptGitStatus` label rule (`selector.tsx:377-382`). */
    fun kind(info: Info?): Kind = when {
        info == null -> Kind.NONE
        !info.current.isNullOrBlank() -> Kind.BRANCH
        repository(info) -> Kind.DETACHED
        else -> Kind.NO_GIT
    }

    /**
     * The branch the chip names: the server's own, else the branch a worktree was
     * created **from** on this device ([NewSessionWorktreeStore]) — the commit
     * its detached HEAD sits on, and upstream's own wording for it
     * (`session.new.workspace.fromBranch`, `selector.tsx:290`). A destination with
     * neither is [Kind.DETACHED] and says so instead of naming a branch it does
     * not know.
     */
    fun branch(info: Info?, sourceBranch: String? = null): String? =
        info?.current?.takeIf { it.isNotBlank() } ?: sourceBranch?.takeIf { it.isNotBlank() }

    /**
     * `GET /api/vcs` answered nothing usable: a failed read, or the all-empty
     * `{branch:{}}` that a worktree the server has not read yet and a directory
     * that is not a repository both answer. Upstream waits for the same thing by
     * keeping the resource `refreshing` (`controller.ts:105-106`
     * `worktreesLoaded`, `syncInfo`/`vcs.sync` in `controller.ts:181-186`); this
     * shell has no sync route, so it asks once more — see [RECHECK_MS].
     */
    fun needsRecheck(info: Info?): Boolean = info == null || !repository(info)

    /** Second read for a worktree the server has not answered for yet. */
    const val RECHECK_MS: Long = 1_500L
}

/**
 * The two onboarding tips of `new-session/view.tsx:167-200`. A dismissal hides
 * one for [DISMISSAL_MS] (upstream's `providerTipDismissalDuration`), and the
 * provider tip wins while both are eligible (upstream's `tip()` checks it
 * first).
 */
object NewSessionTips {

    /** `view.tsx:37` `providerTipDismissalDuration` = 30 days. */
    const val DISMISSAL_MS: Long = 30L * 24 * 60 * 60 * 1000

    enum class Tip { PROVIDER, WORKSPACE }

    /** `Date.now() - dismissedAt >= providerTipDismissalDuration`. */
    fun visible(eligible: Boolean, dismissedAt: Long, now: Long): Boolean =
        eligible && now - dismissedAt >= DISMISSAL_MS

    fun which(
        providerEligible: Boolean,
        workspaceEligible: Boolean,
        providerDismissedAt: Long,
        workspaceDismissedAt: Long,
        now: Long,
    ): Tip? = when {
        visible(providerEligible, providerDismissedAt, now) -> Tip.PROVIDER
        visible(workspaceEligible, workspaceDismissedAt, now) -> Tip.WORKSPACE
        else -> null
    }

    /**
     * `providers.paid().length === 0` (`providers/catalog/providers.ts:74-83`):
     * the connected providers minus the free [FREE_PROVIDER]. Upstream also
     * counts `opencode` when one of its models declares an input cost; the v2
     * provider page carries no model catalog (`GET /api/model` is a separate
     * route this page does not read), so the free provider is always excluded
     * here — the tip can therefore show while a paid `opencode` model is in use.
     */
    fun paidProviderCount(providers: List<V2Api.ProviderInfo>, integrations: List<V2Api.IntegrationInfo>): Int =
        v2ConnectedProviderIds(providers, integrations).count { it != FREE_PROVIDER }

    /** `providers/catalog/providers.ts:77` — the one provider upstream calls free. */
    const val FREE_PROVIDER: String = "opencode"
}

/**
 * The two dismissal timestamps of [NewSessionTips]. Upstream persists them in
 * `Persist.global` (`view.tsx:52-62`); the phone keeps its own prefs file, the
 * same stand-in `ui/overlay/UsageUpsellGate.kt` documents for its dialogs.
 */
private class NewSessionTipStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun dismissedAt(key: String): Long = prefs.getLong(key, 0L)

    fun dismiss(key: String, at: Long) {
        prefs.edit { putLong(key, at) }
    }

    companion object {
        private const val PREFS = "oc_new_session_tips"
        const val PROVIDER: String = "provider_dismissed_at"
        const val WORKSPACE: String = "workspace_dismissed_at"
    }
}

/**
 * Which branch each worktree this device created was branched **from** — the
 * create record `WorktreeCreateDialog` makes but `POST /api/worktree` does not
 * return (`Worktree.Info` is `{directory}` alone,
 * `core/src/worktree/git.ts:19-21`).
 *
 * It is the only name the new-session page can honestly offer for a worktree:
 * the create is `git worktree add --detach <branch>` (`core/src/git.ts:654-658`),
 * so `GET /api/vcs` answers an empty `branch.current` for it forever and the
 * detached HEAD sits on that branch's tip. Persisted rather than remembered,
 * because the worktree outlives the draft page that created it — v64 measured
 * the chip still blank on a second visit (`research/v64_verify/5556/01_68`,
 * `01_73`).
 */
/**
 * The create record's line codec: `directory=branch` per line, the branch
 * empty when the worktree was branched from the project default. A create
 * without a picked branch is still a create — v68 made `eager-lagoon` that
 * way, and the old codec's "no branch, no record" rule left the merge with
 * nothing to admit, so the list could only wait on the server inventory.
 */
object NewSessionWorktreeRecords {

    /** A record for [directory] branched from [branch] (null = project default). */
    fun entry(directory: String?, branch: String?): Pair<String, String?>? {
        val key = directory?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return key to branch?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** The persisted lines back into `directory -> source branch` (null = default). */
    fun parse(text: String?): Map<String, String?> = text
        ?.split('\n')
        ?.mapNotNull { line ->
            // No `=` on a default-branch line: the whole line is the directory.
            val directory = line.substringBefore('=').trim()
            if (directory.isEmpty()) null
            else directory to line.substringAfter('=', "").trim().takeIf { it.isNotEmpty() }
        }
        ?.toMap()
        .orEmpty()

    /** The entries into the persisted lines; a default branch keeps its bare directory. */
    fun format(entries: List<Pair<String, String?>>): String =
        entries.joinToString("\n") { (directory, branch) ->
            if (branch.isNullOrEmpty()) directory else "$directory=$branch"
        }
}

internal class NewSessionWorktreeStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Created worktrees, newest first, as `directory -> source branch` (null = default). */
    fun all(): Map<String, String?> = NewSessionWorktreeRecords.parse(prefs.getString(RECORD, null))

    /** The source branch of [directory], or `null` for a worktree created elsewhere. */
    fun sourceBranch(directory: String?): String? {
        val key = directory?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return all()[key] ?: all().entries.firstOrNull {
            NewSessionWorkspaceModel.sameDirectory(it.key, key)
        }?.value
    }

    fun record(directory: String?, branch: String?) {
        val first = NewSessionWorktreeRecords.entry(directory, branch) ?: return
        val next = (listOf(first) + all().toList().filterNot {
            NewSessionWorkspaceModel.sameDirectory(it.first, first.first)
        }).take(CAP)
        prefs.edit { putString(RECORD, NewSessionWorktreeRecords.format(next)) }
    }

    /**
     * Every worktree this device created, oldest first (v67: the same record is
     * the list's own backstop — a create the server's saved inventory does not
     * answer yet must still be selectable).
     */
    fun directories(): List<String> = all().keys.toList()

    /** Drop a deleted worktree, so it cannot come back through the merge. */
    fun forget(directory: String?) {
        val key = directory?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val next = all().toList().filterNot { NewSessionWorkspaceModel.sameDirectory(it.first, key) }
        if (next.size == all().size) return
        prefs.edit { putString(RECORD, NewSessionWorktreeRecords.format(next)) }
    }

    companion object {
        private const val PREFS = "oc_new_session_worktrees"

        /** The lines this file keeps; a worktree older than the cap is only 「detached」. */
        const val CAP: Int = 16
        private const val RECORD = "created"
    }
}

/**
 * The `new-session` route (`Route.NEW_SESSION`).
 *
 * Upstream this page is a draft editor (`DraftTab`) whose first send creates the
 * session with the workspace the `PromptWorkspaceSelector` picked. This shell
 * has no draft model, so the route is the honest projection of that one
 * decision: pick a workspace, then create — the destination goes to
 * `createSession(directory)` and the route hands off to `Route.SESSION`, so Back
 * from the new session lands on Home (`preselectedDirectory` seeds the
 * selection).
 *
 * Workspace data is the read-only pair the audit asked for
 * (`research/v2_audit/A2-home-shell.md` #5): `V2Api.projects()` picks the
 * project, `V2Api.worktrees(projectID)` its worktrees. Unreachable server or
 * empty catalog render the menu's honest empty line instead of inventing rows.
 * P1 closes the audit's worktree-creation gap: the selector's **create** stop
 * (`session.new.worktree.create`) opens `WorktreeCreateDialog`, whose create
 * (`POST /api/worktree`, `workspaces/create.ts`) lands in the menu and becomes
 * the selection.
 *
 * W4 (F-2/A-11) adds the three pieces upstream puts around that decision:
 * the **summary popover** (`SummaryPopover` + `NewSessionSummary`,
 * `new-session/view.tsx:100-160`) with the project card, the destination's git
 * status and the **MCP draft switches** (`new-session/mcp.ts`); the inline
 * `PromptGitStatus` chip (`selector.tsx:374-396`); and the two onboarding
 * **tips** (`view.tsx:167-200`). The draft is applied at Create — never while
 * toggling — through the same `connect`/`disconnect` pair the summary card's
 * switches use, and a draft that cannot be reached aborts the create
 * (`new-session/mcp.ts:57-111`).
 *
 * **Exit (B-21):** the route is a full-screen page and upstream gives the page
 * no close/back control of its own — the draft is a shell *tab* there, so the
 * tab strip is the way out (`new-session/route.tsx`; `view.tsx` pins only the
 * summary trigger into its top band). With no draft tab to close, this shell
 * had BACK and nothing visible, so the page now carries the platform's
 * conventional leading back arrow ([BackChevronButton]) in the leading half of
 * that same top band, beside the existing `BackHandler { onBack() }`.
 */
@Composable
fun NewSessionScreen(
    chatViewModel: ChatViewModel,
    // Directory pre-picked by the caller (Home row edit action / add-project
    // flow): seeds the selector and matches a project to load worktrees for.
    preselectedDirectory: String? = null,
    onCreated: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Host entry to the provider catalog (`Settings → Providers`), the action of
     * the provider tip (`view.tsx:190-195` opens `DialogConnectProvider`).
     * `null` leaves the tip informational — its copy plus the dismiss button,
     * no click target.
     */
    onOpenProviderSettings: (() -> Unit)? = null,
) {
    val c = OcTheme.colors
    var attempt by remember { mutableStateOf(0) }
    var creating by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<NewSessionCreatePolicy.Failure?>(null) }
    var failureDetail by remember { mutableStateOf<String?>(null) }
    var slow by remember { mutableStateOf(false) }
    val configMissing by chatViewModel.configMissing.collectAsState()

    val context = LocalContext.current
    // Second read-only handle on the shared server prefs — the same fallback
    // `ui/terminal/TerminalPanel.kt` documents: this screen is not handed the
    // host's `ServerConfigStore`, and `ChatViewModel` keeps its transport (and
    // `V2Api`) private.
    val serverStore = remember(context) { ServerConfigStore(context).also { it.restore() } }
    var project by remember { mutableStateOf<NewSessionWorkspaceModel.ProjectRef?>(null) }
    var worktrees by remember { mutableStateOf<List<String>>(emptyList()) }
    var worktreesLoaded by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(preselectedDirectory) }
    // B-5: `session.location.cycle` — the last worktree the user picked, i.e.
    // upstream's `existing()` remembered worktree (see
    // [NewSessionWorkspaceModel.cycleLocation]).
    var lastWorktree by remember { mutableStateOf<String?>(null) }
    // `session.new.worktree.create` (`selector.tsx:152-163`): the create stop's
    // branch picker + create, as one modal (`ui/workspace/WorktreeCreateDialog`).
    var worktreeCreateOpen by remember { mutableStateOf(false) }
    // v67: the menu's 「Refresh worktrees」 — a re-read of the whole project /
    // worktree pair, which also reconciles the server's saved inventory first.
    var worktreeReload by remember { mutableStateOf(0) }
    // The worktree a delete confirm is open for, and the message a refused
    // delete leaves behind (the row stays, the reason is said once).
    var worktreeDeleting by remember { mutableStateOf<String?>(null) }
    var worktreeError by remember { mutableStateOf<String?>(null) }
    val worktreeScope = rememberCoroutineScope()
    // v69: a delete that could not be sent, and a send that said nothing —
    // read here because the confirm's `onConfirm` is a plain callback.
    val noProjectDetail = stringResource(R.string.oc_new_session_worktree_delete_no_project)
    val requestFailedDetail = stringResource(R.string.common_requestFailed)

    // A-11 summary popover + its three destination-scoped reads.
    var summaryOpen by remember { mutableStateOf(false) }
    var destinationLoaded by remember { mutableStateOf(false) }
    var gitInfo by remember { mutableStateOf<NewSessionGitStatus.Info?>(null) }
    // The create record: the branch each worktree this device made was branched
    // from, and the one name a detached worktree's HEAD can honestly carry.
    val worktreeStore = remember(context) { NewSessionWorktreeStore(context) }
    var gitSourceBranch by remember { mutableStateOf<String?>(null) }
    var mcpRows by remember { mutableStateOf<List<McpPageRow>?>(null) }
    var mcpStates by remember { mutableStateOf(emptyMap<String, Boolean>()) }
    var paidProviders by remember { mutableStateOf<Int?>(null) }
    val tipStore = remember(context) { NewSessionTipStore(context) }
    var providerTipDismissedAt by remember { mutableStateOf(tipStore.dismissedAt(NewSessionTipStore.PROVIDER)) }
    var workspaceTipDismissedAt by remember { mutableStateOf(tipStore.dismissedAt(NewSessionTipStore.WORKSPACE)) }
    // The directory the session will be created in — the same value the create
    // call passes (`destination(selected, project?.canonical)`).
    val destination = NewSessionWorkspaceModel.destination(selected, project?.canonical)

    LaunchedEffect(serverStore, preselectedDirectory, worktreeReload) {
        val reloading = worktreeReload > 0
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                // Same stale-snapshot fix as `HomeProjectApi.api()`: re-read the
                // prefs mirror so a WebView config sync that landed after this
                // screen composed is picked up.
                serverStore.restore()
                val server = serverStore.server ?: return@runCatching null
                val api = V2Api.forServer(server.url, server.username, server.password)
                    ?: return@runCatching null
                val projects = api.projects().map {
                    NewSessionWorkspaceModel.ProjectRef(it.id, it.canonical, it.timeUpdated, it.sandboxes, it.raw.optString("vcs"))
                }
                val cwd = withTimeoutOrNull(SERVER_CWD_TIMEOUT_MS) {
                    suspendCancellableCoroutine<String?> { continuation ->
                        // `serverDirectory` answers on the view-model scope and
                        // stays silent when no transport is installed, so the
                        // timeout is the answer for that path too.
                        chatViewModel.serverDirectory { continuation.resume(it) }
                    }
                }
                val picked = NewSessionWorkspaceModel.pickProject(projects, preselectedDirectory, cwd)
                if (picked == null) {
                    // v68: no project owns the current directory — the server
                    // cwd was the fresh data-dir worktree, contained by no
                    // checkout — so the read used to be dropped entirely and
                    // the created worktree only "appeared" once a session
                    // delete moved cwd back under a project. The create records
                    // stay selectable instead; the project itself stays unknown
                    // (the draft still names no project it was not given).
                    return@runCatching null to NewSessionWorkspaceModel.worktreeDirectories(
                        rows = emptyList(),
                        projectRoot = null,
                        created = worktreeStore.directories(),
                    )
                }
                // v67: Refresh reconciles the server's saved inventory first
                // (`POST /api/worktree/refresh`, the same write Home's project
                // row offers), then re-reads it. A plain re-read of a stale
                // inventory answers the same stale rows, which is what the
                // device run measured.
                if (reloading) runCatching { api.refreshWorktrees(picked.id) }
                picked to NewSessionWorkspaceModel.worktreeDirectories(
                    rows = api.worktrees(picked.id),
                    projectRoot = picked.canonical,
                    sandboxes = picked.sandboxes,
                    created = NewSessionWorkspaceModel.worktreeRecordsForProject(
                        created = worktreeStore.directories(),
                        projectRoot = picked.canonical,
                        projectRoots = projects.map { it.canonical },
                    ),
                )
            }.getOrNull()
        }
        project = loaded?.first
        worktrees = loaded?.second ?: worktrees
        worktreesLoaded = true
    }

    // B-5: `session.location.cycle` (`new-session/commands.tsx:46-54`) — the
    // draft route's own command, delivered through the same channel the session
    // screen's screen-local chords use. The App-level dispatcher only requests it
    // while this route is current, and the mount-time id guard keeps a request
    // that landed before the route composed from firing on entry.
    val intents = LocalSessionCommandIntents.current
    val noCommandRequest = remember { MutableStateFlow<CommandRequest?>(null) }
    val commandRequest by (intents?.commandRequest ?: noCommandRequest).collectAsState()
    var lastCommandRequestId by remember { mutableStateOf(intents?.commandRequest?.value?.id ?: 0L) }
    LaunchedEffect(commandRequest?.id) {
        val request = commandRequest ?: return@LaunchedEffect
        if (request.id <= lastCommandRequestId) return@LaunchedEffect
        lastCommandRequestId = request.id
        if (request.commandId == "session.location.cycle") {
            selected = NewSessionWorkspaceModel.cycleLocation(
                selected = selected,
                lastWorktree = lastWorktree,
                projectRoot = project?.canonical,
            )
        }
        intents?.consumeCommand(request)
    }

    // A-11/F-2: what the draft needs from the **destination** directory —
    // `GET /api/vcs` for the branch chip (`PromptGitStatus`), `GET /api/mcp` for
    // the MCP draft switches, and the provider/integration pair behind the
    // provider tip. One block, so switching worktree re-reads all of them
    // together. A failed read leaves its field null, and the UI then says so
    // instead of inventing rows.
    //
    // v64: a worktree the server has not read yet answers the all-empty
    // `{branch:{}}`, which the first read cannot tell from "not a repository"
    // ([NewSessionGitStatus.needsRecheck]) — so a destination that answers
    // nothing is asked once more after [NewSessionGitStatus.RECHECK_MS], which is
    // this shell's stand-in for upstream's `vcs.sync` refetch
    // (`controller.ts:181-186`).
    LaunchedEffect(serverStore, destination, worktreesLoaded, project?.id) {
        // Wait for the project/worktree read: until it lands, `destination` is
        // still the preselected value and the whole block would run twice.
        if (!worktreesLoaded) return@LaunchedEffect
        // The draft is per directory (upstream keys it on
        // `[server, location, worktree]`, `new-session/mcp.ts:24-31`): a new
        // destination starts from the server's own states again.
        mcpStates = emptyMap()
        destinationLoaded = false
        val server = withContext(Dispatchers.IO) {
            // Same stale-snapshot fix as above: the prefs mirror is re-read so a
            // config sync that landed after this screen composed is picked up.
            serverStore.restore()
            serverStore.server
        } ?: return@LaunchedEffect
        val api = withContext(Dispatchers.IO) {
            V2Api.forServer(server.url, server.username, server.password)
        } ?: return@LaunchedEffect
        suspend fun readVcs() = withContext(Dispatchers.IO) {
            NewSessionGitStatus.of(runCatching { api.vcs(destination) }.getOrNull(), project?.vcs)
        }
        val loaded = withContext(Dispatchers.IO) {
            val mcp = runCatching {
                NewSessionMcpDraft.servers(JSONArray(api.mcp(destination)).toString())
            }.getOrNull()
            val paid = runCatching {
                NewSessionTips.paidProviderCount(api.providers(destination), api.integrations(destination))
            }.getOrNull()
            Triple(readVcs(), mcp, paid)
        }
        val sourceBranch = withContext(Dispatchers.IO) { worktreeStore.sourceBranch(destination) }
        val vcs = if (NewSessionGitStatus.needsRecheck(loaded.first) && sourceBranch == null) {
            delay(NewSessionGitStatus.RECHECK_MS)
            readVcs()
        } else {
            loaded.first
        }
        gitInfo = vcs
        gitSourceBranch = sourceBranch
        mcpRows = loaded.second
        paidProviders = loaded.third
        destinationLoaded = true
    }

    // `ensureClient` first: a cold start can land directly on this route (route
    // restore / deep link) with no client built yet.
    //
    // `ensureClient` builds the REST client synchronously but **not** the v2
    // transport — `ensureTransport` installs it after an async generation probe,
    // and `ChatViewModel.createSession` silently returns **without invoking
    // either callback** while `requireTransport()` is still null (also after
    // `stop()` left a stale `generation == V2` over a cleared transport).
    // Calling it in that window is the measured hang: the UI sits on
    // 「Creating a session…」 until the user presses Retry once the probe has
    // landed. So gate on [NewSessionCreatePolicy] — wait out the probe, confirm
    // the transport is actually installed ([transportLive]), auto-re-issue once
    // when it is not, and never leave 「Creating」 up without either a session
    // id or a real error.
    //
    // The failure detail for a create that could not reach the server is the
    // transport's own copy ([transportFailureRes]): the gate now answers in the
    // first poll instead of at the end of the window, so what it names has to
    // be the real problem — an unreachable host, an auth wall and a server that
    // is not 2.x need three different fixes.
    fun noTransportDetail(): String = context.getString(transportFailureRes(chatViewModel.browseFailure.value))
    LaunchedEffect(attempt, creating) {
        if (!creating) return@LaunchedEffect
        slow = false
        failure = null
        failureDetail = null
        var tried = 0
        while (true) {
            tried += 1
            if (!chatViewModel.ensureClient()) {
                creating = false
                failed = true
                failure = NewSessionCreatePolicy.Failure.NOT_READY
                failureDetail = noTransportDetail()
                return@LaunchedEffect
            }
            // A settled negative verdict is not a probe to wait out: the gate
            // would otherwise sleep its whole window on a connection already
            // known to be down (the second attempt of a disconnected create was
            // exactly that — `generation` had just gone UNKNOWN).
            val generationReady = if (chatViewModel.transportSettledNegative()) {
                false
            } else {
                withTimeoutOrNull(NewSessionCreatePolicy.TRANSPORT_WAIT_MS) {
                    chatViewModel.generation.first { it == ServerGeneration.V2 }
                    true
                } ?: (chatViewModel.generation.value == ServerGeneration.V2)
            }
            if (!generationReady) {
                if (NewSessionCreatePolicy.shouldAutoRetry(tried, NewSessionCreatePolicy.Failure.NOT_READY)) {
                    delay(NewSessionCreatePolicy.RETRY_DELAY_MS)
                    continue
                }
                creating = false
                failed = true
                failure = NewSessionCreatePolicy.Failure.NOT_READY
                failureDetail = noTransportDetail()
                return@LaunchedEffect
            }
            // F-2: the MCP draft lands **before** the session exists, exactly
            // where upstream `prepare()`s it (`composer-adapter.ts:81-86`): a
            // draft that cannot be reached aborts the create instead of starting
            // a session against a server set the user did not ask for. The
            // outcome's copy is upstream's own toast title + description
            // (`new-session/mcp.ts:101-111`).
            val outcome = applyMcpDraft(serverStore, destination, mcpStates)
            if (outcome !is McpApplyOutcome.Ready) {
                creating = false
                failed = true
                failure = NewSessionCreatePolicy.Failure.ERRORED
                failureDetail = mcpOutcomeMessage(context, outcome)
                return@LaunchedEffect
            }
            when (
                val call = createSessionOnce(
                    chatViewModel,
                    destination,
                )
            ) {
                is CreateOutcome.Created -> {
                    creating = false
                    onCreated(call.id)
                    return@LaunchedEffect
                }
                is CreateOutcome.Failed -> {
                    if (NewSessionCreatePolicy.shouldAutoRetry(tried, call.failure)) {
                        delay(NewSessionCreatePolicy.RETRY_DELAY_MS)
                        continue
                    }
                    creating = false
                    failed = true
                    failure = call.failure
                    failureDetail = call.message
                        ?: if (call.failure == NewSessionCreatePolicy.Failure.NOT_READY) noTransportDetail() else null
                    return@LaunchedEffect
                }
            }
        }
    }
    LaunchedEffect(attempt, creating) {
        if (!creating) return@LaunchedEffect
        delay(SLOW_MS)
        slow = true
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(c.bgDeep)
            .windowInsetsPadding(WindowInsets.safeDrawing),
        contentAlignment = Alignment.Center,
    ) {
        // The page's explicit exit. Upstream's new-session page has **none** of
        // its own: the draft is a shell tab there (`new-session/route.tsx`
        // resolves a `DraftTab`), so leaving it means closing that tab in the
        // strip, and the page itself only pins the summary trigger into its
        // `absolute inset-x-0 top-0 h-12` band (`view.tsx:98-104`). This shell
        // has no draft tab — the route is a plain destination — so BACK was the
        // only exit and it is invisible. The Android convention for a page with
        // no visible back affordance is a leading arrow, so the page fills the
        // leading half of upstream's own top band: same 48 dp strip, same edge
        // padding, mirrored (`start` where the summary trigger is `end`). It
        // reuses the session header's back chevron (`SessionHeader` rotates the
        // same `ic_oc_chevron_down`) and its `oc_back` description, so the two
        // surfaces are one control to TalkBack.
        BackChevronButton(
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 8.dp, top = 8.dp),
        )
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            // The summary card, the tips line and the failure block can outgrow a
            // short screen; scrolling keeps every one of them reachable instead of
            // clipping the Create button's own error copy.
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.oc_new_session),
                    color = c.textMuted,
                    fontSize = 13.sp,
                )
                // `SummaryPopover` (`view.tsx:100-121`) — the page's top-right
                // affordance, holding the project card, the git status and the
                // MCP draft switches upstream mounts in `NewSessionSummary`.
                SummaryToggle(active = summaryOpen, onClick = { summaryOpen = !summaryOpen })
            }
            if (summaryOpen) {
                NewSessionSummaryCard(
                    project = project,
                    destination = destination,
                    gitKind = NewSessionGitStatus.kind(gitInfo),
                    gitBranch = NewSessionGitStatus.branch(gitInfo, gitSourceBranch),
                    gitFrom = gitInfo?.current.isNullOrBlank() && !gitSourceBranch.isNullOrBlank(),
                    mcpRows = mcpRows,
                    mcpLoaded = destinationLoaded,
                    mcpStates = mcpStates,
                    // Upstream's `controls.pending` (`mcp.ts:38-42`): while the
                    // create — and with it `apply()` — is in flight, every draft
                    // switch is read-only.
                    mcpBusy = if (creating) mcpStates.keys else emptySet(),
                    onToggleMcp = { name, enabled ->
                        mcpStates = NewSessionMcpDraft.withState(mcpStates, name, enabled)
                    },
                )
            }
            WorkspaceSelector(
                selected = selected,
                projectRoot = project?.canonical,
                worktrees = worktrees,
                loading = !worktreesLoaded,
                onSelect = { picked ->
                    selected = picked
                    // B-5: remember the pick for `session.location.cycle`
                    // (upstream's `existing()` worktree).
                    if (picked != null && !NewSessionWorkspaceModel.isLocal(picked, project?.canonical)) {
                        lastWorktree = picked
                    }
                },
                onCreateWorktree = { worktreeCreateOpen = true },
                onRefreshWorktrees = { worktreeReload += 1 },
                onDeleteWorktree = { path ->
                    worktreeError = null
                    worktreeDeleting = path
                },
            )
            // `PromptGitStatus` (`selector.tsx:374-396`) sits inline next to the
            // selector while no workspace bar is mounted — the phone never mounts
            // one, so this is its only form.
            GitStatusChip(
                kind = NewSessionGitStatus.kind(gitInfo),
                branch = NewSessionGitStatus.branch(gitInfo, gitSourceBranch),
                from = gitInfo?.current.isNullOrBlank() && !gitSourceBranch.isNullOrBlank(),
            )
            if (!creating) {
                NeutralButton(
                    label = stringResource(R.string.oc_new_session_create),
                    iconRes = R.drawable.ic_oc_send,
                    onClick = {
                        failed = false
                        creating = true
                    },
                )
            } else {
                Text(
                    text = stringResource(R.string.oc_home_creating),
                    color = c.textMuted,
                    fontSize = 13.sp,
                )
            }
            if (failed && !creating) {
                Text(
                    text = stringResource(R.string.oc_newsession_create_failed),
                    color = c.textAccent,
                    fontSize = 13.sp,
                )
                val reason = when (failure) {
                    NewSessionCreatePolicy.Failure.NOT_READY ->
                        failureDetail ?: stringResource(R.string.oc_home_no_server)

                    NewSessionCreatePolicy.Failure.TIMED_OUT ->
                        stringResource(R.string.oc_home_create_session_timeout)

                    NewSessionCreatePolicy.Failure.ERRORED ->
                        failureDetail ?: stringResource(R.string.oc_newsession_create_failed)

                    null -> null
                }
                if (reason != null) {
                    Text(
                        text = reason,
                        color = c.textMuted,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                    )
                }
            }
            if (configMissing) {
                Text(
                    text = stringResource(R.string.oc_setup_hint),
                    color = c.textMuted,
                    fontSize = 12.sp,
                )
            }
            if (configMissing || (creating && slow)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    NeutralButton(
                        label = stringResource(R.string.retry),
                        iconRes = R.drawable.ic_oc_edit,
                        onClick = {
                            creating = true
                            attempt += 1
                        },
                    )
                    Text(
                        text = stringResource(R.string.oc_back),
                        color = c.textMuted,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .clip(OcTheme.shapes.md)
                            .clickable(role = Role.Button) {
                                creating = false
                                onBack()
                            }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
            // `NewSessionTips` (`view.tsx:167-200`): at most one tip, the provider
            // one first, each dismissible for 30 days. The workspace tip stays
            // ineligible: upstream's gate is `project.managed() === 0` — no
            // worktree the *server's own strategy* created — and this shell's
            // worktree read (`NewSessionWorkspaceModel.worktreeDirectories`)
            // drops the rows' `strategy`, so that count cannot be decided here.
            // The create it would offer is reachable from the selector's own
            // stop either way.
            NewSessionTipRow(
                providerEligible = paidProviders == 0,
                workspaceEligible = false,
                providerDismissedAt = providerTipDismissedAt,
                workspaceDismissedAt = workspaceTipDismissedAt,
                onProviderAction = onOpenProviderSettings,
                onDismiss = { tip ->
                    val now = System.currentTimeMillis()
                    when (tip) {
                        NewSessionTips.Tip.PROVIDER -> {
                            providerTipDismissedAt = now
                            tipStore.dismiss(NewSessionTipStore.PROVIDER, now)
                        }

                        NewSessionTips.Tip.WORKSPACE -> {
                            workspaceTipDismissedAt = now
                            tipStore.dismiss(NewSessionTipStore.WORKSPACE, now)
                        }
                    }
                },
            )
        }
    }
    if (worktreeCreateOpen) {
        // The create stop's picker (`selector.tsx:257-360` + `create.ts`). The
        // created directory joins the menu **and** becomes the selection, so the
        // next tap on 「Create session」 drafts inside the fresh worktree —
        // upstream's `select("create")` → `onCreate(branch)` → the destination
        // resolves to the created path at submit (`composer-adapter.ts:200-216`).
        WorktreeCreateDialog(
            sourceDirectory = destination,
            serverStore = serverStore,
            onCreated = { created ->
                worktreeCreateOpen = false
                worktrees = (worktrees + created).distinct()
                selected = created
            },
            // Both halves of the worktree create land here: the dialog's own
            // `onCreated` (select the fresh worktree) *and* the create record.
            // Calling this screen's `onCreated` — the session-created callback
            // `App.kt` navigates with — used to hand a **directory** to the
            // session route, and `NavRestore.session` built
            // `server/<key>/session//home/…` out of it: the v65 P0 crash
            // (emulator-5556, research/v65_verify/5556/41-42).
            onCreatedFrom = { created, branch ->
                worktreeStore.record(created, branch)
                worktreeCreateOpen = false
                worktrees = (worktrees + created).distinct()
                selected = created
                // v68: invalidate behind the immediate add — reconcile the
                // server inventory first, then re-read with the record merged
                // in, so the fresh worktree survives the next read instead of
                // living only in this composition's memory.
                worktreeReload += 1
            },
            onDismiss = { worktreeCreateOpen = false },
        )
    }
    // v67: the worktree list's delete confirm. Same write the Settings →
    // Worktrees page offers (`DELETE /api/worktree` via
    // [removeWorktree]), reachable where the list is: a worktree created here
    // could not be removed from here at all before.
    worktreeDeleting?.let { target ->
        WorktreeDeleteConfirmDialog(
            directory = target,
            error = worktreeError,
            onConfirm = {
                // v69: the draft's own project is a *hint* only — the list
                // merges another project's rows, and a draft parked in a
                // worktree owns no project at all. The write resolves the
                // owner itself (`worktreeOwnerProjectID`), so a row this
                // device's list borrowed is deleted by the project that
                // actually holds it, and the one case the delete cannot be
                // sent for is named instead of repeated back as a path.
                worktreeDeleting = null
                worktreeError = null
                worktreeScope.launch {
                    val result = withContext(Dispatchers.IO) {
                        removeWorktree(serverStore, project?.id, target)
                    }
                    when (result) {
                        is WorktreeRemoveOutcome.Removed -> {
                            // The row goes the moment the server confirms, and
                            // the create record with it — otherwise the merge
                            // back would put the deleted worktree back in the
                            // list on the next read.
                            worktreeStore.forget(result.directory)
                            worktrees = NewSessionWorkspaceModel.withoutWorktree(worktrees, result.directory)
                            if (NewSessionWorkspaceModel.sameDirectory(selected, result.directory)) selected = ""
                        }
                        // The dialog comes back with the reason — the server's
                        // own sentence when it sent one; the row is untouched,
                        // so the delete is retryable.
                        is WorktreeRemoveOutcome.Failed,
                        WorktreeRemoveOutcome.NoProject -> {
                            worktreeError = WorktreeRemoveModel.detail(result, noProjectDetail, requestFailedDetail)
                            worktreeDeleting = target
                        }
                    }
                }
            },
            onDismiss = {
                worktreeDeleting = null
                worktreeError = null
            },
        )
    }
    // System back always leaves the draft (the nav graph would pop anyway; this
    // makes the intent explicit and keeps Home's BackHandler ordering intact).
    BackHandler { onBack() }
}

/**
 * The page's leading back affordance: the session header's back chevron
 * (`SessionHeader` draws the same `ic_oc_chevron_down` rotated 90°, with the
 * same `oc_back` description and a `Role.Button` click target carrying it).
 *
 * `clickable(role = Role.Button)` + the description on the clickable node — not
 * on the `Icon` inside — is the `SessionHeader.HeaderIconButton` contract: only
 * the node with ACTION_CLICK is announced as a button, and a bare 40 dp Canvas
 * would otherwise be an unlabelled tap target for TalkBack and for a QA pass
 * reading the accessibility tree.
 */
@Composable
private fun BackChevronButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = OcTheme.colors
    val backLabel = stringResource(R.string.oc_back)
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = backLabel },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_oc_chevron_down),
            contentDescription = null,
            tint = c.iconBase,
            modifier = Modifier.rotate(90f).size(16.dp),
        )
    }
}

/**
 * The summary trigger (`SummaryPopover`, `new-session/view.tsx:100-121`): the
 * same `ic_oc_grid` glyph and accent-on-open state the session header uses for
 * `session.summary.toggle` (`header/SessionHeader.kt:242-249`).
 */
@Composable
private fun SummaryToggle(active: Boolean, onClick: () -> Unit) {
    val c = OcTheme.colors
    // Hoisted: `semantics {}` is not a composable scope. The label rides the
    // clickable Row, not the `Icon` inside it — only the node carrying
    // ACTION_CLICK is announced as a button.
    val summaryLabel = stringResource(R.string.oc_command_summary_toggle)
    Row(
        modifier = Modifier
            .height(28.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (active) c.layer02 else c.bgDeep)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = summaryLabel }
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_oc_grid),
            contentDescription = null,
            tint = if (active) c.iconAccent else c.iconMuted,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = summaryLabel,
            color = if (active) c.textBase else c.textMuted,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            maxLines = 1,
        )
    }
}

/**
 * `NewSessionSummary` (`new-session/summary.tsx:12-64`) as one card: the project
 * (`ProjectSummaryCard`, name + directory), the git status row the summary's
 * workspace selector stands for, and the server panel's MCP section
 * (`server-panel.tsx:104-270`) with the draft switches.
 *
 * The switches are **draft only** — nothing is written until Create
 * (`new-session/mcp.ts` `capture()`/`apply()`), which is why the section carries
 * upstream's `session.summary.mcp.onCreation` hint.
 */
@Composable
private fun NewSessionSummaryCard(
    project: NewSessionWorkspaceModel.ProjectRef?,
    destination: String?,
    gitKind: NewSessionGitStatus.Kind,
    gitBranch: String?,
    gitFrom: Boolean,
    mcpRows: List<McpPageRow>?,
    mcpLoaded: Boolean,
    mcpStates: Map<String, Boolean>,
    mcpBusy: Set<String>,
    onToggleMcp: (String, Boolean) -> Unit,
) {
    val c = OcTheme.colors
    val shape = RoundedCornerShape(6.dp)
    Column(
        modifier = Modifier
            .widthIn(max = 360.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(c.bgBase)
            .border(0.5.dp, c.borderMuted, RoundedCornerShape(12.dp))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // ------------------------------------------------ project card
        Column(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(c.layer01)
                .padding(2.dp),
        ) {
            SummaryLine(
                iconRes = R.drawable.ic_oc_folder,
                label = project?.let { NewSessionWorkspaceModel.worktreeName(it.canonical) }
                    ?: stringResource(R.string.oc_summary_project_title),
                value = project?.canonical,
                mono = true,
            )
            SummaryLine(
                iconRes = R.drawable.ic_oc_folder,
                label = stringResource(R.string.oc_summary_workspace),
                value = destination,
                mono = true,
            )
            SummaryLine(
                iconRes = R.drawable.ic_oc_code,
                label = stringResource(R.string.oc_summary_branch),
                value = when (gitKind) {
                    NewSessionGitStatus.Kind.BRANCH -> gitBranch
                    NewSessionGitStatus.Kind.DETACHED -> gitStatusDetachedLabel(gitBranch, gitFrom)
                    NewSessionGitStatus.Kind.NO_GIT -> stringResource(R.string.oc_new_session_git_none)
                    NewSessionGitStatus.Kind.NONE -> null
                },
                mono = true,
            )
        }

        // ------------------------------------------------- server card
        Column(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(c.layer01)
                .padding(2.dp),
        ) {
            Text(
                text = stringResource(R.string.oc_summary_mcp_title),
                color = c.textMuted,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight(530),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
            if (mcpLoaded && mcpRows.isNullOrEmpty()) {
                Text(
                    text = stringResource(R.string.oc_summary_mcp_empty),
                    color = c.textMuted,
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
            mcpRows.orEmpty().forEach { row ->
                McpDraftRow(
                    row = row,
                    enabled = NewSessionMcpDraft.isEnabled(mcpStates, row.name, row.status),
                    busy = row.name in mcpBusy || row.status.equals("pending", ignoreCase = true),
                    onToggle = { onToggleMcp(row.name, it) },
                )
            }
            if (!mcpRows.isNullOrEmpty()) {
                Text(
                    // Upstream's `session.summary.mcp.onCreation`, reworded: the
                    // phone creates no worktree, the session **is** the creation
                    // the draft lands with.
                    text = stringResource(R.string.oc_new_session_mcp_on_creation),
                    color = c.textFaint,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        }
    }
}

/** One `session-summary-row`: leading icon, muted label, value. */
@Composable
private fun SummaryLine(
    iconRes: Int,
    label: String,
    value: String?,
    mono: Boolean,
) {
    val c = OcTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 32.dp)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = c.iconMuted,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = label,
            color = c.textMuted,
            fontSize = 13.sp,
            lineHeight = 20.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = value.orEmpty(),
            color = c.textBase,
            fontSize = 13.sp,
            lineHeight = 20.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontFamily = if (mono) OcTheme.monoFont else null,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * One MCP draft row (`server-panel.tsx:237-265`): the server's name, its status
 * label (`failed` / `connecting` / `sign in required`) and the draft `Switch`.
 * The status text is the **server's** state while the Switch is the **draft** —
 * they differ until Create applies it, exactly like upstream's preview mode.
 */
@Composable
private fun McpDraftRow(
    row: McpPageRow,
    enabled: Boolean,
    busy: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val c = OcTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = row.name,
            color = c.textBase,
            fontSize = 13.sp,
            lineHeight = 20.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        val statusLabel = when {
            row.failed && !row.error.isNullOrBlank() -> row.error
            row.status.equals("failed", ignoreCase = true) -> stringResource(R.string.oc_summary_status_failed)
            row.status.equals("pending", ignoreCase = true) -> stringResource(R.string.oc_summary_status_connecting)
            row.status.equals("needs_auth", ignoreCase = true) -> stringResource(R.string.oc_summary_status_needs_auth)
            else -> null
        }
        if (statusLabel != null) {
            Text(
                text = statusLabel,
                color = c.textMuted,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        OcSwitch(checked = enabled, enabled = !busy, onCheckedChange = onToggle)
    }
}

/**
 * The chip's [NewSessionGitStatus.Kind.DETACHED] copy. A worktree is created
 * detached at its source branch's tip (`core/src/git.ts:654-658`), so with the
 * create record the chip repeats upstream's create-stop wording
 * (`session.new.workspace.fromBranch`, `selector.tsx:290`); without one — a
 * worktree made on another device, or before this record existed — it names the
 * state instead of inventing a branch.
 */
@Composable
private fun gitStatusDetachedLabel(branch: String?, from: Boolean): String =
    if (from && !branch.isNullOrBlank()) {
        stringResource(R.string.oc_new_session_git_from_branch, branch)
    } else {
        stringResource(R.string.oc_new_session_git_detached)
    }

/**
 * `PromptGitStatus` (`new-session/workspace/selector.tsx:374-396`) as the phone's
 * inline chip: upstream's `bg-layer-02` rounded-full pill with the branch icon,
 * 「No Git」 for a directory that is not a repository, and — where upstream
 * renders nothing at all — this shell's named detached state (see
 * [NewSessionGitStatus]).
 */
@Composable
private fun GitStatusChip(kind: NewSessionGitStatus.Kind, branch: String?, from: Boolean) {
    val c = OcTheme.colors
    val label = when (kind) {
        NewSessionGitStatus.Kind.BRANCH -> branch
        NewSessionGitStatus.Kind.DETACHED -> gitStatusDetachedLabel(branch, from)
        NewSessionGitStatus.Kind.NO_GIT -> stringResource(R.string.oc_new_session_git_none)
        NewSessionGitStatus.Kind.NONE -> null
    } ?: return
    Row(
        modifier = Modifier
            .heightIn(min = 24.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(c.layer02)
            .padding(horizontal = 10.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_oc_code),
            contentDescription = null,
            tint = c.iconMuted,
            modifier = Modifier.size(12.dp),
        )
        Text(
            text = label,
            color = c.textFaint,
            fontSize = 13.sp,
            lineHeight = 20.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * `NewSessionTips` (`view.tsx:167-200`): one line at the bottom of the draft —
 * the tip's copy, an optional action, and upstream's dismiss button. The
 * provider tip's action opens the provider catalog; without a host
 * (`onProviderAction == null`) the line stays informational and only the dismiss
 * half works.
 */
@Composable
private fun NewSessionTipRow(
    providerEligible: Boolean,
    workspaceEligible: Boolean,
    providerDismissedAt: Long,
    workspaceDismissedAt: Long,
    onProviderAction: (() -> Unit)?,
    onDismiss: (NewSessionTips.Tip) -> Unit,
) {
    val c = OcTheme.colors
    val now = remember(providerEligible, workspaceEligible, providerDismissedAt, workspaceDismissedAt) {
        System.currentTimeMillis()
    }
    val tip = NewSessionTips.which(
        providerEligible = providerEligible,
        workspaceEligible = workspaceEligible,
        providerDismissedAt = providerDismissedAt,
        workspaceDismissedAt = workspaceDismissedAt,
        now = now,
    ) ?: return
    Row(
        modifier = Modifier
            .widthIn(max = 360.dp)
            .clip(RoundedCornerShape(4.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(
                if (tip == NewSessionTips.Tip.WORKSPACE) R.string.oc_home_workspace_tip else R.string.oc_home_provider_tip,
            ),
            color = c.textFaint,
            fontSize = 13.sp,
            lineHeight = 20.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f, fill = false)
                .then(
                    if (tip == NewSessionTips.Tip.PROVIDER && onProviderAction != null) {
                        Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .clickable(role = Role.Button, onClick = onProviderAction)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    } else {
                        Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    },
                ),
        )
        Text(
            text = stringResource(R.string.common_dismiss),
            color = c.textMuted,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            maxLines = 1,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .clickable(role = Role.Button) { onDismiss(tip) }
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/**
 * The `PromptWorkspaceSelector` trigger + menu (`selector.tsx`), flattened to
 * one dropdown (Compose has no nested submenu): the `Run session in` group with
 * the `Local` stop and the **create** stop (`session.new.worktree.create`,
 * `selector.tsx:152-163`), then the worktree list with upstream's ≥10 search
 * field and empty lines. Upstream's sprite glyphs `monitor` /
 * `outline-worktree` are not in this shell's icon port, so the stops use the
 * folder glyph, the worktree rows upstream's accent tint, and the create stop
 * the plus glyph upstream gives it (`selector.tsx:154` `Icon name="plus"`).
 *
 * The create stop's own branch list (`selector.tsx:257-360`) is not a second
 * level of this menu: it is the picker inside [WorktreeCreateDialog], which
 * `onCreateWorktree` opens.
 */
@Composable
private fun WorkspaceSelector(
    selected: String?,
    projectRoot: String?,
    worktrees: List<String>,
    loading: Boolean,
    onSelect: (String) -> Unit,
    onCreateWorktree: () -> Unit,
    /** v67: re-read the server's worktree inventory (and reconcile it first). */
    onRefreshWorktrees: () -> Unit,
    /** v67: the per-row delete — the list had no way to remove a worktree. */
    onDeleteWorktree: (String) -> Unit,
) {
    val c = OcTheme.colors
    var expanded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val local = NewSessionWorkspaceModel.isLocal(selected, projectRoot)
    val label = when {
        local -> stringResource(R.string.oc_new_session_workspace_trigger_local)
        else -> NewSessionWorkspaceModel.worktreeName(selected.orEmpty())
    }
    // Hoisted: `semantics {}` is not a composable scope. The label rides the
    // clickable Row, not the chevron `Icon` inside it.
    val pickerLabel = stringResource(R.string.oc_new_session_workspace_tooltip)
    Box {
        Row(
            modifier = Modifier
                .height(32.dp)
                .widthIn(max = 240.dp)
                .clip(RoundedCornerShape(4.dp))
                .clickable(role = Role.Button) {
                    query = ""
                    expanded = true
                }
                .semantics { contentDescription = pickerLabel }
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_oc_folder),
                contentDescription = null,
                tint = if (local) c.iconMuted else c.iconAccent,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = label,
                color = c.textBase,
                fontSize = 13.sp,
                fontWeight = FontWeight(440),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                painter = painterResource(R.drawable.ic_oc_chevron_down),
                contentDescription = null,
                tint = c.iconMuted,
                modifier = Modifier.size(12.dp),
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            // M3's default menu surface (`surfaceContainer`) is a light
            // translucent tint the shell's palette never re-themes, so the rows'
            // `textBase`/`textFaint` landed on a near-white panel in the dark
            // theme. The opaque layer token + zero tonal elevation is the pairing
            // every other shell menu uses (`SessionContextPanel`,
            // `SettingsComponents`), and the hairline keeps the edge readable
            // against `bgDeep`.
            shape = RoundedCornerShape(6.dp),
            containerColor = c.layer01,
            tonalElevation = 0.dp,
            border = BorderStroke(0.5.dp, c.borderMuted),
            // v58: ESC 关不掉这个菜单（`research/v57_verify/emu5556`）——
            // `DropdownMenu` 的 `Popup` 默认不是 focusable 的，窗口里没有焦点归属，
            // ESC 进得去、没人接得住（点外面正常关：外面那一击由 `PopupLayout` 自己
            // 判定，与焦点无关）。focusable 打开之后焦点归属才存在，
            // `ocOverlayEscape` 才能在菜单根节点上接到 ESC。
            //
            // v61 真机：v58 这版在**手机**上没生效 —— `DropdownMenu` 的 `modifier`
            // 挂在宿主树上一个零尺寸 `Box` 上（不在 `Popup` 里），ESC 只到宿主窗口，
            // 于是走宿主窗口上的 `OcEscapeRouter`（见 `OcPopoverDismiss.kt`），
            // `open` 门控住菜单关着时那个 Box 也在组合里的那一份。
            properties = PopupProperties(
                focusable = true,
                dismissOnBackPress = true,
                dismissOnClickOutside = true,
            ),
            modifier = Modifier.ocOverlayEscape(open = expanded) { expanded = false },
        ) {
            Column(Modifier.width(200.dp)) {
                MenuGroupLabel(R.string.oc_new_session_workspace_run_in)
                WorkspaceMenuRow(
                    label = stringResource(R.string.oc_new_session_workspace_local),
                    checked = local,
                    accent = false,
                    onClick = {
                        expanded = false
                        onSelect("")
                    },
                )
                WorkspaceMenuRow(
                    // Upstream's `workspace.new` / `session.new.worktree.create`
                    // stop: this shell creates the worktree right away (there is
                    // no draft to defer it to), so the row opens the branch
                    // picker instead of arming a submit.
                    label = stringResource(R.string.session_new_worktree_create),
                    checked = false,
                    accent = true,
                    iconRes = R.drawable.ic_oc_plus,
                    onClick = {
                        expanded = false
                        onCreateWorktree()
                    },
                )
                HorizontalDivider(thickness = 0.5.dp, color = c.borderMuted)
                MenuGroupLabel(R.string.oc_settings_tab_worktrees)
                if (NewSessionWorkspaceModel.showsWorktreeSearch(worktrees.size)) {
                    WorktreeSearchField(query) { query = it }
                }
                when {
                    loading -> MenuEmptyLine(R.string.oc_home_projects_loading)
                    worktrees.isEmpty() -> MenuEmptyLine(R.string.oc_new_session_workspace_empty)
                    else -> {
                        val shown = NewSessionWorkspaceModel.filterWorktrees(worktrees, query)
                        if (shown.isEmpty()) {
                            MenuEmptyLine(R.string.oc_new_session_workspace_search_empty)
                        } else {
                            shown.forEach { path ->
                                WorkspaceMenuRow(
                                    label = NewSessionWorkspaceModel.worktreeName(path),
                                    checked = !local && NewSessionWorkspaceModel.sameDirectory(selected, path),
                                    accent = true,
                                    onClick = {
                                        expanded = false
                                        onSelect(path)
                                    },
                                    onDelete = {
                                        expanded = false
                                        onDeleteWorktree(path)
                                    },
                                )
                            }
                        }
                    }
                }
                if (!loading) {
                    // v67: the refresh Home's project row offers
                    // (`POST /api/worktree/refresh` + a re-read), in the one
                    // place a worktree is chosen from — and reachable when the
                    // list is empty, which is exactly when it is needed.
                    WorkspaceMenuRow(
                        label = stringResource(R.string.oc_home_project_refresh_worktrees),
                        checked = false,
                        accent = false,
                        iconRes = R.drawable.ic_oc_edit,
                        onClick = {
                            expanded = false
                            onRefreshWorktrees()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun MenuGroupLabel(resId: Int) {
    val c = OcTheme.colors
    Text(
        text = stringResource(resId),
        color = c.textMuted,
        fontSize = 11.sp,
        fontWeight = FontWeight(530),
        lineHeight = 16.sp,
        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 2.dp),
    )
}

/** The worktree row's ×: `icon-button-v2[size=large]`'s 16 dp glyph box. */
internal val WORKSPACE_DELETE_GLYPH: Dp = 16.dp

/**
 * v71: the worktree row's trailing inset. Upstream's `padding-inline-end: 6px` is a
 * mouse-sized inset, and the row's box ends where the menu's own surface does — so
 * the ×'s 48 dp finger ring (`ocMinTouchTarget`'s default,
 * [touchTargetRingOverflow] past the glyph box on each side) used to hang half
 * outside the popup. Those taps land in the scrim, where the menu only closes: the
 * delete confirm never opens (`research/v71_verify/MEASUREMENTS.md`, density 160 —
 * x=803..839 on the row opened the confirm, x=841+ only closed the menu). The row
 * reserves the whole overflow, so the finger ends at the surface's edge instead of
 * past it.
 */
internal val WORKSPACE_ROW_END: Dp = touchTargetRingOverflow(WORKSPACE_DELETE_GLYPH)

@Composable
private fun WorkspaceMenuRow(
    label: String,
    checked: Boolean,
    accent: Boolean,
    onClick: () -> Unit,
    /** The row's glyph; the worktree stops use the folder, the create stop the plus. */
    iconRes: Int = R.drawable.ic_oc_folder,
    /** v67: the trailing delete entry a worktree row carries (null: no delete). */
    onDelete: (() -> Unit)? = null,
) {
    val c = OcTheme.colors
    val deleteLabel = stringResource(R.string.oc_new_session_worktree_delete)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 28.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = 12.dp, end = WORKSPACE_ROW_END, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = if (accent) c.iconAccent else c.iconMuted,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = label,
            color = c.textBase,
            fontSize = 13.sp,
            fontWeight = FontWeight(440),
            lineHeight = 20.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (checked) {
            Icon(
                painter = painterResource(R.drawable.ic_oc_check),
                contentDescription = null,
                tint = c.iconBase,
                modifier = Modifier.size(12.dp),
            )
        }
        if (onDelete != null) {
            // A node of its own, not a second target on the row: the row selects the
            // worktree, this removes it. It stays *inside* the row's clickable
            // content — the deeper node wins the gesture (measured on 5556:
            // x=803..839 opens the confirm even though the row's own clickable
            // covers them), where a sibling of the row would lose to the row's
            // direct hit. The label rides the clickable node so the entry is
            // announced as a button (same contract as `GhostNavButton`).
            Box(
                modifier = Modifier
                    .ocMinTouchTarget(visual = WORKSPACE_DELETE_GLYPH)
                    .clip(OcTheme.shapes.md)
                    .clickable(role = Role.Button) { onDelete() }
                    .semantics { contentDescription = deleteLabel },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_oc_close),
                    contentDescription = null,
                    tint = c.iconMuted,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

@Composable
private fun MenuEmptyLine(resId: Int) {
    val c = OcTheme.colors
    Text(
        text = stringResource(resId),
        color = c.textMuted,
        fontSize = 13.sp,
        lineHeight = 20.sp,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

/**
 * The worktree row's delete confirm (v67) — upstream's
 * `DialogDeleteWorkspace` body (`settings/workspaces/workspaces.tsx:180-238`),
 * reached from the new-session worktree list instead of only from the Settings
 * page. The write is [removeWorktree] (`DELETE /api/worktree`), so a refused
 * delete says why and leaves the row alone.
 */
@Composable
private fun WorktreeDeleteConfirmDialog(
    directory: String,
    error: String?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = OcTheme.colors
    val scrim = rememberDialogScrimState()
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(Modifier.fillMaxSize()) {
            DialogScrim(scrim, onDismiss = onDismiss)
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val shape = OcTheme.shapes.xl
                Column(
                    modifier = Modifier
                        .padding(horizontal = 32.dp)
                        .fillMaxWidth()
                        .widthIn(max = 360.dp)
                        .clip(shape)
                        .background(c.bgBase)
                        .border(0.5.dp, c.borderBase, shape)
                        .ocShadow(OcTheme.elevation.floating, shape)
                        .dialogCardBounds(scrim)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = stringResource(R.string.oc_new_session_worktree_delete_title),
                        color = c.textBase,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        lineHeight = 21.sp,
                    )
                    Text(
                        text = stringResource(
                            R.string.oc_new_session_worktree_delete_message,
                            NewSessionWorkspaceModel.worktreeName(directory),
                        ),
                        color = c.textMuted,
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                    )
                    if (!error.isNullOrBlank()) {
                        Text(
                            text = stringResource(
                                R.string.oc_new_session_worktree_delete_failed,
                                error,
                            ),
                            color = c.textMuted,
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                    ) {
                        NeutralButton(
                            label = stringResource(R.string.oc_common_cancel),
                            iconRes = R.drawable.ic_oc_close,
                            onClick = onDismiss,
                        )
                        NeutralButton(
                            label = stringResource(R.string.oc_new_session_worktree_delete),
                            iconRes = R.drawable.ic_oc_close,
                            onClick = onConfirm,
                        )
                    }
                }
            }
        }
    }
}

/** `selector.tsx`'s inline worktree search (magnifier + bare input). */
@Composable
private fun WorktreeSearchField(value: String, onValueChange: (String) -> Unit) {
    val c = OcTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_oc_search),
            contentDescription = null,
            tint = c.iconMuted,
            modifier = Modifier.size(12.dp),
        )
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text(
                    text = stringResource(R.string.oc_new_session_workspace_search_placeholder),
                    color = c.textFaint,
                    fontSize = 13.sp,
                    maxLines = 1,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(
                    color = c.textBase,
                    fontSize = 13.sp,
                    fontWeight = FontWeight(440),
                    lineHeight = 20.sp,
                    fontFamily = FontFamily.SansSerif,
                ),
                cursorBrush = SolidColor(c.textAccent),
                // Workspace / branch names are ASCII identifiers; autocorrect
                // rewrote the filter as it was typed.
                keyboardOptions = OcKeyboard.identifier(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** After this long without a session, the draft offers Retry/Back. */
private const val SLOW_MS = 3_000L
private const val SERVER_CWD_TIMEOUT_MS = 3_000L

/**
 * Bounds and retry rules for 「Create session」. Pure so the policy is testable
 * without a `ChatViewModel` or a clock.
 */
object NewSessionCreatePolicy {

    /** Attempts per one user activation; the second is the automatic re-issue. */
    const val MAX_ATTEMPTS = 2

    /**
     * Ceiling on waiting for the v2 transport (`ChatViewModel.generation`) before
     * giving up on Create. The probe is one `GET /api/info`; anything longer means
     * the server is not answering like a v2 server, and a create issued now would
     * be dropped without a callback anyway.
     */
    const val TRANSPORT_WAIT_MS = 8_000L

    /** Ceiling on polling [transportLive] after the generation verdict lands. */
    const val TRANSPORT_LIVE_WAIT_MS = 8_000L

    /** Gap between [transportLive] polls while the probe is still installing. */
    const val TRANSPORT_POLL_MS = 250L

    /**
     * Ceiling on one `createSession` call. Above the client's 30 s OkHttp read
     * timeout so a slow-but-alive create reports its own `onError` first — this
     * is only the backstop for a lost callback.
     */
    const val CREATE_TIMEOUT_MS = 45_000L

    /** Pause before the automatic re-issue. */
    const val RETRY_DELAY_MS = 500L

    enum class Failure { NOT_READY, TIMED_OUT, ERRORED }

    /**
     * Whether an attempt that ended without a session id is re-issued on its
     * own. Only [Failure.NOT_READY] qualifies: `createSession` was never
     * launched (its silent early-out), so re-issuing cannot double-create. A
     * timed-out create may still be in flight and an `onError` after
     * `transport.createSession` returned may still have created a session —
     * both surface to the user instead of risking a second one.
     */
    fun shouldAutoRetry(attempt: Int, failure: Failure): Boolean =
        failure == Failure.NOT_READY && attempt < MAX_ATTEMPTS

    /**
     * Whether the wait for the transport should give up **before** its window
     * elapses: the probe has settled a negative verdict
     * ([ChatViewModel.transportSettledNegative]) and no transport exists, so
     * polling cannot install one — only a new probe could, and the verdict is
     * what says there is nothing to wait for.
     *
     * This is the measured device hang: with `adb reverse` down, the transport
     * was missing but the *stale* generation verdict still read V2, so the gate
     * above passed instantly and the poll below spun out its whole window
     * before answering 「Not connected to a server」. A settled negative verdict
     * now answers the same copy in the first poll.
     */
    fun shouldAbandonTransportWait(transportLive: Boolean, verdictSettledNegative: Boolean): Boolean =
        !transportLive && verdictSettledNegative
}

/** What one bounded `ChatViewModel.createSession` call ended with. */
private sealed interface CreateOutcome {
    data class Created(val id: String) : CreateOutcome
    data class Failed(val failure: NewSessionCreatePolicy.Failure, val message: String?) : CreateOutcome
}

/**
 * The transport's own copy for a create that could not reach the server — the
 * split the directory picker already makes (`pickerFailureRes`), minus its
 * directory-API wording, because a create fails for the connection reasons
 * alone.
 *
 * [TransportFailure.NotConnected] is the probe's "the host answered, but it is
 * not a 2.x server" verdict, not a dead socket: rendering it as 「not
 * connected」 sends the user to fix a connection that is measurably fine, which
 * is exactly what the picker's own split was written to stop. `ProbePending`
 * keeps the connecting copy, so a create that raced the probe says so.
 */
@StringRes
private fun transportFailureRes(failure: TransportFailure?): Int = when (failure) {
    is TransportFailure.AuthRequired -> R.string.oc_server_error_auth
    is TransportFailure.Unreachable -> R.string.oc_server_error_connect
    is TransportFailure.ProbePending -> R.string.oc_summary_status_connecting
    else -> R.string.oc_home_no_server
}

/**
 * `ChatViewModel.createSession` returns without invoking either callback while
 * `requireTransport()` is null. [ChatViewModel.listProjects] is the public
 * liveness probe for that: its only **synchronous** callback is the same
 * no-transport early-out (the live path always hops through `scope.launch`).
 * The `onResult` argument is named so this probe survives parameter reordering.
 */
private fun transportLive(chatViewModel: ChatViewModel): Boolean {
    var entering = true
    var dropped = false
    chatViewModel.listProjects(onResult = { if (entering) dropped = true })
    entering = false
    return !dropped
}

private suspend fun awaitTransportLive(chatViewModel: ChatViewModel, timeoutMs: Long): Boolean {
    // Kick the probe before polling: `maybeReprobe` refuses to re-probe while a
    // *definitive* verdict is latched (the V2 verdict `stop()` leaves over a
    // cleared transport is exactly that), so without this the loop below waits
    // on a transport nobody is installing — the measured 8 s of
    // 「Creating a session…」.
    if (!transportLive(chatViewModel)) chatViewModel.retryProbe()
    return withTimeoutOrNull(timeoutMs) {
        while (!transportLive(chatViewModel)) {
            // A settled negative verdict cannot be waited out: answer now
            // instead of holding the label for the rest of the window.
            if (
                NewSessionCreatePolicy.shouldAbandonTransportWait(
                    transportLive = false,
                    verdictSettledNegative = chatViewModel.transportSettledNegative(),
                )
            ) {
                return@withTimeoutOrNull false
            }
            delay(NewSessionCreatePolicy.TRANSPORT_POLL_MS)
        }
        true
    } ?: false
}

/** One create attempt: `NOT_READY` when the transport never installed, else the call's own outcome. */
private suspend fun createSessionOnce(chatViewModel: ChatViewModel, directory: String?): CreateOutcome {
    if (!awaitTransportLive(chatViewModel, NewSessionCreatePolicy.TRANSPORT_LIVE_WAIT_MS)) {
        return CreateOutcome.Failed(NewSessionCreatePolicy.Failure.NOT_READY, null)
    }
    // `CompletableDeferred`, not `suspendCancellableCoroutine`: a `createSession`
    // callback that lands after the timeout is absorbed by `complete`'s
    // idempotence instead of resuming a cancelled continuation.
    val call = CompletableDeferred<CreateOutcome>()
    chatViewModel.createSession(
        directory = directory,
        onError = { message ->
            call.complete(CreateOutcome.Failed(NewSessionCreatePolicy.Failure.ERRORED, message))
        },
        onReady = { id -> call.complete(CreateOutcome.Created(id)) },
    )
    return withTimeoutOrNull(NewSessionCreatePolicy.CREATE_TIMEOUT_MS) { call.await() }
        ?: CreateOutcome.Failed(NewSessionCreatePolicy.Failure.TIMED_OUT, null)
}

/**
 * `apply()` (`new-session/mcp.ts:57-90`) against the destination directory:
 * read `GET /api/mcp`, reject an enabled name the directory does not offer,
 * connect/disconnect everything that differs, then re-read and report the first
 * override that did not hold. An empty draft short-circuits without a single
 * request (`mcp.ts:97` `if (!Object.keys(states).length) return true`).
 *
 * The routes are the same pair the summary card's switches use
 * ([mcpConnect] / [mcpDisconnect]), carrying the destination as
 * `location[directory]` like upstream's `toggle.mutateAsync({…, directory})`.
 */
private suspend fun applyMcpDraft(
    serverStore: ServerConfigStore,
    directory: String?,
    states: Map<String, Boolean>,
): McpApplyOutcome = withContext(Dispatchers.IO) {
    if (states.isEmpty()) return@withContext McpApplyOutcome.Ready
    serverStore.restore()
    val server = serverStore.server ?: return@withContext McpApplyOutcome.Failed(null)
    val api = V2Api.forServer(server.url, server.username, server.password)
        ?: return@withContext McpApplyOutcome.Failed(null)
    val rows = runCatching { NewSessionMcpDraft.servers(JSONArray(api.mcp(directory)).toString()) }
        .getOrElse { return@withContext McpApplyOutcome.Failed(it.message) }
    NewSessionMcpDraft.unavailable(rows, states)?.let { return@withContext McpApplyOutcome.Unavailable(it) }
    for (write in NewSessionMcpDraft.writes(rows, states)) {
        val error = runCatching {
            if (write.enabled) api.mcpConnect(write.name, directory) else api.mcpDisconnect(write.name, directory)
        }.exceptionOrNull()
        if (error != null) return@withContext McpApplyOutcome.Failed(error.message)
    }
    val after = runCatching { NewSessionMcpDraft.servers(JSONArray(api.mcp(directory)).toString()) }
        .getOrElse { return@withContext McpApplyOutcome.Failed(it.message) }
    val unresolved = NewSessionMcpDraft.unresolved(after, states) ?: return@withContext McpApplyOutcome.Ready
    if (unresolved.needsAuth) McpApplyOutcome.NeedsAuth(unresolved.name) else McpApplyOutcome.NotReady(unresolved.name)
}

/**
 * Upstream's prepare-failure toast (`new-session/mcp.ts:101-111`): the
 * `session.summary.mcp.prepareFailed` title plus the outcome's own message —
 * `session.summary.mcp.unavailable` / `signInBeforeSend` / `notReady`, or the
 * transport error when a connect/disconnect itself failed.
 */
private fun mcpOutcomeMessage(context: Context, outcome: McpApplyOutcome): String {
    val title = context.getString(R.string.oc_summary_mcp_prepare_failed)
    val detail = when (outcome) {
        McpApplyOutcome.Ready -> return title
        is McpApplyOutcome.Unavailable -> context.getString(R.string.oc_summary_mcp_unavailable, outcome.name)
        is McpApplyOutcome.NeedsAuth -> context.getString(R.string.oc_summary_mcp_sign_in_before_send, outcome.name)
        is McpApplyOutcome.NotReady -> context.getString(R.string.oc_summary_mcp_not_ready, outcome.name)
        is McpApplyOutcome.Failed -> outcome.detail ?: context.getString(R.string.oc_home_no_server)
    }
    return "$title: $detail"
}
