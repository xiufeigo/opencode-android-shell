package com.xiufeigo.opencodeshell.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.ui.theme.OcTheme

/**
 * W7: the **desktop** session tab bar (`research/02` §9b.1) — the top strip the
 * shell was missing on wide screens. This is the `TitlebarTabStrip` half of
 * upstream's titlebar, plus the two icons that frame it:
 *
 * ```
 * [home icon] [tab] [tab] … [+]
 * ```
 *
 * Transcribed from `_upstream/packages/app/src/shell/titlebar/titlebar.tsx:441-704`:
 * the bar is `h-9` = 36 dp, `bg-v2-background-bg-deep`, `gap-1.5` = 6 dp, and
 * inside it the horizontal order is the Home button (`grid-plus`, `aria-label`
 * `home.title`, `data-state="pressed"` while Home is on screen, `:324-350`) →
 * `TitlebarTabStrip` (`:599-611`) → the `+` button (`aria-label`
 * `command.session.new`, `:612-630`). The strip itself
 * (`tab-strip.tsx:277-410`) is a **horizontally scrollable** row of
 * `w-56 max-w-56` slots (`:284-290`), so a long tab list scrolls instead of
 * pushing the `+` off the bar.
 *
 * Two upstream behaviours this placement inherits on purpose:
 *
 * * **It stays visible on Home.** Upstream renders the titlebar on every route;
 *   Home is a *button*, not a tab, and no tab is active while Home content is
 *   shown (`titlebar.tsx:344`: `state={route === "home" ? "pressed" : undefined}`).
 *   [activeSessionId] is therefore `null` off a session route and the Home
 *   button paints as the pressed one — the same polarity as
 *   `BottomTabStrip(sessionActive = …)`.
 * * **The bottom placement is mobile-only.** `titlebar.tsx:64-65`:
 *   `bottom = mobile() && mobileTitlebarPosition() === "bottom"`, so on a desktop
 *   width the bar belongs on top; `AppScaffold` renders this strip there and the
 *   `appearance.tabLayout` bottom bar only below the breakpoint.
 *
 * Faithful deviations (§9b.1/§9b.2): the tabs do not drag-reorder, there is no
 * hover preview popover (touch has no hover), no double-click rename and no
 * scroll-fade gradients; the `:not(:first-child)` separator pill and the 224 dp
 * slot ceiling are kept (§9b.2), and per-tab close uses
 * [SessionTabs.displayTitle] for the label rather than a second title path.
 *
 * The close semantics (which tab becomes active, when the shell returns Home) do
 * **not** live here: both strips close through [SessionTabs.close].
 *
 * @param sessions the shell's open-session store, in open order.
 * @param activeSessionId the route's session id, or `null` off a session route.
 * @param onHome `home.toggle` — the leading icon returns to Home.
 * @param onOpenSession a tap on a tab switches to that session.
 * @param onCloseSession a tap on a tab's ✕ closes it.
 * @param onNewSession the trailing `+` — the app's new-session draft route.
 */
@Composable
fun DesktopTabStrip(
    sessions: List<SessionTabRef>,
    activeSessionId: String?,
    onHome: () -> Unit,
    onOpenSession: (SessionTabRef) -> Unit,
    onCloseSession: (SessionTabRef) -> Unit,
    onNewSession: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = OcTheme.colors
    val homeActive = activeSessionId == null
    // `session.tab.session` (`en.ts:740`) — upstream's fallback for a tab whose
    // title has not arrived yet and for the auto-generated session titles.
    val sessionLabel = stringResource(R.string.oc_tab_session)
    val scroll = rememberScrollState()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(c.bgDeep)
            // The bar paints behind the status bar itself (as the bottom strip
            // does with the navigation bar), so `AppScaffold` consumes these
            // insets for the nav content instead of letting it pad twice.
            .windowInsetsPadding(WindowInsets.statusBars)
            .height(BAR_HEIGHT)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TAB_GAP),
    ) {
        TabIconButton(
            iconRes = R.drawable.ic_oc_grid,
            description = stringResource(R.string.oc_home_title),
            tint = if (homeActive) c.iconBase else c.iconMuted,
            active = homeActive,
            onClick = onHome,
        )
        // `TitlebarTabStrip`: the scrolling list. `weight(1f, fill = false)` is
        // upstream's `min-w-0` + `overflow-x-auto` — the list takes what is left
        // after the two icons and scrolls inside it, but stays content-width when
        // it is short, so `+` trails the last tab instead of floating right.
        Row(
            modifier = Modifier
                .weight(1f, fill = false)
                .horizontalScroll(scroll),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(TAB_GAP),
        ) {
            sessions.forEachIndexed { index, tab ->
                val active = tab.id == activeSessionId
                // `:not(:first-child):not([data-active="true"])::before` — the
                // pill only sits between two non-active slots (§9b.2).
                if (index > 0 && !active) TabSeparator()
                SessionTab(
                    // Resolve the label here: `SessionTab` is shared with the
                    // bottom strip, which falls back to `oc_new_session`.
                    tab = tab.copy(title = SessionTabs.displayTitle(tab.title, sessionLabel)),
                    active = active,
                    onClick = { onOpenSession(tab) },
                    onClose = { onCloseSession(tab) },
                    modifier = Modifier.widthIn(max = TAB_MAX_WIDTH),
                )
            }
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
