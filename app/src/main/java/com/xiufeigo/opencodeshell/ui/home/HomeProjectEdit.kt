package com.xiufeigo.opencodeshell.ui.home

import com.xiufeigo.opencodeshell.data.HomeProjectRecords
import org.json.JSONObject

/**
 * Pure edit model behind the project row's 「Edit project」 dialog — upstream
 * `settings/workspaces/project.tsx` (`SettingsProjectGeneral`) +
 * `project-model.ts` (`createEditProjectModel`), reached from
 * `home/projects/controller.tsx:135-138` (`edit` → `settings.openProject`).
 *
 * Upstream saves per field on blur through `PATCH /api/project/{id}`
 * (`project.update`); this dialog is explicit-Save, so [patchFor] folds the
 * three blur-saves into one patch whose **null fields are omitted from the
 * body** (`updateProject`'s contract: an absent key leaves the field
 * unchanged). The field-value rules mirror the model exactly:
 *
 * - name: trim; a name equal to the folder name is sent as `""` (clear);
 *   unchanged (vs `project.name ?? ""`) is not sent (`saveName`);
 * - icon.color / icon.override: sent only when the swatch selection or the
 *   picked image changed, `""` clears (`saveIcon`). The image is **not**
 *   desktop-only: `Project.Icon` carries `override` on the wire
 *   (`schema/src/project.ts:20-25`) and `project.update` persists it
 *   (`core/src/project.ts:216`), and upstream itself sends a `FileReader`
 *   `data:` URL there — so Android picks through SAF, downsamples, and PATCHes
 *   the same field ([ProjectIconImage]);
 * - commands.start: trimmed, empty clears, unchanged is not sent (`saveStartup`).
 *
 * Projects with no server id (client-local worktrees, and upstream's pseudo
 * project `"global"`) are **not** a degraded state upstream: the same three
 * fields stay editable and the write simply lands on the client's own
 * `ProjectMeta` instead of `PATCH /api/project/{id}`
 * (`project-model.ts:41-46`, `project-row.tsx:60-65`,
 * `global-sync/child-store.ts:335-351`). [writeTargetFor] is that
 * transport fork and nothing more, and [localMetaApplies] is its read-side
 * mirror (`runtime.tsx:151`).
 */
object HomeProjectEdit {

    /** The saved state the dialog loads and diffs against. */
    data class Original(
        /** `Project.id` — null when the worktree is not a server project. */
        val projectID: String?,
        /** `Project.name` as saved (`""` when unset). */
        val name: String,
        /** `Project.icon.color`. */
        val color: String?,
        /**
         * `Project.icon.override` — the project's custom icon image, a `data:`
         * URL (upstream stores the `FileReader.readAsDataURL` result). Null
         * when the project has no custom image.
         */
        val iconOverride: String? = null,
        /** `Project.commands.start`. */
        val startup: String,
        /** `displayName` fallback — the worktree's last segment. */
        val folderName: String,
    )

    /** The dialog's live fields. */
    data class Draft(
        val name: String,
        val color: String?,
        val startup: String,
        /** The picked image's `data:` URL, null when the project keeps none. */
        val iconOverride: String? = null,
    )

    /**
     * The `UpdatePayload` slice to send: `null` = omit the key (field
     * unchanged). [iconColor] / [iconOverride] / [startup] use `""` as a real
     * value (clears).
     */
    data class Patch(
        val name: String? = null,
        val iconColor: String? = null,
        val iconOverride: String? = null,
        val startup: String? = null,
    ) {
        val isEmpty: Boolean
            get() = name == null && iconColor == null && iconOverride == null && startup == null
    }

    /** The color swatches: the names [HomeProjects.iconColorHex] can tint. */
    val COLORS: List<String> = listOf("orange", "pink", "cyan", "green", "gray")

    /** Avatar tint for one swatch (same map the rows use). */
    fun colorHex(color: String?): Long? = HomeProjects.iconColorHex(color)

    /** `getFilename(project.worktree)` — `project-model.ts:17`. */
    fun folderName(worktree: String): String = HomeSessions.projectName(worktree) ?: worktree

    /** The fields shown before the first edit (`createEditProjectModel` store). */
    fun draftOf(original: Original): Draft = Draft(
        name = original.name.ifEmpty { original.folderName },
        color = original.color,
        startup = original.startup,
        iconOverride = original.iconOverride,
    )

