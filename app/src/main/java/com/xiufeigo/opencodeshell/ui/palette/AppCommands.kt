package com.xiufeigo.opencodeshell.ui.palette

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
 * **W4-D:** every row is now actionable. The host ([ui/App.kt]) passes the real
 * side effects in; a null callback keeps the row out of the list entirely rather
 * than leaving an inert row behind (an inert row is exactly the "点了没反应"
 * defect the audit recorded for these five commands).
 *
 * Keybinds follow upstream, not the old placeholder strings
 * (`use-session-commands.tsx:248-463`): `session.new` is `mod+shift+s` (the old
 * `Ctrl+N` actually belongs to the hidden `tab.new`), `review.toggle` is
 * `mod+shift+r`, `file.open` is `mod+p`, `fileTree.toggle` is `mod+\`.
 *
 * Deliberately **not** `@Composable`: it resolves resource *ids* only, so the
 * registry is plain JVM data that a unit test can assert row by row.
 */
fun appCommands(
    onOpenSettings: () -> Unit,
    /**
     * `terminal.toggle` (`Ctrl+`` `, `research/02` §6/§9.3) — a real toggle: the
     * host pops the terminal route when it is already on screen. A null action
     * keeps the row visible but inert, which is what hosts without a terminal
     * panel (e.g. previews) want.
     */
    onOpenTerminal: (() -> Unit)? = null,
    /** `session.new` + the hidden `tab.new` (`titlebar.tsx:388-396`). */
    onNewSession: (() -> Unit)? = null,
    /** `workspace.new` ("New worktree") — the host picks a directory, then drafts. */
    onNewWorkspace: (() -> Unit)? = null,
    /** `session.previous` / `session.next` (`desktop-menu.ts:245-266`). */
    onPreviousSession: (() -> Unit)? = null,
    onNextSession: (() -> Unit)? = null,
    /** `review.toggle` (`use-session-commands.tsx:369-374`) — session panel. */
    onToggleReview: (() -> Unit)? = null,
    /** `file.open` (`:314-321`) — the palette re-opens as a file picker. */
    onOpenFilePicker: (() -> Unit)? = null,
    /** `fileTree.toggle` (`:375-384`) — review panel's file tree. */
    onToggleFileTree: (() -> Unit)? = null,
    /**
     * `session.summary.toggle` (`session/summary/popover.tsx:17-34`) — A1's
     * session summary. The panel's open/closed state is **screen-local** to
     * `SessionScreen` (nothing else owns it), so the host hands in a bump of the
     * revision the screen keys on — the same one-way channel `review.toggle`
     * uses. Null (previews) keeps the row out of the list.
     */
    onToggleSummary: (() -> Unit)? = null,
    /**
     * `visibility.fileTree` (`settings/model.tsx:243,386-390`): upstream only
     * *registers* `fileTree.toggle` when the setting is on (`:375`), so the row
     * disappears from the palette with it. That is the consumer
     * `SettingsStore.showFileTree` drives.
     */
    showFileTree: Boolean = true,
    /**
     * B-17: `debugBar.toggle` (`shell/shell.tsx:49-58`) — flips the debug bar.
     * Upstream declares **no keybind** for it, so the row carries none either
     * and a recorded override is what gives the command a chord. Null (previews,
     * the slash registry) keeps the row out, the same rule every optional row
     * follows.
     */
    onToggleDebugBar: (() -> Unit)? = null,
): List<PaletteCommand> = listOfNotNull(
    PaletteCommand(
        id = "settings.open",
        titleResId = R.string.oc_command_settings_open,
        keybind = "Ctrl+,",
        onSelect = onOpenSettings,
    ),
    onNewSession?.let {
        PaletteCommand(
            id = "session.new",
            titleResId = R.string.oc_command_session_new,
            keybind = "Ctrl+Shift+S",
            onSelect = it,
        )
    },
    // Upstream `tab.new` is hidden (`titlebar.tsx:388-396`) and carries the real
    // `Ctrl+N`; the palette's empty query still prefers `session.new` above.
    onNewSession?.let {
        PaletteCommand(
            id = "tab.new",
            titleResId = R.string.oc_command_session_new,
            hidden = true,
            keybind = "Ctrl+N",
            onSelect = it,
        )
    },
    onNewWorkspace?.let {
        PaletteCommand(
            id = "workspace.new",
            titleResId = R.string.oc_command_workspace_new,
            onSelect = it,
        )
    },
    onPreviousSession?.let {
        PaletteCommand(
            id = "session.previous",
            titleResId = R.string.oc_command_session_previous,
            onSelect = it,
        )
    },
    onNextSession?.let {
        PaletteCommand(
            id = "session.next",
            titleResId = R.string.oc_command_session_next,
            onSelect = it,
        )
    },
    PaletteCommand(
        id = "terminal.toggle",
        titleResId = R.string.oc_command_terminal_toggle,
        keybind = "Ctrl+`",
        onSelect = { onOpenTerminal?.invoke() },
    ),
    onToggleReview?.let {
        PaletteCommand(
            id = "review.toggle",
            titleResId = R.string.oc_command_review_toggle,
            keybind = "Ctrl+Shift+R",
            onSelect = it,
        )
    },
    onOpenFilePicker?.let {
        PaletteCommand(
            id = "file.open",
            titleResId = R.string.oc_command_file_open,
            keybind = "Ctrl+P",
            onSelect = it,
        )
    },
    if (showFileTree && onToggleFileTree != null) {
        PaletteCommand(
            id = "fileTree.toggle",
            titleResId = R.string.oc_command_file_tree_toggle,
            keybind = "Ctrl+\\",
            onSelect = onToggleFileTree,
        )
    } else {
        null
    },
    // A1 (`session.summary.toggle`, `mod+shift+y` upstream — `popover.tsx:17-34`).
    // Same id/keybind the header's summary button and `ui/App.kt`'s
    // `GlobalChord.SUMMARY` carry, so all three entries run one action.
    onToggleSummary?.let {
        PaletteCommand(
            id = "session.summary.toggle",
            titleResId = R.string.oc_command_summary_toggle,
            keybind = "Ctrl+Shift+Y",
            onSelect = it,
        )
    },
    // B-17 (`shell/shell.tsx:49-58`): the debug bar. `command.category.view`,
    // like upstream, and no keybind — the command upstream registers carries
    // none. The row and the chord run the same App-level flip
    // (`ui/App.kt`'s `GlobalChord.DEBUG_BAR`), so the settings row that shows
    // `debugBar.toggle` can never advertise a key the palette disagrees with.
    onToggleDebugBar?.let {
        PaletteCommand(
            id = "debugBar.toggle",
            titleResId = R.string.command_debugBar_toggle,
            category = CATEGORY_VIEW,
            onSelect = it,
        )
    },
)
