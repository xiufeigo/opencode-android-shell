package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.home.HomeSessions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * M3: Home's sessions column logic (`research/02` §1.3) — the Today /
 * Yesterday / Older bucketing, the 64-row cap, the project label and the search
 * filter. Time and zone are injected, so the tests never depend on the clock.
 */
class HomeSessionsTest {

    private val zone: ZoneId = ZoneId.of("UTC")
    private val now: Long = at("2024-05-20T12:00:00Z")

    private fun at(iso: String): Long = Instant.parse(iso).toEpochMilli()

    private fun session(
        id: String,
        title: String = id,
        directory: String? = null,
        updatedAt: Long = 0L,
    ) = OpenCodeClient.Session(id = id, title = title, directory = directory, updatedAt = updatedAt)

    @Test
    fun `groups by local calendar day not by a 24 hour window`() {
        // 23:00 yesterday is "yesterday" even though it is only 13 h back; 23:59
        // the day before is "older" even though it is 36 h back.
        assertEquals(HomeSessions.Group.TODAY, HomeSessions.groupOf(at("2024-05-20T00:00:00Z"), now, zone))
        assertEquals(
            HomeSessions.Group.YESTERDAY,
            HomeSessions.groupOf(at("2024-05-19T23:59:00Z"), now, zone),
        )
        assertEquals(HomeSessions.Group.OLDER, HomeSessions.groupOf(at("2024-05-18T23:59:00Z"), now, zone))
    }

    @Test
    fun `unknown updated time is older`() {
        assertEquals(HomeSessions.Group.OLDER, HomeSessions.groupOf(0L, now, zone))
        assertEquals(HomeSessions.Group.OLDER, HomeSessions.groupOf(-1L, now, zone))
    }

    @Test
    fun `sections keep the fixed order and drop empty groups`() {
        val rows = HomeSessions.rows(
            listOf(
                session("today", updatedAt = at("2024-05-20T08:00:00Z")),
                session("older", updatedAt = at("2024-04-01T08:00:00Z")),
            ),
        )
        val sections = HomeSessions.sections(rows, now, zone)

        // Never [TODAY, YESTERDAY, OLDER] with an empty Yesterday in between.
        assertEquals(listOf(HomeSessions.Group.TODAY, HomeSessions.Group.OLDER), sections.map { it.group })
        assertEquals(listOf("today"), sections[0].rows.map { it.id })
        assertEquals(listOf("older"), sections[1].rows.map { it.id })
    }

    @Test
    fun `yesterday section sits between today and older`() {
        val rows = HomeSessions.rows(
            listOf(
                session("a", updatedAt = at("2024-05-18T01:00:00Z")),
                session("b", updatedAt = at("2024-05-19T01:00:00Z")),
                session("c", updatedAt = at("2024-05-20T01:00:00Z")),
            ),
        )
        val sections = HomeSessions.sections(rows, now, zone)

        assertEquals(
            listOf(HomeSessions.Group.TODAY, HomeSessions.Group.YESTERDAY, HomeSessions.Group.OLDER),
            sections.map { it.group },
        )
        assertEquals(listOf("c"), sections[0].rows.map { it.id })
        assertEquals(listOf("b"), sections[1].rows.map { it.id })
        assertEquals(listOf("a"), sections[2].rows.map { it.id })
    }

    @Test
    fun `caps at 64 rows keeping the newest`() {
        val sessions = (0 until 70).map { index ->
            session("s$index", updatedAt = at("2024-05-20T00:00:00Z") + index)
        }

        val rows = HomeSessions.rows(sessions)

        assertEquals(HomeSessions.MAX_SESSIONS, rows.size)
        assertEquals(64, rows.size)
        assertEquals("s69", rows.first().id)
        assertTrue(rows.none { it.id == "s0" })
    }

    @Test
    fun `sorts newest first regardless of the input order`() {
        val rows = HomeSessions.rows(
            listOf(
                session("old", updatedAt = 10L),
                session("new", updatedAt = 30L),
                session("mid", updatedAt = 20L),
            ),
        )
        assertEquals(listOf("new", "mid", "old"), rows.map { it.id })
    }

    @Test
    fun `project name is the last path segment on both separators`() {
        assertEquals("proj", HomeSessions.projectName("/home/me/proj"))
        assertEquals("proj", HomeSessions.projectName("/home/me/proj/"))
        assertEquals("proj", HomeSessions.projectName("/home/me/proj\\"))
        assertEquals("app", HomeSessions.projectName("C:\\src\\app"))
        assertEquals("app", HomeSessions.projectName("C:\\src\\app\\"))
        assertNull(HomeSessions.projectName("/"))
        assertNull(HomeSessions.projectName("C:\\"))
        assertNull(HomeSessions.projectName("C:/"))
        assertNull(HomeSessions.projectName(""))
        assertNull(HomeSessions.projectName("   "))
        assertNull(HomeSessions.projectName(null))
    }

    @Test
    fun `rows carry the derived project label`() {
        val rows = HomeSessions.rows(listOf(session("a", directory = "/work/api", updatedAt = 5L)))
        assertEquals("api", rows.single().project)
        assertEquals("/work/api", rows.single().directory)
    }

    @Test
    fun `search matches title and project name case insensitively`() {
        val rows = HomeSessions.rows(
            listOf(
                session("1", title = "Fix login", directory = "/work/api", updatedAt = 3L),
                session("2", title = "Write docs", directory = "/work/site", updatedAt = 2L),
            ),
        )

        assertEquals(listOf("1"), HomeSessions.filter(rows, "fix").map { it.id })
        assertEquals(listOf("1"), HomeSessions.filter(rows, "API").map { it.id })
        assertEquals(listOf("1", "2"), HomeSessions.filter(rows, "  ").map { it.id })
        assertEquals(2, HomeSessions.filter(rows, "").size)
        assertTrue(HomeSessions.filter(rows, "nothing-matches").isEmpty())
    }

    @Test
    fun `skeleton renders one title bar plus four rows`() {
        assertEquals(4, HomeSessions.SKELETON_ROWS)
    }
}
