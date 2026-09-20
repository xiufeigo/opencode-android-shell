package com.xiufeigo.opencodeshell.ui.palette

import androidx.compose.runtime.Composable
import com.xiufeigo.opencodeshell.R

/**
 * The command registry M4 ships.
 *
 * Upstream registers per-route (`home.palette` / `session-palette` /
 * `new-session`, `research/05` §1.4) and the *last* registration for an id wins.
 * M4 collapses that into one app-level list because the native shell has a single
 * dialog stack and no per-route command ownership yet; the ids, keybinds and
 * `COMMON_COMMAND_IDS` membership all match upstream, so splitting it later per
 * route is mechanical.
 *
 * Only `settings.open` has a real action in M4 (it pushes the Settings dialog).
 * M5 wires `terminal.toggle` to the terminal panel route; the remaining entries
 * exist so the palette's empty query shows the real `COMMON_COMMAND_IDS` rows,
 * and their actions land with the milestones that own those screens.
 */
@Composable
fun appCommands(
    onOpenSettings: () -> Unit,
    /**
     * `terminal.toggle` (`Ctrl+`` `, `research/02` §6/§9.3). M5 phase 1 opens the
     * terminal panel route; a null action keeps the row visible but inert, which
     * is what hosts without a terminal panel (e.g. previews) want.
     */
    onOpenTerminal: (() -> Unit)? = null,
): List<PaletteCommand> = listOf(
    PaletteCommand(
        id = "settings.open",
        titleResId = R.string.oc_command_settings_open,
        keybind = "Ctrl+,",
        onSelect = onOpenSettings,
    ),
    PaletteCommand(
        id = "session.new",
        titleResId = R.string.oc_command_session_new,
        keybind = "Ctrl+N",
        onSelect = {},
    ),
    PaletteCommand(
        id = "workspace.new",
        titleResId = R.string.oc_command_workspace_new,
        onSelect = {},
    ),
    PaletteCommand(
        id = "session.previous",
        titleResId = R.string.oc_command_session_previous,
        onSelect = {},
    ),
    PaletteCommand(
        id = "session.next",
        titleResId = R.string.oc_command_session_next,
        onSelect = {},
    ),
    PaletteCommand(
        id = "terminal.toggle",
        titleResId = R.string.oc_command_terminal_toggle,
        keybind = "Ctrl+`",
        onSelect = { onOpenTerminal?.invoke() },
    ),
    PaletteCommand(
        id = "review.toggle",
        titleResId = R.string.oc_command_review_toggle,
        onSelect = {},
    ),
)
