package com.xiufeigo.opencodeshell.web

import com.xiufeigo.opencodeshell.web.host.ConnectErrorPage
import com.xiufeigo.opencodeshell.web.host.LaunchGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * F21 · **「空口令时不得进入已鉴权路径」绊线**。
 *
 * ## 为什么单测不够、还要做源码接线断言
 *
 * 纯逻辑（[LaunchGuard.mayLaunch]）绿，**只证明判空这件事本身写对了**，
 * 不证明它**被接在了真正的入口上**。而本缺陷恰恰是"接线"型缺陷：
 * `startProxyAndLoad` 一直有判空、一直是绿的，可真实的洞在
 * 「先不配置」按钮和「提交空口令」这两条**旁路**上。
 *
 * 所以这里分两层钉死：
 *  1. **纯逻辑层** —— 判据本身对不对（`null`/`""` 拒绝，非空放行）。
 *  2. **接线层** —— [LaunchGuard.mayLaunch] 必须出现在 `launch()` 里，
 *     且**必须出现在起代理 / loadUrl 之前**（顺序错了就等于"先放行再拦"）。
 *
 * ## 可断言的量（派单要求的那一条）
 *
 * > **口令为空时不得进入已鉴权路径** —— 即
 * > `mayLaunch("") == false` **且** `launch()` 在起代理之前必查这个闸门。
 *
 * ## 反向验证
 *
 * 把 `launch()` 顶上的闸门撤掉（或把它挪到起代理之后），
 * [launchGuardsBeforeProxyStart] / [launchChecksTheGateBeforeLoadingUi] 必红；
 * 把 [LaunchGuard.mayLaunch] 改成恒真，[emptyPasswordIsRejected] 必红。
 * 实测记录见 `research/web/FIX_F21_EMPTYPW.md` §3。
 */
class EmptyPasswordLaunchGateTest {

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

    // ---- 第 1 层：纯逻辑 ---------------------------------------------------

    @Test
    fun emptyPasswordIsRejected() {
        assertFalse(
            "空口令必须被拒绝进入已鉴权路径（这是本轮的核心可断言量）",
            LaunchGuard.mayLaunch(""),
        )
    }

    @Test
    fun nullPasswordIsRejected() {
        assertFalse(
            "没有播种口令（首次安装/键被删）同样必须被拒绝",
            LaunchGuard.mayLaunch(null),
        )
    }

    @Test
    fun nonEmptyPasswordIsAllowed() {
        // 这一条是**正常路径的回归护栏**：闸门不能把"有口令"也一起拦掉。
        assertTrue(
            "非空口令必须放行，否则正常使用会被这个安全闸门挡住",
            LaunchGuard.mayLaunch("opencode"),
        )
    }

    @Test
    fun whitespacePasswordIsNotSilentlyTrimmed() {
        // 刻意用 isNotEmpty 而非 isNotBlank：口令含首尾空白时由后端裁决成败，
        // 宿主不替它"修正"，免得出现"界面上输了却不生效"的新谜团。
        assertTrue(
            "含空白的口令是真实口令，宿主不应本地裁剪",
            LaunchGuard.mayLaunch("  pw  "),
        )
    }

    // ---- 第 2 层：接线（纯逻辑绿也证明不了的那一半）------------------------

    @Test
    fun launchIsGuardedByTheGate() {
        assertTrue(
            "launch() 必须接上 LaunchGuard 闸门 —— 否则空口令仍会起代理、加载官方 UI",
            activitySrc.contains("LaunchGuard.mayLaunch("),
        )
    }

    @Test
    fun launchGuardsBeforeProxyStart() {
        val body = launchBody()
        val gate = body.indexOf("LaunchGuard.mayLaunch(")
        val proxy = body.indexOf("LoopbackProxy(")
        assertTrue("launch() 里找不到闸门调用", gate >= 0)
        assertTrue("launch() 里找不到起代理的代码", proxy >= 0)
        assertTrue(
            "闸门必须**在起代理之前**：后置就等于'先起代理再拦'，" +
                "仍存在一个能连、能用的无认证代理窗口",
            gate < proxy,
        )
    }

    @Test
    fun launchChecksTheGateBeforeLoadingUi() {
        val body = launchBody()
        val gate = body.indexOf("LaunchGuard.mayLaunch(")
        val loadUrl = body.indexOf("webView.loadUrl(url)")
        assertTrue("launch() 里找不到闸门调用", gate >= 0)
        assertTrue("launch() 里找不到 loadUrl", loadUrl >= 0)
        assertTrue(
            "闸门必须**在 loadUrl 之前** —— 否则官方 UI 已经被摆出来了，" +
                "而本缺陷的实质正是'看起来正常掩盖没有认证'",
            gate < loadUrl,
        )
    }

    @Test
    fun prewarmIsGatedToo() {
        // 预热会真的 `start()` 一个**已经在监听**的代理。若空口令时仍放它过去，
        // 即使 launch() 随后被挡，设备上也已经有一个无认证回环代理在监听。
        val body = prewarmBody()
        val gate = body.indexOf("LaunchGuard.mayLaunch(")
        val proxy = body.indexOf("LoopbackProxy(")
        assertTrue("prewarmProxyAsync() 里找不到闸门调用", gate >= 0)
        assertTrue("prewarmProxyAsync() 里找不到起代理的代码", proxy >= 0)
        assertTrue(
            "预热路径的闸门必须**在起代理之前** —— 后置就等于先开一个无认证监听端口",
            gate < proxy,
        )
    }

