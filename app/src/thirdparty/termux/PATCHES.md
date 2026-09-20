# Vendored termux patch log (M5 phase 2, A′)

Upstream: **termux/termux-app `v0.117`**, commit `9272a757affc0dae4aa044248800f3987e06c52b`
(the exact commit JitPack built `com.github.termux.termux-app:{terminal-view,terminal-emulator}:v0.117`
from — verified by comparing `javap` output of the real AAR with the source: the
`TerminalEmulator(TerminalOutput, int, int, Integer, TerminalSessionClient)` 5-arg constructor and the
`TerminalViewClient`/`TerminalSessionClient` member lists match **exactly**, so `research/06` §1.5 A-2
版本漂移 does not apply to this pair).

Licence: **Apache-2.0** (`LICENSE.md` in the parent directory, copied verbatim; the repository-wide
GPLv3-only grant carries an explicit exception for these two modules). `termux-shared` was **not**
vendored.

Layout (`app/src/thirdparty/termux/`, wired in `settings.gradle.kts` as `:terminal-emulator` and
`:terminal-view` so the sources travel with the app module):

```
app/src/thirdparty/termux/
├── LICENSE.md                      upstream, verbatim
├── terminal-emulator/              namespace com.termux.terminal, NO externalNativeBuild
│   ├── build.gradle.kts            replaces upstream build.gradle
│   └── src/main/java/com/termux/terminal/*.java
└── terminal-view/                  namespace com.termux.view
    ├── build.gradle.kts            replaces upstream build.gradle
    └── src/main/java/com/termux/view/**  (+ res/drawable, res/values/strings.xml)
```

Removed from the vendored copy: `src/main/jni/` (`Android.mk`, `termux.c`), `src/test/` (upstream JUnit
suite, unused), upstream `build.gradle` files.

## Added files

| File | Why |
|---|---|
| `terminal-emulator/…/TerminalIo.java` | **new**: the 4-member transport contract (`write(byte[])`, `updateSize(int,int,int,int)`, `getEmulator()`, `reset()`). `TerminalSession` is *not* an implementation of it — `TerminalOutput.write(byte[],int,int)` plus `TerminalIo.write(byte[])` would make the unqualified two-arg call in `writeCodePoint` ambiguous (documented in the class javadoc). |
| `terminal-view/build.gradle.kts`, `terminal-emulator/build.gradle.kts` | AGP 8 Kotlin-DSL, `namespace` instead of the manifest `package` attribute, `compileSdk 35` / `minSdk 26` / Java 17, `lint.abortOnError = false`, **no** `externalNativeBuild`. |

## Modified files (the whole patch)

### `terminal-emulator`

| File | Change |
|---|---|
| `JNI.java` | **emptied**: `System.loadLibrary("termux")` and all four `native` methods removed, keeping the type as an empty `final` class so the vendored file list still mirrors upstream. |
| `TerminalSession.java` | **hollowed**: process spawn, PTY fd, `Handler` screen pump, the three pump threads and `cleanupResources` removed; `updateSize` only re-flows the emulator, `write(byte[],int,int)` is inert. Kept as a *type only* (nothing in this repository constructs one) so `TerminalSessionClient` / `TerminalViewClient` signatures ported from upstream master still compile. `writeCodePoint` keeps upstream's exact UTF-8 encoder; a static `encodeCodePoint(boolean,int)` was added for the view patch. |

### `terminal-view`

| File | Change | Line count |
|---|---|---|
| `TerminalView.java` | new public field `mIo`; `mTermSession` deprecated (always `null` in remote form); **new `attachIo(TerminalIo)`**; **new `detachFromTerminalHost()`**; **new `copyTextToClipboard(String)`**; 6 input call sites switched from `mTermSession` to `mIo` (`onKeyDown` ACTION_MULTIPLE, `inputCodePoint`, `handleKeyCode`, `autofill`), `updateSize()` now calls `mIo.updateSize(cols, rows, cellW, cellH)` and the `mTermSession == null` guards became `mIo == null` | ~120 (≈60 code) |
| `TerminalRenderer.java` | + `getFontLineSpacingAndAscent()` — upstream kept that field package-private while its two siblings were public, so an outside caller could not reproduce the grid math | +12 |
| `TerminalViewClient.java` | javadoc only: the `TerminalSession` parameter of `onKeyDown`/`onCodePoint` is always `null` in the remote form | javadoc |
| `textselection/TextSelectionCursorController.java` | COPY called `terminalView.mTermSession.clipboardText(…)` → guaranteed NPE with no local session; now `terminalView.copyTextToClipboard(…)` | 1 line |

`TerminalEmulator`, `TerminalBuffer`, `TerminalRow`, `TextStyle`, `WcWidth`, `ByteQueue`,
`KeyHandler`, `TerminalColors`, `TerminalColorScheme`, `TerminalOutput`, `TerminalSessionClient`,
`GestureAndScaleRecognizer` and the rest of `textselection/` are **byte-identical to upstream**.

## Why only six substitution points

`TerminalEmulator` only ever knows a `TerminalOutput` (`research/06` §1.4), and `TerminalView` only
ever *wrote* through three `TerminalSession` methods. The patch therefore replaces exactly those and
leaves the IME `InputConnection`, `handleKeyCode`, gestures, text selection, scrolling, the cursor
blinker and the renderer untouched — no behavioural fork of upstream logic.

## Reproduce the upstream diff

```powershell
# from the repository root
Invoke-WebRequest https://github.com/termux/termux-app/archive/refs/tags/v0.117.zip -OutFile $env:TEMP\t.zip
Expand-Archive $env:TEMP\t.zip -DestinationPath $env:TEMP\t
# then diff $env:TEMP\t\termux-app-0.117\{terminal-emulator,terminal-view} against
# app\src\thirdparty\termux\{terminal-emulator,terminal-view}
```
