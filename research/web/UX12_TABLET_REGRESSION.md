# UX12 — Tablet Regression (Plan A: bundled official frontend + local http origin)

Round: **UX12-Tablet** · emu `emulator-6792` · AVD `Medium_Tablet` · native 1280x800 (800dp wide = tablet tier)
Backend `127.0.0.1:4095` — read-only probes only, never started/stopped by this round.

## 0. Preconditions

| item | value |
| --- | --- |
| APK | `research/deliver/opencode-shell-web.apk` |
| size | 16,796,038 bytes |
| MD5 | `BF688F8A033EDE65C3D4F8C5E8D1E930` |
| build-report registry match | ✅ equals UX12_PLAN_A_BUILD.md registered debug md5 |
| port 6792 | FREE before launch |
| backend /api/info | HTTP 200, `{"version":"2.0.23","pid":31812,...}` |

## 1. Page actual origin — **PASS**

Injected read (b64 JS, read-only):

```
-> "{\"href\":\"http://127.0.0.1:4173/\",\"protocol\":\"http:\",\"origin\":\"http://127.0.0.1:4173\",
\"docURL\":\"http://127.0.0.1:4173/\",\"title\":\"OpenCode\",\"ready\":\"complete\"}"
```

Host logcat (verbatim):
```
I OcLocalUi: local UI origin listening on http://127.0.0.1:4173 (static assets only)
I OcWebHost: insets replayed after page finish: http://127.0.0.1:4173/
```
Traffic origin evidence — every asset served from the local http origin, e.g.
`OcPerfJS res #59 ... http://127.0.0.1:4173/_assets/SOM3K36D-B_ALxO-h.js`, 60 resources, xfer=1,786,706.

Screenshot `ux12t_home.png`: official Home, tablet layout (left sidebar Projects/Add project/Settings/Help + main pane).

## 2. Upstream criteria measured — **PASS**

| probe | result |
| --- | --- |
| `pageOrigin` | `http://127.0.0.1:4173` |
| `isSecureContext` | `true` (localhost is a secure context) |
| `window.isMixedContent` | `undefined` (not exposed) |
| CSP mixed-content directive | `none` (no `meta[http-equiv=Content-Security-Policy]`) |
| **U1** cross-origin `http://127.0.0.1:4095/api/info` w/ Basic | `OK status=200` |
| **U2** `https://127.0.0.1:4095/api/info` from this page | `THROW TypeError: Failed to fetch` |
| **U3** no-cors `http://127.0.0.1:4095/api/info` | `OK status=0` (opaque) |

**Verbatim:** `{"u3_nocors":"OK status=0","u2_https_from_http":"THROW TypeError: Failed to fetch","u1_cross_http":"OK status=200"}`

Judgment: because the UI is served over **`http://`**, an `http://` API is *not* mixed content and is not blocked — U1 returning **200** proves the criterion directly. U2 fails for the mirror-image reason (TLS to a plaintext port), not a policy block.

## 3. Official "Add server" with http on the same page — **PASS**

Real clicks: Home → drawer → Settings → Server → Add server → address `http://127.0.0.1:4095`, password `opencode` → waited 14s (health check is a 10s round) → submit.

- Dialog closed: `dialogOpen: false`
- Server list shows `127.0.0.1:4095` and `127.0.0.1:4173`
- **Green dot** confirmed on the `127.0.0.1:4095` row (screenshot `ux12t_server_green.png`)
- Connection evidence (OcPerfJS, verbatim):
```
OcPerfJS res #513 api ... http://127.0.0.1:4095/api/project @19
OcPerfJS res #514 api ... http://127.0.0.1:4095/api/session/active @19
OcPerfJS res #516 api ... http://127.0.0.1:4095/api/vcs @19
OcPerfJS res #522 api ... http://127.0.0.1:4095/api/info @19
```
- **No red text:** red-text scan returned `[]` (no element with a reddish computed color).

Deviation from the round's expectation, recorded honestly: the **Terminal shell row reads `Auto (Default)`, not `powershell`.** The dropdown options are
`Auto (Default) (selected=true) / pwsh / powershell / bash / cmd`.
Auto is the upstream default and resolves to the host shell; the server row itself is green and connected.

## 4. Official "Add server" with https, same page — **PASS (equivalent construction)**

> ⚠️ **This is an equivalent construction, not the literal "add https to a live http server" case.**
> The backend at 4095 is plaintext-only, so there is no TLS listener to make a literal https pairing meaningful.
> The step instead drives the real UI to submit the scheme pair `https://127.0.0.1:4095` from the same
> `http://127.0.0.1:4173` page and compares the resulting error surface against the http case.
> **Criteria basis:** the judgement under test is whether the *page* (`http://`) blocks a *server* entry by scheme.
> A generic "cannot connect" proves the failure is transport, not a mixed-content policy refusal.

Submitted `https://127.0.0.1:4095` + password, waited 16s.

- Dialog **remains open**: `dialogOpen: true`
- Only generic error, verbatim: `Could not connect to server`
- Red-text scan matched no *mixed-content* text; the single reddish string is the generic connectivity error above.

**Conclusion:** https fails with a generic connectivity error, whereas the same flow with `http://` succeeds
(dialog closes, green dot, live 200 traffic). No mixed-content / insecure-content red text appears in either case,
because the UI origin is itself `http://`. **The red text is gone — no scheme-based blocking at all.**

