package com.xiufeigo.opencodeshell.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.ui.components.OcKeyboard
import com.xiufeigo.opencodeshell.ui.components.ocMinTouchTarget
import com.xiufeigo.opencodeshell.ui.session.composer.HalfWidthText
import com.xiufeigo.opencodeshell.ui.theme.OcTheme

/** One open session tab (upstream `Tab = SessionTab | DraftTab`, `research/02` §9b.3). */
data class SessionTabRef(val id: String, val title: String?)

/**
 * v98 fix2 — **where the mobile tabs sheet is mounted**, done as a real
 * re-parent instead of a value that pretends to be one.
 *
 * Upstream is an overlay: both halves of the sheet are `position: fixed` and so
 * take no part in the parent's flow — `[data-slot="mobile-drawer-overlay"]` is
 * `fixed inset-0; z-index:50` (`_upstream/packages/app/src/shell/mobile-drawer.css:1-7`)
 * and `[data-slot="mobile-drawer-content"]` is `fixed inset-inline:0; bottom:0;
 * z-index:51` (`:13-31`).
 *
 * That distinction is the whole bug. [BottomTabStrip] is a direct child of the
 * shell `Column` (`ui/App.kt`), so the sheet's `Box(Modifier.fillMaxSize())`
 * host (`ui/nav/MobileTabsDrawer.kt:148`) — if it is composed there — is a
 * *flow child* of that column. A `Column` measures its non-weighted children
 * first, so the host swallows every remaining pixel, the `weight(1f)` NavHost
 * is left with 0 px, and the bar — the only child above it — slides up to y=0
 * under the status bar (`research/v97_verify/5560_sweepA/REPORT_v97b.md` N-3:
 * `y=1452..1548` → `y=0..84`).
 *
 * **v97's shape did not move the node.** It handed a `@Composable` lambda down
 * a `CompositionLocal` and the shell wrapped it in a `Box`, but a
 * `CompositionLocal` carries a *value*: the lambda is still **invoked at the
 * consumer**, so the `Box` was composed at the consumer's position — still a
 * flow child of the column. Worse, that `Box` was composed *unconditionally*,
 * so it stayed in the layout even with the sheet closed and the mobile form
 * collapsed at all times. Real-machine round two proved it: mobile width **and**
 * `tabLayout=vertical` rendered a 13-node shell with the bar parked at
 * `y=0..84` (`research/v97_verify/5560_v98/5560_v98_drawer2/REPORT_DRAWER2.md` §2).
 *
 * A mount works where a value cannot. [BottomTabStrip] still *owns* the sheet —
 * its state, its callbacks and the close-then-act order — but it only *hands
 * over* the content; the shell **invokes** that content as the last child of
 * the `Box` that already wraps the column, so the sheet's layout node is a real
 * sibling of the column, stacked over it, outside its measurement.
 *
 * Public only because [BottomTabStrip] is public and takes it as a parameter;
 * both members are `internal`, so nothing outside `ui/nav` can reach the state.
 */
@Stable
class MobileDrawerMount {
    /**
     * **The** open flag — the single state that says "the sheet is open".
     *
     * v98 fix3 made this authoritative. The shell's gate
     * (`ui/App.kt`, `if (mobileDrawerMount.open && …)`) reads it, and the
     * trigger writes it directly in its click handler, so the entry and the
     * host share one object and one write. fix2 instead kept a strip-local
     * `drawerOpen` and published a copy of it here from a `SideEffect` — a
     * post-apply write, one frame behind the tap, with nothing on screen to
     * show for it when the frame did not follow. Nothing may republish this
     * flag from a `SideEffect` again: that copy is the defect.
     */
    internal var open by mutableStateOf(false)

    /**
     * The sheet's content, republished by [BottomTabStrip] on every
     * recomposition. `null` when no strip is mounted, so the shell never
     * composes a stale sheet.
     */
    internal var content: (@Composable () -> Unit)? = null
}

