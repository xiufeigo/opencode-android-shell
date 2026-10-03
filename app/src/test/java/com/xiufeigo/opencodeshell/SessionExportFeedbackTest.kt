package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.export.ExportFeedback
import com.xiufeigo.opencodeshell.ui.session.export.ExportFinish
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * P0: "⋮ → Export session → no picker, no toast, no file" (v8-B device run).
 *
 * The chain had several branches that returned without telling anybody, and
 * the worst of them compounded: a **dismissed** picker called nothing at all
 * (`SessionExportSaver.deliver` returned early), so `SessionScreen`'s
 * `exportWorking` stayed true and every later tap exited at the re-entry guard
 * — permanently silent, whatever SAF would have done.
 *
 * Two kinds of pins below, both JVM-only:
 *
 * 1. the pure outcome → feedback mapping (`ExportFeedback.of`), which is what
 *    makes silence structural: the mapping is a `when` over the sealed
 *    [ExportFinish] with no `else`, so a new outcome cannot compile without
 *    its own copy, and each member is a distinct value, so the copy a device
 *    shows identifies the branch that ran;
 * 2. source pins for the branches that are pure Android (the SAF callback, the
 *    toast switch) — the repo's established answer for Compose/Android glue
 *    (`SessionOverflowMenuTest`, `SessionScreenExportErrorTest`).
 *
 * v9 added a third: the overlay's **exit funnel**. The device run tapped
 * `⋮ → Export session`, watched the mask spin for 12–75 s and then close with
 * no message at all, on the path where the payload read never answered. The
 * pins below therefore count the writers of that flag
 * ([everyOverlayExitReportsThroughTheSingleWriter]) and check that the read
 * that used to hang unbounded now carries a reported deadline
 * ([thePayloadReadIsBoundedAndItsTimeOutIsReported]) — "closed in silence" is
 * not a branch anyone can add back.
 */
class SessionExportFeedbackTest {

    private fun source(relative: String): String {
        val file = File(relative).takeIf { it.isFile } ?: File("app/$relative")
        // CRLF sources would break `\n`-sensitive windows otherwise.
        return file.readText().replace("\r\n", "\n")
    }

    private fun saver() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/export/SessionExportSaver.kt")

    private fun feedback() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/export/SessionExportFeedback.kt")

    private fun sessionScreen() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionScreen.kt")

    /**
     * The whole `⋮ → Export session` section: the overlay state, the report
     * funnel, the entry point and the cancel tap. It starts at the section
     * banner (not at `fun exportSession()`, which the funnel is declared above)
     * so the funnel itself is inside the window.
     */
    private fun exportBlock(): String {
        val body = sessionScreen()
        val start = body.indexOf("// ------------------------------------------------------------ A8 export")
        val end = body.indexOf("// ------------------------------------------------- B2 context tab payload")
        assertTrue("export section not found", start >= 0 && end > start)
        return body.substring(start, end)
    }

    // --------------------------------------------------- outcome → feedback

    @Test
    fun dismissedPickerIsReportedAsACancel() {
        // The old `deliver` returned here without calling anything: this is the
        // branch that muted the whole feature for the rest of the process.
        assertEquals(ExportFeedback.CANCELLED, ExportFeedback.of(ExportFinish.Cancelled))
    }

    @Test
    fun exhaustedSaveIsReportedAsAFailure() {
        assertEquals(ExportFeedback.FAILED, ExportFeedback.of(ExportFinish.Failed))
    }

    @Test
    fun pickerSaveAndDownloadBypassCarryDistinctCopy() {
        val picked = ExportFeedback.of(ExportFinish.Saved("ses_1.json"))
        val bypassed = ExportFeedback.of(ExportFinish.Saved("ses_1.json", viaFallback = true))
        assertEquals(ExportFeedback.SAVED, picked)
        assertEquals(ExportFeedback.SAVED_TO_DOWNLOADS, bypassed)
        // The bypass case is the "no document handler" branch: it must not read
        // like a normal save, or the user hunts for a dialog that never showed.
        assertNotEquals(picked, bypassed)
    }

    @Test
    fun everyOutcomeHasItsOwnFeedback() {
        // Four finishes, four values: 取数失败 is the screen's own copy (it never
        // reaches `ExportFinish`), so these are the four the saver can produce.
        val outcomes = listOf(
            ExportFinish.Saved("a.json"),
            ExportFinish.Saved("a.json", viaFallback = true),
            ExportFinish.Cancelled,
            ExportFinish.Failed,
        ).map { ExportFeedback.of(it) }
        assertEquals("each outcome must be distinguishable", 4, outcomes.toSet().size)
        assertEquals("a new member needs its own copy", 4, ExportFeedback.values().size)
    }

