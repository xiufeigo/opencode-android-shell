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
import com.xiufeigo.opencodeshell.ui.theme.OcMotion
import com.xiufeigo.opencodeshell.ui.theme.OcTheme

/** Actions of the ⋮ menu. Only [RENAME] is wired in M2 (see the status note). */
enum class SessionMenuAction { RENAME, DELETE }

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
 *     └─ MenuV2 (outline-dots)         ⋮  (Rename / Delete…)
 * ```
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
    contextProgress: Float,
    onMenuAction: (SessionMenuAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = OcTheme.colors
    var editing by remember { mutableStateOf(false) }

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

        // `variant="indicator"` ring. Its upstream click opens the Review/Context
        // panel (`openContext()`, M4 scope) — until that panel exists the ring is
        // a non-interactive indicator rather than a dead button.
        val contextUsageLabel = stringResource(R.string.oc_context_usage)
        Box(
            modifier = Modifier
                .size(ICON_BUTTON_SIZE)
                .semantics { contentDescription = contextUsageLabel },
            contentAlignment = Alignment.Center,
        ) {
            ContextRing(progress = contextProgress)
        }
        SessionOverflowMenu(
            onRename = { editing = true },
            onMenuAction = onMenuAction,
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
            tint = c.iconBase,
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
 * M2 wires *Rename*; *Delete* (and upstream's share/export/archive entries)
 * need milestone work beyond the session screen, so they render as disabled
 * placeholders (`research/_M2_session_status.md` §未做).
 */
@Composable
private fun SessionOverflowMenu(
    onRename: () -> Unit,
    onMenuAction: (SessionMenuAction) -> Unit,
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
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp)
                                .height(0.5.dp)
                                .background(c.borderBase),
                        )
                        MenuRow(
                            label = stringResource(R.string.oc_delete_session),
                            enabled = false,
                            onClick = { onMenuAction(SessionMenuAction.DELETE) },
                        )
                    }
                }
            }
        }
    }
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

/** `h-12` row (§2.2). Also the timeline's sticky top inset. */
val HEADER_HEIGHT = 48.dp

/** `IconButtonV2 size="large"` (§2.2) — the touch target around the 16 dp icons. */
private val ICON_BUTTON_SIZE = 40.dp

/** `MenuV2` content width, fixed (`style={{width:"120px"}}`, §2.2). */
private val MENU_WIDTH = 120.dp

private val MENU_GAP = 4.dp

private const val DISABLED_ALPHA = 0.5f
