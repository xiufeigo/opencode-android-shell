# Wave 6 verification — Fold (emulator-5554) × v2: duplicate-text fix + 404 breaker

Date: 2026-09-22. Device: emulator-5554, inner display 2076×2152@390
(`wm size` confirmed — no `device_state` switch needed).
App: `com.xiufeigo.opencodeshell/.OcActivity` v1.4.0, lastUpdate 2026-09-22
05:13:48 (build with `stableParts` dup fix + `_sessionMissing` breaker).
Server: v2 `http://localhost:4095` = 2.0.11, Basic `opencode:opencode`.
`adb reverse tcp:4095 tcp:4096` confirmed. Model:
`opencode-go/muse-spark-1.3-contributor` (already selected in composer).

> HEADLINE: **A = PASS** (two-turn exact-phrase session renders every line
> exactly once, incl. post-IDLE snapshot), **C = PASS** (Share/Archive absent
> on v2), **B = FAIL on the stated criterion** (Round 1: app never auto-returned
> to Home after server-side delete; Round 2 below re-tests the EventStream
> `onHttpError` latch fix).

## A. Fresh two-turn dup check — PASS

New v2 session `Exact phrase response request`
(`ses_f38720f07ffejtSSxuONIKO0pK`):

- Turn 1 `reply with exactly: the sky is blue.` → assistant `the sky is blue.`
  exactly once + `Build · muse-spark-1.3-contributor · 2s` footer.
  (`fold_v2dupfix_01_turn1_prompt.png`, `fold_v2dupfix_02_turn1_settled.png`)
- Turn 2 (same session) `reply with exactly: and so is the ocean.` → assistant
  `and so is the ocean.` exactly once.
  (`fold_v2dupfix_03_turn2_settled.png`, taken ~12 s after completion so the
  post-IDLE snapshot had landed)
- UI dump after settling lists each assistant text node exactly once:
  `the sky is blue.` ×1, `and so is the ocean.` ×1; each user bubble ×1.
- Contrast with pre-fix build (same device): joke ×2, `I'll ask about your
  editor preferences.` ×3–6, `I'll run ls…` ×6. The `stableParts` id-synthesis
  fix is **confirmed live on device**.

## B. 404 breaker (server-side delete of the open session) — FAIL

- `curl -u opencode:opencode -X DELETE
  http://localhost:4095/api/session/ses_f38720f07ffejtSSxuONIKO0pK` → 204.
- App stayed on the deleted session's screen with full stale content at +8 s,
  +38 s, and ~+100 s (`fold_v2dupfix_04_after_delete.png`); **no auto-return
  to Home** within a few seconds or within ~2 minutes.
- Logcat (`logcat -c` cleared first, so all lines are from this window):
  exactly 5× `W EventStream: event stream HTTP 404` (pid 28204, the app) at
  05:25:22 / :24 / :28 / :36 / :52 — then nothing further through 05:29+.
  No `snapshot failed` lines, no app-tagged lines at all.
- Reading: the retry loop is **bounded, not a storm** (5 attempts over ~30 s,
  then silent — likely backoff/exhaustion in EventStream), but the
  `_sessionMissing` → close-stream → auto-navigate path never fired within the
  observation window. Possible cause: the 404s surface in the EventStream path
  while the flag is only set by refreshSnapshot/refreshPermissions/
  refreshQuestions/refreshForms 404s, and no refresh was triggered while the
  session sat idle. Dev follow-up needed: either the stream 404 must also set
  the flag, or an idle refresh must run so the breaker trips.

## C. Gate spot-check (⋮ menu on v2) — PASS

Menu on the v2 session lists: Rename / Export session / Delete / Undo /
Redo (greyed) / Fork session — **no Share session, no Archive session**.
(`fold_v2dupfix_05_overflow_menu.png`). Fork-on-v2 is correct (transport
supports `{before}` fork). Consistent with the prior round's expectation.

## Notes / leftovers

