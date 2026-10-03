package com.xiufeigo.opencodeshell.ui.session.header

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import com.xiufeigo.opencodeshell.ui.session.composer.HalfWidthText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.ServerConfigStore
import com.xiufeigo.opencodeshell.ui.components.OcEnterOnMount
import com.xiufeigo.opencodeshell.ui.components.OcKeyboard
import com.xiufeigo.opencodeshell.ui.components.OcPopoverDismiss
import com.xiufeigo.opencodeshell.ui.components.ocPopoverDismiss
import com.xiufeigo.opencodeshell.ui.palette.LocalPaletteHost
import com.xiufeigo.opencodeshell.ui.palette.PaletteHost
import com.xiufeigo.opencodeshell.ui.palette.PaletteHostKind
import com.xiufeigo.opencodeshell.ui.session.actions.LocalSessionActionsHost
import com.xiufeigo.opencodeshell.ui.session.actions.SessionActionsHost
import com.xiufeigo.opencodeshell.ui.session.actions.SessionActionsMenuItem
import com.xiufeigo.opencodeshell.ui.session.actions.SessionActionsMenuState
import com.xiufeigo.opencodeshell.ui.session.actions.SessionActionsModel
import com.xiufeigo.opencodeshell.ui.session.actions.rememberSessionActionsMenuState
import com.xiufeigo.opencodeshell.ui.session.btw.SessionBtwEntry
import com.xiufeigo.opencodeshell.ui.session.timeline.TimelineSearch
import com.xiufeigo.opencodeshell.ui.theme.OcMotion
import com.xiufeigo.opencodeshell.ui.theme.OcTheme
import com.xiufeigo.opencodeshell.ui.home.NewSessionWorktreeStore
import com.xiufeigo.opencodeshell.ui.workspace.WorktreeCreateDialog

/**
 * Actions of the ⋮ menu that the **call site** runs. [RENAME] is edited by the
 * header itself, [DELETE] and [EXPORT] are run by the call site (they need the
 * session id, the server client and, for delete, the confirm dialog).
 *
 * The W6 session actions (撤销 / 重做 / 分叉) deliberately live
 * outside this enum: they are carried out by `SessionActionsHost` (the
 * `ui.session.actions` package), a fresh group below a divider, and the call
 * site's `when (action)` over these three values stays exhaustive and unchanged.
 * The two enums are bridged by `SessionActionsMenuItem`.
 */
enum class SessionMenuAction { RENAME, DELETE, EXPORT }

/**
 * Sticky session header (`research/02` §2.2) — **one** 48 dp row, rendered as
 * the pinned top overlay of the message timeline:
 *
 * ```
 * div.h-12 (48px), justify-between, gap-2
 * ├─ left  (gap-1, min-w-0, flex-1)
 * │   ├─ button[session-title-parent]   13px/530  → here: back chevron
 * │   └─ h1[session-title-child]        13px/530  → clickable → InlineInput rename
 * └─ right (shrink-0, gap-2)
 *     ├─ <SessionContextUsage/>        context ring (14 dp)
 *     └─ MenuV2 (outline-dots)         ⋮  (Rename / Export / Delete…)
 * ```
 *
 * A1 adds the summary trigger to that trailing cluster (`onToggleSummary`), and
 * [SessionSummaryPopover] hosts the panel it opens.
 *
 * M2 resolution of the "app bar vs sticky row" question: the ring and the ⋮
 * menu live **in the sticky row, not in an app top bar**, so the M1
 * `SessionTopBar` is gone and this row is the only title surface (no duplicated
 * title while scrolling). The session-drawer entry (☰) is an Android addition
 * next to the back chevron: upstream reaches the session list through Home,
 * which a phone shell does not show while a session is open.
 *
 * Rename follows the upstream InlineInput contract: **Enter saves, Esc cancels,
 * blur saves**.
 */
