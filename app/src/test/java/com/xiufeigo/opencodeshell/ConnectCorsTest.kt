package com.xiufeigo.opencodeshell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ConnectCorsTest {
    @Test
    fun androidWebViewOriginIsAssetLoaderHttps() {
        assertEquals("appassets.androidplatform.net", MainActivity.ASSET_HOST)
        assertEquals("https://appassets.androidplatform.net", MainActivity.ANDROID_WEBVIEW_ORIGIN)
    }

    @Test
    fun offlineStubIsOnlyLoopbackPortZero() {
        assertTrue(MainActivity.isOfflineDisconnectStub("127.0.0.1", 0))
        assertTrue(MainActivity.isOfflineDisconnectStub("localhost", 0))
        assertTrue(MainActivity.isOfflineDisconnectStub("::1", 0))
        assertFalse(MainActivity.isOfflineDisconnectStub("127.0.0.1", 4096))
        assertFalse(MainActivity.isOfflineDisconnectStub("localhost", -1))
        assertFalse(MainActivity.isOfflineDisconnectStub("backend.opencode.xiufeigo.xyz", 443))
        assertFalse(MainActivity.isOfflineDisconnectStub("backend.opencode.xiufeigo.xyz", -1))
        assertFalse(MainActivity.isOfflineDisconnectStub("backend.opencode.xiufeigo.xyz", 0))
        assertFalse(MainActivity.isOfflineDisconnectStub(null, 0))
    }

    @Test
    fun networkSecurityTrustsSystemAndUserCas() {
        val xml = File("src/main/res/xml/network_security_config.xml").readText()
        assertTrue(xml.contains("cleartextTrafficPermitted=\"true\""))
        assertTrue(xml.contains("certificates src=\"system\""))
        assertTrue("user-installed CAs must be trusted for private HTTPS", xml.contains("certificates src=\"user\""))
    }

    @Test
    fun interceptDoesNotSwallowRemoteHttps() {
        val src = File("src/main/java/com/xiufeigo/opencodeshell/MainActivity.kt").readText()
        assertTrue(src.contains("disconnectedStub(request.url)"))
        assertTrue(src.contains("assetLoader.shouldInterceptRequest(request.url)"))
        assertFalse(
            "do not native-proxy all HTTPS (that would drop POST bodies / break SSE)",
            src.contains("HttpURLConnection") || src.contains("OkHttp"),
        )
        assertTrue(src.contains("MIXED_CONTENT_ALWAYS_ALLOW"))
        assertTrue(src.contains("setAcceptThirdPartyCookies"))
    }

    @Test
    fun vendoredBundleMentionsCorsOriginAndHealthPaths() {
        val html = File("src/main/assets/opencode-app/index.html").readText()
        val jsName =
            Regex("""src="\./assets/(index-[^"]+\.js)"""").find(html)?.groupValues?.get(1)
                ?: error("missing entry script")
        val assets = File("src/main/assets/opencode-app/assets")
        val js = File(assets, jsName).readText()
        assertTrue(js.contains("https://appassets.androidplatform.net") || js.contains("appassets.androidplatform.net"))
        val allJs = assets.listFiles { _, name -> name.endsWith(".js") }!!.joinToString("\n") { it.readText() }
        assertTrue("health check must use official /api/health", allJs.contains("/api/health"))
        assertTrue("health check must fall back to /global/health", allJs.contains("/global/health"))
        assertTrue(allJs.contains("explainConnectFailure") || allJs.contains("errorCors"))
        assertTrue(allJs.contains("dialog.server.add.corsHint") || allJs.contains("opencode serve --cors"))
        val zh = assets.listFiles { _, name -> name.startsWith("zh-") && name.endsWith(".js") }
            ?.joinToString("\n") { it.readText() }
            ?: ""
        assertTrue(
            "Chinese add-server CORS hint must mention the Android origin",
            zh.contains("https://appassets.androidplatform.net"),
        )
    }

    @Test
    fun readmeDocumentsAndroidCorsRequirement() {
    val readme =
        listOf(File("../README.md"), File("README.md"), File("../../README.md")).firstOrNull { it.isFile }
            ?: error("README.md not found")
    val text = readme.readText()
    assertTrue(text.contains("--cors https://appassets.androidplatform.net"))
    assertTrue(text.contains("Access-Control-Allow-Origin"))
    assertTrue(text.contains("opencode.json"))
    }
}
