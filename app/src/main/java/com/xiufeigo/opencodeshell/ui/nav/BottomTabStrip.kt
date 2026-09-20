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
 * Faithful deviations: the real strip is a scrollable multi-tab list with a
 * drag-reorder + hover-preview model (§9b.5/§9b.6) — M3 keeps the app-level tab
 * store (`context/tabs.tsx`) out of scope and shows the one session the shell
 * has open. Home is a *button* of the bar, not a tab (upstream: `Icon
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
) {
    val c = OcTheme.colors
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
                tab = session,
                active = sessionActive,
                onClick = { onOpenSession(session) },
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
}

/**
 * `[data-titlebar-tab-slot]:not(:first-child):not([data-active="true"])::before`
 * — a 1.5 × 12 dp pill between two inactive slots.
 */
@Composable
private fun TabSeparator() {
    Box(
        Modifier
            .width(1.5.dp)
            .height(12.dp)
            .clip(RoundedCornerShape(percent = 50))
            .background(OcTheme.colors.layer03),
    )
}

@Composable
private fun SessionTab(
    tab: SessionTabRef,
    active: Boolean,
    onClick: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = OcTheme.colors
    val shape = OcTheme.shapes.md
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
            text = tab.title ?: stringResource(R.string.oc_new_session),
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

@Composable
private fun TabIconButton(
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
private val BAR_HEIGHT = 36.dp

/** Tab item height (`h-7` = 28 dp), also the Home/`+` button size. */
private val TAB_HEIGHT = 28.dp

/** `gap-1.5` = 6 dp. */
private val TAB_GAP = 6.dp

/** `w-56` = 224 dp slot ceiling. */
private val TAB_MAX_WIDTH = 224.dp
