package com.xiufeigo.opencodeshell

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import com.xiufeigo.opencodeshell.ui.GlobalChord
import com.xiufeigo.opencodeshell.ui.chordSignatureToken
import com.xiufeigo.opencodeshell.ui.defaultGlobalChords
import com.xiufeigo.opencodeshell.ui.globalChord
import com.xiufeigo.opencodeshell.ui.palette.CATEGORY_FILE
import com.xiufeigo.opencodeshell.ui.palette.CATEGORY_SESSION
import com.xiufeigo.opencodeshell.ui.palette.CATEGORY_VIEW
import com.xiufeigo.opencodeshell.ui.palette.SessionCommands
import com.xiufeigo.opencodeshell.ui.settings.SHORTCUT_ROWS
import com.xiufeigo.opencodeshell.ui.settings.WIRED_SHORTCUT_IDS
import com.xiufeigo.opencodeshell.ui.settings.keybindSignatures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * B-18 / B-19: the five command entries this pair added, and the chords that
 * reach them.
 *
 * Upstream registers two more command groups the native shell had no entry
 * points for: `session.browser` (`browser.open` `mod+shift+b` —
 * `session/browser/model.ts:55-64`; `browser.reload` `f5` — `pane.tsx:45-50`)
 * and `composer-editor` (`file.attach` `mod+u`, `prompt.mode.shell`
 * `mod+shift+x`, `prompt.mode.normal` `mod+shift+e` —
 * `composer/model.ts:411-434`). Both the palette rows and the chords are
 * asserted here, because the actions live inside a `@Composable` no JVM test can
 * invoke.
 *
 * The deliberate split this test also pins: the three composer rows are
 * **settings rows** (rebindable, `SHORTCUT_ROWS` + `WIRED_SHORTCUT_IDS`), while
 * the two browser rows keep their defaults in `ui/App.kt`'s
 * `EXTRA_GLOBAL_CHORDS` and stay out of that table — the pane is a URL-driven
 * full-body overlay, not upstream's side-panel tab.
 */
class BrowserComposerCommandWiringTest {

    private fun source(relative: String): String {
        val file = File(relative).takeIf { it.isFile } ?: File("app/$relative")
        return file.readText()
    }

    private fun rows() = SessionCommands.sessionCommands(
        SessionCommands.SessionCommandActions(
            onOpenBrowser = {},
            onReloadBrowser = {},
            onAttachFile = {},
            onShellMode = {},
            onNormalMode = {},
        ),
    )

    // ------------------------------------------------------------- the rows

    @Test
    fun theFiveRowsCarryUpstreamsIdsCategoriesAndKeybinds() {
        val commands = rows()
        for (id in BROWSER_AND_COMPOSER_IDS) {
            assertTrue("$id must be registered", commands.any { it.id == id })
        }
        val browserOpen = commands.first { it.id == "browser.open" }
        assertEquals(R.string.command_browser_open, browserOpen.titleResId)
        assertEquals(CATEGORY_VIEW, browserOpen.category)
        assertEquals("Ctrl+Shift+B", browserOpen.keybind)

        val browserReload = commands.first { it.id == "browser.reload" }
        assertEquals(R.string.command_browser_reload, browserReload.titleResId)
        assertEquals(CATEGORY_VIEW, browserReload.category)
        assertEquals("F5", browserReload.keybind)

        val attach = commands.first { it.id == "file.attach" }
        assertEquals(R.string.prompt_action_attachFile, attach.titleResId)
        assertEquals(CATEGORY_FILE, attach.category)
        assertEquals("Ctrl+U", attach.keybind)

        val shell = commands.first { it.id == "prompt.mode.shell" }
        assertEquals(R.string.command_prompt_mode_shell, shell.titleResId)
        assertEquals(CATEGORY_SESSION, shell.category)
        assertEquals("Ctrl+Shift+X", shell.keybind)

        val normal = commands.first { it.id == "prompt.mode.normal" }
        assertEquals(R.string.command_prompt_mode_normal, normal.titleResId)
        assertEquals(CATEGORY_SESSION, normal.category)
        assertEquals("Ctrl+Shift+E", normal.keybind)

        // The QA anchor lists them, so the settings/palette coverage sets can
        // never drift from the registry.
        assertTrue(SessionCommands.SESSION_COMMAND_IDS.containsAll(BROWSER_AND_COMPOSER_IDS))
    }

    @Test
    fun aSilentHostGetsAStubInsteadOfAnInertRow() {
        // Unlike the host-callback rows (`session.aside`), these five register
        // unconditionally: their actions exist in the app, and a host without a
        // session screen (a preview) reports the press instead of dropping it.
        val reported = ArrayList<String>()
        val commands = SessionCommands.sessionCommands(
            stubReporter = SessionCommands.StubReporter { reported.add(it) },
        )
        for (id in BROWSER_AND_COMPOSER_IDS) {
            commands.first { it.id == id }.onSelect()
        }
        assertEquals(BROWSER_AND_COMPOSER_IDS.sorted(), reported.sorted())
    }

