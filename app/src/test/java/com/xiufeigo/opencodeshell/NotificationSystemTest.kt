package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.notify.AppNotification
import com.xiufeigo.opencodeshell.notify.AppNotificationKind
import com.xiufeigo.opencodeshell.notify.NotificationCodec
import com.xiufeigo.opencodeshell.notify.NotificationNavigation
import com.xiufeigo.opencodeshell.notify.SystemNotificationPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * B-2/B-4, the parts that are decidable without a device: when a system
 * notification is posted (`SystemNotificationPolicy.shouldDeliver`), which
 * notification slot it takes, whether the sound plays, the persisted wire format
 * (`NotificationCodec`) and the tap → session channel
 * (`NotificationNavigation`).
 *
 * The Android glue itself (`SystemNotifier`'s channel/`NotificationManager`/
 * `PendingIntent`) is pinned by source assertions at the bottom — the same
 * technique `Wave5IntegrationWiringTest` uses for the rest of the shell, and the
 * only option here because an instrumentation test would need a device.
 */
class NotificationSystemTest {

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("missing $relative")

    private fun notification(
        kind: AppNotificationKind = AppNotificationKind.AGENT,
        sessionID: String? = "ses_1",
        viewed: Boolean = false,
        time: Long = 1_000L,
        title: String? = null,
        detail: String? = null,
    ) = AppNotification(
        id = "${kind.name}:${sessionID.orEmpty()}:$time",
        kind = kind,
        sessionID = sessionID,
        sessionTitle = title,
        detail = detail,
        time = time,
        viewed = viewed,
    )

    // ------------------------------------------------------- delivery decision

    @Test
    fun aBackgroundedAppIsAlwaysTold() {
        for (viewed in listOf(false, true)) {
            assertTrue(
                "backgrounded + viewed=$viewed",
                SystemNotificationPolicy.shouldDeliver(notification(viewed = viewed), appInForeground = false),
            )
        }
        assertTrue(
            "a session-less notification still reaches a backgrounded app",
            SystemNotificationPolicy.shouldDeliver(
                notification(sessionID = null),
                appInForeground = false,
            ),
        )
    }

    @Test
    fun theForegroundIsOnlyDisturbedAboutAnotherSession() {
        // The user is looking at exactly this session: the in-app banner already
        // said it, so the system notification is suppressed (upstream's
        // `document.hasFocus()` early-out, `coordinator.ts:62`).
        assertFalse(
            SystemNotificationPolicy.shouldDeliver(
                notification(sessionID = "ses_1", viewed = true),
                appInForeground = true,
            ),
        )
        // Foreground, but on a different page/session: still worth a system
        // notification, which is the case upstream cannot have (one window, one
        // focus flag).
        assertTrue(
            SystemNotificationPolicy.shouldDeliver(
                notification(sessionID = "ses_1", viewed = false),
                appInForeground = true,
            ),
        )
        // No session to be looking at: deliver (nothing on screen stands for it).
        assertTrue(
            SystemNotificationPolicy.shouldDeliver(notification(sessionID = null), appInForeground = true),
        )
    }

    // ------------------------------------------ was the turn actually watched?

    /**
     * The deferred re-check's decision (`shouldPostDeferred`): a raise that the
     * focus test skipped is judged again — by the **same** gate — once the
     * foreground state has settled, and posted only if the user is still away —
     * never on top of a newer notification for the same slot.
     */
    @Test
    fun aSkippedRaiseIsRepostedOnlyWhileTheUserIsStillAwayAndOnlyOnce() {
        assertTrue(
            "away two seconds later, nothing posted in that slot",
            SystemNotificationPolicy.shouldPostDeferred(
                notification = notification(time = 1_000L),
                appInForeground = false,
                postedAt = null,
            ),
        )
        assertFalse(
            "back in the app: the in-app banner already said it",
            SystemNotificationPolicy.shouldPostDeferred(
                notification = notification(time = 1_000L, viewed = true),
                appInForeground = true,
                postedAt = null,
            ),
        )
        assertFalse(
            "the slot has carried a newer notification since",
            SystemNotificationPolicy.shouldPostDeferred(
                notification = notification(time = 1_000L),
                appInForeground = false,
                postedAt = 2_000L,
            ),
        )
        assertFalse(
            "this raise is the one that was posted",
            SystemNotificationPolicy.shouldPostDeferred(
                notification = notification(time = 2_000L),
                appInForeground = false,
                postedAt = 2_000L,
            ),
        )
        assertTrue(
            "newer than what the slot carries: it still wins",
            SystemNotificationPolicy.shouldPostDeferred(
                notification = notification(time = 3_000L),
                appInForeground = false,
                postedAt = 2_000L,
            ),
        )
        // Short enough that a user who really left is told while the turn is
        // still the newest thing that happened.
        assertEquals(2_000L, SystemNotificationPolicy.RECHECK_DELAY_MS)
    }

