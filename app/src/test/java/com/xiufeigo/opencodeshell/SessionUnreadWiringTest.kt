package com.xiufeigo.opencodeshell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A-2 / B2 wiring guards for the parts a JVM test cannot exercise: the SSE
 * listener's cross-thread reads, the shell's route report, and the two call
 * sites that render the dot. Same source-level style as [Wave5EventsWiringTest]
 * — these are the facts a refactor would silently break.
 *
 * The three the v15 device run found (see `research/v15_verify/mcp_nav/`):
 * 1. a background turn completion raised *nothing*: `viewed` was computed from
 *    the tracked session, which outlives its screen, so a completion was marked
 *    seen whenever the user had merely left the session (Home) while the
 *    ViewModel still tracked it;
 * 2. the dot's only source was the in-app feed, which needs a live stream — and
 *    this shell opens none off the session route, so a completion that landed
 *    while the user was on Home could never light anything;
 * 3. a successful `DELETE /session/:id` left the drawer row (and the tab) in
 *    place, because the removal waited for a `session.deleted` frame the closed
 *    stream never delivered.
 */
class SessionUnreadWiringTest {

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("missing $relative")

    private fun viewModel() = source("src/main/java/com/xiufeigo/opencodeshell/ChatViewModel.kt")

    private fun app() = source("src/main/java/com/xiufeigo/opencodeshell/ui/App.kt")

    private fun sessionScreen() = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionScreen.kt")

    private fun homeScreen() = source("src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeScreen.kt")

    private fun homeSessionsList() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeSessionsList.kt")

    private fun indicator() = source("src/main/java/com/xiufeigo/opencodeshell/ui/nav/SessionTabIndicator.kt")

    // ------------------------------------------------ 1. `viewed` is the route

    @Test
    fun aRaisedNotificationIsSeenOnlyForTheSessionOnScreen() {
        val vm = viewModel()
        assertTrue(
            "the raised notification's `viewed` must follow the visible route",
            vm.contains("viewed = sessionID != null && sessionID == visibleSessionID"),
        )
        // The route, not the tracked id — the tracked session outlives its
        // screen (`ui/App.kt` clears the visible one on every route change).
        assertTrue(
            "the tracked session must no longer decide `viewed`",
            !vm.contains("viewed = sessionID != null && sessionID == this.sessionID"),
        )
        assertTrue(vm.contains("private var visibleSessionID: String? = null"))
        assertTrue(vm.contains("fun setSessionVisible(id: String?)"))
        assertTrue("opening a session makes it the visible one", vm.contains("visibleSessionID = id"))
        assertTrue("a new session has nothing on screen", vm.contains("visibleSessionID = null"))
    }

    @Test
    fun theShellReportsTheRouteThatOwnsTheSession() {
        val shell = app()
        assertTrue(
            "the destination listener reports the route's session",
            shell.contains("chatViewModel?.setSessionVisible(currentSession)"),
        )
        // …synchronously, inside the listener: `SessionScreen`'s own entry
        // effect acks the unread watermark, and that ack is gated on this value,
        // so a `LaunchedEffect` ordering would make the entry ack racy.
        val listenerAt = shell.indexOf("onDestinationChanged")
        val reportAt = shell.indexOf("chatViewModel?.setSessionVisible(currentSession)")
        val listenerEndsAt = shell.indexOf("navController.addOnDestinationChangedListener(listener)")
        assertTrue("the listener exists", listenerAt >= 0)
        assertTrue("the listener registration closes it", listenerEndsAt > listenerAt)
        assertTrue(
            "the report sits in the listener, not in an effect",
            reportAt in listenerAt until listenerEndsAt,
        )
    }

    @Test
    fun theIdleAckOnlyAcknowledgesWhatIsOnScreen() {
        val vm = viewModel()
        val ackAt = vm.indexOf("fun ackSessionViewed()")
        assertTrue("the ack exists", ackAt >= 0)
        val body = vm.substring(ackAt, ackAt + 2_400)
        assertTrue(
            "the ack must not fire for a session the user is not looking at",
            body.contains("if (visibleSessionID != id) return"),
        )
        // The body is the row's **current** idle: the value the last list read
        // carried is structurally one terminal transition behind the boundary
        // being acknowledged, and posting it leaves `time.idle > time.viewed`
        // true (the dot that re-lights on the next read).
        assertTrue("the current idle is read off the row", body.contains("val fresh = currentIdle(id) ?: knownIdle"))
        assertTrue(
            "the ack posts the row's watermark, with the local clock as the last resort",
            body.contains("val idle = fresh ?: System.currentTimeMillis()"),
        )
        // A route change mid-wait/mid-read drops the ack.
        assertTrue(body.contains("if (sessionID != id || visibleSessionID != id) return@launch"))
        // …and mirrors it locally, or the dot lingers on the row just opened
        // until some later list read lands after the POST.
        assertTrue(body.contains("_sessionUnread.value = _sessionUnread.value + (id to known.ackedAt(knownIdle))"))
        assertTrue(body.contains("_sessionUnread.value = _sessionUnread.value + (id to prev.ackedAt(fresh))"))
        // The read itself: the single-session route, same `time` object the list
        // read parses.
        assertTrue(vm.contains("private suspend fun currentIdle(id: String): Long?"))
        assertTrue(vm.contains("api.session(id).toUnread().idle"))
    }

