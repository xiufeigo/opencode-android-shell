package com.xiufeigo.opencodeshell.web

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * P6 · 省电绊线（第二道）：**源码接线** tripwire。
 *
 * ## 为什么需要这一道
 *
 * [com.xiufeigo.opencodeshell.web.host.PowerPolicyTest] 证明的是**决策**成立。
 * 但决策对象可能被"接上了却不调用"——那样测试全绿、实际一点没省。
 * 本类直接读源码文本，把三个接线点钉死：
 *  1. `WebHostActivity.onPause()` 里调了 [PowerPolicy] 的兜底清 flag 路径；
 *  2. `WebHostActivity.onPause()` 里调了 `proxy?.suspendStreaming()`；
 *  3. `WebHostActivity.onResume()` 里调了 `proxy?.resumeStreaming()`。
 *
 * ## 反向验证怎么做的
 *
 * 撤回 P6 改动（即把 `WebHostActivity.kt` 还原成 T1 盘点时的样子）后，
 * `onPauseDoesNotCallSuspendStreaming` 与 `onResumeCallsResumeStreaming` 必然**变红**。
 * 实测输出见 `research/perf/POWER_ACTIONS_P6.md` §4。
 *
 * 沿用本仓既有做法（`DebugEvalChannelTest` 直接读 `build.gradle.kts` 与
 * `WebHostActivity.kt` 文本断言），因此不依赖任何 android 运行时。
 */
class PowerWiringTest {

    private fun src(rel: String): String {
        // Gradle 子项目测试的工作目录 = 子项目目录；为稳妥起见向上找几级。
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

    /**
     * 取 `override fun xxx() {` 到下一个 `override fun` 之间的正文，**并剥掉行注释**。
     *
     * 剥注释是必需的，不是洁癖：P6 在 `onPause` 里写了一句解释性注释
     * 「不是 `proxy?.close()`」，而 [onPauseDoesNotCloseTheProxy] 要断言的正是
     * **没有这个调用**。不剥注释的话，注释里的字面量会被当成调用 ⇒ 假红。
     * 断言的意图是"代码里没有这个调用"，不是"这段文字里没出现过这几个字母"。
     */
    private fun bodyOf(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("源码里找不到 `$signature`", start >= 0)
        val next = source.indexOf("\n    override fun ", start + signature.length)
        val raw = if (next < 0) source.substring(start) else source.substring(start, next)
        return stripLineComments(raw)
    }

    /** 去掉 `//` 行注释（本仓 Kotlin 源码不在这几个片段里用 `//` 出现在字符串中）。 */
    private fun stripLineComments(text: String): String =
        text.lineSequence()
            .map { line -> val i = line.indexOf("//"); if (i >= 0) line.substring(0, i) else line }
            .joinToString("\n")

    // ---- 接线点 1：onPause 必须断开流式连接 -------------------------------

    @Test
    fun onPauseCallsSuspendStreaming() {
        val body = bodyOf(activitySrc, "override fun onPause()")
        assertTrue(
            "onPause 必须调用 proxy?.suspendStreaming()（P6 刀 2：后台掐断 SSE）",
            body.contains("suspendStreaming()"),
        )
    }

    // ---- 接线点 2：onResume 必须解除拒绝 -----------------------------------

    @Test
    fun onResumeCallsResumeStreaming() {
        val body = bodyOf(activitySrc, "override fun onResume()")
        assertTrue(
            "onResume 必须调用 proxy?.resumeStreaming()（否则回前台永久 503）",
            body.contains("resumeStreaming()"),
        )
    }

    // ---- 接线点 3：onPause 必须无条件清常亮（忘关路径兜底） ---------------

    @Test
    fun onPauseClearsKeepScreenOnUnconditionally() {
        val body = bodyOf(activitySrc, "override fun onPause()")
        assertTrue(
            "onPause 必须调 clearKeepScreenOnOnBackground()（T1 §7.3 的忘关路径兜底）",
            body.contains("clearKeepScreenOnOnBackground()"),
        )
    }

    @Test
    fun clearKeepScreenOnUsesClearFlagsNotMasking() {
        // 直接 clearFlags 才幂等、且不依赖"当前值是什么"
        val activity = activitySrc
        val idx = activity.indexOf("fun clearKeepScreenOnOnBackground()")
        assertTrue("找不到 clearKeepScreenOnOnBackground()", idx >= 0)
        val snippet = activity.substring(idx, minOf(idx + 400, activity.length))
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
            body.contains("clearKeepScreenOnOnBackground()"),
        )
    }

    // ---- 不得用 close() 代替 suspendStreaming()（T1 §7.2 的明确警告） ------

    @Test
    fun onPauseDoesNotCloseTheProxy() {
        val body = bodyOf(activitySrc, "override fun onPause()")
        assertTrue(
            "onPause 绝不能调 proxy?.close()：那会把 port 置空，onResume 恢复失败（T1 §7.2）",
            !body.contains("proxy?.close()"),
        )
    }

    // ---- 代理侧：必须是"关 + 拒绝"，不能只关 -----------------------------

    @Test
    fun proxyRefusesStreamingWhileSuspended() {
        val proxy = proxySrc
        assertTrue(
            "代理必须在 suspended 状态下拒绝流式请求（否则 EventSource 重连拉锯，射频照旧唤醒）",
            proxy.contains("PowerPolicy.StreamingAction.REFUSE_WHILE_BACKGROUND"),
        )
    }

    @Test
    fun proxyRefusalHappensBeforeUpstreamCall() {
        val proxy = proxySrc
        val refuse = proxy.indexOf("REFUSE_WHILE_BACKGROUND")
        assertTrue("找不到拒绝分支", refuse >= 0)
        val upstream = proxy.indexOf("client.newCall(request).execute()")
        assertTrue("找不到上游调用点", upstream >= 0)
        assertTrue(
            "拒绝必须发生在建上游请求之前（拒绝得晚 = 上游 socket 已经建了，省电为 0）",
            refuse < upstream,
        )
    }

    @Test
    fun suspendStreamingKeepsServerSocketAndPortAlive() {
        val proxy = proxySrc
        val start = proxy.indexOf("fun suspendStreaming()")
        assertTrue("找不到 suspendStreaming()", start >= 0)
        val end = proxy.indexOf("fun resumeStreaming()", start)
        val body = proxy.substring(start, if (end < 0) proxy.length else end)
        assertTrue(
            "suspendStreaming 绝不能关 ServerSocket（会丢 port）",
            !body.contains("serverSocket?.close()"),
        )
        assertTrue(
            "suspendStreaming 绝不能 shutdownNow 线程池",
            !body.contains("shutdownNow()"),
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
}
