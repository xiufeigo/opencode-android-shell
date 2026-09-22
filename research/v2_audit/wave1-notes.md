# Wave 1 implementation notes — dual-protocol transport layer

Date: 2026-09-22. Baseline: A5-protocol-events.md (2.0.11实测) + live GET
verification on :4095 (v2, Basic opencode:opencode) and :4096 (v1 1.18.31);
write-shape validation on ONE throwaway session per shape (created, POSTed,
then DELETEd — no fixture data touched).

## Probe rule implemented (V2Capability)

- `decideInfo(status, contentType, body)`: V2 iff 200 + JSON + `version`
  starts with `"2."`. Everything else (SPA HTML either inline or behind a
  JSON content-type, non-JSON, missing version, `1.x` version, non-200) ⇒
  UNKNOWN. Live: `{"version":"2.0.11",…}` on 4095.
- `decideHealth(...)`: V1 iff 200 + JSON + `version` present (any value).
  Live: `{"healthy":true,"version":"1.18.31"}` on 4096; SPA HTML on 4095 ⇒
  UNKNOWN. The old `healthy`-boolean rule is gone (v1 `/api/health` also
  answers `{"healthy":true}` — it could never discriminate).
- `probe(url, user, pass)`: `GET /api/info` → V2 (+ confirming
  `GET /api/location` for directory; failure never demotes) else
  `GET /global/health` → V1 else UNKNOWN (`httpStatus == null` = network
  error, distinct from a verdict).
- Persisted per serverKey (`ServerConfigStore.generationOf/rememberGeneration`,
  prefs key `generation:<trimmed url>`, backward-compatible: missing/corrupt ⇒
  null ⇒ probe on next connect; UNKNOWN clears the entry). `Server.serverKey`
  = trimmed url (data class untouched — all existing constructors compile).
- `ChatViewModel.ensureClient()`: v1 `OpenCodeClient` ALWAYS built first
  (zero regression window), then `ensureTransport()`: cache hit ⇒
  `setGeneration()` sync; cache miss ⇒ IO probe → persist → adopt only if the
  server is still active. `start()`: opens the v1 global stream immediately,
  upgrades to v2 streams when V2 confirms (v1 stream closed first — one
  generation drives the store).

## Coverage changes (V2Capability.COVERAGE + research/v2_coverage.md)

Re-probed against 2.0.11 + live openapi.json (113 paths). Flips vs the old
1.18.31-hybrid table: session.rename/delete V1_ONLY→V2, session.diff
V1_ONLY→V2 (params from/to/context), session.shell/command V1_ONLY→V2,
question.* V2→V1_ONLY (404 + absent from openapi; v2 carrier = forms),
defaultModel/project/vcs.branch/mcp UNSUPPORTED→V2, event.session route →
experimental log, session.wait → experimental prefix, + new rows
(probe.info/probe.health, message.single, form.*, permission.create/get,
session.fork/share/archive, session.inbox/environ, config.list, vcs.info/diff/
status, shell.list, worktree.list, plugin.list).
 research/v2_coverage.md rewritten as the human mirror (probe table, capability
families, envelope/query rules, GET-only repro commands).

## Transport seam shape

`v2/SessionTransport.kt` — `interface SessionTransport : AutoCloseable`:
`generation`, `snapshotMessages(sessionID, limit)`, `fetchSession` →
`SessionRow` (id/title/directory/cost/revertMessageID/agent/providerID/
modelID), `fetchPermissions`, `sendPrompt(id, text, files, messageID,
delivery, resume)`, `switchAgent`, `switchModel(providerID, modelID, variant)`,
`sendShell(id, command, messageID)`, `sendCommand(id, name, text, files)`,
`interrupt(id, resume?)`, `replyPermission(id, requestID, decision, message?)`,
`createSession(directory, title): String`, `renameSession: String?`,
`deleteSession: Boolean`, `stageRevert: String?`, `clearRevert`,
`commitRevert`, `forkSession(before): String?`, `streamRequests(sessionID,
afterSeq)`, `parseFrame(type, data)`, `frameSessionID(data)`.
`V1Transport(client, baseHttpUrl, directory)` — byte-identical delegation
(shell/command keep their full legacy bodies via `sendShellAs/sendCommandAs`;
switches are no-ops since v1 pins inside write bodies; interrupt→abort).
`V2Transport(api)` — flat prompt, pre-send agent/model switches (steer),
`{decision}` replies, `{before}` forks, DELETE-revert, session-scoped
permission view, form helpers (`fetchForms/replyForm/dismissForm` local-only),
message→UiMessage mapping (user/assistant/notice rows; tool blocks with
status/input/output/error; reasoning parts).
`ChatViewModel` keeps ALL quote-compat entry points (`send/submit/sendShell/
runCommand/abort/replyPermission/replyQuestion/rejectQuestion/renameSession/
deleteSession/undoSession/redoSession/forkSession/shareSession/unshareSession/
archiveSession/createSession/...`); internals route via
`requireTransport()` with v1-client fallback while probing.

