package com.xiufeigo.opencodeshell.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * UX8 · **D1 回合常亮 + P6 后台断 SSE 的接线绊线**（源码文本断言）。
 *
 * ## 它钉住的是什么
 *
 * UX6 删回环代理后这两条能力失效（UX6_OFFICIAL_ENTRY.md §1.2 如实记录）。
 * UX8 在**宿主侧**重新接线，**不许**靠重建代理：
 *
 *  1. **常亮驱动**：注入层 `oc-host.js` 新增回合电源传感器
 *     （`installRoundPowerSensor`）——包装 `window.fetch`，观测
 *     "发消息 POST"（乐观置位）与 `/api/event` SSE 上的
 *     `session.execution.started/succeeded/failed/interrupted` 帧
 *     （**线上实测验证过的标记**，research/web/ux8/wire_event.txt；
 *     上游自己判 running/idle 也是这四个事件，
 *     `_upstream/packages/client/src/solid/data.ts:1009-1011`、`:1025-1028`），
 *     调既有桥 `oc.setKeepScreenOn` ⇒ 宿主 `RoundActivityTracker` ⇒ 落 flag。
 *  2. **后台断 SSE**：`WebHostActivity.onPause/onResume` 合成派发
 *     pagehide/pageshow，驱动上游 client 自有的 pageLifecycle 机制
 *     （`_upstream/packages/client/src/solid/connection.ts:219-240`：
 *     pagehide → stop()（abort 事件流）；pageshow → start()（重连））。
 *
 * ## 为什么这些断言必须存在（对应踩过的坑）
 *
 *  - **"flag 必须真的被置起"**：FIX_D1 的教训是单测全绿、功能 0 生效
 *    （没有任何生产调用方）。所以这里断言**生产接线点**存在且接在
 *    `oc.setKeepScreenOn` 上，而不是只断言"有某个函数定义"。
 *  - **标记必须是线上实测过的**：真后端（127.0.0.1:4095）整轮抓包
 *    **没有** `session.status` 帧 ⇒ 若传感器只认旧标记，"回合结束释放"
 *    会再次落空（释放只做一半的回归）。所以主判据断言为
 *    `session.execution.*` 四个标记；`session.status` 只允许作为兜底出现。
 *  - **后台断开必须是"主动"的**：断言 onPause 里存在 pagehide 派发
 *    （上游机制不会自己触发 —— WebView 切后台不发 pagehide）。
 *
 * ## 反向验证
 *
 * 撤掉任一接线点（传感器的 `oc.setKeepScreenOn` 调用、onPause 的
 * pagehide 派发、onResume 的 pageshow 派发、任一 execution 标记），
 * 对应用例必须**真红**。已实跑确认（见 research/web/UX8_RESTORE_KEEPAWAKE_SSE.md）。
 */
class Ux8KeepAwakeSseTripwireTest {

    private fun src(rel: String): String {
        var dir: File? = File(".").absoluteFile
        repeat(4) {
            val f = File(dir, rel)
            if (f.isFile) return f.readText(Charsets.UTF_8)
            dir = dir?.parentFile
        }
        throw AssertionError("找不到源文件: $rel")
    }