    // ----------------------------------------------------------- the chords

    @Test
    fun theChordsMatchTheBindingsUpstreamDeclares() {
        fun chord(key: Key, ctrl: Boolean = false, shift: Boolean = false): GlobalChord? =
            globalChord(KeyEventType.KeyDown, key, ctrl = ctrl, meta = false, shift = shift)

        assertEquals(GlobalChord.BROWSER_OPEN, chord(Key.B, ctrl = true, shift = true))
        assertEquals(GlobalChord.BROWSER_RELOAD, chord(Key.F5))
        assertEquals(GlobalChord.FILE_ATTACH, chord(Key.U, ctrl = true))
        assertEquals(GlobalChord.PROMPT_MODE_SHELL, chord(Key.X, ctrl = true, shift = true))
        assertEquals(GlobalChord.PROMPT_MODE_NORMAL, chord(Key.E, ctrl = true, shift = true))
        // Every modifier still has to match exactly (`matchKeybind`) — `mod+b`
        // is `session.background`, not `browser.open`, and a bare `u` / `mod+x`
        // bind nothing.
        assertEquals(GlobalChord.SESSION_BACKGROUND, chord(Key.B, ctrl = true))
        assertNull(chord(Key.U))
        assertNull(chord(Key.X, ctrl = true))
    }

    @Test
    fun theBrowserChordsLiveOutsideTheSettingsTableOnPurpose() {
        val defaults = defaultGlobalChords()
        assertEquals("Ctrl+Shift+B", defaults["browser.open"])
        assertEquals("F5", defaults["browser.reload"])
        // The two browser ids are wired but not rebindable rows; the three
        // composer ids are rows, so they are in both tables.
        val ids = SHORTCUT_ROWS.map { it.id }
        assertFalse("browser.open is an EXTRA_GLOBAL_CHORDS id", "browser.open" in ids)
        assertFalse("browser.reload is an EXTRA_GLOBAL_CHORDS id", "browser.reload" in ids)
        for (id in listOf("file.attach", "prompt.mode.shell", "prompt.mode.normal")) {
            assertTrue("$id must be a settings row", id in ids)
            assertTrue("$id must be wired", id in WIRED_SHORTCUT_IDS)
            assertEquals(defaults[id], SHORTCUT_ROWS.first { it.id == id }.keybind)
        }
    }

    @Test
    fun f5EntersTheVocabularyWhileTheRestOfTheFunctionRowStaysOut() {
        // B-18 added exactly one function key, because exactly one binding uses
        // one. `Wave5IntegrationWiringTest` pins the other direction (F1 is
        // still unmapped).
        assertEquals(
            keybindSignatures("F5"),
            listOfNotNull(chordSignatureToken(ctrl = false, meta = false, shift = false, alt = false, key = Key.F5)),
        )
        assertEquals(listOf("f5"), keybindSignatures("F5"))
        assertNull(chordSignatureToken(ctrl = false, meta = false, shift = false, alt = false, key = Key.F1))
    }

    // ------------------------------------------------------------ the wiring

    @Test
    fun appAndSessionScreenServeEveryNewId() {
        val app = source("src/main/java/com/xiufeigo/opencodeshell/ui/App.kt")
        val screen = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionScreen.kt")
        for (id in BROWSER_AND_COMPOSER_IDS) {
            assertTrue("App.kt must route $id", app.contains("\"$id\""))
            assertTrue("SessionScreen must serve $id", screen.contains("\"$id\""))
        }
        // The palette rows ride the same channel as the chords.
        assertTrue(app.contains("onOpenBrowser = { intents.requestCommand(\"browser.open\") }"))
        assertTrue(app.contains("onAttachFile = { intents.requestCommand(\"file.attach\") }"))
        // …and the composer-side half is the external request counters.
        val composer = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/Composer.kt")
        assertTrue(composer.contains("externalAttachRequest: Int = 0"))
        assertTrue(composer.contains("externalShellMode: Int = 0"))
        assertTrue(composer.contains("externalNormalMode: Int = 0"))
        assertTrue(screen.contains("externalAttachRequest = attachRequest"))
        assertTrue(screen.contains("externalShellMode = shellModeRequest"))
        assertTrue(screen.contains("externalNormalMode = normalModeRequest"))
        // The reload request reaches the pane through the ambient counter, and
        // the pane acts on it.
        assertTrue(screen.contains("LocalBrowserReloadRequest provides browserReloadRequest"))
        val pane = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/browser/SessionBrowserPane.kt")
        assertTrue(pane.contains("LocalBrowserReloadRequest.current"))
        assertTrue(pane.contains("LaunchedEffect(reloadRequest)"))
    }

    private companion object {
        val BROWSER_AND_COMPOSER_IDS = listOf(
            "browser.open",
            "browser.reload",
            "file.attach",
            "prompt.mode.shell",
            "prompt.mode.normal",
        )
    }
}
