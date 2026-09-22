package com.xiufeigo.opencodeshell

/**
 * Byte-faithful fixtures captured from the **live** server
 * (`opencode` 1.18.31, `http://127.0.0.1:4096`) with `Invoke-WebRequest` /
 * `curl -sN`. Only volatile values are normalised: ids are shortened to a stable
 * prefix, epoch timestamps are kept as the integers actually observed, and long
 * free-text fields are truncated. **Key names, nesting and value types are
 * exactly what the server sent** — that is the point of this file, since v2's
 * envelopes differ from v1's in almost every family.
 *
 * Prefixed with `V2` so it cannot collide with fixtures elsewhere in this
 * package. Nothing here is re-typed from the OpenAPI document; where a shape is
 * document-derived instead, the test says `UNVERIFIED`.
 */
internal object V2ApiFixtures {

    // ------------------------------------------------------------------ health

    /** `GET /api/info` — measured on 2.0.11: the v2 probe signal. */
    const val INFO_V2 = """{"version":"2.0.11","pid":67503,"urls":["http://127.0.0.1:4095"],"paths":{"tmp":"/tmp/opencode"}}"""

    /** `GET /global/health` — measured on 1.18.31: the v1 probe signal. */
    const val HEALTH_V1 = """{"healthy":true,"version":"1.18.31"}"""

    /** `GET /api/health` — measured: exactly one key, no envelope, no version. */
    const val HEALTH = """{"healthy":true}"""

    /** The SPA fallback every unimplemented `/api/…` path answers: `200` + this. */
    const val SPA_HTML = """<!doctype html>
<html lang="en" style="background-color: var(--v2-background-bg-deep, #fafafa)">
  <head>
    <meta charset="utf-8" />
    <title>OpenCode</title>
  </head>
</html>"""

    /** A genuine v2 error body: `_tag`-discriminated JSON, not HTML. */
    const val SESSION_NOT_FOUND = """{
  "_tag": "SessionNotFoundError",
  "sessionID": "ses_doesnotexist",
  "message": "Session not found: ses_doesnotexist"
}"""

    /** `GET /api/fs/find` without `query` — measured 400. */
    const val MISSING_QUERY_ERROR = """{
  "_tag": "InvalidRequestError",
  "message": "Missing key\n  at [\"query\"]",
  "kind": "Query"
}"""

    // ---------------------------------------------------------------- location

    /** `GET /api/location` — measured bare, **no** `data` envelope. */
    const val LOCATION = """{"directory":"C:\\WINDOWS\\system32","project":{"id":"global","directory":"C:\\"}}"""

    /** `GET /api/session/active` idle — measured. */
    const val ACTIVE_EMPTY = """{"data":{}}"""

    /** `GET /api/session/active` while a turn drained — measured. */
    const val ACTIVE_BUSY = """{"data":{"ses_f3d84f9d4ffeoLtv5OmiMrU6yO":{"type":"running"}}}"""

    // ---------------------------------------------------------------- sessions

    /**
     * `GET /api/session?limit=2` — measured. Note the `cursor` sibling only
     * appears once `limit` is passed, and both anchors are opaque base64.
     */
    const val SESSION_PAGE = """{"data":[{"id":"ses_f408ec4a0ffeKjpcGKG4kfGEBR","projectID":"global","agent":"build","model":{"id":"muse-spark-1.3-contributor","providerID":"opencode-go","variant":"default"},"cost":0.002900526,"tokens":{"input":28621,"output":20,"reasoning":171,"cache":{"read":113,"write":0}},"time":{"created":1789918264159,"updated":1789918272479},"title":"Friendly greeting","location":{"directory":"C:\\sensorsdata\\main\\program\\DSH 临时会话"},"subpath":"sensorsdata/main/program/DSH 临时会话"}],"cursor":{"previous":"eyJhbmNob3IiOnsiaWQiOiJzZXNfZjNkODM5ZTVkZmZlN3d0UTNSVUlnTzRGU2IiLCJ0aW1lIjoxNzg5OTY5MzI2NDk4LCJkaXJlY3Rpb24iOiJwcmV2aW91cyJ9fQ","next":"eyJhbmNob3IiOnsiaWQiOiJzZXNfZjNkODNjN2M4ZmZlaXVHOURuNFQzSnNJU28iLCJ0aW1lIjoxNzg5OTY5MzE1ODk2LCJkaXJlY3Rpb24iOiJuZXh0In19"}}"""

