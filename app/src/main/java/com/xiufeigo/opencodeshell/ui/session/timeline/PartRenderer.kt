package com.xiufeigo.opencodeshell.ui.session.timeline

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiufeigo.opencodeshell.ChatStore
import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.ui.components.SpinnerDots
import com.xiufeigo.opencodeshell.ui.components.TextShimmer
import com.xiufeigo.opencodeshell.ui.markdown.OcMarkdown
import com.xiufeigo.opencodeshell.ui.theme.OcMotion
import com.xiufeigo.opencodeshell.ui.theme.OcTheme

/**
 * Renders one [ChatStore.UiPart] inside the timeline. Dispatch mirrors the
 * upstream `ToolDisplay` / content switch (`_upstream/packages/session-ui/src/
 * tools/tool-renderer.tsx:1189-1261`, `message/message-content.tsx`):
 *
 * | part | rendering |
 * |---|---|
 * | `text` | markdown body (`OcMarkdown`) |
 * | `tool` | chrome-less collapsible card (see [ToolCard]) |
 * | `reasoning` | chrome-less "Thought" collapsible |
 * | `compaction` | summary markdown, the running shimmer, or the failure label + its detail ([CompactionBlock]) |
 * | `file` | attachment chip |
 * | `patch` | `Patch` + `{n} files` row, file list on expand |
 * | `subtask` | agent + description row |
 * | `agent` | `Agent changed` notice row |
 * | `retry` | retry notice row (`session-retry.tsx`) |
 * | `step-start` / `step-finish` / `snapshot` | **no row** (see below) |
 *
 * W4-B (item 5): the last four raw-JSON types used to be dropped silently.
 * `step-start` / `step-finish` / `snapshot` are handled **explicitly** as
 * "render nothing" so they can never fall through to an "unknown part type"
 * branch; upstream folds them into the normalised content model
 * (`schema/src/v1/session.ts:233-257`).
 *
 * M1.5: the readable bodies (`text`, `reasoning`) are wrapped in a
 * [SelectionContainer] so the body text is selectable; the tool card is
 * deliberately **not** wrapped (`PLAN` §4.2 — the expanded body has its own
 * `verticalScroll`, and a selection gesture there would fight it).
 */
