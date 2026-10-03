package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.drawer.SessionTimeLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A-3's last surface: the Workspaces settings page's row label
 * (`WorkspaceRelativeTime`, `workspaces.tsx:167-173` `lastActive`).
 *
 * The label read `System.currentTimeMillis()` **inside** a `remember` — a read
 * Compose cannot see — so a row whose only moving input is time was skipped and
 * a label composed while the newest linked session was「just now」kept reading
 * 「just now」for as long as the page stayed open. The palette's meta cell, the
 * drawer's rows and Home's session rows each froze the same way and each was
 * fixed by injecting a clock the host ticks while the surface is visible
 * ([PaletteRelativeTimeTest], [SessionDrawerFreshnessTest],
 * [HomeSessionRowTimeTest]); this is the fourth and last one.
 *
 * The bucketing is behavioural (it is the drawer's own [SessionTimeLabel]), the
 * wiring that the JVM cannot exercise — the ticking clock and the tab gate — is
 * guarded at source level.
 */
class WorkspaceRelativeTimeTest {

    /** A `now` that is deliberately nowhere near the wall clock. */
    private val updatedAt = 1_700_000_000_000L

    // ---------------------------------------------------------- the bucketing

    @Test
    fun `the label is computed from the injected clock, not the wall clock`() {
        // The fixture sits years before `System.currentTimeMillis()`, so a label
        // that read the wall clock instead of its `now` parameter would bucket
        // this row as hundreds of days, never as minutes.
        assertTrue(System.currentTimeMillis() - updatedAt > 24L * 3_600_000L)

        assertEquals(
            SessionTimeLabel.Relative(SessionTimeLabel.Unit.JUST_NOW, 1),
            SessionTimeLabel.relative(updatedAt, updatedAt + 30_000L),
        )
        assertEquals(
            SessionTimeLabel.Relative(SessionTimeLabel.Unit.MINUTES, 5),
            SessionTimeLabel.relative(updatedAt, updatedAt + 5L * 60_000L + 1_000L),
        )
        assertEquals(
            SessionTimeLabel.Relative(SessionTimeLabel.Unit.HOURS, 3),
            SessionTimeLabel.relative(updatedAt, updatedAt + 3L * 3_600_000L),
        )
    }

    @Test
    fun `an unknown timestamp renders no label`() {
        // `WorkspaceRelativeTime` returns before composing anything.
        assertNull(SessionTimeLabel.relative(0L, updatedAt))
        assertNull(SessionTimeLabel.relative(-1L, updatedAt))
    }

    // ------------------------------------------------------------- the wiring

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("missing $relative")

    private fun panels() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/settings/SettingsPanels.kt")

    private fun bodyOf(text: String, signature: String, span: Int = 900): String {
        val at = text.indexOf(signature)
        assertTrue("missing $signature", at >= 0)
        return text.substring(at, minOf(text.length, at + span))
    }

    @Test
    fun `the panel drives a clock that ticks while it is on screen`() {
        val panels = panels()

        assertTrue(
            "the label takes the panel's clock",
            panels.contains("WorkspaceRelativeTime(updatedAt = updatedAt, now = now)"),
        )
        assertTrue(panels.contains("var workspacesNow by remember { mutableStateOf(System.currentTimeMillis()) }"))
        assertTrue(
            "and it re-measures on a loop, not once per composition",
            panels.contains("while (true) {"),
        )
        assertTrue(panels.contains("workspacesNow = System.currentTimeMillis()"))
        assertTrue(panels.contains("delay(WORKSPACES_CLOCK_TICK_MS)"))
        assertTrue(panels.contains("private const val WORKSPACES_CLOCK_TICK_MS = 30_000L"))
        // The row is handed the same value, and the row's parameter is not
        // defaulted: a clock read anywhere inside would be invisible again.
        assertTrue(panels.contains("now = workspacesNow,"))
        assertTrue(panels.contains("now: Long,"))
    }

    @Test
    fun `the tick lives inside the panel, so its lifetime is the tab's`() {
        val panels = panels()

        // `PanelSlot`'s `when (tab)` composes exactly one panel, so being in
        // `WorktreesPanel` *is* the visible gate: switching tabs or closing the
        // settings dialog leaves composition and cancels the loop.
        val panelAt = panels.indexOf("fun WorktreesPanel(")
        val clockAt = panels.indexOf("var workspacesNow by remember")
        val labelAt = panels.indexOf("private fun WorkspaceRelativeTime(")
        assertTrue("the panel must declare the clock", panelAt >= 0 && clockAt > panelAt)
        assertTrue("the clock must stay inside the panel composable", labelAt > clockAt)

        val screen = source("src/main/java/com/xiufeigo/opencodeshell/ui/settings/SettingsScreen.kt")
        assertTrue(screen.contains("when (tab) {"))
        assertTrue(screen.contains("SettingsTab.WORKSPACES -> WorktreesPanel(serverData)"))
    }

    @Test
    fun `no clock is read inside the label itself`() {
        val panels = panels()
        val label = bodyOf(panels, "private fun WorkspaceRelativeTime(updatedAt: Long, now: Long)")

        // The parameter is what the label measures against…
        assertTrue(label.contains("SessionTimeLabel.relative(updatedAt, now)"))
        assertFalse(label.contains("currentTimeMillis"))
        // …and the surface it draws is the shared bucketing, not a second one.
        assertTrue(label.contains("R.string.oc_time_just_now"))
        assertTrue(label.contains("R.plurals.oc_time_minutes_ago"))
    }

    // ------------------------------------------------------------- the dialogs

    /**
     * v30 BUG-2 (真机 tA_27/tA_28): both delete confirms were emitted as bare
     * siblings of the page inside `PanelSlot`'s `Box`, so they painted their
     * three body lines straight over the panel — no surface, no scrim, the
     * warning stacked on the section heading. They are dialog **bodies**
     * (`McpAddDialog`'s shape) and belong in `SettingsModalOverlay`, the layer
     * every other settings modal mounts through.
     */
    @Test
    fun `the worktree delete confirms mount in the modal layer`() {
        val panels = panels()
        val worktrees = panels
            .substringAfter("fun WorktreesPanel(")
            .substringBefore("/** The two bulk actions")
        assertTrue("the row confirm is mounted", worktrees.contains("WorktreeDeleteDialog("))
        assertTrue("the bulk confirm is mounted", worktrees.contains("BulkWorktreeDeleteDialog("))
        assertTrue(
            "each confirm sits in the modal layer, not in the page's flow",
            Regex("""SettingsModalOverlay\(""").findAll(worktrees).count() >= 2,
        )
    }
}
