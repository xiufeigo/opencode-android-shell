package com.xiufeigo.opencodeshell.notify

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

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
 * Android *behaviour*, so it is unit-testable on the JVM; the Android halves
 * arrive through [NotificationPersistence] / [NotificationDelivery], both
 * nullable, and the only Android reference left is the `Log` call under [TAG]
 * that makes the two gates below visible on a device):
 * - **Gated**: [raise] returns `null` while the owning setting is off, so the
 *   caller never has to filter (`SettingsStore.notifyAgent` /
 *   `notifyPermissions` / `notifyErrors` — the three keys that had no consumer
 *   before this wave).
 * - **De-bounced**: the same [AppNotificationKind] for the same session within
 *   [dedupeWindowMs] raises at most one entry (upstream de-duplicates per event
 *   id via `coordinator.ts:46-54`; a session+kind key is the client-side
 *   equivalent without server event ids).
 * - **Bounded** (B-3): only the newest [MAX_ITEMS] entries inside the last
 *   [TTL_MS] are kept, exactly like upstream's `pruneNotifications`
 *   (`notification.tsx:64-69`, `MAX_NOTIFICATIONS = 500` / 30 days).
 * - **Persisted** (B-3): with a [persistence] the feed — `viewed` flags
 *   included, so an unseen turn survives a restart — is read at construction and
 *   written on every change. Without one it stays in memory (previews/tests).
 * - **Delivered** (B-2): with a [delivery] every *accepted* raise is handed to
 *   the system-notification half; the focus/sound rules live there
 *   ([SystemNotificationPolicy]), so this class stays presentation-free.
 */
