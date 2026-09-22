package com.xiufeigo.opencodeshell.notify

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What an [AppNotification] is about. The four kinds are exactly the four
 * triggers of E5 (`research/verify_wave4.md` §20 E5, upstream
 * `_upstream/packages/app/src/shell/notifications/notification.tsx:225-281`):
 * a finished turn, a permission ask, a question ask and a failed turn.
 *
 * The kind — not a pre-rendered sentence — is what the UI renders: each kind has
 * its own `oc_notify_*` string (`notify/AppNotifier` owns no Android resources
 * and stays JVM-testable).
 */
enum class AppNotificationKind { AGENT, PERMISSION, QUESTION, ERROR }

/**
 * One entry of the in-app notification feed: data only, so the presentation
 * (top banner in `ui/App.kt` or an inline row in `SessionScreen`) is the UI
 * layer's decision.
 *
 * Mirrors the fields upstream keeps per notification
 * (`notification.tsx:23-35`): which [kind], which [sessionID] it belongs to,
 * when it happened and whether the user had already seen it.
 */
data class AppNotification(
    /** Stable identity for list keys / dismissal: `"<kind>:<session>:<time>"`. */
    val id: String,
    val kind: AppNotificationKind,
    /** Owning session; `null` when the event carried no session id. */
    val sessionID: String?,
    /** Session title known at raise time; `null` when it was unknown (foreign session). */
    val sessionTitle: String?,
    /**
     * Free-form detail the UI may show as the second line: the readable error
     * line ([AppNotificationKind.ERROR]), the required permission, or the first
     * question header. `null` when there is nothing worth showing.
     */
    val detail: String?,
    /** When it was raised (epoch millis; the list renders relative times). */
    val time: Long,
    /**
     * `true` when the notification's session was already the tracked one at
     * raise time — upstream's `viewed`
     * (`notification.tsx:221-223`, `viewedInCurrentSession`). A banner should
     * suppress those; the feed keeps them.
     */
    val viewed: Boolean,
)

/**
 * In-app notification store for W5-E5: a bounded, de-duplicated feed of
 * [AppNotification]s, exposed as a [StateFlow] for Compose.
 *
 * Contract (deliberately data-only — no `NotificationManager`, no sound, no
 * Android API, so it is unit-testable on the JVM):
 * - **Gated**: [raise] returns `null` while the owning setting is off, so the
 *   caller never has to filter (`SettingsStore.notifyAgent` /
 *   `notifyPermissions` / `notifyErrors` — the three keys that had no consumer
 *   before this wave).
 * - **De-bounced**: the same [AppNotificationKind] for the same session within
 *   [dedupeWindowMs] raises at most one entry (upstream de-duplicates per event
 *   id via `coordinator.ts:46-54`; a session+kind key is the client-side
 *   equivalent without server event ids).
 * - **Bounded**: only the newest [MAX_ITEMS] entries are kept, so a long-lived
 *   stream cannot grow the list forever.
 */
class AppNotifier(
    /** Setting gate per kind ([AppNotificationKind] → the three notify switches). */
    private val enabled: (AppNotificationKind) -> Boolean = { true },
    /** Injectable clock so the de-bounce window is testable without sleeping. */
    private val now: () -> Long = { System.currentTimeMillis() },
    private val dedupeWindowMs: Long = DEDUPE_WINDOW_MS,
    maxItems: Int = MAX_ITEMS,
) {
    private val capacity = maxItems.coerceAtLeast(1)

    private val _notifications = MutableStateFlow<List<AppNotification>>(emptyList())

    /** The feed, oldest first. */
    val notifications: StateFlow<List<AppNotification>> = _notifications.asStateFlow()

    /** Raise time per `"kind:session"` key — the de-bounce state. */
    private val lastRaised = HashMap<String, Long>()

    /**
     * Offers one notification. Returns the appended entry, or `null` when the
     * kind is disabled or this is a duplicate inside the de-bounce window — in
     * both cases nothing is emitted, so a caller can use the return value to
     * decide whether anything visible happened.
     */
    fun raise(
        kind: AppNotificationKind,
        sessionID: String? = null,
        sessionTitle: String? = null,
        detail: String? = null,
        viewed: Boolean = false,
    ): AppNotification? {
        if (!enabled(kind)) return null
        val time = now()
        val key = key(kind, sessionID)
        val previous = lastRaised[key]
        if (previous != null && time - previous < dedupeWindowMs) return null
        lastRaised[key] = time
        val notification = AppNotification(
            id = "$key:$time",
            kind = kind,
            sessionID = sessionID,
            sessionTitle = sessionTitle?.takeIf { it.isNotBlank() },
            detail = detail?.takeIf { it.isNotBlank() },
            time = time,
            viewed = viewed,
        )
        _notifications.value = (_notifications.value + notification).takeLast(capacity)
        return notification
    }

    /**
     * Marks every entry of [sessionID] as viewed (the user opened that session),
     * so a banner stops re-offering it. `true` when something changed.
     */
    fun markViewed(sessionID: String): Boolean {
        val current = _notifications.value
        if (current.none { it.sessionID == sessionID && !it.viewed }) return false
        _notifications.value = current.map {
            if (it.sessionID == sessionID) it.copy(viewed = true) else it
        }
        return true
    }

    /** Drops one entry by [AppNotification.id]; `true` when it was present. */
    fun dismiss(id: String): Boolean {
        val current = _notifications.value
        val next = current.filterNot { it.id == id }
        if (next.size == current.size) return false
        _notifications.value = next
        return true
    }

    /** Drops the whole feed. */
    fun clear() {
        if (_notifications.value.isNotEmpty()) _notifications.value = emptyList()
    }

    /** Entries still visible (test/introspection helper). */
    val size: Int get() = _notifications.value.size

    private fun key(kind: AppNotificationKind, sessionID: String?): String =
        "${kind.name}:${sessionID.orEmpty()}"

    companion object {
        /** Same session + kind within this window is one notification. */
        const val DEDUPE_WINDOW_MS = 5_000L

        /** Newest-first feed cap; older entries fall off silently. */
        const val MAX_ITEMS = 50
    }
}
