package com.xiufeigo.opencodeshell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Stop (P0, emulator-5556 tStop): pressing Stop left the composer idle while the
 * backend kept running the turn, so the next prompt was steered into it.
 *
 * `ChatViewModel.abort()` used to be fire-and-forget — it POSTed
 * `session.interrupt` and discarded both the `{interrupted:bool}` the route
 * answers and any exception, so all three ways a Stop can fail to reach the
 * backend (no transport, an unreachable server, a server that owns no execution
 * to pre-empt) rendered exactly like a Stop that worked. The ViewModel needs an
 * Android `Context` and the Main dispatcher, so that contract is pinned against
 * the source function-window (the technique [UndoRevertCommitTest] uses).
 *
 * The transcript half — an aborted turn that produced nothing must leave no
 * residue row — is pure store logic and runs for real.
 */
class StopInterruptTest {

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

    // ------------------------------------------------------- abort() contract

    @Test
    fun `stop posts the interrupt with the upstream resume flag`() {
        val body = window(vm, "fun abort()", "private suspend fun reportStopUndelivered(")
        assertTrue(
            "Stop must send resume:true like upstream's session adapter " +
                "(_upstream/packages/app/src/session/composer/adapter.ts:38-42)",
            body.contains("transport.interrupt(id, resume = true)"),
        )
    }

    @Test
    fun `stop no longer swallows the server's answer`() {
        val body = window(vm, "fun abort()", "private suspend fun reportStopUndelivered(")
        assertTrue(
            "the `{interrupted:bool}` answer must not be thrown away",
            body.contains("val interrupted = withTimeoutOrNull(STOP_ACK_TIMEOUT_MS) {") &&
                body.contains("withContext(Dispatchers.IO) { transport.interrupt(id, resume = true) }"),
        )
        // v78: the binding is a three-way `when` now (a wedged request is its
        // own outcome — see StopConvergenceV78Test), but the refusal must still
        // be named rather than folded into "success".
        assertTrue(
            "the `{interrupted:false}` answer is still named",
            body.contains("false -> STOP_NOT_INTERRUPTED"),
        )
    }

    @Test
    fun `stop reports an undelivered abort instead of going quietly idle`() {
        val body = window(vm, "fun abort()", "private suspend fun reportStopUndelivered(")
        assertTrue(
            "a thrown interrupt must become a reported failure, not a log line",
            body.contains("val failure: String? = try {") &&
                body.indexOf("val failure: String? = try {") < body.indexOf("catch (e: Exception) {"),
        )
        assertTrue(
            "a refused Stop must raise the error, not leave a locked-looking idle composer",
            body.contains("reportStopUndelivered(id, failure)"),
        )
        assertFalse(
            "the old body swallowed every outcome and returned",
            body.contains("transport.interrupt(id)"),
        )
    }

    @Test
    fun `stop asks the live set before claiming the turn is over`() {
        val body = window(vm, "fun abort()", "private suspend fun reportStopUndelivered(")
        val probe = body.indexOf("transport.activeRunStatus(id)")
        val report = body.indexOf("reportStopUndelivered(id, failure)")
        assertTrue("the live set must be read", probe >= 0)
        assertTrue("and it must decide whether the error is raised", probe < report)
    }

    @Test
    fun `a landed stop re-reads the transcript so the Interrupted separator lands`() {
        val body = window(vm, "fun abort()", "private suspend fun reportStopUndelivered(")
        assertTrue(
            "the Interrupted divider is a server fact, read back unthrottled",
            body.contains("refreshSnapshot()"),
        )
    }

    // ------------------------------------------------ the transport contract

    @Test
    fun `the transport carries the server's answer`() {
        val transport = source("src/main/java/com/xiufeigo/opencodeshell/v2/SessionTransport.kt")
        assertTrue(
            "interrupt must return whether the server actually pre-empted an execution",
            transport.contains("fun interrupt(sessionID: String, resume: Boolean? = null): Boolean"),
        )
        val impl = source("src/main/java/com/xiufeigo/opencodeshell/v2/V2Transport.kt")
        assertTrue(impl.contains("override fun interrupt(sessionID: String, resume: Boolean?): Boolean"))
    }

    // ------------------------------------------- the Stop residue in the store

    private fun user(id: String) =
        ChatStore.UiMessage(id = id, role = "user", timeCreated = 0, parts = listOf(uiPart("text", "hi")))

    private fun assistant(id: String, interrupted: Boolean, vararg parts: ChatStore.UiPart) =
        ChatStore.UiMessage(
            id = id,
            role = "assistant",
            timeCreated = 1,
            parts = parts.toList(),
            interrupted = interrupted,
        )

    private fun uiPart(type: String, text: String = "", tool: String? = null) =
        ChatStore.UiPart(id = "p_$type${text}_${tool.orEmpty()}", type = type, text = text, toolName = tool)

    private fun reduce(vararg events: ChatStore.Event) =
        events.fold(ChatStore.ChatState()) { state, event -> ChatStore.reduce(state, event) }

    @Test
    fun `an aborted turn with nothing in it leaves no row behind`() {
        val state = reduce(
            ChatStore.Event.Snapshot(
                listOf(
                    user("msg_u"),
                    assistant("msg_a", true, uiPart("step-start"), uiPart("step-finish")),
                ),
            ),
        )
        assertEquals(
            "the empty bubble + its divider are the Stop residue (emulator-5556)",
            listOf("msg_u"),
            state.messages.map { it.id },
        )
    }

    @Test
    fun `an aborted turn that produced something keeps its row and its divider`() {
        val state = reduce(
            ChatStore.Event.Snapshot(
                listOf(assistant("msg_a", true, uiPart("step-start"), uiPart("text", "partial"))),
            ),
        )
        assertEquals(listOf("msg_a"), state.messages.map { it.id })
        assertTrue(state.messages.single().interrupted)
    }

    @Test
    fun `an aborted turn that only ran a tool keeps its tool card`() {
        val state = reduce(
            ChatStore.Event.Snapshot(
                listOf(assistant("msg_a", true, uiPart("step-start"), uiPart("tool", tool = "webfetch"))),
            ),
        )
        assertEquals(listOf("msg_a"), state.messages.map { it.id })
    }

    @Test
    fun `a running empty turn is the Working turn and survives`() {
        val state = reduce(
            ChatStore.Event.Snapshot(listOf(assistant("msg_a", false, uiPart("step-start")))),
        )
        assertEquals(listOf("msg_a"), state.messages.map { it.id })
    }

    @Test
    fun `an aborted turn with no parts at all is left alone`() {
        // The server wrote no bookkeeping around the abort — there is nothing on
        // screen to clear, and the row still owns the divider.
        val state = reduce(
            ChatStore.Event.Snapshot(listOf(assistant("msg_a", true))),
        )
        assertEquals(listOf("msg_a"), state.messages.map { it.id })
    }

    @Test
    fun `a later frame cannot resurrect the residue`() {
        val state = reduce(
            ChatStore.Event.Snapshot(listOf(assistant("msg_a", true, uiPart("step-start"), uiPart("text", "t")))),
            // The abort lands: the row loses the last renderable part.
            ChatStore.Event.MessageUpdated(assistant("msg_a", true, uiPart("step-start"))),
        )
        assertEquals(emptyList<String>(), state.messages.map { it.id })
    }
}
