package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.settings.OcSelectContainerBounds
import com.xiufeigo.opencodeshell.ui.settings.ocSelectListDesiredHeightDp
import com.xiufeigo.opencodeshell.ui.settings.ocSelectListMaxHeightDp
import com.xiufeigo.opencodeshell.ui.settings.ocSelectShouldFlipUp
import com.xiufeigo.opencodeshell.ui.settings.ocSelectSpaceForSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v54 真机批（桌面 tB）：General → Language 的 `OcSelect` 首帧又被裁
 * (`research/v54_verify/tB/tB_42_ocselect_lang_open.png` /
 * `tB_44_lang_scrolled.png` / `tB_45_lang_scrolled2.png`)。
 *
 * 现象：下拉只露「English」一行、不自动上翻，把父容器手动滚一遍才看得见别的
 * 选项。同一批里设置正文也被窗口底边切掉且没有任何提示
 * (`tB_87_appearance_scrolled.png` / `tB_10_about.png`)。
 *
 * 原因：应用是 edge-to-edge（`OcActivity`），`PanelSlot` 报给
 * `LocalOcSelectContainerBounds` 的那块盒子在桌面/分屏窗口里**一直伸到系统
 * 导航栏底下**，它的下边并不是用户能看到的最后一行。于是每个 select 都读到
 * 「下面还有空」，向下展开、限高也按那块看不见的空间算——限高兜底是对的
 * （列表确实限高且内部可滚），但它限到的地方本身就是被导航栏盖住的，滚出来
 * 还是看不见，于是「只露一行 + 不上翻」。正文被切是同一个洞：盒子伸进导航栏，
 * 最后一行落在导航栏底下，滚到底也没有余量。
 *
 * 修法：`PanelSlot` 的盒子先 `.windowInsetsPadding(WindowInsets.navigationBars)`
 * 再 `onGloballyPositioned` —— padding 在外，所以位置节点量到的是**补过 inset
 * 之后真正可见**的盒子，翻转与限高都对它算；正文那一侧则由
 * `SettingsPanelRows` 的滚动内容补一段底部内边距，滚到底时最后一行能离开裁切边。
 * 窗口本来就排除导航栏时 inset 为 0，数字与之前逐字节相同。
 */
class OcSelectInsetBoundsTest {

    private fun source(relative: String): String {
        val file = File("src/main/java/com/xiufeigo/opencodeshell/$relative")
            .takeIf { it.isFile }
            ?: File("app/src/main/java/com/xiufeigo/opencodeshell/$relative")
        assertTrue("missing source: $relative", file.isFile)
        return file.readText()
    }

    private fun screen() = source("ui/settings/SettingsScreen.kt")
    private fun panels() = source("ui/settings/SettingsPanels.kt")

    // ---- the bounds provider -------------------------------------------------

    @Test
    fun thePanelSlotClearsTheNavigationBarBeforeItReportsItsBox() {
        val body = screen()
        val slot = body.substringAfter("private fun PanelSlot(").substringBefore("SettingsTab.GENERAL ->")
        assertTrue(
            "面板 slot 必须先把系统导航栏 inset 吃掉",
            slot.contains("windowInsetsPadding(WindowInsets.navigationBars)"),
        )
    }

    @Test
    fun theInsetIsOutsideThePositionNodeSoTheReportedBoxIsTheVisibleOne() {
        // 顺序反了的话 onGloballyPositioned 量到的是没补 inset 的整块盒子，
        // 翻转/限高继续按导航栏底下那一截算 —— 正是 v54 的现象。
        val body = screen()
        val slot = body.substringAfter("private fun PanelSlot(").substringBefore("SettingsTab.GENERAL ->")
        val insetAt = slot.indexOf("windowInsetsPadding(WindowInsets.navigationBars)")
        val positionAt = slot.indexOf(".onGloballyPositioned {")
        assertTrue("面板 slot 里找不到 inset / 位置节点", insetAt >= 0 && positionAt >= 0)
        assertTrue(
            "inset padding 必须在 onGloballyPositioned 之外，否则量到的还是被导航栏盖住的盒子",
            insetAt < positionAt,
        )
    }

    @Test
    fun theContainerIsStillProvidedToThePanels() {
        val body = screen()
        assertTrue(body.contains("LocalOcSelectContainerBounds provides containerBounds"))
        assertTrue(body.contains("OcSelectContainerBounds("))
    }

    // ---- what the corrected bounds decide -------------------------------------

