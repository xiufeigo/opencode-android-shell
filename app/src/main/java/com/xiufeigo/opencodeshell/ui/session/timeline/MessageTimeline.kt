package com.xiufeigo.opencodeshell.ui.session.timeline

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiufeigo.opencodeshell.ChatStore
import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.ui.components.OcScrollbar
import com.xiufeigo.opencodeshell.ui.components.TextShimmer
import com.xiufeigo.opencodeshell.ui.session.composer.mentionAnnotated
import com.xiufeigo.opencodeshell.ui.session.header.HEADER_HEIGHT
import com.xiufeigo.opencodeshell.ui.settings.LocalTimelineDisplayPrefs
import com.xiufeigo.opencodeshell.ui.theme.OcTheme
import com.xiufeigo.opencodeshell.ui.theme.ocRaisedShadow
import kotlinx.coroutines.launch
import kotlin.math.abs

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
 * - assistant turns are grouped through [TimelineGrouping] (the `compact`
 *   preset: every tool + reasoning folds into one `Used {n} {names}` group).
 * - [errorMessage] renders the session error card as the trailing row.
 *
 * W4-B additions:
 * - item 6: the per-message meta/copy rows ([AssistantMetaRow] / [UserMetaRow]);
 * - item 7: the trailing `Working` shimmer row and the floating
 *   `Jump to latest` button (upstream `message-timeline.tsx:376-391` /
 *   `virtualizer.tsx:646-673`).
 *
 * Wave 5-T additions (all four driven by [TimelineRows] / [TimelineSearch]):
 * - **A3 separators** — `TimelineRows.build()` interleaves the model-switch,
 *   compaction and interrupted separators with the message rows, and the list
 *   renders [TimelineSeparatorRow] for them;
 * - **A5 message rail** — [MessageNavRail] on the trailing edge, one tick per
 *   user message, tap → `animateScrollToItem`;
 * - **A6 turn diff summary** — `AssistantMessageRow` closes each turn with
 *   [TurnChangesBlock] when the turn touched files;
 * - **A2 search** — the shared [TimelineSearch] controller: this composable
 *   publishes the hit count, reveals the active hit, and paints the hit surface
 *   on the matching parts.
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
    /** A permission/question dock is up: upstream hides its `Working` row then. */
    hasPendingRequest: Boolean = false,
    shellExpanded: Boolean = LocalTimelineDisplayPrefs.current.shellToolPartsExpanded,
    editExpanded: Boolean = LocalTimelineDisplayPrefs.current.editToolPartsExpanded,
    /** `settings.general.row.reasoningSummaries` — false hides reasoning parts. */
    reasoningSummaries: Boolean = LocalTimelineDisplayPrefs.current.reasoningSummaries,
    /** Wave 5-T A5: the message-navigation rail (one tick per user turn). */
    messageNav: Boolean = true,
    /** Wave 5-T A6: the `{n} changed files` block of an assistant turn. */
    turnChanges: Boolean = true,
) {
    val thresholdPx = with(LocalDensity.current) { BOTTOM_THRESHOLD.toPx() }.toInt()
    var followNextAppend by remember { mutableStateOf(true) }
    // Bumped by the floating button: a discrete scroll request, so the jump
    // still happens when the SideEffect already considers the list "at bottom".
    var jumpSignal by remember { mutableStateOf(0) }
    val headerInset = if (stickyHeader != null) HEADER_HEIGHT else 0.dp
    val working = showWorkingRow(messages, runStatus, hasPendingRequest)
    // The `Working` / error rows are emitted before the message rows, i.e. at the
    // lowest indices of the `reverseLayout` list (the visual bottom); the rail
    // ticks need that offset to name a real `animateScrollToItem` index.
    val leadingRows = (if (working) 1 else 0) + (if (errorMessage != null) 1 else 0)

    // A3 — message rows with the model-switch / compaction / interrupted
    // separators folded in (`TimelineRows.build`). `rows` is top → bottom, so the
    // list gets it reversed, exactly like the message-only list before.
    val rows = remember(messages) { TimelineRows.build(messages) }
    val rowIndex = remember(rows, leadingRows) {
        val index = HashMap<String, Int>(rows.size)
        rows.asReversed().forEachIndexed { position, row ->
            if (row is TimelineRows.Row.Message) index[row.message.id] = leadingRows + position
        }
        index
    }
    // A5 — one rail tick per user message (oldest first = top → bottom).
    val ticks = remember(rows, leadingRows) { TimelineRows.userTicks(rows, leadingRows) }

    // A2 — the shared search controller: this screen publishes the hit count and
    // consumes the query/active index. `hits` is only computed while the bar is
    // open, so a closed session pays nothing.
    val searchOpen = TimelineSearch.open
    val searchQuery = TimelineSearch.query
    val searchActive = TimelineSearch.active
    val hits = remember(messages, searchQuery, searchOpen) {
        if (searchOpen) TimelineSearch.hits(messages, searchQuery) else emptyList()
    }
    val highlight = remember(hits, searchActive, searchQuery) {
        TimelineSearch.highlight(hits, searchActive, searchQuery)
    }
    val activeHit = remember(hits, searchActive) { TimelineSearch.hitAt(hits, searchActive) }

    // The count lives in the controller because the bar is rendered by the header,
    // a sibling subtree.
    SideEffect { TimelineSearch.publish(if (searchOpen) hits.size else 0) }

    // Leaving the session (or a tab switch on the mobile form) must not leave a
    // search behind: upstream tears the controller down with the screen.
    DisposableEffect(Unit) {
        TimelineSearch.setAvailable(true)
        onDispose { TimelineSearch.reset() }
    }

    // Reveal the active hit (`search-controller.ts:221-240`). Row granularity is
    // what this list has; upstream additionally scrolls to the part inside the
    // message.
    LaunchedEffect(activeHit?.messageID, searchOpen) {
        val id = activeHit?.messageID ?: return@LaunchedEffect
        if (!searchOpen) return@LaunchedEffect
        val index = rowIndex[id] ?: return@LaunchedEffect
        if (abs(listState.firstVisibleItemIndex - index) > JUMP_SCROLL_ITEMS) {
            listState.scrollToItem(index)
        } else {
            listState.animateScrollToItem(index)
        }
    }

    // A5 — which tick the viewport is on. `firstVisibleItemIndex` is the index of
    // the visible row nearest the bottom edge; the topmost visible row is the
    // largest index, so the pair spans the visual viewport. Keyed on `ticks`:
    // the derived state closes over the tick list, and a stale closure would keep
    // computing against the previous session's ticks.
    val currentTick by remember(listState, ticks) {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index
                ?: info.visibleItemsInfo.firstOrNull()?.index
                ?: return@derivedStateOf null
            TimelineRows.currentTick(ticks, info.visibleItemsInfo.firstOrNull()?.index ?: 0, last)
        }
    }
    val scrollScope = rememberCoroutineScope()

    // `reverseLayout = true` makes item 0 the newest message, pinned to the
    // visual bottom. "At bottom" = the first visible item (the newest) is at
    // offset 0. SideEffect reads the PRE-append layout (apply ran, measure has
    // not) so the append decision is correct and the list is not recomposed per
    // pixel.
    SideEffect {
        val info = listState.layoutInfo
        val first = info.visibleItemsInfo.firstOrNull()
        val atBottom = first == null ||
            (first.index <= 1 && first.offset <= thresholdPx)
        if (followNextAppend != atBottom) followNextAppend = atBottom
    }

    // The floating jump button must react to *scrolling*, which does not
    // recompose this composable on its own — a `SideEffect` read would only
    // sample the position on the next unrelated recomposition. A derived state
    // subscribes to `layoutInfo` and notifies only when the boolean flips.
    val atEnd by remember(listState, thresholdPx) {
        derivedStateOf {
            val first = listState.layoutInfo.visibleItemsInfo.firstOrNull()
            first == null || (first.index <= 1 && first.offset <= thresholdPx)
        }
    }

    // Any append that changes message count OR the trailing message's parts
    // (new tool/file part, streaming text growth, toolStatus/output change)
    // re-runs the follow. Structuring the fingerprint keeps a new tool card
    // from being left off-screen (review must-fix #2).
    val appendKey = appendFingerprint(messages)
    LaunchedEffect(appendKey, working) {
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
            // visual bottom. The upstream order of the scroll tail is
            // `… turns / error card / bottomSpacer(Working)`.
            if (working) {
                item(key = WORKING_ROW_KEY) { WorkingRow() }
            }
            if (errorMessage != null) {
                item(key = ERROR_CARD_KEY) { SessionErrorCard(message = errorMessage) }
            }
            // Top → bottom for the reader; `asReversed()` maps it onto the
            // `reverseLayout` list (index 0 = the visual bottom).
            items(items = rows.asReversed(), key = { it.key }) { row ->
                when (row) {
                    is TimelineRows.Row.Separator -> TimelineSeparatorRow(separator = row)

                    is TimelineRows.Row.Message -> MessageRow(
                        message = row.message,
                        isTrailing = row.message.id == messages.lastOrNull()?.id &&
                            runStatus == ChatStore.RunStatus.RUNNING,
                        toolOpen = toolOpen,
                        onToggleTool = onToggleTool,
                        shellExpanded = shellExpanded,
                        editExpanded = editExpanded,
                        reasoningSummaries = reasoningSummaries,
                        highlight = highlight,
                        turnChanges = turnChanges,
                    )
                }
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
        // Wave 5-T A5 — the compact `MessageNav` rail (`message-nav.tsx:48-59`),
        // vertically centred on the list viewport, inset past the scrollbar strip
        // so the two 12–24 dp trailing overlays never fight.
        if (messageNav && ticks.isNotEmpty()) {
            BoxWithConstraints(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .padding(
                        top = headerInset,
                        end = SCROLLBAR_STRIP + NAV_INSET,
                        bottom = contentPadding.calculateBottomPadding() + 8.dp,
                    ),
                contentAlignment = Alignment.CenterEnd,
            ) {
                MessageNavRail(
                    ticks = ticks,
                    currentId = currentTick,
                    maxHeight = maxHeight,
                    onSelect = { tick ->
                        // `animateScrollToItem` — the Compose counterpart of the
                        // rail's `revealMessage` (`message-nav.tsx:28-31`).
                        scrollScope.launch {
                            if (abs(listState.firstVisibleItemIndex - tick.index) > JUMP_SCROLL_ITEMS) {
                                listState.scrollToItem(tick.index)
                            } else {
                                listState.animateScrollToItem(tick.index)
                            }
                        }
                    },
                )
            }
        }
        // W4-B item 7 — `virtualizer.tsx:646-673`: centred, 32×28 dp, radius 8,
        // `bg-base` at 92 % + backdrop blur, 200 ms `ease-out` scale/translate
        // in and out. Shown only while the viewport is away from the end.
        AnimatedVisibility(
            visible = !atEnd && messages.isNotEmpty(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = contentPadding.calculateBottomPadding() + 8.dp),
            enter = fadeIn(tween(200, easing = LinearOutSlowInEasing)) +
                scaleIn(
                    initialScale = 0.8f,
                    animationSpec = tween(200, easing = LinearOutSlowInEasing),
                ),
            exit = fadeOut(tween(200, easing = LinearOutSlowInEasing), targetAlpha = 0f) +
                scaleOut(
                    targetScale = 0.8f,
                    animationSpec = tween(200, easing = LinearOutSlowInEasing),
                ),
        ) {
            JumpToLatestButton(onClick = { jumpSignal++ })
        }
        LaunchedEffect(jumpSignal) {
            if (jumpSignal == 0) return@LaunchedEffect
            followNextAppend = true
            if (listState.firstVisibleItemIndex > JUMP_SCROLL_ITEMS) {
                listState.scrollToItem(0)
            } else {
                listState.animateScrollToItem(0)
            }
        }
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
    reasoningSummaries: Boolean,
    highlight: TimelineSearch.Highlight,
    turnChanges: Boolean,
) {
    if (message.role == "user") {
        UserMessageRow(message, highlight)
    } else {
        AssistantMessageRow(
            message = message,
            isTrailing = isTrailing,
            toolOpen = toolOpen,
            onToggleTool = onToggleTool,
            shellExpanded = shellExpanded,
            editExpanded = editExpanded,
            reasoningSummaries = reasoningSummaries,
            highlight = highlight,
            turnChanges = turnChanges,
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
 *
 * W4-B item 6: the copy/meta row of the message's **last** text part follows
 * that body (`message-content.tsx:539-554`), so a text part keeps its meta even
 * when later tool parts were appended to the turn.
 *
 * Wave 5-T: `highlight` paints the search-hit surface of a part (or of a whole
 * tool group) and `turnChanges` closes the turn with [TurnChangesBlock].
 */
@Composable
private fun AssistantMessageRow(
    message: ChatStore.UiMessage,
    isTrailing: Boolean,
    toolOpen: Map<String, Boolean>,
    onToggleTool: (String, Boolean) -> Unit,
    shellExpanded: Boolean,
    editExpanded: Boolean,
    reasoningSummaries: Boolean,
    highlight: TimelineSearch.Highlight,
    turnChanges: Boolean,
) {
    val rows = remember(message.parts, isTrailing, reasoningSummaries) {
        TimelineGrouping.rowsFor(
            message.parts,
            working = isTrailing,
            reasoningSummaries = reasoningSummaries,
        )
    }
    val meta = remember(message) { MessageMeta.of(message) }
    val copyText = remember(message.parts) {
        message.parts.filter { it.type == "text" && it.text.isNotBlank() }
            .joinToString("\n\n") { it.text }
    }
    val metaAfter = if (copyText.isNotBlank()) {
        rows.indexOfLast { it is TimelineGrouping.Row.Part && it.part.type == "text" && it.part.text.isNotBlank() }
    } else {
        -1
    }
    val changedFiles = remember(message.parts, turnChanges) {
        if (turnChanges) TurnChanges.of(message) else emptyList()
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
                    modifier = hitSurface(
                        hit = row.part.id in highlight.parts,
                        active = row.part.id == highlight.activePart,
                    ),
                    isTrailing = isTrailing && index == lastIndex,
                    userOpen = toolOpen[row.part.id],
                    onToggleTool = onToggleTool,
                    shellExpanded = shellExpanded,
                    editExpanded = editExpanded,
                    reasoningSummaries = reasoningSummaries,
                )

                is TimelineGrouping.Row.Context -> ContextToolGroupRow(
                    group = row,
                    // A grouped part has no row of its own: the group row carries
                    // its hit surface (`TimelineGrouping.rowsFor`).
                    modifier = hitSurface(
                        hit = row.parts.any { it.id in highlight.parts },
                        active = row.parts.any { it.id == highlight.activePart },
                    ),
                    expanded = toolOpen[row.key] == true,
                    onToggle = { open -> onToggleTool(row.key, open) },
                    toolOpen = toolOpen,
                    onToggleTool = onToggleTool,
                    shellExpanded = shellExpanded,
                    editExpanded = editExpanded,
                )
            }
            if (index == metaAfter) {
                AssistantMetaRow(text = copyText, meta = meta)
            }
        }
        // A6 — `[data-slot=session-turn-diffs]` closes the assistant turn
        // (`session-turn.css:137-206`).
        TurnChangesBlock(files = changedFiles, messageId = message.id)
    }
}

/**
 * The search-hit surface of one row: a faint accent band for a hit, a stronger
 * one for the **active** hit, nothing otherwise.
 *
 * This is the documented granularity limit of A2 on the Android side: the
 * assistant body is rendered by `OcMarkdown` (package `ui.markdown`) and the
 * user bubble by `mentionAnnotated` (package `ui.session.composer`), both out of
 * this wave's scope, so a markdown body highlights per part. The user bubble does
 * get glyph-level highlighting — it is built in this file.
 */
@Composable
private fun hitSurface(hit: Boolean, active: Boolean): Modifier {
    if (!hit && !active) return Modifier
    val c = OcTheme.colors
    val alpha = if (active) ACTIVE_HIT_ALPHA else HIT_ALPHA
    return Modifier
        .clip(OcTheme.shapes.sm)
        .background(c.accent.copy(alpha = alpha))
}

/**
 * User turn: right-aligned bubble capped at 82 % of the list width — upstream
 * `[data-slot=user-message-body] { max-width: min(82%, 64ch) }`
 * (`message-part.css` L129) — with the attachment chips above it and the
 * meta/copy row below it (W4-B item 6, `message-content.tsx:311-354`).
 *
 * Bubble surface follows `[data-slot=user-message-text]` (`message-part.css`
 * L138): `layer02` background, radius 10 (`shapes.xl`), 12/8 padding, 14 sp
 * `line-height-large` body.
 *
 * M1.5: the body is selectable (Compose text — `SelectionContainer` works here)
 * and `@file`/`@agent`/`@reference` tokens are coloured with the same
 * classifier as the composer (`research/02` §4.2 "session-turn 间的 message 高亮").
 *
 * W5-T A2: the bubble is the one surface that can carry a **glyph-level** search
 * highlight — the mention spans are built here, so the hit ranges are painted on
 * top of them (upstream `::highlight()` over `[data-slot=user-message-text]`,
 * `search-controller.ts:19`).
 */
@Composable
private fun UserMessageRow(message: ChatStore.UiMessage, highlight: TimelineSearch.Highlight) {
    val c = OcTheme.colors
    val text = message.parts.filter { it.type == "text" }.joinToString("\n\n") { it.text }
    val files = message.parts.filter { it.type == "file" }
    val meta = remember(message) { MessageMeta.of(message) }
    // A2: the bubble can carry a glyph-level hit highlight because its spans are
    // built here (the joined body is exactly what the `Text` renders, so ranges
    // measured on it are the ranges to paint).
    val textPartIds = remember(message.parts) {
        message.parts.filter { it.type == "text" }.map { it.id }
    }
    val bubbleHit = textPartIds.any { it in highlight.parts }
    val hitRanges = remember(text, highlight.needle, bubbleHit) {
        if (bubbleHit && highlight.needle.isNotEmpty()) {
            TimelineSearch.ranges(text, highlight.needle)
        } else {
            emptyList()
        }
    }
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        files.forEachIndexed { index, part ->
            Box(modifier = Modifier.padding(top = if (index == 0) 0.dp else PART_GAP)) {
                AttachmentChip(part)
            }
        }
        if (text.isNotEmpty()) {
            val body = remember(text, c, hitRanges) {
                highlightedBody(mentionAnnotated(text, c), hitRanges, c)
            }
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
                            .background(c.layer02)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
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
            UserMetaRow(text = text, meta = meta)
        }
    }
}

