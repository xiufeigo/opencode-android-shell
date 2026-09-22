package com.xiufeigo.opencodeshell.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.xiufeigo.opencodeshell.ui.theme.ocShadow

/**
 * WAVE 2.3b P0 (A2 #1) — the v2 mobile drawer
 * (`_upstream/packages/app/src/shell/titlebar/titlebar.tsx:462-592`).
 *
 * Upstream shape, top to bottom:
 *
 * ```
 * [TitlebarTabStrip vertical]   every open tab, current marked (`:524-537`)
 * [+ New session]               `mobile-tabs-new-session` (`:539-550`)
 * ─── border-t ───
 * [Home (pressed off-session)]  `mobile-tabs-home` (`:552-565`)
 * [Settings] [Help]             `mobile-tabs-settings/help` (`:566-588`)
 * ```
 *
 * Adaptations: a self-contained in-tree sheet (scrim + bottom card) instead of
 * the Corvu drawer - ui/overlay is owned by the main agent, so this file
 * cannot host on the dialog stack; no drag-reorder (documented no-mobile-DnD
 * adaptation); the 240 ms `cubic-bezier(.2,.8,.2,1)` enter slide is kept simple
 * (no animation dependency beyond core Compose). The tab-count badge on the
 * trigger lives in [BottomTabStrip]'s slot; the drawer itself lists every tab.
 *
 * @param activeSessionId the route's session id, or `null` off a session route
 *   (then the Home footer row paints pressed).
 * @param sessionLabel upstream's tab fallback (`session.tab.session`).
 */
@Composable
fun MobileTabsDrawer(
    open: Boolean,
    onDismissRequest: () -> Unit,
    sessions: List<SessionTabRef>,
    activeSessionId: String?,
    sessionLabel: String,
    onOpenSession: (SessionTabRef) -> Unit,
    onCloseSession: (SessionTabRef) -> Unit,
    onNewSession: () -> Unit,
    onHome: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenHelp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!open) return
    val c = OcTheme.colors
    val shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
    // Footer pressed state: `titlebar.tsx:555` presses Home iff the route is
    // Home — i.e. no session is on screen ([SessionTabs.footerHomePressed]).
    val homePressed = SessionTabs.footerHomePressed(activeSessionId != null)

    Box(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(c.overlayScrim)
                .clickable(
                    interactionSource = androidx.compose.foundation.interaction.MutableInteractionSource(),
                    indication = null,
                    onClick = onDismissRequest,
                ),
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .heightIn(max = 480.dp)
                .clip(shape)
                .background(c.bgDeep)
                .ocShadow(OcTheme.elevation.overlay, shape)
                .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
        ) {
            // `mobile-drawer-handle`: 28 dp row with a 32×4 bar.
            Box(
                modifier = Modifier.fillMaxWidth().height(28.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .width(32.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(c.borderStrong),
                )
            }
            // `TitlebarTabStrip orientation="vertical"` — every open tab.
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(items = sessions, key = { it.id }) { tab ->
                    val active = tab.id == activeSessionId
                    DrawerTabRow(
                        tab = tab,
                        active = active,
                        sessionLabel = sessionLabel,
                        onClick = { onOpenSession(tab) },
                        onClose = { onCloseSession(tab) },
                    )
                }
            }
            // `mobile-tabs-new-session` — plus icon + `command.session.new`.
            DrawerActionRow(
                iconRes = R.drawable.ic_oc_plus,
                label = stringResource(R.string.oc_command_session_new),
                labelColor = c.textBase,
                onClick = onNewSession,
            )
            Spacer(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
                    .height(0.5.dp)
                    .background(c.borderMuted),
            )
            // `mobile-tabs-home` — pressed while Home is on screen.
            DrawerActionRow(
                iconRes = R.drawable.ic_oc_grid,
                label = stringResource(R.string.oc_home_title),
                labelColor = if (homePressed) c.textBase else c.textFaint,
                pressed = homePressed,
                onClick = onHome,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // `mobile-tabs-settings` — leading icon, takes the free space.
                Box(Modifier.weight(1f)) {
                    DrawerActionRow(
                        iconRes = R.drawable.ic_oc_gear,
                        label = stringResource(R.string.oc_home_settings),
                        labelColor = c.textFaint,
                        onClick = onOpenSettings,
                    )
                }
                // `mobile-tabs-help` — trailing, shrink-wrapped.
                DrawerActionRow(
                    iconRes = R.drawable.ic_oc_help,
                    label = stringResource(R.string.oc_home_help),
                    labelColor = c.textFaint,
                    onClick = onOpenHelp,
                )
            }
        }
    }
}

/** One vertical tab slot: title + ✕, active marked like the strip slots. */
@Composable
private fun DrawerTabRow(
    tab: SessionTabRef,
    active: Boolean,
    sessionLabel: String,
    onClick: () -> Unit,
    onClose: () -> Unit,
) {
    val c = OcTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
            .clip(OcTheme.shapes.md)
            .background(if (active) OcTheme.colors.layer02 else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(role = Role.Tab, onClick = onClick)
            .padding(start = 8.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = SessionTabs.displayTitle(tab.title, sessionLabel),
            color = if (active) c.textBase else c.textFaint,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(OcTheme.shapes.md)
                .clickable(role = Role.Button, onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_oc_close),
                contentDescription = stringResource(R.string.oc_home_tab_close),
                tint = c.iconMuted,
                modifier = Modifier.size(12.dp),
            )
        }
    }
}

/** A full-width `h-7` drawer action row (New session / Home / Settings / Help). */
@Composable
private fun DrawerActionRow(
    iconRes: Int,
    label: String,
    labelColor: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
    pressed: Boolean = false,
) {
    val c = OcTheme.colors
    Row(
        modifier = Modifier
            .height(28.dp)
            .clip(OcTheme.shapes.md)
            .background(if (pressed) c.layer02 else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = c.iconMuted,
            modifier = Modifier.size(16.dp),
        )
        Text(text = label, color = labelColor, fontSize = 13.sp, maxLines = 1)
    }
}
