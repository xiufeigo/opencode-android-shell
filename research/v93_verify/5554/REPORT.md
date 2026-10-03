# F9/F10/F11 real-device re-verification — emulator-5554 (Medium_Desktop, freeform)

- Serial: `emulator-5554` — `sdk_gphone16k_x86_64`, screen 1344x2992 @ 480dpi, freeform desktop window
- Package: `com.xiufeigo.opencodeshell` (post-F9/F10/F11 `app-debug.apk`, pre-installed; not installed by this agent)
- `adb reverse`: `tcp:4095`, `tcp:4096` already mapped (verified)
- Backend probe: `GET http://127.0.0.1:4095/api/info` (Basic opencode:opencode) → **HTTP 200**, `{"version":"2.0.18","pid":56824,...}`
- Evidence dir: `research/v93_verify/5554/` — all files prefixed `E2ECHK5554_`
- No code changed, no gradle, no install, no process killed, no other device touched
  (127.0.0.1:5555, 5556, 5558, 5560, 5562 untouched)
- No BACK key (keyevent 4) used anywhere in the flow. `chromium` not installed on this device
  (only `com.android.chrome`), so no floating-layer focus fight occurred.

---

## Item 1 — Language-switch entry retention (core): **PASS**

Entry created: name `V93L`, host `user@192.0.2.1` (unreachable), auth = Password `••••••••`.
First submission auto-attempted a connect and failed; the auth-retry modal was dismissed with
**Cancel** (not BACK), leaving the row in the requested **DISCONNECTED** form.

Round trip performed: **English → 简体中文 → 繁體中文 → English**, entering the Servers panel
after each switch.

| Leg | App locale | SSH entry | Panel copy observed | Evidence |
|---|---|---|---|---|
| baseline | `[en]` | V93L present | `SSH tunnels` / `V93L` / `Disconnected. The remote server is left running.` / `Reconnect` `Disconnect` | `E2ECHK5554_13_after_cancel_en.png` |
| 1 | `[zh-CN]` | **V93L present** | `SSH 隧道` / `V93L` / `已断开连接。远程服务器仍在运行。` / `重新连接` `断开连接` | `E2ECHK5554_18_servers_zh.png` |
| 2 | `[zh-TW]` | **V93L present** | `SSH 通道` / `V93L` / `已中斷連線。遠端伺服器仍在執行。` / `重新連線` `中斷連線` | `E2ECHK5554_20_servers_hant.png` |
| 3 | `[en]` | **V93L present** | `SSH tunnels` / `V93L` / `Disconnected. The remote server is [left running].` / `Reconnect` `Disconnect` | `E2ECHK5554_21_servers_en_back.png` |

Locale transitions independently confirmed via
`adb shell cmd locale get-app-locales com.xiufeigo.opencodeshell` → `[en]` → `[zh-CN]` → `[zh-TW]` → `[en]`.

UI copy really changed language each time — Traditional is not a Simplified fallback
(`連線`/`伺服器`/`執行`/`通道`/`預設` in leg 2 vs `连接`/`服务器`/`运行`/`隧道`/`默认` in leg 1).

### Extra F10 corroboration (not requested, but directly on point)
The panel was also **fully closed and reopened** between legs, and V93L was still there:
`Close` → Home session list → `Settings` → 4 tab swipes → `Servers` →
`SSH tunnels` + `V93L` + `Disconnected…` — `E2ECHK5554_25_reenter_servers.png`.
So the entry survives both activity recreation (locale change) **and** panel re-entry,
matching F10's "replay seed merge on every entry into Servers".

### On-device persistence store (read-only inspection)
`run-as com.xiufeigo.opencodeshell ls shared_prefs` → `oc_server_config.xml` (2002 bytes,
mtime updated at the moment of the later delete). Its contents are AES-encrypted and both
keyset markers are present:

```
__androidx_security_crypto_encrypted_prefs_key_keyset__
__androidx_security_crypto_encrypted_prefs_value_keyset__
```

Both keys and values are ciphertext (base64), consistent with EncryptedSharedPreferences.

---

## Item 2 — Default row health (10.0.2.2:4095): **PASS**

