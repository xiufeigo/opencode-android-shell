# WAVE 2.2a — markdown rendering fidelity (tables + syntax highlight + code chrome)

Owner: markdown agent. Date: 2026-09-21 (UTC).
Binding work order: `research/v2_audit/A3-session-view.md` markdown rows +
`research/v2_audit/README.md` P0-6/P0-9 + `research/v2_audit/wave2.1-handoff.md`
(theme-token handoff; landed before this wave — `OcColors.syntax*` and the
`oc_syntax_*` XML palette already carry the v2 ramp, so this wave only *reads*
them and changes no token value).

## v2 references (read-only)

- Code path: `_upstream/packages/session-ui/src/components/markdown.tsx`
  (copy button 2000 ms, `shellLanguages` set `:203`, `decorateMermaid`
  `:220-259`, `data-language` metadata), `markdown-stream.ts` (streaming
  projection: only the tail `live`/`code` block re-renders), `markdown-worker.ts`
  + `markdown-code-state.ts` (stable/unstable incremental tokens, generation
  bump on reset), `markdown-cache.tsx`, `markdown.css` (all geometry below),
  `_upstream/packages/ui/src/context/marked-theme.tsx` (`OpenCode` Shiki theme →
  legacy `--syntax-*` vars, resolved to the v2 ramp via `oc-2.json` overrides).
- Colors: `oc-2.json` light/dark `overrides` (`syntax-keyword → v2-pink-800/400`,
  `syntax-string → v2-green-800/400`, `syntax-primitive → v2-pink-800/400`,
  `syntax-property → v2-orange-800/400`, `syntax-type → v2-purple-800/400`,
  `syntax-constant → #007b80/#93e9f6`, `syntax-comment → v2-text-text-muted`)
  resolved through `theme/v2/default-primitives.ts` + `v2/mapping.ts`.
  Native equivalents (already landed, verified — not touched): `OcTheme.kt`
  `syntaxKeyword/Property/Type/String/Constant/Primitive/Comment/Weak` light +
  dark, and `res/values/colors_oc.xml:36-44` + `values-night:36-44`
  (`oc_syntax_*`, incl. `variable`/`punctuation` legacy aliases).
- Tables: `marked` with GFM tables on (`marked-parser.tsx` →
  `createMarkdownBase`); style in `markdown.css:305-328` (`margin: 16px 0`,
  `th,td { border-bottom: 0.5px; padding: 12px }`, `th` additionally W500 +
  `border-base`, `table { display: block; overflow-x: auto }`).
- Shell code: `markdown.css:225-237` forces every token span to `currentColor`
  for `data-code-kind="shell"` — i.e. shell fences are plain upstream too.
- Inline code: `markdown.css:279-303` (`:not(pre) > code` base wash 8 %,
  `data-inline-code-kind="path"` → `text-code-path` no bg, `url` inside
  `a.external-link` → `text-code-accent` + underline).

## What changed (native files owned by this wave)

1. `app/.../ui/markdown/MarkdownParser.kt` — GFM pipe-table parsing:
   header + **adjacent** delimiter row required (matches upstream `marked` GFM:
   a blank line between header and delimiter is not a table); column counts must
   match; alignment `:---:`/`---:`/`:---` accepted, not surfaced — upstream
   left-aligns every cell; escaped pipes (`\|`) preserved for the existing inline
   backslash-unescaper (`ESCAPABLE` already contains `|`); code-span pipes
   (`` `a|b` ``) never split; inline formatting parsed per cell via
   `parseInline`; short rows end the table; `MdBlock.Table(header, rows)` added.
   `startsBlock` gained a delimiter-probe so a header row never swallows the
   preceding paragraph.
