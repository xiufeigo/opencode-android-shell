package com.xiufeigo.opencodeshell.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * APK 升级路径 · **内嵌 UI 不得被旧 service worker 劫持** 绊线。
 *
 * ## 缺陷（真机复现）
 *
 * 官方 Web 产物在 `load` 后注册 `/sw.js`（`vite-plugin-pwa`，workbox 预缓存 +
 * `skipWaiting: false` / `clientsClaim: false`）。Android 的 UI 就在 APK 里、由
 * 本机 127.0.0.1 提供，预缓存没有任何收益，却会在**覆盖安装**后继续生效：
 *
 * - 旧 worker 仍接管导航，页面继续拿上一版 `index.html`；
 * - 新 APK 删掉的 chunk 在旧 manifest 里还被引用；
 * - 实测出现过整页 `ReferenceError: Cannot access 'R' before initialization`
 *   （新旧 chunk 混用），清应用数据才恢复。
 *
 * ## 修法（两半缺一不可）
 *
 * 1. `webapp/src/main/assets/www/oc-host.js` 在**文档开始**清理：反注册全部
 *    service worker、删除 Cache Storage；若当前文档正被旧 worker 接管，重载一次
 *    （`sessionStorage` 标记防循环）。
 * 2. 页面自己不再在 Android 上注册 SW（`frontend/android/upstream.patch` 里的
 *    `entry.tsx` 改为 `!android && ...`）；Web 分发（`platform: web`）保持官方行为。
 *
 * ## 本类钉住的不变量
 *  1. 清理在文档开始脚本里、且在 `__ocAndroidNative` 门禁之后（不污染普通 Web）；
 *  2. 反注册 + 删缓存 + 被接管时重载，三件事都在；
 *  3. 重载有 `sessionStorage` 防循环标记（否则会被接管状态反复重载）；
 *  4. 补丁里 Android 分支**不注册** SW，非 Android 分支仍注册；
 *  5. 反向验证：扫描器对"撤回修法"的样本必须判红（不是永远真）。
 */
class EmbeddedUiServiceWorkerTest {

    private fun projectFile(relative: String): File = File(relative).also {
        assertTrue("找不到 $relative（单测工作目录应是 webapp/ 模块目录）", it.isFile)
    }

    // The tracked file uses CRLF on Windows; assertions compare LF text.
    private val hostJs: String by lazy { projectFile("src/main/assets/www/oc-host.js").readText().replace("\r\n", "\n") }

    /** 补丁是纯文本：用它核对上游入口在 Android 上不再注册 SW。 */
    private val upstreamPatch: String by lazy { projectFile("../frontend/android/upstream.patch").readText() }

    /** 取出 `purgeEmbeddedUiCache` 的函数体（花括号配平）。 */
    private fun purgeBody(): String = hostJs.substring(purgeBodyStart(), purgeBodyEnd())

    private fun purgeBodyStart(): Int {
        val start = hostJs.indexOf("(function purgeEmbeddedUiCache() {")
        assertTrue("oc-host.js 里没有 purgeEmbeddedUiCache —— 升级清理被整体撤掉", start >= 0)
        return hostJs.indexOf('{', start)
    }

    /** 函数体闭括号之后必须是 `)();` —— 证明它被立即执行，而不只是定义了。 */
    private fun purgeBodyEnd(): Int {
        val open = purgeBodyStart()
        var depth = 0
        for (i in open until hostJs.length) {
            when (hostJs[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return i + 1
                }
            }
        }
        throw AssertionError("purgeEmbeddedUiCache 花括号不配平")
    }

    private fun purgeCalledImmediately(): Boolean =
        hostJs.substring(purgeBodyEnd()).trimStart().startsWith(")();")

    // ---- 1) 位置：文档开始、且只在 Android 宿主内 ------------------------------

    @Test
    fun purgeRunsInDocumentStartFacadeAfterNativeGate() {
        val nativeGate = hostJs.indexOf("var N = window.__ocAndroidNative;")
        val purge = hostJs.indexOf("(function purgeEmbeddedUiCache() {")
        assertTrue("oc-host.js 必须先做原生门禁（return）再清理缓存", nativeGate >= 0)
        assertTrue("清理必须在原生门禁之后（普通 Web 不带 __ocAndroidNative）", purge > nativeGate)
        assertTrue("清理必须被立即执行一次（不是只定义不调用）", purgeCalledImmediately())
    }

