# WAVE 2.3c — i18n re-extraction from the v2 frontend

- Upstream: `_upstream/` branch `v2`, `@opencode/app 2.0.11` (production build
  `_upstream/packages/app/dist/`, built with `OPENCODE_CHANNEL=prod bunx vite build --base ./ --sourcemap false`).
- Sources: `_upstream/packages/app/src/runtime/i18n/{en,zh,zht}.ts` (+ `desktop-native.ts`,
  spread into app `en`) and `_upstream/packages/ui/src/i18n/{en,zh,zht}.ts`.
- Outputs: `research/_i18n_v2/{en,zh,zht}.json` (new; `research/_i18n/*.json` kept as v1 reference).
- Android: `app/src/main/res/values/strings_oc_i18n.xml` (default/en),
  `values-zh-rCN/`, `values-zh-rTW/`, regenerated with
  `pwsh -File scripts/gen_i18n.ps1 -I18nDir 'research/_i18n_v2' -Prefixes '*' -CompatShimJson 'research/_i18n'`.

## 1. Extraction method (why TS source, not dist chunks)

`scripts/extract-i18n.mjs` gained two modes (backward compatible):

- `--src` (default): parse the TS dict sources directly. This is what produced
  `research/_i18n_v2/*.json`. Per-locale union mirrors the web runtime
  (`language.tsx`: `base = { ...appEn, ...uiEn }`, `zh = { ...appZh, ...uiZh }`).
- `--dist [dir]`: legacy v1 path (scan vendored `assets/*.js` for inlined
  `"key":"value"` tables) writing to `research/_i18n/`. Verified byte-identical:
  re-running `--dist` against `app/src/main/assets/opencode-app/assets` leaves
  `research/_i18n/` unmodified.

Source parsing was chosen over dist-chunk scanning for three measured reasons:

1. **Hyphenated keys are invisible to the old regex.** `en.ts` holds
   `ssh.error.host-key`, `ssh.error.ssh-missing`,
   `settings.timeline.preset.text-only`, `settings.timeline.description.text-only`;
   the old `[a-zA-Z0-9_]` key class drops them. New regex admits `-`.
2. **Single-quoted values are invisible to the old regex.** 11 app-en keys use
   `'...'` values (contain `"` inside, e.g. `prompt.placeholder.normal`,
   `error.chain.*`, `settings.search.empty.query`, `session.delete.confirm`,
   `workspace.reset.confirm`). New regex accepts both quote styles.
3. **Dist en is fragmented, not bucketed.** In the v2 build, locale tables are
   split per lazy chunk (`<locale>-*.js` pairs: app dict + ui dict, e.g. two
   `zht-*.js`), while the *en* tables stay scattered across feature chunks
   (`button-*.js`, …) — the old `index-`/`zh-`/`zht-` filename bucketing no
   longer isolates locales. Backtick-quoted values in dist also break the old
   `"`-only value pattern.

TS escape decoding: only `\n` and `\'` occur in today's dicts; the extractor
decodes `\n \r \t \b \f \v \0 \\ \' \" \x.. \u.... \u{...}` to runtime text so
extracted JSON matches what the web renders.

**Ground truth check** (bun TS import vs extracted JSON): en 1714/1714,
zh 1255/1255, zht 1255/1255 — zero missing, zero extra in all three locales.

## 2. Key counts before / after

| locale | old dict (`research/_i18n`) | v2 dict (`research/_i18n_v2`) | XML before | XML after |
|---|---|---|---|---|
| en | 1399 | **1714** (+471 / −156) | 1082 | **1870** (1714 v2 + 156 shim) |
| zh | 1311 | **1255** (+13 / −69) | 1080 | **1411** (1255 v2 + 156 shim) |
| zht | 1309 | **1255** (+8 / −62) | 1073 | **1411** (1255 v2 + 156 shim) |

- Old-en removals (−156) split into ~87 code/file-type label strings swept up
  by the v1 dist scraper (`package.json`, `tailwind.config.ts`, `CMakeLists.txt`,
  … — never real i18n keys, never referenced) and **69 true stale keys** (§3).
- zh/zht are now **in sync** with each other (both 1255; the old 2/3-key drift
  from A4 §25 is gone). The `keys-missing-in-zh/zht=459` lines in the generator
  log are expected: upstream ships English-only additions and relies on runtime
  English fallback (per `packages/app/AGENTS.md` localization rule), so zh/zht
  dicts simply don't carry those keys. All three locales are in sync *with
  upstream* — every translated key upstream ships is present.
