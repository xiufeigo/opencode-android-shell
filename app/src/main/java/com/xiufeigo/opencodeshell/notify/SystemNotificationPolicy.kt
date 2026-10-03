package com.xiufeigo.opencodeshell.notify

/**
 * B-2/B-4: the decisions a system notification needs, as pure functions — no
 * `Context`, no `NotificationManager`, so every rule below is pinned by a JVM
 * test instead of a device screenshot.
 *
 * Upstream anchor: `_upstream/packages/app/src/shell/notifications/`
 * - `coordinator.ts:60-69` — `system()` returns early when `document.hasFocus()`,
 *   so a focused window never gets a system notification;
 * - `web.ts:21-36` — `notify()` also returns while the page is `visible` **and**
 *   focused, then requests permission and posts on click → `onClick()`.
 *
 * The Android mapping of "the window is focused" is "the app has a **resumed**
 * activity" (`ForegroundTracker`) **and** "the notification's session is the one
 * on screen" ([shouldDeliver]): a backgrounded app must be told, and so must a
 * foreground app that is looking at a different session — that is the one case
 * upstream cannot have, because its window either has focus for the whole app
 * or for nothing.
 */
object SystemNotificationPolicy {

    /** `"default"` — `SettingsStore.SOUNDS`, the second stop of the sound selects. */
    const val SOUND_DEFAULT = "default"

    /** `"none"` — the stop that must stay silent (upstream `sound.option.none`). */
    const val SOUND_NONE = "none"

    /** `POST_NOTIFICATIONS` exists from here on (`SettingsStore` uses the same literal). */
    const val PERMISSION_SDK = 33

    /**
     * The session the tapped notification should open, read by the launcher
     * activity (`OcActivity`) and forwarded to [NotificationNavigation].
     */
    const val EXTRA_SESSION_ID = "opencode.android.notification.sessionId"

    /** [notificationId]'s slot for a notification that carries no session id. */
    const val NO_SESSION = "app"

    /**
     * How long a raise that was skipped as "the user is looking at it" waits
     * before it is judged again ([shouldPostDeferred]) — the safety net for a
     * foreground signal that was still stale when the raise ran.
     *
     * Two seconds: long enough for the state to have settled after a HOME press
     * (`ForegroundTracker`'s class doc, the measured 1.1 s `onActivityStopped`
     * lag, and a recheck that fires while the user is still there would post a
     * notification they can already see), short enough that a user who really
     * left is told while the turn is still the newest thing that happened.
     */
    const val RECHECK_DELAY_MS = 2_000L

    /**
     * How long a raise the focus test withheld stays **withheld** — the window in
     * which the app's next trip to the background still speaks for it
     * ([shouldPostOnBackground]).
     *
     * v63 (research/v63_verify/5558/50_home_bg.png … `57_final.png`): a question
     * was raised while the tester was looking at that very session, so
     * [shouldDeliver] skipped it and armed the [RECHECK_DELAY_MS] re-check. The
     * tester pressed HOME a few seconds later — after the re-check had run and
     * dropped it, because at that moment they really were still in the app. They
     * then sat on the launcher for 64 s, and again for 150 s, with
     * `dumpsys notification` counting 0: the one moment the notification was
     * actually wanted (the app leaving without the user having been told) is
     * exactly the moment the single re-check had already given up on.
     *
     * Two minutes: long enough to cover the re-check and the glance at the
     * transcript a user takes before switching away, short enough that
     * backgrounding the app an hour later does not resurrect a banner about a
     * reply that finished long ago.
     */
    const val WITHHELD_TTL_MS = 120_000L

