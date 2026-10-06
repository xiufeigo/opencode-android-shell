package com.xiufeigo.opencodeshell.web

import com.xiufeigo.opencodeshell.web.host.SecretStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * UX6 · **直连架构 + "宿主不持凭据"** 绊线。
 *
 * 取代已删的 `EmptyPasswordLaunchGateTest`（F21）。旧形态的性质是
 * 「口令为空 ⇒ 不许起代理、不许以无认证方式使用后端」，闸门（LaunchGuard）
 * 守在 `launch()` 里。新形态下这条性质的**保障者变了**：
 *
 * ## F21 在新形态下的表述
 *
 * > **宿主不持有、不注入、不转发任何口令；宿主不存在"替用户完成认证决策"的
 * > 代码路径。认证完全由官方 UI（`ConnectServerScreen` / 「设置 → 服务器」）
 * > 与后端之间协商**（官方前端把密码以 Basic `opencode:<password>` 直接发给
 * > 后端，存页面自己的 localStorage，见 `_upstream/packages/app/src/runtime/server/api.ts:5-7`）。
 *
 * 这条性质由**结构**保障而不是运行时闸门：回环代理整个删掉了，宿主想注入
 * 凭据都没有注入点。能静态断言的就把它钉死 —— 任何"顺手把口令接回来"的
 * 改动都会在这里被抓住。
 *
 * ## 本类钉住的不变量（全部对源码文本断言，不依赖 android 运行时）
 *  1. WebHostActivity **直接 loadUrl 后端地址**（bootUrl = KEY_BACKEND_URL ?: 默认）；
 *  2. 全模块**没有**回环代理 / ServerSocket / Authorization 注入 / 口令键残留；
 *  3. SecretStore **只剩非秘密**的宿主偏好（无 KEY_SERVER_PASSWORD）；
 *  4. 反向验证：对**故意写坏**的样本（带代理/口令的源码）扫描器必须报红。
 */
class DirectConnectAuthBoundaryTest {

    private fun moduleFile(relative: String): String {
        val f = File(relative)
        assertTrue("找不到 $relative（单测工作目录应为 webapp/ 模块目录）", f.isFile)
        return f.readText()
    }

    private val activitySrc: String
        get() = moduleFile("src/main/java/com/xiufeigo/opencodeshell/web/WebHostActivity.kt")

    private val bridgeSrc: String
        get() = moduleFile("src/main/java/com/xiufeigo/opencodeshell/web/host/WebHostBridge.kt")

    private val secretStoreSrc: String
        get() = moduleFile("src/main/java/com/xiufeigo/opencodeshell/web/host/SecretStore.kt")

    private val buildGradle: String get() = moduleFile("build.gradle.kts")

    /** 整个 main 源码树（src/main/java）拼起来做"残留扫描"。 */
    private val allMainSources: String by lazy {
        File("src/main/java").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .joinToString("\n") { it.readText() }
    }

    // ---- 1) 启动路径 = 直连后端 ---------------------------------------------

    @Test
    fun bootResolvesBackendUrlWithDefault() {
        assertTrue(
            "bootUrl() 必须 = SecretStore[KEY_BACKEND_URL] ?: DEFAULT_BACKEND_URL —— " +
                "启动地址的唯一来源，不能再冒出第二个口径",
            activitySrc.contains(
                "SecretStore.getSecret(this, SecretStore.KEY_BACKEND_URL) ?: SecretStore.DEFAULT_BACKEND_URL",
            ),
        )
        assertEquals(
            "默认后端地址不得漂移（adb reverse tcp:4095 tcp:4095 口径）",
            "http://127.0.0.1:4095",
            SecretStore.DEFAULT_BACKEND_URL,
        )
    }

    @Test
    fun launchLoadsBackendDirectly() {
        val start = activitySrc.indexOf("private fun launch(")
        assertTrue("找不到 launch()", start >= 0)
        val body = activitySrc.substring(start, minOf(start + 2500, activitySrc.length))
        assertTrue("launch() 必须 loadUrl", body.contains("webView.loadUrl(url)"))
        assertFalse(
            "launch() 里不允许再出现回环代理（代理已删）",
            body.contains("LoopbackProxy"),
        )
    }

    // ---- 2) 凭据注入路径整体不存在 -------------------------------------------

    @Test
    fun noLoopbackProxyAnywhere() {
        assertFalse(
            "回环代理必须整体删除（含任何 import/引用）",
            allMainSources.contains("LoopbackProxy"),
        )
        assertFalse(
            "OkHttp 依赖必须随代理删除（WebView 直连，不再需要转发客户端）",
            buildGradle.contains("okhttp"),
        )
    }

