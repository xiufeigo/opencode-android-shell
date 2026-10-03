package com.xiufeigo.opencodeshell.ui.session.review

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.ui.session.drawer.MobileColors
import com.xiufeigo.opencodeshell.ui.session.drawer.MobileForm
import com.xiufeigo.opencodeshell.ui.session.drawer.isDarkSurface
import com.xiufeigo.opencodeshell.ui.theme.OcTheme

/**
 * The mobile session view: four **full-width** tabs —
 * Session / Changes / Files / Terminal — replacing the desktop review sidebar
 * (`_upstream/packages/app/src/session/review/view.tsx:23-117`
 * `SessionMobileViewTabs`, mounted by `session/screen.tsx:191-242,290`, which is
 * also where the `terminal` entry opens the panel instead of switching content).
 *
 * Geometry follows the same upstream block:
 * - one strip, `!h-9` → [MobileForm.TAB_STRIP_HEIGHT_DP] dp, each trigger
 *   `min-w-0 flex-1` + `w-full justify-center` (an equal four-way split, not a
 *   scroller), and a 1 px `border-base` line under the strip (`view.tsx:41`
 *   `before:…before:h-px before:bg-…border-base`);
 * - labels: `session.tab.session` / `session.review.change.other` (`Changes`) /
 *   `session.tab.files` / `terminal.title` (`view.tsx:54-60`);
 * - the active trigger is the project's existing line-tab treatment
 *   (13/500 label in `text-base` over a 2 dp accent underline), shared with
 *   [ReviewTabButton] so the mobile strip and the panel tabs cannot drift.
 * - the `⋮` at the strip's end (`view.tsx:66-102`, `class="mx-1.5 shrink-0"`)
 *   opens Usage / Session details. `usage` is deliberately **not** a fifth tab.
 */
@Composable
fun MobileSessionTabs(
    current: MobileForm.Tab,
    onSelect: (MobileForm.Tab) -> Unit,
    modifier: Modifier = Modifier,
    /** `⋮` → Usage (`onSelect("usage")` upstream). */
    onOpenUsage: () -> Unit = {},
    /** `⋮` → Session details; disabled while Android has no summary panel. */
    onOpenSummary: (() -> Unit)? = null,
) {
    val c = OcTheme.colors
    val stripLabel = stringResource(R.string.oc_mobile_view_select)
    var menuOpen by remember { mutableStateOf(false) }
    // Silent-prompt guard: IME/keyboard focus leaves with the tab. Without
    // this the previous tab's editor keeps the focus it grabbed on entry
    // (the composer's B7 auto-focus), keeps receiving injected keys while
    // hidden, and Enter submits a prompt the user never wrote — measured on
    // the Terminal tab (adb typing + KEYCODE_ENTER → session reply).
    val focusManager = LocalFocusManager.current
    val select: (MobileForm.Tab) -> Unit = { tab ->
        focusManager.clearFocus()
        onSelect(tab)
    }
    val openUsage: () -> Unit = {
        focusManager.clearFocus()
        menuOpen = false
        onOpenUsage()
    }
    val openSummary: () -> Unit = {
        focusManager.clearFocus()
        menuOpen = false
        onOpenSummary?.invoke()
    }
    // Hoisted: `semantics {}` is not a composable scope. The label rides the
    // clickable Box, not the `Icon` inside it.
    val moreLabel = stringResource(R.string.oc_more_options)
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(MobileForm.TAB_STRIP_HEIGHT_DP.dp)
                .semantics { contentDescription = stripLabel },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(modifier = Modifier.weight(1f)) {
                MobileForm.TABS.forEach { tab ->
                    MobileTabButton(
                        label = stringResource(tab.labelRes()),
                        selected = tab == current,
                        onClick = { select(tab) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Box {
                Box(
                    modifier = Modifier
                        .padding(horizontal = 6.dp)
                        .size(28.dp)
                        .clip(OcTheme.shapes.sm)
                        .clickable(role = Role.Button) { menuOpen = true }
                        .semantics { contentDescription = moreLabel },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_oc_dots),
                        contentDescription = null,
                        tint = if (menuOpen) c.iconAccent else c.iconMuted,
                        modifier = Modifier.size(14.dp),
                    )
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    // v11 dark-surface sweep (see `DropdownMenuDarkSurfaceTest`):
                    // the M3 default `surfaceContainer` is a light translucent
                    // tint the shell never re-themes, so these rows landed on a
                    // near-white panel in the dark theme.
                    shape = RoundedCornerShape(6.dp),
                    containerColor = c.layer01,
                    tonalElevation = 0.dp,
                    border = BorderStroke(0.5.dp, c.borderMuted),
                ) {
                    MobileViewMenuItem(
                        label = stringResource(R.string.oc_mobile_usage),
                        enabled = true,
                        onClick = openUsage,
                    )
                    MobileViewMenuItem(
                        label = stringResource(R.string.oc_mobile_summary),
                        enabled = onOpenSummary != null,
                        onClick = openSummary,
                    )
                }
            }
        }
        // `before:h-px before:bg-v2-border-border-base` on the upstream strip
        // (`MobileColors` carries the token in the correct ARGB order — the
        // `OcColors` value parses to alpha 0, see `MobileColors` KDoc).
        Box(
            Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .background(MobileColors.hairline(c.isDarkSurface())),
        )
    }
}

/** 13/530 menu row; disabled rows keep the upstream `opacity: .5` look. */
@Composable
private fun MobileViewMenuItem(
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

/** Upstream `view.tsx:54-60` label keys → this project's strings. */
private fun MobileForm.Tab.labelRes(): Int = when (this) {
    MobileForm.Tab.SESSION -> R.string.oc_mobile_tab_session
    MobileForm.Tab.CHANGES -> R.string.oc_review_tab_changes
    MobileForm.Tab.FILES -> R.string.oc_review_tab_files
    MobileForm.Tab.TERMINAL -> R.string.oc_terminal_title
}

@Composable
private fun MobileTabButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = OcTheme.colors
    Column(
        modifier = modifier
            .clip(OcTheme.shapes.sm)
            .clickable(role = Role.Tab, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = label,
            color = if (selected) c.textBase else c.textMuted,
            fontSize = 13.sp,
            fontWeight = FontWeight.W500,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp, bottom = 6.dp),
        )
        Box(
            Modifier
                .width(24.dp)
                .height(2.dp)
                .background(if (selected) c.accent else Color.Transparent),
        )
    }
}
