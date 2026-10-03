package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.v2.V2Api
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v35 tB BUG-1: the ⋮ menu's 撤销 staged the revert boundary and never committed
 * it, so the tail disappeared from the timeline by *projection* while
 * `GET /api/session/:id/message` still answered every reverted row — the next
 * read (reopen / reconnect) brought it back (`research/v35_verify/tB_lifecycle/
 * exported.json`: `revert.messageID` set at the first user message, all six
 * messages still listed).
 *
 * The revert is a three-step write. `POST …/revert/stage` publishes
 * `session.revert.staged`, which the projector only records
 * (`core/src/session/revert.ts:23-59` → `projector.ts:709-722`); the truncation
 * is `POST …/revert/commit` → `session.revert.committed`
 * (`core/src/session/revert.ts:78-84` → `projector.ts:731-767` deletes every
 * `session_message` from the boundary on). `OpenCodeClient.sessionRevert` says
 * the same for the single-call v1 shape: "staging alone changes nothing
 * visible, so the commit is part of this call".
 *
 * The ViewModel needs an Android `Context` and the Main dispatcher, so the
 * contract is pinned against the source function-window — the technique
 * [SessionRaceGuardTest] uses for the same functions.
 */
class UndoRevertCommitTest {

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

    private infix fun String.count(needle: String): Int = split(needle).size - 1

    private val vm by lazy {
        source("src/main/java/com/xiufeigo/opencodeshell/ChatViewModel.kt")
    }

    // ------------------------------------------- 1. the three steps, in order

    @Test
    fun undoInterruptsStagesThenCommits() {
        val body = window(vm, "fun undoSession(", "fun revertToMessage(")
        val interrupt = body.indexOf("interruptIfRunning(id)")
        val stage = body.indexOf("transport.stageRevert(id, target)")
        val commit = body.indexOf("transport.commitRevert(id)")
        assertTrue("撤销 must interrupt a running turn first (`revert.ts:54-56`)", interrupt >= 0)
        assertTrue("the boundary is staged after the interrupt", interrupt < stage)
        assertTrue("staging alone is not a revert — the commit must follow it", stage < commit)
        assertTrue("…and the result is reported after the write", commit < body.lastIndexOf("onResult(true)"))
    }

    @Test
    fun theCommitIsV2OnlyAndItsFailureIsReported() {
        val body = window(vm, "fun undoSession(", "fun revertToMessage(")
        assertTrue(
            "v1 applies a revert in one call; only v2 has the stage/commit pair",
            body.contains("transport.generation != ServerGeneration.V2 -> true"),
        )
        assertTrue(
            "a failed commit must not pass as a successful 撤销",
            body.contains("""Log.w(TAG, "revert commit failed: ${'$'}{e.message}")"""),
        )
        assertTrue(body.contains("onResult(false)"))
    }

    @Test
    fun theLocalBoundaryIsWrittenOnceAfterTheWriteLanded() {
        val body = window(vm, "fun undoSession(", "fun revertToMessage(")
        assertTrue(
            "the projection boundary follows the server's, and only for this session",
            body.contains("if (boundary != null && sessionID == id) _revertMessageID.value = boundary"),
        )
        assertTrue("one write only", body count "_revertMessageID.value = boundary" == 1)
    }

    // ------------------------------------- 2. the good path is left alone

    @Test
    fun theMessageLevelRevertStillStagesThenCommits() {
        val body = window(vm, "fun revertToMessage(", "fun redoSession(")
        val stage = body.indexOf("transport.stageRevert(id, step.messageID)")
        val commit = body.indexOf("transport.commitRevert(id)")
        assertTrue(stage >= 0)
        assertTrue(commit > stage)
    }

    @Test
    fun redoStillCommitsItsBoundaryMove() {
        val body = window(vm, "fun redoSession(", "private suspend fun interruptIfRunning(")
        assertTrue(body.contains("transport.commitRevert(id)"))
    }

    // ------------------------------------ 3. the commit is a real route

    @Test
    fun theCommitHalfPostsItsOwnRoute() {
        // The premise of the fix, at the transport layer: `stage` and `commit`
        // are two different routes, and it is the second one the projector
        // listens to for the truncation. `V2ApiTest` covers stage and clear;
        // commit had no request-level pin.
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            val api = V2Api(
                baseHttpUrl = server.url("/").toString().toHttpUrlOrNull()!!,
                http = V2Api.defaultClient(),
            )
            api.revertCommit("ses_1")
            val commit = server.takeRequest()
            assertEquals("POST", commit.method)
            assertEquals("/api/session/ses_1/revert/commit", commit.requestUrl!!.encodedPath)
        }
    }

    @Test
    fun theTransportExposesBothHalves() {
        val transport = source("src/main/java/com/xiufeigo/opencodeshell/v2/V2Transport.kt")
        assertTrue(transport.contains("api.revertStage(sessionID, messageID).messageID"))
        // Pin the association, not the adjacency: adding a log line inside the body
        // used to break a literal two-line match while the semantics stayed intact
        // (the same class of drift recorded in REVIEW_PIN_DRIFT_FIX.md).
        val body = functionBody(transport, "override fun commitRevert(sessionID: String)")
        assertTrue("commitRevert must exist", body != null)
        assertTrue("commitRevert body must call api.revertCommit", body!!.contains("api.revertCommit(sessionID)"))
    }

    /** Braces-matched body of the first function whose signature contains [signature]. */
    private fun functionBody(text: String, signature: String): String? {
        val start = text.indexOf(signature)
        if (start < 0) return null
        val open = text.indexOf('{', start)
        if (open < 0) return null
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(open + 1, i)
                }
            }
        }
        return null
    }
}
