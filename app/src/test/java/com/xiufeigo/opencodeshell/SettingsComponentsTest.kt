package com.xiufeigo.opencodeshell

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `SettingsComponents.kt`'s two hardening fixes, pinned as source facts (the
 * components are `@Composable` functions a plain JVM test cannot call — the
 * same split `SettingsPanelScrollTest` uses).
 *
 * 1. `OcSwitch` with `enabled = false` used to render exactly like an enabled
 *    one: `McpChooserDialog`'s pending/busy rows looked tappable while their
 *    writes were in flight. The disabled knob now dims as a whole and drops to
 *    a faint token; `enabled = true` renders exactly as before.
 * 2. `OcTextField`'s `autoCorrect` defaulted to `true`, so all 27 settings
 *    fields (URLs, API keys, ports, shell paths, credential labels, search
 *    boxes — literals or identifiers the app keeps verbatim) ate the default
 *    and let the IME rewrite them. The default is now off; an explicit `true`
 *    still reaches the IME untouched.
 */
class SettingsComponentsTest {

    private fun components(): String {
        val file = File("src/main/java/com/xiufeigo/opencodeshell/ui/settings/SettingsComponents.kt")
            .takeIf { it.isFile }
            ?: File("app/src/main/java/com/xiufeigo/opencodeshell/ui/settings/SettingsComponents.kt")
        assertTrue("missing source: SettingsComponents.kt", file.isFile)
        return file.readText()
    }

    /** [text] from [from] up to (excluding) the next [to]. */
    private fun window(text: String, from: String, to: String): String {
        val start = text.indexOf(from)
        assertTrue("$from is gone", start >= 0)
        val end = text.indexOf(to, start + from.length)
        assertTrue("$to is gone", end > start)
        return text.substring(start, end)
    }

    // ------------------------------------------------------- (1) OcSwitch

    @Test
    fun disabledSwitchDimsAsAWhole() {
        assertTrue(components().contains("import androidx.compose.ui.draw.alpha"))
        val switch = window(components(), "fun OcSwitch(", "KeybindChip")
        // The same 0.5 dim factor the dock submit buttons, the terminal icons
        // and the review chips use for their disabled state.
        assertTrue(switch.contains(".alpha(if (enabled) 1f else 0.5f)"))
    }

    @Test
    fun disabledSwitchDesaturatesTheKnobWhileEnabledRendersAsBefore() {
        val switch = window(components(), "fun OcSwitch(", "KeybindChip")
        // Desaturation: the track/knob drop to the same tokens the other
        // disabled controls use (`OcButton`'s primary `layer03`, `OcSelect`'s
        // value `textFaint`).
        assertTrue(switch.contains("val dimmedTrack = if (enabled) track else c.layer03"))
        assertTrue(switch.contains("val dimmedKnob = if (enabled) knob else c.textFaint"))
        // `enabled = true` is untouched: the same track / knob expressions.
        assertTrue(switch.contains("track = if (checked) c.accent else c.layer03"))
        assertTrue(switch.contains("if (checked) c.textInverse else c.textMuted"))
        // Still a real toggleable — the dim is visual only, never behavioural.
        assertTrue(switch.contains("enabled = enabled"))
    }

    // ---------------------------------------------------- (2) OcTextField

    @Test
    fun autoCorrectDefaultsOff() {
        val field = window(components(), "fun OcTextField(", "fun OcSwitch(")
        assertTrue(field.contains("autoCorrect: Boolean = false,"))
    }

    @Test
    fun explicitAutoCorrectTrueStillReachesTheIme() {
        // A free-prose field opting back in with `autoCorrect = true` must be
        // honoured: the flag is threaded into `KeyboardOptions`, never
        // hardcoded at the wiring site.
        val field = window(components(), "fun OcTextField(", "fun OcSwitch(")
        assertTrue(field.contains("autoCorrectEnabled = autoCorrect,"))
        assertFalse(field.contains("autoCorrectEnabled = false"))
    }
}
