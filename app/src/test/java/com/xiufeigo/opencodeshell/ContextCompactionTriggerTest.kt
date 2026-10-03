package com.xiufeigo.opencodeshell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The client-side pre-emptive compaction threshold (device v19 P2,
 * `research/v19_verify/b/`): the app asks for a compaction **before** a turn can
 * walk into the provider's dead zone.
 *
 * The reference the client margin is measured against is upstream's own trigger
 * (`_upstream/packages/core/src/session/compaction.ts:742-763`), reproduced here
 * through [ContextCompactionPolicy.upstreamCeiling]:
 *
 * ```ts
 * const output = Math.min(limit.output, OUTPUT_TOKEN_MAX)                 // 32_000
 * const promptCeiling = Math.min(
 *   limit.input === undefined ? Infinity : limit.input - config.buffer,   // 20_000
 *   context - Math.max(output, config.buffer),
 * )
 * ```
 *
 * On a 1,048,576-token window that is `1_048_576 - 32_000 = 1_016_576`, i.e.
 * 96.9 % — the server only compacts once a *completed* turn's usage has already
 * reached it. The v19 session added `input=643K` + `cache_read=1.3M` in a single
 * turn and the provider answered `InvalidRequest` instead; the 80 % client
 * threshold is the margin that keeps the next turn out of that zone.
 */
class ContextCompactionTriggerTest {

    /** The context window the v19 evidence was measured on (a 1M model). */
    private val limit = 1_048_576

    /** The v19 numerator: `input=643K` + `cache_read=1.3M`, i.e. ~1.85× the window. */
    private val v19Tokens = 1_943_000L

    /** Past the 80 % threshold but still inside the window: the state to fire in. */
    private val nearLimitTokens = 900_000L

    // ------------------------------------------------------ upstream's ceiling

    @Test
    fun upstreamCeilingIsTheServersOwnTrigger() {
        // `context - max(min(output, 32_000), 20_000)` for a 1M window whose
        // advertised output is unknown.
        assertEquals(1_016_576L, ContextCompactionPolicy.upstreamCeiling(limit))
        // An advertised output at or above the cap is clamped to it.
        assertEquals(1_016_576L, ContextCompactionPolicy.upstreamCeiling(limit, limitOutput = 64_000))
        // A smaller advertised output is dominated by the 20_000 buffer.
        assertEquals(1_028_576L, ContextCompactionPolicy.upstreamCeiling(limit, limitOutput = 8_192))
    }

    @Test
    fun upstreamCeilingHasNoAnswerForAnUnusableWindow() {
        // `context <= 0` returns `false` upstream, before the ceiling is built.
        assertNull(ContextCompactionPolicy.upstreamCeiling(null))
        assertNull(ContextCompactionPolicy.upstreamCeiling(0))
        assertNull(ContextCompactionPolicy.upstreamCeiling(-1))
    }

    @Test
    fun theClientThresholdIsStrictlyEarlierThanTheServers() {
        val ceiling = ContextCompactionPolicy.upstreamCeiling(limit)!!
        val client = (limit * ContextCompactionPolicy.DEFAULT_RATIO).toLong()
        assertTrue("client $client must sit below the server's $ceiling", client < ceiling)
        // 96.9 % vs 80 %: the client leaves a fifth of the window for the next turn.
        assertEquals(96.9, ceiling.toDouble() / limit * 100.0, 0.05)
        assertEquals(0.8, ContextCompactionPolicy.DEFAULT_RATIO, 0.0)
        // The v19 numerator is far past both of them — that is why the failure
        // arrived before either trigger had a chance to fire.
        assertTrue(v19Tokens > ceiling)
    }

    // --------------------------------------------------------- the ratio test

    @Test
    fun overThresholdNeedsBothHalves() {
        assertFalse("no usage yet", ContextCompactionPolicy.overThreshold(null, 1_000))
        assertFalse("no window", ContextCompactionPolicy.overThreshold(900L, null))
        assertFalse("zero window", ContextCompactionPolicy.overThreshold(900L, 0))
        assertFalse("negative window", ContextCompactionPolicy.overThreshold(900L, -5))
        assertFalse("negative usage", ContextCompactionPolicy.overThreshold(-1L, 1_000))
    }

    @Test
    fun overThresholdIsInclusiveAtTheRatio() {
        assertFalse(ContextCompactionPolicy.overThreshold(799L, 1_000, ratio = 0.8))
        assertTrue(ContextCompactionPolicy.overThreshold(800L, 1_000, ratio = 0.8))
        assertTrue(ContextCompactionPolicy.overThreshold(1_000L, 1_000))
        assertTrue(ContextCompactionPolicy.overThreshold(2_000L, 1_000))
    }

    // ------------------------------------------------------------- the throttle

    @Test
    fun theSameTurnIsAskedForAtMostOnce() {
        val trigger = ContextCompactionTrigger(minIntervalMs = 60_000L)
        assertTrue(trigger.shouldFire("ses_a", "msg_1", nearLimitTokens, limit, now = 0L))
        // Every later snapshot of the same turn: the numerator is re-read each
        // time, and none of those reads may re-ask.
        assertFalse(trigger.shouldFire("ses_a", "msg_1", nearLimitTokens, limit, now = 5_000L))
        assertFalse(trigger.shouldFire("ses_a", "msg_1", nearLimitTokens, limit, now = 600_000L))
    }

