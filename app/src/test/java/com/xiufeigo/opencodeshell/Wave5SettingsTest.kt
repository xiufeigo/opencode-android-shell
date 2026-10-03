package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.settings.KEYBIND_NONE
import com.xiufeigo.opencodeshell.ui.settings.McpPageRow
import com.xiufeigo.opencodeshell.ui.settings.SETTINGS_NAV_DISABLED
import com.xiufeigo.opencodeshell.ui.settings.SETTINGS_NAV_TABS
import com.xiufeigo.opencodeshell.ui.settings.SHORTCUT_ROWS
import com.xiufeigo.opencodeshell.ui.settings.SettingsServerApi
import com.xiufeigo.opencodeshell.ui.settings.SettingsTab
import com.xiufeigo.opencodeshell.ui.settings.TIMELINE_DETAIL_OPTIONS
import com.xiufeigo.opencodeshell.ui.settings.TimelineDetailPreset
import com.xiufeigo.opencodeshell.ui.settings.applyKeybindOverrides
import com.xiufeigo.opencodeshell.ui.settings.formatKeybindChord
import com.xiufeigo.opencodeshell.ui.settings.isKeybindUnassigned
import com.xiufeigo.opencodeshell.ui.settings.keybindConflicts
import com.xiufeigo.opencodeshell.ui.settings.keybindSignatures
import com.xiufeigo.opencodeshell.ui.settings.mcpPageRows
import com.xiufeigo.opencodeshell.ui.settings.normalizeKeyName
import com.xiufeigo.opencodeshell.ui.settings.pageDescriptionResId
import com.xiufeigo.opencodeshell.ui.settings.projectPageRows
import com.xiufeigo.opencodeshell.ui.settings.skillPageRows
import com.xiufeigo.opencodeshell.ui.settings.worktreeDisplayName
import com.xiufeigo.opencodeshell.ui.settings.worktreePageRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * W5-S (D1/D4/D5) — the pure half of the settings completion work:
 * the timeline-detail preset ownership, the keybind override/recording model,
 * the keybind persistence codec and the read-only page parsers.
 */
class Wave5SettingsTest {

    // ------------------------------------------------------------ the pages

    @Test
    fun everyTabIsReachableFromTheNavigation() {
        // A tab that exists in the enum but not in the nav (or vice versa) is the
        // exact drift that left seven upstream pages unreachable before W5-S.
        assertEquals(SettingsTab.entries.toSet(), SETTINGS_NAV_TABS.toSet())
        // The disabled rows are *not* tabs; wave-close left none (Experimental
        // became a real page and Shell joined it).
        assertEquals(emptyList<Int>(), SETTINGS_NAV_DISABLED.map { it.titleResId })
    }

    @Test
    fun pageDescriptionsMatchThePagesThatRenderAHeader() {
        assertEquals(R.string.oc_settings_appearance_description, SettingsTab.APPEARANCE.pageDescriptionResId())
        assertEquals(R.string.oc_settings_notifications_description, SettingsTab.NOTIFICATIONS.pageDescriptionResId())
        assertEquals(R.string.oc_settings_projects_description, SettingsTab.PROJECTS.pageDescriptionResId())
        assertEquals(R.string.oc_settings_workspaces_description, SettingsTab.WORKSPACES.pageDescriptionResId())
        assertEquals(R.string.oc_settings_extensions_description, SettingsTab.EXTENSIONS.pageDescriptionResId())
        // General renders no upstream header, About carries its own copy.
        assertNull(SettingsTab.GENERAL.pageDescriptionResId())
        assertNull(SettingsTab.ABOUT.pageDescriptionResId())
    }

    // ------------------------------------------------- timeline detail (D5)

    @Test
    fun timelinePresetDetectionMirrorsUpstreamTimelinePreset() {
        assertEquals(
            TimelineDetailPreset.EVERYTHING,
            TimelineDetailPreset.of(shellExpanded = true, editExpanded = true, reasoningShown = true),
        )
        assertEquals(
            TimelineDetailPreset.COMPACT,
            TimelineDetailPreset.of(shellExpanded = false, editExpanded = false, reasoningShown = true),
        )
        assertEquals(
            TimelineDetailPreset.QUIET,
            TimelineDetailPreset.of(shellExpanded = false, editExpanded = false, reasoningShown = false),
        )
        // Anything else is "Custom" (upstream `timelinePreset(value) === undefined`).
        assertNull(TimelineDetailPreset.of(shellExpanded = true, editExpanded = false, reasoningShown = true))
        assertNull(TimelineDetailPreset.of(shellExpanded = false, editExpanded = true, reasoningShown = false))
    }

