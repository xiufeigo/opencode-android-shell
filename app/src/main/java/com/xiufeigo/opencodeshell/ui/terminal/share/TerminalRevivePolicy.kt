package com.xiufeigo.opencodeshell.ui.terminal.share

/**
 * Post-revive fallback for the terminal panel: after [TerminalPanelHost]
 * re-adopts persisted tabs, a panel with no live tab must open a fresh
 * terminal instead of showing "No terminal open".
 *
 * Pure so the revive-then-create rule is unit-testable; the panel performs
 * the IO (`reviveTerminals()` / `createTerminal()`).
 */
object TerminalRevivePolicy {

    /**
     * `true` when the panel must auto-create a terminal: the revive adopted
     * nothing ([revivedAny] `false`, e.g. every persisted id 404s because the
     * backend drops PTYs on abnormal WS disconnect), or the tab list is still
     * empty despite an adoption report.
     */
    fun shouldAutoCreate(revivedAny: Boolean, tabCount: Int): Boolean =
        !revivedAny || tabCount <= 0
}