@Composable
fun PartRenderer(
    part: ChatStore.UiPart,
    modifier: Modifier = Modifier,
    /** Last part of the last assistant message: gates the shimmer/spinner. */
    isTrailing: Boolean = false,
    /**
     * Screen-level `toolOpen` entry for this part (`null` = the user has never
     * toggled it, so `partDefaultOpen` decides). Owned by `SessionScreen` so an
     * expanded card survives LazyColumn recycling, exactly like the upstream
     * timeline store.
     */
    userOpen: Boolean? = null,
    /**
     * Reports a card toggle as `(partId, newOpen)`: the callback carries the
     * resolved value so the owner does not have to re-derive it from
     * `partDefaultOpen` (a default-open card must collapse on the first tap).
     */
    onToggleTool: (String, Boolean) -> Unit = { _, _ -> },
    /** `settings.general.row.shellToolPartsExpanded` (M4 switch, compact default false). */
    shellExpanded: Boolean = ToolCardPolicy.DEFAULT_SHELL_EXPANDED,
    /** `settings.general.row.editToolPartsExpanded` (compact default false). */
    editExpanded: Boolean = ToolCardPolicy.DEFAULT_EDIT_EXPANDED,
    /** `settings.general.row.reasoningSummaries` — false hides reasoning parts. */
    reasoningSummaries: Boolean = true,
) {
    when (part.type) {
        "text" -> if (part.text.isNotBlank()) {
            FoldableMarkdown(
                markdown = part.text,
                stateKey = part.id,
                modifier = modifier,
                color = OcTheme.colors.textBase,
                streaming = isTrailing,
            )
        }

        "tool" -> if (part.toolName != null && part.toolName !in TimelineGrouping.HIDDEN_TOOLS) {
            // `ToolDisplay`'s Switch **replaces** the whole card for error /
            // question / subagent (`tool-renderer.tsx:1219-1256`; the subagent
            // registry entry is `hideDetails`): nesting those cards inside the
            // generic ToolCard stacked two trigger rows and made the error
            // detail a two-tap target. Question still hides while
            // streaming/running (`hideQuestion`, `:1195`); the read-image /
            // skill rows stay headline-only (upstream renders its own
            // `BasicTool`).
            when (toolRendererKind(part)) {
                ToolRendererKind.HIDDEN -> return@PartRenderer
                ToolRendererKind.ERROR -> {
                    ToolErrorCard(part = part, modifier = modifier)
                    return@PartRenderer
                }
                ToolRendererKind.QUESTION -> {
                    QuestionCard(part = part, modifier = modifier)
                    return@PartRenderer
                }
                ToolRendererKind.SUBAGENT -> {
                    SubagentCard(part = part, modifier = modifier)
                    return@PartRenderer
                }
                ToolRendererKind.SKILL, ToolRendererKind.READ_IMAGE -> {
                    DedicatedHeadlineRow(part = part, modifier = modifier, shimmer = isTrailing)
                    return@PartRenderer
                }
                else -> Unit
            }
            ToolCard(
                part = part,
                modifier = modifier,
                shimmer = isTrailing,
                userOpen = userOpen,
                onToggle = { open -> onToggleTool(part.id, open) },
                shellExpanded = shellExpanded,
                editExpanded = editExpanded,
            )
        }

        "reasoning" -> if (reasoningSummaries && part.text.isNotBlank()) {
            ReasoningCard(part = part, modifier = modifier, shimmer = isTrailing)
        }

        // v19 P2 / v21 P3: both status hooks come off the store's one reader
        // ([ChatStore.compactionStatusOf]) — the live `session.compaction.*` row
        // (`toolStatus`, settled by `ChatStore.reduceCompaction`) and the durable
        // `type=compaction` message row (its `status`, rebuilt by
        // `ChatStore.compactionRow`) read the same way, so a rejected compaction
        // gets its own label and its own error row instead of the static
        // "Session compacted" fallback.
        "compaction" -> CompactionBlock(
            part = part,
            running = ChatStore.compactionRunning(part),
            error = ChatStore.compactionErrorOf(part),
            modifier = modifier,
        )

        "file" -> AttachmentChip(part = part, modifier = modifier)

        // W4-B item 5: part types that only carry `UiPart.rawJson`.
        "patch" -> PatchRow(part = part, modifier = modifier)
        "subtask" -> SubtaskRow(part = part, modifier = modifier)
        "agent" -> AgentNoticeRow(part = part, modifier = modifier)
        "retry" -> RetryNoticeRow(part = part, modifier = modifier)

        // v2 notice rows (`V2EventAdapter.notice`, upstream
        // `session-timeline-row.tsx:307-356` + `session-message.ts:40-125`).
        "system", "synthetic" -> NoticeLabelRow(
            label = RawParts.description(part.rawJson) ?: part.text,
            modifier = modifier,
        )
        "skill" -> NoticeLabelRow(
            label = stringResource(R.string.oc_tool_skill),
            value = part.toolName?.takeIf { it.isNotEmpty() },
            modifier = modifier,
        )
        "shell" -> NoticeLabelRow(
            label = stringResource(R.string.oc_tool_shell),
            value = part.toolName?.takeIf { it.isNotEmpty() },
            modifier = modifier,
        )
        "agent-switched" -> NoticeLabelRow(
            label = stringResource(R.string.oc_step_agent_changed),
            value = RawParts.capitalize(RawParts.switch(part.rawJson).value),
            modifier = modifier,
        )
        // Upstream renders `model-switched` as the timeline separator only
        // (`session-timeline-row.tsx:316` returns no notice text); the row here
        // replaces `TimelineRows`' derived MODEL separator (its dedup rule skips
        // messages that carry this part).
        "model-switched" -> NoticeLabelRow(
            label = stringResource(R.string.oc_step_model_changed),
            value = TimelineRows.modelLabel(RawParts.switch(part.rawJson).value),
            modifier = modifier,
        )
        "location-switched" -> NoticeLabelRow(
            label = stringResource(R.string.oc_step_location_changed),
            // Snapshot rows carry no `rawJson` (Route-C keeps it only for the
            // eight v1 part types), so `switch()` alone leaves every reopened
            // row a bare label (v46 tD). The snapshot projection resolves the
            // project name into `text` instead — read it here, exactly like
            // the `system` / `synthetic` row above. Blank-tolerant on both
            // sides: a `switch()` value with no visible characters (or none at
            // all) must fall through to `text` rather than pin the row to a
            // bare label.
            value = RawParts.switch(part.rawJson).value?.takeIf { it.isNotBlank() }
                ?: part.text.takeIf { it.isNotBlank() },
            modifier = modifier,
        )

        // Explicit no-ops (never an "unknown type"): upstream `step-start` /
        // `step-finish` / `snapshot` have no timeline representation of their
        // own — the first two only ride inside a tool group
        // (`TimelineGrouping.GROUP_FILLER_TYPES`).
        "step-start", "step-finish", "snapshot" -> Unit
    }
}

/**
 * Wraps a message body in a [SelectionContainer] (`PLAN` §4.2 "正文可选中").
 *
 * Since the markdown body is now Compose-native ([OcMarkdown] in
 * `ui/markdown`), selection reaches every span — the Markwon `AndroidView`
 * caveat that used to live here is gone (an interop `TextView` could not join
 * Compose's selection pipeline; a selectable one would have swallowed the
 * LazyColumn's vertical drags).
 */
