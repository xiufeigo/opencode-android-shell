package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.settings.ocSelectHasTriggerBottom
import com.xiufeigo.opencodeshell.ui.settings.ocSelectListDesiredHeightDp
import com.xiufeigo.opencodeshell.ui.settings.ocSelectListMaxHeightDp
import com.xiufeigo.opencodeshell.ui.settings.ocSelectShouldFlipUp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v43 真机批：设置里最后一行的 `OcSelect` 内联下拉被对话框底边硬裁
 * (`research/v43_verify/tE/v43_05_lastrow_dropdown.png` /
 * `v43_06_dropdown_scroll.png` — Terminal Font 弹层只露半行、之后的选项完全
 * 不可达，弹层既不滚也不翻转)。
 *
 * 现象：v40 把展开项从 `DropdownMenu` 窗口改成内联 `Column`（见
 * `OcSelectInlineMenuTest`），修好了「画到卡片外」。但内联列表只会**向下**展开并
 * 长到自己的全高；当它在某个 section 的**最后一行**打开时，宿主 scroller 已经滚到
 * 底，多出来的那截被对话框卡片直接裁掉。
 *
 * 修法：展开方向按触发器在窗口中的位置决定（下面放不下、上面放得下就向上翻），
 * 列表体本身有高度上限并可竖向滚动，两条腿保证每个选项都可达。仍是内联布局，
 * 不开第二个窗口。
 *
 * v44 追加：翻转谓词在**首帧**还不生效——第一次展开可能发生在
 * `onGloballyPositioned` 把位置报回来之前，`0` 读作「下面整个窗口都空着」，
 * 于是首帧仍向下展开、被卡片底边切掉末行（`research/v44_verify/tE/tE_27_fontdd.png`
 * / `tE_28_fontscroll.png`：uiautomator 末行 16px vs 其它 31px，滚动页面后才正常）。
 * 修法：测量回来之前**不画列表**，首帧就是已决定的那一侧，绝不出现「先裁一帧再好」。
 */
class OcSelectFlipUpTest {

    private fun components(): String {
        val file = File("src/main/java/com/xiufeigo/opencodeshell/ui/settings/SettingsComponents.kt")
            .takeIf { it.isFile }
            ?: File("app/src/main/java/com/xiufeigo/opencodeshell/ui/settings/SettingsComponents.kt")
        assertTrue("missing source: SettingsComponents.kt", file.isFile)
        return file.readText()
    }

    private fun window(text: String, from: String, to: String): String {
        val start = text.indexOf(from)
        assertTrue("$from is gone", start >= 0)
        val end = text.indexOf(to, start + from.length)
        assertTrue("$to is gone", end > start)
        return text.substring(start, end)
    }

    private fun ocSelect(): String = window(components(), "fun OcSelect(", "/** One option row:")

    // ---- the flip decision -------------------------------------------------

    @Test
    fun theLastRowOfTheDialogFlipsTheListUp() {
        // A 640 dp window, a trigger whose bottom is 560 dp down and a 5-option
        // list: 80 dp below is not enough, and 560 dp above is.
        val list = ocSelectListDesiredHeightDp(5)
        assertTrue(ocSelectShouldFlipUp(560f, list, 640f))
    }

    @Test
    fun aRowWithRoomBelowKeepsOpeningDown() {
        // The default stays the default: the inline list pushes the rows below
        // it down, and flipping up would cover the row's own copy column.
        val list = ocSelectListDesiredHeightDp(5)
        assertFalse(ocSelectShouldFlipUp(200f, list, 640f))
    }

    @Test
    fun aListTallerThanTheWholeWindowStillFlipsOnTheLastRow() {
        // 20 options: taller than the window. Down would be clipped, up at
        // least has the whole card to give — and the cap below takes it from
        // there.
        assertTrue(ocSelectShouldFlipUp(600f, ocSelectListDesiredHeightDp(20), 640f))
    }

    @Test
    fun aShortListOnTheLastRowDoesNotFlipForNothing() {
        // One option (45 dp) with 45 dp of room below it: it fits, so there is
        // no reason to cover the copy column above.
        assertFalse(ocSelectShouldFlipUp(595f, ocSelectListDesiredHeightDp(1), 640f))
    }

