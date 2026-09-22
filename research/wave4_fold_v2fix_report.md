# Wave 4 re-verification — Fold (emulator-5554) × v2 serve (4095), post-fix build

Date: 2026-09-22. Device: emulator-5554, inner display 2076×2152@390
(`wm size` confirmed 2076×2152 — no `device_state` switch needed).
App: `com.xiufeigo.opencodeshell/.OcActivity` v1.4.0 (fresh build, lastUpdate
2026-09-22 03:26:08) with Bug #1 fix (ServerHealthProbe v2 leg), stale-tab
prune, and friendly `errorText`. Servers: v2 `http://localhost:4095` = 2.0.11,
Basic `opencode:opencode`, workdir `/home/xiufeigo`; v1 1.18.31 @ 4096.
`adb reverse tcp:4095 tcp:4096` confirmed. Model:
`opencode-go/muse-spark-1.3-contributor`. Official SPA compared in Chrome.

> HEADLINE: **Bug #1 is FIXED** — `127.0.0.1:4095` + opencode/opencode adds
> cleanly, probes HEALTHY `v2.0.11 • opencode`, and the app runs the **full v2
> path live on device for the first time**: session create, model pick,
> streaming markdown-table reply, tool call with output, form dock ask→answer
> round-trip. Queue/steer UI and abort remain the gaps (details below).

## Numbered checklist