/**
 * Bottom title bar (`research/02` §9b, `titlebar-tab-nav.css`).
 *
 * M3 scope — the optional half of the brief: it exists **only** when
 * `appearance.tabLayout === "vertical"` ([TabLayout]), which is also the mobile
 * "底部导航" setting in §12, and it is off by default. B-16 makes that setting the
 * mobile **placement** switch (`settings.general.row.mobileTitlebarBottom`,
 * `general.tsx:369-381`): with it on, this bar is the bottom stop and the top
 * strip ([DesktopTabStrip]) stands down — `bottom = mobile() && …`
 * (`titlebar.tsx:64-65`). Geometry is the verified
 * one: bar `h-9` = 36 dp, tab `h-7` = 28 dp, radius 6 dp, `px-1.5` = 6 dp gaps,
 * 1.5 × 12 dp separator between adjacent inactive slots, max slot width
 * `w-56` = 224 dp.
 *
 * WAVE 2.3b P0 (A2 #1): the current-session slot is now the drawer's
 * **trigger** — `titlebar.tsx:472-519` `MobileDrawerTrigger` shows the current
 * tab's avatar + title + the tab count badge and opens [MobileTabsDrawer],
 * which lists **every** open tab plus Home / Settings / Help rows
 * (`:520-592`). The `session`/`sessionActive` pair is kept so existing callers
 * compile; pass the full list via [sessions] and the route id via
 * [activeSessionId] for the drawer content.
 *
 * Home is a *button* of the bar, not a tab (upstream: `Icon
 * name="grid-plus"`, keybind `home.toggle`), and `+` creates a session directly
 * with no menu (§9b.4). v35 tA-terminal appends the command palette's button to
 * the same trailing cluster ([onOpenPalette]) — the bottom placement's half of
 * the titlebar slot [DesktopTabStrip] carries at the top.
 */