@Composable
private fun SelectableBody(content: @Composable () -> Unit) {
    SelectionContainer(content = content)
}

// --------------------------------------------------------------------- text
//
// The assistant body renders through `OcMarkdown` (`ui/markdown/`), the
// Compose-native block/inline parser aligned with upstream `markdown.css`.
// The Markwon `AndroidView` pipeline that used to live here (and its
// `MarkwonHolder` cache) was retired with the timeline fidelity pass: a
// Compose-native body joins `SelectionContainer`, follows `OcTheme` without a
// baked palette, and needs no interop `TextView`. Markwon itself stays on the
// classpath for the View-era `ChatView`.

// --------------------------------------------------------------- tool card

/**
 * Tool card: **no surface, no border** — the upstream collapsible body is
 * `background-color: transparent; border: none`
 * (`_upstream/packages/ui/src/components/collapsible.css:1-14`) and only the
 * expanded content is indented by a 12 px rail
 * (`session-ui/src/components/basic-tool.css:314-318`). W4-B item 1 removed the
 * `layer01` fill + 0.5 dp `borderBase` + radius 6 shell that made every card a
 * solid tile.
 *
 * Compact metrics (`basic-tool.css:173-200`, the group/compact variant): 28 dp
 * trigger, 13 sp/530 title, 13 sp/440 muted single-line subtitle, 6 dp gaps.
 * The accent is carried by the **icon colour** (running → `accent`, error →
 * `stateDangerFg`, done → muted).
 *
 * M2 (§3.1 / §3.2 / §3.2b): the open state is resolved by [ToolCardPolicy]
 * (user toggle > `partDefaultOpen`), `pending`/`running` cards are locked shut
 * except for `shell`, and the body animates with the collapsible spring. The
 * chevron only exists for cards that can expand — a body-less card has no arrow.
 *
 * M6 motion: a running card carries the web's `TextShimmer` title
 * (`research/03` §2.3) and the 16×16 4×4 dot-matrix spinner (§2.4) in the
 * trailing slot, both of which freeze under reduced motion.
 */
