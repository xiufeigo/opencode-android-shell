package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.palette.ocPaletteRowDescription
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v44 真机批：命令面板里「Toggle MCPs」的标题与描述渲染成两行一模一样的字
 * (`research/v44_verify/tE/tE_09_mcpsearch.png`)。
 *
 * 原因不在渲染层：上游词典里 `command.mcp.toggle` 与
 * `command.mcp.toggle.description` 就是同一句 "Toggle MCPs"（三种语言都是），
 * 行把描述当成补充说明画在了标题下面，于是同一个词出现两次。
 *
 * 修法：描述只在**真的补充了信息**时才渲染——空描述、或只是把标题重说一遍的
 * 描述，一律当作没有描述。
 */
class PaletteRowDescriptionTest {

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("missing $relative")

    private fun strings(relative: String): Map<String, String> =
        Regex("""<string name="([^"]+)">(.*?)</string>""")
            .findAll(source(relative))
            .associate { it.groupValues[1] to it.groupValues[2] }

    // ---- the rule -----------------------------------------------------------

    @Test
    fun aDescriptionThatOnlyRepeatsTheTitleIsNotRendered() {
        assertNull(ocPaletteRowDescription("Toggle MCPs", "Toggle MCPs"))
    }

    @Test
    fun aDescriptionThatAddsSomethingIsRendered() {
        assertEquals(
            "Enable or disable MCP servers",
            ocPaletteRowDescription("Toggle MCPs", "Enable or disable MCP servers"),
        )
    }

    @Test
    fun anAbsentOrBlankDescriptionIsNotRendered() {
        assertNull(ocPaletteRowDescription("Toggle MCPs", null))
        assertNull(ocPaletteRowDescription("Toggle MCPs", ""))
        assertNull(ocPaletteRowDescription("Toggle MCPs", "   "))
    }

    @Test
    fun theComparisonIgnoresSurroundingWhitespace() {
        assertNull(ocPaletteRowDescription("Toggle MCPs", "  Toggle MCPs\n"))
        assertEquals("Enable MCPs", ocPaletteRowDescription("Toggle MCPs", "  Enable MCPs  "))
    }

    // ---- the wiring ---------------------------------------------------------

    @Test
    fun bothProjectionsDropATitleCopyingDescription() {
        val model = source("src/main/java/com/xiufeigo/opencodeshell/ui/palette/PaletteModel.kt")
        assertTrue(
            "the JVM projection must run the description through the rule",
            model.contains("ocPaletteRowDescription(title, command.descriptionResId?.let(resolveTitle))"),
        )
        val compose = source("src/main/java/com/xiufeigo/opencodeshell/ui/palette/CommandPalette.kt")
        assertTrue(
            "the Compose projection must run the description through the rule",
            compose.substringAfter("fun stringResolvedEntries")
                .contains("description = ocPaletteRowDescription(title, description)"),
        )
    }

    // ---- the fixture this batch came from -----------------------------------

    @Test
    fun upstreamShipsTheMcpToggleDescriptionAsACopyOfItsTitle() {
        // The duplication is upstream's, in all three locales — which is why the
        // fix has to live in the row projection instead of one string table.
        listOf(
            "src/main/res/values/strings_oc_i18n.xml",
            "src/main/res/values-zh-rCN/strings_oc_i18n.xml",
            "src/main/res/values-zh-rTW/strings_oc_i18n.xml",
        ).forEach { table ->
            val strings = strings(table)
            val title = requireNotNull(strings["command_mcp_toggle"]) { "no title in $table" }
            val description = requireNotNull(strings["command_mcp_toggle_description"]) { "no description in $table" }
            assertTrue(
                "$table is expected to repeat the title, so the row must drop the second line",
                ocPaletteRowDescription(title, description) == null,
            )
        }
    }
}