    /**
     * The second half of `viewed` (see
     * `SystemNotificationPolicy.watchedThroughout`): the frame that ends a turn
     * can be read long after it ended — a cached process is frozen, a socket
     * replays on reconnect, and the reconciliation carrier only runs on the way
     * back — so the focus test may not be asked about the *current* foreground
     * state. The measured v30 failure: a 4 s turn finished while the app was
     * backgrounded and no notification was ever posted, because the raise was
     * judged after the user was back on the session.
     */
    @Test
    fun theUserWatchedTheTurnOnlyIfTheAppStayedForeground() {
        // Foreground since before the turn started: the user was there for all
        // of it — upstream's focused window.
        assertTrue(SystemNotificationPolicy.watchedThroughout(foregroundSince = 100L, since = 100L))
        assertTrue(SystemNotificationPolicy.watchedThroughout(foregroundSince = 50L, since = 100L))
        // The app went to the background during the turn and came back: the
        // foreground period starts *after* the turn did, so they missed its end.
        assertFalse(SystemNotificationPolicy.watchedThroughout(foregroundSince = 200L, since = 100L))
        // Backgrounded right now, whatever the turn's date.
        assertFalse(SystemNotificationPolicy.watchedThroughout(foregroundSince = null, since = 100L))
        // Nothing to date (an ask, a foreign session): upstream's route-only
        // `viewed` stands, which is what the default `NotificationDelivery`
        // answers too.
        assertTrue(SystemNotificationPolicy.watchedThroughout(foregroundSince = null, since = null))
        assertTrue(SystemNotificationPolicy.watchedThroughout(foregroundSince = 100L, since = null))
    }

    // ------------------------------------------------------------------ sound

    @Test
    fun onlyTheDefaultStopPlays() {
        assertTrue(SystemNotificationPolicy.shouldPlaySound("default"))
        assertFalse(SystemNotificationPolicy.shouldPlaySound("none"))
        // Anything outside the two stored stops reads as silent, exactly like
        // `SettingsStore.readEnum`'s whitelist.
        assertFalse(SystemNotificationPolicy.shouldPlaySound(""))
        assertFalse(SystemNotificationPolicy.shouldPlaySound("aac-05"))
    }

    // -------------------------------------------------------------- body line

    @Test
    fun theSecondLineIsTheSessionTitleThenTheSessionId() {
        // Upstream `session.title ?? sessionID` (`notification.tsx:243-248`).
        assertEquals("Fix the build", SystemNotificationPolicy.bodyOf(notification(title = "Fix the build")))
        assertEquals("ses_1", SystemNotificationPolicy.bodyOf(notification()))
        // A title the raise could not know (a foreign session's row, or a
        // session the server renamed later) must not leave the body empty —
        // which is exactly what `sessionTitle ?: detail.orEmpty()` produced.
        assertEquals("ses_1", SystemNotificationPolicy.bodyOf(notification(title = "   ")))
    }

    @Test
    fun theErrorBodyPrefersTheErrorText() {
        // Upstream `session.title ?? error-description` (`notification.tsx:270-277`).
        assertEquals(
            "Rate limit reached",
            SystemNotificationPolicy.bodyOf(
                notification(kind = AppNotificationKind.ERROR, detail = "Rate limit reached"),
            ),
        )
        assertEquals(
            "Fix the build",
            SystemNotificationPolicy.bodyOf(
                notification(
                    kind = AppNotificationKind.ERROR,
                    title = "Fix the build",
                    detail = "Rate limit reached",
                ),
            ),
        )
        // No error text and no title: the id is still better than nothing.
        assertEquals("ses_1", SystemNotificationPolicy.bodyOf(notification(kind = AppNotificationKind.ERROR)))
        assertEquals(
            "the session-less, title-less raise has nothing to show",
            "",
            SystemNotificationPolicy.bodyOf(notification(sessionID = null)),
        )
    }

