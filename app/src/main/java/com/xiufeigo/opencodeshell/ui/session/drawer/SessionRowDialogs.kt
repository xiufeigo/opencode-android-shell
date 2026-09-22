package com.xiufeigo.opencodeshell.ui.session.drawer

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    ModalCard(onDismiss = onDismiss) {
        Text(
            text = stringResource(R.string.oc_mobile_session_rename_title),
            color = c.textBase,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            lineHeight = 21.sp,
        )
        BasicTextField(
            value = value,
            onValueChange = { value = it },
            singleLine = true,
            textStyle = TextStyle(color = c.textBase, fontSize = 13.sp, lineHeight = 16.sp),
            cursorBrush = SolidColor(c.textAccent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
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

/** Destructive confirmation (`home/sessions/controller.tsx:222-243`). */
@Composable
fun SessionDeleteDialog(
    sessionTitle: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val c = OcTheme.colors
    ModalCard(onDismiss = onDismiss) {
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
                label = stringResource(R.string.oc_delete_session),
                danger = true,
                onClick = onConfirm,
            )
        }
    }
}

/** Centered card on a tap-through-blocking scrim. */
@Composable
private fun ModalCard(
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = OcTheme.colors
    val shape = OcTheme.shapes.xl
    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MobileColors.scrim)
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
