package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.settings.GENERAL_PANEL
import com.xiufeigo.opencodeshell.ui.settings.HIDDEN_SETTINGS
import com.xiufeigo.opencodeshell.ui.settings.SettingsControl
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B-9: the composer's agent control and the General page's "Show agent" row.
 *
 * Upstream keeps the two in step through one memo:
 * `agentsVisible = settings.visibility.customAgents() || hasCustomAgent(list())`
 * (`providers/models/selection.tsx:94`), where `hasCustomAgent` is
 * `native === false` (`providers/models/agent.ts:1-3`) — and the composer
 * renders no agent control at all while that is false
 * (`composer/model.ts:367-376` → `editor.tsx:284`).
 */
class AgentPickerVisibilityTest {

    private fun agent(name: String, native: Boolean?, mode: String = "primary", hidden: Boolean = false) =
        OpenCodeClient.Agent(name = name, description = "", mode = mode, native = native, hidden = hidden)

    @Test
    fun hasCustomAgentRequiresAnExplicitNonNativeAgent() {
        // Upstream's own cases (`providers/models/agent.test.ts:4-12`).
        assertTrue(OpenCodeClient.Agent.hasCustomAgent(listOf(agent("build", true), agent("custom", false))))
        assertFalse(OpenCodeClient.Agent.hasCustomAgent(listOf(agent("build", true), agent("plan", null))))
        assertFalse(OpenCodeClient.Agent.hasCustomAgent(emptyList()))
    }

    @Test
    fun agentsVisibleIsTheSettingOrACustomAgent() {
        val builtins = listOf(agent("build", true), agent("plan", true))
        val withCustom = builtins + agent("review", false)
        // The switch is on → visible regardless of the catalog.
        assertTrue(OpenCodeClient.Agent.visible(builtins, showCustomAgents = true))
        // Off and nothing custom → hidden (upstream's default: the setting is
        // `false`, `settings/model.tsx:249`).
        assertFalse(OpenCodeClient.Agent.visible(builtins, showCustomAgents = false))
        // Off, but the server really has one → visible anyway.
        assertTrue(OpenCodeClient.Agent.visible(withCustom, showCustomAgents = false))
    }

    @Test
    fun nativeStaysATriStateFromTheWire() {
        // A payload without the key must NOT read as "custom": that is what the
        // v2 catalog looks like, and it would pin the chip on forever.
        val absent = OpenCodeClient.Agent.fromJson(JSONObject("""{"name":"build","mode":"primary"}"""))
        assertNull(absent.native)
        assertFalse(OpenCodeClient.Agent.hasCustomAgent(listOf(absent)))

        val declared = OpenCodeClient.Agent.fromJson(
            JSONObject("""{"name":"review","mode":"primary","native":false}"""),
        )
        assertEquals(false, declared.native)
        assertTrue(OpenCodeClient.Agent.hasCustomAgent(listOf(declared)))
    }

    @Test
    fun theRowIsRenderedAndLeftTheHiddenList() {
        assertFalse(HIDDEN_SETTINGS.contains("settings-show-custom-agents"))
        val row = GENERAL_PANEL.flatMap { it.rows }.single { it.action == "settings-show-custom-agents" }
        assertEquals(SettingsControl.SWITCH, row.control)
        // The pref write exists (the switch's own `setSwitch` path)…
        assertTrue(SettingsStore.BOOLEAN_KEYS.containsKey("settings-show-custom-agents"))
        // …and it is the upstream key name, not a new one.
        assertEquals("opencode.android.showCustomAgents", SettingsStore.BOOLEAN_KEYS["settings-show-custom-agents"])
    }
}
