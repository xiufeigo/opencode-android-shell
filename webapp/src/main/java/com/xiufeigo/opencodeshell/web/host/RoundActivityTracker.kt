package com.xiufeigo.opencodeshell.web.host

import java.util.concurrent.atomic.AtomicLong

/**
 * D1 · **回合活跃跟踪器**：把"回合进行中"这个状态从**页面**搬到**宿主**。
 *
 * ## 为什么要有这个类（= `KEEP_SCREEN_ON` 死代码的根因所在）
 *
 * 修之前的链路是（详见 `research/web/FIX_D1.md` §1）：
 * ```
 *   页面 window.__ocAndroid.setKeepScreenOn(true)
 *        └─ oc-host.js:195  setKeepScreenOn
 *             └─ WebHostBridge.kt:166  @JavascriptInterface setKeepScreenOn   ← 唯一的写 flag 处
 *                  └─ window.addFlags(FLAG_KEEP_SCREEN_ON)
 * ```
 * **这条链的唯一入口是页面。** 而我们加载的是**上游官方 opencode UI**
 * （`WebHostActivity.kt:389 webView.loadUrl(url)`，url 来自回环代理的
 * `publicOrigin()`）。上游 UI **从不调用** `setKeepScreenOn`（A4 盘点的
 * 97 个桥方法里它也在列，但没人调）⇒ `addFlags` 那行**永远执行不到** ⇒
 * "回合进行中保持屏幕常亮"这个功能**从未生效过**。
 *
 * 这不是省电策略的问题，是**功能缺失**：V4 在 `onPause` 后全机 grep
 * `KEEP_SCREEN_ON` 命中 0，以为"策略在位"，实际连置起都没发生过。
 *
 * ## 修法：让宿主自己知道回合在不在跑
 *
 * 回合的生命周期信号本来由回环代理用真实流量喂给本类（代理看得见"页面 POST
 * 了一条消息"和"流式通道静默/关闭"）。**UX6 已把代理整个删除**，直连架构下
 * 宿主看不见流量。**UX8**：宿主注入层（`oc-host.js` 的
 * `installRoundPowerSensor`）把"发消息 POST + /api/event 上的
 * `session.execution.*` 帧"重新接回本类（线上实测标记：started ⇒ 回合开始；
 * succeeded/failed/interrupted ⇒ 回合结束；真后端不发 `session.status` 帧，
 * 旧标记仅作兜底）——D1 常亮恢复生效。页面桥那条路也保留：上游 UI 将来若
 * 真的调它，行为与现在完全一致（仍受 [PowerPolicy] 的前台校验约束）。
 *
 * 页面那条路**不删**：`WebHostBridge.setKeepScreenOn` 改为喂同一个跟踪器，
 * 于是上游 UI 将来若真的调它，行为与现在完全一致（仍受 [PowerPolicy] 的
 * 前台校验约束）。
 *
 * ## 为什么要有 [ROUND_HOLD_TIMEOUT_MS] 超时
 *
 * 回合结束**不一定**有信号：回合中途崩溃、WebView 渲染进程被杀、页面被
 * 冻结 ⇒ 既没有"回合结束"事件，Activity 也可能长时间留在前台。
 * 若只靠显式结束信号，flag 就会一直挂着 —— 这正是 T1 §7.3 点名的**忘关路径**。
 * 所以再加一条兜底：**距上次回合活动超过 [ROUND_HOLD_TIMEOUT_MS] 一律视为结束**。
 * 宁可提前放开屏幕（用户再点一下），也不让常亮赖着不走。
 *
 * ## 无 android 依赖
 *
 * 与 [PowerPolicy] 同理：本类**不 import 任何 `android.*` 符号**，
 * 时间由调用方以 `nowMs` 注入（不自己读时钟）⇒ 可在 JVM 单测里
 * 用假时钟穷举，不依赖 `unitTests.isReturnDefaultValues`。
 */
class RoundActivityTracker {

