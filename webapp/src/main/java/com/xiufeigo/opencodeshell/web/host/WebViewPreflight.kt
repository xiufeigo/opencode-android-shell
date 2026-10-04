package com.xiufeigo.opencodeshell.web.host

/**
 * G1：WebView 前置能力检查 + 运行期兜底的**纯逻辑部分**（不碰任何 Android API，
 * 所以能被普通 JUnit 单测直接覆盖 —— 本文件是 4 条绊线所守护的对象）。
 *
 * ## 要修的实测失败模式
 * 官方前端是**现代 ESM** 构建产物。`emulator-5730`（android-30 / `com.android.webview`
 * **83.0.4103.120**）上实测：
 *
 * ```
 * I OcWebHost: web[ERROR] Uncaught SyntaxError: Unexpected token '=' @2
 * ```
 *
 * ⇒ 主文档和 250+ 个 `/_assets/` 资源**全部 200 取回**，`onPageFinished` **照常回调**，
 * 但脚本一个模块都没执行 ⇒ 屏幕**纯白**（截图 `B5730_official_ui.png` 仅 21295 字节 = 极低熵，
 * 肉眼只有状态栏 + 导航栏）。用户既看不到提示，也无法自救。
 *
 * 对照实测：**124.0.6367.219**（E6/E7/P5）与 **149.0.7827.5**（E3/E4）都正常渲染官方 UI。
 *
 * ## 为什么阈值是 [MIN_MAJOR] = 105（三条依据）
 * 1. **下限侧（必须挡住的）**：83 实测失败 ⇒ 83 < 105 落进"过旧"分支。
 * 2. **通过侧（不能误伤的）**：124 / 149 实测成功 ⇒ 都 ≥ 105，走正常加载。
 * 3. **本仓自身依赖**：`IME_OCCLUSION_FIX.md:254/541` 明确记录 IME 遮挡修复用了 CSS `:has()`，
 *    其最低支持版本是 **Chrome 105**；低于 105 这条修复规则**整条失效**（静默退化）。
 *    ⇒ 105 是"壳自己也不完整"的真实地板，不是一个凭空的数。
 *
 * ⚠️ **未核对**：105–123 这个区间**没有本仓实测数据**（本机只有 83 / 124 / 149 三个点）。
 * 选 105 是**偏保守**的下界 —— 它只保证"124 及以上一定不被挡"，代价是 105–123 仍会走兜底。
 * 若将来在 105–123 上实测通过，把 [MIN_MAJOR] 调低即可（单测会跟着红，红了才是提醒）。
 *
 * ## 为什么**读不出**版本时不拦（防误伤的关键设计）
 * `WebView.getCurrentWebViewPackage()` 理论上可能返回 null（部分定制 ROM / 极少数设备）。
 * 此刻我们**没有任何证据**说它不能跑 ⇒ 一律**放行**（只把运行期看门狗留着），
 * 而不是"读不到就当过旧"把能跑的设备挡在门外。同理 `onReceivedError` 只对
 * **主文档**（`isForMainFrame`）动手，子资源 404 不触发。
 */
object WebViewPreflight {

    /** 低于这个主版本号就判"过旧"。依据见类注释（83 失败 / 124·149 成功 / `:has()` 需 105）。 */
    const val MIN_MAJOR = 105

    /**
     * 首屏"有内容"的看门狗时限：**12 000 ms**。
     *
     * 依据（本机实测，见 `research/perf/STARTUP_AND_FRAMETIME.md` 与
     * `research/perf/POST_FIX_RETEST.md`）：
     *
     * | 信号 | 实测值 |
     * |---|---|
     * | FCP | 2130 / 2290 / 2228 / 2452 / **2856** ms |
     * | DOM 挂载完成（845 节点） | 2572 / **3063** ms |
     * | 宿主 `Z.firstDraw`（首帧白屏终点） | 1236 / 1491 ms |
     *
     * 取 **12 000 ms** 的理由：
     * - 下界必须是"**最差一次**的内容信号"而不是平均值：`3063 ms`（3063 × 3 = 9189 ms）。
     * - 12 000 / 3063 ≈ **3.9×**，给冷启动、代际更差的低端机、代理 bind（实测 225–246 ms）留足余量，
     *   同时**不误判正常慢启动**（这是本条最需要防的回归）。
     * - 它**不需要**覆盖 `TOP1` 那个"380 个语言/主题包串行拉到 31405 ms" —— 报告原文
     *   「**FCP 早它 29 s**」：FCP 才是"用户看到东西"的时刻，DOM 齐不齐与白屏无关。
     *
     * ⚠️ 12 s 是在**最差实测值的 ~3.9 倍**上取的；若将来在更慢的设备上实测到
     * > 12 s 的合法首屏，这里必须跟着调（单测 `theStallTimeoutHasHeadroomOverTheWorstObservedFirstContent`
     * 会把"≥ 最差实测值 × 2"钉成硬下限）。
     */
    const val LOAD_STALL_TIMEOUT_MS = 12_000L

    /**
     * 我们实测到过的**最差**首屏内容信号（ms）。`POST_FIX_RETEST.md` §3.1 的 DOM 挂载 3063 ms。
     * 单测用它给 [LOAD_STALL_TIMEOUT_MS] 钉下限。
     */
    const val WORST_OBSERVED_CONTENT_MS = 3_063L

    /** 停在哪一步 —— 诊断页据此换文案。 */
    enum class Reason(val wire: String) {
        /** 前置检查：WebView 主版本 < [MIN_MAJOR]。 */
        WEBVIEW_TOO_OLD("version"),

