# Wave 2.4a implementation notes — v2 form dock + generation gating

Date: 2026-09-22. Baseline: `research/v2_audit/wave1-notes.md` (1190 tests green),
A5 §1.5/§2, upstream `packages/app/src/session/requests/session-question-dock.tsx`
+ `session-request-tree.ts`, schema `packages/schema/src/form.ts`.

## 0. What Wave 1 had already done (no change needed)

Work item 1 ("expose forms") was already landed by Wave 1: `ChatViewModel.pendingForm`
(`ChatStore.pendingForms.firstOrNull()`, session-scoped), `replyForm(formID, answer)`
(v2 transport reply, `questionResponding` busy flag, optimistic `FormResolved` +
rollback), `generation: StateFlow<ServerGeneration>`, plus `refreshQuestions` (V1-only)
and `refreshForms` (V2-only) snapshot splits. This wave only **added** the missing
`dismissForm` and the guards below.

## 1. Done

- **Form dock UI** (`ui/session/dock/SessionFormDock.kt`, new): renders the pending
  `FormAsk` like upstream's v2 question dock (same shell/tray skeleton, marks,
  button-v2 sizes, progress segments, Back/Next→Submit, Dismiss). Page set = all
  six field types: optioned `string` (single-select + always-offered custom row),
  `multiselect` (checkboxes + custom), option-less `string` (free text),
  `number`/`integer` (numeric input), `boolean` (yes/no), `external` (info page
  with tappable URL, never answered). `when`-inactive and `hidden` fields are
  skipped by the pager and dropped from the submit payload.
- **Pure model** (`ui/session/dock/SessionFormModel.kt`, new): field parse (6 types
  + options/defaults/placeholder), `when` evaluation (AND, unanswered ⇒ false,
  multiselect `eq` = includes), `visiblePages`, `pageAnswered`, `buildAnswer`
  (answered-only; single value vs array; integer-valued doubles post as `Long`),
  `formCancelRequest` (`DELETE /api/session/{id}/form/{id}` + basic-auth header).
- **VM** (`ChatViewModel.kt`): new `dismissForm(formID)` (optimistic resolve +
  rollback, `questionResponding` busy flag; executes the model-built DELETE via
  `sharedClient` so `v2/**` stays untouched); V2 guards on `shareSession` /
  `unshareSession` / `archiveSession` (report failure, no network) and
  `refreshTodos` (early return) — all v1-only per `V2Capability`.
- **Menu gating** (`ui/session/actions/SessionActionsModel.kt`): `menu()` takes
  `generation` (default V1, old callers compile); `SessionActionsMenuState` gains
  `shareVisible`/`archiveVisible` (false unless V1). `rememberSessionActionsMenuState`
  and `SessionActionsHost.start()` (both `SessionActionsHost.kt`) enforce the gate.
- **Screen** (`ui/session/SessionScreen.kt`): collects `pendingForm`, renders
  `SessionFormDock` below the question dock (both may exist), wires
  reply→`replyForm` / dismiss→`dismissForm`; `hasPendingRequest` includes
  `pendingForms` (suppresses the "Working" row while a form pends).
- **Tests** (19 new): `SessionFormModelTest` (12: six types, malformed entries,
  `when` incl. multiselect/AND/unanswered, hidden paging, answer building,
  cancel-request shape/auth/bad-URL) + `Wave24aFormExposureTest` (7: first-pending
  exposure, foreign/global gating, snapshot merge, V1/V2/UNKNOWN/default menu
  visibility). Transport reply wire (`{answer:{…}}` + 204) was already pinned by
  Wave 1 (`Wave1TransportTest.v2FormCreateAndReplyBodies`).

## 2. Deliberate deviation — form dismiss DOES hit the network

Wave 1 made dismissal local-only (`V2Transport.dismissForm` no-op) after measuring
`DELETE …/form/{id} → 409`. That probe ran **after a reply** (settled form ⇒
`FormAlreadySettledError`, expected). Cancelling a *pending* form is the documented
`session.form.cancel` route (`protocol/src/groups/session.ts:855`, "Cancel a pending
form") and exactly what upstream's dock calls on Dismiss — so `dismissForm` DELETEs
while pending, with rollback-to-dock on any failure (safe degradation if a server
ever rejects it). `V2Transport.dismissForm` itself is untouched (file ownership).

## 3. Missing strings (res/** owned outside this wave — hardcoded EN instead)

`SessionFormDock.kt`: `booleanOptions()` labels `"Yes"` / `"No"`. Everything else
reuses `oc_question_*` / `oc_mobile_question_*` (upstream renders forms in the
question dock, so the copy already fits) plus the server-provided form title and
field text. Proposed additions for the res owner: `oc_form_yes`, `oc_form_no`.

## 4. Deferred / follow-ups

- **`ui/session/header/SessionHeader.kt` (not owned by this wave)**: the ⋮ menu
  still *renders* the SHARE/ARCHIVE rows on v2 — `SessionActionsMenuState` now
  carries `shareVisible`/`archiveVisible`, so the fix is wrapping the two
  `MenuRow`s (`SessionHeader.kt:485-506`) in `if (actionsMenu.shareVisible)` /
  `if (actionsMenu.archiveVisible)`. Until then the host/VM guards turn those taps
  into a `common_requestFailed` toast instead of a 404.
- **`ui/App.kt:1325` (not owned)**: the palette `session.archive` row is null-gated
  (`SessionCommands.kt:162` hides it when `onArchive == null`), so passing
  `onArchive = null` when `generation != V1` hides it on v2. Until then the VM
  guard reports failure gracefully.
- **Drawer/bottom-sheet + `MobileSessionTabs`**: no share/archive/todo rows exist
  there (verified by grep) — nothing to gate, despite the work-item guess.
- **`state.todos` has no UI reader** (review panel never renders it); the v2 todo
  story stays "derive from todowrite tool state" per A5 §2. `refreshTodos` is
  gated at the VM so v2 never fires the 404.
- **Live verification**: the v2 server (4095) was offline during this wave (same
  as wave1-notes); form reply/cancel shapes rest on protocol source + generated
  client + Wave 1's 2026-09-21 live captures. `required`-field enforcement is
  upstream-parity (submit skips unanswered; the server 400s if it insists).

## 5. Composer-agent notes (parallel agent owns ui/session/composer/**)

Nothing in the composer was touched. Two things you may rely on: `pendingForm`
is exposed on the VM (first-pending v2 form) and `questionResponding` is the
shared busy flag for question + form replies — if the composer disables send
while a dock is answering, key on `pendingForm != null || pendingQuestion != null`.
The `"global"`-session forms (MCP elicitation) never enter `pendingForms`; a
global dock for those is still open work (wave1-notes §"Wave 2 needs").