    @Test
    fun theStoreHasNoAllThreeKeyPresetEntry() {
        // The old `SettingsStore.update("settings-timeline-detail", id)` branch
        // wrote all three booleans at once, so picking a preset silently undid
        // a reasoning switch the user had flipped by hand (v63 BUG-01). The
        // entry is gone; the panel writes a preset's owned keys per-switch
        // (`SettingsTimelineDetailOwnershipTest`), and a stray write with this
        // action now falls through `BOOLEAN_KEYS` and persists nothing.
        val text = File("src/main/java/com/xiufeigo/opencodeshell/SettingsStore.kt")
            .takeIf { it.isFile }
            ?.readText()
            ?: File("app/src/main/java/com/xiufeigo/opencodeshell/SettingsStore.kt").readText()
        assertFalse(
            "the all-three-key preset write must stay deleted",
            text.contains("\"settings-timeline-detail\" ->"),
        )
        assertFalse(text.contains("TIMELINE_DETAIL_PRESETS"))
    }

    @Test
    fun everyTimelinePresetCarriesALabelAndIsSelectable() {
        assertEquals(
            listOf("everything", "compact", "quiet"),
            TIMELINE_DETAIL_OPTIONS.map { it.value },
        )
        assertTrue(TIMELINE_DETAIL_OPTIONS.all { it.labelResId != 0 })
        // `text-only` is deliberately absent: the native timeline always renders
        // shell/edit cards, so the strictest state it can reach is `quiet`.
        assertTrue(TIMELINE_DETAIL_OPTIONS.none { it.value == "text-only" })
        assertTrue(TIMELINE_DETAIL_OPTIONS.none { it.value == "detailed" })
    }

    // ------------------------------------------------------ keybinds (D4)

    @Test
    fun keyNameNormalizationUsesTheDisplayVocabulary() {
        assertEquals("A", normalizeKeyName("KEYCODE_A"))
        assertEquals("S", normalizeKeyName("KEYCODE_S"))
        assertEquals("1", normalizeKeyName("KEYCODE_1"))
        assertEquals("Enter", normalizeKeyName("KEYCODE_ENTER"))
        assertEquals("Backspace", normalizeKeyName("KEYCODE_DEL"))
        assertEquals("Delete", normalizeKeyName("KEYCODE_FORWARD_DEL"))
        assertEquals("Escape", normalizeKeyName("KEYCODE_ESC"))
        assertEquals(",", normalizeKeyName("KEYCODE_COMMA"))
        assertEquals("`", normalizeKeyName("KEYCODE_GRAVE"))
        assertEquals("\\", normalizeKeyName("KEYCODE_BACKSLASH"))
        assertEquals("ArrowLeft", normalizeKeyName("KEYCODE_DPAD_LEFT"))
        assertEquals("F5", normalizeKeyName("KEYCODE_F5"))
        // A bare modifier keeps the capture waiting instead of binding "Ctrl".
        assertNull(normalizeKeyName("KEYCODE_CTRL_LEFT"))
        assertNull(normalizeKeyName("KEYCODE_SHIFT_RIGHT"))
        assertNull(normalizeKeyName("KEYCODE_ALT_RIGHT"))
        assertNull(normalizeKeyName("KEYCODE_META_LEFT"))
        assertNull(normalizeKeyName(null))
        assertNull(normalizeKeyName("   "))
    }

    @Test
    fun recordedChordsFormatInTheSameVocabularyAsTheDefaultTable() {
        assertEquals("Ctrl+Shift+S", formatKeybindChord(ctrl = true, alt = false, shift = true, meta = false, key = "S"))
        assertEquals("Ctrl+`", formatKeybindChord(ctrl = true, alt = false, shift = false, meta = false, key = "`"))
        assertEquals("Ctrl+Alt+Shift+Meta+K", formatKeybindChord(true, true, true, true, "K"))
        assertEquals("Enter", formatKeybindChord(false, false, false, false, "Enter"))
    }

    @Test
    fun signaturesNormalizeModifierOrderAndSplitMultiChordBindings() {
        assertEquals(listOf("ctrl+shift+s"), keybindSignatures("Shift+Ctrl+S"))
        assertEquals(listOf("ctrl+k", "ctrl+shift+p"), keybindSignatures("Ctrl+K / Ctrl+Shift+P"))
        assertEquals(listOf("ctrl+k", "ctrl+shift+p"), keybindSignatures("Ctrl+K,Ctrl+Shift+P"))
        // A bare slash key must stay one chord, not split into two.
        assertEquals(listOf("ctrl+/"), keybindSignatures("Ctrl+/"))
        assertEquals(emptyList<String>(), keybindSignatures(KEYBIND_NONE))
        assertEquals(emptyList<String>(), keybindSignatures(null))
        assertEquals(emptyList<String>(), keybindSignatures(""))
    }

