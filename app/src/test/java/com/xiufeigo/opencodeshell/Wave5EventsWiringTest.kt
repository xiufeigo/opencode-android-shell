package com.xiufeigo.opencodeshell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * W5-E wiring guards. The parts that cannot be asserted on the JVM — the SSE
 * listener's ordering, the revision flows the UI owner will key on, and the
 * notifications' settings gate — get the repo's established source-level check
 * (`ShellWiringTest`, `Wave5ComposerWiringTest`).
 *
 * The two ordering facts pinned here are the ones a refactor would silently
 * break:
 * 1. signals and notifications are consumed **before** the session gate (a
 *    `session.created` for another project, or a foreign session's ask, must
 *    still arrive — `ChatStore.acceptsSession` would drop both);
 * 2. every E2 revision flow is bumped by its own signal branch, so a wiring
 *    that reads `projectsRevision` cannot be left without a producer.
 */
class Wave5EventsWiringTest {

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("missing $relative")

    private fun viewModel() = source("src/main/java/com/xiufeigo/opencodeshell/ChatViewModel.kt")

    private fun store() = source("src/main/java/com/xiufeigo/opencodeshell/ChatStore.kt")

    private fun notifier() = source("src/main/java/com/xiufeigo/opencodeshell/notify/AppNotifier.kt")

    // ------------------------------------------------- E2: signal consumption

    @Test
    fun theListenerConsumesSignalsAndNotificationsBeforeTheSessionGate() {
        val vm = viewModel()
        val signalAt = vm.indexOf("onServerSignal(event)")
        val notifyAt = vm.indexOf("notifyFor(event, routed)")
        val gateAt = vm.indexOf("if (!acceptsV2Session(sessionID, routed)) continue")
        assertTrue("the server-signal branch is wired", signalAt >= 0)
        assertTrue("the notification trigger is wired", notifyAt >= 0)
        assertTrue("the gate is still there", gateAt >= 0)
        assertTrue("signals must be read before the session gate", signalAt < gateAt)
        assertTrue("notifications must be raised before the session gate", notifyAt < gateAt)
    }

    @Test
    fun everyRevisionFlowHasItsProducer() {
        val vm = viewModel()
        val flows = mapOf(
            "projectsRevision" to "ChatStore.Signal.PROJECT_UPDATED -> _projectsRevision.value += 1",
            "vcsRevision" to "ChatStore.Signal.VCS_BRANCH_UPDATED -> _vcsRevision.value += 1",
            "filesRevision" to "ChatStore.Signal.FILE_EDITED -> _filesRevision.value += 1",
            "ptyRevision" to "-> _ptyRevision.value += 1",
            // W2 A-5: the MCP settle frames used to be dropped, so this flow is
            // new — pin its producer too.
            "mcpRevision" to "-> _mcpRevision.value += 1",
        )
        for ((flow, bump) in flows) {
            assertTrue("$flow must be exposed", vm.contains("val $flow: StateFlow<Int>"))
            assertTrue("$flow must have a producer", vm.contains(bump))
        }
        // Session signals are a multi-label branch: both types share one bump.
        assertTrue(vm.contains("ChatStore.Signal.SESSION_CREATED,"))
        assertTrue(vm.contains("ChatStore.Signal.SESSION_DELETED,"))
        assertTrue(vm.contains("-> _sessionsRevision.value += 1"))
        assertTrue(vm.contains("val sessionsRevision: StateFlow<Int>"))
        // The catalog signals actually re-read the catalogs instead of bumping.
        assertTrue(vm.contains("-> catalogRefresh()"))
        assertTrue(vm.contains("refreshProviders()"))
        assertTrue(vm.contains("refreshCatalogs()"))
        assertTrue("a burst of catalog frames must be throttled", vm.contains("CATALOG_REFRESH_THROTTLE_MS"))
    }

