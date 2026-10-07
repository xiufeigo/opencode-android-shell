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

    /** 2. 窗口内第 1 次崩溃 ⇒ **重建**自愈（不再 reload），且有 60 s 节流窗口。 */
    @Test
    fun firstCrashRebuildsWithThrottle() {
        val body = overrideBody()
        // ⚠️ UX16-P2：本条原先断言 `body.contains("reload()")`。
        // UX16-P1 真触发实证推翻了那条契约 —— reload() 之后渲染进程**必然再崩**
        // （两次复现间隔 6.55s / 6.56s），端到端"恢复可用"从不成立，而节流每次都
        // 把它接住 ⇒ 失败被伪装成"正常落诊断页"。故改为钉"重建"。
        assertTrue(
            "🔴 UX16-P2：崩溃恢复路径里必须 **destroy() 旧实例 + 重建**，而不是 reload —— " +
                "平台口径是渲染进程消失后该实例不应再被使用，且本镜像上 reload 后必然再崩。",
            body.contains("destroy()") && body.contains("setupWebViewAndLoad()"),
        )
        assertTrue(
            "🔴 UX14-P1：必须有节流窗口常量，否则 重建→崩→重建 会变成重启风暴，" +
                "每次都真起一个 Chromium 渲染进程，很快把内存/电量吃干。",
            Regex("RENDER_CRASH_RELOAD_WINDOW_MS\\s*=\\s*60_000L").containsMatchIn(source),
        )
        // 节流窗口必须真的被这个方法用上（常量存在但没读 = 无节流）。
        assertTrue(
            "🔴 UX14-P1：onRenderProcessGone 里必须真的读节流窗口，" +
                "否则窗口只是摆设、崩溃循环仍会无限重建。",
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

    // ---- UX16-P2 新增 3 条 ------------------------------------------------

    /**
     * 5. 崩溃恢复必须是**重建式**：`destroy()` + 走 [setupWebViewAndLoad]，
     * 且首崩分支**不得**再出现 `reload()`。
     *
     * 与第 2 条的区别：第 2 条钉"重建发生过"，本条钉"`reload()` 已被彻底拿掉"——
     * 少了后半句，有人把 `reload()` 加回来会伪装成"重建+刷新"，两条都会绿。
     */
    @Test
    fun recoveryRecreatesWebViewInsteadOfReload() {
        val body = overrideBody()
        assertTrue(
            "🔴 UX16-P2：onRenderProcessGone 的首崩分支必须先 destroy() 旧 WebView 实例 —— " +
                "渲染进程已经没了，继续复用这个实例就是未定义行为。",
            body.contains("destroy()"),
        )
        assertTrue(
            "🔴 UX16-P2：必须调用 setupWebViewAndLoad() 走**完整**初始化链重建，" +
                "否则新实例会漏掉 bridge 注入 / doc-start 脚本 / client / insets，" +
                "页面会安静地半残（能显示但所有原生能力失效，比白屏更难查）。",
            body.contains("setupWebViewAndLoad()"),
        )
        assertTrue(
            "🔴 UX16-P2：首崩分支不得再调用 reload() —— UX16-P1 已实证 reload 之后渲染进程" +
                "必然再崩（6.55s / 6.56s 两次复现），端到端自愈从来不成立。",
            !body.contains("reload()"),
        )
        // 拆桥与出视图树也必须在这一条路径上，否则 destroy 得不干净。
        assertTrue(
            "🔴 UX16-P2：重建前必须 removeJavascriptInterface —— 旧实例上残留的桥引用" +
                "会让 JS 侧拿到一个已死的宿主对象。",
            body.contains("removeJavascriptInterface"),
        )
    }

    /**
     * 6. 诊断页字符集：`<meta charset>` 声明 + **文件本身是合法 UTF-8**。
     *
     * 为什么后半句才是本条的重点：UX16-P1 报的"中文全渲染成 `?`"，当时被归因为
     * "缺 `<meta charset>`"。实测**声明本来就在第 4 行**；真因是文件里 156 处
     * 三字节序列被塌成两字节 + `?`（GBK 往返的产物），整个文件不是合法 UTF-8。
     * 只断言 charset 声明的话，这条绊线对该缺陷**完全瞎**。
     */
    @Test
    fun diagnosticPageDeclaresUtf8() {
        val f = File("src/main/assets/www/index.html")
        assertTrue("找不到诊断页资产：${f.absolutePath}", f.exists())
        val bytes = f.readBytes()
        val text = String(bytes, Charsets.UTF_8)
        assertTrue(
            "🔴 UX16-P2：诊断页必须声明 <meta charset>（大小写不敏感）。",
            Regex("""<meta[^>]+charset\s*=\s*["']?utf-8""", RegexOption.IGNORE_CASE).containsMatchIn(text),
        )
        // 真正的缺陷断言：文件必须是**严格合法**的 UTF-8。
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        val decodeError = try {
            decoder.decode(java.nio.ByteBuffer.wrap(bytes))
            null
        } catch (e: java.nio.charset.CharacterCodingException) {
            e.message
        }
        assertTrue(
            "🔴 UX16-P2：诊断页必须是**严格合法**的 UTF-8。当前不是：$decodeError\n" +
                "非法字节会让 Chromium 逐字把受影响的中文渲染成 `?`（UX16-P1 截图证据），" +
                "而 <meta charset> 写得再对也救不了非法的字节序列。",
            decodeError == null,
        )
    }

    /** 7. 全部自带 HTML 资产都要声明 charset（含子目录）。 */
    @Test
    fun allBundledHtmlDeclareCharset() {
        val root = File("src/main/assets/www")
        assertTrue("找不到自带资源目录：${root.absolutePath}", root.isDirectory)
        val pages = root.walkTopDown().filter { it.isFile && it.name.endsWith(".html") }.toList()
        assertTrue("自带资源里一个 .html 都没有，绊线失去意义", pages.isNotEmpty())
        for (page in pages) {
            val text = page.readText(Charsets.UTF_8)
            assertTrue(
                "🔴 UX16-P2：自带 HTML 资产 ${page.path} 必须声明 <meta charset=\"utf-8\">。",
                Regex("""<meta[^>]+charset\s*=\s*["']?utf-8""", RegexOption.IGNORE_CASE).containsMatchIn(text),
            )
        }
    }
}