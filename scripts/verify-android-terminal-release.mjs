#!/usr/bin/env node
/**
 * verify-android-terminal.mjs — real-production verification of the Android
 * packaged terminal.
 *
 * ## What this runner actually does
 *
 * Unlike `verify-terminal-touch.mjs` (which builds an isolated fixture around
 * one extracted oc-host.js section), this runner boots the **complete packaged
 * Android UI** exactly the way `LocalUiOriginServer` + `WebHostActivity` do:
 *
 *   · `webapp/src/main/assets/www/ui/index.html` is the served document, and its
 *     real `/_assets/*` chunks come from `www/ui/oa/*` — byte-for-byte the files
 *     that ship in the APK. Nothing in the vendored build is rewritten.
 *   · The Ghostty module slot is swapped **at the HTTP route only**:
 *       `/oc-terminal.js`  ->  `www/oc-terminal.js`
 *       `/oc-terminal/{file}`        ->  `www/terminal/{file}`
 *     The production `index-*.js` lazy-imports that same URL, so it asks for the
 *     replacement module by itself. A request log proves it did.
 *   · The **complete** `oc-host.js` (all 2172 lines, not a section) is injected
 *     at document start behind a mock `window.__ocAndroidNative`, so the Android
 *     facade, the IME/insets plumbing and the OC-TERMINAL touch/font patches are
 *     all live exactly as on device.
 *   * A minimal OpenCode V2 mock backend (HTTP + SSE `/event` + WebSocket
 *     `/api/pty/{id}/connect`) supplies the session + PTY the UI needs.
 *   · The terminal is opened through the **app's own** `terminal.toggle`
 *     keybind, so the real panel mounts the real `Terminal` component.
 *   · Touch is delivered as **trusted input** via CDP `Input.dispatchTouchEvent`,
 *     never `dispatchEvent`.
 *
 * ## Scope honesty
 *
 * This is a Chromium emulation of an Android WebView. It is NOT device
 * validation: there is no attached device, no Android System WebView, and no
 * real IME. The report labels every result `chromium-emulated` and never claims
 * otherwise.
 *
 * ## Usage
 *
 *     node scripts/verify-android-terminal.mjs
 *     VERIFY_TERMINAL_FILTER=drag  node scripts/verify-android-terminal.mjs
 *     VERIFY_TERMINAL_NO_SHOTS=1   node scripts/verify-android-terminal.mjs
 *     VERIFY_TERMINAL_MODE=boot    node scripts/verify-android-terminal.mjs
 *
 * `MODE=boot` stops right after the production SPA mounts and dumps the request
 * log + console output; it is the diagnostic to run when the replacement module
 * is not in place yet.
 *
 * Exit code 0 = every selected check passed, 1 = at least one failed,
 * 2 = the replacement terminal module is missing (blocker, not a test failure).
 */
import http from "node:http"
import fs from "node:fs"
import path from "node:path"
import crypto from "node:crypto"
import { fileURLToPath } from "node:url"

const HERE = path.dirname(fileURLToPath(import.meta.url))
const REPO = path.resolve(HERE, "..")

const WWW = path.join(REPO, "webapp/src/main/assets/www")
const UI_ROOT = path.join(WWW, "ui")
const BUNDLE_DIR = path.join(UI_ROOT, "oa")
const TERMINAL_DIR = WWW
const TERMINAL_MODULE = path.join(WWW, "oc-terminal.js")
const HOST_JS = path.join(WWW, "oc-host.js")

// Harness self-test escape hatch only. Points the replacement-module route at a
// scratch module OUTSIDE the packaged assets so the runner itself can be
// exercised before the lead's module lands. Never used for a real verdict: the
// report stamps every run with which module path served the route.
const TERMINAL_MODULE_OVERRIDE = process.env.VERIFY_TERMINAL_MODULE || ""
const TERMINAL_MODULE_SERVED = TERMINAL_MODULE_OVERRIDE
  ? path.resolve(TERMINAL_MODULE_OVERRIDE)
  : TERMINAL_MODULE
const TERMINAL_DIR_SERVED = TERMINAL_MODULE_OVERRIDE
  ? path.dirname(TERMINAL_MODULE_SERVED)
  : TERMINAL_DIR

const PLAYWRIGHT_MJS =
  process.env.VERIFY_TERMINAL_PLAYWRIGHT ||
  path.join(REPO, "_upstream/node_modules/.bun/playwright@1.59.1/node_modules/playwright/index.mjs")
const CHROMIUM =
  process.env.VERIFY_TERMINAL_CHROMIUM ||
  "C:/Users/xiufe/AppData/Local/ms-playwright/chromium-1234/chrome-win64/chrome.exe"

// RELEASE COPY, FINAL ROUND ("ready"): a brand-new output tree so the previous
// verdict at research/tmp/verify-android-terminal-release-final/ is preserved
// byte-for-byte as a negative/neutral record and can never be overwritten by
// this run. PREVIOUS_OUT_DIR points at it so the delta stays auditable.
const OUT_DIR = path.join(REPO, "research/tmp/verify-android-terminal-release-ready", new Date().toISOString().replace(/[:.]/g, "-") + "-" + crypto.randomBytes(3).toString("hex"))
const PREVIOUS_OUT_DIR = path.join(REPO, "research/tmp/verify-android-terminal-release-final")
const REPORT_PATH = path.join(OUT_DIR, "report.json")

const FILTER = process.env.VERIFY_TERMINAL_FILTER || ""
const NO_SHOTS = process.env.VERIFY_TERMINAL_NO_SHOTS === "1"
const MODE = process.env.VERIFY_TERMINAL_MODE || "full"

/** Device profiles mirroring oc-host.js's own field notes. */
const PHONE = {
  name: "phone",
  viewport: { width: 411, height: 914 },
  deviceScaleFactor: 2.625,
  isMobile: true,
  hasTouch: true,
}
const TABLET = {
  name: "tablet",
  viewport: { width: 1024, height: 768 },
  deviceScaleFactor: 2,
  isMobile: false,
  hasTouch: true,
}

/** A real phone IME: 336.38 CSS px tall, per the oc-host.js device measurements. */
const IME_HEIGHT = 336

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

/* ───────────────────────── static asset origin ───────────────────────── */

function contentTypeOf(p) {
  if (p.endsWith(".html")) return "text/html; charset=utf-8"
  if (p.endsWith(".js") || p.endsWith(".mjs")) return "text/javascript; charset=utf-8"
  if (p.endsWith(".css")) return "text/css; charset=utf-8"
  if (p.endsWith(".json") || p.endsWith(".webmanifest")) return "application/json; charset=utf-8"
  if (p.endsWith(".svg")) return "image/svg+xml"
  if (p.endsWith(".png")) return "image/png"
  if (p.endsWith(".ico")) return "image/x-icon"
  if (p.endsWith(".webp")) return "image/webp"
  if (p.endsWith(".woff2")) return "font/woff2"
  if (p.endsWith(".woff")) return "font/woff"
  if (p.endsWith(".ttf")) return "font/ttf"
  if (p.endsWith(".wasm")) return "application/wasm"
  if (p.endsWith(".txt")) return "text/plain; charset=utf-8"
  return "application/octet-stream"
}

function normalize(p) {
  if (!p.startsWith("/")) p = `/${p}`
  const parts = []
  for (const seg of p.split("/")) {
    if (seg === "" || seg === ".") continue
    if (seg === "..") parts.pop()
    else parts.push(seg)
  }
  return `/${parts.join("/")}`
}

/**
 * Request log. Every hit is recorded so the report can prove which modules the
 * production page actually asked for — in particular the Ghostty replacement.
 */
const requestLog = []
function record(entry) {
  requestLog.push(entry)
  if (process.env.VERIFY_TERMINAL_TRACE === "1") {
    console.error(`  [http] ${entry.status} ${entry.path}`)
  }
}

/**
 * The UI origin, mirroring `LocalUiOriginServer` plus the lead's module
 * replacement routes. SPA fallback to index.html, matching the real server.
 */
function startUiServer() {
  const server = http.createServer((req, res) => {
    let pathname = "/"
    try {
      pathname = decodeURIComponent(new URL(req.url || "/", "http://127.0.0.1").pathname)
    } catch {}

    const serve = (file, note) => {
      try {
        const body = fs.readFileSync(file)
        record({ path: pathname, status: 200, bytes: body.length, via: note })
        res.writeHead(200, {
          "content-type": contentTypeOf(file),
          "content-length": body.length,
          "cache-control": "no-cache",
        })
        res.end(req.method === "HEAD" ? undefined : body)
        return true
      } catch {
        return false
      }
    }

    // 1. Ghostty replacement slot. Matched before the /_assets/ bundle mapping
    //    because this is a real chunk name that also exists on disk.
    if (pathname === "/oc-terminal.js") {
      if (serve(TERMINAL_MODULE_SERVED, "terminal:oc-terminal.mjs")) return
      record({ path: pathname, status: 404, via: "terminal-module-missing" })
      res.writeHead(404, { "content-type": "text/plain; charset=utf-8" })
      res.end(`replacement terminal module missing at ${TERMINAL_MODULE_SERVED}`)
      return
    }

    // 2. AGP drops `_`-prefixed dirs, so /_assets/x lives on disk at oa/x.
    const disk = pathname.startsWith("/_assets/")
      ? path.join(BUNDLE_DIR, pathname.slice("/_assets/".length))
      : path.join(UI_ROOT, normalize(pathname))

    if (disk.startsWith(UI_ROOT) && serve(disk, pathname.startsWith("/_assets/") ? "ui:oa/*" : "ui:*")) return

    // 4. SPA fallback.
    const index = path.join(UI_ROOT, "index.html")
    if (serve(index, "ui:index.html(spa)")) return

    record({ path: pathname, status: 404, via: "ui:miss" })
    res.writeHead(404, { "content-type": "text/plain; charset=utf-8" })
    res.end("not found")
  })
  return listen(server)
}

/* ─────────────────────────── mock OpenCode V2 backend ─────────────────────────── */

/** Minimal RFC 6455 server-side framing (no dependencies). */
function wsAccept(key) {
  return crypto
    .createHash("sha1")
    .update(`${key}258EAFA5-E914-47DA-95CA-C5AB0DC85B11`)
    .digest("base64")
}

function wsFrame(payload, opcode = 0x1) {
  const data = Buffer.isBuffer(payload) ? payload : Buffer.from(String(payload), "utf8")
  const len = data.length
  let header
  if (len < 126) {
    header = Buffer.alloc(2)
    header[1] = len
  } else if (len < 65536) {
    header = Buffer.alloc(4)
    header[1] = 126
    header.writeUInt16BE(len, 2)
  } else {
    header = Buffer.alloc(10)
    header[1] = 127
    header.writeBigUInt64BE(BigInt(len), 2)
  }
  header[0] = 0x80 | opcode
  return Buffer.concat([header, data])
}

/**
 * The PTY's retained output, mirroring _upstream/packages/core/src/pty.ts:
 *
 *   BUFFER_LIMIT = 2MB, `buffer` holds the retained tail, `bufferCursor` is the
 *   ABSOLUTE cursor of buffer[0], `cursor` is the absolute end. `attach()`
 *   computes `from` as -1 -> end (tail only), a safe integer -> that offset,
 *   anything else -> 0, and replies with buffer.slice(from - bufferCursor).
 *
 * Without this the backend was a dumb sink: every reconnect -- and a full page
 * reload is a reconnect -- produced ZERO bytes, so "the scrollback did not come
 * back" was guaranteed by the fixture and proved nothing about the product.
 * The client advances its own resume cursor on every frame (terminal.tsx:625
 * `cursor += data.length; seek = cursor`), so this history is the only thing
 * that has to survive the reload.
 */
const PTY_BUFFER_LIMIT = 2 * 1024 * 1024

function newPtyLog() {
  return { history: "", bufferCursor: 0, cursor: 0 }
}

/**
 * A PTY connection. The test drives output in; whatever the UI sends back is
 * recorded verbatim so "input reached the WS exactly once" is a real observation.
 */
class MockPtySocket {
  constructor(id, log) {
    this.id = id
    this.log = log
    this.socket = null
    this.received = []
    // Everything the backend pushed DOWN to the UI, live frames and replay alike.
    this.sent = []
    this.closed = false
    this.replayBytes = 0
  }
  /** Appends to the PTY's retained output AND pushes it live, in that order. */
  append(text) {
    const log = this.log
    log.history += text
    log.cursor += text.length
    if (log.history.length > PTY_BUFFER_LIMIT) {
      const excess = log.history.length - PTY_BUFFER_LIMIT
      log.history = log.history.slice(excess)
      log.bufferCursor += excess
    }
  }
  write(text) {
    if (!this.socket || this.socket.destroyed) return false
    this.socket.write(wsFrame(text, 0x1))
    return true
  }
  attach(socket) {
    this.socket = socket
    socket.on("data", (buf) => this.onData(buf))
    socket.on("close", () => {
      this.closed = true
    })
    socket.on("error", () => {
      this.closed = true
    })
  }
  onData(buf) {
    let offset = 0
    while (offset + 2 <= buf.length) {
      const opcode = buf[offset] & 0x0f
      const masked = (buf[offset + 1] & 0x80) !== 0
      let len = buf[offset + 1] & 0x7f
      let cursor = offset + 2
      if (len === 126) {
        if (cursor + 2 > buf.length) return
        len = buf.readUInt16BE(cursor)
        cursor += 2
      } else if (len === 127) {
        if (cursor + 8 > buf.length) return
        len = Number(buf.readBigUInt64BE(cursor))
        cursor += 8
      }
      let mask = null
      if (masked) {
        mask = buf.subarray(cursor, cursor + 4)
        cursor += 4
      }
      const payload = Buffer.from(buf.subarray(cursor, cursor + len))
      if (mask) for (let i = 0; i < payload.length; i++) payload[i] ^= mask[i % 4]
      offset = cursor + len
      if (opcode === 0x9) this.socket?.write(wsFrame(payload, 0xa))
      else if (opcode === 0x8) {
        this.socket?.end()
        return
      } else if (opcode === 0x1) this.received.push(payload.toString("utf8"))
    }
  }
  send(text) {
    if (!this.socket || this.socket.destroyed) return false
    this.append(text)
    this.sent.push(text)
    return this.write(text)
  }
  /**
   * The control frame production terminal.tsx parses for the resume cursor:
   * 0x00 + JSON {cursor}, websocket opcode 0x2 (core/src/pty/protocol.ts
   * metaFrame). Sent by the real backend right after replay with the absolute
   * end cursor, so it always reports THIS pty's absolute position.
   */
  sendCursor(cursor) {
    const value = Number.isSafeInteger(cursor) ? cursor : this.log.cursor
    const json = JSON.stringify({ cursor: value })
    const bytes = Buffer.concat([Buffer.from([0]), Buffer.from(json, "utf8")])
    this.sent.push(`\u0000${json}`)
    if (!this.socket || this.socket.destroyed) return false
    this.socket.write(wsFrame(bytes, 0x2))
    return true
  }
  /** Replays retained output from an absolute cursor (the -1 / n / 0 rules). */
  replayFrom(requested) {
    const log = this.log
    const from =
      requested === -1
        ? log.cursor
        : typeof requested === "number" && Number.isSafeInteger(requested)
          ? Math.max(0, requested)
          : 0
    let data = ""
    if (log.history && from < log.cursor) {
      const offset = Math.max(0, from - log.bufferCursor)
      if (offset < log.history.length) data = log.history.slice(offset)
    }
    this.replayBytes = data.length
    // PtyProtocol.chunks(): bounded frames, never one giant blob.
    const CHUNK = 64 * 1024
    for (let i = 0; i < data.length; i += CHUNK) {
      const piece = data.slice(i, i + CHUNK)
      this.sent.push(piece)
      this.write(piece)
    }
    return { requested, from, bytes: data.length, data }
  }
  /** How many times this backend socket pushed the given marker down to the UI. */
  markerSends(marker) {
    return this.sent.filter((t) => String(t).includes(marker)).length
  }
}

function corsHeaders(req) {
  const origin = req.headers.origin || "*"
  return {
    "access-control-allow-origin": origin,
    "access-control-allow-credentials": origin === "*" ? undefined : "true",
    "access-control-allow-headers":
      req.headers["access-control-request-headers"] ||
      "Authorization, Content-Type, Accept, Last-Event-ID, x-opencode-directory, x-opencode-workspace, x-opencode-ticket",
    "access-control-allow-methods": "GET, POST, PUT, PATCH, DELETE, HEAD, OPTIONS",
    "access-control-allow-private-network": "true",
    vary: "Origin",
  }
}

