# WAVE 2.3b — Home + navigation shell parity (A2 deltas #1, #4, #7, #8, #9, #19)

Date: 2026-09-21. Owner files only: `ui/home/**`, `ui/nav/**`
(SessionTabs, DesktopTabStrip, BottomTabStrip, LastRouteStore, AppearancePrefs),
new tests, this note.

## A2 delta disposition

| A2 # | Item | P | Status | Where |
|------|------|---|--------|-------|
| 1 | Mobile bottom bar: full tab list + Home/Settings/Help drawer | P0 | DONE (drawer UI + model; App.kt wiring pending, see I-1) | `ui/nav/MobileTabsDrawer.kt` (new), `ui/nav/BottomTabStrip.kt`, `SessionTabs.footerHomePressed` |
| 4 | Home session-row context menu: rename / export / delete | P1 | DONE (menu + inline rename + delete dialog + export plumbing; App.kt `HomeRowActions` build pending, see I-2) | `ui/home/HomeRowMenu.kt` (new), `HomeSessionsList.kt`, `HomeScreen.kt`, `HomeRowActions.kt` (new), `HomeDeleteDialog.kt` (new) |
| 7 | `+` / Home context inheritance | P1 | DONE (pure branch logic; App.kt wiring pending, see I-3/I-4) | `SessionTabs.draftTarget/toggleHomeTarget`, `ui/home/NewSessionProject.kt` (new) |
| 8 | `mod+f` focuses Home search | P1 | DONE (field-side handle; App.kt chord branch pending, see I-5) | `HomeSearchFocusRequest` in `HomeSessionsList.kt`, `searchFocusRequest` params on `HomeSessionsColumn`/`HomeScreen` |
| 9 | Search full list + sessionID lookup | P1 | DONE | `HomeSessions.searchRows/looksLikeSessionID/filter`, `searchSessions` params on column + screen |
| 19 | Bottom-strip fallback label `oc_new_session` vs `oc_tab_session` | P2 | DONE | `SessionTab` now resolves via `SessionTabs.displayTitle(title, oc_tab_session)` |
| 2, 3 | DraftTab model + draft persistence | P1 | DEFERRED - design note below (D-1) | - |
| 5, 6 | Workspace selector, summary/MCP popover | P1 | DEFERRED - sibling-owned `NewSessionScreen.kt` body (see damage report) | - |
| 10, 11, 12 | Sounds / system notifications / mark-viewed | P1 | DEFERRED - outside owned files (`ChatViewModel`, `notify/`, `ui/session/`) | - |
| 13 | Floating new-session button copy | P1 | DEFERRED - small; kept icon-only pending design pass | - |
| 14, 15 | Tab persistence, close/reopen-closed chords | P1 | DEFERRED - needs `App.kt` + store work owned by main agent | - |
| 16 | Help external link | P1 | PARTIAL - drawer Help row calls `onOpenHelp` (App.kt must open `https://opencode.ai/desktop-feedback`); `HomeUtilityNav` placeholder untouched | `MobileTabsDrawer.kt` |
| 17, 18, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30 | P2 polish set | P2 | DEFERRED | - |

Fixed this wave: #1, #4, #7, #8, #9, #19 (6 items; #16 partial via drawer callback).
Deferred: the rest (see table + D-1).

## What changed (owned files)

NEW:
- `ui/nav/MobileTabsDrawer.kt` - v2 mobile drawer (titlebar.tsx:520-592):
  all open tabs (displayTitle-resolved, active marked) + New-session row +
  Home footer row (pressed off-session) + Settings/Help row pair. Self-contained
  in-tree sheet (scrim + bottom card); no dependency on ui/overlay.
- `ui/nav/SessionTabs.kt` - added pure model: `footerHomePressed`,
  `draftTarget` (+ `DraftTarget`: InheritSession / InheritProject /
  DefaultDirectory), `toggleHomeTarget` (+ `HomeToggle`: BackToTab / GoHome).
  Rewritten with line comments only (see damage report: block-comment KDoc was
  the corruption vector; `/*` sequences inside comments broke the parser).
- `ui/home/HomeRowMenu.kt` - pure menu+rename state machine (openMenu,
  closeMenu, openEditor, editDraft, submit w/ blank/unchanged noop, resolveSave,
  closeEditor, menuKeyOpens, isCompositionKey + IME_COMPOSITION_KEYCODE=229).
- `ui/home/HomeRowActions.kt` - `HomeRowActions(rename/export/delete)` host
  contract + `HomeRowExportSaver` (SessionExport.serialize + SAF saver +
  oc_export_success/failed toasts) + `rememberHomeRowExportSaver()`.
