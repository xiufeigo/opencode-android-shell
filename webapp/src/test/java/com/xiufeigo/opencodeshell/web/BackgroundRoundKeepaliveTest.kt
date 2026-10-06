package com.xiufeigo.opencodeshell.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * UX13-P7 · **后台回合保活 SSE + 通知权限主动申请**的接线绊线（源码文本断言）。
 *
 * ## 它钉住的是什么
 *
 * P6（`research/web/UX13_P6_NOTIFY_ROOTCAUSE.md`）钉死的后台通知死锁链：
 *
 * ```
 *   onPause → 合成 pagehide → 上游 pageLifecycle 的 pagehide 监听
 *         → w() → AbortController.abort() ⇒ 4095 的在途 SSE 被掐断
 *   ⇒ 后台期间后端发的 session.execution.succeeded（HOME 后 ~2s 才到）必丢
 *   ⇒ 上游 notify 闸门（只挂终态帧）从不评估
 *   ⇒ Notification.requestPermission() 从不触发
 *   ⇒ 宿主 POST_NOTIFICATIONS launcher 从不弹窗 ⇒ 一条通知都没有
 * ```
 *
 * P7 的两把刀分别砍在链条的两节上：
 *
 *  1. **回合进行中切后台不再派 `pagehide`**（改派 `blur`）⇒ SSE 活着 ⇒ 终帧能到
 *     ⇒ 闸门被评估。空闲后台维持原 pagehide（UX8 已验收项不许变坏）。
 *  2. **首次观测到回合开始且在前台时，宿主主动申请 `POST_NOTIFICATIONS`**
 *     ⇒ 闸门从不评估的那一节也被打断 ⇒ 死锁双向拆解。
 *
 * ## 为什么断言必须落在「生产接线点」上
 *
 * 沿用 FIX_D1 的教训：单测全绿、功能 0 生效的根因永远是"没有任何生产调用方"。
 * 所以这里断言的是**分支结构**（pagehide 在 `if (roundActive)` 的 else 里、
 * blur 在 if 里、桥真的调了申请函数），而不是"文件里出现过某个字符串"。
 *
 * 特别地，`onPauseDispatchesPagehideInP6` 那种"无条件 pagehide"断言**必须**被
 * 拆掉 —— 否则本轮修复会被自己的历史绊线挡住；而拆掉它之后仍要保住
 * "空闲后台照样断流"这条 UX8 不变量（见 `idleBackgroundStillDispatchesPagehide`）。
 *
 * ## 反向验证
 *
 * 备份后回退 `WebHostActivity.kt`（`git show bc87e72:...`）与 `oc-host.js`
 * （git 对象）⇒ 本文件里的用例真红；恢复 ⇒ 全绿。原文见 P7 报告 §1d.3。
 */
class BackgroundRoundKeepaliveTest {

    private fun src(rel: String): String {
        var dir: File? = File(".").absoluteFile
        repeat(4) {
            val f = File(dir, rel)
            if (f.isFile) return f.readText(Charsets.UTF_8)
            dir = dir?.parentFile
        }
        throw AssertionError("找不到源文件: $rel")
    }

    /** 取某个成员函数体；[endSignature] 缺省时截到下一个 `override fun`。 */
    private fun bodyOf(source: String, signature: String, endSignature: String = "\n    override fun "): String {
        val start = source.indexOf(signature)
        assertTrue("源码里找不到 `$signature`", start >= 0)
        val next = source.indexOf(endSignature, start + signature.length)
        val raw = if (next < 0) source.substring(start) else source.substring(start, next)
        // 剥掉行注释：注释里的字面量会被当成调用 ⇒ 假绿。
        return raw.lineSequence()
            .map { line -> val i = line.indexOf("//"); if (i >= 0) line.substring(0, i) else line }
            .joinToString("\n")
    }

    private val js: String
        get() = src("src/main/assets/www/oc-host.js")

    private val activitySrc: String
        get() = src("src/main/java/com/xiufeigo/opencodeshell/web/WebHostActivity.kt")

    private val bridgeSrc: String
        get() = src("src/main/java/com/xiufeigo/opencodeshell/web/host/WebHostBridge.kt")

    // ---- 刀 1：onPause 的回合感知分支 --------------------------------------

    @Test
    fun onPauseIsRoundAwareNotUnconditional() {
        val body = bodyOf(activitySrc, "override fun onPause()")
        assertTrue(
            "onPause 必须查询回合活跃状态（复用 RoundActivityTracker）",
            body.contains("roundTracker.isRoundActive("),
        )
        assertTrue(
            "onPause 必须有回合感知分支（if (roundActive)）",
            body.contains("if (roundActive)"),
        )
    }

