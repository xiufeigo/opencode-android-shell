package com.xiufeigo.opencodeshell.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * UX4 绊线（tripwire）：两个主题缺陷的修法不允许被静默撤回。
 *
 * ## 缺陷 1 —— 「+」选择面板配色
 * 产品负责人实测：浅色主题下「+」面板是一块突兀的深色（黑底白字）。
 * 根因是 `oc-host.js` 的 `buildPanel()` 把面板底色/文字/描边/hover **写死成
 * 深色字面量**（改前原文：`"background:#18181b", "border:1px solid #2e2e35", …,
 * "color:#e8e8ea"`）。修法：配色全部改读上游主题 token（`:root` 上的 CSS 变量，
 * 随主题/深浅色实时更新）：
 *   --surface-raised-base / --surface-raised-base-hover / --border-weaker-base /
 *   --text-strong / --text-weak
 * （依据：`_upstream/packages/ui/src/theme/themes/oc-2.json` 与
 * `packages/ui/src/theme/context.tsx` 的 `applyThemeCss`。）
 *
 * ## 缺陷 2 —— 「跟随系统」
 * 核查结论（详见 research/web/UX4_THEME_FIX.md §缺陷 2）：
 * 主题为 DayNight ✓、`configChanges` 含 `uiMode` ✓、WebView 无方向错误的
 * dark 设置 ✓、上游走 `prefers-color-scheme` ✓（设备实测跟随生效）。
 * 宿主侧确凿缺陷：**缺 `values-night/`** 且 `oc_host_bg` 恒为深灰
 * `#FF080808`（浅色模式下 WebView 首帧背后也是深灰），且**没有
 * `onConfigurationChanged` 处理**（`uiMode` 翻转后宿主不重推 scheme、
 * 系统栏明暗兜底不复查）。修法：`values-night/colors.xml` + 昼版改浅色
 * `#FFFAFAFA` + Activity 重写 `onConfigurationChanged`。
 *
 * ## 反向验证（本绊线必须能变红）
 * [redHardcodedPanel] 是**故意写坏**的样本（改前的 buildPanel 配色原文），
 * 扫描器必须判它不合格；把真实 `oc-host.js` / `colors.xml` 撤回改前内容后，
 * 对应断言必须真红（已实测，见 UX4 报告 §绊线与反向验证）。
 */
class Ux4ThemeTripwireTest {

    private fun read(rel: String): String {
        val f = File(rel)
        assertTrue("找不到 $rel（单测工作目录应为 webapp/ 模块目录）", f.isFile)
        return f.readText()
    }

    private val js: String by lazy { read("src/main/assets/www/oc-host.js") }
    private val colors: String by lazy { read("src/main/res/values/colors.xml") }
    private val nightColors: String by lazy { read("src/main/res/values-night/colors.xml") }
    private val activity: String by lazy {
        read("src/main/java/com/xiufeigo/opencodeshell/web/WebHostActivity.kt")
    }

    // ------------------------------------------------------------ 缺陷 1 断言

    /** 改前的错误形态：面板底色写死深色字面量。出现即 = 缺陷 1 回归。 */
    private val hardcodedPanelBg = Regex("""background:#18181b""")
    private val hardcodedPanelText = Regex("""color:#e8e8ea""")

    @Test
    fun `plus chooser panel has no hardcoded dark background`() {
        assertFalse(
            "「+」面板底色又写成硬编码深色字面量（background:#18181b）—— " +
                "浅色主题下会再次出现黑底面板（UX4 缺陷 1）",
            hardcodedPanelBg.containsMatchIn(js),
        )
    }

    @Test
    fun `plus chooser panel text is not hardcoded light-on-dark`() {
        assertFalse(
            "「+」面板文字色写死 #e8e8ea（深色底上的浅色字）—— 浅色主题下会看不清",
            hardcodedPanelText.containsMatchIn(js),
        )
    }

