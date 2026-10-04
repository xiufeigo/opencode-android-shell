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

    /** 代理的 SSE 读超时（`LoopbackProxy.SSE_IDLE_READ_TIMEOUT_MS`，internal ⇒ 同模块可见）。 */
    private val SSE_IDLE_READ_TIMEOUT_MS = LoopbackProxy.SSE_IDLE_READ_TIMEOUT_MS

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
    fun messagePostIsRecognisedAsRoundStart() {
        val t = RoundActivityTracker()
        assertTrue(
            "POST 会话消息 = 回合开始",
            t.isRoundStartRequest("POST", "/session/ses_abc/message"),
        )
        assertTrue(
            "带 /api 前缀也要认",
            t.isRoundStartRequest("POST", "/api/session/ses_abc/message"),
        )
        assertTrue(
            "message_async 也要认",
            t.isRoundStartRequest("POST", "/api/session/ses_abc/message_async"),
        )
    }

    @Test
    fun nonMessageRequestsAreNotRoundStart() {
        val t = RoundActivityTracker()
        // 这些都会出现在真实流量里，认错就会"打开页面就常亮"。
        assertFalse("GET 会话列表不是回合", t.isRoundStartRequest("GET", "/api/session"))
        assertFalse("GET 事件流不是回合", t.isRoundStartRequest("GET", "/api/event"))
        assertFalse("GET 消息列表不是回合", t.isRoundStartRequest("GET", "/api/session/ses_abc/message"))
        assertFalse("改会话名不是回合", t.isRoundStartRequest("PATCH", "/api/session/ses_abc"))
        assertFalse("压缩不是回合", t.isRoundStartRequest("POST", "/api/session/ses_abc/compact"))
        assertFalse("空路径不是回合", t.isRoundStartRequest("POST", "/"))
    }

    @Test
    fun queryStringDoesNotBreakPathMatch() {
        val t = RoundActivityTracker()
        assertTrue("带 query 的消息请求也要认", t.isRoundStartRequest("POST", "/api/session/ses_abc/message?x=1"))
    }

    // ---- 回合结束 ⇒ 释放 -------------------------------------------------

    @Test
    fun roundEndClearsKeepScreenOn() {
        val t = RoundActivityTracker()
        t.onRoundStart(startAt)
        assertTrue("先置起", shouldBeSet(t, startAt))
        t.onRoundEnd()
        assertFalse("回合结束必须释放 KEEP_SCREEN_ON", shouldBeSet(t, startAt))
    }

    @Test
    fun streamEndAfterLongSilenceClearsKeepScreenOn() {
        // 回合"自然结束"：流式通道静默超过一个 SSE 读超时后关闭。
        // 这里**照抄代理 copyStreaming 末尾的判定**（见 LoopbackProxy）：
        //   静默 >= SSE_IDLE_READ_TIMEOUT_MS ⇒ 调 onRoundEnd()。
        // 跟踪器本身不会"自己"在 35s 释放 —— 释放来自这个显式结束信号，
        // 漏了这个信号由 [holdTimeoutReleasesStaleRound] 的 90s 兜底接管。
        val t = RoundActivityTracker()
        t.onRoundStart(startAt)
        t.onRoundActivity(startAt + 1_000L)
        val shortSilence = startAt + 10_000L
        assertTrue(
            "短暂静默不该释放：页面 EventSource 正在按退避重连",
            shouldBeSet(t, shortSilence) && t.idleMs(shortSilence) < SSE_IDLE_READ_TIMEOUT_MS,
        )
        val longSilence = startAt + 36_000L
        assertTrue(
            "静默 36s 已越过 35s 阈值 ⇒ 代理会调 onRoundEnd",
            t.idleMs(longSilence) >= SSE_IDLE_READ_TIMEOUT_MS,
        )
        t.onRoundEnd()
        assertFalse("回合自然结束后必须释放 KEEP_SCREEN_ON", shouldBeSet(t, longSilence))
    }

    // ---- 兜底超时：忘关路径 ---------------------------------------------

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
        // 每 10 s 一次心跳（上游 `/event` 的真实节奏），持续 5 分钟。
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
    fun idleEventStreamAloneNeverStartsARound() {
        // 页面随手开个 GET /event 订阅（上游每 10 s 心跳）**不是回合**。
        // 若把它当回合，常亮会被一个纯订阅永久吊住。
        val t = RoundActivityTracker()
        var now = startAt
        while (now < startAt + 600_000L) {
            t.onRoundActivity(now)
            now += 10_000L
        }
        assertFalse("只有心跳、没有回合开始信号 ⇒ 不得置起", shouldBeSet(t, now))
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
    fun persistentEventStreamCannotKeepFlagOnForever() {
        // ⚠️ 设备实测逼出来的用例：官方 UI **常驻**一条 `GET /event` 订阅，
        // 其 10 s 心跳经代理的 onRoundActivity 持续续命 ⇒ 只靠 90 s 空闲超时
        // 的话，flag 会**永久**挂着（设备原文：t=+91s…t=+131s 一直 hits=1）。
        // 硬上限 [maxRoundKeepMs] 就是为这道兜底而存在。
        val t = RoundActivityTracker()
        t.onRoundStart(startAt)
        val ceiling = t.maxRoundKeepMs
        // 心跳一直来：60 min 内每一秒都有活动。
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
            "超过 ${ceiling}ms 的硬上限 ⇒ 必须释放，无论心跳多活跃",
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
        // ⚠️ 必须**持续**喂活动（每 10 s 一次心跳），不是只喂一次 ——
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
    fun applyKeepScreenOnBitRoundTripIsConsistentWithTracker() {
        // 端到端对齐：跟踪器的判定结果作用到 flag 位上，必须与 shouldBeSet 一致。
        val t = RoundActivityTracker()
        val base = 0x00000040 // 无关位
        assertEquals(
            "回合结束后不得留下 KEEP_SCREEN_ON 位",
            base,
            PowerPolicy.applyKeepScreenOn(base, foreground = true, roundActive = t.isRoundActive(startAt)),
        )
        t.onRoundStart(startAt)
        assertTrue(
            "回合进行中应带上 KEEP_SCREEN_ON 位",
            PowerPolicy.hasKeepScreenOn(
                PowerPolicy.applyKeepScreenOn(base, foreground = true, roundActive = t.isRoundActive(startAt)),
            ),
        )
        assertEquals(
            "回合结束后该位必须被清掉",
            base,
            PowerPolicy.applyKeepScreenOn(
                PowerPolicy.applyKeepScreenOn(base, foreground = true, roundActive = true),
                foreground = true,
                roundActive = t.isRoundActive(startAt + timeout + 1L),
            ),
        )
    }
}
