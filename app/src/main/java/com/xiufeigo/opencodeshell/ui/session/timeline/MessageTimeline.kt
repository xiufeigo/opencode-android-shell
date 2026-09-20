package com.xiufeigo.opencodeshell.ui.session.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiufeigo.opencodeshell.ChatStore
import com.xiufeigo.opencodeshell.ui.components.OcScrollbar
import com.xiufeigo.opencodeshell.ui.session.composer.mentionAnnotated
import com.xiufeigo.opencodeshell.ui.session.header.HEADER_HEIGHT
import com.xiufeigo.opencodeshell.ui.theme.OcTheme

/**
 * Message timeline. Mirrors the upstream virtual list contract
 * (`research/02` §2.1): `anchorTo: "end"`, `followOnAppend: true`,
 * `scrollEndThreshold: 80`, turn gap 24 dp, assistant part gap 12 dp.
 *
 * M2 additions:
 * - [stickyHeader] renders the session header (`research/02` §2.2) as the
 *   timeline's pinned top row; the list viewport is inset by its height, which
 *   is the Compose equivalent of the virtualizer's `scrollMargin` (64 px) +
 *   `--sticky-header-height`. Content does **not** scroll behind it, so no
 *   `to bottom` gradient is needed (documented deviation in the status note).
 * - [toolOpen] is the screen-level `Record<partId, boolean>` (upstream
 *   `timelineCache.toolOpen`): an expanded tool card survives recycling.
 * - assistant turns are grouped through [TimelineGrouping] (consecutive
 *   `read/glob/grep/list` → one "Exploring" row, `todowrite` hidden).
 * - [errorMessage] renders the session error card as the trailing row.
 *
 * Follow logic (why it is not a `derivedStateOf` read in composition):
 * a new message changes the list, and the *next* layout is what pushes the
 * viewport away from the end — so asking "was the user at the bottom?" after
 * that layout returns the wrong answer. [SideEffect] runs after the
 * composition applies but **before** the frame's measure pass, so the
 * `layoutInfo` it reads still describes the previous content: exactly the
 * pre-append value the decision needs. Reading it there also avoids
 * subscribing the timeline to every scroll pixel (a `derivedStateOf` read in
 * composition would recompose the list on each drag).
 */
@Composable
fun MessageTimeline(
    messages: List<ChatStore.UiMessage>,
    runStatus: ChatStore.RunStatus,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 24.dp),
    listState: LazyListState = rememberLazyListState(),
    stickyHeader: (@Composable () -> Unit)? = null,
    toolOpen: Map<String, Boolean> = emptyMap(),
    /** Reports `(rowKey, newOpen)`; see `PartRenderer.onToggleTool`. */
    onToggleTool: (String, Boolean) -> Unit = { _, _ -> },
    errorMessage: String? = null,
    shellExpanded: Boolean = ToolCardPolicy.DEFAULT_SHELL_EXPANDED,
    editExpanded: Boolean = ToolCardPolicy.DEFAULT_EDIT_EXPANDED,
) {
    val thresholdPx = with(LocalDensity.current) { BOTTOM_THRESHOLD.toPx() }.toInt()
    var followNextAppend by remember { mutableStateOf(true) }
    val headerInset = if (stickyHeader != null) HEADER_HEIGHT else 0.dp

    // `reverseLayout = true` makes item 0 the newest message, pinned to the
    // visual bottom. "At bottom" = the first visible item (the newest) is at
    // offset 0. SideEffect reads the PRE-append layout (apply ran, measure has
    // not) so the decision is correct and the list is not recomposed per pixel.
    SideEffect {
        val info = listState.layoutInfo
        val first = info.visibleItemsInfo.firstOrNull()
        val atBottom = first == null ||
            (first.index <= 1 && first.offset <= thresholdPx)
        if (followNextAppend != atBottom) followNextAppend = atBottom
    }

    // Any append that changes message count OR the trailing message's parts
    // (new tool/file part, streaming text growth, toolStatus/output change)
    // re-runs the follow. Structuring the fingerprint keeps a new tool card
    // from being left off-screen (review must-fix #2).
    val appendKey = appendFingerprint(messages)
    LaunchedEffect(appendKey) {
        // With reverseLayout the bottom anchor is item 0; scrollToItem(0) pins
        // it to the viewport end — correct even when that message is taller
        // than a screen (review must-fix #1). Near-bottom append animates,
        // cold history load jumps.
        if (!followNextAppend) return@LaunchedEffect
        if (listState.firstVisibleItemIndex > JUMP_SCROLL_ITEMS) {
            listState.scrollToItem(0)
        } else {
            listState.animateScrollToItem(0)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(top = headerInset),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(TURN_GAP),
            reverseLayout = true,
        ) {
            // Emitted first: with `reverseLayout` the first entry sits at the
            // visual bottom, i.e. right below the newest turn.
            if (errorMessage != null) {
                item(key = ERROR_CARD_KEY) { SessionErrorCard(message = errorMessage) }
            }
            items(items = messages.asReversed(), key = { it.id }) { message ->
                MessageRow(
                    message = message,
                    isTrailing = message.id == messages.lastOrNull()?.id &&
                        runStatus == ChatStore.RunStatus.RUNNING,
                    toolOpen = toolOpen,
                    onToggleTool = onToggleTool,
                    shellExpanded = shellExpanded,
                    editExpanded = editExpanded,
                )
            }
        }
        if (stickyHeader != null) {
            // Pinned row (`sticky top-0 z-30` + the 48 px gradient in §2.2). The
            // gradient only matters when content scrolls underneath; the list is
            // inset instead, so the row stays opaque `bg-base`.
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .background(OcTheme.colors.bgBase),
            ) {
                stickyHeader()
            }
        }
        // M6: the 12 dp hit strip + 3 dp pill on the trailing edge, inset by the
        // header and by the floating composer's measured height so the bar only
        // ever spans the real list viewport.
        OcScrollbar(
            state = listState,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(
                    top = headerInset,
                    bottom = contentPadding.calculateBottomPadding(),
                ),
        )
    }
}

