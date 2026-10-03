package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.settings.OC_SELECT_LIST_CHROME_DP
import com.xiufeigo.opencodeshell.ui.settings.OcSelectContainerBounds
import com.xiufeigo.opencodeshell.ui.settings.ocSelectListDesiredHeightDp
import com.xiufeigo.opencodeshell.ui.settings.ocSelectListMaxHeightDp
import com.xiufeigo.opencodeshell.ui.settings.ocSelectNestedConsumeVelocityY
import com.xiufeigo.opencodeshell.ui.settings.ocSelectNestedConsumeY
import com.xiufeigo.opencodeshell.ui.settings.ocSelectSpaceForSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v47 真机批：`OcSelect` 三件（裁切 / 穿透 / 上翻切顶）。
 *
 * 现象：Terminal Font 弹层被设置卡片底边裁（第三项只剩 8 px，
 * `research/v47_verify/tE/tE_11_tf_clean.png` / `tE_08_termfont_open.png`），
 * 弹层开着在它上面滑动滚的是底下设置列表（`tE_12_tf_swipe.png`），UI Font
 * 上翻时首项被弹层自身顶边切半行（`tE_06_app_bottom.png`）。
 *
 * 修法（`SettingsComponents.kt`，翻转逻辑本身不动——翻转只是优化）：
 * - 无容器回退从"整个窗口"收紧为"窗口在获胜侧的 room"，装不下的列表成为
 *   有限高 scroller 而不是被裁且无滚动余量的盒子；
 * - 列表用 `nestedScroll` 连接先吃掉自己能滚的那部分竖向拖拽，剩下的才交
 *   给面板，弹层吃掉自身区域的手势；
 * - 上翻分支补齐触发器侧的 4 dp 间隙（与下开分支对称），限高预留的 chrome
 *   落到具名常量 [OC_SELECT_LIST_CHROME_DP]。
 *
 * v48 真机批（`research/v48_verify/tE/v48_16/17/21`）：短列表特有穿透——
 * Terminal Font 仅 2 项、内容装得下（`maxScroll = 0`）时，在弹层上滑动穿透
 * 到底层设置列表（弹层不动、底层滚 125 px）；长列表（Theme / UI Font）滚自
 * 身正常。上一轮的 consume 只吃"弹层自己能滚的部分"，`maxScroll = 0` 就全
 * 放行了。修法：无滚动余量时吃掉整个竖向拖拽（含松手 fling），长列表行为不
 * 变；UI Font 上翻首项不被切的修复只加间隙、未动手势链，不回归。
 */
class OcSelectListGuaranteeTest {

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

    private fun ocSelectList(): String =
        window(components(), "private fun OcSelectList(", "fun OcTextField(")

    // ---- the backstop: bounded + scrollable without a container ------------

    @Test
    fun fittingListKeepsWantedHeightWithoutAContainer() {
        // Room below (580 dp) holds the 3-option list: the cap is the wanted
        // height, exactly like the old whole-window fallback.
        val window = 1080f
        val space = ocSelectSpaceForSide(464f, 500f, window, null, flipUp = false)
        assertEquals(window - 500f, space, 0.001f)
        assertEquals(
            ocSelectListDesiredHeightDp(3),
            ocSelectListMaxHeightDp(3, window, spaceAvailableDp = space),
            0.001f,
        )
    }

    @Test
    fun overflowingListWithoutAContainerBecomesAScroller() {
        // A trigger parked 100 dp above the window bottom with 3 options: the
        // old fallback capped at the wanted height (no scroll room, the v47
        // clip); the side room leaves 88 dp, so 31 dp of options move into the
        // list's own scroller instead of under the card edge.
        val window = 800f
        val space = ocSelectSpaceForSide(664f, 700f, window, null, flipUp = false)
        assertEquals(100f, space, 0.001f)
        val wanted = ocSelectListDesiredHeightDp(3)
        val capped = ocSelectListMaxHeightDp(3, window, spaceAvailableDp = space)
        assertTrue("capped list $capped is not smaller than its $wanted dp want", capped < wanted)
        assertTrue("overflow $wanted vs cap $capped leaves no scroll room", wanted - capped > 0f)
    }

    @Test
    fun overflowingFlippedListWithoutAContainerBecomesAScroller() {
        // Same guarantee upward: 60 dp above the trigger cannot hold 3 options,
        // so the flipped list is capped at 48 dp and scrolls internally.
        val space = ocSelectSpaceForSide(60f, 96f, 800f, null, flipUp = true)
        assertEquals(60f, space, 0.001f)
        val capped = ocSelectListMaxHeightDp(3, 800f, spaceAvailableDp = space)
        assertEquals(48f, capped, 0.001f)
    }

