# v66 — composer popover / editor cluster (real-device round, `emulator-5554`)

Date: 2026-09-29. Build: `:app:assembleDebug` installed on **emulator-5554 only** (Medium_Desktop, 3840×2160, density 320).
Screenshots: `research/v66_fix/5554/`. JVM: `ComposerPopoverV66Test` (new, 12 cases) + the composer/home regression slice, all green.

## 0. What the device actually showed first (this is the load-bearing finding)

Before touching code I re-ran every v65 repro on 5554, **including turning the soft IME on**
(`settings put secure show_ime_with_hard_keyboard 1` — 5554 runs hardware-keyboard mode by default, so
without it the real `InputConnection` path is never exercised). Result:

| v65 report | 5554, pre-fix |
| --- | --- |
| `@` panel never opens | **opens** — empty composer, IME up and down, brand-new session, `+` → Context (shots `04_at`, `18_ime_at`, `16_context`, `23_new_at`) |
| caret pinned at 0, reverse insert | **does not happen** — `Z@v@`+`K` → `Z@v@K` in order, caret at the end (`20_afterK`); mid-text tap inserts mid-text (`13_mid`); `logcat` shows **zero** `restartInput` / `ImeTracker … ATTACH_NEW_INPUT` |
| `/` row click dead | **works** — `/model` opens the model picker, `/undo` and `/redo` clear the draft and close the panel (`08_cmd_model`, `11_undo_click`, `45_redo_pick`) |

So 5554 is not the device that fails. But the 5558 screenshots in `research/v65_verify/5558/` **are** the
evidence, and they are unambiguous — `17_at_clean.png` / `18_at_wait.png` show the composer holding `v@`
with **the caret drawn at offset 0, in front of the `v`**, and no card above the editor. That single state
explains three of the four filed bugs at once:

* a caret at 0 makes backspace and select-all+delete no-ops (**BUG 2**),
* a caret at 0 makes `MentionText.anchorAt` answer `null`, and that null *is* the whole `@` surface
  (**BUG 1** — the "面板完全不弹" and "光标钉死 0" are one bug, not two),
* the same session switch is what leaves the popover's row list drawn from one source of truth and its
  click handler reading another (**BUG 3**).

5558 is the layout where a top-tab switch destroys the composer behind `key(mobileTab)`
(`Composer.kt`, the P1-C comment at the `DisposableEffect`), so a composer comes back with its **draft**
but with two plain `remember` mirrors reset to `null`. 5554's desktop layout does not dispose the
composer, which is exactly why the two devices disagree.

## 1. BUG 1 — `@` panel dead when the caret is 0

**Root cause.** `MentionText.anchorAt(text, caret)` returns `null` for `caret <= 0`
(`MentionText.kt:59`). `ComposerSuggestions.triggerAt` had no second reading, so one lost caret
silently removed the entire `@` popover — no panel, no empty state, nothing.

**Fix.** `MentionText.anchorAtEndOfLine` (`MentionText.kt:68`) reads the anchor off the **text tail**,
and `ComposerSuggestions.triggerAt` (`ComposerSuggestions.kt`) falls back to it when the caret yields
nothing. This is not a widening: upstream's own open shape is `/(?:^|\s)@([^\s@]*)$/`
(`composer/suggestions/machine.ts:98-105`) — end-anchored, caret-independent. A caret that really is
inside an anchor still wins, so a mention being completed mid-line keeps its exact span.

## 2. BUG 2 — the editor can come back holding a non-empty line with the caret at 0

**Root cause.** The `rememberSaveable` initializer was
`TextFieldValue(HalfWidthText.normalize(ComposerDrafts.text(draftKey)))` — and `TextFieldValue(text)`
seeds its selection with `TextRange.Zero`. A composer rebuilt on a session switch therefore held the
draft's text with the caret parked at offset 0: the exact state `17_at_clean.png` shows.

**Fix.** `Composer.kt:409-420` seeds the restored draft at its end, the same place `insertText` and the
failed-send restore put it. This is the one place in the composer that built a non-empty
`TextFieldValue` without a range; the other eleven are empty-text or already ranged (audited by grep).

## 3. BUG 3 — `/` row click did nothing

