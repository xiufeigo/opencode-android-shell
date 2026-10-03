package com.xiufeigo.opencodeshell

import androidx.compose.ui.unit.dp
import com.xiufeigo.opencodeshell.ui.home.HomeRowLayout
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 极窄 Home rows: the sub-250 dp defect behind [HomeRowLayout].
 *
 * Both Home lists draw a fixed piece of furniture around one flexible text cell,
 * and both used the **desktop** numbers only:
 *
 * ```
 * session row: pl-3 (12) + pr-10 (40, the archive slot) + avatar slot 24
 *              + 2 × gap 8 + the time cell (≈80 dp for "23 minutes ago")
 *              = 172 dp before the title gets a pixel
 * project row: px-1.5 (6) + 2 + avatar 18 + 4 × gap 8 + ⋯ ✎ ✕ (84)
 *              = 142 dp before the project name gets a pixel
 * ```
 *
 * A Compose `Row` measures its unweighted children (the time cell, the icon
 * buttons) **first** and gives the weighted ones what is left, so below those
 * widths the text cell was the half squeezed to nothing — the row rendered as
 * avatar + time, or avatar + three icons, with the title gone and the time label
 * itself clipping mid-word.
 *
 * The fix is a row-width floor ([HomeRowLayout.narrow]) and the compact shape
 * behind it: upstream's own mobile `pe-3` instead of the `md:pe-10` archive slot
 * (`home/sessions/view.tsx:597-601`), a capped, ellipsizing time cell, and the
 * two secondary halves (the session row's project label, the project row's
 * duplicated ✎/✕) giving way.
 *
 * The project has no JVM-renderable Compose surface (no `ui-test-junit4`), so the
 * decision is pinned as the **pure arithmetic** the rows call — with the real
 * widths those rows measure — plus source facts that both rows really call it
 * (the house style: [SettingsRowStackedTest], [HomeSessionRowTimeTest]).
 */
class HomeRowNarrowTest {

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()?.replace("\r\n", "\n")
            ?: error("missing $relative")

    private fun sessions() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeSessionsList.kt")

    private fun projects() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeProjectsList.kt")

    private fun bodyOf(text: String, signature: String, span: Int = 1_400): String {
        val at = text.indexOf(signature)
        assertTrue("missing $signature", at >= 0)
        return text.substring(at, minOf(text.length, at + span))
    }

    /**
     * A window bounded by the **next declaration** rather than a character count.
     * The editor composable's own span had to be raised once already for this
     * reason (v43's commit chain) and outgrew its number again in v77's
     * entry-sequence fix, which silently cut the field's padding out of the
     * window. The assertions are about the composable, not about a length.
     */
    private fun windowOf(text: String, from: String, to: String): String {
        val start = text.indexOf(from)
        assertTrue("missing $from", start >= 0)
        val end = text.indexOf(to, start + from.length)
        assertTrue("missing $to after $from", end > start)
        return text.substring(start, end)
    }

    /** The label a real row carries: "23 minutes ago" at 12 sp is ≈ 80 dp. */
    private val timeCell = 80.dp

    // ------------------------------------------------------------- the decision

    @Test
    fun `the floor is the row's own 250 dp`() {
        assertEquals("the documented floor", 250f, HomeRowLayout.NARROW_WIDTH.value)
        assertTrue("one dp under the floor is narrow", HomeRowLayout.narrow(249.dp))
        assertTrue(HomeRowLayout.narrow(200.dp))
        // A 320 dp phone insets the card by 8 dp a side and the content by 12, so
        // its rows are 280 dp — the desktop shape, untouched.
        assertFalse("a phone's 280 dp row keeps the desktop shape", HomeRowLayout.narrow(280.dp))
        assertFalse("the floor itself is the last desktop width", HomeRowLayout.narrow(250.dp))
        // No measurement, no behaviour change (a collapsed host, a preview).
        assertFalse(HomeRowLayout.narrow(0.dp))
        assertFalse(HomeRowLayout.narrow((-1).dp))
    }