    @Test
    fun theIdleAckReadsTheRowOnlyAfterTheWaitSettles() {
        val vm = viewModel()
        val ackAt = vm.indexOf("fun ackSessionViewed()")
        assertTrue("the ack exists", ackAt >= 0)
        val body = vm.substring(ackAt, ackAt + 2_400)

        // The boundary being acknowledged is the settle the `wait` resolves on,
        // and the row's `time.idle` is committed by that same terminal event
        // (`bus.ts`: the projector runs inside the transaction the frame is
        // published after). A read issued ahead of the wait therefore hands back
        // the *previous* transition — the stale watermark that leaves
        // `time.idle > time.viewed` true and re-lights the dot on every later
        // read, i.e. the v17 `switch_perm` symptom this path exists to fix.
        val waitAt = body.indexOf("awaitSessionIdle(id)")
        val readAt = body.indexOf("val fresh = currentIdle(id) ?: knownIdle")
        assertTrue("the ack parks on the session's idle first", waitAt >= 0)
        assertTrue("the ack reads the row", readAt >= 0)
        assertTrue("the row is read after the wait, not before it", waitAt < readAt)

        // …and the ack itself is never gated on the wait's verdict: a wait that
        // cannot be issued (no v2 API) or fails must not skip `POST …/view`,
        // which upstream never gates on the wait either — a skipped ack leaves
        // the row unread for every other client.
        assertTrue("the ack posts the boundary it read", body.contains("markSessionViewed(idle)"))
        assertFalse("the ack must not sit behind the wait's callback", body.contains("waitSession {"))
        assertFalse("the ack must not sit behind the wait's verdict", body.contains("ok &&"))
        assertTrue(vm.contains("private suspend fun awaitSessionIdle(id: String): Boolean"))
        assertTrue(
            "a wait that cannot be issued answers false instead of dropping the caller",
            vm.contains("val api = v2Api() ?: return false"),
        )
        // The public seam stays: it is `waitSession`'s body the ack parks on.
        assertTrue(vm.contains("val ok = awaitSessionIdle(id)"))
        assertTrue(vm.contains("fun waitSession(onResult: (Boolean) -> Unit = {})"))
    }

    // ------------------------------------- the auto-approve ledger (v17 chain a)

    @Test
    fun theAutoApproverClosesTheLedgerEntryItAnswered() {
        val vm = viewModel()
        val at = vm.indexOf("private fun autoApprove(request: OpenCodeClient.PermissionRequest)")
        assertTrue("the auto-approver exists", at >= 0)
        val body = vm.substring(at, at + 1_600)
        // Upstream's auto-approver only answers the server — it can, because its
        // ledger holds no permission row. This shell's does, and an entry raised
        // `viewed = false` (the ask landed while the user was elsewhere) stays
        // unseen for the whole process unless the reply closes it.
        assertTrue(body.contains("appNotifier.markKindViewed("))
        assertTrue(body.contains("kind = AppNotificationKind.PERMISSION"))
        assertTrue("the mark sits behind the setting gate", body.indexOf("tryMark") < body.indexOf("markKindViewed"))
    }

