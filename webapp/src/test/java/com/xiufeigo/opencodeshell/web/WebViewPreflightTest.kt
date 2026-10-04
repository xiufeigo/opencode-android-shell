package com.xiufeigo.opencodeshell.web

import com.xiufeigo.opencodeshell.web.host.WebViewPreflight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * G1 绊线：把实测到的真实失败模式
 * 「**WebView 83 ⇒ 官方前端 ESM 解析失败 ⇒ 一片纯白**」
 * 变成四条可断言、可反向验证的契约。
 *
 * ## 证据（不是推测）
 * - **83.0.4103.120**（`emulator-5730` / android-30）：`Uncaught SyntaxError: Unexpected token '=' @2`，
 *   资源全 200、`onPageFinished` 照常回调，截图纯白（`research/env/PHONE_AVD_VERIFY.md` §6）。
 * - **124.0.6367.219**（E6/E7/P5/IME）：官方 UI 正常。
 * - **149.0.7827.5**（E3/E4）：官方 UI 正常。
 * - 首屏内容信号：FCP 2130 / 2290 / 2228 / 2452 ms（最差 2856）；DOM 挂载 2572 ms（最差 3063）。
 *
 * ## 判据与守护对象必须一致
 * 本类断言的**唯一**判定入口是 [WebViewPreflight]（宿主也只调它），
 * 所以「把阈值改成永远通过 / 永远失败」一定会让下面第 1/4 条变红 —— 那正是它们存在的意义。
 */
class WebViewPreflightTest {

    private val minTargetPx = 48

    private fun page(): String {
        val f = File("src/main/assets/www/index.html")
        assertTrue("找不到诊断页资产：${f.absolutePath}", f.isFile)
        return f.readText()
    }

    private fun activitySource(): String {
        val f = File("src/main/java/com/xiufeigo/opencodeshell/web/WebHostActivity.kt")
        assertTrue("找不到 WebHostActivity.kt：${f.absolutePath}", f.isFile)
        return f.readText()
    }

    private fun bridgeSource(): String =
        File("src/main/java/com/xiufeigo/opencodeshell/web/host/WebHostBridge.kt").readText()

    // ================= 绊线 ①：版本过旧 ⇒ 走诊断页，不加载官方 UI ================

    @Test
    fun anOldWebviewIsSentToTheDiagnosticPageInsteadOfTheOfficialUi() {
        // 实测失败的那个版本。
        val v = WebViewPreflight.preflight("83.0.4103.120")
        val blocked = v as? WebViewPreflight.Verdict.ShowDiagnostic
        assertTrue(
            "G1 ①: WebView 83.0.4103.120 是**实测会白屏**的版本（ESM 解析失败），" +
                "preflight 却判成 ${v::class.simpleName} ⇒ 会去加载官方 UI = 让用户看白屏。",
            blocked != null,
        )
        assertEquals(
            "G1 ①: 失败 reason 必须是 version（诊断页据此换文案）",
            WebViewPreflight.Reason.WEBVIEW_TOO_OLD,
            blocked!!.reason,
        )
        assertEquals("G1 ①: 主版本应解析成 83", 83, blocked.major)
        assertTrue("G1 ①: ShowDiagnostic 必须让调用方不加载官方 UI", v.showsDiagnosticPage)

        // 阈值边界：104 拦，105 放。
        assertTrue(
            "G1 ①: 104 低于阈值 ${WebViewPreflight.MIN_MAJOR}，应拦",
            WebViewPreflight.preflight("104.0.0.0").showsDiagnosticPage,
        )
        assertFalse(
            "G1 ①: 105 等于阈值，应放行（`:has()` 从 105 起支持）",
            WebViewPreflight.preflight("105.0.0.0").showsDiagnosticPage,
        )

        // 宿主侧真的把判定接到了 loadUrl 之前（接线不能只存在于纯逻辑里）。
        val src = activitySource()
        val gate = src.indexOf("WebViewPreflight.preflight(")
        assertTrue("G1 ①: WebHostActivity 里找不到 preflight 判定调用", gate >= 0)
        val load = src.indexOf("webView.loadUrl(url)", gate)
        assertTrue("G1 ①: 找不到官方 UI 的 loadUrl", load > gate)
        assertTrue(
            "G1 ①: preflight 判定必须发生在加载官方 UI **之前**，否则仍是先白屏再提示。" +
                "（判定位置 $gate，loadUrl 位置 $load）",
            gate in 0 until load,
        )
        assertTrue(
            "G1 ①: 过旧分支必须走 showDiagnostic 并 return，不能继续 loadUrl 官方 UI",
            src.substring(gate, load).contains("showDiagnostic") &&
                src.substring(gate, load).contains("return"),
        )
    }

