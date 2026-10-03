package com.xiufeigo.opencodeshell.ui.palette

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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
import com.xiufeigo.opencodeshell.v2.debugLocations
import com.xiufeigo.opencodeshell.v2.evictDebugLocation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「Debug locations」 — `GET /api/debug/location` (the bare directory list of
 * every location the server has loaded) with a per-row `DELETE` evict
 * (`debug.ts:8-31` → `evictDebugLocation`), which disposes that location's
 * cached services so its next use boots them fresh. Same self-serve seam as
 * [AgentDetailsDialog]; the list re-reads after every evict.
 */
@Composable
fun DebugLocationsDialog(onClose: () -> Unit) {
    val c = OcTheme.colors
    val shape = OcTheme.shapes.xl
    val context = LocalContext.current
    val serverStore = remember(context) { ServerConfigStore(context).also { it.restore() } }
    val scope = rememberCoroutineScope()

    var locations by remember { mutableStateOf<List<String>?>(null) }
    var loadFailed by remember { mutableStateOf(false) }

    suspend fun reload() {
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                val server = serverStore.server ?: return@runCatching null
                val api = V2Api.forServer(server.url, server.username, server.password)
                    ?: return@runCatching null
                api.debugLocations()
            }.getOrNull()
        }
        locations = loaded
        loadFailed = loaded == null
    }

    LaunchedEffect(serverStore) { reload() }

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
                text = stringResource(R.string.oc_palette_debug_locations_title),
                color = c.textBase,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                lineHeight = 21.sp,
            )
            val current = locations
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
                    text = stringResource(R.string.oc_palette_debug_locations_empty),
                    color = c.textMuted,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 12.dp),
                )
            } else {
                current.forEach { directory ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = directory,
                            color = c.textBase,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = stringResource(R.string.oc_palette_debug_locations_evict),
                            color = c.textMuted,
                            fontSize = 12.sp,
                            fontWeight = FontWeight(530),
                            modifier = Modifier
                                .clip(OcTheme.shapes.md)
                                .clickable(role = Role.Button) {
                                    scope.launch {
                                        val ok = withContext(Dispatchers.IO) {
                                            runCatching {
                                                val server = serverStore.server
                                                    ?: return@runCatching false
                                                val api = V2Api.forServer(
                                                    server.url,
                                                    server.username,
                                                    server.password,
                                                ) ?: return@runCatching false
                                                api.evictDebugLocation(directory)
                                                true
                                            }.getOrDefault(false)
                                        }
                                        Toast.makeText(
                                            context,
                                            context.getString(
                                                if (ok) {
                                                    R.string.oc_palette_debug_locations_evicted
                                                } else {
                                                    R.string.common_requestFailed
                                                },
                                            ),
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                        reload()
                                    }
                                }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
                }
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
