package com.xiufeigo.opencodeshell.notify

/**
 * Which sessions belong to **this device**, for the notification chain.
 *
 * The server is shared: four emulators in the v49 test ran four shells against
 * one backend, and every one of them sees every session's `session.idle` on the
 * SSE stream. Upstream has a per-client key (`notification.tsx:225`'s
 * `input.key`, `sessionIDHasOpenTab(tabs.store, input.key, …)`), which is what
 * makes its turn-complete notification fire for *its own* tabs and nobody
 * else's; there is no such key on the Android frame path — the raise only ever
 * carried the session id. So a session started on another device finished, and
 * this device put a 「回复已完成」 banner over whatever the user was doing
 * (v49 tC: four banners, all of them for the 5554 tester's sessions).
 *
 * The rule is deliberately client-side and deliberately *narrow*: a session is
 * this device's once this device has **had it open** ([learn] from
 * `ChatViewModel.observeSession`, which covers both a session the user tapped
 * into and one this app created and promoted). Deliberately not the fetched
 * session list: with a shared backend that list contains every other device's
 * sessions too, so seeding from it would reproduce the exact bug it is meant to
 * fix.
 *
 * The set is in-memory only (a restart re-learns from the route the app comes
 * up on), and bounded: ids are kept in insertion order and the oldest fall off
 * past [maxIds], so a long-lived process cannot grow it without limit.
 *
 * Pure Kotlin — no Android type — so the rule is pinned by a JVM test
 * (`SessionOwnershipTest`), like the rest of `notify/`.
 */
class SessionOwnership(
    private val maxIds: Int = MAX_IDS,
) {
    /** Learned ids, oldest first. Guarded because raises arrive on the SSE thread. */
    private val ids = ArrayDeque<String>()

    /**
     * Records that this device has [sessionID] open (or just created it).
     * `true` when the set changed — a repeat learn is a no-op, so the common
     * "same session, every turn" path allocates nothing.
     */
    @Synchronized
    fun learn(sessionID: String?): Boolean {
        val id = sessionID?.takeIf { it.isNotBlank() } ?: return false
        if (id in ids) return false
        ids.addLast(id)
        while (ids.size > maxIds) ids.removeFirst()
        return true
    }

    /**
     * Whether a notification about [sessionID] is this device's business.
     * A `null` id (a frame whose payload carried no session) is kept: it cannot
     * be attributed to anybody, and dropping it would silently lose the error
     * and ask notifications the user does own.
     */
    @Synchronized
    fun owns(sessionID: String?): Boolean {
        val id = sessionID?.takeIf { it.isNotBlank() } ?: return true
        return id in ids
    }

    /** The learned ids (test/introspection helper). */
    @Synchronized
    fun snapshot(): Set<String> = ids.toSet()

    companion object {
        /**
         * Cap on remembered sessions — far above any plausible open-tab count,
         * and low enough that a process left running for weeks cannot grow the
         * set without bound.
         */
        const val MAX_IDS = 256
    }
}
