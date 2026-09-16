# OpenCode launcher branding

Launcher icon is the official OpenCode “window” glyph on a **full-bleed** dark `#131010` field.

HyperOS/MIUI often ignores a color-only adaptive background and content-crops a padded glyph onto a white plate. The bitmaps therefore fill the entire canvas (dark to the edges) with the window at ~70% of the viewport.

## Source

| Item | Upstream |
| --- | --- |
| Canonical SVG | [`packages/app/public/favicon-v3.svg`](https://github.com/anomalyco/opencode/blob/v1.18.31/packages/app/public/favicon-v3.svg) |
| Repo / pin | https://github.com/anomalyco/opencode @ **v1.18.31** |

Copies: `frontend/branding/favicon-v3.svg`, preview `frontend/branding/launcher-xxxhdpi.png`.

## How the Android bitmaps are built

`scripts/generate-launcher-icons.py`:

- `mipmap-*/ic_launcher.png` — opaque full-bleed (legacy + adaptive foreground)
- `mipmap-*/ic_launcher_round.png` — same mark, circular alpha
- `mipmap-*/ic_launcher_foreground.png` — 108–432px, same full-bleed art
- Adaptive XML uses a solid dark background drawable plus **`@mipmap/ic_launcher_foreground`** (never `@mipmap/ic_launcher`, which is the XML itself on API 26+ and becomes the Android robot)

Regenerate: `python3 scripts/generate-launcher-icons.py`

## HyperOS cache

Uninstall OpenCode completely, then install the new APK (`versionCode` must increase). Overlay-install often keeps the old white plate.