    // ================= 绊线 ②：诊断页展示真实版本号 ============================

    @Test
    fun theDiagnosticPageShowsTheRealWebViewVersionNotAHardcodedOne() {
        val url = WebViewPreflight.diagnosticUrl(
            baseUrl = WebHostActivity.LEGACY_PLACEHOLDER_URL,
            reason = WebViewPreflight.Reason.WEBVIEW_TOO_OLD,
            major = 83,
            versionName = "83.0.4103.120",
        )
        assertTrue("G1 ②: 诊断页 URL 丢了 reason 参数：$url", url.contains("r=version"))
        assertTrue("G1 ②: 诊断页 URL 没带真实版本号：$url", url.contains("v=83.0.4103.120"))
        assertTrue("G1 ②: 诊断页 URL 没带阈值：$url", url.contains("min=${WebViewPreflight.MIN_MAJOR}"))
        assertTrue("G1 ②: 诊断页 URL 没带看门狗时限：$url", url.contains("t=${WebViewPreflight.LOAD_STALL_TIMEOUT_MS}"))

        // 读不到版本时也必须显示成「未读到」，而不是编一个数出来。
        val unknown = WebViewPreflight.diagnosticUrl(
            baseUrl = WebHostActivity.LEGACY_PLACEHOLDER_URL,
            reason = WebViewPreflight.Reason.STALLED,
            major = null,
            versionName = null,
        )
        assertTrue("G1 ②: 读不到版本时 URL 应写明「未读到」：$unknown", unknown.contains("v=%E6%9C%AA%E8%AF%BB%E5%88%B0"))
        assertTrue("G1 ②: 读不到版本时 major 应是 ?，不得编成 0：$unknown", unknown.contains("m=%3F"))

        // 页面必须从参数里**渲染**版本号。
        val html = page()
        assertTrue(
            "G1 ②: 诊断页没有从 query 参数读版本号（q[\"v\"]）—— 显示的会是空白或写死的值",
            html.contains("""q["v"]""") || html.contains("q['v']"),
        )
        assertTrue(
            "G1 ②: 诊断页没有渲染真实版本号那一行（#d-ver）",
            html.contains("id=\"d-ver\""),
        )
        // 写死的版本号 = 用户看到的是假的读数。
        for (hard in listOf("83.0.4103.120", "124.0.6367.219", "149.0.7827.5")) {
            assertFalse(
                "G1 ②: 诊断页里出现了写死的版本号 `$hard` —— 真实读数必须来自宿主 query 参数，" +
                    "否则换一台设备显示的就是假信息。",
                html.contains(hard),
            )
        }
        // 阈值也必须来自参数（阈值是会被复审改的数）。
        assertTrue("G1 ②: 诊断页没有把阈值渲染出来", html.contains("""q["min"]"""))
    }

    // ================= 绊线 ③：诊断页控件 ≥ 48dp =============================

