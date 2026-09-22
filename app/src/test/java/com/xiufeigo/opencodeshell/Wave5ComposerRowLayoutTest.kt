package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.composer.ComposerSuggestions
import com.xiufeigo.opencodeshell.ui.session.composer.SuggestionRowText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * W5-G: a `/` (or `@`) candidate row must always keep its label, and the
 * description must be the half that gives way.
 *
 * The measured bug (`research/wave5_shots/v6-slash-tablet.png`): over a 75-row
 * `GET /command`, the two commands with a long description — `customize-opencode`
 * and `jira-SCB-Collector` — drew **no `/name` at all**, only the category icon and
 * the description. The label was the row's `weight(1f)` child, and a Compose `Row`
 * measures its weighted children last: the unweighted description took the whole
 * row first and left the label 0 dp.
 *
 * Two layers, because the JVM test source set has no Compose runtime:
 *
 * 1. [SuggestionRowText] — the strings the row's two `Text`s are handed: a label is
 *    never blank and both halves are bounded. This is the pure-logic half of the
 *    fix, and the reason an empty label can no longer reach the layout.
 * 2. a source guard on `MentionPopover.SuggestionRow` (the repo's established idiom
 *    for layout facts a JVM test cannot compose — cf. `Wave5ComposerWiringTest`,
 *    `ComposerM1_5Test`): the row has **exactly one weighted child, the
 *    description's slot**, and the label is drawn unweighted before it. That is
 *    what makes the label's width independent of the description's length, and it
 *    is the property the two `weight(…)` calls on the old row violated.
 */
class Wave5ComposerRowLayoutTest {

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            // Line endings are not what this suite is about: the literal fragments
            // below are written with `\n`, and git may hand us CRLF.
            ?.readText()
            ?.replace("\r\n", "\n")
            ?: error("missing $relative")

    /** The body of `MentionPopover.SuggestionRow` — the one row both menus draw. */
    private fun suggestionRow(): String {
        val popover = source(
            "src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/MentionPopover.kt",
        )
        val start = popover.indexOf("private fun SuggestionRow(")
        assertTrue("the popover must draw every candidate through SuggestionRow", start >= 0)
        // The function's closing brace is the first `}` at column 0 after it.
        val end = popover.indexOf("\n}\n", start)
        return popover.substring(start, if (end > start) end else popover.length)
    }

    // --------------------------------------------------- the row's layout guard

    @Test
    fun theLabelIsDrawnBeforeTheDescriptionAndCarriesNoModifierOfItsOwn() {
        val row = suggestionRow()
        val label = row.indexOf("text = SuggestionRowText.label(")
        val slot = row.indexOf("Box(")
        assertTrue("the label is drawn before the description slot", label in 0 until slot)
        // The label's `Text` call alone — up to the Spacer that follows it — must
        // carry no modifier: a weight there would put the label back into the pool
        // Compose measures *last*, and a long description would squeeze it to 0 dp
        // exactly as it did on the tablet.
        val labelCall = row.substring(label, row.indexOf("Spacer(", label))
        assertFalse(
            "the label must stay unweighted and unbounded (upstream `shrink-0`): $labelCall",
            labelCall.contains("Modifier."),
        )
    }

    @Test
    fun theDescriptionSlotIsTheRowsOnlyWeightedChild() {
        val row = suggestionRow()
        val weights = Regex("""Modifier\.weight\(""").findAll(row).toList()
        assertEquals(
            "exactly one child may be given the row's remainder: the description",
            1,
            weights.size,
        )
        // …and that one weight is the description's slot, which therefore starts
        // after the label and ends at the row's edge.
        assertTrue(
            "the weight belongs to the description's Box, not to the label",
            row.contains("Box(\n            modifier = Modifier.weight(1f),\n            contentAlignment = Alignment.CenterEnd,"),
        )
        // The slot draws the string the pure policy produced.
        assertTrue(row.contains("val description = SuggestionRowText.description("))
        assertTrue(row.contains("text = description"))
    }

    @Test
    fun bothHalvesOfTheRowAreSingleLineAndEllipsized() {
        val row = suggestionRow()
        assertEquals("the description truncates, it never wraps", 2, Regex("maxLines = 1").findAll(row).count())
        assertEquals(
            "both `Text`s end in `…` instead of pushing the other out",
            2,
            Regex("overflow = TextOverflow.Ellipsis").findAll(row).count(),
        )
    }

    // ------------------------------------------------------ pure text policy

