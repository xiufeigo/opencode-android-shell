package com.xiufeigo.opencodeshell.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * UX6 · **输入框方向键"焦点不越界"绊线**（缺陷：输入框内按 → 到行尾后，
 * 焦点被 WebView 空间导航抢到边栏/工具按钮；产品负责人原话报障）。
 *
 * ## 设备实测的根因（修复前，基线包 logcat 逐键读数）
 *
 * composer 是单行 `<input>`。光标在行尾时再按 →（KEYCODE_DPAD_RIGHT）：
 * 编辑器不消费该键 ⇒ 落给 WebView 的 focus search ⇒ `document.activeElement`
 * 从 INPUT 变成 BUTTON（逐键 eval 读数呈奇偶交替：奇数键 BUTTON、偶数键
 * INPUT，光标位置不再变化）。上游键位派发器对 editable 目标里的无修饰键
 * 直接 return（`_upstream/packages/app/src/shell/commands/command.tsx:410`），
 * 与此无关 —— 这是 WebView 原生空间导航，只能由注入层拦。
 *
 * ## 修法（oc-host.js 的 `onEditableArrowGuard`）
 *
 * 文档捕获阶段拦"光标已无处可移"的 ArrowLeft/ArrowRight：
 * 只 `preventDefault`（抑制 WebView 默认动作 = 空间导航），**不** `stopPropagation`
 * （上游自己的 keydown 消费者 —— 设置搜索 combobox 导航等 —— 照常收到事件）。
 *
 * ## 本类钉住的不变量
 *  1. 守卫注册存在（撤回整个函数/注册行 ⇒ [guardIsRegistered] 真红）；
 *  2. 守卫只做边界吞咽：函数体内有 `preventDefault`、**没有** `stopPropagation`；
 *  3. 守卫覆盖 Left/Right 两个方向且做选区折叠判断（selectionStart/End）；
 *  4. 守卫跳过终端（`.xterm`）与 IME 组合（`isComposing`）—— 这两条是
 *     "别改坏别的"的硬边界（终端把空 textarea 当按键汇，边界判定对它全命中）；
 *  5. 反向验证：对**故意写坏**的样本（无守卫的注入层）扫描器必须报红，
 *     证明绊线不是"永远绿"（[scannerFlagsSourceWithoutGuard]）。
 *
 * ## 与 InputPathHotspotTest 的关系
 * 该测试豁免了本守卫的 keydown 注册（唯一豁免点，三条窄化条件写在那里）；
 * 本类守的是**守卫本身的行为语义**，两者互补。
 */
class ComposerArrowGuardTripwireTest {

    private fun read(rel: String): String {
        val f = File(rel)
        assertTrue("找不到 $rel（单测工作目录应为 webapp/ 模块目录）", f.isFile)
        return f.readText()
    }

    private val js: String by lazy { read("src/main/assets/www/oc-host.js") }

    /** 取出 `function onEditableArrowGuard(...) { ... }` 的函数体（花括号配平）。 */
    private fun guardBody(): String {
        val start = js.indexOf("function onEditableArrowGuard(")
        assertTrue("oc-host.js 里没有 onEditableArrowGuard —— 方向键守卫被整体撤掉了", start >= 0)
        val open = js.indexOf('{', start)
        assertTrue("onEditableArrowGuard 后找不到函数体", open >= 0)
        var depth = 0
        var i = open
        while (i < js.length) {
            when (js[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return js.substring(open, i + 1)
                }
            }
            i++
        }
        throw AssertionError("onEditableArrowGuard 花括号不配平")
    }

    // ---- 1) 注册与存在性 ---------------------------------------------------

    @Test
    fun guardIsRegistered() {
        assertTrue(
            "方向键守卫必须注册为文档捕获 keydown 监听（撤回注册行 ⇒ 本用例红）",
            js.contains("addEventListener(\"keydown\", onEditableArrowGuard, true)"),
        )
    }

    // ---- 2) 行为语义 --------------------------------------------------------

