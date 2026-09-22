package com.xiufeigo.opencodeshell.ui.session.dock

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
import com.xiufeigo.opencodeshell.OpenCodeClient
import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.ui.session.drawer.QuestionPager
import com.xiufeigo.opencodeshell.ui.theme.OcTheme

/**
 * Question confirmation dock — the v1 counterpart of the upstream
 * `session-question-dock.tsx` (`_upstream/packages/app/src/session/requests/`),
 * mounted above the permission dock in the composer region
 * (`session-composer-region.tsx:39-60`: questionDock → permissionDock →
 * composer). Where upstream drives the v2 form wizard one tab at a time, this
 * dock targets the **v1** `QuestionV1.Request` payload
 * (`_upstream/packages/schema/src/v1/question.ts`) and renders every question
 * of the request stacked, with one Submit / Dismiss pair at the bottom.
 *
 * Layout reuses the [SessionPermissionDock] shell/tray skeleton verbatim:
 * - **shell** (`layer01`, radius 12, `12 12 0` padding, 0.5 dp hairline):
 *   help icon + title, then per question the `header` label (13/530 muted),
 *   the `question` text (14/400), a single/multi hint and the option rows
 *   (`question-option*` slots in `message-part.css:1011-1145`).
 * - **tray** (`bgBase`, 1 dp `borderMuted`, radius 12, `margin-top: -24px` ⇒
 *   `padding: 32px 8px 8px`): Dismiss = `ghost`, Submit = `submit` (upstream
 *   order keeps the primary action rightmost).
 *
 * Option marks transcribe `question-option-box` (`message-part.css:1066`):
 * 16 dp box, 0.5 dp `borderBase`, radio = circle / checkbox = `radius-sm`;
 * picked = `iconAccent` border, checkbox additionally `iconAccent` fill with
 * an `iconInverse` check. A picked row tints `stateInfoBg`
 * (`[data-picked="true"]`, `:1112`).
 *
 * Answer semantics (`QuestionV1.Reply`): `answers[i]` is the label array for
 * question `i`, in question order. Single-select replaces; `multiple` toggles.
 * A question whose `custom` is not `false` gains a「自定义…」row: for
 * single-select the custom text *is* the answer while active (picking an
 * option drops it, upstream `pick()`); for multi it is appended as one extra
 * label. Unanswered questions submit an empty array so the arrays stay
 * aligned with the question order.
 */
