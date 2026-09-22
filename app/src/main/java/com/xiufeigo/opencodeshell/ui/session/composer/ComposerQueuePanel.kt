package com.xiufeigo.opencodeshell.ui.session.composer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.ui.theme.OcTheme

/**
 * WAVE 2.4b (P0-7): the queued-prompts panel above the composer.
 *
 * Upstream (`session/composer/queue-panel.tsx:18-83`): a pullout listing the
 * prompts queued behind the current turn — `rounded-xl bg-base px-1.5 pt-1.5`
 * with an inset hairline, `-mb-3` overlap into the composer so the two read as
 * one attached surface, a `N queued` header past 3 rows, and at most ~4.5 rows
 * visible (`max-h-[148px]` scroll with an edge fade). Each row
 * (`queue-panel.tsx:85-196`): 32 dp, drag handle, truncating display text (+
 * `N attachments` when both), hover actions **Steer** (working) / **Send**
 * (parked) + remove ✕; the parked first row keeps its actions visible and the
 * editing row highlights.
 *
 * Native shape: no drag-reorder gesture (touch list keeps tap actions; reorder
 * stays a pure-model op — [ComposerQueue.Model.reorder] — for the VM to drive
 * if ever needed), rows tap-to-edit. All strings are generated v2 i18n keys
 * (`strings_oc_i18n.xml` default bundle; runtime fallback covers zh).
 */
@Composable
fun ComposerQueuePanel(
    rows: List<ComposerQueue.Row>,
    running: Boolean,
    busy: Boolean,
    editingId: String?,
    onSteer: (String) -> Unit,
    onRemove: (String) -> Unit,
    onEdit: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (rows.isEmpty()) return
    val c = OcTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(OcTheme.shapes.lg)
            .background(c.layer01)
            .padding(start = 6.dp, end = 6.dp, top = 6.dp, bottom = PANEL_OVERLAP),
    ) {
        if (ComposerQueue.showCount(rows.size)) {
            Text(
                text = stringResource(
                    if (rows.size == 1) R.string.session_queue_count_one else R.string.session_queue_count_other,
                    rows.size,
                ),
                color = c.textMuted,
                fontSize = 11.sp,
                modifier = Modifier.padding(start = 6.dp, end = 6.dp, top = 4.dp),
            )
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = PANEL_MAX_HEIGHT),
        ) {
            itemsIndexed(
                items = rows,
                key = { _, row -> row.id },
            ) { index, row ->
                QueueRow(
                    row = row,
                    parkedFirst = ComposerQueue.isParkedFirst(running, index),
                    running = running,
                    busy = busy,
                    editing = editingId == row.id,
                    onSteer = { onSteer(row.id) },
                    onRemove = { onRemove(row.id) },
                    onEdit = { onEdit(row.id) },
                )
            }
        }
    }
}

@Composable
private fun QueueRow(
    row: ComposerQueue.Row,
    parkedFirst: Boolean,
    running: Boolean,
    busy: Boolean,
    editing: Boolean,
    onSteer: () -> Unit,
    onRemove: () -> Unit,
    onEdit: () -> Unit,
) {
    val c = OcTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            .clip(OcTheme.shapes.md)
            .background(if (editing) c.overlayHover else c.layer01)
            .padding(start = 4.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Display text: tap loads the prompt back into the composer for edit
        // (`queue.ts:147-171` `edit`).
        Row(
            modifier = Modifier
                .weight(1f)
                .clip(OcTheme.shapes.sm)
                .clickable(enabled = !busy, role = Role.Button, onClick = onEdit)
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = row.text.ifBlank {
                    row.attachmentCount.takeIf { it > 0 }?.let { QueueAttachmentsText.fallback(it) }.orEmpty()
                },
                color = if (editing) c.textFaint else c.textBase,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (row.text.isNotBlank() && row.attachmentCount > 0) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = queuedAttachmentsLabel(row.attachmentCount),
                    color = c.textMuted,
                    fontSize = 13.sp,
                    maxLines = 1,
                )
            }
        }
        // Actions: parked first row always shows them (`queue-panel.tsx:89-92`
        // `active()`); otherwise they are always visible here — a touch screen
        // has no hover (`[@media(hover:none)]:opacity-100` upstream).
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(if (running) R.string.session_queue_steer else R.string.session_queue_send),
            color = c.accent,
            fontSize = 12.sp,
            modifier = Modifier
                .clip(OcTheme.shapes.sm)
                .clickable(enabled = !busy, role = Role.Button, onClick = onSteer)
                .padding(horizontal = 6.dp, vertical = 4.dp),
        )
        Spacer(Modifier.width(4.dp))
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(OcTheme.shapes.sm)
                .clickable(enabled = !busy, role = Role.Button, onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_oc_close),
                contentDescription = stringResource(R.string.session_queue_remove),
                tint = c.iconMuted,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

@Composable
private fun queuedAttachmentsLabel(count: Int): String = stringResource(
    if (count == 1) R.string.session_queue_attachments_one else R.string.session_queue_attachments_other,
    count,
)

/** `max-h-[148px]` — four 32 px rows + gaps, with a hint of the fifth. */
private val PANEL_MAX_HEIGHT = 148.dp

/**
 * The `-mb-3` overlap: the panel slides under the composer card so the two
 * read as one attached surface (`queue-panel.tsx:18-28`).
 */
private val PANEL_OVERLAP = 12.dp

/** `h-8` rows. */
private val ROW_HEIGHT = 32.dp