    /**
     * 回合活动的**兜底超时**：距上次活动超过这么久 ⇒ 判定回合已结束。
     *
     * 取 90 s 的理由：上游 `/event` 每 10 s 一次心跳（T1 §7.2 记录），
     * 一个正在跑的回合至少每 10 s 会经代理产生一次流式字节。
     * 90 s = 9 个心跳周期的宽裕余量，足以容忍渲染进程短暂卡顿；
     * 又远小于"用户盯着不灭屏"的忍耐阈值。
     */
    val roundHoldTimeoutMs: Long = DEFAULT_ROUND_HOLD_TIMEOUT_MS

    /**
     * **单回合常亮的硬上限**（从回合开始算起），到期无条件结束。
     *
     * ## 为什么必须有它（设备实测逼出来的）
     *
     * 官方 UI 会**常驻**一条 `GET /event` 订阅（设备上实测到页面加载了
     * `_assets/eventListener-*.js`）。这条流每 10 s 一次心跳 ⇒ 代理的
     * [onRoundActivity] 被持续调用 ⇒ `lastActivityAtMs` 一直被刷新 ⇒
     * **90 s 兜底永远不会到期**。
     *
     * 实测原文：回合置起于 `19:46:32`，兜底复查在 `19:48:02`（正好 90 s 后）
     * 触发，看到 `roundActive=true` 就**又挂了一次**：
     * ```
     * 19:46:32.851  D1 applyKeepScreenOn: roundActive=true ... flagSet=true
     * 19:48:02.852  D1 applyKeepScreenOn: roundActive=true ... flagSet=true   ← 重新挂上
     * ```
     * ⇒ flag 可以**永久**挂着。
     *
     * 取 30 min 的取舍：超时的失败方向是"屏幕多灭一次"（无害，用户点一下），
     * 而无限期的失败方向是"屏幕永远亮着"（耗电大户）。**宁可多灭，不可常亮。**
     */
    val maxRoundKeepMs: Long = DEFAULT_MAX_ROUND_KEEP_MS

    /** 回合开始时刻；0 = 当前没有回合。 */
    private val roundStartAtMs = AtomicLong(0L)

    /** 最近一次回合活动（开始或流式字节）；0 = 没有回合。 */
    private val lastActivityAtMs = AtomicLong(0L)

    /**
     * 宿主可见的判定结果。供 [PowerPolicy.shouldKeepScreenOn] 消费。
     *
     * **顺带把超时结算掉**（`compareAndSet` 幂等）：调用它就等于告诉跟踪器
     * "这次结算的结果可以落成 flag 了"。这样即使宿主之后不再问，
     * 状态也不会停在"已超时但仍 active"的错位态。
     */
    fun isRoundActive(nowMs: Long): Boolean {
        if (roundStartAtMs.get() == 0L) return false
        val startedAt = roundStartAtMs.get()
        // 硬上限优先于活跃续命：常驻的 /event 心跳会无限续命（见 [maxRoundKeepMs]），
        // 没有这道天花板，flag 就可能永久挂着。
        if (nowMs - startedAt >= maxRoundKeepMs) {
            lastActivityAtMs.set(0L)
            roundStartAtMs.compareAndSet(startedAt, 0L)
            return false
        }
        val idle = nowMs - lastActivityAtMs.get()
        if (idle >= roundHoldTimeoutMs) {
            // 忘了关：就地结算成"无回合"，让后续读数自洽。
            lastActivityAtMs.set(0L)
            roundStartAtMs.compareAndSet(startedAt, 0L)
            return false
        }
        return true
    }

    /**
     * 回合开始：页面 POST 了一条"发消息"请求。
     *
     * @return 状态是否**发生变化**（变了才值得通知宿主去动 flag，避免抖动刷日志）
     */
    fun onRoundStart(nowMs: Long): Boolean {
        val was = isRoundActive(nowMs)
        roundStartAtMs.set(nowMs)
        lastActivityAtMs.set(nowMs)
        return !was
    }