    @Test
    fun theDiagnosticPageControlsClear48dp() {
        val html = page()

        // 重试按钮必须在（否则用户只能杀进程自证）。
        assertTrue(
            "G1 ③: 诊断页没有重试按钮（#retryBtn）—— CLEANUP_C1.md §4 第 4 条的老问题又回来了",
            html.contains("id=\"retryBtn\""),
        )
        assertTrue(
            "G1 ③: 重试按钮没有接到原生 retry()，点了没反应",
            html.contains("oc.retry()") || html.contains("__ocAndroidNative.retry"),
        )
        assertTrue(
            "G1 ③: 原生桥没有暴露 retry()（WebHostBridge.kt）",
            bridgeSource().contains("fun retry()"),
        )
        assertTrue(
            "G1 ③: 宿主没有实现 retryFromDiagnosticPage()（WebHostActivity.kt）",
            activitySource().contains("fun retryFromDiagnosticPage()"),
        )

        // 全部 `<button>` 共用同一条 CSS 规则，按盒模型算（1 CSS px == 1 dp，本页 meta 写明）。
        val btnRule = Regex("(?s)\\bbutton\\s*\\{(.*?)\\}").find(html)
        assertTrue("G1 ③: 诊断页里找不到 `button { … }` 规则", btnRule != null)
        val rule = btnRule!!.groupValues[1]
        val minH = Regex("""min-height:\s*([\d.]+)px""").find(rule)?.groupValues?.get(1)?.toDouble() ?: 0.0
        val bodyFont = Regex("""font:\s*([\d.]+)px\s*/\s*([\d.]+)""")
            .find(Regex("(?s)\\bbody\\s*\\{(.*?)\\}").find(html)!!.groupValues[1])
        val content = bodyFont!!.groupValues[1].toDouble() * bodyFont.groupValues[2].toDouble()
        val pad = Regex("""padding:\s*([\d.]+)px""").find(rule)?.groupValues?.get(1)?.toDouble() ?: 0.0
        val border = Regex("""border:\s*([\d.]+)px""").find(rule)?.groupValues?.get(1)?.toDouble() ?: 0.0
        val effective = maxOf(minH, content + pad * 2 + border * 2)

        val buttons = Regex("<button\\b").findAll(html).count()
        assertTrue("G1 ③: 页内一个 `<button>` 都没有（buttons=0），断言会空转", buttons > 0)
        assertTrue(
            "G1 ③: 诊断页可交互控件触摸靶只有 $effective px(=dp)（min-height=$minH，固有盒高=" +
                "${content + pad * 2 + border * 2}），必须 ≥ $minTargetPx px(=dp)。页内按钮数=$buttons。",
            effective >= minTargetPx,
        )
    }

    // ================= 绊线 ④：正常版本**不**误触发（最重要）==================

    @Test
    fun aSupportedWebviewIsNeverSentToTheDiagnosticPage() {
        // 本机实测能正常渲染官方 UI 的三个版本，一个都不能被挡在门外。
        for (good in listOf("124.0.6367.219", "149.0.7827.5")) {
            val v = WebViewPreflight.preflight(good)
            assertFalse(
                "G1 ④: WebView $good 是**实测正常**的版本（官方 UI 正常渲染），" +
                    "却被判成 ${v::class.simpleName} ⇒ 把能跑的设备挡在门外。",
                v.showsDiagnosticPage,
            )
        }
        assertFalse(
            "G1 ④: 刚够阈值的 105 被拦了（`:has()` 从 105 起支持）",
            WebViewPreflight.preflight("105.0.0.0").showsDiagnosticPage,
        )
        // 读不到版本时**必须放行**：getCurrentWebViewPackage() 理论上可能返回 null，
        // 此刻没有任何证据说它不能跑 ⇒ 宁可多挂一层看门狗，也不许拦。
        for (unknown in listOf(null, "", "   ", "unknown", "not available")) {
            val v = WebViewPreflight.preflight(unknown)
            assertFalse(
                "G1 ④: 读不到版本（versionName=$unknown）时被判成过旧 ⇒ " +
                    "把「能跑但版本读不出来」的设备挡在门外了。",
                v.showsDiagnosticPage,
            )
            assertTrue(
                "G1 ④: 读不到版本时应标记 unknownVersion 以便留运行期看门狗（$unknown）",
                (v as WebViewPreflight.Verdict.LoadOfficialUi).unknownVersion,
            )
        }
        // 阈值只挡"主版本"，别把带后缀的 UA 串判死。
        assertEquals(
            "G1 ④: 解析带 UA 尾巴的串应拿到主版本",
            124,
            WebViewPreflight.parseMajor("Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/124.0.0.0"),
        )
        assertNull(
            "G1 ④: 垃圾输入必须返回 null（而不是 0 —— 0 会被判成过旧）",
            WebViewPreflight.parseMajor("WebView 0.0.0 preloader"),
        )
    }

