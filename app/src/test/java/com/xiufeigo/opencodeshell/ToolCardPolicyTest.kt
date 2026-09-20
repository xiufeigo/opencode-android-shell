package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.timeline.ToolCardPolicy
import com.xiufeigo.opencodeshell.ui.session.timeline.ToolPresentation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `partDefaultOpen` / chevron-gating transcription (`research/02` §3.1 / §3.2 /
 * §3.2b). These are the rules M2 wires into the tool card, so they are pinned
 * here rather than only in the UI.
 */
class ToolCardPolicyTest {

    private fun part(
        tool: String,
        status: String? = "completed",
        input: String? = null,
        output: String? = null,
        error: String? = null,
        type: String = "tool",
    ) = ChatStore.UiPart(
        id = "p_$tool${input?.hashCode() ?: 0}",
        type = type,
        toolName = tool,
        toolStatus = status,
        toolInput = input,
        toolOutput = output,
        toolError = error,
    )

    // --------------------------------------------------------- default open

    @Test
    fun shellDefaultsCollapsedAndFollowsShellSetting() {
        val shell = part("bash", input = """{"command":"ls"}""")
        assertFalse(ToolCardPolicy.defaultOpen(shell))
        assertTrue(ToolCardPolicy.defaultOpen(shell, shellExpanded = true))
        assertEquals(
            ToolCardPolicy.DEFAULT_SHELL_EXPANDED,
            ToolCardPolicy.defaultOpen(part("shell", input = """{"command":"ls -la"}""")),
        )
    }

    @Test
    fun editWithAdditionsDefaultsExpanded() {
        val edit = part("edit", input = """{"filePath":"a.kt","oldString":"a","newString":"b"}""")
        assertTrue(ToolCardPolicy.defaultOpen(edit))
        assertFalse(ToolCardPolicy.deletionOnly(edit))
    }

    @Test
    fun editThatOnlyDeletesDefaultsCollapsed() {
        val deletion = part("edit", input = """{"filePath":"a.kt","oldString":"a\nb"}""")
        assertTrue(ToolCardPolicy.deletionOnly(deletion))
        assertFalse(ToolCardPolicy.defaultOpen(deletion))
        // ... and stays collapsed even when the edit setting is on: upstream is
        // `edit && !deletionOnly(part)`.
        assertFalse(ToolCardPolicy.defaultOpen(deletion, editExpanded = true))
    }

    @Test
    fun writeWithContentDefaultsExpanded() {
        val write = part("write", input = """{"filePath":"a.kt","content":"hello"}""")
        assertTrue(ToolCardPolicy.defaultOpen(write))
        // No diff at all ⇒ upstream's `deletionOnly` is false (it needs
        // `metadata.files` / `filediff`), so the edit setting still applies.
        assertTrue(ToolCardPolicy.defaultOpen(part("write", input = """{"filePath":"a.kt"}""")))
    }

    @Test
    fun editWithoutTheSettingStaysCollapsed() {
        val edit = part("edit", input = """{"oldString":"a","newString":"b"}""")
        assertFalse(ToolCardPolicy.defaultOpen(edit, editExpanded = false))
    }

    @Test
    fun otherToolsFold() {
        assertFalse(ToolCardPolicy.defaultOpen(part("read", input = """{"filePath":"a"}""")))
        assertFalse(ToolCardPolicy.defaultOpen(part("webfetch", input = """{"url":"u"}""")))
        assertFalse(ToolCardPolicy.defaultOpen(part("task", input = """{"description":"d"}""")))
    }

    // ------------------------------------------------------------- gating

    @Test
    fun pendingAndRunningAreLockedExceptShell() {
        val runningRead = part("read", status = "running", input = """{"filePath":"a"}""")
        val pendingRead = part("read", status = "pending", input = """{"filePath":"a"}""")
        val runningShell = part("bash", status = "running", input = """{"command":"ls"}""")
        assertTrue(ToolCardPolicy.isPendingOrRunning(runningRead))
        assertFalse(ToolCardPolicy.canExpand(runningRead))
        assertFalse(ToolCardPolicy.canExpand(pendingRead))
        // `allowOpenWhilePending` is set for shell only.
        assertTrue(ToolCardPolicy.canExpand(runningShell))
    }

    @Test
    fun bodylessCardHasNoChevron() {
        assertFalse(ToolCardPolicy.canExpand(part("read")))
        assertTrue(ToolCardPolicy.canExpand(part("read", input = """{"filePath":"a"}""")))
        // A failed tool always has a body: its own error text (`§3.3`).
        assertTrue(ToolCardPolicy.canExpand(part("grep", status = "error", error = "boom")))
    }

    // -------------------------------------------------------------- resolve

    @Test
    fun explicitToggleWinsOverTheDefault() {
        val edit = part("edit", input = """{"oldString":"a","newString":"b"}""")
        // Default-open card: the user closing it must be honoured ...
        assertTrue(ToolCardPolicy.resolveOpen(edit, userOpen = null))
        assertFalse(ToolCardPolicy.resolveOpen(edit, userOpen = false))
        // ... and vice versa for a default-closed card.
        val read = part("read", input = """{"filePath":"a"}""")
        assertFalse(ToolCardPolicy.resolveOpen(read, userOpen = null))
        assertTrue(ToolCardPolicy.resolveOpen(read, userOpen = true))
    }

    @Test
    fun lockedCardCollapsesEvenWithAStaleUserFlag() {
        val running = part("read", status = "running", input = """{"filePath":"a"}""")
        assertFalse(ToolCardPolicy.resolveOpen(running, userOpen = true))
    }

    @Test
    fun statesMapToTheFourCardStates() {
        assertEquals(ToolCardPolicy.State.PENDING, ToolCardPolicy.stateOf(part("read", status = "pending")))
        assertEquals(ToolCardPolicy.State.RUNNING, ToolCardPolicy.stateOf(part("read", status = "running")))
        assertEquals(ToolCardPolicy.State.ERROR, ToolCardPolicy.stateOf(part("read", status = "error")))
        assertEquals(ToolCardPolicy.State.COMPLETED, ToolCardPolicy.stateOf(part("read", status = "completed")))
        assertEquals(ToolCardPolicy.State.COMPLETED, ToolCardPolicy.stateOf(part("read", status = null)))
    }

    // --------------------------------------------------- error body (§3.3)

    @Test
    fun failedToolBodyStartsWithItsErrorMessage() {
        val failed = part(
            "bash",
            status = "error",
            input = """{"command":"make"}""",
            output = "line1",
            error = "exit 2: no rule",
        )
        val lines = ToolPresentation.bodyLines(failed)
        assertEquals(ToolPresentation.LineKind.ERROR, lines.first().kind)
        assertEquals("exit 2: no rule", lines.first().text)
        // The command header and the captured output are still there.
        assertTrue(lines.any { it.kind == ToolPresentation.LineKind.COMMAND })
        assertTrue(lines.any { it.text == "line1" })
        // A successful tool gains no error line.
        assertTrue(
            ToolPresentation.bodyLines(part("bash", input = """{"command":"make"}"""))
                .none { it.kind == ToolPresentation.LineKind.ERROR },
        )
    }
}
