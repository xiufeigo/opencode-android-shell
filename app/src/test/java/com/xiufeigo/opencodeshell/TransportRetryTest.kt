package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.v2.TransportRetry
import com.xiufeigo.opencodeshell.v2.V2Api
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * The retry policy behind v32 BUG-1: an export read failed with
 * `Failed to connect to /127.0.0.1:4095` while the reverse mapping and the
 * backend were both up — the app's pooled socket had been dropped and nothing
 * tried again, so a transient blip surfaced as a permanent error.
 *
 * Three things must hold at once, and the third is why this is a policy object
 * and not a `try` loop: a **transient** failure retries, a **definitive** answer
 * never does (repeating an identical GET reproduces it — the cursor-rejection
 * invariant `V2MessageCursorDrainTest` pins lives here), and a write never uses
 * this runner at all (pinned in `TransportRetryWiringTest`).
 */
class TransportRetryTest {

    private fun api(code: Int, body: String = """{"_tag":"InvalidRequestError","message":"boom"}""") =
        V2Api.ApiException(code, "HTTP $code boom", rawBody = body)

    // ---------------------------------------------------------------- policy

    @Test
    fun aDroppedConnectionIsTransient() {
        assertTrue(TransportRetry.isTransient(IOException("Failed to connect to /127.0.0.1:4095")))
        assertTrue(TransportRetry.isTransient(SocketTimeoutException("connect timed out")))
        assertTrue(TransportRetry.isTransient(IOException("unexpected end of stream")))
    }

    @Test
    fun definitiveAnswersAreNotTransient() {
        assertFalse(TransportRetry.isTransient(api(400)))
        assertFalse(TransportRetry.isTransient(api(401)))
        assertFalse(TransportRetry.isTransient(api(403)))
        assertFalse(TransportRetry.isTransient(api(404)))
        // A 500 is as often a deterministic application error as a blip.
        assertFalse(TransportRetry.isTransient(api(500)))
        // Not an I/O failure at all.
        assertFalse(TransportRetry.isTransient(IllegalStateException("bug")))
        assertFalse(TransportRetry.isTransient(null))
    }

    @Test
    fun notNowStatusesAndARejectedCursor() {
        assertTrue("a proxy asking for a retry", TransportRetry.isTransient(api(503)))
        assertTrue(TransportRetry.isTransient(api(504)))
        assertTrue(TransportRetry.isTransient(api(429)))
        // The cursor is client-side state: the identical request reproduces the
        // identical 400, so the marker has to win over the status.
        val cursor = V2Api.ApiException(
            400,
            "HTTP 400 InvalidCursorError: Invalid cursor",
            rawBody = """{"_tag":"InvalidCursorError","message":"Invalid cursor"}""",
        )
        assertTrue(cursor.isInvalidCursor)
        assertFalse(TransportRetry.isTransient(cursor))
    }

    @Test
    fun theBudgetIsTwoRetriesAfterTheFirstAttempt() {
        assertEquals(3, TransportRetry.READ_ATTEMPTS)
        val blip = IOException("Failed to connect")
        assertTrue("first retry", TransportRetry.shouldRetry(0, blip))
        assertTrue("second retry", TransportRetry.shouldRetry(1, blip))
        assertFalse("budget spent", TransportRetry.shouldRetry(2, blip))
        assertFalse("a definitive answer never retries", TransportRetry.shouldRetry(0, api(404)))
    }

    @Test
    fun backoffGrowsExponentially() {
        assertEquals(400L, TransportRetry.delayMs(0))
        assertEquals(800L, TransportRetry.delayMs(1))
        assertEquals(1_600L, TransportRetry.delayMs(2))
    }

