package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.SessionTerminalEntryPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v43: the wide-screen `>_` terminal entry chip used to be `Alignment.TopEnd` of
 * the **session body**, so the docked review panel — the timeline row's trailing
 * sibling — laid its tab row underneath it and the chip covered the 「文件」tab
 * (`research/v43_verify/tE/v43_32_panel_wide.png`, `v43_34_resize2.png`).
 *
 * The chip is now mounted by `SessionScreen` inside the timeline pane
 * (`SessionTerminalEntryPolicy`), which is what makes the overlap impossible;
 * these values pin that its geometry did **not** move in the process, so the
 * chip still lands where it did with no panel docked.
 */
class SessionTerminalEntryPolicyTest {

    /** Below the 0–80 dp session header band (48 dp bar + sticky row). */
    @Test
    fun theChipStartsClearOfTheSessionHeaderBand() {
        assertEquals(88f, SessionTerminalEntryPolicy.TOP_DP.value, 0.001f)
    }

    /** 8 dp from the timeline pane's trailing edge, as before. */
    @Test
    fun theChipKeepsItsTrailingInset() {
        assertEquals(8f, SessionTerminalEntryPolicy.END_DP.value, 0.001f)
    }
}
