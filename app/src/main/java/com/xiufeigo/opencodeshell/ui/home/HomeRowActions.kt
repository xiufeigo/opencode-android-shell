package com.xiufeigo.opencodeshell.ui.home

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.TransportUnavailableException
import com.xiufeigo.opencodeshell.v2.SessionRenameOutcome
import com.xiufeigo.opencodeshell.ui.session.export.ExportFeedback
import com.xiufeigo.opencodeshell.ui.session.export.ExportFinish
import com.xiufeigo.opencodeshell.ui.session.export.ImportFailure
import com.xiufeigo.opencodeshell.ui.session.export.ImportFinish
import com.xiufeigo.opencodeshell.ui.session.export.SessionExport
import com.xiufeigo.opencodeshell.ui.session.export.rememberSessionExportSaver
import com.xiufeigo.opencodeshell.ui.session.export.rememberSessionImporter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
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
    // Reports the server's answer; HomeSessionsColumn ends the inline editor on
    // **either** answer — a failure leaves the row on the title the server still
    // has and hands it back as a normal row, whose menu is reachable again (v84
    // P1: v83's rolled-back-but-open editor was a state a phone could not leave,
    // and the rollback never reached the field either). The
    // answer is the v81 outcome set rather than a boolean because two of its
    // shapes need their own copy — and their own repair — instead of the
    // connection sentence every failure used to get (`SessionRenameOutcome`).
    val rename: (sessionId: String, title: String, onResult: (SessionRenameOutcome) -> Unit) -> Unit,
    // Export fetch: the info/messages frame the SAF saver writes, or the
    // reason there is none — `ChatViewModel.fetchExportFrameResult`, whose
    // `Result` keeps the transport exception the nullable `fetchExportFrame`
    // drops, and names a never-sent read by type
    // ([TransportUnavailableException]) rather than by an empty `null`.
    // Runs off the main thread by the caller.
    val export: suspend (row: HomeSessions.Row) -> Result<SessionExport.Frame?>,
    // Delete through DELETE /session/:id (ChatViewModel.deleteSession),
    // then route cleanup (close the tab if open, leave the session route).
    // Reports the server's answer; Home drops the row on the next read.
    val delete: (row: HomeSessions.Row, onResult: (Boolean) -> Unit) -> Unit,
)

/**
 * The export half of [HomeRowActions.export]: serializes the fetched frame
 * with the pure [SessionExport] and writes it through the SAF saver with the
 * `Download/` bypass — the same contract SessionScreen's export path uses.
 *
 * **No branch is silent** (the v8-C report read this as the screen's own P0
 * before it was fixed there, in the smaller form):
 * - the fetch result is unwrapped instead of swallowed, so a read that never
 *   left the app names the missing connection (`oc_export_no_transport`, v40
 *   tC), a transport failure names its reason (`oc_export_fetch_failed`) and a
 *   reason-less one still reports (`oc_export_failed`);
 * - the save goes through the **scope overload** of the saver, so every
 *   outcome is answered — [ExportFeedback] separates a picker save
 *   (`oc_export_success`), a `Download/` bypass (`oc_export_saved_downloads`,
 *   otherwise the user hunts for a dialog that never appeared), a dismissed
 *   picker (`oc_export_cancelled`; the boolean overload keeps upstream's
 *   silence here, which left the row looking dead) and an exhausted save
 *   (`oc_export_failed`);
 * - a tap while an attempt is already out says so (`oc_export_in_progress`)
 *   instead of hitting the saver's one-request guard and reading as a failure.
 *
 * [io] is injectable so the JVM test drives the fetch hop without a real
 * thread.
 */
