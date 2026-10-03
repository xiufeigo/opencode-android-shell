package com.xiufeigo.opencodeshell

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import com.xiufeigo.opencodeshell.ui.GlobalChord
import com.xiufeigo.opencodeshell.ui.bindingForSignatures
import com.xiufeigo.opencodeshell.ui.defaultGlobalChords
import com.xiufeigo.opencodeshell.ui.effectiveGlobalChords
import com.xiufeigo.opencodeshell.ui.globalChord
import com.xiufeigo.opencodeshell.ui.rootChordYieldsToTextInput
import com.xiufeigo.opencodeshell.ui.settings.SHORTCUT_ROWS
import com.xiufeigo.opencodeshell.ui.settings.WIRED_SHORTCUT_IDS
import com.xiufeigo.opencodeshell.ui.settings.keybindSignatures
import com.xiufeigo.opencodeshell.ui.settings.recordedKeyToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v82 「Ctrl+K 打开模型选择器而非命令面板」 — the chord audit.
 *
 * The report came from `research/v82_verify/5556/57_ctrlk1.png` +
 * `research/v82_verify/5554/23_ctrlk_correct.png`, and the tester's own command
 * log names what was really pressed: the 5556 run used
 * `adb shell input keycombination 113 75` for "Ctrl+K", the 5554 run
 * `input keycombination 113 42` (`agent-770` / `agent-771` wire logs). Against
 * the installed SDK sources (`sources/android-36.1/android/view/KeyEvent.java`):
 *
 * - `KEYCODE_CTRL_LEFT = 113` — right on both;
 * - `KEYCODE_K = 39` — **never sent**;
 * - `KEYCODE_APOSTROPHE = 75` — what 5556 actually pressed, i.e. `Ctrl+'`, the
 *   documented default of `model.choose` (`SHORTCUT_ROWS`: 「Choose model =
 *   Ctrl+'」), which is why the model picker opened — correctly;
 * - `KEYCODE_N = 42` — what 5554 pressed, i.e. `Ctrl+N` = `tab.new`, which is
 *   why that run showed a new-session draft.
 *
 * So the mapping was never broken; this class pins that it stays right, and that
 * the Shortcuts page's table is the handler's table — the audit the report asked
 * for alongside the fix.
 */
class ChordPaletteReachabilityV82Test {

    private fun chord(
        key: Key,
        ctrl: Boolean = false,
        shift: Boolean = false,
        alt: Boolean = false,
        keybinds: Map<String, String> = emptyMap(),
    ) = globalChord(
        type = KeyEventType.KeyDown,
        key = key,
        ctrl = ctrl,
        meta = false,
        shift = shift,
        alt = alt,
        keybinds = keybinds,
    )

    private fun source(relative: String): String =
        listOf("app/$relative", relative)
            .map { File(it) }
            .firstOrNull { it.isFile }
            ?.readText()?.replace("\r\n", "\n")
            ?: error("missing $relative")

    // ------------------------------------------------- the palette's own chords

    @Test
    fun ctrlKAndCtrlShiftPOpenThePalette() {
        // The two chords the Shortcuts page advertises for「Command palette」
        // (`SHORTCUT_ROWS`: `Ctrl+K / Ctrl+Shift+P`), with the override table the
        // devices really had (`oc_settings.xml`: no `opencode.android.keybinds`
        // key on 5556, `{}` on 5554) — i.e. no overrides at all.
        assertEquals(GlobalChord.PALETTE, chord(Key.K, ctrl = true))
        assertEquals(GlobalChord.PALETTE, chord(Key.P, ctrl = true, shift = true))
        assertEquals("command.palette", GlobalChord.PALETTE.commandId)
    }

    @Test
    fun thePaletteChordsAreNeverHeldBackWhileTyping() {
        // Upstream `handleKeyDown` (`command.tsx:405-411`) checks `isPalette`
        // before the editable-target stand-down, so `Ctrl+K` opens the palette
        // from the composer too — the「输入态 Ctrl+K 开面板」deviation the shell
        // already documents.
        for (key in listOf(Key.K, Key.P)) {
            val shift = key == Key.P
            val resolved = chord(key, ctrl = true, shift = shift)
            assertEquals(GlobalChord.PALETTE, resolved)
            assertFalse(
                rootChordYieldsToTextInput(
                    chord = resolved,
                    key = key,
                    ctrl = true,
                    shift = shift,
                    textInputActive = true,
                ),
            )
        }
    }

    @Test
    fun aChordTheHarnessCouldHaveSentInsteadIsTheModelPicker() {
        // The evidence chain in one place: the app's own recorder names the two
        // key codes the harness used, and the table maps them where the devices
        // showed them going.
        assertEquals("K", recordedKeyToken(nativeKeyName = "KEYCODE_K"))
        assertEquals("'", recordedKeyToken(nativeKeyName = "KEYCODE_APOSTROPHE"))
        // `input keycombination 113 75` — Ctrl+`'` — is `model.choose`'s default.
        assertEquals(GlobalChord.MODEL_CHOOSE, chord(Key.Apostrophe, ctrl = true))
        assertEquals("model.choose", GlobalChord.MODEL_CHOOSE.commandId)
        // `input keycombination 113 42` — Ctrl+N — is `tab.new`.
        assertEquals(GlobalChord.NEW_SESSION, chord(Key.N, ctrl = true))
        assertEquals("Ctrl+'", SHORTCUT_ROWS.first { it.id == "model.choose" }.keybind)
    }

    // ---------------------------------------------- Shortcuts page ↔ chord table

    /**
     * The ids `ui/App.kt`'s `GLOBAL_CHORD_COMMAND_IDS` maps onto a handler, read
     * from the source because the map is file-private. Every entry is one row of
     * the settings table, so the two can be compared without a third list.
     */
    private fun chordTable(): Map<String, String> {
        val app = source("src/main/java/com/xiufeigo/opencodeshell/ui/App.kt")
        val start = app.indexOf("private val GLOBAL_CHORD_COMMAND_IDS")
        assertTrue("GLOBAL_CHORD_COMMAND_IDS must exist", start >= 0)
        val block = app.substring(start, app.indexOf("private val EXTRA_GLOBAL_CHORDS", start))
        val table = Regex("\"([^\"]+)\"\\s+to\\s+GlobalChord\\.([A-Z_]+)")
            .findAll(block)
            .associate { it.groupValues[1] to it.groupValues[2] }
        assertTrue("the chord table must not be empty", table.size > 20)
        return table
    }

    @Test
    fun everyShortcutRowAdvertisesTheHandlerItIsWiredTo() {
        val table = chordTable()
        val effective = effectiveGlobalChords()
        val defaults = defaultGlobalChords()
        val owners = HashMap<String, String>()

        for ((id, member) in table) {
            val expected = GlobalChord.valueOf(member)
            val row = SHORTCUT_ROWS.firstOrNull { it.id == id }
            // Every wired id is either a rebindable row or one of the documented
            // context bindings (`EXTRA_GLOBAL_CHORDS`: `session.search`,
            // `session.summary.toggle`, the two browser rows).
            val binding = row?.keybind ?: defaults[id]
            assertNotNull("$id must have a default binding", binding)

            for (signature in keybindSignatures(bindingForSignatures(binding!!))) {
                assertEquals(
                    "$id advertises $signature, so it must reach $member",
                    expected,
                    effective[signature],
                )
                val previous = owners.put(signature, id)
                assertTrue(
                    "$signature is advertised by both $previous and $id — one of them " +
                        "would silently shadow the other",
                    previous == null || previous == id,
                )
            }
        }

        // …and the other direction: a row the panel shows but the table does not
        // carry would be「能改但无效」.
        for (row in SHORTCUT_ROWS) {
            assertTrue("the table must carry ${row.id}", table.containsKey(row.id))
        }
    }

    @Test
    fun theShortcutRowsAndTheWiredIdSetStayInStep() {
        // The panel's rows are the handler's ids: `SettingsStructureTest` pins the
        // set, this pins it against the chord table read above.
        val table = chordTable()
        assertEquals(SHORTCUT_ROWS.map { it.id }.toSet(), WIRED_SHORTCUT_IDS)
        for (id in table.keys) {
            assertTrue(
                "$id is wired but neither a row nor an EXTRA binding",
                SHORTCUT_ROWS.any { it.id == id } || defaultGlobalChords().containsKey(id),
            )
        }
    }
}