    /** `POST /api/session` → measured 200. Title and ids are the server's own. */
    const val SESSION_CREATED = """{"data":{"id":"ses_f3d84f9d4ffeoLtv5OmiMrU6yO","projectID":"ae54078c0e09bf8e7cbeefd35db19aaa6b064e46","agent":"build","model":{"id":"gpt-5.6-sol-fast","providerID":"openai","variant":"default"},"cost":0,"tokens":{"input":0,"output":0,"reasoning":0,"cache":{"read":0,"write":0}},"time":{"created":1789969237710,"updated":1789969237710},"title":"New session - 2026-09-21T05:40:37.710Z","location":{"directory":"C:\\sensorsdata\\main\\program\\opencode-android-shell"}}}"""

    /**
     * `GET /api/session/{id}/message?order=asc` after a real turn — measured.
     * The two element shapes are the whole point: a flat `type` tag, `content[]`
     * instead of v1's `parts[]`, and no `info` wrapper anywhere.
     */
    const val MESSAGES = """{"data":[{"id":"msg_0c27b1da6001O2neNc6Sg8xZ9t","time":{"created":1789969243559},"text":"Say hi","type":"user"},{"id":"msg_0c27b72a1001xI7Uoa2Ek3AoBy","time":{"created":1789969265313,"completed":1789969271792},"type":"assistant","agent":"build","model":{"id":"gpt-5.6-sol","providerID":"openai","variant":"default"},"content":[{"type":"text","id":"msg_097adb71f5c62c62016ab0c370b2b487d0b46d6573fe1c20bb","text":"Hi!"}],"snapshot":{"start":"2549b7b15e4842f60b68e657ab0394505e21bc59"},"finish":"stop","cost":0,"tokens":{"input":8234,"output":6,"reasoning":0,"cache":{"read":0,"write":0}}}],"cursor":{"previous":"eyJpZCI6Im1zZ18wYzI3YjcyYTEwMDF4STdVb2EyRWszQW9CeSIsIm9yZGVyIjoiZGVzYyIsImRpcmVjdGlvbiI6InByZXZpb3VzIn0","next":"eyJpZCI6Im1zZ18wYzI3YjFkYTYwMDFPMm5lTmM2U2c4eFo5dCIsIm9yZGVyIjoiZGVzYyIsImRpcmVjdGlvbiI6Im5leHQifQ"}}"""

    /** `GET /api/session/{id}/message` on a session with no v2 projection — measured. */
    const val MESSAGES_EMPTY = """{"data":[],"cursor":{"previous":null,"next":null}}"""

    /** `{data:Session.Inbox.User}` from `POST /api/session/{id}/prompt` — measured live 2026-09-21 (flat body). */
    const val PROMPT_ADMITTED = """{"data":{"id":"msg_0c27b1da6001O2neNc6Sg8xZ9t","sessionID":"ses_f3d84f9d4ffeoLtv5OmiMrU6yO","time":{"created":1789969243559},"type":"user","payload":{"text":"Say hi"},"delivery":"queue"}}"""