    // ------------------------------------------------------- notification slot

    @Test
    fun oneNotificationSlotPerSession() {
        val first = SystemNotificationPolicy.notificationId(notification(sessionID = "ses_1", time = 1_000L))
        val later = SystemNotificationPolicy.notificationId(
            notification(kind = AppNotificationKind.ERROR, sessionID = "ses_1", time = 9_000L),
        )
        // A newer notification for the same session replaces the old one instead
        // of stacking, whatever its kind or time.
        assertEquals(first, later)
        // …and it is stable across restarts (`String.hashCode` is specified), so
        // the tap target of a redelivered notification is the same slot.
        assertEquals("ses_1".hashCode(), first)
        assertNotEquals(first, SystemNotificationPolicy.notificationId(notification(sessionID = "ses_2")))
        assertNotEquals(
            first,
            SystemNotificationPolicy.notificationId(notification(sessionID = null)),
        )
        // Positive on purpose: `notify` accepts a negative id, a negative
        // PendingIntent request code is a trap.
        for (session in listOf("ses_1", "a", "", "会话")) {
            assertTrue(SystemNotificationPolicy.notificationId(notification(sessionID = session)) >= 0)
        }
    }

    // -------------------------------------------------------------- permission

    @Test
    fun thePermissionIsAskedOnceOn33AndLater() {
        // Pre-33: no runtime permission exists at all.
        assertFalse(SystemNotificationPolicy.shouldRequestPermission(26, granted = false, enabled = true))
        assertFalse(SystemNotificationPolicy.shouldRequestPermission(32, granted = false, enabled = true))
        // 33+: only while it is missing and the app would actually post.
        assertTrue(SystemNotificationPolicy.shouldRequestPermission(33, granted = false, enabled = true))
        assertTrue(SystemNotificationPolicy.shouldRequestPermission(35, granted = false, enabled = true))
        assertFalse(SystemNotificationPolicy.shouldRequestPermission(35, granted = true, enabled = true))
        assertFalse(
            "every notify switch off → never ask",
            SystemNotificationPolicy.shouldRequestPermission(35, granted = false, enabled = false),
        )
    }

    @Test
    fun thePermissionGateFollowsTheThreeNotifySwitches() {
        assertFalse(SystemNotificationPolicy.anyChannelEnabled(false, false, false))
        assertTrue(SystemNotificationPolicy.anyChannelEnabled(true, false, false))
        assertTrue(SystemNotificationPolicy.anyChannelEnabled(false, true, false))
        assertTrue(SystemNotificationPolicy.anyChannelEnabled(false, false, true))
    }

    // ------------------------------------------------------------------ codec

    @Test
    fun theFeedRoundTripsThroughItsWireFormat() {
        val entries = listOf(
            notification(kind = AppNotificationKind.AGENT, sessionID = "ses_1", time = 1_700_000_000_000L, title = "Fix the build"),
            notification(kind = AppNotificationKind.ERROR, sessionID = null, time = 1_700_000_001_000L, detail = "Rate limit reached", viewed = true),
        )
        assertEquals(entries, NotificationCodec.decode(NotificationCodec.encode(entries)))
    }

    @Test
    fun aCorruptOrForeignBlobReadsAsEmpty() {
        assertEquals(emptyList<AppNotification>(), NotificationCodec.decode(null))
        assertEquals(emptyList<AppNotification>(), NotificationCodec.decode(""))
        assertEquals(emptyList<AppNotification>(), NotificationCodec.decode("   "))
        assertEquals(emptyList<AppNotification>(), NotificationCodec.decode("{not json"))
        assertEquals(emptyList<AppNotification>(), NotificationCodec.decode("{\"list\":[]}"))
    }

