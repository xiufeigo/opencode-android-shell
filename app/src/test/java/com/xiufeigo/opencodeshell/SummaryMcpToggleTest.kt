package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.summary.SessionSummaryModel
import com.xiufeigo.opencodeshell.ui.session.summary.SessionSummaryModel.ServiceItem
import com.xiufeigo.opencodeshell.ui.session.summary.SummaryServiceSource
import com.xiufeigo.opencodeshell.ui.settings.McpPageRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * F-3 / A-6: the summary server card's MCP `Switch` rows and its
 * `ServiceConfigLink` footer (`session/summary/server-panel.tsx:157-270,440-480`),
 * plus the source pins for the two write surfaces the wave added.
 */
class SummaryMcpToggleTest {

    private fun source(relative: String): String {
        val file = File(relative).takeIf { it.isFile } ?: File("app/$relative")
        return file.readText()
    }

    // ------------------------------------------------------- row state (F-3)

    @Test
    fun anMcpRowReadsItsSwitchPositionFromTheRawStatus() {
        // `connected` and `disabled` both tokenise to "no label", so the token
        // alone cannot position the Switch (`server-panel.tsx:225-227`).
        assertTrue(ServiceItem("a", status = null, rawStatus = "connected").enabled)
        assertFalse(ServiceItem("b", status = null, rawStatus = "disabled").enabled)
        assertFalse(ServiceItem("c", rawStatus = "Disabled").enabled)
        assertTrue(ServiceItem("d", status = "failed", rawStatus = "failed").enabled)
        // Plugin/skill/LSP rows carry no live status and no Switch.
        assertTrue(ServiceItem("e").enabled)
    }

    @Test
    fun aPendingRowIsTheServersOwnInFlightConnect() {
        assertTrue(ServiceItem("a", rawStatus = "pending").pending)
        assertTrue(ServiceItem("a", rawStatus = "Pending").pending)
        assertFalse(ServiceItem("a", rawStatus = "connected").pending)
        assertFalse(ServiceItem("a").pending)
    }

    @Test
    fun mcpItemsCarryRawStatusAndTheFailureMessage() {
        val items = SummaryServiceSource.mcpItems(
            listOf(
                McpPageRow("zeta", "connected", null),
                McpPageRow("alpha", "failed", "boom"),
                McpPageRow("beta", "disabled", null),
            ),
        )
        assertEquals(listOf("alpha", "beta", "zeta"), items.map { it.name })
        assertEquals(listOf("failed", null, null), items.map { it.status })
        assertEquals(listOf("failed", "disabled", "connected"), items.map { it.rawStatus })
        assertEquals("boom", items.first().error)
        assertNull(items.last().error)
    }

    // ------------------------------------------- configuration path (A-6)

    @Test
    fun theConfigPathPrefersTheDocumentCarryingTheService() {
        // `server-panel.tsx:445-452`: over the `.json`/`.jsonc` documents, the
        // last one with `info[service]`, else the last document at all.
        val config = """
            [
              {"type":"document","path":"/home/u/.config/opencode/opencode.json",
               "info":{"mcp":{"servers":{"a":{}}}}},
              {"type":"directory","path":"/home/u/.config/opencode"},
              {"type":"document","path":"/home/u/.config/opencode/opencode.jsonc",
               "info":{"shell":"bash"}}
            ]
        """.trimIndent()
        assertEquals(
            "/home/u/.config/opencode/opencode.json",
            SessionSummaryModel.configPath(config, SessionSummaryModel.SERVICE_MCP),
        )
        // No service key anywhere → the last document (the live 4095 page has
        // exactly this shape: two documents, neither with `info.mcp`).
        assertEquals(
            "/home/u/.config/opencode/opencode.jsonc",
            SessionSummaryModel.configPath(config, SessionSummaryModel.SERVICE_LSP),
        )
        // The last document wins even when an earlier one carried the service.
        val both = """
            [
              {"type":"document","path":"/a/opencode.json","info":{"mcp":{}}},
              {"type":"document","path":"/b/opencode.jsonc","info":{"mcp":{}}}
            ]
        """.trimIndent()
        assertEquals("/b/opencode.jsonc", SessionSummaryModel.configPath(both, SessionSummaryModel.SERVICE_MCP))
    }

