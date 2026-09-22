package com.xiufeigo.opencodeshell.ui.session.actions

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.ui.theme.OcTheme
import com.xiufeigo.opencodeshell.ui.theme.ocShadow
import java.util.Date

/**
 * The three surfaces the ⋮ menu's session rows open (W6): the fork picker, the
 * share panel and the archive confirmation.
 *
 * Mounted once per session screen by `ui/App.kt` (a full-size sibling of
 * `SessionScreen`), because a dialog that must cover the whole window cannot be
 * laid out inside the header's 48 dp row. It renders nothing while
 * [SessionActionsHost.dialog] is null.
 *
 * All three are in-tree overlays on `overlayScrim` rather than Material
 * `AlertDialog`s, the same choice `SessionRowDialogs` records for the drawer: the
 * card keeps the OC surface tokens (`bgBase`, 0.5 dp `borderBase`,
 * `elevation.floating`).
 */
@Composable
fun SessionActionsOverlay(host: SessionActionsHost, modifier: Modifier = Modifier) {
    when (host.dialog) {
        null -> Unit
        SessionActionsDialog.FORK -> ForkSurface(host, modifier)
        SessionActionsDialog.SHARE -> ShareSurface(host, modifier)
        SessionActionsDialog.ARCHIVE -> ArchiveSurface(host, modifier)
    }
}

/**
 * `fork-dialog.tsx:60-95`: title `command.session.fork`, one row per forkable
 * user message (text left, time right), `dialog.fork.empty` when there is none,
 * and picking a row forks **before** it.
 *
 * Deviations from upstream, both deliberate and small: the rows are a snapshot
 * taken when the row was tapped (the picker is modal, so nothing can arrive
 * under it), and the list has no search field — upstream's `List search` has no
 * Android equivalent here (`SessionActionsModel.forkMessages` caps the text the
 * same way upstream does).
 */
@Composable
private fun ForkSurface(host: SessionActionsHost, modifier: Modifier = Modifier) {
    val c = OcTheme.colors
    val context = LocalContext.current
    val timeFormat = remember(context) { DateFormat.getTimeFormat(context) }
    ModalCard(modifier = modifier, onDismiss = host::dismiss) {
        Text(
            text = stringResource(R.string.command_session_fork),
            color = c.textBase,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            lineHeight = 21.sp,
        )
        if (host.forkRows.isEmpty()) {
            Text(
                text = stringResource(R.string.dialog_fork_empty),
                color = c.textMuted,
                fontSize = 13.sp,
                lineHeight = 20.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = FORK_LIST_MAX_HEIGHT)) {
                items(host.forkRows, key = { it.id }) { row ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(OcTheme.shapes.md)
                            .clickable(role = Role.Button) { host.pickForkPoint(row.id) }
                            .padding(horizontal = 6.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = row.text,
                            color = c.textBase,
                            fontSize = 13.sp,
                            lineHeight = 20.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            // `formatTime(new Date(message.time.created))`.
                            text = timeFormat.format(Date(row.timeCreated)),
                            color = c.textMuted,
                            fontSize = 13.sp,
                            lineHeight = 20.sp,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
        ModalButtons {
            ModalButton(label = stringResource(R.string.oc_common_cancel), onClick = host::dismiss)
        }
    }
}

/**
 * 分享会话's confirmation (`session.share.popover.*`): what the session's public
 * link is, that it is public, and the three things left to do with it —
 * 复制链接 (`session.share.copy.copyLink`), 取消发布 and close.
 *
 * The share itself has already happened when this surface appears: the row
 * publishes, copies and toasts ([SessionActionsHost.share]), and this is the
 * receipt. Its 复制链接 re-copies, which is also what 取消发布's sibling does
 * upstream — the link is the one thing the user came for.
 */
@Composable
private fun ShareSurface(host: SessionActionsHost, modifier: Modifier = Modifier) {
    val c = OcTheme.colors
    val url by host.viewModel.shareUrl.collectAsState()
    ModalCard(modifier = modifier, onDismiss = host::dismiss) {
        Text(
            text = stringResource(R.string.session_share_popover_title),
            color = c.textBase,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            lineHeight = 21.sp,
        )
        Text(
            text = stringResource(R.string.session_share_popover_description_shared),
            color = c.textMuted,
            fontSize = 13.sp,
            lineHeight = 20.sp,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            text = url.orEmpty(),
            color = c.textAccent,
            fontSize = 12.sp,
            lineHeight = 20.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        ModalButtons {
            ModalButton(label = stringResource(R.string.oc_common_close), onClick = host::dismiss)
            Spacer(Modifier.width(8.dp))
            ModalButton(
                label = stringResource(R.string.session_share_copy_copyLink),
                enabled = url != null,
                onClick = { host.copyShareLink(url.orEmpty()) },
            )
            Spacer(Modifier.width(8.dp))
            ModalButton(
                label = stringResource(R.string.session_share_action_unpublish),
                enabled = !host.busy,
                onClick = host::unshare,
            )
        }
    }
}

/**
 * 归档会话. Upstream archives from the command palette with no confirmation
 * (`use-session-commands.tsx:239-246` region); a phone ⋮ menu hit by accident is
 * not a palette invocation, so the row confirms first — the one Android-only
 * addition of this surface (`oc_archive_session_message`).
 */
@Composable
private fun ArchiveSurface(host: SessionActionsHost, modifier: Modifier = Modifier) {
    val c = OcTheme.colors
    ModalCard(modifier = modifier, onDismiss = host::dismiss) {
        Text(
            text = stringResource(R.string.command_session_archive),
            color = c.textBase,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            lineHeight = 21.sp,
        )
        Text(
            text = stringResource(R.string.oc_archive_session_message),
            color = c.textMuted,
            fontSize = 13.sp,
            lineHeight = 20.sp,
            modifier = Modifier.padding(top = 8.dp),
        )
        ModalButtons {
            ModalButton(label = stringResource(R.string.oc_common_cancel), onClick = host::dismiss)
            Spacer(Modifier.width(8.dp))
            ModalButton(
                label = stringResource(R.string.common_archive),
                enabled = !host.busy,
                onClick = host::archive,
            )
        }
    }
}

/**
 * Centered card on a tap-through-blocking scrim (drawer dialogs' geometry).
 */
@Composable
private fun ModalCard(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = OcTheme.colors
    val shape = OcTheme.shapes.xl
    Box(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(c.overlayScrim)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
        )
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 32.dp)
                .fillMaxWidth()
                .widthIn(max = 320.dp)
                .clip(shape)
                .background(c.bgBase)
                .border(0.5.dp, c.borderBase, shape)
                .ocShadow(OcTheme.elevation.floating, shape)
                .padding(16.dp),
            content = content,
        )
    }
}

/** Trailing button row of a card. */
@Composable
private fun ModalButtons(content: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content()
    }
}

/** `button-v2` metrics of the dock trays: h 28, radius 6, padding-x 12, 13/530. */
@Composable
private fun ModalButton(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    danger: Boolean = false,
) {
    val c = OcTheme.colors
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier = Modifier
            .alpha(if (enabled) 1f else 0.5f)
            .height(28.dp)
            .clip(shape)
            .background(if (danger) c.stateDangerFg else Color.Transparent)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (danger) c.textInverse else c.textBase,
            fontSize = 13.sp,
            fontWeight = FontWeight(530),
            lineHeight = 20.sp,
        )
    }
}

/** Six rows of the fork picker fit a phone without scrolling; the rest scroll. */
private val FORK_LIST_MAX_HEIGHT = 320.dp