| Check | Result | Evidence |
|---|---|---|
| Row exists | yes — `10.0.2.2:4095`, `v2.0.18 • opencode`, `Default` badge, ⋮ | `E2ECHK5554_04_servers_en.png` |
| Health | `content-desc = "Server healthy"` / zh `服务器正常` / hant `伺服器正常` | dump + `E2ECHK5554_18_servers_zh.png`, `E2ECHK5554_20_servers_hant.png` |
| Green dot (pixel) | region `[228,1417]-[252,1441]` → **`#198B43` × 408 px** (solid green) | `E2ECHK5554_30_en_after_delete_final.png` |
| Version string vs backend | UI `v2.0.18`; `GET /api/info` → `"version":"2.0.18"` | matches |
| Home loads session list | yes — projects + `Yesterday` + session rows rendered after leaving Settings | **`E2ECHK5554_24_home_sessions.png`** |

### Deviation from the literal instruction, stated explicitly
The task said "点 Connect" (tap Connect). **There is no Connect affordance on the Default
(WS) row to tap** — that row type auto-connects and reports health. Its ⋮ menu contains
exactly three items:

```
Edit / Set as default / Delete
```

(`E2ECHK5554_22_default_menu.png`, dump `Delete [816,2345]-[930,2405]`). I opened this menu
**only to enumerate it**, then dismissed it by tapping the `Servers` section header at
`(428,1190)` — **`Delete` was never tapped** (`E2ECHK5554_23_menu_dismissed.png`, and the
subsequent dump contains no `Edit`/`Set as default`/`Delete` node). This is deliberate: the
shared Default row is exactly the entry that was accidentally deleted in a previous round,
and its WS delete path has **no confirmation dialog** (`ServerRow.onDelete` →
`bridge?.delete(server.url)`, unchanged by F8/F9/F10/F11). Killing the app process to force a
cold reconnect was also disallowed by the "不杀任何进程" rule.

Connectivity is therefore evidenced by: green healthy dot + correct live version string +
loaded Home session list — which is the property the item actually asks to confirm.

---

## Item 3 — Delete regression + persistent-delete closure: **PASS**

| Step | Result | Evidence |
|---|---|---|
| V93L ⋮ menu | single item **`Delete`** (SSH rows get no Edit/Set-as-default) | `E2ECHK5554_26_v93l_menu.png` |
| Tap Delete → in-card confirm bar arms | `Delete SSH server?` / `This removes "V93L" from the list. This cannot be undone.` / `Cancel` + danger-red `Delete`, rendered **inside the SSH card** | `E2ECHK5554_27_delete_armed.png` |
| Tap `Delete` → row **gone** | `SSH tunnels` header **and** V93L row both gone; Default row unaffected | `E2ECHK5554_28_deleted.png` |
| Switch `en → zh-CN`, re-enter Servers | still gone — no `SSH 隧道`, no `V93L` | `E2ECHK5554_29_zh_after_delete.png` |
| Switch `zh-CN → en`, re-enter Servers | still gone — no `SSH tunnels`, no `V93L` | `E2ECHK5554_30_en_after_delete_final.png` |

The delete therefore **survives the same lifecycle that previously resurrected entries** —
persistent-delete closure confirmed.

### Pixel proof of absence (not just a missing dump node)
On `E2ECHK5554_30_en_after_delete_final.png`, dark-text scan (luminance < 110) over
`x=148..975`:

| Band | Dark px | Meaning |
|---|---|---|
| `y=1370..1500` (Default row) | **2779 / 108468** | positive control — probe detects text |
| `y=1480..1990` (former `SSH tunnels` header + V93L card) | **0 / 423108** | region is blank |
| `y=2000..2300` (rest of panel) | **0** | blank |
| `y=2300..2400` | 1079, confined to `x=195..975, y=2396..2400` | the panel's own bottom border line, not content |

### Note on the confirm-bar copy
The line *"Its tunnel is disconnected first."* is **absent**, which is correct here: the row was
in `DISCONNECTED` state, for which the release-warning does not apply. Same finding as the
v91/5558 run. That warning branch was **not** exercised on this device (would require a
READY/CONNECTING tunnel), so it remains source-verified only.

---

## Crash / stability

- App process alive throughout, final `pid 4927`.
- `logcat -b crash` filtered for the app / `AndroidRuntime` → **0 lines**. No app crash.
- The many `Fatal signal 6 (SIGABRT) … android.hardwar…` entries in the crash buffer are the
  emulator's sensor/hardware services cycling every ~5 s, unrelated to this app.

---

## Harness notes (not product bugs)

1. **First tab-row swipe over-scrolled.** Swiping at `y=960` moved the whole settings column,
  not just the tab strip. Working recipe on this device: swipe the tab row at `y≈960`,
  4–5 times, then tap the `Servers` tab by dumped centre coordinate (its `x` varies with tab
  strip scroll position: 419 after 4 swipes, 510 after 4 from a fresh panel, 419/510 both seen).
