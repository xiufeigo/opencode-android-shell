# OpenCode launcher branding

Launcher icon is the official OpenCode “window” glyph on a **full-bleed** dark `#131010` field.

HyperOS/MIUI ignores a color-only adaptive background and content-crops a padded glyph onto a white plate. Both adaptive layers are therefore the **same opaque PNG** of the mark (window ~80% of the canvas, dithered dark to the edges).

## Source

| Item | Upstream |
| --- | --- |
| Canonical SVG | [`packages/app/public/favicon-v3.svg`](https://github.com/anomalyco/opencode/blob/v1.18.31/packages/app/public/favicon-v3.svg) |
| Repo / pin | https://github.com/anomalyco/opencode @ **v1.18.31** |

Copies: `frontend/branding/favicon-v3.svg`, preview `frontend/branding/launcher-xxxhdpi.png`.

## How the Android bitmaps are built

`scripts/generate-launcher-icons.py`:

- `mipmap-*/ic_launcher.png` — opaque full-bleed (legacy launcher if OEM skips adaptive)
- `mipmap-*/ic_launcher_round.png` — same mark, circular alpha
- `mipmap-*/ic_launcher_foreground.png` — 108–432px, same full-bleed art
- `drawable-nodpi/ic_launcher_background.png` — same art (not a `<shape>` color)
- Adaptive XML: background `@drawable/ic_launcher_background`, foreground `@mipmap/ic_launcher_foreground` (never `@mipmap/ic_launcher`)

Regenerate: `python3 scripts/generate-launcher-icons.py`

## HyperOS cache

Uninstall OpenCode completely, then install the new APK (`versionCode` must increase). Overlay-install often keeps the old white plate.
