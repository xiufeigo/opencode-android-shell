package com.xiufeigo.opencodeshell.ui.session.header

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
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
import com.xiufeigo.opencodeshell.ui.components.OcEnterOnMount
import com.xiufeigo.opencodeshell.ui.session.actions.LocalSessionActionsHost
import com.xiufeigo.opencodeshell.ui.session.actions.SessionActionsHost
import com.xiufeigo.opencodeshell.ui.session.actions.SessionActionsMenuItem
import com.xiufeigo.opencodeshell.ui.session.actions.SessionActionsMenuState
import com.xiufeigo.opencodeshell.ui.session.actions.rememberSessionActionsMenuState
import com.xiufeigo.opencodeshell.ui.session.timeline.TimelineSearch
import com.xiufeigo.opencodeshell.ui.theme.OcMotion
import com.xiufeigo.opencodeshell.ui.theme.OcTheme

/**
 * Actions of the ⋮ menu that the **call site** runs. [RENAME] is edited by the
 * header itself, [DELETE] and [EXPORT] are run by the call site (they need the
 * session id, the server client and, for delete, the confirm dialog).
 *
 * The W6 session actions (撤销 / 重做 / 分叉 / 分享 / 归档) deliberately live
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
) {
    val c = OcTheme.colors
    var editing by remember { mutableStateOf(false) }
    // A2: the timeline search owns the header row while it is open. The state is
    // the session-scoped `TimelineSearch` controller (see its KDoc for why it is
    // not a parameter: the header is rendered outside the timeline on the mobile
    // form and inside its sticky slot on the desktop one).
    val searching = TimelineSearch.open
    val canSearch = TimelineSearch.available
    // W6: the session-action rows (撤销 / 重做 / 分叉 / 分享 / 归档). The host is
    // published by `ui/App.kt`; without it (a preview, the legacy chat tab) the
    // five rows are simply absent and the menu keeps its three M2/A8 entries.
    // Both reads happen before the Row so the search/rename early-outs below
    // cannot change the composition order of these composable calls.
    val actionsHost = LocalSessionActionsHost.current
    val actionsMenu = if (actionsHost == null) null else rememberSessionActionsMenuState(actionsHost)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(HEADER_HEIGHT)
            .padding(start = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HeaderIconButton(
            iconRes = R.drawable.ic_oc_chevron_down,
            contentDescription = stringResource(R.string.oc_back),
            iconModifier = Modifier.rotate(90f),
            onClick = onBack,
        )
        // M2 addition: the session drawer entry (phone-first navigation).
        HeaderIconButton(
            iconRes = R.drawable.ic_oc_menu,
            contentDescription = stringResource(R.string.oc_open_sessions),
            onClick = onOpenDrawer,
        )

        if (searching) {
            // The bar replaces the title, the ring and the ⋮ menu: at phone width
            // upstream's 200 px field does not fit next to them.
            TimelineSearchBar(modifier = Modifier.weight(1f))
            return@Row
        }

        if (editing) {
            InlineTitleInput(
                initial = title,
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
                modifier = Modifier
                    .weight(1f)
                    .clip(OcTheme.shapes.md)
                    .clickable(role = Role.Button) { editing = true }
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            )
        }

        // A2 entry: same slot the open bar takes, i.e. in front of the ring
        // (`message-timeline.tsx:544-548`). Upstream reaches the search through
        // `mod+f` only; a touch shell needs a visible button.
        if (canSearch && !editing) TimelineSearchEntry()

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
        )
        SessionOverflowMenu(
            onRename = { editing = true },
            onMenuAction = onMenuAction,
            actionsHost = actionsHost,
            actionsMenu = actionsMenu,
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
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
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
 * Commit contract: Enter (IME "Done") saves, Esc cancels, blur saves. The
 * `focused` flag exists because `onFocusChanged` fires once with
 * `isFocused == false` when the field is first composed, before the request
 * below lands — committing there would instantly close an untouched editor.
 */
@Composable
private fun InlineTitleInput(
    initial: String,
    onCommit: (String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = OcTheme.colors
    var value by remember { mutableStateOf(initial) }
    var focused by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    fun commit() {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) onCancel() else onCommit(trimmed)
    }

    BasicTextField(
        value = value,
        onValueChange = { value = it },
        singleLine = true,
        textStyle = TextStyle(
            color = c.textBase,
            fontSize = 13.sp,
            fontWeight = FontWeight(530),
        ),
        cursorBrush = SolidColor(c.textBase),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { commit() }),
        modifier = modifier
            .padding(horizontal = 6.dp)
            .clip(OcTheme.shapes.md)
            .background(c.layer02)
            .padding(horizontal = 8.dp, vertical = 5.dp)
            .focusRequester(focusRequester)
            .onFocusChanged { state ->
                if (state.isFocused) focused = true else if (focused) commit()
            }
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                    onCancel()
                    true
                } else {
                    false
                }
            },
    )
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboard?.show()
    }
}

