package com.xiufeigo.opencodeshell.ui.session.export

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.xiufeigo.opencodeshell.ServerConfigStore
import com.xiufeigo.opencodeshell.v2.SessionTransferData
import com.xiufeigo.opencodeshell.v2.V2Api
import com.xiufeigo.opencodeshell.v2.optStringOrNull
import com.xiufeigo.opencodeshell.v2.sessionImport
import java.io.IOException
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Parses one `{info, messages}` transfer document ([SessionTransferData],
 * `session-transfer.ts:7-10`) out of an exported file. Lenient like the
 * re-import contract: a missing `messages` half is an empty list, a missing
 * `info` half is a `null` (nothing to import) rather than an exception.
 */
fun SessionExport.parseTransfer(json: String): SessionTransferData? {
    val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
    val info = root.optJSONObject("info") ?: return null
    val messages = root.optJSONArray("messages") ?: JSONArray()
    val rows = buildList {
        for (i in 0 until messages.length()) {
            messages.optJSONObject(i)?.let(::add)
        }
    }
    return SessionTransferData(info = info, messages = rows)
}

/**
 * True when [transfer] holds at least one row in the hand-rolled export's own
 * `{info, parts}` shape ([SessionExport.messageJson]) instead of the route's
 * flat `SessionMessage.Info` rows. Only the flat shape is importable: measured
 * on 2.0.11, such a row answers `400 InvalidRequestError` ("Expected
 * Session.Message.Info at [\"messages\"][0]"), so 「导出会话」's file is a
 * *format* failure — the import says so instead of relaying a decode error as
 * if the server had refused a good file.
 *
 * The signature is the `info` + `parts` **pair** at the row's top level: the
 * route's own rows never carry a top-level `parts` (an assistant message keeps
 * its parts under `content`), and a bare `{info: …}` row — which older fixtures
 * and hand-written documents use — is left alone rather than guessed at.
 */
fun SessionExport.hasUnimportableRows(transfer: SessionTransferData): Boolean =
    transfer.messages.any { it.has("parts") && it.has("info") && !it.has("type") }

/** The `ses_` prefix `Session.ID` checks (`schema/src/session-id.ts:5`). */
internal const val SESSION_ID_PREFIX = "ses_"

/** The `msg_` prefix `Session.Message.ID` checks (`schema/src/session-message.ts:23`). */
internal const val MESSAGE_ID_PREFIX = "msg_"

private const val ID_ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
private const val ID_TIME_BYTES = 6
private const val ID_TAIL_CHARS = 14

/**
 * One id in the server's own shape (`util/identifier.ts:15-27`): six hex bytes
 * of the millisecond, then 14 base62 chars. Only the prefix is schema-checked,
 * but a same-shape id is indistinguishable from the server's own — the shell
 * already mints `msg_` ids this way (`ChatViewModel.newMessageID`).
 */
internal fun mintTransferId(
    prefix: String,
    now: Long = System.currentTimeMillis(),
    random: Random = Random,
): String = buildString(prefix.length + ID_TIME_BYTES * 2 + ID_TAIL_CHARS) {
    append(prefix)
    for (index in 0 until ID_TIME_BYTES) {
        append(((now shr (40 - 8 * index)) and 0xff).toString(16).padStart(2, '0'))
    }
    repeat(ID_TAIL_CHARS) { append(ID_ALPHABET[random.nextInt(ID_ALPHABET.length)]) }
}

/**
 * The transfer one import POSTs: [transfer]'s transcript verbatim, with a
 * **fresh identity** — a new `info.id` and a new `id` per message row.
 *
 * Why the picked document's own identity cannot be reused (both measured on
 * 2.0.11 against the real `SessionTransfer.import`, `core/src/session/transfer.ts:64-70`):
 *
 * - the route refuses an id that already exists — `409 ConflictError`,
 *   "Session already exists: ses_…" — and an export just wrote the **source**
 *   session's id, which by definition exists on the server the app exported
 *   from: every export→import roundtrip collided, and the file could never be
 *   imported back;
 * - `session_message.id` is a PRIMARY KEY (`core/src/session/sql.ts:79-98`), so
 *   renaming only the session and keeping the message ids answers **500** (an
 *   insert conflict, not a typed error) — the second half of the same trap.
 *
 * Re-keying both halves makes the roundtrip deterministic: an import always
 * creates a **new** session holding a copy of the transcript. Every other field
 * — `parentID`, times, costs, tokens, parts — rides through untouched, and the
 * caller's [transfer] is never mutated.
 *
 * `revert`/`fork` name message ids, so they follow the rename
 * ([remapBoundaries]) rather than pointing at rows the copy does not have. An
 * *export* carries neither today (measured 2.0.11: the exported `info` is
 * `id, projectID, cost, tokens, time, title, subpath, location` — the route
 * drops them, and a file that does carry them imports fine but is ignored), so
 * this is a guard for hand-written or future documents, not a live path.
 */
