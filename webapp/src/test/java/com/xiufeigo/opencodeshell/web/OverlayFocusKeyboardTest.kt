package com.xiufeigo.opencodeshell.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * OC-KEYBOARD 绊线：**弹层（菜单/对话框/popper）里的"自动聚焦"不许再弹软键盘**。
 *
 * ## 它钉住的产品行为（2026-10-07 产品负责人报单）
 *
 * 「点模型芯片 → 菜单从底部往上弹、还自己弹键盘」其实是**同一个根因**：上游给菜单里的
 * 搜索框加了 `autofocus` 并在打开时 `focus()`，Android WebView 一给可编辑元素焦点就拉 IME；
 * 键盘吃掉半屏 ⇒ popper 只能把菜单顶到屏幕中上部 ⇒ 看起来像"从底部弹上来的大面板"。
 * 设备实测（411×914 CSS）：修前 `mInputShown=true` + 菜单 y=230；修后 `false` + y=566（贴着芯片）。
 *
 * ## 修法的三条边界（本类逐条钉死）
 *
 * 1. 只拦**程序化** focus：`HTMLElement.prototype.focus` 包装，其余情况 `orig.apply` 原样放行。
 * 2. 只拦**浮层内**的可编辑元素（菜单 / 对话框 / popper / 移动抽屉）。
 * 3. **用户点出来的聚焦一律放行**（1s 手势窗口 + 目标匹配）⇒ 点搜索框键盘照常弹、能输入。
 *
 * 反向断言（防后人换成另一套机制或把范围放宽）：
 * · 不许用 `inputmode` / `blur()` / 直接改 `document.activeElement` 这些**第二套真相来源**；
 * · 不许注册逐键监听（另有 `InputPathHotspotTest` 守全文件，这里再对**本段**守一次）。
 */
class OverlayFocusKeyboardTest {

    private fun read(relative: String): String {
        val f = File(relative)
        assertTrue("找不到 $relative（单测工作目录应为 webapp/ 模块目录）", f.isFile)
        return f.readText()
    }

    private val js: String by lazy { read("src/main/assets/www/oc-host.js") }

    /** 本补丁那一段（从 OC-KEYBOARD 段头到文件末尾）。 */
    private val block: String by lazy {
        val idx = js.indexOf("OC-KEYBOARD")
        assertTrue("oc-host.js 里找不到 OC-KEYBOARD 段（弹层聚焦守卫）", idx >= 0)
        js.substring(idx)
    }

    /** 去掉 `//` 行注释后的可执行代码（负向断言只盯代码）。 */
    private val code: String by lazy {
        block.lineSequence().map { it.substringBefore("//") }.joinToString("\n")
    }

    @Test
    fun patchesFocusOnceAndCallsThroughOtherwise() {
        assertTrue(
            "必须包装 HTMLElement.prototype.focus（程序化聚焦的唯一收口）",
            code.contains("window.HTMLElement && window.HTMLElement.prototype"),
        )
        assertTrue("必须保存原 focus 并在放行分支调用它", code.contains("orig.apply(this, arguments)"))
        assertTrue(
            "必须幂等（重复注入不得二次包装）",
            code.contains("__ocFocusGuard"),
        )
        assertTrue(
            "放行分支必须**透传参数**（focus({preventScroll}) 不能被吃掉）",
            code.contains("orig.apply(this, arguments)"),
        )
    }

    @Test
    fun blocksOnlyProgrammaticFocusInsideOverlays() {
        // 三个条件必须同时成立才拦。
        assertTrue(
            "守卫条件必须是「可编辑 && 在浮层内 && 非用户手势」三者同时成立",
            code.contains("ocEditable(this) && ocInOverlay(this) && !ocFocusFromGesture(this)"),
        )
        for (needle in listOf("INPUT", "TEXTAREA", "isContentEditable")) {
            assertTrue("可编辑判定必须覆盖 $needle", code.contains(needle))
        }
        for (needle in listOf(
            "[role=\"menu\"]",
            "[role=\"dialog\"]",
            "[data-component=\"menu-v2-content\"]",
            "[data-slot=\"dialog-container\"]",
            "[data-popper-positioner]",
            "[data-corvu-drawer-content]",
        )) {
            assertTrue("浮层判定必须覆盖 $needle（设备实测的模型菜单就是 menu-v2-content + popper）", code.contains(needle))
        }
    }

    @Test
    fun userGestureAlwaysWinsSoTappingAnInputStillOpensTheKeyboard() {
        assertTrue(
            "必须记录用户手势（pointerdown：非逐键，一次交互一次）",
            code.contains("addEventListener(") && code.contains("\"pointerdown\""),
        )
        assertTrue(
            "手势必须记下**目标元素**（否则无法区分'点芯片'与'点输入框'）",
            code.contains("target: e.target"),
        )
        assertTrue(
            "必须有手势时效窗口常量并参与判定",
            code.contains("OC_FOCUS_GESTURE_MS") && code.contains("Date.now() - g.at >"),
        )
        assertTrue(
            "手势匹配必须是「点的就是它 / 是它的后代」",
            code.contains("g.target === el || el.contains(g.target)"),
        )
        // 逐键监听在本段里也必须没有（全文件另有 InputPathHotspotTest）。
        for (ev in listOf("keydown", "keypress", "keyup", "beforeinput")) {
            assertFalse("本段不得注册逐键监听 $ev", code.contains("addEventListener(\n      \"$ev\"") || code.contains("\"$ev\""))
        }
    }

    @Test
    fun installedAtDocumentStartAndObservableOnDevice() {
        // 注入层是 addDocumentStartJavaScript ⇒ 顶层直调即"文档开始"，早于页面脚本。
        val install = code.indexOf("installOverlayFocusGuard();")
        assertTrue("文件里必须有顶层直调 installOverlayFocusGuard();", install >= 0)
        val gesture = code.indexOf("addEventListener(")
        assertTrue(
            "手势监听必须先于守卫安装（否则第一次弹层的聚焦无法归因）",
            gesture >= 0 && gesture < install,
        )
        assertTrue(
            "必须留设备可读的计数（CDP 读 window.__ocFocusGuard 即可确认生不生效）",
            code.contains("window.__ocFocusGuard") && code.contains("blocked"),
        )
    }

    @Test
    fun doesNotIntroduceASecondMechanism() {
        // 只允许"不聚焦"这一种手段：另加静音/失焦会让"谁在控制键盘"出现第二套真相。
        assertFalse("不得改用 inputmode 静音（用户点进来还得补一次 blur+focus，状态会漏）", code.contains("inputmode"))
        assertFalse("不得在守卫里 blur()（会先弹一下再收，肉眼可见闪烁）", code.contains(".blur("))
        assertFalse("不得直接改 document.activeElement", code.contains("document.activeElement"))
    }
}
