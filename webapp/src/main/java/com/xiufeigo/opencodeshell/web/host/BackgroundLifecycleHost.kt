package com.xiufeigo.opencodeshell.web.host

import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity

/**
 * Background lifecycle and round-power orchestration for the Web shell.
 *
 * Owns foreground truth, round activity tracking, keep-screen-on flag writes,
 * synthetic page lifecycle events, and the background SSE keepalive/release
 * state machine. The Activity forwards lifecycle events; this module owns the
 * policy and state transitions.
 */
class BackgroundLifecycleHost(
    private val activity: AppCompatActivity,
    private val webViewProvider: () -> WebView?,
    private val evaluateJs: (String) -> Unit,
    private val refreshInsets: () -> Unit,
) {
    private val webView: WebView
        get() = webViewProvider() ?: error("WebView is not initialized")

    private fun webViewOrNull(): WebView? = webViewProvider()

    /**
     * D1：回合活跃跟踪器。UX6 起**唯一的驱动方是页面桥**（`setKeepScreenOn`）——
     * 回环代理已删，宿主看不见流量，无法再按真实回合流驱动（这是本轮如实记录的
     * 能力回退，见报告 §1.2）。
     */
    val roundTracker = RoundActivityTracker()

    /**
     * P6：宿主是否在前台。
     *
     * ⚠️ 为什么不用 `lifecycle.currentState.isAtLeast(STARTED)`：[WebHostBridge] 的
     * `@JavascriptInterface` 方法跑在** WebView 的 JS 桥线程**上，不是主线程。
     * 读 `lifecycleScope`/lifecycle registry 在跨线程读时是可见的，但 Activity
     * 已 `destroy` 时访问会抛；这里用一个 `@Volatile` 布尔，语义更窄也更安全。
     */
    @Volatile
    private var foreground: Boolean = false

    /**
     * P7 · 当前 `onPause` 是否走了「回合保活路径」。
     *
     * true = 切后台时有回合在进行 ⇒ **没派 pagehide**（SSE 仍活着）；
     * false = 走了空闲路径 ⇒ 已派 pagehide 断流，回前台需要 pageshow 重建。
     * 由 `onPause` 写、`onResume` 与 [backgroundRoundRelease] 读。
     */
    @Volatile
    private var backgroundKeepAlive: Boolean = false

    /**
     * P7 · 后台回合保活的收口轮询间隔（ms）。
     *
     * 取 2s：只在后台保活路径上排一个 runnable，作用仅是问一次
     * `isRoundActive`。回合结束的正常路径是**事件驱动**（传感器读到终态帧即
     * 毫秒级收口），这条轮询只是"传感器也失联了"的兜底。
     */
    private val BACKGROUND_ROUND_RELEASE_POLL_MS: Long = 2_000L


    fun isForeground(): Boolean = foreground

    /** Reconcile the keep-screen-on flag with the current round state on the UI thread. */
    fun applyKeepScreenOnState() {
        val active = roundTracker.isRoundActive(System.currentTimeMillis())
        activity.runOnUiThread { applyKeepScreenOn(active) }
    }

    fun onPause() {
        // P6：先落状态再清 flag。顺序有意为之 —— 让此刻之后任何 `setKeepScreenOn(true)`
        // 的桥调用都立刻被 [isForeground] 拒掉（哪怕 `clearFlags` 还没跑到主线程）。
        foreground = false
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
        // P6 · 刀 2（UX8 恢复）：切后台**主动断开 SSE**。
        //
        // 复用上游既有机制：client connection 的 pageLifecycle（_upstream
        // packages/client/src/solid/connection.ts:219-240）是
        //   pagehide → stop()（:202-209，`stream?.abort()` 真正掐断 /api/event）
        //   pageshow → start()（重连）
        // 上游 App 也开着它（packages/app/src/runtime/server/client.tsx:90-93
        // `pageLifecycle: true`）。但 WebView 切后台**不发 pagehide**
        // （connection.ts:222-223 注释自己承认），机制在位、扳机缺失 ——
        // 宿主合成派发补上。波及面全 _upstream 检索过：pagehide 的其它监听者
        // 只有 layout.tsx:363 / persist.ts:20 两个 flush（落盘，无害且有益）；
        // stop()/start() 均幂等（:185-206），即便 WebView 将来原生补发也不会双连。
        //
        // 🔴 P7 · **回合感知**：这条断流不再是"无条件"的。
        //
        // P6 钉死的死锁链（`research/web/UX13_P6_NOTIFY_ROOTCAUSE.md`）：
        //   onPause → 合成 pagehide → 上游 pageLifecycle 的 pagehide 监听
        //         → w() → AbortController.abort() → 4095 的在途 SSE 被掐断
        //   ⇒ 后台期间后端发的 `session.execution.succeeded`（HOME 后 ~2s 才到）必丢
        //   ⇒ 上游 notify 闸门（只挂终态帧）从不评估 ⇒ `Notification.requestPermission()`
        //     从不触发 ⇒ 宿主 POST_NOTIFICATIONS launcher 从不弹窗 ⇒ 一条通知都没有。
        //
        // 读 bundle 得到的**关键设计事实**（本轮只读，未改 ui/ 一字节）：
        // `runtime-DVG-cmAP.js` 的 pageLifecycle 只注册四个监听器（原文见报告 §0）：
        //   pagehide        → w() = abort（**唯一**会掐断 SSE 的路径）
        //   pageshow        → C() = 重连（已连接时幂等返回）
        //   visibilitychange→ 仅在 `document.visibilityState==='visible'` 时可能触发 resync
        //   online          → resync
        // **它不监听 focus / blur。** ⇒ 后台派 blur 对上游连接状态机是**完全惰性**的：
        // 语义上"未聚焦"照常传给页面上层 UI，但 SSE 一滴不流。
        //
        // 所以回合进行中时：**只派 blur，不派 pagehide** ⇒ SSE 活着 ⇒ 终帧能到 ⇒
        // notify 闸门被评估 ⇒ 通知能投出；回合结束后由 [backgroundRoundRelease] 补派
        // pagehide 收口（省电语义不丢）。空闲后台维持原样（UX8 已验收项不变）。
        webView.removeCallbacks(backgroundRoundRelease)
        val roundActive = roundTracker.isRoundActive(System.currentTimeMillis())
        if (roundActive) {
            backgroundKeepAlive = true
            Log.i(TAG, "P7 onPause: round active -> keep SSE alive (blur only, no pagehide)")
            dispatchPageLifecycleEvent("blur")
            webView.postDelayed(backgroundRoundRelease, BACKGROUND_ROUND_RELEASE_POLL_MS)
        } else {
            backgroundKeepAlive = false
            dispatchPageLifecycleEvent("pagehide")
        }
        // P10 · 切后台 ⇒ 把页面的"可见/聚焦"**读数**改成 hidden / false。
        //
        // 🔴 P13 · **这一句必须排在 `webView.onPause()` 之前**。
        //
        // 修之前它在本方法**最后**一行，而 `webView.onPause()` 在它前面：
        // WebView 一旦被 `onPause()` 暂停，JS 线程被节流，随后这条 evaluateJavascript
        // 不会立刻执行。P13 设备实测（emulator-6790，冷启重进，ping→HOME）：
        //   12:09:49.454  P7 onPause（Android onPause 落地）
        //   12:09:52.222  页面 visibilityState 读数才第一次变成 "hidden"   ← 迟 2.768 s
        // 这 2.768 s 里页面仍向页面上层报告 `visibilityState === "visible"`，
        // 而上游的 focus 管理器（P13 在 bundle `build-CA4aPxfP.js` 实读到
        // `isFocused(){ ... : document?.visibilityState !== 'hidden' }`）
        // 正是拿 visibilityState 当**兜底真值** ⇒ 这 2.768 s 内它认为"仍在前台"，
        // notify 闸门若在此刻被评估就直接 return，通知不出。
        //
        // P13 同一次运行的实测时序（证明闸门就是在 vs 翻 hidden 之后才开的）：
        //   12:09:51.239  round end session.execution.succeeded（后台收到终帧）
        //   12:09:52.222  visibilityState → "hidden"（本条 evaluateJs 终于生效）
        //   12:09:52.232  Notification when=1791288592232（channel=oc_turns）  ← 仅迟 10 ms
        // ⇒ 终帧比"闸门可开"早了 0.98 s；本轮是**险胜**，这正是 P12 §5 偶发丢通知的宿主侧成因。
        //
        // 修法 = 只调换顺序，不改语义：读数仍只由 `__ocSetBackground` 改、仍不派任何事件
        // （派 visibilitychange 会触发上游 resync → abort，掐死保活路径的 SSE，P7 已证）。
        evaluateJs("window.__ocSetBackground && window.__ocSetBackground(true)")
        if (webViewOrNull() != null) webView.onPause()
    }

    /**
     * P7 · **后台回合保活的省电收口**：回合在后台期间结束 ⇒ 补派 `pagehide` 断 SSE。
     *
     * 为什么需要它：保活路径下 onPause 没有派 pagehide，若没有这条兜底，
     * 「回合结束后继续挂一条 4095 长连接」会一直挂到用户回前台 —— 省电语义就丢了。
     *
     * 回合结束有两条到达路径，两条都收口：
     *  1. 注入层传感器在**后台**读到 `session.execution.succeeded/failed` → 经
     *     `WebHostBridge.setKeepScreenOn(false)` → `onRoundEnd()`；
     *  2. 这条 90s 空闲兜底轮询（回合崩溃 / 渲染进程被杀 / 传感器被撤时兜底）。
     *
     * 轮询间隔 [BACKGROUND_ROUND_RELEASE_POLL_MS] 取 2s：只为了在**后台**问一次
     * `isRoundActive`，开销可忽略；真正的精度来源是路径 1（事件驱动，毫秒级）。
     */
    // ⚠ 类型必须显式写：`Runnable {}` 内部要把自己重新 post 回去（自引用），
    //   交给 Kotlin 推断会撞上 "recursive problem" 编译错。
    private val backgroundRoundRelease: Runnable = Runnable {
        if (roundTracker.isRoundActive(System.currentTimeMillis())) {
            webView.removeCallbacks(backgroundRoundRelease)
            webView.postDelayed(backgroundRoundRelease, BACKGROUND_ROUND_RELEASE_POLL_MS)
            return@Runnable
        }
        if (!backgroundKeepAlive) return@Runnable
        backgroundKeepAlive = false
        Log.i(TAG, "P7 background round ended -> dispatch pagehide (release SSE)")
        dispatchPageLifecycleEvent("pagehide")
    }

    fun onResume() {
        foreground = true
        // D1：回合若仍在进行，回前台要**把常亮补回来**（否则回合中途切走再回来，
        // 回合还在跑屏幕却不再常亮）。回合已结束则这里等价于一次幂等 clearFlags。
        val active = roundTracker.isRoundActive(System.currentTimeMillis())
        applyKeepScreenOn(active)
        if (webViewOrNull() != null) {
            webView.onResume()
            refreshInsets()
            // P6 · 刀 2（UX8 恢复）：回前台重建 SSE —— 合成 pageshow 触发上游
            // connection.ts:221 的 start()。幂等（:185-188 已 started 则直接返回）。
            //
            // P7 · **对称**：若上一次的 onPause 走的是"回合保活路径"（没断过流），
            // 这里补 `focus` 而不是 `pageshow` —— `pageshow` 的语义是"页面刚从
            // bfcache 恢复"，会触发一次 `start()`；虽然上游幂等不会双连，但在
            // 没有断过流的前提下派它属于撒谎。`focus` 才是保活路径的对偶事件，
            // 且上游 pageLifecycle **不监听 focus**（只认 pagehide/pageshow），
            // 所以它同样不会动 SSE 状态机 —— 纯粹是把"回到前台"这个事实还回页面。
            if (backgroundKeepAlive) {
                backgroundKeepAlive = false
                dispatchPageLifecycleEvent("focus")
            } else {
                dispatchPageLifecycleEvent("pageshow")
            }
            // P10 · 回前台 ⇒ 恢复 visible / true，让页面读数与真实前台状态一致。
            //
            // 与 onPause 的 `__ocSetBackground(true)` 严格对偶；同样只改读数、
            // 不派发事件。放在 `isInitialized` 判空之内（evaluateJs 自身也再兜一层）。
            evaluateJs("window.__ocSetBackground && window.__ocSetBackground(false)")
        }
    }

    /**
     * P6 · 刀 2：把页面生命周期事件**合成派发**给页面。
     *
     * 上游 client 的 pageLifecycle 只认 window 的 pagehide/pageshow
     * （connection.ts:230-231），而 Android WebView 切后台/回前台不发这两个事件
     * （只 pause/resume JS 时钟），所以机制虽有、WebView 里永远不会被触发。
     * 宿主生命周期是唯一可靠的信号源 ⇒ 在这里把事件派给页面。
     *
     * 合成事件 `isTrusted=false`；上游 handler 是不校验信任位的普通闭包
     * （connection.ts:220-221），合成事件触发它 = 执行上游自己的 stop()/start()，
     * 不是绕过上游另起一套。不携带、不触碰任何凭据（纯事件名字符串）。
     */
    private fun dispatchPageLifecycleEvent(name: String) {
        if (webViewOrNull() == null) return
        Log.i(TAG, "P6 dispatch synthetic $name (upstream pageLifecycle)")
        evaluateJs("window.dispatchEvent(new Event(\"$name\"))")
    }

    /**
     * P6 · 兜底清常亮。
     *
     * 用 `clearFlags` 而不是 `addFlags(x and x.inv())`：直接 `clearFlags` 不依赖
     * "当前值是什么"，因此**幂等**、且天然清掉别人（或历史代码）设的同一 flag。
     */
    private fun clearKeepScreenOnOnBackground() {
        runCatching { activity.window.clearFlags(PowerPolicy.FLAG_KEEP_SCREEN_ON_BIT) }
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
        val should = PowerPolicy.shouldKeepScreenOn(foreground = isForeground(), roundActive = roundActive)
        val next = if (should) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            true
        } else {
            activity.window.clearFlags(PowerPolicy.FLAG_KEEP_SCREEN_ON_BIT)
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
            "D1 applyKeepScreenOn: roundActive=$roundActive foreground=${isForeground()} " +
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