    /**
     * Whether a raise [shouldDeliver] withheld is posted by the
     * foreground → background transition, which is the honest answer to
     * 「did this turn happen while I was looking?」 — the app's leaving is the
     * user's own signal that they are no longer watching.
     *
     * The re-check ([shouldPostDeferred]) can only ask once, two seconds after
     * the raise; a user who presses HOME later than that had the raise
     * withheld and the news with it, and nothing was left to re-arm (the v63
     * measurement above). The same second opinion as the re-check applies — the
     * entry must still be the newest for its slot ([postedAt] older than it)
     * and still recent ([notification.time] inside [WITHHELD_TTL_MS]) — and the
     * caller is the last resumed activity's pause, i.e. an app that really is
     * leaving rather than one that never left.
     *
     * The trade this makes explicitly: upstream reads `document.hasFocus()` at
     * the raise and never reconsiders, so a user who was looking at the session
     * gets nothing — which is what 「通知回前台投递」 deliberately gives up. A
     * user who saw the in-app banner and then switched away within
     * [WITHHELD_TTL_MS] is told as well; the in-app banner is transient (8 s,
     * [BannerPolicy.AUTO_DISMISS_MS]) and a turn's completion is what the
     * notification is *for*.
     */
    fun shouldPostOnBackground(
        notification: AppNotification,
        postedAt: Long?,
        now: Long,
    ): Boolean =
        (postedAt == null || postedAt < notification.time) &&
            now - notification.time <= WITHHELD_TTL_MS

    /**
     * Whether this notification should leave the app.
     *
     * [AppNotification.viewed] is upstream's `viewedInCurrentSession`
     * (`notification.tsx:221-223`) — `true` exactly when the user was already
     * looking at that session when it was raised — so "the session is on screen"
     * is `viewed` **and** a real session id: a session-less notification has
     * nothing on screen that could stand for it.
     */
    fun shouldDeliver(notification: AppNotification, appInForeground: Boolean): Boolean {
        if (!appInForeground) return true
        val sessionVisible = notification.sessionID != null && notification.viewed
        return !sessionVisible
    }

    /**
     * Whether a raise that [shouldDeliver] skipped — "the user is looking at
     * that session" — should still be posted by the deferred re-check, which
     * runs [RECHECK_DELAY_MS] after the raise.
     *
     * The skip is only as good as the foreground signal it was judged against,
     * and that signal is a race by construction: the raise runs on the
     * event-stream coroutine, the lifecycle callback on the main thread, and on
     * a real device the two interleave (`research/v31_verify/tA`: the raise was
     * decided 40 ms after HOME, against a state that flipped 1.1 s later). The
     * re-check closes that race from the other side: the user is asked again
     * once the state has settled, and is told only if they are still away.
     *
     * The re-check asks [shouldDeliver] **again** — the raise's own gate, not a
     * weaker one built out of `appInForeground` alone. A gate that forgets
     * [AppNotification.viewed] can overturn the raise's own conclusion: two
     * seconds after an entry that was raised as "the user is looking at that
     * session", any trip to the background would turn the skip into a post
     * (v50 tD). Same rule, second opinion: a raise the user has since *seen* is
     * not re-judged into a system notification just because the app left the
     * foreground.
     *
     * [postedAt] — when this slot ([notificationId]) was last posted, or `null`
     * if never. A newer notification for the same session replaces its older
     * one, so a stale entry whose slot has since been posted must not land on
     * top of it.
     */
    fun shouldPostDeferred(
        notification: AppNotification,
        appInForeground: Boolean,
        postedAt: Long?,
    ): Boolean =
        shouldDeliver(notification, appInForeground) &&
            (postedAt == null || postedAt < notification.time)