    // ================= 支撑断言：超时与看门狗语义 ============================

    @Test
    fun theStallTimeoutHasHeadroomOverTheWorstObservedFirstContent() {
        // 依据（research/perf/*）：FCP 最差 2856 ms、DOM 挂载最差 3063 ms。
        val worstFcp = 2_856L
        val worstDom = WebViewPreflight.WORST_OBSERVED_CONTENT_MS
        val n = WebViewPreflight.LOAD_STALL_TIMEOUT_MS
        assertTrue(
            "G1: 看门狗时限 $n ms 小于最差实测首屏内容信号（FCP $worstFcp / DOM $worstDom）" +
                "⇒ 会把正常慢启动误判成白屏（这正是最不能犯的错）。",
            n >= maxOf(worstFcp, worstDom) * 2,
        )
        assertTrue(
            "G1: 看门狗时限 $n ms 相对最差实测（$worstDom ms）余量只有 " +
                "${"%.2f".format(n.toDouble() / worstDom)}×，太贴近噪声带。",
            n.toDouble() / worstDom >= 3.0,
        )
    }

    @Test
    fun theStallWatchdogOnlyFiresWhenNothingWasEverDrawn() {
        val n = WebViewPreflight.LOAD_STALL_TIMEOUT_MS
        // 有内容 ⇒ 永远不判白屏（哪怕已经超时很久）。
        assertNull("G1: 已收到首屏内容信号时不得判白屏", WebViewPreflight.stallVerdict(n * 10, true))
        // 没内容且到点 ⇒ 判白屏。
        assertEquals(
            "G1: 无内容且超时未到时不得判白屏",
            null,
            WebViewPreflight.stallVerdict(n - 1, false),
        )
        assertEquals(
            "G1: 无内容且到点应判 stalled",
            WebViewPreflight.Reason.STALLED,
            WebViewPreflight.stallVerdict(n, false),
        )
        // 子资源失败不拦（拦了就是误伤）。
        assertNull("G1: 子资源失败不得切诊断页", WebViewPreflight.loadErrorVerdict(isForMainFrame = false))
        assertEquals(
            "G1: 主文档失败应切诊断页",
            WebViewPreflight.Reason.LOAD_ERROR,
            WebViewPreflight.loadErrorVerdict(isForMainFrame = true),
        )
        // 探针必须真的接上了，否则看门狗永远等不到解除信号。
        val src = activitySource()
        assertTrue("G1: 宿主没有安装首屏内容探针", src.contains("installPreflightProbe"))
        assertTrue(
            "G1: 宿主没有接收探针回报（onFirstContent）",
            bridgeSource().contains("fun onFirstContent"),
        )
        // 渲染进程崩溃：reason 与诊断页文案必须就位（宿主侧 override 本轮未接线，
        // 根因未核对 —— 见 WEBVIEW_PREFLIGHT.md §7 未闭环 6）。
        assertNotNull(
            "G1: `rendercrash` 这个 reason 必须存在（诊断页据此换文案）",
            WebViewPreflight.Reason.fromWire("rendercrash"),
        )
        assertTrue(
            "G1: 诊断页缺「渲染进程崩溃」的文案分支",
            page().contains("rendercrash:") && page().contains("渲染进程"),
        )
    }
}