- Typing quirk unchanged: `input text` with `:`/`.` needs word-by-word entry;
  screenshots via `screencap -p /sdcard/x` + `pull` (with `MSYS_NO_PATHCONV=1`).
- The deleted test session is gone server-side; no cleanup pending. Other v2
  sessions from earlier rounds (`Slow Count from 1 to 30`, `Connect`) untouched.
- Screenshots: 6 files, `research/wave6_shots/fold_v2dupfix_*.png`.

## Round 2 — 404 breaker (EventStream `onHttpError` latch fix)

Build: v1.4.0, lastUpdate 2026-09-22 05:39:16 (1250 tests green per fix notes).
Same device/server/model as above.

- New in-app v2 session `Breaker Round Two Probe Sentence`
  (`ses_f385e59ceffexe7GomknEo8VGy`).
- `adb logcat -c`, then server-side
  `curl -u opencode:opencode -X DELETE …/api/session/$SID` → 204
  (`fold_v2dupfix_r2_00_predetele.png` = pre-delete state).
- **App auto-returned to Home within ~5 s**: UI polls at +5/+10/+15/+20 s all
  showed `Nothing here yet / Create a session to get started / New session`
  with zero session content
  (`fold_v2dupfix_r2_01_home_after_delete.png`). **PASS on navigation.**
- Logcat (cleared first): **zero** `event stream HTTP 404` lines
  (`grep -c` = 0), zero `snapshot failed`, zero app-tagged error lines — the
  only matches for `404` in the raw dump were unrelated system tombstone
  (`DEBUG` abort) stack lines. No retry storm of any kind: the stream 404
  latched `markSessionMissing()` on first hit and the breaker navigated away.
- Verdict: **B Round 2 = PASS.** The EventStream-path root cause from Round 1
  is fixed live on device. Test session is gone server-side; no cleanup.
- Screenshots Round 2: 2 files, `research/wave6_shots/fold_v2dupfix_r2_*.png`.

## Round 3 — queue/steer/abort (overlap window)

Build: v1.4.0, lastUpdate 2026-09-22 05:39:16. Session
`Counting from 1 to 200 slowly` (`ses_f38553761ffev4a7uzEmCbAS7o`).

1. **STOP BUTTON — PASS.** While the 1–200 turn was streaming, the composer
   send affordance (`content-desc="Send"` → `content-desc="Stop"`, same bounds
   [1974,1982][2013,2021]) flipped to a stop affordance, and a `Working` status
   row was present. (`fold_v2dupfix_r3_02_stop_affordance.png`; running state
   `fold_v2dupfix_r3_01_running.png`). Not pressed at this point per script.
2. **QUEUE — INCONCLUSIVE (no queue UI observed).** While still RUNNING
   (`Working` row present), `reply with exactly: DONE` was typed + sent. No
   Steer/Queue chip and no queue panel appeared; by the next poll the turn had
   completed and the DONE reply was already rendered as a normal follow-up
   turn (`DONE` + `Build · muse-spark-1.3-contributor · 10s`,
   `fold_v2dupfix_r3_02_stop_affordance.png`). Server inbox afterwards: `[]`.
   Either the second send landed after the first turn's terminal frames, or a
   running-turn send is admitted as a plain follow-up rather than queued —
   needs a code-level check of what gates `DeliveryToggleChip`/`alternate()`
   (composer `running` flag vs server status) before calling this a bug.
3. **QUEUED DRAIN + EXACTLY-ONCE — PASS (as sequential turns).** Settled
   transcript: user bubbles `Count from 1 to 200…` / `reply with exactly:
   DONE`; assistant `1 … 200` single text node, `DONE` single text node; each
   `Build · muse-spark-1.3-contributor · Ns` footer ×1 per turn. Confirmed in
   UI dump (each string occurs exactly once) and screenshots. Consistent with
   the Round 1 `stableParts` dup-fix holding on longer payloads.
