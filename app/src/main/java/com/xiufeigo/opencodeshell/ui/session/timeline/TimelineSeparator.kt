package com.xiufeigo.opencodeshell.ui.session.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
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
import com.xiufeigo.opencodeshell.ui.theme.OcTheme

/**
 * `TimelineSeparator` (`_upstream/packages/session-ui/src/components/
 * timeline-separator.tsx:4-32`):
 *
 * ```
 * div.flex.h-8.w-full.items-center.gap-3.text-v2-text-text-faint
 * ├─ span.h-px.min-w-0.flex-1.bg-v2-border-border-strong
 * ├─ span.text-[13px].font-[440].leading-text-compact.tracking-[-0.04px]
 * │    ├─ ProviderIcon            (only with providerID — omitted, see below)
 * │    └─ bdi.truncate            {props.label}
 * └─ span.h-px.min-w-0.flex-1.bg-v2-border-border-strong
 * ```
 *
 * Ported as: 32 dp row height (`h-8`), 12 dp gaps (`gap-3`), the two 1 px
 * `border-strong` rules as weighted boxes, the label at 13 sp / 440 in
 * `textFaint`, single line and truncated (`truncate` + `bdi[title]`).
 *
 * Deviations, both recorded in `research/wave5_timeline.md`:
 * - the optional `providerID` `ProviderIcon` is dropped (our model-switch
 *   anchor has no provider icon resolver inside the session UI packages);
 * - the optional `variant` dot + capitalized suffix (`:16-27`) is dropped — the
 *   Android store does not carry the model variant.
 */
@Composable
internal fun TimelineSeparator(label: String, modifier: Modifier = Modifier) {
    val c = OcTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(SEPARATOR_HEIGHT),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SEPARATOR_GAP),
    ) {
        SeparatorRule(Modifier.weight(1f))
        Text(
            text = label,
            color = c.textFaint,
            fontSize = 13.sp,
            fontWeight = FontWeight(440),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        SeparatorRule(Modifier.weight(1f))
    }
}

@Composable
private fun SeparatorRule(modifier: Modifier) {
    Box(modifier.height(1.dp).background(OcTheme.colors.borderStrong))
}

/**
 * One [TimelineRows.Row.Separator] with its localized label — the mapping from
 * the pure insertion rule to the upstream copy:
 *
 * | kind | string | upstream |
 * |---|---|---|
 * | `INTERRUPTED` | `oc_meta_interrupted` | `ui.message.interrupted` (`i18n/en.ts:245`) |
 * | `MODEL` | the model display name | `session-timeline-row.tsx:511` `model().label` |
 * | `COMPACTION_START` | `oc_sep_compaction_started` | `ui.messagePart.compaction.started` (`en.ts:107`) |
 * | `COMPACTION_END` | `oc_compaction` | `ui.messagePart.compaction` (`en.ts:106`, "Session compacted") |
 *
 * Upstream wraps every one of these in `py-2` (`session-timeline-row.tsx:510,647`,
 * `message-content.tsx:436,462`); the timeline's `Arrangement.spacedBy(TURN_GAP)`
 * already separates the rows, so no extra vertical padding is added here.
 */
@Composable
internal fun TimelineSeparatorRow(
    separator: TimelineRows.Row.Separator,
    modifier: Modifier = Modifier,
) {
    val model = TimelineRows.modelLabel(separator.model)
    // A model switch with an empty label would render a bare pair of rules.
    if (separator.kind == TimelineRows.SeparatorKind.MODEL && model.isEmpty()) return
    val label = when (separator.kind) {
        TimelineRows.SeparatorKind.INTERRUPTED -> stringResource(R.string.oc_meta_interrupted)
        TimelineRows.SeparatorKind.MODEL -> model
        TimelineRows.SeparatorKind.COMPACTION_START ->
            stringResource(R.string.oc_sep_compaction_started)

        TimelineRows.SeparatorKind.COMPACTION_END -> stringResource(R.string.oc_compaction)
    }
    TimelineSeparator(label = label, modifier = modifier.padding(vertical = 2.dp))
}

/** `h-8`. */
private val SEPARATOR_HEIGHT = 32.dp

/** `gap-3`. */
private val SEPARATOR_GAP = 12.dp
