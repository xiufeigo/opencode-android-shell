package com.xiufeigo.opencodeshell.web.host

/**
 * F5 · 接入错误态（后端不可达 / 认证失败）—— **纯逻辑**，无 Android 依赖，故可被
 * `:webapp:testDebugUnitTest` 直接覆盖。
 *
 * ## 为什么单独一个类
 *
 * F5 要修的两条真缺陷（V7 实测，证据 `research/env/v7/v7_n5_*.png`）**根因是同一处断层**：
 * 代理层**已经知道真相**（logcat 里有 `auth rejected … 401`、有 `upstream … failed`），
 * 但**页面层没把真相告诉用户**。V7 的两张截图就是这条断层的直接后果：
 *
 *  - `v7_n5_noreverse2.png` —— 整页只有一行**裸 JSON**：
 *    `{"error":"unexpected end of stream on http://127.0.0.1:4095/…"}`
 *    ⇒ 既不是人话，又**泄露内部 URL 与端口**。
 *  - `v7_n5_wrongpw.png` —— 代理持续 401，页面**永远停在 "Loading" 骨架屏**，
 *    用户既看不到"认证失败"，也**没有重试/改口令的入口**（不可自救）。
 *
 * 所以本类只做一件事：**把"用户自己能确认的东西"变成一页能看懂、能点的界面**。
 *
 * ## 三条不可让步的规矩
 *
 *  1. **不泄露内部信息**。[redact] 把任何 `scheme://host:port/…` 形如的片段替换成
 *     `<已隐藏>`，并且**只**把这一句放进产物：让用户去检查**服务器地址**与**网络** ——
 *     这两样用户自己知道、也自己能改，不需要知道我们内部用了哪个端口。
 *  2. **必须能自救**：每页都有一个 **≥48dp** 的重试按钮（`MIN_TOUCH_TARGET_CSS_PX`
 *     与 [SetupDialogMetrics] 的 48dp 硬要求同源，见 `research/web/TOUCH_TARGET_FIX.md`）。
 *  3. **不碰口令**：[authFailedPageHtml] 的文案里只有"用户名或口令不对"，
 *     **永不回显口令、永不打印 Basic 头**（口令只存在于 `SecretStore` 与转发瞬间的请求头）。
 *
 * ## 为什么用 HTML 而不是原生 View
 *
 * 失败发生在**文档请求**（`GET /`）上：此时 WebView 已经把代理 origin 当成了页面 origin，
 * 我们要在这个 origin 上给出一页自洽的内容。原生对话框只能盖在上面、且无法覆盖
 * "页面已经 502 之后刷新重试"这条路径。HTML 页面同时覆盖两条：**首屏**与**点重试后的再次失败**。
 */
object ConnectErrorPage {

    /**
     * 触摸靶硬要求 48dp。CSS px 在本宿主里等于 dp（WebView 默认 `textZoom = 1`，
     * 宿主不注入缩放；见 `WebHostActivity.configureSettings`），
     * 所以 **48 CSS px 即 48dp**，下方按钮显式写死 48，不靠内容撑高。
     */
    const val MIN_TOUCH_TARGET_CSS_PX = 48

    /** 允许出现在界面上的"排错线索"文案：只提用户自己能确认/能改的东西。 */
    const val HINT_NETWORK = "请检查服务器地址是否正确、设备是否连上了服务器所在的网络，然后重试。"

    /**
     * 口令错的提示：**明确说"用户名或口令不对"**，但不说是哪一个
     * （Basic 认证下后端只回 401，分不清用户名错还是口令错，猜错比不说更糟）。
     * 派单要求"401 ⇒ 明确判定用户名或口令不对"，所以这句话是**硬性文案**，
     * 有单测守着（`ConnectRobustnessTest`）。
     */
    const val HINT_AUTH = "服务器拒绝了登录：用户名或口令不对。请确认后重试，或重新输入口令。"

    /**
     * 内部细节一律替换成这个占位。**界面上永不出现真实 host:port。**
     */
    const val REDACTED = "<已隐藏>"

    private val URL_LIKE = Regex("""(?i)\b[a-z][a-z0-9+.\-]*://[^\s"'<>)\]]+""")

