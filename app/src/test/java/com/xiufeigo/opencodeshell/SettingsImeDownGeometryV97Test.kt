package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.settings.SETTINGS_CARD_GUTTER_DP
import com.xiufeigo.opencodeshell.ui.settings.SETTINGS_CARD_MAX_HEIGHT_DP
import com.xiufeigo.opencodeshell.ui.settings.SETTINGS_CARD_MIN_HEIGHT_DP
import com.xiufeigo.opencodeshell.ui.settings.settingsDialogCardHeightDp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v97 — 「设置弹层 IME-down 几何」定性钉。
 *
 * 现象（`research/v97_verify/5556_v82/REPORT.md` §S3，5556 = Medium_Desktop，
 * 1366x768@160dpi，键盘落下）：卡片 a11y 盒 `[193,94][1173,674]` = **高 580、顶边 94**
 * （像素探针白色面 95..671），而 `research/HANDOFF.md:71` 记的 v82 历史值是
 * **高 600 / 顶边 84**。任务书怀疑「v82 把 `windowHeightDp` 换成了扣掉桌面 shell 条
 * 后的高度（672）」。
 *
 * **裁定：不是 v82 引入的回归**，本类把裁定钉死：
 *
 * ① v82 之前的取值就是同一份源码。git `HEAD`（`a5322cf`，v82 之前的工作树）
 *    `SettingsScreen.kt:192/196`：`minOf(config.screenHeightDp.dp - 92.dp, 600.dp)`
 *    + `.height(maxOf(height, 240.dp))`——**同一个 `config.screenHeightDp`**、同一条
 *    链（`width → height → background → border`，无任何 padding）。今天的
 *    `BoxWithCappedSize` 只多了 `.then(if (imeUp) … else Modifier)`，键盘落下时它
 *    是 `Modifier`（恒等），而纯函数在 `imeBottomDp = 0` 时**逐值等于**旧表达式
 *    （`imeDown` 用例把旧表达式原样抄进测试做全窗扫描）。
 * ② 20 dp 的落差来自**设备环境**：同一台 5556、同一 `wm size/density` 读数下，
 *    v82 那轮的底部系统条实测 **y=720..767（48 px）**，本轮实测
 *    **y=672..767（96 px）**（两轮截图各自的像素探针，见
 *    `research/v97_fix/SETTINGS_IME_DOWN_GEOMETRY.md`）。`Configuration.screenHeightDp`
 *    随之从 720 变成 672，而 `min(720−92, 600) = 600` 仍被 600 上限吃掉、
 *    `min(672−92, 600) = 580` 露出来——跨越阈值 `w − 92 < 600 ⇔ w < 692`。
 *    卡片由 `DialogHost` 在**整窗 768** 里居中，所以高度少 20 dp 只会让顶边
 *    下移一半 = 10 px：384−290 = 94（旧：384−300 = 84）。
 * ③ 上游对照：`_upstream/packages/ui/src/overlays/dialog/dialog.css:178`
 *    `height: min(calc(100vh - 92px), 600px)`，其 `100vh` 是**整个视口**。本壳用
 *    `LocalConfiguration.screenHeightDp`（显示高**减去**系统条）代理 `100vh`，
 *    在 96 px 系统条的窗口里天然短 96 dp——**这是 v47/v48 以来的代理口径**，不是
 *    v82 引入的，且与「键盘落下时 IME 链条不存在」无关（本类 `theImeDownChainAddsNoPadding` 钉住）。
 */
class SettingsImeDownGeometryV97Test {

    private fun source(relative: String): String =
        listOf("app/$relative", relative)
            .map { File(it) }
            .firstOrNull { it.isFile }
            ?.readText()?.replace("\r\n", "\n")
            ?: error("missing $relative")

    private fun settingsScreen() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/settings/SettingsScreen.kt")

    /** `BoxWithCappedSize`（卡片容器）那段源码。 */
    private fun cardWindow(): String {
        val screen = settingsScreen()
        val start = screen.indexOf("private fun BoxWithCappedSize(")
        assertTrue("BoxWithCappedSize must exist", start >= 0)
        val end = screen.indexOf("private fun SettingsNavList(", start)
        assertTrue("missing the nav after the card", end > start)
        return screen.substring(start, end)
    }

