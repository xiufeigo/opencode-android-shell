package com.xiufeigo.opencodeshell.ui.nav

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.ui.components.TabReorder
import com.xiufeigo.opencodeshell.ui.terminal.TerminalStripChrome
import com.xiufeigo.opencodeshell.ui.theme.OcTheme
import kotlin.math.abs

/**
 * Height a desktop/freeform window's **caption** takes out of the top of the
 * app window: the — ▭ ✕ row the window manager (or the desktop launcher) paints
 * over it, and to which it keeps every touch.
 *
 * A window that reports no top system-bar/cutout inset is exactly such a window
 * — a phone app always has one of the two — so that is the signal; there is no
 * caption inset type to read (the caption is not part of the app's window).
 */
internal val DESKTOP_CAPTION_HEIGHT: Dp = 36.dp

/** The band [DesktopTabStrip] keeps clear so its controls stay tappable. */
internal fun desktopCaptionReserve(topSystemInsetPx: Int): Dp =
    if (topSystemInsetPx <= 0) DESKTOP_CAPTION_HEIGHT else 0.dp

/**
 * The same reserve, read from **this composition's** window — the single place
 * the "no top system furniture ⇒ a desktop/freeform window" judgement is made.
 *
 * Every route host that owns the whole top of the window clears the caption with
 * this: the top tab strip, and the standalone terminal route
 * (`Route.TERMINAL` in [com.xiufeigo.opencodeshell.ui.App]), which pads with
 * `WindowInsets.safeDrawing` and therefore learned nothing about the caption —
 * a freeform window reports no top inset there, so its tab row sat under the
 * — ▭ ✕ band and none of it took taps (`research/v40_verify/tB/`).
 *
 * Deliberately not remembered: the value must follow the window moving between
 * the status bar and a cutout, and a desktop window entering or leaving.
 */
@Composable
internal fun desktopCaptionReserve(): Dp {
    val density = LocalDensity.current
    val topSystemInset = maxOf(
        WindowInsets.statusBars.getTop(density),
        WindowInsets.displayCutout.getTop(density),
    )
    return desktopCaptionReserve(topSystemInset)
}

