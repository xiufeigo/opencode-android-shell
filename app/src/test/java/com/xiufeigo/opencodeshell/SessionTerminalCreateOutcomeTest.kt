package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.terminal.share.SessionTerminalCreateOutcome
import com.xiufeigo.opencodeshell.ui.terminal.share.SessionTerminalCreateOutcome.Effect
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [SessionTerminalCreateOutcome] — a session-terminal `+` must attach the new
 * terminal as a tab and refetch the list (no manual leave-and-reenter);
 * a failed create must surface the error and still refetch.
 */
class SessionTerminalCreateOutcomeTest {

    @Test
    fun `success attaches then refetches`() {
        assertEquals(
            listOf(Effect.Attach("pty_1"), Effect.Refetch),
            SessionTerminalCreateOutcome.effects("pty_1"),
        )
    }

    @Test
    fun `null id shows the error and still refetches`() {
        assertEquals(
            listOf(Effect.ShowCreateError, Effect.Refetch),
            SessionTerminalCreateOutcome.effects(null),
        )
    }

    @Test
    fun `blank id shows the error and still refetches`() {
        assertEquals(
            listOf(Effect.ShowCreateError, Effect.Refetch),
            SessionTerminalCreateOutcome.effects("  "),
        )
    }
}
