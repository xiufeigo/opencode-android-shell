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
 *       `/_assets/ghostty-web-*.js`  ->  `www/oc-terminal.js`
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

const OUT_DIR = path.join(REPO, "research/tmp/verify-android-terminal")
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
    if (/^\/_assets\/ghostty-web-[^/]+\.js$/.test(pathname)) {
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
 * A PTY connection. The test drives output in; whatever the UI sends back is
 * recorded verbatim so "input reached the WS exactly once" is a real observation.
 */
class MockPtySocket {
  constructor(id) {
    this.id = id
    this.socket = null
    this.received = []
    this.closed = false
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
    this.socket.write(wsFrame(text, 0x1))
    return true
  }
  /** The control frame production terminal.tsx parses for the resume cursor. */
  sendCursor(cursor) {
    if (!this.socket || this.socket.destroyed) return false
    const json = JSON.stringify({ cursor })
    const bytes = Buffer.concat([Buffer.from([0]), Buffer.from(json, "utf8")])
    this.socket.write(wsFrame(bytes, 0x2))
    return true
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
    sse: new Set(),
    hits: [],
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
          try {
            const body = JSON.parse(Buffer.concat(chunks).toString("utf8") || "{}")
            const size = body.size || body.title?.size
            if (size?.cols && size?.rows) {
              pty.cols = size.cols
              pty.rows = size.rows
            }
            const title = body.title
            if (typeof title === "string") pty.title = title
          } catch {}
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
    const pty = new MockPtySocket(id)
    state.ptys.set(id, ptyInfo(id, { cwd: state.workspace }))
    state.sockets.set(id, pty)
    record({ path: url.pathname, status: 101, via: "backend:pty-ws" })
    pty.attach(socket)
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
const TEST_HOOK_SOURCE = `window.__ocTerminalTest = { instances: [] }`

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
  if (!t) return { ok: false, reason: "no-instance", count: list.length }

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

/** Sends >= 250 numbered lines down the real PTY websocket. */
function seedOutput(state, ptyId, lines = 260) {
  const socket = state.sockets.get(ptyId)
  if (!socket) throw new Error(`no mock PTY socket for ${ptyId}`)
  let cursor = 0
  for (let i = 1; i <= lines; i++) {
    const line = `[${String(i).padStart(4, " ")}] line-${String(i).padStart(4, " ")} $(".".repeat(i % 17 + 3))}\r\n`
    cursor += line.length
    if (!socket.send(line)) throw new Error("pty socket closed while seeding")
  }
  socket.sendCursor(cursor)
  return { lines, cursor }
}

/* ─────────────────────────── the run ─────────────────────────── */

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
    console.error(`  boot diagnostics -> research/tmp/verify-android-terminal/boot-${device.name}.json`)
    await context.close()
    return
  }

  // ── open the terminal through the app's own UI ------------------------
  // On a phone profile the packaged UI reaches the terminal through the session
  // mobile view tab (review/view.tsx renders views
  // ["session","changes","files","terminal"]); on wide layouts it is the
  // `terminal.toggle` keybind. Try the real control first, then the keybind.
  let ptyId = null
  let openedVia = null
  try {
    const tabPoint = await page.evaluate(`(() => {
      const t =
        document.querySelector('[data-slot="tabs-trigger"][data-value="terminal"]') ||
        document.querySelector('[data-value="terminal"]')
      if (!t) return false
      const r = t.getBoundingClientRect()
      if (!r.width || !r.height) return false
      return { x: r.x + r.width / 2, y: r.y + r.height / 2 }
    })()`)

    if (tabPoint) {
      await trustedTap(client, tabPoint)
      openedVia = "mobile-view-tab"
    }

    // The keybind is always safe to also try; it is a no-op if already open.
    await client.send("Input.dispatchKeyEvent", {
      type: "rawKeyDown",
      key: "`",
      code: "Backquote",
      windowsVirtualKeyCode: 192,
      nativeVirtualKeyCode: 192,
      modifiers: 2, // Ctrl
    })
    await client.send("Input.dispatchKeyEvent", {
      type: "keyUp",
      key: "`",
      code: "Backquote",
      windowsVirtualKeyCode: 192,
      nativeVirtualKeyCode: 192,
      modifiers: 2,
    })
    if (!openedVia) openedVia = "terminal.toggle keybind"

    ptyId = await waitFor(
      () => {
        const ids = [...state.ptys.keys()]
        return ids.length ? ids[ids.length - 1] : false
      },
      { timeout: 25_000, label: "PTY creation through the production terminal panel" },
    )
    if (shouldRun("terminal-created")) {
      recordResult(
        "terminal-created",
        device.name,
        "pass",
        `production UI created PTY ${ptyId} (opened via ${openedVia})`,
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
    recordResult("engine-instance", device.name, "fail", `no Terminal instance appended to window.__ocTerminalTest: ${err.message}`)
    await shot(page, `${device.name}-03-no-engine`)
    await context.close()
    return
  }

  // ── the replacement module must be requested by the production chunk ──
  if (shouldRun("module-route")) {
    const ghosttyHits = requestLog
      .slice(baseline)
      .filter((r) => /^\/_assets\/ghostty-web-[^/]+\.js$/.test(r.path))
    const served = ghosttyHits.find((r) => r.via === "terminal:oc-terminal.mjs")
    if (served) {
      recordResult(
        "module-route",
        device.name,
        "pass",
        `production chunk requested ${served.path} and the HTTP route served the replacement module (${served.bytes} bytes) from ${TERMINAL_MODULE_SERVED}`,
      )
    } else if (ghosttyHits.length) {
      recordResult(
        "module-route",
        device.name,
        "fail",
        `ghostty chunk requested (${ghosttyHits[0].path}) but served via ${ghosttyHits[0].via} — replacement not wired`,
      )
    } else {
      recordResult(
        "module-route",
        device.name,
        "fail",
        "production page never requested a ghostty-web chunk, so the replacement slot was never exercised",
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

  // ── seed >= 250 numbered lines over the real websocket ─────────────────
  let seeded = null
  try {
    seeded = seedOutput(state, ptyId, 260)
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
    const p = await page.evaluate(PROBE)
    const r = (p && (p.canvasRect || p.panelRect)) || null
    if (!r || !r.width || !r.height) return null
    return { x: r.x + r.width / 2, y: r.y + r.height / 2, rect: r }
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
      const socket = state.sockets.get(ptyId)
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
    const noIme = await page.evaluate(PROBE)
    const grew = noIme.viewport?.innerHeight
    if (typeof grew === "number" && grew >= device.viewport.height - 2 &&
        noIme.connected && Math.abs(noIme.frameBottom - noIme.containerBottom) <= 2 &&
        noIme.rows === before.rows) {
      recordResult("ime-hide", device.name, "pass", `viewport grew back to ${grew} after keyboard hide`)
    } else {
      recordResult(
        "ime-hide",
        device.name,
        "fail",
        `keyboard hide geometry: before=${JSON.stringify(before)}, after=${JSON.stringify(noIme)}`,
      )
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
    const center = await canvasCenter()
    const socket = state.sockets.get(ptyId)
    if (!center || !socket) {
      recordResult("ime-input", device.name, "fail", "no terminal box or PTY socket to type into")
    } else {
      await trustedTap(client, { x: center.x, y: center.y })
      await sleep(400)

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
        `IME composition commit produced ${afterComposition} websocket frame(s) (expected exactly 1): ${JSON.stringify(
          socket.received.slice(markCommit),
        )}`,
      )

      // Paste: the panel listens for a real `paste` ClipboardEvent.
      const markPaste = socket.received.length
      const pasted = await page.evaluate(() => {
        const panel = document.querySelector('[data-component="terminal"]')
        if (!panel) return false
        const dt = new DataTransfer()
        dt.setData("text/plain", "pasted-line-42")
        const ev = new ClipboardEvent("paste", { clipboardData: dt, bubbles: true, cancelable: true })
        panel.dispatchEvent(ev)
        return true
      })
      await sleep(700)
      const afterPaste = socket.received.length - markPaste
      if (pasted) {
        recordResult(
          "paste-input",
          device.name,
          afterPaste === 1 ? "pass" : "fail",
          `paste produced ${afterPaste} websocket frame(s) (expected exactly 1): ${JSON.stringify(
            socket.received.slice(markPaste),
          )}`,
        )
      } else {
        recordResult("paste-input", device.name, "fail", "could not locate the terminal panel for paste")
      }
      await shot(page, `${device.name}-10-input`)
    }
  }

  /* ── bottom-follow + cursor recovery + history retained ──────────────── */
  if (shouldRun("bottom-follow")) {
    const socket = state.sockets.get(ptyId)
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
      // Distinguish "scrollback truncated in place" from "engine was re-created
      // and came back empty" — the latter is a remount/restoration problem, not
      // a scrollback problem, and the two need different fixes.
      const remounted = typeof after.count === "number" && after.count > 1
      if (remounted) {
        recordResult(
          "history-retained",
          device.name,
          "info",
          `engine was re-created ${after.count} times during the run (PTY reconnects after resize/rotation) and the new ` +
            `instance started with buffer.active.length=${after.bufferLength} instead of restoring the ${seeded?.lines ?? "seeded"} seeded lines`,
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

  /* ── saved history cleanup / restoration ─────────────────────────────── */
  if (shouldRun("history-restore")) {
    try {
      const before = await page.evaluate(PROBE)
      const historyMarker = `OC-RESTORE-${device.name}-42`
      state.sockets.get(ptyId).send(`\r\n${historyMarker}\r\n`)
      await sleep(400)
      const switchView = async (value) => {
        const point = await page.evaluate((value) => {
          const tab = document.querySelector(`[data-slot="tabs-trigger"][data-value="${value}"]`)
          const r = tab?.getBoundingClientRect()
          return r?.width ? { x: r.x + r.width / 2, y: r.y + r.height / 2 } : null
        }, value)
        if (point) return trustedTap(client, point)
        await page.keyboard.press("Control+Backquote")
      }
      await switchView("session")
      await sleep(1200)
      const persisted = await page.evaluate((marker) => {
        const out = {}
        for (let i = 0; i < localStorage.length; i++) {
          const key = localStorage.key(i)
          if (key?.includes("terminal")) {
            const value = localStorage.getItem(key) || ""
            out[key] = { bytes: value.length, marker: value.includes(marker) }
          }
        }
        return out
      }, historyMarker)
      recordResult("history-restore", device.name,
        Object.values(persisted).some((value) => value.marker) ? "pass" : "fail",
        `saved terminal history contains marker: ${JSON.stringify(persisted)}`)
      await switchView("terminal")
      await sleep(1500)
      const after = await page.evaluate(PROBE)
      const restored = await page.evaluate((marker) => {
        const list = window.__ocTerminalTest.instances
        const buffer = list[list.length - 1].buffer.active
        return Array.from({ length: buffer.length }, (_, row) => buffer.getLine(row)?.translateToString(true)).some((line) => line.includes(marker))
      }, historyMarker)
      recordResult("history-restored", device.name, restored ? "pass" : "fail",
        `history marker restored=${restored}, buffer lines ${before.bufferLength} -> ${after.bufferLength}`)
    } catch (err) {
      recordResult("history-restore", device.name, "fail", err.message)
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
  state.workspace = path.join(REPO, "research/tmp/verify-android-terminal/workspace")
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

  const failed = results.filter((r) => r.status === "fail")
  const passed = results.filter((r) => r.status === "pass")
  const skipped = results.filter((r) => r.status === "skip")

  const report = {
    status: failed.length ? "fail" : "pass",
    mode: MODE,
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
      : "the packaged www/oc-terminal.js served at the /_assets/ghostty-web-*.js route",
    totals: { pass: passed.length, fail: failed.length, skip: skipped.length },
    checks: results,
    artifacts,
    requestLog,
    backendHits: state.hits,
  }
  fs.writeFileSync(REPORT_PATH, JSON.stringify(report, null, 2))

  console.error("")
  console.error(`pass=${passed.length} fail=${failed.length} skip=${skipped.length}`)
  console.error(`report: ${path.relative(REPO, REPORT_PATH)}`)
  if (artifacts.length) console.error(`shots:  ${artifacts.length} in research/tmp/verify-android-terminal`)
  process.exitCode = failed.length ? 1 : 0
}

export { startBackend, seedScript, base64url }

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