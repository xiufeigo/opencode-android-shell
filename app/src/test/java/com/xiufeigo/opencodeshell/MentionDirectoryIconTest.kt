package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.composer.ComposerSuggestions
import com.xiufeigo.opencodeshell.ui.session.composer.ComposerSuggestions.Category
import com.xiufeigo.opencodeshell.ui.session.composer.MentionSources
import com.xiufeigo.opencodeshell.ui.session.composer.MentionText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The `@` menu's **directory** rows (device v15: `/home/xiufeigo/v11diff` — a
 * directory — and `/home/xiufeigo/v11diff/a.txt` both drew the document icon and
 * both said "File").
 *
 * Why they collapsed into one row: `/find/file` answers with bare paths for
 * **both** kinds (`v2/SessionTransport.searchFiles` — files *and* directories,
 * v1 marking a directory only with a trailing separator), so every hit lands in
 * [Category.FILE] and the category alone cannot tell them apart. The row's own
 * path shape is the only classification available ([MentionText.isDirectoryPath]),
 * and it is what picks the folder glyph — the same one upstream's
 * `ComposerSuggestionIcon` (`editor.tsx:904-913`) gives `kind === "reference"`.
 */
class MentionDirectoryIconTest {

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("missing $relative")

    private fun popover() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/MentionPopover.kt")

    // --------------------------------------------------------- the path shape

    @Test
    fun directoryPathsAreReadFromTheirShape() {
        // The v15 pair.
        assertTrue(MentionText.isDirectoryPath("/home/xiufeigo/v11diff"))
        assertFalse(MentionText.isDirectoryPath("/home/xiufeigo/v11diff/a.txt"))
        // v1's explicit marker and the v2 shapes.
        assertTrue(MentionText.isDirectoryPath("build/"))
        assertTrue(MentionText.isDirectoryPath("src\\main"))
        assertTrue(MentionText.isDirectoryPath("/"))
        assertTrue(MentionText.isDirectoryPath(".."))
        assertTrue(MentionText.isDirectoryPath("."))
        assertTrue(MentionText.isDirectoryPath("  /home/x/docs  "))
        // A dot in the last segment is an extension → file.
        assertFalse(MentionText.isDirectoryPath("README.md"))
        assertFalse(MentionText.isDirectoryPath("src/Main.kt"))
        assertFalse(MentionText.isDirectoryPath(".gitignore"))
        // Nothing to classify.
        assertFalse(MentionText.isDirectoryPath(""))
        assertFalse(MentionText.isDirectoryPath("   "))
        // Documented trade-off (same rule `classify` uses for the colours): an
        // extension-less file reads as a directory, a dotted directory as a file.
        assertTrue(MentionText.isDirectoryPath("Dockerfile"))
        assertFalse(MentionText.isDirectoryPath("release/v1.2"))
    }

    // ----------------------------------------------------------- the row kind

    @Test
    fun findFileRowsCarryTheKindTheirPathNames() {
        val items = ComposerSuggestions.mentionItems(
            query = "",
            agents = emptyList(),
            commands = emptyList(),
            skills = emptyList(),
            files = listOf(
                "/home/xiufeigo/v11diff",
                "/home/xiufeigo/v11diff/a.txt",
                "build/",
                "README.md",
            ),
        )
        assertEquals(
            listOf(
                "/home/xiufeigo/v11diff",
                "/home/xiufeigo/v11diff/a.txt",
                "build/",
                "README.md",
            ),
            items.map { it.label },
        )
        assertTrue("every `/find/file` hit is a file-category row", items.all { it.category == Category.FILE })
        assertEquals(
            "the directory rows are the ones without an extension (or with a trailing separator)",
            listOf(true, false, true, false),
            items.map { it.directory },
        )
        // The other categories are unaffected: an agent row never claims to be a
        // directory, and the label/target of the file rows is untouched.
        val withAgent = ComposerSuggestions.mentionItems(
            query = "",
            agents = listOf(
                OpenCodeClient.Agent(name = "build", description = "", mode = "subagent", native = true, hidden = false),
            ),
            commands = emptyList(),
            skills = emptyList(),
            files = listOf("/home/xiufeigo/v11diff"),
        )
        assertFalse(withAgent.first { it.category == Category.AGENT }.directory)
        assertEquals("/home/xiufeigo/v11diff", withAgent.first { it.category == Category.FILE }.label)
        assertEquals(null, withAgent.first { it.category == Category.FILE }.path)
    }

    @Test
    fun referenceRowsStillDrawTheFolder() {
        // F-1: a reference is `application/x-directory` upstream (`model.ts:159`),
        // so its category maps to the folder glyph on its own — the v15 fix must
        // not have replaced that mapping with the path heuristic.
        val popover = popover()
        assertTrue(
            popover.contains("ComposerSuggestions.Category.REFERENCE -> R.drawable.ic_oc_folder"),
        )
        // …and the file category splits by the row's kind.
        assertTrue(
            popover.contains(
                "ComposerSuggestions.Category.FILE ->\n" +
                    "        if (item.directory) R.drawable.ic_oc_folder else R.drawable.ic_oc_file",
            ),
        )
        // A reference row (label = bare name, target = path) carries no file kind.
        val items = ComposerSuggestions.mentionItems(
            query = "",
            agents = emptyList(),
            commands = emptyList(),
            skills = emptyList(),
            files = emptyList(),
            references = listOf(MentionSources.Reference(name = "docs", path = "/home/x/docs")),
        )
        val reference = items.single()
        assertEquals(Category.REFERENCE, reference.category)
        assertEquals("/home/x/docs", reference.path)
        assertFalse("the reference kind is the category's, not the path's", reference.directory)
    }

    // ------------------------------------------------------------ the drawing

    @Test
    fun theRowLabelSaysDirectoryAndIsTheContentDescription() {
        val popover = popover()
        // The label is resolved per row, so a directory row reads "Directory" in
        // the trailing column too (that column falls back to it).
        assertTrue(popover.contains("val category = stringResource(rowLabelRes(item))"))
        assertTrue(popover.contains("if (item.category == ComposerSuggestions.Category.FILE && item.directory) {"))
        assertTrue(popover.contains("R.string.oc_composer_mention_directory"))
        assertTrue("the icon announces the resolved label", popover.contains("contentDescription = category"))
        assertTrue("the description column keeps falling back to that label", popover.contains("text = description ?: category"))
        // The icon itself is dispatched from the row, not from the category alone.
        assertTrue(popover.contains("painter = painterResource(categoryIconRes(item))"))
        assertFalse("the category-only dispatch is gone", popover.contains("categoryIconRes(item.category)"))
    }

    @Test
    fun theDirectoryLabelIsLocalised() {
        fun value(dir: String): String =
            Regex("""<string name="oc_composer_mention_directory">(.*?)</string>""")
                .find(source("src/main/res/$dir/strings.xml"))
                ?.groupValues
                ?.get(1)
                ?: error("$dir missing oc_composer_mention_directory")

        assertEquals("Directory", value("values"))
        assertEquals("目录", value("values-zh-rCN"))
        assertEquals("目錄", value("values-zh-rTW"))
        // The two labels must stay distinguishable in every locale.
        for (dir in listOf("values", "values-zh-rCN", "values-zh-rTW")) {
            val file = Regex("""<string name="oc_composer_mention_file">(.*?)</string>""")
                .find(source("src/main/res/$dir/strings.xml"))
                ?.groupValues
                ?.get(1)
            assertNotEquals("$dir: Directory and File must not read the same", file, value(dir))
        }
    }
}