class HomeRowExportSaver(
    private val save: (
        filename: String,
        json: String,
        scope: CoroutineScope,
        onFinish: (ExportFinish) -> Unit,
    ) -> Unit,
    private val toast: (resId: Int, arg: String?) -> Unit,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private var working = false

    /** True from the fetch until the attempt's one outcome has been reported. */
    val hasAttemptOut: Boolean get() = working

    fun export(
        row: HomeSessions.Row,
        fetch: suspend (HomeSessions.Row) -> Result<SessionExport.Frame?>,
    ) {
        if (working) {
            toast(R.string.oc_export_in_progress, null)
            return
        }
        working = true
        scope.launch {
            val result = withContext(io) {
                // A host may fail by throwing instead of returning a failure;
                // folding it in keeps the two failure shapes on one seam.
                runCatching { fetch(row) }.getOrElse { failure -> Result.failure(failure) }
            }
            val frame = result.getOrNull()
            if (frame == null) {
                working = false
                toastFetchFailure(result.exceptionOrNull())
                return@launch
            }
            // The frame's own `info` is the server-fresh session object, so
            // its title wins over the row's — a rename that landed after this
            // Home list was read (or from another surface) still pre-fills
            // the new name instead of the cached one.
            val filename = SessionExport.filename(
                row.id,
                SessionExport.frameTitle(frame) ?: row.title.ifBlank { null },
            )
            val json = SessionExport.serialize(frame)
            save(filename, json, scope) { finish ->
                working = false
                when (ExportFeedback.of(finish)) {
                    ExportFeedback.SAVED -> toast(R.string.oc_export_success, filename)
                    ExportFeedback.SAVED_TO_DOWNLOADS ->
                        toast(R.string.oc_export_saved_downloads, filename)
                    ExportFeedback.CANCELLED -> toast(R.string.oc_export_cancelled, null)
                    ExportFeedback.FAILED -> toast(R.string.oc_export_failed, null)
                }
            }
        }
    }

    /**
     * v40 tC: the fetch failed for one of two very different reasons, and both
     * used to collapse into the bare「导出会话失败」— the tester could not tell a
     * dead server from a refused read:
     * * [TransportUnavailableException] — nothing was sent at all (no server, or
     *   its v2 probe has not landed). The SAF picker is never reached, so the
     *   line says so and points at the server connection.
     * * anything else — the wire answered (or refused); the server's own reason
     *   rides `oc_export_fetch_failed`, and a reason-less one still reports.
     */
    private fun toastFetchFailure(cause: Throwable?) {
        if (cause is TransportUnavailableException) {
            toast(R.string.oc_export_no_transport, null)
            return
        }
        val reason = cause?.message?.takeIf { it.isNotBlank() }
        if (reason == null) toast(R.string.oc_export_failed, null)
        else toast(R.string.oc_export_fetch_failed, reason)
    }
}

// Remembers the SAF-backed HomeRowExportSaver for HomeScreen.
@Composable
fun rememberHomeRowExportSaver(): HomeRowExportSaver {
    val context = LocalContext.current
    val saver = rememberSessionExportSaver()
    val scope = remember { CoroutineScope(Dispatchers.Main) }
    // Leaving Home must cancel in-flight export work: this scope is created
    // here and nothing else would ever cancel it, so its toasts/callbacks
    // would land on a composition that is already gone.
    DisposableEffect(scope) {
        onDispose { scope.cancel() }
    }
    return remember(saver, scope) {
        HomeRowExportSaver(
            save = { filename, json, exportScope, onFinish ->
                saver.save(filename, json, exportScope, onFinish)
            },
            toast = { resId, arg ->
                val text = if (arg == null) context.getString(resId) else context.getString(resId, arg)
                // Outcome copy (saved / dismissed / failed, carrying a filename
                // or the server's reason) needs reading time — long, not short.
                // The re-tap hint is a transient acknowledgment and stays short.
                val duration =
                    if (resId == R.string.oc_export_in_progress) Toast.LENGTH_SHORT else Toast.LENGTH_LONG
                Toast.makeText(context, text, duration).show()
            },
            scope = scope,
        )
    }
}

/**
 * A-3: the host behind Home's project-row 「导入会话」 (`home.project.importSession`,
 * `_upstream/packages/app/src/home/projects/controller.tsx:109-135`).
 *
 * Upstream opens a JSON attachment picker, decodes the file as
 * `SessionTransfer.Data`, posts it to the import route **with
 * `location: {directory: project.worktree}`** and finally opens the created
 * session (`openProjectSession`). This returns the trigger for that flow, and
 * everything except the final navigation is self-serve:
 *
 * - the picker + SAF read + `POST /api/experimental/session/import` live in
 *   [rememberSessionImporter], whose `locationDirectory` is what pins the
 *   import into the *tapped* project — the importer is remembered per target,
 *   so the row cannot import into whichever project was selected last;
 * - [onImported] carries the created session id out to the host, which is the
 *   one step that needs the nav controller (`HomeScreen` → `ui/App.kt`);
 * - every outcome is reported: success with the id (`oc_session_import_success`,
 *   the same copy the context tab uses), a timeout or a failure with their own
 *   lines, and a dismissed picker stays silent (upstream does the same).
 *
 * A second tap while a picker is out is dropped instead of stacking a second
 * request (the importer's own one-at-a-time guard would reject it anyway).
 */
