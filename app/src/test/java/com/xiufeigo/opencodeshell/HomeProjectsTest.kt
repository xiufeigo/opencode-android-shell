package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.data.HomeProjectRecords
import com.xiufeigo.opencodeshell.ui.home.HomeProjects
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M3: Home's projects column (`research/02` §1.2). The column is *derived* from
 * the sessions' `directory` values because the client has no project endpoint —
 * these tests pin that derivation down so it cannot silently become a
 * fabrication (§13.2: "不要臆造").
 */
class HomeProjectsTest {

    private fun session(id: String, directory: String?, updatedAt: Long) =
        OpenCodeClient.Session(id = id, title = id, directory = directory, updatedAt = updatedAt)

    @Test
    fun `derives one project per distinct directory, most recent first`() {
        val projects = HomeProjects.fromSessions(
            listOf(
                session("a", "/work/api", 100L),
                session("b", "/work/site", 200L),
                session("c", "/work/api", 50L),
            ),
        )

        assertEquals(listOf("site", "api"), projects.map { it.name })
        assertEquals(listOf("/work/site", "/work/api"), projects.map { it.path })
        assertEquals(listOf(1, 2), projects.map { it.sessionCount })
    }

    @Test
    fun `is order independent`() {
        val shuffled = HomeProjects.fromSessions(
            listOf(
                session("c", "/work/api", 50L),
                session("b", "/work/site", 200L),
                session("a", "/work/api", 100L),
            ),
        )
        assertEquals(listOf("site", "api"), shuffled.map { it.name })
    }

    @Test
    fun `ignores sessions without a directory`() {
        val projects = HomeProjects.fromSessions(
            listOf(
                session("a", null, 100L),
                session("b", "   ", 90L),
                session("c", "/work/api", 80L),
            ),
        )
        assertEquals(listOf("api"), projects.map { it.name })
    }

    @Test
    fun `falls back to the full path when there is no name`() {
        val projects = HomeProjects.fromSessions(listOf(session("a", "/", 10L)))
        assertEquals(listOf("/"), projects.map { it.name })
    }

    @Test
    fun `empty input yields no projects`() {
        assertTrue(HomeProjects.fromSessions(emptyList()).isEmpty())
    }

    @Test
    fun `openRows collapses twin records of one directory into one row`() {
        val rows = HomeProjects.openRows(
            opened = listOf(
                HomeProjectRecords.Project("C:\\repo", sandboxes = listOf("C:\\sb1")),
                HomeProjectRecords.Project("c:/repo/", sandboxes = listOf("C:\\sb2")),
            ),
            known = emptyList(),
        )
        assertEquals(1, rows.size)
        assertEquals("C:\\repo", rows.single().path)
        assertEquals(listOf("C:\\sb1", "C:\\sb2"), rows.single().sandboxes)
    }

    @Test
    fun `openRows collapses msys and win32 twins into one row`() {
        val rows = HomeProjects.openRows(
            opened = listOf(
                HomeProjectRecords.Project(
                    "/mnt/c/sensorsdata/repo",
                    sandboxes = listOf("/mnt/c/sb1"),
                ),
                HomeProjectRecords.Project(
                    "C:\\SensorsData\\Repo",
                    sandboxes = listOf("C:\\sb2"),
                ),
            ),
            known = emptyList(),
        )
        assertEquals(1, rows.size)
        assertEquals("/mnt/c/sensorsdata/repo", rows.single().path)
        assertEquals(listOf("/mnt/c/sb1", "C:\\sb2"), rows.single().sandboxes)
    }

    @Test
    fun `openRows honours a closed tombstone under another spelling`() {
        // `close` records the spelling it was given, but the tombstone matches
        // by key — a twin spelling must not resurrect the row.
        val rows = HomeProjects.openRows(
            opened = listOf(HomeProjectRecords.Project("C:\\sensorsdata\\repo")),
            known = emptyList(),
            closed = listOf("/mnt/c/sensorsdata/repo/"),
        )
        assertTrue(rows.isEmpty())
    }

    @Test
    fun `openRows keeps posix case variants as two rows`() {
        val rows = HomeProjects.openRows(
            opened = listOf(
                HomeProjectRecords.Project("/work/api"),
                HomeProjectRecords.Project("/work/API"),
            ),
            known = emptyList(),
        )
        assertEquals(listOf("/work/api", "/work/API"), rows.map { it.path })
    }

    @Test
    fun `recently closed stays empty until a project store exists`() {
        // `home.recentlyClosed` needs the closed-project stack; M3 has no source
        // for it, so the section (and its header) must stay hidden.
        assertTrue(HomeProjects.RECENTLY_CLOSED.isEmpty())
    }
}
