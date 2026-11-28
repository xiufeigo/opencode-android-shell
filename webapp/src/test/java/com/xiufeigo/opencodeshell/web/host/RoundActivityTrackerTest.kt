package com.xiufeigo.opencodeshell.web.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D1 · **回合活跃跟踪器**的单测。
 *
 * 断言的可观测量就是任务书要求的那两条：
 *  - **回合进行中应置起** ⇒ `PowerPolicy.shouldKeepScreenOn(foreground=true, roundActive=true) == true`
 *  - **回合结束后应释放** ⇒ 同一个 flag 在回合结束后回到 false
 *
 * 本类不 import 任何 `android.*` 符号，时间用假时钟注入 ⇒ 可以在 JVM 上
 * 穷举"90 s 之内 / 90 s 之后"这类真实设备上要等 90 秒的时序。
 */
class RoundActivityTrackerTest {

    private val timeout = RoundActivityTracker.DEFAULT_ROUND_HOLD_TIMEOUT_MS
    private val startAt = 1_000_000L

    /** 当前 flag 该不该置起（= 宿主实际落 flag 的判据）。 */
    private fun shouldBeSet(t: RoundActivityTracker, now: Long, foreground: Boolean = true): Boolean =
        PowerPolicy.shouldKeepScreenOn(
            foreground = foreground,
            roundActive = t.isRoundActive(now),
        )

    // ---- 回合开始 ⇒ 置起 -------------------------------------------------

    @Test
    fun roundStartSetsKeepScreenOnInForeground() {
        val t = RoundActivityTracker()
        assertFalse("起点不该常亮", shouldBeSet(t, startAt))
        t.onRoundStart(startAt)
        assertTrue("回合进行中且在前台 ⇒ 必须置起 KEEP_SCREEN_ON", shouldBeSet(t, startAt))
    }

    @Test
    fun roundStartWhileBackgroundedDoesNotSetFlag() {
        // 漏斗的第一级：回合在进行，但已切后台 ⇒ 不许常亮（屏幕该灭）。
        val t = RoundActivityTracker()
        t.onRoundStart(startAt)
        assertFalse("后台时即便回合进行中也不该常亮", shouldBeSet(t, startAt, foreground = false))
    }

    @Test
    fun roundEndClearsKeepScreenOn() {
        val t = RoundActivityTracker()
        t.onRoundStart(startAt)
        assertTrue("先置起", shouldBeSet(t, startAt))
        t.onRoundEnd()
        assertFalse("回合结束必须释放 KEEP_SCREEN_ON", shouldBeSet(t, startAt))
    }

    @Test
    fun holdTimeoutReleasesStaleRound() {
        // 回合中途崩溃/渲染进程被杀 ⇒ 既没有结束事件，也可能一直留在前台。
        // 这正是 T1 §7.3 的"忘关路径"，必须由超时兜底释放。
        val t = RoundActivityTracker()
        t.onRoundStart(startAt)
        assertTrue("刚开始还常亮", shouldBeSet(t, startAt + timeout - 1L))
        assertFalse(
            "超过 ${timeout}ms 没有任何回合活动 ⇒ 必须释放",
            shouldBeSet(t, startAt + timeout),
        )
    }

    @Test
    fun streamActivityKeepsAliveRoundFromTimingOut() {
        val t = RoundActivityTracker()
        t.onRoundStart(startAt)
        // 调用方已分类为"回合活动"的信号持续到来，持续 5 分钟。
        var now = startAt
        while (now < startAt + 300_000L) {
            t.onRoundActivity(now)
            now += 10_000L
        }
        assertTrue(
            "持续有流式活动的回合不该被兜底超时判死",
            shouldBeSet(t, startAt + 300_000L),
        )
    }

    @Test
    fun activityWithoutRoundStartNeverStartsARound() {
        // 没有回合开始信号时，即使用调用方误把后台活动喂进来，也不能凭空开回合。
        val t = RoundActivityTracker()
        var now = startAt
        while (now < startAt + 600_000L) {
            t.onRoundActivity(now)
            now += 10_000L
        }
        assertFalse("没有回合开始信号 ⇒ 不得置起", shouldBeSet(t, now))
    }

    @Test
    fun roundEndIsIdempotent() {
        val t = RoundActivityTracker()
        t.onRoundStart(startAt)
        assertTrue("第一次 onRoundEnd 报告发生了变化", t.onRoundEnd())
        assertFalse("已经没有回合了，再调不应再报变化", t.onRoundEnd())
        assertFalse("没有回合时 flag 保持清除", shouldBeSet(t, startAt))
    }

