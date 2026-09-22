# Wave 4 acceptance — Fold (emulator-5554) × v2 serve (4095) report

Date: 2026-09-22. Device: emulator-5554 (Pixel Fold; inner 2076×2152 early in run,
outer 1080×2364@390 after a mid-run emulator crash/reboot — screenshots after the
reboot use `-d 4619827551948147201`). App: `com.xiufeigo.opencodeshell/.OcActivity`,
fresh `app-debug.apk` reinstalled after reboot. Servers (host-verified via curl):
v2 `http://localhost:4095` = 2.0.11, HTTP Basic `opencode:opencode`, workdir
`/home/xiufeigo`; v1 `http://localhost:4096` = 1.18.31, no auth.
`adb reverse tcp:4095 tcp:4096` confirmed on 5554.
Test model on v2: `opencode-go/muse-spark-1.3-contributor` (active).
Official v2 SPA compared in emulator Chrome (`fold_v2_09_official.png`).

> HEADLINE: the in-app **v2 path was never exercised**, blocked by Bug #1
> (connect screen rejects auth-walled v2 servers). Scenarios 2–5, 10 ran on the
> **V1 transport** instead (proven: overflow menu showed Share/Archive =
> generation V1; the app's session landed in the v1 `GET /session` list).
> V2 behavior below is verified at the HTTP layer (curl) + code inspection
> (read-only greps), NOT live in-app. This is stated per-scenario — nothing is
> faked.

## Numbered checklist

| # | Scenario | Verdict | Evidence (research/wave4_shots/) |
|---|----------|---------|----------------------------------|
| 1 | Connect `localhost:4095` + opencode/opencode, behaves as V2 | **FAIL** (blocking bug) | `fold_v2_30_welcome.png`, `fold_v2_31_welcome_filled.png`, `fold_v2_33_connect_error.png` — red "Something answered, but it is not an OpenCode server." Same failure in settings add-server dialog: `fold_v2_04_addserver.png`, `fold_v2_07_probefail.png` ("有服务响应，但它不是 OpenCode 服务器") |
| 2a | New session + model pick `opencode-go/muse-spark-1.3-contributor` | **PASS (on v1)** | `fold_v2_10_newsession.png`, `fold_v2_11_modelpicker.png` (OpenCode Go group lists the model), `fold_v2_12_modelselected.png` (chip shows it) |
| 2b | Streaming prompt; table renders as real table, not raw pipes | **PASS (on v1)** | Sent `reply with a short markdown table…` (`fold_v2_13_composer_text.png`); reply in ~6–8 s: user bubble + 3-row table (Language/Typing Discipline; Python/TypeScript/Rust) + one sentence + `Build · muse-spark-1.3-contributor · 6 秒` footer + copy buttons: `fold_v2_14_reply_table.png`, `fold_v2_15_replyfull.png`. agent-18 zero-width table fix CONFIRMED on device |
| 3 | Queue/steer while running: Steer/Queue chip + queue panel; steer + remove queued item | **FAIL (app) / PARTIAL (HTTP)** | App: second send never started a turn — composer retained text, no user bubble, no status, no chip/panel (`fold_v2_16→21_*`). HTTP layer: `delivery:"queue"` prompt admitted then executed immediately (inbox stayed `[]`); `PATCH …/inbox/msg_bogus {delivery:steer}` → 409 `ConflictError "Pending input cannot change to steer: msg_bogus"` (route+auth OK); `DELETE …/inbox/msg_bogus` → 204. True steer/cancel of a live queued item never observed |
| 4 | Tool card + permission for `ls -la` | **FAIL (app) / PASS (HTTP)** | App: shell prompt never left the composer (same send failure as #3); no permission dock ever appeared. HTTP layer: `POST …/session/{id}/shell {command:"ls -la"}` → 204; message back is `{type:shell, command, status:exited, exit:0, output:{output:"total 1565980…"}}` — the exact shape ToolCards render |
| 5 | Abort a long turn via stop control | **NOT-OBSERVABLE** | No running turn ever existed in-app to abort. HTTP note: `POST …/session/{id}/abort` → 404 on v2 (v2 interrupt uses another route; V2Api's interrupt path not exercised) |
| 6 | Generation gating: Share/Archive ABSENT on v2 (session ⋮ + palette) | **NOT-OBSERVABLE live / PASS by code + v1 control** | The only connected generation was V1, where Share/Archive CORRECTLY appear (`fold_v2_41_v1_menu.png`). Code (read-only grep): `SessionHeader.kt:485,501` wraps SHARE/ARCHIVE rows in `if (actionsMenu.shareVisible/archiveVisible)` — i.e. the wave2.4a-notes §4 follow-up **is landed**; `App.kt:1326-1334` null-gates palette `onArchive` for non-V1. Runtime v2 behavior unverified (blocked by #1) |
| 7 | Rename + delete session via UI; server state changes | **PARTIAL** | In-app rename row tap produced no visible dialog on 2 attempts (`fold_v2_22_overflow_menu.png`, `fold_v2_24_menu2.png`, `fold_v2_23_rename.png`, `fold_v2_25_rename_dialog.png` show no dialog; a `wave4rename` row exists in Home but is unattributable — second agent shares the servers). Delete via UI not attempted. Server-side on v2 via curl: PASS — create → `PATCH title` → 204 → GET shows `wave4-renamed-ok` → `DELETE` → 204 → list count back to 1 |
| 8 | Terminal tab, settings screen, dark/light toggle | **PASS** | Terminal tab renders local shell `PS C:\Windows\System32>` + cursor, `Terminal 1` tab, +/close controls (`fold_v2_42_terminal.png`). Settings renders General/Appearance/Notifications/Shortcuts + pages (`fold_v2_44_settings_en.png`, `fold_v2_45_appearance.png`). Color-scheme dropdown System/Light/Dark (`fold_v2_48_theme_dropdown.png`); Dark fully re-themes (`fold_v2_49_dark_theme.png`); back to Light works (`fold_v2_50_light_theme.png`) |
| 9 | Side-by-side parity vs official v2 SPA | **PARTIAL** | Official Home captured (`fold_v2_09_official.png`): Projects / Add project / Search sessions / Yesterday-Older grouping / session rows / Settings+Help — app Home mirrors all of these (`fold_v2_01_launch.png`, `fold_v2_54_final_home_v1.png`). Same-session view comparison NOT done (no shared v2 session possible). Discrepancy list below |
| 10 | Form dock (v2 server-initiated forms) | **NOT-OBSERVABLE** | `ask me a multiple choice question…` sent on the v1 path → `HTTP 404 {"name":"NotFoundError","data":{"message":"Session not found: ses_f391…"}}` error card (`fold_v2_51_v1_error.png`, `fold_v2_52_error_expanded.png`). No form ever pended; no dock observed. Form code exists per wave2.4a notes (not re-verified live) |

Bonus (v1 control, not in script): full send→stream→reply E2E on v1 **PASSES** —
user bubble + timestamp, `Hello, nice to meet you.`, `Build · gpt-5.6-sol-fast · 32s`
footer, copy actions (`fold_v2_43_v1_streaming_reply.png`).

## Discrepancies vs official frontend (app Home vs v2 SPA Home)

1. (low) Session-row badges: app uses generic letter chips (S/D/M/W); official uses
   distinct per-item icons. Cosmetic.
2. (low) Session-row subtitle: app shows directory/project (`System32`,
   `DSH 临时会话`); official shows title + subpath. Same info, different order.
3. (low) Official groups Yesterday/Older; app adds a `今天 Today` section — superset,
   fine.
4. (low) App session view has extra header tabs (Changes/Files/Terminal/⋯) the SPA
   Home has no equivalent of — intended native extension, not a regression.
5. (info) Chrome on this emulator shows a "No internet connection" banner while
   `127.0.0.1` reverse-tunnel pages load fine (`fold_v2_37_chrome_health.png`) —
   emulator connectivity-check quirk, not an app bug.
6. Session-view parity (bubbles, colors, type, spacing, tool cards) NOT compared —
   blocked by #1. Table render matches expectation (real grid, no raw pipes).

## Bugs found

1. **[BLOCKING] First-run connect screen rejects auth-walled v2 servers.**
   Repro: fresh install (or `pm clear`) → Server address `http://127.0.0.1:4095`,
   Password `opencode` → Connect → red `Something answered, but it is not an
   OpenCode server.` (`fold_v2_33_connect_error.png`). Root cause (code read):
   `ui/servers/ServerHealth.kt` probes only `GET /global/health`; on v2+auth that
   returns 200 SPA HTML (auth accepted) → `healthBody` → `InvalidResponse` →
   `oc_connect_error_invalid_response`. `V2Capability.probe` (`GET /api/info` →
   V2 on JSON `version:"2.x"`, Basic auth attached) is never consulted by the
   connect flow, so NO v2+auth server can ever be added. curl proof: authed
   `/api/info` → 200 `{"version":"2.0.11",…}`; authed `/global/health` → 200 HTML.
   Fix suggestion: when the health probe yields InvalidResponse/Unauthorized,
   fall back to `V2Capability.probeUrl(url, user, pass)` and accept a V2 verdict.
2. **[HIGH] Same probe failure in settings add-server dialog**
   (`fold_v2_07_probefail.png`). Same root cause as #1 (via SettingsServerApi).
3. **[HIGH] Composer send silently does nothing (message loss, no feedback).**
   Repro: in an app-created session, type a second message after the previous
   turn completed, tap send → composer keeps the text, no user bubble, no
   status, no error (`fold_v2_18_stuck.png` → `fold_v2_21_nostart.png`). First
   send in the same session worked. No app-tag logcat lines exist to diagnose
   (logcat shows only system noise; PID filter returns nothing — consider
   adding debug logging or a different log tag). Possibly stale-session send;
   the later stale-id send DID surface an error card, so this earlier silent
   case is a distinct path worth tracing.
4. **[MED] Session ⋮ → 重命名 row tap shows no dialog** (2 attempts,
   `fold_v2_23_rename.png`, `fold_v2_25_rename_dialog.png`). Rename on v1 may
   still work (unconfirmed); v2 rename API itself is fine (204, see #7).
5. **[LOW] Raw JSON leaks into the error card**: `HTTP 404 {"name":
   "NotFoundError","data":{"message":"Session not found: ses_…"}}`
   (`fold_v2_51/52_*`). Suggest a friendly sentence + collapsing details.
6. **[ENV, not a bug] Emulator crashed mid-run** (`device offline`, empty
   `adb devices`); relaunched with `-avd Pixel_10_Pro_Fold -no-snapshot
   -wipe-data`, which came up on the OUTER display (1080×2364@390) instead of
   the inner Fold display — screenshots differ in size accordingly; inner shots
   need `screencap -d 4619827551948147201`. Also: `screencap -p` + `exec-out`
   redirect produces corrupt PNGs in Git Bash; reliable form is
   `screencap -p /sdcard/x.png` + `pull` (with `MSYS_NO_PATHCONV=1`).
   `input text` needs Latin IME (`ime set …LatinIME`) + word-by-word typing;
   ENTER sends from the composer (matches official).

## Wave-4 risk items resolved/confirmed

- **probe → V2Transport → SSE adapter → form dock chain: NOT confirmed live
  in-app** (blocked by Bug #1). Unit coverage stands per wave notes
  (1190+ green). Presence of every link verified read-only in code:
  `ChatViewModel.steerQueued/cancelQueued/confirmQueueEdit` (:1484–1642),
  `V2Transport.updateInboxDelivery/cancelInbox` (:146–155),
  `V2Api.updateInboxDelivery/cancelInbox` (:787–803),
  `SessionScreen` queue wiring (:763–766), `SessionHeader` share/archive gates
  (:485–507), `App.kt` palette `onArchive` null-gate (:1326–1334).
- **inbox PATCH/DELETE live behavior: route+auth CONFIRMED**
  (`PATCH …/inbox/{bogus}` → 409 structured `ConflictError`; `DELETE` → 204),
  but steering/cancelling a REAL queued item never observed — `delivery:queue`
  prompts executed immediately in these tests (inbox stayed `[]`), so the
  queue panel + `delivery.changed` confirm path remain never-verified-live.
- **Generation gating: code LANDED, runtime on v2 unverified.** v1 control
  behaves correctly (Share/Archive visible).
- **Table rendering (agent-18 zero-width fix): CONFIRMED fixed on device** —
  real 3-row grid renders (`fold_v2_14_reply_table.png`).
- **Server-side session rename/delete on v2: PASS** (204/204, title round-trip
  verified, list count restored). In-app equivalents: not verified.
- Cleanup: all throwaway v2 sessions created by this run were DELETEd
  (v2 list back to 1, the pre-existing `Connect` session). v1 gained
  app-created sessions (`run the shell command…`, `wave4rename`(unattributed),
  `New session - 2026-09-22T01:42…`); left intact for the tablet agent.

Screenshots: 51 files, `research/wave4_shots/fold_v2_*.png` (this session:
`fold_v2_30`→`fold_v2_54` plus pre-reboot `fold_v2_01`→`fold_v2_27`).
