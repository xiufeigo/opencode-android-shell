package com.xiufeigo.opencodeshell

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v78 (emulator-5558): a manual rename is overwritten by in-flight auto-title
 * reads.
 *
 * After renaming a session to `E2ECHK5558-T1`, one prompt after another
 * completed and the title was rewritten each time (A→B→C). Root cause: the
 * server renames sessions in the background (title agent — the run's own
 * `Round 2 cold variant test` is one), and every prompt this client sent arms
 * another in-flight read that picks the new name up (`refreshSnapshot`'s
 * `fetchSession`, the catalogue rows). Those reads answer after the rename and
 * `publishCurrentTitle` filed each one unconditionally.
 *
 * The fix records the hand-set spelling per session (`manualTitles`, written
 * by `renameSession` itself) and the single publisher drops a server title
 * that disagrees with it — filed nowhere, published nowhere — so the rename
 * sticks while its own write (same spelling) still lands.
 */
class SessionTitleManualGuardV78Test {

    // ------------------------------------------------------- the pure rule

    @Test
    fun aSessionNeverRenamedByHandAdoptsAnyServerTitle() {
        assertTrue(adoptsServerTitle(null, "Round 2 cold variant test"))
        assertTrue(adoptsServerTitle(null, "Anything at all"))
    }

    @Test
    fun theRenameEchoAdoptsBecauseItCarriesTheSameSpelling() {
        assertTrue(adoptsServerTitle("E2ECHK5558-T1", "E2ECHK5558-T1"))
    }

    @Test
    fun aLateAutoTitleThatDisagreesWithTheManualRenameIsDropped() {
        assertFalse(adoptsServerTitle("E2ECHK5558-T1", "Round 2 cold variant test"))
        assertFalse(adoptsServerTitle("E2ECHK5558-T1", "Old auto title"))
    }

    // ------------------------------------------------------- the wiring pins

    @Test
    fun renameSessionRecordsTheHandSetSpelling() {
        val text = source("src/main/java/com/xiufeigo/opencodeshell/ChatViewModel.kt")
        // v81 P1: the spelling filed for the guard is the one the transport
        // **confirmed** (`RenameResult.Renamed.title`, an exact match by
        // construction). The pin's meaning is unchanged — renameSession files
        // the server-echoed spelling for the guard to compare against — and it
        // can no longer be a title the server did not hold.
        assertTrue(
            "renameSession must file the server-echoed spelling for the guard to compare against",
            text.contains("manualTitles[id] = result.title"),
        )
    }

    @Test
    fun thePublisherDropsAServerTitleThatDisagreesWithTheManualOne() {
        val text = source("src/main/java/com/xiufeigo/opencodeshell/ChatViewModel.kt")
        assertTrue(
            "the registry for hand-set titles is missing",
            text.contains("private val manualTitles"),
        )
        assertTrue(
            "publishCurrentTitle must consult the guard before filing or publishing",
            text.contains("if (!adoptsServerTitle(manualTitles[forID], title)) return"),
        )
    }

    @Test
    fun theGuardDoesNotDisturbTheTrackedSessionRule() {
        // v69: a write for a session that is no longer tracked still records
        // under its own id and leaves the live title alone.
        val text = source("src/main/java/com/xiufeigo/opencodeshell/ChatViewModel.kt")
        assertTrue(
            "the tracked-session gate must stay the publisher's last word",
            text.contains("if (forID != null && forID != sessionID) return"),
        )
    }

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?.replace("\r\n", "\n")
            ?: error("missing $relative")
}
