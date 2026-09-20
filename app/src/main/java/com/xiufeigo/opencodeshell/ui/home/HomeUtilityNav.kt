package com.xiufeigo.opencodeshell.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.ui.theme.OcTheme
import kotlinx.coroutines.delay

/**
 * `HomeUtilityNav` (`research/02` §1.4): two `HomeProjectNavButton` rows —
 * Settings (`settings-gear`) and Help (`help`) — rendered **twice** upstream:
 * `home-projects-view` (`hidden lg:flex`, bottom of the left column) and
 * `home.tsx` (`flex lg:hidden`, bottom of the mobile single column).
 *
 * The container is `flex-col gap-1 pr-3` → 4 dp between rows, 12 dp right
 * padding; each row is `h-7` = 28 dp / radius 6 / `px-1.5` = 6 dp /
 * `gap-2` = 8 dp, label 13 px/440 `text-faint`, icon `icon-muted` at 16 dp.
 *
 * Settings navigates to the Settings screen (M4 turns that route into the
 * `dialog-v2 variant=settings` host — the callback lives in `OcApp`, so Home
 * needs no change). Help's dialog is not in this milestone, so the row reports
 * that inline instead of failing silently.
 */
@Composable
internal fun HomeUtilityNav(
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = OcTheme.colors
    var helpHint by remember { mutableStateOf(false) }
    LaunchedEffect(helpHint) {
        if (helpHint) {
            delay(HINT_TIMEOUT_MS)
            helpHint = false
        }
    }

    Column(modifier = modifier.fillMaxWidth().padding(end = 12.dp)) {
        if (helpHint) {
            Text(
                text = stringResource(R.string.oc_home_help_soon),
                color = c.textFaint,
                fontSize = 11.sp,
                modifier = Modifier.padding(start = 6.dp, bottom = 4.dp),
            )
        }
        NavRow(
            iconRes = R.drawable.ic_oc_gear,
            label = stringResource(R.string.oc_home_settings),
            onClick = onOpenSettings,
        )
        Spacer(Modifier.height(4.dp))
        NavRow(
            iconRes = R.drawable.ic_oc_help,
            label = stringResource(R.string.oc_home_help),
            onClick = { helpHint = true },
        )
    }
}

@Composable
private fun NavRow(iconRes: Int, label: String, onClick: () -> Unit) {
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
        Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = c.iconMuted,
                modifier = Modifier.size(16.dp),
            )
        }
        Text(text = label, color = c.textFaint, fontSize = 13.sp, maxLines = 1)
    }
}

/** `h-7` = 28 dp. */
private val ROW_HEIGHT = 28.dp

private const val HINT_TIMEOUT_MS = 4_000L