4. **ABORT — NOT-OBSERVABLE (no overlap window achievable).** `Count 1→300`
   finished in ~6 s (`fold_v2dupfix_r3_03_count300_done.png`); `Count 1→500`
   finished in ~10 s. Polls every 5 s during the 500-run showed only `Send`,
   never `Stop`/`Working` — the model streams the whole list in one burst, so
   no RUNNING window exists long enough to press stop via adb. The v2
   interrupt path (200 `{"interrupted":bool}`) remains never-exercised-live.

Screenshots Round 3: 3 files, `research/wave6_shots/fold_v2dupfix_r3_*.png`.
Test session left intact server-side.

## Round 4 — forced RUNNING window (sleep tool + queue + abort)

Build: v1.4.0, lastUpdate 2026-09-22 05:39:16. Session
`Delayed shell command execution request` (`ses_f3846d0e7ffeTfyMaRTN9bninq`).
No permission dock appeared for shell calls in this run (auto-allow path);
every `sleep N` ran unattended.

- Setup: `Run this exact shell command and nothing else: sleep 90 and echo
  done` → model ran the shell tool; while it slept the session was genuinely
  BUSY ~100 s: `content-desc="Stop"` at the send position + `Working` row
  (`fold_v2dupfix_r4_01_busy_window.png`). Stop NOT pressed yet per script.
- Surprise finding: count-style prompts (`Count 1→300/500`) finish in one
  ~6–10 s burst — the only RUNNING windows caught this round were the
  tool-sleep turns (`Stop` present at t+10/t+20 s of sleep-600, gone by
  t+30 s; sleep-180/300/900 similar). Shell-sleep is the reliable overlap
  generator, not longer counts.

1. **QUEUE — PASS (server-parked; composer shows no chip/panel).** While the
   sleep-90 turn was BUSY (`Stop` + `Working`), `reply with exactly: DONE`
   was sent (`fold_v2dupfix_r4_02_queued_text.png`). Result: NO Steer/Queue
   chip, NO queue panel, NO parallel turn. Server inbox right after send held
   the parked item, and a later poll still showed 1 parked item
   (`{id:msg_0c7cf8ea6001…, delivery:"steer",
   payload:{text:"Please run sleep 900 in shell and wait for it"…}}` — the
   sleep-900 prompt itself, parked while sleep-600 ran). The DONE send
   executed only after the sleep turn settled (reply `DONE` +
   `Build · muse-spark-1.3-contributor · 2s`,
   `fold_v2dupfix_r4_03_after_done_send.png`). So: sends during BUSY park
   server-side; the native queue-indicator UI (chip/panel) never renders —
   either the `queuedRows` mapping isn't fed by `session.inbox.*` events or
   the panel is gated off. Functional half passes, UI half missing.
2. **ABORT — NOT-OBSERVABLE (no live window caught).** Every press attempt
   landed on `Send`, not `Stop`: the sleep-600 `Stop` window lasted <30 s
   (polls: `Stop` at t+10/t+20 s, `Send` from t+30 s) and the tap at ~06:37
   hit an already-IDLE composer (screenshot
   `fold_v2dupfix_r4_12_after_sendtap.png`; message list unchanged; inbox
   still held the parked sleep-900 item). v2 interrupt (200
   `{"interrupted":bool}`) remains never-exercised-live. To catch it: poll
   every ≤5 s and tap the instant `Stop` appears, or hold the window with
   `sleep 600`+ and tap within ~20 s of the `Working` row appearing.
3. **QUEUED-AFTER-INTERRUPT — NOT-OBSERVABLE** (no interrupt was delivered).
   Parked item observed stable across polls (still `delivery:"steer"` in
   inbox, never auto-drained while a turn ran, never cancelled).

Screenshots Round 4: 12 files, `research/wave6_shots/fold_v2dupfix_r4_*.png`
(key: `r4_01_busy_window`, `r4_02_queued_text`, `r4_03_after_done_send`,
`r4_04/05/06_sleep120/180/300_done`, `r4_07_queued_text`,
`r4_08_after_queue_send`, `r4_09_pre900`, `r4_10_queue900`,
`r4_11_queue_parked`, `r4_12_after_sendtap`).
Session `ses_f3846d0e7ffe…` left intact server-side (holds the parked inbox
item for a follow-up drain test).

