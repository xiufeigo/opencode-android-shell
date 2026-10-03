package com.xiufeigo.opencodeshell.ui.session.export

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.xiufeigo.opencodeshell.ServerConfigStore
import com.xiufeigo.opencodeshell.v2.SessionTransferData
import com.xiufeigo.opencodeshell.v2.V2Api
import com.xiufeigo.opencodeshell.v2.sessionExport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The three ways this shell can write a session JSON out.
 *
 * | kind | source | notes |
 * |---|---|---|
 * | [ASSEMBLED] | `sessionRaw` + `messagesRaw` via [SessionExport.frameFrom] | the existing hand-rolled pair (`session/commands/export.ts`), already byte-equivalent to the default export element-for-element (Measured 2026-09-23 on 2.0.11) |
 * | [OFFICIAL] | `GET /api/experimental/session/{id}/export` ([com.xiufeigo.opencodeshell.v2.sessionExport], `sanitize` omitted) | one round-trip for the complete transcript — no `cursor` paging and no `maxPages` truncation |
 * | [SANITIZED] | the same route with `?sanitize=true` | the redacted form: `title`, `location.directory`, message `text`/`metadata` and part `state`s collapse to `[redacted:<kind>:<id>]` / `{"redacted":"<kind>:<id>"}` while ids, times, costs and token counts stay real |
 *
 * [sanitizeParam] is the `BooleanFromString` query value each kind sends
 * (`"true"` literally, never `1`; `null` = the key is omitted and the server's
 * unsanitized default runs).
 */
enum class SessionExportKind { ASSEMBLED, OFFICIAL, SANITIZED }

/** `?sanitize=` of the official route; `null` omits the query parameter. */
fun SessionExportKind.sanitizeParam(): Boolean? = when (this) {
    SessionExportKind.ASSEMBLED, SessionExportKind.OFFICIAL -> null
    SessionExportKind.SANITIZED -> true
}

/**
 * `SessionTransfer.Data` → [SessionExport.Frame], the `{info, messages}` pair
 * the SAF saver serializes. Both halves are the route's own raw objects, so the
 * re-import envelope survives a save verbatim.
 */
fun SessionExport.transferFrame(transfer: SessionTransferData): SessionExport.Frame =
    SessionExport.Frame(transfer.info, transfer.messages)

/**
 * Fetches one official export (`sessionExport`, any [SessionExportKind.sanitizeParam]).
 * `null` = the request failed — callers report `oc_export_failed` and keep the
 * dialog closed, the same contract as [SessionExportSaver]'s failed write.
 */
fun interface SessionExportSource {
    suspend fun fetch(sessionID: String, sanitize: Boolean?): SessionTransferData?
}

/**
 * Read-only official-export fetcher. Same self-serve pattern as
 * `SummaryServiceSource.rememberSummaryCatalogs`: the screen's hand-rolled
 * export needs no ViewModel, and `ChatViewModel` exposes nothing for this route
 * (`ChatViewModel.fetchExportFrame` stays on `sessionRaw`/`messagesRaw`).
 */
@Composable
fun rememberSessionExportSource(): SessionExportSource {
    val context = LocalContext.current
    val serverStore = remember(context) { ServerConfigStore(context).also { it.restore() } }
    return remember(serverStore) {
        SessionExportSource { sessionID, sanitize ->
            withContext(Dispatchers.IO) {
                val server = serverStore.server ?: return@withContext null
                val api = V2Api.forServer(server.url, server.username, server.password)
                    ?: return@withContext null
                runCatching { api.sessionExport(sessionID, sanitize) }.getOrNull()
            }
        }
    }
}