    /**
     * Whether the user **watched the turn that raised this notification** — the
     * second half of [AppNotification.viewed], and the reason the flag cannot be
     * the route test alone on Android.
     *
     * Upstream reads `document.hasFocus()` at the moment the event arrives, and
     * its window either has focus or does not — an event that happened while the
     * user was away is handled while the user is still away. An Android app has
     * no such guarantee: the process is frozen while it is cached and the SSE
     * socket replays on reconnect, so the frame that ends a turn is routinely
     * read **after** the user is back on that session. Judging the raise by the
     * foreground state *at read time* therefore suppresses exactly the
     * notification the feature exists for — the measured v30 failure
     * (`research/v30_verify/tA_notify_ws`: a 4 s turn completed in the
     * background, `cmd notification list` stayed empty, and the raise only ran
     * once the user was looking at the transcript again).
     *
     * [since] is the client-clock instant the turn was last seen alive
     * (`TurnRunStatus.since`); [foregroundSince] is when the app most recently
     * entered the foreground, `null` while it is backgrounded. The user watched
     * the turn iff the app has been in the foreground **continuously** since
     * then — a single trip to the background anywhere in that window means they
     * were not looking when it ended.
     *
     * `since == null` means "no local history to date this from" (the ask
     * notifications, which carry no turn, and a foreign session): the answer is
     * `true`, i.e. the raise keeps upstream's route-only `viewed`.
     */
    fun watchedThroughout(foregroundSince: Long?, since: Long?): Boolean =
        since == null || (foregroundSince != null && foregroundSince <= since)

    /**
     * B-4: `default` plays the platform's notification sound, `none` stays
     * silent. Any other value (a hand-edited preference, an older build's id)
     * reads as `none`, mirroring `SettingsStore.readEnum`'s whitelist.
     */
    fun shouldPlaySound(sound: String): Boolean = when (sound) {
        SOUND_DEFAULT -> true
        SOUND_NONE -> false
        else -> false
    }

    /**
     * The `NotificationManager` id **and** the `PendingIntent` request code: one
     * slot per session, so a session's newer notification replaces its older one
     * (the Android convention for a conversation) instead of stacking — and the
     * tap target keeps the extras of the newest raise.
     *
     * `String.hashCode` is specified by the JVM, so the id is stable across
     * restarts; the mask keeps it positive (`notify` accepts a negative id, but a
     * negative request code is a needless trap).
     */
    fun notificationId(notification: AppNotification): Int =
        (notification.sessionID?.takeIf { it.isNotBlank() } ?: NO_SESSION).hashCode() and 0x7fffffff

    /**
     * The notification's second line — upstream's `session.title ?? sessionID`
     * for a finished turn (`notification.tsx:243-248`) and
     * `session.title ?? error-description` for a failed one (`:270-277`).
     *
     * The session id is the fallback upstream uses, and the reason this is a
     * function: `SystemNotifier` used to render `sessionTitle ?: detail`, which
     * is **empty** for a turn whose title this client never learned (a foreign
     * session's row, or a session the server renamed after the raise) — a
     * notification with a title and no body. The error kind keeps the error text
     * ahead of the id, because that is what the user has to act on.
     */
    fun bodyOf(notification: AppNotification): String {
        notification.sessionTitle?.takeIf { it.isNotBlank() }?.let { return it }
        val detail = notification.detail?.takeIf { it.isNotBlank() }
        val session = notification.sessionID?.takeIf { it.isNotBlank() }
        return when (notification.kind) {
            AppNotificationKind.ERROR -> detail ?: session.orEmpty()
            else -> session ?: detail.orEmpty()
        }
    }

    /**
     * Whether the launcher should ask for `POST_NOTIFICATIONS`. Android only
     * allows the prompt from a foreground activity and never shows it twice, so
     * this is asked once per launch while the user has at least one notification
     * channel enabled ([enabled]) — a user who turned all three switches off is
     * never asked for a permission the app would not use.
     *
     * Pre-33 there is no runtime permission (and no such permission string), so
     * the answer is always `false` there.
     */
    fun shouldRequestPermission(sdkInt: Int, granted: Boolean, enabled: Boolean): Boolean =
        sdkInt >= PERMISSION_SDK && !granted && enabled

    /**
     * The three notify switches of `SettingsStore.Settings` reduced to "would the
     * app post anything at all" — the gate of [shouldRequestPermission].
     */
    fun anyChannelEnabled(notifyAgent: Boolean, notifyPermissions: Boolean, notifyErrors: Boolean): Boolean =
        notifyAgent || notifyPermissions || notifyErrors
}