    @Test
    fun aTriggerPinnedUnderTheNavigationBarFlipsInsteadOfOpeningDown() {
        // tB_42 的形状：容器可见底边就是导航栏上沿，Language 的触发器只剩
        // 十几 dp，下面放不下 4 个语言，向上却还有 32 dp。
        val visible = OcSelectContainerBounds(topDp = 120f, bottomDp = 190f)
        val list = ocSelectListDesiredHeightDp(4)
        assertTrue("fixture 必须放不下", visible.bottomDp - 178f < list)
        assertTrue(
            ocSelectShouldFlipUp(
                triggerBottomDp = 178f,
                listHeightDp = list,
                windowHeightDp = 311f,
                triggerTopDp = 152f,
                containerTopDp = visible.topDp,
                containerBottomDp = visible.bottomDp,
            ),
        )
    }

    @Test
    fun theCapKeepsTheWholeListOffTheNavigationBar() {
        // 兜底「限高 + 内部可滚」在新的边界上照旧成立：可见下方只剩 12 dp，
        // 列表必须从 wanted（4 个语言 156 dp）被压成一只可滚的小盒子，而不是
        // 伸到导航栏底下 —— tB_42 那条「只露一行」就是这么来的。
        val visible = OcSelectContainerBounds(topDp = 120f, bottomDp = 190f)
        val below = ocSelectSpaceForSide(152f, 178f, 311f, visible, flipUp = false)
        assertEquals(12f, below, 0.001f)
        val wanted = ocSelectListDesiredHeightDp(4)
        val capped = ocSelectListMaxHeightDp(4, 311f, spaceAvailableDp = below)
        assertTrue("限高没有生效：$capped 不小于 wanted $wanted", capped < wanted)
        assertTrue(
            "没有空间时列表会退回到最小可点高度（44 dp），这是有意的下限，不是 bug",
            capped <= 44f,
        )
    }

    @Test
    fun aSelectWithRoomStillOpensDownWithTheCorrectedBounds() {
        // 补 inset 不能把「下面够用」的 select 也翻上去。
        val visible = OcSelectContainerBounds(topDp = 120f, bottomDp = 460f)
        val list = ocSelectListDesiredHeightDp(3)
        assertEquals(
            list,
            ocSelectListMaxHeightDp(
                3,
                311f,
                spaceAvailableDp = ocSelectSpaceForSide(150f, 180f, 311f, visible, flipUp = false),
            ),
            0.001f,
        )
        assertTrue(
            !ocSelectShouldFlipUp(
                triggerBottomDp = 180f,
                listHeightDp = list,
                windowHeightDp = 311f,
                triggerTopDp = 150f,
                containerTopDp = visible.topDp,
                containerBottomDp = visible.bottomDp,
            ),
        )
    }

    // ---- the settings body --------------------------------------------------

    @Test
    fun theRowPanelScrollKeepsTrailingRoomForItsLastRow() {
        // tB_87 / tB_10：`SettingsPanelBody` 一直有 `padding(0 40 40)`，行页面
        // （General / Appearance / Notifications）自己的 scroller 却没有，于是
        // 最后一行齐着裁切边，看起来像被切掉一半。
        val scroller = panels().substringAfter("val scrollerModifier = Modifier")
            .substringBefore("LaunchedEffect(jumpAction)")
        assertTrue("行页面滚动体缺少底部余量", scroller.contains(".padding(bottom = 40.dp)"))
    }

    @Test
    fun thatTrailingRoomIsInsideTheScrollSoItCanBeScrolledTo() {
        // padding 必须在 verticalScroll **之后**：在之前只是把视口缩小，最后
        // 一行照样贴着裁切边，滚到底也没有余量。
        val scroller = panels().substringAfter("val scrollerModifier = Modifier")
            .substringBefore("LaunchedEffect(jumpAction)")
        val scrollAt = scroller.indexOf(".verticalScroll(scrollState)")
        val paddingAt = scroller.indexOf(".padding(bottom = 40.dp)")
        assertTrue("找不到 verticalScroll / 底部 padding", scrollAt >= 0 && paddingAt >= 0)
        assertTrue("底部余量必须落在滚动内容里", scrollAt < paddingAt)
    }

    @Test
    fun theRowJumpStillMeasuresTheScrollerItself() {
        // 搜索跳行靠 `contentTop`，底部 padding 不许挪动它量的那个盒子。
        val scroller = panels().substringAfter("val scrollerModifier = Modifier")
            .substringBefore("LaunchedEffect(jumpAction)")
        assertTrue(
            scroller.contains(
                ".onGloballyPositioned { contentTop = it.positionInWindow().y.roundToInt() }",
            ),
        )
    }
}