/**
 * ⋮ menu (`MenuV2`): fixed **120 px** content width per §2.2, `layer-01`
 * surface, `lg` radius, a 0.5 dp border for definition on the layer background.
 * M2 wires *Rename*; *Delete* runs the call site's session-delete flow (W4-E F-4);
 * *Export…* (A8, W5-INT) serializes the session through the call site.
 *
 * W6 appends the five session-action rows below a divider: 撤销本轮变更 /
 * 重做变更 / 分叉会话 / 分享会话 (取消分享会话 while the session is public) /
 * 归档会话. They are a second group on purpose — the three entries above are
 * `SessionMenuAction`s the *call site* runs (session id + client + confirm
 * dialog), while these five are carried out by [SessionActionsHost], which the
 * shell publishes for the session on screen. Enablement is
 * `SessionActionsModel.menu(...)`'s, i.e. the same projection upstream's command
 * palette uses for `session.undo` / `session.redo` / `session.fork`
 * (`use-session-commands.tsx:261,269,292`); this file decides no rule of its own.
 */
@Composable
private fun SessionOverflowMenu(
    onRename: () -> Unit,
    onMenuAction: (SessionMenuAction) -> Unit,
    actionsHost: SessionActionsHost?,
    actionsMenu: SessionActionsMenuState?,
) {
    val c = OcTheme.colors
    val shapes = OcTheme.shapes
    var expanded by remember { mutableStateOf(false) }
    var anchor by remember { mutableStateOf(IntSize.Zero) }
    val gapPx = with(LocalDensity.current) { MENU_GAP.roundToPx() }

    Box(modifier = Modifier.onSizeChanged { anchor = it }) {
        HeaderIconButton(
            iconRes = R.drawable.ic_oc_dots,
            contentDescription = stringResource(R.string.oc_more_options),
            onClick = { expanded = true },
        )
        if (expanded) {
            // Right-aligned under the anchor, 4 dp below it.
            val provider = remember(anchor, gapPx) {
                object : PopupPositionProvider {
                    override fun calculatePosition(
                        anchorBounds: IntRect,
                        windowSize: IntSize,
                        layoutDirection: LayoutDirection,
                        popupContentSize: IntSize,
                    ): IntOffset = IntOffset(
                        x = anchorBounds.right - popupContentSize.width,
                        y = anchorBounds.bottom + gapPx,
                    )
                }
            }
            Popup(
                popupPositionProvider = provider,
                onDismissRequest = { expanded = false },
                properties = PopupProperties(focusable = true),
            ) {
                // M6: `menu-v2-in` — `.12s ease-out`, `scale(.96) → 1` + opacity,
                // `transform-origin: top center` (`research/03` §3.4). The Popup
                // mounts only while open, so the enter has to be played on mount.
                OcEnterOnMount(overlay = OcMotion.Overlay.MENU) {
                    Column(
                        modifier = Modifier
                            .width(MENU_WIDTH)
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
                        // W6: 撤销 / 重做 / 分叉 / 分享·取消分享 / 归档 — the same five
                        // actions upstream registers as commands
                        // surface them here because Android has no command palette in
                        // the header and the ⋮ menu is where the user looks.
                        MenuRow(
                            label = stringResource(R.string.command_session_export),
                            enabled = true,
                            onClick = { onMenuAction(SessionMenuAction.EXPORT) },
                        )
                        MenuRow(
                            label = stringResource(R.string.oc_delete_session),
                            // W4-E F-4: the destructive flow exists now (the
                            // drawer rows run it), so the header entry stops
                            // being a dead placeholder. Upstream renders Delete
                            // for top-level sessions only; Android has no
                            // `parentID`, so it is always offered.
                            enabled = true,
                            onClick = { onMenuAction(SessionMenuAction.DELETE) },
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
                            MenuRow(
                                label = stringResource(R.string.command_session_redo),
                                enabled = actionsMenu.redoEnabled,
                                onClick = {
                                    expanded = false
                                    actionsHost.start(SessionActionsMenuItem.REDO)
                                },
                            )
                            MenuRow(
                                label = stringResource(R.string.oc_fork_session),
                                enabled = actionsMenu.forkEnabled,
                                onClick = {
                                    expanded = false
                                    actionsHost.start(SessionActionsMenuItem.FORK)
                                },
                            )
                            // One row, two labels: the server keeps reporting
                            // `share` after `DELETE …/share` (measured live), so
                            // "public?" is the local flow the host's view model
                            // maintains, never the payload of the last write.
                            if (actionsMenu.shareVisible) {
                                MenuRow(
                                    label = stringResource(
                                        if (actionsMenu.shared) {
                                            R.string.command_session_unshare
                                        } else {
                                            R.string.command_session_share
                                        },
                                    ),
                                    enabled = true,
                                    onClick = {
                                        expanded = false
                                        actionsHost.start(SessionActionsMenuItem.SHARE)
                                    },
                                )
                            }
                            if (actionsMenu.archiveVisible) {
                                MenuRow(
                                    label = stringResource(R.string.command_session_archive),
                                    enabled = true,
                                    onClick = {
                                        expanded = false
                                        actionsHost.start(SessionActionsMenuItem.ARCHIVE)
                                    },
                                )
                            }
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

@Composable
private fun MenuRow(label: String, enabled: Boolean, onClick: () -> Unit) {
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
            color = if (enabled) c.textBase else c.textMuted,
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

/** `MenuV2` content width, fixed (`style={{width:"120px"}}`, §2.2). */
private val MENU_WIDTH = 120.dp

private val MENU_GAP = 4.dp

private const val DISABLED_ALPHA = 0.5f
