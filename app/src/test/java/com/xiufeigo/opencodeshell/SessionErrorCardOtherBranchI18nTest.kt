package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.timeline.SERVER_ERROR_PHRASES
import com.xiufeigo.opencodeshell.ui.session.timeline.ToolText
import com.xiufeigo.opencodeshell.ui.session.timeline.errorCardBody
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The **generic (`OTHER`) branch of `SessionErrorCard`** in the UI locale: the
 * server composes its failure sentences in English and sends no key, so the
 * card's summary line and expanded body are the only surfaces that could print
 * English under a localized title ([errorCopy]'s `else` arm → `oc_error_title`).
 *
 * The display half therefore runs through [SERVER_ERROR_PHRASES] resolved from
 * the generated resources ([ToolText.localizeServerPhrases]); these tests pin
 * that wiring for the *session* card's own pipeline — `unwrapErrorMessage` →
 * first non-blank line → the dictionary swap → [errorCardBody] — over the whole
 * table and in all three locales, plus a leak detector that would catch a phrase
 * the dictionary fails to swap.
 *
 * The sibling [ServerErrorPhraseTableTest] pins the key wiring itself (every
 * phrase → an `error.server.*` name present and non-English in all three
 * generated files) and the tool card's head/detail split; this class pins the
 * session card's summary/body path, which is what the `OTHER` branch renders.
 */
class SessionErrorCardOtherBranchI18nTest {

    /** Same resolution the other source-reading tests use (module dir or repo root). */
    private fun source(relative: String): String {
        val file = File(relative).takeIf { it.isFile } ?: File("app/$relative")
        return file.readText()
    }

    /** `<string name="…">value</string>` → name to value (generated + hand-written). */
    private fun strings(relative: String): Map<String, String> =
        Regex("""<string name="([^"]+)">([^<]*)</string>""")
            .findAll(source(relative))
            .associate { it.groupValues[1] to unquote(it.groupValues[2]) }

    /**
     * `gen_i18n.ps1` wraps a value whose surrounding whitespace matters in double
     * quotes; aapt2 keeps the quoted whitespace and drops the quotes.
     */
    private fun unquote(value: String): String =
        if (value.length >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value.substring(1, value.length - 1)
        } else {
            value
        }

    /** The dictionary the app loads for one locale: the generated `error.server.*` values. */
    private fun table(locale: String): Map<String, String> = tables.getValue(locale)

    private val tables: Map<String, Map<String, String>> by lazy {
        val wired = wiredNames
        listOf("values", "values-zh-rCN", "values-zh-rTW").associateWith { locale ->
            val xml = strings("src/main/res/$locale/strings_oc_i18n.xml")
            SERVER_ERROR_PHRASES.associate { it.phrase to xml.getValue(wired.getValue(it.phrase)) }
        }
    }

    /**
     * The `R.string.<name>` each phrase is wired to, read off the source — a JVM
     * test cannot recover a resource **name** from a compiled `R` id.
     */
    private val wiredNames: Map<String, String> by lazy {
        val body = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/timeline/ToolRenderers.kt")
            .substringAfter("internal val SERVER_ERROR_PHRASES")
            .substringBefore("\n@Composable")
        val pairs = Regex("""ServerErrorPhrase\(\s*"([^"]*)",\s*R\.string\.(\w+),?\s*\)""")
            .findAll(body)
            .associate { it.groupValues[1] to it.groupValues[2] }
        SERVER_ERROR_PHRASES.associate { entry ->
            entry.phrase to pairs.getValue(entry.phrase)
        }
    }

    /** The card's summary line for a server failure: unwrap, then the first non-blank line. */
    private fun summaryOf(message: String): String {
        val unwrapped = ToolText.unwrapErrorMessage(message)
        return unwrapped.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: unwrapped.trim()
    }

    /** What the `OTHER` branch paints for [message] in one locale. */
    private fun shownSummary(message: String, locale: String): String =
        ToolText.localizeServerPhrases(summaryOf(message), table(locale))

    /** What the `OTHER` branch paints behind the chevron in one locale. */
    private fun shownBody(message: String, detail: String?, locale: String): String =
        ToolText.localizeServerPhrases(errorCardBody(message, detail), table(locale))

    @Test
    fun `the generic branch title and details label are translated in all three locales`() {
        // The OTHER arm has no advice — the title and the expandable row's
        // content description are the only copy the card itself contributes, so
        // they are the whole of its non-server text.
        val en = strings("src/main/res/values/strings.xml")
        val zh = strings("src/main/res/values-zh-rCN/strings.xml")
        val zht = strings("src/main/res/values-zh-rTW/strings.xml")
        for (name in listOf("oc_error_title", "oc_error_details")) {
            assertTrue("$name missing from values/", en.containsKey(name))
            assertTrue("$name missing from values-zh-rCN/", zh.containsKey(name))
            assertTrue("$name missing from values-zh-rTW/", zht.containsKey(name))
            assertNotEquals("$name is untranslated in zh", en.getValue(name), zh.getValue(name))
            assertNotEquals("$name is untranslated in zht", en.getValue(name), zht.getValue(name))
        }
    }

    @Test
    fun `every phrase the generic branch can name renders in the ui locale`() {
        assertTrue("the phrase table must not be empty", SERVER_ERROR_PHRASES.isNotEmpty())
        val zh = table("values-zh-rCN")
        val zht = table("values-zh-rTW")
        for (entry in SERVER_ERROR_PHRASES) {
            // A realistic server line: the phrase plus the dynamic tail the
            // server appends (a path, a command, a tool name).
            val line = "${entry.phrase}: /home/u/proj"
            // The en dictionary repeats the phrase, so the swap is a no-op there:
            // a reader of the source language sees the server's own words.
            assertEquals(
                "'${entry.phrase}' must survive the en locale untouched",
                line,
                shownSummary(line, "values"),
            )
            for ((locale, dict) in listOf("values-zh-rCN" to zh, "values-zh-rTW" to zht)) {
                val shown = shownSummary(line, locale)
                assertTrue("$locale dropped the server line for '${entry.phrase}'", shown.isNotEmpty())
                assertFalse(
                    "'${entry.phrase}' is still English in $locale: $shown",
                    shown.contains(entry.phrase, ignoreCase = true),
                )
                assertTrue(
                    "$locale lost the dynamic tail for '${entry.phrase}': $shown",
                    shown.endsWith("/home/u/proj"),
                )
                assertEquals(
                    "$locale must render the dictionary value for '${entry.phrase}'",
                    dict.getValue(entry.phrase) + ": /home/u/proj",
                    shown,
                )
            }
        }
    }

    @Test
    fun `the expanded body localizes the raw session error json too`() {
        // The body merges the human line with the raw `session.error` payload,
        // so the swap has to reach *inside* the JSON — that is where the
        // `type`/`status` the summary drops live, and it is selectable text a
        // user copies into a bug report.
        for (entry in SERVER_ERROR_PHRASES) {
            val detail = """{"type":"provider.invalid-request","message":"${entry.phrase}","status":400}"""
            val line = "${entry.phrase}: /home/u/proj"
            for (locale in listOf("values-zh-rCN", "values-zh-rTW")) {
                val body = shownBody(line, detail, locale)
                assertFalse(
                    "'${entry.phrase}' is still English in the $locale body: $body",
                    body.contains(entry.phrase, ignoreCase = true),
                )
                // The payload stays a payload: the JSON shape survives the swap.
                assertTrue("the body lost its raw JSON: $body", body.contains("\"status\":400"))
                assertTrue("the body lost the human line: $body", body.contains("/home/u/proj"))
            }
            // en is the identity mapping: the server's words, byte for byte.
            assertEquals(
                errorCardBody(line, detail),
                shownBody(line, detail, "values"),
            )
        }
    }

    @Test
    fun `no phrase leaks through the dictionary in zh or zht`() {
        // Every phrase once, in one line — the leak detector for the whole
        // table: after the swap no English phrase may survive, whatever order
        // the dictionary holds (a shorter phrase eating a longer one's prefix
        // would leave its tail English).
        val line = SERVER_ERROR_PHRASES.joinToString(" | ") { "${it.phrase}: /a/b" }
        for (locale in listOf("values-zh-rCN", "values-zh-rTW")) {
            val shown = ToolText.localizeServerPhrases(line, table(locale))
            for (entry in SERVER_ERROR_PHRASES) {
                assertFalse(
                    "'${entry.phrase}' leaked through in $locale",
                    shown.contains(entry.phrase, ignoreCase = true),
                )
            }
        }
        // The en locale rewrites nothing, so the table's own English must be
        // exactly what the server wrote — a mismatched search key would show up
        // here as a phrase that was never swapped in zh.
        assertEquals(line, ToolText.localizeServerPhrases(line, table("values")))
    }
}
