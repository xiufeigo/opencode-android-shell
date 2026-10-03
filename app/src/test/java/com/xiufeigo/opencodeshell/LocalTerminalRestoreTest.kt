package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.terminal.share.LocalTerminalRestore
import com.xiufeigo.opencodeshell.ui.terminal.share.PtyProtocol
import com.xiufeigo.opencodeshell.ui.terminal.share.TerminalStateStore
import com.xiufeigo.opencodeshell.ui.terminal.share.TerminalTabs
import com.xiufeigo.opencodeshell.ui.terminal.share.resolveLocalRestore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Local embedded terminal revival: a panel rebuilt after process death
 * re-adopts the same server-side PTY ids and repaints the screen —
 * checkpoint when the id has one, socket full replay otherwise — instead of
 * opening a blank shell.
 */
class LocalTerminalRestoreTest {

    @Test
    fun `non-empty checkpoint resolves to a snapshot resume`() {
        val checkpoint = byteArrayOf(0x1B, '['.code.toByte(), 'H'.code.toByte())
        val plan = resolveLocalRestore(checkpoint, cols = 80, rows = 24, outputTail = 1234L)
        assertTrue(plan is LocalTerminalRestore.Snapshot)
        plan as LocalTerminalRestore.Snapshot
        assertTrue(plan.checkpoint.contentEquals(checkpoint))
        assertEquals(80, plan.cols)
        assertEquals(24, plan.rows)
        assertEquals(1234L, plan.tail)
    }

    @Test
    fun `missing or empty checkpoint falls back to socket replay`() {
        assertTrue(resolveLocalRestore(null, 80, 24, 0L) is LocalTerminalRestore.FullReplay)
        assertTrue(resolveLocalRestore(byteArrayOf(), 80, 24, 9L) is LocalTerminalRestore.FullReplay)
    }

    @Test
    fun `connect cursors match the wire contract`() {
        assertEquals(
            PtyProtocol.CURSOR_NEW_OUTPUT_ONLY,
            LocalTerminalRestore.Fresh.connectCursor,
        )
        assertEquals(
            77L,
            LocalTerminalRestore.Snapshot(byteArrayOf(1), 80, 24, 77L).connectCursor,
        )
        assertNull(LocalTerminalRestore.FullReplay.connectCursor)
    }

    @Test
    fun `seeded counter keeps fresh numbers off revived ones`() {
        val tabs = TerminalTabs()
        val revived = tabs.add("pty-old", number = 5) ?: error("add revived")
        tabs.seedNumber(revived.number)
        val fresh = tabs.add("pty-new") ?: error("add fresh")
        assertEquals(5, revived.number)
        assertEquals(6, fresh.number)
    }

    @Test
    fun `seed never moves the counter backwards`() {
        val tabs = TerminalTabs()
        tabs.add("pty-a")
        tabs.add("pty-b")
        tabs.seedNumber(1)
        val fresh = tabs.add("pty-c") ?: error("add fresh")
        assertEquals(3, fresh.number)
    }

    @Test
    fun `tab chrome round-trips through the store`() {
        val tabs = TerminalTabs()
        tabs.add("pty-1", title = null) ?: error("add 1")
        tabs.add("pty-2", title = "logs") ?: error("add 2")
        val decoded = TerminalStateStore.decode(
            TerminalStateStore.encode(TerminalStateStore.snapshot(tabs)),
        )
        assertEquals(listOf("pty-1", "pty-2"), decoded.tabs.map { it.id })
        assertEquals(listOf(1, 2), decoded.tabs.map { it.number })
        assertEquals("logs", decoded.tabs[1].title)
        assertEquals("pty-2", decoded.activeId)
    }

    @Test
    fun `panel wires revive before auto-create`() {
        val panel = terminalSource("TerminalPanel.kt")
        assertTrue("revive", panel.contains("fun reviveTerminals("))
        assertTrue("restore param", panel.contains("restore: LocalTerminalRestore = LocalTerminalRestore.Fresh"))
        assertTrue("replay cursor", panel.contains("initialCursor = restore.connectCursor"))
        assertTrue("revive-first", panel.contains("val revived = reviveTerminals()"))
        assertTrue(
            "fallback",
            panel.contains("TerminalRevivePolicy.shouldAutoCreate(revived, tabs.size)"),
        )
        assertTrue("seed", panel.contains("tabs.seedNumber("))
        val restore = terminalSource("share/SessionTerminalRestore.kt")
        assertTrue("plan", restore.contains("fun resolveLocalRestore("))
    }

    private fun terminalSource(name: String): String {
        val relative = "src/main/java/com/xiufeigo/opencodeshell/ui/terminal/$name"
        return listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText() ?: error("missing $relative")
    }
}
