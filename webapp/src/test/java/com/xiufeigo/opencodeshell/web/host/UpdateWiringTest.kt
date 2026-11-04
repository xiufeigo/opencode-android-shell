package com.xiufeigo.opencodeshell.web.host

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * App 内更新检查的**接线断言**（源码级绊线）。
 *
 * ## 为什么要有这一层
 *
 * [UpdateCheckerTest] 测的是**决策**（解析/比较/节流）。但"决策对了"不等于
 * "产品真的做到了"——接线断了照样一片绿：
 *  - 检查压根没排程 ⇒ 永远不检查（绿）；
 *  - 网络跑在主线程 ⇒ 卡 UI（绿）；
 *  - 通知渠道没建 ⇒ post 静默失败（绿）；
 *  - 带了 token ⇒ 违反"公开 API 无 token"的约定（绿）。
 *
 * 这些都只能靠**读源码**钉住（跟 `NotificationClickPlanTest` / `PowerWiringTest`
 * 同一套路）。⚠️ 绊线的"可反向验证"依赖 `build.gradle.kts` 里把相关源文件
 * 声明成测试任务输入 —— 否则只改 .kt 时 `testDebugUnitTest` 会被判 UP-TO-DATE
 * **整段跳过**，本文件就成了"假绿"。
 *
 * ## 每条断言分别能红在哪
 * - `updateCheckIsScheduledAtProcessLevel`：从 `WebHostApp` 摘掉排程 ⇒ 红。
 * - `networkHappensOffTheMainThread`：把网络搬回主线程 ⇒ 红。
 * - `releaseApiIsPublicAndTokenless`：偷偷加 `Authorization` 头 ⇒ 红。
 * - `notifierHasDedicatedUpdateChannel`：渠道名改了 ⇒ 红。
 * - `updateNotificationOpensTheDownloadUrl`：点击不打开 URL ⇒ 红。
 * - `throttleStateLivesInSharedPreferences`：节流键没落盘 ⇒ 红。
 * - `activityRegistersAndUnregistersTheListener`：漏注销 ⇒ 红。
 * - `localVersionComesFromBuildConfig`：把版本号写死 ⇒ 红（发版 bump 就失联）。
 */
class UpdateWiringTest {

    private fun moduleFile(relative: String): String {
        val f = File(relative)
        assertTrue("找不到 $relative（单测工作目录应为 webapp/ 模块目录）", f.isFile)
        return f.readText()
    }

    private val notifierSource: String get() =
        moduleFile("src/main/java/com/xiufeigo/opencodeshell/web/host/NotifierHost.kt")

    private val checkerSource: String get() =
        moduleFile("src/main/java/com/xiufeigo/opencodeshell/web/host/AppUpdateChecker.kt")

    private val updateCheckerSource: String get() =
        moduleFile("src/main/java/com/xiufeigo/opencodeshell/web/host/UpdateChecker.kt")

    private val appSource: String get() =
        moduleFile("src/main/java/com/xiufeigo/opencodeshell/web/WebHostApp.kt")

    private val activitySource: String get() =
        moduleFile("src/main/java/com/xiufeigo/opencodeshell/web/WebHostActivity.kt")

    // ---- 1. 排程 --------------------------------------------------------

    @Test
    fun updateCheckIsScheduledAtProcessLevel() {
        assertTrue(
            "更新检查必须挂在进程级（WebHostApp）—— 挂在 Activity 上会漏掉" +
                "「Activity 已退出但进程还在」这一段的复查。",
            appSource.contains("AppUpdateChecker.schedule(this)"),
        )
        assertTrue(
            "检查必须**延迟**执行（不能抢首屏网络）：启动延迟必须是正的。",
            AppUpdateChecker.STARTUP_DELAY_MS > 0L,
        )
        assertTrue(
            "排程延迟常量必须是 3–8 秒之间（任务约定的节流窗口）。",
            3_000L <= AppUpdateChecker.STARTUP_DELAY_MS && AppUpdateChecker.STARTUP_DELAY_MS <= 8_000L,
        )
        assertTrue(
            "必须用 Handler 延迟（而不是同步调用）—— 同步调用会把网络请求塞进冷启动关键路径。",
            checkerSource.contains("mainHandler.postDelayed"),
        )
    }

    // ---- 2. 网络不在 UI 线程 -------------------------------------------

    @Test
    fun networkHappensOffTheMainThread() {
        assertTrue(
            "网络必须跑在后台线程池上 —— UI 线程发 HTTP 会直接卡住首屏。",
            checkerSource.contains("Executors.newSingleThreadExecutor"),
        )
        assertTrue(
            "检查入口必须把网络丢进 executor（而不是在调用方线程直接跑）。",
            checkerSource.contains("executor.execute"),
        )
        assertFalse(
            "别在主线程 Handler 上直接发请求（`mainHandler.post { fetch... }` 这类写法会把" +
                "阻塞 IO 挪回主线程）。",
            Regex("""mainHandler\.post\s*\{[^}]*fetch""").containsMatchIn(checkerSource),
        )
        assertTrue(
            "必须有连接/读取超时 —— 没有超时的请求会一直挂着占线程。",
            checkerSource.contains("connectTimeout") && checkerSource.contains("readTimeout"),
        )
    }