fun SessionExport.rekeyForImport(
    transfer: SessionTransferData,
    sessionId: String = mintTransferId(SESSION_ID_PREFIX),
    messageId: () -> String = { mintTransferId(MESSAGE_ID_PREFIX) },
): SessionTransferData {
    val renamed = mutableMapOf<String, String>()
    val messages = transfer.messages.map { row ->
        val copy = shallowCopy(row)
        val fresh = messageId()
        optStringOrNull(row, "id")?.let { renamed[it] = fresh }
        copy.put("id", fresh)
        copy
    }
    val info = shallowCopy(transfer.info)
    info.put("id", sessionId)
    remapBoundaries(info, renamed)
    return SessionTransferData(info = info, messages = messages)
}

/** `revert.messageID` and `fork.boundary.messageID` are message ids; neither survives a dangling one. */
private fun remapBoundaries(info: JSONObject, renamed: Map<String, String>) {
    info.optJSONObject("revert")?.let { revert ->
        val fresh = optStringOrNull(revert, "messageID")?.let(renamed::get)
        if (fresh == null) info.remove("revert") else revert.put("messageID", fresh)
    }
    info.optJSONObject("fork")?.let { fork ->
        val boundary = fork.optJSONObject("boundary")
        val fresh = optStringOrNull(boundary, "messageID")?.let(renamed::get)
        if (fresh == null) info.remove("fork") else boundary.put("messageID", fresh)
    }
}

/**
 * One level of `JSONObject` copy, so a re-key never writes through to the
 * document the caller parsed. `JSONObject.keys()` is the copy loop rather than
 * `JSONObject.getNames`: the latter is absent from the Android stub the unit
 * tests compile against.
 */
private fun shallowCopy(obj: JSONObject): JSONObject {
    val copy = JSONObject()
    val keys = obj.keys()
    while (keys.hasNext()) {
        val key = keys.next()
        copy.put(key, obj.get(key))
    }
    return copy
}

/**
 * Why one attempt failed. Each member owns a line of copy, so a picked file
 * that is not an export can never read as a server refusal and vice versa; the
 * mapping lives at both call sites (`ui/session/context/SessionContextPanel.kt`
 * and `ui/home/HomeRowActions.kt`), which keep it visibly in step.
 */
enum class ImportFailure {
    /** The picked file could not be read at all (dead provider, unreadable stream). */
    FILE_UNREADABLE,

    /** Readable, but not a `{info, messages}` transfer document. */
    FILE_FORMAT,

    /** A transfer document whose rows are the hand-rolled `{info, parts}` export. */
    FILE_ASSEMBLED,

    /** The route answered non-2xx — the server refused it; [ImportFinish.Failed.detail] carries its words. */
    SERVER,

    /** Nothing answered within its deadline: the picker, the read or the route. */
    TIMEOUT,

    /** The route could not be reached (connect, reset, a dead read). */
    NETWORK,

    /** No server configured, or no usable v2 base url for the configured one — the thunk answered `null`. */
    NO_SERVER,

    /** Anything else: a missing/throwing picker, an attempt already in flight, an unexpected throw. */
    UNKNOWN,
}

/** How much of the server's own message a toast may carry. */
const val IMPORT_DETAIL_CAP = 160

/**
 * Where one import attempt ended. [Imported] carries the created session id;
 * [Cancelled] is the user dismissing the picker (upstream stays silent on it,
 * so the legacy callback fires nothing); [Failed] exhausted every path, with
 * [Failed.timedOut] telling a wedged provider/route apart from a plain failure
 * so the UI can say "timed out, retry" instead of a generic error.
 */
sealed interface ImportFinish {
    data class Imported(val sessionId: String) : ImportFinish
    data object Cancelled : ImportFinish

