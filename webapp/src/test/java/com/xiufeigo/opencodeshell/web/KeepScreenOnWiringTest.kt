package com.xiufeigo.opencodeshell.web

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * D1 · **`KEEP_SCREEN_ON` 接线绊线**（源码文本断言）。
 *
 * ## 为什么单测还不够
 *
 * [com.xiufeigo.opencodeshell.web.host.RoundActivityTrackerTest] 证明了
 * **状态机**对，但"状态机对"不等于"flag 真被置起"。修前的 bug 恰恰是：
 * 状态机/策略都在、`setKeepScreenOn` 也在，**却没有一个生产调用方**
 * （全仓只有定义处 + 单测命中）⇒ flag 永远置不起来。
 * 也就是说，这类 bug **单测 100% 绿、功能 100% 不生效**。
 *
 * 所以这里把**接线**钉死：必须有生产路径真的把 flag 置起来。
 *
 * ## 反向验证
 *
 * 把任一接线点撤掉（见 `research/web/FIX_D1.md` §3），
 * 对应用例必然**变红**。
 */
class KeepScreenOnWiringTest {

    private fun src(rel: String): String {
        val relPath = File(rel)
        var dir: File? = File(".").absoluteFile
        repeat(4) {
            val f = File(dir, rel)
            if (f.isFile) return f.readText(Charsets.UTF_8)
            dir = dir?.parentFile
        }
        throw AssertionError("找不到源文件: $rel（从 ${File(".").absolutePath} 向上找了 4 级）")
    }

    private val activitySrc: String
        get() = src("src/main/java/com/xiufeigo/opencodeshell/web/WebHostActivity.kt")

    private val proxySrc: String
        get() = src("src/main/java/com/xiufeigo/opencodeshell/web/host/LoopbackProxy.kt")

    private val bridgeSrc: String
        get() = src("src/main/java/com/xiufeigo/opencodeshell/web/host/WebHostBridge.kt")

    // ---- 接线点 1：宿主必须有"落 flag"的生产实现 ------------------------

    @Test
    fun activityHasKeepScreenOnApplyImplementation() {
        val src = activitySrc
        assertTrue(
            "WebHostActivity 缺少 applyKeepScreenOn(...) —— 回合状态无人落地成 flag",
            src.contains("fun applyKeepScreenOn("),
        )
    }

    @Test
    fun applyKeepScreenOnActuallyAddsTheFlag() {
        val src = activitySrc
        val idx = src.indexOf("fun applyKeepScreenOn(")
        assertTrue("找不到 applyKeepScreenOn", idx >= 0)
        val snippet = src.substring(idx, minOf(idx + 1600, src.length))
        assertTrue(
            "回合进行中必须 addFlags(FLAG_KEEP_SCREEN_ON)（这是修复前从未执行到的那一行）",
            snippet.contains("addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)"),
        )
        assertTrue(
            "不置位时必须 clearFlags",
            snippet.contains("clearFlags(PowerPolicy.FLAG_KEEP_SCREEN_ON_BIT)"),
        )
    }

    @Test
    fun applyKeepScreenOnGatedByPowerPolicy() {
        val src = activitySrc
        val idx = src.indexOf("fun applyKeepScreenOn(")
        assertTrue("找不到 applyKeepScreenOn", idx >= 0)
        val snippet = src.substring(idx, minOf(idx + 1600, src.length))
        assertTrue(
            "置位必须过 [PowerPolicy.shouldKeepScreenOn]（后台不许常亮）",
            snippet.contains("PowerPolicy.shouldKeepScreenOn"),
        )
    }

    // ---- 接线点 2：代理必须真的把回合信号喂出去 ------------------------

    @Test
    fun proxyDetectsRoundStartFromRealTraffic() {
        assertTrue(
            "代理必须在 onForward 里识别回合开始请求",
            proxySrc.contains("isRoundStartRequest"),
        )
    }

    @Test
    fun proxyPublishesRoundStateToHost() {
        val src = proxySrc
        assertTrue("代理必须暴露回合状态回调", src.contains("onRoundActiveChanged"))
        assertTrue("代理必须真正调用回调（否则宿主收不到回合状态）", src.contains("cb(active)"))
    }

    @Test
    fun proxyHasRoundEndSignal() {
        val src = proxySrc
        assertTrue(
            "必须有回合结束信号（否则只有置起、没有释放，忘了关就常亮）",
            src.contains("onRoundEnd()"),
        )
    }

    // ---- 接线点 3：宿主必须真的把回调装上 ------------------------------

    @Test
    fun activityInstallsRoundCallbackOnProxy() {
        val src = activitySrc
        assertTrue(
            "起代理后必须装上 onRoundActiveChanged（缺它 ⇒ 回合信号发不出去 ⇒ 仍是死代码）",
            src.contains("onRoundActiveChanged ="),
        )
    }

    // ---- 接线点 4：释放路径不能被绕过 ----------------------------------

    @Test
    fun onPauseStillClearsKeepScreenOnUnconditionally() {
        val start = activitySrc.indexOf("override fun onPause()")
        assertTrue("找不到 onPause", start >= 0)
        val next = activitySrc.indexOf("\n    override fun ", start + 1)
        val body = activitySrc.substring(start, if (next < 0) activitySrc.length else next)
        assertTrue(
            "onPause 必须仍然无条件清常亮（离开前台一定释放，是硬要求）",
            body.contains("clearKeepScreenOnOnBackground()"),
        )
    }

