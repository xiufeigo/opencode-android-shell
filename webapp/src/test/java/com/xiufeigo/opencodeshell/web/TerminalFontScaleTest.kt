package com.xiufeigo.opencodeshell.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * OC-TERMINAL 绊线：**窄屏终端的列数**（手机竖屏"显示太诡异"）。
 *
 * ## 现象与根因（2026-10-07 产品负责人报单 + 同机型设备实测）
 *
 * 竖屏终端里 PowerShell 的表格被压成"一列一个字符"。设备实测（411×914 CSS，dpr 2.625）：
 * 面板 clientWidth 394（左右各 14px padding）⇒ 内容 366px；画布 CSS 宽 343.6px；
 * `IBM Plex Mono` 14px 单元格 8.4px ⇒ **41 列**。根因是上游把终端字号**写死 14px**
 * （`new Terminal({ … fontSize: 14, fontFamily: r.font('mono') … })`），且没有字号设置项。
 *
 * ## 修法（本类钉住的不变量）
 *
 * 不动 CSS 缩放（`zoom` 会让控件的指针数学差一个缩放倍率 —— 设备实测：点在视觉 x=299.8
 * 处被算成第 33.4 格，真实是第 44.6 格），而是**在 CSS 像素空间里把度量同比缩小**：
 *   ① `CanvasRenderingContext2D.measureText`：终端量单元格宽高时按比例缩小返回值
 *      ⇒ 控件自己算出更多列；
 *   ② `ctx.font` setter：只在**终端面板内**的画布上把 px 缩小 ⇒ 字形与单元格同步变小。
 * 两者同处一个坐标系 ⇒ `metrics` / `getBoundingClientRect` / `offsetX` 全部自洽，
 * 点按、拖选、滚动条命中都不偏移。
 *
 * ## 反向断言（防两种真实踩过的坑）
 *
 * · **不许拿完整字体栈比对**：控件用的是上游设置里的"代码字体"
 *   （默认 `JetBrainsMono Nerd Font Mono`），面板 CSS 是 `"IBM Plex Mono", …` —— 首选项不同、
 *   尾巴相同。整串比对 ⇒ 量字不命中而画字照缩 ⇒ 字距被拉开（2026-10-07 实测踩到）。
 * · **不许在非终端字体上生效**：必须显式判字体栈尾巴，不命中就原样返回。
 */
class TerminalFontScaleTest {

    private fun read(relative: String): String {
        val f = File(relative)
        assertTrue("找不到 $relative（单测工作目录应为 webapp/ 模块目录）", f.isFile)
        return f.readText()
    }

    private val js: String by lazy { read("src/main/assets/www/oc-host.js") }

    private val block: String by lazy {
        val idx = js.indexOf("OC-TERMINAL")
        assertTrue("oc-host.js 里找不到 OC-TERMINAL 段（窄屏终端列数）", idx >= 0)
        js.substring(idx)
    }

    private val code: String by lazy {
        block.lineSequence().map { it.substringBefore("//") }.joinToString("\n")
    }

    @Test
    fun scalesTerminalMetricsOnlyOnNarrowScreens() {
        assertTrue("必须有缩放常量 OC_TERM_FONT_SCALE", code.contains("OC_TERM_FONT_SCALE"))
        assertTrue(
            "缩放常量必须是一个 (0,1) 的系数（否则列数不会变多 / 会画出格）",
            Regex("""OC_TERM_FONT_SCALE\s*=\s*0\.\d+""").containsMatchIn(code),
        )
        assertTrue(
            "只在窄屏生效（与上游窄屏断点一致）",
            code.contains("\"(max-width: 767px)\"") && code.contains(".matches"),
        )
        // 宽屏直接原样返回，一个像素都不动。
        assertTrue(
            "宽屏必须原样返回原始度量",
            code.contains("if (!window.matchMedia || !window.matchMedia(\"(max-width: 767px)\").matches) return m"),
        )
    }

    @Test
    fun patchesBothMeasurementAndGlyphDrawing() {
        assertTrue(
            "必须包装 CanvasRenderingContext2D.prototype.measureText（列数的来源）",
            code.contains("CanvasRenderingContext2D") && code.contains("proto.measureText = function"),
        )
        assertTrue(
            "必须包装 ctx.font 的 setter（字形大小）",
            code.contains("Object.getOwnPropertyDescriptor(proto, \"font\")") && code.contains("Object.defineProperty(proto, \"font\""),
        )
        assertTrue(
            "画字形只允许在终端面板内生效",
            code.contains("function ocCtxInTerminal") && code.contains("[data-component=\"terminal\"]"),
        )
        assertTrue("必须幂等（重复注入不得二次包装）", code.contains("__ocTermScale"))
        assertTrue(
            "宽度与行高（ascent/descent）必须同比缩放 —— 只缩宽度会让行列比例失真",
            code.contains("width: m.width * z") && code.contains("fontBoundingBoxAscent") && code.contains("fontBoundingBoxDescent"),
        )
    }

    @Test
    fun measurementIsScopedByFontStackTailNotWholeStack() {
        // 🔴 实测踩过的坑：控件字体族（JetBrainsMono Nerd Font Mono）≠ 面板 CSS 字体族（IBM Plex Mono）。
        assertTrue("必须提供「取字体栈尾巴」的函数", code.contains("function ocTerminalFontTail"))
        assertTrue(
            "尾巴必须从第一个逗号之后取（首选项不可比）",
            code.contains("indexOf(\",\")") && code.contains("slice(i + 1)"),
        )
        assertTrue(
            "量字必须用尾巴判据，且不命中就原样返回",
            code.contains("var tail = ocTerminalFontTail()") &&
                code.contains("if (!tail) return m") &&
                code.contains("font.indexOf(tail) < 0"),
        )
        assertFalse(
            "不得改用完整字体栈比对（首选项不同 ⇒ 量字不命中、画字照缩 = 字距被拉开）",
            code.contains("font.indexOf(fam)"),
        )
    }

    @Test
    fun degradesSafelyWhenUpstreamChangesShape() {
        // 上游若换掉 measureText/ctx.font 的用法，补丁必须"自然失效"（= 回到 41 列），而不是画歪。
        assertTrue("量字出错必须回退原值", code.contains("} catch (e) {\n            return m;\n          }"))
        assertTrue("画字出错必须回退原值", code.contains("desc.set.call(this, v)"))
        assertTrue(
            "整体安装必须包在 try/catch 里（canvas API 不可用时不崩）",
            code.contains("function installTerminalFontScale()") && code.contains("catch (e) {}"),
        )
        assertTrue("必须在文档开始安装（终端控件是懒加载的）", code.contains("installTerminalFontScale();"))
    }
}