    /** Kept: frame DTO shape used by the log-stream replay path (route removed, A5 S0.3). */
    const val HISTORY = """{"data":[{"id":"evt_0c27b72a10022F3qbete8na4CA","type":"session.next.step.started","durable":{"aggregateID":"ses_f3d84f9d4ffeoLtv5OmiMrU6yO","seq":4,"version":1},"data":{"timestamp":1789969265313,"sessionID":"ses_f3d84f9d4ffeoLtv5OmiMrU6yO","assistantMessageID":"msg_0c27b72a1001xI7Uoa2Ek3AoBy","agent":"build","model":{"id":"gpt-5.6-sol","providerID":"openai","variant":"default"},"snapshot":"2549b7b15e4842f60b68e657ab0394505e21bc59"}},{"id":"evt_0c27b72a5001sbcwWm0FcddLX1","type":"session.next.text.started","durable":{"aggregateID":"ses_f3d84f9d4ffeoLtv5OmiMrU6yO","seq":5,"version":1},"data":{"timestamp":1789969265317,"sessionID":"ses_f3d84f9d4ffeoLtv5OmiMrU6yO","assistantMessageID":"msg_0c27b72a1001xI7Uoa2Ek3AoBy","textID":"msg_097adb71f5c62c62016ab0c370b2b487d0b46d6573fe1c20bb"}}],"hasMore":true}"""

    /** `{data:RevertState}` from `POST /api/session/{id}/revert/stage` — measured. */
    const val REVERT_STAGE = """{"data":{"messageID":"msg_0c27b3d4a001X0L2Er4RZTpEpe","snapshot":"c06da87d1093008bc7d538ac4adca2b762814fd8","diff":"","files":[]}}"""

    // --------------------------------------------------------------- SSE frames

    /**
     * The raw bytes `curl -sN /api/session/{id}/event?after=3` produced, kept
     * verbatim including the blank-line framing and the `: heartbeat` comment.
     * The envelope is `{id,type,durable{aggregateID,seq,version},data}` — the v1
     * wrapper `{"payload":{"id","type","properties"}}` is **absent**.
     */
    const val SSE_SESSION = """data: {"id":"evt_0c27b72a10022F3qbete8na4CA","type":"session.next.step.started","durable":{"aggregateID":"ses_f3d84f9d4ffeoLtv5OmiMrU6yO","seq":4,"version":1},"data":{"timestamp":1789969265313,"sessionID":"ses_f3d84f9d4ffeoLtv5OmiMrU6yO","assistantMessageID":"msg_0c27b72a1001xI7Uoa2Ek3AoBy","agent":"build","model":{"id":"gpt-5.6-sol","providerID":"openai","variant":"default"},"snapshot":"2549b7b15e4842f60b68e657ab0394505e21bc59"}}

data: {"id":"evt_0c27b8a48001cFWE62O54bKASb","type":"session.next.text.ended","durable":{"aggregateID":"ses_f3d84f9d4ffeoLtv5OmiMrU6yO","seq":6,"version":1},"data":{"timestamp":1789969271368,"sessionID":"ses_f3d84f9d4ffeoLtv5OmiMrU6yO","assistantMessageID":"msg_0c27b72a1001xI7Uoa2Ek3AoBy","textID":"msg_097adb71f5c62c62016ab0c370b2b487d0b46d6573fe1c20bb","text":"Hi!"}}

: heartbeat

data: {"id":"evt_0c27b8bf0001rtHhQa2HGBqCbq","type":"session.next.step.ended","durable":{"aggregateID":"ses_f3d84f9d4ffeoLtv5OmiMrU6yO","seq":7,"version":2},"data":{"timestamp":1789969271792,"sessionID":"ses_f3d84f9d4ffeoLtv5OmiMrU6yO","assistantMessageID":"msg_0c27b72a1001xI7Uoa2Ek3AoBy","finish":"stop","cost":0,"tokens":{"input":8234,"output":6,"reasoning":0,"cache":{"read":0,"write":0}},"snapshot":"293270fd54530bc6c6063a4d0cadbe704c6c1171","files":["research/final_visual_fix.md"]}}

"""

