package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.settings.OcSelectContainerBounds
import com.xiufeigo.opencodeshell.ui.settings.ocSelectListDesiredHeightDp
import com.xiufeigo.opencodeshell.ui.settings.ocSelectListMaxHeightDp
import com.xiufeigo.opencodeshell.ui.settings.ocSelectShouldFlipUp
import com.xiufeigo.opencodeshell.ui.settings.ocSelectSpaceForSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v46 真机批：设置对话框里的 `OcSelect` 翻转认错了边界
 * (`research/v46_verify/tE/tE_05_termfont_first.png` /
 * `tE_06_tf_settled.png` / `tE_07_tf_scrolled.png`)。
 *
 * 现象：Appearance 页滚到底点 Terminal Font，弹层首帧就被对话框底边裁
 * （首项露一半、第二项完全不可见）、静置 2s 不自愈、上滑还穿透到底层页面
 * （把页面推走才"意外"露出选项）。反证是同一个组件在会话页模型选择器靠近
 * **屏幕**底边时正确上翻（`tE_26_modeldd.png` / `tE_30_dd2.png`）——翻转逻辑
 * 存在，错的是碰撞边界：v43 的规则拿触发器底边到**窗口**底边的距离当"下面
 * 的 room"，而宽窗口下对话框卡片底边比屏幕底边高几百 dp，"下面"看起来很空，
 * 列表就向下开进了卡片之外。
 *
 * 修法：边界取对话框内容区（`PanelSlot` 通过
 * `LocalOcSelectContainerBounds` 提供自己的可见框），翻转和限高都对它算；
 * 没有 provider 的宿主（会话页各选择器）走完全一样的旧窗口数学，行为不变。
 */
class OcSelectDialogBoundsTest {

    // A wide window like the v46 capture: the card ends far above the screen
    // edge, so the window math sees room where the card has none.
    private val window = 800f
    private val card = OcSelectContainerBounds(topDp = 100f, bottomDp = 700f)

    // Terminal Font trigger parked 40 dp above the card's bottom edge, the
    // v46 pose. Three options want 8 + 3 * 37 = 119 dp.
    private val triggerTop = 624f
    private val triggerBottom = 660f
    private val list3 = ocSelectListDesiredHeightDp(3)

    private fun flip(
        triggerBottomDp: Float = triggerBottom,
        listHeightDp: Float = list3,
        windowHeightDp: Float = window,
        triggerTopDp: Float = triggerTop,
        container: OcSelectContainerBounds? = card,
    ): Boolean = ocSelectShouldFlipUp(
        triggerBottomDp,
        listHeightDp,
        windowHeightDp,
        triggerTopDp = triggerTopDp,
        containerTopDp = container?.topDp,
        containerBottomDp = container?.bottomDp,
    )

    @Test
    fun theV46PoseFlipsAgainstTheCardButNotAgainstTheWindow() {
        // Window math (the v43/v45 rule): 140 dp below holds 119 dp, so it
        // opens down — straight under the card edge. This is the bug.
        assertFalse(ocSelectShouldFlipUp(triggerBottom, list3, window))
        // Card math: 40 dp below cannot hold 119 dp, 524 dp above can.
        assertTrue(flip())
    }

    @Test
    fun aRowThatFitsBelowInTheCardKeepsOpeningDown() {
        assertFalse(flip(triggerBottomDp = 400f, triggerTopDp = 364f))
    }

    @Test
    fun aTallListInTheMiddleOfTheCardKeepsOpeningDown() {
        // 12 options (452 dp) under a trigger at 350 dp: 350 dp below is past
        // the last-row band, so the list opens down and the host scroller
        // follows — flipping here would cover the row's copy for no gain.
        val list = ocSelectListDesiredHeightDp(12)
        assertTrue("fixture must not fit below", card.bottomDp - 350f < list)
        assertFalse(flip(triggerBottomDp = 350f, triggerTopDp = 314f, listHeightDp = list))
    }

    @Test
    fun whenNeitherSideHoldsTheRoomierSideWins() {
        // 20 options (748 dp) on the last row: below has 50 dp, above 514 dp.
        val list = ocSelectListDesiredHeightDp(20)
        assertTrue(flip(triggerBottomDp = 650f, triggerTopDp = 614f, listHeightDp = list))
    }