    private fun bodyOf(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("源码里找不到 `$signature`", start >= 0)
        val next = source.indexOf("\n    override fun ", start + signature.length)
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

    /** 传感器函数体（从定义到下一个顶层函数）。 */
    private fun sensorBody(): String {
        val src = js
        val idx = src.indexOf("function installRoundPowerSensor()")
        assertTrue("oc-host.js 里找不到 installRoundPowerSensor", idx >= 0)
        return src.substring(idx, minOf(idx + 9000, src.length))
    }

    // ---- 接线点 1：常亮驱动链（传感器 → 既有桥 → 跟踪器 → flag） -----------

    @Test
    fun sensorIsInstalledFromDocumentStartScript() {
        assertTrue(
            "installRoundPowerSensor 必须被调用（只定义不装 = 0 生效）",
            js.contains("installRoundPowerSensor();"),
        )
    }

    @Test
    fun sensorDrivesExistingKeepScreenOnBridge() {
        assertTrue(
            "传感器必须调 oc.setKeepScreenOn(（既有桥：前台校验/超时兜底/落 flag 都在宿主）",
            sensorBody().contains("oc.setKeepScreenOn("),
        )
    }

    @Test
    fun sensorKeysOnWireVerifiedRoundStartMarkers() {
        val body = sensorBody()
        assertTrue(
            "必须认 session.execution.started（线上实测：回合开始权威信号）",
            body.contains("\"session.execution.started\""),
        )
        assertTrue(
            "必须认 session.execution.succeeded（线上实测：回合成功收口）",
            body.contains("\"session.execution.succeeded\""),
        )
    }

    @Test
    fun sensorKeysOnWireVerifiedRoundEndMarkers() {
        val body = sensorBody()
        assertTrue(
            "必须认 session.execution.failed（回合失败也是收口）",
            body.contains("\"session.execution.failed\""),
        )
        assertTrue(
            "必须认 session.execution.interrupted（打断/关机会话也是收口）",
            body.contains("\"session.execution.interrupted\""),
        )
    }

    @Test
    fun sensorObservesRoundStartPost() {
        val body = sensorBody()
        assertTrue(
            "必须拦截发消息 POST（prompt 端点）做乐观置位",
            body.contains("prompt") && body.contains("ROUND_POST_RE"),
        )
    }

    @Test
    fun sensorWrapsFetch() {
        assertTrue(
            "必须包装 window.fetch（早于上游脚本拿到全局引用）",
            sensorBody().contains("window.fetch = function"),
        )
    }

    @Test
    fun sensorTeesEventStreamAndScansFrames() {
        val body = sensorBody()
        assertTrue(
            "必须 tee /api/event 响应体（pipeThrough）",
            body.contains("/api/event") && body.contains("pipeThrough"),
        )
        assertTrue(
            "必须按 SSE 帧解析（data: 行）",
            body.contains("data:"),
        )
    }

    @Test
    fun sensorTracksBusySessionsPerSessionId() {
        // 多会话：任一会话在跑 ⇒ 常亮；全部收口 ⇒ 释放。
        assertTrue(
            "必须按 sessionID 维护 busy 集合",
            sensorBody().contains("sessionID"),
        )
    }

    // ---- 接线点 2：后台断 SSE / 回前台重建（上游 pageLifecycle 的扳机） -----

    @Test
    fun onPauseDispatchesPagehide() {
        val body = bodyOf(activitySrc, "override fun onPause()")
        assertTrue(
            "onPause 必须合成派发 pagehide（WebView 不发；上游 stop() 靠它触发）",
            body.contains("dispatchPageLifecycleEvent(\"pagehide\")"),
        )
    }

    @Test
    fun onResumeDispatchesPageshow() {
        val body = bodyOf(activitySrc, "override fun onResume()")
        assertTrue(
            "onResume 必须合成派发 pageshow（上游 start() 靠它重建 SSE）",
            body.contains("dispatchPageLifecycleEvent(\"pageshow\")"),
        )
    }

    @Test
    fun dispatcherReallyBuildsWindowDispatchEvent() {
        val src = activitySrc
        val idx = src.indexOf("private fun dispatchPageLifecycleEvent(")
        assertTrue("找不到 dispatchPageLifecycleEvent 实现", idx >= 0)
        val body = src.substring(idx, minOf(idx + 700, src.length))
        assertTrue(
            "派发必须是 window.dispatchEvent(new Event(...))",
            body.contains("window.dispatchEvent(new Event("),
        )
    }

    @Test
    fun lifecycleDispatchIsHostSideOnly() {
        // 红线自检的一部分：pagehide/pageshow 合成事件是纯事件名，不得携带/注入凭据。
        val src = activitySrc
        val idx = src.indexOf("private fun dispatchPageLifecycleEvent(")
        assertTrue("找不到 dispatchPageLifecycleEvent 实现", idx >= 0)
        val body = src.substring(idx, minOf(idx + 700, src.length))
        assertFalse(
            "生命周期事件派发不得触碰凭据（Authorization/password）",
            body.contains("Authorization") || body.contains("password"),
        )
    }

    // ---- 反向形状：旧失效形态必须可被检出 ---------------------------------

    @Test
    fun preUx8DeadSensorShapeIsDetectable() {
        // UX6 失效形态 = oc-host.js 没有任何东西调 setKeepScreenOn 之外的生产驱动。
        // 若日后有人把传感器整体撤掉，上面 sensorDrivesExistingKeepScreenOnBridge
        // 会先红；这里再补一道"页面桥 + 传感器至少一条生产路径"的存在性断言。
        assertTrue(
            "宿主侧必须存在回合常亮的生产驱动（注入层传感器）",
            js.contains("installRoundPowerSensor"),
        )
        assertTrue(
            "Activity 必须存在 pagehide/pageshow 扳机",
            activitySrc.contains("pagehide") && activitySrc.contains("pageshow"),
        )
    }
}