    @Test
    fun theConfigPathIgnoresNonDocumentsAndOtherSuffixes() {
        val config = """
            [
              {"type":"directory","path":"/a/opencode.json","info":{"mcp":{}}},
              {"type":"document","path":"/a/opencode.toml","info":{"mcp":{}}},
              {"type":"document","path":"/a/notes.txt","info":{}}
            ]
        """.trimIndent()
        assertNull(SessionSummaryModel.configPath(config, SessionSummaryModel.SERVICE_MCP))
        assertNull(SessionSummaryModel.configPath(null, SessionSummaryModel.SERVICE_MCP))
        assertNull(SessionSummaryModel.configPath("", SessionSummaryModel.SERVICE_MCP))
        assertNull(SessionSummaryModel.configPath("not json", SessionSummaryModel.SERVICE_MCP))
        // The `{entries}` envelope the panel's reader accepts works here too.
        val wrapped = """{"entries":[{"type":"document","path":"/a/opencode.json","info":{}}]}"""
        assertEquals("/a/opencode.json", SessionSummaryModel.configPath(wrapped, SessionSummaryModel.SERVICE_MCP))
    }

    @Test
    fun theServiceIDsAreUpstreamsInfoKeys() {
        assertEquals("mcp", SessionSummaryModel.SERVICE_MCP)
        assertEquals("plugins", SessionSummaryModel.SERVICE_PLUGINS)
        assertEquals("skills", SessionSummaryModel.SERVICE_SKILLS)
        assertEquals("lsp", SessionSummaryModel.SERVICE_LSP)
    }

    // ------------------------------------------------------------- source pins

    @Test
    fun theMcpSubmenuRendersTheSwitchAndThePanelWiresAToggleHost() {
        val panel = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/summary/SessionSummaryPanel.kt")
        assertTrue(panel.contains("OcSwitch("))
        assertTrue(panel.contains("onToggle = mcpToggle?.toggle"))
        assertTrue(panel.contains("busy = mcpToggle?.busy.orEmpty()"))
        assertTrue(panel.contains("mcpToggle: McpToggleHost? = null"))
        assertTrue(panel.contains("rememberMcpToggle()"))
        // The write must re-read the catalog, like upstream's invalidate + sync.
        assertTrue(panel.contains("rememberSummaryCatalogs(directory, toggleHost?.revision ?: 0)"))
    }

    @Test
    fun theChooserRowSwitchIsTheThemedControl() {
        // `/mcp` → `DialogSelectMcp` (`ui/overlay/McpChooserDialog`): the chooser
        // is a bare card on `DialogHost`, so its row switch is drawn by the
        // dialog itself. A bare M3 `Switch` defaults to `SwitchDefaults.colors()`
        // — a light-tinted track/thumb the shell never re-themes, i.e. a white
        // knob on the dark card. It is the same control the settings rows and
        // the summary's MCP rows use.
        val chooser = source("src/main/java/com/xiufeigo/opencodeshell/ui/overlay/McpChooserDialog.kt")
        assertFalse(chooser.contains("androidx.compose.material3.Switch"))
        val row = chooser.substringAfter("private fun McpChooserRow(").substringBefore("/**")
        assertTrue(row.contains("OcSwitch("))
        assertFalse("the M3 default switch is back", Regex("(?<!Oc)Switch\\(").containsMatchIn(row))
        assertTrue(row.contains("onCheckedChange = onToggle"))
        assertTrue(row.contains("enabled = toggleEnabled && !busy"))

        // One switch implementation, so the two surfaces cannot drift: the
        // control is `SettingsComponents.OcSwitch` (themed tokens + `Role.Switch`).
        val components = source("src/main/java/com/xiufeigo/opencodeshell/ui/settings/SettingsComponents.kt")
        assertTrue(
            components.contains(
                "fun OcSwitch(checked: Boolean, enabled: Boolean = true, onCheckedChange: (Boolean) -> Unit)",
            ),
        )
        val switch = components.substringAfter("fun OcSwitch(").substringBefore("KeybindChip")
        assertTrue(switch.contains("track = if (checked) c.accent else c.layer03"))
        assertTrue(switch.contains("role = Role.Switch"))
    }