- `ui/home/HomeDeleteDialog.kt` - self-contained delete danger dialog
  (drawer's SessionDeleteDialog is sibling-owned; TODO(i18n): session.delete.*
  has no oc_* key yet - drawer's delete strings stand in).
- `ui/home/NewSessionProject.kt` - `NewSessionProject.resolve` =
  selected > lastProject > first opened (home/model.ts:24-29).

MODIFIED:
- `ui/nav/BottomTabStrip.kt` - current-session slot is now the drawer trigger
  (opens MobileTabsDrawer); new optional params `sessions`, `activeSessionId`,
  `onOpenSettings`, `onOpenHelp` (all defaulted, existing callers compile);
  `SessionTab` fallback fixed to `oc_tab_session` via displayTitle (A2 #19).
- `ui/home/HomeSessions.kt` - `searchRows` (untruncated source, scope-aware,
  dedup by id, newest-first), `looksLikeSessionID` (ses_ + >20 chars),
  `filter` session-ID exact fast path.
- `ui/home/HomeSessionsList.kt` - `HomeSessionsColumn` gains `searchSessions`,
  `onRenameSession/onExportSession/onDeleteSession`, `searchFocusRequest`
  (FocusRequester + SharedFlow collect); `HomeSearchFocusRequest` class;
  rows: combinedClickable long-press + ContextMenu/Shift+F10 key handler +
  DropdownMenu (Rename / Export... / Delete...) + inline rename editor
  (Enter=save, Esc=cancel, disabled while saving). Desktop right-click
  deferred: no verified pointer-button API on the pinned Compose BOM (noted in
  code); long-press + keyboard are the wired paths.
- `ui/home/HomeScreen.kt` - `searchFocusRequest` + `rowActions` params;
  passes full `sessions` as search source; builds rename/export/delete row
  callbacks; `pendingDelete` danger dialog.
- `ui/home/HomeProjects.kt` - reconstructed (was clobbered, see below):
  fromSessions, RECENTLY_CLOSED, merge, openRows, closedRows, sandboxesOf,
  directoriesFor, parentOf, iconColorHex, keyOf.
- `ui/home/HomeProjectsList.kt` - reconstructed column callbacks
  (onNewSession/onCloseProject/onReopenProject/onAddProject are per-project)
  + added `HomeProjectsDropdown` for the mobile layout; removed dead hint
  state/imports.
- `ui/home/NewSessionScreen.kt` - added `preselectedDirectory: String? = null`
  param (kept for App.kt's Route.newSession(directory) call; body still
  sibling-owned).

TESTS (new file only, existing suites untouched):
- `app/src/test/.../HomeShellWave23bTest.kt` - 17 tests: footerHomePressed,
  searchRows (cap/dedupe/scope/degrade), looksLikeSessionID + filter fast path,
  HomeRowMenu (menu move/dismiss, submit noop/save/resolve, closeEditor,
  menuKey, composition guard), draftTarget branches, NewSessionProject.resolve
  order, toggleHomeTarget.

## Build / test status

- `compileDebugKotlin` (--offline): GREEN (only pre-existing warnings).
- Targeted suites GREEN: HomeShellWave23bTest (17), SessionTabsTest,
  HomeSessionsTest, HomeNavTest, HomeProjectsTest, HomeProjectFilterTest,
  HomeProjectsMergeTest, HomeProjectSandboxesTest.
- Full `testDebugUnitTest`: 1135 tests, 6 failures - ALL in sibling-owned,
  untracked-from-HEAD suites unrelated to this wave: PatchBodyLinesTest (1),
  ToolPresentationTest (3), Wave22bToolFidelityTest (2). Failing files are
  either untracked new files from another agent or outside ui/home+ui/nav.
  Do NOT "fix" them from this wave.

## App.kt integration notes (for the main agent - REQUIRED wiring)

All symbols below already exist in the wave's files; App.kt just needs to call
them. Nothing in ui/App.kt was touched (not wave-owned).

I-1 (P0 drawer data): the `BottomTabStrip(...)` call (~App.kt:792) currently
passes only `session = activeTab`. Add:
  sessions = openSessions,                 // List<SessionTabRef>, full tab list
  activeSessionId = currentSession,        // String?, null off-session
  onOpenSettings = onOpenSettings,         // existing settings opener
  onOpenHelp = { /* open https://opencode.ai/desktop-feedback */ },
There is no `openExternal` helper in scope at that call site (only
MainActivity.openExternal exists); use Custom Tabs / ACTION_VIEW intent.

I-2 (P1 row actions): build once per Home entry (needs chatViewModel +
serverStore) and pass `rowActions = ...` into `HomeScreen`:
  val exportClient = remember(serverStore, rev) {
      serverStore?.server?.let { OpenCodeClient.forServer(it) } }  // SessionScreen A8 pattern
  HomeRowActions(
      rename = { id, title, cb -> chatViewModel?.renameSession(id, title, cb) },
      export = { row -> runCatching {
          SessionExport.Frame(exportClient!!.sessionJson(row.id, row.directory),
                              exportClient.messagesJson(row.id, row.directory)) }.getOrNull() },
      delete = { row, cb ->
          chatViewModel?.deleteSession(row.id, row.directory) { ok ->
              if (ok) { openSessions.removeAll { it.id == row.id }
                        if (currentSession == row.id) navController.popBackStack() }
              cb(ok) } },
  )
`HomeScreen` without `rowActions` (null default) keeps compiling with menu
items disabled - previews unaffected.

I-3 (P1 `+` inheritance): replace `onNewSession = { navigate(newSession()) }`
with a `SessionTabs.draftTarget(...)` branch:
  val target = SessionTabs.draftTarget(
      onSessionRoute = currentRoute == Route.SESSION, activeSessionId = currentSession,
      selectedDirectory = <home selection if hoisted, else null>,
      projectDirectories = <GET /project worktrees or emptyList()>,
      mostRecentDirectory = homeProjects.last())
  when (target) {
      is DraftTarget.InheritSession -> { /* model already effective via
          ChatViewModel.selectedModel; navigate newSession() */ }
      is DraftTarget.InheritProject -> navigate(Route.newSession(target.directory))
      DraftTarget.DefaultDirectory -> navigate(Route.newSession())
  }
True per-session composer-model handoff (reading another session's composer
state) needs a composer registry that does not exist yet; the effective model
(`ChatViewModel.selectedModel`, global) is already inherited implicitly.

I-4 (P1 Home toggle): in both `onHome` lambdas (desktop strip ~545, bottom
strip ~795), use `SessionTabs.toggleHomeTarget(onHomeRoute =
currentRoute == Route.HOME, mostRecentTabId = openSessions.lastOrNull()?.id)`:
BackToTab(id) -> openSession(id); GoHome -> existing navigate-HOME (+ record
recent = current tab before leaving; `openSessions.lastOrNull()` is the
run-scoped recent-tab approximation - persisted recent.key does not exist).

I-5 (P1 mod+f): `GlobalChord.SESSION_SEARCH` (~914) unconditionally opens
`TimelineSearch`. Gate it:
  if (currentRoute == Route.HOME) homeSearchFocusRequest.focus()
  else TimelineSearch.open()
Needs a `val homeSearchFocusRequest = remember { HomeSearchFocusRequest() }`
hoisted in AppScaffold and passed to `HomeScreen(searchFocusRequest = ...)`.
`HomeSearchFocusRequest` is in ui/home/HomeSessionsList.kt (owned by this
wave); App.kt only imports and calls `.focus()`.

I-6 (P1 search loading state): upstream shows a spinner while the sessionID
`lookup` is in flight (`lookingUp`). The native search is synchronous over the
already-fetched list, so there is no async lookup and no loading state by
design; noted, not a gap.

## D-1: DraftTab design note (A2 #2/#3, deferred - large)

v2 `Tab = SessionTab | DraftTab`; each `+` pushes a draft tab with independent
composer/directory/branch/MCP state, drafts persist per draftID, promoteDraft
atomically swaps draft->session, prepareSession failure rolls back. Native has
a singleton NEW_SESSION route (launchSingleTop) with remember-only state.
A self-contained path inside owned files: (1) `DraftTab(id, directory,
modelRef?, prompt?)` in ui/nav + `DraftStore` (SharedPreferences JSON,
per-draft prompt+directory+model, like LastRouteStore); (2) Route.NEW_SESSION
gains `?draftId=` (App.kt, main agent); (3) NewSessionScreen reads/writes the
store by draftId instead of remember; (4) SessionTabs gains
newDraft/promoteDraft pure transitions + JVM tests. Estimated ~400 lines +
tests; needs App.kt route + Home `+` wiring, so it was left as a note rather
than a half-built store.

## Damage report ( read before reviewing this diff )

The worktree arrived with heavy cross-agent damage INSIDE this wave's owned
paths (uncommitted worktree state, no stash, HEAD=51f8e70 has none of these
files): ui/nav/SessionTabs.kt + DesktopTabStrip.kt and
ui/home/DirectoryPickerDialog.kt existed as UNTRACKED files referencing APIs
that do not exist at HEAD (DirectoryPickerDialog used HomeProjects.parentOf;
DesktopTabStrip used internal BottomTabStrip pieces); App.kt/HomeScreen at HEAD
already call sibling-new APIs (onNewSessionInDirectory, homeProjectStore,
preselectedDirectory, HomeProjects.openRows/merge/directoriesFor/closedRows,
HomeProjectsDropdown). Separately, an encoding/ascii-fication pass had stripped
every KDoc `/**` opener and every `/*`-in-comment sequence repo-wide, leaving
orphan `*` continuation lines (hundreds of "Expecting member/top-level
declaration" errors) - SessionTabs.kt was rewritten from scratch with
line-comments only for this reason. Recovery: SessionTabs rewritten clean
(behavior = W7 close/title + new 2.3b pure model); HomeProjects.kt fully
reconstructed from the pinning tests (HomeProjectsTest,
HomeProjectFilterTest, HomeProjectsMergeTest, HomeProjectSandboxesTest);
HomeProjectsList column callbacks restored per HomeScreen call sites +
HomeProjectsDropdown added; NewSessionScreen gained preselectedDirectory.
Full compile is green; the 6 remaining test failures are sibling-owned suites
outside this wave (listed above). Main agent: diff ui/home + ui/nav against
HEAD with care - parts of that diff are reconstruction, not 2.3b features;
the 2.3b additions are marked with WAVE 2.3b comments.
