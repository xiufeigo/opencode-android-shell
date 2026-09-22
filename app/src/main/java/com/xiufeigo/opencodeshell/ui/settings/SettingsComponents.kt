package com.xiufeigo.opencodeshell.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiufeigo.opencodeshell.ui.components.OcDotsIcon
import com.xiufeigo.opencodeshell.ui.components.OcPlusIcon
import com.xiufeigo.opencodeshell.ui.theme.OcTheme

/**
 * The `settings-v2` primitives from `research/02_ui_screens.md` §5.3 / §5.6 and
 * `research/05_palette_host_session.md` §5.2.
 *
 * Sizes are transcribed 1:1 from the vendored CSS: list = `layer-01`, radius 8,
 * `padding-inline: 20px`, `inset 0 0 0 0.5px border-muted`; row = `padding-block:
 * 20px` + `0.5px` bottom border (none on `:last-child`), title `13px/530`,
 * description `13px/440` in `text-muted`; nav trigger wrapper `h-28dp / r-4dp`.
 */

/** `13px/530` row title / `13px/440` muted description. */
@Composable
fun SettingsRowCopy(titleResId: Int, descriptionResId: Int?, modifier: Modifier = Modifier) {
    val c = OcTheme.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = stringResource(titleResId),
            color = c.textBase,
            fontSize = 13.sp,
            fontWeight = FontWeight(530),
        )
        if (descriptionResId != null) {
            Text(
                text = stringResource(descriptionResId),
                color = c.textMuted,
                fontSize = 13.sp,
                fontWeight = FontWeight(440),
                lineHeight = 20.sp,
            )
        }
    }
}

/** `[data-component=settings-v2-list]`. */
@Composable
fun SettingsList(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val c = OcTheme.colors
    Column(
        modifier
            .fillMaxWidth()
            .background(c.layer01, RoundedCornerShape(8.dp))
            .border(0.5.dp, c.borderMuted, RoundedCornerShape(8.dp))
            .padding(PaddingValues(start = 20.dp, end = 20.dp)),
    ) {
        content()
    }
}

/**
 * One `[data-component=settings-v2-row]`. On `<640dp` the copy and the control
 * stack vertically (the vendored `flex-direction: column` override, `05` §5.3).
 */
@Composable
fun SettingsRow(
    titleResId: Int,
    descriptionResId: Int?,
    last: Boolean,
    modifier: Modifier = Modifier,
    stacked: Boolean = false,
    control: @Composable () -> Unit,
) {
    val c = OcTheme.colors
    val border = if (last) Color.Transparent else c.borderBase
    Column(
        modifier
            .fillMaxWidth()
            .padding(vertical = 20.dp),
    ) {
        if (stacked) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsRowCopy(titleResId, descriptionResId, Modifier.fillMaxWidth())
                control()
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { SettingsRowCopy(titleResId, descriptionResId) }
                Spacer(Modifier.width(16.dp))
                control()
            }
        }
        if (!last) {
            Spacer(Modifier.height(20.dp))
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(border))
        }
    }
}

/** `settings-v2-section` (`gap:16px`) + `settings-v2-section-title`. */
@Composable
fun SettingsSectionBlock(titleResId: Int?, content: @Composable () -> Unit) {
    SettingsSectionBlockTitle(titleResId?.let { stringResource(it) }, content)
}

/**
 * [SettingsSectionBlock] with an already-resolved title — the Providers/Models
 * panels group by *dynamic* provider names that have no resource id.
 */
@Composable
fun SettingsSectionBlock(title: String, content: @Composable () -> Unit) {
    SettingsSectionBlockTitle(title, content)
}

@Composable
private fun SettingsSectionBlockTitle(title: String?, content: @Composable () -> Unit) {
    val c = OcTheme.colors
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (title != null) {
            Text(
                text = title,
                color = c.textBase,
                fontSize = 15.sp,
                fontWeight = FontWeight(640),
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        content()
    }
}

/**
 * `SelectV2` stand-in: a `button-v2`-shaped trigger plus an anchored menu.
 * `appearance="large"` ⇒ 36dp tall, radius 6, `layer-01` surface.
 */
@Composable
fun OcSelect(
    value: String,
    options: List<Pair<String, String>>,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onSelect: (String) -> Unit,
) {
    val c = OcTheme.colors
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            Modifier
                .widthIn(min = 140.dp, max = 260.dp)
                .defaultMinSize(minHeight = 36.dp)
                .background(c.layer01, RoundedCornerShape(6.dp))
                .border(0.5.dp, c.borderBase, RoundedCornerShape(6.dp))
                .clickable(enabled = enabled) { expanded = true }
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = value,
                color = if (enabled) c.textBase else c.textFaint,
                fontSize = 13.sp,
                fontWeight = FontWeight(440),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(8.dp))
            Chevron(c.iconMuted)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (optionValue, label) ->
                DropdownMenuItem(
                    text = {
                        Text(
                            label,
                            color = if (optionValue == value) c.textAccent else c.textBase,
                            fontSize = 13.sp,
                        )
                    },
                    onClick = {
                        expanded = false
                        onSelect(optionValue)
                    },
                )
            }
        }
    }
}