    /**
     * v82 **之前**的取值，逐字抄自 git `HEAD`（`a5322cf`）
     * `SettingsScreen.kt:192,196`——本测试自己持有这份实现，才能证明重构是恒等变换，
     * 而不是靠注释自证。
     */
    private fun preV82CardHeightDp(windowHeightDp: Float): Float =
        maxOf(minOf(windowHeightDp - SETTINGS_CARD_GUTTER_DP, SETTINGS_CARD_MAX_HEIGHT_DP), 240f)

    // ------------------------------------------------------ Q1：公式与输入来源

    @Test
    fun imeDownIsThePreV82ExpressionForEveryWindow() {
        // 键盘落下时 `imeBottomDp = 0`：`min(w − 92, 600, w)` 的第三项恒 ≥ 前者
        // （w − 92 ≤ w），故新纯函数与旧表达式**逐值恒等**。全窗扫描钉死这一点。
        for (window in 0..1600) {
            val w = window.toFloat()
            assertEquals(
                "window=$w: the IME-down branch must be the pre-v82 expression",
                preV82CardHeightDp(w),
                settingsDialogCardHeightDp(windowHeightDp = w, imeBottomDp = 0f),
                0f,
            )
        }
    }

    @Test
    fun imeDownAtTheUpstreamCapAndAtTheMeasuredWindow() {
        // 1366x768@160 且系统条 0/小：上游上限 600（`min(768 − 92, 600)`）——HANDOFF:71
        // 记的「高 600 / 顶边 84」就是这一支。
        assertEquals(600f, settingsDialogCardHeightDp(windowHeightDp = 768f))
        // v82 那轮的实测环境：768 − 48(系统条) = 720 → 仍然被 600 上限吃掉。
        assertEquals(600f, settingsDialogCardHeightDp(windowHeightDp = 720f))
        // 本轮实测环境：768 − 96(系统条) = 672 → 580，正是 5556 键盘落下态的 a11y 高
        // （`ui_V82_S3_01_settings_kbd_down.xml` 卡片节点 `[193,94][1173,674]`）。
        assertEquals(580f, settingsDialogCardHeightDp(windowHeightDp = 672f))
        // 阈值：w − 92 < 600 ⇔ w < 692。48 px 系统条（720）在阈上、96 px（672）在阈下，
        // 这就是两轮差 20 dp 的全部原因。
        assertEquals(600f, settingsDialogCardHeightDp(windowHeightDp = 692f))
        assertEquals(599f, settingsDialogCardHeightDp(windowHeightDp = 691f))
        assertEquals(280f, settingsDialogCardHeightDp(windowHeightDp = 372f))
        // 短窗仍按 gutter 缩、地板仍是 240（`SettingsPanelLayout` 的 0/负窗口径不变）。
        assertEquals(308f, settingsDialogCardHeightDp(windowHeightDp = 400f))
        assertEquals(SETTINGS_CARD_MIN_HEIGHT_DP, settingsDialogCardHeightDp(windowHeightDp = 200f))
        assertEquals(SETTINGS_CARD_MIN_HEIGHT_DP, settingsDialogCardHeightDp(windowHeightDp = 0f))
    }

    @Test
    fun theKeyboardStateStillTakesTheBandAboveTheKeyboard() {
        // 5556 本轮键盘上沿 y=284 → inset 768 − 284 = 484，可视带 284。
        assertEquals(284f, settingsDialogCardHeightDp(windowHeightDp = 768f, imeBottomDp = 484f))
        // 任务书指定的 (768, 284)：284 px 键盘 → 可见 484，仍被 600 上限吃住的一半之下。
        assertEquals(484f, settingsDialogCardHeightDp(windowHeightDp = 768f, imeBottomDp = 284f))
        // v82 测试员当年在 5558 量的 426 px 键盘 → 342；200 px → 568；键盘盖到顶 → 240 地板。
        assertEquals(342f, settingsDialogCardHeightDp(windowHeightDp = 768f, imeBottomDp = 426f))
        assertEquals(568f, settingsDialogCardHeightDp(windowHeightDp = 768f, imeBottomDp = 200f))
        assertEquals(SETTINGS_CARD_MIN_HEIGHT_DP, settingsDialogCardHeightDp(windowHeightDp = 768f, imeBottomDp = 700f))
        // 键盘态是 IME-down 的**唯一**例外：同一窗口下 0 与 484 必须给出两个值，
        // 否则「给键盘让位」这条 v82 承诺就没了。
        assertTrue(
            settingsDialogCardHeightDp(windowHeightDp = 768f, imeBottomDp = 484f) <
                settingsDialogCardHeightDp(windowHeightDp = 768f),
        )
    }

