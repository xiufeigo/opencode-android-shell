package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.header.ContextUsage
import com.xiufeigo.opencodeshell.ui.session.review.ReviewPanelTab
import com.xiufeigo.opencodeshell.ui.session.review.visibleReviewTabs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v161: the session right panel's three parity fixes, pinned against upstream.
 *
 * Upstream is `_upstream/packages/app/src/session/files/session-side-panel.tsx`
 * (+ `session/screen.tsx`, `shell/commands/command.tsx`); the shell is
 * `ui/session/review/ReviewPanel.kt` + `ui/session/SessionScreen.kt` +
 * `ui/session/header/ContextUsage.kt`. Each test names the upstream line it
 * holds, and the source pins are the repo's established answer for screen
 * wiring (see [SessionContextRingTargetTest]).
 *
 * The two deliberately **not** changed are documented instead: the `上下文`
 * wording and the usage denominator -- see
 * `research/fix/RIGHT_PANEL_PARITY_FIX.md`.
 */
class RightPanelParityTest {

    private fun source(relative: String): String {
        val file = File(relative).takeIf { it.isFile } ?: File("app/$relative")
        // CRLF sources would break `\n`-sensitive windows otherwise.
        return file.readText().replace("\r\n", "\n")
    }

    private fun reviewPanel() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/review/ReviewPanel.kt")

    private fun sessionScreen() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionScreen.kt")

    private fun appCommands() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/palette/AppCommands.kt")

    // --------------------------------------------------------------- fix 1

    /**
     * Upstream opens the terminal from the command palette, never from the side
     * panel: `shell/commands/command.tsx:18` lists `terminal.toggle` /
     * `terminal.new` among the palette-editable commands, and the panel itself
     * is the side / bottom dock (`screen.tsx:473-500`, `:507-539`). Upstream's
     * tab strip has no terminal trigger at all (`session-side-panel.tsx:333-374`).
     */
    @Test
    fun theSidePanelNoLongerMountsTheSelfInventedTerminalChip() {
        val screen = sessionScreen()
        assertFalse(
            "the 28 dp `TerminalEntryButton` has no upstream counterpart " +
                "(`session-side-panel.tsx:333-374` has review + context triggers " +
                "only) and must not be mounted in the session screen",
            screen.contains("TerminalEntryButton("),
        )
    }

    /** The entry itself is still there, as the palette's `terminal.toggle` row. */
    @Test
    fun theTerminalEntryIsTheCommandPaletteToggle() {
        assertTrue(
            "`ui/palette/AppCommands.kt` registers the `terminal.toggle` row " +
                "(upstream `shell/commands/command.tsx:18` -> `shell/commands/" +
                "palette.ts:43`)",
            appCommands().contains("id = \"terminal.toggle\""),
        )
        // ... and the dock it toggles is untouched: both upstream slots remain.
        val screen = sessionScreen()
        assertTrue("side dock slot kept", screen.contains("TerminalPlacement.SIDE"))
        assertTrue("bottom dock slot kept", screen.contains("TerminalPlacement.BOTTOM"))
    }

    /**
     * The 48 dp touch-target geometry the chip was measured with is not deleted
     * with the chip: `SessionTerminalEntryPolicy` is the pinned anchor
     * [UsabilityCellsV146SendOcclusionFixTest] rebuilds the 46x48 measured box
     * from, and [SessionTerminalEntryPolicyTest] holds its two values.
     */
    @Test
    fun theTerminalEntryGeometryPinsSurvive() {
        val policy = source(
            "src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionScreen.kt"
        )
        assertTrue(
            "`SessionTerminalEntryPolicy` must stay the pinned 48 dp touch-box " +
                "anchor (`SessionTerminalEntryPolicyTest`, " +
                "`UsabilityCellsV146SendOcclusionFixTest`)",
            policy.contains("internal object SessionTerminalEntryPolicy"),
        )
    }

    // --------------------------------------------------------------- fix 2

    /**
     * Upstream's strip is a fixed pair -- `review` (`session-side-panel.tsx:333-343`)
     * and `context` (`:344-374`) -- plus `<For each={panelTabs()}>` (`:375-386`),
     * the file tabs an opened file creates. With no file open there are exactly
     * two triggers.
     */
    @Test
    fun theDefaultStripIsTheUpstreamPair() {
        assertEquals(
            listOf(ReviewPanelTab.CHANGES, ReviewPanelTab.CONTEXT),
            visibleReviewTabs(null),
        )
        assertEquals(2, visibleReviewTabs(null).size)
    }