    /** [Original] from a `GET /api/project` row's raw JSON (`schema/src/project.ts`). */
    fun originalFromJson(projectID: String, worktree: String, raw: JSONObject): Original = Original(
        projectID = serverProjectID(projectID),
        name = raw.optString("name", ""),
        color = raw.optJSONObject("icon")?.optString("color", "")?.takeIf { it.isNotEmpty() },
        // `org.json`'s `optString` answers the literal "null" for a JSON null,
        // so the override is read through the `isNull` guard — a project whose
        // icon carries only `color` must not end up with a "null" image.
        iconOverride = raw.optJSONObject("icon")?.let { icon ->
            if (icon.isNull("override")) null
            else icon.optString("override", "").takeIf { it.isNotEmpty() && it != "null" }
        },
        startup = raw.optJSONObject("commands")?.optString("start", "") ?: "",
        folderName = folderName(worktree),
    )

    /**
     * The one PATCH for an explicit Save: every unchanged field is omitted so
     * the server keeps it (`optionalKey` — `project-model.ts` `persist` per
     * field on blur). An all-unchanged draft is [Patch.isEmpty] and needs no
     * request at all.
     */
    fun patchFor(original: Original, draft: Draft): Patch {
        val nameValue = draft.name.trim().let { if (it == original.folderName) "" else it }
        val startupValue = draft.startup.trim()
        return Patch(
            name = nameValue.takeIf { it != original.name },
            iconColor = if (draft.color != original.color) (draft.color ?: "") else null,
            // Upstream `saveIcon(color, override)` clears with `override ?? ""`;
            // null here is "unchanged", so a clear has to be sent as "".
            iconOverride = if (draft.iconOverride != original.iconOverride) {
                draft.iconOverride ?: ""
            } else {
                null
            },
            startup = startupValue.takeIf { it != original.startup },
        )
    }

    /**
     * What a tap on the icon preview does — upstream's one `iconClick()`
     * control (`project-model.ts:125-132`), which picks **or** clears:
     *
     * ```ts
     * iconClick() {
     *   if (store.iconOverride && store.iconHover) {
     *     setStore("iconOverride", "")
     *     saveIcon(store.color, "")
     *     return
     *   }
     *   iconInput?.click()
     * }
     * ```
     *
     * Upstream disambiguates with hover (`onMouseEnter`/`Leave`,
     * `project.tsx:73-74`) and shows which of the two a tap will do through the
     * overlay glyph — `close` with an image, `share` without (`:91`). A touch
     * screen has no hover to carry that state, so the same fork is made on the
     * image itself: [Clear] exactly when an override is set (upstream's
     * clearing branch), [Pick] when it is not (upstream's `iconInput.click()`).
     *
     * [None] is the disabled case, so a save in flight still swallows the tap
     * the way the swatches do.
     */
    fun iconTap(override: String?, enabled: Boolean): IconAction = when {
        !enabled -> IconAction.None
        // Upstream's guard is `store.iconOverride && …` — a **truthiness** test,
        // so an empty string means "no image" and falls through to the picker.
        !override.isNullOrEmpty() -> IconAction.Clear
        else -> IconAction.Pick
    }

    /**
     * The draft a [IconAction.Clear] tap produces: only the image goes, and it
     * goes to **null** (「no image」), not to `""`. [patchFor] then sends the
     * clear as `iconOverride = ""` (`saveIcon(color, "")`), so the name, the
     * color and the startup command are all left out of the patch and survive
     * the write — upstream clears the image without touching the swatch either.
     */
    fun draftWithoutIcon(draft: Draft): Draft = draft.copy(iconOverride = null)

    /**
     * The highlighted spans of one hint line — upstream's `inlineVariables`
     * (`project.tsx:165-169`):
     *
     * ```ts
     * text.split(/(\$[A-Z][A-Z0-9_]*)/g)
     *     .map((part, index) => (index % 2 === 0 ? part : <code dir="ltr">{part}</code>))
     * ```
     *
     * The capturing group keeps the `$VAR` runs in the result, and because
     * every match lands on an odd index those are exactly the runs the
     * `<code>` wrapper goes on. Split with the same shape here so the dialog
     * paints the same runs in the monospace face upstream gives them.
     */
    fun variableSegments(text: String): List<HintSegment> {
        val segments = mutableListOf<HintSegment>()
        var index = 0
        for (match in VARIABLE.findAll(text)) {
            if (match.range.first > index) {
                segments += HintSegment(text.substring(index, match.range.first), false)
            }
            segments += HintSegment(match.value, true)
            index = match.range.last + 1
        }
        if (index < text.length) segments += HintSegment(text.substring(index), false)
        return segments
    }

    /** `$[A-Z][A-Z0-9_]*` — the exact token `inlineVariables` wraps in `<code>`. */
    private val VARIABLE = Regex("""\$[A-Z][A-Z0-9_]*""")