    @Test
    fun aNewTurnMayAskAgainOnceTheIntervalHasPassed() {
        val trigger = ContextCompactionTrigger(minIntervalMs = 60_000L)
        assertTrue(trigger.shouldFire("ses_a", "msg_1", 900_000L, limit, now = 0L))
        // Inside the interval even a *new* turn waits: this is the guard that
        // keeps a failed compaction from being retried on every snapshot.
        assertFalse(trigger.shouldFire("ses_a", "msg_2", 950_000L, limit, now = 30_000L))
        // Past it, the new turn asks.
        assertTrue(trigger.shouldFire("ses_a", "msg_2", 950_000L, limit, now = 60_000L))
    }

    @Test
    fun theKeyCarriesTheSessionSoASwitchNeedsNoReset() {
        val trigger = ContextCompactionTrigger(minIntervalMs = 60_000L)
        assertTrue(trigger.shouldFire("ses_a", "msg_1", 900_000L, limit, now = 0L))
        // The same turn id under another session is a different attempt — the
        // interval, not the key, is what bounds the burst.
        assertFalse(trigger.shouldFire("ses_b", "msg_1", 900_000L, limit, now = 1_000L))
        assertTrue(trigger.shouldFire("ses_b", "msg_1", 900_000L, limit, now = 61_000L))
    }

    @Test
    fun belowTheThresholdNothingFires() {
        val trigger = ContextCompactionTrigger()
        // 80 % of 1,048,576 is 838,860.8: just under asks nothing, just over asks.
        assertFalse(trigger.shouldFire("ses_a", "msg_1", 838_860L, limit, now = 0L))
        assertTrue(trigger.shouldFire("ses_a", "msg_1", 838_861L, limit, now = 0L))
    }

    @Test
    fun anUnknownHalfOrAnUnknownTurnNeverFires() {
        val trigger = ContextCompactionTrigger()
        assertFalse(trigger.shouldFire("ses_a", "msg_1", null, limit, now = 0L))
        assertFalse(trigger.shouldFire("ses_a", "msg_1", 900_000L, null, now = 0L))
        assertFalse(trigger.shouldFire(null, "msg_1", 900_000L, limit, now = 0L))
        assertFalse(trigger.shouldFire("ses_a", null, 900_000L, limit, now = 0L))
        assertFalse(trigger.shouldFire("ses_a", "  ", 900_000L, limit, now = 0L))
    }

    // ------------------------------------------------------------ the headroom

    @Test
    fun hasHeadroomStopsAtTheWindow() {
        assertTrue(ContextCompactionPolicy.hasHeadroom(0L, 1_000))
        assertTrue(ContextCompactionPolicy.hasHeadroom(999L, 1_000))
        assertFalse(ContextCompactionPolicy.hasHeadroom(1_000L, 1_000))
        assertFalse(ContextCompactionPolicy.hasHeadroom(1_001L, 1_000))
        assertFalse("no usage", ContextCompactionPolicy.hasHeadroom(null, 1_000))
        assertFalse("no window", ContextCompactionPolicy.hasHeadroom(1L, null))
        assertFalse("zero window", ContextCompactionPolicy.hasHeadroom(1L, 0))
    }

    @Test
    fun atOrPastTheLimitNothingIsAskedAutomatically() {
        // The state the v19 session was in: a compaction would summarise the same
        // over-long history and be rejected the same way (the manual
        // `POST …/compact` of the evidence wrote its row and changed no tokens).
        // That state belongs to the error card's recovery rows, not to the
        // automatic trigger — so the trigger stays silent and adds no doomed
        // compaction row to a session that is already stuck.
        val trigger = ContextCompactionTrigger()
        assertFalse(trigger.shouldFire("ses_a", "msg_1", limit.toLong(), limit, now = 0L))
        assertFalse(trigger.shouldFire("ses_a", "msg_1", v19Tokens, limit, now = 0L))
        assertFalse(trigger.shouldFire("ses_a", "msg_2", v19Tokens, limit, now = 600_000L))
    }

    // -------------------------------------------------------- when it may fire

    @Test
    fun onlyARunningTurnPaysForAnAutomaticCompaction() {
        // Upstream compacts inside the drain; here the boundary is also a cost
        // boundary — the summary request carries the history it summarises, so
        // merely opening a session at 85 % must not spend it.
        assertTrue(ContextCompactionPolicy.isAdvancing(ChatStore.RunStatus.RUNNING))
        assertFalse(ContextCompactionPolicy.isAdvancing(ChatStore.RunStatus.IDLE))
        assertFalse(ContextCompactionPolicy.isAdvancing(ChatStore.RunStatus.ERROR))
    }

    @Test
    fun resetReArmsTheThrottle() {
        val trigger = ContextCompactionTrigger(minIntervalMs = 60_000L)
        assertTrue(trigger.shouldFire("ses_a", "msg_1", 900_000L, limit, now = 0L))
        assertFalse(trigger.shouldFire("ses_a", "msg_1", 900_000L, limit, now = 1_000L))
        trigger.reset()
        assertTrue(trigger.shouldFire("ses_a", "msg_1", 900_000L, limit, now = 1_000L))
    }
}