**Root cause.** `accept(index)` drew its rows from `items`, which is built from `live` (the editor's own
text and caret), but branched on the two `remember` mirrors `slashQuery` / `trigger`. A composer rebuilt
with its draft has the popover drawn and both mirrors `null`, so every pick fell through to
`val active = trigger ?: return` — no insert, no command, no close.

**Fix.** `Composer.kt` `accept()` now reads `live.slashQuery` / `live.mention`, one source of truth for
the shape and the rows. A mention pick additionally follows the anchor to its own end when the caret is
not inside it, so the tail-read anchor of BUG 1 inserts instead of refusing.

## 4. BUG 4 — the rename editor lost one character per segmented input

**Root cause.** `HomeSessionRenameEditor.askForFocus()` was called from `onGloballyPositioned` — and a
renaming row **relayouts on every keystroke**, because the title it draws grows with the draft. Each
repeat is a fresh `requestFocus()` plus `keyboard.show()`, i.e. a re-attach of the editor's
`InputConnection`; an IME re-initialised at a segment boundary drops the character it was holding
(`E2ECHK5`+`558-`+`ent` → `E2ECHK5558-nt`, `research/v65_verify/5558/36_rename_ent_typed.png`). The
plain composer has no such ask and never lost a character — that difference is the shape of the bug.

**Fix.** `HomeSessionsList.kt:1206-1222` — the ask is guarded by `if (focused) return` and the
`onGloballyPositioned` caller by `if (!focused)`. The v43 "ask again once the row is placed" intent is
unchanged; the loop just stops once there is nothing left to ask for.

## 5. Real-device acceptance on 5554 (post-fix APK)

| item | result | shot |
| --- | --- | --- |
| `@` panel opens, filters, pick inserts the token | PASS — `@se` → panel → pick → `@explore ` | `40_at_se`, `41_at_pick` |
| text carrying a token is editable / caret and DEL work | PASS — `@explore hello world` + DEL×3 | `42_token_text`, `43_token_bs` |
| `/` panel filters, pick runs and the panel closes | PASS — `/re` → `/review`+`/redo`; pick clears the draft, closes the card, runs the alias | `44_slash_re`, `45_redo_pick` |
| rename, 5× segmented input, no character lost | PASS — `E2ECHK5`+`54`+`4-v66` → `E2ECHK5544-v66`, 5/5 exact; then `E2E5554-rename-v66` committed | `52_rename_open`, `53_rename_typed`, `54_rename_committed` |
| draft survives a home round trip with the caret at the end | PASS (the BUG-2 initializer), `@` panel still opens after it | `61_draft_back`, `62_at_after_roundtrip` |

## 6. JVM

`app/src/test/java/com/xiufeigo/opencodeshell/ComposerPopoverV66Test.kt` (new, 12 cases): the tail anchor
(bare `@`, last-`@`, whitespace closure, no trigger), `triggerAt` on the measured v65 state
(`triggerAt("v@", 0).mention == Anchor(1, "")`), the caret winning over the tail, the full-width fold,
the slash menu unaffected, and source pins for all three code fixes.
`HomeRenameCommitV43Test`'s source pin was updated to the guarded ask (with the reason in the comment).
Regression slice (composer + home + i18n, 60 classes incl. `ComposerPopoverTriggerV64Test`,
`RenameEditorImeV64Test`, `ComposerImeCommitTest`, `HomeRenameCommitV43Test`): **0 failures**.

## 7. Left open

* The three composer fixes are pinned on the JVM against the exact states the 5558 screenshots show,
  but 5554 never entered the caret-0 state, so the **live** re-verification of BUG 1/2/3 has to happen
  on 5556/5558 (tester). BUG 4 was reproduced and verified live here.
* Known non-app noise on 5554, unchanged and already in HANDOFF: adb bulk `input text` mangles text
  while Gboard holds a composing region (`draft one` landed as `bdraftb`). It is an injection artefact,
  not an app race — the same note as the 桶4 finding.
* `settings put secure show_ime_with_hard_keyboard 1` is now **on** for 5554. Leave it on: without it
  the desktop emulator never shows the keyboard and the real IME path is untestable.
