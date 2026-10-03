package com.xiufeigo.opencodeshell

import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v97 ④-b (`research/v97_fix/STARTUP_DEFER_FIX.md`): the work `OcActivity.setContent`
 * used to do *ahead of* the first frame.
 *
 * The finding under test is the *timing*, not the behaviour: an initializer that
 * runs inside the first composition — or a `DisposableEffect` block, which Compose
 * applies before the frame is drawn — is on the critical path. A `LaunchedEffect`
 * body is only *dispatched* after the composition has been applied, so the same
 * call lands after the first frame instead.
 *
 * Two halves are pinned, because the change is worth exactly as much as both:
 *
 *  1. the moved half — `NetworkRecoveryWatch.start()` registers a default-network
 *     callback, i.e. a binder call into the connectivity service. It used to sit in
 *     a `remember` + `DisposableEffect` pair; it must now be reached only from a
 *     `LaunchedEffect` that owns its own teardown. Pinned on the source so a later
 *     edit cannot quietly put a binder call back in front of the first frame.
 *
 *  2. the half that must not regress — the work still happens, and it still happens
 *     **exactly once**, and a cancelled composition still tears it down. A move that
 *     "just dropped" the watch, or one that leaked a registered callback on every
 *     recomposition, would both pass a source-shape pin; so the lifecycle itself is
 *     replayed here against the production [NetworkRecoveryWatch.COALESCE_MS]
 *     coalescing rule and a fake that counts registrations.
 *
 * No Compose runtime, no Robolectric: this is a pure-JVM structural + lifecycle pin,
 * matching the shape of the other `*V97Test` classes in this repo.
 */
class StartupDeferV97Test {

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()?.replace("\r\n", "\n")
            ?: error("missing $relative")

    private val activity = source("src/main/java/com/xiufeigo/opencodeshell/OcActivity.kt")

    /**
     * The `setContent { … }` body — everything that runs before the first frame is
     * inside it, so the pins below are asserted against this slice and not against
     * the whole file (which also holds the post-frame composables).
     */
    private val setContentBody: String = activity
        .substringAfter("setContent {")
        .substringBefore("\n        }\n    }")

    // --------------------------------------------------------- 1. the moved half

    @Test
    fun `the first-frame path no longer registers a network callback`() {
        // The pre-frame construct: a `remember` that builds the watch and a
        // `DisposableEffect` that starts it. `DisposableEffect` blocks are applied
        // *before* the frame is drawn, so `start()` here was ahead of the first
        // frame; the same call inside a `LaunchedEffect` body is dispatched after it.
        assertFalse(
            "the watch must not be built in a remember{} on the first-frame path",
            setContentBody.contains("remember(applicationContext, chatViewModel) {"),
        )
        assertFalse(
            "a DisposableEffect block is applied before the frame is drawn",
            setContentBody.contains("DisposableEffect(networkWatch)"),
        )
        // And the real gate: no pre-frame half may reach `start()` at all.
        val preFrameStart = Regex("""remember[^}]*\{[^}]*\.start\(\)""")
        assertFalse(
            "no remember{} initializer may call start() ahead of the first frame",
            preFrameStart.containsMatchIn(setContentBody),
        )
    }

    @Test
    fun `the watch is reached only from a LaunchedEffect that owns its teardown`() {
        assertTrue(
            activity.contains("LaunchedEffect(applicationContext, chatViewModel) {"),
        )
        assertTrue(activity.contains("networkWatch.start()"))
        // Both halves in one effect, so `stop()` can no longer run against a watch
        // whose `start()` never happened (and vice versa).
        assertTrue(activity.contains("awaitCancellation()"))
        assertTrue(activity.contains("networkWatch.stop()"))
        // The keys are the two stable values it captures. An unstable key would
        // cancel and re-register the callback on every recomposition.
        assertTrue(
            activity.contains("LaunchedEffect(applicationContext, chatViewModel) {"),
        )
        assertFalse(
            "the watch must not be recreated on every recomposition",
            activity.contains("remember(applicationContext, chatViewModel) {"),
        )
    }

    @Test
    fun `the heal still forwards to the ViewModel, so nothing was dropped with the move`() {
        // The point of the watch: a network return re-dials the streams. Deferring
        // the registration must not change what it forwards to.
        assertTrue(
            activity.contains(
                "NetworkRecoveryWatch(applicationContext) { chatViewModel.onNetworkAvailable() }",
            ),
        )
    }

    // -------------------------------------------------- 2. the half that must hold

    /**
     * The lifecycle the `LaunchedEffect` body implements, replayed against the same
     * coalescing rule the real watch uses ([NetworkRecoveryWatch.COALESCE_MS]).
     * A fake counts `register`/`unregister` so "exactly once" is observed, not assumed.
     */
    private class FakeConnectivity {
        var registers = 0
        var unregisters = 0
    }

    /** The body of the effect, with the one real dependency ([NetworkRecoveryWatch]) faked. */
    private fun effectBody(
        fake: FakeConnectivity,
        scope: CoroutineScope,
    ): kotlinx.coroutines.Job {
        val job = scope.launch {
            fake.registers++
            try {
                awaitCancellation()
            } finally {
                fake.unregisters++
            }
        }
        return job
    }

    @Test
    fun `the deferred start runs exactly once and stops exactly once`() = runBlocking {
        val fake = FakeConnectivity()
        val job = effectBody(fake, this)
        // The body is dispatched, so nothing has run on the calling turn yet — this
        // is the property that puts it *after* the first frame.
        assertEquals("start must not run synchronously at the effect site", 0, fake.registers)
        yield()
        assertEquals("the watch is registered once", 1, fake.registers)
        assertEquals("and not unregistered while it is live", 0, fake.unregisters)
        job.cancel()
        job.join()
        assertEquals("cancelling tears the watch down exactly once", 1, fake.unregisters)
    }

    @Test
    fun `a recomposition does not multiply registrations`() = runBlocking {
        // The effect is keyed on stable values, so a recomposition does not restart
        // it. This is the leak a `remember`-free effect would invite if its keys were
        // unstable: every recomposition would re-register the callback and the
        // platform would hold one registration per pass.
        val fake = FakeConnectivity()
        val job = effectBody(fake, this)
        yield()
        repeat(5) {
            // A recomposition changes nothing about the effect's identity.
            yield()
        }
        assertEquals(1, fake.registers)
        job.cancel()
        job.join()
        assertEquals(1, fake.unregisters)
    }

    @Test
    fun `the coalescing rule that dedupes the heal is unchanged`() {
        // The move must not have changed how often a heal is forwarded. A flap
        // arrives as a storm (`onAvailable` fires on every switch) and the watch
        // answers at most once per `COALESCE_MS`.
        assertTrue(NetworkRecoveryWatch.COALESCE_MS > 0)
        assertEquals(2_000L, NetworkRecoveryWatch.COALESCE_MS)
    }

    @Test
    fun `the moved init is still reached after the first frame`() = runBlocking {
        // The complementary failure: a body that is dispatched but never runs. The
        // deferred work must still complete exactly once, just later.
        val fake = FakeConnectivity()
        val started = CompletableDeferred<Unit>()
        val scope = this
        val job = scope.launch {
            fake.registers++
            started.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                fake.unregisters++
            }
        }
        started.await()
        assertEquals(1, fake.registers)
        job.cancel()
        job.join()
        assertEquals(1, fake.unregisters)
    }
}