@Composable
fun BottomTabStrip(
    session: SessionTabRef?,
    sessionActive: Boolean,
    onHome: () -> Unit,
    onOpenSession: (SessionTabRef) -> Unit,
    onCloseSession: (SessionTabRef) -> Unit,
    onNewSession: () -> Unit,
    /**
     * v98 fix2 — the shell's overlay slot for the sheet. Required and
     * un-defaulted on purpose: the sheet has exactly one legitimate mount, and a
     * defaulted one would silently reintroduce the in-flow shape that put the
     * bar at y=0 (`REPORT_DRAWER2.md` §2). The strip keeps owning the sheet's
     * state and callbacks and hands this the content to compose as a **sibling
     * of the shell column**, never as one of its flow children.
     */
    drawerMount: MobileDrawerMount,
    modifier: Modifier = Modifier,
    /**
     * v35 tA-terminal: opens the command palette — the trailing slot upstream
     * mounts its "search and command palette button" in (`TitlebarRight`, see
     * [com.xiufeigo.opencodeshell.ui.palette.PaletteHost]). Null hides the
     * button, so a host without a palette keeps the strip it always had.
     */
    onOpenPalette: (() -> Unit)? = null,
    /**
     * WAVE 2.3b: all open tabs for the drawer list. Defaults to the legacy
     * single slot so pre-drawer callers keep compiling; drawer hosts pass the
     * shell's `openSessions` here.
     */
    sessions: List<SessionTabRef> = if (session == null) emptyList() else listOf(session),
    /** Route's session id, or `null` off a session route (drawer Home pressed). */
    activeSessionId: String? = session?.takeIf { sessionActive }?.id,
    /** `sidebar.settings` — opens the settings surface. */
    onOpenSettings: () -> Unit = {},
    /** `sidebar.help` — opens the desktop-feedback URL. */
    onOpenHelp: () -> Unit = {},
    /**
     * A-2: the per-tab `unread` / `loading` state (`SessionTabIndicator`, upstream
     * `SessionTabAvatarView`). Defaulted to [SessionTabBadge.NONE] so a host that
     * has no per-session state to offer keeps the strip it always had.
     */
    tabBadge: (SessionTabRef) -> SessionTabBadge = { SessionTabBadge.NONE },
    /**
     * B-6: the drawer's `tab.reopenClosed` row — the newest closed tab (null
     * hides the row) and its action. Passed straight through to
     * [MobileTabsDrawer].
     */
    closedTab: ClosedTab? = null,
    onReopenClosed: () -> Unit = {},
    /**
     * B-20: the drawer's long-press drag reorder — `(from, to)` indices into
     * [sessions]. Passed straight through to [MobileTabsDrawer]; the model
     * ([SessionTabs.reorder]) and the gesture live in the drawer, so the bottom
     * bar only carries the host's callback.
     */
    onMoveSession: (Int, Int) -> Unit = { _, _ -> },
    /** B-20: the drawer's long-press menu Rename — `(tab, new title)`. */
    onRenameSession: (SessionTabRef, String) -> Unit = { _, _ -> },
) {
    val c = OcTheme.colors
    // Upstream's fallback for a tab whose title has not arrived yet
    // (`tab-title.ts:4-6` via `titlebar.tsx:430-433`).
    val sessionLabel = stringResource(R.string.oc_tab_session)
    // v98 fix3 — **no strip-local open flag any more.** The one state that says
    // "the sheet is open" is [MobileDrawerMount.open], the same object the shell's
    // gate reads (`ui/App.kt`). v98 fix2 kept a local `drawerOpen` here and
    // published a *copy* of it into the mount from a `SideEffect`, i.e. after
    // composition **and** apply: a click reached the screen only via
    // click → invalidate the strip → recompose the strip → post-apply copy →
    // invalidate the *ancestor* scope → a second frame re-reads the gate. Two
    // state objects, two invalidations, one deferred hop — and a hop that
    // produces no follow-up frame is **completely invisible**: no scrim, no
    // drawer row, no a11y delta, no crash. That is the whole of round three's
    // "7 taps, byte-identical dumps" (`REPORT_DRAWER4.md` §2.2). Writing the
    // mount's own flag from the click handler makes it one state and one write,
    // so the gate cannot miss it. `content` still rides a `SideEffect` — the
    // strip owns the callbacks, and the shell has to invoke them.
    // NOTE: the flag is read *inside* the handed-over lambda below, never
    // hoisted into a `val` here — hoisting it would re-introduce the very
    // copy-that-can-drift this fix removes.

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(c.bgDeep)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .height(BAR_HEIGHT)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TAB_GAP),
    ) {
        // `home.toggle` — the Home button (`grid-plus`), tinted like the active
        // tab while Home is on screen.
        TabIconButton(
            iconRes = R.drawable.ic_oc_grid,
            description = stringResource(R.string.oc_home_title),
            tint = if (sessionActive) c.iconMuted else c.iconBase,
            active = !sessionActive,
            onClick = onHome,
        )
        if (session != null) {
            // The separator only shows next to an *inactive* slot (§9b.2).
            if (!sessionActive) TabSeparator()
            SessionTab(
                tab = session.copy(title = SessionTabs.displayTitle(session.title, sessionLabel)),
                active = sessionActive,
                // WAVE 2.3b: the slot is the drawer trigger (`MobileDrawerTrigger`
                // `data-slot="mobile-tabs-trigger"`), not a direct open.
                // v98 fix3: this writes the **shell's own** flag — the exact
                // state the host gate reads — instead of a local flag that a
                // `SideEffect` copied over after the frame. One write, one
                // invalidation, the gate cannot miss it.
                onClick = { drawerMount.open = true },
                onClose = { onCloseSession(session) },
                badge = tabBadge(session),
                // v98 fix3: the trigger was the app's only drawer entry and it
                // carried **no** content description, so `uiautomator` reported
                // it as an unlabelled clickable node and the real-machine round
                // had nothing to resolve a tap target from
                // (`REPORT_DRAWER4.md` §2.1: "clickable、无 content-desc").
                // The label is the tab/session string the strip already loads
                // for its own fallback title (`:207`) — no new user-visible
                // string, and the same `semantics { contentDescription = … }`
                // shape [TabIconButton] already uses for every other entry.
                label = sessionLabel,
                modifier = Modifier.widthIn(max = TAB_MAX_WIDTH),
            )
        }
        TabIconButton(
            iconRes = R.drawable.ic_oc_plus,
            description = stringResource(R.string.oc_new_session),
            tint = c.iconMuted,
            active = false,
            onClick = onNewSession,
        )
        // v35 tA-terminal: the palette entry, the bottom placement's half of the
        // trailing slot [DesktopTabStrip] carries at the top. See that call for
        // why the glyph is the `>_` console and not upstream's magnifier.
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

    // v98 fix2: the sheet is **handed over**, not emitted here. Composing it in
    // this body made it a flow child of the shell `Column` — the `Column`
    // measured its non-weighted `fillMaxSize` host first, the `weight(1f)`
    // NavHost was starved to 0 px and the bar slid to y=0 (N-3, and the mobile
    // shell-wide collapse in `REPORT_DRAWER2.md` §2). The strip keeps the state
    // and every callback; only the composition point moves, to the shell's
    // `Box` sibling slot (`ui/App.kt`), which is what a `position: fixed`
    // sheet does upstream. v98 fix3: this lambda reads the open flag **out of
    // the mount** (`open = drawerMount.open`, read where the shell invokes
    // it) instead of closing over a strip-local copy, so the sheet and the
    // shell's gate can never disagree about whether it is open.
    val drawerContent: @Composable () -> Unit = {
        MobileTabsDrawer(
            open = drawerMount.open,
            onDismissRequest = { drawerMount.open = false },
            sessions = sessions,
            activeSessionId = activeSessionId,
            sessionLabel = sessionLabel,
            tabBadge = tabBadge,
            onOpenSession = {
                drawerMount.open = false
                onOpenSession(it)
            },
            onCloseSession = onCloseSession,
            onNewSession = {
                drawerMount.open = false
                onNewSession()
            },
            onHome = {
                drawerMount.open = false
                onHome()
            },
            onOpenSettings = {
                drawerMount.open = false
                onOpenSettings()
            },
            onOpenHelp = {
                drawerMount.open = false
                onOpenHelp()
            },
            // B-6: the closed-tab entry (`tab.reopenClosed`).
            closedTab = closedTab,
            onReopenClosed = {
                drawerMount.open = false
                onReopenClosed()
            },
            // B-20: drag reorder + long-press rename of the drawer's tab rows.
            onMoveSession = onMoveSession,
            onRenameSession = onRenameSession,
        )
    }
    // v98 fix3: **content only.** The open flag is no longer published here —
    // it is written straight to the mount by the trigger and by every close
    // path, so the shell's gate reads the same object the click wrote. A
    // `SideEffect` that copied the flag after the frame was the defect
    // (`REPORT_DRAWER4.md` §2.2), and republishing it would put it back.
    SideEffect {
        drawerMount.content = drawerContent
    }
    // Leaving composition (desktop width, or a route that stands the bar down)
    // must not leave a sheet mounted: the shell reads this on its own frame.
    DisposableEffect(drawerMount) {
        onDispose {
            drawerMount.open = false
            drawerMount.content = null
        }
    }
}

