package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.palette.PaletteEntry
import com.xiufeigo.opencodeshell.ui.palette.fuzzySubsequence
import com.xiufeigo.opencodeshell.ui.palette.matchesEntry
import com.xiufeigo.opencodeshell.ui.palette.matchesTerm
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v63 桶F-2: the palette search is fuzzy, so 「trm」 reaches *Toggle terminal*.
 *
 * Upstream's own palette filter is a plain `includes`
 * (`shell/commands/dialog.tsx:26-28`), which the shell already widens with a
 * command's id words and its English source copy. Neither reaches an
 * abbreviation: `trm` matched nothing on device
 * (`research/v63_verify/5556/29_palette_fuzzy.png` → `31_palette_term2.png`).
 * The desktop keybinds page filters its rows through `fuzzysort`
 * (`settings/keybinds/keybinds.tsx:183-186`), so the subsequence reading is the
 * one the product already answers to elsewhere.
 */
class PaletteFuzzySearchV63Test {

    private val terminal = PaletteEntry.command(
        id = "terminal.toggle",
        title = "Toggle terminal",
        description = "Show or hide the terminal panel",
        searchTerms = listOf("Show or hide the terminal panel"),
    )

    @Test
    fun anAbbreviationReachesTheTerminalCommand() {
        assertTrue(matchesEntry(terminal, "trm"))
    }

    @Test
    fun anAbbreviationReachesItOnATranslatedDeviceToo() {
        val zh = terminal.copy(title = "切换终端", searchTerms = emptyList())
        // The English source copy stays in the haystack, so the upstream name
        // still answers on a Chinese UI — that is what the row is found by.
        assertTrue(matchesEntry(zh, "trm"))
    }

    @Test
    fun subsequenceAcceptsTheAbbreviatedSpelling() {
        assertTrue(fuzzySubsequence("trm", "toggle terminal"))
        assertTrue(fuzzySubsequence("tt", "toggle terminal"))
    }

    @Test
    fun subsequenceRejectsOutOfOrderLetters() {
        assertFalse(fuzzySubsequence("mt", "toggle terminal"))
        assertFalse(fuzzySubsequence("trmz", "toggle terminal"))
    }

    @Test
    fun anEmptyQueryMatchesEverything() {
        assertTrue(matchesTerm("", "Toggle terminal"))
        assertTrue(matchesEntry(terminal, "   "))
    }

    @Test
    fun theSubstringRuleIsUnchanged() {
        assertTrue(matchesEntry(terminal, "toggle"))
        assertTrue(matchesEntry(terminal, "TOGGLE TERMINAL"))
        assertTrue(matchesEntry(terminal, "terminal panel"))
    }

    @Test
    fun fuzzyIsStrictlyWiderThanContains() {
        val haystacks = listOf("toggle terminal", "session previous", "open settings", "a", "")
        for (candidate in listOf("tog", "trm", "sess", "open", "set", "zz", "q")) {
            for (hay in haystacks) {
                if (hay.contains(candidate)) {
                    assertTrue("$candidate ⊂ $hay", matchesTerm(candidate, hay))
                }
            }
        }
    }
}
