package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.timeline.SERVER_ERROR_PHRASES
import com.xiufeigo.opencodeshell.ui.session.timeline.ToolText
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The server phrase table ([SERVER_ERROR_PHRASES] + `error.server.*`): the
 * display half of a server-composed English failure.
 *
 * The server writes these sentences itself (`new Error(\`…\`)` / `message:`
 * under `_upstream/packages/core/src`) and sends no key, so a client-side table
 * is the only way to show them in the UI locale
 * ([ToolText.localizeServerPhrases]). These tests pin the properties that keep
 * the table honest, without a composition:
 *
 * 1. every table entry is wired to an `error.server.*` resource that exists in
 *    **all three** generated locale files — translated in zh/zht, identical in
 *    en, so the en locale is a no-op and cannot rewrite the server's words;
 * 2. no phrase is eaten by another phrase nested inside it (longest-first);
 * 3. a phrase whose server sentence carries a dynamic segment (`<path>`, a
 *    command line) leaves that segment exactly as the server wrote it.
 */
class ServerErrorPhraseTableTest {

    /** Same resolution the other source-reading tests use (module dir or repo root). */
    private fun source(relative: String): String {
        val file = File(relative).takeIf { it.isFile } ?: File("app/$relative")
        return file.readText()
    }

    private val enXml = source("src/main/res/values/strings_oc_i18n.xml")
    private val zhXml = source("src/main/res/values-zh-rCN/strings_oc_i18n.xml")
    private val zhtXml = source("src/main/res/values-zh-rTW/strings_oc_i18n.xml")

    /** `<string name="…">value</string>` → name to value, for one generated file. */
    private fun strings(xml: String): Map<String, String> =
        Regex("""<string name="([^"]+)">([^<]*)</string>""")
            .findAll(xml)
            .associate { it.groupValues[1] to unquote(it.groupValues[2]) }

    /**
     * `gen_i18n.ps1` wraps a value whose surrounding whitespace matters in double
     * quotes (aapt2 keeps the quoted whitespace and drops the quotes); the raw
     * XML text therefore carries them.
     */
    private fun unquote(value: String): String =
        if (value.length >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value.substring(1, value.length - 1)
        } else {
            value
        }

    /**
     * The `ServerErrorPhrase("<phrase>", R.string.<name>)` pairs as the Kotlin
     * source writes them. Read off the source because the resource **name** is
     * the one thing a JVM test cannot recover from a compiled `R` id — and it is
     * exactly the wiring worth pinning (a swapped `R.string` reference compiles
     * and would show an unrelated sentence).
     */
    private val wiredPairs: List<Pair<String, String>> = run {
        val body = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/timeline/ToolRenderers.kt")
            .substringAfter("internal val SERVER_ERROR_PHRASES")
            .substringBefore("\n@Composable")
        Regex("""ServerErrorPhrase\(\s*"([^"]*)",\s*R\.string\.(\w+),?\s*\)""")
            .findAll(body)
            .map { it.groupValues[1] to it.groupValues[2] }
            .toList()
    }

    /** A replacement that cannot itself contain a key, so it can never re-match. */
    private fun markers(): Map<String, String> =
        SERVER_ERROR_PHRASES
            .mapIndexed { index, entry -> entry.phrase to markerOf(entry.phrase, index) }
            .toMap(LinkedHashMap())

    private fun markerOf(phrase: String, index: Int = -1): String {
        val at = if (index >= 0) index else SERVER_ERROR_PHRASES.indexOfFirst { it.phrase == phrase }
        assertTrue("phrase not in the table: $phrase", at >= 0)
        return "§$at§"
    }

    @Test
    fun `the table is non-empty and free of duplicate phrases`() {
        assertTrue("the server phrase table must not be empty", SERVER_ERROR_PHRASES.isNotEmpty())
        val phrases = SERVER_ERROR_PHRASES.map { it.phrase }
        assertEquals("a duplicate key would shadow its twin", phrases.size, phrases.toSet().size)
        for (entry in SERVER_ERROR_PHRASES) {
            assertTrue("an empty phrase would match everywhere", entry.phrase.isNotEmpty())
        }
    }

