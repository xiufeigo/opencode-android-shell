package com.xiufeigo.opencodeshell.ui.session.context

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.ui.session.export.rememberSessionExportSaver
import com.xiufeigo.opencodeshell.ui.theme.OcTheme

/**
 * Session **context** tab (B2) — the body of upstream's `SessionContextTab`
 * (`_upstream/packages/app/src/session/files/session-context-tab.tsx`), mounted
 * by the right panel's third tab (`session-side-panel.tsx:345-373`) and, on
 * mobile, as the whole `usage` view (`session/screen.tsx:307-309`).
 *
 * Upstream lays the sixteen [SessionContextModel.Stat] rows in a
 * `grid-cols-1 @[32rem]:grid-cols-2 gap-4` (`:277`), followed by the optional
 * system-prompt block (`:283-292`) and the raw-message accordion (`:294-312`).
 * The composable mirrors that order, projects nothing itself and keeps every
 * value a parameter, exactly like its sibling
 * [com.xiufeigo.opencodeshell.ui.session.summary.SessionSummaryPanel]: it never
 * reaches into a ViewModel and never fetches — the caller hands it the payloads
 * it already pulled (`GET /session/:id` + `GET /session/:id/message`, projected
 * through [SessionContextModel]) plus the provider catalog.
 *
 * @param snapshot projection of those payloads; `null` (nothing loaded yet)
 *   renders [SessionContextModel.EMPTY] — the sixteen rows, every value `—`.
 * @param mobile mobile sheet density: 44 dp rows instead of 32 dp, 12 dp insets.
 */
@Composable
fun SessionContextPanel(
    snapshot: SessionContextModel.Snapshot?,
    modifier: Modifier = Modifier,
    mobile: Boolean = false,
) {
    val c = OcTheme.colors
    val shown = snapshot ?: SessionContextModel.EMPTY
    val shape = RoundedCornerShape(6.dp)
    val inset = if (mobile) 12.dp else 16.dp
    val rowHeight = if (mobile) 44.dp else 32.dp
    // Expansion state of the raw rows, keyed by the model's stable row id.
    var expandedIds by remember { mutableStateOf(emptySet<String>()) }

    BoxWithConstraints(modifier) {
        val columns = SessionContextModel.columns(maxWidth.value.toInt())
        val statRows = remember(shown.stats, columns) { shown.stats.chunked(columns) }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = inset,
                end = inset,
                top = inset,
                // `pb-6` / `md:pb-10` (`:276`).
                bottom = inset * 2,
            ),
        ) {
            // ---------------------------------------------------- stat grid
            items(statRows) { rowStats ->
                StatRow(rowStats, columns)
            }

            // ---------------------------------------------- system prompt
            val prompt = shown.systemPrompt
            if (prompt != null) {
                item {
                    SectionLabel(stringResource(R.string.oc_context_system_prompt_title))
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(shape)
                            .background(c.layer01)
                            .border(0.5.dp, c.borderMuted, shape)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        SelectionContainer {
                            Text(
                                text = prompt,
                                color = c.textMuted,
                                fontSize = 12.sp,
                                lineHeight = 16.sp,
                            )
                        }
                    }
                    Spacer(Modifier.height(if (mobile) 20.dp else 24.dp))
                }
            }

            // ------------------------------------------------ raw messages
            item {
                // Upstream's header row is `flex items-center justify-between`
                // with the export button on the trailing edge (`:296-307`).
                val export = shown.export
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SectionLabel(stringResource(R.string.oc_context_raw_messages_title))
                    if (export != null) {
                        Spacer(Modifier.weight(1f))
                        ExportSessionButton(export)
                    }
                }
            }
            itemsIndexed(
                items = shown.messages,
                // The index keeps the key unique even if a server repeats an id
                // (`LazyColumn` rejects duplicate keys); expansion still keys off
                // the message id, which is what the row carries.
                key = { index, message -> "$index\u0000${message.id}" },
            ) { _, message ->
                RawMessageRow(
                    message = message,
                    expanded = message.id in expandedIds,
                    onToggle = {
                        expandedIds = if (message.id in expandedIds) {
                            expandedIds - message.id
                        } else {
                            expandedIds + message.id
                        }
                    },
                    shape = shape,
                    rowHeight = rowHeight,
                )
            }
        }
    }
}

/**
 * `context.export.session` (`session-context-tab.tsx:296-307`): the small ghost
 * button of the raw-messages header. Upstream re-fetches the session from this
 * tab; the [SessionContextModel.Export] this panel was projected from already
 * holds the same two payloads, so the button hands the string straight to the
 * SAF saver the `⋮ → 导出会话` path uses (`rememberSessionExportSaver`) and toasts
 * exactly like it (`oc_export_success` / `oc_export_failed`; a cancelled dialog
 * stays silent, upstream's `saveSessionExport` contract).
 *
 * Upstream paints a `download` icon next to the label; the native sprite has no
 * download glyph and this wave's scope adds no drawable, so the label alone
 * carries the affordance (and its accessibility name).
 */
