package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.home.HomeSessions
import com.xiufeigo.opencodeshell.ui.palette.PaletteRelativeTime
import com.xiufeigo.opencodeshell.ui.session.drawer.SessionTimeLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * BUG-1 (the v28 device run): 「会话抽屉的会话行完全没有相对时间标签」 — the row
 * showed avatar + title + project name and nothing else, while the very same
 * session carried a correct, advancing `just now` / `N minutes ago` cell in the
 * command palette.
 *
 * The dump behind that report is Home's sessions column, not the session drawer:
 * its rows carry a project name (`HomeSessionsList.HomeSessionRow`) and the
 * drawer's do not (`SessionDrawer.SessionRow` renders title + time and has had
 * its time slot since A-3). Home's row genuinely had no time slot at all —
 * upstream's own `HomeSessionRow` (`home/sessions/view.tsx:518-660`) stops at the
 * project name — so `HomeSessions.Row.updatedAt` was carried and then never
 * rendered. That is the defect this file pins:
 *
 * 1. **the slot** — the row renders `SessionRowTime(updatedAt = row.updatedAt,
 *    now = now)`, in the palette's meta-cell position (the row's trailing edge).
 * 2. **the clock** — the label is measured against an injected `now`
 *    ([HomeSessionsColumn]'s parameter, threaded from `HomeScreen`'s ticking
 *    `homeNow`), never a wall-clock read inside the row; the Today / Yesterday /
 *    Older grouping uses the same value, so a session cannot sit in Today past
 *    midnight.
 * 3. **the bucket** — the same [SessionTimeLabel.relative] the palette's
 *    [PaletteRelativeTime] resolves, so the list and the palette can never round
 *    an age differently.
 *
 * The first two are wiring the JVM cannot exercise (Compose), so they are guarded
 * at source level in the style of [SessionDrawerFreshnessTest] /
 * [PaletteRelativeTimeTest]; the bucketing is behavioural.
 */
class HomeSessionRowTimeTest {

    // -------------------------------------------------------------- the bucket

    /** A `now` deliberately nowhere near the wall clock. */
    private val updatedAt = 1_700_000_000_000L

    private fun paletteLabel(updatedAt: Long, now: Long): String? =
        PaletteRelativeTime.label(
            updatedAt = updatedAt,
            now = now,
            justNow = "just now",
            minutesAgo = { count -> "$count minutes ago" },
            hoursAgo = { count -> "$count hours ago" },
            daysAgo = { count -> "$count days ago" },
        )

    @Test
    fun `the row's bucket is the palette's bucket`() {
        // The list and the palette resolve the same `updatedAt` through the same
        // bucketing, so the same session cannot read「5 minutes ago」in one and
        // 「1 hours ago」in the other. `SessionRowTime` renders exactly this.
        val ages = listOf(
            0L,
            59_000L,
            60_000L,
            5L * 60_000L + 1_000L,
            3_600_000L,
            23L * 3_600_000L,
            24L * 3_600_000L,
            9L * 24L * 3_600_000L + 5_000L,
        )
        for (age in ages) {
            val now = updatedAt + age
            val bucket = SessionTimeLabel.relative(updatedAt, now)!!
            val expected = when (bucket.unit) {
                SessionTimeLabel.Unit.JUST_NOW -> "just now"
                SessionTimeLabel.Unit.MINUTES -> "${bucket.count} minutes ago"
                SessionTimeLabel.Unit.HOURS -> "${bucket.count} hours ago"
                SessionTimeLabel.Unit.DAYS -> "${bucket.count} days ago"
            }

            assertEquals("age $age must read the same in both surfaces", expected, paletteLabel(updatedAt, now))
        }
    }

