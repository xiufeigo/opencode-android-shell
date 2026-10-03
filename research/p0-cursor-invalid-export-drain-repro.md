# P0 — `400 InvalidCursorError: Invalid cursor` on every export / context fetch

Captured 2026-09-25 against the live server `http://127.0.0.1:4095`
(`opencode` **2.0.11**, pid 116870, single-arch WSL box), auth
`opencode:opencode`, read-only GETs only.

## 1. Symptom (device)

`research/v9_verify/export_errors/v9_41_ctx_export_disabled.png` (device clock
10:14) shows the Context/Usage tab's `Raw messages` row with the export button
disabled and:

```
Could not load session data: HTTP 400 InvalidCursorError: Invalid cursor
```

The same fetch backs ⋮ → `Export session`, the Home row action and the
background-tasks poll — all of them failed on every attempt.

## 2. Root cause — two `org.json` implementations, one line of code

The export/context chain is
`ChatViewModel.fetchExportFrameResult` → `V2Transport.fetchExportFrame` →
`V2Api.sessionRaw` + `V2Api.messagesRawPage`, and that drain ends a transcript
by reading `cursor.next` off the last page:

```
GET /api/session/{id}/message?order=asc       → page 1 … cursor.next = <base64url>
GET /api/session/{id}/message?cursor=<next>   → terminal page
```

Live, byte-exact terminal page (50 bytes):

```json
{"data":[],"cursor":{"previous":null,"next":null}}
```

The code read that with `JSONObject.optString("next", "")`. `optString` is the
one API the two runtimes this app uses do **not** agree on:

| runtime | `JSONObject("""{"next":null}""").optString("next","")` |
|---|---|
| Android platform (`sources/android-36.1/org/json/JSONObject.java:579`) | `"null"` ← the `NULL` sentinel goes through `JSON.toString` → `String.valueOf(JSONObject.NULL)` → `"null"`; only a *missing* key reaches the fallback |
| JVM `org.json:json:20240303` (what `:app:testDebugUnitTest` runs) | `""` ← `NULL` is explicitly mapped to the fallback |

Measured side by side:

```
# Android SDK source (C:/Users/xiufe/AppData/Local/Android/Sdk/sources/android-36.1)
JSONObject.java:579    public String optString(String name, String fallback) {
                           Object object = opt(name);
                           String result = JSON.toString(object);
                           return result != null ? result : fallback; }
JSONObject.java:114        @Override public String toString() { return "null"; }   // the NULL sentinel
JSON.java:86           static String toString(Object value) {
                           if (value instanceof String) return (String) value;
                           else if (value != null) return String.valueOf(value);
                           return null; }

# JVM probe against the exact jar the unit tests use
$ java -cp build/_tmpjson;json-20240303.jar NullProbe
isNull(next)=true
optString(next,"")=[]        # fallback — no cursor, drain stops cleanly
```

So on the **device** the terminal page produced `next == "null"`, the drain sent
a third request, and the server answered exactly the screenshot's message:

```
$ curl -s -u opencode:opencode \
    "http://127.0.0.1:4095/api/session/ses_f37fa7a65ffe2K671KIbhk2Tus/message?cursor=null"
{"_tag":"InvalidCursorError","message":"Invalid cursor"}     [400, 0.004 s]
```

This is `"非服务端签发的 cursor"` in its literal form: the string `null`, never
issued by the server. The **session-list** cursor (`GET /api/session`) had the
identical defect — `Page.nextCursor` → `ChatViewModel.fetchSessionRows` — so the
drawer's session list died the same way (`research/v9_verify/probe_smoke/04_drawer_no_sessions_backend_wedged.png`).

Every JVM test stayed green because the JVM jar returns the fallback. That is
the whole reason the suite could not see this.

## 3. Actual request sequence (live 4095, real `V2Transport.fetchExportFrame`)

Emitted with an OkHttp `EventListener` around the production code path
(`V2LiveExportDrainReproTest`, temporary harness):

