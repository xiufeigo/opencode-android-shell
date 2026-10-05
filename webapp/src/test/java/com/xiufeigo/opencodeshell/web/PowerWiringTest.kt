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
 * 本类直接读源码文本，把剩下的接线点钉死：
 *  1. `WebHostActivity.onPause()` 里调了 [PowerPolicy] 的兜底清 flag 路径；
 *  2. `WebHostActivity.onDestroy()` 同样清 flag；
 *  3. 桥的 `setKeepScreenOn(true)` 必须过 [PowerPolicy] 的前台校验。
 *
 * ## UX6 删除的部分（逐条理由）
 *  - `proxy?.suspendStreaming()` / `proxy?.resumeStreaming()` 两个接线点：
 *    **回环代理已按产品决策整体删除**（直连架构），"后台掐 SSE"这条省电刀法
 *    随代理消失 —— 后台时 WebView `onPause` 暂停页面 JS，`/event` 由浏览器
 *    自行挂起/重连，与手机浏览器语义一致。这是**如实记录的能力回退**，不是遗漏。
 *  - 代理侧 REFUSE_WHILE_BACKGROUND 断言：被测对象（代理）已不存在。
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

    private val bridgeSrc: String
        get() = src("src/main/java/com/xiufeigo/opencodeshell/web/host/WebHostBridge.kt")

    /**
     * 取 `override fun xxx() {` 到下一个 `override fun` 之间的正文，**并剥掉行注释**。
     *
     * 剥注释是必需的，不是洁癖：注释里的字面量会被当成调用 ⇒ 假红。
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

    // ---- 接线点 1：onPause 必须无条件清常亮（忘关路径兜底） ---------------

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
            !bodyOf(activitySrc, "override fun onPause()").contains("suspendStreaming()"),
        )
        assertTrue(
            "onResume 里不应再有 proxy?.resumeStreaming()（代理已删）",
            !bodyOf(activitySrc, "override fun onResume()").contains("resumeStreaming()"),
        )
    }
}
