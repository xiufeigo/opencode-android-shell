package com.xiufeigo.opencodeshell

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import com.xiufeigo.opencodeshell.ui.GlobalChord
import com.xiufeigo.opencodeshell.ui.chordSignatureToken
import com.xiufeigo.opencodeshell.ui.defaultGlobalChords
import com.xiufeigo.opencodeshell.ui.effectiveGlobalChords
import com.xiufeigo.opencodeshell.ui.globalChord
import com.xiufeigo.opencodeshell.ui.settings.SHORTCUT_ROWS
import com.xiufeigo.opencodeshell.ui.settings.keybindSignatures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * W5-INT wiring guards.
 *
 * Wave 5-INT only *connected* what four earlier waves had already delivered, so
 * almost nothing here can be asserted against a JVM-visible behaviour: the pieces
 * are Compose composables, key handlers and Android toasts. The repo's established
 * answer (`ShellWiringTest`, `Wave5EventsWiringTest`, `Wave5ComposerWiringTest`) is
 * a source-level guard, used below for the six integration points, plus the one
 * genuinely pure piece this wave added: the **keybind override table** that
 * `globalChord` now matches against.
 *
 * The keybind tests are the non-source ones and they pin the three claims the
 * settings wave could not make on its own (`research/wave5_settings.md` §3):
 * default = the previous behaviour, a recorded override wins, and an id without a
 * global chord stays inert **on purpose**.
 */
class Wave5IntegrationWiringTest {

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("missing $relative")

    private fun app() = source("src/main/java/com/xiufeigo/opencodeshell/ui/App.kt")

    private fun sessionScreen() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionScreen.kt")

    private fun sessionHeader() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/header/SessionHeader.kt")

    private fun homeScreen() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeScreen.kt")

    private fun appCommands() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/palette/AppCommands.kt")

    private fun client() = source("src/main/java/com/xiufeigo/opencodeshell/OpenCodeClient.kt")

    // ------------------------------------------- keybind overrides (pure, JVM)

    private fun chord(
        key: Key,
        ctrl: Boolean = true,
        meta: Boolean = false,
        shift: Boolean = false,
        alt: Boolean = false,
        keybinds: Map<String, String> = emptyMap(),
    ): GlobalChord? = globalChord(
        type = KeyEventType.KeyDown,
        key = key,
        ctrl = ctrl,
        meta = meta,
        shift = shift,
        alt = alt,
        keybinds = keybinds,
    )

    @Test
    fun withNoOverridesEveryChordStillBehavesAsBefore() {
        assertEquals(GlobalChord.PALETTE, chord(Key.K))
        assertEquals(GlobalChord.PALETTE, chord(Key.P, shift = true))
        assertEquals(GlobalChord.PALETTE_FILES, chord(Key.P))
        assertEquals(GlobalChord.SETTINGS, chord(Key.Comma))
        assertEquals(GlobalChord.NEW_SESSION, chord(Key.N))
        assertEquals(GlobalChord.NEW_SESSION, chord(Key.S, shift = true))
        assertEquals(GlobalChord.TERMINAL, chord(Key.Grave))
        assertEquals(GlobalChord.SESSION_SEARCH, chord(Key.F))
        // A1's new binding.
        assertEquals(GlobalChord.SUMMARY, chord(Key.Y, shift = true))
        // Every modifier still has to match exactly (`matchKeybind`).
        assertNull(chord(Key.K, shift = true))
        assertNull(chord(Key.Y))
        assertNull(chord(Key.S))
        assertNull(chord(Key.Q))
        assertNull(chord(Key.K, ctrl = false, meta = false))
        assertNull(
            globalChord(KeyEventType.KeyUp, Key.K, ctrl = true, meta = false, shift = false),
        )
        // Android has no ⌘: `meta` still reads as `mod` (the W4-D contract).
        assertEquals(GlobalChord.PALETTE, chord(Key.K, ctrl = false, meta = true))
        // …but an unmapped key can never match, however it is modified.
        assertNull(chord(Key.K, alt = true))
    }

    @Test
    fun theDefaultTableIsTheSettingsTablesOwnBindings() {
        val defaults = defaultGlobalChords()
        // No drift: the handler's defaults *are* the rows the settings panel shows.
        for (row in SHORTCUT_ROWS) {
            assertEquals("${row.id} default", row.keybind, defaults[row.id])
        }
        // The two bindings with no settings row still have a default, or the
        // handler would silently lose an action.
        assertEquals("Ctrl+F", defaults["session.search"])
        assertEquals("Ctrl+Shift+Y", defaults["session.summary.toggle"])
        // Eight ids → nine signatures: the palette carries two (`mod+k` and
        // `mod+shift+p`) and nothing else contributes a second chord. `prompt.submit`
        // is a row without a global chord, and is therefore absent on purpose.
        assertEquals(9, effectiveGlobalChords().size)
        assertNull(chordSignatureToken(ctrl = false, meta = false, shift = false, alt = false, key = Key.Enter))
    }

