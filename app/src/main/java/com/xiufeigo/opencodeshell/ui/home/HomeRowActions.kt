package com.xiufeigo.opencodeshell.ui.home

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.ui.session.export.SessionExport
import com.xiufeigo.opencodeshell.ui.session.export.rememberSessionExportSaver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// WAVE 2.3b P1 (A2 #4) - the host behind Home's session-row context menu
// (home/sessions/controller.tsx:141-190,219-243,309-310).
//
// Upstream wires the three items as:
// - Rename: session.update title + local cache refresh, blank/unchanged
//   short-circuits to success (:141-167);
// - Export: fetchSessionExport + saveSessionExport with success/error
//   toasts (:169-190);
// - Delete: the danger dialog (DeleteDialog, :219-243), confirmed through
//   session.remove + notifySessionTabsRemoved (:192-217), shown via
//   showDelete (:309-310).
//
// This shell cannot call ui/session internals (owned by another wave) or
// ui/App.kt from here; App.kt therefore builds this host from the view model
// plus the session-route helpers it already owns and passes it into HomeScreen
// as rowActions. Null (previews, hosts without a client) disables the matching
// menu items instead of failing.
class HomeRowActions(
    // Rename through PATCH /session/:id (ChatViewModel.renameSession).
    // Reports the server's answer; HomeSessionsColumn closes the editor only
    // on true and keeps the draft for another attempt on false.
    val rename: (sessionId: String, title: String, onResult: (Boolean) -> Unit) -> Unit,
    // Export fetch: info/messages frame for the SAF saver, or null when the
    // server is unreachable. Runs off the main thread by the caller.
    val export: suspend (row: HomeSessions.Row) -> SessionExport.Frame?,
    // Delete through DELETE /session/:id (ChatViewModel.deleteSession),
    // then route cleanup (close the tab if open, leave the session route).
    // Reports the server's answer; Home drops the row on the next read.
    val delete: (row: HomeSessions.Row, onResult: (Boolean) -> Unit) -> Unit,
)

// The export half of HomeRowActions.export: serializes the fetched frame
// with the pure SessionExport and writes it through the SAF saver - the same
// saveSessionExport contract SessionScreen's export path uses (silent
// cancel, oc_export_success / oc_export_failed toasts).
class HomeRowExportSaver(
    private val save: (filename: String, json: String, onResult: (Boolean) -> Unit) -> Unit,
    private val toast: (resId: Int, arg: String?) -> Unit,
    private val scope: CoroutineScope,
) {
    fun export(row: HomeSessions.Row, fetch: suspend (HomeSessions.Row) -> SessionExport.Frame?) {
        scope.launch {
            val frame = withContext(Dispatchers.IO) {
                runCatching { fetch(row) }.getOrNull()
            }
            if (frame == null) {
                toast(R.string.oc_export_failed, null)
                return@launch
            }
            val filename = SessionExport.filename(row.id, row.title.ifBlank { null })
            val json = SessionExport.serialize(frame)
            save(filename, json) { ok ->
                if (ok) toast(R.string.oc_export_success, filename)
                else toast(R.string.oc_export_failed, null)
            }
        }
    }
}

// Remembers the SAF-backed HomeRowExportSaver for HomeScreen.
@Composable
fun rememberHomeRowExportSaver(): HomeRowExportSaver {
    val context = LocalContext.current
    val saver = rememberSessionExportSaver()
    val scope = remember { CoroutineScope(Dispatchers.Main) }
    return remember(saver, scope) {
        HomeRowExportSaver(
            save = { filename, json, onResult -> saver.save(filename, json, onResult) },
            toast = { resId, arg ->
                val text = if (arg == null) context.getString(resId) else context.getString(resId, arg)
                Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
            },
            scope = scope,
        )
    }
}
