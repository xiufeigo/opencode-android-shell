package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.timeline.ToolModels
import com.xiufeigo.opencodeshell.ui.session.timeline.ToolPresentation
import com.xiufeigo.opencodeshell.ui.session.timeline.ToolPresentation.LineKind
import com.xiufeigo.opencodeshell.ui.session.timeline.ToolText
import com.xiufeigo.opencodeshell.ui.session.timeline.agentSlot
import com.xiufeigo.opencodeshell.ui.session.timeline.toolRendererKind
import com.xiufeigo.opencodeshell.ui.session.timeline.ToolRendererKind
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WAVE 2.2b: hunk-based edit diff, write-as-text, ANSI-stripped shell output,
 * `ToolErrorCard` head/detail, error-JSON unwrap, the dedicated tool models
 * (read/websearch/skill/subagent/question/diagnostics/compaction/nav chips)
 * and the renderer dispatch.
 *
 * Existing files are untouched per the wave contract (ToolPresentationTest,
 * ToolCardPolicyTest, TimelineGroupingTest, PatchBodyLinesTest); everything
 * below covers only the new pure functions. Two pre-existing behaviours these
 * tests *change the expectation of* are noted inline:
 * - `editBodyIsADiffOfOldAndNewStrings` (old file) still asserts the flat
 *   `- old`/`+ new` pair via `diffLines` — the card now renders
 *   `hunkDiffLines` instead (kept side by side, see below);
 * - shell output is now ANSI-stripped (was raw).
 */
class Wave22bToolFidelityTest {

    private fun toolPart(
        name: String?,
        status: String? = "completed",
        input: String? = null,
        output: String? = null,
        error: String? = null,
        type: String = "tool",
        text: String = "",
    ) = ChatStore.UiPart(
        id = "p22b_${name}_${input?.hashCode()}_${output?.hashCode()}",
        type = type,
        text = text,
        toolName = name,
        toolStatus = status,
        toolInput = input,
        toolOutput = output,
        toolError = error,
    )

    private fun kinds(lines: List<ToolPresentation.BodyLine>) = lines.map { it.kind to it.text }

    // ------------------------------------------------------- A3-9 hunk diff

    @Test
    fun hunkDiffGroupsAOneLineChangeWithContext() {
        // 10 unchanged lines, one changed in the middle: a single hunk with
        // 4 lines of context each side (jsdiff default).
        val old = (1..10).joinToString("\n") { "line $it" }
        val new = (1..10).joinToString("\n") { if (it == 6) "line SIX" else "line $it" }
        val lines = ToolPresentation.hunkDiffLines(old, new)
        val header = lines.first()
        assertEquals(LineKind.HUNK, header.kind)
        assertEquals("@@ -2,9 +2,9 @@", header.text)
        val context = lines.filter { it.kind == LineKind.CONTEXT }
        assertEquals(8, context.size)
        assertEquals(listOf("- line 6", "+ line SIX"), lines.filter {
            it.kind == LineKind.DELETE || it.kind == LineKind.ADD
        }.map { it.text })
    }

    @Test
    fun hunkDiffSplitsDistantChangesIntoTwoHunks() {
        val old = (1..20).joinToString("\n") { "line $it" }
        val new = (1..20).joinToString("\n") { if (it == 3 || it == 18) "CHANGED $it" else "line $it" }
        val lines = ToolPresentation.hunkDiffLines(old, new)
        val hunks = lines.filter { it.kind == LineKind.HUNK }
        assertEquals(2, hunks.size)
        assertEquals("@@ -1,7 +1,7 @@", hunks[0].text)
        assertEquals("@@ -14,7 +14,7 @@", hunks[1].text)
    }

    @Test
    fun hunkDiffMergesOverlappingContextWindows() {
        // Two changes 4 lines apart: windows (with context=2) overlap → 1 hunk.
        val old = (1..10).joinToString("\n") { "l$it" }
        val new = (1..10).joinToString("\n") { if (it == 4 || it == 8) "X$it" else "l$it" }
        val lines = ToolPresentation.hunkDiffLines(old, new, context = 2)
        assertEquals(1, lines.count { it.kind == LineKind.HUNK })
    }

