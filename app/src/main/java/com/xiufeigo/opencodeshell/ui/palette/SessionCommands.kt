package com.xiufeigo.opencodeshell.ui.palette

import android.util.Log
import com.xiufeigo.opencodeshell.R

/**
 * WAVE 2.3a — the ~20 missing session commands (A4 #3).
 *
 * Upstream (`use-session-commands.tsx:250-463`, `composer/commands.tsx:51-75`,
 * `new-session/commands.tsx:24-48`) registers a per-route command set; the
 * native [appCommands] registry carried only the 11 app-level rows. This file
 * adds the session route's rows — lifecycle (P0) first, the rest (P1) after —
 * with the same ids, categories and keybinds.
 *
 * Wiring rule: a row whose underlying action exists in [SessionCommandActions]
 * calls it; a row whose action does not exist yet registers with a stub that
 * logs (`Log.i`, tag `SessionCommands`) and reports through [StubReporter] so
 * the host can surface a toast. Stubs are listed in
 * `research/v2_audit/wave2.3a-notes.md` §4.
 *
 * B-18/B-19 extend the same table with the two registrations this shell folded
 * into the session route: upstream's `session.browser` rows (`browser.open`,
 * `browser.reload` — `session/browser/model.ts:55-64`, `pane.tsx:45-50`) and the
 * `composer-editor` rows (`file.attach`, `prompt.mode.shell`,
 * `prompt.mode.normal` — `composer/model.ts:411-434`). Each registers
 * unconditionally with a stub fallback, like `session.compact`.
 */
object SessionCommands {

    const val LOG_TAG = "SessionCommands"

