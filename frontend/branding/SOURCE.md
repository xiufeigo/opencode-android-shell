# OpenCode launcher branding

Launcher icon is the official OpenCode “window” glyph (dark `#131010` field, white frame, gray inner pane).

## Source

| Item | Upstream |
| --- | --- |
| Canonical SVG | [`packages/app/public/favicon-v3.svg`](https://github.com/anomalyco/opencode/blob/v1.18.31/packages/app/public/favicon-v3.svg) |
| Same artwork | `packages/ui/src/assets/favicon/favicon-v3.svg` |
| Desktop Android adaptive template | `packages/desktop/icons/prod/android/` (XML only in git; PNG mipmaps are generated and gitignored) |
| Repo / pin | https://github.com/anomalyco/opencode @ **v1.18.31** (`014614d35b397775e5d397a490fc72368c894ec2`) |

A copy of the SVG is at `frontend/branding/favicon-v3.svg`.
Rendered xxxhdpi preview: `frontend/branding/launcher-xxxhdpi.png`.

## How the Android bitmaps are built

`scripts/generate-launcher-icons.py` paints the SVG’s three axis-aligned rectangles into **RGBA** PNG (stdlib `zlib`, no SVG converter):

- `mipmap-{m,h,x,xx,xxx}dpi/ic_launcher.png` (48–192px, opaque, dark field included)
- `mipmap-{m,h,x,xx,xxx}dpi/ic_launcher_round.png` (same mark, circular alpha mask)
- `mipmap-{m,h,x,xx,xxx}dpi/ic_launcher_foreground.png` (108–432px adaptive foreground)

The glyph is scaled to **62%** of the 512 viewport and centered so the Android adaptive-icon safe zone (center ~66%) still shows the dark field. Without that scale, OEM circular/squircle masks crop to the white window chrome and look like a blank white tile.

Adaptive icons (`mipmap-anydpi-v26`) use background `#131010` and the PNG foreground.

Regenerate:

```bash
python3 scripts/generate-launcher-icons.py
```

Do not replace this with a custom shell glyph or a vector-only evenOdd path.

## HyperOS / Xiaomi launcher cache

HyperOS caches launcher icons by package name. After installing a new APK:

1. Uninstall OpenCode completely (not just update-over-install).
2. Reboot if the old white tile is still in the drawer.
3. Install the new APK (`versionCode` must be higher than the previous build).
