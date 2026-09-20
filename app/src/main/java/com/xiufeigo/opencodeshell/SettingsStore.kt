package com.xiufeigo.opencodeshell

import android.content.Context
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

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(read())
    val state: StateFlow<Settings> = _state.asStateFlow()

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
        notifyErrors = prefs.getBoolean(KEY_NOTIFY_ERRORS, true),
        soundAgent = readEnum(KEY_SOUND_AGENT, SOUNDS, DEFAULT_SOUND),
        soundPermissions = readEnum(KEY_SOUND_PERMISSIONS, SOUNDS, DEFAULT_SOUND),
        soundErrors = readEnum(KEY_SOUND_ERRORS, SOUNDS, DEFAULT_SOUND),
        showFileTree = prefs.getBoolean(KEY_SHOW_FILE_TREE, true),
        showSearch = prefs.getBoolean(KEY_SHOW_SEARCH, true),
        showStatus = prefs.getBoolean(KEY_SHOW_STATUS, true),
        showCustomAgents = prefs.getBoolean(KEY_SHOW_CUSTOM_AGENTS, true),
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
        _state.value = read()
    }

    /** Convenience for the Switch rows. */
    fun setSwitch(action: String, checked: Boolean) = update(action, checked.toString())

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

        const val DEFAULT_THEME_ID = "oc-2"
        const val DEFAULT_SCHEME = "system"
        const val DEFAULT_LANGUAGE = "en"
        const val DEFAULT_SHELL = "auto"
        const val DEFAULT_SOUND = "default"

        val SCHEMES = setOf("system", "light", "dark")
        val LANGUAGES = setOf("en", "zh", "zht")
        val SHELLS = setOf("auto", "sh", "bash", "zsh")
        val SOUNDS = setOf("none", "default")

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

        /** `data-action` → boolean pref key; everything not in [SHELLS] etc. */
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
        )
    }
}
