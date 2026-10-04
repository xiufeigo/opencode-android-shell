package com.xiufeigo.opencodeshell.web

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * N5 · **通知投递策略接线绊线**（源码文本断言）。
 *
 * ## 背景：为什么"单测全绿"仍然可能一条通知都不发
 *
 * 上游把系统通知交给**浏览器 Notification API**
 * （`origin/v2` `packages/app/src/runtime/platform/web.ts:21-40`）。
 * Android WebView 没有这个 API ⇒ 上游在 `web.ts:22`
 * `if (!("Notification" in window)) return` **直接早退**。
 *
 * 于是形成两个各自都能通过单测、但功能整体不通的洞：
 *  1. 上游侧：早退 ⇒ 从不发起通知；
 *  2. 宿主侧：`NotifierHost.postTurnFinished` 只是"投递"，
 *     而**全仓没有任何生产代码调它** ——
 *     `oc.postMessage` 的唯一调用者是自检页
 *     `webapp/src/main/assets/www/index.html:284`。
 *
 * 所以本文件钉的是**接线**，不是策略：
 *  - 页面侧必须存在 `window.Notification` 垫片，且它**只做转发**；
 *  - 宿主侧必须**保持"不判断策略"**（去重/静音/焦点判断一旦出现在宿主，
 *    就等于把上游已经实现过的策略复制一份 ⇒ 两处漂移）。
 *
 * ## 反向验证
 *
 * 撤掉垫片、或让宿主长出自造策略（见 `research/web/N1N2_UPSTREAM_N5.md` §3），
 * 对应用例必然**变红**。
 */
class NotificationPolicyBridgeTest {

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

    private val hostJs: String
        get() = src("src/main/assets/www/oc-host.js")

    private val notifierSrc: String
        get() = src("src/main/java/com/xiufeigo/opencodeshell/web/host/NotifierHost.kt")

    // ---- 接线点 1：页面侧必须有 Notification 垫片 -------------------------

    @Test
    fun hostJsDefinesNotificationShim() {
        val src = hostJs
        assertTrue(
            "oc-host.js 未定义 window.Notification 垫片 —— 上游会在 " +
                "web.ts:22 `if (!(\"Notification\" in window)) return` 早退，本宿主一条系统通知都不会发",
            src.contains("if (!(\"Notification\" in window)) {"),
        )
        assertTrue(
            "垫片必须真的挂到 window.Notification 上，不能只是定义了个局部构造函数",
            src.contains("window.Notification = ShimNotification"),
        )
    }

    @Test
    fun shimOnlyInstallsWhenTheRealApiIsMissing() {
        // 反向断言：若去掉 `!(\"Notification\" in window)` 这层判断，
        // 垫片会在已有真实现的浏览器里覆盖官方 API ⇒ 用例变红。
        val src = hostJs
        assertTrue(
            "垫片必须以 `!(\"Notification\" in window)` 为前提，否则会覆盖真实浏览器里的官方 Notification",
            src.contains("if (!(\"Notification\" in window)) {"),
        )
    }

    @Test
    fun shimForwardsToHostDelivery() {
        val src = hostJs
        assertTrue(
            "垫片必须把通知转发给宿主投递口 oc.postMessage，否则系统通知仍然出不来",
            src.contains("oc.postMessage(String(title == null ? \"\" : title)"),
        )
    }

    @Test
    fun shimMapsHostPermissionState() {
        val src = hostJs
        assertTrue(
            "垫片的 requestPermission 必须接宿主授权态，否则上游的权限门控（web.ts:24-28）永远判 denied",
            src.contains("ShimNotification.requestPermission = function () {"),
        )
        assertTrue(
            "requestPermission 未接宿主 hasNotificationPermission()",
            src.contains("oc.hasNotificationPermission()"),
        )
    }

