package com.xiufeigo.opencodeshell.ui.session.export

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.selects.select

/**
 * How long an export payload read (`{info, messages}`, session half + every
 * message page) may stay unanswered.
 *
 * Deliberately the same order as the two other watches the export path already
 * runs — [SessionExportFallback.SAF_TIMEOUT_MS] for the SAF dialog and
 * `IMPORT_REQUEST_TIMEOUT_MS` for the import route — and comfortably above the
 * client's own `readTimeout` (30 s), so a slow-but-answering server is never
 * cut off, while the read the v9 run watched sit for 12–75 s still ends in a
 * reported outcome instead of a mask that closes without saying anything.
 */
const val EXPORT_FETCH_TIMEOUT_MS = 60_000L

/** How one deadline-bounded export stage ended. */
sealed interface ExportStageResult<out T> {
    /** [block] answered in time. */
    data class Ok<T>(val value: T) : ExportStageResult<T>

    /** [block] threw; nothing was reported to the user yet. */
    data class Failed(val cause: Throwable) : ExportStageResult<Nothing>

    /** [block] was still running when the deadline expired; it was cancelled. */
    data object TimedOut : ExportStageResult<Nothing>
}

/**
 * Runs [block] on [scope] and returns **without waiting for the loser**: [Ok]
 * when it answers inside [timeoutMs], [TimedOut] when the deadline wins and the
 * still-running body is cancelled, [Failed] when it throws.
 *
 * `withTimeout` cannot bound this stage — its cancellation reaches the block at
 * the block's next suspension point, so a `withContext(Dispatchers.IO)` body
 * stuck in a blocking socket read (the transport's own 30 s) keeps the caller
 * waiting past the deadline. Handing [block] to a job on [scope] and racing
 * *that* is what makes the deadline a real one: the timeout frame is what the
 * caller returns on, and the abandoned read is left to die on its own.
 *
 * [sleeper] is the injection point the JVM test uses to fire the deadline
 * without waiting for it, exactly like [SessionImporter]'s.
 */
suspend fun <T> withExportDeadline(
    scope: CoroutineScope,
    timeoutMs: Long = EXPORT_FETCH_TIMEOUT_MS,
    sleeper: suspend (Long) -> Unit = { delay(it) },
    block: suspend () -> T,
): ExportStageResult<T> = coroutineScope {
    // `runCatching` keeps a throwing body out of the Deferred's un-awaited
    // failure path (a failed `async` would cancel the screen's scope).
    val work = scope.async { runCatching { block() } }
    val timer = async { sleeper(timeoutMs) }
    try {
        select<ExportStageResult<T>> {
            work.onAwait { outcome ->
                timer.cancel()
                outcome.fold(
                    onSuccess = { ExportStageResult.Ok(it) },
                    onFailure = { ExportStageResult.Failed(it) },
                )
            }
            timer.onAwait {
                work.cancel()
                ExportStageResult.TimedOut
            }
        }
    } finally {
        timer.cancel()
    }
}