    @Test
    fun everyProxyStartSiteIsGated() {
        // 不变量：**WebHostActivity 里每一处 `LoopbackProxy(` 都必须处在某道闸门之后**。
        // 当前共 2 处（launch / prewarm），两处都接了 [LaunchGuard.mayLaunch]。
        // 若有人新增第三个起代理点却忘了接闸门，本用例必红。
        val src = activitySrc
        val sites = Regex("LoopbackProxy\\(").findAll(src).count()
        assertEquals(
            "起代理点数量变了（现在 $sites 处）：新增点必须自己接上 LaunchGuard 闸门",
            2,
            sites,
        )
        val gates = Regex("LaunchGuard\\.mayLaunch\\(").findAll(src).count()
        assertEquals(
            "闸门数量与起代理点数量不匹配（起代理 $sites 处 / 闸门 $gates 处）",
            sites,
            gates,
        )
    }

    // ---- 第 3 层：被挡态本身必须"说真话" ----------------------------------

    @Test
    fun blockedPageTellsTheUserAuthIsMissing() {
        val html = LaunchGuard.blockedHtml()
        assertTrue(
            "被挡页必须**明说**没有身份验证 —— 不能让'看起来正常'掩盖'没有认证'",
            html.contains("没有身份验证"),
        )
    }

    @Test
    fun blockedPageOffersAWayOut() {
        // F5 的硬规矩：任何错误页都必须能自救。这里走的是 openSetup 而不是
        // location.reload()（后者只会把这页再刷一遍）。
        assertTrue(
            "被挡页必须有回配置对话框的出口（走 openSetup，不是 reload）",
            LaunchGuard.blockedHtml().contains("openSetup"),
        )
    }

    @Test
    fun blockedPageNeverEchoesThePasswordOrInternalAddress() {
        val html = LaunchGuard.blockedHtml()
        assertFalse(
            "被挡页绝不能出现 host:port 形态的内部地址",
            Regex("""\b\d{1,3}(\.\d{1,3}){3}:\d{2,5}\b""").containsMatchIn(html),
        )
        assertFalse(
            "被挡页绝不能出现 scheme://host 形态的内部地址",
            Regex("""(?i)\b[a-z][a-z0-3+.\-]*://[^\s"'<>)\]]+""").containsMatchIn(html),
        )
    }

    @Test
    fun blockedPageIsClassifiedAsNoPassword() {
        assertEquals(
            ConnectErrorPage.Kind.NO_PASSWORD,
            LaunchGuard.KIND,
        )
        assertEquals(
            "分类的标题与闸门文案必须同源（避免原生/网页各说一套）",
            LaunchGuard.HEADING_NO_PASSWORD,
            ConnectErrorPage.Kind.NO_PASSWORD.heading,
        )
    }

    @Test
    fun blockedPageRetryButtonIsAtLeast48px() {
        // 与 F2/F5 的 48dp 触摸靶硬要求同源；被挡页是用户唯一能自救的地方，
        // 按钮点不到就等于没有出口。
        assertTrue(
            "被挡页的重试按钮必须 ≥48px（ConnectErrorPage 的硬要求）",
            LaunchGuard.blockedHtml().contains("min-height:48px"),
        )
    }

    @Test
    fun blockedPageButtonLabelMatchesItsAction() {
        // 设备实测首版：按钮写着"重试"、动作却是"回配置对话框"。
        // 在这一页上**每个字都必须是真话** —— 用户按一个与名字不符的按钮，
        // 正是"看起来正常掩盖真实状态"那类缺陷的同一种病。
        val html = LaunchGuard.blockedHtml()
        assertTrue(
            "被挡页按钮文案必须是「${LaunchGuard.BUTTON_LABEL}」（与 openSetup 动作一致）",
            html.contains(">${LaunchGuard.BUTTON_LABEL}<"),
        )
        assertFalse(
            "被挡页的按钮不能写「重试」—— 它的动作不是重试，是去设置密码",
            html.contains(">${ConnectErrorPage.DEFAULT_BUTTON_LABEL}<"),
        )
    }

    @Test
    fun defaultButtonLabelIsUnchangedForOtherErrorPages() {
        // 回归护栏：F21 只改被挡页的文案，前三类错误页必须**逐字不变**。
        assertEquals("重试", ConnectErrorPage.DEFAULT_BUTTON_LABEL)
        assertTrue(
            "Unreachable 错误页的按钮文案不得被本轮改动波及",
            ConnectErrorPage.unreachableHtml().contains(">${ConnectErrorPage.DEFAULT_BUTTON_LABEL}<"),
        )
        assertTrue(
            "AuthFailed 错误页的按钮文案不得被本轮改动波及",
            ConnectErrorPage.authFailedHtml().contains(">${ConnectErrorPage.DEFAULT_BUTTON_LABEL}<"),
        )
    }

    /** 取出 `launch(...)` 的函数体（到下一个顶层 `private`/注释块前的实现结束）。 */
    private fun launchBody(): String {
        val src = activitySrc
        val start = src.indexOf("private fun launch(")
        assertTrue("源码里找不到 private fun launch(", start >= 0)
        // 保守地取后面 4000 字符：本函数远小于此，且不会碰到下一个成员。
        return src.substring(start, minOf(src.length, start + 4000))
    }

    /** 取出 `prewarmProxyAsync(...)` 的函数体。 */
    private fun prewarmBody(): String {
        val src = activitySrc
        val start = src.indexOf("private fun prewarmProxyAsync(")
        assertTrue("源码里找不到 private fun prewarmProxyAsync(", start >= 0)
        return src.substring(start, minOf(src.length, start + 3000))
    }
}
