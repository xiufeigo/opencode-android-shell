package com.xiufeigo.opencodeshell.ui.session.dock

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.xiufeigo.opencodeshell.ChatStore
import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.ui.session.drawer.QuestionPager
import com.xiufeigo.opencodeshell.ui.theme.OcTheme

/**
 * v2 form dock — the v2 counterpart of [SessionQuestionDock], transcribed from
 * the upstream v2 dock (`_upstream/packages/app/src/session/requests/
 * session-question-dock.tsx`), which renders a pending `FormInfo` (not a v1
 * question request): only `string` / `multiselect` fields become question
 * pages (`questionField`, `:21-23`), one page at a time with a `{{current}} of
 * {{total}}` header, progress segments, Back/Next→Submit, Dismiss (cancel) and
 * an always-offered custom answer (`:580-656`).
 *
 * The native dock extends the page set to all six field types (`SessionFormModel`
 * owns the field parse, the `when` linkage and the answer shape): scalar
 * `number` / `integer` / `boolean` fields get their own editors, `external`
 * fields an info page that never enters the answer. `when`-inactive and
 * `hidden` fields are skipped by the pager and dropped from the submit payload.
 *
 * Shell/tray skeleton, marks, buttons and segments reuse the
 * [SessionQuestionDock] transcription verbatim (same radii, overlaps, button-v2
 * sizes, option-mark geometry) so the two docks read as one surface.
 */