    @Test
    fun theTokenVocabularyIsTheOneTheSettingsPanelWrites() {
        // Recording writes `formatKeybindChord`/`keybindSignatures` spellings; the
        // matcher has to speak the same language or nothing recorded would fire.
        assertEquals(
            keybindSignatures("Ctrl+Shift+Y"),
            listOfNotNull(chordSignatureToken(ctrl = true, meta = false, shift = true, alt = false, key = Key.Y)),
        )
        assertEquals(keybindSignatures("Ctrl+`"), listOfNotNull(chordSignatureToken(true, false, false, false, Key.Grave)))
        // The comma key is the one ambiguous spelling: `,` is upstream's chord
        // *separator*, so the shared vocabulary reads `Ctrl+,` as the bare modifier
        // `Ctrl`. The table normalizes that to `Ctrl+Comma` before tokenizing, which
        // is why the comma is asserted end-to-end in the test above instead of by a
        // token-versus-signature comparison — and this pins the trap itself.
        assertEquals(listOf("ctrl"), keybindSignatures("Ctrl+,"))
        assertEquals("ctrl+comma", chordSignatureToken(ctrl = true, meta = false, shift = false, alt = false, key = Key.Comma))
        // A key outside the vocabulary is not mapped at all.
        assertNull(chordSignatureToken(ctrl = true, meta = false, shift = false, alt = false, key = Key.F1))
    }

    @Test
    fun aRecordedOverrideWinsAndFreesItsOldChord() {
        val keybinds = mapOf("terminal.toggle" to "Ctrl+Shift+S")
        assertEquals(GlobalChord.TERMINAL, chord(Key.S, shift = true, keybinds = keybinds))
        // The default it replaced is dead: an override *replaces*, it does not add.
        assertNull(chord(Key.Grave, keybinds = keybinds))
        // Unrelated rows are untouched.
        assertEquals(GlobalChord.PALETTE, chord(Key.K, keybinds = keybinds))
        assertEquals(GlobalChord.SETTINGS, chord(Key.Comma, keybinds = keybinds))
    }

    @Test
    fun anUnassignedOverrideRemovesTheAction() {
        // `none` is the panel's spelling for an explicitly cleared row
        // (`KEYBIND_NONE`), and a blank value means the same thing: no signature,
        // so the action has no chord at all — matching what the panel displays.
        assertNull(chord(Key.Grave, keybinds = mapOf("terminal.toggle" to "none")))
        assertNull(chord(Key.Grave, keybinds = mapOf("terminal.toggle" to "  ")))
        // …while every other row keeps working.
        assertEquals(GlobalChord.PALETTE, chord(Key.K, keybinds = mapOf("terminal.toggle" to "none")))
    }

    @Test
    fun theSummaryCommandFollowsItsOwnOverride() {
        assertEquals(
            GlobalChord.SUMMARY,
            chord(Key.J, shift = true, keybinds = mapOf("session.summary.toggle" to "Ctrl+Shift+J")),
        )
        assertNull(chord(Key.Y, shift = true, keybinds = mapOf("session.summary.toggle" to "Ctrl+Shift+J")))
    }

    @Test
    fun idsWithoutAGlobalChordStayInertOnPurpose() {
        // `review.toggle` / `fileTree.toggle` / `prompt.submit` have palette rows
        // (and composer handlers) but no global chord, so an override for them may
        // not shadow a chord that *is* implemented.
        val keybinds = mapOf("review.toggle" to "Ctrl+Shift+Y", "prompt.submit" to "Ctrl+K")
        assertEquals(GlobalChord.SUMMARY, chord(Key.Y, shift = true, keybinds = keybinds))
        assertEquals(GlobalChord.PALETTE, chord(Key.K, keybinds = keybinds))
        assertNull(chord(Key.R, shift = true, keybinds = keybinds))
    }

    // -------------------------------------------------- W5-E §5 event refresh

    @Test
    fun homeListensToBothEventRevisions() {
        val home = homeScreen()
        assertTrue(
            "Home must refresh on project.updated **and** session.created/deleted",
            home.contains("LaunchedEffect(chatViewModel, projectsRevision, sessionsRevision)"),
        )
        assertTrue(home.contains("chatViewModel?.projectsRevision"))
        assertTrue(home.contains("chatViewModel?.sessionsRevision"))
        assertTrue("the project read is still there", home.contains("viewModel.listProjects"))
        assertTrue("the session read is still there", home.contains("viewModel.listSessions"))
    }

