package com.xiufeigo.opencodeshell.web.host

import com.xiufeigo.opencodeshell.web.host.RoundActivityTracker.RoundStreamSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F4 tripwire: "round ends => KEEP_SCREEN_ON must be released".
 *
 * ## The defect this locks down
 *
 * V5 (`research/web/RELEASE_VERIFY_V5.md` §6) measured on a real device: a turn
 * finished in 4 s, the input box went back to its placeholder, and
 * `fl=KEEP_SCREEN_ON` was still set 4 minutes later, re-set every 90 s.
 * F4 re-measured the same thing independently before touching the code
 * (`research/web/FIX_F4_KEEPSCREEN.md` §1): turn ended in 6 s, flag still HIT
 * at T+25 s / T+65 s / T+120 s, and re-set by the backstop at +90 s.
 *
 * Root cause: the resident `GET /event` heartbeat refreshed `lastActivityAtMs`,
 * so `idleMs >= SSE_IDLE_READ_TIMEOUT_MS` (the only round-end trigger) could
 * never hold, and the 90 s hold timeout could never expire.
 *
 * Every test here is written so that **removing the release makes it fail**.
 *
 * NOTE on the clock: `RoundActivityTracker` uses `0L` as the "no round"
 * sentinel for `roundStartAtMs`, so a round must never be started at `now = 0`.
 * Hence [T0].
 */
class RoundStreamSignalReleaseTest {

    /** A non-zero wall clock, because 0 means "no round" inside the tracker. */
    private val T0 = 1_000_000L

    private fun tracker() = RoundActivityTracker()

    /** Upstream's current round-status frame: `session.status` -> `status.type = idle`. */
    private fun upstreamIdleFrame(sessionId: String = "ses_abc") =
        """data: {"type":"session.status","properties":{"sessionID":"$sessionId","status":{"type":"idle"}}}"""

    /** Upstream's deprecated round-end frame, still emitted by older servers. */
    private fun legacyIdleFrame(sessionId: String = "ses_abc") =
        """data: {"type":"session.idle","properties":{"sessionID":"$sessionId"}}"""

    /** A real round frame: assistant text streaming in. */
    private fun messageDeltaFrame() =
        """data: {"type":"message.part.updated","properties":{"part":{"type":"text","text":"Building"}}"""

    /** The ~10 s keep-alive the official UI's permanent `/event` subscription emits. */
    private fun heartbeatFrame() = """: ping

"""

    // ---------------------------------------------------------------- release on turn end

    @Test
    fun upstreamIdleStatusFrameReleasesTheFlag() {
        val t = tracker()
        t.onRoundStart(T0)
        assertTrue("turn must be holding the flag while it runs", t.isRoundActive(T0))
        assertFalse(
            "host must want the flag while the turn runs",
            PowerPolicy.mustClearKeepScreenOn(foreground = true, roundActive = true),
        )

        val endAt = T0 + 4_000L
        val signal = t.classifyRoundStream(upstreamIdleFrame())
        assertEquals(RoundStreamSignal.ROUND_END, signal)
        assertTrue("a finished turn is a state change", t.onRoundStreamSignal(endAt, signal))

        assertFalse("turn ended => must NOT stay active", t.isRoundActive(endAt))
        assertTrue(
            "turn ended => host must clear KEEP_SCREEN_ON",
            PowerPolicy.mustClearKeepScreenOn(foreground = true, roundActive = t.isRoundActive(endAt)),
        )
        // 4 minutes later it must still be released (V5's observation window).
        assertFalse(t.isRoundActive(endAt + 240_000L))
    }

    @Test
    fun legacySessionIdleFrameReleasesTheFlag() {
        val t = tracker()
        t.onRoundStart(T0)
        val signal = t.classifyRoundStream(legacyIdleFrame())
        assertEquals(RoundStreamSignal.ROUND_END, signal)
        assertTrue(t.onRoundStreamSignal(T0 + 4_000L, signal))
        assertFalse(t.isRoundActive(T0 + 4_000L))
    }

    @Test
    fun spacedJsonIdleIsStillRecognised() {
        assertEquals(
            RoundStreamSignal.ROUND_END,
            tracker().classifyRoundStream("""data: {"type": "session.status", "status": {"type": "idle"}}"""),
        )
    }

    // ---------------------------------------------------------------- heartbeats

    @Test
    fun busyStatusFrameKeepsTheRoundAlive() {
        val t = tracker()
        t.onRoundStart(T0)
        val signal = t.classifyRoundStream(
            """data: {"type":"session.status","properties":{"status":{"type":"busy"}}}""",
        )
        assertEquals(RoundStreamSignal.ACTIVITY, signal)
        t.onRoundStreamSignal(T0 + 5_000L, signal)
        assertTrue("a busy session is still a running turn", t.isRoundActive(T0 + 5_000L))
    }

    @Test
    fun heartbeatFramesAreNotRoundActivity() {
        val t = tracker()
        assertEquals(RoundStreamSignal.HEARTBEAT, t.classifyRoundStream(heartbeatFrame()))
        assertEquals(RoundStreamSignal.HEARTBEAT, t.classifyRoundStream(""))
        assertEquals(RoundStreamSignal.HEARTBEAT, t.classifyRoundStream("data: {}\n\n"))
        assertEquals(RoundStreamSignal.HEARTBEAT, t.classifyRoundStream("event: ping\ndata: \n\n"))
    }

    @Test
    fun heartbeatFramesMustNotRefreshTheIdleClock() {
        // The exact mechanism of the bug: a heartbeat used to move
        // lastActivityAtMs, which is what kept `idleMs` under the 35 s threshold.
        val t = tracker()
        t.onRoundStart(T0)
        val heartbeat = t.classifyRoundStream(heartbeatFrame())
        assertEquals(0L, t.idleMs(T0))
        t.onRoundStreamSignal(T0 + 10_000L, heartbeat)
        assertEquals(
            "a heartbeat must not move lastActivityAtMs forward (that was the bug)",
            10_000L,
            t.idleMs(T0 + 10_000L),
        )
    }

