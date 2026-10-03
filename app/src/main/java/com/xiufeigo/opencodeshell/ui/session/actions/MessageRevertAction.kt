package com.xiufeigo.opencodeshell.ui.session.actions

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.ui.theme.OcTheme

/**
 * The per-message「撤销到此」button — upstream's `MessageActionButton icon="reset"`
 * on the **user** message action row (`_upstream/packages/session-ui/src/message/
 * message-content.tsx:328-338`, placed before the copy button, 24 dp target /
 * 14 dp glyph like every other message action).
 *
 * Upstream's `actions.revert({sessionID, messageID})` is `revert.to(messageID)`
 * (`session/composer/region.tsx:155`, `session/revert.ts:85-119`): stage the
 * revert boundary at that user message, dropping it and everything after.
 * `ChatViewModel.undoSession()` is **not** that call — it rewinds one user
 * message (`revert.ts:92-99`) and takes no id — so the host passes [onRevert]
 * through to a `ChatViewModel.revertToMessage(messageID)` once that exists
 * (the delivery note spells out its body: `interruptIfRunning` +
 * `transport.stageRevert` + the v2 `commitRevert`, exactly like `redoSession`'s
 * `RevertStep.RevertTo` branch).
 *
 * Mount it from `UserMetaRow` in `ui/session/timeline/MessageMetaRow.kt`
 * (that package is owned by another agent — this file stays wiring-free).
 * Enablement follows upstream: visible whenever the host supplies the action,
 * disabled while a revert is in flight (`state.reverting`).
 */
@Composable
fun MessageRevertAction(
    messageID: String,
    busy: Boolean,
    onRevert: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = OcTheme.colors
    val label = stringResource(R.string.oc_meta_revert_message)
    Box(
        modifier = modifier
            .size(24.dp)
            .clickable(enabled = messageID.isNotBlank() && !busy, role = Role.Button) { onRevert(messageID) }
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        ResetIcon(tint = if (busy) c.iconMuted.copy(alpha = 0.5f) else c.iconMuted)
    }
}

/** `Icon name="reset"`: a near-full circular arrow, 14 dp, stroked like the sprite. */
@Composable
private fun ResetIcon(tint: Color) {
    Canvas(Modifier.size(14.dp)) {
        val s = size.minDimension
        val stroke = Stroke(width = s * 0.1f, cap = StrokeCap.Round)
        drawArc(
            color = tint,
            startAngle = -60f,
            sweepAngle = 280f,
            useCenter = false,
            topLeft = Offset(s * 0.12f, s * 0.12f),
            size = Size(s * 0.76f, s * 0.76f),
            style = stroke,
        )
        // Arrow head at the arc's open end (top-right).
        drawLine(tint, Offset(s * 0.62f, s * 0.08f), Offset(s * 0.9f, s * 0.14f), stroke.width, StrokeCap.Round)
        drawLine(tint, Offset(s * 0.62f, s * 0.08f), Offset(s * 0.74f, s * 0.34f), stroke.width, StrokeCap.Round)
    }
}