    @Test
    fun hunkDiffPureAdditionAndDeletionHaveNoHeader() {
        val added = ToolPresentation.hunkDiffLines("", "a\nb")
        assertEquals(
            listOf(LineKind.ADD to "+ a", LineKind.ADD to "+ b"),
            kinds(added),
        )
        val deleted = ToolPresentation.hunkDiffLines("a\nb", "")
        assertEquals(
            listOf(LineKind.DELETE to "- a", LineKind.DELETE to "- b"),
            kinds(deleted),
        )
    }

    @Test
    fun hunkDiffIsMinimalForAMovedBlock() {
        val old = "a\nb\nc\nd"
        val new = "a\nc\nd\nb"
        val lines = ToolPresentation.hunkDiffLines(old, new, context = 10)
        // Minimal script: one deletion + one insertion, not a full rewrite.
        assertEquals(1, lines.count { it.kind == LineKind.DELETE })
        assertEquals(1, lines.count { it.kind == LineKind.ADD })
        assertTrue(lines.count { it.kind == LineKind.CONTEXT } >= 2)
    }

    @Test
    fun editCardRendersHunksNotTheFlatPair() {
        val lines = ToolPresentation.bodyLines(
            toolPart("edit", input = """{"path":"a.kt","oldString":"a\nb\nc","newString":"a\nB\nc"}"""),
        )
        assertEquals(LineKind.HUNK, lines.first().kind)
        assertTrue(lines.any { it.kind == LineKind.CONTEXT && it.text == "  a" })
        assertTrue(lines.any { it.kind == LineKind.DELETE && it.text == "- b" })
        assertTrue(lines.any { it.kind == LineKind.ADD && it.text == "+ B" })
    }

    @Test
    fun patchHunkHeadersAreHunkKind() {
        val lines = ToolPresentation.patchLines("@@ -1,2 +1,2 @@\n-old\n+new")
        assertEquals(LineKind.HUNK, lines.first().kind)
        assertTrue(ToolPresentation.isDiffKind(LineKind.HUNK))
        assertTrue(ToolPresentation.isDiffKind(LineKind.CONTEXT))
        assertFalse(ToolPresentation.isDiffKind(LineKind.TEXT))
        assertFalse(ToolPresentation.isDiffKind(LineKind.COMMAND))
    }

    // -------------------------------------------------- A3-11 write as text

    @Test
    fun writeCardRendersPlainTextNotADiff() {
        val lines = ToolPresentation.bodyLines(
            toolPart("write", input = """{"path":"a.kt","content":"line one\nline two"}"""),
        )
        assertEquals(2, lines.size)
        assertTrue(lines.all { it.kind == LineKind.TEXT })
        assertEquals(listOf("line one", "line two"), lines.map { it.text })
    }

    // ------------------------------------------------- A3-7/8 shell output

    @Test
    fun shellOutputStripsAnsiAndNormalisesNewlines() {
        assertEquals("hello", ToolText.shellOutput("\u001B[32mhello\u001B[0m"))
        assertEquals("a\nb", ToolText.shellOutput("a\r\nb"))
        assertEquals("a\nb", ToolText.shellOutput("a\rb"))
        assertEquals("", ToolText.shellOutput(null))
        // Cursor-movement + erase sequences go too.
        assertEquals("xy", ToolText.shellOutput("\u001B[2K\u001B[1Gx\u001B[Cy"))
    }

    @Test
    fun shellBodyUsesCodeForExecuteAndStripsOutput() {
        val lines = ToolPresentation.bodyLines(
            toolPart(
                "execute",
                input = """{"code":"print(1)"}""",
                output = "\u001B[1m1\u001B[0m\r\n",
            ),
        )
        assertEquals(LineKind.COMMAND to "$ print(1)", kinds(lines)[0])
        assertTrue(lines.map { it.text }.contains("1"))
        assertFalse(lines.any { it.text.contains("\u001B") })
    }

