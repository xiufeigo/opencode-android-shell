package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.notify.AppNotification
import com.xiufeigo.opencodeshell.notify.AppNotificationKind
import com.xiufeigo.opencodeshell.ui.home.projectUnseenNotifications
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The V2-parity light-up round: five audit items whose *behaviour* was already
 * delivered but whose **wiring** was still missing — A-2 (tab/drawer unread),
 * A-3 (Home project rows), A-12 (camera permission), F-1 (`@` reference /
 * MCP-resource sources) and A-5 (live `mcp.*` events).
 *
 * Everything here is either the one pure piece the wiring added
 * ([projectUnseenNotifications], the project attribution behind
 * 「清除通知」) or a source pin — the repo's established guard
 * (`ShellWiringTest`, `Wave5IntegrationWiringTest`, `CatalogSettleTest`) for
 * integration that a JVM test cannot reach: the pieces are Compose composables,
 * a `LaunchedEffect` debounce and an `AndroidManifest.xml` entry.
 */
class V2ParityWiringTest {

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?.replace("\r\n", "\n")
            ?: error("missing $relative")

    private fun app() = source("src/main/java/com/xiufeigo/opencodeshell/ui/App.kt")

    private fun sessionScreen() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionScreen.kt")

    private fun homeScreen() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeScreen.kt")

    private fun drawer() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/drawer/SessionDrawer.kt")

    private fun notification(sessionID: String?, viewed: Boolean = false) = AppNotification(
        id = "id-$sessionID-$viewed",
        kind = AppNotificationKind.AGENT,
        sessionID = sessionID,
        sessionTitle = null,
        detail = null,
        time = 0L,
        viewed = viewed,
    )

    // ---------------------------------------------------------------- A-2

    @Test
    fun bothTabStripsReceiveTheBadgeDerivation() {
        val body = app()
        // The desktop titlebar and the mobile bottom bar are two mounts of the
        // same store, so both have to carry it — a badge on one placement only
        // is exactly the kind of half-wiring this round exists to close.
        assertEquals(2, Regex("tabBadge = tabBadge").findAll(body).count())
        // The derivation itself now lives in one place (`SessionDotSources`), and
        // both mounts read it through `tabBadge`.
        assertTrue(body.contains("val dotSources = SessionDotSources("))
        assertTrue(body.contains("val tabBadge: (SessionTabRef) -> SessionTabBadge = { tab -> dotSources.badge(tab.id) }"))
        val nav = source("src/main/java/com/xiufeigo/opencodeshell/ui/nav/SessionTabIndicator.kt")
        assertTrue(nav.contains("SessionTabIndicator.badge("))
        assertTrue(nav.contains("SessionTabIndicator.unseenCount("))
        assertTrue(nav.contains("autoApprove = autoApprove"))
        assertTrue(body.contains("autoApprove = settings.autoAcceptPermissions"))
        assertTrue(body.contains("trackedRunning = trackedRunStatus == ChatStore.RunStatus.RUNNING"))
        // The run status is de-duplicated off the combined `ChatState`, so the
        // strip does not recompose on every streamed token.
        assertTrue(body.contains("chatViewModel?.state?.map { it.runStatus }?.distinctUntilChanged()"))
    }

    @Test
    fun theDrawerRowCarriesTheSameUnreadIndicator() {
        val row = drawer()
        assertTrue(row.contains("unseen: (OpenCodeClient.Session) -> Boolean = { false }"))
        assertTrue(row.contains("unseen = unseen(session),"))
        // The same indicator the strips use, not a second dot implementation.
        assertTrue(row.contains("SessionTabBadgeSlot("))
        assertTrue(row.contains("SessionTabBadge(unread = true)"))
        // …and the same precedence: the dot wins over the marquee, because
        // `SessionTabIndicator.badge` never calls a session that needs attention
        // "loading".
        assertTrue(row.indexOf("if (unseen) {") < row.indexOf("} else if (running) {"))
        // The row's `unseen` is fed by the same derivation the strips read — one
        // `SessionDotSources` assembled from the location-wide ask ledger, the
        // in-app feed and the server row's own watermark
        // (`ChatViewModel.sessionUnread`, which is what a turn that settled while
        // no stream was open leaves behind), with the same auto-approve gate the
        // strips pass on the feed's permission rows.
        val screen = sessionScreen()
        assertTrue(screen.contains("unseen = { session -> dotSources.unread(session.id) }"))
        assertTrue(screen.contains("val dotSources = SessionDotSources("))
        assertTrue(screen.contains("autoApprove = autoApprovePermissions"))
        assertTrue(screen.contains("unread = sessionUnread,"))
    }

