package com.xiufeigo.opencodeshell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The three `strings.xml` locales must expose the same key set. Android falls
 * back silently when a translation is missing, so an untranslated key ships
 * English in zh with no build error — four such keys shipped before this test.
 */
class StringsLocaleParityTest {

    private val locales = listOf("values", "values-zh-rCN", "values-zh-rTW")

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("missing $relative")

    /** Resource names of one locale's `strings.xml`, XML comments stripped. */
    private fun keys(dir: String): Set<String> {
        val body = source("src/main/res/$dir/strings.xml")
            .replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
        return Regex("""name="([^"]+)"""")
            .findAll(body)
            .mapTo(mutableSetOf()) { it.groupValues[1] }
    }

    private fun value(dir: String, key: String): String =
        Regex("""<string name="$key">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .find(source("src/main/res/$dir/strings.xml"))
            ?.groupValues
            ?.get(1)
            ?: error("$dir missing $key")

    @Test
    fun `every locale exposes exactly the same string keys`() {
        val base = keys("values")
        for (dir in locales) {
            val localized = keys(dir)
            assertEquals(
                "$dir differs (missing=${base - localized}, extra=${localized - base})",
                base,
                localized,
            )
        }
    }

    @Test
    fun `settings and terminal keys added without a translation pass are localised`() {
        val lateKeys = listOf(
            "oc_settings_mcp_unsupported",
            "oc_settings_plugins_empty",
            "oc_terminal_move_left",
            "oc_terminal_move_right",
        )
        for (dir in listOf("values-zh-rCN", "values-zh-rTW")) {
            for (key in lateKeys) {
                assertNotEquals(
                    "$dir/$key is still the English copy",
                    value("values", key),
                    value(dir, key),
                )
            }
        }
    }

    /**
     * BUG-1 (v30): the workspaces row's meta line rendered as
     * `0 中有 opencode-android-shell 个会话` — the count and the project swapped —
     * while the filtered variant (`%1$s 个会话`) was right. `gen_i18n.ps1` numbers
     * `{{var}}` by first appearance *per locale*, and the Kotlin call site passes
     * `(count, project)` in the English order, so a translation that puts the
     * project first must either place the count first as well or reference it as
     * `%2$s`. The generator's own order is the one the source JSON feeds it, so
     * this pins the resource the shell actually ships.
     */
    @Test
    fun `the worktree session label keeps the count at index 1 in every locale`() {
        val xml = Regex("""<string name="settings_workspaces_sessions_other">(.*?)</string>""")
        for (dir in locales) {
            val body = source("src/main/res/$dir/strings_oc_i18n.xml")
            val text = xml.find(body)?.groupValues?.get(1) ?: error("$dir missing the label")
            assertTrue("$dir must name the count as %1\$s (got '$text')", text.startsWith("%1\$s"))
            assertTrue("$dir must name the project as %2\$s (got '$text')", text.contains("%2\$s"))
        }
    }
}
