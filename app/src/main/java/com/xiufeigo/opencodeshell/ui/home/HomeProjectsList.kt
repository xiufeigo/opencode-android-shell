@file:OptIn(ExperimentalFoundationApi::class)

package com.xiufeigo.opencodeshell.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.PopupProperties
import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.ui.components.ocHitTargetAtLeast
import com.xiufeigo.opencodeshell.ui.components.ocMinTouchTarget
import com.xiufeigo.opencodeshell.ui.components.ocOverlayEscape
import com.xiufeigo.opencodeshell.ui.overlay.DialogScrim
import com.xiufeigo.opencodeshell.ui.overlay.dialogCardBounds
import com.xiufeigo.opencodeshell.ui.overlay.rememberDialogScrimState
import com.xiufeigo.opencodeshell.ui.theme.OcTheme
import com.xiufeigo.opencodeshell.ui.theme.ocShadow

/**
 * Home's left column (`home-projects-view.tsx`, `research/02` §1.2).
 *
 * Geometry: header row `h-7` = 28 dp with the title at 13 px/530 `text-muted`,
 * project rows `h-7` = 28 dp / radius 6 / `px-1.5` = 6 dp / 8 dp icon gap,
 * `gap-4` = 16 dp to the list, desktop offsets `mt-14` (56 dp) + `pt-[52px]`
 * (52 dp), and the utility nav at the bottom (`hidden lg:flex mb-8 mt-4`).
 *
 * What M3 deliberately drops: drag-to-reorder (`@dnd-kit`, touch disabled
 * upstream — §376). The 最近关闭 stack keeps upstream's placement — inside the
 * empty state, never beside the live rows ([HomeProjects.showsClosed]).
 * Upstream's `MenuV2` (`view.tsx:731-760`) is the row's menu, reached by
 * right-click (`:660-663`) **or** its visible ⋯ trigger (`:724-731`) — both, and
 * [ProjectRow] carries both: the long-press and the dots button. The rows:
 * 「新建会话」 (`:733-735`), 「Edit project…」
 * (`controller.tsx:135-138` → the settings project editor, hosted here as
 * [HomeProjectEditDialog]) and 「Refresh worktrees」 (`POST
 * /api/worktree/refresh`) join the close entry that M3 exposed as a visible ✕.
 * A-3 adds the two upstream rows the audit listed — 「导入会话」
 * (`view.tsx:737-740`, hidden without a picker, exactly upstream's
 * `Show when={canImportSession}`) and 「清除通知」 (`view.tsx:752-758`, disabled
 * at `unseen === 0`) — in upstream's relative order. One upstream row stays
 * out: 「Open in file manager」 (`canRevealProject`) has no Android counterpart.
 * The row's trailing ✎ (`home-project-new-session`) is kept but is always
 * visible: touch has no hover-reveal (§1.2 lists it under
 * `group-hover/project`) — see [ProjectRow] for what that pencil means
 * upstream.
 */
