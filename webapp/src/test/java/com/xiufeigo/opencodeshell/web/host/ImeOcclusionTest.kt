package com.xiufeigo.opencodeshell.web.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/**
 * IME1 绊线：断言「IME 弹起时 composer 与 IME 区域的重叠面积为 0」。
 *
 * ### 两层
 * 1. **几何层**（[ImeOcclusion] 的纯函数）：用 IME1 改前**真机原始读数**做固定输入。
 * 2. **资产契约层**（[facadeConsumesImeBottomForComposer]）：读注入层 `oc-host.js`，
 *    断言修法**真的在**资产里。这一层是**可反向验证**的关键——
 *    把修法撤回（删掉 `oc-ime-lift` 那段）⇒ 本用例必红。
 *
 * ### 原始读数出处（research/web/IME_OCCLUSION_FIX.md §2）
 * 手机 412×915 @dpr2.625：ime frame=[0,1517][1080,2400] 高 883px
 *   ⇒ --oc-ime-bottom = 336.38 CSS px（336.38×2.625 = 883 ✓）
 *   composer [790, 850] ⇒ 改前重叠 **60** px。
 * 平板 1366×768 @dpr1：ime frame=[0,342][1366,768] 高 426px
 *   composer [643, 703] ⇒ 改前重叠 **60** px。
 */
class ImeOcclusionTest {

    // ---- 读数常量（与设备原始输出一一对应，改动前请先改报告） ----------------
    private val phoneViewport = 915.0
    private val phoneImeBottom = 336.38
    private val phoneComposerBefore = ImeOcclusion.Rect(top = 790.0, bottom = 850.0)

    private val tabletViewport = 768.0
    private val tabletImeBottom = 426.0
    private val tabletComposerBefore = ImeOcclusion.Rect(top = 643.0, bottom = 703.0)

    // ---- 1. 几何：imeTopCss ------------------------------------------------

    @Test
    fun `ime 上沿等于视口高减 ime 高度`() {
        assertEquals(578.62, ImeOcclusion.imeTopCss(phoneImeBottom, phoneViewport), 0.01)
        assertEquals(342.0, ImeOcclusion.imeTopCss(tabletImeBottom, tabletViewport), 0.01)
    }

    @Test
    fun `ime 收起时上沿贴视口底 不遮挡任何东西`() {
        assertEquals(phoneViewport, ImeOcclusion.imeTopCss(0.0, phoneViewport), 0.01)
        assertEquals(0.0, ImeOcclusion.overlapPx(phoneComposerBefore, phoneViewport), 0.0)
        assertTrue(ImeOcclusion.isFullyVisible(phoneComposerBefore, 0.0, phoneViewport))
    }

    // ---- 2. 几何：重叠量（缺陷的量化口径） ---------------------------------

    @Test
    fun `改前读数 手机侧重叠等于 composer 全高 60px`() {
        val top = ImeOcclusion.imeTopCss(phoneImeBottom, phoneViewport)
        assertEquals(
            "IME1 改前：composer 整条落在键盘带内 ⇒ 重叠应为 60",
            60.0,
            ImeOcclusion.overlapPx(phoneComposerBefore, top),
            0.01,
        )
        assertFalse(ImeOcclusion.isFullyVisible(phoneComposerBefore, phoneImeBottom, phoneViewport))
    }

    @Test
    fun `改前读数 平板侧重叠同样等于 60px 证明非窄屏特有`() {
        val top = ImeOcclusion.imeTopCss(tabletImeBottom, tabletViewport)
        assertEquals(
            "IME1 改前：平板几何下同样完全遮挡",
            60.0,
            ImeOcclusion.overlapPx(tabletComposerBefore, top),
            0.01,
        )
        assertFalse(ImeOcclusion.isFullyVisible(tabletComposerBefore, tabletImeBottom, tabletViewport))
    }

    // ---- 3. 几何：上抬后必须归零（这是修法的数学前提） ---------------------

    @Test
    fun `按 requiredLift 上抬后重叠精确归零`() {
        listOf(
            Triple(phoneComposerBefore, phoneImeBottom, phoneViewport),
            Triple(tabletComposerBefore, tabletImeBottom, tabletViewport),
        ).forEach { (composer, ime, vh) ->
            val lift = ImeOcclusion.requiredLiftPx(composer, ime, vh)
            val lifted = ImeOcclusion.Rect(composer.top - lift, composer.bottom - lift)
            assertEquals(
                "上抬 $lift px 后重叠必须为 0",
                0.0,
                ImeOcclusion.overlapPx(lifted, ImeOcclusion.imeTopCss(ime, vh)),
                0.0,
            )
            assertTrue(ImeOcclusion.isFullyVisible(lifted, ime, vh))
        }
    }

    @Test
    fun `键盘比 composer 矮时 requiredLift 为 0`() {
        // 视口 915，键盘只占 100 ⇒ 上沿 815，composer 底 850 仍需上抬 35。
        val lift = ImeOcclusion.requiredLiftPx(phoneComposerBefore, 100.0, phoneViewport)
        assertEquals(35.0, lift, 0.01)
        // 键盘只占 60 ⇒ 上沿 855 > 850 ⇒ 已经在键盘上方，无需移动。
        assertEquals(0.0, ImeOcclusion.requiredLiftPx(phoneComposerBefore, 60.0, phoneViewport), 0.0)
    }

    // ---- 4. 资产契约层：**可反向验证**的那条 --------------------------------

    private fun facade(): String {
        val f = File("src/main/assets/www/oc-host.js")
        assertTrue("找不到注入层资产：${f.absolutePath}（单测工作目录应为 webapp/ 模块目录）", f.isFile)
        return f.readText()
    }

    @Test
    fun `facadeConsumesImeBottomForComposer`() {
        val src = facade()
        // (a) 修法本体在资产里
        assertTrue(
            "撤回修法 ⇒ 注入层不再注册 oc-ime-lift 样式",
            src.contains("oc-ime-lift"),
        )
        // (b) 它消费的是宿主实测的 --oc-ime-bottom（不是硬编码数字）
        assertTrue(
            "抬升必须由 --oc-ime-bottom 驱动，不能硬编码 IME 高度",
            src.contains("var(--oc-ime-bottom"),
        )
        // (c) 锚定在 composer 容器上：:has() 按语义找，不绑上游类名
        assertTrue(
            "修法必须锚定 composer 容器（:has(textarea) / [contenteditable]）",
            src.contains("form:has(textarea)") || src.contains("[contenteditable"),
        )
        // (d) 样式真的被安装了，且在 readyState==='loading' 分支里也装（两条路径都要装，
        //     否则冷启动（文档开始注册脚本时 readyState 就是 'loading'）会整个丢掉修法）。
        val installCalls = Regex("""^\s*installImeLiftStyle\(\);""", RegexOption.MULTILINE)
            .findAll(src).count()
        assertTrue(
            "installImeLiftStyle() 应在 readyState 的两条分支上各调一次（实测 $installCalls 次）",
            installCalls >= 2,
        )
    }

    @Test
    fun `facade 仍保留 insets 变量注入 不得被修法挤掉`() {
        val src = facade()
        // 修法是在既有机制上加的，不许替换掉原有变量通道。
        listOf("--oc-ime-bottom", "--oc-safe-bottom", "--oc-window-height", "applyInsets")
            .forEach { key ->
                assertTrue("注入层丢了既有通道 $key", src.contains(key))
            }
    }
}
