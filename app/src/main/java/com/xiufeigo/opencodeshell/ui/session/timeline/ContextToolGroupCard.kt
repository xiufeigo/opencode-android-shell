package com.xiufeigo.opencodeshell.ui.session.timeline

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiufeigo.opencodeshell.ChatStore
import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.ui.components.SpinnerDots
import com.xiufeigo.opencodeshell.ui.components.TextShimmer
import com.xiufeigo.opencodeshell.ui.theme.OcMotion
import com.xiufeigo.opencodeshell.ui.theme.OcTheme

/**
 * Grouped timeline parts under the **`compact` preset** (W4-B item 2) — the
 * port of upstream `ContextToolGroup` (`session-ui/src/tools/tool-renderer.tsx`,
 * `BasicTool` at `:602-640` + the label memo at `:516-558`):
 *
 * ```
 * [data-component=context-tool-group-trigger]        BasicTool compact, arrow
 * ├─ spinner (status=running) / glasses icon
 * └─ span[context-tool-group-title]   "Used 3 Read, Glob, Grep"
 * [data-component=context-tool-group-list]           padding-inline-start:12px
 * ```
 *
 * The row label is upstream's `Used {count} {names}` (`ui.messagePart.tools.used`)
 * — the M6-era `Exploring` / `Explored` swap was dropped with W4-B: upstream's
 * `ui.sessionTimeline.exploring/explored` keys have **no consumer** in
 * `_upstream/**/*.tsx`, so the Android timeline was showing a dead key as its
 * only summary line.
 *
 * The expanded list holds the group members: reasoning parts render as their
 * compact collapsible, tools as flat `tool-trigger` rows that may still open
 * their body (upstream renders the full `ToolDisplay` there, `:697-728`). The
 * open flags are the same screen-level `toolOpen` map as the standalone cards,
 * keyed by the row key for the group and by `part.id` inside it.
 *
 * Wave 2.2b: the `subagent` streaming pill, skill rows and read-image rows
 * render at row level here too (same headline-only policy as the standalone
 * path in `PartRenderer`), because a group member is a full `ToolDisplay`.
 */
@Composable
fun ContextToolGroupRow(
    group: TimelineGrouping.Row.Context,
    expanded: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    toolOpen: Map<String, Boolean> = emptyMap(),
    onToggleTool: (String, Boolean) -> Unit = { _, _ -> },
    shellExpanded: Boolean = ToolCardPolicy.DEFAULT_SHELL_EXPANDED,
    editExpanded: Boolean = ToolCardPolicy.DEFAULT_EDIT_EXPANDED,
) {
    val c = OcTheme.colors
    // Shared collapsible arrow: closed = right (-90°), open = down
    // (`collapsible.css` `collapsible-arrow-icon` rotate(-90deg) → 0deg).
    val rotation by animateFloatAsState(if (expanded) 0f else -90f, label = "ctx-group-chevron")
    // Hoisted: `semantics {}` is not a composable scope. The label rides the
    // clickable Row, not the chevron `Icon` inside it.
    val toggleLabel = stringResource(R.string.oc_toggle_tool)

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // Two lines when the P2 multiplicity chips show (`GroupLabelText`
                // stacks them under the `Used …` line); one line otherwise.
                .heightIn(min = TRIGGER_HEIGHT)
                .clickable(role = Role.Button) { onToggle(expanded) }
                .semantics { contentDescription = toggleLabel },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // `status={pending() ? "running" : "completed"}` (`tool-renderer.tsx:606`):
            // the running state replaces the icon with the tool spinner.
            if (group.busy) {
                SpinnerDots(size = 16.dp, color = c.textFaint)
            } else {
                Icon(
                    painter = painterResource(R.drawable.ic_oc_glasses),
                    contentDescription = null,
                    tint = c.iconMuted,
                    modifier = Modifier.size(16.dp),
                )
            }
            Spacer(Modifier.width(GAP))
            GroupLabelText(group = group)
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(GAP))
            Icon(
                painter = painterResource(R.drawable.ic_oc_chevron_down),
                contentDescription = null,
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
                group.parts.forEach { part ->
                    when (part.type) {
                        "reasoning" -> ReasoningCard(part = part, shimmer = false)
                        "tool" -> when (toolRendererKind(part)) {
                            // `ToolDisplay`'s Switch **replaces** the whole row
                            // for the dedicated renderers (same rule as
                            // `PartRenderer`): a flat trigger around
                            // `ToolErrorCard`/`QuestionCard`/`SubagentCard`
                            // stacked two same-named headers and made the
                            // error detail a two-tap target.
                            ToolRendererKind.HIDDEN -> Unit
                            ToolRendererKind.ERROR -> ToolErrorCard(part = part)
                            ToolRendererKind.QUESTION -> QuestionCard(part = part)
                            ToolRendererKind.SUBAGENT -> SubagentCard(part = part)
                            ToolRendererKind.SKILL -> SkillRow(part = part)
                            ToolRendererKind.READ_IMAGE -> ReadImageRow(part = part)
                            else -> GroupToolRow(
                                part = part,
                                userOpen = toolOpen[part.id],
                                onToggle = { open -> onToggleTool(part.id, open) },
                                shellExpanded = shellExpanded,
                                editExpanded = editExpanded,
                            )
                        }
                        else -> GroupToolRow(
                            part = part,
                            userOpen = toolOpen[part.id],
                            onToggle = { open -> onToggleTool(part.id, open) },
                            shellExpanded = shellExpanded,
                            editExpanded = editExpanded,
                        )
                    }
                }
            }
        }
    }
}

/**
 * `Used {count} {names}` / `Updates` (`tool-renderer.tsx:535-558`). The count
 * and the distinct tool titles come from [TimelineGrouping.label]; the titles
 * are resolved through the same `oc_tool_*` literals the tool rows use, and a
 * thoughts-only group falls back to the `Thought` / `Thoughts` plural.
 */
@Composable
private fun GroupLabelText(group: TimelineGrouping.Row.Context) {
    val c = OcTheme.colors
    val label = TimelineGrouping.label(group.parts)
    // P2 tool-count chips: multiplicity per distinct title ("2 Read · Grep").
    val chips = ToolModels.countChips(group.parts)
    val text = if (label.isUpdatesOnly) {
        stringResource(R.string.oc_meta_updates)
    } else {
        val titles = label.toolNames.map { name ->
            dedicatedCardTitle(name)
                ?: name?.let { ToolPresentation.titleRes(it)?.let { res -> stringResource(res) } }
                ?: TimelineGrouping.toolLabel(name)
        }
        val title = if (titles.isNotEmpty()) {
            titles.joinToString(", ")
        } else {
            pluralStringResource(R.plurals.oc_meta_thought, label.thoughts, label.thoughts)
        }
        stringResource(R.string.oc_meta_used, label.count, title)
    }
    // The label stacks: the P2 chips ride under the `Used …` line when the
    // group mixes titles. The two Texts used to be sequential siblings of the
    // trigger row with no gap and read as one glued line
    // (`Used 2 Shell, GrepShell . Grep`, 5558 v81).
    Column {
        TextShimmer(
            text = text,
            active = group.busy,
            baseColor = c.textBase,
            fontSize = 13.sp,
            fontWeight = FontWeight(530),
        )
        if (!label.isUpdatesOnly && chips.size > 1) {
            ToolCountChips(chips = chips)
        }
    }
}

/**
 * One tool inside a group. Upstream renders a full `ToolDisplay` there, so a
 * member with a body still opens it (`tool-renderer.tsx:697-728`); the title is
 * the `tool-trigger` line otherwise.
 */
@Composable
private fun GroupToolRow(
    part: ChatStore.UiPart,
    userOpen: Boolean?,
    onToggle: (Boolean) -> Unit,
    shellExpanded: Boolean,
    editExpanded: Boolean,
) {
    val c = OcTheme.colors
    // One `bodyLines` per recomposition: canExpand + resolveOpen→defaultOpen
    // and ToolBody would otherwise each re-parse the JSON and re-run Myers
    // (O(n·m)) on every recomposition of a large edit — same rule as ToolCard.
    val lines = remember(part.id, part.toolInput, part.toolOutput, part.toolName, part.toolStatus, part.toolError) {
        ToolPresentation.bodyLines(part)
    }
    val expandable = ToolCardPolicy.canExpand(part, lines)
    val expanded = ToolCardPolicy.resolveOpen(part, userOpen, shellExpanded, editExpanded, lines)
    val rotation by animateFloatAsState(if (expanded) 0f else -90f, label = "ctx-item-chevron")
    val subtitle = ToolPresentation.subtitle(part)
    // Hoisted: `semantics {}` is not a composable scope. The label rides the
    // clickable Row, not the chevron `Icon` inside it.
    val toggleLabel = stringResource(R.string.oc_toggle_tool)

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(TRIGGER_HEIGHT)
                .clickable(enabled = expandable, role = Role.Button) { onToggle(expanded) }
                .semantics { contentDescription = toggleLabel },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(toolIconRes(ToolPresentation.icon(part.toolName))),
                contentDescription = null,
                tint = if (ToolCardPolicy.stateOf(part) == ToolCardPolicy.State.ERROR) {
                    c.stateDangerFg
                } else {
                    c.iconMuted
                },
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(GAP))
            Text(
                text = dedicatedCardTitle(part.toolName)
                    ?: ToolPresentation.titleRes(part.toolName)?.let { stringResource(it) }
                    ?: ToolPresentation.title(part.toolName),
                color = if (ToolCardPolicy.stateOf(part) == ToolCardPolicy.State.ERROR) {
                    c.stateDangerFg
                } else {
                    c.textBase
                },
                fontSize = 13.sp,
                fontWeight = FontWeight(530),
                maxLines = 1,
            )
            if (!subtitle.isNullOrEmpty()) {
                Spacer(Modifier.width(GAP))
                Text(
                    text = subtitle,
                    color = if (ToolCardPolicy.stateOf(part) == ToolCardPolicy.State.ERROR) {
                        c.stateDangerFg
                    } else {
                        c.textMuted
                    },
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            if (expandable) {
                Spacer(Modifier.width(GAP))
                Icon(
                    painter = painterResource(R.drawable.ic_oc_chevron_down),
                    contentDescription = null,
                    tint = c.textFaint,
                    modifier = Modifier.size(16.dp).rotate(rotation),
                )
            }
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(animationSpec = OcMotion.expand) + fadeIn(OcMotion.fade),
            exit = shrinkVertically(animationSpec = OcMotion.expand) + fadeOut(OcMotion.fade),
        ) {
            ToolBody(part, lines)
        }
    }
}

/** Collapsed trigger: `collapsible-trigger` compact = 28 px (`basic-tool.css:173-174`). */
private val TRIGGER_HEIGHT = 28.dp

/** Compact `tool-trigger` gap (`basic-tool.css:180-183`). */
private val GAP = 6.dp