    /**
     * [reason] names the stage that failed and [detail] carries the server's own
     * words (only [ImportFailure.SERVER] fills it, capped at [IMPORT_DETAIL_CAP]),
     * so the toast says which of 「文件格式不对 / 服务端拒绝 / 网络」 happened.
     *
     * **Equality is [timedOut] alone**, deliberately: the pre-existing
     * `Failed(timedOut = …)` assertions ([SessionImporterTest]) and both call
     * sites' `is ImportFinish.Failed ->` arms keep their exact shape, while the
     * richer fields are the copy's input and are asserted directly
     * ([SessionImportRoundtripTest]).
     */
    class Failed(
        val timedOut: Boolean,
        val reason: ImportFailure = ImportFailure.UNKNOWN,
        val detail: String? = null,
    ) : ImportFinish {
        override fun equals(other: Any?): Boolean = other is Failed && other.timedOut == timedOut
        override fun hashCode(): Int = timedOut.hashCode()
        override fun toString(): String = "Failed(timedOut=$timedOut, reason=$reason, detail=$detail)"
    }
}

/** How long the SAF read may stall before the attempt fails as timed out. */
const val IMPORT_READ_TIMEOUT_MS = 30_000L

/** How long the import route may stall before the attempt fails as timed out. */
const val IMPORT_REQUEST_TIMEOUT_MS = 60_000L

/**
 * True when some activity answers `ACTION_OPEN_DOCUMENT`. False means launching
 * the picker would throw or hang, so the caller must fail fast instead.
 * Mirrors [SessionExportFallback.hasSaveHandler]; never called from JVM tests
 * (the importer defaults its handler check to `true`).
 */
fun hasImportPicker(context: Context): Boolean {
    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "*/*"
    }
    val pm = context.packageManager
    val hits = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        pm.queryIntentActivities(intent, 0)
    }
    return hits.isNotEmpty()
}

/**
 * `POST /api/experimental/session/import` ([com.xiufeigo.opencodeshell.v2.
 * sessionImport]) behind an `ACTION_OPEN_DOCUMENT` picker: read one export
 * document, hand it to the import thunk and report the created session id. The
 * thunk ([postSessionImport]) is what re-keys the identity and owns the route.
 *
 * `locationDirectory` is the import target (`Location.PublicRef` in the body,
 * not a query scope); `null` lets the server use its default location. Parent
 * ids must already exist (`session.ts:251-252`) — a missing parent answers
 * `404`, and a refusal of any other kind surfaces as a failed import whose
 * `detail` carries the server's own words.
 *
 * No stage hangs forever (the P0 the exporter already fixed in
 * [SessionExportSaver]): the picker silence is reclaimed by a watchdog, the
 * SAF read and the import route are each raced against a timeout, and every
 * terminal state reaches exactly one callback — a timeout included, so the UI
 * can show an error with a retry instead of wedging. A late picker answer
 * after a reclaim is ignored, never double-applied, and a second
 * [importSession] while one is in flight fails fast instead of stacking
 * pickers.
 */
