package com.xiufeigo.opencodeshell.web

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * UX13-P13 · **P10 后台读数必须在 `webView.onPause()` 之前落地**（源码顺序绊线）
 *
 * ## 这个绊线在防什么
 *
 * P13 在 emulator-6790 上实测（冷启重进 → 发 `ping` → HOME）拿到一条**竞态**：
 *
 * ```
 * 12:09:49.454  P7 onPause（Android onPause 落地，日志为证）
 * 12:09:51.239  round end session.execution.succeeded（后台收到终帧）
 * 12:09:52.222  页面 visibilityState 读数才第一次变成 "hidden"   ← 比 onPause 迟 2.768 s
 * 12:09:52.232  Notification when=1791288592232（channel=oc_turns） ← 仅在 vs 翻 hidden 后 10 ms
 * ```
 *
 * 成因是**纯顺序**：`evaluateJs("window.__ocSetBackground(true)")` 原本写在
 * [WebHostActivity.onPause] 的**最后一行**，而 `webView.onPause()` 在它**前面**。
 * WebView 一旦被 `onPause()` 暂停，JS 线程被节流，这条 evaluateJavascript 不会立刻跑。
 *
 * ## 为什么这 2.768 s 是致命的
 *
 * 页面上层（上游 focus 管理器，bundle `build-CA4aPxfP.js` 实读原文）
 * `isFocused(){ return typeof this.#e==='boolean' ? this.#e
 *                    : globalThis.document?.visibilityState !== 'hidden' }`
 * —— 拿 `visibilityState` 当**兜底真值**。于是这 2.768 s 内页面上层仍认为
 * "在前台"，notify 闸门此刻若被评估就直接 return，通知丢失（P12 §5 的现象）。
 *
 * 本轮是**险胜**：终帧只比"闸门可开"早了 0.98 s，通知才没丢。
 *
 * ## 修法与本绊线的作用
 *
 * 修法 = 只调换两行顺序，**不改任何语义**（读数仍只由 `__ocSetBackground` 改，
 * 仍不派任何事件——派 visibilitychange 会触发上游 resync → abort 掐死 SSE，P7 已证）。
 *
 * 顺序是纯文本性质、无法在 JVM 单测里跑出来的，所以用**源码顺序断言**当绊线：
 * 任何人以后把 `__ocSetBackground(true)` 挪回 `webView.onPause()` 之后，本测试立刻红。
 */
class Ux13P13BackgroundGateOrderTripwireTest {

    private fun onPauseSource(): String {
        val f = File("src/main/java/com/xiufeigo/opencodeshell/web/WebHostActivity.kt")
        assertTrue("找不到 WebHostActivity.kt：${f.absolutePath}", f.exists())
        val text = f.readText()
        val start = text.indexOf("    override fun onPause() {")
        assertTrue("找不到 onPause() 定义", start >= 0)
        // onPause 方法体到下一个同缩进的 "override fun" 之前为止。
        val next = text.indexOf("    override fun ", start + 1)
        val end = if (next < 0) text.length else next
        // 🔴 断言必须只针对**真实代码**：本方法的注释里大量出现
        // `webView.onPause()` / `visibilitychange` / `__ocSetBackground(true)` 这些字面量
        // （P6/P7/P10/P13 的设计说明就写在旁边），不剥注释的话断言会读到散文而不是语句。
        return stripComments(text.substring(start, end))
    }

    /** 去掉 `//` 行注释与 `/* */` 块注释，只留可执行代码。 */
    private fun stripComments(src: String): String {
        var out = StringBuilder(src.length)
        var i = 0
        val n = src.length
        while (i < n) {
            val c = src[i]
            val next = if (i + 1 < n) src[i + 1] else ' '
            when {
                c == '/' && next == '/' -> {
                    while (i < n && src[i] != '\n') i++
                }
                c == '/' && next == '*' -> {
                    i += 2
                    while (i + 1 < n && !(src[i] == '*' && src[i + 1] == '/')) i++
                    i = (i + 2).coerceAtMost(n)
                }
                c == '"' -> {
                    out.append(c); i++
                    while (i < n && src[i] != '"') {
                        if (src[i] == '\\') { out.append(src[i]); i++ }
                        if (i < n) { out.append(src[i]); i++ }
                    }
                    if (i < n) { out.append(src[i]); i++ }
                }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }

    @Test
    fun `P10 后台读数 evaluateJs 必须排在 webView onPause 之前`() {
        val body = onPauseSource()
        val setBg = body.indexOf("__ocSetBackground(true)")
        val webViewPause = body.indexOf("webView.onPause()")

        assertTrue("onPause() 里应存在 __ocSetBackground(true) 这一行", setBg >= 0)
        assertTrue("onPause() 里应存在 webView.onPause() 这一行", webViewPause >= 0)
        assertTrue(
            "🔴 P13 回归：__ocSetBackground(true) 被挪到了 webView.onPause() 之后。" +
                "WebView 被 onPause() 暂停后 JS 线程被节流，这条 evaluateJavascript 会迟到约 2.8 s，" +
                "期间上游 focus 管理器读到 visibilityState 仍是 'visible'，notify 闸门直接 return。" +
                "必须把它排在 webView.onPause() 之前。",
            setBg < webViewPause,
        )
    }

    @Test
    fun `P10 后台读数只设置一次`() {
        val body = onPauseSource()
        val count = Regex("__ocSetBackground\\(true\\)").findAll(body).count()
        assertTrue(
            "🔴 P13：onPause() 里出现了 $count 次 __ocSetBackground(true)，应恰好 1 次" +
                "（调换顺序时容易留下重复调用）。",
            count == 1,
        )
    }

    @Test
    fun `P10 shim 仍然只改读数不派发 visibilitychange`() {
        val body = onPauseSource()
        assertTrue(
            "🔴 P13 回归：onPause() 里不得派发 visibilitychange —— 会触发上游 resync → abort，" +
                "掐死回合保活路径的 SSE（P6/P7 已证的死锁链）。",
            !body.contains("visibilitychange"),
        )
    }
}