package com.xiufeigo.opencodeshell.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * UX14-P6 · **幻影服务器条目清除**（宿主注入层 DOM 级隐藏）绊线。
 *
 * ## 为什么需要宿主注入层
 *
 * P4/P5 已把机制钉死，这里直接采信：
 *
 * - 服务器注册表 = `localStorage['opencode.global.dat:server']`，
 *   **首次 Add 成功才惰性创建**；结构 `{"list":[…],"hidden":{},…}`。
 * - 页面 origin 那条（本机静态来源 4173）是**上游 builtin**：它来自
 *   `location.origin`（`currentServerUrl`），**不在 `list` 里**。
 * - 上游 `Remove` 的渲染条件只是"该条目在 `list` 里"
 *   ⇒ builtin 永远 `canRemove` 不成立；`hidden` 容器虽存在，但上游
 *   **没有任何写入入口** ⇒ 也没有 hide 出口。
 * - ⇒ **上游侧永远删不掉这条。** 产品负责人 2026-10-06 拍板："这条不应该存在"。
 *
 * 修法只能是宿主注入层（`oc-host.js`）做 DOM 级隐藏。
 *
 * ## 本类钉住的不变量
 *
 * 1. 例程存在，且目标是**动态** `location.origin`（不许硬编码端口字面量）。
 * 2. 用 `data-oc-phantom` 标记 + CSS 隐藏，**不删除节点**。
 * 3. 有 `MutationObserver` 节流（跟住 SPA 重渲染）。
 * 4. 排除 `input`/`textarea`（不碰 Add server 对话框的输入）。
 * 5. UX15-P1：候选取元素走 **XPath 直接文本匹配**，不做全页 `textContent` 扫描。
 *
 * ## 反向验证
 *
 * 外科撤除例程 ⇒ 本类 5 条真红；恢复 ⇒ 全绿。
 */
class PhantomServerHideTest {

    private fun read(relative: String): String {
        val f = File(relative)
        assertTrue("找不到 $relative（单测工作目录应为 webapp/ 模块目录）", f.isFile)
        return f.readText()
    }

    private val js: String by lazy { read("src/main/assets/www/oc-host.js") }

    /** 只取例程自身的那一段，避免扫到文件里与本轮无关的既有代码。 */
    private val routine: String by lazy {
        val marker = "UX14-P6"
        val idx = js.indexOf(marker)
        assertTrue("oc-host.js 里找不到 UX14-P6 例程段（幻影服务器行隐藏）", idx >= 0)
        js.substring(idx)
    }

    /** 注释之外的代码文本（用于"不许出现删除节点调用"的检查）。 */
    private val routineCode: String by lazy {
        routine.lineSequence()
            .map { it.substringBefore("//") }
            .filter { it.contains("remove", ignoreCase = true) }
            .joinToString("\n")
    }

    /**
     * 去掉 `//` 行注释后的整段例程代码。
     *
     * 只用于**负向**断言：源码注释里允许保留"原实现是 `querySelectorAll("*")` 全页扫描"
     * 这类动机说明，负向断言必须只盯可执行代码，否则会被自己的说明文字误伤。
     * （正向断言仍走原始文本，因为 XPath 表达式本身就含 `//` 前缀。）
     */
    private val routineNoComments: String by lazy {
        routine.lineSequence()
            .map { it.substringBefore("//") }
            .joinToString("\n")
    }

    // ---- 1) 例程存在，目标动态取自 location.origin --------------------------

    @Test
    fun routineExistsAndTargetsDynamicLocationOrigin() {
        assertTrue(
            "必须存在幻影行隐藏例程的入口函数 startPhantomServerHide",
            routine.contains("function startPhantomServerHide"),
        )
        assertTrue(
            "目标标识必须动态取自 location.origin",
            routine.contains("window.location.origin"),
        )
        // 🔴 反向：不得把本机静态来源端口写死。
        //     （写死等于把架构常量焊进注入层，端口一变就失效。）
        assertFalse(
            "例程不得硬编码 127.0.0.1:4173 字面量（目标必须是动态 location.origin）",
            routine.contains("127.0.0.1:4173"),
        )
    }

    // ---- 2) 只隐藏、不删除 --------------------------------------------------