/**
 * `[data-component="session-working"]` (`message-timeline.tsx:384-391`):
 * `flex h-9 items-center gap-2 pt-3 text-[13px] font-[530]`, the label rendered
 * through `TextShimmer` with `role="status"`.
 */
@Composable
private fun WorkingRow(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(WORKING_ROW_HEIGHT)
            .padding(top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextShimmer(
            text = stringResource(R.string.oc_working_label),
            active = true,
            baseColor = OcTheme.colors.textBase,
            fontSize = 13.sp,
            fontWeight = FontWeight(530),
        )
    }
}

/**
 * `w-8 h-7 rounded-lg` (`virtualizer.tsx:655-672`): 32×28 dp, radius 8,
 * `color-mix(in srgb, var(--background-base) 92%, transparent)` surface, the
 * raised elevation plus an 8 px `bg-base` drop shadow, and a 16 px
 * `arrow-down` glyph.
 */
@Composable
private fun JumpToLatestButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = OcTheme.colors
    val shape = OcTheme.shapes.lg
    Box(
        modifier = modifier
            .size(width = 32.dp, height = 28.dp)
            .ocRaisedShadow(shape)
            .clip(shape)
            .background(c.bgBase.copy(alpha = 0.92f))
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_oc_chevron_down),
            contentDescription = stringResource(R.string.oc_jump_to_latest),
            tint = c.textBase,
            modifier = Modifier.size(16.dp),
        )
    }
}

