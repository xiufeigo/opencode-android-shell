package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.settings.MODEL_REF_MAX_CHARS
import com.xiufeigo.opencodeshell.ui.settings.middleEllipsize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v46 tD_89/tD_92 — the Models/Providers rows hid what distinguishes them:
 *
 * - two models rendered the identical `DeepSeek V4 Fl…` — the difference lives
 *   in the truncated tail — and two providers read `Default / Ope…` /
 *   `MiniMax Tok…` for the same reason (one end-ellipsized line cuts exactly
 *   the distinguishing part);
 * - the model's `provider/model` ref wrapped mid-token (`…flash-vis|ion-exp`).
 *
 * Names now wrap to two lines (every cataloged name fits, so the tail stays on
 * screen); the unbroken ref token is middle-truncated to one line (one line
 * can never break a word, and [middleEllipsize] keeps the tail). The rows have
 * no JVM-renderable surface (no Compose test dependency; see
 * `SettingsPanelScrollTest`), so the wiring is pinned as source facts and the
 * truncation itself as pure behaviour.
 */
class ModelProviderTruncationV46Test {

    private fun source(relative: String): String {
        val file = File(relative).takeIf { it.isFile } ?: File("app/$relative")
        return file.readText().replace("\r\n", "\n")
    }

    private fun panels() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/settings/SettingsPanels.kt")

    private fun modelRow(): String =
        panels().substringAfter("private fun ModelVisibilityRow(")
            .substringBefore("internal fun RowSeparator()")

    private fun connectedRow(): String =
        panels().substringAfter("private fun ProviderConnectedRow(")
            .substringBefore("private fun ProviderPopularRow(")

    private fun popularRow(): String =
        panels().substringAfter("private fun ProviderPopularRow(")
            .substringBefore(" * `SettingsModelsV2`")

    // ------------------------------------------------------- the pure split

    @Test
    fun shortTextPassesThroughUntouched() {
        assertEquals("opencode-go/deepseek-v4-flash", middleEllipsize("opencode-go/deepseek-v4-flash", 30))
        assertEquals("exact fit is not truncated", "abcde", middleEllipsize("abcde", 5))
        assertEquals("empty stays empty", "", middleEllipsize("", 30))
    }

    @Test
    fun longTextKeepsItsHeadAndItsTail() {
        // The v46 pair: identical heads, told apart only by the tail.
        val truncated = middleEllipsize("opencode-go/deepseek-v4-flash-vision-exp", MODEL_REF_MAX_CHARS)
        assertEquals("exactly the budget", MODEL_REF_MAX_CHARS, truncated.length)
        assertTrue("the head survives", truncated.startsWith("opencode-go/de"))
        assertTrue("the distinguishing tail survives", truncated.endsWith("vision-exp"))
        assertTrue("one ellipsis joins them", truncated.contains("…") && !truncated.contains("……"))
    }

    @Test
    fun degenerateBudgetsNeverCrash() {
        assertEquals("below two there is no room for head, ellipsis and tail", "abcdef", middleEllipsize("abcdef", 1))
        assertEquals("zero too", "abcdef", middleEllipsize("abcdef", 0))
        assertEquals("negative too", "abcdef", middleEllipsize("abcdef", -3))
        assertEquals("two keeps the head around the ellipsis", "a…", middleEllipsize("abcdef", 2))
    }

    // ------------------------------------------------------- the row wiring

    @Test
    fun theModelNameWrapsInsteadOfCuttingItsTail() {
        val row = modelRow()
        val name = row.substringBefore("if (row.current)")
        assertTrue("two lines fit every cataloged name", name.contains("maxLines = 2"))
        assertTrue("still ellipsized at the tail for absurd names", name.contains("overflow = TextOverflow.Ellipsis"))
    }

    @Test
    fun theModelRefIsOneMiddleTruncatedLine() {
        val row = modelRow()
        val ref = row.substringAfter("row.ref")
        assertTrue("the ref goes through the middle split", row.contains("middleEllipsize(row.ref, MODEL_REF_MAX_CHARS)"))
        assertTrue("a single line can never break mid-word", ref.contains("maxLines = 1"))
    }

    @Test
    fun bothProviderNamesWrapInsteadOfCuttingTheirTails() {
        for ((label, row) in listOf("connected" to connectedRow(), "popular" to popularRow())) {
            assertTrue("the $label name wraps to two lines", row.contains("maxLines = 2"))
        }
    }
}
