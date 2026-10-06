package com.xiufeigo.opencodeshell.web

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * P9 · **通知权限状态闭环绊线**（源码文本断言）。
 *
 * ## 背景：P8 实测里"授权成功但一条通知都没有"的根因
 *
 * P8 实测确认：POST_NOTIFICATIONS 弹窗出现并被点"允许"、后台 blur 保活成功、
 * 后台日志里有 `round end succeeded` —— 但 `dumpsys notification` 里 0 条本包通知。
 *
 * 卡点收敛到我们自己注入的垫片 `assets/www/oc-host.js`：
 *
 *  1. `ShimNotification.permission` 是**硬编码的静态值** `"default"`，
 *     Android 侧授权之后**没有任何路径**把它更新成 `"granted"`；
 *  2. `requestPermission()` 只在被**调用**时才查宿主真值
 *     （`has === 1` ⇒ 返回 `"granted"`）。
 *
 * 于是：上游闸门若**先读 `Notification.permission` 属性**，
 * 只有读到 `"default"` 才去调 `requestPermission()` —— 就永远卡在 `"default"`。
 * 授权弹窗早就点过了，通知却永远不放行。
 *
 * （P8 探针里的 `oc is not defined` 是探针自身作用域看不到 IIFE 闭包的**观测假象**，
 *   不是垫片运行时错误，不要再沿这条线走。）
 *
 * ## 本文件钉的是"状态闭环"的两半
 *
 *  - 页面侧：`requestPermission()` 查完宿主真值后**同步回写静态属性**；
 *  - 宿主侧：`POST_NOTIFICATIONS` launcher 回调经 `evaluateJavascript` 派发
 *    `oc:notifyPerm` 事件，页面侧监听后刷新该属性。
 *
 * ## 反向验证
 *
 * 备份后 `git show bc87e72:webapp/src/main/assets/www/oc-host.js > ...`（Kotlin 同理），
 * 对应用例必然变红；恢复后全绿。原文见 `research/web/UX13_P9_NOTIFY_PERMISSION_FIX.md`。
 */
class NotificationShimTest {

    private fun src(rel: String): String {
        val relPath = File(rel)
        var dir: File? = File(".").absoluteFile
        repeat(4) {
            val f = File(dir, rel)
            if (f.isFile) return f.readText(Charsets.UTF_8)
            dir = dir?.parentFile
        }
        throw AssertionError("找不到源文件: $rel（从 ${File(".").absolutePath} 向上找了 4 级）")
    }

    private val hostJs: String
        get() = src("src/main/assets/www/oc-host.js")

    private val activitySrc: String
        get() = src("src/main/java/com/xiufeigo/opencodeshell/web/WebHostActivity.kt")

    // ---- 断言①：requestPermission 回写静态 permission ---------------------

    @Test
    fun requestPermissionWritesBackStaticPermission() {
        val src = hostJs
        assertTrue(
            "oc-host.js 的 requestPermission() 必须把宿主真值同步回写进静态属性 —— " +
                "只改 resolve() 的返回值不够：上游闸门若先读 `Notification.permission`，" +
                "硬编码的 \"default\" 会让它永远不放行（P8 实测 0 条通知的根因）",
            src.contains(
                "ShimNotification.permission = has === 1 ? \"granted\" : (has < 0 ? \"denied\" : \"default\");",
            ),
        )
    }

    @Test
    fun writeBackHappensBeforeResolving() {
        val src = hostJs
        val body = src.substringAfter("ShimNotification.requestPermission = function () {")
        val has = body.indexOf("var has = oc.hasNotificationPermission();")
        val writeBack = body.indexOf(
            "ShimNotification.permission = has === 1 ? \"granted\" : (has < 0 ? \"denied\" : \"default\");",
        )
        val resolve = body.indexOf("if (has === 1) return Promise.resolve(\"granted\");")
        assertTrue("未找到 `var has = oc.hasNotificationPermission();`", has >= 0)
        assertTrue("未找到回写赋值语句", writeBack >= 0)
        assertTrue("未找到原有 granted 返回分支", resolve >= 0)
        assertTrue(
            "回写必须发生在查询宿主真值之后、返回 Promise 之前，否则读到的还是旧值",
            has < writeBack && writeBack < resolve,
        )
    }

    // ---- 断言②：oc:notifyPerm 事件闭环（页面侧监听） ----------------------

