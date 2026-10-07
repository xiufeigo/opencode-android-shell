package com.xiufeigo.opencodeshell.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 幻影服务器条目（页面 origin `127.0.0.1:4173`）绊线。
 *
 * ## 这一层为什么存在
 *
 * 上游把页面 origin 当"内建服务器"（`currentServerUrl = location.origin`），它不在注册表
 * `list` 里，因此上游 Remove 永远删不掉；而 Home 的服务器列表与**当前上下文**都读 store 的
 * `visible = list.filter(s => !hidden[Key(s)])`，origin 恒在 `visible[0]` ⇒ Home 的当前
 * 服务器恒为 4173 ⇒ 而 4173 的 API 前缀（`/api/`）由 `LocalUiOriginServer` 一律 404 ⇒ 事件流
 * 404@1Hz ⇒ Home 永久 Loading。
 *
 * ## 修法（2026-10-07 改判，取代 v1–v4 的纯 DOM 隐藏）
 *
 * 写**上游自己的机制**：`registry.hidden[origin] = true`。它让 origin 从所有读 `visible`
 * 的表面消失，**并让 Home 的当前服务器落到用户自己的服务器上**。纯 DOM 隐藏只遮界面、
 * 遮不住上下文（UX17-P6 设备复验：设置里藏了、页头/搜索框仍写着 4173、Home 仍 Loading），
 * 产品负责人 2026-10-07 报的就是这个半隐藏形态。
 *
 * ## 本类钉住的不变量
 *
 * A. 注册表机制（主）：闸门/幂等/只动 `hidden`/origin 动态/reload 有界。
 * B. DOM 兜底（辅）：只对上游没消费 `hidden` 的表面补齐，**闸门读同一份 `hidden`**
 *    （单一事实来源），且仍然只隐藏、不删节点、不碰 Home 切换器。
 */
class PhantomServerHideTest {

    private fun read(relative: String): String {
        val f = File(relative)
        assertTrue("找不到 $relative（单测工作目录应为 webapp/ 模块目录）", f.isFile)
        return f.readText()
    }

    private val js: String by lazy { read("src/main/assets/www/oc-host.js") }

    /** DOM 兜底层的常量段（PHANTOM_ATTR / STYLE_ID / THROTTLE_MS / ROW_SEL）。 */
    private val domConstants: String by lazy {
        val start = js.indexOf("var PHANTOM_ATTR")
        val end = js.indexOf("var PHANTOM_REGISTRY_KEY")
        assertTrue("oc-host.js 里找不到 DOM 常量段（PHANTOM_ATTR…）", start >= 0 && end > start)
        js.substring(start, end)
    }

    /** 注册表机制那一段（常量 + 读写函数）。 */
    private val registry: String by lazy {
        val start = js.indexOf("var PHANTOM_REGISTRY_KEY")
        val end = js.indexOf("function phantomOriginToken")
        assertTrue("oc-host.js 里找不到注册表机制段（PHANTOM_REGISTRY_KEY…）", start >= 0)
        assertTrue("oc-host.js 里找不到段边界 function phantomOriginToken", end > start)
        js.substring(start, end)
    }

    /** DOM 兜底 + 启动 + 文件末尾的 boot/观察器调用。 */
    private val dom: String by lazy {
        val start = js.indexOf("function phantomOriginToken")
        assertTrue("oc-host.js 里找不到 DOM 兜底段", start >= 0)
        js.substring(start)
    }

    /** DOM 相关断言的合集（常量段 + 实现段）。 */
    private val domAll: String by lazy { domConstants + "\n" + dom }

    /** 去掉 `//` 行注释后的代码文本（负向断言只盯可执行代码）。 */
    private fun code(text: String): String =
        text.lineSequence().map { it.substringBefore("//") }.joinToString("\n")

    private val registryCode: String by lazy { code(registry) }
    private val domCode: String by lazy { code(dom) }

    // ================= A. 注册表机制（主） =================

    @Test
    fun syncOriginHiddenRefusesToTouchAnythingBeforeAUserServerExists() {
        assertTrue(
            "必须有 syncOriginHidden() 这个唯一写入口",
            registry.contains("function syncOriginHidden"),
        )
        // 键不存在（首启：用户还没加过任何服务器）⇒ 一个字节都不写。
        // 断言"闸门早于任何写入"：`if (!reg) return false;` 必须出现在**写回调用**之前
        // （注意找的是调用点 `return phantomWriteRegistry(reg);`，不是函数定义）。
        val gate = registryCode.indexOf("if (!reg) return false;")
        val write = registryCode.indexOf("return phantomWriteRegistry(reg);")
        assertTrue("syncOriginHidden 必须有 `if (!reg) return false;` 的首启闸门", gate >= 0)
        assertTrue("首启闸门必须早于写回调用（否则首启就会被我们写脏注册表）", write > gate)
    }