    @Test
    fun anEntryTheCurrentBuildCannotReadIsDroppedNotFatal() {
        val blob = """
            [
              {"id":"AGENT:ses_1:2000","kind":"AGENT","sessionID":"ses_1","time":2000,"viewed":false},
              {"id":"x","kind":"NOT_A_KIND","time":2000,"viewed":false},
              {"id":"x","kind":"AGENT","time":0,"viewed":false},
              "not an object",
              {"kind":"ERROR","time":3000}
            ]
        """.trimIndent()
        val decoded = NotificationCodec.decode(blob)
        assertEquals(
            listOf(AppNotificationKind.AGENT, AppNotificationKind.ERROR),
            decoded.map { it.kind },
        )
        // An entry without a stored id gets the same id the raise path builds.
        assertEquals("ERROR::3000", decoded.last().id)
        assertNull(decoded.last().sessionID)
    }

    // --------------------------------------------------------- tap → session

    @Test
    fun aTapIsDeliveredToTheNavExactlyOnce() {
        assertNull(NotificationNavigation.consume())
        NotificationNavigation.request("ses_1")
        assertEquals("ses_1", NotificationNavigation.pending.value)
        assertEquals("ses_1", NotificationNavigation.consume())
        assertNull("consumed once", NotificationNavigation.consume())
        assertNull(NotificationNavigation.pending.value)

        // A notification without a session must not ask the nav for anything.
        NotificationNavigation.request(null)
        NotificationNavigation.request("  ")
        assertNull(NotificationNavigation.consume())
    }

    // ----------------------------------------------------------- Android glue

    @Test
    fun theManifestDeclaresThePermissionTheChannelNeeds() {
        val manifest = source("src/main/AndroidManifest.xml")
        assertTrue(
            manifest.contains(
                """<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />""",
            ),
        )
        // The launcher keeps `singleTask` (one task, one instance), which is what
        // makes the PendingIntent below land on `onNewIntent`.
        assertTrue(manifest.contains("android:launchMode=\"singleTask\""))
    }

    @Test
    fun theSystemNotifierPostsThroughAChannelAndOpensTheSession() {
        val notifier = source("src/main/java/com/xiufeigo/opencodeshell/notify/SystemNotifier.kt")
        assertTrue(notifier.contains("NotificationChannel("))
        assertTrue(notifier.contains("NotificationManagerCompat.from(appContext)"))
        assertTrue(notifier.contains("areNotificationsEnabled()"))
        // The channel is silent: the sound is a per-kind setting, and a channel's
        // sound cannot change after creation.
        assertTrue(notifier.contains("setSound(null, null)"))
        assertTrue(notifier.contains("RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)"))
        assertTrue(notifier.contains("shouldPlaySound(soundSettingFor(notification.kind))"))
        // The tap target is the Compose launcher with the session id, not the
        // legacy WebView shell.
        assertTrue(notifier.contains("Intent(appContext, OcActivity::class.java)"))
        assertTrue(notifier.contains("SystemNotificationPolicy.EXTRA_SESSION_ID"))
        assertTrue(notifier.contains("PendingIntent.getActivity("))
        assertTrue(notifier.contains("SystemNotificationPolicy.notificationId(notification)"))
        // The focus test is the resumed-activity state **that still holds the
        // window focus** (`ForegroundTracker.contentVisible`, v68 5554: the
        // resumed set alone stays lit under a covering freeform window).
        assertTrue(notifier.contains("registerActivityLifecycleCallbacks"))
        assertTrue(notifier.contains("shouldDeliver(notification, contentVisible)"))
        assertTrue(notifier.contains("fun onWindowFocusChanged(hasFocus: Boolean)"))
        assertTrue(notifier.contains("focusTimedOut()"))
        // The second line is the upstream fallback chain, in one place.
        assertTrue(notifier.contains("SystemNotificationPolicy.bodyOf(notification)"))
        // Delivery must never break the event stream it is called from.
        assertTrue(notifier.contains("catch (error: RuntimeException)"))
    }