    /**
     * Actions the host (`ui/App.kt`) supplies. Nullable individually: a null
     * action keeps the row out of the registry rather than leaving an inert
     * row (the same rule [appCommands] uses).
     */
    data class SessionCommandActions(
        /** `session.undo` — `ChatViewModel.undoSession`. */
        val onUndo: (() -> Unit)? = null,
        /** `session.redo` — `ChatViewModel.redoSession`. */
        val onRedo: (() -> Unit)? = null,
        /** `session.compact` — `ChatViewModel.compactSession` (`POST …/compact`). */
        val onCompact: (() -> Unit)? = null,
        /** `session.fork` — fork dialog (reads `ChatViewModel.forkMessages`). */
        val onFork: (() -> Unit)? = null,
        /** `session.export` — transcript export: stub. */
        val onExport: (() -> Unit)? = null,
        /** `session.copyID` — copies the tracked session id: stub/handled by host. */
        val onCopySessionID: (() -> Unit)? = null,
        /** `session.background` — `ctrl+b`, move a blocking session to background. */
        val onBackground: (() -> Unit)? = null,
        /** `session.archive` — `ChatViewModel.archiveSession`. */
        val onArchive: (() -> Unit)? = null,
        /** `session.import` — session import: stub. */
        val onImport: (() -> Unit)? = null,
        /**
         * `session.aside` — the `/btw` side question, registered by upstream's
         * btw plugin with `palette: true` (`_upstream` …/prompt/btw.tsx
         * `command.register("session.aside", …)`). The argument is the words
         * typed after the trigger, or `null` for the palette row's own form:
         * upstream's `run(input)` has no input there and falls back to its
         * question dialog (`btw.tsx:64-66`), which on this shell is the btw
         * panel's own field — so `null` opens the panel.
         *
         * The ask runs through the **same** btw host the composer's `/btw`
         * route uses (`ui/session/btw` `SessionBtwHost.ask`/`openPanel`,
         * `POST /api/session/{id}/generate`), so it never appends a turn to the
         * conversation.
         */
        val onAskAside: ((String?) -> Unit)? = null,
        /** `message.previous` / `message.next` — timeline navigation. */
        val onPreviousMessage: (() -> Unit)? = null,
        val onNextMessage: (() -> Unit)? = null,
        /** `mcp.toggle` — MCP chooser dialog (`ui.overlay.McpChooserDialog`, host-wired). */
        val onToggleMcp: (() -> Unit)? = null,
        /** `permissions.autoaccept` — flips `SettingsStore.autoAcceptPermissions`. */
        val onToggleAutoAccept: (() -> Unit)? = null,
        /** `input.focus` — focuses the composer input. */
        val onFocusInput: (() -> Unit)? = null,
        /** `terminal.new` — creates a terminal tab. */
        val onNewTerminal: (() -> Unit)? = null,
        /** `terminal.close` — hidden (`mod+w` inside the terminal): stub. */
        val onCloseTerminal: (() -> Unit)? = null,
        /** `file.close` — closes the active file tab: stub. */
        val onCloseFile: (() -> Unit)? = null,
        /** `context.addSelection` — adds the editor selection to context. */
        val onAddSelection: (() -> Unit)? = null,
        /** `project.copyID` — copies the project id: stub/handled by host. */
        val onCopyProjectID: (() -> Unit)? = null,
        /** `project.select` — project picker (`session.new.project.search`). */
        val onSelectProject: (() -> Unit)? = null,
        /** `session.location.cycle` — cycles the session location. */
        val onCycleLocation: (() -> Unit)? = null,
        /** `model.choose` — model picker. */
        val onChooseModel: (() -> Unit)? = null,
        /** `model.variant.cycle` — reasoning-effort cycling. */
        val onCycleVariant: (() -> Unit)? = null,
        /** `agent.cycle` / `agent.cycle.reverse` — agent cycling. */
        val onCycleAgent: (() -> Unit)? = null,
        val onCycleAgentReverse: (() -> Unit)? = null,
        /**
         * B-19 `file.attach` (`composer/model.ts:411-418`, `mod+u`): the
         * composer's attachment picker — the same SAF launcher the 「+」 menu's
         * 附件 row runs (`SessionScreen` `attachmentPicker::pick`).
         */
        val onAttachFile: (() -> Unit)? = null,
        /**
         * B-19 `prompt.mode.shell` (`composer/model.ts:419-426`,
         * `mod+shift+x`): the composer's shell mode — upstream's
         * `dispatch({type:"mode.shell"})` (`suggestions/machine.ts:75`), i.e.
         * mode on + popover closed, the editor text kept.
         */
        val onShellMode: (() -> Unit)? = null,
        /**
         * B-19 `prompt.mode.normal` (`composer/model.ts:427-434`,
         * `mod+shift+e`): back to the prompt mode (`machine.ts:76`).
         */
        val onNormalMode: (() -> Unit)? = null,
        /**
         * B-18 `browser.open` (`session/browser/model.ts:55-64`, `mod+shift+b`):
         * the embedded browser pane. Upstream's browser is a side-panel tab
         * (`tabs.open`); the native pane is a full-body overlay opened on a URL
         * (`SessionBrowserPane`), so the row shows the pane at the last URL it
         * displayed — or at its empty state when there is none.
         */
        val onOpenBrowser: (() -> Unit)? = null,
        /**
         * B-18 `browser.reload` (`session/browser/pane.tsx:45-50`, `f5`):
         * forwards to the pane's own reload control. A no-op while the pane is
         * closed — upstream disables the row the same way (`!props.visible`).
         */
        val onReloadBrowser: (() -> Unit)? = null,
    )

    /**
     * Where a stubbed command reports itself. The palette host passes a
     * toast-showing implementation; unit tests pass a recording one.
     */
    fun interface StubReporter {
        fun report(commandId: String)
    }

    private fun stub(id: String, reporter: StubReporter?): () -> Unit = {
        Log.i(LOG_TAG, "stub command selected: $id (no native action yet)")
        reporter?.report(id)
    }

