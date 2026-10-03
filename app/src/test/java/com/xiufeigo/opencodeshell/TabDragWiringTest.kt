package com.xiufeigo.opencodeshell

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * B-20 / D-15: the wiring of the tab drag gestures.
 *
 * The gestures live inside `@Composable` bodies that no JVM test can invoke
 * (the same reason `ChordCommandWiringTest` scans sources), so this pins the
 * three surfaces — desktop session strip, mobile drawer, terminal strip — and
 * the host callback each one has to reach. Deleting a gesture or dropping the
 * callback fails here instead of silently turning a drag into a dead gesture.
 */
class TabDragWiringTest {

    private fun source(relative: String): String {
        val file = File(relative).takeIf { it.isFile } ?: File("app/$relative")
        return file.readText()
    }

    private val desktop = "src/main/java/com/xiufeigo/opencodeshell/ui/nav/DesktopTabStrip.kt"
    private val drawer = "src/main/java/com/xiufeigo/opencodeshell/ui/nav/MobileTabsDrawer.kt"
    private val terminal = "src/main/java/com/xiufeigo/opencodeshell/ui/terminal/TerminalPanel.kt"
    private val app = "src/main/java/com/xiufeigo/opencodeshell/ui/App.kt"

    /** Both session placements reorder on a long press and step through the grid. */
    @Test
    fun theSessionStripsTakeTheLongPressDrag() {
        for (path in listOf(desktop, drawer)) {
            val text = source(path)
            assertTrue("$path: long-press drag", text.contains("detectDragGesturesAfterLongPress"))
            assertTrue("$path: the drop index", text.contains("TabReorder.dropIndex"))
            assertTrue("$path: live reorder", text.contains("liveMove.value(from, to)"))
            // The gesture has to survive its own reorder: keyed slots, not
            // positional ones, and the list read through `rememberUpdatedState`.
            assertTrue("$path: live list", text.contains("rememberUpdatedState(sessions)"))
        }
        assertTrue(source(desktop).contains("key(tab.id)"))
        // Vertical drawer travels on y, the horizontal strip on x.
        assertTrue(source(drawer).contains("translationY = dragDy"))
        assertTrue(source(desktop).contains("translationX = dragDx"))
    }

    /** A long press that never moved opens the Rename menu, not a reorder. */
    @Test
    fun aStationaryLongPressOpensTheRenameMenu() {
        for (path in listOf(desktop, drawer)) {
            val text = source(path)
            assertTrue("$path: the menu state", text.contains("var menuFor by remember"))
            assertTrue("$path: the menu item", text.contains("R.string.common_rename"))
            assertTrue("$path: the editor", text.contains("TabRenameField("))
            assertTrue("$path: menu only without movement", text.contains("!dragMoved"))
        }
    }

    /** D-15: the terminal strip drives the existing model `move()`. */
    @Test
    fun theTerminalStripReordersThroughItsModel() {
        val text = source(terminal)
        assertTrue(text.contains("detectDragGesturesAfterLongPress"))
        assertTrue(text.contains("TabReorder.dropIndex"))
        assertTrue(text.contains("liveMove.value(from, to)"))
        assertTrue(text.contains("key(tab.id)"))
        // The chip's own ⋮ menu is the long press's other outcome, so the menu
        // state had to move up into the strip.
        assertTrue(text.contains("onOpenMenu"))
        assertTrue(text.contains("menuOpen"))
    }

    /** The host applies the drag to the one list both placements render. */
    @Test
    fun theHostWiresReorderAndRename() {
        val text = source(app)
        assertTrue(text.contains("SessionTabs.reorder(openSessions.toList(), from, to)"))
        assertTrue(text.contains("onMoveSession = onMoveSessionTab"))
        assertTrue(text.contains("onRenameSession = onRenameSessionTab"))
        assertTrue(text.contains("viewModel.renameSession(tab.id, trimmed)"))
        // The bottom bar only carries the drawer's callbacks.
        val bottom = source("src/main/java/com/xiufeigo/opencodeshell/ui/nav/BottomTabStrip.kt")
        assertTrue(bottom.contains("onMoveSession = onMoveSession"))
        assertTrue(bottom.contains("onRenameSession = onRenameSession"))
    }
}
