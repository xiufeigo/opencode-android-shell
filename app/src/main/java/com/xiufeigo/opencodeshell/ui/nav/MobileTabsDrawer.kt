package com.xiufeigo.opencodeshell.ui.nav

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.ui.components.TabReorder
import com.xiufeigo.opencodeshell.ui.overlay.DialogScrim
import com.xiufeigo.opencodeshell.ui.overlay.dialogCardBounds
import com.xiufeigo.opencodeshell.ui.overlay.rememberDialogScrimState
import com.xiufeigo.opencodeshell.ui.theme.OcTheme
import com.xiufeigo.opencodeshell.ui.theme.ocShadow
import kotlin.math.abs

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
 * cannot host on the dialog stack; the 240 ms `cubic-bezier(.2,.8,.2,1)` enter
 * slide is kept simple (no animation dependency beyond core Compose). The
 * tab-count badge on the trigger lives in [BottomTabStrip]'s slot; the drawer
 * itself lists every tab.
 *
 * B-20: the vertical list reorders on **long-press drag** — upstream's
 * `TitlebarTabStrip orientation="vertical"` runs the same `useSortable` as the
 * horizontal one (`tab-strip.tsx:334-352`), so the drawer keeps that instead of
 * the earlier no-mobile-DnD adaptation, and it is why the drawer and the
 * desktop strip share one order. A long press that never moves opens the
 * Rename menu (upstream's double-click rename, `tab-nav.tsx:64-67`); the
 * drawer's rows scroll, and a drag near the edge does not auto-scroll the list
 * the way dnd-kit's `AutoScroller` does.
 *
 * @param activeSessionId the route's session id, or `null` off a session route
 *   (then the Home footer row paints pressed).
 * @param sessionLabel upstream's tab fallback (`session.tab.session`).
 * @param onMoveSession B-20: a row drag landed — `(from, to)` indices into
 *   [sessions]; the host applies [SessionTabs.reorder].
 * @param onRenameSession B-20: the long-press menu's Rename — `(tab, title)`.
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
    /** A-2: the same per-tab indicator the strip's own slot renders. */
    tabBadge: (SessionTabRef) -> SessionTabBadge = { SessionTabBadge.NONE },
    /**
     * B-6: the newest closed tab, or null when nothing was closed. Non-null
     * renders the `tab.reopenClosed` row (`titlebar.tsx:406-412`) — the phone
     * has no keyboard chord, so this row is the mobile entry point.
     */
    closedTab: ClosedTab? = null,
    /** B-6: runs `tab.reopenClosed` (restore the tab + navigate to it). */
    onReopenClosed: () -> Unit = {},
    /** B-20: long-press drag reorder — `(from, to)` indices into [sessions]. */
    onMoveSession: (Int, Int) -> Unit = { _, _ -> },
    /** B-20: the long-press menu's Rename — `(tab, new title)`. */
    onRenameSession: (SessionTabRef, String) -> Unit = { _, _ -> },
) {
    if (!open) return
    val c = OcTheme.colors
    val shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
    // Footer pressed state: `titlebar.tsx:555` presses Home iff the route is
    // Home — i.e. no session is on screen ([SessionTabs.footerHomePressed]).
    val homePressed = SessionTabs.footerHomePressed(activeSessionId != null)

    // B-20 drag state, the vertical twin of the desktop strip's: the rows are
    // keyed by tab id in the `LazyColumn`, so a reorder moves a row instead of
    // rebinding it and the gesture is never restarted mid-drag.
    val rowHeights = remember { mutableStateMapOf<String, Float>() }
    var dragId by remember { mutableStateOf<String?>(null) }
    var dragDy by remember { mutableFloatStateOf(0f) }
    var dragMoved by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<String?>(null) }
    var renamingId by remember { mutableStateOf<String?>(null) }
    var renameDraft by remember { mutableStateOf("") }
    val liveSessions = rememberUpdatedState(sessions)
    val liveMove = rememberUpdatedState(onMoveSession)
    val rowGapPx = with(LocalDensity.current) { DRAWER_TAB_GAP.toPx() }

    val scrim = rememberDialogScrimState()
    Box(modifier = modifier.fillMaxSize()) {
        DialogScrim(scrim, onDismissRequest)
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .heightIn(max = 480.dp)
                .clip(shape)
                .background(c.bgDeep)
                .ocShadow(OcTheme.elevation.overlay, shape)
                .dialogCardBounds(scrim)
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
                verticalArrangement = Arrangement.spacedBy(DRAWER_TAB_GAP),
            ) {
                items(items = sessions, key = { it.id }) { tab ->
                    val active = tab.id == activeSessionId
                    val dragging = dragId == tab.id
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .onSizeChanged { rowHeights[tab.id] = it.height.toFloat() }
                            // The dragged row follows the finger and floats over
                            // its neighbours (dnd-kit's feedback).
                            .graphicsLayer {
                                if (dragging) {
                                    translationY = dragDy
                                    alpha = 0.85f
                                }
                            }
                            .zIndex(if (dragging) 1f else 0f)
                            .pointerInput(tab.id) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        dragId = tab.id
                                        dragDy = 0f
                                        dragMoved = false
                                    },
                                    // A long press that never moved is the menu;
                                    // the library consumes the up it ends on, so
                                    // this is not also a tap on the row.
                                    onDragEnd = {
                                        if (dragId == tab.id && !dragMoved) menuFor = tab.id
                                        dragId = null
                                        dragDy = 0f
                                    },
                                    onDragCancel = {
                                        dragId = null
                                        dragDy = 0f
                                    },
                                    onDrag = { _, amount ->
                                        if (dragId != tab.id) return@detectDragGesturesAfterLongPress
                                        dragDy += amount.y
                                        if (abs(dragDy) > viewConfiguration.touchSlop) dragMoved = true
                                        val list = liveSessions.value
                                        val from = list.indexOfFirst { it.id == tab.id }
                                        // Row height + the list's gap is the
                                        // pitch of the vertical grid.
                                        val pitch = (rowHeights[tab.id] ?: 0f) + rowGapPx
                                        val to = TabReorder.dropIndex(from, dragDy, pitch, list.size)
                                        if (to >= 0 && to != from) {
                                            liveMove.value(from, to)
                                            // Re-base the travel on the row's new
                                            // home so the next step counts from
                                            // there.
                                            dragDy -= (to - from) * pitch
                                        }
                                    },
                                )
                            },
                    ) {
                        DrawerTabRow(
                            tab = tab,
                            active = active,
                            sessionLabel = sessionLabel,
                            badge = tabBadge(tab),
                            onClick = { onOpenSession(tab) },
                            onClose = { onCloseSession(tab) },
                        )
                        // The editor covers the row (`matchParentSize` does not
                        // feed back into the Box's own size), so the list does
                        // not reflow while it is open.
                        if (renamingId == tab.id) {
                            TabRenameField(
                                value = renameDraft,
                                onValueChange = { renameDraft = it },
                                onConfirm = {
                                    renamingId = null
                                    onRenameSession(tab, renameDraft)
                                },
                                onDismiss = { renamingId = null },
                                modifier = Modifier.matchParentSize(),
                            )
                        }
                        DropdownMenu(
                            expanded = menuFor == tab.id,
                            onDismissRequest = { menuFor = null },
                            // v11: the shell's palette for menus — M3's default
                            // surface container is not re-themed dark here.
                            shape = RoundedCornerShape(6.dp),
                            containerColor = c.layer01,
                            tonalElevation = 0.dp,
                            border = BorderStroke(0.5.dp, c.borderMuted),
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(R.string.common_rename),
                                        fontSize = 13.sp,
                                        color = c.textBase,
                                    )
                                },
                                onClick = {
                                    menuFor = null
                                    renameDraft = tab.title.orEmpty()
                                    renamingId = tab.id
                                },
                            )
                        }
                    }
                }
            }
            // `mobile-tabs-new-session` — plus icon + `command.session.new`.
            DrawerActionRow(
                iconRes = R.drawable.ic_oc_plus,
                label = stringResource(R.string.oc_command_session_new),
                labelColor = c.textBase,
                onClick = onNewSession,
            )
            // B-6: `tab.reopenClosed` (`titlebar.tsx:406-412`) — the drawer's
            // mobile entry for the closed-tab stack. Hidden while nothing is
            // closed, so the row never advertises an empty action.
            if (closedTab != null) {
                DrawerActionRow(
                    iconRes = R.drawable.ic_oc_reopen,
                    label = stringResource(R.string.command_tab_reopenClosed),
                    labelColor = c.textBase,
                    onClick = onReopenClosed,
                )
            }
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

/** `gap-1` of the drawer's vertical tab list — the row pitch's gap half. */
private val DRAWER_TAB_GAP = 4.dp

/** One vertical tab slot: title + ✕, active marked like the strip slots. */
@Composable
private fun DrawerTabRow(
    tab: SessionTabRef,
    active: Boolean,
    sessionLabel: String,
    onClick: () -> Unit,
    onClose: () -> Unit,
    badge: SessionTabBadge = SessionTabBadge.NONE,
) {
    val c = OcTheme.colors
    // Hoisted: `semantics {}` is not a composable scope. The label rides the
    // clickable Box below, not the `Icon` inside it.
    val closeLabel = stringResource(R.string.oc_home_tab_close)
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
        SessionTabBadgeSlot(badge, modifier = Modifier.padding(end = 6.dp))
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
                .clickable(role = Role.Button, onClick = onClose)
                .semantics { contentDescription = closeLabel },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_oc_close),
                contentDescription = null,
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
