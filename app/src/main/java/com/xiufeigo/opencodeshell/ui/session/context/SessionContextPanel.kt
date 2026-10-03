package com.xiufeigo.opencodeshell.ui.session.context

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.ui.session.export.EXPORT_FETCH_TIMEOUT_MS
import com.xiufeigo.opencodeshell.ui.session.export.ExportFeedback
import com.xiufeigo.opencodeshell.ui.session.export.ExportStageResult
import com.xiufeigo.opencodeshell.ui.session.export.ImportFailure
import com.xiufeigo.opencodeshell.ui.session.export.ImportFinish
import com.xiufeigo.opencodeshell.ui.session.export.SessionExport
import com.xiufeigo.opencodeshell.ui.session.export.SessionExportKind
import com.xiufeigo.opencodeshell.ui.session.export.rememberSessionExportSaver
import com.xiufeigo.opencodeshell.ui.session.export.rememberSessionImporter
import com.xiufeigo.opencodeshell.ui.session.export.rememberSessionExportSource
import com.xiufeigo.opencodeshell.ui.session.export.sanitizeParam
import com.xiufeigo.opencodeshell.ui.session.export.transferFrame
import com.xiufeigo.opencodeshell.ui.session.export.withExportDeadline
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
 *   The `ctx()` half of that projection (provider / model / limit / tokens /
 *   last activity) is derived from `ChatViewModel.activeContextRows`, the live
 *   read the header ring's numerator comes from, so it is not a one-shot: the
 *   frame half (counts / raw list / export) is what the host re-reads on
 *   「刷新」 and at every settled turn (`SessionContextLoad`).
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

            // ----------------------------------------------- instructions
            // The API-managed `api/<key>` entries (`SessionInstructionsModel`).
            // Rendered only once the raw session half has landed, because the
            // routes key off the session id.
            val instructionsID = shown.sessionID
            if (instructionsID != null) {
                item {
                    val host = rememberInstructionsHost(instructionsID)
                    SessionInstructionsSection(host = host, rowHeight = rowHeight)
                    Spacer(Modifier.height(if (mobile) 20.dp else 24.dp))
                }
            }

            // ------------------------------------------------ raw messages
            item {
                // Upstream's header row is `flex items-center justify-between`
                // with the export button on the trailing edge (`:296-307`) —
                // and upstream never hides it. So neither do we: without
                // payloads the button renders disabled and the hint below says
                // why (waiting / load failure); arrivals flip it enabled
                // automatically because `shown` is re-projected per payload
                // change, no click-through or retry plumbing needed here.
                val export = shown.export
                val exportState = shown.exportState
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SectionLabel(stringResource(R.string.oc_context_raw_messages_title))
                        Spacer(Modifier.weight(1f))
                        ExportSessionButton(export)
                    }
                    if (exportState != SessionContextModel.ExportState.READY) {
                        Text(
                            text = if (exportState == SessionContextModel.ExportState.FAILED) {
                                stringResource(
                                    R.string.oc_context_export_load_failed,
                                    shown.exportError.orEmpty(),
                                )
                            } else {
                                stringResource(R.string.oc_context_export_waiting)
                            },
                            color = if (exportState == SessionContextModel.ExportState.FAILED) {
                                c.stateDangerFg
                            } else {
                                c.textFaint
                            },
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                    // The backing read stopped at the message drain's page cap
                    // (`V2Api.MessageDrain.truncated`, carried on
                    // `SessionExport.Frame` and projected by
                    // `SessionContextModel`): the export holds the first N pages
                    // of the transcript, not all of it. Only meaningful once the
                    // payload is in hand — while loading, the hint above already
                    // says the rows are unavailable. Same slot, own line, so the
                    // two can never overwrite each other.
                    if (exportState == SessionContextModel.ExportState.READY &&
                        shown.exportTruncated
                    ) {
                        Text(
                            text = stringResource(
                                R.string.oc_context_export_truncated,
                                shown.exportTruncatedPages,
                            ),
                            color = c.stateWarningFg,
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
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
 * button of the raw-messages header, now a **four-row** menu — the three save
 * formats below plus Import, in that render order.
 *
 * This is the only entry that offers all four ([SessionContextModel.ExportRow]
 * is their order and their gates; `SessionContextExportMenuTest` pins the
 * pairing). The header `⋮`'s 「导出会话」 is deliberately a *single* row that
 * goes straight to the SAF saver (intentional deviation, `research/HANDOFF.md`)
 * — a v35 device pass read that menu and reported the four rows as lost.
 *
 * - **Export session** keeps upstream's hand-rolled `{info, messages}` pair
 *   (the payloads this tab was projected from, [SessionContextModel.Export]);
 * - **官方格式导出** / **脱敏导出** fetch the official envelope instead —
 *   `GET /api/experimental/session/{id}/export` ([SessionExportKind.OFFICIAL]
 *   with `sanitize` omitted, [SessionExportKind.SANITIZED] with `?sanitize=true`,
 *   see [sanitizeParam]). Measured: the default route answers element-for-element
 *   what the hand-rolled pair writes, so the official kind's win is one
 *   round-trip for the whole transcript (no cursor paging) and the sanitized
 *   kind's `[redacted:…:id]` markers exist only there.
 *
 * All three go through the same SAF saver, and **none of them is silent**:
 * the save goes through the saver's scope overload and is reported through
 * [ExportFeedback], so a picker save (`oc_export_success`), a `Download/`
 * bypass write (`oc_export_saved_downloads`), a dismissed picker
 * (`oc_export_cancelled`) and an exhausted save (`oc_export_failed`) each say
 * which branch ran. The boolean overload upstream mirrors would keep the
 * dismiss and the bypass silent/bogus here (v8-C report). The two route-backed
 * kinds need the session id and their fetch is deadline-bounded, so a wedged
 * route reports (`oc_export_fetch_failed` with the reason) instead of leaving
 * `busy` up with a closed menu and nothing said.
 *
 * The button itself always renders **and is always tappable**: what a missing
 * `[export]` (nothing loaded yet, or the backing fetch failed) takes down is
 * the three payload-backed rows, not the menu — the header's hint line carries
 * the reason, and [SessionContextModel.exportRowEnabled] is the one place that
 * rule lives. Gating the *trigger* instead (v9-41) took Import down with the
 * payload: Import reads a file the user picks, needs no payload and no session
 * id, and has no reason to be disabled by a failed read.
 */
@Composable
private fun ExportSessionButton(export: SessionContextModel.Export?) {
    val c = OcTheme.colors
    val context = LocalContext.current
    val saver = rememberSessionExportSaver()
    val source = rememberSessionExportSource()
    val importer = rememberSessionImporter()
    val scope = rememberCoroutineScope()
    var menuOpen by remember { mutableStateOf(false) }
    // Two own-attempt flags, never one: an export attempt must not gate Import
    // ([SessionContextModel.exportRowEnabled] takes them separately for the
    // same reason).
    var exportBusy by remember { mutableStateOf(false) }
    var importBusy by remember { mutableStateOf(false) }
    val ready = export != null

    fun toast(resId: Int, arg: String?) {
        val text = if (arg == null) context.getString(resId) else context.getString(resId, arg)
        // Outcome copy (saved / dismissed / failed, with a filename or the
        // server's reason) needs reading time — long, not short.
        Toast.makeText(context, text, Toast.LENGTH_LONG).show()
    }

    /** The menu's enablement, straight from the pinned pure rule. */
    fun rowEnabled(row: SessionContextModel.ExportRow): Boolean = SessionContextModel.exportRowEnabled(
        row = row,
        payloadReady = ready,
        sessionID = export?.sessionID,
        exportBusy = exportBusy,
        importBusy = importBusy,
    )

    // One finished attempt → exactly one message. `ExportFeedback` is a `when`
    // over the saver's outcomes with no `else`, so a dismiss can never go back
    // to the silent branch and a `Download/` bypass can never read as a normal
    // save. Releasing the export flag here is what keeps the menu usable
    // afterwards. [reason] rides the failure copy: the route's own message, or
    // the read deadline.
    fun report(feedback: ExportFeedback, filename: String, reason: String? = null) {
        exportBusy = false
        when (feedback) {
            ExportFeedback.SAVED -> toast(R.string.oc_export_success, filename)
            ExportFeedback.SAVED_TO_DOWNLOADS ->
                toast(R.string.oc_export_saved_downloads, filename)
            ExportFeedback.CANCELLED -> toast(R.string.oc_export_cancelled, null)
            ExportFeedback.FAILED -> reason?.takeIf { it.isNotBlank() }
                ?.let { toast(R.string.oc_export_fetch_failed, it) }
                ?: toast(R.string.oc_export_failed, null)
        }
    }

    fun save(kind: SessionExportKind) {
        if (exportBusy) return
        menuOpen = false
        exportBusy = true
        val current = export
        if (kind == SessionExportKind.ASSEMBLED) {
            if (current == null) {
                // Registered as a row gate (`exportRowEnabled`), so this is the
                // belt to those braces: a payload that vanished between the
                // render and the tap still reports instead of returning mute.
                report(ExportFeedback.FAILED, "")
                return
            }
            saver.save(current.filename, current.json, scope) { finish ->
                report(ExportFeedback.of(finish), current.filename)
            }
            return
        }
        val sessionID = current?.sessionID
        // No payload, or a payload without the raw session id the route needs:
        // report instead of returning mute (the ASSEMBLED row above is the
        // payload-only case).
        if (current == null || sessionID == null) {
            report(ExportFeedback.FAILED, current?.filename.orEmpty())
            return
        }
        scope.launch {
            // The v9 run watched this read hold the screen with the menu closed
            // and no hint at all: the deadline turns "still waiting" into a
            // reported failure with its own reason.
            val transfer = try {
                withExportDeadline(scope) { source.fetch(sessionID, kind.sanitizeParam()) }
            } catch (_cancelled: CancellationException) {
                // The attempt died with its scope. Report so `exportBusy` is
                // released: a flag left up disables these three rows for the
                // rest of the composition, which is the v9-41 shape of the bug.
                report(ExportFeedback.CANCELLED, current.filename)
                return@launch
            }
            when (transfer) {
                ExportStageResult.TimedOut -> {
                    report(
                        ExportFeedback.FAILED,
                        current.filename,
                        context.getString(
                            R.string.oc_export_fetch_timeout,
                            (EXPORT_FETCH_TIMEOUT_MS / 1000L).toInt(),
                        ),
                    )
                    return@launch
                }
                is ExportStageResult.Failed -> {
                    report(ExportFeedback.FAILED, current.filename, transfer.cause.message)
                    return@launch
                }
                is ExportStageResult.Ok -> {
                    val data = transfer.value
                    if (data == null) {
                        report(ExportFeedback.FAILED, current.filename)
                        return@launch
                    }
                    saver.save(
                        current.filename,
                        SessionExport.serialize(SessionExport.transferFrame(data)),
                        scope,
                    ) { finish ->
                        report(ExportFeedback.of(finish), current.filename)
                    }
                }
            }
        }
    }

    Box {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                // Dimmed while the payload is missing — that is what the three
                // export rows below are gated on — but still tappable: the menu
                // is the only way to reach Import.
                .alpha(if (ready) 1f else 0.4f)
                .clickable(role = Role.Button) { menuOpen = true }
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
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            // v11: M3's default menu surface (`surfaceContainer`) is a *light*
            // translucent tint that the shell's own palette never re-themes, so
            // in the dark theme the rows' `textBase`/`textFaint` landed on a
            // near-white panel. The opaque layer token + zero tonal elevation is
            // the same pairing the other shell menus use
            // (`NewSessionScreen`/`SettingsComponents`), and the hairline keeps
            // the edge readable against `bgBase`.
            shape = RoundedCornerShape(6.dp),
            containerColor = c.layer01,
            tonalElevation = 0.dp,
            border = BorderStroke(0.5.dp, c.borderMuted),
        ) {
            ExportMenuItem(
                label = stringResource(R.string.oc_context_export_session),
                enabled = rowEnabled(SessionContextModel.ExportRow.ASSEMBLED),
                onClick = { save(SessionExportKind.ASSEMBLED) },
            )
            ExportMenuItem(
                label = stringResource(R.string.oc_session_export_official),
                enabled = rowEnabled(SessionContextModel.ExportRow.OFFICIAL),
                onClick = { save(SessionExportKind.OFFICIAL) },
            )
            ExportMenuItem(
                label = stringResource(R.string.oc_session_export_sanitized),
                enabled = rowEnabled(SessionContextModel.ExportRow.SANITIZED),
                onClick = { save(SessionExportKind.SANITIZED) },
            )
            ExportMenuItem(
                label = stringResource(R.string.oc_session_import),
                // Never gated by the payload, the session id or an export
                // attempt — only by its own (v9-41).
                enabled = rowEnabled(SessionContextModel.ExportRow.IMPORT),
                onClick = {
                    menuOpen = false
                    importBusy = true
                    // The scope overload, not the boolean one: it bounds the
                    // picker, the file read and the route, and answers every
                    // one of them, so `importBusy` can never stay up.
                    importer.importSession(scope) { finish ->
                        importBusy = false
                        val text: String? = when (finish) {
                            is ImportFinish.Imported ->
                                context.getString(R.string.oc_session_import_success, finish.sessionId)
                            // Upstream stays silent on a dismissed picker; the
                            // row is released either way.
                            ImportFinish.Cancelled -> null
                            is ImportFinish.Failed -> importFailureCopy(context, finish)
                        }
                        // Same lifetime as the export reports above: an import's
                        // success/failure is an outcome, not a hint.
                        text?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
                    }
                },
            )
        }
    }
}

@Composable
private fun ExportMenuItem(label: String, enabled: Boolean, onClick: () -> Unit) {
    val c = OcTheme.colors
    DropdownMenuItem(
        text = {
            Text(
                text = label,
                color = if (enabled) c.textBase else c.textFaint,
                fontSize = 13.sp,
                lineHeight = 20.sp,
            )
        },
        enabled = enabled,
        onClick = onClick,
    )
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

/**
 * The one line one failed import shows, picked from
 * [com.xiufeigo.opencodeshell.ui.session.export.ImportFailure]: a file that
 * cannot be read, a file that is not a transfer, the hand-rolled export's own
 * shape, a server refusal (with the server's words), an unreachable route, a
 * timeout, or a missing server. It replaced a single `oc_session_import_failed`
 * for all of them — a device report could not tell a bad file from a 4xx.
 *
 * The same mapping lives in `ui/home/HomeRowActions.kt` (the other import call
 * site, which navigates to the created session); the two must stay in step.
 *
 * v39 reuses **this** copy from the third call site, the header ⋮ menu's
 * 「导入会话…」 row (`ui/session/SessionScreen.kt`) — hence `internal`: a third
 * hand-kept copy of the eight arms is exactly the drift this KDoc warns about.
 */
internal fun importFailureCopy(
    context: android.content.Context,
    finish: ImportFinish.Failed,
): String = when (finish.reason) {
    ImportFailure.TIMEOUT -> context.getString(R.string.oc_session_import_timeout)
    ImportFailure.NETWORK -> context.getString(R.string.oc_session_import_offline)
    ImportFailure.FILE_UNREADABLE -> context.getString(R.string.oc_session_import_unreadable)
    ImportFailure.FILE_FORMAT -> context.getString(R.string.oc_session_import_bad_file)
    ImportFailure.FILE_ASSEMBLED -> context.getString(R.string.oc_session_import_assembled)
    ImportFailure.SERVER -> context.getString(R.string.oc_session_import_rejected, finish.detail.orEmpty())
    ImportFailure.NO_SERVER -> context.getString(R.string.oc_session_import_no_server)
    ImportFailure.UNKNOWN -> context.getString(R.string.oc_session_import_failed)
}
