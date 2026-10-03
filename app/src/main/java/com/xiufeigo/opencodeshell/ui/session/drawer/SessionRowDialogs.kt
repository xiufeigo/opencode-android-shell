package com.xiufeigo.opencodeshell.ui.session.drawer

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.ui.components.OcKeyboard
import com.xiufeigo.opencodeshell.ui.overlay.DialogScrim
import com.xiufeigo.opencodeshell.ui.overlay.dialogCardBounds
import com.xiufeigo.opencodeshell.ui.overlay.ocModalEscape
import com.xiufeigo.opencodeshell.ui.overlay.rememberDialogScrimState
import com.xiufeigo.opencodeshell.ui.session.composer.HalfWidthText
import com.xiufeigo.opencodeshell.ui.theme.OcTheme
import com.xiufeigo.opencodeshell.ui.theme.ocShadow

/**
 * Rename / delete confirmations for the session rows, opened from the row's
 * long-press menu (`_upstream/packages/app/src/home/sessions/view.tsx:694-701`:
 * Rename → inline editor, Delete → the danger dialog of
 * `home/sessions/controller.tsx:222-243`).
 *
 * Both are in-tree overlays on an `overlayScrim` rather than Material
 * `AlertDialog`s so the card keeps the OC surface tokens (`bgBase`, 0.5 dp
 * `borderBase`, `elevation.floating`) used by the project's other dialogs.
 *
 * v50 LOW-1: being in-tree is exactly why they needed
 * [ocModalEscape] — `DialogHost`'s Escape hook only sees layers it stacked, so
 * a pointer-only card like 删除… had no ESC path at all on the device.
 */