@Composable
fun SessionHeader(
    title: String,
    onRename: (String) -> Unit,
    onBack: () -> Unit,
    onOpenDrawer: () -> Unit,
    onMenuAction: (SessionMenuAction) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Legacy sweep fraction. Superseded by [contextPercent] — kept so the
     * pre-W4-B call site (`SessionScreen.kt:272`, which passed
     * `ContextUsage.progressOf(messages, limit = null)`) still compiles.
     */
    contextProgress: Float = 0f,
    /**
     * `round(total / model.limit.context * 100)`, clamped 0…100; `null` while
     * either side of the division is unknown (`ContextUsage.percentOf`). When
     * set it wins over [contextProgress].
     */
    contextPercent: Int? = null,
    /** Token total of the last assistant message — the tooltip's `Tokens` row. */
    contextTokens: Long? = null,
    /** Session cost (`Session.Info.cost`) — the tooltip's `Cost` row when known. */
    contextCost: Double? = null,
    /**
     * Tap action of the ring. Upstream's `SessionContextUsage` toggles the
     * Review→Context panel (`session-context-usage.tsx:93-108`); until the call
     * site provides it, a tap opens the Cost/%/Tokens popover instead (the
     * upstream tooltip, `:131-155`) and a long press always does.
     */
    onOpenContext: (() -> Unit)? = null,
    /**
     * A1: `session.summary.toggle` (`session/summary/popover.tsx:17-34`) — a
     * trailing icon button that toggles the summary popover. Null hides the
     * button; the phone form reaches the same panel through
     * `MobileSessionTabs`' ⋯ menu (`onOpenSummary`) instead, exactly like
     * upstream, which only mounts the popover trigger on wide screens.
     */
    onToggleSummary: (() -> Unit)? = null,
    /** Highlight state of that button while the popover is up. */
    summaryOpen: Boolean = false,
    /**
     * v31 BUG-2: the inline title editor opened / closed. The call site arms its
     * tap-away on this — the header owns the editor state, the screen owns the
     * body the outside tap lands on.
     */
    onTitleEditing: (Boolean) -> Unit = {},
    /**
     * v97 N3 fix2 (`research/v97_fix/TITLE_DISCARD_FIX2.md`): the call site's
     * authorization for a blur-driven save. Its outside-tap handler arms the
     * fence immediately before it clears focus, so **a tap-away inside the
     * session still commits** while **leaving the session discards** — the shell's
     * navigation chrome is `Modifier.clickable`, and a clickable takes focus, so
     * 首页/Home and the tab strip blur the field from outside the composition.
     *
     * Null (a preview, a host with no screen-level tap-away) therefore **discards
     * on blur**: a host that never installed the tap-away handler has no way to
     * produce an in-session tap-away blur, so the only blurs it can ever see are
     * focus steals, and a focus steal must never save. Defaulting to "save" here
     * would put the data-loss bug back for every host that forgot to pass it.
     */
    titleEditFence: TitleEditFence? = null,
    /**
     * What the inline rename editor opens with. Null (the default — previews
     * and hosts without a title chain) seeds from [title]; the session screen
     * passes the own-title-only seed (`sessionRenameSeed` in `ui/App.kt`) so
     * the field never offers the display fallbacks as a name to save.
     */
    renameSeed: String? = null,
    /**
     * 「导入会话…」, the row the ⋮ menu carries beside 「导出会话」. Upstream has
     * **no** import entry in the session screen at all — its only one is the
     * Home project row's menu (`home/projects/controller.tsx:109-135`) — so this
     * is a deliberate Android addition for *discoverability*, recorded in
     * [SessionOverflowMenu]'s KDoc: the Context tab's four-row export menu is
     * the upstream-faithful home of the import, but on a phone it sits behind
     * ⋯(tabs) → 用量 → scroll → 原始消息 → ⋯, and two device passes (v36/v38 tB)
     * never found it. Null (a preview, a host with no importer) leaves the menu
     * exactly as it was.
     */
    onImportSession: (() -> Unit)? = null,
) {
    val c = OcTheme.colors
    var editing by remember { mutableStateOf(false) }
    // Published for the call site's tap-away, and dropped again with the row so
    // a header that leaves the composition cannot leave that layer armed.
    LaunchedEffect(editing) { onTitleEditing(editing) }
    DisposableEffect(Unit) { onDispose { onTitleEditing(false) } }
    // A2: the timeline search owns the header row while it is open. The state is
    // the session-scoped `TimelineSearch` controller (see its KDoc for why it is
    // not a parameter: the header is rendered outside the timeline on the mobile
    // form and inside its sticky slot on the desktop one).
    val searching = TimelineSearch.open
    val canSearch = TimelineSearch.available
    // v97 (`research/v97_verify/5562_sweepB/REPORT_v97b.md` §3.5, Q4–Q10): the
    // 🔍 / 「Ask a side question」 entries exist **only** outside the two editing
    // states, and the row early-returns above while the search bar is up, so
    // "these two nodes are gone" is exactly the predicate below being false.
    // It was read off the wire as a permanently stuck header; the device dumps
    // say the state was the **inline title editor** (see
    // `research/v97_fix/SESSION_SEARCH_FIX.md`). The predicate is named and pure
    // so the exit-path reset is pinned on the JVM instead of on a device.
    val trailingActionsVisible = sessionHeaderActionsVisible(searching, editing)
    // W6: the session-action rows (撤销 / 重做 / 分叉 / 移动到…). The host is
    // published by `ui/App.kt`; without it (a preview, the legacy chat tab) the
    // four rows are simply absent and the menu keeps its three M2/A8 entries.
    // Both reads happen before the Row so the search/rename early-outs below
    // cannot change the composition order of these composable calls.
    val actionsHost = LocalSessionActionsHost.current
    val actionsMenu = if (actionsHost == null) null else rememberSessionActionsMenuState(actionsHost)
    // v35 tA-terminal: the shell-level rows (命令面板 / 调试栏). Same host pattern,
    // same reason — the palette's `DialogController` and the `debugBarOpen` flag
    // live in `ui/App.kt`, and `SessionScreen` calls this composable with a fixed
    // argument list. Null (a preview, a header outside the shell) drops the two
    // rows and leaves the menu exactly as it was.
    val paletteHost = LocalPaletteHost.current
    // P1: the create stop of the Move group (`session-workspace-menu.tsx:103-127`)
    // opens the branch picker + create as one modal.
    var worktreeCreateOpen by remember { mutableStateOf(false) }
    // The tracked session's server-confirmed directory — the create's source
    // checkout (upstream `directory: props.directory`, `session-workspace-menu.tsx:68`).
    var sessionDirectory by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(actionsHost) {
        val host = actionsHost ?: return@LaunchedEffect
        host.viewModel.currentDirectory.collect { sessionDirectory = it }
    }
    val context = LocalContext.current
    // The same second read-only handle on the shared server prefs
    // `NewSessionScreen` documents: the header is handed neither the host's
    // `ServerConfigStore` nor its private `V2Api`.
    val worktreeStore = remember(context) { ServerConfigStore(context).also { it.restore() } }
    // How many fixed-size targets follow the title — the budget
    // [sessionHeaderMetrics] spends the row's width on. The ring and the ⋮ menu
    // are always there; the three entries before them come and go with the
    // search/edit state and the host.
    val trailingIconCount =
        (if (canSearch && !editing && !searching) 1 else 0) +
            (if (!editing) 1 else 0) +
            (if (onToggleSummary != null) 1 else 0) +
            2

    // v49 BUG-1 (`research/v49_verify/tC/v49_37_narrow_home.png`): on a 640x1500
    // @420dpi window — ~243 dp wide, half of a phone — the trailing cluster (the
    // search / `/btw` / summary entries, the context ring and the ⋮ menu, six
    // 40 dp targets) measured at its full width and left the weighted title
    // **53 px**: one ellipsised character, i.e. no way to tell which session is
    // open. Compose gives a `Row`'s non-weighted children first claim on the
    // width and hands the weighted ones whatever is left, which can be nothing.
    //
    // So the row's own budget is computed instead of guessed: the trailing
    // entries are fixed-size boxes (each `ICON_BUTTON_SIZE`, the inner `size()`
    // of the entry composables coerces to whatever this modifier passes), so the
    // cluster width is `count * size` exactly, and the touch target shrinks a
    // step at a time until the title is guaranteed [SESSION_TITLE_MIN_WIDTH].
    // [sessionHeaderMetrics] is the decision, and it is pure so a unit test can
    // pin the narrow-window numbers.
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(HEADER_HEIGHT)
            .padding(start = HEADER_EDGE_PADDING, end = HEADER_EDGE_PADDING),
    ) {
        val metrics = sessionHeaderMetrics(
            windowWidth = maxWidth,
            trailingIconCount = trailingIconCount,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
        HeaderIconButton(
            iconRes = R.drawable.ic_oc_chevron_down,
            contentDescription = stringResource(R.string.oc_back),
            iconModifier = Modifier.rotate(90f),
            onClick = onBack,
            modifier = Modifier.size(metrics.iconButtonSize),
        )
        // M2 addition: the session drawer entry (phone-first navigation).
        HeaderIconButton(
            iconRes = R.drawable.ic_oc_menu,
            contentDescription = stringResource(R.string.oc_open_sessions),
            onClick = onOpenDrawer,
            modifier = Modifier.size(metrics.iconButtonSize),
        )

        if (searching) {
            // The bar replaces the title, the ring and the ⋮ menu: at phone width
            // upstream's 200 px field does not fit next to them.
            TimelineSearchBar(modifier = Modifier.weight(1f))
            return@Row
        }

        if (editing) {
            InlineTitleInput(
                initial = renameSeed ?: title,
                titleEditFence = titleEditFence,
                onCommit = { renamed ->
                    editing = false
                    if (renamed != title) onRename(renamed)
                },
                onCancel = { editing = false },
                modifier = Modifier.weight(1f),
            )
        } else {
            Text(
                text = title,
                color = c.textBase,
                fontSize = 13.sp,
                fontWeight = FontWeight(530),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // v97 HIGH-1 (data loss), the *actual* defect behind the
                // "the search box renamed my session" report. The clickable box
                // is this Text's own bounds, so sizing it with `width(...)` made
                // it swallow **every** pixel the trailing cluster did not claim:
                // on the 1366 px / 160 dpi window of `emulator-5562` that is
                // `[84,110][1118,158]` — 1034 x 48 px of invisible hit area
                // behind a 90 px string (`v97b_002_session01.xml`), and the
                // 🔍 `Search session` entry is the 40 px at its right edge
                // (`[1118,110][1158,158]`). A tap anywhere in that band landed on
                // the **rename editor**, and Enter there is `saveTitleEditor` —
                // so a mis-aimed tap rewrote the session title on the server.
                //
                // Upstream's title is `w-fit` (`message-timeline.tsx:451`:
                // `truncate … w-fit rounded-[6px] px-1 py-1 hover:bg-…`), i.e.
                // the hit area is the text. `widthIn(max = …)` restores that and
                // keeps the v49 narrow-window clamp: [metrics] still caps the box
                // at the leftover width, and a title longer than the cap ellipsises
                // inside it exactly as before.
                modifier = Modifier
                    .widthIn(max = metrics.titleWidth)
                    .clip(OcTheme.shapes.md)
                    .clickable(role = Role.Button) { editing = true }
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            )
            // Whatever the title did not need goes between the title and the
            // trailing cluster — on a phone window that is what keeps the ring
            // and the ⋮ menu on the screen's trailing edge, where the tab
            // strip and the user's thumb expect them.
            Spacer(Modifier.weight(1f))
        }

        // A2 entry: same slot the open bar takes, i.e. in front of the ring
        // (`message-timeline.tsx:544-548`). Upstream reaches the search through
        // `mod+f` only; a touch shell needs a visible button.
        if (canSearch && trailingActionsVisible) {
            TimelineSearchEntry(modifier = Modifier.size(metrics.iconButtonSize))
        }

        // `/btw` side-question entry (`ui/session/btw`): upstream opens the panel
        // through the hidden `/btw` slash command (`session/btw/model.ts`
        // `command.register`), which no composer route reaches here yet — the
        // button is the entry until it does. Renders nothing without a host.
        if (trailingActionsVisible) SessionBtwEntry(modifier = Modifier.size(metrics.iconButtonSize))

        // A1: the summary trigger, in front of the ring — upstream renders the
        // `window-analytics` icon button in the same trailing cluster
        // (`session/summary/popover.tsx:17-34`); Android's own asset set has no
        // analytics glyph, so the generic 2×2 grid (already the Home entry's
        // icon) stands in.
        if (onToggleSummary != null) {
            HeaderIconButton(
                iconRes = R.drawable.ic_oc_grid,
                contentDescription = stringResource(R.string.oc_command_summary_toggle),
                onClick = onToggleSummary,
                active = summaryOpen,
                modifier = Modifier.size(metrics.iconButtonSize),
            )
        }

        // `variant="indicator"` ring, now fed by the real denominator
        // (`--v2-background-bg-icon-button-contrast`-free, `icon-base` progress)
        // and clickable (W4-B item 8).
        ContextUsageButton(
            percent = contextPercent,
            tokens = contextTokens,
            cost = contextCost,
            fallbackProgress = contextProgress,
            onOpenContext = onOpenContext,
            modifier = Modifier.size(metrics.iconButtonSize),
        )
        SessionOverflowMenu(
            onRename = { editing = true },
            onMenuAction = onMenuAction,
            onImportSession = onImportSession,
            actionsHost = actionsHost,
            actionsMenu = actionsMenu,
            onCreateWorktree = { worktreeCreateOpen = true },
            paletteHost = paletteHost,
            modifier = Modifier.size(metrics.iconButtonSize),
        )
        }
    }

    // `session-workspace-menu.tsx:103-127` `move("create")`: create a worktree
    // from the session's own checkout and move the session into it. The dialog
    // opens a window of its own (`androidx.compose.ui.window.Dialog`), so it is
    // not constrained by the header's 48 dp row — the reason
    // `SessionActionsOverlay` has to be mounted as a sibling of `SessionScreen`
    // does not apply here, and this surface needs neither the session id nor the
    // transcript.
    if (worktreeCreateOpen && actionsHost != null) {
        WorktreeCreateDialog(
            sourceDirectory = sessionDirectory,
            serverStore = worktreeStore,
            onCreated = { created ->
                worktreeCreateOpen = false
                // v68: the new-session list's backstop only admits what this
                // device recorded — record the Move create too (no branch is
                // picked on this path), so the fresh worktree lists on a later
                // visit even when the server inventory has not caught up yet.
                NewSessionWorktreeStore(context).record(created, null)
                actionsHost.viewModel.moveSessionTo(created) { ok ->
                    if (!ok) {
                        Toast.makeText(
                            context,
                            context.getString(R.string.common_requestFailed),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            },
            onDismiss = { worktreeCreateOpen = false },
        )
    }
}

/** 40 dp circular touch target around a 16 dp icon (mirrors the M1 back button). */
@Composable
private fun HeaderIconButton(
    iconRes: Int,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconModifier: Modifier = Modifier,
    /** Accent tint only — the header's toggle affordances do not use a disc. */
    active: Boolean = false,
) {
    val c = OcTheme.colors
    Box(
        modifier = modifier
            .size(ICON_BUTTON_SIZE)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            // The description rides the clickable Box, not the `Icon` inside it:
            // only the node carrying ACTION_CLICK is announced as a button, and
            // Compose reads `contentDescription` from a node's own config.
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = if (active) c.iconAccent else c.iconBase,
            modifier = iconModifier.size(16.dp),
        )
    }
}

/**
 * Inline rename field. Upstream v2 styles the editable title as
 * `w-fit rounded-[6px] px-2 py-1` — same 13 px / 530 metrics as the display
 * state so the row does not jump.
 *
 * Commit contract: Enter saves, Esc cancels, blur saves — upstream's
 * `InlineInput` (`session/timeline/message-timeline.tsx:473-485`: `onKeyDown`
 * Enter → `saveTitleEditor`, Escape → `closeTitleEditor`, `onBlur` → save). The
 * `focused` flag exists because `onFocusChanged` fires once with
 * `isFocused == false` when the field is first composed, before the request
 * below lands — committing there would instantly close an untouched editor.
 *
 * v31 BUG-2: **Enter is handled as a key event too**, not only through the IME's
 * `Done` action. On a phone the soft keyboard sends the action, but a hardware
 * Enter (`input keyevent 66`, a docked keyboard, a scrcpy key) reaches the field
 * as a plain key event and Compose never turns it into `onDone` — so the only
 * commit that ever ran on the device was the IME's ✓, and Enter did nothing.
 * The same pair is what `TabRenameField` and `OcTerminalRenameField` already
 * keep.
 *
 * v32 BUG-1/BUG-2: the draft's commit/cancel decision and the entry selection are
 * [InlineTitleEdit]'s. Two device bugs came out of this field keeping them itself:
 * Escape's cancel was overtaken by the blur that closing the editor produces (the
 * field is *detached*, which force-clears focus with a focus-event refresh), and
 * the String overload of `BasicTextField` cannot carry a selection, so the cursor
 * sat in front of the old title instead of selecting it.
 */
@Composable
private fun InlineTitleInput(
    initial: String,
    onCommit: (String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * v97 N3 fix2: who authorized a blur-driven save — see [TitleEditFence]. Null
     * (a preview, or a host that never installed the screen's tap-away handler)
     * means no authorization is obtainable, so a blur is discarded: such a host
     * can only ever see focus steals, and those must never save.
     */
    titleEditFence: TitleEditFence? = null,
) {
    val c = OcTheme.colors
    val edit = remember { InlineTitleEdit(initial) }
    // A `TextFieldValue`, not a String: only the value overload can carry the entry
    // selection ([InlineTitleEdit.entrySelection]) — the String overload seeds
    // `TextFieldValue(text = value)`, i.e. `TextRange.Zero`.
    var value by remember { mutableStateOf(TextFieldValue(initial, edit.entrySelection)) }
    var focused by remember { mutableStateOf(false) }
    // The field's **current** focus, as opposed to `focused`, which is the
    // v32 "has ever been focused" latch that keeps the first `isFocused == false`
    // (fired while the field is being composed) from committing an untouched
    // editor. `commit()` needs the live answer: a key that arrives while the
    // field holds focus is the user saving, and needs no authorization at all.
    var fieldFocused by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    /**
     * The blur that nobody asked for: the chrome took the focus, or the
     * composition is going away. Upstream discards in exactly this state
     * (`message-timeline.tsx:285-289`), and it reuses [InlineTitleEdit.leave] so
     * the teardown latch and this one are the same promise — a discarded editor
     * can never be rescued by a later blur.
     */
    fun discard() {
        if (edit.leave().close) onCancel()
    }

    fun commit() {
        // v97 N3 fix2 (`research/v97_fix/TITLE_DISCARD_FIX2.md`, device B4 twice on
        // two builds): a blur is **not** by itself a reason to save. The shell's
        // navigation chrome — 首页/Home, the tab strip, the back chevron, the
        // drawer entry — is `Modifier.clickable`, and a clickable *takes focus*,
        // so leaving the session blurs this field while the session is still
        // composed. That is indistinguishable from a tap-away inside the session,
        // and the unconditional `commit()` below saved the half-typed draft
        // (`E2ECHK5556v98_discard` reached `GET /api/session` twice). The
        // teardown latch installed for it could not help: this blur is a focus
        // steal, not a detach, so the save is already dispatched before the
        // composition is ever torn down.
        //
        // The fence replaces the guess with an authorization. Focused + a key
        // event is the user saving (Enter / the IME's `Done`) and is never gated.
        // Unfocused, only the screen's own outside-tap handler may have armed it.
        if (!fieldFocused && titleEditFence?.consumeBlurSave() != true) {
            discard()
            return
        }
        val decision = edit.commit(value.text)
        if (decision.save != null) onCommit(decision.save) else if (decision.close) onCancel()
    }

    fun cancel() {
        if (edit.cancel().close) onCancel()
    }

    BasicTextField(
        value = value,
        // v40 BUG-2: `OcKeyboard.literal` only sets `autoCorrectEnabled = false`;
        // a Chinese IME still rewrites `:` into `：` (U+FF1A) and `/` into `／`
        // (`research/v40_verify/tC/h4_typed.png`). `HalfWidthText.normalizeName`
        // — the W5-F *name* rule — is the input boundary for exactly this, and it
        // is 1:1, so the selection and the composing region survive it.
        onValueChange = { value = HalfWidthText.normalizeName(it) },
        singleLine = true,
        textStyle = TextStyle(
            color = c.textBase,
            fontSize = 13.sp,
            fontWeight = FontWeight(530),
        ),
        cursorBrush = SolidColor(c.textBase),
        keyboardOptions = OcKeyboard.literal(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { commit() }),
        modifier = modifier
            .padding(horizontal = 6.dp)
            .clip(OcTheme.shapes.md)
            .background(c.layer02)
            .padding(horizontal = 8.dp, vertical = 5.dp)
            .focusRequester(focusRequester)
            .onFocusChanged { state ->
                // v97 N3 fix3: mirror the live focus **in this handler, before the
                // `commit()` it may reach on the line below**. `fieldFocused` also has
                // an `onFocusEvent` observer further down the chain, but that is a
                // *different* focus node, and the order in which the two observers
                // see one focus event is the focus target chain's business, not
                // ours. If `onFocusChanged` ran first while `fieldFocused` was still
                // `true`, `commit()`'s gate would read a stale `true`, the
                // `!fieldFocused` half would be false, and an **unarmed** focus
                // steal — the chrome's 首页/Home button — would sail straight
                // through to `edit.commit(value.text)`. That is fix2's exact
                // failure mode surviving on a device while every JVM test stayed
                // green, so the blur's own observer is the one that must set it.
                fieldFocused = state.isFocused
                if (state.isFocused) focused = true else if (focused) commit()
            }
            // v97 N3 fix2: the field's *current* focus, which is what tells a save
            // that arrives while the user is still typing (Enter, the IME's `Done`)
            // apart from a blur produced by something outside the editor taking
            // the focus for itself. It observes only — it never consumes, and it
            // never decides, so the upstream `onBlur → save` line above stays
            // exactly as `SessionTitleInlineEditTest.escapeCancels` pins it.
            .onFocusEvent { state ->
                fieldFocused = state.isFocused
                if (state.isFocused) titleEditFence?.disarm()
                false
            }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.Enter, Key.NumPadEnter -> {
                        commit()
                        true
                    }

                    Key.Escape -> {
                        cancel()
                        true
                    }

                    else -> false
                }
            },
    )
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboard?.show()
    }
    // v97 N3 (`research/v97_fix/TITLE_EDITOR_CANCEL_DIAG.md` §★, the
    // `E2ECHK5562-01 → Queued` data loss): leaving the screen used to **commit** the
    // draft. Upstream discards it — `message-timeline.tsx:285-289` closes the editor
    // and clears `draft` when `props.active === false`, and never calls `rename`.
    // The shell had no such guard, and the teardown blur supplied one: leaving
    // detaches the focused field, `FocusTargetNode.onDetach` force-clears focus
    // (the v32 mechanism above), and the `onFocusChanged` above ran `commit()` on
    // the half-typed draft. This dispose is the Android spelling of `active === false`;
    // it latches the discard in the same `applyChanges` pass that detaches the node,
    // so the trailing blur is inert. `onCancel()` is deliberately **not** called —
    // the editor is already going away, and writing `editing` during teardown says
    // nothing.
    DisposableEffect(Unit) {
        onDispose {
            // Any authorization still held dies with the editor it was given to.
            titleEditFence?.disarm()
            edit.leave()
        }
    }
}

