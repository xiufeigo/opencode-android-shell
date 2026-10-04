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
 * 回合的生命周期**本来就在宿主手里** —— 回环代理是所有页面流量必经的中枢，
 * 它看得见"页面 POST 了一条消息"（回合开始）和"流式通道静默/关闭"
 * （回合结束）。所以本类把回合活跃判定做成**纯 Kotlin 状态机**，
 * 由 [LoopbackProxy] 用真实流量喂它，宿主据此置位/清除 flag。
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
    }
}