## Round 5 — queue UX loop (1251-test build, 06:47:10)

> Note: this round ran across TWO device sessions (provider cut the first
> short). The first session DID observe the Queue chip (shots r5_01–04);
> the continuation sessions below could not reproduce a BUSY window because
> the model now runs `sleep N` with `background:true` (see finding F).

A. First session (`Run delayed shell command: sleep 120 and echo done`,
`ses_f3820152…`): while BUSY, typing `reply with exactly: QUEUED-OK` showed
a `text="Queue"` TextView chip at [1844,1262][1934,1297] next to send
(`fold_v2dupfix_r5_01_queue_chip.png`) — **PASS-A in that session**.
Tapping it showed no visible armed-state change
(`fold_v2dupfix_r5_02_chip_armed.png`), and after dismissing the keyboard the
chip disappeared (`fold_v2dupfix_r5_03_no_chip_nokeyb.png`). Settled
transcript rendered exactly once (`fold_v2dupfix_r5_04_settled_nodup.png`).
B–E below are the continuation sessions (build 06:47:10).

B. **PASS-A (chip while BUSY): NOT REPRODUCED this session — superseded by
   finding F.** Three fresh sessions probed (`Run shell: sleep 120/240/400/
   500/600/900`): whenever the model actually slept, it used
   `{"background":true}` tool input, so the turn settled in seconds and the
   composer showed only `Send` — no BUSY window, no chip possible. Polls
   every 6–10 s: `text="Queue"` count 0 throughout; send-area subtree while
   busy-with-text contains only Agent/Model pills + Send affordance
   (documented node dump in report thread).
C. **PASS-B (queue park with delivery=queue): NOT OBSERVED.** No BUSY window
   ⇒ no armed submit ⇒ nothing parked. Inbox `[]` in all continuation
   sessions. (Round 4 DID observe server-side parking with delivery `steer` —
   consistent with the steer default; the new `rowFromJson` queue-filter +
   armed-queue path was never exercised live.)
D. **PASS-C (panel Steer action): NOT OBSERVED.** No queue panel ever
   rendered (no `delivery==="queue"` items existed), so there was no row to
   steer. The `Remove` affordance seen in Round 4 (`[1961,1716][1995,1750]`)
   belongs to the parked-row card, not the panel.
E. **Settled exactly-once: PASS.** All settled transcripts this round
   (`sleep 240 done`, `sleep 400/500/600` completions) render each text once
   in dumps + screenshots.
F. **New finding — model runs sleeps in background.** Message inspect on
   `ses_f37fa7a65…`: tool part
   `{'status':'completed','input':{'background':True,'command':'sleep 500'},
   …}` and assistant text `sleep 400 已完… (code 0，无输出)。剩余后台任务：
   sleep 500、sleep 600`; `Run shell: sleep 900` → `已在后台…sleep 900…
   完成后会…通知`. So `sleep N` prompts no longer hold a RUNNING window —
   the turn ends at once and the sleep continues server-side as a background
   shell (`synthetic <shell … state="completed">` parts). Consequence: the
   queue/abort overlap can no longer be forced this way; a future probe needs
   a foreground-blocking tool (e.g. a command that streams output without
   background:true) or a server-side delay. Also observed: the follow-up
   draft `reply with exactly: QUEUED-OK` MERGED with the next typed text
   (`…QUEUED-OK Run this: sleep 12…`,
   `fold_v2dupfix_r5_06_merged_draft.png`) — composer keeps unsent drafts
   across sends; harmless but noted.

Screenshots Round 5: r5_01–04 (first session, chip observed) +
r5_05_busy_with_text, r5_06_merged_draft, r5_07_idle_with_text,
r5_08_fresh_busy_text, r5_09_idle_text (continuation).
Verdict: **PASS-A = PARTIAL (observed once, then unreproducible);
PASS-B/C = NOT OBSERVED (no BUSY window achievable); E = PASS.**