@Composable
private fun ToolCard(
    part: ChatStore.UiPart,
    modifier: Modifier = Modifier,
    shimmer: Boolean,
    userOpen: Boolean?,
    onToggle: (Boolean) -> Unit,
    shellExpanded: Boolean,
    editExpanded: Boolean,
) {
    val c = OcTheme.colors
    val state = ToolCardPolicy.stateOf(part)
    val tint = when (state) {
        ToolCardPolicy.State.RUNNING -> c.accent
        ToolCardPolicy.State.ERROR -> c.stateDangerFg
        ToolCardPolicy.State.COMPLETED -> c.iconMuted
        ToolCardPolicy.State.PENDING -> c.iconMuted
    }
    // One `bodyLines` per recomposition: canExpand + resolveOpen→defaultOpen
    // and ToolBody would otherwise each re-parse the JSON and re-run Myers
    // (O(n·m)) on every recomposition of a large edit card.
    val lines = remember(part.id, part.toolInput, part.toolOutput, part.toolName, part.toolStatus, part.toolError) {
        ToolPresentation.bodyLines(part)
    }
    val expandable = ToolCardPolicy.canExpand(part, lines)
    val expanded = ToolCardPolicy.resolveOpen(part, userOpen, shellExpanded, editExpanded, lines)
    // Upstream collapsible arrow: closed = pointing right (-90°), open = down
    // (`collapsible.css` `rotate(-90deg)` → `rotate(0deg)` on aria-expanded).
    val rotation by animateFloatAsState(if (expanded) 0f else -90f, label = "tool-chevron")
    // Hoisted: `semantics {}` is not a composable scope. The label rides the
    // clickable Row, not the chevron `Icon` inside it — only the node carrying
    // ACTION_CLICK is announced as a button.
    val toggleLabel = stringResource(R.string.oc_toggle_tool)

    Column(modifier = modifier.fillMaxWidth()) {
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
                tint = tint,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(GAP))
            // M6: the running title is the web's `TextShimmer` (sweep across the
            // glyphs), replacing M1's 1 s alpha pulse — `research/03` §2.3.
            // Error state: title in `stateDangerFg` (fidelity pass — the failed
            // card reads red end to end: icon, title, error summary, body).
            // `websearch` titles through the provider label (`ui.tool.websearch`
            // / `ui.tool.websearch.provider`); everything else uses the generic
            // `titleRes`/`title` path.
            TextShimmer(
                text = dedicatedCardTitle(part.toolName)
                    ?: ToolPresentation.titleRes(part.toolName)?.let { stringResource(it) }
                    ?: ToolPresentation.title(part.toolName),
                active = shimmer && state == ToolCardPolicy.State.RUNNING,
                baseColor = if (state == ToolCardPolicy.State.ERROR) c.stateDangerFg else c.textBase,
                fontSize = 13.sp,
                fontWeight = FontWeight(530),
            )
            val subtitle = ToolPresentation.subtitle(part)
            if (!subtitle.isNullOrEmpty()) {
                Spacer(Modifier.width(GAP))
                Text(
                    text = subtitle,
                    // On failure the subtitle IS the error summary
                    // (`tool-error-card.css` `tool-error-card-summary` = state-fg-danger).
                    color = if (state == ToolCardPolicy.State.ERROR) c.stateDangerFg else c.textMuted,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            if (state == ToolCardPolicy.State.RUNNING) {
                // The tool card's 16×16 spinner (`research/03` §2.4/§3.5), not a
                // rotating ring: the 4×4 dot matrix breathing in place.
                Spacer(Modifier.width(GAP))
                SpinnerDots(size = 16.dp, color = c.textFaint)
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

/**
 * Expanded body (`bash-output` / `tool-output`, `message-part.css:323-464`):
 * `bg-base` surface, 0.5 px `border-base` hairline, radius 6, 12 px inner
 * padding, mono 13 px/20 px, capped at 240 px with an inner scroll.
 *
 * The 12 dp `padding-inline-start` is the upstream "rail"
 * (`basic-tool.css:314-318`) that carries the collapsed→expanded indent now
 * that the card itself has no surface.
 */
@Composable
internal fun ToolBody(part: ChatStore.UiPart, sharedLines: List<ToolPresentation.BodyLine>? = null) {
    val c = OcTheme.colors
    // Dedicated renderers first (`ToolDisplay`'s `Switch`: error + registry
    // branches before `GenericTool`, `tool-renderer.tsx:1218-1261`). The
    // standalone path now replaces the whole card for those kinds (see
    // `PartRenderer`); this branch serves the group rows
    // (`ContextToolGroupCard`), whose flat trigger still wraps ToolBody.
    when (toolRendererKind(part)) {
        ToolRendererKind.HIDDEN -> return
        ToolRendererKind.ERROR -> {
            ToolErrorCard(part = part)
            return
        }
        ToolRendererKind.SHELL -> {
            ShellBody(part = part)
            return
        }
        ToolRendererKind.SUBAGENT -> {
            Column(Modifier.fillMaxWidth()) {
                SubagentCard(part = part)
                // A subagent with output keeps the generic JSON/output body
                // below the card (upstream `BasicTool` children).
                GenericBody(part = part)
            }
            return
        }
        ToolRendererKind.QUESTION -> {
            QuestionCard(part = part)
            return
        }
        ToolRendererKind.SKILL -> {
            SkillRow(part = part)
            return
        }
        ToolRendererKind.READ_IMAGE -> {
            Column(Modifier.fillMaxWidth()) {
                ReadImageRow(part = part)
                GenericBody(part = part)
            }
            return
        }
        ToolRendererKind.READ_LOADED -> {
            // `metadata.loaded` never reaches the store, so the hook stays
            // empty: the row hides and only the generic body shows.
            Column(Modifier.fillMaxWidth()) {
                LoadedFilesRow(loadedPaths = emptyList())
                GenericBody(part = part)
            }
            return
        }
        ToolRendererKind.WEBSEARCH -> {
            Column(Modifier.fillMaxWidth()) {
                WebLinksBlock(part = part)
                GenericBody(part = part)
            }
            return
        }
        ToolRendererKind.GENERIC -> Unit
    }
    // `sharedLines` is ToolCard's own remember — same keys, so the standalone
    // card parses JSON + runs Myers once per change, not once per call site.
    val lines = remember(part.id, part.toolInput, part.toolOutput, part.toolName, part.toolStatus, part.toolError, sharedLines) {
        sharedLines ?: ToolPresentation.bodyLines(part)
    }
    if (lines.isEmpty()) return
    // U-4 fold threshold: a running/streaming tool keeps rendering its live
    // tail; a settled body longer than OUTPUT_FOLD_LINES renders the head plus
    // an expander, so opening the card on a huge output no longer inflates the
    // measure/layout pass (upstream only caps at 240 px with an inner scroll,
    // `message-part.css:323-464` — the cap stays as the second limit).
    val live = part.toolStatus == "running" || part.toolStatus == "streaming"
    val long = remember(lines, live) { !live && TimelineFold.isLongBody(lines.size) }
    var showAll by rememberSaveable(part.id, "body-more") { mutableStateOf(false) }
    val visible = if (long && !showAll) lines.subList(0, TimelineFold.OUTPUT_FOLD_LINES) else lines
    val hidden = lines.size - visible.size
    // Diff lines are tinted by **background**, matching upstream's diff renderer
    // (`--diffs-*` backgrounds, foreground untouched) and the review panel's
    // `DiffLineKind.ADDITION -> textBase to stateSuccessBg` mapping. A single
    // `Text` can only paint a span behind the glyphs, so a body that actually
    // contains diff lines is rendered line-per-row where the tint spans the full
    // width; everything else stays on the cheap single-`Text` path.
    val hasDiff = visible.any { ToolPresentation.isDiffKind(it.kind) }
    Column(modifier = Modifier.fillMaxWidth()) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, bottom = 8.dp)
            .heightIn(max = TOOL_BODY_MAX_HEIGHT)
            .clip(OcTheme.shapes.md)
            .background(c.bgBase)
            .border(0.5.dp, c.borderBase, OcTheme.shapes.md)
            .verticalScroll(rememberScrollState()),
    ) {
        if (hasDiff) {
            Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                val additionBg = c.stateSuccessBg
                val deletionBg = c.stateDangerBg
                val hunkBg = c.layer01
                visible.forEach { line ->
                    val bg = when (line.kind) {
                        ToolPresentation.LineKind.ADD -> additionBg
                        ToolPresentation.LineKind.DELETE -> deletionBg
                        // `@@` headers sit on the layered surface, like the
                        // review panel's `DiffLineKind.HUNK -> layer01`.
                        ToolPresentation.LineKind.HUNK -> hunkBg
                        else -> androidx.compose.ui.graphics.Color.Transparent
                    }
                    Text(
                        text = if (line.kind == ToolPresentation.LineKind.JSON) {
                            toolBodyAnnotated(listOf(line))
                        } else {
                            androidx.compose.ui.text.AnnotatedString(line.text)
                        },
                        color = when (line.kind) {
                            ToolPresentation.LineKind.ERROR -> c.stateDangerFg
                            // Hunk headers + unchanged context read faint, the
                            // review panel's `HUNK/META -> textFaint`,
                            // `CONTEXT -> textMuted` mapping.
                            ToolPresentation.LineKind.HUNK -> c.textFaint
                            ToolPresentation.LineKind.CONTEXT -> c.textMuted
                            else -> c.textBase
                        },
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        // `--line-height-compact`: 16px for 13px tool text.
                        lineHeight = 16.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(bg)
                            .padding(horizontal = 12.dp),
                    )
                }
            }
        } else {
            Box(Modifier.padding(12.dp)) {
                Text(
                    text = toolBodyAnnotated(visible),
                    color = c.textBase,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    lineHeight = 16.sp,
                )
            }
        }
    }
        if (long) {
            FoldToggleRow(
                hiddenLines = hidden,
                expanded = showAll,
                onToggle = { showAll = it },
                modifier = Modifier.padding(start = 12.dp, end = 12.dp),
            )
        }
    }
}