/** The upstream `scrollEndThreshold` (80 px) scaled to dp. */
private val BOTTOM_THRESHOLD = 80.dp

/**
 * Hit surface of a search match. Upstream paints the `::highlight()` glyph runs
 * (`search-controller.ts:17-18`, `timeline-search-hit` / `…-active`); a markdown
 * body can only be tinted per part here, so the band is fainter than a glyph run
 * would be and the active one is the strong one.
 */
private const val HIT_ALPHA = 0.10f
private const val ACTIVE_HIT_ALPHA = 0.22f

/**
 * `OcScrollbar`'s 12 dp hit strip occupies the trailing edge; the A5 rail is
 * offset past it so both stay reachable.
 */
private val SCROLLBAR_STRIP = 12.dp
private val NAV_INSET = 2.dp

/**
 * Search-hit highlighting of the user bubble: the mention spans of
 * [mentionAnnotated] are kept and a background is added over every hit range
 * (the Compose equivalent of upstream's `CSS.highlights` sets,
 * `search-controller.ts:69-79`).
 */
private fun highlightedBody(
    base: AnnotatedString,
    ranges: List<IntRange>,
    colors: com.xiufeigo.opencodeshell.ui.theme.OcColors,
): AnnotatedString {
    if (ranges.isEmpty()) return base
    val style = SpanStyle(background = colors.accent.copy(alpha = HIT_ALPHA))
    return buildAnnotatedString {
        append(base)
        ranges.forEach { addStyle(style, it.first, it.last + 1) }
    }
}