    @Test
    fun pageSideListensForNotifyPermEvent() {
        val src = hostJs
        assertTrue(
            "oc-host.js 必须在垫片安装处监听宿主推送的 `oc:notifyPerm` 事件 —— " +
                "Android 授权回调无法被页面异步观察到，只能由宿主主动派发",
            src.contains("window.addEventListener(\"oc:notifyPerm\""),
        )
        assertTrue(
            "监听器必须用事件 detail 刷新静态属性",
            src.contains("ShimNotification.permission = detail;"),
        )
        assertTrue(
            "监听器必须校验 detail 取值，避免把非法值写进 permission",
            src.contains("if (detail === \"granted\" || detail === \"denied\") {"),
        )
    }

    @Test
    fun listenerIsInstalledAtTheShimInstallSite() {
        val src = hostJs
        val install = src.indexOf("window.Notification = ShimNotification")
        val listen = src.indexOf("window.addEventListener(\"oc:notifyPerm\"")
        assertTrue("未找到垫片安装点", install >= 0)
        assertTrue("未找到 oc:notifyPerm 监听器", listen >= 0)
        assertTrue(
            "监听器必须与 `window.Notification = ShimNotification` 在同一垫片安装块内",
            listen > install,
        )
        // 垫片安装块在其后遇到 OpenCodeAndroid 兼容入口即结束。
        val compat = src.indexOf("window.OpenCodeAndroid = {", install)
        assertTrue(
            "监听器跑到垫片安装块之外了（应在 OpenCodeAndroid 兼容入口之前）",
            compat >= 0 && listen < compat,
        )
    }

    // ---- 断言②：oc:notifyPerm 事件闭环（宿主侧派发） ----------------------

    @Test
    fun hostDispatchesNotifyPermFromPermissionCallback() {
        val src = activitySrc
        assertTrue(
            "WebHostActivity.kt 的 POST_NOTIFICATIONS launcher 回调必须经 evaluateJs 派发 " +
                "`oc:notifyPerm`，否则页面侧静态 permission 永远收不到宿主真值",
            src.contains("new CustomEvent('oc:notifyPerm'"),
        )
        assertTrue(
            "派发必须落在 evaluateJs(...) 通道里（内部即 evaluateJavascript）",
            src.contains(
                "\"window.dispatchEvent(new CustomEvent('oc:notifyPerm',\" +",
            ),
        )
        assertTrue(
            "granted/denied 必须由回调的 granted 参数决定，不能写死",
            src.contains("{detail:'") &&
                src.contains("if (granted) \"granted\" else \"denied\""),
        )
    }

    @Test
    fun hostDispatchLivesInTheNotificationPermissionCallback() {
        val src = activitySrc
        val launcher = src.indexOf("ActivityResultContracts.RequestPermission(),")
        val dispatch = src.indexOf("new CustomEvent('oc:notifyPerm'")
        assertTrue("未找到 POST_NOTIFICATIONS launcher 注册", launcher >= 0)
        assertTrue("未找到 oc:notifyPerm 派发", dispatch >= 0)
        assertTrue(
            "派发必须写在 POST_NOTIFICATIONS launcher 回调体内",
            dispatch > launcher,
        )
    }

    // ---- 防回归：原始缺陷形态不得复活 -------------------------------------

    @Test
    fun permissionIsNotOnlyAHardcodedDefault() {
        val src = hostJs
        assertTrue(
            "静态初值声明本身应保留（尚未授权时只能是 \"default\"）",
            src.contains("ShimNotification.permission = \"default\";"),
        )
        val staticAssignments = Regex("ShimNotification\\.permission\\s*=")
            .findAll(src)
            .count()
        assertTrue(
            "ShimNotification.permission 至少要有 3 处赋值（初值 / requestPermission 回写 / " +
                "事件监听刷新），实际只有 $staticAssignments 处 —— 状态闭环断了",
            staticAssignments >= 3,
        )
    }

    // ---- P10 · 后台"未聚焦"页面状态 shim ---------------------------------