/**
 * ⋮ menu (`MenuV2`): fixed **120 px** content width per §2.2, `layer-01`
 * surface, `lg` radius, a 0.5 dp border for definition on the layer background.
 * M2 wires *Rename*; *Delete* runs the call site's session-delete flow (W4-E F-4);
 * *Export…* (A8, W5-INT) serializes the session through the call site.
 *
 * W6 appends the four session-action rows below a divider: 撤销本轮变更 /
 * 重做变更 / 分叉会话 / 移动到… (v2-only: 分享 / 归档 hit v1 routes that 404 on v2, so
 * those rows are gone — see `SessionActionsMenuState`). They are a second
 * group on purpose — the three entries above are
 * `SessionMenuAction`s the *call site* runs (session id + client + confirm
 * dialog), while these four are carried out by [SessionActionsHost], which the
 * shell publishes for the session on screen. Enablement is
 * `SessionActionsModel.menu(...)`'s, i.e. the same projection upstream's command
 * palette uses for `session.undo` / `session.redo` / `session.fork`
 * (`use-session-commands.tsx:261,269,292`); 移动到… is always on while a session
 * is tracked (upstream moves from the workspace menu
 * `session-workspace-menu.tsx`, which Android does not render). P1 adds
 * 创建工作树 beside it — the same upstream menu's create stop
 * (`session-workspace-menu.tsx:103-127`), which creates a worktree from the
 * session's checkout and moves the session into it. This file decides no rule
 * of its own.
 *
 * v35 tA-terminal adds a last group of two **shell-level** rows, 命令面板 and
 * 调试栏, from [LocalPaletteHost]. They are Android's own entries: upstream opens
 * the palette from the titlebar's search/command-palette button
 * (`settings.general.row.showSearch.description`) or the `mod+k` / `mod+shift+p`
 * chords (`command.tsx:397-424`), and toggles the debug bar from the titlebar's
 * `ChannelIndicator` button on dev/local builds (`titlebar.tsx:789-825`) or the
 * `debugBar.toggle` palette row — a phone has no keyboard for the chords, and the
 * tester could reach neither. The strip's palette button
 * ([com.xiufeigo.opencodeshell.ui.nav.DesktopTabStrip]) is the port of the
 * titlebar control; this menu is the entry that is visible on every form.
 *
 * v39 adds 「导入会话…」 (`onImportSession`) directly under 「导出会话」 — a
 * **deliberate deviation**: upstream's session screen has no import entry at
 * all, and its only one is the Home project row's menu
 * (`home/projects/controller.tsx:109-135`). The upstream-faithful home of the
 * flow on Android is the Context tab's four-row export menu
 * (`ui/session/context/SessionContextPanel.kt`, which keeps all four rows), but
 * on a phone that menu sits behind ⋮(view tabs) → 「用量」 → scroll to the
 * bottom → 「原始消息」 → ⋯ — and two device passes (v36 tB, v38 tB) reported the
 * import as missing because they never walked that far. The pair also reads
 * naturally: upstream's own two command ids are `command.session.export` /
 * `command.session.import` (the same two labels the Home menu uses), and the
 * file an export wrote is what an import reads. The row carries **no** export
 * variants — ASSEMBLED / OFFICIAL / SANITIZED stay in the Context tab's menu,
 * which is the one place a format can be chosen.
 */
