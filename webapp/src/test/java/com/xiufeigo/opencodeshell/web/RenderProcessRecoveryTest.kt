package com.xiufeigo.opencodeshell.web

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * UX14-P1 · **渲染进程崩溃必须自愈**（源码级绊线，4 用例）
 *
 * ## 这个绊线在防什么
 *
 * `WebViewPreflight.Reason` 一直有 `RENDER_CRASH` 这个枚举值，诊断页也有对应的
 * 「渲染进程崩溃」文案，但**全模块没有任何 `onRenderProcessGone` 实现** ——
 * 宿主侧只有一个 `onRenderProcessGoneNotWired()` 空壳。那段空壳的注释还断言
 * 「本模块编译期解析不到 `android.webkit.WebViewRenderProcessDetail`」。
 *
 * 那个断言是**错的**：写错了类名。平台类型叫
 * `android.webkit.RenderProcessGoneDetail`（本轮在 `android-35/android.jar`
 * 里用 zip 目录实查确认存在），且 `compileSdk = 35` / `minSdk = 26` 都过 API 26 门槛。
 * 也就是说这个缺口**从来不是环境问题，是没人接线** ——
 * HANDOFF §5 第 11 条「该分支文案可能永远走不到」由此闭环。
 *
 * ## 为什么要接线（功能一致性缺口）
 *
 * WebView 渲染进程崩溃是「白屏死」的**唯一常见成因**，而 WebView 的默认行为是
 * **直接 finish 掉整个 Activity**：用户唯一出路是杀进程重启，正在跑的回合（SSE）
 * 全丢。桌面端 Electron 渲染进程崩溃会**自动恢复** —— 壳这一侧不接就是对不齐。
 *
 * 本绊线钉死四件事：override 存在且**消费**事件（不 finish）、窗口内第一次崩溃
 * **reload** 自愈、窗口内第二次崩溃**落诊断页**（不是无限重启）、以及**不调 super**
 * （super 的 `return false` 就是"不处理"，正是不该走的那条路）。
 *
 * ## 为什么是源码断言而不是行为测试
 *
 * `onRenderProcessGone` 需要一个真的、并且会崩掉的渲染进程才能触发，JVM 单测里
 * 造不出来（本轮也不起模拟器）。同 `Ux13P13BackgroundGateOrderTripwireTest`
 * 的做法：断言**源码结构**。⚠️ 全部断言都在**剥掉注释后**的代码上做 ——
 * 本 override 的设计说明里就写着 `return true` / `reload()` / `super` 这些字面量，
 * 不剥注释会把散文当成语句读，必然假绿。
 */
class RenderProcessRecoveryTest {

    private val source: String by lazy {
        val f = File("src/main/java/com/xiufeigo/opencodeshell/web/WebHostActivity.kt")
        assertTrue("找不到 WebHostActivity.kt：${f.absolutePath}", f.exists())
        f.readText()
    }

    /** `onRenderProcessGone` 的方法体（已剥注释），找不到时测试直接红。 */
    private fun overrideBody(): String {
        val start = source.indexOf("override fun onRenderProcessGone(")
        assertTrue(
            "🔴 UX14-P1 回归：WebHostActivity 里没有 onRenderProcessGone 的 override。" +
                "渲染进程崩溃会走 WebView 默认行为 finish 掉整个 Activity，" +
                "用户只能杀进程重启（正在跑的回合全丢），与桌面端 Electron 的自动恢复不一致。",
            start >= 0,
        )
        // 方法体到下一个同缩进（8 空格）的 "override fun" 之前为止。
        val next = source.indexOf("\n        override fun ", start + 1)
        val end = if (next < 0) source.length else next
        return stripComments(source.substring(start, end))
    }