/**
 * `[data-titlebar-tab-slot]:not(:first-child):not([data-active="true"])::before`
 * — a 1.5 × 12 dp pill between two inactive slots.
 *
 * `internal` (not file-private): [DesktopTabStrip] reuses this pill, [SessionTab]
 * and [TabIconButton] so the top and bottom placements stay one drawing.
 */
@Composable
internal fun TabSeparator() {
    Box(
        Modifier
            .width(1.5.dp)
            .height(12.dp)
            .clip(RoundedCornerShape(percent = 50))
            .background(OcTheme.colors.layer03),
    )
}

/** One tab slot; shared by [BottomTabStrip] and [DesktopTabStrip]. */
@Composable
internal fun SessionTab(
    tab: SessionTabRef,
    active: Boolean,
    onClick: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    /** A-2: the leading `unread` dot / `loading` marquee ([SessionTabBadgeSlot]). */
    badge: SessionTabBadge = SessionTabBadge.NONE,
    /**
     * v98 fix3: a11y label for the slot's own clickable node, or `null` for no
     * label at all. The bottom bar passes the tab/session string because its
     * slot is the **only** drawer entry in the app and it used to reach
     * `uiautomator` as an unlabelled clickable — there was no announced name
     * to find the entry by, on device or in any dump-driven tap
     * (`REPORT_DRAWER4.md` §2.1).
     *
     * Defaulted, and the desktop placement does not pass it: this is a label on
     * an existing node, not a new entry, and it changes no geometry, no
     * clickable and no string resource.
     */
    label: String? = null,
) {
    val c = OcTheme.colors
    val shape = OcTheme.shapes.md
    // Upstream's fallback for a tab whose title has not arrived yet
    // (`tab-title.ts:4-6` via `titlebar.tsx:430-433`): the desktop strip resolves
    // it at the call site; the drawer trigger above does the same, so this
    // default only fires for callers that pass a raw tab straight in.
    val title = SessionTabs.displayTitle(tab.title, stringResource(R.string.oc_tab_session))
    // Hoisted: `semantics {}` is not a composable scope. The label rides the
    // clickable Box below, not the `Icon` inside it — only the node carrying
    // ACTION_CLICK is announced as a button.
    val closeLabel = stringResource(R.string.oc_home_tab_close)
    Row(
        modifier = modifier
            .height(TAB_HEIGHT)
            .clip(shape)
            .background(Color.Transparent)
            .clickable(role = Role.Tab, onClick = onClick)
            // v98 fix3 — rides the clickable node, same shape as
            // [TabIconButton]'s, so the announced node is the one that is
            // actually tappable. Conditional: `null` leaves the node exactly as
            // it was, which is what the desktop placement gets.
            .then(
                if (label != null) Modifier.semantics { contentDescription = label }
                else Modifier,
            )
            .padding(start = 6.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // `[data-slot="project-avatar-slot"]` — the indicator, when there is one.
        // The gap lives on the slot, so a tab with no badge keeps exactly the
        // title/✕ geometry it had.
        SessionTabBadgeSlot(badge, modifier = Modifier.padding(end = 6.dp))
        Text(
            text = title,
            color = if (active) c.textBase else c.textFaint,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(shape)
                .clickable(role = Role.Button, onClick = onClose)
                .semantics { contentDescription = closeLabel },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_oc_close),
                contentDescription = null,
                tint = c.iconMuted,
                modifier = Modifier.size(12.dp),
            )
        }
    }
}