class AppNotifier(
    /** Setting gate per kind ([AppNotificationKind] → the three notify switches). */
    private val enabled: (AppNotificationKind) -> Boolean = { true },
    /** Injectable clock so the de-bounce window is testable without sleeping. */
    private val now: () -> Long = { System.currentTimeMillis() },
    private val dedupeWindowMs: Long = DEDUPE_WINDOW_MS,
    maxItems: Int = MAX_ITEMS,
    /** B-3: where the feed survives a restart; `null` keeps it in memory only. */
    private val persistence: NotificationPersistence? = null,
    /** B-2: the Android system-notification half; `null` = in-app feed only. */
    private val delivery: NotificationDelivery? = null,
) {
    private val capacity = maxItems.coerceAtLeast(1)

    private val _notifications: MutableStateFlow<List<AppNotification>>

    /** The feed, oldest first. */
    val notifications: StateFlow<List<AppNotification>>

    /** Raise time per `"kind:session"` key — the de-bounce state. Written from
     * both Main (frame paths) and Dispatchers.Default (the idle watchdog), hence
     * not a plain HashMap. */
    private val lastRaised = ConcurrentHashMap<String, Long>()

    init {
        // Upstream prunes once the persisted store is ready and writes the result
        // back (`notification.tsx:188-197`); doing it here means the file never
        // carries an entry the UI would refuse to show. Only a prune that really
        // dropped something rewrites the blob.
        val loaded = persistence?.load().orEmpty()
        val restored = prune(loaded)
        _notifications = MutableStateFlow(restored)
        notifications = _notifications.asStateFlow()
        if (persistence != null && restored.size != loaded.size) persistence.save(restored)
    }

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
        if (!enabled(kind)) {
            Log.d(TAG, "gate: kind=$kind disabled by the settings switch (session=$sessionID)")
            return null
        }
        val time = now()
        val key = key(kind, sessionID)
        val previous = lastRaised[key]
        if (previous != null && time - previous < dedupeWindowMs) {
            Log.d(TAG, "gate: $key de-bounced (${time - previous}ms < ${dedupeWindowMs}ms)")
            return null
        }
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
        persist(prune(_notifications.value + notification))
        Log.d(TAG, "accepted: $key viewed=$viewed delivery=${delivery != null}")
        delivery?.deliver(notification)
        return notification
    }

    /**
     * Marks every entry of [sessionID] as viewed (the user opened that session),
     * so a banner stops re-offering it. `true` when something changed.
     */
    fun markViewed(sessionID: String): Boolean = markViewed(sessionID, kind = null)

    /**
     * Marks every entry of [sessionID] **of one [kind]** as viewed. The
     * auto-approve path uses it (`ChatViewModel.autoApprove`): the ask was
     * answered without the user, so the entry it raised must stop counting as
     * unseen (`SessionTabIndicator.unseenCount`, the tab/drawer dot).
     * `true` when something changed.
     */
    fun markKindViewed(sessionID: String, kind: AppNotificationKind): Boolean =
        markViewed(sessionID, kind)

    private fun markViewed(sessionID: String, kind: AppNotificationKind?): Boolean {
        val current = _notifications.value
        val matches: (AppNotification) -> Boolean =
            { it.sessionID == sessionID && (kind == null || it.kind == kind) }
        if (current.none { matches(it) && !it.viewed }) return false
        persist(current.map { if (matches(it)) it.copy(viewed = true) else it })
        return true
    }

    /** Drops one entry by [AppNotification.id]; `true` when it was present. */
    fun dismiss(id: String): Boolean {
        val current = _notifications.value
        val next = current.filterNot { it.id == id }
        if (next.size == current.size) return false
        persist(next)
        return true
    }

    /**
     * The banner's 「知道了」: drops the entry [dismiss] would drop **and**
     * marks its session's remaining entries viewed, so the banner's
     * `lastOrNull { !viewed }` selection moves past a session the user just
     * acknowledged. A bare `dismiss` only pops the shown entry: when the feed
     * holds several unviewed entries for the same session (one per settled
     * turn over a long run), the next identical banner renders in the same
     * frame and the tap looks dead (v47 tE: the same `Response ready` banner
     * survived every Dismiss). Entries of other sessions are untouched — their
     * news is still unseen. `false` when no entry carries [id].
     */
    fun acknowledge(id: String): Boolean {
        val current = _notifications.value
        val entry = current.firstOrNull { it.id == id } ?: return false
        val session = entry.sessionID
        persist(
            current.filterNot { it.id == id }
                .map { if (session != null && it.sessionID == session) it.copy(viewed = true) else it },
        )
        return true
    }

    /** Drops the whole feed. */
    fun clear() {
        if (_notifications.value.isNotEmpty()) persist(emptyList())
    }

    /** Entries still visible (test/introspection helper). */
    val size: Int get() = _notifications.value.size

    /**
     * Publishes the new feed and mirrors it to disk. The single write path, so no
     * mutation can leave the persisted blob behind the in-memory list.
     */
    private fun persist(next: List<AppNotification>) {
        _notifications.value = next
        persistence?.save(next)
    }

    /**
     * Upstream `pruneNotifications` (`notification.tsx:64-69`): drop everything
     * older than [TTL_MS], then keep the newest [capacity]. The clock is the
     * injected one, so a test can age an entry out without waiting 30 days.
     */
    private fun prune(list: List<AppNotification>): List<AppNotification> {
        val cutoff = now() - TTL_MS
        val fresh = list.filter { it.time >= cutoff }
        return if (fresh.size <= capacity) fresh else fresh.takeLast(capacity)
    }

    private fun key(kind: AppNotificationKind, sessionID: String?): String =
        "${kind.name}:${sessionID.orEmpty()}"

    companion object {
        /** Same session + kind within this window is one notification. */
        const val DEDUPE_WINDOW_MS = 5_000L

        /**
         * The chain-wide log tag (`SystemNotifier.TAG`): this class's gates, the
         * ViewModel's trigger and the Android half's delivery gates all log under
         * it, so `logcat -s OcNotify` shows the whole chain in order.
         *
         * `android.util.Log` is the one Android API in this class — it is a
         * no-op stub under `unitTests.isReturnDefaultValues`, so the JVM contract
         * below is unaffected.
         */
        const val TAG = "OcNotify"

        /**
         * B-3: the feed cap — upstream `MAX_NOTIFICATIONS`
         * (`notification.tsx:61`). Older entries fall off silently.
         */
        const val MAX_ITEMS = 500

        /**
         * B-3: how long an entry is worth keeping — upstream
         * `NOTIFICATION_TTL_MS` (`notification.tsx:62`, 30 days). A turn that
         * finished a month ago is not "unseen", it is history.
         */
        const val TTL_MS = 1000L * 60 * 60 * 24 * 30
    }
}
