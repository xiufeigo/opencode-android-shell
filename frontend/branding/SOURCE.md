# OpenCode launcher branding

Launcher icon is the official OpenCode favicon-v3 mark: a full-bleed dark `#131010`
field with the white window in the center (not a white plate with a tiny inner glyph).

- **Legacy** `ic_launcher.png`: SCALE=1, the square *is* the favicon.
- **Adaptive** FG/BG: SCALE=72/108 so the whole favicon (including its dark margin)
  sits in the 66% safe zone. SCALE=1 on 108dp lets the OEM mask eat the dark
  margin and leaves a white plate.

HyperOS/MIUI ignores a color-only adaptive background and content-crops a *uniform*
dark field onto a white plate. Both adaptive layers are the **same opaque PNG**,
Bayer-dithered. Never zoom the window above 1.0 on the 108dp canvas.

## Source

| Item | Upstream |
| --- | --- |
| Canonical SVG | [`packages/app/public/favicon-v3.svg`](https://github.com/anomalyco/opencode/blob/v1.18.31/packages/app/public/favicon-v3.svg) |
| Repo / pin | https://github.com/anomalyco/opencode @ **v1.18.31** |

Copies: `frontend/branding/favicon-v3.svg`, preview `frontend/branding/launcher-xxxhdpi.png`.

## How the Android bitmaps are built

`scripts/generate-launcher-icons.py`:

- `mipmap-*/ic_launcher.png` — opaque official mark (legacy launcher if OEM skips adaptive)
- `mipmap-*/ic_launcher_round.png` — same mark, circular alpha
- `mipmap-*/ic_launcher_foreground.png` — 108–432px, same full-bleed art
- `drawable-nodpi/ic_launcher_background.png` — same art (not a `<shape>` color)
- Adaptive XML: background `@drawable/ic_launcher_background`, foreground `@mipmap/ic_launcher_foreground` (never `@mipmap/ic_launcher`)

Regenerate: `python3 scripts/generate-launcher-icons.py`

## HyperOS cache

Uninstall OpenCode completely, then install the new APK (`versionCode` must increase). Overlay-install often keeps the old white plate.