    @Test
    fun roundActiveBackgroundDoesNotDispatchPagehide() {
        val body = bodyOf(activitySrc, "override fun onPause()")
        val branchIdx = body.indexOf("if (roundActive)")
        val blurIdx = body.indexOf("dispatchPageLifecycleEvent(\"blur\")")
        assertTrue("保活分支里必须派 blur", blurIdx >= 0)
        assertTrue("blur 必须落在回合分支内", blurIdx > branchIdx)
        val elseIdx = body.indexOf("} else {", branchIdx)
        assertTrue("pagehide 之前必须出现 else（= 空闲分支）", elseIdx > branchIdx)
        val pagehideIdx = body.indexOf("dispatchPageLifecycleEvent(\"pagehide\")")
        assertTrue("空闲后台分支必须仍派 pagehide", pagehideIdx >= 0)
        assertTrue(
            "pagehide 必须落在 else 分支里（不能在回合进行中被派发）",
            pagehideIdx > elseIdx,
        )
    }

    @Test
    fun keepaliveBackgroundTracksStateForResumeSymmetry() {
        val body = bodyOf(activitySrc, "override fun onPause()")
        assertTrue(
            "保活路径必须置位 backgroundKeepAlive（onResume 靠它对称）",
            body.contains("backgroundKeepAlive = true"),
        )
        assertTrue(
            "空闲路径必须复位 backgroundKeepAlive",
            body.contains("backgroundKeepAlive = false"),
        )
    }

    @Test
    fun idleBackgroundStillDispatchesPagehide() {
        // UX8 已验收项不许变坏：空闲后台仍然断流（省电），只是不再"无条件"。
        val body = bodyOf(activitySrc, "override fun onPause()")
        val elseIdx = body.indexOf("} else {")
        assertTrue("onPause 必须保留空闲（else）分支", elseIdx >= 0)
        val tail = body.substring(elseIdx)
        assertTrue(
            "空闲后台必须派 pagehide",
            tail.contains("dispatchPageLifecycleEvent(\"pagehide\")"),
        )
    }

    @Test
    fun backgroundRoundEndReleasesTheStream() {
        // 回合在后台结束时补派 pagehide（省电收口）—— 省电语义不许丢。
        val body = bodyOf(
            activitySrc,
            "private val backgroundRoundRelease",
            endSignature = "\n    private fun ",
        )
        assertTrue(
            "保活路径必须排一个收口 runnable",
            activitySrc.contains("webView.postDelayed(backgroundRoundRelease,"),
        )
        assertTrue(
            "收口 runnable 必须复查回合是否已结束",
            body.contains("roundTracker.isRoundActive("),
        )
        assertTrue(
            "回合结束后必须补派 pagehide 断 SSE",
            body.contains("dispatchPageLifecycleEvent(\"pagehide\")"),
        )
    }

    @Test
    fun onResumeIsSymmetricWithThePausePathTaken() {
        val body = bodyOf(activitySrc, "override fun onResume()")
        assertTrue(
            "onResume 必须按 onPause 走过的路径分支",
            body.contains("if (backgroundKeepAlive)"),
        )
        val keepIdx = body.indexOf("if (backgroundKeepAlive)")
        val focusIdx = body.indexOf("dispatchPageLifecycleEvent(\"focus\")")
        val showIdx = body.indexOf("dispatchPageLifecycleEvent(\"pageshow\")")
        assertTrue("保活回前台必须派 focus", focusIdx > keepIdx)
        assertTrue(
            "focus 必须在 pageshow 之前（保活路径没断过流，不该再谎称 pageshow）",
            focusIdx < showIdx,
        )
    }

    @Test
    fun blurAndFocusAreInertForUpstreamConnection() {
        // 读 bundle 得到的设计事实（见 P7 报告 §0.1）：上游 pageLifecycle 只注册
        // pagehide / pageshow / online / visibilitychange 四个监听器，**不监听 focus/blur**。
        // 所以派 blur 保活时，SSE 状态机一根汗毛都不会动 —— 这是本轮修复成立的前提。
        // 这里把该前提钉成绊线：若日后有人改成"派 blur 来断流"，本用例会红。
        val body = bodyOf(activitySrc, "private fun dispatchPageLifecycleEvent(")
        assertTrue(
            "派发函数必须仍是纯 window 事件派发",
            body.contains("window.dispatchEvent(new Event("),
        )
        assertFalse(
            "生命周期派发不得触碰凭据",
            body.contains("Authorization") || body.contains("password"),
        )
    }

