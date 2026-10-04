package com.xiufeigo.opencodeshell.web.host

/**
 * P6 · 省电策略：**纯 Kotlin 决策层**（本文件**不 import 任何 android.\* 符号**）。
 *
 * ## 为什么要单独抽一层
 *
 * `LoopbackProxy` / `WebHostActivity` / `WebHostBridge` 三处都会调 `android.util.Log`，
 * 而 `webapp` 模块**没有**开 `unitTests.isReturnDefaultValues`（`app` 模块开了，见
 * `app/build.gradle.kts:64-65`；本轮**无权**改 `webapp/build.gradle.kts`）。
 * 于是任何在 JVM 单测里构造这三个类的用例都会在 `Log.i` 上抛
 * `RuntimeException: Method i in android.util.Log not mocked`。
 *
 * 所以：把**决策**全放进这个不依赖 android 的对象，三处宿主只做**接线**。
 * 单测直接测决策（真行为断言），另有 `PowerWiringTest` 做源码接线断言。
 *
 * ## 依据
 *
 * `research/perf/BATTERY_HOWTO.md` §7.2 / §7.3（只读盘点，带行号）：
 *  - §7.2 头号风险：`LoopbackProxy.kt:137-155` 常驻 + `WebHostActivity.kt:776`
 *    **只在 `onDestroy` 关闭** ⇒ 退到后台后 SSE 长连接（上游 `/event` 每 10 s 心跳）
 *    继续唤醒射频，**且与屏幕亮度无关**。
 *  - §7.3：`WebHostBridge.kt:156-167` 的 `setKeepScreenOn` 是**死代码**（无调用者），
 *    但 `onPause`（`WebHostActivity.kt:756-759`）**没有任何防御性 `clearFlags`**
 *    ⇒ 一旦页面接上调用，回合中途出错 / 被杀 / 切走就成"忘关路径"。
 *
 * ⚠️ 本文件只做**决策**。"决策成立"不等于"耗电下降" —— 后者只能真机量，见
 * `research/perf/POWER_ACTIONS_P6.md` 的代理量对照表。
 */
object PowerPolicy {

    /**
     * `WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON` 的位值。
     *
     * 写死数值而不是引用 `WindowManager.LayoutParams`：本文件要保持**无 android 依赖**
     * （见类注释）。该常量自 API 1 起未变（`android:keepScreenOn` 文档同源）。
     */
    const val FLAG_KEEP_SCREEN_ON_BIT: Int = 0x00000080

    /** 当前 flag 里是否还挂着常亮。 */
    fun hasKeepScreenOn(flags: Int): Boolean = (flags and FLAG_KEEP_SCREEN_ON_BIT) != 0

    /**
     * **常亮只允许在「前台 且 回合进行中」时成立。**
     *
     * 两个条件缺一不可：
     *  - `foreground = false` ⇒ Activity 已 `onPause`，屏幕该灭；常亮纯属漏电。
     *  - `roundActive = false` ⇒ 回合已结束（页面发了 `false`，或出错没发）。
     *    后者就是 T1 §7.3 点名的**忘关路径**。
     */
    fun shouldKeepScreenOn(foreground: Boolean, roundActive: Boolean): Boolean =
        foreground && roundActive

    /**
     * **兜底判据（绊线用的就是它）**：给定宿主可见状态，回合结束 / 离开前台
     * 之后**必须**清掉常亮 flag。
     */
    fun mustClearKeepScreenOn(foreground: Boolean, roundActive: Boolean): Boolean =
        !shouldKeepScreenOn(foreground, roundActive)

    /**
     * 把 [mustClearKeepScreenOn] 的结论作用到一组 flag 上（纯位运算，宿主用
     * `addFlags`/`clearFlags` 表达同一件事）。
     */
    fun applyKeepScreenOn(flags: Int, foreground: Boolean, roundActive: Boolean): Int =
        if (shouldKeepScreenOn(foreground, roundActive)) {
            flags or FLAG_KEEP_SCREEN_ON_BIT
        } else {
            flags and FLAG_KEEP_SCREEN_ON_BIT.inv()
        }

    /**
     * **后台是否允许维持流式连接（SSE）。**
     *
     * false ⇒ 宿主必须让流式连接**断开**，且**不得**回落到"照样连上游"。
     * 兜底那条是重点：若断开后页面 `EventSource` 自动重连又被放行，
     * 就变成"关一条、再连一条"的拉锯，射频照旧被唤醒，省电等于 0。
     */
    fun allowStreaming(foreground: Boolean): Boolean = foreground

    /**
     * 某条流式请求在当前状态下该被怎么处理。代理据此决定转发还是拒绝。
     */
    enum class StreamingAction {
        /** 前台：照常转发上游（保持原行为）。 */
        FORWARD,

        /**
         * 后台：**不回上游**，直接给页面一个可重试的失败（`503` + `Connection: close`）。
         *
         * 为什么是 503 而不是"悄悄挂着"：页面 `EventSource` 的自动重连是既有语义 ——
         * `LoopbackProxy.kt:129-133` 的"35 s 静默判半开主动断开让页面重连"早就依赖它。
         * 复用同一条恢复路径 = 不引入新行为类别；回前台时页面自然重连并重新同步。
         * 且重连只打**回环**（127.0.0.1），不碰上游 ⇒ **射频不唤醒**。
         */
        REFUSE_WHILE_BACKGROUND,
    }

    fun streamingAction(foreground: Boolean): StreamingAction =
        if (allowStreaming(foreground)) StreamingAction.FORWARD else StreamingAction.REFUSE_WHILE_BACKGROUND

    /**
     * 后台拒绝流式请求时该回什么状态码。
     *
     * 503 = Service Unavailable，语义"服务暂时不可用、稍后重试"，正好对上
     * `EventSource` 的重连语义；**不选 200**（200 空 body 会让页面以为连上了、
     * 永远不重连 ⇒ 回前台后收不到任何事件，那才是真 bug）。
     */
    const val BACKGROUND_STREAM_REFUSE_STATUS: Int = 503
}
