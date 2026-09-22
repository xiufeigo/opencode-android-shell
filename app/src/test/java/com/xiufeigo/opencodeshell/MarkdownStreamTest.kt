package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.timeline.COL_CHUNK_MS
import com.xiufeigo.opencodeshell.ui.session.timeline.coalesceLatest
import com.xiufeigo.opencodeshell.ui.session.timeline.isNightUiMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Markdown streaming pipeline (M1-review "Markwon 每帧全量重解析" +
 * "Markwon 配色随主题刷新"): the coalescing contract is pure and JVM-tested, the
 * two Compose-side wirings (throttled value → `AndroidView`, theme-keyed
 * instance) are guarded at source level — they are only reachable from an
 * instrumented test.
 */
class MarkdownStreamTest {

    /** Small window: the tests assert the *shape* (windows), not the 80 ms wall time. */
    private val window = 5L

    // ------------------------------------------------------------ coalescing

    @Test
    fun coalescingKeepsTheFirstAndTheNewestValueOfABurst() {
        val emitted = runBlocking { flowOf("a", "b", "c").coalesceLatest(window).toList() }
        assertEquals(listOf("a", "c"), emitted)
    }

    @Test
    fun theLastValueOfABurstIsNeverDropped() {
        // A streaming answer emits faster than the window; losing the tail would
        // render a truncated answer until the next event.
        val emitted = runBlocking {
            flowOf("a", "ab", "abc", "abcd").coalesceLatest(window).toList()
        }
        assertEquals("a", emitted.first())
        assertEquals("abcd", emitted.last())
    }

    @Test
    fun aValueOutsideTheWindowIsPassedThrough() {
        val emitted = runBlocking {
            flow {
                emit("1")
                delay(window * 4)
                emit("2")
            }.coalesceLatest(window).toList()
        }
        assertEquals(listOf("1", "2"), emitted)
    }

    @Test
    fun theWindowIsTheViewEraCoalescingConstant() {
        assertEquals(80L, COL_CHUNK_MS)
    }

    // ---------------------------------------------------------------- theme

    @Test
    fun nightUiModeIsDecodedFromTheConfigurationBits() {
        // 0x30 = Configuration.UI_MODE_NIGHT_MASK, 0x20 = UI_MODE_NIGHT_YES,
        // 0x10 = UI_MODE_NIGHT_NO (repeated locally so the test never touches the
        // android.jar stub).
        assertTrue(isNightUiMode(0x20))
        assertTrue(isNightUiMode(0x21)) // NIGHT_YES | TYPE_NORMAL
        assertFalse(isNightUiMode(0x10))
        assertFalse(isNightUiMode(0x11)) // NIGHT_NO | TYPE_NORMAL
        assertFalse(isNightUiMode(0x00))
    }

    // -------------------------------------------------------- source guards

    @Test
    fun theBodyIsCoalescedInsteadOfReparsedPerRecomposition() {
        // The renderer moved to the Compose-native `OcMarkdown` (ui/markdown)
        // with the timeline fidelity pass; the coalescing contract moved with it.
        val renderer = source(RENDERER)
        assertTrue("the streaming text must be throttled", renderer.contains(".coalesceLatest()"))
        assertTrue(
            "the throttled value must be what gets parsed",
            renderer.contains("remember(rendered)"),
        )
        assertFalse(
            "no raw per-frame parse of the streaming parameter",
            renderer.contains("MarkdownParser.parse(markdown)"),
        )
    }

    @Test
    fun theRendererIsComposeNativeAndFollowsTheTheme() {
        // Replaces the Markwon-era "instance rebuilt on uiMode" guard: a
        // Compose-native body reads `OcTheme.colors` in composition, so a
        // dark⇄light switch recolours without any baked-palette cache.
        val renderer = source(RENDERER)
        assertTrue(renderer.contains("OcTheme.colors"))
        assertFalse("no interop TextView", renderer.contains("AndroidView"))
        assertFalse("no Markwon dependency", renderer.contains("io.noties"))
    }

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("missing $relative")

    private companion object {
        const val RENDERER =
            "src/main/java/com/xiufeigo/opencodeshell/ui/markdown/MarkdownRenderer.kt"
    }
}
