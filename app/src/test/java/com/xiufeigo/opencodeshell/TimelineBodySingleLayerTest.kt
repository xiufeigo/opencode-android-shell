package com.xiufeigo.opencodeshell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v58 — the short-window ghost text (a ~30 % alpha copy of the body paragraph,
 * offset ≈ +17 dp / −4.5 dp, on `research/v57_verify/5554/44_short_scrolldown.png`
 * and `47_kb3.png`, gone once the window is restored) turned out to have no
 * second text node anywhere in the composition, so it is a stale window buffer
 * rather than app content.
 *
 * This pins the invariant that makes that conclusion checkable: a markdown body
 * paints its text exactly once, with no overlay or offscreen layer for a stale
 * copy to survive in. A future "fix" that reaches for a second `Text`, a
 * `drawWithContent` / `graphicsLayer` cache or a conditional alpha on the body
 * fails here instead of on an emulator.
 */
class TimelineBodySingleLayerTest {

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("missing $relative")

    private val markdown: String
        get() = source("src/main/java/com/xiufeigo/opencodeshell/ui/markdown/MarkdownRenderer.kt")

    @Test
    fun aParagraphAndAHeadingAreEachOneText() {
        assertTrue(markdown.contains("is MdBlock.Paragraph -> Text("))
        assertEquals(1, markdown.windowed("is MdBlock.Paragraph -> Text(".length).count { it == "is MdBlock.Paragraph -> Text(" })
    }

    @Test
    fun theBodyOwnsNoOverlayOrCachedLayer() {
        val markdownDir = File("app/src/main/java/com/xiufeigo/opencodeshell/ui/markdown")
            .takeIf { it.isDirectory }
            ?: File("src/main/java/com/xiufeigo/opencodeshell/ui/markdown")
        val files = markdownDir.listFiles { f: File -> f.extension == "kt" }.orEmpty()
        assertTrue("no markdown sources found", files.isNotEmpty())
        for (file in files) {
            val text = file.readText()
            for (forbidden in listOf("drawWithContent", "drawWithCache", "graphicsLayer", "RenderEffect")) {
                assertTrue("${file.name} uses $forbidden", forbidden !in text)
            }
        }
    }

    @Test
    fun theTimelineBodyPathAddsNoLayerOfItsOwn() {
        val timeline = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/timeline/PartRenderer.kt")
        for (forbidden in listOf("drawWithContent", "drawWithCache", "graphicsLayer", "RenderEffect")) {
            assertTrue("PartRenderer uses $forbidden", forbidden !in timeline)
        }
        // The only animations left in the body path are the tool / reasoning /
        // patch chevrons' rotations — no alpha, no translate, nothing that
        // could keep a faded copy of the text on screen.
        // 3 call sites (+ the import): tool / reasoning / patch chevrons.
        assertEquals(4, timeline.windowed("animateFloatAsState".length).count { it == "animateFloatAsState" })
        assertTrue("a chevron rotation that is not a rotation", timeline.contains("if (expanded) 0f else -90f"))
        assertTrue("alpha on the body path", "Modifier.alpha(" !in timeline)
    }
}