    @Test
    fun hidesOriginExactlyWhenTheUserHasTheirOwnServer() {
        assertTrue(
            "语义判据必须是「list 里存在 url ≠ origin 的条目」（用户自己的服务器）",
            registryCode.contains("phantomUserServers(reg).length > 0"),
        )
        assertTrue(
            "命中语义时必须写 hidden[origin] = true（上游 visible 才会过滤掉它）",
            registryCode.contains("hidden[origin] = true"),
        )
        assertTrue(
            "必须整表写回注册表（读-改-写）",
            registryCode.contains("JSON.stringify(reg)"),
        )
    }

    @Test
    fun isIdempotentAndNeverWritesWhenAlreadyAligned() {
        assertTrue(
            "必须先用 `want === has` 判定再决定写不写 —— 已对齐时不写、也就不会触发 reload",
            registryCode.contains("if (want === has) return false;"),
        )
        assertTrue(
            "hidden 容器缺失时必须**补建**而不是放弃（首启 Add 后上游写出的 hidden 是 {}）",
            registryCode.contains("hidden = {};"),
        )
    }

    @Test
    fun unhidesOriginWhenTheUserRemovesEveryServer() {
        // 用户把自己的服务器全删了 ⇒ 撤掉 hidden[origin]，placeholder 回来，
        // 否则界面会变成"一个服务器都没有"的死状态。
        assertTrue(
            "非命中语义时必须 `delete hidden[origin]`（placeholder 语义：删光了就还回来）",
            registryCode.contains("delete hidden[origin]"),
        )
    }

    @Test
    fun neverTouchesTheServerListNorDeletesEntries() {
        // 红线：只动 hidden 容器。list / 条目一律不改 —— 上游的焦点、排序、project
        // 归属都挂在 list 上，动它就等于改上游数据模型。
        assertFalse(
            "注册表例程不得改写 reg.list（只允许读）",
            registryCode.contains("reg.list =") || registryCode.contains("reg.list.push"),
        )
        assertFalse(
            "注册表例程不得删除条目（splice / pop / shift / delete list[i]）",
            registryCode.contains(".splice(") || registryCode.contains(".pop()") ||
                registryCode.contains(".shift()"),
        )
        assertFalse(
            "注册表例程不得删键（removeItem 会把别人的服务器一起清掉）",
            registryCode.contains("removeItem"),
        )
    }

    @Test
    fun originIsReadDynamicallyAndNeverHardcoded() {
        assertTrue(
            "origin 必须动态取自 window.location.origin",
            registryCode.contains("window.location.origin"),
        )
        assertFalse(
            "注册表例程（可执行代码）不得硬编码 127.0.0.1:4173 —— 端口一变就全线失效",
            registryCode.contains("127.0.0.1:4173"),
        )
    }

    @Test
    fun bootAppliesBeforeTheAppHydratesAndWatchesLaterWrites() {
        // boot 路径：本文件由 addDocumentStartJavaScript 注入 ⇒ 顶层直调即"早于 hydration"，
        // 当次启动生效、无需 reload（用户手机上"已经有服务器"的状态装上即好）。
        val boot = dom.indexOf("syncOriginHidden();")
        assertTrue("文件末尾必须有 boot 直调 syncOriginHidden();", boot >= 0)
        // 运行期路径：应用自己写注册表（Add/Edit/Remove）时要补写。
        val watch = dom.indexOf("watchRegistryWrites();")
        assertTrue("文件末尾必须装 watchRegistryWrites()（运行期 Add/Remove 路径）", watch > boot)
        assertTrue(
            "观察器必须挂在 Storage.prototype.setItem 上（应用写注册表就走这条路）",
            registryCode.contains("window.Storage && window.Storage.prototype"),
        )
        assertTrue(
            "观察器必须只认注册表这一个键（其它 localStorage 写入一律不管）",
            registryCode.contains("if (key === PHANTOM_REGISTRY_KEY && !phantomWritingRegistry)"),
        )
        assertTrue(
            "必须防递归：我们自己的写回不能再触发观察器",
            registryCode.contains("phantomWritingRegistry = true"),
        )
    }

