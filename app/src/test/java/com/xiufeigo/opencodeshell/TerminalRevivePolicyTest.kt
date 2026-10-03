package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.terminal.share.TerminalRevivePolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TerminalRevivePolicy] — the revive-then-create fallback: after
 * `reviveTerminals()` a panel with no live tab must auto-create a terminal
 * instead of showing "No terminal open".
 */
class TerminalRevivePolicyTest {

    @Test
    fun `all persisted ids dead means auto-create`() {
        assertTrue(TerminalRevivePolicy.shouldAutoCreate(revivedAny = false, tabCount = 0))
    }

    @Test
    fun `revive failure falls back even if a tab raced in`() {
        assertTrue(TerminalRevivePolicy.shouldAutoCreate(revivedAny = false, tabCount = 1))
    }

    @Test
    fun `revived tabs suppress the fallback`() {
        assertFalse(TerminalRevivePolicy.shouldAutoCreate(revivedAny = true, tabCount = 1))
        assertFalse(TerminalRevivePolicy.shouldAutoCreate(revivedAny = true, tabCount = 3))
    }

    @Test
    fun `empty tab list still falls back even when revive reported success`() {
        assertTrue(TerminalRevivePolicy.shouldAutoCreate(revivedAny = true, tabCount = 0))
    }
}
