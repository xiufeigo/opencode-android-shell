package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.palette.PaletteCommand
import com.xiufeigo.opencodeshell.ui.palette.PaletteEntry
import com.xiufeigo.opencodeshell.ui.palette.PaletteSources
import com.xiufeigo.opencodeshell.ui.palette.SessionCommands
import com.xiufeigo.opencodeshell.ui.palette.appCommands
import com.xiufeigo.opencodeshell.ui.palette.commandEntries
import com.xiufeigo.opencodeshell.ui.palette.commandIdSearchTerms
import com.xiufeigo.opencodeshell.ui.palette.matchesEntry
import com.xiufeigo.opencodeshell.ui.palette.searchEntries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v36 tC: the palette is searchable by a command's **identity**, not only by its
 * localized title.
 *
 * The tester's report (`research/v36_verify/tC/tC_04_palette_search_debug.png`):
 * on a Chinese device the palette answered `debug` with 「没有找到结果」. The row
 * exists — `debugBar.toggle` — but `matchesEntry` read upstream's
 * `[title, description, category]` (`dialog.tsx:25-28`), and all three are
 * localized, so 「切换调试栏」 was findable by 「调试」 and by nothing a developer
 * would type. The same gap hid `export`, `terminal`, `browser` and the rest of
 * the ~40 commands behind the palette.
 *
 * The fix widens the haystack with two dimensions that come from the
 * registration itself — never a hand-kept alias table:
 *
 * * the words of the command id ([commandIdSearchTerms]),
 * * the English source copy the Compose layer reads back from the `values/`
 *   table ([PaletteEntry.searchTerms]).
 *
 * The pure half is asserted directly; the half that needs the shipped strings is
 * asserted against the `values` and `values-zh-rCN` resource tables plus the
 * `R.string` field names, i.e. the copy the shell actually ships, so the
 * assertions cannot drift from the build.
 */
class PaletteSearchTest {

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("missing $relative")

    // ------------------------------------------------------- the id dimension

    @Test
    fun `id words split on camel humps and dots`() {
        assertEquals(
            listOf("debugbar.toggle", "debug bar toggle", "debug", "bar", "toggle"),
            commandIdSearchTerms("command:debugBar.toggle"),
        )
        // `copyID` — the hump split keeps the acronym as its own word.
        assertEquals(
            listOf("session.copyid", "session copy id", "session", "copy", "id"),
            commandIdSearchTerms("command:session.copyID"),
        )
        assertEquals(
            listOf("agent.cycle.reverse", "agent cycle reverse", "agent", "cycle", "reverse"),
            commandIdSearchTerms("command:agent.cycle.reverse"),
        )
        // `command:` is the entry identity, not a word of the id: matching it
        // would make the query "command" hit every row in the palette.
        assertFalse(commandIdSearchTerms("command:debugBar.toggle").contains("command"))
        assertEquals(emptyList<String>(), commandIdSearchTerms("command:"))
    }

    @Test
    fun `id words are a command dimension only`() {
        // A file row's id is `file:` + the path its title already shows, and a
        // session row's is `session:` + server + session id — widening those
        // would only add "file" / "session" / the server key to every query.
        val file = PaletteEntry.file("src/main/App.kt")
        assertTrue(matchesEntry(file, "app.kt"))
        assertFalse(matchesEntry(file, "file:src"))

        val session = PaletteEntry.session("srv", "ses_1", "New session")
        assertTrue(matchesEntry(session, "new"))
        assertFalse(matchesEntry(session, "session:srv"))
    }

    // ------------------------------------------- the English dimension (pure)

    @Test
    fun `a chinese titled command answers to its english name`() {
        // The row the tester could not find: the title is the zh copy, and the
        // search terms are what `stringResolvedEntries` reads back from
        // `values/` for the same resource id.
        val row = PaletteEntry.command(
            id = "debugBar.toggle",
            title = "切换调试栏",
            searchTerms = listOf("Toggle debug bar"),
        )

        assertTrue(matchesEntry(row, "debug"))
        assertTrue(matchesEntry(row, "Debug"))
        assertTrue(matchesEntry(row, "debugBar"))
        assertTrue(matchesEntry(row, "toggle debug bar"))
        assertTrue(matchesEntry(row, "bar"))
        // The localized copy keeps working — this is a widening, not a switch.
        assertTrue(matchesEntry(row, "调试"))
        assertTrue(matchesEntry(row, "切换调试栏"))
        assertFalse(matchesEntry(row, "terminal"))
    }

    @Test
    fun `a row without search terms keeps upstreams haystack`() {
        val row = PaletteEntry.command(id = "settings.open", title = "Open settings", category = "Commands")
        assertTrue(matchesEntry(row, "open"))
        assertTrue(matchesEntry(row, "settings"))
        assertFalse(matchesEntry(row, "terminal"))
    }

    // ------------------------------------------ the shipped registry (both locales)

    /** The shipped registry — `ui/App.kt`'s `appCommands(...) + sessionCommands`. */
    private fun registry(): List<PaletteCommand> =
        appCommands(
            onOpenSettings = {},
            onOpenTerminal = {},
            onNewSession = {},
            onNewWorkspace = {},
            onPreviousSession = {},
            onNextSession = {},
            onToggleReview = {},
            onOpenFilePicker = {},
            onToggleFileTree = {},
            onToggleSummary = {},
            onToggleDebugBar = {},
        ) + SessionCommands.sessionCommands()

