package com.xiufeigo.opencodeshell

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v63 (watchdog main-thread freeze): `ChatViewModel.startIdleWatchdog`'s loop ran
 * on the view-model scope (`Dispatchers.Main.immediate`), and Android suspends a
 * backgrounded process's main looper — so the `delay` between ticks never fired
 * in exactly the situation the settle branch exists for: a backgrounded turn
 * whose terminal frame was lost, where the branch's `raise` is the only carrier
 * left that can announce the finished reply. The ViewModel needs an Android
 * `Context` and the Main dispatcher, so the threading contract is pinned against
 * the source function-window (the technique [StopInterruptTest] uses); the
 * policy itself stays covered by [TurnIdleWatchdogTest].
 */
class WatchdogDispatcherTest {

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()?.replace("\r\n", "\n")
            ?: error("missing $relative")

    private fun window(src: String, from: String, to: String): String {
        val start = src.indexOf(from)
        assertTrue("missing window start: $from", start >= 0)
        val end = src.indexOf(to, start + from.length)
        assertTrue("missing window end: $to", end > start)
        return src.substring(start, end)
    }

    private val vm: String get() = source("src/main/java/com/xiufeigo/opencodeshell/ChatViewModel.kt")

    private fun watchdog(): String =
        window(vm, "private fun startIdleWatchdog()", "private fun requireClient()")

    @Test
    fun `the watchdog loop ticks off the main dispatcher`() {
        val body = watchdog()
        val launch = body.indexOf("scope.launch(Dispatchers.Default)")
        assertTrue(
            "a frozen main looper must not be able to stop the loop whose whole " +
                "job is to act while the main looper is frozen",
            launch >= 0,
        )
        assertTrue(
            "the tick itself runs on that dispatcher: the loop's `delay` must come " +
                "after the Default launch (the scope is Dispatchers.Main.immediate)",
            body.indexOf("delay(IDLE_WATCHDOG_TICK_MS)", launch) > launch,
        )
    }

    @Test
    fun `the server probe stays on the io dispatcher`() {
        val body = watchdog()
        assertTrue(
            body.contains("withContext(Dispatchers.IO) { transport.activeRunStatus(id) }"),
        )
    }

    @Test
    fun `the store write hops back to main`() {
        val body = watchdog()
        val raise = body.indexOf("raise(AppNotificationKind.AGENT, sessionID = id, since = since)")
        // v82 BUG-C added a second, **quiet** Main hop before the probe (the
        // no-transport branch settles without announcing anything). This pin is
        // about the settle branch's own hop, so it starts the lookup at the
        // announcement instead of at the first `withContext(Dispatchers.Main)`
        // in the function.
        val hop = body.indexOf("withContext(Dispatchers.Main)", raise)
        assertTrue("the compound store update must return to Main", raise >= 0 && hop > raise)
        assertTrue(
            "and it must carry the settle event",
            body.indexOf("applyEvent(ChatStore.Event.RunStatusChanged(ChatStore.RunStatus.IDLE))", hop) > hop,
        )
    }

    @Test
    fun `the settle notification is raised before the main hop`() {
        val body = watchdog()
        val raise = body.indexOf("raise(AppNotificationKind.AGENT, sessionID = id, since = since)")
        assertTrue("the settle branch must still announce the turn", raise >= 0)
        val hop = body.indexOf("withContext(Dispatchers.Main)", raise)
        assertTrue(
            "NotificationManager is thread-agnostic; the announcement must not wait " +
                "on a looper that may be the very thing that is frozen",
            hop > raise,
        )
    }

    @Test
    fun `the last activity stamp stays volatile`() {
        // The Default-dispatched loop now writes it (REARM) while Main-side frame
        // paths write it too.
        assertTrue(vm.contains("@Volatile\n    private var lastPartEventAt"))
    }
}
