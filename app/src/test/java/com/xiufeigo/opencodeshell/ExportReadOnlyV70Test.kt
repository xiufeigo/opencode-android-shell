package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.export.ExportFeedback
import com.xiufeigo.opencodeshell.ui.session.export.routeSaveOutcome
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v70 P3 — **「export 走完两步确认后 Projects 多出一个 tmp 项目」**
 * (`research/v69_verify/5560/03x_exported.png` → `03y_export_done.png` →
 * `07j_home.png`, whose uiautomator dump `ui_home.xml` carries the extra row).
 *
 * The observation does not reproduce as a shell defect, and the reason is worth
 * pinning so the next device run does not re-open it:
 *
 * - **The two-step confirmation was the directory picker, not an export.** Those
 *   two shots are `DirectoryPickerDialog`'s 「选择此目录」 → 「确认此目录」 pair
 *   (upstream's `home.model.ts:101-120` add-project probe, the one flow that is
 *   *meant* to write to the server's project catalogue), driven from
 *   `NewSessionScreen`'s project picker. No export frame was ever in that path.
 * - **The row is not this run's.** `GET /api/project` on the shared backend
 *   answers a `/tmp` entry with `time.created = 2026-09-23T09:43:34Z` — six days
 *   before the v69 run, and 639 ms after the very first
 *   `/mnt/c/…/opencode-android-shell` entry, i.e. an earlier round's add. The
 *   Home projects column reads the shared server's catalogue, so on this
 *   multi-device backend it shows whatever every earlier round and every other
 *   device registered. Same class as the v58 note in `research/HANDOFF.md`
 *   (「共享后端上那是别的设备的项目」); not new, and not a shell write.
 * - **Export cannot reach the registration anyway.** `HomeProjectApi.register`
 *   is the only writer of the server-side project catalogue, and it has exactly
 *   one call site — the add-project picker's committed `onSelect`. The export
 *   read is two GETs and the export write is a local file. This suite pins all
 *   of that, because the only way this bug could ever come back is a
 *   registration call quietly appearing in an export path.
 *
 * The second half of the export audit is the "no silent export" contract the
 * v69 report asked for (`oc_export_failed` exists and every surface uses it):
 * all three export entry points render every [ExportFeedback] member.
 */
class ExportReadOnlyV70Test {

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()?.replace("\r\n", "\n")
            ?: error("missing $relative")

    private val mainRoot: File =
        listOf(File("src/main/java/com/xiufeigo/opencodeshell"), File("app/src/main/java/com/xiufeigo/opencodeshell"))
            .firstOrNull { it.isDirectory }
            ?: error("missing main sources")

    private fun window(text: String, from: String, to: String): String {
        val start = text.indexOf(from)
        assertTrue("missing anchor: $from", start >= 0)
        val end = text.indexOf(to, start + from.length)
        assertTrue("missing anchor: $to", end > start)
        return text.substring(start, end)
    }

    // ------------------------------------------------ registration is unreachable from export

    @Test
    fun `the server project catalogue has exactly one writer`() {
        val callers = mutableListOf<String>()
        mainRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            val body = file.readText().replace("\r\n", "\n")
            if (Regex("""\b\w*[Pp]rojectApi\.register\(""").containsMatchIn(body)) {
                callers += file.name
            }
        }
        assertEquals(
            "a second registration call site is how a read-only flow would start writing",
            listOf("HomeScreen.kt"),
            callers,
        )
    }

    @Test
    fun `that one writer is the add-project picker, on a committed selection`() {
        val home = source("src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeScreen.kt")
        val add = window(home, "val onAddProject: () -> Unit = {", "val onEditProject:")
        // Upstream's `home.project.add` registration probe, which runs from the
        // picker's committed `onSelect` and not from browsing (`onNavigate` is
        // deliberately absent — see the comment there).
        assertTrue("the probe rides the picker", add.contains("DirectoryPickerDialog("))
        assertTrue("on a committed selection", add.contains("onSelect = { directory ->"))
        assertTrue(
            "after the row is opened, not before",
            add.indexOf("openProject(directory)") < add.indexOf("projectApi.register(directory)"),
        )
        assertTrue("browsing is not registration", !add.contains("onNavigate = { directory -> register"))
    }

    @Test
    fun `no export surface can reach a registration or a project-store write`() {
        val files = mapOf(
            "SessionExport.kt" to source("src/main/java/com/xiufeigo/opencodeshell/ui/session/export/SessionExport.kt"),
            "SessionExportSaver.kt" to source("src/main/java/com/xiufeigo/opencodeshell/ui/session/export/SessionExportSaver.kt"),
            "SessionExportSource.kt" to source("src/main/java/com/xiufeigo/opencodeshell/ui/session/export/SessionExportSource.kt"),
            "SessionExportFallback.kt" to source("src/main/java/com/xiufeigo/opencodeshell/ui/session/export/SessionExportFallback.kt"),
            "HomeRowActions.kt" to source("src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeRowActions.kt"),
        )
        for ((name, body) in files) {
            for (forbidden in listOf(".register(", "openProject(", "homeProjectStore", "api.location(")) {
                assertTrue("$name must stay read-only (found `$forbidden`)", !body.contains(forbidden))
            }
        }
    }

    @Test
    fun `the session screen's export reads the frame and writes nothing to the server`() {
        val screen = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionScreen.kt")
        val export = window(screen, "fun exportSession() {", "fun cancelExport()")
        for (forbidden in listOf(".register(", "openProject(", "homeProjectStore", "api.location(")) {
            assertTrue("exportSession must stay read-only (found `$forbidden`)", !export.contains(forbidden))
        }
        // Its whole server side is the one frame read; the write is the SAF saver.
        assertTrue("one read", export.contains("chatViewModel.fetchExportFrameResult(activeSession.id"))
        assertTrue("one write, to the file picker", export.contains("exportSaver.save("))
    }

    @Test
    fun `the export drain is two GETs and never loads a location`() {
        val transport = source("src/main/java/com/xiufeigo/opencodeshell/v2/V2Transport.kt")
        val drain = window(transport, "private fun readExportFrame(sessionID: String)", "/** A follower's view")
        assertTrue("the session object", drain.contains("api.sessionRaw(sessionID)"))
        assertTrue("the messages", drain.contains("api.messagesRawPage(sessionID)"))
        // `GET /api/location[directory]` is what puts a directory into the
        // server's project catalogue; the export must not ask for one.
        assertTrue("no location load", !drain.contains("api.location("))
    }

    @Test
    fun `the only location load that names a directory is the add-project probe`() {
        val transport = source("src/main/java/com/xiufeigo/opencodeshell/v2/V2Transport.kt")
        // The picker's read-only uses (`browseHome` / `browseCwd`) pass nothing,
        // so they read the instance default and register nothing.
        assertEquals(
            "a named-directory location load outside the probe is a new registration",
            0,
            Regex("""api\.location\(directory""").findAll(transport).count(),
        )
        val api = source("src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeProjectApi.kt")
        val probe = window(api, "fun register(worktree: String): Boolean {", "/** `POST /api/worktree/refresh`")
        assertTrue("the probe names the directory", probe.contains("api.location(directory = directory)"))
    }

    // ------------------------------------------------ every export outcome is still reported

    @Test
    fun `all three export surfaces render every outcome`() {
        val surfaces = mapOf(
            "SessionScreen" to window(
                source("src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionScreen.kt"),
                "fun reportExport(feedback: ExportFeedback",
                "fun exportSession() {",
            ),
            "HomeRowExportSaver" to window(
                source("src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeRowActions.kt"),
                "when (ExportFeedback.of(finish)) {",
                "/**\n     * v40 tC:",
            ),
            "SessionContextPanel" to window(
                source("src/main/java/com/xiufeigo/opencodeshell/ui/session/context/SessionContextPanel.kt"),
                "fun report(feedback: ExportFeedback",
                "fun save(kind: SessionExportKind) {",
            ),
        )
        for ((name, body) in surfaces) {
            for (member in listOf("SAVED", "SAVED_TO_DOWNLOADS", "CANCELLED", "FAILED")) {
                assertTrue("$name must render ExportFeedback.$member", body.contains("ExportFeedback.$member"))
            }
            // The failure branch is the one the v69 report asked about, and the
            // only line `oc_export_failed` is for.
            assertTrue("$name must report a failure", body.contains("oc_export_failed"))
        }
    }

    @Test
    fun `a dismissed or unwritable export is never silent`() {
        val saver = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/export/SessionExportSaver.kt")
        // The scope overload's contract is "exactly one [ExportFinish] per
        // attempt"; a dismiss that reported nothing is what used to wedge the
        // caller's busy flag so every later tap did nothing at all.
        val deliver = window(saver, "internal suspend fun deliver(uri: Uri?) {", "private fun claim(")
        assertTrue("a dismissed picker is delivered", deliver.contains("routeSaveOutcome(picked = false"))
        assertTrue("a failed write is delivered as Failed", deliver.contains("ExportFinish.Failed"))
        assertTrue("a reclaimed request releases the scope overload too", saver.contains("ExportFinish.Cancelled"))
        val fallback = window(saver, "private fun onPickerTimeout(", "private fun finishViaFallback(")
        assertTrue("a silent picker falls back rather than stalling", fallback.contains("finishViaFallback("))
        // The mapping itself, so a dismiss can never route back to the silent
        // branch: `routeSaveOutcome` is what the saver answers both dispatches
        // with, and it has no "no answer" outcome at all.
        assertEquals(
            ExportFeedback.CANCELLED,
            ExportFeedback.of(routeSaveOutcome(picked = false, writeOk = false, filename = "s.json")),
        )
        assertEquals(
            ExportFeedback.SAVED,
            ExportFeedback.of(routeSaveOutcome(picked = true, writeOk = true, filename = "s.json")),
        )
        assertEquals(
            ExportFeedback.FAILED,
            ExportFeedback.of(routeSaveOutcome(picked = true, writeOk = false, filename = "s.json")),
        )
    }
}