/**
 * Generic JSON/output body shared by the dedicated cards that keep upstream
 * `BasicTool` children below their headline (subagent/read/websearch). Empty —
 * renders nothing — when there is no input/output, so the card never gains a
 * stray surface.
 */
@Composable
private fun GenericBody(part: ChatStore.UiPart) {
    val input = part.toolInput?.takeIf { it.isNotBlank() }
    val output = part.toolOutput?.takeIf { it.isNotBlank() }
    if (input == null && output == null) return
    val c = OcTheme.colors
    val lines = remember(part.id, input, output) {
        buildList {
            input?.let { raw ->
                ToolPresentation.splitLines(ToolPresentation.pretty(raw)).forEach {
                    add(ToolPresentation.BodyLine(ToolPresentation.LineKind.JSON, it))
                }
            }
            output?.let { text ->
                if (isNotEmpty()) add(ToolPresentation.BodyLine(ToolPresentation.LineKind.PLAIN, ""))
                ToolPresentation.splitLines(text.replace("\r\n", "\n").replace('\r', '\n')).forEach {
                    add(ToolPresentation.BodyLine(ToolPresentation.LineKind.PLAIN, it))
                }
            }
        }
    }
    if (lines.isEmpty()) return
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, bottom = 8.dp)
            .heightIn(max = TOOL_BODY_MAX_HEIGHT)
            .clip(OcTheme.shapes.md)
            .background(c.bgBase)
            .border(0.5.dp, c.borderBase, OcTheme.shapes.md)
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
    ) {
        Text(
            text = toolBodyAnnotated(lines),
            color = c.textBase,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            lineHeight = 16.sp,
        )
    }
}