@Composable
internal fun HomeProjectsColumn(
    projects: List<HomeProjects.Project>,
    recentlyClosed: List<HomeProjects.Project>,
    selected: String?,
    onSelect: (HomeProjects.Project) -> Unit,
    onNewSession: (HomeProjects.Project) -> Unit,
    onCloseProject: (HomeProjects.Project) -> Unit,
    onReopenProject: (HomeProjects.Project) -> Unit,
    onAddProject: () -> Unit,
    onOpenSettings: () -> Unit,
    /** Row-menu 「Edit project…」 — `home/projects/controller.tsx:135` `edit`. */
    onEditProject: (HomeProjects.Project) -> Unit = {},
    /** Row-menu 「Refresh worktrees」 — `POST /api/worktree/refresh` per project. */
    onRefreshWorktrees: (HomeProjects.Project) -> Unit = {},
    /**
     * A-3: 「导入会话」. The host is self-serve (the importer owns the picker, the
     * SAF read and the import route), so this only carries the one thing the
     * importer cannot do — open the session it created. Upstream
     * `home.project.openProjectSession` (`controller.tsx:129-131`).
     */
    onSessionImported: (String) -> Unit = {},
    /**
     * A-3: per-project unseen count for 「清除通知」's `disabled={unseen === 0}`
     * (`view.tsx:752`). Defaults to 0 — the row renders disabled, never lying
     * about a count this file has no source for.
     */
    projectUnseen: (HomeProjects.Project) -> Int = { 0 },
    /**
     * A-3: 「清除通知」 (`sidebar.project.clearNotifications`,
     * `controller.tsx:145-150`): mark every directory of the project viewed. Null
     * (no host) leaves the row disabled.
     */
    onClearProjectNotifications: ((HomeProjects.Project) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val c = OcTheme.colors
    // One row menu at a time (the session column keys its menu the same way).
    var menuProject by remember { mutableStateOf<String?>(null) }
    // A-3: the row's import host (`rememberHomeProjectImport`) — self-serve like
    // the project editor and the worktree refresh above, so HomeScreen needs no
    // new callback for the picker itself.
    val importSession = rememberHomeProjectImport(onSessionImported)
    // v73: the row's ✕ (and its menu's Close row) asks first. Upstream's Close
    // is a silent client-local removal, and on a column of live rows that reads
    // exactly like a delete — the tap gives no feedback and, with rows left,
    // the Recently-closed stack stays out of sight. The confirm names the
    // consequence (off the list, nothing deleted), so the affordance cannot be
    // mistaken for the session column's destructive delete; the semantics
    // behind it stay upstream's Close.
    var pendingClose by remember { mutableStateOf<HomeProjects.Project?>(null) }

    Column(modifier = modifier.fillMaxHeight().padding(top = DESKTOP_TOP_INSET)) {
        Row(
            modifier = Modifier.fillMaxWidth().height(HEADER_HEIGHT).padding(start = 6.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.oc_home_projects),
                color = c.textMuted,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            // Shown while a single server is connected (§1.2 condition) — the
            // shell always has exactly one.
            //
            // The label rides the clickable Box, never the `Icon` below it: only
            // the node carrying ACTION_CLICK is announced as a button, and
            // Compose reads `contentDescription` from a node's own config (see
            // [ProjectRow]'s ✕).
            val addProjectLabel = stringResource(R.string.oc_home_add_project)
            Box(
                modifier = Modifier
                    // 28 dp of pixels, 48 dp of finger (`ocMinTouchTarget`).
                    .ocMinTouchTarget(visual = ICON_BUTTON_LARGE)
                    .clip(OcTheme.shapes.md)
                    .clickable(role = Role.Button, onClick = onAddProject)
                    .semantics { contentDescription = addProjectLabel },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_oc_folder_add),
                    contentDescription = null,
                    tint = c.iconMuted,
                    modifier = Modifier.size(16.dp),
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        if (projects.isEmpty()) {
            // `HomeProjectEmpty`: the first row *is* the add-project entry, and
            // the closed stack below it is this state's second half.
            AddProjectRow(onClick = onAddProject)
        } else {
            projects.forEach { project ->
                ProjectRow(
                    project = project,
                    label = HomeProjects.rowLabel(project, projects),
                    selected = project.path == selected,
                    menuOpen = menuProject == project.path,
                    onSelect = { onSelect(project) },
                    onNewSession = { onNewSession(project) },
                    onClose = { pendingClose = project },
                    onOpenMenu = { menuProject = project.path },
                    onDismissMenu = { menuProject = null },
                    onEdit = {
                        menuProject = null
                        onEditProject(project)
                    },
                    onRefresh = {
                        menuProject = null
                        onRefreshWorktrees(project)
                    },
                    onImport = {
                        menuProject = null
                        importSession(project)
                    },
                    clearEnabled = projectUnseen(project) > 0 &&
                        onClearProjectNotifications != null,
                    onClearNotifications = {
                        menuProject = null
                        onClearProjectNotifications?.invoke(project)
                    },
                )
            }
        }

        pendingClose?.let { target ->
            ProjectCloseConfirmDialog(
                label = HomeProjects.rowLabel(target, projects),
                onConfirm = {
                    pendingClose = null
                    onCloseProject(target)
                },
                onDismiss = { pendingClose = null },
            )
        }

        // Upstream's `HomeProjectEmpty` owns the closed stack: it renders only
        // once the column has no open project left, so a just-closed row never
        // sits next to the live ones (see [HomeProjects.showsClosed]).
        if (HomeProjects.showsClosed(projects, recentlyClosed)) {
            RecentlyClosedSection(projects = recentlyClosed, onReopen = onReopenProject)
        }

        Spacer(Modifier.weight(1f))

        // `HomeUtilityNav.hidden.lg:flex.mb-8.mt-4`
        Spacer(Modifier.height(16.dp))
        HomeUtilityNav(
            onOpenSettings = onOpenSettings,
            modifier = Modifier.padding(bottom = 32.dp),
        )
    }
}

/** `HomeProjectNavButton data-action="home-add-project-row"` (the empty state's first row). */
@Composable
private fun AddProjectRow(onClick: () -> Unit) {
    val c = OcTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 48 dp floor for the clickable node; must precede any `.height` /
            // `.clip` / `.clickable` below (see `ocHitTargetAtLeast`).
            .ocHitTargetAtLeast(HomeRowLayout.MIN_HIT_TARGET)
            .heightIn(min = ROW_HEIGHT)
            .clip(OcTheme.shapes.md)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_oc_folder_add),
            contentDescription = null,
            tint = c.iconMuted,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = stringResource(R.string.oc_home_add_project),
            color = c.textMuted,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * `HomeProjectRow`: 28 dp row, `bg-layer-03` while selected, the trailing
 * `home-project-menu` ⋯ plus the `home-project-new-session` ✎ and a close
 * affordance. The menu (upstream `MenuV2`, `view.tsx:731-760`) opens from the ⋯
 * **and** from a long-press, in upstream's relative order:
 * 「新建会话」 (`command.session.new`) / 「导入会话」 / 「Edit project…」 /
 * 「Refresh worktrees」 / 「清除通知」 / ─── / 「Close」 — the two rows A-3 added
 * sit exactly where upstream puts them, and 「Refresh worktrees」 is this
 * shell's own row (`POST /api/worktree/refresh`; upstream keeps worktrees in
 * the session selector and Settings instead).
 *
 * The ⋯ is upstream's own trigger, not an Android addition: upstream renders
 * `Menu.Trigger as={IconButton} data-action="home-project-menu"` with
 * `icon={<Icon name="outline-dots" />}` and `aria-label={common.moreOptions}`
 * (`view.tsx:724-731`) in the row's trailing cluster, and right-click opens the
 * same menu (`:660-663`). This shell shipped only the long-press half, so the
 * menu — 「导入会话」 included — was invisible until someone happened to
 * long-press a row (v36/v38 tB: two testers never found the import entry).
 * Touch has no right-click, so the long-press stays as that half; the dots
 * button is the half that makes the menu findable.
 *
 * The ✎'s semantics are upstream's, not the glyph's: upstream ships
 * `data-action="home-project-new-session"` with `icon={<Icon name="edit" />}`
 * and `aria-label={command.session.new}` (`view.tsx:767-771`), so the pencil
 * **starts a draft**. Editing a project is only ever the menu's
 * 「Edit project…」 (`controller.tsx:135-138` → the settings project editor) —
 * the row menu lists both actions in upstream's order so the pencil cannot be
 * mistaken for the editor.
 *
 * Two upstream rows stay out: 「Open in file manager」 (`canRevealProject`, no
 * Android counterpart) and the import row when the host handed no picker over
 * (upstream hides it behind `canImportSession`, `:737`).
 *
 * 极窄 (a row below [HomeRowLayout.NARROW_WIDTH]): the trailing cluster drops to
 * the ⋯ alone. The ✎ and the ✕ are the menu's first and last rows
 * (`command.session.new` / `Close`), so nothing becomes unreachable, and the
 * three 28 dp buttons stop squeezing the project name to zero — the row
 * measures itself ([HomeRowLayout.projectNameWidth] is that arithmetic).
 */
@Composable
private fun ProjectRow(
    project: HomeProjects.Project,
    /** The name line: the name, plus a path tail when a twin makes it ambiguous. */
    label: String,
    selected: Boolean,
    menuOpen: Boolean,
    onSelect: () -> Unit,
    onNewSession: () -> Unit,
    onClose: () -> Unit,
    onOpenMenu: () -> Unit,
    onDismissMenu: () -> Unit,
    onEdit: () -> Unit,
    onRefresh: () -> Unit,
    onImport: (() -> Unit)? = null,
    onClearNotifications: () -> Unit = {},
    clearEnabled: Boolean = false,
) {
    val c = OcTheme.colors
    // Resolved here because a `semantics {}` block cannot call `stringResource`;
    // the ✕ below and the menu's 「Close」 row share the one label.
    val closeLabel = stringResource(R.string.oc_home_project_close)
    // Same reason as `closeLabel`: the ✎'s label has to be hoisted out of the
    // `semantics {}` block on its clickable Box. It is upstream's own
    // `aria-label` for this button (`command.session.new`, `view.tsx:769`) —
    // the glyph beside it is upstream's `edit` icon, not an editor.
    val newSessionLabel = stringResource(R.string.oc_new_session)
    // Same hoist again: upstream's `aria-label` for the ⋯ trigger
    // (`common.moreOptions`, `view.tsx:730`) — the label the header's own ⋮
    // announces, so the two menu buttons read alike.
    val moreOptionsLabel = stringResource(R.string.oc_more_options)
    // The row measures itself instead of taking a flag from its mount point: the
    // desktop column is a fixed 280 dp while the phone dropdown is the window
    // width, so only the row knows how much room its trailing cluster has. (The
    // sessions column measures once at its root — it is a 64-row LazyColumn, this
    // is a handful of rows.)
    BoxWithConstraints {
        val narrow = HomeRowLayout.narrow(maxWidth)
        // 极窄: the ✎ and the ✕ repeat the ⋯ menu's own two rows, so below the
        // width floor they give way and the ⋯ — the only visible menu trigger —
        // keeps the name readable (see [HomeRowLayout.showProjectActions]).
        val showActions = HomeRowLayout.showProjectActions(narrow)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // 48 dp floor, before any `.height`/`.clip`/`.clickable` below.
                .ocHitTargetAtLeast(HomeRowLayout.MIN_HIT_TARGET)
                .heightIn(min = ROW_HEIGHT)
                .clip(OcTheme.shapes.md)
                // `data-[selected]:bg-layer-03` / `pr-16` reserves the action slot.
                .background(if (selected) c.layer03 else c.bgBase)
                .combinedClickable(role = Role.Button, onClick = onSelect, onLongClick = onOpenMenu)
                .padding(start = 6.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ProjectAvatar(project = project)
            // v52 tD: two projects can share a basename (`/home/xiufeigo/v36projC`
            // beside `…/opencode-android-shell/v36projC`), and the row has room
            // for one line — so the shortest path tail that tells them apart
            // rides on the name's own line ([HomeProjects.rowLabel]). A unique
            // name prints alone, unchanged.
            Text(
                text = label,
                color = c.textMuted,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // Upstream's own trailing cluster order (`view.tsx:707-731`): the
            // `home-project-menu` ⋯ first, then the ✎. Same label-on-the-clickable
            // shape as the ✎ and ✕ below.
            Box(
                modifier = Modifier
                    .size(ICON_BUTTON_LARGE)
                    .clip(OcTheme.shapes.md)
                    .clickable(role = Role.Button, onClick = onOpenMenu)
                    .semantics { contentDescription = moreOptionsLabel },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_oc_dots),
                    // Null: the ⋯ Box above owns the label (see `closeLabel`).
                    contentDescription = null,
                    tint = c.iconMuted,
                    modifier = Modifier.size(14.dp),
                )
            }
            if (showActions) {
                Box(
                    modifier = Modifier
                        .size(ICON_BUTTON_LARGE)
                        .clip(OcTheme.shapes.md)
                        .clickable(role = Role.Button, onClick = onNewSession)
                        .semantics { contentDescription = newSessionLabel },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_oc_edit),
                        // Null: the ✎ Box above owns the label (see `closeLabel`).
                        contentDescription = null,
                        tint = c.iconMuted,
                        modifier = Modifier.size(14.dp),
                    )
                }
                // The label belongs on the clickable Box, not on the `Icon` below it:
                // only the node carrying ACTION_CLICK is announced as a button, and
                // Compose reads a node's `contentDescription` from its own semantics
                // (a descendant's is not merged into an ancestor in the accessibility
                // tree). Same shape as the tab strip's ✕ and the composer's badges.
                Box(
                    modifier = Modifier
                        .size(ICON_BUTTON_LARGE)
                        .clip(OcTheme.shapes.md)
                        .clickable(role = Role.Button, onClick = onClose)
                        .semantics { contentDescription = closeLabel },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_oc_close),
                        // Null: the ✕ Box above owns the label, so TalkBack does not
                        // read it twice (once for the button, once for the glyph).
                        contentDescription = null,
                        tint = c.iconMuted,
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
        }
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = onDismissMenu,
            // `Popup` 默认不是 focusable，弹层窗口里没有焦点归属，ESC 进了窗口
            // 却没人接（外点关闭是 `PopupLayout` 自己判的，所以点空白正常）。
            properties = PopupProperties(
                focusable = true,
                dismissOnBackPress = true,
                dismissOnClickOutside = true,
            ),
            // v61：与 `HomeSessionsList` 的会话行菜单同因同方 ——
            // `DropdownMenu` 的 `modifier` 在宿主树上，ESC 只到宿主窗口，
            // 所以走宿主窗口上的 `OcEscapeRouter`（见 `OcPopoverDismiss.kt`）。
            modifier = Modifier.ocOverlayEscape(open = menuOpen) { onDismissMenu() },
            // v11 dark-surface sweep (see `DropdownMenuDarkSurfaceTest`): M3's
            // default `surfaceContainer` is a light translucent tint the shell
            // never re-themes, so these rows' text landed on a near-white panel
            // in the dark theme.
            shape = RoundedCornerShape(6.dp),
            containerColor = c.layer01,
            tonalElevation = 0.dp,
            border = BorderStroke(0.5.dp, c.borderMuted),
        ) {
            // `command.session.new` (`view.tsx:733-735`) — upstream's **first**
            // menu row, and the same action the row's trailing ✎ runs
            // (`onOpenProjectNewSession`). It is what makes the ✎ readable: the
            // glyph upstream gives that button is `Icon name="edit"`
            // (`view.tsx:770`) while its `aria-label` is `command.session.new`
            // (`:769`) — a pencil that starts a draft, never an editor. Listing
            // the action here too keeps the two entries from contradicting each
            // other, exactly as upstream ships them.
            DropdownMenuItem(
                text = {
                    Text(
                        text = stringResource(R.string.oc_command_session_new),
                        color = c.textBase,
                    )
                },
                onClick = {
                    onDismissMenu()
                    onNewSession()
                },
            )
            // `Show when={props.canImportSession}` (`view.tsx:737-740`).
            if (onImport != null) {
                DropdownMenuItem(
                    text = {
                        Text(
                            text = stringResource(R.string.command_session_import),
                            color = c.textBase,
                        )
                    },
                    onClick = onImport,
                )
            }
            DropdownMenuItem(
                text = {
                    Text(
                        text = stringResource(R.string.oc_home_project_edit),
                        color = c.textBase,
                    )
                },
                onClick = onEdit,
            )
            // Upstream's 「Refresh worktrees」 is not a project-menu item at
            // all: it is a side effect of **opening** the session workspace
            // menu, typed on a server project
            // (`session-workspace-menu.tsx:18` `project: Project`, whose `id`
            // comes from `ProjectListOutput[number]` — `types.ts:3-6`), and its
            // failure is swallowed (`.catch(() => undefined)`, `:53-57`).
            // Upstream therefore never offers the action for a directory the
            // server has no project for, rather than showing it and refusing.
            // Hiding it here is that rule; [HomeProjectEdit.refreshApplies] is
            // the predicate, and the row's `serverID` is `GET /api/project`'s
            // own answer for it (`HomeProjects.merge`).
            if (HomeProjectEdit.refreshApplies(project.serverID)) {
                DropdownMenuItem(
                    text = {
                        Text(
                            text = stringResource(R.string.oc_home_project_refresh_worktrees),
                            color = c.textBase,
                        )
                    },
                    onClick = onRefresh,
                )
            }
            // `disabled={props.unseen === 0}` (`view.tsx:752`) — a missing host
            // disables it too, so the row never looks live without a handler.
            DropdownMenuItem(
                text = {
                    Text(
                        text = stringResource(R.string.oc_home_project_clear_notifications),
                        color = if (clearEnabled) c.textBase else c.textFaint,
                    )
                },
                enabled = clearEnabled,
                onClick = onClearNotifications,
            )
            HorizontalDivider(color = c.borderMuted)
            DropdownMenuItem(
                // The same label the row's ✕ announces (see `closeLabel`).
                text = {
                    Text(
                        text = closeLabel,
                        color = c.textBase,
                    )
                },
                onClick = {
                    onDismissMenu()
                    onClose()
                },
            )
        }
    }
}