## Event adapter coverage (V2EventAdapter + V2SeqTracker)

Mapped to existing store events (UI: zero changes): session.status (+retry)/
idle, execution.started/succeeded/failed/interrupted, retry.scheduled,
step.started (+RUNNING) / streamed (noop) / ended (liveness) / failed
(ERROR or IDLE on interrupt), text/reasoning started (empty slot) / delta
(PartDelta append) / ended (full cover), tool.input.started (streaming slot) /
ended (input cover) / called (running+input), tool.success/failed (terminal
PartUpdated; content text-concat + file→`[file: uri]` lines), step/text slot
ids `msg:type:ordinal`, tool slots = call id, permission.asked (renames
action/resources/save/source{id}→Tool) / replied, form.created/replied/
cancelled (FormCreated/FormResolved + ChatState.pendingForms/FormSnapshot),
session.created/deleted (+renamed/moved/forked/inbox.* → created row for list
freshness), provider/model/agent/config/project/vcs/pty signals, server.
connected/log.synced (stream state only). Durable `log.synced{seq}` flat-seq
fix in `DurableEvent.fromJson` (watermark is top-level, not under `durable`).
`V2SeqTracker`: maxSeq/seenIDs/syncedSeq, `afterParam()`, pure
`gapBelowWatermark()` math. Reconnect = `?after=maxSeq&follow=true` + IDLE-
triggered throttled full-message fetch for ephemeral gaps.
DEFERRED (no Signal rows added — the 13-entry pin test forbids silent growth):
session.usage.* (dropped), tool.input.delta + tool.progress (dropped — no
store field; durable frames that follow carry values), shell/synthetic/skill/
instructions/content.updated/compaction notices (snapshot reconciles),
agent.selected/model.selected/permissions/viewed/revert.*/mcp/worktree/
location/plugin/shell/credential (dropped; Wave 2 adds rows), `session.viewed`,
`"global"` forms (gated out of session lists).

## Deviations from A5 (all live-verified 2026-09-21)

1. Prompt response is `Session.Inbox.User`
   `{data:{id:msg_,sessionID,time,type:"user",payload:{text},delivery}}` —
   NOT `{admittedSeq,…}` (old hybrid contract; `admittedSeq` kept as 0/absent
   back-compat). Nested `{prompt:{text}}` → 400 `InvalidRequestError
   Missing key at ["text"]`.
2. Revert-clear is `DELETE /api/session/{id}/revert` (204);
   `POST …/revert/clear` → 404.
3. `POST /api/session/{id}/wait` does NOT exist — only
   `POST /api/experimental/session/{id}/wait`.
4. Interrupt answers `200 {"interrupted":bool}` (false on idle), not 204;
   `resume` is a query flag. Client tolerates 200/204.
5. `POST …/permission` self-created asks answer `{effect:allow}` and never
   pend — replying 404s `PermissionNotFoundError`; `{reply}` body 400s,
   `{decision}` required.
6. `POST …/fork{messageID}` is silently IGNORED (forks whole session anyway);
   `{before}` required. Empty-session fork → 400 `empty_session`.
7. `DELETE …/form/{id}` after reply → 409 (no dismiss route — local-only).
8. Compact answers 200 `{data:Session.Inbox.Compaction}` (not 204).
9. Agent entries HAVE `name`; command has NO `template`/`source`; skill
   `location`→`path`; provider is flat `{id,integrationID?,name,activation?,
   package?,settings?…}`; model has `modelID/compatibility/package/settings/
   time` extra keys.