        /** 运行期：主文档 `onReceivedError`。 */
        LOAD_ERROR("loaderror"),

        /** 运行期：渲染进程崩了（`onRenderProcessGone`）。 */
        RENDER_CRASH("rendercrash"),

        /** 运行期：[LOAD_STALL_TIMEOUT_MS] 内首屏没有任何内容。 */
        STALLED("stalled"),
        ;

        companion object {
            fun fromWire(s: String?): Reason? = values().firstOrNull { it.wire == s }
        }
    }

    /** 前置检查的判定结果。 */
    sealed interface Verdict {
        /** 版本够新（或读不到）⇒ 正常加载官方 UI，但**仍然**要挂运行期看门狗。 */
        data class LoadOfficialUi(val major: Int?, val unknownVersion: Boolean) : Verdict

        /** 版本过旧 ⇒ **不加载**官方 UI，直接显示诊断页。 */
        data class ShowDiagnostic(val major: Int?, val versionName: String?, val reason: Reason) : Verdict

        val showsDiagnosticPage: Boolean get() = this is ShowDiagnostic
    }

    /**
     * 从 `PackageInfo.versionName`（或 UA 串）里取主版本号。
     *
     * - `"83.0.4103.120"` → `83`
     * - `"124.0.6367.219"` → `124`
     * - UA `"... Chrome/124.0.0.0"` → `124`（**必须**认 `Chrome/` 这类标记，
     *   否则会把 `Mozilla/5.0` 里的 `5` 当成版本号）
     * - `""` / `null` / `"unknown"` / `"WebView 0.0.0"` → `null`（**不是 0**）
     *
     * ⚠️ 读不到时**必须**返回 `null` 而不是回落到 0：0 会被判成"过旧"，
     * 那就正好把"能跑但读不到版本"的设备挡在门外了。
     */
    fun parseMajor(versionName: String?): Int? {
        val raw = versionName?.trim().orEmpty()
        if (raw.isEmpty()) return null
        // UA 里先认带标记的版本号（Chrome/124…、Version/124…），别去匹配 Mozilla/5.0。
        for (marker in listOf("Chrome/", "Version/", "WebView/")) {
            val i = raw.indexOf(marker, ignoreCase = true)
            if (i >= 0) {
                val digits = raw.substring(i + marker.length).takeWhile { it.isDigit() }
                val v = digits.toIntOrNull()
                if (v != null && v > 0) return v
            }
        }
        // 否则取第一个数字段（`83.0.4103.120` ⇒ 83）。
        val digits = raw.takeWhile { it.isDigit() || it == '.' }.takeWhile { it.isDigit() }
        val v = digits.toIntOrNull() ?: return null
        return if (v > 0) v else null
    }

    /** 版本过旧？`null`（读不到）**不算过旧** —— 见类注释的"防误伤"说明。 */
    fun isTooOld(major: Int?): Boolean = major != null && major < MIN_MAJOR

    /**
     * 前置检查。**唯一**的入口判定：调用方不许自己写 `major < 105`。
     *
     * @param versionName `WebView.getCurrentWebViewPackage()?.versionName`，读不到传 `null`。
     */
    fun preflight(versionName: String?): Verdict {
        val major = parseMajor(versionName)
        return if (isTooOld(major)) {
            Verdict.ShowDiagnostic(major, versionName?.trim(), Reason.WEBVIEW_TOO_OLD)
        } else {
            Verdict.LoadOfficialUi(major, unknownVersion = major == null)
        }
    }

    /**
     * 运行期看门狗的判定：到点了还没内容信号 ⇒ 走诊断页。
     *
     * @param elapsedMs 从 `loadUrl` 发起算起的经过时间。
     * @param contentSeen 是否已收到首屏内容信号（FCP / DOM 就绪 / 首帧）。
     */
    fun stallVerdict(elapsedMs: Long, contentSeen: Boolean): Reason? =
        if (!contentSeen && elapsedMs >= LOAD_STALL_TIMEOUT_MS) Reason.STALLED else null

    /**
     * `onReceivedError` 的判定：**只有主文档**的失败才算"整页挂掉"。
     * 子资源（图片/字体/单个 chunk）挂掉页面照样能用，拦下来反而是误伤。
     */
    fun loadErrorVerdict(isForMainFrame: Boolean): Reason? =
        if (isForMainFrame) Reason.LOAD_ERROR else null

    /**
     * 拼诊断页 URL。`index.html` 靠这几个 query 参数换文案并显示**真实读数**。
     *
     * 全部值都做 [java.net.URLEncoder] 编码 —— `detail` 里带的是 logcat 原文
     * （`Unexpected token '='`、URL 等），不编码会把 query 拆坏。
     */
    fun diagnosticUrl(
        baseUrl: String,
        reason: Reason,
        major: Int?,
        versionName: String?,
        detail: String? = null,
    ): String {
        val params = linkedMapOf(
            "r" to reason.wire,
            "m" to (major?.toString() ?: "?"),
            "v" to (versionName?.takeIf { it.isNotBlank() } ?: "未读到"),
            "min" to MIN_MAJOR.toString(),
            "t" to LOAD_STALL_TIMEOUT_MS.toString(),
        )
        detail?.takeIf { it.isNotBlank() }?.let { params["d"] = it.take(400) }
        val qs = params.entries.joinToString("&") { (k, v) -> "$k=${enc(v)}" }
        val sep = if (baseUrl.contains('?')) "&" else "?"
        return "$baseUrl$sep$qs"
    }

    private fun enc(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
