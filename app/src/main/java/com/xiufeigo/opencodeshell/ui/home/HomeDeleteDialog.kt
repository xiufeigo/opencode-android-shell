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
@Composable
fun HomeDeleteDialog(
    title: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val c = OcTheme.colors
    val shape = OcTheme.shapes.xl
    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(c.overlayScrim)
                .clickable(
                    interactionSource = androidx.compose.foundation.interaction.MutableInteractionSource(),
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
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                DialogButton(
                    label = stringResource(R.string.oc_common_cancel),
                    onClick = onDismiss,
                )
                Spacer(Modifier.width(8.dp))
                DialogButton(
                    label = stringResource(R.string.oc_delete_session),
                    danger = true,
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
            .clickable(role = Role.Button, onClick = onClick)
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
