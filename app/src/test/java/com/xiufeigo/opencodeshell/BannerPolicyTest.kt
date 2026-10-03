package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.notify.AppNotification
import com.xiufeigo.opencodeshell.notify.AppNotificationKind
import com.xiufeigo.opencodeshell.notify.BannerPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * v48/v49 banner band: the transient selection/snooze rules behind
 * `ui/App.kt` `NotificationBanner` — the newest entry shows, retired ids stay
 * out of the band without touching the feed, and genuinely new entries always
 * surface. v49 dropped the `!viewed` filter (a reply that finished while the
 * user was looking at that session is exactly what the band is for) and put
 * the opening retirement set in its place.
 */
class BannerPolicyTest {

    private fun entry(id: String, session: String? = "ses_1", viewed: Boolean = false) =
        AppNotification(
            id = id,
            kind = AppNotificationKind.AGENT,
            sessionID = session,
            sessionTitle = null,
            detail = null,
            time = 1_000L,
            viewed = viewed,
        )

    @Test
    fun autoDismissIsAboutEightSeconds() {
        assertEquals(8_000L, BannerPolicy.AUTO_DISMISS_MS)
    }

    @Test
    fun selectShowsTheNewestEntry() {
        val feed = listOf(entry("a"), entry("b"), entry("c"))
        assertEquals("c", BannerPolicy.select(feed, emptySet())?.id)
    }

    @Test
    fun selectShowsAViewedEntryThatJustArrived() {
        // v49 tE: the foreground 「回复已完成」 case. The turn settles while the
        // user is looking at that very session, so the raise is `viewed=true` —
        // and the old `!viewed` filter made that the one entry the band could
        // never show (2 of 7 rounds popped anything; the rest needed a restart).
        val feed = listOf(entry("a", viewed = true), entry("b", viewed = true))
        assertEquals("b", BannerPolicy.select(feed, emptySet())?.id)
    }

    @Test
    fun theOpeningRetirementSetKeepsARestoredFeedOffTheBand() {
        // The same entries, but the band mounted with the feed already there —
        // the cold-start/persisted case. Freshness, not seen-ness, decides.
        val restored = listOf(entry("a", viewed = true), entry("b", viewed = true))
        val hidden = BannerPolicy.allIds(restored)
        assertNull(BannerPolicy.select(restored, hidden))

        val raised = restored + entry("c", viewed = true)
        assertEquals("c", BannerPolicy.select(raised, hidden)?.id)
    }

    @Test
    fun autoHideRetiresTheShownEntryEvenWhenItIsViewed() {
        // The v49 common case: the shown entry is `viewed`, so the pre-v49
        // auto-hide (which retired only unviewed ids) left it on screen.
        val feed = listOf(entry("a", viewed = true), entry("b", viewed = true))
        val hidden = BannerPolicy.hiddenAfterShow(feed, emptySet(), "b")
        assertEquals(setOf("b"), hidden)
        assertEquals("a", BannerPolicy.select(feed, hidden)?.id)
    }

    @Test
    fun autoHideAlsoRetiresTheUnviewedQueueBehindIt() {
        val feed = listOf(entry("a", viewed = true), entry("b"), entry("c"))
        val hidden = BannerPolicy.hiddenAfterShow(feed, emptySet(), "a")
        assertEquals(setOf("a", "b", "c"), hidden)
        assertNull(BannerPolicy.select(feed, hidden))
    }

    @Test
    fun selectFallsBackPastRetiredIds() {
        // v48 tE: retiring the shown entry must reveal the queued older one —
        // and retiring that one hides the band — instead of looking dead.
        val feed = listOf(entry("a"), entry("b"))
        assertEquals("a", BannerPolicy.select(feed, setOf("b"))?.id)
        assertNull(BannerPolicy.select(feed, setOf("a", "b")))
    }

    @Test
    fun selectRendersNothingWhenThereIsNoNews() {
        assertNull(BannerPolicy.select(emptyList(), emptySet()))
        // Retired ids of entries that left the feed hide nothing by themselves.
        assertNull(BannerPolicy.select(emptyList(), setOf("gone")))
    }

    @Test
    fun aGenuinelyNewEntrySurfacesDespiteTheSnooze() {
        // The snooze retires what was shown — never what arrives next.
        val shown = listOf(entry("a"), entry("b"))
        val hidden = BannerPolicy.hideUnviewed(shown, emptySet())
        assertNull(BannerPolicy.select(shown, hidden))

        val raised = shown + entry("c")
        assertEquals("c", BannerPolicy.select(raised, hidden)?.id)
    }

    @Test
    fun hiddenAfterPrunesIdsThatLeftTheFeed() {
        val feed = listOf(entry("a"), entry("b"))
        // "gone" was acknowledged/cleared since: it must not linger in the set.
        val next = BannerPolicy.hiddenAfter(feed, setOf("gone"), listOf("b"))
        assertEquals(setOf("b"), next)
    }

    @Test
    fun hiddenAfterWithNothingNewKeepsTheSet() {
        val feed = listOf(entry("a"))
        assertEquals(setOf("a"), BannerPolicy.hiddenAfter(feed, setOf("a"), emptyList()))
    }