@Composable
fun SessionFormDock(
    form: ChatStore.FormAsk,
    responding: Boolean,
    onSubmit: (Map<String, Any?>) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = OcTheme.colors
    val surfaceShape = RoundedCornerShape(12.dp)
    val trayOverlap = 24.dp
    val context = LocalContext.current

    val fields = remember(form.id, form.fieldsJson) {
        SessionFormModel.parseFormFields(form.fieldsJson)
    }

    // Canonical answers by field key: String (Text), List<String> (Multi),
    // Double (Number), Boolean (Boolean). Seeded from schema defaults so an
    // untouched form still answers what the server suggested.
    var values by remember(form.id) {
        mutableStateOf(
            buildMap<String, Any?> {
                for (field in fields) {
                    when (field) {
                        is FormField.Text -> field.default?.let { put(field.key, it) }
                        is FormField.Multi -> field.default.takeIf { it.isNotEmpty() }
                            ?.let { put(field.key, it) }

                        is FormField.NumberInput -> field.default?.let { put(field.key, it) }
                        is FormField.BooleanInput -> field.default?.let { put(field.key, it) }
                        is FormField.External -> Unit
                    }
                }
            },
        )
    }
    // Custom-answer drafts (upstream `store.custom` / `customOn`, `:87-96`):
    // per text-option key, whether the custom row is the active answer and its
    // draft text. Picking a real option leaves custom mode (upstream `pick()`).
    var customOn by remember(form.id) { mutableStateOf(setOf<String>()) }
    var customText by remember(form.id) { mutableStateOf(mapOf<String, String>()) }
    // Raw numeric drafts: the canonical value only holds parsed numbers, so an
    // in-progress (invalid) input must live elsewhere or typing would jam.
    var numberDraft by remember(form.id) {
        mutableStateOf(
            buildMap<String, String> {
                for (field in fields) {
                    if (field is FormField.NumberInput && field.default != null) {
                        put(field.key, formatNumber(field.default, field.integer))
                    }
                }
            },
        )
    }

    val pages = SessionFormModel.visiblePages(fields, values)
    var tab by remember(form.id) { mutableStateOf(0) }
    val total = pages.size
    val page = QuestionPager.clamp(tab, total)
    val segments = QuestionPager.segments(
        tab = page,
        answered = pages.map { SessionFormModel.pageAnswered(it, values[it.key]) },
    )

    fun setValue(key: String, value: Any?) {
        values = if (value == null) values - key else values + (key to value)
    }

    fun pickOption(field: FormField.Text, value: String) {
        setValue(field.key, value)
        customOn = customOn - field.key
    }

    fun toggleOption(field: FormField.Multi, value: String) {
        val current = (values[field.key] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()
        setValue(field.key, if (value in current) current - value else current + value)
    }

    fun toggleCustom(key: String, multi: Boolean, current: List<String> = emptyList()) {
        customOn = if (key in customOn) {
            if (multi) {
                // Leaving custom mode drops the draft from a multi answer.
                val draft = customText[key].orEmpty().trim()
                if (draft.isNotEmpty()) setValue(key, current - draft)
            }
            customOn - key
        } else {
            customOn + key
        }
    }

    Layout(
        modifier = modifier.fillMaxWidth(),
        content = {
            // -------------------------------------------------------- shell
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(surfaceShape)
                    .background(c.layer01)
                    .border(0.5.dp, c.borderMuted, surfaceShape)
                    .padding(start = 12.dp, end = 12.dp, top = 12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier.size(20.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_oc_help),
                            contentDescription = null,
                            tint = c.iconAccent,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (QuestionPager.showProgress(total)) {
                            stringResource(
                                R.string.oc_mobile_question_progress,
                                QuestionPager.progressNumber(page, total),
                                total,
                            )
                        } else {
                            form.title.ifEmpty { stringResource(R.string.oc_question_title) }
                        },
                        color = c.textBase,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.W500,
                        lineHeight = 21.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (QuestionPager.showProgress(total)) {
                        Spacer(Modifier.width(8.dp))
                        FormProgressSegments(
                            segments = segments,
                            total = total,
                            enabled = !responding,
                            onJump = { index -> tab = QuestionPager.clamp(index, total) },
                        )
                    }
                }
                Column(
                    modifier = Modifier.padding(top = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    pages.getOrNull(page)?.let { field ->
                        when (field) {
                            is FormField.Text -> if (field.options.isEmpty()) {
                                FreeTextBlock(
                                    title = field.title,
                                    description = field.description,
                                    placeholder = field.placeholder
                                        ?: stringResource(R.string.oc_question_custom_placeholder),
                                    value = (values[field.key] as? String).orEmpty(),
                                    enabled = !responding,
                                    onChange = { setValue(field.key, it) },
                                )
                            } else {
                                OptionsBlock(
                                    title = field.title,
                                    description = field.description,
                                    multi = false,
                                    options = field.options,
                                    picked = (values[field.key] as? String)
                                        ?.takeIf { field.key !in customOn }
                                        ?.let { listOf(it) } ?: emptyList(),
                                    customAllowed = field.custom,
                                    customActive = field.key in customOn,
                                    customValue = customText[field.key].orEmpty(),
                                    enabled = !responding,
                                    onSelect = { pickOption(field, it) },
                                    onToggleCustom = {
                                        toggleCustom(field.key, multi = false)
                                    },
                                    onCustomChange = { draft ->
                                        customText = customText + (field.key to draft)
                                        val trimmed = draft.trim()
                                        setValue(
                                            field.key,
                                            trimmed.takeIf { it.isNotEmpty() },
                                        )
                                        if (trimmed.isNotEmpty()) customOn = customOn + field.key
                                    },
                                )
                            }

                            is FormField.Multi -> {
                                val picked = (values[field.key] as? List<*>)
                                    ?.mapNotNull { it?.toString() } ?: emptyList()
                                OptionsBlock(
                                    title = field.title,
                                    description = field.description,
                                    multi = true,
                                    options = field.options,
                                    picked = picked,
                                    customAllowed = field.custom,
                                    customActive = field.key in customOn,
                                    customValue = customText[field.key].orEmpty(),
                                    enabled = !responding,
                                    onSelect = { toggleOption(field, it) },
                                    onToggleCustom = {
                                        toggleCustom(field.key, multi = true, current = picked)
                                    },
                                    onCustomChange = { draft ->
                                        val previous = customText[field.key].orEmpty().trim()
                                        customText = customText + (field.key to draft)
                                        val trimmed = draft.trim()
                                        val base = if (previous.isEmpty()) {
                                            picked
                                        } else {
                                            picked.filter { it.trim() != previous }
                                        }
                                        setValue(
                                            field.key,
                                            (if (trimmed.isEmpty()) base else base + trimmed)
                                                .takeIf { it.isNotEmpty() },
                                        )
                                        customOn = if (trimmed.isEmpty()) {
                                            customOn - field.key
                                        } else {
                                            customOn + field.key
                                        }
                                    },
                                )
                            }

                            is FormField.NumberInput -> FreeTextBlock(
                                title = field.title,
                                description = field.description,
                                placeholder = stringResource(R.string.oc_question_custom_placeholder),
                                value = numberDraft[field.key].orEmpty(),
                                enabled = !responding,
                                numeric = true,
                                onChange = { draft ->
                                    numberDraft = numberDraft + (field.key to draft)
                                    setValue(field.key, draft.trim().toDoubleOrNull())
                                },
                            )

                            is FormField.BooleanInput -> {
                                val current = values[field.key] as? Boolean
                                OptionsBlock(
                                    title = field.title,
                                    description = field.description,
                                    multi = false,
                                    options = booleanOptions(
                                        yes = stringResource(R.string.oc_form_yes),
                                        no = stringResource(R.string.oc_form_no),
                                    ),
                                    picked = current?.let { listOf(it.toString()) } ?: emptyList(),
                                    customAllowed = false,
                                    customActive = false,
                                    customValue = "",
                                    enabled = !responding,
                                    onSelect = { setValue(field.key, it.toBoolean()) },
                                    onToggleCustom = {},
                                    onCustomChange = {},
                                )
                            }

                            is FormField.External -> ExternalBlock(
                                field = field,
                                enabled = !responding,
                                onOpen = {
                                    runCatching {
                                        context.startActivity(
                                            Intent(Intent.ACTION_VIEW, field.url.toUri()),
                                        )
                                    }
                                },
                            )
                        }
                    }
                }
            }
            // --------------------------------------------------------- tray
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(surfaceShape)
                    .background(c.bgBase)
                    .border(1.dp, c.borderMuted, surfaceShape)
                    .padding(start = 8.dp, end = 8.dp, top = 32.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.weight(1f))
                FormDockButton(
                    label = stringResource(R.string.oc_question_dismiss),
                    variant = FormDockButtonVariant.Ghost,
                    enabled = !responding,
                    onClick = onDismiss,
                )
                if (QuestionPager.canGoBack(page)) {
                    Spacer(Modifier.width(8.dp))
                    FormDockButton(
                        label = stringResource(R.string.oc_mobile_question_back),
                        variant = FormDockButtonVariant.Neutral,
                        enabled = !responding,
                        onClick = { tab = QuestionPager.back(page, total) },
                    )
                }
                Spacer(Modifier.width(8.dp))
                val last = QuestionPager.isLast(page, total)
                FormDockButton(
                    label = stringResource(
                        if (last) R.string.oc_question_submit else R.string.oc_mobile_question_next,
                    ),
                    variant = if (last) FormDockButtonVariant.Submit
                    else FormDockButtonVariant.Neutral,
                    enabled = !responding,
                    onClick = {
                        if (last) {
                            onSubmit(SessionFormModel.buildAnswer(fields, values))
                        } else {
                            tab = QuestionPager.next(page, total)
                        }
                    },
                )
            }
        },
    ) { measurables, constraints ->
        val overlapPx = trayOverlap.roundToPx()
        val shell = measurables[0].measure(constraints)
        val tray = measurables[1].measure(constraints)
        layout(shell.width, shell.height + tray.height - overlapPx) {
            tray.place(0, shell.height - overlapPx)
            shell.place(0, 0)
        }
    }
}