    // ---------------------------------------------------------------- A-3

    @Test
    fun anUnseenNotificationIsAttributedToItsSessionsProject() {
        val directories = listOf("/work/app")
        val map = mapOf(
            "ses_in" to "/work/app",
            "ses_nested" to "/work/app/src/main",
            "ses_out" to "/work/other",
            "ses_nodir" to "",
        )
        val feed = listOf(
            notification("ses_in"),
            notification("ses_nested"),
            notification("ses_out"),
            notification("ses_nodir"),
            notification(null),
        )
        assertEquals(
            listOf("ses_in", "ses_nested"),
            projectUnseenNotifications(feed, map, directories).map { it.sessionID },
        )
    }

    @Test
    fun aViewedNotificationNeverCounts() {
        val feed = listOf(
            notification("ses_in", viewed = true),
            notification("ses_in"),
        )
        assertEquals(
            1,
            projectUnseenNotifications(feed, mapOf("ses_in" to "/work/app"), listOf("/work/app")).size,
        )
    }

    @Test
    fun sandboxesCountAndUnknownDirectoriesDoNot() {
        val sandboxes = listOf("/work/app", "/work/app-feature")
        val map = mapOf(
            "ses_worktree" to "/work/app",
            "ses_sandbox" to "/work/app-feature",
            "ses_unknown" to "/tmp/scratch",
        )
        val feed = listOf(
            notification("ses_worktree"),
            notification("ses_sandbox"),
            notification("ses_unknown"),
            notification("ses_not_listed"),
        )
        assertEquals(
            listOf("ses_worktree", "ses_sandbox"),
            projectUnseenNotifications(feed, map, sandboxes).map { it.sessionID },
        )
        // A project with no directories at all (an unopened path) owns nothing —
        // 「清除通知」 stays disabled instead of clearing another project's rows.
        assertEquals(0, projectUnseenNotifications(feed, map, emptyList()).size)
    }

    @Test
    fun theProjectRowHostsAreWiredToBothColumns() {
        val body = homeScreen()
        // Desktop column + mobile dropdown: both mounts, like the tab badge.
        assertEquals(2, Regex("projectUnseen = projectUnseen").findAll(body).count())
        assertEquals(2, Regex("onClearProjectNotifications = onClearProjectNotifications").findAll(body).count())
        assertEquals(2, Regex("onSessionImported = onSessionImported").findAll(body).count())
        // The clear row acks the owning sessions through the view model's own
        // ledger — the same call the session screen makes on entry.
        assertTrue(body.contains(".forEach(viewModel::markNotificationsViewed)"))
        // `openProjectSession`: register the imported session's directory, then
        // open it.
        assertTrue(body.contains("viewModel.listSessions { list ->"))
        assertTrue(body.contains("?.let { registerProject(it) }\n                onOpenSession(id)"))
    }

    // --------------------------------------------------------------- A-12

    @Test
    fun theManifestDeclaresTheCameraItUses() {
        val manifest = source("src/main/AndroidManifest.xml")
        assertTrue(manifest.contains("""<uses-permission android:name="android.permission.CAMERA" />"""))
        // Optional hardware: the pairing panel falls back to a photo pick and
        // manual entry, so a camera-less device must still install.
        assertTrue(
            manifest.contains(
                """<uses-feature android:name="android.hardware.camera" android:required="false" />""",
            ),
        )
        // Declared next to INTERNET, and the package-visibility block untouched.
        assertTrue(
            manifest.indexOf("android.permission.INTERNET") <
                manifest.indexOf("android.permission.CAMERA"),
        )
        assertTrue(manifest.indexOf("android.permission.CAMERA") < manifest.indexOf("<queries>"))
        // The scanner that needs it really is the in-app camera2 preview.
        assertTrue(source("src/main/java/com/xiufeigo/opencodeshell/ui/settings/PairingPanel.kt")
            .contains("cameraPermissionLauncher.launch(Manifest.permission.CAMERA)"))
    }