    @Test
    fun theStoreTurnsTheWiredEventTypesIntosignals() {
        val store = store()
        for (wireType in listOf(
            "provider.updated",
            "model.updated",
            "agent.updated",
            "config.updated",
            "project.updated",
            "vcs.branch.updated",
            "file.edited",
            "session.created",
            "session.deleted",
            "pty.created",
            "pty.updated",
            "pty.exited",
            "pty.deleted",
        )) {
            assertTrue("$wireType must be modelled", store.contains("\"$wireType\""))
        }
        // Unknown types fall through in the v2 adapter (parseEvent is gone).
        val adapter = source("src/main/java/com/xiufeigo/opencodeshell/v2/V2EventAdapter.kt")
        assertTrue("unknown types must keep falling through", adapter.contains("Signal.ofWireType(frame.type)"))
        assertTrue("a signal must not change the chat state", store.contains("is Event.ServerSignal -> state"))
    }

    // ------------------------------------------------------- E3: parse gaps

    @Test
    fun theDeltaReducerAddressesEveryModelledStringField() {
        val store = store()
        // The old `if (event.field != "text") return state` early-out is gone.
        assertTrue(store.contains("private fun withFieldDelta("))
        assertTrue(store.contains("\"state.output\", \"output\" ->"))
        assertTrue(store.contains("\"state.error\", \"error\" ->"))
        assertTrue(store.contains("\"reasoning\" ->"))
        assertTrue(store.contains("else -> null"))
    }

    @Test
    fun theReplyPayloadsReachTheState() {
        val store = store()
        assertTrue(store.contains("permissionReplies = state.permissionReplies"))
        // The reply value itself must reach the record, not just the removal.
        val vm = viewModel()
        assertTrue(vm.contains("ChatStore.Event.PermissionReplied(requestID, reply)"))
    }

    // ----------------------------------------------------- E5: notifications

    @Test
    fun theViewModelExposesTheNotificationFeedAndItsThreeGates() {
        val vm = viewModel()
        assertTrue(vm.contains("val notifications: StateFlow<List<AppNotification>>"))
        // B-2/B-3 added the two Android halves to the constructor call; the
        // settings gate is still `enabled = ::notificationEnabled`.
        assertTrue(vm.contains("private val appNotifier = AppNotifier("))
        assertTrue(vm.contains("enabled = ::notificationEnabled,"))
        assertTrue(vm.contains("fun dismissNotification(id: String)"))
        assertTrue("the settings are real consumers now", vm.contains("settings.notifyAgent"))
        assertTrue(vm.contains("settings.notifyPermissions"))
        assertTrue(vm.contains("settings.notifyErrors"))
        // All four triggers are wired (v2 forms carry the question dock), and each
        // turn-scoped raise carries the turn's own date (`since`) so the delivery
        // decision can ask whether the user watched it — see
        // `NotifyBackgroundDeliveryTest`.
        assertTrue(vm.contains("is ChatStore.Event.PermissionAsked -> raise("))
        assertTrue(vm.contains("is ChatStore.Event.FormCreated -> raise("))
        assertTrue(
            vm.contains(
                "raise(AppNotificationKind.ERROR, sessionID = routed, detail = event.error, since = since)",
            ),
        )
        assertTrue(vm.contains("raise(AppNotificationKind.AGENT, sessionID = routed, since = since)"))
        // …and the initial state of the notifier itself is data-only.
        assertTrue(notifier().contains("val notifications: StateFlow<List<AppNotification>>"))
        assertTrue(notifier().contains("DEDUPE_WINDOW_MS = 5_000L"))
    }