    @Test
    fun theMappingIsExhaustiveWithoutAnElse() {
        // The structural half of the contract: a `when` over the sealed
        // interface (no `else` arm) fails the build when `ExportFinish` grows.
        val body = feedback()
        assertTrue(body.contains("fun of(finish: ExportFinish): ExportFeedback = when (finish) {"))
        assertTrue(!body.contains("else ->"))
        assertTrue(body.contains("is ExportFinish.Saved ->"))
        assertTrue(body.contains("ExportFinish.Cancelled -> CANCELLED"))
        assertTrue(body.contains("ExportFinish.Failed -> FAILED"))
    }

    // -------------------------------------------------------- saver branches

    @Test
    fun saverDeliversTheDismissInsteadOfReturningSilently() {
        val body = saver()
        // the reclaim/cancel guard still ignores a *late* answer
        assertTrue(body.contains("if (result == null && finish == null) return"))
        // …but a dismissed dialog is delivered, via the pure routing rule
        assertTrue(body.contains("finish?.invoke(routeSaveOutcome(picked = false, writeOk = false"))
        // and the old silent early return is gone
        assertTrue(
            "the silent cancel branch must not come back",
            !body.contains("if (uri == null || json == null || filename == null) return"),
        )
    }

    @Test
    fun cancelPendingReleasesTheScopeCaller() {
        val body = saver()
        val start = body.indexOf("fun cancelPending()")
        val end = body.indexOf("/** Called by [rememberSessionExportSaver] when the SAF dialog returns. */")
        assertTrue("cancelPending not found", start >= 0 && end > start)
        val cancel = body.substring(start, end)
        // the overlay's Cancel button lands here: the caller must hear back …
        assertTrue(cancel.contains("finish?.invoke(ExportFinish.Cancelled)"))
        // … while the boolean overload keeps upstream's silent dismiss
        assertTrue("a dismissed dialog is not a failed write", !cancel.contains("result?.invoke"))
    }

    // --------------------------------------------------------- screen branch

    @Test
    fun theScreenAnswersEveryOutcomeAndCancelsTheStuckFlag() {
        val block = exportBlock()
        assertTrue("no silent cancel branch", !block.contains("ExportFinish.Cancelled -> Unit"))
        assertTrue("the funnel maps every outcome it reports", block.contains("when (feedback) {"))
        assertTrue(block.contains("ExportFeedback.of(finish),"))
        assertTrue(block.contains("exportWorking = false"))
        for (kind in listOf("SAVED", "SAVED_TO_DOWNLOADS", "CANCELLED", "FAILED")) {
            assertTrue("ExportFeedback.$kind needs an arm", block.contains("ExportFeedback.$kind ->"))
        }
        // each arm names its copy: success, Download/ bypass, cancel, failure
        assertTrue(block.contains("R.string.oc_export_success, filename"))
        assertTrue(block.contains("R.string.oc_export_saved_downloads, filename"))
        assertTrue(block.contains("R.string.oc_export_cancelled"))
        assertTrue(block.contains("R.string.oc_export_failed"))
        // …and the failure arm still names the stage's reason when there is one
        assertTrue(block.contains("R.string.oc_export_fetch_failed, it"))
    }

    // ------------------------------------------------------- the exit funnel

    @Test
    fun everyOverlayExitReportsThroughTheSingleWriter() {
        val screen = sessionScreen()
        // The overlay is mounted from `exportWorking`. Exactly one assignment
        // clears it, and that assignment lives in the funnel that toasts — so no
        // exit can take the mask down without saying how the attempt ended.
        assertEquals(
            "the overlay flag needs exactly one writer",
            1,
            Regex("""exportWorking = false""").findAll(screen).count(),
        )
        assertEquals(
            "one report funnel",
            1,
            Regex("""fun reportExport\(feedback: ExportFeedback, filename: String, reason: String\? = null\)""")
                .findAll(screen).count(),
        )
        val block = exportBlock()
        // the ledger is what keeps the funnel to one message per attempt …
        assertTrue(block.contains("if (!exportLedger.settle(feedback)) return"))
        assertTrue(block.contains("exportLedger.begin()"))
        // … and the entry point never dispatches into a dead scope in silence
        assertTrue(block.contains("val scopeJob = scope.coroutineContext[Job]"))
        assertTrue(block.contains("if (scopeJob != null && !scopeJob.isActive) {"))
    }