    @Test
    fun everyDotSurfaceGatesThePermissionLedgerOnTheSetting() {
        // The gate itself moved into the one derivation every surface now calls
        // (`SessionDotSources`), which is *stronger* than the three per-surface
        // copies it replaced: a surface can no longer forget it.
        val nav = indicator()
        assertTrue(nav.contains("data class SessionDotSources("))
        assertTrue(nav.contains("autoApprove: Boolean = false"))
        assertTrue(nav.contains("unseenNotifications = SessionTabIndicator.unseenCount(notifications, sessionID, autoApprove)"))
        assertTrue(nav.contains("!(autoApprove && it.kind == AppNotificationKind.PERMISSION)"))

        // …and the three surfaces all build that one object off the same setting.
        val shell = app()
        assertTrue("the tab strip assembles the shared sources", shell.contains("val dotSources = SessionDotSources("))
        assertTrue(shell.contains("autoApprove = settings.autoAcceptPermissions"))
        val screen = sessionScreen()
        assertTrue("the session drawer assembles them too", screen.contains("val dotSources = SessionDotSources("))
        assertTrue(screen.contains("autoApprove = autoApprovePermissions"))
        assertTrue(screen.contains("chatViewModel.autoApprovePermissions.collectAsState()"))
        val home = homeScreen()
        assertTrue("Home's session rows assemble them too", home.contains("val dotSources = SessionDotSources("))
        assertTrue(home.contains("autoApprove = autoApprovePermissions"))
        assertTrue(viewModel().contains("val autoApprovePermissions: StateFlow<Boolean>"))
    }

    @Test
    fun theDrawerDotAlsoAnswersThePendingAsk() {
        val screen = sessionScreen()
        // The row answers the *same* derivation the strips do, permission/form
        // half included. Without it an ask that lands while its session is on
        // screen lights the strip and not the row: that entry is raised
        // `viewed = true`, and the server watermark never moves on an ask.
        assertTrue(
            "the drawer's dot lambda answers the shared derivation",
            screen.contains("unseen = { session -> dotSources.unread(session.id) }"),
        )
        val at = screen.indexOf("val dotSources = SessionDotSources(")
        assertTrue("the drawer assembles the sources", at >= 0)
        val body = screen.substring(at, at + 700)
        assertTrue(body.contains("trackedPermission = pendingPermission != null"))
        assertTrue(body.contains("trackedForm = pendingForm != null"))
        assertTrue(body.contains("asks = sessionAsks"))
        assertTrue(body.contains("trackedSessionID = chatViewModel.sessionID"))

        // The strip answers the same two terms through the same object, so the
        // two surfaces cannot drift apart.
        val shell = app()
        assertTrue(shell.contains("val tabBadge: (SessionTabRef) -> SessionTabBadge = { tab -> dotSources.badge(tab.id) }"))
        val stripAt = shell.indexOf("val dotSources = SessionDotSources(")
        val stripBody = shell.substring(stripAt, stripAt + 700)
        assertTrue(stripBody.contains("trackedPermission = pendingPermission != null"))
        assertTrue(stripBody.contains("trackedForm = pendingForm != null"))
        assertTrue(stripBody.contains("asks = sessionAsks"))
        assertTrue(stripBody.contains("trackedSessionID = trackedSessionId"))
    }

    /**
     * The v28 `t2_envtweak` run (`research/v28_verify/t2_envtweak/`): with the
     * permission dock up, *both* drawer screenshots showed a session list with no
     * dot anywhere — Home's. `HomeSessionRowUI` renders `SessionTabAvatarView`
     * upstream (`home/sessions/view.tsx:219-228`), the same dot / marquee the tab
     * strip draws, and this shell's port had dropped it, so the one list the user
     * was looking at could not report an ask at all.
     */
    @Test
    fun homeSessionRowsCarryTheAttentionDot() {
        val home = homeScreen()
        val at = home.indexOf("val dotSources = SessionDotSources(")
        assertTrue("Home assembles the shared sources", at >= 0)
        val body = home.substring(at, at + 900)
        assertTrue("…off the location-wide ask ledger", body.contains("asks = sessionAsks"))
        assertTrue(body.contains("trackedPermission = pendingPermission != null"))
        assertTrue(body.contains("trackedForm = pendingForm != null"))
        assertTrue("…and the server row's watermark", body.contains("unread = sessionUnread"))
        assertTrue(
            "the rows get both halves of the badge",
            home.contains("val rowUnread: (String) -> Boolean = { id -> dotSources.unread(id) }"),
        )
        assertTrue(home.contains("val rowLoading: (String) -> Boolean = { id -> dotSources.badge(id).loading }"))
        // Both column layouts (wide two-column and phone) get them.
        assertEquals(
            "every HomeSessionsColumn call passes the dot inputs",
            home.split("unread = rowUnread,").size - 1,
            home.split("HomeSessionsColumn(").size - 1,
        )
        assertTrue(home.contains("running = rowLoading,"))

        // The row renders them: the dot on the avatar's corner, the marquee
        // instead of the avatar while the session works.
        val list = homeSessionsList()
        assertTrue(list.contains("HomeSessionLeading(row = row, open = open, unread = unread, loading = loading)"))
        assertTrue(list.contains("if (unread) SessionAvatarUnreadDot(avatarSize = AVATAR_SIZE)"))
        assertTrue(list.contains("SessionProgressIndicator(modifier = Modifier.align(Alignment.TopStart), size = AVATAR_SIZE)"))
        // Defaulted, so a preview / isolated host renders the row it always did.
        assertTrue(list.contains("unread: (String) -> Boolean = { false }"))
        assertTrue(list.contains("running: (String) -> Boolean = { false }"))
    }