    @Test
    fun aTallListInTheMiddleOfThePageKeepsOpeningDown() {
        // 12 options (452 dp) do not fit under a trigger at 300 dp, but there
        // is still 340 dp — far more than the last-row band — so the list keeps
        // opening down and the host scroller follows it. Flipping here would
        // cover the row's own copy column for no gain.
        val list = ocSelectListDesiredHeightDp(12)
        assertTrue("this fixture is meant to be taller than the space below", 640f - 300f < list)
        assertFalse(ocSelectShouldFlipUp(300f, list, 640f))
    }

    @Test
    fun aLastRowWithALongListFlipsEvenWhenALittleRoomIsLeft() {
        // 20 options, trigger 100 dp from the bottom: 100 dp is inside the band,
        // so the list goes up rather than showing four options and hiding the
        // other sixteen.
        assertTrue(ocSelectShouldFlipUp(540f, ocSelectListDesiredHeightDp(20), 640f))
    }

    @Test
    fun anUnmeasuredTriggerStaysDownInsteadOfGuessing() {
        // First composition: the trigger has no window position yet, so 0 must
        // read as "top of the page", not "bottom of the page".
        assertFalse(ocSelectShouldFlipUp(0f, ocSelectListDesiredHeightDp(5), 640f))
    }

    @Test
    fun aZeroSizedWindowNeverFlips() {
        assertFalse(ocSelectShouldFlipUp(600f, ocSelectListDesiredHeightDp(5), 0f))
    }

    // ---- the first frame (v44) --------------------------------------------

    @Test
    fun anUnmeasuredTriggerIsNotYetUsable() {
        // v44: the first open could run before `onGloballyPositioned` reported
        // anything, and a 0 bottom reads as "the whole window is free below", so
        // the list opened down under the card's bottom edge and its last row was
        // sliced in half (`research/v44_verify/tE/tE_27_fontdd.png`). Until a
        // real measurement exists the list must not be placed at all.
        assertFalse(ocSelectHasTriggerBottom(0f))
    }

    @Test
    fun aMeasuredTriggerIsUsable() {
        assertTrue(ocSelectHasTriggerBottom(1f))
        assertTrue(ocSelectHasTriggerBottom(560f))
    }

    @Test
    fun theListWaitsForTheMeasurementInsteadOfPaintingAClippedFirstFrame() {
        val body = ocSelect()
        assertTrue(
            "the list must be gated on a completed trigger measurement",
            body.contains("val sideKnown = ocSelectHasTriggerBottom(triggerBottomDp)"),
        )
        // Both halves of the list go through the same gate, so the first frame
        // can never be the undecided "down" one.
        assertFalse(
            "the down branch still measures the side itself",
            body.contains("expanded && !ocSelectShouldFlipUp("),
        )
        assertTrue(body.contains("if (flipUp) {"))
        assertTrue(body.contains("if (expanded && sideKnown && !flipUp) {"))
    }

    // ---- the height cap ----------------------------------------------------

    @Test
    fun theCapKeepsAShortListAtItsWantedHeight() {
        val wanted = ocSelectListDesiredHeightDp(3)
        assertEquals(wanted, ocSelectListMaxHeightDp(3, 640f), 0.001f)
    }

    @Test
    fun theCapBoundsAListTallerThanTheWindow() {
        // 20 options on a 640 dp window: the list must be a fraction of the
        // window, never the whole card, or the card clips it again.
        val capped = ocSelectListMaxHeightDp(20, 640f)
        assertTrue("capped list $capped is not smaller than the window", capped < 640f)
        assertTrue(capped >= ocSelectListDesiredHeightDp(3))
    }

    @Test
    fun theCapRespectsTheSpaceActuallyLeftBelowTheTrigger() {
        // Last row with 90 dp below it: the cap must leave the list inside that,
        // which is what makes the internal scroller the thing that saves the
        // options from being unreachable.
        val capped = ocSelectListMaxHeightDp(8, 640f, spaceAvailableDp = 90f)
        assertTrue("capped list $capped does not fit in 90 dp", capped <= 90f)
    }

    @Test
    fun theCapNeverCollapsesToNothing() {
        // Even with no room at all the list keeps a tappable minimum, so the
        // user can still see it and scroll it.
        val capped = ocSelectListMaxHeightDp(8, 640f, spaceAvailableDp = 0f)
        assertTrue("a zero-space cap would make the list unreachable", capped > 0f)
    }

    @Test
    fun theCapIsZeroWithoutAWindow() {
        assertEquals(0f, ocSelectListMaxHeightDp(5, 0f), 0.001f)
    }

    // ---- the composable contract -------------------------------------------