@Composable
private fun toolBodyAnnotated(lines: List<ToolPresentation.BodyLine>): AnnotatedString {
    val c = OcTheme.colors
    val addColor = colorResource(R.color.oc_diff_add_fg)
    val delColor = colorResource(R.color.oc_diff_del_fg)
    return remember(lines, c, addColor, delColor) {
        buildAnnotatedString {
            lines.forEachIndexed { index, line ->
                if (index > 0) append('\n')
                when (line.kind) {
                    ToolPresentation.LineKind.ADD -> colored(line.text, addColor)
                    ToolPresentation.LineKind.DELETE -> colored(line.text, delColor)
                    ToolPresentation.LineKind.ERROR -> colored(line.text, c.stateDangerFg)
                    ToolPresentation.LineKind.HUNK -> colored(line.text, c.textFaint)
                    ToolPresentation.LineKind.CONTEXT -> colored(line.text, c.textMuted)
                    ToolPresentation.LineKind.TEXT -> colored(line.text, c.textBase)
                    ToolPresentation.LineKind.JSON ->
                        appendJsonHighlighted(line.text, c.textMuted, c.syntaxString, c.textBase)

                    else -> colored(line.text, c.textBase)
                }
            }
        }
    }
}

private fun AnnotatedString.Builder.colored(text: String, color: Color) {
    withStyle(SpanStyle(color = color)) { append(text) }
}

/** Ports `ChatView.jsonSpannable`: keys muted, string values syntax-green. */
private fun AnnotatedString.Builder.appendJsonHighlighted(
    text: String,
    keyColor: Color,
    stringColor: Color,
    baseColor: Color,
) {
    var cursor = 0
    for (match in JSON_STRING_REGEX.findAll(text)) {
        if (match.range.first > cursor) {
            colored(text.substring(cursor, match.range.first), baseColor)
        }
        var after = match.range.last + 1
        while (after < text.length && text[after].isWhitespace()) after++
        val isKey = after < text.length && text[after] == ':'
        colored(match.value, if (isKey) keyColor else stringColor)
        cursor = match.range.last + 1
    }
    if (cursor < text.length) colored(text.substring(cursor), baseColor)
}

/**
 * Headline-only rows (skill / read-image): upstream renders its own `BasicTool`
 * trigger with no expandable children, so they bypass the generic card. The
 * `shimmer` flag marks the trailing row of a running turn (matches upstream's
 * `TextShimmer active` on streaming/running statuses).
 */
@Composable
private fun DedicatedHeadlineRow(
    part: ChatStore.UiPart,
    modifier: Modifier = Modifier,
    shimmer: Boolean = false,
) {
    when (toolRendererKind(part)) {
        ToolRendererKind.SKILL -> SkillRow(part = part, modifier = modifier)
        ToolRendererKind.READ_IMAGE -> ReadImageRow(part = part, modifier = modifier)
        else -> SkillRow(part = part, modifier = modifier)
    }
}

// ---------------------------------------------------------- reasoning card

/**
 * Reasoning part: the same chrome-less collapsible shell as the tool card
 * (W4-B item 1 — `collapsible.css:1-14`), with the thought label and a 13 sp
 * muted markdown body on the 12 dp rail.
 *
 * Fidelity checked against upstream `AssistantReasoningContent`
 * (`message-content.tsx:584-627`): default state is **collapsed**
 * (`open ?? state.open ?? defaultOpen ?? false`) and the chevron runs the
 * shared collapsible rotation (right when closed, down when open). The title is
 * `Thinking` while the turn still streams and `Thought` once it settles
 * (`message-content.tsx:603-607`, keys `ui.sessionTurn.status.thinking` /
 * `ui.tool.thought`). The body matches `reasoning-part` markdown: 13 sp,
 * `line-height-base` (20 sp), muted, headings/strong inheriting
 * (`OcMarkdown(muted = true)`).
 */
@Composable
internal fun ReasoningCard(
    part: ChatStore.UiPart,
    modifier: Modifier = Modifier,
    shimmer: Boolean,
) {
    val c = OcTheme.colors
    var expanded by rememberSaveable(part.id) { mutableStateOf(false) }
    val rotation by animateFloatAsState(if (expanded) 0f else -90f, label = "reasoning-chevron")
    // Hoisted: `semantics {}` is not a composable scope (see the tool card).
    val toggleLabel = stringResource(R.string.oc_toggle_tool)

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(TRIGGER_HEIGHT)
                .clickable { expanded = !expanded }
                .semantics { contentDescription = toggleLabel },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // `ui.tool.thought` is a shimmering title while the model is still
            // thinking (`research/03` §2.3); the spin-off pulse of M1 is gone.
            TextShimmer(
                text = stringResource(if (shimmer) R.string.oc_step_thinking else R.string.oc_thought),
                active = shimmer,
                baseColor = c.textBase,
                fontSize = 13.sp,
                fontWeight = FontWeight(530),
            )
            Spacer(Modifier.weight(1f))
            Icon(
                painter = painterResource(R.drawable.ic_oc_chevron_down),
                contentDescription = null,
                tint = c.textFaint,
                modifier = Modifier.size(16.dp).rotate(rotation),
            )
        }
        if (expanded) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, bottom = 8.dp)
                    .heightIn(max = TOOL_BODY_MAX_HEIGHT)
                    .verticalScroll(rememberScrollState()),
            ) {
                // U-4: a long thought folds to head+expander on top of the
                // card-level collapse (the card itself still starts closed).
                FoldableMarkdown(
                    markdown = part.text,
                    stateKey = part.id,
                    color = c.textMuted,
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    muted = true,
                )
            }
        }
    }
}