    /**
     * The session route's command rows. Every row carries the upstream
     * category + keybind (`use-session-commands.tsx`, `composer/commands.tsx`,
     * `new-session/commands.tsx`); `terminal.close` is hidden like upstream.
     *
     * Resource ids are the v2 generated keys (`strings_oc_i18n.xml`); the
     * three session-lifecycle rows without a description upstream
     * (`session.background`, `session.copyID`, `project.copyID`) reuse the
     * title only.
     *
     * [enabled] is the route that serves them: the shell registers **one**
     * command table for every palette host (`AppCommands`' KDoc — one dialog
     * stack, no per-route ownership), so Home lists these rows too, but it has no
     * session screen to run them. With `enabled = false` every row keeps its id,
     * title and keybind and loses only its action — exactly the state its chord
     * has off-route (`ui/App.kt`'s `onChordCommand` gates every session id on
     * `currentRoute == Route.SESSION`: upstream disables a session command
     * without a session id, `use-session-commands.tsx` `disabled:` clauses).
     * Dropping the rows instead is what made the Home palette a subset of the
     * registry, so `export` / `import` / `share` read as missing
     * (`research/v37_verify/tB/40_palette_export_none.png`).
     */
    fun sessionCommands(
        actions: SessionCommandActions = SessionCommandActions(),
        stubReporter: StubReporter? = null,
        enabled: Boolean = true,
    ): List<PaletteCommand> = listOfNotNull(
        actions.onUndo?.let {
            PaletteCommand(
                id = "session.undo",
                titleResId = R.string.command_session_undo,
                descriptionResId = R.string.command_session_undo_description,
                category = CATEGORY_SESSION,
                onSelect = it,
            )
        },
        actions.onRedo?.let {
            PaletteCommand(
                id = "session.redo",
                titleResId = R.string.command_session_redo,
                descriptionResId = R.string.command_session_redo_description,
                category = CATEGORY_SESSION,
                onSelect = it,
            )
        },
        PaletteCommand(
            id = "session.compact",
            titleResId = R.string.command_session_compact,
            descriptionResId = R.string.command_session_compact_description,
            category = CATEGORY_SESSION,
            onSelect = actions.onCompact ?: stub("session.compact", stubReporter),
        ),
        PaletteCommand(
            id = "session.background",
            titleResId = R.string.command_session_background,
            category = CATEGORY_SESSION,
            keybind = "Ctrl+B",
            onSelect = actions.onBackground ?: stub("session.background", stubReporter),
        ),
        actions.onFork?.let {
            PaletteCommand(
                id = "session.fork",
                titleResId = R.string.command_session_fork,
                descriptionResId = R.string.command_session_fork_description,
                category = CATEGORY_SESSION,
                onSelect = it,
            )
        },
        PaletteCommand(
            id = "session.export",
            titleResId = R.string.command_session_export,
            descriptionResId = R.string.command_session_export_description,
            category = CATEGORY_SESSION,
            onSelect = actions.onExport ?: stub("session.export", stubReporter),
        ),
        PaletteCommand(
            id = "session.copyID",
            titleResId = R.string.command_session_copyID,
            category = CATEGORY_SESSION,
            onSelect = actions.onCopySessionID ?: stub("session.copyID", stubReporter),
        ),
        actions.onArchive?.let {
            PaletteCommand(
                id = "session.archive",
                titleResId = R.string.command_session_archive,
                category = CATEGORY_SESSION,
                onSelect = it,
            )
        },
        PaletteCommand(
            id = "session.import",
            titleResId = R.string.command_session_import,
            category = CATEGORY_SESSION,
            onSelect = actions.onImport ?: stub("session.import", stubReporter),
        ),
        // `session.aside` — the `/btw` side question, the one session-route row
        // that comes from upstream's btw plugin instead of
        // `use-session-commands.tsx` (`btw.tsx`: `palette: true`,
        // `slash: {name: "btw", arguments: true}`). Selecting the palette row
        // passes no input (`null`): upstream opens its question dialog, here the
        // btw panel's own field is that dialog.
        //
        // Deliberately **not** in [SLASH_COMMAND_IDS]: the composer already owns
        // `/btw` as a built-in row (`ComposerSuggestions.BTW_TRIGGER`) routed to
        // the same host, and a second alias for one trigger would only duplicate
        // it (the composer's alias rows are built from that table). Its
        // description carries the trigger so a `btw` query finds the row —
        // `matchesEntry` reads the localized title/description/category plus
        // the row's id words and English copy.
        actions.onAskAside?.let { ask ->
            PaletteCommand(
                id = ASIDE_COMMAND_ID,
                titleResId = R.string.oc_btw_ask,
                descriptionResId = R.string.oc_btw_palette_description,
                category = CATEGORY_SESSION,
                onSelect = { ask(null) },
            )
        },
        actions.onPreviousMessage?.let {
            PaletteCommand(
                id = "message.previous",
                titleResId = R.string.command_message_previous,
                descriptionResId = R.string.command_message_previous_description,
                category = CATEGORY_SESSION,
                keybind = "Ctrl+Alt+[",
                onSelect = it,
            )
        },
        actions.onNextMessage?.let {
            PaletteCommand(
                id = "message.next",
                titleResId = R.string.command_message_next,
                descriptionResId = R.string.command_message_next_description,
                category = CATEGORY_SESSION,
                keybind = "Ctrl+Alt+]",
                onSelect = it,
            )
        },
        PaletteCommand(
            id = "mcp.toggle",
            titleResId = R.string.command_mcp_toggle,
            descriptionResId = R.string.command_mcp_toggle_description,
            category = CATEGORY_MCP,
            keybind = "Ctrl+;",
            onSelect = actions.onToggleMcp ?: stub("mcp.toggle", stubReporter),
        ),
        actions.onToggleAutoAccept?.let {
            PaletteCommand(
                id = "permissions.autoaccept",
                titleResId = R.string.command_permissions_autoaccept_enable,
                category = CATEGORY_PERMISSIONS,
                keybind = "Ctrl+Shift+A",
                onSelect = it,
            )
        },
        actions.onFocusInput?.let {
            PaletteCommand(
                id = "input.focus",
                titleResId = R.string.command_input_focus,
                category = CATEGORY_VIEW,
                keybind = "Ctrl+L",
                onSelect = it,
            )
        },
        actions.onNewTerminal?.let {
            PaletteCommand(
                id = "terminal.new",
                titleResId = R.string.command_terminal_new,
                descriptionResId = R.string.command_terminal_new_description,
                category = CATEGORY_TERMINAL,
                keybind = "Ctrl+Alt+T",
                onSelect = it,
            )
        },
        PaletteCommand(
            id = "terminal.close",
            titleResId = R.string.terminal_close,
            category = CATEGORY_TERMINAL,
            keybind = "Ctrl+W",
            hidden = true,
            onSelect = actions.onCloseTerminal ?: stub("terminal.close", stubReporter),
        ),
        PaletteCommand(
            id = "file.close",
            titleResId = R.string.command_tab_close,
            category = CATEGORY_FILE,
            onSelect = actions.onCloseFile ?: stub("file.close", stubReporter),
        ),
        PaletteCommand(
            id = "context.addSelection",
            titleResId = R.string.command_context_addSelection,
            descriptionResId = R.string.command_context_addSelection_description,
            category = CATEGORY_CONTEXT,
            keybind = "Ctrl+Shift+L",
            onSelect = actions.onAddSelection ?: stub("context.addSelection", stubReporter),
        ),
        PaletteCommand(
            id = "project.copyID",
            titleResId = R.string.command_project_copyID,
            category = CATEGORY_PROJECT,
            onSelect = actions.onCopyProjectID ?: stub("project.copyID", stubReporter),
        ),
        actions.onSelectProject?.let {
            PaletteCommand(
                id = "project.select",
                titleResId = R.string.session_new_project_search,
                category = CATEGORY_PROJECT,
                keybind = "Ctrl+Shift+O",
                onSelect = it,
            )
        },
        actions.onCycleLocation?.let {
            PaletteCommand(
                id = "session.location.cycle",
                titleResId = R.string.command_session_location_cycle,
                category = CATEGORY_WORKSPACE,
                keybind = "Ctrl+Alt+L",
                onSelect = it,
            )
        },
        actions.onChooseModel?.let {
            PaletteCommand(
                id = "model.choose",
                titleResId = R.string.command_model_choose,
                descriptionResId = R.string.command_model_choose_description,
                category = CATEGORY_MODEL,
                keybind = "Ctrl+'",
                onSelect = it,
            )
        },
        actions.onCycleVariant?.let {
            PaletteCommand(
                id = "model.variant.cycle",
                titleResId = R.string.command_model_variant_cycle,
                descriptionResId = R.string.command_model_variant_cycle_description,
                category = CATEGORY_MODEL,
                keybind = "Shift+Ctrl+D",
                onSelect = it,
            )
        },
        actions.onCycleAgent?.let {
            PaletteCommand(
                id = "agent.cycle",
                titleResId = R.string.command_agent_cycle,
                descriptionResId = R.string.command_agent_cycle_description,
                category = CATEGORY_MODEL,
                keybind = "Ctrl+.",
                onSelect = it,
            )
        },
        actions.onCycleAgentReverse?.let {
            PaletteCommand(
                id = "agent.cycle.reverse",
                titleResId = R.string.command_agent_cycle_reverse,
                descriptionResId = R.string.command_agent_cycle_reverse_description,
                category = CATEGORY_MODEL,
                keybind = "Shift+Ctrl+.",
                onSelect = it,
            )
        },
        // B-18: upstream's `session.browser` registration
        // (`session/browser/model.ts:55-64`) — `mod+shift+b`, category view.
        // The native browser is a full-body overlay over one agent-driven URL
        // rather than upstream's side-panel tab, so `browser.open` shows the
        // pane at the last URL (the screen keeps it) and `browser.reload`
        // forwards to the pane's own reload; both stay registered with a stub
        // when the host has no session screen to serve them.
        PaletteCommand(
            id = "browser.open",
            titleResId = R.string.command_browser_open,
            category = CATEGORY_VIEW,
            keybind = "Ctrl+Shift+B",
            onSelect = actions.onOpenBrowser ?: stub("browser.open", stubReporter),
        ),
        PaletteCommand(
            id = "browser.reload",
            titleResId = R.string.command_browser_reload,
            category = CATEGORY_VIEW,
            keybind = "F5",
            onSelect = actions.onReloadBrowser ?: stub("browser.reload", stubReporter),
        ),
        // B-19: upstream's `composer-editor` registration
        // (`composer/model.ts:411-434`) — attach (`mod+u`), shell mode
        // (`mod+shift+x`), prompt mode (`mod+shift+e`). All three drive state
        // that lives inside the composer (`SessionScreen` forwards them through
        // the same `commandRequest` channel the chords use).
        PaletteCommand(
            id = "file.attach",
            titleResId = R.string.prompt_action_attachFile,
            category = CATEGORY_FILE,
            keybind = "Ctrl+U",
            onSelect = actions.onAttachFile ?: stub("file.attach", stubReporter),
        ),
        PaletteCommand(
            id = "prompt.mode.shell",
            titleResId = R.string.command_prompt_mode_shell,
            category = CATEGORY_SESSION,
            keybind = "Ctrl+Shift+X",
            onSelect = actions.onShellMode ?: stub("prompt.mode.shell", stubReporter),
        ),
        PaletteCommand(
            id = "prompt.mode.normal",
            titleResId = R.string.command_prompt_mode_normal,
            category = CATEGORY_SESSION,
            keybind = "Ctrl+Shift+E",
            onSelect = actions.onNormalMode ?: stub("prompt.mode.normal", stubReporter),
        ),
    ).let { rows -> if (enabled) rows else rows.map { it.withSelect {} } }

