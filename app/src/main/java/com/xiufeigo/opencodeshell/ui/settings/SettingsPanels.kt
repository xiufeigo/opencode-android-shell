package com.xiufeigo.opencodeshell.ui.settings

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiufeigo.opencodeshell.BuildConfig
import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.SettingsStore
import com.xiufeigo.opencodeshell.ui.theme.OcTheme
import kotlinx.coroutines.launch

/**
 * The `settings-v2` panels, driven by the pure tables in [SettingsModel].
 *
 * Sizes and structure are transcribed 1:1 from the vendored CSS
 * (`research/02` §5.2/§5.3): list = `layer-01`, radius 8, `padding-inline: 20px`;
 * row = `padding-block: 20px` + `0.5px` bottom border (none on `:last-child`).
 */

// ------------------------------------------------------------------- general

/**
 * `SettingsGeneralV2` (`settings/general/general.tsx:465-498`). Render order is
 * fixed by [GENERAL_PANEL]: General (untitled) → Timeline → Advanced.
 *
 * Updates / Display (desktop-only) are intentionally dropped on Android; the
 * Android-specific rows fold into the untitled first block.
 */
@Composable
fun GeneralPanel(settings: SettingsStore.Settings, store: SettingsStore) {
    SettingsPanelRows(GENERAL_PANEL, settings, store)
}

/**
 * `SettingsAppearanceV2` (`settings/appearance/appearance.tsx:64-131`): the color
 * scheme and the terminal font, under upstream's title + description header. The
 * theme picker and the UI/code font inputs stay hidden — see [APPEARANCE_PANEL].
 */
@Composable
fun AppearancePanel(settings: SettingsStore.Settings, store: SettingsStore) {
    SettingsPanelRows(
        panel = APPEARANCE_PANEL,
        settings = settings,
        store = store,
        headerTitleResId = SettingsTab.APPEARANCE.headerTitleResId(),
        descriptionResId = SettingsTab.APPEARANCE.pageDescriptionResId(),
    )
}

/**
 * `SettingsNotificationsV2` (`settings/notifications/notifications.tsx:56-127`)
 * reduced to the three switches W5-E5's in-app feed consumes. The sound selects
 * are hidden — see [NOTIFICATIONS_PANEL].
 */
@Composable
fun NotificationsPanel(settings: SettingsStore.Settings, store: SettingsStore) {
    SettingsPanelRows(
        panel = NOTIFICATIONS_PANEL,
        settings = settings,
        store = store,
        headerTitleResId = SettingsTab.NOTIFICATIONS.headerTitleResId(),
        descriptionResId = SettingsTab.NOTIFICATIONS.pageDescriptionResId(),
    )
}

@Composable
private fun SettingsPanelRows(
    panel: List<SettingsSection>,
    settings: SettingsStore.Settings,
    store: SettingsStore,
    headerTitleResId: Int? = null,
    descriptionResId: Int? = null,
) {
    if (headerTitleResId == null) {
        SettingsPanelBody { SettingsPanelSections(panel, settings, store) }
        return
    }
    Column(Modifier.fillMaxSize()) {
        SettingsPanelHeader(
            titleResId = headerTitleResId,
            below = descriptionResId?.let { { OcBodyText(stringResource(it), muted = true) } },
        )
        SettingsPanelBody { SettingsPanelSections(panel, settings, store) }
    }
}

@Composable
private fun SettingsPanelSections(
    panel: List<SettingsSection>,
    settings: SettingsStore.Settings,
    store: SettingsStore,
) {
    for (section in panel) {
        SettingsSectionBlock(section.titleResId) {
            SettingsList {
                section.rows.forEachIndexed { index, row ->
                    SettingsRow(
                        titleResId = row.titleResId,
                        descriptionResId = row.descriptionResId,
                        last = index == section.rows.lastIndex,
                    ) {
                        SettingsRowControl(row, settings, store)
                    }
                }
            }
        }
    }
}

/** One row's control, driven purely by [SettingsControl]. */
@Composable
private fun SettingsRowControl(
    row: SettingsRow,
    settings: SettingsStore.Settings,
    store: SettingsStore,
) {
    if (row.action == "settings-timeline-detail") {
        TimelineDetailControl(settings, store)
        return
    }
    if (row.action == "settings-follow-up-behavior") {
        FollowUpBehaviorControl(settings, store)
        return
    }
    when (row.control) {
        SettingsControl.SWITCH -> OcSwitch(
            checked = stringValue(settings, row.action) == "true",
            onCheckedChange = { store.setSwitch(row.action, it) },
        )

        SettingsControl.SELECT -> {
            val current = stringValue(settings, row.action)
            val options = row.options.map { it.value to stringResource(it.labelResId) }
            OcSelect(
                value = options.firstOrNull { it.first == current }?.second ?: current,
                options = options,
                onSelect = { store.update(row.action, it) },
            )
        }

        SettingsControl.TEXT -> OcTextField(
            value = stringValue(settings, row.action),
            onValueChange = { store.update(row.action, it) },
            placeholder = stringResource(R.string.oc_setting_font_default),
        )
    }
}

/**
 * `FollowUpBehaviorControl` (`FollowUpBehaviorSetting`, `general.tsx:157-184`).
 */
@Composable
private fun FollowUpBehaviorControl(settings: SettingsStore.Settings, store: SettingsStore) {
    val template = stringResource(R.string.settings_general_row_followUpBehavior_description)
    val description = remember(template) {
        template.replace("%1\$s", "Ctrl+Enter")
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val current = stringValue(settings, "settings-follow-up-behavior")
        val options = FOLLOW_UP_BEHAVIOR_OPTIONS.map { it.value to stringResource(it.labelResId) }
        OcSelect(
            value = options.firstOrNull { it.first == current }?.second ?: current,
            options = options,
            onSelect = { store.update("settings-follow-up-behavior", it) },
        )
        OcBodyText(description, muted = true)
    }
}

/**
 * `TimelineDetailControl` (`settings/timeline-detail.tsx:21-68`) reduced to the
 * three booleans the native timeline consumes: the preset dropdown replaces all
 * three at once, and the current value is *detected* — a combination outside the
 * presets reads as `Custom` (upstream `timelinePreset(props.value)` + the
 * `settings.timeline.custom` label), while the three switches below stay the
 * advanced/granular control (upstream's collapsible "Advanced" block).
 */
