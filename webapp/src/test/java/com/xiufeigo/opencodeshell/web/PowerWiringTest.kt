package com.xiufeigo.opencodeshell.web

import com.xiufeigo.opencodeshell.web.testutil.TestSource
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P6 · 省电绊线（第二道）：**源码接线** tripwire。
 *
 * ## 为什么需要这一道
 *
 * [com.xiufeigo.opencodeshell.web.host.PowerPolicyTest] 证明的是**决策**成立。
 * 但决策对象可能被"接上了却不调用"——那样测试全绿、实际一点没省。
 * 本类直接读源码文本，把剩下的接线点钉死：
 *  1. `BackgroundLifecycleHost.onPause()` 把兜底清 flag 委托给 `KeepScreenOnHost`；
 *  2. `WebHostActivity.onDestroy()` 通过同一个生命周期宿主清 flag；
 *  3. 桥的 `setKeepScreenOn(true)` 必须过 [PowerPolicy] 的前台校验。
 *
 * ## UX6 删除的部分（逐条理由）
 *  - `proxy?.suspendStreaming()` / `proxy?.resumeStreaming()` 两个接线点：
 *    **回环代理已按产品决策整体删除**（直连架构），宿主不再介入流量转发。
 *  - 代理侧 REFUSE_WHILE_BACKGROUND 断言：被测对象（代理）已不存在。
 *
 * ## UX8 更新
 *  "后台掐 SSE"能力已**恢复**，但实现换了条路：不重建代理，而是宿主在
 *  onPause/onResume 合成派发 pagehide/pageshow，驱动上游 client 自有的
 *  pageLifecycle 机制（connection.ts:219-240）。因此 `suspendStreaming()` /
 *  `resumeStreaming()` 这两个**代理时代的方法名**仍然不得出现（出现 = 有人
 *  把代理接了回来），下方反向断言继续有效。
 *
 * 沿用本仓既有做法（`DebugEvalChannelTest` 直接读 `build.gradle.kts` 与
 * `WebHostActivity.kt` 文本断言），因此不依赖任何 android 运行时。
 */
class PowerWiringTest {

    private val activitySrc: String
        get() = TestSource.read("src/main/java/com/xiufeigo/opencodeshell/web/WebHostActivity.kt")

    private val lifecycleSrc: String
        get() = TestSource.read("src/main/java/com/xiufeigo/opencodeshell/web/host/BackgroundLifecycleHost.kt")

    private val powerSrc: String
        get() = TestSource.read("src/main/java/com/xiufeigo/opencodeshell/web/host/KeepScreenOnHost.kt")

    private val bridgeSrc: String
        get() = TestSource.read("src/main/java/com/xiufeigo/opencodeshell/web/host/WebHostBridge.kt")

    /**
     * 取成员函数体并剥掉行注释。剥注释是必需的：注释里的字面量会被当成调用。
     */
    private fun bodyOf(source: String, signature: String, endSignature: String = "\n    override fun "): String {
        val start = source.indexOf(signature)
        assertTrue("源码里找不到 `$signature`", start >= 0)
        val next = source.indexOf(endSignature, start + signature.length)
        val raw = if (next < 0) source.substring(start) else source.substring(start, next)
        return stripLineComments(raw)
    }

    /** 去掉 `//` 行注释（本仓 Kotlin 源码不在这几个片段里用 `//` 出现在字符串中）。 */
    private fun stripLineComments(text: String): String =
        text.lineSequence()
            .map { line -> val i = line.indexOf("//"); if (i >= 0) line.substring(0, i) else line }
            .joinToString("\n")

    // ---- 接线点 1：onPause 必须无条件清常亮（忘关路径兜底） ---------------

    @Test
    fun onPauseClearsKeepScreenOnUnconditionally() {
        val body = bodyOf(lifecycleSrc, "fun onPause()", "\n    private val backgroundRoundRelease")
        assertTrue(
            "onPause 必须委托 KeepScreenOnHost 执行 T1 §7.3 的忘关路径兜底",
            body.contains("keepScreenOn.onBackground()"),
        )
    }

    @Test
    fun clearKeepScreenOnUsesClearFlagsNotMasking() {
        // 直接 clearFlags 才幂等、且不依赖"当前值是什么"
        val power = powerSrc
        val idx = power.indexOf("fun clearKeepScreenOnOnBackground()")
        assertTrue("找不到 clearKeepScreenOnOnBackground()", idx >= 0)
        val snippet = power.substring(idx, minOf(idx + 400, power.length))
        assertTrue(
            "兜底清常亮必须用 clearFlags",
            snippet.contains("clearFlags"),
        )
    }

    @Test
    fun onDestroyAlsoClearsKeepScreenOn() {
        val body = bodyOf(activitySrc, "override fun onDestroy()")
        assertTrue(
            "onDestroy 也应清常亮（flag 泄漏会在 onSaveInstanceState 重建路径被观察到）",
            body.contains("backgroundLifecycle.onDestroy()"),
        )
    }

    // ---- 桥侧：置位常亮要过前台校验 ---------------------------------------

    @Test
    fun bridgeGatesKeepScreenOnOnForeground() {
        val bridge = bridgeSrc
        val idx = bridge.indexOf("fun setKeepScreenOn(")
        assertTrue("找不到 setKeepScreenOn", idx >= 0)
        val snippet = bridge.substring(idx, minOf(idx + 900, bridge.length))
        assertTrue(
            "setKeepScreenOn(true) 必须过 [PowerPolicy] 的前台校验（P6 刀 1）",
            snippet.contains("PowerPolicy.mustClearKeepScreenOn"),
        )
    }

    // ---- UX6：后台断流接线已随代理删除（反向断言，防残留） -----------------

    @Test
    fun noSuspendOrResumeStreamingCallsRemain() {
        // 代理删除后，这两个调用不应再出现；出现 = 有人把代理相关代码接了回来。
        assertTrue(
            "onPause 里不应再有 proxy?.suspendStreaming()（代理已删）",
            !bodyOf(lifecycleSrc, "fun onPause()", "\n    private val backgroundRoundRelease").contains("suspendStreaming()"),
        )
        assertTrue(
            "onResume 里不应再有 proxy?.resumeStreaming()（代理已删）",
            !bodyOf(lifecycleSrc, "fun onResume()", "\n    private fun dispatchPageLifecycleEvent").contains("resumeStreaming()"),
        )
    }
}
