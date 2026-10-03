package com.xiufeigo.opencodeshell.notify

import android.app.Activity
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.xiufeigo.opencodeshell.OcActivity
import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.SettingsStore
import java.util.concurrent.ConcurrentHashMap

/**
 * B-2/B-4 on device: the Android system notification.
 *
 * Upstream posts through `platform.notify(title, description, onClick)`
 * (`runtime/platform/web.ts:21-36`), which returns early while the page is
 * visible and focused, and opens the session when the notification is clicked.
 * The three Android-only pieces it needs are all here:
 *
 * 1. a **channel** (API 26+ has no sound-less-by-default notification) created
 *    with `setSound(null, null)` because the sound is a *setting*
 *    (`soundAgent`/`soundPermissions`/`soundErrors`) the app applies itself —
 *    a channel's sound is immutable once created, so relying on it would make the
 *    three switches impossible to honour per kind;
 * 2. a **content intent** that re-enters [OcActivity] with
 *    [SystemNotificationPolicy.EXTRA_SESSION_ID], which the activity forwards to
 *    [NotificationNavigation] (`web.ts:31-35`'s `window.focus(); onClick()`);
 * 3. the **focus test** — [SystemNotificationPolicy.shouldDeliver] fed by
 *    [contentVisible], i.e. by [ForegroundTracker]'s set of **resumed**
 *    activities **that still hold the window focus** (`onWindowFocusChanged`,
 *    past the focus-loss grace). Upstream reads `document.hasFocus()`; Android
 *    has no such flag,
 *    and an [Application.ActivityLifecycleCallbacks] listener is the closest
 *    equivalent that does not need the activity to be recomposed to stay
 *    correct. Which of the callbacks may *drive* it is the whole point of
 *    [ForegroundTracker] (a started/stopped counter is ~1 s late on HOME, the
 *    measured v31 failure, and the resumed set alone stays lit under a
 *    covering window on a freeform desktop, the measured v68 5554 miss); this
 *    class is only the adapter over it. The state is
 *    **seeded from the activity's lifecycle** ([owner]) because the listener is
 *    registered from a composition, which on a cold start runs after
 *    `onActivityResumed`: without the seed the app would look backgrounded for
 *    the whole first launch and every raise would post.
 *
 * [watchedThrough] is the other half of that test: whether the app was in the
 * foreground *for the whole turn*, which is what the raise's `viewed` flag
 * needs (see [SystemNotificationPolicy.watchedThroughout] for why the current
 * foreground state is the wrong question on Android).
 *
 * A raise that [SystemNotificationPolicy.shouldDeliver] skips is not thrown
 * away: it is re-judged [SystemNotificationPolicy.RECHECK_DELAY_MS] later, when
 * the foreground state has settled, and posted if the user is still away
 * ([shouldPostDeferred][SystemNotificationPolicy.shouldPostDeferred]). The skip
 * is a race by construction — the raise runs on the event-stream coroutine, the
 * lifecycle callback on the main thread — and this is the second half of the
 * fix: the timely signal closes the common case, the re-check closes the rest
 * without ever double-posting (the slot's post history is the de-dupe).
 *
 * The re-check is a **single** timed ask, though, and a user who presses HOME
 * a moment after it ran had the raise dropped and the news with it — the v63
 * measurement (`research/v63_verify/5558`: 64 s, then 150 s on the launcher,
 * `dumpsys notification` counting 0). So a skipped raise is also *withheld*
 * ([withheld]) and posted by the app's own trip to the background
 * ([postWithheld]): the transition the user makes is the honest answer to
 * 「am I still looking at this?」, and it is the one moment a withheld raise
 * can still be spoken for.
 *
 * [deliver] is called from the event-stream coroutine and therefore never throws:
 * a device where notifications are disabled (or a `POST_NOTIFICATIONS` the user
 * denied) must not take the stream down with it.
 *
 * Every gate below logs under [TAG] (`OcNotify`), including the ones that
 * return early: a device where nothing appears has to be diagnosable from
 * `logcat -s OcNotify` alone, because the failure mode of this class is silence.
 */
