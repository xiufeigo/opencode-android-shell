# OpenCode launcher branding

HyperOS/MIUI repeatedly composed our `adaptive-icon` XML into a **white rounded
plate with a tiny inner glyph**. 1.3.7 ships **legacy-only** launcher bitmaps:

- No `mipmap-anydpi-v26` adaptive XML (OEM cannot layer background/foreground).
- `android:icon` and `android:roundIcon` both point at the **same** full-canvas
  opaque PNG: official favicon-v3 drawn edge-to-edge on `#131010`.
- No transparent margins, no 72/108 safe-zone shrink, no vector, no `@color` background.

The system launcher applies its own squircle/circle mask to the bitmap, same as
a normal full-bleed app icon.

## Source

| Item | Upstream |
| --- | --- |
| Canonical SVG | [`packages/app/public/favicon-v3.svg`](https://github.com/anomalyco/opencode/blob/v1.18.31/packages/app/public/favicon-v3.svg) |
| Repo / pin | https://github.com/anomalyco/opencode @ **v1.18.31** |

Copies: `frontend/branding/favicon-v3.svg`, preview `frontend/branding/launcher-xxxhdpi.png`
and `frontend/branding/launcher-squircle-white.png`.

## How the Android bitmaps are built

`scripts/generate-launcher-icons.py`:

- `mipmap-*/ic_launcher.png` — opaque official mark
- `mipmap-*/ic_launcher_round.png` — **identical** opaque bytes (not a circular hole)

Regenerate: `python3 scripts/generate-launcher-icons.py`

After assemble: `python3 scripts/check-apk-launcher-icon.py app/build/outputs/apk/debug/app-debug.apk`

## HyperOS cache

Uninstall OpenCode completely, then install the new APK (`versionCode` must increase).
Overlay-install keeps the previous (adaptive) icon.