@Composable
private fun ExportSessionButton(export: SessionContextModel.Export) {
    val c = OcTheme.colors
    val context = LocalContext.current
    val saver = rememberSessionExportSaver()
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(role = Role.Button) {
                saver.save(export.filename, export.json) { ok ->
                    val text = if (ok) {
                        context.getString(R.string.oc_export_success, export.filename)
                    } else {
                        context.getString(R.string.oc_export_failed)
                    }
                    Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
                }
            }
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.oc_context_export_session),
            color = c.textFaint,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            maxLines = 1,
        )
    }
}

/** One grid line: [columns] weighted cells, a short line padded with spacers. */
@Composable
private fun StatRow(rowStats: List<SessionContextModel.Stat>, columns: Int) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        rowStats.forEach { stat ->
            StatCell(stat, Modifier.weight(1f))
        }
        repeat(columns - rowStats.size) {
            Spacer(Modifier.weight(1f))
        }
    }
}

/** Upstream `Stat` (`:24-31`): 12 px weak label over a 12 px strong value. */
@Composable
private fun StatCell(stat: SessionContextModel.Stat, modifier: Modifier = Modifier) {
    val c = OcTheme.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(labelRes(stat.kind)),
            color = c.textFaint,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = stat.value,
            color = c.textBase,
            fontSize = 12.sp,
            lineHeight = 16.sp,
        )
    }
}

/** The 12 px weak section label of `systemPrompt.title` / `rawMessages.title`. */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        color = OcTheme.colors.textFaint,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

/**
 * One raw message (upstream `RawMessage`, `:54-81`): `type • id` and
 * `time.created` on a [rowHeight]-tall trigger, the two-space JSON underneath
 * while expanded. The JSON is capped at [MAX_RAW_CHARS] characters — one
 * assistant message can carry megabytes of tool output, and an uncapped `Text`
 * would stall the panel.
 */
@Composable
private fun RawMessageRow(
    message: SessionContextModel.RawMessage,
    expanded: Boolean,
    onToggle: () -> Unit,
    shape: RoundedCornerShape,
    rowHeight: Dp,
) {
    val c = OcTheme.colors
    val body = remember(message.json) { message.json.take(MAX_RAW_CHARS) }
    val truncated = body.length < message.json.length
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.layer01)
            .padding(bottom = 4.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = rowHeight)
                .clickable(role = Role.Button, onClick = onToggle)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = message.title,
                color = c.textMuted,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = message.time,
                color = c.textFaint,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                maxLines = 1,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (expanded) "\u25be" else "\u25b8",
                color = c.iconMuted,
                fontSize = 12.sp,
                lineHeight = 16.sp,
            )
        }
        if (expanded) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .clip(shape)
                    .background(c.bgBase)
                    .border(0.5.dp, c.borderMuted, shape)
                    .padding(12.dp),
            ) {
                SelectionContainer {
                    Text(
                        text = body,
                        color = c.textMuted,
                        fontFamily = OcTheme.monoFont,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                    )
                }
                if (truncated) {
                    Text(
                        text = stringResource(R.string.oc_context_raw_truncated),
                        color = c.textFaint,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

/** Label resource of one stat, `context.stats.*` in upstream order (`:173-193`). */
private fun labelRes(kind: SessionContextModel.Kind): Int = when (kind) {
    SessionContextModel.Kind.SESSION -> R.string.oc_context_stats_session
    SessionContextModel.Kind.MESSAGES -> R.string.oc_context_stats_messages
    SessionContextModel.Kind.PROVIDER -> R.string.oc_context_stats_provider
    SessionContextModel.Kind.MODEL -> R.string.oc_context_stats_model
    SessionContextModel.Kind.LIMIT -> R.string.oc_context_stats_limit
    SessionContextModel.Kind.TOTAL_TOKENS -> R.string.oc_context_stats_total_tokens
    SessionContextModel.Kind.USAGE -> R.string.oc_context_stats_usage
    SessionContextModel.Kind.INPUT_TOKENS -> R.string.oc_context_stats_input_tokens
    SessionContextModel.Kind.OUTPUT_TOKENS -> R.string.oc_context_stats_output_tokens
    SessionContextModel.Kind.REASONING_TOKENS -> R.string.oc_context_stats_reasoning_tokens
    SessionContextModel.Kind.CACHE_TOKENS -> R.string.oc_context_stats_cache_tokens
    SessionContextModel.Kind.USER_MESSAGES -> R.string.oc_context_stats_user_messages
    SessionContextModel.Kind.ASSISTANT_MESSAGES -> R.string.oc_context_stats_assistant_messages
    SessionContextModel.Kind.TOTAL_COST -> R.string.oc_context_stats_total_cost
    SessionContextModel.Kind.SESSION_CREATED -> R.string.oc_context_stats_session_created
    SessionContextModel.Kind.LAST_ACTIVITY -> R.string.oc_context_stats_last_activity
}

/** Hard cap of the rendered raw JSON (see [RawMessageRow]). */
private const val MAX_RAW_CHARS = 20_000
