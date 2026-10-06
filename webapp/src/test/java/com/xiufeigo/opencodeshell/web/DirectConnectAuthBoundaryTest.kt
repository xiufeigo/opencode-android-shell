package com.xiufeigo.opencodeshell.web

import com.xiufeigo.opencodeshell.web.host.LocalUiOriginServer
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

    /**
     * UX11：产品负责人**明确授权**的唯一一个本机 http 端点就是"服务 UI 静态资源"的那个。
     * 它必须存在，但不得出现在"代理/凭据残留"扫描域里 —— 它的合规性由
     * [noLoopbackListenerAnywhere] 单独逐条钉死（只绑回环、只服务 assets、/api 拒答、0 凭据字样）。
     */
    private val AUTHORIZED_STATIC_ORIGIN = "LocalUiOriginServer.kt"

    /** 除"已授权的静态来源端点"之外的全部 main 源码（代理/凭据残留扫描域）。 */
    private val allMainSourcesExceptAuthorized: String by lazy {
        File("src/main/java").walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != AUTHORIZED_STATIC_ORIGIN }
            .joinToString("\n") { it.readText() }
    }

    // ---- 1) 启动路径 = 直连后端 ---------------------------------------------

    @Test
    fun bootResolvesBackendUrlWithDefault() {
        // UX11：页面来源改为本机 http 静态来源（官方前端自带），**不再**是所配后端。
        // 这正是"死锁从根上消失"的机制点：页面协议恒为 http:，与后端协议无关。
        assertTrue(
            "bootUrl() 必须 = LocalUiOriginServer.origin() —— 页面来自本机静态来源，" +
                "而不是某个后端地址（否则配了 https 后端页面就变 https，死锁复发）",
            activitySrc.contains("LocalUiOriginServer.origin()"),
        )
        // 反向验证：启动链不允许再回到"加载后端地址"的老形态。
        assertFalse(
            "bootUrl() 不得再解析后端地址作为页面来源（UX10 的双向死锁来源）",
            activitySrc.contains("SecretStore.getSecret(this, SecretStore.KEY_BACKEND_URL) ?: SecretStore.DEFAULT_BACKEND_URL"),
        )
        // 本机静态来源端口必须固定且避开 4095（4095 是后端/adb reverse 口径）。
        assertEquals(
            "默认后端地址不得漂移（通知路由仍用它推导）",
            "http://127.0.0.1:4095",
            SecretStore.DEFAULT_BACKEND_URL,
        )
        assertEquals(
            "本机静态来源端口必须避开 4095",
            4173,
            LocalUiOriginServer.DEFAULT_PORT,
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
        // UX11：产品负责人授权了"为服务 UI 静态资源起一个本机 http 端点"，
        // 这**取代**了此前"不许 ServerSocket"那条限制。但授权范围极窄：
        // 监听器**只准**服务 assets 里的 UI 静态文件，**不准**碰凭据、**不准**代理 API。
        // 下面把这条窄授权钉成可执行的断言，而不是简单删掉这个用例。
        val server = File("src/main/java/com/xiufeigo/opencodeshell/web/host/LocalUiOriginServer.kt")
        assertTrue("找不到 LocalUiOriginServer.kt（UX11 的本机静态来源端点）", server.isFile)
        val serverSrc = server.readText()

        // 只绑回环：外部网络访问不到这个端点。
        assertTrue(
            "本机静态来源端点必须只绑 127.0.0.1",
            serverSrc.contains("InetAddress.getByName(\"127.0.0.1\")"),
        )
        // 只服务 assets 静态资源，且 /api 一律 404（绝不代理 API）。
        assertTrue(
            "本机静态来源端点必须只服务 assets 静态资源",
            serverSrc.contains("assets.open(assetPath)"),
        )
        assertTrue(
            "本机静态来源端点必须显式拒答 /api（红线：不许把 API 代理过去）",
            serverSrc.contains("refuseApiPath") && serverSrc.contains("p.startsWith(\"/api/\")"),
        )
        // 🔴 最关键：静态来源端点里不得出现任何凭据字样。
        listOf("Authorization", "password", "Basic", "authTokenFromCredentials", "KEY_SERVER_PASSWORD")
            .forEach { banned ->
                assertFalse(
                    "本机静态来源端点不得出现凭据相关字样：$banned",
                    serverSrc.contains(banned),
                )
            }
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
            scanResiduals(allMainSourcesExceptAuthorized, "main").isEmpty(),
        )
    }
}
