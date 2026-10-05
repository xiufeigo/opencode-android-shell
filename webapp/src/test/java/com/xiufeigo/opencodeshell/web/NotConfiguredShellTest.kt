package com.xiufeigo.opencodeshell.web

import com.xiufeigo.opencodeshell.web.host.LaunchGuard
import com.xiufeigo.opencodeshell.web.host.NotConfiguredShell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * UX1 · 任务2 · **「不再弹开场对话框 + 未配置即全灰」绊线**。
 *
 * ## 派单要求的可断言量
 *
 * > 未配置服务器 ⇒ **不弹对话框** + **功能呈灰/不可用** + **代理未起**，
 * > 同时 **F21 的安全性质仍保住**（空口令 ⇒ 绝不启动代理）。
 *
 * ## 为什么必须做源码接线断言（两层）
 *
 * 纯逻辑层（[NotConfiguredShell.html] 的内容）绿，只证明**这一页画对了**；
 * 它不证明**启动链真的走的是这一页**。而本任务的风险恰恰全在接线：
 * 只要有人把 `startProxyAndLoad` 的空口令分支改回 `showSetupDialog`，
 * 或者把 `showNotConfiguredShell` 挪到起代理**之后**，纯逻辑层**照样全绿**。
 *
 * 所以本文件分两层钉死：
 *  1. **纯逻辑层** —— 灰态页的内容：灰项、不可点、不泄露、按钮可自救；
 *  2. **接线层** —— 启动链空口令分支走灰态页**且在起代理之前 return**，
 *     同时 [LaunchGuard] 那道闸门**原样还在**（不许被本轮"顺手删掉"）。
 *
 * ## 反向验证
 *
 * 把空口令分支改回 `showSetupDialog(backend)` ⇒ [noDialogOnUnconfiguredFirstRun] 必红；
 * 把 `showNotConfiguredShell()` 那行删掉 ⇒ 同上；
 * 把 F21 的闸门整段删掉 ⇒ [f21GateIsStillPresentInLaunch] 必红。
 * 实测记录见 `research/web/UX1_IMPROVEMENTS.md`。
 */
class NotConfiguredShellTest {

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

    private val html: String = NotConfiguredShell.html()

    // ---- 第 1 层：纯逻辑（灰态页本身） -------------------------------------

    @Test
    fun unconfiguredShellShowsEveryProductEntryAsGray() {
        // 派单点名的三项（命令面板截图里的措辞）必须都在，且都在灰态清单里。
        listOf("新建会话", "切换终端", "切换审查").forEach { label ->
            assertTrue(
                "灰态页必须含入口「$label」——它对应产品截图里的命令项",
                NotConfiguredShell.GRAYED_ENTRIES.any { it.label == label },
            )
        }
    }

    @Test
    fun everyGrayedEntryIsRenderedWithTheDisabledAttribute() {
        NotConfiguredShell.GRAYED_ENTRIES.forEach { e ->
            assertTrue(
                "入口「${e.label}」必须带 ${NotConfiguredShell.DISABLED_ATTR}：灰不是变淡，是真的不可用",
                html.contains("data-entry=\"${e.id}\"") &&
                    html.contains(NotConfiguredShell.DISABLED_ATTR),
            )
        }
    }

    @Test
    fun grayedEntriesCannotBeTapped() {
        // 派单要求"不能看起来能用"：光改颜色而仍能点，就是**看起来能用**。
        // 因此必须有真的点击拦截，而不只是 CSS 的 cursor:not-allowed。
        assertTrue(
            "灰项必须被真的拦住点击（addEventListener + preventDefault）",
            html.contains("addEventListener('click'") && html.contains("e.preventDefault()"),
        )
        assertTrue("灰项光标必须是不许点的形状", html.contains("cursor:not-allowed"))
    }

    @Test
    fun unconfiguredShellExplainsWhyEverythingIsGray() {
        // 灰态本身不能成为谜：必须有一句话说清"为什么灰、去哪改"。
        assertTrue("必须有一句标题说明未连接", html.contains(NotConfiguredShell.HEADING))
        assertTrue("必须有一句说明告诉用户去设置", html.contains(NotConfiguredShell.HINT))
    }