    /** 去掉 `//` 行注释与 `/* */` 块注释（跳过字符串字面量），只留可执行代码。 */
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

    /**
     * 1. override 存在**且被消费** —— `return true` 是全篇的语义核心：
     * WebView 默认行为就是 `return false`（不处理 ⇒ finish Activity）。
     */
    @Test
    fun onRenderProcessGoneIsOverriddenAndConsumed() {
        val body = overrideBody()
        assertTrue(
            "🔴 UX14-P1：onRenderProcessGone 必须消费事件（return true）。" +
                "返回 false 等于交给 WebView 默认处理 —— 那会把整个 Activity finish 掉。",
            body.contains("return true"),
        )
        // 签名参数类型必须是对的那个类：历史上正是因为写成
        // `WebViewRenderProcessDetail`（不存在）才被误判成"编译期解析不到"。
        assertTrue(
            "🔴 UX14-P1：onRenderProcessGone 的第二个参数类型应是平台真名 " +
                "`RenderProcessGoneDetail`（不是历史注释里写的 `WebViewRenderProcessDetail`）。",
            source.contains("detail: RenderProcessGoneDetail"),
        )
    }

    /** 2. 窗口内第 1 次崩溃 ⇒ reload 自愈，且有 60 s 节流窗口。 */
    @Test
    fun firstCrashReloadsWithThrottle() {
        val body = overrideBody()
        assertTrue(
            "🔴 UX14-P1：崩溃恢复路径里必须有 webView.reload() —— " +
                "页面从本地 4173 重新加载，会话/服务器状态都在 localStorage，可直接自愈。",
            body.contains("reload()"),
        )
        assertTrue(
            "🔴 UX14-P1：必须有节流窗口常量，否则 reload→崩→reload 会变成重启风暴，" +
                "每次都真起一个 Chromium 渲染进程，很快把内存/电量吃干。",
            Regex("RENDER_CRASH_RELOAD_WINDOW_MS\\s*=\\s*60_000L").containsMatchIn(source),
        )
        // 节流窗口必须真的被这个方法用上（常量存在但没读 = 无节流）。
        assertTrue(
            "🔴 UX14-P1：onRenderProcessGone 里必须真的读节流窗口，" +
                "否则窗口只是摆设、崩溃循环仍会无限 reload。",
            body.contains("RENDER_CRASH_RELOAD_WINDOW_MS"),
        )
    }

    /** 3. 窗口内再次崩溃 ⇒ 落 showDiagnostic，并真正引用 RENDER_CRASH。 */
    @Test
    fun crashLoopFallsBackToDiagnostic() {
        val body = overrideBody()
        assertTrue(
            "🔴 UX14-P1：崩溃循环必须落到 showDiagnostic（认输并展示可读信息），" +
                "不能继续无限 reload。",
            body.contains("showDiagnostic("),
        )
        assertTrue(
            "🔴 UX14-P1：诊断页必须收 WebViewPreflight.Reason.RENDER_CRASH —— " +
                "这个枚举值和对应文案一直存在却没人用（HANDOFF §5 第 11 条），本轮正是接线它。",
            body.contains("WebViewPreflight.Reason.RENDER_CRASH"),
        )
        // 崩溃次数判定：必须存在"第 2 次"这一档的判定，不能只有单次崩溃处理。
        assertTrue(
            "🔴 UX14-P1：必须区分窗口内第 1 次与再次崩溃（crashesInWindow 判定），" +
                "否则第 2 次崩溃也会走 reload。",
            Regex("crashesInWindow\\s*>=\\s*2").containsMatchIn(body),
        )
    }

    /**
     * 4. **不调 super** —— 消费语义的结构证据。
     *
     * `WebViewClient.onRenderProcessGone` 的 super 实现就是 `return false`；
     * 一旦调了 super 又要 return true，语义自相矛盾（且极易被人"顺手补上"）。
     */
    @Test
    fun defaultFinishIsPrevented() {
        val body = overrideBody()
        assertTrue(
            "🔴 UX14-P1：onRenderProcessGone 里不得调用 super.onRenderProcessGone —— " +
                "super 的实现是 return false（不处理），正是会让 WebView finish 掉 " +
                "整个 Activity 的那条默认路径。消费语义要求完全接管、不调 super。",
            !body.contains("super.onRenderProcessGone"),
        )
    }
}