    @Test
    fun noRoomAboveMeansNoFlip() {
        // Synthetic guard pin: the trigger sits at the container's own top, so
        // above (0 dp) loses to below even though below cannot hold the list.
        assertFalse(flip(triggerBottomDp = 640f, triggerTopDp = 100f, listHeightDp = ocSelectListDesiredHeightDp(20)))
    }

    @Test
    fun aDegenerateContainerFallsBackToTheWindowMath() {
        val flat = OcSelectContainerBounds(topDp = 700f, bottomDp = 700f)
        assertEquals(
            ocSelectShouldFlipUp(triggerBottom, list3, window),
            flip(container = flat),
        )
        val inverted = OcSelectContainerBounds(topDp = 700f, bottomDp = 100f)
        assertEquals(
            ocSelectShouldFlipUp(triggerBottom, list3, window),
            flip(container = inverted),
        )
    }

    @Test
    fun aZeroWindowNeverFlipsEvenWithAContainer() {
        assertFalse(flip(windowHeightDp = 0f))
    }

    // ---- the side space --------------------------------------------------------

    @Test
    fun spaceWithoutAContainerIsTheWindowRoomOnThatSide() {
        // v47 (`research/v47_verify/tE/tE_11_tf_clean.png`): the fallback used
        // to be the whole window, so a short list rendered full-height past
        // the card edge with no scroll room. It is now the window room on the
        // side that won — below sees `window − triggerBottom`, above sees
        // `triggerTop`. A list that fits keeps the old cap (room ≥ wanted ⇒
        // cap == wanted); one that does not becomes a bounded scroller.
        assertEquals(
            window - triggerBottom,
            ocSelectSpaceForSide(triggerTop, triggerBottom, window, null, flipUp = false),
            0.001f,
        )
        assertEquals(
            triggerTop,
            ocSelectSpaceForSide(triggerTop, triggerBottom, window, null, flipUp = true),
            0.001f,
        )
    }

    @Test
    fun spaceWithAContainerIsTheVisibleStretchOnThatSide() {
        assertEquals(40f, ocSelectSpaceForSide(triggerTop, triggerBottom, window, card, flipUp = false), 0.001f)
        assertEquals(524f, ocSelectSpaceForSide(triggerTop, triggerBottom, window, card, flipUp = true), 0.001f)
    }

    @Test
    fun spaceNeverGoesNegative() {
        val past = OcSelectContainerBounds(topDp = 100f, bottomDp = 500f)
        assertEquals(0f, ocSelectSpaceForSide(600f, 636f, window, past, flipUp = false), 0.001f)
        assertEquals(0f, ocSelectSpaceForSide(10f, 46f, window, past, flipUp = true), 0.001f)
    }

    // ---- the cap ----------------------------------------------------------------

    @Test
    fun theCappedListFitsInsideTheContainerSideThatWon() {
        // The v46 guarantee: with the side's visible room as the cap, the list
        // cannot outgrow it, so the card never cuts it and the list's own
        // scroller reaches every option.
        val cappedDown = ocSelectListMaxHeightDp(3, window, spaceAvailableDp = 40f)
        assertTrue("capped list $cappedDown escapes its 40 dp side", cappedDown <= 44f)
        val cappedUp = ocSelectListMaxHeightDp(3, window, spaceAvailableDp = 524f)
        assertEquals(list3, cappedUp, 0.001f)
    }

    // ---- the composable contract --------------------------------------------------

    private fun file(name: String): String {
        val file = File("src/main/java/com/xiufeigo/opencodeshell/ui/settings/$name")
            .takeIf { it.isFile }
            ?: File("app/src/main/java/com/xiufeigo/opencodeshell/ui/settings/$name")
        assertTrue("missing source: $name", file.isFile)
        return file.readText()
    }

    @Test
    fun theSelectReadsTheProvidedContainer() {
        val body = file("SettingsComponents.kt")
        assertTrue(body.contains("val container = LocalOcSelectContainerBounds.current"))
        assertTrue(body.contains("triggerTopDp = top"))
    }

    @Test
    fun eachSideIsCappedAgainstItsOwnRoom() {
        val body = file("SettingsComponents.kt")
        assertTrue(body.contains("spaceAvailableDp = spaceAboveDp"))
        assertTrue(body.contains("spaceAvailableDp = spaceBelowDp"))
    }

    @Test
    fun theDialogSlotProvidesItsVisibleBox() {
        val body = file("SettingsScreen.kt")
        assertTrue(body.contains("LocalOcSelectContainerBounds provides containerBounds"))
        assertTrue(body.contains("OcSelectContainerBounds("))
    }
}
