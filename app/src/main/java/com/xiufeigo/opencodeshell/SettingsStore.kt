package com.xiufeigo.opencodeshell

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * M4 settings persistence.
 *
 * The storage keys the *theme* trio uses are the upstream localStorage keys
 * (`packages/ui/src/theme/context.tsx:15-16`: `opencode-theme-id`,
 * `opencode-color-scheme`) so an APK that ever migrates the WebView's
 * localStorage keeps the same names. The remaining keys are Android-only
 * additions (same naming scheme) and live in their own prefs file, never in the
 * WebView's `opencode.global.dat` blob.
 *
 * **Deviation from the milestone note "DataStore"**: this project has no
 * `androidx.datastore` dependency and the build runs offline, so the store is
 * backed by `SharedPreferences` and exposes a `StateFlow` instead. The public
 * surface (`state` / `set…`) is what a DataStore re-implementation would offer,
 * so swapping it later is a one-file change.
 */
class SettingsStore(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(read())
    val state: StateFlow<Settings> = _state.asStateFlow()

    init {
        applyLanguage(_state.value.language)
    }

    /**
     * Applies the stored language process-wide.
     *
     * API 33+ goes straight to the framework `LocaleManager`: the launcher
     * activity ([OcActivity]) is a plain `ComponentActivity`, so no
     * `AppCompatDelegate` is ever attached in this process and
     * `AppCompatDelegate.setApplicationLocales` silently no-ops there (its
     * API-33 delegation needs an app context captured by a delegate). The
     * framework call persists the per-app locale itself and recreates the
     * running activities with the new configuration, which is exactly what
     * makes `stringResource()` switch language without a restart.
     *
     * Pre-33 falls back to AppCompat, which applies on the next
     * `AppCompatActivity` attach ([MainActivity] / [OcActivity] both are).
     */
    private fun applyLanguage(language: String) {
        val tag = when (language) {
            "zh" -> "zh-CN"
            "zht" -> "zh-TW"
            else -> "en"
        }
        if (Build.VERSION.SDK_INT >= 33) {
            val manager = appContext.getSystemService(LocaleManager::class.java) ?: return
            if (manager.applicationLocales.toLanguageTags() != tag) {
                manager.applicationLocales = LocaleList.forLanguageTags(tag)
            }
        } else {
            @Suppress("DEPRECATION")
            if (AppCompatDelegate.getApplicationLocales().toLanguageTags() != tag) {
                @Suppress("DEPRECATION")
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
            }
        }
    }

    /** Single immutable snapshot; recomposition-friendly. */
    data class Settings(
        /** `system` | `light` | `dark` — mirrors `opencode-color-scheme`. */
        val colorScheme: String,
        /** `opencode-theme-id`; only `oc-2` ships. */
        val themeId: String,
        val uiFont: String,
        val codeFont: String,
        val terminalFont: String,
        val language: String,
        val autoAcceptPermissions: Boolean,
        val shell: String,
        val reasoningSummaries: Boolean,
        val shellToolPartsExpanded: Boolean,
        val editToolPartsExpanded: Boolean,
        val notifyAgent: Boolean,
        val notifyPermissions: Boolean,
        val notifyErrors: Boolean,
        val soundAgent: String,
        val soundPermissions: String,
        val soundErrors: String,
        val showFileTree: Boolean,
        val showSearch: Boolean,
        val showStatus: Boolean,
        val showCustomAgents: Boolean,
        /**
         * W5-S (D4): the persisted keybind override table, keyed by command id.
         * Upstream stores it as `settings.keybinds` (`settings/model.tsx:258`);
         * a value of `"none"` means "explicitly unbound"
         * (`keybinds.tsx:272-282`). Empty map = every row uses its default.
         */
        val keybinds: Map<String, String> = emptyMap(),
        /**
         * B3: where the desktop session docks the terminal — `side` | `bottom`
         * (upstream `settings.general.terminalPlacement`, default `side`).
         * Consumed by `ui/terminal/TerminalDock.kt` through the settings-backed
         * preference channel `ui/App.kt` already publishes.
         */
        val terminalPlacement: String = DEFAULT_TERMINAL_PLACEMENT,
        /**
         * Upstream `settings.general.followUpBehavior` (`settings/model.tsx:96`),
         * default `steer` (`model.tsx:253`). The composer reads it to decide
         * whether a follow-up Enter queues behind the running turn or steers it
         * (`A4 #9` consumer note in `wave2.3a-notes.md` §5).
         */
        val followUpBehavior: String = DEFAULT_FOLLOW_UP_BEHAVIOR,
        /**
         * Upstream `settings.general.reasoningMode` (`model.tsx:207`) —
         * `hidden` | `compact` | `full`, no stored default upstream (null =
         * "follow the preset"). The native timeline derives it from the preset
         * triple; this field lets a later milestone store an explicit choice.
         * Empty = unset (follow preset).
         */
        val reasoningMode: String = "",
        /**
         * Upstream `settings.general.mobileDiffWrap` (`model.tsx:94`), default
         * `true` (`model.tsx:251`). Consumer: the review diff renderer wraps
         * long lines instead of scrolling horizontally.
         */
        val mobileDiffWrap: Boolean = true,
        /**
         * Upstream `settings.workspaces.defaultDestination` (`model.tsx:114`),
         * default `last-used` (`model.tsx:260`). Consumer: the new-session
         * draft's initial workspace location.
         */
        val workspaceDefaultDestination: String = DEFAULT_WORKSPACE_DESTINATION,
        /**
         * B-11/B-16: upstream `appearance.tabLayout` (`model.tsx:257`, default
         * `horizontal`) — `horizontal` | `vertical`. Consumer:
         * [com.xiufeigo.opencodeshell.ui.nav.AppearancePrefs] →
         * `OcApp.bottomTabStrip` → [com.xiufeigo.opencodeshell.ui.nav.BottomTabStrip]
         * (the mobile bottom title bar, `titlebar.tsx:64-65`) or, on the other
         * stop, [com.xiufeigo.opencodeshell.ui.nav.DesktopTabStrip] at the top.
         *
         * The value is read once per launch (the nav layer has no store of its
         * own). Two rows write it: the Experimental page's Tabs select
         * (`horizontal`/`vertical`) and B-16's General-page「底部导航」switch
         * (`settings.general.row.mobileTitlebarBottom`, `general.tsx:369-381`),
         * which is the same pref seen as "bottom = `vertical`".
         */
        val tabLayout: String = DEFAULT_TAB_LAYOUT,
    ) {
        /**
         * `oc-theme-preload.js`: `isDark = scheme=="dark" ||
         * (scheme=="system" && prefers-color-scheme: dark)`.
         */
        fun isDark(systemDark: Boolean): Boolean = when (colorScheme) {
            "dark" -> true
            "light" -> false
            else -> systemDark
        }
    }

    private fun read(): Settings = Settings(
        colorScheme = readEnum(KEY_COLOR_SCHEME, SCHEMES, DEFAULT_SCHEME),
        themeId = prefs.getString(KEY_THEME_ID, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_THEME_ID,
        uiFont = prefs.getString(KEY_UI_FONT, null) ?: "",
        codeFont = prefs.getString(KEY_CODE_FONT, null) ?: "",
        terminalFont = prefs.getString(KEY_TERMINAL_FONT, null) ?: "",
        language = readEnum(KEY_LANGUAGE, LANGUAGES, DEFAULT_LANGUAGE),
        autoAcceptPermissions = prefs.getBoolean(KEY_AUTO_ACCEPT, false),
        shell = readEnum(KEY_SHELL, SHELLS, DEFAULT_SHELL),
        reasoningSummaries = prefs.getBoolean(KEY_REASONING_SUMMARIES, true),
        shellToolPartsExpanded = prefs.getBoolean(KEY_SHELL_EXPANDED, false),
        editToolPartsExpanded = prefs.getBoolean(KEY_EDIT_EXPANDED, false),
        notifyAgent = prefs.getBoolean(KEY_NOTIFY_AGENT, true),
        notifyPermissions = prefs.getBoolean(KEY_NOTIFY_PERMISSIONS, true),
        // Upstream `notifications.errors` defaults to **false**
        // (`settings/model.tsx:261`) — the native default used to be `true`,
        // which the audit flagged as a mismatch. W5-S aligned it now that the
        // E5 in-app feed makes the switch live.
        notifyErrors = prefs.getBoolean(KEY_NOTIFY_ERRORS, false),
        soundAgent = readEnum(KEY_SOUND_AGENT, SOUNDS, DEFAULT_SOUND),
        soundPermissions = readEnum(KEY_SOUND_PERMISSIONS, SOUNDS, DEFAULT_SOUND),
        soundErrors = readEnum(KEY_SOUND_ERRORS, SOUNDS, DEFAULT_SOUND),
        showFileTree = prefs.getBoolean(KEY_SHOW_FILE_TREE, true),
        showSearch = prefs.getBoolean(KEY_SHOW_SEARCH, true),
        showStatus = prefs.getBoolean(KEY_SHOW_STATUS, true),
        // B-9: upstream `general.showCustomAgents` defaults to **false**
        // (`settings/model.tsx:249`). The row used to be hidden, so the old
        // `true` default was never observable; the composer's agent chip reads
        // it now, and `false` means "hide the agent picker unless the server
        // has a custom agent" — upstream's `agentsVisible`.
        showCustomAgents = prefs.getBoolean(KEY_SHOW_CUSTOM_AGENTS, false),
        keybinds = parseKeybinds(prefs.getString(KEY_KEYBINDS, null)),
        terminalPlacement = readEnum(KEY_TERMINAL_PLACEMENT, PLACEMENTS, DEFAULT_TERMINAL_PLACEMENT),
        followUpBehavior = readEnum(KEY_FOLLOW_UP_BEHAVIOR, FOLLOW_UP_BEHAVIORS, DEFAULT_FOLLOW_UP_BEHAVIOR),
        reasoningMode = prefs.getString(KEY_REASONING_MODE, null)?.takeIf { it in REASONING_MODES } ?: "",
        mobileDiffWrap = prefs.getBoolean(KEY_MOBILE_DIFF_WRAP, true),
        workspaceDefaultDestination = readEnum(
            KEY_WORKSPACE_DEFAULT_DESTINATION,
            WORKSPACE_DESTINATIONS,
            DEFAULT_WORKSPACE_DESTINATION,
        ),
        tabLayout = readEnum(KEY_TAB_LAYOUT, TAB_LAYOUTS, DEFAULT_TAB_LAYOUT),
    )

    private fun readEnum(key: String, allowed: Set<String>, fallback: String): String =
        prefs.getString(key, null)?.takeIf { it in allowed } ?: fallback

    /**
     * Generic writer keyed by the web `data-action` string, so
     * [com.xiufeigo.opencodeshell.ui.settings.SettingsScreen] can stay a thin
     * `when(action)` paint loop. Unknown actions are ignored.
     */
    fun update(action: String, value: String) {
        val editor = prefs.edit()
        when (action) {
            "settings-color-scheme" -> if (value in SCHEMES) editor.putString(KEY_COLOR_SCHEME, value)
            "settings-theme" -> if (value.isNotBlank()) editor.putString(KEY_THEME_ID, value)
            "settings-ui-font" -> editor.putString(KEY_UI_FONT, value)
            "settings-code-font" -> editor.putString(KEY_CODE_FONT, value)
            "settings-terminal-font" -> editor.putString(KEY_TERMINAL_FONT, value)
            "settings-language" -> if (value in LANGUAGES) editor.putString(KEY_LANGUAGE, value)
            "settings-shell" -> if (value in SHELLS) editor.putString(KEY_SHELL, value)
            // B3: `settings.general.terminalPlacement` — the side/bottom select of
            // the General page. An unknown value is ignored (the upstream schema
            // is a two-literal union).
            "settings-terminal-placement" -> if (value in PLACEMENTS) editor.putString(KEY_TERMINAL_PLACEMENT, value)
            // WAVE 2.3a (A4 #9): `general.followUpBehavior` — `queue` | `steer`.
            "settings-follow-up-behavior" -> if (value in FOLLOW_UP_BEHAVIORS) editor.putString(KEY_FOLLOW_UP_BEHAVIOR, value)
            // WAVE 2.3a: `general.reasoningMode` — `hidden` | `compact` | `full`;
            // blank clears back to "follow the preset".
            "settings-reasoning-mode" ->
                if (value.isBlank()) editor.remove(KEY_REASONING_MODE)
                else if (value in REASONING_MODES) editor.putString(KEY_REASONING_MODE, value)
            // WAVE 2.3a: `workspaces.defaultDestination` — where new sessions start.
            "settings-workspace-destination" ->
                if (value in WORKSPACE_DESTINATIONS) editor.putString(KEY_WORKSPACE_DEFAULT_DESTINATION, value)
            // B-11: `appearance.tabLayout` — the Experimental page's Tabs row
            // (`experimental.tsx:26-44`), consumed by `AppearancePrefs`.
            "settings-tab-layout" -> if (value in TAB_LAYOUTS) editor.putString(KEY_TAB_LAYOUT, value)
            // B-16: the General page's「底部导航」switch (`general.tsx:369-381`).
            // It is the *same* placement the Tabs row above writes, seen as a
            // boolean — checked = the strip at the bottom = `vertical` — so the
            // two rows cannot disagree and the nav layer keeps one decoder
            // (`ui/nav/TabLayout.bottomStrip`).
            "settings-mobile-titlebar-bottom" ->
                editor.putString(
                    KEY_TAB_LAYOUT,
                    if (value == "true") TAB_LAYOUT_VERTICAL else TAB_LAYOUT_HORIZONTAL,
                )
            "settings-sounds-agent" -> if (value in SOUNDS) editor.putString(KEY_SOUND_AGENT, value)
            "settings-sounds-permissions" -> if (value in SOUNDS) editor.putString(KEY_SOUND_PERMISSIONS, value)
            "settings-sounds-errors" -> if (value in SOUNDS) editor.putString(KEY_SOUND_ERRORS, value)
            else -> {
                val boolKey = BOOLEAN_KEYS[action]
                if (boolKey == null || (value != "true" && value != "false")) return
                editor.putBoolean(boolKey, value == "true")
            }
        }
        editor.apply()
        val next = read()
        _state.value = next
        if (action == "settings-language") {
            // Persisted language must also update Android's resource locale;
            // changing only SharedPreferences leaves all stringResource() calls unchanged.
            applyLanguage(next.language)
        }
    }

    /** Convenience for the Switch rows. */
    fun setSwitch(action: String, checked: Boolean) = update(action, checked.toString())

    // ------------------------------------------------------------- keybinds

    /**
     * Records one keybind override. [value] is a formatted chord (`Ctrl+Shift+S`)
     * or `"none"` for an explicitly cleared binding; a blank value removes the
     * override so the built-in default applies again.
     */
    fun setKeybind(id: String, value: String) {
        if (id.isBlank()) return
        val next = _state.value.keybinds.toMutableMap()
        if (value.isBlank()) next.remove(id) else next[id] = value
        writeKeybinds(next)
    }

    /** `Backspace`/`Delete` on a row (`keybinds.tsx:272-282`). */
    fun clearKeybind(id: String) = setKeybind(id, KEYBIND_NONE_VALUE)

    /** Upstream `keybinds.resetAll()` + the enabled-when-`hasOverrides` button. */
    fun resetKeybinds() = writeKeybinds(emptyMap())

    fun hasKeybindOverrides(): Boolean = _state.value.keybinds.isNotEmpty()

    private fun writeKeybinds(overrides: Map<String, String>) {
        prefs.edit { putString(KEY_KEYBINDS, encodeKeybinds(overrides)) }
        _state.value = read()
    }

    /**
     * The model picker's last explicit selection, encoded `"providerID/modelID"`
     * ([OpenCodeClient.parseModelRef]); null/blank removes the key (「使用服务器默认」).
     *
     * It is the **draft's** model and the picker memory, not a pin on every
     * session: upstream keeps the per-session pick under the session's own id
     * (`providers/models/selection.tsx:139-143`) and only falls back to the recent
     * picks for a session that names no model of its own (`:163-178`), so a value
     * read here must never relabel an opened session
     * (`ChatViewModel.effectiveModelRef` — the v28 bug). Per-session picks are
     * in-memory only, because the pick is also written onto the session itself
     * (`POST /api/session/{id}/model`), which is where reopening reads it back.
     *
     * Deliberately NOT part of [Settings]: it is a per-user picker memory, not a
     * settings-panel field, so it neither needs nor wants a `state` emission.
     */
    var selectedModelOverride: String?
        get() = prefs.getString(KEY_SELECTED_MODEL, null)?.takeIf { it.isNotBlank() }
        set(value) {
            prefs.edit {
                if (value.isNullOrBlank()) {
                    remove(KEY_SELECTED_MODEL)
                } else {
                    putString(KEY_SELECTED_MODEL, value)
                }
            }
        }

    /**
     * v73: the per-session reasoning-effort pins (`sessionID` → variant label),
     * the Android durable half of upstream's `saved.session[sessionID].variant`
     * (`providers/models/selection.tsx:286-299`).
     *
     * The model pick does not need this: it is written onto the session itself
     * (`POST /api/session/{id}/model`) and read back from `Session.Info.model`.
     * The effort is written the same way, but the *chip* must also survive a
     * session whose variant write never reached the server (offline, a failed
     * switch), so the pick is mirrored here and restored on open
     * (`ChatViewModel.observeSession`). Like [selectedModelOverride], it is a
     * per-session picker memory rather than a settings field, so it neither
     * needs nor wants a [state] emission.
     */
    fun sessionVariant(sessionID: String): String? {
        if (sessionID.isBlank()) return null
        return parseSessionVariants(prefs.getString(KEY_SESSION_VARIANTS, null))[sessionID]
    }

    fun setSessionVariant(sessionID: String, variant: String?) {
        if (sessionID.isBlank()) return
        val current = parseSessionVariants(prefs.getString(KEY_SESSION_VARIANTS, null))
        val next = withSessionVariant(current, sessionID, variant)
        // Reopening a session re-pins what it already stored; that is not a
        // change worth a disk write (the table is rewritten whole).
        if (next == current) return
        prefs.edit { putString(KEY_SESSION_VARIANTS, encodeSessionVariants(next)) }
    }

    /**
     * WAVE 2.3a: per-model visibility overrides (`providerID/modelID` →
     * `show`/`hide`), the store half of the Models-tab Switch. Reads/writes go
     * through [ModelVisibility]'s codec; a write re-emits [state] so the
     * panel and the picker filter stay in sync.
     */
    fun modelVisibility(): Map<String, String> =
        com.xiufeigo.opencodeshell.ui.settings.ModelVisibility.decode(
            prefs.getString(KEY_MODEL_VISIBILITY, null),
        )

    fun setModelVisible(providerID: String, modelID: String, visible: Boolean) {
        if (providerID.isBlank() || modelID.isBlank()) return
        val next = com.xiufeigo.opencodeshell.ui.settings.ModelVisibility.setVisible(
            modelVisibility(), providerID, modelID, visible,
        )
        prefs.edit {
            putString(
                KEY_MODEL_VISIBILITY,
                com.xiufeigo.opencodeshell.ui.settings.ModelVisibility.encode(next),
            )
        }
        _state.value = read()
    }

    /** WAVE 2.3a: models-tab group collapse (`providerID` → collapsed). */
    fun modelsCollapsed(): Map<String, Boolean> =
        com.xiufeigo.opencodeshell.ui.settings.ModelVisibility.decodeCollapsed(
            prefs.getString(KEY_MODELS_COLLAPSED, null),
        )

    fun setModelsCollapsed(providerID: String, collapsed: Boolean) {
        if (providerID.isBlank()) return
        val next = modelsCollapsed().toMutableMap()
        if (collapsed) next[providerID] = true else next.remove(providerID)
        prefs.edit {
            putString(
                KEY_MODELS_COLLAPSED,
                com.xiufeigo.opencodeshell.ui.settings.ModelVisibility.encodeCollapsed(next),
            )
        }
        _state.value = read()
    }

    /**
     * Writes the server list back to the WebView-compatible store schema
     * (`{"list":[...], "active":...}`) that [ServerConfigStore] reads. Returns
     * the encoded JSON so the caller can hand it to the bridge.
     */
    fun encodeServerListJson(servers: List<ServerConfigStore.Server>, activeUrl: String?): String =
        encodeServerList(servers, activeUrl)

    companion object {
        const val PREFS_NAME = "oc_settings"

        // Upstream localStorage keys (theme context).
        const val KEY_THEME_ID = "opencode-theme-id"
        const val KEY_COLOR_SCHEME = "opencode-color-scheme"
        const val KEY_UI_FONT = "opencode-ui-font"
        const val KEY_CODE_FONT = "opencode-code-font"
        const val KEY_TERMINAL_FONT = "opencode-terminal-font"

        const val KEY_LANGUAGE = "opencode-language"
        const val KEY_AUTO_ACCEPT = "opencode.android.autoAcceptPermissions"
        const val KEY_SHELL = "opencode-shell"
        const val KEY_REASONING_SUMMARIES = "opencode.android.reasoningSummaries"
        const val KEY_SHELL_EXPANDED = "opencode.android.shellToolPartsExpanded"
        const val KEY_EDIT_EXPANDED = "opencode.android.editToolPartsExpanded"
        const val KEY_NOTIFY_AGENT = "opencode.android.notify.agent"
        const val KEY_NOTIFY_PERMISSIONS = "opencode.android.notify.permissions"
        const val KEY_NOTIFY_ERRORS = "opencode.android.notify.errors"
        const val KEY_SOUND_AGENT = "opencode.android.sound.agent"
        const val KEY_SOUND_PERMISSIONS = "opencode.android.sound.permissions"
        const val KEY_SOUND_ERRORS = "opencode.android.sound.errors"
        const val KEY_SHOW_FILE_TREE = "opencode.android.showFileTree"
        const val KEY_SHOW_SEARCH = "opencode.android.showSearch"
        const val KEY_SHOW_STATUS = "opencode.android.showStatus"
        const val KEY_SHOW_CUSTOM_AGENTS = "opencode.android.showCustomAgents"
        const val KEY_SELECTED_MODEL = "opencode.android.selectedModel"

        /**
         * v73: the per-session effort table, encoded as a JSON object
         * (`{"ses_1":"max"}`) — upstream keeps the same pair in
         * `saved.session[id]` (a `variant` per session), which is what makes a
         * reopened session show the effort its chip showed before.
         */
        const val KEY_SESSION_VARIANTS = "opencode.android.sessionVariants"

        /**
         * B3 terminal placement, stored under an Android-only key like the rest of
         * this block (upstream keeps it in the `settings.v3` localStorage blob as
         * `general.terminalPlacement`). The value is the upstream literal
         * (`side`/`bottom`) so the two never drift.
         */
        const val KEY_TERMINAL_PLACEMENT = "opencode.android.terminalPlacement"

        /**
         * WAVE 2.3a (A4 #9): `general.followUpBehavior` (`settings/model.tsx:96`),
         * default `steer` (`model.tsx:253`).
         */
        const val KEY_FOLLOW_UP_BEHAVIOR = "opencode.android.followUpBehavior"

        /**
         * WAVE 2.3a: `general.reasoningMode` (`model.tsx:207`). Empty (absent key)
         * = unset = "follow the timeline preset".
         */
        const val KEY_REASONING_MODE = "opencode.android.reasoningMode"

        /** WAVE 2.3a: `general.mobileDiffWrap` (`model.tsx:94`), default `true`. */
        const val KEY_MOBILE_DIFF_WRAP = "opencode.android.mobileDiffWrap"

        /**
         * WAVE 2.3a: `workspaces.defaultDestination` (`model.tsx:114`), default
         * `last-used`.
         */
        const val KEY_WORKSPACE_DEFAULT_DESTINATION = "opencode.android.workspaceDefaultDestination"

        /**
         * B-11: upstream `appearance.tabLayout` (`model.tsx:257`) — the literal
         * key name `ui/nav/TabLayout.KEY` spells, stored in **this** store's
         * prefs file since the Experimental page's row became its writer
         * (`AppearancePrefs` reads the same pair).
         */
        const val KEY_TAB_LAYOUT = "appearance.tabLayout"

        /**
         * WAVE 2.3a: per-model visibility overrides (`providers/models/models.tsx`),
         * encoded like the keybind table (`{"anthropic/claude":"hide"}`).
         * Consumed by the composer ModelPicker filter (wiring note §6).
         */
        const val KEY_MODEL_VISIBILITY = "opencode.android.modelVisibility"

        /**
         * WAVE 2.3a: models-tab group collapse (`ModelProvidersSchema.collapsed`),
         * encoded `{"anthropic":true}`.
         */
        const val KEY_MODELS_COLLAPSED = "opencode.android.modelsCollapsed"

        /**
         * WAVE 2.3a (A4 #21): terminal tabs persisted per workspace directory
         * (`session/terminal/context.tsx:14-28`) — JSON array of
         * `{id,title,titleNumber}` capped at 20 entries per workspace; empty key
         * = the workspace the server process runs in. Trimmed on workspace
         * switch, cleaned on `pty.exited` (see `TerminalStateStore.trim`).
         */
        const val KEY_TERMINAL_TABS = "opencode.android.terminalTabs"

        /**
         * W5-S (D4) keybind override table (upstream `settings.keybinds`,
         * `settings/model.tsx:258`). Stored as a JSON object string
         * (`{"terminal.toggle":"Ctrl+Shift+T"}`) so the value is inspectable and
         * the encoder stays a pure, JVM-testable function.
         */
        const val KEY_KEYBINDS = "opencode.android.keybinds"

        /** `"none"` in the override table = cleared binding (upstream spelling). */
        const val KEYBIND_NONE_VALUE = "none"

        const val DEFAULT_THEME_ID = "oc-2"
        const val DEFAULT_SCHEME = "system"
        const val DEFAULT_LANGUAGE = "en"
        const val DEFAULT_SHELL = "auto"
        const val DEFAULT_SOUND = "default"

        /**
         * Upstream default (`settings/model.tsx:252`): the terminal opens as a
         * pane of the right side region. Kept here as plain data (no
         * `ui.terminal` import) because [SettingsStore] is not JVM-unit-testable;
         * `TerminalLayoutTest` asserts it equals `TerminalPlacement.Default`.
         */
        const val DEFAULT_TERMINAL_PLACEMENT = "side"

        /** Upstream default `followUpBehavior` (`settings/model.tsx:253`). */
        const val DEFAULT_FOLLOW_UP_BEHAVIOR = "steer"

        /** Upstream default `workspaces.defaultDestination` (`model.tsx:260`). */
        const val DEFAULT_WORKSPACE_DESTINATION = "last-used"

        /** B-11/B-16: the two stops, shared by both placement rows. */
        const val TAB_LAYOUT_HORIZONTAL = "horizontal"
        const val TAB_LAYOUT_VERTICAL = "vertical"

        /** Upstream default `appearance.tabLayout` (`model.tsx:257`). */
        const val DEFAULT_TAB_LAYOUT = TAB_LAYOUT_HORIZONTAL

        val SCHEMES = setOf("system", "light", "dark")
        val LANGUAGES = setOf("en", "zh", "zht")
        val SHELLS = setOf("auto", "sh", "bash", "zsh")
        val SOUNDS = setOf("none", "default")
        val PLACEMENTS = setOf("side", "bottom")
        val FOLLOW_UP_BEHAVIORS = setOf("queue", "steer")
        val REASONING_MODES = setOf("hidden", "compact", "full")
        val WORKSPACE_DESTINATIONS = setOf("last-used", "local", "new")

        /** B-11: `appearance.tabLayout` stops (`experimental.tsx:8`). */
        val TAB_LAYOUTS = setOf(TAB_LAYOUT_HORIZONTAL, TAB_LAYOUT_VERTICAL)

        /**
         * WAVE 2.3a: upstream `general.reasoningMode` fallback when the field is
         * unset — the timeline derives it from the preset triple instead.
         * `reasoningShown=false` reads as `hidden`, both expanded as `full`,
         * otherwise `compact` (`timeline/controller.tsx:118-123`).
         */
        fun reasoningModeFallback(
            shellExpanded: Boolean,
            editExpanded: Boolean,
            reasoningShown: Boolean,
        ): String = when {
            !reasoningShown -> "hidden"
            shellExpanded && editExpanded -> "full"
            else -> "compact"
        }

        /**
         * Pure encoder for the server-list wire schema (the same shape
         * `ServerConfigStore` parses). Lives in the companion so it is
         * JVM-testable — `SettingsStore` itself needs an Android `Context`.
         */
        fun encodeServerList(servers: List<ServerConfigStore.Server>, activeUrl: String?): String {
            val list = JSONArray()
            for (server in servers) {
                val http = JSONObject().put("url", server.url)
                server.username?.let { http.put("username", it) }
                server.password?.let { http.put("password", it) }
                list.put(JSONObject().put("type", "http").put("http", http))
            }
            val obj = JSONObject().put("list", list)
            activeUrl?.takeIf { it.isNotBlank() }?.let { obj.put("active", it) }
            return obj.toString()
        }

        /**
         * `data-action` → boolean pref key; everything not in [SHELLS] etc.
         */
        val BOOLEAN_KEYS: Map<String, String> = mapOf(
            "settings-auto-accept-permissions" to KEY_AUTO_ACCEPT,
            "settings-feed-reasoning-summaries" to KEY_REASONING_SUMMARIES,
            "settings-feed-shell-tool-parts-expanded" to KEY_SHELL_EXPANDED,
            "settings-feed-edit-tool-parts-expanded" to KEY_EDIT_EXPANDED,
            "settings-notifications-agent" to KEY_NOTIFY_AGENT,
            "settings-notifications-permissions" to KEY_NOTIFY_PERMISSIONS,
            "settings-notifications-errors" to KEY_NOTIFY_ERRORS,
            "settings-show-file-tree" to KEY_SHOW_FILE_TREE,
            "settings-show-search" to KEY_SHOW_SEARCH,
            "settings-show-status" to KEY_SHOW_STATUS,
            "settings-show-custom-agents" to KEY_SHOW_CUSTOM_AGENTS,
            // WAVE 2.3a (A4 #9): the mobile-diff-wrap row writes the same
            // boolean the review diff renderer reads.
            "settings-mobile-diff-wrap" to KEY_MOBILE_DIFF_WRAP,
        )

        /**
         * Pure encoder for the keybind override table. Keys and values are
         * written verbatim; a blank value is dropped (it means "use the default",
         * which is the absence of an override).
         */
        fun encodeKeybinds(overrides: Map<String, String>): String {
            val obj = JSONObject()
            for ((id, value) in overrides) {
                if (id.isBlank() || value.isBlank()) continue
                obj.put(id, value)
            }
            return obj.toString()
        }

        /** Inverse of [encodeKeybinds]; malformed input reads as "no overrides". */
        fun parseKeybinds(raw: String?): Map<String, String> {
            val trimmed = raw?.trim().orEmpty()
            if (trimmed.isEmpty()) return emptyMap()
            val obj = try {
                JSONObject(trimmed)
            } catch (_: org.json.JSONException) {
                return emptyMap()
            }
            val out = linkedMapOf<String, String>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = obj.optString(key, "")
                if (key.isNotBlank() && value.isNotBlank()) out[key] = value
            }
            return out
        }

        /**
         * Pure encoder for the per-session effort table ([KEY_SESSION_VARIANTS]).
         * Blank halves are dropped, like the keybind table above.
         */
        fun encodeSessionVariants(variants: Map<String, String>): String {
            val obj = JSONObject()
            for ((id, variant) in variants) {
                if (id.isBlank() || variant.isBlank()) continue
                obj.put(id, variant)
            }
            return obj.toString()
        }

        /** Inverse of [encodeSessionVariants]; malformed input reads as "no pins". */
        fun parseSessionVariants(raw: String?): Map<String, String> = parseKeybinds(raw)

        /**
         * One entry of the table: `sessionID` → `variant`, with a blank/null
         * `variant` *removing* the entry (「默认」 is the absence of a pin, not a
         * stored label). The id is re-inserted at the end so [MAX_SESSION_VARIANTS]
         * drops the least recently pinned session, not the newest one.
         */
        fun withSessionVariant(
            variants: Map<String, String>,
            sessionID: String,
            variant: String?,
        ): Map<String, String> {
            if (sessionID.isBlank()) return variants
            val next = linkedMapOf<String, String>()
            for ((id, value) in variants) {
                if (id != sessionID && id.isNotBlank() && value.isNotBlank()) next[id] = value
            }
            if (!variant.isNullOrBlank()) next[sessionID] = variant
            if (next.size <= MAX_SESSION_VARIANTS) return next
            return next.entries.drop(next.size - MAX_SESSION_VARIANTS).associate { it.key to it.value }
        }

        /**
         * How many sessions the effort table keeps. A pin is a few bytes, but an
         * install that opens sessions for months must not grow the prefs file
         * without bound (`TerminalStateStore` caps its per-workspace tabs the
         * same way).
         */
        const val MAX_SESSION_VARIANTS = 200
    }
}
