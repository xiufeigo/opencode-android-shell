package com.xiufeigo.opencodeshell.web.host

import android.util.Log
import android.view.Window
import android.view.WindowManager
import android.webkit.WebView

/**
 * Android window-flag owner for round-scoped `KEEP_SCREEN_ON`.
 *
 * The page bridge and lifecycle host supply only facts (`roundActive`,
 * foreground, and the shared [RoundActivityTracker]). This module owns the
 * effectful part: UI-thread reconciliation, idempotent flag writes, the delayed
 * backstop that settles an otherwise passive timeout rule, and callback cleanup
 * when the owning WebView or window lifecycle ends.
 */
class KeepScreenOnHost(
    private val window: Window,
    private val runOnUiThread: (Runnable) -> Unit,
    private val webViewProvider: () -> WebView?,
    private val foregroundProvider: () -> Boolean,
    private val roundTracker: RoundActivityTracker,
) {
    private val webView: WebView
        get() = webViewProvider() ?: error("WebView is not initialized")

    private fun webViewOrNull(): WebView? = webViewProvider()

    /** Reconcile the keep-screen-on flag with the current round state on the UI thread. */
    fun applyState() {
        val active = roundTracker.isRoundActive(System.currentTimeMillis())
        runOnUiThread(Runnable { applyKeepScreenOn(active) })
    }

    /**
     * Leaving the foreground is a hard power boundary: cancel the backstop and
     * unconditionally release the flag even if the page never sends round end.
     */
    fun onBackground() {
        // D1：撤掉挂着的兜底复查（回合已不在前台，没必要再问一次）。
        if (webViewOrNull() != null) webView.removeCallbacks(keepScreenOnBackstop)
        // P6 · 省电 · 刀 1：切后台**无条件**清常亮。
        //
        // 为什么是"无条件"而不是"只在页面发过 false 时清"：这正是 T1 §7.3 点名的
        // **忘关路径** —— 回合中途页面崩溃 / WebView 渲染进程被杀 / 用户切走而没发
        // `setKeepScreenOn(false)`，flag 会一直留到 Activity 销毁。屏幕常亮是耗电大户，
        // 所以这里把"宿主离开前台"当作**硬边界**：只要不在前台，就不许常亮。
        // 决策在 [PowerPolicy.shouldKeepScreenOn]（纯 Kotlin、可单测）。
        clearKeepScreenOnOnBackground()
    }

    /** Restore the flag if a round is still live when the host returns to the foreground. */
    fun onForeground() {
        // D1：回合若仍在进行，回前台要**把常亮补回来**（否则回合中途切走再回来，
        // 回合还在跑屏幕却不再常亮）。回合已结束则这里等价于一次幂等 clearFlags。
        val active = roundTracker.isRoundActive(System.currentTimeMillis())
        applyKeepScreenOn(active)
    }

    /**
     * P6 · 兜底清常亮。
     *
     * 用 `clearFlags` 而不是 `addFlags(x and x.inv())`：直接 `clearFlags` 不依赖
     * "当前值是什么"，因此**幂等**、且天然清掉别人（或历史代码）设的同一 flag。
     */
    private fun clearKeepScreenOnOnBackground() {
        runCatching { window.clearFlags(PowerPolicy.FLAG_KEEP_SCREEN_ON_BIT) }
    }

    /**
     * D1 · **按 [PowerPolicy] 的结论真正置位/清除常亮 flag**。
     *
     * 这是"回合进行中保持屏幕常亮"**唯一**的 flag 写入口。UX6 起驱动方只剩
     * 页面桥；UX8 起注入层回合传感器（`oc-host.js installRoundPowerSensor`，
     * 观测发消息 POST 与 /api/event 的 session.execution.* 帧）经
     * `WebHostBridge.setKeepScreenOn` 喂给 [roundTracker] ⇒ 功能恢复生效。
     *
     * @param roundActive 当前是否有回合在进行
     */
    private fun applyKeepScreenOn(roundActive: Boolean) {
        val should = PowerPolicy.shouldKeepScreenOn(foreground = foregroundProvider(), roundActive = roundActive)
        val next = if (should) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            true
        } else {
            window.clearFlags(PowerPolicy.FLAG_KEEP_SCREEN_ON_BIT)
            false
        }
        // D1：置位后**主动排一次兜底复查**。
        //
        // 为什么必须排：`RoundActivityTracker.isRoundActive` 是**被问才结算**的纯函数 ——
        // 90 s 兜底超时只是"判定规则"，**没有任何定时器会自己去问**。
        // 若不排这一下，回合中途崩溃/渲染进程被杀（既无结束事件、也没人再调桥）时
        // flag 就会**永久挂着** —— 正是 T1 §7.3 的忘关路径。
        // 实测教训：设备上只靠规则时，t=+91 s 仍然 `KEEP_SCREEN_ON hits=1`；
        // 加上这一行复查后，t=+95 s 归 0。
        if (next) scheduleKeepScreenOnBackstopCheck()
        Log.i(
            TAG,
            "D1 applyKeepScreenOn: roundActive=$roundActive foreground=${foregroundProvider()} " +
                "shouldKeep=$should flagSet=$next",
        )
    }

    /** D1：兜底复查用的 runnable（同一时刻只挂一个，重复挂会互相覆盖成"最后一次"）。 */
    private val keepScreenOnBackstop = Runnable {
        val stillActive = roundTracker.isRoundActive(System.currentTimeMillis())
        if (stillActive) {
            // 回合还活着（心跳还在续命）⇒ 重新挂一次，别让它漏掉下一次兜底。
            applyKeepScreenOn(true)
        } else {
            applyKeepScreenOn(false)
        }
    }

    private fun scheduleKeepScreenOnBackstopCheck() {
        if (webViewOrNull() == null) return
        webView.removeCallbacks(keepScreenOnBackstop)
        val now = System.currentTimeMillis()
        val tracker = roundTracker
        // 两种兜底谁先到就按谁：
        //  ① 空闲超时（回合没动静了）—— 常规路径；
        //  ② 硬上限剩余量 —— 兜底中的兜底。常驻心跳会无限续命，
        //     只靠 ① 永远不会触发。
        val holdMs = tracker.roundHoldTimeoutMs
        val remainMs = (tracker.maxRoundKeepMs - tracker.roundAgeMs(now)).coerceAtLeast(1L)
        webView.postDelayed(keepScreenOnBackstop, minOf(holdMs, remainMs))
    }

    /** Detach callbacks owned by this module before a WebView instance is destroyed. */
    fun onWebViewDisposed() {
        webViewOrNull()?.removeCallbacks(keepScreenOnBackstop)
    }

    fun onDestroy() {
        clearKeepScreenOnOnBackground()
    }

    private companion object {
        const val TAG = "OcWebHost"
    }
}
