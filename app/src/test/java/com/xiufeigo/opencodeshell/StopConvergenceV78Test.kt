package com.xiufeigo.opencodeshell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v78 (emulator-5556, `research/v78_verify/5556/27-29`; emulator-5554,
 * `research/v78_verify/5554/37-41`): two Stop residues with one shape — the
 * composer's Stop did not converge on the Stop's own answer.
 *
 * ① **Scrolled away from the bottom, the settle took ~10 s and the delivery
 * chip read 「Steer」 in the meantime**; at the bottom the same Stop converged in
 * under a second (the reviewers read the difference as an off-bottom viewport,
 * i.e. a layout callback that only fires where the `Interrupted` divider is
 * visible). What actually differed is *which frame arrived last*: the record
 * [StoppedTurn] kept the **live set** from re-asserting RUNNING, but
 * `V2EventAdapter` maps eight frame types onto RUNNING and the frame loop
 * applied every one of them to the store whatever the record said. One
 * `session.step.streamed` / `session.step.ended` from the step the Stop had just
 * interrupted — published while the execution fiber runs its finalizers
 * (`_upstream/packages/core/src/session/run-coordinator.ts:150-172`) — put the
 * store back on RUNNING, and only the idle watchdog's `IDLE_GRACE_MS` ended it.
 * [StoppedTurn.allowsRunAssert] is the missing half of the rule, pinned here.
 *
 * ② **A tap on Stop during a pending question did nothing for 30 s** (the
 * composer stayed on Stop, `Working` stayed up, tool calls kept landing; the
 * second tap stopped it). The 30 s is the app's own REST read timeout
 * (`V2Api.defaultClient`), so the tap was sitting inside an unanswered
 * `session.interrupt`. And even when that call *did* answer, the "not active"
 * tail only re-read the snapshot — whose reconcile half waits out
 * `IDLE_GRACE_MS` before it will settle a RUNNING turn. So the ViewModel half is
 * pinned against the source function window here ([StopInterruptTest]'s
 * technique); the pure halves run for real.
 */
class StopConvergenceV78Test {

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

    // ------------------------------------------- ① the record owns RUNNING

    /** Every frame type the adapter maps onto RUNNING except the two that start a run. */
    private val livenessFrames = listOf(
        // `session.status{type:busy}` and `session.status{type:retry}`.
        "session.status",
        "session.retry.scheduled",
        // A `resume`-woken successor is a *start*; a `shutdown`/`inactivity`
        // interrupt keeps the same run alive server-side, so it is liveness.
        "session.execution.interrupted",
        "session.step.streamed",
        "session.step.ended",
    )

    @Test
    fun `a confirmed stop refuses every RUNNING frame that is not a start`() {
        val record = StoppedTurn()
        record.watch("ses_a")
        record.confirmed("ses_a")
        for (type in livenessFrames) {
            assertFalse(
                "$type re-opened a stopped turn (v78 P3: the ~10 s settle + the Steer window)",
                record.allowsRunAssert("ses_a", type),
            )
        }
    }

    @Test
    fun `the two frames that start a run are allowed and lift the record`() {
        val record = StoppedTurn()
        record.watch("ses_a")
        record.confirmed("ses_a")
        for (type in listOf("session.execution.started", "session.step.started")) {
            assertTrue(
                "$type is the server saying work begins here",
                record.allowsRunAssert("ses_a", type),
            )
        }
        // …and the frame loop clears it on the same edge (`newRun`), so the
        // next liveness frame of the *new* run is allowed too.
        record.newRun("ses_a")
        for (type in livenessFrames) {
            assertTrue("after the new run, $type is that run", record.allowsRunAssert("ses_a", type))
        }
    }

    @Test
    fun `the gate is scoped and empty of its own accord`() {
        val record = StoppedTurn()
        record.watch("ses_a")
        record.confirmed("ses_a")
        // A foreign session is never held back — the map and the wire speak for
        // it only to its own screen.
        assertTrue(record.allowsRunAssert("ses_b", "session.step.streamed"))
        // A session-less frame belongs to the tracked session (`tracksSession`).
        assertFalse(record.allowsRunAssert(null, "session.step.streamed"))
        // Nothing on record → everything is allowed (a screen opened mid-turn
        // has no Stop to defend).
        val fresh = StoppedTurn()
        fresh.watch("ses_a")
        for (type in livenessFrames + "session.step.started") {
            assertTrue(fresh.allowsRunAssert("ses_a", type))
        }
        // A *refused* Stop must not be recorded: only `{interrupted:true}` is
        // the server saying a turn died (see the abort branch below).
        val refused = StoppedTurn()
        refused.watch("ses_a")
        assertTrue(refused.allowsRunAssert("ses_a", "session.step.streamed"))
        // Switching sessions drops the previous one's Stop.
        record.watch("ses_b")
        assertTrue(record.allowsRunAssert("ses_a", "session.step.streamed"))
    }

    @Test
    fun `the frame loop drops a refused RUNNING event before the mirrors see it`() {
        val body = window(vm, "private fun onV2Frame(", "private fun acceptsV2Session(")
        val gate = body.indexOf("!stoppedTurn.allowsRunAssert(routed, eventType)")
        assertTrue("the frame-loop gate is gone (v78 bug 2)", gate >= 0)
        assertTrue(
            "the gate must sit before the notification/ask mirrors",
            gate < body.indexOf("notifyFor(event, routed)"),
        )
        assertTrue(
            "and before the store write",
            gate < body.indexOf("scope.launch { applyEvent(event) }"),
        )
        assertTrue(
            "only a RUNNING assertion is dropped",
            body.substring(0, gate).contains("event.status == ChatStore.RunStatus.RUNNING"),
        )
        assertTrue(
            "the gate reads the frame type, so a start can pass",
            body.substring(gate, gate + 80).contains("allowsRunAssert(routed, eventType)"),
        )
    }