@Composable
private fun MessageRow(
    message: ChatStore.UiMessage,
    isTrailing: Boolean,
    toolOpen: Map<String, Boolean>,
    onToggleTool: (String, Boolean) -> Unit,
    shellExpanded: Boolean,
    editExpanded: Boolean,
) {
    if (message.role == "user") {
        UserMessageRow(message)
    } else {
        AssistantMessageRow(
            message = message,
            isTrailing = isTrailing,
            toolOpen = toolOpen,
            onToggleTool = onToggleTool,
            shellExpanded = shellExpanded,
            editExpanded = editExpanded,
        )
    }
}

/**
 * Assistant turn: display rows stacked with a 12 dp gap
 * (`[data-slot=session-turn-assistant-content] gap:12px`). The trailing row of
 * a running turn is the one allowed to shimmer.
 *
 * `groupParts()` runs here — per message, not across messages — because the
 * upstream grouping is scoped to one assistant turn's part list and a turn is
 * exactly one `LazyColumn` item (which keeps the stable item key).
 */
@Composable
private fun AssistantMessageRow(
    message: ChatStore.UiMessage,
    isTrailing: Boolean,
    toolOpen: Map<String, Boolean>,
    onToggleTool: (String, Boolean) -> Unit,
    shellExpanded: Boolean,
    editExpanded: Boolean,
) {
    val rows = remember(message.parts, isTrailing) {
        TimelineGrouping.rowsFor(message.parts, working = isTrailing)
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(PART_GAP),
    ) {
        val lastIndex = rows.lastIndex
        rows.forEachIndexed { index, row ->
            when (row) {
                is TimelineGrouping.Row.Part -> PartRenderer(
                    part = row.part,
                    isTrailing = isTrailing && index == lastIndex,
                    userOpen = toolOpen[row.part.id],
                    onToggleTool = onToggleTool,
                    shellExpanded = shellExpanded,
                    editExpanded = editExpanded,
                )

                is TimelineGrouping.Row.Context -> ContextToolGroupRow(
                    group = row,
                    expanded = toolOpen[row.key] == true,
                    onToggle = { open -> onToggleTool(row.key, open) },
                )
            }
        }
    }
}

/**
 * User turn: right-aligned `layer01` bubble (radius 10, 14 sp body) with the
 * attachment chips above it, capped at 82 % of the list width
 * (`ChatView.BUBBLE_MAX_RATIO`).
 *
 * M1.5: the body is selectable (Compose text — `SelectionContainer` works here,
 * unlike the Markwon `AndroidView` bodies) and `@file`/`@agent`/`@reference`
 * tokens are coloured with the same classifier as the composer
 * (`research/02` §4.2 "session-turn 间的 message 高亮").
 */
@Composable
private fun UserMessageRow(message: ChatStore.UiMessage) {
    val c = OcTheme.colors
    val text = message.parts.filter { it.type == "text" }.joinToString("\n\n") { it.text }
    val files = message.parts.filter { it.type == "file" }
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        files.forEachIndexed { index, part ->
            Box(modifier = Modifier.padding(top = if (index == 0) 0.dp else PART_GAP)) {
                AttachmentChip(part)
            }
        }
        if (text.isNotEmpty()) {
            val body = remember(text, c) { mentionAnnotated(text, c) }
            Box(
                modifier = Modifier
                    .fillMaxWidth(BUBBLE_MAX_RATIO)
                    .wrapContentWidth(Alignment.End)
                    .padding(top = if (files.isEmpty()) 0.dp else PART_GAP),
            ) {
                SelectionContainer {
                    Box(
                        modifier = Modifier
                            .clip(OcTheme.shapes.xl)
                            .background(c.layer01)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    ) {
                        Text(
                            text = body,
                            color = c.textBase,
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                        )
                    }
                }
            }
        }
    }
}

/** The upstream `scrollEndThreshold` (80 px) scaled to dp. */
private val BOTTOM_THRESHOLD = 80.dp

/** `[data-slot=session-turn-list] gap:24px`. */
private val TURN_GAP = 24.dp

/** `[data-slot=session-turn-assistant-content] gap:12px`. */
private val PART_GAP = 12.dp

/** Stable `LazyColumn` key of the trailing session error card. */
internal const val ERROR_CARD_KEY = "session-error-card"

private const val BUBBLE_MAX_RATIO = 0.82f
private const val JUMP_SCROLL_ITEMS = 20

/**
 * Structured fingerprint of the list state that must trigger a bottom-follow:
 * message count + the trailing message's id, part count, and per-part text /
 * toolOutput length + toolStatus. Covers every event that grows or changes the
 * newest content (new part, streamed text, tool output/status flip), so a tool
 * card that appears or fills while streaming is not left off-screen.
 *
 * Pure so it is covered by a JVM unit test (`TimelineFingerprintTest`).
 */
internal fun appendFingerprint(messages: List<ChatStore.UiMessage>): Long {
    var hash = messages.size.toLong()
    val last = messages.lastOrNull() ?: return hash
    hash = hash * 31 + last.id.hashCode()
    hash = hash * 31 + last.parts.size
    for (part in last.parts) {
        hash = hash * 31 + part.text.length
        hash = hash * 31 + (part.toolName?.hashCode() ?: 0)
        hash = hash * 31 + (part.toolStatus?.hashCode() ?: 0)
        hash = hash * 31 + (part.toolOutput?.length ?: 0)
    }
    return hash
}