class SessionImporter internal constructor(
    private val readBytes: (Uri) -> String?,
    private val launchOpenDocument: () -> Unit,
    private val importTransfer: suspend (SessionTransferData) -> String?,
    private val hasOpenHandler: () -> Boolean = { true },
    private val readTimeoutMs: Long = IMPORT_READ_TIMEOUT_MS,
    private val importTimeoutMs: Long = IMPORT_REQUEST_TIMEOUT_MS,
    private val pickerTimeoutMs: Long = SessionExportFallback.SAF_TIMEOUT_MS,
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
) {
    private var pendingResult: ((String?) -> Unit)? = null
    private var pendingFinish: ((ImportFinish) -> Unit)? = null
    private var watchdog: Job? = null

    /** True while a picker request is out and unanswered. */
    val hasPending: Boolean get() = pendingResult != null || pendingFinish != null

    /**
     * Opens the picker; [onDone] receives the created session id or `null`.
     * A dismissed dialog calls nothing (upstream's silent cancel); any failure
     * — including a timeout — reports `null`, which the caller already toasts
     * as `oc_session_import_failed` with the menu left enabled for a retry.
     */
    fun importSession(onDone: (String?) -> Unit) {
        if (!claim()) {
            onDone(null)
            return
        }
        pendingResult = onDone
        if (!openPicker()) {
            clearPending()
            onDone(null)
        }
    }

    /**
     * [importSession] with a user-visible outcome: [ImportFinish.Imported] on
     * success, [ImportFinish.Cancelled] on dismiss, [ImportFinish.Failed] when
     * the picker is missing/broken or an attempt is already in flight
     * ([ImportFailure.UNKNOWN] — the pre-existing generic line), the read or
     * route fails (its own [ImportFinish.Failed.reason]), or anything stays
     * silent past its timeout ([ImportFailure.TIMEOUT]). [scope] (main) runs the
     * watchdog; callers keep it screen-scoped so leaving the screen drops the
     * whole attempt.
     */
    fun importSession(scope: CoroutineScope, onFinish: (ImportFinish) -> Unit) {
        if (!claim()) {
            onFinish(failed(ImportFailure.UNKNOWN))
            return
        }
        pendingFinish = onFinish
        if (!openPicker()) {
            clearPending()
            onFinish(failed(ImportFailure.UNKNOWN))
            return
        }
        watchdog = scope.launch {
            sleeper(pickerTimeoutMs)
            onPickerTimeout(onFinish)
        }
    }

    /**
     * Drops the pending request, if any. A picker answer arriving afterwards is
     * ignored. Returns whether anything was pending.
     */
    fun cancelPending(): Boolean {
        val had = hasPending
        clearPending()
        return had
    }

    /** Called by [rememberSessionImporter] when the picker returns. */
    internal suspend fun deliver(uri: Uri?) {
        val result = pendingResult
        val finish = pendingFinish
        if (result == null && finish == null) return // reclaimed/cancelled — ignore the late answer
        clearPending()
        if (uri == null) {
            finish?.invoke(ImportFinish.Cancelled)
            return // cancelled — upstream shows nothing
        }
        // Off the caller's thread: a real provider read blocks, and a wedged
        // provider must cost a worker, never an ANR. A non-cooperative stall
        // past the timeout still leaks that worker until the provider answers,
        // but the callback below has already fired, so the UI is never wedged.
        val outcome = readAndImport { withContext(Dispatchers.IO) { readBytes(uri) } }
        result?.invoke((outcome as? ImportFinish.Imported)?.sessionId)
        finish?.invoke(outcome)
    }

    /**
     * Reads one export document via [read] and imports it, each stage bounded
     * by its timeout. Never throws: every failure (null bytes, malformed JSON,
     * missing `info`, a `{info, parts}` export, a throwing route, either
     * timeout) is an [ImportFinish.Failed] naming its [ImportFailure].
     *
     * The parsed document reaches [importTransfer] **verbatim** — re-keying the
     * identity is the thunk's business, next to the route call that demands it
     * ([postSessionImport]).
     *
     * The [Uri] handle stays in [deliver] so JVM tests can drive this with a
     * plain thunk — no Android runtime is needed for a `Uri` instance.
     */
    internal suspend fun readAndImport(read: suspend () -> String?): ImportFinish {
        val body = when (val raced = raceTimeout(readTimeoutMs) { runCatching { read() }.getOrNull() }) {
            is Race.TimedOut -> return failed(ImportFailure.TIMEOUT)
            is Race.Ok -> raced.value
        } ?: return failed(ImportFailure.FILE_UNREADABLE)
        val transfer = SessionExport.parseTransfer(body) ?: return failed(ImportFailure.FILE_FORMAT)
        if (SessionExport.hasUnimportableRows(transfer)) return failed(ImportFailure.FILE_ASSEMBLED)
        val attempt = when (
            val raced = raceTimeout(importTimeoutMs) {
                runCatching { importTransfer(transfer) }
            }
        ) {
            is Race.TimedOut -> return failed(ImportFailure.TIMEOUT)
            is Race.Ok -> raced.value
        } ?: return failed(ImportFailure.UNKNOWN)
        if (attempt.isFailure) return failed(importFailureOf(attempt), detailOf(attempt))
        val created = attempt.getOrNull() ?: return failed(ImportFailure.NO_SERVER)
        return ImportFinish.Imported(created)
    }

    /** The one terminal report a failure path builds: [ImportFinish.Failed.timedOut] follows [reason]. */
    private fun failed(reason: ImportFailure, detail: String? = null): ImportFinish =
        ImportFinish.Failed(timedOut = reason == ImportFailure.TIMEOUT, reason = reason, detail = detail)

    private fun claim(): Boolean = !hasPending

    private fun openPicker(): Boolean {
        if (!hasOpenHandler()) return false
        return try {
            launchOpenDocument()
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun onPickerTimeout(onFinish: (ImportFinish) -> Unit) {
        if (pendingFinish == null) return
        clearPending()
        onFinish(failed(ImportFailure.TIMEOUT))
    }

    /**
     * Races [block] against [sleeper]: whichever finishes first wins, the
     * loser is cancelled. The injected [sleeper] is what makes every timeout
     * branch deterministic under plain JVM tests — no virtual-time library,
     * no real waiting.
     */
    private suspend fun <T> raceTimeout(timeoutMs: Long, block: suspend () -> T?): Race<T> =
        coroutineScope {
            val work = async { block() }
            val timer = async { sleeper(timeoutMs) }
            select<Race<T>> {
                work.onAwait { timer.cancel(); Race.Ok(it) }
                timer.onAwait { work.cancel(); Race.TimedOut }
            }
        }

    private fun clearPending() {
        watchdog?.cancel()
        watchdog = null
        pendingResult = null
        pendingFinish = null
    }
}

private sealed interface Race<out T> {
    data class Ok<T>(val value: T?) : Race<T>
    data object TimedOut : Race<Nothing>
}

/**
 * The route's own failure, classified for the toast: a server refusal
 * ([V2Api.ApiException], any non-2xx) is [ImportFailure.SERVER] and keeps the
 * server's words in the detail, a dead socket ([IOException]) is
 * [ImportFailure.NETWORK], anything else stays [ImportFailure.UNKNOWN].
 */
private fun importFailureOf(attempt: Result<*>): ImportFailure {
    val error = attempt.exceptionOrNull() ?: return ImportFailure.UNKNOWN
    return when {
        error is V2Api.ApiException -> ImportFailure.SERVER
        error is IOException -> ImportFailure.NETWORK
        else -> ImportFailure.UNKNOWN
    }
}

/** The server's own words, capped: `HTTP 409 ConflictError: Session already exists: ses_…`. */
private fun detailOf(attempt: Result<*>): String? =
    (attempt.exceptionOrNull() as? V2Api.ApiException)?.message?.take(IMPORT_DETAIL_CAP)

/**
 * The production import thunk: resolve one [server], re-key the picked document
 * ([rekeyForImport]) and POST it, answering the created session id.
 *
 * `null` means the server has no usable v2 base url, which the caller reports
 * as [ImportFailure.NO_SERVER]; a refusal arrives as [V2Api.ApiException] and a
 * dead socket as `IOException`, both classified by
 * [SessionImporter.readAndImport].
 *
 * The re-key belongs here, beside the route that demands it, rather than in the
 * picker/read orchestration: see [rekeyForImport] for the two collisions it
 * avoids. Extracted from the composable so the whole wire shape — a fresh
 * `ses_`/`msg_` identity over the picked transcript, no `location` query — is
 * JVM-testable ([SessionImportRoundtripTest] drives this exact function).
 */
internal suspend fun postSessionImport(
    server: ServerConfigStore.Server,
    locationDirectory: String?,
    document: SessionTransferData,
): String? {
    val api = V2Api.forServer(server.url, server.username, server.password) ?: return null
    val transfer = SessionExport.rekeyForImport(document)
    return api.sessionImport(transfer, locationDirectory).id
}

/** Picker + import handle; the counterpart of [rememberSessionExportSaver]. */
@Composable
fun rememberSessionImporter(
    locationDirectory: String? = null,
): SessionImporter {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val serverStore = remember(context) { ServerConfigStore(context).also { it.restore() } }
    val launchHolder = remember { arrayOfNulls<() -> Unit>(1) }
    val importer = remember(serverStore, locationDirectory) {
        SessionImporter(
            readBytes = { uri ->
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        stream.readBytes().toString(Charsets.UTF_8)
                    }
                }.getOrNull()
            },
            launchOpenDocument = { launchHolder[0]?.invoke() },
            hasOpenHandler = { hasImportPicker(context) },
            importTransfer = { document ->
                withContext(Dispatchers.IO) {
                    val server = serverStore.server ?: return@withContext null
                    postSessionImport(server, locationDirectory, document)
                }
            },
        )
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        scope.launch { importer.deliver(uri) }
    }
    launchHolder[0] = { launcher.launch(arrayOf("application/json", "application/octet-stream", "*/*")) }
    return importer
}