    @Test
    fun `RUNNING only ever enters the store through RunStatusChanged`() {
        // The reason the frame gate above is a complete answer for the UI: a
        // streamed part cannot resurrect a turn on its own, so blocking the
        // status event leaves the transcript free to keep healing.
        val running = ChatStore.reduce(
            ChatStore.ChatState(),
            ChatStore.Event.RunStatusChanged(ChatStore.RunStatus.RUNNING),
        )
        assertEquals(ChatStore.RunStatus.RUNNING, running.runStatus)
        val streamed = ChatStore.reduce(
            running,
            ChatStore.Event.PartUpdated("msg_a", ChatStore.UiPart(id = "p1", type = "text", text = "hi")),
        )
        assertEquals(ChatStore.RunStatus.RUNNING, streamed.runStatus)
    }

    // ----------------------------------------- ② the Stop's answer is bounded

    @Test
    fun `the acknowledgement is bounded and still carries resume`() {
        val body = window(vm, "fun abort()", "private suspend fun reportStopUndelivered(")
        val timeout = body.indexOf("withTimeoutOrNull(STOP_ACK_TIMEOUT_MS)")
        val call = body.indexOf("transport.interrupt(id, resume = true)")
        assertTrue("the interrupt call is not BOUNDED any more (v78 bug 1: 30 s of silence)", timeout >= 0)
        assertTrue("the interrupt must sit inside the bound", timeout < call)
        assertTrue(
            "resume:true is upstream's own Stop flag (adapter.ts:38-42) and must survive",
            call >= 0,
        )
        // The bound must be inside the idle watchdog's own grace, so the Stop
        // decides before the watchdog outlives it.
        val constants = window(vm, "private const val STOP_ACK_TIMEOUT_MS", "private const val STOP_NO_ANSWER")
        assertTrue(constants.contains("5_000L"))
        assertTrue(vm.contains("private const val IDLE_GRACE_MS = 10_000L"))
    }

    @Test
    fun `every way the answer can fail is named`() {
        val body = window(vm, "fun abort()", "private suspend fun reportStopUndelivered(")
        assertTrue(
            "an interrupted=true is the only silent success",
            body.contains("true -> null"),
        )
        assertTrue(
            "a refusal names itself",
            body.contains("false -> STOP_NOT_INTERRUPTED"),
        )
        assertTrue(
            "and a wedged request is an outcome of its own, not an exception",
            body.contains("null -> STOP_NO_ANSWER"),
        )
        assertTrue(body.contains("catch (e: Exception) {"))
    }

    @Test
    fun `the live set decides between a report and an immediate settle`() {
        val body = window(vm, "fun abort()", "private suspend fun reportStopUndelivered(")
        val live = body.indexOf("transport.activeRunStatus(id)")
        val report = body.indexOf("reportStopUndelivered(id, failure)")
        assertTrue("the live set must be read", live >= 0)
        assertTrue("and it must precede the verdict", live < report)
        assertTrue(
            "a session the map still lists gets the visible error",
            body.substring(live, report)
                .contains("if (live == \"running\" || live == \"busy\" || live == \"retry\")"),
        )
        val settle = body.substring(report)
        assertTrue(
            "otherwise the composer settles HERE — the old tail only re-read the " +
                "snapshot, whose reconcile waits out IDLE_GRACE_MS (v78 P3: ~10 s)",
            settle.contains("applyEvent(ChatStore.Event.RunStatusChanged(ChatStore.RunStatus.IDLE))") &&
                settle.contains("refreshSnapshot()"),
        )
        val confirmed = body.indexOf("stoppedTurn.confirmed(id)")
        assertTrue("the confirmed path must still put the Stop on record", confirmed >= 0)
        assertTrue(
            "a refusal must not be recorded as a confirmed Stop — the wire keeps the right " +
                "to re-open a turn only the map said was gone",
            confirmed < report,
        )
        assertTrue(
            "the already-idle client still says nothing (it had no turn of its own)",
            body.contains("if (!believedRunning) {"),
        )
    }

    // ------------------------------------------------ ③ the reason reads三语

    @Test
    fun `the no-answer reason is in the table and in all three locales`() {
        val table = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/timeline/ToolRenderers.kt")
        val entry = window(
            table,
            "ServerErrorPhrase(\n        \"the server did not answer\",",
            ")\n",
        )
        assertTrue(
            "the reason slot must be translatable, not an English fragment",
            entry.contains("R.string.error_server_stopUndeliveredNoAnswer"),
        )
        val values = listOf("values", "values-zh-rCN", "values-zh-rTW").map { dir ->
            val body = source("src/main/res/$dir/strings_oc_i18n.xml")
            Regex("""<string name="error_server_stopUndeliveredNoAnswer">(.*?)</string>""")
                .find(body)?.groupValues?.get(1)
                ?: error("$dir is missing the no-answer reason")
        }
        assertEquals("the English entry is the phrase itself", "the server did not answer", values[0])
        for (index in 1..2) {
            assertFalse(
                "${listOf("", "zh-rCN", "zh-rTW")[index]} ships the English copy",
                values[index] == values[0],
            )
        }
        // The sentence it lands in: `reportStopUndelivered`'s line, whose two
        // static halves are already in the same table.
        assertTrue(
            "the Stop sentence's own halves are still wired",
            table.contains("\"Stop did not reach the server (\"") &&
                table.contains("\") — the turn may still be running.\""),
        )
    }
}
