package com.xiufeigo.opencodeshell.ui.session.drawer

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiufeigo.opencodeshell.OpenCodeClient
import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.ui.components.SessionProgressIndicator
import com.xiufeigo.opencodeshell.ui.theme.OcTheme

/**
 * Left session drawer (`research/02` §7) — the Compose port of the M0-era
 * `view_session_drawer.xml` + `ChatView.SessionsAdapter`, whose behaviour is:
 * list the sessions from `ChatViewModel.listSessions`, highlight the currently
 * observed one, switch with `observeSession(id, title, directory)`.
 *
 * The container (`DrawerLayout` in the View shell) becomes
 * `ModalNavigationDrawer` in [SessionScreen]; this composable is only the
 * sheet's content, so it can be previewed / reused by the M3 Home screen.
 *
 * M6: the row of the session that is currently working carries the upstream
 * `session-progress-indicator-v2` — the web mounts that 25-dot marquee in the
 * session row / tab *instead of* the project avatar while the session is
 * loading (`index-CKBO9Mty.js` @1680971, `research/03` §2.1). The shell has no
 * avatar slot in this row, so the indicator sits next to the relative time,
 * where it never collides with the selection bar or the title.
 *
 * W4-C: the row now also answers the upstream long-press menu
 * (`home/sessions/view.tsx:30,597-610,694-701`, `HOME_SESSION_LONG_PRESS_MS`
 * = 500 ms) with **Rename / Delete**. The actions are hoisted to the caller so
 * this composable stays a pure view (and the confirmations render as
 * screen-level overlays, not inside the sheet).
 *
 * @param sheet mobile bottom-sheet form: no own surface (the sheet provides
 *   `bg-deep` + radius) and shrink-to-content height instead of `fillMaxSize`.
 * @param onRenameSession row menu → Rename; null disables the item.
 * @param onDeleteSession row menu → Delete; null disables the item (used when
 *   the client has no `DELETE /session/:id`).
 */