    /**
     * 回合进行中：代理在回合相关的流式通道上搬到了字节。
     *
     * 只有**已经开始的回合**才被续命：防止页面随手开个 `GET /event` 订阅
     * （上游那个每 10 s 一次心跳的长连接）就把常亮永久吊住 —— 那不是回合。
     */
    fun onRoundActivity(nowMs: Long): Boolean {
        if (roundStartAtMs.get() == 0L) return false
        val changed = nowMs - lastActivityAtMs.get() >= roundHoldTimeoutMs
        lastActivityAtMs.set(nowMs)
        return changed
    }

    /**
     * 回合结束：显式信号（流式通道 EOF / 静默超时 / 页面调 `setKeepScreenOn(false)`）。
     */
    fun onRoundEnd(): Boolean {
        val had = roundStartAtMs.getAndSet(0L) != 0L
        lastActivityAtMs.set(0L)
        return had
    }

    /**
     * F4 · **流上字节的三分类**（纯函数，无状态）。
     *
     * ## 为什么需要它（= "回合结束不释放"的根因）
     *
     * 修之前，[onRoundActivity] 拿到**任何**流上字节都续命，包括官方 UI 常驻那条
     * `GET /event` 的 ~10 s 心跳。于是 [idleMs] 永远 < 10 s，旧代理那条
     * "35 s 静默判半开"的释放条件**在任何流退出那一刻都不成立**，
     * 90 s 兜底 [roundHoldTimeoutMs] 同样永远不到期 ⇒ 回合正常结束后 flag 仍挂着，
     * 并被兜底每 90 s 重新挂一次（V5 设备原文：`+90/+180/+270 s` 三行 `flagSet=true`）。
     *
     * 所以必须把"心跳"和"回合自己的数据"分开：心跳**不续命**。
     *
     * ## 回合结束的权威信号来自**上游协议**，不是页面
     *
     * 页面那侧根本没有"回合结束"信号：上游官方 UI 从不调 `setKeepScreenOn`
     * （`_upstream/packages` 全量检索零命中），也没有任何 `__ocAndroid` 调用者。
     * 而回合的**全部真实流量**都经回环代理，且上游后端在 `/event` 上明确发了
     * 回合状态事件（`_upstream/packages/ai/node_modules/@opencode/schema/src/session-status-event.ts:8-13`
     * `type: "idle"` / `:27-32` `type: "busy"`；`:43-49` 旧的 `session.idle` 事件）。
     * ⇒ 宿主在**字节流**上认这两处标记即可精确收口，不必等 35 s 静默。
     */
    enum class RoundStreamSignal {
        /** 流上出现了上游的"回合结束"标记（`session.status`→`idle` / `session.idle`）。 */
        ROUND_END,

        /** 回合自己的数据帧（有 `"type"` 的事件负载）⇒ 续命。 */
        ACTIVITY,

        /** 纯心跳 / 空负载 ⇒ **不续命**（这是"忘关路径"的正解）。 */
        HEARTBEAT,
    }

    /**
     * 纯逻辑分类器：给一段 SSE 文本定级。**可在 JVM 单测里直接穷举**（无 android 依赖）。
     *
     * @param text 扫描窗口内的文本（代理里是"上一次的尾巴 + 本次新到的字节"，
     *   以免标记被读切成两半）。
     */
    fun classifyRoundStream(text: String): RoundStreamSignal = when {
        text.isEmpty() -> RoundStreamSignal.HEARTBEAT
        containsRoundEndMarker(text) -> RoundStreamSignal.ROUND_END
        containsSubstantivePayload(text) -> RoundStreamSignal.ACTIVITY
        else -> RoundStreamSignal.HEARTBEAT
    }

    /**
     * 把一个 [RoundStreamSignal] 作用到跟踪器上。@return 回合状态是否**变化**。
     *
     * 只有 [RoundStreamSignal.HEARTBEAT] 是"什么也不做"—— 这正是修复点。
     */
    fun onRoundStreamSignal(nowMs: Long, signal: RoundStreamSignal): Boolean = when (signal) {
        RoundStreamSignal.ROUND_END -> onRoundEnd()
        RoundStreamSignal.ACTIVITY -> onRoundActivity(nowMs)
        RoundStreamSignal.HEARTBEAT -> false
    }