    @Test
    fun theDrawerAndTheReviewPanelFollowTheEventRevisions() {
        val screen = sessionScreen()
        assertTrue(
            "the drawer list is keyed on sessionsRevision",
            screen.contains("LaunchedEffect(drawerState.isOpen, drawerOpen, sessionsRevision)"),
        )
        assertTrue(
            "vcs.branch.updated re-runs the branch/diff pull",
            screen.contains("LaunchedEffect(activeSession.id, reviewOpen, mobileReview, summaryOpen, vcsRevision)"),
        )
        assertTrue(screen.contains("val sessionsRevision by chatViewModel.sessionsRevision.collectAsState()"))
        assertTrue(screen.contains("val vcsRevision by chatViewModel.vcsRevision.collectAsState()"))
        assertTrue(screen.contains("val filesRevision by chatViewModel.filesRevision.collectAsState()"))
        // `file.edited` is debounced (a burst per tool call) and refreshes the read
        // models without clearing the branch chip.
        assertTrue(screen.contains("private const val FILE_EDITED_DEBOUNCE_MS = 300L"))
        assertTrue(screen.contains("delay(FILE_EDITED_DEBOUNCE_MS)"))
        assertTrue(screen.contains("chatViewModel.refreshDiff()"))
        assertTrue(screen.contains("chatViewModel.refreshTodos()"))
    }

    // ------------------------------------------------------- A1 summary panel

    @Test
    fun theSummaryPanelIsMountedOnBothForms() {
        val screen = sessionScreen()
        // Wide screens: the header popover.
        assertTrue(screen.contains("SessionSummaryPopover(open = summaryOpen"))
        assertTrue(screen.contains("directory = reviewRootPath"))
        assertTrue(screen.contains("branch = reviewBranch"))
        assertTrue(screen.contains("changedFiles = summaryChangedFiles"))
        assertTrue(screen.contains("additions = summaryAdditions"))
        assertTrue(screen.contains("deletions = summaryDeletions"))
        assertTrue(screen.contains("serverUrl = summaryServerUrl"))
        assertEquals(
            "the panel is mounted exactly twice: popover + phone view",
            2,
            Regex("SessionSummaryPanel\\(").findAll(screen).count(),
        )
        // Phone: the ⋯ menu's「会话详情」row, which used to be disabled.
        assertTrue(screen.contains("onOpenSummary = {"))
        assertTrue(screen.contains("mobile = true"))
        // The popover host lives with the other header popup.
        assertTrue(sessionHeader().contains("fun SessionSummaryPopover("))
        // …and the diff the changes row counts really is `ChatState.diff`.
        assertTrue(screen.contains("state.diff.size"))
    }

    @Test
    fun theSummaryCommandIsRegisteredInBothEntryPoints() {
        assertTrue(appCommands().contains("id = \"session.summary.toggle\""))
        assertTrue(appCommands().contains("keybind = \"Ctrl+Shift+Y\""))
        assertTrue(appCommands().contains("onToggleSummary?.let"))
        assertTrue("the palette gets the host action", app().contains("onToggleSummary = actions.onToggleSummary"))
        val app = app()
        assertTrue(app.contains("GlobalChord.SUMMARY"))
        assertTrue(app.contains("val onToggleSummary: () -> Unit = {"))
        assertTrue(
            "a bump outside a session would auto-open the panel on the next entry",
            app.contains("if (currentRoute == Route.SESSION) summaryRevision += 1"),
        )
    }

    // --------------------------------------------------------- A8 session export

    @Test
    fun theClientGainedRawJsonReadersAndKeptTheParsedPaths() {
        val client = client()
        assertTrue(client.contains("fun sessionJson(sessionID: String, directory: String? = null): JSONObject"))
        assertTrue(
            client.contains(
                "fun messagesJson(sessionID: String, directory: String? = null, limit: Int? = null): List<JSONObject>",
            ),
        )
        // Additive: the parsed readers every other feature uses are unchanged.
        assertTrue(client.contains("fun session(sessionID: String, directory: String? = null): Session"))
        assertTrue(client.contains("fun messages(sessionID: String, directory: String? = null, limit: Int? = null): List<Message>"))
        assertTrue(client.contains("Session.fromJson("))
        assertTrue(client.contains(").map(Message::fromJson)"))
    }