| # | Scenario | Verdict | Evidence (research/wave4_shots/) |
|---|----------|---------|----------------------------------|
| 1 | Connect 4095 + opencode/opencode; behaves as V2 | **PASS** | Add-server dialog accepted with NO error (`fold_v2fix_03_formfilled.png`); new row `127.0.0.1:4095 / v2.0.11 • opencode`, green dot (`fold_v2fix_04_server_added.png`); row ⋮ → Set as default → Default badge MOVED 4096→4095 (`fold_v2fix_07_switched_v2.png` → `fold_v2fix_10_default_v2.png`); post-switch Home shows live v2 data (`fold_v2fix_11_v2_home.png`, `fold_v2fix_22_home_v2_only.png`) |
| 2 | New session + model pick + markdown-table prompt, streaming render | **PASS** | Session created (`fold_v2fix_12_new_session_v2.png`); OpenCode Go group lists target model (`fold_v2fix_13_modelpicker_v2.png`); chip set (`fold_v2fix_14_model_selected.png`); prompt sent (`fold_v2fix_15/16_*`); reply in ~5 s: user bubble + real 3-row table (Python/TypeScript/Rust) + sentence + `Build · muse-spark-1.3-contributor · 5s` footer + copy buttons (`fold_v2fix_17_v2_table_reply.png`) |
| 3 | Queue/steer while running: chip + panel; steer (PATCH inbox); remove (DELETE inbox) | **PARTIAL** | Two sends 60–90 s apart both executed (no overlapping turn achieved); NO Steer/Queue chip or queue panel ever appeared. Observed instead: stale-turn state — composer cleared but turn ran headless (`fold_v2fix_24_second_send_stuck.png`); correct tap at [1993,2001] delivered `also tell me a joke about robots` which ran to a full reply (`fold_v2fix_30_queued_joke_reply.png`). HTTP layer: admitted `delivery:queue` prompt executed immediately (inbox `[]`); `PATCH …/inbox/{real-or-bogus} {delivery:steer}` → 409 `ConflictError "Pending input cannot change to steer: …"`; `DELETE` → 204. Steer/cancel of a genuinely queued item still never observed live |
| 4a | Tool card + permission for `ls -la` on v2 | **PASS** (no permission dock appeared — auto-allow path) | `run the shell command ls and show me the output` → `Listing directory contents with ls` + `Used 1 Shell` tool card, real `/home/xiufeigo` listing in output block, `Build · muse-spark-1.3-contributor · 2s` (`fold_v2fix_41/42/43/44_toolcard*.png`). Server shape confirmed: `{type:shell, command, status:exited, exit:0, output:{output}}`. No permission dock ever surfaced on v2 in this run (nothing to approve) |
| 4b | Abort a long turn on v2 | **NOT-OBSERVABLE** | Ocean-voyage turn finished before abort UI could be used; while running, NO Stop control existed in the hierarchy (polls for `Stop`/`Send` content-desc returned only `Send`; screenshot `fold_v2fix_47_running_turn.png` shows composer, no stop affordance). v2 interrupt semantics (200 `{"interrupted":bool}`) not exercised live |
| 5a | Old Bug #3 (send silently drops) | **AUTOMATION NOISE, not an app bug** | First send tap at [1993,1280] missed the real Send affordance ([1974,1982][2013,2021], parent [1935,1943][2052,2060]) — text stayed. Correct-coordinate tap delivered. Same for the joke follow-up: 3 taps at the stale y=1820 (keyboard-visible layout) missed; tap at y=2001 (keyboard-hidden layout) sent. Every correctly-aimed send produced a server turn |
| 5b | Old Bug #4 (Rename shows no dialog) | **NOT A BUG — inline edit confirmed** | ⋮ → Rename turns the header title into a focused EditText with full text selected (`fold_v2fix_19_rename_inline.png`); Enter committed — header became `New session` (`fold_v2fix_21_rename_saved.png`). (Edit-field typing via `input text` was fiddly — Gboard autocorrect fragments — cosmetic only; commit path works) |
| 6 | Generation gating: Share/Archive absent on v2 | **FAIL (regression vs code expectation)** | v2 session ⋮ menu shows Rename / Export session / Delete / Undo / Redo / **Fork session** (`fold_v2fix_45_overflow_menu_v2.png`; also `fold_v2fix_18_overflow_menu_v2.png` with Share+Archive). Row exists in `SessionActionsMenuItem` but NO `shareVisible/archiveVisible` gate is applied at this callsite — the `SessionHeader.kt:485,501` gating verified by grep in the prior run is NOT in this build's active menu path (or the menu is built from a different model). Installed 1.4.0, lastUpdate 03:26:08 — needs dev follow-up, exact build + screenshots captured |
| 7 | Rename/delete on v2 via UI; server state | **PASS (server) / PARTIAL (UI)** | UI rename commit path works (see 5b); server rename/delete via curl PASS (PATCH → 204, title round-trip `v2fix-toolcard-renamed`, DELETE → 204, list 3→2). UI delete row not tapped (row must be preserved for the report). Stale-tab-after-delete fix could not be confirmed (no UI delete performed) |
| 8 | Session list; terminal; settings; theme | **PASS** | v2 Home lists live server sessions (`fold_v2fix_31_home_3sessions.png`); v2-only empty state `Nothing here yet / Create a session to get started` (`fold_v2fix_22_home_v2_only.png`). Settings + Appearance page render (`fold_v2fix_36_appearance.png`); System/Light/Dark dropdown (`fold_v2fix_37_theme_dropdown.png`); Dark re-theme (`fold_v2fix_38_dark_theme.png`); back to Light (`fold_v2fix_39_light_theme.png`). Terminal tab visited on v1 build in prior run; not re-opened on this build (settings/terminal code untouched by the fix) |
| 9 | Side-by-side parity vs official v2 SPA | **PARTIAL** | Official Home captured (`fold_v2fix_32_chrome_official.png`): Today=`Slow Count from 1 to 30`, Yesterday=`Connect`, Older rows, search, Settings+Help — app Home mirrors all of it. BUT the official SPA **session view is broken server-side**: opening our app-created session renders `Something went wrong / BrowserAttachments context must be used within a context provider` (v2.0.10 bundle, `fold_v2fix_33_spa_session_error.png`) — so same-session view parity is unobtainable until the server bundle is fixed. No app fault |
| 10 | Form dock (v2 server-initiated forms) | **PASS — first live round-trip** | `ask me a multiple choice question about my editor preferences` → dock `Questions / Editor Preference / Which code editor… / Select one option` with VS Code/Neovim/Vim/Emacs/custom rows + Dismiss/Submit (`fold_v2fix_49_form_dock.png`); tap selects VS Code (highlighted, `fold_v2fix_50_form_selected.png`); Submit → dock resolves, model replies `Noted — you prefer VS Code for daily development.` + `Question` tool card + `Build · muse-spark-1.3-contributor · 4s` (`fold_v2fix_51_form_submitted.png`). probe→V2Transport→SSE→form-dock chain CONFIRMED live |

