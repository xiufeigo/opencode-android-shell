package com.xiufeigo.opencodeshell.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.SettingsStore
import com.xiufeigo.opencodeshell.ui.theme.OcTheme

/**
 * `SettingsGeneralV2` (`settings-v2/general.tsx`) — render order is fixed by
 * `research/02` §5.2 and asserted by `SettingsPaletteModelTest`.
 *
 * Updates / Display (desktop-only) are intentionally dropped on Android; the
 * Android-specific rows (CORS notice, tab-plus behaviour) fold into the
 * untitled first block.
 */
@Composable
fun GeneralPanel(settings: SettingsStore.Settings, store: SettingsStore) {
    SettingsPanelBody {
        for (section in GENERAL_PANEL) {
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
}

/** One row's control, driven purely by [SettingsControl]. */
@Composable
private fun SettingsRowControl(
    row: SettingsRow,
    settings: SettingsStore.Settings,
    store: SettingsStore,
) {
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
 * Reads the current value of a `data-action` as a string — the single bridge
 * between the pure [SettingsRow] table and [SettingsStore.Settings].
 */
fun stringValue(settings: SettingsStore.Settings, action: String): String = when (action) {
    "settings-language" -> settings.language
    "settings-auto-accept-permissions" -> settings.autoAcceptPermissions.toString()
    "settings-shell" -> settings.shell
    "settings-feed-reasoning-summaries" -> settings.reasoningSummaries.toString()
    "settings-feed-shell-tool-parts-expanded" -> settings.shellToolPartsExpanded.toString()
    "settings-feed-edit-tool-parts-expanded" -> settings.editToolPartsExpanded.toString()
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
    else -> ""
}

/** `SettingsKeybinds` — read-only table grouped by [ShortcutGroup]. */
@Composable
fun ShortcutsPanel() {
    SettingsPanelBody {
        for ((group, rows) in shortcutsByGroup()) {
            SettingsSectionBlock(group.titleResId()) {
                SettingsList {
                    rows.forEachIndexed { index, row ->
                        SettingsRow(
                            titleResId = row.titleResId,
                            descriptionResId = null,
                            last = index == rows.lastIndex,
                        ) {
                            // M4 shows the binding verbatim; the record/replay
                            // interaction (§5.6) is a later-milestone placeholder.
                            KeybindChip(row.keybind)
                        }
                    }
                }
            }
        }
    }
}

/** `SettingsProvidersV2` — read-only list assembled from `provider.list`. */
@Composable
fun ProvidersPanel(extra: SettingsPanelExtras) {
    SettingsPanelBody {
        when {
            extra.loading -> OcBodyText(stringResource(R.string.oc_common_loading), muted = true)
            extra.providers.isEmpty() -> OcBodyText(stringResource(R.string.oc_providers_empty), muted = true)
            else -> {
                SettingsSectionBlock(R.string.oc_providers_section_connected) {
                    SettingsList {
                        val c = OcTheme.colors
                        extra.providers.forEachIndexed { index, (id, name) ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(
                                        top = 20.dp,
                                        bottom = if (index == extra.providers.lastIndex) 20.dp else 0.dp,
                                    ),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(name, color = c.textBase, fontSize = 13.sp, fontWeight = FontWeight530)
                                    Text(id, color = c.textMuted, fontSize = 13.sp, fontWeight = FontWeight440)
                                }
                            }
                            if (index != extra.providers.lastIndex) RowSeparator()
                        }
                    }
                }
            }
        }
    }
}

/** `SettingsModelsV2` — read-only list assembled from `model.list`. */
@Composable
fun ModelsPanel(extra: SettingsPanelExtras) {
    SettingsPanelBody {
        when {
            extra.loading -> OcBodyText(stringResource(R.string.oc_common_loading), muted = true)
            extra.models.isEmpty() -> OcBodyText(stringResource(R.string.oc_models_empty), muted = true)
            else -> {
                SettingsList {
                    val c = OcTheme.colors
                    extra.models.forEachIndexed { index, (id, name) ->
                        Column(
                            Modifier.fillMaxWidth().padding(vertical = 20.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(name, color = c.textBase, fontSize = 13.sp, fontWeight = FontWeight530)
                            Text(id, color = c.textMuted, fontSize = 11.sp, fontWeight = FontWeight440)
                        }
                        if (index != extra.models.lastIndex) RowSeparator()
                    }
                }
            }
        }
    }
}

/** The `0.5px` `border-bottom: border-base` between two settings rows. */
@Composable
private fun RowSeparator() {
    Box(Modifier.fillMaxWidth().height(0.5.dp).background(OcTheme.colors.borderBase))
}

private val FontWeight530 = androidx.compose.ui.text.font.FontWeight(530)
private val FontWeight440 = androidx.compose.ui.text.font.FontWeight(440)
