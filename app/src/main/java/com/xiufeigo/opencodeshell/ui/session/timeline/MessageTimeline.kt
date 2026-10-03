package com.xiufeigo.opencodeshell.ui.session.timeline

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import com.xiufeigo.opencodeshell.ui.markdown.LocalMarkdownImageReader
import com.xiufeigo.opencodeshell.ui.markdown.MarkdownImages
import com.xiufeigo.opencodeshell.ui.session.SessionPaneLayout
import com.xiufeigo.opencodeshell.ui.session.composer.mentionAnnotated
import com.xiufeigo.opencodeshell.ui.settings.LocalTimelineDisplayPrefs
import com.xiufeigo.opencodeshell.ui.theme.OcTheme
import com.xiufeigo.opencodeshell.ui.theme.ocRaisedShadow
import kotlinx.coroutines.delay
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
 * - [errorMessage] renders the session error card as the trailing row; the
 *   failures of the turns themselves are [TimelineRows.Row.Error] rows — one
 *   card each, in turn order (see [SessionErrorCard]).
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
    /**
     * Newest failure of the session, mirrored by `ChatState.error`: this is the
     * **trailing card** — the pre-`Row.Error` presentation, kept as the fallback
     * for a failure whose row is not in the list (a state that only carries the
     * status, a revert-projected transcript). When the failure row is present
     * (the normal case, see [ChatStore.ERROR_ROLE]) it renders the card itself,
     * so the two never show one failure twice.
     */
    errorMessage: String? = null,
    /** Raw `session.error` JSON for [errorMessage] (type/status for the error card classifier). */
    errorDetail: String? = null,
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
    /**
     * Per-message「撤销到此」(`message-content.tsx:328-338`, user rows only).
     * `null` hides the button — upstream's `Show when={props.actions?.revert}`.
     */
    onRevertMessage: ((String) -> Unit)? = null,
    /** A revert is in flight: the buttons disable (upstream `state.reverting`). */
    revertBusy: Boolean = false,
    /**
     * U-3 draft handoff: publishes the message-id → reversed-list position map
     * ([rowIndex]) whenever the rows change, so the screen can scroll back to
     * a restored draft's referenced message over its own hoisted [listState].
     * Null (default) skips the publish; the rail keeps working as before.
     */
    onMessageIndex: ((Map<String, Int>) -> Unit)? = null,
    /**
     * BUG-2 (v36 tB): re-deliver a parked steer ([ChatStore.PENDING_ROLE] row)
     * by its admission id — `ChatViewModel.retryPendingDelivery`. The row offers
     * it only while the session is **not** running (`runStatus`): a steer parked
     * behind a working turn is the normal case, one parked on an idle session is
     * the wedge the v36 report measured, and that is when a user needs the way
     * out. Null (the default) hides the action.
     */
    onRetryPending: ((String) -> Unit)? = null,
    /**
     * A-1: the session's workspace directory, used to scope **relative**
     * markdown images (`MarkdownImages.reader(context, directory)` → the
     * `location[directory]` query of `GET /api/fs/read`). Null (the default)
     * leaves the reader resolving the directory itself via `GET /api/location`,
     * which is the pre-existing behaviour; an absolute image path is scoped to
     * its own parent either way.
     */
    imageDirectory: String? = null,
) {
    val thresholdPx = with(LocalDensity.current) { BOTTOM_THRESHOLD.toPx() }.toInt()
    var followNextAppend by remember { mutableStateOf(true) }
    // Bumped by the floating button: a discrete scroll request, so the jump
    // still happens when the SideEffect already considers the list "at bottom".
    var jumpSignal by remember { mutableStateOf(0) }
    // v58 P0: the pinned row is measured, not assumed. The sticky row is an
    // opaque `bg-base` overlay drawn **over** the list (`sticky top-0 z-30`),
    // so whatever the list does not inset by is covered — a row taller than
    // [SessionPaneLayout.HEADER_INSET_FALLBACK] (the session header row's own
    // height) clipped the top of the newest row and it could not be scrolled out
    // of the bar, and a shorter one spent the pane's dp on empty space. The
    // default is the constant so the first frame is unchanged; the measurement
    // takes over on the next.
    //
    // v77 P0: measured in px and converted at read time, so the inset follows the
    // Configuration's density instead of the one that was current when the
    // callback was built (0 px is the "not measured yet" reading `headerInset`
    // turns back into the constant — the v58 first-frame default).
    var stickyHeaderPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val headerInset = if (stickyHeader != null) {
        SessionPaneLayout.headerInset(with(density) { stickyHeaderPx.toDp() })
    } else {
        0.dp
    }
    val working = showWorkingRow(messages, runStatus, hasPendingRequest)

    // Stall notice (device P1, 2026-09-25): a turn that stops producing events
    // looks exactly like a busy one — the shimmering `Working` label stayed up
    // for the whole 38-minute hung model call, with nothing telling the user that
    // nothing was arriving. The timer below is that missing signal, and it hangs
    // off the *same* condition as the row itself: it runs only while `working`,
    // i.e. the run is busy **and** no visible content explains the wait (a
    // streaming text part, a running tool card, a retry card or a pending dock
    // all switch `working` off), so it can never contradict what is on screen.
    //
    // `appendFingerprint` is the activity probe: it changes on every event that
    // grows or changes the newest content (new part, streamed text, tool output
    // or status flip, a new message). Any change restarts this effect, which
    // zeroes the notice — i.e. it disappears by itself as soon as the turn
    // answers again. A `SessionScreen`-side clock is impossible: `MessageTimeline`
    // is called with a fixed argument list (see the class note), so the timeline
    // has to derive the signal from what it already receives.
    var noResponseMinutes by remember { mutableStateOf(0) }
    LaunchedEffect(appendFingerprint(messages), working) {
        noResponseMinutes = 0
        if (!working) return@LaunchedEffect
        val startedAt = System.currentTimeMillis()
        while (true) {
            noResponseMinutes = stallNoticeMinutes(System.currentTimeMillis() - startedAt)
            delay(NO_RESPONSE_POLL_MS)
        }
    }

    // A3 — message rows with the model-switch / compaction / interrupted
    // separators folded in (`TimelineRows.build`). `rows` is top → bottom, so the
    // list gets it reversed, exactly like the message-only list before. Each
    // failed turn is one `Row.Error` here, in turn order.
    val rows = remember(messages) { TimelineRows.build(messages) }
    // The trailing card stands in for a failure the rows do not already carry —
    // see [trailingErrorMessage].
    val trailingError = remember(errorMessage, rows) { trailingErrorMessage(errorMessage, rows) }
    // The `Working` / error rows are emitted before the message rows, i.e. at the
    // lowest indices of the `reverseLayout` list (the visual bottom); the rail
    // ticks need that offset to name a real `animateScrollToItem` index.
    val leadingRows = (if (working) 1 else 0) + (if (trailingError != null) 1 else 0)
    // `asReversed()` is a view, but remembering it keeps the LazyColumn's item
    // list instance stable across unrelated recompositions (same content →
    // same slots, no remeasure).
    val reversed = remember(rows) { rows.asReversed() }
    // The turn a live append changes: the newest message that is a real turn
    // (a failure row sits after the turn it closes, and a parked steer is not a
    // turn at all — upstream keeps the pending steer from owning assistant work
    // until it is delivered, `controller-projection.ts:28`).
    val newestMessageID = remember(messages) {
        messages.lastOrNull { !ChatStore.isClientRow(it) }?.id
    }
    val rowIndex = remember(rows, leadingRows) {
        val index = HashMap<String, Int>(rows.size)
        rows.asReversed().forEachIndexed { position, row ->
            if (row is TimelineRows.Row.Message) index[row.message.id] = leadingRows + position
        }
        index
    }
    // A5 — one rail tick per user message (oldest first = top → bottom).
    val ticks = remember(rows, leadingRows) { TimelineRows.userTicks(rows, leadingRows) }
    // v63: the rail overlays the list's trailing edge, so while it is visible the
    // list reserves its gutter (same width upstream spends on the rail's own
    // `shrink-0` layout column, `share/[shareID].tsx:276-281`). Without it an
    // end-aligned row — the user message's meta/copy row, an interrupted
    // assistant meta row — slides under the ticks, which read as a 「≣」 stain
    // pinned on the copy icon (`research/v63_verify/5554/50_find_closed2.png`).
    val railVisible = messageNav && MessageNavRailPolicy.isVisible(ticks.size)
    val listContentPadding = if (railVisible) {
        val direction = LocalLayoutDirection.current
        PaddingValues(
            start = contentPadding.calculateStartPadding(direction),
            top = contentPadding.calculateTopPadding(),
            end = contentPadding.calculateEndPadding(direction) + RAIL_GUTTER,
            bottom = contentPadding.calculateBottomPadding(),
        )
    } else {
        contentPadding
    }
    // U-3: hand the position map to the screen (see [onMessageIndex]).
    LaunchedEffect(rowIndex) { onMessageIndex?.invoke(rowIndex) }

    // A2 — the shared search controller: this screen publishes the hit count and
    // consumes the query/active index. `hits` is only computed while the bar is
    // open, so a closed session pays nothing.
    val searchLive = TimelineSearch.isLive
    val searchQuery = TimelineSearch.query
    val searchActive = TimelineSearch.active
    val hits = remember(messages, searchQuery, searchLive) {
        if (searchLive) TimelineSearch.hits(messages, searchQuery) else emptyList()
    }
    val highlight = remember(hits, searchActive, searchQuery) {
        TimelineSearch.highlight(hits, searchActive, searchQuery)
    }
    val activeHit = remember(hits, searchActive) { TimelineSearch.hitAt(hits, searchActive) }

    // The count lives in the controller because the bar is rendered by the header,
    // a sibling subtree. A bar that is up with an empty box holds no search, so
    // the published count follows [TimelineSearch.isLive], not the bar's
    // visibility: a cleared box must read `0` and drop every hit decoration, and
    // `Highlight.None` is the only state that paints none.
    SideEffect { TimelineSearch.publish(if (searchLive) hits.size else 0) }

    // Leaving the session (or a tab switch on the mobile form) must not leave a
    // search behind: upstream tears the controller down with the screen.
    DisposableEffect(Unit) {
        TimelineSearch.setAvailable(true)
        onDispose { TimelineSearch.reset() }
    }

    // Reveal the active hit (`search-controller.ts:221-240`). Row granularity is
    // what this list has; upstream additionally scrolls to the part inside the
    // message.
    LaunchedEffect(activeHit?.messageID, searchLive) {
        val id = activeHit?.messageID ?: return@LaunchedEffect
        if (!searchLive) return@LaunchedEffect
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
    // offset 0.
    //
    // The follow latch ([TimelineFollow]): it answers "is the viewport still
    // pinned to the newest message?" **from every scroll sample**, not only from
    // the recompositions a `SideEffect` happens to catch. Reading
    // `layoutInfo` in a `SideEffect` misses the whole user gesture in the
    // common case — scrolling does not recompose this composable — so an append
    // landing while the user was reading history found a stale `true` and
    // `animateScrollToItem(0)` yanked them back to the newest message (v53 tE,
    // `research/v53_verify/tE/v53_07_scroll.png`).
    //
    // `snapshotFlow` samples the two position fields, so a drag, a fling's
    // decay and a programmatic jump all land here. Once the user is away from
    // the bottom the latch stays false and no append moves the viewport; it
    // re-arms only by returning to the bottom or by 「回到最新」.
    //
    // v77 P2: the sample carries `isScrollInProgress` too, because the list also
    // moves when nobody asked it to — see [TimelineFollow.onPositionSample]. An
    // off-bottom *position* is only the reader's doing when a scroll is what put
    // it there; everything else (an append re-anchoring the row the list keeps
    // at offset 0, the sticky row's inset, the dock's height) is the app's own
    // layout, and reading it as "the reader scrolled away" is what turned a
    // Stop's settle into an off-bottom viewport the reader never asked for
    // (emulator-5556 v76: `research/v76_verify/5556/33_stopped.png` — the
    // floating 「回到最新」 button up over a viewport nobody had moved, and
    // `…/34_after_stop_next.png`, the next message appended above the fold).
    LaunchedEffect(listState, thresholdPx, searchLive) {
        snapshotFlow {
            TimelineFollow.atBottom(
                listState.firstVisibleItemIndex,
                listState.firstVisibleItemScrollOffset,
                thresholdPx,
            ) to listState.isScrollInProgress
        }.collect { (atBottom, scrolling) ->
            // A live search owns the viewport, so its own reveal scroll must not
            // be mistaken for the reader leaving the end. The position is still
            // sampled — the release effect below needs it — it just does not
            // overwrite the pre-search latch.
            if (!searchLive) {
                followNextAppend = TimelineFollow.onPositionSample(
                    following = followNextAppend,
                    atBottom = atBottom,
                    scrolling = scrolling,
                )
            }
        }
    }

    // The floating jump button must react to *scrolling*, which does not
    // recompose this composable on its own — a `SideEffect` read would only
    // sample the position on the next unrelated recomposition. A derived state
    // subscribes to `layoutInfo` and notifies only when the boolean flips.
    val atEnd by remember(listState, thresholdPx) {
        derivedStateOf {
            TimelineFollow.atBottom(
                listState.firstVisibleItemIndex,
                listState.firstVisibleItemScrollOffset,
                thresholdPx,
            )
        }
    }

    // Arm on the way in, release on the way out: the position the reader was in
    // when the search took the viewport, and the hand-back when the search gives
    // it up. Without the release, clearing the Find box left the viewport exactly
    // where the last hit had scrolled it, so the floating 「回到最新」 button —
    // whose whole contract is "you are away from the end" — stayed on screen
    // over a message the search had picked
    // (`research/v63_verify/5554/47_find_clear.png`, `50_find_closed2.png`).
    var searchArmedAtEnd by remember { mutableStateOf(false) }
    var searchWasLive by remember { mutableStateOf(false) }
    LaunchedEffect(searchLive) {
        // Only a live→idle edge is a release; the first composition is not.
        if (searchWasLive == searchLive) return@LaunchedEffect
        searchWasLive = searchLive
        if (searchLive) {
            searchArmedAtEnd = atEnd
            return@LaunchedEffect
        }
        val armed = searchArmedAtEnd
        followNextAppend = TimelineFollow.onSearchReleased(armedAtEnd = armed, atEnd = atEnd)
        if (!armed) return@LaunchedEffect
        if (listState.firstVisibleItemIndex > JUMP_SCROLL_ITEMS) {
            listState.scrollToItem(0)
        } else {
            listState.animateScrollToItem(0)
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
        // Only a viewport that is **already** pinned to the newest message
        // follows an append. A user reading history owns their scroll position:
        // the new message arrives above the fold (the list is `reverseLayout`)
        // and the floating 「回到最新」 button is what brings them back
        // ([TimelineFollow.followOnAppend]).
        if (!TimelineFollow.followOnAppend(
                TimelineFollow.followOnAppendWhileSearching(followNextAppend, searchLive))
        ) {
            return@LaunchedEffect
        }
        if (listState.firstVisibleItemIndex > JUMP_SCROLL_ITEMS) {
            listState.scrollToItem(0)
        } else {
            listState.animateScrollToItem(0)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        // A-1: the markdown image reader for every body in this timeline. Built
        // once per timeline (the active server is re-read per image, so a server
        // switch needs no rebuild); a host that wants to scope local images to
        // its own directory can provide its own
        // `LocalMarkdownImageReader` around this composable — or, as
        // `SessionScreen` does, hand the session directory in through
        // [imageDirectory].
        val imageContext = LocalContext.current
        val imageReader = remember(imageContext, imageDirectory) {
            MarkdownImages.reader(imageContext, imageDirectory)
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(top = headerInset),
            contentPadding = listContentPadding,
            verticalArrangement = Arrangement.spacedBy(TURN_GAP),
            reverseLayout = true,
        ) {
            // Emitted first: with `reverseLayout` the first entry sits at the
            // visual bottom. The upstream order of the scroll tail is
            // `… turns / error card / bottomSpacer(Working)`.
            if (working) {
                // Same slot, same key: the stall notice replaces the label in
                // place instead of shifting the list.
                item(key = WORKING_ROW_KEY) { WorkingRow(noResponseMinutes = noResponseMinutes) }
            }
            if (trailingError != null) {
                item(key = ERROR_CARD_KEY) { SessionErrorCard(message = trailingError, detail = errorDetail) }
            }
            // Top → bottom for the reader; `reversed` maps it onto the
            // `reverseLayout` list (index 0 = the visual bottom). `contentType`
            // keeps item slots warm across types on long scrolls
            // ([TimelineRows.contentType], U-4 LazyColumn stabilization).
            items(items = reversed, key = { it.key }, contentType = { TimelineRows.contentType(it) }) { row ->
                when (row) {
                    is TimelineRows.Row.Separator -> TimelineSeparatorRow(separator = row)

                    // One card per failed turn, at that turn's position. Its
                    // stable key (`error:<id>`, the same string the
                    // `LazyColumn` item is keyed by) is what scopes the card's
                    // expand state — two failures, even two identical ones,
                    // toggle independently, and the state survives leaving and
                    // reopening the session (`ErrorCardExpansion`).
                    is TimelineRows.Row.Error -> SessionErrorCard(
                        message = row.message,
                        detail = row.detail,
                        stateKey = row.key,
                    )

                    // A-1: every markdown body of the turn renders its images
                    // through this reader (assistant text, reasoning, tool
                    // output).
                    is TimelineRows.Row.Message -> CompositionLocalProvider(
                        LocalMarkdownImageReader provides imageReader,
                    ) {
                        MessageRow(
                            message = row.message,
                            // The newest *turn* — failure rows close a turn, so the
                            // trailing message is the newest one that is not a row.
                            isTrailing = row.message.id == newestMessageID &&
                                runStatus == ChatStore.RunStatus.RUNNING,
                            toolOpen = toolOpen,
                            onToggleTool = onToggleTool,
                            shellExpanded = shellExpanded,
                            editExpanded = editExpanded,
                            reasoningSummaries = reasoningSummaries,
                            highlight = highlight,
                            turnChanges = turnChanges,
                            onRevertMessage = onRevertMessage,
                            revertBusy = revertBusy,
                            pendingRetryable = runStatus != ChatStore.RunStatus.RUNNING,
                            onRetryPending = onRetryPending,
                        )
                    }
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
                    .background(OcTheme.colors.bgBase)
                    .onSizeChanged {
                        stickyHeaderPx = it.height
                    },
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
        if (railVisible) {
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
                        //
                        // The follow drops with it, explicitly: this is a jump
                        // *away* from the end, and a programmatic one — upstream
                        // unpins for the same reason in `navigateMessage`
                        // (`interaction.ts:144`), because geometry alone cannot
                        // tell a jump from a relayout ([TimelineFollow.onNavigateAway]).
                        followNextAppend = TimelineFollow.onNavigateAway()
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
            visible = TimelineFollow.showsJumpButton(atEnd, messages.isNotEmpty(), searchLive),
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
            followNextAppend = TimelineFollow.onJumpToLatest()
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
    onRevertMessage: ((String) -> Unit)?,
    revertBusy: Boolean,
    /** Idle session: a parked steer is abnormal, so the row offers its retry. */
    pendingRetryable: Boolean = false,
    onRetryPending: ((String) -> Unit)? = null,
) {
    if (ChatStore.isPendingRow(message)) {
        // A parked steer renders as the user's own message with a "queued" label:
        // it is not delivered yet, so it must not carry the meta / revert actions
        // a real user row has (those address a server message by id).
        PendingSteerRow(
            message = message,
            onRetry = onRetryPending?.takeIf { pendingRetryable },
        )
        return
    }
    if (message.role == "user") {
        UserMessageRow(
            message = message,
            highlight = highlight,
            onRevertMessage = onRevertMessage,
            revertBusy = revertBusy,
        )
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
            // U-4: keyed part rows — a streaming append shifts the list, and
            // keyed slots keep the settled cards' composition instead of
            // re-creating every row below the insertion point.
            key(row.key) {
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
private fun UserMessageRow(
    message: ChatStore.UiMessage,
    highlight: TimelineSearch.Highlight,
    onRevertMessage: ((String) -> Unit)?,
    revertBusy: Boolean,
) {
    val c = OcTheme.colors
    val text = message.parts.filter { it.type == "text" }.joinToString("\n\n") { it.text }
    val files = message.parts.filter { it.type == "file" }
    val meta = remember(message) { MessageMeta.of(message) }
    // F-4: the line comments the row carries. A delivered user row arrives
    // without its `metadata` (the v2 snapshot maps `{id,type,time,text}` only),
    // so the notes are recovered from the body with upstream's
    // `parseCommentNote` fallback (`message-timeline.tsx:296-301`); the body
    // then renders without those note lines, which is what the upstream
    // `displayText` half shows.
    val comments = remember(text) { TimelineComments.ofText(text) }
    val bodyText = remember(text, comments) {
        if (comments.isEmpty()) text else TimelineComments.displayText(text)
    }
    var commentsExpanded by rememberSaveable(message.id) { mutableStateOf(false) }
    // A2: the bubble can carry a glyph-level hit highlight because its spans are
    // built here (the joined body is exactly what the `Text` renders, so ranges
    // measured on it are the ranges to paint).
    val textPartIds = remember(message.parts) {
        message.parts.filter { it.type == "text" }.map { it.id }
    }
    // U-4: a long paste folds to head+expander (user turns never stream, so no
    // live gate is needed). The glyph highlight only applies to the unfolded
    // body — ranges measured on the full text would overrun the head slice.
    val long = remember(bodyText) { TimelineFold.isLongText(bodyText) }
    var bubbleExpanded by rememberSaveable(message.id) { mutableStateOf(false) }
    val foldedBubble = long && !bubbleExpanded
    val fold = remember(bodyText) { TimelineFold.foldText(bodyText) }
    val visibleText = if (foldedBubble) fold.head.joinToString("\n") else bodyText
    val bubbleHit = textPartIds.any { it in highlight.parts }
    val hitRanges = remember(visibleText, highlight.needle, bubbleHit, foldedBubble) {
        if (bubbleHit && !foldedBubble && highlight.needle.isNotEmpty()) {
            TimelineSearch.ranges(visibleText, highlight.needle)
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
        if (text.isNotEmpty() || comments.isNotEmpty()) {
            val annotated = remember(visibleText, c, hitRanges) {
                highlightedBody(mentionAnnotated(visibleText, c), hitRanges, c)
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
                        // F-4: the cards live inside the bubble body
                        // (`message-content.tsx:296-307`: `UserMessageComments`
                        // is a child of `[data-slot=user-message-text]`), and a
                        // comment-only message — the note lines were the whole
                        // text — still shows them.
                        Column {
                            if (visibleText.isNotEmpty()) {
                                Text(
                                    text = annotated,
                                    color = c.textBase,
                                    fontSize = 14.sp,
                                    lineHeight = 20.sp,
                                )
                            }
                            UserCommentCards(
                                notes = comments,
                                expanded = commentsExpanded,
                                onExpand = { commentsExpanded = true },
                                modifier = Modifier.padding(top = if (visibleText.isEmpty()) 0.dp else 8.dp),
                            )
                        }
                    }
                }
            }
            if (long) {
                FoldToggleRow(
                    hiddenLines = fold.hiddenLines,
                    expanded = bubbleExpanded,
                    onToggle = { bubbleExpanded = it },
                )
            }
            UserMetaRow(
                // The copy action copies what the bubble shows: upstream reads
                // `readPromptPresentation(metadata).displayText ?? message.text`
                // (`composer/prompt.ts:58`), i.e. the user's own words without
                // the comment notes.
                text = bodyText,
                meta = meta,
                messageID = message.id,
                revertBusy = revertBusy,
                onRevert = onRevertMessage,
            )
        }
    }
}

/**
 * `[data-component="session-working"]` (`message-timeline.tsx:384-391`):
 * `flex h-9 items-center gap-2 pt-3 text-[13px] font-[530]`, the label rendered
 * through `TextShimmer` with `role="status"`.
 *
 * [noResponseMinutes] > 0 turns the same slot into the **stall notice**
 * (device P1, 2026-09-25): the turn is still running but nothing has arrived
 * for minutes — a hung provider call used to leave the shimmering `Working`
 * label up indefinitely with no hint that nothing was happening. The label is
 * replaced (not stacked) so the two states never render at once, and it drops
 * back to `Working` the moment any event resumes (see the timer in
 * [MessageTimeline]).
 */
@Composable
private fun WorkingRow(noResponseMinutes: Int = 0, modifier: Modifier = Modifier) {
    if (noResponseMinutes > 0) {
        val c = OcTheme.colors
        Row(
            modifier = modifier
                .fillMaxWidth()
                .height(WORKING_ROW_HEIGHT)
                .padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_oc_alert),
                contentDescription = null,
                tint = c.stateWarningFg,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.oc_timeline_no_response, noResponseMinutes),
                color = c.stateWarningFg,
                fontSize = 13.sp,
                fontWeight = FontWeight(530),
            )
        }
        return
    }
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
 * A **parked steer**: the user's own follow-up the server accepted into the
 * session inbox with `delivery:"steer"` and has not delivered yet
 * ([ChatStore.PENDING_ROLE]). It renders exactly like the user bubble the
 * delivered message will become — same shape, background and width cap — plus
 * the muted `Queued` (`ui.message.queued`) label above it, so the turn is
 * visibly held rather than lost.
 *
 * No meta row and no revert action: those address a *server* message by id, and
 * this id is still only an admission. The attachment count comes from the row's
 * `file` parts, which carry no content (`payload.files` is base64 data) — the
 * count is the whole point, so no chips.
 *
 * BUG-2 (v36 tB): [onRetry] (non-null only while the session is not running)
 * adds the one action a parked steer needs when the server never delivers it —
 * the measured report had the row sit 「Queued」 forever with no way to act and
 * no timeout. It re-delivers through the server's own
 * `PATCH …/inbox/{id}{delivery:"steer"}` (`ChatViewModel.retryPendingDelivery`),
 * which also rings the run's doorbell, and it leaves the row exactly as it is:
 * the server's next `session.inbox.*` frame is what turns it into the message.
 */
@Composable
private fun PendingSteerRow(
    message: ChatStore.UiMessage,
    modifier: Modifier = Modifier,
    onRetry: ((String) -> Unit)? = null,
) {
    val c = OcTheme.colors
    val text = ChatStore.pendingRowText(message)
    val files = ChatStore.pendingRowFiles(message).size
    // F-4: the parked steer's structured line comments
    // (`ChatViewModel.pendingSteerRow` keeps each `metadata.comments[]` entry as
    // a `comment` part).
    val comments = remember(message.parts) { TimelineComments.ofParts(message.parts) }
    var commentsExpanded by rememberSaveable(message.id) { mutableStateOf(false) }
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.End,
    ) {
        Text(
            text = buildString {
                append(stringResource(R.string.ui_message_queued))
                if (files > 0) {
                    append(" · ")
                    append(
                        stringResource(
                            if (files == 1) {
                                R.string.oc_queued_attachments_one
                            } else {
                                R.string.oc_queued_attachments_other
                            },
                            files,
                        ),
                    )
                }
            },
            color = c.textMuted,
            fontSize = 11.sp,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        if (onRetry != null) {
            // The row is parked and nothing is running: the delivery did not
            // happen, so say so where the message is and offer the way out.
            Text(
                text = stringResource(R.string.oc_message_not_delivered),
                color = c.textMuted,
                fontSize = 11.sp,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            Text(
                text = stringResource(R.string.retry),
                color = c.textBase,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .padding(bottom = 6.dp)
                    .clickable(role = Role.Button) { onRetry(message.id) },
            )
        }
        if (text.isNotEmpty() || comments.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(BUBBLE_MAX_RATIO)
                    .wrapContentWidth(Alignment.End),
            ) {
                SelectionContainer {
                    Box(
                        modifier = Modifier
                            .clip(OcTheme.shapes.xl)
                            .background(c.layer02)
                            .border(1.dp, c.borderBase, OcTheme.shapes.xl)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Column {
                            if (text.isNotEmpty()) {
                                Text(
                                    text = text,
                                    color = c.textBase,
                                    fontSize = 14.sp,
                                    lineHeight = 20.sp,
                                )
                            }
                            // F-4: upstream renders the parked steer's
                            // `metadata.comments` on the pending row as well
                            // (`session/route.tsx:73`), and its `text` is the
                            // note-free `displayText` — the parts are what makes
                            // a queued comment visible before delivery.
                            UserCommentCards(
                                notes = comments,
                                expanded = commentsExpanded,
                                onExpand = { commentsExpanded = true },
                                modifier = Modifier.padding(top = if (text.isEmpty()) 0.dp else 8.dp),
                            )
                        }
                    }
                }
            }
        }
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
    // Hoisted: `semantics {}` is not a composable scope. The label rides the
    // clickable Box, not the `Icon` inside it.
    val jumpLabel = stringResource(R.string.oc_jump_to_latest)
    Box(
        modifier = modifier
            .size(width = 32.dp, height = 28.dp)
            .ocRaisedShadow(shape)
            .clip(shape)
            .background(c.bgBase.copy(alpha = 0.92f))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = jumpLabel },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_oc_chevron_down),
            contentDescription = null,
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

/** The rail column plus the scrollbar strip in front of it — reserved on the list's trailing edge while the rail is visible (see [MessageTimeline]'s `railVisible`). */
private val RAIL_GUTTER = SCROLLBAR_STRIP + NAV_INSET + MessageNavRailPolicy.RAIL_WIDTH_DP.dp

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
 * Which failure the **trailing** card renders: `ChatState.error` — the live
 * status mirror — stands in only for a failure the rows do not already carry.
 *
 * The failure row of a turn ([ChatStore.ERROR_ROLE]) renders the very same card
 * at the turn's own position, so showing both would double the newest card.
 * Comparing the newest failure *text* (not the whole history) means an older,
 * unrelated failure in the transcript never suppresses the trailing card, and a
 * state whose rows are gone (a revert-projected transcript) falls back to it.
 * `null` = no trailing card.
 *
 * Pure, so it is covered by a JVM test.
 */
internal fun trailingErrorMessage(errorMessage: String?, rows: List<TimelineRows.Row>): String? =
    errorMessage?.takeIf { line ->
        val newest = rows.lastOrNull { it is TimelineRows.Row.Error } as? TimelineRows.Row.Error
        newest == null || newest.message != line
    }

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
    // A failure row is not a turn: the newest *turn* decides (a retry frame
    // clears the row anyway, so this only covers the frame order in between).
    // Neither is a parked steer — upstream keys this guard on the *active*
    // message, and a pending steer explicitly does not own assistant work until
    // it is delivered (`controller-projection.ts:28`, `message-timeline.tsx:339-343`),
    // so a steer waiting behind the running turn must not switch the row off.
    val last = messages.lastOrNull { !ChatStore.isClientRow(it) } ?: return false
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
 * The timeline's follow policy, as pure functions so the whole
 * 「贴底跟随 / 上翻停止 / 回底恢复」 matrix is JVM-testable
 * (`TimelineFollowTest`).
 *
 * The timeline is `reverseLayout`: index 0 is the **newest** message and sits at
 * the visual bottom, so "pinned to the newest" is `firstVisibleItemIndex == 0`
 * within a [BOTTOM_ITEMS] window and a near-zero scroll offset. Upstream
 * (`virtualizer.tsx`) follows appends unconditionally; here the follow is a
 * latch the user's own scrolling controls, because v53 tE measured a reader
 * scrolling up through a short window and being yanked back to the newest
 * message by every arriving token (`research/v53_verify/tE/v53_07_scroll.png`).
 */
internal object TimelineFollow {

    /**
     * How many of the newest items count as "the bottom". One item of slack
     * absorbs a rounding pixel on the first row and a partially visible second
     * row without letting a real scroll-away pass.
     */
    const val BOTTOM_ITEMS = 1

    /**
     * Is the viewport pinned to the newest message? `thresholdPx` is the same
     * near-zero slack the floating 「回到最新」 button used, so the button, the
     * latch and the append decision can never disagree about the same frame.
     */
    fun atBottom(firstIndex: Int, firstOffset: Int, thresholdPx: Int): Boolean =
        firstIndex <= BOTTOM_ITEMS && firstOffset <= thresholdPx

    /**
     * The latch after a scroll sample: the position *is* the answer. Sampling
     * on every position change (rather than only on the recompositions a
     * `SideEffect` happens to catch) is what stops the follow from firing on top
     * of an in-flight user scroll — a user who is reading history keeps it
     * false, and it only goes back to true when they return to the bottom.
     *
     * Position-only, so it cannot tell a gesture from a relayout; the live
     * sample goes through [onPositionSample], which can.
     */
    fun onScrollSample(following: Boolean, firstIndex: Int, firstOffset: Int, thresholdPx: Int): Boolean =
        atBottom(firstIndex, firstOffset, thresholdPx)

    /**
     * The latch after one **live** position sample — the rule the timeline
     * actually collects (`snapshotFlow { atBottom(…) to isScrollInProgress }`).
     *
     * - back at the end → follow, whoever put it there: upstream re-pins on
     *   "arrived at the end", including the arrival a *shrink* produces under a
     *   viewport that was already there (`virtualizer.tsx:557-569`);
     * - away from the end **and the list is scrolling** → the reader did it, so
     *   the latch drops, which is upstream's own gate on un-pinning
     *   (`(pointerHeld || touchScrolling) && scrollTop < previousTop`,
     *   `virtualizer.tsx:570`);
     * - away from the end **and nothing is scrolling** → nobody moved the
     *   viewport, so this sample is not an answer at all and the latch is left
     *   alone.
     *
     * The third case is the whole point. `reverseLayout` keeps the row the list
     * had at offset 0 there by **key** across a data change
     * (`LazyListState.updateScrollPositionIfTheFirstItemWasMoved`), so an append
     * inserts at index 0 and pushes `firstVisibleItemIndex` up by the number of
     * rows inserted — with the pixel distance to the end unchanged, because the
     * row the reader was looking at is still flush against it. [BOTTOM_ITEMS]
     * absorbs one such row; a settle that lands two (the `Interrupted` divider
     * plus an error card, a partless marker row, the `Working` row appearing or
     * leaving) reads as "two items away from the end" and used to drop the
     * latch. The same applies to the sticky row's measured inset and to the
     * dock's height, both of which re-lay the list without a gesture. Measured
     * on emulator-5556 v76: after a Stop the floating 「回到最新」 button stood
     * over a viewport the reader had not moved, and the next message was
     * appended above the fold (`research/v76_verify/5556/33_stopped.png`,
     * `…/34_after_stop_next.png`).
     *
     * A fling counts as scrolling for its whole duration
     * (`LazyListState.isScrollInProgress` covers the drag and the decay), and so
     * does this timeline's own `animateScrollToItem`. The follow's own animation
     * therefore samples as "scrolling, away from the end" while it travels — it
     * drops the latch mid-flight — but its **last** sample is the end it lands
     * on, which re-arms, so a follow can never leave the latch switched off.
     */
    fun onPositionSample(following: Boolean, atBottom: Boolean, scrolling: Boolean): Boolean = when {
        atBottom -> true
        scrolling -> false
        else -> following
    }

    /**
     * Does an append move the viewport? Only while the latch holds: the user is
     * pinned to the newest message and expects new content to stay in view.
     * Once they have scrolled away, a new message arrives above the fold and
     * their scroll position is theirs.
     */
    fun followOnAppend(following: Boolean): Boolean = following

    /** 「回到最新」: the user asked for the newest message, so following re-arms. */
    fun onJumpToLatest(): Boolean = true

    /**
     * 「跳转到某条消息」: an explicit navigation **away** from the end, so the
     * follow drops.
     *
     * It has to be said out loud for the same reason upstream says it in
     * `navigateMessage` (`interaction.ts:144`): the jump is programmatic, and
     * [onPositionSample] deliberately refuses to read a position change nobody
     * scrolled for. `scrollToItem` completes inside one frame, so
     * `isScrollInProgress` need never be observed at all.
     */
    fun onNavigateAway(): Boolean = false

    /**
     * The floating button's visibility: shown whenever the viewport is away
     * from the end ([atBottom] false), which is exactly the state the user
     * cannot follow out of on their own — **unless a search is running**.
     *
     * A live search is what moved the viewport in the first place: revealing a
     * hit calls `scrollToItem` (`search-controller.ts:221-240`), and the
     * resulting position is indistinguishable from one the reader scrolled to.
     * So the button appears over a message the search chose, offering to undo a
     * navigation the search bar's own ↑/↓ is already driving — the 「消息区中央
     * 残留跳转按钮」 of `research/v63_verify/5554/48_find_typed_clear.png`. While
     * the search lives the button is withdrawn; when the search clears or closes,
     * [onSearchReleased] puts the reader back where the search found them and
     * the button is answered by the real position, not the search's.
     */
    fun showsJumpButton(atEnd: Boolean, hasMessages: Boolean, searching: Boolean = false): Boolean =
        hasMessages && !atEnd && !searching

    /**
     * The follow latch a released search hands back.
     *
     * [armedAtEnd] is the position the viewport was in when the search went live
     * (see the `LaunchedEffect` in [MessageTimeline]). A search that started
     * pinned to the newest message put the reader somewhere else on purpose, so
     * releasing it returns them to the newest message and re-arms the follow —
     * the state they were in before the search opened, and the reason the
     * floating button does not reappear the instant the box is cleared. A search
     * that started while the reader was already in history leaves them where the
     * search left them, and the latch follows the release frame, so the follow
     * never re-arms over a position the user is reading.
     */
    fun onSearchReleased(armedAtEnd: Boolean, atEnd: Boolean): Boolean =
        if (armedAtEnd) true else atEnd

    /**
     * An append during a live search does not move the viewport: the search owns
     * it, and a reveal that a streaming token yanks away from is the same class
     * of bug as the v53 tE one described on [followOnAppend]. The pre-search
     * latch is restored by [onSearchReleased].
     */
    fun followOnAppendWhileSearching(following: Boolean, searching: Boolean): Boolean =
        following && !searching
}

/**
 * Structured fingerprint of the list state that must trigger a bottom-follow:
 * message count + the trailing message's id, role, interrupted flag, part
 * count, and per-part text / toolOutput length + toolStatus. Covers every
 * event that grows or changes the newest content (new part, streamed text,
 * tool output/status flip), so a tool card that appears or fills while
 * streaming is not left off-screen — and the two tail changes a Stop and a
 * delivery make with no part edit at all: settling the trailing message pins
 * the `Interrupted` divider below it, and handing the trailing placeholder to
 * the delivered message restyles the bubble. Both change the laid-out tail, so
 * without them the follow never re-pinned the bottom and the new rows stayed
 * out of view until the next scroll.
 *
 * Pure so it is covered by a JVM unit test (`TimelineFingerprintTest`).
 */
internal fun appendFingerprint(messages: List<ChatStore.UiMessage>): Long {
    var hash = messages.size.toLong()
    val last = messages.lastOrNull() ?: return hash
    hash = hash * 31 + last.id.hashCode()
    hash = hash * 31 + last.role.hashCode()
    hash = hash * 31 + if (last.interrupted) 1 else 0
    hash = hash * 31 + last.parts.size
    for (part in last.parts) {
        hash = hash * 31 + part.text.length
        hash = hash * 31 + (part.toolName?.hashCode() ?: 0)
        hash = hash * 31 + (part.toolStatus?.hashCode() ?: 0)
        hash = hash * 31 + (part.toolOutput?.length ?: 0)
    }
    return hash
}

/**
 * How long a silently running turn may go before the timeline says so. Two
 * minutes is well past a normal step (the measured model calls answer in
 * seconds) and still short enough to be useful in a session the user is
 * watching; the device P1 was a **38-minute** silent hang.
 */
internal const val NO_RESPONSE_THRESHOLD_MS = 120_000L

/** How often the stall notice re-checks its elapsed time (only while it is armed). */
private const val NO_RESPONSE_POLL_MS = 15_000L

/**
 * Whole minutes of silence to show in the stall notice, or `0` while the notice
 * must not appear (still inside [NO_RESPONSE_THRESHOLD_MS]).
 *
 * The threshold is inclusive: exactly two minutes of silence reads as
 * "2 min", which is the first whole minute the notice can honestly claim.
 * Pure, so the boundary is covered by a JVM test. (Named after the *quantity*
 * rather than "noResponseMinutes" so it never collides with the Compose state
 * of the same name that it feeds.)
 */
internal fun stallNoticeMinutes(elapsedMs: Long): Int =
    if (elapsedMs < NO_RESPONSE_THRESHOLD_MS) 0 else (elapsedMs / 60_000L).toInt()
