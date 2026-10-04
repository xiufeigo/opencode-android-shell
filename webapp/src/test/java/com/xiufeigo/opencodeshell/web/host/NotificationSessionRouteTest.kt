package com.xiufeigo.opencodeshell.web.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * N2 绊线：通知**点击回到会话**的**路由**契约。
 *
 * ## 守护对象
 * [NotifierHost.sessionRoutePath] / [NotifierHost.serverKeyOf] / [NotifierHost.encodePathSegment]
 * —— 也就是「点通知 ⇒ 页面**真的切到那个会话**」这条链路上，唯一能被单测断言的量。
 *
 * ## 为什么 N1 修完还是不通（本轮查出来的真根因）
 * N1 把双层 JSON 编码修掉了，页面 `e.detail.sessionId` 拿到了**裸 id**，看着像通了。
 * 但那只证明"信号送达了页面"，**没有**证明"页面切了会话"。
 * 实际上上游 SPA 里**没有任何一处**监听 `oc:openSession`：
 *  `_upstream/packages/app` 全文没有 `oc:openSession` / `__ocAndroid` /
 *  `setOpenSessionHandler`（连上游自己的 `opencode:deep-link` 都无人监听）。
 * 上游唯一支持的入口是**路由**：
 *  `/server/<base64url(serverKey)>/session/<sessionId>`
 *  （`shell/routes/routes.tsx:39-40`、`shell/routes/session.ts:5-7`）；
 *  上游自己的 `openNotificationSession()`（`shell/notifications/notification.tsx:54-59`）
 *  做的也是 `navigate(tabHref(tab))` —— **导航，而不是派发事件**。
 *
 * ⇒ 原实现把 sessionId 派发进了虚空：**通知点了，页面永远不切会话。**
 * 真机证据：`research/web/NOTIFICATION_CLICK_ROOTCAUSE.md`。
 *
 * ## 每条断言分别能红在哪（反向验证见该报告 §6）
 * - `serverKeyMatchesUpstreamBase64UrlEncoding` —— base64 口径/`+`→`-`/`/`→`_`/去 `=` 改错 ⇒ 红。
 * - `routeMatchesUpstreamSessionRouteShape` —— 路由形状不对 ⇒ 红（上游路由不认 ⇒ 404/空页）。
 * - `routeShapeMatchesUpstreamOwnExample` —— 拿上游自己的测试样例当判据 ⇒ 红。
 * - `missingBackendUrlOrSessionYieldsNoRoute` —— 去掉 null 守卫 ⇒ 红（硬造必然失败的路径）。
 * - `pathSegmentIsPercentEncoded` —— 不编码 ⇒ 红（id 里带 `/`、`#` 会截断路由）。
 * - `deliverOpenSessionNavigatesByRoute` —— 生产代码不调 sessionRoutePath ⇒ **红**（防纯函数摆设）。
 * - `facadeNavigatesInsteadOfOnlyDispatching` —— facade 只派发不导航 ⇒ **红**（本轮真根因）。
 */
class NotificationSessionRouteTest {

    private fun moduleFile(relative: String): String {
        val f = File(relative)
        assertTrue("找不到 $relative（单测工作目录应为 webapp/ 模块目录）", f.isFile)
        return f.readText()
    }

    private val notifierSource: String get() =
        moduleFile("src/main/java/com/xiufeigo/opencodeshell/web/host/NotifierHost.kt")

    private val bridgeSource: String get() =
        moduleFile("src/main/java/com/xiufeigo/opencodeshell/web/host/WebHostBridge.kt")

    private val facadeSource: String get() = moduleFile("src/main/assets/www/oc-host.js")

    // ---- 1. serverKey 的编码口径必须与上游逐字一致 -------------------------