    // ---------------------------------------------------------- session row math

    @Test
    fun `the compact shape gives the title back its width`() {
        // The defect, in the arithmetic: at 200 dp the desktop shape leaves the
        // labels cell 28 dp and the title 0.7 × 28 ≈ 20 dp — one ellipsized glyph
        // beside an 80 dp time cell that kept its full width.
        assertEquals(28.dp, HomeRowLayout.sessionLabelsWidth(200.dp, narrow = false, timeWidth = timeCell))
        assertTrue(
            "the desktop shape starves the title",
            HomeRowLayout.sessionTitleWidth(200.dp, narrow = false, timeWidth = timeCell) < 25.dp,
        )

        // The compact shape: the archive slot drops to 12 dp, the time cell is
        // capped at 64 and the project label has given way, so the title owns
        // everything the row has left.
        assertEquals(72.dp, HomeRowLayout.sessionTitleWidth(200.dp, narrow = true, timeWidth = timeCell))
        assertFalse("the project label gives way at the floor", HomeRowLayout.showProjectLabel(narrow = true))
        assertTrue("and stays at every wider row", HomeRowLayout.showProjectLabel(narrow = false))
    }

    @Test
    fun `a 240 dp row still fits its whole time label`() {
        // The compact shape's first job is not to break the row that was merely
        // cramped: 64 dp is the cap, not the width — an 80 dp label is capped to
        // it and the labels cell keeps 240 − (12 + 12 + 24 + 16 + 64) = 112 dp.
        assertEquals(64.dp, HomeRowLayout.timeMax(narrow = true))
        assertEquals(112.dp, HomeRowLayout.sessionLabelsWidth(240.dp, narrow = true, timeWidth = timeCell))
        // A label that fits the cap is untouched, in either shape.
        assertEquals(
            HomeRowLayout.sessionLabelsWidth(240.dp, narrow = true, timeWidth = 48.dp),
            240.dp - 12.dp - 12.dp - 24.dp - 16.dp - 48.dp,
        )
        assertTrue(
            "the wide cap is the safety net, not the rule",
            HomeRowLayout.timeMax(narrow = false) > HomeRowLayout.timeMax(narrow = true),
        )
    }

    @Test
    fun `the end padding is upstream's own mobile half`() {
        // `py-3 ps-1.5 pe-3 md:ps-3 md:pe-10` (`view.tsx:597-601`): the 40 dp
        // archive slot is the `md:` value, so the sub-md row is not an invention.
        assertEquals(40.dp, HomeRowLayout.sessionEnd(narrow = false))
        assertEquals(12.dp, HomeRowLayout.sessionEnd(narrow = true))
        assertEquals(12.dp, HomeRowLayout.SESSION_START)
        assertEquals(24.dp, HomeRowLayout.SESSION_LEADING)
    }

    // ---------------------------------------------------------- project row math

    @Test
    fun `the project name keeps its room when the icons give way`() {
        // 200 − (6 + 2 + 18 + 4 × 8 + 3 × 28) = 58 dp of name at the desktop
        // shape — with three 28 dp buttons it cannot fit.
        assertEquals(58.dp, HomeRowLayout.projectNameWidth(200.dp, narrow = false))
        // 200 − (6 + 2 + 18 + 2 × 8 + 28) = 130 dp once the ✎ and the ✕ — both
        // rows of the ⋯ menu — are gone.
        assertEquals(130.dp, HomeRowLayout.projectNameWidth(200.dp, narrow = true))
        assertFalse(HomeRowLayout.showProjectActions(narrow = true))
        assertTrue(HomeRowLayout.showProjectActions(narrow = false))
        // The desktop column is a fixed 280 dp: never narrow, so the two icon
        // buttons stay exactly where upstream puts them.
        assertEquals(138.dp, HomeRowLayout.projectNameWidth(280.dp, narrow = false))
    }

    // --------------------------------------------------------------- the wiring

