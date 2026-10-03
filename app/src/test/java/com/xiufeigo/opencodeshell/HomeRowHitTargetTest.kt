package com.xiufeigo.opencodeshell

import androidx.compose.ui.unit.dp
import com.xiufeigo.opencodeshell.ui.home.HomeRowLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Home's list rows are the whole tap target, and two devices measured them at
 * 41 dp — a `uiautomator` dump shows one `clickable` node per session row at
 * 108 px (2.625 px/dp) and 41 dp at 160 dpi, below the 48 dp platform minimum.
 *
 * The rows grow through `ocHitTargetAtLeast`, which must sit **before** every
 * `.height` / `.heightIn(min)` / `.clip` / `.clickable` in the chain: a fixed
 * height after it constrains the child and clamps the node back down (the same
 * trap `TabIconTouchTargetRingTest` pins for the tab strip).
 */
class HomeRowHitTargetTest {

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("missing $relative")

    @Test
    fun thePlatformMinimumIs48Dp() {
        assertEquals(48.dp, HomeRowLayout.MIN_HIT_TARGET)
    }

    @Test
    fun aRowShorterThanTheMinimumReportsTheMinimum() {
        assertEquals(48.dp, HomeRowLayout.hitTarget(HomeRowLayout.SESSION_ROW_HEIGHT))
        assertEquals(48.dp, HomeRowLayout.hitTarget(28.dp))
    }

    @Test
    fun aRowTallerThanTheMinimumIsLeftAlone() {
        assertEquals(96.dp, HomeRowLayout.hitTarget(96.dp))
        assertEquals(48.dp, HomeRowLayout.hitTarget(48.dp))
    }

    @Test
    fun theSessionRowWiresTheGrowthBeforeItsFixedHeight() {
        val sessions = source("src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeSessionsList.kt")
        assertTrue(sessions.contains("private val ROW_HEIGHT = HomeRowLayout.SESSION_ROW_HEIGHT"))
        val chain = sessions
            .substringAfter("ocHitTargetAtLeast(HomeRowLayout.MIN_HIT_TARGET)")
            .substringBefore("combinedClickable(")
        // A fixed height after the growth would clamp the node back to 40 dp.
        assertTrue(".height(ROW_HEIGHT)" !in chain)
        assertTrue(chain.contains(".heightIn(min = ROW_HEIGHT)"))
        // The clickable node sits inside the grown one, so it reports 48 dp.
        assertTrue(
            sessions.indexOf("ocHitTargetAtLeast(HomeRowLayout.MIN_HIT_TARGET)") <
                sessions.indexOf("combinedClickable("),
        )
    }

    @Test
    fun everyProjectRowWiresTheGrowth() {
        val projects = source("src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeProjectsList.kt")
        val growth = projects.windowed("ocHitTargetAtLeast(HomeRowLayout.MIN_HIT_TARGET)".length)
            .count { it == "ocHitTargetAtLeast(HomeRowLayout.MIN_HIT_TARGET)" }
        // The project row, the empty state's add row and the recently-closed row.
        assertEquals(3, growth)
        assertTrue(projects.contains("private val ROW_HEIGHT = 28.dp"))
        assertEquals(3, projects.windowed(".heightIn(min = ROW_HEIGHT)".length).count { it == ".heightIn(min = ROW_HEIGHT)" })
    }

    @Test
    fun theHelperKeepsTheChildAndCentresIt() {
        val helper = source("src/main/java/com/xiufeigo/opencodeshell/ui/components/OcTouchTarget.kt")
        assertTrue(helper.contains("target: Dp = 48.dp"))
        // It measures the child as-is (upstream's h-10 / h-7 survive) and only
        // grows the node it reports to the parent.
        assertTrue(helper.contains("measurable.measure(constraints)"))
        assertTrue(helper.contains("touchTargetOffsetPx(placeable.height, height)"))
    }
}
