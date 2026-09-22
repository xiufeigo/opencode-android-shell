# Wave 6 v1 smoke — Tablet (emulator-5556) × v1 protocol (localhost:4096)

Date: 2026-09-22. App: `com.xiufeigo.opencodeshell` (fresh build, verification only — no source touched).
Server: local v1 (serve 1.18.31, bare-array `GET /session`, healthy). Model: `opencode-go/muse-spark-1.3-contributor`.
Shots: `research/wave6_shots/tablet_v1smoke_*.png`.
Scope: regression smoke of the v1 path after today's shared-code fixes (session-missing 404 circuit breaker, event-stream HTTP error hook, snapshot liveness reconcile).

## Checklist

1. **CONNECT (v1)** — PASS. App launched attached to v1 server (localhost:4096, adb reverse); opened an existing session, navigated back to the session list which rendered the v1 server's sessions (grouped 今天/昨天/更早, project `System32`). Server `GET /session` returns the v1 bare array.
   Evidence: `tablet_v1smoke_01_launch.png`, `tablet_v1smoke_01_home.png`.

2. **TWO TURNS (same session)** — PASS. New session via `+`
   (`ses_f37922d37ffehA3ypMCUo09s88`, auto-titled `Requesting sky is blue reply`).
   - Turn 1: `reply with exactly: the sky is blue.` → assistant `the sky is blue.` rendered exactly once, streamed (stop button visible during turn), footer `Build · muse-spark-1.3-contributor · 3 秒`.
   - Turn 2 (same session): `reply with exactly: and so is the ocean.` → assistant `and so is the ocean.` rendered exactly once, footer `Build · muse-spark-1.3-contributor · 6 秒`.
   - Server API `/session/…/message` = exactly 4 messages (user/assistant ×2), texts byte-exact, no duplicates. No 404s / circuit-breaker trips observed; event stream delivered both turns.
   Evidence: `tablet_v1smoke_02_newsession.png`, `tablet_v1smoke_04_turn1_done.png`, `tablet_v1smoke_05_turn2_done.png`.

3. **MENU GATING (v1)** — PASS. ⋮ overflow menu on the open session shows the full v1 set (8 rows): 重命名 / 导出会话 / 删除 / 撤销 / 重做(disabled) / 分叉会话 / **分享会话 (Share)** / **归档会话 (Archive)** — Share and Archive present as required for v1 (hidden only on v2). Confirmed both visually and via uiautomator bounds dump.
   Evidence: `tablet_v1smoke_06_menu.png`.

4. **SESSION DELETE** — PASS (with observation). ⋮ → 删除 → confirm dialog
   `删除会话？ 将删除「Requesting sky is blue reply」及其全部消息，且无法撤销。` → 删除. Session removed server-side (`GET /session` 18 → 17, title gone) and app returned to Home; the session is absent from the list.
   Observation (follow-up worthy): after the tab closes, the workspace shows an empty "no tabs" frame (blank content area) for tens of seconds before the session list renders again; tapping the ⧉ (home) button restored the list (~40 s here, ~10–15 s on an earlier delete in this run). Not a functional failure (list + server state both correct), but slower than wave4's immediate fallback — possibly related to today's snapshot-liveness reconcile changes.
   Evidence: `tablet_v1smoke_07_delete_confirm.png`, `tablet_v1smoke_08_deleted_home.png`.

## Verdict

| # | Check | Result |
|---|-------|--------|
| 1 | Connect to v1 + session list | PASS |
| 2 | Two turns (exact text ×1 each, streaming, model footers) | PASS |
| 3 | v1 menu includes Share + Archive | PASS |
| 4 | In-app session delete → Home, list updated | PASS (observation: delayed Home re-render after delete) |

Overall: **v1 path did NOT regress** on these checks.

## Notes / test-harness observations (not app defects)

- Gboard on the emulator auto-engages pinyin for the WebView composer (mangles `adb shell input text`); worked around via the IME pill language menu → English (US) before each prompt, verifying composer text before send.
- The app UI on this emulator lags screenshot/tap feedback by many seconds (frame gaps up to minutes in `EGL_emulation` stats while backgrounded); taps on popup menus/dialogs occasionally need a repeat. All verdicts above were cross-checked against the v1 server API, not screenshots alone.