@Composable
fun SessionQuestionDock(
    request: OpenCodeClient.QuestionRequest,
    responding: Boolean,
    onSubmit: (List<List<String>>) -> Unit,
    onReject: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = OcTheme.colors
    val surfaceShape = RoundedCornerShape(12.dp)
    val trayOverlap = 24.dp

    // Per-question answer state, keyed to the request so a re-asked id starts
    // clean. Selections are Lists (not Sets) to keep click order deterministic
    // for the wire payload.
    var selections by remember(request.id) {
        mutableStateOf(request.questions.map { emptyList<String>() })
    }
    var customOn by remember(request.id) {
        mutableStateOf(request.questions.map { false })
    }
    var customText by remember(request.id) {
        mutableStateOf(request.questions.map { "" })
    }

    // Multi-question requests page **one question at a time** (upstream
    // `session-question-dock.tsx:84-119,437,486-533`: `store.tab`, the
    // `{{current}} of {{total}} questions` header, the progress segments and the
    // Back/Next→Submit tray). The pager arithmetic lives in `QuestionPager` so
    // it is plain JVM-tested logic.
    var tab by remember(request.id) { mutableStateOf(0) }
    val total = request.questions.size
    val page = QuestionPager.clamp(tab, total)
    val segments = QuestionPager.segments(
        tab = page,
        answered = request.questions.indices.map { i ->
            QuestionPager.answered(
                selections = selections.getOrElse(i) { emptyList() },
                customActive = customOn.getOrElse(i) { false },
                customText = customText.getOrElse(i) { "" },
            )
        },
    )

    fun select(questionIndex: Int, label: String) {
        val question = request.questions.getOrNull(questionIndex) ?: return
        selections = selections.mapIndexed { i, picked ->
            if (i != questionIndex) {
                picked
            } else if (question.multiple) {
                if (label in picked) picked - label else picked + label
            } else {
                listOf(label)
            }
        }
        // Upstream `pick()`: choosing a real option leaves custom mode.
        if (!question.multiple) {
            customOn = customOn.mapIndexed { i, on -> if (i == questionIndex) false else on }
        }
    }

    fun toggleCustom(questionIndex: Int) {
        val question = request.questions.getOrNull(questionIndex) ?: return
        if (question.multiple) {
            customOn = customOn.mapIndexed { i, on -> if (i == questionIndex) !on else on }
        } else {
            // Single-select: the custom row is a radio alternative — turning
            // it on replaces the option selection (upstream `customOpen`).
            customOn = customOn.mapIndexed { i, on -> if (i == questionIndex) true else on }
            selections = selections.mapIndexed { i, picked ->
                if (i == questionIndex) emptyList() else picked
            }
        }
    }

    fun answers(): List<List<String>> = request.questions.mapIndexed { i, question ->
        val custom = customText.getOrElse(i) { "" }.trim()
        val customActive = customOn.getOrElse(i) { false } && custom.isNotEmpty()
        if (question.multiple) {
            val picked = selections.getOrElse(i) { emptyList() }
            if (customActive) picked + custom else picked
        } else {
            if (customActive) listOf(custom) else selections.getOrElse(i) { emptyList() }.take(1)
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
                // Header row — same `20px 1fr, column-gap 8` grid as the
                // permission dock's `[data-variant="header"]`.
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
                        // `summary()` (`:112-115`): the header title becomes the
                        // page counter as soon as there is more than one question.
                        text = if (QuestionPager.showProgress(total)) {
                            stringResource(
                                R.string.oc_mobile_question_progress,
                                QuestionPager.progressNumber(page, total),
                                total,
                            )
                        } else {
                            stringResource(R.string.oc_question_title)
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
                        QuestionProgressSegments(
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
                    // One page per question (`:437-548` renders only
                    // `questions()[store.tab]`).
                    request.questions.getOrNull(page)?.let { question ->
                        QuestionBlock(
                            question = question,
                            picked = selections.getOrElse(page) { emptyList() },
                            customActive = customOn.getOrElse(page) { false },
                            customValue = customText.getOrElse(page) { "" },
                            enabled = !responding,
                            onSelect = { label -> select(page, label) },
                            onToggleCustom = { toggleCustom(page) },
                            onCustomChange = { value ->
                                customText = customText.mapIndexed { i, old ->
                                    if (i == page) value else old
                                }
                            },
                        )
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
                // Upstream order: Dismiss (ghost) left, then Back/Next, then the
                // primary action rightmost (`ui.common.dismiss` / `ui.common.back`
                // / `ui.common.next` → `ui.common.submit`, `:514-535`).
                QuestionDockButton(
                    label = stringResource(R.string.oc_question_dismiss),
                    variant = QuestionDockButtonVariant.Ghost,
                    enabled = !responding,
                    onClick = onReject,
                )
                if (QuestionPager.canGoBack(page)) {
                    Spacer(Modifier.width(8.dp))
                    QuestionDockButton(
                        label = stringResource(R.string.oc_mobile_question_back),
                        variant = QuestionDockButtonVariant.Neutral,
                        enabled = !responding,
                        onClick = { tab = QuestionPager.back(page, total) },
                    )
                }
                Spacer(Modifier.width(8.dp))
                val last = QuestionPager.isLast(page, total)
                QuestionDockButton(
                    label = stringResource(
                        if (last) R.string.oc_question_submit else R.string.oc_mobile_question_next,
                    ),
                    // `variant={last() ? "submit" : "neutral"}` (`:525-533`).
                    variant = if (last) QuestionDockButtonVariant.Submit
                    else QuestionDockButtonVariant.Neutral,
                    enabled = !responding,
                    onClick = {
                        // `next()` (`:437`): advance, or submit on the last page.
                        if (last) onSubmit(answers()) else tab = QuestionPager.next(page, total)
                    },
                )
            }
        },
    ) { measurables, constraints ->
        val overlapPx = trayOverlap.roundToPx()
        val shell = measurables[0].measure(constraints)
        val tray = measurables[1].measure(constraints)
        // Same negative-margin trick as the permission dock: the tray tucks
        // `overlapPx` under the opaque shell and is placed first so the shell
        // paints over the overlap.
        layout(shell.width, shell.height + tray.height - overlapPx) {
            tray.place(0, shell.height - overlapPx)
            shell.place(0, 0)
        }
    }
}

/** One question: header + text + hint + options (+ custom row). */
@Composable
private fun QuestionBlock(
    question: OpenCodeClient.QuestionRequest.Info,
    picked: List<String>,
    customActive: Boolean,
    customValue: String,
    enabled: Boolean,
    onSelect: (String) -> Unit,
    onToggleCustom: () -> Unit,
    onCustomChange: (String) -> Unit,
) {
    val c = OcTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (question.header.isNotEmpty()) {
            Text(
                text = question.header,
                color = c.textMuted,
                fontSize = 13.sp,
                fontWeight = FontWeight(530),
                lineHeight = 16.sp,
            )
        }
        if (question.question.isNotEmpty()) {
            Text(
                text = question.question,
                color = c.textBase,
                fontSize = 14.sp,
                lineHeight = 21.sp,
            )
        }
        Text(
            text = stringResource(
                if (question.multiple) R.string.oc_question_hint_multi
                else R.string.oc_question_hint_single
            ),
            color = c.textFaint,
            fontSize = 13.sp,
            lineHeight = 16.sp,
        )
        Column(
            modifier = Modifier.padding(top = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for (option in question.options) {
                OptionRow(
                    multi = question.multiple,
                    picked = option.label in picked,
                    label = option.label,
                    description = option.description,
                    enabled = enabled,
                    onClick = { onSelect(option.label) },
                )
            }
            if (question.custom) {
                CustomRow(
                    multi = question.multiple,
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

/** `[data-slot="question-option"]` — mark + label + optional description. */
@Composable
private fun OptionRow(
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
        OptionMark(multi = multi, picked = picked)
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

/** The「自定义…」row: a mark toggling custom mode plus the inline input. */
@Composable
private fun CustomRow(
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
        OptionMark(multi = multi, picked = active)
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

/**
 * `[data-slot="question-option-box"]`: 16 dp, 0.5 dp `borderBase`; radio =
 * circle, checkbox = 4 dp radius. Picked: `iconAccent` border; checkbox adds
 * an `iconAccent` fill + `iconInverse` check, radio a 6 dp `iconAccent` dot.
 */
@Composable
private fun OptionMark(multi: Boolean, picked: Boolean) {
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

private enum class QuestionDockButtonVariant { Ghost, Neutral, Submit }

/**
 * Same `button-v2` language as the permission dock's tray: height 28, radius
 * 6, padding-x 12, 13/530/20; `ghost` transparent, `neutral`
 * `--v2-background-bg-button-neutral` (`button.css:70-75`) and `submit` solid
 * inverse.
 */
@Composable
private fun QuestionDockButton(
    label: String,
    variant: QuestionDockButtonVariant,
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
                    QuestionDockButtonVariant.Ghost -> Color.Transparent
                    QuestionDockButtonVariant.Neutral -> c.buttonNeutral
                    QuestionDockButtonVariant.Submit -> c.inverse
                },
            )
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = when (variant) {
                QuestionDockButtonVariant.Submit -> c.textInverse
                else -> c.textBase
            },
            fontSize = 13.sp,
            fontWeight = FontWeight(530),
            lineHeight = 20.sp,
        )
    }
}

/**
 * `[data-slot="question-progress"]` + `question-progress-segment`
 * (`session-question-dock.tsx:486-502`): one segment per question, the current
 * page `data-active`, an answered page `data-answered`, and a tap jumps to that
 * page (`jump(i)`). 4 dp tall bars on a 12 dp touch row, 4 dp apart.
 */
@Composable
private fun QuestionProgressSegments(
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