    @Test
    fun usesDataAttributeAndCssHideWithoutDeletingNodes() {
        assertTrue(
            "必须用 data-oc-phantom 标记要隐藏的行容器",
            routine.contains("data-oc-phantom"),
        )
        assertTrue(
            "必须靠 CSS 隐藏标记元素（display:none!important）",
            routine.contains("display:none!important"),
        )
        assertTrue(
            "必须用受控的行容器选择器定位要隐藏的行",
            routine.contains("PHANTOM_ROW_SEL"),
        )
        // 🔴 血泪坑（2026-10-06 设备复验当场踩到，必须钉死）：
        //     无脑 closest('…, [data-slot]') 会爬到页面外壳，
        //     <html>/<body> 自身也含该 URL 文本且无匹配祖先 ⇒ 回落成"标记自身"
        //     ⇒ 实测 <html> 被标记，整页连 <style> 一起消失、屏幕全黑。
        assertTrue(
            "必须显式跳过 <html>/<body> 根节点（标记 <html> 会让整页消失）",
            routine.contains("el === document.documentElement") &&
                routine.contains("el === document.body"),
        )
        assertTrue(
            "必须给上爬加文本量上界（避免把分区/页面外壳当行容器）",
            routine.contains("candMax"),
        )
        assertFalse(
            "例程不得再用无约束 closest()（已改为 phantomRowFor 有界上爬）",
            routine.contains("closest('a,li,"),
        )
        // 容器里带输入框的一律不动 —— 保护 Add server 对话框。
        assertTrue(
            "带 input/textarea 的容器必须整体跳过（保护 Add server 对话框）",
            routine.contains("el.querySelector(\"input,textarea\")"),
        )
        // 🔴 核心红线：只隐藏、不删除、不改上游数据模型。
        //     （删除节点会破坏 visible[0] 焦点逻辑。）
        assertFalse(
            "例程里不得对行容器调用 remove()/removeChild —— 只允许隐藏，" +
                "删除会破坏上游 visible[0] 焦点逻辑。当前命中：$routineCode",
            routineCode.contains("removeChild") || routineCode.contains(".remove()"),
        )
    }

    // ---- 3) MutationObserver 节流 -------------------------------------------

    @Test
    fun mutationObserverIsThrottled() {
        assertTrue(
            "必须有 MutationObserver 跟住 SPA 重渲染",
            routine.contains("new MutationObserver"),
        )
        assertTrue(
            "必须 subtree+childList 全量监听（服务器行是动态插入的）",
            routine.contains("subtree: true") && routine.contains("childList: true"),
        )
        assertTrue(
            "必须有节流常量（防止每次 DOM 变动都全量扫描）",
            routine.contains("PHANTOM_THROTTLE_MS"),
        )
        // 幂等：已标记的元素跳过。
        assertTrue(
            "例程必须幂等（已打 data-oc-phantom 的元素要跳过）",
            routine.contains("已标记即跳过") || routine.contains("幂等"),
        )
    }

    // ---- 4) 不碰输入框 ------------------------------------------------------

    @Test
    fun skipsInputAndTextarea() {
        assertTrue(
            "必须排除 input（Add server 对话框里可能正输入着同一个地址）",
            routine.contains("\"INPUT\""),
        )
        assertTrue(
            "必须排除 textarea",
            routine.contains("\"TEXTAREA\""),
        )
        // 启动时机：只在首屏加载完之后启动，避免阻断首屏。
        assertTrue(
            "必须等 readyState==='complete' / load 之后再启动（不得阻断首屏）",
            routine.contains("readyState === \"complete\"") && routine.contains("\"load\""),
        )
    }

    // ---- 5) UX15-P1 · 候选取元素必须是 XPath 直接文本匹配（性能红线） ------