    /**
     * The first frames `curl -sN /api/event` produced — measured. Note that this
     * stream mixes the v2 envelope (`pty.updated`, no `durable`) with v1-shaped
     * `data.info` payloads (`session.updated`, carrying `"version":"1.18.31"`).
     */
    const val SSE_GLOBAL = """data: {"id":"evt_0c27c0805001HZlMhVHKcRplKV","type":"server.connected","data":{}}

: heartbeat

data: {"id":"evt_0c27c180e001Iy16NB3M5X2l1G","type":"pty.updated","location":{"directory":"C:\\WINDOWS\\system32"},"data":{"info":{"id":"pty_0c27bd974001Yrmql3g4h5KZYX","title":"v2probe2","command":"cmd.exe","args":[],"cwd":"C:\\WINDOWS\\system32","status":"running","pid":49008}}}

data: {"id":"evt_0c27c099c00114xpUSNYlijzqQ","type":"session.updated","durable":{"aggregateID":"ses_f3d83fd06ffeZsABPiDtXEwenN","seq":10,"version":1},"location":{"directory":"C:\\Windows\\System32"},"data":{"sessionID":"ses_f3d83fd06ffeZsABPiDtXEwenN","info":{"id":"ses_f3d83fd06ffeZsABPiDtXEwenN","slug":"crisp-mountain","projectID":"global","directory":"C:\\Windows\\System32","path":"Windows/System32","cost":0,"tokens":{"input":0,"output":0,"reasoning":0,"cache":{"read":0,"write":0}},"title":"wave6-menu-probe","agent":"build","model":{"id":"gpt-5.6-sol-fast","providerID":"openai","variant":"default"},"version":"1.18.31","time":{"created":1789969302265,"updated":1789969303963}}}}

"""

    /**
     * A v1 `/event` frame, used **only** to assert that v2 parsing does not
     * mistake it for a v2 envelope. Never sent by `/api/session/{id}/event`.
     */
    const val SSE_V1_WRAPPER = """data: {"payload":{"id":"evt_1","type":"message.updated","properties":{"sessionID":"ses_f408ec4a0ffeKjpcGKG4kfGEBR"}}}

"""

    // ---------------------------------------------------------------- catalogs

    /**
     * `GET /api/agent` — measured 275 entries; one primary and one hidden,
     * truncated. The v2 key for the name is `id`, and there is no `native`.
     */
    const val AGENTS = """{"location":{"directory":"C:\\WINDOWS\\system32","project":{"id":"global","directory":"C:\\"}},"data":[{"id":"build","request":{"headers":{},"body":{}},"system":"You are opencode","description":"Build agent","mode":"primary","hidden":false,"permissions":{}},{"id":"plan","request":{"headers":{},"body":{}},"description":"Plan agent","mode":"primary","hidden":false,"permissions":{}},{"id":"compaction","request":{"headers":{},"body":{}},"description":"Compaction helper","mode":"primary","hidden":true,"permissions":{}}]}"""

    /** `GET /api/model` — measured; one entry, trimmed to the observed keys. */
    const val MODELS = """{"location":{"directory":"C:\\WINDOWS\\system32","project":{"id":"global","directory":"C:\\"}},"data":[{"id":"stepfun/step-5-preview","providerID":"nano-gpt","family":"step","name":"Step 5 Preview","api":{"id":"stepfun/step-5-preview","type":"aisdk","package":"@ai-sdk/openai-compatible","url":"https://nano-gpt.com/api/v1"},"capabilities":{"tools":true,"input":["text","image","video"],"output":["text"]},"request":{"headers":{},"body":{}},"variants":[],"time":{"released":1789862400000},"cost":[{"input":1,"output":2.7,"cache":{"read":0.05,"write":0}}],"status":"active","enabled":true,"limit":{"context":1000000,"input":1000000,"output":1000000}}]}"""

