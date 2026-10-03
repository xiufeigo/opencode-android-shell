package com.xiufeigo.opencodeshell.ui.session.context

import android.widget.Toast
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.ServerConfigStore
import com.xiufeigo.opencodeshell.ui.theme.OcTheme
import com.xiufeigo.opencodeshell.v2.InstructionEntry
import com.xiufeigo.opencodeshell.v2.V2Api
import com.xiufeigo.opencodeshell.v2.sessionInstructionEntries
import com.xiufeigo.opencodeshell.v2.sessionInstructionEntryPut
import com.xiufeigo.opencodeshell.v2.sessionInstructionEntryRemove
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Loaded state of the session's instruction entries (`sessionInstructionEntries`).
 * `null` in [entries] is "not loaded / failed" — the section then shows the
 * empty copy plus a retry, never invented rows.
 */
data class InstructionsState(
    val entries: List<InstructionEntry>? = null,
    val loading: Boolean = false,
)

/** Behaviour half of [SessionInstructionsSection]: list + put + remove over one V2 handle. */
class InstructionsHost internal constructor(
    initial: InstructionsState,
    private val list: suspend () -> List<InstructionEntry>,
    private val put: suspend (String, Any?) -> Unit,
    private val remove: suspend (String) -> Unit,
) {
    var state by mutableStateOf(initial)
        private set

    fun reload(scope: CoroutineScope) {
        state = state.copy(loading = true)
        scope.launch {
            val rows = runCatching { list() }.getOrNull()
            state = InstructionsState(entries = rows)
        }
    }

    fun save(scope: CoroutineScope, key: String, value: Any?, onDone: (Boolean) -> Unit) {
        scope.launch {
            val ok = runCatching { put(key.trim(), value) }.isSuccess
            if (ok) reload(scope)
            onDone(ok)
        }
    }

    fun delete(scope: CoroutineScope, key: String, onDone: (Boolean) -> Unit) {
        scope.launch {
            val ok = runCatching { remove(key) }.isSuccess
            if (ok) reload(scope)
            onDone(ok)
        }
    }
}

/**
 * Self-served entries host — same read-only fallback pattern as
 * `SummaryServiceSource.rememberSummaryCatalogs` (`ChatViewModel` exposes
 * nothing for these experimental routes).
 */
@Composable
fun rememberInstructionsHost(sessionID: String?): InstructionsHost? {
    val context = LocalContext.current
    val serverStore = remember(context) { ServerConfigStore(context).also { it.restore() } }
    val scope = rememberCoroutineScope()
    val host = remember(serverStore, sessionID) {
        if (sessionID.isNullOrBlank()) return@remember null
        fun api(): V2Api? = serverStore.server?.let { server ->
            V2Api.forServer(server.url, server.username, server.password)
        }
        InstructionsHost(
            initial = InstructionsState(loading = true),
            list = {
                withContext(Dispatchers.IO) {
                    api()?.sessionInstructionEntries(sessionID) ?: error("no api")
                }
            },
            put = { key, value ->
                withContext(Dispatchers.IO) {
                    val handle = api() ?: error("no api")
                    handle.sessionInstructionEntryPut(sessionID, key, value)
                }
            },
            remove = { key ->
                withContext(Dispatchers.IO) {
                    val handle = api() ?: error("no api")
                    handle.sessionInstructionEntryRemove(sessionID, key)
                }
            },
        )
    }
    LaunchedEffect(serverStore, sessionID) {
        host?.reload(scope)
    }
    return host
}

/**
 * 「指令」 section of the session context tab — the API-managed instruction
 * entries (`schema/src/instruction-entry.ts`): one expandable row per entry
 * (tap to edit the value / delete), plus an add row that edits key + value.
 *
 * Upstream renders no screen for these routes; the layout borrows the tab's own
 * expandable [com.xiufeigo.opencodeshell.ui.session.context.RawMessageRow]
 * treatment so the list reads as part of the tab. Key grammar is
 * [SessionInstructionsModel.isValidKey] (`^[a-z0-9][a-z0-9._-]*$`).
 */
@Composable
fun SessionInstructionsSection(
    host: InstructionsHost?,
    modifier: Modifier = Modifier,
    rowHeight: Dp = 32.dp,
) {
    val c = OcTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val shape = RoundedCornerShape(6.dp)
    var expandedKey by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    val failedText = stringResource(R.string.oc_session_instructions_failed)

    fun toastOk(ok: Boolean) {
        if (!ok) Toast.makeText(context, failedText, Toast.LENGTH_SHORT).show()
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(R.string.oc_session_instructions_title),
            color = c.textBase,
            fontSize = 13.sp,
            fontWeight = FontWeight(530),
            lineHeight = 20.sp,
        )
        if (host == null) return@Column
        val rows = SessionInstructionsModel.rows(host.state.entries.orEmpty())
        // No inner scroller: the section is one item of the tab's LazyColumn,
        // so the entries join the tab's single scroll path (stats → prompt →
        // entries → raw messages → export). A capped inner box fragments short
        // viewports into competing scrollers and strands the rows below it.
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (rows.isEmpty() && !host.state.loading) {
                Text(
                    text = stringResource(R.string.oc_session_instructions_empty),
                    color = c.textMuted,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
            rows.forEach { row ->
                InstructionEntryRow(
                    row = row,
                    expanded = expandedKey == row.key,
                    rowHeight = rowHeight,
                    onToggle = {
                        adding = false
                        expandedKey = if (expandedKey == row.key) null else row.key
                    },
                    onSave = { text ->
                        val value = SessionInstructionsModel.parseValue(text)
                        host.save(scope, row.key, value) { toastOk(it) }
                        expandedKey = null
                    },
                    onDelete = {
                        host.delete(scope, row.key) { toastOk(it) }
                        expandedKey = null
                    },
                )
            }
            if (adding) {
                InstructionAddRow(
                    rowHeight = rowHeight,
                    onCancel = { adding = false },
                    onSave = { key, text ->
                        if (!SessionInstructionsModel.canSave(key)) return@InstructionAddRow
                        val value = SessionInstructionsModel.parseValue(text)
                        host.save(scope, key, value) { toastOk(it) }
                        adding = false
                    },
                )
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(rowHeight)
                        .clip(shape)
                        .clickable(role = Role.Button) {
                            expandedKey = null
                            adding = true
                        }
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.oc_session_instructions_add),
                        color = c.textFaint,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                    )
                }
            }
        }
    }
}

