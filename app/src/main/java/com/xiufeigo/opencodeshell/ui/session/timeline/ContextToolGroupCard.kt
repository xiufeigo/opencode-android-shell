package com.xiufeigo.opencodeshell.ui.session.timeline

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiufeigo.opencodeshell.ChatStore
import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.ui.components.OcCountItem
import com.xiufeigo.opencodeshell.ui.components.TextShimmer
import com.xiufeigo.opencodeshell.ui.theme.LocalOcReducedMotion
import com.xiufeigo.opencodeshell.ui.theme.OcMotion
import com.xiufeigo.opencodeshell.ui.theme.OcTheme

/**
 * Grouped context tools (`research/02` §3.7) — consecutive
 * `read | glob | grep | list` parts collapse into one row:
 *
 * ```
 * [data-component=context-tool-group-trigger]
 * ├─ span[context-tool-group-title]    text-14-medium
 * │   ├─ span[context-tool-group-label]    "Exploring" (busy) / "Explored"
 * │   └─ span[context-tool-group-summary]   "3 reads, 2 searches, 1 list"
 * └─ Collapsible.Arrow
 * [data-component=context-tool-group-list]  padding-left:12px; gap:4px
 * ```
 *
 * Default collapsed (`localOpen = false` upstream); the open flag is part of
 * the same screen-level `toolOpen` map as the tool cards, keyed by the row key.
 * The expanded list holds **plain, non-expandable** rows (title + subtitle +
 * args), which is what upstream renders there.
 *
 * M6: the `tool-status-title` swap and the 480 ms `tool-count-summary`
 * "gather" of the count labels are now implemented (`research/03` §3.3) — see
 * [ToolStatusTitleSwap] and [ContextSummary]. The chevron rotation and the
 * collapse/expand spring were already there (`research/03` §4).
 */
@Composable
fun ContextToolGroupRow(
    group: TimelineGrouping.Row.Context,
    expanded: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = OcTheme.colors
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "ctx-group-chevron")
    val summary = TimelineGrouping.summary(group.parts)

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(OcTheme.shapes.md)
                .clickable(role = Role.Button) { onToggle(expanded) }
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // M6: `tool-status-title` — "Exploring" shimmers while the group is
            // busy and cross-fades with "Explored" through a 2px*0.45 blur and a
            // `.03em` lift (`research/03` §3.3).
            ToolStatusTitleSwap(busy = group.busy)
            if (!summary.isEmpty) {
                Spacer(Modifier.width(6.dp))
                ContextSummary(summary)
            }
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(6.dp))
            Icon(
                painter = painterResource(R.drawable.ic_oc_chevron_down),
                contentDescription = stringResource(R.string.oc_toggle_tool),
                tint = c.textFaint,
                modifier = Modifier.size(16.dp).rotate(rotation),
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(animationSpec = OcMotion.expand) + fadeIn(OcMotion.fade),
            exit = shrinkVertically(animationSpec = OcMotion.expand) + fadeOut(OcMotion.fade),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, top = 2.dp, bottom = 2.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                group.parts.forEach { part -> ContextToolLine(part) }
            }
        }
    }
}

/**
 * One tool inside a group: icon + title + subtitle, **no** chevron and no
 * expandable body (`[data-component=tool-trigger]` rendered as flat text
 * upstream).
 */
@Composable
private fun ContextToolLine(part: ChatStore.UiPart) {
    val c = OcTheme.colors
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            painter = painterResource(toolIconRes(ToolPresentation.icon(part.toolName))),
            contentDescription = null,
            tint = c.iconMuted,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = ToolPresentation.titleRes(part.toolName)?.let { stringResource(it) }
                ?: ToolPresentation.title(part.toolName),
            color = c.textMuted,
            fontSize = 13.sp,
            maxLines = 1,
        )
        val subtitle = ToolPresentation.subtitle(part, stringResource(R.string.oc_tool_error))
        if (!subtitle.isNullOrEmpty()) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = subtitle,
                color = c.textFaint,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * `{{count}} read/reads, {{count}} search/searches, {{count}} list/lists`
 * (`research/02` §3.7). Zero buckets are skipped, exactly like the upstream
 * `AnimatedCountList` (which mounts an item only when its count is > 0).
 *
 * M6: every bucket (and every comma, the `[data-slot=tool-count-summary-prefix]`
 * of the web) is an [OcCountItem], so a bucket that goes from zero to one
 * gathers in over the 480 ms `grid 0fr → 1fr` + blur + micro-lift transition
 * (`research/03` §3.3). Buckets that are already on screen when the row is
 * first composed render immediately — [knownKeys] starts out holding them, so a
 * group scrolled into view does not replay its counters.
 */
