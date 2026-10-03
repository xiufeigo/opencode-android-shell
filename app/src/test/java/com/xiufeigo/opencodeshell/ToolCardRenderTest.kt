package com.xiufeigo.opencodeshell

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-level pins for tool-card chrome fixes that live inside `@Composable`
 * functions (not reachable from a plain JVM call): `ToolDisplay`-style
 * whole-card replacement for the dedicated renderers (no stacked trigger
 * rows), the error-detail height cap, and the single shared `bodyLines` cache.
 */
class ToolCardRenderTest {

    private fun source(relative: String): String {
        val file = File(relative).takeIf { it.isFile } ?: File("app/$relative")
        return file.readText()
    }

    private fun partRenderer() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/timeline/PartRenderer.kt")

    @Test
    fun dedicatedRenderersReplaceTheGenericCard() {
        // The tool branch up to the generic `ToolCard(...)`: each dedicated
        // card renders and bails out before it — nesting them stacked two
        // header rows and made the error detail a two-tap target.
        val dispatch = partRenderer()
            .substringAfter("\"tool\" ->")
            .substringBefore("\"reasoning\" ->")
        val calls = listOf(
            "ToolErrorCard(part = part",
            "QuestionCard(part = part",
            "SubagentCard(part = part",
        )
        for (call in calls) {
            val at = dispatch.indexOf(call)
            assertTrue("$call must render at row level", at >= 0)
            val after = dispatch.substring(at)
            val ret = after.indexOf("return@PartRenderer")
            val generic = after.indexOf("ToolCard(")
            assertTrue("$call must return before the generic ToolCard", ret in 0 until generic)
        }
    }

    @Test
    fun errorDetailIsHeightCappedAndScrollable() {
        val card = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/timeline/ToolRenderers.kt")
            .substringAfter("fun ToolErrorCard(")
            .substringBefore("private fun ErrorTriggerRow")
        assertTrue(card.contains("heightIn(max = TOOL_BODY_MAX_HEIGHT)"))
        assertTrue(card.contains("verticalScroll(rememberScrollState())"))
    }

    @Test
    fun toolCardBodyLinesAreCachedOnceAndSharedWithTheBody() {
        val card = partRenderer()
            .substringAfter("private fun ToolCard(")
            .substringBefore("internal fun ToolBody(")
        assertTrue(card.contains("ToolPresentation.bodyLines(part)"))
        assertTrue(card.contains("ToolCardPolicy.canExpand(part, lines)"))
        assertTrue(
            card.contains(
                "ToolCardPolicy.resolveOpen(part, userOpen, shellExpanded, editExpanded, lines)",
            ),
        )
        assertTrue(card.contains("ToolBody(part, lines)"))
        val policy = source(
            "src/main/java/com/xiufeigo/opencodeshell/ui/session/timeline/ToolCardPolicy.kt",
        )
        assertTrue(policy.contains("lines: List<ToolPresentation.BodyLine>? = null"))
    }
}