    @Test
    fun hostJsDefinesBackgroundStateShim() {
        val src = hostJs
        assertTrue(
            "oc-host.js 必须暴露 window.__ocSetBackground —— 宿主靠它切后台/回前台读数",
            src.contains("window.__ocSetBackground = function (v)"),
        )
        assertTrue(
            "shim 必须用 Object.defineProperty 覆盖 document.visibilityState 的**读数**" +
                "（这是上游闸门 `document.visibilityState === 'visible'` 唯一认的东西）",
            src.contains("Object.defineProperty(document, \"visibilityState\""),
        )
        assertTrue(
            "getter 必须按后台标志返回 hidden / visible 两个值",
            src.contains("ocBackgrounded ? \"hidden\" : \"visible\""),
        )
        assertTrue(
            "document.hasFocus 必须被覆盖为 !ocBackgrounded（闸门的第二个条件）",
            src.contains("document.hasFocus = function ()"),
        )
        assertTrue(
            "ocBackgrounded 变量必须存在（shim 状态源）",
            src.contains("var ocBackgrounded = false;"),
        )
    }

    @Test
    fun shimMustNotDispatchVisibilityOrFocusEvents() {
        val src = hostJs
        // 🔴 核心防回归：shim **只覆盖属性/方法，不得派发事件**。
        // 派 visibilitychange 会让上游 pageLifecycle 走 resync → abort，
        // 把保活路径下的 SSE 掐死，"终态帧必丢"的 P6 死锁原样复活。
        assertTrue(
            "oc-host.js 绝不允许出现 `dispatchEvent(new Event(\"visibilitychange\")` —— " +
                "会触发上游 resync→abort，掐死保活 SSE（P7 已证）。实际内容含该串",
            !src.contains("dispatchEvent(new Event(\"visibilitychange\")"),
        )
        assertTrue(
            "oc-host.js 绝不允许出现单引号形态的 visibilitychange 派发",
            !src.contains("dispatchEvent(new Event('visibilitychange')"),
        )
        assertTrue(
            "shim 附近不得派发 focus/blur —— 事件面由 WebHostActivity 的 " +
                "dispatchPageLifecycleEvent 独占，页面侧再派一次就是重复且会动 SSE 状态机",
            !Regex("dispatchEvent\\(\\s*new (Event|CustomEvent)\\(\\s*['\\\"](focus|blur)['\\\"]")
                .containsMatchIn(src),
        )
        // 反向：确认这条约束是**有意为之的注释**，不是漏写。
        assertTrue(
            "shim 处应留有\"只覆盖属性/方法、不派发事件\"的注释，把 P7 约束钉在源码里",
            src.contains("绝不派发 visibilitychange"),
        )
    }

    @Test
    fun hostTogglesBackgroundStateInPauseAndResume() {
        val src = activitySrc
        assertTrue(
            "WebHostActivity.kt 必须在 onPause 里把页面置为后台：`__ocSetBackground(true)`",
            src.contains("__ocSetBackground(true)"),
        )
        assertTrue(
            "WebHostActivity.kt 必须在 onResume 里把页面置为前台：`__ocSetBackground(false)`",
            src.contains("__ocSetBackground(false)"),
        )
        val pauseAt = src.indexOf("override fun onPause()")
        val resumeAt = src.indexOf("override fun onResume()")
        val bgTrue = src.indexOf("__ocSetBackground(true)")
        val bgFalse = src.indexOf("__ocSetBackground(false)")
        assertTrue("未找到 onPause", pauseAt >= 0)
        assertTrue("未找到 onResume", resumeAt >= 0)
        assertTrue(
            "`__ocSetBackground(true)` 必须写在 onPause 里（切后台 ⇒ hidden/未聚焦）",
            bgTrue > pauseAt && bgTrue < resumeAt,
        )
        assertTrue(
            "`__ocSetBackground(false)` 必须写在 onResume 里（回前台 ⇒ visible/聚焦）",
            bgFalse > resumeAt,
        )
        // 两处都必须走 evaluateJs 通道：它内部是 evaluateJavascript + isInitialized 判空 + runCatching。
        val bgTrueCall = "evaluateJs(\"window.__ocSetBackground && window.__ocSetBackground(true)\")"
        val bgFalseCall = "evaluateJs(\"window.__ocSetBackground && window.__ocSetBackground(false)\")"
        assertTrue(
            "onPause 必须整句经 evaluateJs(...) 派发（内部 = evaluateJavascript + 判空 + runCatching），" +
                "不能裸调 webView.evaluateJavascript",
            src.contains(bgTrueCall),
        )
        assertTrue(
            "onResume 必须整句经 evaluateJs(...) 派发（内部 = evaluateJavascript + 判空 + runCatching），" +
                "不能裸调 webView.evaluateJavascript",
            src.contains(bgFalseCall),
        )
        // 两类调用各只应出现一次：多处派发 = 未来有人复制粘贴出新入口，语义会失控。
        // （只数**完整调用语句**，注释里提到这个名字不算。）
        assertTrue(
            "`evaluateJs(\"...__ocSetBackground(true)\")` 的调用点必须恰好 1 处",
            Regex(Regex.escape(bgTrueCall)).findAll(src).count() == 1,
        )
        assertTrue(
            "`evaluateJs(\"...__ocSetBackground(false)\")` 的调用点必须恰好 1 处",
            Regex(Regex.escape(bgFalseCall)).findAll(src).count() == 1,
        )
    }

