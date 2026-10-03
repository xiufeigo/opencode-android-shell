package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.timeline.toolErrorBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `ToolErrorCard`'s expanded body must never be blank while the trigger is
 * expandable: a single-line error has no `": "` remainder (`detail` is
 * empty), so the old `detail`-only body expanded to an empty card. The body
 * merges the human line and the remainder like `errorCardBody`.
 */
class ToolErrorBodyTest {

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("missing $relative")

    @Test
    fun singleLineErrorExpandsToItsHead() {
        assertEquals("Boom exploded", toolErrorBody("Boom exploded", ""))
    }

    @Test
    fun headPlusDetailJoinedWithBlankLine() {
        assertEquals("Failed\n\nno such file", toolErrorBody("Failed", "no such file"))
    }

    @Test
    fun duplicateSegmentsDeduped() {
        assertEquals("boom", toolErrorBody("boom", "boom"))
    }

    @Test
    fun blankHalvesDropped() {
        assertEquals("boom", toolErrorBody("  boom\n", "  "))
        assertEquals("", toolErrorBody("  ", "  "))
    }

    @Test
    fun triggerOnlyExpandsWhileThereIsAnErrorToShow() {
        // Source pin (repo `ToolCardRenderTest` convention): the card merges
        // the body through `toolErrorBody` and gates the trigger on the error
        // text, so expandable ⇒ non-empty.
        val card = source(
            "src/main/java/com/xiufeigo/opencodeshell/ui/session/timeline/ToolRenderers.kt",
        ).substringAfter("fun ToolErrorCard(")
            .substringBefore("private fun ErrorTriggerRow")
        assertTrue(card.contains("toolErrorBody(head, detail)"))
        assertTrue(card.contains("expandable"))
        assertTrue(card.contains("if (expanded && fullBody.isNotEmpty())"))
    }
}