    @Test
    fun `plus chooser panel colors follow upstream theme tokens`() {
        assertTrue(
            "面板底色必须读上游 token --surface-raised-base（随主题/深浅色更新）",
            js.contains("var(--surface-raised-base,"),
        )
        assertTrue(
            "菜单项 hover 底色必须读上游 token --surface-raised-base-hover",
            js.contains("var(--surface-raised-base-hover,"),
        )
        assertTrue(
            "面板描边必须读上游 token --border-weaker-base",
            js.contains("var(--border-weaker-base,"),
        )
        assertTrue(
            "菜单项文字必须读上游 token --text-strong",
            Regex("""var\(--text-strong,""").containsMatchIn(js),
        )
        assertTrue(
            "「打开」小标题必须读上游 token --text-weak",
            js.contains("var(--text-weak,"),
        )
    }

    // ------------------------------------------------------------ 缺陷 2 断言

    @Test
    fun `window background follows daynight mode`() {
        // 只断言 oc_host_bg 那一行 —— ic_launcher_background 是合法的恒定深色。
        val dayBg = colors.lines().firstOrNull { it.contains("""name="oc_host_bg"""") }.orEmpty()
        val nightBg = nightColors.lines().firstOrNull { it.contains("""name="oc_host_bg"""") }.orEmpty()
        assertFalse(
            "昼版 oc_host_bg 又改回恒深色 #FF080808 —— 浅色模式下 WebView 首帧" +
                "背后是一块深灰（UX4 缺陷 2 宿主侧修法被撤回）",
            dayBg.contains("#FF080808"),
        )
        assertTrue(
            "昼版 oc_host_bg 应为浅色（与上游页面浅色底色 #fafafa 协调）",
            dayBg.contains("#FFFAFAFA"),
        )
        assertTrue(
            "必须有 values-night/colors.xml（改前缺失 ⇒ DayNight 没有 night 变体）",
            nightColors.isNotBlank(),
        )
        assertTrue(
            "夜版 oc_host_bg 应为深色 #FF080808（与上游页面深色底色一致）",
            nightBg.contains("#FF080808"),
        )
    }

    @Test
    fun `activity handles uimode configuration change`() {
        assertTrue(
            "configChanges 含 uiMode 时 Activity 必须重写 onConfigurationChanged" +
                "（否则深浅色切换后宿主不重推 scheme / 系统栏兜底不复查）",
            Regex("""override\s+fun\s+onConfigurationChanged""").containsMatchIn(activity),
        )
        val body = activity.substringAfter("override fun onConfigurationChanged")
        assertTrue(
            "onConfigurationChanged 必须重推 insets（把新 scheme 发给页面）",
            Regex("""insets\.refresh\(\)""").containsMatchIn(body),
        )
        assertTrue(
            "onConfigurationChanged 必须清掉 pageReportedDark（让系统栏明暗兜底" +
                "重新读当前 uiMode，而不是停留在页面旧上报值）",
            body.contains("pageReportedDark = null"),
        )
    }

    // ------------------------------------------------------------ 反向验证

    /** 坏样本：缺陷 1 改前的 buildPanel 配色原文（含写死的深色字面量）。 */
    private val redHardcodedPanel = """
        p.style.cssText = [
          "position:fixed", "z-index:2147483000",
          "left:" + left + "px", "top:" + top + "px", "width:" + w + "px",
          "background:#18181b", "border:1px solid #2e2e35", "border-radius:10px",
          "box-shadow:0 10px 30px rgba(0,0,0,.55)", "padding:6px",
          "font:14px/1.4 -apple-system,Roboto,'Noto Sans CJK SC',sans-serif", "color:#e8e8ea",
        ].join(";");
    """.trimIndent()

    @Test
    fun `scanner flags the old hardcoded dark panel as red`() {
        assertTrue(
            "扫描器对改前的硬编码深色面板样本不报红 —— 绊线是假绿",
            hardcodedPanelBg.containsMatchIn(redHardcodedPanel),
        )
        assertFalse(
            "改前样本不含任何上游 token，必须被判不合格",
            redHardcodedPanel.contains("var(--surface-raised-base,"),
        )
    }
}
