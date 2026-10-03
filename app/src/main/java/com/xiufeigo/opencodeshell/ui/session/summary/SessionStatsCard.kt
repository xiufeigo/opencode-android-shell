package com.xiufeigo.opencodeshell.ui.session.summary

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.ServerConfigStore
import com.xiufeigo.opencodeshell.ui.theme.OcTheme
import com.xiufeigo.opencodeshell.v2.SessionStatsInfo
import com.xiufeigo.opencodeshell.v2.V2Api
import com.xiufeigo.opencodeshell.v2.sessionStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One-shot `GET /api/experimental/session/stats` ([V2Api.sessionStats]) for the
 * summary card. `null` = not loaded or failed — the card shows its empty copy
 * instead of inventing numbers. `tools=detail` so the card can show the
 * per-tool usage alongside the totals (`session-stats.ts:43`).
 */
@Composable
fun rememberSessionStats(): SessionStatsInfo? {
    val context = LocalContext.current
    val serverStore = remember(context) { ServerConfigStore(context).also { it.restore() } }
    var stats by remember { mutableStateOf<SessionStatsInfo?>(null) }
    LaunchedEffect(serverStore) {
        stats = withContext(Dispatchers.IO) {
            val server = serverStore.server ?: return@withContext null
            val api = V2Api.forServer(server.url, server.username, server.password)
                ?: return@withContext null
            runCatching { api.sessionStats(tools = "detail") }.getOrNull()
        }
    }
    return stats
}

/**
 * 「统计」 card of the summary panel — the `SessionStats.Info` projection of
 * [SessionStatsModel] (`experimental/session/stats`; upstream ships no screen,
 * so the row layout is the summary panel's own card style). [onReload] adds the
 * `location.reload` action ([com.xiufeigo.opencodeshell.v2.locationReload]) on
 * the trailing edge of the header: a manual rebuild of every loaded location
 * after a config change (`protocol/src/groups/location.ts:51-59`).
 */
@Composable
fun SessionStatsCard(
    mobile: Boolean,
    onReload: (() -> Unit)?,
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = false,
    stats: SessionStatsInfo? = null,
) {
    val c = OcTheme.colors
    val shape = RoundedCornerShape(6.dp)
    var expanded by remember(initiallyExpanded) { mutableStateOf(initiallyExpanded) }
    val snapshot = remember(stats) { stats?.let(SessionStatsModel::snapshot) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.bgBase)
            .border(0.5.dp, c.borderMuted, shape)
            .padding(2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BoxHeader(
                title = stringResource(R.string.oc_summary_stats_title),
                expanded = expanded,
                onToggle = { expanded = !expanded },
                rowHeight = if (mobile) 44.dp else 32.dp,
                modifier = Modifier.weight(1f),
            )
            if (onReload != null) {
                ReloadButton(
                    label = stringResource(R.string.oc_summary_reload),
                    rowHeight = if (mobile) 44.dp else 32.dp,
                    onClick = onReload,
                )
            }
        }
        if (!expanded) return@Column
        if (snapshot == null) {
            Text(
                text = stringResource(R.string.oc_summary_stats_empty),
                color = c.textMuted,
                fontSize = 13.sp,
                lineHeight = 20.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
            return@Column
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = STATS_MAX_HEIGHT)
                .verticalScroll(rememberScrollState()),
        ) {
            snapshot.rows.forEach { row -> StatsRow(row, mobile) }
            snapshot.tools?.let { tools ->
                StatsRow(
                    SessionStatsModel.Row(SessionStatsModel.Kind.STEPS, tools.calls),
                    mobile,
                    labelOverride = R.string.oc_summary_stats_tool_calls,
                )
                StatsRow(
                    SessionStatsModel.Row(SessionStatsModel.Kind.STEPS, tools.failed),
                    mobile,
                    labelOverride = R.string.oc_summary_stats_tool_failed,
                )
            }
            snapshot.models.forEach { model ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(if (mobile) 44.dp else 32.dp)
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = model.label,
                        color = c.textMuted,
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = model.steps + " · " + model.cost,
                        color = c.textBase,
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatsRow(
    row: SessionStatsModel.Row,
    mobile: Boolean,
    labelOverride: Int? = null,
) {
    val c = OcTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (mobile) 44.dp else 32.dp)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(labelOverride ?: labelRes(row.kind)),
            color = c.textMuted,
            fontSize = 13.sp,
            lineHeight = 20.sp,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = row.value,
            color = c.textBase,
            fontSize = 13.sp,
            lineHeight = 20.sp,
            maxLines = 1,
        )
    }
}

@Composable
private fun labelRes(kind: SessionStatsModel.Kind): Int = when (kind) {
    SessionStatsModel.Kind.SESSIONS -> R.string.oc_summary_stats_sessions
    SessionStatsModel.Kind.SUBAGENTS -> R.string.oc_summary_stats_subagents
    SessionStatsModel.Kind.PROMPTS -> R.string.oc_summary_stats_prompts
    SessionStatsModel.Kind.STEPS -> R.string.oc_summary_stats_steps
    SessionStatsModel.Kind.INPUT_TOKENS -> R.string.oc_context_stats_input_tokens
    SessionStatsModel.Kind.OUTPUT_TOKENS -> R.string.oc_context_stats_output_tokens
    SessionStatsModel.Kind.REASONING_TOKENS -> R.string.oc_context_stats_reasoning_tokens
    SessionStatsModel.Kind.CACHE_TOKENS -> R.string.oc_context_stats_cache_tokens
    SessionStatsModel.Kind.COST -> R.string.oc_context_stats_total_cost
    SessionStatsModel.Kind.ACTIVE_DAYS -> R.string.oc_summary_stats_active_days
    SessionStatsModel.Kind.STREAK -> R.string.oc_summary_stats_streak
}

/** Header row of the card: chevron + title, same treatment as [CardHeader]. */
@Composable
private fun BoxHeader(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    rowHeight: Dp,
    modifier: Modifier = Modifier,
) {
    val c = OcTheme.colors
    Row(
        modifier = modifier
            .height(rowHeight)
            .clip(RoundedCornerShape(4.dp))
            .clickable(role = Role.Button, onClick = onToggle)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (expanded) "\u25be" else "\u25b8",
            color = c.textMuted,
            fontSize = 12.sp,
            lineHeight = 16.sp,
        )
        Text(
            text = title,
            color = c.textBase,
            fontSize = 13.sp,
            fontWeight = FontWeight(530),
            lineHeight = 20.sp,
            maxLines = 1,
        )
    }
}

/** Ghost action on the card header (`location.reload` — destructive-ish rebuild). */
@Composable
private fun ReloadButton(label: String, rowHeight: Dp, onClick: () -> Unit) {
    val c = OcTheme.colors
    Row(
        modifier = Modifier
            .height(rowHeight)
            .clip(RoundedCornerShape(4.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = c.textFaint,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            maxLines = 1,
        )
    }
}

/** Stats list height cap — the card scrolls its own rows. */
private val STATS_MAX_HEIGHT = 360.dp
