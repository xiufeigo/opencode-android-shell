package com.xiufeigo.opencodeshell.web.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * TERM1 绊线：**IME 弹起时把布局视口压矮**（键盘遮住终端的修法）。
 *
 * ### 缺陷与根因（2026-10-07 产品负责人报单 + 同机型设备实测）
 *
 * 边到边窗口（`WindowCompat.setDecorFitsSystemWindows(false)`）下 `adjustResize` 失效，
 * 布局视口**不随键盘变矮**。实测：弹键盘后 `--oc-ime-bottom = 336.38` CSS px、
 * 而 `innerHeight` 仍是 **915**、`visualViewport.height` 也仍是 915。
 * 早先只有 "页面侧给 composer 垫留白" 一条让位路径 ⇒ 靠视口高度的界面全废：
 *
 * | 界面 | 修前被遮 |
 * | --- | --- |
 * | 终端面板 `[data-component=terminal]` | **327px** |
 * | 终端画布 | **310px**（光标/最新输出落在键盘后面） |
 *
 * `research/env/FORMFACTORS_F4.md` §6 问题 3 早写过这个脆弱点：
 * "一旦 composer 让位逻辑在某个宽度上失效，**不会退化成 resize 兜底，而是直接被遮挡**"。
 * 本轮就是补兜底：宿主把 content 层底部压 `max(ime, nav)`，视口真的变矮。
 *
 * ### 两层断言
 * 1. **几何层**（[ImeResize] 纯函数）：用真机读数 + 平台僵死读数做固定输入。
 * 2. **契约层**：宿主源码必须压"WebView 的父（content 层）"而不是往下找 `android.R.id.content`
 *    （旧实现恒为 null ⇒ 从来没生效）；注入层必须把页面侧抬升**只在 vars 模式**生效
 *    （resize 下再垫一次 = 双倍留白，composer 会飘到半空）。
 */
class ImeViewportResizeTest {

    // ---- 真机读数（411×914 CSS / dpr 2.625 / 1080×2400 px）-------------------
    private val phoneWindowPx = 2400
    private val phoneImePx = 883 // = 336.38 CSS px × 2.625
    private val phoneNavPx = 63

    // ---- 1. 几何层 ---------------------------------------------------------

    @Test
    fun `有键盘时按 IME 高度压矮 取 ime 与 nav 的较大者`() {
        assertEquals(
            "手机实测：ime 883 > nav 63 ⇒ 压 883",
            phoneImePx,
            ImeResize.bottomPadPx(phoneWindowPx, phoneImePx, phoneNavPx),
        )
        assertEquals(
            "只有导航条（键盘收起）时也要让出导航条",
            phoneNavPx,
            ImeResize.bottomPadPx(phoneWindowPx, 0, phoneNavPx),
        )
    }

    @Test
    fun `键盘收起与无效几何一律不动`() {
        assertEquals(0, ImeResize.bottomPadPx(phoneWindowPx, 0, 0))
        assertEquals("窗口高未知时不动（宁可不动也不要猜）", 0, ImeResize.bottomPadPx(0, phoneImePx, phoneNavPx))
        assertEquals(0, ImeResize.bottomPadPx(-1, phoneImePx, phoneNavPx))
    }

    @Test
    fun `挡掉平台的僵死 IME 读数 别把视口压成一条缝`() {
        // v77 实测事故：上报 2195px、真实键盘只有 766px（窗口 2400 ⇒ 占比 91%）。
        assertEquals(
            "占比 ≥ 85% 判定为僵死读数 ⇒ 不压（退化成老行为，而不是把界面压坏）",
            0,
            ImeResize.bottomPadPx(phoneWindowPx, 2195, 63),
        )
        // 边界：刚好 85% 也不压。
        assertEquals(0, ImeResize.bottomPadPx(1000, 850, 0))
        // 84.9% 仍然压（横屏键盘可以很高，别误杀）。
        assertEquals(849, ImeResize.bottomPadPx(1000, 849, 0))
    }

    @Test
    fun `压矮量永远小于窗口高 不会把视口压没`() {
        val pad = ImeResize.bottomPadPx(1000, 999, 0)
        assertTrue("压矮量必须 < 窗口高（实测 $pad）", pad < 1000)
    }

    // ---- 2. 宿主契约层 -----------------------------------------------------

    private fun read(relative: String): String {
        val f = File(relative)
        assertTrue("找不到 $relative（单测工作目录应为 webapp/ 模块目录）", f.isFile)
        return f.readText()
    }

    @Test
    fun `宿主压的是 WebView 的父 content 层 不是往下找 content id`() {
        val src = read("src/main/java/com/xiufeigo/opencodeshell/web/host/InsetsHost.kt")
        assertTrue("必须按 max(ime, nav) 压矮", src.contains("ImeResize.bottomPadPx("))
        assertTrue("必须作用在真正那一层上（WebView 的父）", src.contains("val p = root.parent"))
        assertTrue("必须真的下 padding", src.contains("layer.setPadding("))
        assertFalse(
            "旧实现的坑：findViewById(android.R.id.content) 往下找永远找不到（content 是祖先）⇒ 恒 null ⇒ 修法从未生效",
            src.contains("findViewById<View>(android.R.id.content)"),
        )
        assertFalse(
            "旧实现里那道 lp.height 守卫也是死代码（MATCH_PARENT 恒等于它排除掉的那个值）",
            Regex("""lp\.height\s*!=\s*-1""").containsMatchIn(src),
        )
    }

    @Test
    fun `默认就是 resize 模式`() {
        val src = read("src/main/java/com/xiufeigo/opencodeshell/web/host/InsetsHost.kt")
        assertTrue(
            "默认必须是 RESIZE（页面侧单条让位路径已经证明不够）",
            Regex("""var imeMode: ImeMode = ImeMode\.\w+""").find(src)?.value?.contains("RESIZE") == true,
        )
    }

    // ---- 3. 注入层契约层（防"双倍留白"） -----------------------------------

    private fun facade(): String = read("src/main/assets/www/oc-host.js")

    @Test
    fun `页面侧抬升只在 vars 模式生效 避免双倍留白`() {
        val src = facade()
        assertTrue(
            "必须把 imeMode 透成 html 属性供 CSS 判据用",
            src.contains("setAttribute(\"data-oc-ime-mode\"") && src.contains("d.imeMode"),
        )
        // 每条会让位的 CSS 规则前面都必须有 resize 闸门。
        // （JS 源码里引号是转义过的，所以只看闸门前缀，别去匹配带引号的整串。）
        val gate = "data-oc-ime-mode="
        assertTrue(
            "至少要有闸门（实测 ${Regex(Regex.escape(gate)).findAll(src).count()} 处）",
            Regex(Regex.escape(gate)).findAll(src).count() >= 2,
        )
        Regex(Regex.escape("form:has(")).findAll(src).forEach { m ->
            val before = src.substring(maxOf(0, m.range.first - 90), m.range.first)
            assertTrue(
                "composer 的 form:has(...) 规则必须带 resize 闸门（resize 下会再垫一次 IME ⇒ composer 飘到半空）",
                before.contains(gate),
            )
        }
        val lift = src.indexOf("[data-oc-ime-lift]{")
        assertTrue("兜底规则必须还在", lift > 0)
        assertTrue(
            "兜底的 [data-oc-ime-lift] 规则也必须带 resize 闸门",
            src.substring(maxOf(0, lift - 90), lift).contains(gate),
        )
        assertTrue("抬升仍由宿主实测值驱动", src.contains("var(--oc-ime-bottom"))
    }
}
