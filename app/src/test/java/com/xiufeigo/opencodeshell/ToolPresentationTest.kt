package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.timeline.ToolPresentation
import com.xiufeigo.opencodeshell.ui.session.timeline.ToolPresentation.LineKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Compose timeline reuses the View-era tool-card logic
 * (`ChatView.titleForTool` / `subtitleForTool` / `toolBodyText`); the titles
 * follow the authoritative `research/02` §3.8 literals instead (M1-review ruling
 * B). These tests pin the ported mapping so a refactor cannot silently change
 * tool titles, subtitles or the diff/shell body layout.
 */
class ToolPresentationTest {

    private fun toolPart(
        name: String?,
        status: String? = "completed",
        input: String? = null,
        output: String? = null,
        error: String? = null,
    ) = ChatStore.UiPart(
        id = "part-1",
        type = "tool",
        toolName = name,
        toolStatus = status,
        toolInput = input,
        toolOutput = output,
        toolError = error,
    )

    // ------------------------------------------------------------------ title

    @Test
    fun titlesFollowTheUpstreamToolMapping() {
        assertEquals("Shell", ToolPresentation.title("bash"))
        assertEquals("Shell", ToolPresentation.title("shell"))
        assertEquals("Edit", ToolPresentation.title("edit"))
        assertEquals("Write", ToolPresentation.title("write"))
        assertEquals("Read", ToolPresentation.title("read"))
        // §3.8 / `_i18n ui.tool.*` — not the View shell's Find/Search/Fetch.
        assertEquals("Glob", ToolPresentation.title("glob"))
        assertEquals("Grep", ToolPresentation.title("grep"))
        assertEquals("Task", ToolPresentation.title("task"))
        assertEquals("Webfetch", ToolPresentation.title("webfetch"))
        assertEquals("To-dos", ToolPresentation.title("todowrite"))
    }

    /**
     * The Compose card renders the *resource*, so `strings.xml` — not only the
     * Java-side literal — must carry the §3.8 literals. (The ids keep their
     * View-era names: `ChatView.titleForTool` references the same three.)
     */
    @Test
    fun theTitleResourcesCarryTheAuthoritativeLiterals() {
        val xml = File("src/main/res/values/strings.xml")
            .takeIf { it.isFile }
            ?: File("app/src/main/res/values/strings.xml")
        val text = xml.readText()
        assertTrue(text.contains("<string name=\"oc_tool_shell\">Shell</string>"))
        assertTrue(text.contains("<string name=\"oc_tool_edit\">Edit</string>"))
        assertTrue(text.contains("<string name=\"oc_tool_write\">Write</string>"))
        assertTrue(text.contains("<string name=\"oc_tool_read\">Read</string>"))
        assertTrue(text.contains("<string name=\"oc_tool_find\">Glob</string>"))
        assertTrue(text.contains("<string name=\"oc_tool_search\">Grep</string>"))
        assertTrue(text.contains("<string name=\"oc_tool_task\">Task</string>"))
        assertTrue(text.contains("<string name=\"oc_tool_fetch\">Webfetch</string>"))
        assertTrue(text.contains("<string name=\"oc_tool_todos\">To-dos</string>"))
    }

    @Test
    fun unknownToolFallsBackToCapitalizedNameThenGenericLabel() {
        assertEquals("Patch", ToolPresentation.title("patch"))
        assertEquals(ToolPresentation.GENERIC_LABEL, ToolPresentation.title(null))
        assertEquals(ToolPresentation.GENERIC_LABEL, ToolPresentation.title(""))
    }

    @Test
    fun knownToolsMapToStringResourcesAndUnknownOnesDoNot() {
        assertEquals(R.string.oc_tool_shell, ToolPresentation.titleRes("bash"))
        assertEquals(R.string.oc_tool_todos, ToolPresentation.titleRes("todowrite"))
        assertNull(ToolPresentation.titleRes("patch"))
        assertNull(ToolPresentation.titleRes(null))
    }

    @Test
    fun iconsFollowTheDrawableMapping() {
        assertEquals(ToolPresentation.Icon.SHELL, ToolPresentation.icon("bash"))
        assertEquals(ToolPresentation.Icon.CODE, ToolPresentation.icon("apply_patch"))
        assertEquals(ToolPresentation.Icon.GLASSES, ToolPresentation.icon("read"))
        assertEquals(ToolPresentation.Icon.SEARCH, ToolPresentation.icon("grep"))
        assertEquals(ToolPresentation.Icon.GLOBE, ToolPresentation.icon("websearch"))
        assertEquals(ToolPresentation.Icon.TASK, ToolPresentation.icon("task"))
        assertEquals(ToolPresentation.Icon.CHECK, ToolPresentation.icon("todowrite"))
        assertEquals(ToolPresentation.Icon.FILE, ToolPresentation.icon("unknown"))
    }

    // --------------------------------------------------------------- subtitle

    @Test
    fun subtitleUsesTheFirstMeaningfulInputKeyInPriorityOrder() {
        assertEquals(
            "ls -la",
            ToolPresentation.subtitle(toolPart("bash", input = """{"command":"ls -la","pattern":"x"}""")),
        )
        assertEquals(
            "src/Main.kt",
            ToolPresentation.subtitle(toolPart("read", input = """{"pattern":"","filePath":"src/Main.kt"}""")),
        )
        assertEquals(
            "TODO",
            ToolPresentation.subtitle(toolPart("grep", input = """{"query":"TODO"}""")),
        )
    }

    @Test
    fun subtitleIsNullWithoutInput() {
        assertNull(ToolPresentation.subtitle(toolPart("bash")))
        assertNull(ToolPresentation.subtitle(toolPart("bash", input = "not json")))
        assertNull(ToolPresentation.subtitle(toolPart("bash", input = """{"other":"value"}""")))
    }

