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

    private fun messageTimeline() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/timeline/MessageTimeline.kt")

    private fun markdownRenderer() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/markdown/MarkdownRenderer.kt")

    private fun homeScreen() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeScreen.kt")

    private fun appCommands() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/palette/AppCommands.kt")

    private fun client() = source("src/main/java/com/xiufeigo/opencodeshell/OpenCodeClient.kt")

    private fun chatViewModel() = source("src/main/java/com/xiufeigo/opencodeshell/ChatViewModel.kt")

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
        // B-18: the same for the two browser rows, whose defaults also live in
        // `EXTRA_GLOBAL_CHORDS` (the pane is an overlay, not a rebindable row).
        assertEquals("Ctrl+Shift+B", defaults["browser.open"])
        assertEquals("F5", defaults["browser.reload"])
        // B-5: 31 wired rows → 25 distinct signatures. Only `command.palette`
        // contributes two chords (`mod+k` and `mod+shift+p`); the eight rows whose
        // upstream binding is empty (`session.undo`, `session.redo`,
        // `session.compact`, `session.fork`, `session.export`, `session.copyID`,
        // `session.aside`, and B-17's `debugBar.toggle` — upstream declares no
        // keybind for the debug bar either) contribute none until the user
        // records one.
        // B-19 adds `file.attach` / `prompt.mode.shell` / `prompt.mode.normal`
        // and B-18 the two browser chords: 25 + 5 = 30.
        assertEquals(30, effectiveGlobalChords().size)
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
    fun everyWiredRowCanBeReassignedThroughItsOverride() {
        // B-5: every row is wired, so an override for any of them fires — the
        // whole point of the wave. `review.toggle` used to be the counter-example
        // (a palette-only row), and is now the representative of the new ones.
        val keybinds = mapOf("review.toggle" to "Ctrl+Shift+J", "session.undo" to "Ctrl+Alt+U")
        assertEquals(GlobalChord.REVIEW, chord(Key.J, shift = true, keybinds = keybinds))
        assertEquals(GlobalChord.SESSION_UNDO, chord(Key.U, alt = true, keybinds = keybinds))
        // A default it replaced is dead — an override replaces, it does not add.
        assertNull(chord(Key.R, shift = true, keybinds = keybinds))
    }

    @Test
    fun overridesForIdsThePanelNoLongerListsCannotShadowAChord() {
        // B-5 dropped the rows with no implementation face (`prompt.submit`,
        // `steps.toggle`, …). A stale override left in the preference file by an
        // older build must not take a chord away from a live action.
        //
        // B-17 moved `debugBar.toggle` **out** of this example set: it is a wired
        // row now, so its override is *supposed* to shadow — that is the
        // `everyWiredRowCanBeReassignedThroughItsOverride` contract. The
        // still-dropped ids are the ones that must stay inert.
        val keybinds = mapOf("prompt.submit" to "Ctrl+K", "steps.toggle" to "Ctrl+Shift+Y")
        assertEquals(GlobalChord.PALETTE, chord(Key.K, keybinds = keybinds))
        assertEquals(GlobalChord.SUMMARY, chord(Key.Y, shift = true, keybinds = keybinds))
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
            screen.contains(
                "LaunchedEffect(activeSession.id, reviewOpen, mobileReview, summaryOpen, vcsRevision, " +
                    "currentDirectory)",
            ),
        )
        assertTrue(screen.contains("val sessionsRevision by chatViewModel.sessionsRevision.collectAsState()"))
        assertTrue(screen.contains("val vcsRevision by chatViewModel.vcsRevision.collectAsState()"))
        assertTrue(screen.contains("val filesRevision by chatViewModel.filesRevision.collectAsState()"))
        // `file.edited` is debounced (a burst per tool call) and refreshes the read
        // models without clearing the branch chip.
        assertTrue(screen.contains("private const val FILE_EDITED_DEBOUNCE_MS = 300L"))
        assertTrue(screen.contains("delay(FILE_EDITED_DEBOUNCE_MS)"))
        assertTrue(screen.contains("chatViewModel.refreshDiff()"))
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
        // Additive: the parsed readers every other feature uses survive on the
        // v2 route (message rows now decode through Message.fromV2Json).
        assertTrue(client.contains("fun session(sessionID: String, directory: String? = null): Session"))
        assertTrue(client.contains("fun messages(sessionID: String, directory: String? = null, limit: Int? = null): List<Message>"))
        assertTrue(client.contains("Session.fromJson("))
        assertTrue(client.contains("Message.fromV2Json("))
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
        // P0: the raw reads ride the generation-aware transport (v1
        // `sessionJson`/`messagesJson`, v2 `/api/session…`) instead of a
        // bare `OpenCodeClient` — on v2 the unprefixed routes are SPA HTML.
        // The export flow reads through the **Result** twin so the transport's
        // failure reason survives into the toast (`fetchExportFrame` swallows
        // it), and it is the same call the context tab makes — counted, so both
        // mount points stay on the one fetch.
        val seam = "chatViewModel.fetchExportFrameResult(activeSession.id, reviewRootPath)"
        assertEquals("export and the context tab share the one fetch", 2, screen.split(seam).size - 1)
        // The saver takes the composable scope so a failed/hung SAF launch can
        // fall back to Download/ (P0: a bare `launcher.launch()` black-screened
        // the app on the desktop branch with no resolve pre-check or watchdog).
        // v68: the saved name is resolved from the freshly fetched frame title
        // (`liveFilename`), so a rename that landed after the row cache still
        // shows up in the SAF pre-fill.
        assertTrue(screen.contains("exportSaver.save(liveFilename, json, scope)"))
        assertTrue(
            "the filename prefers the live frame title",
            screen.contains("SessionExport.frameTitle(frame)"),
        )
        assertTrue("a failed save is reported", screen.contains("R.string.oc_export_failed"))
        assertTrue("a successful save names the file", screen.contains("R.string.oc_export_success, filename"))
        // The fetch is IO-bound inside `ChatViewModel.fetchExportFrame`.
        assertTrue(chatViewModel().contains("withContext(Dispatchers.IO)"))
    }

    private fun v2api() = source("src/main/java/com/xiufeigo/opencodeshell/v2/V2Api.kt")

    private fun v2transport() = source("src/main/java/com/xiufeigo/opencodeshell/v2/V2Transport.kt")

    private fun backgroundShellPanel() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/terminal/BackgroundShellPanel.kt")

    // ------------------------------------------------------- E5 notification UI

    @Test
    fun theNotificationBannerIsInsideTheThemeAndOutsideTheDialogHost() {
        val app = app()
        // Scoped to `OcApp`: the banner composable's own definition repeats the
        // name below, and only the mount point's position matters here.
        val root = app.substringAfter("fun OcApp(").substringBefore("private fun AppScaffold(")
        val banner = root.indexOf("NotificationBanner(")
        val theme = root.indexOf("OcTheme(")
        // The theme carries the settings wiring (`themeId` / `uiFont` /
        // `codeFont` from the store), so the banner position pin anchors on
        // the call head rather than the old single-arg line.
        assertTrue("the theme carries settings.themeId", app.contains("themeId = settings.themeId"))
        assertTrue("the theme carries settings.uiFont", app.contains("uiFontFamily(settings.uiFont)"))
        assertTrue(
            "the theme carries settings.codeFont",
            app.contains("codeFontFamily(settings.codeFont, settings.uiFont)"),
        )
        val dialogHost = root.indexOf("DialogHost {")
        assertTrue("the banner is mounted", banner >= 0)
        assertTrue("inside OcTheme", theme in 0 until banner)
        assertTrue("the dialog host is still there", dialogHost > banner)
        // v46 tD_58: the banner rides in-flow ABOVE the host instead of
        // overlaying it — an overlay with no scrim pressed straight onto the
        // page and covered the Projects title. In flow the banner takes its
        // own band and pushes every route's content down while it is up. It
        // stays a sibling of the host, never a child of it: a dialog's scrim
        // still cannot clip it, and the settings surface stays above it.
        val hostEnd = endOfBlock(root, dialogHost)
        assertTrue("the host block is well-formed", hostEnd > dialogHost)
        assertTrue("the banner is the host's sibling above it, not its child", banner < dialogHost)
        assertTrue(
            "the host fills what is left under the banner",
            root.contains("Box(Modifier.fillMaxWidth().weight(1f))"),
        )
        assertTrue(
            "the in-flow banner still clears the status bar itself",
            root.contains(".windowInsetsPadding(WindowInsets.statusBars)"),
        )
        assertTrue("and stays centered", root.contains(".align(Alignment.CenterHorizontally)"))
        // Data + actions come from the ViewModel's feed.
        assertTrue(app.contains("(chatViewModel?.notifications ?: NO_NOTIFICATIONS)"))
        // v48: the band is transient while the feed is not — `BannerPolicy`
        // skips retired entries, taps retire the id before delegating
        // (same-frame hide even when a re-raised twin races the tap), and a
        // shown entry auto-hides after ~8s while staying unviewed in the feed.
        // v49: the band opens with the whole existing feed retired (the cold
        // start must not pop a page of restored history), and the auto-hide
        // retires the shown id too — the entry that just finished in the
        // foreground is `viewed`, which no longer keeps it off the band.
        // v50 tE: with `viewed` out of the selection, the taps must retire the
        // same scope `acknowledge` marks viewed (the whole session) and the
        // whole feed for dismiss-all, or the twin behind the shown entry takes
        // the frame and the tap measures as no tap at all.
        assertTrue(app.contains("BannerPolicy.allIds(notifications)"))
        assertTrue(app.contains("BannerPolicy.hiddenAfterShow(notifications, hiddenIds.value, pending.id)"))
        assertTrue(app.contains("BannerPolicy.select(notifications, hiddenIds.value)"))
        assertTrue(
            app.contains("BannerPolicy.hiddenAfterDismiss(notifications, hiddenIds.value, pending)"),
        )
        assertTrue(
            app.contains("BannerPolicy.hiddenAfterDismissAll(notifications, hiddenIds.value)"),
        )
        assertTrue(app.contains("LaunchedEffect(pending.id)"))
        assertTrue(app.contains("delay(BannerPolicy.AUTO_DISMISS_MS)"))
        // v48 tE: the text actions need a real touch target — the old ~28
        // dp-tall text was easy to miss by finger and by automation tap.
        // v51 tE: the height is the named `BannerBand.ACTION_TARGET` rather
        // than a literal — the target's height *is* the band's height, so a
        // 48 dp action made the band reserve ~190 px and pushed the composer's
        // input box off a short window. 40 dp keeps the floor (platform
        // comfortable minimum is 32 dp) and the actions keep a 48 dp width.
        assertTrue(
            "the action keeps a 48 dp minimum width",
            app.contains("sizeIn(minWidth = 48.dp, minHeight = BannerBand.ACTION_TARGET)"),
        )
        assertTrue(
            "the touch-target height is the named BannerBand constant, not a literal",
            app.contains("val ACTION_TARGET = 40.dp"),
        )
        // The floor the shrink was allowed to stop at: below the platform's
        // 32 dp comfortable minimum the target regresses to "easy to miss".
        val targetDp = Regex("""val ACTION_TARGET = (\d+)\.dp""")
            .find(app)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()
        assertTrue("the touch-target constant is a readable dp literal", targetDp != null)
        assertTrue(
            "the action's touch target must stay at or above the 32 dp floor",
            targetDp!! >= 32,
        )
        // v47 tE: 「知道了」 acknowledges instead of bare-dismissing — a bare
        // `dismissNotification` only popped the top entry and the next identical
        // banner rendered in the same frame, so every tap looked dead.
        assertTrue(app.contains("chatViewModel?.acknowledgeNotification(pending.id)"))
        assertTrue(app.contains("chatViewModel?.clearNotifications()"))
        assertTrue(app.contains("R.string.oc_notify_dismiss"))
        assertTrue(app.contains("R.string.oc_notify_dismiss_all"))
        // B-2: the banner's second line is the same one the system notification
        // posts (`SystemNotificationPolicy.bodyOf`), so an unknown title shows
        // the session id instead of nothing.
        assertTrue(app.contains("SystemNotificationPolicy.bodyOf(pending)"))
        // The kind → title mapping exists once, for all four kinds.
        for (kind in listOf("AGENT", "PERMISSION", "QUESTION", "ERROR")) {
            assertTrue("AppNotificationKind.$kind has a title", app.contains("AppNotificationKind.$kind ->"))
        }
    }

    // ------------------------------------------------- U-3/F-1/F-3/F-4 wiring

    @Test
    fun codeBlocksFollowTheThemedMonoFont() {
        val renderer = markdownRenderer()
        assertTrue("no hardcoded mono family remains", !renderer.contains("FontFamily.Monospace"))
        // Language label, highlighted body and inline code all read the ambient
        // mono family, so `settings-code-font` takes effect without touching
        // the call sites (`OcTheme(codeFont)` in `ui/App.kt`).
        assertTrue(renderer.contains("fontFamily = OcTheme.monoFont"))
        assertTrue(renderer.contains("if (span.code) OcTheme.monoFont else null"))
    }

    @Test
    fun sessionComposerGetsPaletteCommandsAndDraftRestore() {
        val screen = sessionScreen()
        // F-1: the `/` menu's session-alias half resolves against the screen's
        // palette table instead of the empty default.
        assertTrue(screen.contains("paletteCommands = composerPaletteCommands"))
        assertTrue(screen.contains("SessionCommands.sessionCommands("))
        // U-3: the failed-send handoff re-enters screen state (attachments) and
        // scrolls the timeline back to the referenced message.
        assertTrue(screen.contains("onRestoreAttachments = { restored ->"))
        assertTrue(screen.contains("AttachmentPolicy.merge(attachments, restored)"))
        assertTrue(screen.contains("onRestoreMessageRef = { messageID ->"))
        assertTrue(screen.contains("timelineListState.animateScrollToItem(index)"))
    }

    @Test
    fun timelinePublishesItsMessageIndexForRestoreScroll() {
        val timeline = messageTimeline()
        assertTrue(timeline.contains("onMessageIndex: ((Map<String, Int>) -> Unit)? = null"))
        assertTrue(timeline.contains("onMessageIndex?.invoke(rowIndex)"))
        val screen = sessionScreen()
        assertTrue(screen.contains("listState = timelineListState"))
        assertTrue(screen.contains("onMessageIndex = { timelineRowIndex = it }"))
    }

    @Test
    fun moveSessionRefreshesTheSnapshotOnSuccess() {
        val vm = chatViewModel()
        // F-4: after a move every REST read re-scopes to the new directory —
        // the success branch re-pulls instead of answering from the old one
        // until the next event settles. The optimistic-row rewrite nests the
        // guard inside `if (pendingMove == submitted)`, so pin the refresh
        // inside the success branch rather than the old single-line shape.
        val moveAt = vm.indexOf("fun moveSessionTo(")
        assertTrue("moveSessionTo exists", moveAt >= 0)
        // The signature carries a `{}` default arg — open the block from the
        // signature's own `) {`, not the first `{` after the name.
        val bodyOpen = vm.indexOf(") {", moveAt)
        assertTrue("moveSessionTo body opens", bodyOpen > moveAt)
        val moveEnd = endOfBlock(vm, bodyOpen)
        assertTrue("moveSessionTo block is well-formed", moveEnd > moveAt)
        val body = vm.substring(moveAt, moveEnd)
        val successAt = body.indexOf("if (ok && sessionID == id)")
        assertTrue("the success branch is still guarded on ok + same session", successAt >= 0)
        val successEnd = endOfBlock(body, successAt)
        assertTrue("the success branch is well-formed", successEnd > successAt)
        assertTrue(
            "move success re-pulls the snapshot instead of answering from the old directory",
            body.substring(successAt, successEnd).contains("refreshSnapshotThrottled()"),
        )
    }

    @Test
    fun timelineLinksOpenInTheEmbeddedBrowserPane() {
        val screen = sessionScreen()
        // F-3: http(s) links resolve to the in-app pane (everything reading
        // `LocalUriHandler` inside the timeline is intercepted); anything else
        // still delegates outward, and the pane's external row reuses that path.
        assertTrue(screen.contains("var browserUrl by rememberSaveable"))
        assertTrue(screen.contains("LocalUriHandler provides inAppUriHandler"))
        assertTrue(screen.contains("browserUrl = uri"))
        // Single-line pins: `SessionScreen.kt` is CRLF on disk, so a
        // multi-line literal would never match.
        assertTrue(screen.contains("SessionBrowserPane("))
        assertTrue(screen.contains("onClose = { browserUrl = null }"))
        assertTrue(screen.contains("onOpenExternal = { openExternalUrl(url) }"))
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

    // --------------------------------- P0 messagesRaw pagination + BG shells

    @Test
    fun messagesRawFollowsCursorAloneOrExportDiesOnInvalidCursor() {
        // Measured 2026-09-24 on 4095: `order=asc&cursor=…` answers 400
        // `InvalidCursorError` ("Cursor cannot be combined with order") while
        // `cursor=…` alone answers 200 — upstream
        // `server/src/handlers/message.ts:36` rejects `cursor && order` and
        // derives the order from the decoded cursor.
        val api = v2api()
        assertTrue(api.contains("fun messagesRaw("))
        assertTrue(api.contains("if (page == 0)"))
        assertTrue(api.contains("addQueryParameter(\"order\", \"asc\")"))
        assertTrue(api.contains("addQueryParameter(\"cursor\", pageCursor)"))
        assertTrue("the measured error tag must be named", api.contains("InvalidCursorError"))
        val rawAt = api.indexOf("fun messagesRaw(")
        assertTrue(rawAt >= 0)
        val rawDoc = api.substring((rawAt - 1500).coerceAtLeast(0), rawAt)
        assertTrue("the messagesRaw contract names InvalidCursorError", rawDoc.contains("InvalidCursorError"))
        assertTrue("the stale InvalidRequestError tag must be gone from messagesRaw", !rawDoc.contains("InvalidRequestError"))
        // The session list is the opposite contract (limit/order MAY repeat
        // with cursor, measured 200) — the two comments must not be swapped.
        assertTrue(api.contains("cursor-must-ride-alone rule lives on `message`"))
    }

    @Test
    fun theExportAndBackgroundChainsRideTheRawDrain() {
        // ⋮ Export, the context tab and ExportSessionButton share
        // `fetchExportFrame`, which unwraps `sessionRaw` + the raw message drain
        // (`messagesRawPage` — the same cursor-alone loop, reporting the page-cap
        // truncation the export now writes into its payload).
        val transport = v2transport()
        assertTrue(transport.contains("val info = api.sessionRaw(sessionID)"))
        assertTrue(transport.contains("val drain = api.messagesRawPage(sessionID)"))
        assertTrue(transport.contains("messages = drain.messages"))
        assertTrue(transport.contains("truncated = drain.truncated"))
        // The background-tasks poll derives from the same raw rows.
        assertTrue(chatViewModel().contains("api.messagesRaw(id)"))
    }

    @Test
    fun theShellCreateButtonIsWiredAndTheTodoIsGone() {
        val panel = backgroundShellPanel()
        assertTrue(panel.contains("api.shellCreate("))
        assertTrue(panel.contains("ShellCreateOutcome"))
        assertTrue(panel.contains("R.string.oc_terminal_shell_create"))
        assertTrue("the inert plus button must be gone", !panel.contains("enabled = false"))
        assertTrue("the not-wired note must be gone", !panel.contains("not wired yet"))
        assertTrue("a failed create is reported", panel.contains("oc_terminal_shell_create_failed"))
        for (locale in listOf("values", "values-zh-rCN", "values-zh-rTW")) {
            val xml = source("src/main/res/$locale/strings.xml")
            for (key in listOf("oc_terminal_shell_create", "oc_terminal_shell_command_hint", "oc_terminal_shell_run", "oc_terminal_shell_create_failed")) {
                assertTrue("$locale is missing $key", xml.contains("name=\"$key\""))
            }
        }
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
