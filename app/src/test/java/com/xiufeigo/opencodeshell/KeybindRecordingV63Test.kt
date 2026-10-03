package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.settings.NON_NAMEABLE_KEY_NAMES
import com.xiufeigo.opencodeshell.ui.settings.formatKeybindChord
import com.xiufeigo.opencodeshell.ui.settings.isNameableKeyToken
import com.xiufeigo.opencodeshell.ui.settings.keybindSignatures
import com.xiufeigo.opencodeshell.ui.settings.normalizeKeyName
import com.xiufeigo.opencodeshell.ui.settings.recordedKeyToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v63 桶F-3: a recorded chord names its key.
 *
 * Every combination the Shortcuts page was handed came out `UNKNOWN` and was
 * written over the row's real default (`research/v63_verify/5560/59_key_edit.png`
 * → `60_key_set.png`): the recording read the key's name out of Compose's
 * `Key` enum (`event.key.keyCode`), which answers 0 for a press the platform
 * dispatched without a key code, and `keyCodeToString(0)` spells
 * `KEYCODE_UNKNOWN`.
 *
 * Two contracts are pinned here: a nameable press produces its key name
 * (`Ctrl+Alt+M`, `Ctrl+K`, `F1`), and a press that names nothing produces
 * `null` — the caller then keeps waiting and writes nothing, exactly as it
 * does for a bare modifier (upstream `keybinds.tsx:277-283`).
 */
class KeybindRecordingV63Test {

    @Test
    fun aLetterPressIsNamedFromThePlatformEvent() {
        assertEquals("M", recordedKeyToken(nativeKeyName = "KEYCODE_M"))
        assertEquals("K", recordedKeyToken(nativeKeyName = "KEYCODE_K"))
        assertEquals("A", recordedKeyToken(nativeKeyName = "KEYCODE_A"))
    }

    @Test
    fun functionKeysAreNamed() {
        assertEquals("F1", recordedKeyToken(nativeKeyName = "KEYCODE_F1"))
        assertEquals("F12", recordedKeyToken(nativeKeyName = "KEYCODE_F12"))
    }

    @Test
    fun punctuationKeepsItsPrintedSpelling() {
        assertEquals(",", recordedKeyToken(nativeKeyName = "KEYCODE_COMMA"))
        assertEquals("\\", recordedKeyToken(nativeKeyName = "KEYCODE_BACKSLASH"))
        assertEquals("`", recordedKeyToken(nativeKeyName = "KEYCODE_GRAVE"))
    }

    @Test
    fun theRecordedChordReadsLikeTheDefaultTable() {
        val name = recordedKeyToken(nativeKeyName = "KEYCODE_M")!!
        assertEquals("Ctrl+Alt+M", formatKeybindChord(ctrl = true, alt = true, shift = false, meta = false, key = name))
        val k = recordedKeyToken(nativeKeyName = "KEYCODE_K")!!
        assertEquals("Ctrl+K", formatKeybindChord(ctrl = true, alt = false, shift = false, meta = false, key = k))
        assertEquals(listOf("ctrl+k"), keybindSignatures("Ctrl+K"))
    }

    @Test
    fun anUnknownKeyCodeIsRefusedRatherThanStored() {
        assertNull(recordedKeyToken(nativeKeyName = "KEYCODE_UNKNOWN"))
        assertNull(recordedKeyToken(nativeKeyName = "KEYCODE_UNKNOWN", composeKeyName = "Unknown"))
    }

    @Test
    fun everyNonNameableNameIsRefused() {
        for (name in NON_NAMEABLE_KEY_NAMES) {
            assertNull(name, recordedKeyToken(nativeKeyName = "KEYCODE_$name"))
            assertFalse(name, isNameableKeyToken(name))
        }
    }

    @Test
    fun aBareModifierOrAbsentPressNamesNothing() {
        assertNull(recordedKeyToken(nativeKeyName = "KEYCODE_SHIFT_LEFT"))
        assertNull(recordedKeyToken(nativeKeyName = "KEYCODE_CTRL_LEFT"))
        assertNull(recordedKeyToken(nativeKeyName = "KEYCODE_META_LEFT"))
        assertNull(recordedKeyToken(nativeKeyName = null, composeKeyName = null))
        assertNull(recordedKeyToken(nativeKeyName = "  "))
    }

    @Test
    fun theComposeEnumIsTheSecondSource() {
        assertEquals("K", recordedKeyToken(composeKeyName = "K"))
        assertEquals("F1", recordedKeyToken(composeKeyName = "F1"))
        assertEquals(",", recordedKeyToken(composeKeyName = "Comma"))
        assertEquals("Enter", recordedKeyToken(composeKeyName = "Enter"))
    }

    @Test
    fun aTypedCharacterIsTheLastResort() {
        assertEquals("m", recordedKeyToken(unicodeChar = 'm'))
        assertEquals("/", recordedKeyToken(unicodeChar = '/'))
        assertNull(recordedKeyToken(unicodeChar = '\n'))
    }

    @Test
    fun theGateRejectsPlaceholdersAndBlanks() {
        assertFalse(isNameableKeyToken(null))
        assertFalse(isNameableKeyToken(""))
        assertFalse(isNameableKeyToken("   "))
        assertFalse(isNameableKeyToken("KEY CODE"))
        assertTrue(isNameableKeyToken("Ctrl"))
        assertTrue(isNameableKeyToken("F1"))
    }

    @Test
    fun normalizeKeepsItsAndroidToWebSpelling() {
        assertEquals("Backspace", normalizeKeyName("KEYCODE_DEL"))
        assertEquals("Delete", normalizeKeyName("KEYCODE_FORWARD_DEL"))
        assertEquals("ArrowUp", normalizeKeyName("KEYCODE_DPAD_UP"))
        assertNull(normalizeKeyName("KEYCODE_CAPS_LOCK"))
    }
}
