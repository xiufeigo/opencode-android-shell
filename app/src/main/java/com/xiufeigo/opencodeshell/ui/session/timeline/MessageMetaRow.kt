package com.xiufeigo.opencodeshell.ui.session.timeline

import android.text.format.DateFormat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.ui.session.actions.MessageRevertAction
import com.xiufeigo.opencodeshell.ui.theme.OcTheme
import kotlinx.coroutines.delay
import java.util.Date

/**
 * The per-message copy/meta rows of `message-content.tsx` (W4-B item 6):
 *
 * - assistant (`:539-554` + `message-part.css:236-265`): a 24 dp row with a
 *   10 px gap, left-aligned, copy button first then the 12 px `text-weak` meta;
 *   on an interrupted message the row spans the width and right-aligns with a
 *   12 px gap;
 * - user (`:311-354` + `message-part.css:164-211`): right-aligned, `Agent · Model`
 *   head plus the locale short time stamp, copy button last.
 *
 * Upstream reveals these on hover (`@media (hover: hover)`); a touch target has
 * no hover, so they are always visible — the same call the rest of the Android
 * shell makes for hover-only affordances.
 */
@Composable
internal fun AssistantMetaRow(
    text: String,
    meta: MessageMeta.Meta,
    modifier: Modifier = Modifier,
) {
    val c = OcTheme.colors
    val interrupted = meta.interrupted
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .height(META_ROW_HEIGHT),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (interrupted) Arrangement.End else Arrangement.Start,
    ) {
        if (text.isNotBlank()) {
            CopyActionButton(text = text, copyLabelRes = R.string.oc_meta_copy_response)
        }
        val label = assistantMetaLabel(meta)
        if (label.isNotEmpty()) {
            Spacer(Modifier.width(if (interrupted) INTERRUPTED_GAP else META_GAP))
            Text(
                text = label,
                color = c.textFaint,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * User row meta (`:311-327`): `Agent · Model` head + time stamp, right aligned,
 * copy button after the text. The「撤销到此」action (`message-content.tsx:328-338`)
 * sits before the copy button; [onRevert] hides it when the host supplies none.
 */
@Composable
internal fun UserMetaRow(
    text: String,
    meta: MessageMeta.Meta,
    modifier: Modifier = Modifier,
    messageID: String = "",
    revertBusy: Boolean = false,
    onRevert: ((String) -> Unit)? = null,
) {
    val c = OcTheme.colors
    val context = LocalContext.current
    // `Intl.DateTimeFormat(locale, { timeStyle: "short" })` (`:226`): the
    // locale's short time format, e.g. `14:32`.
    val stamp = remember(meta.createdAt, context) {
        if (meta.createdAt <= 0L) {
            null
        } else {
            DateFormat.getTimeFormat(context).format(Date(meta.createdAt))
        }
    }
    val label = listOfNotNull(meta.head.takeIf { it.isNotEmpty() }, stamp)
        .joinToString("\u00A0\u00B7\u00A0")
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .height(META_ROW_HEIGHT),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.End,
    ) {
        if (label.isNotEmpty()) {
            Text(
                text = label,
                color = c.textFaint,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        if (onRevert != null) {
            Spacer(Modifier.width(META_GAP))
            MessageRevertAction(
                messageID = messageID,
                busy = revertBusy,
                onRevert = onRevert,
            )
        }
        if (text.isNotBlank()) {
            Spacer(Modifier.width(META_GAP))
            CopyActionButton(text = text, copyLabelRes = R.string.oc_meta_copy_message)
        }
    }
}

/**
 * `Agent · Model · {duration} · Interrupted` (`message-content.tsx:511-521`),
 * assembled from the localized templates so the row follows the app locale —
 * [MessageMeta.Meta.text] is the English composition used by the JVM tests.
 */
@Composable
private fun assistantMetaLabel(meta: MessageMeta.Meta): String {
    val duration = MessageMeta.durationSeconds(meta.durationMs)?.let { seconds ->
        if (seconds < 60) {
            stringResource(R.string.oc_meta_duration_seconds, seconds)
        } else {
            stringResource(R.string.oc_meta_duration_minutes, seconds / 60, seconds % 60)
        }
    }
    return listOfNotNull(
        meta.agent,
        meta.model,
        duration,
        if (meta.interrupted) stringResource(R.string.oc_meta_interrupted) else null,
    ).joinToString(" \u00B7 ")
}

/**
 * `MessageActionButton` (`message-content.tsx:56-76`): ghost-muted 14 px icon in
 * a small square target, `outline-copy` → `check` for 2 s after the copy
 * (`:522-527`), tooltip replaced by the content description on touch.
 */
@Composable
private fun CopyActionButton(
    text: String,
    copyLabelRes: Int,
) {
    val c = OcTheme.colors
    val clipboard = LocalClipboardManager.current
    val label = stringResource(copyLabelRes)
    val copiedLabel = stringResource(R.string.oc_meta_copied)
    var copied by remember { mutableStateOf(false) }
    // Hoisted: `semantics {}` is not a composable scope. The label rides the
    // clickable Box, not the `Icon` inside it.
    val copyLabel = if (copied) copiedLabel else label

    LaunchedEffect(copied) {
        if (!copied) return@LaunchedEffect
        delay(COPIED_RESET_MS)
        copied = false
    }

    Box(
        modifier = Modifier
            .size(COPY_BUTTON_SIZE)
            .clip(OcTheme.shapes.md)
            .clickable(role = Role.Button) {
                clipboard.setText(AnnotatedString(text))
                copied = true
            }
            .semantics { contentDescription = copyLabel },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(if (copied) R.drawable.ic_oc_check else R.drawable.ic_oc_copy),
            contentDescription = null,
            tint = c.iconMuted,
            modifier = Modifier.size(14.dp),
        )
    }
}

/** `min-height: 24px` of both copy wrappers. */
private val META_ROW_HEIGHT = 24.dp

/** `gap: 10px` (`message-part.css:170`). */
private val META_GAP = 10.dp

/** `[data-interrupted] { gap: 12px }` (`message-part.css:264`). */
private val INTERRUPTED_GAP = 12.dp

/** `IconButton size="normal"` (`icon-button.css`). */
private val COPY_BUTTON_SIZE = 24.dp

/** `setTimeout(() => setCopied(false), 2000)`. */
private const val COPIED_RESET_MS = 2_000L