    // ------------------------------------- 2. the dot reads the server watermark

    @Test
    fun theViewModelPublishesTheSessionRowsUnreadWatermark() {
        val vm = viewModel()
        assertTrue(vm.contains("val sessionUnread: StateFlow<Map<String, SessionUnread>>"))
        assertTrue("the row's watermark is parsed off the raw payload", vm.contains("row.toUnread()"))
        assertTrue(
            "a read must not un-read a transition this client already acknowledged",
            vm.contains("row.toUnread().withAcknowledged(acknowledged[row.id])"),
        )
        assertTrue(vm.contains("private fun V2Api.SessionInfo.toUnread(): SessionUnread"))
        assertTrue(vm.contains("time?.optLong(\"idle\", 0L)"))
        assertTrue(vm.contains("time?.optLong(\"viewed\", 0L)"))
        assertTrue(
            "rows and watermarks are published by the same read",
            vm.contains("_sessionUnread.value = unread"),
        )
    }

    @Test
    fun everyDotSurfacePassesTheServerWatermark() {
        val shell = app()
        assertTrue(
            "the tab strip's sources carry the watermark",
            shell.contains("unread = sessionUnread,"),
        )
        assertTrue(shell.contains("chatViewModel?.sessionUnread ?: NO_SESSION_UNREAD"))
        assertTrue(
            "the session drawer's rows pass it too",
            sessionScreen().contains("unread = sessionUnread,"),
        )
        assertTrue(
            "and so do Home's",
            homeScreen().contains("unread = sessionUnread,"),
        )
        // The derivation itself: a default keeps every other host rendering what
        // it always did, so the parameter has to stay optional.
        assertTrue(indicator().contains("serverUnread: Boolean = false"))
        // The three-term OR now lives in `unread` — the drawer row's dot — and
        // `badge` takes its own `unread` from there, so the strip and the drawer
        // answer one derivation instead of two transcriptions of it.
        assertTrue(indicator().contains("fun unread("))
        assertTrue(indicator().contains("unseenNotifications > 0 ||"))
        assertTrue(indicator().contains("unread = unread("))
    }

    // ------------------------------------- the location-wide pending-ask ledger

    /**
     * The ask halves of the dot are keyed by **session**, not by "the session on
     * screen" (upstream `project-avatar-state.ts:26-35` reads
     * `ctx.data.session.permission.list` / `session.form.list`, both
     * location-wide). `ChatStore.pendingPermissions` is the tracked session's
     * dock list — every ask event is gated by `tracksSession` — so on its own it
     * can never light a *foreign* session's row, which is the second scenario the
     * v28 device run reported dark.
     */
    @Test
    fun theViewModelPublishesTheLocationWideAskLedger() {
        val vm = viewModel()
        assertTrue(vm.contains("private val _askLedger = MutableStateFlow(SessionAskLedger())"))
        assertTrue(
            "a reconnect restarts it — a reply nobody heard must not keep a row lit",
            vm.contains("_askLedger.update { SessionAskLedger() }"),
        )
        assertTrue(
            "every fold is atomic — the frame path and the REST reads race",
            !vm.contains("_askLedger.value = _askLedger"),
        )
        assertTrue(vm.contains("val sessionAsks: StateFlow<Map<String, SessionAsks>> ="))
        assertTrue(
            "the frame path feeds it for every session, next to the feed",
            vm.contains("recordAsk(event, routed)"),
        )
        assertTrue("the folding lives in the ledger type", vm.contains("private fun recordAsk(event: ChatStore.Event, routed: String?)"))
        assertTrue(vm.contains("current.asked(event.request.id, event.request.sessionID.ifEmpty { routed.orEmpty() })"))
        assertTrue(vm.contains("is ChatStore.Event.PermissionReplied -> current.replied(event.requestID)"))
        assertTrue(vm.contains("current.formCreated(event.form.id, event.form.sessionID.ifEmpty { routed.orEmpty() })"))
        assertTrue(vm.contains("is ChatStore.Event.FormResolved -> current.formResolved(event.formID)"))
        // The tracked session's own REST snapshots are authoritative for it…
        assertTrue(vm.contains("_askLedger.update { it.permissionSnapshot(id, requests.map { request -> request.id }) }"))
        assertTrue(vm.contains("_askLedger.update { it.formSnapshot(id, forms.map { form -> form.id }) }"))
        // …the location-wide reads union in what no stream was open to hear…
        assertTrue(vm.contains("mergeLocationAsks(api)"))
        assertTrue(vm.contains("api.pendingPermissions(directory).map { it.id to it.sessionID }"))
        assertTrue(vm.contains("api.pendingForms(directory).map { it.id to it.sessionID }"))
        // …and a deleted session takes its asks with it.
        assertTrue(vm.contains("_askLedger.update { it.forget(id) }"))
        // The three surfaces read it.
        assertTrue(app().contains("chatViewModel?.sessionAsks ?: NO_SESSION_ASKS"))
        assertTrue(sessionScreen().contains("chatViewModel.sessionAsks.collectAsState()"))
        assertTrue(homeScreen().contains("chatViewModel?.sessionAsks ?: NO_SESSION_ASKS_FLOW"))
    }