    /**
     * 自我回归审查（2026-10-06）钉的性能红线。
     *
     * 原实现是 `document.querySelectorAll("*")` + 逐元素 `el.textContent.includes(token)`：
     * `textContent` 是 O(子树) 的串接读取，把全页元素轮一遍就是 **O(整页文本量)**；而
     * 流式输出期间 DOM 高频变动，250ms 节流窗口内反复全量扫描 ⇒ 中低端机掉帧风险。
     *
     * 现在改为 `document.evaluate(<全文档后代元素轴> + [text()[contains(., "<token>")]] 谓词, …)`：
     * 浏览器原生实现只走文本节点通道，且**天然免疫 P6 踩到的祖先污染** ——
     * 祖先的 `textContent` 含该串，但它自己的**直接文本**并不含，根本不会进入上爬。
     *
     * 下面三条**只针对候选取元素这一段**；P6 保留的紧凑上界（`phantomRowFor` 里的
     * `node.textContent` 长度判定）是有界上爬的兜底，属于必须保留的血泪坑防护，
     * 不在禁用范围内（负向断言因此精确匹配 `querySelectorAll("*")` 与 `indexOf(token)`，
     * 而不是笼统禁 `textContent`）。
     */
    @Test
    fun candidateScanUsesXpathDirectTextMatchInsteadOfFullPageTextScan() {
        assertTrue(
            "候选取元素必须用 document.evaluate 走 XPath —— 全页 textContent 扫描在流式" +
                "输出期间是掉帧源（O(整页文本量) × 节流窗口）",
            routine.contains("document.evaluate"),
        )
        assertTrue(
            "必须用 ORDERED_NODE_SNAPSHOT_TYPE 取一次性快照（迭代期 DOM 变动不能影响候选集）",
            routine.contains("XPathResult.ORDERED_NODE_SNAPSHOT_TYPE"),
        )
        assertTrue(
            "XPath 必须是 '//*[text()[contains(., \"…\")]]' 的**直接文本**匹配形态 —— " +
                "这同时免疫 P6 的祖先污染（祖先 textContent 含目标串但直接文本不含）",
            routine.contains("//*[text()[contains(., \\\"\" + lit + \"\\\")]]"),
        )
        assertTrue(
            "必须经 snapshotItem(i) 遍历快照结果",
            routine.contains("hits.snapshotItem(i)"),
        )
        // 🔴 反向：全页元素枚举扫描不得复活。
        //    （只看**代码**、不看注释：oc-host.js 里允许留"原实现是这样的"说明文字，
        //      那是本轮改动的动机记录，不是可执行代码。）
        assertFalse(
            "例程不得再做 `querySelectorAll(\"*\")` 全页元素枚举（性能红线）",
            routineNoComments.contains("querySelectorAll(\"*\")"),
        )
        assertFalse(
            "例程不得再用 textContent.indexOf(token) 做候选命中判定（改为 XPath 直接文本匹配）",
            routineNoComments.contains("indexOf(token)"),
        )
        // XPath 字符串里的目标串必须是转义过的字面量，且目标仍动态取自 location.origin。
        assertTrue(
            "XPath 字面量必须对双引号做实体转义（否则地址含引号时 XPath 直接抛错）",
            routine.contains("token.replace(/\"/g, \"&quot;\")"),
        )
    }

    /**
     * UX15-P2 · 首启空态缺陷的绊线：**有用户服务器才隐藏**。
     *
     * P1 把扫描换成 XPath 后性能修好了，却暴露出一个首启回归：全新安装
     * （还没加过任何服务器）时 builtin 行被隐藏 ⇒ Home 无限期停在 Loading
     * 骨架屏、没有"添加服务器"引导、入口只剩一个无文字的 `+`。
     * 而首启时那条红点行恰恰是**唯一可操作的上下文**
     * （P8 实证：Edit 它就能改成自己的服务器 = 官方自带 onboarding 路径）。
     *
     * ⇒ 闸门信号取注册表键 `opencode.global.dat:server` 的**存在性**：
     * P5 实证该键**首次 Add 成功后才惰性创建** ⇒ 键存在 ⟺ 用户已有自己的服务器。
     *
     * 下面两条钉住：闸门引用了这个键；且键不存在时**一个节点都不动**。
     */
    @Test
    fun hideIsGatedOnUserServerRegistryKeyPresence() {
        assertTrue(
            "隐藏闸门必须读服务器注册表键 opencode.global.dat:server（P5 实证：首次 Add " +
                "成功才惰性创建 ⇒ 键存在 ⟺ 用户已添加过自己的服务器）",
            routine.contains("opencode.global.dat:server"),
        )
        assertTrue(
            "闸门必须通过惰性读取 localStorage.getItem(key) 的存在性判定（不能缓存成一次性结论， " +
                "否则首启时判定为 false 之后加服务器就再也放行不了）",
            routineNoComments.contains("localStorage.getItem(") &&
                routineNoComments.contains("PHANTOM_REGISTRY_KEY"),
        )
        assertTrue(
            "必须提供闸门判定函数 hasUserServerRegistry",
            routine.contains("function hasUserServerRegistry"),
        )
        // localStorage 不可用（隐私模式/被拒）时按"没有用户服务器"处理 ⇒ 退回首启不隐藏。
        // 这是更保守、也更不会误伤的一侧，必须钉住。
        assertTrue(
            "闸门读取必须被 try/catch 包裹且失败时返回 false（退回首启可见，绝不误隐藏）",
            routineNoComments.contains("function hasUserServerRegistry") &&
                Regex("function hasUserServerRegistry\\(\\)\\s*\\{[\\s\\S]{0,400}?catch\\s*\\([a-z]+\\)\\s*\\{\\s*return false;").containsMatchIn(
                    routineNoComments
                ),
        )
    }