    /**
     * `session.aside` — upstream's `/btw` side question (`btw.tsx`
     * `command.register("session.aside", …)`). Listed in [SESSION_COMMAND_IDS]
     * like every other session-route row, which is what puts it in the settings
     * shortcuts screen's coverage set (`SHORTCUT_ROWS` carries the matching
     * `session.aside` row with no keybind — upstream binds none; the trigger is
     * the `/btw` slash form). The palette row itself is registered once the host
     * provides `onAskAside` (a null action keeps it out, the same rule
     * `session.undo` follows).
     */
    const val ASIDE_COMMAND_ID: String = "session.aside"

    /** Ids [sessionCommands] registers (test/QA anchor, incl. the hidden row). */
    val SESSION_COMMAND_IDS: List<String> = listOf(
        "session.undo",
        "session.redo",
        "session.compact",
        "session.background",
        "session.fork",
        "session.export",
        "session.copyID",
        "session.archive",
        "session.import",
        ASIDE_COMMAND_ID,
        "message.previous",
        "message.next",
        "mcp.toggle",
        "permissions.autoaccept",
        "input.focus",
        "terminal.new",
        "terminal.close",
        "file.close",
        "context.addSelection",
        "project.copyID",
        "project.select",
        "session.location.cycle",
        "model.choose",
        "model.variant.cycle",
        "agent.cycle",
        "agent.cycle.reverse",
        "browser.open",
        "browser.reload",
        "file.attach",
        "prompt.mode.shell",
        "prompt.mode.normal",
    )