    /**
     * Whether 「刷新工作区」 belongs on this row's menu at all.
     *
     * Upstream's refresh is not a project-menu item to be gated — it is a side
     * effect of **opening** the session workspace menu, on a menu typed on a
     * server project (`session-workspace-menu.tsx:18` `project: Project`, i.e.
     * `ProjectListOutput[number]` with its `id` — `runtime/server/types.ts:3-6`).
     * Upstream therefore never renders the action anywhere the server has no
     * project for this directory, and treats the call as a best-effort refresh
     * whose failure is swallowed (`.catch(() => undefined)`, `:53-57`).
     *
     * Two rules fall out, and both are honoured here rather than refused with a
     * message: the row menu **hides** the item when the row has no server
     * project, and a call that still finds none **does nothing quietly** instead
     * of toasting 「该项目未在服务器注册」. The toast the audit flagged as
     * self-invented (Q3: upstream's i18n has no such key) is gone from the
     * shell's behaviour, not just from the panel.
     *
     * Same predicate as [serverProjectID] being non-null — written out because
     * the two live on opposite sides of a tap (one decides whether the row
     * offers the action, the other what the action finds) and the row menu has
     * no server call to make at that point.
     */
    fun refreshApplies(serverID: String?): Boolean = serverProjectID(serverID) != null

    /** A server project id: blank and upstream's pseudo project `global` have no PATCH route. */
    fun serverProjectID(id: String?): String? = id?.trim()?.takeIf { it.isNotEmpty() && it != "global" }

    /**
     * Where one save goes — upstream's `project.id && project.id !== "global" ?
     * serverPATCH : sync.project.meta(worktree, patch)` ternary
     * (`project-model.ts:41-46`, repeated for the inline rename at
     * `project-row.tsx:60-65`), with the branch it picked named.
     *
     * A **transport** fork, deliberately not an availability gate: a null id is
     * a normal, fully-editable project whose patch lands in the client-local
     * store ([com.xiufeigo.opencodeshell.data.HomeProjectStore.setMeta]).
     * `global` is folded in here for the same reason upstream folds it in —
     * it is a pseudo project with no PATCH route — so a caller never has to
     * remember the second condition.
     */
    fun writeTargetFor(projectID: String?): WriteTarget =
        serverProjectID(projectID)?.let { WriteTarget.Server(it) } ?: WriteTarget.Local

    /**
     * `runtime.tsx:151`'s read side:
     * `...(!metadata || metadata.id === "global" ? childStore.projectMeta : undefined)`
     * — the client-local meta **covers** the server's only when the worktree
     * has no server project. A registered project is owned by the server: its
     * icon must come from the catalogue, and a stale local value must not
     * shadow it.
     */
    fun localMetaApplies(serverProjectID: String?): Boolean = serverProjectID == null

    /**
     * The unregistered [Original] carrying the client-local meta, so re-opening
     * the dialog offers back what was last saved instead of three blank fields
     * (the read side of `childStore.projectMeta` behind `runtime.tsx:151`).
     *
     * Field-level, like the write: a field the record never set leaves the
     * [original]'s value alone.
     */
    fun originalWithLocalMeta(
        original: Original,
        meta: HomeProjectRecords.LocalMeta,
    ): Original = original.copy(
        name = meta.name.ifEmpty { original.name },
        color = meta.iconColor ?: original.color,
        iconOverride = meta.iconOverride ?: original.iconOverride,
        startup = meta.startup ?: original.startup,
    )

    /**
     * The same [Patch] as a client-local [HomeProjectRecords.MetaPatch] — the
     * tri-state is already identical (null = unchanged, `""` = clear), so the
     * local route reuses the one diff the dialog computed rather than
     * recomputing it.
     */
    fun metaPatchFor(patch: Patch): HomeProjectRecords.MetaPatch = HomeProjectRecords.MetaPatch(
        name = patch.name,
        iconColor = patch.iconColor,
        iconOverride = patch.iconOverride,
        startup = patch.startup,
    )

    /**
     * The `Project.id` of one worktree — `GET /api/project` matched via
     * [matchIn] (case/separator insensitive), because the row carries only the
     * path. Null when the server does not list it.
     */
    fun projectIdFor(path: String, projects: List<ServerProject>): String? =
        matchIn(path, projects)?.let { serverProjectID(it.id) }

    /**
     * The `GET /api/project` row owning [path]: the row whose `canonical` *is*
     * [path], else a row whose `sandboxes` contain it. A directory the server
     * registered only as a sandbox (the parent of a `.worktrees/<branch>`
     * checkout) has no canonical of its own — exact-canonical matching alone
     * reads that row as unregistered and 「Refresh worktrees」 fails for it.
     */
    fun matchIn(path: String, projects: List<ServerProject>): ServerProject? {
        val key = HomeSessions.matchKey(path) ?: return null
        return projects.firstOrNull { HomeSessions.matchKey(it.worktree) == key }
            ?: projects.firstOrNull { project -> project.sandboxes.any { HomeSessions.matchKey(it) == key } }
    }