    @Test
    fun doesNotHideWhenUserServerRegistryKeyAbsent() {
        // 闸门必须**包在 hidePhantomRows 入口处**（返回 0，一个节点都不动），
        // 而不是藏在循环里事后补救 —— 键不存在时连 XPath 扫描都不该启动。
        val gateIdx = routineNoComments.indexOf("if (!hasUserServerRegistry()) return 0;")
        val scanIdx = routineNoComments.indexOf("document.evaluate")
        val hideFnIdx = routineNoComments.indexOf("function hidePhantomRows")
        assertTrue(
            "hidePhantomRows 入口必须有 hasUserServerRegistry 闸门：" +
                "注册表键不存在（首启，未加过任何服务器）时必须**完全不动**，" +
                "否则首启用户失去唯一可操作的 onboarding 入口（Edit 4173 行改地址）",
            gateIdx >= 0,
        )
        assertTrue(
            "闸门必须写在 hidePhantomRows 函数体内",
            hideFnIdx >= 0 && gateIdx > hideFnIdx,
        )
        assertTrue(
            "闸门必须早于候选扫描 document.evaluate（键不存在时连扫描都不启动）",
            scanIdx >= 0 && gateIdx < scanIdx,
        )
        // 🔴 反向：不得把闸门写成"先扫后判"（例如循环内 continue）——
        //    那样首启虽然没隐藏，但每轮仍付一次全文档 XPath 扫描的开销。
        assertFalse(
            "闸门不得实现在循环体内（必须入口早退，否则首启每轮仍白付一次 XPath 扫描）",
            gateIdx > routineNoComments.indexOf("for (var i = 0; i < m; i++)"),
        )
    }

    /**
     * UX15-P1 血泪坑之二（**同轮设备复验当场踩到**）的绊线。
     *
     * 改用 XPath 直接文本匹配后，候选只剩"直接文本节点含目标串"的元素。真实 DOM
     * （设置 → Server 页）里那条链全是 `div`/`section`，**没有一个匹配
     * `PHANTOM_ROW_SEL`**：
     * ```
     * bdi.settings-servers-name   ← XPath 命中
     *   div.settings-servers-copy → div.settings-servers-lead
     *     div.settings-servers-row → div
     *       section.settings-section.settings-server-connection  ← 旧扫描在这里收口
     * ```
     * 于是 P6 原有的"回落标记命中元素自身"只把那个 `<bdi>` 藏了 ⇒ **行的红点与
     * "…" 仍在**，设备上表现为"服务器行只剩红点和三个点、名字没了"。
     *
     * 旧的全量扫描能整块隐藏，是因为祖先自身的 `textContent` 同样含目标串，
     * 所以 `section` 也是候选、并在"父级文本超上限"处收口被标记。
     * ⇒ 例程必须保留同样的收口语义。
     */
    @Test
    fun rowFallbackHidesOutermostCompactAncestorNotJustTheTextNode() {
        assertTrue(
            "有界上爬必须记住**最外层仍然紧凑**的祖先（XPath 只给直接文本命中，" +
                "整块隐藏的收口点只能从这里来）",
            routineNoComments.contains("lastCompact = node"),
        )
        assertTrue(
            "找不到 PHANTOM_ROW_SEL 匹配时必须回落标记 lastCompact，而不是命中的 <bdi> 自身 —— " +
                "只藏文本会留下行的红点与 \"…\"（设备实测到的形态）",
            routineNoComments.contains("return lastCompact || el"),
        )
        // 收口点仍然受紧凑上界约束：不能爬成页面外壳/整页。
        assertTrue(
            "上爬仍必须受紧凑上界约束（父级文本超上限即停）",
            routineNoComments.contains("trim().length > cap) break"),
        )
        assertTrue(
            "上爬不得越过根节点/head",
            routineNoComments.contains("node !== document.documentElement") &&
                routineNoComments.contains("node === document.head) break"),
        )
    }

    @Test
    fun neverHidesHomeServerSwitcher() {
        // UX17-v4 · 自锁修复（UX17-P3 实证）：Home 服务器切换器
        // （[data-component=home-projects-dropdown]）的文本 = "当前服务器名"。
        // 首加服务器后当前仍是 4173 ⇒ 藏了切换器 = 永远无法切走（死锁）。
        // 必须排除；用户切到自己服务器后其标签自然不再含 4173。
        assertTrue(
            "hidePhantomRows 必须排除 home-projects-dropdown（closest 语义，含自身与后代）",
            routineNoComments.contains(
                "row.closest('[data-component=\"home-projects-dropdown\"]')",
            ),
        )
        assertTrue(
            "排除规则必须位于 row 判定之后、setAttribute 之前（否则形同虚设）",
            run {
                val excl = routineNoComments.indexOf("home-projects-dropdown")
                val set = routineNoComments.indexOf("row.setAttribute(PHANTOM_ATTR")
                excl >= 0 && set > excl
            },
        )
    }
}