    /**
     * F-1: upstream slash aliases — the single source of truth for `/trigger` →
     * palette command id.
     *
     * Upstream (`composer/model.ts:230-247`) builds the `/` menu's slash half from
     * **every** registered command carrying a `slash` field: the composer
     * built-ins (`model`, `agent` — `composer/commands.tsx:49-82`) plus the
     * session route's rows (`use-session-commands.tsx:248-463`: `new`, `undo`,
     * `redo`, `compact`, `fork`, `export`, `open` on `file.open`, `terminal` on
     * `terminal.toggle`, `mcp`). The composer (`ui/session/composer`) consumes
     * this table instead of keeping a second copy.
     *
     * `model`/`agent` stay composer built-ins (they open the chip menus, not the
     * palette); [SESSION_SLASH_COMMAND_IDS] is the subset the composer routes to
     * [PaletteCommand.onSelect].
     */
    val SLASH_COMMAND_IDS: Map<String, String> = mapOf(
        "model" to "model.choose",
        "agent" to "agent.cycle",
        "new" to "session.new",
        "undo" to "session.undo",
        "redo" to "session.redo",
        "compact" to "session.compact",
        "fork" to "session.fork",
        "export" to "session.export",
        "open" to "file.open",
        "terminal" to "terminal.toggle",
        "mcp" to "mcp.toggle",
    )

