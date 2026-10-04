package com.xiufeigo.opencodeshell.web

import com.xiufeigo.opencodeshell.web.host.BackendProbe
import com.xiufeigo.opencodeshell.web.host.ConnectErrorPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F5 · 接入健壮性守卫。
 *
 * 这三条断言直接对着 V7 的两张缺陷截图（`research/env/v7/v7_n5_*.png`）：
 * 裸 JSON + 泄露内部 URL（缺陷1）、错口令永停 Loading 骨架屏（缺陷2）。
 * **只增不减、不放宽**：任何一条失败都意味着又退回了 V7 的形态。
 */
class ConnectRobustnessTest {

    // ---- 缺陷1：不可达 ⇒ 友好页 + 不泄露内部信息 --------------------------

    @Test
    fun `redact hides scheme url and host port`() {
        // 这就是 V7 截图里泄露的那一串，逐位取自 v7_n5_noreverse2.png 的可见文本。
        val leaked = "unexpected end of stream on http://127.0.0.1:4095/api/event"
        val out = ConnectErrorPage.redact(leaked)
        assertFalse("内部 URL 不得出现在界面上: $out", out.contains("127.0.0.1"))
        assertFalse("端口不得出现在界面上: $out", out.contains("4095"))
        assertFalse("scheme 不得出现在界面上: $out", out.contains("http://"))
        assertTrue("应留下可读的部分: $out", out.contains("unexpected end of stream"))
    }

    @Test
    fun `redact also covers bare host port without scheme`() {
        val out = ConnectErrorPage.redact("connect failed to 192.168.1.50:4095")
        assertFalse(out.contains("192.168.1.50"))
        assertFalse(out.contains("4095"))
    }

    @Test
    fun `redact handles null and empty`() {
        assertEquals(ConnectErrorPage.REDACTED, ConnectErrorPage.redact(null))
        assertEquals(ConnectErrorPage.REDACTED, ConnectErrorPage.redact(""))
    }

    @Test
    fun `unreachable page is human readable not bare json`() {
        val html = ConnectErrorPage.unreachableHtml(
            "unexpected end of stream on http://127.0.0.1:4095/",
        )
        // 不是裸 JSON：V7 缺陷1 的形态是整页只有一行 {"error":...}
        assertFalse("不得吐裸 JSON", html.trimStart().startsWith("{"))
        assertTrue("必须是人话标题", html.contains(ConnectErrorPage.Kind.UNREACHABLE.heading))
        assertTrue("必须给重试按钮", html.contains("重试"))
        // 界面上不得出现内部 URL/端口
        assertFalse(html.contains("127.0.0.1"))
        assertFalse(html.contains("4095"))
    }

    @Test
    fun `error page retry button meets 48dp hard requirement`() {
        val html = ConnectErrorPage.unreachableHtml()
        assertEquals(48, ConnectErrorPage.MIN_TOUCH_TARGET_CSS_PX)
        // 按钮显式写死高度，不靠内容撑（与 F2 的 minHeight 同思路）。
        assertTrue("重试按钮必须 >=48px: $html", html.contains("min-height:48px"))
        assertTrue("必须真的有点击动作", html.contains(ConnectErrorPage.DEFAULT_RETRY_JS))
    }

    @Test
    fun `error page escapes html so detail cannot break out`() {
        val evil = "</title><script>alert(1)</script> http://127.0.0.1:4095"
        val html = ConnectErrorPage.unreachableHtml(evil)
        assertFalse("不得出现裸 script 标签", html.contains("<script>"))
        assertFalse(html.contains("127.0.0.1"))
    }

    @Test
    fun `auth page never echoes password and only says credentials are wrong`() {
        val html = ConnectErrorPage.authFailedHtml("401 for user=opencode")
        assertTrue(html.contains(ConnectErrorPage.Kind.AUTH_FAILED.heading))
        assertTrue("必须明确是认证问题", html.contains("用户名或口令不对"))
        assertTrue("必须可重试", html.contains("重试"))
        // 文案不得回显口令
        assertFalse(html.contains("opencode:"))
    }

    // ---- 缺陷2：口令错 ⇒ 明确判定 + 有上限 -------------------------------

    @Test
    fun `401 and 403 classify as auth failed not as generic failure`() {
        val probe = BackendProbe()
        assertTrue(probe.classify(401, null) is BackendProbe.Result.AuthFailed)
        assertTrue(probe.classify(403, null) is BackendProbe.Result.AuthFailed)
    }

    @Test
    fun `no response classifies as unreachable so user is told to check address`() {
        val probe = BackendProbe()
        val r = probe.classify(null, "timeout")
        assertTrue(r is BackendProbe.Result.Unreachable)
        // 用户拿到的引导必须提"服务器地址/网络"这类自己能确认的东西
        assertTrue(ConnectErrorPage.Kind.UNREACHABLE.hint.contains("服务器地址"))
    }

    @Test
    fun `2xx and 3xx classify as ok so normal loading is not disturbed`() {
        val probe = BackendProbe()
        assertTrue(probe.classify(200, null) is BackendProbe.Result.OK)
        assertTrue(probe.classify(302, null) is BackendProbe.Result.OK)
    }

    @Test
    fun `5xx is unreachable and 404 is unknown both stay retryable`() {
        val probe = BackendProbe()
        assertTrue(probe.classify(502, null) is BackendProbe.Result.Unreachable)
        assertTrue(probe.classify(404, null) is BackendProbe.Result.Unknown)
    }

    @Test
    fun `probe timeout is bounded and justified against proxy connect timeout`() {
        // 超时必须有上限：否则用户永远等不到提示。
        assertTrue(BackendProbe.PROBE_TIMEOUT_MS > 0)
        // 上界必须严格小于代理自身的 connectTimeout(15s)，否则探测形同虚设。
        assertTrue(
            "probe timeout ${BackendProbe.PROBE_TIMEOUT_MS}ms must be < proxy connectTimeout 15000ms",
            BackendProbe.PROBE_TIMEOUT_MS < 15_000L,
        )
        // 探的是 /api/info（免认证 401 / Basic 200 那一档）。
        assertEquals("/api/info", BackendProbe.PROBE_PATH)
    }

    @Test
    fun `probe result carries no password field anywhere`() {
        // 反射守住"口令不进 Result"这条纪律：将来有人加字段会被这里抓到。
        val fields = BackendProbe.Result::class.java.declaredClasses
            .flatMap { it.declaredFields.map { f -> f.name } }
        for (f in fields) {
            assertFalse("Result 不得带口令字段: $f", f.contains("pass", ignoreCase = true))
            assertFalse("Result 不得带口令字段: $f", f.contains("secret", ignoreCase = true))
        }
    }
}