/** `appearance="large"` TextInputV2 (`h-36`, radius 6, `layer-02@60%`). */
@Composable
fun OcTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    password: Boolean = false,
    enabled: Boolean = true,
    imeAction: ImeAction = ImeAction.Done,
    keyboardType: KeyboardType = KeyboardType.Text,
    onImeAction: (() -> Unit)? = null,
    style: TextStyle? = null,
) {
    val c = OcTheme.colors
    val base = style ?: TextStyle(fontSize = 13.sp, fontWeight = FontWeight(440))
    Box(
        modifier
            .fillMaxWidth()
            .height(36.dp)
            .background(c.layer02.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
            .border(0.5.dp, c.borderBase, RoundedCornerShape(6.dp))
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty() && placeholder != null) {
            Text(placeholder, color = c.textFaint, fontSize = base.fontSize, maxLines = 1)
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = true,
            textStyle = base.copy(color = c.textBase),
            cursorBrush = SolidColor(c.textAccent),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
            keyboardActions = KeyboardActions(
                onDone = { onImeAction?.invoke() },
                onGo = { onImeAction?.invoke() },
                onSend = { onImeAction?.invoke() },
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** `Switch` stand-in (M3 primitives only ship layout; colour comes from tokens). */
@Composable
fun OcSwitch(checked: Boolean, enabled: Boolean = true, onCheckedChange: (Boolean) -> Unit) {
    val c = OcTheme.colors
    val track = if (checked) c.accent else c.layer03
    val knob = if (checked) c.textInverse else c.textMuted
    Box(
        Modifier
            .size(width = 40.dp, height = 24.dp)
            .background(track, RoundedCornerShape(12.dp))
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(2.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(Modifier.size(20.dp).background(knob, RoundedCornerShape(10.dp)))
    }
}

/**
 * `settings-v2-keybind-button`: `11px/530`, faint, `layer-02` on hover/press.
 *
 * W5-S (D4): it is a **button** now — tapping starts/stops key capture
 * (`keybinds.tsx:448-463` sets `settings-keybind-button--active` while capturing),
 * and the accent tint marks the row that currently owns the keyboard.
 */
@Composable
fun KeybindChip(
    text: String,
    modifier: Modifier = Modifier,
    recording: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val c = OcTheme.colors
    Box(
        modifier
            .background(if (recording) c.stateInfoBg else c.layer02, RoundedCornerShape(2.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Text(
            text = text,
            color = if (recording) c.stateInfoFg else c.textFaint,
            fontSize = 11.sp,
            fontWeight = FontWeight(530),
        )
    }
}

/** Small square icon button used by the servers tab (`plus` / `dots`). */
@Composable
fun OcIconButton(
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val c = OcTheme.colors
    Box(
        modifier
            .size(28.dp)
            .background(Color.Transparent, RoundedCornerShape(4.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
fun AddServerButton(onClick: () -> Unit) {
    val c = OcTheme.colors
    OcIconButton(onClick = onClick) { OcPlusIcon(c.iconMuted) }
}

@Composable
fun ServerRowMenu(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = OcTheme.colors
    OcIconButton(modifier, onClick) { OcDotsIcon(c.iconMuted) }
}

/** Chevron used by [OcSelect]; 10dp, `icon-muted`. */
@Composable
private fun Chevron(tint: Color) {
    Canvas(Modifier.size(10.dp)) {
        val s = size.minDimension
        drawLine(tint, Offset(s * 0.15f, s * 0.35f), Offset(s * 0.5f, s * 0.68f), s * 0.13f, StrokeCap.Round)
        drawLine(tint, Offset(s * 0.5f, s * 0.68f), Offset(s * 0.85f, s * 0.35f), s * 0.13f, StrokeCap.Round)
    }
}

/** Body text helper so screens never hardcode a colour. */
@Composable
fun OcBodyText(text: String, modifier: Modifier = Modifier, muted: Boolean = false, size: TextUnit = 13.sp) {
    Text(
        text = text,
        color = if (muted) OcTheme.colors.textMuted else OcTheme.colors.textBase,
        fontSize = size,
        fontWeight = FontWeight(440),
        modifier = modifier,
    )
}