    /**
     * B-2 device fix (v29 tB: a backgrounded turn finished and nothing was ever
     * posted). The turn-complete trigger may not ask the *store* for the run
     * status: `applyEvent` runs through `scope.launch`, so a read that carries a
     * whole turn (a durable replay after a reconnect — "session.idle can be
     * missed while backgrounded", in `refreshSnapshot`'s own words) reaches the
     * terminal frame with the store still on the previous status, and the
     * reconciliation path clears it before a late frame arrives. The guard reads
     * the wire's status ([TurnRunStatus]), and the reconciliation that concludes
     * the turn ended raises the notification itself — the only carrier left once
     * the frame is gone. Since v50 the mirror also remembers whether the run
     * produced a step, because a session update is a busy → idle pair as well.
     */
    @Test
    fun theTurnCompleteTriggerReadsTheWireAndTheReconciliationRaises() {
        val vm = viewModel()
        // The mirror: fed by the stream's status frames and the server's live set.
        assertTrue(vm.contains("private val turnRunStatus = TurnRunStatus()"))
        assertTrue(vm.contains("ChatStore.RunStatus.RUNNING -> turnRunStatus.running(routed)"))
        assertTrue(vm.contains("turnRunStatus.running(id)"))
        // v50 tD: a session *update* (the 「移动到…」 move) is a busy → idle pair
        // too, so the guard also asks whether the run produced a step.
        assertTrue(vm.contains("val finished = !foreign && turnRunStatus.ranTurn(routed)"))
        assertTrue(vm.contains("turnRunStatus.step(routed)"))
        // …and reset when the tracked session changes.
        assertTrue(vm.contains("turnRunStatus.watch(id)"))
        assertTrue(vm.contains("turnRunStatus.watch(null)"))
        // The reconciliation raise: `sessionID = id` is the tracked session, and
        // `since` is that turn's own start on the client clock — the reference
        // that keeps a raise concluded on the way back to the foreground from
        // being suppressed as "already seen" (device v30 tA).
        assertTrue(vm.contains("raise(AppNotificationKind.AGENT, sessionID = id, since = since)"))
        // The store-based guard is gone.
        assertFalse(vm.contains("val finished = !foreign && _state.value.runStatus == ChatStore.RunStatus.RUNNING"))
    }

    /**
     * The `?after=` this session's log replay depends on is this session's own
     * cursor. The global stream carries every session and each counts its own
     * `durable.seq`, so only the tracked aggregate may move the watermark —
     * otherwise the replay skips exactly the frames the notification needs
     * (`session.execution.succeeded`, `permission.asked`).
     */
    @Test
    fun theSessionLogWatermarkFollowsOnlyTheTrackedAggregate() {
        val vm = viewModel()
        assertTrue(
            vm.contains("seqTracker.observe(frame.id, frame.seq, aggregate = frame.aggregateID == sessionID)"),
        )
    }

    // ------------------------------------------------------------- strings

    private val notifyKeys = listOf(
        "oc_notify_title_agent",
        "oc_notify_title_permission",
        "oc_notify_title_question",
        "oc_notify_title_error",
        "oc_notify_dismiss",
        "oc_notify_dismiss_all",
    )

    @Test
    fun everyNotifyStringExistsInAllThreeLocales() {
        for (locale in listOf("values", "values-zh-rCN", "values-zh-rTW")) {
            val xml = source("src/main/res/$locale/strings.xml")
            val missing = notifyKeys.filterNot { xml.contains("name=\"$it\"") }
            assertEquals("$locale is missing $missing", emptyList<String>(), missing)
        }
    }

    @Test
    fun theNotifyStringsAreLocalised() {
        val english = source("src/main/res/values/strings.xml")
        for (locale in listOf("values-zh-rCN", "values-zh-rTW")) {
            val xml = source("src/main/res/$locale/strings.xml")
            for (key in notifyKeys) {
                val value = xml.lineSequence().firstOrNull { it.contains("name=\"$key\"") }
                    ?.substringAfter('>')?.substringBefore('<')
                val source = english.lineSequence().firstOrNull { it.contains("name=\"$key\"") }
                    ?.substringAfter('>')?.substringBefore('<')
                assertTrue("$locale/$key is still the English copy", value != source)
            }
        }
    }
}