    @Test
    fun secondRoundStartAfterEndWorksAgain() {
        // 回合 1 结束、回合 2 开始：功能必须能反复用，不能一次性。
        val t = RoundActivityTracker()
        t.onRoundStart(startAt)
        t.onRoundEnd()
        val secondAt = startAt + 60_000L
        assertTrue("第二个回合开始应再次报告变化", t.onRoundStart(secondAt))
        assertTrue("第二个回合应再次置起", shouldBeSet(t, secondAt))
        t.onRoundEnd()
        assertFalse("第二个回合结束应再次释放", shouldBeSet(t, secondAt))
    }

    // ---- 诊断读数 --------------------------------------------------------

    @Test
    fun diagnosticsReportAgeAndIdle() {
        val t = RoundActivityTracker()
        assertEquals("无回合时 age = 0", 0L, t.roundAgeMs(startAt))
        assertEquals("无回合时 idle = 0", 0L, t.idleMs(startAt))
        t.onRoundStart(startAt)
        t.onRoundActivity(startAt + 2_000L)
        assertEquals("age 从回合开始算", 2_000L, t.roundAgeMs(startAt + 2_000L))
        assertEquals("idle 从最近一次活动算", 0L, t.idleMs(startAt + 2_000L))
        assertEquals("idle 随时间增长", 5_000L, t.idleMs(startAt + 7_000L))
    }

    @Test
    fun persistentRoundActivityCannotKeepFlagOnForever() {
        // 即使调用方持续喂入回合活动，单靠 90 s 空闲兜底也可能永久续命；
        // 硬上限 [maxRoundKeepMs] 就是为这道兜底而存在。
        val t = RoundActivityTracker()
        t.onRoundStart(startAt)
        val ceiling = t.maxRoundKeepMs
        // 活动一直来：60 min 内每一秒都有活动。
        var now = startAt
        while (now < startAt + 60 * 60_000L) {
            t.onRoundActivity(now)
            now += 10_000L
        }
        assertTrue(
            "上限之前仍应常亮（正常长回合不能被腰斩）",
            shouldBeSet(t, startAt + ceiling - 1L),
        )
        assertFalse(
            "超过 ${ceiling}ms 的硬上限 ⇒ 必须释放，无论活动多频繁",
            shouldBeSet(t, startAt + ceiling),
        )
    }

    @Test
    fun ceilingResetAfterRoundEnd() {
        // 上限用完就结算成"无回合"，下一个回合必须能**重新拿到完整额度**，
        // 不能停留在"额度已耗尽"的错位态（那会让后续回合一律不常亮）。
        //
        // 注意断言的是"重新拿到**完整的两层额度**"，即：
        //  - 空闲层：新的回合从此刻起又有完整 90 s（下面用 60 s 验证）；
        //  - 上限层：有活动时又能一直续到新的 30 min 天花板（下面验证）。
        val t = RoundActivityTracker()
        t.onRoundStart(startAt)
        val ceiling = t.maxRoundKeepMs
        assertFalse("第一回合到上限即结束", shouldBeSet(t, startAt + ceiling))

        val secondAt = startAt + ceiling + 1_000L
        assertTrue("第二回合应重新可用（额度未被永久耗尽）", t.onRoundStart(secondAt))
        assertTrue(
            "第二回合应重新获得完整的 90s 空闲额度",
            shouldBeSet(t, secondAt + 60_000L),
        )
        // 有活动时，续命可以一直到**新的**天花板，而不是沿用旧回合的余额。
        // ⚠️ 必须**持续**喂活动，不是只喂一次 ——
        // 90 s 空闲层是独立生效的：只喂一次的话 90 s 后照样判死。
        var t2 = secondAt + 80_000L
        while (t2 < secondAt + ceiling) { t2 += 10_000L; t.onRoundActivity(t2) }
        assertTrue(
            "第二回合持续有活动时仍可续命到接近新的上限",
            shouldBeSet(t, secondAt + ceiling - 1L),
        )
        assertFalse(
            "第二回合自己的上限同样会到期",
            shouldBeSet(t, secondAt + ceiling),
        )
    }

    @Test
    fun keepScreenOnDecisionRoundTripIsConsistentWithTracker() {
        val t = RoundActivityTracker()
        assertFalse(
            "没有回合时不得允许常亮",
            PowerPolicy.shouldKeepScreenOn(foreground = true, roundActive = t.isRoundActive(startAt)),
        )
        t.onRoundStart(startAt)
        assertTrue(
            "回合进行中应允许常亮",
            PowerPolicy.shouldKeepScreenOn(foreground = true, roundActive = t.isRoundActive(startAt)),
        )
        assertTrue(
            "回合空闲超时后必须要求清掉常亮",
            PowerPolicy.mustClearKeepScreenOn(foreground = true, roundActive = t.isRoundActive(startAt + timeout + 1L)),
        )
    }
}