    /**
     * 把任何看起来像 URL/带端口的片段替换成 [REDACTED]。
     *
     * 之所以要"连异常原文一起过一遍"：`e.message` 这种异常串里**必然**带
     * `http://127.0.0.1:4095/...`（V7 截图里泄露的就是它）。**我们不删异常原文、
     * 也不改日志**（排错要靠它），只是**不把它印到用户眼前**。
     */
    fun redact(raw: String?): String {
        if (raw.isNullOrEmpty()) return REDACTED
        val stripped = URL_LIKE.replace(raw, REDACTED)
        // 只剩端口号形态（`127.0.0.1:4095`）也要兜住：异常偶尔不带 scheme。
        val hostPort = Regex("""\b\d{1,3}(\.\d{1,3}){3}:\d{2,5}\b""")
        return hostPort.replace(stripped, REDACTED)
    }

    /**
     * HTML 转义。错误串会进 `title`，不能让它有机会闭合标签。
     */
    fun escapeHtml(raw: String): String = buildString {
        for (ch in raw) when (ch) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&#39;")
            else -> append(ch)
        }
    }

    /**
     * 失败原因 ⇒ 用户可读的分类。**只有分类进界面，原文不前进界面。**
     *
     * [NO_PASSWORD] 是 F21 加的第四类，**与前三类的性质不同**：前三类是
     * "**已经连上了、但这次没成**"，可以给"继续加载"；它是"**根本没有身份验证**"，
     * 属于**必须先解决、否则不许继续**的那一类。
     */
    enum class Kind(val heading: String, val hint: String) {
        UNREACHABLE("连不上服务器", HINT_NETWORK),
        AUTH_FAILED("登录没通过", HINT_AUTH),
        UNKNOWN("连接出了点问题", HINT_NETWORK),
        NO_PASSWORD(LaunchGuard.HEADING_NO_PASSWORD, LaunchGuard.HINT_NO_PASSWORD),
    }

    /**
     * 生成一页自洽的错误页。
     *
     * @param kind 用户可读的失败分类
     * @param detailForLog 重试动作的 JS。默认 `location.reload()`；
     *                调用方也可传"回到配对页"之类的动作（见宿主）。
     * @param buttonLabel 按钮文案。**必须与实际动作一致**（F21）：
     *                按钮点了去配置对话框却写成"重试"，是在让用户按一个
     *                与它名字不符的按钮 —— 错误页上的每个字都应当为真的。
     */
    fun html(
        kind: Kind,
        detailForLog: String? = null,
        retryJs: String = DEFAULT_RETRY_JS,
        buttonLabel: String = DEFAULT_BUTTON_LABEL,
    ): String {
        val heading = escapeHtml(kind.heading)
        val hint = escapeHtml(kind.hint)
        // detail 只进注释，且已 redact —— 保留一点点上下文又不泄露。
        val note = detailForLog?.let { "<!-- detail: ${escapeHtml(redact(it))} -->" } ?: ""
        return buildString {
            append("<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">")
            append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
            append("<title>").append(heading).append("</title>")
            append("<style>")
            append("html,body{margin:0;height:100%;background:#0b0b0d;color:#e8e8ea;")
            append("font:16px/1.6 -apple-system,Roboto,'Noto Sans SC',sans-serif;}")
            append(".wrap{max-width:34em;margin:0 auto;padding:24px 20px;}")
            append("h1{font-size:20px;font-weight:600;margin:0 0 12px;}")
            append("p{margin:0 0 12px;color:#b9b9c0;}")
            append("button{width:100%;min-height:48px;height:48px;font-size:16px;font-weight:600;")
            append("border:0;border-radius:10px;background:#3b82f6;color:#fff;}")
            append("button:active{background:#2f6fd0;}")
            append("</style></head><body>")
            append(note)
            append("<div class=\"wrap\">")
            append("<h1>").append(heading).append("</h1>")
            append("<p>").append(hint).append("</p>")
            append("<button type=\"button\" onclick=\"").append(escapeHtml(retryJs)).append("\">")
            append(escapeHtml(buttonLabel)).append("</button>")
            append("</div></body></html>")
        }
    }

    const val DEFAULT_RETRY_JS = "location.reload()"

    /** 按钮默认文案。F21 之前所有错误页的动作都确实是"再试一次"，所以默认值不变。 */
    const val DEFAULT_BUTTON_LABEL = "重试"

    /**
     * 不可达（连接失败 / 超时 / 502）⇒ 友好页。
     *
     * **注意这个签名**：详情原文只用于 redact 后塞进 HTML 注释与日志，
     * 界面上永远只有 [Kind.UNREACHABLE] 的两句话。
     */
    fun unreachableHtml(detailForLog: String? = null): String =
        html(Kind.UNREACHABLE, detailForLog)

    /** 认证失败（401/403）⇒ 友好页。**永不回显口令。** */
    fun authFailedHtml(detailForLog: String? = null): String =
        html(Kind.AUTH_FAILED, detailForLog)
}
