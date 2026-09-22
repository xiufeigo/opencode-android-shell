package com.xiufeigo.opencodeshell.ui.session.header

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
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
import java.text.NumberFormat
import java.util.Locale

/**
 * The context-usage ring button of the session header (W4-B item 8) — upstream
 * `SessionContextUsage` (`_upstream/packages/app/src/session/timeline/
 * session-context-usage.tsx`):
 *
 * - the ring is the `compact` geometry (14 px / 1.5 stroke, `:125-129`) fed by
 *   `round(total / model.limit.context * 100)` (`:68-82`) — the whole point of
 *   the item is that it must **not** be an always-empty track;
 * - clicking opens the Review→Context panel (`:93-108`);
 * - the Cost / Context / Tokens triple lives in the tooltip (`:131-155`,
 *   `w-[120px] gap-2`, muted name left, base value right).
 *
 * Touch mapping: a **tap** calls [onOpenContext] when the call site provides it,
 * and otherwise opens the popover (there is no hover on a phone); a **long
 * press** opens the popover in both cases. A ring with no denominator
 * ([percent] `null`) still renders the empty track — the documented "data
 * missing ≠ fake value" state (`ContextUsage.percentOf`).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ContextUsageButton(
    percent: Int?,
    tokens: Long?,
    cost: Double?,
    fallbackProgress: Float,
    onOpenContext: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val c = OcTheme.colors
    val shapes = OcTheme.shapes
    var open by remember { mutableStateOf(false) }
    var anchor by remember { mutableStateOf(IntSize.Zero) }
    val gapPx = with(LocalDensity.current) { POPOVER_GAP.roundToPx() }
    val label = stringResource(R.string.oc_context_usage)
    val progress = percent?.let { it.coerceIn(0, 100) / 100f } ?: fallbackProgress

    Box(modifier = modifier.onSizeChanged { anchor = it }) {
        Box(
            modifier = Modifier
                .size(CONTEXT_BUTTON_SIZE)
                .clip(shapes.md)
                .combinedClickable(
                    onClick = { if (onOpenContext != null) onOpenContext() else open = !open },
                    onLongClick = { open = !open },
                )
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            ContextRing(progress = progress)
        }
        if (open) {
            // Right-aligned under the anchor, 4 dp below it (same placement the
            // ⋮ menu uses, mirroring the upstream `placement="top"` tooltip).
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
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true),
            ) {
                OcEnterOnMount(overlay = OcMotion.Overlay.MENU) {
                    ContextUsagePopover(percent = percent, tokens = tokens, cost = cost)
                }
            }
        }
    }
}

/**
 * The upstream tooltip body (`session-context-usage.tsx:131-140`): 120 px wide,
 * 8 px gaps, one row per metric. Rows without data are omitted rather than
 * printed as `$0.00` — the documented "缺数据不显示" rule of W4-B.
 */
@Composable
private fun ContextUsagePopover(percent: Int?, tokens: Long?, cost: Double?) {
    val c = OcTheme.colors
    val shapes = OcTheme.shapes
    val costText = cost?.let { currencyFormat().format(it) }
    val tokenText = tokens?.let { integerFormat().format(it) }
    Column(
        modifier = Modifier
            .width(POPOVER_WIDTH)
            .clip(shapes.lg)
            .background(c.layer01)
            .border(0.5.dp, c.borderBase, shapes.lg)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (costText != null) {
            UsageRow(stringResource(R.string.oc_meta_context_cost), costText)
        }
        UsageRow(stringResource(R.string.oc_meta_context_usage), "${percent ?: 0}%")
        UsageRow(stringResource(R.string.oc_meta_context_tokens), tokenText ?: "0")
    }
}

/** `flex items-center gap-4`: muted name left, base value right (`:21-28`). */
@Composable
private fun UsageRow(name: String, value: String) {
    val c = OcTheme.colors
    Row(modifier = Modifier.width(POPOVER_WIDTH), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = name,
            color = c.textMuted,
            fontSize = 12.sp,
            maxLines = 1,
        )
        Text(
            text = value,
            color = c.textBase,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}

/** `Intl.NumberFormat(locale, {style:"currency", currency:"USD"})`. */
@Composable
private fun currencyFormat(): NumberFormat = remember {
    NumberFormat.getCurrencyInstance(Locale.getDefault())
}

/** `toLocaleString(locale)` for the token count. */
@Composable
private fun integerFormat(): NumberFormat = remember { NumberFormat.getIntegerInstance() }

/** Touch target around the 14 dp ring (the header's 40 dp button box). */
private val CONTEXT_BUTTON_SIZE = 40.dp

/** `w-[120px]` of the tooltip body. */
private val POPOVER_WIDTH = 120.dp

private val POPOVER_GAP = 4.dp