    @Test
    fun heartbeatOnlyTrafficCannotHoldTheFlagForever() {
        val t = tracker()
        t.onRoundStart(T0)
        // A running turn: real frames keep it alive across the 90 s hold window.
        var now = T0
        while (now <= T0 + 2 * t.roundHoldTimeoutMs) {
            t.onRoundStreamSignal(now, t.classifyRoundStream(messageDeltaFrame()))
            now += 5_000L
        }
        assertTrue("real round traffic must hold it", t.isRoundActive(now))

        // Turn finished, the connection stays open and only heartbeats remain.
        val endAt = now + 10_000L
        t.onRoundStreamSignal(endAt, t.classifyRoundStream(upstreamIdleFrame()))
        assertFalse(t.isRoundActive(endAt))

        // Even if the marker never arrives, heartbeats must not pin the flag.
        val stuck = tracker()
        stuck.onRoundStart(T0)
        var clock = T0
        while (clock < T0 + 5 * 60_000L) {
            stuck.onRoundStreamSignal(clock, stuck.classifyRoundStream(heartbeatFrame()))
            clock += 10_000L
        }
        assertFalse(
            "heartbeats alone must not keep the screen pinned on",
            stuck.isRoundActive(clock),
        )
    }

    // ---------------------------------------------------------------- the V5 timeline, executable

    @Test
    fun v5TimelineHeartbeatsThenIdleMarkerReleasesTheFlag() {
        val t = tracker()
        t.onRoundStart(T0)

        // V5 §6.3 / F4 §1.3: the turn set the flag, then the 90 s backstop saw
        // roundActive=true and re-set it, every 90 s, forever. Heartbeats must
        // not produce that any more.
        var now = T0
        var backstopHits = 0
        while (now < T0 + 4 * 60_000L) {
            t.onRoundStreamSignal(now, t.classifyRoundStream(heartbeatFrame()))
            if (t.isRoundActive(now) && t.idleMs(now) >= t.roundHoldTimeoutMs) backstopHits++
            now += 10_000L
        }
        assertEquals(
            "no heartbeat-driven round activity may exist in 4 minutes",
            0,
            backstopHits,
        )

        // The turn itself ended after 4 s and the server said so on /event.
        val endAt = T0 + 4_000L
        t.onRoundStreamSignal(endAt, t.classifyRoundStream(upstreamIdleFrame()))
        assertFalse("V5's failing step: turn end must release", t.isRoundActive(endAt))
        assertFalse("and it must stay released", t.isRoundActive(endAt + 240_000L))
    }

    @Test
    fun aRunningTurnIsStillHeldBeforeTheEndMarkerArrives() {
        // Guards the opposite failure: the fix must not release a turn that is
        // genuinely running (that would be "screen dims mid-turn").
        val t = tracker()
        t.onRoundStart(T0)
        var now = T0
        while (now < T0 + 3 * 60_000L) {
            t.onRoundStreamSignal(now, t.classifyRoundStream(messageDeltaFrame()))
            now += 5_000L
        }
        assertTrue("a 3-minute turn with live deltas must stay active", t.isRoundActive(now))
    }

    // ---------------------------------------------------------------- other release paths

    @Test
    fun leavingForegroundAlwaysClearsTheFlag() {
        // Requirement: onPause/onStop must release even with a live turn.
        assertTrue(PowerPolicy.mustClearKeepScreenOn(foreground = false, roundActive = true))
        assertTrue(PowerPolicy.mustClearKeepScreenOn(foreground = false, roundActive = false))
        assertTrue(PowerPolicy.mustClearKeepScreenOn(foreground = true, roundActive = false))
        assertFalse(PowerPolicy.mustClearKeepScreenOn(foreground = true, roundActive = true))
    }

    @Test
    fun explicitPageCallStillEndsTheRound() {
        // The page bridge path must keep working alongside the new stream path.
        val t = tracker()
        t.onRoundStart(T0)
        assertTrue(t.onRoundEnd())
        assertFalse(t.isRoundActive(T0))
        assertFalse("ending twice is a no-op", t.onRoundEnd())
    }

    @Test
    fun roundEndMarkerSplitAcrossTwoReadsIsStillSeen() {
        // LoopbackProxy keeps a bounded tail (SSE_SCAN_WINDOW_CHARS) so a marker
        // cut in half by a socket read is still matched.
        val whole = upstreamIdleFrame()
        val cut = whole.length / 2
        val firstHalf = whole.substring(0, cut)
        val secondHalf = whole.substring(cut)
        assertEquals(RoundStreamSignal.ACTIVITY, tracker().classifyRoundStream(firstHalf))
        assertEquals(
            RoundStreamSignal.ROUND_END,
            tracker().classifyRoundStream(firstHalf + secondHalf),
        )
    }

    @Test
    fun aSecondTurnAfterTheFirstWorksAgain() {
        val t = tracker()
        t.onRoundStart(T0)
        t.onRoundStreamSignal(T0 + 4_000L, t.classifyRoundStream(upstreamIdleFrame()))
        assertFalse(t.isRoundActive(T0 + 4_000L))

        val second = T0 + 30_000L
        t.onRoundStart(second)
        assertTrue("the next turn must set the flag again", t.isRoundActive(second))
        t.onRoundStreamSignal(second + 4_000L, t.classifyRoundStream(upstreamIdleFrame()))
        assertFalse(t.isRoundActive(second + 4_000L))
    }
}
