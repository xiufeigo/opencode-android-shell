package com.xiufeigo.opencodeshell.ui.session.review

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.ui.session.context.SessionContextModel
import com.xiufeigo.opencodeshell.ui.session.context.SessionContextPanel
import com.xiufeigo.opencodeshell.ui.session.drawer.MobileColors
import com.xiufeigo.opencodeshell.ui.session.drawer.isDarkSurface
import com.xiufeigo.opencodeshell.ui.theme.OcTheme
import java.util.Locale
import kotlin.math.abs

/**
 * Usage view of the mobile session (`⋮` → `session.tab.usage`, upstream
 * `_upstream/packages/app/src/session/review/view.tsx:94` → `onSelect("usage")`
 * → `SessionContextTab`, `session/screen.tsx:307-309`).
 *
 * Upstream's mobile usage view **is** `SessionContextTab`, so below the three
 * headline figures of the context ring
 * (`session-context-usage.tsx:131-140` — context usage percent, tokens with the
 * model window when known, session cost, read from
 * `ChatViewModel.contextLimit` / `contextUsage` / `sessionCost`) the panel now
 * renders the full context tab: the sixteen stat rows, the system prompt and the
 * raw messages ([SessionContextPanel], data handed in by the screen). Those three
 * rows stay pinned while the tab body scrolls. It is a **view behind the ⋮ menu,
 * not a fifth tab**, matching upstream.
 *
 * @param snapshot B2 projection of the raw payloads; `null` = not loaded yet, and
 *   the tab body renders the sixteen rows with every value `—`.
 */
@Composable
fun MobileUsagePanel(
    percent: Int?,
    tokens: Long?,
    limit: Int?,
    cost: Double?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    snapshot: SessionContextModel.Snapshot? = null,
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
                .height(40.dp)
                .padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.oc_mobile_usage),
                color = c.textBase,
                fontSize = 13.sp,
                fontWeight = FontWeight(530),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            PanelIconButton(
                iconRes = R.drawable.ic_oc_close,
                contentDescription = stringResource(R.string.oc_common_close),
                onClick = onClose,
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .background(MobileColors.hairline(c.isDarkSurface())),
        )

        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            UsageRow(
                label = stringResource(R.string.oc_context_usage),
                value = MobileUsage.percent(percent),
            )
            UsageRow(
                label = stringResource(R.string.oc_meta_context_tokens),
                value = MobileUsage.tokens(tokens, limit),
            )
            UsageRow(
                label = stringResource(R.string.oc_meta_context_cost),
                value = MobileUsage.cost(cost),
            )
        }

        // The headline figures stay put; the context tab body takes the rest of
        // the view and scrolls on its own (`screen.tsx:307-309`).
        Box(
            Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .background(MobileColors.hairline(c.isDarkSurface())),
        )
        SessionContextPanel(
            snapshot = snapshot,
            mobile = true,
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
    }
}

/** Label left / value right, both 13 sp; the value is mono like the counters. */
@Composable
private fun UsageRow(label: String, value: String) {
    val c = OcTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = c.textMuted,
            fontSize = 13.sp,
            lineHeight = 16.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.padding(horizontal = 4.dp))
        Text(
            text = value,
            color = c.textBase,
            fontFamily = OcTheme.monoFont,
            fontSize = 13.sp,
            lineHeight = 16.sp,
            maxLines = 1,
        )
    }
}

/**
 * Value formatting for the usage rows — pure so `:app:testDebugUnitTest` can pin
 * it. Grouping is fixed to `Locale.US` (`,` separators) so the rendered digits
 * do not depend on the device locale; upstream formats through `Intl` with the
 * app locale, which is a **known deviation** recorded in the W4-C report.
 */
object MobileUsage {
    /** Upstream `session-context-usage.tsx:68-85` clamps to 0…100. */
    fun percent(value: Int?): String = value?.let { "${it.coerceIn(0, 100)}%" } ?: "—"

    /** `1,234` when the window is unknown, `1,234 / 200,000` when it is known. */
    fun tokens(total: Long?, limit: Int?): String {
        if (total == null) return "—"
        val shown = String.format(Locale.US, "%,d", abs(total))
        val signed = if (total < 0) "-$shown" else shown
        if (limit == null || limit <= 0) return signed
        return "$signed / " + String.format(Locale.US, "%,d", limit)
    }

    /** 4 decimals, `$`-prefixed; upstream shows the currency reported by the session. */
    fun cost(value: Double?): String =
        value?.let { String.format(Locale.US, "$%.4f", it) } ?: "—"
}
