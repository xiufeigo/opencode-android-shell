package com.xiufeigo.opencodeshell.web

import com.xiufeigo.opencodeshell.web.host.LocalUiOriginServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * UX11 · **本机 http 静态来源端点**（官方前端自带 + 本机 http 加载）绊线。
 *
 * 本类钉住这个架构改动的全部不变量。它与 [DirectConnectAuthBoundaryTest] 是
 * 互补的两半：那边管"凭据/代理整体不回来"，这边管"新授权的那个端点是不是
 * 仍然只服务 UI 静态资源"。
 *
 * ## 形态
 *
 * 官方前端由 `_upstream/packages/app` 的 `vite build` 产出后打进
 * `webapp/src/main/assets/www/ui/`，由 [com.xiufeigo.opencodeshell.web.host.LocalUiOriginServer]
 * 从 `http://127.0.0.1:4173` 提供。页面协议恒为 `http:` ⇒ 上游
 * `servers/connect/browser.ts:4` 的混合内容短路生效 ⇒ 同一页可同时添加
 * `http://` 与 `https://` 服务器。
 */
class LocalUiOriginServerTest {

    private fun moduleFile(relative: String): String {
        val f = File(relative)
        assertTrue("找不到 $relative（单测工作目录应为 webapp/ 模块目录）", f.isFile)
        return f.readText()
    }

    private val serverSrc: String
        get() = moduleFile("src/main/java/com/xiufeigo/opencodeshell/web/host/LocalUiOriginServer.kt")

    private val activitySrc: String
        get() = moduleFile("src/main/java/com/xiufeigo/opencodeshell/web/WebHostActivity.kt")

    private val indexHtml: String
        get() = moduleFile("src/main/assets/www/ui/index.html")

    // ---- 1) 官方前端确实被"自带"进来了 ---------------------------------------

    @Test
    fun officialFrontendIsBundledInAssets() {
        val uiRoot = File("src/main/assets/www/ui")
        assertTrue("官方前端必须打进 assets/www/ui/（自带，不是由某台服务器提供）", uiRoot.isDirectory)

        // 官方前端入口：vite 产物的 index.html。
        assertTrue("自带入口缺失：src/main/assets/www/ui/index.html", indexHtml.contains("<title>OpenCode</title>"))

        // SPA 的 JS 产物目录必须一起进来，否则页面起来是空白。
        // ⚠️ 磁盘目录名是 `oa/` 而不是官方的 `_assets/`：AGP 资源合并会丢弃下划线开头的
        // 目录（实测 890 个 chunk 全被过滤），端点把 `/_assets/x` 映射到 `/oa/x`，
        // **官方产物内容与页面 URL 一字未改**（见 LocalUiOriginServer.toDiskPath）。
        val assets = File("src/main/assets/www/ui/oa")
        assertTrue("官方前端 JS 产物目录缺失：oa/（= 官方产物的 _assets/）", assets.isDirectory)
        val jsCount = assets.listFiles()?.count { it.isFile && it.name.endsWith(".js") } ?: 0
        assertTrue("自带 JS 产物过少（$jsCount 个）⇒ 页面起不来", jsCount >= 50)

        // 主题预载脚本：官方 UI 的浅/深色首屏就靠它（UX4 已验收项的依赖）。
        assertTrue(
            "主题预载脚本缺失：oc-theme-preload.js（会破坏浅/深/跟随主题）",
            File("src/main/assets/www/ui/oc-theme-preload.js").isFile,
        )
    }

    @Test
    fun bundledIndexUsesRootRelativeAssetUrls() {
        // 官方前端用 `/xxx` 根相对路径取资源。静态来源把 assets/www/ui 当根 ⇒ 必须成立。
        val html = indexHtml
        assertTrue(
            "官方前端应使用根相对资源路径（静态来源以 ui/ 为根提供）",
            html.contains("href=\"/icons/") || html.contains("src=\"/_assets/") ||
                html.contains("href=\"/site.webmanifest\""),
        )
        // 主题预载必须被内联进 index.html，否则首帧会闪白。
        assertTrue(
            "主题预载脚本必须内联进自带 index.html（官方 vite 插件产物）",
            html.contains("oc-theme-preload-script"),
        )
    }

    // ---- 2) 端点只服务 UI 静态资源 -------------------------------------------