@Composable
private fun SessionOverflowMenu(
    onRename: () -> Unit,
    onMenuAction: (SessionMenuAction) -> Unit,
    onImportSession: (() -> Unit)?,
    actionsHost: SessionActionsHost?,
    actionsMenu: SessionActionsMenuState?,
    onCreateWorktree: () -> Unit,
    paletteHost: PaletteHost?,
    modifier: Modifier = Modifier,
) {
    val c = OcTheme.colors
    val shapes = OcTheme.shapes
    var expanded by remember { mutableStateOf(false) }
    // v49: ESC 关闭 + 外点穿透，宿主窗口在 Popup 之外取（见 `OcPopoverDismiss`）。
    val hostView = LocalView.current
    val popoverDismiss = remember { OcPopoverDismiss(hostView) }
    LaunchedEffect(expanded) { if (!expanded) popoverDismiss.flushPendingTap() }
    var anchor by remember { mutableStateOf(IntSize.Zero) }
    val gapPx = with(LocalDensity.current) { MENU_GAP.roundToPx() }
    val edgeInsetPx = with(LocalDensity.current) { MENU_EDGE_INSET.roundToPx() }

    Box(modifier = modifier.onSizeChanged { anchor = it }) {
        HeaderIconButton(
            iconRes = R.drawable.ic_oc_dots,
            contentDescription = stringResource(R.string.oc_more_options),
            onClick = { expanded = true },
        )
        if (expanded) {
            // Right-aligned under the anchor, 4 dp below it, clamped into the
            // window (v43: the card used to hug the screen's trailing edge).
            val provider = remember(anchor, gapPx, edgeInsetPx) {
                object : PopupPositionProvider {
                    override fun calculatePosition(
                        anchorBounds: IntRect,
                        windowSize: IntSize,
                        layoutDirection: LayoutDirection,
                        popupContentSize: IntSize,
                    ): IntOffset = sessionOverflowMenuOffset(
                        anchorRight = anchorBounds.right,
                        anchorBottom = anchorBounds.bottom,
                        popupWidth = popupContentSize.width,
                        windowWidth = windowSize.width,
                        gapPx = gapPx,
                        edgeInsetPx = edgeInsetPx,
                    )
                }
            }
            Popup(
                popupPositionProvider = provider,
                onDismissRequest = { expanded = false },
                // v49: explicit so a later edit cannot silently drop them back to
                // relying on the defaults — the flags are the dismiss contract
                // this menu has always advertised.
                properties = PopupProperties(
                    focusable = true,
                    dismissOnBackPress = true,
                    dismissOnClickOutside = true,
                ),
            ) {
                // M6: `menu-v2-in` — `.12s ease-out`, `scale(.96) → 1` + opacity,
                // `transform-origin: top center` (`research/03` §3.4). The Popup
                // mounts only while open, so the enter has to be played on mount.
                OcEnterOnMount(
                    overlay = OcMotion.Overlay.MENU,
                    // ESC 关闭 + 外点穿透（含 Popup 自己不建立的焦点归属）。
                    modifier = Modifier.ocPopoverDismiss(popoverDismiss) { expanded = false },
                ) {
                    Column(
                        modifier = Modifier
                            .widthIn(min = MENU_WIDTH, max = MENU_MAX_WIDTH)
                            .clip(shapes.lg)
                            .background(c.layer01)
                            .border(0.5.dp, c.borderBase, shapes.lg)
                            .padding(vertical = 4.dp),
                    ) {
                        MenuRow(
                            label = stringResource(R.string.oc_rename_session),
                            enabled = true,
                            onClick = {
                                expanded = false
                                onRename()
                            },
                        )
                        MenuDivider()
                        // A8: 「导出会话」. This used to be a placeholder comment
                        // ("server share/export is missing"); the export exists now
                        // — the call site pulls the raw `{info, messages}` JSON and
                        // writes it through SAF (`SessionExportSaver`).
                        // W6: 撤销 / 重做 / 分叉 / 移动到… — the session actions
                        // upstream registers as commands surface them here
                        // because Android has no command palette in the header
                        // and the ⋮ menu is where the user looks.
                        MenuRow(
                            label = stringResource(R.string.command_session_export),
                            enabled = true,
                            onClick = {
                                expanded = false
                                onMenuAction(SessionMenuAction.EXPORT)
                            },
                        )
                        // v39: 「导入会话…」, the row beside the export (see this
                        // composable's KDoc for why it is Android's own entry).
                        // Absent without a host, exactly like the palette rows
                        // below, so a preview keeps the menu it had. The label is
                        // upstream's own `command.session.import` — the id the
                        // Home project menu uses — so the two entries for the one
                        // flow read alike.
                        if (onImportSession != null) {
                            MenuRow(
                                label = stringResource(R.string.command_session_import),
                                enabled = true,
                                onClick = {
                                    expanded = false
                                    onImportSession()
                                },
                            )
                        }
                        MenuRow(
                            label = stringResource(R.string.oc_delete_session),
                            // W4-E F-4: the destructive flow exists now (the
                            // drawer rows run it), so the header entry stops
                            // being a dead placeholder. Upstream renders Delete
                            // for top-level sessions only; Android has no
                            // `parentID`, so it is always offered.
                            enabled = true,
                            onClick = {
                                expanded = false
                                onMenuAction(SessionMenuAction.DELETE)
                            },
                        )
                        // ---- W6 session actions ------------------------------
                        if (actionsHost != null && actionsMenu != null) {
                            MenuDivider()
                            MenuRow(
                                label = stringResource(R.string.command_session_undo),
                                enabled = actionsMenu.undoEnabled,
                                onClick = {
                                    expanded = false
                                    actionsHost.start(SessionActionsMenuItem.UNDO)
                                },
                            )
                            // v51 `tE`: 重做 is hidden rather than shown greyed
                            // out — 撤销 is destructive in this shell, so the row
                            // can never light up again after a revert settles
                            // (see `SessionActionsModel.showsRedo`).
                            if (SessionActionsModel.showsRedo(actionsMenu.redoEnabled)) {
                                MenuRow(
                                    label = stringResource(R.string.command_session_redo),
                                    enabled = actionsMenu.redoEnabled,
                                    onClick = {
                                        expanded = false
                                        actionsHost.start(SessionActionsMenuItem.REDO)
                                    },
                                )
                            }
                            MenuRow(
                                label = stringResource(R.string.oc_fork_session),
                                enabled = actionsMenu.forkEnabled,
                                onClick = {
                                    expanded = false
                                    actionsHost.start(SessionActionsMenuItem.FORK)
                                },
                            )
                            // F-4 移动到…: last of the session-action group,
                            // after 分叉 — upstream moves from the workspace
                            // menu (`session-workspace-menu.tsx`), which
                            // Android does not render, so the ⋮ group carries
                            // it. The row collapses the menu first like every
                            // other row; the host opens the directory picker.
                            MenuRow(
                                label = stringResource(R.string.oc_move_session),
                                enabled = actionsMenu.moveEnabled,
                                onClick = {
                                    expanded = false
                                    actionsHost.start(SessionActionsMenuItem.MOVE)
                                },
                            )
                            // P1 创建工作树 (`workspace.new`,
                            // `session-workspace-menu.tsx:103-127`): upstream's
                            // Move menu carries the create stop right after the
                            // Local one, and 移动到… is Android's projection of
                            // that same menu — so the create sits beside it.
                            // Enablement is the Move row's (upstream locks both
                            // on `store.selected`; the modal keeps the choice,
                            // so there is no second tap to guard).
                            MenuRow(
                                label = stringResource(R.string.workspace_new),
                                enabled = actionsMenu.moveEnabled,
                                onClick = {
                                    expanded = false
                                    onCreateWorktree()
                                },
                            )
                        }
                        // ---- v35 tA-terminal: the shell-level rows --------
                        // 命令面板 is the palette's touch entry (upstream: the
                        // titlebar button + `mod+k` / `mod+shift+p`); 调试栏 is the
                        // `debugBar.toggle` row, accent-tinted while the bar is up
                        // like upstream's `aria-pressed` on the titlebar's channel
                        // button. Both collapse the menu first, like every other
                        // row: the palette is a dialog layer and a popup left open
                        // would sit on top of it.
                        if (paletteHost != null) {
                            MenuDivider()
                            MenuRow(
                                label = stringResource(R.string.oc_palette_title),
                                enabled = true,
                                onClick = {
                                    expanded = false
                                    paletteHost.open(PaletteHostKind.SESSION)
                                },
                            )
                            MenuRow(
                                label = stringResource(R.string.command_debugBar_toggle),
                                enabled = true,
                                active = paletteHost.debugBarOpen,
                                onClick = {
                                    expanded = false
                                    paletteHost.toggleDebugBar()
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** The ⋮ menu's 0.5 dp group separator, inset 8 dp on both sides. */
@Composable
private fun MenuDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .height(0.5.dp)
            .background(OcTheme.colors.borderBase),
    )
}

/**
 * One ⋮ row. [active] tints the label with the accent colour while the row's
 * toggle is on — the same `active` idiom [HeaderIconButton] uses, and the state
 * upstream puts on its titlebar channel button (`aria-pressed`,
 * `titlebar.tsx:817`). 调试栏 is the only row that passes it today.
 *
 * The state is a tint rather than a trailing glyph on purpose: "Toggle debug
 * bar" is the longest label the menu carries, so a check mark would push it
 * into an ellipsis in English. A tint costs no width, and no `On`/`Off` copy
 * exists in `res/` to announce the state with (the label is the
 * `debugBar.toggle` command title — a verb that reads as an action on its own).
 */
@Composable
private fun MenuRow(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    active: Boolean = false,
) {
    val c = OcTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        Text(
            text = label,
            color = when {
                !enabled -> c.textMuted
                active -> c.iconAccent
                else -> c.textBase
            },
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * A1: the wide-screen home of `SessionSummaryPanel` — a `bottom-end` popover
 * anchored to the header band.
 *
 * Upstream positions it from a zero-width `h-7` anchor at `end-3 top-2.5`
 * (`session/summary/popover.tsx:17-34`): the popover's bottom-end lands 12 px
 * from the container's trailing edge and 38 px (10 + 28) below the header's top,
 * `width: 280px`. Android mounts the same rectangle — the popup is a child of the
 * header box, so `anchorBounds` **is** the header band.
 *
 * The content is a slot: the header stays free of session data (it lives in
 * `ChatViewModel` / `SessionScreen`) and `SessionSummaryPanel` keeps its
 * "values in, layout out" contract. Tapping outside dismisses
 * (`onDismissRequest` of a focusable popup), the same way the ⋮ menu behaves.
 */
@Composable
fun SessionSummaryPopover(
    open: Boolean,
    onDismiss: () -> Unit,
    /** Panel width; upstream's `width: 280px`. */
    width: Dp = SUMMARY_POPOVER_WIDTH,
    content: @Composable () -> Unit,
) {
    if (!open) return
    val density = LocalDensity.current
    val endPx = with(density) { SUMMARY_POPOVER_END.roundToPx() }
    val topPx = with(density) { SUMMARY_POPOVER_TOP.roundToPx() }
    val provider = remember(endPx, topPx) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ): IntOffset = IntOffset(
                x = anchorBounds.right - endPx - popupContentSize.width,
                y = anchorBounds.top + topPx,
            )
        }
    }
    Popup(
        popupPositionProvider = provider,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Box(Modifier.width(width)) { content() }
    }
}

/** `end-3` = 12 px from the container's trailing edge. */
private val SUMMARY_POPOVER_END = 12.dp

/** `top-2.5` (10 px) + the anchor's own `h-7` (28 px) = the popover's top. */
private val SUMMARY_POPOVER_TOP = 38.dp

/** `width: 280px` (`popover.tsx:17-34`), so the panel body fills `fillMaxWidth`. */
private val SUMMARY_POPOVER_WIDTH = 280.dp

/** `h-12` row (§2.2). Also the timeline's sticky top inset. */
val HEADER_HEIGHT = 48.dp

/** `IconButtonV2 size="large"` (§2.2) — the touch target around the 16 dp icons. */
private val ICON_BUTTON_SIZE = 40.dp

/**
 * The row's own inset from the window edges, inside whatever `modifier` the
 * caller passed. Named because the call site has to line the floating review
 * toggle up with the header's trailing cluster, and the arithmetic only closes
 * if both use this same number.
 */
internal val HEADER_EDGE_PADDING = 4.dp

/**
 * `MenuV2` content width (`style={{width:"120px"}}`, §2.2) — the **minimum**
 * only, since v43 (`research/v43_verify/tE/v43_38_more2.png`) found the fixed
 * width ellipsising the shell's own longest rows: 「Command palette」 rendered
 * `Command pal…` and 「Toggle debug bar」 was cut. Upstream's menu only ever
 * carries its own short titles; the two rows [SessionOverflowMenu] appends are
 * Android's, and one of them is longer than 120 px. Growing to the content up
 * to [MENU_MAX_WIDTH] keeps §2.2's width on a menu that fits it and stops the
 * truncation on one that does not.
 */
private val MENU_WIDTH = 120.dp

/** Ceiling for the growth above: past this a label is ellipsised as before. */
private val MENU_MAX_WIDTH = 200.dp

/**
 * Gap the menu keeps from the window's leading/trailing edge.
 *
 * v43 also found the menu card's right edge sitting ~2.5 dp from the screen
 * edge: `Popup`'s provider placed it at `anchorBounds.right - width` with no
 * clamp against [PopupPositionProvider.calculatePosition]'s `windowSize`, and
 * the ⋮ button is the last thing in the header, so the card's own right edge
 * landed on the anchor's right edge — which is the screen's. Clamping to
 * `windowSize.width - inset` pulls it back onto the screen; the same clamp on
 * the left is what keeps a narrow window from pushing it off the other side.
 */
private val MENU_EDGE_INSET = 8.dp

private val MENU_GAP = 4.dp

/**
 * Where the ⋮ menu card's top-left corner goes.
 *
 * Right-aligned under the anchor, 4 dp below it, then clamped into the window
 * with [edgeInsetPx] on both sides. Exposed for the JVM pin in
 * `SessionOverflowMenuTest` — `PopupPositionProvider` is an Android type, so
 * this is the only part of the placement a unit test can read.
 */
internal fun sessionOverflowMenuOffset(
    anchorRight: Int,
    anchorBottom: Int,
    popupWidth: Int,
    windowWidth: Int,
    gapPx: Int,
    edgeInsetPx: Int,
): IntOffset {
    val maxX = windowWidth - popupWidth - edgeInsetPx
    val x = (anchorRight - popupWidth).coerceIn(0, maxOf(maxX, 0))
    return IntOffset(x = x, y = anchorBottom + gapPx)
}

private const val DISABLED_ALPHA = 0.5f

/**
 * The v49 BUG-1 row budget: the touch-target size the header's icon buttons take
 * at this window width, and the **ceiling** for the title's box. Since v97 the
 * title is laid out with `widthIn(max = titleWidth)` — upstream's `w-fit` — so
 * this is the clamp the title ellipsises inside, not a width it is stretched to.
 */
internal data class SessionHeaderMetrics(
    val iconButtonSize: Dp,
    val titleWidth: Dp,
)

/**
 * How the header row divides its width between the title and the trailing icon
 * cluster.
 *
 * Compose's `Row` measures its non-weighted children first and hands the
 * weighted ones the remainder, so a `weight(1f)` title next to six fixed 40 dp
 * targets can be measured at **zero** on a narrow window — which is what
 * happened at 640 px @420 dpi (~243 dp, `research/v49_verify/tC/v49_37_narrow_home.png`):
 * the title came out 53 px wide, i.e. one ellipsised character.
 *
 * The trailing entries are all fixed-size boxes, so their width is exactly
 * `leading (2) + [trailingIconCount]` times the button size — no measurement
 * needed. The size then steps down through [HEADER_ICON_SIZES] until the title
 * is left at least [SESSION_TITLE_MIN_WIDTH]; a window too narrow for even the
 * smallest step keeps that step and gives the title what is left, because the
 * buttons are the ones that must stay reachable.
 *
 * @param windowWidth the row's own width, padding already removed.
 * @param trailingIconCount the fixed-size entries between the title and the
 *   end of the row (search, `/btw`, summary, the ring, ⋮).
 */
internal fun sessionHeaderMetrics(
    windowWidth: Dp,
    trailingIconCount: Int,
): SessionHeaderMetrics {
    val icons = (trailingIconCount.coerceAtLeast(0) + 2).coerceAtLeast(1)
    val size = HEADER_ICON_SIZES.firstOrNull { candidate ->
        windowWidth - candidate * icons >= SESSION_TITLE_MIN_WIDTH
    } ?: HEADER_ICON_SIZES.last()
    val available = windowWidth - size * icons
    return SessionHeaderMetrics(
        iconButtonSize = size,
        titleWidth = available.coerceAtLeast(0.dp),
    )
}

/**
 * The button sizes the header may fall back to, widest first. The first entry is
 * `ICON_BUTTON_SIZE` — the row as it renders on every ordinary phone and desktop
 * width, and unchanged whenever the window can afford it. The later entries are
 * the v49 BUG-1 fallbacks; the smallest still clears the 48 dp row with room to
 * spare and keeps the 16 dp glyph legible.
 */
private val HEADER_ICON_SIZES = listOf(40.dp, 32.dp, 24.dp)

/**
 * The width the title is guaranteed at the narrowest supported window: enough
 * for a short prefix plus the ellipsis, the way the tab strip's own labels are
 * truncated (`E2ECHK5558_hom…`). Below it the shell starts eating the buttons,
 * not the title — the title is how the user knows which session is open.
 */
private val SESSION_TITLE_MIN_WIDTH = 64.dp

/**
 * Whether the header's **trailing action entries** — 🔍 `Search session` and
 * 「Ask a side question」 — are on the row.
 *
 * They step aside for the two editing states and nothing else:
 *
 * - [editing] — the inline title editor has the title slot (`v32`/`InlineTitleEdit`),
 *   and `trailingIconCount` already budgets the two entries out of the row so the
 *   editor gets their width;
 * - [searching] — the bar *replaces* the title **and** the ring / ⋮ cluster
 *   (`return@Row` above), so the entries are unreachable rather than merely
 *   crowded. Stated here so the exit path is a checkable predicate: closing the
 *   search must put both back, and the v97 sweep read their absence as a
 *   permanently wedged header (`research/v97_verify/5562_sweepB/REPORT_v97b.md`
 *   Q4–Q6) when the state that had removed them was the *rename editor*, which
 *   resets on its own.
 *
 * Pure so `SessionSearchStateV97Test` can pin the reset on the JVM.
 */
internal fun sessionHeaderActionsVisible(searching: Boolean, editing: Boolean): Boolean =
    !searching && !editing
