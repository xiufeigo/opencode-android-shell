package com.xiufeigo.opencodeshell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Cross-session race guards + the v2 transport install for the
 * `ChatViewModel` / `SessionScreen` / `App` concurrency batch.
 *
 * The ViewModel itself needs an Android `Context` and the Main dispatcher, so
 * these contracts are pinned against the source function-window by
 * function-window — the same technique [ModelPickerTest] uses for ViewModel
 * wiring. Each test names the race it forbids.
 */
class SessionRaceGuardTest {

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            // Sources are CRLF; normalize so multi-line `contains` patterns
            // written with `\n` match on every platform.
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

    // ------------------------------------------------- 1. searchFiles on Main

    @Test
    fun searchFilesBothOverloadsAnswerOnTheMainThread() {
        val first = window(vm, "fun searchFiles(query: String, onResult", "Directory-scoped variant")
        val second = window(vm, "fun searchFiles(query: String, directory", "Fuzzy directory search")
        for (body in listOf(first, second)) {
            assertTrue(
                "callback must hop to Main (Views / Compose state on the caller's thread)",
                body.contains("withContext(Dispatchers.Main) { onResult(results) }"),
            )
            assertFalse("raw IO-thread callback", body.contains("\n            onResult(results)"))
        }
    }

    // --------------------------------------- 2. v2 transport install

    @Test
    fun ensureTransportAlwaysProbesThenInstallsTheV2Transport() {
        val body = window(vm, "private fun ensureTransport(", "/** Install the transport")
        // V2-only: every connect probes and installs from the verdict — the old
        // generation cache is gone, nothing to hit or to clear any more.
        assertTrue(body.contains("V2Capability.probeUrl("))
        assertTrue(body.contains("setGeneration(generation, server)"))
        assertFalse(body.contains("setGeneration(cached, server)"))
        val install = window(vm, "private fun setGeneration(", "private fun transport():")
        assertTrue("V2 verdict builds a V2Transport", install.contains("V2Transport(V2Api("))
        assertTrue("UNKNOWN installs nothing", install.contains("ServerGeneration.UNKNOWN -> null"))
        val store = source("src/main/java/com/xiufeigo/opencodeshell/ServerConfigStore.kt")
        assertFalse(store.contains("rememberGeneration"))
    }

    // ---------------------------------- 3. snapshot vs. session-switch race

    @Test
    fun snapshotIsDroppedWhenTheSessionSwitchedMidFetch() {
        val body = window(vm, "private fun refreshSnapshot() {", "private fun refreshPermissions() {")
        val guard = body.indexOf("if (sessionID != id) return@launch")
        assertTrue("snapshot needs a session gate", guard >= 0)
        assertTrue(
            "gate must sit before the Snapshot apply",
            guard < body.indexOf("applyEvent(ChatStore.Event.Snapshot(messages))"),
        )
        // The late `activeRunStatus` read is gated too (it flips run status).
        assertTrue(body count "if (sessionID != id) return@launch" >= 2)
    }

    // -------------------------------------------- 4. undo/redo boundary writes

    @Test
    fun revertBoundaryWritesAreSessionScoped() {
        val undo = window(vm, "fun undoSession(", "fun redoSession(")
        assertTrue(
            undo.contains(
                "if (boundary != null && sessionID == id) _revertMessageID.value = boundary",
            ),
        )
        val redo = window(vm, "fun redoSession(", "private suspend fun interruptIfRunning(")
        // Unrevert's clear must no longer run inside the IO block (its session
        // check would read a possibly stale `sessionID` off the main thread).
        assertFalse(
            redo.contains("transport.clearRevert(id)\n                            _revertMessageID.value = null"),
        )
        assertTrue(redo.contains("step is RevertStep.Unrevert && sessionID == id"))
        assertTrue(redo.contains("if (sessionID == id) _revertMessageID.value = step.messageID"))
        assertTrue(redo.contains("RevertStep.RevertTo && sessionID == id"))
    }

    // ------------------------------------------- 5. share URL cross-session

    @Test
    fun shareStateWritesAreSessionScoped() {
        // share/unshare/archive are gone; nothing to scope any more.
    }

