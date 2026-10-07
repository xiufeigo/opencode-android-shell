# UX17-P2 — Final Verify (hidden-map end-to-end + T6 long-round + smoke)

Round: **UX17-P2 / final verification only — no code changed**
Date: 2026-10-07
Serial touched: **`emulator-6790`** only (`Medium_Phone_API_37.0`, self-created/self-killed). `emulator-5554` received **no** writes.

APK: `research/deliver/opencode-shell-web.apk` — MD5 `F03A86EF47B22D12C689A308E0CAA67D`, 16,827,823 bytes (matches batch #11).
Package: `com.xiufeigo.opencodeshell.web.debug`. Backend `127.0.0.1:4095` (pid 21136) untouched, HTTP 200 at close.

---

## Verdict summary

| # | Item | Result |
|---|------|--------|
| 0 | First boot: `opencode.global.dat:server` is null | ✅ PASS |
| 1 | ① 4173 disappears from settings nav / Home / dropdown | ❌ **FAIL** |
| 1 | ② Home auto-lands on 4095, no Loading deadlock | ❌ **FAIL** |
| 1 | ③ `hidden` contains `http://127.0.0.1:4173` | ✅ PASS |
| 2 | T6 keep-alive branch on long round | ✅ PASS |
| 2 | T6 background `session.execution.succeeded` | ✅ PASS |
| 2 | T6 notification present | ✅ PASS |
| 2 | T6 real click back to session | ✅ PASS |
| 3 | Smoke: terminal echo | ✅ PASS |
| 3 | Smoke: idle background SSE release / resume | ✅ PASS |
| 3 | Smoke: theme dark | ✅ PASS |
| 3 | Smoke: composer bottom ≤ vv.height | ✅ PASS |

**T6 is fully cleared as a test-timing artifact.** The keep-alive branch works correctly on a long round.
**hidden-map is NOT cleared end-to-end.** The registry write works (③), but the official UI does not consume it (①, ②).

---

## Step 0 — Boot and first-boot judgement ✅

`install -r` → `pm clear` → `am start` → 5s.

CDP read of `localStorage.getItem('opencode.global.dat:server')` on first boot:

```
None
```

`null` ✅ — hidden-map writes **nothing** at first boot.

The 4173 builtin row **is visible** on first boot, as required — Settings ▾ Preferences… ▾ `Server` segment,
`Connection` section:

```
●  127.0.0.1:4173
   http://127.0.0.1:4173
```

(red dot = offline). Evidence: `research/tmp/boot03_settings.png`, `boot05_server_seg_after` predecessor `boot05_server_seg.png`.

---

## Step 1 — Add server 4095 (coordinate script, all hits on first try)

Script coordinates worked as documented; no ±30px adjustment was needed except re-opening the segment
picker once after the dialog closed (see deviation note below).

| Step | Action | Coordinate | Result |
|---|---|---|---|
| 1 | hamburger | `(67,120)` | ✅ |
| 2 | Settings row | `(179,2324)` | ✅ |
| 3 | `Preferences ▾` → `Server` | `(868,216)` → `(785,988)` | ✅ |
| 4 | `+ Add server` | `(906,482)` | ✅ |
| 4 | `Server address` | `http://127.0.0.1:4095` | ✅ |
| 4 | `Password` | 8 dots (`opencode`) | ✅ |
| 4 | wait ≥14s, submit | 16s wait, `(907,1652)` | ✅ |

**Deviation (documented):** after submit, Settings reset to the `Preferences` segment. Re-selecting `Server`
at `(785,988)` missed because the picker list had changed; re-opened the picker at `(868,216)`, then
selected the per-server segment. This is the "real click, not DOM scan" rule working as intended.

### ③ hidden key written ✅

```json
{"list":[{"type":"http","http":{"url":"http://127.0.0.1:4095","password":"opencode"}}],
 "hidden":{"http://127.0.0.1:4173":true},
 "projects":{}, "lastProject":{}, "recentlyClosed":{}}
```

`hidden` contains `http://127.0.0.1:4173` → **true** ✅.

### ① 4173 does NOT disappear ❌ FAIL

Per the script, **no switcher was clicked** after the add. 4173 is still visible in all three locations:

- **Settings segment picker (下拉)** — after adding, the generic `Server` segment is replaced by per-server
  segments, and **both** are listed:
  ```
  Preferences ✓
  Appearance
  Notifications
  Shortcuts
  ─────────────
  127.0.0.1:4095
  127.0.0.1:4173     ← still present
  ─────────────
  About
  ```
  Evidence: `research/tmp/hm03_picker.png`, `sm5_picker.png` (re-confirmed twice, later in the round).
- **Home server switcher dropdown** — both rows shown, 4173 with a **red/offline dot**, 4095 green/online:
  ```
  > ●  127.0.0.1:4173      …
    🟢 127.0.0.1:4095      …
  ```
  Evidence: `research/tmp/hm05_switcher.png`.
- **Home header context** — subtitle reads `127.0.0.1:4173`.

The `hidden` map is present in `localStorage` but the official UI does **not** filter these lists by it.

### ② Home does NOT auto-land on 4095 ❌ FAIL (Loading deadlock)

Home after the add, with **no switcher touched**:

```
All projects
127.0.0.1:4173          ← context stayed on 4173, did NOT auto-switch to 4095
Search sessions in 127.0.0.1:4173
Loading                 ← stuck, deadlock
```

Evidence: `research/tmp/hm04_home.png`.

Because the app itself is served from `http://127.0.0.1:4173/` (the WebView asset origin), the Home context
is pinned to 4173 and queries a non-backend origin → `Loading` never resolves.

Only after a **manual** switch to 4095 did Home render correctly (4 real sessions, no deadlock):

```
All projects
127.0.0.1:4095
Today    v2 smoke test
Yesterday  Greeting · Greeting · New session
Older    Single word reply request · New session
```

Evidence: `research/tmp/hm06_on4095.png`.

**Script deviation (forced):** the script says *"绿点后不要点任何切换器"*. Since ② failed and Home was
deadlocked on 4173, T6 could not run at all. I manually switched to 4095 to proceed. This means the
end-to-end acceptance for ② is a genuine **FAIL**, not a skipped check.

### Corroborating finding — SDK event stream still bound to 4173

Throughout the round the `oc-pow` SSE probe kept targeting the asset origin and 404'ing, **even while the
UI was correctly on 4095**, in a ~1 s retry loop:

```
[oc-pow] event stream OPEN #387 http://127.0.0.1:4173/api/event status=404
[oc-pow] event stream NON2XX #387 http://127.0.0.1:4173 status=404
[global-sdk] event stream disconnected
```

This is the most likely mechanism behind ①/②: the registry is written correctly, but the SDK's
"current server" context stays bound to 4173, so list rendering and Home context never move to 4095.

---

## Step 2 — T6 long-round verification ✅ (4/4)

### Setup
- Session `v2 smoke test` opened from the 4095 Home list.
- Permission grabbed first via a short `ping` message → real `POST_NOTIFICATIONS` system dialog appeared
  → **Allow** tapped for real. Confirmed: `android.permission.POST_NOTIFICATIONS: granted=true, flags=[USER_SET|…]`.
- Ping round completed (`Pong!` 2s), no round active before the long round.

### Long round
Message: `Count from 1 to 500, comma separated, think step by step`
(a stray `to` landed in the text — typed as `Count tofrom …`; harmless, round unaffected.)

**Send + HOME interval measured at 140 ms** (< 1.5 s required) ✅

### T6 grid — all four cells PASS

| Cell | Verdict | Raw evidence |
|---|---|---|
| Keep-alive branch (not pagehide) | ✅ | `P7 onPause: round active -> keep SSE alive (blur only, no pagehide)` |
| Background round succeeded | ✅ | `[oc-pow] round end session.execution.succeeded` @ `01:49:13.101` |
| Notification present | ✅ | `NotificationRecord(… pkg=com.xiufeigo.opencodeshell.web.debug … channel=oc_turns … flags=AUTO_CANCEL)` |
| Real click back to session | ✅ | focus → `WebHostActivity`; full `1, 2, 3, … 173, …` response rendered |

Supporting timeline (verbatim, no guessing):

```
01:48:45.384  [oc-pow] keepScreenOn -> true busy=[__post__]
01:48:45.385  [oc-pow] round POST /api/session/ses_ef43fab08ffeoHiNxtinjW9V0c/prompt
01:48:45.399  P7 onPause: round active -> keep SSE alive (blur only, no pagehide)
01:48:45.420  D1 applyKeepScreenOn: roundActive=true foreground=false shouldKeep=false flagSet=false
01:48:45.559  [oc-pow] round start session.execution.started
01:49:13.101  [oc-pow] round end session.execution.succeeded
01:49:13.415  P7 background round ended -> dispatch pagehide (release SSE)
```

Round ran ~27.5 s entirely in background and terminated on `succeeded`, then released SSE correctly.

Notification shade (`research/tmp/t6_09_shade2.png`):

```
OpenCode Web · now
Response ready
v2 smoke test
```

Real tap on the notification returned to `WebHostActivity` with the complete response in the transcript.

**Conclusion: T6 was a test-timing artifact (short round + late HOME). With a long round and an
immediate HOME, the keep-alive path is taken correctly and the round completes in background.**

---

## Step 3 — Smoke regressions ✅ (4/4)

| Item | Verdict | Evidence |
|---|---|---|
| Terminal echo | ✅ | `PS D:\…> echo oc_smoke_echo_ok` → `oc_smoke_echo_ok` |
| Idle background SSE / resume | ✅ | idle HOME → `P6 dispatch synthetic pagehide (upstream pageLifecycle)`; resume → SSE probe loop restarts, session fully intact |
| Theme dark | ✅ | Appearance ▸ Color scheme `System` → `Dark`; persisted as `opencode-color-scheme` |
| Composer bottom ≤ vv.height | ✅ | `composerBottom=893`, `visualViewportHeight=914`, `overflowPx=-21`, `fitsWithinVV=true` |

Terminal, theme and composer were all exercised through real UI interaction, not DOM injection.

---

## Step 4 — Cleanup ✅

- `adb -s emulator-6790 reverse --remove tcp:4095` → `reverse --list` empty.
- `adb -s emulator-6790 forward --remove-all` → empty.
- `adb -s emulator-6790 emu kill` → `OK: killing emulator, bye bye`.
- `adb devices` → only `emulator-5554` remains (never written to).
- Backend pid **21136** still `opencode`, `GET http://127.0.0.1:4095/` → **HTTP 200**.

---

## Findings for the next round

1. **hidden-map is write-only.** `hidden:{"http://127.0.0.1:4173":true}` is persisted exactly as designed,
   but no UI surface filters on it (server switcher, settings segment picker, Home context). The batch #11
   claim "official consumption" does not survive an end-to-end check — the registry is written, not consumed.
2. **4173 is not just a phantom row — it is the live asset origin.** Home's context is pinned to 4173, which
   is why ② deadlocks on `Loading` and why the SDK event stream 404s. Hiding a server that is also the page
   origin cannot work by list filtering alone; the *active context* has to be re-pointed.
3. **T6 needs no further work.** Long round + immediate HOME reproduces the intended keep-alive branch.
   Re-test only as a regression guard.
4. Not verified this round (out of scope): whether `hidden` survives a cold restart, and whether a
   genuinely non-origin backend (a second device port) hides cleanly. Both are worth a follow-up.

---

## Report path

`research/web/UX17_P2_FINAL_VERIFY.md`

---

首启 null✅ · ①消失❌FAIL ②自动切换❌FAIL(Loading 死锁,需手动切) ③hidden键✅ ·
T6（保活分支✅ / 后台 succeeded✅ / 通知✅ / 点击回会话✅ 四格全过,已证伪"测试时序"）·
冒烟四项 全✅ · 报告路径 `research/web/UX17_P2_FINAL_VERIFY.md` · 碰过的 serial: **emulator-6790**(仅此一个,emulator-5554 全程未写)