/**
 * B-20: the inline rename editor behind the session strip's long-press menu —
 * upstream's double-click rename (`tab-nav.tsx:64-67` `onRename`, called by
 * `titlebar.tsx` `TabNavItem`) has no touch equivalent, so the menu row is the
 * phone's entry and this is the editor it opens. Enter saves, Escape cancels —
 * the same contract the terminal chip's own field keeps
 * (`OcTerminalRenameField`, `TerminalPanel.kt`).
 *
 * The caller overlays it on the slot (`Modifier.matchParentSize()` inside the
 * slot's `Box`), so the strip's geometry is untouched while the field is open.
 *
 * v33 BUG-2: the field opens with the **whole** title selected
 * ([tabRenameEntrySelection]) so the first keystroke replaces the old name
 * instead of splicing into it — upstream `selectTitle()` (`tab-nav.tsx:152-164`)
 * and the header's editor (`InlineTitleEdit.entrySelection`, v32 BUG-2) both do
 * this. `BasicTextField`'s String overload cannot express a selection: it seeds
 * `TextFieldValue(text = value)`, i.e. `TextRange.Zero`, which put the cursor in
 * front of the old title.
 */
@Composable
internal fun TabRenameField(
    value: String,
    onValueChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = OcTheme.colors
    val focus = remember { FocusRequester() }
    // A `TextFieldValue`, not the caller's String: only the value overload
    // carries the entry selection. The draft still lives in the caller — every
    // edit is echoed back through `onValueChange`, so `onConfirm` reads the
    // value the user typed.
    var edit by remember { mutableStateOf(TextFieldValue(value, tabRenameEntrySelection(value))) }
    // The menu item that opened this is gone, so nothing else can hold focus:
    // asking for it here is what raises the keyboard on a phone.
    LaunchedEffect(Unit) { focus.requestFocus() }
    Box(
        modifier = modifier
            .clip(OcTheme.shapes.md)
            .background(c.bgDeep)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicTextField(
            value = edit,
            // v40 BUG-2: this field is `OcKeyboard.literal`, not the ASCII-tier
            // option, so a Chinese IME still folds `:`/`/` into `：`/`／`
            // (`research/v40_verify/tC/h4_typed.png`). The W5-F *name* rule
            // (`HalfWidthText.normalizeName`) is the input boundary; 1:1, so the
            // selection survives.
            onValueChange = {
                val folded = HalfWidthText.normalizeName(it)
                edit = folded
                onValueChange(folded.text)
            },
            singleLine = true,
            textStyle = TextStyle(color = c.textBase, fontSize = 13.sp),
            cursorBrush = SolidColor(c.textAccent),
            // Hardware Enter arrives as a key event; the phone's IME sends the
            // Done action instead, so both paths confirm.
            keyboardOptions = OcKeyboard.literal(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onConfirm() }),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focus)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.Enter, Key.NumPadEnter -> {
                            onConfirm()
                            true
                        }
                        Key.Escape -> {
                            onDismiss()
                            true
                        }
                        else -> false
                    }
                },
        )
    }
}

