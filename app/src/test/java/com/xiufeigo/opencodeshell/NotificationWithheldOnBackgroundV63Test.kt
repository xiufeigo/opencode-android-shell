package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.notify.AppNotification
import com.xiufeigo.opencodeshell.notify.AppNotificationKind
import com.xiufeigo.opencodeshell.notify.SystemNotificationPolicy
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v63 P1 「后台会话完成不投递系统通知」(`research/v63_verify/5558/50_home_bg.png` …
 * `57_final.png`): a question was raised while the tester was looking at that
 * very session, the HOME press came a few seconds later, and the launcher was
 * then watched for 64 s and again for 150 s with `dumpsys notification`
 * counting 0 — `POST_NOTIFICATIONS` granted, the `opencode.sessions` channel
 * created, and the same mechanism known to have posted before.
 *
 * The chain was right at every step; the *order* was not. The raise was skipped
 * as 「the user is looking at that session」, the re-check asked again
 * [SystemNotificationPolicy.RECHECK_DELAY_MS] later, and by then the user was
 * still in the app — so the raise was dropped, correctly, and the news went with
 * it. The one moment the withheld raise could still have been spoken for is the
 * moment the user actually leaves, and nothing re-armed it.
 *
 * [SystemNotificationPolicy.shouldPostOnBackground] is that third carrier, and
 * this pins its two gates (still the newest for the slot, still recent) plus
 * the wiring that runs it on the last resumed activity's pause.
 */
class NotificationWithheldOnBackgroundV63Test {

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("missing $relative")

    private val notifier =
        source("src/main/java/com/xiufeigo/opencodeshell/notify/SystemNotifier.kt")

    private fun notification(time: Long = 1_000L, viewed: Boolean = true) = AppNotification(
        id = "AGENT:ses_1:$time",
        kind = AppNotificationKind.AGENT,
        sessionID = "ses_1",
        sessionTitle = "Shell",
        detail = null,
        time = time,
        viewed = viewed,
    )

    // ------------------------------------------------------------ the policy

    @Test
    fun aWithheldRaiseIsSpokenForWhenTheAppGoesAway() {
        val raised = notification(time = 1_000L)
        assertTrue(
            "nothing posted in that slot yet",
            SystemNotificationPolicy.shouldPostOnBackground(raised, postedAt = null, now = 3_000L),
        )
        // The re-check's own de-dupe, applied to the same second opinion: a
        // newer notification for the session supersedes the withheld one.
        assertFalse(
            "the slot has carried a newer notification since",
            SystemNotificationPolicy.shouldPostOnBackground(raised, postedAt = 2_000L, now = 3_000L),
        )
        assertFalse(
            "this raise is the one that was posted",
            SystemNotificationPolicy.shouldPostOnBackground(
                notification(time = 2_000L),
                postedAt = 2_000L,
                now = 3_000L,
            ),
        )
        assertTrue(
            "newer than what the slot carries: it still wins",
            SystemNotificationPolicy.shouldPostOnBackground(
                notification(time = 3_000L),
                postedAt = 2_000L,
                now = 3_000L,
            ),
        )
    }

    @Test
    fun onlyNewsIsWithheld() {
        val raised = notification(time = 1_000L)
        // The edge of the window is inside it, the next millisecond is not: the
        // bound is what stops a background transition hours later from
        // resurrecting a banner about a reply that finished long ago.
        val edge = 1_000L + SystemNotificationPolicy.WITHHELD_TTL_MS
        assertTrue(
            SystemNotificationPolicy.shouldPostOnBackground(raised, postedAt = null, now = edge),
        )
        assertFalse(
            SystemNotificationPolicy.shouldPostOnBackground(raised, postedAt = null, now = edge + 1),
        )
        // Two minutes: past the re-check and a glance at the transcript, short
        // enough to be news.
        assertEquals(120_000L, SystemNotificationPolicy.WITHHELD_TTL_MS)
        // The bound is measured on the client's own clock, like every other
        // timestamp in the chain — a server `created` is hours off a device.
        assertEquals(1_000L, notification(time = 1_000L).time)
    }

    @Test
    fun theSkippedRaiseIsKeptForTheTransitionNotOnlyForTheReCheck() {
        // The raise records the skip in `withheld` on the way to the re-check —
        // without that the background transition would have nothing to speak for
        // (the v63 loss).
        assertTrue(
            notifier.contains(
                "withheld[SystemNotificationPolicy.notificationId(notification)] = notification",
            ),
        )
        // …and a post retires it, so the same slot never posts twice.
        assertTrue(notifier.contains("withheld.remove(id)"))
        // A newer raise for the same session supersedes the withheld one: the
        // slot is the session's, so only the newest raise is worth posting.
        assertTrue(notifier.contains("pending.remove(id)"))
    }

    @Test
    fun theTransitionIsTheLastResumedActivitysPause() {
        // Any pause would fire on a second activity coming to the top; only the
        // one that empties the set is the app leaving.
        assertTrue(
            notifier.contains(
                """
                    if (!foreground.inForeground) postWithheld()
                """.trimIndent(),
            ),
        )
        assertTrue(notifier.contains("private fun postWithheld()"))
        assertTrue(notifier.contains("SystemNotificationPolicy.shouldPostOnBackground(notification, posted[id], now)"))
        // …and the drain reads `withheld` while the map may be written from the
        // event-stream coroutine, so it iterates a snapshot of the keys.
        assertTrue(notifier.contains("for (id in withheld.keys.toList())"))
    }

    @Test
    fun aPostedRaiseIsNeverWithheldTwice() {
        // `post` is the single write to `posted`, and it is where the slot is
        // cleared, so the re-check, the background drain and the raise itself
        // all de-dupe through it.
        assertEquals(1, Regex("""posted\[id\] = notification\.time""").findAll(notifier).count())
        assertTrue(notifier.contains("handler.removeCallbacksAndMessages(null)"))
        // The drain is on the main thread (the lifecycle callback), so it
        // catches for the same reason `recheck` does: a vendor ROM refusing the
        // post must not take the app down.
        assertTrue(notifier.contains("withheld post failed"))
    }
}