    @Test
    fun thePayloadReadIsBoundedAndItsTimeOutIsReported() {
        val block = exportBlock()
        // the read the v9 run watched hang for 12–75 s is now raced
        assertTrue(block.contains("withExportDeadline(scope) {"))
        assertTrue(block.contains("ExportStageResult.TimedOut ->"))
        // and a timeout is not the generic "failed" copy: it names the deadline
        assertTrue(block.contains("R.string.oc_export_fetch_timeout"))
        assertTrue(block.contains("(EXPORT_FETCH_TIMEOUT_MS / 1000L).toInt()"))
        // a scope cancellation reports too, instead of leaving the mask behind
        assertTrue(block.contains("catch (_cancelled: CancellationException)"))
        assertTrue(block.contains("reportExport(ExportFeedback.CANCELLED, filename)"))
        // the overlay names the stage it waits on, so a long wait ≠ a hang
        assertTrue(sessionScreen().contains("ExportWorkingOverlay(phase = exportPhase, onCancel = ::cancelExport)"))
        assertTrue(sessionScreen().contains("ExportPhase.FETCHING -> R.string.oc_export_phase_fetch"))
        assertTrue(sessionScreen().contains("ExportPhase.SAVING -> R.string.oc_export_phase_save"))
    }

    @Test
    fun theFetchFailureNamesTheTransportReason() {
        val block = exportBlock()
        // the Result seam, so the reason survives (the nullable twin drops it)
        assertTrue(block.contains("chatViewModel.fetchExportFrameResult(activeSession.id, reviewRootPath)"))
        assertTrue(block.contains("result.exceptionOrNull()?.message"))
        assertTrue(block.contains("R.string.oc_export_fetch_failed"))
        // and the reason-less case still reports (no transport)
        assertTrue(block.contains("?: context.getString(R.string.oc_export_failed)"))
    }

    @Test
    fun aReTapWhileAnAttemptIsOutIsNotSilent() {
        val block = exportBlock()
        val guard = block.indexOf("if (exportWorking) {")
        assertTrue("re-entry guard not found", guard >= 0)
        val dispatch = block.indexOf("exportWorking = true", guard)
        assertTrue(dispatch > guard)
        // the guard's body says so instead of returning in silence
        assertTrue(block.substring(guard, dispatch).contains("R.string.oc_export_in_progress"))
    }

    @Test
    fun theCancelTapReportsWhenItDropsAFetchToo() {
        val block = exportBlock()
        val start = block.indexOf("fun cancelExport()")
        assertTrue(start >= 0)
        val cancel = block.substring(start)
        // the saver-owned dismiss reports itself (its `ExportFinish` lands in the
        // funnel); the fetch it drops is reported by this call. The ledger drops
        // whichever of the two arrives second — exactly one message per attempt.
        assertTrue(cancel.contains("exportSaver.cancelPending()"))
        assertTrue(cancel.contains("reportExport(ExportFeedback.CANCELLED"))
    }

    // ------------------------------------------------------------- the copy