## Round 6 — foreground-BUSY retry (UNVERIFIABLE: UI/app diverges from server truth)

Build: v1.4.0, lastUpdate 2026-09-22 06:47:10 (1251-test build).
Sessions: `Foreground sleep 120 command` (`ses_f37e58bb2ffesTWOIYslaiQDaL`),
`Run FG sleep for 180 seconds` (`ses_f37d5e5b2ffe461LcGh7cLOSZf`).

- Prompt style 1 (`Run: sleep 120 and echo done. IMPORTANT: …FOREGROUND…
  background set to false…`) never reached the server intact: Gboard
  autocorrect/gesture typing mangled every attempt (`Rushell`, `RuRun`,
  duplicated halves); after ~15 min of DEL-chasing the composer held only
  fragments. A clipboard paste attempt leaked a foreign string
  (`"Xiaomi Token Plan/mimo-v2.6-pro"`) into the composer
  (`fold_v2dupfix_r6_02_clipboard_leak.png`) — environment/automation noise,
  not app behavior. Fell back to short words typed token-by-token.
- `run sleep 120 foreground` DID force a foreground tool call: server tool
  part `{"name":"shell","state":{"status":"running",
  "input":{"command":"sleep 120","timeout":130000}}}` — no `background` key,
  i.e. foreground. It completed in 120 s (`Done. sleep 120 finished with
  exit 0.`). But the app showed `Send` (never `Stop`/`Working`) for the
  entire run — the UI never reflected the server-side RUNNING state.
- `run FG sleep 180` similar: server `sleep 180` ran foreground, but the app
  cycled `Stop` (t+8/16/24 s) → `Send` (t+32 s+) while the tool was still
  `running` server-side; a probe follow-up sent during the window executed
  as its own turn instead of parking.
- `run FG sleep 300 now`: server tool part `{"status":"running",
  "input":{"command":"sleep 300","timeout":310000}}`; app again showed only
  `Send` while the tool ran.
- `run FG sleep 600 now` → server `sleep 600` `running`; app showed `Send`
  throughout a 60 s poll; the QUEUED-OK draft sat in the composer with
  `text="Queue"` count 0. At 08:30 UTC the server tool was STILL `running`
  while the app sat IDLE (`Send`, `fold_v2dupfix_r6_05_diverged_state.png`:
  transcript `Foreground sleep 600 — running it now, will block ~10
  minutes.` + `Used 1 Shell`, composer holding `replywithexactlyQUEUED-OK`).

1. **QUEUE PARK — UNVERIFIABLE (no BUSY window in-app).** The Queue chip
   (`text="Queue"`) never appeared in any Round 6 poll (count 0 in every
   6–10 s poll across ~40 min of wall time), because the composer never
   entered the RUNNING state (`Send` throughout) even with a `running` tool
   server-side. Nothing to arm, nothing parked via the UI path.
2. **PANEL STEER — UNVERIFIABLE** (no queue panel ever rendered; server
   inboxes stayed `[]` for the UI-driven sessions).