    @Test
    fun conflictDetectionUsesTheEffectiveBindingAndIgnoresTheEditedRow() {
        val rows = applyKeybindOverrides(mapOf("terminal.toggle" to "Ctrl+Shift+S"))
        val clashes = keybindConflicts("Ctrl+Shift+S", rows, editingId = "command.palette")
        assertEquals(listOf("session.new", "terminal.toggle"), clashes.map { it.id })
        // Re-recording a row's own binding is not a conflict with itself.
        assertTrue(keybindConflicts("Ctrl+Shift+S", SHORTCUT_ROWS, editingId = "session.new").isEmpty())
        // An override frees the default it replaced…
        assertTrue(keybindConflicts("Ctrl+`", rows, editingId = "command.palette").isEmpty())
        // …and the new chord is busy for every other row.
        assertEquals(
            listOf("session.new", "terminal.toggle"),
            keybindConflicts("Ctrl+Shift+S", rows, editingId = "file.open").map { it.id },
        )
    }

    @Test
    fun clearedBindingsNeverConflict() {
        val rows = applyKeybindOverrides(mapOf("terminal.toggle" to KEYBIND_NONE))
        assertTrue(keybindConflicts(KEYBIND_NONE, rows, editingId = "session.new").isEmpty())
        assertTrue(keybindConflicts("", rows, editingId = "session.new").isEmpty())
    }

    @Test
    fun overridesReplaceDefaultsAndUnassignedIsDetectable() {
        val rows = applyKeybindOverrides(mapOf("session.new" to KEYBIND_NONE))
        val row = rows.first { it.id == "session.new" }
        assertEquals(KEYBIND_NONE, row.keybind)
        assertTrue(isKeybindUnassigned(row.keybind))
        assertFalse(isKeybindUnassigned(rows.first { it.id == "command.palette" }.keybind))
        // Untouched rows still carry their default.
        assertEquals(SHORTCUT_ROWS.size, rows.size)
        assertEquals("Ctrl+,", rows.first { it.id == "settings.open" }.keybind)
    }

    @Test
    fun keybindTableRoundTripsThroughTheStoreCodec() {
        val overrides = linkedMapOf(
            "terminal.toggle" to "Ctrl+Shift+T",
            "session.new" to KEYBIND_NONE,
        )
        val encoded = SettingsStore.encodeKeybinds(overrides)
        assertEquals(overrides, SettingsStore.parseKeybinds(encoded))
        // Blank values and blank ids are dropped (they mean "use the default").
        assertEquals(emptyMap<String, String>(), SettingsStore.parseKeybinds(SettingsStore.encodeKeybinds(mapOf("" to "Ctrl+K", "x.y" to " "))))
        assertEquals(emptyMap<String, String>(), SettingsStore.parseKeybinds(null))
        assertEquals(emptyMap<String, String>(), SettingsStore.parseKeybinds(""))
        assertEquals(emptyMap<String, String>(), SettingsStore.parseKeybinds("{not json"))
        assertTrue(SettingsStore.KEY_KEYBINDS.startsWith("opencode"))
    }

    // -------------------------------------------- read-only pages (D1)

    @Test
    fun projectsParseTheRealV1Payload() {
        // Trimmed from a live `GET /project` answer (opencode 1.18.31).
        val json = """
            [{"id":"global","worktree":"/","time":{"created":1782648470362,"updated":1789956385238},"sandboxes":[]},
             {"id":"af8fd21b","worktree":"C:\\sensorsdata\\main\\program\\chenjieyan_test","vcs":"git",
              "icon":{"color":"mint"},"time":{"created":1782745166926,"updated":1789901086510},"sandboxes":[]},
             {"id":"no-worktree","vcs":"git"}]
        """.trimIndent()
        val rows = projectPageRows(json)
        assertEquals(listOf("/", "chenjieyan_test"), rows.map { it.name })
        assertEquals("/", rows.first { it.id == "global" }.worktree)
        assertEquals("git", rows.first { it.id == "af8fd21b" }.vcs)
        assertEquals(1789901086510L, rows.first { it.id == "af8fd21b" }.updatedAt)
        // Newest first: the most recently touched worktree leads.
        assertEquals(listOf("global", "af8fd21b"), rows.map { it.id })
        // An entry without a worktree is skipped, never rendered as a blank row.
        assertTrue(rows.none { it.id == "no-worktree" })
        assertTrue(projectPageRows("{}").isEmpty())
        assertTrue(projectPageRows(null).isEmpty())
    }

