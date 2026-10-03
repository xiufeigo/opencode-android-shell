package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.composer.ComposerSuggestions
import com.xiufeigo.opencodeshell.ui.session.composer.MentionText
import com.xiufeigo.opencodeshell.ui.session.composer.SuggestionRowLayout
import com.xiufeigo.opencodeshell.ui.session.composer.SuggestionRowText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v16: the `@` menu's file rows must keep their "Directory" / "File" tag visible
 * next to a long absolute path.
 *
 * Measured (`research/v16_verify/misc/v16_mention_dir_file.png`): every
 * `/find/file` row drew the category icon and the path only. The tag was in the
 * a11y tree (`Icon.contentDescription`) and out of the picture, because the row's
 * trailing column — where the tag is drawn (`description ?: category`) — was the
 * `weight(1f)` child while the label (the whole path) was unweighted: a Compose
 * `Row` measures its weighted children last, so the path took the entire row and
 * the tag got 0 dp. The W5-G fix (`Wave5ComposerRowLayoutTest`) is what put the
 * path in the unweighted slot — right for a `/name`, wrong for a path.
 *
 * The fix is [SuggestionRowLayout]: the label is the flexible half exactly on the
 * rows whose trailing column is the category tag (no server description — every
 * file/directory row), so the tag keeps its natural width and the path ellipsizes
 * instead. This suite pins both halves of that: the pure policy, the row's source
 * (the repo's idiom for layout facts the JVM cannot compose), and the tag's own
 * width budget, which is what keeps the row from bursting on a phone.
 */
class MentionRowTagVisibilityTest {

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?.replace("\r\n", "\n")
            ?: error("missing $relative")

    /** The body of `MentionPopover.SuggestionRow`, whitespace-folded. */
    private fun row(): String {
        val popover = source(
            "src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/MentionPopover.kt",
        )
        val start = popover.indexOf("private fun SuggestionRow(")
        assertTrue("the popover must draw every candidate through SuggestionRow", start >= 0)
        val end = popover.indexOf("\n}\n", start)
        return popover.substring(start, if (end > start) end else popover.length)
            .replace(Regex("\\s+"), " ")
    }

    // ----------------------------------------------------------- the row data

    /** The two shapes the `@` menu mixes (`v16_mention_dir_file.png`). */
    private val paths = listOf(
        "/mnt/c/sensorsdata/main/program/opencode-android-shell/app/src/main",
        "/mnt/c/sensorsdata/main/program/opencode-android-shell/gradle.properties",
    )

    @Test
    fun aFileRowCarriesNoDescriptionSoItsTrailingColumnIsTheCategoryTag() {
        val items = ComposerSuggestions.mentionItems(
            query = "",
            agents = emptyList(),
            commands = emptyList(),
            skills = emptyList(),
            files = paths,
        )
        assertEquals(paths, items.map { it.label })
        assertEquals(
            "v15 keeps the kind the path shape names",
            listOf(true, false),
            items.map { it.directory },
        )
        for (item in items) {
            // `SuggestionRow` reads the description through the pure policy; null is
            // exactly the case that makes the trailing column fall back to the tag.
            assertNull(
                "a file row has no server description, so the tag is what it draws",
                SuggestionRowText.description(item.description),
            )
        }
    }

    // -------------------------------------------------------- the pure policy

    @Test
    fun aTagRowMakesThePathTheHalfThatGivesWay() {
        assertEquals(
            SuggestionRowLayout.Flexible.LABEL,
            SuggestionRowLayout.flexible(hasDescription = false),
        )
    }

    @Test
    fun theTwoBranchesAreExhaustiveAndMutuallyExclusive() {
        val both = listOf(true, false).map { SuggestionRowLayout.flexible(it) }
        assertEquals(
            "exactly one half is flexible per row",
            listOf(SuggestionRowLayout.Flexible.TRAILING, SuggestionRowLayout.Flexible.LABEL),
            both,
        )
    }

    // ----------------------------------------------------- the row's source pin

    @Test
    fun theTagIsDrawnUnweightedSoThePathCannotSqueezeItOut() {
        val row = row()
        assertTrue(
            "the trailing slot is unweighted while it draws the tag, and weighted while it " +
                "draws a description: $row",
            row.contains(
                "modifier = if (flexible == SuggestionRowLayout.Flexible.TRAILING) { " +
                    "Modifier.weight(1f) } else { Modifier }",
            ),
        )
        assertTrue(
            "the label takes the remainder on exactly the tag rows",
            row.contains(
                "modifier = if (flexible == SuggestionRowLayout.Flexible.LABEL) { " +
                    "Modifier.weight(1f) } else { Modifier }",
            ),
        )
        // The tag slot keeps the row's end alignment and its own natural width: no
        // fixed/fractional width on the Box that wraps the tag's `Text`, so the tag
        // is sized by the string alone.
        val slot = row.substring(row.indexOf("Box("), row.indexOf("text = description ?: category"))
        assertTrue(
            "the tag's slot is sized by its text, not by a width rule: $slot",
            slot.contains("contentAlignment = Alignment.CenterEnd") &&
                !slot.contains("widthIn(") &&
                !slot.contains("fillMaxWidth(") &&
                !slot.contains(".width("),
        )
        // The path ellipsizes instead of pushing the tag out.
        assertTrue(
            "the path truncates with `…`",
            row.contains("text = SuggestionRowText.label(item.label, category)") &&
                row.contains("overflow = TextOverflow.Ellipsis"),
        )
    }

    // ------------------------------------------------------- the width budget

    /**
     * The tag is the only *protected* column of the row, so its width is the whole
     * fixed overhead: the icon (16 dp) + the two 8 dp gaps + the row's 16 dp of
     * padding + the tag. This pins the tag to a budget a phone row can afford — at
     * 360 dp the path keeps well over half the row — for **every** locale the shell
     * ships, since the tag is a resource string and not server text.
     */
    @Test
    fun everyLocaleKeepsTheTagInsideItsWidthBudget() {
        val keys = listOf(
            "oc_composer_mention_directory",
            "oc_composer_mention_file",
            "oc_composer_mention_agent",
            "oc_composer_mention_command",
            "oc_composer_mention_skill",
            "oc_composer_mention_reference",
            "oc_composer_mention_resource",
        )
        val resDir = listOf(File("src/main/res"), File("app/src/main/res"))
            .firstOrNull { it.isDirectory }
            ?: error("missing src/main/res")
        val tables = resDir.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("values") && File(it, "strings.xml").isFile }
            ?.sortedBy { it.name }
            .orEmpty()
        val default = File(resDir, "values/strings.xml").readText().replace("\r\n", "\n")
        assertTrue("the default string table is the fallback every locale reads", tables.size >= 1)
        for (table in tables) {
            val text = File(table, "strings.xml").readText().replace("\r\n", "\n")
            for (key in keys) {
                // A locale that does not translate a key falls back to the default
                // table, so that is what an untranslated tag would draw.
                val tag = value(text, key) ?: value(default, key)
                    ?: error("$key is missing from values/strings.xml")
                assertTrue(
                    "${table.name}/$key = \"$tag\" is wider than the row's tag budget",
                    tagWidthDp(tag) <= TAG_BUDGET_DP,
                )
            }
        }
    }

    /** The `<string name="key">value</string>` body, or null when the table has no such key. */
    private fun value(stringsXml: String, key: String): String? =
        Regex("""<string name="$key"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .find(stringsXml)
            ?.groupValues
            ?.get(1)

    /**
     * The tag's width in dp at the row's 12 sp: one full-width character is one em
     * (12 dp), a Latin one averages ~0.6 em. An estimate, not a measurement — which
     * is why the budget below is generous.
     */
    private fun tagWidthDp(tag: String): Double =
        tag.sumOf { if (it.code > 0x2000) 1.0 else 0.6 } * TAG_FONT_SP

    private companion object {
        /** The tag `Text`'s size (`MentionPopover.SuggestionRow`). */
        const val TAG_FONT_SP = 12.0

        /**
         * A phone row is 360 dp wide; the row's other fixed parts are 16 dp of
         * padding, a 16 dp icon and two 8 dp gaps, so a 120 dp tag still leaves the
         * path ~192 dp — over half the row.
         */
        const val TAG_BUDGET_DP = 120.0
    }

    @Test
    fun theBudgetIsMetByTheMeasuredRows() {
        // The two tags the v16 screenshot lost, in the locales that were on screen.
        assertEquals(9 * 0.6 * 12.0, tagWidthDp("Directory"), 0.001)
        assertEquals(4 * 0.6 * 12.0, tagWidthDp("File"), 0.001)
        assertEquals(2 * 1.0 * 12.0, tagWidthDp("目录"), 0.001)
        assertTrue(tagWidthDp("MCP resource") <= TAG_BUDGET_DP)
    }

    // ------------------------------------------------- the menu the row serves

    @Test
    fun aDirectoryPathIsStillDetectedAsADirectory() {
        // v15's split, which is what makes the tag read "Directory" and not "File".
        assertTrue(MentionText.isDirectoryPath(paths[0]))
        assertFalse(MentionText.isDirectoryPath(paths[1]))
    }
}