    /**
     * F-1: the aliases the composer executes through the palette path (same
     * [PaletteCommand.onSelect] the palette row invokes). `session.new`,
     * `file.open` and `terminal.toggle` live in the app registry
     * (`AppCommands`), the rest in [sessionCommands] above.
     */
    val SESSION_SLASH_COMMAND_IDS: Map<String, String> =
        SLASH_COMMAND_IDS - setOf("model", "agent")

    /** The slash trigger a palette command id is reachable as, or null. */
    fun slashForCommand(commandId: String): String? =
        SLASH_COMMAND_IDS.entries.firstOrNull { it.value == commandId }?.key

    /** The palette command id a slash trigger runs, or null when unknown. */
    fun commandIdForSlash(trigger: String): String? = SLASH_COMMAND_IDS[trigger]

    /**
     * P1-C: the composer's `/` menu resolves slash aliases against the same
     * registry the app command palette lists — the app rows ([appCommands]:
     * `session.new`, `file.open`, `terminal.toggle`) plus the full session rows
     * ([sessionCommands]). First registration wins on an id clash, mirroring
     * upstream's per-route collapsing (`research/05` §1.4, last wins there —
     * the two tables never share an id, so the order is only a tie-break).
     * Hidden rows stay listed here (the composer itself skips `hidden`
     * registrations when building alias rows); filtering to slash-relevant ids
     * is the caller's job ([slashRegistry]).
     */
    fun slashRegistry(
        app: List<PaletteCommand>,
        session: List<PaletteCommand>,
    ): List<PaletteCommand> = (app + session).distinctBy { it.id }

    /**
     * P1-C: the slash-relevant slice of a combined registry — the rows a
     * `/trigger` can run ([SLASH_COMMAND_IDS] values). `model.choose` /
     * `agent.cycle` never appear: the composer serves `/model` / `/agent`
     * through its built-in chip menus, not the palette path.
     */
    fun slashRegistryCommands(commands: List<PaletteCommand>): List<PaletteCommand> =
        commands.filter { SLASH_COMMAND_IDS.values.contains(it.id) }
}