    @Test
    fun guardSwallowsByPreventDefaultOnly() {
        val body = guardBody()
        assertTrue(
            "守卫必须 preventDefault（抑制 WebView 空间导航，焦点留在输入框）",
            body.contains("e.preventDefault()"),
        )
        assertFalse(
            "守卫**不许** stopPropagation：上游自己的 keydown 消费者" +
                "（设置搜索 combobox、时间线搜索）必须照常收到事件；" +
                "stopPropagation 会把它们一并闷死。",
            body.contains("stopPropagation"),
        )
    }

    @Test
    fun guardCoversBothHorizontalDirections() {
        val body = guardBody()
        assertTrue(body.contains("\"ArrowLeft\""))
        assertTrue(body.contains("\"ArrowRight\""))
    }

    @Test
    fun guardChecksCaretBoundaryWithCollapsedSelection() {
        val body = guardBody()
        assertTrue("必须读 selectionStart（input/textarea 路径）", body.contains("selectionStart"))
        assertTrue("必须读 selectionEnd（input/textarea 路径）", body.contains("selectionEnd"))
        assertTrue(
            "必须做选区折叠判断（未折叠时放行，让默认行为收拢选区）",
            body.contains("start !== end"),
        )
    }

    @Test
    fun guardCoversContentEditableComposer() {
        val body = guardBody()
        assertTrue(
            "composer 的 contenteditable 形态实测同样被空间导航抢焦点（行尾按 → ⇒ " +
                "activeElement 变 BUTTON 且不再回来）⇒ 守卫必须覆盖 contenteditable 路径",
            body.contains("getSelection") && body.contains("isContentEditable"),
        )
        assertTrue(
            "contenteditable 边界判定必须按'光标前字符数'做（首/尾判定）",
            body.contains("selectNodeContents"),
        )
    }

    @Test
    fun guardSkipsTerminalTextarea() {
        assertTrue(
            "守卫必须跳过 .xterm 内的 textarea（终端把空 textarea 当按键汇，" +
                "边界判定对它全命中，不跳过会把终端方向键整个吞掉）",
            guardBody().contains(".xterm"),
        )
    }

    @Test
    fun guardSkipsImeComposing() {
        assertTrue(
            "守卫必须跳过 IME 组合中（isComposing / keyCode 229）",
            guardBody().contains("isComposing"),
        )
    }

    @Test
    fun guardIgnoresModifiedKeys() {
        assertTrue(
            "守卫必须放行带修饰键的组合（ctrl/meta/alt —— 上游键位走这些）",
            guardBody().contains("ctrlKey") && guardBody().contains("metaKey"),
        )
    }

    // ---- 3) 反向验证：扫描器必须能变红 --------------------------------------

    /** 模拟"撤回修复"的注入层：没有守卫函数、没有注册。 */
    private val redJsNoGuard = """
        (function () {
          var N = window.__ocAndroidNative;
          if (!N) return;
          document.addEventListener("focusin", function () {}, true);
        })();
    """.trimIndent()

    /** 和守卫同款的扫描器：注册存在 ⇏ 守卫在；函数体缺失才算撤回。 */
    private fun guardPresent(source: String): Boolean =
        source.contains("addEventListener(\"keydown\", onEditableArrowGuard, true)") &&
            source.contains("function onEditableArrowGuard(") &&
            source.contains("e.preventDefault()")

    @Test
    fun scannerFlagsSourceWithoutGuard() {
        assertFalse(
            "反向验证失败：对**没有守卫**的注入层样本，扫描器仍判'守卫在' ⇒ 绊线是假绿。",
            guardPresent(redJsNoGuard),
        )
        assertTrue(
            "扫描器必须对真实 oc-host.js 判'守卫在' —— 否则是扫描器自己坏了。",
            guardPresent(js),
        )
    }

    @Test
    fun realGuardRegistrationCountIsOne() {
        val count = Regex("""addEventListener\(\s*["']keydown["']\s*,\s*onEditableArrowGuard\s*,\s*true\s*\)""")
            .findAll(js).count()
        assertEquals("方向键守卫注册必须恰好一处（$count 处）", 1, count)
    }
}
