package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.home.HomeRowExportSaver
import com.xiufeigo.opencodeshell.ui.home.HomeSessions
import com.xiufeigo.opencodeshell.ui.session.export.ExportFinish
import com.xiufeigo.opencodeshell.ui.session.export.SessionExport
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Home session-row `导出会话` had the same deaf branches the session ⋮ menu
 * had before its P0 fix (v8-C cross-file list):
 *
 * - a dismissed picker hit `ExportFinish.Cancelled -> Unit` — no picker, no
 *   toast, no file, and the row looked dead;
 * - a `Download/` bypass write reported the plain success copy, so the user
 *   hunted for a save dialog that never appeared;
 * - the fetch unwrapped the nullable `fetchExportFrame`, which swallows the
 *   transport exception, so every取数 failure read as the generic「导出失败」.
 *
 * [HomeRowExportSaver] takes its fetch, its save and its toast as lambdas and
 * its dispatcher as a parameter, so the whole chain is driven here on one
 * thread (the injected `Unconfined` dispatch runs the launch inline) instead of
 * being pinned by source text: every branch asserts the exact res id and
 * argument a device would toast.
 */
class HomeRowExportFeedbackTest {

    private val row = HomeSessions.Row(
        id = "ses_1",
        title = "Export work",
        directory = "/work",
        project = "work",
        updatedAt = 1_735_689_600_000L,
    )

    /** `SessionExport.filename("ses_1", "Export work")` — case kept (v63 BUG-3). */
    private val filename = "Export-work.json"

    private fun frame(): SessionExport.Frame = SessionExport.Frame(
        JSONObject().put("id", "ses_1"),
        listOf(JSONObject().put("info", JSONObject().put("id", "msg_1"))),
    )

    private class Recorder {
        val toasts = mutableListOf<Pair<Int, String?>>()
        val written = mutableListOf<Pair<String, String>>()
        var answer: ((ExportFinish) -> Unit)? = null
        var fetches = 0

        fun toastResIds() = toasts.map { it.first }
    }

    /**
     * [finish] `null` means the picker never answers (the saver still holds the
     * request), which is what the re-entry guard has to survive.
     */
    private fun saver(rec: Recorder, finish: ExportFinish? = ExportFinish.Saved("x")) =
        HomeRowExportSaver(
            save = { filename, json, _, onFinish ->
                rec.written.add(filename to json)
                rec.answer = onFinish
                finish?.let(onFinish)
            },
            toast = { resId, arg -> rec.toasts.add(resId to arg) },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            io = Dispatchers.Unconfined,
        )

    private fun export(
        rec: Recorder,
        saver: HomeRowExportSaver,
        fetch: suspend (HomeSessions.Row) -> Result<SessionExport.Frame?>,
    ) {
        saver.export(row) { rec.fetches++; fetch(it) }
    }

    // ------------------------------------------------------------- the saves

    @Test
    fun pickerSaveReportsTheFilename() {
        val rec = Recorder()
        val saver = saver(rec, ExportFinish.Saved(filename))
        export(rec, saver) { Result.success(frame()) }
        assertEquals(listOf(R.string.oc_export_success to filename), rec.toasts)
        assertEquals(filename, rec.written.single().first)
        assertTrue(rec.written.single().second.contains("\"messages\""))
        assertFalse(saver.hasAttemptOut)
    }

    @Test
    fun aDismissedPickerIsReportedAsACancelled() {
        val rec = Recorder()
        val saver = saver(rec, ExportFinish.Cancelled)
        export(rec, saver) { Result.success(frame()) }
        // the old `ExportFinish.Cancelled -> Unit` arm: no toast at all
        assertEquals(listOf(R.string.oc_export_cancelled to null), rec.toasts)
        assertFalse("the attempt must not stay out forever", saver.hasAttemptOut)
    }

