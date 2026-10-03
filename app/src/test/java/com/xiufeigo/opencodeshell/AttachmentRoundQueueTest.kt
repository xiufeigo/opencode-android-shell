package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.composer.AttachmentRoundQueue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

/**
 * The attachment read round runner (`AttachmentRoundQueue`, W5 bug list #3/#4):
 * rounds queue instead of cancelling each other, a cancelled round answers
 * nothing and cannot wipe the next round's progress state, and a failed round
 * still clears the bar. The queue runs on an injected [Dispatchers.Unconfined]
 * scope, so every interleaving below is deterministic on the JVM — the reader
 * suspends on gates the test opens by hand.
 */
class AttachmentRoundQueueTest {

    private fun queue(): AttachmentRoundQueue<String> =
        AttachmentRoundQueue(CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("missing $relative")

    @Test
    fun `starting while a round runs queues it instead of cancelling the active one`() {
        val queue = queue()
        val gate = CompletableDeferred<Unit>()
        val delivered = mutableListOf<String>()
        queue.enqueue(
            total = 2,
            read = { report ->
                report(1, 2, "first")
                gate.await()
                "A"
            },
            deliver = { delivered += it },
        )
        assertTrue(queue.state.value.running)
        assertEquals(2, queue.state.value.total)
        assertEquals("0 / 2", queue.state.value.counter)

        queue.enqueue(
            total = 1,
            read = { report ->
                report(1, 1, "second")
                "B"
            },
            deliver = { delivered += it },
        )
        // The paste round waits; the in-flight SAF pick is untouched.
        assertEquals("the active round must not be cancelled", emptyList<String>(), delivered)
        assertEquals("still the first round's progress", 2, queue.state.value.total)

        gate.complete(Unit)
        assertEquals(listOf("A", "B"), delivered)
        assertFalse(queue.state.value.running)
        assertEquals(0f, queue.state.value.fraction)
    }

    @Test
    fun `cancel drops the active and the queued rounds and never answers them`() {
        val queue = queue()
        val gate = CompletableDeferred<Unit>()
        val delivered = mutableListOf<String>()
        queue.enqueue(
            total = 3,
            read = { report ->
                report(1, 3, "a")
                gate.await()
                "A"
            },
            deliver = { delivered += it },
        )
        queue.enqueue(total = 1, read = { "B" }, deliver = { delivered += it })
        assertTrue(queue.state.value.running)

        queue.cancel()
        assertFalse(queue.state.value.running)
        assertEquals(0f, queue.state.value.fraction)
        gate.complete(Unit)
        assertTrue("a cancelled pick adds nothing", delivered.isEmpty())
        // The queue stays usable after a cancel.
        queue.enqueue(total = 1, read = { "C" }, deliver = { delivered += it })
        assertEquals(listOf("C"), delivered)
    }

    @Test
    fun `a dead round's late progress write cannot resurrect the bar`() {
        // The IO thread can still be inside the progress callback when cancel
        // lands on main — without a generation guard that write revived the
        // progress bar of a round nobody is running any more.
        val queue = queue()
        lateinit var report: (Int, Int, String?) -> Unit
        queue.enqueue(
            total = 3,
            read = { callback ->
                report = callback
                callback(1, 3, "x")
                awaitCancellation()
            },
            deliver = { error("cancelled rounds never answer") },
        )
        assertTrue(queue.state.value.running)
        queue.cancel()
        assertFalse(queue.state.value.running)

        report(3, 3, "late")
        assertFalse("stale progress must be dropped", queue.state.value.running)
        assertEquals(0f, queue.state.value.fraction)
        assertNull(queue.state.value.counter)
    }

    @Test
    fun `a failed round clears the bar and the next queued round still runs`() {
        val queue = queue()
        val delivered = mutableListOf<String>()
        queue.enqueue(
            total = 1,
            read = { throw IOException("provider exploded") },
            deliver = { delivered += it },
        )
        assertFalse("the bar must not stick at running", queue.state.value.running)
        assertTrue(delivered.isEmpty())

        queue.enqueue(total = 1, read = { "ok" }, deliver = { delivered += it })
        assertEquals(listOf("ok"), delivered)
        assertFalse(queue.state.value.running)
    }

    @Test
    fun `the queue rethrows CancellationException instead of swallowing it`() {
        // Not observable through the public surface (both paths skip the
        // callback), so pin the source: a swallowed CE used to land in
        // `catch (e: Exception)` and write idle over the next round's state.
        val source = source(
            "src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/AttachmentUploads.kt",
        )
        assertTrue(source.contains("catch (e: CancellationException)"))
        assertTrue("the CE must leave the coroutine", source.contains("throw e"))
        assertTrue("rounds queue behind each other", source.contains("waiting.addLast("))
        assertTrue("stale writes compare the generation", source.contains("@Volatile"))
    }
}