    @Test
    fun theLauncherRequestsThePermissionAndForwardsTheTap() {
        val activity = source("src/main/java/com/xiufeigo/opencodeshell/OcActivity.kt")
        assertTrue(activity.contains("Manifest.permission.POST_NOTIFICATIONS"))
        assertTrue(activity.contains("ActivityResultContracts.RequestPermission()"))
        assertTrue(activity.contains("shouldRequestPermission(Build.VERSION.SDK_INT, granted, enabled)"))
        assertTrue(activity.contains("anyChannelEnabled("))
        // Cold start (onCreate) and warm start (onNewIntent) both feed the channel.
        assertTrue(
            activity.contains(
                "NotificationNavigation.request(intent?.getStringExtra(SystemNotificationPolicy.EXTRA_SESSION_ID))",
            ),
        )
        assertTrue(
            activity.contains(
                "NotificationNavigation.request(intent.getStringExtra(SystemNotificationPolicy.EXTRA_SESSION_ID))",
            ),
        )
        assertTrue(activity.contains("override fun onNewIntent(intent: Intent)"))
        // The pipeline's Android halves are wired into the ViewModel.
        assertTrue(activity.contains("notificationPersistence = notificationPrefs"))
        assertTrue(activity.contains("notificationDelivery = notifications"))
        // v68 5554: the window focus reaches the notifier — the lifecycle
        // callbacks cannot see a freeform window being covered.
        assertTrue(activity.contains("override fun onWindowFocusChanged(hasFocus: Boolean)"))
        assertTrue(activity.contains("systemNotifier?.onWindowFocusChanged(hasFocus)"))
    }

    @Test
    fun theNavGraphOpensTheSessionTheTapAskedFor() {
        val app = source("src/main/java/com/xiufeigo/opencodeshell/ui/App.kt")
        assertTrue(app.contains("NotificationNavigation.pending.collectAsStateWithLifecycle()"))
        assertTrue(app.contains("navController.currentBackStackEntryAsState()"))
        assertTrue(
            app.contains(
                "if (NotificationNavigation.arrived(backStackEntry?.arguments?.getString(Route.ARG_ID))) {",
            ),
        )
        assertTrue(app.contains("navController.navigate(NavRestore.session(serverKey, id)) { launchSingleTop = true }"))
    }

    /**
     * v64 (`research/v64_verify/5558/17b-18b`): the tap came back to the page the
     * app was last showing. The request is now held until the session is really
     * the destination, so a navigation the graph refuses — or one that something
     * else lands on top of — re-issues instead of dropping the tap.
     */
    @Test
    fun aTapIsHeldUntilTheSessionIsActuallyOnScreen() {
        NotificationNavigation.consume()
        NotificationNavigation.request("ses_v64")
        // The scaffold is on some other page (the draft): not arrived, not cleared.
        assertFalse(NotificationNavigation.arrived(null))
        assertFalse(NotificationNavigation.arrived("ses_other"))
        assertEquals("ses_v64", NotificationNavigation.pending.value)
        // The navigate is issued; the request only clears once the destination is
        // the session, and a re-check before that still holds it.
        assertFalse(NotificationNavigation.arrived(null))
        assertTrue(NotificationNavigation.arrived("ses_v64"))
        assertEquals("ses_v64", NotificationNavigation.consume())
        assertNull(NotificationNavigation.pending.value)
        assertFalse("cleared: nothing left to hold", NotificationNavigation.arrived("ses_v64"))
    }

    @Test
    fun theFeedIsPersistedThroughItsOwnPrefsFile() {
        val prefs = source("src/main/java/com/xiufeigo/opencodeshell/notify/NotificationPrefs.kt")
        assertTrue(prefs.contains("getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)"))
        assertTrue(prefs.contains("NotificationCodec.encode(notifications)"))
        assertTrue(prefs.contains("NotificationCodec.decode(prefs.getString(KEY_LIST, null))"))
    }

    @Test
    fun theSoundStopsStayTheTwoThatCanPlay() {
        val model = source("src/main/java/com/xiufeigo/opencodeshell/ui/settings/SettingsModel.kt")
        // The two stops that can actually play (`NotificationSoundsModelTest`
        // pins the structural half); the 45 bundled upstream ids stay absent.
        assertTrue(model.contains("SettingsOption(\"none\", R.string.oc_setting_sound_none)"))
        assertTrue(model.contains("SettingsOption(\"default\", R.string.oc_setting_sound_default)"))
        assertTrue(
            "the 45-sound deviation is recorded where the stops are declared",
            model.contains("45 bundled"),
        )
    }
}
