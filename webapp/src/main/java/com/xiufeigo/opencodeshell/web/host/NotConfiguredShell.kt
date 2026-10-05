package com.xiufeigo.opencodeshell.web.host

/**
 * UX1 任务2 · **"未配置服务器"这一态的应用外壳**。**纯逻辑**，无 Android 依赖，
 * 故可被 `:webapp:testDebugUnitTest` 直接覆盖。
 *
 * ## 产品负责人要什么（逐字）
 *
 * > 「开场的时候就直接把那个界面去了，直接进 app，然后服务器没设置的话，
 * > 就所有功能不可用就行，就显示灰色那样子」
 *
 * 即：**首次启动不再弹对话框**，直接进 app；未配置时功能呈**灰/不可用**。
 *
 * ## 本类为什么不是"把 F21 的被挡页换个皮"
 *
 * F21 的被挡页（[LaunchGuard.blockedHtml]）是**一屏报错**——它回答"为什么不能用"。
 * 产品要的是**一屏应用**：进去之后看到的是应用本身，只是每个功能都灰着。
 * 两者形态不同，判据也不同：
 *
 *  - 被挡页：用户的目标是"知道出了什么事"，动作是"去设置"；
 *  - 本页：用户的目标是"看清这个 app 长什么样、现在为什么不能动"，动作**仍然**是"去设置"。
 *
 * 所以本页**不是** F21 闸门的替代品，而是它的**前置形态**：
 * 未配置时**根本走不到** `launch()`，代理压根不起（见 [WebHostActivity.startProxyAndLoad]）。
 *
 * ## 三条不可让步的规矩（沿用 [ConnectErrorPage]）
 *
 *  1. **不误导**——灰掉的项**带 `aria-disabled` / `disabled`**，
 *     且页面上明写"还没连接服务器"；绝不让它"看起来能用"。
 *  2. **不泄露内部信息**——全页**不出现**任何 `scheme://host:port`、端口号、口令。
 *  3. **不破坏 F21 的安全性质**——本页是**静态 HTML**，不发起任何后端请求；
 *     代理依然由 [LaunchGuard] 挡在 `launch()` 里。见 [PROXY_STARTS_HERE] 那条测试。
 *
 * ## 为什么不"加载官方 UI 再把它变灰"
 *
 * 官方 UI 是**从代理 origin 加载的**，而"起代理"正是 F21 闸门禁止的动作。
 * 要让官方 UI 出现在未配置态，就得**先起一个无认证的代理**——那恰好是 F21 修掉的那个洞。
 * ⇒ 未配置态**只能**是自带的静态外壳，官方 UI 一行都不加载。
 */
object NotConfiguredShell {

    /** 触摸靶硬要求 48dp，与 [ConnectErrorPage.MIN_TOUCH_TARGET_CSS_PX] 同源。 */
    const val MIN_TOUCH_TARGET_CSS_PX = 48

    /**
     * 页面上唯一**可用**的控件文案。刻意与 [LaunchGuard.BUTTON_LABEL]（"去设置密码"）
     * 区分开：这一页灰掉的是**所有功能**，不只是鉴权，所以入口是"设置服务器"而不是"设置密码"。
     */
    const val SETUP_BUTTON_LABEL = "去设置服务器"

    /** 灰掉区顶部的一句话。必须说清"为什么灰"，否则灰态本身就成了谜。 */
    const val HEADING = "还没连接服务器"

    /**
     * 灰掉区的说明。**只说用户自己能确认/能改的东西**（沿用 [ConnectErrorPage] 规矩 1）：
     * 不写 host、不写端口、不写口令。
     */
    const val HINT =
        "服务器还没设置，现在所有功能都用不了。" +
            "请先设置服务器地址和密码，再回来使用。"

    /**
     * 「去设置服务器」的落点：**宿主内部私有 scheme** `ocshell://setup`。
     *
     * ## 为什么不用 JS 桥（设备实测抓出来的真缺陷）
     *
     * 本页是 `loadDataWithBaseURL` 挂上去的，而
     * `addDocumentStartJavaScript` 注入的 `window.__ocAndroid` **对这类文档不生效**。
     * 实测点按钮 ⇒ 桥分支落空、退化成 `location.reload()` ⇒ 整页变成 WebView 自己的
     * `net::ERR_NAME_NOT_RESOLVED` 错误页（`research/web/ux1/shots/ux1_setup_dialog.png`）。
     * ⇒ 必须走**导航**：私有 scheme 一定会被
     * `HostWebViewClient.shouldOverrideUrlLoading` 拦下，不依赖任何注入是否成功。
     */
    const val SETUP_HREF = "ocshell://setup"

    /**
     * [SETUP_HREF] 的 JS 回退：桥若**恰好**可用就走桥（不刷新页面）。
     *
     * 保留它只是为了在"桥可用"的场景下少一次导航；桥不可用时不会重载页面，
     * 而是交回给 <a> 的默认行为去解析 `ocshell://setup`。
     */
    const val OPEN_SETUP_JS = "(function(){var b=window.__ocAndroid;" +
        "if(b&&b.openSetup){b.openSetup();}else{return false;}}())"