    @Test
    fun serverKeyMatchesUpstreamBase64UrlEncoding() {
        // 真机实测值（emulator-5880）：导航到
        //   /server/aHR0cDovLzEyNy4wLjAuMTo0MDk1/session/<id>
        // 路由成立（未落到上游的 "Something went wrong" 错误页）。
        assertEquals(
            "N2: serverKey 必须是 base64url(后端 URL)，且不带 = 填充。" +
                "口径对齐上游 packages/util/src/encode.ts 的 base64Encode：" +
                "标准 base64，+ → -，/ → _，去掉 =。",
            "aHR0cDovLzEyNy4wLjAuMTo0MDk1",
            NotifierHost.serverKeyOf("http://127.0.0.1:4095"),
        )
        // + / 必须被替换掉，而不是留着 —— 上游 URL 解析会拿 base64url 去找已注册连接。
        val tricky = NotifierHost.serverKeyOf("https://a.b/c?d=e+f")!!
        assertFalse("N2: serverKey 里不能出现 base64 标准的 '+'（必须是 '-'）。", tricky.contains('+'))
        assertFalse("N2: serverKey 里不能出现 base64 标准的 '/'（必须是 '_'）。", tricky.contains('/'))
        assertFalse("N2: serverKey 不能带 '=' 填充。", tricky.contains('='))
        // 末尾斜杠归一化：后端 URL 常写成 http://host:4095/，不该影响 key。
        assertEquals(
            "N2: 后端 URL 末尾的 '/' 必须先去掉再编码，否则同一个后端会算出两个 key，" +
                "路由就落不到已注册的连接上。",
            NotifierHost.serverKeyOf("http://127.0.0.1:4095"),
            NotifierHost.serverKeyOf("http://127.0.0.1:4095/"),
        )
    }

    // ---- 2. 路由形状必须与上游 session 路由一致 ---------------------------

    @Test
    fun routeMatchesUpstreamSessionRouteShape() {
        val path = NotifierHost.sessionRoutePath("http://127.0.0.1:4095", "ses_efc722bf9ffeWaRdoFTwbmYj1Y")
        assertEquals(
            "N2: 目标路由必须是上游唯一支持的会话路由" +
                "（routes.tsx:39-40 `path=\"/server/:serverKey/session/:id\"`）。" +
                "形状不对 ⇒ 上游路由不匹配 ⇒ 点了通知也不会切会话。",
            "/server/aHR0cDovLzEyNy4wLjAuMTo0MDk1/session/ses_efc722bf9ffeWaRdoFTwbmYj1Y",
            path,
        )
        assertTrue(
            "N2: 路由必须是站内绝对路径（以 / 开头），facade 用 location.assign 导航。",
            path!!.startsWith("/server/"),
        )
        assertEquals(
            "N2: 路由必须正好 5 段：server / <key> / session / <id>（前导 / 不计）。",
            4,
            path.trim('/').split('/').size,
        )
    }

    @Test
    fun routeShapeMatchesUpstreamOwnExample() {
        // 直接拿上游自己的单测样例当判据（_upstream/packages/app/src/shell/routes/session.test.ts:10）：
        //   sessionHref("https://example.com:4096", "session-1")
        //     === "/server/aHR0cHM6Ly9leGFtcGxlLmNvbTo0MDk2/session/session-1"
        assertEquals(
            "N2: 路由形状必须与上游 sessionHref() 的自有样例逐字一致。",
            "/server/aHR0cHM6Ly9leGFtcGxlLmNvbTo0MDk2/session/session-1",
            NotifierHost.sessionRoutePath("https://example.com:4096", "session-1"),
        )
    }

    // ---- 3. 缺料就不造路由（不许硬造一条必然失败的路径）--------------------

    @Test
    fun missingBackendUrlOrSessionYieldsNoRoute() {
        assertNull(
            "N2: 没有后端 URL 就造不出 serverKey ⇒ 不该硬造一条必然落空的路由。",
            NotifierHost.sessionRoutePath(null, "ses_1"),
        )
        assertNull(
            "N2: 后端 URL 是空白 ⇒ 等价于没有。",
            NotifierHost.sessionRoutePath("   ", "ses_1"),
        )
        assertNull(
            "N2: 没有会话 id ⇒ 无从回起，不该编一个假 id 出去。",
            NotifierHost.sessionRoutePath("http://127.0.0.1:4095", null),
        )
        assertNull(
            "N2: 空白会话 id ⇒ 等价于没有。",
            NotifierHost.sessionRoutePath("http://127.0.0.1:4095", "   "),
        )
        assertNull("N2: serverKeyOf(null) 应为 null", NotifierHost.serverKeyOf(null))
    }