    @Test
    fun noCredentialInjectionAnywhere() {
        assertFalse(
            "宿主源码不得出现 Authorization 头构造（凭据注入的唯一旧注入点已删）",
            allMainSources.contains("Authorization"),
        )
        assertFalse(
            "宿主源码不得出现 serverPassword 键",
            allMainSources.contains("KEY_SERVER_PASSWORD"),
        )
        assertFalse(
            "宿主源码不得出现 Basic 凭据编码",
            allMainSources.contains("BASIC_USER") || allMainSources.contains("authTokenFromCredentials"),
        )
    }

    @Test
    fun noLoopbackListenerAnywhere() {
        assertFalse(
            "宿主不得再监听 ServerSocket（回环代理的底座；直连架构下它是多余的攻击面）",
            allMainSources.contains("ServerSocket"),
        )
    }

    @Test
    fun secretStoreHoldsNoSecrets() {
        assertFalse(
            "SecretStore 不得再定义口令键",
            secretStoreSrc.contains("KEY_SERVER_PASSWORD"),
        )
        assertFalse(
            "SecretStore 不得再保留\"宿主专用键\"机制（它存在的唯一理由就是挡口令过桥）",
            secretStoreSrc.contains("HOST_ONLY_KEYS") || secretStoreSrc.contains("isHostOnly"),
        )
    }

    @Test
    fun bridgeCarriesNoProxyOrigin() {
        assertTrue(
            "桥上的 proxyOrigin/proxyPrefix 必须恒空串（打了补丁的上游前端" +
                "要求 origin 与 prefix 都非空才改写；恒空 ⇒ 前端直连真实 URL）",
            bridgeSrc.contains("private val PROXY_ORIGIN: String get() = \"\"") &&
                bridgeSrc.contains("private val PROXY_PREFIX = \"\""),
        )
    }

    @Test
    fun noSelfBuiltSetupOrGrayPages() {
        // 这些是 UX6 明确删除的自建 UI：未配置灰态 / 启动配置对话框 / 探测错误页。
        listOf(
            "NotConfiguredShell", "showSetupDialog", "showNotConfiguredShell",
            "showNoPasswordBlockedPage", "showConnectErrorDialog", "probeThenLaunch",
            "BackendProbe", "LaunchGuard", "ocshell", "INTERNAL_SCHEME",
        ).forEach { banned ->
            assertFalse(
                "宿主源码不得再出现已删除的自建页/对话框/探测组件残留：$banned",
                activitySrc.contains(banned),
            )
        }
    }

    // ---- 3) 保留面回归：桥与默认地址仍在 -------------------------------------

    @Test
    fun bridgeStillExposesSecretStoreBackendUrlForNotificationRoutes() {
        assertTrue(
            "通知点击回会话仍需要 KEY_BACKEND_URL（NotifierHost 路由由它推导）",
            bridgeSrc.contains("SecretStore.KEY_BACKEND_URL"),
        )
    }

    // ---- 4) 反向验证：扫描器必须能变红 ----------------------------------------

    private data class ResidualFinding(val file: String, val what: String)

    /** 与上面同款的"残留扫描器"：在任意源码里找凭据注入/代理残留。 */
    private fun scanResiduals(source: String, label: String): List<ResidualFinding> {
        val banned = listOf(
            "LoopbackProxy(" to "loopback-proxy",
            "ServerSocket(" to "server-socket",
            "Authorization" to "authorization-header",
            "KEY_SERVER_PASSWORD" to "password-key",
        )
        return banned.filter { (needle, _) -> source.contains(needle) }
            .map { (_, what) -> ResidualFinding(label, what) }
    }

    @Test
    fun scannerFlagsReintroducedProxySource() {
        val bad = """
            class Evil {
                fun inject() {
                    val proxy = LoopbackProxy("http://127.0.0.1:4095") { null }
                    val socket = ServerSocket(0)
                    val header = "Authorization: Basic ..."
                    SecretStore.getSecret(ctx, SecretStore.KEY_SERVER_PASSWORD)
                }
            }
        """.trimIndent()
        val hits = scanResiduals(bad, "bad-sample")
        assertEquals(
            "反向验证失败：扫描器没抓到故意写回来的代理/凭据残留 ⇒ 绊线是假绿。hits=$hits",
            4,
            hits.size,
        )
        assertTrue(
            "扫描器必须对当前真实源码判零残留（否则是扫描器自己坏了）",
            scanResiduals(allMainSources, "main").isEmpty(),
        )
    }
}