    /** [ProjectFault.Request] for an exception: the transport's own reason. */
    fun faultOf(error: Throwable): ProjectFault.Request =
        ProjectFault.Request(error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName)

    /** One `GET /api/project` row as the lookup needs it (`canonical` = worktree). */
    data class ServerProject(
        val id: String,
        val worktree: String,
        val sandboxes: List<String> = emptyList(),
        /** `name` (`schema/src/project.ts`): blank when the server never set one. */
        val name: String = "",
    )

    /**
     * The catalogue's display names by [HomeSessions.matchKey]: the
     * `displayName(project)` source (`project.name || getFilename(worktree)` —
     * `shell/layout/helpers.ts:50-51`) for every spelling the catalogue knows.
     * Both the canonical worktree and each sandbox spelling map to the same
     * name, so an MSYS `/mnt/c/…` canonical still names a `C:\…` row; blank
     * names are dropped (unset reads as "no name", never an empty label) and
     * the first row wins on collision.
     */
    fun namesByKey(projects: List<ServerProject>): Map<String, String> {
        val names = LinkedHashMap<String, String>()
        for (project in projects) {
            val name = project.name.trim()
            if (name.isEmpty()) continue
            for (path in listOf(project.worktree) + project.sandboxes) {
                val key = HomeSessions.matchKey(path) ?: continue
                names.putIfAbsent(key, name)
            }
        }
        return names
    }
}

/**
 * Which write channel a project edit takes ([HomeProjectEdit.writeTargetFor]).
 * Both are normal, successful outcomes with the same user-visible result —
 * 「已保存」 — because upstream treats "no server project" as a transport
 * choice, not an error (see the object doc).
 */
sealed interface WriteTarget {
    /** `project.id` present: `PATCH /api/project/{id}`. */
    data class Server(val projectID: String) : WriteTarget

    /** No server project: `sync.project.meta(worktree, patch)` — client-local. */
    data object Local : WriteTarget
}

/**
 * What a tap on the icon preview does — upstream's single `iconClick()` control
 * ([HomeProjectEdit.iconTap]). Pick and clear are the same button there; only
 * the hover state says which one a tap will do.
 */
sealed interface IconAction {
    /** An image is set: the tap removes it (`setStore("iconOverride", "")`). */
    data object Clear : IconAction

    /** No image: the tap opens the picker (`iconInput?.click()`). */
    data object Pick : IconAction

    /** A save is in flight — the tap is swallowed, like the color swatches'. */
    data object None : IconAction
}

/**
 * One run of a hint line, split so `$UPPER_SNAKE` renders in the monospace face
 * upstream's `<code>` gives it (`project.tsx:165-169`).
 */
data class HintSegment(val text: String, val variable: Boolean)

/** Why a project catalog call failed — shown instead of a bare 「request failed」. */
sealed interface ProjectFault {
    /** No usable server entry (`ServerConfigStore.server` null or not an http URL). */
    data object NoServer : ProjectFault
    /** The request failed; [message] is the transport/API reason (`HTTP 401 …`). */
    data class Request(val message: String) : ProjectFault
}

/** [HomeProjectApi.loadOriginal] outcome. */
sealed interface ProjectLoad {
    /** The server answered; `original.projectID` is null when unregistered. */
    data class Ready(val original: HomeProjectEdit.Original) : ProjectLoad
    data class Failed(val fault: ProjectFault) : ProjectLoad
}

/** Catalog-write outcome ([HomeProjectApi.save] / [HomeProjectApi.refreshWorktrees]). */
sealed interface ProjectWrite {
    data object Ok : ProjectWrite

    /**
     * No server project owns this worktree, so there is nothing to
     * `POST /api/worktree/refresh` against.
     *
     * **Not an error state**, and deliberately not reported as one. Upstream
     * never reaches the question: its 「Refresh worktrees」 is the session
     * workspace menu's own open-time refresh, typed on a **server** project
     * (`session-workspace-menu.tsx:18` `project: Project`, whose `id` is
     * `ProjectListOutput[number]["id"]` — `runtime/server/types.ts:3-6`), so
     * the action only exists where the server knows the directory. Its failure
     * handling is `.catch(() => undefined)` (`:57`) — **silent**, no toast for
     * any failure at all.
     *
     * So the two upstream-visible rules are: do not offer the action where
     * there is no server project, and stay quiet when a call fails. The row
     * menu honours the first by hiding the item
     * ([refreshApplies]) and the refresh handler the second by
     * swallowing this outcome without a toast.
     */
    data object NotRegistered : ProjectWrite

    data class Failed(val fault: ProjectFault) : ProjectWrite
}
