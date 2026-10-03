package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.terminal.share.TerminalStateStore
import com.xiufeigo.opencodeshell.ui.terminal.share.TerminalTabs
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Numbered-tab durability across process death: the `terminal.title.numbered`
 * counter is snapshotted with the tab chrome, restored (with a legacy fallback
 * for payloads written before the counter existed), and re-seeded on revive —
 * so a restart never reissues "Terminal 1" while "Terminal 2" was the last
 * number handed out.
 */
class TerminalNumberPersistenceTest {

    @Test
    fun `snapshot carries the counter past closed tabs`() {
        val tabs = TerminalTabs()
        tabs.add("pty_1")
        tabs.add("pty_2")
        tabs.close("pty_1")
        tabs.close("pty_2")
        assertEquals(2, TerminalStateStore.snapshot(tabs).nextNumber)
    }

    @Test
    fun `counter round-trips through encode and decode`() {
        val tabs = TerminalTabs()
        tabs.add("pty_1")
        tabs.add("pty_2")
        val decoded = TerminalStateStore.decode(
            TerminalStateStore.encode(TerminalStateStore.snapshot(tabs)),
        )
        assertEquals(2, decoded.nextNumber)
        assertEquals(listOf(1, 2), decoded.tabs.map { it.number })
    }

    @Test
    fun `legacy payloads without a counter fall back to the highest tab number`() {
        val raw = """{"active":"pty_2","all":[
            {"id":"pty_1","titleNumber":1},
            {"id":"pty_2","titleNumber":2}
        ]}""".trimIndent().replace("\n", "")
        assertEquals(2, TerminalStateStore.decode(raw).nextNumber)
    }

    @Test
    fun `dropping a tab keeps the counter`() {
        val state = TerminalStateStore.PersistedState(
            activeId = "pty_2",
            tabs = listOf(
                TerminalStateStore.PersistedTab("pty_1", null, 1),
                TerminalStateStore.PersistedTab("pty_2", null, 2),
            ),
            nextNumber = 2,
        )
        val dropped = TerminalStateStore.withoutTab(state, "pty_1")
        assertEquals(2, dropped.nextNumber)
    }

    @Test
    fun `an explicitly restored number advances the counter without seedNumber`() {
        val tabs = TerminalTabs()
        tabs.add("pty_9", number = 2, focus = false)
        assertEquals(3, tabs.add("pty_10")?.number)
    }

    @Test
    fun `reseeding after a total-drop restart keeps numbering monotonic`() {
        val tabs = TerminalTabs()
        tabs.seedNumber(2)
        assertEquals(3, tabs.add("pty_fresh")?.number)
    }

    @Test
    fun `a zero explicit number falls back to the counter`() {
        val tabs = TerminalTabs()
        tabs.add("pty_1")
        tabs.add("pty_2")
        val tab = tabs.add("pty_zero", number = 0)
        assertEquals(3, tab?.number)
    }
}
