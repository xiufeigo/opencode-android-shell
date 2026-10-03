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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.ui.session.SessionPaneLayout
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
 * Deviation (device v11): every row also carries a leading muted **queued**
 * label (`ui.message.queued`). Upstream relies on the panel header alone —
 * which only appears past 3 rows — so a single queued prompt had no visible
 * queue wording at all on the phone; the label matches the timeline's parked
 * steer row instead.
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
    /**
     * v99 P0 「队列条把时间线压到 16px 且 100% 覆盖」: the bar's ceiling, passed
     * in by the pane instead of being the constant [QUEUE_PANEL_MAX_HEIGHT].
     *
     * The bar is the **first** child of the composer's own `Column`
     * (`Composer`'s strip), so in a height-constrained host it is measured
     * before the card: a constant 148 dp ceiling meant it always took its full
     * intrinsic height, and the pane — which charges the whole host to the
     * timeline, `SessionPaneLayout.viewportHeightPx` — paid for it out of the
     * message band. Measured on emulator-5562 at 1366x768 the timeline went
     * 188 px → **16 px**, all of it inside the bar's opaque rectangle
     * (`research/v97_verify/5562_v98/REPORT_SWEEPD3.md` §2).
     *
     * `SessionPaneLayout.queueBarCeiling` now derives this from the pane, paying
     * the timeline's floor and the composer's tool row *first*, and it is handed
     * over only when the bar can be drawn at least
     * [SessionPaneLayout.QUEUE_BAR_MIN_HEIGHT] tall.
     *
     * Past the ceiling the bar does not grow, it **scrolls** — it is already a
     * `LazyColumn` — and the viewport keeps a whole row of height
     * ([SessionPaneLayout.queueBarViewportFloor], applied below) so that scrolling
     * is what hides rows. A bar drawn shorter than one row is not a smaller bar:
     * it is a cropped one, and the actions inside the cropped row are laid out
     * outside the viewport and never reach the accessibility tree. The default
     * keeps every other caller (and the existing pins) on the upstream 148 px.
     */
    maxHeight: Dp = SessionPaneLayout.QUEUE_BAR_MAX_HEIGHT,
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
                // v99-b: the viewport's **floor** is one whole row, so the cap
                // above is spent on hiding rows, never on squashing them. This
                // is the difference between「11 or 23 rows, the bar scrolls」and
                // the queue-actions regression REPORT_U1 §3 判据四 measured: a
                // viewport shorter than a row clips the row, and a clipped row's
                // `Steer` / **×** are laid out outside the viewport, so they never
                // enter the accessibility tree at all. `maxHeight` still wins
                // (a `heightIn` pair coerces min down to max), which is why the
                // pane budget can never be overflowed by this floor.
                .heightIn(min = SessionPaneLayout.queueBarViewportFloor(rows.size))
                .heightIn(max = maxHeight),
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
        // (`queue.ts:147-171` `edit`). The queued label leads the same tap
        // target, so the whole row reads as one affordance.
        Row(
            modifier = Modifier
                .weight(1f)
                .clip(OcTheme.shapes.sm)
                .clickable(enabled = !busy, role = Role.Button, onClick = onEdit)
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // WAVE 2.4d (device v11): the row's own queued label. The panel
            // header only appears past three rows ([ComposerQueue.showCount]),
            // so a single queued prompt used to read as an ordinary message that
            // had already been sent — the device report had no queue wording
            // anywhere on screen. `ui.message.queued` is the same key the
            // timeline's parked steer row (`PendingSteerRow`) labels itself
            // with, so the two surfaces that hold an undelivered prompt agree on
            // the wording; they never render the same item (queue vs steer
            // delivery).
            Text(
                text = stringResource(R.string.ui_message_queued),
                color = c.textMuted,
                fontSize = 11.sp,
                maxLines = 1,
                modifier = Modifier.padding(end = 8.dp),
            )
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
        // Actions: the ordered set is [ComposerQueue.rowActions] — upstream's
        // `session-queue-actions` children in JSX order (`queue-panel.tsx:150-191`),
        // the steer/send button then the remove ✕. It is read from the model
        // rather than re-derived here so the action set stays assertable on the
        // JVM ([ComposerQueue.RowActions]) instead of being a property of this
        // composable alone.
        //
        // Visibility: upstream gates the container on hover
        // (`opacity-0 … group-hover/queue-row:opacity-100`, `queue-panel.tsx:154`)
        // but ends that same class list with `[@media(hover:none)]:opacity-100`
        // — a touch screen has no hover, so upstream's own touch rendering keeps
        // every row's actions visible, and only `busy()` (`pointer-events-none`,
        // line 156) turns them inert. The parked-first row's always-on
        // `active()` (line 92) is the same intent on a pointer device; the
        // [parkedFirst] flag is carried for that parity and does not gate
        // anything here, because nothing on a touch list can hover.
        val rowActions = ComposerQueue.rowActions(running = running, editing = editing)
        Spacer(Modifier.width(8.dp))
        rowActions.actions.forEachIndexed { position, action ->
            if (position > 0) Spacer(Modifier.width(4.dp))
            when (action) {
                ComposerQueue.RowAction.STEER -> {
                    Text(
                        text = stringResource(
                            when (rowActions.steerLabel) {
                                ComposerQueue.SteerLabel.STEER -> R.string.session_queue_steer
                                ComposerQueue.SteerLabel.SEND -> R.string.session_queue_send
                            },
                        ),
                        color = c.accent,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .clip(OcTheme.shapes.sm)
                            .clickable(enabled = !busy, role = Role.Button, onClick = onSteer)
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                    )
                }
                ComposerQueue.RowAction.REMOVE -> {
                    // Hoisted: `semantics {}` is not a composable scope. The label
                    // rides the clickable Box, not the `Icon` inside it.
                    val removeLabel = stringResource(R.string.session_queue_remove)
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(OcTheme.shapes.sm)
                            .clickable(enabled = !busy, role = Role.Button, onClick = onRemove)
                            .semantics { contentDescription = removeLabel },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_oc_close),
                            contentDescription = null,
                            tint = c.iconMuted,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun queuedAttachmentsLabel(count: Int): String = stringResource(
    if (count == 1) R.string.session_queue_attachments_one else R.string.session_queue_attachments_other,
    count,
)

/**
 * The `-mb-3` overlap: the panel slides under the composer card so the two
 * read as one attached surface (`queue-panel.tsx:18-28`). Counted in
 * [SessionPaneLayout.QUEUE_BAR_CHROME], the bar's own non-row height.
 */
private val PANEL_OVERLAP = 12.dp

/** `h-8` rows. */
private val ROW_HEIGHT = SessionPaneLayout.QUEUE_ROW_HEIGHT