    @Test
    fun subtitleShowsTheErrorSummaryOnFailure() {
        val part = toolPart(
            "bash",
            status = "error",
            input = """{"command":"ls"}""",
            error = "\nboom: no such file\nsecond line",
        )
        assertEquals("boom: no such file", ToolPresentation.subtitle(part))
    }

    @Test
    fun subtitleFallsBackToTheErrorLabelOnAFailureWithoutText() {
        val part = toolPart("bash", status = "error")
        assertEquals("Echec", ToolPresentation.subtitle(part, errorLabel = "Echec"))
    }

    @Test
    fun subtitleIsCappedAtTheUiLimit() {
        val long = "x".repeat(400)
        val subtitle = ToolPresentation.subtitle(toolPart("bash", input = """{"command":"$long"}"""))
        assertEquals(ToolPresentation.SUBTITLE_MAX, subtitle?.length)
    }

    // ------------------------------------------------------------------- body

    @Test
    fun bashBodyIsCommandThenBlankLineThenOutput() {
        val lines = ToolPresentation.bodyLines(
            toolPart("bash", input = """{"command":"ls -la"}""", output = "a\r\nb\r\n"),
        )
        assertEquals(
            listOf(
                Touch(LineKind.COMMAND, "$ ls -la"),
                Touch(LineKind.PLAIN, ""),
                Touch(LineKind.PLAIN, "a"),
                Touch(LineKind.PLAIN, "b"),
            ),
            lines.map { Touch(it.kind, it.text) },
        )
    }

    @Test
    fun bashBodyWithoutOutputIsJustTheCommandLine() {
        val lines = ToolPresentation.bodyLines(toolPart("bash", input = """{"command":"pwd"}"""))
        assertEquals(listOf(Touch(LineKind.COMMAND, "$ pwd")), lines.map { Touch(it.kind, it.text) })
    }

    @Test
    fun editBodyIsADiffOfOldAndNewStrings() {
        val lines = ToolPresentation.bodyLines(
            toolPart(
                "edit",
                input = """{"filePath":"a.kt","oldString":"old line","newString":"new line\nsecond"}""",
            ),
        )
        assertEquals(
            listOf(
                Touch(LineKind.DELETE, "- old line"),
                Touch(LineKind.ADD, "+ new line"),
                Touch(LineKind.ADD, "+ second"),
            ),
            lines.map { Touch(it.kind, it.text) },
        )
    }

    @Test
    fun anEmptyDiffSideContributesNoLines() {
        val lines = ToolPresentation.bodyLines(
            toolPart("edit", input = """{"oldString":"","newString":"only new"}"""),
        )
        assertEquals(listOf(Touch(LineKind.ADD, "+ only new")), lines.map { Touch(it.kind, it.text) })
    }

    @Test
    fun writeBodyUsesContentWhenThereIsNoNewString() {
        val lines = ToolPresentation.bodyLines(
            toolPart("write", input = """{"filePath":"a.kt","content":"line one"}"""),
        )
        assertEquals(listOf(Touch(LineKind.ADD, "+ line one")), lines.map { Touch(it.kind, it.text) })
    }

    @Test
    fun otherToolsPrettyPrintInputThenAppendOutput() {
        val lines = ToolPresentation.bodyLines(
            toolPart("read", input = """{"filePath":"a.kt"}""", output = "hello"),
        )
        assertEquals(LineKind.JSON, lines.first().kind)
        assertTrue(lines.map { it.text }.any { it.contains("filePath") && it.contains("a.kt") })
        assertTrue(lines.map { it.text }.contains("hello"))
        // Blank separator between the JSON input and the raw output.
        assertTrue(lines.map { it.text }.contains(""))
    }

    @Test
    fun bodyIsEmptyForAToolWithNoPayload() {
        assertTrue(ToolPresentation.bodyLines(toolPart("read")).isEmpty())
    }

    // ------------------------------------------------------------------ json

    /**
     * `toString(2)` pretty-prints on Android but is compact on the JVM reference
     * implementation (`org.json:json`), so assert round-trippability rather than
     * a byte-exact layout.
     */
    @Test
    fun prettyNormalizesJsonAndKeepsUnparseableText() {
        val prettyObject = ToolPresentation.pretty("""{"a":1}""")
        assertEquals(1, ToolPresentation.parseObject(prettyObject)?.optInt("a"))

        val prettyArray = ToolPresentation.pretty("[1,2]")
        assertEquals(2, org.json.JSONArray(prettyArray).length())

        assertEquals("not json", ToolPresentation.pretty("not json"))
    }

    @Test
    fun splitLinesDropsTheTrailingEmptyLineAndIsEmptyForAnEmptyString() {
        assertEquals(emptyList<String>(), ToolPresentation.splitLines(""))
        assertEquals(listOf("a"), ToolPresentation.splitLines("a\n"))
        assertEquals(listOf("a", "b"), ToolPresentation.splitLines("a\nb"))
        assertEquals(listOf(""), ToolPresentation.splitLines("\n"))
    }

    @Test
    fun editLikeDetectionMatchesTheDiffRenderingBranch() {
        assertTrue(ToolPresentation.isEditLike("edit"))
        assertTrue(ToolPresentation.isEditLike("write"))
        assertTrue(ToolPresentation.isEditLike("apply_patch"))
        assertEquals(false, ToolPresentation.isEditLike("bash"))
        assertEquals(false, ToolPresentation.isEditLike(null))
    }

    /** Small value holder so the assertions read line-by-line. */
    private data class Touch(val kind: LineKind, val text: String)
}