/** `[data-slot=session-turn-list] gap:24px`. */
private val TURN_GAP = 24.dp

/** `[data-slot=session-turn-assistant-content] gap:12px`. */
private val PART_GAP = 12.dp

/** `h-9` of the `session-working` row (`message-timeline.tsx:385`). */
private val WORKING_ROW_HEIGHT = 36.dp

/** Stable `LazyColumn` key of the trailing session error card. */
internal const val ERROR_CARD_KEY = "session-error-card"

/** Stable `LazyColumn` key of the `Working` bottom spacer. */
internal const val WORKING_ROW_KEY = "session-working"

private const val BUBBLE_MAX_RATIO = 0.82f
private const val JUMP_SCROLL_ITEMS = 20

/**
 * `showWorking()` (`message-timeline.tsx:331-372`): the `Working` row is the
 * fallback for "busy, but nothing visible to look at yet".
 *
 * Android data cannot express every upstream guard (`time.streamed`, pending
 * steers, background tasks), so the port keeps the ones that exist: the run must
 * be busy, no permission/question dock may be open, the newest message must be
 * an assistant turn, a trailing `retry` part wins over the row, a non-blank
 * trailing text part means the model is visibly writing, and a standalone
 * running tool card is visible activity on its own. Grouped tools deliberately
 * do **not** suppress it — upstream keeps the fallback for `context` groups
 * (`:362-363`) regardless of their disclosure state.
 *
 * Pure, so it is covered by a JVM test.
 */
internal fun showWorkingRow(
    messages: List<ChatStore.UiMessage>,
    runStatus: ChatStore.RunStatus,
    hasPendingRequest: Boolean = false,
): Boolean {
    if (runStatus != ChatStore.RunStatus.RUNNING) return false
    if (hasPendingRequest) return false
    val last = messages.lastOrNull() ?: return false
    if (last.role != "assistant") return false
    val visible = last.parts.lastOrNull { it.type !in TimelineGrouping.INVISIBLE_TYPES } ?: return true
    if (visible.type == "retry") return false
    if (visible.type == "text" && visible.text.isNotBlank()) return false
    val standaloneRunning = TimelineGrouping.rowsFor(last.parts, working = true).any { row ->
        row is TimelineGrouping.Row.Part && row.part.type == "tool" &&
            row.part.toolStatus == "running"
    }
    return !standaloneRunning
}

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