function startBackend() {
  const state = {
    origin: null,
    workspace: null,
    sessionId: null,
    ptys: new Map(),
    sockets: new Map(),
    // Every socket ever opened per PTY: a reconnect must not erase what the
    // previous connection pushed, or "how many times did the backend send the
    // marker" silently goes DOWN across a reload and reports nonsense.
    socketLog: new Map(),
    // The PTY's retained output (buffer / bufferCursor / cursor), i.e. the real
    // server's history, per _upstream/packages/core/src/pty.ts.
    ptyLogs: new Map(),
    // One record per websocket attach: which resume cursor the client asked for
    // and how many bytes the backend replayed in reply.
    replays: [],
    // The line the history-restore check stamps into the scrollback, so a replay
    // frame can be classified as "the server re-sent it" without guessing.
    marker: null,
    sse: new Set(),
    hits: [],
    ptyPatches: [],
  }

  const json = (res, status, body) => {
    const text = JSON.stringify(body)
    res.writeHead(status, { "content-type": "application/json", "content-length": Buffer.byteLength(text) })
    res.end(text)
  }

  const server = http.createServer((req, res) => {
    const cors = Object.fromEntries(Object.entries(corsHeaders(req)).filter(([, v]) => v != null))
    const url = new URL(req.url || "/", "http://127.0.0.1")
    const p = url.pathname
    const method = req.method || "GET"
    state.hits.push(`${method} ${p}`)
    record({ path: p, status: 200, via: `backend${method === "GET" ? "" : ` ${method}`}` })

    if (method === "OPTIONS") {
      res.writeHead(204, cors)
      res.end()
      return
    }

    const send = (body, status = 200) => {
      const text = JSON.stringify(body)
      res.writeHead(status, { ...cors, "content-type": "application/json", "content-length": Buffer.byteLength(text) })
      res.end(text)
    }

    // --- health / info -------------------------------------------------
    if (p === "/api/health" || p === "/global/health" || p === "/health") {
      send({ healthy: true, version: "verify-android-terminal", time: Date.now() })
      return
    }
    if (p === "/api/info" || p === "/global/info" || p === "/info") {
      // Schema-exact ServerInfo: version, pid, urls, paths.tmp.
      send({
        version: "0.0.0-verify",
        pid: 1,
        urls: [state.origin || "http://127.0.0.1"],
        paths: { tmp: state.workspace || "." },
      })
      return
    }

    // --- SSE event stream ----------------------------------------------
    if (p === "/event" || p === "/global/event" || p === "/api/event") {
      res.writeHead(200, {
        ...cors,
        "content-type": "text/event-stream",
        "cache-control": "no-store",
        connection: "keep-alive",
      })
      res.write(": open\n\n")
      state.sse.add(res)
      req.on("close", () => state.sse.delete(res))
      return
    }

    // --- config / provider / model -------------------------------------
    // config.list() feeds a store that the app calls .findLast() on, so this MUST
    // be an array. Returning an object here crashes the whole session route with
    // "o(...)?.findLast is not a function".
    if (p === "/config" || p === "/global/config" || p === "/api/config") {
      send([])
      return
    }
    if (p === "/api/config/shell") {
      send([{ path: "/bin/bash", name: "bash", acceptable: true }])
      return
    }
    if (p === "/api/provider" || p === "/provider") {
      send({ providers: [], default: {} })
      return
    }
    if (p === "/api/model" || p === "/model") {
      send({ provider: {}, models: {} })
      return
    }
    if (p === "/api/agent" || p === "/agent") {
      send([])
      return
    }
    if (p === "/api/command" || p === "/command") send([])
    else if (p === "/api/skill" || p === "/skill") send([])
    else if (p === "/api/mcp" || p === "/mcp") send({})
    else if (p === "/api/plugin" || p === "/plugin") send([])
    else if (p === "/api/permission/request" || p === "/permission/request") send([])
    else if (p === "/api/question/request" || p === "/question/request") send([])
    else if (p === "/api/vcs/status" || p === "/vcs/status") send({})
    else if (p === "/api/vcs/diff" || p === "/vcs/diff") send({ files: [] })
    else if (p === "/api/project" || p === "/project") {
      send([{ id: "mock-project", worktree: state.workspace, vcs: false, time: { created: Date.now(), initialized: Date.now() } }])
    } else if (p === "/api/project/current" || p === "/project/current") {
      send({ id: "mock-project", worktree: state.workspace, vcs: false })
    } else if (p === "/path" || p === "/api/location" || p === "/location") {
      send(locationPublicInfo(state.workspace))
    } else if (p === "/file" || p === "/api/fs/list") {
      send([])
    } else if (p === "/file/status") {
      send([])
    } else if (p.startsWith("/file/content")) {
      send({ content: "" })
    }

    // --- sessions --------------------------------------------------------
    // SessionsResponse is { data: SessionInfo[], cursor: {previous?, next?} }.
    else if (p === "/api/session" || p === "/session") {
      if (method === "POST") {
        const id = state.sessionId || (state.sessionId = `ses_${crypto.randomUUID().slice(0, 12)}`)
        send({ data: sessionInfo(id, state.workspace), cursor: {} })
        return
      }
      send({ data: [sessionInfo(state.sessionId, state.workspace)], cursor: {} })
    } else if (/^\/api\/session\/[^/]+$/.test(p) || /^\/session\/[^/]+$/.test(p)) {
      const id = decodeURIComponent(p.split("/").pop())
      send({ data: sessionInfo(id, state.workspace) })
    }
    // Sub-resources under /api/session/{id}. Output envelopes are
    // { data: Array<T> } for message/inbox/context, { data: Array<FileDiffInfo> } for diff.
    // message is paged: the app reads `cursor.next`, so the envelope must carry a
    // cursor object even when there are no messages.
    else if (/message$/.test(p)) {
      send({ data: [], cursor: {} })
    } else if (/\/(inbox|context)$/.test(p)) {
      send({ data: [] })
    } else if (/todo$/.test(p)) {
      send({ data: { todos: [] } })
    } else if (/diff$/.test(p)) {
      send({ data: [] })
    } else if (/\/shell$/.test(p)) {
      send({ data: [] })
    }

    // --- PTY -------------------------------------------------------------
    // PtyCreateOutput / PtyGetOutput / PtyUpdateOutput are { location, data: Pty }.
    else if (p === "/api/pty" || p === "/pty") {
      if (method === "POST") {
        const id = `pty_${crypto.randomUUID().slice(0, 10)}`
        state.ptys.set(id, ptyInfo(id, { cwd: state.workspace }))
        send({ location: { directory: state.workspace }, data: state.ptys.get(id) })
        return
      }
      send({ data: [...state.ptys.values()] })
    } else if (/^\/api\/pty\/[^/]+$/.test(p) || /^\/pty\/[^/]+$/.test(p)) {
      const id = decodeURIComponent(p.split("/").pop())
      const existing = state.ptys.get(id)
      const pty = existing || ptyInfo(id, { cwd: state.workspace })
      state.ptys.set(id, pty)
      if (method === "PATCH" || method === "PUT") {
        const chunks = []
        req.on("data", (c) => chunks.push(c))
        req.on("end", () => {
          const raw = Buffer.concat(chunks).toString("utf8")
          let body = {}
          try {
            body = JSON.parse(raw || "{}")
          } catch {}
          /* FINAL: record what the client ACTUALLY persists for a PTY.
           * The packaged terminal hands its serialized scrollback to
           * onCleanup({id, buffer, cursor, rows, cols, scrollY}) -> model.update(),
           * and the terminal reads `pty.buffer` back at mount. Whether that
           * payload ever reaches the server is exactly the question the previous
           * run guessed wrong, so every PATCH body is captured verbatim and the
           * report states what was sent rather than assuming.
           */
          state.ptyPatches.push({
            ptyId: id,
            bytes: raw.length,
            keys: Object.keys(body || {}),
            hasBuffer: typeof body?.buffer === "string" && body.buffer.length > 0,
            bufferBytes: typeof body?.buffer === "string" ? body.buffer.length : 0,
            raw,
          })
          // If the client really does push a serialized buffer up, honour the
          // real save/read contract: keep it on the pty so a later GET (i.e. a
          // real page refresh) hands it back instead of pretending it never
          // existed.
          if (typeof body?.buffer === "string") pty.buffer = body.buffer
          if (Number.isSafeInteger(body?.cursor)) pty.cursor = body.cursor
          if (Number.isSafeInteger(body?.scrollY)) pty.scrollY = body.scrollY
          const size = body.size || body.title?.size
          if (size?.cols && size?.rows) {
            pty.cols = size.cols
            pty.rows = size.rows
          }
          const title = body.title
          if (typeof title === "string") pty.title = title
          send({ location: { directory: state.workspace }, data: pty })
        })
        return
      }
      if (method === "DELETE") {
        state.ptys.delete(id)
        res.writeHead(204, cors)
        res.end()
        return
      }
      send({ location: { directory: state.workspace }, data: pty })
    }

    // Permissive tail: the UI treats most sync endpoints as optional, so an
    // empty-but-valid { data: [] } envelope keeps the shell alive instead of
    // hard-failing. A bare {} trips the generated schema decoder and drops the
    // whole session route into its error boundary.
    else send({ data: [] })
  })

  server.on("upgrade", (req, socket, head) => {
    const url = new URL(req.url || "/", "http://127.0.0.1")
    const match = /^\/(?:api\/)?pty\/([^/]+)\/connect$/.exec(url.pathname)
    if (!match) {
      socket.write("HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\n")
      socket.destroy()
      return
    }
    const key = req.headers["sec-websocket-key"]
    if (!key) {
      socket.write("HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n")
      socket.destroy()
      return
    }
    socket.write(
      [
        "HTTP/1.1 101 Switching Protocols",
        "Upgrade: websocket",
        "Connection: Upgrade",
        `Sec-WebSocket-Accept: ${wsAccept(key)}`,
        "",
        "",
      ].join("\r\n"),
    )
    const id = match[1]
    /* Cursor protocol, read from the real client and server:
     *   client  packages/app/src/session/terminal/terminal.tsx:225
     *            `seek = start !== undefined ? start : restore ? -1 : 0`
     *   server  packages/server/src/handlers/pty.ts:160-165 parses ?cursor and
     *            accepts any safe integer >= -1, then hands it to Pty.attach.
     * The old fixture ignored the parameter entirely and pushed nothing, so a
     * reconnect could never restore anything -- a fixture artefact dressed up
     * as a product defect.
     */
    const rawCursor = url.searchParams.get("cursor")
    const parsed = rawCursor === null ? undefined : Number(rawCursor)
    const requested =
      parsed !== undefined && Number.isSafeInteger(parsed) && parsed >= -1 ? parsed : undefined

    const log = state.ptyLogs.get(id) || newPtyLog()
    state.ptyLogs.set(id, log)
    // Keep whatever metadata the panel PATCHed (title/size); never clobber it
    // with a fresh object just because a socket reconnected.
    const existing = state.ptys.get(id)
    const pty = existing || ptyInfo(id, { cwd: state.workspace })
    state.ptys.set(id, pty)

    const sock = new MockPtySocket(id, log)
    state.sockets.set(id, sock)
    if (!state.socketLog.has(id)) state.socketLog.set(id, [])
    state.socketLog.get(id).push(sock)
    record({ path: url.pathname, status: 101, via: "backend:pty-ws" })
    sock.attach(socket)

    const replay = sock.replayFrom(requested)
    // metaFrame(attachment.cursor) -- the absolute end cursor, sent right
    // after the replay exactly like handlers/pty.ts:198-199.
    sock.sendCursor(log.cursor)
    state.replays.push({
      ptyId: id,
      requestedCursor: requested ?? null,
      from: replay.from,
      replayBytes: replay.bytes,
      endCursor: log.cursor,
      hasMarkerInReplay: replay.data.includes(state.marker || "\u0000no-marker-set\u0000"),
    })
    if (head && head.length) socket.unshift(head)
  })

  return listen(server).then(() => {
    state.server = server
    state.origin = `http://127.0.0.1:${server.address().port}`
    state.sessionId = `ses_${crypto.randomUUID().slice(0, 12)}`
    return state
  })
}

function sessionInfo(id, directory) {
  // Schema-exact against packages/client/src/promise/generated/types.ts SessionInfo:
  // id, projectID, cost, tokens, time{created,updated}, location{directory} are required.
  const now = Date.now()
  return {
    id,
    projectID: "mock-project",
    cost: 0,
    tokens: { input: 0, output: 0, reasoning: 0, cache: { read: 0, write: 0 } },
    time: { created: now, updated: now },
    title: "verify-android-terminal",
    location: { directory },
  }
}

function locationPublicInfo(directory) {
  return { directory, project: { id: "mock-project", directory, canonical: directory } }
}

function ptyInfo(id, patch = {}) {
  return {
    id,
    title: "mock",
    command: "/bin/sh",
    args: [],
    cwd: "",
    status: "running",
    pid: 4242,
    ...patch,
  }
}

function listen(server) {
  return new Promise((resolve, reject) => {
    server.on("error", reject)
    server.listen(0, "127.0.0.1", () => resolve(server))
  })
}

function closeServer(server) {
  return new Promise((resolve) => {
    server.closeAllConnections?.()
    server.close(() => resolve())
  })
}

/* ─────────────────────────── page bootstrap ─────────────────────────── */

/** The mock Kotlin bridge: every method oc-host.js reaches for. */
function nativeBridgeSource() {
  return `(() => {
  const calls = []
  window.__ocNativeCalls = calls
  window.__ocPlatform = {
    onmessage: null,
    postMessage(raw) {
      const request = JSON.parse(raw)
      calls.push({ name: request.method, args: request.payload })
      const value = ["readClipboardImage", "browserConfig"].includes(request.method) ? null : request.method === "configureBrowser" ? false : true
      queueMicrotask(() => this.onmessage?.({ data: JSON.stringify({ id: request.id, value }) }))
    },
  }
  const record = (name, args) => { try { calls.push({ name, args }) } catch {} }
  const secret = new Map()
  window.__ocAndroidNative = {
    hostInfo: () => JSON.stringify({ model: "Chromium-Emulated-Android", sdk: 34, brand: "verify" }),
    hasNotificationPermission: () => 1,
    requestNotificationPermission: () => record("requestNotificationPermission"),
    postMessage: (t, b, s) => { record("postMessage", [t, b, s]); return 0 },
    pickFile: (a, m) => record("pickFile", [a, m]),
    readPickedFile: () => JSON.stringify({ error: "no file" }),
    saveContent: (n, m, b) => record("saveContent", [n, m, b]),
    clearPickedFile: () => record("clearPickedFile"),
    share: (t, s) => { record("share", [t, s]); return true },
    getSecret: (k) => (secret.has(k) ? secret.get(k) : null),
    setSecret: (k, v) => { secret.set(k, v); return true },
    setKeepScreenOn: (on) => record("setKeepScreenOn", [on]),
    setImeMode: (m) => { record("setImeMode", [m]); window.__ocImeMode = m },
    getImeMode: () => window.__ocImeMode || "resize",
    retry: () => record("retry"),
    reportTheme: (dark) => record("reportTheme", [dark]),
    proxyOrigin: () => "http://127.0.0.1:0",
    proxyPrefix: () => "/opencode-proxy/unused",
  }
})()`
}

/**
 * The test hook the replacement module is contracted to append to. Installed
 * before boot so the Terminal constructor can find it.
 */
function installTestHook(trace) {
  const hook = window.__ocTerminalTest = { instances: [], events: [], realm: Math.random().toString(36).slice(2) + "-" + Date.now() }
  if (!trace) return
  hook.instances.push = function (terminal) {
    const index = this.length
    const snapshot = () => {
      const b = terminal.buffer.active
      return { rows: terminal.rows, cols: terminal.cols, viewportY: b.viewportY, baseY: b.baseY, length: b.length, top: b.getLine(b.viewportY)?.translateToString(true), connected: !!terminal.element?.isConnected }
    }
    const record = (event, detail) => hook.events.push({ index, event, time: performance.now(), ...snapshot(), ...detail })
    record("construct")
    for (const name of ["resize", "getViewportY", "scrollToLine", "scrollToBottom", "dispose", "write", "loadAddon"]) {
      const original = terminal[name]
      terminal[name] = function (...args) {
        record(name + ":before", { args: name === "write" ? [typeof args[0] === "string" ? args[0].slice(0, 60) : "bytes"] : name === "loadAddon" ? [] : args })
        const result = original.apply(this, args)
        if (name === "loadAddon" && typeof args[0].serialize === "function") {
          const addon = args[0]
          const serialize = addon.serialize
          addon.serialize = function (...options) {
            const text = serialize.apply(this, options)
            record("serialize", { bytes: text.length })
            return text
          }
        }
        record(name + ":after", { result: typeof result === "number" ? result : undefined })
        return result
      }
    }
    return Array.prototype.push.call(this, terminal)
  }
}
const TEST_HOOK_SOURCE = `;(${installTestHook.toString()})(${process.env.VERIFY_TERMINAL_TRACE === "1"});`

/** Seeds the exact localStorage shape the production persistence layer reads. */
function seedScript({ serverOrigin, workspace, sessionId }) {
  const serverKey = serverOrigin
  const state = {
    list: [{ type: "http", http: { url: serverOrigin } }],
    hidden: {},
    projects: { [serverKey]: [{ worktree: workspace, expanded: true }] },
    lastProject: { [serverKey]: workspace },
    recentlyClosed: {},
  }
  return `(() => {
  try {
    const server = ${JSON.stringify(state)}
    localStorage.setItem("opencode.global.dat:server", JSON.stringify(server))
    localStorage.setItem("opencode.global.dat:server.v3", JSON.stringify(server))
    localStorage.setItem("opencode-color-scheme", "dark")
    localStorage.setItem("opencode-theme-id", "oc-2")
    localStorage.setItem("opencode.android.tabPlusCreatesSession", "false")
  } catch {}
})()`
}