@Composable
fun rememberHomeProjectImport(
    onImported: (String) -> Unit = {},
): (HomeProjects.Project) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Which project the pending picker belongs to; also the importer's key.
    var target by remember { mutableStateOf<HomeProjects.Project?>(null) }
    // The importer is remembered *per target*: `locationDirectory` is baked in at
    // construction, so one shared instance could only ever import into one
    // project.
    val importer = rememberSessionImporter(target?.path)
    val latestOnImported by rememberUpdatedState(onImported)
    LaunchedEffect(target) {
        val project = target ?: return@LaunchedEffect
        importer.importSession(scope) { finish ->
            // Released before the report so a later tap starts clean.
            target = null
            val text = when (finish) {
                is ImportFinish.Imported ->
                    context.getString(R.string.oc_session_import_success, finish.sessionId)

                ImportFinish.Cancelled -> null

                is ImportFinish.Failed -> importFailureCopy(context, finish)
            }
            text?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
            (finish as? ImportFinish.Imported)?.let { latestOnImported(it.sessionId) }
        }
    }
    return { project -> if (target == null) target = project }
}

/**
 * v40: the same import flow behind the **session row**'s long-press menu
 * (`HomeSessionsList.HomeSessionRow`), i.e. the third entry point for
 * 「导入会话」 next to the project row's ⋯ and the chat header's ⋮.
 *
 * The only thing that differs between the three is the import **location**: the
 * project row uses `project.worktree`, the header uses the open session's
 * `directory` (upstream's `location: {directory: …}`,
 * `home/projects/controller.tsx:120-126`), and a session row uses the directory
 * of the session it was opened from — the project that row belongs to. So this
 * is [rememberHomeProjectImport] keyed on a row's `directory` instead of a
 * project's `path`; the picker, the SAF read, the `{info, messages}` parse, the
 * re-key and `POST /api/experimental/session/import` are all
 * [rememberSessionImporter]'s, and every outcome is reported by the same
 * [importFailureCopy] mapping (success with the id, a timeout, a refusal with
 * the server's words, silence on a dismissed picker).
 *
 * A null/blank directory is left as-is: `locationDirectory = null` makes the
 * server pick its own default, which is the best available answer for a session
 * the server gave no directory for.
 *
 * [onImported] hands the created session id to the host, which is the one step
 * that needs the nav controller (`HomeScreen.onSessionImported` — the same
 * callback the project row's import uses). A second tap while a picker is out is
 * dropped instead of stacking a second request.
 */
@Composable
fun rememberHomeSessionImport(
    onImported: (String) -> Unit = {},
): (HomeSessions.Row) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // The row whose picker is out; also the importer's key.
    var target by remember { mutableStateOf<HomeSessions.Row?>(null) }
    // Remembered per *directory*: `locationDirectory` is baked in at construction,
    // and two rows of one project import into the same place — keying on the row
    // would rebuild the importer (and re-read the server config) for every row.
    val importer = rememberSessionImporter(target?.directory)
    val latestOnImported by rememberUpdatedState(onImported)
    LaunchedEffect(target) {
        val row = target ?: return@LaunchedEffect
        importer.importSession(scope) { finish ->
            // Released before the report so a later tap starts clean.
            target = null
            val text = when (finish) {
                is ImportFinish.Imported ->
                    context.getString(R.string.oc_session_import_success, finish.sessionId)

                ImportFinish.Cancelled -> null

                is ImportFinish.Failed -> importFailureCopy(context, finish)
            }
            text?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
            (finish as? ImportFinish.Imported)?.let { latestOnImported(it.sessionId) }
        }
    }
    return { row -> if (target == null) target = row }
}

/**
 * The one line one failed import shows, picked from [ImportFailure]: an
 * unreadable file, a file that is not a transfer, the hand-rolled export's own
 * shape, a server refusal (with the server's words), an unreachable route, a
 * timeout, or a missing server. It replaced a single `oc_session_import_failed`
 * for all of them.
 *
 * The same mapping lives in `ui/session/context/SessionContextPanel.kt` (the
 * other import call site, which stays on the Context tab); the two must stay in
 * step. The header ⋮ row's 「导入会话…」 reuses *that* copy rather than adding a
 * third (it is `internal` for exactly this reason).
 */
private fun importFailureCopy(
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
