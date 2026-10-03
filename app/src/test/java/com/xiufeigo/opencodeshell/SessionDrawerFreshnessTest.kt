package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.drawer.SessionTimeLabel
import com.xiufeigo.opencodeshell.v2.V2EventAdapter
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A-3, the freshness half of the drawer report: 「会话 A 是当前活跃 tab 且刚有更新
 * （服务端 updated 最新）时，抽屉把另一个旧会话排在最上，还标着 just now」.
 *
 * Two independent stalenesses produced that screenshot, and this file pins both:
 *
 * 1. **the order** — the drawer's list is a `GET /api/session` snapshot, re-read
 *    on entry / drawer-open / `sessionsRevision`. The server writes a session
 *    row's `time.updated` for `session.model.selected` / `session.agent.selected`
 *    / `session.permissions` / `session.revert.*` as well
 *    (`core/src/session/projector.ts:551,562,579,716,726,761`), and none of those
 *    frames reached the list, so a model/agent switch in the active session left
 *    the drawer's order on its pre-action snapshot until the screen was
 *    re-entered. They now map to [ChatStore.Signal.SESSION_UPDATED] →
 *    `ChatViewModel.sessionsRevision` → the drawer re-reads.
 * 2. **the label** — a row's only input is `updatedAt`, which does not move as
 *    time passes, so Compose skipped the row and the label composed while the
 *    session was "just now" stayed "just now" for as long as the row lived. The
 *    host now passes a clock that ticks while the drawer is open.
 *
 * The first half is behavioural (the adapter is pure JSON → events, so a frame
 * can be adapted here without a server); the wiring that cannot be exercised on
 * the JVM — the revision bump and the ticking clock — is guarded at source level
 * in the same style as [SessionUnreadWiringTest].
 */
class SessionDrawerFreshnessTest {

    // ------------------------------------------------------- the row write

    private fun adapt(type: String, json: String): List<ChatStore.Event> =
        V2EventAdapter.adapt(
            type,
            JSONObject("""{"id":"evt_1","type":"$type","created":1,"data":$json}"""),
        )

    private fun sessionUpdated(type: String, json: String): ChatStore.Event.ServerSignal =
        adapt(type, json)
            .filterIsInstance<ChatStore.Event.ServerSignal>()
            .single { it.signal == ChatStore.Signal.SESSION_UPDATED }

    /** Every v2 frame that writes the session row, i.e. that moves `time.updated`. */
    private val rowWrites = listOf(
        "session.agent.selected" to """{"sessionID":"ses_1","agent":"build"}""",
        "session.model.selected" to
            """{"sessionID":"ses_1","model":{"id":"gpt-5.6","providerID":"openai"}}""",
        // `permissions` is a `Permission.Ruleset` **array** (`schema/src/permission.ts:65`).
        "session.permissions" to """{"sessionID":"ses_1","permissions":[]}""",
        "session.revert.staged" to """{"sessionID":"ses_1","revert":{"messageID":"msg_1"}}""",
        "session.revert.cleared" to """{"sessionID":"ses_1"}""",
        "session.revert.committed" to """{"sessionID":"ses_1","to":"msg_1"}""",
    )

    @Test
    fun `every session row write stales the session list`() {
        for ((type, payload) in rowWrites) {
            // The frame's own event is pinned by `V2EventAdapterCoverageTest`
            // (whose `own()` helper drops this signal); here the signal half:
            val signal = sessionUpdated(type, payload)

            assertEquals("$type must carry the row's session", "ses_1", signal.sessionID)
        }
    }

    @Test
    fun `the v1 row event maps by wire type`() {
        // v1's own `session.updated` (`schema/src/v1/session.ts:581`): the v2
        // wire has no such frame, so the enum's `wireType` is what a v1 stream
        // resolves through (`Signal.ofWireType`, the adapter's `else` branch).
        assertEquals(ChatStore.Signal.SESSION_UPDATED, ChatStore.Signal.ofWireType("session.updated"))
        assertEquals(
            ChatStore.Signal.SESSION_UPDATED,
            (adapt("session.updated", """{"sessionID":"ses_1"}""").single() as ChatStore.Event.ServerSignal).signal,
        )
    }

    // ------------------------------------------------------------- the clock

    @Test
    fun `a relative label follows the clock`() {
        // The row that read "just now" while it was composed has to read its real
        // age once the clock moves on — that is all the ticking `now` does.
        val updatedAt = 1_700_000_000_000L

        assertEquals(
            SessionTimeLabel.Unit.JUST_NOW,
            SessionTimeLabel.relative(updatedAt, updatedAt + 30_000L)!!.unit,
        )
        assertEquals(
            SessionTimeLabel.Relative(SessionTimeLabel.Unit.MINUTES, 5),
            SessionTimeLabel.relative(updatedAt, updatedAt + 5L * 60_000L + 1_000L),
        )
    }

    // ------------------------------------------------------------ the wiring

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("missing $relative")

    private fun sessionScreen() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionScreen.kt")

    private fun drawer() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/drawer/SessionDrawer.kt")

    @Test
    fun `the shell re-reads the list when a session row moves`() {
        val vm = source("src/main/java/com/xiufeigo/opencodeshell/ChatViewModel.kt")

        val signalAt = vm.indexOf("ChatStore.Signal.SESSION_UPDATED,")
        assertTrue("the row write must be handled by the signal switch", signalAt >= 0)
        // …and handled by bumping the list revision, not by some later branch:
        // the bump sits in the same `when` arm, a few characters on.
        val bumpAt = vm.indexOf("_sessionsRevision.value += 1", signalAt)
        assertTrue("the row write must bump the session-list revision", bumpAt in (signalAt + 1)..(signalAt + 200))
    }

    @Test
    fun `the drawer measures its labels against a clock that ticks while it is open`() {
        val screen = sessionScreen()

        assertTrue(
            "the drawer's labels take the screen's clock",
            screen.contains("now = drawerNow"),
        )
        assertTrue(
            "the clock ticks only while the drawer is on screen",
            screen.contains("val drawerVisible = drawerState.isOpen || drawerOpen"),
        )
        assertTrue(
            "and it re-measures on a loop, not once per composition",
            screen.contains("while (drawerVisible) {"),
        )
        assertTrue(screen.contains("drawerNow = System.currentTimeMillis()"))
        assertTrue(screen.contains("delay(DRAWER_CLOCK_TICK_MS)"))
        // A clock read inside the row would be invisible to Compose again.
        val rows = drawer()
        assertTrue(rows.contains("RelativeTimeText(updatedAt = session.updatedAt, now = now)"))
        assertTrue(!rows.contains("SessionTimeLabel.relative(updatedAt, System.currentTimeMillis())"))
    }

    @Test
    fun `the drawer orders its own rows`() {
        val rows = drawer()

        assertTrue(
            "the view cannot depend on its caller's ordering",
            rows.contains("val rows = remember(sessions) { SessionDrawerOrder.rows(sessions) }"),
        )
        assertTrue("and it renders that order", rows.contains("items(items = rows, key = { it.id })"))
    }
}