```
GET /api/session/ses_0ed9691f6ffeZUnCfPBxATar34
GET /api/session/ses_0ed9691f6ffeZUnCfPBxATar34/message?order=asc
GET /api/session/ses_0ed9691f6ffeZUnCfPBxATar34/message?cursor=eyJpZCI6Im1zZ18wYzM4Y2M3ZTIwMDFMc0JaMHM0QjdENWtJTSIsIm9yZGVyIjoiYXNjIiwiZGlyZWN0aW9uIjoibmV4dCJ9
    cursor decodes: {"id":"msg_0c38cc7e2001LsBZ0s4B7D5kIM","order":"asc","direction":"next"}
```

- 22-row session and 36-row session, two rounds each, plus interleaved sessions:
  identical sequences, **zero 400s**, cursor byte-identical to the server's own
  `cursor.next`, no cursor reused across rounds, terminal page requested once.
- The live server drains the exact same sequence in a raw `curl`/Python
  re-implementation (2 requests, 200 both).
- So the algorithm and the wire shape were already right; only the value read
  out of the terminal page differed on Android.

Everything above was re-verified **after** the fix — same sequences, still 200.

## 4. Server-wedge check (the "4095 打挂 5–9 分钟" claim) — NOT reproduced

17 malformed / mismatched cursor shapes were sent to the live 2.0.11 process,
each followed by a health probe. Every one answered in ≤ 0.03 s and health
stayed 200. Raw output: `research/_wedge_probe.txt`.

| request | status | time |
|---|---|---|
| `…/message?cursor=null` | 400 InvalidCursorError: Invalid cursor | 0.028 s |
| `…/message?cursor=` (empty) | 200 (treated as no cursor) | 0.017 s |
| `…/message?cursor=undefined` | 400 InvalidCursorError | 0.025 s |
| `…/message?cursor=<raw JSON, not base64>` | 400 InvalidCursorError | 0.015 s |
| `…/message?cursor=<base64 of "not json">` | 400 InvalidCursorError | 0.015 s |
| `…/message?cursor=<unknown id>` | 200 `{"data":[]…}` | 0.016 s |
| `…/message?cursor=<flipped direction>` | 200 | 0.019 s |
| `…/message?cursor=<session-shaped cursor>` | 400 InvalidCursorError | 0.016 s |
| `…/message?cursor=<8000-char id>` | 200 `{"data":[]…}` | 0.017 s |
| `…/message?cursor=<valid>&limit=200` | 200 | 0.018 s |
| `/api/session?cursor=<message-shaped cursor>` | 400 InvalidCursorError | 0.016 s |
| `/api/session?cursor=null` | 400 InvalidCursorError | 0.023 s |
| `/api/session?cursor=<unknown anchor>` | 200 `{"data":[]…}` | 0.017 s |
| `/api/experimental/session/{id}/export` (36 rows) | 200, 357 870 B | 0.013 s |
| `/api/session/{id}/context` | 200 | 0.018 s |

Server-side decode path for the 400
(`_upstream/packages/server/src/handlers/message.ts:38-41`): `Buffer.from(v,
"base64url")` → `JSON.parse` → `Schema.decodeUnknownSync`; a throw becomes
`InvalidCursorError`. The anchor lookup
(`_upstream/packages/core/src/session/store.ts:141-152`) then does one indexed
`SELECT seq … .get()`, so a *decodable* cursor with an unknown id returns `[]`
rather than scanning. There is no unbounded loop on this route.

**Conclusion for the upstream report:** the export chain's `cursor=null` explains
the 400s completely, but it does **not** wedge the server — the malformed-cursor
path is a few microseconds of base64 + `JSON.parse` and answers immediately.
The 5–9 min全站无响应 seen at ~17:45 on 2026-09-25 (`/tmp/oc4095.log` mtime
17:53 = the restart) has to come from something else and is left open; nothing
in the export/context/context-window request set reproduces it.

## 5. Fix

`app/src/main/java/com/xiufeigo/opencodeshell/v2/`

- `V2Api.kt` — `Cursor.fromJson` now reads both halves through the new
  `opaqueCursorValue` helper, which uses the runtime-stable `isNull` instead of
  `optString` alone and additionally rejects the literal `"null"`/`"undefined"`
  tokens. Fixes the message drain **and** the session list in one place, and is
  the entire behavioural fix.