    // ------------------------------------------------- 3. delete drops the row

    @Test
    fun aSuccessfulDeleteDropsTheRowLocally() {
        val vm = viewModel()
        val deleteAt = vm.indexOf("fun deleteSession(")
        assertTrue("the delete exists", deleteAt >= 0)
        val body = vm.substring(deleteAt, deleteAt + 1_600)
        assertTrue(
            "the deleted row's watermark goes with it",
            body.contains("_sessionUnread.value = _sessionUnread.value.filterKeys { it != id }"),
        )
        assertTrue(
            "the list consumers must be re-driven without waiting for the frame",
            body.contains("_sessionsRevision.value += 1"),
        )
        // Still only on the server's answer: a failed delete keeps the row.
        assertTrue("the drop is gated on the server's answer", body.contains("if (deleted) {"))
    }

    @Test
    fun theTabPruneSurvivesAFailedListRead() {
        val shell = app()
        assertTrue(
            "an empty list from a failed read must not drop every tab",
            shell.contains("if (viewModel.sessionsLoadFailed.value) return@listSessions"),
        )
        // The prune is no longer inline (`openSessions.removeAll { ... }`) - it
        // runs through the pure decision `SessionTabs.pruneAgainstServerList`
        // (v50 LOW-2a/-2b), whose `protectedIds` cover the sessions this run
        // created. The failed-read contract therefore
        // rests on two pins, both of which must hold: the guard above, and the
        // guard standing *between* the read and the prune call - a prune that
        // moved ahead of the guard would prune against a failed read.
        val guard = "if (viewModel.sessionsLoadFailed.value) return@listSessions"
        val prune = "SessionTabs.pruneAgainstServerList("
        val guardAt = shell.indexOf(guard)
        val pruneAt = shell.indexOf(prune)
        assertTrue("the failed-read guard is gone", guardAt >= 0)
        assertTrue("the prune call is gone", pruneAt >= 0)
        assertTrue(
            "the failed-read guard must stand between listSessions and the prune",
            guardAt < pruneAt,
        )
        // v51 HIGH-2: the escape hatch narrowed to pendingCreatedSessions only.
        // The unconditional `+ listOfNotNull(currentSession)` kept a dead tab
        // alive forever whenever it happened to be the one on screen, so a
        // cold start restored a tab whose session no longer exists. A dead tab
        // must now be prunable even when it is the current session.
        assertTrue(
            "the prune must keep the protected-id escape hatch",
            shell.contains("protectedIds = pendingCreatedSessions.toSet()"),
        )
        val protectedAt = shell.indexOf("protectedIds = pendingCreatedSessions.toSet()")
        val protectedLine = shell.substring(protectedAt, shell.indexOf("\n", protectedAt))
        assertTrue(
            "the escape hatch must cover only this run's pending creations",
            !protectedLine.contains("currentSession"),
        )
    }

    @Test
    fun theForegroundReReadsTheSessionList() {
        // The watermark only helps once a list read has happened, and while no
        // session screen is mounted nothing else reads it (no stream, no poll) —
        // so activation is the trigger.
        val shell = app()
        assertTrue(shell.contains("LocalLifecycleOwner.current"))
        assertTrue(shell.contains("if (event == Lifecycle.Event.ON_START) viewModel.listSessions {}"))
        assertTrue(shell.contains("lifecycleOwner.lifecycle.addObserver(observer)"))
        assertTrue(shell.contains("onDispose { if (observer != null) lifecycleOwner.lifecycle.removeObserver(observer) }"))
    }
}
