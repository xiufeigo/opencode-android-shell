# Wave 2.1 → Wave 2.2/2.3 handoff (design-token recalibration)

Author: Wave 2.1 agent. Scope: `ui/theme/*.kt` + 5 motion components only.
Everything below needs an edit **outside** that scope — do not re-audit, just apply.

## 1. Markdown / Markwon syntax colors are still the legacy ramp (A1 #7/#8 follow-up)

- Files: `app/src/main/res/values/colors_oc.xml:36-44`, `app/src/main/res/values-night/colors_oc.xml:36-44` (FORBIDDEN to Wave 2.1 — `res/**`).
- The Markwon code-block palette is baked at build time from those XML resources
  (`ui/session/timeline/MarkdownStream.kt:42` documents the night-resource split).
- Current (legacy `--syntax-*` from `theme.css:313-322` light / `:584-593` dark)
  vs correct (oc-2 v2 ramp from `oc-2.json` `light.overrides:36-45` /
  `dark.overrides:287-297`; ramp hex in `tokens/colors.css`):

| token | file:line | current | correct | v2 source |
|---|---|---|---|---|
| `oc_syntax_keyword` light | `values/colors_oc.xml:41` | `#5C5C5C` (text-weak) | `#C83D8B` (pink-800) | `oc-2.json light.overrides: syntax-keyword → var(--v2-pink-800)`; `colors.css:166` |
| `oc_syntax_string` light | `:38` | `#006656` | `#198B43` (green-800) | `syntax-string → var(--v2-green-800)`; `colors.css:110` |
| `oc_syntax_primitive` light | `:40` | `#FB4804` | `#C83D8B` (pink-800) | `syntax-primitive → var(--v2-pink-800)` |
| `oc_syntax_property` light | `:36` | `#ED6DC8` | `#D16427` (orange-800) | `syntax-property → var(--v2-orange-800)`; `colors.css:82` |
| `oc_syntax_type` light | `:37` | `#596600` | `#5230C2` (purple-800) | `syntax-type → var(--v2-purple-800)`; `colors.css:152` |
| `oc_syntax_variable` light | `:43` | `#161616` (text-strong) | keep or map to textBase — no v2 token, upstream `syntax-variable: var(--text-strong)` (`theme.css:319`) |
| `oc_syntax_punctuation` light | `:44` | `#5C5C5C` | keep — no v2 token, upstream `var(--text-base)` (`theme.css:323`) but oc-2 light `text-base` is `#6F6F6F` (`oc-2.json:20`); decide in Wave 2.3 |
| `oc_syntax_comment` light | `:42` | `#5C5C5C` | correct already (`syntax-comment → var(--v2-text-text-muted)` = grey-700 `#5C5C5C`) |
| `oc_syntax_constant` light | `:39` | `#007B80` | correct already (unique literal, both files agree) |
| `oc_syntax_keyword` dark | `values-night/colors_oc.xml:41` | `#8F8F8F` | `#F799C6` (pink-400) | `oc-2.json dark.overrides: syntax-keyword → var(--v2-pink-400)`; `colors.css:162` |
| `oc_syntax_string` dark | `:38` | `#00CEB9` | `#96E3A6` (green-400) | `syntax-string → var(--v2-green-400)`; `colors.css:106` |
| `oc_syntax_primitive` dark | `:40` | `#FFBA92` | `#F799C6` (pink-400) | `syntax-primitive → var(--v2-pink-400)` |
| `oc_syntax_property` dark | `:36` | `#FF9AE2` | `#FFC1A4` (orange-400) | `syntax-property → var(--v2-orange-400)`; `colors.css:78` |
| `oc_syntax_type` dark | `:37` | `#ECF58C` | `#9E99F7` (purple-400) | `syntax-type → var(--v2-purple-400)`; `colors.css:148` |
| `oc_syntax_comment` dark | `:42` | `#8F8F8F` | `#AEAEAE` (text-muted grey-500) | `syntax-comment → var(--v2-text-text-muted)` = dark grey-500 (`theme.css:403`) |
| `oc_syntax_constant` dark | `:39` | `#93E9F6` | correct already |
| inline code fg light | `oc_code_inline_fg` (`:32`) | `#263FA9` (blue-1000) | `#2C47C8` (blue-900 = `text-code-accent`) | `theme.css:27` |