// -------------------------------------------------------------- compaction
//
// The `ui.messagePart.compaction` static line is now the empty-summary
// fallback inside [CompactionBlock] (`ToolRenderers.kt`); this alias keeps the
// old name resolvable for any out-of-tree caller while the dispatch uses the
// block directly.

/** `ui.messagePart.compaction` — a single muted separator line. */
@Composable
private fun CompactionRow(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.oc_compaction),
        color = OcTheme.colors.textFaint,
        fontSize = 12.sp,
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}

// -------------------------------------------------------------- file chip

/** Attachment chip (`layer02`, radius 6) — `ChatView.fileChip` L1156 semantics. */
@Composable
internal fun AttachmentChip(part: ChatStore.UiPart, modifier: Modifier = Modifier) {
    val c = OcTheme.colors
    // File parts may carry only a (possibly huge) data URL; never display that.
    val name = part.filename
        ?: part.url?.takeIf { !it.startsWith("data:") }
        ?: stringResource(R.string.oc_attachment_file)
    Row(
        modifier = modifier
            .clip(OcTheme.shapes.md)
            .background(c.layer02)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_oc_file),
            contentDescription = null,
            tint = c.iconMuted,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = name,
            color = c.textBase,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ------------------------------------------------- raw-JSON part renderers

/**
 * `patch` part (`schema/src/v1/session.ts:94-100` `{ hash, files[] }`) — the
 * upstream `patch` **tool** card semantics (`tool-renderer.tsx:375-383`: title
 * `ui.tool.patch`, subtitle `{n} {files}`), not a diff: the payload carries the
 * touched paths only. Expanding lists them (mono, on the 12 dp rail).
 */
@Composable
private fun PatchRow(part: ChatStore.UiPart, modifier: Modifier = Modifier) {
    val c = OcTheme.colors
    val patch = remember(part.rawJson) { RawParts.patch(part.rawJson) } ?: return
    var expanded by rememberSaveable(part.id) { mutableStateOf(false) }
    val rotation by animateFloatAsState(if (expanded) 0f else -90f, label = "patch-chevron")
    val expandable = patch.files.isNotEmpty()
    // Hoisted: `semantics {}` is not a composable scope (see the tool card).
    val toggleLabel = stringResource(R.string.oc_toggle_tool)

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(TRIGGER_HEIGHT)
                .clickable(enabled = expandable, role = Role.Button) { expanded = !expanded }
                .semantics { contentDescription = toggleLabel },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_oc_code),
                contentDescription = null,
                tint = c.iconMuted,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(GAP))
            Text(
                text = stringResource(R.string.oc_step_patch),
                color = c.textBase,
                fontSize = 13.sp,
                fontWeight = FontWeight(530),
            )
            if (expandable) {
                Spacer(Modifier.width(GAP))
                Text(
                    text = pluralStringResource(
                        R.plurals.oc_step_files,
                        patch.files.size,
                        patch.files.size,
                    ),
                    color = c.textMuted,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
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
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, bottom = 4.dp)
                    .heightIn(max = TOOL_BODY_MAX_HEIGHT)
                    .verticalScroll(rememberScrollState()),
            ) {
                patch.files.forEach { path ->
                    Text(
                        text = path,
                        color = c.textMuted,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                    )
                }
            }
        }
    }
}

/**
 * `subtask` part (`schema/src/v1/session.ts:204-218`) — the parent's delegation
 * record: agent + description line, the same information the upstream
 * `task-tool-card` headline carries (`basic-tool.css:320-389`).
 */
