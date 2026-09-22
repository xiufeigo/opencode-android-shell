package com.xiufeigo.opencodeshell.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.ui.theme.OcTheme

/**
 * Home's left column (`home-projects-view.tsx`, `research/02` §1.2).
 *
 * Geometry: header row `h-7` = 28 dp with the title at 13 px/530 `text-muted`,
 * project rows `h-7` = 28 dp / radius 6 / `px-1.5` = 6 dp / 8 dp icon gap,
 * `gap-4` = 16 dp to the list, desktop offsets `mt-14` (56 dp) + `pt-[52px]`
 * (52 dp), and the utility nav at the bottom (`hidden lg:flex mb-8 mt-4`).
 *
 * What M3 deliberately drops (all mouse-only or later-milestone hosts):
 * drag-to-reorder (`@dnd-kit`, touch disabled upstream — §376), the right-click
 * context menu and its `MenuV2` entries (edit / reveal / clear / close need
 * project CRUD), and `HomeRecentlyClosed`'s restore action (no source, see
 * [HomeProjects]). The row's trailing ✎ (`home-project-new-session`) is kept but
 * is always visible: touch has no hover-reveal (§1.2 lists it under
 * `group-hover/project`).
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
    modifier: Modifier = Modifier,
) {
    val c = OcTheme.colors

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
            Box(
                modifier = Modifier
                    .size(ICON_BUTTON_LARGE)
                    .clip(OcTheme.shapes.md)
                    .clickable(role = Role.Button, onClick = onAddProject),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_oc_folder_add),
                    contentDescription = stringResource(R.string.oc_home_add_project),
                    tint = c.iconMuted,
                    modifier = Modifier.size(16.dp),
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        if (projects.isEmpty()) {
            // `HomeProjectEmpty`: the first row *is* the add-project entry.
            AddProjectRow(onClick = onAddProject)
        } else {
            projects.forEach { project ->
                ProjectRow(
                    project = project,
                    selected = project.path == selected,
                    onSelect = { onSelect(project) },
                    onNewSession = { onNewSession(project) },
                    onClose = { onCloseProject(project) },
                )
            }
        }

        if (recentlyClosed.isNotEmpty()) {
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
                )
            }
            recentlyClosed.forEach { project ->
                RecentlyClosedRow(project = project, onSelect = { onReopenProject(project) })
            }
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
            .height(ROW_HEIGHT)
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
        )
    }
}

/**
 * `HomeProjectRow`: 28 dp row, `bg-layer-03` while selected, the trailing
 * `home-project-new-session` ✎ plus a close affordance.
 */
@Composable
private fun ProjectRow(
    project: HomeProjects.Project,
    selected: Boolean,
    onSelect: () -> Unit,
    onNewSession: () -> Unit,
    onClose: () -> Unit,
) {
    val c = OcTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            .clip(OcTheme.shapes.md)
            // `data-[selected]:bg-layer-03` / `pr-16` reserves the action slot.
            .background(if (selected) c.layer03 else c.bgBase)
            .clickable(role = Role.Button, onClick = onSelect)
            .padding(start = 6.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ProjectAvatar(name = project.name)
        Text(
            text = project.name,
            color = c.textMuted,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Box(
            modifier = Modifier
                .size(ICON_BUTTON_LARGE)
                .clip(OcTheme.shapes.md)
                .clickable(role = Role.Button, onClick = onNewSession),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_oc_edit),
                contentDescription = stringResource(R.string.oc_new_session),
                tint = c.iconMuted,
                modifier = Modifier.size(14.dp),
            )
        }
        Box(
            modifier = Modifier
                .size(ICON_BUTTON_LARGE)
                .clip(OcTheme.shapes.md)
                .clickable(role = Role.Button, onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_oc_close),
                contentDescription = stringResource(R.string.oc_home_project_close),
                tint = c.iconMuted,
                modifier = Modifier.size(12.dp),
            )
        }
    }
}

/** `HomeRecentlyClosedRow`: `ProjectAvatar variant="outline"` + the display name. */
@Composable
private fun RecentlyClosedRow(project: HomeProjects.Project, onSelect: () -> Unit) {
    val c = OcTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            .clip(OcTheme.shapes.md)
            .clickable(role = Role.Button, onClick = onSelect)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ProjectAvatar(name = project.name, outline = true)
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
 * `ProjectAvatar` stand-in: the client has no project/service payload, so the
 * avatar is the name's initial in a 18 dp rounded square. `variant="outline"`
 * (recently closed) swaps the fill for a hairline border.
 */
@Composable
private fun ProjectAvatar(name: String, outline: Boolean = false) {
    val c = OcTheme.colors
    val shape = OcTheme.shapes.sm
    Box(
        modifier = Modifier
            .size(AVATAR_SIZE)
            .clip(shape)
            .background(if (outline) c.bgBase else c.layer02)
            .then(if (outline) Modifier.border(0.5.dp, c.borderMuted, shape) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = name.firstOrNull()?.uppercaseChar()?.toString().orEmpty(),
            color = c.textMuted,
            fontSize = 10.sp,
            maxLines = 1,
        )
    }
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
    modifier: Modifier = Modifier,
) {
    val c = OcTheme.colors
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().height(ROW_HEIGHT),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = selected?.let { HomeSessions.projectName(it) } ?: stringResource(R.string.oc_home_projects),
                color = c.textMuted,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier = Modifier
                    .size(ICON_BUTTON_LARGE)
                    .clip(OcTheme.shapes.md)
                    .clickable(role = Role.Button, onClick = onAddProject),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_oc_folder_add),
                    contentDescription = stringResource(R.string.oc_home_add_project),
                    tint = c.iconMuted,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        projects.forEach { project ->
            ProjectRow(
                project = project,
                selected = project.path == selected,
                onSelect = { onSelect(project) },
                onNewSession = { onNewSession(project) },
                onClose = { onCloseProject(project) },
            )
        }
        recentlyClosed.forEach { project ->
            RecentlyClosedRow(project = project, onSelect = { onReopenProject(project) })
        }
    }
}
