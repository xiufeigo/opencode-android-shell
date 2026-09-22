# Fix: table-only assistant message renders blank (TableView zero-size)

Date: 2026-09-21 (UTC). Symptom: assistant message whose markdown is exactly one
GFM table renders completely blank (session `ses_f41f7138cffeB43nHxQAyNE1uf`);
persists after cold restart; no crash; uiautomator has no node with the text;
parser unit tests pass.

## Root cause

`TableView` (`app/.../ui/markdown/MarkdownRenderer.kt`, Wave 2.2a) measured to
zero size. It put `Row(fillMaxWidth)` with `weight(1f)` cell `Text`s inside
`Box(...horizontalScroll(...))`. A horizontal-scroll wrapper measures its
content with **unbounded** width constraints, and in an unbounded `Row`,
`weight()` children resolve to **zero width**. Every header/body cell therefore
measured 0 dp: nothing painted, and the zero-bounds text nodes never entered
the accessibility tree (hence no uiautomator node), with no crash and no
logcat signal. Table-only messages go fully blank; plain paragraphs never hit
this path (no weight, no scroll), and parsing was never at fault.

## Fix (files owned by this task)

- `app/.../ui/markdown/TableLayout.kt` (new): `tableColumnWidth(viewportWidth,
  columns, minColWidth = 96.dp)` — even split floored at a scrollable minimum,
  with degenerate/infinite-viewport fallback to the minimum (never zero,
  never infinite). Pure arithmetic, JVM-tested.
- `app/.../ui/markdown/MarkdownRenderer.kt`: `TableView` now sizes via
  `BoxWithConstraints` (bounded `maxWidth` from the timeline item) with fixed
  `width(colWidth)` cells and `width(colWidth * columns)` rules. Equal widths
  keep header/body columns aligned across their separate rows (the reason
  weight was chosen); narrow tables fill the viewport; wide tables scroll
  (upstream `table { display: block; overflow-x: auto }` fallback). The
  `widthIn(min = 320.dp)` crutch and the `weight(1f)` cells are gone.
- `app/src/test/.../TableRenderTest.kt` (new, 6 cases): exact repro text parses
  to one `Table` block (3+3), plus `tableColumnWidth` split/floor/degenerate
  cases.

## Not the bug (ruled out)

- `OcMarkdown` 80 ms `coalesceLatest` pipeline: static post-restart content
  initializes `rendered = markdown` directly; parse yields the `Table`.
- `PartRenderer` text branch (`isNotBlank` passes) and the empty-reasoning
  branch (renders nothing by design) — no sequence corruption.
- `MarkdownStream.kt` untouched.

## Verification status

- Baseline (pre-fix) device evidence: `research/v2_verify_shots/`
  `baseline_table_blank.png` + uiautomator dump (user prompt present ×2, zero
  table-cell nodes).
- Standalone harness (repo tree red, see below): compiled the **real**
  `MarkdownParser.kt` + `TableLayout.kt` + `TableRenderTest.kt` with
  `kotlin-compiler-embeddable-2.0.21` (stdlib/coroutines/trove4j/annotations
  jars from the Gradle cache; only `androidx.compose.ui.unit.Dp` stubbed with
  a semantics-faithful mirror — the real `Dp` operators used were verified by
  `javap`: `div(Float/Int)`, `times(Float/Int)`, `compareTo`, `value`) and ran
  JUnit: **new `TableRenderTest` 6/6 OK, existing `MarkdownTableTest` 15/15 OK
  (21/21)**. Harness notes: the hand-rolled kotlinc run needs jetbrains
  `annotations-13.0.jar` on the *compiler's own* classpath (else backend
  codegen crashes — the same env issue wave2.2a hit), and the android
  `ui-unit-release.aar` classes' `DpKt` facade metadata is unreadable to
  standalone kotlinc (`Int.dp` unresolvable — hence the stub; real Gradle
  builds resolve it from the full transformed classpath).
- `testDebugUnitTest` / `assembleDebug` / device proof: **BLOCKED — tree does
  not compile** due to in-flight parallel-agent edits in forbidden files
  (`v2/*`: `SessionTransport.kt` + `V2Capability.kt` syntax errors,
  `V1Transport`/`V2Transport`/`V2Api` unresolved refs; root-package
  `ChatStore.kt` unresolved `FormAsk`). Still red after ~45 min / 5 polls with
  errors churning (refactor converging but not landed). **Zero compile errors
  reference any file this fix owns** (verified by grepping compiler output on
  every poll).
- RESUME (parent): once the tree is green, run
  `JAVA_HOME='/c/Program Files/Android/Android Studio/jbr' ./gradlew
  --offline testDebugUnitTest` (gate: 1165+ tests incl. new `TableRenderTest`),
  then `./gradlew assembleDebug` (drop `--offline` if lint-gradle resolution
  fails), `adb -s emulator-5556 install -r
  app/build/outputs/apk/debug/app-debug.apk`, relaunch with `adb -s
  emulator-5556 shell monkey -p com.xiufeigo.opencodeshell 1` (route restores
  to the android-e2e session; adb reverse for 4096 already set), and capture
  `adb -s emulator-5556 exec-out screencap -p >
  research/v2_verify_shots/fix_table_render.png`. Expect the 3×3 table above
  the `· 14 秒` meta footer. (Set `MSYS_NO_PATHCONV=1` for any adb shell
  absolute-path use under Git Bash.)