2. `app/.../ui/markdown/CodeHighlight.kt` (new) — pure-Kotlin Prism4j tokenizer
   for fenced code. Kind mapping mirrors the syntax-ramp subset of
   `OcPrismTheme.typeColors` (`ChatView` View-era pipeline) so both bodies agree
   on the v2 ramp; `variable`/`interpolation`/`bold`/`italic`/`operator`/
   `punctuation`/`namespace`/`entity` and unknown stay plain (those map to the
   `oc_syntax_variable`/`oc_syntax_punctuation` XML aliases, which the wave2.1
   handoff §1 flags as still-undecided legacy mappings — plain body color
   matches upstream's near-body treatment of those kinds). Shell languages
   (`bash/sh/shell/zsh/fish/console/terminal`, the exact `markdown.tsx:203` set)
   return empty **by design** (upstream renders them plain). Unknown languages
   (no vendored Prism4j grammar: `rust`, `typescript`, `diff`, … —
   `OcGrammarLocator` covers 10) return empty instead of mis-highlighting.
   `MAX_CHARS = 32768` blowup guard. Deterministic full-text re-tokenize ⇒
   prefix-stable colors (no flicker); rate-bounded by the existing 80 ms
   `coalesceLatest` window + `remember` memoization.
3. `app/.../ui/markdown/MarkdownRenderer.kt` —
   `TableView` (16 dp vertical margin, header W500 + `borderBase` bottom rule,
   body `borderMuted` bottom rules, 12 dp cell padding, equal `weight(1f)`
   columns, horizontal scroll **outside** the column grid so header/body widths
   always agree, header via `annotate(..., forceBold = true)`); code chrome
   (11 sp mono language label row + tap-to-copy `IconButton` using the existing
   `ic_oc_copy`/`ic_oc_check` drawables and `oc_md_copy_code`/`oc_md_copied`
   strings, 2000 ms reset, long-press still copies); highlight wiring
   (`remember(code, language, palette)` → `highlightAnnotated`, theme-switch
   re-resolves without re-tokenizing); `annotate` gained `forceBold` for `th`.
   `MarkdownStream.kt` was **not** touched — the pacing approach already covers
   the new work (see below).
4. Tests (new, existing style): `app/src/test/.../MarkdownTableTest.kt`
   (15 cases), `app/src/test/.../CodeHighlightTest.kt` (6 cases incl.
   prefix-stability across 8 grammars). No existing test file modified.

## A3 delta disposition

- A3 #1 (P0 tables) — **fixed** (parse + render per above).
- A3 #4 (P0 code highlight) — **fixed within Prism4j coverage** (10 vendored
  grammars + aliases; exact v2 colors — theme wave already landed the ramp, and
  the XML palette matches `OcColors.syntax*`). Shiki-only languages stay plain
  — recorded deviation. P0-2 (README) is therefore **done on the markdown side**
  (remaining P0-2 work, if any, is theme-owned).
- Code-block header/copy (A3 §71-adjacent / `markdown.css` copy-button rules)
  — **fixed** (label + tap/long-press copy, 2000 ms confirmation; hover-only
  reveal has no touch equivalent).
- Inline code colors (A1 row 9 `text-code-path`/`text-code-accent`,
  `markdown.css:291-303`) — **deferred**: `OcColors.textCodePath`/
  `textCodeAccent` exist (theme wave), but `MdSpan` has no path/url kind and
  threading the 1900-line Linguist-derived `inlineCodeKind` port into the inline
  parser + a new annotated-span style is a self-contained follow-up owned by
  whoever next touches `MarkdownParser.parseInline`/`annotate`. Inline code
  keeps the current body-color + 8 % wash (matches the base `:not(pre) > code`
  rule; only the path/url variants differ upstream).
- A3 #5 (P1 mermaid) — **graceful degradation**: fence renders as a labeled
  `mermaid` code block (same chrome as any other fence), never hidden. Full SVG
  diagram rendering is out of scope (no diagram renderer on hand; would need a
  WebView or a native mermaid port + a new dependency, both forbidden here).
- A3 #6 (P1 streaming pacing, 24 ms `PacedMarkdown`) — **not changed**:
  `MarkdownStream.kt` (80 ms `coalesceLatest`) paces both parse and highlight
  together; per-window full-text re-tokenize is O(block) per window, not O(n²)
  per frame. Finer word-by-word reveal belongs to the timeline wave that owns
  `MarkdownStream.kt`.

