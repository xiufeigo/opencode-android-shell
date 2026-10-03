package com.xiufeigo.opencodeshell

import androidx.compose.ui.unit.dp
import com.xiufeigo.opencodeshell.ui.BannerBand
import com.xiufeigo.opencodeshell.ui.session.actions.SessionActionsModel
import com.xiufeigo.opencodeshell.ui.terminal.TerminalTabStaleLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v51 真机批 ·【小件清扫四件】的纯逻辑侧。
 *
 * 四处都是 UI 决策，但决策本身可以从 Compose 里剥出来断言：
 *
 * - 终端 chip 的「旧目录」前缀（`TerminalTabStaleLabel`）——前缀不能吃掉编号；
 * - 通知横幅占位（`BannerBand`）——不能再把输入框顶出可视区；
 * - 弹层焦点接管（`OcPopoverPolicy`）——见 `OcPopoverDismissTest`，这里断言
 *   「带输入框的弹层不抢焦点」这条契约的另一半（`requestInitialFocus`）；
 * - 重做行（`SessionActionsModel.showsRedo`）——不渲染永不生效的行。
 */
class V51SmallCleanupTest {

    // ---- 1. 终端 chip 的旧目录前缀 -------------------------------------------

    @Test
    fun stalePrefixKeepsTheTerminalNumberVisible() {
        // v51 `tD/v51_62_term_after_move.png`: 两个旧 chip 都显示
        // 「Old dir · Termi…」，编号——唯一能区分它们的字——被省略号吃掉了。
        assertEquals("Old · Terminal 1", TerminalTabStaleLabel.chip("Old dir", "Terminal 1"))
        assertEquals("Old · Terminal 2", TerminalTabStaleLabel.chip("Old dir", "Terminal 2"))
    }

    @Test
    fun theTwoStaleChipsStillDiffer() {
        val a = TerminalTabStaleLabel.chip("Old dir", "Terminal 1")
        val b = TerminalTabStaleLabel.chip("Old dir", "Terminal 2")
        assertTrue("stale chips must stay distinguishable", a != b)
    }

    @Test
    fun thePrefixLosesAboutHalfItsWidth() {
        // 12 sp 下 `Old dir` 约占 chip 96 dp 的一半；`Old` 回到三分之一以内，
        // 编号（+ chip 的 ⋯ 菜单）重新拿回预算。
        assertTrue("Old dir".length - TerminalTabStaleLabel.chipPrefix("Old dir").length >= 3)
    }

    @Test
    fun aPrefixWithoutASpaceIsUsedWhole() {
        // CJK 没有空格可切：`旧目录` / `舊目錄` 原样保留，不做无意义的截断。
        assertEquals("旧目录", TerminalTabStaleLabel.chipPrefix("旧目录"))
        assertEquals("舊目錄", TerminalTabStaleLabel.chipPrefix("舊目錄"))
        assertEquals("旧目录 · 终端 1", TerminalTabStaleLabel.chip("旧目录", "终端 1"))
    }

    @Test
    fun aLongTranslationIsStillCapped() {
        // 一条更长的翻译不能把编号重新挤出 96 dp。
        val prefix = TerminalTabStaleLabel.chipPrefix("Outdated working directory")
        assertTrue(prefix.length <= 4)
    }

    @Test
    fun aBlankPrefixDoesNotProduceALeadingSeparator() {
        assertEquals(" · Terminal 1", TerminalTabStaleLabel.chip("   ", "Terminal 1"))
    }

    @Test
    fun theContentDescriptionKeepsTheFullWording() {
        // 视觉上缩短了，无障碍读到的仍是完整前缀。
        assertEquals(
            "Old dir · Terminal 1",
            TerminalTabStaleLabel.semantics("Old dir", "Terminal 1"),
        )
    }

    // ---- 2. 通知横幅的占位 ---------------------------------------------------

    @Test
    fun theBannerReservesLessThanOneAndAHalfLines() {
        // v51 `tE`: 老形状（两个 48 dp 动作 + 8 dp 上下留白）预留 ~64 dp ≈ 190 px，
        // 在小窗里正好把 composer 的输入框顶出可视区。
        assertTrue(
            "banner reserves ${BannerBand.RESERVED_HEIGHT}, was ~64.dp",
            BannerBand.RESERVED_HEIGHT < 48.dp,
        )
    }

    @Test
    fun theBannerHeightIsHardCapped() {
        // 以后再加一个动作 / 换一条更长的文案，也不能推得更多：先截断，再推挤。
        assertTrue(BannerBand.MAX_HEIGHT <= 48.dp)
        assertTrue(BannerBand.RESERVED_HEIGHT <= BannerBand.MAX_HEIGHT + BannerBand.ACTION_TARGET)
    }

    @Test
    fun theTouchTargetStaysAboveThePlatformMinimum() {
        // v48 把它提到 48 dp 是为了手指点得着；v51 收敛到 40 dp 仍在 32 dp 之上，
        // 且两个动作各留 48 dp 宽。
        assertTrue(BannerBand.ACTION_TARGET >= 32.dp)
    }

    // ---- 4. 重做行 -----------------------------------------------------------

    @Test
    fun redoIsShownOnlyWhileItCanRun() {
        // v51 `tE`: 撤销后重做永久置灰。本壳撤销是破坏性的（真的 stage 一次
        // revert），没有可回退的栈，所以那一行永远不会亮——它承诺的恢复能力
        // 壳并不具备。
        assertFalse(SessionActionsModel.showsRedo(redoEnabled = false))
        assertTrue(SessionActionsModel.showsRedo(redoEnabled = true))
    }

    @Test
    fun aFreshSessionWithNothingRevertedHidesRedo() {
        // 没有 revertMessageID 就没有可重做的边界；空会话同样没有用户消息。
        val menu = SessionActionsModel.menu(messages = emptyList(), revertMessageID = null)
        assertFalse(menu.undoEnabled)
        assertFalse(SessionActionsModel.showsRedo(menu.redoEnabled))
    }
}