    @Test
    fun shellFailureSubtitleStillReadsLikeTheCommand() {
        val part = toolPart("bash", status = "error", input = """{"command":"sleep 30"}""", error = "killed")
        assertEquals("sleep 30", ToolPresentation.subtitle(part))
    }

    // --------------------------------------------------- A3-13 error card

    @Test
    fun errorHeadDetailSplitsOnColonSpace() {
        val (head, detail) = ToolText.errorHeadDetail("Error: read failed: no such file", "read")
        // `{tool} ` prefix is stripped before the split.
        assertEquals("Failed", head)
        assertEquals("no such file", detail)
    }

    @Test
    fun errorHeadDetailCapitalisesAndFallsBack() {
        val (head, detail) = ToolText.errorHeadDetail("boom exploded", null)
        assertEquals("Boom exploded", head)
        assertEquals("", detail)
        val (fallback, _) = ToolText.errorHeadDetail("", null, fallback = "Failed")
        assertEquals("Failed", fallback)
    }

    @Test
    fun errorSubtitleUsesTheToolInputKey() {
        assertEquals(
            "sleep 30",
            ToolPresentation.subtitle(toolPart("shell", status = "error", input = """{"command":"sleep 30"}""", error = "exit 1")),
        )
        assertEquals(
            "main.kt",
            ToolPresentation.subtitle(toolPart("read", status = "error", input = """{"path":"/src/main.kt"}""", error = "denied")),
        )
        assertEquals(
            "2 questions",
            ToolPresentation.subtitle(
                toolPart(
                    "question",
                    status = "error",
                    input = """{"questions":[{"question":"a"},{"question":"b"}]}""",
                    error = "dismissed this question",
                ),
            ),
        )
        // No input at all → the old error-summary path (unchanged behaviour).
        assertEquals(
            "boom",
            ToolPresentation.subtitle(toolPart("bash", status = "error", error = "boom")),
        )
    }

    // ------------------------------------------------- A3-26 error unwrap

    @Test
    fun unwrapExtractsTypeAndMessageFromNestedJson() {
        assertEquals(
            "ProviderAuthError: bad key",
            ToolText.unwrapErrorMessage("""{"error":{"type":"ProviderAuthError","message":"bad key"}}"""),
        )
        assertEquals(
            "plain detail",
            ToolText.unwrapErrorMessage("""{"message":"plain detail"}"""),
        )
        assertEquals(
            "boom",
            ToolText.unwrapErrorMessage("""{"error":"boom"}"""),
        )
    }

    @Test
    fun unwrapHandlesPrefixAndDoubleEncoding() {
        assertEquals(
            "APIError: quota",
            ToolText.unwrapErrorMessage("""Error: {"error":{"type":"APIError","message":"quota"}}"""),
        )
        val inner = """{"error":{"type":"APIError","message":"quota"}}"""
        assertEquals(
            "APIError: quota",
            ToolText.unwrapErrorMessage(JSONObject.quote(inner)),
        )
    }

    @Test
    fun unwrapLeavesPlainTextAlone() {
        assertEquals("just a message", ToolText.unwrapErrorMessage("just a message"))
        // `Error:` prefix is stripped for the JSON probe but a non-JSON
        // payload returns the *original* message (upstream parity:
        // `projection.ts:741` `if (!record(json)) return message`).
        assertEquals("Error: x", ToolText.unwrapErrorMessage("Error: x"))
    }

    // ------------------------------------------------- dedicated dispatch