    @Test
    fun theDownloadBypassSaysWhereTheFileWent() {
        val rec = Recorder()
        export(rec, saver(rec, ExportFinish.Saved(filename, viaFallback = true))) {
            Result.success(frame())
        }
        // not the plain success copy: no dialog appeared, the file is in Download/
        assertEquals(listOf(R.string.oc_export_saved_downloads to filename), rec.toasts)
    }

    @Test
    fun anExhaustedSaveReportsFailure() {
        val rec = Recorder()
        val saver = saver(rec, ExportFinish.Failed)
        export(rec, saver) { Result.success(frame()) }
        assertEquals(listOf(R.string.oc_export_failed to null), rec.toasts)
        assertFalse(saver.hasAttemptOut)
    }

    // ------------------------------------------------------------- the fetch

    @Test
    fun theFetchFailureNamesTheTransportReason() {
        val rec = Recorder()
        export(rec, saver(rec)) { Result.failure(IOException("connection refused")) }
        assertEquals(listOf(R.string.oc_export_fetch_failed to "connection refused"), rec.toasts)
        // the saver is never reached: one attempt, one message
        assertTrue(rec.written.isEmpty())
    }

    @Test
    fun aThrowingFetchKeepsItsReasonToo() {
        val rec = Recorder()
        export(rec, saver(rec)) { throw IOException("dial tcp 127.0.0.1:4095: connect: refused") }
        assertEquals(
            listOf(R.string.oc_export_fetch_failed to "dial tcp 127.0.0.1:4095: connect: refused"),
            rec.toasts,
        )
    }

    @Test
    fun noTransportStillReportsInsteadOfGoingSilent() {
        val rec = Recorder()
        export(rec, saver(rec)) { Result.success(null) }
        assertEquals(listOf(R.string.oc_export_failed to null), rec.toasts)
    }

    /**
     * v40 tC: the Home export failed 100% and said only「导出会话失败」, because
     * "no transport at all" came back as a reason-less `Result.success(null)` —
     * the very shape the test above pins. It is a named failure now, and it gets
     * its own line: nothing was sent, so the save picker never opens and the
     * user needs the server, not the export.
     */
    @Test
    fun aNeverSentReadNamesTheMissingConnection() {
        val rec = Recorder()
        export(rec, saver(rec)) { Result.failure(TransportUnavailableException()) }
        assertEquals(listOf(R.string.oc_export_no_transport to null), rec.toasts)
        assertTrue("the saver must not run", rec.written.isEmpty())
    }

    @Test
    fun theNoTransportFailureCarriesNoMessageSoEveryOtherCallerKeepsItsCopy() {
        // SessionScreen's export report and the context tab's
        // `contextExportError` show `cause.message` and fall back to
        // `oc_export_failed` when it is null — the exact behaviour they had
        // while the case was `success(null)`.
        val rec = Recorder()
        export(rec, saver(rec)) { Result.failure(TransportUnavailableException()) }
        assertEquals("no raw English reaches the toast", null, rec.toasts.single().second)
        assertEquals(null, TransportUnavailableException().message)
    }

    @Test
    fun theViewModelFailsTypedInsteadOfReportingAnEmptySuccess() {
        val vm = source("src/main/java/com/xiufeigo/opencodeshell/ChatViewModel.kt")
        val start = vm.indexOf("suspend fun fetchExportFrameResult(")
        assertTrue(start >= 0)
        val body = vm.substring(start, start + 900)
        // the silent `?: return Result.success(null)` the v40 run hit
        assertFalse(
            "no-transport must not come back as a success carrying no reason",
            body.contains("Result.success(null)"),
        )
        assertTrue(body.contains("Result.failure(TransportUnavailableException())"))
    }

