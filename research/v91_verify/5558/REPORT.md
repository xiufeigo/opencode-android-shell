# F8 SSH-row delete — E2E verification on emulator-5558

- Serial: `emulator-5558` (Pixel_9a, com.xiufeigo.opencodeshell)
- APK: post-F8 build, already installed
- Evidence dir: `research/v91_verify/5558/`
- No other device touched. No sessions created. No repo files edited outside this dir.

---

## Item 1 — Failed-state SSH entry delete flow: **PASS**

| Step | Result | Evidence |
|---|---|---|
| Settings → Servers baseline (1 WS row, 0 SSH) | ok | `s04_servers.png`, `ui_servers0.xml` |
| Add SSH server → host `10.255.255.1:22` | typed OK | `s08_hosttyped.png`, `ui_hosttyped2.xml` (`text='10.255.255.1:22'`) |
| Password gate answered (`pass1234`) | ok | `ui_after27.xml`, `ui_pwtyped*.xml` |
| Connection failure reached (~8 s) | FAILED | `s11_pwsubmitted.png`, `s12_failedstate.png` — "Could not establish the SSH connection. Check…", "Connection failed" |
| Dead row w/ Reconnect + Disconnect + ⋮ | ok | `s13_failedclean.png` (`ui_failedclean.xml`) |
| ⋮ menu on SSH row | **single item "Delete"** | `s14_ssh_menu.png`, `ui_ssh_menu.xml` |
| ⋮ → Delete → confirm bar arms | ok | `s15b_armed.png`, `ui_armed_scrolled.xml`, `s18b_armed2.png` |
| **Cancel → row still present** | **PASS** | `s16_aftercancel.png` — bar gone, `10.255.255.1:22` + Reconnect/Disconnect + ⋮ all still there |
| ⋮ → Delete → Confirm → row gone | **PASS** | `s19_afterconfirm.png` — row + "SSH tunnels" header both gone within ~1.2 s |
| Leave Settings (Close) → re-enter → Servers | **does not reappear** | `s20_closed.png` → `s21_reenter_servers.png` |

Confirm-bar copy rendered (from `ui_armed_scrolled.xml`):
- Title: `Delete SSH server?`
- Body: `This removes "10.255.255.1:22" from the list. This cannot be undone.`
- `Cancel` + danger `Delete` (fill `#B82D35`, white label — pixel-probed in `s18b_armed2.png`)

Note: the extra line "Its tunnel is disconnected first." is **correctly absent** — the row was a
dead row (FAILED, then DISCONNECTED after dismissing the auth-retry modal), and
`sshForgetReleasesTunnel()` returns `false` for both. That warning is source-confirmed for
READY/CONNECTING/CHECKING/… but was **not** device-verified, since item 1 specifies a failed-state row.

Confirm bar renders **inside the SSH card** (see `s18b_armed2.png`), above the separate WS card — matching the in-card design.

## Item 2 — WebSocket (non-SSH) row regression: **PASS**

- Default WS row ⋮ menu contains exactly the pre-existing entries: **Edit / Set as default / Delete**
  — `s05_ws_menu.png`, `ui_ws_menu.xml`.
- No confirm bar, no new in-card block, no reordering on the WS row.
- Dismissed by tapping the section header (outside the dropdown) — **Delete was never tapped**,
  so the shared row is untouched: `s06_ws_dismissed.png`, `ui_ws_dismissed.xml`.

Non-leak confirmed in source as well as on device:
- `SshTunnelForgetConfirm` is rendered **only** inside `SshTunnelCard`, guarded by
  `forget.armed == tunnel.config.id` (`ui/servers/SshTunnelHost.kt:252-256`).
- `ServerRow`'s `onDelete` is the pre-existing immediate `bridge?.delete(server.url)` with no
  confirm (`ui/settings/ServersPanel.kt:179-182`) — unchanged by F8.

**Deliberate deviation:** I did not exercise WS-row Delete, because the task forbids deleting the
shared Default row and that path removes the entry with **no confirmation** (source above). It
was verified structurally (menu contents + code path), not by execution.

## Item 3 — Clean state after item 1: **PASS**

After the confirmed delete and a full Settings close/re-enter cycle:

- WS row renders normally: `10.0.2.2:4095` / `v2.0.18 • opencode`, `Default` badge, ⋮ present
  — `s21_reenter_servers.png`, `ui_reenter_servers.xml`.
- Health dot green: pixel probe `[201,1145]-[222,1166]` → `#198B43` ×317.
- **Zero SSH rows** and no `SSH tunnels` header: dark-text (lum<110) pixel scan over
  `x=148..975, y=1200..2010` → **total 5 stray px** (panel edge at y=2000); the whole band is blank.
  Positive control on the WS row band `y=1090..1200` → 446 / 1502 / 231 dark px, proving the probe detects text.

---

## New bugs

**None found for F8.** No regression, no crash, no state leak.

### Observation (out of F8 scope, not verified as a regression)
The SSH card's status line wraps at ~2 characters per line ("Dis/con/nected. The/rem/ote/ser/ver/…"),
see `s13_failedclean.png` and `s18b_armed2.png`. This is a narrow-column layout artifact of the
status text, not of the delete flow — F8 only adds the ⋮ entry and the confirm bar. I did not
compare against a pre-F8 build, so I cannot claim it is pre-existing; flagging it for a look only.

## Harness noise hit (not product bugs)
- uiautomator dumps lagged twice: the armed confirm bar's `Delete` node was missing on first dump
  (`ui_armed3.xml`) and the WS row's `10.0.2.2:4095` was missing from an intermediate dump.
  Screencap pixel evidence resolved both, as expected.
- The confirm bar renders at the very bottom of the card; it sits **below the fold** until the
  panel is scrolled. First `Delete` tap appeared to "do nothing" in the dump for this reason only.
- Two `Parent-death guardian target registration timed out` harness errors; both were retried
  and the actions re-verified.
- The connection failed in ~8 s, well inside the 35 s budget.