    @Test
    fun rendererKindDispatchMatchesUpstreamBranches() {
        assertEquals(ToolRendererKind.ERROR, toolRendererKind(toolPart("read", status = "error", error = "nope")))
        assertEquals(ToolRendererKind.SHELL, toolRendererKind(toolPart("bash")))
        assertEquals(ToolRendererKind.SHELL, toolRendererKind(toolPart("execute")))
        assertEquals(ToolRendererKind.SUBAGENT, toolRendererKind(toolPart("subagent")))
        assertEquals(ToolRendererKind.QUESTION, toolRendererKind(toolPart("question")))
        assertEquals(ToolRendererKind.SKILL, toolRendererKind(toolPart("skill")))
        assertEquals(ToolRendererKind.WEBSEARCH, toolRendererKind(toolPart("websearch")))
        // Question hides while streaming/running.
        assertEquals(ToolRendererKind.HIDDEN, toolRendererKind(toolPart("question", status = "running")))
        assertEquals(ToolRendererKind.HIDDEN, toolRendererKind(toolPart("question", status = "streaming")))
        // Generic tools keep the JSON body.
        assertEquals(ToolRendererKind.GENERIC, toolRendererKind(toolPart("glob", input = """{"pattern":"x"}""")))
        // Read with an image path resolves to the image row when completed …
        assertEquals(
            ToolRendererKind.READ_IMAGE,
            toolRendererKind(toolPart("read", input = """{"path":"shot.png"}""")),
        )
        // … but not while running (upstream only shows it on completion).
        assertEquals(
            ToolRendererKind.READ_LOADED,
            toolRendererKind(toolPart("read", status = "running", input = """{"path":"shot.png"}""")),
        )
    }

    // ------------------------------------------------- read / websearch

    @Test
    fun readImagePathAcceptsImagesOnly() {
        assertEquals("a/shot.png", ToolModels.readImagePath(JSONObject("""{"path":"a\\shot.png"}""")))
        assertNull(ToolModels.readImagePath(JSONObject("""{"path":"a/main.kt"}""")))
        assertNull(ToolModels.readImagePath(JSONObject("""{}""")))
        assertNull(ToolModels.readImagePath(null))
    }

    @Test
    fun loadedFilesJoinAndRelativise() {
        assertEquals(
            "Loaded src/a.kt, b.kt",
            ToolModels.loadedFiles(listOf("/repo/src/a.kt", "/repo/b.kt"), "/repo"),
        )
        assertNull(ToolModels.loadedFiles(emptyList()))
    }

    @Test
    fun webLinksDedupeAndTrim() {
        val output = "see https://a.example/x, https://a.example/x and (https://b.example/y.)"
        assertEquals(
            listOf("https://a.example/x", "https://b.example/y"),
            ToolModels.webLinks(output),
        )
        assertEquals(ToolModels.WEB_LINKS_SHOWN, 10)
        assertTrue(ToolModels.webLinks(null).isEmpty())
    }

    // ------------------------------------------------- skill / subagent

    @Test
    fun loadedSkillPrefersIdOverName() {
        assertEquals("plan", ToolModels.loadedSkillName(JSONObject("""{"id":"plan","name":"other"}""")))
        assertEquals("other", ToolModels.loadedSkillName(JSONObject("""{"name":"other"}""")))
        assertNull(ToolModels.loadedSkillName(JSONObject("""{}""")))
        assertEquals("Loaded plan", ToolModels.loadedSkillLabel("plan"))
    }

    @Test
    fun subagentSubtitlePrefersDescription() {
        assertEquals("Explore", ToolModels.subagentSubtitle("Explore", "ses_1", false))
        assertEquals("ses_1", ToolModels.subagentSubtitle(null, "ses_1", false))
        assertEquals("Explore (background)", ToolModels.subagentSubtitle("Explore", null, true))
        assertNull(ToolModels.subagentSubtitle(null, null, false))
    }

    @Test
    fun agentLabelsAndSlotsAreDeterministic() {
        assertEquals("Build", ToolModels.agentLabel("build"))
        assertNull(ToolModels.agentLabel(null))
        assertEquals(agentSlot("zzz", 6), agentSlot("zzz", 6))
        // Slots stay in range for any name.
        assertTrue(agentSlot("some-agent-name", 6) in 0 until 6)
    }

    @Test
    fun subagentRunningCoversStreamingAndChild() {
        assertTrue(ToolModels.subagentRunning("streaming"))
        assertTrue(ToolModels.subagentRunning("running"))
        assertTrue(ToolModels.subagentRunning("completed", childRunning = true))
        assertFalse(ToolModels.subagentRunning("completed"))
    }

    // ------------------------------------------------- question