- `V2Api.kt` — `ApiException.isInvalidCursor` names the `_tag` rejection so
  callers (and tests) can assert "never retry"; the transport has no retry loop
  and this documents/pins it.
- `V2Transport.kt` — `fetchExportFrame` is now single-flight per
  `session + directory` (`ConcurrentHashMap<String, FutureTask<Frame>>`), so the
  export menu, the context tab and Home's row action share one drain instead of
  running three, and a follower receives the leader's failure as the same
  `ApiException` (not an `IOException` wrapper).

Deliberately **not** done: a client-side re-implementation of the server's
cursor schema check (`base64url → {id, order, direction}`) in the drain. It
would silently turn a loud 400 into a short, unflagged transcript whenever a
future server shape drifts, and the echo-verbatim contract already guarantees
the value is the server's own. The `opaqueCursorValue` sentinel filter covers
the concrete case that actually failed.

## 6. Regression pin — and proof that it can fail

`app/src/test/java/com/xiufeigo/opencodeshell/V2MessageCursorDrainTest.kt`
(10 tests, all green after the fix).

A JVM test cannot observe Android's `optString`, but it can pin its **output**:
`{"next":null}` (wire) and `{"next":"null"}` (Android's reading of it) must both
mean "no cursor". Fixture bodies are byte-exact live captures, including the
50-byte terminal page and the real 2026-09-25 cursor strings.

Reverting only `opaqueCursorValue` to the pre-fix `optString`-only form makes
three of them fail **with the device's own error**:

```
V2MessageCursorDrainTest > aTerminalCursorReadAsAndroidReadsItEndsTheDrainInsteadOfSendingNull
    FAILED  java.io.IOException: HTTP 400 InvalidCursorError: Invalid cursor
V2MessageCursorDrainTest > theStringifiedNullSentinelNeverReachesTheWire
    FAILED  com.xiufeigo.opencodeshell.v2.V2Api$ApiException: HTTP 400 InvalidCursorError: Invalid cursor
V2MessageCursorDrainTest > cursorFromJsonTreatsNullUndefinedAndTheStringifiedSentinelAsAbsent
    FAILED  java.lang.AssertionError: expected null, but was:<null>
9 tests completed, 3 failed
```

That is the P0 reproduced end-to-end in the JVM harness: the drain sends
`?cursor=null` and takes the 400. With the fix restored all 10 pass, and so do
the pre-existing `V2ApiTest`, `Wave1TransportTest`, `SessionExportTest`,
`SessionExportTruncationTest`, `V2ApiErrorTypeTest`, `V2TransportMappingTest`.

## 7. Live re-verification after the fix

Real `V2Transport.fetchExportFrame` against 4095, every request URL printed from
an OkHttp `EventListener`:

```
=== FIRST fetch ses_0ed9691f6ffeZUnCfPBxATar34 (36 rows): rows=36 pages=2 truncated=false err=null
    url=…/api/session/ses_0ed9691f6ffeZUnCfPBxATar34
    url=…/api/session/ses_0ed9691f6ffeZUnCfPBxATar34/message?order=asc
    url=…/api/session/ses_0ed9691f6ffeZUnCfPBxATar34/message?cursor=eyJpZCI6Im1zZ18wYzM4Y2M3ZTIwMDFMc0JaMHM0QjdENWtJTSIsIm9yZGVyIjoiYXNjIiwiZGlyZWN0aW9uIjoibmV4dCJ9
    cursor={"id":"msg_0c38cc7e2001LsBZ0s4B7D5kIM","order":"asc","direction":"next"}
=== 25 drains: ok=25 rows=732 requests=75 distinctCursors=2
=== health after 25 drains: HTTP 200 in 1ms
=== health again:          HTTP 200 in 1ms
=== post-storm re-fetch rows=36 err=null
=== post-storm health:     HTTP 200 in 0ms
```

25 consecutive full export drains (75 requests, exactly one `order=asc` page 1
plus one `cursor=` follow-up per drain): zero 400s, zero `cursor=null`, the
cursor is the server's own `cursor.next` byte-for-byte, and `/api/info` answers
200 in ≤ 1 ms before, between and after them. (Note: `/global/health` is the SPA
catch-all on 2.0.11 and returns HTML; `/api/info` is the health route.)