### Input note (real-interaction artefact, not a product defect)
The address field autocapitalises. Typing `https://…` through the real IME produced `Https://…` / `Xhttps://…`.
A literal lowercase `https://127.0.0.1:4095` was finally entered by typing the tail `://127.0.0.1:4095` first and
prepending `https` with the cursor at position 0. This affects the on-screen keyboard path only; it is recorded
as an upstream input-behaviour observation.

## 5. Full regression — session `ses_ef43fab08…` on server `http://127.0.0.1:4095`

Session URL (base64 segment `aHR0cDovLzEyNy4wLjAuMTo0MDk1` = `http://127.0.0.1:4095`).

| # | item | result | evidence |
| --- | --- | --- | --- |
| 1 | terminal usable | **PASS** | `ux12t_terminal_echo.png`: `PowerShell 7.6.6`, prompt `PS D:\XiaoZhuTouTou\OpenCodeUI>`; `echo UX12T_TERMINAL_OK` round-tripped (PS parsed the arg → `InputObject:`). Terminal is canvas-rendered, so content is not in `innerText` — confirmed by screenshot. |
| 2 | terminal tab-bar `+` → `Terminal 2`, no panel | **PASS** | after tap: `Terminal 1` + `Terminal 2`, `panelShown: false`. `ux12t_terminal2.png` |
| 3 | **review tab-bar `+` still opens our panel** | **PASS** | `ux12t_panel.png` shows 打开 / 打开终端 / 打开文件; `panelKeywords: ["打开","打开终端","打开文件"]` |
| 4 | panel 打开终端 works | **PASS** | terminal panel opened, `Terminal 1` auto-created (`{"s":"Terminal 1",...}`). `ux12t_open_terminal.png` |
| 5 | panel 打开文件 works | **PASS** | upstream `Open file` browser rendered (`OpenCodeUI .git .github .idea assets dist docker docs node_modules public scripts src-router src-tauri src …`), and `topResumedActivity=…com.xiufeigo.opencodeshell.web.WebHostActivity t8` — **no Android SAF** picker. `ux12t_open_file.png` |
| 6 | send message → reply | **PASS** | `Reply with the single word OK` → `OK` (`Fledge Alpha Free · 2s`). `ux12t_reply.png` |
| 7 | switch session / return Home | **PASS** | `+` created a 2nd tab; switching back → `…/session/ses_ef43fab08…`; Home → `/`; re-enter → same session, `ready: complete` |
| 8 | keyboard does not clip composer | **PASS (with caveat)** | session screen: `visualViewport.height=800`, `innerHeight=800`, `composerBottom=367` → `fits:true`, `overflowPx=-433` |
| 9 | theme light / dark / system | **PASS** | `data-color-scheme`: `dark` (bodyBg `rgb(8,8,8)`) → `light` (bodyBg `rgb(250,250,250)`) → System resolves to `light`. `ux12t_theme_dark.png`, `ux12t_theme_light.png` |
| 10 | keep-awake during a turn | **PASS** | `D1 applyKeepScreenOn: roundActive=true foreground=true shouldKeep=true flagSet=true` @03:43:59.226 → `roundActive=false … flagSet=false` @03:44:01.805 — **released 2.579 s later** |
| 11 | background SSE drop / foreground rebuild | **PASS** | HOME: `[oc-pow] event stream CLOSE #1155 cancel` + 4095 `/api/event` closed. Foreground: `event stream OPEN #1182` @03:47:45.121, `OPEN #1183` @03:47:46.135; page `ready: complete` |
| 12 | official settings can add + connect a server | **PASS** | see §3 |

### Item 8 caveat (honest note)
The soft IME does raise (`dumpsys input_method` → `mInputShown=true`) but the **WebView viewport does not shrink on this
tablet AVD** — `visualViewport.height` stayed equal to `innerHeight` (800) with the keyboard up, so the keyboard-up
measurement could not be taken in its intended form. What was verified is that the composer is never clipped: its bottom
edge (367 CSS px) stays 433 px **above** the viewport bottom. The New session screen was not separately measured because
the soft keyboard could not be raised as a viewport resize on this device profile.

## 6. Closeout

1. **Devices** — at closeout only `emulator-6792 device product:sdk_gtablet_x86_64 model:Pixel_Tablet` was attached.
   Every command in this round was scoped `-s emulator-6792`. `emulator-6790` (the parallel phone round) was seen once in
   an early `adb devices` listing and was **never targeted, never installed to, and never killed by this round**.
2. **Emulator killed / port released** — `adb -s emulator-6792 emu kill` → `OK: killing emulator, bye bye`;
   `Get-NetTCPConnection -LocalPort 6792` → **`6792 RELEASED (no listener)`**.
3. **Reverse removed** — before: `host-16 tcp:4095 tcp:4095`; after `adb reverse --remove tcp:4095` the list is **empty**.
4. **Backend never started/stopped** — `4095 LISTENING pid=31812`, `GET /api/info` → **HTTP 200** with `"pid":31812`,
   the same pid observed before this round began. Only read-only `Invoke-WebRequest` was used.
5. **No source edits** — `git status --porcelain` shows `webapp/**` modifications, but their mtime is
   **10:55:52**, i.e. they come from the UX12 Plan-A **build** round and predate this round's start (11:23:34).
   This round wrote only under `research/web/`. Gradle was never invoked.

### Serials touched
- `emulator-6792` — **the only serial used this round** (started by this round, installed to, and killed by this round).
- Non-self instances touched: **none**.