/** One entry: collapsed `key · value` line, expanded value editor + delete. */
@Composable
private fun InstructionEntryRow(
    row: SessionInstructionsModel.Row,
    expanded: Boolean,
    rowHeight: Dp,
    onToggle: () -> Unit,
    onSave: (String) -> Unit,
    onDelete: () -> Unit,
) {
    val c = OcTheme.colors
    val shape = RoundedCornerShape(6.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.layer01)
            .border(0.5.dp, c.borderMuted, shape),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(rowHeight)
                .clickable(role = Role.Button, onClick = onToggle)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = row.key,
                color = c.textBase,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = row.text,
                color = c.textMuted,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        if (expanded) {
            InstructionEditor(
                initialKey = row.key,
                initialValue = row.text,
                keyEditable = false,
                onSave = onSave,
                onDelete = onDelete,
            )
        }
    }
}

/** The add row: expands into a key + value editor. */
@Composable
private fun InstructionAddRow(
    rowHeight: Dp,
    onCancel: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    val c = OcTheme.colors
    val shape = RoundedCornerShape(6.dp)
    var key by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.layer01)
            .border(0.5.dp, c.borderMuted, shape),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(rowHeight)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.oc_session_instructions_add),
                color = c.textBase,
                fontSize = 12.sp,
                fontWeight = FontWeight(530),
                lineHeight = 16.sp,
            )
        }
        InstructionFields(key = key, value = value, keyEditable = true, onKey = { key = it }, onValue = { value = it })
        val keyValid = SessionInstructionsModel.canSave(key)
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (key.isNotBlank() && !keyValid) {
                Text(
                    text = stringResource(R.string.oc_session_instructions_key_invalid),
                    color = c.textMuted,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            EditorButton(
                label = stringResource(R.string.oc_common_cancel),
                enabled = true,
                onClick = onCancel,
            )
            EditorButton(
                label = stringResource(R.string.oc_common_save),
                enabled = keyValid,
                onClick = { onSave(key, value) },
            )
        }
    }
}

/** Expanded value editor of one entry (key is identity — not editable). */
@Composable
private fun InstructionEditor(
    initialKey: String,
    initialValue: String,
    keyEditable: Boolean,
    onSave: (String) -> Unit,
    onDelete: () -> Unit,
) {
    var value by remember(initialKey) { mutableStateOf(initialValue) }
    InstructionFields(
        key = initialKey,
        value = value,
        keyEditable = keyEditable,
        onKey = {},
        onValue = { value = it },
    )
    Row(
        modifier = Modifier.fillMaxWidth().padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EditorButton(
            label = stringResource(R.string.oc_session_instructions_delete),
            enabled = true,
            onClick = onDelete,
        )
        Spacer(Modifier.weight(1f))
        EditorButton(
            label = stringResource(R.string.oc_common_save),
            enabled = true,
            onClick = { onSave(value) },
        )
    }
}

@Composable
private fun InstructionFields(
    key: String,
    value: String,
    keyEditable: Boolean,
    onKey: (String) -> Unit,
    onValue: (String) -> Unit,
) {
    val c = OcTheme.colors
    val shape = RoundedCornerShape(6.dp)
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (keyEditable) {
            FieldBox(shape) {
                if (key.isEmpty()) {
                    Text(
                        text = stringResource(R.string.oc_session_instructions_key),
                        color = c.textFaint,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                    )
                }
                BasicTextField(
                    value = key,
                    onValueChange = onKey,
                    singleLine = true,
                    textStyle = TextStyle(
                        color = c.textBase,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        fontFamily = FontFamily.Monospace,
                    ),
                    cursorBrush = SolidColor(c.textBase),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        FieldBox(shape) {
            if (value.isEmpty()) {
                Text(
                    text = stringResource(R.string.oc_session_instructions_value),
                    color = c.textFaint,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValue,
                textStyle = TextStyle(
                    color = c.textBase,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    fontFamily = FontFamily.Monospace,
                ),
                cursorBrush = SolidColor(c.textBase),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            )
        }
    }
}

@Composable
private fun FieldBox(
    shape: Shape,
    content: @Composable () -> Unit,
) {
    val c = OcTheme.colors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.bgBase)
            .border(0.5.dp, c.borderBase, shape)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        content()
    }
}

@Composable
private fun EditorButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    val c = OcTheme.colors
    val shape = RoundedCornerShape(4.dp)
    Box(
        modifier = Modifier
            .alpha(if (enabled) 1f else 0.5f)
            .height(24.dp)
            .clip(shape)
            .background(c.layer02)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = c.textBase,
            fontSize = 12.sp,
            fontWeight = FontWeight(530),
            lineHeight = 16.sp,
        )
    }
}
