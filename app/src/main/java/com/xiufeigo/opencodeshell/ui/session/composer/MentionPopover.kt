package com.xiufeigo.opencodeshell.ui.session.composer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.ui.theme.OcTheme

/**
 * `@`-mention completion popover (`PromptInputV2Popover`, `research/02` §4.7):
 * a `layer01` card with radius 12 and `elevation-raised`, capped at 320 dp with
 * its own scroll, rendered directly on the editor's top edge.
 *
 * The View shell used a `PopupWindow` pinned to the window bottom; here the card
 * is part of the composer layout — the composer is bottom-aligned already, so
 * the popover sits on the editor's upper edge with or without the IME, and grows
 * the composer instead of covering the timeline.
 *
 * Not implemented vs. the spec: per-row keybind column and the empty state
 * ("No matching items") — the candidates are dismissed while empty, exactly like
 * `ChatView.showMention`.
 */
@Composable
internal fun MentionPopover(
    candidates: List<MentionText.Candidate>,
    selected: Int,
    onPick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = OcTheme.colors
    val shape = RoundedCornerShape(12.dp)
    val elevation = OcTheme.elevation.raised
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = POPOVER_GAP)
            .shadow(
                elevation = elevation.blur.dp,
                shape = shape,
                ambientColor = elevation.color,
                spotColor = elevation.color,
            )
            .clip(shape)
            .background(c.layer01)
            .heightIn(max = POPOVER_MAX_HEIGHT)
            .padding(POPOVER_PADDING),
    ) {
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            itemsIndexed(items = candidates, key = { _, candidate -> candidate.label }) { index, candidate ->
                MentionRow(
                    candidate = candidate,
                    active = index == selected,
                    onClick = { onPick(index) },
                )
            }
        }
    }
}

/** `button[data-suggestion-id]`: icon + label + muted description, 6 dp radius rows. */
@Composable
private fun MentionRow(
    candidate: MentionText.Candidate,
    active: Boolean,
    onClick: () -> Unit,
) {
    val c = OcTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            .clip(OcTheme.shapes.md)
            .background(if (active) c.overlayHover else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(mentionIconRes(candidate.kind)),
            contentDescription = null,
            tint = c.iconMuted,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = candidate.label,
            color = c.textBase,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        candidate.description?.takeIf { it.isNotEmpty() }?.let { description ->
            Spacer(Modifier.width(8.dp))
            Text(
                text = description,
                color = c.textMuted,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** `agent` → brain-ish accent icon, everything else → the file icon (spec §4.7). */
private fun mentionIconRes(kind: MentionText.Kind): Int = when (kind) {
    MentionText.Kind.AGENT -> R.drawable.ic_oc_task
    else -> R.drawable.ic_oc_file
}

/** `max-h-80`. */
private val POPOVER_MAX_HEIGHT = 320.dp

/** `p-2`. */
private val POPOVER_PADDING = 8.dp

/** `-top-2` — 8 dp between the popover's bottom edge and the editor. */
private val POPOVER_GAP = 8.dp

/** `px-2 py-1` row with a comfortable touch target. */
private val ROW_HEIGHT = 32.dp