@Composable
fun SessionRenameDialog(
    initialTitle: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val c = OcTheme.colors
    val focusRequester = remember { FocusRequester() }
    // Upstream's rename editor opens with the current title selected
    // (`session/timeline/controller.tsx` → `TitleEditor`), so typing replaces it.
    var value by remember(initialTitle) {
        mutableStateOf(
            TextFieldValue(
                text = initialTitle,
                selection = TextRange(0, initialTitle.length),
            ),
        )
    }
    val trimmed = value.text.trim()
    // v51 MED-4 (`research/v51_verify/tE/v51_05/06/49`): the focus request is
    // retried over a few frames instead of being made once. A bare
    // `requestFocus()` throws `IllegalStateException` when the field's node is
    // not attached yet — which is the state the first frame of a card mounted
    // from a menu callback is in — and an exception thrown out of a
    // `LaunchedEffect` takes the whole composition's frame with it, so the
    // editor either never came up or came up without its IME. This is the same
    // retry `ocModalEscape` and `ocPopoverDismiss` already do for their roots
    // (`ui/overlay/ModalEscape.kt`, `ui/components/OcPopoverDismiss.kt`), and it
    // keeps the "open with the current title selected" contract: the selection
    // is seeded by [value], not by the focus landing.
    LaunchedEffect(Unit) {
        repeat(FOCUS_ATTACH_ATTEMPTS) {
            if (runCatching { focusRequester.requestFocus() }.isSuccess) return@LaunchedEffect
            withFrameNanos { }
        }
    }

    ModalCard(onDismiss = onDismiss, autoFocus = false) {
        Text(
            text = stringResource(R.string.oc_mobile_session_rename_title),
            color = c.textBase,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            lineHeight = 21.sp,
        )
        BasicTextField(
            value = value,
            // v40 BUG-2: a Chinese IME's full-width punctuation (`:` → `：`)
            // survives `OcKeyboard.literal`, which only disables autocorrect
            // (`research/v40_verify/tC/h4_typed.png`). `HalfWidthText.normalizeName`
            // — the W5-F *name* rule — is the shared input boundary; it is 1:1, so
            // the selection survives.
            onValueChange = { value = HalfWidthText.normalizeName(it) },
            singleLine = true,
            textStyle = TextStyle(color = c.textBase, fontSize = 13.sp, lineHeight = 16.sp),
            cursorBrush = SolidColor(c.textAccent),
            keyboardOptions = OcKeyboard.literal(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                onDone = { if (trimmed.isNotEmpty()) onConfirm(trimmed) },
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
                .clip(OcTheme.shapes.md)
                .background(c.layer02)
                .border(0.5.dp, c.borderBase, OcTheme.shapes.md)
                .focusRequester(focusRequester)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            ModalButton(
                label = stringResource(R.string.oc_common_cancel),
                onClick = onDismiss,
            )
            Spacer(Modifier.width(8.dp))
            ModalButton(
                label = stringResource(R.string.oc_common_save),
                enabled = trimmed.isNotEmpty(),
                onClick = { onConfirm(trimmed) },
            )
        }
    }
}

/**
 * The **durable** half of a failed rename: v82 BUG-B (`research/v82_verify/5558/
 * 35_ghost_rename_err.png` → `37_ghost_rename_result.png`).
 *
 * The session route's inline title closed, the title snapped back to the
 * server's, and the screen said nothing — because everything it had to say was
 * a `Toast` ([com.xiufeigo.opencodeshell.ui.renameFailureToast]), and a Toast is
 * gone in [android.widget.Toast.LENGTH_LONG] = 3.5 s. The device log of the very
 * rename those screenshots show proves the report was only ever about *that*:
 *
 * ```
 * 09:04:06.324 W ChatViewModel: rename session ses_f0e85a432… refused by the server: HTTP 500
 * 09:04:06.363 D WindowManager: setClientSurface … VRI-Toast#3476 … for … Toast
 * 09:04:10.453 W NotificationService: Toast already killed. pkg=com.xiufeigo.opencodeshell
 * ```
 *
 * — the toast was up for 4.1 s and the next screenshot landed at 09:04:11.398.
 * The shape routing was right (server log `PATCH /api/session/ses_f0e85a432…
 * → 500`), so the fix is to make the answer **stay**: this card carries the same
 * localised sentence from [com.xiufeigo.opencodeshell.ui.renameFailureRes] and
 * only the reader's tap (or Escape) takes it away, which no screenshot window
 * can outrun.
 *
 * One button, `Close`: there is nothing to retry inside the card — the title
 * editor is the retry, and the copy already says what a retry would do.
 */
@Composable
fun RenameFailureDialog(
    message: String,
    onDismiss: () -> Unit,
) {
    val c = OcTheme.colors
    ModalCard(onDismiss = onDismiss) {
        Text(
            text = stringResource(R.string.oc_error_title),
            color = c.textBase,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            lineHeight = 21.sp,
        )
        Text(
            text = message,
            color = c.stateDangerFg,
            fontSize = 13.sp,
            lineHeight = 20.sp,
            modifier = Modifier.padding(top = 8.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            ModalButton(
                label = stringResource(R.string.oc_common_close),
                onClick = onDismiss,
            )
        }
    }
}

/**
 * Destructive confirmation (`home/sessions/controller.tsx:222-243`).
 *
 * The same answer-carrying contract as [com.xiufeigo.opencodeshell.ui.home.HomeDeleteDialog]:
 * it used to close on the tap alone, so a `DELETE` the server never confirmed —
 * the P2 「删除静默失败」 (`research/v69_verify/5560/07f_delete.png` →
 * `07m_delete2.png`: card gone, row still in the list, nothing said) read exactly
 * like a successful one. [busy] locks the buttons (and Escape) while the write is
 * out, and [errorText] — set only on a server answer of "not deleted" — keeps the
 * card up with the reason, so the same 删除 is a retry.
 */
@Composable
fun SessionDeleteDialog(
    sessionTitle: String,
    busy: Boolean = false,
    errorText: String? = null,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val c = OcTheme.colors
    ModalCard(onDismiss = onDismiss, open = !busy) {
        Text(
            text = stringResource(R.string.oc_mobile_session_delete_title),
            color = c.textBase,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            lineHeight = 21.sp,
        )
        Text(
            text = stringResource(R.string.oc_mobile_session_delete_message, sessionTitle),
            color = c.textMuted,
            fontSize = 13.sp,
            lineHeight = 20.sp,
            modifier = Modifier.padding(top = 8.dp),
        )
        if (errorText != null) {
            Text(
                text = errorText,
                color = c.stateDangerFg,
                fontSize = 13.sp,
                lineHeight = 20.sp,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            ModalButton(
                label = stringResource(R.string.oc_common_cancel),
                enabled = !busy,
                onClick = onDismiss,
            )
            Spacer(Modifier.width(8.dp))
            ModalButton(
                label = stringResource(R.string.oc_delete_session),
                danger = true,
                enabled = !busy,
                onClick = onConfirm,
            )
        }
    }
}

/** Centered card on a tap-through-blocking scrim. */
@Composable
private fun ModalCard(
    onDismiss: () -> Unit,
    /** `false` for the rename editor — its text field autofocuses (see [ocModalEscape]). */
    autoFocus: Boolean = true,
    /** A write in flight owns the card, so Escape stops closing it (as `HomeDeleteDialog` does). */
    open: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = OcTheme.colors
    val shape = OcTheme.shapes.xl
    val scrim = rememberDialogScrimState()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .ocModalEscape(open = open, autoFocus = autoFocus, onDismiss = onDismiss),
    ) {
        DialogScrim(scrim, onDismiss, color = MobileColors.scrim)
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

/** Retry frames for a `requestFocus` on a node the first frame has not attached yet. */
private const val FOCUS_ATTACH_ATTEMPTS = 4

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
