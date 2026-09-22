package com.xiufeigo.opencodeshell.ui.session.export

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
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
 */
class SessionExportSaver internal constructor(
    private val context: Context,
    private val launchCreateDocument: (String) -> Unit,
) {
    private var pendingJson: String? = null
    private var pendingResult: ((Boolean) -> Unit)? = null

    /**
     * Opens the system save dialog for [filename]; [onResult] runs only after
     * the bytes are on disk (`true`) or the write failed (`false`). A cancelled
     * dialog calls nothing, mirroring upstream's silent cancel.
     */
    fun save(filename: String, json: String, onResult: (Boolean) -> Unit) {
        pendingJson = json
        pendingResult = onResult
        launchCreateDocument(filename)
    }

    /** Called by [rememberSessionExportSaver] when the SAF dialog returns. */
    internal suspend fun deliver(uri: Uri?) {
        val json = pendingJson
        val callback = pendingResult
        pendingJson = null
        pendingResult = null
        if (uri == null || json == null) return // cancelled — upstream shows nothing
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { stream ->
                    stream.write(json.toByteArray(Charsets.UTF_8))
                    stream.flush()
                    true
                } ?: false
            }.getOrDefault(false)
        }
        callback?.invoke(ok)
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
        SessionExportSaver(context.applicationContext) { filename -> launchHolder[0]?.invoke(filename) }
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> scope.launch { saver.deliver(uri) } }
    launchHolder[0] = { filename -> launcher.launch(filename) }
    return saver
}