    @Test
    fun theNewCopyExistsInAllThreeLocales() {
        val keys = listOf(
            "oc_export_cancelled",
            "oc_export_fetch_failed",
            "oc_export_saved_downloads",
            "oc_export_in_progress",
            // v9: the read deadline's own words (it rides the fetch failure's
            // reason slot) plus the two stage hints of the progress overlay
            "oc_export_fetch_timeout",
            "oc_export_phase_fetch",
            "oc_export_phase_save",
            "oc_session_import_timeout",
        )
        for (dir in listOf("values", "values-zh-rCN", "values-zh-rTW")) {
            val body = source("src/main/res/$dir/strings.xml")
            for (key in keys) {
                val value = Regex("""<string name="$key">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
                    .find(body)
                    ?.groupValues
                    ?.get(1)
                assertTrue("$dir is missing a non-blank $key", !value.isNullOrBlank())
            }
        }
        // the reason-carrying copy must keep its format slot
        val base = source("src/main/res/values/strings.xml")
        assertTrue(base.contains("""<string name="oc_export_fetch_failed">Could not fetch session data: %1${'$'}s</string>"""))
        assertTrue(base.contains("""<string name="oc_export_saved_downloads">No save dialog available"""))
        // the deadline reads as that reason, so its seconds slot must survive:
        // "Could not fetch session data: no answer within 60 s"
        for (dir in listOf("values", "values-zh-rCN", "values-zh-rTW")) {
            val body = source("src/main/res/$dir/strings.xml")
            assertTrue(
                "$dir: the timeout reason must carry the deadline",
                body.contains("""<string name="oc_export_fetch_timeout">""") &&
                    Regex("""<string name="oc_export_fetch_timeout">[^<]*%1\${'$'}d""").containsMatchIn(body),
            )
        }
    }

    // ------------------------------------------- the context tab's own chain

    @Test
    fun theContextTabExportChainKeepsItsFeedback() {
        // The audit pin for `ExportSessionButton`'s four rows. It used to record
        // the one gap left in that file: the three save rows went through the
        // **boolean** `save(name, json)` overload, whose dismissed picker calls
        // nothing and whose `Download/` bypass reads as a normal save. The gap
        // is closed (the panel is this wave's file): all three rows use the
        // scope overload and answer every outcome through `ExportFeedback`'s
        // four distinct copies.
        val panel = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/context/SessionContextPanel.kt")
        // the scope overload, never the silent boolean one
        assertTrue(panel.contains("saver.save(current.filename, current.json, scope) {"))
        assertTrue(panel.contains(", scope) { finish ->"))
        assertTrue(!panel.contains("current.json) { report("))
        assertTrue(!panel.contains("fun report(filename: String, ok: Boolean)"))
        // one arm per outcome, each naming its own copy
        assertTrue(panel.contains("when (feedback) {"))
        for (kind in listOf("SAVED", "SAVED_TO_DOWNLOADS", "CANCELLED", "FAILED")) {
            assertTrue("ExportFeedback.$kind needs an arm", panel.contains("ExportFeedback.$kind ->"))
        }
        assertTrue(panel.contains("R.string.oc_export_cancelled"))
        assertTrue(panel.contains("R.string.oc_export_saved_downloads, filename"))
        // both save rows (ASSEMBLED + the route-backed pair) report through it
        val reports = panel.split("report(ExportFeedback.of(finish), current.filename)").size - 1
        assertEquals("both save rows must report", 2, reports)
        // the pre-saver exits (no payload, no session id, no route answer,
        // timed-out route read) go through the same funnel, which releases the
        // export flag on every arm — they used to be a `fail()` helper that
        // reported without a reason and invited one more silent `return`
        assertTrue(panel.contains("fun report(feedback: ExportFeedback, filename: String, reason: String? = null)"))
        assertTrue(!panel.contains("fun fail()"))
        for (exit in listOf(
            "report(ExportFeedback.FAILED, \"\")",
            "report(ExportFeedback.FAILED, current?.filename.orEmpty())",
            "report(ExportFeedback.FAILED, current.filename, transfer.cause.message)",
            "report(ExportFeedback.FAILED, current.filename)",
        )) {
            assertTrue(exit, panel.contains(exit))
        }
        // the route read is bounded and reports its deadline, like the screen's
        assertTrue(panel.contains("withExportDeadline(scope) { source.fetch(sessionID, kind.sanitizeParam()) }"))
        assertTrue(panel.contains("ExportStageResult.TimedOut ->"))
        assertTrue(panel.contains("R.string.oc_export_fetch_timeout"))
        // …and a cancelled scope releases the flag instead of leaving the three
        // rows disabled for the rest of the composition
        assertTrue(panel.contains("catch (_cancelled: CancellationException)"))
        // the fourth row (Import) reports through its own pair of copies — and
        // now answers a timeout too, through the importer's scope overload
        assertTrue(panel.contains("importer.importSession(scope) { finish ->"))
        assertTrue(panel.contains("oc_session_import_success"))
        assertTrue(panel.contains("oc_session_import_failed"))
        assertTrue(panel.contains("oc_session_import_timeout"))
        assertTrue(panel.contains("ImportFinish.Cancelled -> null"))
    }

    /**
     * Toast lifetime (audit finding): the panel's outcome copy — saved /
     * dismissed / failed, each carrying a filename or the server's reason — was
     * `LENGTH_SHORT` like a passing hint, so it could be gone before it was read.
     * Every toast this panel shows is an outcome (its busy states are the rows'
     * own `busy` flags, never a toast), so no `LENGTH_SHORT` is left in the file.
     */
    @Test
    fun thePanelsOutcomeToastsOutliveAPassingHint() {
        val panel = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/context/SessionContextPanel.kt")
        assertTrue("no LENGTH_SHORT left in the panel", !panel.contains("Toast.LENGTH_SHORT"))
        // the export funnel …
        assertTrue(panel.contains("Toast.makeText(context, text, Toast.LENGTH_LONG).show()"))
        // … and the import row's own report
        assertTrue(panel.contains("text?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }"))
    }
}