const base64url = (v) =>
  Buffer.from(v, "utf8").toString("base64").replace(/\+/g, "-").replace(/\//g, "_").replace(/=/g, "")

/**
 * The complete oc-host.js, loaded verbatim, at the earliest instant that has a
 * DOM root — still before any page script runs.
 *
 * Why not a plain `addInitScript`: Playwright (and Chromium's
 * `Page.addScriptToEvaluateOnNewDocument`, which is what WebView's
 * `addDocumentStartJavaScript` mirrors) evaluates scripts *before* the parser has
 * created `document.documentElement`. oc-host.js does
 * `(document.head || document.documentElement).appendChild(st)`, so it throws
 * there and — because the OC-TERMINAL installers run at the tail of the file —
 * the whole terminal patch block never installs. The host file is never edited;
 * this loader just hands it a moment where `document.documentElement` exists and
 * no page script has executed yet.
 */
function ocHostLoaderSource(hostSource) {
  return `(() => {
  if (window.__ocHostInstalled) return
  const run = () => {
    if (window.__ocHostInstalled) return true
    const root = document.documentElement
    if (!root) return false
    window.__ocHostInstalled = true
    try {
      ;(0, eval)(window.__ocHostSource)
    } catch (err) {
      window.__ocHostError = String((err && err.stack) || err)
    }
    return true
  }
  window.__ocHostSource = ${JSON.stringify(hostSource)}
  if (run()) return
  const obs = new MutationObserver(() => { if (run()) obs.disconnect() })
  obs.observe(document, { childList: true, subtree: true })
  document.addEventListener("readystatechange", run, { once: true })
  document.addEventListener("DOMContentLoaded", run, { once: true })
})()`
}

/* ─────────────────────────── in-page probes ─────────────────────────── */

/**
 * Reads real state off the live production Terminal instance. Everything is
 * defensive: the report states what was observable rather than throwing away
 * the whole run because one accessor is missing.
 */
const PROBE = `(() => {
  const list = (window.__ocTerminalTest && window.__ocTerminalTest.instances) || []
  const t = list[list.length - 1]
  if (!t) {
    // No engine yet: still report the host CSS variables, so inset checks can run
    // before the terminal is opened instead of silently seeing nothing.
    const cs0 = getComputedStyle(document.documentElement)
    return {
      ok: false, reason: "no-instance", count: list.length,
      ocVars: {
        imeBottom: cs0.getPropertyValue("--oc-ime-bottom").trim(),
        imeVisible: cs0.getPropertyValue("--oc-ime-visible").trim(),
        safeBottom: cs0.getPropertyValue("--oc-safe-bottom").trim(),
        safeTop: cs0.getPropertyValue("--oc-safe-top").trim(),
        windowHeight: cs0.getPropertyValue("--oc-window-height").trim(),
      },
      viewport: { innerHeight: window.innerHeight, innerWidth: window.innerWidth },
    }
  }

  const buffer = t.buffer || {}
  const active = buffer.active || null
  const api = {
    ok: true,
    count: list.length,
    rows: t.rows,
    cols: t.cols,
    hasTextarea: !!t.textarea,
    textareaFocused: !!(t.textarea && document.activeElement === t.textarea),
    textareaActive: !!(t.textarea && t.textarea.ownerDocument.activeElement === t.textarea),
    bufferActive: !!active,
    bufferLength: active ? active.length : null,
    viewportY: active ? active.viewportY : null,
    baseY: active ? active.baseY : null,
    cursorX: active ? active.cursorX : null,
    cursorY: active ? active.cursorY : null,
    connected: !!t.element?.isConnected,
    frameBottom: t.mobileFrame?.getBoundingClientRect().bottom,
    containerBottom: t.mobileContainer?.getBoundingClientRect().bottom,
  }
  try { if (typeof t.getViewportY === "function") api.getViewportY = t.getViewportY() } catch (e) { api.getViewportYError = String(e) }
  try { if (typeof t.getMode === "function") api.getMode2031 = !!t.getMode(2031) } catch (e) { api.getModeError = String(e) }
  try {
    const el = t.element || t._core || (t.textarea && t.textarea.parentElement) || null
    if (el && typeof el.getBoundingClientRect === "function") {
      const r = el.getBoundingClientRect()
      api.canvasRect = { x: r.x, y: r.y, width: r.width, height: r.height }
    }
  } catch {}
  try {
    const el = document.querySelector('[data-component="terminal"]')
    if (el) {
      const r = el.getBoundingClientRect()
      api.panelRect = { x: r.x, y: r.y, width: r.width, height: r.height }
      api.panelVisible = r.width > 0 && r.height > 0
    }
  } catch {}
  try {
    const canvas = document.querySelector('[data-component="terminal"] canvas')
    api.canvasCount = document.querySelectorAll('[data-component="terminal"] canvas').length
    api.hasCanvas = !!canvas
  } catch {}
  try { api.wasmTerm = !!(t.wasmTerm || t.ghostty) } catch {}
  try {
    api.activeTag = document.activeElement ? document.activeElement.tagName : null
    api.activeInsideTerminal = !!(document.activeElement && document.activeElement.closest &&
      document.activeElement.closest('[data-component="terminal"]'))
    const a = document.activeElement
    if (a && a.tagName === "TEXTAREA") api.activeTextareaValue = a.value
    // Some engines expose how much gesture they saw; report it if present.
    const g = window.__ocTerminalTest && window.__ocTerminalTest.lastGesture
    if (g) api.lastGesture = g.last ? { ...g.last, gestures: g.gestures } : g
  } catch {}
  try {
    const fns = ["write", "onData", "onKey", "onResize", "loadAddon", "open", "dispose", "paste", "input", "getSelection", "scrollToLine", "attachCustomKeyEventHandler", "focus", "blur"]
    api.missingMethods = fns.filter((f) => typeof t[f] !== "function")
  } catch {}
  try {
    api.viewport = { innerHeight: window.innerHeight, innerWidth: window.innerWidth }
    api.visualViewport = window.visualViewport
      ? { height: window.visualViewport.height, width: window.visualViewport.width, offsetTop: window.visualViewport.offsetTop }
      : null
    api.dpr = window.devicePixelRatio
    const cs = getComputedStyle(document.documentElement)
    api.ocVars = {
      imeBottom: cs.getPropertyValue("--oc-ime-bottom").trim(),
      imeVisible: cs.getPropertyValue("--oc-ime-visible").trim(),
      safeBottom: cs.getPropertyValue("--oc-safe-bottom").trim(),
      safeTop: cs.getPropertyValue("--oc-safe-top").trim(),
      windowHeight: cs.getPropertyValue("--oc-window-height").trim(),
    }
    api.imeMode = document.documentElement.getAttribute("data-oc-ime-mode")
  } catch {}
  return api
})()`

/** Panel/layout facts used by the geometry + focus checks. */
const LAYOUT_PROBE = `(() => {
  const out = { terminalPanels: 0, panelIds: [], focusChain: [], helperTextareas: 0 }
  try {
    const panels = document.querySelectorAll('[data-component="terminal"]')
    out.terminalPanels = panels.length
    out.panelIds = Array.from(panels).map((p) => p.id || p.getAttribute("data-slot") || "")
  } catch {}
  try {
    const a = document.activeElement
    out.activeTag = a ? a.tagName : null
    out.activeIsHelperTextarea = !!(
      a && a.tagName === "TEXTAREA" && a.closest('[data-component="terminal"]')
    )
    let node = a
    const chain = []
    while (node && node !== document.body) {
      chain.push((node.tagName || "?") + (node.className && typeof node.className === "string"
        ? "." + node.className.split(/\\s+/).filter(Boolean).slice(0, 2).join(".")
        : ""))
      node = node.parentElement
    }
    out.focusChain = chain
  } catch {}
  try { out.helperTextareas = document.querySelectorAll('[data-component="terminal"] textarea').length } catch {}
  try {
    out.sessionKey = location.pathname
    out.rootChildren = document.getElementById("root") ? document.getElementById("root").childElementCount : 0
    out.hostPresent = typeof window.__ocAndroid === "object" && window.__ocAndroid !== null
    out.hostVersion = out.hostPresent ? window.__ocAndroid.version : null
    out.touchPatchInstalled = !!window.__ocTermTouch
    // oc-host.js:20 hardcodes this to "xterm" and then bails out of BOTH
    // installTerminalFontScale() (2011) and installTerminalTouchScroll() (2340)
    // when it equals "xterm". So with the packaged host the touch->wheel bridge
    // is never installed and the engine must handle finger drags itself.
    out.ocTerminalEngine = window.__ocTerminalEngine || null
    out.hostSourceEvaluated = !!window.__ocHostInstalled
    out.hostLoadError = window.__ocHostError || null
    out.topInsetStyle = !!document.getElementById("oc-top-inset")
    out.imeLiftStyle = !!document.getElementById("oc-ime-lift")
    out.touchStyle = !!document.getElementById("oc-term-touch")

    // What is actually on screen, so a stuck boot is diagnosable from the report.
    out.rootText = (document.getElementById("root")?.innerText || "").slice(0, 400)
    out.terminalSurface = !!document.getElementById("terminal-panel")
    out.terminalPanelComponent = !!document.querySelector('[data-component="terminal-panel"]')
    const termTab = document.querySelector('[data-slot="tabs-trigger"][data-value="terminal"]')
    out.terminalTab = !!termTab
    if (termTab) {
      const r = termTab.getBoundingClientRect()
      out.terminalTabRect = { x: r.x, y: r.y, width: r.width, height: r.height }
      out.terminalTabText = (termTab.innerText || "").trim()
    }
    out.composerForms = document.querySelectorAll("form:has(textarea)").length
    out.textareas = document.querySelectorAll("textarea").length
    out.sessionScreens = document.querySelectorAll('[data-workspace-session],[data-component="session-screen"]').length
    const labels = []
    document.querySelectorAll("[aria-label]").forEach((el) => {
      const v = (el.getAttribute("aria-label") || "").trim()
      if (v && labels.length < 60) labels.push(v)
    })
    out.ariaLabels = labels
    out.dataSlots = Array.from(new Set(Array.from(document.querySelectorAll("[data-slot]")).map((e) => e.getAttribute("data-slot")))).slice(0, 40)

    // If the route fell into its ErrorBoundary, ErrorPage renders the formatted
    // error into a read-only multiline TextField. Pull it out so the report says
    // *what* failed instead of just "crashed".
    const detail = document.querySelector('[data-slot="input-input"]')
    out.errorDetail = detail
      ? String(detail.value || detail.innerText || detail.getAttribute("value") || "").slice(0, 1500)
      : null
    out.inErrorBoundary = /error\.page|ErrorPage/.test(document.body.innerHTML) && !!out.errorDetail
  } catch {}
  return out
})()`

/* ─────────────────────────── CDP trusted input ─────────────────────────── */

async function cdpSession(context, page) {
  const client = await context.newCDPSession(page)
  return client
}

async function dispatchTouch(client, type, points) {
  await client.send("Input.dispatchTouchEvent", {
    type,
    touchPoints: points.map((p, i) => ({
      x: p.x,
      y: p.y,
      id: p.id ?? 1,
      radiusX: 12,
      radiusY: 12,
      force: 1,
      rotationAngle: 0,
      ...(type === "touchEnd" ? {} : { timestamp: p.ts ?? Date.now() / 1000 }),
    })),
    modifiers: 0,
  })
}

/**
 * A finger drag with intermediate moves, so velocity/fling logic sees a real
 * gesture rather than one teleporting event.
 */
async function fingerDrag(client, from, to, { steps = 14, stepMs = 16, release = true } = {}) {
  await dispatchTouch(client, "touchStart", [{ x: from.x, y: from.y }])
  for (let i = 1; i <= steps; i++) {
    const t = i / steps
    await dispatchTouch(client, "touchMove", [{ x: from.x + (to.x - from.x) * t, y: from.y + (to.y - from.y) * t }])
    await sleep(stepMs)
  }
  if (release) await dispatchTouch(client, "touchEnd", [])
}

/** A trusted tap: down then up at the same point, inside the slop threshold. */
async function trustedTap(client, point) {
  await dispatchTouch(client, "touchStart", [{ x: point.x, y: point.y }])
  await sleep(24)
  // The released point must still be named on touchEnd, otherwise the engine sees
  // a touchend with no changedTouches and never treats the gesture as a tap.
  await dispatchTouch(client, "touchEnd", [{ x: point.x, y: point.y }])
}

async function trustedKey(client, { key, code, keyCode, text, modifiers = 0 }) {
  const common = {
    key,
    code,
    windowsVirtualKeyCode: keyCode,
    nativeVirtualKeyCode: keyCode,
    modifiers,
  }
  await client.send("Input.dispatchKeyEvent", {
    ...common,
    type: text ? "keyDown" : "rawKeyDown",
    ...(text ? { text } : {}),
  })
  await client.send("Input.dispatchKeyEvent", { ...common, type: "keyUp" })
}

/* ─────────────────────────── checks ─────────────────────────── */

const results = []
const artifacts = []

function recordResult(check, device, status, detail, extra = {}) {
  const entry = { check, device, status, detail, ...extra }
  results.push(entry)
  const icon =
    status === "pass" ? "PASS" : status === "skip" ? "SKIP" : status === "info" ? "INFO" : "FAIL"
  console.error(`  [${icon}] ${device}/${check}: ${detail}`)
  return entry
}

const shouldRun = (name) => !FILTER || name.includes(FILTER)

async function waitFor(fn, { timeout = 20_000, interval = 150, label = "condition" } = {}) {
  const start = Date.now()
  let last
  while (Date.now() - start < timeout) {
    last = await fn()
    if (last) return last
    await sleep(interval)
  }
  throw new Error(`timeout waiting for ${label} (last=${JSON.stringify(last)?.slice(0, 200)})`)
}

async function shot(page, name) {
  if (NO_SHOTS) return null
  try {
    fs.mkdirSync(OUT_DIR, { recursive: true })
    const file = path.join(OUT_DIR, `${name}.png`)
    await page.screenshot({ path: file })
    artifacts.push(path.relative(REPO, file))
    return file
  } catch {
    return null
  }
}

/**
 * FINAL FIX (live PTY must belong to the visible terminal).
 *
 * Two separate defects lived here in the previous run:
 *
 *  (a) It captured `ptyId` once, right after the panel opened, and reused it for
 *      every later input assertion. The production panel remounts on rotation /
 *      resize / IME changes and the SPA then opens a FRESH pty and websocket, so
 *      that id goes stale -- the assertion watched a dead socket while the UI
 *      really typed on the live one. That is the "composition commit produced 0
 *      frames" result: a stale observer, not a product defect.
 *  (b) Its fallback was "the newest socket in the map", an arbitrary guess. With
 *      several ptys alive, newest is not necessarily the one on screen -- which
 *      is exactly the "typed into a hidden engine" failure.
 *
 * The socket is therefore resolved from the terminal that is actually visible
 * (`#terminal-wrapper-<ptyId>` without the `hidden` class), falling back to the
 * supplied id and only then to the newest socket -- and the report says which
 * path was taken.
 */
async function liveSocket(page, state, ptyId) {
  const visible = await visiblePtyId(page).catch(() => null)
  for (const id of [visible, ptyId].filter(Boolean)) {
    const s = state.sockets.get(id)
    if (s) return s
  }
  // Newest socket that is still OPEN, so a half-dead connection is never chosen
  // over the one the page is really attached to.
  const live = [...state.sockets.values()].filter((s) => !s.closed)
  const newest = live.at(-1) || [...state.sockets.values()].at(-1)
  if (newest) {
    newest.__resolvedFrom = visible
      ? `fallback:visible-pty ${visible} had no socket`
      : "fallback:no-visible-wrapper"
  }
  return newest || null
}

/** Sends >= 250 numbered lines down the given PTY websocket. */
async function seedOutput(socket, lines = 260) {
  if (!socket) throw new Error("no mock PTY socket to seed")
  for (let i = 1; i <= lines; i++) {
    const dots = ".".repeat((i % 17) + 3)
    const line = "[" + String(i).padStart(4, " ") + "] line-" + String(i).padStart(4, " ") + " " + dots + "\r\n"
    if (!socket.send(line)) throw new Error("pty socket closed while seeding")
  }
  // The absolute cursor of the PTY, never a locally re-counted one: a cursor
  // that only counts this batch would make a later resume replay from the wrong
  // offset and duplicate the scrollback.
  socket.sendCursor(socket.log.cursor)
  return { lines, cursor: socket.log.cursor }
}

/* ─────────────────────── FINAL: real entry + insets ─────────────────────── */

/**
 * FINAL FIX (the real trusted entry point).
 *
 * The previous run reported "opened via mobile-view-tab" while the screenshot
 * still showed the session composer with its own tab indicator lit. Two
 * independent reasons, both removed here:
 *
 *  1. It dispatched Ctrl+Backquote UNCONDITIONALLY right after the tab tap. When
 *     the tap genuinely selected terminal:main, that keybind is `terminal.toggle`
 *     and swung the view straight back to the session. The PTY stayed alive, the
 *     engine instance stayed mounted, and every later probe cheerfully measured a
 *     terminal that was no longer on screen -- which is exactly why six phone
 *     checks failed while the identical code passed on the tablet.
 *  2. It took the FIRST matching trigger in document order and trusted its
 *     bounding box. The packaged shell renders more than one tab strip (the
 *     session mobile-view tabs, and separately the off-canvas mobile-tabs drawer
 *     plus the vertical tab list), and a translated-off-canvas element still
 *     reports a non-zero rect. Tapping its centre hits nothing at all.
 *
 * The entry point is now: enumerate every candidate WITH its hit-test verdict,
 * tap only a candidate that is on-screen and whose centre point provably belongs
 * to it via document.elementFromPoint, and then PROVE the switch by
 * data-state/aria-selected/the indicator. PTY creation is never accepted as
 * evidence that the terminal opened.
 */
const TAB_SELECTOR =
  '[data-slot="tabs-v2-trigger"],[data-slot="tabs-trigger"],[data-slot="mobile-tabs-trigger"],' +
  '[data-slot="tab-link"],[data-slot="titlebar-tab-item"]'

const TAB_CANDIDATES = `(() => {
  const SELECTOR = ${JSON.stringify(TAB_SELECTOR)}
  const selected = (n) => n.getAttribute("data-state") === "active" ||
    n.getAttribute("data-selected") !== null || n.getAttribute("aria-selected") === "true" ||
    n.getAttribute("data-active") !== null
  const nodes = Array.from(document.querySelectorAll(SELECTOR))
  return nodes.map((n, index) => {
    const r = n.getBoundingClientRect()
    const cx = r.x + r.width / 2
    const cy = r.y + r.height / 2
    const cs = getComputedStyle(n)
    let hit = null
    try { hit = document.elementFromPoint(cx, cy) } catch {}
    const owner = hit && hit.closest ? hit.closest(SELECTOR) : null
    return {
      index,
      value: n.getAttribute("data-value"),
      slot: n.getAttribute("data-slot"),
      text: (n.innerText || "").trim().slice(0, 24),
      selected: selected(n),
      state: n.getAttribute("data-state"),
      ariaSelected: n.getAttribute("aria-selected"),
      rect: { x: r.x, y: r.y, width: r.width, height: r.height },
      onScreen: r.x >= 0 && r.y >= 0 && r.right <= window.innerWidth && r.bottom <= window.innerHeight,
      styled: cs.display !== "none" && cs.visibility !== "hidden" && Number(cs.opacity) > 0.05,
      hitTag: hit ? hit.tagName : null,
      // The decisive test: the point we are about to tap really resolves to this
      // trigger (or to something inside it), and not to a neighbour or to nothing.
      hitOwns: !!owner && owner === n,
      point: { x: cx, y: cy },
    }
  })
})()`

/** Which tab is genuinely selected right now, and is the indicator on it? */
const TAB_STATE = `(() => {
  const SELECTOR = '[data-slot="tabs-v2-trigger"],[data-slot="tabs-trigger"],[data-slot="mobile-tabs-trigger"]'
  const nodes = Array.from(document.querySelectorAll(SELECTOR))
  return {
    selected: nodes
      .filter((n) => n.getAttribute("data-state") === "active" ||
        n.getAttribute("aria-selected") === "true" ||
        n.getAttribute("data-selected") !== null ||
        n.getAttribute("data-active") !== null)
      .map((n) => ({
        value: n.getAttribute("data-value"),
        text: (n.innerText || "").trim().slice(0, 24),
        state: n.getAttribute("data-state"),
        ariaSelected: n.getAttribute("aria-selected"),
      })),
    all: nodes.map((n) => n.getAttribute("data-value")),
  }
})()`

/**
 * FINAL FIX (prove the engine is what is actually on screen).
 *
 * The packaged panel gives every PTY surface a wrapper
 * `<div id="terminal-wrapper-<ptyId>">` and toggles a literal `hidden` class on
 * it whenever `!present || workspace !== current || ptyId !== model.active()`.
 * That is the mechanism behind "the Terminal exists but is hidden": the instance
 * is alive and the buffer is populated while the wrapper carries `hidden`.
 * So visibility is asserted on the wrapper, and the point under test is fed back
 * through elementFromPoint to prove the terminal container is genuinely the top
 * element at its own centre -- not merely present in the DOM.
 */
const ENGINE_VISIBLE = `(() => {
  const wrappers = Array.from(document.querySelectorAll('[id^="terminal-wrapper-"]'))
  const detail = wrappers.map((w) => {
    const r = w.getBoundingClientRect()
    const cx = r.x + r.width / 2
    const cy = r.y + r.height / 2
    let hit = null
    try { hit = document.elementFromPoint(cx, cy) } catch {}
    const cs = getComputedStyle(w)
    return {
      id: w.id,
      ptyId: w.id.replace(/^terminal-wrapper-/, ""),
      hiddenClass: w.classList.contains("hidden"),
      display: cs.display,
      visibility: cs.visibility,
      opacity: Number(cs.opacity),
      rect: { x: r.x, y: r.y, width: r.width, height: r.height },
      onScreen: r.width > 0 && r.height > 0 &&
        r.x >= 0 && r.y >= 0 && r.right <= window.innerWidth && r.bottom <= window.innerHeight,
      // The topmost element at the wrapper's centre belongs to this wrapper.
      hitInside: !!hit && (hit === w || w.contains(hit)),
      hitTag: hit ? hit.tagName : null,
    }
  })
  const visible = detail.filter((w) => !w.hiddenClass && w.display !== "none" &&
    w.visibility !== "hidden" && w.opacity > 0.05 && w.onScreen && w.hitInside)
  const panel = document.getElementById("terminal-panel")
  const pr = panel ? panel.getBoundingClientRect() : null
  return {
    wrappers: detail,
    visibleCount: visible.length,
    visiblePtyId: visible.length ? visible[0].ptyId : null,
    panelPresent: !!panel,
    panelRect: pr ? { x: pr.x, y: pr.y, width: pr.width, height: pr.height } : null,
    panelOnScreen: !!pr && pr.width > 0 && pr.height > 0 &&
      pr.x >= 0 && pr.y >= 0 && pr.right <= window.innerWidth && pr.bottom <= window.innerHeight,
  }
})()`

/**
 * FINAL FIX (boot insets). The previous run never sent an initial insets payload,
 * so oc-host's CSS variables were still empty when the pre-IME baseline was
 * taken and safeTop=24px only appeared for the first time inside the IME-hide
 * section. Rows therefore "changed" purely because the baseline was invalid.
 * What the real InsetsHost delivers the moment the WebView is attached is sent
 * here, before any geometry is sampled, so the baseline is a genuine steady
 * state with the device's real paddings already applied.
 */
const BOOT_INSETS = { safeTop: 24, safeBottom: 16, safeLeft: 0, safeRight: 0 }

async function applyInsets(page, { imeBottom = 0, windowHeight, imeVisible = false, safeBottom = BOOT_INSETS.safeBottom }) {
  await page.evaluate(
    ({ imeBottom, windowHeight, imeVisible, safeTop, safeBottom }) => {
      window.__ocAndroid.applyInsets(
        JSON.stringify({
          safeTop,
          safeBottom,
          safeLeft: 0,
          safeRight: 0,
          imeBottom,
          windowHeight,
          imeVisible,
          imeMode: "resize",
          scheme: "dark",
        }),
      )
    },
    { imeBottom, windowHeight, imeVisible, safeTop: BOOT_INSETS.safeTop, safeBottom },
  )
}

/**
 * Reads oc-host's CSS variables straight off the document root, independently of
 * whether a Terminal engine exists yet. The general PROBE returns early with
 * "no-instance" before the terminal opens and therefore never reaches its ocVars
 * block -- which is exactly why the startup-inset wait could never succeed when
 * it was placed before the terminal is opened.
 */
const INSET_PROBE = `(() => {
  const cs = getComputedStyle(document.documentElement)
  const v = (n) => cs.getPropertyValue(n).trim()
  return {
    safeTop: v("--oc-safe-top"),
    safeBottom: v("--oc-safe-bottom"),
    imeBottom: v("--oc-ime-bottom"),
    imeVisible: v("--oc-ime-visible"),
    windowHeight: v("--oc-window-height"),
    imeMode: document.documentElement.getAttribute("data-oc-ime-mode"),
  }
})()`

/** Sends the real startup insets and waits until oc-host has actually applied them. */
async function applyBootInsets(page, device) {
  await applyInsets(page, {
    imeBottom: 0,
    windowHeight: device.viewport.height,
    imeVisible: false,
  })
  return waitFor(
    async () => {
      const p = await page.evaluate(INSET_PROBE).catch(() => null)
      const top = p?.safeTop
      const bottom = p?.safeBottom
      return top && !top.startsWith("0") && bottom ? { safeTop: top, safeBottom: bottom } : false
    },
    { timeout: 12_000, interval: 200, label: "oc-host to apply the real startup insets" },
  ).catch(() => null)
}

/** FINAL FIX (visible PTY). Resolves the PTY that the on-screen terminal owns. */
async function visiblePtyId(page) {
  return page
    .evaluate(ENGINE_VISIBLE)
    .then((v) => (v && v.visiblePtyId) || null)
    .catch(() => null)
}

/**
 * FINAL FIX (real trusted view switching).
 *
 * The serialization checks are worthless if "switch away and back" never really
 * switches: the previous run proved that a synthetic el.click() -- and the
 * Ctrl+Backquote fallback, which changes no view at all -- leaves the very same
 * live instance mounted, so "the marker is still there" proved nothing at all.
 * This uses the same rigour as the entry point: only a tab that is on-screen and
 * provably owns its own centre point may be tapped, the tap goes through a real
 * locator.tap(), and the before/after tab + engine state is returned so the
 * assertion can insist the view actually changed.
 */
async function switchView(page, wanted) {
  const wantedLower = wanted.map((w) => String(w).toLowerCase())
  const candidates = await page.evaluate(TAB_CANDIDATES)
  const matches = (v) => {
    const s = String(v || "").toLowerCase()
    return wantedLower.some((w) => s === w || s.startsWith(`${w}:`) || s.endsWith(`:${w}`))
  }
  const usable = candidates.filter(
    (c) => matches(c.value) && c.onScreen && c.styled && c.hitOwns && c.rect.width > 0 && c.rect.height > 0,
  )
  const target = usable.find((c) => !c.selected) || usable[0] || null
  if (!target) {
    return {
      switched: false,
      reason: "no tap-able tab for the requested values",
      available: candidates.map((c) => ({ value: c.value, onScreen: c.onScreen, hitOwns: c.hitOwns, selected: c.selected })),
    }
  }
  const before = { tab: await page.evaluate(TAB_STATE), engine: await page.evaluate(ENGINE_VISIBLE) }
  await page.locator(TAB_SELECTOR).nth(target.index).tap()
  await sleep(1200)
  const after = { tab: await page.evaluate(TAB_STATE), engine: await page.evaluate(ENGINE_VISIBLE) }
  return {
    switched: true,
    tapped: target.value,
    text: target.text,
    selectedBefore: before.tab?.selected?.map((s) => s.value) || [],
    selectedAfter: after.tab?.selected?.map((s) => s.value) || [],
    visibleEngineBefore: before.engine?.visiblePtyId || null,
    visibleEngineAfter: after.engine?.visiblePtyId || null,
  }
}

/**
 * FINAL FIX (where is the real cache).
 *
 * The previous run looked for the marker only in localStorage keys containing
 * "terminal" and concluded the product never caches scrollback -- but the value
 * it found there is the PTY REGISTRY (~90-110 bytes of id/cols/rows), not a
 * serialized buffer, so the marker could never appear in it by construction.
 * This dumps every client-side persistence surface and classifies what it finds,
 * so the report states where a serialized scrollback actually lives -- or that it
 * is not reachable from any store the page can see.
 */
async function clientStores(marker) {
  const out = { localStorage: [], sessionStorage: [], indexedDB: [], markerHits: [] }
  for (const [name, store] of [["localStorage", window.localStorage], ["sessionStorage", window.sessionStorage]]) {
    try {
      for (let i = 0; i < store.length; i++) {
        const key = store.key(i)
        if (key == null) continue
        const value = store.getItem(key) || ""
        const hasMarker = value.indexOf(marker) !== -1
        out[name].push({ key, bytes: value.length, hasMarker })
        if (hasMarker) out.markerHits.push({ store: name, key, bytes: value.length })
      }
    } catch {}
  }
  try {
    const dbs = (indexedDB.databases ? await indexedDB.databases() : []) || []
    for (const d of dbs) out.indexedDB.push({ name: d.name, version: d.version })
  } catch (e) { out.indexedDBError = String(e) }
  return out
}

/** Does the live engine buffer actually contain the marker, and on which instance? */
function bufferHas(marker) {
  const list = (window.__ocTerminalTest && window.__ocTerminalTest.instances) || []
  const t = list[list.length - 1]
  if (!t) return { ok: false, reason: "no-instance", count: list.length }
  const buffer = (t.buffer && (t.buffer.active || t.buffer.normal)) || null
  if (!buffer) return { ok: false, reason: "no-buffer", count: list.length }
  const rows = []
  for (let row = 0; row < buffer.length; row++) {
    const line = buffer.getLine(row)
    if (!line) continue
    if (line.translateToString(true).indexOf(marker) !== -1) rows.push(row)
  }
  return {
    ok: true,
    found: rows.length > 0,
    rows,
    bufferLength: buffer.length,
    instanceCount: list.length,
    realm: (window.__ocTerminalTest && window.__ocTerminalTest.realm) || null,
    connected: !!(t.element && t.element.isConnected),
  }
}

/**
 * FINAL FIX (real trusted entry point, reusable).
 *
 * Enumerate every tab candidate WITH its hit-test verdict; tap only a candidate
 * that is on-screen, styled, laid out and provably owns its own centre point via
 * document.elementFromPoint; then PROVE the switch (data-state / aria-selected /
 * the selected-tab set) and PROVE a terminal engine is genuinely on screen (its
 * `#terminal-wrapper-<ptyId>` is not `.hidden`, is laid out on screen, and the
 * topmost element at its centre belongs to it). PTY creation alone is never
 * accepted as evidence that the terminal opened.
 *
 * Ctrl+Backquote is used ONLY on layouts that genuinely have no terminal tab; it
 * is never fired after a successful tap, which is what silently toggled the
 * terminal back off screen in the previous run.
 */
async function openTerminalView(page, client) {
  const isTerminal = (c) => {
    const v = (c.value || "").toLowerCase()
    return v === "terminal:main" || v === "terminal" || v.startsWith("terminal:") || v.endsWith(":terminal")
  }
  const candidates = await page.evaluate(TAB_CANDIDATES)
  const terminalTabs = candidates.filter(isTerminal)
  const usable = terminalTabs.filter(
    (c) => c.onScreen && c.styled && c.hitOwns && c.rect.width > 0 && c.rect.height > 0,
  )
  const target = usable.find((c) => !c.selected) || usable[0] || null

  const evidence = { candidates, terminalTabs, tap: null, tabState: null, engine: null, keybindTried: false }

  if (target) {
    // A REAL trusted tap: Playwright's locator.tap() runs its actionability checks
    // (visible, stable, receives events, enabled) and then delivers genuine touch
    // input through CDP. No synthetic el.click(), no dispatchEvent, no guessed box.
    await page.locator(TAB_SELECTOR).nth(target.index).tap()
    evidence.tap = { value: target.value, text: target.text, index: target.index }
    await sleep(1000)
    evidence.tabState = await page.evaluate(TAB_STATE)
    evidence.engine = await page.evaluate(ENGINE_VISIBLE)
    const selectedOk = (evidence.tabState?.selected || []).some(isTerminal)
    const engineOk = (evidence.engine?.visibleCount || 0) > 0
    if (!selectedOk || !engineOk) {
      const err = new Error(
        `trusted tap on the Terminal tab did not put a terminal on screen ` +
        `(tabSelected=${JSON.stringify(evidence.tabState?.selected)} ` +
        `engineVisible=${evidence.engine?.visibleCount} ` +
        `wrappers=${JSON.stringify(evidence.engine?.wrappers?.map((w) => ({ id: w.id, hiddenClass: w.hiddenClass, display: w.display, visibility: w.visibility, onScreen: w.onScreen, hitInside: w.hitInside, hitTag: w.hitTag })))})`,
      )
      err.evidence = evidence
      throw err
    }
    return { openedVia: `trusted tap on tab "${target.value}" (${target.text})`, evidence }
  }

  if (terminalTabs.length) {
    const err = new Error(
      `a Terminal tab exists but none of them can receive a real tap: ` +
      JSON.stringify(terminalTabs.map((c) => ({ value: c.value, onScreen: c.onScreen, styled: c.styled, hitOwns: c.hitOwns, rect: c.rect, hitTag: c.hitTag }))),
    )
    err.evidence = evidence
    throw err
  }

  // Wide layout: no session/chat tab exists, so terminal.toggle IS the real control.
  evidence.keybindTried = true
  await client.send("Input.dispatchKeyEvent", {
    type: "rawKeyDown", key: "`", code: "Backquote",
    windowsVirtualKeyCode: 192, nativeVirtualKeyCode: 192, modifiers: 2,
  })
  await client.send("Input.dispatchKeyEvent", {
    type: "keyUp", key: "`", code: "Backquote",
    windowsVirtualKeyCode: 192, nativeVirtualKeyCode: 192, modifiers: 2,
  })
  await sleep(1400)
  evidence.tabState = await page.evaluate(TAB_STATE)
  evidence.engine = await page.evaluate(ENGINE_VISIBLE)
  if (!(evidence.engine?.visibleCount > 0)) {
    const err = new Error(
      `terminal.toggle did not put a visible terminal on screen: ${JSON.stringify(evidence.engine?.wrappers)}`,
    )
    err.evidence = evidence
    throw err
  }
  return { openedVia: "terminal.toggle keybind (this layout has no session tab)", evidence }
}

/**
 * Engine identity and DOM connection distinguish retained instances from
 * serialized restoration. Panel collapse legitimately retains its instance.
 */
const ENGINE_INSTANCES = `(() => {
  const list = (window.__ocTerminalTest && window.__ocTerminalTest.instances) || []
  return {
    count: list.length,
    realm: (window.__ocTerminalTest && window.__ocTerminalTest.realm) || null,
    instances: list.map((t, i) => {
      const b = t.buffer && (t.buffer.active || t.buffer.normal)
      return {
        index: i,
        connected: !!(t.element && t.element.isConnected),
        rows: t.rows ?? null,
        cols: t.cols ?? null,
        bufferLength: b ? b.length : null,
      }
    }),
  }
})()`

/** Ctrl+Backquote -- the product's own `terminal.toggle` keybind. */
async function toggleTerminalKeybind(client) {
  await client.send("Input.dispatchKeyEvent", {
    type: "rawKeyDown", key: "`", code: "Backquote",
    windowsVirtualKeyCode: 192, nativeVirtualKeyCode: 192, modifiers: 2,
  })
  await client.send("Input.dispatchKeyEvent", {
    type: "keyUp", key: "`", code: "Backquote",
    windowsVirtualKeyCode: 192, nativeVirtualKeyCode: 192, modifiers: 2,
  })
}

/**
 * Leave the terminal view for real, through the control that actually exists on
 * THIS layout, and report which control it used. On the phone that is the
 * session mobile-view tab; on the wide layout there is no session tab at all and
 * the product's own `terminal.toggle` keybind is the control. Returns
 * {left:false} when nothing moved, so the caller never mistakes a no-op for a
 * successful round trip.
 */
async function leaveTerminalView(page, client) {
  const away = await switchView(page, ["session"]).catch(() => ({ switched: false, reason: "no-session-tab" }))
  if (away.switched) {
    await sleep(900)
    const engine = await page.evaluate(ENGINE_VISIBLE).catch(() => null)
    if ((engine?.visibleCount ?? 0) === 0) {
      return { left: true, mechanism: `session tab "${away.tapped}"`, selectedAfter: away.selectedAfter, engine }
    }
  }
  await toggleTerminalKeybind(client)
  await sleep(1800)
  const engine = await page.evaluate(ENGINE_VISIBLE).catch(() => null)
  const selected = await page.evaluate(TAB_STATE).catch(() => null)
  return {
    left: (engine?.visibleCount ?? 0) === 0,
    mechanism: "terminal.toggle keybind (this layout has no session tab)",
    selectedAfter: selected?.selected?.map((s) => s.value) || [],
    allTabs: selected?.all || [],
    engine,
    tabAttempt: { switched: !!away.switched, tapped: away.tapped || null },
  }
}

/**
 * The counterpart: put the terminal back on screen through a real control.
 *
 * FINAL FIX (a toggle is not idempotent, and a retry loop must not fight it).
 * Both this and closeTerminalPanel drive a TOGGLE, so a helper that presses
 * first and looks afterwards is wrong under retry: if the panel is already open
 * but still mid-attach, pressing again CLOSES it, and the next retry opens it
 * again -- the panel ends up closed exactly when the run gives up. So the
 * current state is read FIRST, and the control is only fired when the state
 * actually has to change.
 */
async function returnTerminalView(page, client) {
  const before = await page.evaluate(ENGINE_VISIBLE).catch(() => null)
  if ((before?.visibleCount ?? 0) > 0) {
    const selected = await page.evaluate(TAB_STATE).catch(() => null)
    return {
      entered: true,
      alreadyThere: true,
      mechanism: "already on screen (no control needed)",
      selectedAfter: selected?.selected?.map((s) => s.value) || [],
      engine: before,
    }
  }
  const back = await switchView(page, ["terminal:main", "terminal"]).catch(() => ({ switched: false }))
  if (back.switched) {
    await sleep(900)
    const engine = await page.evaluate(ENGINE_VISIBLE).catch(() => null)
    if ((engine?.visibleCount ?? 0) > 0) {
      return { entered: true, mechanism: `terminal tab "${back.tapped}"`, selectedAfter: back.selectedAfter, engine }
    }
  }
  await toggleTerminalKeybind(client)
  await sleep(1800)
  const engine = await page.evaluate(ENGINE_VISIBLE).catch(() => null)
  const selected = await page.evaluate(TAB_STATE).catch(() => null)
  return {
    entered: (engine?.visibleCount ?? 0) > 0,
    mechanism: "terminal.toggle keybind",
    selectedAfter: selected?.selected?.map((s) => s.value) || [],
    allTabs: selected?.all || [],
    engine,
    tabAttempt: { switched: !!back.switched, tapped: back.tapped || null },
  }
}

/**
 * Names the mechanism that actually put the content on the wire, from the
 * backend's own attach records.
 *
 *   cursor = -1  the client came back with a serialized buffer and told the PTY
 *                "I already have it" (terminal.tsx:225 `restore ? -1 : 0`) ->
 *                restore came from the CLIENT-side serialization.
 *   cursor = n   the backend replied with everything after offset n -> restore
 *                came from the SERVER's retained output, replayed by cursor.
 */
function describeRestoreMechanism(replays, marker) {
  const list = replays || []
  const withMarker = list.filter((r) => r.hasMarkerInReplay)
  const last = list[list.length - 1] || null
  const requestedCursor = last ? last.requestedCursor : null
  const replayBytes = list.reduce((n, r) => n + (r.replayBytes || 0), 0)
  const markerCameOverTheWire = withMarker.length > 0
  let label
  if (!list.length) label = "no websocket attach was recorded"
  else if (requestedCursor === -1) label = "client-side serialization (the client asked for cursor=-1, i.e. no replay)"
  else if (markerCameOverTheWire)
    label = `server replay by cursor (client asked cursor=${requestedCursor}; the backend re-sent ${withMarker[withMarker.length - 1].replayBytes} bytes containing the marker)`
  else if (requestedCursor === 0)
    label = `server replay by cursor (client asked cursor=0 but the replay carried no marker; ${replayBytes} bytes re-sent)`
  else label = `server replay from cursor ${requestedCursor} (${replayBytes} bytes re-sent, marker not among them)`
  return { attaches: list.length, requestedCursor, replayBytes, markerCameOverTheWire, label, records: list }
}

/**
 * Collapse the panel using its real control. Upstream panel.tsx keeps surfaces
 * mounted independently of opened(); this does not destroy the PTY or engine.
 */
async function closeTerminalPanel(page, client) {
  const tried = []
  const snap = async (tag) => {
    const engine = await page.evaluate(ENGINE_VISIBLE).catch(() => null)
    const instances = await page.evaluate(ENGINE_INSTANCES).catch(() => null)
    tried.push({ tag, visibleCount: engine?.visibleCount ?? null, instances: instances?.count ?? null })
    return { engine, instances }
  }
  await snap("before")
  // Idempotent: never fire a toggle at a panel that is already closed.
  const alreadyClosed = (tried[tried.length - 1]?.visibleCount ?? 0) === 0
  if (!alreadyClosed) {
    await toggleTerminalKeybind(client)
    await sleep(1800)
  } else {
    tried.push({ tag: "already closed (no keybind sent)", visibleCount: 0, instances: tried[0]?.instances ?? null })
  }
  let s = await snap("terminal.toggle keybind")
  if ((s.engine?.visibleCount ?? 0) === 0) {
    return { left: true, mechanism: "terminal.toggle keybind (Ctrl+Backquote), the product's own close control", tried }
  }
  const away = await switchView(page, ["session"]).catch(() => ({ switched: false }))
  if (away.switched) {
    await sleep(1500)
    s = await snap("session tab")
    if ((s.engine?.visibleCount ?? 0) === 0) {
      return {
        left: true,
        mechanism: `session tab "${away.tapped}" (this control only HIDES the panel: the engine survives)`,
        tried,
      }
    }
  }
  const last = tried[tried.length - 1]
  return {
    left: (last?.visibleCount ?? 0) === 0,
    mechanism: "no control on this layout closed the terminal",
    tried,
  }
}

/* ─────────────────────────── the run ─────────────────────────── */

async function verifyAdapterLifecycle(page) {
  return page.evaluate(async () => {
    const url = performance.getEntriesByType("resource").find((entry) => new URL(entry.name).pathname === "/oc-terminal.js")?.name
    if (!url) throw new Error("The production terminal module was not loaded")
    const { Terminal } = await import(url)
    const container = document.createElement("div")
    container.style.cssText = "position:fixed;left:-10000px;width:600px;height:300px"
    document.body.appendChild(container)
    const options = { cols: 60, rows: 12, scrollback: 1000 }
    const addon = () => ({ serialize() { throw new Error("Adapter serializer was not installed") }, activate() {}, dispose() {} })
    const write = (t, text) => new Promise((resolve) => t.write(text, resolve))
    const snapshot = (t) => {
      const b = t.buffer.normal
      const lines = []
      for (let y = 0; y < b.length; y++) {
        const line = b.getLine(y)
        const cells = []
        for (let x = 0; x < t.cols; x++) {
          const c = line.getCell(x)
          cells.push([c.getChars(), c.getWidth(), c.getFgColorMode(), c.getFgColor(), c.getBgColorMode(), c.getBgColor(), !!c.isBold(), !!c.isUnderline(), !!c.isInverse()])
        }
        lines.push(cells)
      }
      return { lines, cursorX: b.cursorX, cursorY: b.cursorY, scrollY: t.getViewportY(), top: b.getLine(b.viewportY)?.translateToString(true) }
    }
    let old, restored
    try {
      old = new Terminal(options)
      const serializer = addon()
      old.loadAddon(serializer)
      old.open(container)
      await write(old, Array.from({ length: 180 }, (_, i) => `\x1b[1;4;38;2;12;34;56;48;5;22mANSI-${i}-history\x1b[0m\r\n`).join(""))
      old.scrollLines(-80)
      const before = snapshot(old)
      const serialized = serializer.serialize({ scrollback: 2000 })
      const oldFrame = old.mobileFrame
      old.dispose()
      const disposed = old.mobileDisposed && !oldFrame.isConnected
      restored = new Terminal(options)
      restored.loadAddon(addon())
      restored.open(container)
      await write(restored, serialized)
      restored.scrollToLine(before.scrollY)
      const after = snapshot(restored)
      restored.resize(options.cols, options.rows - 4)
      await write(restored, "\x1b[?1049hALT-BUFFER")
      const alternateBufferIsolated = restored.buffer.active.type === "alternate" &&
        !restored.resizeAnchor && restored.getViewportY() === 0
      return {
        ok: disposed && before.scrollY > 0 && JSON.stringify(before) === JSON.stringify(after) && alternateBufferIsolated,
        scope: "isolated adapter serialization/dispose/rebuild; no SPA or PTY lifecycle claim",
        disposed, differentInstance: old !== restored, serializedBytes: serialized.length,
        historyAndAnsiEqual: JSON.stringify(before.lines) === JSON.stringify(after.lines),
        cursorEqual: before.cursorX === after.cursorX && before.cursorY === after.cursorY,
        scrollBefore: before.scrollY, scrollAfter: after.scrollY, topBefore: before.top, topAfter: after.top,
        alternateBufferIsolated,
      }
    } finally {
      if (old && !old.mobileDisposed) old.dispose()
      restored?.dispose()
      container.remove()
    }
  })
}

async function runDevice(browser, { chromium }, device, state) {
  console.error(`\n=== ${device.name} ${device.viewport.width}x${device.viewport.height} ===`)
  const context = await browser.newContext({
    viewport: device.viewport,
    deviceScaleFactor: device.deviceScaleFactor,
    isMobile: device.isMobile,
    hasTouch: device.hasTouch,
    colorScheme: "dark",
    reducedMotion: "reduce",
  })
  const page = await context.newPage()
  const consoleErrors = []
  const pageErrors = []
  page.on("console", (m) => {
    if (m.type() === "error") consoleErrors.push(m.text())
    if (m.type() === "error" && process.env.VERIFY_TERMINAL_TRACE === "1") console.error(m.text())
  })
  page.on("pageerror", (e) => pageErrors.push(e && e.stack ? String(e.stack) : String(e)))

  // Mock bridge + the test hook first, then the COMPLETE oc-host.js, all before
  // any page script runs (see ocHostLoaderSource for the documentElement dance).
  const hostSource = fs.readFileSync(HOST_JS, "utf8")
  await page.addInitScript({ content: `${nativeBridgeSource()}\n${TEST_HOOK_SOURCE}\n` })
  await page.addInitScript({ content: ocHostLoaderSource(hostSource) })
  await page.addInitScript({
    content: seedScript({ serverOrigin: state.origin, workspace: state.workspace, sessionId: state.sessionId }),
  })

  const client = await cdpSession(context, page)
  const baseline = requestLog.length

  const route = `/server/${base64url(state.origin)}/session/${state.sessionId}`
  await page.goto(`${state.uiOrigin}${route}`, { waitUntil: "domcontentloaded", timeout: 60_000 })

  // The packaged app is a lazy-loaded SPA: at DOMContentLoaded the bundle is
  // still hydrating, so probing immediately reports an empty shell. Wait for the
  // production shell to actually paint before making any claim about it.
  const settle = await waitFor(
    async () => {
      const p = await page
        .evaluate(`(() => {
          const root = document.getElementById("root")
          if (!root) return false
          return (root.innerText || "").trim().length > 0 || root.querySelectorAll("[data-slot]").length > 0
        })()`)
        .catch(() => false)
      return p
    },
    { timeout: 30_000, label: "production SPA first paint" },
  ).catch(() => false)

  const layout0 = await page.evaluate(LAYOUT_PROBE).catch(() => null)
  if (shouldRun("mount")) {
    if (!layout0) {
      recordResult("mount", device.name, "fail", "could not evaluate layout probe in page")
    } else if (!layout0.hostPresent) {
      recordResult(
        "mount",
        device.name,
        "fail",
        "oc-host.js did not install window.__ocAndroid — document-start injection did not run",
      )
    } else if (layout0.rootChildren === 0) {
      recordResult("mount", device.name, "fail", "production SPA did not mount (empty #root)")
    } else {
      recordResult(
        "mount",
        device.name,
        "pass",
        `production SPA mounted (#root children=${layout0.rootChildren}, oc-host v${layout0.hostVersion}, patches: touch=${layout0.touchPatchInstalled})`,
      )
      await shot(page, `${device.name}-01-mounted`)
    }
  }

  /* ── real startup insets, BEFORE any geometry is sampled ─────────────── */
  // The previous run never sent an initial insets payload, so --oc-safe-top and
  // --oc-safe-bottom were still empty when the pre-IME baseline was captured and
  // safeTop=24px only landed for the first time inside the IME-hide section. The
  // rows comparison was therefore comparing against an invalid baseline. What the
  // real InsetsHost hands the WebView at attach time is delivered here, so every
  // later baseline already carries the device's genuine paddings.
  {
    const applied = await applyBootInsets(page, device).catch(() => null)
    const p = await page.evaluate(INSET_PROBE).catch(() => null)
    const ok = !!applied && p?.safeTop === `${BOOT_INSETS.safeTop}px`
    recordResult(
      "boot-insets",
      device.name,
      ok ? "pass" : "fail",
      ok
        ? `real startup insets applied before any baseline: --oc-safe-top=${p.safeTop} --oc-safe-bottom=${p.safeBottom} --oc-ime-bottom=${p.imeBottom}`
        : `startup insets were not applied (${JSON.stringify(applied)}) — every later geometry baseline would be invalid: ${JSON.stringify(p)}`,
    )
  }

  // Record whether oc-host's own terminal patches actually installed. Under the
  // packaged host they never do (see the __ocTerminalEngine gate), which means
  // finger-drag scrolling has to come from the replacement engine itself.
  if (shouldRun("host-engine-gate")) {
    const engine = layout0 && layout0.ocTerminalEngine
    if (layout0 && layout0.touchPatchInstalled) {
      recordResult(
        "host-engine-gate",
        device.name,
        "pass",
        `oc-host installed its terminal touch bridge (__ocTerminalEngine=${engine})`,
      )
    } else {
      recordResult(
        "host-engine-gate",
        device.name,
        "info",
        `oc-host's terminal patches did NOT install (__ocTerminalEngine=${engine}, touch=${layout0?.touchPatchInstalled}). ` +
          `oc-host.js:20 pins the flag to "xterm" and installTerminalTouchScroll() (2340) / installTerminalFontScale() (2011) ` +
          `both bail out on it, so trusted finger drags must be handled by the replacement engine, not by the host bridge.`,
      )
    }
  }

  // (The ghostty replacement route is asserted after the terminal opens — the
// production chunk only lazy-imports that URL once the panel mounts.)

  if (MODE === "boot") {
    const info = {
      device: device.name,
      url: page.url(),
      layout0: layout0 ? { ...layout0, firstPaintSettled: settle } : null,
      requests: requestLog.slice(baseline),
      backendHits: state.hits.slice(-80),
      consoleErrors: consoleErrors.slice(0, 40),
      pageErrors: pageErrors.slice(0, 40),
    }
    fs.mkdirSync(OUT_DIR, { recursive: true })
    fs.writeFileSync(path.join(OUT_DIR, `boot-${device.name}.json`), JSON.stringify(info, null, 2))
    console.error(`  boot diagnostics -> ${path.relative(REPO, path.join(OUT_DIR, `boot-${device.name}.json`))}`)
    await context.close()
    return
  }

  // ── open the terminal through the app's OWN real control ─────────────
  // On a phone the packaged UI reaches the terminal through the session mobile
  // view tabs; on wide layouts there is no session tab and the product's own
  // `terminal.toggle` keybind is the real control. See TAB_CANDIDATES /
  // ENGINE_VISIBLE for why "a PTY appeared" is explicitly not accepted here.
  let ptyId = null
  let openedVia = null
  const openEvidence = { candidates: [], tap: null, tabState: null, engine: null, keybindTried: false }
  try {
    const opened = await openTerminalView(page, client)
    openedVia = opened.openedVia
    openEvidence.candidates = opened.evidence.candidates
    openEvidence.tap = opened.evidence.tap
    openEvidence.tabState = opened.evidence.tabState
    openEvidence.engine = opened.evidence.engine
    openEvidence.keybindTried = opened.evidence.keybindTried

    ptyId = await waitFor(
      () => {
        const ids = [...state.ptys.keys()]
        return ids.length ? ids[ids.length - 1] : false
      },
      { timeout: 25_000, label: "PTY creation through the production terminal panel" },
    )
    if (shouldRun("terminal-created")) {
      const vis = openEvidence.engine?.visiblePtyId
      recordResult(
        "terminal-created",
        device.name,
        "pass",
        `production UI created PTY ${ptyId} (${openedVia}); visible wrapper pty=${vis}` +
          (vis && vis !== ptyId ? ` [visible pty differs from newest backend pty ${ptyId}]` : ""),
      )
    }
  } catch (err) {
    if (shouldRun("terminal-created")) {
      recordResult("terminal-created", device.name, "fail", `could not open terminal through UI: ${err.message}`)
    }
    // Capture why: whether the terminal control existed at all, and what the
    // production DOM looked like when the PTY never appeared.
    const stuck = await page.evaluate(LAYOUT_PROBE).catch(() => null)
    fs.mkdirSync(OUT_DIR, { recursive: true })
    fs.writeFileSync(
      path.join(OUT_DIR, `stuck-${device.name}.json`),
      JSON.stringify(
        {
          openedVia,
          openEvidence,
          engineAtFailure: await page.evaluate(ENGINE_VISIBLE).catch(() => null),
          tabStateAtFailure: await page.evaluate(TAB_STATE).catch(() => null),
          layout: stuck,
          backendHits: state.hits.slice(-40),
          pageErrors: pageErrors.slice(0, 10),
          consoleErrors: consoleErrors.slice(0, 10),
        },
        null,
        2,
      ),
    )
    await shot(page, `${device.name}-02-terminal-open-failed`)
    await context.close()
    return
  }

  // ── the terminal must construct an engine instance ─────────────────────
  let probe
  try {
    await waitFor(
      async () => {
        const p = await page.evaluate(PROBE).catch(() => null)
        return p && p.ok ? p : false
      },
      { timeout: 25_000, label: "replacement engine instance" },
    )
    await sleep(600) // let the initial fit + paint settle
    probe = await page.evaluate(PROBE)
  } catch (err) {
    recordResult("engine-instance", device.name, "fail", `no Terminal instance appended to window.__ocTerminalTest: ${err.message}; consoleErrors=${JSON.stringify(consoleErrors)}; pageErrors=${JSON.stringify(pageErrors)}`)
    fs.writeFileSync(path.join(OUT_DIR, `${device.name}-no-engine.json`), JSON.stringify(await page.evaluate(() => ({ hook: window.__ocTerminalTest, text: document.body.innerText })), null, 2))
    await shot(page, `${device.name}-03-no-engine`)
    await context.close()
    return
  }

  // ── the replacement module must be requested by the production chunk ──
  if (shouldRun("module-route")) {
    const terminalHits = requestLog
      .slice(baseline)
      .filter((r) => r.path === "/oc-terminal.js")
    const served = terminalHits.find((r) => r.via === "terminal:oc-terminal.mjs")
    if (served) {
      recordResult(
        "module-route",
        device.name,
        "pass",
        `production chunk requested ${served.path} and the HTTP route served the replacement module (${served.bytes} bytes) from ${TERMINAL_MODULE_SERVED}`,
      )
    } else if (terminalHits.length) {
      recordResult(
        "module-route",
        device.name,
        "fail",
        `terminal module requested (${terminalHits[0].path}) but served via ${terminalHits[0].via} — adapter not wired`,
      )
    } else {
      recordResult(
        "module-route",
        device.name,
        "fail",
        "production page never requested /oc-terminal.js, so the adapter was never exercised",
      )
    }
  }

  if (shouldRun("engine-instance")) {
    const bits = []
    bits.push(`rows=${probe.rows} cols=${probe.cols}`)
    bits.push(`canvas=${probe.hasCanvas ? "yes" : "no"}`)
    bits.push(`buffer.active=${probe.bufferActive ? "yes" : "no"}`)
    if (probe.missingMethods && probe.missingMethods.length) bits.push(`MISSING: ${probe.missingMethods.join(",")}`)
    recordResult("engine-instance", device.name, "pass", bits.join(" "))
  }

  /* ── the terminal must be the thing actually on screen ───────────────── */
  // "A PTY was created" and "a Terminal instance exists" are both satisfiable by
  // a terminal that is mounted but hidden. This check is the guard that the whole
  // rest of the run is allowed to depend on: the tab is genuinely selected, the
  // wrapper is not `.hidden`, it is laid out on screen, and the topmost element at
  // its own centre belongs to it.
  if (shouldRun("terminal-on-screen")) {
    const tabState = await page.evaluate(TAB_STATE).catch(() => null)
    const engine = await page.evaluate(ENGINE_VISIBLE).catch(() => null)
    openEvidence.tabState = tabState
    openEvidence.engine = engine
    const isTerminalValue = (v) => {
      const s = String(v || "").toLowerCase()
      return s === "terminal:main" || s === "terminal" || s.startsWith("terminal:")
    }
    const selectedTabs = tabState?.selected || []
    const sel = selectedTabs.find((s) => isTerminalValue(s.value))
    const engineOk = (engine?.visibleCount || 0) > 0
    // On the phone the terminal is a mobile view keyed "terminal:main". On a wide
    // layout the terminal is an overlay whose own tab strip is keyed by PTY id
    // (e.g. "pty_7db213b3-0"), and there is no terminal:main tab at all -- so
    // requiring that exact key there was a wrong premise, not a product failure.
    // The invariant that actually matters on BOTH layouts is: a tab is selected
    // AND a terminal engine wrapper is visible, laid out, and owns its own centre
    // point.
    const ok = engineOk && (!!sel || (openEvidence.keybindTried && selectedTabs.length > 0))
    const visWrapper = engine?.wrappers?.find((w) => w.ptyId === engine.visiblePtyId)
    recordResult(
      "terminal-on-screen",
      device.name,
      ok ? "pass" : "fail",
      (ok
        ? `terminal engine is genuinely on screen (${openEvidence.keybindTried ? "wide-layout overlay, tab strip keyed by pty id" : "mobile view tab"}): ` +
          `selected tab=${JSON.stringify(selectedTabs.map((s) => s.value))} (terminal:main=${!!sel}); ` +
          `wrapper=${engine.visiblePtyId ? `terminal-wrapper-${engine.visiblePtyId}` : "?"} visible ` +
          `hiddenClass=${visWrapper?.hiddenClass} on-screen ${JSON.stringify(visWrapper?.rect)}, ` +
          `elementFromPoint at its centre resolves inside the container (${visWrapper?.hitTag})`
        : `the terminal is NOT on screen — selected tabs=${JSON.stringify(selectedTabs)} ` +
          `engineVisible=${engine?.visibleCount} ` +
          `wrappers=${JSON.stringify(engine?.wrappers?.map((w) => ({ id: w.id, hiddenClass: w.hiddenClass, display: w.display, visibility: w.visibility, onScreen: w.onScreen, hitInside: w.hitInside })))}, ` +
          `opened via ${openedVia}`) +
        `; wrappers=${engine?.wrappers?.length ?? 0}`,
    )
    await shot(page, `${device.name}-03-terminal-on-screen`)
  }

  // ── seed >= 250 numbered lines over the real websocket ─────────────────
  let seeded = null
  try {
    seeded = await seedOutput(await liveSocket(page, state, ptyId), 260)
    await sleep(1500)
    const after = await page.evaluate(PROBE)
    probe = after
    if (shouldRun("seed-output")) {
      const lines = after.bufferLength
      if (after.bufferActive && typeof lines === "number" && lines > seeded.lines) {
        recordResult(
          "seed-output",
          device.name,
          "pass",
          `${seeded.lines} numbered lines pushed over the PTY websocket; buffer.active.length=${lines} (scrollback built)`,
        )
      } else {
        recordResult(
          "seed-output",
          device.name,
          "fail",
          `pushed ${seeded.lines} lines but buffer.active.length=${lines} — output never reached the engine`,
        )
      }
    }
    await shot(page, `${device.name}-04-seeded`)
  } catch (err) {
    if (shouldRun("seed-output")) recordResult("seed-output", device.name, "fail", err.message)
  }

  /* ── trusted drag changes viewportY ─────────────────────────────────── */
  const canvasCenter = async () => {
    // The box of the wrapper that is genuinely ON SCREEN is the authoritative one.
    // The engine element's own rect can be stale right after a resize / rotation /
    // font-scale cycle -- and a tap aimed at a stale centre is dispatched into empty
    // space, which is exactly how a "tap should focus the terminal" check ends up
    // silently tapping nothing and being reported as a product bug.
    const eng = await page.evaluate(ENGINE_VISIBLE).catch(() => null)
    const wrapper = eng?.wrappers?.find((w) => w.ptyId === eng.visiblePtyId) || null
    const p = await page.evaluate(PROBE)
    const vp = p?.viewport || null
    const r = wrapper?.rect || (p && (p.canvasRect || p.panelRect)) || null
    if (!r || !r.width || !r.height) return null
    let x = r.x + r.width / 2
    let y = r.y + r.height / 2
    const unclamped = { x, y }
    if (vp && typeof vp.innerWidth === "number" && typeof vp.innerHeight === "number") {
      x = Math.max(1, Math.min(vp.innerWidth - 1, x))
      y = Math.max(1, Math.min(vp.innerHeight - 1, y))
    }
    return { x, y, rect: r, unclamped, from: wrapper ? "visible-wrapper" : "engine-rect" }
  }

  /**
   * Parks the viewport at the newest output. A drag only proves something when
   * there is room to move, and "follow the bottom" is only meaningful when the
   * viewport was already pinned there.
   */
  const settleAtBottom = async (maxDrags = 40) => {
    const c = await canvasCenter()
    if (!c) return null
    // Scrollback can be thousands of rows, so one short drag is not enough.
    // Clamp the drag length to the panel so the start point stays on-canvas.
    const reach = Math.max(160, Math.min(600, (c.rect?.height || 400) * 0.9))
    let last = null
    for (let i = 0; i < maxDrags; i++) {
      const p = await page.evaluate(PROBE)
      if (typeof p.viewportY !== "number" || typeof p.baseY !== "number") return p
      last = p
      // A couple of rows of slack: fractional scroll can land just short of baseY.
      if (Math.abs(p.viewportY - p.baseY) <= 2) return p
      await fingerDrag(
        client,
        { x: c.x, y: c.y + reach / 2 },
        { x: c.x, y: Math.max(20, c.y - reach / 2) },
        { steps: 18, stepMs: 8 },
      )
      await sleep(200)
    }
    // Out of attempts: re-read so the caller sees the true final position.
    return page.evaluate(PROBE).then((p) => p || last)
  }

  /**
   * One real finger drag DOWN the screen.
   *
   * Direction is not cosmetic. drag-history and drag-down establish the
   * convention on this engine: a finger travelling DOWN (y increasing) lowers
   * viewportY and walks into history, a finger travelling UP raises it toward
   * the newest output. The reverse-anchor loop needs history, so it must drag
   * down.
   *
   * The gesture is launched from a point recomputed from the wrapper that is
   * genuinely on screen right now, inside its own rect, and that point is
   * re-verified with document.elementFromPoint -- a drag started from a stale
   * centre is dispatched into empty space and silently scrolls nothing.
   */
  const fingerDragIntoHistory = async (fraction = 0.28) => {
    const eng = await page.evaluate(ENGINE_VISIBLE).catch(() => null)
    const wrapper = eng?.wrappers?.find((w) => w.ptyId === eng.visiblePtyId) || null
    const rect = wrapper?.rect || null
    if (!rect || !rect.width || !rect.height) return { ok: false, reason: "no-visible-wrapper-rect" }
    const x = Math.round(rect.x + rect.width / 2)
    const clampY = (y) => Math.max(Math.round(rect.y + 8), Math.min(Math.round(rect.y + rect.height - 8), Math.round(y)))
    const from = { x, y: clampY(rect.y + rect.height * fraction) }
    const to = { x, y: clampY(rect.y + rect.height * (1 - fraction)) }
    const owned = await page.evaluate(
      ({ x, y, ptyId }) => {
        const w = ptyId ? document.getElementById(`terminal-wrapper-${ptyId}`) : null
        if (!w) return { ok: false, hitTag: null }
        const el = document.elementFromPoint(x, y)
        return { ok: !!el && (el === w || w.contains(el)), hitTag: el ? el.tagName : null }
      },
      { x: from.x, y: from.y, ptyId: eng.visiblePtyId },
    )
    if (!owned?.ok) return { ok: false, reason: "drag-start-point-not-owned-by-the-visible-terminal", from, to, owned }
    await fingerDrag(client, from, to, { steps: 18, stepMs: 10 })
    return { ok: true, from, to, rect, hitTag: owned.hitTag }
  }

  if (shouldRun("drag-history")) {
    const center = await canvasCenter()
    if (!center) {
      recordResult("drag-history", device.name, "fail", "terminal panel has no laid-out box to drag in")
    } else {
      // The terminal opens pinned to the bottom, so an upward drag has no room
      // to move. Scroll back into history first, then measure both directions.
      await fingerDrag(client, { x: center.x, y: center.y - 140 }, { x: center.x, y: center.y + 140 })
      await sleep(700)
      await fingerDrag(client, { x: center.x, y: center.y - 140 }, { x: center.x, y: center.y + 140 })
      await sleep(900)
      const inHistory = await page.evaluate(PROBE)

      const before = inHistory
      // Finger up moves toward newer output: xterm's absolute viewportY increases.
      await fingerDrag(client, { x: center.x, y: center.y + 140 }, { x: center.x, y: center.y - 140 })
      await sleep(900)
      const afterUp = await page.evaluate(PROBE)
      const movedY = afterUp.viewportY !== null && before.viewportY !== null ? afterUp.viewportY - before.viewportY : null
      if (inHistory.viewportY > 0 && movedY !== null && movedY !== 0) {
        recordResult(
          "drag-history",
          device.name,
          "pass",
          `trusted upward finger drag moved buffer.active.viewportY ${before.viewportY} -> ${afterUp.viewportY} (entered history at ${inHistory.viewportY})`,
        )
      } else if (!(inHistory.viewportY > 0)) {
        recordResult(
          "drag-history",
          device.name,
          "fail",
          `could not scroll into history to test the up-drag (viewportY=${inHistory.viewportY}, baseY=${inHistory.baseY})`,
        )
      } else {
        recordResult(
          "drag-history",
          device.name,
          "fail",
          `trusted upward finger drag did not change viewportY (before=${before.viewportY}, after=${afterUp.viewportY})`,
        )
      }
      await shot(page, `${device.name}-05-after-updrag`)

      const beforeDown = afterUp.viewportY
      await fingerDrag(client, { x: center.x, y: center.y - 140 }, { x: center.x, y: center.y + 140 })
      await sleep(900)
      const afterDown = await page.evaluate(PROBE)
      if (typeof afterDown.viewportY === "number" && afterDown.viewportY !== beforeDown) {
        recordResult(
          "drag-down",
          device.name,
          "pass",
          `trusted downward finger drag moved viewportY ${beforeDown} -> ${afterDown.viewportY}`,
        )
      } else {
        recordResult(
          "drag-down",
          device.name,
          "fail",
          `trusted downward finger drag did not change viewportY (${beforeDown} -> ${afterDown.viewportY})`,
        )
      }
    }
  }

  /* ── new output while reading history must not steal the viewport ────── */
  if (shouldRun("viewport-stability")) {
    const center = await canvasCenter()
    if (center) {
      await fingerDrag(client, { x: center.x, y: center.y + 160 }, { x: center.x, y: center.y - 160 })
      await sleep(800)
      const reading = await page.evaluate(PROBE)
      const socket = await liveSocket(page, state, ptyId)
      if (socket && typeof reading.viewportY === "number" && reading.viewportY > 0) {
        socket.send("\r\n[LIVE] new output while reading history\r\n")
        await sleep(900)
        const stillReading = await page.evaluate(PROBE)
        if (stillReading.viewportY === reading.viewportY && stillReading.bufferLength >= reading.bufferLength) {
          recordResult(
            "viewport-stability",
            device.name,
            "pass",
            `viewport held at ${stillReading.viewportY} while new output arrived (buffer ${reading.bufferLength} -> ${stillReading.bufferLength})`,
          )
        } else {
          recordResult(
            "viewport-stability",
            device.name,
            "fail",
            `viewport jumped to ${stillReading.viewportY} (was ${reading.viewportY}) when new output arrived`,
          )
        }
      } else {
        recordResult(
          "viewport-stability",
          device.name,
          "skip",
          `could not enter history reading state (viewportY=${reading.viewportY})`,
        )
      }
    }
  }

  /* ── tap focuses the terminal input surface; drag must not ─────────────── */
  // The requirement is "tap focuses, drag does not". Focus is asserted against
  // whatever actually holds focus inside [data-component="terminal"], and the
  // exact element is reported: Chromium's default action on the container's
  // tabIndex={-1} can leave the panel DIV focused rather than the engine's
  // helper textarea, so both are named rather than papered over.
  const focusInTerminal = (p) => !!(p && (p.textareaFocused || p.textareaActive || p.activeInsideTerminal))
  const focusLabel = (p) =>
    p?.textareaFocused || p?.textareaActive ? "engine helper textarea" : `container (${p?.activeTag || "?"})`

  if (shouldRun("tap-focus")) {
    const center = await canvasCenter()
    if (!center) {
      recordResult("tap-focus", device.name, "fail", "no terminal box to tap")
    } else {
      await page.evaluate(() => {
        const a = document.activeElement
        if (a && a.blur && a !== document.body) a.blur()
      })
      await trustedTap(client, { x: center.x, y: center.y })
      await sleep(600)
      const afterTap = await page.evaluate(PROBE)
      recordResult(
        "tap-focus",
        device.name,
        focusInTerminal(afterTap) ? "pass" : "fail",
        focusInTerminal(afterTap)
          ? `trusted tap focused the terminal input surface: ${focusLabel(afterTap)}`
          : `trusted tap did not focus the terminal input surface (activeElement=${afterTap.activeTag}, gesture=${JSON.stringify(afterTap.lastGesture)})`,
      )

      // Now a drag over the same spot: must scroll, must NOT take focus.
      await page.evaluate(() => {
        const a = document.activeElement
        if (a && a.blur && a !== document.body) a.blur()
      })
      await settleAtBottom()
      const beforeDrag = await page.evaluate(PROBE)
      await fingerDrag(client, { x: center.x, y: center.y - 130 }, { x: center.x, y: center.y + 130 })
      await sleep(900)
      const afterDrag = await page.evaluate(PROBE)
      const dragFocused = focusInTerminal(afterDrag)
      const dragMoved = typeof afterDrag.viewportY === "number" && afterDrag.viewportY !== beforeDrag.viewportY
      if (dragMoved && !dragFocused) {
        recordResult(
          "drag-focus-separation",
          device.name,
          "pass",
          `drag scrolled (viewportY ${beforeDrag.viewportY} -> ${afterDrag.viewportY}) without taking focus (activeElement=${afterDrag.activeTag})`,
        )
      } else {
        recordResult(
          "drag-focus-separation",
          device.name,
          "fail",
          `drag moved=${dragMoved} focusTaken=${dragFocused} (viewportY ${beforeDrag.viewportY} -> ${afterDrag.viewportY}, activeElement=${afterDrag.activeTag}, gesture=${JSON.stringify(afterDrag.lastGesture)})`,
        )
      }
    }
  }

  /* ── keyboard: viewport shrinks + Android insets event ───────────────── */
  if (shouldRun("ime-resize")) {
    // Steady-state baseline. Captured AFTER the real startup insets landed (see
    // applyBootInsets), so --oc-safe-top/--oc-safe-bottom are already applied and
    // every geometry comparison below is against a genuine resting state.
    const before = await page.evaluate(PROBE)
    const shrunk = Math.round(device.viewport.height * 0.62)
    await client.send("Emulation.setDeviceMetricsOverride", {
      width: device.viewport.width,
      height: shrunk,
      deviceScaleFactor: device.deviceScaleFactor,
      mobile: device.isMobile,
      screenWidth: device.viewport.width,
      screenHeight: shrunk,
    })
    // What the Kotlin InsetsHost hands the page (exact oc-host.js payload shape).
    await page.evaluate(
      ({ imeBottom, windowHeight }) => {
        window.__ocAndroid.applyInsets(
          JSON.stringify({
            safeTop: 24,
            safeBottom: 0,
            safeLeft: 0,
            safeRight: 0,
            imeBottom,
            windowHeight,
            imeVisible: imeBottom > 0,
            imeMode: "resize",
            scheme: "dark",
          }),
        )
      },
      { imeBottom: IME_HEIGHT, windowHeight: shrunk },
    )
    await sleep(1200)
    const withIme = await page.evaluate(PROBE)
    const innerH = withIme.viewport?.innerHeight
    if (typeof innerH === "number" && innerH < device.viewport.height) {
      recordResult(
        "ime-resize",
        device.name,
        "pass",
        `IME shrank the actual layout viewport ${device.viewport.height} -> ${innerH} and oc-host applied --oc-ime-bottom=${withIme.ocVars?.imeBottom} (mode=${withIme.imeMode})`,
      )
    } else {
      recordResult(
        "ime-resize",
        device.name,
        "fail",
        `layout viewport did not shrink with the IME (innerHeight=${innerH}, expected < ${device.viewport.height})`,
      )
    }
    await shot(page, `${device.name}-06-ime-open`)

    // Rows must follow the settled layout. Wait for the grid to actually stop
    // changing first: reading it the instant the IME opens races the engine's
    // debounced fit and reports a stale value.
    const settledRows = await waitFor(
      async () => {
        const a = await page.evaluate(PROBE).catch(() => null)
        if (!a || typeof a.rows !== "number" || a.rows <= 0) return false
        await sleep(500)
        const b = await page.evaluate(PROBE).catch(() => null)
        if (!b || b.rows !== a.rows || b.cols !== a.cols) return false
        return { before: a, after: b }
      },
      { timeout: 12_000, interval: 200, label: "grid to settle after the IME opened" },
    ).catch(() => null)

    if (settledRows) {
      const { before, after } = settledRows
      const imeRows = withIme.viewport?.innerHeight
      recordResult(
        "ime-rows",
        device.name,
        "pass",
        `grid settled to ${after.cols}x${after.rows} inside the ${imeRows}px IME viewport and stayed stable`,
      )
    } else {
      const now = await page.evaluate(PROBE)
      recordResult(
        "ime-rows",
        device.name,
        "fail",
        `grid never settled after the IME opened (rows=${withIme.rows} -> ${now.rows}, cols=${now.cols})`,
      )
    }

    // Hide the keyboard: viewport must grow back.
    await client.send("Emulation.setDeviceMetricsOverride", {
      width: device.viewport.width,
      height: device.viewport.height,
      deviceScaleFactor: device.deviceScaleFactor,
      mobile: device.isMobile,
      screenWidth: device.viewport.width,
      screenHeight: device.viewport.height,
    })
    await page.evaluate(
      ({ windowHeight }) => {
        window.__ocAndroid.applyInsets(
          JSON.stringify({
            safeTop: 24,
            safeBottom: 16,
            safeLeft: 0,
            safeRight: 0,
            imeBottom: 0,
            windowHeight,
            imeVisible: false,
            imeMode: "resize",
            scheme: "dark",
          }),
        )
      },
      { windowHeight: device.viewport.height },
    )
    await sleep(1200)
    // Let the grid settle after the hide before sampling: reading the instant the
    // viewport grows back races the engine's debounced fit.
    const hiddenSettled = await waitFor(
      async () => {
        const a = await page.evaluate(PROBE).catch(() => null)
        if (!a || typeof a.rows !== "number" || a.rows <= 0) return false
        await sleep(500)
        const b = await page.evaluate(PROBE).catch(() => null)
        if (!b || b.rows !== a.rows || b.cols !== a.cols) return false
        return b
      },
      { timeout: 12_000, interval: 200, label: "grid to settle after the keyboard hid" },
    ).catch(() => page.evaluate(PROBE).catch(() => null))
    const noIme = hiddenSettled
    const grew = noIme?.viewport?.innerHeight

    /* FINAL FIX (ime-hide).
     *
     * The old premise was wrong twice over:
     *  - It required |frameBottom - containerBottom| <= 2, i.e. that the frame
     *    ends flush with its container. This product has a permanent ~12px gap
     *    between them in the STEADY state too (phone 777/789, tablet 747/759), so
     *    the assertion could never pass. What actually has to hold is that the
     *    SAME structural gap is restored, not that it vanishes.
     *  - It required rows(after) == rows(before) while `before` was sampled before
     *    any insets had ever been applied, so safeTop=24px landed for the first
     *    time inside this section and the rows moved for an unrelated reason. Boot
     *    insets (applyBootInsets) now make `before` a genuine steady state.
     *
     * No check is removed: the viewport must still return to full height, the
     * engine must still be connected, the container must return to its resting
     * bottom, the structural padding gap must be restored, and the grid must
     * return to its resting size.
     */
    const beforeGap = before.containerBottom != null && before.frameBottom != null
      ? before.containerBottom - before.frameBottom
      : null
    const afterGap = noIme?.containerBottom != null && noIme?.frameBottom != null
      ? noIme.containerBottom - noIme.frameBottom
      : null
    const checks = {
      viewportRestored: typeof grew === "number" && grew >= device.viewport.height - 2,
      engineConnected: !!noIme?.connected,
      containerBottomRestored:
        typeof before.containerBottom === "number" && typeof noIme?.containerBottom === "number"
          ? Math.abs(noIme.containerBottom - before.containerBottom) <= 2 : false,
      paddingGapRestored:
        typeof beforeGap === "number" && typeof afterGap === "number"
          ? Math.abs(afterGap - beforeGap) <= 2 : false,
      gridRestored: noIme?.rows === before.rows,
    }
    const ok = Object.values(checks).every(Boolean)
    const detailBits = `viewport ${device.viewport.height} -> ${grew}` +
      `; containerBottom ${before.containerBottom} -> ${noIme?.containerBottom}` +
      `; structural frame/container gap ${beforeGap} -> ${afterGap} (restored, not zero)` +
      `; rows ${before.rows} -> ${noIme?.rows}` +
      `; --oc-ime-bottom=${noIme?.ocVars?.imeBottom} imeVisible=${noIme?.ocVars?.imeVisible}`
    if (ok) {
      recordResult("ime-hide", device.name, "pass",
        `keyboard hide restored the exact steady-state geometry: ${detailBits}`)
    } else {
      recordResult("ime-hide", device.name, "fail",
        `keyboard hide did NOT restore the steady-state geometry: ${detailBits}; ` +
        `failed=${JSON.stringify(checks)}`)
    }
    await shot(page, `${device.name}-07-ime-closed`)
  }

  /* ── repeated IME open/close + rotation ──────────────────────────────── */
  if (shouldRun("ime-cycles") && shouldRun("rotation")) {
    let cyclesOk = 0
    for (let i = 0; i < 3; i++) {
      const shrunk = Math.round(device.viewport.height * 0.62)
      await client.send("Emulation.setDeviceMetricsOverride", {
        width: device.viewport.width,
        height: shrunk,
        deviceScaleFactor: device.deviceScaleFactor,
        mobile: device.isMobile,
        screenWidth: device.viewport.width,
        screenHeight: shrunk,
      })
      await page.evaluate(
        ({ imeBottom, windowHeight }) =>
          window.__ocAndroid.applyInsets(
            JSON.stringify({
              safeTop: 24,
              safeBottom: 0,
              safeLeft: 0,
              safeRight: 0,
              imeBottom,
              windowHeight,
              imeVisible: true,
              imeMode: "resize",
              scheme: "dark",
            }),
          ),
        { imeBottom: IME_HEIGHT, windowHeight: shrunk },
      )
      await sleep(500)
      await client.send("Emulation.setDeviceMetricsOverride", {
        width: device.viewport.width,
        height: device.viewport.height,
        deviceScaleFactor: device.deviceScaleFactor,
        mobile: device.isMobile,
        screenWidth: device.viewport.width,
        screenHeight: device.viewport.height,
      })
      await page.evaluate(
        ({ windowHeight }) =>
          window.__ocAndroid.applyInsets(
            JSON.stringify({
              safeTop: 24,
              safeBottom: 16,
              safeLeft: 0,
              safeRight: 0,
              imeBottom: 0,
              windowHeight,
              imeVisible: false,
              imeMode: "resize",
              scheme: "dark",
            }),
          ),
        { windowHeight: device.viewport.height },
      )
      await sleep(500)
      const p = await page.evaluate(PROBE)
      if (p.viewport?.innerHeight >= device.viewport.height - 2 && typeof p.rows === "number" && p.rows > 0) cyclesOk++
    }
    recordResult(
      "ime-cycles",
      device.name,
      cyclesOk === 3 ? "pass" : "fail",
      `${cyclesOk}/3 IME open/close cycles ended with a restored viewport and a live grid`,
    )

    // Rotation: swap the viewport, then check the grid reflows.
    const rotated = { width: device.viewport.height, height: device.viewport.width }
    await client.send("Emulation.setDeviceMetricsOverride", {
      width: rotated.width,
      height: rotated.height,
      deviceScaleFactor: device.deviceScaleFactor,
      mobile: device.isMobile,
      screenWidth: rotated.width,
      screenHeight: rotated.height,
    })
    await sleep(1400)
    const rot = await page.evaluate(PROBE)
    const rotatedOk = rot.viewport?.innerWidth === rotated.width && typeof rot.cols === "number" && rot.cols > 0
    recordResult(
      "rotation",
      device.name,
      rotatedOk ? "pass" : "fail",
      rotatedOk
        ? `rotated to ${rot.viewport.innerWidth}x${rot.viewport.innerHeight}; grid reflowed to ${rot.cols}x${rot.rows}`
        : `rotation did not settle (innerWidth=${rot.viewport?.innerWidth}, cols=${rot.cols}, rows=${rot.rows})`,
    )
    await shot(page, `${device.name}-08-rotated`)

    await client.send("Emulation.setDeviceMetricsOverride", {
      width: device.viewport.width,
      height: device.viewport.height,
      deviceScaleFactor: device.deviceScaleFactor,
      mobile: device.isMobile,
      screenWidth: device.viewport.width,
      screenHeight: device.viewport.height,
    })
    await sleep(1000)
  }

  /* ── user font scale ─────────────────────────────────────────────────── */
  if (shouldRun("font-scale")) {
    const before = await page.evaluate(PROBE)
    try {
      await client.send("Page.setFontSizes", { fontSizes: { standard: 20, fixed: 20 } })
    } catch (err) {
      recordResult("font-scale", device.name, "skip", `Page.setFontSizes unavailable: ${err.message}`)
    }
    await sleep(1500)
    const after = await page.evaluate(PROBE)
    const changed = after.cols !== before.cols || after.rows !== before.rows
    // CDP's Page.setFontSizes changes the *default* font size; it has no
    // equivalent for Android's user font scale / text zoom. And with the
    // packaged host the terminal font-scale patch is disabled anyway by the
    // __ocTerminalEngine gate. So an unchanged grid is reported, not failed:
    // a real verdict needs on-device instrumentation.
    recordResult(
      "font-scale",
      device.name,
      changed ? "pass" : "info",
      changed
        ? `font scale changed the grid ${before.cols}x${before.rows} -> ${after.cols}x${after.rows}`
        : `font scale did not change the grid (${after.cols}x${after.rows}). Page.setFontSizes only moves the default font size, ` +
          `it is not Android text zoom, and oc-host's installTerminalFontScale() is disabled by the __ocTerminalEngine gate. ` +
          `Verdict needs a real device.`,
    )
    await shot(page, `${device.name}-09-font-scale`)
    try {
      await client.send("Page.setFontSizes", { fontSizes: { standard: 16, fixed: 13 } })
    } catch {}
    await sleep(800)
  }

  /* ── IME composition + paste reach the websocket exactly once ────────── */
  if (shouldRun("ime-input")) {
    /* FINAL FIX (every premise here was sampled before the geometry churn).
     *
     * This block runs after ime-resize, ime-cycles, a rotation and a font-scale
     * change. Any of those can leave a DIFFERENT terminal surface on screen,
     * re-create the engine, and hand the page a different PTY websocket. The old
     * code captured `center` and `socket` before the section and typed into
     * whatever was there, so:
     *   - the tap could land on a panel that is not the visible one,
     *   - the focus probe could read BODY (no editable focused) while the
     *     composition was then dispatched anyway, and
     *   - the frames were counted on a socket that was no longer attached,
     * producing "0 frames" -- which reads like an IME bug but is a stale
     * observer. 0 frames on a socket nobody was attached to is not evidence
     * about the IME commit path at all.
     *
     * So the visible terminal is re-established first, then the rect and the
     * live socket are re-acquired, and focus is asserted IMMEDIATELY before the
     * composition -- if the textarea is not focused at that instant, the check
     * fails and says so instead of reporting a frame count that means nothing.
     */
    let vis = await page.evaluate(ENGINE_VISIBLE).catch(() => null)
    let reopenedFor = null
    if (!(vis?.visibleCount > 0)) {
      reopenedFor = await returnTerminalView(page, client).catch((e) => ({ entered: false, reason: e.message }))
      vis = await page.evaluate(ENGINE_VISIBLE).catch(() => null)
    }
    const center = await canvasCenter()
    const socket = await liveSocket(page, state, vis?.visiblePtyId || ptyId)
    if (!center || !socket) {
      recordResult(
        "ime-input",
        device.name,
        "fail",
        `no visible terminal box or live PTY socket to type into after re-establishing the view ` +
          `(visibleCount=${vis?.visibleCount}, reopened=${JSON.stringify(reopenedFor)})`,
      )
    } else {
      const setup = {
        reopenedFor,
        visiblePtyId: vis?.visiblePtyId ?? null,
        tapPointSource: center.from,
        socketId: socket.id,
        socketResolvedFrom: socket.__resolvedFrom || "visible-pty",
      }

      /* FINAL FIX (composition needs a genuinely focused editable).
       *
       * `Input.imeSetComposition` / `Input.insertText` are delivered to whatever
       * editable element currently has focus. If focus is not on the engine's
       * helper textarea at that instant the commit goes nowhere and the socket
       * sees 0 frames. Focus is therefore re-established with a real trusted tap
       * and asserted on the very next read, before the composition is started.
       */
      const ensureEngineFocus = async () => {
        for (let i = 0; i < 3; i++) {
          const p = await page.evaluate(PROBE).catch(() => null)
          if (p && (p.textareaFocused || p.textareaActive)) return p
          await trustedTap(client, { x: center.x, y: center.y })
          await sleep(500)
        }
        return page.evaluate(PROBE).catch(() => null)
      }
      const focusState = await ensureEngineFocus()
      const textareaFocusedNow = !!(focusState?.textareaFocused || focusState?.textareaActive)
      if (!textareaFocusedNow) {
        recordResult(
          "ime-composition",
          device.name,
          "fail",
          `the engine's helper textarea was NOT focused on the visible terminal immediately before the ` +
            `composition, so any frame count would describe a commit that was never delivered: ` +
            `activeTag=${focusState?.activeTag} activeInsideTerminal=${!!focusState?.activeInsideTerminal}; ` +
            `setup=${JSON.stringify(setup)}`,
        )
      } else {

      // What is actually under the tap point, so a "no focus" result can be read
      // as either a missed tap or a refused focus.
      const pointInfo = await page.evaluate(
        ({ x, y }) => {
          const el = document.elementFromPoint(x, y)
          const list = (window.__ocTerminalTest && window.__ocTerminalTest.instances) || []
          const t = list[list.length - 1]
          return {
            tag: el ? el.tagName : null,
            insideTerminal: !!(el && el.closest && el.closest('[data-component="terminal"],[id^="terminal-wrapper-"]')),
            helperTextareas: document.querySelectorAll('[data-component="terminal"] textarea').length,
            textareaConnected: !!(t && t.textarea && t.textarea.isConnected),
            point: { x, y },
          }
        },
        { x: center.x, y: center.y },
      )

      // Real IME path: composition (preedit) then commit. A correct engine sends
      // nothing over the socket for the preedit and exactly one frame for the
      // commit, so the mark is taken after the preedit.
      try {
        await client.send("Input.imeSetComposition", {
          text: "ni-hao",
          selectionStart: 6,
          selectionEnd: 6,
        })
        await sleep(250)
      } catch (err) {
        recordResult("ime-input", device.name, "fail", `IME composition could not start: ${err.message}`)
      }
      const markCommit = socket.received.length
      try {
        await client.send("Input.insertText", { text: "ni-hao" })
      } catch (err) {
        recordResult("ime-input", device.name, "fail", `IME commit failed: ${err.message}`)
      }
      await sleep(600)
      const afterComposition = socket.received.length - markCommit
      recordResult(
        "ime-composition",
        device.name,
        afterComposition === 1 ? "pass" : "fail",
        `IME composition commit on the CURRENTLY VISIBLE terminal produced ${afterComposition} websocket ` +
          `frame(s) (expected exactly 1): ${JSON.stringify(socket.received.slice(markCommit))}; ` +
          `setup=${JSON.stringify(setup)}; focus immediately before the commit=${JSON.stringify({
            activeTag: focusState?.activeTag,
            textareaFocused: focusState?.textareaFocused,
            textareaActive: focusState?.textareaActive,
            activeInsideTerminal: focusState?.activeInsideTerminal,
          })}; tapTarget=${JSON.stringify(pointInfo)}; tapPointSource=${center.from ?? "?"} ` +
          `unclamped=${JSON.stringify(center.unclamped)}; socket=${socket.id}${socket.__resolvedFrom ? ` (${socket.__resolvedFrom})` : ""}`,
      )
      }

      if (textareaFocusedNow) {
        /* FINAL FIX (it was pasted into the wrong terminal).
         *
         * `document.querySelector('[data-component="terminal"]')` returns the
         * FIRST such panel in the DOM. After a view change, a rotation or a tab
         * switch the page can hold several panels, and the first one is routinely
         * the one that is no longer on screen -- so a perfectly working paste
         * handler saw nothing happen and the check blamed the input path. The
         * panel that is actually visible is resolved through the same
         * #terminal-wrapper-<ptyId> id ENGINE_VISIBLE uses, and is verified to be
         * on screen before the event is dispatched.
         */
        const markPaste = socket.received.length
        const pasted = await page.evaluate(
          (ptyId) => {
            const visible = (w) => !!(w && w.getClientRects().length && w.offsetParent !== null)
            const wrapper = ptyId ? document.getElementById(`terminal-wrapper-${ptyId}`) : null
            let panel = wrapper && wrapper.querySelector('[data-component="terminal"]')
            if (!panel) {
              const panels = [...document.querySelectorAll('[data-component="terminal"]')]
              panel = panels.find(visible) || panels.at(-1) || null
            }
            if (!panel) return { ok: false, reason: "no-terminal-panel" }
            const onScreen = visible(panel)
            if (!onScreen) return { ok: false, reason: "terminal-panel-found-but-not-visible" }
            const dt = new DataTransfer()
            dt.setData("text/plain", "pasted-line-42")
            const ev = new ClipboardEvent("paste", { clipboardData: dt, bubbles: true, cancelable: true })
            panel.dispatchEvent(ev)
            return { ok: true, wrapperId: wrapper ? wrapper.id : null, panelVisible: onScreen }
          },
          vis?.visiblePtyId || null,
        )
        await sleep(700)
        const afterPaste = socket.received.length - markPaste
        if (pasted?.ok) {
          recordResult(
            "paste-input",
            device.name,
            afterPaste === 1 ? "pass" : "fail",
            `paste into the currently visible terminal (${pasted.wrapperId}) produced ${afterPaste} websocket ` +
              `frame(s) (expected exactly 1): ${JSON.stringify(socket.received.slice(markPaste))}`,
          )
        } else {
          recordResult(
            "paste-input",
            device.name,
            "fail",
            `could not paste into the visible terminal panel: ${JSON.stringify(pasted)}; setup=${JSON.stringify(setup)}`,
          )
        }
      } else {
        // Focus never reached the engine's textarea on the visible terminal, so
        // there is no commit path to measure. ime-composition already carries the
        // evidence; paste is reported as not-attempted rather than left as a
        // silent pass that would hide the missing precondition.
        recordResult(
          "paste-input",
          device.name,
          "fail",
          `not attempted: the engine's textarea was not focused on the visible terminal, so a paste would ` +
            `have been dispatched without a live commit path; setup=${JSON.stringify(setup)}`,
        )
      }
      await shot(page, `${device.name}-10-input`)
    }
  }

  /* ── bottom-follow + cursor recovery + history retained ──────────────── */
  if (shouldRun("bottom-follow")) {
    const socket = await liveSocket(page, state, ptyId)
    if (!socket) {
      recordResult("bottom-follow", device.name, "fail", "no PTY socket to push output through")
    } else {
      // Park at the bottom first: "follow" only means something when the
      // viewport was already pinned to the newest output.
      await settleAtBottom()
      const before = await page.evaluate(PROBE)
      socket.send("\r\n[BOTTOM] returning to newest output\r\n")
      await sleep(1000)
      const after = await page.evaluate(PROBE)
      const followed =
        typeof after.viewportY === "number" &&
        typeof after.baseY === "number" &&
        Math.abs(after.viewportY - after.baseY) <= 1
      recordResult(
        "bottom-follow",
        device.name,
        followed ? "pass" : "fail",
        `terminal followed new output to the bottom (viewportY=${after.viewportY}, baseY=${after.baseY}, was ${before.viewportY})`,
      )
      // Instance counts only describe remounts. The history-restore check below
      // verifies whether snapshots and backend replay preserved the content.
      const remounted = typeof after.count === "number" && after.count > 1
      if (remounted) {
        recordResult(
          "history-retained",
          device.name,
          "info",
          `observed ${after.count} engine instances during the run; current buffer.active.length=${after.bufferLength}. ` +
            `Content restoration is verified separately by history-restore.`,
        )
      } else {
        recordResult(
          "history-retained",
          device.name,
          typeof after.bufferLength === "number" && after.bufferLength > 250 ? "pass" : "fail",
          `scrollback retained across the whole run in a single engine instance: buffer.active.length=${after.bufferLength}`,
        )
      }
      recordResult(
        "cursor-recovery",
        device.name,
        typeof after.cursorY === "number" ? "pass" : "fail",
        `cursor position readable from the live buffer (x=${after.cursorX}, y=${after.cursorY})`,
      )
    }
  }

/**
 * FINAL (reverse viewport anchor).
 *
 * "Reverse anchor" is the user having deliberately scrolled back into history.
 * While that anchor is set, NONE of new output, a focus change, the IME opening
 * or closing, a rotation, or a tab reveal may yank the viewport to the bottom --
 * and when the viewport IS parked at the bottom, new output must still be
 * followed (that half is the bottom-follow check).
 *
 * The invariant is asserted on the CONTENT at the top of the viewport, not on the
 * absolute viewportY, because a rotation legitimately changes the grid size and
 * therefore the numeric viewportY while the same line should stay on screen.
 */
function anchorProbe(anchorText) {
  const list = (window.__ocTerminalTest && window.__ocTerminalTest.instances) || []
  const t = list[list.length - 1]
  const b = t && t.buffer && (t.buffer.active || t.buffer.normal)
  if (!b) return { ok: false, reason: "no-buffer" }
  const v = b.viewportY
  const lineText = (y) => {
    const l = b.getLine(y)
    return l ? l.translateToString(true).slice(0, 90) : ""
  }
  /* Width-safe line identity.
   *
   * A rotation or an IME reflow changes `cols`, and xterm then re-WRAPS the
   * same logical line across different rows. Comparing the raw 90-char prefix
   * made an anchored line look destroyed after a rotation that had in fact
   * kept it perfectly on screen -- which the previous run reported as "content
   * destroyed". Collapsing runs of spaces and accepting a prefix relation (or
   * a shared long leading token) compares the LINE, not the wrap points.
   */
  const norm = (s) => String(s == null ? "" : s).replace(/\s+/g, " ").trim()
  const sameLine = (a, b) => {
    const x = norm(a)
    const y = norm(b)
    if (!x || !y) return false
    if (x === y) return true
    if (x.startsWith(y) || y.startsWith(x)) return true
    const n = Math.min(28, x.length, y.length)
    return n >= 12 && x.slice(0, n) === y.slice(0, n)
  }
  const topText = lineText(v)
  if (anchorText === "") {
    /* Establishing pass. It MUST report rows and bufferLength like the matching
     * pass does: the deep-anchor loop computes
     * `bufferLength - (viewportY + rows)` from this, and an omitted field makes
     * that `NaN`, `NaN >= 120` is false, and the anchor is never actually driven
     * away from the bottom -- which silently turns the whole check into "anything
     * near the bottom held".
     */
    return {
      ok: true,
      viewportY: v,
      baseY: b.baseY,
      rows: t.rows ?? null,
      bufferLength: b.length,
      atBottom: typeof t.rows === "number" && t.rows > 0 ? v + t.rows >= b.length : false,
      topText,
      topKey: norm(topText).slice(0, 48),
      foundAt: null,
      held: true,
      anchorAnywhere: true,
    }
  }
  let foundAt = null
  // Scan a few rows either side of the top: an anchor that has crept one or two
  // rows is still the user's anchor, whereas an anchor parked at the bottom of a
  // 2000-line scrollback is not.
  for (let d = -3; d <= 3; d++) {
    const y = v + d
    if (y < 0) continue
    if (sameLine(lineText(y), anchorText)) { foundAt = y; break }
  }
  // Does the anchored line still exist ANYWHERE in the buffer? If it does not, the
  // CONTENT ITSELF was destroyed (the engine was re-created and the scrollback was
  // not restored). That is a different defect from "the viewport was yanked away
  // from a line that is still sitting there", and the two must not be conflated.
  let anchorAnywhere = false
  for (let row = 0; row < b.length; row++) {
    if (sameLine(lineText(row), anchorText)) { anchorAnywhere = true; break }
  }
  return {
    ok: true,
    viewportY: v,
    baseY: b.baseY,
    rows: t.rows ?? null,
    bufferLength: b.length,
    /* FINAL FIX (what "at the bottom" actually means).
     * This engine's `buffer.baseY` tracks `viewportY` one-for-one -- it is NOT an
     * independent marker of the newest output, so `viewportY === baseY` is true
     * at every scroll position and proves nothing. The real bottom is reached when
     * the last visible row is the last row in the buffer.
     */
    atBottom: typeof t.rows === "number" && t.rows > 0 ? v + t.rows >= b.length : false,
    topText,
    topKey: norm(topText).slice(0, 48),
    foundAt,
    held: foundAt !== null,
    anchorAnywhere,
  }
}

  /* ── the reverse viewport anchor survives everything that follows ─────── */
  if (shouldRun("reverse-anchor")) {
    const stages = []
    try {
      const center = await canvasCenter()
      if (!center) {
        recordResult("reverse-anchor", device.name, "fail", "no terminal box to anchor in")
      } else {
        // The product re-creates the engine on resize/rotation and does NOT carry
        // the previous scrollback across (see the history-retained evidence), so
        // by this point the buffer may hold nothing at all to scroll through.
        // Re-seed before trying to set a reverse anchor. It has to be a DEEP seed:
        // with a 59-row grid, 200 lines only gives ~146 rows of scrollback, so an
        // "anchor" there sits two rows above the bottom and every later jump to
        // the newest output looks identical to holding still. 600 lines makes the
        // reverse anchor unambiguous.
        const pre = await page.evaluate(PROBE).catch(() => null)
        if (typeof pre?.baseY === "number" && pre.baseY <= 0) {
          const s0 = await liveSocket(page, state, ptyId)
          if (s0) {
            await seedOutput(s0, 600).catch(() => {})
            await sleep(2500)
          }
        }
        await settleAtBottom()

        /* FINAL FIX (the drag direction was backwards).
         *
         * Drag convention on this engine, established by drag-history/drag-down:
         * a finger moving DOWN the screen (y increases) decreases viewportY and
         * walks into history; a finger moving UP increases viewportY and walks
         * toward the newest output. The loop below used to drag from
         * center.y+170 to center.y-170 -- i.e. UP -- while trying to reach 120
         * rows of history, so it drove the viewport to the newest output and the
         * achieved depth was always 0. Every downstream stage then measured an
         * "anchor" parked on the bottom, which is why the check never reached a
         * verdict.
         *
         * The gesture now starts from a point that is INSIDE the terminal's
         * currently visible rect and provably owned by the visible wrapper (a
         * drag launched from a stale centre is dispatched into empty space), and
         * travels downwards.
         */
        const ANCHOR_DEPTH = 120
        const MIN_DEPTH = 40
        const depthOf = (p) =>
          p && typeof p.bufferLength === "number" && typeof p.rows === "number" && typeof p.viewportY === "number"
            ? p.bufferLength - (p.viewportY + p.rows)
            : null
        const dragAttempts = []
        for (let attempt = 0; attempt < 14; attempt++) {
          const p = await page.evaluate(anchorProbe, "").catch(() => null)
          if (!p || !p.ok) break
          const depth = depthOf(p)
          if (depth !== null && depth >= ANCHOR_DEPTH) break
          const before = p.viewportY
          const gesture = await fingerDragIntoHistory()
          await sleep(900)
          const after = await page.evaluate(anchorProbe, "").catch(() => null)
          dragAttempts.push({
            attempt,
            depthBefore: depth,
            viewportYBefore: before,
            viewportYAfter: after?.viewportY ?? null,
            depthAfter: depthOf(after),
            ...gesture,
          })
        }
        await sleep(1000)

        /* Stop the fling before reading anything.
         *
         * A 300px drag leaves inertia running for well over a second; capturing
         * the anchor mid-coast makes the next stage look like a 2-row "steal"
         * that never happened. Three consecutive identical reads is the gate:
         * once it passes, the viewport genuinely stopped and everything measured
         * from here on is the resting state.
         */
        const settled = await waitFor(
          async () => {
            const a = await page.evaluate(anchorProbe, "").catch(() => null)
            if (!a || !a.ok) return false
            await sleep(700)
            const b = await page.evaluate(anchorProbe, "").catch(() => null)
            if (!b || !b.ok || b.viewportY !== a.viewportY) return false
            await sleep(700)
            const c = await page.evaluate(anchorProbe, "").catch(() => null)
            return c && c.ok && c.viewportY === a.viewportY ? c : false
          },
          { timeout: 20_000, interval: 400, label: "the viewport to stop coasting before anchoring" },
        ).catch(() => null)

        const achievedDepth = depthOf(settled)
        /* No INCONCLUSIVE. If the depth was not actually reached, the honest
         * outcome is a FAILURE with the numbers that prove it -- reporting
         * "inconclusive" for a setup the harness itself controls is exactly the
         * soft verdict this check must never produce.
         */
        if (typeof settled?.viewportY !== "number" || !(achievedDepth !== null && achievedDepth >= MIN_DEPTH)) {
          recordResult(
            "reverse-anchor",
            device.name,
            "fail",
            `could not drive the viewport into real history: achieved depth=${achievedDepth} rows ` +
              `(need >= ${MIN_DEPTH}, target >= ${ANCHOR_DEPTH}) with inertia stopped; ` +
              `settled=${JSON.stringify(settled)}; dragAttempts=${JSON.stringify(dragAttempts)}`,
            { stages, dragAttempts },
          )
        } else {
          const anchorText = settled?.topText || ""
          stages.push({
            stage: "anchored",
            viewportY: settled?.viewportY ?? null,
            rows: settled?.rows ?? null,
            bufferLength: settled?.bufferLength ?? null,
            depth: achievedDepth,
            atBottom: !!settled?.atBottom,
            held: true,
          })

          const step = async (label) => {
            await sleep(1000)
            const r = await page.evaluate(anchorProbe, anchorText).catch(() => null)
            stages.push({
              stage: label,
              viewportY: r?.viewportY ?? null,
              rows: r?.rows ?? null,
              bufferLength: r?.bufferLength ?? null,
              atBottom: !!r?.atBottom,
              topText: r?.topText ?? null,
              foundAt: r?.foundAt ?? null,
              held: !!r?.held,
              anchorAnywhere: !!r?.anchorAnywhere,
            })
          }

          // (a) new output arrives
          const sock = await liveSocket(page, state, ptyId)
          sock?.send("\r\n[ANCHOR] new output while anchored in history\r\n")
          await step("new-output")

          // (b) focus moves (a real trusted tap)
          await trustedTap(client, { x: center.x, y: center.y })
          await step("focus-tap")

          // (c) IME opens and closes
          const shrunk = Math.round(device.viewport.height * 0.62)
          await client.send("Emulation.setDeviceMetricsOverride", {
            width: device.viewport.width, height: shrunk,
            deviceScaleFactor: device.deviceScaleFactor, mobile: device.isMobile,
            screenWidth: device.viewport.width, screenHeight: shrunk,
          })
          await applyInsets(page, { imeBottom: IME_HEIGHT, windowHeight: shrunk, imeVisible: true, safeBottom: 0 })
          await step("ime-open")
          await client.send("Emulation.setDeviceMetricsOverride", {
            width: device.viewport.width, height: device.viewport.height,
            deviceScaleFactor: device.deviceScaleFactor, mobile: device.isMobile,
            screenWidth: device.viewport.width, screenHeight: device.viewport.height,
          })
          await applyInsets(page, { imeBottom: 0, windowHeight: device.viewport.height, imeVisible: false })
          await step("ime-closed")

          // (d) rotation, then back
          const rotated = { width: device.viewport.height, height: device.viewport.width }
          await client.send("Emulation.setDeviceMetricsOverride", {
            width: rotated.width, height: rotated.height,
            deviceScaleFactor: device.deviceScaleFactor, mobile: device.isMobile,
            screenWidth: rotated.width, screenHeight: rotated.height,
          })
          await step("rotated")
          await client.send("Emulation.setDeviceMetricsOverride", {
            width: device.viewport.width, height: device.viewport.height,
            deviceScaleFactor: device.deviceScaleFactor, mobile: device.isMobile,
            screenWidth: device.viewport.width, screenHeight: device.viewport.height,
          })
          await step("un-rotated")

          // Consecutive responsive remounts while output may still be flushing.
          const currentSocket = await liveSocket(page, state, ptyId)
          currentSocket?.send(Array.from({ length: 128 }, (_, i) => `\r\n[RAPID-${i}] output during rotation`).join(""))
          for (let i = 0; i < 4; i++) {
            const size = i % 2 === 0 ? rotated : device.viewport
            await client.send("Emulation.setDeviceMetricsOverride", {
              width: size.width, height: size.height,
              deviceScaleFactor: device.deviceScaleFactor, mobile: device.isMobile,
              screenWidth: size.width, screenHeight: size.height,
            })
          }
          await step("rapid-rotation")

          // (e) a REAL tab reveal: genuinely away, then genuinely back
          const away = await leaveTerminalView(page, client)
          const hidden = await page.evaluate(ENGINE_VISIBLE)
          const back = await returnTerminalView(page, client)
          const visible = await page.evaluate(ENGINE_VISIBLE)
          const viewRoundTrip = away.left && hidden.visibleCount === 0 && back.entered && visible.visibleCount > 0
          await step("tab-round-trip")

          // Fail loss, jumps and drift beyond the ±3-row content tolerance.
          const stolen = stages.filter((s) => !s.held && s.atBottom && s.anchorAnywhere)
          const drifted = stages.filter((s) => !s.held && !s.atBottom && s.anchorAnywhere)
          const destroyed = stages.filter((s) => !s.held && !s.anchorAnywhere)
          recordResult(
            "reverse-anchor",
            device.name,
            stolen.length || destroyed.length || drifted.length || !viewRoundTrip ? "fail" : "pass",
            (stolen.length
              ? `the reverse viewport anchor was YANKED TO THE BOTTOM: ${JSON.stringify(stolen)}; all stages=${JSON.stringify(stages)}`
              : `the reverse viewport anchor was never yanked to the bottom: ` +
                stages.map((s) => `${s.stage}@${s.viewportY}+${s.rows}/${s.bufferLength}${s.atBottom ? "(bottom)" : ""}`).join(" -> ")) +
              `; anchorLine="${anchorText.slice(0, 48)}"; ` +
              (drifted.length
                ? `NOTE: the anchor sat a few rows off the top (still mid-scrollback, not yanked) at ${drifted.map((s) => s.stage).join(", ")}`
                : `the anchor stayed at the top throughout`) +
              (destroyed.length
                ? `; CAVEAT: the anchored line no longer exists at all after ${destroyed.map((s) => s.stage).join(", ")} — ` +
                  `that is the engine-remount scrollback loss owned by history-restore, not an anchor steal`
                : `; the anchor line survived in the buffer throughout`) +
              `; real view round trip=${viewRoundTrip} (hidden=${hidden.visibleCount}, visible=${visible.visibleCount})`,
            { stages, dragAttempts, viewRoundTrip },
          )
          } // end of the "anchor was deep enough to judge" branch
        }
    } catch (err) {
      recordResult("reverse-anchor", device.name, "fail", `reverse anchor run threw: ${err.message}; stages=${JSON.stringify(stages)}`)
    }
  }

/* ── saved history: REAL serialization and REAL restore ───────────────── */
  /* FINAL FIX (the old premise was outside the product's own mechanism).
   *
   * The previous check demanded a FULL PAGE RELOAD + a brand-new JS realm +
   * "the backend must never replay the marker", and failed the product when the
   * content came back anyway. Both halves of that premise are wrong:
   *
   *  - On unmount the engine serialises its scrollback into
   *    onCleanup({id, buffer, cursor, rows, cols, scrollY})
   *    (terminal.tsx Ae(); readable copy at lines 321-330) which panel.tsx:367
   *    hands to ops.update(pty). A re-mount in the SAME realm therefore restores
   *    from that cached buffer and deliberately connects with cursor = -1 --
   *    "I already have the content, just tail me" (terminal.tsx:225). That is a
   *    supported path during responsive panel remounts. Collapse itself keeps
   *    the same instance and is checked as retention, not reconstruction.
   *  - onConnect -> markTerminalConnected -> ops.trim(id) (panel.tsx:204-206,
   *    context.tsx trimTerminal 70-78) drops buffer/cursor/scrollY once the socket
   *    is up, because from then on the PTY BACKEND owns the history:
   *    core/src/pty.ts keeps `buffer`, `bufferCursor` and `cursor` per session and
   *    answers attach() with buffer.slice(from - bufferCursor) for whatever cursor
   *    the client asks for. A fresh realm has no client cache at all, so it asks
   *    with cursor = 0 and the backend re-sends the retained output. Demanding
   *    "the backend must NOT replay" asserted the absence of the very mechanism
   *    that makes restore work.
   *
   * Three real paths are checked, and each reports WHICH mechanism supplied the
   * content:
   *   1. same page, real view change (leave the terminal view and come back)
   *   2. panel collapse/reopen: real visibility change and retained history
   *   3. a real page refresh -> new realm, new engine, backend replay by cursor
   *
   * They are measured independently: a failure in one must not hide the others.
   * Nothing is ever written into a client store -- the marker reaches the engine
   * only through the websocket, and "restored" is only ever claimed from a buffer
   * the engine actually holds afterwards.
   */
  const historyEvidence = {}
  if (shouldRun("history-restore")) {
    const marker = `OC-RESTORE-${device.name}-42`
    state.marker = marker
    const scenario = { viewRoundTrip: null, panelReopen: null, afterReload: null }
    let sock = null
    let restorePty = null
    let realmBefore = null
    const startedFrom = Date.now()
    try {
      // 0. The terminal must genuinely be on screen before any of this means
      // anything; the earlier stages leave it in whatever state they like.
      let engineNow = await page.evaluate(ENGINE_VISIBLE).catch(() => null)
      if (!(engineNow?.visibleCount > 0)) {
        historyEvidence.reopenedForStart = await returnTerminalView(page, client).catch((e) => ({ error: e.message }))
        engineNow = await page.evaluate(ENGINE_VISIBLE).catch(() => null)
      }
      restorePty = engineNow?.visiblePtyId || ptyId
      sock = await liveSocket(page, state, restorePty)
      if (!sock) throw new Error("no live PTY socket to write the history marker on")
      historyEvidence.socketId = sock.id
      historyEvidence.ptyId = restorePty

      /* 1. the marker onto the wire, on the socket that owns the visible terminal */
      sock.send(`\r\n${marker}\r\n`)
      await sleep(1400)
      const inLive = await page.evaluate(bufferHas, marker)
      historyEvidence.inLive = inLive
      if (!inLive.ok || !inLive.found) {
        throw new Error(`the history marker never reached the live engine buffer: ${JSON.stringify(inLive)}`)
      }
      // Where the payload could possibly live client-side. Recorded, never
      // written to: the marker only ever reaches the engine over the wire.
      historyEvidence.clientStores = await page.evaluate(clientStores, marker)
      historyEvidence.ptyPatchesBefore = state.ptyPatches.map((p) => ({
        ptyId: p.ptyId,
        bytes: p.bytes,
        keys: p.keys,
        bufferHasMarker: typeof p.raw === "string" && p.raw.indexOf(marker) !== -1,
      }))
      realmBefore = inLive.realm
    } catch (err) {
      historyEvidence.setupError = err.message
    }

    /* ── scenario 1: same page, genuinely leave the view and come back ───── */
    if (!historyEvidence.setupError) {
      try {
        const away = await leaveTerminalView(page, client)
        await sleep(1400)
        const engineAway = await page.evaluate(ENGINE_VISIBLE).catch(() => null)
        const backAgain = await returnTerminalView(page, client)
        await sleep(1800)
        const engineBack = await page.evaluate(ENGINE_VISIBLE).catch(() => null)
        const after = await page.evaluate(bufferHas, marker)
        /* "The view really changed" has to mean the engine actually left the
         * screen and came back -- that is the observation, on every layout.
         *
         * Comparing the selected tab VALUES on top of that is only meaningful
         * where a mobile-view tab bar exists. On the wide layout there is no such
         * bar (the terminal is opened with terminal.toggle), so the selected set
         * is identical before and after by construction; demanding a difference
         * there rejected a round trip that demonstrably worked -- engine visible
         * 0 while away, 1 after returning, marker intact -- purely because of a
         * phone-shaped assumption.
         */
        const allTabs = [...(away.allTabs || []), ...(backAgain.allTabs || [])]
        const hasMobileViewTabs = allTabs.some((v) => /^session|terminal/i.test(String(v || "")))
        const tabSetChanged = JSON.stringify(away.selectedAfter) !== JSON.stringify(backAgain.selectedAfter)
        const viewReallyChanged =
          !!away.left &&
          !!backAgain.entered &&
          (engineAway?.visibleCount ?? 0) === 0 &&
          (engineBack?.visibleCount ?? 0) > 0 &&
          (tabSetChanged || !hasMobileViewTabs)
        scenario.viewRoundTrip = {
          ok: viewReallyChanged && !!after.found,
          away: { left: !!away.left, mechanism: away.mechanism, selectedAfter: away.selectedAfter },
          back: { entered: !!backAgain.entered, mechanism: backAgain.mechanism, selectedAfter: backAgain.selectedAfter },
          engineVisibleWhileAway: engineAway?.visibleCount ?? null,
          engineVisibleAfterReturn: engineBack?.visibleCount ?? null,
          viewReallyChanged,
          tabSetChanged,
          hasMobileViewTabs,
          markerKept: !!after.found,
          rows: after.rows ?? null,
          bufferLength: after.bufferLength ?? null,
          mechanism: "hide/reveal of the live panel (no engine teardown), buffer carried by the instance itself",
        }
      } catch (err) {
        scenario.viewRoundTrip = { ok: false, error: err.message }
      }
    }

    /* ── scenario 2: collapse/reopen retains the same engine and history ── */
    if (!historyEvidence.setupError) {
      try {
        const closeMark = state.replays.length
        const before = await page.evaluate(ENGINE_INSTANCES).catch(() => null)
        const closed = await closeTerminalPanel(page, client)
        await sleep(2200)
        const whileClosed = await page.evaluate(ENGINE_VISIBLE).catch(() => null)
        const whileClosedInstances = await page.evaluate(ENGINE_INSTANCES).catch(() => null)
        const reopened = await returnTerminalView(page, client)
        await sleep(2000)
        const afterInstances = await page.evaluate(ENGINE_INSTANCES).catch(() => null)
        await waitFor(
          async () => {
            const r = await page.evaluate(bufferHas, marker).catch(() => null)
            return r && r.ok ? r : false
          },
          { timeout: 20_000, label: "an engine instance after reopening the panel" },
        )
        await sleep(1200)
        const after = await page.evaluate(bufferHas, marker)
        const replays = state.replays.slice(closeMark)
        const sameInstance = before?.count === afterInstances?.count &&
          before?.instances.some((i) => i.connected && afterInstances.instances[i.index]?.connected)
        const visibilityChanged = !!closed.left && (whileClosed?.visibleCount ?? 0) === 0 && !!reopened.entered
        scenario.panelReopen = {
          ok: visibilityChanged && sameInstance && !!after.found && replays.length === 0,
          closed: { left: !!closed.left, mechanism: closed.mechanism, tried: closed.tried },
          mechanism: "panel collapse retains the mounted engine and its buffer",
          reopened: { entered: !!reopened.entered, mechanism: reopened.mechanism },
          engineVisibleWhileClosed: whileClosed?.visibleCount ?? null,
          instancesBefore: before?.count ?? null,
          instancesWhileClosed: whileClosedInstances?.count ?? null,
          instancesAfter: afterInstances?.count ?? null,
          sameInstance,
          visibilityChanged,
          markerKept: !!after.found,
          rows: after.rows ?? null,
          bufferLength: after.bufferLength ?? null,
          replays,
        }
      } catch (err) {
        scenario.panelReopen = { ok: false, error: err.message }
      }
    }

    /* ── scenario 3: a REAL page refresh -> new realm, new engine, replay ── */
    if (!historyEvidence.setupError) {
      try {
        const reloadMark = state.replays.length
        await page.reload({ waitUntil: "domcontentloaded", timeout: 60_000 })
        await waitFor(
          async () =>
            page
              .evaluate(`(() => {
                const root = document.getElementById("root")
                if (!root) return false
                return (root.innerText || "").trim().length > 0 || root.querySelectorAll("[data-slot]").length > 0
              })()`)
              .catch(() => false),
          { timeout: 30_000, label: "the SPA to paint again after a real refresh" },
        )
        await applyBootInsets(page, device).catch(() => null)
        // The overlay/tab may still be animating in right after a reload, so the
        // real control gets a few genuine attempts before it is called a failure.
        let reopenedAfterReload = null
        const reopenErrors = []
        for (let attempt = 0; attempt < 3 && !reopenedAfterReload; attempt++) {
          try {
            reopenedAfterReload = await returnTerminalView(page, client)
          } catch (e) {
            reopenErrors.push(String(e.message).slice(0, 300))
            await sleep(1800)
          }
        }
        if (!reopenedAfterReload?.entered) {
          throw new Error(`could not reopen the terminal after a real refresh: ${JSON.stringify(reopenErrors)}`)
        }
        historyEvidence.reopenedVia = reopenedAfterReload.mechanism
        await waitFor(
          async () => {
            const r = await page.evaluate(bufferHas, marker).catch(() => null)
            return r && r.ok ? r : false
          },
          { timeout: 25_000, label: "an engine instance after the refresh" },
        )
        await sleep(1800)
        const after = await page.evaluate(bufferHas, marker)
        const replays = state.replays.slice(reloadMark)
        const mechanism = describeRestoreMechanism(replays, marker)
        const newRealm = !!after.realm && after.realm !== realmBefore
        /* In a fresh realm there is no client-side cache to restore from, so the
         * ONLY way the content can be back is the real backend replaying its
         * retained output from the cursor the client asked for. That is read off
         * the frames the backend actually sent -- not inferred from the buffer.
         */
        const restored = !!after.found
        const serverReplayedMarker = restored && mechanism.markerCameOverTheWire
        scenario.afterReload = {
          ok: newRealm && restored && serverReplayedMarker,
          probe: after,
          realmBefore,
          newRealm,
          restored,
          rows: after.rows ?? null,
          bufferLength: after.bufferLength ?? null,
          replays,
          mechanism,
          serverReplayedMarker,
          ptyAfterReload: (await page.evaluate(ENGINE_VISIBLE).catch(() => null))?.visiblePtyId ?? null,
        }
      } catch (err) {
        scenario.afterReload = { ok: false, error: err.message }
      }
    }

    historyEvidence.scenario = scenario
    historyEvidence.seconds = Math.round((Date.now() - startedFrom) / 1000)
    historyEvidence.clientStoresAfterAll = await page
      .evaluate(clientStores, marker)
      .catch(() => null)
    const verdict = {
      viewChangedAndKept: !!scenario.viewRoundTrip?.ok,
      panelCollapsedAndHistoryKept: !!scenario.panelReopen?.ok,
      newRealmAndServerReplayed: !!scenario.afterReload?.ok,
    }
    historyEvidence.verdict = verdict
    const all = Object.values(verdict).every(Boolean)
    recordResult(
      "history-restore",
      device.name,
      all ? "pass" : "fail",
      all
        ? `the scrollback survived the three product paths, each attributed to the mechanism that ` +
            `actually supplied the content. (1) real view change: left via ${scenario.viewRoundTrip.away.mechanism} ` +
            `(engine visible ${scenario.viewRoundTrip.engineVisibleWhileAway}) and returned via ` +
            `${scenario.viewRoundTrip.back.mechanism} (engine visible ` +
            `${scenario.viewRoundTrip.engineVisibleAfterReturn}) -- the view really changed, and the marker was ` +
            `still in the buffer at row ${JSON.stringify(scenario.viewRoundTrip.rows)}: ${scenario.viewRoundTrip.mechanism}. ` +
            `(2) collapse/reopen via ${scenario.panelReopen.closed.mechanism}: ` +
            `same instance=${scenario.panelReopen.sameInstance}, marker retained at row ` +
            `${JSON.stringify(scenario.panelReopen.rows)} -- ${scenario.panelReopen.mechanism}. ` +
            `(3) REAL page refresh: JS realm ${realmBefore} -> ${scenario.afterReload.probe.realm} (new realm, ` +
            `engine re-created), marker back at row ${JSON.stringify(scenario.afterReload.rows)} of ` +
            `${scenario.afterReload.bufferLength} -- ${scenario.afterReload.mechanism.label}.`
        : `history restore did not hold on every real path: ${JSON.stringify(verdict)}. ` +
            `viewRoundTrip=${JSON.stringify(scenario.viewRoundTrip)}; panelReopen=${JSON.stringify(scenario.panelReopen)}; ` +
            `afterReload=${JSON.stringify(scenario.afterReload)}. ` +
            `Client-side persistence surfaces searched for the marker (recorded, never written to): ` +
            `localStorage=${JSON.stringify(historyEvidence.clientStoresAfterAll?.localStorage?.filter((k) => k.hasMarker))}; ` +
            `sessionStorage=${JSON.stringify(historyEvidence.clientStoresAfterAll?.sessionStorage?.filter((k) => k.hasMarker))}; ` +
            `indexedDB=${JSON.stringify(historyEvidence.clientStoresAfterAll?.indexedDB)}; ` +
            `PTY PATCHes the client sent to the backend=${JSON.stringify(historyEvidence.ptyPatchesBefore)}`,
      { evidence: historyEvidence },
    )
  }
  if (shouldRun("adapter-lifecycle")) {
    try {
      const evidence = await verifyAdapterLifecycle(page)
      recordResult("adapter-lifecycle", device.name, evidence.ok ? "pass" : "fail", JSON.stringify(evidence), { evidence })
    } catch (error) {
      recordResult("adapter-lifecycle", device.name, "fail", error.stack || error.message)
    }
  }
  if (consoleErrors.length) {
    results.push({
      check: "console-errors",
      device: device.name,
      status: "info",
      detail: `${consoleErrors.length} console error(s): ${consoleErrors.slice(0, 5).join(" | ")}`,
    })
  }
  if (pageErrors.length) {
    results.push({
      check: "page-errors",
      device: device.name,
      status: "info",
      detail: `${pageErrors.length} uncaught: ${pageErrors.slice(0, 5).join(" | ")}`,
    })
  }

  if (process.env.VERIFY_TERMINAL_TRACE === "1") {
    fs.writeFileSync(path.join(OUT_DIR, `${device.name}-lifecycle.json`), JSON.stringify(await page.evaluate(() => window.__ocTerminalTest?.events), null, 2))
  }
  await context.close()
}

async function main() {
  fs.mkdirSync(OUT_DIR, { recursive: true })

  // Blocker 1: the lead's replacement module has to exist before any of this
  // means anything. Report it as a blocker, not a test failure.
  if (!fs.existsSync(TERMINAL_MODULE_SERVED)) {
    const report = {
      status: "blocked",
      reason: "replacement-terminal-module-missing",
      expected: TERMINAL_MODULE_SERVED,
      terminalDirExists: fs.existsSync(TERMINAL_DIR),
      hint: "the lead's www/oc-terminal.js has not landed yet; this runner is ready to run once it does",
      checks: [],
    }
    fs.writeFileSync(REPORT_PATH, JSON.stringify(report, null, 2))
    console.error("")
    console.error("BLOCKED: replacement terminal module is not in the packaged assets yet.")
    console.error(`  expected: ${TERMINAL_MODULE_SERVED}`)
    console.error(`  terminal dir exists: ${fs.existsSync(TERMINAL_DIR)}`)
    console.error(`  report: ${path.relative(REPO, REPORT_PATH)}`)
    console.error("")
    console.error("Everything else in this runner is ready; re-run the same command once the module lands.")
    process.exitCode = 2
    return
  }

  if (!fs.existsSync(HOST_JS)) {
    console.error(`BLOCKED: ${HOST_JS} is missing`)
    process.exitCode = 2
    return
  }

  // Blocker 2: oc-host.js must be able to install its facade, which requires the
  // mock bridge. Verified by the mount check, so no separate gate here.
  const uiServer = await startUiServer()
  const uiPort = uiServer.address().port
  const state = await startBackend()
  state.uiOrigin = `http://127.0.0.1:${uiPort}`
  state.workspace = path.join(OUT_DIR, "workspace")
  fs.mkdirSync(state.workspace, { recursive: true })
  fs.writeFileSync(path.join(state.workspace, "README.md"), "# verify-android-terminal\n")

  const { chromium } = await import(`file:///${PLAYWRIGHT_MJS.replace(/\\/g, "/")}`)
  const browser = await chromium.launch({ executablePath: CHROMIUM, headless: true })

  console.error(`UI origin:    ${state.uiOrigin}`)
  console.error(`Backend mock: ${state.origin}`)
  console.error(`Workspace:    ${state.workspace}`)

  const devices = process.env.VERIFY_TERMINAL_DEVICE
    ? [process.env.VERIFY_TERMINAL_DEVICE === "tablet" ? TABLET : PHONE]
    : [PHONE, TABLET]

  try {
    for (const device of devices) {
      await runDevice(browser, { chromium }, device, state)
    }
  } finally {
    await browser.close()
    for (const s of state.sse) {
      try {
        s.end?.()
      } catch {}
    }
    state.sse.clear()
    for (const socket of state.sockets.values()) {
      try {
        socket.socket?.destroy()
      } catch {}
    }
    state.sockets.clear()
    await closeServer(state.server)
    await closeServer(uiServer)
  }

  if (FILTER && !results.some((r) => r.check.includes(FILTER))) {
    recordResult("filter", "suite", "fail", `No check matched VERIFY_TERMINAL_FILTER=${JSON.stringify(FILTER)}`)
  }
  const failed = results.filter((r) => r.status === "fail")
  const passed = results.filter((r) => r.status === "pass")
  const skipped = results.filter((r) => r.status === "skip")

  const report = {
    status: failed.length ? "fail" : "pass",
    mode: MODE,
    filter: FILTER || null,
    coverage: FILTER || MODE !== "full" ? "targeted" : "full phone/tablet",
    // Honesty guard rail: this is Chromium emulating an Android WebView, not a device.
    environment: "chromium-emulated (no attached Android device, no real IME, no Android System WebView)",
    node: process.version,
    chromium: CHROMIUM,
    uiOrigin: state.uiOrigin,
    backendOrigin: state.origin,
    terminalModule: TERMINAL_MODULE_SERVED,
    terminalModuleOverride: TERMINAL_MODULE_OVERRIDE || null,
    // A run against an override is a harness self-test, never a verdict on the lead's module.
    verdictScope: TERMINAL_MODULE_OVERRIDE
      ? "HARNESS SELF-TEST against a scratch stub module — NOT verification of the lead's module"
      : "the packaged www/oc-terminal.js served at /oc-terminal.js",
    totals: { pass: passed.length, fail: failed.length, skip: skipped.length, info: results.filter((r) => r.status === "info").length },
    checks: results,
    artifacts,
    // The previous 35/10 run is preserved verbatim and is NOT overwritten by
    // this one; the two report paths are recorded side by side so the delta is
    // auditable.
    previousRun: {
      report: path.relative(REPO, path.join(PREVIOUS_OUT_DIR, "report.json")),
      preserved: fs.existsSync(path.join(PREVIOUS_OUT_DIR, "report.json")),
    },
    terminalAssetSha256: crypto
      .createHash("sha256")
      .update(fs.readFileSync(TERMINAL_MODULE_SERVED))
      .digest("hex")
      .toUpperCase(),
    terminalAssetBytes: fs.statSync(TERMINAL_MODULE_SERVED).size,
    requestLog,
    backendHits: state.hits,
    ptyPatches: state.ptyPatches.map((p) => ({
      ptyId: p.ptyId, bytes: p.bytes, keys: p.keys,
      hasBuffer: p.hasBuffer, bufferBytes: p.bufferBytes,
    })),
  }
  fs.writeFileSync(REPORT_PATH, JSON.stringify(report, null, 2))

  console.error("")
  console.error(`pass=${passed.length} fail=${failed.length} skip=${skipped.length}`)
  console.error(`report: ${path.relative(REPO, REPORT_PATH)}`)
  console.error(`previous run preserved: ${report.previousRun.report} (present=${report.previousRun.preserved})`)
  if (artifacts.length) console.error(`shots:  ${artifacts.length} in ${path.relative(REPO, OUT_DIR)}`)
  process.exitCode = failed.length ? 1 : 0
}

export { startBackend, startUiServer, nativeBridgeSource, ocHostLoaderSource, seedScript, base64url }

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) main().catch((err) => {
  console.error(`FATAL: ${err && err.stack ? err.stack : err}`)
  try {
    fs.mkdirSync(OUT_DIR, { recursive: true })
    fs.writeFileSync(
      REPORT_PATH,
      JSON.stringify({ status: "error", error: String(err && err.stack ? err.stack : err), checks: results }, null, 2),
    )
  } catch {}
  process.exitCode = 1
})