    @Test
    fun onResumeReappliesKeepScreenOnForLiveRound() {
        val start = activitySrc.indexOf("override fun onResume()")
        assertTrue("找不到 onResume", start >= 0)
        val next = activitySrc.indexOf("\n    override fun ", start + 1)
        val body = activitySrc.substring(start, if (next < 0) activitySrc.length else next)
        assertTrue(
            "onResume 必须重算一次常亮（回合中途切走再回来，回合还在跑就该恢复常亮）",
            body.contains("applyKeepScreenOn"),
        )
    }

    // ---- 桥侧：页面那条路必须接到同一个状态机，不再是死胡同 ------------

    @Test
    fun bridgeRoutesPageCallIntoTrackerNotDirectFlagWrite() {
        val src = bridgeSrc
        val idx = src.indexOf("fun setKeepScreenOn(")
        assertTrue("找不到 setKeepScreenOn", idx >= 0)
        val snippet = src.substring(idx, minOf(idx + 1400, src.length))
        assertTrue(
            "桥必须把页面请求喂进回合跟踪器（否则页面这条路仍与 flag 无关）",
            snippet.contains("onRoundStart") || snippet.contains("onRoundEnd"),
        )
        assertTrue(
            "桥必须调宿主的统一落 flag 入口，不自己 addFlags",
            snippet.contains("applyKeepScreenOnState"),
        )
    }

    @Test
    fun bridgeKeepsForegroundGate() {
        val src = bridgeSrc
        val idx = src.indexOf("fun setKeepScreenOn(")
        val snippet = src.substring(idx, minOf(idx + 1400, src.length))
        assertTrue(
            "页面显式置位仍必须过前台校验（P6 刀 1，不能因 D1 改写而丢失）",
            snippet.contains("PowerPolicy.mustClearKeepScreenOn"),
        )
    }

    // ---- 接线点 5：兜底超时必须**被排程**，否则规则形同虚设 ----------

    @Test
    fun keepScreenOnBackstopIsActuallyScheduled() {
        // ⚠️ 这一道是被设备实测逼出来的：只写"90s 超时"这条**判定规则**是不够的 ——
        // `isRoundActive` 是"被问才结算"的纯函数，没有定时器去问它，
        // 设备上实测 t=+91s 仍然 `KEEP_SCREEN_ON hits=1`（flag 永久挂着）。
        // 必须**主动 postDelayed 排一次复查**，兜底才真的会兜住。
        val src = activitySrc
        val idx = src.indexOf("fun applyKeepScreenOn(")
        assertTrue("找不到 applyKeepScreenOn", idx >= 0)
        // ⚠️ 必须断言**调用点**的精确形态，不能只断言"出现过这个名字" ——
        // `scheduleKeepScreenOnBackstopCheck` 的**函数定义**就紧跟在后面，
        // 只判 contains(name) 的话，删掉调用也照样绿（实测 R5 变异就是这样漏过去的）。
        assertTrue(
            "置位后必须真的调用 scheduleKeepScreenOnBackstopCheck()（`if (next) …` 那一句）",
            src.contains("if (next) scheduleKeepScreenOnBackstopCheck()"),
        )
        assertTrue(
            "兜底复查必须真的有 postDelayed 延时调用",
            src.contains("postDelayed") && src.contains("roundHoldTimeoutMs"),
        )
        assertTrue(
            "重复挂起必须先 removeCallbacks，否则互相覆盖成只剩最后一次",
            src.contains("removeCallbacks"),
        )
        // 复查 runnable 必须真的会去问一次（否则排了也没人结算）。
        val backstop = src.indexOf("private val keepScreenOnBackstop = Runnable")
        assertTrue("找不到兜底复查 runnable", backstop >= 0)
        val backstopBody = src.substring(backstop, minOf(backstop + 900, src.length))
        assertTrue(
            "兜底复查 runnable 必须重新问一次 isRoundActive（不问就永远不会结算）",
            backstopBody.contains("isRoundActive"),
        )
    }

    @Test
    fun onPauseCancelsPendingBackstop() {
        val start = activitySrc.indexOf("override fun onPause()")
        assertTrue("找不到 onPause", start >= 0)
        val next = activitySrc.indexOf("\n    override fun ", start + 1)
        val body = activitySrc.substring(start, if (next < 0) activitySrc.length else next)
        assertTrue(
            "onPause 必须撤掉挂着的兜底复查（已不在前台，不必再问）",
            body.contains("removeCallbacks"),
        )
    }

    // ---- 反向证明：修复前的形态必须被这些断言判红 ----------------------

    @Test
    fun preFixShapeIsDetectable() {
        // 修复前的关键特征：桥自己 addFlags，且没有任何"回合跟踪器"。
        // 若日后有人把改动整体撤掉，下面这条会先于其它用例报警。
        val activity = activitySrc
        assertTrue(
            "RoundActivityTracker 必须真的被宿主引用（不是只建了个没接线的类）",
            activity.contains("RoundActivityTracker") || activity.contains("roundActivity"),
        )
    }
}