    // ---- 2) 三件事：反注册 / 删缓存 / 被接管时重载 ------------------------------

    @Test
    fun purgeUnregistersAndDeletesCaches() {
        val body = purgeBody()
        assertTrue("必须反注册 service worker（否则旧版本继续接管）", body.contains("getRegistrations()"))
        assertTrue("必须对每个注册调用 unregister()", body.contains(".unregister()"))
        assertTrue("必须删除 Cache Storage 里的预缓存", body.contains("caches.keys()") && body.contains("caches.delete("))
    }

    @Test
    fun purgeReloadsOnlyWhenAnOldWorkerControlsTheDocument() {
        val body = purgeBody()
        assertTrue("必须读取 navigator.serviceWorker.controller 判断是否被旧 worker 接管", body.contains("navigator.serviceWorker.controller"))
        assertTrue("被旧 worker 接管时必须重载一次，让 APK 内的新页面出图", body.contains("location.reload()"))
        assertTrue("缓存清理是异步的，重载必须排在清理之后", body.contains("Promise.all(work).then("))
    }

    @Test
    fun purgeReloadIsGuardedAgainstLoops() {
        val body = purgeBody()
        assertTrue("必须有 sessionStorage 防循环标记", body.contains("sessionStorage.getItem(marker)"))
        assertTrue("清理完成后必须写入标记", body.contains("sessionStorage.setItem(marker, \"purged\")"))
        assertTrue("标记命中时必须提前 return", Regex("""getItem\(marker\)\s*===\s*"purged"\)\s*return""").containsMatchIn(body))
    }

    @Test
    fun purgeNeverBreaksThePage() {
        val body = purgeBody()
        // WebView 可能禁用 storage / caches；任何一步失败都只能放弃清理，不能抛出。
        assertTrue("清理里的 storage 访问必须 try/catch 包裹", body.contains("try {") && body.contains("catch (e)"))
        assertTrue("Promise 失败必须被吞掉（拒绝分支存在）", body.contains("}, function () {});"))
    }

    // ---- 3) 补丁：Android 不注册，Web 照旧 -------------------------------------

    @Test
    fun androidEntryDoesNotRegisterServiceWorker() {
        assertTrue(
            "upstream.patch 里 entry.tsx 必须把注册改成 `!android && ...`（Android 内嵌 UI 不再注册 SW）",
            upstreamPatch.contains("+if (!android && import.meta.env.PROD && \"serviceWorker\" in navigator) {"),
        )
        assertFalse(
            "upstream.patch 里不应再有未加 android 条件的注册行（撤回修法必须变红）",
            upstreamPatch.contains("+if (import.meta.env.PROD && \"serviceWorker\" in navigator) {"),
        )
    }

    @Test
    fun webBuildStillRegistersTheOfficialWorker() {
        // Web 分发走同一补丁产物，但 `android` 为 false ⇒ 保留官方 PWA 行为。
        assertTrue(
            "非 Android（Web 分发）必须继续注册官方 SW，不能把 PWA 能力一起砍掉",
            upstreamPatch.contains("navigator.serviceWorker.register(\"/sw.js\")"),
        )
        assertTrue(
            "注册行必须仍在 load 事件里、once 语义不变",
            upstreamPatch.contains("window.addEventListener(\"load\", () => void navigator.serviceWorker.register(\"/sw.js\"), { once: true })"),
        )
    }

    // ---- 4) 反向验证：扫描器必须能变红 ----------------------------------------

    /** 模拟"撤回清理"的注入层：没有清理函数、没有调用。 */
    private val redHostNoPurge = """
        (function () {
          var N = window.__ocAndroidNative;
          if (!N) return;
          var backHandler = null;
        })();
    """.trimIndent()

    private fun purgePresent(source: String): Boolean =
        source.contains("(function purgeEmbeddedUiCache() {") &&
            Regex("""\}[\s]*\)\s*\(\);""").containsMatchIn(source) &&
            source.contains("getRegistrations()") &&
            source.contains(".unregister()") &&
            source.contains("location.reload()")

    @Test
    fun scannerFlagsSourceWithoutPurge() {
        assertFalse("反向验证失败：对没有清理的样本仍判'有清理' ⇒ 绊线是假绿", purgePresent(redHostNoPurge))
        assertTrue("扫描器必须对真实 oc-host.js 判'有清理' —— 否则是扫描器自己坏了", purgePresent(hostJs))
    }
}