    // -------------------------------------------- 调用点：高度来源是哪一个窗口量

    @Test
    fun theCallSiteHandsTheHelperTheConfigurationWindowHeight() {
        val window = cardWindow()
        // 来源就是 `LocalConfiguration.current.screenHeightDp`——**没有**在 v82 里被换成
        // 任何「扣掉 shell 条」的自算高度：全文件没有 BoxWithConstraints / maxHeight /
        // LocalView / WindowMetrics / rootView 这类另一个窗口量。
        assertTrue(window.contains("val config = LocalConfiguration.current"))
        assertTrue(window.contains("windowHeightDp = config.screenHeightDp.toFloat()"))
        assertTrue(window.contains("imeBottomDp = imeBottom.value"))
        for (forbidden in listOf("BoxWithConstraints", "constraints.maxHeight", "LocalView", "WindowMetrics", "rootView")) {
            assertFalse(
                "the card must not read a second window height ($forbidden)",
                window.contains(forbidden),
            )
        }
        // 宽度仍是同一份 config 的一对读数（`min(100vw − 32, 980)`）——高度与宽度同源，
        // 说明 v82 改的是「高度怎么用 IME」，不是「窗口量从哪来」。
        assertTrue(window.contains("val width = minOf(config.screenWidthDp.dp - 32.dp, 980.dp)"))
    }

    @Test
    fun theImeDownChainAddsNoPadding() {
        // v82 承诺「键盘落下时链条不存在」：`.then(if (imeUp) …)` 的 else 分支必须是空
        // `Modifier`，而 `.height(height)` 前面不许长出别的 padding。
        val window = cardWindow()
        assertTrue(window.contains("val imeUp = imeBottom > 0.dp"))
        assertTrue(window.contains("if (imeUp) {"))
        assertTrue(window.contains("} else {"))
        val chain = window.substringAfter("Box(").substringBefore(") { content() }")
        val elseBranch = chain.substringAfter("} else {")
        assertFalse(
            "the IME-down branch must stay a no-op",
            elseBranch.contains("Padding") || elseBranch.contains("padding("),
        )
        assertTrue("the else branch must hand back a bare Modifier", elseBranch.contains("Modifier"))
        // 固定高（不是 `heightIn`）：KDoc 记的「a max-only constraint would let the shell
        // shrink to its intrinsic content」，这条契约不许被悄悄换成上限式。
        assertTrue(window.contains(".height(height)"))
        assertFalse(window.contains(".heightIn("))
        // IME 抬升链在 `.height` 之外（v82 的既有钉，见 SettingsDialogImeInsetV82Test）。
        val lift = window.indexOf(".imePadding()")
        val height = window.indexOf(".height(height)")
        assertTrue("the IME chain must precede .height(height)", lift in 1 until height)
    }

    @Test
    fun theServersDialogsReadTheSameWindowHeightSoTheCardIsNotTheOddOneOut() {
        // 「IME-down 与 IME-up 用同一个高度来源会不会互相牵连」的另一半：同域的二级弹层
        // （`.heightIn(max = band)` 那一族）也吃 `config.screenHeightDp.toFloat()`。
        // 若将来把来源换成整窗高度，这三处必须一起换，否则一级卡与二级表单会各算各的。
        val panel = source("src/main/java/com/xiufeigo/opencodeshell/ui/settings/ServersPanel.kt")
        assertTrue(panel.contains("val windowHeightDp = config.screenHeightDp.toFloat()"))
        assertTrue(panel.contains("serverDialogBandDp("))
    }
}
