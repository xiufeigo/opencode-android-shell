package com.xiufeigo.opencodeshell.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * UX5 绊线（tripwire）：选择面板的两个动作**必须全部透传给上游**，不许自造。
 *
 * ## 背景（产品负责人 2026-10-05 实测打回，唯一验收标准）
 * ① 「打开终端」按了没正常打开终端；② 「打开文件」变成了"选择我平板上的文件"
 * （宿主原生 SAF 选择器）——负责人要的是"**原本点那个 + 号所触发的功能**"。
 *
 * 设备实证（research/web/UX5_CHOOSER_ACTIONS.md）：
 * - 上游审查条「+」（aria-label="Open file"）原行为 = 审查条出现「Open file」
 *   标签页 + 工作区应用内文件浏览器（session-side-panel.tsx:475-482 →
 *   openFileBrowser() :205-208）。⇒ 「打开文件」必须把合成 click 还给被拦截的
 *   那个「+」自己（passThrough），构造上即与"直接点 +"等价。
 * - 终端面板收起时终端条**不在 DOM**（实测 tablists=0），只 passThrough 点
 *   「New terminal」按钮 = 什么都不发生（logcat "no upstream terminal trigger
 *   found"）。上游自己的打开终端序列 = openTerminal()
 *   （use-session-commands.tsx:195-199，绑定 terminal.new 命令，默认键位
 *   ctrl+alt+t）；键位派发器（command.tsx:401-428）只看 event.key/修饰键、
 *   无 isTrusted 检查 ⇒ 合成 KeyboardEvent 可触发上游自己的 handler
 *   （设备实证：合成 ctrl+` 打开终端面板 + 自动创建 Terminal 1 + 活 PowerShell）。
 *
 * ## 静态判据（对 oc-host.js 的 installPlusChooser 段扫描）
 * 1. 「打开文件」= passThrough 还给 findPlusButton() 命中的上游「+」；
 *    该段**不得出现** `pickFile(` 调用（宿主 SAF 路线撤回）。
 * 2. 「打开终端」必须有两条上游路：终端条触发器**真实可见**（rect 宽高 > 0，
 *    不只是 offsetParent——零高缓存挂载时 offsetParent 仍非 null）时 passThrough
 *    点它；否则派发上游 terminal.new 键位（dispatchUpstreamKey + ctrl/alt）。
 *    键位路必须带自检（oc:open-terminal 事件回读 found 标志），不许"发了就宣称好了"。
 *
 * ## 反向验证（本绊线必须能变红）
 * [redSafFileAction] 模拟 UX3/UX4 的宿主 SAF 实现；[redClickOnlyTerminal]
 * 模拟"只点上游按钮"的旧实现（终端面板收起时按了没反应——产品负责人打回的那版）。
 * 扫描器必须对两者报红，否则是假绿绊线。
 */
class Ux5ChooserActionsTripwireTest {

    private fun read(rel: String): String {
        val f = File(rel)
        assertTrue("找不到 $rel（单测工作目录应为 webapp/ 模块目录）", f.isFile)
        return f.readText()
    }

    private val js: String by lazy { read("src/main/assets/www/oc-host.js") }

    /** installPlusChooser 段：从函数体到 window.__ocPlusChooser 暴露赋值之前。 */
    private val chooserBody: String by lazy {
        val from = js.indexOf("function installPlusChooser()")
        assertTrue("oc-host.js 里找不到 installPlusChooser()", from >= 0)
        // 注意函数体第一行就是 if (window.__ocPlusChooser) return; ——
        // 所以终点必须用「暴露赋值」原文，不能只用 "window.__ocPlusChooser"。
        val to = js.indexOf("window.__ocPlusChooser = {", from)
        assertTrue("oc-host.js 里找不到 __ocPlusChooser 暴露赋值点", to > from)
        js.substring(from, to)
    }

    /** 宿主 SAF 调用（`pickFile(`）——出现在选择面板段即 = UX3/UX4 的错路回归。 */
    private val safCall = Regex("""\bpickFile\s*\(""")

    @Test
    fun `open file hands the click back to upstream plus, never host SAF`() {
        assertTrue(
            "「打开文件」必须用 findPlusButton() 找回被拦截的上游「+」",
            chooserBody.contains("findPlusButton()"),
        )
        assertTrue(
            "「打开文件」必须用 passThrough 把合成 click 还给上游「+」自己",
            Regex("""passThrough\s*=\s*true""").containsMatchIn(chooserBody),
        )
        assertFalse(
            "「打开文件」又调用了宿主原生 pickFile（会弹 Android 系统文件选择器）——" +
                "产品负责人明确要求透传上游「+」的原行为",
            safCall.containsMatchIn(chooserBody),
        )
    }

    @Test
    fun `open terminal must really open a terminal via upstream`() {
        assertTrue(
            "「打开终端」必须能在终端面板已开时 passThrough 点上游「New terminal」",
            chooserBody.contains("findTerminalTrigger()"),
        )
        assertTrue(
            "终端触发器可见性必须用 getBoundingClientRect 的真实宽高判定" +
                "（零高缓存挂载时 offsetParent 仍非 null，只靠它会误判）",
            chooserBody.contains("getBoundingClientRect") &&
                Regex("""r\.width\s*>\s*0\s*&&\s*r\.height\s*>\s*0""").containsMatchIn(chooserBody),
        )
        assertTrue(
            "「打开终端」必须在终端面板未开时派发上游自己的 terminal.new 键位" +
                "（dispatchUpstreamKey，ctrl+alt+t —— use-session-commands.tsx openTerminal 序列）",
            chooserBody.contains("dispatchUpstreamKey(\"t\", true, true)"),
        )
        assertTrue(
            "键位路必须派发合成 keydown（上游键位派发器 command.tsx:401-428" +
                " 只看 key/修饰键，无 isTrusted 检查）",
            chooserBody.contains("KeyboardEvent(\"keydown\""),
        )
        assertTrue(
            "键位路必须自检终端面板真的出现（oc:open-terminal 事件回读 found 标志），" +
                "不许只发一个事件就宣称打开成功",
            chooserBody.contains("oc:open-terminal"),
        )
    }

    @Test
    fun `host facade keeps pickFile for other callers outside the chooser`() {
        // facade 层的 pickFile 桥本身保留（别处可能用），但选择面板段不得出现 ——
        // 这条用例同时钉住"扫描段"没选错范围：facade 定义在选择面板段之外。
        assertTrue(
            "facade 的 pickFile 桥应该仍在（供面板以外的调用方使用）",
            Regex("""pickFile:\s*function""").containsMatchIn(js),
        )
    }

    // ---------------------------------------------------------------- 反向验证

    /** 坏样本 1：UX3/UX4 的「打开文件」= 宿主原生 SAF 选择器。 */
    private val redSafFileAction = """
        function runFile() {
          try {
            var b = window.__ocAndroid;
            if (b && b.pickFile) {
              b.pickFile("*/*", false);
              return true;
            }
          } catch (e) {}
          return false;
        }
    """.trimIndent()

    /** 坏样本 2：UX3/UX4 的「打开终端」= 只 passThrough 点上游按钮（面板收起时按了没反应）。 */
    private val redClickOnlyTerminal = """
        function runTerminal() {
          var btn = findTerminalTrigger();
          if (!btn) {
            window.dispatchEvent(new CustomEvent("oc:open-terminal", { detail: { found: false } }));
            return false;
          }
          passThrough = true;
          try { btn.click(); } finally { passThrough = false; }
          return true;
        }
    """.trimIndent()

    @Test
    fun `scanner flags host SAF open-file as red`() {
        assertTrue(
            "扫描器对宿主 SAF 版「打开文件」样本不报红 —— 绊线是假绿",
            safCall.containsMatchIn(redSafFileAction),
        )
    }

    @Test
    fun `scanner flags click-only open-terminal as red`() {
        assertFalse(
            "坏样本没有上游键位路（dispatchUpstreamKey），必须被判不合格 —— " +
                "终端面板收起时该实现按了没反应（产品负责人打回的那版）",
            redClickOnlyTerminal.contains("dispatchUpstreamKey(\"t\", true, true)"),
        )
        assertFalse(
            "坏样本没有 rect 宽高可见性判据，必须被判不合格",
            redClickOnlyTerminal.contains("getBoundingClientRect"),
        )
    }
}
