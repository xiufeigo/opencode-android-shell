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
 *
 * ## 反向验证
 *
 * 外科撤除例程 ⇒ 本类 4 条真红；恢复 ⇒ 全绿。
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
}