    @Test
    fun connectionVocabularyCoversTheWireText() {
        assertTrue(TransportRetry.isConnectionFailureText("Failed to connect to /127.0.0.1:4095"))
        assertTrue(TransportRetry.isConnectionFailureText("java.net.SocketException: Connection reset by peer"))
        assertTrue(TransportRetry.isConnectionFailureText("unexpected end of stream on http://127.0.0.1:4095/"))
        assertTrue(TransportRetry.isConnectionFailureText("java.net.UnknownHostException: Unable to resolve host \"x\""))
        assertFalse("a provider verdict keeps its own card", TransportRetry.isConnectionFailureText("rate limit exceeded"))
        assertFalse(TransportRetry.isConnectionFailureText("This model cannot be used from your current country"))
        assertFalse(TransportRetry.isConnectionFailureText(null))
        assertFalse(TransportRetry.isConnectionFailureText("   "))
    }

    // ---------------------------------------------------------------- runner

    /** Every attempt, and the sleeps between them, without a real clock. */
    private class Attempts {
        var count = 0
        val delays = mutableListOf<Long>()
        val failures = mutableListOf<Throwable>()
    }

    @Test
    fun aFirstTrySuccessNeverSleeps() = runBlocking {
        val seen = Attempts()
        val value = TransportRetry.runRead(
            onFailure = { seen.failures += it },
            delayFor = { seen.delays += it },
        ) {
            seen.count++
            41
        }
        assertEquals(41, value)
        assertEquals(1, seen.count)
        assertEquals(emptyList<Long>(), seen.delays)
        assertEquals(emptyList<Throwable>(), seen.failures)
    }

    @Test
    fun aTransientFailureRetriesAndCanStillSucceed() = runBlocking {
        val seen = Attempts()
        val value = TransportRetry.runRead(
            onFailure = { seen.failures += it },
            delayFor = { seen.delays += it },
        ) {
            seen.count++
            if (seen.count < 3) throw IOException("Failed to connect to /127.0.0.1:4095")
            "ok"
        }
        assertEquals("ok", value)
        assertEquals(3, seen.count)
        assertEquals(listOf(400L, 800L), seen.delays)
        // The recovery hook ran per transient failure — that is where the caller
        // evicts the dead pooled sockets so the next attempt dials afresh.
        assertEquals(2, seen.failures.size)
    }

    @Test
    fun aDefinitiveFailureIsRethrownOnTheFirstAnswer() = runBlocking {
        val seen = Attempts()
        val error = api(404)
        val thrown = runCatching {
            TransportRetry.runRead(delayFor = { seen.delays += it }) {
                seen.count++
                throw error
            }
        }.exceptionOrNull()
        assertSame(error, thrown)
        assertEquals(1, seen.count)
        assertEquals(emptyList<Long>(), seen.delays)
    }

    @Test
    fun aSpentBudgetRethrowsTheLastFailure() = runBlocking {
        val seen = Attempts()
        val thrown = runCatching {
            TransportRetry.runRead(
                onFailure = { seen.failures += it },
                delayFor = { seen.delays += it },
            ) {
                seen.count++
                throw IOException("Failed to connect to /127.0.0.1:4095 (#${seen.count})")
            }
        }.exceptionOrNull()
        assertEquals(3, seen.count)
        assertEquals(listOf(400L, 800L), seen.delays)
        assertTrue("the last attempt's failure is the one rethrown", thrown?.message?.contains("#3") == true)
        assertFalse("no attempt 4", thrown?.message?.contains("#4") == true)
        // The final failure also reports, so the caller's pool eviction is not
        // skipped just because the retry budget ran out.
        assertEquals(3, seen.failures.size)
    }

    @Test
    fun cancellationIsNeverRetried() = runBlocking {
        val seen = Attempts()
        val thrown = runCatching {
            TransportRetry.runRead(delayFor = { seen.delays += it }) {
                seen.count++
                throw CancellationException("scope closed")
            }
        }.exceptionOrNull()
        assertTrue(thrown is CancellationException)
        assertEquals(1, seen.count)
        assertEquals(emptyList<Long>(), seen.delays)
    }
}