    /** `GET /api/provider` — measured; note there is no `models` map. */
    const val PROVIDERS = """{"location":{"directory":"C:\\WINDOWS\\system32","project":{"id":"global","directory":"C:\\"}},"data":[{"id":"subconscious","name":"Subconscious","api":{"type":"aisdk","package":"@ai-sdk/anthropic","url":"https://api.subconscious.dev/v1"},"request":{"headers":{"anthropic-beta":"interleaved-thinking-2025-05-14,fine-grained-tool-streaming-2025-05-14"},"body":{}}},{"id":"opencode-go","name":"OpenCode Go","api":{"type":"aisdk","package":"@ai-sdk/openai-compatible","url":"https://opencode.ai/zen/go/v1"},"request":{"headers":{},"body":{}}}]}"""

    /** `GET /api/provider/{providerID}` — measured 200 for `opencode-go`. */
    const val PROVIDER_ONE = """{"location":{"directory":"C:\\WINDOWS\\system32","project":{"id":"global","directory":"C:\\"}},"data":{"id":"opencode-go","name":"OpenCode Go","api":{"type":"aisdk","package":"@ai-sdk/openai-compatible","url":"https://opencode.ai/zen/go/v1"},"request":{"headers":{},"body":{}}}}"""

    /** `GET /api/command` scoped to the repo directory — measured. `source` is gone. */
    const val COMMANDS = """{"location":{"directory":"C:\\sensorsdata\\main\\program\\opencode-android-shell","project":{"id":"ae54078c0e09bf8e7cbeefd35db19aaa6b064e46","directory":"C:\\sensorsdata\\main\\program\\opencode-android-shell"}},"data":[{"name":"init","template":"Create or update `AGENTS.md` for this repository.","description":"Create agents.md"}]}"""

    /** `GET /api/skill` scoped to the repo directory — measured. */
    const val SKILLS = """{"location":{"directory":"C:\\sensorsdata\\main\\program\\opencode-android-shell","project":{"id":"ae54078c0e09bf8e7cbeefd35db19aaa6b064e46","directory":"C:\\sensorsdata\\main\\program\\opencode-android-shell"}},"data":[{"name":"customize-opencode","description":"Use ONLY when the user is editing opencode's own configuration.","location":"C:\\Users\\xiufe\\.config\\opencode\\skill\\customize-opencode\\SKILL.md","content":"# Customize opencode"}]}"""

    /** `GET /api/integration` — measured 222 entries; two kept. */
    const val INTEGRATIONS = """{"location":{"directory":"C:\\WINDOWS\\system32","project":{"id":"global","directory":"C:\\"}},"data":[{"id":"302ai","name":"302.AI","methods":[{"type":"key"},{"type":"env","names":["302AI_API_KEY"]}],"connections":[]},{"id":"abacus","name":"Abacus","methods":[{"type":"key"}],"connections":[]}]}"""

    /** `GET /api/reference` — measured `[]` at both locations. */
    const val REFERENCES_EMPTY = """{"location":{"directory":"C:\\WINDOWS\\system32","project":{"id":"global","directory":"C:\\"}},"data":[]}"""

    // ------------------------------------------------------------- filesystem

    /** `GET /api/fs/list` — measured. Paths are relative and keep the trailing `\`. */
    const val FS_LIST = """{"location":{"directory":"C:\\sensorsdata\\main\\program\\opencode-android-shell","project":{"id":"ae54078c0e09bf8e7cbeefd35db19aaa6b064e46","directory":"C:\\sensorsdata\\main\\program\\opencode-android-shell"}},"data":[{"path":"_upstream\\","type":"directory"},{"path":".git\\","type":"directory"},{"path":"app\\","type":"directory"},{"path":".gitignore","type":"file"},{"path":"gradlew.bat","type":"file"}]}"""

