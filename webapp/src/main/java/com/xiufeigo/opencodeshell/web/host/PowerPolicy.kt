package com.xiufeigo.opencodeshell.web.host

/**
 * P6 · 省电策略：**纯 Kotlin 决策层**（本文件**不 import 任何 android.\* 符号**）。
 *
 * ## 为什么要单独抽一层
 *
 * `WebHostActivity` / `WebHostBridge` 都会调 `android.util.Log`，
 * 而 `webapp` 模块**没有**开 `unitTests.isReturnDefaultValues`（`app` 模块开了，见
 * `app/build.gradle.kts:64-65`；本轮**无权**改 `webapp/build.gradle.kts`）。
 * 于是任何在 JVM 单测里构造这两个类的用例都会在 `Log.i` 上抛
 * `RuntimeException: Method i in android.util.Log not mocked`。
 *
 * 所以：把**决策**全放进这个不依赖 android 的对象，宿主只做**接线**。
 * 单测直接测决策（真行为断言），另有 `PowerWiringTest` 做源码接线断言。
 *
 * ## 依据
 *
 * `research/perf/BATTERY_HOWTO.md` §7.2 / §7.3（只读盘点，带行号）：
 *  - §7.2 头号风险（**历史形态**）：回环代理常驻 + 只在 `onDestroy` 关闭 ⇒
 *    退到后台后 SSE 长连接继续唤醒射频。UX6 已把代理整个删除；**UX8 起宿主在
 *    onPause 合成派发 pagehide、onResume 派发 pageshow**，驱动上游 client 的
 *    pageLifecycle 机制（_upstream packages/client/src/solid/connection.ts:219-240）
 *    主动 abort 事件流并在回前台重连 —— 后台不再常挂长连接。
 *  - §7.3：`WebHostBridge` 的 `setKeepScreenOn` 曾长期是**死代码**（无调用者），
 *    但 `onPause` **没有任何防御性 `clearFlags`** ⇒ 一旦页面接上调用，回合中途
 *    出错 / 被杀 / 切走就成"忘关路径"。`onPause` 无条件清 flag 的接线保留至今。
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
         * UX6：回环代理已删，本枚举暂无生产消费者（保留为策略声明；若未来
         * 宿主重新介入流量，这条语义即生效）。历史理由：页面 `EventSource`
         * 的自动重连是既有语义，复用"可重试失败"= 不引入新行为类别，回前台时
         * 页面自然重连并重新同步。
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