    @Test
    fun theExportRowIsWiredAndThePlaceholderIsGone() {
        val header = sessionHeader()
        assertTrue(header.contains("SessionMenuAction.EXPORT"))
        assertTrue(header.contains("R.string.command_session_export"))
        assertTrue("the old placeholder note must be gone", !header.contains("still missing"))
        assertTrue(!header.contains("needs server share/export"))
        val screen = sessionScreen()
        assertTrue(screen.contains("SessionMenuAction.EXPORT -> exportSession()"))
        assertTrue(screen.contains("fun exportSession()"))
        assertTrue(screen.contains("rememberSessionExportSaver()"))
        assertTrue(screen.contains("SessionExport.serialize("))
        assertTrue(screen.contains("client.sessionJson("))
        assertTrue(screen.contains("client.messagesJson("))
        assertTrue(screen.contains("exportSaver.save(filename, json)"))
        assertTrue("a failed save is reported", screen.contains("R.string.oc_export_failed"))
        assertTrue("a successful save names the file", screen.contains("R.string.oc_export_success, filename"))
        // The two raw reads plus the serialization stay off the main thread.
        assertTrue(screen.contains("withContext(Dispatchers.IO)"))
    }

    // ------------------------------------------------------- E5 notification UI

    @Test
    fun theNotificationBannerIsInsideTheThemeAndOutsideTheDialogHost() {
        val app = app()
        val banner = app.indexOf("NotificationBanner(")
        val theme = app.indexOf("OcTheme(isDark = dark) {")
        val dialogHost = app.indexOf("DialogHost {")
        assertTrue("the banner is mounted", banner >= 0)
        assertTrue("inside OcTheme", theme in 0 until banner)
        assertTrue("the dialog host is still there", dialogHost in 0 until banner)
        // The banner must not be a child of `DialogHost`: a dialog's scrim would
        // cover it, and the settings surface would clip it.
        val hostEnd = endOfBlock(app, dialogHost)
        assertTrue("DialogHost's block is closed before the banner", hostEnd in 0 until banner)
        // Data + actions come from the ViewModel's feed.
        assertTrue(app.contains("(chatViewModel?.notifications ?: NO_NOTIFICATIONS)"))
        assertTrue(app.contains("notifications.lastOrNull { !it.viewed }"))
        assertTrue(app.contains("chatViewModel?.dismissNotification(pending.id)"))
        assertTrue(app.contains("chatViewModel?.clearNotifications()"))
        assertTrue(app.contains("R.string.oc_notify_dismiss"))
        assertTrue(app.contains("R.string.oc_notify_dismiss_all"))
        // The kind → title mapping exists once, for all four kinds.
        for (kind in listOf("AGENT", "PERMISSION", "QUESTION", "ERROR")) {
            assertTrue("AppNotificationKind.$kind has a title", app.contains("AppNotificationKind.$kind ->"))
        }
    }

    /** Index of the `}` that closes the block opened at/after [start], or -1. */
    private fun endOfBlock(source: String, start: Int): Int {
        val open = source.indexOf('{', start)
        if (open < 0) return -1
        var depth = 0
        for (index in open until source.length) {
            when (source[index]) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return index
                }
            }
        }
        return -1
    }

    // ------------------------------------------------------------------ strings

    private val newKeys = listOf("oc_command_summary_toggle", "oc_export_success", "oc_export_failed")

    @Test
    fun everyNewStringExistsInAllThreeLocales() {
        for (locale in listOf("values", "values-zh-rCN", "values-zh-rTW")) {
            val xml = source("src/main/res/$locale/strings.xml")
            val missing = newKeys.filterNot { xml.contains("name=\"$it\"") }
            assertEquals("$locale is missing $missing", emptyList<String>(), missing)
            // A8's menu row deliberately reuses the WebView bundle's own i18n key
            // instead of adding a third copy of the same sentence; that key lives in
            // its own resource file (values*/strings_oc_i18n.xml), not strings.xml.
            val i18n = source("src/main/res/$locale/strings_oc_i18n.xml")
            assertTrue("$locale must keep command_session_export", i18n.contains("name=\"command_session_export\""))
        }
    }

    @Test
    fun theNewStringsAreLocalised() {
        val english = source("src/main/res/values/strings.xml")
        for (locale in listOf("values-zh-rCN", "values-zh-rTW")) {
            val xml = source("src/main/res/$locale/strings.xml")
            for (key in newKeys) {
                val value = xml.lineSequence().firstOrNull { it.contains("name=\"$key\"") }
                    ?.substringAfter('>')?.substringBefore('<')
                val source = english.lineSequence().firstOrNull { it.contains("name=\"$key\"") }
                    ?.substringAfter('>')?.substringBefore('<')
                assertNotNull("$locale/$key", value)
                assertTrue("$locale/$key is still the English copy", value != source)
            }
        }
    }
}
