package com.xiufeigo.opencodeshell.ui.session.composer

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * W5-C B6: the SAF read round's progress, plus its cancel.
 *
 * The SAF launcher (`rememberAttachmentPicker`) is registered by the *screen* and
 * the attachment list also belongs to the screen, so this object is the one place
 * both halves can meet without the composer owning any of it: the picker enqueues
 * a read, the composer renders [state] (progress bar + 取消) and calls [cancel].
 *
 * Cancelling aborts the round at the next document and answers *nothing* to the
 * enqueueing callback — a cancelled pick must not add half a selection.
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

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    /**
     * Starts one read round. [onResult] runs on the main dispatcher and is skipped
     * entirely when the round is cancelled.
     */
    fun start(
        context: Context,
        uris: List<Uri>,
        onResult: (AttachmentReader.Result) -> Unit,
    ) {
        if (uris.isEmpty()) return
        job?.cancel()
        _state.value = State(running = true, total = uris.size, done = 0)
        job = scope.launch {
            try {
                val result = AttachmentReader.readAll(context, uris) { index, total, label ->
                    // `readAll` reports from IO; the flow write is safe from any
                    // thread, so no extra hop is needed.
                    _state.value = State(
                        running = true,
                        total = total,
                        done = (index - 1).coerceAtLeast(0),
                        label = label,
                    )
                }
                _state.value = State()
                onResult(result)
            } catch (e: Exception) {
                Log.w(TAG, "attachment read failed: ${e.message}")
                _state.value = State()
            }
        }
    }

    /** 取消: aborts the in-flight round; the picker's callback never fires. */
    fun cancel() {
        job?.cancel()
        job = null
        _state.value = State()
    }

    /** Test seam. */
    fun reset() {
        cancel()
    }

    private const val TAG = "AttachmentUploads"
}
