package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.settings.GENERAL_PANEL
import com.xiufeigo.opencodeshell.ui.settings.SettingsControl
import com.xiufeigo.opencodeshell.ui.settings.TimelineDetailKey
import com.xiufeigo.opencodeshell.ui.settings.TimelineDetailPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v63 (`research/v63_verify/5554/33-42`): the timeline-detail preset row used to
 * write all three timeline booleans, so picking a preset silently undid a switch
 * the user had just flipped by hand, and the chip — which re-derived the preset
 * from all three values — could then read `Custom` with no option marked.
 *
 * The preset therefore owns a key set, writes only that set, and is detected on
 * the same set, so the row and the chip can no longer disagree about what a
 * preset means. Pure, so it is JVM-tested; the two structural facts (the panel
 * writes through the owned keys, the select hands the list the trigger's width)
 * are pinned as source facts, the panels having no JVM-renderable surface.
 */
class SettingsTimelineDetailOwnershipTest {

    // ------------------------------------------------------------ the writes

    @Test
    fun aPresetWritesOnlyTheKeysItOwns() {
        // Everything and Compact decide the detail axis; neither may touch the
        // reasoning switch the user turned off by hand.
        for (preset in listOf(TimelineDetailPreset.EVERYTHING, TimelineDetailPreset.COMPACT)) {
            assertEquals(
                setOf(TimelineDetailKey.SHELL, TimelineDetailKey.EDIT),
                preset.ownedKeys,
            )
            assertEquals(
                setOf(TimelineDetailKey.SHELL, TimelineDetailKey.EDIT),
                preset.writes().keys,
            )
        }
        // Quiet is the only preset that hides reasoning, so it is the only one
        // that owns that key.
        assertEquals(TimelineDetailKey.entries.toSet(), TimelineDetailPreset.QUIET.ownedKeys)
        assertEquals(false, TimelineDetailPreset.QUIET.writes()[TimelineDetailKey.REASONING])
    }

    @Test
    fun theOwnedWritesAreThePresetsOwnValues() {
        assertEquals(
            mapOf(TimelineDetailKey.SHELL to true, TimelineDetailKey.EDIT to true),
            TimelineDetailPreset.EVERYTHING.writes(),
        )
        assertEquals(
            mapOf(TimelineDetailKey.SHELL to false, TimelineDetailKey.EDIT to false),
            TimelineDetailPreset.COMPACT.writes(),
        )
        assertEquals(
            mapOf(
                TimelineDetailKey.SHELL to false,
                TimelineDetailKey.EDIT to false,
                TimelineDetailKey.REASONING to false,
            ),
            TimelineDetailPreset.QUIET.writes(),
        )
    }

    @Test
    fun everyOwnedKeyNamesTheSwitchRowThatWritesIt() {
        // The panel applies a preset through these `data-action`s, so a key whose
        // action is not a rendered SWITCH row would write nothing.
        val switches = GENERAL_PANEL
            .flatMap { it.rows }
            .filter { it.control == SettingsControl.SWITCH }
            .map { it.action }
            .toSet()
        for (key in TimelineDetailKey.entries) {
            assertTrue("no SWITCH row writes ${key.name}", key.action in switches)
        }
    }

    // ---------------------------------------------------------- the detection

    @Test
    fun everyPresetIsDetectedFromItsOwnKeys() {
        assertEquals(
            TimelineDetailPreset.EVERYTHING,
            TimelineDetailPreset.of(shellExpanded = true, editExpanded = true, reasoningShown = true),
        )
        assertEquals(
            TimelineDetailPreset.COMPACT,
            TimelineDetailPreset.of(shellExpanded = false, editExpanded = false, reasoningShown = true),
        )
        assertEquals(
            TimelineDetailPreset.QUIET,
            TimelineDetailPreset.of(shellExpanded = false, editExpanded = false, reasoningShown = false),
        )
        // Shell and edit disagreeing is the only state no preset owns — it takes
        // both independent switches to reach it, and it is the `Custom` chip.
        assertNull(TimelineDetailPreset.of(shellExpanded = true, editExpanded = false, reasoningShown = true))
        assertNull(TimelineDetailPreset.of(shellExpanded = false, editExpanded = true, reasoningShown = false))
    }

    @Test
    fun aManuallyChangedSwitchDoesNotHideThePresetThatSetTheRest() {
        // Everything, then reasoning summaries off by hand: the preset row still
        // reads `Everything` (it owns shell/edit only), and picking it again
        // leaves reasoning alone instead of turning it back on.
        assertEquals(
            TimelineDetailPreset.EVERYTHING,
            TimelineDetailPreset.of(shellExpanded = true, editExpanded = true, reasoningShown = false),
        )
        assertFalse(
            TimelineDetailPreset.EVERYTHING.writes().containsKey(TimelineDetailKey.REASONING),
        )
        // The same for Compact after the user turned reasoning summaries off.
        assertEquals(
            TimelineDetailPreset.COMPACT,
            TimelineDetailPreset.of(shellExpanded = false, editExpanded = false, reasoningShown = true),
        )
    }

    @Test
    fun theChipAlwaysNamesAPresetForAnyStateTheRowItselfCanProduce() {
        // Every reachable triple except the two hand-built disagreements resolves
        // to a preset, so picking a preset can never leave the chip unmarked.
        for (shell in listOf(true, false)) {
            for (edit in listOf(true, false)) {
                for (reasoning in listOf(true, false)) {
                    if (shell != edit) continue
                    assertTrue(
                        "($shell,$edit,$reasoning) matched no preset",
                        TimelineDetailPreset.of(shell, edit, reasoning) != null,
                    )
                }
            }
        }
    }

    @Test
    fun theMostSpecificPresetWinsWhenTwoMatch() {
        // A fully collapsed, reasoning-free timeline satisfies Compact (it owns
        // the two expansion keys) *and* Quiet (which owns all three); Quiet is
        // the one that says it, so it is the chip.
        assertEquals(
            TimelineDetailPreset.QUIET,
            TimelineDetailPreset.of(shellExpanded = false, editExpanded = false, reasoningShown = false),
        )
        assertTrue(
            TimelineDetailPreset.QUIET.ownedKeys.size >
                TimelineDetailPreset.COMPACT.ownedKeys.size,
        )
    }

    // -------------------------------------------------------- the source pins

    private fun source(relative: String): String {
        val file = File(relative).takeIf { it.isFile } ?: File("app/$relative")
        return file.readText()
    }

    private fun window(text: String, from: String, to: String): String {
        val start = text.indexOf(from)
        assertTrue("$from is gone", start >= 0)
        val end = text.indexOf(to, start)
        assertTrue("$to is gone", end > start)
        return text.substring(start, end)
    }

    @Test
    fun thePanelAppliesAPresetThroughItsOwnedKeys() {
        val text = source("src/main/java/com/xiufeigo/opencodeshell/ui/settings/SettingsPanels.kt")
        val control = window(text, "private fun TimelineDetailControl(", "\nfun switchValue(")
        assertTrue(
            "the preset must be written through its owned keys",
            control.contains("preset.writes()") && control.contains("store.setSwitch(key.action, value)"),
        )
        assertFalse(
            "the preset row must not go through the all-three-key store write",
            control.contains("store.update(\"settings-timeline-detail\""),
        )
    }

    @Test
    fun theSelectListIsGivenTheTriggersMeasuredWidth() {
        val text = source("src/main/java/com/xiufeigo/opencodeshell/ui/settings/SettingsComponents.kt")
        assertTrue(
            "the trigger must report its width",
            text.contains("triggerWidthDp = width"),
        )
        val list = window(text, "private fun OcSelectList(", "fun OcTextField(")
        assertTrue(
            "the list must take the trigger's width when it is known",
            list.contains("triggerWidthDp > 0f -> Modifier.width(triggerWidthDp.dp)"),
        )
    }
}