    @Test
    fun degenerateContainerFallsBackToTheSideRoom() {
        // Flat or inverted bounds are no bounds: same numbers as the null path.
        val flat = OcSelectContainerBounds(topDp = 700f, bottomDp = 700f)
        assertEquals(
            140f,
            ocSelectSpaceForSide(624f, 660f, 800f, flat, flipUp = false),
            0.001f,
        )
        assertEquals(
            624f,
            ocSelectSpaceForSide(624f, 660f, 800f, flat, flipUp = true),
            0.001f,
        )
        val inverted = OcSelectContainerBounds(topDp = 700f, bottomDp = 100f)
        assertEquals(
            140f,
            ocSelectSpaceForSide(624f, 660f, 800f, inverted, flipUp = false),
            0.001f,
        )
    }

    @Test
    fun zeroWindowWithoutAContainerStaysZero() {
        assertEquals(0f, ocSelectSpaceForSide(624f, 660f, 0f, null, flipUp = false), 0.001f)
        assertEquals(0f, ocSelectListMaxHeightDp(3, 0f), 0.001f)
    }

    // ---- the gesture: the list eats what it can scroll ----------------------

    @Test
    fun dragUpAtTopScrollsTheList() {
        assertEquals(-50f, ocSelectNestedConsumeY(-50f, 0f, 200f), 0.001f)
    }

    @Test
    fun dragDownAtTopFallsThroughToThePanel() {
        assertEquals(0f, ocSelectNestedConsumeY(50f, 0f, 200f), 0.001f)
    }

    @Test
    fun dragDownAtBottomScrollsTheList() {
        assertEquals(50f, ocSelectNestedConsumeY(50f, 200f, 200f), 0.001f)
    }

    @Test
    fun dragUpAtBottomFallsThroughToThePanel() {
        assertEquals(0f, ocSelectNestedConsumeY(-50f, 200f, 200f), 0.001f)
    }

    @Test
    fun consumeClampsToTheRemainingRoom() {
        // Only 190 dp of upward room left: the rest belongs to the panel.
        assertEquals(-190f, ocSelectNestedConsumeY(-500f, 10f, 200f), 0.001f)
        // Only 190 dp of downward room left.
        assertEquals(190f, ocSelectNestedConsumeY(500f, 190f, 200f), 0.001f)
    }

    @Test
    fun shortListWithoutScrollRoomConsumesTheWholeDrag() {
        // v48: Terminal Font's 2 options fit, so maxScroll = 0 — the whole
        // vertical drag dies on the popup instead of falling through to the
        // panel behind (v48_16/17/21: the popup stood still while the
        // settings travelled 125 px).
        assertEquals(-50f, ocSelectNestedConsumeY(-50f, 0f, 0f), 0.001f)
        assertEquals(50f, ocSelectNestedConsumeY(50f, 0f, 0f), 0.001f)
        assertEquals(-500f, ocSelectNestedConsumeY(-500f, 0f, 0f), 0.001f)
        assertEquals(500f, ocSelectNestedConsumeY(500f, 0f, 0f), 0.001f)
        // A degenerate negative max is still "no room": swallow, don't pass.
        assertEquals(-50f, ocSelectNestedConsumeY(-50f, 0f, -1f), 0.001f)
        assertEquals(50f, ocSelectNestedConsumeY(50f, 0f, -1f), 0.001f)
        // Zero delta stays zero whatever the room.
        assertEquals(0f, ocSelectNestedConsumeY(0f, 0f, 0f), 0.001f)
        assertEquals(0f, ocSelectNestedConsumeY(0f, 100f, 200f), 0.001f)
    }

    @Test
    fun shortListWithoutScrollRoomConsumesTheFling() {
        // The release velocity travels the fling chain on its own: without
        // this a fast swipe on the short popup flings the panel behind after
        // its drags already died above.
        assertEquals(-2000f, ocSelectNestedConsumeVelocityY(-2000f, 0f), 0.001f)
        assertEquals(2000f, ocSelectNestedConsumeVelocityY(2000f, 0f), 0.001f)
        assertEquals(-2000f, ocSelectNestedConsumeVelocityY(-2000f, -1f), 0.001f)
        assertEquals(0f, ocSelectNestedConsumeVelocityY(0f, 0f), 0.001f)
    }

    @Test
    fun longListKeepsTheStockFlingChain() {
        // A list that can scroll leaves fling to the stock chain — its own
        // scroller flings first, the panel takes the remainder — so Theme /
        // UI Font behave exactly as in v47.
        assertEquals(0f, ocSelectNestedConsumeVelocityY(-2000f, 200f), 0.001f)
        assertEquals(0f, ocSelectNestedConsumeVelocityY(2000f, 200f), 0.001f)
    }

    // ---- the composable contract --------------------------------------------