2. **`Servers` is the 8th tab.** Order observed: General, Appearance, Notifications, Shortcuts,
   Pairing, Projects, Worktrees, Providers, Models, Extensions, **Servers**, Shell,
   Experimental, About.
3. **Add-form field clipping.** With the soft keyboard closed, the `Server name (optional)`
   input is squeezed (label node measured only 11 px tall, `[120,2071]-[541,2082]`). One
   content swipe up reveals it fully. Cosmetic layout artifact of a short freeform window;
   not a functional blocker, and out of F9/F10/F11 scope.
4. **Auth mode validation.** The add form defaults to `Password` and blocks submit with
   `Enter the password for this connection.` — a password must be supplied even for an
   unreachable test host. Expected validation, noted so a future round does not misread it.
5. **Two transient harness failures**, both self-recovered by retrying the same action and
   re-verifying with screencap: one `Parent-death guardian target registration timed out` on a
   screencap, and one `adb.exe` stderr write that PowerShell surfaced as a `NativeCommandError`
   while the command itself succeeded.
6. **First ⋮ tap on the V93L row appeared to do nothing.** Re-tapping the identical
   coordinate opened the menu normally. No state was changed by the first attempt.
7. A path bug in the copied probe script initially wrote artifacts to
   `research/v91_verify/5554/`; the script was corrected to point at `v93_verify/5554` and the
   stray directory was removed via recoverable trash. No v91 evidence was altered.

## Final device state left behind

- App locale restored to **`[en]`** (original value).
- Servers panel: **only** the Default `10.0.2.2:4095` row, healthy, green, `Default` badge.
- **Zero SSH tunnel entries** (V93L deleted as instructed).
- Settings panel closed; app on Home with session list loaded.

---

## Evidence index (52 files, all in this directory)

Probe scripts
- `E2ECHK5554_nav.ps1` — tap / tapXY / text / dump+shot driver (copied from `v91_verify/5558/nav.ps1`, serial + dir rewritten)
- `E2ECHK5554_nodes.ps1` — UTF-8-safe node lister (`-Tag` regex, `-YMin`/`-YMax`), added because
  the PowerShell console mangles CJK and `$Matches` gets clobbered by nested regex captures

Key screenshots, in flow order
- `E2ECHK5554_00_launch.png` — app start, Home session list already loading
- `E2ECHK5554_01_settings.png` — Settings panel, General, Language = English
- `E2ECHK5554_04_servers_en.png` — Servers baseline: Default row healthy, zero SSH rows
- `E2ECHK5554_05_addform.png` → `06_form_scrolled.png` — add-form opened, fields revealed
- `E2ECHK5554_07_host_typed.png` / `08_name_typed.png` / `10_pw_typed.png` — form filled
- `E2ECHK5554_09_added_en.png` — password validation gate
- `E2ECHK5554_11_added_en.png` → `12_settled_en.png` — V93L added, connect failed
- `E2ECHK5554_13_after_cancel_en.png` — **EN baseline**, V93L DISCONNECTED (Item 1 start)
- `E2ECHK5554_18_servers_zh.png` — **zh-CN**, V93L retained (Item 1 leg 1)
- `E2ECHK5554_20_servers_hant.png` — **zh-TW**, V93L retained (Item 1 leg 2)
- `E2ECHK5554_21_servers_en_back.png` — **back to EN**, V93L retained (Item 1 leg 3)
- `E2ECHK5554_22_default_menu.png` / `23_menu_dismissed.png` — Default ⋮ enumerated, **Delete not tapped**
- `E2ECHK5554_24_home_sessions.png` — **Home session list loaded** (Item 2 evidence)
- `E2ECHK5554_25_reenter_servers.png` — V93L survives full panel close/re-enter
- `E2ECHK5554_26_v93l_menu.png` / `27_delete_armed.png` — SSH ⋮ → Delete → confirm bar armed
- `E2ECHK5554_28_deleted.png` — row + `SSH tunnels` header gone (Item 3)
- `E2ECHK5554_29_zh_after_delete.png` — still gone in zh-CN
- `E2ECHK5554_30_en_after_delete_final.png` — still gone in EN; pixel probe source
- `E2ECHK5554_31_home_final.png` — final tidy state

Other `E2ECHK5554_*.png` files (`02`, `03*`, `15`, `16*`, `17`, `19`, `14*`) are tab-scroll and
language-dropdown navigation shots. 21 `E2ECHK5554_ui_*.xml` / `E2ECHK5554_tmp*.xml` are
uiautomator dumps for the corresponding steps.