    /**
     * 灰掉的入口清单。
     *
     * 每一项都带 [DISABLED_ATTR]：灰不是"变淡一点"，而是**真的不可点**——
     * 点击处理器直接 `return`，不派发任何动作。派单要求"不能看起来能用"，
     * 只改颜色而仍能点，恰恰就是"看起来能用"。
     */
    val GRAYED_ENTRIES: List<GrayedEntry> = listOf(
        GrayedEntry("新建会话", "new session"),
        GrayedEntry("切换终端", "terminal"),
        GrayedEntry("切换审查", "review"),
        GrayedEntry("项目", "projects"),
        GrayedEntry("历史会话", "sessions"),
    )

    /**
     * 一个灰掉的入口。
     *
     * @param label 界面上显示的中文名。
     * @param id    稳定的英文 id，供单测断言、供将来接真命令时**保持同一个键**。
     */
    data class GrayedEntry(val label: String, val id: String)

    /** 灰态项统一带的属性：语义上就是 disabled，而不只是样式变淡。 */
    const val DISABLED_ATTR = "aria-disabled=\"true\""

    /**
     * 自带外壳的完整 HTML。
     *
     * 结构刻意模仿官方 UI 的骨架（标题栏 + 一列入口），让用户第一次进来时
     * 认得出"这是那个 app"，而不是以为自己打开失败进了某个错误页。
     */
    fun html(): String = buildString {
        append("<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">")
        append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1,maximum-scale=1\">")
        append("<title>opencode</title><style>")
        append(":root{color-scheme:dark}")
        append("*{box-sizing:border-box}")
        append("html,body{margin:0;height:100%;background:#080808;color:#e8e8ea;")
        append("font:15px/1.5 -apple-system,Roboto,'Noto Sans CJK SC',sans-serif}")
        append(".shell{max-width:640px;margin:0 auto;padding:20px 16px 32px}")
        append("h1{font-size:19px;font-weight:600;margin:8px 0 4px}")
        append(".hint{color:#9a9aa4;font-size:13px;margin:0 0 20px}")
        append("ul{list-style:none;margin:0 0 24px;padding:0;border-top:1px solid #26262c}")
        append("li{border-bottom:1px solid #26262c}")
        append(".entry{display:flex;align-items:center;gap:10px;min-height:")
        append(MIN_TOUCH_TARGET_CSS_PX)
        append("px;padding:0 4px;color:#5a5a63;font-size:14px;cursor:not-allowed;")
        append("-webkit-tap-highlight-color:transparent}")
        append(".dot{width:6px;height:6px;border-radius:50%;background:#3a3a42;flex:0 0 auto}")
        append(".tag{margin-left:auto;font-size:11px;color:#4a4a52;border:1px solid #2a2a31;")
        append("border-radius:4px;padding:1px 6px}")
        append("a{width:100%;min-height:").append(MIN_TOUCH_TARGET_CSS_PX)
        append("px;border:1px solid #3d7df2;background:#3d7df2;color:#fff;font-size:15px;")
        append("font-weight:600;border-radius:8px;text-align:center;text-decoration:none;")
        append("display:flex;align-items:center;justify-content:center}")
        append("a:active{background:#2f6ad8}")
        append("</style></head><body><div class=\"shell\">")
        append("<h1>").append(HEADING).append("</h1>")
        append("<p class=\"hint\">").append(HINT).append("</p>")
        append("<ul>")
        GRAYED_ENTRIES.forEach { e ->
            append("<li><div class=\"entry\" ").append(DISABLED_ATTR)
            append(" data-entry=\"").append(e.id).append("\">")
            append("<span class=\"dot\"></span><span>").append(e.label).append("</span>")
            append("<span class=\"tag\">不可用</span></div></li>")
        }
        append("</ul>")
        // 按钮是**真的 <a href="ocshell://setup">**：走导航、被 shouldOverrideUrlLoading 拦。
        // onclick 里桥可用就走桥（避免一次导航），否则返回 false 交回 <a> 默认行为。
        append("<a href=\"").append(SETUP_HREF).append("\" onclick=\"")
        append(OPEN_SETUP_JS).append("\">")
        append(SETUP_BUTTON_LABEL).append("</a>")
        append("</div><script>")
        // 灰项**真的不可点**：拦住点击、不派发任何动作。
        append("document.querySelectorAll('.entry').forEach(function(el){")
        append("el.addEventListener('click',function(e){e.preventDefault();e.stopPropagation();});});")
        append("</script></body></html>")
    }

    /**
     * 供 logcat / 报告对齐用的稳定标记串。
     * 判定"未配置态确实加载了、且没起代理"时，靠它与 `proxyListening` 的**同时缺席**。
     */
    const val MARKER = "UX1 not-configured shell"
}