class SystemNotifier(
    context: Context,
    /** Read per delivery: the three sound selects can change while the app runs. */
    private val settings: () -> SettingsStore.Settings,
    /**
     * The activity that owns the first composition — read once, to seed
     * [appInForeground] (see the class doc). It must be the **activity itself**
     * (`OcActivity`), because the seed is stored under its own identity and is
     * taken back by that activity's `onActivityPaused`/`onActivityStopped`; a
     * `LifecycleOwner` that is not an [Activity] (a test, a preview) seeds
     * nothing and leaves the callbacks to decide. `null` in a context with no
     * activity at all (a service).
     */
    owner: LifecycleOwner? = null,
) : NotificationDelivery {

    private val appContext = context.applicationContext

    /**
     * The resumed-activity state behind [appInForeground]. See [ForegroundTracker]
     * for why the signal is *resumed* and not *started* (the measured 1.1 s
     * `onActivityStopped` lag that dropped the v31 notification).
     */
    private val foreground = ForegroundTracker()

    /** Whether the app currently has a resumed activity. */
    val appInForeground: Boolean get() = foreground.inForeground

    /**
     * Whether the user can currently see the app's content — resumed **and**
     * holding the window focus past [ForegroundTracker.FOCUS_LOST_GRACE_MS].
     * This is what the delivery gate is fed: on a freeform desktop the activity
     * stays resumed while another window covers it (v68 5554), and judging the
     * raise by the resumed set alone skips the notification for a user who is
     * looking at something else.
     */
    val contentVisible: Boolean get() = foreground.contentVisible

    /** Skipped raises awaiting their re-check, by [SystemNotificationPolicy.notificationId]. */
    private val pending = ConcurrentHashMap<Int, AppNotification>()

    /** When each slot was last posted on the client clock — the re-check's de-dupe. */
    private val posted = ConcurrentHashMap<Int, Long>()

    /**
     * Raises [SystemNotificationPolicy.shouldDeliver] withheld, by slot — the
     * ones the app's next trip to the background speaks for.
     *
     * The re-check ([scheduleRecheck]) is a single timed second opinion, and it
     * runs [SystemNotificationPolicy.RECHECK_DELAY_MS] after the raise: a user
     * who presses HOME a moment later was still in the app at that instant, so
     * the raise was dropped and the news went with it. The v63 measurement
     * (`research/v63_verify/5558`: 64 s and then 150 s on the launcher with
     * `dumpsys notification` counting 0) is that drop. This map keeps the raise
     * until one of the three things that can speak for it happens instead: a
     * post ([post] clears it), a newer raise for the same slot (the slot is the
     * session's, so only the newest raise is worth posting), or
     * [SystemNotificationPolicy.WITHHELD_TTL_MS] elapsing.
     */
    private val withheld = ConcurrentHashMap<Int, AppNotification>()

    /** The posted re-check of each slot, so a newer post can cancel a stale one. */
    private val rechecks = ConcurrentHashMap<Int, Runnable>()

    /** Main-thread timer: the delivery path runs on the event-stream coroutine. */
    private val handler = Handler(Looper.getMainLooper())

    private val lifecycle = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) {
            foreground.resumed(activity)
            Log.d(
                TAG,
                "foreground: resumed=${activity.javaClass.simpleName} " +
                    "resumedCount=${foreground.resumedCount}",
            )
        }

        override fun onActivityPaused(activity: Activity) {
            foreground.paused(activity)
            Log.d(
                TAG,
                "foreground: paused=${activity.javaClass.simpleName} " +
                    "resumedCount=${foreground.resumedCount}",
            )
            // The last resumed activity left: the app is on its way out, and a
            // raise the focus test withheld can no longer be judged by a
            // foreground state that is disappearing (`postWithheld`).
            if (!foreground.inForeground) postWithheld()
        }

        /**
         * Logged, never a signal: `started` fires before the activity is
         * interactive, and counting it is the delayed-stop bug in reverse
         * ([ForegroundTracker]'s doc). Kept in the log because the tester's
         * timeline needs the whole callback order, not only the two that decide.
         */
        override fun onActivityStarted(activity: Activity) {
            Log.d(
                TAG,
                "foreground: started=${activity.javaClass.simpleName} " +
                    "resumedCount=${foreground.resumedCount}",
            )
        }

        /** The late correction: it can only take back a resume, never grant one. */
        override fun onActivityStopped(activity: Activity) {
            foreground.stopped(activity)
            Log.d(
                TAG,
                "foreground: stopped=${activity.javaClass.simpleName} " +
                    "resumedCount=${foreground.resumedCount}",
            )
        }

        // Every remaining callback is a required override: the interface's
        // default methods only exist from API 29, while `minSdk` is 26.
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    init {
        // The composition that creates this object runs *after* the first
        // `onActivityResumed` on a cold start (the `ComposeView` only attaches
        // once the decor view reaches the window manager, i.e. around
        // `onResume`), so the callback would never see that resume. Seeding the
        // owner *as the activity key* makes the state right in both orders:
        // registered before the resume -> the callback adds it; registered after
        // -> the seed already did. The key is the owner itself, so the activity's
        // own `paused`/`stopped` removes exactly this entry (the shell is a
        // single-activity app: `OcActivity` is the `singleTask` launcher).
        if (owner is Activity && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            foreground.resumed(owner)
        }
        (appContext as? Application)?.registerActivityLifecycleCallbacks(lifecycle)
        Log.d(TAG, "notifier ready: seeded=${foreground.inForeground}")
    }

    /** Unregisters the lifecycle callbacks (the activity's `onDispose`). */
    fun close() {
        (appContext as? Application)?.unregisterActivityLifecycleCallbacks(lifecycle)
        // A pending re-check belongs to a notifier that is gone: dropping the
        // messages is what keeps a recreated activity (a config change) from
        // being posted by the previous instance's timer.
        handler.removeCallbacksAndMessages(null)
        pending.clear()
        rechecks.clear()
        withheld.clear()
    }

    /**
     * The activity's `onWindowFocusChanged`, forwarded by `OcActivity`: another
     * window took the screen (or came back) while the activity stays resumed —
     * the freeform case the lifecycle callbacks cannot see. A loss arms the
     * focus-timeout carrier ([runFocusTimeout]); a gain disarms it.
     */
    fun onWindowFocusChanged(hasFocus: Boolean) {
        if (hasFocus) {
            foreground.focusGained()
            handler.removeCallbacks(focusTimeout)
            Log.d(TAG, "focus: gained resumedCount=${foreground.resumedCount}")
        } else {
            foreground.focusLost()
            handler.removeCallbacks(focusTimeout)
            handler.postDelayed(focusTimeout, ForegroundTracker.FOCUS_LOST_GRACE_MS)
            Log.d(TAG, "focus: lost resumedCount=${foreground.resumedCount}")
        }
    }

    /**
     * The focus half of the withheld carriers: a focus loss that outlasted the
     * grace ends the viewing period ([ForegroundTracker.focusTimedOut]) and the
     * app's covered state speaks for the raises the focus test withheld
     * ([postWithheld]) — the same job the last pause does for the lifecycle
     * half ([Application.ActivityLifecycleCallbacks.onActivityPaused]).
     */
    private val focusTimeout = Runnable {
        try {
            if (foreground.focusTimedOut()) {
                Log.d(TAG, "focus: timeout expired (the window stayed covered)")
                postWithheld()
            }
        } catch (error: RuntimeException) {
            Log.w(TAG, "focus timeout failed", error)
        }
    }

    /**
     * `true` when the app has been in the foreground continuously since [since]
     * — the user watched the turn that raise is about. `since == null` means the
     * raise carries no turn to date (an ask), and upstream's route-only answer
     * stands.
     */
    override fun watchedThrough(since: Long?): Boolean {
        val foregroundSince = foreground.foregroundSince
        val watched = SystemNotificationPolicy.watchedThroughout(foregroundSince, since)
        Log.d(TAG, "watchedThrough: since=$since foregroundSince=$foregroundSince -> $watched")
        return watched
    }

    /**
     * The raise's half of the delivery: post it now, or — when the user is
     * looking at the session — queue the deferred re-check ([scheduleRecheck])
     * that posts it once the foreground state has settled.
     */
    override fun deliver(notification: AppNotification) {
        try {
            if (SystemNotificationPolicy.shouldDeliver(notification, contentVisible)) {
                post(notification, via = "raise")
                return
            }
            Log.d(
                TAG,
                "skip: app is foreground on ${notification.sessionID} " +
                    "(viewed=${notification.viewed}) kind=${notification.kind} " +
                    "resumedCount=${foreground.resumedCount} windowFocused=${foreground.windowFocused}",
            )
            // Kept for the background transition, not just the re-check: the
            // re-check can only ask once, and the moment the user actually
            // leaves may well be after it (v63, `research/v63_verify/5558`).
            withheld[SystemNotificationPolicy.notificationId(notification)] = notification
            scheduleRecheck(notification)
        } catch (error: RuntimeException) {
            // A missing/revoked permission or a vendor ROM refusing the post must
            // not propagate into the SSE loop (`NotificationDelivery`'s contract).
            // Logged, never silent: this catch is the one place a posted
            // notification can vanish without a `NotificationRecord`.
            Log.w(
                TAG,
                "deliver failed: kind=${notification.kind} session=${notification.sessionID} " +
                    "id=${SystemNotificationPolicy.notificationId(notification)}",
                error,
            )
        }
    }

    /**
     * The post itself, for both carriers: the raise ([via] `raise`) and the
     * deferred re-check ([via] `recheck`). The two gates below are re-asked here
     * because the re-check runs two seconds later — the user may have left in
     * between, and the package may have lost its notifications.
     *
     * The slot bookkeeping is what makes the second carrier safe: a post is
     * recorded in [posted] (the re-check's de-dupe) and cancels any pending
     * re-check for the same slot, so a newer notification for a session is never
     * overwritten by a stale one that was waiting on a timer.
     */
    private fun post(notification: AppNotification, via: String) {
        val manager = NotificationManagerCompat.from(appContext)
        // Also false when the user denied POST_NOTIFICATIONS on 33+: the post
        // would be dropped by the system, and `notify` would be a no-op we
        // would rather not pay for.
        if (!manager.areNotificationsEnabled()) {
            Log.d(TAG, "skip: notifications disabled for the package (kind=${notification.kind})")
            return
        }
        val id = SystemNotificationPolicy.notificationId(notification)
        // A newer post for the slot supersedes whatever the timer is holding.
        pending.remove(id)
        withheld.remove(id)
        rechecks.remove(id)?.let { handler.removeCallbacks(it) }
        ensureChannel()
        manager.notify(id, build(notification))
        // Recorded only once it is really out: the de-dupe must not claim a slot
        // the system refused to post.
        posted[id] = notification.time
        Log.d(
            TAG,
            "posted: id=$id kind=${notification.kind} session=${notification.sessionID} " +
                "title=${notification.sessionTitle} viewed=${notification.viewed} " +
                "sound=${soundSettingFor(notification.kind)} contentVisible=$contentVisible " +
                "via=$via",
        )
        if (SystemNotificationPolicy.shouldPlaySound(soundSettingFor(notification.kind))) {
            Log.d(TAG, "sound: playing the default notification ringtone")
            playDefaultSound()
        }
    }

    /**
     * Queues a raise that [SystemNotificationPolicy.shouldDeliver] skipped as
     * "the user is looking at that session" for a second look once the
     * foreground state has settled ([SystemNotificationPolicy.RECHECK_DELAY_MS]).
     *
     * One re-check per slot: a newer raise for the same session replaces the
     * pending one (and its timer), because only the newest raise is worth
     * posting — the slot is the session's, so the two would overwrite each other
     * anyway, in the wrong order.
     */
    private fun scheduleRecheck(notification: AppNotification) {
        val id = SystemNotificationPolicy.notificationId(notification)
        pending[id] = notification
        rechecks.remove(id)?.let { handler.removeCallbacks(it) }
        val runnable = Runnable { recheck(id) }
        rechecks[id] = runnable
        handler.postDelayed(runnable, SystemNotificationPolicy.RECHECK_DELAY_MS)
        Log.d(
            TAG,
            "recheck: scheduled id=$id kind=${notification.kind} " +
                "in ${SystemNotificationPolicy.RECHECK_DELAY_MS}ms",
        )
    }

    /**
     * The deferred decision, on the main thread: post only if the user is *still*
     * away — a user who came back (or never left) already has the in-app banner,
     * and posting would be the duplicate the skip exists to prevent
     * ([SystemNotificationPolicy.shouldPostDeferred]).
     *
     * Runs on the main thread, so it catches for the same reason [deliver] does:
     * a vendor ROM refusing the post must not take the app down.
     */
    private fun recheck(id: Int) {
        try {
            rechecks.remove(id)
            val notification = pending.remove(id) ?: return
            val postedAt = posted[id]
            if (!SystemNotificationPolicy.shouldPostDeferred(notification, contentVisible, postedAt)) {
                Log.d(
                    TAG,
                    "recheck: id=$id dropped (contentVisible=$contentVisible postedAt=$postedAt " +
                        "raised=${notification.time})",
                )
                return
            }
            Log.d(TAG, "recheck: id=$id posting (the user left before the raise was judged)")
            post(notification, via = "recheck")
        } catch (error: RuntimeException) {
            Log.w(TAG, "recheck failed: id=$id", error)
        }
    }

    /**
     * The third carrier, and the one that answers the question the re-check
     * cannot: the user has just **left** the app, so a raise the focus test
     * withheld is no longer something they are looking at. Posts every slot
     * still withheld ([SystemNotificationPolicy.shouldPostOnBackground]), which
     * is what the v63 5558 run was missing: the raise, the re-check and the
     * HOME press were all in the wrong order, and the news was lost with them.
     *
     * One slot per session, so this is bounded by the number of sessions with
     * an unacknowledged raise, not by the length of the run. Runs on the main
     * thread (the lifecycle callback), so it catches for the same reason
     * [recheck] does.
     */
    private fun postWithheld() {
        // Both callers (the last pause, the focus timeout) mean "the user can
        // no longer see the content"; the guard keeps a caller that raced a
        // return to the foreground from posting into it.
        if (contentVisible) return
        val now = System.currentTimeMillis()
        for (id in withheld.keys.toList()) {
            try {
                val notification = withheld[id] ?: continue
                if (!SystemNotificationPolicy.shouldPostOnBackground(notification, posted[id], now)) {
                    withheld.remove(id)
                    continue
                }
                Log.d(TAG, "withheld: id=$id posting (the user left before the re-check)")
                post(notification, via = "background")
            } catch (error: RuntimeException) {
                Log.w(TAG, "withheld post failed: id=$id", error)
            }
        }
    }

    /**
     * The one channel every notification uses. Re-creating an existing channel is
     * a no-op (`createNotificationChannel` only inserts), so this is safe to call
     * per delivery — and it must be, because the process can be restarted between
     * two deliveries.
     */
    private fun ensureChannel() {
        val manager = appContext.getSystemService(NotificationManager::class.java) ?: run {
            Log.w(TAG, "channel: no NotificationManager service")
            return
        }
        val channel = NotificationChannel(
            CHANNEL_ID,
            appContext.getString(R.string.oc_setting_section_notifications),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            // B-4: silence the channel and play the default ringtone ourselves.
            setSound(null, null)
            enableVibration(true)
            setShowBadge(true)
        }
        manager.createNotificationChannel(channel)
    }

    private fun build(notification: AppNotification): Notification {
        // Upstream posts one description (`platform.notify(title, description)`):
        // the session title, else the session id / the error text — never an
        // empty second line ([SystemNotificationPolicy.bodyOf]).
        val body = SystemNotificationPolicy.bodyOf(notification)
        val builder = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(smallIconFor(notification.kind))
            .setContentTitle(appContext.getString(titleResFor(notification.kind)))
            // The same second line the in-app banner renders (`ui/App.kt`
            // `NotificationBanner`).
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setShowWhen(true)
            .setWhen(notification.time)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        contentIntent(notification)?.let { builder.setContentIntent(it) }
        return builder.build()
    }

    /**
     * Re-enters [OcActivity] with the session id. `NEW_TASK | SINGLE_TOP` is the
     * canonical notification-intent pair, and `OcActivity` is `singleTask` in the
     * manifest, so a running instance is reused — the tap lands on `onNewIntent`,
     * not a cold start. The request code is the per-session slot
     * ([SystemNotificationPolicy.notificationId]) so each session's intent keeps
     * its own extras.
     *
     * Deliberately **not** [com.xiufeigo.opencodeshell.MainActivity]: that is the
     * legacy WebView shell (`exported="false"`, no longer the launcher) and it has
     * no session route at all — a tap there could not open the session the
     * notification is about.
     */
    private fun contentIntent(notification: AppNotification): PendingIntent? {
        val sessionID = notification.sessionID?.takeIf { it.isNotBlank() } ?: return null
        val intent = Intent(appContext, OcActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra(SystemNotificationPolicy.EXTRA_SESSION_ID, sessionID)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        return PendingIntent.getActivity(
            appContext,
            SystemNotificationPolicy.notificationId(notification),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * B-4: `default` → the platform's notification sound, **zero assets** (the 45
     * bundled `SoundID`s of upstream `shell/notifications/sound.ts` are not
     * shipped — see the delivery note). `none` → nothing at all.
     *
     * `USAGE_NOTIFICATION` + `CONTENT_TYPE_SONIFICATION` is what makes the ringtone
     * obey the ringer mode / Do-Not-Disturb, i.e. exactly what a notification sound
     * would do if the channel played it.
     */
    private fun playDefaultSound() {
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION) ?: return
        val ringtone = RingtoneManager.getRingtone(appContext, uri) ?: return
        ringtone.audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        ringtone.play()
    }

    private fun soundSettingFor(kind: AppNotificationKind): String {
        val current = settings()
        return when (kind) {
            AppNotificationKind.AGENT -> current.soundAgent
            AppNotificationKind.PERMISSION, AppNotificationKind.QUESTION -> current.soundPermissions
            AppNotificationKind.ERROR -> current.soundErrors
        }
    }

    private fun titleResFor(kind: AppNotificationKind): Int = when (kind) {
        AppNotificationKind.AGENT -> R.string.oc_notify_title_agent
        AppNotificationKind.PERMISSION -> R.string.oc_notify_title_permission
        AppNotificationKind.QUESTION -> R.string.oc_notify_title_question
        AppNotificationKind.ERROR -> R.string.oc_notify_title_error
    }

    /** A monochrome silhouette is what the system draws; all three ship already. */
    private fun smallIconFor(kind: AppNotificationKind): Int = when (kind) {
        AppNotificationKind.AGENT -> R.drawable.ic_oc_task
        AppNotificationKind.PERMISSION, AppNotificationKind.QUESTION -> R.drawable.ic_oc_help
        AppNotificationKind.ERROR -> R.drawable.ic_oc_alert
    }

    companion object {
        /** Stable channel id; a reinstall keeps the user's channel settings. */
        const val CHANNEL_ID = "opencode.sessions"

        /**
         * One tag for the whole notification chain — the ViewModel's trigger and
         * gates, `AppNotifier`'s settings/de-bounce gates and this class's
         * delivery gates all log under it, so `logcat -s OcNotify` is the single
         * command that shows which link of the chain broke on a device.
         */
        const val TAG = "OcNotify"
    }
}