    @Test
    fun unconfiguredShellLeaksNoInternalInformation() {
        // 沿用 ConnectErrorPage 规矩 1：不出现 scheme://host:port、不出现端口号、不回显凭据。
        // ⚠ 刻意**不**把 "opencode" 列为泄露物：它是本 app 自己的公开名字，
        //   `<title>opencode</title>`` 恰恰是应该出现的东西（首版把它列进来导致假红）。
        listOf("127.0.0.1", "localhost", "http://", "https://", "4095", "Authorization", "serverPassword")
            .forEach { leak ->
                assertFalse(
                    "灰态页绝不能出现内部信息「$leak」",
                    html.contains(leak),
                )
            }
    }

    @Test
    fun unconfiguredShellHasAWorkingWayOut() {
        // 派单要求"给一个显式的去设置入口"；且必须有一条**不依赖 JS 桥**的路。
        // 设备实测：loadDataWithBaseURL 页面上 __ocAndroid 不可用，桥路会退化成
        // location.reload() ⇒ 整页 ERR_NAME_NOT_RESOLVED（不可自救）。见 UX1 报告 §2.5。
        assertTrue("必须有「去设置服务器」按钮", html.contains(NotConfiguredShell.SETUP_BUTTON_LABEL))
        assertTrue(
            "按钮必须是**真的 <a href> 走宿主私有 scheme**（这是桥不可用时唯一可靠的路）",
            html.contains("href=\"${NotConfiguredShell.SETUP_HREF}\""),
        )
        assertTrue("host 必须拦这个 scheme", activitySrc.contains("INTERNAL_SCHEME"))
        assertTrue(
            "scheme 命中后必须开配置对话框",
            activitySrc.contains("shouldOverrideUrlLoading") &&
                activitySrc.contains("openSetupFromBlockedPage()"),
        )
        // 绝不能再有 location.reload() 兜底 —— 那是本条缺陷的成因。
        assertFalse(
            "绝不能用 location.reload() 兜底（会退化成 ERR_NAME_NOT_RESOLVED 错误页）",
            NotConfiguredShell.OPEN_SETUP_JS.contains("location.reload"),
        )
    }

    @Test
    fun setupEntryMeetsTheTouchTargetFloor() {
        // 与 ConnectErrorPage / SetupDialogMetrics 同源的 48dp 硬要求。
        assertTrue(
            "「去设置服务器」按钮必须达到 48dp 触摸靶",
            html.contains("min-height:${NotConfiguredShell.MIN_TOUCH_TARGET_CSS_PX}px"),
        )
        assertEquals(48, NotConfiguredShell.MIN_TOUCH_TARGET_CSS_PX)
    }

    @Test
    fun unconfiguredShellMakesNoBackendRequests() {
        // 未配置态不得有任何指向后端的加载：页面是**静态**的。
        listOf("fetch(", "XMLHttpRequest", "WebSocket", "EventSource")
            .forEach { api ->
                assertFalse("灰态页不得发起任何后端请求（发现 $api）", html.contains(api))
            }
    }

    // ---- 第 2 层：接线（启动链真的走这一页，且在起代理之前） ---------------

    @Test
    fun noDialogOnUnconfiguredFirstRun() {
        // 本任务的核心可断言量：首次启动 + 未配置 ⇒ **不弹对话框**。
        val branch = activitySrc.substringAfter("private fun startProxyAndLoad")
            .substringBefore("probeThenLaunch(backend, password)")
        assertTrue(
            "空口令分支必须走灰态外壳 showNotConfiguredShell()",
            branch.contains("showNotConfiguredShell()"),
        )
        assertFalse(
            "空口令分支**不得**再弹 showSetupDialog —— 这正是本任务要改掉的那件事",
            branch.contains("showSetupDialog(backend)"),
        )
    }

    @Test
    fun unconfiguredBranchReturnsBeforeAnyProxyStart() {
        // 「代理未起」是派单点名的证据之一：用源码顺序钉死 —— 灰态分支必须先 return。
        val branch = activitySrc.substringAfter("private fun startProxyAndLoad")
            .substringBefore("probeThenLaunch(backend, password)")
        // ⚠ 用正则找**语句** `return`，不用 `indexOf("return")`：
        // 本分支的注释里就出现了英文单词 return（"在起代理之前就 return 了"），
        // 朴素 indexOf 会先命中注释、得出"return 早于 showNotConfiguredShell"的假红。
        val returnAfterShell = Regex("""showNotConfiguredShell\(\)\s*;?\s*return\b""")
        assertTrue("分支里必须调 showNotConfiguredShell()", branch.contains("showNotConfiguredShell()"))
        assertTrue(
            "showNotConfiguredShell() 之后必须**立刻** return（不许落到起代理那条路上）",
            returnAfterShell.containsMatchIn(branch),
        )
        assertFalse(
            "空口令分支内不得出现 probeThenLaunch（探不通也会起代理的入口）",
            branch.contains("probeThenLaunch"),
        )
    }