    @Test
    fun `the sessions column measures once and threads the shape down`() {
        val body = sessions()
        // The column *is* the row's width (no horizontal padding between them), so
        // one measurement at its root replaces a per-row one.
        assertTrue(body.contains(".fillMaxWidth()\n                .weight(1f)"))
        assertTrue(body.contains("val narrow = HomeRowLayout.narrow(maxWidth)"))
        // Threaded to the rows and to the search panel's rows.
        assertTrue(body.contains("narrow = narrow,"))
        // …and the row really uses the policy rather than a second set of numbers.
        val row = bodyOf(body, "private fun HomeSessionRow(", 7_500)
        assertTrue(row.contains("HomeRowLayout.sessionEnd(narrow)"))
        assertTrue(row.contains("Modifier.widthIn(max = HomeRowLayout.timeMax(narrow))"))
        assertTrue(row.contains("HomeRowLayout.TITLE_WEIGHT"))
        // The project label is the row's own decision at the floor, not a host's.
        assertTrue(body.contains("HomeRowLayout.showProjectLabel(narrow)"))
        // The editor and the skeleton take the same shape, so neither moves when
        // the real row lands. The editor's window is bounded by the next
        // declaration: its span had already been raised once for v43's commit
        // chain, and v77's entry-sequence fix outgrew the number again.
        assertTrue(
            windowOf(body, "private fun HomeSessionRenameEditor(", "\ninternal fun homeSessionItemIndex(")
                .contains("HomeRowLayout.sessionEnd(narrow)"),
        )
        assertTrue(bodyOf(body, "private fun HomeSessionSkeleton(").contains("HomeRowLayout.sessionEnd(narrow)"))
        // The search panel is the narrowest place a session row is drawn: it is
        // inset 18/24 dp on top of the row's own paddings.
        assertTrue(bodyOf(body, "private fun SearchResultRow(", 2_000).contains("HomeRowLayout.timeMax(narrow)"))
    }

    @Test
    fun `the project row measures itself and drops its duplicated icons`() {
        val body = projects()
        // Self-measuring: the desktop column is a fixed 280 dp and the phone
        // dropdown is the window width, so only the row knows its own room.
        assertTrue(body.contains("BoxWithConstraints {"))
        assertTrue(body.contains("val narrow = HomeRowLayout.narrow(maxWidth)"))
        assertTrue(body.contains("val showActions = HomeRowLayout.showProjectActions(narrow)"))
        assertTrue(body.contains("if (showActions) {"))
        // The ⋯ stays outside the gate — it is the only visible menu trigger.
        val row = bodyOf(body, "private fun ProjectRow(", 6_000)
        val dots = row.indexOf("onClick = onOpenMenu")
        val gate = row.indexOf("if (showActions) {")
        assertTrue("the ⋯ is upstream's own trigger", dots >= 0)
        assertTrue("the ⋯ must not be gated away", gate > dots)
    }

    @Test
    fun `every one-line row label ellipsizes instead of hard-clipping`() {
        val rows = sessions()
        for (marker in listOf(
            "private fun SessionRowTime(",
            "private fun HomeGroupHeader(",
            "internal fun NeutralButton(",
        )) {
            assertTrue(
                "$marker must ellipsize its one line",
                bodyOf(rows, marker).contains("overflow = TextOverflow.Ellipsis"),
            )
        }
        for (marker in listOf(
            "private fun AddProjectRow(",
            "private fun RecentlyClosedSection(",
        )) {
            assertTrue(
                "$marker must ellipsize its one line",
                bodyOf(projects(), marker).contains("overflow = TextOverflow.Ellipsis"),
            )
        }
        // The group header also reserves the floating ✎'s slot, so its title
        // cannot run under the button on the first line.
        assertTrue(
            bodyOf(rows, "private fun HomeGroupHeader(")
                .contains(".padding(start = HomeRowLayout.SESSION_START, end = HomeRowLayout.SESSION_END)"),
        )
    }
}
