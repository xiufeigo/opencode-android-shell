package com.xiufeigo.opencodeshell.notify

import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Which activities are **resumed** right now — the Android answer to upstream's
 * `document.hasFocus()`, and the signal [SystemNotificationPolicy.shouldDeliver]
 * and [watchedThroughout][SystemNotificationPolicy.watchedThroughout] are fed.
 *
 * The obvious implementation — a counter over `onActivityStarted` /
 * `onActivityStopped` — is a full second late on the one transition that
 * matters, and that lateness is a measured failure, not a theory
 * (`research/v31_verify/tA`, `logcat -s OcNotify`):
 *
 * ```
 * 01:19:49.037  trigger idle: … finished=true
 * 01:19:49.042  skip: app is foreground on ses_xxx (viewed=true) kind=AGENT
 * 01:19:50.174  foreground: stopped=OcActivity count=0
 * ```
 *
 * The user pressed HOME at 49.0, the SSE idle frame was read 40 ms later, and
 * the *stop* callback only arrived 1.1 s after that — the raise was judged
 * against a foreground that no longer existed, so the turn-complete
 * notification was silently dropped. `onActivityPaused` is not like that: the
 * framework pauses the activity as part of handling the HOME press itself, so
 * the state flips in the same frame the user leaves. Android only defers the
 * *stop* half (the activity stays paused-but-started while its process is still
 * worth keeping around).
 *
 * So the state is a set of resumed activities rather than a counter, and the
 * three callbacks have three different jobs:
 *
 * - [resumed] — the only **up** signal. An activity is interactive from here,
 *   which is what "the in-app banner already said it" means;
 * - [paused] — the immediate **down** signal (HOME, a task switch, another
 *   activity on top of this one);
 * - [stopped] — a one-way **correction**: it can only remove an activity whose
 *   pause callback never arrived. `onActivityStarted` is deliberately not an up
 *   signal (it fires before the activity is interactive, and counting it is the
 *   delayed-stop bug in reverse).
 *
 * The set is keyed by the activity instance, so the callbacks are
 * order-independent: a `stopped` for an activity that already paused is a
 * no-op, and a stale callback for a destroyed instance can never decrement
 * another activity's state — the flaw of a bare counter, where one late
 * callback shifts every later decision.
 *
 * [foregroundSince] is the other half of the class: the client-clock instant the
 * app most recently *became* interactive, `null` while it is not. It is what
 * dates the "did the user watch this turn?" test, so it must move on the same
 * timely signal — with a started/stopped counter it stayed at the pre-HOME
 * instant for the whole 1.1 s, which is what made the raise's `viewed` flag
 * `true` as well.
 *
 * A resumed activity is not yet "the user can see the content": on a freeform
 * desktop (emulator-5554 `Medium_Desktop`, `research/v68_verify/5554`) multi-
 * resume keeps the activity resumed while another window covers it —
 * `80_notif_wait.png` is the app behind the Settings window, still resumed,
 * its content invisible. The resumed set alone then reads foreground for a
 * user who is looking at something else, and a turn that finishes there is
 * skipped as "viewed" and never posted (144 s + 140 s, `dumpsys` 0). So the
 * delivery gate reads [contentVisible]: resumed **and** holding the window
 * focus, where a focus loss only counts once it outlasts [FOCUS_LOST_GRACE_MS]
 * — the Android half of upstream's `document.hasFocus()`, which is what the
 * focus test always meant to be.
 *
 * Pure Kotlin — no Android type anywhere — so the ordering above is pinned by a
 * JVM test instead of a device run (`ForegroundTrackerTest`); [SystemNotifier]
 * is the thin `Application.ActivityLifecycleCallbacks` adapter over it, plus
 * the `onWindowFocusChanged` forward from the activity.
 */
