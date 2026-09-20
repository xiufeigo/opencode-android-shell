package com.xiufeigo.opencodeshell.ui.session.drawer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
) {
    val c = OcTheme.colors
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(c.bgBase),
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
        Box(modifier = Modifier.fillMaxWidth().height(0.5.dp).background(c.borderBase))

        if (sessions.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.oc_sessions_empty),
                    color = c.textFaint,
                    fontSize = 13.sp,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(items = sessions, key = { it.id }) { session ->
                    SessionRow(
                        session = session,
                        selected = session.id == currentSessionId,
                        running = session.id == runningSessionId,
                        onClick = { onSelect(session) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionRow(
    session: OpenCodeClient.Session,
    selected: Boolean,
    running: Boolean,
    onClick: () -> Unit,
) {
    val c = OcTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .height(ROW_HEIGHT)
            .clip(OcTheme.shapes.md)
            // `ChatView.SessionsAdapter`: the observed session sits on layer-01;
            // §1.3 additionally marks it with a short bar on the leading edge.
            .background(if (selected) c.layer01 else Color.Transparent)
            .clickable(role = Role.Button, onClick = onClick)
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