    // ---- P11 · 页面重载后按宿主真值重推权限 ---------------------------------

    /**
     * P10 §4.5 遗留：点通知把 Activity 拉起 ⇒ **页面重载** ⇒ `oc-host.js` 垫片重装，
     * 静态 `Notification.permission` 回落 `"default"`。而上游闸门是**先读属性**、
     * 读到 `!== "granted"` 就直接 `return`（闸门自身**不会**去调 `requestPermission`，
     * 那个 await 分支只在"曾经申请过"的内部状态下走）⇒ 重载后通知静默失效，
     * 直到下一次宿主主动推送。
     *
     * 修法：宿主在**每次页面加载完成**时按当前真值重推一次 `oc:notifyPerm`
     * （复用 P9 的事件通道，页面侧监听器一行未改）。
     */
    @Test
    fun hostRepushesNotifyPermOnEveryPageLoad() {
        val src = activitySrc

        // (1) 重推函数存在，且走 evaluateJs 通道派发同一个事件。
        assertTrue(
            "WebHostActivity.kt 必须提供按宿主真值重推权限的入口 `pushNotificationPermission()`",
            src.contains("private fun pushNotificationPermission()"),
        )
        val pushAt = src.indexOf("private fun pushNotificationPermission()")
        assertTrue("未找到重推函数定义", pushAt >= 0)
        val pushBody = src.substring(pushAt, src.indexOf("override fun onCreate", pushAt))
        assertTrue(
            "重推必须复用 P9 的 `oc:notifyPerm` 事件名（页面侧监听器已按此名注册）",
            pushBody.contains("new CustomEvent('oc:notifyPerm'"),
        )
        assertTrue(
            "重推必须经 evaluateJs(...) 通道派发（内部 = evaluateJavascript + 判空 + runCatching）",
            pushBody.contains("evaluateJs("),
        )

        // (2) 真值必须来自宿主 compat checker，不能写死字符串。
        val stateAt = src.indexOf("private fun notificationPermissionState()")
        assertTrue("未找到权限真值读取函数", stateAt >= 0)
        val stateBody = src.substring(stateAt, src.indexOf("private fun pushNotificationPermission", stateAt))
        assertTrue(
            "权限真值必须读宿主 compat checker（ContextCompat.checkSelfPermission）",
            stateBody.contains("ContextCompat.checkSelfPermission("),
        )
        assertTrue(
            "权限真值必须查 POST_NOTIFICATIONS",
            stateBody.contains("Manifest.permission.POST_NOTIFICATIONS"),
        )
        assertTrue(
            "必须保留 pre-33 分支（API 33 之前没有运行时通知权限，视为 granted）",
            stateBody.contains("Build.VERSION_CODES.TIRAMISU"),
        )

        // (3) 🔴 核心：调用点必须落在页面就绪路径（onPageFinished）里。
        val pageReadyAt = src.indexOf("override fun onPageFinished(view: WebView, url: String)")
        assertTrue("未找到 onPageFinished（页面就绪回调）", pageReadyAt >= 0)
        val pageReadyBody = src.substring(pageReadyAt, src.indexOf("private fun contextCompatDark", pageReadyAt))
        assertTrue(
            "WebHostActivity.kt 必须在 onPageFinished（页面加载完成）里调用重推 —— " +
                "P10 实测：重载后静态 permission 回落 \"default\"，闸门读到非 granted 直接 return，" +
                "不重推则通知静默失效",
            pageReadyBody.contains("pushNotificationPermission()"),
        )

        // (4) 只应有这一个调用点：多处派发 = 语义失控的入口。
        val callSites = Regex("""(?<!fun )pushNotificationPermission\(\)""").findAll(src).count()
        assertTrue(
            "`pushNotificationPermission()` 的调用点必须恰好 1 处（页面就绪路径），实际 $callSites 处",
            callSites == 1,
        )
    }
}