    @Test
    fun `the label follows the clock`() {
        // A row composed while its session was「just now」has to read its real age
        // once the host's clock moves — that is all the ticking `now` buys, and it
        // is what the device run saw the palette do and the list not do.
        assertEquals(
            SessionTimeLabel.Unit.JUST_NOW,
            SessionTimeLabel.relative(updatedAt, updatedAt + 30_000L)!!.unit,
        )
        assertEquals(
            SessionTimeLabel.Relative(SessionTimeLabel.Unit.MINUTES, 5),
            SessionTimeLabel.relative(updatedAt, updatedAt + 5L * 60_000L + 1_000L),
        )
    }

    @Test
    fun `an unknown timestamp renders no label`() {
        // The row's `SessionRowTime` returns before composing anything.
        assertNull(SessionTimeLabel.relative(0L, updatedAt))
        assertNull(SessionTimeLabel.relative(-1L, updatedAt))
        assertNull(paletteLabel(0L, updatedAt))
    }

    @Test
    fun `the row model keeps the server's updated time for the label`() {
        // The label has nothing to render if the model drops the field on the way
        // from `GET /session` to the row (`HomeSessions.rows` is where it could).
        val rows = HomeSessions.rows(
            listOf(
                OpenCodeClient.Session(
                    id = "ses_1",
                    title = "E2ECHK beta greeting",
                    directory = "/work/v28projA",
                    updatedAt = updatedAt,
                ),
            ),
        )

        assertEquals(updatedAt, rows.single().updatedAt)
        assertEquals("v28projA", rows.single().project)
    }

    // ------------------------------------------------------------ the wiring

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("missing $relative")

    private fun rows() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeSessionsList.kt")

    private fun home() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeScreen.kt")

    /** The file without its comment lines, so a doc mention is not a code read. */
    private fun code(text: String): String = text.lineSequence()
        .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") || it.trimStart().startsWith("/*") }
        .joinToString("\n")

    private fun bodyOf(text: String, signature: String, span: Int = 900): String {
        val at = text.indexOf(signature)
        assertTrue("missing $signature", at >= 0)
        return text.substring(at, minOf(text.length, at + span))
    }

    @Test
    fun `the home row renders a relative-time slot`() {
        val rows = rows()

        assertTrue(
            "the row must render the label its `updatedAt` describes",
            rows.contains("SessionRowTime(updatedAt = row.updatedAt, now = now)"),
        )
        // …through the shared bucketing, not a second implementation.
        val label = bodyOf(rows, "private fun SessionRowTime(updatedAt: Long, now: Long)")
        assertTrue(label.contains("SessionTimeLabel.relative(updatedAt, now)"))
        assertTrue(label.contains("R.string.oc_time_just_now"))
    }

    @Test
    fun `the row takes the host's clock instead of reading the wall clock`() {
        val rows = rows()

        assertTrue(
            "the row must take the clock as a parameter",
            bodyOf(rows, "private fun HomeSessionRow(").contains("now: Long,"),
        )
        assertTrue(
            "and the column must thread it to both row forms",
            rows.contains("HomeSessionRow(") && rows.contains("now = now,"),
        )
        // The whole file, not just the row: the column's own group bucketing used
        // to read the wall clock, which froze a session in Today past midnight.
        assertTrue(
            "no wall clock may be read in this file's code",
            !code(rows).contains("currentTimeMillis"),
        )
    }

    @Test
    fun `the group headers are measured against the same clock`() {
        val rows = rows()

        assertTrue(rows.contains("HomeSessions.sections(rows, now)"))
        assertTrue(!rows.contains("HomeSessions.sections(rows, System.currentTimeMillis())"))
    }

    @Test
    fun `home drives a clock that ticks while it is on screen`() {
        val home = home()

        assertTrue(
            "the column takes the screen's clock",
            home.contains("now = homeNow,"),
        )
        assertTrue(home.contains("var homeNow by remember { mutableStateOf(System.currentTimeMillis()) }"))
        assertTrue(
            "and it re-measures on a loop, not once per composition",
            home.contains("while (true) {"),
        )
        assertTrue(home.contains("homeNow = System.currentTimeMillis()"))
        assertTrue(home.contains("delay(HOME_CLOCK_TICK_MS)"))
    }
}