    @Test
    fun aRowLabelIsNeverBlank() {
        assertEquals("/customize-opencode", SuggestionRowText.label("/customize-opencode", "命令"))
        assertEquals("/init", SuggestionRowText.label("  /init ", "命令"))
        // A blank server name falls back to the row's category, so no code path can
        // hand the layout a label it would measure at zero width.
        assertEquals("命令", SuggestionRowText.label("", "命令"))
        assertEquals("命令", SuggestionRowText.label("   ", "命令"))
        assertEquals("命令", SuggestionRowText.label("", " 命令 "))
        assertEquals(SuggestionRowText.UNKNOWN_LABEL, SuggestionRowText.label("", ""))
        for (junk in listOf("", " ", "\n", "\t ", "\u3000", " \u00a0 ")) {
            assertTrue(
                "blank label for ${junk.length}-char input",
                SuggestionRowText.label(junk, "").isNotBlank(),
            )
        }
    }

    @Test
    fun theDescriptionIsOptionalAndTrimmed() {
        assertNull(SuggestionRowText.description(null))
        assertNull(SuggestionRowText.description(""))
        assertNull(SuggestionRowText.description("   "))
        assertEquals(
            "Query Jira SCB ops tickets by week via REST API",
            SuggestionRowText.description("  Query Jira SCB ops tickets by week via REST API  "),
        )
    }

    @Test
    fun bothHalvesAreBounded() {
        assertEquals("abc", SuggestionRowText.clamp("abc", 3))
        assertEquals("abcd", SuggestionRowText.clamp("abcd", 4))
        // The cut keeps `max` characters and is *marked* by the ellipsis.
        assertEquals("abc\u2026", SuggestionRowText.clamp("abcd", 3))
        assertEquals("a\u2026", SuggestionRowText.clamp("abcd", 1))
        assertEquals("", SuggestionRowText.clamp("abcd", 0))
        assertEquals("", SuggestionRowText.clamp("abcd", -1))
        assertEquals(
            SuggestionRowText.LABEL_MAX_CHARS + 1,
            SuggestionRowText.label("x".repeat(5_000), "").length,
        )
        assertEquals(
            SuggestionRowText.DESCRIPTION_MAX_CHARS + 1,
            SuggestionRowText.description("x".repeat(50_000))!!.length,
        )
    }

    @Test
    fun aCutNeverSplitsASurrogatePair() {
        val emoji = "a\uD83D\uDE00b" // 'a' + U+1F600 + 'b'
        assertEquals("a\u2026", SuggestionRowText.clamp(emoji, 2))
        assertEquals("a\uD83D\uDE00\u2026", SuggestionRowText.clamp(emoji, 3))
        assertEquals(emoji, SuggestionRowText.clamp(emoji, 4))
    }

    // ---------------------------------------------- the two device offenders

    @Test
    fun theTwoTabletOffendersKeepTheirLabelAndABoundedDescription() {
        // The payload the tablet showed (`research/wave5_composer.md` §9): the data
        // was never the bug — the label was in the candidate list all along.
        val longList = List(60) {
            "Use ONLY when the user is editing or creating opencode's own configuration: " +
                "opencode.json, opencode.jsonc, files under .opencode/, or files under ~/.config/opencode/."
        }.joinToString(" ")
        val items = ComposerSuggestions.commandItems(
            query = "",
            commands = listOf(
                OpenCodeClient.CommandInfo(
                    name = "customize-opencode",
                    description = longList,
                    source = "skill",
                    template = "",
                ),
                OpenCodeClient.CommandInfo(
                    name = "jira-SCB-Collector",
                    description = "Query Jira SCB ops tickets by week via REST API, extract key fields " +
                        "(SOR link, issue type, SCB link, combined analysis, solution summary, linked " +
                        "tickets excluding SOR), and output a 6-column summary table. " + longList,
                    source = "skill",
                    template = "",
                ),
            ),
        )
        val labels = items.map { it.label }
        assertEquals(listOf("/model", "/agent", "/customize-opencode", "/jira-SCB-Collector"), labels)
        for (item in items.drop(2)) {
            // The label the row draws is the full `/name` — non-blank, unclamped.
            assertEquals(item.label, SuggestionRowText.label(item.label, "命令"))
            val description = SuggestionRowText.description(item.description)
            assertTrue("a description was present", description != null)
            assertTrue(
                "description stays inside the row's budget",
                description!!.length <= SuggestionRowText.DESCRIPTION_MAX_CHARS + 1,
            )
        }
    }
}