@Composable
private fun ContextSummary(summary: TimelineGrouping.Summary) {
    val c = OcTheme.colors
    val buckets = buildList {
        if (summary.read > 0) add("read")
        if (summary.search > 0) add("search")
        if (summary.list > 0) add("list")
    }
    // `SideEffect` (not `LaunchedEffect`) so the very next frame is the one that
    // animates the newcomer in; the structural-equality state policy keeps the
    // write from looping.
    var knownKeys by remember { mutableStateOf(buckets.toSet()) }
    SideEffect { knownKeys = buckets.toSet() }

    Row(verticalAlignment = Alignment.CenterVertically) {
        buckets.forEachIndexed { index, key ->
            OcCountItem(visible = key in knownKeys) {
                Text(
                    text = contextBucketLabel(key, summary),
                    color = c.textBase,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (index != buckets.lastIndex) {
                OcCountItem(visible = key in knownKeys) {
                    Text(text = ", ", color = c.textBase, fontSize = 14.sp, maxLines = 1)
                }
            }
        }
    }
}

/** `pluralStringResource` for the bucket, so the label matches the count. */
@Composable
private fun contextBucketLabel(key: String, summary: TimelineGrouping.Summary): String = when (key) {
    "read" -> pluralStringResource(R.plurals.oc_context_read, summary.read, summary.read)
    "search" -> pluralStringResource(R.plurals.oc_context_search, summary.search, summary.search)
    else -> pluralStringResource(R.plurals.oc_context_list, summary.list, summary.list)
}

/**
 * `[data-component=tool-status-title]` (`research/03` §3.3): "Exploring" while
 * the turn works, "Explored" once it settles.
 *
 * The two slots are stacked and cross-faded, exactly like the CSS: the hidden
 * one carries `opacity: 0`, a `blur(2px × .45)` and a `translateY(.03em)` lift,
 * while the visible one clears them. The active slot uses [TextShimmer], so the
 * busy label also sweeps (`research/03` §2.3) instead of merely pulsing.
 */
@Composable
private fun ToolStatusTitleSwap(busy: Boolean) {
    val reduced = LocalOcReducedMotion.current
    val progress by animateFloatAsState(
        targetValue = if (busy) 1f else 0f,
        animationSpec = tween(
            durationMillis = OcMotion.durationMs(OcMotion.ToolCount.STATUS_SWAP_MS, reduced),
            easing = OcMotion.softSpringEasing,
        ),
        label = "tool-status-swap",
    )
    val em = with(LocalDensity.current) { 14.sp.toPx() }

    Box {
        StatusTitleSlot(text = stringResource(R.string.oc_context_explored), progress = 1f - progress, em = em)
        StatusTitleSlot(
            text = stringResource(R.string.oc_context_exploring),
            progress = progress,
            em = em,
            shimmer = true,
        )
    }
}

@Composable
private fun StatusTitleSlot(
    text: String,
    progress: Float,
    em: Float,
    shimmer: Boolean = false,
) {
    Box(
        Modifier
            .graphicsLayer {
                alpha = progress
                translationY = (1f - progress) * OcMotion.ToolCount.STATUS_TRANSLATE_EM * em
            }
            .blur(
                radius = (
                    (1f - progress) * OcMotion.ToolCount.BLUR_DP * OcMotion.ToolCount.STATUS_BLUR_FACTOR
                    ).dp,
            ),
    ) {
        if (shimmer) {
            TextShimmer(
                text = text,
                active = progress > 0.5f,
                baseColor = OcTheme.colors.textBase,
                fontSize = 14.sp,
                fontWeight = FontWeight.W500,
            )
        } else {
            Text(
                text = text,
                color = OcTheme.colors.textBase,
                fontSize = 14.sp,
                fontWeight = FontWeight.W500,
                maxLines = 1,
            )
        }
    }
}