- Same-name value updates: **72** en strings changed vs the old file. Notable:
  `dialog.usageExceeded.freeTier.description` `$10/month` → `starting at $5/month`
  (A4 #27, now fixed at the source), `command.workspace.toggle` and several
  `workspace.*` strings `Workspace` → `Worktree`, `settings.*` description
  rewrites (`settings.providers.description`, `settings.models.description`,
  `settings.general.section.notifications` → `Desktop notifications`), and the
  old file's 11 `<!-- upstream alias ... (untranslated) -->` placeholders now
  resolve to real copy (e.g. `prompt.dropzone.file.label` = `Drop to @mention file`).
  Ellipsis rendering (`…` U+2026) verified byte-correct (`e2 80 a6`) through
  JSON → XML; earlier `��` sightings were a console-decoding artifact only.

## 3. Stale-key state (69 legacy keys — RETAINED as shim, NOT removed)

Task §4 said "candidates for removal — do NOT remove them yet". During the
build gate this proved load-bearing rather than hypothetical: the regenerated
pure-v2 files dropped all 69 stale names, and `compileDebugKotlin` failed on 9
unresolved references across 3 Kotlin files (details §4). A `-CompatShimJson`
switch was therefore added to `gen_i18n.ps1`: legacy keys present in
`research/_i18n` but absent from the v2 dicts are re-emitted in a flagged tail
block (`<!-- legacy-carryover ... -->` per entry; never overriding a v2 key or
a hand-written name). The XML files below thus contain 1714/1255/1255 v2 keys
**plus a 156-entry shim** (the 69 true-stale keys + ~87 v1 file-label strings;
all comment-flagged). The shim keeps the tree compiling until the owning waves
migrate the Kotlin call sites; a future cleanup run without `-CompatShimJson`
(plus the Kotlin edits in §4) removes it.

Shim list (156 = the 69 below + 87 `*.config.js`/`package.json`-style scraper
noise, all flagged in-file):

- `command` (4): `command_session_share`, `command_session_share_description`,
  `command_session_unshare`, `command_session_unshare_description`
- `dialog` (7): `dialog_server_add_corsHint`, `dialog_server_add_errorAuth`,
  `dialog_server_add_errorCors`, `dialog_server_add_username`,
  `dialog_server_add_usernamePlaceholder`, `dialog_server_apply`,
  `dialog_server_status_current`
- `drag` (1): `drag_enter`
- `help` (11): `help_tabs_date`, `help_tabs_description`, `help_tabs_home`,
  `help_tabs_introduction`, `help_tabs_organize`, `help_tabs_persistence`,
  `help_tabs_sessions`, `help_tabs_title`, `help_tabs_toast_ariaLabel`,
  `help_tabs_toast_dismiss`, `help_tabs_worktrees`
- `mode` (1): `mode_shell`
- `prompt` (3): `prompt_toast_pasteUnsupported_description`,
  `prompt_toast_pasteUnsupported_title`, `prompt_toast_promptSendFailed_retry`
- `server` (1): `server_row_noUsername`
- `session` (22): `session_followupDock_collapse/edit/expand/sendNow/summary_one/summary_other`,
  `session_revertDock_collapse/expand/restore/summary_one/summary_other`,
  `session_share_action_publish/publishing/share/unpublish/unpublishing/view`,
  `session_share_copy_copied/copyLink`,
  `session_share_popover_description_shared/description_unshared/title`
- `settings` (12): `settings_general_row_followup_description/title/option_queue/option_steer`,
  `settings_general_row_newInterface_badge/description/title`,
  `settings_general_row_newInterfaceNotice_description/dismiss/title`,
  `settings_general_row_tabPlusCreatesSession_description/title`
- `toast` (5): `toast_session_share_copyFailed_title/failed_description/failed_title/success_description/success_title`
- `ui` (2): `ui_tool_todos`, `ui_tool_todos_read`

## 4. Dead Kotlin references (report only — no .kt edits)

Method: enumerated all 275 distinct `R.string.*` / `R.plurals.*` references via
`git grep` and diffed against the v2 en key set. 271 "misses" are the
hand-written `oc_*` / `chat_*` / `error_*` / `R.plurals.oc_*` track
(`values/strings.xml`, dual-track per A4 #30) — **not dead**, out of scope.

Genuinely dead (generated-namespace refs to keys absent upstream):

| ref | file(s) | note |
|---|---|---|
| `R.string.command_session_share` / `command_session_unshare` | `ui/session/header/SessionHeader.kt:488-490` | v2 dropped `command.session.{share,unshare}`; share flow itself is gone upstream (no `toast.session.share.*` either — only `toast.session.unshare.*` survives) |
| `R.string.toast_session_share_failed_title` | `ui/session/actions/SessionActionsHost.kt:131` (`share()`) | v2 has no `toast.session.share.*` keys at all |
| `R.string.toast_session_share_copyFailed_title` / `toast_session_share_success_description` | `SessionActionsHost.kt:183,187` (`copy()`) | same; success path comment even cites the v1 copy |

Related and now compiling via the shim (names resolve; functional ownership
with the settings/palette wave):
`session_share_popover_*`, `session_share_copy_copyLink`,
`session_share_action_unpublish` (`SessionActionsDialogs.kt`),
`toast_session_share_*` (`SessionActionsHost.kt:131,183,187`),
`command_session_share/unshare` (`SessionHeader.kt:488-490`) — all flagged
`legacy-carryover` in the XML. Whether the v2 UI still has the share dialog is
a functional question, not a resource one.

## 5b. Build gate status ( honest )

`JAVA_HOME='C:\Program Files\Android\Android Studio\jbr' ./gradlew --offline compileDebugKotlin`
→ **RED on parallel-agent files, GREEN on everything this wave owns**:

- `:app:mergeDebugResources` — BUILD SUCCESSFUL (all three regenerated XML files
  merge cleanly: no bad escapes, no duplicate names, no missing-name errors).
- All 9 `Unresolved reference '...share...'` errors from the pure-v2 attempt are
  gone after the shim (verified: zero `^e:.*R.string|strings_oc` errors).
- Remaining ~65 errors are all in concurrently-added files outside this wave's
  ownership (`ui/home/HomeScreen.kt`, `HomeDeleteDialog.kt` [unclosed comment],
  `HomeRowActions.kt` [unclosed comment], `DirectoryPickerDialog.kt`,
  `ui/markdown/MarkdownRenderer.kt`, `ui/App.kt:621`) — untracked/working-copy
  files from parallel agents (home-screen wave in flight). No .kt edits were
  made here per the task's FORBIDDEN list; the owning wave's cleanup will clear
  them. Re-run the gate once that wave lands.

## 5. What the settings / palette wave needs to know

- **All 146 missing `settings.*` keys now exist** (tab titles, timeline
  preset/category matrix, workspaces/projects/search-catalog copy, about page,
  `followUpBehavior`/`reasoningMode`/`terminalPlacement` rows, `server.connect.*`,
  `dialog.server.menu.hide/show`). Settings panels can switch from hand-written
  `oc_setting_*` to generated keys at will — but note the "existing wins" rule:
  generation skips names already present in `strings.xml`, so convergence needs
  deleting the hand-written duplicate first (A4 #30 dual-track still open).
- **Palette/command copy**: `command.session.{background,copyID,import,location.cycle,summary.toggle}`,
  `command.browser.open/reload`, `command.debugBar.toggle`, `command.project.copyID`,
  `common.copied/viewAll` are all present now.
- **New hyphenated resource names** (dash → underscore, collision-free, verified
  zero collisions across the 1714-key set): `ssh_error_host_key`,
  `ssh_error_ssh_missing`, `settings_timeline_preset_text_only`,
  `settings_timeline_description_text_only`.
- **`.one/.other` plurals are still plain strings** (gen script convention
  unchanged, A4 #26 open): `settings.workspaces.count.one/other`,
  `workspace.reset.archived.{none,one,many}`, `ui.question.subtitle.answered`,
  etc. Callers must keep hand-selecting via `language.plural` semantics.
- **Known-lossy values for future work** (script warns, values land safely):
  `prompt.toast.uploading.percent` / `ui.promptInput.uploading` (`{{percent}}%`
  → `%1$s%%` — caller must `format()`, else renders `%%`); 10 edge-whitespace
  values are XML-quoted to preserve spaces (`provider.connect.*.visit.prefix/suffix`,
  `common.moreCountSuffix`, `ui.lineComment.*.prefix`); 3 empty en values emit
  self-closing tags (`ui.lineComment.*.suffix`, `ui.list.emptyWithFilter.suffix`);
  `desktop.recovery.unknown` = `<unknown>` is entity-escaped.
- **Scope decision (A4 §"范围外")**: `wsl.*` (67), `ssh.*` (42), `debugBar.*` (38),
  `desktop.*` (95), `sound.*` (46), `project.*` (33) were all generated
  (`-Prefixes '*'`, nothing skipped). Per the upstream fallback rule they are
  harmless if the features don't ship — absent keys fall back to English at
  runtime, and present-but-unused strings cost nothing. If APK size ever matters,
  narrow `-Prefixes`, but keep `app,command,common,context,dialog,drag,error,
  help,home,language,lsp,mcp,mode,model,notification,palette,prompt,provider,
  server,session,settings,sidebar,status,terminal,theme,titlebar,toast,ui,workspace`
  plus the in-scope subset.
- **zh/zht gap (459 en-only keys)** is upstream-faithful, not a bug: feature work
  adds English only; translations land after review. Do not backfill with machine
  translation (upstream AGENTS.md forbids translating from model knowledge).

## 6. Files changed

- `scripts/extract-i18n.mjs` — `--src` (default) / `--dist [dir]` modes, TS
  dict parser (hyphen keys, single-quoted values, TS escape decoding),
  per-locale app+ui union, sorted JSON output.
- `scripts/gen_i18n.ps1` — new `-I18nDir` param (default `research/_i18n`,
  wave used `research/_i18n_v2`); new `-CompatShimJson` param (wave used
  `research/_i18n`: re-emits legacy keys absent from v2 in a flagged
  `legacy-carryover` tail block so pre-existing Kotlin refs keep compiling);
  `Convert-KeyToName` maps `-` → `_`; generated-file header names the source dir.
- `research/_i18n_v2/{en,zh,zht}.json` — new (1714 / 1255 / 1255 keys).
- `app/src/main/res/{values,values-zh-rCN,values-zh-rTW}/strings_oc_i18n.xml` —
  regenerated (1870 / 1411 / 1411 strings = v2 keys + 156 flagged shim entries;
  0 conflicts, 0 dupes, 0 missing-vs-source; resource merge GREEN).
- `_upstream/packages/app/dist/` — v2 production build output (build artifact only).
