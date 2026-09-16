# OpenCode launcher branding

Launcher icon and in-app mark are the official OpenCode “window” glyph (dark `#131010` field, white frame, gray inner pane).

## Source

| Item | Upstream |
| --- | --- |
| Canonical SVG | [`packages/app/public/favicon-v3.svg`](https://github.com/anomalyco/opencode/blob/v1.18.31/packages/app/public/favicon-v3.svg) |
| Same artwork | `packages/ui/src/assets/favicon/favicon-v3.svg` |
| Desktop Android adaptive template | `packages/desktop/icons/prod/android/` (XML only in git; PNG mipmaps are generated and gitignored) |
| Repo / pin | https://github.com/anomalyco/opencode @ **v1.18.31** (`014614d35b397775e5d397a490fc72368c894ec2`) |

A copy of the SVG is kept at `frontend/branding/favicon-v3.svg` so the Android vector can be regenerated without cloning.

`app/src/main/res/drawable/ic_launcher_foreground.xml` traces the SVG paths on a 512×512 viewport. Adaptive background is `#131010` (the SVG canvas fill). minSdk 26, so launcher uses adaptive icons only.

Do not replace this with a custom shell glyph.
