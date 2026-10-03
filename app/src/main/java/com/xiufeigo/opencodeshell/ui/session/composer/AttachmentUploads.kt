package com.xiufeigo.opencodeshell.ui.session.composer

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.InputStream

/**
 * `Route.UPLOAD`'s sink: streams one pick to the server's temp dir and hands
 * back the resolved absolute path (`POST /api/experimental/fs/write` —
 * upstream `composer/attachments/destination.ts:24-31`). Blocking; the reader
 * calls it on `Dispatchers.IO`.
 *
 * [open] yields the pick's raw bytes as a stream (a hundreds-of-MB file is
 * never buffered — see `AttachmentPolicy.readCapped`'s OOM note); the callee
 * closes it. Wire it with `V2Api.fsWriteUpload(name, open().octetStreamBody(),
 * tmp)` (`v2/V2ApiFsWrite.kt`).
 */
fun interface AttachmentUploader {
    fun upload(filename: String, mime: String, open: () -> InputStream): String
}

/**
 * W5-C B6: the SAF read round's progress, plus its cancel.
 *
 * The SAF launcher (`rememberAttachmentPicker`) is registered by the *screen* and
 * the attachment list also belongs to the screen, so this object is the one place
 * both halves can meet without the composer owning any of it: the picker enqueues
 * a read, the composer renders [state] (progress bar + 取消) and calls [cancel].
 *
 * Rounds run one after another through [AttachmentRoundQueue]: starting while a
 * round is in flight **queues** the new pick — cancelling the in-flight SAF
 * selection used to drop it with no callback at all. Cancelling still aborts
 * the active round at the next document, drops whatever waits behind it, and
 * answers *nothing* — a cancelled pick must not add half a selection.
 *
 * The three [destination][AttachmentDestination] knobs below default to the
 * pre-destination behaviour (inline `data:` chips, no uploads). The view model
 * that owns the `V2Api` overwrites them on connect — see the delivery notes:
 * [uploader] and consuming `AttachmentReader.Result.outcomes` land together.
 */
internal object AttachmentUploads {

    /** `AttachmentReader.readAll`'s progress, ready to render. */
    data class State(
        val running: Boolean = false,
        val total: Int = 0,
        val done: Int = 0,
        val label: String? = null,
    ) {
        /** `done / total` in `0f..1f`; a finished/idle round reports 0. */
        val fraction: Float
            get() = if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)

        /** `"2 / 5"` progress text, or null while idle. */
        val counter: String?
            get() = if (total <= 0) null else "$done / $total"
    }

    // Lazy: JVM tests touch the nested `State` without initializing the
    // object, whose scope needs `Dispatchers.Main` (absent on the JVM).
    private val queue by lazy {
        AttachmentRoundQueue<AttachmentReader.Result>(
            CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        )
    }

    /**
     * Model input modalities for `AttachmentDestination.route`
     * (`capabilities.input`). Defaults assume a multimodal model so images and
     * PDFs stay inline until the model catalog is plumbed through.
     */
    @Volatile
    var input: AttachmentDestination.Input = AttachmentDestination.Input(image = true, pdf = true)

    /** Upstream `server.isLocal` — false while the server runs on another machine. */
    @Volatile
    var local: Boolean = false

    /**
     * `Route.UPLOAD` sink (`fsWrite`). Null — nothing owns the seam yet — makes
     * a pick that routes to upload fall back to the inline pack when it fits
     * `AttachmentPolicy.MAX_ATTACHMENT_BYTES` (pre-destination behaviour), so no
     * pick disappears before the view model can consume path outcomes.
     */
    @Volatile
    var uploader: AttachmentUploader? = null

    val state: StateFlow<State>
        get() = queue.state

    /**
     * Starts one read round. [onResult] runs on the main dispatcher and is
     * skipped entirely when the round is cancelled; a round already in flight
     * finishes first — this one waits instead of cancelling it. The destination
     * knobs are captured when the round is *enqueued*.
     */
    fun start(
        context: Context,
        uris: List<Uri>,
        onResult: (AttachmentReader.Result) -> Unit,
    ) {
        if (uris.isEmpty()) return
        val destinationInput = input
        val destinationLocal = local
        val destinationUploader = uploader
        queue.enqueue(
            total = uris.size,
            read = { report ->
                // `readAll` reports from IO; the flow write is safe from any
                // thread, so no extra hop is needed.
                AttachmentReader.readAll(
                    context = context,
                    uris = uris,
                    input = destinationInput,
                    local = destinationLocal,
                    uploader = destinationUploader,
                ) { index, total, label ->
                    report(index, total, label)
                }
            },
            deliver = onResult,
        )
    }

    /** 取消: aborts the in-flight round and drops queued ones; no callback ever fires. */
    fun cancel() = queue.cancel()

    /** Test seam. */
    fun reset() = cancel()
}