## Discrepancies vs official frontend

1. (high — Bug #6) v2 ⋮ menu still shows Share/Archive (and Fork); expected hidden on v2.
2. (med) Assistant text renders the SAME sentence 2–4× in a row (joke ×2 in
   `fold_v2fix_30`, `I'll ask about your editor preferences.` ×3–6 in form
   shots, `I'll run ls…` ×6 in `fold_v2fix_44`). Likely streaming
   delta+ended double-apply in the v2 adapter — needs a desktop re-check of
   whether `?after=` replay or PartDelta/ended double-commit duplicates text.
3. (low) Composer model chip `Muse Spark 1.3 Contributor` overflows its pill
   (`fold_v2fix_15_composer_prefilled.png`).
4. (low) Session-row badges/subtitles/grouping identical to prior report (letter
   chips, directory subtitles, extra Today section) — unchanged, cosmetic.
5. (info) Chrome "No internet connection" banner with working 127.0.0.1 pages —
   emulator quirk, not app.
6. (info) Official SPA session view crashes (BrowserAttachments) — server-bundle
   bug, blocks same-session parity.

## Bugs found (this build)

1. **[HIGH, NEW] Share/Archive (+Fork) visible in v2 ⋮ menu** — repro: any v2
   session → ⋮ → menu lists Rename/Export/Delete/Undo/Redo/Fork (+Share/Archive
   in `fold_v2fix_18`). Expected: hidden on V2 per generation gating.
   Evidence: `fold_v2fix_45_overflow_menu_v2.png`, `fold_v2fix_18_overflow_menu_v2.png`.
2. **[MED, NEW] Repeated assistant sentences (v2 adapter?)** — same sentence
   appended 2–6× in tool/form/joke turns. Evidence listed in Discrepancies §2.
3. **[MED, carryover#c] Friendly `errorText` fix not observable** — no error card
   appeared in any v2 flow this run, so the `_tag`/`message` extraction change
   could not be verified live (unit-pinned per fix notes).
4. **[LOW] Send affordance is a tiny target** ([1974,1982][2013,2021],
   parent [1935,1943][2052,2060]); taps at remembered coordinates miss after
   keyboard layout shifts. Consider enlarging the hit area.
5. **[ENV]** `screencap -p` + `exec-out` redirect still corrupts in Git Bash —
   used `screencap -p /sdcard/x` + `pull` throughout (with `MSYS_NO_PATHCONV=1`).

## Wave-4 risk items resolved/confirmed

- **Bug #1 fix: CONFIRMED** — auth-walled v2 adds cleanly, HEALTHY `v2.0.11 •
  opencode`, set-as-default switches the active server, Home shows live v2 data.
- **probe→V2Transport→SSE adapter→form dock: CONFIRMED LIVE** (table stream,
  shell tool call, form ask→answer round-trip).
- **inbox PATCH/DELETE: route+auth CONFIRMED** (409-structured / 204), but a
  genuinely-queued item + chip/panel UI path is still never-verified-live —
  the model answers too fast to overlap; a slower/deferred turn is needed.
- **Abort on v2: NOT verified** — no Stop affordance exists while a turn runs.
- **Stale-tab prune: not verified** (no UI delete performed).
- Cleanup: curl-created `v2fix-toolcard` session renamed+DELETEd (v2 list 3→2:
  `Slow Count from 1 to 30`, `Connect`). App-created v2 sessions (`Programming
  languages typing discipline table`, `Slow Count from 1 to 30`, `Listing
  directory contents with ls`, ocean story) left intact.

Screenshots: 52 files, `research/wave4_shots/fold_v2fix_*.png`.
