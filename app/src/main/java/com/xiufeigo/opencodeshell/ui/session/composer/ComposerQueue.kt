package com.xiufeigo.opencodeshell.ui.session.composer

/**
 * WAVE 2.4b (P0-7): the composer-side queue model — prompts queued behind the
 * running turn.
 *
 * Upstream (`session/composer/queue.ts` + `queue-panel.tsx`):
 * - queued prompts are server inbox items (`SessionInboxInfo{type:"user",
 *   payload:{text,files,agents,skills,metadata},delivery:"queue"}`); the panel
 *   lists them above the composer (`data-component="session-queue-panel"`,
 *   `-mb-3` overlap so panel + composer read as one surface), each row showing
 *   the display text (+ `N attachments`) with hover actions **Steer** (promote
 *   to the running turn via `PATCH …/inbox/{id}{delivery:"steer"}`) and
 *   **Remove** (`DELETE …/inbox/{id}`), plus drag-reorder (implemented as
 *   re-admit-changed-suffix + cancel-old — `queue.ts:100-131` `rewrite`) and
 *   **edit** (loads the prompt back into the composer; Enter confirms in place
 *   as queue, the alternate action steers — `queue.ts:183-208`).
 * - while the turn is stopped the queue parks: the first row's actions stay
 *   visible and its label reads **Send** (`queue-panel.tsx:89-92,175`).
 *
 * Native shape: the server round-trips (`inbox.list/cancel/update`, prompt
 * re-admit) belong to the VM/transport layer (a parallel agent owns
 * `ChatViewModel`), so this object is the **pure, JVM-testable ordering +
 * edit-session half** — add/remove/reorder/promote rows plus the one-shot edit
 * loan — with generation gating ([isQueueSupported]) so the UI can hide the
 * whole surface on v1 (no inbox API there; v1 keeps send-and-wait).
 *
 * The VM integrator mirrors these rows against `session.inbox.*` events
 * (`enqueued/delivered/cancelled/delivery.changed` → list freshness) and
 * drains them on turn end; the contract is written in
 * `research/v2_audit/wave2.4b-notes.md` §VM.
 */
object ComposerQueue {

    /**
     * False on v1: `OpenCodeClient` has no inbox endpoint and `V1Transport`
     * drops `delivery`, so queueing would silently misfire — the composer keeps
     * send-and-wait there. True on v2 (and unknown, optimistically: the probe
     * resolves before the first submit and corrects it).
     */
    fun isQueueSupported(generationName: String?): Boolean =
        generationName?.trim()?.uppercase() != "V1"

    /** One queued follow-up as the panel shows it (`queuedPromptRows`). */
    data class Row(
        /** Server inbox id (`msg_…`); the optimistic association key. */
        val id: String,
        /** Display text (`metadata.displayText` ?? `payload.text`). */
        val text: String,
        /** `payload.files` count + `metadata.attachments` count. */
        val attachmentCount: Int = 0,
        /** The structured resubmission payload (edit confirm / steer need it). */
        val submission: ComposerSubmission.Model? = null,
    )

    /**
     * The ordered queue. Rows are keyed by [Row.id] (upstream keys panel rows
     * by id so store updates move rows instead of remounting them —
     * `queue-panel.tsx:61-62`).
     */
    class Model(rows: List<Row> = emptyList()) {

        private val _rows = rows.toMutableList()
        val rows: List<Row> get() = _rows.toList()

        /** The one-shot edit loan: the queued prompt loaded in the composer. */
        var editingId: String? = null
            private set

        val count: Int get() = _rows.size
        val isEmpty: Boolean get() = _rows.isEmpty()
        val editing: Row? get() = editingId?.let { id -> _rows.find { it.id == id } }

        /** Adds a queued follow-up (server ack of a `delivery=queue` prompt). */
        fun add(row: Row) {
            if (row.id.isBlank()) return
            if (_rows.none { it.id == row.id }) _rows += row
        }

        /**
         * Removes a queued prompt (user Remove, `inbox.cancelled`, or
         * `inbox.delivered`). Cancels a matching edit loan.
         */
        fun remove(id: String): Boolean {
            if (editingId == id) editingId = null
            return _rows.removeAll { it.id == id }
        }

        /**
         * Promotes a queued prompt to steer (`PATCH …/inbox/{id}{delivery}`):
         * the row leaves the queue; the running turn picks it up.
         */
        fun promoteToSteer(id: String): Row? {
            val row = _rows.find { it.id == id } ?: return null
            remove(id)
            return row
        }