    /** 上游是否明确发了"这个会话不忙了"= 回合结束。 */
    fun containsRoundEndMarker(text: String): Boolean {
        if (text.contains(ROUND_END_LEGACY_MARKER)) return true
        if (!text.contains(ROUND_STATUS_MARKER)) return false
        // `session.status` 帧里 `status.type` 才是判据：idle 收口，busy/retry 继续。
        return text.contains(STATUS_IDLE_COMPACT) || text.contains(STATUS_IDLE_SPACED)
    }

    /** 帧里是否带得上游事件的类型标记（= 真的业务负载，而不是心跳）。 */
    fun containsSubstantivePayload(text: String): Boolean =
        text.contains(SUBSTANTIVE_MARKER)

    /** 诊断读数：回合已持续多久（无回合 = 0）。 */
    fun roundAgeMs(nowMs: Long): Long =
        if (roundStartAtMs.get() == 0L) 0L else nowMs - roundStartAtMs.get()

    /** 诊断读数：距上次回合活动多久（无回合 = 0）。 */
    fun idleMs(nowMs: Long): Long =
        if (roundStartAtMs.get() == 0L) 0L else nowMs - lastActivityAtMs.get()

    /**
     * 这条请求是不是"发消息"（= 回合开始）。
     *
     * 判据 = **POST** 且路径落在会话消息端点上。路径按后缀匹配，容忍
     * `/api` 前缀与未来的版本前缀（`/api/session/{id}/message`、
     * `/session/{id}/message_async` 都算）。
     */
    fun isRoundStartRequest(method: String?, path: String?): Boolean {
        if (!method.equals("POST", ignoreCase = true)) return false
        val p = path?.substringBefore('?')?.trimEnd('/') ?: return false
        if (!p.contains("/session/")) return false
        return p.endsWith(MESSAGE_SUFFIX) || p.endsWith(MESSAGE_ASYNC_SUFFIX) ||
            p.endsWith(PROMPT_SUFFIX)
    }

    companion object {
        const val DEFAULT_ROUND_HOLD_TIMEOUT_MS: Long = 90_000L

        /**
         * 单回合常亮的硬上限：30 min。
         *
         * 为什么不是"无限"：官方 UI 常驻一条 `GET /event` 订阅，其 10 s 心跳会
         * 无限续命（见 [maxRoundKeepMs] 注释里的设备实测原文）⇒ 没有天花板，
         * flag 可永久挂着。宁可 30 min 后让屏幕自己灭一次（用户点一下即可），
         * 也不要"屏幕永远亮着"。
         */
        const val DEFAULT_MAX_ROUND_KEEP_MS: Long = 30 * 60_000L

        private const val MESSAGE_SUFFIX = "/message"
        private const val MESSAGE_ASYNC_SUFFIX = "/message_async"
        private const val PROMPT_SUFFIX = "/prompt"

        /**
         * F4 · 回合结束标记（依据：`_upstream/.../@opencode/schema/src/session-status-event.ts:8-13`
         * `type: "idle"`、`:27-32` `type: "busy"`、`:43-49` 已废弃的 `session.idle` 事件）。
         */

        /** 上游已废弃但仍在发的回合结束事件名。 */
        const val ROUND_END_LEGACY_MARKER: String = "\"session.idle\""

        /** 现行回合状态事件名；它本身**不代表**结束，还要看 `status.type`。 */
        const val ROUND_STATUS_MARKER: String = "\"session.status\""

        /** `{"type":"idle"}` 紧凑写法。 */
        const val STATUS_IDLE_COMPACT: String = "\"type\":\"idle\""

        /** `{"type": "idle"}` 带空格写法（上游序列化也可能带空格）。 */
        const val STATUS_IDLE_SPACED: String = "\"type\": \"idle\""

        /** 任何上游事件负载都带 `"type"`；心跳 / 空帧不带。 */
        const val SUBSTANTIVE_MARKER: String = "\"type\""
    }
}
