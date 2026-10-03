package com.xiufeigo.opencodeshell.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.ui.overlay.DialogScrim
import com.xiufeigo.opencodeshell.ui.overlay.dialogCardBounds
import com.xiufeigo.opencodeshell.ui.overlay.ocModalEscape
import com.xiufeigo.opencodeshell.ui.overlay.rememberDialogScrimState
import com.xiufeigo.opencodeshell.ui.theme.OcTheme
import com.xiufeigo.opencodeshell.ui.theme.ocShadow

// WAVE 2.3b P1 (A2 #4) - Home's delete danger dialog, self-contained in
// ui/home (home/sessions/controller.tsx:219-243 DeleteDialog:
// session.delete.title + session.delete.confirm name + Cancel ghost /
// Delete danger).
//
// Self-contained rather than reusing ui/session/drawer/SessionRowDialogs.kt
// (SessionDeleteDialog): that file is owned by another wave and renders the
// drawer's danger copy (oc_mobile_session_delete_title/message), while this
// dialog keeps Home's upstream copy. TODO(i18n): session.delete.* has no oc_*
// key yet - the drawer's delete strings stand in until the i18n agent
// adds them (noted in research/v2_audit/wave2.3b-notes.md).
//
// v44 tC: the dialog also carries the delete's **answer**. It used to close on
// the tap alone, so a `DELETE` that never left the device (the P0
// 「删除静默无效」, research/v44_verify/tC/v44_49_delete_confirm.png) looked
// exactly like a successful one. [busy] locks the buttons while the write is
// out, and [errorText] — set only on a server answer of "not deleted" — keeps the
// dialog up with the reason, so the same 删除 is a retry.
@Composable
fun HomeDeleteDialog(
    title: String,
    busy: Boolean = false,
    errorText: String? = null,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val c = OcTheme.colors
    val shape = OcTheme.shapes.xl
    val scrim = rememberDialogScrimState()
    Box(
        modifier = Modifier
            .fillMaxSize()
            // v50 LOW-1: this card draws its own scrim instead of riding a
            // DialogHost layer, so the host's Escape never reached it
            // (`research/v50_verify/tD/v50_46→47`). Same rule as the host: with
            // the IME up Escape only collapses the keyboard; with it down it
            // cancels — and a `DELETE` in flight owns the buttons, so it owns
            // Escape too.
            .ocModalEscape(open = !busy, onDismiss = onDismiss),
    ) {
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
        ) {
            Text(
                text = stringResource(R.string.oc_mobile_session_delete_title),
                color = c.textBase,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                lineHeight = 21.sp,
            )
            Text(
                text = stringResource(R.string.oc_mobile_session_delete_message, title),
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
                DialogButton(
                    label = stringResource(R.string.oc_common_cancel),
                    enabled = !busy,
                    onClick = onDismiss,
                )
                Spacer(Modifier.width(8.dp))
                DialogButton(
                    label = stringResource(R.string.oc_delete_session),
                    danger = true,
                    enabled = !busy,
                    onClick = onConfirm,
                )
            }
        }
    }
}

// button-v2 metrics: h 28, radius 6, padding-x 12, 13/530.
@Composable
private fun DialogButton(
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
    danger: Boolean = false,
) {
    val c = OcTheme.colors
    val shape = OcTheme.shapes.md
    Box(
        modifier = Modifier
            .height(28.dp)
            .clip(shape)
            .background(if (danger) c.stateDangerFg else Color.Transparent)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (!enabled) c.textMuted else if (danger) c.textInverse else c.textBase,
            fontSize = 13.sp,
            fontWeight = FontWeight(530),
            lineHeight = 20.sp,
        )
    }
}
