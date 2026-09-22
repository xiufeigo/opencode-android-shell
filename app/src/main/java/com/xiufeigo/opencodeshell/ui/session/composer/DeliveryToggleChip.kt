package com.xiufeigo.opencodeshell.ui.session.composer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.ui.theme.OcTheme

/**
 * WAVE 2.4b (P0-7): the Steer/Queue delivery toggle.
 *
 * Upstream the composer offers the delivery opposite to what plain Enter does
 * as a hint button next to submit (`composer/editor/editor.tsx:822-866`
 * `ComposerEditorAlternateDelivery`: "Steer ⌘⏎" / "Queue ⌘⏎", visible only
 * while the queue exposes an alternate — turn running and composer holding a
 * value) plus the `followUpBehavior` settings row (`settings/model.tsx:96`).
 *
 * Native shape: a 28 dp chip in the controls row, shown only while
 * [alternate] is non-null (same visibility rule, see [ComposerDelivery.alternate]).
 * Tapping it submits immediately with the offered delivery (the alternate
 * keybind path, `model.ts:271-273`); the plain submit path keeps using
 * [ComposerDelivery.delivery]. The setting itself is never written here
 * (upstream keeps it in settings; the composer only reads it to default the
 * toggle, see `wave2.4b-notes.md` section VM).
 *
 * Strings: the labels reuse the generated `ui_promptInput_steer` /
 * `ui_promptInput_queue` keys from `strings_oc_i18n.xml`, present in the
 * default bundle so every locale falls back safely at runtime.
 */
@Composable
fun DeliveryToggleChip(
    /** The delivery the hint offers (`ComposerDelivery.alternate`), null = hidden. */
    alternate: String?,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (alternate.isNullOrBlank()) return
    val c = OcTheme.colors
    Row(
        modifier = modifier
            .height(CHIP_HEIGHT)
            .clip(OcTheme.shapes.md)
            .clickable(role = Role.Button, onClick = onToggle)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_oc_send),
            contentDescription = null,
            tint = c.iconMuted,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = deliveryChipLabel(alternate),
            color = c.textMuted,
            fontSize = 12.sp,
            maxLines = 1,
        )
    }
}

/** Chip height, matching the agent/model chips in the same row. */
private val CHIP_HEIGHT = 28.dp