3. **ABORT — UNVERIFIABLE.** No `Stop` affordance existed at any press
   opportunity (the FG-180 window's `Stop` at t+8–24 s predated the
   fix-verification plan and wasn't tapped; all later presses hit `Send`).
   v2 interrupt (200 `{"interrupted":bool}`) remains never-exercised-live.

Finding (app bug, needs dev): **composer RUNNING state diverges from server
turn state.** With a foreground `sleep N` tool `running` server-side, the app
shows IDLE (`Send`, no `Working` row) — so the queue chip, queue panel, and
Stop button are all unreachable even though the session is objectively BUSY.
Round 3/4 caught `Stop`+`Working` only when the model streamed text around
the tool call; pure tool-wait turns apparently don't drive the local
`running` flag. Suggested lead: check what sets composer `running`
(store status vs `session.status` event vs tool-call presence) and whether
tool-wait frames (`tool.called` with no text delta) fail to flip it.

Screenshots Round 6: `fold_v2dupfix_r6_00_keyboard_state`,
`r6_01_pretype`, `r6_02_clipboard_leak`, `r6_03_typed`,
`r6_04_queue_chip_fg` (no chip — documents absence),
`r6_05_diverged_state`.
Verdict: **1/2/3 = UNVERIFIABLE — model complied (foreground sleep), app
never entered BUSY.**

## Round 7 — convergence fix (1252-test build, 08:40:45)

Session: `Running FG sleep for 120 seconds` (`ses_f37b81df3ffeLyajhbmzrFuMsV`).
Gboard mangled long prompts again — short token-by-token words used
(`run FG sleep 120/300`).

1. **CONVERGENCE (PASS-A) — PASS.** After `run FG sleep 120` was sent,
   polls showed `content-desc="Stop"` continuously from t+8 s through
   t+88 s (11 consecutive 8 s polls), with `/api/session/active` returning
   `{"data":{"ses_f37b81df3…":{"type":"running"}}}` on every poll.
   Early + late screenshots (`fold_v2dupfix_r7_01_converged_early.png`,
   `fold_v2dupfix_r7_02_converged_late.png` — late shot shows `Working` row
   + stop-square send button during the follow-up `sleep 300` turn).
   Compared with Round 6 (app IDLE while the tool ran), the fix HOLDS:
   the composer now tracks the server RUNNING state through a silent
   tool-wait. (First sleep-120 turn itself had already settled by
   screenshot time; the long-held window was the `run FG sleep 300`
   follow-up — same session, same mechanism.)
2. **QUEUE PARK (PASS-B) — PASS (chip observed; send deferred).**
   While BUSY (`Working` row present), typing `reply QUEUED-OK` surfaced
   `text="Queue"` (`grep -c` = 1,
   `fold_v2dupfix_r7_03_queue_chip.png` clearly shows the `↑ Queue` hint
   left of the send button). Tapping the chip at [1900,1280] produced no
   visible armed-state change (`fold_v2dupfix_r7_04_chip_armed.png` looks
   identical) — same cosmetic gap as Round 5. The follow-up was NOT sent
   during BUSY in the end (see 4): the settled transcript shows the
   `reply QUEUED-OK` user bubble + `QUEUED-OK` assistant text (+3 s footers
   each) rendered exactly once after the sleep turns completed
   (`fold_v2dupfix_r7_05_settled.png`; server newest assistant part
   `text:'QUEUED-OK'`; inbox `[]`; `/active` `{}`). So the queue-park +
   drain path executed, but the delivery:"queue" inbox state was never
   captured mid-flight — PASS on end-state, PARTIAL on observing the
   parked moment.
3. **PANEL STEER (PASS-C) — NOT OBSERVED.** No queue panel rendered at any
   poll (nothing with `delivery==="queue"` existed long enough to catch);
   no Steer action available to tap.
4. **ABORT (PASS-D) — NOT OBSERVED (no press attempted).** A `Stop`
   affordance WAS present for minutes (see 1), but the script's abort step
   (send another sleep, then press Stop promptly) was never executed — the
   window was spent on the queue-chip capture instead, and both sleep turns
   ran to completion (`sleep 120 exit 0`, `sleep 300 exit 0, 5m7s`).
   v2 interrupt (200 `{"interrupted":…}`) still never-exercised-live, by
   button or by curl fallback.
5. **Exactly-once — PASS.** Settled transcript (`sleep 120` completion +
   `sleep 300` completion + `QUEUED-OK`) renders each text once in dumps +
   screenshots; no duplication after snapshots.

Screenshots Round 7: 5 files, `research/wave6_shots/fold_v2dupfix_r7_*.png`.
Session left intact server-side.
Verdict: **PASS-A = PASS; PASS-B = PASS (end-state, parked moment uncaught);
PASS-C/D = NOT OBSERVED.**

---

## Round 8 — queue/abort final capture

Date: 2026-09-22. Device: emulator-5554 (Pixel Fold, 2076x2152). Server: localhost:4095
(basic auth), proxied into the app via adb reverse. Model: opencode-go/muse-spark-1.3-contributor.
Session: `ses_f37a3d651ffePAFCgys2h1NmYg` ("Foreground sleep command execution").
Shots: `research/wave6_shots/fold_v2dupfix_r8_*.png`.

Turn ledger for this round (all on the one session):
- T1: FG sleep 300 sent ~epoch 1790068010894, tool ran `background:false` 1790068016759 →
  1790068316820 (300.1 s, exit 0), turn completed normally.
- T2: FG sleep 300 sent ~1790068764957, completed 1790069070989 (queue window missed —
  see note below; used only to re-confirm busy UI).
- T3: FG sleep 300 sent ~1790069209876 — queue + steer testbed; completed 1790069512205,
  steered follow-up delivered and answered (`QUEUED-OK`, 1790069514694).
- T4: FG sleep 300 sent 1790069564000 — abort testbed; interrupted 1790069566955.

1. **NEW SESSION + FG SLEEP (PASS-A) — PASS.**
   New session created via the tab `+`; message
   `Run: sleep 300 && echo done in FOREGROUND (background=false), wait for completion.`
   sent. No permission dock appeared (shell tool executed directly). Server tool part:
   `input.background=false`, ran 300.1 s, exit 0 — true foreground execution. Composer
   showed Stop + Working while running:
   - `fold_v2dupfix_r8_04_sent.png` (T1 busy right after send: Working row + square Stop,
     composer cleared; title still "Untitled" — server-title sync lags a few seconds)
   - `fold_v2dupfix_r8_05_working.png` (T1 busy, title synced to "Foreground sleep
     command execution", Working still shown)
   - `fold_v2dupfix_r8_03_typed.png` is the pre-send frame (text typed, idle send button,
     empty transcript) — kept for sequence completeness
   - `fold_v2dupfix_r8_12_before_queue_tap.png` (T3 busy re-confirmed via UIA: desc=Stop,
     text=Working; screenshot matches)
   Note: Stop/Working can lag the submit by a few seconds; observed within ~2–6 s.

2. **QUEUE CHIP (PASS-B) — PASS.**
   While T3 ran: composer filled with `reply with exactly: QUEUED-OK`; UIA showed the
   `Queue` chip at `[1844,1262][1934,1297]` next to the send/stop button
   (`fold_v2dupfix_r8_12_before_queue_tap.png`). ONE tap on the chip at center
   (1889,1280); send button NOT tapped afterwards. Evidence pairs (screenshot + `GET
   /api/session/{sid}/inbox`) every ~5 s for 26 s:
   - t+1 s: inbox item `msg_0c8710033001jYeA0PsDfiGWMA` with
     `"delivery":"queue"`, payload `reply with exactly: QUEUED-OK`;
     queue panel row with Steer + ✕ rendered above composer; composer cleared;
     Working still shown — `fold_v2dupfix_r8_13_q_t1s.png`.
   - t+6/11/16/21/26 s: inbox unchanged, still `"delivery":"queue"` —
     `fold_v2dupfix_r8_14_q_t6s.png`, `_t11s.png`, `_t16s.png`, `_t21s.png`, `_t26s.png`
     (panel + Steer still visible, e.g. confirmed on `_t16s.png`).
   Chip tap DID submit (composer emptied immediately) — no bug observed.

3. **PANEL STEER (PASS-C) — PASS.**
   While the item was still parked (t+~30 s after queue tap), tapped the row's `Steer`
   action at UIA bounds `[1824,953][1920,1070]` (center 1872,1012). Immediately after:
   - inbox item `delivery` flipped `"queue"` → `"steer"` (captured at t+1 s and t+5 s);
   - queue panel row + Steer button vanished (UIA: no `Steer`, no queue-row text);
     Working + Stop still present (turn unaffected);
   - shots: `fold_v2dupfix_r8_15_after_steer_t1s.png`, `fold_v2dupfix_r8_16_after_steer_t5s.png`.
   End-state: after T3's sleep finished, the steered message was delivered as user msg
   `msg_0c8710033001…` (1790069512240) and answered with assistant text `QUEUED-OK`
   (`msg_0c875184…`, completed 1790069514694); inbox drained to `[]`.

4. **ABORT (PASS-D) — PASS.**
   Sent a fresh turn T4 with the same FG sleep message at 1790069564049. Stop button
   visible at t+2.4 s — `fold_v2dupfix_r8_18_abort_stop_shown.png` (square Stop +
   Working row). Tapped Stop (1994,1280) at 1790069566972. Result:
   - server `/api/session/active` returned `{}` within **286 ms** of the tap
     (idle msg `outcome=interrupted`; assistant `error={type:aborted,message:'Step interrupted'}`
     at 1790069566955);
   - composer back to idle within ~3 s: placeholder text, up-arrow Send, no Working,
     UIA desc=Send (not Stop) — `fold_v2dupfix_r8_19_abort_idle.png`;
   - transcript shows interrupted markers: `Build · … · 3s · Interrupted`, red shell
     block, `Interrupted` separator.
   Well within the ~10 s PASS-D bound.

5. **EXACTLY-ONCE TRANSCRIPT — PASS.**
   Full transcript swept top → bottom after dismissing the IME (keyboard had been
   absorbing swipe gestures): 9 scroll steps, screenshots
   `fold_v2dupfix_r8_21_tr_sweep0.png` … `_sweep8.png`, per-step UIA text dumps in
   `fold_v2dupfix_r8_sweep_texts.json`. Findings (cross-checked against server
   `/message` list):
   - `Running your 300s foreground sleep to completion.` — 4 instances total, one per
     turn (T1 `5m 6s`, T2 `5m 3s`, T3 `5m 2s`, T4 `3s · Interrupted` footers
     disambiguate); never twice within one viewport for the same turn.
   - Done texts — 2 total, distinct: T1 `Done — \`sleep 300 && echo done\` completed with
     exit 0, output: done.` and T2 `Done — exit 0, output: done.`; each rendered once.
   - user `reply with exactly: QUEUED-OK` — 1 bubble (9:31 AM), once.
   - assistant `QUEUED-OK` — 1 block (9:31 AM divider), once.
   - user sleep bubbles — 4 (9:06 / 9:19 / 9:26 / 9:32 AM), one per turn.
   - `Interrupted` appears as footer + separator — two distinct UI labels of the same
     interrupted turn, not a duplicated message.
   Sweep-top and sweep-bottom frames visually verified (no message block repeated).

Notes / anomalies this round:
- **Capture-tool caveat:** the `ReadMediaFile` viewer intermittently returned wrong or
  cached image content mid-round (a red/green-marker identity test on
  `fold_v2dupfix_r8_marker_full.png` failed to show the markers). All verdicts were
  therefore cross-checked through three independent channels: raw PNG pixel statistics
  (composer text row darkness), `uiautomator dump`, and a second vision model
  (MiniMax) on the same files. On-disk screencap files are consistent with UIA + server
  state: pixel stats + MiniMax confirm r8_04 = T1 busy (Stop+Working, cleared composer),
  r8_05 = titled+Working, r8_06 = idle after T1 completed — matching the server timeline.
  Early impressions of these frames as "stale Untitled + text" were viewer artifacts,
  not file content.
- Queue window for T1/T2 was missed while debugging the viewer; PASS-B/C were exercised
  on T3 with identical setup — no functional difference.
- Emulator status-bar clock was non-monotonic across captures; all timing above uses
  host epoch ms.
- Session left intact server-side, idle, transcript complete.

Verdict: **PASS-A = PASS; PASS-B = PASS; PASS-C = PASS; PASS-D = PASS;
exactly-once = PASS.**