    // ---- 3. 公开 API、无 token ------------------------------------------

    @Test
    fun releaseApiIsPublicAndTokenless() {
        assertTrue(
            "Release 源必须是公开 API 的 releases/latest。",
            UpdateChecker.RELEASES_API ==
                "https://api.github.com/repos/xiufeigo/opencode-android-shell/releases/latest",
        )
        assertTrue(
            "请求必须指向那个公开端点。",
            checkerSource.contains("UpdateChecker.RELEASES_API"),
        )
        // 只看**真正发出去的请求头**（`setRequestProperty("…")`），不看注释里的字样。
        val requestHeaders = Regex("""setRequestProperty\(\s*"([^"]+)"""")
            .findAll(checkerSource)
            .map { it.groupValues[1] }
            .toList()
        assertFalse(
            "🔴 公开 API **不需要也不该带 token** —— 加了 Authorization 头既违反约定，" +
                "又会在仓库被转私有时静默带上凭据。当前请求头：$requestHeaders",
            requestHeaders.any { it.equals("Authorization", ignoreCase = true) },
        )
        assertFalse(
            "不该把凭据塞进 URL 查询串（当前仓库是公开仓，压根不需要凭据）。",
            Regex("""access_token|api_key|client_secret""").containsMatchIn(checkerSource),
        )
    }

    // ---- 4. 失败静默 ----------------------------------------------------

    @Test
    fun failuresStaySilent() {
        assertTrue(
            "非 2xx 必须当作「没有更新」（而不是抛出去）。",
            checkerSource.contains("code !in 200..299"),
        )
        assertTrue(
            "解析失败必须返回 null 让上层静默。",
            updateCheckerSource.contains("runCatching { JsonLite.parseObject(json) }"),
        )
        assertFalse(
            "别在检查路径上把异常抛给 UI（会变成崩溃）。",
            checkerSource.contains("throw RuntimeException"),
        )
    }

    // ---- 5. 通知渠道与落点 ---------------------------------------------

    @Test
    fun notifierHasDedicatedUpdateChannel() {
        assertTrue(
            "更新提醒必须有自己的渠道 `oc_updates`（与回合完成分开，用户可单独关掉）。",
            notifierSource.contains("""const val CHANNEL_UPDATES = "oc_updates""""),
        )
        assertTrue(
            "ensureChannels 里必须真的建这个渠道，否则 post 会静默失败。",
            notifierSource.contains("CHANNEL_UPDATES,"),
        )
    }

    @Test
    fun updateNotificationOpensTheDownloadUrl() {
        assertTrue(
            "更新通知的点击必须用 ACTION_VIEW 打开下载链接。",
            notifierSource.contains("Intent.ACTION_VIEW"),
        )
        assertTrue(
            "通知必须挂在更新渠道上（不是回合渠道）。",
            notifierSource.contains("NotificationCompat.Builder(context, CHANNEL_UPDATES)"),
        )
        assertFalse(
            "更新通知不该复用回合通知的 requestCode（否则两条 PendingIntent 互相覆盖）。",
            notifierSource.contains("requestCode = plan.requestCode)"),
        )
    }

    // ---- 6. 节流落盘 ----------------------------------------------------

    @Test
    fun throttleStateLivesInSharedPreferences() {
        assertTrue(
            "节流键必须落盘（同一远端版本只提醒一次 ⇒ 跨启动也要记住）。",
            checkerSource.contains("KEY_LAST_NOTIFIED") && checkerSource.contains("KEY_LAST_DIALOG"),
        )
        assertTrue(
            "节流状态必须存在 SharedPreferences 里。",
            checkerSource.contains("getSharedPreferences"),
        )
        assertTrue(
            "通知真正发出去之后才记节流键（没权限时下次启动还能再提醒）。",
            checkerSource.contains("if (posted == 0)"),
        )
    }

    // ---- 7. Activity 侧 --------------------------------------------------

    @Test
    fun activityRegistersAndUnregistersTheListener() {
        assertTrue(
            "Activity 必须注册更新回调（否则没有启动时的对话框）。",
            activitySource.contains("AppUpdateChecker.addListener(updateListener)"),
        )
        assertTrue(
            "Activity 必须注销更新回调 —— 漏注销会把回调打到已销毁的 Activity 上。",
            activitySource.contains("AppUpdateChecker.removeListener(updateListener)"),
        )
        assertTrue(
            "对话框落点优先 `.apk` 直链。",
            activitySource.contains("offer.downloadUrl"),
        )
    }

    // ---- 8. 本机版本口径 ------------------------------------------------

    @Test
    fun localVersionComesFromBuildConfig() {
        assertTrue(
            "本机版本必须读 BuildConfig.VERSION_NAME（不许写死 —— 发版 bump 会失联）。",
            checkerSource.contains("BuildConfig.VERSION_NAME"),
        )
        assertFalse(
            "不许把版本号写死成字符串常量（发版 bump 后就永远比不出来）。",
            Regex("""VERSION_NAME\s*=\s*""").containsMatchIn(checkerSource),
        )
    }
}