    /** `GET /api/fs/list?path=app` — measured: entries stay rooted at the location. */
    const val FS_LIST_SUBDIR = """{"location":{"directory":"C:\\sensorsdata\\main\\program\\opencode-android-shell","project":{"id":"ae54078c0e09bf8e7cbeefd35db19aaa6b064e46","directory":"C:\\sensorsdata\\main\\program\\opencode-android-shell"}},"data":[{"path":"app\\build\\","type":"directory"},{"path":"app\\src\\","type":"directory"}]}"""

    /** `GET /api/fs/find?query=gradlew&limit=5` — measured; ranked, not literal. */
    const val FS_FIND = """{"location":{"directory":"C:\\WINDOWS\\system32","project":{"id":"global","directory":"C:\\"}},"data":[{"path":"config/systemprofile/AppData/Local/AMD_Common/NewComponents.json","type":"file"},{"path":"config/systemprofile/AppData/Local/TeamViewer\\","type":"directory"}]}"""

    /** `GET /api/pty` with none open — measured. */
    const val PTY_LIST_EMPTY = """{"location":{"directory":"C:\\WINDOWS\\system32","project":{"id":"global","directory":"C:\\"}},"data":[]}"""

    /** `POST /api/pty` — measured 200. */
    const val PTY_CREATED = """{"location":{"directory":"C:\\WINDOWS\\system32","project":{"id":"global","directory":"C:\\"}},"data":{"id":"pty_0c27bd974001Yrmql3g4h5KZYX","title":"v2probe","command":"cmd.exe","args":[],"cwd":"C:\\WINDOWS\\system32","status":"running","pid":49008}}"""

    /** `PUT /api/pty/{ptyID}` — measured 200 with the new title echoed back. */
    const val PTY_UPDATED = """{"location":{"directory":"C:\\WINDOWS\\system32","project":{"id":"global","directory":"C:\\"}},"data":{"id":"pty_0c27bd974001Yrmql3g4h5KZYX","title":"v2probe2","command":"cmd.exe","args":[],"cwd":"C:\\WINDOWS\\system32","status":"running","pid":49008}}"""

    /** `POST /api/pty/{ptyID}/connect-token` with `x-opencode-ticket: 1` — measured. */
    const val PTY_TICKET = """{"location":{"directory":"C:\\WINDOWS\\system32","project":{"id":"global","directory":"C:\\"}},"data":{"ticket":"08b2d9f5-c399-4213-bd40-564d93e649c4","expires_in":60}}"""

    /** The same route without the header — measured 403. */
    const val PTY_TICKET_FORBIDDEN = """{
  "_tag": "ForbiddenError",
  "message": "Invalid PTY connect token request"
}"""

    /** `GET /api/pty/{ptyID}` after `DELETE` — measured 404. */
    const val PTY_NOT_FOUND = """{
  "_tag": "PtyNotFoundError",
  "ptyID": "pty_0c27bd974001Yrmql3g4h5KZYX",
  "message": "PTY session not found: pty_0c27bd974001Yrmql3g4h5KZYX"
}"""

    // -------------------------------------------------- permission / question

    /** `GET /api/session/{id}/permission` — measured; **no** `location` envelope. */
    const val SESSION_PERMISSIONS_EMPTY = """{"data":[]}"""

    /** `GET /api/session/{id}/form` — measured live 2026-09-21 (self-created form). */
    const val SESSION_FORMS_ONE = """{"data":[{"id":"frm_0c38a488e001UE9X8YyPgvMfb0","sessionID":"ses_1","title":"t","fields":[{"key":"q","title":"Q?","type":"string"}]}]}"""

    /** `GET /api/permission/request` — measured; **with** a `location` envelope. */
    const val PENDING_PERMISSIONS_EMPTY = """{"location":{"directory":"C:\\WINDOWS\\system32","project":{"id":"global","directory":"C:\\"}},"data":[]}"""

    /** `GET /api/permission/saved` — measured. */
    const val SAVED_PERMISSIONS_EMPTY = """{"data":[]}"""