@Composable
private fun TimelineDetailControl(settings: SettingsStore.Settings, store: SettingsStore) {
    val current = TimelineDetailPreset.of(
        shellExpanded = settings.shellToolPartsExpanded,
        editExpanded = settings.editToolPartsExpanded,
        reasoningShown = settings.reasoningSummaries,
    )
    val options = TimelineDetailPreset.Options.map { it.id to stringResource(it.labelResId) }
    OcSelect(
        value = current?.let { stringResource(it.labelResId) }
            ?: stringResource(R.string.oc_settings_timeline_custom),
        options = options,
        onSelect = { store.update("settings-timeline-detail", it) },
    )
}

/**
 * Reads the current value of a `data-action` as a string — the single bridge
 * between the pure [SettingsRow] table and [SettingsStore.Settings].
 */
fun stringValue(settings: SettingsStore.Settings, action: String): String = when (action) {
    "settings-language" -> settings.language
    "settings-auto-accept-permissions" -> settings.autoAcceptPermissions.toString()
    "settings-shell" -> settings.shell
    "settings-terminal-placement" -> settings.terminalPlacement
    "settings-feed-reasoning-summaries" -> settings.reasoningSummaries.toString()
    "settings-feed-shell-tool-parts-expanded" -> settings.shellToolPartsExpanded.toString()
    "settings-feed-edit-tool-parts-expanded" -> settings.editToolPartsExpanded.toString()
    "settings-timeline-detail" -> TimelineDetailPreset.of(
        shellExpanded = settings.shellToolPartsExpanded,
        editExpanded = settings.editToolPartsExpanded,
        reasoningShown = settings.reasoningSummaries,
    )?.id ?: "custom"

    "settings-color-scheme" -> settings.colorScheme
    "settings-theme" -> settings.themeId
    "settings-ui-font" -> settings.uiFont
    "settings-code-font" -> settings.codeFont
    "settings-terminal-font" -> settings.terminalFont
    "settings-notifications-agent" -> settings.notifyAgent.toString()
    "settings-notifications-permissions" -> settings.notifyPermissions.toString()
    "settings-notifications-errors" -> settings.notifyErrors.toString()
    "settings-sounds-agent" -> settings.soundAgent
    "settings-sounds-permissions" -> settings.soundPermissions
    "settings-sounds-errors" -> settings.soundErrors
    "settings-show-file-tree" -> settings.showFileTree.toString()
    "settings-show-search" -> settings.showSearch.toString()
    "settings-show-status" -> settings.showStatus.toString()
    "settings-show-custom-agents" -> settings.showCustomAgents.toString()
    "settings-follow-up-behavior" -> settings.followUpBehavior
    "settings-reasoning-mode" -> settings.reasoningMode
    "settings-workspace-destination" -> settings.workspaceDefaultDestination
    "settings-mobile-diff-wrap" -> settings.mobileDiffWrap.toString()
    else -> ""
}

// ------------------------------------------------------------------ shortcuts

/**
 * `SettingsKeybinds` with the **record → conflict-check → persist** flow
 * (`settings/keybinds/keybinds.tsx:248-338`):
 *
 * * tap a binding → the chip reads "Press keys" and the panel takes the keyboard;
 * * `Backspace` / `Delete` (no modifier) writes `"none"` = cleared;
 * * `Escape` cancels; a bare modifier keeps waiting;
 * * a chord already used by another row is **rejected** and the conflict is named
 *   inline (upstream shows the same as a toast);
 * * "Reset to defaults" clears the whole override table (disabled while there is
 *   nothing to reset).
 *
 * The table itself is `ui/App.kt`'s binding list, so what a row shows is what the
 * shell already implements. The recorded override is persisted in
 * [SettingsStore] and displayed from there; wiring it into `globalChord` is a
 * Lead-level change (`research/wave5_settings.md` §D4).
 */