    @Test
    fun shimDerivesSessionIdFromUpstreamRoute() {
        // 上游 notify(title, description, onClick) 不带 sessionId；
        // 而 notification.tsx:240 只在该会话**有打开 tab** 时才发 ⇒ 当前路由就是目标会话。
        val src = hostJs
        assertTrue(
            "垫片必须从当前路由解析 sessionId（/server/<key>/session/<id>），否则点通知回不到会话",
            src.contains("/\\/server\\/[^/]+\\/session\\/([^/?#]+)/"),
        )
    }

    // ---- 接线点 2：宿主侧必须"不判断策略" --------------------------------

    @Test
    fun hostNotifierStaysPolicyFree() {
        // 这是"对齐上游"的核心断言：去重 / 静音 / 焦点判断
        // 上游都已经在页面侧实现了（coordinator.ts:62,73-93；
        // notification.tsx:227,240,255,271）。宿主一旦再长一份，
        // 同一策略就有两个真相来源。
        val src = notifierSrc
        val forbidden = listOf(
            "hasFocus" to "coordinator.ts:62 的焦点门控已在页面侧实现，宿主不得重复",
            "dedup" to "coordinator.ts:73-93 claim() 已按 eventID 去重，宿主不得重复",
            "eventID" to "eventID 去重归上游 coordinator，宿主不得重复",
            "localStorage" to "claim 跨重启防重归上游，宿主不得重复",
        )
        forbidden.forEach { (token, why) ->
            assertTrue(
                "NotifierHost 出现了 `$token`（$why）—— 策略出现第二个真相来源",
                !src.contains(token),
            )
        }
    }

    @Test
    fun hostStillDeliversThroughExistingBridge() {
        val bridge = src("src/main/java/com/xiufeigo/opencodeshell/web/host/WebHostBridge.kt")
        assertTrue(
            "WebHostBridge 必须仍然把 postMessage 接到 NotifierHost.postTurnFinished",
            bridge.contains("return NotifierHost.postTurnFinished(ctx, t, b, s)"),
        )
    }

    // ---- F3：投递口**只允许**垫片一个调用者 ------------------------------
    //
    // 上游通知种类是**封闭 union**（origin/v2 notification.tsx:26-29 =
    // turn-complete | error），而通知路径上的每一道门控
    // （web.ts:24-28 权限、web.ts:29 与 coordinator.ts:62 焦点、
    //  coordinator.ts:73-93 eventID 去重、notification.tsx:240 hasOpenTab）
    // 都**只在** web.ts:21-40 这条路径上执行。
    //
    // 因此：任何**直接**调 `oc.postMessage(...)` 的页面代码都绕过了全部门控，
    // 是一条自造投递口。F3 之前自检页 index.html:284 就是这样一个调用者
    // （也是生产路径上唯一的一个）。本用例把它钉死。

    @Test
    fun onlyTheShimMayCallTheHostDeliveryMouth() {
        val offenders = mutableListOf<String>()
        val root = File("src/main/assets/www")
        require(root.isDirectory) { "找不到资源目录 ${root.absolutePath}" }
        root.walkTopDown().filter { it.isFile && it.extension in setOf("js", "html") }.forEach { f ->
            val rel = f.relativeTo(root).path.replace('\\', '/')
            // 垫片自己的那一行转发是**应该存在**的（oc-host.js:391）。
            if (rel == "oc-host.js") return@forEach
            f.readLines(Charsets.UTF_8).forEachIndexed { i, line ->
                if (Regex("""\boc\.postMessage\s*\(""").containsMatchIn(line)) {
                    offenders += "$rel:${i + 1}: ${line.trim()}"
                }
            }
        }
        assertTrue(
            "只有 oc-host.js 的 Notification 垫片可以调 oc.postMessage；以下调用者绕过了上游全部门控" +
                "（web.ts:24-29 / coordinator.ts:62,73-93 / notification.tsx:240）:\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    @Test
    fun selfCheckPageRoutesThroughTheShimNotDirectly() {
        assertTrue(
            "自检页必须改走 window.Notification 垫片，才能证明生产路径真的经过上游门控",
            src("src/main/assets/www/index.html").contains("new window.Notification("),
        )
    }
}
