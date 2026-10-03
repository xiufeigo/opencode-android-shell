package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.composer.ComposerSuggestions
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v63 桶F-1: a `/` in the composer opens the command menu.
 *
 * The composer's placeholder promises 「/ for commands」
 * (`oc_composer_placeholder`), yet the first `/` a person typed opened nothing:
 * the panel's visibility rode on the mirrored [slashQuery] state, which an IME
 * commit can leave describing a value the editor no longer holds, so the
 * command menu was unreachable and ⋮ → Command palette was the only way in
 * (`research/v63_verify/5556/25_palette3.png` → `26_palette4.png`).
 *
 * The trigger is re-read from the text itself
 * ([ComposerSuggestions.isCommandTrigger]), so every shape of `/` opens it.
 */
class SlashTriggerPanelV63Test {

    @Test
    fun theFirstSlashIsInCommandPosition() {
        assertTrue(ComposerSuggestions.isCommandTrigger("/"))
    }

    @Test
    fun aSlashQueryKeepsTheMenuOpen() {
        assertTrue(ComposerSuggestions.isCommandTrigger("/model"))
        assertTrue(ComposerSuggestions.isCommandTrigger("/btw"))
    }

    @Test
    fun aFullWidthSlashIsTheSameTrigger() {
        assertTrue(ComposerSuggestions.isCommandTrigger("／"))
        assertTrue(ComposerSuggestions.isCommandTrigger("／ｍｏｄｅｌ"))
    }

    @Test
    fun ordinaryProseIsNotCommandPosition() {
        assertFalse(ComposerSuggestions.isCommandTrigger(""))
        assertFalse(ComposerSuggestions.isCommandTrigger("hello"))
        assertFalse(ComposerSuggestions.isCommandTrigger("1/2"))
        assertFalse(ComposerSuggestions.isCommandTrigger("hello / world"))
    }

    @Test
    fun aSlashFollowedByWhitespaceClosesTheMenuLikeUpstream() {
        // `/^\/(\S*)$/` — `machine.ts:107`: the whole value is one slash and no
        // whitespace, so a slash inside prose is not a trigger.
        assertFalse(ComposerSuggestions.isCommandTrigger("/model picker"))
        assertFalse(ComposerSuggestions.isCommandTrigger("/model "))
    }

    @Test
    fun theQueryIsTheTextAfterTheSlash() {
        assertTrue(ComposerSuggestions.commandQuery("/model") == "model")
        assertTrue(ComposerSuggestions.commandQuery("/") == "")
    }
}