        /**
         * Reorders to the given id sequence (drag-reorder). Unknown ids are
         * ignored; rows missing from [orderedIds] keep their relative order at
         * the end — the VM only calls this with the panel's full id list, so a
         * short list means a stale drag, not a deletion.
         */
        fun reorder(orderedIds: List<String>) {
            if (orderedIds.isEmpty()) return
            val byId = _rows.associateBy { it.id }
            val seen = LinkedHashSet<String>()
            val next = ArrayList<Row>(_rows.size)
            for (id in orderedIds) {
                if (seen.add(id)) byId[id]?.let { next += it }
            }
            for (row in _rows) {
                if (seen.add(row.id)) next += row
            }
            _rows.clear()
            _rows.addAll(next)
        }

        /** Replaces the rows wholesale (server `inbox.list` / event refresh). */
        fun replace(rows: List<Row>) {
            _rows.clear()
            val seen = LinkedHashSet<String>()
            for (row in rows) {
                if (row.id.isNotBlank() && seen.add(row.id)) _rows += row
            }
            if (editingId != null && _rows.none { it.id == editingId }) editingId = null
        }

        /**
         * Loads a queued prompt into the composer for editing
         * (`queue.ts:147-171` `edit`): answers the row, or null when there is
         * nothing to edit. The composer shows the row's text; Enter confirms
         * via [confirmEdit], Esc via [cancelEdit].
         */
        fun edit(id: String): Row? {
            val row = _rows.find { it.id == id } ?: return null
            editingId = id
            return row
        }

        /** First row into the editor (the panel's Send/steer affordance). */
        fun editFirst(): Row? = _rows.firstOrNull()?.let { edit(it.id) }

        /**
         * Confirms the edit (`queue.ts:183-208` `confirmEdit`): the composer
         * value becomes the replacement. Answers the edited id, or null when
         * no edit is open. The VM re-admits the replacement and cancels the
         * original (admit-before-cancel, so a failed replacement never
         * discards the original — `queue.ts:65-74`); a pristine value with
         * `delivery=queue` is a no-op cancel upstream — the VM compares before
         * calling, this just closes the loan.
         */
        fun confirmEdit(): String? {
            val id = editingId ?: return null
            editingId = null
            return id
        }

        /** Abandons the edit; the queued row is untouched (`queue.ts:172-182`). */
        fun cancelEdit() {
            editingId = null
        }

        /** Drain after the turn settles (delivered rows leave one by one). */
        fun clear() {
            _rows.clear()
            editingId = null
        }
    }

    /** Empty queue factory (the composer holds one per session key). */
    fun empty(): Model = Model()

    /**
     * Panel header (`session.queue.count`, `queue-panel.tsx:30-34`): shown only
     * past 3 rows; the generated `session_queue_count_*` strings carry it.
     */
    fun showCount(count: Int): Boolean = count > 3

    /**
     * Row actions stay visible without hover for the parked first row
     * (`queue-panel.tsx:89-92` `active()`): the turn is stopped and this is row 0.
     */
    fun isParkedFirst(running: Boolean, index: Int): Boolean = !running && index == 0

    /**
     * One action in a queue row's `session-queue-actions` container
     * (`queue-panel.tsx:150-191`).
     *
     * - [STEER] — the text button `data-action="session-queue-steer"`
     *   (line 166), icon `arrow-up`, label `session.queue.steer` while the turn
     *   runs and `session.queue.send` once it parks (line 175). It is wrapped in
     *   `<Show when={!editing()}>` (line 159), so an editing row has no steer
     *   action at all.
     * - [REMOVE] — the `outline-xmark` icon button `data-action="session-queue-remove"`
     *   (line 181-188), `aria-label` `session.queue.remove`. It is **not** inside
     *   the `<Show>`, so it survives on an editing row — the only way out of an
     *   edit that has not been confirmed.
     */
    enum class RowAction { STEER, REMOVE }

    /** The label [RowAction.STEER] carries (`queue-panel.tsx:175`). */
    enum class SteerLabel { STEER, SEND }

    /**
     * The ordered action set one row renders. Order is upstream's JSX order, not
     * a convenience: the container is a single flex line, so `[STEER, REMOVE]`
     * is the only left-to-right reading it can produce.
     */
    data class RowActions(
        val actions: List<RowAction>,
        val steerLabel: SteerLabel,
    ) {
        val hasSteer: Boolean get() = RowAction.STEER in actions
        val hasRemove: Boolean get() = RowAction.REMOVE in actions
    }

