package com.xiufeigo.opencodeshell

import androidx.compose.ui.unit.dp
import com.xiufeigo.opencodeshell.ui.home.HomeRowLayout
import com.xiufeigo.opencodeshell.ui.home.NEW_SESSION_TOP
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v58 P0 — a tap in the 6–28 px strip between two cards on Home opened a draft
 * session (`research/v57_verify/E2ECHK5560/37_home_again.png` →
 * `38_gaptap.png`).
 *
 * Two surfaces owned that strip, and neither of them was the row the reader
 * believed they were aiming at:
 *
 * 1. the floating ✎ sat in the 16 dp [HomeRowLayout.GROUP_PAD_HEIGHT] strip
 *    *above* the first group header, where upstream puts it on the header's own
 *    line — its 48 dp finger ring therefore covered the blank band between the
 *    search block and the first row;
 * 2. the `gap-px` between two cards was an outer `padding` on the caller's
 *    `Box`, so it belonged to neither row's clickable and swallowed the tap.
 */
class HomeSessionGapTapV58Test {

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("missing $relative")

    private val sessions: String
        get() = source("src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeSessionsList.kt")

    @Test
    fun theFloatingButtonRidesTheFirstGroupHeadersLine() {
        // Upstream `right-0; top-[84px]` — the header line is one group pad down.
        assertEquals(HomeRowLayout.GROUP_PAD_HEIGHT, NEW_SESSION_TOP)
        assertEquals(16.dp, NEW_SESSION_TOP)
    }

    @Test
    fun theFloatingButtonIsInsetByThatLineRatherThanPinnedToTheTop() {
        val call = sessions.substringAfter("FloatingNewSessionButton(").take(240)
        assertTrue("the ✎ must not sit flush with the rows' top edge", call.contains(".padding(top = NEW_SESSION_TOP)"))
        assertTrue(call.contains(".align(Alignment.TopEnd)"))
    }

    @Test
    fun theInterRowGapIsInsideTheClickableNode() {
        val chain = sessions
            .substringAfter("ocHitTargetAtLeast(HomeRowLayout.MIN_HIT_TARGET)")
            .substringBefore("verticalAlignment = Alignment.CenterVertically")
        val clickable = chain.indexOf("combinedClickable(")
        val gap = chain.indexOf(".padding(bottom = ROW_GAP)")
        assertTrue("the row never wires the gap", gap >= 0)
        // After the clickable, so the gap's pixel belongs to this row.
        assertTrue("the gap must be padded after combinedClickable", gap > clickable)
    }

    @Test
    fun theListNoLongerPadsTheGapOutsideTheRow() {
        // An outer `padding(bottom = ROW_GAP)` on the row call is a dead strip
        // between the cards; the row draws it itself now.
        assertTrue("the caller still pads the gap from the outside", "modifier = Modifier.padding(bottom = ROW_GAP)" !in sessions)
    }
}
