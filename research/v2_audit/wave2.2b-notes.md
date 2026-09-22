# WAVE 2.2b — tool-card & timeline-row fidelity (A3 deltas)

Scope: `ui/session/timeline/**` (except `MarkdownStream.kt`) + `ui/session/dock/**`.
Owner: wave-2.2b agent. Date: 2026-09-21.

Upstream anchors (all read-only, never edited):
- `_upstream/packages/session-ui/src/tools/tool-renderer.tsx` (2202 lines — all 15 renderers + `ToolErrorCard`)
- `_upstream/packages/session-ui/src/components/tool-error-card.tsx`, `basic-tool.tsx`
- `_upstream/packages/session-ui/src/components/{file,file-media,image-preview,message-nav,tool-count-label,tool-count-summary}.tsx`
- `_upstream/packages/session-ui/src/timeline/{projection.ts,detail.ts,timeline-row.ts}`
- `_upstream/packages/session-ui/src/message/{message-content.tsx,current-tool-state.ts}`
- `_upstream/packages/session-ui/src/components/session-diff.ts`, `tools/shell-output.ts`

## A3 deltas — fixed by this wave

| A3 # | Delta | Fix | Files |
|------|-------|-----|-------|
| 9 | edit fake diff (whole file `-`/`+`) | `ToolPresentation.hunkDiffLines` — Myers O(ND) edit script (LCS DP on the trimmed middle; prefix/suffix fast paths), changed-line windows widened by `HUNK_CONTEXT = 4` (jsdiff default) and merged on overlap, `@@ -a,b +c,d @@` headers; pure add/delete renders headerless. `diffLines` kept for the flat pair + old tests. `patchLines` `@@` headers re-typed `HUNK`. | `ToolPresentation.kt` |
| 11 | write rendered as diff | `write` body is now plain `TEXT` lines (upstream `mode="text"`). `isEditLike` still lists `write` for policy (unchanged). | `ToolPresentation.kt` |
| 7 | shell ANSI escapes | `ToolText.stripAnsi` (CSI + OSC) + `shellOutput` (strip + `\\r\\n?` normalise); `bodyLines` uses it for bash/shell/**execute** (execute also reads `code` for the `$` header). | `ToolText.kt` (new), `ToolPresentation.kt` |
| 13 | ToolErrorCard not ported | `ToolErrorCard` composable: tool title + subtitle + danger `": "`-split summary head (`ToolText.errorHeadDetail`: `Error:` strip, `{tool} ` strip, capitalise, `Failed` fallback); detail + copy button (copies `cleaned`, 2 s check) behind the toggle; `jumpTarget: String? = null` + `onJumpToSession: (String) -> Unit = {}` — **no-op hook**. `subtitle()` now transcribes `toolErrorSubtitle` (`:1265-1289`) so failed rows read like their success counterparts. | `ToolRenderers.kt` (new), `ToolPresentation.kt` |
| 8 | shell live-follow + copy | `ShellBody`: `ConsoleOutput` surface, copy button copies the **command**; `liveOutput: String? = null` hook replaces the snapshot while running (`saved ?? streamed` precedence once wired). Loader (`shellOutput(id)` polling per `tools/shell-output.ts`) not wired — store gap, see below. | `ToolRenderers.kt` (new) |
| 16 | subagent/task downgraded | `SubagentCard`: agent-tinted title (`agentTone` — theme `agentPlan/Build/Explore/Review/Writer`, unknown names hash deterministically via `agentSlot`, port of `tone()`), description subtitle + ` (background)`, spinner while running, `Delegating work` pill while streaming, chevron only when `childSessionId != null`. `childSessionId/childRunning/background/onJumpToSession` all defaulted — **no-op until wired**. `task` maps alongside `subagent`. | `ToolRenderers.kt` (new), `ToolModels.kt` (new) |
| 14 | websearch link list | `WebLinksBlock`: `ToolModels.webLinks` (unique http(s) URLs, `ExaOutput.urls()` shape incl. trailing-punct trim), max 10 + `(+n more)`, tap opens the browser, selectable. Card title override `Web Search` (`dedicatedCardTitle`). | `ToolRenderers.kt`, `ToolModels.kt` |
| 15 | read image + Loaded row | `ReadImageRow` (basename row for completed image reads; bitmap fetch is a markdown-agent gap), `LoadedFilesRow(loadedPaths, directory)` — `Loaded {relativised, comma-joined paths}` (`ToolModels.loadedFiles`); empty list renders nothing. | `ToolRenderers.kt`, `ToolModels.kt` |
| 17 | question card | `QuestionCard`: hides while streaming/running (`HIDDEN`); completed cards default open, `{count} answered` subtitle, per-question Q + `, `-joined answers echo (`(no answer)` fallback). `answers` defaults empty (store gap). | `ToolRenderers.kt`, `ToolModels.kt` |
| 18 | skill Loaded row | `SkillRow`: `Loaded {name}` (`metadata.name ?? input.id ?? input.name`; store has no metadata so input only), name shimmers while running; nameless falls back to `Skill` shimmer. | `ToolRenderers.kt`, `ToolModels.kt` |
| 25 | compaction static line | `CompactionBlock`: summary through muted markdown, `running` shimmer hook, `error` card hook; empty summary keeps the old `Session compacted` fallback. Dispatch in `PartRenderer` now calls it (the old `CompactionRow` stays as an alias). | `ToolRenderers.kt`, `PartRenderer.kt` |
| 26 | error JSON wall | `ToolText.unwrapErrorMessage` (transcribed `:730-759`: `Error:` strip, double-decode, `error.{type,message}`, `message`, string `error`, else input back); `SessionErrorCard` header shows the unwrapped first line, full raw text stays behind the chevron. | `ToolText.kt` (new), `SessionErrorCard.kt` |
| — | diagnostics column (A3-10, unnumbered in the priority list but P1) | `DiagnosticsBlock` + `ToolModels.diagnostics` (severity-1 only, max 3, 1-based `[line:col]`); `diagnosticsJson` defaults null (store gap). **Not yet attached under edit/write cards** — needs `metadata.diagnostics`, so the call is the main agent's one-liner once the store carries it. | `ToolRenderers.kt`, `ToolModels.kt` |
| 35 (P2) | nav-rail parity items | `NavPreview` (label + optional `{additions,deletions}`) + `ToolModels.diffBars` (transcribed 5-cell bucketing incl. ratio/volume rules) + `ToolCountChips` static `AnimatedCountList` counterpart fed by `ToolModels.countChips` (title multiplicity). Chips render under multi-title group headers; rail previews/diff-bars are caller hooks. | `MessageNavRail.kt`, `ToolModels.kt`, `ToolRenderers.kt`, `ContextToolGroupCard.kt` |

## Also changed (same files, no behaviour break)

- `PartRenderer.ToolBody`: dispatches `toolRendererKind(part)` first (HIDDEN/ERROR/SHELL/SUBAGENT/QUESTION/SKILL/READ_IMAGE/READ_LOADED/WEBSEARCH/GENERIC); group members get the same headline-only policy for streaming-subagent/skill/read-image; `HUNK` headers get `layer01` bg + faint fg, `CONTEXT` muted fg (review-panel mapping); `write`/`TEXT` lines render base; `GenericBody` keeps JSON/output children under subagent/read/websearch cards.
- `PartRenderer` + `ContextToolGroupCard` triggers: `websearch` title override.
- `ContextToolGroupCard` group label: chips under multi-title headers.
- `ToolPresentation.LineKind`: + `CONTEXT`, `HUNK`, `TEXT`; + `isDiffKind`, `basename`.

## Store data gaps (why hooks default to null/empty — NOT rendering gaps)

`ChatStore.UiPart` keeps `toolInput/toolOutput/toolError` but **no `state.metadata`**.
Unobservable here: `metadata.loaded` (read), `metadata.sessionID` (subagent jump),
`metadata.answers` (question), `metadata.provider` (websearch title),
`metadata.diagnostics`, `metadata.files/filediff`, `metadata.shellID/status`
(live follow), compaction status/error. Every affected renderer takes the
missing piece as a defaulted parameter so the main agent wires it without
touching this wave's files once the store carries metadata. Sub-session
navigation (`navigateToSession`) does not exist natively yet — both jump hooks
are no-ops with `TODO`-equivalent notes in kdocs.

## Deferred (out of scope / parallel owner)

- A3-5 mermaid, A3-4 code highlight, A3-6 pacing, A3-1 tables — parallel markdown agent (`ui/session/markdown/**`, `MarkdownStream.kt` untouched).
- Composer/queue/mention (A3-2/3), review comments, docks beyond question/permission, header/palette/settings — other waves. `ui/session/dock/**` examined: permission/question docks already at parity incl. W6 notes; no change needed.
- A3-19 file-group, A3-20 standalone-running, A3-21 cross-message groups, A3-22 presets, A3-23 thinking row, A3-24 pending/steer, A3-27 retry countdown, A3-28 per-message revert, A3-30 websearch dock, A3-31 background tasks, A3-32 child sessions, A3-33 project menu, A3-34 turn diffs, A3-36 ring behaviour, A3-37 glyph search, A3-38 user images, A3-39 64ch cap, A3-40 turn duration, A3-41 shell role, A3-42 divider placement, A3-43..46 composer details — not in this wave's item list; `NavPreview`/`countChips`/`diffBars` hooks are the integration seams for 34/35.

## Tests

- New: `app/src/test/.../Wave22bToolFidelityTest.kt` (30 tests) — hunk headers/context/split/merge/minimality, write-as-text, ANSI strip, execute `code` header, error subtitles, head/detail split, unwrap (nested/prefixed/double-encoded/plain), dispatch incl. question-hide + read-image gating, image-path filter, loaded relativise, URL dedupe, skill/subagent/question models, agent slots, diagnostics cap, compaction kinds, count chips, diff bars.
- Existing files untouched: `ToolPresentationTest`, `ToolCardPolicyTest`, `TimelineGroupingTest`, `PatchBodyLinesTest`. Two intentional deltas vs old expectations: (1) `edit` card renders `hunkDiffLines` (old `diffLines` flat-pair tests still pass — the function is kept); (2) shell output is now stripped (old shell tests use plain text — unaffected).
- `ToolRendererCopy`: English literals live in code, not `res/` (out of scope). The main agent should add `R.string` keys per the kdoc table (`oc_tool_copy_error`, `oc_tool_delegating`, `oc_tool_open_session`, `oc_tool_questions`, `oc_tool_skill`, `oc_tool_loaded`, `oc_tool_websearch`, `oc_compaction_running`, `oc_tool_error_card_failed`, `oc_question_answer_none`, `oc_tool_more_links`, `oc_question_answered`) and swap the references.

## Build

- `JAVA_HOME=/c/Program Files/Android/Android Studio/jbr ./gradlew --offline :app:compileDebugKotlin` — **this wave's files compile clean** (zero errors in `timeline/*`, verified by filtered output).
- Full-module and `testDebugUnitTest` gates are **red on `main` independently of this wave**: pre-existing breakage in `ui/home/*`, `ui/nav/*`, `ui/App.kt`, `ui/markdown/MarkdownRenderer.kt` (parallel agents' work in progress — e.g. `SessionTabs.kt` BOM/encoding errors, 1011 errors in that file alone). The mandated `compileDebugKotlin testDebugUnitTest` green gate cannot pass until those are fixed; none of the errors touch this wave's files. Retry-on-lock: N/A (no lock timeouts seen; `JAVA_HOME` must be the Studio `jbr`, **not** quoted Windows style — the JDK 25 default breaks the Kotlin daemon with `IllegalArgumentException: 25.0.4.1`).

## SessionScreen.kt integration notes (main agent)

1. `PartRenderer`/`ContextToolGroupCard` already dispatch internally — no change needed for the default path.
2. When the store gains `state.metadata`, thread it through (all params exist with defaults): `ToolErrorCard(part, jumpTarget, onJumpToSession)`, `ShellBody(part, liveOutput)` (wire a `shellOutput(id)` poller per `tools/shell-output.ts`: 64 KiB tail, 1 s poll while running, saved-snapshot-wins), `SubagentCard(part, childSessionId, childRunning, background, onJumpToSession)`, `QuestionCard(part, answers)`, `LoadedFilesRow(paths, directory)`, `DiagnosticsBlock(path, diagnosticsJson)`, `CompactionBlock(part, running, error)`, `WebLinksBlock` needs nothing.
3. Attach `DiagnosticsBlock(path, json)` under expanded edit/write bodies once `metadata.diagnostics` is available.
4. Suggested P2 slots: `ToolCountChips` under expanded group headers (done for collapsed); `NavPreview` rows + `TurnChanges`-sourced `changes` for the rail hover-card equivalent; move `ToolRendererCopy` literals into `strings.xml` (keys listed above).
5. `question` while streaming/running now renders **nothing** (was a generic card) — confirm the dock covers the ask UI so no question vanishes visually.
6. `execute` tools now use the shell renderer (`$ code` + stripped output + command copy).
