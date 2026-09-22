# Wave 2.4b implementation notes — composer queue/steer + structured @mentions

Date: 2026-09-22. Baseline: A3-session-view.md (deltas #2 queue, #3 mentions),
wave1-notes.md (transport seam), A5-protocol-events.md §1.3/§2 (inbox + prompt
payload). Tree was green (1190 tests) on entry; Wave 2.4a landed concurrently
(1209 green) touching ChatViewModel/docks/actions/SessionScreen — none of that
is reverted; the §VM contract below is written against the 2.4a `submit()`
shape (hardcoded `steer=true`, `delivery="steer"`).

Upstream read (read-only): `session/composer/{queue.ts,queue-panel.tsx}`,
`composer/{submit.ts:17-26,351-402, request.ts, adapter.ts:42, model.ts:271-273,
editor/editor.tsx:351-471,822-866, schema.ts, suggestions/machine.ts:39,181-188}`,
`session/composer/controller.ts:27-32`, `runtime/server/data.ts`.

## Composer-side deliverables (this wave — DONE)

Pure models (`ui/session/composer/`, no Android/Compose imports):
- `MentionTokens.kt` — accepted-`@` token record. `accept()` pairs the
  `MentionText.Insertion` the editor needs with the typed token
  (`FileAttachmentPart{path,…}` / `AgentPart{name}` / `SkillPart{id,name}`
  shape from `composer/schema.ts`); COMMAND items record nothing (a `/name`
  line is a server command invocation, never a mention). `retokenize()` drops
  tokens whose `@label` left the text. `structure()` splits tokens into v2
  fields in token order; `fileUri()` mirrors `request.ts:42-47` `absolute()`
  + `encodeFilePath` (relative→session dir, backslash fold, segment encoding,
  drive-colon preserved).
- `ComposerSubmission.kt` — the submit model: `Model{text, files, agents,
  skills, delivery, shell, command, arguments}`. `build()` orders files as
  `[...mention file:// refs, ...attachment data: URLs]` (`request.ts:133,193`
  `[...files, ...context, ...inline]`); agents/skills in token order.
  `Payload.structured()` upgrades the legacy payload at the single submit site.
- `ComposerDelivery.kt` — the `queue.ts:216-223` truth table:
  `delivery() = working ? behavior : "steer"`; `alternate()` = opposite while
  running+content, `"steer"` while confirming an edit, null (hidden) when idle.
- `ComposerQueue.kt` — the pure queue half: `isQueueSupported(generation)`
  (OFF on v1 — no inbox API), `Row{id,text,attachmentCount,submission}`,
  `Model{add/remove/reorder/promoteToSteer/replace/edit/editFirst/
  confirmEdit/cancelEdit/clear}`, `showCount` (>3 header), `isParkedFirst`.

UI (`Composer.kt` + 3 new files, all params defaulted — M1.5/W5-C callsites compile):
- `DeliveryToggleChip.kt` (`DeliveryToggleChip`) + `DeliveryChipText.kt`
  (delivery→`ui_promptInput_steer`/`ui_promptInput_queue` key) — the Steer/Queue
  hint (`editor.tsx:822-866`), shown only while `alternate()` is non-null.
- `ComposerQueuePanel.kt` (`ComposerQueuePanel`) + `QueueAttachmentsText.kt` —
  the panel (`queue-panel.tsx:18-83` geometry: `rounded-xl`, 6dp padding,
  148dp max, `-mb-3`-style 12dp overlap, `N queued` header past 3 rows; rows:
  32dp, tap-to-edit text + `N attachments`, Steer/Send + ✕ — no drag gesture on
  touch; `reorder()` stays a pure-model op).
- `Composer.kt`: `tokens` state (token recorded on popover pick via
  `MentionText.insert` + `MentionTokens.tokenFor`, `retokenize` on every
  `setText`), `queueEdit` loan + `queueEditOpen` mirror (row tap → editor; Esc
  cancels; submit confirms via `onQueueConfirm` instead of history + send),
  `fun submit()` (M1.5 shape kept: clear + `trigger = null` first in a 400-char
  window) with the one-shot `alternateNext` toggle arming, delivery default +
  generation gate, new defaulted params (`followUpBehavior`, `generation`,
  `queuedRows`, `queueBusy`, `onQueueSteer/Remove/Confirm`,
  `onStructuredSubmit`).

Tests: `Wave24bComposerQueueTest` (21 pure assertions),
`Wave24bComposerWiringTest` (5 source guards incl. default-bundle string keys).
No new dependencies. IME/attachment/draft/history untouched (submit keeps the
`onSubmit`→`onSend` fallback chain; history skips only edit-confirms).
Pre-existing pin tests (`ComposerSubmitTest`, `ComposerM1_5Test`,
`Wave5ComposerWiringTest`) pass unmodified — the composer keeps their pinned
literals (`fun submit()`, `value = TextFieldValue("")`, `trigger = null`,
`MentionText.insert(`, `onSend(outgoing, attachments)`).

## §VM — exact wiring for the integrator (ChatViewModel + docks agent)

You own `ChatViewModel.kt`, `ChatStore.kt`, `v2/**`, `OpenCodeClient.kt`,
`ui/session/**` outside `composer/`, `ui/App.kt`. Nothing below is implemented
— wire exactly this:

### 1. `ChatViewModel.submit` — accept the structured model + delivery

Current signature (your file, ~L1214):
`submit(text, attachments, shell=false, command=null, arguments="")`.
Extend (all defaulted, old callers keep compiling):
```kotlin
fun submit(
    text: String,
    attachments: List<Attachment> = emptyList(),
    shell: Boolean = false,
    command: String? = null,
    arguments: String = "",
    // NEW (2.4b):
    files: List<ComposerSubmission.FileEntry> = emptyList(), // mention file:// refs (submission order)
    agents: List<String> = emptyList(),                       // mention agent names
    skills: List<ComposerSubmission.ComposerSkill> = emptyList(),
    delivery: String = "steer",                               // "steer" | "queue"
)
```
Routing inside the existing `when (val t = transport)`:
- **V1 branch**: IGNORE `delivery` and the mention lists except files. Map
  `files.filter { it.uri.startsWith("file://") }` → append to the prompt text
  as `Attached file: \`path\`` lines? NO — simpler and closer to upstream:
  convert each mention file to a `{type:file, mime, filename, url}` UiPart
  with `url = file.uri` (server resolves `file://`), appended AFTER the
  attachment parts; agents/skills have no v1 carrier — fold them into the text
  as `@name` (they are already inline in `text`; nothing extra to do — the
  chip text IS the mention). Keep `promptAsync` byte-identical otherwise.
  Shell/command branches: unchanged (mentions only attach to plain prompts;
  the composer never sends tokens with shell/command today).
- **V2 branch**: replace the hardcoded `steer=true` / `delivery="steer"` with
  the parameter. Payload mapping per `V2Api.prompt(...)`:
  - `files = filesOf(attachments)` **+** mention files: `V2Api.PromptFile(
    uri = entry.uri, name = entry.name)` — mention entries ALREADY carry
    `file://` URIs (built composer-side); attachments keep today's
    `filesOf()` data-URL mapping. Order: mentions first, then attachments
    (matches `request.ts`).
  - `agents = agents.map { JSONObject().put("name", it) }` — v2 `agents[]`
    entries are `{name, mention?}` (`request.ts:83-86`); `mention` ranges are
    optional (server accepts entries without; upstream sends them for
    edit-round-trip fidelity — skip ranges on first submit, the text still
    carries `@name`).
  - `skills = skills.map { JSONObject().put("id", it.id).put("name", it.name) }`.
  - `metadata = JSONObject().put("displayText", text)` (+ existing attachment
    metadata if any — keep whatever the VM puts there today, just ADD
    displayText).
  - `delivery = delivery`, `resume = null` (omit; `resume=false` is the
    admit-without-run novelty — NOT this wave).
  - Steer-only selection rule (already in your code, keep): `if (delivery ==
    "steer") { switchAgent; switchModel }` — a queued follow-up MUST NOT
    reconfigure the running turn (`submit.ts:363`).
  - `sendCommand`: thread `delivery` through too (`V2Api.command` already takes
    `delivery?`; `V2Transport.sendCommand` needs the param added — your
    seam). Queued commands likewise skip selection switches.
- Failure path: unchanged (`restoreDraft` + ERROR). Note: a failed
  `delivery=queue` submit restores the draft — same as prompts today.

### 2. SessionScreen → Composer wiring (your `ui/session/**` half)

At the `Composer(...)` callsite (~L690):
```kotlin
val settings by settingsStore.state.collectAsState()   // or the existing settings flow
val generation by chatViewModel.generation.collectAsState()
Composer(
    …,
    followUpBehavior = settings.followUpBehavior,      // READ-ONLY; never write from composer
    generation = generation.name,                       // "V1" | "V2" | "UNKNOWN"
    queuedRows = inboxRows,                             // YOUR state: inbox items → ComposerQueue.Row
    queueBusy = inboxMutationInFlight,                  // YOUR flag while a queue mutation runs
    onQueueSteer = { id -> chatViewModel.steerQueued(id) },
    onQueueRemove = { id -> chatViewModel.cancelQueued(id) },
    onQueueConfirm = { id, replacement -> chatViewModel.confirmQueueEdit(id, replacement) },
    onStructuredSubmit = { m ->
        chatViewModel.submit(m.text, attachments /*cleared by you as today*/,
            m.shell, m.command, m.arguments,
            files = m.files, agents = m.agents, skills = m.skills,
            delivery = m.delivery.wire)
        attachments = emptyList()
    },
)
```
Keep `onSubmit`/`onSend` passed as today (fallback chain stays green).

### 3. Queue state (VM-owned; composer holds NO queue list)

- Add to VM (or a `ComposerQueueHolder` next to it — your choice, NOT in
  `composer/`): `StateFlow<List<ComposerQueue.Row>>` + `busy` flag, per
  session (clear on session switch).
- Ack path: `V2Transport.sendPrompt` returns void today — capture the admitted
  id. `V2Api.prompt()` returns `SessionAdmitted` (`{data:{id:msg_,…}}`);
  change `SessionTransport.sendPrompt` to return the admitted message id
  (`String?`, null on v1) and, when `delivery=="queue"`, `add(Row(
  id = admittedId, text = displayText, attachmentCount = files.size,
  submission = sentModel ))` optimistically.
- Server truth: `session.inbox.*` events (`enqueued{inboxID,item}/
  delivered{inboxID}/cancelled/delivery.changed`) → refresh via
  `V2Api.sessionInbox(sessionID)` (exists; returns raw JSON) mapped to rows:
  `id = obj.optString("id")`, `text = metadata.displayText ?? payload.text`,
  `attachmentCount = payload.files.length + metadata.attachments.length`,
  `submission = null` (server rows re-submit from text; structured resubmit
  only for optimistic/local rows). `replace(rows)` on every refresh; `remove`
  on delivered/cancelled. Drain: when the turn goes IDLE with rows present,
  the first row's Send (parked) re-admits it as steer — i.e. `steerQueued(id)`.
- Mutations (all v2-only; NO-OP on v1 — the panel never shows there):
  - `steerQueued(id)` → `PATCH /api/session/{id}/inbox/{inboxID} {delivery:
    "steer"}` — **NO client method exists**: add `V2Api.updateInboxDelivery(
    sessionID, inboxID, delivery)` + `V2Transport` passthrough, then
    `queue.remove(id)` optimistically (server `delivery.changed` confirms).
  - `cancelQueued(id)` → `DELETE /api/session/{id}/inbox/{inboxID}` — **NO
    client method exists**: add `V2Api.cancelInbox(sessionID, inboxID)` +
    passthrough, then `queue.remove(id)`.
  - `confirmQueueEdit(originalId, replacement)` → upstream admit-before-cancel
    (`queue.ts:65-74`): `sendPrompt(…replacement…, delivery =
    replacement.delivery.wire, messageID = new msg_ id)` FIRST, then
    `cancelInbox(originalId)`; on re-admit failure keep the original row and
    `ComposerDrafts.restore(key, replacement.text)`. Pristine value + queue =
    no-op (skip both calls; the composer already closed the loan — compare
    `replacement.text` to the row's text + attachment counts before calling).
  - reorder: upstream re-admits the changed suffix + cancels olds
    (`queue.ts:100-131` `rewrite`) — server has no move API. Defer unless asked
    (the native panel has no drag gesture; `Model.reorder()` is ready if a
    gesture lands later).

### 4. Generation gating (both halves)

- Composer hides toggle+panel on `"V1"` and forces `STEER` (`submit()`:
  `if (!running || !supported) STEER`). VM MUST mirror: `if (generation ==
  V1) delivery = "steer"` regardless of what the composer sent (belt and
  braces — the composer already guarantees it, but the VM is the trust
  boundary). `UNKNOWN` behaves as v2-optimistic (probe resolves pre-submit).

### 5. What is deliberately NOT in this wave (follow-ups)

- `resume=false` admit-without-run; inbox `GET` polling cadence (event-driven
  only); `delivery.changed` optimistic row patch (full `replace` is fine);
  queue persistence across restarts (in-memory per session, like history);
  mention `file://` content pre-read (server reads workspace paths); image
  `blob:` re-resolution (attachments arrive as `data:` URLs already);
  skill distinct ids (`SkillPart.id` = name until the catalog splits them);
  command-arg mentions; drag-reorder gesture.

## §strings — nothing missing

No new keys: toggle/chip/panel reuse the generated v2 bundle
(`values/strings_oc_i18n.xml` — `ui_promptInput_steer/queue/steerHint`,
`session_queue_steer/send/remove/count_{one,other}/
attachments_{one,other}`), verified present by
`Wave24bComposerWiringTest.everyRenderedQueueStringExistsInTheDefaultBundle`.
zh locales fall back to the default bundle at runtime (standard Android
fallback; the zh `strings_oc_i18n.xml` files are an older 1411-key subset vs
1870 default — a pre-existing gap, NOT this wave's). If a follow-up wants
native zh labels, add the 10 keys to the two zh files (FORBIDDEN here).

## Validation

- New tests: `Wave24bComposerQueueTest` (21), `Wave24bComposerWiringTest` (5).
- Full gate: `compileDebugKotlin` + `testDebugUnitTest` — see build status in
  the return message (baseline 1209 incl. 2.4a; target 1209 + 26 = 1235,
  0 failures).