    @Test
    fun reloadIsBoundedAndOnlyHappensAfterTheAppMounted() {
        // 应用内存里的 hidden 只有重新 hydration 才会跟上 ⇒ 运行期改状态要 reload 一次。
        // 但必须防两类事故：启动期空转 reload、以及死循环 reload。
        assertTrue("必须有 reload 防环常量", registryCode.contains("PHANTOM_RELOAD_MAX"))
        assertTrue(
            "防环计数必须落在 sessionStorage（跨 reload 存活）",
            registryCode.contains("window.sessionStorage"),
        )
        assertTrue(
            "只有应用挂载完成后才允许 reload（启动期由 boot 路径处理）",
            registryCode.contains("document.querySelector(\"[data-opencode-mounted]\")"),
        )
        assertTrue(
            "reload 必须延迟到当前写事务之后（同步 reload 会打断应用自己的写入）",
            registryCode.contains("window.setTimeout(") && registryCode.contains("window.location.reload()"),
        )
        assertTrue(
            "一次只排一次 reload（phantomReloadPending 守卫）",
            registryCode.contains("phantomReloadPending"),
        )
    }

    // ================= B. DOM 兜底（辅） =================

    @Test
    fun domFallbackIsGatedByTheRegistryHiddenMap() {
        assertTrue(
            "DOM 兜底层必须存在（上游没消费 hidden 的表面由它补齐）",
            dom.contains("function hidePhantomRows"),
        )
        assertTrue(
            "DOM 闸门必须读注册表的 hidden[origin] —— 单一事实来源",
            domCode.contains("if (!isOriginHiddenInRegistry()) return 0;"),
        )
        assertTrue(
            "必须提供 isOriginHiddenInRegistry()（直接读 hidden[origin]）",
            registry.contains("function isOriginHiddenInRegistry"),
        )
        // 🔴 反向：旧的"注册表键存在性"闸门必须退役。
        //    它把"键在但 list 为空"（用户删光了自己的服务器）也判成隐藏 ⇒ 界面无服务器可用。
        assertFalse(
            "不得再用 hasUserServerRegistry()（键存在性闸门：删光服务器时会隐藏出死界面）",
            code(js).contains("hasUserServerRegistry"),
        )
    }

    @Test
    fun domFallbackHidesWithoutDeletingNodesAndProtectsRoots() {
        assertTrue("必须用 data-oc-phantom 标记", domAll.contains("data-oc-phantom"))
        assertTrue("必须靠 CSS 隐藏（display:none!important）", domAll.contains("display:none!important"))
        assertTrue(
            "必须声明受控的行容器选择器",
            domConstants.contains("PHANTOM_ROW_SEL"),
        )
        assertTrue("必须显式跳过 <html>/<body>", dom.contains("el === document.documentElement") && dom.contains("el === document.body"))
        assertTrue("必须跳过 <head>", dom.contains("el === document.head"))
        assertTrue("上爬必须有文本量上界", dom.contains("candMax"))
        assertTrue("必须排除 input/textarea（保护 Add server 对话框）", dom.contains("el.querySelector(\"input,textarea\")"))
        assertTrue(
            "候选取元素必须用 XPath 直接文本匹配（全页 textContent 扫描是掉帧源）",
            dom.contains("document.evaluate"),
        )
        assertTrue(
            "必须节流（PHANTOM_THROTTLE_MS + MutationObserver）",
            domConstants.contains("PHANTOM_THROTTLE_MS") && dom.contains("new MutationObserver"),
        )
        assertFalse(
            "DOM 层不得删除节点（只允许隐藏）",
            code(dom).contains("removeChild") || code(dom).contains(".remove()"),
        )
    }

    @Test
    fun domFallbackNeverHidesTheHomeServerSwitcher() {
        // Home 切换器文本 = 当前服务器名。首加服务器后当前仍是 origin 时藏了它 = 无法切走（自锁）。
        assertTrue(
            "DOM 层必须排除 [data-component=home-projects-dropdown]",
            domCode.contains("row.closest('[data-component=\"home-projects-dropdown\"]')"),
        )
    }

    @Test
    fun deadPointerDefaultServerUrlIsNeverWritten() {
        // UX18-P1 设备实测：`opencode.settings.dat:defaultServerUrl` 是**只写不读**的死指针
        // （列表/切换器/事件流三方都不消费它）。写它只会制造"设了默认却没生效"的假象。
        assertFalse(
            "注入层不得再写 defaultServerUrl（死指针，写了也没用）",
            code(js).contains("defaultServerUrl"),
        )
    }
}
