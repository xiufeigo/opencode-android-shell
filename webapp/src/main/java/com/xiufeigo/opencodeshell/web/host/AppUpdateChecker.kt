package com.xiufeigo.opencodeshell.web.host

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.xiufeigo.opencodeshell.web.BuildConfig
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * App 内更新检查：**联网 + 调度 + 发通知**（决策全在 [UpdateChecker] 这个纯逻辑件里）。
 *
 * ## 数据流
 *
 * ```
 * 启动(或周期) ──延迟 5s──▶ 后台线程: GET releases/latest
 *                                │ 失败 ⇒ 静默（最多一行日志）
 *                                ▼
 *                          UpdateChecker.decide(远端, 本机, 上次通知, 上次弹窗)
 *                                │ 有新版
 *                                ├──▶ NotifierHost.postUpdateAvailable(…)  ← 系统通知
 *                                └──▶ 监听者（Activity）⇒ 弹一次性对话框
 * ```
 *
 * ## 硬约束
 *
 * 1. **绝不在 UI 线程做网络** —— 网络跑在单线程 [executor] 上，UI 侧只收回调。
 * 2. **网络失败静默** —— 401/403/404/超时/解析失败一律当作"没有更新"，
 *    最多一行 `Log.w`，绝不弹窗、绝不崩溃、绝不刷屏。
 * 3. **无 token** —— 仓已公开，公开 API 匿名可读（见 [UpdateChecker.RELEASES_API]）。
 * 4. **同一远端版本只提醒一次** —— 节流状态存在普通 `SharedPreferences`
 *    （**不是**秘密，所以不进 [SecretStore] 的加密偏好）。
 */
object AppUpdateChecker {
    private const val TAG = "OcWebHost/Update"

    /** 节流用的偏好文件名。 */
    const val PREFS_NAME = "oc_updates"

    /** 上一次**已发过通知**的远端版本号（节流键）。 */
    const val KEY_LAST_NOTIFIED = "lastNotifiedVersion"

    /** 上一次**已弹过对话框**的远端版本号（节流键）。 */
    const val KEY_LAST_DIALOG = "lastDialogVersion"

    /**
     * 启动后延迟多久才去问一次。
     *
     * 取 5s（任务要求的 3–8s 区间中位）：冷启动那几秒正在建 WebView、连后端，
     * 此时再插一次 GitHub 请求只会跟首屏抢网络；晚一点问，用户也已经看到界面了。
     */
    const val STARTUP_DELAY_MS = 5_000L

    /**
     * 进程存活期间的**复查**间隔（6 小时）。
     *
     * 为什么还要复查：应用可能连续开着好几天，期间上游发了新版。
     * 6 小时 ⇒ 一天最多 4 次请求，节流上又由"同一版本只提醒一次"兜底，
     * 所以既不会刷屏也不会把流量打满。
     */
    const val PERIODIC_INTERVAL_MS = 6L * 60L * 60L * 1000L

    /** 建连超时。 */
    private const val CONNECT_TIMEOUT_MS = 8_000

    /** 读超时。 */
    private const val READ_TIMEOUT_MS = 8_000

    /** 响应体上限（Release JSON 只有几 KB；上限只是防"被返回了一个大文件"）。 */
    private const val MAX_BODY_BYTES = 256 * 1024

    /** 有新版时通知给 UI 侧的回调。 */
    fun interface UpdateListener {
        fun onUpdateAvailable(offer: UpdateChecker.Offer)
    }