    // ---- 4. 路径段必须百分号编码 ------------------------------------------

    @Test
    fun pathSegmentIsPercentEncoded() {
        assertEquals(
            "N2: RFC 3986 unreserved（A-Za-z0-9 - . _ ~）不该被编码。",
            "ses_abc-123.x~y",
            NotifierHost.encodePathSegment("ses_abc-123.x~y"),
        )
        assertEquals(
            "N2: 会话 id 里的 '/' 会把路由截断（多出一段），必须编码成 %2F。",
            "a%2Fb",
            NotifierHost.encodePathSegment("a/b"),
        )
        assertEquals(
            "N2: '#' 会让后面的部分变成 fragment，必须编码成 %23。",
            "a%23b",
            NotifierHost.encodePathSegment("a#b"),
        )
        assertEquals(
            "N2: 空格必须编码成 %20，不能留在路径里。",
            "a%20b",
            NotifierHost.encodePathSegment("a b"),
        )
        // 整条路由里绝不能出现会截断的裸字符
        val path = NotifierHost.sessionRoutePath("http://127.0.0.1:4095", "s/1#2")!!
        assertTrue("N2: 路由里出现了裸 '#'，会被当成 fragment。", !path.contains('#'))
        assertEquals(
            "N2: 编码只作用于 id 段，serverKey 段不该被编码。",
            "/server/aHR0cDovLzEyNy4wLjAuMTo0MDk1/session/s%2F1%232",
            path,
        )
    }

    // ---- 5. 防"纯函数摆设"：生产代码真的走它 ------------------------------

    @Test
    fun deliverOpenSessionNavigatesByRoute() {
        val src = bridgeSource
        assertTrue(
            "N2: deliverOpenSession 不再走 NotifierHost.sessionRoutePath() —— " +
                "纯函数与生产代码脱钩，单测就变成了摆设；页面仍然切不了会话。",
            src.contains("NotifierHost.sessionRoutePath("),
        )
        assertTrue(
            "N2: 必须把算出的路由交给 facade 去导航（__openSessionByRoute）。",
            src.contains("__openSessionByRoute"),
        )
        assertTrue(
            "N2: 后端 URL 必须取自 SecretStore.KEY_BACKEND_URL（宿主本来就知道它），" +
                "不能写死 —— 写死就换不了后端。",
            src.contains("SecretStore.KEY_BACKEND_URL"),
        )
        assertTrue(
            "N2: 造不出路由时必须退回旧行为（只派发事件），不能硬造一条必然失败的路径。",
            src.contains("if (route != null)") && src.contains("onOpenSession("),
        )
    }

    @Test
    fun facadeNavigatesInsteadOfOnlyDispatching() {
        val src = facadeSource
        assertTrue(
            "N2: facade 里没有 __openSessionByRoute —— 宿主没有可调用的导航入口。" +
                "（本轮真根因：facade 只 dispatchEvent('oc:openSession')，" +
                "而上游 SPA 没有任何一处监听它 ⇒ 点了通知页面永远不切会话。" +
                "真机证据：research/web/NOTIFICATION_CLICK_ROOTCAUSE.md）",
            src.contains("__openSessionByRoute"),
        )
        assertTrue(
            "N2: facade 必须真的导航（location.assign），不能只派发事件。",
            Regex("""__openSessionByRoute[\s\S]{0,900}?location\.assign\(""").containsMatchIn(src),
        )
        // 保留旧入口：不许为了修这条把已有监听者（事件）一起弄没了。
        assertTrue(
            "N2: onOpenSession 事件入口必须保留（谁监听了它都不该被这次修复弄坏）。",
            src.contains("onOpenSession:") && src.contains("oc:openSession"),
        )
    }
}