/**
 * W7: the **desktop** session tab bar (`research/02` §9b.1) — the top strip the
 * shell was missing on wide screens. This is the `TitlebarTabStrip` half of
 * upstream's titlebar, plus the icons that frame it:
 *
 * ```
 * [home icon] [tab] [tab] … [+] [palette]
 * ```
 *
 * The trailing palette button is v35 tA-terminal's port of `TitlebarRight`
 * (`right-slot.tsx`), the slot upstream mounts the "search and command palette
 * button" in — see [com.xiufeigo.opencodeshell.ui.palette.PaletteHost]. It is
 * absent while the host passes no [onOpenPalette].
 *
 * Transcribed from `_upstream/packages/app/src/shell/titlebar/titlebar.tsx:441-704`:
 * the bar is `h-9` = 36 dp, `bg-v2-background-bg-deep`, `gap-1.5` = 6 dp, and
 * inside it the horizontal order is the Home button (`grid-plus`, `aria-label`
 * `home.title`, `data-state="pressed"` while Home is on screen, `:324-350`) →
 * `TitlebarTabStrip` (`:599-611`) → the `+` button (`aria-label`
 * `command.session.new`, `:612-630`). The strip itself
 * (`tab-strip.tsx:277-410`) is a **horizontally scrollable** row of
 * `w-56 max-w-56` slots (`:284-290`), so a long tab list scrolls instead of
 * pushing the `+` off the bar.
 *
 * Two upstream behaviours this placement inherits on purpose:
 *
 * * **It stays visible on Home.** Upstream renders the titlebar on every route;
 *   Home is a *button*, not a tab, and no tab is active while Home content is
 *   shown (`titlebar.tsx:344`: `state={route === "home" ? "pressed" : undefined}`).
 *   [activeSessionId] is therefore `null` off a session route and the Home
 *   button paints as the pressed one — the same polarity as
 *   `BottomTabStrip(sessionActive = …)`.
 * * **The bottom placement is mobile-only.** `titlebar.tsx:64-65`:
 *   `bottom = mobile() && mobileTitlebarPosition() === "bottom"`, so on a desktop
 *   width the bar belongs on top; `AppScaffold` renders this strip there and the
 *   bottom bar only below the breakpoint. B-16 completes the pair: below the
 *   breakpoint this strip is the **top** stop of the same setting
 *   (`appearance.tabLayout`, the General page's「底部导航」switch), which is what
 *   upstream's `bottom` memo means by rendering the titlebar at the top while the
 *   position is not `"bottom"`.
 *
 * Faithful deviations (§9b.1/§9b.2): B-20 gives the strip **long-press drag
 * reorder** (`useSortable`'s `arrayMove` result, `tab-strip.tsx:334-352`) and a
 * **long-press menu** whose Rename row opens the inline editor — upstream
 * renames on double-click (`tab-nav.tsx:64-67`), which a touch screen cannot
 * express, so the menu is the phone's entry to the same
 * `session.update`; there is no hover preview popover (`tab-popover.tsx`, a
 * touch screen has no hover). The strip fades both overflowed edges
 * ([SessionStripEdgeFade], v48 `tD_39`) so a mid-scroll tab reads as
 * scrollable rather than clipped — upstream's `tab-strip` paints no such
 * gradients; the recipe is the terminal strip's overlay. Upstream's drag also
 * *navigates* to the tab it picks up (`tab-strip.tsx:320-326`, horizontal only)
 * so the moved tab is on screen; this port keeps the route where it is, because
 * the drag feedback (the slot follows the finger) already shows which tab is
 * moving and yanking the route mid-gesture is the more disruptive of the two.
 * Upstream also refuses to start a drag on the close target (`preventActivation`
 * → `isTabCloseTarget`); here the gesture sits on the whole slot, so a long
 * press that lands on the ✕ picks the slot up too — a *tap* on the ✕ still
 * closes it, and a long press on a 20 dp target is not a close gesture.
 * The `:not(:first-child)` separator pill and the 224 dp slot ceiling are kept
 * (§9b.2), and per-tab close uses [SessionTabs.displayTitle] for the label
 * rather than a second title path.
 *
 * **v40 tB: the bar keeps a desktop window's caption clear.** The strip paints
 * the whole window width, including the region behind the system bar, so every
 * one of its controls sits in the window's top 36 dp. On a phone that band is
 * the status bar (nothing is drawn there, the system only draws the clock), but
 * in a desktop/freeform window it is the **window caption** — the — ▭ ✕ row the
 * window manager paints over the app and takes the touches for. The v40 run
 * read that as a dead palette button: `input tap` on `>_` did nothing while the
 * same palette opened from `Ctrl+K`, and a probe showed the *whole* bar dead —
 * Home, the tabs and `+` did not answer either, while everything below the band
 * did. [desktopCaptionReserve] moves the controls (not the background) below it
 * in exactly those windows.
 *
 * The close semantics (which tab becomes active, when the shell returns Home) do
 * **not** live here: both strips close through [SessionTabs.close]. The reorder
 * order is the host's list itself: [onMoveSession] mutates the one
 * `openSessions` the desktop strip and the mobile drawer both render, so the
 * order the user dragged survives switching placements.
 *
 * @param sessions the shell's open-session store, in open order.
 * @param activeSessionId the route's session id, or `null` off a session route.
 * @param onHome `home.toggle` — the leading icon returns to Home.
 * @param onOpenSession a tap on a tab switches to that session.
 * @param onCloseSession a tap on a tab's ✕ closes it.
 * @param onNewSession the trailing `+` — the app's new-session draft route.
 * @param onMoveSession B-20: a drag landed — `(from, to)` indices into
 *   [sessions]; the host applies [SessionTabs.reorder].
 * @param onRenameSession B-20: the long-press menu's Rename — `(tab, title)`,
 *   already trimmed by the field's caller.
 */