    // ---------------------------------------------------------------- F-1

    @Test
    fun theComposerGetsBothMentionSources() {
        val body = sessionScreen()
        assertTrue(body.contains("val availableReferences by chatViewModel.availableReferences.collectAsState()"))
        assertTrue(body.contains("val availableResources by chatViewModel.availableResources.collectAsState()"))
        assertTrue(body.contains("references = availableReferences,"))
        assertTrue(body.contains("resources = availableResources,"))
        // …and the menu really consumes them (`@` categories), not just stores
        // them: the screen is only the transport.
        val composer = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/Composer.kt")
        assertTrue(composer.contains("references = references,"))
        assertTrue(composer.contains("resources = resources,"))
    }

    @Test
    fun theTimelineImageReaderIsScopedToTheSessionDirectory() {
        // A-1's reader is built *inside* the timeline, so the directory has to
        // travel as a parameter — a provider around the timeline would be
        // overridden by the timeline's own one.
        val timeline = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/timeline/MessageTimeline.kt")
        assertTrue(timeline.contains("imageDirectory: String? = null,"))
        assertTrue(timeline.contains("remember(imageContext, imageDirectory) {"))
        assertTrue(timeline.contains("MarkdownImages.reader(imageContext, imageDirectory)"))
        assertTrue(sessionScreen().contains("imageDirectory = reviewRootPath,"))
    }

    // ---------------------------------------------------------------- A-5

    @Test
    fun theMcpRevisionIsSettledOnceAtTheSource() {
        val body = app()
        assertTrue(body.contains("chatViewModel?.mcpRevision ?: NO_MCP_REVISION"))
        assertTrue(body.contains("LocalMcpRevision provides settledMcpRevision,"))
        // The settle itself, at the upstream window (`data.ts:79` `settleMs`).
        val panels = source("src/main/java/com/xiufeigo/opencodeshell/ui/settings/SettingsPanels.kt")
        assertTrue(panels.contains("private const val MCP_SETTLE_MS = 150L"))
        assertTrue(panels.contains("fun rememberSettledRevision(revision: Int, settleMillis: Long = MCP_SETTLE_MS): Int"))
        // Debounce shape: a newer value restarts the delay, so only the last
        // event of a burst lands.
        assertTrue(panels.contains("LaunchedEffect(revision) {"))
        assertTrue(panels.contains("if (revision == settled) return@LaunchedEffect"))
        assertTrue(panels.contains("delay(settleMillis)"))
    }

    @Test
    fun bothMcpConsumersKeyTheirFetchOnTheSettledBurst() {
        // The settings Extensions page: the burst is a second key on the MCP
        // page only (upstream refreshes the mcp server list, not skills/plugins).
        val panels = source("src/main/java/com/xiufeigo/opencodeshell/ui/settings/SettingsPanels.kt")
        assertTrue(panels.contains("val mcpBurst = rememberMcpBurstKey(LocalMcpRevision.current)"))
        assertTrue(panels.contains("rememberServerPage(source, reload, mcpBurst) { it.mcpServers() }"))
        assertTrue(panels.contains("LaunchedEffect(mcpResourcesOpen, source, mcpBurst) {"))
        // The chooser: the same key on its one list read.
        val chooser = source("src/main/java/com/xiufeigo/opencodeshell/ui/overlay/McpChooserDialog.kt")
        assertTrue(chooser.contains("val mcpBurst = rememberMcpBurstKey(LocalMcpRevision.current)"))
        assertTrue(chooser.contains("LaunchedEffect(source, reload, mcpBurst) {"))
    }
}