## Deviations from upstream (deliberate, documented in code)

- Table column widths are equal (`weight(1f)`, wrapping cells); upstream
  auto-sizes columns to content with `overflow-x: auto` fallback. Rationale in
  `TableView` kdoc: auto-sizing needs a two-pass intrinsic layout Compose
  `Text` does not offer cheaply per streaming frame; the scroll wrapper
  preserves the `display: block; overflow-x: auto` fallback for unbreakable
  content.
- `variable`/`operator`/`punctuation` token kinds render plain in the Compose
  body but tinted (legacy near-body aliases) in the View-era `ChatView` body.
  Both are near-body tones; converging them is blocked on the Wave 2.3
  `oc_syntax_variable`/`oc_syntax_punctuation` decision (wave2.1 handoff §1).
- Mermaid: labeled code block instead of SVG (see above).

## Streaming / perf notes (for the timeline-wave owner)

- `OcMarkdown` flow is unchanged: `snapshotFlow { latest }.coalesceLatest()`
  → `remember(rendered) { parse }`; `CodeBlockView` adds
  `remember(code, language, palette) { tokenize }`. Worst case per 80 ms window
  is one parse + one tokenize per code block; grammars are cached in both
  `OcGrammarLocator` and `CodeHighlight`.
- `CodeHighlight.tokenize` runs on the composition thread (same as
  `MarkdownParser.parse`). Blocks > 32 k chars skip tokenizing. If a future
  profile shows composition jank on huge fences, move the tokenize into the
  existing coalescing pipeline (e.g. compute `highlightAnnotated` alongside the
  parse) — no API change needed (`tokenize` is a pure function).
- `Prism4j(OcGrammarLocator())` is a lazy singleton shared with `ChatView`'s
  `SyntaxHighlightPlugin`; `OcGrammarLocator.grammar()` is synchronized and
  re-entrant-safe (documented there).

## Verification

- New tests: `MarkdownTableTest` (15) + `CodeHighlightTest` (6) — written
  against the contracts above; **not yet executed** (see build gate).
- Existing suites untouched: `TimelineFidelityTest`, `MarkdownStreamTest`
  (incl. the source guards: `.coalesceLatest()`, `remember(rendered)`, no
  `AndroidView`/`io.noties` in the renderer — the new `CodeHighlight` import is
  `com.xiufeigo…ui.markdown`, so the `no Markwon dependency` guard still
  holds). No existing test file modified, per constraints.
- Build gate `JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
  ./gradlew --offline compileDebugKotlin testDebugUnitTest` — **red at handoff,
  not caused by this wave**: 44 compile errors, all in parallel-agent-owned
  files (`ui/home/*` 43 + `ui/App.kt` 1); **zero errors reference
  `ui/markdown/**`, `OcGrammarLocator`, `OcPrismTheme`, the two new test files,
  or any file this wave owns** (verified by grepping the compiler output).
  A standalone `kotlinc` check of the wave's pure-Kotlin files was attempted
  but the hand-assembled compiler classpath crashes in backend codegen even for
  untouched files — environment issue, not a code signal. Re-run the gate once
  the `ui/home` wave lands; this wave's files are gate-ready.

## Handoff items for other waves

- Theme wave: nothing open — ramp already landed; Wave 2.3 may still re-point
  `oc_syntax_variable`/`oc_syntax_punctuation` (handoff §1), at which point the
  Compose body can optionally adopt them in `CodeHighlight.kindOf` (one map).
- Inline-code path/url kinds (`markdown.css:291-303`, needs `MdSpan` kind +
  `inlineCodeKind` port + `annotate` style) — self-contained follow-up for
  whoever next owns `ui/markdown/`.
- Timeline wave: owns `MarkdownStream.kt` and any 24 ms paced-reveal work;
  owns full mermaid SVG rendering if ever scheduled (needs a renderer +
  dependency decision). Table/code-block search-highlight (A3 #37 glyph-level
  hits) will need span-aware hooks in `TableView`/`highlightAnnotated` —
  both are plain `Text` today.
