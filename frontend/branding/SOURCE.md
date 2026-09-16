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

## How the Android bitmaps are built

`scripts/generate-launcher-icons.py` paints the SVG’s three axis-aligned rectangles into PNG (stdlib `zlib`, no SVG converter):

- `mipmap-{m,h,x,xx,xxx}dpi/ic_launcher.png` and `ic_launcher_round.png` (48–192px)
- `mipmap-{m,h,x,xx,xxx}dpi/ic_launcher_foreground.png` (108–432px, full mark including the dark field)

Adaptive icons (`mipmap-anydpi-v26`) use background `#131010` and the **PNG** foreground. The dark field is inside the foreground bitmap so OEM launchers that ignore the background layer (seen on HyperOS as a white tile + tiny gray bar) still show the real logo.

Regenerate:

```bash
python3 scripts/generate-launcher-icons.py
```

Do not replace this with a custom shell glyph or a vector-only evenOdd path.