class ForegroundTracker(
    /** Injectable clock: [foregroundSince] is a client-clock stamp. */
    private val now: () -> Long = { System.currentTimeMillis() },
) {

    /**
     * The resumed activities, by identity (`Activity` does not override
     * `equals`). A concurrent set because the callbacks run on the main thread
     * while [inForeground] is read from the event-stream coroutine.
     */
    private val resumedActivities: MutableSet<Any> =
        Collections.newSetFromMap(ConcurrentHashMap<Any, Boolean>())

    /** When the app most recently became interactive; `null` while backgrounded. */
    @Volatile
    var foregroundSince: Long? = null
        private set

    /** Whether any activity is resumed — "the app is on screen" (`shouldDeliver`). */
    val inForeground: Boolean get() = resumedActivities.isNotEmpty()

    /**
     * Whether the user can currently see the app's content: resumed **and**
     * holding the window focus, where a focus loss younger than
     * [FOCUS_LOST_GRACE_MS] still counts as visible. This is what the delivery
     * gate (`shouldDeliver`) is fed; [inForeground] stays the raw resumed
     * answer for the lifecycle logs.
     *
     * The grace rides out transient blips (a shade peek, a split-screen glance
     * that taps back) without posting; anything longer means the content is
     * covered or another window owns the screen, and a raise there must tell
     * the user. A raise skipped inside the grace is still carried by the
     * re-check and the withheld slot, so the grace never drops news.
     */
    val contentVisible: Boolean
        get() = inForeground &&
            (windowFocused || (focusLostAt?.let { now() - it < FOCUS_LOST_GRACE_MS } == true))

    /** Window focus, as last reported by `onWindowFocusChanged`; `true` until told otherwise. */
    @Volatile
    var windowFocused: Boolean = true
        private set

    /**
     * When the window last lost focus while focused, on the client clock; `null`
     * while focused or once the loss has timed out ([focusTimedOut]). Stamped
     * only on the transition, so a flapping callback cannot stretch the grace.
     */
    @Volatile
    var focusLostAt: Long? = null
        private set

    /** Resumed activities, for the lifecycle logs (`> 0` = on screen). */
    val resumedCount: Int get() = resumedActivities.size

    /**
     * `onActivityResumed`, and the seed of a composition that runs after it
     * (`SystemNotifier`'s `owner`): the app is interactive from here on. The
     * timestamp only moves on the transition, so a second activity resuming on
     * top of the first cannot re-date a foreground period that never ended.
     *
     * No stamp while the window is unfocused (a freeform window opened behind
     * another one): nobody is watching yet, and stamping here would date the
     * turn as watched. The stamp lands on [focusGained] instead.
     */
    fun resumed(key: Any) {
        val wasForeground = inForeground
        resumedActivities.add(key)
        if (!wasForeground && windowFocused) foregroundSince = now()
    }

    /**
     * `onActivityPaused`: the immediate down signal. Returns whether this
     * callback actually changed the state, i.e. whether [key] was resumed —
     * `false` is the normal answer for a `paused` that follows an earlier one.
     */
    fun paused(key: Any): Boolean {
        val wasResumed = resumedActivities.remove(key)
        if (!inForeground) foregroundSince = null
        return wasResumed
    }

    /**
     * `onActivityStopped`: the same removal as [paused], kept separate because
     * it is the *late* callback — a stop for an activity that already paused is
     * a no-op, and a stop is the only correction available for a pause that
     * never arrived (a process started while the activity was already stopping).
     */
    fun stopped(key: Any): Boolean = paused(key)

    /**
     * `onWindowFocusChanged(true)`: the window owns the screen again. A viewing
     * period that the focus timeout had ended ([focusTimedOut]) starts over —
     * the mirror of [resumed] for the focus half of [contentVisible].
     */
    fun focusGained() {
        if (windowFocused) return
        windowFocused = true
        focusLostAt = null
        if (inForeground && foregroundSince == null) foregroundSince = now()
    }

    /**
     * `onWindowFocusChanged(false)`: another window took the screen (or the
     * shade came down) while the activity stays resumed. Stamps the loss; the
     * content counts as left only once the loss outlasts [FOCUS_LOST_GRACE_MS]
     * ([contentVisible], [focusTimedOut]).
     */
    fun focusLost() {
        if (!windowFocused) return
        windowFocused = false
        focusLostAt = now()
    }

    /**
     * Applies a focus loss that outlasted [FOCUS_LOST_GRACE_MS]: the viewing
     * period ends — the mirror of [paused] for the focus half — so a turn that
     * finishes from here on is dated as unwatched, and the caller can speak for
     * the raises the focus test withheld. `true` on the one transition; a loss
     * that is still inside the grace, an already-applied one, a regained focus
     * or a paused app all answer `false`.
     */
    fun focusTimedOut(): Boolean {
        if (windowFocused || !inForeground) return false
        val lostAt = focusLostAt ?: return false
        if (now() - lostAt < FOCUS_LOST_GRACE_MS) return false
        focusLostAt = null
        foregroundSince = null
        return true
    }

    companion object {
        /**
         * How long a focus loss is still "the user is looking" ([contentVisible]).
         * Five seconds rides out a shade peek or a split-screen glance that taps
         * back, while a window that stays covered (v68 5554: the Settings window
         * over the app for minutes) reads away long before the turn finishes.
         */
        const val FOCUS_LOST_GRACE_MS = 5_000L
    }
}
