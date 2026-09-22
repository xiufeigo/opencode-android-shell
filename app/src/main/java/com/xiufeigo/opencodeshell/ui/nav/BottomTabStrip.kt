package com.xiufeigo.opencodeshell.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.ui.theme.OcTheme

/** One open session tab (upstream `Tab = SessionTab | DraftTab`, `research/02` §9b.3). */
data class SessionTabRef(val id: String, val title: String?)

/**
 * Bottom title bar (`research/02` §9b, `titlebar-tab-nav.css`).
 *
 * M3 scope — the optional half of the brief: it exists **only** when
 * `appearance.tabLayout === "vertical"` ([TabLayout]), which is also the mobile
 * "底部导航" setting in §12, and it is off by default. Geometry is the verified
 * one: bar `h-9` = 36 dp, tab `h-7` = 28 dp, radius 6 dp, `px-1.5` = 6 dp gaps,
 * 1.5 × 12 dp separator between adjacent inactive slots, max slot width
 * `w-56` = 224 dp.
 *
 * WAVE 2.3b P0 (A2 #1): the current-session slot is now the drawer's
 * **trigger** — `titlebar.tsx:472-519` `MobileDrawerTrigger` shows the current
 * tab's avatar + title + the tab count badge and opens [MobileTabsDrawer],
 * which lists **every** open tab plus Home / Settings / Help rows
 * (`:520-592`). The `session`/`sessionActive` pair is kept so existing callers
 * compile; pass the full list via [sessions] and the route id via
 * [activeSessionId] for the drawer content.
 *
 * Home is a *button* of the bar, not a tab (upstream: `Icon
 * name="grid-plus"`, keybind `home.toggle`), and `+` creates a session directly
 * with no menu (§9b.4).
 */
@Composable
fun BottomTabStrip(
    session: SessionTabRef?,
    sessionActive: Boolean,
    onHome: () -> Unit,
    onOpenSession: (SessionTabRef) -> Unit,
    onCloseSession: (SessionTabRef) -> Unit,
    onNewSession: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * WAVE 2.3b: all open tabs for the drawer list. Defaults to the legacy
     * single slot so pre-drawer callers keep compiling; drawer hosts pass the
     * shell's `openSessions` here.
     */
    sessions: List<SessionTabRef> = if (session == null) emptyList() else listOf(session),
    /** Route's session id, or `null` off a session route (drawer Home pressed). */
    activeSessionId: String? = session?.takeIf { sessionActive }?.id,
    /** `sidebar.settings` — opens the settings surface. */
    onOpenSettings: () -> Unit = {},
    /** `sidebar.help` — opens the desktop-feedback URL. */
    onOpenHelp: () -> Unit = {},
) {
    val c = OcTheme.colors
    var drawerOpen by remember { mutableStateOf(false) }
    // Upstream's fallback for a tab whose title has not arrived yet
    // (`tab-title.ts:4-6` via `titlebar.tsx:430-433`).
    val sessionLabel = stringResource(R.string.oc_tab_session)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(c.bgDeep)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .height(BAR_HEIGHT)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TAB_GAP),
    ) {
        // `home.toggle` — the Home button (`grid-plus`), tinted like the active
        // tab while Home is on screen.
        TabIconButton(
            iconRes = R.drawable.ic_oc_grid,
            description = stringResource(R.string.oc_home_title),
            tint = if (sessionActive) c.iconMuted else c.iconBase,
            active = !sessionActive,
            onClick = onHome,
        )
        if (session != null) {
            // The separator only shows next to an *inactive* slot (§9b.2).
            if (!sessionActive) TabSeparator()
            SessionTab(
                tab = session.copy(title = SessionTabs.displayTitle(session.title, sessionLabel)),
                active = sessionActive,
                // WAVE 2.3b: the slot is the drawer trigger (`MobileDrawerTrigger`
                // `data-slot="mobile-tabs-trigger"`), not a direct open.
                onClick = { drawerOpen = true },
                onClose = { onCloseSession(session) },
                modifier = Modifier.widthIn(max = TAB_MAX_WIDTH),
            )
        }
        TabIconButton(
            iconRes = R.drawable.ic_oc_plus,
            description = stringResource(R.string.oc_new_session),
            tint = c.iconMuted,
            active = false,
            onClick = onNewSession,
        )
    }

    MobileTabsDrawer(
        open = drawerOpen,
        onDismissRequest = { drawerOpen = false },
        sessions = sessions,
        activeSessionId = activeSessionId,
        sessionLabel = sessionLabel,
        onOpenSession = {
            drawerOpen = false
            onOpenSession(it)
        },
        onCloseSession = onCloseSession,
        onNewSession = {
            drawerOpen = false
            onNewSession()
        },
        onHome = {
            drawerOpen = false
            onHome()
        },
        onOpenSettings = {
            drawerOpen = false
            onOpenSettings()
        },
        onOpenHelp = {
            drawerOpen = false
            onOpenHelp()
        },
    )
}

/**
 * `[data-titlebar-tab-slot]:not(:first-child):not([data-active="true"])::before`
 * — a 1.5 × 12 dp pill between two inactive slots.
 *
 * `internal` (not file-private): [DesktopTabStrip] reuses this pill, [SessionTab]
 * and [TabIconButton] so the top and bottom placements stay one drawing.
 */
@Composable
internal fun TabSeparator() {
    Box(
        Modifier
            .width(1.5.dp)
            .height(12.dp)
            .clip(RoundedCornerShape(percent = 50))
            .background(OcTheme.colors.layer03),
    )
}

/** One tab slot; shared by [BottomTabStrip] and [DesktopTabStrip]. */
@Composable
internal fun SessionTab(
    tab: SessionTabRef,
    active: Boolean,
    onClick: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = OcTheme.colors
    val shape = OcTheme.shapes.md
    // Upstream's fallback for a tab whose title has not arrived yet
    // (`tab-title.ts:4-6` via `titlebar.tsx:430-433`): the desktop strip resolves
    // it at the call site; the drawer trigger above does the same, so this
    // default only fires for callers that pass a raw tab straight in.
    val title = SessionTabs.displayTitle(tab.title, stringResource(R.string.oc_tab_session))
    Row(
        modifier = modifier
            .height(TAB_HEIGHT)
            .clip(shape)
            .background(Color.Transparent)
            .clickable(role = Role.Tab, onClick = onClick)
            .padding(start = 6.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
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
                .clip(shape)
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

/** The Home / `+` square button; shared by both tab strips. */
@Composable
internal fun TabIconButton(
    iconRes: Int,
    description: String,
    tint: Color,
    active: Boolean,
    onClick: () -> Unit,
) {
    val shape = OcTheme.shapes.md
    Box(
        modifier = Modifier
            .size(TAB_HEIGHT)
            .clip(shape)
            .background(if (active) OcTheme.colors.layer02 else Color.Transparent)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = description,
            tint = tint,
            modifier = Modifier.size(16.dp),
        )
    }
}

/** `titlebar-v2` bar height (`h-9` = 36 dp). */
internal val BAR_HEIGHT = 36.dp

/** Tab item height (`h-7` = 28 dp), also the Home/`+` button size. */
internal val TAB_HEIGHT = 28.dp

/** `gap-1.5` = 6 dp. */
internal val TAB_GAP = 6.dp

/** `w-56` = 224 dp slot ceiling. */
internal val TAB_MAX_WIDTH = 224.dp
