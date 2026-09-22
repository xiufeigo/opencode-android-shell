package com.xiufeigo.opencodeshell.ui.session.summary

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.ui.theme.OcTheme

/**
 * Session summary panel (A1) — the popover body: a project card and a server
 * card, both built from [SessionSummaryModel] rows.
 *
 * Upstream (`session/summary/panel.tsx:44-142`) puts this in a `bottom-end`
 * popover anchored right under the session header, `width: 280px` (mobile:
 * full width, 44px rows), cards with `radius: 6px`, `bg-base` + raised
 * elevation, rows `min-height: 32px` / `padding: 4px 12px` / `gap: 8px` /
 * `13px` at `--line-height-base` (`summary.css:6-116,299-307`). The composable
 * therefore takes a [width]-agnostic [modifier] and a [mobile] flag instead of
 * knowing about the popover: the integrator decides where it mounts (popover on
 * wide screens, the mobile view's details tab on narrow ones, matching
 * `screen.tsx:191-240`).
 *
 * Wiring needs are listed in `research/wave6_summary.md`; the panel takes every
 * value as a parameter so it never reaches into a ViewModel.
 */
@Composable
fun SessionSummaryPanel(
    directory: String?,
    branch: String?,
    changedFiles: Int?,
    serverLabel: String?,
    serverUrl: String?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    additions: Int? = null,
    deletions: Int? = null,
    mobile: Boolean = false,
    /**
     * Initial project-card expansion. Upstream persists this in
     * `settings.sessionSummary.projectExpanded` (default `true`,
     * `summary/project-card.tsx:20-46`); the caller passes the stored value and
     * toggling stays local to the panel, so wiring the setting needs no change
     * here.
     */
    initiallyExpanded: Boolean = true,
) {
    val c = OcTheme.colors
    val shape = RoundedCornerShape(6.dp)
    val projectRows = remember(directory, branch, changedFiles, additions, deletions) {
        SessionSummaryModel.projectRows(directory, branch, changedFiles, additions, deletions)
    }
    val serverRows = remember(serverLabel, serverUrl) {
        SessionSummaryModel.serverRows(serverLabel, serverUrl)
    }
    var projectExpanded by remember(initiallyExpanded) { mutableStateOf(initiallyExpanded) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(c.layer01)
            .border(0.5.dp, c.borderBase, RoundedCornerShape(12.dp))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // ------------------------------------------------ project card
        Column(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(c.bgBase)
                .border(0.5.dp, c.borderMuted, shape)
                .padding(2.dp),
        ) {
            CardHeader(
                title = stringResource(R.string.oc_summary_project_title),
                expanded = projectExpanded,
                onToggle = { projectExpanded = !projectExpanded },
                rowHeight = if (mobile) 44.dp else 32.dp,
            )
            if (projectExpanded) {
                projectRows.forEach { row -> SummaryRow(row, mobile) }
            }
        }

        // ------------------------------------------------- server card
        if (serverRows.isNotEmpty()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(c.bgBase)
                    .border(0.5.dp, c.borderMuted, shape)
                    .padding(2.dp),
            ) {
                CardHeader(
                    title = stringResource(R.string.oc_summary_server_title),
                    expanded = true,
                    onToggle = null,
                    rowHeight = if (mobile) 44.dp else 32.dp,
                )
                serverRows.forEach { row -> SummaryRow(row, mobile) }
            }
        }

        // Android has no background-task model to show (documented gap), and the
        // panel is closed from the header affordance; a one-line hint keeps the
        // panel from looking truncated when only the project card has content.
        if (projectRows.isEmpty() && serverRows.isEmpty()) {
            Text(
                text = stringResource(R.string.oc_summary_empty),
                color = c.textMuted,
                fontSize = 13.sp,
                lineHeight = 20.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }

        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = if (mobile) 44.dp else 32.dp)
                .clip(shape)
                .clickable(role = Role.Button, onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.oc_summary_close),
                color = c.textMuted,
                fontSize = 13.sp,
                lineHeight = 20.sp,
            )
        }
    }
}

@Composable
private fun CardHeader(
    title: String,
    expanded: Boolean,
    onToggle: (() -> Unit)?,
    rowHeight: androidx.compose.ui.unit.Dp,
) {
    val c = OcTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = rowHeight)
            .then(
                if (onToggle != null) {
                    Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(role = Role.Button, onClick = onToggle)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_oc_folder),
            contentDescription = null,
            tint = c.iconMuted,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = title,
            color = c.textBase,
            fontSize = 13.sp,
            fontWeight = FontWeight(530),
            lineHeight = 20.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (onToggle != null) {
            Text(
                text = if (expanded) "▾" else "▸",
                color = c.iconMuted,
                fontSize = 13.sp,
                lineHeight = 20.sp,
            )
        }
    }
}

@Composable
private fun SummaryRow(row: SessionSummaryModel.Row, mobile: Boolean) {
    val c = OcTheme.colors
    val label = when (row.kind) {
        SessionSummaryModel.Kind.WORKSPACE -> stringResource(R.string.oc_summary_workspace)
        SessionSummaryModel.Kind.BRANCH -> stringResource(R.string.oc_summary_branch)
        SessionSummaryModel.Kind.CHANGES -> stringResource(R.string.oc_summary_changes)
        SessionSummaryModel.Kind.SERVER -> stringResource(R.string.oc_summary_server)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = if (mobile) 44.dp else 32.dp)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = c.textMuted,
            fontSize = 13.sp,
            lineHeight = 20.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = row.value,
            color = c.textBase,
            fontSize = 13.sp,
            lineHeight = 20.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontFamily = if (row.mono) OcTheme.monoFont else null,
            modifier = Modifier.weight(1f),
        )
    }
}