@Composable
fun SessionDrawerContent(
    sessions: List<OpenCodeClient.Session>,
    currentSessionId: String?,
    onNewSession: () -> Unit,
    onSelect: (OpenCodeClient.Session) -> Unit,
    modifier: Modifier = Modifier,
    /** The session whose turn is running (`RunStatus.RUNNING`), if any. */
    runningSessionId: String? = null,
    sheet: Boolean = false,
    onRenameSession: ((OpenCodeClient.Session) -> Unit)? = null,
    onDeleteSession: ((OpenCodeClient.Session) -> Unit)? = null,
) {
    val c = OcTheme.colors
    // Which row's long-press menu is open, by session id.
    var menuFor by remember { mutableStateOf<String?>(null) }
    Column(
        modifier = modifier
            .then(
                if (sheet) Modifier.fillMaxWidth()
                else Modifier.fillMaxSize().background(c.bgBase),
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(NEW_SESSION_HEIGHT)
                .clickable(role = Role.Button, onClick = onNewSession)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_oc_plus),
                contentDescription = null,
                tint = c.iconBase,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = stringResource(R.string.oc_new_session),
                color = c.textBase,
                fontSize = 14.sp,
                fontWeight = FontWeight.W500,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .background(MobileColors.hairline(c.isDarkSurface())),
        )

        if (sessions.isEmpty()) {
            Box(
                modifier = if (sheet) {
                    Modifier.fillMaxWidth().height(EMPTY_SHEET_HEIGHT)
                } else {
                    Modifier.fillMaxWidth().weight(1f)
                },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.oc_sessions_empty),
                    color = c.textFaint,
                    fontSize = 13.sp,
                )
            }
        } else {
            LazyColumn(
                // Bottom-sheet form wraps its content up to the sheet's max
                // height (`max-height: min(75dvh, …)`), the drawer fills.
                modifier = Modifier.weight(1f, fill = !sheet),
                contentPadding = PaddingValues(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(items = sessions, key = { it.id }) { session ->
                    SessionRow(
                        session = session,
                        selected = session.id == currentSessionId,
                        running = session.id == runningSessionId,
                        onClick = { onSelect(session) },
                        // Upstream row menu (500 ms touch / right click).
                        menuOpen = menuFor == session.id,
                        onLongClick = { menuFor = session.id },
                        onDismissMenu = { menuFor = null },
                        onRename = {
                            menuFor = null
                            onRenameSession?.invoke(session)
                        },
                        renameEnabled = onRenameSession != null,
                        onDelete = {
                            menuFor = null
                            onDeleteSession?.invoke(session)
                        },
                        deleteEnabled = onDeleteSession != null,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionRow(
    session: OpenCodeClient.Session,
    selected: Boolean,
    running: Boolean,
    onClick: () -> Unit,
    menuOpen: Boolean,
    onLongClick: () -> Unit,
    onDismissMenu: () -> Unit,
    onRename: () -> Unit,
    renameEnabled: Boolean,
    onDelete: () -> Unit,
    deleteEnabled: Boolean,
) {
    val c = OcTheme.colors
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .height(ROW_HEIGHT)
                .clip(OcTheme.shapes.md)
                // `ChatView.SessionsAdapter`: the observed session sits on layer-01;
                // §1.3 additionally marks it with a short bar on the leading edge.
                .background(if (selected) c.layer01 else Color.Transparent)
                .combinedClickable(
                    role = Role.Button,
                    onClick = onClick,
                    onLongClick = onLongClick,
                )
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selected) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .height(12.dp)
                        .clip(OcTheme.shapes.xs)
                        .background(c.iconBase),
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text = session.title,
                color = c.textBase,
                fontSize = 14.sp,
                fontWeight = if (selected) FontWeight.W500 else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            if (running) {
                SessionProgressIndicator(modifier = Modifier.padding(end = 6.dp))
            }
            RelativeTimeText(updatedAt = session.updatedAt)
        }
        // Upstream menu order: Rename / Separator / Delete
        // (`home/sessions/view.tsx:694-701`). A missing handler renders the item
        // disabled instead of hiding it, so the menu shape stays upstream-shaped.
        DropdownMenu(expanded = menuOpen, onDismissRequest = onDismissMenu) {
            SessionMenuItem(
                label = stringResource(R.string.oc_rename_session),
                enabled = renameEnabled,
                onClick = onRename,
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(0.5.dp)
                    .background(MobileColors.muted(c.isDarkSurface())),
            )
            SessionMenuItem(
                label = stringResource(R.string.oc_delete_session),
                enabled = deleteEnabled,
                onClick = onDelete,
            )
        }
    }
}

/** 13/530 menu row; disabled rows keep the upstream `opacity: .5` look. */
@Composable
private fun SessionMenuItem(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val c = OcTheme.colors
    DropdownMenuItem(
        text = {
            Text(
                text = label,
                color = c.textBase,
                fontSize = 13.sp,
                lineHeight = 16.sp,
                modifier = Modifier.alpha(if (enabled) 1f else 0.5f),
            )
        },
        enabled = enabled,
        onClick = onClick,
    )
}

/** `common.time.*` label for the row; nothing is rendered for an unknown time. */
@Composable
private fun RelativeTimeText(updatedAt: Long) {
    val relative = SessionTimeLabel.relative(updatedAt, System.currentTimeMillis()) ?: return
    Text(
        text = when (relative.unit) {
            SessionTimeLabel.Unit.JUST_NOW -> stringResource(R.string.oc_time_just_now)
            SessionTimeLabel.Unit.MINUTES ->
                pluralStringResource(R.plurals.oc_time_minutes_ago, relative.count, relative.count)

            SessionTimeLabel.Unit.HOURS ->
                pluralStringResource(R.plurals.oc_time_hours_ago, relative.count, relative.count)

            SessionTimeLabel.Unit.DAYS ->
                pluralStringResource(R.plurals.oc_time_days_ago, relative.count, relative.count)
        },
        color = OcTheme.colors.textMuted,
        fontSize = 12.sp,
        maxLines = 1,
    )
}

/** `view_session_drawer.xml`: `btn_new_session` h-44. */
private val NEW_SESSION_HEIGHT = 44.dp

/** `item_session.xml`: h-48 rows. */
private val ROW_HEIGHT = 48.dp

/** Bottom-sheet empty state: the sheet wraps its content, so give it a floor. */
private val EMPTY_SHEET_HEIGHT = 140.dp
