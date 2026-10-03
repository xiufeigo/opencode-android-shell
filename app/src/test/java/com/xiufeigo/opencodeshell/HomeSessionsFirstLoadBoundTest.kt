package com.xiufeigo.opencodeshell

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ITEM A (P2): the session list's first load sat on its skeleton forever —
 * no error, no retry — when the server was slow/wedged.
 *
 * Root cause: `listSessions` wrapped its fetch passes in a bare
 * `withTimeoutOrNull(SESSION_LIST_TIMEOUT_MS)`, but the passes are blocking
 * OkHttp `execute()` calls (30 s read timeout each, up to three attempts per
 * read through `TransportRetry`, plus a second pass and the two
 * location-ask reads), which never reach a suspension point — so the 20 s
 * bound could not fire, the callback never landed, and Home's `loading`
 * stayed true. A later server-config edit re-ran the read after the stuck
 * calls had timed out, which is why the error state only appeared then.
 *
 * The passes now race in a child `async` whose `await` *is* cancellable, so
 * the bound reports at 20 s and the failure state below (`sessionsLoadFailed`
 * + the column's error/retry) covers the very first load too. A healthy read
 * answers in ~1 s on the same path.
 */
class HomeSessionsFirstLoadBoundTest {

    private fun vmSource(): String =
        listOf(
            File("src/main/java/com/xiufeigo/opencodeshell/ChatViewModel.kt"),
            File("app/src/main/java/com/xiufeigo/opencodeshell/ChatViewModel.kt"),
        ).firstOrNull { it.isFile }?.readText()?.replace("\r\n", "\n")
            ?: error("missing ChatViewModel.kt")

    private fun listSessionsBody(): String =
        vmSource()
            .substringAfter("fun listSessions(onResult")
            .substringBefore("private fun scheduleSessionListRetry()")

    @Test
    fun theBoundRacesTheBlockingPassesInsteadOfWrappingThem() {
        val body = listSessionsBody()
        assertTrue(
            "the passes must run in a child async so the wait is cancellable",
            body.contains("async(Dispatchers.IO)"),
        )
        assertTrue("the bound waits on the race", body.contains("fetch.await()"))
        assertTrue(
            "the 20 s bound still owns the read",
            body.contains("withTimeoutOrNull(SESSION_LIST_TIMEOUT_MS)"),
        )
    }

    @Test
    fun theZombiePassCanNeverSurfaceAfterTheBoundDropsIt() {
        val body = listSessionsBody()
        assertTrue(
            "a late answer must be discarded, never thrown unobserved",
            body.contains("runCatching"),
        )
        assertTrue(body.contains(".getOrNull()"))
    }

    @Test
    fun theBoundKeepsTheExistingReadContract() {
        val body = listSessionsBody()
        assertTrue("a cold start must not race the probe", body.contains("awaitProbeSettled()"))
        assertTrue(
            "transient startup failure still retries once",
            body.contains("delay(STARTUP_RETRY_DELAY_MS)"),
        )
        assertTrue(body.contains("_sessionsLoadFailed.value = sessions == null"))
    }
}
