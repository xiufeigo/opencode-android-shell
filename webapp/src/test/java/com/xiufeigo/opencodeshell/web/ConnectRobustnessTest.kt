package com.xiufeigo.opencodeshell.web

import com.xiufeigo.opencodeshell.web.host.BackendProbe
import com.xiufeigo.opencodeshell.web.host.ConnectErrorPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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

    // ---- F6：探测必须带 Basic 凭据（V8 缺陷的正面守卫）---------------------

    @Test
    fun `probe request carries basic credentials with fixed user and given password`() {
        // 这就是 F6 的核心断言。V8 实测：每一次探测都是**匿名**请求 ⇒ 后端回 401
        // ⇒ 口令正确的用户被告知"用户名或口令不对"（V8_VERIFY.md §0.1）。
        // 断言的是**真的要发出去的那条请求**的请求头。
        val probe = BackendProbe()
        val req = probe.buildProbeRequest("http://127.0.0.1:4095", "opencode")
        val auth = req.header("Authorization")

        assertNotNull("探测请求必须带 Authorization: 实际=$auth", auth)
        assertTrue("必须是 Basic 认证: $auth", auth!!.startsWith("Basic "))
        // 解码核对：用户名固定 opencode，口令原样带上。
        val decoded = String(
            java.util.Base64.getDecoder().decode(auth.removePrefix("Basic ")),
            Charsets.UTF_8,
        )
        assertEquals("opencode:opencode", decoded)
        assertEquals("探的端点必须是 /api/info", "/api/info", req.url.encodedPath)
    }

    @Test
    fun `probe request drops trailing slash and appends probe path exactly once`() {
        // 地址拼接是"会不会把 /api/info 探成 /api//api/info"这类问题的守门。
        val probe = BackendProbe()
        for (base in listOf("http://127.0.0.1:4095", "http://127.0.0.1:4095/")) {
            val url = probe.buildProbeRequest(base, "opencode").url
            assertEquals(
                "base=$base 拼接必须恰好一次",
                "http://127.0.0.1:4095/api/info",
                url.toString(),
            )
        }
    }

    @Test
    fun `probe request without password carries no authorization header`() {
        // 反向的另一半：空口令时**确实不发** header（不能硬塞一个空口令的 Basic）。
        // 此时的 401 依然判 AuthFailed，语义是"去输口令"，不是"你输错了"。
        val probe = BackendProbe()
        for (empty in listOf(null, "")) {
            val req = probe.buildProbeRequest("http://127.0.0.1:4095", empty)
            assertNull("空口令不得发 Authorization: pass=$empty", req.header("Authorization"))
        }
    }

    @Test
    fun `three state classification is distinct for credentialed responses`() {
        // F6 要求三态严格分开：带凭据 200 = 放行；带凭据 401 = 口令不对；
        // 连不上/超时 = 不可达（**不是**认证失败）。这里守住"AuthFailed 与
        // Unreachable 互不吞并"这条线。
        val probe = BackendProbe()
        val ok = probe.classify(200, null)
        val auth = probe.classify(401, null)
        val unreach = probe.classify(null, "timeout")

        assertTrue("带凭据 200 ⇒ OK（正常放行进官方 UI）", ok is BackendProbe.Result.OK)
        assertTrue("带凭据 401 ⇒ AuthFailed", auth is BackendProbe.Result.AuthFailed)
        assertTrue("连不上/超时 ⇒ Unreachable", unreach is BackendProbe.Result.Unreachable)

        assertFalse("OK 与 AuthFailed 不得同形", ok is BackendProbe.Result.AuthFailed)
        assertFalse("Unreachable 不得被当成 AuthFailed", unreach is BackendProbe.Result.AuthFailed)
        assertEquals("OK 必须带回状态码", 200, (ok as BackendProbe.Result.OK).code)
        assertEquals("AuthFailed 必须带回状态码", 401, (auth as BackendProbe.Result.AuthFailed).code)
        // 不可达引导的是"查地址/网络"，认证失败引导的是"改口令" —— 文案不得串。
        assertTrue(
            ConnectErrorPage.Kind.UNREACHABLE.hint.contains("服务器地址"),
        )
        // F6：带凭据 401 判 AuthFailed 时，给用户看的那句话必须**明确**是认证问题
        // （"用户名或口令不对"在 HINT_AUTH 里；heading 是"登录没通过"）。
        assertTrue(
            ConnectErrorPage.Kind.AUTH_FAILED.hint.contains("用户名或口令不对"),
        )
        // 认证失败的文案不得混进"连不上/查地址"那套引导，否则用户会去查网络而反复失败。
        assertFalse(
            ConnectErrorPage.Kind.AUTH_FAILED.hint.contains("服务器地址"),
        )
    }

    @Test
    fun `probe log line never contains the password`() {
        // 口令纪律的**机械**核对：凡是拼进日志的字符串，都不得直接插值口令。
        // 用源码接线检查来守 —— 反射看不到字符串模板的内容。
        val f = File("src/main/java/com/xiufeigo/opencodeshell/web/host/BackendProbe.kt")
        assertTrue("必须能读到 BackendProbe.kt 源文件: ${f.absolutePath}", f.exists())
        val text = f.readText()

        val logCallBodies = Regex("""Log\.[a-z]\(([\s\S]*?)\n\s*\)""")
            .findAll(text).map { it.groupValues[1] }.toList()
        assertTrue("应当能扫到日志调用", logCallBodies.isNotEmpty())
        for (body in logCallBodies) {
            assertFalse(
                "日志里不得插值口令: $body",
                body.contains("\$password") || body.contains("\${password"),
            )
        }
        // 结论行只能记长度，不能记口令
        assertTrue("结论行应只记 passLen", text.contains("passLen=\$passLen"))
    }

    @Test
    fun `every probe call site must build its request from buildProbeRequest`() {
        // F6 反向验证总结的教训：**只测 buildProbeRequest 是不够的**。
        //
        // F6 第一版绊线只断言了 `buildProbeRequest` 单独调用时会带凭据，于是把
        // `probeAsync` 改回 V8 原始形态（自己造一条匿名 Request）时，**测试照样绿** ——
        // 那正是"假绿"：断言的对象（构造器）与真实发出去的那条请求**不是同一条路**。
        //
        // 本条断言改成守**接线**：文件里每一处 `httpClient.newCall(` 都必须
        // 用 `buildProbeRequest(...)` 造请求。任何人再引入一条匿名探测请求，
        // 这里必红。
        val f = File("src/main/java/com/xiufeigo/opencodeshell/web/host/BackendProbe.kt")
        assertTrue("必须能读到 BackendProbe.kt 源文件: ${f.absolutePath}", f.exists())
        val lines = f.readLines()

        val callSites = lines
            .mapIndexed { i, l -> i to l }
            .filter { (_, l) -> l.contains("httpClient.newCall(") && !l.trimStart().startsWith("*") }
        assertTrue("应当至少有一处 newCall", callSites.isNotEmpty())

        for ((idx, line) in callSites) {
            val n = idx + 1
            assertTrue(
                "第 $n 行的 newCall 必须用 buildProbeRequest 造请求（否则探测变匿名）: $line",
                line.contains("buildProbeRequest("),
            )
        }
    }
}