@Composable
private fun SubtaskRow(part: ChatStore.UiPart, modifier: Modifier = Modifier) {
    val c = OcTheme.colors
    val subtask = remember(part.rawJson) { RawParts.subtask(part.rawJson) } ?: return
    val title = RawParts.capitalize(subtask.agent) ?: stringResource(R.string.oc_step_agent)
    val subtitle = subtask.description?.takeIf { it.isNotBlank() }
        ?: subtask.prompt?.takeIf { it.isNotBlank() }

    Row(
        modifier = modifier.fillMaxWidth().height(TRIGGER_HEIGHT),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_oc_task),
            contentDescription = null,
            tint = c.iconMuted,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(GAP))
        Text(
            text = title,
            color = c.textBase,
            fontSize = 13.sp,
            fontWeight = FontWeight(530),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!subtitle.isNullOrBlank()) {
            Spacer(Modifier.width(GAP))
            Text(
                text = subtitle,
                color = c.textMuted,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * `agent` part (`schema/src/v1/session.ts:181-193`) — the upstream agent-switch
 * notice (`session-timeline-row.tsx:308-315` + `:450-481`): the label is 530
 * `text-faint` and the value follows with an 8 px gap, no separator.
 */
@Composable
private fun AgentNoticeRow(part: ChatStore.UiPart, modifier: Modifier = Modifier) {
    val agent = remember(part.rawJson) { RawParts.agent(part.rawJson) } ?: return
    NoticeLabelRow(
        label = stringResource(R.string.oc_step_agent_changed),
        value = RawParts.capitalize(agent.name),
        modifier = modifier,
    )
}

/**
 * The shared notice chrome (`session-timeline-row.tsx:308-315`): a 530
 * `text-faint` label and the optional muted value after an 8 px gap.
 */
@Composable
private fun NoticeLabelRow(
    label: String,
    value: String? = null,
    modifier: Modifier = Modifier,
) {
    val c = OcTheme.colors
    if (label.isEmpty() && value == null) return
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (label.isNotEmpty()) {
            Text(
                text = label,
                color = c.textFaint,
                fontSize = 13.sp,
                fontWeight = FontWeight(530),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!value.isNullOrBlank()) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = value,
                color = c.textMuted,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * `retry` part (`schema/src/v1/session.ts:220-231`) — the upstream retry card
 * (`components/session-retry.tsx`): danger warning icon, the provider error
 * truncated to 80 chars, and a shimmering `Retrying … attempt #k` line.
 */
@Composable
private fun RetryNoticeRow(part: ChatStore.UiPart, modifier: Modifier = Modifier) {
    val c = OcTheme.colors
    val retry = remember(part.rawJson) { RawParts.retry(part.rawJson) } ?: return
    val message = RawParts.retryMessage(retry.message)
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_oc_alert),
            contentDescription = null,
            tint = c.stateDangerFg,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(GAP))
        Column(modifier = Modifier.weight(1f)) {
            if (message != null) {
                Text(
                    text = message,
                    color = c.stateDangerFg,
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextShimmer(
                text = retryInfo(retry.attempt),
                active = true,
                baseColor = c.textMuted,
                fontSize = 13.sp,
                fontWeight = FontWeight.W400,
            )
        }
    }
}

/** `Retrying in {s}s - attempt #{k}` (`ui.sessionTurn.retry.*`). */
@Composable
private fun retryInfo(attempt: Int): String {
    val retrying = stringResource(R.string.oc_step_retrying)
    val label = stringResource(R.string.oc_step_retry_attempt, attempt)
    return if (attempt > 0) "$retrying - $label" else retrying
}

// ------------------------------------------------------------------ shared

/** Drawable for a [ToolPresentation.Icon] slot (shared with the group rows). */
internal fun toolIconRes(icon: ToolPresentation.Icon): Int = when (icon) {
    ToolPresentation.Icon.SHELL -> R.drawable.ic_oc_console
    ToolPresentation.Icon.CODE -> R.drawable.ic_oc_code
    ToolPresentation.Icon.GLASSES -> R.drawable.ic_oc_glasses
    ToolPresentation.Icon.SEARCH -> R.drawable.ic_oc_search
    ToolPresentation.Icon.GLOBE -> R.drawable.ic_oc_globe
    ToolPresentation.Icon.TASK -> R.drawable.ic_oc_task
    ToolPresentation.Icon.CHECK -> R.drawable.ic_oc_check
    ToolPresentation.Icon.FILE -> R.drawable.ic_oc_file
}

private val JSON_STRING_REGEX = Regex("\"(?:\\\\.|[^\"\\\\])*\"")

/** `bash-output` / `tool-output` `max-height: 240px` (`message-part.css:351-365`). */
internal val TOOL_BODY_MAX_HEIGHT = 240.dp

/** Collapsed trigger: `collapsible-trigger` 32 px / compact 28 px → 28 dp. */
private val TRIGGER_HEIGHT = 28.dp

/** Compact `tool-trigger` gap (`basic-tool.css:180-183`). */
private val GAP = 6.dp
