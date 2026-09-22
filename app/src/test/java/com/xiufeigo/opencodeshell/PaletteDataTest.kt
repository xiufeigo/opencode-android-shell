package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.palette.CATEGORY_SESSION
import com.xiufeigo.opencodeshell.ui.palette.PaletteEntry
import com.xiufeigo.opencodeshell.ui.palette.PaletteEntryType
import com.xiufeigo.opencodeshell.ui.palette.PaletteHostKind
import com.xiufeigo.opencodeshell.ui.palette.filePaletteEntries
import com.xiufeigo.opencodeshell.ui.palette.sessionPaletteEntries
import com.xiufeigo.opencodeshell.ui.palette.sources
import com.xiufeigo.opencodeshell.ui.palette.visibleEntries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W2-C palette data assembly: `GET /session` / `/find/file` results →
 * `PaletteEntry` rows, and how they flow through `visibleEntries`.
 */
class PaletteDataTest {

    private fun session(id: String, title: String, updatedAt: Long, directory: String? = null) =
        OpenCodeClient.Session(id = id, title = title, directory = directory, updatedAt = updatedAt)

    @Test
    fun sessionEntriesPreserveServerOrderAndShape() {
        // The ViewModel hands over `updatedAt`-descending; conversion must not
        // re-sort (that order IS the palette's session order).
        val entries = sessionPaletteEntries(
            listOf(
                session("ses_new", "Newest", updatedAt = 300, directory = "/w/app"),
                session("ses_mid", "Middle", updatedAt = 200),
                session("ses_old", "Oldest", updatedAt = 100),
            ),
            server = "srv1",
            openSessionIds = setOf("ses_mid"),
        )
        assertEquals(listOf("ses_new", "ses_mid", "ses_old"), entries.map { it.sessionID })
        val first = entries[0]
        assertEquals("session:srv1:ses_new", first.id)
        assertEquals(PaletteEntryType.SESSION, first.type)
        assertEquals(CATEGORY_SESSION, first.category)
        assertEquals("Newest", first.title)
        assertEquals("/w/app", first.description)
        assertFalse(first.open)
        assertTrue(entries[1].open)
        // Rows without a directory simply hide the description line.
        assertNull(entries[1].description)
    }

    @Test
    fun fileEntriesDropBlanksAndKeepServerOrder() {
        val entries = filePaletteEntries(listOf("src/a.kt", "", "src/b.kt", "  "))
        assertEquals(listOf("file:src/a.kt", "file:src/b.kt"), entries.map { it.id })
        assertEquals(listOf("src/a.kt", "src/b.kt"), entries.map { it.path })
        assertTrue(entries.all { it.type == PaletteEntryType.FILE })
    }

    @Test
    fun sessionHostSearchesCommandsSessionsAndFilesTogether() {
        val commands = listOf(PaletteEntry.command("session.new", "New session"))
        val sessions = sessionPaletteEntries(
            listOf(session("ses_1", "palette wiring", updatedAt = 2)),
            server = "srv1",
        )
        val files = filePaletteEntries(listOf("src/palette/model.kt"))
        val sources = PaletteHostKind.SESSION.sources(
            commands = commands,
            sessions = sessions,
            files = files,
        )
        val rows = visibleEntries(sources, "palette")
        assertEquals(
            listOf("session:srv1:ses_1", "file:src/palette/model.kt"),
            rows.map { it.id },
        )
        // A command-only query still matches commands alongside.
        val commandRows = visibleEntries(sources, "new session")
        assertTrue(commandRows.any { it.id == "command:session.new" })
    }

    @Test
    fun homeHostKeepsCommandsOnly() {
        // Upstream `DialogHomeCommandPaletteV2`: no file category at all, and
        // W2-C feeds it no session/file lists either — even if some slipped
        // in, `includeFiles = false` filters files out of search results.
        val sources = PaletteHostKind.HOME.sources(
            commands = listOf(PaletteEntry.command("settings.open", "Open settings")),
            sessions = listOf(PaletteEntry.session("srv1", "ses_1", "hidden session")),
            files = listOf(PaletteEntry.file("src/secret.kt")),
        )
        assertFalse(sources.includeFiles)
        val rows = visibleEntries(sources, "secret")
        assertTrue(rows.none { it.type == PaletteEntryType.FILE })
    }

    @Test
    fun selectingASessionRowCarriesTheSessionID() {
        // App.kt navigates on `entry.sessionID`; the conversion must always
        // populate it (a null would silently drop the selection).
        val entries = sessionPaletteEntries(
            listOf(session("ses_1", "t", updatedAt = 1)),
            server = "srv1",
        )
        assertEquals("ses_1", entries.single().sessionID)
    }
}