- Note: `OcColors.syntaxKeyword/Property/Type/String/Primitive/Comment/Weak`
  (theme-level, Wave 2.1 fixed in `OcTheme.kt`) and the Markwon XML palette are
  **two independent copies** — keep them in sync when editing.
- Downstream Compose consumers already read the fixed theme values, no change
  needed there: `PartRenderer.kt:397` (`syntaxString`), `MentionColors.kt:22-24`
  (property/type/keyword), `TerminalPanel.kt:704-713`.

## 2. `TextShimmer` call sites now inherit fonts (Wave 2.1 changed defaults)

- File: `ui/components/TextShimmer.kt` — `fontSize` default is now
  `TextUnit.Unspecified` and `fontWeight` is `null`, merged over
  `LocalTextStyle.current` (upstream `font/letter-spacing/line-height: inherit`,
  `text-shimmer.css:22-24`). Explicit call-site args still win.
- Existing call sites that pass explicit `fontSize = 13.sp` + `FontWeight(530)`
  are unaffected; if Wave 2.3 wants pure inheritance, drop those args at:
  `ui/session/timeline/ContextToolGroupCard.kt:164-170`,
  `MessageTimeline.kt:623-629`, `PartRenderer.kt:252-259,468-474`.
  (`PartRenderer.kt:749-755` uses `textMuted/W400` — leave it, retry row style.)

## 3. New tokens available for adoption (no consumer wired by Wave 2.1)

- `OcColors.textContrast`, `textCodePath`, `iconFaint`, `iconContrast`,
  `iconAccentHover`, `borderInverse`, `bgCodePath`, `bgIconButtonContrast`,
  `shimmerBase`, `shimmerPeak` — light+dark values set in `OcTheme.kt` per
  `tokens/theme.css:12,24,28,33-35,43,167-168` + `:389-424` and `oc-2.json`.
- `OcElevation.buttonNeutral/buttonContrast/elements/switchOff/switchOn` +
  `OcShadow.ring` + `Modifier.ocRing()` / `Modifier.ocOverlayShadow()` in
  `ui/theme/OcShadows.kt`. Suggested wiring (other agents' files):
  neutral/contrast buttons → `buttonNeutral/buttonContrast` + `ocRing`;
  switch track → `switchOff/switchOn`; cards/popovers add `.ocRing(shadow, shape)`.
- `OcType.lineHeightTight` (12sp, `tokens/theme.css:138`) for 11px tooltip/meta text.
- `OcMotion.exitOpacitySpec(TOAST)` = 160ms for toast opacity; dropdown/popover
  `exitSpec` now returns 150ms + `enterEasing` (ease-out) per
  `menu.css:197-199`, `popover.css:22-28`, `hover-card.css:26-32`.

## 4. Known facilitations / deviations for reviewers

- `OcMotion.Overlay.TOAST` is `(enterMs=280, exitMs=280)` with
  `opacityExitMs=160`: `exitSpec` drives transform/height (280ms), new
  `exitOpacitySpec` drives opacity (160ms) per `toast.css:28-32`. No Compose
  caller uses `TOAST` yet (`DialogHost` only maps `PALETTE`/`DIALOG`);
  `allWithinRhythm()` excludes `TOAST` explicitly.
- `allWithinRhythm()` also excludes `DROPDOWN`/`POPOVER` exits (150ms ease-out
  per keyframes above, outside the old 80–100ms exit band). Unit test
  `OcMotionM6Test` updated accordingly (new `dropdown popover…` test + TOAST
  assertions); everything else in that file untouched.
- Dark `elevation-elements`/`switch-off`/`switch-on` are shared with light
  (identical dark/light token values in `theme.css:242-251` vs `:498-504`).
- `borderFocus` kept `#7698fd` (blue-500) in both schemes per oc-2
  (`theme.css:44,181,298,424`); the file's older `borderFocus` was already that.
- `bgIconButtonContrast` dark = grey-400 `#dbdbdb`, **not** `contrast`
  (light aliases `bg-contrast`; dark points at grey-400) — `theme.css:397`.
- `shimmerBase/shimmerPeak` expose the **light-scheme** legacy pair
  (`#8F8F8F`/`#171717`, `theme.css:165,167`) because `OcColors` has no legacy
  per-scheme text tokens; dark shimmer inherits them until a per-scheme legacy
  text token exists (Wave 2.3 may add `textWeak/textStrong`).