    /** `POST /api/session/{id}/permission` — measured live: self-created asks auto-allow. */
    const val PERMISSION_CREATED = """{"data":{"id":"per_0c38a26ed001t1uD6y4X3vD6Ff","effect":"allow"}}"""

    /**
     * `UNVERIFIED` instance shape: the live server had no pending ask, so this is
     * assembled from the OpenAPI `PermissionV2Request`/`QuestionV2Request`
     * schemas to pin the parser's field names against a future rename. The
     * envelopes above are measured; these two are not.
     */
    const val PERMISSION_REQUEST_UNVERIFIED = """[{"id":"per_1","sessionID":"ses_1","action":"bash","resources":["rm -rf build"],"save":["rm *"],"source":{"type":"tool","messageID":"msg_1","id":"call_1"}}]"""

    /** Live v2 turn frames (captured 2026-09-21 through a real `reply with exactly: YO` turn on 4095). */
    const val SSE_V2_TURN = """data: {"id":"evt_0c403bcb6001JsjdTAw3umnNcj","created":1789994974390,"type":"session.inbox.enqueued","location":{"directory":"/home/xiufeigo"},"data":{"inboxID":"msg_0c403bcb5001RXObnn0kJmqL6r","sessionID":"ses_1","item":{"type":"user","payload":{"text":"reply with exactly: YO"},"delivery":"steer"}},"durable":{"aggregateID":"ses_1","seq":1,"version":1}}

data: {"id":"evt_0c403bcb8001QK83hkIvuAHj4p","created":1789994974392,"type":"session.execution.started","data":{"sessionID":"ses_1"},"durable":{"aggregateID":"ses_1","seq":2,"version":1}}

data: {"id":"evt_0c403c428001HgRoW3L6Hed91K","created":1789994976296,"type":"session.text.started","location":{"directory":"/home/xiufeigo"},"data":{"sessionID":"ses_1","assistantMessageID":"msg_a1","ordinal":0},"durable":{"aggregateID":"ses_1","seq":6,"version":1}}

data: {"id":"evt_0c403c4360014P8qi7YcU5uIwQ","created":1789994976310,"type":"session.text.delta","location":{"directory":"/home/xiufeigo"},"data":{"sessionID":"ses_1","assistantMessageID":"msg_a1","ordinal":0,"delta":"YO"}}

data: {"id":"evt_0c403c437001r224VWYyK5yMbt","created":1789994976311,"type":"session.text.ended","location":{"directory":"/home/xiufeigo"},"data":{"sessionID":"ses_1","assistantMessageID":"msg_a1","ordinal":0,"text":"YO"},"durable":{"aggregateID":"ses_1","seq":7,"version":1}}

data: {"id":"evt_0c403c438001streamed","created":1789994976313,"type":"session.step.streamed","location":{"directory":"/home/xiufeigo"},"data":{"sessionID":"ses_1","assistantMessageID":"msg_a1"},"durable":{"aggregateID":"ses_1","seq":8,"version":1}}

data: {"id":"evt_0c403c43b0016BXG4zDaR84Na8","created":1789994976315,"type":"session.step.ended","location":{"directory":"/home/xiufeigo"},"data":{"sessionID":"ses_1","assistantMessageID":"msg_a1","finish":"stop","rawFinish":"stop","cost":0.00138075,"tokens":{"input":9197,"output":2,"reasoning":0,"cache":{"read":0,"write":0}}},"durable":{"aggregateID":"ses_1","seq":9,"version":1}}

data: {"id":"evt_0c403c43c001HX5i54uEacgdO3","created":1789994976316,"type":"session.usage.updated","data":{"sessionID":"ses_1","cost":0.00138075,"tokens":{"input":9197,"output":2,"reasoning":0,"cache":{"read":0,"write":0}}}}

data: {"id":"evt_0c403c43d001nCD37hiqz2j7cO","created":1789994976317,"type":"session.execution.succeeded","data":{"sessionID":"ses_1"},"durable":{"aggregateID":"ses_1","seq":10,"version":1}}

: heartbeat

data: {"type":"log.synced","aggregateID":"ses_1","seq":10}

"""