    @Test
    fun `every phrase is wired to an error server string in all three locales`() {
        val en = strings(enXml)
        val zh = strings(zhXml)
        val zht = strings(zhtXml)
        assertEquals(
            "the source and the compiled table disagree",
            SERVER_ERROR_PHRASES.map { it.phrase },
            wiredPairs.map { it.first },
        )
        for ((phrase, name) in wiredPairs) {
            assertTrue("'$phrase' must live under error.server.*, not $name", name.startsWith("error_server_"))
            assertEquals("$name does not carry the server phrase it is wired to", phrase, en[name])
            assertTrue("$name missing in values-zh-rCN", zh.containsKey(name))
            assertTrue("$name missing in values-zh-rTW", zht.containsKey(name))
            assertNotEquals("$name is untranslated in zh", phrase, zh.getValue(name))
            assertNotEquals("$name is untranslated in zht", phrase, zht.getValue(name))
        }
    }

    @Test
    fun `no error server key is missing from a locale`() {
        // A key present in en but absent from zh/zht still compiles (the default
        // locale falls back) but would silently show English in that locale.
        val en = strings(enXml).keys.filter { it.startsWith("error_server_") }.toSet()
        val zh = strings(zhXml).keys
        val zht = strings(zhtXml).keys
        assertTrue(en.isNotEmpty())
        for (name in en) {
            assertTrue("$name missing in values-zh-rCN", name in zh)
            assertTrue("$name missing in values-zh-rTW", name in zht)
        }
    }

    @Test
    fun `the english table maps every phrase onto itself so nothing is rewritten`() {
        // The en dictionary repeats the phrase; `localizeServerPhrases` skips
        // identity pairs, so a reader of the source language sees the server's
        // sentence byte for byte.
        val identity = SERVER_ERROR_PHRASES.associate { it.phrase to it.phrase }
        for (entry in SERVER_ERROR_PHRASES) {
            assertEquals(entry.phrase, ToolText.localizeServerPhrases(entry.phrase, identity))
        }
        val line = "Could not find oldString in /a/b.ts. It must match exactly, " +
            "including whitespace and indentation."
        assertEquals(line, ToolText.localizeServerPhrases(line, identity))
    }

    @Test
    fun `no phrase is eaten by a shorter phrase nested inside it`() {
        // Every phrase, once, in one line: each must come out as its own marker,
        // in place, whatever order the map happens to hold.
        val line = SERVER_ERROR_PHRASES.joinToString(" | ") { it.phrase }
        val expected = SERVER_ERROR_PHRASES.indices.joinToString(" | ") { "§$it§" }
        assertEquals(expected, ToolText.localizeServerPhrases(line, markers()))
    }

    @Test
    fun `a dynamic path segment survives the swap`() {
        // `shell.ts:145/149`, `edit.ts:151`, `glob.ts:84-89`, `grep.ts:106` — the
        // server appends the path (or command line) it was given; only the static
        // half is a key, so the interpolated tail must come through untouched.
        val marker = markers()
        fun swapped(text: String) = ToolText.localizeServerPhrases(text, marker)
        assertEquals(
            "${markerOf("Working directory does not exist")}: /home/u/proj",
            swapped("Working directory does not exist: /home/u/proj"),
        )
        assertEquals(
            "${markerOf("Working directory is not a directory")}: /home/u/proj",
            swapped("Working directory is not a directory: /home/u/proj"),
        )
        assertEquals(
            "${markerOf("File not found")}: /a/b.ts",
            swapped("File not found: /a/b.ts"),
        )
        assertEquals(
            "${markerOf("Search path is not a directory")}: /srv",
            swapped("Search path is not a directory: /srv"),
        )
        assertEquals(
            "${markerOf("Unable to execute command")}: npm run build",
            swapped("Unable to execute command: npm run build"),
        )
        assertEquals(
            "${markerOf("Unable to edit")} /a/b.ts",
            swapped("Unable to edit /a/b.ts"),
        )
    }