    @Test
    fun hideUnviewedRetiresOnlyTheUnseenHalf() {
        val feed = listOf(entry("a", viewed = true), entry("b"), entry("c"))
        val hidden = BannerPolicy.hideUnviewed(feed, emptySet())
        assertEquals(setOf("b", "c"), hidden)
        // 「全部清除」 drops the feed right after, so a `viewed` entry still in
        // the band is harmless here — the tap is not the auto-hide path.
        assertEquals("a", BannerPolicy.select(feed, hidden)?.id)
    }

    // ------------------------------------------------- the taps (v50 tE)

    /**
     * v50 tE, the regression: the feed holds several entries for one session and
     * the band shows one at a time, so retiring the shown id alone handed the
     * frame to the twin behind it — the same title, the same second line. The
     * tap measured 8.3 s, exactly like not tapping (v49: gone on the tap).
     *
     * 「知道了」 retires the session, the same scope [AppNotifier.acknowledge]
     * marks viewed — `viewed` no longer steers the band (v49), so the retirement
     * has to do that work itself.
     */
    @Test
    fun dismissRetiresTheWholeSessionSoTheBandGoesQuietAtOnce() {
        val feed = listOf(
            entry("a", viewed = true),
            entry("b", viewed = true),
            entry("c", viewed = true),
        )
        val hidden = BannerPolicy.hiddenAfterDismiss(feed, emptySet(), feed.last())
        assertEquals("every entry of the session is retired", setOf("a", "b", "c"), hidden)
        assertNull(BannerPolicy.select(feed, hidden))
    }

    @Test
    fun dismissLeavesAnotherSessionsNewsAlone() {
        val feed = listOf(entry("a", session = "ses_1"), entry("b", session = "ses_2"))
        val hidden = BannerPolicy.hiddenAfterDismiss(feed, emptySet(), feed.last())
        assertEquals(setOf("b"), hidden)
        assertEquals("a", BannerPolicy.select(feed, hidden)?.id)
    }

    @Test
    fun dismissDropsRetiredIdsThatLeftTheFeed() {
        val feed = listOf(entry("a"))
        val hidden = BannerPolicy.hiddenAfterDismiss(feed, setOf("gone"), feed.single())
        assertEquals(setOf("a"), hidden)
    }

    /**
     * 「全部清除」 retires the whole feed, `viewed` half included: the clear that
     * follows is a round-trip, and a `viewed` entry would otherwise keep the
     * band up until it lands (the common case since v49).
     */
    @Test
    fun dismissAllRetiresEveryEntryViewedOrNot() {
        val feed = listOf(entry("a", viewed = true), entry("b"), entry("c", viewed = true))
        val hidden = BannerPolicy.hiddenAfterDismissAll(feed, emptySet())
        assertEquals(setOf("a", "b", "c"), hidden)
        assertNull(BannerPolicy.select(feed, hidden))
    }

    // --------------------------------------- transport failure vs. real one

    /** The exact line the v57 device round produced with `adb reverse` pulled. */
    private fun failure(id: String, message: String, detail: String? = null) =
        BannerPolicy.FailureRow(id = id, message = message, detail = detail)

    @Test
    fun aConnectionFailureGoesStaleOnceTheServerAnswersAgain() {
        // v57 (`E2ECHK5556-28…30`): the card said 「无法连接到此服务器」 and stayed
        // on screen long after the reverse was restored, because nothing retires
        // a transport failure row.
        val rows = listOf(failure("e1", "Failed to connect to /127.0.0.1:4095"))

        assertEquals(setOf("e1"), BannerPolicy.staleTransportIds(rows))
    }

    @Test
    fun aRealFailureKeepsItsCardAfterRecovery() {
        // The failure history *is* the transcript upstream projects
        // (`TimelineRow.Error`, `projection.ts:357-359`): quota, region, a
        // provider error and a missing directory all survive a later turn.
        val rows = listOf(
            failure("q", "Token Plan quota exhausted", """{"type":"quota_error","status":429}"""),
            failure("r", "This model is not available in your country.", """{"type":"provider.auth","status":403}"""),
            failure("d", "HTTP 500 PlatformError: NotFound: FileSystem.realPath"),
            failure("p", "Something went wrong", """{"type":"provider.invalid-request"}"""),
        )

        assertEquals(emptySet<String>(), BannerPolicy.staleTransportIds(rows))
    }

    @Test
    fun onlyTheTransportCardsAreRetiredFromAMixedTranscript() {
        val rows = listOf(
            failure("t1", "Connection reset by peer"),
            failure("p1", "Something went wrong"),
            failure("t2", "unexpected end of stream"),
            failure("p2", "Rate limit reached"),
        )

        assertEquals(setOf("t1", "t2"), BannerPolicy.staleTransportIds(rows))
    }

    @Test
    fun anEmptyTranscriptRetiresNothing() {
        assertEquals(emptySet<String>(), BannerPolicy.staleTransportIds(emptyList()))
    }
}