    @Test
    fun endpointServesOnlyStaticUiAssets() {
        assertTrue(
            "静态来源端点必须只从 assets 读文件",
            serverSrc.contains("assets.open(assetPath)"),
        )
        assertTrue(
            "端点必须从官方产物目录提供",
            serverSrc.contains("const val ASSET_ROOT = \"www/ui\""),
        )
        // 官方产物里的 `/_assets/` 在磁盘上落在 `oa/`（AGP 会丢下划线开头的目录），
        // 端点做路径映射，**官方产物内容与页面 URL 一字不改**。
        assertTrue(
            "必须把官方产物的 /_assets/ 映射到磁盘的 oa/（AGP 过滤下划线目录）",
            serverSrc.contains("URL_BUNDLE_PREFIX = \"/_assets/\"") &&
                serverSrc.contains("BUNDLE_DIR = \"oa\""),
        )
    }

    @Test
    fun terminalChunkUsesPackagedMobileAdapter() {
        assertTrue(serverSrc.contains("normalized == \"/oc-terminal.js\""))
        assertTrue(serverSrc.contains("assets.open(\"www/oc-terminal.js\")"))
        assertTrue(File("src/main/assets/www/oc-terminal.js").length() > 100_000)
        val adapter = moduleFile("../frontend/terminal/terminal.js")
        assertTrue(adapter.contains("from \"@xterm/xterm\""))
        assertTrue(adapter.contains("SerializeAddon"))
    }

    @Test
    fun endpointHasSpaFallback() {
        // 官方前端是 SPA：深链（/session/xxx、/settings）必须回落到 index.html。
        assertTrue(
            "静态来源端点必须有 SPA 回落（否则深链 404 成白屏）",
            serverSrc.contains("SPA 回落") && serverSrc.contains("readAsset(\"/index.html\")"),
        )
    }

    @Test
    fun endpointRefusesApiPaths() {
        // 🔴 红线：不许把 API 代理过去。静态来源对 /api 一律 404，页面直连用户配置的服务器。
        assertTrue("必须显式拒答 /api", serverSrc.contains("refuseApiPath"))
        assertTrue(
            "必须匹配 /api/ 前缀",
            serverSrc.contains("p.startsWith(\"/api/\")"),
        )
        // 反向验证：拒答逻辑真的会被触发（把扫描器钉在真实源码上）。
        assertFalse(
            "当前源码里不应出现把 API 转发出去的写法（正向读取后回落到 UI）",
            serverSrc.contains("request.getInputStream") && serverSrc.contains("backUrl"),
        )
    }

    // ---- 3) 端口固定、避开 4095、只绑回环 -------------------------------------

    @Test
    fun endpointPortIsFixedAndAvoidsBackend() {
        assertEquals(
            "本机静态来源端口必须固定",
            "http://127.0.0.1:4173",
            LocalUiOriginServer.origin(),
        )
        assertTrue(
            "本机静态来源端口不得与后端 4095 冲突",
            LocalUiOriginServer.DEFAULT_PORT != 4095,
        )
    }

    @Test
    fun endpointBindsLoopbackOnly() {
        assertTrue(
            "端点必须只绑回环地址（外部网络访问不到）",
            serverSrc.contains("InetAddress.getByName(\"127.0.0.1\")"),
        )
    }

    // ---- 4) 页面确实从本机来源加载 -------------------------------------------

    @Test
    fun activityLoadsPageFromLocalOrigin() {
        assertTrue(
            "启动链必须加载本机静态来源",
            activitySrc.contains("LocalUiOriginServer.origin()"),
        )
        assertTrue(
            "onCreate 必须先起本机静态来源再加载页面",
            activitySrc.contains("LocalUiOriginServer(assets).also { it.start() }"),
        )
        assertTrue(
            "onDestroy 必须停掉端点（不泄漏监听）",
            activitySrc.contains("localUiServer.stop()"),
        )
    }

    // ---- 5) 路径归一化的边界（穿越防护） -------------------------------------

    @Test
    fun pathNormalizationRejectsTraversal() {
        val f = File("src/main/java/com/xiufeigo/opencodeshell/web/host/LocalUiOriginServer.kt")
        assertTrue(f.isFile)
        // 反向验证：源码里必须有 `..` 的消除逻辑（assets 读取前先归一化）。
        assertTrue(
            "路径归一化必须消除 `..`（防目录穿越出 ui/ 根）",
            f.readText().contains("\"..\" ->"),
        )
    }

    // ---- 6) 不许借这次改动把自建页请回来 -------------------------------------

    @Test
    fun noCustomShellUiIntroduced() {
        // 本轮新增的只能是"服务官方产物的静态端点"，不得引入任何自造界面。
        listOf("showSetupDialog", "showNotConfiguredShell", "NotConfiguredShell", "ocshell")
            .forEach { banned ->
                assertFalse(
                    "不得借这次改动引入自建页/启动配置对话框：$banned",
                    serverSrc.contains(banned) || activitySrc.contains(banned),
                )
            }
    }
}