    @Test
    fun theListEatsItsOwnDragBeforeThePanel() {
        val list = ocSelectList()
        assertTrue(list.contains("NestedScrollConnection"))
        assertTrue(list.contains("onPreScroll"))
        assertTrue(list.contains("NestedScrollSource.UserInput"))
        assertTrue(list.contains("ocSelectNestedConsumeY("))
        assertTrue(list.contains(".nestedScroll(nestedPriority)"))
        // The priority connection sits *outside* the list's own scroller, so it
        // takes first crack at the drag before the panel behind ever sees it.
        val nestedAt = list.indexOf(".nestedScroll(nestedPriority)")
        val scrollAt = list.indexOf(".verticalScroll(scrollState)")
        assertTrue("the list lost its scroller", scrollAt >= 0)
        assertTrue("the priority connection must wrap the scroller", nestedAt in 0 until scrollAt)
    }

    @Test
    fun theShortListSwallowsTheFlingToo() {
        // v48: the drag math alone cannot stop a fast swipe — the release
        // velocity travels the fling chain separately, so the connection eats
        // it (no-room case only) on both fling passes.
        val list = ocSelectList()
        assertTrue(list.contains("onPreFling"))
        assertTrue(list.contains("onPostFling"))
        assertTrue(list.contains("ocSelectNestedConsumeVelocityY("))
    }

    @Test
    fun theListKeepsItsStockScroller() {
        val list = ocSelectList()
        assertTrue(list.contains("rememberScrollState()"))
        assertTrue(list.contains(".verticalScroll(scrollState)"))
        assertTrue(list.contains("heightIn(max = maxHeightDp.dp)"))
    }

    @Test
    fun theUpBranchMirrorsTheDownBranchGap() {
        // Trigger–4 dp–list downward; 4 dp–list–4 dp–trigger upward: three gaps
        // in total, so the flipped list never sits flush on its trigger.
        val select = ocSelect()
        assertEquals(
            3,
            Regex("Spacer\\(Modifier\\.height\\(4\\.dp\\)\\)").findAll(select).count(),
        )
    }

    @Test
    fun theCapReservesTheNamedChrome() {
        val body = components()
        assertTrue(body.contains("OC_SELECT_LIST_CHROME_DP = 12f"))
        assertTrue(body.contains("spaceAvailableDp - OC_SELECT_LIST_CHROME_DP"))
    }

    // ---- v49: the rows are the height the cap reserves ----------------------

    @Test
    fun theRowDrawsTheHeightTheCapReserves() {
        // v49 (`research/v49_verify/tE/v49_33/34/35`): the row was the 13 sp
        // text's own line box plus 20 dp of padding — ~44 dp against the 37 dp
        // `OC_SELECT_ROW_HEIGHT_DP` the wanted height is built from — so a popup
        // showing every option still measured scroll room and took the v47
        // fall-through path instead of the v48 swallow. The rows now carry the
        // constant, which is what makes the cap, the wanted height and the drawn
        // height one number (and the label is single-line + ellipsized, so an
        // extreme font scale shortens it instead of clipping the row).
        val list = ocSelectList()
        assertTrue(list.contains(".height(OC_SELECT_ROW_HEIGHT_DP.dp)"))
        assertTrue(list.contains("maxLines = 1"))
        assertTrue(list.contains("overflow = TextOverflow.Ellipsis"))
    }

    @Test
    fun aFullyVisibleLongListIsNowARoomlessList() {
        // The arithmetic the gesture chain reads: 8 options at the row height
        // the list actually draws == the wanted height, so the capped box can
        // hold all of them and `maxScroll` stays 0 — the v48 swallow, not the v47
        // fall-through that moved the settings panel by ~34 px behind the popup.
        val wanted = ocSelectListDesiredHeightDp(8)
        val cap = ocSelectListMaxHeightDp(8, windowHeightDp = 1080f, spaceAvailableDp = 520f)
        assertEquals(wanted, cap, 0.001f)
        assertEquals(
            // Zero room is the v48 case, and it swallows the whole drag — this
            // is what used to be unreachable for a list showing every option.
            -40f,
            ocSelectNestedConsumeY(-40f, 0f, scrollMax = wanted - cap),
            0.001f,
        )
        // …while a list that genuinely does not fit still scrolls itself, so the
        // v47 edge behavior is untouched for the genuinely clipped case.
        val capped = ocSelectListMaxHeightDp(8, windowHeightDp = 1080f, spaceAvailableDp = 200f)
        assertTrue("capped $capped is not smaller than the $wanted dp want", capped < wanted)
        assertTrue(
            "a clipped list must keep scrolling itself",
            ocSelectNestedConsumeY(-40f, 0f, scrollMax = wanted - capped) < 0f,
        )
    }
}