/** A free-text page: `string` without options, `number` / `integer`. */
@Composable
private fun FreeTextBlock(
    title: String,
    description: String,
    placeholder: String,
    value: String,
    enabled: Boolean,
    onChange: (String) -> Unit,
    numeric: Boolean = false,
) {
    val c = OcTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (title.isNotEmpty()) {
            Text(
                text = title,
                color = c.textMuted,
                fontSize = 13.sp,
                fontWeight = FontWeight(530),
                lineHeight = 16.sp,
            )
        }
        if (description.isNotEmpty()) {
            Text(
                text = description,
                color = c.textBase,
                fontSize = 14.sp,
                lineHeight = 21.sp,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(c.bgBase)
                .border(0.5.dp, c.borderBase, RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            if (value.isEmpty()) {
                Text(
                    text = placeholder,
                    color = c.textFaint,
                    fontSize = 13.sp,
                    lineHeight = 16.sp,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onChange,
                enabled = enabled,
                singleLine = !numeric,
                textStyle = TextStyle(
                    color = c.textBase,
                    fontSize = 13.sp,
                    lineHeight = 16.sp,
                ),
                cursorBrush = SolidColor(c.textAccent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** An option-list page: optioned `string` (single), `multiselect`, `boolean`. */
@Composable
private fun OptionsBlock(
    title: String,
    description: String,
    multi: Boolean,
    options: List<FormOption>,
    picked: List<String>,
    customAllowed: Boolean,
    customActive: Boolean,
    customValue: String,
    enabled: Boolean,
    onSelect: (String) -> Unit,
    onToggleCustom: () -> Unit,
    onCustomChange: (String) -> Unit,
) {
    val c = OcTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (title.isNotEmpty()) {
            Text(
                text = title,
                color = c.textMuted,
                fontSize = 13.sp,
                fontWeight = FontWeight(530),
                lineHeight = 16.sp,
            )
        }
        if (description.isNotEmpty()) {
            Text(
                text = description,
                color = c.textBase,
                fontSize = 14.sp,
                lineHeight = 21.sp,
            )
        }
        if (multi) {
            Text(
                text = stringResource(R.string.oc_question_hint_multi),
                color = c.textFaint,
                fontSize = 13.sp,
                lineHeight = 16.sp,
            )
        } else if (options.size > 1 || customAllowed) {
            Text(
                text = stringResource(R.string.oc_question_hint_single),
                color = c.textFaint,
                fontSize = 13.sp,
                lineHeight = 16.sp,
            )
        }
        Column(
            modifier = Modifier.padding(top = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for (option in options) {
                FormOptionRow(
                    multi = multi,
                    picked = option.value in picked,
                    label = option.label,
                    description = option.description.orEmpty(),
                    enabled = enabled,
                    onClick = { onSelect(option.value) },
                )
            }
            if (customAllowed) {
                FormCustomRow(
                    multi = multi,
                    active = customActive,
                    value = customValue,
                    enabled = enabled,
                    onToggle = onToggleCustom,
                    onValueChange = onCustomChange,
                )
            }
        }
    }
}

/** An `external` page: title + description + the tappable URL. No answer. */
@Composable
private fun ExternalBlock(
    field: FormField.External,
    enabled: Boolean,
    onOpen: () -> Unit,
) {
    val c = OcTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (field.title.isNotEmpty()) {
            Text(
                text = field.title,
                color = c.textMuted,
                fontSize = 13.sp,
                fontWeight = FontWeight(530),
                lineHeight = 16.sp,
            )
        }
        if (field.description.isNotEmpty()) {
            Text(
                text = field.description,
                color = c.textBase,
                fontSize = 14.sp,
                lineHeight = 21.sp,
            )
        }
        Text(
            text = field.url,
            color = c.textAccent,
            fontSize = 13.sp,
            lineHeight = 16.sp,
            modifier = Modifier
                .padding(top = 4.dp)
                .clickable(enabled = enabled, role = Role.Button, onClick = onOpen),
        )
    }
}

@Composable
private fun FormOptionRow(
    multi: Boolean,
    picked: Boolean,
    label: String,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val c = OcTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(if (picked) c.stateInfoBg else Color.Transparent)
            .clickable(enabled = enabled, role = if (multi) Role.Checkbox else Role.RadioButton, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FormOptionMark(multi = multi, picked = picked)
        Spacer(Modifier.width(8.dp))
        Column {
            Text(
                text = label,
                color = c.textBase,
                fontSize = 13.sp,
                fontWeight = FontWeight(530),
                lineHeight = 16.sp,
            )
            if (description.isNotEmpty()) {
                Text(
                    text = description,
                    color = c.textMuted,
                    fontSize = 13.sp,
                    lineHeight = 16.sp,
                )
            }
        }
    }
}

@Composable
private fun FormCustomRow(
    multi: Boolean,
    active: Boolean,
    value: String,
    enabled: Boolean,
    onToggle: () -> Unit,
    onValueChange: (String) -> Unit,
) {
    val c = OcTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(if (active) c.stateInfoBg else Color.Transparent)
            .clickable(enabled = enabled, role = if (multi) Role.Checkbox else Role.RadioButton, onClick = onToggle)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FormOptionMark(multi = multi, picked = active)
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.oc_question_custom),
                color = c.textBase,
                fontSize = 13.sp,
                fontWeight = FontWeight(530),
                lineHeight = 16.sp,
            )
            if (active) {
                Box {
                    if (value.isEmpty()) {
                        Text(
                            text = stringResource(R.string.oc_question_custom_placeholder),
                            color = c.textFaint,
                            fontSize = 13.sp,
                            lineHeight = 16.sp,
                        )
                    }
                    BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        enabled = enabled,
                        singleLine = true,
                        textStyle = TextStyle(
                            color = c.textBase,
                            fontSize = 13.sp,
                            lineHeight = 16.sp,
                        ),
                        cursorBrush = SolidColor(c.textAccent),
                        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun FormOptionMark(multi: Boolean, picked: Boolean) {
    val c = OcTheme.colors
    val shape = if (multi) RoundedCornerShape(4.dp) else CircleShape
    Box(
        modifier = Modifier
            .size(16.dp)
            .clip(shape)
            .background(if (picked && multi) c.iconAccent else Color.Transparent)
            .border(
                width = 0.5.dp,
                color = if (picked) c.iconAccent else c.borderBase,
                shape = shape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (picked) {
            if (multi) {
                Icon(
                    painter = painterResource(R.drawable.ic_oc_check),
                    contentDescription = null,
                    tint = c.iconInverse,
                    modifier = Modifier.size(10.dp),
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(c.iconAccent),
                )
            }
        }
    }
}

private enum class FormDockButtonVariant { Ghost, Neutral, Submit }

@Composable
private fun FormDockButton(
    label: String,
    variant: FormDockButtonVariant,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val c = OcTheme.colors
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier = Modifier
            .alpha(if (enabled) 1f else 0.5f)
            .height(28.dp)
            .clip(shape)
            .background(
                when (variant) {
                    FormDockButtonVariant.Ghost -> Color.Transparent
                    FormDockButtonVariant.Neutral -> c.buttonNeutral
                    FormDockButtonVariant.Submit -> c.inverse
                },
            )
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = when (variant) {
                FormDockButtonVariant.Submit -> c.textInverse
                else -> c.textBase
            },
            fontSize = 13.sp,
            fontWeight = FontWeight(530),
            lineHeight = 20.sp,
        )
    }
}

@Composable
private fun FormProgressSegments(
    segments: QuestionPager.Segments,
    total: Int,
    enabled: Boolean,
    onJump: (Int) -> Unit,
) {
    val c = OcTheme.colors
    Row(
        modifier = Modifier.height(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (index in 0 until total) {
            val active = index == segments.active
            val answered = index in segments.answered
            val label = stringResource(R.string.oc_mobile_question_numbered, index + 1)
            Box(
                modifier = Modifier
                    .width(18.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(
                        when {
                            active -> c.textAccent
                            answered -> c.iconBase
                            else -> c.borderStrong
                        },
                    )
                    .semantics { contentDescription = label }
                    .clickable(enabled = enabled, role = Role.Tab) { onJump(index) },
            )
        }
    }
}

/** The two `boolean` rows. Values are the JSON booleans as text. */
private fun booleanOptions(yes: String, no: String): List<FormOption> = listOf(
    FormOption(value = "true", label = yes, description = null),
    FormOption(value = "false", label = no, description = null),
)

/** Canonical draft text for a numeric default (integers without a fraction). */
private fun formatNumber(value: Double, integer: Boolean): String =
    if (integer && value == kotlin.math.floor(value)) {
        value.toLong().toString()
    } else {
        value.toString()
    }
