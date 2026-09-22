package com.xiufeigo.opencodeshell.ui.session.header

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.ui.session.timeline.TimelineSearch
import com.xiufeigo.opencodeshell.ui.theme.OcTheme

/**
 * A2 — the search entry and the search bar, both living in the session header
 * exactly where upstream mounts `TimelineSearchBar`:
 * `_upstream/packages/app/src/session/timeline/message-timeline.tsx:544-548`
 * renders `props.search` as the **first** child of the header's trailing cluster
 * (`shrink-0 flex items-center gap-2`), i.e. in front of the context ring. The
 * bar itself (`app/src/session/timeline/search-bar.tsx`) is:
 *
 * ```
 * Show when (controller.visible())                        :11
 *   div.h-7.w-[200px].max-w-[50vw]                        :12
 *     text-input-v2 (28px tall, leading magnifier icon)   :13-14
 *     input[type=search]                                  :18-49
 *     span "{active+1}/{count}"  (11px, muted, tabular)    :51-58
 *     button xmark-small        → controller.close()      :59-67
 * ```
 *
 * Key contract (`search-bar.tsx:26-48`): Esc closes, Enter / ArrowDown move
 * forward, Shift+Enter / ArrowUp move back. Turning those into header icon
 * buttons is the one Android addition (a phone has no Enter key); the
 * zero-hit state uses the controller's own `session.search.noResults` string
 * (`search-controller.ts:259`), which the upstream bar leaves unused.
 *
 * Note that upstream has **no** search icon at all — the bar opens through the
 * `session.search` command (`mod+f`, `search-controller.ts:184-192`). A touch
 * shell needs a visible entry, so [TimelineSearchEntry] is that entry, placed in
 * the same trailing cluster the bar occupies once open.
 */
@Composable
internal fun TimelineSearchEntry(
    onClick: () -> Unit = { TimelineSearch.open() },
    modifier: Modifier = Modifier,
) {
    val c = OcTheme.colors
    val label = stringResource(R.string.oc_search_open)
    Box(
        modifier = modifier
            .size(ENTRY_SIZE)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_oc_search),
            contentDescription = null,
            tint = c.iconBase,
            modifier = Modifier.size(16.dp),
        )
    }
}

/**
 * The open bar. Rendered by `SessionHeader` **in place of the title**: a phone
 * width cannot host upstream's `w-[200px] max-w-[50vw]` next to the back button,
 * the drawer button, the ring and the ⋮ menu, so while searching the row keeps
 * only the navigation buttons and gives the bar the rest. The `h-7` height, the
 * leading magnifier, the trailing `active/count`, and the Esc / Enter /
 * Shift+Enter / ↑ / ↓ contract are upstream's.
 */
@Composable
internal fun TimelineSearchBar(
    onClose: () -> Unit = { TimelineSearch.close() },
    modifier: Modifier = Modifier,
) {
    val c = OcTheme.colors
    val query = TimelineSearch.query
    val count = TimelineSearch.count
    val active = TimelineSearch.active
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val placeholder = stringResource(R.string.oc_search_placeholder)

    // `focusTick` effect (`search-controller.ts:174-182`): opening the bar focuses
    // and selects the field.
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboard?.show()
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(BAR_HEIGHT)
            .clip(OcTheme.shapes.md)
            .background(c.layer02)
            .border(0.5.dp, c.borderBase, OcTheme.shapes.md)
            .padding(start = 8.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_oc_search),
            contentDescription = null,
            tint = c.iconMuted,
            modifier = Modifier.size(16.dp),
        )
        BasicTextField(
            value = query,
            onValueChange = { TimelineSearch.setQuery(it) },
            singleLine = true,
            textStyle = TextStyle(color = c.textBase, fontSize = 13.sp),
            cursorBrush = SolidColor(c.textBase),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            // The IME "search" action is the touch equivalent of Enter (`:33-37`).
            keyboardActions = KeyboardActions(onSearch = { TimelineSearch.moveBy(1) }),
            decorationBox = { inner ->
                if (query.isEmpty()) {
                    Text(
                        text = placeholder,
                        color = c.textFaint,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                inner()
            },
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 6.dp)
                .focusRequester(focusRequester)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.Escape -> {
                            onClose()
                            true
                        }

                        Key.Enter, Key.NumPadEnter -> {
                            TimelineSearch.moveBy(if (event.isShiftPressed) -1 else 1)
                            true
                        }

                        Key.DirectionDown -> {
                            TimelineSearch.moveBy(1)
                            true
                        }

                        Key.DirectionUp -> {
                            TimelineSearch.moveBy(-1)
                            true
                        }

                        else -> false
                    }
                },
        )
        if (query.isNotEmpty()) {
            // `{open ? active + 1 : 0}/{count}` (`search-bar.tsx:56`), with the
            // controller's `noResults` copy for the empty case.
            Text(
                text = if (count > 0) {
                    "${active + 1}/$count"
                } else {
                    stringResource(R.string.oc_search_no_results)
                },
                color = c.textMuted,
                fontSize = 11.sp,
                fontWeight = FontWeight(440),
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
        if (count > 0) {
            BarIconButton(
                iconRes = R.drawable.ic_oc_chevron_down,
                label = stringResource(R.string.oc_search_previous),
                rotateDegrees = 180f,
                onClick = { TimelineSearch.moveBy(-1) },
            )
            BarIconButton(
                iconRes = R.drawable.ic_oc_chevron_down,
                label = stringResource(R.string.oc_search_next),
                rotateDegrees = 0f,
                onClick = { TimelineSearch.moveBy(1) },
            )
        }
        BarIconButton(
            iconRes = R.drawable.ic_oc_close,
            label = stringResource(R.string.oc_search_close),
            rotateDegrees = 0f,
            onClick = onClose,
        )
    }
}

@Composable
private fun BarIconButton(
    iconRes: Int,
    label: String,
    rotateDegrees: Float,
    onClick: () -> Unit,
) {
    val c = OcTheme.colors
    Box(
        modifier = Modifier
            .size(BAR_ICON_SIZE)
            .clip(OcTheme.shapes.sm)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = c.iconMuted,
            modifier = Modifier.size(14.dp).rotate(rotateDegrees),
        )
    }
}

/** 40 dp circular target — the header's own icon-button metric. */
private val ENTRY_SIZE = 40.dp

/** `h-7`. */
private val BAR_HEIGHT = 28.dp

/** `size-5` of the trailing buttons. */
private val BAR_ICON_SIZE = 24.dp