    @Test
    fun theNoTransportCopyIsShippedInEveryLocale() {
        for ((locale, english) in listOf(
            "values" to true,
            "values-zh-rCN" to false,
            "values-zh-rTW" to false,
        )) {
            val xml = source("src/main/res/$locale/strings.xml")
            val at = xml.indexOf("name=\"oc_export_no_transport\"")
            assertTrue("$locale is missing oc_export_no_transport", at >= 0)
            val line = xml.substring(at, xml.indexOf('\n', at))
            assertEquals(
                "only the default locale may keep the English line",
                english,
                line.contains(">Not connected"),
            )
            assertFalse("$locale ships a placeholder it cannot fill", line.contains("%"))
        }
    }

    @Test
    fun aBlankReasonFallsBackToTheGenericCopy() {
        val rec = Recorder()
        export(rec, saver(rec)) { Result.failure(IOException("   ")) }
        assertEquals(listOf(R.string.oc_export_failed to null), rec.toasts)
    }

    // ---------------------------------------------------------- re-entry

    @Test
    fun aSecondTapWhileAnAttemptIsOutSaysSo() {
        val rec = Recorder()
        val saver = saver(rec, finish = null)
        export(rec, saver) { Result.success(frame()) }
        assertTrue(saver.hasAttemptOut)
        assertEquals("the pending attempt is silent so far", emptyList<Pair<Int, String?>>(), rec.toasts)

        export(rec, saver) { Result.success(frame()) }
        assertEquals(
            "the second tap must not stack a picker or read as a failure",
            listOf(R.string.oc_export_in_progress to null),
            rec.toasts,
        )
        assertEquals("the in-flight fetch is not repeated", 1, rec.fetches)

        // the saver's own answer releases the guard
        rec.answer?.invoke(ExportFinish.Cancelled)
        assertFalse(saver.hasAttemptOut)
        assertEquals(2, rec.toasts.size)
    }

    // -------------------------------------------------------- the wiring

    @Test
    fun theHomeRowFetchCarriesTheTransportReason() {
        // The reason can only reach this saver through the Result seam, so the
        // host wiring is part of the contract: `fetchExportFrame` (nullable)
        // would silently bring back the generic copy.
        val app = source("src/main/java/com/xiufeigo/opencodeshell/ui/App.kt")
        assertTrue(app.contains("export = { row -> viewModel.fetchExportFrameResult(row.id, row.directory) }"))
        assertTrue(!app.contains("export = { row -> viewModel.fetchExportFrame(row.id, row.directory) }"))

        val actions =
            source("src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeRowActions.kt")
        assertTrue(actions.contains("val export: suspend (row: HomeSessions.Row) -> Result<SessionExport.Frame?>"))
        // the silent cancel arm must not come back
        assertTrue(!actions.contains("ExportFinish.Cancelled -> Unit"))
        assertTrue(actions.contains("when (ExportFeedback.of(finish))"))
        for (kind in listOf("SAVED", "SAVED_TO_DOWNLOADS", "CANCELLED", "FAILED")) {
            assertTrue("ExportFeedback.$kind needs an arm", actions.contains("ExportFeedback.$kind ->"))
        }
    }

    /**
     * Toast lifetime (audit finding): the host's single toast seam serves both
     * the re-tap hint and the outcomes, and all of them were `LENGTH_SHORT`, so a
     * 「保存失败」 that carried the server's reason was gone as fast as a hint.
     * The outcomes are long now — the hint stays short, it is not a result.
     */
    @Test
    fun theOutcomeToastsOutliveTheRetapHint() {
        val actions = source("src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeRowActions.kt")
        val host = actions.substringAfter("toast = { resId, arg ->").substringBefore("},\n")
        assertTrue(host.contains("Toast.LENGTH_LONG"))
        assertTrue(
            "only the in-progress hint keeps the short duration",
            host.contains("if (resId == R.string.oc_export_in_progress) Toast.LENGTH_SHORT"),
        )
    }

    private fun source(relative: String): String {
        val file = File(relative).takeIf { it.isFile } ?: File("app/$relative")
        // CRLF sources would break `\n`-sensitive windows otherwise.
        return file.readText().replace("\r\n", "\n")
    }
}