10. Live turn frames (real `YO` turn on 4095): global stream carries
    inbox.enqueued → execution.started → instructions.updated →
    inbox.delivered → step.started → text.started/delta/ended →
    step.streamed/ended → usage.updated → execution.succeeded → (later)
    session.deleted; per-session log `?after=0` replays durable frames.
11. No store change for tool input-delta (ChatStoreTest pins the drop) and no
    new Signal rows (Wave5 pin test pins 13) — adapter drops those frames.

## Tests added/passing

- `V2ApiTest`: 41 (updated: flat prompt, DELETE-revert, interrupt flag test,
  log-stream requests, decideInfo/decideHealth split, coverage verdicts,
  form-vs-question, new DTO fixtures).
- `Wave1TransportTest` (new): 18 — probe never-cross-selects, probe request
  order (info→location / info→health), V1Transport byte parity
  (prompt_async/shell/command bodies), V1 frame parse, `{decision}` vs
  `{reply}`, fork `{before}`, shell/command/interrupt shapes, catalog vs
  session-list query keys, bare-array vs `{data}` envelopes, diff from/to,
  form CRUD bodies, live-turn fold (delta→ended→IDLE), seq watermark math
  (+dedup), permission rename, form dock reduce, transport parse/routing,
  stream request pair.
- Full suite: **1190 tests, 0 failures** (`testDebugUnitTest`).
- One pin-test edit (allowed — it tests code I changed):
  `Wave5ComposerWiringTest` shell/command pins now match `sendShellAs(/`
  `sendCommandAs(` (same routes/bodies, seam hides the client).
- Reverted-to-green (pre-existing tests kept untouched): ChatStoreTest +
  ChatStoreWave5EventTest tool-input-delta drop, 13-Signal pin,
  `withFieldDelta` pin (`"state.output", "output" ->`), errorMessageOf order
  extended with `type` (v2 field-less errors) — v1 order unchanged.

## What Wave 2 (UI) needs to know

- `pendingForm: StateFlow<FormAsk?>` (first pending v2 form) + `replyForm(
  formID, answer: Map<String, Any?>)` are exposed; `questionResponding` doubles
  as the form busy flag. The v1 question dock is untouched (v1 servers).
  `"global"`-session forms never enter session lists (MCP elicitation needs a
  global dock). Form fields stay raw JSON (`fieldsJson`) — Wave 2 owns the 6
  field types + `when` linkage + external jump chains.
- `generation: StateFlow<ServerGeneration>` (default V1) for any
  generation-gated UI (share/archive/todo rows hide on V2 — transport has no
  v2 routes for them; `shareSession/unshareSession/archiveSession` still call
  v1 client paths today and will 404/HTML-fail on v2 — Wave 2 should gate
  those rows on `generation == V1`).
- Inbox queue UI (composer pending bar, steer/queue badges) has protocol
  support (`session.inbox.*` → list revision; `delivery` on prompt) but no
  store list yet — Wave 2 adds `pendingInbox` next to `pendingForms`.
- V2 catalogs land in the SAME composer lists (agents/commands/skills/providers
  incl. `limit.context` ring + variant ids + `model/default` chip default);
  v2 commands have no templates (client expansion v1-only).
- Cost ticks (`usage.updated`) are snapshot-driven, not yet a live ring tick.
- `V2Transport` builds a second `V2Api` for catalog reads (same creds/dir);
  a follow-up can share the instance the transport owns.

## Validation log (throwaway sessions on 4095, all DELETEd afterwards)

Flat `{text,resume:false}` → user-inbox ack; nested `{prompt:{text}}` → 400;
permission self-create → `{effect:allow}`, decision-reply → 404, `{reply}` →
400; shell/command → 204; fork `{}`/`{messageID}` (ignored) vs `{before}`;
PATCH rename → 204; revert stage → `{data:{messageID,files:[]}}`, busy →
409, clear-DELETE → 204, clear-POST → 404; form create → 200
`{data:{id:frm_,…}}`, reply → 204, delete-after-reply → 409; interrupt idle →
200 `{"interrupted":false}`; DELETE session → 204 → GET 404.
v2 server 4095 was OFFLINE on 2026-09-22 (connection refused); v1 4096 stayed
up (`{"healthy":true,"version":"1.18.31"}`). All v2 facts above are A5 +
2026-09-21 live captures baked into fixtures (SSE_V2_TURN is a real turn).