    @Test
    fun theListBodyIsBoundedAndScrollable() {
        val body = components()
        val list = window(body, "private fun OcSelectList(", "fun OcTextField(")
        assertTrue(
            "a list that cannot scroll is how v43_06 lost every option after the first",
            list.contains(".verticalScroll(scrollState)"),
        )
        assertTrue(list.contains("heightIn(max = maxHeightDp.dp)"))
        assertTrue(list.contains("rememberScrollState()"))
    }

    @Test
    fun theListStillOpensNoSecondWindow() {
        // The v40 contract survives the v43 fix: a Popup would again draw past
        // the dialog card's rounded edge.
        val select = ocSelect()
        assertFalse(select.contains("DropdownMenu("))
        assertFalse(select.contains("Popup("))
        assertFalse(components().contains("import androidx.compose.ui.window.Popup"))
    }

    @Test
    fun theListIsDrawnOnWhicheverSideWon() {
        val select = ocSelect()
        val triggerAt = select.indexOf(".clickable(enabled = enabled) { expanded = !expanded }")
        assertTrue("the trigger is gone", triggerAt >= 0)
        val calls = Regex("OcSelectList\\(").findAll(select).map { it.range.first }.toList()
        assertEquals(
            "one list above the trigger and one below, each behind its own guard",
            2,
            calls.size,
        )
        assertTrue(
            "the upward list must be drawn before the trigger, the downward one after",
            calls[0] < triggerAt && calls[1] > triggerAt,
        )
        assertTrue(
            "the side decision must read the dialog container, not just the window",
            select.contains("ocSelectShouldFlipUp("),
        )
        assertTrue(select.contains("containerBottomDp = container?.bottomDp"))
        assertTrue(select.contains("containerTopDp = container?.topDp"))
        assertTrue(select.contains("triggerTopDp = triggerTopDp"))
    }

    @Test
    fun pickingAnOptionStillClosesTheList() {
        val select = ocSelect()
        val pick = select.indexOf("onPick = {")
        assertTrue(pick >= 0)
        val dispatch = select.indexOf("onSelect(it)", pick)
        assertTrue(dispatch > pick)
        assertTrue(
            "the list must collapse before the call site runs",
            select.substring(pick, dispatch).contains("expanded = false"),
        )
    }

    @Test
    fun everyOptionRowIsStillRendered() {
        val list = window(components(), "private fun OcSelectList(", "fun OcTextField(")
        assertTrue(list.contains("options.forEach { (optionValue, label) ->"))
        assertTrue(list.contains("onPick(optionValue)"))
    }

    @Test
    fun theTriggerChevronStillFollowsTheState() {
        val select = ocSelect()
        assertTrue(select.contains("expanded = !expanded"))
        assertTrue(select.contains("Chevron(c.iconMuted, expanded = expanded)"))
    }

    @Test
    fun theOcSelectCallSiteCountIsUnchanged() {
        // The signature did not move, so v40's call-site pin still holds.
        val dir = File("src/main/java/com/xiufeigo/opencodeshell/ui/settings")
            .takeIf { it.isDirectory }
            ?: File("app/src/main/java/com/xiufeigo/opencodeshell/ui/settings")
        assertTrue("the settings package is gone", dir.isDirectory)
        var calls = 0
        for (file in dir.listFiles { f: File -> f.name.endsWith(".kt") }.orEmpty().sortedBy { it.name }) {
            calls += Regex("(?<!fun )OcSelect\\(").findAll(file.readText()).count()
        }
        val dock = File("src/main/java/com/xiufeigo/opencodeshell/ui/session/dock")
            .takeIf { it.isDirectory }
            ?: File("app/src/main/java/com/xiufeigo/opencodeshell/ui/session/dock")
        if (dock.isDirectory) {
            for (file in dock.listFiles { f: File -> f.name.endsWith(".kt") }.orEmpty()) {
                calls += Regex("(?<!fun )OcSelect\\(").findAll(file.readText()).count()
            }
        }
        val servers = File("src/main/java/com/xiufeigo/opencodeshell/ui/servers")
            .takeIf { it.isDirectory }
            ?: File("app/src/main/java/com/xiufeigo/opencodeshell/ui/servers")
        if (servers.isDirectory) {
            for (file in servers.listFiles { f: File -> f.name.endsWith(".kt") }.orEmpty()) {
                calls += Regex("(?<!fun )OcSelect\\(").findAll(file.readText()).count()
            }
        }
        assertEquals("OcSelect call sites changed unexpectedly", 9, calls)
    }
}
