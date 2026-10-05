package com.xiufeigo.opencodeshell.web.host

/**
 * F21 · **空口令 ⇒ 不许进入已鉴权路径**的闸门。**纯逻辑**，无 Android 依赖。
 *
 * ## 这条缺陷的准确定性（F20 挖到、本轮核实）
 *
 * F20 记录的是"`WebHostActivity.kt:634-637` 存在空口令路径"。本轮逐行核实后，
 * 根因比"少了一次判空"更具体，也更危险：
 *
 *  - **启动链本身是有判空的**（`WebHostActivity.startProxyAndLoad`，口令为空就
 *    走 `showSetupDialog` 并 `return`，**不起代理**）。所以"冷启动 + 首次安装未播种"
 *    这条最常见的路**本来就被挡住了**。
 *  - 真正漏的是**配置对话框里的两个出口**：
 *    1. 「先不配置」（634-637）—— 注释自己写着 `setup skipped -> API will 401`，
 *       但它**仍然调了 `launch(defaultBackend)`**，于是代理照起、官方 UI 照加载；
 *    2. 「保存并连接」允许**提交空口令**（`SecretStore.setSecret(..., "")` 传空串
 *       等于**删键**，见 `SecretStore.setSecret:93`），随后 `probeThenLaunch` 若探到
 *       后端 200（**后端自己没设密码时正是如此**）就判 `OK` → `launch`。
 *  - 还有两个间接入口同样落到 `launch`：探测不可达/未知时的"继续看看"（F5 刻意
 *    允许"探不通仍然加载"），以及诊断页重试 `retryFromDiagnosticPage`。
 *
 * ⇒ 四个出口全都汇进**同一个函数** `launch()`。因此**闸门必须下在 `launch()` 里**，
 * 而不是分散到每个调用点：漏一个调用点就是重新打开这个洞。
 *
 * ## 为什么"看起来正常"就是本缺陷的实质
 *
 * 后端**自己没有设密码**时，它对任何能到达该端口的请求都直接放行（`origin/v2`
 * 的默认形态：认证是**可选**的，不配 `OPENCODE_SERVER_PASSWORD` 就不校验）。
 * 此时代理若照常注入空凭据/不注入 `Authorization`，官方 UI 会**完整加载、能点、能用**，
 * **没有任何报错**——用户以为"我已经在用受保护的 opencode 了"，实际上：
 *
 *  - 设备上**任意 app** 都能访问 `127.0.0.1:<proxy port>`（回环不隔离进程）；
 *  - 一旦宿主跑了 `adb reverse tcp:4095 tcp:4095`，**通过 USB 连着的任何程序**
 *    都能访问宿主侧后端。
 *
 * 也就是说：失败形态不是"用不了"，而是**"用得很好，但完全没有认证"**——
 * 这正是派单点名的"不能让看起来正常掩盖没有认证"。所以本闸门的判据是
 * **"有口令才允许起代理"**，而不是"起代理后请求失败再报错"：后者仍然是先放行再失败。
 *
 * ## 不可让步的一条
 *
 * 本闸门**只**管"能不能进入已鉴权路径"，**不**改代理转发逻辑、
 * **不**回显口令、**不**影响口令正确时的正常路径（回归见 §4）。
 */
object LaunchGuard {

    /**
     * 唯一判据：**只有非空口令才允许进入已鉴权路径**。
     *
     * `null` 与 `""` 同等对待 —— `SecretStore.getSecret` 已经用
     * `takeIf { it.isNotEmpty() }` 把空串过滤成 `null`（`SecretStore.kt:85`），
     * 这里再判一次是为了让**直接传字面量的调用方**（测试、未来的代码）也得到同一答案。
     *
     * 刻意**不**做 `isNotBlank()`：口令若含首尾空白，仍是一个真实口令，
     * 宿主会原样 Basic 编码发出去，由后端裁决成败。在这一层替它"修正"反而
     * 会制造"界面上输入了、却因本地裁剪而失效"的新谜团。
     */
    fun mayLaunch(password: String?): Boolean = !password.isNullOrEmpty()

    /**
     * 闸门拒绝时的标题。与 [ConnectErrorPage.Kind] 的文案同源，
     * 避免"原生说一套、网页说另一套"（F5 立下的规矩）。
     */
    const val HEADING_NO_PASSWORD = "还没设置服务器密码"

    /**
     * 拒绝理由的用户可读版。**必须把"没有认证"这件事本身说出来** ——
     * 这是本轮的核心要求：不能让"看起来正常"掩盖"实际上没有认证"。
     * 所以这句话**明确告知"此时后端对任何能连上它的人都是开放的"**。
     *
     * ⚠ 只提用户自己能确认/能改的东西（沿用 [ConnectErrorPage] 规矩 1）：
     * 这里**不写**任何 host:port、不写端口号。
     */
    const val HINT_NO_PASSWORD =
        "没有服务器密码，服务器会对任何能连上它的程序开放，等于没有身份验证。" +
            "请先设置密码再连接。"

    /** 闸门拒绝时给用户的错误页分类。 */
    val KIND: ConnectErrorPage.Kind = ConnectErrorPage.Kind.NO_PASSWORD

    /**
     * 闸门拒绝页：唯一动作是"去设置密码"。
     *
     * 重试动作**不是** `location.reload()`（那只会把这一页再刷一遍），
     * 而是调桥回到配置对话框 —— 否则用户被挡在这里却无处可去，
     * 就成了 F5 修掉的同一个"不可自救"缺陷。
     */
    fun blockedHtml(): String = ConnectErrorPage.html(
        kind = KIND,
        detailForLog = "launch blocked: no password in SecretStore (F21)",
        retryJs = OPEN_SETUP_JS,
        // 按钮的动作是"回配置对话框"，文案就必须说这件事。写成"重试"会让用户
        // 点一个与名字不符的按钮 —— 恰恰在这一页，**每个字都必须是真话**。
        buttonLabel = BUTTON_LABEL,
    )

    /** 被挡页上那个按钮的文案：与它的实际动作一致（F21）。 */
    const val BUTTON_LABEL = "去设置密码"

    /**
     * 错误页上那个按钮的 JS。走 [WebHostBridge.openSetup] 这个**新桥方法**，
     * 而不是 `retry()` —— `retry()` 会回到 `launch()`，在闸门处再次被挡，
     * 变成"点一次刷一次"的死循环。
     */
    const val OPEN_SETUP_JS = "(function(){var b=window.__ocAndroid;" +
        "if(b&&b.openSetup){b.openSetup();}else{location.reload();}})()"
}