    @Test
    fun questionSubtitleAndAnswers() {
        val answered = ToolModels.QuestionModel(
            questions = listOf(ToolModels.QuestionItem("a"), ToolModels.QuestionItem("b")),
            answers = listOf(listOf("x"), emptyList()),
        )
        assertEquals("2 answered", ToolModels.questionSubtitle(answered) { "$it answered" })
        assertEquals(
            listOf("a" to "x", "b" to "(no answer)"),
            ToolModels.questionAnswers(answered, "(no answer)"),
        )
        val open = ToolModels.QuestionModel(
            questions = listOf(ToolModels.QuestionItem("a")),
            answers = emptyList(),
        )
        assertEquals("1 question", ToolModels.questionSubtitle(open) { "$it answered" })
        assertNull(ToolModels.questionSubtitle(ToolModels.QuestionModel(emptyList(), emptyList())) { "x" })
    }

    // ------------------------------------------------- diagnostics

    @Test
    fun diagnosticsKeepErrorsCappedAtThree() {
        val json = JSONObject().apply {
            put("a.kt", org.json.JSONArray().apply {
                repeat(5) { i ->
                    put(JSONObject().apply {
                        put("message", "err $i")
                        put("severity", 1)
                        put("range", JSONObject().apply {
                            put("start", JSONObject().apply {
                                put("line", i)
                                put("character", 2)
                            })
                            put("end", JSONObject().apply {
                                put("line", i)
                            })
                        })
                    })
                }
                put(JSONObject().apply {
                    put("message", "a warning")
                    put("severity", 2)
                    put("range", JSONObject().apply {
                        put("start", JSONObject().apply {
                            put("line", 0)
                        })
                    })
                })
            })
        }.toString()
        val rows = ToolModels.diagnostics(json, "a.kt")
        assertEquals(3, rows.size)
        assertEquals(ToolModels.Diagnostic(1, 3, "err 0"), rows[0])
        assertTrue(ToolModels.diagnostics(json, "other.kt").isEmpty())
        assertTrue(ToolModels.diagnostics(null, "a.kt").isEmpty())
    }

    // ------------------------------------------------- compaction / chips

    @Test
    fun compactionSummaryOrFallback() {
        val withSummary = toolPart(null, type = "compaction", text = "Did stuff")
        val model = ToolModels.compaction(withSummary)
        assertEquals(ToolModels.CompactionKind.SUMMARY, model.kind)
        assertEquals("Did stuff", model.summary)
        val empty = toolPart(null, type = "compaction", text = "  ")
        assertEquals(ToolModels.CompactionKind.STARTED_FALLBACK, ToolModels.compaction(empty).kind)
    }

    @Test
    fun countChipsCountTitlesInOrder() {
        fun tool(name: String) = toolPart(name, input = """{"filePath":"f"}""")
        val chips = ToolModels.countChips(listOf(tool("read"), tool("read"), tool("glob")))
        assertEquals(listOf("Read" to 2, "Glob" to 1), chips)
        assertTrue(ToolModels.countChips(emptyList()).isEmpty())
    }

    @Test
    fun diffBarsFollowTheUpstreamBucketing() {
        assertEquals(List(5) { ToolModels.DiffCell.WEAK }, ToolModels.diffBars(0, 0))
        // Fewer than 5 total: one cell per non-empty side.
        assertEquals(
            listOf(
                ToolModels.DiffCell.ADD,
                ToolModels.DiffCell.WEAK,
                ToolModels.DiffCell.WEAK,
                ToolModels.DiffCell.WEAK,
                ToolModels.DiffCell.WEAK,
            ),
            ToolModels.diffBars(2, 0),
        )
        assertEquals(
            listOf(
                ToolModels.DiffCell.ADD,
                ToolModels.DiffCell.DELETE,
                ToolModels.DiffCell.WEAK,
                ToolModels.DiffCell.WEAK,
                ToolModels.DiffCell.WEAK,
            ),
            ToolModels.diffBars(1, 1),
        )
        // Volume splits proportionally over 4-5 cells.
        val bars = ToolModels.diffBars(30, 10)
        assertEquals(5, bars.size)
        assertTrue(bars.count { it == ToolModels.DiffCell.ADD } >= bars.count { it == ToolModels.DiffCell.DELETE })
    }
}
