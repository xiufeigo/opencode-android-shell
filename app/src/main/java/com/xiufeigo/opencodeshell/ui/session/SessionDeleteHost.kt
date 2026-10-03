package com.xiufeigo.opencodeshell.ui.session

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The session delete card: which session the ⋮ → 删除… / a drawer row's 删除…
 * asked about, and how far its `DELETE` got.
 *
 * [busy] is the lock the v69 P2 fix put on the buttons, [failed] the server's
 * refusal — the card stays up with the reason so the same 删除 is a retry
 * (`SessionDeleteDialog`'s contract, shared with `HomeDeleteDialog`).
 */
data class SessionDeleteCard(
    val id: String,
    val title: String,
    val directory: String? = null,
    val busy: Boolean = false,
    val failed: Boolean = false,
)

/**
 * The card's rules, pure so a JVM test can drive every transition.
 *
 * The invariant the whole flow rests on: **a card never changes its target while
 * its write is out** ([asked] refuses), so an answer that arrives late still
 * belongs to the card it was issued for — a `DELETE` answered after the user
 * armed another row can therefore never paint one session's refusal on another
 * session's card.
 */
object SessionDeleteModel {

    /**
     * Arms the card for one session. An empty id is refused (nothing to delete),
     * and so is a re-arm while a write is out — the card's target is pinned for
     * the whole write.
     */
    fun asked(
        current: SessionDeleteCard?,
        id: String,
        title: String,
        directory: String?,
    ): SessionDeleteCard? = when {
        id.isEmpty() -> current
        current?.busy == true -> current
        else -> SessionDeleteCard(id = id, title = title, directory = directory)
    }

    /**
     * The card a tap may put a `DELETE` behind, or null when there is nothing to
     * write (no card) or a write is already out — the re-entry guard, which is
     * what keeps a second tap from stacking a second `DELETE`.
     */
    fun writable(current: SessionDeleteCard?): SessionDeleteCard? =
        current?.takeIf { !it.busy }?.copy(busy = true, failed = false)

    /**
     * The card after the server answered: gone on a confirmed delete, still up
     * and unlocked with the reason on a refusal. A card the user dismissed
     * while the write was out stays dismissed — the answer is not a reason to
     * put a card back on screen.
     */
    fun settled(current: SessionDeleteCard?, deleted: Boolean): SessionDeleteCard? = when {
        current == null -> null
        deleted -> null
        else -> current.copy(busy = false, failed = true)
    }
}

/**
 * The not-found page's own entry delete (v83 P1): the id one 「删除该条目」 is
 * about, and how far its `DELETE` got.
 *
 * Separate from [SessionDeleteCard] because this exit has no question to ask —
 * the tap already is the answer, on a page whose whole subject is the entry the
 * user wants gone — so the states are only "the write is out" ([busy]) and "the
 * server refused" ([failed]), and the page paints both on itself
 * ([com.xiufeigo.opencodeshell.ui.session.SessionNotFoundPanel]).
 */
data class SessionDeleteEntry(val id: String, val busy: Boolean = false, val failed: Boolean = false)

/**
 * [SessionDeleteEntry]'s rules — the same three transitions as
 * [SessionDeleteModel], and pure for the same reason: a `DELETE`'s answer
 * arrives after the tap, so it must never paint on another entry, and a second
 * tap must not stack a second write.
 */
object SessionDeleteEntryModel {

    /** Arms the flow for one entry; an empty id is refused, and so is a re-arm while a write is out. */
    fun asked(current: SessionDeleteEntry?, id: String): SessionDeleteEntry? = when {
        id.isEmpty() -> current
        current?.busy == true -> current
        else -> SessionDeleteEntry(id)
    }

    /** The state a tap may put a `DELETE` behind, or null when there is none / one is already out. */
    fun writable(current: SessionDeleteEntry?): SessionDeleteEntry? =
        current?.takeIf { !it.busy }?.copy(busy = true, failed = false)

    /** The answer: nothing left to show on a delete that landed, the reason on a refusal. */
    fun settled(current: SessionDeleteEntry?, deleted: Boolean): SessionDeleteEntry? = when {
        current == null -> null
        deleted -> null
        else -> current.copy(busy = false, failed = true)
    }
}

/**
 * v70 P2 — the delete card's state and its write, owned **above** the session
 * screen.
 *
 * `research/v70_verify`'s two machines disagree about the v69 fix: 5560 (phone
 * form) kept the card up with 「Not deleted — the server did not confirm it」 on
 * a dead server, 5556 (desktop form) lost it — no reason, no error, and the
 * `DELETE` that did reach the server left the tab up. The state and the answer
 * both lived in `SessionScreen`'s own `remember`s, so anything that recomposes
 * or re-creates that screen between the tap and the answer takes them with it:
 * the card unmounts, the failure has nowhere to paint, and the success half —
 * which is what closes the tab — never runs. The banner in the timeline was
 * never the cause; it only made the disappearance look explained.
 *
 * So the card is a host object built by `ui/App.kt` (the scope that already owns
 * the tab set and the nav controller) and published as
 * [LocalSessionDeleteHost] — the same shape as `SessionActionsHost` /
 * `SessionBtwHost`. Three things follow:
 *
 * 1. **Both entry points are one card.** The header ⋮ and a drawer row call
 *    [ask] on the same object, so the two paths cannot drift apart — the v69
 *    fix had to be written twice, and only one of them survived the device.
 * 2. **A refusal cannot be lost.** The write's answer is written into this
 *    object, not into a composition-scoped `remember`, so a recomposed or
 *    re-created screen still shows the reason.
 * 3. **A confirmed delete always reaches the tab set.** [onConfirmed] is
 *    `ui/App.kt`'s tab decision (`SessionTabs.afterDeleteFromElsewhere`) and
 *    runs even when the screen that asked is gone — which is exactly the
 *    「删除成功但 tab 不同步关」 the desktop machine showed.
 *
 * The only Android-bound part is the `write` lambda
 * (`ChatViewModel.deleteSession`); every transition above is pure.
 */
class SessionDeleteHost(
    /** `ChatViewModel.deleteSession`'s shape: one `DELETE`, one answer, never throwing. */
    private val write: (id: String, directory: String?, onResult: (Boolean) -> Unit) -> Unit,
    /** A confirmed delete: the shell closes the tab and moves the route (see the class KDoc). */
    private val onConfirmed: (String) -> Unit = {},
) {

    /** The open card, or null while no delete is being confirmed. */
    var card by mutableStateOf<SessionDeleteCard?>(null)
        private set

    /**
     * The not-found page's own exit state (v83 P1) — kept here and not on the
     * page for the same reason the card is: the `DELETE`'s answer lands after the
     * tap, and a recomposed or re-created screen must not lose it. The page reads
     * it back per session id, so a refusal latched for one entry can never paint
     * on another.
     */
    var entry by mutableStateOf<SessionDeleteEntry?>(null)
        private set

    /**
     * The ids the server confirmed gone in this run. The screen's own drawer
     * list drops them at once, the way Home's tombstone does
     * (`dropDeletedSessions`), so a catalogue read that was already in flight
     * cannot put a deleted row back under the finger.
     */
    var confirmed by mutableStateOf<Set<String>>(emptySet())
        private set

    /** Arms the card for one session (both entry points come through here). */
    fun ask(id: String, title: String, directory: String? = null) {
        card = SessionDeleteModel.asked(card, id, title, directory)
    }

    /**
     * v81 P2 「幽灵会话死胡同」: drop an entry **everywhere** — the local half of
     * the not-found page's 「删除该条目」 (`SessionNotFoundPanel`).
     *
     * Write-free by construction, and since v83 P1 it is no longer the *only*
     * step: the page asks the server first ([deleteEntry]) and reaches this only
     * on an answer that means the session is gone (a `DELETE`'s `204`, or the
     * `404` of [deleteEntry]'s "already lost" case). The id joins [confirmed] for
     * the same reason a server-confirmed delete does: every surface drops it at
     * once (the drawer list, Home's tombstone mirror, the tab through
     * [onConfirmed]), and a catalogue read already in flight cannot put the row
     * back under the finger.
     */
    fun forget(id: String) {
        if (id.isEmpty()) return
        card = null
        confirmed = confirmed + id
        onConfirmed(id)
    }

    /**
     * v83 P1 — the not-found page's 「删除该条目」: **the server first, the entry
     * second** (`research/v83_verify/5558/25_after_delete.png` →
     * `26_list_after_del.png`).
     *
     * v81 made this exit local-only, and its rationale was the `404` shape: a
     * `DELETE` for a session the server has already forgotten answers `404`, which
     * [confirm]'s card reports as 「Not deleted」 — a second dead end for the one
     * action the user asked for. The device corrected the premise: the measured
     * `REFUSED` entry (the missing-project-directory session of
     * `research/v82_verify/5558/33_ghost_open.png`, whose scoped routes answer
     * `5xx`) answers `DELETE` with **`204`** and is really gone afterwards —
     * while the app only closed the tab, leaving the row in Home and the session
     * on the server (`GET` → `200`). Backing out of the dead end is not the same
     * as being rid of it.
     *
     * So the write goes out first, and both "gone" answers count as deleted:
     * `204` is a delete, and the `404` of a session the server never had
     * (`Transport.deleteSession` folds it into a confirmed answer) is the state
     * this page already describes. Anything else — no transport, a socket
     * failure, another status — keeps the page up with [entry].`failed` so the
     * exit is never silently a no-op, and the same button is the retry.
     */
    fun deleteEntry(id: String, directory: String? = null, onResult: (Boolean) -> Unit = {}) {
        val armed = SessionDeleteEntryModel.writable(SessionDeleteEntryModel.asked(entry, id)) ?: return
        entry = armed
        write(armed.id, directory) { deleted ->
            entry = SessionDeleteEntryModel.settled(entry, deleted)
            if (deleted) forget(armed.id)
            onResult(deleted)
        }
    }

    /** Cancel / backdrop / Escape — the user's own way out of an unanswered card. */
    fun dismiss() {
        card = null
    }

    /**
     * The card's 删除. Nothing to write (or a write already out) is a no-op, so
     * a double tap cannot stack a second `DELETE`; otherwise the card locks and
     * the answer either closes it or paints the reason.
     */
    fun confirm() {
        val armed = SessionDeleteModel.writable(card) ?: return
        card = armed
        write(armed.id, armed.directory) { deleted ->
            card = SessionDeleteModel.settled(card, deleted)
            if (deleted) {
                confirmed = confirmed + armed.id
                onConfirmed(armed.id)
            }
        }
    }
}

/**
 * The host `ui/App.kt` builds for the session route. Null outside a real host
 * (previews, a screen rendered without a shell) — the ⋮ and the drawer then
 * simply have no delete to offer, exactly like the other `Local…Host`s.
 */
val LocalSessionDeleteHost = compositionLocalOf<SessionDeleteHost?> { null }
