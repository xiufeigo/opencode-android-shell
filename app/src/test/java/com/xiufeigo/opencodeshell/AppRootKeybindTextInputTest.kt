package com.xiufeigo.opencodeshell

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import com.xiufeigo.opencodeshell.ui.GlobalChord
import com.xiufeigo.opencodeshell.ui.globalChord
import com.xiufeigo.opencodeshell.ui.rootChordYieldsToTextInput
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Upstream `handleKeyDown` (`command.tsx:405-411`): a shortcut typed into an
 * editable target is skipped only when it is none of the palette chord, an
 * `EDITABLE_KEYBIND_IDS` id, a modified (`ctrl`/`meta`/`alt`) chord, Tab.
 * `GlobalKeybindFocus` is a root `onPreviewKeyEvent`, so it runs **before**
 * the focused `TextField` and its `true` ends dispatch — the field never gets
 * a chance to take the key, which is exactly what a modified chord wants.
 *
 * [rootChordYieldsToTextInput] is the stand-down rule, as a pure function.
 */
class AppRootKeybindTextInputTest {

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

    /** A recorded override on the bare letter `E` — the drawer case. */
    private val bareLetter = mapOf("session.new" to "E")

    // ------------------------------------------------------------- not typing

    @Test
    fun withNoFieldFocusedEveryChordStillFires() {
        assertFalse(
            rootChordYieldsToTextInput(
                chord = GlobalChord.PALETTE,
                key = Key.K,
                ctrl = true,
                textInputActive = false,
            ),
        )
        assertFalse(
            "a bare-letter override keeps working outside a field",
            rootChordYieldsToTextInput(
                chord = chord(Key.E, keybinds = bareLetter),
                key = Key.E,
                textInputActive = false,
            ),
        )
    }

    // ------------------------------------------------------------- while typing

    @Test
    fun aModifierChordFiresWhileTyping() {
        // Upstream `modified` (`command.tsx:407`): a `Ctrl`+<letter> typed into
        // a field is the user's explicit chord, not text — `Ctrl+N` opens a
        // session mid-word on the desktop too. These are the real default
        // bindings — `Ctrl+N`, `Ctrl+K`, `Ctrl+P`, `Ctrl+F`, `Ctrl+,`.
        listOf(
            Key.N to GlobalChord.NEW_SESSION,
            Key.K to GlobalChord.PALETTE,
            Key.P to GlobalChord.PALETTE_FILES,
            Key.F to GlobalChord.SESSION_SEARCH,
            Key.Comma to GlobalChord.SETTINGS,
        ).forEach { (key, expected) ->
            val chord = chord(key, ctrl = true)
            assertTrue("ctrl+… is a real chord", expected == chord)
            assertFalse(
                "a modified chord fires while typing",
                rootChordYieldsToTextInput(
                    chord = chord,
                    key = key,
                    ctrl = true,
                    textInputActive = true,
                ),
            )
        }
    }

    @Test
    fun thePaletteSecondChordFiresWhileTyping() {
        // `Ctrl+Shift+P` is the palette's second default (`command.palette`);
        // the `isPalette` carve-out covers it, not just `Ctrl+K`.
        assertTrue(chord(Key.P, ctrl = true, shift = true) == GlobalChord.PALETTE)
        assertFalse(
            rootChordYieldsToTextInput(
                chord = GlobalChord.PALETTE,
                key = Key.P,
                ctrl = true,
                shift = true,
                textInputActive = true,
            ),
        )
    }

    @Test
    fun editableIdChordsFireWhileTyping() {
        // Upstream `EDITABLE_KEYBIND_IDS` (`command.tsx:24`).
        listOf(
            Key.Grave to GlobalChord.TERMINAL,
            Key.U to GlobalChord.FILE_ATTACH,
            Key.F5 to GlobalChord.BROWSER_RELOAD,
        ).forEach { (key, expected) ->
            val chord = if (key == Key.F5) chord(key) else chord(key, ctrl = true)
            assertTrue("the binding is a real chord", expected == chord)
            assertFalse(
                "an editable id fires while typing",
                rootChordYieldsToTextInput(
                    chord = chord,
                    key = key,
                    ctrl = key != Key.F5,
                    textInputActive = true,
                ),
            )
        }
    }

    @Test
    fun shiftAloneNeverUnYields() {
        // Upstream `modified` is `ctrl`/`meta`/`alt` — a bare `shift` is not
        // one, so a shift-only chord still belongs to the field.
        val keybinds = mapOf("session.new" to "Shift+N")
        val chord = chord(Key.N, shift = true, keybinds = keybinds)
        assertTrue("shift+n is a real chord", GlobalChord.NEW_SESSION == chord)
        assertTrue(
            rootChordYieldsToTextInput(
                chord = chord,
                key = Key.N,
                shift = true,
                textInputActive = true,
            ),
        )
    }

    @Test
    fun tabFiresWhileTyping() {
        // Upstream `!isTab` (`command.tsx:408`): Tab is never skipped, so a
        // Tab-bound row keeps working out of a field.
        assertFalse(
            rootChordYieldsToTextInput(
                chord = GlobalChord.NEW_SESSION,
                key = Key.Tab,
                textInputActive = true,
            ),
        )
    }

    @Test
    fun aBareLetterBindingIsGivenToTheField() {
        // The `E` drawer case: an override on a letter, with no modifier at all.
        val chord = chord(Key.E, keybinds = bareLetter)
        assertTrue("the override really is a chord", GlobalChord.NEW_SESSION == chord)
        assertTrue(
            rootChordYieldsToTextInput(
                chord = chord,
                key = Key.E,
                textInputActive = true,
            ),
        )
    }

    @Test
    fun aModifiedEditingKeyFiresWhileTyping() {
        // `Ctrl+,` is `settings.open`; upstream fires it out of the field like
        // any other modified chord (`command.tsx:407`).
        assertFalse(
            rootChordYieldsToTextInput(
                chord = chord(Key.Comma, ctrl = true),
                key = Key.Comma,
                ctrl = true,
                textInputActive = true,
            ),
        )
    }

    @Test
    fun aNonTextKeyBindingStillFiresWhileTyping() {
        // A binding on a key no text field claims is not the field's business,
        // so the shell keeps it — the carve-out that stops the stand-down from
        // disabling the whole table.
        assertFalse(
            rootChordYieldsToTextInput(
                chord = chord(Key.F5),
                key = Key.F5,
                textInputActive = true,
            ),
        )
    }

    @Test
    fun anUnmatchedKeyIsNeverHeldBack() {
        // Not a chord → the root returns false anyway; the predicate must not
        // claim otherwise (it is also read on the null path in tests).
        assertFalse(
            rootChordYieldsToTextInput(
                chord = chord(Key.Z, ctrl = true),
                key = Key.Z,
                ctrl = true,
                textInputActive = true,
            ),
        )
    }
}