    @Test
    fun projectsParseTheV2CanonicalPayload() {
        // Trimmed from a live `GET /api/project` answer (opencode 2.0.11): the
        // path key is `canonical`, `icon` is gone, and an element WITHOUT a
        // path is still skipped rather than rendered blank.
        val json = """
            [{"id":"16a80bf9","canonical":"/home/xiufeigo/opencode-android-shell",""" +
            """"time":{"created":1790075402947,"updated":1790075402947},"sandboxes":[],"vcs":"git"},
             {"id":"broken","time":{"created":1,"updated":2}}]
        """.trimIndent()
        val rows = projectPageRows(json)
        assertEquals(listOf("opencode-android-shell"), rows.map { it.name })
        assertEquals("/home/xiufeigo/opencode-android-shell", rows.single().worktree)
        assertEquals("git", rows.single().vcs)
        assertEquals(1790075402947L, rows.single().updatedAt)
        assertTrue(rows.none { it.id == "broken" })
    }

    @Test
    fun worktreesParseTolerantlyAndSkipDirectorylessEntries() {
        val json = """
            [{"name":"feature-a","branch":"feature-a","directory":"/repo/.worktrees/feature-a"},
             {"directory":"C:\\repo\\.worktrees\\b"},
             {"name":"broken"}]
        """.trimIndent()
        val rows = worktreePageRows(json)
        assertEquals(listOf("feature-a", "b"), rows.map { it.name })
        assertEquals("feature-a", rows.first().branch)
        assertNull(rows[1].branch)
        assertTrue(worktreePageRows("[]").isEmpty())
        assertEquals("cyber-watcher", worktreeDisplayName("C:\\x\\cyber-watcher\\"))
        assertEquals("/", worktreeDisplayName("/"))
    }

    @Test
    fun mcpPageAcceptsBothTheObjectMapAndTheArrayShape() {
        // v1 `GET /mcp` — an object map, `{}` when nothing is configured.
        val map = mcpPageRows("""{"linear":{"status":"connected"},"gh":{"status":"failed","error":"spawn ENOENT"}}""")
        assertEquals(listOf("linear", "gh"), map.map { it.name })
        assertTrue(map.first().connected)
        assertFalse(map.first().failed)
        assertTrue(map.last().failed)
        assertEquals("spawn ENOENT", map.last().error)
        assertTrue(mcpPageRows("{}").isEmpty())
        assertTrue(mcpPageRows(null).isEmpty())

        // v2 `mcp.list()` — an array of `{name, status: {status}}`.
        val array = mcpPageRows("""[{"name":"b","status":{"status":"disabled"}},{"name":"a","status":{"status":"connected"}}]""")
        assertEquals(listOf("a", "b"), array.map { it.name })
        assertTrue(array.first().connected)
        assertEquals("disabled", array.last().status)
    }

    @Test
    fun skillsParseNameAndDescription() {
        val rows = skillPageRows("""[{"name":"customize-opencode","description":"Use ONLY when…"},{"description":"no name"}]""")
        assertEquals(listOf("customize-opencode"), rows.map { it.name })
        assertEquals("Use ONLY when…", rows.first().description)
        assertTrue(skillPageRows("[]").isEmpty())
        assertTrue(skillPageRows("not json").isEmpty())
    }

    @Test
    fun readOnlyPagesOnlyBuildAbsoluteEndpoints() {
        val url = SettingsServerApi.endpoint("http://127.0.0.1:4096/", "/project")
        assertNotNull(url)
        assertEquals("http://127.0.0.1:4096/project", url.toString())
        assertEquals(
            "https://a.example.com/experimental/worktree",
            SettingsServerApi.endpoint("https://a.example.com", "/experimental/worktree").toString(),
        )
        assertNull(SettingsServerApi.endpoint("127.0.0.1:4096", "/mcp"))
        assertNull(SettingsServerApi.endpoint("", "/mcp"))
    }

    @Test
    fun mcpRowStatesAreDistinguished() {
        assertTrue(McpPageRow("a", "connected", null).connected)
        assertTrue(McpPageRow("a", "FAILED", null).failed)
        assertFalse(McpPageRow("a", "disabled", null).failed)
    }
}
