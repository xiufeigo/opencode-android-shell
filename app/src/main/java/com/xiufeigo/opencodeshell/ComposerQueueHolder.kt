package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.composer.ComposerQueue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * WAVE 2.4b (queue/steer VM half): the ViewModel-owned composer queue.
 *
 * The composer holds NO queue list — it only renders [rows] and forwards row
 * ids back through `onQueueSteer/onQueueRemove/onQueueConfirm`. This holder is
 * the single owner: the optimistic add on a `delivery=queue` admit, the
 * `session.inbox.*` refresh ([replace] from [rowFromJson]), and the
 * steer/cancel/edit mutations all funnel through it. One instance per
 * [ChatViewModel], cleared on every session switch (in-memory per session,
 * like history — deliberately no persistence).
 *
 * Threading: the inner [ComposerQueue.Model] is NOT synchronized — confine
 * every call to the Main thread (the ViewModel scope / UI callbacks).
 * Pure Kotlin except the flows, so the mapping is JVM-testable.
 */
class ComposerQueueHolder {

    private val model = ComposerQueue.Model()

    private val _rows = MutableStateFlow<List<ComposerQueue.Row>>(emptyList())
    val rows: StateFlow<List<ComposerQueue.Row>> = _rows.asStateFlow()

    private val _busy = MutableStateFlow(false)
    /** True while a queue mutation round-trip is in flight (rows render inert). */
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** Current rows (Main thread only). */
    fun current(): List<ComposerQueue.Row> = model.rows

    fun setBusy(busy: Boolean) {
        _busy.value = busy
    }

    /** Optimistic add: the server ack of a `delivery=queue` prompt. */
    fun add(row: ComposerQueue.Row) {
        model.add(row)
        publish()
    }

    /**
     * Optimistic remove (user Remove / steer-promote / edit-cancel-original).
     * Answers the removed row, or null when the id was unknown.
     */
    fun remove(id: String): ComposerQueue.Row? {
        val row = model.rows.find { it.id == id } ?: return null
        model.remove(id)
        publish()
        return row
    }

    /** Server truth: wholesale replace from `GET …/inbox` items. */
    fun replace(rows: List<ComposerQueue.Row>) {
        model.replace(rows)
        publish()
    }

    /** Session switch / teardown: forget everything, including the busy flag. */
    fun clear() {
        model.clear()
        _busy.value = false
        publish()
    }

    private fun publish() {
        _rows.value = model.rows
    }

    companion object {
        /**
         * One `GET …/inbox` item → panel row (`wave2.4b-notes.md` §VM 3), or
         * null when the item is not panel material. Upstream shows only parked
         * **queue** items (`queue.ts:80-84`
         * `type === "user" && item.delivery === "queue"`): a steer admission
         * that the server parked while busy drains on its own and never gets
         * a row, and non-user control items block reordering server-side.
         * Row fields: `id`, `metadata.displayText ?? payload.text`
         * (`metadata` is read under `payload` first — the inbox shape nests it
         * there — then top-level), `payload.files.length +
         * metadata.attachments.length`. Server rows carry no structured
         * resubmission (`submission = null`; re-submit goes from text).
         */
        fun rowFromJson(obj: JSONObject): ComposerQueue.Row? {
            if (obj.optString("type", "") != "user") return null
            if (obj.optString("delivery", "") != "queue") return null
            val id = obj.optString("id", "").takeIf { it.isNotBlank() } ?: return null
            val payload = obj.optJSONObject("payload")
            val metadata = payload?.optJSONObject("metadata") ?: obj.optJSONObject("metadata")
            val text = metadata?.optString("displayText", "")?.takeIf { it.isNotEmpty() }
                ?: payload?.optString("text", "").orEmpty()
            val fileCount = payload?.optJSONArray("files")?.length() ?: 0
            val attachmentCount = metadata?.optJSONArray("attachments")?.length() ?: 0
            return ComposerQueue.Row(
                id = id,
                text = text,
                attachmentCount = fileCount + attachmentCount,
                submission = null,
            )
        }
    }
}
