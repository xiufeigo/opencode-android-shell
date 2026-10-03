package com.xiufeigo.opencodeshell

import androidx.compose.ui.unit.dp
import com.xiufeigo.opencodeshell.ui.components.touchTargetOffsetPx
import com.xiufeigo.opencodeshell.ui.components.touchTargetRingOverflow
import com.xiufeigo.opencodeshell.ui.home.WORKSPACE_DELETE_GLYPH
import com.xiufeigo.opencodeshell.ui.home.WORKSPACE_ROW_END
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v71（`research/v71_verify/5556`，emulator-5556，density 160，`wm size 1366x768`）:
 * the worktree row's × — the new-session selector's only delete entry.
 *
 * 真机量到的是三件事，不是「行体把 × 那一击吃掉」:
 * - 行体（x 641..841）点选中 ✓；
 * - × 的手指环（x 803..839、y 491..539，即 16 dp 盒外 Compose 自己按
 *   `minimumTouchTargetSize` 补的 48 dp 环）点开删除确认 ✓ —— 环落在行体上时
 *   **× 赢**（子节点在手势竞争里先拿那一击），行体没有抢；
 * - x=841 起（**菜单自己的右边界之外**）整条带子落进 popup 的 scrim：菜单只是关掉，
 *   删除确认永远不出现（x=841/844/854…910 全是这个结果）。测试员报的「下拉收起、
 *   没有弹窗」就是这一条；「Local 标签切到该 worktree」是更早那次刻意点行
 *   （`41_row_click`）留下的 chip —— `34_x_clicked`/`39_xtries` 里 chip 还是
 *   Local，即那些点击并没有选中任何东西。
 *
 * 上游的 `padding-inline-end: 6px` 是鼠标尺寸：环要 16 dp 才放得下。行因此把整个
 * 溢出留出来，环的右缘落在菜单自己的边上。删除必经确认那条红线由
 * [WorktreeLifecycleWiringTest] 钉着，这里只钉「× 是自己的目标、行体是另一个」。
 */
class WorktreeDeleteHitTargetV71Test {

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?.replace("\r\n", "\n")
            ?: error("missing $relative")

    /** The row renderer's own body, so the pins cannot be satisfied by another row. */
    private fun row(): String {
        val body = source("src/main/java/com/xiufeigo/opencodeshell/ui/home/NewSessionScreen.kt")
        val start = body.indexOf("private fun WorkspaceMenuRow(")
        val end = body.indexOf("@Composable\nprivate fun MenuEmptyLine(", start)
        assertTrue("the worktree row renderer is gone", start >= 0 && end > start)
        return body.substring(start, end)
    }

    // ------------------------------------------------- the finger fits the menu

    @Test
    fun theRingOverflowIsTheRowsTrailingInset() {
        // The glyph box and the platform's finger: 16 dp of pixels, 48 dp of finger.
        val overflow = touchTargetRingOverflow(visual = 16.dp)
        assertEquals(16f, WORKSPACE_DELETE_GLYPH.value, 0f)
        assertEquals(16f, overflow.value, 0f)
        // The same number out of the placement math the modifier itself runs.
        assertEquals(-16, touchTargetOffsetPx(visualPx = 16, targetPx = 48))
        // The row reserves the whole overflow: the ring ends at the menu's edge
        // instead of hanging into the scrim (where the tap only closes the menu).
        assertTrue(
            "the row's trailing inset must cover the ring's overflow",
            WORKSPACE_ROW_END >= overflow,
        )
        assertTrue(
            "the row's padding must be the derived inset, not upstream's 6 dp",
            row().contains(
                "padding(start = 12.dp, end = WORKSPACE_ROW_END, top = 4.dp, bottom = 4.dp)",
            ),
        )
    }

    @Test
    fun theGlyphBoxIsTheOneTheInsetIsDerivedFrom() {
        // If the glyph box ever changes, the inset has to follow it — so the × has
        // to take the shared constant rather than its own literal.
        assertTrue(
            "the × must size its box from the constant the inset derives from",
            row().contains(".ocMinTouchTarget(visual = WORKSPACE_DELETE_GLYPH)"),
        )
        // 48 dp is `ocMinTouchTarget`'s default: the ring the inset is derived for
        // must stay the platform minimum.
        val helper = source("src/main/java/com/xiufeigo/opencodeshell/ui/components/OcTouchTarget.kt")
        assertTrue(helper.contains("fun touchTargetRingOverflow(visual: Dp, target: Dp = 48.dp)"))
    }

    // ------------------------------------------------- two targets, two actions

    @Test
    fun theRowSelectsAndTheDeleteRemoves() {
        val body = row()
        // The row body: selection, on the row's own clickable chain.
        assertTrue(body.contains(".clickable(role = Role.Button, onClick = onClick)"))
        // The ×: its own clickable chain with its own action, so the row's click
        // can never be the delete and vice versa.
        assertTrue(
            "the × needs its own clickable wired to onDelete",
            Regex(
                """\.clickable\(role = Role\.Button\) \{ onDelete\(\) \}\s*""" +
                    """\.semantics \{ contentDescription = deleteLabel \}""",
            ).containsMatchIn(body),
        )
        // And it stays *inside* the row's clickable content: the deeper node wins
        // the gesture there (measured), where a sibling of the row would lose to
        // the row's direct hit and the × would stop opening the confirm.
        val rowClickable = body.indexOf(".clickable(role = Role.Button, onClick = onClick)")
        val rowContent = body.indexOf("verticalAlignment = Alignment.CenterVertically,", rowClickable)
        val deleteClickable = body.indexOf(".clickable(role = Role.Button) { onDelete() }")
        assertTrue(
            "the × must be a child of the row's content, after the row's own chain",
            rowClickable >= 0 && rowContent > rowClickable && deleteClickable > rowContent,
        )
    }

    @Test
    fun theDeleteIsStillOneCallbackAwayFromTheConfirm() {
        // The × arms the screen's `onDelete` — never the transport itself: the write
        // stays behind `WorktreeDeleteConfirmDialog`, which is where
        // [WorktreeLifecycleWiringTest] pins it.
        val body = source("src/main/java/com/xiufeigo/opencodeshell/ui/home/NewSessionScreen.kt")
        assertTrue(body.contains("onDelete = {"))
        assertTrue(body.contains("onDeleteWorktree(path)"))
    }
}