    @Test
    fun `the generated zh dictionary renders real server failures end to end`() {
        // The values the app actually loads (the generated zh resources, not a
        // hand-written map) run through the real display path: the swap, then
        // `ToolErrorCard`'s head/detail split — which is why the swap has to
        // happen *before* the split, or the head would stay English while the
        // body changed language.
        val zh = strings(zhXml)
        val phrases = wiredPairs.associate { (phrase, name) -> phrase to zh.getValue(name) }
        assertEquals(
            "工作目录不是目录: /home/u/proj",
            ToolText.localizeServerPhrases("Working directory is not a directory: /home/u/proj", phrases),
        )
        val (head, detail) = ToolText.errorHeadDetail(
            ToolText.localizeServerPhrases("Working directory does not exist: /home/u/proj", phrases),
            "shell",
        )
        assertEquals("工作目录不存在", head)
        assertEquals("/home/u/proj", detail)
        val editLine = ToolText.localizeServerPhrases(
            "Could not find oldString in /a/b.ts. It must match exactly, " +
                "including whitespace and indentation.",
            phrases,
        )
        assertEquals(
            "无法在 /a/b.ts 中找到 oldString。必须完全匹配，包括空白和缩进。",
            editLine,
        )
        // The localized sentence carries no ASCII `": "`, so the whole line is
        // the card's head and the detail is empty — the split is a display
        // decision, not a second rewrite of the text.
        assertEquals(editLine.replaceFirstChar { it.uppercaseChar() }, ToolText.errorHeadDetail(editLine, "edit").first)
        assertEquals("", ToolText.errorHeadDetail(editLine, "edit").second)
    }

    @Test
    fun `the mid sentence path in the edit failure is preserved between the fragments`() {
        // `edit.ts:191`: `Could not find oldString in ${path}. It must match …` —
        // the path sits *between* the two keys, so this is the one phrase whose
        // swap could swallow it. Asserted against the dictionary's own wording
        // rather than markers, because the read has to stay a sentence.
        val zh = mapOf(
            "Could not find oldString in" to "无法在",
            ". It must match exactly, including whitespace and indentation." to
                " 中找到 oldString。必须完全匹配，包括空白和缩进。",
        )
        assertEquals(
            "无法在 /a/b.ts 中找到 oldString。必须完全匹配，包括空白和缩进。",
            ToolText.localizeServerPhrases(
                "Could not find oldString in /a/b.ts. It must match exactly, " +
                    "including whitespace and indentation.",
                zh,
            ),
        )
        // …and the generated resources keep the leading space that joins the path
        // to the translated sentence (gen_i18n.ps1 quotes it, aapt2 keeps the
        // quoted whitespace). A trimmed value would glue the path to the words.
        assertEquals(
            " 中找到 oldString。必须完全匹配，包括空白和缩进。",
            strings(zhXml).getValue("error_server_oldStringNotFoundTail"),
        )
        assertTrue(
            "the zht tail fragment must keep its leading space",
            strings(zhtXml).getValue("error_server_oldStringNotFoundTail").startsWith(" "),
        )
    }

    @Test
    fun `both error cards display the localized line`() {
        // The two call sites: the tool error card and the session error card route
        // their *displayed* text through the table. The tool card's copy payload
        // stays on `cleaned` — the server's own words, for a bug report.
        val toolCard = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/timeline/ToolRenderers.kt")
        assertTrue(toolCard.contains("ToolText.localizeServerPhrases(cleaned, phrases)"))
        assertTrue(toolCard.contains("internal fun serverErrorPhrases()"))
        val sessionCard = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/timeline/SessionErrorCard.kt")
        assertTrue(sessionCard.contains("val phrases = serverErrorPhrases()"))
        assertTrue(sessionCard.contains("ToolText.localizeServerPhrases(summary, phrases)"))
        assertTrue(sessionCard.contains("ToolText.localizeServerPhrases(fullBody, phrases)"))
        assertTrue(sessionCard.contains("text = shownSummary"))
        assertTrue(sessionCard.contains("text = shownBody"))
        // The legacy banner is the other surface of the same failure (v21 P3:
        // the two must not call one failure by two names), so its line is
        // localized from the same table through the `Resources` overload.
        val legacyBanner = source("src/main/java/com/xiufeigo/opencodeshell/ChatView.kt")
        assertTrue(
            legacyBanner.contains(
                "ToolText.localizeServerPhrases(error, serverErrorPhrases(resources))",
            ),
        )
    }
}