/**
 * `HomeProjectEmpty`'s closed stack (`view.tsx:583-590`): `mt-3` gap + the
 * `home.recentlyClosed` header + one [RecentlyClosedRow] per closed worktree.
 * Shared by the desktop column and the mobile dropdown, so the dropdown carries
 * the header too — an unlabelled closed row under the 项目 title reads as a
 * project row that 「关闭」 failed to remove.
 */
@Composable
private fun RecentlyClosedSection(
    projects: List<HomeProjects.Project>,
    onReopen: (HomeProjects.Project) -> Unit,
) {
    val c = OcTheme.colors
    Spacer(Modifier.height(12.dp))
    Row(
        modifier = Modifier.fillMaxWidth().height(HEADER_HEIGHT).padding(start = 6.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.oc_home_recently_closed),
            color = c.textFaint,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    projects.forEach { project ->
        RecentlyClosedRow(project = project, onSelect = { onReopen(project) })
    }
}

/** `HomeRecentlyClosedRow`: `ProjectAvatar variant="outline"` + the display name. */
@Composable
private fun RecentlyClosedRow(project: HomeProjects.Project, onSelect: () -> Unit) {
    val c = OcTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 48 dp floor for the clickable node; must precede any `.height` /
            // `.clip` / `.clickable` below (see `ocHitTargetAtLeast`).
            .ocHitTargetAtLeast(HomeRowLayout.MIN_HIT_TARGET)
            .heightIn(min = ROW_HEIGHT)
            .clip(OcTheme.shapes.md)
            .clickable(role = Role.Button, onClick = onSelect)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ProjectAvatar(project = project, outline = true)
        Text(
            text = project.name,
            color = c.textFaint,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * `ProjectAvatar` for one row: the project's custom icon image when it has one
 * (`icon.override`), else the `icon.color` variant's tint, else the neutral
 * surface with the name's initial — upstream `ProjectIcon`'s
 * `override → url → color` chain. `variant="outline"` (recently closed) swaps
 * the fill for a hairline border and carries no project metadata, so those rows
 * keep the neutral initial. The drawing itself is [ProjectIconView] (shared with
 * the editor's preview).
 */
@Composable
private fun ProjectAvatar(project: HomeProjects.Project, outline: Boolean = false) {
    ProjectIconView(
        name = project.name,
        iconColor = project.iconColor,
        override = project.iconOverride,
        size = AVATAR_SIZE,
        outline = outline,
    )
}

/** `mt-14` (56 dp) + `pt-[52px]` (52 dp) — desktop offsets of the projects aside. */
private val DESKTOP_TOP_INSET = 108.dp

/** `h-7` = 28 dp rows and header. */
private val ROW_HEIGHT = 28.dp
private val HEADER_HEIGHT = 28.dp

/** `icon-button-v2[size=large]` = 28 × 28 dp. */
private val ICON_BUTTON_LARGE = 28.dp

private val AVATAR_SIZE = 18.dp

// Mobile dropdown (home/route.tsx:27-31): below the two-column breakpoint the
// same column is hosted as a dropdown/list-panel at the top of the card, so
// the project list and the add-project entry stay reachable on a phone. Same
// callbacks as the desktop column, minus the settings entry (the mobile single
// column renders HomeUtilityNav separately).
@Composable
internal fun HomeProjectsDropdown(
    projects: List<HomeProjects.Project>,
    recentlyClosed: List<HomeProjects.Project>,
    selected: String?,
    onSelect: (HomeProjects.Project) -> Unit,
    onNewSession: (HomeProjects.Project) -> Unit,
    onCloseProject: (HomeProjects.Project) -> Unit,
    onReopenProject: (HomeProjects.Project) -> Unit,
    onAddProject: () -> Unit,
    onEditProject: (HomeProjects.Project) -> Unit = {},
    onRefreshWorktrees: (HomeProjects.Project) -> Unit = {},
    modifier: Modifier = Modifier,
    /** A-3: the same row-menu additions [HomeProjectsColumn] takes. */
    onSessionImported: (String) -> Unit = {},
    projectUnseen: (HomeProjects.Project) -> Int = { 0 },
    onClearProjectNotifications: ((HomeProjects.Project) -> Unit)? = null,
) {
    val c = OcTheme.colors
    var menuProject by remember { mutableStateOf<String?>(null) }
    val importSession = rememberHomeProjectImport(onSessionImported)
    // Same ask-first gate as the desktop column above.
    var pendingClose by remember { mutableStateOf<HomeProjects.Project?>(null) }
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().height(ROW_HEIGHT),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                // The selected row's display name (`displayName(project)`),
                // so a renamed project reads renamed here too — never the
                // bare folder name. No matching row (or a nameless root)
                // falls back to the folder name, then the column title.
                text = selected?.let { sel ->
                    val key = HomeSessions.matchKey(sel)
                    projects.firstOrNull { HomeSessions.matchKey(it.path) == key }?.name
                        ?: HomeSessions.projectName(sel)
                } ?: stringResource(R.string.oc_home_projects),
                color = c.textMuted,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            val addProjectLabel = stringResource(R.string.oc_home_add_project)
            Box(
                modifier = Modifier
                    // 28 dp of pixels, 48 dp of finger (`ocMinTouchTarget`).
                    .ocMinTouchTarget(visual = ICON_BUTTON_LARGE)
                    .clip(OcTheme.shapes.md)
                    .clickable(role = Role.Button, onClick = onAddProject)
                    .semantics { contentDescription = addProjectLabel },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_oc_folder_add),
                    contentDescription = null,
                    tint = c.iconMuted,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        projects.forEach { project ->
            ProjectRow(
                project = project,
                label = HomeProjects.rowLabel(project, projects),
                selected = project.path == selected,
                menuOpen = menuProject == project.path,
                onSelect = { onSelect(project) },
                onNewSession = { onNewSession(project) },
                onClose = { pendingClose = project },
                onOpenMenu = { menuProject = project.path },
                onDismissMenu = { menuProject = null },
                onEdit = {
                    menuProject = null
                    onEditProject(project)
                },
                onRefresh = {
                    menuProject = null
                    onRefreshWorktrees(project)
                },
                onImport = {
                    menuProject = null
                    importSession(project)
                },
                clearEnabled = projectUnseen(project) > 0 &&
                    onClearProjectNotifications != null,
                onClearNotifications = {
                    menuProject = null
                    onClearProjectNotifications?.invoke(project)
                },
            )
        }
        // Same gate as the desktop column (see [HomeProjects.showsClosed]).
        if (HomeProjects.showsClosed(projects, recentlyClosed)) {
            RecentlyClosedSection(projects = recentlyClosed, onReopen = onReopenProject)
        }
        pendingClose?.let { target ->
            ProjectCloseConfirmDialog(
                label = HomeProjects.rowLabel(target, projects),
                onConfirm = {
                    pendingClose = null
                    onCloseProject(target)
                },
                onDismiss = { pendingClose = null },
            )
        }
    }
}

/**
 * v73: the ask-first step behind the row's ✕ and its menu's `Close` row (both
 * mounts funnel through it — the ✕ and the menu row only arm `pendingClose`).
 *
 * Close stays upstream's non-destructive removal — the card says so outright
 * (neutral buttons, no danger styling: there is nothing to warn about) — but
 * it no longer fires silently: on a column of live rows a silent removal reads
 * exactly like the session column's delete, while a worktree the close did not
 * touch keeps the server-side row (and any session opened in it re-registers
 * the project, upstream `openProjectSession`). A worktree that should really
 * go is deleted where the shell already offers that — the new-session worktree
 * list and the Settings worktrees page (`WorktreeDeleteConfirmDialog`, owner
 * resolution included) — never from this row.
 */
@Composable
private fun ProjectCloseConfirmDialog(
    label: String,
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
                        text = stringResource(R.string.oc_home_project_close_title),
                        color = c.textBase,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        lineHeight = 21.sp,
                    )
                    Text(
                        text = stringResource(R.string.oc_home_project_close_message, label),
                        color = c.textMuted,
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                    )
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
                            label = stringResource(R.string.oc_home_project_close),
                            iconRes = R.drawable.ic_oc_close,
                            onClick = onConfirm,
                        )
                    }
                }
            }
        }
    }
}