    /**
     * The hoisted-tab contract: `file.open` switches the panel to the file tab
     * from outside (`ReviewPanel.kt`'s `tab` param, driven by
     * `ui/palette/AppCommands.kt`). v161 made that tab dynamic like upstream's --
     * so the target must still exist, and still be reachable.
     */
    @Test
    fun theFileOpenContractStillReachesAFileTab() {
        val withFile = visibleReviewTabs("src/main/kotlin/Main.kt")
        assertTrue(
            "`file.open` must still land on a file tab that is rendered",
            withFile.contains(ReviewPanelTab.FILES),
        )
        // upstream order: review, context, then the file tabs
        assertEquals(
            listOf(ReviewPanelTab.CHANGES, ReviewPanelTab.CONTEXT, ReviewPanelTab.FILES),
            withFile,
        )
        // a blank path is not an opened file either
        assertEquals(2, visibleReviewTabs("").size)
        assertEquals(2, visibleReviewTabs("   ").size)
    }

    /** The two ends of the contract, in the sources that have to agree. */
    @Test
    fun theHoistedFileOpenWiringIsIntact() {
        val screen = sessionScreen()
        assertTrue(
            "`SessionScreen` still hoists the tab and still switches it to FILES",
            screen.contains("ReviewPanelTab.FILES.name"),
        )
        assertTrue(
            "the selected tab is still the hoisted state",
            screen.contains("var reviewTabName by rememberSaveable"),
        )
        val panel = reviewPanel()
        assertTrue(
            "the tab row is built from the dynamic list",
            panel.contains("visibleReviewTabs(openPath)"),
        )
        assertTrue(
            "the file tab still has its content branch",
            panel.contains("ReviewPanelTab.FILES -> ReviewFilesTab("),
        )
        // the hoisted setter is still wired to the panel
        assertTrue(panel.contains("onTabChange: (ReviewPanelTab) -> Unit"))
    }

    // --------------------------------------------------------------- fix 3

    /**
     * Upstream's context trigger carries `<SessionContextUsage variant=
     * "indicator" />` (`session-side-panel.tsx:370`). The number it paints is
     * `ctx()?.usage` (`session-context-tab.tsx:133`) -- the same cell the tab's
     * stats table shows, so the indicator cannot disagree with the `Usage` row.
     */
    @Test
    fun theUsageIndicatorReadsTheContextTabsOwnUsageCell() {
        assertEquals(42, ContextUsage.indicatorPercent("42%"))
        assertEquals(0.42f, ContextUsage.indicatorProgress("42%")!!, 0.0001f)
        assertEquals(0f, ContextUsage.indicatorProgress("0%")!!, 0.0001f)
        assertEquals(1f, ContextUsage.indicatorProgress("100%")!!, 0.0001f)
        // a locale-tolerant cell parses, a non-percentage one does not
        assertEquals(42, ContextUsage.indicatorPercent("42 %"))
        assertEquals(42, ContextUsage.indicatorPercent(" 42% "))
        assertNull(ContextUsage.indicatorProgress("—"))
        assertNull(ContextUsage.indicatorProgress(null))
        assertNull(ContextUsage.indicatorProgress(""))
        assertNull(ContextUsage.indicatorProgress("1 024%"))
        // out of range: no ring at all rather than a clamped lie
        assertNull(ContextUsage.indicatorPercent("150%"))
        assertNull(ContextUsage.indicatorPercent("-1%"))
    }

    /** The indicator is wired into the context trigger, in front of the label. */
    @Test
    fun theContextTabRendersTheIndicator() {
        val panel = reviewPanel()
        assertTrue(
            "the context tab passes the indicator as the leading affordance",
            panel.contains("ContextTabUsageIndicator(contextSnapshot)"),
        )
        assertTrue(
            "only the context trigger carries it, like upstream (:369-372)",
            panel.contains("if (entry == ReviewPanelTab.CONTEXT)"),
        )
        assertTrue(
            "the indicator is upstream's own `ProgressCircleV2` port",
            panel.contains("ContextRing(progress = progress"),
        )
        assertTrue(
            "it reads the `Usage` cell of the same snapshot the tab renders",
            panel.contains("SessionContextModel.Kind.USAGE"),
        )
        // leading slot: label still renders, indicator sits in front of it
        assertTrue(panel.contains("leading: (@Composable () -> Unit)? = null"))
        assertTrue(panel.contains("Arrangement.spacedBy(8.dp)"))
    }

    /** No new user-visible copy was introduced by any of the three fixes. */
    @Test
    fun noNewTabCopyWasIntroduced() {
        val panel = reviewPanel()
        // the strip still resolves the three pre-existing string resources
        assertTrue(panel.contains("R.string.oc_review_tab_changes"))
        assertTrue(panel.contains("R.string.oc_review_tab_context"))
        assertTrue(panel.contains("R.string.oc_review_tab_files"))
    }
}
