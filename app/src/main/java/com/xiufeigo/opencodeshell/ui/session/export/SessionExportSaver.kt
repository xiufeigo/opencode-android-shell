package com.xiufeigo.opencodeshell.ui.session.export

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Writes an exported session JSON through the Storage Access Framework.
 *
 * Upstream's `saveSessionExport` (`_upstream/packages/app/src/session/commands/
 * export.ts:58-68`) hands the serialized string to `platform.saveFile({defaultPath},
 * json)` and treats `false` as **the user cancelling**: callers return silently
 * and show no toast (`home/sessions/controller.tsx:169-190` toasts only on
 * success/failure). Android's `ACTION_CREATE_DOCUMENT` is the same contract —
 * `defaultPath` in, a content [Uri] out, `null` when cancelled — via
 * [ActivityResultContracts.CreateDocument] with the payload's own mime type.
 *
 * P0 hardening: the picker is not guaranteed to answer. On builds where no
 * activity handles `ACTION_CREATE_DOCUMENT` (or the launch throws, or the
 * dialog never calls back) the old code left the request pending forever with
 * no path back to a usable UI. Now every entry point fails fast or falls
 * back:
 * - [save] pre-flights the resolve check and guards the launch, so a broken
 *   picker reports `false` instead of throwing or hanging;
 * - the [save] overload taking a scope additionally writes straight to
 *   Download/ when the picker is missing, throws, fails the write, or stays
 *   silent past [SessionExportFallback.SAF_TIMEOUT_MS];
 * - a **dismissed** picker is reported as [ExportFinish.Cancelled] too: the
 *   silent version of that branch was the worst one — the scope overload never
 *   heard back, kept its busy flag up, and every later attempt bounced off its
 *   re-entry guard with no picker, no toast and no file;
 * - [cancelPending] drops a pending request (Cancel affordance, timeout
 *   reclaim) and releases the scope overload the same way; a late picker
 *   answer afterwards is ignored, never double-applied.
 * Never more than one request is ever pending: a second [save] while one is in
 * flight fails fast instead of stacking pickers.
 */
class SessionExportSaver internal constructor(
    private val context: Context,
    private val launchCreateDocument: (String) -> Unit,
    private val hasSaveHandler: () -> Boolean = { SessionExportFallback.hasSaveHandler(context) },
    private val fallbackWrite: (filename: String, json: String) -> Boolean =
        { filename, json -> SessionExportFallback.writeToDownloads(context, filename, json) },
) {
    private var pendingJson: String? = null
    private var pendingFilename: String? = null
    private var pendingResult: ((Boolean) -> Unit)? = null
    private var pendingFinish: ((ExportFinish) -> Unit)? = null
    private var watchdog: Job? = null

    /** True while a picker request is out and unanswered. */
    val hasPending: Boolean get() = pendingResult != null || pendingFinish != null

    /**
     * Opens the system save dialog for [filename]; [onResult] runs only after
     * the bytes are on disk (`true`) or the write failed (`false`). A cancelled
     * dialog calls nothing, mirroring upstream's silent cancel — callers that
     * need the dismiss reported use the [save] overload taking a scope. A
     * missing or throwing picker also reports `false` — never throws, never
     * hangs.
     */
    fun save(filename: String, json: String, onResult: (Boolean) -> Unit) {
        if (!claim(filename, json)) {
            onResult(false)
            return
        }
        pendingResult = onResult
        if (!openPicker()) {
            clearPending()
            onResult(false)
        }
    }

    /**
     * [save] with the Download/ bypass: [onFinish] always fires exactly once —
     * [ExportFinish.Saved] (picker or fallback), [ExportFinish.Cancelled]
     * (picker dismissed, or the request dropped by [cancelPending]: the user
     * said no, so there is no fallback write), or [ExportFinish.Failed]
     * (picker missing/threw/timed out AND the fallback failed).
     *
     * [scope] (main) runs the watchdog and the fallback hop; callers keep it
     * screen-scoped so leaving the screen drops the whole attempt.
     */
    fun save(
        filename: String,
        json: String,
        scope: CoroutineScope,
        onFinish: (ExportFinish) -> Unit,
    ) {
        if (!claim(filename, json)) {
            onFinish(ExportFinish.Failed)
            return
        }
        pendingFinish = onFinish
        if (!hasSaveHandler()) {
            finishViaFallback(scope, filename, json, onFinish)
            return
        }
        watchdog = scope.launch {
            delay(SessionExportFallback.SAF_TIMEOUT_MS)
            onPickerTimeout(scope, onFinish)
        }
        try {
            launchCreateDocument(filename)
        } catch (_: Exception) {
            watchdog?.cancel()
            watchdog = null
            finishViaFallback(scope, filename, json, onFinish)
        }
    }

    /**
     * Drops the pending request, if any. A picker answer arriving afterwards is
     * ignored. Returns whether anything was pending.
     *
     * The scope overload is released with [ExportFinish.Cancelled] so a
     * reclaimed/cancelled attempt still clears its caller's busy state; the
     * boolean overload stays silent, exactly like upstream's dismissed dialog
     * (`saveSessionExport` treats it as "the user said no", not as an error
     * copy) and like the picker's own dismiss.
     */
    fun cancelPending(): Boolean {
        val had = hasPending
        val finish = pendingFinish
        clearPending()
        finish?.invoke(ExportFinish.Cancelled)
        return had
    }

    /** Called by [rememberSessionExportSaver] when the SAF dialog returns. */
    internal suspend fun deliver(uri: Uri?) {
        val result = pendingResult
        val finish = pendingFinish
        val json = pendingJson
        val filename = pendingFilename
        if (result == null && finish == null) return // reclaimed/cancelled — ignore the late answer
        clearPending()
        val dismissed = uri == null || json == null || filename == null
        if (dismissed) {
            // No picker answer is a cancel (`routeSaveOutcome`'s rule) and it
            // is **delivered**: this branch used to `return` without calling
            // anything, which left a scope-overload caller (SessionScreen's
            // `exportWorking`) stuck true forever — the tap after that showed
            // no picker, no toast and no file.
            finish?.invoke(routeSaveOutcome(picked = false, writeOk = false, filename = filename.orEmpty()))
            return
        }
        val ok = writeJson(uri, json)
        result?.invoke(ok)
        if (finish != null) {
            if (ok) {
                finish(ExportFinish.Saved(filename))
            } else {
                val viaFallback = withContext(Dispatchers.IO) { fallbackWrite(filename, json) }
                finish(
                    if (viaFallback) ExportFinish.Saved(filename, viaFallback = true)
                    else ExportFinish.Failed,
                )
            }
        }
    }

    private fun claim(filename: String, json: String): Boolean {
        if (hasPending) return false
        pendingFilename = filename
        pendingJson = json
        return true
    }

    private fun openPicker(): Boolean {
        if (!hasSaveHandler()) return false
        return try {
            launchCreateDocument(pendingFilename ?: return false)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun onPickerTimeout(scope: CoroutineScope, onFinish: (ExportFinish) -> Unit) {
        if (pendingFinish == null) return
        val filename = pendingFilename
        val json = pendingJson
        clearPending()
        if (filename == null || json == null) {
            onFinish(ExportFinish.Failed)
            return
        }
        finishViaFallback(scope, filename, json, onFinish)
    }

    private fun finishViaFallback(
        scope: CoroutineScope,
        filename: String,
        json: String,
        onFinish: (ExportFinish) -> Unit,
    ) {
        clearPending()
        scope.launch {
            val ok = withContext(Dispatchers.IO) { fallbackWrite(filename, json) }
            onFinish(
                if (ok) ExportFinish.Saved(filename, viaFallback = true)
                else ExportFinish.Failed,
            )
        }
    }

    private suspend fun writeJson(uri: Uri, json: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openOutputStream(uri)?.use { stream ->
                stream.write(json.toByteArray(Charsets.UTF_8))
                stream.flush()
                true
            } ?: false
        }.getOrDefault(false)
    }

    private fun clearPending() {
        watchdog?.cancel()
        watchdog = null
        pendingJson = null
        pendingFilename = null
        pendingResult = null
        pendingFinish = null
    }
}

/** The SAF handle; see [SessionExportSaver] for the upstream mapping. */
@Composable
fun rememberSessionExportSaver(): SessionExportSaver {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // The launcher needs the saver (to deliver the uri) and the saver needs the
    // launcher (to open the dialog); a lambda holder breaks the cycle without
    // naming the launcher type (which differs across activity versions).
    val launchHolder = remember { arrayOfNulls<(String) -> Unit>(1) }
    val saver = remember(context) {
        SessionExportSaver(
            context.applicationContext,
            launchCreateDocument = { filename -> launchHolder[0]?.invoke(filename) },
        )
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> scope.launch { saver.deliver(uri) } }
    launchHolder[0] = { filename -> launcher.launch(filename) }
    return saver
}
