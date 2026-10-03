package com.xiufeigo.opencodeshell.ui.session.actions

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.xiufeigo.opencodeshell.ui.home.DirectoryPickerDialog
import com.xiufeigo.opencodeshell.ui.overlay.DialogScrim
import com.xiufeigo.opencodeshell.ui.overlay.dialogCardBounds
import com.xiufeigo.opencodeshell.ui.overlay.rememberDialogScrimState
import com.xiufeigo.opencodeshell.ui.theme.OcTheme
import com.xiufeigo.opencodeshell.ui.theme.ocShadow
import java.util.Date

/**
 * The surfaces the ⋮ menu's fork / move rows open (W6 + F-4).
 *
 * Mounted once per session screen by `ui/App.kt` (a full-size sibling of
 * `SessionScreen`), because a dialog that must cover the whole window cannot be
 * laid out inside the header's 48 dp row. It renders nothing while
 * [SessionActionsHost.dialog] is null.
 *
 * v2-only dropped the share panel and the archive confirmation with their rows.
 *
 * The cards are in-tree overlays on `overlayScrim` rather than Material
 * `AlertDialog`s, the same choice `SessionRowDialogs` records for the drawer: the
 * card keeps the OC surface tokens (`bgBase`, 0.5 dp `borderBase`,
 * `elevation.floating`).
 */
@Composable
fun SessionActionsOverlay(host: SessionActionsHost, modifier: Modifier = Modifier) {
    when (host.dialog) {
        null -> Unit
        SessionActionsDialog.FORK -> ForkSurface(host, modifier)
        SessionActionsDialog.MOVE -> MoveSurface(host, modifier)
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
 * F-4 移动到… surface: the shared `DirectoryPickerDialog` (`ui.home`,
 * read-only reuse), starting at the session's server-confirmed directory.
 * Upstream moves from the workspace menu (`session-workspace-menu.tsx:59-87`),
 * which Android does not render — the ⋮ group opens the same destination
 * choice here, and the pick submits straight into `host.confirmMove`
 * (`POST /api/session/{id}/move`).
 *
 * The picker is a bare card (no scrim of its own — `DialogHost` normally
 * supplies the frame), so this surface wraps it in the same scrim + centering
 * as [ModalCard] without doubling the card chrome. `onOpenServers` stays null:
 * an auth-walled server cannot move a session either, and the picker's own
 * retry row already covers recovery.
 *
 * The session directory goes in raw, and the picker's entry guard
 * (`pickerEntryStart`) is what keeps a Windows system start — the server's
 * launch cwd, `C:\Windows\System32` on a Windows host — from parking the
 * browser there; this call site is the one that made the guard necessary.
 *
 * Which directory that is, is [movePickerStart]'s rule: the session's own
 * `location.directory`, never the project's. v40 measured 移动到… opening on the
 * project directory for a session living in `…/frontend` (`tA/52_moveto.png`).
 */
@Composable
private fun MoveSurface(host: SessionActionsHost, modifier: Modifier = Modifier) {
    val currentDirectory by host.viewModel.currentDirectory.collectAsState()
    // The start must be the **session's** directory, the one
    // `GET /api/session/{id}` publishes as `location.directory` — never the
    // project's. `ChatViewModel.observeSession` republishes `currentDirectory`
    // from the screen's route on every entry, and that route's directory is
    // null (it is derived from the nav argument, never from the session), so a
    // null can wipe the server-confirmed value; the picker then started
    // wherever the probes point — the project directory / the server cwd
    // (`research/v40_verify/tA/52_moveto.png`). The last non-blank answer is
    // kept here so a transient null cannot become a start.
    var lastConfirmedDirectory by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(currentDirectory) {
        if (!currentDirectory.isNullOrBlank()) lastConfirmedDirectory = currentDirectory
    }
    val sessionDirectory = movePickerStart(currentDirectory, lastConfirmedDirectory)
    val scrim = rememberDialogScrimState()
    Box(modifier = modifier.fillMaxSize()) {
        DialogScrim(scrim, host::dismiss)
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            DirectoryPickerDialog(
                chatViewModel = host.viewModel,
                // The session's own directory, verbatim: it is the honest start
                // for 移动到…. A Windows system directory never reaches the
                // browser as a start even so — the picker's own entry guard
                // (`pickerEntryStart`) re-roots it to its drive root, which is
                // what the 「移动到…」 flow needs when the session runs in the
                // server's launch cwd (`C:\Windows\System32`, measured).
                initialPath = sessionDirectory,
                onSelect = host::confirmMove,
                onClose = host::dismiss,
                // The picker's own box is what `DialogHost` measures as the
                // layer's content, so its 24 dp gutter counts as "inside the
                // card" here too — a tap there is swallowed, never a dismissal.
                modifier = Modifier.dialogCardBounds(scrim),
            )
        }
    }
}

/**
 * Where 「移动到…」 starts: the session's own server-confirmed directory
 * ([currentDirectory], `GET /api/session/{id}`'s `location.directory`), falling
 * back to the last non-blank one this surface saw ([previous]) when the live
 * value has gone blank.
 *
 * The blank is not a "no directory" answer but a re-`observeSession` from a
 * route that never carried one, and the picker's own probes answer a null start
 * with the *project* directory — the bug v40 measured on `tA/52_moveto.png`
 * (the session lives in `…/frontend`, the picker opened on the shell repo).
 * An all-blank pair is still null, so the probes keep deciding.
 */
internal fun movePickerStart(currentDirectory: String?, previous: String?): String? =
    currentDirectory?.trim()?.takeIf { it.isNotEmpty() }
        ?: previous?.trim()?.takeIf { it.isNotEmpty() }

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
    val scrim = rememberDialogScrimState()
    Box(modifier = modifier.fillMaxSize()) {
        DialogScrim(scrim, onDismiss)
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
                .dialogCardBounds(scrim)
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