    // ---- 刀 2：回合开始时主动申请通知权限 ----------------------------------

    @Test
    fun proactivePermissionRequestExistsAndIsForegroundGated() {
        val body = bodyOf(
            activitySrc,
            "fun requestNotificationPermissionOnRoundStart()",
            endSignature = "\n    override fun ",
        )
        assertTrue(
            "主动申请必须只在**前台**发生",
            body.contains("if (!isForeground()) return"),
        )
        assertTrue(
            "已申请过不得重复弹（每回合弹一次系统框不可接受）",
            body.contains("if (notificationPermissionRequested) return"),
        )
        assertTrue(
            "必须先查真实授权状态再决定要不要弹",
            body.contains("checkSelfPermission(") && body.contains("POST_NOTIFICATIONS"),
        )
    }

    @Test
    fun proactivePermissionRequestReusesExistingLauncherChain() {
        val body = bodyOf(
            activitySrc,
            "fun requestNotificationPermissionOnRoundStart()",
            endSignature = "\n    override fun ",
        )
        assertTrue(
            "必须复用既有 requestNotificationPermission()，不许新造申请链",
            body.contains("requestNotificationPermission()"),
        )
        val existing = bodyOf(activitySrc, "fun requestNotificationPermission()")
        assertTrue(
            "既有链必须仍然走同一个 launcher",
            existing.contains("notificationPermission.launch("),
        )
    }

    @Test
    fun bridgeTriggersPermissionOnRoundStart() {
        val body = bodyOf(bridgeSrc, "fun setKeepScreenOn(on: Boolean)")
        assertTrue(
            "回合开始（on=true）必须触发主动申请",
            body.contains("requestNotificationPermissionOnRoundStart()"),
        )
        val startIdx = body.indexOf("tracker.onRoundStart(")
        val askIdx = body.indexOf("requestNotificationPermissionOnRoundStart()")
        assertTrue(
            "申请必须发生在回合开始之后（生产接线点，不是死代码）",
            askIdx > startIdx,
        )
        assertTrue(
            "必须切回 UI 线程（@JavascriptInterface 是 JS 线程）",
            body.contains("runOnUiThread"),
        )
    }

    // ---- 刀 3：传感器日志补 origin/status（消灭 P5/P6 误读面） --------------

    @Test
    fun eventStreamLogCarriesOriginAndStatus() {
        val body = bodyOf(js, "function teeEventStream(resp, path, absUrl)")
        assertTrue(
            "OPEN 日志必须带 origin 与 status（P6 §B.4：缺这两项时 404 空转与真实流无法区分）",
            body.contains("event stream OPEN #") &&
                body.contains("origin") &&
                body.contains("status=") &&
                body.contains("originOf("),
        )
        assertTrue(
            "CLOSE 日志必须带 origin（否则前后两半日志对不上是哪条流）",
            body.contains("event stream CLOSE #") && body.contains("origin"),
        )
    }

    @Test
    fun non2xxEventStreamsAreLoggedSeparately() {
        val body = bodyOf(js, "function teeEventStream(resp, path, absUrl)")
        assertTrue(
            "EVENT_PATH_RE 命中但非 2xx 时必须单独打一行 NON2XX",
            body.contains("event stream NON2XX #"),
        )
        assertTrue(
            "NON2XX 判定必须是 2xx 区间（不得把 undefined/0 也算进来）",
            body.contains("status < 200") && body.contains("status >= 300"),
        )
    }

    @Test
    fun teeStaysByteTransparent() {
        // 行为红线：日志增强**不得**改变 tee 的透传语义 —— 一个字节不截留。
        val wrapper = bodyOf(js, "window.fetch = function")
        assertTrue(
            "fetch 包装必须仍然原样返回 resp",
            wrapper.contains("return resp;"),
        )
        assertTrue(
            "tee 仍走 pipeThrough 透明流",
            js.contains("pipeThrough"),
        )
        val tee = bodyOf(js, "function teeEventStream(resp, path, absUrl)")
        assertFalse(
            "tee 内不得对 body 做读取/替换（会截留字节）",
            tee.contains("resp.text()") || tee.contains("resp.json()"),
        )
    }

    @Test
    fun fetchWrapperPassesAbsoluteUrlToTee() {
        // origin 必须从绝对 URL 推导；EVENT_PATH_RE 匹配的 `path` 只是 pathname。
        val wrapper = bodyOf(js, "window.fetch = function")
        assertTrue(
            "fetch 包装必须把绝对 url 传给 tee（否则 origin 只能退化成页面 origin）",
            wrapper.contains("teeEventStream(resp, path, url)"),
        )
    }
}