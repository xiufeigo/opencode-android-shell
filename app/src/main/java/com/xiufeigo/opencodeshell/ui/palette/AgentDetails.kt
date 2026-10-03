package com.xiufeigo.opencodeshell.ui.palette

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.ServerConfigStore
import com.xiufeigo.opencodeshell.ui.overlay.DialogScrim
import com.xiufeigo.opencodeshell.ui.overlay.dialogCardBounds
import com.xiufeigo.opencodeshell.ui.overlay.rememberDialogScrimState
import com.xiufeigo.opencodeshell.ui.theme.OcTheme
import com.xiufeigo.opencodeshell.ui.theme.ocShadow
import com.xiufeigo.opencodeshell.v2.V2Api
import com.xiufeigo.opencodeshell.v2.agent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 「View agent details」 — the palette host of upstream's settings agent pages
 * (`packages/app/src/settings/`, `GET /api/agent/{agentID}` via [V2Api.agent]).
 * The list is `GET /api/agent` (the catalog `ChatViewModel` already reads);
 * selecting a row runs the single-agent route and shows its `description` +
 * `system` block, so the write-free detail endpoint finally has a UI.
 */
object AgentDetails {

    /** One list row (`schema/src/agent.ts:29-51` flattened for the picker). */
    data class Row(
        val id: String,
        val name: String,
        val mode: String,
        val description: String,
        val hidden: Boolean,
    )

    /** The catalog projection the picker renders (order = server order). */
    fun rows(infos: List<V2Api.AgentInfo>): List<Row> = infos.map {
        Row(
            id = it.id,
            name = it.name,
            mode = it.mode,
            description = it.description,
            hidden = it.hidden,
        )
    }
}

/** Self-serve catalog read for the dialog (see [AgentDetails] / `SummaryServiceSource`). */
@Composable
fun AgentDetailsDialog(onClose: () -> Unit) {
    val c = OcTheme.colors
    val shape = OcTheme.shapes.xl
    val context = androidx.compose.ui.platform.LocalContext.current
    val serverStore = remember(context) { ServerConfigStore(context).also { it.restore() } }

    var rows by remember { mutableStateOf<List<AgentDetails.Row>?>(null) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var loadFailed by remember { mutableStateOf(false) }

    LaunchedEffect(serverStore) {
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                val server = serverStore.server ?: return@runCatching null
                val api = V2Api.forServer(server.url, server.username, server.password)
                    ?: return@runCatching null
                AgentDetails.rows(api.agents())
            }.getOrNull()
        }
        rows = loaded
        loadFailed = loaded == null
    }

    val scrim = rememberDialogScrimState()
    Box(modifier = Modifier.fillMaxSize()) {
        DialogScrim(scrim, onClose)
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 32.dp)
                .fillMaxWidth()
                .widthIn(max = 360.dp)
                .clip(shape)
                .background(c.bgBase)
                .border(0.5.dp, c.borderBase, shape)
                .ocShadow(OcTheme.elevation.floating, shape)
                .dialogCardBounds(scrim)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Text(
                text = stringResource(R.string.oc_palette_agent_details_title),
                color = c.textBase,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                lineHeight = 21.sp,
            )
            val current = rows
            if (current == null) {
                Text(
                    text = stringResource(
                        if (loadFailed) R.string.common_requestFailed else R.string.oc_common_loading,
                    ),
                    color = c.textMuted,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 12.dp),
                )
            } else if (current.isEmpty()) {
                Text(
                    text = stringResource(R.string.oc_palette_agent_empty),
                    color = c.textMuted,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 12.dp),
                )
            } else {
                current.forEach { row ->
                    AgentRow(
                        row = row,
                        active = selectedId == row.id,
                        onClick = { selectedId = row.id },
                    )
                }
                // Detail of the selected agent, fetched through `GET /api/agent/{id}`.
                AgentDetail(serverStore = serverStore, agentID = selectedId)
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                Text(
                    text = stringResource(R.string.oc_common_close),
                    color = c.textMuted,
                    fontSize = 13.sp,
                    fontWeight = FontWeight(530),
                    modifier = Modifier
                        .clip(OcTheme.shapes.md)
                        .clickable(role = Role.Button, onClick = onClose)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun AgentRow(row: AgentDetails.Row, active: Boolean, onClick: () -> Unit) {
    val c = OcTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(OcTheme.shapes.md)
            .background(if (active) c.layer03 else c.bgBase)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = row.name,
                color = c.textBase,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = row.mode,
                color = c.textFaint,
                fontSize = 11.sp,
                maxLines = 1,
            )
        }
        if (row.description.isNotBlank()) {
            Text(
                text = row.description,
                color = c.textMuted,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The detail card of one agent — `GET /api/agent/{agentID}` ([V2Api.agent]),
 * rendered as id / mode / description / system. Re-fetches per selection.
 */
@Composable
private fun AgentDetail(serverStore: ServerConfigStore, agentID: String?) {
    val c = OcTheme.colors
    var detail by remember(agentID) { mutableStateOf<V2Api.AgentInfo?>(null) }
    var failed by remember(agentID) { mutableStateOf(false) }
    LaunchedEffect(agentID) {
        if (agentID == null) return@LaunchedEffect
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                val server = serverStore.server ?: return@runCatching null
                val api = V2Api.forServer(server.url, server.username, server.password)
                    ?: return@runCatching null
                api.agent(agentID)
            }.getOrNull()
        }
        detail = loaded
        failed = loaded == null
    }
    if (agentID == null) return
    Spacer(Modifier.height(8.dp))
    when {
        detail == null && !failed -> Text(
            text = stringResource(R.string.oc_common_loading),
            color = c.textMuted,
            fontSize = 12.sp,
        )
        failed -> Text(
            text = stringResource(R.string.common_requestFailed),
            color = c.textMuted,
            fontSize = 12.sp,
        )
        else -> {
            val info = detail ?: return
            Text(
                text = info.id,
                color = c.textFaint,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            )
            if (info.description.isNotBlank()) {
                Text(
                    text = info.description,
                    color = c.textMuted,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            val system = info.system
            if (!system.isNullOrBlank()) {
                Text(
                    text = stringResource(R.string.oc_palette_agent_system),
                    color = c.textMuted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp, max = 160.dp)
                        .background(c.layer02.copy(alpha = 0.6f), OcTheme.shapes.md)
                        .verticalScroll(rememberScrollState())
                        .padding(8.dp),
                ) {
                    Text(
                        text = system,
                        color = c.textBase,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}