    /**
     * The actions a row renders (`queue-panel.tsx:150-191`).
     *
     * Visibility is **not** modelled here, and that is deliberate: upstream
     * gates the container on hover
     * (`opacity-0 … group-hover/queue-row:opacity-100`, line 154) but ends the
     * same class list with `[@media(hover:none)]:opacity-100` — on a touch
     * screen there is no hover, so every row's actions are always visible and
     * only `busy()` (`pointer-events-none`, line 156) turns inert. A touch
     * renderer therefore never reproduces upstream's `opacity-0` state, and
     * a "hover to reveal" port would hide the only way to cancel a queued
     * prompt behind a gesture the platform has no notion of.
     *
     * @param running the session turn is running (`queue.working()`).
     * @param editing this row is loaded in the composer for edit.
     */
    fun rowActions(running: Boolean, editing: Boolean): RowActions = RowActions(
        actions = if (editing) listOf(RowAction.REMOVE) else listOf(RowAction.STEER, RowAction.REMOVE),
        steerLabel = if (running) SteerLabel.STEER else SteerLabel.SEND,
    )

    // ──────────────────────────────────────────────────────────────────────
    // v131: the edit loan's exit table. Upstream keeps ONE loan (`state.editing`
    // in `queue.ts`) and **converges** it against `queued()` on every read
    // (`queue.ts:93-97`), so a loan can never outlive its row. The native port
    // had two copies of the fact (the row + a separate `queueEditOpen` latch)
    // maintained by *discrete events only*, so the confirm path closed one and
    // left the other stuck on — and `ComposerDelivery.alternate`'s first line
    // (`if (editingQueued) return STEER`) then pinned the chip to `Steer` for
    // the rest of the composer's life (v130, emulator-5554). These two pure
    // functions are the whole loan state machine: the composer derives the chip
    // from the loan and drives the loan through them, so "只能进不能出" is not
    // expressible.
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Why a queued prompt's loan is being given up.
     *
     * - [CONFIRM] — Enter on an open edit re-admits the replacement and cancels
     *   the original (`queue.ts:65-74` `confirmEdit`; a pristine value with
     *   `delivery=queue` is a no-op cancel upstream, `queue.ts:197`).
     * - [CANCEL] — Esc abandons the edit, the row untouched (`queue.ts:172-182`;
     *   upstream guards with `if (!editing) return`, so it is idempotent).
     * - [SESSION_SWITCH] — the loan is composer-local state, so it must not
     *   survive a draft-key change (v67 B2, the same reset for `tokens`).
     * - [STEER] / [REMOVE] — promoting or cancelling **the edited row itself**
     *   closes its loan (`queue.ts:133`, `queue.ts:139`); either action on some
     *   *other* row is not an exit for this loan and leaves it open.
     */
    enum class EditLoanEvent { CONFIRM, CANCEL, SESSION_SWITCH, STEER, REMOVE }

    /**
     * The loan that survives [event], applied to [row] — total and idempotent:
     * defined for every (loan, event) pair, and a terminal event on an
     * already-closed loan stays closed instead of throwing or reviving it.
     *
     * @param targetId the row the [EditLoanEvent.STEER] / [EditLoanEvent.REMOVE]
     * was aimed at; `null` (or a different id) means "not this loan's row".
     */
    fun endEditLoan(row: Row?, event: EditLoanEvent, targetId: String? = null): Row? = when (event) {
        EditLoanEvent.CONFIRM,
        EditLoanEvent.CANCEL,
        EditLoanEvent.SESSION_SWITCH,
        -> null
        EditLoanEvent.STEER,
        EditLoanEvent.REMOVE,
        -> if (row != null && row.id == targetId) null else row
    }

    /**
     * Upstream's convergence effect (`queue.ts:93-97`): a loan whose row is no
     * longer in the queue is dropped, so a row consumed by the running turn,
     * removed, or drained when the turn settles takes the loan with it.
     *
     * The [busy] guard is upstream's `mutation.isPending`: while a queue
     * mutation is in flight the loan is left alone, because the mutation's own
     * outcome (a steer/remove of this row) is what closes it — a second,
     * premature close would strand the in-flight re-admit. It is a *delay*,
     * never a lock: the very next converged read with `busy = false` closes it.
     */
    fun convergeEditLoan(row: Row?, queuedIds: Collection<String>, busy: Boolean): Row? =
        if (row == null || busy) row else row.takeIf { it.id in queuedIds }
}