    @Test
    fun theChooserSearchFieldDrawsItsCaretFromTheTokens() {
        // `List`'s `search.autofocus` focuses this field the moment the chooser
        // opens (`mcp-dialog.tsx:44`), and Compose's `BasicTextField` defaults
        // `cursorBrush` to `SolidColor(Color.Black)` — the shell never re-themes
        // it, so on the dark theme's `bgDeep` (#080808) the blinking caret was
        // invisible. It was the only field in the app that did not pass a token.
        val chooser = source("src/main/java/com/xiufeigo/opencodeshell/ui/overlay/McpChooserDialog.kt")
        assertTrue(chooser.contains("import androidx.compose.ui.graphics.SolidColor"))
        val field = chooser.substringAfter("BasicTextField(").substringBefore("decorationBox")
        // The caret follows the field's own colour, like upstream's browser
        // default (`text-field.css:6` `color: var(--text-strong)`).
        assertTrue(field.contains("textStyle = TextStyle(color = c.textBase"))
        assertTrue(field.contains("cursorBrush = SolidColor(c.textBase)"))
    }

    @Test
    fun everyServiceSubmenuClosesWithTheConfigFooter() {
        val panel = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/summary/SessionSummaryPanel.kt")
        val body = panel.substringAfter("private fun ServiceSubmenus(").substringBefore("private fun ServiceSubmenusOf(")
        for (service in listOf("SERVICE_MCP", "SERVICE_PLUGINS", "SERVICE_SKILLS", "SERVICE_LSP")) {
            assertTrue("$service needs its config footer", body.contains("configService = SessionSummaryModel.$service"))
        }
        assertTrue(panel.contains("R.string.oc_summary_copy_config_path"))
        assertTrue(panel.contains("SessionSummaryModel.configPath(configJson, service)"))
        assertTrue(panel.contains("rememberConfigPathCopy()"))
    }

    @Test
    fun theActionsHitTheTwoRoutesTheAuditNames() {
        val actions = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/summary/SessionSummaryActions.kt")
        assertTrue(actions.contains("if (enabled) api.mcpConnect(name) else api.mcpDisconnect(name)"))
        assertTrue(actions.contains("class McpToggleHost("))
        assertTrue(actions.contains("clipboard.setText(AnnotatedString(path))"))
        assertTrue(actions.contains("R.string.oc_summary_config_missing"))
    }

    @Test
    fun theNewSessionPageCarriesTheDraftSwitchesGitChipAndTips() {
        val screen = source("src/main/java/com/xiufeigo/opencodeshell/ui/home/NewSessionScreen.kt")
        // F-2: the draft list + the apply-at-create path.
        assertTrue(screen.contains("NewSessionMcpDraft.servers(JSONArray(api.mcp(destination)).toString())"))
        assertTrue(screen.contains("applyMcpDraft(serverStore, destination, mcpStates)"))
        assertTrue(screen.contains("OcSwitch(checked = enabled, enabled = !busy, onCheckedChange = onToggle)"))
        assertTrue(screen.contains("R.string.oc_new_session_mcp_on_creation"))
        // A-11: git status, the summary popover and the tips row.
        assertTrue(screen.contains("api.vcs(destination)"))
        assertTrue(screen.contains("GitStatusChip("))
        assertTrue(screen.contains("R.string.oc_new_session_git_none"))
        assertTrue(screen.contains("SummaryToggle("))
        assertTrue(screen.contains("NewSessionSummaryCard("))
        assertTrue(screen.contains("NewSessionTipRow("))
        assertTrue(screen.contains("R.string.oc_home_provider_tip"))
        assertTrue(screen.contains("R.string.common_dismiss"))
        // Item 5: the workspace menu's dark-surface quartet.
        val menu = screen.substringAfter("DropdownMenu(").substringBefore(") {")
        assertTrue(menu, menu.contains("containerColor = c.layer01"))
        assertTrue(menu, menu.contains("tonalElevation = 0.dp"))
        assertTrue(menu, menu.contains("border = BorderStroke(0.5.dp, c.borderMuted)"))
    }
}