@Composable
fun DesktopTabStrip(
    sessions: List<SessionTabRef>,
    activeSessionId: String?,
    onHome: () -> Unit,
    onOpenSession: (SessionTabRef) -> Unit,
    onCloseSession: (SessionTabRef) -> Unit,
    onNewSession: () -> Unit,
    /**
     * v35 tA-terminal: opens the command palette — the trailing slot upstream
     * mounts its "search and command palette button" in (`TitlebarRight`, see
     * [com.xiufeigo.opencodeshell.ui.palette.PaletteHost]). Null hides the
     * button, so a host without a palette keeps the strip it always had.
     */
    onOpenPalette: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    /** A-2: per-tab `unread` / `loading`, same contract as [BottomTabStrip]. */
    tabBadge: (SessionTabRef) -> SessionTabBadge = { SessionTabBadge.NONE },
    /** B-20: long-press drag reorder — `(from, to)` indices into [sessions]. */
    onMoveSession: (Int, Int) -> Unit = { _, _ -> },
    /** B-20: the long-press menu's Rename — `(tab, new title)`. */
    onRenameSession: (SessionTabRef, String) -> Unit = { _, _ -> },
) {
    val c = OcTheme.colors
    val homeActive = activeSessionId == null
    // `session.tab.session` (`en.ts:740`) — upstream's fallback for a tab whose
    // title has not arrived yet and for the auto-generated session titles.
    val sessionLabel = stringResource(R.string.oc_tab_session)
    val scroll = rememberScrollState()

    val captionReserve = desktopCaptionReserve()

    // B-20 drag state. It is hoisted out of the slots because the drag outlives
    // the reorder it triggers: each slot keeps its `key` and its `pointerInput`
    // is keyed by the tab id, so a reorder moves the slot instead of rebinding
    // it and the gesture is never restarted mid-drag.
    val slotWidths = remember { mutableStateMapOf<String, Float>() }
    // Each slot's own range in the scroll content's coordinates — what the v51
    // reveal below needs ("is the active tab inside the viewport?"). Measured
    // with `onGloballyPositioned`, i.e. read off the strip's own layout rather
    // than recomputed from widths, so the `1.5 dp` separator slots between two
    // inactive tabs cannot drift the arithmetic.
    val slotBounds = remember { mutableStateMapOf<String, IntRange>() }
    // The scrolling viewport's own width — the v51 reveal needs it to know what
    // "visible" means (see the effect below).
    var viewportWidth by remember { mutableIntStateOf(0) }
    var dragId by remember { mutableStateOf<String?>(null) }
    var dragDx by remember { mutableFloatStateOf(0f) }
    var dragMoved by remember { mutableStateOf(false) }
    // The long-press menu (a press that never moved) and the editor its Rename
    // row opens — both keyed by tab id, so at most one is open.
    var menuFor by remember { mutableStateOf<String?>(null) }
    var renamingId by remember { mutableStateOf<String?>(null) }
    var renameDraft by remember { mutableStateOf("") }
    // Read through `rememberUpdatedState`: the gesture closure is created once
    // per slot, so it must see the *current* list and callback rather than the
    // ones from the composition that started the drag.
    val liveSessions = rememberUpdatedState(sessions)
    val liveMove = rememberUpdatedState(onMoveSession)
    val gapPx = with(LocalDensity.current) { TAB_GAP.toPx() }

    // v51 MED-2 (`research/v51_verify/tC/v51_47/48/50`): the strip overflows, so
    // the tab of a newly created session is added **off-screen** — the session
    // screen is already on it while the strip shows nothing, and it only turns up
    // after the user closes an older tab. Upstream scrolls the active tab into
    // view on every render of the strip (`TitlebarTabStrip`), so this runs
    // whenever the active id or the tab set changes. The decision itself is
    // [SessionTabs.revealOffset]; `slotBounds` is the strip's own measurement, so
    // a slot the frame has not placed yet simply has no entry and the effect
    // runs again with the next one.
    LaunchedEffect(activeSessionId, sessions.size, viewportWidth, slotBounds.size) {
        val id = activeSessionId ?: return@LaunchedEffect
        val target = SessionTabs.revealOffset(
            bounds = slotBounds,
            viewport = viewportWidth,
            current = scroll.value,
            id = id,
        ) ?: return@LaunchedEffect
        scroll.animateScrollTo(target.coerceIn(0, scroll.maxValue))
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(c.bgDeep)
            // The bar paints behind the status bar itself (as the bottom strip
            // does with the navigation bar), so `AppScaffold` consumes these
            // insets for the nav content instead of letting it pad twice.
            .windowInsetsPadding(WindowInsets.statusBars)
            .height(BAR_HEIGHT + captionReserve)
            // …and, in a desktop/freeform window, behind the *window caption*
            // as well, which the window manager paints over the top band and
            // keeps the touches for itself (see [desktopCaptionReserve]).
            .padding(top = captionReserve, start = 8.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TAB_GAP),
    ) {
        // v54 P1 (`research/v54_verify/tD/39_home_shrunk.png`, `37`, `11`): with
        // the tab list overflowing, Home's tap box measured ~29 dp — under half
        // the 48 dp floor, and the scrolled strip's first tab was drawn hard
        // against it, so the entry back to Home was the one control that got
        // harder to hit exactly when the bar was busiest. The glyph stays the
        // 28 dp `titlebar-v2` square; only the finger grows ([ocMinTouchTarget]).
        //
        // The wrapper is as wide as the button **plus** the 10 dp the ring
        // overflows on its trailing side, so the 48 dp box ends at the wrapper's
        // edge and never reaches into the scroll viewport (which is a later
        // sibling and would win any overlap).
        //
        // v56: the ring overflows on **both** sides now — a `Modifier.size` below
        // it was clamping the 48 dp box back to the 28 dp glyph *and* shifting
        // that box 10 dp up-left, so Home's measured bounds read
        // `[0,144][74,254]` (28 dp, half of it off the window edge,
        // `research/v56_verify/tD/33_home_shrunk.png`). The leading 10 dp is
        // therefore reserved here as well: without it the 48 dp finger starts
        // 2 dp left of the window and uiautomator reports 46 dp, not 48.
        Box(modifier = Modifier.padding(start = HOME_FINGER_INSET)) {
            Box(modifier = Modifier.width(TAB_HEIGHT + 10.dp)) {
                TabIconButton(
                    iconRes = R.drawable.ic_oc_grid,
                    description = stringResource(R.string.oc_home_title),
                    tint = if (homeActive) c.iconBase else c.iconMuted,
                    active = homeActive,
                    onClick = onHome,
                    minTouchTarget = true,
                )
            }
        }
        // `TitlebarTabStrip`: the scrolling list. `weight(1f, fill = false)` is
        // upstream's `min-w-0` + `overflow-x-auto` — the list takes what is left
        // after the two icons and scrolls inside it, but stays content-width when
        // it is short, so `+` trails the last tab instead of floating right.
        //
        // v48 `tD_39`: the scrolled row fades at both overflowed edges — the
        // same overlay recipe as the terminal strip (`TerminalStripEdgeFade`,
        // `TerminalPanel.kt`): the fades ride *on top of* the viewport Box,
        // never inside the scroll content (a mask drawn inside
        // `horizontalScroll` scrolls away with the content,
        // `placeRelativeWithLayer`). The Box keeps the strip's own
        // `weight(1f, fill = false)` geometry, so a short list still trails `+`
        // after the last tab; `clipToBounds` is the viewport's clip (the
        // content-sized scrolled row would otherwise paint past it) and
        // `fillMaxHeight` lets the fades cover the whole bar. The overlays
        // carry no input modifiers, so drags, flings and taps fall through to
        // the scrolling row beneath.
        Box(
            modifier = Modifier
                .weight(1f, fill = false)
                .fillMaxHeight()
                .onSizeChanged { viewportWidth = it.width }
                .clipToBounds(),
            contentAlignment = Alignment.CenterStart,
        ) {
            Row(
                modifier = Modifier
                    .horizontalScroll(scroll),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(TAB_GAP),
            ) {
                sessions.forEachIndexed { index, tab ->
                    val active = tab.id == activeSessionId
                    // `:not(:first-child):not([data-active="true"])::before` — the
                    // pill only sits between two non-active slots (§9b.2).
                    if (index > 0 && !active) TabSeparator()
                    // `key(tab.id)`: a reorder *moves* this group rather than
                    // rebinding the slot in place, which is what lets the drag
                    // survive its own reorder (a positional slot would restart the
                    // gesture and cancel it).
                    key(tab.id) {
                        val dragging = dragId == tab.id
                        Box(
                            modifier = Modifier
                                .widthIn(max = TAB_MAX_WIDTH)
                                .onSizeChanged { slotWidths[tab.id] = it.width.toFloat() }
                                // v51 MED-2: the slot's own range in the scroll
                                // content, which is what `revealOffset` reads.
                                // The dragged slot's `translationX` is a layer
                                // offset and does not move the layout, so the
                                // recorded range stays the resting one.
                                .onGloballyPositioned { coordinates ->
                                    val left = coordinates.positionInParent().x
                                    slotBounds[tab.id] =
                                        left.toInt()..(left.toInt() + coordinates.size.width)
                                }
                                // The dragged slot follows the finger and floats over
                                // its neighbours — dnd-kit's drag feedback, which
                                // upstream renders through its `Feedback` plugin.
                                .graphicsLayer {
                                    if (dragging) {
                                        translationX = dragDx
                                        alpha = 0.85f
                                    }
                                }
                                .zIndex(if (dragging) 1f else 0f)
                                .pointerInput(tab.id) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = {
                                            dragId = tab.id
                                            dragDx = 0f
                                            dragMoved = false
                                        },
                                        // A long press that never moved is the menu,
                                        // not a reorder. The library consumes the up
                                        // it ends on, so this is not also a tap on
                                        // the tab.
                                        onDragEnd = {
                                            if (dragId == tab.id && !dragMoved) menuFor = tab.id
                                            dragId = null
                                            dragDx = 0f
                                        },
                                        onDragCancel = {
                                            dragId = null
                                            dragDx = 0f
                                        },
                                        onDrag = { _, amount ->
                                            if (dragId != tab.id) return@detectDragGesturesAfterLongPress
                                            dragDx += amount.x
                                            if (abs(dragDx) > viewConfiguration.touchSlop) dragMoved = true
                                            val list = liveSessions.value
                                            val from = list.indexOfFirst { it.id == tab.id }
                                            // The slot's own width is the pitch: the
                                            // strip's slots are content-sized, so
                                            // there is no fixed grid to measure
                                            // against.
                                            val pitch = (slotWidths[tab.id] ?: 0f) + gapPx
                                            val to = TabReorder.dropIndex(from, dragDx, pitch, list.size)
                                            if (to >= 0 && to != from) {
                                                liveMove.value(from, to)
                                                // The slot just moved under the
                                                // finger: re-base the travel on its
                                                // new home so the next step counts
                                                // from there.
                                                dragDx -= (to - from) * pitch
                                            }
                                        },
                                    )
                                },
                        ) {
                            SessionTab(
                                // Resolve the label here: `SessionTab` is shared with the
                                // bottom strip, which falls back to `oc_new_session`.
                                tab = tab.copy(title = SessionTabs.displayTitle(tab.title, sessionLabel)),
                                active = active,
                                onClick = { onOpenSession(tab) },
                                onClose = { onCloseSession(tab) },
                                badge = tabBadge(tab),
                            )
                            // The editor covers the slot instead of sitting in the
                            // row, so the strip's geometry does not move while it is
                            // open (`matchParentSize` does not feed back into the
                            // Box's own size).
                            if (renamingId == tab.id) {
                                TabRenameField(
                                    value = renameDraft,
                                    onValueChange = { renameDraft = it },
                                    onConfirm = {
                                        renamingId = null
                                        onRenameSession(tab, renameDraft)
                                    },
                                    onDismiss = { renamingId = null },
                                    modifier = Modifier.matchParentSize(),
                                )
                            }
                            DropdownMenu(
                                expanded = menuFor == tab.id,
                                onDismissRequest = { menuFor = null },
                                // v11: the shell's palette for menus — M3's default
                                // surface container is not re-themed dark here.
                                shape = RoundedCornerShape(6.dp),
                                containerColor = c.layer01,
                                tonalElevation = 0.dp,
                                border = BorderStroke(0.5.dp, c.borderMuted),
                            ) {
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            stringResource(R.string.common_rename),
                                            fontSize = 13.sp,
                                            color = c.textBase,
                                        )
                                    },
                                    onClick = {
                                        menuFor = null
                                        renameDraft = tab.title.orEmpty()
                                        renamingId = tab.id
                                    },
                                )
                            }
                        }
                    }
                }
            }
            // The fades ride the same scroll position the row scrolls with
            // ([TerminalStripChrome.fadeEdges]): an edge with nothing beyond it
            // stays sharp, and a strip that fits altogether draws no overlay.
            // Read in composition (not in a draw phase), so a fling recomposes
            // just these two small boxes.
            val edges = TerminalStripChrome.fadeEdges(scroll.value, scroll.maxValue)
            if (edges.leading) {
                SessionStripEdgeFade(leading = true, modifier = Modifier.align(Alignment.CenterStart))
            }
            if (edges.trailing) {
                SessionStripEdgeFade(leading = false, modifier = Modifier.align(Alignment.CenterEnd))
            }
        }
        TabIconButton(
            iconRes = R.drawable.ic_oc_plus,
            description = stringResource(R.string.oc_new_session),
            tint = c.iconMuted,
            active = false,
            onClick = onNewSession,
        )
        // v35 tA-terminal: the palette entry, in the trailing slot upstream fills
        // with its titlebar search/command-palette button (`TitlebarRight`, the
        // portal `right-slot.tsx` exposes). The phone has no chord (`mod+k` /
        // `mod+shift+p` never arrive from `input keyevent`), so without this
        // button the ~40 commands — `debugBar.toggle` among them — have no touch
        // entry at all. The glyph is the `>_` console rather than upstream's
        // magnifier: the session header row right below already wears the
        // magnifier for timeline search (`oc_search_open`), and two identical
        // magnifiers opening two different searches is the ambiguity the entry
        // was added to remove.
        if (onOpenPalette != null) {
            TabIconButton(
                iconRes = R.drawable.ic_oc_console,
                description = stringResource(R.string.oc_palette_title),
                tint = c.iconMuted,
                active = false,
                onClick = onOpenPalette,
            )
        }
    }
}

