package com.xiufeigo.opencodeshell

import org.junit.Assert.assertEquals
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
        val gateAt = vm.indexOf("acceptsSession(sessionID, inner)")
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
        assertTrue("unknown types must keep falling through", store.contains("Signal.ofWireType(type)"))
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
    fun theReplyAndAnswerPayloadsReachTheState() {
        val store = store()
        assertTrue(store.contains("permissionReplies = state.permissionReplies"))
        assertTrue(store.contains("questionResolutions = state.questionResolutions"))
        assertTrue(store.contains("Event.PermissionReplied(it, p.optString(\"reply\", \"\"))"))
        assertTrue(store.contains("Event.QuestionResolved(it, answers = questionAnswers(p))"))
        assertTrue(store.contains("Event.QuestionResolved(it, rejected = true)"))
        // The in-app answers themselves must record their real reply/answers,
        // not an empty optimistic removal — the record is the display data the
        // timeline reads after the dock is gone.
        val vm = viewModel()
        assertTrue(vm.contains("ChatStore.Event.PermissionReplied(requestID, reply)"))
        assertTrue(vm.contains("ChatStore.Event.QuestionResolved(requestID, answers)"))
        assertTrue(vm.contains("ChatStore.Event.QuestionResolved(requestID, rejected = true)"))
    }

    // ----------------------------------------------------- E5: notifications

    @Test
    fun theViewModelExposesTheNotificationFeedAndItsThreeGates() {
        val vm = viewModel()
        assertTrue(vm.contains("val notifications: StateFlow<List<AppNotification>>"))
        assertTrue(vm.contains("AppNotifier(enabled = ::notificationEnabled)"))
        assertTrue(vm.contains("fun dismissNotification(id: String)"))
        assertTrue("the settings are real consumers now", vm.contains("settings.notifyAgent"))
        assertTrue(vm.contains("settings.notifyPermissions"))
        assertTrue(vm.contains("settings.notifyErrors"))
        // All four triggers are wired.
        assertTrue(vm.contains("is ChatStore.Event.PermissionAsked -> raise("))
        assertTrue(vm.contains("is ChatStore.Event.QuestionAsked -> raise("))
        assertTrue(vm.contains("raise(AppNotificationKind.ERROR, sessionID = routed, detail = event.error)"))
        assertTrue(vm.contains("raise(AppNotificationKind.AGENT, sessionID = routed)"))
        // …and the initial state of the notifier itself is data-only.
        assertTrue(notifier().contains("val notifications: StateFlow<List<AppNotification>>"))
        assertTrue(notifier().contains("DEDUPE_WINDOW_MS = 5_000L"))
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
