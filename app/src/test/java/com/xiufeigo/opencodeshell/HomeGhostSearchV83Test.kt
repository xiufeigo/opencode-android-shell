package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.home.HomeSessions
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v83 P3 (emulator-5558 `02_search.png`): 坏目录会话搜索不到 — with `E2ECHK5558`
 * typed in Home's search box the panel answered 「No sessions found for
 * E2ECHK5558」 while the session `E2ECHK5558-gd` (project
 * `/mnt/c/nonexist_v83`) existed.
 *
 * The report's hypothesis — a filter on the search **data source** that drops a
 * bad-directory session — is what these tests check, and they disprove it: the
 * filter matches a case-insensitive substring of the row's title, and nothing on
 * the path (neither [HomeSessions.searchRows] nor [HomeSessions.filter]) looks
 * at whether the directory exists on the server. A session in `nonexist_v83`
 * reaches the results by the very query the device reported.
 *
 * The measured cause is therefore **freshness, not filtering**, and the last
 * test pins that boundary as a fact: the panel filters Home's *own last
 * catalogue read* (`HomeScreen` hands over `titledSessions`), so it can only ever
 * answer about the list the column is drawing. At `18:53:58` that list did not
 * have the row yet — `01_launch.png` (`18:53:48`) shows the same two Today rows
 * without it, and it is there in `07_backlist.png` (`18:54:50`, after the returns
 * that re-read) — while the palette's own search, which reads the catalogue when
 * it opens, found the same session at `18:54:22`
 * (`04_palettesearch.png`). Fixing that means re-reading on search (or a
 * narrower freshness window), not a filter change, so it is recorded instead of
 * patched here.
 */
class HomeGhostSearchV83Test {

    private fun session(
        id: String,
        title: String = id,
        directory: String? = null,
        updatedAt: Long = 0L,
    ) = OpenCodeClient.Session(id = id, title = title, directory = directory, updatedAt = updatedAt)

    /** The measured pair: the ghost session, and Home's read without it. */
    private val ghost = session(
        id = "ses_f0edfb53ffe14Sr",
        title = "E2ECHK5558-gd",
        directory = "/mnt/c/nonexist_v83",
        updatedAt = 1_700_000_000_000,
    )

    @Test
    fun `the query the device used finds the bad-directory session`() {
        val rows = HomeSessions.rows(listOf(ghost))
        val results = HomeSessions.filter(rows, "E2ECHK5558")
        assertEquals(listOf("ses_f0edfb53ffe14Sr"), results.map { it.id })
        assertEquals("E2ECHK5558-gd", results.first().title)
    }

    @Test
    fun `neither the source nor the filter looks at the directory existing`() {
        // The untruncated search source (`searchSessions`) is built with no
        // directory argument at all, and the row it yields keeps the server's
        // own path — nothing resolves it, so "does this folder exist" can never
        // decide whether the session is searchable.
        val all = HomeSessions.searchRows(
            all = listOf(ghost),
            visible = emptyList(),
        )
        assertEquals(listOf("ses_f0edfb53ffe14Sr"), all.map { it.id })
        assertEquals("/mnt/c/nonexist_v83", all.first().directory)
        assertEquals("nonexist_v83", all.first().project)

        // The project label is searchable too, and it is a substring match, so
        // the folder the session was created in is reachable by name as well.
        assertEquals(
            listOf("ses_f0edfb53ffe14Sr"),
            HomeSessions.filter(all, "nonexist").map { it.id },
        )
    }

    @Test
    fun `a query that matches nothing says so, and the ghost is not a special case`() {
        val rows = HomeSessions.rows(listOf(ghost, session("ses_ok", "E2ECHK5554 link check")))
        assertTrue("a genuinely absent name stays absent", HomeSessions.filter(rows, "E2ECHK5558-zz").isEmpty())
        assertEquals(2, HomeSessions.filter(rows, "").size)
    }

    /**
     * The record the P3 verdict rests on: the panel is fed Home's own read, so
     * "no sessions found" is a statement about *that list*, not about the server.
     */
    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()?.replace("\r\n", "\n")
            ?: error("missing $relative")

    @Test
    fun `the panel searches Home's last catalogue read, not a fresh one`() {
        val home = source("src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeScreen.kt")
        val list = source("src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeSessionsList.kt")
        assertTrue(
            "both placements hand the column the same last read",
            Regex("""searchSessions = titledSessions,""").findAll(home).count() == 2,
        )
        assertTrue(
            "the source degrades to the visible rows when the read is empty",
            list.contains("searchSessions.ifEmpty { null }"),
        )
        assertTrue(
            "and the filter is a substring match over title + project label",
            source("src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeSessions.kt")
                .contains("row.title.contains(needle, ignoreCase = true)"),
        )
    }
}