/**
 * v48 `tD_39`: one edge fade of the session tab strip's scrolling list — the
 * terminal strip's overlay recipe (`TerminalStripEdgeFade`,
 * `TerminalPanel.kt`) in the session strip's own background: the bar paints
 * `c.bgDeep`, so the fade runs `bgDeep` → transparent (fading to the
 * terminal's `bgBase` would show a seam — the same reason `AttachmentEdgeFade`
 * fades to its own container colour).
 *
 * Why an overlay and not a `DstIn` mask: a mask drawn by a modifier *inside*
 * `horizontalScroll` is drawn in *content* coordinates and translated away
 * with the scroll (`ScrollingLayoutNode.measure` → `placeRelativeWithLayer`,
 * foundation `Scroll.kt`) — past the first fade-width of travel both masks
 * sit off-viewport and the mid-scroll tab hard-cuts mid-glyph (`itled`,
 * `tD_39`). An overlay in the strip's own (unscrolled) `Box` stays pinned to
 * the viewport by construction: plain translucent overdraw, no blend modes,
 * nothing a real GPU driver can silently drop.
 */
@Composable
private fun SessionStripEdgeFade(
    leading: Boolean,
    modifier: Modifier = Modifier,
) {
    val c = OcTheme.colors
    Box(
        modifier = modifier
            .width(SESSION_STRIP_EDGE_FADE)
            .fillMaxHeight()
            .background(
                Brush.horizontalGradient(
                    if (leading) listOf(c.bgDeep, Color.Transparent)
                    else listOf(Color.Transparent, c.bgDeep),
                ),
            ),
    )
}

/**
 * v48: the session strip's edge-fade width — the same 12 dp as the terminal
 * strip's (`STRIP_EDGE_FADE`, `TerminalPanel.kt`), so the two tab bars fade
 * identically.
 */
private val SESSION_STRIP_EDGE_FADE = 12.dp

/**
 * v56: leading room the Home wrapper reserves so Home's 48 dp finger
 * ([ocMinTouchTarget]) starts **on** the window rather than 2 dp off it — the
 * ring is centred on the 28 dp glyph and overflows 10 dp per side
 * ([touchTargetOffsetPx]), and a box that starts off-screen measures short
 * (`research/v56_verify/tD/33_home_shrunk.png` read 28 dp before the ring was
 * fixed at all, 46 dp with the ring but no inset). Costs 10 dp of the bar;
 * buys the full 48 dp in measured bounds.
 */
internal val HOME_FINGER_INSET = 10.dp