/** `command.category.*` groups the session registry uses. */
const val CATEGORY_MCP: String = "MCP"
const val CATEGORY_PERMISSIONS: String = "Permissions"
const val CATEGORY_VIEW: String = "View"
const val CATEGORY_TERMINAL: String = "Terminal"
const val CATEGORY_FILE: String = "File"
const val CATEGORY_CONTEXT: String = "Context"
const val CATEGORY_PROJECT: String = "Project"
/** Upstream `command.category.workspace` reads "Worktree" (v2 renamed). */
const val CATEGORY_WORKSPACE: String = "Worktree"
const val CATEGORY_MODEL: String = "Model"

/**
 * `looksLikeSessionID` (`session/search.ts:1-3`): `ses_`-prefixed ids longer
 * than 20 chars. The palette uses it to decide whether the query is worth an
 * exact `GET /session/:id` alongside the list search (`palette.ts:264`).
 */
fun looksLikeSessionID(value: String): Boolean =
    value.length > 20 && value.startsWith("ses_")

/**
 * Session rows for an async palette search (`createServerSessionEntries`,
 * `palette.ts:226-289`): exact-ID hit first (deduped by id), archived rows
 * dropped (`!session.time.archived` — the native predicate is
 * `archivedAt > 0`, see `HomeSessions.isArchived`), capped at
 * [SESSION_SEARCH_LIMIT] (v2 `limit: 50`, defined in `PaletteModel.kt`).
 */

fun sessionSearchEntries(
    listed: List<com.xiufeigo.opencodeshell.OpenCodeClient.Session>,
    exact: com.xiufeigo.opencodeshell.OpenCodeClient.Session?,
    server: String,
    openSessionIds: Set<String> = emptySet(),
    meta: ((com.xiufeigo.opencodeshell.OpenCodeClient.Session) -> String?)? = null,
): List<PaletteEntry> {
    val merged = linkedMapOf<String, com.xiufeigo.opencodeshell.OpenCodeClient.Session>()
    if (exact != null) merged[exact.id] = exact
    for (session in listed) merged.putIfAbsent(session.id, session)
    return merged.values
        .filter { (it.archivedAt ?: 0L) <= 0L }
        .take(SESSION_SEARCH_LIMIT)
        .map { session ->
            PaletteEntry.session(
                server = server,
                sessionID = session.id,
                title = session.title,
                description = session.directory,
                meta = meta?.invoke(session),
                open = session.id in openSessionIds,
            )
        }
}

/**
 * Active-row anchoring (`dialog.tsx:73-100`): when async results land, the
 * active row keeps its id instead of jumping to 0; a vanished id falls back
 * to the first row. Returns the anchored **index**.
 */
fun anchorActiveIndex(entries: List<PaletteEntry>, activeId: String?): Int {
    if (entries.isEmpty()) return 0
    if (activeId == null) return 0
    return entries.indexOfFirst { it.id == activeId }.takeIf { it >= 0 } ?: 0
}

/**
 * Recent-files source (`recentFileEntries`, `palette.ts:119-133`): opened tabs
 * with the active file first, deduped, capped at [ENTRY_LIMIT]. [tabs] is the
 * opened-tab order, [pathOf] maps a tab to its file path (null = not a file
 * tab), [activePath] is pinned first.
 */
fun recentFileEntries(
    tabs: List<String>,
    pathOf: (String) -> String?,
    activePath: String? = null,
): List<PaletteEntry> {
    val ordered = if (activePath != null) {
        listOf(activePath) + tabs.mapNotNull(pathOf).filter { it != activePath }
    } else {
        tabs.mapNotNull(pathOf)
    }
    val seen = HashSet<String>()
    return ordered.filter { it.isNotBlank() && seen.add(it) }
        .take(ENTRY_LIMIT)
        .map { PaletteEntry.file(it) }
}
