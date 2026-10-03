package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.components.touchTargetOffsetPx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The 48 dp touch target behind Home's 28 dp icon buttons.
 *
 * 真机 (v31 tA): 「folder+」 opened the picker only when the tap landed on the
 * exact centre of the 16 dp glyph — `y=278` opened it, `y=240` (38 px away, still
 * inside the same 28 dp header row) did nothing, and the add entry read as
 * broken. The fix keeps the row's pixel geometry and grows only the *hit* box
 * ([touchTargetOffsetPx]); this pins the placement math and the wiring.
 */
class HomeTouchTargetTest {

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("missing $relative")

    @Test
    fun theHitBoxStaysCentredOnTheVisualBox() {
        // The header geometry at density 2.5: 28 dp visual → 48 dp target.
        val visual = 70
        val target = 120
        val offset = touchTargetOffsetPx(visual, target)
        assertEquals(-25, offset)
        // Same centre on both axes: the glyph does not move.
        assertEquals(visual / 2, offset + target / 2)
        // The ring overflows the reported box on both sides — that overflow is
        // the part a finger lands on (Compose hit-tests a child by its own
        // placed bounds, so it stays tappable).
        assertTrue(offset < 0)
        assertEquals(target, visual - 2 * offset)
    }

    @Test
    fun anAlreadyLargeControlIsNotMoved() {
        // Target == visual (a 48 dp control): no offset at all.
        assertEquals(0, touchTargetOffsetPx(118, 118))
    }

    @Test
    fun homeWiresTheLargerTargetOnTheAddEntry() {
        val projects = source("src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeProjectsList.kt")
        val sessions = source("src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeSessionsList.kt")
        val picker = source("src/main/java/com/xiufeigo/opencodeshell/ui/home/DirectoryPickerDialog.kt")

        // The add entry (both layouts: the column and the standalone list) and the
        // sessions column's floating ✎ go through the helper.
        assertTrue(projects.contains("ocMinTouchTarget(visual = ICON_BUTTON_LARGE)"))
        assertTrue(sessions.contains("ocMinTouchTarget(visual = ICON_BUTTON_LARGE)"))
        // The picker's 28 dp close.
        assertTrue(picker.contains("ocMinTouchTarget(visual = 28.dp)"))
        // The helper itself: 48 dp default, centred placement, geometry preserved.
        val helper = source("src/main/java/com/xiufeigo/opencodeshell/ui/components/OcTouchTarget.kt")
        assertTrue(helper.contains("target: Dp = 48.dp"))
        assertTrue(helper.contains("touchTargetOffsetPx"))
        assertTrue(helper.contains("layout(visualPx, visualPx)"))
    }
}