/**
 * The selection the rename field opens with: the **whole** title. Upstream
 * focuses and then selects it (`tab-nav.tsx:152-164` `selectTitle()`,
 * `tab-nav.tsx:64-67`), so the first keystroke replaces the old name rather than
 * landing in front of it.
 *
 * The same rule the header's editor keeps as `InlineTitleEdit.entrySelection`;
 * it is spelled out here because the strip must not depend on the session
 * header's editor, and it is pure so `TabRenameFieldSelectionTest` pins it.
 */
internal fun tabRenameEntrySelection(title: String): TextRange = TextRange(0, title.length)

/**
 * The Home / `+` square button; shared by both tab strips.
 *
 * [minTouchTarget] gives the clickable node a 48 dp finger without growing the
 * 28 dp glyph square ([ocMinTouchTarget]): v54 tD measured the desktop bar's
 * Home button at a ~29 dp hit box once the tab list overflowed, against a 48 dp
 * floor. It is opt-in because the ring overflows the bar — the caller owns the
 * spacing around it.
 */
@Composable
internal fun TabIconButton(
    iconRes: Int,
    description: String,
    tint: Color,
    active: Boolean,
    onClick: () -> Unit,
    minTouchTarget: Boolean = false,
) {
    val shape = OcTheme.shapes.md
    Box(
        modifier = Modifier
            .size(TAB_HEIGHT)
            // v56 (`research/v56_verify/tD/33_home_shrunk.png`, bounds
            // `[0,144][74,254]` = 28 dp): the ring has to sit **outside** the
            // `size`, not inside it. `ocMinTouchTarget` measures its child at
            // the 48 dp target and places that box centred on the visual one,
            // but a `Modifier.size` further down the chain enforces 28 dp on
            // itself whatever it is handed — so the ring was collapsed back to
            // the glyph, *and* shifted the 28 dp box 10 dp up and to the left,
            // half of it off the screen edge. Applied after `size` nothing
            // downstream re-clamps it: the node still reports 28 dp to the row
            // (so the bar's layout is untouched) and the clickable below is a
            // real 48 dp box.
            .then(if (minTouchTarget) Modifier.ocMinTouchTarget(visual = TAB_HEIGHT) else Modifier)
            .clip(shape)
            .background(if (active) OcTheme.colors.layer02 else Color.Transparent)
            .clickable(role = Role.Button, onClick = onClick)
            // The description rides the clickable Box, not the `Icon` inside it:
            // only the node carrying ACTION_CLICK is announced as a button.
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(16.dp),
        )
    }
}

/** `titlebar-v2` bar height (`h-9` = 36 dp). */
internal val BAR_HEIGHT = 36.dp

/** Tab item height (`h-7` = 28 dp), also the Home/`+` button size. */
internal val TAB_HEIGHT = 28.dp

/** `gap-1.5` = 6 dp. */
internal val TAB_GAP = 6.dp

/** `w-56` = 224 dp slot ceiling. */
internal val TAB_MAX_WIDTH = 224.dp
