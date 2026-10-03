package com.xiufeigo.opencodeshell

import android.provider.MediaStore
import com.xiufeigo.opencodeshell.ui.session.export.ExportFinish
import com.xiufeigo.opencodeshell.ui.session.export.SessionExportFallback
import com.xiufeigo.opencodeshell.ui.session.export.routeSaveOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic coverage for the P0 export hardening: the SAF answer routing
 * ([routeSaveOutcome]) and the Download/ fallback descriptor
 * ([SessionExportFallback.downloadEntry]). Both stay Android-free so they run
 * as plain JVM tests; the `MediaStore` write itself ([SessionExportFallback.
 * writeToDownloads]) is a thin insert + stream wrapper over this map.
 */
class SessionExportFallbackTest {

    // ------------------------------------------------------------ routing

    @Test
    fun dismissedPickerIsACancelNeverAFallback() {
        assertEquals(ExportFinish.Cancelled, routeSaveOutcome(picked = false, writeOk = false, filename = "a.json"))
        assertEquals(ExportFinish.Cancelled, routeSaveOutcome(picked = false, writeOk = true, filename = "a.json"))
    }

    @Test
    fun answeredPickerReportsItsWriteResult() {
        assertEquals(
            ExportFinish.Saved("ses_1.json"),
            routeSaveOutcome(picked = true, writeOk = true, filename = "ses_1.json"),
        )
        assertEquals(ExportFinish.Failed, routeSaveOutcome(picked = true, writeOk = false, filename = "ses_1.json"))
    }

    @Test
    fun directSaveIsNotMarkedAsFallback() {
        val saved = routeSaveOutcome(picked = true, writeOk = true, filename = "ses_1.json")
        assertTrue(saved is ExportFinish.Saved)
        assertFalse((saved as ExportFinish.Saved).viaFallback)
    }

    // ----------------------------------------------------- download entry

    @Test
    fun downloadEntryNamesTheFileMimeAndDownloadDir() {
        val entry = SessionExportFallback.downloadEntry("my-session.json")
        assertEquals("my-session.json", entry.getValue(MediaStore.MediaColumns.DISPLAY_NAME))
        assertEquals("application/json", entry.getValue(MediaStore.MediaColumns.MIME_TYPE))
        assertEquals("Download/", entry.getValue(MediaStore.MediaColumns.RELATIVE_PATH))
    }

    @Test
    fun downloadEntryUsesTheJsonMimeType() {
        assertEquals("application/json", SessionExportFallback.MIME_TYPE)
        assertEquals(
            SessionExportFallback.MIME_TYPE,
            SessionExportFallback.downloadEntry("x.json").getValue(MediaStore.MediaColumns.MIME_TYPE),
        )
    }

    // ------------------------------------------------------------ timeout

    @Test
    fun safTimeoutIsGenerousButFinite() {
        // Generous: a user browsing for a folder is not a hang. Finite: a dead
        // picker always releases the UI back to the Download/ fallback.
        assertTrue(SessionExportFallback.SAF_TIMEOUT_MS >= 10_000L)
        assertTrue(SessionExportFallback.SAF_TIMEOUT_MS <= 120_000L)
    }
}
