package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.settings.GENERAL_PANEL
import com.xiufeigo.opencodeshell.ui.settings.SettingsControl
import com.xiufeigo.opencodeshell.ui.settings.switchValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The `Auto-accept permissions` switch (`settings/general/general.tsx:53-69`,
 * `data-action=settings-auto-accept-permissions`) after the v16 device finding:
 * tapping it "did nothing".
 *
 * The contract this file pins, in the order the three device symptoms were
 * reported:
 *
 * 1. **Tap flips** — the row's switch is a controlled `toggleable`
 *    (`OcSwitch`), and the row's copy is a second target for the *same* write,
 *    so a tap on the label is no longer a dead zone.
 * 2. **The tap persists** — `store.setSwitch(action, value)` →
 *    `SettingsStore.update` → [SettingsStore.booleanWrite] → `putBoolean` on
 *    `SettingsStore.KEY_AUTO_ACCEPT`.
 * 3. **Reopening reads it back** — the switch's `checked` is
 *    [switchValue] of the *store snapshot*, never local state, so a dialog that
 *    is closed and reopened shows the persisted value.
 *
 * `SettingsStore` itself needs an Android `Context`, so the write half is
 * exercised through its pure companion helper (the exact call `update` makes)
 * and the read half through the pure `stringValue`/`switchValue` bridge.
 */
class SettingsAutoAcceptSwitchTest {

    private val action = "settings-auto-accept-permissions"

    private fun source(relative: String): String {
        val file = File(relative).takeIf { it.isFile } ?: File("app/$relative")
        return file.readText()
    }

    private fun panels() = source("src/main/java/com/xiufeigo/opencodeshell/ui/settings/SettingsPanels.kt")

    private fun components() = source("src/main/java/com/xiufeigo/opencodeshell/ui/settings/SettingsComponents.kt")

    private fun store() = source("src/main/java/com/xiufeigo/opencodeshell/SettingsStore.kt")

    private fun viewModel() = source("src/main/java/com/xiufeigo/opencodeshell/ChatViewModel.kt")

    /** A store snapshot shaped exactly like `SettingsStore.read()`'s result. */
    private fun settings(autoAccept: Boolean) = SettingsStore.Settings(
        colorScheme = "system",
        themeId = "oc-2",
        uiFont = "",
        codeFont = "",
        terminalFont = "",
        language = "en",
        autoAcceptPermissions = autoAccept,
        shell = "auto",
        reasoningSummaries = true,
        shellToolPartsExpanded = false,
        editToolPartsExpanded = false,
        notifyAgent = true,
        notifyPermissions = true,
        notifyErrors = false,
        soundAgent = "default",
        soundPermissions = "default",
        soundErrors = "default",
        showFileTree = true,
        showSearch = true,
        showStatus = true,
        showCustomAgents = true,
    )

    // ------------------------------------------------------------- the row

    @Test
    fun theGeneralPageRendersTheRowAsASwitch() {
        val row = GENERAL_PANEL.flatMap { it.rows }.firstOrNull { it.action == action }
        assertEquals(SettingsControl.SWITCH, row?.control)
        assertEquals(R.string.oc_setting_auto_accept_permissions, row?.titleResId)
        assertTrue("the row's action is writable", SettingsStore.BOOLEAN_KEYS.containsKey(action))
    }

    // --------------------------------------------------- 1. the tap flips

    @Test
    fun theSwitchDrawsAndFlipsTheStoredValue() {
        // `checked` = the store snapshot, so both states render truthfully.
        assertFalse(switchValue(settings(autoAccept = false), action))
        assertTrue(switchValue(settings(autoAccept = true), action))

        // The switch's own tap writes the toggled value…
        val off = settings(autoAccept = false)
        assertTrue(!switchValue(off, action))
        val on = settings(autoAccept = true)
        assertFalse(!switchValue(on, action))

        // …and the row's copy runs the same write (a tap on the label is not a
        // dead zone: the 40×24 dp knob used to be the only target).
        val body = panels()
        assertTrue(body.contains("store.setSwitch(row.action, !switchValue(settings, row.action))"))
        assertTrue(body.contains("onToggle = toggle"))
        assertTrue(body.contains("if (row.control == SettingsControl.SWITCH)"))

        // The control itself is a real toggle with switch semantics, not a bare
        // clickable Box (TalkBack / uiautomator / keyboard all reach it now).
        val switch = components().substringAfter("fun OcSwitch(").substringBefore("KeybindChip")
        assertTrue(switch.contains("toggleable("))
        assertTrue(switch.contains("role = Role.Switch"))
        assertTrue(switch.contains("onValueChange = onCheckedChange"))
        assertFalse("no bare clickable left on the switch", switch.contains("clickable(enabled = enabled)"))

        // The row wires the copy to the toggle callback only when asked.
        val row = components().substringAfter("fun SettingsRow(").substringBefore("fun SettingsSectionBlock(")
        assertTrue(row.contains("onToggle: (() -> Unit)? = null"))
        assertTrue(row.contains("clickable(onClick = onToggle)"))
    }