    /** `log.synced` watermark frame (per-session log stream opener). */
    const val SSE_LOG_SYNCED = """data: {"type":"log.synced","aggregateID":"ses_1","seq":10}

"""

    /** `UNVERIFIED` — see [PERMISSION_REQUEST_UNVERIFIED]. Kept: v1 question behavior is untouched (OpenCodeClient). */
    const val QUESTION_REQUEST_UNVERIFIED = """[{"id":"que_1","sessionID":"ses_1","questions":[{"question":"Which one?","header":"Pick","options":[{"label":"A","description":"first"},{"label":"B","description":"second"}],"multiple":false,"custom":true}],"tool":{"messageID":"msg_1","callID":"call_1"}}]"""

    /** `GET /api/vcs` — measured live 2026-09-21: `{location,data:{branch:{}}}`. */
    const val VCS = """{"location":{"directory":"/home/xiufeigo"},"data":{"branch":{}}}"""

    /** `GET /api/model/default` — measured live (trimmed to envelope + ids). */
    const val MODEL_DEFAULT =
        """{"location":{"directory":"/home/xiufeigo"},"data":{"id":"deepseek-v4.1-flash","modelID":"deepseek-v4.1-flash","providerID":"opencode-go","family":"deepseek-flash","name":"DeepSeek V4.1 Flash","status":"active","enabled":true,"capabilities":{"tools":true,"input":["text","image"],"output":["text"]},"limit":{"context":1000000,"output":384000},"cost":[{"input":0.15,"output":0.6,"cache":{"read":0.003,"write":0}}],"variants":[{"id":"low"},{"id":"high"},{"id":"max"}]}}"""

    /** `GET /api/project` — measured live: a BARE ARRAY (no envelope). */
    const val PROJECTS_BARE =
        """[{"id":"0a95337c0d21b14f8a06c6fca757ff194a73a5d4","canonical":"/home/xiufeigo","time":{"created":1789976843241,"updated":1789976843241},"sandboxes":[]}]"""

    /** `GET /api/config` — measured live: a BARE ARRAY `[{type,path,info}]`. */
    const val CONFIGS_BARE = """[{"type":"document","path":"/home/xiufeigo/.config/opencode/opencode.json","info":{"model":"opencode/deepseek-v4.1-flash"}}]"""

    /** `GET /api/session/{id}/diff` — measured live: `{data:[]}` on a real session. */
    const val SESSION_DIFF_EMPTY = """{"data":[]}"""

    /** `GET /api/session/{id}/message/{messageID}` — measured live (assistant row). */
    const val MESSAGE_ONE =
        """{"data":{"id":"msg_f8925f266001F5GKKtGrfN9I4p","time":{"created":1784712393318,"completed":1784712419789},"type":"assistant","agent":"build","model":{"id":"big-pickle","providerID":"opencode"},"content":[{"type":"tool","id":"call_bee4f2ed90154deaaaada664","name":"bash","state":{"status":"completed","input":{"command":"echo hi"},"content":[{"type":"text","text":"hi"}],"metadata":{"output":"hi","exit":0,"truncated":false}}}]}}"""

    /** `GET /api/agent` — measured live 2026-09-21: entries carry `name` ("Build"). */
    const val AGENT_ONE =
        """{"location":{"directory":"/home/xiufeigo"},"data":[{"id":"build","name":"Build","request":{"settings":{},"headers":{},"body":{}},"description":"The default agent.","mode":"primary","hidden":false,"permissions":[]}]}"""

    /** `GET /api/skill` — measured live: entries carry `path` (not `location`). */
    const val SKILL_ONE =
        """{"location":{"directory":"/home/xiufeigo"},"data":[{"id":"opencode","name":"OpenCode","description":"d","path":"/builtin/opencode.md","content":"# x"}]}"""
}