@Composable
fun ShortcutsPanel(settings: SettingsStore.Settings, store: SettingsStore) {
    var recording by remember { mutableStateOf<String?>(null) }
    var conflict by remember { mutableStateOf<ShortcutConflict?>(null) }
    var query by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }

    val rows = remember(settings.keybinds) { applyKeybindOverrides(settings.keybinds) }
    // Titles are resolved once here (they are resource ids) so the filter stays a
    // plain string match on title + binding, like upstream's fuzzysort target
    // keys `["title", "keybind"]` (`keybinds.tsx:183-186`).
    val titleById = rows.associate { it.id to stringResource(it.titleResId) }
    val grouped = remember(rows, query, titleById) {
        val lowered = query.trim().lowercase()
        val filtered = if (lowered.isEmpty()) rows else rows.filter {
            it.keybind.lowercase().contains(lowered) ||
                titleById[it.id]?.lowercase()?.contains(lowered) == true
        }
        shortcutsByGroup(filtered)
    }
    val hasOverrides = settings.keybinds.isNotEmpty()

    LaunchedEffect(recording) {
        if (recording != null) runCatching { focusRequester.requestFocus() }
    }

    Column(
        Modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                val id = recording ?: return@onPreviewKeyEvent false
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent true
                if (event.key == Key.Escape) {
                    recording = null
                    conflict = null
                    return@onPreviewKeyEvent true
                }
                val ctrl = event.isCtrlPressed
                val alt = event.isAltPressed
                val shift = event.isShiftPressed
                val meta = event.isMetaPressed
                if ((event.key == Key.Backspace || event.key == Key.Delete) && !ctrl && !alt && !shift && !meta) {
                    store.clearKeybind(id)
                    recording = null
                    conflict = null
                    return@onPreviewKeyEvent true
                }
                val name = normalizeKeyName(AndroidKeyEvent.keyCodeToString(event.key.keyCode.toInt()))
                    ?: return@onPreviewKeyEvent true
                val candidate = formatKeybindChord(ctrl, alt, shift, meta, name)
                val clashes = keybindConflicts(candidate, rows, id)
                if (clashes.isEmpty()) {
                    store.setKeybind(id, candidate)
                    recording = null
                    conflict = null
                } else {
                    // Upstream stays in capture mode and reports the clash
                    // (`keybinds.tsx:295-304`); nothing is written.
                    conflict = ShortcutConflict(candidate, clashes)
                }
                true
            },
    ) {
        SettingsPanelHeader(
            titleResId = SettingsTab.SHORTCUTS.headerTitleResId(),
            trailing = {
                OcButton(
                    label = stringResource(R.string.oc_settings_shortcuts_reset),
                    enabled = hasOverrides,
                    primary = false,
                    onClick = {
                        recording = null
                        conflict = null
                        store.resetKeybinds()
                    },
                )
            },
            below = {
                OcTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = stringResource(R.string.oc_settings_shortcuts_search),
                    imeAction = ImeAction.Search,
                )
            },
        )
        SettingsPanelBody {
            conflict?.let { notice ->
                val titles = notice.rows.map { stringResource(it.titleResId) }.joinToString()
                OcBodyText(
                    stringResource(R.string.oc_settings_shortcuts_conflict, notice.candidate, titles),
                    muted = false,
                )
            }
            if (grouped.isEmpty()) {
                OcBodyText(stringResource(R.string.oc_settings_shortcuts_empty), muted = true)
            }
            for ((group, groupRows) in grouped) {
                SettingsSectionBlock(group.titleResId()) {
                    SettingsList {
                        groupRows.forEachIndexed { index, row ->
                            SettingsRow(
                                titleResId = row.titleResId,
                                descriptionResId = null,
                                last = index == groupRows.lastIndex,
                            ) {
                                KeybindChip(
                                    text = when {
                                        recording == row.id -> stringResource(R.string.oc_shortcut_press_keys)
                                        isKeybindUnassigned(row.keybind) ->
                                            stringResource(R.string.oc_shortcut_unassigned)

                                        else -> row.keybind
                                    },
                                    recording = recording == row.id,
                                    onClick = {
                                        conflict = null
                                        recording = if (recording == row.id) null else row.id
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A rejected recording: the candidate chord and the rows that already use it. */
private data class ShortcutConflict(val candidate: String, val rows: List<ShortcutRow>)

// ---------------------------------------------------------------------- about

/**
 * `SettingsAbout` (`settings/about/about.tsx:30-93`) — the native subset: the
 * version/license intro and the publication footer. The credits/wordmark/legal
 * art are desktop assets this shell does not ship, so they are not drawn at all
 * rather than stubbed.
 */
@Composable
fun AboutPanel() {
    val c = OcTheme.colors
    val uriHandler = LocalUriHandler.current
    Column(Modifier.fillMaxSize()) {
        SettingsPanelHeader(titleResId = SettingsTab.ABOUT.headerTitleResId())
        SettingsPanelBody {
            SettingsList {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = stringResource(R.string.oc_settings_app_name),
                        color = c.textBase,
                        fontSize = 15.sp,
                        fontWeight = FontWeight(640),
                    )
                    OcBodyText(stringResource(R.string.oc_about_version, BuildConfig.VERSION_NAME), muted = true)
                    OcBodyText(stringResource(R.string.oc_about_license), muted = true)
                }
                RowSeparator()
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OcBodyText(stringResource(R.string.oc_about_description), muted = true)
                    OcButton(
                        label = stringResource(R.string.oc_about_website),
                        primary = false,
                        onClick = { uriHandler.openUri(OPENCODE_WEBSITE) },
                    )
                    OcBodyText(stringResource(R.string.oc_about_copyright), muted = true)
                }
            }
        }
    }
}

/** `settings.about.website` points at the public site (`about.tsx:74-77`). */
private const val OPENCODE_WEBSITE = "https://opencode.ai"

// ----------------------------------------------- read-only server pages (D1)

/**
 * Loads one read-only page. `null` while the request is in flight; a source-less
 * dialog (previews, tests) resolves to [SettingsPageLoad.NoServer] so the page
 * says why it is empty instead of silently showing nothing.
 */
@Composable
private fun <T> rememberServerPage(
    source: SettingsServerData?,
    reloadKey: Int,
    load: suspend (SettingsServerData) -> SettingsPageLoad<T>,
): SettingsPageLoad<T>? {
    var value by remember(source, reloadKey) { mutableStateOf<SettingsPageLoad<T>?>(null) }
    LaunchedEffect(source, reloadKey) {
        value = if (source == null) SettingsPageLoad.NoServer else load(source)
    }
    return value
}

/**
 * `SettingsProjects` (`settings/workspaces/projects.tsx`) as a **read-only** list:
 * name + worktree + vcs, straight from `GET /project`.
 *
 * No create/delete/rename action is drawn: upstream's project settings edit the
 * server's project store, and this wave adds no write endpoints
 * (`OpenCodeClient` is owned by another wave).
 */
@Composable
fun ProjectsPanel(source: SettingsServerData?) {
    var reload by remember { mutableStateOf(0) }
    var query by remember { mutableStateOf("") }
    val state = rememberServerPage(source, reload) { it.projects() }
    ServerPageScaffold(
        titleResId = SettingsTab.PROJECTS.headerTitleResId(),
        descriptionResId = SettingsTab.PROJECTS.pageDescriptionResId(),
        state = state,
        onReload = { reload++ },
    ) { rows ->
        if (rows.isEmpty()) {
            OcBodyText(stringResource(R.string.oc_settings_projects_empty), muted = true)
        } else {
            if (showsProjectSearch(rows.size)) {
                OcTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = stringResource(R.string.settings_projects_search_placeholder),
                    imeAction = ImeAction.Search,
                )
                Spacer(Modifier.height(12.dp))
            }
            val filtered = remember(rows, query) { filterProjectRows(rows, query) }
            if (filtered.isEmpty()) {
                OcBodyText(
                    stringResource(R.string.settings_search_empty_query, query),
                    muted = true,
                )
            } else {
                SettingsSectionBlock(R.string.oc_settings_projects_section) {
                    SettingsList {
                        filtered.forEachIndexed { index, row ->
                            ServerListRow(
                                title = row.name,
                                subtitle = row.worktree,
                                tag = row.vcs,
                            )
                            if (index != filtered.lastIndex) RowSeparator()
                        }
                    }
                }
            }
        }
    }
}

/**
 * `SettingsWorkspaces` (`settings/workspaces/workspaces.tsx`): the
 * `GET /experimental/worktree` list plus the delete flow.
 *
 * WAVE 2.3a (A4 #17): each row carries a Delete action guarded by
 * [worktreeDeleteGuard] — an active location or linked sessions block with an
 * error, a dirty tree needs the force confirm. The preflight signals the
 * native client cannot compute (vcs status, session scope) are assumed clean;
 * the guards that CAN be evaluated (active/blocked) still apply, and the
 * confirm dialog always shows the upstream warning copy. The write itself is
 * `DELETE /api/worktree` via [SettingsServerData.deleteWorktree].
 */
@Composable
fun WorktreesPanel(source: SettingsServerData?) {
    var reload by remember { mutableStateOf(0) }
    var pendingDelete by remember { mutableStateOf<WorktreePageRow?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val state = rememberServerPage(source, reload) { it.worktrees() }
    ServerPageScaffold(
        titleResId = SettingsTab.WORKSPACES.headerTitleResId(),
        descriptionResId = SettingsTab.WORKSPACES.pageDescriptionResId(),
        state = state,
        onReload = { reload++ },
    ) { rows ->
        if (rows.isEmpty()) {
            OcBodyText(stringResource(R.string.oc_settings_worktrees_empty), muted = true)
        } else {
            failure?.let { ServerPageMessage(it) }
            SettingsSectionBlock(R.string.oc_settings_worktrees_section) {
                SettingsList {
                    rows.forEachIndexed { index, row ->
                        WorktreeRow(
                            row = row,
                            canDelete = source != null && !deleting,
                            onDelete = { pendingDelete = row },
                        )
                        if (index != rows.lastIndex) RowSeparator()
                    }
                }
            }
        }
    }
    pendingDelete?.let { row ->
        WorktreeDeleteDialog(
            row = row,
            deleting = deleting,
            onDismiss = { if (!deleting) pendingDelete = null },
            onConfirm = { force ->
                if (source == null) return@WorktreeDeleteDialog
                deleting = true
                failure = null
                scope.launch {
                    val result = source.deleteWorktree(row.directory, force)
                    deleting = false
                    when (result) {
                        ProviderWriteResult.Done -> {
                            pendingDelete = null
                            reload++
                        }
                        ProviderWriteResult.Unsupported -> {
                            pendingDelete = null
                            failure = "unsupported"
                        }
                        is ProviderWriteResult.Failed -> {
                            pendingDelete = null
                            failure = result.reason
                        }
                    }
                }
            },
        )
    }
}

@Composable
private fun WorktreeRow(row: WorktreePageRow, canDelete: Boolean, onDelete: () -> Unit) {
    val c = OcTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(row.name, color = c.textBase, fontSize = 13.sp, fontWeight = FontWeight(530))
            Text(
                text = row.directory,
                color = c.textMuted,
                fontSize = 11.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (row.branch != null) {
            Spacer(Modifier.width(8.dp))
            OcTag(row.branch)
        }
        Spacer(Modifier.width(8.dp))
        OcButton(
            label = stringResource(R.string.settings_workspaces_delete_button),
            primary = false,
            enabled = canDelete,
            onClick = onDelete,
        )
    }
}

@Composable
private fun WorktreeDeleteDialog(row: WorktreePageRow, deleting: Boolean, onDismiss: () -> Unit, onConfirm: (Boolean) -> Unit) {
    var force by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().padding(vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OcBodyText(stringResource(R.string.workspace_delete_confirm, row.name), muted = false)
        OcBodyText(stringResource(R.string.settings_workspaces_delete_warning), muted = true)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OcSwitch(checked = force, enabled = !deleting, onCheckedChange = { force = it })
            OcBodyText(stringResource(R.string.workspace_status_dirty), muted = true)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OcButton(
                label = stringResource(R.string.common_cancel),
                primary = false,
                enabled = !deleting,
                onClick = onDismiss,
            )
            OcButton(
                label = stringResource(R.string.common_delete),
                enabled = !deleting,
                onClick = { onConfirm(force) },
            )
        }
    }
}

/**
 * `SettingsExtensions` (`settings/providers/extensions.tsx:24-169`) as three
 * catalogs: the MCP servers (`GET /mcp`), the skills (`GET /skill`) and the
 * plugins (`GET /api/plugin`, v2 only).
 *
 * WAVE 2.3a (A4 #18): the MCP **toggle** is wired — `useMcpToggle` posts
 * `POST /api/experimental/mcp/{server}/{connect,disconnect}` and the row
 * Switch calls [SettingsServerData.setMcpEnabled]. On a v1 server (or while
 * the write is in flight) the Switch disables itself instead of lying.
 * The plugins subtab renders the v2 plugin list; on v1 it shows the documented
 * empty state. Subtabs are `mcps`/`plugins`/`skills` pill tabs
 * (`settings.extensions.tab.*`, plugins title reuses
 * `status.popover.tab.plugins`).
 */
@Composable
fun ExtensionsPanel(source: SettingsServerData?) {
    var reload by remember { mutableStateOf(0) }
    var subtab by remember { mutableStateOf(ExtensionSubtab.MCPS) }
    val mcp = rememberServerPage(source, reload) { it.mcpServers() }
    val skills = rememberServerPage(source, reload) { it.skills() }
    val plugins = rememberServerPage(source, reload) { it.plugins() }
    Column(Modifier.fillMaxSize()) {
        SettingsPanelHeader(
            titleResId = SettingsTab.EXTENSIONS.headerTitleResId(),
            trailing = { ReloadButton(onClick = { reload++ }) },
            below = {
                SettingsTab.EXTENSIONS.pageDescriptionResId()?.let {
                    OcBodyText(stringResource(it), muted = true)
                }
            },
        )
        ExtensionSubtabBar(current = subtab, onSelect = { subtab = it })
        SettingsPanelBody {
            when (subtab) {
                ExtensionSubtab.MCPS -> ExtensionMcpList(
                    state = mcp,
                    source = source,
                    onReload = { reload++ },
                )
                ExtensionSubtab.PLUGINS -> ExtensionPluginList(state = plugins)
                ExtensionSubtab.SKILLS -> ExtensionSkillList(state = skills)
            }
        }
    }
}

/** Pill subtabs of the Extensions page (`mcps`/`plugins`/`skills`). */
private enum class ExtensionSubtab { MCPS, PLUGINS, SKILLS }

@Composable
private fun ExtensionSubtabBar(current: ExtensionSubtab, onSelect: (ExtensionSubtab) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 40.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ExtensionSubtabChip(
            label = stringResource(R.string.settings_extensions_tab_mcps),
            selected = current == ExtensionSubtab.MCPS,
            onClick = { onSelect(ExtensionSubtab.MCPS) },
        )
        ExtensionSubtabChip(
            label = stringResource(R.string.status_popover_tab_plugins),
            selected = current == ExtensionSubtab.PLUGINS,
            onClick = { onSelect(ExtensionSubtab.PLUGINS) },
        )
        ExtensionSubtabChip(
            label = stringResource(R.string.settings_extensions_tab_skills),
            selected = current == ExtensionSubtab.SKILLS,
            onClick = { onSelect(ExtensionSubtab.SKILLS) },
        )
    }
}

@Composable
private fun ExtensionSubtabChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val c = OcTheme.colors
    Box(
        Modifier
            .background(if (selected) c.layer02 else Color.Transparent, RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (selected) c.textBase else c.textMuted,
            fontSize = 13.sp,
            fontWeight = FontWeight(530),
        )
    }
}

@Composable
private fun ExtensionMcpList(
    state: SettingsPageLoad<List<McpPageRow>>?,
    source: SettingsServerData?,
    onReload: () -> Unit,
) {
    var pending by remember { mutableStateOf<String?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    when (state) {
        null -> OcBodyText(stringResource(R.string.oc_common_loading), muted = true)
        SettingsPageLoad.NoServer -> ServerPageMessage(stringResource(R.string.oc_settings_page_no_server))
        is SettingsPageLoad.Failed ->
            ServerPageMessage(stringResource(R.string.oc_settings_page_failed, state.reason))

        is SettingsPageLoad.Loaded -> {
            val rows = state.rows
            failure?.let { ServerPageMessage(it) }
            val unsupportedText = stringResource(R.string.oc_settings_mcp_unsupported)
            val pageFailedFormat = stringResource(R.string.oc_settings_page_failed, "")
            SettingsSectionBlock(R.string.oc_settings_mcp_section) {
                SettingsList {
                    if (rows.isEmpty()) {
                        Column(Modifier.fillMaxWidth().padding(vertical = 20.dp)) {
                            OcBodyText(stringResource(R.string.oc_settings_mcp_empty), muted = true)
                        }
                    } else {
                        rows.forEachIndexed { index, row ->
                            McpToggleRow(
                                row = row,
                                enabled = source != null && pending == null,
                                checked = row.connected,
                                busy = pending == row.name,
                                onCheckedChange = { checked ->
                                    if (pending != null || source == null) return@McpToggleRow
                                    pending = row.name
                                    failure = null
                                    scope.launch {
                                        val result = source.setMcpEnabled(row.name, checked)
                                        pending = null
                                        when (result) {
                                            ProviderWriteResult.Done -> onReload()
                                            ProviderWriteResult.Unsupported ->
                                                failure = unsupportedText

                                            is ProviderWriteResult.Failed ->
                                                failure = pageFailedFormat.replace("%1\$s", result.reason)
                                        }
                                    }
                                },
                            )
                            if (index != rows.lastIndex) RowSeparator()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun McpToggleRow(
    row: McpPageRow,
    enabled: Boolean,
    checked: Boolean,
    busy: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = row.name,
                color = OcTheme.colors.textBase,
                fontSize = 13.sp,
                fontWeight = FontWeight(530),
            )
            row.error?.let {
                Text(
                    text = it,
                    color = OcTheme.colors.textMuted,
                    fontSize = 11.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (busy) {
                OcBodyText(stringResource(R.string.oc_common_loading), muted = true)
            }
        }
        OcSwitch(checked = checked, enabled = enabled && !busy, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ExtensionPluginList(state: SettingsPageLoad<List<PluginPageRow>>?) {
    when (state) {
        null -> OcBodyText(stringResource(R.string.oc_common_loading), muted = true)
        SettingsPageLoad.NoServer -> ServerPageMessage(stringResource(R.string.oc_settings_page_no_server))
        is SettingsPageLoad.Failed ->
            ServerPageMessage(stringResource(R.string.oc_settings_page_failed, state.reason))

        is SettingsPageLoad.Loaded -> {
            val rows = state.rows
            SettingsSectionBlock(R.string.status_popover_tab_plugins) {
                SettingsList {
                    if (rows.isEmpty()) {
                        Column(Modifier.fillMaxWidth().padding(vertical = 20.dp)) {
                            OcBodyText(stringResource(R.string.oc_settings_plugins_empty), muted = true)
                        }
                    } else {
                        rows.forEachIndexed { index, row ->
                            Column(
                                Modifier.fillMaxWidth().padding(vertical = 20.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text(
                                    text = row.name,
                                    color = OcTheme.colors.textBase,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight(530),
                                )
                            }
                            if (index != rows.lastIndex) RowSeparator()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExtensionSkillList(state: SettingsPageLoad<List<SkillPageRow>>?) {
    when (state) {
        null -> OcBodyText(stringResource(R.string.oc_common_loading), muted = true)
        SettingsPageLoad.NoServer -> ServerPageMessage(stringResource(R.string.oc_settings_page_no_server))
        is SettingsPageLoad.Failed ->
            ServerPageMessage(stringResource(R.string.oc_settings_page_failed, state.reason))

        is SettingsPageLoad.Loaded -> {
            val rows = state.rows
            SettingsSectionBlock(R.string.oc_settings_skills_section) {
                SettingsList {
                    if (rows.isEmpty()) {
                        Column(Modifier.fillMaxWidth().padding(vertical = 20.dp)) {
                            OcBodyText(stringResource(R.string.oc_settings_skills_empty), muted = true)
                        }
                    } else {
                        rows.forEachIndexed { index, row ->
                            Column(
                                Modifier.fillMaxWidth().padding(vertical = 20.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text(
                                    text = row.name,
                                    color = OcTheme.colors.textBase,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight(530),
                                )
                                row.description?.let {
                                    Text(
                                        text = it,
                                        color = OcTheme.colors.textMuted,
                                        fontSize = 11.sp,
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            if (index != rows.lastIndex) RowSeparator()
                        }
                    }
                }
            }
        }
    }
}

/** Shared chrome for the two single-list read-only pages. */
@Composable
private fun <T> ServerPageScaffold(
    titleResId: Int,
    descriptionResId: Int?,
    state: SettingsPageLoad<T>?,
    onReload: () -> Unit,
    list: @Composable (T) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        SettingsPanelHeader(
            titleResId = titleResId,
            trailing = { ReloadButton(onClick = onReload) },
            below = descriptionResId?.let { { OcBodyText(stringResource(it), muted = true) } },
        )
        SettingsPanelBody {
            when (state) {
                null -> OcBodyText(stringResource(R.string.oc_common_loading), muted = true)
                SettingsPageLoad.NoServer -> ServerPageMessage(stringResource(R.string.oc_settings_page_no_server))
                is SettingsPageLoad.Failed ->
                    ServerPageMessage(stringResource(R.string.oc_settings_page_failed, state.reason))

                is SettingsPageLoad.Loaded -> list(state.rows)
            }
        }
    }
}

/** One name + path (or branch) row of a read-only page. */
@Composable
private fun ServerListRow(title: String, subtitle: String, tag: String?) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = OcTheme.colors.textBase, fontSize = 13.sp, fontWeight = FontWeight(530))
            Text(
                text = subtitle,
                color = OcTheme.colors.textMuted,
                fontSize = 11.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (tag != null) {
            Spacer(Modifier.width(8.dp))
            OcTag(tag)
        }
    }
}

@Composable
private fun ServerPageMessage(text: String) {
    OcBodyText(text, muted = true)
}

/** Reload is a real action: it re-runs the page's fetch, not a decorative button. */
@Composable
private fun ReloadButton(onClick: () -> Unit) {
    OcButton(
        label = stringResource(R.string.oc_settings_page_reload),
        primary = false,
        onClick = onClick,
    )
}

// ------------------------------------------------------------ providers/models

/**
 * `SettingsProvidersV2` — the real `GET /provider` catalog
 * (`providers/catalog/providers.ts:14-86`).
 *
 * WAVE 2.3a (A4 #1): Connected + Popular sections (`providers.tsx:134-213`)
 * with source badges, provider notes, the recommended badge and the view-all
 * connect entry. Connect (API-key form → `POST
 * /api/integration/{id}/connect/key`) and Disconnect (credential removal →
 * `DELETE /api/credential/{id}`) run through [SettingsServerData]; on a v1
 * server the buttons report `Unsupported` instead of firing. The "view all"
 * entry opens the full provider picker (search + custom-provider note) as a
 * second step of the same panel — no dialog-stack dependency.
 */
@Composable
fun ProvidersPanel(extra: SettingsPanelExtras, store: SettingsStore? = null) {
    val providers = extra.providers?.collectAsState()?.value
    val baseRows = remember(providers) { providerRows(providers) }
    var integrations by remember { mutableStateOf<List<ProviderIntegrationRow>>(emptyList()) }
    var viewAll by remember { mutableStateOf(false) }
    var busyId by remember { mutableStateOf<String?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var connectId by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val serverData = extra.serverBridge as? SettingsServerData

    LaunchedEffect(serverData, providers) {
        integrations = serverData?.integrations() ?: emptyList()
    }

    val byIntegration = remember(integrations) { integrations.associateBy { it.id } }
    val enriched = remember(baseRows, byIntegration) {
        baseRows.map { row ->
            val integration = byIntegration[row.id]
            val types = integration?.connectionTypes ?: emptyList()
            val has = integration?.hasIntegration ?: false
            row.copy(
                source = ProviderConnectCatalog.resolveSource(types, has, catalogSource = null),
                disconnectable = row.connected && ProviderConnectCatalog.canDisconnect(types, has, null),
            )
        }
    }
    val rows = remember(enriched) { ProviderConnectCatalog.providerSourceRows(enriched) }
    val connectedRows = remember(rows) { rows.filter { it.connected } }
    val popularRows = remember(rows) { rows.filter { !it.connected } }
    val connected = connectedRows.size
    val total = providers?.all?.size ?: 0
    val shownPopular = remember(popularRows, viewAll) {
        if (viewAll) popularRows else popularRows.take(POPULAR_VISIBLE_COUNT)
    }

    fun refreshIntegrations() {
        scope.launch { integrations = serverData?.integrations() ?: emptyList() }
    }

    Column(Modifier.fillMaxSize()) {
        SettingsPanelHeader(
            titleResId = SettingsTab.PROVIDERS.headerTitleResId(),
            below = if (providers == null) {
                null
            } else {
                {
                    OcBodyText(
                        stringResource(R.string.oc_settings_providers_connected, connected),
                        muted = true,
                    )
                    Spacer(Modifier.height(2.dp))
                    OcBodyText(stringResource(R.string.oc_settings_providers_total, total), muted = true)
                    failure?.let {
                        Spacer(Modifier.height(2.dp))
                        OcBodyText(it, muted = false)
                    }
                }
            },
        )
        SettingsPanelBody {
            when {
                extra.providers == null ->
                    OcBodyText(stringResource(R.string.oc_settings_providers_unavailable), muted = true)

                providers == null -> OcBodyText(stringResource(R.string.oc_common_loading), muted = true)
                rows.isEmpty() -> OcBodyText(stringResource(R.string.oc_providers_empty), muted = true)
                else -> {
                    SettingsSectionBlock(R.string.settings_providers_section_connected) {
                        SettingsList {
                            if (connectedRows.isEmpty()) {
                                Column(Modifier.fillMaxWidth().padding(vertical = 20.dp)) {
                                    OcBodyText(
                                        stringResource(R.string.settings_providers_connected_empty),
                                        muted = true,
                                    )
                                }
                            } else {
                                connectedRows.forEachIndexed { index, row ->
                                    ProviderConnectedRow(
                                        row = row,
                                        busy = busyId == row.id,
                                        canAct = serverData != null && busyId == null,
                                        onDisconnect = {
                                            if (serverData == null) return@ProviderConnectedRow
                                            busyId = row.id
                                            failure = null
                                            scope.launch {
                                                val result = serverData.disconnectProvider(row.id)
                                                busyId = null
                                                when (result) {
                                                    ProviderWriteResult.Done -> refreshIntegrations()
                                                    ProviderWriteResult.Unsupported ->
                                                        failure = "unsupported"

                                                    is ProviderWriteResult.Failed -> failure = result.reason
                                                }
                                            }
                                        },
                                    )
                                    if (index != connectedRows.lastIndex) RowSeparator()
                                }
                            }
                        }
                    }
                    SettingsSectionBlock(R.string.settings_providers_section_popular) {
                        SettingsList {
                            shownPopular.forEachIndexed { index, row ->
                                ProviderPopularRow(
                                    row = row,
                                    canAct = serverData != null && busyId == null,
                                    onConnect = { connectId = row.id },
                                )
                                if (index != shownPopular.lastIndex) RowSeparator()
                            }
                        }
                        if (!viewAll && popularRows.size > shownPopular.size) {
                            OcButton(
                                label = stringResource(R.string.dialog_provider_viewAll),
                                primary = false,
                                onClick = { viewAll = true },
                            )
                        }
                    }
                    connectId?.let { id ->
                        ProviderConnectSheet(
                            providerID = id,
                            busy = busyId == id,
                            canAct = serverData != null,
                            onDismiss = { if (busyId == null) connectId = null },
                            onSubmit = { key ->
                                if (serverData == null) return@ProviderConnectSheet
                                busyId = id
                                failure = null
                                scope.launch {
                                    val result = serverData.connectProviderKey(id, key)
                                    busyId = null
                                    when (result) {
                                        ProviderWriteResult.Done -> {
                                            connectId = null
                                            refreshIntegrations()
                                        }
                                        ProviderWriteResult.Unsupported -> failure = "unsupported"
                                        is ProviderWriteResult.Failed -> failure = result.reason
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

/** Visible popular rows before "view all" expands the list. */
private const val POPULAR_VISIBLE_COUNT = 8

@Composable
private fun ProviderConnectedRow(row: ProviderRow, busy: Boolean, canAct: Boolean, onDisconnect: () -> Unit) {
    val c = OcTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(row.name, color = c.textBase, fontSize = 13.sp, fontWeight = FontWeight530)
                Spacer(Modifier.width(8.dp))
                OcTag(
                    stringResource(
                        when (row.source) {
                            ProviderConnectCatalog.ProviderSource.ENV -> R.string.settings_providers_tag_environment
                            ProviderConnectCatalog.ProviderSource.API -> R.string.provider_connect_method_apiKey
                            ProviderConnectCatalog.ProviderSource.CONFIG -> R.string.settings_providers_tag_config
                            ProviderConnectCatalog.ProviderSource.CUSTOM -> R.string.settings_providers_tag_custom
                            else -> R.string.settings_providers_tag_other
                        },
                    ),
                    accent = true,
                )
            }
            Text(row.id, color = c.textMuted, fontSize = 13.sp, fontWeight = FontWeight440)
            Text(
                text = stringResource(R.string.oc_settings_provider_models, row.modelCount),
                color = c.textMuted,
                fontSize = 11.sp,
                fontWeight = FontWeight440,
            )
            if (busy) OcBodyText(stringResource(R.string.oc_common_loading), muted = true)
        }
        if (row.disconnectable) {
            Spacer(Modifier.width(8.dp))
            OcButton(
                label = stringResource(R.string.common_disconnect),
                primary = false,
                enabled = canAct && !busy,
                onClick = onDisconnect,
            )
        } else {
            Spacer(Modifier.width(8.dp))
            OcBodyText(
                stringResource(R.string.settings_providers_connected_environmentDescription),
                muted = true,
            )
        }
    }
}

@Composable
private fun ProviderPopularRow(row: ProviderRow, canAct: Boolean, onConnect: () -> Unit) {
    val c = OcTheme.colors
    val noteKey = remember(row.id) { ProviderConnectCatalog.providerNoteKey(row.id) }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(row.name, color = c.textBase, fontSize = 13.sp, fontWeight = FontWeight530)
                if (ProviderConnectCatalog.isRecommended(row.id)) {
                    Spacer(Modifier.width(8.dp))
                    OcTag(stringResource(R.string.dialog_provider_tag_recommended), accent = true)
                }
            }
            if (noteKey != null) {
                val note = when (noteKey) {
                    "dialog.provider.opencode.note" -> stringResource(R.string.dialog_provider_opencode_note)
                    "dialog.provider.opencodeGo.tagline" -> stringResource(R.string.dialog_provider_opencodeGo_tagline)
                    "dialog.provider.anthropic.note" -> stringResource(R.string.dialog_provider_anthropic_note)
                    "dialog.provider.copilot.note" -> stringResource(R.string.dialog_provider_copilot_note)
                    "dialog.provider.openai.note" -> stringResource(R.string.dialog_provider_openai_note)
                    "dialog.provider.google.note" -> stringResource(R.string.dialog_provider_google_note)
                    "dialog.provider.openrouter.note" -> stringResource(R.string.dialog_provider_openrouter_note)
                    "dialog.provider.vercel.note" -> stringResource(R.string.dialog_provider_vercel_note)
                    else -> null
                }
                note?.let {
                    Text(color = c.textMuted, fontSize = 11.sp, fontWeight = FontWeight440, text = it)
                }
            } else if (row.id == "_custom") {
                Text(
                    text = stringResource(R.string.settings_providers_custom_description),
                    color = c.textMuted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight440,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        OcButton(
            label = stringResource(R.string.common_connect),
            primary = false,
            enabled = canAct,
            onClick = onConnect,
        )
    }
}

@Composable
private fun ProviderConnectSheet(
    providerID: String,
    busy: Boolean,
    canAct: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    var key by remember(providerID) { mutableStateOf("") }
    val valid = remember(key) { ProviderConnectForm.validateApiKey(key) != null }
    Column(
        Modifier.fillMaxWidth().padding(vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OcBodyText(stringResource(R.string.provider_connect_apiKey_label, providerID), muted = false)
        OcTextField(
            value = key,
            onValueChange = { key = it },
            placeholder = stringResource(R.string.provider_connect_apiKey_placeholder),
            password = true,
            imeAction = ImeAction.Done,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OcButton(
                label = stringResource(R.string.common_cancel),
                primary = false,
                enabled = !busy,
                onClick = onDismiss,
            )
            OcButton(
                label = stringResource(R.string.common_connect),
                enabled = canAct && !busy && valid,
                onClick = {
                    ProviderConnectForm.validateApiKey(key)?.let(onSubmit)
                },
            )
        }
    }
}

/**
 * `SettingsModelsV2` — every model of every **connected** provider
 * (`providers/models/models.tsx:24-31`), grouped by provider, with the effective
 * model tagged. The catalog is the only source of the context window the ring
 * needs (`limit.context`), so the row meta shows it.
 *
 * WAVE 2.3a (A4 #2): each row carries the visibility Switch — the tab's reason
 * to exist (`models.tsx:186-199`, `models.visible/setVisibility`). Groups sort
 * popular-first and collapse per provider (`ModelProvidersSchema.collapsed`);
 * both persist in [SettingsStore] and drive the composer ModelPicker filter
 * (consumer wiring note in `wave2.3a-notes.md` §6).
 */
@Composable
fun ModelsPanel(extra: SettingsPanelExtras, store: SettingsStore? = null) {
    val providers = extra.providers?.collectAsState()?.value
    val selected = extra.selectedModel?.collectAsState()?.value
    val visibility = store?.state?.collectAsState()?.value?.let {
        runCatching { store.modelVisibility() }.getOrDefault(emptyMap())
    } ?: emptyMap()
    val collapsed = store?.state?.collectAsState()?.value?.let {
        runCatching { store.modelsCollapsed() }.getOrDefault(emptyMap())
    } ?: emptyMap()
    val allRows = remember(providers, selected, visibility) { modelRows(providers, selected, visibility) }
    var query by remember { mutableStateOf("") }
    val rows = remember(allRows, query) { filterModelRows(allRows, query) }
    val groups = remember(rows, collapsed, query) {
        sortedModelGroups(rows, collapsed, searching = query.isNotBlank())
    }
    val current = allRows.firstOrNull { it.current }

    Column(Modifier.fillMaxSize()) {
        SettingsPanelHeader(
            titleResId = SettingsTab.MODELS.headerTitleResId(),
            below = if (providers == null) {
                null
            } else {
                {
                    OcBodyText(
                        stringResource(R.string.oc_settings_models_count, allRows.size),
                        muted = true,
                    )
                    Spacer(Modifier.height(2.dp))
                    OcBodyText(
                        text = current?.name
                            ?: stringResource(R.string.oc_settings_models_default),
                        muted = true,
                    )
                }
            },
        )
        SettingsPanelBody {
            when {
                extra.providers == null ->
                    OcBodyText(stringResource(R.string.oc_settings_providers_unavailable), muted = true)

                providers == null -> OcBodyText(stringResource(R.string.oc_common_loading), muted = true)
                allRows.isEmpty() -> OcBodyText(stringResource(R.string.oc_models_empty), muted = true)
                else -> {
                    OcTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = stringResource(R.string.oc_settings_models_search),
                        imeAction = ImeAction.Search,
                    )
                    if (rows.isEmpty()) {
                        OcBodyText(stringResource(R.string.oc_settings_search_empty), muted = true)
                    }
                    for (group in groups) {
                        ModelGroupBlock(
                            group = group,
                            canCollapse = query.isBlank() && store != null,
                            onToggleCollapse = {
                                store?.setModelsCollapsed(group.providerID, !group.collapsed)
                            },
                        ) {
                            SettingsList {
                                group.rows.forEachIndexed { index, row ->
                                    ModelVisibilityRow(
                                        row = row,
                                        canToggle = store != null,
                                        onToggle = { visible ->
                                            store?.setModelVisible(row.providerID, row.id, visible)
                                        },
                                    )
                                    if (index != group.rows.lastIndex) RowSeparator()
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelGroupBlock(
    group: ModelGroup,
    canCollapse: Boolean,
    onToggleCollapse: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable(enabled = canCollapse, onClick = onToggleCollapse),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = group.providerName,
                color = OcTheme.colors.textBase,
                fontSize = 15.sp,
                fontWeight = FontWeight(640),
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (group.collapsed) "+" else "−",
                color = OcTheme.colors.textMuted,
                fontSize = 13.sp,
            )
        }
        if (!group.collapsed) content()
    }
}

@Composable
private fun ModelVisibilityRow(row: ModelRow, canToggle: Boolean, onToggle: (Boolean) -> Unit) {
    val c = OcTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(row.name, color = c.textBase, fontSize = 13.sp, fontWeight = FontWeight530)
                if (row.current) {
                    Spacer(Modifier.width(8.dp))
                    OcTag(stringResource(R.string.oc_settings_models_current), accent = true)
                }
            }
            Text(row.ref, color = c.textMuted, fontSize = 11.sp, fontWeight = FontWeight440)
        }
        formatContextLimit(row.limitContext)?.let { limit ->
            Text(
                text = stringResource(R.string.oc_settings_model_context, limit),
                color = c.textFaint,
                fontSize = 11.sp,
                fontWeight = FontWeight530,
            )
            Spacer(Modifier.width(8.dp))
        }
        OcSwitch(checked = row.visible, enabled = canToggle, onCheckedChange = onToggle)
    }
}

/** The `0.5px` `border-bottom: border-base` between two settings rows. */
@Composable
private fun RowSeparator() {
    Box(Modifier.fillMaxWidth().height(0.5.dp).background(OcTheme.colors.borderBase))
}

private val FontWeight530 = androidx.compose.ui.text.font.FontWeight(530)
private val FontWeight440 = androidx.compose.ui.text.font.FontWeight(440)
