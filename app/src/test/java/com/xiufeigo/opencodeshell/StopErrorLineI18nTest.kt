package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.timeline.SERVER_ERROR_PHRASES
import com.xiufeigo.opencodeshell.ui.session.timeline.ToolText
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The client-authored half of the error phrase table: the fixed English failure
 * sentences `ChatViewModel` raises as `RunStatusChanged(ERROR, …)` reach
 * `SessionErrorCard` through [ToolText.localizeServerPhrases], so without table
 * entries they were the one error line on the card still printed in English on
 * a Chinese device — the Stop that never reached the server, beside the
 * connection verdicts.
 *
 * The store keeps the English text (the copy payload, `TransportRetry`'s
 * matcher, `BannerPolicy`'s classifier all read it), so only the *displayed*
 * half is localized — the same rule the server-authored half already follows.
 */
class StopErrorLineI18nTest {

    /** `ChatViewModel.reportStopUndelivered`, with a reason in the middle. */
    private val stopLine =
        "Stop did not reach the server (the server reports no execution to interrupt) " +
            "— the turn may still be running."

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("missing $relative")

    private fun strings(locale: String): Map<String, String> =
        Regex("""<string name="([^"]+)">(.*?)</string>""")
            .findAll(source("src/main/res/values$locale/strings_oc_i18n.xml"))
            .associate { it.groupValues[1] to it.groupValues[2] }

    /** The table resolved against a locale, read off the source the way the table test reads it. */
    private fun phrases(locale: String): Map<String, String> {
        val body = source(
            "src/main/java/com/xiufeigo/opencodeshell/ui/session/timeline/ToolRenderers.kt",
        ).substringAfter("internal val SERVER_ERROR_PHRASES")
            .substringBefore("\n@Composable")
        val wired = Regex("""ServerErrorPhrase\(\s*"([^"]*)",\s*R\.string\.(\w+),?\s*\)""")
            .findAll(body)
            .associate { it.groupValues[1] to it.groupValues[2] }
        assertEquals(SERVER_ERROR_PHRASES.map { it.phrase }, wired.keys.toList())
        val values = strings(locale)
        return wired.mapValues { (_, name) -> values.getValue(name) }
    }

    @Test
    fun theStopLineReadsInSimplifiedChinese() {
        val shown = ToolText.localizeServerPhrases(stopLine, phrases("-zh-rCN"))
        assertEquals("停止指令未送达服务器（the server reports no execution to interrupt）— 本轮可能仍在运行。", shown)
        // The reason the transport reported survives the swap — it is the only
        // part of the line the client cannot name.
        assertTrue(
            "the reason is dropped",
            shown.contains("the server reports no execution to interrupt"),
        )
    }

    @Test
    fun theStopLineReadsInTraditionalChinese() {
        val shown = ToolText.localizeServerPhrases(stopLine, phrases("-zh-rTW"))
        assertEquals("停止指令未送達伺服器（the server reports no execution to interrupt）— 本輪可能仍在執行。", shown)
    }

    @Test
    fun anEnglishLocaleIsLeftAlone() {
        val identity = SERVER_ERROR_PHRASES.associate { it.phrase to it.phrase }
        assertEquals(stopLine, ToolText.localizeServerPhrases(stopLine, identity))
    }

    @Test
    fun everyFixedErrorLineTheViewModelRaisesIsInTheTable() {
        // The Stop line alone would have been the inconsistent one: these five
        // reach the same card by the same path. (The em dash is what separates
        // them from NewSessionScreen's own `onError` copy, which never reaches
        // the error card.)
        val table = SERVER_ERROR_PHRASES.map { it.phrase }.toSet()
        val raised = Regex(""""((?:Not connected —|Stop did not reach)[^"]*)"""")
            .findAll(source("src/main/java/com/xiufeigo/opencodeshell/ChatViewModel.kt"))
            .map { it.groupValues[1] }
            .toList()
        assertEquals("the five connection verdicts and the Stop line", 6, raised.size)
        for (line in raised) {
            assertTrue(
                "'$line' is not in the phrase table",
                // Either the whole line, or — the Stop sentence — a table phrase
                // that opens it, with the reported reason interpolated after.
                line in table || table.any { line.startsWith(it) },
            )
        }
    }
}