    @Test
    fun notConfiguredShellMethodNeverStartsAProxy() {
        // 灰态页面的加载方法里，**不许**出现 LoopbackProxy / loadUrl / launch。
        val method = activitySrc.substringAfter("private fun showNotConfiguredShell()")
            .substringBefore("private fun showDiagnostic")
        assertFalse("灰态加载方法不得起回环代理", method.contains("LoopbackProxy"))
        assertFalse("灰态加载方法不得 loadUrl 官方 UI", method.contains("loadUrl"))
        assertFalse("灰态加载方法不得调 launch()", method.contains("launch("))
        assertTrue("灰态必须用 loadDataWithBaseURL 挂宿主 origin", method.contains("loadDataWithBaseURL"))
    }

    // ---- 第 3 层：F21 安全性质仍保住（本轮不许动） -------------------------

    @Test
    fun f21GateIsStillPresentInLaunch() {
        // 本轮把首启路径改了，**但绝不能顺手删掉 F21 那道闸门**：
        // 四个出口（配置对话框两个 + 探测两分支 + 诊断页重试）仍汇进 launch()。
        val launchFn = activitySrc.substringAfter("private fun launch(backend: String)")
            .substringBefore("private fun prewarmProxyAsync")
        assertTrue(
            "F21 闸门必须仍在 launch() 里 —— 本轮不许动它",
            launchFn.contains("LaunchGuard.mayLaunch"),
        )
        assertTrue(
            "launch 被拦时必须走 F21 被挡页",
            launchFn.contains("showNoPasswordBlockedPage()"),
        )
    }

    @Test
    fun f21GateStillStandsBeforeProxyStart() {
        val launchFn = activitySrc.substringAfter("private fun launch(backend: String)")
            .substringBefore("private fun prewarmProxyAsync")
        val gateIdx = launchFn.indexOf("LaunchGuard.mayLaunch")
        val proxyIdx = launchFn.indexOf("LoopbackProxy")
        assertTrue("launch() 里必须还有闸门", gateIdx >= 0)
        assertTrue("launch() 里必须还有起代理", proxyIdx >= 0)
        assertTrue(
            "闸门必须在起代理**之前**（顺序反了就是先放行再失败）",
            gateIdx < proxyIdx,
        )
    }

    @Test
    fun emptyPasswordStillCannotLaunch() {
        // 回归 F21 的判据本身：这条测试**不在**本轮改动范围，但必须仍然绿。
        assertFalse("空口令仍不得进入已鉴权路径", LaunchGuard.mayLaunch(""))
        assertFalse("null 口令仍不得进入已鉴权路径", LaunchGuard.mayLaunch(null))
    }

    @Test
    fun setupDialogIsStillReachableForExplicitConfiguration() {
        // 灰态不等于"配置界面被删了"：用户点「去设置服务器」必须还能回到配置对话框。
        // 这条是"显式去设置入口"能真正落地的前提。
        val bridgeSrc = src("src/main/java/com/xiufeigo/opencodeshell/web/host/WebHostBridge.kt")
        assertTrue("openSetup 桥方法必须还在", bridgeSrc.contains("fun openSetup()"))
        assertTrue(
            "openSetup 桥必须落到配置对话框",
            activitySrc.contains("fun openSetupFromBlockedPage()") &&
                activitySrc.contains("showSetupDialog(backend)"),
        )
    }

    @Test
    fun configuredPathStillUnchanged() {
        // 正常路径回归护栏：口令非空时仍然走 probeThenLaunch（官方 UI 完整加载）。
        val branch = activitySrc.substringAfter("private fun startProxyAndLoad")
        assertTrue("口令非空时仍必须探一次再加载", branch.contains("probeThenLaunch(backend, password)"))
    }
}
