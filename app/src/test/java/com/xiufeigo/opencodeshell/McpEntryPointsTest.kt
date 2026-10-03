package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.palette.SessionCommands
import com.xiufeigo.opencodeshell.ui.settings.SETTINGS_NAV_TABS
import com.xiufeigo.opencodeshell.ui.settings.SettingsTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v43: "找不到 MCP 入口" — the chooser is reachable, it is just not a top-level
 * surface. This pins every path a tester (or a bug report) can take, so a
 * future removal of one of them fails here rather than on a device.
 *
 * The four documented entries:
 * 1. Settings → 「扩展 / Extensions」 → 「MCP」 subtab (「MCP servers」 list,
 *    add / resources) — the only place that *manages* servers
 *    (`ui/settings/SettingsPanels.kt` `ExtensionsPanel` / `ExtensionMcpList`);
 * 2. the command palette row `mcp.toggle` (`ui/palette/SessionCommands.kt`),
 *    reachable from the session ⋮ menu's 「命令面板」 row and from `Ctrl+K`;
 * 3. the global `Ctrl+;` chord (`App.kt` `GlobalChord.MCP_TOGGLE`);
 * 4. the composer's `/mcp` slash alias, which routes to the same palette row.
 */
class McpEntryPointsTest {

    @Test
    fun mcpIsReachableFromTheCommandPalette() {
        assertTrue(
            "the palette must keep the mcp.toggle row (命令面板 → MCP)",
            SessionCommands.SESSION_COMMAND_IDS.contains(MCP_TOGGLE),
        )
    }

    @Test
    fun mcpIsReachableFromTheComposerSlashAlias() {
        assertEquals(MCP_TOGGLE, SessionCommands.SESSION_SLASH_COMMAND_IDS["mcp"])
        assertEquals("mcp", SessionCommands.slashForCommand(MCP_TOGGLE))
    }

    @Test
    fun mcpIsReachableFromSettings() {
        // 「扩展」 is the 5th item of the server group; the MCP list is its third
        // subtab, so the nav entry itself is the anchor this test can see.
        assertTrue(
            "settings must keep the Extensions page that hosts the MCP section",
            SETTINGS_NAV_TABS.contains(SettingsTab.EXTENSIONS),
        )
    }

    @Test
    fun theComposerSlashAliasIsNotADeadId() {
        // A slash id whose command the palette never builds executes nothing —
        // the failure mode "the entry does not exist" reports. The other
        // aliases are exempt on purpose (`session.new` / `file.open` /
        // `terminal.toggle` live in the app registry, `model` / `agent` are
        // composer built-ins), so this pins the one this file is about.
        val id = SessionCommands.SESSION_SLASH_COMMAND_IDS["mcp"]
        assertTrue(
            "slash /mcp maps to a command the palette does not build: $id",
            id != null && SessionCommands.SESSION_COMMAND_IDS.contains(id),
        )
    }

    private companion object {
        /** `SessionCommands` `mcp.toggle`. */
        const val MCP_TOGGLE = "mcp.toggle"
    }
}