    /**
     * The rows the shell ships, projected the way `stringResolvedEntries`
     * projects them: the UI locale for `title` / `description`, the `values/`
     * table for `searchTerms`.
     */
    private fun shippedEntries(locale: String): List<PaletteEntry> {
        val english = valueOrNull(stringTable("values"))
        val localized = valueOrNull(stringTable(locale))
        return commandEntries(
            commands = registry(),
            resolveEnglish = english,
            resolveTitle = { resId -> localized(resId).orEmpty() },
        )
    }

    /** Resource id → value, `null` when the `R.string` table has no such key. */
    private fun valueOrNull(table: Map<String, String>): (Int) -> String? =
        { resId -> resIds[resId]?.let(table::get) }

    private fun shippedIds(locale: String, query: String): List<String> =
        searchEntries(PaletteSources(commands = shippedEntries(locale)), query).map { it.id }

    @Test
    fun `the shipped chinese palette is searchable by the upstream english name`() {
        // The exact query the tester typed, in the locale they typed it in.
        val debugIds = shippedIds("values-zh-rCN", "debug")
        val phraseIds = shippedIds("values-zh-rCN", "toggle debug bar")
        val camelIds = shippedIds("values-zh-rCN", "debugBar")
        assertTrue("debug -> $debugIds", "command:debugBar.toggle" in debugIds)
        assertTrue("toggle debug bar -> $phraseIds", "command:debugBar.toggle" in phraseIds)
        assertTrue("debugBar -> $camelIds", "command:debugBar.toggle" in camelIds)

        assertTrue("export", "command:session.export" in shippedIds("values-zh-rCN", "export"))
        assertTrue("terminal", "command:terminal.toggle" in shippedIds("values-zh-rCN", "terminal"))
        assertTrue("term", "command:terminal.toggle" in shippedIds("values-zh-rCN", "term"))
        assertTrue("browser", "command:browser.open" in shippedIds("values-zh-rCN", "browser"))

        // The Chinese dimension is untouched: the localized title still matches.
        assertTrue("调试", "command:debugBar.toggle" in shippedIds("values-zh-rCN", "调试"))
        assertTrue("导出", "command:session.export" in shippedIds("values-zh-rCN", "导出"))
        assertTrue("终端", "command:terminal.toggle" in shippedIds("values-zh-rCN", "终端"))
    }

    @Test
    fun `the shipped english palette keeps matching on its titles`() {
        val ids = shippedIds("values", "debug")
        assertTrue("command:debugBar.toggle" in ids)
        assertTrue("command:session.export" in shippedIds("values", "export"))
        assertTrue("command:terminal.toggle" in shippedIds("values", "terminal"))
        assertTrue("command:browser.open" in shippedIds("values", "browser"))
    }

    @Test
    fun `the shipped english copy is what makes the id-only queries land`() {
        // Proof the second dimension is load-bearing: "summarize" is carried
        // only by the English description of `session.compact` (the zh copy is
        // 「总结会话以减少上下文大小」), and no id word spells it.
        val commands = registry()
        val withoutEnglish = commandEntries(commands, resolveTitle = { "标题" })

        assertTrue(
            "summarize",
            searchEntries(PaletteSources(commands = shippedEntries("values-zh-rCN")), "summarize")
                .any { it.id == "command:session.compact" },
        )
        assertTrue(
            searchEntries(PaletteSources(commands = withoutEnglish), "summarize").isEmpty(),
        )
        // …while the Chinese copy still answers to its own words.
        assertTrue(
            "压缩",
            searchEntries(PaletteSources(commands = shippedEntries("values-zh-rCN")), "压缩")
                .any { it.id == "command:session.compact" },
        )
    }

    // ------------------------------------------------------- the Compose wiring

    @Test
    fun `the compose projection fills the search terms from the default table`() {
        val palette = source("src/main/java/com/xiufeigo/opencodeshell/ui/palette/CommandPalette.kt")
        val projection = palette.substringAfter("fun stringResolvedEntries")
        assertTrue(projection.contains("searchTerms = commandEnglishTerms(command, englishTerm)"))

        // `stringResource` resolves the UI locale; the English dimension needs
        // the resource default, so the helper must force it.
        val helper = palette.substringAfter("internal fun englishStrings()")
        assertTrue(helper.contains("LocaleList(Locale.ENGLISH)"))
        assertTrue(helper.contains("createConfigurationContext"))
    }

    // ------------------------------------------------------------- the fixtures

    /**
     * Resource name → value for one locale directory, with Android's per-key
     * fallback to `values/` (a missing translation is not a missing string).
     */
    private fun stringTable(dir: String): Map<String, String> =
        parse("src/main/res/values/strings.xml") +
            parse("src/main/res/values/strings_oc_i18n.xml") +
            parse("src/main/res/$dir/strings.xml") +
            parse("src/main/res/$dir/strings_oc_i18n.xml")

    private fun parse(relative: String): Map<String, String> {
        val body = source(relative).replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
        return Regex("""<string name="([^"]+)">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(body)
            .associate { it.groupValues[1] to unescape(it.groupValues[2]) }
    }

    private fun unescape(value: String): String = value
        .replace("\\'", "'")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")

    /** `R.string`'s field names: a resource name IS its field name. */
    private val resIds: Map<Int, String> by lazy {
        R.string::class.java.declaredFields
            .filter { it.type == Int::class.javaPrimitiveType }
            .associate { it.getInt(null) to it.name }
    }
}