    // ------------------------------------------- 6. queue cross-session writes

    @Test
    fun queueMutationsAreSessionScoped() {
        val inbox = window(vm, "private fun refreshInbox() {", "private fun drainQueueOnIdle() {")
        assertTrue(
            inbox.contains(
                "{ transport.fetchInbox(id) }\n                if (sessionID != id) return@launch",
            ),
        )
        val submit = window(vm, "fun submit(", "private fun filesOf(")
        assertTrue(submit.contains("sessionID == id && wireDelivery == \"queue\""))
        // P1-C: the rollback hands the full handoff back (failed attachments
        // ride along), still keyed by `id` and still gated for the banner —
        // in that order, comments in between allowed.
        val rollbackAt = submit.indexOf("restoreDraft(id, text, shell, attachments)")
        assertTrue(rollbackAt >= 0)
        assertTrue(submit.substring(rollbackAt).contains("if (sessionID == id) {"))
        assertFalse(submit.contains("restoreDraft(id, text, shell, attachments)\n                _state.value"))
        for (body in listOf(
            window(vm, "fun steerQueued(", "fun cancelQueued("),
            window(vm, "fun cancelQueued(", "fun confirmQueueEdit("),
        )) {
            assertTrue("rollback must not re-add into the new session", body.contains("if (sessionID == id) queueHolder.add(row)"))
        }
        val edit = window(vm, "fun confirmQueueEdit(", "private fun sendStructured(")
        assertTrue(edit.contains("if (sessionID == id) {\n                    queueHolder.remove(originalID)"))
    }

    // ------------------------------------------- 7. markSessionMissing gating

    @Test
    fun markSessionMissingOnlyForTheSessionItWasObservedOn() {
        assertTrue(
            "every latch is scoped to the session it was observed on",
            vm.contains("private fun markSessionMissing(forID: String?"),
        )
        assertTrue(vm.contains("if (sessionID != forID) return"))
        assertFalse("no unscoped latch may remain", vm.contains("markSessionMissing()"))
        // log stream + snapshot + permissions + forms … plus (v81) the
        // entry-level drop: `forgetSession` latches the tracked session so its
        // route explains that the server no longer has it, and the not-found
        // page's 「删除该条目」 can take the entry off every surface.
        //
        // v82 BUG-A: the four read failures carry the *reason* they latched with
        // (`markSessionMissing(id, failure)` — 404 vs 5xx), so the count is split
        // by shape; every single call still names its session, which is the
        // property this test is about.
        val scoped = vm count "markSessionMissing(id)"
        val reasoned = vm count "markSessionMissing(id, failure)"
        assertEquals(5, scoped + reasoned)
        assertEquals(
            "only `forgetSession` (a confirmed-gone entry, v81 P2) latches without a read verdict",
            1,
            scoped,
        )
    }

    // ------------------------------------------- 8. paste replaces vs. merges

    @Test
    fun pasteAttachesThroughTheSharedMergePolicy() {
        val screen = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionScreen.kt")
        assertTrue(screen.contains("AttachmentPolicy.merge(attachments, pasted)"))
        // P1-C: every staged-list change goes through `stageAttachments`, which
        // assigns AND mirrors the list into the draft handoff — still a merge,
        // never a raw replace.
        assertTrue(screen.contains("stageAttachments(merged.attachments)"))
        assertFalse("raw replace drops staged chips and bypasses the cap", screen.contains("attachments = pasted"))
        assertTrue("cap toast mirrors the SAF picker", screen.contains("chat_attach_limit"))
    }

    // ------------------------------------- 9. palette exact-ID via the seam

    @Test
    fun paletteExactIdLookupRidesTheTransportSeamNotBareV1() {
        val app = source("src/main/java/com/xiufeigo/opencodeshell/ui/App.kt")
        assertFalse("bare v1 client answers SPA HTML on v2", app.contains("paletteClient"))
        assertTrue(app.contains("chatViewModel?.fetchExportFrame(id, null)"))
        assertTrue(app.contains("OpenCodeClient.Session.fromJson(frame.info)"))
    }
}