    // ------------------------------------------------ 2. the tap persists

    @Test
    fun theTapPersistsUnderTheAutoAcceptKey() {
        // The row's `data-action` is the store's write key, and that key is the
        // one the device has always written.
        assertEquals(SettingsStore.KEY_AUTO_ACCEPT, SettingsStore.BOOLEAN_KEYS[action])
        assertEquals("opencode.android.autoAcceptPermissions", SettingsStore.KEY_AUTO_ACCEPT)
        assertTrue(SettingsStore.BOOLEAN_KEYS.containsKey(action))

        // The UI's write half is the store's, never a local `remember`…
        val body = panels()
        assertTrue(body.contains("onCheckedChange = { store.setSwitch(row.action, it) }"))
        assertTrue(body.contains("checked = switchValue(settings, row.action)"))

        // …and `setSwitch` → `update` → `putBoolean` really writes it. Both
        // halves are pinned because `SettingsStore` needs an Android `Context`
        // and cannot be instantiated in a JVM test.
        val storeBody = store()
        assertTrue(
            storeBody.contains(
                "fun setSwitch(action: String, checked: Boolean) = update(action, checked.toString())",
            ),
        )
        val boolBranch = storeBody.substringAfter("else -> {").substringBefore("editor.apply()")
        assertTrue(boolBranch.contains("val boolKey = BOOLEAN_KEYS[action]"))
        assertTrue(boolBranch.contains("if (boolKey == null || (value != \"true\" && value != \"false\")) return"))
        assertTrue(boolBranch.contains("editor.putBoolean(boolKey, value == \"true\")"))
    }

    @Test
    fun aNonBooleanActionCanNeverWrite() {
        // Nothing else can be turned into a pref write by a stray tap, and a
        // non-boolean value is refused even for a real switch row (that guard
        // lives in the branch pinned above).
        assertFalse(SettingsStore.BOOLEAN_KEYS.containsKey("settings-color-scheme"))
        assertFalse(SettingsStore.BOOLEAN_KEYS.containsKey("settings-sounds-agent"))
        assertFalse(SettingsStore.BOOLEAN_KEYS.containsKey("settings-follow-up-behavior"))
        assertNull(SettingsStore.BOOLEAN_KEYS["settings-notifications-unknown"])
    }

    // --------------------------------------- 3. reopening reads it back

    @Test
    fun reopeningTheSettingsReadsThePersistedValueBack() {
        // The switch's `checked` is a function of the store snapshot only, in
        // both directions — a reopen (`read()`) therefore cannot disagree with
        // what the tap wrote.
        assertTrue(switchValue(settings(autoAccept = true), action))
        assertFalse(switchValue(settings(autoAccept = false), action))

        // The store's read half reads that same key, so the pairing cannot
        // silently break on one side only.
        val readLine = store().substringAfter("private fun read(): Settings = Settings(")
            .substringBefore("private fun readEnum")
        assertTrue(readLine.contains("autoAcceptPermissions = prefs.getBoolean(KEY_AUTO_ACCEPT, false)"))

        // And the panel never keeps its own copy of the value: the only source
        // of `checked` is the store snapshot handed down by
        // `SettingsDialogContent`.
        assertFalse(panels().contains("var autoAccept"))
        assertFalse(panels().contains("remember { mutableStateOf(switchValue"))
    }

    // ------------------------------------------- the consumer sees it live

    @Test
    fun flippingTheSettingReachesTheAutoApproverImmediately() {
        // The production shape: `AutoApprover(enabled = { …autoAcceptPermissions })`
        // reads the *current* snapshot on every ask, so the very next permission
        // request after a tap is auto-replied — no restart, no reopen.
        var snapshot = settings(autoAccept = false)
        val approver = AutoApprover(enabled = { snapshot.autoAcceptPermissions })
        assertFalse("off ⇒ nothing is replied", approver.tryMark("per_1"))
        assertEquals(0, approver.attemptsOf("per_1"))

        snapshot = settings(autoAccept = true)
        assertTrue("the tap is live for the next ask", approver.tryMark("per_2"))

        snapshot = settings(autoAccept = false)
        assertFalse("turning it back off stops the replies", approver.tryMark("per_3"))

        // The view model's lambda is that live read, not a captured snapshot.
        val body = viewModel()
        assertTrue(body.contains("settingsStore?.state?.value?.autoAcceptPermissions == true"))
        assertTrue(body.contains("private val autoApprover = AutoApprover("))
    }
}