/**
 * Sequential read rounds behind [AttachmentUploads] — pure Kotlin (injected
 * scope, no Android types in the read path) so the race rules are JVM-tested
 * in `AttachmentRoundQueueTest`.
 *
 * Why this class exists — what the old single-`job` version broke:
 * 1. [enqueue] **queues** behind the active round. `start` used to
 *    `job?.cancel()` first, so「+ → 粘贴」during a SAF read cancelled the
 *    selection mid-flight and its callback never ran: the pick vanished.
 * 2. A cancelled round rethrows `CancellationException`. Swallowing it in
 *    `catch (e: Exception)` (a `CancellationException` *is* an Exception) wrote
 *    a fresh idle state from the dying round and wiped the replacement round's
 *    `running` flag.
 * 3. [generation] tags every started round and every cancel; progress writes
 *    already in flight on the IO thread compare against it, so a dead round
 *    can never resurrect the progress bar.
 *
 * [enqueue]/[cancel] must be called from the scope's thread (main in the app);
 * the state writes from the reader's IO thread are safe because
 * `StateFlow.value` is atomic.
 */
internal class AttachmentRoundQueue<T>(
    private val scope: CoroutineScope,
) {

    private class Round<T>(
        val total: Int,
        val read: suspend ((Int, Int, String?) -> Unit) -> T,
        val deliver: (T) -> Unit,
    )

    private val _state = MutableStateFlow(AttachmentUploads.State())
    val state: StateFlow<AttachmentUploads.State> = _state.asStateFlow()

    private val waiting = ArrayDeque<Round<T>>()
    private var job: Job? = null

    @Volatile
    private var generation = 0

    fun enqueue(
        total: Int,
        read: suspend ((Int, Int, String?) -> Unit) -> T,
        deliver: (T) -> Unit,
    ) {
        waiting.addLast(Round(total, read, deliver))
        pump()
    }

    fun cancel() {
        generation++
        waiting.clear()
        job?.cancel()
        job = null
        _state.value = AttachmentUploads.State()
    }

    private fun pump() {
        if (job?.isActive == true) return
        if (waiting.isEmpty()) return
        val round = waiting.removeFirst()
        val id = ++generation
        _state.value = AttachmentUploads.State(running = true, total = round.total, done = 0)
        val launched = scope.launch {
            try {
                val result = round.read { index, total, label ->
                    if (id == generation) {
                        _state.value = AttachmentUploads.State(
                            running = true,
                            total = total,
                            done = (index - 1).coerceAtLeast(0),
                            label = label,
                        )
                    }
                }
                if (id == generation) {
                    _state.value = AttachmentUploads.State()
                    round.deliver(result)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "attachment read failed: ${e.message}")
                if (id == generation) _state.value = AttachmentUploads.State()
            } finally {
                if (job === coroutineContext[Job]) job = null
                pump()
            }
        }
        // Main.immediate/Unconfined rounds may finish (and pump a successor)
        // inline before `launch` returns — only claim the slot when it is free.
        if (job?.isActive != true) {
            job = launched.takeIf { it.isActive }
        }
    }

    private companion object {
        const val TAG = "AttachmentRoundQueue"
    }
}