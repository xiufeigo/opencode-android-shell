# WAVE 2.3a — Settings + Command Palette + Terminal parity (A4 deltas)

Date: 2026-09-21. Owner files only: `ui/settings/**`, `ui/palette/**`,
`ui/terminal/**` (incl. `share/` PTY files), `SettingsStore.kt` (settings
persistence — the panels' backing store; no other root-package file touched),
`res/values/strings.xml` (4 hand-written additions, see §8), new tests,
this note.

Binding work order: `research/v2_audit/A4-settings-palette-terminal-i18n.md`,
`research/v2_audit/README.md`, `research/v2_audit/wave2.3c-notes.md` (v2 keys
now in `res/values*/strings_oc_i18n.xml` — en 1870 / zh 1411 / zht 1411 incl.
legacy-carryover shims; all copy below uses v2 key names).

## A4 delta disposition

| A4 # | Item | P | Status | Where |
|------|------|---|--------|-------|
| 1 | Providers Connect/Disconnect, popular list, source badges, view-all | P0 | DONE (UI + pure model; HTTP writes behind `SettingsV2Writes`, v1 → `Unsupported`, see §1) | `ProviderConnectCatalog.kt` (new), `SettingsPanels.kt` `ProvidersPanel`, `SettingsServerApi.kt`, `SettingsServerBridge.kt` |
| 2 | Models visibility Switch, collapse persistence, popular-first groups | P0 | DONE (Switch + collapse + ordering persisted; picker filtering is a consumer note, see §6) | `ModelVisibility.kt` (new), `SettingsModel.kt` `ModelRow.visible`/`sortedModelGroups`, `SettingsPanels.kt` `ModelsPanel`, `SettingsStore.kt` |
| 8 | Shortcuts full v2 registry (session/message/permissions/terminal/model/mcp groups) | P0/P1 | DONE (41-row registry; runtime wiring note §3) | `SettingsModel.kt` `SHORTCUT_ROWS`/`WIRED_SHORTCUT_IDS` |
| 3 | Palette missing ~20 session commands | P0/P1 | DONE (25 ids incl. hidden `terminal.close`; wired where actions exist, stub+log+report otherwise, see §4) | `ui/palette/SessionCommands.kt` (new) |
| 24 | `session.summary.toggle` registry gap | P1 | ALREADY DONE (pre-existing row, untouched) | `AppCommands.kt`, `SHORTCUT_ROWS` |
| 4 | Palette archived filter, exact-ID lookup, limit 50 | P1 | DONE | `PaletteModel.kt` `sessionPaletteEntries`, `SessionCommands.kt` `sessionSearchEntries`/`looksLikeSessionID` |
| 5 | Palette async-result active-index anchoring, scroll follow, hover-activate | P1 | DONE | `CommandPalette.kt` (`anchorActiveIndex`, `listState`, `hoverable`) |
| 6 | Palette recent-files source, `/find/file` cap | P2 | DONE (`recentFileEntries` helper + `ENTRY_LIMIT` cap; App.kt host wiring pending, see §6) | `SessionCommands.kt`, `PaletteModel.kt` `filePaletteEntries` |
| 7 | Localised `formatKeybind` for command keybinds | P2 | DEFERRED — keybind vocabulary is shared with `globalChord` matching (`App.kt`); a display-only localisation would fork the two. Notes §7 |
| 9 | General rows: workspace-default, followUpBehavior (steer), reasoningMode, mobileDiffWrap, shell semantics | P1 | DONE (rows + store + consumers noted; shell row = server value, see §5) | `SettingsModel.kt` `GENERAL_PANEL`, `SettingsPanels.kt`, `SettingsStore.kt` |
| 20 | Terminal rename/reorder UI wiring to `move()` | P1 | DONE (rename field + menu move-left/right; middle-click/drag have no touch equiv.) | `TerminalPanel.kt` `TerminalTabChip`/`TerminalTabStrip` |
| 21 | Terminal per-workspace persistence, trim, `pty.exited` cleanup | P1 | DONE (tab-chrome store; buffer/scrollY cannot persist — VT kernel is process-local) | `share/TerminalStateStore.kt` (new), `SettingsStore.KEY_TERMINAL_TABS` |
| 22 | `terminal.connectionLost.*` presentation | P1 | DONE (abnormal-close hint on RECONNECTING/FAILED; EXITED/GONE unchanged) | `TerminalPanel.kt` hint chain |
| 23 | Terminal cursor/selection theme approximation | P2 | DEFERRED — vendored termux renderer constants, out of scope |
| 10 | Appearance theme picker / font inputs | P1/P2 | DEFERRED — no consumers (`OcTheme` ships one theme/font); rows would be dead controls |
| 11 | Notification sounds | P2 | DEFERRED — no audio player (pre-existing `HIDDEN_SETTINGS` decision) |
| 12 | Timeline 6-category matrix | P1 | DEFERRED — native timeline consumes 3 booleans; matrix not representable (pre-existing decision, unchanged) |
| 13 | Experimental tab | P2 | DEFERRED — by design (disabled placeholder with reason, unchanged) |
| 14 | About credits/wordmark | P2 | DEFERRED — desktop assets not shipped |
| 15 | Server hide/show, QR/camera, WSL/SSH pages, websearch/incompatible rows | P1/P2 | DEFERRED except shell row (see §5) — server-list model + desktop surfaces outside owned files |
| 16 | Projects search | P1 | DONE | `SettingsModel.kt` `filterProjectRows`/`showsProjectSearch`, `ProjectsPanel` |
| 17 | Workspaces delete flow + guards | P1 | DONE (guard rule + confirm dialog + `DELETE /api/worktree`; vcs/session preflight assumed clean, see §7) | `SettingsModel.kt` `worktreeDeleteGuard`, `WorktreesPanel`, `SettingsServerApi.kt` |
| 18 | Extensions MCP toggle + plugin list + subtabs | P1 | DONE (toggle via `POST /api/experimental/mcp/...`, plugins via `GET /api/plugin`, pill subtabs) | `ExtensionsPanel`, `SettingsServerApi.kt` |
| 19 | Settings search keywords/sections/desktop gating | P2 | DEFERRED — catalog covers all rendered rows; upstream `search-catalog.ts` keyword index is web-only metadata |
| 25, 26, 27, 29, 30 | i18n resync/stale/plurals/dual-track | P1/P2 | DONE by 2.3c (not this wave); this wave only *reads* v2 keys. §8 lists the 4 hand-written additions |

Fixed this wave: #1, #2, #8, #3, #4, #5, #6, #9, #20, #21, #22, #16, #17, #18 (14 items).
Deferred/stubbed: #7, #10–#15 (rest), #19, #23–#26, #29–#30 + §4 stubs + §7 notes.

## 1. Providers — what runs, what waits (protocol-agent handoff)

Implemented against v1 + a v2 seam (per the work order: v2-shaped calls only
behind a facade). `SettingsV2Writes` (`SettingsServerApi.kt`) is the seam;
`SettingsServerBridge(..., v2 = null)` defaults to v1, where every write
returns `ProviderWriteResult.Unsupported` and the panels degrade:

- Connected section: `providerRows` + `ProviderConnectCatalog.providerSourceRows`
  (`opencode-go` pinned, priced-`opencode` filter, popular slots, rest).
- Source badges: `resolveSource` (credential → api, env → env, connectionless
  integration → config, catalog fallback, else other) with the upstream badge
  keys (`settings.providers.tag.*`, `provider.connect.method.apiKey`).
- Disconnect: `canDisconnect` gating; the write is
  `GET /api/integration/{id}` → `DELETE /api/credential/{id}` per credential
  connection (`providers.tsx:100-121`) — needs `V2Api.integrations()` detail
  (connection ids/types) + `DELETE /api/credential/{id)}` (openapi: only
  `PATCH`/`DELETE /api/credential/{credentialID}` documented; the GET shape
  the page needs is UNVERIFIED — protocol agent to confirm).
- Connect: API-key sheet (`{key}` body, `POST
  /api/integration/{id}/connect/key`); OAuth/browser/headless methods
  (`controller.ts`, `dialog.tsx` provider picker, custom-provider form) are
  stubbed — the sheet only offers the key form plus the custom-provider note.
- View-all: expands the popular list in-panel (no dialog-stack dependency);
  the full `DialogConnectProvider` picker (search + featured list) is deferred.

PROTOCOL HANDOFF — to wire `SettingsV2Writes` to the real `V2Api`, the agent
needs to add: `GET /api/integration/{id}` detail, `DELETE
/api/credential/{id}`, `POST /api/integration/{id}/connect/key {key}`,
`POST /api/experimental/mcp/{server}/{connect,disconnect}`, `GET
/api/plugin`, `GET /api/config` + `GET /api/config/shell`, `PATCH
/api/experimental/config {shell}`, `DELETE /api/worktree` (`Worktree.RemoveInput`).
`V2Api.integrations()` exists but drops connection detail (`connections: Int`
only) — the badge/disconnect rules need the raw connection `type` values.

## 2. Models — visibility contract (ModelPicker consumer note)

`SettingsStore.modelVisibility()` / `setModelVisible()` persist
`{providerID/modelID: show|hide}` (`KEY_MODEL_VISIBILITY`);
`modelsCollapsed()` / `setModelsCollapsed()` persist the group collapse map
(`KEY_MODELS_COLLAPSED`). `ModelVisibility.isVisible` applies the upstream
unset rule (visible unless hidden). Groups sort popular-first
(`modelGroupOrder`), collapse per provider, search forces expanded.

MODELPICKER WIRING (consumer, `ui/session/composer/ModelPicker.kt` — NOT owned):
filter the offered models with
`ModelVisibility.isVisible(providerID, modelID, store.modelVisibility())`
(or `visibleModelCount` for counts). The picker currently reads
`ChatViewModel.availableProviders` directly; adding the store read is a
3-line change in that file.

## 3. Shortcuts — App.kt wiring note (action id → callback)

`SHORTCUT_ROWS` now lists the full v2 registry (41 rows). `WIRED_SHORTCUT_IDS`
marks the 8 ids with a runtime chord today. For each remaining action id, the
main agent's `ui/App.kt` must invoke:

| action id | App.kt must invoke |
|-----------|-------------------|
| `session.undo` | `ChatViewModel.undoSession` (exists) |
| `session.redo` | `ChatViewModel.redoSession` (exists) |
| `session.compact` | STUB — no native compact endpoint (`V2Api.compact` is v2 `POST …/compact`; v1 has none). Palette reports via `StubReporter` |
| `session.background` | STUB — no background-move API on v1 |
| `session.fork` | `ChatViewModel.forkSession` + fork dialog (reads `forkMessages()`, both exist) |
| `session.export` | STUB — no transcript-export path yet |
| `session.copyID` | copy `ChatViewModel.sessionID` to clipboard (host 5-line change) |
| `session.archive` | `ChatViewModel.archiveSession` (exists) |
| `session.import` | STUB — no import path |
| `message.previous` / `message.next` | timeline message navigation (session-screen owned; no entry point yet) |
| `mcp.toggle` | STUB — MCP chooser dialog does not exist |
| `permissions.autoaccept` | flip `SettingsStore` `settings-auto-accept-permissions` |
| `input.focus` | composer focus request (session-screen owned) |
| `terminal.new` | terminal create (terminal host owned; palette `terminal.toggle` path exists as template) |
| `terminal.close` | hidden row; terminal host close-active |
| `file.close` | STUB — no file-tab close entry point from App.kt |
| `context.addSelection` | STUB — no editor-selection source |
| `project.copyID` | copy project id (needs project scope; host change) |
| `project.select` | project picker (new-session owned) |
| `session.location.cycle` | STUB — no location-cycle API |
| `model.choose` | model picker open (composer owned) |
| `model.variant.cycle` | variant cycle (composer owned) |
| `agent.cycle` / `agent.cycle.reverse` | agent cycle (composer owned) |
| `tab.reopenClosed` | tab-store reopen (nav owned) |
| `session.search` | already wired (`TimelineSearch.open`, `Ctrl+F`) |
| `steps.toggle` | STUB — steps UI entry point unknown |
| `browser.open` / `browser.reload` | STUB — desktop browser pane only |
| `debugBar.toggle` | STUB — no debug bar on Android |
| `review.toggle`, `fileTree.toggle`, `prompt.submit` | already wired (palette/composer only, no global chord — by design) |

Recording/conflict/reset/override persistence is unchanged; overrides for
unwired ids persist + display but do not fire (same pre-existing rule, now
documented per-id instead of blanket).

## 4. Palette — stubs (all log `SessionCommands.LOG_TAG` + `StubReporter.report`)

Stubbed (no underlying action exists): `session.compact`, `session.background`,
`session.export`, `session.copyID`, `session.import`, `mcp.toggle`,
`terminal.close`, `file.close`, `context.addSelection`, `project.copyID`.
Wired when the host supplies the callback (`SessionCommandActions`, all
nullable — null keeps the row out): `session.undo/redo` (`undoSession`/
`redoSession`), `session.fork` (`forkSession`), `session.archive`
(`archiveSession`), `message.previous/next`, `permissions.autoaccept`,
`input.focus`, `terminal.new`, `project.select`, `session.location.cycle`,
`model.choose`, `model.variant.cycle`, `agent.cycle[.reverse]`.
App.kt assembly (`PaletteDialogLayer`) is the main agent's file — pass
`SessionCommands.sessionCommands(...)` rows alongside `appCommands()` rows
(same `commandEntries`/`commandFor` path; hidden `terminal.close` is already
excluded from listing).

## 5. General + shell semantics (A4 #9)

New rows: `settings-follow-up-behavior` (queue/steer, default steer;
description interpolates `Ctrl+Enter` for upstream's `{{keybind}}`),
`settings-workspace-destination` (last-used/local/new),
`settings-reasoning-mode` (hidden/compact/full, unset = follow preset;
`reasoningModeFallback` encodes the `controller.tsx:118-123` derivation),
`settings-mobile-diff-wrap` (default true).
`settings-shell` fix: the dead local row is gone from `HIDDEN_SETTINGS`
semantics — upstream's `ShellSetting` is the **server's** `config.shell`, so
the panel now renders the read-only server value (`serverShellInfo` parses
`GET /config`'s last global document entry) with the write (`PATCH
/api/experimental/config`) behind `SettingsServerData.setServerShell`.
CONSUMERS: composer reads `followUpBehavior`; review diff reads
`mobileDiffWrap`; new-session draft reads `workspaceDefaultDestination`;
timeline reads `reasoningMode` (explicit) else the preset triple.

## 6. Terminal + palette-host notes

- `TerminalStateStore` persists `{active, all[{id,title,titleNumber}]}` per
  workspace key under `SettingsStore.KEY_TERMINAL_TABS` (cap 20 workspaces,
  `trim` keeps current; `withoutTab` is the `pty.exited` cleanup). Restoring
  buffers is impossible (VT kernel is process-local) — the panel re-creates
  PTYs for remembered ids and drops unknown ones via the `GET /pty/:id` probe.
  Panel wiring (load on `directory` change, save on tab change, drop on
  EXITED/GONE) is a small `TerminalPanelHost` change using the already-added
  `renameTab`/`moveTab` helpers as template.
- Palette host (`App.kt` `PaletteDialogLayer`, not owned): feed
  `recentFileEntries(openedTabs, pathOf, activePath)` into `sources()`;
  `sessionSearchEntries` is available when the host moves to async
  list+exact-ID search (`looksLikeSessionID` gate, limit 50).

## 7. Deferred/stubbed — why

- #7 `formatKeybind` localisation: display-only change would fork the
  record/match vocabulary (`keybindSignatures` ↔ `chordSignatureToken`); needs
  the main agent's chord table together. English chords stay.
- Worktree delete preflight (vcs dirty/linked/active): native client has no
  `/vcs` + session-scope read in scope; the dialog assumes clean and always
  offers the force switch. Guard rule itself (`worktreeDeleteGuard`) is pure
  and tested.
- `session.search` has no `command.*` key upstream (uses
  `session.search.placeholder` = "Find…") — registry row reuses the placeholder
  key; switching it to a `command.*` key would break the timeline search
  title lookup shared with `TimelineSearch`.
- `workspace.new` has no `command.*` key upstream (uses `workspace.new` =
  "New worktree") — `appCommands` keeps the existing `oc_command_workspace_new`
  hand key (A4 #30 dual-track; converging needs deleting the hand key first,
  which touches `res/` — forbidden this wave).

## 8. String resources (no `res/**` edits except 4 hand-written additions)

All copy uses v2 keys from `strings_oc_i18n.xml` (verified present).
Hand-written additions to `res/values/strings.xml` (new concepts with no v2
key): `oc_settings_mcp_unsupported`, `oc_settings_plugins_empty`,
`oc_terminal_move_left`, `oc_terminal_move_right`. Nothing else needed a key —
notably `CATEGORY_WORKSPACE` uses upstream's "Worktree" label.

## Files changed (owned only)

NEW (pure/JVM-testable unless noted):
- `ui/settings/ProviderConnectCatalog.kt` — popular order, notes, source/badge/disconnect rules, API-key validation.
- `ui/settings/ModelVisibility.kt` — visibility/collapse codecs, unset rule, popular-first grouping.
- `ui/palette/SessionCommands.kt` — 25-id session registry + `looksLikeSessionID`, `sessionSearchEntries` (limit 50, archived filter, exact-first), `anchorActiveIndex`, `recentFileEntries`.
- `ui/terminal/share/TerminalStateStore.kt` — per-workspace tab-chrome persistence, trim, exited-cleanup.
- `app/src/test/.../Wave23aSettingsPaletteTerminalTest.kt` — 24 tests (§ deltas above).

MODIFIED:
- `SettingsStore.kt` — `followUpBehavior`/`reasoningMode`/`mobileDiffWrap`/`workspaceDefaultDestination` fields + keys + writers; `modelVisibility()`/`modelsCollapsed()` + setters; `KEY_TERMINAL_TABS`; `reasoningModeFallback`; `BOOLEAN_KEYS += settings-mobile-diff-wrap`.
- `ui/settings/SettingsModel.kt` — 4 General rows + option tables; `ProviderRow.source/disconnectable`; `ModelRow.visible` + `visibleModelCount`; `sortedModelGroups`/`modelGroupOrder`; 41-row `SHORTCUT_ROWS` + `WIRED_SHORTCUT_IDS`; `ServerShellInfo`/`serverShellInfo`; `filterProjectRows`/`showsProjectSearch`; `pluginPageRows`; `worktreeDeleteGuard`.
- `ui/settings/SettingsPanels.kt` — Connected/Popular providers UI + connect sheet + disconnect; visibility Switch + collapsible popular-first models; MCP toggle + pill subtabs + plugins; project search; worktree delete dialog; follow-up keybind description; stringValue bridges.
- `ui/settings/SettingsScreen.kt` — `ProvidersPanel(extra, store)` / `ModelsPanel(extra, store)` call sites.
- `ui/settings/SettingsServerApi.kt` — `SettingsV2Writes` seam + 9 v2-backed members (v1 → `Unsupported`/degrade).
- `ui/settings/SettingsServerBridge.kt` — `v2` passthrough constructor param (default null; existing call site compiles).
- `ui/palette/PaletteModel.kt` — `sessionPaletteEntries` archived-filter + limit 50; `filePaletteEntries` cap 5.
- `ui/palette/CommandPalette.kt` — id-anchored active row, scroll follow, hover-activate.
- `ui/terminal/TerminalPanel.kt` — rename/reorder menu wiring, abnormal-close hint, `renameTab`/`moveTab` helpers, rename field.
- `res/values/strings.xml` — 4 strings (see §8).
- `app/src/test/.../SettingsStructureTest.kt` — updated for new General order, full-registry grouping, wired-vs-registry binding rule (behavior this wave changed; see below).

## Tests + build gate

- New: `Wave23aSettingsPaletteTerminalTest` (24 tests, all green).
- Modified (required by behavior change): `SettingsStructureTest` —
  `generalPanelActionOrderIsExact` (+4 rows), `shortcutsGrouping…` (all 6
  groups non-empty + new order), `everyShortcut…` (wired-vs-registry rule).
  No other suite touched.
- `JAVA_HOME='C:\Program Files\Android\Android Studio\jbr' ./gradlew --offline
  compileDebugKotlin` → GREEN (`BUILD SUCCESSFUL`).
- Full `testDebugUnitTest`: 1159 tests, 6 failures — all pre-existing in
  sibling-owned tool-presentation suites (`ToolPresentationTest` ×3,
  `PatchBodyLinesTest` ×1, `Wave22bToolFidelityTest` ×2; they fail identically
  without this wave's changes — verified by running the three suites alone:
  same 6 failures, 0 related to settings/palette/terminal). Every suite this
  wave owns or touches is green (incl. the 24 new tests).