    /** 后台线程池（单线程 ⇒ 同一时刻最多一个检查在飞）。 */
    private val executor by lazy {
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "oc-update-check").apply { isDaemon = true }
        }
    }

    /** 主线程 Handler（回调、周期排程都在主线程）。 */
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /** 监听者（Activity 注册/注销）。 */
    private val listeners = CopyOnWriteArrayList<UpdateListener>()

    /** 周期排程的 runnable（只排一次，重排时先移除旧的，避免叠加）。 */
    private var periodicRunnable: Runnable? = null

    // ======================================================================
    // 对外接口
    // ======================================================================

    /**
     * 排一次**启动后延迟**的检查，并在进程存活期间按 [PERIODIC_INTERVAL_MS] 复查。
     *
     * 挂在 [com.xiufeigo.opencodeshell.web.WebHostApp]（进程级），
     * 这样即使 Activity 已经退出，进程还在时也能复查到新版。
     */
    fun schedule(context: Context) {
        val app = context.applicationContext
        val runnable = object : Runnable {
            override fun run() {
                checkAsync(app)
                mainHandler.postDelayed(this, PERIODIC_INTERVAL_MS)
            }
        }
        periodicRunnable?.let { mainHandler.removeCallbacks(it) }
        periodicRunnable = runnable
        mainHandler.postDelayed(runnable, STARTUP_DELAY_MS)
    }

    /** 注册"有新版"监听者（Activity 用它弹一次性对话框）。 */
    fun addListener(listener: UpdateListener) {
        listeners.addIfAbsent(listener)
    }

    /** 注销监听者（Activity 销毁时必须调，否则回调打到死 Activity 上）。 */
    fun removeListener(listener: UpdateListener) {
        listeners.remove(listener)
    }

    /**
     * 立刻在后台线程做一次检查（不回调 UI，仅发通知 + 通知监听者）。
     *
     * 网络与解析都在后台线程 ⇒ **UI 线程不做任何网络**。
     */
    fun checkAsync(context: Context) {
        val app = context.applicationContext
        executor.execute {
            val result = runCatching { fetchLatestRelease() }
            val release = result.getOrNull()
            if (release == null) {
                // 网络失败/响应不可解析 ⇒ 静默。**只记一行**，不刷屏、不打断用户。
                Log.w(TAG, "更新检查失败：${result.exceptionOrNull()?.javaClass?.simpleName ?: "未知原因"}")
                return@execute
            }
            handle(app, release)
        }
    }

    // ======================================================================
    // 内部
    // ======================================================================

    /**
     * 拉取最新 Release 的 JSON。
     *
     * @return 拉取或解析失败时返回 `null`（调用方静默处理）。
     */
    private fun fetchLatestRelease(): UpdateChecker.ReleaseInfo? {
        val conn = (URL(UpdateChecker.RELEASES_API).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            // 公开 API 匿名可读 ⇒ **不带任何鉴权头**（更不带任何凭据）。
            // ⚠️ 仓一旦转私有，这个端点会 404/401 ⇒ 静默失败（见下面的状态码处理）。
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty(
                "User-Agent",
                "OpenCode-Android-Shell/${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            )
        }
        return try {
            val code = conn.responseCode
            if (code !in 200..299) {
                // 404 = 仓改名/转私有；403 = 限流；401 = 需要鉴权。**都不是崩溃理由**。
                Log.w(TAG, "GitHub Release API 返回 HTTP $code")
                return null
            }
            val body = conn.inputStream.use { input ->
                val buffer = ByteArray(8 * 1024)
                val out = ByteArrayOutputStream()
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    out.write(buffer, 0, read)
                    if (out.size() >= MAX_BODY_BYTES) break
                }
                String(out.toByteArray(), Charsets.UTF_8)
            }
            UpdateChecker.parseRelease(body)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 拿到远端 Release 后的**落地**（通知 + 通知监听者）。
     *
     * 决策（比版本、该不该提醒）在 [UpdateChecker.decide] 里；这里只负责执行。
     */
    private fun handle(context: Context, release: UpdateChecker.ReleaseInfo) {
        val prefs = prefs(context)
        val decision = UpdateChecker.decide(
            release = release,
            localVersion = UpdateChecker.parseVersionTag(BuildConfig.VERSION_NAME),
            lastNotified = prefs.getString(KEY_LAST_NOTIFIED, null),
            lastDialog = prefs.getString(KEY_LAST_DIALOG, null),
        )
        if (decision.offer == null) {
            Log.i(TAG, "更新检查：${decision.reason}")
            return
        }
        val offer = decision.offer
        Log.i(TAG, "更新检查：${decision.reason}")

        if (decision.notify) {
            val posted = NotifierHost.postUpdateAvailable(
                context = context,
                title = "发现新版本 ${offer.version.normalized}",
                body = "当前版本 ${BuildConfig.VERSION_NAME}，点击下载 ${offer.version.normalized}。",
                url = offer.downloadUrl ?: offer.htmlUrl ?: UpdateChecker.REPO_RELEASES_PAGE,
            )
            // 只有**真的发出去**才记节流键：没权限时下次启动还能再提醒一次。
            if (posted == 0) {
                prefs.edit().putString(KEY_LAST_NOTIFIED, offer.throttleKey).apply()
            }
        }

        if (decision.dialog) {
            prefs.edit().putString(KEY_LAST_DIALOG, offer.throttleKey).apply()
            mainHandler.post {
                listeners.forEach { listener ->
                    runCatching { listener.onUpdateAvailable(offer) }
                        .onFailure { Log.w(TAG, "更新回调失败", it) }
                }
            }
        }
    }

    /** 节流状态（普通偏好：不是秘密）。 */
    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
