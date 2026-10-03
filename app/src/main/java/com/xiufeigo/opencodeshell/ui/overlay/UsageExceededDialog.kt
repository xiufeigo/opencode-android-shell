package com.xiufeigo.opencodeshell.ui.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiufeigo.opencodeshell.ChatStore
import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.ui.theme.OcTheme
import com.xiufeigo.opencodeshell.ui.theme.ocShadow

// Usage-exceeded upsell (`_upstream/packages/app/src/session/
// usage-exceeded-dialogs.tsx` + `providers/connect/usage-exceeded.tsx`
// `DialogUsageExceeded`): title + description header, footer row with a ghost
// "don't show again" and a contrast primary action. The server copy
// ([ChatStore.UsageUpsell.message]) renders verbatim as the body; the title /
// button labels come from resources branched on `reason` — the server copy
// stays English-only, like upstream's non-English path. The `go-upsell`
// persistence lives here too ([UsageUpsellGate]): first show stamps
// `lastSeenAt` (same reason stays quiet for 24 h), "don't show again" stamps
// `dontShow`, and the primary button opens the Go `action.link`
// ([ChatStore.UsageUpsell.link]) in the system browser before dismissing.
//
// A bare card on purpose: [DialogHost] paints the scrim and owns dismissal
// (scrim tap / BACK), and its measured-viewport overflow fallback caps + scrolls
// this content — so the card keeps a plain bounded column (width cap, inner
// scroll) and adds no self-measuring layout that could fight the fallback.
@Composable
fun UsageExceededDialog(
    upsell: ChatStore.UsageUpsell,
    onDismiss: () -> Unit,
) {
    val c = OcTheme.colors
    val shape = OcTheme.shapes.xl
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    val prefs = remember(context) { UsageUpsellGate.prefsOf(context) }
    // Upstream records `lastSeenAt` in `onClose` (both buttons); recording on
    // first show additionally covers the scrim / BACK dismissal, which
    // bypasses both buttons and would otherwise re-pop on the next
    // qualifying frame. The screen's mount gate ([UsageUpsellGate.shouldShow])
    // reads the same prefs, so this write snoozes the reason for 24 h.
    LaunchedEffect(upsell.reason) {
        UsageUpsellGate.markShown(prefs, upsell.reason)
    }
    val isFreeTier = upsell.reason == ChatStore.UsageUpsell.REASON_FREE_TIER_LIMIT
    val isRateLimit = upsell.reason == ChatStore.UsageUpsell.REASON_ACCOUNT_RATE_LIMIT
    val titleRes = when {
        isFreeTier -> R.string.oc_usage_exceeded_free_tier_title
        isRateLimit -> R.string.oc_usage_exceeded_rate_limit_title
        else -> R.string.oc_usage_exceeded_quota_title
    }
    val fallbackBodyRes = when {
        isFreeTier -> R.string.oc_usage_exceeded_free_tier_description
        isRateLimit -> R.string.oc_usage_exceeded_rate_limit_description
        else -> R.string.oc_usage_exceeded_quota_description
    }
    val primaryRes = when {
        isFreeTier -> R.string.oc_usage_exceeded_free_tier_action
        isRateLimit -> R.string.oc_usage_exceeded_rate_limit_action
        else -> R.string.oc_usage_exceeded_close
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 320.dp)
            .clip(shape)
            .background(c.bgBase)
            .border(0.5.dp, c.borderBase, shape)
            .ocShadow(OcTheme.elevation.floating, shape)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text(
            text = stringResource(titleRes),
            color = c.textBase,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            lineHeight = 21.sp,
        )
        Text(
            text = upsell.message?.takeIf { it.isNotBlank() } ?: stringResource(fallbackBodyRes),
            color = c.textMuted,
            fontSize = 13.sp,
            lineHeight = 20.sp,
            modifier = Modifier.padding(top = 8.dp),
        )
        upsell.retryAfter?.let { retryAfter ->
            Text(
                text = stringResource(R.string.oc_usage_exceeded_retry_after, retryAfter),
                color = c.textFaint,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            UsageExceededButton(
                label = stringResource(R.string.oc_usage_exceeded_dont_show_again),
                onClick = {
                    UsageUpsellGate.markDontShow(prefs, upsell.reason)
                    onDismiss()
                },
            )
            Spacer(Modifier.width(8.dp))
            UsageExceededButton(
                label = stringResource(primaryRes),
                primary = true,
                onClick = {
                    // Upstream `runAction`: open the Go action link in the
                    // system browser, then close. A null/blank link (the
                    // generic quota bucket) is a plain close.
                    upsell.link?.takeIf { it.isNotBlank() }?.let { link ->
                        runCatching { uriHandler.openUri(link) }
                    }
                    onDismiss()
                },
            )
        }
    }
}

// button-v2 metrics: h 28, radius 6, padding-x 12, 13/530 — same as Home's
// delete dialog; primary is the `contrast` solid (`inverse` + `textInverse`
// is the paired contrast set in both schemes).
@Composable
private fun UsageExceededButton(
    label: String,
    onClick: () -> Unit,
    primary: Boolean = false,
) {
    val c = OcTheme.colors
    val shape = OcTheme.shapes.md
    Box(
        modifier = Modifier
            .height(28.dp)
            .clip(shape)
            .background(if (primary) c.inverse else Color.Transparent)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (primary) c.textInverse else c.textBase,
            fontSize = 13.sp,
            fontWeight = FontWeight(530),
            lineHeight = 20.sp,
        )
    }
}
